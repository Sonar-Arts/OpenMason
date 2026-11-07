package com.openmason.engine.rendering.particles;

/**
 * Answers "what in this world cell blocks particles?" for effects with {@link ParticleEffect#collides()} set.
 * A voxel host implements it over its block store. It is called a few times per colliding particle per
 * update (once per axis per sub-step), so it must be cheap and allocation-free. Called on the thread that
 * runs {@link ParticleSystem#update}.
 *
 * <p>A cell blocks the slab {@code [y, y + solidHeight)} of its unit cube: 1 for full blocks, a fraction for
 * partial ones (snow layers, slabs, stair treads — one rule with a host's own collision-height table), 0 for
 * air. Lambdas only implement {@link #isSolid}; override {@link #solidHeight} for partial blocks.
 */
@FunctionalInterface
public interface ParticleCollider {

    /** True when the unit cell with minimum corner {@code (x, y, z)} (world coordinates) blocks particles. */
    boolean isSolid(int x, int y, int z);

    /** Height (0..1) of the blocking slab at the bottom of the cell; default: full cell if solid. */
    default float solidHeight(int x, int y, int z) {
        return isSolid(x, y, z) ? 1f : 0f;
    }

    /** A collider that never blocks. */
    ParticleCollider NONE = (x, y, z) -> false;
}
