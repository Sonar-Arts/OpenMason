package com.openmason.engine.voxel.world;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Cenda voxel world ({@code cv_*}) through its FFM binding: round trips through Java, the surface
 * upcall (from several threads, and a throwing source), the CV_NEED retry protocol behind every
 * variable-size result, layers, blobs, column records and the region store. The native suite
 * ({@code cenda/tests/voxel_world_test.cpp}) covers the storage and tracker rules in depth; this checks
 * marshalling.
 */
class CubeWorldTest {

    private static final int AIR = 0;
    private static final int STONE = 1;
    private static final int GLASS = 2;
    private static final short[] FLAGS = {
        0,
        (short) (BlockFlags.CUBE | BlockFlags.OPAQUE | BlockFlags.SKY_OPAQUE | BlockFlags.VIS_OPAQUE),
        (short) (BlockFlags.CUBE | BlockFlags.TRANSPARENT),
    };

    private final AtomicInteger surfaceCalls = new AtomicInteger();
    private volatile boolean throwFromSource;
    private CubeWorld world;

    /** Generator surface: sky 20 + (x+z) % 5, top = max(sky, 24) (a "sea" at 24). */
    private boolean surface(long cx, long cz, long[] sky, long[] top) {
        surfaceCalls.incrementAndGet();
        if (throwFromSource) {
            throw new IllegalStateException("surface failure (expected by the test)");
        }
        for (int lz = 0; lz < 32; lz++) {
            for (int lx = 0; lx < 32; lx++) {
                long x = cx * 32 + lx, z = cz * 32 + lz;
                sky[lz * 32 + lx] = 20 + Math.floorMod(x + z, 5);
                top[lz * 32 + lx] = Math.max(sky[lz * 32 + lx], 24);
            }
        }
        return true;
    }

    @BeforeEach
    void setUp() {
        assumeTrue(CendaVoxelWorld.isAvailable(), () -> "Cenda voxel world unavailable: " + CendaVoxelWorld.unavailableReason());
        world = CubeWorld.create(-128, 128, AIR, this::surface);
        world.setBlockTable(FLAGS);
    }

    @AfterEach
    void tearDown() {
        if (world != null) world.close();
    }

    @Test
    void blocksRoundTripWithLongCoordinates() {
        try (CubeWorld big = CubeWorld.create(CubeWorld.UNBOUNDED_MIN, CubeWorld.UNBOUNDED_MAX, AIR, null)) {
            big.setBlockTable(FLAGS);
            long far = 1L << 62;
            assertTrue(big.createCube(far >> 5, -far >> 5, 7, AIR));
            assertFalse(big.createCube(far >> 5, -far >> 5, 7, AIR));
            assertEquals(AIR, big.set(far + 3, -far + 1, 7 * 32 + 9, 0xFFFE));
            assertEquals(0xFFFE, big.get(far + 3, -far + 1, 7 * 32 + 9), "uint16 ids above 32767 survive");
            assertEquals(CubeWorld.MISSING, big.get(0, 0, 0));
            assertEquals(CubeWorld.MISSING, big.set(0, 0, 0, STONE));
            assertEquals(1, big.cubeCount());
            assertArrayEquals(new long[] {far >> 5, -far >> 5, 7}, big.listCubes());
        }
        // bounds
        assertThrows(IllegalArgumentException.class, () -> world.createCube(0, 10, 0, STONE));
        world.createCube(0, 0, 0, AIR);
        assertThrows(IllegalArgumentException.class, () -> world.set(0, 200, 0, STONE));
        assertEquals(AIR, world.get(0, 500, 0));
    }

    @Test
    void cubeContentEncodeDecodeAndUniform() {
        short[] ids = new short[32768];
        for (int i = 0; i < ids.length; i++) ids[i] = (short) (i % 3 == 0 ? STONE : (i % 7 == 0 ? GLASS : AIR));
        world.writeIds(1, 0, 2, ids);
        short[] back = new short[32768];
        assertTrue(world.readIds(1, 0, 2, back));
        assertArrayEquals(ids, back);
        assertFalse(world.readIds(9, 0, 9, back));
        assertEquals(CubeWorld.NOT_UNIFORM, world.uniformId(1, 0, 2));
        world.createCube(2, 0, 2, STONE);
        assertEquals(STONE, world.uniformId(2, 0, 2));
        assertEquals(CubeWorld.MISSING, world.uniformId(5, 0, 5));

        world.defineLayer(0, CubeWorld.LAYER_SAVED | CubeWorld.LAYER_MESHED);
        world.setLayer(0, 40, 3, 70, 12);
        world.setBlob(1, 0, 2, new byte[] {7, 8, 9});
        byte[] saved = world.encode(1, 0, 2, CubeWorld.PARTS_SAVED);
        assertNotNull(saved);
        assertNull(world.encode(9, 0, 9, CubeWorld.PARTS_SAVED));
        byte[] net = world.encode(1, 0, 2, CubeWorld.PARTS_NETWORK);
        assertTrue(net.length < saved.length);

        try (CubeWorld other = CubeWorld.create(-128, 128, AIR, null)) {
            other.setBlockTable(FLAGS);
            other.defineLayer(0, CubeWorld.LAYER_SAVED | CubeWorld.LAYER_MESHED);
            other.decode(1, 0, 2, saved);
            assertTrue(other.readIds(1, 0, 2, back));
            assertArrayEquals(ids, back);
            assertEquals(12, other.getLayer(0, 40, 3, 70));
            assertArrayEquals(new byte[] {7, 8, 9}, other.getBlob(1, 0, 2));
            assertArrayEquals(saved, other.encode(1, 0, 2, CubeWorld.PARTS_SAVED));
            byte[] garbage = saved.clone();
            garbage[0] = 99;
            assertThrows(IllegalArgumentException.class, () -> other.decode(3, 0, 3, garbage));
        }
    }

    @Test
    void layersScanAndBlob() {
        world.createCube(0, 0, 0, STONE);
        world.defineLayer(3, CubeWorld.LAYER_MESHED);
        assertEquals(0, world.setLayer(3, 1, 2, 3, 0xABCD));
        assertEquals(0xABCD, world.getLayer(3, 1, 2, 3));
        assertEquals(CubeWorld.MISSING, world.getLayer(3, 100, 2, 3));
        CubeWorld.LayerCells cells = world.scanLayer(3, 0, 0, 0);
        assertEquals(1, cells.count());
        assertEquals(CubeWorld.cellIndex(1, 2, 3), Short.toUnsignedInt(cells.index()[0]));
        assertEquals(0xABCD, Short.toUnsignedInt(cells.value()[0]));
        short[] layer = new short[32768];
        assertTrue(world.readLayer(3, 0, 0, 0, layer));
        assertEquals((short) 0xABCD, layer[CubeWorld.cellIndex(1, 2, 3)]);
        layer[5] = 4;
        assertTrue(world.writeLayer(3, 0, 0, 0, layer));
        assertEquals(2, world.scanLayer(3, 0, 0, 0).count());
        assertNull(world.scanLayer(3, 5, 0, 5));
        assertThrows(IllegalArgumentException.class, () -> world.setLayer(9, 0, 0, 0, 1));

        assertArrayEquals(new byte[0], world.getBlob(0, 0, 0));
        byte[] big = new byte[10_000];   // > the 4 KiB first try: CV_NEED retry
        for (int i = 0; i < big.length; i++) big[i] = (byte) i;
        assertTrue(world.setBlob(0, 0, 0, big));
        assertArrayEquals(big, world.getBlob(0, 0, 0));
        assertNull(world.getBlob(4, 0, 4));
        assertFalse(world.setBlob(4, 0, 4, big));
    }

    @Test
    void retryProtocolGrowsEveryVariableResult() {
        // > 1024 cubes: listCubes and drainDirty grow past their first buffer
        for (int x = 0; x < 40; x++) {
            for (int z = 0; z < 30; z++) world.createCube(x, -1, z, STONE);
        }
        assertEquals(3 * 1200, world.listCubes().length);
        long[] dirty = world.drainDirty();
        assertEquals(3 * 1200, dirty.length);
        Set<CubePos> unique = new HashSet<>();
        for (int i = 0; i < dirty.length; i += 3) unique.add(new CubePos(dirty[i], dirty[i + 1], dirty[i + 2]));
        assertEquals(1200, unique.size());
        assertEquals(0, world.drainDirty().length);
        // > 64 KiB encoding: 8 direct sections
        short[] ids = new short[32768];
        for (int i = 0; i < ids.length; i++) ids[i] = (short) (i % 1000 + 3);
        world.writeIds(0, 1, 0, ids);
        byte[] enc = world.encode(0, 1, 0, CubeWorld.PARTS_SAVED);
        assertTrue(enc.length > 64 * 1024, "encoding is " + enc.length);
        world.decode(0, 2, 0, enc);
        short[] back = new short[32768];
        assertTrue(world.readIds(0, 2, 0, back));
        assertArrayEquals(ids, back);
    }

    @Test
    void revisionsAndDirtySet() {
        world.createCube(0, 0, 0, AIR);
        world.createCube(1, 0, 0, AIR);
        world.drainDirty();
        long before = world.worldRevision();
        world.set(31, 5, 5, GLASS);
        assertTrue(world.worldRevision() > before);
        assertEquals(world.worldRevision(), world.cubeRevision(0, 0, 0));
        assertEquals(0, world.cubeRevision(7, 7, 7));
        long[] dirty = world.drainDirty();
        Set<CubePos> got = new HashSet<>();
        for (int i = 0; i < dirty.length; i += 3) got.add(new CubePos(dirty[i], dirty[i + 1], dirty[i + 2]));
        assertEquals(Set.of(new CubePos(0, 0, 0), new CubePos(1, 0, 0)), got);
    }

    @Test
    void surfaceUpcallAnswersNeverGeneratedSpaceAndKnownAir() {
        // never generated: the callback answers (cached per column)
        assertEquals(20 + Math.floorMod(3 + 4, 5), world.surfaceY(3, 4));
        assertEquals(24, world.topY(3, 4));
        int calls = surfaceCalls.get();
        assertEquals(24, world.topY(5, 6));
        assertEquals(calls, surfaceCalls.get(), "one upcall per column");
        // known air above TOP of a missing cube, unknown below
        assertEquals(AIR, world.get(3, 60, 4));
        assertEquals(CubeWorld.MISSING, world.get(3, 10, 4));
        short[] box = new short[40];
        assertEquals(24, world.readBox(3, 0, 4, 1, 40, 1, box, 999));
        assertEquals(999, box[0]);
        assertEquals(AIR, box[30]);
        CubeWorld.TopRange range = world.topRange(0, 0);
        assertTrue(range.known());
        assertEquals(24, range.topMin());
        assertEquals(24, range.topMax());
        assertEquals(20, range.skyMin());
        // generated content overrides the callback
        world.createCube(0, 0, 0, AIR);
        world.createCube(0, -1, 0, STONE);
        assertEquals(0, world.surfaceY(3, 4));
        world.set(3, 30, 4, STONE);
        assertEquals(31, world.surfaceY(3, 4));
        long[] column = new long[1024];
        assertTrue(world.surfaceColumn(0, 0, column));
        assertEquals(31, column[4 * 32 + 3]);
        assertEquals(0, column[0]);
        // column records round trip
        byte[] rec = world.encodeColumn(0, 0);
        assertNotNull(rec);
        assertFalse(world.removeColumn(0, 0), "cubes keep the column");
        world.removeCube(0, 0, 0);
        world.removeCube(0, -1, 0);
        assertEquals(31, world.surfaceY(3, 4), "the column remembers unloaded cubes");
        assertTrue(world.removeColumn(0, 0));
        assertNull(world.encodeColumn(0, 0));
        world.decodeColumn(0, 0, rec);
        assertEquals(31, world.surfaceY(3, 4));
        assertThrows(IllegalArgumentException.class, () -> world.decodeColumn(1, 1, new byte[] {1, 2, 3}));
    }

    @Test
    void surfaceUpcallFromManyThreadsAndAThrowingSource() throws Exception {
        List<Thread> threads = new ArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int t = 0; t < 6; t++) {
            int seed = t;
            threads.add(Thread.ofPlatform().start(() -> {
                try {
                    for (int i = 0; i < 200; i++) {
                        long x = (seed * 997L + i * 131L) % 4000 - 2000, z = (i * 71L) % 3000;
                        long y = world.surfaceY(x, z);
                        if (y != 20 + Math.floorMod(x + z, 5)) throw new AssertionError("surface " + y + " at " + x + "," + z);
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            }));
        }
        for (Thread t : threads) t.join();
        if (failure.get() != null) throw new AssertionError(failure.get());
        // A throwing source reads as "unknown": no crash, nothing known.
        try (CubeWorld w = CubeWorld.create(-128, 128, AIR, (cx, cz, sky, top) -> {
            throw new IllegalStateException("surface failure (expected by the test)");
        })) {
            assertEquals(-128, w.surfaceY(1, 1));
            assertEquals(CubeWorld.MISSING, w.get(1, 100, 1));
            assertFalse(w.topRange(0, 0).known());
        }
    }

    @Test
    void storeRoundTrip(@TempDir Path dir) {
        assumeTrue(CendaVoxelWorld.hasStore(), "library has no cv_store yet");
        world.createCube(0, 0, 0, STONE);
        byte[] cube = world.encode(0, 0, 0, CubeWorld.PARTS_SAVED);
        world.set(1, 1, 1, AIR);
        byte[] column = world.encodeColumn(0, 0);
        try (CubeStore store = CubeStore.open(dir)) {
            assertNull(store.readCube(0, 0, 0));
            store.writeCube(0, 0, 0, cube);
            store.writeColumn(0, 0, column);
            store.flush();
            assertArrayEquals(cube, store.readCube(0, 0, 0));
            assertArrayEquals(column, store.readColumn(0, 0));
            store.deleteCube(0, 0, 0);
            assertNull(store.readCube(0, 0, 0));
        }
        try (CubeStore again = CubeStore.open(dir)) {
            assertArrayEquals(column, again.readColumn(0, 0));
        }
    }

    @Test
    void faceRulesThroughTheBinding() {
        assumeTrue(CendaVoxelWorld.hasMesher() && CendaVoxelWorld.FACE_RULE_ADD != null, "library without face rules");
        world.createCube(0, 0, 0, AIR);
        world.set(5, 5, 5, GLASS);
        assertEquals(6, world.meshCube(0, 0, 0, 0).cutoutCount());
        // east face toward air culled; top face toward air drawn opaque
        world.addFaceRule(GLASS, -1, 0, CubeWorld.faceBit(4), CubeWorld.FACE_RULE_CULL_WHEN_NEIGHBOUR_IN, AIR);
        world.addFaceRule(GLASS, -1, 0, CubeWorld.faceBit(0), CubeWorld.FACE_RULE_OPAQUE_WHEN_NEIGHBOUR_IN, AIR, STONE);
        CubeMesh m = world.meshCube(0, 0, 0, CubeMesh.GREEDY);
        assertEquals(1, m.opaqueCount());
        assertEquals(4, m.cutoutCount());
        // a rule whose neighbour set never matches changes nothing
        world.clearFaceRules(GLASS);
        world.addFaceRule(GLASS, -1, 0, CubeWorld.ALL_FACES, CubeWorld.FACE_RULE_CULL_WHEN_NEIGHBOUR_IN, STONE);
        assertEquals(6, world.meshCube(0, 0, 0, 0).cutoutCount());
        world.clearFaceRules(-1);
        assertThrows(IllegalArgumentException.class,
            () -> world.addFaceRule(GLASS, -1, 0, 0, CubeWorld.FACE_RULE_CULL_WHEN_NEIGHBOUR_IN, AIR));
        assertThrows(IllegalArgumentException.class, () -> world.addFaceRule(GLASS, -1, 0, CubeWorld.ALL_FACES, 3, AIR));
        assertThrows(IllegalArgumentException.class,
            () -> world.addFaceRule(GLASS, -1, 0, CubeWorld.ALL_FACES, CubeWorld.FACE_RULE_CULL_WHEN_NEIGHBOUR_IN));
    }

    @Test
    void mesherModelsFluidsAndTheWalkThroughTheBinding() {
        assumeTrue(CendaVoxelWorld.hasMesher(), "library without the native mesher");
        int host = 3, water = 4;
        world.setBlockTable(new short[]{FLAGS[0], FLAGS[1], FLAGS[2], (short) BlockFlags.HOST_GEOMETRY,
            (short) (BlockFlags.HOST_GEOMETRY | BlockFlags.TRANSPARENT | BlockFlags.TRANSLUCENT)});
        world.createCube(0, 0, 0, AIR);
        world.set(5, 5, 5, STONE);
        world.set(9, 5, 5, GLASS);
        world.set(12, 5, 5, host);
        world.set(14, 5, 5, water);
        CubeMesh m = world.meshCube(0, 0, 0, CubeMesh.SMOOTH_LIGHTING | CubeMesh.GREEDY);
        assertNotNull(m);
        assertEquals(6, m.opaqueCount());
        assertEquals(6, m.cutoutCount());
        assertEquals(2, m.hostCellCount());   // the host cell and the not-yet-configured water
        assertEquals(0x7FFF, m.visibility());

        // a model bound to the host block takes it off the host list; so does a fluid
        float[] uv = {0, 0, 1, 0, 1, 1, 0, 1};
        world.defineModel(7, List.of(new CubeWorld.ModelQuad(
            new float[]{0, 1, 1, 1, 1, 1, 1, 1, 0, 0, 1, 0}, uv, new float[]{0, 1, 0}, 3, 0, 0)));
        world.bindBlockModel(host, -1, 0, 7);
        world.setFluid(1, water, new float[]{0.875f, 0.765625f, 0.65625f, 0.546875f, 0.4375f, 0.328125f, 0.21875f, 0.109375f});
        world.setBlockFaces(STONE, new CubeWorld.FaceTexture[]{
            new CubeWorld.FaceTexture(10, 0, false), new CubeWorld.FaceTexture(11, 1, false),
            new CubeWorld.FaceTexture(12, 2, false), new CubeWorld.FaceTexture(13, 3, false),
            new CubeWorld.FaceTexture(14, 4, false), new CubeWorld.FaceTexture(15, 5, true)});
        m = world.meshCube(0, 0, 0, CubeMesh.SMOOTH_LIGHTING | CubeMesh.GREEDY);
        assertEquals(0, m.hostCellCount());
        world.bindBlockModel(host, -1, 0, -1);
        world.removeFluid(water);
        assertEquals(2, world.meshCube(0, 0, 0, 0).hostCellCount());
        assertThrows(IllegalArgumentException.class, () -> world.defineModel(-1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> world.setBlockFaces(STONE, new CubeWorld.FaceTexture[]{
            new CubeWorld.FaceTexture(0, 9, false), new CubeWorld.FaceTexture(0, 0, false),
            new CubeWorld.FaceTexture(0, 0, false), new CubeWorld.FaceTexture(0, 0, false),
            new CubeWorld.FaceTexture(0, 0, false), new CubeWorld.FaceTexture(0, 0, false)}));

        // visibility + walk
        assertEquals(0x7FFF, world.cubeVisibility(0, 0, 0));
        assertEquals(CubeWorld.MISSING, world.cubeVisibility(9, 9, 9));
        CubeWorld.VisibleCubes v = world.visibleCubes(0, 0, 0, 1, null, 64);
        assertEquals(3, v.visible().length);
        assertTrue(v.missingTotal() > 0);   // unloaded cubes below the generator surface are reported
    }

    @Test
    void closedWorldRefusesCalls() {
        CubeWorld w = CubeWorld.create(-32, 32, AIR, null);
        w.close();
        w.close();
        assertThrows(IllegalStateException.class, () -> w.get(0, 0, 0));
    }

    @Test
    void visibleCubesIntoReusableBuffersMatchesTheAllocatingWalkWithoutAllocating() {
        assumeTrue(CendaVoxelWorld.hasMesher(), "library without the native mesher");
        // A 3x3x3 block of air cubes around the camera plus stone cubes beyond: the walk reaches present
        // cubes and stops at unknown missing ones below the generator surface.
        for (int cx = -2; cx <= 2; cx++) {
            for (int cy = 0; cy <= 2; cy++) {
                for (int cz = -2; cz <= 2; cz++) {
                    world.createCube(cx, cy, cz, Math.abs(cx) == 2 || Math.abs(cz) == 2 ? STONE : AIR);
                }
            }
        }
        float[] planes = null;
        CubeWorld.VisibleCubes reference = world.visibleCubes(0, 1, 0, 3, planes, 1 << 16);
        assertTrue(reference.visible().length / 3 > 8, "walk reaches several cubes");
        assertTrue(reference.missingTotal() > 2, "walk reports missing cubes");

        // Tiny lists: both must grow once, then the result equals the allocating walk.
        VisibleCubeBuffers out = new VisibleCubeBuffers(1, 1);
        int n = world.visibleCubes(0, 1, 0, 3, planes, out);
        assertEquals(reference.visible().length / 3, n);
        assertEquals(n, out.visibleCount());
        assertEquals(reference.missingTotal(), out.missingCount());
        assertTrue(out.growCount() >= 1);
        for (int i = 0; i < n; i++) {
            assertEquals(reference.visible()[3 * i], out.visibleX(i));
            assertEquals(reference.visible()[3 * i + 1], out.visibleY(i));
            assertEquals(reference.visible()[3 * i + 2], out.visibleZ(i));
        }
        for (int i = 0; i < out.missingCount(); i++) {
            assertEquals(reference.missing()[3 * i], out.missingX(i));
            assertEquals(reference.missing()[3 * i + 1], out.missingY(i));
            assertEquals(reference.missing()[3 * i + 2], out.missingZ(i));
        }
        java.nio.LongBuffer view = out.visibleBuffer();
        assertEquals(3 * n, view.remaining());
        assertEquals(reference.visible()[3 * n - 1], view.get(3 * n - 1));

        // Steady state: no growth, no per-call garbage.
        int grows = out.growCount();
        java.lang.foreign.MemorySegment visibleBefore = out.visibleSegment();
        for (int i = 0; i < 200; i++) {
            world.visibleCubes(0, 1, 0, 3, planes, out);   // warm the call path up
        }
        long before = allocatedBytes();
        for (int i = 0; i < 1000; i++) {
            world.visibleCubes(0, 1, 0, 3, planes, out);
        }
        long after = allocatedBytes();
        assertEquals(grows, out.growCount());
        assertTrue(visibleBefore == out.visibleSegment(), "same native list every walk");
        if (before >= 0 && after >= 0) {
            long perCall = (after - before) / 1000;
            assertTrue(perCall < 64, "bytes allocated per walk: " + perCall);
        }

        // Frustum planes (copied into the buffers' native block): a plane facing +x keeps x >= 0 only.
        float[] halfSpace = new float[24];
        for (int p = 0; p < 6; p++) {
            halfSpace[p * 4 + 3] = 1;              // 0x + 0y + 0z + 1 >= 0: always inside
        }
        halfSpace[0] = 1;
        halfSpace[3] = 0;                          // x >= 0 (camera-cube-relative blocks)
        int half = world.visibleCubes(0, 1, 0, 3, halfSpace, out);
        assertTrue(half > 0 && half < n, half + " of " + n);

        // The raw call reports an overflow as -needed and writes nothing.
        try (java.lang.foreign.Arena a = java.lang.foreign.Arena.ofConfined()) {
            java.lang.foreign.MemorySegment small = a.allocate(24, 8);
            java.lang.foreign.MemorySegment count = a.allocate(8, 8);
            long r = world.visibleCubesInto(0, 1, 0, 3, java.lang.foreign.MemorySegment.NULL, small,
                java.lang.foreign.MemorySegment.NULL, count);
            assertEquals(-n, r);
        }
    }

    @Test
    void allocatingWalkTruncatesMissingInsteadOfSpinning() {
        assumeTrue(CendaVoxelWorld.hasMesher(), "library without the native mesher");
        world.createCube(0, 1, 0, AIR);
        CubeWorld.VisibleCubes v = world.visibleCubes(0, 1, 0, 2, null, 1);
        assertTrue(v.missingTotal() > 1, "several missing cubes: " + v.missingTotal());
        assertEquals(3, v.missing().length, "the record keeps missingCap cubes");
    }

    /** This thread's allocated bytes (HotSpot ThreadMXBean, reached reflectively: the test module does not read
     *  jdk.management), or -1 when unavailable. */
    private static long allocatedBytes() {
        try {
            Class<?> factory = Class.forName("java.lang.management.ManagementFactory");
            Object bean = factory.getMethod("getThreadMXBean").invoke(null);
            Class<?> hotspot = Class.forName("com.sun.management.ThreadMXBean");
            return (long) hotspot.getMethod("getCurrentThreadAllocatedBytes").invoke(bean);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return -1;
        }
    }
}
