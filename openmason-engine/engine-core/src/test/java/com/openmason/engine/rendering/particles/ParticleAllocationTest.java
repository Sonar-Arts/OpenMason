package com.openmason.engine.rendering.particles;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins the zero-allocation contract: once warm, a frame of emitters + bursts + simulation + cull/sort/pack
 * allocates nothing on the Java heap, so particles never feed the GC no matter how many are alive.
 */
class ParticleAllocationTest {

    static final Method ALLOCATED = findMeter();

    @Test
    void steadyStateFramesAllocateNothing() {
        assumeTrue(ALLOCATED != null, "JVM has no per-thread allocation meter");
        ParticleSystem s = new ParticleSystem(30_000);
        s.setCollider((x, y, z) -> y < -4);
        s.setWind(1.5f, 0, 0.5f);
        for (ParticleEffect e : ParticleLibrary.Presets.ALL) {
            s.createEmitter(e, 0, 0, -8).setRateScale(8).setTint(0xFFE0C0FF);
        }
        ParticleEmitter mover = s.createEmitter(ParticleLibrary.Presets.FIRE, 0, 0, -6);
        ParticleBatchBuilder builder = new ParticleBatchBuilder();
        ParticleFrame frame = ParticleBatchBuilderTest.frame();
        ByteBuffer out = MemoryUtil.memAlloc(30_000 * ParticleVertexFormat.STRIDE);
        try {
            // Warm up: fill the pool, size scratch, let the JIT settle.
            for (int i = 0; i < 400; i++) {
                frame(s, builder, frame, out, mover, i);
            }
            assertTrue(s.liveCount() > 3_000, "a real load: " + s.liveCount());
            long bytes = steadyBytes(i -> frame(s, builder, frame, out, mover, i));
            assertEquals(0, bytes, "bytes allocated over 1200 steady-state frames");
        } finally {
            MemoryUtil.memFree(out);
        }
    }

    /** The FX layer keeps the contract: playing, firing, parameters, events, lights and sounds allocate nothing. */
    @Test
    void steadyStateFxFramesAllocateNothing() {
        assumeTrue(ALLOCATED != null, "JVM has no per-thread allocation meter");
        ParticleSystem s = new ParticleSystem(40_000);
        s.setCollider((x, y, z) -> y < -4);
        FxWorld fx = new FxWorld(s);
        long[] calls = new long[1];
        fx.setHost(new FxHost() {
            @Override
            public void event(FxInstance instance, String name, double x, double y, double z) {
                calls[0]++;
            }

            @Override
            public void sound(FxInstance instance, String sound, double x, double y, double z, float volume) {
                calls[0]++;
            }
        });
        float[] light = new float[1];
        FxLightSink sink = (inst, x, y, z, r, g, b, intensity, radius) -> light[0] += intensity;
        FxInstance campfire = fx.play(FxPresets.CAMPFIRE, 0, -4, -8);
        for (FxSystem sys : FxPresets.ALL) {
            fx.register(sys);
        }
        ParticleBatchBuilder builder = new ParticleBatchBuilder();
        ParticleFrame frame = ParticleBatchBuilderTest.frame();
        ByteBuffer out = MemoryUtil.memAlloc(40_000 * ParticleVertexFormat.STRIDE);
        try {
            for (int i = 0; i < 1200; i++) {
                fxFrame(fx, campfire, builder, frame, out, sink, i);
            }
            assertTrue(s.liveCount() > 500, "a real load: " + s.liveCount());
            assertTrue(calls[0] > 0 && light[0] > 0f, "host callbacks and lights ran");
            int pooled = fx.pooledInstances();
            long bytes = steadyBytes(i -> fxFrame(fx, campfire, builder, frame, out, sink, i));
            assertEquals(pooled, fx.pooledInstances(), "the instance pool was warm (" + bytes + " bytes)");
            assertEquals(0, bytes, "bytes allocated over 1200 steady-state FX frames");
        } finally {
            MemoryUtil.memFree(out);
        }
    }

    private static void fxFrame(FxWorld fx, FxInstance campfire, ParticleBatchBuilder b, ParticleFrame f,
                                ByteBuffer out, FxLightSink sink, int i) {
        campfire.move(Math.sin(i * 0.05) * 3, -4, -8).setParam("intensity", 0.5f + 0.5f * (float) Math.abs(Math.sin(i * 0.01)));
        if (i % 30 == 0) {
            FxInstance boom = fx.play(FxPresets.EXPLOSION, 2, -3, -6);
            boom.setParamColor("debrisTint", 0xA08060FF).release();
            fx.fire(FxPresets.FIREWORK, -2, -4, -7);
        }
        if (i % 45 == 0) {
            campfire.raise("none");
        }
        fx.update(1f / 60f);
        fx.forEachLight(sink);
        b.prepare(fx.system(), f);
        b.pack(MemoryUtil.memAddress(out));
    }

    private static void frame(ParticleSystem s, ParticleBatchBuilder b, ParticleFrame f, ByteBuffer out,
                              ParticleEmitter mover, int i) {
        mover.setPosition(Math.sin(i * 0.05) * 3, 0, -6);
        if (i % 20 == 0) {
            s.burst(ParticleLibrary.Presets.SPARKS, 0, 1, -5, 0, 1, 0.2f, 60, 0xFFFFFFFF);
            s.burst(ParticleLibrary.Presets.DEBRIS, 1, 0, -5, 40);
        }
        s.update(1f / 60f);
        b.prepare(s, f);
        b.pack(MemoryUtil.memAddress(out));
    }

    /**
     * Bytes allocated by 1200 frames, measured over up to five consecutive windows and returning the first that
     * allocates nothing (else the smallest). A real per-frame allocation shows in every window; a one-off JIT
     * recompilation (deoptimization re-materialises scalar-replaced objects, e.g. after another test trained the
     * shared code differently) settles after the first. The meter's own cost is measured and subtracted.
     */
    static long steadyBytes(java.util.function.IntConsumer frames) {
        long m0 = allocated();
        long meterCost = allocated() - m0;
        long best = Long.MAX_VALUE;
        int i = 100_000;
        for (int window = 0; window < 5 && best != 0; window++) {
            long before = allocated();
            for (int k = 0; k < 1200; k++) {
                frames.accept(i++);
            }
            best = Math.min(best, allocated() - before - meterCost);
        }
        return best;
    }

    private static long allocated() {
        try {
            return (long) ALLOCATED.invoke(ManagementFactory.getThreadMXBean());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** {@code com.sun.management.ThreadMXBean#getCurrentThreadAllocatedBytes}, reflectively (no jdk.management read). */
    private static Method findMeter() {
        try {
            Method m = Class.forName("com.sun.management.ThreadMXBean").getMethod("getCurrentThreadAllocatedBytes");
            m.setAccessible(true);
            long probe = (long) m.invoke(ManagementFactory.getThreadMXBean());
            return probe >= 0 ? m : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
