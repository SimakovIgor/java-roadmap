public class IfElse {
    public static void main(String[] args) {
        boolean condition = true;
        if (condition) {
            //...
        }
        if (!condition) {
            //not ...
        }

        int a = 2, b = 3, c = 0;

        if (a < b) {
            System.out.println("a меньше b");
        }

        if (a == b) {
            System.out.println("a равно b. Это сообщение не будет выведено");
        }

        c = a - b;
        System.out.println("с = " + c);

        if (c >= 0) {
            System.out.println("с не отрицательно");
        }

        if (c < 0) {
            System.out.println("c отрицательно");
        }

        c = b - a; // переменная с = 3 - 2 = 1
        System.out.println("с = " + c);

        if (c >= 0) {
            System.out.println("с неотрицательно");
        }

        if (c < 0) {
            System.out.println("c отрицательно");
        }

    }
}