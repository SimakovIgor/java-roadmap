### [Назад к оглавлению](../../../../README.md)

# JUnit

Юнит-тесты по-другому называют модульными тестами (англ. unit — «часть», «модуль»).

**Модуль** — это целостная часть системы, которая выполняет отдельную задачу и может быть протестирована изолированно от других. Например, модули в
приложении-калькуляторе — это сложение, вычитание и другие математические операции.

Модули в коде — отдельные классы и методы в них. Если один модуль сломается, это отразится на всей системе.

### Когда проводят юнит-тесты

Юнит-тесты проводят, когда функциональность ещё не разработана до конца. Это помогает найти ошибки как можно раньше: так легче и дешевле исправить недочёт.

Часто юнит-тесты пишут сами разработчики — чтобы проверять кусочки своего кода как только он написан. Но автоматизатору тоже нужно уметь с ними работать.

### Как называть юнит-тесты

Названия тестов стоит писать по такой схеме:

- Имя метода, который тестируешь — например, `pizzaDelivery`.
- Входные параметры тестирования. Например, количество пицц — 0: `ZeroAmount`
- Ожидаемый результат. Например, если пицц 0, тест выдаст ошибку — `ShowsError`.

В итоге:

![junit-1.png](img/junit-1.png)

Если нужно проверить метод, когда пицц больше 1, будет так — `pizzaDeliveryGreaterThanZeroShowsOk`. Если меньше нуля —
`pizzaDeliveryLessThanZeroShowsError`.

**Пиши ожидаемый результат конкретно.** Например, `ShowsError`. Или `BalanceIncreased`, если должен пополниться баланс. Не стоит писать `WorksCorrect`
или `EverythingIsOk` — непонятно, что именно значит «правильно работает».

### Аннотации

**Аннотация `@Test`**. Нужно ставить перед каждым тестом, иначе Junit его не запустит.

**Аннотация `@Before`.** Действия в методе с такой аннотацией будут происходит перед каждым тестом.

Есть условие: метод c аннотацией `@Before` должен быть `public void`. Только так JUnit его увидит.

**Аннотация `@After`.** Действия в методе происходят после теста.

Например, нужно удалить всё, что создали во время работы теста — записи в БД, файлы и так далее.

```java
public class Example {
    File file;

    //Создаем тестовый файл
    @Before
    public void createOutputFile() {
        file = new File(...);
    }

    //Выполняем тесты, используя файл
    @Test
    public void doSomethingWithFile() {
        ...
    }

    @Test
    public void doSomethingElseWithFile() {
        ...
    }

    //Удаляем тестовый файл
    @After
    public void deleteOutputFile() {
        file.delete();
    }
}
```

## Assert

Любой тест прежде всего проверяет, как работает система. В JUnit есть специальный класс **Assert** — с английского «утверждать». Методы этого класса проверяют,
верны ли утверждения и условия.

### `assertEquals`: сравнить целые числа

Для целых чисел метод будет выглядеть так — `assertEquals(int expected, int actual)`. Ожидаемое значение — `expected` — сравнивают с фактическим — `actual`.

**Пример.** В интернет-магазине можно добавлять один и тот же продукт в корзину, нажимая `+`. Нужно проверить, что в корзине окажется правильное количество
продуктов. Если на `+` кликнули четыре раза, в корзине должно быть четыре продукта.

Как эта задача выглядит в коде:

```java

@Test
public void shouldBeTwoNumbersEqual() {
    int expected = 4;  // ожидаемое значение
    int actual = 4;  // фактическое значение
    assertEquals(expected, actual);  // проверяет равенство чисел через метод assertEquals
}
```

Обрати внимание: в этом примере нет метода, который нужно протестировать. Поэтому и тестовый метод `shouldBeTwoNumbersEqual` назван не по правилам: в нём нет
имени тестируемого метода, входных параметров и ожидаемого результата. Не забывай указывать их, когда будешь писать настоящие тестовые методы.

### `assertEquals`: сравнить дробные числа

Чтобы сравнить дробные числа, пользуются таким синтаксисом — `assertEquals(double expected, double actual, double delta)`. Здесь `delta` — максимальная разница
между `expected` и `actual`.

**Пример.** Нужно взвесить и расфасовать сливочное масло в брикеты. Масса каждого брикета — примерно одинаковая с допустимой погрешностью в 0.05 грамма.

Проверки в коде будут выглядеть так:

```java

@Test
public void shouldBeTwoDoubleEqual() {
    double expected = 180.00;  // ожидаемый результат
    double actual = 180.05;  // фактический результат
    assertEquals(expected, actual, 0.05);  // такое сравнение пройдёт без ошибок,
    // потому что разница между значениями не превышает delta = 0.05
}
```

Если значения, которые ты передаёшь, должны быть равны, `delta` задают равной нулю.

Например, если сравнивать массу золотых изделий, каждый миллиграмм на счету. Очень важно, чтобы масса точно совпадала:

```java

@Test
public void shouldBeTwoDoubleEqual() {
    double expected = 4.05;
    double actualDouble = 4.0;
    assertEquals(expected, actualDouble, 0);  // такая проверка вернётся с ошибкой и остановит исполнение кода
}
```

### `assertEquals`: сравнить строки

Метод `assertEquals` позволяет сравнивать не только числа, но и строки. Передавай их как аргументы:

```java

@Test
public void shouldBeTwoStringsEqual() {
    String expected = "Её глаза на звезды не похожи";
    String actual = "Нельзя уста кораллами назвать";
    assertEquals(expected, actual);  // метод выдаст ошибку
}
```

### Ошибки в assertEquals

Если передаваемые значения не равны, метод выдаст ошибку. Например, при сравнении строк из стихотворения вернётся такое сообщение:

```java
org.junit.ComparisonFailure:
Expected :
Её глаза
на звёзды
не похожи
Actual   :
Нельзя уста
кораллами назвать
```

Можно включить в метод своё сообщение, которое будет отображаться и для пользователя. Сообщение об ошибке добавляют в метод перед ожидаемым и фактическим
значениями.

Это необязательный элемент, но в тестировании он считается хорошим тоном. Как это выглядит в коде:

```java
assertEquals(String message,  // сообщение, которое возникло в случае ошибки
             String expected,  // ожидаемое значение
             String actual)  // фактическое значение
```

Тогда, если сравнить два значения, которые не равны, это будет выглядеть так:

```java
java.lang.AssertionError:
Дважды два
должно равняться 4 // сообщение об ошибке
Expected :4
Actual   :5
```

**Пример.** В форме регистрации на сайте пользователь должен ввести пароль дважды. Твоя задача — проверить, что такая функциональность работает правильно. В
документации зафиксировано: если пароли не совпадают, система выводит сообщение «Пароли должны совпадать».

Сделать в коде это можно так:

```java

@Test
public void shouldBeTwoStringsEqual() {
    String expected = "Пароли должны совпадать";  // ожидаемое сообщение о несовпадении паролей
    String actual = "Что-то пошло не так"; // фактическое сообщение
    assertEquals("Неверный текст ошибки!", expected, actual); // добавили сообщение об ошибке
}
```

### `assertNotEquals`: одно не равно другому

Если нужно проверить, что после исполнения кода программы одно значение точно поменяется на другое, пользуются другим методом — `assertNotEquals`.

Например, если в онлайн-магазине в корзину с товарами добавить любой товар, то предварительная сумма покупки должна измениться.

Если два значения не равны между собой — тест отдаёт статус Passed. Если равны — Failed.

Как узнать ошибку в коде:

```
java.lang.AssertionError: Values should be different.  // ошибка сообщает,
// что проверка утверждения выполнилась неудачно, потому что значения,
// которые мы сравниваем, должны различаться
```

Синтаксис метода `assertNotEquals` такой же, как у`assertEquals`. Только цель проверки — убедиться, что значения не равны между собой.

Примеры:

```java

@Test
public void shouldBeNotTwoStringsEqual() {
    String unexpected = "Не знаю я, как шествуют богини";
    String actual = "Но милая ступает по земле";
    assertNotEquals(unexpected, actual);  // метод выполнится успешно, т.к. ожидает,
    // что строки unexpected и actual отличаются
}
```

Так же как и в assertEquals, в assertNotEquals можно зашить сообщение об ошибке, которое будет видно и пользователю:

```java

@Test
public void shouldBeNotTwoNumbersEqual() {
    int unexpected = 4;
    int actual = 4;
    assertNotEquals("Результат расчёта должен отличаться от 4", unexpected, actual);  // в тексте message пишем подробности, чтобы ошибка была ещё более говорящей
}
```

Результат исполнения кода вернётся в таком виде:

```java
java.lang.AssertionError:
Результат расчёта
должен отличаться
от 4.Actual:4
```

## `assertThat`: проверить утверждение

Метод `assertThat` помогает проверить любое утверждение.

Синтаксис `assertThat` похож на естественный язык. Этот метод принимает на вход:

- Аргумент для проверки — например, строка `"Life is good"` или число 10.
- **Матчер** — специальный метод, в котором заложена логика проверки — например, `greaterThanOrEqualTo()`, `endsWith()`. В следующем уроке ты узнаешь, какие они
  бывают и как их использовать.
- Сообщение об ошибке — его указывать необязательно. Если указываешь, он должен стоять первым.

Например, нужно проверить, что 10 больше 5. Понадобится аргумент — 10 и матчер — `greaterThan(5)` («больше 5»):

```java
Assert.assertThat(10,greaterThan(5));
```

В первом аргументе можно передать сообщение об ошибке:

```java
Assert.assertThat("Это будет выведено, если проверка не сработает",10,greaterThan(5));
```

### Матчеры

Матчеры — это методы, которые описывают логику проверки. Например, нужно проверить, что в слове Java есть буквы va. Понадобится матчер `containsString` (
«содержит строку»).

Все матчеры — это статические методы из библиотеки Hamcrest. Чтобы с ними работать, нужно импортировать определённый матчер и специальный класс `MatcherAssert`:

```java
import static org.hamcrest.CoreMatchers.containsString; // импорт матчера containsString

import org.hamcrest.MatcherAssert; // импорт класса MatcherAssert
import org.junit.Test;

public class Example {

    @Test
    public void test() {
        String actual = "Java";
        String expected = "va";
        MatcherAssert.assertThat(actual, containsString(expected));
        // матчер передаётся в качестве аргумента в метод MatcherAssert.assertThat()
    }
}
```

### Для работы со строками

Строку на проверку нужно передать в аргументе матчеру; строку, с которой сравнивают, — в `assertThat`.

**Матчер `containsString`** проверяет, содержит ли одна строка другую:

```java
import static org.hamcrest.CoreMatchers.containsString;

import org.hamcrest.MatcherAssert;
import org.junit.Test;

public class Example {
    @Test
    public void testJava() {
        String actual = "Java"; // проверяемая строка
        String jSign = "J"; // строка, которая должна входить в проверяемую
        MatcherAssert.assertThat(actual, containsString(jSign));
        // метод assertThat принимает в качестве аргумента проверяемую строку;
        // матчер - нужную часть этой строки
    }
}
```

**Матчер `startsWith`** проверяет, начинается ли одна строка с другой:

```java
import static org.hamcrest.CoreMatchers.startsWith;

import org.hamcrest.MatcherAssert;
import org.junit.Test;

public class Example {
    @Test
    public void testJava() {
        String actual = "Java";
        String jSign = "J";
        MatcherAssert.assertThat(actual, startsWith(jSign));
    }
}
```

**Матчер `endsWith`** проверяет, заканчивается ли одна строка другой:

```java
import static org.hamcrest.CoreMatchers.endsWith;

import org.hamcrest.MatcherAssert;
import org.junit.Test;

public class Example {
    @Test
    public void testJava() {
        String actual = "Java";
        String vaLetters = "va";
        MatcherAssert.assertThat(actual, endsWith(vaLetters));
    }
}
```

### Для проверки нескольких условий

Такие матчеры принимают в аргументе другие матчеры.

**Матчер `allOf`** проверяет, что аргумент метода `assertThat` соответствует всем условиям — матчерам-аргументам метода `allOf`:

```java
import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.startsWith;

import org.hamcrest.MatcherAssert;
import org.junit.Test;

public class Example {
    @Test
    public void testJava() {
        String actual = "Java";
        String JSign = "J";
        String vaLetters = "va";
        MatcherAssert.assertThat(actual, allOf(containsString(vaLetters), startsWith(JSign))); // проверили сразу два условия с матчерами containsString и startsWith
    }
}
```

**Матчер `anyOf`** проверяет, что аргумент метода `assertThat` соответствует хотя бы одному условию — матчеру-аргументу метода `anyOf`:

```java
import static org.hamcrest.CoreMatchers.anyOf;
import static org.hamcrest.CoreMatchers.endsWith;
import static org.hamcrest.CoreMatchers.startsWith;

import org.hamcrest.MatcherAssert;
import org.junit.Test;

public class Example {
    @Test
    public void testJava() {
        String actual = "Java";
        String JSign = "J";
        String vaLetters = "va";
        MatcherAssert.assertThat(actual, anyOf(endsWith(vaLetters), startsWith(JSign)));
    }
}
```

### Для проверки, что значение не null

**Матчер `notNullValue()`** проверяет, что аргумент метода `assertThat` — не null-значение:

```java
import static org.hamcrest.CoreMatchers.notNullValue;

import org.hamcrest.MatcherAssert;
import org.junit.Test;

public class Example {
    @Test
    public void testJava() {
        String actual = "Java";
        MatcherAssert.assertThat(actual, notNullValue());
    }
}
```

### Для проверки истинности и ложности

**Матчер `is`** проверяет, что один аргумент является другим:

```java
import static org.hamcrest.CoreMatchers.is;

import org.hamcrest.MatcherAssert;
import org.junit.Test;

public class Example {
    @Test
    public void testNumbers() {
        MatcherAssert.assertThat(10, is(10));
    }
}
```

**Матчер `not`** — логическое отрицание другого матчера:

```java
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;

import org.hamcrest.MatcherAssert;
import org.junit.Test;

public class Example {
    @Test
    public void testNumbers() {
        MatcherAssert.assertThat(10, is(not(11)));
    }
}
```

# Параметризация

## Что такое параметризация

Когда тесты отличаются только тестовыми данными, применяют **параметризацию**. Это механизм, который отделяет тестовые данные от кода.

**Пример**. Представь, что тебе нужно написать юнит-тесты для метода, который возвращает сумму двух целых чисел:

```java
package ru.yandex.praktikum;

public class Calculator {

    public int sum(int a, int b) {
        return a + b;
    }
}
```

Его нужно протестировать на разных данных. Например, тест на сложение двух положительных чисел получится таким:

```java
package ru.yandex.praktikum;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CalculatorTest {

    @Test
    public void shouldSumPositive() {
        Calculator calculator = new Calculator(); // создали экземпляр класса
        int firstNumber = 1;
        int secondNumber = 9;
        int actual = calculator.sum(firstNumber, secondNumber); // вызвали проверяемый метод
        int expected = 10;
        assertEquals(expected, actual); // сравнили ожидаемый результат с фактическим
    }
}
```

Можно добавить ещё один тест — на сложение положительного числа и нуля:

```java
package ru.yandex.praktikum;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CalculatorTest {

    @Test
    public void shouldSumPositive() {
        Calculator calculator = new Calculator(); // создали экземпляр класса
        int firstNumber = 1;
        int secondNumber = 9;
        int actual = calculator.sum(firstNumber, secondNumber); // вызвали проверяемый метод
        int expected = 10;
        assertEquals(expected, actual); // сравнили ожидаемый результат с фактическим
    }

    @Test
    public void shouldSumPositiveAndZero() {
        Calculator calculator = new Calculator(); // создали экземпляр класса
        int firstNumber = 1;
        int secondNumber = 0;
        int actual = calculator.sum(firstNumber, secondNumber); // вызвали проверяемый метод
        int expected = 1;
        assertEquals(expected, actual); // сравнили ожидаемый результат с фактическим
    }

}
```

У всех тестов для этого метода одинаковая структура:

1. Создать экземпляр класса.
2. Вызвать проверяемый метод с двумя числами.
3. Сравнить ожидаемый и фактический результат.

Чтобы покрыть метод тестами, придётся дублировать код и менять только тестовые данные и ожидаемый результат. Вместо этого можно написать параметризованный тест.

## Как написать параметризованный тест

Параметризованные тесты пишут через JUnit.

### Аннотация `@RunWith`

Перед тестовым классом нужно указать раннер `Parameterized` — класс, который помогает запускать тесты с параметризацией. Для этого нужна
аннотация `@RunWith(Parameterized.class)`:

```java
package ru.yandex.praktikum;

// импорт класса Parameterized и аннотации RunWith

import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class) // указали, что тесты будет запускать раннер Parameterized

public class CalculatorTest {
}
```

Запись `@RunWith(Parameterized.class)` означает, что тесты класса `CalculatorTest` будет запускать раннер `Parameterized`.

### Поля и конструктор класса

Теперь нужно добавить поля класса — в них будут храниться параметры тестового метода.

Вспомни тесты для метода `sum()`:

```java
public class CalculatorTest {

    @Test
    public void shouldSumPositive() {
        Calculator calculator = new Calculator(); // создали экземпляр класса
        int firstNumber = 1;
        int secondNumber = 9;
        int actual = calculator.sum(firstNumber, secondNumber); // вызвали проверяемый метод
        int expected = 10;
        assertEquals(expected, actual); // сравнили ожидаемый результат с фактическим
    }

    @Test
    public void shouldSumPositiveAndZero() {
        Calculator calculator = new Calculator(); // создали экземпляр класса
        int firstNumber = 1;
        int secondNumber = 0;
        int actual = calculator.sum(firstNumber, secondNumber); // вызвали проверяемый метод
        int expected = 1;
        assertEquals(expected, actual); // сравнили ожидаемый результат с фактическим
    }
}
```

Чтобы написать параметризованный тест для этого метода, понадобится три параметра: `firstNumber` — первое число, `secondNumber` — второе число, `expected` —
ожидаемый результат, то есть сумма двух чисел.

Такие поля нужно создать в классе `CalculatorTest`:

```java
package ru.yandex.praktikum;

import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class CalculatorTest {
    private final int firstNumber;
    private final int secondNumber;
    private final int expected;
}
```

Чтобы менять, или **параметризовать** эти поля, нужно объявить конструктор класса `CalculatorTest`. Он принимает в качестве параметров значения всех полей
класса:

```java
package ru.yandex.praktikum;

import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class CalculatorTest {
    private final int firstNumber;
    private final int secondNumber;
    private final int expected;

    public CalculatorTest(int firstNumber, int secondNumber, int expected) {
        this.firstNumber = firstNumber;
        this.secondNumber = secondNumber;
        this.expected = expected;
    }
}
```

Обрати внимание: все поля — `final`, потому что инициализируются один раз в конструкторе, и `private`, потому что доступны только в пределах класса.

### Метод для получения данных

Чтобы получать конкретные тестовые значения, нужен метод с аннотацией `@Parameterized.Parameters`. Он должен быть публичным и статическим:

```java
package ru.yandex.praktikum;

import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class CalculatorTest {
    private final int firstNumber;
    private final int secondNumber;
    private final int expected;

    public CalculatorTest(int firstNumber, int secondNumber, int expected) {
        this.firstNumber = firstNumber;
        this.secondNumber = secondNumber;
        this.expected = expected;
    }

    @Parameterized.Parameters // добавили аннотацию
    public static Object[][] getSumData() {
        return new Object[][]{
                {1, 9, 10},
                {1, 0, 1},
        };
    }
}
```

Метод `getSumData()` возвращает **двумерный массив** `Object`, то есть массив, в котором хранятся другие массивы. Поэтому после его названия нужно два раза
написать квадратные скобки — `Object[][]`.

Каждая строка с данными — это тестовый набор для одного запуска теста: `firstNumber`, `secondNumber`, `expected`. Например, первый раз тест будет запущен со
значениями 1, 9, 10, а второй — со значениями 1, 0, 1. Количество запусков теста равно количеству строк с данными.

Тестовые данные нужно подбирать по техникам тест-дизайна: классы эквивалентности и граничные значения.

### Тестовый метод

Осталось написать сам тест. Вместо конкретных значений в методе `shouldBeSum()` нужно обратиться к полям тестового класса и сравнить ожидаемый результат с
фактическим:

```java
package ru.yandex.praktikum;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static org.junit.Assert.assertEquals;

@RunWith(Parameterized.class)
public class CalculatorTest {
    private final int firstNumber;
    private final int secondNumber;
    private final int expected;

    public CalculatorTest(int firstNumber, int secondNumber, int expected) {
        this.firstNumber = firstNumber;
        this.secondNumber = secondNumber;
        this.expected = expected;
    }

    @Parameterized.Parameters
    public static Object[][] getSumData() {
        return new Object[][]{
                {1, 9, 10},
                {1, 0, 1},
        };
    }

    @Test
    public void shouldBeSum() {
        Calculator calculator = new Calculator();
        int actual = calculator.sum(firstNumber, secondNumber);
        assertEquals(expected, actual);
    }
}
```

Чтобы запустить этот тест с другими данными, достаточно дописать ещё одну строку в метод `getSumData()`.

### [Назад к оглавлению](../../../../README.md)
