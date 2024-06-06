package ru.simakov.javacore.lvl1;

public class Wall extends Obstacle {
    private final int height;

    public Wall(final int height) {
        this.height = height;
    }

    @Override
    public void doIt(final Participant participant) {
        participant.jump(height);
    }
}
