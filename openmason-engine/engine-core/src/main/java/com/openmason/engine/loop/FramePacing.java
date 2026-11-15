package com.openmason.engine.loop;

/**
 * The manual frame limiter (#96, from Stonebreak's {@code GameWindow.frameBudgetNanos}). "VSync" here
 * means capping at the monitor's refresh rate with a sleep, not the driver's swap-interval 1: the cap
 * gives the same tear suppression on G-Sync/FreeSync displays without the half-rate fallback that
 * double-buffered swap-interval 1 forces when a frame misses vblank. The inputs are passed in; the
 * engine reads no host settings.
 */
public final class FramePacing {

    private FramePacing() {
    }

    /**
     * The per-frame budget, or 0 for uncapped (no sleep). The effective cap is the lowest active
     * limit: the monitor rate when {@code vsync} is on, and {@code maxFps} when it is positive.
     *
     * @param vsync     cap at {@code monitorHz}
     * @param monitorHz the refresh rate of the monitor the window is on (ignored when &le; 0)
     * @param maxFps    the frame-rate cap; &le; 0 means unlimited
     */
    public static long budgetNanos(boolean vsync, int monitorHz, int maxFps) {
        int cap = Integer.MAX_VALUE;
        if (vsync && monitorHz > 0) {
            cap = Math.min(cap, monitorHz);
        }
        if (maxFps > 0) {
            cap = Math.min(cap, maxFps);
        }
        if (cap == Integer.MAX_VALUE) {
            return 0L;
        }
        return 1_000_000_000L / cap;
    }

    /**
     * Sleeps away what is left of {@code budgetNanos} after a frame that took {@code frameNanos}.
     * A budget of 0, an overrun, or less than a millisecond left: no sleep.
     *
     * @return false if the sleep was interrupted (the loop should exit)
     */
    public static boolean sleepRemaining(long budgetNanos, long frameNanos) {
        long remaining = remainingNanos(budgetNanos, frameNanos);
        if (remaining <= 0) {
            return true;
        }
        try {
            Thread.sleep(remaining / 1_000_000, (int) (remaining % 1_000_000));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** The sleep {@link #sleepRemaining} would do, in nanoseconds; 0 when it would not sleep. */
    public static long remainingNanos(long budgetNanos, long frameNanos) {
        long remaining = budgetNanos - frameNanos;
        return remaining >= 1_000_000 ? remaining : 0L;
    }
}
