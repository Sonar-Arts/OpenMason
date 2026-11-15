package com.openmason.engine.loop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The headless fixed step (#96) with the integrated server's numbers: 50 ms, 5-tick clamp. */
class FixedStepTest {

    private static final long MS = 1_000_000L;

    private static int drain(FixedStep step) {
        int n = 0;
        while (step.consumeTick()) {
            n++;
        }
        return n;
    }

    @Test
    void theFirstAdvanceOnlyStartsTheClock() {
        FixedStep step = new FixedStep(50 * MS, 5);
        assertEquals(0, step.advance(1_000 * MS));
        assertEquals(0, drain(step));
        step.advance(1_120 * MS);
        assertEquals(2, drain(step));
        assertEquals(0.4, step.alpha(), 1e-9);
    }

    @Test
    void catchUpIsClampedAndTheDroppedTicksReported() {
        FixedStep step = new FixedStep(50 * MS, 5);
        step.advance(0);
        assertEquals(15, step.advance(1_000 * MS), "20 ticks due, 5 kept");
        assertTrue(step.hasPendingTick());
        assertEquals(5, drain(step));
        assertFalse(step.hasPendingTick());
        assertEquals(0.0, step.alpha());
    }

    @Test
    void resetResumesWithoutABurst() {
        FixedStep step = new FixedStep(50 * MS, 5);
        step.advance(0);
        step.advance(40 * MS);
        step.reset(10_000 * MS);
        step.advance(10_060 * MS);
        assertEquals(1, drain(step));
        step.reset();
        assertEquals(0, step.advance(99_999 * MS), "after reset() the next advance restarts the clock");
        assertEquals(0, drain(step));
    }

    @Test
    void ofHzAndValidation() {
        assertEquals(50 * MS, FixedStep.ofHz(20, 5).periodNs());
        assertEquals(0.05f, FixedStep.ofHz(20, 5).periodSeconds(), 1e-7f);
        assertThrows(IllegalArgumentException.class, () -> new FixedStep(0, 5));
        assertThrows(IllegalArgumentException.class, () -> new FixedStep(50 * MS, 0));
    }
}
