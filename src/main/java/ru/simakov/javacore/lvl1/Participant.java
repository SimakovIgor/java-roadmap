package ru.simakov.javacore.lvl1;

/**
 * Можно ли данный interface переделать в абстрактный класс? Когда что лучше?
 */
public interface Participant {
    void run(int distance);

    void swim(int distance);

    void jump(int height);

}
