package com.openmason.engine.rendering.particles;

import java.io.IOException;

/**
 * The host's way of turning a sprite texture name into pixels. Engine code cannot read game or tool resources
 * itself (JPMS: resource lookups are caller-sensitive), so whoever owns the images passes this lambda — a
 * classpath opener, a file next to the effect document, an asset's embedded image.
 */
@FunctionalInterface
public interface ParticleSpriteLoader {

    /**
     * The image named {@code name}, or null when there is none. A null or a thrown exception leaves the name
     * unpacked: its particles show the missing-texture checker and {@link ParticleSpriteLayout#missing()} lists it.
     */
    ParticleImage load(String name) throws IOException;
}
