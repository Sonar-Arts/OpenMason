package com.openmason.engine.physics;

/** A fluid measurement ({@code cp_fluid_sample}), filled by {@link PhysicsWorld}. Mutable, reusable. */
public final class FluidSample {

    /** 0..1 of the body height under the surface. */
    public float submersion;
    /** Frame y of the surface measured; NaN when dry. */
    public double surfaceY = Double.NaN;
    /** Mean flow force over the samples that were in fluid. */
    public float flowX, flowY, flowZ;
    /** How many of the 15 flow samples were in fluid. */
    public int flowSamples;
}
