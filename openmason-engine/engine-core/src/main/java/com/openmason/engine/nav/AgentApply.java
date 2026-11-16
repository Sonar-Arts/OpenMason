package com.openmason.engine.nav;

/**
 * What a {@link GroundAgent} call decided for its body ({@code cn_apply}). The host applies it in
 * this order: {@link #turn()} (yaw), {@link #jump()} / {@link #swimUp()}, then {@link #setVelocity()}
 * (horizontal velocity). Reusable.
 */
public final class AgentApply {

    static final int YAW = 1;
    static final int VELOCITY = 2;
    static final int JUMP = 4;
    static final int SWIM_UP = 8;

    int apply;
    float yaw;
    float vx;
    float vz;

    /** Whether to set the body's yaw to {@link #yaw()}. */
    public boolean turn() {
        return (apply & YAW) != 0;
    }

    /** Whether to set the body's horizontal velocity to ({@link #vx()}, {@link #vz()}). */
    public boolean setVelocity() {
        return (apply & VELOCITY) != 0;
    }

    /** Whether to jump (the body was standing). */
    public boolean jump() {
        return (apply & JUMP) != 0;
    }

    /** Whether to swim a stroke upward (the body was in water). */
    public boolean swimUp() {
        return (apply & SWIM_UP) != 0;
    }

    public float yaw() {
        return yaw;
    }

    public float vx() {
        return vx;
    }

    public float vz() {
        return vz;
    }

    void read(java.lang.foreign.MemorySegment s) {
        apply = s.get(CendaNav.I32, 0);
        yaw = s.get(CendaNav.F32, 4);
        vx = s.get(CendaNav.F32, 8);
        vz = s.get(CendaNav.F32, 12);
    }
}
