/**
 * Created by igorsimakov on 02.01.2022
 */
public class DoubleArrays {
    /**
     * В следующем примере создадим двумерный массив размером 3х4, заполним его числами от 1 до 12 и
     * отпечатаем в консоль в виде таблицы.
     */
    public static void doubleArr() {
        int counter = 1;
        int[][] table = new int[3][4];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 4; j++) {
                table[i][j] = counter;
                System.out.print(table[i][j] + " ");
            }
            System.out.println();
        }
    }

    /**
     * При работе с отладкой и двумерными массивами для их распечатки можно пользоваться следующим методом.
     * На вход метода необходимо подать ссылку на любой двумерный целочисленный массив. Первый индекс массива
     * указывает на строку, второй – на столбец.
     * @param arr - arr
     */
    public static void printDoubleArr(int [][] arr) {
        for (int i = 0; i < arr.length; i++) {
            for (int j = 0; j < arr[i].length; j++) {
                System.out.print(arr[i][j]);
            }
            System.out.println();
        }
    }
}
