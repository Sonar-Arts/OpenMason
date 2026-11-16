package com.openmason.engine.nav;

import static com.openmason.engine.nav.CendaNav.rethrow;

/**
 * The steering math a {@link GroundAgent} turns with, for host code that turns a body itself (facing
 * a target while standing, a flyer's heading): one place for the model-yaw-offset rule.
 */
public final class Steer {

    private Steer() {
    }

    /** The yaw (degrees) that points a model along the horizontal direction (dirX, dirZ). */
    public static float yawFor(float dirX, float dirZ, float modelYawOffset) {
        try {
            return (float) CendaNav.STEER_YAW_FOR.invokeExact(dirX, dirZ, modelYawOffset);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code yaw} turned toward {@code targetYaw} along the shortest arc by at most {@code maxStep} degrees. */
    public static float turn(float yaw, float targetYaw, float maxStep) {
        try {
            return (float) CendaNav.STEER_TURN.invokeExact(yaw, targetYaw, maxStep);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Degrees left to turn from {@code yaw} to {@code targetYaw} along the shortest arc (&gt;= 0). */
    public static float yawError(float yaw, float targetYaw) {
        try {
            return (float) CendaNav.STEER_YAW_ERROR.invokeExact(yaw, targetYaw);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }
}
