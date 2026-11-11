package com.openmason.engine.audio;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Footsteps fall on ground covered, not on a clock: the step count follows the distance walked,
 * never the number of updates (moved from Stonebreak's MobSoundsTest with the logic).
 */
@Tag("regression")
class StrideFootstepsTest {

    private static final float STRIDE = 1.0f;

    /** A walker at x along +X; {@code grounded}/{@code swimming} per update. */
    private static final class Walker {
        final StrideFootsteps steps;
        float x;
        boolean grounded = true;
        boolean swimming;

        Walker(float stride) {
            steps = new StrideFootsteps(stride);
            steps.update(x, 0, grounded, swimming); // establish the starting point
        }

        int walk(float perUpdate, int updates) {
            int n = 0;
            for (int i = 0; i < updates; i++) {
                x += perUpdate;
                if (steps.update(x, 0, grounded, swimming)) n++;
            }
            return n;
        }
    }

    @Test
    void stepsOncePerStrideOfTravel() {
        assertEquals(3, new Walker(STRIDE).walk(0.25f, 13), "3.25 blocks is three footsteps");
    }

    @Test
    void speedChangesCadenceNotStrideCount() {
        int slow = new Walker(STRIDE).walk(0.1f, 45);
        int fast = new Walker(STRIDE).walk(0.5f, 9);
        assertEquals(slow, fast);
        assertEquals(4, slow);
    }

    @Test
    void standingStillNeverSteps() {
        assertEquals(0, new Walker(STRIDE).walk(0f, 100));
    }

    @Test
    void sustainedAirtimeIsSilent() {
        Walker w = new Walker(STRIDE);
        w.walk(0.4f, 2);
        w.grounded = false;
        w.walk(0.4f, StrideFootsteps.GROUND_GRACE_UPDATES + 2); // burn through the grace
        assertEquals(0, w.walk(0.4f, 30));
    }

    @Test
    void aMomentaryLossOfGroundContactDoesNotSilenceAWalker() {
        Walker w = new Walker(STRIDE);
        int steps = 0;
        for (int i = 0; i < 40; i++) {
            w.grounded = i % 2 == 0;
            steps += w.walk(0.25f, 1);
        }
        assertTrue(steps > 0);
    }

    @Test
    void swimmingIsSilentWadingIsNot() {
        Walker swimmer = new Walker(STRIDE);
        swimmer.swimming = true;
        assertEquals(0, swimmer.walk(0.4f, 30));
        assertTrue(new Walker(STRIDE).walk(0.4f, 30) > 0);
    }

    @Test
    void teleportsDoNotFireABurst() {
        StrideFootsteps s = new StrideFootsteps(STRIDE);
        s.update(0, 0, true, false);
        assertFalse(s.update(500, 500, true, false));
    }

    @Test
    void theFirstUpdateAndResetOnlyEstablishAPosition() {
        StrideFootsteps s = new StrideFootsteps(STRIDE);
        assertFalse(s.update(100, 100, true, false));
        s.reset();
        assertFalse(s.update(101.4f, 100, true, false), "the jump after a reset is not travel");
    }

    @Test
    void sizedFromTheBody() {
        assertEquals(0.4f, StrideFootsteps.strideFor(0.1f), 1e-6f, "never under 0.4");
        assertEquals(1.6f, StrideFootsteps.strideFor(1.6f), 1e-6f);
        assertEquals(0.15f, StrideFootsteps.volumeFor(0.1f), 1e-6f);
        assertEquals(0.4f, StrideFootsteps.volumeFor(5f), 1e-6f);
        assertTrue(StrideFootsteps.volumeFor(1.5f) > StrideFootsteps.volumeFor(0.8f));
    }
}
