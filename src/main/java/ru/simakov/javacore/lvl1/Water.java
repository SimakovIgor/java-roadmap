package ru.simakov.javacore.lvl1;

public class Water extends Obstacle {
    private final int distance;

    public Water(int distance) {
        this.distance = distance;
    }

    @Override
    public void doIt(Participant participant) {
        participant.swim(distance);
    }
}
