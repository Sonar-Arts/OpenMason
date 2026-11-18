package com.openmason.engine.voxel.mms.mmsRegion;

import com.openmason.engine.rendering.RenderOrigin;
import com.openmason.engine.voxel.mms.mmsCore.MmsSharedQuadIndexBuffer;
import com.openmason.engine.voxel.mms.mmsCore.MmsStagingRing;
import com.openmason.engine.voxel.mms.mmsCore.MmsVertexFormat;
import com.openmason.engine.vram.VramPlans;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43C.*;

/**
 * Cubic regions on a real GL context (opt in with {@code -Dopenmason.mms.gl=true}):
 * native-memory uploads land byte-exact in the arenas (QUAD16 and COMPACT20 + u32
 * indices), the region origin attribute is the int64 corner made camera-relative,
 * and the GPU cull kernel agrees command-for-command with its CPU twin, enable
 * bit included, with the camera at ±2^40.
 */
class MmsChunkRegionGlTest {

    private long window;
    private final List<MmsChunkRegion> regions = new ArrayList<>();

    @BeforeEach
    void createContext() {
        assumeTrue(Boolean.getBoolean("openmason.mms.gl"), "opt in with -Dopenmason.mms.gl=true");
        assertTrue(glfwInit(), "GLFW display unavailable");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        window = glfwCreateWindow(16, 16, "MMS region test", 0, 0);
        assertNotEquals(0, window);
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        RenderOrigin.reset();
    }

    @AfterEach
    void destroyContext() {
        if (window == 0) {
            return;
        }
        for (MmsChunkRegion r : regions) {
            r.delete();
        }
        MmsStagingRing ring = MmsStagingRing.sharedInstance();
        if (ring != null) {
            ring.close();
        }
        MmsSharedQuadIndexBuffer.invalidate();
        RenderOrigin.reset();
        int error = glGetError();
        GL.setCapabilities(null);
        glfwDestroyWindow(window);
        glfwTerminate();
        window = 0;
        assertEquals(GL_NO_ERROR, error, "GL error during the test");
    }

    private MmsChunkRegion region(MmsRegionKey key, MmsVertexFormat format, int indexBytes) {
        MmsChunkRegion r = format == MmsVertexFormat.COMPACT20
            ? new MmsChunkRegion(key, format, VramPlans.CUBE_REGION_STAMP, indexBytes)
            : new MmsChunkRegion(key, format, VramPlans.CUBE_REGION_MESH);
        regions.add(r);
        return r;
    }

    private static byte[] readBack(int buffer, long offset, int length) {
        glMemoryBarrier(GL_ALL_BARRIER_BITS);
        glFinish();
        ByteBuffer out = ByteBuffer.allocateDirect(length).order(ByteOrder.nativeOrder());
        glBindBuffer(GL_COPY_READ_BUFFER, buffer);
        glGetBufferSubData(GL_COPY_READ_BUFFER, offset, out);
        glBindBuffer(GL_COPY_READ_BUFFER, 0);
        byte[] bytes = new byte[length];
        out.get(bytes);
        return bytes;
    }

    @Test
    void nativeQuadUploadLandsByteExactAndTheOriginIsCameraRelative() {
        long p40 = 1L << 40;
        RenderOrigin.update((double) p40 + 3.5, -100.0, (double) -p40 - 0.5);
        MmsRegionKey key = MmsRegionKey.ofBlock(p40 + 300, -384, -p40 - 700);
        MmsChunkRegion region = region(key, MmsVertexFormat.QUAD16, Short.BYTES);
        try (Arena arena = Arena.ofConfined()) {
            int quads = 37;
            MemorySegment seg = arena.allocate(quads * 16L, 16);
            for (int i = 0; i < quads * 4; i++) {
                seg.setAtIndex(ValueLayout.JAVA_INT, i, i * 0x01010101 ^ 0x5a5a5a5a);
            }
            region.uploadQuads(seg, 7, 0, 0, 0, 1, 1, 1); // a first mesh so the second sits at an offset
            MmsRegionMeshHandle h = region.uploadQuads(seg, quads, 0, 0, 0, 32, 32, 32);
            byte[] gpu = readBack(region.vertexBufferIdForTest(), (long) h.baseVertex() * 4, quads * 16);
            byte[] cpu = seg.toArray(ValueLayout.JAVA_BYTE);
            for (int i = 0; i < gpu.length; i++) {
                assertEquals(cpu[i], gpu[i], "byte " + i);
            }
            assertEquals(quads * 6, h.getIndexCount());
            assertEquals(2, region.gpuCommandCount());
        }
        float[] origin = new float[4];
        ByteBuffer.wrap(readBack(region.originBufferIdForTest(), 0, 16)).order(ByteOrder.nativeOrder())
            .asFloatBuffer().get(origin);
        Vector3f expected = region.renderOrigin(new Vector3f());
        assertEquals(expected.x, origin[0]);
        assertEquals(expected.y, origin[1]);
        assertEquals(expected.z, origin[2]);
        assertEquals(256f - 0f, expected.x, "corner 2^40+256 minus origin 2^40");
        assertEquals(-512f + 128f, expected.y, "corner -512 minus origin -128");

        // An origin step re-bakes the attribute.
        RenderOrigin.update((double) p40 + 3.5 + 64, -100.0, (double) -p40 - 0.5);
        ByteBuffer.wrap(readBack(region.originBufferIdForTest(), 0, 16)).order(ByteOrder.nativeOrder())
            .asFloatBuffer().get(origin);
        assertEquals(256f - 64f, origin[0]);
    }

    @Test
    void stampUploadWithU32IndicesFromNativeMemory() {
        MmsChunkRegion region = region(new MmsRegionKey(-3, 0, 2), MmsVertexFormat.COMPACT20, Integer.BYTES);
        assertEquals(GL_UNSIGNED_INT, region.indexType());
        try (Arena arena = Arena.ofConfined()) {
            int vertices = 8;
            int indices = 12;
            MemorySegment v = arena.allocate(vertices * 20L, 4);
            for (int i = 0; i < vertices * 5; i++) {
                v.setAtIndex(ValueLayout.JAVA_INT, i, 1000 + i);
            }
            MemorySegment ix = arena.allocate(indices * 4L, 4);
            for (int i = 0; i < indices; i++) {
                ix.setAtIndex(ValueLayout.JAVA_INT, i, 70_000 + i); // past u16 on purpose
            }
            MmsRegionMeshHandle h = region.uploadLocal(v, vertices, ix, indices, 0, 0, 0, 2, 2, 2);
            ByteBuffer idx = ByteBuffer.wrap(readBack(region.indexBufferIdForTest(),
                h.indexOffsetBytes(), indices * 4)).order(ByteOrder.nativeOrder());
            for (int i = 0; i < indices; i++) {
                assertEquals(70_000 + i, idx.getInt(), "u32 index " + i);
            }
            ByteBuffer vb = ByteBuffer.wrap(readBack(region.vertexBufferIdForTest(),
                (long) h.baseVertex() * 20, vertices * 20)).order(ByteOrder.nativeOrder());
            for (int i = 0; i < vertices * 5; i++) {
                assertEquals(1000 + i, vb.getInt());
            }
        }
    }

    @Test
    void gpuCullMatchesTheCpuTwinIncludingTheEnableBitFarFromZero() {
        assumeTrue(MmsGpuCuller.isSupported(), "needs OpenGL 4.3");
        long base = -(1L << 40);
        RenderOrigin.update((double) base + 0.5, 70.0, (double) base + 0.5);
        Matrix4f viewProj = new Matrix4f().perspective((float) Math.toRadians(70), 1f, 0.1f, 1000f)
            .lookAt(new Vector3f(0.5f, 6f, 0.5f), new Vector3f(0.5f, 0f, -20f), new Vector3f(0, 1, 0));
        float[] planes = new float[24];
        Vector4f p = new Vector4f();
        for (int i = 0; i < 6; i++) {
            viewProj.frustumPlane(i, p);
            planes[i * 4] = p.x;
            planes[i * 4 + 1] = p.y;
            planes[i * 4 + 2] = p.z;
            planes[i * 4 + 3] = p.w;
        }

        MmsChunkRegion region = region(MmsRegionKey.ofBlock(base, 0, base - 256), MmsVertexFormat.QUAD16,
            Short.BYTES);
        List<MmsRegionMeshHandle> handles = new ArrayList<>();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(16L * 4, 16);
            for (int cx = 0; cx < 8; cx++) {
                for (int cy = 0; cy < 8; cy++) {
                    for (int cz = 0; cz < 8; cz++) {
                        MmsRegionMeshHandle h = region.uploadQuads(seg, 4, cx * 32, cy * 32, cz * 32,
                            cx * 32 + 32, cy * 32 + 32, cz * 32 + 32);
                        h.setEnabled(((cx + cy + cz) % 3) != 0);
                        handles.add(h);
                    }
                }
            }
        }
        Vector3f o = region.renderOrigin(new Vector3f());
        int expectedVisible = 0;
        int[] expected = new int[handles.size()];
        for (int i = 0; i < handles.size(); i++) {
            MmsRegionMeshHandle h = handles.get(i);
            boolean v = h.isEnabled() && MmsGpuCuller.aabbVisible(planes, o.x, o.y, o.z,
                h.localMinX(), h.localMinY(), h.localMinZ(), h.localMaxX(), h.localMaxY(), h.localMaxZ());
            expected[i] = v ? 1 : 0;
            expectedVisible += expected[i];
        }
        assertTrue(expectedVisible > 10 && expectedVisible < handles.size() / 2,
            "the scene must exercise both verdicts: " + expectedVisible);

        try (MmsGpuCuller culler = new MmsGpuCuller();
             MmsMultiDrawBatch batch = new MmsMultiDrawBatch(64)) {
            culler.beginPass(planes);
            assertEquals(handles.size(), culler.cull(region));
            culler.endCull();
            ByteBuffer cmds = ByteBuffer.wrap(readBack(region.gpuCmdBufferId, region.gpuCmdOffsetBytes,
                handles.size() * 20)).order(ByteOrder.nativeOrder());
            // Live-handle order is upload order (no frees).
            for (int i = 0; i < handles.size(); i++) {
                MmsRegionMeshHandle h = handles.get(i);
                assertEquals(h.getIndexCount(), cmds.getInt(), "index count " + i);
                assertEquals(expected[i], cmds.getInt(), "instance count (visibility) of mesh " + i);
                assertEquals(0, cmds.getInt());
                assertEquals(h.baseVertex(), cmds.getInt());
                assertEquals(0, cmds.getInt());
            }
            culler.endDraw();

            assertEquals(expectedVisible, region.appendVisible(batch, planes), "CPU path, same verdicts");
        }
    }
}
