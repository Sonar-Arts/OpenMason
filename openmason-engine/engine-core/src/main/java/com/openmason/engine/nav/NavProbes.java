package com.openmason.engine.nav;

import com.openmason.engine.physics.PhysicsWorld;

import static com.openmason.engine.nav.CendaNav.rethrow;

/**
 * Small local questions about one column or one box, answered from the physics cache — for behaviours
 * that need to know about the ground, water or air right here, not where to go. A cell BLOCKS when
 * its shape is in the mask and fills the cell to its top. Unloaded cells read as the physics world's
 * unloaded shape. Owner thread of the physics world.
 */
public final class NavProbes {

    private NavProbes() {
    }

    /**
     * Top of the first blocking cell with a non-blocking cell above it, scanning down from
     * {@code floor(startY) + scanUp} to {@code floor(startY) - scanDown} in the column at (x, z), or
     * {@link Float#NEGATIVE_INFINITY}.
     */
    public static float groundLevel(PhysicsWorld physics, float x, float z, float startY, int scanUp, int scanDown,
                                    int mask) {
        CendaNav.requireAvailable();
        try {
            return (float) CendaNav.PROBE_GROUND_LEVEL.invokeExact(physics.nativeHandle(), x, z, startY, scanUp,
                scanDown, mask);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * One above the highest cell holding a fluid in {@code fluidMask}, from {@code floor(y) + scanUp}
     * down to {@code floor(y) - scanDown} in the column at (x, z), or {@link Float#NEGATIVE_INFINITY}.
     */
    public static float fluidTop(PhysicsWorld physics, float x, float y, float z, int scanUp, int scanDown,
                                 int fluidMask) {
        CendaNav.requireAvailable();
        try {
            return (float) CendaNav.PROBE_FLUID_TOP.invokeExact(physics.nativeHandle(), x, y, z, scanUp, scanDown,
                fluidMask);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Whether no blocking cell touches the cube of half extent {@code radius} around (x, y, z). */
    public static boolean isClear(PhysicsWorld physics, float x, float y, float z, float radius, int mask) {
        CendaNav.requireAvailable();
        try {
            return (int) CendaNav.PROBE_CLEAR.invokeExact(physics.nativeHandle(), x, y, z, radius, mask) != 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * One above the highest blocking cell within {@code range} of {@code floor(fromY)} in the column,
     * or {@link Float#NEGATIVE_INFINITY}.
     */
    public static float columnPeak(PhysicsWorld physics, float x, float z, float fromY, int range, int mask) {
        CendaNav.requireAvailable();
        try {
            return (float) CendaNav.PROBE_COLUMN_PEAK.invokeExact(physics.nativeHandle(), x, z, fromY, range, mask);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }
}
