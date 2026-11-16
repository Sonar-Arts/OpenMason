package com.openmason.engine.nav;

/**
 * A ground body as it stands this tick ({@code cn_body}), filled by the host before
 * {@link GroundAgent#tick}. Reusable.
 */
public final class AgentBody {
    /** Origin: the top of the legs. */
    public float x, y, z;
    /** Degrees. */
    public float yaw;
    /** Feet = y - legHeight. */
    public float legHeight;
    /** Half the body's x and z extents (the obstacle probe's reach). */
    public float halfWidth, halfLength;
    /** Base speed and the body's own multiplier (status effects); speed = base * route * own. */
    public float moveSpeed, speedMultiplier;
    /** Speed factor applied while in water. */
    public float waterSpeedFactor = 1.0f;
    /** How high a swim stroke carries it. */
    public float swimStrokeReach;
    public boolean onGround, inWater;
}
