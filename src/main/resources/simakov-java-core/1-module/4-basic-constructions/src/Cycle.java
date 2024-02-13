/**
 * Created by igorsimakov on 29.12.2021
 */
public class Cycle {
    public static void main(String[] args) {

    }

    /**
     * Выполнение цикла `for` продолжается до тех пор, пока проверка условия даёт истинный результат `true`
     *
     * В начале каждого шага цикла проверяется _условие_ `i < 5`. Если это условие дает `true`, вызывается тело цикла,
     * затем выполняется _итерационная_ часть цикла. Как только условное выражение примет значение `false`, цикл закончит свою работу.
     *
     * Результат:
     * i = 0
     * i = 1
     * i = 2
     * i = 3
     * i = 4
     */
    public static void printFrom0to5() {
        for (int i = 0; i < 5; i++) {
            System.out.println("i = " + i);
        }
        System.out.println("end");
    }

    /**
     * Пример цикла с отрицательным приращением цикла. Еще одной особенностью цикла является «вынос»
     * объявления управляющей переменной до начала цикла, хотя обычно она объявляется внутри for.
     */
    public static void negativeStepCycle() {
        int x;  // объявление управляющей переменной вынесено до начала цикла
        for (x = 10; x >= 0; x -= 5) { // Шаг -5
            System.out.print(x + " ");
        }
    }

    /**
     * Код в цикле может вообще не выполняться, если проверяемое условие с начала оказывается ложным
     */
    public static void noStartMethodBody() {
        int x = 0;
        for (int count = 10; count < 5; count++) {
            x += count; // этот оператор не будет выполнен, так как 10 > 5
        }
    }

    /**
     * Цикл for с несколькими управляющими переменными
     *
     * Результат:
     * i-j: 0-10
     * i-j: 1-9
     * i-j: 2-8
     * i-j: 3-7
     * i-j: 4-6
     */
    public static void twoInitVariables() {
        for (int i = 0, j = 10; i < j; i++, j--) {
            System.out.println("i-j: " + i + "-" + j);
        }
    }

    /**
     * Бесконечный цикл:
     */
    public static void infinityForLoop() {
        for (;;) {
            System.out.println("infinityForLoop");
        }
    }

    /**
     * С помощью оператора `break` можно прервать выполнение цикла
     *
     * Результат:
     * i = 0
     * i = 1
     * i = 2
     * i = 3
     */
    public static void breakExample() {
        for(int i = 0; i < 10; i++) {
            if (i > 3) {
                break;
            }
            System.out.println("i = " + i);
        }
    }

}