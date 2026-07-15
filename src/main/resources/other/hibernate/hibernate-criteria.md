# Hibernate/JPA Criteria API

![Раздел](https://img.shields.io/badge/раздел-Hibernate%20·%202%20из%202-3b82f6)
![Уровень](https://img.shields.io/badge/уровень-middle%20%E2%86%92%20senior-16a34a)
![Стек](https://img.shields.io/badge/стек-Java%2021%20·%20JPA%20·%20Criteria%20API-8b5cf6)

## Зачем это нужно / какую проблему решает

Представь типичный экран каталога: пользователь ищет заказы. Сверху, панель фильтров: статус, продавец, диапазон дат, минимальная сумма, поиск по подстроке в номере. Любой из фильтров может быть заполнен, а может быть пустым. Комбинаций, сотни. Бэкенд обязан построить ровно один SQL-запрос, где `WHERE` содержит только те условия, что реально пришли.

Первое, что делает почти каждый, собирает JPQL строкой:

```java
// НИКОГДА так не делайте
String jpql = "SELECT o FROM SellerOrder o WHERE 1=1";
if (status != null) {
    jpql += " AND o.status = '" + status + "'";      // SQL-инъекция
}
if (search != null) {
    jpql += " AND o.number LIKE '%" + search + "%'";  // и снова инъекция
}
if (minAmount != null) {
    jpql += " AND o.amount >= " + minAmount;
}
```

Что здесь не так, всё:

- **SQL/JPQL-инъекция.** Конкатенация пользовательского ввода в текст запроса, классическая дыра. `status = "'; DROP ..."`, и вы в новостях.
- **`WHERE 1=1` и ручная склейка `AND`.** Костыль, чтобы не думать, где ставить первый `AND`. Читается плохо, ломается легко.
- **Опечатки ловятся только в рантайме.** Написали `o.stauts`, узнаете, когда упадёт запрос в проде, а не при компиляции.
- **Рефакторинг ломает молча.** Переименовали поле `number` → `publicId` в сущности, строковый `"o.number"` про это не узнает. IDE не подсветит, тесты может не покрыть.
- **Кошмар с параметрами и типами.** `LIKE '%...%'`, экранирование, даты, `IN (...)` с переменным числом элементов, всё руками.

Правильный способ параметризации через `setParameter` спасает от инъекций, но не спасает от главной боли **динамических запросов**: когда набор условий заранее неизвестен, JPQL как строку всё равно приходится собирать кусками. Именно здесь выходит на сцену **Criteria API**, способ строить запрос из типобезопасных объектов-кирпичиков, а не из строки. Условия (`Predicate`) складываются в список, а финальный SQL Hibernate генерирует сам, корректно и с bind-параметрами.

Criteria API это «запрос как код»: компилятор проверяет структуру, IDE делает автодополнение и рефакторинг, а инъекции становятся невозможны by design.

## Ключевые понятия

### EntityManager и CriteriaBuilder

Всё начинается с `EntityManager` (в Spring его дают инжектом через `@PersistenceContext` или получают из репозитория). Из него берётся **`CriteriaBuilder`**, фабрика всего: запросов, предикатов, выражений, сортировок.

```java
CriteriaBuilder cb = entityManager.getCriteriaBuilder();
```

Думай о `CriteriaBuilder` как о наборе функций-конструкторов: `cb.equal(...)`, `cb.like(...)`, `cb.and(...)`, `cb.greaterThanOrEqualTo(...)`, `cb.asc(...)`.

### CriteriaQuery, сам запрос

**`CriteriaQuery<T>`** это дерево запроса: что выбираем (`select`), откуда (`from`), по какому условию (`where`), как сортируем (`orderBy`), как группируем (`groupBy`). Тип `<T>`, тип результата.

```java
CriteriaQuery<SellerOrder> query = cb.createQuery(SellerOrder.class);
```

### Root, точка входа в сущность

**`Root<T>`** это `FROM`-сущность и одновременно способ обращаться к её полям. `root.get("status")`, путь к атрибуту. Через `Root` строятся и джойны.

```java
Root<SellerOrder> root = query.from(SellerOrder.class);
query.select(root);                       // SELECT o FROM SellerOrder o
Path<Object> statusPath = root.get("status");
```

### Predicate, условие

**`Predicate`**, одно логическое условие для `WHERE`/`HAVING`. Их можно собирать в список и комбинировать через `cb.and(...)`/`cb.or(...)`. Именно предикаты решают проблему динамики: не пришёл фильтр, не добавили предикат.

```java
List<Predicate> predicates = new ArrayList<>();
predicates.add(cb.equal(root.get("status"), OrderStatus.PACKING));
predicates.add(cb.greaterThanOrEqualTo(root.get("amount"), 1000L));
query.where(cb.and(predicates.toArray(Predicate[]::new)));
```

### Expression, Order, join

- **`Expression<T>`**, любое вычислимое значение: путь к полю, `cb.lower(path)`, `cb.count(root)`, арифметика.
- **`Order`**, сортировка: `cb.asc(root.get("createdAt"))`.
- **`Join`**, связь с другой сущностью: `root.join("items")`. Для загрузки связи в тот же запрос (борьба с N+1), `root.fetch("items")`.

### TypedQuery, выполнение

`CriteriaQuery` это только описание. Чтобы выполнить, оборачиваем в `TypedQuery` через тот же `EntityManager`:

```java
List<SellerOrder> result = entityManager.createQuery(query).getResultList();
```

### Metamodel: `get("status")` vs `SellerOrder_.status`

Строковый `root.get("status")`, типобезопасен по структуре запроса, но имя поля всё ещё строка. Настоящая типобезопасность, **JPA Static Metamodel**: аннотационный процессор Hibernate генерирует класс `SellerOrder_` с полями-атрибутами.

```java
root.get(SellerOrder_.status)   // компилятор проверит и поле, и его тип
```

Переименовали поле, код с `SellerOrder_.status` перестанет компилироваться. Это и есть «typesafe».

### Как это соотносится с JPQL и Spring Data Specifications

- **JPQL**, компактно и читаемо для *статических* запросов, где условия фиксированы. Для динамики превращается в строковую склейку.
- **Criteria API**, многословно, но идеально для *динамических* запросов и рефакторинг-безопасно.
- **Spring Data JPA Specifications**, тонкая обёртка над Criteria API. `Specification<T>`, это, по сути, «фабрика `Predicate`», которые удобно комбинировать (`Specification.where(a).and(b)`). Под капотом, всё тот же `CriteriaBuilder`. В боевом Spring-коде чаще пишут именно Specifications, а голый Criteria API, когда нужен полный контроль (сложные проекции, подзапросы, агрегаты).

Правило выбора: **фиксированный запрос → JPQL/`@Query`; динамический набор фильтров → Specifications (или Criteria API напрямую).**

## Примеры на Java

### Сущность (Jakarta, не javax!)

```java
package com.example.orders.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "seller_order")
public class SellerOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String number;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(nullable = false)
    private Long amount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    // LAZY по умолчанию, грузим связь осознанно через fetch/join
    @OneToMany(mappedBy = "order", fetch = FetchType.LAZY)
    private List<OrderItem> items = new ArrayList<>();

    protected SellerOrder() { } // требуется JPA

    // геттеры опущены для краткости
}
```

```java
package com.example.orders.model;

public enum OrderStatus {
    CREATED, PACKING, DELIVERING, COMPLETED, CANCELED
}
```

### DTO фильтра и критерии приёмки запроса (record, Java 21)

```java
package com.example.orders.web;

import com.example.orders.model.OrderStatus;
import java.time.Instant;

// null-поле = фильтр не задан
public record OrderFilter(
        OrderStatus status,
        Long sellerId,
        String numberContains,
        Long minAmount,
        Instant createdFrom,
        Instant createdTo
) { }
```

### Репозиторий на «голом» Criteria API

Это `@Repository` (слой доступа к данным). Бизнес-логики здесь нет, только сборка запроса.

```java
package com.example.orders.repository;

import com.example.orders.model.OrderStatus;
import com.example.orders.model.SellerOrder;
import com.example.orders.web.OrderFilter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class SellerOrderCriteriaRepository {

    @PersistenceContext
    private EntityManager em;

    public List<SellerOrder> search(OrderFilter filter, int page, int size) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<SellerOrder> query = cb.createQuery(SellerOrder.class);
        Root<SellerOrder> root = query.from(SellerOrder.class);

        List<Predicate> predicates = buildPredicates(cb, root, filter);

        query.select(root)
                .where(cb.and(predicates.toArray(Predicate[]::new)))
                .orderBy(cb.desc(root.get("createdAt")));

        return em.createQuery(query)
                .setFirstResult(page * size)   // пагинация: OFFSET
                .setMaxResults(size)           // LIMIT
                .getResultList();
    }

    // Сборка предикатов, сердце динамического запроса.
    // Условие добавляется в список ТОЛЬКО если фильтр реально задан.
    private List<Predicate> buildPredicates(CriteriaBuilder cb,
                                            Root<SellerOrder> root,
                                            OrderFilter f) {
        List<Predicate> predicates = new ArrayList<>();

        if (f.status() != null) {
            predicates.add(cb.equal(root.get("status"), f.status()));
        }
        if (f.sellerId() != null) {
            predicates.add(cb.equal(root.get("sellerId"), f.sellerId()));
        }
        if (f.numberContains() != null && !f.numberContains().isBlank()) {
            // регистронезависимый LIKE; параметр биндится, инъекция невозможна
            predicates.add(cb.like(
                    cb.lower(root.get("number")),
                    "%" + f.numberContains().toLowerCase() + "%"));
        }
        if (f.minAmount() != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("amount"), f.minAmount()));
        }
        if (f.createdFrom() != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), f.createdFrom()));
        }
        if (f.createdTo() != null) {
            predicates.add(cb.lessThan(root.get("createdAt"), f.createdTo()));
        }

        return predicates;
    }

    // Пример агрегата: сколько заказов в статусе у продавца
    public long countByStatus(Long sellerId, OrderStatus status) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<SellerOrder> root = query.from(SellerOrder.class);

        query.select(cb.count(root))
                .where(cb.and(
                        cb.equal(root.get("sellerId"), sellerId),
                        cb.equal(root.get("status"), status)));

        return em.createQuery(query).getSingleResult();
    }
}
```

### То же самое через Spring Data Specifications (рекомендуемый способ в Spring-проектах)

Спецификация, переиспользуемая «фабрика предиката». Их удобно комбинировать.

```java
package com.example.orders.repository;

import com.example.orders.model.OrderStatus;
import com.example.orders.model.SellerOrder;
import java.time.Instant;
import org.springframework.data.jpa.domain.Specification;

public final class OrderSpecifications {

    private OrderSpecifications() { }

    public static Specification<SellerOrder> hasStatus(OrderStatus status) {
        // возвращаем null, если фильтр не задан, Spring Data сам его отбросит
        return status == null
                ? null
                : (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<SellerOrder> numberContains(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return (root, query, cb) ->
                cb.like(cb.lower(root.get("number")), "%" + text.toLowerCase() + "%");
    }

    public static Specification<SellerOrder> amountAtLeast(Long min) {
        return min == null
                ? null
                : (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("amount"), min);
    }

    public static Specification<SellerOrder> createdBetween(Instant from, Instant to) {
        return (root, query, cb) -> {
            if (from != null && to != null) {
                return cb.between(root.get("createdAt"), from, to);
            }
            if (from != null) {
                return cb.greaterThanOrEqualTo(root.get("createdAt"), from);
            }
            if (to != null) {
                return cb.lessThan(root.get("createdAt"), to);
            }
            return null;
        };
    }
}
```

```java
package com.example.orders.repository;

import com.example.orders.model.SellerOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

// JpaSpecificationExecutor даёт findAll(Specification, Pageable) из коробки
public interface SellerOrderRepository
        extends JpaRepository<SellerOrder, Long>,
                JpaSpecificationExecutor<SellerOrder> {
}
```

### Сервис, здесь бизнес-логика и оркестрация

```java
package com.example.orders.service;

import com.example.orders.model.SellerOrder;
import com.example.orders.repository.OrderSpecifications;
import com.example.orders.repository.SellerOrderRepository;
import com.example.orders.web.OrderFilter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderSearchService {

    private final SellerOrderRepository repository;

    // Конструкторная инъекция, не полевая. Тестируемо и final.
    public OrderSearchService(SellerOrderRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Page<SellerOrder> search(OrderFilter filter, int page, int size) {
        Specification<SellerOrder> spec = Specification
                .where(OrderSpecifications.hasStatus(filter.status()))
                .and(OrderSpecifications.numberContains(filter.numberContains()))
                .and(OrderSpecifications.amountAtLeast(filter.minAmount()))
                .and(OrderSpecifications.createdBetween(
                        filter.createdFrom(), filter.createdTo()));

        var pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return repository.findAll(spec, pageable);
    }
}
```

### Контроллер, тонкий

```java
package com.example.orders.web;

import com.example.orders.model.OrderStatus;
import com.example.orders.model.SellerOrder;
import com.example.orders.service.OrderSearchService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

@RestController
@Validated
public class OrderSearchController {

    private final OrderSearchService searchService;

    public OrderSearchController(OrderSearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping("/api/orders")
    public Page<SellerOrder> search(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) Long sellerId,
            @RequestParam(required = false) String numberContains,
            @RequestParam(required = false) Long minAmount,
            @RequestParam(required = false) Instant createdFrom,
            @RequestParam(required = false) Instant createdTo,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {

        var filter = new OrderFilter(
                status, sellerId, numberContains, minAmount, createdFrom, createdTo);
        return searchService.search(filter, page, size);
    }
}
```

### Включаем static metamodel (typesafe get)

Подключаем аннотационный процессор Hibernate, он сгенерирует `SellerOrder_`:

```groovy
// build.gradle
dependencies {
    annotationProcessor 'org.hibernate.orm:hibernate-jpamodelgen:6.6.0.Final'
}
```

После сборки в коде можно писать `root.get(SellerOrder_.status)` вместо `root.get("status")`, и получить проверку на этапе компиляции.

### Пример конфигурации (application.yml)

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/orders
    username: ${DB_USER}          # секреты, из переменных окружения, НЕ в yml
    password: ${DB_PASSWORD}
  jpa:
    hibernate:
      ddl-auto: validate          # схему ведём миграциями (Liquibase/Flyway), не Hibernate
    properties:
      hibernate:
        format_sql: true
    open-in-view: false           # выключаем OSIV: LazyInit только в транзакции сервиса
```

## Частые ошибки / подводные камни

### 1. Строковая конкатенация JPQL/SQL вместо предикатов

Главная причина этого урока. Любая склейка пользовательского ввода в текст запроса, инъекция и невозможность рефакторинга. Динамику собираем списком `Predicate` (или комбинацией `Specification`), значения всегда идут bind-параметрами.

### 2. N+1 при обращении к LAZY-связи

Выбрали 100 заказов, потом в цикле дёрнули `order.getItems()`, Hibernate сделает 1 + 100 запросов. В Criteria лечится fetch-join:

```java
root.fetch("items", JoinType.LEFT);
query.distinct(true);   // fetch коллекции размножает строки, нужен distinct
```

Осторожно: **fetch коллекции ломает пагинацию** (`setMaxResults` в этом случае применяется в памяти, Hibernate предупредит в логе `HHH000104`). Для «список + связь + пагинация» используй двухшаговый подход: сначала выбери ID страницы, потом отдельным запросом подтяни связи по этим ID.

### 3. `open-in-view: true` (по умолчанию, включено!)

Spring Boot по умолчанию держит `EntityManager` открытым до конца HTTP-ответа. LAZY-поля тогда «дозагружаются» при сериализации в контроллере, незаметный N+1 и запросы вне транзакции сервиса. Ставь `spring.jpa.open-in-view: false` и грузи всё нужное явно (fetch/`@EntityGraph`) внутри `@Transactional`-метода сервиса.

### 4. Полевая инъекция вместо конструкторной

```java
@Autowired private SellerOrderRepository repository;  // плохо
```

Поле нельзя сделать `final`, тяжело тестировать без Spring, скрываются циклические зависимости. Всегда, конструктор (в single-конструкторе `@Autowired` даже не нужен).

### 5. `@Transactional` на private-методе или при self-invocation

`@Transactional` работает через Spring-прокси. Аннотация на `private`-методе, молча игнорируется. Вызов `this.otherTransactional()` внутри того же бина минует прокси, транзакция не откроется. Транзакционный метод должен быть `public` и вызываться извне (через инжектированный бин).

### 6. EAGER «на всякий случай»

`@ManyToOne(fetch = EAGER)` / `@OneToMany(fetch = EAGER)` тянет связь в *каждом* запросе, даже когда она не нужна, и порождает неубираемый N+1. Дефолт: связи, **LAZY**, а нужные грузим точечно fetch-join'ом или `@EntityGraph`.

### 7. Забытый `query.select(root)` / неверный тип результата

`createQuery(SellerOrder.class)`, но `select` указывает на `root.get("amount")` (тип `Long`), рантайм-ошибка несоответствия типов. Тип `CriteriaQuery<T>`, `Root`, `select`-выражения и `getResultList()` должны быть согласованы. Для проекций заводи отдельный `CriteriaQuery<SomeDto>` с `cb.construct(SomeDto.class, ...)`.

### 8. Секреты в конфиге

Пароль БД строкой прямо в `application.yml`, закоммиченном в git, утечка. Секреты, через переменные окружения / vault, в yml только плейсхолдер `${DB_PASSWORD}`.

> Тему устойчивости запросов к нагрузке (пагинация, rate limiting, идемпотентность повторных вызовов, ретраи при таймаутах БД) см. в разделе роадмапа **«Надёжность и архитектура»**.

## Практическая задача

**Контекст.** Ты пишешь бэкенд админ-панели маркетплейса. Менеджеры ищут заказы через форму с фильтрами. Форма растёт: продукт то и дело просит новый фильтр. Текущий код, портянка из `if` и склейки JPQL строкой; последний инцидент, менеджер ввёл в поиск апостроф, и запрос упал (а security-ревью нашло инъекцию).

**Дано.**
- Сущности `SellerOrder` (поля: `id`, `number`, `sellerId`, `status`, `amount`, `createdAt`) и связанная `OrderItem` (`id`, `order`, `productName`, `quantity`).
- Эндпоинт `GET /api/admin/orders`.
- Стек: Java 21, Spring Boot 3.x, PostgreSQL, Spring Data JPA.

**ТЗ.** Реализуй поиск заказов через **динамические критерии** (Criteria API напрямую или через `Specification`, на твой выбор, но обоснуй). Поддержи фильтры (любая комбинация, любой может отсутствовать):

1. `status`, точное совпадение.
2. `sellerId`, точное совпадение.
3. `numberContains`, регистронезависимая подстрока в `number`.
4. `minAmount` / `maxAmount`, диапазон суммы (границы независимы).
5. `createdFrom` / `createdTo`, диапазон дат.
6. `productName`, заказы, у которых **хотя бы один** `OrderItem` содержит подстроку в `productName` (потребуется join и `distinct`).
7. Сортировка по `createdAt DESC` и пагинация (`page`, `size`).

**Критерии приёмки.**
- В коде **нет** ни одной строковой конкатенации фрагментов запроса; все значения, bind-параметры.
- Пустой фильтр (все параметры null) возвращает все заказы, страницами.
- Поиск по `productName` не дублирует заказы, даже если совпало несколько позиций.
- Апостроф/спецсимволы в текстовом поиске не ломают запрос и не выполняются как код.
- Контроллер тонкий: только приём параметров и делегирование сервису. Бизнес-сборка, в сервисе/спеках.
- Метод чтения помечен `@Transactional(readOnly = true)`, `open-in-view` выключен.
- Есть функциональный тест (Testcontainers + PostgreSQL): проверь минимум три сценария, фильтр по статусу, по диапазону суммы, по `productName` без дублей.

**Подсказки (без готового решения).**
- Начни с `OrderFilter` (record) и списка `Predicate`, добавляемых по условию `!= null`.
- Для `productName`: `Join<SellerOrder, OrderItem> items = root.join("items", JoinType.INNER)` + `query.distinct(true)`.
- `Specification.where(a).and(b)` спокойно принимает `null`-спеки, используй это, чтобы не городить if вокруг комбинирования.
- Для диапазона суммы, где заданы обе границы, есть `cb.between`; где одна, `greaterThanOrEqualTo` / `lessThanOrEqualTo`.
- Проверь сгенерированный SQL (`hibernate.format_sql: true`, `logging.level.org.hibernate.SQL: DEBUG`), убедись, что при пустом фильтре нет мусорного `WHERE 1=1` и что параметры именно биндятся.
- Подумай, что будет с пагинацией при fetch-join коллекции (см. подводный камень №2), и нужен ли тебе fetch именно здесь или достаточно `join`.

## Что почитать

- **Spring Data JPA, Specifications** (официальная дока): https://docs.spring.io/spring-data/jpa/reference/jpa/specifications.html
- **Jakarta Persistence, Criteria API** (спецификация, актуальная для Jakarta EE / Spring Boot 3): https://jakarta.ee/specifications/persistence/3.1/jakarta-persistence-spec-3.1.html#a6925
- **Hibernate 6 ORM User Guide, Criteria** (генерация metamodel, particularities): https://docs.jboss.org/hibernate/orm/6.6/userguide/html_single/Hibernate_User_Guide.html#criteria
- **Baeldung, Spring Data JPA Specifications / Criteria queries**: https://www.baeldung.com/spring-data-criteria-queries

---

[← Hibernate (ORM)](hibernate.md) · [Оглавление](../../../../../README.md) · [Spring Core →](../spring/spring-core.md)
