/**
 * Created by igorsimakov on 02.01.2022
 */
public class Arrays {
    public static void main(String[] args) {

    }

    /**
     * В приведенном ниже примере программы в массиве arr сохраняются числа от 0 до 4.
     * <p>
     * //Результат:
     * //arr[0] = 0
     * //arr[1] = 1
     * //arr[2] = 2
     * //arr[3] = 3
     * //arr[4] = 4
     */
    public static void printArr1() {
        int[] arr = new int[5];
        for (int i = 0; i < 5; i++) {
            arr[i] = i;
            System.out.println("arr[" + i + "] = " + arr[i]);
        }
    }


    /**
     * Заполнять созданные массивы можно последовательным набором операторов.
     *
     * @return - arr
     */
    public static int[] fillArr() {
        int[] nums = new int[4];
        nums[0] = 5;
        nums[1] = 10;
        nums[2] = 15;
        nums[3] = 15;

        return nums;
    }

    /**
     * Существует более простой способ решения этой задачи: заполнить массив сразу при его создании.
     *
     * @return arr
     */
    public static int[] fillArrEasy() {
//        тип_данных[] имя_массива = {v1, v2, v3, ..., vN} ;
        int[] nums = {5, 10, 15, 20};
        return nums;
    }

    /**
     * Если обратиться к несуществующему элементу массива, будет получена ошибка.
     * <p>
     * Как только значение переменной `i` достигнет 10, будет сгенерировано исключение `ArrayIndexOutOfBoundsException`
     * и выполнение программы прекратится.
     */
    public static void exceptionExample() {
        int[] arr = new int[10];
        for (int i = 0; i < 20; i++) {
            arr[i] = i;
        }
    }

    /**
     * Распечатать одномерный массив в консоль можно с помощью конструкции `Arrays.toString()`.
     */
    public static void printArrWithToString() {
        String[] arr = {"A", "B", "C", "D"};
        System.out.println(java.util.Arrays.toString(arr));
    }

    /**
     * Получение длины массива
     *
     * Результат:
     * arr.length: 8
     * 2 4 5 1 2 3 4 5
     */
    public static void getArrLength() {
        int[] arr = {2, 4, 5, 1, 2, 3, 4, 5};
        System.out.println("arr.length: " + arr.length);
        for (int i = 0; i < arr.length; i++) {
            System.out.print(arr[i] + " ");
        }
    }
}
