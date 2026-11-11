package com.openmason.engine.audio;

/**
 * Footstep timing by ground covered: one step per stride of horizontal travel.
 *
 * <p>Stepping on distance rather than on a timer makes cadence right without tuning: an ambling
 * and a running creature cover ground at different rates, so they step at different rates for
 * free. A creature that leaves the ground for a few updates stops accumulating instead of
 * resetting, so hop-prone walkers do not stutter, and nothing depends on a frame clock.
 *
 * <p>The host decides what "grounded" and "swimming" mean and which sample a step plays; this
 * class only decides when a step falls. Not thread-safe: one instance per walker.
 */
public final class StrideFootsteps {

    /** A single-update jump beyond this is a teleport or correction, not walking. */
    public static final float MAX_TRAVEL_PER_UPDATE = 1.5f;

    /**
     * Updates of tolerance for a walker that momentarily leaves the ground. Not a nicety: a
     * walking creature is measurably airborne about half the time as contact flickers.
     */
    public static final int GROUND_GRACE_UPDATES = 4;

    private static final float MIN_STRIDE = 0.4f;
    private static final float VOLUME_PER_BLOCK_TALL = 0.18f;
    private static final float VOLUME_BASE = 0.12f;
    private static final float MIN_STEP_VOLUME = 0.15f;
    private static final float MAX_STEP_VOLUME = 0.4f;

    private final float strideLength;
    private float lastX;
    private float lastZ;
    private boolean hasLast;
    private float sinceStep;
    private int groundedGrace;

    /** @param strideLength travel between footsteps (a short-legged walker has a short stride) */
    public StrideFootsteps(float strideLength) {
        this.strideLength = Math.max(0.1f, strideLength);
    }

    /** A creature strides about its own body length (never under 0.4). */
    public static float strideFor(float bodyLength) {
        return Math.max(MIN_STRIDE, bodyLength);
    }

    /** A taller creature lands harder: step gain from its standing height, within 0.15..0.4. */
    public static float volumeFor(float standingHeight) {
        return Math.min(MAX_STEP_VOLUME, Math.max(MIN_STEP_VOLUME, VOLUME_PER_BLOCK_TALL * standingHeight + VOLUME_BASE));
    }

    public float strideLength() {
        return strideLength;
    }

    /**
     * Accumulates travel to ({@code x}, {@code z}); call once per update.
     *
     * @param grounded touching the ground this update
     * @param swimming swimming (no footfalls), as opposed to wading
     * @return whether a footstep fell this update
     */
    public boolean update(float x, float z, boolean grounded, boolean swimming) {
        if (!hasLast) {
            lastX = x;
            lastZ = z;
            hasLast = true;
            return false;
        }
        float dx = x - lastX;
        float dz = z - lastZ;
        lastX = x;
        lastZ = z;

        if (grounded) {
            groundedGrace = GROUND_GRACE_UPDATES;
        } else if (groundedGrace > 0) {
            groundedGrace--;
        }
        // Airborne or swimming: stop accumulating but keep what is banked, so a hop mid-stride
        // carries on where it left off.
        if (groundedGrace <= 0 || swimming) {
            return false;
        }
        float travelled = (float) Math.sqrt(dx * dx + dz * dz);
        if (travelled > MAX_TRAVEL_PER_UPDATE) {
            return false;
        }
        sinceStep += travelled;
        if (sinceStep < strideLength) {
            return false;
        }
        sinceStep = 0f;
        return true;
    }

    /** Forgets position and progress; call on spawn or teleport. */
    public void reset() {
        hasLast = false;
        sinceStep = 0f;
        groundedGrace = 0;
    }
}
