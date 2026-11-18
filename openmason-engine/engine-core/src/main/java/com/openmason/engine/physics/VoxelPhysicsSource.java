package com.openmason.engine.physics;

/**
 * Where a {@link PhysicsWorld} gets its cells: the host's world, asked one brick region at a time
 * when the cache needs it. Called on the world's owner thread, inside the physics call that needed
 * the cells; it must not call back into the same {@link PhysicsWorld}.
 *
 * <p>Cell coordinates are ABSOLUTE {@code long}s (OpenMason #142): the region is never relative to
 * the world's frame origin.
 */
@FunctionalInterface
public interface VoxelPhysicsSource {

    /**
     * Writes the shape id and fluid id of every cell of
     * {@code [x0, x0 + sx) x [y0, y0 + sy) x [z0, z0 + sz)} at index
     * {@code ((y - y0) * sz + (z - z0)) * sx + (x - x0)}. Both arrays arrive zeroed (shape 0 = empty,
     * fluid 0 = none) and are at least {@code sx * sy * sz} long.
     *
     * @return false when the region is not loaded: the physics call then treats it as the world's
     *         unloaded shape, and nothing is cached
     */
    boolean fill(long x0, long y0, long z0, int sx, int sy, int sz, short[] shapes, short[] fluids);
}
