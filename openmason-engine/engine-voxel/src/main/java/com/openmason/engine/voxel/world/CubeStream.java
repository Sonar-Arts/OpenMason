package com.openmason.engine.voxel.world;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.ref.Cleaner;
import java.util.List;

import static com.openmason.engine.voxel.world.CendaVoxelWorld.ADDR;
import static com.openmason.engine.voxel.world.CendaVoxelWorld.I32;
import static com.openmason.engine.voxel.world.CendaVoxelWorld.I64;
import static com.openmason.engine.voxel.world.CendaVoxelWorld.failure;
import static com.openmason.engine.voxel.world.CendaVoxelWorld.isNeed;
import static com.openmason.engine.voxel.world.CendaVoxelWorld.needed;
import static com.openmason.engine.voxel.world.CendaVoxelWorld.require;
import static com.openmason.engine.voxel.world.CendaVoxelWorld.rethrow;

/**
 * The native streamer of a {@link CubeWorld} ({@code cv_stream}, OpenMason #121): decides which cubes exist
 * around the viewers (surface-following shape, {@link StreamCenter}), loads them from a {@link CubeStore} or
 * generates them with the game's native generator on its own worker pool, populates them (2x2x2 rule),
 * meshes them into GPU-ready records, saves and unloads them, and reports it all as {@link StreamEvents}.
 * Rules: {@code voxel_world.h} "STREAMER RULES". Java only marshals.
 *
 * <p><b>Modes.</b> {@link #create} with a generator (a native {@code cv_generator*} the game's worldgen lib
 * exports) is the authority (server / singleplayer). {@link #createRemote} has no generator: cubes arrive
 * through {@link CubeWorld#decode} (+ {@link CubeWorld#decodeColumn}) from the host, {@link #missing} says
 * what to request; meshing, events and unloading are the same, nothing is saved.
 *
 * <p><b>Threads.</b> {@link #tick}, {@link #mesh} and {@link #releaseMesh} on the OWNER thread only (the one
 * that owns the world's game state; it receives the events). {@link #setCenters}, {@link #stats},
 * {@link #missing} and {@link #saveAll} from any thread. {@link #close} must not race other calls and must
 * run before the world and the store are closed. The stream owns the world's dirty set (the host must not
 * call {@link CubeWorld#drainDirty} while a stream runs).
 *
 * <p><b>The world's surface.</b> Create the world with {@link CubeWorld#createForGenerator}(…, generator) so
 * known air, the visibility walk and never-generated columns agree with the generator (the world then calls the
 * generator's {@code column_surface} natively, no upcall).
 */
public final class CubeStream implements AutoCloseable {

    /** Event types ({@code CV_EV_*}). */
    public static final int EV_INSTALLED = 1;
    public static final int EV_UNLOAD_PENDING = 2;
    public static final int EV_UNLOADED = 3;
    public static final int EV_REMESHED = 4;
    public static final int EV_COLUMN_READY = 5;
    public static final int EV_COLUMN_UNREADY = 6;
    /**
     * {@code CV_EV_CHANGED}: an installed cube's content changed after its INSTALLED event - a population
     * finished writing into it, or a write since the last tick (edits, decodes over it). At most once per cube
     * per tick, sent in meshing and {@link #NO_MESH} streams alike: the physics / host-cache invalidation
     * signal (REMESHED is not). Rules: {@code voxel_world.h} "CHANGED". Libraries older than 2026-11-17 never
     * send it.
     */
    public static final int EV_CHANGED = 7;

    /** Mesh flags ({@code CV_MESH_*}). */
    public static final int MESH_SMOOTH_LIGHTING = 1;
    public static final int MESH_GREEDY = 2;
    /** {@code CV_MESH_DETAIL}: {@link BlockFlags#DETAIL} blocks draw their model (streams set it per cube). */
    public static final int MESH_DETAIL = 4;
    /**
     * {@code CV_STREAM_NO_MESH} (or it into the mesh flags): the stream never builds GPU meshes (server /
     * integrated world). No REMESHED events; {@link #EV_CHANGED} still reports content changes and
     * COLUMN_READY means "every band cube installed and complete". An older library ignores the bit and meshes.
     */
    public static final int NO_MESH = 0x80000000;

    private static final Cleaner CLEANER = Cleaner.create();

    private final CubeWorld world;
    private final Cleaner.Cleanable cleanable;
    private final Arena arena = Arena.ofShared();   // owner-side scratch, freed by close()
    private final MemorySegment eventsOut;
    private final MemorySegment budgetSeg;
    private final StreamEvents events = new StreamEvents();
    private volatile MemorySegment stream;

    private CubeStream(CubeWorld world, CubeStore store, MemorySegment generator, int workerThreads, int meshFlags) {
        this.world = world;
        MethodHandle create = require(CendaVoxelWorld.STREAM_CREATE, "cv_stream_create");
        try (Arena a = Arena.ofConfined()) {
            MemorySegment desc = a.allocate(CendaVoxelWorld.STREAM_DESC_BYTES, 8);
            desc.set(ADDR, 0, store == null ? MemorySegment.NULL : store.pointer());
            desc.set(ADDR, 8, generator == null ? MemorySegment.NULL : generator);
            desc.set(I32, 16, workerThreads);
            desc.set(I32, 20, meshFlags);
            MemorySegment s = (MemorySegment) create.invokeExact(world.pointer(), desc);
            if (s.equals(MemorySegment.NULL)) {
                throw new IllegalArgumentException("cv_stream_create failed: " + CendaVoxelWorld.lastError());
            }
            this.stream = s;
        } catch (Throwable t) {
            arena.close();
            throw rethrow(t);
        }
        this.eventsOut = arena.allocate(ADDR);
        this.budgetSeg = arena.allocate(StreamBudget.BYTES, 8);
        // The action keeps the world and store reachable, so their own cleaners never run first.
        this.cleanable = CLEANER.register(this, new Destroy(stream, world, store));
    }

    /**
     * A streamer that generates and (with a store) loads and saves.
     *
     * @param world         the world (created with {@link CubeWorld#createForGenerator}(…, generator))
     * @param store         the region store, or null (nothing is loaded or saved)
     * @param generator     a native {@code cv_generator*} (the game lib's, or {@link #createTestGenerator});
     *                      it must outlive the stream
     * @param workerThreads worker threads ({@code <= 0}: hardware threads - 2, at least 1)
     * @param meshFlags     {@link #MESH_SMOOTH_LIGHTING} | {@link #MESH_GREEDY}, or {@link #NO_MESH}
     */
    public static CubeStream create(CubeWorld world, CubeStore store, MemorySegment generator, int workerThreads,
                                    int meshFlags) {
        if (world == null || generator == null || generator.equals(MemorySegment.NULL)) {
            throw new IllegalArgumentException("world and generator are required (createRemote for clients)");
        }
        return new CubeStream(world, store, generator, workerThreads, meshFlags);
    }

    /** A REMOTE (client) streamer: no generation, loading or saving; the host decodes cubes into the world. */
    public static CubeStream createRemote(CubeWorld world, int workerThreads, int meshFlags) {
        if (world == null) throw new IllegalArgumentException("world is required");
        return new CubeStream(world, null, null, workerThreads, meshFlags);
    }

    private record Destroy(MemorySegment stream, CubeWorld world, CubeStore store) implements Runnable {
        @Override
        public void run() {
            try {
                int ignored = (int) CendaVoxelWorld.STREAM_DESTROY.invokeExact(stream);
            } catch (Throwable ignored) {
                // releasing at exit
            }
        }
    }

    private MemorySegment pointer() {
        MemorySegment s = stream;
        if (s == null) throw new IllegalStateException("CubeStream is closed");
        return s;
    }

    /** The world this stream fills. */
    public CubeWorld world() {
        return world;
    }

    /** Saves everything (when a store exists), waits for the workers and destroys the stream. */
    @Override
    public void close() {
        if (stream != null) {
            stream = null;
            cleanable.clean();
            arena.close();
        }
    }

    // ---- viewers ------------------------------------------------------------------------------------------------

    /** Replaces the viewer set (any thread). Server: one per player; client: one; empty: unload everything. */
    public void setCenters(List<StreamCenter> centers) {
        MethodHandle h = require(CendaVoxelWorld.STREAM_SET_CENTERS, "cv_stream_set_centers");
        try (Arena a = Arena.ofConfined()) {
            int n = centers.size();
            MemorySegment seg = n == 0 ? MemorySegment.NULL : a.allocate(StreamCenter.BYTES * n, 8);
            for (int i = 0; i < n; i++) {
                StreamCenter c = centers.get(i);
                long o = i * StreamCenter.BYTES;
                seg.set(I64, o, c.x());
                seg.set(I64, o + 8, c.y());
                seg.set(I64, o + 16, c.z());
                seg.set(I32, o + 24, c.radiusH());
                seg.set(I32, o + 28, c.radiusV());
                seg.set(I32, o + 32, c.bandBelow());
                seg.set(I32, o + 36, c.bandAbove());
            }
            int r = (int) h.invokeExact(pointer(), seg, n);
            if (r < 0) throw failure("cv_stream_set_centers", r);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public void setCenters(StreamCenter... centers) {
        setCenters(List.of(centers));
    }

    // ---- owner tick ---------------------------------------------------------------------------------------------

    /**
     * OWNER THREAD. Installs finished work within the budget, schedules new work, unloads, hands out meshes.
     * The returned view is reused and valid until the next tick.
     */
    public StreamEvents tick(StreamBudget budget) {
        MethodHandle h = require(CendaVoxelWorld.STREAM_TICK, "cv_stream_tick");
        StreamBudget b = budget == null ? StreamBudget.UNLIMITED : budget;
        budgetSeg.set(I32, 0, b.maxInstalls());
        budgetSeg.set(I32, 4, b.maxMeshes());
        budgetSeg.set(I64, 8, b.maxMeshBytes());
        budgetSeg.set(I32, 16, b.maxUnloads());
        try {
            int n = (int) h.invokeExact(pointer(), budgetSeg, eventsOut);
            if (n < 0) throw failure("cv_stream_tick", n);
            events.reset(eventsOut.get(ADDR, 0), n);
            return events;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** OWNER THREAD. The mesh of a REMESHED event of the current tick (see {@link StreamMesh} lifetime). */
    public StreamMesh mesh(int slot) {
        MethodHandle h = require(CendaVoxelWorld.STREAM_MESH, "cv_stream_mesh");
        try {
            MemorySegment m = (MemorySegment) h.invokeExact(pointer(), slot);
            if (m.equals(MemorySegment.NULL)) throw new IllegalArgumentException("no mesh in slot " + slot + " this tick");
            return new StreamMesh(m);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** OWNER THREAD. Returns a mesh's memory before the next tick (optional: the tick releases the rest). */
    public void releaseMesh(int slot) {
        MethodHandle h = require(CendaVoxelWorld.STREAM_RELEASE_MESH, "cv_stream_release_mesh");
        try {
            h.invokeExact(pointer(), slot);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * Re-meshes every installed cube ({@code cv_stream_remesh_all}) after a mesh-configuration change nothing
     * dirties ({@link CubeWorld#setBlockFaces}, models, fluids, face rules) or to make sure no mesh built from
     * old tables is still handed out (e.g. a leaf-transparency setting; {@link CubeWorld#setBlockTable} itself
     * already re-meshes through the dirty set). Nearest first through the normal budgets; every cube with
     * geometry comes back once as REMESHED. Owner thread (it waits for a running tick).
     *
     * @return the number of cubes queued (0 for a {@link #NO_MESH} stream)
     */
    public long remeshAll() {
        MethodHandle h = require(CendaVoxelWorld.STREAM_REMESH_ALL, "cv_stream_remesh_all");
        try {
            long n = (long) h.invokeExact(pointer());
            if (n < 0) throw failure("cv_stream_remesh_all", n);
            return n;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * Detail radius ({@code cv_stream_set_detail_radius}): cubes whose box lies within {@code radiusBlocks} of a
     * viewer mesh {@link BlockFlags#DETAIL} blocks as their model, the others as cube faces (a cube keeps its
     * detail mesh up to radius + 8). Moving the viewers re-meshes only the cubes holding exposed detail blocks
     * that crossed the radius. Negative (the default) = detail everywhere. Owner thread. No-op on a library
     * without the call.
     */
    public void setDetailRadius(int radiusBlocks) {
        MethodHandle h = CendaVoxelWorld.STREAM_SET_DETAIL_RADIUS;
        if (h == null) return;
        try {
            int rc = (int) h.invokeExact(pointer(), radiusBlocks);
            if (rc < 0) throw failure("cv_stream_set_detail_radius", rc);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ---- any thread ----------------------------------------------------------------------------------------------

    /** Saves every changed cube and column and commits the store (blocking; waits for a running tick). */
    public void saveAll() {
        MethodHandle h = require(CendaVoxelWorld.STREAM_SAVE_ALL, "cv_stream_save_all");
        try {
            int r = (int) h.invokeExact(pointer());
            if (r < 0) throw failure("cv_stream_save_all", r);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public StreamStats stats() {
        MethodHandle h = require(CendaVoxelWorld.STREAM_STATS_GET, "cv_stream_stats_get");
        try (Arena a = Arena.ofConfined()) {
            MemorySegment s = a.allocate(CendaVoxelWorld.STREAM_STATS_BYTES, 8);
            h.invokeExact(pointer(), s);
            return StreamStats.read(s);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * The prioritized wanted-but-absent cubes of the latest plan as (cx, cy, cz) triples, nearest first: in
     * REMOTE mode what the host should request from the server.
     */
    public long[] missing() {
        MethodHandle h = require(CendaVoxelWorld.STREAM_MISSING, "cv_stream_missing");
        try (Arena a = Arena.ofConfined()) {
            long cap = 256;
            for (;;) {
                MemorySegment out = a.allocate(cap * 3 * 8, 8);
                long n = (long) h.invokeExact(pointer(), out, cap);
                if (isNeed(n)) {
                    cap = needed(n) + 64;
                    continue;
                }
                if (n < 0) throw failure("cv_stream_missing", n);
                return out.asSlice(0, n * 3 * 8).toArray(I64);
            }
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ---- generators ------------------------------------------------------------------------------------------------

    /**
     * TESTS ONLY: the engine's toy generator ({@code cv_test_toy_generator_create}: sea, cliff plateau with a
     * tunnel, overhang, trees by population; ids 0 air, 1 stone, 2 grass, 3 water, 4 log, 5 leaves). Free it
     * with {@link #destroyTestGenerator} after every stream using it closed.
     */
    public static MemorySegment createTestGenerator(long seed) {
        MethodHandle h = require(CendaVoxelWorld.TEST_TOY_GENERATOR_CREATE, "cv_test_toy_generator_create");
        try {
            MemorySegment g = (MemorySegment) h.invokeExact(seed);
            if (g.equals(MemorySegment.NULL)) throw new IllegalStateException("toy generator allocation failed");
            return g.reinterpret(CendaVoxelWorld.GENERATOR_BYTES);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void destroyTestGenerator(MemorySegment generator) {
        MethodHandle h = require(CendaVoxelWorld.TEST_TOY_GENERATOR_DESTROY, "cv_test_toy_generator_destroy");
        try {
            h.invokeExact(generator);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** True when the library exports the streamer. */
    public static boolean isAvailable() {
        return CendaVoxelWorld.isAvailable() && CendaVoxelWorld.STREAM_CREATE != null
            && CendaVoxelWorld.STREAM_MISSING != null;
    }
}
