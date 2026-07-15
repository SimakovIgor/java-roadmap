### [Назад к оглавлению](../../../../../README.md)

# Инъекции зависимостей

![Раздел](https://img.shields.io/badge/раздел-Other%20·%20Mockito-3b82f6)
![Уровень](https://img.shields.io/badge/уровень-middle-16a34a)
![Тема](https://img.shields.io/badge/тема-моки%20·%20заглушки%20·%20DI%20в%20тестах-8b5cf6)

### Что такое зависимость

Зависимость это когда одна сущность не может работать без другой. Например, разработчик не может написать программу без компьютера.

**Зависимость в программировании** (dependency) означает, что один программный компонент не работает без другого. Например, класс «программист», без класса «компьютер».

**Пример.** Представь холодильник. Когда его дверца открывается, внутри включается свет. Для этого в холодильнике есть лампочка. Если она не работает, свет в холодильнике не загорится. Получается, свет связан с лампочкой это зависимость.

Этот пример легко переложить на язык Java. Пусть холодильнику в коде соответствует класс `Fridge`, а лампочке, `HorizontLamp`. Класс лампочки называется так, потому что её произвели на заводе «Горизонт».

В классе холодильника есть метод `openDoor()`. Он открывает дверцу. А класс лампочки содержит метод `switchLightOn()`, который включает свет.

Чтобы включить свет при открытии дверцы, нужно вызвать метод `switchLightOn()` для объекта `HorizontLamp` внутри класса `Fridge`:

```java
public class HorizontLamp {
    // метод, который включает лампочку
    public void switchLightOn() {
        System.out.println("Лампочка загорелась");
    }
}

public class Fridge {
    // метод открывает двери
    public void openDoor() {
        // объект класса HorizontLamp создаётся прямо внутри Fridge
        HorizontLamp horizontLamp = new HorizontLamp();
        // включается свет
        horizontLamp.switchLightOn();
    }
}
```

Класс `Fridge` зависит от `HorizontLamp`, потому что не сможет без него работать. Поэтому класс `Fridge` называют **зависимым**, а класс `HorizontLamp`, **зависимостью**.

У такого кода есть недостаток: зависимость «зашита» прямо внутрь. Если завод «Горизонт» нужно заменить на «СуперЛампочку» или у конструктора `HorizontLamp` появится новый параметр, придётся переписывать сам класс `Fridge`. К тому же такой класс **сложно тестировать**: нельзя подменить настоящую лампочку заглушкой, потому что `Fridge` создаёт её сам.

### Что такое инъекция зависимостей

**Инъекция зависимостей** (англ. Dependency Injection, DI) это принцип построения кода.

В основе принципа лежит такая идея: когда ты создаёшь зависимость внутри зависимого объекта, появляются сложности → значит, нужно вынести создание зависимости наружу → тогда зависимость можно будет внедрить в объект в готовом виде.

В примере с холодильником класс `Fridge` должен получать готовый объект лампочки и просто использовать его. Это и есть инъекция зависимостей. Заодно это делает код тестируемым: в тесте вместо настоящей лампочки можно передать мок.

### Как написать инъекцию зависимости

Чтобы ослабить связь между зависимым классом и зависимостью, создай интерфейс. Тогда зависимый класс будет работать с абстракцией, а не с конкретной реализацией.

1. **Создать интерфейс для зависимости.** Реализаций сразу две, `HorizontLamp` и `SuperLamp`. Пусть интерфейс называется `Lamp`.
2. **Создать в зависимом классе поле типа интерфейса.** По принципу инкапсуляции, приватное и `final`: `private final Lamp lamp`.
3. **Передавать зависимость через конструктор.** `public Fridge(Lamp lamp)`, внутри `this.lamp = lamp`.

```java
// общий интерфейс, который реализуют и HorizontLamp, и SuperLamp
public interface Lamp {
    void switchLightOn();
}

public class HorizontLamp implements Lamp {
    @Override
    public void switchLightOn() {
        System.out.println("Включаю лампу «Горизонт»");
    }
}

public class SuperLamp implements Lamp {
    @Override
    public void switchLightOn() {
        System.out.println("Включаю «СуперЛампу», светит ярко!");
    }
}

public class Fridge {
    // зависимость, поле класса, final, задаётся один раз в конструкторе
    private final Lamp lamp;

    // объект lamp создаётся снаружи и приходит готовым
    public Fridge(Lamp lamp) {
        this.lamp = lamp;
    }

    public void openDoor() {
        lamp.switchLightOn();
        // дальше может быть код, который выполняется после открытия дверцы
    }
}
```

Теперь зависимости создаются в одном месте (например, в `main` или, в реальном проекте, в Spring-контейнере), а `Fridge` получает их готовыми:

```java
public class Example {
    public static void main(String[] args) {
        // чтобы заменить лампочку, достаточно передать new SuperLamp() —
        // код класса Fridge менять не нужно
        Lamp lamp = new HorizontLamp();
        Fridge fridge = new Fridge(lamp);
        fridge.openDoor();
    }
}
```

### Главное

- Инъекция зависимости означает, что класс получает объект, созданный за его пределами.
- Проще всего внедрять зависимость через конструктор: поле объявляем в одном классе, а значение передаём снаружи.
- DI не только ослабляет связанность, но и делает код тестируемым, в тесте зависимость легко подменить заглушкой.

---

# Пирамида тестирования

Прежде чем говорить о моках, полезно держать в голове **пирамиду тестирования**:

- **Юнит-тесты** (основание пирамиды), много, быстрые, проверяют один класс/метод в изоляции. Внешние зависимости подменяются моками.
- **Интеграционные тесты** (середина/верх), меньше, медленнее, проверяют связку компонентов с реальными инфраструктурными вещами: базой данных, брокером сообщений. Именно здесь пригодится **Testcontainers**.
- **E2E / UI-тесты** (вершина), совсем мало, самые медленные.

Мокирование, инструмент для юнит-тестов. Testcontainers, инструмент для интеграционных. Дальше разберём и то, и другое.

---

# Моки и стабы

## Что такое мок

Мок (от англ. mock, «передразнивать») это «дублёр» реальной зависимости в тесте. Он помогает протестировать один класс, не запуская реальный код его зависимостей.

В мок можно заложить только те действия, которые нужны для теста. Мок, как дублёр в кино: подменяет актёра, но играть так же хорошо ему необязательно.

**Пример.** Есть объекты `Sender` и `Mailbox`. `Sender` отправляет письма, а `Mailbox` умеет сортировать письма и искать спам. Чтобы отправить письмо, `Mailbox` вызывает метод `Sender`. Чтобы протестировать `Mailbox`, не нужно слать настоящие письма, достаточно убедиться, что метод `Sender` вызвался. Для этого `Sender` заменяют моком.

## Когда мок оправдан, а когда лучше реальный объект

Мок оправдан, когда зависимость:

- **обращается во внешний мир**, сеть, база, файловая система, другой сервис. В юнит-тесте это медленно и ненадёжно (сеть может отвалиться, база, быть недоступной).
- **тяжело или дорого создать/настроить**, например, объект требует десятков заполненных полей, а тесту нужен всего один метод.
- **ещё не готова**, часть системы пишет другая команда, а тестировать нужно уже сейчас.
- **нужна, чтобы проверить сам факт взаимодействия**, что метод был вызван (нужное число раз, с нужными аргументами).

Реальный объект лучше мока, когда:

- это **простая структура данных или value-object** без побочных эффектов (`record`, DTO, enum), мокать его бессмысленно и только зашумляет тест;
- **чистая логика без внешних зависимостей** (калькулятор, валидатор, маппер), тестируем на реальном объекте с настоящими данными;
- вы **переусердствовали с моками**: если тест целиком состоит из `when(...).thenReturn(...)`, он проверяет не поведение, а свою же настройку. Такой тест ломается при любом рефакторинге и не ловит реальные баги.

Правило простое: **мокаем границы (I/O, внешние сервисы), а доменную логику тестируем на реальных объектах.**

## Мок vs стаб

Часто говорят про два вида «дублёров»:

- **Стаб** (stub, «заглушка») отвечает за **состояние**: на вызов метода возвращает заранее заданные данные (`when(...).thenReturn(...)`).
- **Мок** отвечает за **поведение**: мы проверяем, что метод действительно был вызван (`verify(...)`).

В Mockito и то и другое делается одним и тем же мок-объектом, поэтому на практике термины часто смешивают.

## Как подключить Mockito

Mockito подключается как тестовая зависимость. Для интеграции с JUnit 5 нужен ещё артефакт `mockito-junit-jupiter`. Актуальные версии на момент написания (проверяй в Maven Central на свежесть):

```xml
<dependency>
    <groupId>org.mockito</groupId>
    <artifactId>mockito-core</artifactId>
    <version>5.14.2</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.mockito</groupId>
    <artifactId>mockito-junit-jupiter</artifactId>
    <version>5.14.2</version>
    <scope>test</scope>
</dependency>
```

> Отдельно подключать Byte Buddy не нужно, Mockito тянет его транзитивно.

## Как включить Mockito в тесте (JUnit 5)

Старый `@RunWith(MockitoJUnitRunner.class)` это JUnit 4, в JUnit 5 он не работает. Есть два актуальных способа.

**Способ 1, расширение `MockitoExtension` (рекомендуется).** Оно само инициализирует поля `@Mock`/`@InjectMocks` и проверяет корректность использования моков:

```java
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CarTest {
    // ...
}
```

**Способ 2, `MockitoAnnotations.openMocks(this)` в `@BeforeEach`.** Пригодится, когда управлять расширениями вручную неудобно. Обрати внимание: метод называется `openMocks`, а старый `initMocks`, **deprecated**, использовать его не нужно.

```java
import org.junit.jupiter.api.BeforeEach;
import org.mockito.MockitoAnnotations;

class CarTest {

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }
    // ...
}
```

## Как создать мок

Мок создают либо методом `Mockito.mock()`, либо аннотацией `@Mock`:

```java
Car car = Mockito.mock(Car.class);
```

```java
@Mock
Car car;
```

Второй вариант лаконичнее, поэтому его используют чаще (вместе с `MockitoExtension`).

Когда ты создаёшь мок, все его методы по умолчанию возвращают «пустые» значения: `null` для ссылочных типов, `0`/`false` для примитивов, пустую коллекцию для коллекций.

Мок можно создать почти для любого класса или интерфейса. Начиная с Mockito 5 по умолчанию используется `inline`-движок, поэтому мокаются даже `final`-классы и `final`-методы. Исключение, некоторые платформенные типы вроде `String`: их мокать нельзя и не нужно (это просто данные).

## `@InjectMocks`, внедрить моки в тестируемый объект

`@InjectMocks` создаёт реальный экземпляр тестируемого класса и **автоматически подставляет** в него все объявленные рядом `@Mock`. Это избавляет от ручного вызова конструктора.

Пусть `Car` зависит от `Engine`:

```java
public class Engine {
    public int getPower() {
        return 125;
    }
}

public class Car {
    private final Engine engine;

    public Car(Engine engine) {
        this.engine = engine;
    }

    public int getEnginePower() {
        return engine.getPower();
    }
}
```

Тест: мок `Engine` внедряется в реальный `Car`.

```java
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CarTest {

    @Mock
    Engine engine;          // зависимость-мок

    @InjectMocks
    Car car;                // реальный Car, в который внедрили мок engine

    @Test
    void shouldReturnStubbedPower() {
        when(engine.getPower()).thenReturn(500);

        assertThat(car.getEnginePower()).isEqualTo(500);
    }
}
```

## Стабинг: `when().thenReturn()`

`when(...).thenReturn(...)` задаёт, что мок вернёт на конкретный вызов:

```java
when(engine.getPower()).thenReturn(500);
// теперь engine.getPower() всегда возвращает 500
```

Для методов с параметрами можно указать конкретные аргументы или матчеры семейства `any`:

```java
public class Wheel {
    public int countWheels(int frontWheels, int backWheels) {
        return frontWheels + backWheels;
    }
}
```

```java
// сработает только при аргументах (2, 2)
when(wheel.countWheels(2, 2)).thenReturn(5);

// сработает при любых целочисленных аргументах
when(wheel.countWheels(anyInt(), anyInt())).thenReturn(5);
```

> Важно: матчеры (`anyInt()`, `any()`, `eq(...)`) нельзя смешивать с «живыми» значениями. Если хотя бы один аргумент задан матчером, остальные тоже должны быть матчерами: `countWheels(eq(2), anyInt())`.

## Стабинг исключений: `thenThrow()`

Чтобы проверить, как код ведёт себя при сбое зависимости, мок можно заставить бросить исключение:

```java
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@Test
void shouldPropagateEngineFailure() {
    when(engine.getPower()).thenThrow(new IllegalStateException("двигатель заглох"));

    assertThatThrownBy(() -> car.getEnginePower())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("двигатель заглох");
}
```

Это удобный способ сымитировать недоступность внешнего ресурса (базы, стороннего сервиса), не поднимая его на самом деле.

## Проверка вызовов: `verify()`, `times()`, `never()`

Мок помогает убедиться, что метод был вызван, с нужными аргументами и нужное число раз.

```java
public class Car {
    private String carBrand;

    public void setCarBrand(String carBrand) {
        this.carBrand = carBrand;
    }
}
```

```java
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CarVerifyTest {

    @Mock
    Car car;

    @Test
    void shouldVerifyInteractions() {
        car.setCarBrand("Lamborghini");

        // метод вызван ровно с этим аргументом
        verify(car).setCarBrand("Lamborghini");

        // ... или с любой строкой
        verify(car).setCarBrand(anyString());
    }

    @Test
    void shouldVerifyCallCount() {
        car.setCarBrand("Lamborghini");
        car.setCarBrand("Lamborghini");
        car.setCarBrand("Lamborghini");

        // метод вызван ровно 3 раза
        verify(car, times(3)).setCarBrand("Lamborghini");

        // а с этим аргументом, ни разу
        verify(car, never()).setCarBrand("Lada");
    }
}
```

Если в `verify()` указать аргумент, с которым метод на самом деле не вызывался, тест упадёт.

## `ArgumentCaptor`, «поймать» аргумент вызова

Иногда мало проверить факт вызова, нужно заглянуть внутрь объекта, который передали в мок. Для этого есть `ArgumentCaptor`: он «захватывает» фактический аргумент, и его можно проверить через AssertJ.

```java
public record Email(String to, String subject) {}

public interface Sender {
    void send(Email email);
}

public class Mailbox {
    private final Sender sender;

    public Mailbox(Sender sender) {
        this.sender = sender;
    }

    public void notifyUser(String userEmail) {
        sender.send(new Email(userEmail, "Добро пожаловать"));
    }
}
```

```java
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MailboxTest {

    @Mock
    Sender sender;

    @InjectMocks
    Mailbox mailbox;

    @Captor
    ArgumentCaptor<Email> emailCaptor;

    @Test
    void shouldSendWelcomeEmailToUser() {
        mailbox.notifyUser("user@example.com");

        verify(sender).send(emailCaptor.capture());

        assertThat(emailCaptor.getValue())
            .extracting(Email::to, Email::subject)
            .containsExactly("user@example.com", "Добро пожаловать");
    }
}
```

## `@Spy`, частичный мок реального объекта

`@Mock` создаёт «пустышку», у которой не работает ни один реальный метод. Иногда же нужен **реальный объект**, у которого подменён только один-два метода. Для этого есть `@Spy` (шпион).

У spy по умолчанию вызываются настоящие методы, а застабленные, возвращают заданное значение.

```java
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

@ExtendWith(MockitoExtension.class)
class SpyTest {

    @Spy
    Engine engine; // реальный Engine, а не пустышка

    @Test
    void shouldUseRealMethodByDefault() {
        // реальный метод вернёт 125
        assertThat(engine.getPower()).isEqualTo(125);
    }

    @Test
    void shouldOverrideOnlyStubbedMethod() {
        // для spy безопаснее doReturn(...).when(...), чтобы не вызвать реальный метод при настройке
        doReturn(500).when(engine).getPower();

        assertThat(engine.getPower()).isEqualTo(500);
    }
}
```

Spy используют экономно: если объект приходится «шпионить», часто это сигнал, что класс стоит разбить на части.

## Пример: разрыв зависимости от внешнего ресурса

Соберём всё вместе на типичном сценарии, класс зависит от внешнего клиента, который ходит в сеть. В юнит-тесте сеть трогать нельзя, поэтому клиента мокаем.

```java
// клиент внешнего сервиса, в проде реально ходит по сети
public interface RemoteApiClient {
    int fetchStatusCode(String url);
}

public class HealthService {
    private final RemoteApiClient client;

    public HealthService(RemoteApiClient client) {
        this.client = client;
    }

    public String checkServer(String url) {
        int code = client.fetchStatusCode(url);
        return code == 200 ? "Сервер доступен" : "Сервер недоступен";
    }
}
```

```java
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HealthServiceTest {

    @Mock
    RemoteApiClient client;

    @InjectMocks
    HealthService healthService;

    @Test
    void shouldReturnAvailable_whenServerAnswers200() {
        when(client.fetchStatusCode(anyString())).thenReturn(200);

        assertThat(healthService.checkServer("http://example.com"))
            .isEqualTo("Сервер доступен");
    }

    @Test
    void shouldReturnUnavailable_whenServerAnswers404() {
        when(client.fetchStatusCode(anyString())).thenReturn(404);

        assertThat(healthService.checkServer("http://example.com"))
            .isEqualTo("Сервер недоступен");
    }
}
```

Мы проверили обе ветки логики, ни разу не выходя в сеть. Это и есть **изолированный** юнит-тест: он даёт одинаковый результат при любом порядке запуска и не зависит от окружения.

---

# Интеграционные тесты и Testcontainers

## Зачем нужен Testcontainers

Моки хороши для юнит-тестов, но у них есть предел: замокав DAO/репозиторий, ты проверяешь свой код, но **не проверяешь настоящий SQL, схему БД, транзакции и маппинг**, всё то, что чаще всего и ломается. Мок репозитория с радостью «вернёт» что угодно, а на реальной базе запрос может упасть.

Тут два неудачных подхода, которых стоит избегать:

- **мокать базу**, тест ничего не говорит о реальном поведении;
- поднимать **встроенную H2** «вместо PostgreSQL», диалекты и типы отличаются, тест зелёный, а в проде падает.

Правильный путь для интеграционного теста, гонять его **против той же СУБД, что в проде**. [Testcontainers](https://testcontainers.com/) поднимает настоящий PostgreSQL (или Kafka, Redis и т.д.) в Docker-контейнере прямо на время теста, а после, гасит его. Тест видит реальную базу, а окружение остаётся чистым.

Это уже **интеграционный тест**, верхняя часть пирамиды: их пишут меньше, чем юнитов, они медленнее (нужен Docker и старт контейнера), зато ловят реальные интеграционные баги.

## Подключение зависимостей

Testcontainers использует BOM для согласования версий модулей. Нужны модуль интеграции с JUnit 5 (`junit-jupiter`), модуль `postgresql` и JDBC-драйвер PostgreSQL:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-bom</artifactId>
            <version>1.20.4</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <!-- интеграция Testcontainers с JUnit 5 -->
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>junit-jupiter</artifactId>
        <scope>test</scope>
    </dependency>
    <!-- модуль для PostgreSQL-контейнера -->
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>postgresql</artifactId>
        <scope>test</scope>
    </dependency>
    <!-- JDBC-драйвер PostgreSQL -->
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <version>42.7.4</version>
        <scope>test</scope>
    </dependency>
</dependencies>
```

> Для запуска нужен установленный и работающий Docker.

## Тест репозитория на реальном PostgreSQL

Возьмём простой репозиторий, который сохраняет и читает пользователя. Здесь он написан на «голом» JDBC, чтобы показать суть без Spring; в реальном проекте это был бы Spring Data JPA-репозиторий.

```java
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import javax.sql.DataSource;

public record User(long id, String name) {}

public class UserRepository {
    private final DataSource dataSource;

    public UserRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void save(User user) {
        String sql = "INSERT INTO users (id, name) VALUES (?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, user.id());
            ps.setString(2, user.name());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось сохранить пользователя", e);
        }
    }

    public Optional<User> findById(long id) {
        String sql = "SELECT id, name FROM users WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new User(rs.getLong("id"), rs.getString("name")));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось прочитать пользователя", e);
        }
    }
}
```

Интеграционный тест поднимает настоящий PostgreSQL в контейнере и работает с ним:

```java
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers // включает управление жизненным циклом контейнеров
class UserRepositoryIT {

    // один контейнер на весь класс (static), быстрее, чем поднимать на каждый тест
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");

    UserRepository repository;

    static DataSource dataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl());   // адрес и порт контейнера подставляются автоматически
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return ds;
    }

    @BeforeAll
    static void createSchema() throws SQLException {
        try (Connection conn = dataSource().getConnection();
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, name VARCHAR(255))");
        }
    }

    @BeforeEach
    void setUp() throws SQLException {
        // чистим таблицу перед каждым тестом, изоляция
        try (Connection conn = dataSource().getConnection();
             Statement st = conn.createStatement()) {
            st.execute("TRUNCATE TABLE users");
        }
        repository = new UserRepository(dataSource());
    }

    @Test
    void shouldSaveAndFindUser() {
        repository.save(new User(1L, "Alice"));

        assertThat(repository.findById(1L))
            .isPresent()
            .get()
            .extracting(User::id, User::name)
            .containsExactly(1L, "Alice");
    }

    @Test
    void shouldReturnEmpty_whenUserNotFound() {
        assertThat(repository.findById(42L)).isEmpty();
    }
}
```

Что здесь происходит:

- `@Testcontainers` + `@Container`, Testcontainers сам запускает контейнер перед тестами и останавливает после.
- `static` у контейнера означает «один контейнер на весь класс». Так тесты идут быстрее, а изоляцию обеспечиваем через `TRUNCATE` в `@BeforeEach`.
- `getJdbcUrl()` / `getUsername()` / `getPassword()` возвращают реальные координаты запущенного контейнера, порт выбирается случайно, руками его прописывать не нужно.
- Суффикс `IT` (Integration Test), общепринятая конвенция для интеграционных тестов; часто их отделяют от юнитов и запускают отдельной фазой сборки.

По той же схеме поднимаются `KafkaContainer`, `GenericContainer` для Redis и другие модули, Testcontainers покрывает почти любую инфраструктуру.

---

# Оценка покрытия

Покрытие кода (code coverage) показывает, какой процент программы выполняется во время тестов. Смотрят на процент покрытых строк, ветвей (условий) и методов.

**Пример.** Сервис считает зарплату менеджера: 5% со всех продаж за месяц, но не больше 50 000:

```java
public class SalaryService {

    public int calculateSalary(int sales) {
        int percent = 5;
        int salary = sales * percent / 100;
        int salaryLimit = 50_000;
        if (salary > salaryLimit) {
            salary = salaryLimit;
        }
        return salary;
    }
}
```

Юнит-тест на JUnit 5 + AssertJ:

```java
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SalaryServiceTest {

    private final SalaryService salaryService = new SalaryService();

    @Test
    void shouldCalculateSalary_whenUnderLimit() {
        assertThat(salaryService.calculateSalary(50_000)).isEqualTo(2_500);
    }
}
```

> Обрати внимание: `SalaryService`, чистая логика без внешних зависимостей, поэтому тестируем его **на реальном объекте, без всяких моков**. Мокать тут было бы бессмысленно.

### Виды покрытия

- **Покрытие строк.** Строка покрыта, если хотя бы раз выполнилась. `int salary = sales * percent / 100;` выполняется всегда, покрыта. А `salary = salaryLimit;` внутри `if`, только когда зарплата превышает лимит.
- **Покрытие ветвей (условий).** Условие покрыто, если сработали **обе** ветки. `if (salary > salaryLimit)` при единственном тесте покрыт лишь наполовину, проверен только случай «ниже лимита».
- **Покрытие методов.** Метод покрыт, если вызван хотя бы раз. `calculateSalary` вызывается, покрыт.

### Как считать покрытие: JaCoCo

Вручную считать покрытие неудобно, помогает плагин **JaCoCo**. Он подключается в секцию `build/plugins` в `pom.xml`. Нужны две цели: `prepare-agent` (навешивает агент на JVM во время тестов) и `report` (генерирует HTML-отчёт).

```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.jacoco</groupId>
            <artifactId>jacoco-maven-plugin</artifactId>
            <version>0.8.12</version>
            <executions>
                <execution>
                    <id>prepare-agent</id>
                    <goals>
                        <goal>prepare-agent</goal>
                    </goals>
                </execution>
                <execution>
                    <id>report</id>
                    <phase>verify</phase>
                    <goals>
                        <goal>report</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

Запусти `mvn verify` и открой отчёт `target/site/jacoco/index.html` в браузере. В нём столбцы **Missed Instructions** (строки) и **Missed Branches** (ветви) показывают, что покрыто. Цвета в исходнике: зелёный, покрыто, жёлтый, покрыто частично, красный, не покрыто.

Чтобы закрыть непокрытую ветку `if`, добавим тест на превышение лимита:

```java
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SalaryServiceTest {

    private final SalaryService salaryService = new SalaryService();

    @Test
    void shouldCalculateSalary_whenUnderLimit() {
        assertThat(salaryService.calculateSalary(50_000)).isEqualTo(2_500);
    }

    @Test
    void shouldCapSalary_whenOverLimit() {
        assertThat(salaryService.calculateSalary(1_000_000)).isEqualTo(50_000);
    }
}
```

Теперь покрыты обе ветки, отчёт покажет 100%.

### Сколько покрытия нужно и почему цифры мало

На практике 100% почти недостижимо и не самоцель. Обычно фиксируют минимальный порог (например, 80%) и не дают ему падать.

Важно: **100% покрытия не значит, что протестированы все сценарии.** Покрытие говорит лишь, что строка выполнилась, но не проверяет, правильные ли данные ты подобрал. Если случайно испортить условие:

```java
int test = 40_000;
if (salary > test) {   // было: if (salary > salaryLimit)
    salary = salaryLimit;
}
```

тесты могут остаться зелёными, а покрытие, 100%, хотя логика уже сломана. Поэтому кроме количественного покрытия нужно и качественное: осмысленные тестовые данные и техники тест-дизайна (граничные значения, классы эквивалентности).

---

# Домашнее задание

**Задача 1. Юнит-тест с моком (Mockito).**

Есть сервис уведомлений, зависящий от внешнего клиента:

```java
public interface SmsClient {
    boolean send(String phone, String text);
}

public class NotificationService {
    private final SmsClient smsClient;

    public NotificationService(SmsClient smsClient) {
        this.smsClient = smsClient;
    }

    public boolean notifyUser(String phone, String text) {
        if (phone == null || phone.isBlank()) {
            throw new IllegalArgumentException("phone обязателен");
        }
        return smsClient.send(phone, text);
    }
}
```

Напиши юнит-тесты на JUnit 5 + Mockito + AssertJ:

1. Подключи Mockito через `@ExtendWith(MockitoExtension.class)`, `SmsClient` создай как `@Mock`, `NotificationService`, как `@InjectMocks`.
2. Застабь `smsClient.send(...)` через `when().thenReturn(true)` и проверь, что `notifyUser` вернул `true`.
3. Через `verify(...)` убедись, что `send` был вызван ровно один раз с нужными аргументами; при желании используй `ArgumentCaptor`.
4. Проверь, что при пустом `phone` бросается `IllegalArgumentException`, а `send` **не вызывается** (`verify(..., never())`).
5. Через `thenThrow(...)` сымитируй сбой клиента и проверь, что исключение пробрасывается наружу.

**Задача 2. Интеграционный тест на Testcontainers (реальный PostgreSQL).**

Возьми `UserRepository` из урока (или напиши свой на Spring Data JPA). Напиши интеграционный тест:

1. Подними реальный PostgreSQL через `@Testcontainers` + `@Container PostgreSQLContainer`.
2. Создай схему, сохрани нескольких пользователей и проверь через AssertJ, что `findById` возвращает сохранённого, а для несуществующего id, `Optional.empty()`.
3. Обеспечь изоляцию тестов (очистка таблицы в `@BeforeEach`).
4. Ответь в комментарии к тесту: почему для этой проверки Testcontainers лучше, чем мок репозитория или встроенная H2?

---

[← JUnit 5](junit.md) · [Оглавление](../../../../../README.md) · [Maven →](../maven/Maven.md)
