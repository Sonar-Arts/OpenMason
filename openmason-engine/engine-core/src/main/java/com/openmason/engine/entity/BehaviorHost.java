package com.openmason.engine.entity;

/**
 * The host side of a {@link BehaviorScheduler}: the behaviours themselves. The scheduler decides
 * which run; the host answers its questions and carries out what it decides, by behaviour index (the
 * order the behaviours were given to {@link BehaviorScheduler#create}).
 *
 * <p>Called on the thread that called the scheduler, inside that call, in the order the native
 * contract fixes ({@code behavior.h}). An exception thrown here aborts the scheduler call and is
 * rethrown from it.
 */
public interface BehaviorHost {

    /** Whether running behaviour {@code index} keeps going. */
    boolean shouldContinue(int index);

    /** Whether behaviour {@code index} (not running, and fitting) can begin. */
    boolean canStart(int index);

    /** Behaviour {@code index} begins. */
    void start(int index);

    /** Behaviour {@code index} ends, whether it finished or was interrupted. */
    void stop(int index);

    /** Running behaviour {@code index} does its tick. */
    void tick(int index, float deltaTime);

    /** A uniform float in {@code [0, 1)} for the weighted choice among equal priorities. */
    float random();
}
