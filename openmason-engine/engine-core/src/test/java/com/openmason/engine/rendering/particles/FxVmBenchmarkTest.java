package com.openmason.engine.rendering.particles;

import com.openmason.engine.rendering.particles.graph.FxGraph;
import com.openmason.engine.rendering.particles.graph.FxModules;
import com.openmason.engine.rendering.particles.graph.FxStageKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * CPU cost per particle of the compiled programs vs the old hand-written loop at 50k particles (OM #99 acceptance).
 * Opt in with {@code -Dopenmason.particles.bench=true}; prints a table and checks the batch backend stays within a
 * small factor of the hand-written loop.
 */
class FxVmBenchmarkTest {

    private static final int PARTICLES = 50_000;
    private static final int FRAMES = 300;

    @Test
    void batchProgramsCostWithinASmallFactorOfTheHandWrittenLoop() {
        assumeTrue(Boolean.getBoolean("openmason.particles.bench"), "opt in with -Dopenmason.particles.bench=true");
        ParticleEffect smoke = ParticleLibrary.Presets.SMOKE.toBuilder().name("bench-smoke").lifetime(1000, 1000)
                .lodDistance(0).build();
        ParticleEffect debris = ParticleLibrary.Presets.DEBRIS.toBuilder().name("bench-debris").lifetime(1000, 1000)
                .maxLive(0).build();
        ParticleEffect curl = smoke.toBuilder().name("bench-curl").graph(FxGraph.of(List.of(),
                FxGraph.withModule(FxStageKind.PARTICLE_UPDATE, FxModules.curlNoiseVelocity(2f, 0.4f, 0.2f), "forces")))
                .build();

        double smokeRef = nsPerParticle(smoke, true), smokeVm = nsPerParticle(smoke, false);
        double debrisRef = nsPerParticle(debris, true), debrisVm = nsPerParticle(debris, false);
        double curlVm = nsPerParticle(curl, false);
        double curlHand = handWrittenCurl(smoke);
        System.out.printf(Locale.ROOT, "%n%-34s %10s %10s %8s%n", "per particle per step @ 50k", "hand loop", "batch VM", "factor");
        System.out.printf(Locale.ROOT, "%-34s %8.2fns %8.2fns %7.2fx%n", "smoke (forces, move, spin)", smokeRef, smokeVm, smokeVm / smokeRef);
        System.out.printf(Locale.ROOT, "%-34s %8.2fns %8.2fns %7.2fx%n", "debris (+ collision)", debrisRef, debrisVm, debrisVm / debrisRef);
        System.out.printf(Locale.ROOT, "%-34s %8.2fns %8.2fns %7.2fx%n", "smoke + curl-noise (custom module)", curlHand, curlVm, curlVm / curlHand);
        assertTrue(smokeVm < 4 * smokeRef, "built-in modules within 4x of the hand-written loop");
        assertTrue(debrisVm < 4 * debrisRef, "with collision within 4x");
        assertTrue(curlVm < 4 * curlHand, "a custom module within 4x of the same math hand-written");
    }

    /** The curl module's math written by hand over the columns, plus the hand-written built-in loop. */
    private static double handWrittenCurl(ParticleEffect effect) {
        ParticleSystem s = new ParticleSystem(PARTICLES, 1);
        s.referenceIntegrator = true;
        s.setCollider((x, y, z) -> y < -50);
        s.setWind(0.5f, 0, 0);
        s.burst(effect, 0, 0, 0, PARTICLES);
        float dt = 1f / 240f;
        float[] c = new float[3], scratch = new float[9];
        Runnable frame = () -> {
            for (int i = 0; i < s.liveCount(); i++) {
                float a = s.age[i];
                FxNoise.curl(s.px[i] * 0.4f, s.py[i] * 0.4f + 0.2f * a, s.pz[i] * 0.4f, c, scratch);
                float k = 2f * dt;
                s.vx[i] += c[0] * k;
                s.vy[i] += c[1] * k;
                s.vz[i] += c[2] * k;
            }
            s.update(dt);
        };
        for (int f = 0; f < 200; f++) {
            frame.run();
        }
        long best = Long.MAX_VALUE;
        for (int round = 0; round < 3; round++) {
            long t0 = System.nanoTime();
            for (int f = 0; f < FRAMES; f++) {
                frame.run();
            }
            best = Math.min(best, System.nanoTime() - t0);
        }
        return best / (double) FRAMES / s.liveCount();
    }

    private static double nsPerParticle(ParticleEffect effect, boolean reference) {
        ParticleSystem s = new ParticleSystem(PARTICLES, 1);
        s.referenceIntegrator = reference;
        s.setCollider((x, y, z) -> y < -50);
        s.setWind(0.5f, 0, 0);
        s.burst(effect, 0, 0, 0, PARTICLES);
        float dt = 1f / 240f;
        for (int f = 0; f < 200; f++) {
            s.update(dt); // warm up the JIT on this path
        }
        long best = Long.MAX_VALUE;
        for (int round = 0; round < 3; round++) {
            long t0 = System.nanoTime();
            for (int f = 0; f < FRAMES; f++) {
                s.update(dt);
            }
            best = Math.min(best, System.nanoTime() - t0);
        }
        return best / (double) FRAMES / s.liveCount();
    }
}
