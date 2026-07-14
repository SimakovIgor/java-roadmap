### [Назад к оглавлению](../../../../../../README.md)

# Работа с JSON

Обзор библиотеки Jackson

>
>[Подключение библиотеки](#подключение-библиотеки)
>
>[ObjectMapper](#object-mapper)
>
>[Автогенерация Java классов](#автогенерация-java-классов-на-основе-json)
>
>[Полезные аннотации](#полезные-аннотации)
>
>[Практическое задание](#практическое-задание)
>
>[Используемая литература](#используемая-литература)
>

# Подключение библиотеки

На прошлом занятии мы рассмотрели подключение JAR библиотеки OkHttp в
проект через настройки в интерфейсе Intellij IDEA. В случае с Jackson
алгоритм абсолютно идентичен. В случае, если преподаватель проговорился
и показал (пусть даже и в первом приближении) как пользоваться сборщиком
Maven, в pom.xml в раздел dependencies нужно вставить следующий блок:

```
<dependency>
   <groupId>com.fasterxml.jackson.core</groupId>
   <artifactId>jackson-databind</artifactId>
   <version>2.17.2</version>
</dependency>
```

(2.17.2 - актуальная библиотека на момент написания методички)

на чем предварительную настройку можно считать оконченной.

# Object Mapper

Рассмотрим класс, отвечающий за сериализацию Java-объектов в JSON и
десериализацию объектов из JSON строки в Java-объекты.

В качестве подопытного примера, используем класс **Car:**

```java
class Car {
    private String color;
    private String type;

    // геттеры и сеттеры обязательно должны быть определены, но не указаны для экономии места
}
```

## Современная Java (16+): Car как record

Для DTO-классов вроде `Car`, которые описывают только данные без изменяемого
состояния, вместо класса с полями, геттерами/сеттерами и написанными вручную
`equals`/`hashCode`/`toString` можно использовать `record` (Java 16+):

```java
record Car(String color, String type) {
}
```

Компилятор сам генерирует канонический конструктор, аксессоры (`color()`,
`type()` — без префикса `get`), корректные `equals`/`hashCode` и читаемый
`toString()` (`Car[color=red, type=BMW]`).

**Важно для Jackson:** начиная с jackson-databind 2.12, `ObjectMapper` умеет
сериализовать и десериализовать record'ы "из коробки" — имена компонентов
Jackson читает через `java.lang.reflect.RecordComponent`, поэтому (в отличие
от обычных классов) флаг компилятора `-parameters` для этого не требуется.
Если нужно переименовать поле в JSON — аннотация ставится прямо на компонент
записи:

```java
record Person(int age, @JsonProperty("firstName") String name) {
}
```

## Экземпляры классов в JSON

Давайте посмотрим на первый пример сериализации объектов Java в JSON,
используя метод **writeValue** класса **ObjectMapper**:

```java
public static void main(String[] args) throws IOException {
    ObjectMapper objectMapper = new ObjectMapper();
    Car car = new Car("red", "BMW");
    objectMapper.writeValue(new File("car.json"), car);
}

// {"color":"red","type":"BMW"}
```

**Современный способ:** тип `ObjectMapper` и `Car` очевиден из правой части
присваивания — начиная с Java 10 для таких локальных переменных идиоматично
использовать `var` вместо явного типа:

```java
public static void main(String[] args) throws IOException {
    var objectMapper = new ObjectMapper();
    var car = new Car("red", "BMW");
    objectMapper.writeValue(new File("car.json"), car);
}
```

Методы **writeValueAsString** и **writeValueAsBytes** вернут
сгенерированный JSON как строку или как массив байтов соответственно.

```java
String jsonString = objectMapper.writeValueAsString(car);
byte[] jsonBytes = objectMapper.writeValueAsBytes(car);
```

## JSON в экземпляры классов Java

Для чтения из различных источников используется метод **readValue.**
Метод имеет огромное количество реализаций, отличающихся по источнику
данных и поддерживает чтение из файлов, строк, URL, массива байтов и уже
знакомых нам классов **Reader** и **InputStream**. Вторым аргументом
указывается класс, экземпляр которого мы хотим десериализовать - в
данном случае Car.class:

```java
public static void main(String[] args) throws IOException {
    ObjectMapper objectMapper = new ObjectMapper();
    Car carFromFile = objectMapper.readValue(new File("car.json"), Car.class);
    System.out.println(carFromFile.toString());
}

// Car{color='red', type='BMW'}
```

**Важно!** Если в классе, экземпляр которого мы хотим получить нет
пустого конструктора, то мы получим **InvalidDefinitionException**, так
как Jackson сначала создает пустой объект, и только затем наполняет его
поля через сеттеры. Это означает что также **важно** создавать сеттеры
и геттеры для сериализации/десериализации объектов.

Метод readValue, помимо прочего, может считывать целые массивы данных и
преобразовывать их в списки, но конструкция вида

```java
List<Car> carList = objectMapper.readValue(carsList, ArrayList<Car>.class);
```

не скомпилируется.

Для того чтобы получить класс обобщенного типа используется абстрактный
класс **TypeReference**:

```java
public static void main(String[] args) throws IOException {
    String carsList = "[{\"color\":\"red\", \"type\":\"BMW\"}," +
            " {\"color\":\"black\", \"type\":\"lada priora\"}]";
    ObjectMapper objectMapper = new ObjectMapper();
    List<Car> carList =
            objectMapper.readValue(carsList, new TypeReference<List<Car>>() {
            });
    System.out.println(carsList);
}


// [{"color":"red", "type":"BMW"}, {"color":"black", "type":"lada priora"}]
```

### Современный способ: text blocks (Java 15+)

Собирать многострочный JSON через конкатенацию экранированных строк
неудобно и легко ошибиться в кавычках. Начиная с Java 15 для этого
используются text blocks (`"""`) — кавычки внутри не экранируются,
компилятор сам разбирается с отступами и переносами строк:

```java
public static void main(String[] args) throws IOException {
    var carsList = """
            [
              {"color":"red", "type":"BMW"},
              {"color":"black", "type":"lada priora"}
            ]
            """;
    var objectMapper = new ObjectMapper();
    List<Car> carList =
            objectMapper.readValue(carsList, new TypeReference<List<Car>>() {
            });
    System.out.println(carList);
}
```

**Задание:** перепишите пример выше (тот, что с ручной конкатенацией строк
`"[{\"color\":\"red\"..."`) на text block и проверьте, что список
десериализуется так же.

## Конфигурирование сериализации-десериализации

### Игнорирование неизвестных полей

Допустим, мы пишем десктопный клиент для получения погоды от сервера.
Само собой, собственной метеостанции у нас нет, а следовательно, данные
будем получать из сторонних источников. Работая со сторонними
источниками всегда следует помнить одну вещь - формат данных может
измениться, совместимость может быть утрачена. Вернемся к нашему примеру
и смоделируем эту ситуацию в упрощенном виде: добавим к представлению
класса **Car** поле "год":

```java
public static void main(String[] args) throws IOException {
    String jsonString = "{ \"color\" : \"white\", \"type\" : \"Volga\", \"year\" :\"1970\" }";
    ObjectMapper objectMapper = new ObjectMapper();
    Car car = objectMapper.readValue(jsonString, Car.class);
    System.out.println(car);
}

// Exception in thread "main" com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException: Unrecognized field "year" (class ru.geekbrains.qa.lesson7.Car), not marked as ignorable (2 known properties: "type", "color"])
```

**ObjectMapper** не смог распознать поле "**year**" и бросил
исключение - это стандартное поведение. Было бы здорово рассказать
библиотечному классу "игнорируй все, что не сможешь понять" - и эта
возможность, к нашему счастью, есть - экземпляр **ObjectMapper** может
быть настроен через метод **configure()**

```java
public static void main(String[] args) throws IOException {
    String jsonString = "{ \"color\" : \"white\", \"type\" : \"Volga\", \"year\" :\"1970\" }";
    ObjectMapper objectMapper = new ObjectMapper();

    objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false); // <- указание поведения при обнаружении неизвестных полей

    Car car = objectMapper.readValue(jsonString, Car.class);
    System.out.println(car);
}

// Car{color='white', type='Volga'}
```

**Современный способ конфигурирования:** вместо создания `ObjectMapper`
через `new` и последующей мутации через `configure()`, актуальные версии
Jackson предлагают неизменяемый builder-стиль — `JsonMapper.builder()`,
который сразу возвращает полностью настроенный (и потокобезопасный)
маппер:

```java
ObjectMapper objectMapper = JsonMapper.builder()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .build();
```

Есть и другой способ - с помощью аннотации **@JsonIgnoreProperties**
определяем стратегию игнорирования неопознанных полей в json на уровне
класса.

```java

@JsonIgnoreProperties(ignoreUnknown = true)
class Car {
    private String color;
    private String type;

    // ...
}
```

### Игнорирование поля класса при сериализации

Но ситуация может быть и противоположной - в нашем классе может быть
поле, полезное с точки зрения бизнес-логики приложения, но абсолютно
бесполезное для отправки на сервер (в нашем случае - булево поле
isActive). Помечая его аннотацией **@JsonIgnore** мы решаем свою
проблему - поле не попадет в сериализованную json-строку.

```java

@JsonIgnoreProperties(ignoreUnknown = true)
class Car {
    private String color;
    private String type;

    @JsonIgnore
    private boolean isActive;

    //  ...
}
```

### Изменение названия поля класса

Представим другую ситуацию - на стороне сервера решили что type - не
очень удачное название для обозначения модели автомобиля и изменили его
на model. Чтобы не менять название этого поля в классе (и всех частях
программы, где мы вызывали это поле) существует аннотация
**@JsonProperty**, в которой можно указать название поля в json:

```java

@JsonProperty(value = "model")
private String type;

// {"color":"green","model":"audi a8"}
```

## Чтение полей json строки без десериализации

Иногда нам может потребоваться прочесть какое-нибудь значение одного
поля из json строки, но заводить для этих целей целый класс было бы
излишне расточительным и трудоемким занятием. Вместо этого, можно
воспользоваться методом **readTree**() класса **ObjectMapper**, который
возвращает экземпляр класса **JsonNode**, позволяющий обходить элементы
json объекта, считывать и записывать значения.

В качестве примера рассмотрим следующий json объект

```json
{
  "name": "Ivan",
  "education": {
    "school": "School #52",
    "university": {
      "degree": "master",
      "name": "SPbGU"
    }
  }
}
```

Название университета вложено в объект "university", который, в свою
очередь, вложен в объект "education", который находится в корневом
объекте. Структура легко описывается в методе **at**:

```
ObjectMapper objectMapper = new ObjectMapper();
JsonNode universityName = objectMapper
    .readTree(jsonString)
    .at("/education/university/name");

System.out.println(universityName.asText());

>> SPbGU
```

# Автогенерация Java классов на основе Json

В некоторых ситуациях нам приходится конструировать класс на основе
примера ответа в формате JSON. Так как программисты - люди ленивые, в
интернете существует довольно большое количество сервисов, аналогичных
этому - [https://codebeautify.org/json-to-java-converter](https://codebeautify.org/json-to-java-converter)

Подобные конвертеры генерируют код разной степени замусоренности, но для
быстрого прототипирования такой подход имеет право на существование.

# Полезные аннотации

## Аннотация @JsonRootName

Применяется вместе с включенной опцией оборачивания в корневое значение

objectMapper.enable(SerializationFeature.*WRAP_ROOT_VALUE*);

По умолчанию, корневое значение будет совпадать с названием класса, но
используя аннотацию можно изменить это поведение:

```java

@JsonRootName(value = "car")
class Car {
    // ...
}

// {"car":{"color":"yellow","type":"Renault Logan"}}


```

## Аннотация @JsonCreator

Позволяет помечать конструктор класса для использования его при создании
экземпляра при десериализации. Применяется совместно с указанием
аннотации **@JsonProperty**, которой помечаются аргументы конструктора
с указанием соответствия названиям полей в json:

```java
class Person {
    private int age;
    private String name;

    @JsonCreator
    public Person(@JsonProperty("age") int age,
                  @JsonProperty("firstName") String name) {
        this.age = age;
        this.name = name;
    }
}
```

```java
String jsonPerson = "{ \"age\" : 30, \"firstName\" : \"Vsevolod\" }";
ObjectMapper objectMapper = new ObjectMapper();
Person p = objectMapper.readValue(jsonPerson, Person.class);
System.out.

println(p);
// Person{age=30, name='Vsevolod'}
```

## Аннотация @JsonGetter, @JsonSetter

Применяются в случае, если название геттера или сеттера поля не
соответствует конвенции Java, например:

```java
class Student {
    private String name;
    private double averageMark;

    @JsonGetter("name")
    public String getStudentName() {
        return name;
    }

    @JsonSetter("name")
    public void setStudentName(String name) {
        this.name = name;
    }

    @JsonGetter("averageMark")
    public double getAvgMark() {
        return averageMark;
    }

    @JsonSetter("averageMark")
    public void setAvgMark(double averageMark) {
        this.averageMark = averageMark;
    }
}
```

Тем не менее, вывод отрабатывает без ошибок:

```
ObjectMapper objectMapper = new ObjectMapper();
Student student = new Student("Ivan", 4.87);
String jsonStudent = objectMapper.writeValueAsString(student);
System.out.println(jsonStudent);

// {"name":"Ivan","averageMark":4.87}
```

## Современная Java: даты в JSON (`java.time` + `jackson-datatype-jsr310`)

В практическом задании ниже нужно вывести дату (`DATE`) для прогноза на
5 дней. Для работы с датами в современном Java-коде используется не
`Date`/`Calendar`, а пакет `java.time` (`LocalDate`, `LocalDateTime`,
`OffsetDateTime` и т.д.) — он неизменяем, потокобезопасен и удобнее в
использовании.

"Из коробки" `ObjectMapper` не умеет (де)сериализовать типы `java.time` —
нужно подключить модуль:

```xml
<dependency>
    <groupId>com.fasterxml.jackson.datatype</groupId>
    <artifactId>jackson-datatype-jsr310</artifactId>
    <version>2.17.2</version>
</dependency>
```

и зарегистрировать его в мэппере:

```java
record WeatherDay(LocalDate date, String weatherText, double temperature) {
}

ObjectMapper objectMapper = JsonMapper.builder()
        .addModule(new JavaTimeModule())
        .build();

var day = new WeatherDay(LocalDate.of(2026, 7, 16), "ясно", 24.5);
String json = objectMapper.writeValueAsString(day);
System.out.println(json);

// {"date":"2026-07-16","weatherText":"ясно","temperature":24.5}
```

Без модуля `JavaTimeModule` попытка сериализовать/десериализовать
`LocalDate` бросит `InvalidDefinitionException` — модуль как раз и
регистрирует нужные сериализаторы/десериализаторы для типов `java.time`.

# Практическое задание

Задание необходимо сдать через Git. [Инструкция](https://docs.google.com/document/d/1tD_AjQss1Qe_qEQ199Mu3sK_jrDt_ADLhxiG9BC7xeM/edit?usp=sharing)

1. Реализовать корректный вывод информации о текущей погоде. Создать класс WeatherResponse и десериализовать ответ сервера. Выводить пользователю только
   текстовое описание погоды и температуру в градусах цельсия.
2. Реализовать корректный выход из программы
3. Реализовать вывод погоды на следующие 5 дней в формате
   > В городе CITY на дату DATE ожидается WEATHER_TEXT, температура - TEMPERATURE

где CITY, DATE, WEATHER_TEXT и TEMPERATURE - уникальные значения для каждого дня.

### Дополнительное задание (опционально)

Перепишите `WeatherResponse` (и вложенные классы) в виде `record`-ов,
даты храните как `LocalDate`/`LocalDateTime` (с подключённым
`JavaTimeModule`), а многострочные тестовые JSON-строки для проверки
десериализации оформите через text block вместо конкатенации строк.

# Используемая литература

Для подготовки данного методического пособия были использованы следующие
ресурсы:

1. [https://www.baeldung.com/jackson](https://www.baeldung.com/jackson)

2. [http://tutorials.jenkov.com/java-json/jackson-jsonnode.html#get-json-node-at-path](http://tutorials.jenkov.com/java-json/jackson-jsonnode.html#get-json-node-at-path)

3. [https://github.com/FasterXML/jackson](https://github.com/FasterXML/jackson)

### [Назад к оглавлению](../../../../../../README.md)
