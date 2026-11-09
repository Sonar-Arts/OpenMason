package com.openmason.engine.rendering.particles;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The GL-free side of GPU simulation (OM #100): which effects divert to the GPU, the spawn requests they leave, the
 * liveness ledger that finishes FX instances without per-particle deaths, and the event inbox.
 */
class ParticleGpuQueueTest {

    private static final float DT = 1f / 60f;

    private static ParticleEffect gpu(String name, float life) {
        return ParticleEffect.builder(name).rate(0).lifetime(life, life).simulation(ParticleEffect.Simulation.GPU)
                .build();
    }

    @Test
    void withoutEnableGpuAGpuEffectRunsOnTheCpu() {
        ParticleSystem s = new ParticleSystem(1024);
        assertEquals(10, s.burst(gpu("g", 1f), 0, 0, 0, 10));
        assertEquals(10, s.liveCount(), "CPU fallback: the particles are in the CPU pool");
        assertFalse(s.compile(gpu("g2", 1f)).gpu);
    }

    @Test
    void enableGpuLaterSwitchesGpuEffectsAlreadyCompiled() {
        ParticleSystem s = new ParticleSystem(1024);
        CompiledEffect g = s.compile(gpu("g", 1f));
        CompiledEffect c = s.compile(ParticleEffect.builder("cpu").build());
        assertFalse(g.gpu, "no GL context here: CPU");
        s.enableGpu();
        assertTrue(g.gpu);
        assertFalse(c.gpu, "CPU effects stay on the CPU");
    }

    @Test
    void falling_backReplaysPendingSpawnsOnTheCpuAndCountsThemOnce() {
        ParticleSystem s = new ParticleSystem(1024);
        s.enableGpu();
        FxWorld fx = new FxWorld(s);
        FxInstance inst = fx.play(FxSystem.of(gpu("g", 2f), 7), 0, 0, 0);
        fx.update(DT);
        assertEquals(7, inst.liveParticles());
        assertEquals(0, s.liveCount());
        s.disableGpu();
        assertFalse(s.gpuEnabled());
        assertEquals(7, s.liveCount(), "the queued spawn now lives on the CPU");
        assertEquals(7, inst.liveParticles(), "counted once");
        inst.release();
        fx.close();
    }

    @Test
    void aSystemNoRendererStepsFallsBackToTheCpu() {
        ParticleSystem s = new ParticleSystem(1024);
        s.enableGpu();
        ParticleEffect e = gpu("g", 10f);
        s.burst(e, 0, 0, 0, 4);
        for (int k = 0; k <= ParticleSystem.GPU_IDLE_LIMIT; k++) {
            s.update(DT);
        }
        assertFalse(s.gpuEnabled(), "nobody simulated the GPU effects");
        assertEquals(4, s.liveCount(), "the spawns that were waiting run on the CPU");
    }

    @Test
    void ownersRetireAsSoonAsTheGpuCountsNoLiveParticle() {
        ParticleSystem s = new ParticleSystem(1024);
        s.enableGpu();
        FxWorld fx = new FxWorld(s);
        FxInstance inst = fx.play(FxSystem.of(gpu("g", 30f), 3), 0, 0, 0);
        fx.update(DT);
        ParticleGpuQueue q = s.gpuQueue();
        int owner = q.owner[0];
        java.nio.ByteBuffer counts = java.nio.ByteBuffer.allocate(4 * 64).order(java.nio.ByteOrder.nativeOrder());
        q.observeOwnerCounts(counts, 0, 64, 5);
        fx.update(DT);
        assertTrue(inst.isAlive(), "a readback from before the upload proves nothing");
        q.markUploaded(owner, 3);
        q.observeOwnerCounts(counts, 0, 64, 5);
        fx.update(DT);
        assertFalse(inst.isAlive(), "a later readback counted none: retired without waiting 30 s");
        inst.release();
        fx.close();
    }

    @Test
    void gpuSpawnsBecomeRequestsAndCpuEffectsStayOnTheCpu() {
        ParticleSystem s = new ParticleSystem(1024);
        s.enableGpu();
        s.setAnchor(100, 0, 0);
        assertEquals(10, s.burst(gpu("g", 1f), 105, 2, -3, 10));
        assertEquals(5, s.burst(ParticleEffect.builder("cpu").rate(0).build(), 0, 0, 0, 5));
        assertEquals(5, s.liveCount(), "only the CPU effect's particles are CPU particles");
        ParticleGpuQueue q = s.gpuQueue();
        assertEquals(1, q.requests);
        assertEquals(10, q.count[0]);
        assertEquals(105, q.x[0], 1e-9, "requests carry world positions (the anchor may move before upload)");
        assertEquals(-3, q.z[0], 1e-9);
        s.clearParticles();
        assertEquals(0, q.requests);
        assertTrue(q.clearRequested);
    }

    @Test
    void fxInstancesCountGpuParticlesAndRetireThemAfterTheirLifetime() {
        ParticleSystem s = new ParticleSystem(1024);
        s.enableGpu();
        FxWorld fx = new FxWorld(s);
        FxInstance inst = fx.play(FxSystem.of(gpu("g", 0.5f), 12), 0, 0, 0);
        fx.update(DT);
        assertEquals(12, inst.liveParticles());
        assertEquals(12, s.gpuHeldParticles());
        assertTrue(inst.isAlive());
        float waited = DT;
        while (inst.isAlive() && waited < 5f) {
            fx.update(DT);
            waited += DT;
        }
        assertFalse(inst.isAlive());
        assertEquals(0.5 + ParticleGpuQueue.READBACK_LATENCY, waited, 0.1,
                "retired once the longest lifetime plus the readback latency passed");
        assertEquals(0, s.gpuHeldParticles());
        inst.release();
        fx.close();
    }

    @Test
    void readBackEventsRouteHandlersWithoutCountingParticles() {
        ParticleSystem s = new ParticleSystem(1024);
        s.enableGpu();
        FxWorld fx = new FxWorld(s);
        List<String> events = new ArrayList<>();
        fx.setHost(new FxHost() {
            @Override
            public void event(FxInstance instance, String name, double x, double y, double z) {
                events.add(name);
            }
        });
        FxSystem sys = FxSystem.builder("pops")
                .emitter(FxEmitter.builder("e", gpu("pop", 1f)).burst(3).duration(0).build())
                .on(FxEventHandler.forward(FxEventHandler.Trigger.DEATH, "e", "popped")).build();
        FxInstance inst = fx.play(sys, 0, 0, 0);
        fx.update(DT);
        int owner = s.gpuQueue().owner[0];
        assertNotEquals(0, owner);
        s.gpuQueue().post(owner, ParticleGpuQueue.EVENT_DEATH, 0, 1, 0, 0, 0, 0);
        fx.update(DT);
        assertEquals(List.of("popped"), events);
        assertEquals(3, inst.liveParticles(), "events do not count particles: the ledger does");
        inst.release();
        fx.close();
    }

    @Test
    void aFullRequestTableDropsSpawns() {
        ParticleSystem s = new ParticleSystem(1024);
        s.enableGpu();
        ParticleEffect e = gpu("g", 1f);
        for (int k = 0; k < ParticleGpuQueue.REQUEST_CAPACITY; k++) {
            s.burst(e, 0, 0, 0, 1);
        }
        assertEquals(0, s.burst(e, 0, 0, 0, 1));
        assertEquals(1, s.gpuRequestsDroppedTotal());
    }
}
