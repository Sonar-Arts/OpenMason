package com.openmason.engine.voxel.mms.mmsRegion;

import com.openmason.engine.cearl.CearlKernel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the CEARL-authored cull kernel's compiled shape — everything the
 * dispatcher and the GPU contract depend on, verifiable without a GL context.
 * The std430 layouts must match {@code MmsChunkRegion}'s buffer strides
 * (48-byte metadata, 20-byte tightly packed indirect commands). The plane
 * math itself is pinned against its CPU twin in {@link MmsRegionCullTest}.
 */
class MmsGpuCullerKernelTest {

    @Test
    void kernelCompilesWithTheDispatchContract() {
        CearlKernel kernel = MmsGpuCuller.compileKernel();
        assertEquals("cull_commands", kernel.name());
        assertEquals(64, kernel.localSize());

        // Buffer takes in declaration order: metadata then commands.
        assertEquals(2, kernel.buffers().size());
        assertEquals("meshes", kernel.buffers().getFirst().name());
        assertEquals(0, kernel.buffers().getFirst().binding());
        assertEquals("cmds", kernel.buffers().get(1).name());
        assertEquals(1, kernel.buffers().get(1).binding());

        // Uniforms: six render-space frustum planes, the region's camera-relative origin, the live count.
        assertEquals(3, kernel.uniforms().size());
        assertEquals("planes", kernel.uniforms().getFirst().name());
        assertEquals(MmsGpuCuller.PLANES, kernel.uniforms().getFirst().arraySize());
        assertEquals(6, MmsGpuCuller.PLANES, "the keep-box planes are gone (enable bit instead)");
        assertEquals("origin", kernel.uniforms().get(1).name());
        assertEquals("vec4", kernel.uniforms().get(1).glslType());
        assertEquals("mesh_count", kernel.uniforms().get(2).name());
        assertEquals("uint", kernel.uniforms().get(2).glslType());
    }

    @Test
    void generatedGlslCarriesTheGpuContract() {
        String glsl = MmsGpuCuller.compileKernel().glsl();
        assertTrue(glsl.contains("layout(local_size_x = 64"), glsl);
        assertTrue(glsl.contains(
            "layout(std430, binding = 0) readonly buffer cearl_meshes { MeshMeta meshes[]; };"),
            glsl);
        assertTrue(glsl.contains(
            "layout(std430, binding = 1) writeonly buffer cearl_cmds { DrawCmd cmds[]; };"),
            glsl);
        assertTrue(glsl.contains("uniform vec4 planes[6];"), glsl);
        assertTrue(glsl.contains("uniform vec4 origin;"), glsl);
        assertTrue(glsl.contains("uniform uint mesh_count;"), glsl);
        // The five tightly packed uints of DrawElementsIndirectCommand, in order.
        int a = glsl.indexOf("uint index_count;");
        int b = glsl.indexOf("uint instance_count;");
        int c = glsl.indexOf("uint first_index;");
        int d = glsl.indexOf("uint base_vertex;");
        int e = glsl.indexOf("uint base_instance;");
        assertTrue(a >= 0 && a < b && b < c && c < d && d < e, glsl);
        // pick() lowers to the branchless ternary; the enable bit is draw.w bit 0.
        assertTrue(glsl.contains("((pl.x > 0.0) ? m.maxb.x : m.minb.x)"), glsl);
        assertTrue(glsl.contains("m.draw.w & "), glsl);
        assertTrue(glsl.contains("origin.xyz"), glsl);
    }

    @Test
    void hizKernelsCompileWithTheirDispatchContract() {
        CearlKernel early = MmsGpuCuller.compileKernel("cull_early");
        assertEquals(64, early.localSize());
        assertEquals(java.util.List.of("meshes", "cmds", "vis", "stats"),
            early.buffers().stream().map(CearlKernel.BufferBinding::name).toList());
        assertEquals(java.util.List.of("planes", "origin", "mesh_count", "history", "stats_base"),
            early.uniforms().stream().map(CearlKernel.UniformBinding::name).toList());

        CearlKernel late = MmsGpuCuller.compileKernel("cull_late");
        assertEquals(java.util.List.of("meshes", "cmds", "vis", "pyramid", "stats"),
            late.buffers().stream().map(CearlKernel.BufferBinding::name).toList());
        assertEquals(java.util.List.of("planes", "view_proj", "origin", "mesh_count", "early_rule", "use_pyramid",
                "screen_w", "screen_h", "levels", "stats_base"),
            late.uniforms().stream().map(CearlKernel.UniformBinding::name).toList());
        assertEquals(4, late.uniforms().get(1).arraySize(), "view_proj = four vec4 columns");
        String glsl = late.glsl();
        assertTrue(glsl.contains("readonly buffer cearl_pyramid { float pyramid[]; };"), glsl);
        assertTrue(glsl.contains("buffer cearl_vis { uint vis[]; };"), glsl);

        CearlKernel reduce = MmsGpuCuller.compileKernel("hiz_reduce");
        assertEquals(java.util.List.of("pyramid"),
            reduce.buffers().stream().map(CearlKernel.BufferBinding::name).toList());
        assertTrue(reduce.glsl().contains("max(max("), reduce.glsl());
    }

    @Test
    void frustumKernelIsUnchangedByTheHizAdditions() {
        // The frustum-only kernel (shadow / torch passes, CPU twin parity) keeps its exact signature.
        CearlKernel kernel = MmsGpuCuller.compileKernel();
        assertEquals(2, kernel.buffers().size());
        assertEquals(3, kernel.uniforms().size());
    }
}
