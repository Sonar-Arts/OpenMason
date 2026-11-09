package com.openmason.engine.rendering.particles;

import com.openmason.engine.cearl.CearlCompiler;
import com.openmason.engine.cearl.CearlProgram;
import com.openmason.engine.rendering.particles.graph.FxAttribute;
import com.openmason.engine.rendering.particles.graph.FxGraph;
import com.openmason.engine.rendering.particles.graph.FxModule;
import com.openmason.engine.rendering.particles.graph.FxModules;
import com.openmason.engine.rendering.particles.graph.FxNode;
import com.openmason.engine.rendering.particles.graph.FxOp;
import com.openmason.engine.rendering.particles.graph.FxStage;
import com.openmason.engine.rendering.particles.graph.FxStageKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The GPU backend's code side (OM #100), GL-free: what the generator emits for every effect the GPU can run compiles
 * through CEARL into the expected kernels, effects the GPU cannot run are refused, and {@code simulation} travels
 * through the effect JSON. (The driver's view of the GLSL and the pixels are {@code ParticleGpuGlTest}.)
 */
class ParticleGpuCodegenTest {

    private static final Map<String, Long> ENV = Map.of("vram", 0L);

    private static ParticleSystem gpuSystem() {
        ParticleSystem s = new ParticleSystem(1024);
        s.enableGpu();
        return s;
    }

    private static ParticleEffect gpu(ParticleEffect e) {
        return e.toBuilder().simulation(ParticleEffect.Simulation.GPU).build();
    }

    private static CearlProgram compile(CompiledEffect c) {
        return CearlCompiler.compile(ParticleGpuCodegen.source(c), c.source.name() + ".CEARL", ENV);
    }

    @Test
    void everyPresetCompilesForTheGpuSubEmittersIncluded() {
        ParticleSystem s = gpuSystem();
        for (ParticleEffect p : ParticleLibrary.Presets.ALL) {
            CompiledEffect c = s.compile(gpu(p));
            assertTrue(c.gpu, p.name());
            for (CompiledEffect t : c.subTarget) {
                assertTrue(t.gpu, p.name() + ": its sub-emitter target runs on the GPU too");
            }
            CearlProgram program = compile(c);
            assertEquals(Set.of("reset", "prep_sim", "simulate", "spawn", "prep_sub", "subspawn", "prep_pack", "pack",
                    "prep_draw"), program.kernels().keySet(), p.name());
            if (!c.source.subEmitters().isEmpty()) {
                assertTrue(ParticleGpuCodegen.source(c).contains("sub0[si"), p.name() + " appends GPU children");
            }
        }
        CearlProgram merged = CearlCompiler.compile(ParticleGpuCodegen.mergedSource(), "merged.CEARL", ENV);
        assertEquals(Set.of("merged_begin", "cpu_keys", "sort_pad", "sort_local", "sort_global", "gather",
                "merged_draw"), merged.kernels().keySet());
    }

    @Test
    void theLivenessLedgerWaitsForTheWholeSubEmitterChain() {
        ParticleSystem s = gpuSystem();
        CompiledEffect firework = s.compile(gpu(ParticleLibrary.Presets.FIREWORK));
        float own = ParticleLibrary.Presets.FIREWORK.lifetime().max();
        assertTrue(firework.gpuChainLife > own, "rocket + the longest child: " + firework.gpuChainLife);
    }

    @Test
    void customGraphModulesTranslate() {
        FxModule init = FxModule.custom("init heat",
                FxNode.op("r", FxOp.RANDOM),
                FxNode.constant("half", 0.5f),
                FxNode.op("hr", FxOp.MUL, "r", "half"),
                FxNode.op("h", FxOp.ADD, "hr", "half"),
                FxNode.write("w", "heat", "h"));
        FxModule cool = FxModule.custom("cool",
                FxNode.read("h", "heat"),
                FxNode.op("dt", FxOp.DT),
                FxNode.constant("rate", 0.5f),
                FxNode.op("drop", FxOp.MUL, "rate", "dt"),
                FxNode.op("h2", FxOp.SUB, "h", "drop"),
                FxNode.write("w", "heat", "h2"),
                FxNode.write("size", "startSize", "h2"));
        FxGraph graph = FxGraph.of(List.of(FxAttribute.scalar("heat", 0f)),
                FxStage.of(FxStageKind.PARTICLE_SPAWN, FxModule.builtin("initialize"), init),
                FxGraph.withModule(FxStageKind.PARTICLE_UPDATE, cool, null));
        ParticleEffect hot = gpu(ParticleEffect.builder("hot").rate(0).graph(graph).build());
        FxGraph swirl = FxGraph.of(List.of(),
                FxGraph.withModule(FxStageKind.PARTICLE_UPDATE, FxModules.curlNoiseVelocity(6f, 0.7f, 0.3f), "forces"),
                FxGraph.withModule(FxStageKind.EMITTER_UPDATE, FxModules.pulsingRate(2f), null));
        ParticleEffect curl = gpu(ParticleEffect.builder("curl").rate(50).graph(swirl).collides(0.3f, 0.6f).build());

        ParticleSystem s = gpuSystem();
        for (ParticleEffect e : List.of(hot, curl)) {
            CompiledEffect c = s.compile(e);
            String src = ParticleGpuCodegen.source(c);
            compile(c);
            if (e == hot) {
                assertTrue(src.contains("c0: f32"), "the custom attribute is a particle field");
                assertTrue(src.contains("PARTICLE_SPAWN program"), "the spawn program runs on the GPU");
                assertTrue(src.contains("u01(prng)"), "graph RANDOM draws from the particle's stream");
            } else {
                assertTrue(src.contains("curl3("), "curl noise is ported");
                assertTrue(src.contains("volume["), "a colliding effect collides with the voxel volume");
                assertTrue(src.contains("heights["), "or lands on the height field");
            }
        }
        assertNotNull(s.compile(curl).emitterProgram, "the emitter stage stays on the CPU (spawn rate)");
    }

    @Test
    void floatConstantsAreCearlLiterals() {
        assertEquals("0.5", ParticleGpuCodegen.lit(0.5f));
        assertEquals("(0.0 - 20.0)", ParticleGpuCodegen.lit(-20f));
        assertEquals("0.0001", ParticleGpuCodegen.lit(1e-4f));
        assertEquals("100000000.0", ParticleGpuCodegen.lit(1e8f));
        assertFalse(ParticleGpuCodegen.lit(1.2345e-7f).contains("E"));
    }

    @Test
    void gpuPoolsAreSizedFromMaxLive() {
        ParticleSystem s = gpuSystem();
        assertEquals(CompiledEffect.DEFAULT_GPU_CAPACITY, s.compile(gpu(ParticleEffect.builder("a").build())).gpuCapacity);
        assertEquals(1 << 20, s.compile(gpu(ParticleEffect.builder("b").maxLive(1_000_000).build())).gpuCapacity);
        assertEquals(64, s.compile(gpu(ParticleEffect.builder("c").maxLive(3).build())).gpuCapacity);
    }

    @Test
    void simulationTravelsThroughEffectJson() throws java.io.IOException {
        ParticleEffect e = gpu(ParticleEffect.builder("dense").rate(1000).maxLive(200_000).build());
        String json = ParticleLibrary.toJsonText(e);
        assertTrue(json.contains("\"simulation\" : \"gpu\""), json);
        ParticleLibrary lib = new ParticleLibrary();
        lib.load(new java.io.ByteArrayInputStream(("{\"format\":\"openmason.particles\",\"version\":1,\"effects\":["
                + json + "]}").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(ParticleEffect.Simulation.GPU, lib.get("dense").simulation());
        assertFalse(ParticleLibrary.toJsonText(ParticleEffect.builder("plain").build()).contains("simulation"),
                "cpu is the default and is not written (existing files stay byte-identical)");
    }
}
