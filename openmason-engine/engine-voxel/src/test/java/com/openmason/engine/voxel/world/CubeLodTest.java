package com.openmason.engine.voxel.world;

import com.openmason.engine.voxel.mms.mmsCore.MmsLodQuadCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The native octree LOD ({@code cv_lod_*}) through its FFM binding: node reads (classes, downsampling), node-mode
 * LODQUAD16 meshes decoded with {@link MmsLodQuadCodec}, selection events + mesh hand-out, readiness handover,
 * persistence across instances and the stream event feed. The native suite ({@code cenda/tests/voxel_lod_test.cpp})
 * covers the rules in depth; this checks marshalling.
 */
class CubeLodTest {

    private static final short SOLID = (short) (BlockFlags.CUBE | BlockFlags.OPAQUE | BlockFlags.SKY_OPAQUE | BlockFlags.VIS_OPAQUE);
    private static final short[] FLAGS = {0, SOLID, SOLID, (short) (BlockFlags.TRANSLUCENT | BlockFlags.TRANSPARENT)};

    @BeforeEach
    void setUp() {
        assumeTrue(CendaVoxelWorld.isAvailable(), () -> "Cenda voxel world unavailable: " + CendaVoxelWorld.unavailableReason());
        assumeTrue(CubeLod.isAvailable(), "this Cenda library has no octree LOD (rebuild it)");
    }

    /** 4x4 columns of flat terrain: stone below y 40, grass at 40, a stone block floating in cube (10, 0, 10). */
    private static CubeWorld terrain() {
        CubeWorld w = CubeWorld.create(-64, 192, 0, null);
        w.setBlockTable(FLAGS);
        short[] ids = new short[32768];
        for (long cx = 0; cx < 4; cx++) {
            for (long cz = 0; cz < 4; cz++) {
                for (long cy = 0; cy <= 2; cy++) {
                    for (int ly = 0; ly < 32; ly++) {
                        long y = cy * 32 + ly;
                        short id = y < 40 ? (short) 1 : y == 40 ? (short) 2 : (short) 0;
                        for (int lz = 0; lz < 32; lz++) {
                            for (int lx = 0; lx < 32; lx++) ids[CubeWorld.cellIndex(lx, ly, lz)] = id;
                        }
                    }
                    w.writeIds(cx, cy, cz, ids);
                }
            }
        }
        short[] lone = new short[32768];
        lone[CubeWorld.cellIndex(3, 4, 5)] = 1;
        w.writeIds(10, 0, 10, lone);
        return w;
    }

    @Test
    void nodesAndNodeModeMeshes() {
        try (CubeWorld w = terrain(); CubeLod lod = CubeLod.create(w, null, null, CubeLod.Config.of(3, 1))) {
            short[] ids = new short[32768];
            byte[] cells = new byte[32768];
            assertTrue(lod.readNode(0, 0, 1, 0, ids, cells));
            assertEquals(2, ids[CubeWorld.cellIndex(4, 8, 4)]);   // y 40
            assertEquals(CubeLod.CELL_SOLID | CubeLod.CELL_FULL, cells[CubeWorld.cellIndex(4, 8, 4)]);
            assertEquals(0, cells[CubeWorld.cellIndex(4, 9, 4)]);
            // Level 1: the cell over blocks y 40..41 has 4 of 8 solid -> SOLID (not FULL), grass on top.
            assertTrue(lod.readNode(1, 0, 0, 0, ids, cells));
            assertEquals(2, ids[CubeWorld.cellIndex(3, 20, 3)]);
            assertEquals(CubeLod.CELL_SOLID, cells[CubeWorld.cellIndex(3, 20, 3)]);
            assertFalse(lod.readNode(0, 50, 0, 50, null, null));   // never generated: incomplete

            // One stone block -> 6 unit quads, node-local, decoded with the Java codec.
            LodMesh m = lod.buildMesh(0, 10, 0, 10);
            assertEquals(6, m.quadCount());
            assertEquals(0, m.waterCount());
            assertEquals(320, m.originX());
            assertEquals(1, m.cellSize());
            MemorySegment seg = m.quads();
            assertEquals(6L * LodMesh.QUAD_BYTES, seg.byteSize());
            ByteBuffer q = ByteBuffer.wrap(seg.toArray(ValueLayout.JAVA_BYTE)).order(ByteOrder.LITTLE_ENDIAN);
            Set<Integer> faces = new HashSet<>();
            for (int i = 0; i < 6; i++) {
                int w0 = q.getInt(i * 16);
                int w1 = q.getInt(i * 16 + 4);
                int face = MmsLodQuadCodec.face(w0);
                faces.add(face);
                assertEquals(1, MmsLodQuadCodec.nodeWidth(w1));
                assertEquals(1, MmsLodQuadCodec.nodeHeight(w1));
                assertEquals(1, MmsLodQuadCodec.layer(w1));
                assertTrue(MmsLodQuadCodec.lit(w1));
                assertEquals(w0, MmsLodQuadCodec.nodeWord0(MmsLodQuadCodec.nodeX(w0), MmsLodQuadCodec.nodeY(w0),
                    MmsLodQuadCodec.nodeZ(w0), face));
                for (int corner = 0; corner < 4; corner++) {
                    long x = MmsLodQuadCodec.nodeBlockPosition(q, i, corner, 0, m.originX(), m.cellSize());
                    long y = MmsLodQuadCodec.nodeBlockPosition(q, i, corner, 1, m.originY(), m.cellSize());
                    long z = MmsLodQuadCodec.nodeBlockPosition(q, i, corner, 2, m.originZ(), m.cellSize());
                    assertTrue(x == 323 || x == 324, "x " + x);
                    assertTrue(y == 4 || y == 5, "y " + y);
                    assertTrue(z == 325 || z == 326, "z " + z);
                    if (face == 0) assertEquals(5, y);
                    if (face == 1) assertEquals(4, y);
                }
            }
            assertEquals(6, faces.size());
            assertThrows(IllegalArgumentException.class, () -> lod.readNode(4, 0, 0, 0, null, null));
        }
    }

    @Test
    void selectionEventsReadinessAndPersistence(@TempDir Path dir) {
        try (CubeWorld w = terrain()) {
            Path lodDir = dir.resolve("lod");
            CubeLod.Config cfg = new CubeLod.Config(3, 0, 0, 1, 5, 0, 2, false);
            long digest;
            try (CubeLod lod = CubeLod.create(w, null, lodDir, cfg)) {
                lod.setView(new long[] {64, 41, 64}, new LodPolicy(64, 2, 200, 2, -1));
                Map<List<Long>, Long> drawn = new HashMap<>();
                Set<List<Long>> fallback = new HashSet<>();
                long meshBytes = 0;
                int quiet = 0;
                for (int t = 0; t < 4000 && quiet < 3; t++) {
                    LodEvents ev = lod.tick(0);
                    for (int i = 0; i < ev.count(); i++) {
                        List<Long> key = List.of((long) ev.level(i), ev.x(i), ev.y(i), ev.z(i));
                        switch (ev.type(i)) {
                            case CubeLod.EV_SHOW, CubeLod.EV_UPDATE -> {
                                LodMesh m = lod.mesh(ev.mesh(i));
                                assertEquals(ev.level(i), m.level());
                                assertEquals(ev.x(i), m.nx());
                                assertEquals(m.bytes(), m.quads().byteSize() + m.water().byteSize());
                                assertFalse(m.isEmpty());
                                meshBytes += m.bytes();
                                drawn.put(key, m.digest());
                            }
                            case CubeLod.EV_HIDE -> assertTrue(drawn.remove(key) != null);
                            case CubeLod.EV_FALLBACK_ON -> assertTrue(fallback.add(List.of((long) ev.level(i), ev.x(i), ev.z(i))));
                            case CubeLod.EV_FALLBACK_OFF -> assertTrue(fallback.remove(List.of((long) ev.level(i), ev.x(i), ev.z(i))));
                            default -> throw new AssertionError("event type " + ev.type(i));
                        }
                    }
                    quiet = ev.count() == 0 && lod.stats().idle() ? quiet + 1 : 0;
                    if (ev.count() == 0) sleep(2);
                }
                assertFalse(drawn.isEmpty(), "octree nodes drawn over the generated area");
                assertFalse(fallback.isEmpty(), "fallback footprints where nothing was generated");
                assertTrue(meshBytes > 0);
                assertEquals(drawn.size(), lod.stats().drawnNodes());

                // Column (1, 1) becomes READY: in the same tick nothing drawn covers it any more.
                lod.setColumnReady(1, 1, true);
                LodEvents ev = lod.tick(0);
                for (int i = 0; i < ev.count(); i++) {
                    List<Long> key = List.of((long) ev.level(i), ev.x(i), ev.y(i), ev.z(i));
                    if (ev.type(i) == CubeLod.EV_SHOW) drawn.put(key, 0L);
                    if (ev.type(i) == CubeLod.EV_HIDE) drawn.remove(key);
                    if (ev.type(i) == CubeLod.EV_FALLBACK_ON) fallback.add(List.of((long) ev.level(i), ev.x(i), ev.z(i)));
                    if (ev.type(i) == CubeLod.EV_FALLBACK_OFF) fallback.remove(List.of((long) ev.level(i), ev.x(i), ev.z(i)));
                }
                for (List<Long> k : drawn.keySet()) {
                    int lv = k.get(0).intValue();
                    assertFalse((1 >> lv) == k.get(1) && (1 >> lv) == k.get(3), "drawn node over a READY column: " + k);
                }
                for (List<Long> f : fallback) {
                    int lv = f.get(0).intValue();
                    assertFalse((1 >> lv) == f.get(1) && (1 >> lv) == f.get(2), "fallback over a READY column: " + f);
                }
                digest = lod.nodeDigest(2, 0, 0, 0);
                lod.flush();
            }
            // Reopen: the persisted levels load, nothing is rebuilt.
            try (CubeLod lod = CubeLod.create(w, null, lodDir, cfg)) {
                assertEquals(digest, lod.nodeDigest(2, 0, 0, 0));
                lod.tick(0);
                LodStats st = lod.stats();
                assertTrue(st.nodesLoadedTotal() > 0);
                assertEquals(0, st.nodesBuiltTotal());
            }
            // Another content version: a mismatch (unless reset is asked for).
            CubeLod.Config other = new CubeLod.Config(3, 0, 0, 1, 6, 0, 2, false);
            assertThrows(IllegalArgumentException.class, () -> CubeLod.create(w, null, lodDir, other));
            CubeLod.Config reset = new CubeLod.Config(3, 0, 0, 1, 6, 0, 2, true);
            try (CubeLod lod = CubeLod.create(w, null, lodDir, reset)) {
                assertEquals(digest, lod.nodeDigest(2, 0, 0, 0));
                lod.tick(0);
                assertEquals(0, lod.stats().nodesLoadedTotal());
            }
        }
    }

    @Test
    void editsReachEveryLevel() {
        try (CubeWorld w = terrain(); CubeLod lod = CubeLod.create(w, null, null, CubeLod.Config.of(3, 1))) {
            long before = lod.nodeDigest(3, 0, 0, 0);
            for (long x = 0; x < 64; x++) {
                for (long z = 0; z < 64; z++) {
                    for (long y = 0; y < 41; y++) w.set(x, y, z, 0);   // a 64-wide crater down to y 0
                }
            }
            lod.noteCubes(0, 0, 0, 1, 0, 0, 0, 0, 1, 1, 0, 1, 0, 1, 0, 1, 1, 0, 0, 1, 1, 1, 1, 1);
            lod.waitIdle();
            long after = lod.nodeDigest(3, 0, 0, 0);
            assertTrue(before != after);
            try (CubeLod fresh = CubeLod.create(w, null, null, CubeLod.Config.of(3, 1))) {
                assertEquals(fresh.nodeDigest(3, 0, 0, 0), after);
                assertEquals(fresh.nodeDigest(1, 0, 0, 0), lod.nodeDigest(1, 0, 0, 0));
            }
        }
    }

    @Test
    void streamEventsFeedTheLod(@TempDir Path dir) {
        assumeTrue(CubeStream.isAvailable() && CendaVoxelWorld.TEST_TOY_GENERATOR_CREATE != null);
        MemorySegment gen = CubeStream.createTestGenerator(3);
        try {
            try (CubeWorld w = CubeWorld.createForGenerator(-384, 640, 0, gen);
                 CubeStore store = CubeStore.open(dir.resolve("cubes"))) {
                w.setBlockTable(new short[] {0, SOLID, SOLID, (short) (BlockFlags.TRANSLUCENT | BlockFlags.TRANSPARENT),
                    (short) (BlockFlags.CUBE | BlockFlags.OPAQUE | BlockFlags.VIS_OPAQUE), (short) (BlockFlags.CUBE | BlockFlags.TRANSPARENT)});
                w.defineLayer(0, CubeWorld.LAYER_SAVED | CubeWorld.LAYER_NETWORK);
                try (CubeStream s = CubeStream.create(w, store, gen, 2, CubeStream.MESH_GREEDY);
                     CubeLod lod = CubeLod.create(w, store, dir.resolve("lod"), CubeLod.Config.of(3, 1))) {
                    s.setCenters(new StreamCenter(0, 40, 0, 64, 48, 16, 8));
                    lod.setView(new long[] {0, 40, 0}, new LodPolicy(48, 3, 300, 2, -1));
                    long shows = 0;
                    for (int t = 0; t < 3000 && lod.stats().readyColumns() < 4; t++) {
                        StreamEvents se = s.tick(StreamBudget.UNLIMITED);
                        lod.noteEvents(se);
                        LodEvents le = lod.tick(0);
                        for (int i = 0; i < le.count(); i++) {
                            if (le.type(i) == CubeLod.EV_SHOW) shows++;
                        }
                        if (se.count() == 0 && le.count() == 0) sleep(2);
                    }
                    assertTrue(lod.stats().readyColumns() >= 4, "COLUMN_READY reached the LOD");
                    lod.waitIdle();
                    assertTrue(lod.stats().nodesBuiltTotal() > 0, "installed cubes rebuilt their level-1 parents (" + shows + " shows)");
                }
            }
        } finally {
            CubeStream.destroyTestGenerator(gen);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
