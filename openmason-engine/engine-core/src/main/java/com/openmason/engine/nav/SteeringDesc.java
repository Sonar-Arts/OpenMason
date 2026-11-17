package com.openmason.engine.nav;

/**
 * How a body steers ({@code cn_steering_desc}): turn rate, hop and stroke rules, and the obstacle
 * probe's geometry. Host tuning, as everything else here.
 *
 * @param rotationSpeed      turn rate, degrees per second
 * @param hopBoostSpeed      horizontal speed mid-hop (&lt;= 0: no boost)
 * @param hopDuration        hop boost safety cap in seconds (it ends on landing)
 * @param jumpCooldown       seconds between jumps
 * @param swimStrokeCooldown seconds between swim strokes
 * @param obstacleLookAhead  the obstacle probe's gap past the body's leading edge
 * @param footProbeLift      the probe's height above the feet
 * @param maxShoreProbeCells how far up a shore is measured from water
 * @param modelYawOffset     degrees added to a travel direction's yaw (how the model was authored)
 * @param collideMask        shape categories the obstacle probe treats as solid
 * @param stepHeight         the body's own auto-step: at a full block or more, a one-block obstacle
 *                           is stepped, never hopped
 */
public record SteeringDesc(
        float rotationSpeed,
        float hopBoostSpeed,
        float hopDuration,
        float jumpCooldown,
        float swimStrokeCooldown,
        float obstacleLookAhead,
        float footProbeLift,
        int maxShoreProbeCells,
        float modelYawOffset,
        int collideMask,
        float stepHeight) {

    /** A body with the usual half-block step. */
    public SteeringDesc(float rotationSpeed, float hopBoostSpeed, float hopDuration, float jumpCooldown,
                        float swimStrokeCooldown, float obstacleLookAhead, float footProbeLift,
                        int maxShoreProbeCells, float modelYawOffset, int collideMask) {
        this(rotationSpeed, hopBoostSpeed, hopDuration, jumpCooldown, swimStrokeCooldown, obstacleLookAhead,
            footProbeLift, maxShoreProbeCells, modelYawOffset, collideMask, 0.5f);
    }

    void write(java.lang.foreign.MemorySegment s) {
        float[] f = {rotationSpeed, hopBoostSpeed, hopDuration, jumpCooldown, swimStrokeCooldown, obstacleLookAhead,
            footProbeLift};
        for (int i = 0; i < f.length; i++) {
            s.set(CendaNav.F32, 4L * i, f[i]);
        }
        s.set(CendaNav.I32, 28, maxShoreProbeCells);
        s.set(CendaNav.F32, 32, modelYawOffset);
        s.set(CendaNav.I32, 36, collideMask);
        s.set(CendaNav.F32, 40, stepHeight);
    }
}
