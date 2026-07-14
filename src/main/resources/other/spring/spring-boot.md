# Spring Boot 3

## Зачем это нужно / какую проблему решает

Представь: тебе поручили поднять простейший REST-сервис — принять HTTP-запрос, сходить в PostgreSQL, вернуть JSON. На «голом» Spring это выглядит так: подключаешь десяток jar-ов (spring-web, spring-webmvc, jackson, hibernate, hikari, драйвер БД) и вручную следишь, чтобы их версии не конфликтовали. Потом руками поднимаешь `DispatcherServlet`, регистрируешь `ViewResolver`, настраиваешь `DataSource`, создаёшь `EntityManagerFactory`, `TransactionManager`, оборачиваешь всё в `web.xml` и пачку XML-конфигов. Разворачиваешь `.war` в отдельно установленный Tomcat. День работы — и это ещё до первой строчки бизнес-логики.

Проблема одним словом — **boilerplate**: горы инфраструктурного кода и конфигурации, которые в 99% проектов одинаковы, но которые надо каждый раз писать заново и держать согласованными.

Spring Boot переворачивает подход: **convention over configuration**. Ты объявляешь *что* тебе нужно (стартер `spring-boot-starter-web` — «хочу веб»), а Boot сам решает *как* это собрать: подтягивает согласованный набор зависимостей, поднимает встроенный Tomcat, настраивает Jackson, `DataSource`, транзакции — всё с разумными дефолтами, которые ты переопределяешь только там, где реально надо. Приложение — это обычный `main()`, который запускается как `java -jar app.jar`. Никакого внешнего сервера приложений, никакого `web.xml`.

Результат: рабочий сервис поднимается за 15 минут, а ты сразу пишешь бизнес-логику, а не воюешь с инфраструктурой.

**Что важно про версию 3.x.** Spring Boot 3 — это водораздел:
- **Java 17+ обязательна** (Boot 3.2+ полноценно дружит с Java 21 — виртуальные потоки, records, pattern matching). Мы на Java 21.
- **javax → jakarta.** Вся Java EE переехала в Eclipse Foundation и сменила пакеты: `javax.persistence.*` → `jakarta.persistence.*`, `javax.validation.*` → `jakarta.validation.*`, `javax.servlet.*` → `jakarta.servlet.*`. Старые библиотеки с `javax` в Boot 3 **не заведутся**. Это первое, обо что спотыкаются при миграции.
- Hibernate 6, Spring Framework 6, Spring Security 6 (без `WebSecurityConfigurerAdapter`), поддержка нативной компиляции через GraalVM.

## Ключевые понятия

### Стартеры (starters)

Стартер — это «мета-зависимость»: пустой по коду артефакт, который тянет за собой согласованный набор библиотек под конкретную задачу. `spring-boot-starter-web` = Spring MVC + встроенный Tomcat + Jackson. `spring-boot-starter-data-jpa` = Spring Data JPA + Hibernate + HikariCP. Тебе не нужно подбирать версии руками — их фиксирует **BOM** (Bill of Materials) родительского `spring-boot-starter-parent` или `spring-boot-dependencies`. Ты пишешь зависимость без версии, версию проставляет Boot.

### Автоконфигурация (auto-configuration)

Сердце Boot. При старте Boot сканирует classpath и применяет сотни `@AutoConfiguration`-классов по принципу **условной регистрации бинов**: `@ConditionalOnClass` (если класс есть в classpath), `@ConditionalOnMissingBean` (если ты сам такой бин не объявил), `@ConditionalOnProperty` и т.д.

Логика простая: «вижу в classpath `DataSource` и драйвер PostgreSQL, а своего бина `DataSource` пользователь не создал — создам его сам из `spring.datasource.*`». Ключевой принцип — **твой бин всегда побеждает**: как только ты объявляешь свой `@Bean`, автоконфигурация отступает (`@ConditionalOnMissingBean`). Автоконфигурация — это разумные дефолты, а не клетка.

Хочешь увидеть, что именно применилось и почему — запусти с `--debug`, Boot напечатает **condition evaluation report**: positive/negative matches.

### Embedded-сервер

В Boot HTTP-сервер (по умолчанию Tomcat) встроен **внутрь** приложения как обычная библиотека. Артефакт — исполняемый «fat jar» со всеми зависимостями внутри. Деплой — это `java -jar app.jar`, а не «установи Tomcat и положи туда war». Это фундамент для контейнеризации: один jar = один Docker-образ = один процесс. Хочешь Jetty или Undertow вместо Tomcat — исключаешь стартер Tomcat и подключаешь другой.

### `@SpringBootApplication`

Одна аннотация на главном классе, объединяющая три:
- `@SpringBootConfiguration` — класс является источником бин-определений (`@Configuration`).
- `@EnableAutoConfiguration` — включить автоконфигурацию.
- `@ComponentScan` — сканировать пакет этого класса и вложенные на предмет `@Component`/`@Service`/`@Repository`/`@RestController`.

Отсюда важное правило: **главный класс кладут в корневой пакет проекта**, чтобы component scan увидел все нижележащие пакеты. Бины из пакета *выше или сбоку* не подхватятся.

### `application.yml` и внешняя конфигурация

Настройки живут вне кода — в `application.yml` (или `.properties`). Boot читает их из чётко определённой цепочки источников с приоритетами: аргументы командной строки > переменные окружения > `application-{profile}.yml` > `application.yml`. Это позволяет один и тот же jar гонять в dev/stage/prod, меняя только окружение.

**Профили** (`spring.profiles.active=prod`) подключают профильные файлы `application-prod.yml`. **Секреты** (пароли, токены) в yml **не хранят** — их подставляют через переменные окружения: `${DB_PASSWORD}`.

Типобезопасный доступ к настройкам — `@ConfigurationProperties`, который маппит ветку yml на Java-record.

### Слои: controller → service → repository

Стандартная трёхслойная архитектура, которую Boot поддерживает стереотипными аннотациями:

- **`@RestController`** — веб-слой. Принимает HTTP-запрос, валидирует вход, делегирует сервису, отдаёт ответ/статус. Тонкий: никакой бизнес-логики.
- **`@Service`** — бизнес-логика и границы транзакций (`@Transactional`). Оркестрирует репозитории.
- **`@Repository`** — доступ к данным. С Spring Data JPA — это интерфейс поверх `JpaRepository`, реализацию генерирует фреймворк.

Зависимости идут строго вниз: controller → service → repository. Контроллер не лезет в репозиторий напрямую. (В нашем роадмапе этому посвящён раздел «Слоистая архитектура» — здесь только фундамент.)

### Внедрение зависимостей (DI)

Boot — это IoC-контейнер: он создаёт бины и связывает их. Правильный способ — **конструкторная инъекция**: зависимости приходят в конструктор, поле объявляется `final`. Для класса с одним конструктором `@Autowired` не нужен, а Lombok `@RequiredArgsConstructor` генерирует конструктор по `final`-полям. Полевая инъекция (`@Autowired` на поле) — антипаттерн (см. «Подводные камни»).

### Actuator

`spring-boot-starter-actuator` добавляет production-ready эндпоинты для эксплуатации: `/actuator/health` (liveness/readiness для k8s), `/actuator/metrics` и `/actuator/prometheus` (метрики через Micrometer), `/actuator/info`, `/actuator/env`. По умолчанию по HTTP наружу торчит только `health` — остальное включают явно, потому что среди них есть чувствительные (`env`, `heapdump`).

### DevTools

`spring-boot-devtools` — инструмент для *локальной разработки*: автоперезапуск приложения при изменении classpath (быстрее полного рестарта за счёт двух класслоадеров), отключение кешей шаблонов, LiveReload. В prod-сборку не попадает (Boot сам исключает его из fat jar). Никогда не тащи devtools в продакшн.

## Примеры на Java

Соберём минимальный, но реалистичный сервис: сущность `Order`, репозиторий, сервис с транзакциями, REST-контроллер с валидацией, конфиг и типобезопасные настройки.

### Зависимости (Maven)

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.4.1</version> <!-- версии дочерних зависимостей фиксирует этот BOM -->
</parent>

<properties>
    <java.version>21</java.version>
</properties>

<dependencies>
    <!-- Web: Spring MVC + встроенный Tomcat + Jackson -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <!-- Data JPA: Spring Data + Hibernate 6 + HikariCP -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <!-- Bean Validation (jakarta.validation) -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
    <!-- Production-ready эндпоинты -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <!-- Драйвер PostgreSQL (версия из BOM) -->
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <scope>runtime</scope>
    </dependency>
    <!-- Только для локальной разработки; в fat jar не попадёт -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-devtools</artifactId>
        <scope>runtime</scope>
        <optional>true</optional>
    </dependency>
</dependencies>
```

### Главный класс

```java
package com.example.shop; // корневой пакет — component scan увидит всё ниже

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication // = @SpringBootConfiguration + @EnableAutoConfiguration + @ComponentScan
public class ShopApplication {
    public static void main(String[] args) {
        SpringApplication.run(ShopApplication.class, args);
    }
}
```

### Сущность (jakarta.persistence — НЕ javax)

```java
package com.example.shop.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String customerEmail;

    @Column(nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING) // хранить как текст 'NEW', а не как ordinal 0 (см. подводные камни)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected Order() {
        // требуется JPA
    }

    public Order(String customerEmail, BigDecimal amount) {
        this.customerEmail = customerEmail;
        this.amount = amount;
        this.status = OrderStatus.NEW;
        this.createdAt = Instant.now();
    }

    // Меняем состояние осмысленным методом, а не публичным сеттером статуса
    public void markPaid() {
        if (status != OrderStatus.NEW) {
            throw new IllegalStateException("Оплатить можно только новый заказ, текущий статус: " + status);
        }
        this.status = OrderStatus.PAID;
    }

    public Long getId() {
        return id;
    }

    public String getCustomerEmail() {
        return customerEmail;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
```

```java
package com.example.shop.order;

public enum OrderStatus {
    NEW, PAID, CANCELLED
}
```

### Репозиторий (Spring Data JPA)

```java
package com.example.shop.order;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

// Реализацию генерирует Spring Data — писать её руками не нужно
public interface OrderRepository extends JpaRepository<Order, Long> {

    // Query-метод: имя метода → SQL. Никакого тела.
    List<Order> findByStatus(OrderStatus status);

    Optional<Order> findByCustomerEmailAndStatus(String email, OrderStatus status);
}
```

### DTO на records (Java 21) с валидацией (jakarta.validation)

```java
package com.example.shop.order;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Instant;

public record CreateOrderRequest(
        @NotNull @Email String customerEmail,
        @NotNull @Positive BigDecimal amount
) {}

public record OrderResponse(
        Long id,
        String customerEmail,
        BigDecimal amount,
        OrderStatus status,
        Instant createdAt
) {
    static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getCustomerEmail(),
                order.getAmount(),
                order.getStatus(),
                order.getCreatedAt()
        );
    }
}
```

### Сервис (бизнес-логика + транзакции)

```java
package com.example.shop.order;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private final OrderRepository orderRepository;

    // Конструкторная инъекция: @Autowired не нужен для единственного конструктора
    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Transactional // пишущая операция — открываем транзакцию
    public OrderResponse create(CreateOrderRequest request) {
        var order = new Order(request.customerEmail(), request.amount());
        var saved = orderRepository.save(order);
        return OrderResponse.from(saved);
    }

    @Transactional
    public OrderResponse pay(Long orderId) {
        var order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        order.markPaid(); // dirty checking: Hibernate сам сделает UPDATE на коммите
        return OrderResponse.from(order);
    }

    @Transactional(readOnly = true) // для чтения — оптимизация, без dirty checking
    public List<OrderResponse> findByStatus(OrderStatus status) {
        return orderRepository.findByStatus(status).stream()
                .map(OrderResponse::from)
                .toList();
    }
}
```

```java
package com.example.shop.order;

public class OrderNotFoundException extends RuntimeException {
    public OrderNotFoundException(Long id) {
        super("Заказ не найден: " + id);
    }
}
```

### REST-контроллер (тонкий)

```java
package com.example.shop.order;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse create(@Valid @RequestBody CreateOrderRequest request) {
        return orderService.create(request); // делегируем и всё
    }

    @PostMapping("/{id}/pay")
    public OrderResponse pay(@PathVariable Long id) {
        return orderService.pay(id);
    }

    @GetMapping
    public List<OrderResponse> list(@RequestParam OrderStatus status) {
        return orderService.findByStatus(status);
    }
}
```

### Глобальная обработка ошибок

```java
package com.example.shop.order;

import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    // ProblemDetail (RFC 7807) — стандарт из Spring 6, отдельная библиотека не нужна
    @ExceptionHandler(OrderNotFoundException.class)
    public ProblemDetail handleNotFound(OrderNotFoundException ex) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handleConflict(IllegalStateException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }
}
```

### Типобезопасная конфигурация

```java
package com.example.shop.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Маппит ветку shop.* из application.yml на immutable-record
@ConfigurationProperties(prefix = "shop")
public record ShopProperties(
        int maxOrdersPerCustomer,
        String supportEmail
) {}
```

```java
package com.example.shop.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ShopProperties.class) // регистрируем properties-бин
public class AppConfig {
}
```

### application.yml

```yaml
spring:
  application:
    name: shop-service
  datasource:
    url: jdbc:postgresql://localhost:5432/shop
    username: shop
    # Секрет — из переменной окружения, НЕ хардкодим в файл
    password: ${DB_PASSWORD}
  jpa:
    hibernate:
      ddl-auto: validate   # в prod — validate/none; схемой рулит Liquibase/Flyway, не Hibernate
    properties:
      hibernate:
        jdbc:
          batch_size: 50
    open-in-view: false     # отключаем OSIV: транзакции — только в сервисе (см. подводные камни)

# Наши типобезопасные настройки
shop:
  max-orders-per-customer: 100
  support-email: support@example.com

# Actuator: наружу — только health + prometheus, ничего лишнего
management:
  endpoints:
    web:
      exposure:
        include: health, info, prometheus
  endpoint:
    health:
      probes:
        enabled: true   # /health/liveness и /health/readiness для Kubernetes
```

```yaml
# application-prod.yml — подключится при spring.profiles.active=prod
spring:
  jpa:
    hibernate:
      ddl-auto: validate
logging:
  level:
    root: INFO
    com.example.shop: INFO
```

## Частые ошибки / подводные камни

### 1. Полевая инъекция вместо конструкторной

`@Autowired private OrderRepository repo;` — антипаттерн. Поле нельзя сделать `final`, класс невозможно инстанцировать без Spring-контейнера (мешает тестам), скрытые зависимости легко разрастаются до «God-класса», а инъекция происходит после конструктора — в самом конструкторе зависимость ещё `null`. **Правильно:** конструкторная инъекция с `final`-полями (или Lombok `@RequiredArgsConstructor`). Тогда объект всегда в консистентном состоянии, а обязательность зависимости проверяется компилятором.

### 2. `@Transactional` на private-методе или self-invocation

`@Transactional` работает через Spring AOP-прокси. Прокси перехватывает только **внешние** вызовы через бин. Поэтому:
- на `private`/`protected`/package-private методе аннотация **не работает** — прокси не может его обернуть;
- вызов `this.otherMethod()` внутри того же класса идёт мимо прокси — транзакция/`@Cacheable`/`@Async` **не сработают**, даже если метод public.

**Правильно:** транзакционный метод — `public`, и вызывается через *другой* бин (или через self-инъекцию/`TransactionTemplate`). Классическая ловушка: «поставил `@Transactional`, а откат не происходит» — почти всегда это self-invocation.

### 3. N+1 запросов из-за ленивой загрузки

`@OneToMany`/`@ManyToOne` по умолчанию (для `@OneToMany` — LAZY) при обходе коллекции в цикле стреляет отдельным SELECT на каждую сущность: 1 запрос на список + N на связи. На проде это кладёт БД. **Правильно:** осознанно грузить связи — `JOIN FETCH` в JPQL, `@EntityGraph` на методе репозитория, или `@BatchSize` для батч-подгрузки. И включи `spring.jpa.show-sql` / p6spy локально, чтобы *видеть* реальные запросы.

### 4. `FetchType.EAGER` на всех связях «чтобы не падало»

Обратная крайность: навешать `EAGER` везды, чтобы не ловить `LazyInitializationException`. Тогда любой запрос к сущности тащит за собой весь граф связанных данных — по десятку JOIN-ов на ровном месте, даже когда связи не нужны. **Правильно:** оставлять LAZY по умолчанию, а нужные связи подгружать точечно через fetch-join/entity-graph под конкретный сценарий. `LazyInitializationException` лечится границей транзакции в сервисе (и `open-in-view: false`), а не глобальным EAGER.

### 5. Секреты прямо в `application.yml` и коммит в git

`password: super-secret-123` в yml, закоммиченный в репозиторий — утечка, которую потом не вычистить из истории. **Правильно:** секреты — через переменные окружения (`${DB_PASSWORD}`), Vault, Kubernetes Secrets или Spring Cloud Config. В yml остаётся только placeholder. Плюс профили: `application-local.yml` для локалки (в `.gitignore`), прод-секреты — из окружения.

### 6. `open-in-view` включён по умолчанию (OSIV)

Boot по умолчанию держит `spring.jpa.open-in-view=true`: Hibernate-сессия живёт до конца обработки HTTP-запроса, включая рендеринг ответа. Это маскирует N+1 (ленивые связи «внезапно» подгружаются во View-слое) и держит соединение с БД занятым дольше нужного — под нагрузкой пул исчерпывается. **Правильно:** `open-in-view: false` и явно грузить всё нужное внутри транзакции сервиса. Boot даже пишет warning при старте, если OSIV включён неявно — не игнорируй его.

### 7. Stateful-бин там, где нужен stateless

Синглтон-бин (а `@Service`/`@Controller` по умолчанию синглтоны) с изменяемым полем-состоянием (`private List<Order> currentBatch`) — гонка данных: один экземпляр обслуживает все параллельные запросы разными потоками. **Правильно:** сервисы держать stateless, состояние передавать через параметры/локальные переменные метода. Идемпотентность, ретраи, rate limiting — это отдельные механизмы (см. раздел роадмапа «Надёжность и архитектура»), а не поля в синглтоне.

## Практическая задача

**Контекст.** Ты пишешь сервис бронирования переговорок (`meeting-room-service`). Нужен REST-эндпоинт создания брони. Ключевое требование бизнеса: **на одну переговорку в один временной слот не может быть двух подтверждённых броней** — иначе два отдела приходят в одну комнату.

**Дано.**
- Пустой Spring Boot 3.4 проект (Java 21), PostgreSQL поднят через docker-compose.
- Стартеры: `web`, `data-jpa`, `validation`, `actuator`, драйвер postgres.
- Сущность `Booking` (поля: `id`, `roomId: Long`, `slotStart: Instant`, `slotEnd: Instant`, `bookedBy: String`, `status: BookingStatus {ACTIVE, CANCELLED}`) — часть полей нужно спроектировать самому.

**ТЗ.**
1. Слои `controller → service → repository`. Контроллер тонкий, вся логика — в сервисе.
2. `POST /api/v1/bookings` принимает JSON (`roomId`, `slotStart`, `slotEnd`, `bookedBy`), валидирует его через `jakarta.validation` (все поля обязательны, `slotEnd` позже `slotStart` — реализуй кастомную или составную проверку), возвращает `201 Created` с телом брони.
3. Если на пересекающийся слот той же комнаты уже есть **ACTIVE**-бронь — вернуть `409 Conflict` с телом `ProblemDetail` и внятным сообщением.
4. `GET /api/v1/bookings?roomId={id}` — список активных броней комнаты, отсортированных по `slotStart`.
5. `DELETE /api/v1/bookings/{id}` — перевести бронь в `CANCELLED` (не удалять физически), `204 No Content`.
6. Настройки: лимит `booking.max-active-per-user` (типобезопасный `@ConfigurationProperties`-record). При попытке создать бронь сверх лимита активных на пользователя — `422 Unprocessable Entity`.
7. `application.yml`: `open-in-view: false`, `ddl-auto: validate`, пароль БД — из переменной окружения. Actuator наружу — только `health` и `prometheus`.

**Критерии приёмки.**
- Приложение стартует `java -jar`; `/actuator/health` отдаёт `UP`.
- Все импорты — `jakarta.*`, ни одного `javax.*`.
- Конструкторная инъекция везде, `@Transactional` — на public-методах сервиса, вызываемых через бин.
- Дублирующая бронь на пересекающийся слот реально возвращает `409` (проверь двумя POST-запросами подряд).
- В логах при создании брони видно **не больше ожидаемого** числа SQL-запросов (нет N+1 при выборке пересечений).
- Секрет БД отсутствует в репозитории в открытом виде.

**Подсказки (без готового решения).**
- Пересечение интервалов: два отрезка `[a1,a2)` и `[b1,b2)` пересекаются, когда `a1 < b2 && b1 < a2`. Это ложится в один query-метод репозитория (`existsBy...` с двумя условиями по времени и `status = ACTIVE`) — не тащи все брони в память.
- Гонка «два одновременных POST на один слот» query-методом до конца не закрывается: рядом с проверкой имеет смысл **уникальный частичный индекс** в БД (`UNIQUE ... WHERE status = 'ACTIVE'`) и обработка `DataIntegrityViolationException` → `409`. Продумай оба уровня защиты — это тема идемпотентности/надёжности из соответствующего раздела роадмапа.
- Кастомная валидация «`slotEnd` > `slotStart`» — либо аннотация класс-уровня (`@AssertTrue`-метод на record), либо свой `ConstraintValidator`.
- Разные бизнес-ошибки — разные статусы: маппинг исключений на HTTP делай в `@RestControllerAdvice` через `ProblemDetail`, а не `try/catch` в контроллере.
- Не забудь `protected`-конструктор без аргументов в JPA-сущности и `@Enumerated(EnumType.STRING)` для статуса.

## Что почитать

- [Spring Boot 3 — официальная документация](https://docs.spring.io/spring-boot/index.html) — reference guide: автоконфигурация, external config, actuator.
- [Guide: Building a RESTful Web Service (spring.io)](https://spring.io/guides/gs/rest-service) — канонический getting-started от Spring.
- [Spring Boot 3.0 Migration Guide (javax → jakarta)](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-3.0-Migration-Guide) — что ломается при переходе на Boot 3 и как чинить.
- [Spring Data JPA — Reference](https://docs.spring.io/spring-data/jpa/reference/index.html) — query-методы, `@EntityGraph`, деривация запросов из имён.
- [Baeldung: The @ConfigurationProperties Guide](https://www.baeldung.com/configuration-properties-in-spring-boot) — типобезопасная конфигурация на практике.
