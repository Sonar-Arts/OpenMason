package com.openmason.engine.rendering.particles;

import com.openmason.engine.rendering.particles.graph.FxAttribute;
import com.openmason.engine.rendering.particles.graph.FxGraph;
import com.openmason.engine.rendering.particles.graph.FxGraphJson;
import com.openmason.engine.rendering.particles.graph.FxModule;
import com.openmason.engine.rendering.particles.graph.FxModules;
import com.openmason.engine.rendering.particles.graph.FxNode;
import com.openmason.engine.rendering.particles.graph.FxOp;
import com.openmason.engine.rendering.particles.graph.FxStage;
import com.openmason.engine.rendering.particles.graph.FxStageKind;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The FX graph IR and its CPU backend (OM #99). */
class FxGraphTest {

    private static final float DT = 1f / 60f;
    /** Floor plus a wall at x ≥ 6 and a half slab at z ≤ −6: every collision path. */
    private static final ParticleCollider WORLD = new ParticleCollider() {
        @Override
        public boolean isSolid(int x, int y, int z) {
            return y < 0 || x >= 6;
        }

        @Override
        public float solidHeight(int x, int y, int z) {
            if (z <= -6 && y == 0) {
                return 0.5f;
            }
            return isSolid(x, y, z) ? 1f : 0f;
        }
    };

    // ------------------------------------------------------------------------------------------------
    // Built-in modules = today's simulation, bit for bit
    // ------------------------------------------------------------------------------------------------

    @Test
    void builtInModulesReproduceTheHandWrittenLoopBitForBit() {
        ParticleSystem ref = scene(true), vm = scene(false);
        for (int f = 0; f < 420; f++) {
            if (f % 25 == 0) {
                for (ParticleSystem s : List.of(ref, vm)) {
                    s.burst(ParticleLibrary.Presets.SPARKS, 1, 2, 0, 0.3f, 1, 0, 80, 0xFFFFFFFF);
                    s.burst(ParticleLibrary.Presets.DEBRIS, -1, 1, -6.5, 30);
                    s.burst(ParticleLibrary.Presets.EXPLOSION, 3, 2, 2, 12);
                    s.burst(ParticleLibrary.Presets.FIREWORK, 0, 0.5, 1, 1);
                    s.burst(ParticleLibrary.Presets.SPLASH, 5, 0.5, 0, 25);
                }
            }
            ref.update(DT);
            vm.update(DT);
            assertEquals(ref.liveCount(), vm.liveCount(), "live at frame " + f);
            assertEquals(ref.spawnedTotal(), vm.spawnedTotal(), "spawned at frame " + f);
            assertEquals(ref.droppedTotal(), vm.droppedTotal(), "dropped at frame " + f);
            if (f % 30 == 29) {
                assertSameState(ref, vm, "frame " + f);
            }
        }
        assertTrue(ref.liveCount() > 2_000, "a busy scene: " + ref.liveCount());
        assertSameState(ref, vm, "end");
    }

    private static ParticleSystem scene(boolean reference) {
        ParticleSystem s = new ParticleSystem(60_000, 4242);
        s.referenceIntegrator = reference;
        s.setCollider(WORLD);
        s.setWind(1.2f, 0, -0.4f);
        s.setViewer(0, 3, 10);
        double x = -4;
        for (ParticleEffect e : ParticleLibrary.Presets.ALL) {
            if (e.rate() > 0f) {
                s.createEmitter(e, x, e == ParticleLibrary.Presets.RAIN || e == ParticleLibrary.Presets.SNOW ? 6 : 0.5, 0)
                        .setTint(0xFFE0C0FF);
                x += 0.7;
            }
        }
        return s;
    }

    private static void assertSameState(ParticleSystem a, ParticleSystem b, String when) {
        int n = a.liveCount();
        assertEquals(n, b.liveCount(), when);
        for (int i = 0; i < n; i++) {
            String w = when + " particle " + i;
            assertEquals(Float.floatToRawIntBits(a.px[i]), Float.floatToRawIntBits(b.px[i]), w + " px");
            assertEquals(Float.floatToRawIntBits(a.py[i]), Float.floatToRawIntBits(b.py[i]), w + " py");
            assertEquals(Float.floatToRawIntBits(a.pz[i]), Float.floatToRawIntBits(b.pz[i]), w + " pz");
            assertEquals(Float.floatToRawIntBits(a.vx[i]), Float.floatToRawIntBits(b.vx[i]), w + " vx");
            assertEquals(Float.floatToRawIntBits(a.vy[i]), Float.floatToRawIntBits(b.vy[i]), w + " vy");
            assertEquals(Float.floatToRawIntBits(a.vz[i]), Float.floatToRawIntBits(b.vz[i]), w + " vz");
            assertEquals(Float.floatToRawIntBits(a.age[i]), Float.floatToRawIntBits(b.age[i]), w + " age");
            assertEquals(Float.floatToRawIntBits(a.rot[i]), Float.floatToRawIntBits(b.rot[i]), w + " rot");
            assertEquals(a.effectId[i], b.effectId[i], w + " effect");
            assertEquals(a.tint[i], b.tint[i], w + " tint");
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Custom modules
    // ------------------------------------------------------------------------------------------------

    private static ParticleEffect.Builder drifting(String name) {
        return ParticleEffect.builder(name).lifetime(5, 5).rate(0).speed(0.5f, 0.5f).direction(0, 1, 0).spread(0)
                .fade(0, 0);
    }

    @Test
    void curlNoiseModuleCompilesAndSwirlsParticles() {
        FxGraph graph = FxGraph.of(List.of(),
                FxGraph.withModule(FxStageKind.PARTICLE_UPDATE, FxModules.curlNoiseVelocity(6f, 0.7f, 0.3f), "forces"));
        ParticleEffect plain = drifting("plain").build();
        ParticleEffect swirl = drifting("swirl").graph(graph).build();
        ParticleSystem s = new ParticleSystem(1000, 5);
        s.burst(plain, 0, 0, 0, 1);
        s.burst(swirl, 0.3, 0.2, 0.1, 200);
        for (int f = 0; f < 120; f++) {
            s.update(DT);
        }
        float maxSide = 0f;
        for (int i = 0; i < s.liveCount(); i++) {
            if (s.effectOf(i) == plain) {
                assertEquals(0.0, s.worldX(i), 1e-6, "no custom module: straight up");
            } else {
                maxSide = Math.max(maxSide, (float) Math.hypot(s.worldX(i) - 0.3, s.worldZ(i) - 0.1));
            }
        }
        assertTrue(maxSide > 0.3f, "curl noise pushed particles sideways: " + maxSide);
        String asm = s.updateProgram(swirl).disassemble();
        assertTrue(asm.contains("curl"), asm);
    }

    @Test
    void curlNoiseIsDivergenceFree() {
        float[] out = new float[3], scratch = new float[9];
        float h = 1e-2f;
        double worst = 0, typical = 0;
        for (int k = 0; k < 50; k++) {
            float x = k * 0.37f, y = k * 0.11f - 2f, z = 1.3f - k * 0.23f;
            FxNoise.curl(x + h, y, z, out, scratch);
            float dxp = out[0];
            FxNoise.curl(x - h, y, z, out, scratch);
            float dxm = out[0];
            FxNoise.curl(x, y + h, z, out, scratch);
            float dyp = out[1];
            FxNoise.curl(x, y - h, z, out, scratch);
            float dym = out[1];
            FxNoise.curl(x, y, z + h, out, scratch);
            float dzp = out[2];
            FxNoise.curl(x, y, z - h, out, scratch);
            float dzm = out[2];
            double div = ((dxp - dxm) + (dyp - dym) + (dzp - dzm)) / (2 * h);
            worst = Math.max(worst, Math.abs(div));
            FxNoise.curl(x, y, z, out, scratch);
            typical += Math.sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2]);
        }
        typical /= 50;
        assertTrue(typical > 0.3, "a real field: " + typical);
        assertTrue(worst < 0.05 * typical, "divergence " + worst + " vs magnitude " + typical);
    }

    @Test
    void customAttributesAreInitialisedAtSpawnAndEvolve() {
        // heat: random 0.5..1 at spawn, cools 0.5/s; size follows heat; "debug" is written but never read.
        FxModule init = FxModule.custom("init heat",
                FxNode.op("r", FxOp.RANDOM),
                FxNode.constant("half", 0.5f),
                FxNode.op("hr", FxOp.MUL, "r", "half"),
                FxNode.op("h", FxOp.ADD, "hr", "half"),
                FxNode.write("w", "heat", "h"),
                FxNode.constant("d", 7f),
                FxNode.write("w2", "debug", "d"));
        FxModule cool = FxModule.custom("cool",
                FxNode.read("h", "heat"),
                FxNode.op("dt", FxOp.DT),
                FxNode.constant("rate", 0.5f),
                FxNode.op("drop", FxOp.MUL, "rate", "dt"),
                FxNode.op("h2", FxOp.SUB, "h", "drop"),
                FxNode.constant("zero", 0f),
                FxNode.op("h3", FxOp.MAX, "h2", "zero"),
                FxNode.write("w", "heat", "h3"),
                FxNode.write("size", "startSize", "h3"),
                FxNode.write("size2", "endSize", "h3"));
        FxGraph graph = FxGraph.of(List.of(FxAttribute.scalar("heat", 0f), FxAttribute.scalar("debug", 0f)),
                FxStage.of(FxStageKind.PARTICLE_SPAWN, FxModule.builtin("initialize"), init),
                FxGraph.withModule(FxStageKind.PARTICLE_UPDATE, cool, null));
        ParticleEffect hot = drifting("hot").graph(graph).build();
        ParticleSystem s = new ParticleSystem(1000, 9);
        s.burst(hot, 0, 0, 0, 100);
        float lo = 9, hi = -9;
        for (int i = 0; i < s.liveCount(); i++) {
            float h = s.customAttribute(i, "heat");
            lo = Math.min(lo, h);
            hi = Math.max(hi, h);
        }
        assertTrue(lo >= 0.5f && hi < 1f && hi - lo > 0.3f, "random 0.5..1 at spawn: " + lo + ".." + hi);
        float first = s.customAttribute(0, "heat");
        for (int f = 0; f < 60; f++) {
            s.update(DT);
        }
        // Same particle (no deaths, so no swaps): one second of cooling.
        assertEquals(first - 0.5f, s.customAttribute(0, "heat"), 1e-4f);
        assertEquals(s.customAttribute(0, "heat"), s.size0[0], 0f, "attributes drive built-ins");
        assertThrows(IllegalArgumentException.class, () -> s.customAttribute(0, "debug"),
                "written but never read: eliminated, never allocated");
    }

    @Test
    void emitterUpdateProgramsDriveTheSpawnRate() {
        FxGraph graph = FxGraph.of(List.of(),
                FxGraph.withModule(FxStageKind.EMITTER_UPDATE, FxModules.pulsingRate(1f), null));
        ParticleEffect pulse = drifting("pulse").rate(400).lifetime(10, 10).graph(graph).build();
        ParticleSystem s = new ParticleSystem(10_000);
        s.createEmitter(pulse, 0, 0, 0);
        long[] perQuarter = new long[4];
        long last = 0;
        for (int q = 0; q < 4; q++) {
            for (int f = 0; f < 15; f++) {
                s.update(DT);
            }
            perQuarter[q] = s.spawnedTotal() - last;
            last = s.spawnedTotal();
        }
        // rate × (0.5 + 0.5 sin 2πt): quarters average 0.82, 0.82, 0.18, 0.18 of 400/s × 0.25 s.
        assertTrue(perQuarter[0] > 60 && perQuarter[1] > 60, java.util.Arrays.toString(perQuarter));
        assertTrue(perQuarter[2] < 40 && perQuarter[3] < 40, java.util.Arrays.toString(perQuarter));
        assertEquals(200, s.spawnedTotal(), 3, "a full period averages half the rate");
    }

    // ------------------------------------------------------------------------------------------------
    // Compiler: folding, hoisting, elimination, round trips, validation
    // ------------------------------------------------------------------------------------------------

    @Test
    void perFrameMathIsHoistedOutOfTheParticleLoop() {
        ParticleSystem s = new ParticleSystem(10);
        FxProgram p = s.updateProgram(ParticleLibrary.Presets.SMOKE);
        String asm = p.disassemble();
        String lanes = asm.substring(asm.indexOf("; lanes"));
        assertFalse(lanes.contains("exp"), "drag factor computed once per frame, not per particle:\n" + asm);
        assertFalse(lanes.contains("wind"), asm);
        assertTrue(asm.substring(0, asm.indexOf("; lanes")).contains("exp"), asm);
        // loads: v 3, p 3, rotation + spin 2; broadcasts 5; v += a·dt then ×drag 6; p += v·dt 6; rot += spin·dt 2;
        // stores: p 3, v 3, rotation 1 = 34, each column loaded and stored at most once.
        long bcasts = lanes.lines().filter(l -> l.trim().startsWith("bcast")).count();
        assertEquals(5, bcasts, "accel xyz, drag factor and dt, each broadcast once (dt shared by modules):\n" + asm);
        assertEquals(34, p.laneInstructions(), "lane instructions:\n" + asm);
        // Registers × FxVm.CHUNK floats must stay L1-sized (20 × 256 × 4 B = 20 KiB).
        assertTrue(p.laneRegisters() <= 20, p.laneRegisters() + " registers");
    }

    @Test
    void constantSubgraphsFoldAtCompileTime() {
        FxModule folded = FxModule.custom("folded",
                FxNode.constant("a", 2f),
                FxNode.constant("b", 3f),
                FxNode.op("ab", FxOp.MUL, "a", "b"),
                FxNode.op("s", FxOp.SIN, "ab"),
                FxNode.read("r", "rotation"),
                FxNode.op("r2", FxOp.ADD, "r", "s"),
                FxNode.write("w", "rotation", "r2"));
        ParticleEffect e = drifting("fold").graph(FxGraph.of(List.of(),
                FxGraph.withModule(FxStageKind.PARTICLE_UPDATE, folded, null))).build();
        String asm = new ParticleSystem(10).updateProgram(e).disassemble();
        assertFalse(asm.contains("sin"), "sin(2 × 3) folded:\n" + asm);
        assertTrue(asm.contains(Float.toString((float) Math.sin(6f))), asm);
    }

    @Test
    void theIrRoundTripsThroughJsonToTheSameProgram() throws IOException {
        FxGraph graph = FxGraph.of(List.of(FxAttribute.vector("swirl", 0, 1, 0), FxAttribute.scalar("heat", 0.5f)),
                FxStage.of(FxStageKind.PARTICLE_SPAWN, FxModule.builtin("initialize"), FxModule.custom("seed",
                        FxNode.op("r", FxOp.RANDOM), FxNode.write("w", "heat", "r"))),
                new FxStage(FxStageKind.PARTICLE_UPDATE, List.of(FxModule.builtin("lifetime"),
                        FxModules.curlNoiseVelocity(2f, 0.5f, 0.1f), FxModule.builtin("forces"),
                        FxModules.attractor(0, 2, 0, 3f), FxModule.builtin("move"), FxModule.custom("curve",
                                FxNode.read("t", "normalizedAge"), FxNode.curve("c", "t", 0f, 1f, 0.5f, 2f, 1f, 0f),
                                FxNode.read("h", "heat"), FxNode.op("m", FxOp.MUL, "c", "h"),
                                FxNode.write("w", "startSize", "m")))),
                FxGraph.withModule(FxStageKind.EMITTER_UPDATE, FxModules.pulsingRate(2f), null));
        String json = FxGraphJson.write(graph);
        FxGraph back = FxGraphJson.read(json);
        assertEquals(graph, back, "the IR is reproduced exactly");
        assertEquals(json, FxGraphJson.write(back), "and writes back byte for byte");

        ParticleEffect a = drifting("a").collides(0.3f, 0.5f).graph(graph).build();
        ParticleEffect b = drifting("a").collides(0.3f, 0.5f).graph(back).build();
        FxCompiler.Result ra = FxCompiler.compile(FxCompiler.Input.of(a)), rb = FxCompiler.compile(FxCompiler.Input.of(b));
        assertEquals(ra.update, rb.update, "same update program");
        assertEquals(ra.spawn, rb.spawn, "same spawn program");
        assertEquals(ra.emitter, rb.emitter, "same emitter program");
        assertArrayEquals(ra.customNames, rb.customNames);
        assertArrayEquals(new String[]{"heat"}, ra.customNames, "'swirl' is never read: no column");

        // Effects carry their graph through the particle library format too.
        ParticleLibrary lib = new ParticleLibrary();
        lib.register(a);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        lib.write(bytes);
        ParticleLibrary loaded = new ParticleLibrary();
        loaded.load(new ByteArrayInputStream(bytes.toByteArray()));
        assertEquals(a, loaded.get("a"));
    }

    @Test
    void graphsAreValidatedWhenTheEffectIsBuilt() {
        assertRejects("unknown attribute", FxModule.custom("m",
                FxNode.read("x", "temperature"), FxNode.write("w", "rotation", "x")));
        assertRejects("writes a VEC3", FxModule.custom("m",
                FxNode.read("p", "position"), FxNode.write("w", "rotation", "p")));
        assertRejects("read-only", FxModule.custom("m",
                FxNode.constant("c", 1f), FxNode.write("w", "normalizedAge", "c")));
        assertRejects("cross takes", FxModule.custom("m",
                FxNode.read("p", "position"), FxNode.constant("c", 1, 2, 3), FxNode.op("d", FxOp.DOT, "p", "c"),
                FxNode.op("bad", FxOp.CROSS, "d", "c"), FxNode.write("w", "velocity", "bad")));
        assertThrows(IllegalArgumentException.class, () -> drifting("x").graph(FxGraph.of(List.of(),
                FxStage.of(FxStageKind.PARTICLE_UPDATE, FxModule.builtin("forces")))).build(), "lifetime required");
        assertThrows(IllegalArgumentException.class, () -> drifting("x").graph(FxGraph.of(List.of(),
                FxStage.of(FxStageKind.EVENT, FxModule.custom("e", FxNode.op("d", FxOp.DT))))).build());
        assertThrows(IllegalArgumentException.class, () -> FxModule.custom("m", FxNode.op("a", FxOp.ADD, "x", "y")),
                "inputs must be earlier nodes");
        assertThrows(IllegalArgumentException.class,
                () -> FxModule.custom("m", new FxNode("t", FxOp.TRAIL, List.of(), List.of(), null)), "intrinsics");
        assertThrows(IOException.class, () -> FxGraphJson.read("{\"format\":\"openmason.fxgraph\",\"version\":2}"));
        assertThrows(IOException.class, () -> FxGraphJson.read("{\"stages\":[{\"stage\":\"particle_update\","
                + "\"modules\":[{\"name\":\"m\",\"nodes\":[{\"id\":\"a\",\"op\":\"teleport\"}]}]}]}"));
    }

    private static void assertRejects(String message, FxModule module) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> drifting("x").graph(
                FxGraph.of(List.of(), FxGraph.withModule(FxStageKind.PARTICLE_UPDATE, module, null))).build());
        assertTrue(e.getMessage().contains(message), e.getMessage());
    }

    @Test
    void customModulesKeepTheZeroAllocationContract() {
        ParticleEffect swirl = drifting("swirl").rate(3000).lifetime(1, 2).graph(FxGraph.of(
                List.of(FxAttribute.scalar("heat", 1f)),
                FxStage.of(FxStageKind.PARTICLE_SPAWN, FxModule.builtin("initialize"), FxModule.custom("seed",
                        FxNode.op("r", FxOp.RANDOM), FxNode.write("w", "heat", "r"))),
                new FxStage(FxStageKind.PARTICLE_UPDATE, List.of(FxModule.builtin("lifetime"),
                        FxModules.curlNoiseVelocity(3f, 0.5f, 0.2f), FxModule.builtin("forces"),
                        FxModule.builtin("move"), FxModule.custom("size", FxNode.read("h", "heat"),
                                FxNode.write("w", "startSize", "h")))),
                FxGraph.withModule(FxStageKind.EMITTER_UPDATE, FxModules.pulsingRate(0.5f), null))).build();
        ParticleSystem s = new ParticleSystem(20_000);
        s.createEmitter(swirl, 0, 0, 0);
        for (int f = 0; f < 600; f++) {
            s.update(DT);
        }
        assertTrue(s.liveCount() > 500);
        org.junit.jupiter.api.Assumptions.assumeTrue(ParticleAllocationTest.ALLOCATED != null, "no allocation meter");
        long bytes = ParticleAllocationTest.steadyBytes(i -> s.update(DT));
        assertEquals(0, bytes, "custom modules allocate nothing per frame");
    }
}
