package com.openmason.engine.rendering.particles;

/**
 * Where each sprite texture lives in the particle texture array: texture name → array layer. A
 * {@link ParticleSystem} resolves its effects' {@link ParticleEffect#textures} through this once (at compile time
 * and whenever the layers change), so the per-particle frame code is already a layer index.
 *
 * <p>{@link ParticleTextureArray} (GPU) and {@link ParticleSpriteLayout} (its GL-free packing) implement it; GL-free
 * tests can pass a lambda.
 */
@FunctionalInterface
public interface ParticleSpriteLayers {

    /** No textures: every textured effect shows the missing-texture checker. */
    ParticleSpriteLayers NONE = name -> -1;

    /** The array layer holding {@code name}, or -1 when it is not packed (missing image, not loaded yet). */
    int layerOf(String name);
}
