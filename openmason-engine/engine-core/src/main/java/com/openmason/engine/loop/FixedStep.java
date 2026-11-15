package com.openmason.engine.loop;

/**
 * A headless fixed-timestep accumulator (#96): wall time goes in through {@link #advance}, whole
 * ticks come out through {@link #consumeTick}, and the remainder is the render interpolation
 * {@link #alpha}. Catch-up is clamped to {@code maxCatchUpTicks} so a stall (GC, debugger, a slow
 * tick) never turns into a spiral of death; the dropped ticks are reported so the host can warn.
 *
 * <p>Usable with or without a window: Stonebreak's integrated server runs its 20 Hz step on it
 * ({@code new FixedStep(50_000_000L, 5)}). Not thread-safe; one owner thread.
 *
 * <pre>{@code
 * long dropped = step.advance(System.nanoTime());
 * while (step.consumeTick()) {
 *     tick(step.periodSeconds());
 * }
 * }</pre>
 */
public final class FixedStep {

    private final long periodNs;
    private final long maxAccumulatorNs;
    private long accumulatorNs;
    private long lastNs;
    private boolean started;

    /**
     * @param periodNs        the tick length in nanoseconds (&gt; 0)
     * @param maxCatchUpTicks the most ticks one {@link #advance} may bank (&ge; 1)
     */
    public FixedStep(long periodNs, int maxCatchUpTicks) {
        if (periodNs <= 0) {
            throw new IllegalArgumentException("periodNs must be > 0: " + periodNs);
        }
        if (maxCatchUpTicks < 1) {
            throw new IllegalArgumentException("maxCatchUpTicks must be >= 1: " + maxCatchUpTicks);
        }
        this.periodNs = periodNs;
        this.maxAccumulatorNs = periodNs * maxCatchUpTicks;
    }

    /** A step of {@code hz} ticks per second. */
    public static FixedStep ofHz(int hz, int maxCatchUpTicks) {
        return new FixedStep(1_000_000_000L / hz, maxCatchUpTicks);
    }

    /**
     * Banks the time since the previous call (nothing on the first call, which only starts the
     * clock), clamped to the catch-up limit.
     *
     * @return how many whole ticks the clamp dropped (0 when keeping up)
     */
    public long advance(long nowNs) {
        if (!started) {
            started = true;
            lastNs = nowNs;
        }
        accumulatorNs += nowNs - lastNs;
        lastNs = nowNs;
        if (accumulatorNs > maxAccumulatorNs) {
            long dropped = (accumulatorNs - maxAccumulatorNs) / periodNs;
            accumulatorNs = maxAccumulatorNs;
            return dropped;
        }
        return 0;
    }

    /** Takes one banked tick; false when less than a period remains. */
    public boolean consumeTick() {
        if (accumulatorNs >= periodNs) {
            accumulatorNs -= periodNs;
            return true;
        }
        return false;
    }

    /** True when another whole tick is banked (a catch-up burst is still running). */
    public boolean hasPendingTick() {
        return accumulatorNs >= periodNs;
    }

    /** How far into the next tick the banked remainder is, in [0, 1): the render interpolation factor. */
    public double alpha() {
        return Math.min(1.0, (double) accumulatorNs / periodNs);
    }

    /** Drops banked time and restarts the clock at {@code nowNs}: resume after a pause without a burst. */
    public void reset(long nowNs) {
        accumulatorNs = 0;
        lastNs = nowNs;
        started = true;
    }

    /** Drops banked time; the next {@link #advance} starts the clock again. */
    public void reset() {
        accumulatorNs = 0;
        started = false;
    }

    public long periodNs() {
        return periodNs;
    }

    public float periodSeconds() {
        return periodNs / 1_000_000_000f;
    }

    public long accumulatedNs() {
        return accumulatorNs;
    }
}
