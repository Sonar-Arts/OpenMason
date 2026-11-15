package com.openmason.engine.loop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Frame budget math (#96, from Stonebreak's GameWindow.frameBudgetNanos) and the delta clamp. */
class FramePacingTest {

    @Test
    void vsyncCapsAtTheMonitorRate() {
        assertEquals(1_000_000_000L / 144, FramePacing.budgetNanos(true, 144, 0));
    }

    @Test
    void theLowestActiveCapWins() {
        assertEquals(1_000_000_000L / 60, FramePacing.budgetNanos(true, 144, 60));
        assertEquals(1_000_000_000L / 144, FramePacing.budgetNanos(true, 144, 240));
        assertEquals(1_000_000_000L / 240, FramePacing.budgetNanos(false, 144, 240));
    }

    @Test
    void noActiveCapMeansNoSleep() {
        assertEquals(0L, FramePacing.budgetNanos(false, 144, 0));
        assertEquals(0L, FramePacing.budgetNanos(true, 0, -1), "an unknown monitor rate is no cap");
        assertEquals(0L, FramePacing.remainingNanos(0L, 5_000_000L));
        assertTrue(FramePacing.sleepRemaining(0L, 0L));
    }

    @Test
    void sleepsOnlyWhenAtLeastAMillisecondIsLeft() {
        assertEquals(0L, FramePacing.remainingNanos(16_000_000L, 20_000_000L), "overrun");
        assertEquals(0L, FramePacing.remainingNanos(16_000_000L, 15_500_000L), "under 1 ms left");
        assertEquals(6_000_000L, FramePacing.remainingNanos(16_000_000L, 10_000_000L));
    }

    @Test
    void frameTimerClampsTheDelta() {
        FrameTimer t = new FrameTimer(0.1f);
        assertEquals(0f, t.tick(5_000_000_000L), "an unstarted timer's first tick only starts it");
        assertEquals(0.016f, t.tick(5_016_000_000L), 1e-6f);
        assertEquals(0.1f, t.tick(9_000_000_000L), "a stall arrives as one bounded step");
        t.start(10_000_000_000L);
        assertEquals(0.05f, t.tick(10_050_000_000L), 1e-6f);
        assertThrows(IllegalArgumentException.class, () -> new FrameTimer(0f));
    }
}
