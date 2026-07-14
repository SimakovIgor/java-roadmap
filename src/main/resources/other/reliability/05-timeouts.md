# Таймауты

> «У нас всё работало. Просто один сервис перестал отвечать — и через четыре минуты лёг весь кластер.»

Таймаут — это самая дешёвая страховка в распределённой системе и одновременно самая часто забываемая. В этом уроке разберём, почему отсутствие таймаута превращает единичную деградацию в каскадный отказ, где именно в Java-стеке прячутся «бесконечные» ожидания, и как расставить границы времени так, чтобы система деградировала предсказуемо, а не падала целиком.

---

## Проблема

Пятница, вечер. Ваш сервис `order-service` синхронно ходит в `pricing-service` за актуальной ценой перед оформлением заказа. Код выглядит абсолютно нормально:

```java
@Service
public class PricingClient {

    private final RestTemplate restTemplate = new RestTemplate(); // ← дефолтный, без таймаутов

    public Price fetchPrice(long skuId) {
        return restTemplate.getForObject(
            "http://pricing-service/api/v1/price/{skuId}",
            Price.class,
            skuId
        );
    }
}
```

Ревью прошло, тесты зелёные, на нагрузочном всё летало. А теперь смотрим, что происходит в проде.

`pricing-service` не упал — он **завис**. GC-пауза, залоченный коннекшн к своей БД, деградировавший диск — не важно. Он принимает TCP-соединение, но ответ не шлёт. Для клиента это худший из сценариев: не отказ (на отказ мы бы среагировали), а **бесконечное ожидание**.

Что происходит дальше по шагам:

```
t=0s     pricing-service начинает висеть
t=0s     запрос #1 клиента уходит в pricing → поток Tomcat #1 заблокирован в read()
t=1s     +40 запросов/сек, каждый занимает поток из пула Tomcat (200 потоков)
t=5s     ~200 потоков заняты, все ждут ответа, которого не будет
t=5s     пул потоков Tomcat исчерпан
t=5s+    новые HTTP-запросы к order-service встают в accept-очередь
t=8s     healthcheck /actuator/health тоже не может получить поток → k8s считает под мёртвым
t=8s     liveness-проба фейлится → под убивают и рестартят
t=…      трафик переезжает на соседние поды → они забиваются так же → каскад
```

Ключевой момент: **проблема была в чужом сервисе, а лёг ваш**. И не потому что вы делали что-то тяжёлое — а потому что не поставили границу, сколько времени вы готовы ждать. Дефолтный `RestTemplate` (как и `new Socket()`, как и `HttpClient` без настроек в старых версиях) по умолчанию ждёт **бесконечно**. Один заблокированный поток — это ещё не беда. Беда — когда их 200, и они держат весь пул, а за пулом стоит очередь, а за очередью — healthcheck.

Стоимость: полный отказ сервиса из-за деградации зависимости, которая, может, и сама бы через 30 секунд очухалась. Вместо «часть заказов посчиталась по кэшу» вы получили «весь чекаут лежит + каскад на соседей + ночной вызов дежурного».

---

## Что это и когда применять

**Таймаут** — это заранее заданный лимит времени, после которого операция принудительно прекращается с ошибкой, вместо того чтобы ждать результата неопределённо долго.

Простыми словами: вы говорите «я готов ждать этот вызов максимум N миллисекунд; не уложился — считаем, что не получилось, и идём дальше». Таймаут превращает **неопределённое зависание** в **определённый, обрабатываемый отказ**. А обрабатываемый отказ — это то, с чем система умеет жить: вернуть кэш, вернуть 503, уйти в fallback, отпустить поток обратно в пул.

Таймаут решает ровно одну, но фундаментальную проблему: **защищает ваш ограниченный ресурс (потоки, коннекшены, память) от бесконечного удержания медленной или зависшей зависимостью.**

Где таймаут обязателен:

- **любой сетевой вызов** — HTTP-клиенты, gRPC, вызовы БД, Kafka-продюсер, Redis, вызовы внешних API;
- **любое ожидание блокирующего ресурса** — получение коннекшена из пула (`HikariCP.connectionTimeout`), захват лока, `Future.get()`, `queue.poll()`;
- **любой цикл ретраев** — общий бюджет времени на всю операцию, а не только на одну попытку.

Когда таймаут НЕ нужен (или вреден в наивной форме):

- **Чисто локальные CPU-вычисления** без внешних зависимостей — таймаут тут либо бессмыслен, либо требует отдельного потока и `Future.cancel()`, что часто дороже проблемы.
- **Заведомо долгие фоновые задачи** (батч на 2 часа, экспорт отчёта) — здесь не «таймаут на весь процесс», а таймауты на *отдельные шаги* внутри и явный механизм отмены/чекпоинтов. Ставить таймаут 5 минут на операцию, которая честно работает 2 часа — это не надёжность, это баг.
- **Стриминг / long-polling / SSE / WebSocket** — там долгое удержание соединения это норма; нужен не общий таймаут ответа, а **idle/read-таймаут между чанками** («молчит дольше 30с — рвём»).

Частая ошибка новичка — воспринимать таймаут как «поставлю 30 секунд везде и забуду». 30 секунд — это чаще всего **слишком много**: за 30 секунд под потоками уже успеет забиться пул. Таймаут должен исходить не из «сколько сервис может отвечать в худшем случае», а из «сколько я реально готов ждать, учитывая, сколько запросов набежит за это время». Обычно это единицы секунд, а часто — сотни миллисекунд.

---

## Как это работает

### Виды таймаутов у одного HTTP-вызова

Наивно кажется, что «таймаут» — одно число. На самом деле у сетевого вызова несколько разных фаз, и у каждой свой таймаут:

```
           ваш сервис                         внешний сервис
              │                                      │
   ┌──────────┤                                      │
   │ connect  │───── SYN ───────────────────────────▶│   connectTimeout:
   │ timeout  │◀──── SYN/ACK ────────────────────────│   сколько ждём установки
   └──────────┤                                      │   TCP-соединения
              │                                      │
   ┌──────────┤───── HTTP request ──────────────────▶│
   │  read /  │                                      │   readTimeout (socket):
   │  socket  │            (сервер думает…)          │   макс. простой БЕЗ данных
   │ timeout  │◀──── HTTP response ──────────────────│   между пакетами
   └──────────┤                                      │
              │                                      │
   ├──────────────── requestTimeout / total ─────────┤   общий бюджет на весь
   │            (от отправки до последнего байта)     │   вызов целиком
```

- **connectTimeout** — сколько ждём, пока установится TCP-соединение. Ловит «сервер недоступен / сеть лежит / фаервол чёрной дырой глотает пакеты». Обычно маленький: 200мс–1с.
- **readTimeout (socket timeout)** — максимальное время *простоя* между порциями данных. Ловит «соединение есть, но сервер завис и молчит». Внимание: это таймаут на *паузу*, а не на весь ответ — если сервер отдаёт по байту каждые 100мс, readTimeout=1с не сработает никогда.
- **requestTimeout / call timeout (total)** — жёсткий потолок на всю операцию целиком. Именно он реально спасает от медленной отдачи. В идеале должен быть у каждого вызова.
- **connectionRequestTimeout** — сколько ждём коннекшн *из пула клиента* (если пул исчерпан). Часто забывают — и получают зависание ещё до того, как вообще пошли в сеть.

### Как выбрать значение

Правило: таймаут выбирается от **клиента вниз**, а не от сервера вверх. Смотрите на p99/p99.9 латентности зависимости в нормальном режиме и добавляйте запас. Если pricing-service в норме отвечает за 50мс (p99), таймаут 500мс — разумно (10x запас на редкие всплески), а 30с — это «я согласен, чтобы 200 потоков висели полминуты».

### Бюджет времени (deadline propagation)

Продвинутая, но важная идея: таймаут — это не свойство одного вызова, а **бюджет, который тратится по цепочке**. Если у вас есть 1 секунда на весь HTTP-запрос, и внутри три последовательных вызова, нельзя каждому дать по 1 секунде — суммарно получится 3с.

```
входящий запрос: бюджет 1000мс
  ├─ вызов A: потратил 300мс      → осталось 700мс
  ├─ вызов B: даём timeout=700мс, потратил 200мс → осталось 500мс
  └─ вызов C: даём timeout=500мс (не 1000!)
```

Каждый следующий вызов получает таймаут = «сколько осталось от общего бюджета», а не свою фиксированную константу. Это то, что делают gRPC deadlines и трейсинг-контексты. Даже если вы не пробрасываете deadline автоматически — держите в голове, что сумма таймаутов по цепочке не должна превышать таймаут входящего запроса.

### Таймаут + отмена

Таймаут на клиенте прекращает *ждать* ответ. Но хорошо бы ещё и **отменить работу** — закрыть сокет, чтобы сервер понял, что клиент ушёл, и не тратил ресурсы. HTTP-клиенты при таймауте рвут соединение. При работе с `Future`/`CompletableFuture` — вызывайте `cancel(true)`. При таймауте в БД — `statement.setQueryTimeout()` шлёт `CANCEL` в PostgreSQL, а не просто бросает ваш поток.

### Таймаут и связка с другими паттернами

Таймаут почти никогда не работает в одиночку. Его боевые соседи:

- **Circuit breaker** — если зависимость стабильно не укладывается в таймаут, нет смысла долбить её и жечь таймауты; размыкаем цепь и сразу отдаём fallback.
- **Retry** — таймаут делает ретрай осмысленным (мы знаем, что попытка *провалилась*, а не «ещё думает»); но ретраить нужно с общим бюджетом времени, иначе 3 ретрая × 5с = 15с зависания.
- **Bulkhead** — изолирует пул под каждую зависимость, чтобы даже забитый таймаутами вызов не съел общие потоки.

---

## Пример на Java

### 1. Настраиваем таймауты руками (RestClient / RestTemplate)

Начнём с самого главного и самого дешёвого шага — правильно сконфигурированный клиент. 80% проблем решаются вот этим:

```java
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class PricingClientConfig {

    @Bean
    public RestClient pricingRestClient() {
        var connConfig = ConnectionConfig.custom()
            // ждём установку TCP-соединения максимум 500мс
            .setConnectTimeout(Duration.ofMillis(500))
            // максимальный простой между пакетами данных
            .setSocketTimeout(Duration.ofMillis(800))
            .build();

        var connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setDefaultConnectionConfig(connConfig);
        connectionManager.setMaxTotal(50);          // общий лимит коннекшенов
        connectionManager.setDefaultMaxPerRoute(50);

        var requestConfig = RequestConfig.custom()
            // сколько ждём коннекшн ИЗ ПУЛА, если он исчерпан — частый забытый таймаут
            .setConnectionRequestTimeout(Duration.ofMillis(200))
            // жёсткий потолок на весь ответ целиком
            .setResponseTimeout(Duration.ofMillis(1000))
            .build();

        CloseableHttpClient httpClient = HttpClients.custom()
            .setConnectionManager(connectionManager)
            .setDefaultRequestConfig(requestConfig)
            .build();

        var factory = new HttpComponentsClientHttpRequestFactory(httpClient);

        return RestClient.builder()
            .baseUrl("http://pricing-service")
            .requestFactory(factory)
            .build();
    }
}
```

Здесь важно, что мы закрыли **все четыре** таймаута: connect, socket, connection-request и response. Пропустите любой — и остаётся щель, через которую поток можно заблокировать навсегда.

### 2. Таймаут через Resilience4j (+ fallback)

Ручные таймауты клиента защищают от медленной сети. Но иногда нужен таймаут на **любую** операцию (не только HTTP) и внешний контроль с fallback. Resilience4j `TimeLimiter` выполняет задачу в отдельном пуле и жёстко отменяет её по истечении времени:

```java
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;

import java.time.Duration;
import java.util.concurrent.*;

@Service
public class PricingService {

    private final RestClient pricingRestClient;
    private final ScheduledExecutorService scheduler =
        Executors.newScheduledThreadPool(4);
    private final ExecutorService worker =
        Executors.newFixedThreadPool(20); // bulkhead: изолированный пул под pricing

    private final TimeLimiter timeLimiter = TimeLimiter.of(
        TimeLimiterConfig.custom()
            .timeoutDuration(Duration.ofMillis(1000))
            .cancelRunningFuture(true) // при таймауте отменяем задачу, освобождаем поток
            .build()
    );

    public PricingService(RestClient pricingRestClient) {
        this.pricingRestClient = pricingRestClient;
    }

    public Price fetchPrice(long skuId) {
        Supplier<CompletableFuture<Price>> futureSupplier = () ->
            CompletableFuture.supplyAsync(() -> callPricing(skuId), worker);

        try {
            return timeLimiter.executeCompletionStage(
                scheduler,
                futureSupplier::get
            ).toCompletableFuture().join();
        } catch (Exception e) {
            // таймаут или ошибка → предсказуемый fallback, а не зависание
            return Price.fallbackFor(skuId); // например, последняя кэшированная цена
        }
    }

    private Price callPricing(long skuId) {
        return pricingRestClient.get()
            .uri("/api/v1/price/{skuId}", skuId)
            .retrieve()
            .body(Price.class);
    }
}
```

Обратите внимание на два уровня защиты: таймаут *самого HTTP-клиента* (1000мс response) и `TimeLimiter` поверх (1000мс). Клиентский таймаут — основной; `TimeLimiter` страхует случаи, когда вызов повис не в сети, а, скажем, в маппинге ответа или в чужой библиотеке, которая игнорирует socket timeout.

### 2b. Декларативно, через аннотации

В реальном проекте на Spring Boot связку обычно вешают аннотациями:

```java
@Service
public class PricingService {

    @TimeLimiter(name = "pricing", fallbackMethod = "priceFallback")
    @CircuitBreaker(name = "pricing", fallbackMethod = "priceFallback")
    public CompletableFuture<Price> fetchPrice(long skuId) {
        return CompletableFuture.supplyAsync(() -> callPricing(skuId));
    }

    // сигнатура fallback = сигнатура метода + Throwable последним аргументом
    private CompletableFuture<Price> priceFallback(long skuId, Throwable t) {
        return CompletableFuture.completedFuture(Price.fallbackFor(skuId));
    }
}
```

```yaml
# application.yml
resilience4j:
  timelimiter:
    instances:
      pricing:
        timeout-duration: 1s
        cancel-running-future: true
  circuitbreaker:
    instances:
      pricing:
        sliding-window-size: 50
        failure-rate-threshold: 50        # >50% фейлов → размыкаем
        slow-call-duration-threshold: 900ms
        slow-call-rate-threshold: 80      # >80% медленных (>900мс) тоже размыкает цепь
        wait-duration-in-open-state: 10s
```

Ключевая связка: `slow-call-duration-threshold` учит circuit breaker считать *медленные* вызовы (близкие к таймауту) за отказы. Иначе breaker откроется только когда вызовы начнут падать по таймауту — а это уже поздно, потоки уже страдают.

### 3. Таймауты на БД (PostgreSQL)

Сетевой вызов — не единственный источник зависаний. Тяжёлый запрос или залоченная строка держат поток так же надёжно. Закрываем на трёх уровнях:

```yaml
spring:
  datasource:
    hikari:
      connection-timeout: 2000   # ждём коннекшн из пула max 2с (иначе — исключение)
      max-lifetime: 1800000
      maximum-pool-size: 20
  jpa:
    properties:
      hibernate:
        jdbc:
          # таймаут на выполнение JDBC-стейтмента (шлёт CANCEL в PG)
          # Hibernate возьмёт его из @Transactional(timeout=...) тоже
```

```sql
-- Уровень сессии/транзакции: жёстко ограничиваем время запроса на стороне БД
SET statement_timeout = '3s';         -- любой SQL дольше 3с прибивается сервером PG
SET lock_timeout = '1s';              -- ждём блокировку строки max 1с, иначе ошибка
SET idle_in_transaction_session_timeout = '10s'; -- прибиваем «забытые» открытые транзакции
```

```java
@Transactional(timeout = 3) // Spring выставит query timeout = 3с на транзакцию
public void updateOrder(long id) { ... }
```

`statement_timeout` на стороне PostgreSQL — самая надёжная граница: даже если приложение забыло про таймаут, сервер БД сам прибьёт зависший запрос и вернёт коннекшн.

### 4. Таймаут в цикле ретраев — с общим бюджетом

Ретраи без общего бюджета времени — это способ умножить зависание. Правильно — считать оставшийся бюджет:

```java
public Price fetchWithBudget(long skuId, Duration totalBudget) {
    long deadlineNanos = System.nanoTime() + totalBudget.toNanos();
    int attempt = 0;
    RuntimeException last = null;

    while (System.nanoTime() < deadlineNanos) {
        attempt++;
        long remainingMs = Duration.ofNanos(deadlineNanos - System.nanoTime()).toMillis();
        if (remainingMs <= 0) {
            break;
        }
        try {
            // таймаут попытки = min(таймаут вызова, остаток бюджета)
            return callPricingWithTimeout(skuId, Math.min(800, remainingMs));
        } catch (TimeoutException | ResourceAccessException e) {
            last = new RuntimeException(e);
            // экспоненциальный backoff + джиттер, но не выходя за бюджет
            long backoff = (long) (Math.pow(2, attempt) * 50);
            long jitter = ThreadLocalRandom.current().nextLong(50);
            sleepUpTo(backoff + jitter, deadlineNanos);
        }
    }
    throw new PricingUnavailableException("budget exhausted after " + attempt + " attempts", last);
}
```

Общий бюджет гарантирует: сколько бы ни было ретраев, наверх мы вернёмся не позже `totalBudget`. Именно это число согласуется с таймаутом входящего запроса.

---

## Подводные камни

1. **Дефолт = бесконечность.** `new RestTemplate()`, `new Socket()`, старый `HttpURLConnection`, многие JDBC-драйверы, Kafka-продюсер с дефолтным `delivery.timeout.ms` — молча ждут очень долго или вечно. Никогда не полагайтесь на «наверное, там есть разумный дефолт». Проверяйте каждый клиент явно.

2. **Закрыли не все таймауты.** Поставили connect + read, но забыли `connectionRequestTimeout` (ожидание коннекшена из пула). Пул из 50 коннекшенов забился — и 51-й запрос виснет *до сети*, а ваши socket-таймауты даже не начали тикать. Дырка должна быть закрыта на всех фазах.

3. **readTimeout ≠ таймаут ответа.** `socketTimeout` ловит только *паузу без данных*. Сервер, отдающий гигабайтный ответ по капле, или медленный chunked-стрим никогда не триггерят socket timeout. Нужен отдельный `responseTimeout`/call timeout на всю операцию.

4. **Ретраи умножают таймаут.** Таймаут 5с × 3 ретрая = 15с зависания вместо 5. А если ещё и без джиттера — все клиенты ретраят синхронно и устраивают **retry storm**, добивая уже деградировавшую зависимость. Всегда: общий бюджет времени + экспоненциальный backoff + джиттер + ретраить только идемпотентное.

5. **Таймаут без отмены оставляет работу висеть.** Клиент бросил `TimeoutException` и ушёл, но запрос на сервере продолжает выполняться, коннекшн к БД держится, поток занят. Рвите соединение (HTTP-клиенты это делают), вызывайте `future.cancel(true)`, используйте `statement_timeout` в PG — чтобы отменялась *настоящая работа*, а не только ваше ожидание.

6. **Таймаут больше, чем нужно.** «Поставлю 30с, чтобы наверняка». За 30с при 40 rps набежит 1200 заблокированных запросов — пул умрёт задолго до срабатывания таймаута. Таймаут считается от p99 зависимости + запас, а не от «максимально возможного времени ответа». Обычно это сотни мс — единицы секунд.

7. **Таймаут короче, чем честная работа.** Обратная крайность: 2с на генерацию PDF-отчёта, который законно делается 8с. Теперь операция не может завершиться *никогда* — каждая попытка обрывается на таймауте. Для долгих операций — асинхронная модель (принять задачу → 202 Accepted → поллинг статуса), а не «уменьшить таймаут».

8. **Рассинхрон таймаутов по цепочке.** Внешний балансировщик рвёт запрос на 5с, а ваш вызов вниз имеет таймаут 10с. Клиент уже получил 504 и ушёл (возможно, ретраит!), а вы ещё пять секунд жжёте ресурсы на ответ, который никто не ждёт. Таймауты должны *убывать* вниз по цепочке: каждый следующий уровень ≤ предыдущего.

---

## Практическая задача

### Контекст

Вы дорабатываете `checkout-service` — сервис оформления заказа в интернет-магазине. Перед подтверждением корзины он синхронно вызывает три внешних сервиса:

- `inventory-service` — проверка наличия (p99 ≈ 40мс);
- `pricing-service` — актуальная цена (p99 ≈ 60мс);
- `loyalty-service` — расчёт бонусов (p99 ≈ 120мс, но **известен своей нестабильностью** — раз в неделю висит по несколько минут).

Входящий HTTP-запрос на `POST /checkout` обслуживается пулом Tomcat на 200 потоков. SLA на весь эндпоинт — ответ за 1 секунду.

### Дано (текущий сломанный код)

```java
@Service
public class CheckoutService {

    private final RestTemplate rest = new RestTemplate(); // дефолт, таймаутов нет

    public CheckoutResult checkout(CheckoutRequest req) {
        var stock  = rest.postForObject("http://inventory-service/check", req, StockInfo.class);
        var price  = rest.postForObject("http://pricing-service/quote", req, PriceQuote.class);
        var bonus  = rest.postForObject("http://loyalty-service/calc",  req, BonusInfo.class);

        return CheckoutResult.of(stock, price, bonus);
    }
}
```

Сейчас происходит следующее: когда `loyalty-service` виснет, каждый `/checkout` блокируется на нём навсегда, пул Tomcat забивается за секунды, `/checkout` и healthcheck ложатся целиком. При этом бонусы — **не критичны**: заказ можно оформить и без них (начислить позже, показать «бонусы уточняются»).

### Задание

Переделайте `CheckoutService` так, чтобы деградация любой зависимости — в первую очередь нестабильного `loyalty-service` — **не могла исчерпать пул потоков и уронить эндпоинт**. Конкретно:

1. Настройте HTTP-клиент(ы) с явными таймаутами на все фазы (connect, socket/read, response, connection-request из пула). Значения выберите осознанно, отталкиваясь от p99 каждой зависимости, а не «на глаз».
2. Впишитесь в общий бюджет **1 секунда** на весь `/checkout`: сумма таймаутов по цепочке (с учётом того, что вызовы последовательны) не должна выходить за SLA. Подумайте, не стоит ли часть вызовов распараллелить.
3. Сделайте `loyalty-service` **деградируемым**: при таймауте/ошибке `/checkout` должен вернуть успешный результат с пометкой «бонусы недоступны», а не падать. Для `inventory` и `pricing` таймаут — это законный отказ всего чекаута (без них заказ оформить нельзя), но отказ должен быть **быстрым и явным**, а не зависанием.
4. Добавьте для `loyalty-service` circuit breaker: если он стабильно не укладывается в таймаут, перестаньте его дёргать на `wait-duration` и сразу отдавайте fallback.

### Критерии приёмки

- Если `loyalty-service` не отвечает (симулируйте — например, поднимите заглушку, которая `Thread.sleep(60_000)`), `/checkout` всё равно возвращает `200` с бонусами в состоянии «недоступно», уложившись в ~1с.
- При зависании `loyalty-service` под нагрузкой пул потоков Tomcat **не** исчерпывается: healthcheck и остальные эндпоинты продолжают отвечать.
- Если виснет `inventory` или `pricing`, `/checkout` отдаёт ошибку **быстро** (в пределах своего таймаута, не 30с), а не висит.
- После N подряд медленных вызовов `loyalty` circuit breaker размыкается, и следующие запросы уходят в fallback **мгновенно**, не тратя таймаут (проверьте по метрикам/логам breaker'а).
- Суммарное время `/checkout` в худшем случае (все зависимости на грани таймаута) не превышает согласованный бюджет.

### Подсказки (без готового решения)

- Начните с самого дешёвого и важного — правильно сконфигурированного `RestClient`/`HttpClient`. Аннотации Resilience4j — уже сверху.
- `slow-call-duration-threshold` в circuit breaker — ваш друг: он позволит breaker'у реагировать на *медленные* вызовы, не дожидаясь, пока они начнут падать по таймауту.
- Подумайте: три последовательных вызова с таймаутом ~300мс каждый — это уже почти секунда впритык. `inventory` и `pricing` независимы — их можно запустить параллельно (`CompletableFuture.allOf`) и сэкономить бюджет для более медленного `loyalty`.
- Для `loyalty` уместна связка `@TimeLimiter` + `@CircuitBreaker` + `fallbackMethod`. Не забудьте `cancelRunningFuture: true`, чтобы таймаут *отменял* работу, а не просто отпускал ваше ожидание.
- Проверьте, что fallback у `loyalty` — это осмысленное состояние (`BonusInfo.unavailable()`), а не `null`, об который потом упадёт маппинг ответа.
- Симулируйте зависание через WireMock (`withFixedDelay`) или простую заглушку со `sleep` — и прогоните маленький нагрузочный тест, наблюдая за пулом Tomcat и метриками Resilience4j.

---

## Что почитать

- [Resilience4j — TimeLimiter](https://resilience4j.readme.io/docs/timeout) и [CircuitBreaker](https://resilience4j.readme.io/docs/circuitbreaker) — официальная документация по таймаутам и связке с circuit breaker в Spring Boot.
- [Amazon Builders' Library — Timeouts, retries, and backoff with jitter](https://aws.amazon.com/builders-library/timeouts-retries-and-backoff-with-jitter/) — практический разбор, как выбирать таймауты, почему нужен джиттер и как ретраи усиливают отказ. Обязательное чтение.
- [Amazon Builders' Library — Using load shedding to avoid overload](https://aws.amazon.com/builders-library/using-load-shedding-to-avoid-overload/) — что делать, когда таймаутов уже мало: сброс нагрузки и защита от каскадов.
- [Microsoft — Cloud Design Patterns: Timeout, Retry, Circuit Breaker](https://learn.microsoft.com/en-us/azure/architecture/patterns/) — каталог паттернов надёжности, как таймаут стыкуется с ретраями и bulkhead.
