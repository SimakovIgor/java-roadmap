# Spring Data JPA

## Зачем это нужно / какую проблему решает

Представь: у тебя есть таблица `orders` и сущность `Order`. Тебе нужно «найти заказ по id», «найти все заказы продавца со статусом `PACKING`, отсортированные по дате», «постранично отдать заказы за последний месяц». В голом JDBC/JPA под каждую такую операцию ты пишешь один и тот же ритуал:

```java
// Классический DAO на «голом» EntityManager — так делать НЕ надо
public class OrderDaoImpl {
    @PersistenceContext
    private EntityManager em;

    public Optional<Order> findById(Long id) {
        return Optional.ofNullable(em.find(Order.class, id));
    }

    public List<Order> findBySellerAndStatus(Long sellerId, OrderStatus status) {
        return em.createQuery(
                "select o from Order o where o.sellerId = :s and o.status = :st", Order.class)
            .setParameter("s", sellerId)
            .setParameter("st", status)
            .getResultList();
    }
    // ... и так на каждый чих: save, delete, count, exists, paging...
}
```

Это **boilerplate** — механический код, который ничего не рассказывает о бизнесе, но который надо написать, покрыть тестами и поддерживать. На реальном сервисе таких DAO десятки, и 80% их методов — это тривиальные «найди по полю / сохрани / посчитай».

**Spring Data JPA** убирает этот слой почти целиком. Ты объявляешь **интерфейс**, а реализацию Spring генерирует в рантайме — по имени метода, по аннотации `@Query` или из набора стандартных CRUD-операций. Вместо сотен строк DAO — декларация контракта:

```java
public interface OrderRepository extends JpaRepository<Order, Long> {
    List<Order> findBySellerIdAndStatus(Long sellerId, OrderStatus status);
}
```

Всё. `save`, `findById`, `findAll`, `count`, `delete`, пагинация, сортировка — уже есть в `JpaRepository`. Метод `findBySellerIdAndStatus` Spring разберёт по имени и сгенерирует JPQL. Ты пишешь только то, что нетривиально, и тратишь время на бизнес-логику, а не на перекладывание строк из `ResultSet`.

Важно понимать границу: Spring Data JPA — это **тонкая обёртка над JPA/Hibernate**, а не отдельная ORM. Под капотом всё тот же `EntityManager`, тот же persistence context, те же правила транзакций и ленивой загрузки. Поэтому все классические грабли JPA (N+1, `LazyInitializationException`, каскады) никуда не деваются — просто теперь они спрятаны за красивым интерфейсом, и важно понимать, что происходит внутри.

## Ключевые понятия

### Иерархия репозиториев

Spring Data строит репозитории на иерархии интерфейсов-маркеров:

- `Repository<T, ID>` — пустой корневой маркер.
- `CrudRepository<T, ID>` — `save`, `findById`, `findAll`, `delete`, `count`, `existsById`.
- `PagingAndSortingRepository<T, ID>` — добавляет `findAll(Pageable)` и `findAll(Sort)`.
- `JpaRepository<T, ID>` — JPA-специфика: `saveAll`, `flush`, `saveAndFlush`, `getReferenceById`, `findAll` возвращает `List` (а не `Iterable`).

В 99% случаев наследуешь `JpaRepository<Entity, IdType>` и получаешь весь CRUD + пагинацию бесплатно. Реализацию (`SimpleJpaRepository`) Spring подставляет сам через прокси — своего класса писать не нужно.

### Derived query methods (запросы по имени метода)

Spring парсит **имя метода** и генерирует запрос. Грамматика: `find|read|get|query|count|exists|delete` + `By` + условия через `And`/`Or` + ключевые слова.

```java
List<Order> findBySellerId(Long sellerId);
List<Order> findBySellerIdAndStatus(Long sellerId, OrderStatus status);
List<Order> findByStatusInAndCreatedAtAfter(Collection<OrderStatus> statuses, Instant after);
Optional<Order> findFirstBySellerIdOrderByCreatedAtDesc(Long sellerId);
long countByStatus(OrderStatus status);
boolean existsByPublicId(String publicId);
```

Ключевые слова: `Between`, `LessThan`, `GreaterThanEqual`, `Like`, `Containing`, `StartingWith`, `In`, `IsNull`, `True/False`, `IgnoreCase`, `OrderBy...Asc/Desc`, `Distinct`, `First`/`Top`.

Правило: derived-методы хороши, пока имя читается как фраза. Как только имя превращается в `findBySellerIdAndStatusInAndCreatedAtBetweenAndDeletedFalseOrderByCreatedAtDesc` — это сигнал перейти на `@Query`.

### @Query — JPQL и native

Когда имя метода уже не тянет (сложные условия, join'ы, агрегаты, `update`), пишешь запрос явно.

**JPQL** (работает с сущностями и их полями, а не с таблицами/колонками):

```java
@Query("select o from Order o where o.sellerId = :sellerId and o.status = :status")
List<Order> findActive(@Param("sellerId") Long sellerId, @Param("status") OrderStatus status);
```

**Native** (сырой SQL, когда нужны специфичные фичи БД — оконные функции, `ON CONFLICT`, CTE):

```java
@Query(value = "select * from orders o where o.seller_id = :sellerId "
             + "and o.created_at > now() - interval '30 days'", nativeQuery = true)
List<Order> findRecentBySeller(@Param("sellerId") Long sellerId);
```

Модифицирующие запросы требуют `@Modifying` и активной транзакции:

```java
@Modifying
@Query("update Order o set o.status = :status where o.id = :id")
int updateStatus(@Param("id") Long id, @Param("status") OrderStatus status);
```

`@Modifying(clearAutomatically = true, flushAutomatically = true)` — если после массового `update` в той же транзакции продолжаешь работать с этими сущностями, иначе persistence context будет содержать устаревшие данные (bulk-запрос идёт мимо контекста первого уровня).

### Пагинация и сортировка (Pageable / Sort)

Не тащи всю таблицу в память — отдавай страницами.

```java
Page<Order> findBySellerId(Long sellerId, Pageable pageable);
Slice<Order> findByStatus(OrderStatus status, Pageable pageable);
```

- `Page<T>` — содержит контент + **общее число элементов** (делает дополнительный `count`-запрос). Нужен, когда рисуешь «страница 3 из 47».
- `Slice<T>` — знает только «есть ли следующая страница» (`hasNext()`), без `count`. Дешевле — для бесконечной прокрутки.
- `Pageable` собирается через `PageRequest.of(page, size, Sort.by(...))`.

`Sort` — типобезопасная сортировка, передаётся отдельно или внутри `Pageable`:

```java
var pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
```

### @Transactional

Транзакция — это граница, внутри которой живёт **persistence context** (кэш первого уровня) и в конце которой изменённые managed-сущности синхронизируются с БД (dirty checking + flush).

- Методы `JpaRepository` уже транзакционные: чтения — `readonly`, записи — обычные.
- Свою транзакцию открываешь на **сервисном** методе, когда несколько операций должны быть атомарны или когда нужен «грязный» апдейт через dirty checking без явного `save`.

```java
@Transactional
public void confirm(Long orderId) {
    Order order = orderRepository.findById(orderId).orElseThrow();
    order.confirm();                 // меняем состояние managed-сущности
    // save() не нужен: flush на коммите сам запишет изменения (dirty checking)
}
```

Два критичных факта, которые ломают половину проектов новичков:
1. Spring оборачивает бин **прокси**. Вызов `@Transactional`-метода **из того же класса через `this.method()`** транзакцию НЕ откроет (self-invocation минует прокси).
2. `@Transactional` на `private`/`protected`/package-private методе не работает — прокси перехватывает только `public` (для CGLIB — ещё и не `final`).

`@Transactional(readOnly = true)` на чтениях — не только «намерение», но и оптимизация: Hibernate переводит сессию в `FlushMode.MANUAL` (не делает лишних flush и dirty-check), и БД может маршрутизировать на реплику.

### Проблема N+1 и способы борьбы

**N+1** — самая частая проблема производительности в JPA. Ты грузишь список из N заказов (1 запрос), а потом в цикле обращаешься к ленивой связи `order.getItems()` — и Hibernate делает **ещё N запросов**, по одному на заказ.

```java
List<Order> orders = orderRepository.findBySellerId(sellerId); // 1 запрос
for (Order o : orders) {
    o.getItems().size();  // +N запросов — по одному на каждый заказ
}
```

Способы лечения:

**1. `@EntityGraph`** — декларативно говорим, какие связи подтянуть одним запросом (fetch join под капотом):

```java
@EntityGraph(attributePaths = {"items"})
List<Order> findBySellerId(Long sellerId);
```

**2. Fetch join в JPQL** — то же самое явно:

```java
@Query("select distinct o from Order o join fetch o.items where o.sellerId = :sellerId")
List<Order> findBySellerIdWithItems(@Param("sellerId") Long sellerId);
```

**3. `@BatchSize`** — Hibernate грузит ленивые связи пачками (`IN (...)`), превращая N+1 в N/размер_пачки запросов. Ставится на коллекцию или сущность.

Важно: **fetch join нескольких коллекций одновременно** порождает декартово произведение — join'ить `items` и `payments` в одном запросе нельзя (получишь дубли и предупреждение Hibernate). Тогда либо два `@EntityGraph`-запроса, либо `@BatchSize`.

### DTO-проекции

Не отдавай JPA-сущности наружу (в контроллер/API): тянешь лишние колонки, рискуешь `LazyInitializationException` вне транзакции, протекает доменная модель в контракт. Проецируй в DTO прямо на уровне запроса.

**Интерфейсные проекции** (Spring сам сгенерирует прокси, тянет только нужные колонки):

```java
public interface OrderSummary {
    Long getId();
    OrderStatus getStatus();
    Instant getCreatedAt();
}

List<OrderSummary> findBySellerId(Long sellerId);
```

**Классовые (DTO) проекции через конструктор в JPQL** — `record` идеально подходит:

```java
public record OrderView(Long id, OrderStatus status, Instant createdAt) {}

@Query("select new com.example.OrderView(o.id, o.status, o.createdAt) from Order o where o.sellerId = :s")
List<OrderView> findViews(@Param("s") Long sellerId);
```

Проекция дешевле полной сущности: меньше колонок в `SELECT`, не грузятся ленивые связи, объект detached-безопасен.

### Связь с Hibernate

Spring Data JPA — это **абстракция над JPA API** (`EntityManager`, аннотации `jakarta.persistence.*`). Реализация JPA по умолчанию в Spring Boot — **Hibernate**. Иерархия ответственности:

- **JPA** — спецификация (интерфейсы, аннотации): `@Entity`, `EntityManager`, JPQL.
- **Hibernate** — конкретная реализация: dirty checking, кэш первого уровня, ленивые прокси, диалекты SQL, `@BatchSize`, `hibernate.jdbc.batch_size`.
- **Spring Data JPA** — генерация репозиториев, derived-запросы, `Pageable`, декларативные транзакции поверх всего этого.

Поэтому «магия» Spring Data — это на самом деле поведение Hibernate: persistence context, flush на коммите, ленивая инициализация. Понимаешь Hibernate — понимаешь, почему Spring Data ведёт себя именно так.

## Примеры на Java

### Сущность (Jakarta EE, не javax!)

```java
package com.example.order.model;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Enumerated(EnumType.STRING)   // хранить enum строкой, не ORDINAL
    @Column(nullable = false)
    private OrderStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    // Ленивая связь по умолчанию (для коллекций FetchType.LAZY — и это правильно)
    @OneToMany(mappedBy = "order", fetch = FetchType.LAZY)
    private List<OrderItem> items = new ArrayList<>();

    protected Order() { /* required by JPA */ }

    public Order(Long sellerId) {
        this.sellerId = sellerId;
        this.status = OrderStatus.CREATED;
        this.createdAt = Instant.now();
    }

    // Изменение состояния — через доменные методы, не через публичные сеттеры
    public void confirm() {
        if (status != OrderStatus.CREATED) {
            throw new IllegalStateException("Cannot confirm order in status " + status);
        }
        this.status = OrderStatus.PACKING;
    }

    public Long getId() { return id; }
    public Long getSellerId() { return sellerId; }
    public OrderStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public List<OrderItem> getItems() { return List.copyOf(items); }
}
```

```java
package com.example.order.model;

public enum OrderStatus {
    CREATED, PACKING, DELIVERING, COMPLETED, CANCELED
}
```

### Репозиторий

```java
package com.example.order.repository;

import com.example.order.model.Order;
import com.example.order.model.OrderStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {

    // 1. Derived query — Spring генерирует JPQL по имени
    List<Order> findBySellerIdAndStatus(Long sellerId, OrderStatus status);

    boolean existsBySellerIdAndStatus(Long sellerId, OrderStatus status);

    // 2. Пагинация: Page делает count-запрос, Pageable несёт page/size/sort
    Page<Order> findBySellerId(Long sellerId, Pageable pageable);

    // 3. @EntityGraph — тянем items одним запросом, лечим N+1
    @EntityGraph(attributePaths = {"items"})
    Optional<Order> findWithItemsById(Long id);

    // 4. JPQL с fetch join — альтернатива EntityGraph
    @Query("select distinct o from Order o join fetch o.items where o.sellerId = :sellerId")
    List<Order> findBySellerIdWithItems(@Param("sellerId") Long sellerId);

    // 5. Native — специфичный SQL PostgreSQL (interval)
    @Query(value = "select * from orders o where o.seller_id = :sellerId "
                 + "and o.created_at > now() - interval '30 days'", nativeQuery = true)
    List<Order> findRecentBySeller(@Param("sellerId") Long sellerId);

    // 6. Модифицирующий запрос — нужен @Modifying + транзакция на вызывающем методе
    @Modifying(clearAutomatically = true)
    @Query("update Order o set o.status = :status where o.id = :id and o.status = :expected")
    int updateStatusIfCurrent(@Param("id") Long id,
                              @Param("expected") OrderStatus expected,
                              @Param("status") OrderStatus status);

    // 7. DTO-проекция через конструктор record прямо в JPQL
    @Query("select new com.example.order.dto.OrderView(o.id, o.status, o.createdAt) "
         + "from Order o where o.sellerId = :sellerId and o.createdAt > :after")
    List<com.example.order.dto.OrderView> findViews(@Param("sellerId") Long sellerId,
                                                    @Param("after") Instant after);
}
```

### DTO-проекция (record)

```java
package com.example.order.dto;

import com.example.order.model.OrderStatus;
import java.time.Instant;

public record OrderView(Long id, OrderStatus status, Instant createdAt) {}
```

### Сервис (конструкторная инъекция + транзакции)

```java
package com.example.order.service;

import com.example.order.dto.OrderView;
import com.example.order.model.Order;
import com.example.order.model.OrderStatus;
import com.example.order.repository.OrderRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private final OrderRepository orderRepository;

    // Конструкторная инъекция: final-поле, легко тестировать, нет скрытых зависимостей
    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Transactional(readOnly = true)
    public Page<Order> listBySeller(Long sellerId, int page, int size) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return orderRepository.findBySellerId(sellerId, pageable);
    }

    @Transactional(readOnly = true)
    public List<OrderView> recentViews(Long sellerId) {
        var after = Instant.now().minus(30, ChronoUnit.DAYS);
        return orderRepository.findViews(sellerId, after);
    }

    @Transactional
    public void confirm(Long orderId) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
        order.confirm();  // dirty checking запишет изменение на коммите, save() не нужен
    }

    // Атомарный переход статуса на уровне БД (optimistic-style guard через WHERE)
    @Transactional
    public boolean tryStartDelivery(Long orderId) {
        int updated = orderRepository.updateStatusIfCurrent(
            orderId, OrderStatus.PACKING, OrderStatus.DELIVERING);
        return updated == 1;
    }
}
```

### Контроллер (тонкий, только делегирует)

```java
package com.example.order.controller;

import com.example.order.dto.OrderView;
import com.example.order.model.Order;
import com.example.order.service.OrderService;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    public Page<Order> list(@RequestParam Long sellerId,
                            @RequestParam(defaultValue = "0") int page,
                            @RequestParam(defaultValue = "20") int size) {
        return orderService.listBySeller(sellerId, page, size);
    }

    @GetMapping("/recent")
    public List<OrderView> recent(@RequestParam Long sellerId) {
        return orderService.recentViews(sellerId);
    }

    @PostMapping("/{id}/confirm")
    public void confirm(@PathVariable Long id) {
        orderService.confirm(id);
    }
}
```

### Конфигурация (application.yml)

```yaml
spring:
  datasource:
    # Секреты — из переменных окружения / секрет-менеджера, НЕ хардкод в yml
    url: jdbc:postgresql://${DB_HOST:localhost}:5432/orders
    username: ${DB_USER}
    password: ${DB_PASSWORD}
  jpa:
    hibernate:
      ddl-auto: validate          # в проде только validate/none; схему ведёт Liquibase/Flyway
    open-in-view: false           # ОБЯЗАТЕЛЬНО выключить: иначе транзакция живёт весь HTTP-запрос
    properties:
      hibernate:
        jdbc.batch_size: 50       # батчинг INSERT/UPDATE
        order_inserts: true
        order_updates: true
        # Ловим N+1 на этапе разработки — падаем, если ленивую связь трогают вне транзакции
        query.fail_on_pagination_over_collection_fetch: true
    show-sql: false               # для отладки true, но лучше логировать через logger ниже
logging:
  level:
    org.hibernate.SQL: debug              # видеть генерируемый SQL
    org.hibernate.orm.jdbc.bind: trace    # видеть значения параметров (Hibernate 6)
```

### Зависимости (build.gradle, фрагмент)

```groovy
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-web'
    runtimeOnly 'org.postgresql:postgresql'
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.testcontainers:postgresql'
}
```

## Частые ошибки / подводные камни

**1. Полевая инъекция вместо конструкторной.** `@Autowired private OrderRepository repo;` мешает делать поле `final`, прячет зависимости, ломает тестируемость (нельзя подставить зависимость без рефлексии/контекста) и допускает создание объекта в невалидном состоянии. Всегда — конструкторная инъекция с `final`-полями (для одного конструктора `@Autowired` даже не нужен).

**2. N+1 незаметно убивает производительность.** Список из 100 заказов + обращение к `order.getItems()` в цикле = 101 запрос. На дев-базе с 10 строками не видно, на проде — таймауты. Лечи `@EntityGraph`/fetch join там, где точно нужны связанные данные, или DTO-проекцией, если данные связи не нужны вовсе. Включи `fail_on_pagination_over_collection_fetch` и логируй SQL, чтобы ловить это на разработке.

**3. `@Transactional` на private-методе или через self-invocation.** Прокси Spring перехватывает только `public`-вызовы **через бин**. `@Transactional private void save()` не откроет транзакцию вообще; `public` метод, вызванный как `this.otherTransactional()`, — тоже. Выноси транзакционный метод в отдельный бин или вызывай через инжектированную ссылку. (Подробнее — в правилах архитектуры проекта.)

**4. `FetchType.EAGER` везде «чтобы не падало».** EAGER на связях означает, что связь тянется **всегда**, даже когда не нужна — раздувает запросы, порождает скрытые join'ы и тот же N+1 при `findAll`. Держи связи `LAZY` (для коллекций это дефолт), а нужные данные подтягивай точечно через `@EntityGraph`/fetch join под конкретный сценарий.

**5. `open-in-view: true` (дефолт Spring Boot!).** Оставляет Hibernate-сессию открытой на весь HTTP-запрос, чтобы ленивые связи «волшебно» подгружались в контроллере/сериализаторе. Цена: транзакция/соединение держится дольше, N+1 переезжает в слой сериализации JSON, границы транзакций размываются. Ставь `spring.jpa.open-in-view: false` и грузи всё нужное явно в сервисном слое.

**6. Секреты в конфиге и `ddl-auto: update`/`create` в проде.** Пароль БД в `application.yml`, закоммиченном в git, — утечка. Бери из переменных окружения/секрет-менеджера. А `ddl-auto: update` даёт Hibernate менять схему прода на лету — непредсказуемо и опасно; в проде только `validate`/`none`, а схему ведёт Liquibase/Flyway отдельными миграциями.

**7. Отдача JPA-сущностей наружу и `LazyInitializationException`.** Вернул сущность с ленивой коллекцией из контроллера — при сериализации вне транзакции (с `open-in-view: false`) получишь `LazyInitializationException`; с включённым OSIV — скрытый N+1. Решение — DTO/проекции, собранные внутри транзакции.

**8. `Page` там, где хватило бы `Slice`.** `Page` всегда делает дополнительный `count(*)` — на больших таблицах это заметно. Если UI не показывает «страница X из Y», а просто «загрузить ещё» — используй `Slice` и сэкономь count-запрос.

## Практическая задача

**Контекст.** Ты пишешь бэкенд кабинета продавца. Есть таблица `orders` (сущность `Order` со связью `@OneToMany` на `OrderItem`) и таблица `order_items`. Фронт кабинета показывает список заказов продавца с фильтром по статусу, постранично, с превью числа позиций в каждом заказе. Сейчас endpoint отдаёт всю сущность `Order` целиком, грузит все заказы разом и в шаблоне дёргает `order.getItems().size()` — на продавце с 5000 заказов страница открывается 8 секунд, в логах видно сотни SQL-запросов.

**Дано:**
- Сущности `Order(id, sellerId, status, createdAt, items)` и `OrderItem(id, order, sku, quantity)`.
- `OrderStatus` — enum.
- Работающий Spring Boot 3.x + PostgreSQL проект, репозиторий пока пустой (`extends JpaRepository`).

**Задание.** Спроектировать выдачу списка заказов так, чтобы она была быстрой и корректной:

1. Реализуй endpoint `GET /api/v1/orders?sellerId=&status=&page=&size=`, возвращающий **страницу** заказов с сортировкой по `createdAt` DESC.
2. Наружу отдаётся **не сущность**, а DTO/проекция: `orderId`, `status`, `createdAt`, `itemsCount` (число позиций). Сущность `Order` за пределы сервисного слоя не выходит.
3. `itemsCount` должен считаться **без N+1** — не подгружай коллекцию `items` в цикле.
4. Endpoint отдаёт `Page` с корректным `totalElements`.
5. Добавь отдельный метод «детали заказа по id» с подгрузкой позиций **одним запросом** (fetch join или `@EntityGraph`), возвращающий DTO с массивом позиций.

**Критерии приёмки:**
- В логах Hibernate на запрос списка из N заказов — **фиксированное число запросов** (1 на данные + 1 на count), а не N+1. Проверь через `logging.level.org.hibernate.SQL: debug`.
- `spring.jpa.open-in-view: false` в конфиге, и при этом сериализация не падает с `LazyInitializationException`.
- Инъекция зависимостей — только конструкторная, поля `final`.
- Транзакции на чтениях помечены `@Transactional(readOnly = true)`.
- Endpoint деталей заказа делает ровно **один** SQL-запрос (проверь по логам), несмотря на подгрузку `items`.

**Подсказки (без готового решения):**
- `itemsCount` удобно считать прямо в JPQL: `select new ...Dto(o.id, o.status, o.createdAt, size(o.items)) ...` — функция `size()` превращается в подзапрос/агрегат и не грузит коллекцию. Альтернатива — `count`-проекция с `group by`.
- Для страницы с проекцией сигнатура репозитория: `Page<OrderListDto> findBySellerIdAndStatus(Long sellerId, OrderStatus status, Pageable pageable)` — но с конструкторной JPQL-проекцией понадобится явный `@Query` + отдельный `countQuery`.
- Для деталей — `@EntityGraph(attributePaths = "items")` на методе, возвращающем `Optional<Order>`, затем маппинг в DTO **внутри** транзакции.
- `Pageable` собери в сервисе через `PageRequest.of(page, size, Sort.by(DESC, "createdAt"))`, а не парси сортировку в контроллере.
- Помни про декартово произведение: fetch join одной коллекции — ок; если однажды понадобится тянуть две коллекции сразу — это уже `@BatchSize` или два запроса.

Если решишь добавить идемпотентность массовых операций над заказами, ретраи при конфликте статусов или rate limiting на endpoint — это уже смежная тема роадмапа, раздел «**Надёжность и архитектура**».

## Что почитать

- [Spring Data JPA — Reference Documentation](https://docs.spring.io/spring-data/jpa/reference/jpa.html) — официальная дока: derived queries, `@Query`, проекции, `Pageable`, кастомные реализации.
- [Spring Guide: Accessing Data with JPA](https://spring.io/guides/gs/accessing-data-jpa) — быстрый старт от команды Spring на актуальном Boot 3.
- [Hibernate 6 ORM — User Guide](https://docs.jboss.org/hibernate/orm/6.4/userguide/html_single/Hibernate_User_Guide.html) — как устроено под капотом: fetching-стратегии, batch fetching, dirty checking, N+1.
- [Baeldung: Spring Data JPA Guide](https://www.baeldung.com/the-persistence-layer-with-spring-data-jpa) — практические примеры проекций, пагинации и решения N+1.
