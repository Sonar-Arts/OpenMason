package com.openmason.engine.voxel.world;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The native streamer ({@code cv_stream_*}) through its FFM binding, with the engine's toy generator
 * ({@code cv_test_toy_generator_create}): a local stream generating, populating, meshing and saving around a
 * viewer; a REMOTE stream fed from it by the host loop ({@link CubeStream#missing} -> encode/decode); event
 * views, mesh hand-out as native segments, stats, unloading. The native suite
 * ({@code cenda/tests/voxel_stream_test.cpp}) covers the rules in depth; this checks marshalling.
 */
class CubeStreamTest {

    private static final short[] FLAGS = {
        0,
        (short) (BlockFlags.CUBE | BlockFlags.OPAQUE | BlockFlags.SKY_OPAQUE | BlockFlags.VIS_OPAQUE),
        (short) (BlockFlags.CUBE | BlockFlags.OPAQUE | BlockFlags.SKY_OPAQUE | BlockFlags.VIS_OPAQUE),
        (short) (BlockFlags.TRANSLUCENT | BlockFlags.TRANSPARENT),
        (short) (BlockFlags.CUBE | BlockFlags.OPAQUE | BlockFlags.VIS_OPAQUE),
        (short) (BlockFlags.CUBE | BlockFlags.TRANSPARENT),
    };

    private MemorySegment generator;

    @BeforeEach
    void setUp() {
        assumeTrue(CendaVoxelWorld.isAvailable(), () -> "Cenda voxel world unavailable: " + CendaVoxelWorld.unavailableReason());
        assumeTrue(CubeStream.isAvailable() && CendaVoxelWorld.TEST_TOY_GENERATOR_CREATE != null,
            "this Cenda library has no streamer (rebuild it)");
        generator = CubeStream.createTestGenerator(7);
    }

    @AfterEach
    void tearDown() {
        if (generator != null) CubeStream.destroyTestGenerator(generator);
    }

    private CubeWorld newWorld() {
        CubeWorld w = CubeWorld.createForGenerator(-384, 640, 0, generator);
        w.setBlockTable(FLAGS);
        w.defineLayer(0, CubeWorld.LAYER_SAVED | CubeWorld.LAYER_NETWORK);
        return w;
    }

    /** Event bookkeeping with the contract checks of every tick. */
    private static final class Seen {
        final Set<CubePos> installed = new HashSet<>();
        final Set<Long> ready = new HashSet<>();
        final Map<CubePos, Integer> changed = new HashMap<>();
        final Map<CubePos, Integer> remeshed = new HashMap<>();
        final Set<CubePos> changedLastTick = new HashSet<>();
        int meshes;
        long meshBytes;
        int unloads;
        int readies;
        long ticks;
    }

    private static long col(long cx, long cz) {
        return cx * 1_000_003L + cz;
    }

    private static void observe(CubeStream stream, CubeWorld world, StreamEvents ev, Seen seen) {
        seen.ticks++;
        seen.changedLastTick.clear();
        Set<CubePos> installedNow = new HashSet<>();
        for (int i = 0; i < ev.count(); i++) {
            CubePos p = new CubePos(ev.cx(i), ev.cy(i), ev.cz(i));
            switch (ev.type(i)) {
                case CubeStream.EV_INSTALLED -> {
                    assertTrue(seen.installed.add(p), "installed twice: " + p);
                    assertTrue(world.hasCube(p.x(), p.y(), p.z()));
                    installedNow.add(p);
                }
                case CubeStream.EV_UNLOAD_PENDING -> assertTrue(seen.installed.contains(p));
                case CubeStream.EV_UNLOADED -> {
                    assertTrue(seen.installed.remove(p));
                    assertFalse(world.hasCube(p.x(), p.y(), p.z()));
                    seen.unloads++;
                }
                case CubeStream.EV_REMESHED -> {
                    StreamMesh m = stream.mesh(ev.mesh(i));
                    assertEquals(p, new CubePos(m.cx(), m.cy(), m.cz()));
                    assertTrue(m.revision() > 0 && m.revision() <= world.cubeRevision(p.x(), p.y(), p.z()));
                    long bytes = 0;
                    for (int l = 0; l < 3; l++) {
                        MemorySegment q = m.quads(l);
                        assertEquals((long) m.quadCount(l) * StreamMesh.QUAD16_BYTES, q.byteSize());
                        if (q.byteSize() > 0) q.get(ValueLayout.JAVA_LONG_UNALIGNED, q.byteSize() - 8);   // readable
                        bytes += q.byteSize();
                    }
                    assertEquals((long) m.waterCount() * StreamMesh.WATERQUAD16_BYTES, m.water().byteSize());
                    assertEquals((long) m.stampVertexCount() * StreamMesh.COMPACT20_BYTES, m.stampVertices().byteSize());
                    assertEquals(m.stampIndexCount() * 4L, m.stampIndices().byteSize());
                    assertEquals(bytes + m.water().byteSize() + m.stampVertices().byteSize() + m.stampIndices().byteSize(),
                        m.byteSize());
                    if (!m.isEmpty()) assertTrue(m.aabbMax(1) >= m.aabbMin(1));
                    seen.meshes++;
                    seen.meshBytes += m.byteSize();
                    seen.remeshed.merge(p, 1, Integer::sum);
                    if ((seen.meshes & 1) == 0) stream.releaseMesh(ev.mesh(i));   // early release works too
                }
                case CubeStream.EV_COLUMN_READY -> {
                    assertEquals(0, ev.cy(i));
                    assertTrue(seen.ready.add(col(p.x(), p.z())));
                    seen.readies++;
                }
                case CubeStream.EV_COLUMN_UNREADY -> assertTrue(seen.ready.remove(col(p.x(), p.z())));
                case CubeStream.EV_CHANGED -> {
                    assertEquals(-1, ev.mesh(i));
                    assertTrue(seen.installed.contains(p) && !installedNow.contains(p), "CHANGED before INSTALLED: " + p);
                    assertTrue(world.hasCube(p.x(), p.y(), p.z()));
                    assertTrue(seen.changedLastTick.add(p), "two CHANGED in one tick: " + p);
                    seen.changed.merge(p, 1, Integer::sum);
                }
                default -> throw new AssertionError("unknown event " + ev.get(i));
            }
        }
    }

    /** Ticks until no work is left and two plans changed nothing (or, without viewers, 300 ms passed). */
    private static void settle(CubeStream stream, CubeWorld world, Seen seen, boolean viewers, Runnable perTick)
        throws InterruptedException {
        long end = System.currentTimeMillis() + 60_000;
        long idleSince = -1, basePlans = 0, baseChanges = -1;
        while (System.currentTimeMillis() < end) {
            observe(stream, world, stream.tick(StreamBudget.UNLIMITED), seen);
            if (perTick != null) perTick.run();
            StreamStats st = stream.stats();
            if (st.workIdle() && st.planPending() == 0) {
                if (idleSince < 0 || st.levelChangesTotal() != baseChanges) {
                    idleSince = System.currentTimeMillis();
                    basePlans = st.plansTotal();
                    baseChanges = st.levelChangesTotal();
                } else if (viewers ? st.plansTotal() >= basePlans + 2 : System.currentTimeMillis() - idleSince >= 300) {
                    return;
                }
            } else if (!st.workIdle()) {
                idleSince = -1;
            }
            Thread.sleep(1);
        }
        throw new AssertionError("stream did not settle: " + stream.stats());
    }

    @Test
    void localStreamGeneratesPopulatesMeshesAndSaves(@TempDir Path dir) throws InterruptedException {
        try (CubeWorld world = newWorld(); CubeStore store = CubeStore.open(dir)) {
            Seen seen = new Seen();
            CubePos probe;
            try (CubeStream stream = CubeStream.create(world, store, generator, 4,
                CubeStream.MESH_SMOOTH_LIGHTING | CubeStream.MESH_GREEDY)) {
                assertNotNull(stream.world());
                stream.setCenters(StreamCenter.of(8, 20, 8, 64, 32));
                settle(stream, world, seen, true, null);
                StreamStats st = stream.stats();
                assertTrue(st.generatedTotal() > 0);
                assertTrue(st.populatedTotal() > 0);
                assertTrue(st.cubesWanted() > 0 && st.cubesNeeded() >= st.cubesWanted());
                assertEquals(world.cubeCount(), st.cubesLoaded());
                assertEquals(world.cubeCount(), seen.installed.size());
                assertTrue(seen.meshes > 0 && seen.meshBytes > 0);
                assertEquals(st.columnsReady(), seen.ready.size());
                assertTrue(seen.readies > 0);
                assertEquals(0, st.errorsTotal());
                assertEquals(0, stream.missing().length);
                // Trees from population exist (log id 4).
                long logs = 0;
                short[] ids = new short[CubeWorld.CUBE_EDGE * CubeWorld.CUBE_EDGE * CubeWorld.CUBE_EDGE];
                for (CubePos p : seen.installed) {
                    if (!world.readIds(p.x(), p.y(), p.z(), ids)) continue;
                    for (short id : ids) if (id == 4) logs++;
                }
                assertTrue(logs > 0);
                // An edit is re-meshed.
                long sy = world.surfaceY(8, 8);
                world.set(8, sy, 8, 1);
                long rev = world.cubeRevision(0, sy >> 5, 0);
                int before = seen.meshes;
                for (int i = 0; i < 200 && seen.meshes == before; i++) {
                    observe(stream, world, stream.tick(new StreamBudget(8, 8, 1 << 20, 8)), seen);
                    Thread.sleep(2);
                }
                assertTrue(seen.meshes > before);
                assertTrue(world.cubeRevision(0, sy >> 5, 0) >= rev);
                probe = new CubePos(0, sy >> 5, 0);
                stream.saveAll();
                byte[] rec = store.readCube(probe.x(), probe.y(), probe.z());
                assertNotNull(rec);
                assertArrayEquals(world.encode(probe.x(), probe.y(), probe.z(), CubeWorld.PARTS_SAVED), rec);
                assertNotNull(store.readColumn(0, 0));

                // No viewers: everything unloads (saved first), columns go.
                stream.setCenters(List.of());
                settle(stream, world, seen, false, null);
                assertEquals(0, world.cubeCount());
                assertTrue(seen.installed.isEmpty() && seen.ready.isEmpty());
                assertTrue(stream.stats().savedTotal() > 0);
            }
            assertNotNull(store.readCube(probe.x(), probe.y(), probe.z()));
        }
    }

    @Test
    void noMeshStreamReportsChangesAndReadinessWithoutMeshes() throws InterruptedException {
        try (CubeWorld world = newWorld()) {
            Seen seen = new Seen();
            try (CubeStream stream = CubeStream.create(world, null, generator, 4,
                CubeStream.MESH_SMOOTH_LIGHTING | CubeStream.MESH_GREEDY | CubeStream.NO_MESH)) {
                stream.setCenters(StreamCenter.of(8, 20, 8, 64, 32));
                settle(stream, world, seen, true, null);
                StreamStats st = stream.stats();
                assertTrue(st.populatedTotal() > 0);
                assertEquals(0, st.meshedTotal());
                assertEquals(0, seen.meshes);
                assertTrue(st.columnsReady() > 0);
                assertEquals(st.columnsReady(), seen.ready.size());
                assertFalse(seen.changed.isEmpty(), "populations writing into installed cubes are reported");
                assertEquals(0, stream.remeshAll());

                // An edit: exactly one CHANGED, next tick, for its cube.
                long sy = world.surfaceY(8, 8);
                int old = world.get(9, sy, 9);
                world.set(9, sy, 9, old == 1 ? 0 : 1);
                world.set(10, sy, 10, world.get(10, sy, 10) == 1 ? 0 : 1);
                observe(stream, world, stream.tick(StreamBudget.UNLIMITED), seen);
                assertEquals(Set.of(new CubePos(0, sy >> 5, 0)), seen.changedLastTick);
                observe(stream, world, stream.tick(StreamBudget.UNLIMITED), seen);
                assertTrue(seen.changedLastTick.isEmpty());
                assertEquals(0, seen.meshes);
            }
        }
    }

    @Test
    void remeshAllHandsEveryMeshOutAgain() throws InterruptedException {
        try (CubeWorld world = newWorld()) {
            Seen seen = new Seen();
            try (CubeStream stream = CubeStream.create(world, null, generator, 4,
                CubeStream.MESH_SMOOTH_LIGHTING | CubeStream.MESH_GREEDY)) {
                stream.setCenters(StreamCenter.of(8, 20, 8, 64, 32));
                settle(stream, world, seen, true, null);
                Set<CubePos> withMesh = new HashSet<>(seen.remeshed.keySet());
                assertFalse(withMesh.isEmpty());
                CubeWorld.FaceTexture[] faces = new CubeWorld.FaceTexture[6];
                java.util.Arrays.fill(faces, new CubeWorld.FaceTexture(77, 0, false));
                world.setBlockFaces(1, faces);   // dirties nothing
                seen.remeshed.clear();
                assertTrue(stream.remeshAll() >= withMesh.size());
                StreamBudget budget = new StreamBudget(8, 3, 1 << 20, 8);
                long end = System.currentTimeMillis() + 30_000;
                while (seen.remeshed.size() < withMesh.size() && System.currentTimeMillis() < end) {
                    StreamEvents ev = stream.tick(budget);
                    int n = 0;
                    for (int i = 0; i < ev.count(); i++) if (ev.type(i) == CubeStream.EV_REMESHED) n++;
                    assertTrue(n <= 3, "mesh budget");
                    observe(stream, world, ev, seen);
                    Thread.sleep(1);
                }
                for (int i = 0; i < 20; i++) {   // and nothing twice
                    observe(stream, world, stream.tick(budget), seen);
                    Thread.sleep(1);
                }
                assertEquals(withMesh, seen.remeshed.keySet());
                assertTrue(seen.remeshed.values().stream().allMatch(c -> c == 1));
            }
        }
    }

    @Test
    void detailRadiusPicksTheMeshModePerCube() throws InterruptedException {
        assumeTrue(CendaVoxelWorld.STREAM_SET_DETAIL_RADIUS != null, "this Cenda library has no detail radius");
        try (CubeWorld world = newWorld()) {
            Seen seen = new Seen();
            try (CubeStream stream = CubeStream.create(world, null, generator, 4,
                CubeStream.MESH_SMOOTH_LIGHTING | CubeStream.MESH_GREEDY)) {
                stream.setCenters(StreamCenter.of(8, 20, 8, 112, 32));
                settle(stream, world, seen, true, null);
                short[] flags = java.util.Arrays.copyOf(FLAGS, 7);
                flags[6] = (short) (BlockFlags.CUBE | BlockFlags.OPAQUE | BlockFlags.SKY_OPAQUE
                    | BlockFlags.VIS_OPAQUE | BlockFlags.DETAIL);
                world.setBlockTable(flags);
                float[] top = {0, 1, 0, 1, 1, 0, 0, 1, 1, 1, 1, 1};
                world.defineModel(3, List.of(new CubeWorld.ModelQuad(top, new float[8], new float[] {0, 1, 0}, 9, 0, 0)));
                world.bindBlockModel(6, -1, 0, 3);
                CubePos near = placeFloating(world, 12, 12);
                CubePos far = placeFloating(world, 70, 8);
                stream.setDetailRadius(32);
                stream.remeshAll();
                Map<CubePos, Integer> lastFlags = new HashMap<>();
                long end = System.currentTimeMillis() + 30_000;
                while ((!lastFlags.containsKey(near) || !lastFlags.containsKey(far)) && System.currentTimeMillis() < end) {
                    StreamEvents ev = stream.tick(null);
                    for (int i = 0; i < ev.count(); i++) {
                        if (ev.type(i) != CubeStream.EV_REMESHED) continue;
                        StreamMesh m = stream.mesh(ev.mesh(i));
                        if (m.hasDetail()) lastFlags.put(new CubePos(m.cx(), m.cy(), m.cz()), m.flags());
                    }
                    Thread.sleep(1);
                }
                assertEquals(12, lastFlags.get(near) & 12, "near: exposed detail, built with detail");
                assertEquals(4, lastFlags.get(far) & 12, "far: exposed detail, built without detail");
            }
        }
    }

    /** Puts block 6 in the first air cell with air above and below (an installed cube) of column (x, z). */
    private static CubePos placeFloating(CubeWorld world, long x, long z) {
        for (long y = 400; y > -380; y--) {
            if (!world.hasCube(x >> 5, y >> 5, z >> 5)) continue;
            if (world.get(x, y, z) == 0 && world.get(x, y - 1, z) == 0 && world.get(x, y + 1, z) == 0) {
                world.set(x, y, z, 6);
                return new CubePos(x >> 5, y >> 5, z >> 5);
            }
        }
        throw new AssertionError("no air cell in column " + x + ", " + z);
    }

    @Test
    void remoteStreamMeshesWhatTheHostDecodes() throws InterruptedException {
        try (CubeWorld server = newWorld(); CubeWorld client = newWorld()) {
            Seen serverSeen = new Seen();
            try (CubeStream host = CubeStream.create(server, null, generator, 4, 3);
                 CubeStream remote = CubeStream.createRemote(client, 2, 3)) {
                host.setCenters(StreamCenter.of(8, 20, 8, 96, 32));
                settle(host, server, serverSeen, true, null);

                Seen seen = new Seen();
                Set<Long> sentColumns = new HashSet<>();
                int[] sent = {0};
                Runnable feed = () -> {
                    long[] miss = remote.missing();
                    for (int i = 0; i + 2 < miss.length && i < 64 * 3; i += 3) {
                        long x = miss[i], y = miss[i + 1], z = miss[i + 2];
                        if (sentColumns.add(col(x, z))) {
                            byte[] c = server.encodeColumn(x, z);
                            if (c != null && c.length > 0) client.decodeColumn(x, z, c);
                        }
                        if (!server.hasCube(x, y, z)) continue;
                        client.decode(x, y, z, server.encode(x, y, z, CubeWorld.PARTS_NETWORK));
                        sent[0]++;
                    }
                };
                remote.setCenters(StreamCenter.of(8, 20, 8, 64, 32));
                settle(remote, client, seen, true, feed);
                StreamStats st = remote.stats();
                assertTrue(sent[0] > 0);
                assertEquals(0, st.generatedTotal());
                assertEquals(0, st.populatedTotal());
                assertTrue(seen.installed.size() >= sent[0]);
                assertTrue(seen.meshes > 0);
                assertTrue(st.columnsReady() > 0);
                for (CubePos p : seen.installed) {
                    assertArrayEquals(server.encode(p.x(), p.y(), p.z(), CubeWorld.PARTS_NETWORK),
                        client.encode(p.x(), p.y(), p.z(), CubeWorld.PARTS_NETWORK));
                }
                remote.setCenters(List.of());
                settle(remote, client, seen, false, null);
                assertEquals(0, client.cubeCount());
                assertEquals(0, remote.stats().savedTotal());
            }
        }
    }

    @Test
    void argumentsAndLifecycle() {
        try (CubeWorld world = newWorld()) {
            assertThrows(IllegalArgumentException.class, () -> CubeStream.create(world, null, null, 1, 0));
            CubeStream stream = CubeStream.createRemote(world, 1, 0);
            assertThrows(IllegalArgumentException.class, () -> stream.mesh(0));
            StreamEvents ev = stream.tick(null);
            assertEquals(0, ev.count());
            assertThrows(IndexOutOfBoundsException.class, () -> ev.type(0));
            assertEquals(0, stream.missing().length);
            stream.saveAll();   // REMOTE: nothing to save
            stream.close();
            stream.close();     // idempotent
            assertThrows(IllegalStateException.class, () -> stream.tick(null));
            // The world answers never-generated columns from the generator's column_surface (native, no upcall).
            assertEquals(0, world.topY(-20 * 32, 0));   // sea column: the water surface
            assertTrue(world.topY(-20 * 32, 0) >= world.surfaceY(-20 * 32, 0));
            assertTrue(world.topRange(-20, 0).known());
        }
    }

    @Test
    void nativeSurfaceFactory() {
        assumeTrue(CendaVoxelWorld.WORLD_CREATE_WITH_GENERATOR != null, "this Cenda library has no native surface factory");
        try (CubeWorld world = newWorld()) {
            // Never-generated columns answer straight from the generator: the sea and the plateau differ.
            long sea = world.topY(-20 * 32, 0);
            assertEquals(0, sea);
            assertTrue(world.surfaceY(-20 * 32, 0) <= sea);
            CubeWorld.TopRange r = world.topRange(-20, 0);
            assertTrue(r.known());
            assertTrue(r.topMin() <= sea && sea <= r.topMax());
            // Known air above the generator's top, unknown below it.
            assertEquals(0, world.get(-20 * 32, 40, 0));
            assertEquals(CubeWorld.MISSING, world.get(-20 * 32, -40, 0));
        }
        assertThrows(IllegalArgumentException.class, () -> CubeWorld.createForGenerator(0, 64, 0, null));
        assertThrows(IllegalArgumentException.class, () -> CubeWorld.createForGenerator(0, 64, 0, MemorySegment.NULL));
        // A generator built against another CV_ABI_VERSION is refused.
        try (java.lang.foreign.Arena a = java.lang.foreign.Arena.ofConfined()) {
            MemorySegment copy = a.allocate(CendaVoxelWorld.GENERATOR_BYTES, 8);
            copy.copyFrom(generator.reinterpret(CendaVoxelWorld.GENERATOR_BYTES));
            copy.set(ValueLayout.JAVA_INT, 0, 999);
            assertThrows(IllegalArgumentException.class, () -> CubeWorld.createForGenerator(0, 64, 0, copy));
        }
    }
}
