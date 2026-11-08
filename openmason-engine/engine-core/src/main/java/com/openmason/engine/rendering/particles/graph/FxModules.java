package com.openmason.engine.rendering.particles.graph;

/** Ready-made custom modules (graphs of operators, not built-ins): drop them into a stage stack. */
public final class FxModules {

    private FxModules() {
    }

    /**
     * Curl-noise velocity: adds a divergence-free swirl to the velocity, sampled at the particle's position scaled
     * by {@code frequency} and scrolled by {@code drift} blocks/s along Y, weighted by {@code strength} (blocks/s²).
     * Put it before {@code forces} in PARTICLE_UPDATE so drag damps it like any other force.
     */
    public static FxModule curlNoiseVelocity(float strength, float frequency, float drift) {
        return FxModule.custom("curl noise velocity",
                FxNode.read("p", "position"),
                FxNode.read("age", "age"),
                FxNode.constant("freq", frequency),
                FxNode.constant("drift", 0f, drift, 0f),
                FxNode.op("scroll", FxOp.MUL, "drift", "age"),
                FxNode.op("ps", FxOp.MUL, "p", "freq"),
                FxNode.op("sample", FxOp.ADD, "ps", "scroll"),
                FxNode.op("curl", FxOp.CURL, "sample"),
                FxNode.constant("k", strength),
                FxNode.op("dt", FxOp.DT),
                FxNode.op("kdt", FxOp.MUL, "k", "dt"),
                FxNode.op("dv", FxOp.MUL, "curl", "kdt"),
                FxNode.read("v", "velocity"),
                FxNode.op("v2", FxOp.ADD, "v", "dv"),
                FxNode.write("out", "velocity", "v2"));
    }

    /**
     * Pulls particles toward a point {@code (x, y, z)} relative to the anchor with {@code strength} blocks/s²
     * (vortex cores, black holes, collectables flying to the player).
     */
    public static FxModule attractor(float x, float y, float z, float strength) {
        return FxModule.custom("attractor",
                FxNode.read("p", "position"),
                FxNode.constant("c", x, y, z),
                FxNode.op("d", FxOp.SUB, "c", "p"),
                FxNode.op("dir", FxOp.NORMALIZE, "d"),
                FxNode.constant("k", strength),
                FxNode.op("dt", FxOp.DT),
                FxNode.op("kdt", FxOp.MUL, "k", "dt"),
                FxNode.op("dv", FxOp.MUL, "dir", "kdt"),
                FxNode.read("v", "velocity"),
                FxNode.op("v2", FxOp.ADD, "v", "dv"),
                FxNode.write("out", "velocity", "v2"));
    }

    /** EMITTER_UPDATE: spawn rate pulsing as {@code rate × (0.5 + 0.5 sin(2π · hz · emitterAge))}. */
    public static FxModule pulsingRate(float hz) {
        return FxModule.custom("pulsing rate",
                FxNode.read("age", "emitterAge"),
                FxNode.constant("w", (float) (2 * Math.PI) * hz),
                FxNode.op("phase", FxOp.MUL, "age", "w"),
                FxNode.op("s", FxOp.SIN, "phase"),
                FxNode.constant("half", 0.5f),
                FxNode.op("hs", FxOp.MUL, "s", "half"),
                FxNode.op("m", FxOp.ADD, "hs", "half"),
                FxNode.read("rate", "spawnRate"),
                FxNode.op("r", FxOp.MUL, "rate", "m"),
                FxNode.write("out", "spawnRate", "r"));
    }
}
