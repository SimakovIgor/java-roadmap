# Rate limiting (ограничение частоты)

## Проблема

Пятница, 14:30. Ты сидишь в чате дежурного, и туда прилетает алерт: p99 у `/api/v1/orders/search` уехал с 120 мс до 8 секунд. Пользователи в поддержке пишут «приложение висит». Пул соединений к PostgreSQL забит, Hikari логирует `Connection is not available, request timed out after 30000ms`. CPU на подах — 95%.

Ты идёшь в логи и группируешь запросы по клиенту. Картина: один интеграционный партнёр (назовём его «магазин-переезжант») запустил ночную выгрузку и льёт **900 запросов в секунду** на тяжёлый поисковый эндпоинт. Он не злоумышленник — у него просто цикл без пауз и 50 параллельных потоков. Но эффект такой же, как у DDoS: он забрал весь пул, все потоки Tomcat, все коннекты к базе. Остальные 4000 честных клиентов получают таймауты, потому что для них просто не осталось ресурсов.

Самое обидное — сервис написан «правильно». Вот типичный контроллер, который проходит ревью и выглядит безупречно:

```java
@RestController
@RequestMapping("/api/v1/orders")
public class OrderSearchController {

    private final OrderSearchService searchService;

    @PostMapping("/search")
    public SearchResponse search(@RequestBody SearchRequest request,
                                 @AuthenticationPrincipal SellerInfo seller) {
        // валидация, делегирование в сервис — всё по канону
        return searchService.search(seller.getId(), request);
    }
}
```

Здесь нет ни одной ошибки в бизнес-логике. Проблема в том, чего здесь **нет**: сервис принимает столько запросов, сколько в него влезет физически, и распределяет ресурсы по принципу «кто громче орёт». У одного клиента нет никакого потолка, поэтому один клиент способен утопить весь кластер. Горизонтальное масштабирование не спасает — ты просто дашь абузеру больше ресурсов, которые он с тем же успехом выжрет, а счёт за облако вырастет.

Нужен механизм, который скажет: «дорогой клиент, ты имеешь право на N запросов в секунду; всё сверх — вежливо отбивай, не трогая остальных». Это и есть rate limiting.

## Что это и когда применять

**Rate limiting** — это ограничение количества операций, которое источник (клиент, IP, API-ключ, продавец, эндпоинт) может выполнить за окно времени. Превышение лимита сервис не обслуживает: возвращает `429 Too Many Requests` (или ставит в очередь/тормозит), не тратя на абузера дорогие ресурсы.

Простыми словами: это турникет на входе в метро. Он пропускает людей с нормальной скоростью и не даёт толпе в одну секунду смять эскалатор. Пропускная способность станции конечна — турникет делает её предсказуемо распределённой.

Какие задачи решает:

- **Защита от перегрузки (fair use).** Гарантирует, что один шумный клиент не заберёт ресурсы у остальных. Это про справедливость, а не только про безопасность.
- **Защита от абуза и брутфорса.** Лимит на `/login` и `/password-reset` по IP+логину рушит перебор паролей и спам OTP.
- **Контроль стоимости.** Если каждый вызов дёргает платный внешний API (SMS, геокодер, LLM), лимит — это прямая экономия денег.
- **Защита нижележащих систем.** Твой сервис держит 5000 rps, а внешний банк-партнёр — 50 rps. Rate limiter на исходящих вызовах бережёт партнёра (и твой контракт с ним).
- **Тарифные планы.** Free — 10 rps, Business — 1000 rps. Rate limiting здесь — часть продукта.

**Когда НЕ нужно (частая ошибка — вешать лимитер на всё подряд):**

- **Внутренний трафик между твоими же сервисами в одном кластере.** Здесь лучше работают bulkhead, таймауты и circuit breaker. Rate limit между своими сервисами часто маскирует настоящую проблему — недостаточную ёмкость — и приводит к каскадным отказам на ровном месте.
- **Батч-джобы и потребители Kafka.** Там частоту регулируют размером пула/конкаренси консьюмера и backpressure, а не отбойником `429`. Кафка сама себе rate limiter через `max.poll.records` и число партиций.
- **Эндпоинты, где реальный дефицитный ресурс — не частота, а один конкретный внешний вызов или коннект.** Тогда точечно нужен bulkhead/семафор на этот ресурс, а не глобальный лимит на весь эндпоинт.
- **Когда у тебя ещё нет метрик.** Ставить лимит «на глаз» вслепую — это выбрать между «слишком слабо, не защищает» и «слишком строго, режем честных». Сначала измерь реальные профили нагрузки по клиентам, потом ставь порог.

Практическое правило: rate limiting — это про **вход извне** (клиент → твой API) и про **дорогой выход** (твой сервис → чужой ограниченный API). Внутри своего периметра предпочитай bulkhead + таймаут + circuit breaker.

## Как это работает

Ядро любого лимитера — счётчик, привязанный к ключу (`sellerId`, IP, API-key) и сбрасываемый/пополняемый со временем. Отличаются алгоритмы тем, как именно они считают и насколько ровно «размазывают» нагрузку.

### Token bucket (ведро токенов) — рабочая лошадка

У каждого ключа есть ведро вместимостью `capacity` токенов. Токены капают со скоростью `refillRate` в секунду. Каждый запрос забирает токен. Есть токен — пропускаем; ведро пусто — `429`.

```
refillRate = 5 ток/с, capacity = 10

     капает 5 ток/с
          |
          v
     ┌──────────┐
     │ ● ● ● ●  │  capacity = 10 (burst)
     │ ● ● ●    │
     └────┬─────┘
          │ запрос забирает 1 токен
          v
   есть токен? ──да──> пропустить (200)
          │
          нет
          v
     отбить (429 + Retry-After)
```

Ключевое свойство: `capacity` задаёт допустимый **всплеск** (burst). Клиент может простоять минуту молча, накопить 10 токенов и разом сделать 10 запросов — это ок. А устойчивая скорость всё равно ограничена `refillRate = 5/с`. Это удобно: реальные клиенты ходят рывками, и небольшой burst не считается абузом.

### Leaky bucket (дырявое ведро) — сглаживание

Запросы заливаются в очередь-ведро, а «вытекают» на обработку строго с постоянной скоростью. Всплеск на входе превращается в ровный поток на выходе; переполнение очереди — отказ.

```
入  ││││   рывками на входе
    v
 ┌─────┐
 │queue│  фиксированная ёмкость
 └──┬──┘
    │ вытекает РОВНО 5/с
    v
 обработка  ── ─ ── ─ ──  ровный поток
```

Отличие от token bucket: leaky bucket **не разрешает burst на выходе** — он именно сглаживает. Применяют, когда нижележащая система болезненно реагирует на пики (например, тот самый партнёр на 50 rps, которому нельзя разом 200).

### Fixed window vs sliding window

- **Fixed window**: считаем запросы в фиксированном окне (например, «100 в минуту, окно = календарная минута»). Просто, но есть эффект границы: 100 запросов в 12:00:59 и ещё 100 в 12:01:00 — фактически 200 за 2 секунды на стыке окон.
- **Sliding window (log/counter)**: окно скользит вместе с текущим моментом, границы нет. Точнее, но дороже по памяти/вычислениям. Sliding window counter — компромисс: взвешивает текущее и предыдущее окно.

Для большинства HTTP API дефолт — **token bucket**: он даёт естественный burst и дёшев. `leaky` — когда нужно сглаживание, `sliding window` — когда важна точность на границах (биллинг, квоты).

### Где считать: локально vs распределённо

- **Локальный лимитер (in-memory).** Счётчик живёт в памяти инстанса. Быстро, без зависимостей. Проблема: при N подах реальный лимит = `N × настроенный`, и он «плывёт» при автоскейле. Ок для грубой защиты одного инстанса.
- **Распределённый лимитер (Redis).** Единый счётчик на кластер. Точный глобальный лимит, но каждый запрос идёт в Redis (+RTT) и сам Redis становится критичной зависимостью. Атомарность обеспечивают Lua-скриптом или командой `INCR`+`EXPIRE`.

Правило выбора: точный тарифный лимит на клиента → Redis. Грубая защита инстанса от перегрузки → local.

### Что вернуть клиенту

Не молчи и не роняй коннект. Верни `429 Too Many Requests` и заголовок `Retry-After: <секунды>` (или `RateLimit-Reset`), чтобы вежливый клиент знал, когда повторить. Хорошая практика — также отдавать `RateLimit-Limit` и `RateLimit-Remaining`.

## Пример на Java

Разберём три уровня: (1) руками token bucket, чтобы понимать механику; (2) боевой локальный лимитер через Resilience4j; (3) распределённый лимитер на Redis для точного глобального лимита per-seller.

### 1. Token bucket руками (чтобы понимать, что внутри)

```java
/**
 * Потокобезопасный token bucket на один ключ.
 * refillRate — токенов в секунду, capacity — максимум (burst).
 */
public final class TokenBucket {

    private final double capacity;
    private final double refillPerNano;
    private double tokens;
    private long lastRefillNanos;

    public TokenBucket(double capacity, double refillRatePerSec) {
        this.capacity = capacity;
        this.refillPerNano = refillRatePerSec / 1_000_000_000.0;
        this.tokens = capacity;                 // стартуем полным ведром
        this.lastRefillNanos = System.nanoTime();
    }

    /** true — запрос разрешён (токен списан), false — лимит исчерпан. */
    public synchronized boolean tryConsume() {
        refill();
        if (tokens < 1.0) {
            return false;
        }
        tokens -= 1.0;
        return true;
    }

    private void refill() {
        long now = System.nanoTime();
        double add = (now - lastRefillNanos) * refillPerNano; // сколько накапало
        if (add > 0) {
            tokens = Math.min(capacity, tokens + add);        // не переполняем ведро
            lastRefillNanos = now;
        }
    }
}
```

Обрати внимание на две вещи: пополнение считается «лениво» по прошедшему времени (никакого фонового треда-таймера — это дёшево и точно), а `Math.min(capacity, ...)` не даёт накопить сверх ёмкости. Это и есть весь token bucket. В проде такой класс ты писать не будешь — возьмёшь библиотеку, — но понимать эти 20 строк обязан.

### 2. Боевой вариант: Resilience4j RateLimiter per-seller

Resilience4j даёт готовый лимитер с корректной семантикой окна и ожидания. Заведём по лимитеру на каждого продавца из `RateLimiterRegistry`.

```xml
<!-- build.gradle: implementation("io.github.resilience4j:resilience4j-ratelimiter:2.2.0") -->
```

```java
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;

import java.time.Duration;

@Component
public class SellerRateLimitGuard {

    private final RateLimiterRegistry registry;

    public SellerRateLimitGuard() {
        // 20 разрешений на каждый период в 1 секунду; ждать за разрешением не будем (0)
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(20)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ZERO)   // не блокируемся — сразу отказ, если нет разрешения
                .build();
        this.registry = RateLimiterRegistry.of(config);
    }

    /** Бросает RequestNotPermitted, если продавец превысил лимит. */
    public void check(long sellerId) {
        // отдельный лимитер на каждого продавца, создаётся по требованию
        RateLimiter limiter = registry.rateLimiter("seller-" + sellerId);
        RateLimiter.waitForPermission(limiter); // при timeout=0 либо проходит, либо кидает RequestNotPermitted
    }
}
```

Ловим отказ централизованно и превращаем в честный `429` с `Retry-After`:

```java
import io.github.resilience4j.ratelimiter.RequestNotPermitted;

@RestControllerAdvice
public class RateLimitExceptionHandler {

    @ExceptionHandler(RequestNotPermitted.class)
    public ResponseEntity<ApiError> handle(RequestNotPermitted ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, "1") // подскажем клиенту, когда повторить
                .body(new ApiError("RATE_LIMIT_EXCEEDED", "Too many requests, slow down"));
    }
}
```

Контроллер остаётся тонким — одна строка защиты в начале:

```java
@PostMapping("/search")
public SearchResponse search(@RequestBody SearchRequest request,
                             @AuthenticationPrincipal SellerInfo seller) {
    rateLimitGuard.check(seller.getId()); // guard clause: отобьём абузера до тяжёлой работы
    return searchService.search(seller.getId(), request);
}
```

Это защищает **один инстанс**. При трёх подах фактический потолок на продавца — 60 rps. Если нужен точный глобальный лимит — идём в Redis.

### 3. Распределённый лимитер на Redis (атомарный token bucket через Lua)

Единый счётчик на весь кластер. Всю логику «пополнить-проверить-списать» выполняем **атомарно** внутри Redis Lua-скриптом, иначе между `GET` и `SET` два пода прочитают одно значение и оба пройдут (race condition).

```lua
-- token_bucket.lua
-- KEYS[1] = ключ ведра (например rl:seller:42)
-- ARGV: capacity, refillPerSec, nowMillis, requested
local capacity     = tonumber(ARGV[1])
local refillPerSec = tonumber(ARGV[2])
local now          = tonumber(ARGV[3])
local requested    = tonumber(ARGV[4])

local data   = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(data[1])
local ts     = tonumber(data[2])
if tokens == nil then            -- первое обращение — ведро полное
  tokens = capacity
  ts = now
end

local delta = math.max(0, now - ts) / 1000.0
tokens = math.min(capacity, tokens + delta * refillPerSec)  -- пополнили по времени

local allowed = tokens >= requested
if allowed then
  tokens = tokens - requested
end

redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', now)
redis.call('PEXPIRE', KEYS[1], math.ceil(capacity / refillPerSec * 1000)) -- TTL, чтобы неактивные ключи не копились
return allowed and 1 or 0
```

Вызов скрипта из Spring через `StringRedisTemplate`:

```java
@Component
public class RedisRateLimiter {

    private final StringRedisTemplate redis;
    private final RedisScript<Long> script;

    public RedisRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
        // грузим Lua из ресурсов; Redis закеширует его по SHA
        this.script = RedisScript.of(
                new ClassPathResource("redis/token_bucket.lua"), Long.class);
    }

    /**
     * @return true — запрос разрешён глобально по кластеру.
     */
    public boolean tryAcquire(String key, int capacity, double refillPerSec) {
        Long allowed = redis.execute(
                script,
                List.of("rl:" + key),
                String.valueOf(capacity),
                String.valueOf(refillPerSec),
                String.valueOf(System.currentTimeMillis()),
                "1");
        return Long.valueOf(1L).equals(allowed);
    }
}
```

Guard поверх этого — с обязательным **fail-open** на случай недоступности Redis:

```java
public void check(long sellerId) {
    boolean allowed;
    try {
        allowed = redisRateLimiter.tryAcquire("seller:" + sellerId, 40, 20.0);
    } catch (RuntimeException redisDown) {
        // Redis лёг — НЕ роняем весь трафик из-за отказа лимитера.
        // Лучше временно пропустить, чем устроить полный отказ сервиса.
        log.warn("Rate limiter unavailable, failing open for seller {}", sellerId, redisDown);
        return;
    }
    if (!allowed) {
        throw new RequestNotPermitted("seller " + sellerId + " rate limit exceeded");
    }
}
```

Решение fail-open vs fail-closed — осознанное: для защиты от перегрузки обычно **fail-open** (доступность сервиса важнее идеального лимита), для защиты от брутфорса на `/login` — наоборот **fail-closed** (лучше отказать, чем пустить перебор).

## Подводные камни

1. **Локальный лимитер при нескольких инстансах «умножается» на число подов.** Настроил 20 rps, а под нагрузкой видишь 100 — потому что подов пять, и каждый считает свой счётчик. И при автоскейле реальный лимит скачет. Если нужен точный per-client лимит — только распределённый счётчик (Redis). Local годится лишь как грубая защита инстанса.

2. **Ключ лимита выбран не по тому измерению.** Лимит по IP рушится за корпоративным NAT (тысячи людей под одним IP) и за CDN/прокси (все запросы с IP балансировщика — читай `X-Forwarded-For`, но только доверенный). Для аутентифицированного API правильный ключ — `sellerId`/`apiKey`, а не IP. Часто нужна комбинация: `apiKey` для честных и `IP` для анонимных.

3. **Отсутствие burst убивает нормальных клиентов, избыток burst не защищает.** Если поставить `capacity == refillRate` (нет накопления), любой легитимный клиент с 5 параллельными запросами словит `429`. Если `capacity` огромный — абузер разом высадит весь burst и всё равно перегрузит базу. Настраивай `capacity` под реальный параллелизм честного клиента, `refillRate` — под ёмкость системы.

4. **Redis-лимитер без атомарности и без TTL.** Схема «`GET` → проверил → `SET`» из кода приложения — это гонка: два пода пройдут одновременно. Только Lua/атомарные команды. И без `PEXPIRE` ключи неактивных клиентов копятся вечно — Redis пухнет по памяти. TTL обязателен.

5. **Молчаливый отказ вместо честного `429`.** Если на превышении ты роняешь соединение, кидаешь `500` или просто вешаешь запрос — клиент не понимает, что происходит, и начинает **ретраить агрессивнее**, усугубляя перегрузку. Возвращай `429` + `Retry-After`, тогда корректный клиент отступит с backoff.

6. **Лимитер не покрывает стоимость запроса.** «100 запросов в минуту» звучит честно, но один запрос может тянуть 1 строку, а другой — экспорт на 2 млн строк. Клиент уложится в лимит по количеству и всё равно положит базу. Для тяжёлых эндпоинтов лимитируй по «весу» (списывай несколько токенов) или ставь отдельный, более строгий лимит.

7. **Rate limiter вместо capacity planning.** Когда лимитером глушат внутренний трафик между своими сервисами, чтобы «не падало», — это лечение симптома. Первопричина (мало ресурсов, N+1, тяжёлый запрос) остаётся, а `429` начинает прилетать честным внутренним вызовам и провоцирует каскад. Внутри периметра — bulkhead + таймаут + автоскейл, а не отбойник.

## Практическая задача

**Система.** Публичный API для партнёров маркетплейса. Есть эндпоинт синхронизации остатков: партнёр присылает батч обновлений склада. Эндпоинт дорогой — каждый вызов пишет в PostgreSQL и публикует событие в Kafka. Аутентификация — по `apiKey` (заголовок `X-Api-Key`), он же есть в `PartnerContext`.

**Дано (сейчас ломается ровно из-за отсутствия rate limiting):**

```java
@RestController
@RequestMapping("/api/v1/stock")
public class StockSyncController {

    private final StockSyncService stockSyncService;

    @PostMapping("/sync")
    public StockSyncResult sync(@RequestBody StockSyncRequest request,
                                @AuthenticationPrincipal PartnerContext partner) {
        // никакой защиты: сколько партнёр пришлёт — столько и обработаем
        return stockSyncService.sync(partner.apiKey(), request);
    }
}
```

Один партнёр запустил миграцию каталога и льёт ~600 rps на этот эндпоинт в 40 потоков. Пул Hikari (20 коннектов) выбран мгновенно, продюсер Kafka копит backlog, остальные партнёры получают таймауты. Инстансов сервиса — три (за балансировщиком), автоскейл до шести.

**ТЗ.** Реализовать per-partner rate limiting так, чтобы один партнёр не мог занять больше своей квоты, а остальные работали штатно.

Требования:
- Лимит — **на `apiKey`**, а не на IP и не глобальный на эндпоинт.
- Лимит должен быть **точным по всему кластеру** независимо от числа подов и автоскейла (подумай, почему in-memory здесь не подойдёт).
- Дефолтная квота: устойчиво **30 rps** с допустимым всплеском до **60** запросов.
- Превышение — ответ `429` с заголовком `Retry-After`; тело с машиночитаемым кодом ошибки.
- При недоступности инфраструктуры лимитера сервис не должен полностью падать — определи и обоснуй стратегию (fail-open/fail-closed именно для этого кейса).
- Контроллер остаётся тонким; защита не размазана по бизнес-логике.

Критерии приёмки (что проверить функциональным тестом):
- Партнёр A, шлющий выше квоты, начинает получать `429`, при этом партнёр B со своей нормальной нагрузкой продолжает получать `200` (изоляция по ключу).
- В пределах burst (60 запросов пачкой после паузы) все проходят; на устойчивых 100 rps часть отбивается.
- Ответ `429` содержит корректный `Retry-After`.
- Два инстанса с общим Redis суммарно не пропускают больше квоты (проверить, что лимит именно глобальный, а не ×2).
- Ключи неактивных партнёров в Redis протухают (проверить наличие TTL).

Подсказки (без готового решения):
- Начни с выбора алгоритма: тебе нужен burst — значит token bucket, а не leaky/fixed window.
- In-memory лимитер даст тебе `лимит × число подов` и поплывёт при автоскейле — это прямо противоречит требованию точности. Что даёт единый счётчик на кластер?
- «Проверить и списать» должно быть одной атомарной операцией на стороне хранилища счётчиков — иначе гонка между подами. Чем это сделать в Redis?
- Для burst=60 при 30 rps подумай, что такое `capacity` и что такое `refillRate` в терминах token bucket.
- Не забудь TTL на ключах и обработку отказа хранилища. Для защиты от перегрузки (а не от брутфорса) какая стратегия отказа безопаснее для доступности?
- Отдельно подумай про заголовки `RateLimit-Remaining`/`Retry-After` — как их вернуть, не протаскивая детали лимитера в контроллер (спойлер: `@RestControllerAdvice`).

## Что почитать

- [Resilience4j — RateLimiter](https://resilience4j.readme.io/docs/ratelimiter) — конфигурация, семантика `limitForPeriod`/`limitRefreshPeriod`/`timeoutDuration`, интеграция со Spring Boot и метриками.
- [AWS Builders' Library — Timeouts, retries, and backoff with jitter](https://aws.amazon.com/builders-library/timeouts-retries-and-backoff-with-jitter/) — почему отбитый клиент должен отступать с backoff+jitter, а не долбить сильнее (обратная сторона rate limiting со стороны клиента).
- [Stripe Engineering — Scaling your API with rate limiters](https://stripe.com/blog/rate-limiters) — практический разбор token bucket, разных типов лимитов (request-rate, concurrency, load shedding) на реальном платёжном API.
- [Redis — Pattern: Rate limiting](https://redis.io/docs/latest/develop/use-cases/rate-limiting/) и [`INCR` rate limiter](https://redis.io/docs/latest/commands/incr/) — распределённые счётчики, атомарность, Lua-скрипты.
