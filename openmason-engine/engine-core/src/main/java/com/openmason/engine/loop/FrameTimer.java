package com.openmason.engine.loop;

/**
 * The variable frame delta (#96): seconds since the previous {@link #tick}, clamped to
 * {@code maxDeltaSeconds} so a stall (loading, a debugger, a dragged window) arrives as one bounded
 * step instead of a teleport. Stonebreak's client clamps at 0.1 s.
 */
public final class FrameTimer {

    private final float maxDeltaSeconds;
    private long lastNs;
    private boolean started;

    public FrameTimer(float maxDeltaSeconds) {
        if (!(maxDeltaSeconds > 0f)) {
            throw new IllegalArgumentException("maxDeltaSeconds must be > 0: " + maxDeltaSeconds);
        }
        this.maxDeltaSeconds = maxDeltaSeconds;
    }

    /** Starts (or restarts) timing at {@code nowNs}; the next tick measures from here. */
    public void start(long nowNs) {
        lastNs = nowNs;
        started = true;
    }

    /** Seconds since the previous tick (or {@link #start}), clamped; 0 on an unstarted timer's first tick. */
    public float tick(long nowNs) {
        if (!started) {
            start(nowNs);
            return 0f;
        }
        float dt = (nowNs - lastNs) / 1_000_000_000.0f;
        lastNs = nowNs;
        if (dt > maxDeltaSeconds) {
            return maxDeltaSeconds;
        }
        return Math.max(0f, dt);
    }

    public float maxDeltaSeconds() {
        return maxDeltaSeconds;
    }
}
