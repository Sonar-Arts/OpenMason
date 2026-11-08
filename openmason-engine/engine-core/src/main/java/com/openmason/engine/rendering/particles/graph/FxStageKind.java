package com.openmason.engine.rendering.particles.graph;

/**
 * The stages of an FX script (Unity VFX Graph contexts / Unreal Niagara stacks). Each holds an ordered stack of
 * {@link FxModule}s; the modules of a stage compile into one fused program.
 */
public enum FxStageKind {
    /** Once when an emitter starts. */
    EMITTER_SPAWN,
    /** Every frame per emitter: computes the emitter's {@code spawnRate}. */
    EMITTER_UPDATE,
    /** Once per new particle, after the built-in {@code initialize} module. */
    PARTICLE_SPAWN,
    /** Every frame per live particle, after the built-in {@code lifetime} module. */
    PARTICLE_UPDATE,
    /** Event handlers (sub-emitters and FX system handlers). */
    EVENT,
    /** How particles are drawn (sprite, orientation, blending). */
    RENDER
}
