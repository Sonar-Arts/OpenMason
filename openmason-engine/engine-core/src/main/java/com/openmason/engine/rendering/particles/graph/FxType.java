package com.openmason.engine.rendering.particles.graph;

/** Value types flowing through an FX graph. Vectors are three floats (stored as three lanes by the CPU backend). */
public enum FxType {
    FLOAT(1),
    VEC3(3);

    public final int width;

    FxType(int width) {
        this.width = width;
    }
}
