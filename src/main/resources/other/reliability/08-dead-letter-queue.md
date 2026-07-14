# Dead Letter Queue и poison messages

## Проблема

Пятница, 18:40. Ты уже собираешься закрывать ноут, и тут прилетает алерт: `consumer lag` по топику `order` за 20 минут вырос с нуля до 2 миллионов сообщений. Заказы не создаются, продавцы в поддержке, менеджеры в чате.

Лезешь в логи. Одна и та же ошибка, десятки тысяч строк в секунду:

```
ERROR OrderListener - Failed to process record offset=48213
java.lang.NullPointerException: Cannot invoke "BigDecimal.scale()" because "price" is null
	at OrderMapper.toEntity(OrderMapper.java:64)
```

Всё встало из-за **одного** сообщения на офсете 48213. В нём `price = null` — новый релиз upstream-сервиса начал в редком кейсе (товар «под заказ») слать заказы без цены. Твой консьюмер на таком сообщении бросает исключение. А дальше — классика Kafka:

```java
@KafkaListener(topics = "order", groupId = "seller-order-service")
public void onOrder(KafkaOrder order) {
    // маппинг падает с NPE, если order.price() == null
    var entity = orderMapper.toEntity(order);
    orderService.create(entity);          // сюда управление не доходит
    // offset коммитится только при успешном выходе из метода
}
```

Логика, которая выглядит абсолютно правильно: если обработка упала — не коммитим офсет, значит сообщение обработается ещё раз, ничего не потеряем. Именно так работает `at-least-once`.

Но вот беда: сообщение-то **детерминированно ядовитое**. Оно упадёт и на второй попытке, и на двухсотой — данные не поменяются сами собой. Kafka (или Spring Kafka с дефолтным бесконечным ретраем) послушно переигрывает один и тот же офсет по кругу. Партиция встаёт колом: за «отравленным» сообщением стоят ещё сотни тысяч совершенно валидных заказов, и они **не поедут никогда**, потому что консьюмер не может перешагнуть офсет 48213.

Это и есть **poison message** (ядовитое сообщение): запись, обработка которой всегда фейлится по причине, которая не исчезнет от повтора. А наивный `at-least-once` без «аварийного выхода» превращает одно битое сообщение в полный простой партиции.

Цена вопроса:
- **Head-of-line blocking** — одно сообщение блокирует всю очередь за ним.
- **Retry storm** — бесконечные ретраи жгут CPU, забивают логи, крутят внешние вызовы (если обработка ходит в БД/HTTP).
- **Расследование вслепую** — среди миллионов одинаковых стектрейсов найти то самое битое сообщение и понять, что с ним не так, тяжело.

Нам нужен механизм, который скажет: «это сообщение N раз не смогло обработаться — отложи его в сторону и иди дальше». Сторона называется **Dead Letter Queue**.

## Что это и когда применять

**Dead Letter Queue (DLQ)**, в Kafka — **Dead Letter Topic (DLT)** — это отдельная очередь/топик, куда отправляются сообщения, которые не удалось обработать после исчерпания разумного числа попыток. Обработчик, вместо того чтобы вечно долбиться в ядовитое сообщение, после N неудач **перекладывает его в DLQ, коммитит офсет основного топика и едет дальше**.

Простыми словами: DLQ — это «папка Проблемные» для сообщений. Основной конвейер не должен останавливаться из-за одной битой детали — её снимают с ленты и кладут в ящик, чтобы разобрать позже руками или отдельным процессом.

Что это даёт:
- **Разблокирует очередь.** Валидные сообщения за poison-записью обрабатываются сразу.
- **Не теряет данные.** Битое сообщение не выбрасывается — оно сохранено в DLQ со всем контекстом (стектрейс,原топик, офсет, заголовки).
- **Даёт точку наблюдения.** Метрика «сколько сообщений в DLQ» — прямой сигнал качества входных данных и багов обработки. Алерт на рост DLQ ловит проблему раньше, чем клиенты.
- **Позволяет reprocess.** После фикса кода сообщения из DLQ можно переиграть обратно в основной топик.

**Когда НЕ нужно / частые ошибки:**

- **DLQ — не замена ретраям транзиентных ошибок.** Если ошибка временная (таймаут БД, 503 от соседнего сервиса, дедлок) — сообщение надо **ретраить с backoff**, оно обработается само. Отправлять транзиентную ошибку сразу в DLQ — значит закапывать сообщения, которые прошли бы со второй попытки. DLQ — для **невосстановимых** (non-retryable) ошибок и для тех, что пережили все ретраи.

- **Не сваливай в одну DLQ всё подряд без классификации ошибок.** `NullPointerException` из-за битых данных и `SocketTimeoutException` из-за упавшего Postgres — принципиально разные вещи. Первую — в DLQ сразу, вторую — ретраить. Об этом ниже.

- **DLQ без потребителя — это /dev/null с лишними шагами.** Если в DLQ никто не смотрит (нет алерта, нет процесса разбора) — вы просто тихо теряете данные и узнаете об этом от бизнеса через неделю. DLQ обязана иметь: мониторинг размера, алерт и регламент разбора.

- **Не применяй DLQ там, где нужен строгий порядок и остановка.** Иногда бизнес-требование — «если сломалось, встань и разбудите человека, но НЕ обрабатывай ничего дальше» (например, финансовый реестр, где пропуск записи недопустим). Тогда head-of-line blocking — это фича, а не баг, и DLQ противопоказана. Такие кейсы редки, но они есть.

## Как это работает

Ключевая идея — конвейер с тремя исходами на каждое сообщение: **успех**, **повторяемая ошибка → ретрай**, **непроходимая ошибка / исчерпаны ретраи → DLQ**.

```
                 ┌─────────────────────────────────────────────┐
                 │              основной топик: order            │
                 └───────────────────────┬─────────────────────┘
                                         │ poll
                                         ▼
                              ┌────────────────────┐
                              │   process(record)  │
                              └─────────┬──────────┘
                     success            │            failure
                ┌───────────────────────┼───────────────────────┐
                ▼                        │                       ▼
        commit offset,          классификация ошибки:    ┌───────────────┐
        идём дальше             retryable / fatal         │ fatal (данные)│
                                        │                 └───────┬───────┘
                          retryable     │                        │
                                        ▼                         │
                        ┌───────────────────────────┐            │
                        │  attempt < N ?             │            │
                        │  backoff (delay + jitter)  │            │
                        │  повтор обработки          │            │
                        └───────┬───────────┬───────┘            │
                        да, повтор │     нет (исчерпано)          │
                                   │           │                 │
                                   ▼           ▼                 ▼
                             (назад в     ┌──────────────────────────────┐
                              process)    │      Dead Letter Topic        │
                                          │  order.DLT                    │
                                          │  + headers: exception,        │
                                          │    original-topic, offset,    │
                                          │    stacktrace, timestamp      │
                                          └──────────────┬───────────────┘
                                                         │
                                     ┌───────────────────┴───────────────┐
                                     ▼                                   ▼
                          алерт «DLQ растёт»               ручной/авто reprocess
                                                           после фикса кода
```

Шаги:

1. **Читаем сообщение** из основного топика.
2. **Обрабатываем.** Успех → коммит офсета, следующий.
3. **Классифицируем ошибку.** Это самый важный и самый забываемый шаг:
   - **Fatal / non-retryable** — данные битые или бизнес-правило нарушено: `null` в обязательном поле, невалидный JSON, `ConstraintViolation`, неизвестный enum, «продавец не существует». Повтор не поможет → **сразу в DLQ**, ретраи пропускаем.
   - **Retryable / transient** — инфраструктура моргнула: таймаут, `503`, `DeadlockLoserDataAccessException`, `OptimisticLockException`, недоступность соседнего сервиса. Повтор с высокой вероятностью пройдёт → **ретраим**.
4. **Ретраим с backoff.** Между попытками — задержка, растущая (exponential backoff), обязательно с **jitter** (случайным разбросом), чтобы тысячи консьюмеров не ретраили синхронно и не устроили retry storm. Число попыток ограничено (N).
5. **Исчерпали ретраи** → сообщение всё равно едет в **DLQ** (даже если ошибка была «временной» — раз она не прошла за N попыток, дальше пусть разбирается человек).
6. **DLQ живёт своей жизнью:** мониторинг, алерт, разбор, при необходимости — reprocess обратно в основной топик после исправления.

**Ключевые параметры, которые надо осознанно выбрать:**

| Параметр | О чём | Типичный выбор |
|---|---|---|
| Число ретраев `N` | сколько раз пробуем до DLQ | 3–5 для transient; 0 для fatal |
| Backoff | задержка между ретраями | exponential: 500ms → 1s → 2s → 4s |
| Jitter | случайный разброс задержки | ±20–50% от delay |
| Blocking vs non-blocking retry | ждём в том же потоке или через отдельные retry-топики | non-blocking (retry-топики), если нельзя блокировать партицию |
| Классификатор ошибок | какие исключения retryable | явный список: retryable по умолчанию или fatal по умолчанию — решить и зафиксировать |

**Blocking retry** (Spring Kafka `DefaultErrorHandler` с `BackOff`) прост, но на время ретраев **держит партицию** — если backoff большой, лаг растёт. **Non-blocking retry** (`@RetryableTopic`) отправляет сообщение в отдельные топики `order-retry-0`, `order-retry-1` с возрастающими задержками, не блокируя основную партицию, — но ломает строгий порядок. Выбор зависит от того, что важнее: порядок или throughput.

## Пример на Java

Начнём с «руками», чтобы была видна механика, потом — как это делает Spring Kafka из коробки.

### Вариант 1. Явная классификация + ручная отправка в DLT

```java
@Component
@RequiredArgsConstructor
public class OrderListener {

    private final OrderService orderService;
    private final OrderMapper orderMapper;
    private final KafkaTemplate<String, KafkaOrder> kafkaTemplate;

    // Исключения, которые бесполезно ретраить — сразу в DLQ
    private static final Set<Class<? extends Throwable>> FATAL = Set.of(
            IllegalArgumentException.class,        // битые данные, невалидный enum
            NullPointerException.class,            // обязательное поле = null
            ConstraintViolationException.class     // бизнес-инвариант нарушен
    );

    @KafkaListener(topics = "order", groupId = "seller-order-service")
    public void onOrder(ConsumerRecord<String, KafkaOrder> record) {
        try {
            var entity = orderMapper.toEntity(record.value());
            orderService.create(entity);
            // выход без исключения => Spring закоммитит офсет
        } catch (Exception e) {
            if (isFatal(e)) {
                // ядовитое сообщение: не ретраим, сразу откладываем и едем дальше
                sendToDlt(record, e);
                return;                 // офсет закоммитится, партиция разблокирована
            }
            // транзиентная ошибка: пробрасываем — пусть отработает retry/backoff
            throw e;
        }
    }

    private boolean isFatal(Throwable e) {
        return FATAL.stream().anyMatch(type -> type.isInstance(e));
    }

    private void sendToDlt(ConsumerRecord<String, KafkaOrder> record, Exception e) {
        var dltRecord = new ProducerRecord<>(
                record.topic() + ".DLT", record.key(), record.value());
        // сохраняем контекст расследования в заголовках
        dltRecord.headers()
                .add("x-original-topic", record.topic().getBytes(UTF_8))
                .add("x-original-offset", Long.toString(record.offset()).getBytes(UTF_8))
                .add("x-original-partition", Integer.toString(record.partition()).getBytes(UTF_8))
                .add("x-exception-class", e.getClass().getName().getBytes(UTF_8))
                .add("x-exception-message",
                        String.valueOf(e.getMessage()).getBytes(UTF_8));
        kafkaTemplate.send(dltRecord);
    }
}
```

Здесь важна классификация: `NullPointerException` из нашего продакшн-сценария попадает в `FATAL` → минуя ретраи уходит в `order.DLT`, офсет коммитится, партиция едет дальше. А таймаут БД (`не fatal`) пробрасывается наружу и попадает в штатный retry-механизм.

### Вариант 2. Боевой способ — Spring Kafka `DefaultErrorHandler` + `DeadLetterPublishingRecoverer`

Руками писать отправку в DLT почти никогда не нужно — Spring Kafka делает это за вас. Конфигурируем один error handler на весь контейнер:

```java
@Configuration
public class KafkaErrorHandlingConfig {

    @Bean
    public DefaultErrorHandler errorHandler(KafkaTemplate<Object, Object> template) {
        // Recoverer: после исчерпания попыток публикует в <topic>.DLT
        // и сам добавляет заголовки kafka_dlt-exception-*, kafka_dlt-original-*
        var recoverer = new DeadLetterPublishingRecoverer(template);

        // Exponential backoff с jitter: 500ms, x2, до 10s, максимум 4 попытки
        var backOff = new ExponentialBackOffWithMaxRetries(4);
        backOff.setInitialInterval(500L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(10_000L);

        var handler = new DefaultErrorHandler(recoverer, backOff);

        // Эти исключения НЕ ретраятся — сразу в DLT (классификация fatal-ошибок)
        handler.addNotRetryableExceptions(
                IllegalArgumentException.class,
                NullPointerException.class,
                ConstraintViolationException.class,
                MethodArgumentNotValidException.class);

        return handler;
    }
}
```

При такой конфигурации листенер снова становится тривиальным — он просто бросает исключение, а инфраструктура решает: ретраить или в DLT.

```java
@KafkaListener(topics = "order", groupId = "seller-order-service")
public void onOrder(KafkaOrder order) {
    var entity = orderMapper.toEntity(order);   // бросит NPE на битых данных
    orderService.create(entity);                // бросит DataAccessException при проблемах с БД
}
// NPE -> в addNotRetryableExceptions -> сразу order.DLT
// DataAccessException -> 4 попытки с backoff -> если не прошло, тоже order.DLT
```

### Вариант 3. Non-blocking retry через `@RetryableTopic`

Если нельзя блокировать партицию на время backoff — пусть ретраи идут через отдельные топики:

```java
@RetryableTopic(
        attempts = "4",
        backoff = @Backoff(delay = 1000, multiplier = 2.0),
        // fatal-исключения не ретраятся, сразу в DLT
        exclude = {IllegalArgumentException.class, NullPointerException.class},
        dltStrategy = DltStrategy.FAIL_ON_ERROR,
        // топики: order-retry-0, order-retry-1, ... и order-dlt
        topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE)
@KafkaListener(topics = "order", groupId = "seller-order-service")
public void onOrder(KafkaOrder order) {
    orderService.create(orderMapper.toEntity(order));
}

@DltHandler
public void onDlt(KafkaOrder order,
                  @Header(KafkaHeaders.DLT_EXCEPTION_MESSAGE) String error) {
    // сюда попадают сообщения после всех ретраев — логируем, метрика, алерт
    log.error("Order moved to DLT: {}, reason: {}", order, error);
    dlqMetrics.increment();
}
```

### Разбор DLQ: дедуп при reprocess

Когда после фикса кода вы переигрываете сообщения из DLQ обратно, легко создать дубликаты (например, часть заказов уже создалась при частичном успехе). Reprocess **обязан быть идемпотентным**. Простейшая защита — уникальный ключ + `ON CONFLICT` в Postgres:

```sql
CREATE TABLE seller_order (
    id           BIGSERIAL PRIMARY KEY,
    external_id  BIGINT NOT NULL,
    status       VARCHAR(32) NOT NULL,
    created_at   TIMESTAMP NOT NULL DEFAULT now()
);

-- гарантирует, что один и тот же заказ не создастся дважды при reprocess
CREATE UNIQUE INDEX seller_order_external_id_udx ON seller_order (external_id);
```

```java
@Transactional
public void create(SellerOrder order) {
    // INSERT ... ON CONFLICT DO NOTHING: повторная обработка того же
    // external_id не создаёт второй заказ. Дедуп на уровне БД, а не приложения.
    orderRepository.insertIgnoreConflict(order);
}
```

```java
@Modifying
@Query(value = """
        INSERT INTO seller_order (external_id, status, created_at)
        VALUES (:#{#o.externalId}, :#{#o.status}, now())
        ON CONFLICT (external_id) DO NOTHING
        """, nativeQuery = true)
int insertIgnoreConflict(@Param("o") SellerOrder o);
```

## Подводные камни

1. **DLQ вместо ретраев для транзиентных ошибок.** Если ты пихаешь в DLT `SocketTimeoutException`, то при коротком сетевом моргании туда улетят тысячи валидных сообщений, и придётся руками их реплеить. Транзиентные ошибки — ретраить, в DLQ они попадают только после исчерпания попыток. Держи явный список `notRetryable` и осознанно решай, что в нём.

2. **Ретраи не-идемпотентной обработки.** Если `orderService.create()` не идемпотентен, то blocking-retry (4 попытки) может создать 4 заказа: первая попытка вставила строку и упала на отправке события, вторая вставила снова. Любой ретрай требует идемпотентности обработчика (уникальный индекс + `ON CONFLICT`, idempotency key, дедуп-таблица). Ретрай без идемпотентности хуже, чем отсутствие ретрая.

3. **Retry storm без jitter.** `ExponentialBackOff` без разброса + падение общей зависимости (например, БД) = все консьюмеры всех подов ретраят синхронно, в одни и те же моменты, и добивают уже лежащую зависимость. Всегда добавляй jitter. Кстати, `@RetryableTopic` с фиксированным delay без jitter — та же проблема.

4. **DLQ без мониторинга и алерта.** Самая частая беда: DLT настроили, галочку поставили, забыли. Сообщения тихо капают в DLT неделями. Обязательно: метрика `dlt_messages_total` (counter), алерт на рост, дашборд. Рост DLT — это инцидент, а не «когда-нибудь посмотрим».

5. **Poison message в самом DLT-продьюсере.** Если сериализация сообщения в DLT падает (например, слишком большой payload превышает `max.request.size`), recoverer сам бросит исключение, и ты вернёшься к бесконечному ретраю исходного сообщения. Проверяй, что DLT-топик выдержит любой payload, который может прийти; логируй `key`/`offset` даже если тело не улетело.

6. **Ретраи держат партицию (blocking retry).** `DefaultErrorHandler` с backoff 10s и 5 попытками = до 50 секунд партиция стоит на одном сообщении. При потоке это огромный лаг. Если throughput важнее порядка — non-blocking (`@RetryableTopic`), если наоборот — держи backoff коротким.

7. **Потеря порядка при non-blocking retry.** `@RetryableTopic` уводит сообщение в retry-топик, а следующие за ним обрабатываются сразу — порядок в рамках ключа ломается. Для потоков, где важен порядок событий одной сущности (статусы одного заказа), это баг. Тогда — blocking retry либо перекладывание ключа с сохранением упорядочивания на стороне обработки.

8. **DLT без ретеншена / без разбора накапливается вечно.** У DLT-топика должен быть осознанный retention и процесс разбора. Иначе через полгода там миллионы сообщений, и никто уже не знает, актуальны ли они и можно ли их реплеить.

## Практическая задача

**Система.** Сервис `seller-order-service` потребляет из Kafka-топика `order` сообщения о новых заказах (DTO `KafkaOrder`) и создаёт записи в Postgres. Consumer group `seller-order-service`. Порядок сообщений в рамках партиции для этой задачи не критичен (каждый заказ независим).

**Дано.** Текущий листенер (упрощённо):

```java
@KafkaListener(topics = "order", groupId = "seller-order-service")
public void onOrder(KafkaOrder order) {
    var entity = orderMapper.toEntity(order);   // NPE, если order.price() == null
    orderService.create(entity);                // может бросить DataAccessException при проблемах с БД
}
```

Никакого error handler не сконфигурировано → используется дефолтный бесконечный ретрай. Сегодня в проде: upstream начал изредка присылать заказы с `price == null`. Одно такое сообщение застряло на офсете, `mapper.toEntity` бросает `NullPointerException`, партиция встала, лаг растёт, валидные заказы не создаются.

**Задача.** Сделать так, чтобы ядовитое сообщение не блокировало очередь, при этом транзиентные ошибки БД по-прежнему ретраились, а битые сообщения не терялись.

**Что реализовать:**

1. **Классификация ошибок.** `NullPointerException` (и другие ошибки битых данных: `IllegalArgumentException`, `ConstraintViolationException`) — non-retryable, отправляются в `order.DLT` немедленно. `DataAccessException` / таймауты — retryable.
2. **Retry с backoff.** Для retryable — не больше 4 попыток, exponential backoff (старт 500ms, множитель 2, потолок 10s), **с jitter**. После исчерпания попыток — тоже в DLT.
3. **DLQ.** Сообщения из DLT сохраняют контекст: original offset/partition, класс и сообщение исключения (Spring делает это сам через `DeadLetterPublishingRecoverer`).
4. **Идемпотентность.** `orderService.create()` должен быть безопасен к повтору: добавь уникальный индекс на `external_id` и `INSERT ... ON CONFLICT DO NOTHING`, чтобы ретрай/реплей не создавал дубль заказа.
5. **Наблюдаемость.** `@DltHandler` (или лог в recoverer), инкрементирующий метрику `dlt_messages_total`.

**Критерии приёмки (проверить функциональным тестом на Testcontainers Kafka + Postgres):**

- Сообщение с `price == null` **не блокирует** обработку: следующее валидное сообщение в той же партиции создаёт заказ в БД. (Отправь три сообщения: валидное → ядовитое → валидное; убедись, что в БД два заказа, а ядовитое ушло в `order.DLT`.)
- Ядовитое сообщение оказывается в топике `order.DLT` с заголовком, содержащим `NullPointerException`.
- Ядовитое сообщение при этом обрабатывается **ровно один раз** (не 200 ретраев) — проверь по числу вызовов маппера/по логам, что non-retryable не ретраится.
- **Идемпотентность:** отправка двух сообщений с одинаковым `external_id` создаёт **ровно один** заказ в `seller_order` (второй гасится `ON CONFLICT`).
- Транзиентная ошибка ретраится: замокай `orderService.create()` так, чтобы первые 2 вызова бросали `DataAccessException`, а третий проходил → заказ в итоге создаётся, в DLT ничего не попадает.

**Подсказки (без готового решения):**

- Один `@Bean DefaultErrorHandler` на контейнер закрывает пункты 1–3: `DeadLetterPublishingRecoverer` + `ExponentialBackOffWithMaxRetries` + `addNotRetryableExceptions(...)`.
- Jitter из коробки в `ExponentialBackOffWithMaxRetries` нет — либо навесь `RetryTopic`-подход, либо оберни/добавь случайный разброс сам; в тесте jitter проверять не обязательно, но в проде он обязателен (см. подводный камень №3).
- Заголовки DLT, которые ставит Spring: `KafkaHeaders.DLT_EXCEPTION_FQCN`, `KafkaHeaders.DLT_ORIGINAL_OFFSET` и др. — по ним удобно ассертить.
- Для проверки «обработано ровно один раз» удобно считать вызовы реального бина через счётчик, а не через `@MockBean` (он ломает контекст — см. правила тестирования проекта).
- Не забудь создать DLT-топик (или включи автосоздание в тестовом профиле) — если топика нет, recoverer может сам упасть.

## Что почитать

- **Spring for Apache Kafka — Handling Exceptions, `DefaultErrorHandler`, `DeadLetterPublishingRecoverer`, `@RetryableTopic`:** https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html
- **Amazon Builders' Library — Timeouts, retries, and backoff with jitter** (Marc Brooker): https://aws.amazon.com/builders-library/timeouts-retries-and-backoff-with-jitter/
- **Resilience4j — Retry (backoff, интервалы, предикаты retryable-исключений):** https://resilience4j.readme.io/docs/retry
- **Confluent — Error Handling Patterns for Apache Kafka (Dead Letter Queues):** https://www.confluent.io/blog/error-handling-patterns-in-kafka/
- **Chris Richardson, microservices.io — Transactional Outbox pattern** (надёжная публикация событий, смежная с DLQ тема): https://microservices.io/patterns/data/transactional-outbox.html
