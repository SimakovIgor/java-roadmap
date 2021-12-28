/**
 * !!! Если ключевое слово static будет отсутствовать, метод не получится вызвать из метода main().
 * Смысл этого ключевого слова будет пояснен на следующих занятиях в теме «Объектно-ориентированное программирование».
 */
public class Methods {
    public static void main(String[] args) {
        // передаем 2 аргумента типа int, результатом работы будет целое число, которое напечатается в консоль
        System.out.println(summ(5, 5));

        printSomeText();
        printMyText("Java");
    }

    /**
     * Метод возвращает целое число, принимает на вход два целых числа
     *
     * @param a - first int
     * @param b - second int
     * @return - sum
     */
    public static int summ(int a, int b) {
        return a + b;
    }

    /**
     * Выводит Hello, World в консоль
     */
    public static void printSomeText() {
        System.out.println("Hello, World");
    }

    /**
     * Печатает текст в консоль
     *
     * @param text - text to print
     */
    public static void printMyText(String text) {
        System.out.println(text);
    }
}
