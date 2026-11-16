package com.openmason.engine.physics;

/** Cache counters of a {@link PhysicsWorld} ({@code cp_stats}), for diagnostics. */
public record PhysicsStats(long fills, long filledCells, long unloadedFills, int bricks, int denseBricks,
                           int dynamicBoxes) {
}
