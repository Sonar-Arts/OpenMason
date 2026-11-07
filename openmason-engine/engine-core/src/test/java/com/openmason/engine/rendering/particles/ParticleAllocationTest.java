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

    private static final Method ALLOCATED = findMeter();

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
            // Reading the meter reflectively allocates (boxing + reflection); measure and subtract it.
            long m0 = allocated();
            long meterCost = allocated() - m0;
            long before = allocated();
            for (int i = 0; i < 1200; i++) {
                frame(s, builder, frame, out, mover, i);
            }
            long bytes = allocated() - before - meterCost;
            assertEquals(0, bytes, "bytes allocated over 1200 steady-state frames");
        } finally {
            MemoryUtil.memFree(out);
        }
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
