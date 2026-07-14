# Circuit Breaker (предохранитель)

> Раздел: Надёжность и архитектура. Стек: Java 21, Spring Boot, PostgreSQL, Kafka, внешние HTTP-сервисы.

Предохранитель в электрощитке не «чинит» короткое замыкание. Он делает другое: **быстро размыкает цепь**, чтобы пожар в одной комнате не спалил весь дом. Circuit Breaker в распределённой системе — ровно про это. Он не лечит упавший downstream. Он не даёт упавшему downstream утащить за собой вас.

---

## Проблема

Пятница, вечер. Ваш `order-service` при оформлении заказа синхронно ходит в `pricing-service` за актуальной ценой и скидками. Обычно это 20 мс. Всё летает.

В 19:42 у `pricing-service` деградирует база: соединения из пула не отдаются, запросы начинают висеть. `pricing-service` не отвечает `500` — он просто **молчит**. Ваши HTTP-вызовы теперь упираются в таймаут. А таймаут у вас, как это часто бывает, стоит «на всякий случай побольше» — 30 секунд.

Смотрим, что происходит у вас в сервисе. Наивный, «правильно выглядящий» код:

```java
@Service
public class PricingClient {

    private final RestClient restClient; // Spring 6 RestClient

    public Price getPrice(long sku) {
        // Выглядит невинно. Здесь и зарыта бомба.
        return restClient.get()
            .uri("/prices/{sku}", sku)
            .retrieve()
            .body(Price.class);
    }
}
```

А вызывающий его контроллер обрабатывает HTTP-запросы на стандартном пуле Tomcat (`server.tomcat.threads.max`, по умолчанию 200):

```java
@PostMapping("/orders")
public OrderResponse create(@RequestBody OrderRequest req) {
    var price = pricingClient.getPrice(req.sku()); // блокирует поток Tomcat на 30 сек
    return orderService.place(req, price);
}
```

Считаем математику катастрофы.

- К вам приходит ~100 запросов/сек на создание заказа.
- Каждый теперь висит 30 секунд на вызове `pricing-service`.
- Через `200 / 100 = 2` секунды **все 200 потоков Tomcat заняты** ожиданием.
- Новые запросы встают в accept-очередь, потом отваливаются по таймауту у клиента.

И вот тут — самое обидное. К вам приходят запросы не только на создание заказа. К вам приходит `GET /orders/{id}`, `GET /health`, вообще всё. Но **свободных потоков нет** — все висят на `pricing-service`. Ваш сервис перестаёт отвечать целиком. Kubernetes liveness-проба не получает ответа → под убивают и рестартуют → трафик переезжает на соседние поды → те тоже забиваются → **каскадный отказ**.

Итог: `pricing-service` уронил заказы, каталог, здоровье подов и разбудил вас в 20:00. Хотя, казалось бы, «просто одна из зависимостей была недоступна».

Ключевая мысль: **проблема не в том, что downstream упал. Проблема в том, что вы продолжали в него ломиться и держать под это ресурсы.** Каждый обречённый вызов — это занятый поток, занятый коннекшн, съеденная память под стек. Вы платите полную цену за запросы, которые заведомо провалятся.

---

## Что это и когда применять

**Circuit Breaker** — обёртка вокруг вызова к нестабильной зависимости, которая считает ошибки и, когда их становится слишком много, **перестаёт пропускать вызовы**: вместо реального обращения к downstream она мгновенно кидает исключение (`fail fast`). Периодически она «пробует» — пропускает один-два вызова, и если downstream ожил, снова открывает движение.

Что это даёт:

- **Fail fast вместо fail slow.** Вместо 30 секунд ожидания — мгновенный отказ за микросекунды. Поток освобождается сразу.
- **Защита ресурсов вызывающего.** Потоки, коннекшны, память не утекают в чёрную дыру.
- **Защита downstream.** Пока сервис лежит, вы не долбите его новыми запросами и даёте ему шанс подняться, а не добиваете retry-штормом.
- **Точка для graceful degradation.** На разомкнутом breaker'е удобно повесить fallback: отдать цену из кэша, дефолтную скидку, или явную ошибку «оформление временно недоступно».

**Когда НЕ нужно** (частая ошибка — вешать breaker на всё подряд):

- **На локальные/внутрипроцессные вызовы.** Circuit Breaker — про сетевые границы и внешние зависимости. Оборачивать в него обращение к своей же БД в рамках основного пути или чистую CPU-функцию — бессмысленный оверхед и лишняя точка отказа.
- **На батчи и офлайн-обработку, где нет живого потребителя.** Если Kafka-консьюмер обрабатывает сообщение и ему некуда торопиться — здесь важнее retry с backoff и корректный ack/DLT, а не размыкание. Fail fast в фоне часто означает «молча потеряли работу».
- **Когда у зависимости и так есть надёжный быстрый таймаут и запрос дешёвый.** Один необязательный вызов с таймаутом 200 мс и хорошим fallback не создаёт каскад — breaker тут лишняя сложность.
- **Как замена таймауту.** Это распространённое заблуждение. Circuit Breaker **не отменяет** таймаут — он на нём стоит. Без агрессивного таймаута breaker не сможет быстро «увидеть» медленные вызовы. Таймаут — фундамент, breaker — надстройка.

Практическое правило: Circuit Breaker имеет смысл на **синхронном вызове через сетевую границу к зависимости, которая может деградировать**, где у вас есть либо fallback, либо осмысленный быстрый отказ.

---

## Как это работает

Circuit Breaker — это конечный автомат с тремя состояниями.

```
            ошибок больше порога
            (напр. >50% из окна)
   ┌────────────────────────────────────┐
   │                                     ▼
┌──────┐                            ┌────────┐
│CLOSED│                            │  OPEN  │
│(норм)│                            │(отказ) │
└──────┘                            └────────┘
   ▲   │                              │   ▲
   │   │ вызовы проходят,             │   │ пробный вызов
   │   │ считаем успех/ошибку         │   │ снова упал
   │   │                              │   │
   │   │        прошёл  wait-duration │   │
   │   │        (напр. 10 сек)        ▼   │
   │   │                        ┌───────────┐
   │   └────── пробные вызовы ──│ HALF_OPEN │
   │           успешны          │ (проба)   │
   └────────────────────────────└───────────┘
```

**CLOSED (замкнут, норма).** Все вызовы проходят к downstream. Breaker ведёт статистику по скользящему окну — по последним N вызовам (count-based) или за последние T секунд (time-based). Считает долю ошибок и долю «медленных» вызовов (превысивших `slowCallDurationThreshold`).

**Переход CLOSED → OPEN.** Как только доля ошибок (или медленных вызовов) в окне превышает порог — breaker размыкается. Важная деталь: срабатывает только после набора минимального числа вызовов (`minimumNumberOfCalls`), чтобы 1 ошибка из 1 вызова не размыкала цепь на пустом месте.

**OPEN (разомкнут, отказ).** Ни один вызов не идёт к downstream. Любое обращение мгновенно завершается `CallNotPermittedException` (или уходит в fallback). Breaker сидит так `waitDurationInOpenState` (например, 10 секунд), давая downstream отдышаться.

**Переход OPEN → HALF_OPEN.** По истечении wait-duration breaker пропускает ограниченное число пробных вызовов (`permittedNumberOfCallsInHalfOpenState`).

**HALF_OPEN (проба).**
- Если пробные вызовы прошли успешно → downstream ожил → **CLOSED**.
- Если снова падают → downstream ещё лежит → назад в **OPEN** ещё на wait-duration.

### Ключевые параметры (Resilience4j)

| Параметр | Смысл | Типичное значение |
|---|---|---|
| `slidingWindowType` | окно: `COUNT_BASED` (по числу вызовов) или `TIME_BASED` (по секундам) | зависит от трафика |
| `slidingWindowSize` | размер окна | 20–100 вызовов / 10–60 сек |
| `minimumNumberOfCalls` | минимум вызовов до расчёта статистики | 10–20 |
| `failureRateThreshold` | % ошибок для размыкания | 50% |
| `slowCallRateThreshold` | % медленных вызовов для размыкания | 80–100% |
| `slowCallDurationThreshold` | что считать «медленным» | ~таймаут / 2 |
| `waitDurationInOpenState` | сколько сидим в OPEN | 5–30 сек |
| `permittedNumberOfCallsInHalfOpenState` | пробных вызовов в HALF_OPEN | 3–5 |

Два тонких, но критичных момента.

**1. `recordExceptions` / `ignoreExceptions`.** Считать провалом нужно только то, что говорит о нездоровье downstream: таймауты, `503`, `ConnectException`. А `HTTP 400 Bad Request` или `404` — это **ваша** ошибка или нормальный бизнес-ответ; засчитывать их в failure rate нельзя, иначе кривой клиент разомкнёт breaker и оставит всех без сервиса. По умолчанию учите breaker игнорировать бизнес-исключения (`4xx`).

**2. Circuit Breaker всегда стоит поверх таймаута.** Порядок «декораторов» на вызове обычно такой (снаружи внутрь): `Retry → CircuitBreaker → TimeLimiter/Timeout → сам вызов`. Таймаут гарантирует, что «медленный» вызов быстро становится измеримой ошибкой; breaker на основе этих ошибок принимает решение; retry (если он есть и операция идемпотентна) повторяет — но уже уважая состояние breaker'а: если цепь разомкнута, retry не долбит downstream.

---

## Пример на Java

Соберём реалистичный `PricingClient` с таймаутом, Circuit Breaker и fallback на кэш. Покажу и «руками» (чтобы понять механику), и через Resilience4j + Spring Boot (как делают в проде).

### Вариант «руками» — понять автомат

Не для прода, но отлично показывает суть. Минимальный count-based breaker:

```java
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public final class SimpleCircuitBreaker {

    private enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;          // сколько подряд ошибок → OPEN
    private final Duration openDuration;         // сколько сидим в OPEN

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile Instant openedAt = Instant.MIN;

    public SimpleCircuitBreaker(int failureThreshold, Duration openDuration) {
        this.failureThreshold = failureThreshold;
        this.openDuration = openDuration;
    }

    public <T> T call(Supplier<T> action) {
        if (state.get() == State.OPEN) {
            // прошло ли время «отдыха»? если да — пробуем (HALF_OPEN)
            if (Instant.now().isBefore(openedAt.plus(openDuration))) {
                throw new CallNotPermittedException(); // fail fast, downstream не трогаем
            }
            state.set(State.HALF_OPEN);
        }

        try {
            T result = action.get();
            onSuccess();
            return result;
        } catch (RuntimeException e) {
            onFailure();
            throw e;
        }
    }

    private void onSuccess() {
        consecutiveFailures.set(0);
        state.set(State.CLOSED); // пробный вызов прошёл — цепь замкнулась
    }

    private void onFailure() {
        // в HALF_OPEN любая ошибка сразу возвращает в OPEN
        if (state.get() == State.HALF_OPEN
                || consecutiveFailures.incrementAndGet() >= failureThreshold) {
            state.set(State.OPEN);
            openedAt = Instant.now();
        }
    }

    public static final class CallNotPermittedException extends RuntimeException { }
}
```

Здесь видно всё главное: в OPEN мы **не вызываем `action.get()` вообще** — вот она, экономия ресурсов. В HALF_OPEN один вызов решает судьбу цепи. В реальной жизни так писать не надо (нет скользящего окна, учёта медленных вызовов, метрик, потокобезопасной статистики) — для этого есть Resilience4j.

### Вариант для прода — Resilience4j + Spring Boot

Зависимость (Gradle):

```groovy
implementation 'io.github.resilience4j:resilience4j-spring-boot3:2.2.0'
implementation 'org.springframework.boot:spring-boot-starter-aop' // нужен для аннотаций
```

Конфигурация в `application.yml`. Обратите внимание на `ignoreExceptions` и `slowCallDurationThreshold`:

```yaml
resilience4j:
  circuitbreaker:
    instances:
      pricing:
        sliding-window-type: COUNT_BASED
        sliding-window-size: 20
        minimum-number-of-calls: 10           # не судим по первым 1-2 вызовам
        failure-rate-threshold: 50            # >50% ошибок в окне → OPEN
        slow-call-rate-threshold: 90          # >90% медленных → тоже OPEN
        slow-call-duration-threshold: 400ms   # медленный = дольше 400 мс
        wait-duration-in-open-state: 10s
        permitted-number-of-calls-in-half-open-state: 3
        automatic-transition-from-open-to-half-open-enabled: true
        record-exceptions:                    # ЭТО считаем провалом downstream
          - java.io.IOException
          - java.util.concurrent.TimeoutException
          - org.springframework.web.client.HttpServerErrorException  # 5xx
        ignore-exceptions:                    # ЭТО НЕ вина downstream — игнорируем
          - org.springframework.web.client.HttpClientErrorException  # 4xx
  timelimiter:
    instances:
      pricing:
        timeout-duration: 800ms               # жёсткий таймаут — фундамент под breaker
        cancel-running-future: true
```

Клиент с аннотациями. `@CircuitBreaker` + `fallbackMethod` — fallback вызывается и на ошибке downstream, и когда цепь разомкнута:

```java
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class PricingClient {

    private final RestClient restClient;
    private final PriceCache priceCache; // напр. Redis/Caffeine с последней известной ценой

    public PricingClient(RestClient restClient, PriceCache priceCache) {
        this.restClient = restClient;
        this.priceCache = priceCache;
    }

    @CircuitBreaker(name = "pricing", fallbackMethod = "priceFromCache")
    public Price getPrice(long sku) {
        return restClient.get()
            .uri("/prices/{sku}", sku)
            .retrieve()
            .body(Price.class);
    }

    // Сигнатура fallback = сигнатура метода + последний параметр Throwable.
    // Вызывается при провале ИЛИ при CallNotPermittedException (цепь OPEN).
    private Price priceFromCache(long sku, Throwable cause) {
        return priceCache.find(sku)
            .map(p -> p.withStale(true)) // помечаем «цена возможно устаревшая»
            .orElseThrow(() -> new PricingUnavailableException(sku, cause));
    }
}
```

Что мы получили по сравнению с наивной версией из «Проблемы»:

- Вызов ограничен **800 мс**, а не 30 секундами → поток Tomcat освобождается быстро.
- После серии таймаутов breaker **разомкнётся** → следующие запросы возвращаются мгновенно из кэша, downstream не трогаем.
- `4xx` не роняют breaker — кривой запрос не оставит всех без цен.
- Есть **деградация**: заказ оформится по последней известной цене вместо полного отказа.

### Наблюдаемость — обязательна

Разомкнутый breaker — это событие, о котором надо знать. Resilience4j публикует метрики в Micrometer (`resilience4j_circuitbreaker_state`, `..._calls`) и события состояния. Минимум — алерт «breaker `pricing` в состоянии OPEN дольше 1 минуты» и логирование переходов:

```java
circuitBreakerRegistry.circuitBreaker("pricing").getEventPublisher()
    .onStateTransition(e -> log.warn("CB pricing: {} -> {}",
        e.getStateTransition().getFromState(),
        e.getStateTransition().getToState()));
```

---

## Подводные камни

1. **Circuit Breaker без таймаута бесполезен.** Если сам HTTP-вызов может висеть 30 секунд, breaker «увидит» деградацию слишком поздно и всё равно даст потокам утечь. Всегда ставьте breaker **поверх** жёсткого таймаута (connect + read/response). Это грабля №1, на неё наступают чаще всего.

2. **`4xx` засчитываются как отказ downstream.** По умолчанию любое исключение — «провал». Клиент с багом начинает слать `400`, breaker размыкается — и теперь **никто** не может получить цену, хотя сервис жив. Явно перечисляйте `recordExceptions` (только 5xx/сеть/таймаут) и `ignoreExceptions` (4xx и бизнес-ошибки).

3. **Слишком агрессивные пороги → «мигающий» breaker.** `minimumNumberOfCalls: 1` или `failureRateThreshold: 10%` размыкают цепь на случайном сетевом всплеске и режут здоровый трафик. С другой стороны, слишком мягкие (`failureRateThreshold: 95%`) — breaker не сработает, пока сервис почти полностью не ляжет. Пороги подбираются под реальный профиль ошибок, не «из головы».

4. **Retry поверх breaker без jitter = retry storm.** Если на разомкнутый breaker навесить retry, который синхронно ждёт и повторяет, вы либо забьёте очередь ожидания, либо, когда downstream оживёт, все клиенты ломанутся одновременно (thundering herd) и уронят его снова. Retry только для **идемпотентных** операций, с экспоненциальным backoff **и jitter**, и он должен уважать `CallNotPermittedException` (не повторять её).

5. **Breaker per-instance, а не глобальный.** В Resilience4j состояние живёт в памяти пода. 10 подов — 10 независимых breaker'ов, каждый учится на своей статистике. Это нормально и обычно правильно, но учитывайте: маленькое окно на поде с малым трафиком набирается медленно, реакция запаздывает. Не ждите единого «глобального» решения без внешнего координатора.

6. **Fallback, который сам ходит по сети.** Классика: цепь к `pricing-service` разомкнулась, а fallback лезет в Redis — который тоже прилёг, потому что деградировал весь кластер. Fallback должен быть **дешёвым и локальным** либо мгновенно-отказным. Fallback, способный висеть или падать так же, как основной путь, воспроизводит исходную проблему.

7. **HALF_OPEN пропускает боевой трафик в пробу.** Пробные вызовы в HALF_OPEN — это реальные пользовательские запросы. Если downstream ещё нездоров, часть пользователей словит ошибку/латенси на «разведке». Это осознанный компромисс; просто помните о нём при настройке `permittedNumberOfCallsInHalfOpenState` и при расчёте SLO.

---

## Практическая задача

**Система.** `checkout-service` (Spring Boot, Java 21) на шаге оформления заказа синхронно вызывает внешний `promo-service` — узнать, применима ли промо-акция и какая скидка. `promo-service` — сторонняя команда, SLA у него так себе: пару раз в неделю он «залипает» на 10–40 секунд под нагрузкой, отвечая не ошибкой, а тишиной.

**Дано.** Сейчас код такой, и он ровно из-за отсутствия предохранителя роняет весь checkout:

```java
@Service
public class PromoClient {

    private final RestClient restClient;

    public Discount getDiscount(String cartId) {
        // Нет таймаута → read-timeout берётся дефолтный (фактически бесконечный).
        // Нет предохранителя → каждый залипший вызов держит поток Tomcat.
        return restClient.get()
            .uri("/promo/discount?cartId={id}", cartId)
            .retrieve()
            .body(Discount.class);
    }
}

@RestController
class CheckoutController {
    private final PromoClient promoClient;
    private final CheckoutService checkoutService;

    @PostMapping("/checkout")
    CheckoutResponse checkout(@RequestBody CheckoutRequest req) {
        Discount d = promoClient.getDiscount(req.cartId()); // тут всё и висит
        return checkoutService.finalize(req, d);
    }
}
```

Когда `promo-service` залипает, за считанные секунды все потоки Tomcat заняты ожиданием, и `checkout-service` перестаёт отвечать целиком — включая оформление заказов без промокода, которым `promo-service` вообще не нужен.

**Что важно для бизнеса:** промо-скидка — **необязательная** часть. Лучше оформить заказ без скидки (или с последней известной), чем не оформить вовсе.

**ТЗ. Реализуйте защиту вызова `promo-service` через Circuit Breaker так, чтобы деградация `promo-service` не роняла checkout.**

Требования:

1. Добавить **жёсткий таймаут** на вызов (ориентир: `read`/response ≤ 1 сек, `connect` ≤ 300 мс). Без него всё остальное не работает.
2. Обернуть вызов в **Circuit Breaker** (Resilience4j). Настроить окно, порог ошибок и/или порог медленных вызовов, `waitDurationInOpenState`.
3. Настроить, что считать отказом: таймауты и `5xx` — да; `4xx` (например, «промокод не найден») — **нет**.
4. Реализовать **fallback**: при ошибке или разомкнутой цепи вернуть «скидка не применена» (`Discount.none()`), а не пробрасывать исключение в контроллер. Checkout должен завершаться успешно.
5. Добавить **метрику/лог перехода** breaker'а в OPEN, чтобы дежурный узнал о проблеме.

**Критерии приёмки:**

- При искусственно «зависшем» `promo-service` (замокайте endpoint с задержкой 30 сек через WireMock) один вызов `checkout` завершается **быстро** (в пределах таймаута ~1 сек), а не за 30 сек.
- После серии подряд идущих таймаутов breaker переходит в **OPEN**: следующий `checkout` возвращается **практически мгновенно** (fallback, downstream не вызывается — проверьте по счётчику обращений в WireMock, что новых запросов к `promo-service` не идёт).
- При этом `checkout` **возвращает 200** и заказ оформляется с `Discount.none()`.
- Ответ `promo-service` со статусом **404** («промокод не найден») **не размыкает** breaker и обрабатывается как обычная бизнес-ситуация (тоже `Discount.none()`, но без ухода в OPEN).
- После восстановления `promo-service` breaker сам возвращается в **CLOSED** (через HALF_OPEN) и скидки снова применяются.

**Подсказки (без готового решения):**

- Порядок обёрток: таймаут — самый внутренний, breaker — над ним, fallback — снаружи. В Resilience4j `TimeLimiter` работает с `CompletableFuture`/реактивным типом; если оставляете синхронный `RestClient`, задайте таймауты на уровне HTTP-клиента (`ClientHttpRequestFactory`/`JdkClientHttpRequestFactory` с `Duration`), а breaker'у дайте `slowCallDurationThreshold` чуть меньше таймаута.
- `minimumNumberOfCalls` не ставьте в 1 — иначе первый же случайный таймаут разомкнёт цепь.
- Для критерия «downstream не вызывается в OPEN» удобно проверять `wireMockServer.verify(...)` / счётчик запросов, а не время ответа.
- Fallback-метод должен иметь ту же сигнатуру, что основной, плюс параметр `Throwable`. Убедитесь, что он **не** ходит по сети.
- Проверьте, что аннотации Resilience4j вообще срабатывают: нужен `spring-boot-starter-aop`, а метод с `@CircuitBreaker` должен вызываться **через бин** (не через `this.` из того же класса) — иначе прокси не сработает, как и с `@Transactional`.
- Тест на переходы состояний удобно писать, дергая `circuitBreakerRegistry.circuitBreaker("promo").getState()` и меняя поведение WireMock-стаба по ходу теста.

---

## Что почитать

- **Resilience4j — CircuitBreaker (официальная документация).** Полное описание состояний, скользящих окон, всех параметров и интеграции со Spring Boot: <https://resilience4j.readme.io/docs/circuitbreaker>
- **Amazon Builders' Library — «Timeouts, retries, and backoff with jitter»** (Marc Brooker). Почему таймаут — фундамент, как retry с jitter спасает от retry storm, как это сочетается с предохранителем: <https://aws.amazon.com/builders-library/timeouts-retries-and-backoff-with-jitter/>
- **Microsoft Azure Architecture Center — Circuit Breaker pattern.** Каноничное описание паттерна, диаграммы состояний, соображения по проектированию и антипаттерны: <https://learn.microsoft.com/en-us/azure/architecture/patterns/circuit-breaker>
- **Martin Fowler — «CircuitBreaker».** Первоисточник в популярном изложении, с наглядными переходами состояний и мотивацией: <https://martinfowler.com/bliki/CircuitBreaker.html>
