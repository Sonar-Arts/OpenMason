package com.openmason.engine.voxel.mms.mmsRegion;

import com.openmason.engine.voxel.mms.mmsCore.MmsQuadCodec;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GL-free region culling contract: the MeshMeta record layout (region-local
 * bounds, draw words, enable bit), command splitting for oversized pulled
 * meshes, the CPU twin of the kernel's plane test with camera-relative region
 * origins at extreme coordinates, and the CPU multidraw batch honouring the
 * enable bit.
 */
class MmsRegionCullTest {

    private static MmsRegionMeshHandle pulledHandle(MmsArenaAllocator alloc, int quads,
                                                    float minX, float minY, float minZ,
                                                    float maxX, float maxY, float maxZ) {
        MmsArenaAllocator.Segment seg = alloc.alloc(quads * 4);
        return new MmsRegionMeshHandle(null, seg, null, quads * 6, Short.BYTES, quads,
            minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static MmsRegionMeshHandle indexedHandle(MmsArenaAllocator vertices, MmsArenaAllocator indices,
                                                     int vertexCount, int indexCount, int indexBytes) {
        return new MmsRegionMeshHandle(null, vertices.alloc(vertexCount), indices.alloc(indexCount),
            indexCount, indexBytes, 0, 0f, 0f, 0f, 32f, 32f, 32f);
    }

    @Test
    void meshMetaRecordPacksRegionLocalBoundsDrawWordsAndTheEnableBit() {
        MmsArenaAllocator alloc = new MmsArenaAllocator(1 << 16);
        alloc.alloc(400); // push the mesh off offset 0
        MmsRegionMeshHandle h = pulledHandle(alloc, 100, 32f, 64f, 96f, 64f, 70.5f, 128f);
        ByteBuffer buf = ByteBuffer.allocate(MmsChunkRegion.GPU_META_STRIDE * 2).order(ByteOrder.nativeOrder());

        MmsChunkRegion.writeMeta(buf, h, h.metaFlags());
        h.setEnabled(false);
        MmsChunkRegion.writeMeta(buf, h, h.metaFlags());
        assertEquals(MmsChunkRegion.GPU_META_STRIDE * 2, buf.position(), "one 48-byte record per command");

        buf.flip();
        for (int rec = 0; rec < 2; rec++) {
            assertEquals(32f, buf.getFloat());
            assertEquals(64f, buf.getFloat());
            assertEquals(96f, buf.getFloat());
            assertEquals(0f, buf.getFloat());
            assertEquals(64f, buf.getFloat());
            assertEquals(70.5f, buf.getFloat());
            assertEquals(128f, buf.getFloat());
            assertEquals(0f, buf.getFloat());
            assertEquals(600, buf.getInt(), "index count: 6 per quad");
            assertEquals(0, buf.getInt(), "pulled meshes index the shared quad EBO from 0");
            assertEquals(h.baseVertex(), buf.getInt());
            assertEquals(rec == 0 ? MmsRegionMeshHandle.FLAG_ENABLED : 0, buf.getInt(), "flags word");
        }
    }

    @Test
    void oversizedPulledMeshesSplitIntoCommandsThatCoverEveryQuad() {
        int perDraw = MmsQuadCodec.MAX_QUADS_PER_DRAW;
        MmsArenaAllocator alloc = new MmsArenaAllocator(1 << 20);
        int quads = perDraw * 2 + 123;
        MmsRegionMeshHandle h = pulledHandle(alloc, quads, 0, 0, 0, 32, 32, 32);
        assertEquals(3, h.commandCount());
        int covered = 0;
        for (int k = 0; k < 3; k++) {
            assertEquals(h.baseVertex() + covered * 4, h.commandBaseVertex(k), "commands are contiguous");
            assertTrue(h.commandIndexCount(k) <= perDraw * 6, "never past the shared u16 EBO");
            covered += h.commandIndexCount(k) / 6;
        }
        assertEquals(quads, covered);
        assertEquals(1, pulledHandle(alloc, perDraw, 0, 0, 0, 1, 1, 1).commandCount());

        ByteBuffer buf = ByteBuffer.allocate(MmsChunkRegion.GPU_META_STRIDE * 3).order(ByteOrder.nativeOrder());
        MmsChunkRegion.writeMeta(buf, h, h.metaFlags());
        assertEquals(MmsChunkRegion.GPU_META_STRIDE * 3, buf.position());
    }

    @Test
    void indexedMeshesAddressTheirArenaInTheRegionsIndexWidth() {
        MmsArenaAllocator vertices = new MmsArenaAllocator(1 << 16);
        MmsArenaAllocator indices = new MmsArenaAllocator(1 << 16);
        indices.alloc(1000);
        MmsRegionMeshHandle u32 = indexedHandle(vertices, indices, 40, 60, Integer.BYTES);
        assertEquals(1, u32.commandCount());
        assertEquals(60, u32.commandIndexCount(0));
        int first = u32.commandFirstIndex(0);
        assertTrue(first > 0, "the mesh sits past the first allocation");
        assertEquals(first * 4L, u32.indexOffsetBytes(), "firstIndex is in elements, the byte offset u32-wide");

        try (MmsMultiDrawBatch batch = new MmsMultiDrawBatch(4)) {
            assertEquals(1, batch.add(u32));
            assertEquals(60, batch.countAt(0));
            assertEquals(first * 4L, batch.offsetAt(0));
            assertEquals(u32.baseVertex(), batch.baseVertexAt(0));
        }
    }

    @Test
    void cpuBatchSkipsDisabledMeshes() {
        MmsArenaAllocator alloc = new MmsArenaAllocator(1 << 20);
        MmsRegionMeshHandle a = pulledHandle(alloc, 10, 0, 0, 0, 32, 32, 32);
        MmsRegionMeshHandle b = pulledHandle(alloc, MmsQuadCodec.MAX_QUADS_PER_DRAW + 1, 32, 0, 0, 64, 32, 32);
        try (MmsMultiDrawBatch batch = new MmsMultiDrawBatch(4)) {
            assertEquals(1, batch.add(a));
            assertEquals(2, batch.add(b), "split mesh adds every command");
            a.setEnabled(false);
            assertEquals(0, batch.add(a), "disabled: the LOD owns this column");
            assertEquals(3, batch.size());
            assertEquals(b.commandBaseVertex(1), batch.baseVertexAt(2));
            assertEquals(6, batch.countAt(2));
        }
    }

    /** Render-space frustum planes from a camera at the origin looking down -Z. */
    private static float[] planes(Matrix4f viewProj) {
        float[] out = new float[24];
        Vector4f p = new Vector4f();
        for (int i = 0; i < 6; i++) {
            viewProj.frustumPlane(i, p);
            out[i * 4] = p.x;
            out[i * 4 + 1] = p.y;
            out[i * 4 + 2] = p.z;
            out[i * 4 + 3] = p.w;
        }
        return out;
    }

    /**
     * The camera sits at ±2^40 (render origin there); a region 256 blocks
     * ahead of it, and another behind, are tested with region-local boxes plus
     * the long-exact camera-relative region origin. Absolute float boxes at
     * that magnitude would be quantized to 131072-block steps.
     */
    @Test
    void cpuCullWithCameraRelativeRegionOriginsIsExactFarFromZero() {
        Matrix4f viewProj = new Matrix4f().perspective((float) Math.toRadians(70), 1f, 0.1f, 2000f)
            .lookAt(new Vector3f(0.5f, 10f, 0.5f), new Vector3f(0.5f, 10f, -10f), new Vector3f(0, 1, 0));
        float[] planes = planes(viewProj);
        for (long base : new long[]{1L << 40, -(1L << 40), 0}) {
            com.openmason.engine.rendering.RenderOrigin.update((double) base + 0.5, 10.0, (double) base + 0.5);
            try {
                // Region whose corner is 512 blocks in front (-Z) of the camera's origin cell.
                MmsRegionKey ahead = MmsRegionKey.ofBlock(base, 0, base - 512);
                MmsRegionKey behind = MmsRegionKey.ofBlock(base, 0, base + 512);
                Vector3f oa = com.openmason.engine.rendering.RenderOrigin.relative(
                    ahead.minBlockX(), ahead.minBlockY(), ahead.minBlockZ(), new Vector3f());
                Vector3f ob = com.openmason.engine.rendering.RenderOrigin.relative(
                    behind.minBlockX(), behind.minBlockY(), behind.minBlockZ(), new Vector3f());
                long cx = base - ahead.minBlockX();
                // A cube straight ahead, at the camera's x, inside the region.
                float lx = cx, lz = 0f;
                assertTrue(MmsGpuCuller.aabbVisible(planes, oa.x, oa.y, oa.z,
                    lx, 0f, lz + 128f, lx + 32f, 32f, lz + 160f), "ahead at " + base);
                assertFalse(MmsGpuCuller.aabbVisible(planes, ob.x, ob.y, ob.z,
                    lx, 0f, 0f, lx + 32f, 32f, 32f), "behind at " + base);
                // Same box far off to the side: outside the 70° frustum.
                assertFalse(MmsGpuCuller.aabbVisible(planes, oa.x + 4000f, oa.y, oa.z,
                    lx, 0f, lz + 128f, lx + 32f, 32f, lz + 160f));
            } finally {
                com.openmason.engine.rendering.RenderOrigin.reset();
            }
        }
    }

    /** Rebasing world-space planes onto the render origin gives the same verdicts (compat path). */
    @Test
    void worldPlanesRebaseToRenderPlanes() {
        Matrix4f renderViewProj = new Matrix4f().perspective((float) Math.toRadians(70), 1f, 0.1f, 500f)
            .lookAt(new Vector3f(3f, 70f, 5f), new Vector3f(3f, 60f, -50f), new Vector3f(0, 1, 0));
        long ox = 640, oy = 64, oz = -320;
        // World-consuming matrix = render matrix * T(-origin) (RenderOrigin.acceptWorldSpace).
        Matrix4f worldViewProj = new Matrix4f(renderViewProj).translate(-ox, -oy, -oz);
        float[] render = planes(renderViewProj);
        float[] rebased = new float[24];
        MmsGpuCuller.worldToRenderPlanes(planes(worldViewProj), ox, oy, oz, rebased);
        for (int i = 0; i < 24; i++) {
            assertEquals(render[i], rebased[i], Math.abs(render[i]) * 1e-3f + 1e-2f, "plane component " + i);
        }
        for (int x = -96; x <= 96; x += 32) {
            for (int z = -192; z <= 32; z += 32) {
                boolean a = MmsGpuCuller.aabbVisible(render, 0, 0, 0, x, 50, z, x + 32, 82, z + 32);
                boolean b = MmsGpuCuller.aabbVisible(rebased, 0, 0, 0, x, 50, z, x + 32, 82, z + 32);
                assertEquals(a, b, "box " + x + "," + z);
            }
        }
    }
}
