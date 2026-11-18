package com.openmason.engine.voxel.world;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.ref.Cleaner;
import java.nio.file.Path;

import static com.openmason.engine.voxel.world.CendaVoxelWorld.ADDR;
import static com.openmason.engine.voxel.world.CendaVoxelWorld.I32;
import static com.openmason.engine.voxel.world.CendaVoxelWorld.I64;
import static com.openmason.engine.voxel.world.CendaVoxelWorld.failure;
import static com.openmason.engine.voxel.world.CendaVoxelWorld.require;
import static com.openmason.engine.voxel.world.CendaVoxelWorld.rethrow;

/**
 * The native octree LOD of a {@link CubeWorld} ({@code cv_lod_*}, OpenMason #122; rules in voxel_world.h "LOD"):
 * level-k nodes of 32^3 cells covering {@code 32 << k} blocks, downsampled from loaded cubes, stored cube records
 * and synthesized deep rock, kept incrementally up to date, persisted per level, meshed into LODQUAD16 node-mode
 * records ({@link LodMesh}), and selected per frame with FastLOD's bands and the stream's column readiness so the
 * drawn set always covers the view exactly once: READY detail, octree nodes, or a host FALLBACK footprint (the
 * game's own far terrain where nothing was ever generated). Java only marshals.
 *
 * <p><b>Threads.</b> {@link #tick}, {@link #mesh}, {@link #noteEvents} and {@link #setColumnReady} on the OWNER
 * thread (the stream's). {@link #setView}, {@link #noteCubes}, {@link #readNode}, {@link #buildMesh},
 * {@link #waitIdle}, {@link #flush} and {@link #stats} from any thread. {@link #close} must not race other
 * calls and runs before the world and the cube store are closed.
 *
 * <p><b>Host loop</b> (per frame): {@code stream.tick} → {@code lod.noteEvents(streamEvents)} →
 * {@code lod.tick(budget)} → apply every event (SHOW: upload {@link #mesh}(slot) and fade it in; UPDATE: replace;
 * HIDE: fade out, then free; FALLBACK_ON/OFF: start / stop drawing the host's far terrain over that footprint).
 */
public final class CubeLod implements AutoCloseable {

    /** Event types ({@code CV_LOD_EV_*}). */
    public static final int EV_SHOW = 1;
    public static final int EV_UPDATE = 2;
    public static final int EV_HIDE = 3;
    public static final int EV_FALLBACK_ON = 4;
    public static final int EV_FALLBACK_OFF = 5;

    /** Block classes ({@code CV_LOD_CLASS_*}) for {@link #setBlockClasses}. */
    public static final byte CLASS_DEFAULT = 0;
    public static final byte CLASS_AIR = 1;
    public static final byte CLASS_SOLID = 2;
    public static final byte CLASS_FLUID = 3;

    /** Cell bits of {@link #readNode} ({@code CV_LOD_CELL_*}): class 0 air / 1 solid / 2 fluid, plus FULL. */
    public static final int CELL_SOLID = 1;
    public static final int CELL_FLUID = 2;
    public static final int CELL_FULL = 4;

    public static final int MAX_LEVEL = 16;

    /**
     * Creation parameters ({@code cv_lod_desc}).
     *
     * @param maxLevel        highest level built and persisted (1..16)
     * @param minY            vertical range represented (blocks); {@code minY == maxY == 0}: the world's bounds
     * @param maxY            exclusive; at most 8192 blocks above minY
     * @param fillId          block synthesized below the TOP of generated columns where cubes were never generated
     * @param contentVersion  bump when block classes, fill id or face texture layers change (stored LODs with
     *                        another value are a mismatch)
     * @param cacheBytes      node data cache ({@code <= 0}: 256 MiB)
     * @param workerThreads   {@code <= 0}: 2
     * @param resetOnMismatch a mismatching store is wiped instead of failing creation
     */
    public record Config(int maxLevel, long minY, long maxY, int fillId, long contentVersion, long cacheBytes,
                         int workerThreads, boolean resetOnMismatch) {
        public static Config of(int maxLevel, int fillId) {
            return new Config(maxLevel, 0, 0, fillId, 0, 0, 0, false);
        }
    }

    private static final Cleaner CLEANER = Cleaner.create();

    private final CubeWorld world;
    private final Cleaner.Cleanable cleanable;
    private final Arena arena = Arena.ofShared();
    private final MemorySegment eventsOut;
    private final MemorySegment budgetSeg;
    private final LodEvents events = new LodEvents();
    private volatile MemorySegment lod;

    private CubeLod(CubeWorld world, CubeStore cubeStore, Path storeDir, Config config) {
        this.world = world;
        MethodHandle create = require(CendaVoxelWorld.LOD_CREATE, "cv_lod_create");
        try (Arena a = Arena.ofConfined()) {
            MemorySegment desc = a.allocate(CendaVoxelWorld.LOD_DESC_BYTES, 8);
            desc.set(ADDR, 0, storeDir == null ? MemorySegment.NULL : a.allocateFrom(storeDir.toString()));
            desc.set(ADDR, 8, cubeStore == null ? MemorySegment.NULL : cubeStore.pointer());
            desc.set(I64, 16, config.minY());
            desc.set(I64, 24, config.maxY());
            desc.set(I64, 32, config.contentVersion());
            desc.set(I64, 40, config.cacheBytes());
            desc.set(I32, 48, config.maxLevel());
            desc.set(I32, 52, config.workerThreads());
            desc.set(I32, 56, config.resetOnMismatch() ? 1 : 0);
            desc.set(ValueLayout.JAVA_SHORT, 60, (short) config.fillId());
            MemorySegment l = (MemorySegment) create.invokeExact(world.pointer(), desc);
            if (l.equals(MemorySegment.NULL)) {
                throw new IllegalArgumentException("cv_lod_create failed: " + CendaVoxelWorld.lastError());
            }
            this.lod = l;
        } catch (Throwable t) {
            arena.close();
            throw rethrow(t);
        }
        this.eventsOut = arena.allocate(ADDR);
        this.budgetSeg = arena.allocate(CendaVoxelWorld.LOD_BUDGET_BYTES, 8);
        this.cleanable = CLEANER.register(this, new Destroy(lod, world, cubeStore));
    }

    /**
     * An octree LOD over {@code world}.
     *
     * @param cubeStore the store unloaded cubes and columns are read from (the stream's), or null
     * @param storeDir  directory of the persisted nodes (one cv_store per level inside), or null: memory only.
     *                  Must not be the cube store's directory.
     */
    public static CubeLod create(CubeWorld world, CubeStore cubeStore, Path storeDir, Config config) {
        if (world == null || config == null) throw new IllegalArgumentException("world and config are required");
        return new CubeLod(world, cubeStore, storeDir, config);
    }

    private record Destroy(MemorySegment lod, CubeWorld world, CubeStore store) implements Runnable {
        @Override
        public void run() {
            try {
                int ignored = (int) CendaVoxelWorld.LOD_DESTROY.invokeExact(lod);
            } catch (Throwable ignored) {
                // releasing at exit
            }
        }
    }

    private MemorySegment pointer() {
        MemorySegment l = lod;
        if (l == null) throw new IllegalStateException("CubeLod is closed");
        return l;
    }

    public CubeWorld world() {
        return world;
    }

    /** Waits for running jobs, commits the level stores and frees the LOD. */
    @Override
    public void close() {
        if (lod != null) {
            lod = null;
            cleanable.clean();
            arena.close();
        }
    }

    /** Per-block classes ({@link #CLASS_AIR} ...) for ids {@code [0, classes.length)}; configuration, call first. */
    public void setBlockClasses(byte[] classes) {
        MethodHandle h = require(CendaVoxelWorld.LOD_BLOCK_CLASSES, "cv_lod_block_classes");
        try (Arena a = Arena.ofConfined()) {
            MemorySegment seg = classes.length == 0 ? MemorySegment.NULL : a.allocateFrom(ValueLayout.JAVA_BYTE, classes);
            int r = (int) h.invokeExact(pointer(), seg, classes.length);
            if (r < 0) throw failure("cv_lod_block_classes", r);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ---- change feed ------------------------------------------------------------------------------------------

    /** OWNER THREAD, right after the stream's tick: its events (installs, changes, readiness) reach the LOD. */
    public void noteEvents(StreamEvents streamEvents) {
        if (streamEvents.count() == 0) return;
        MethodHandle h = require(CendaVoxelWorld.LOD_NOTE_EVENTS, "cv_lod_note_events");
        try {
            int r = (int) h.invokeExact(pointer(), streamEvents.segment(), streamEvents.count());
            if (r < 0) throw failure("cv_lod_note_events", r);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Any thread: cubes (cx, cy, cz triples) whose content may have changed (hosts editing without a stream). */
    public void noteCubes(long... xyz) {
        if (xyz.length % 3 != 0) throw new IllegalArgumentException("triples expected");
        if (xyz.length == 0) return;
        MethodHandle h = require(CendaVoxelWorld.LOD_NOTE_CUBES, "cv_lod_note_cubes");
        try (Arena a = Arena.ofConfined()) {
            int r = (int) h.invokeExact(pointer(), a.allocateFrom(I64, xyz), (long) (xyz.length / 3));
            if (r < 0) throw failure("cv_lod_note_cubes", r);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** OWNER THREAD: column readiness without a stream (what COLUMN_READY / UNREADY do). */
    public void setColumnReady(long cx, long cz, boolean ready) {
        MethodHandle h = require(CendaVoxelWorld.LOD_SET_COLUMN_READY, "cv_lod_set_column_ready");
        try {
            int r = (int) h.invokeExact(pointer(), cx, cz, ready ? 1 : 0);
            if (r < 0) throw failure("cv_lod_set_column_ready", r);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ---- view + tick -------------------------------------------------------------------------------------------

    /** Any thread: viewers as block-position triples (x, y, z ...) and the band policy; empty = draw nothing. */
    public void setView(long[] viewersXyz, LodPolicy policy) {
        if (viewersXyz.length % 3 != 0) throw new IllegalArgumentException("viewer triples expected");
        MethodHandle h = require(CendaVoxelWorld.LOD_SET_VIEW, "cv_lod_set_view");
        try (Arena a = Arena.ofConfined()) {
            int n = viewersXyz.length / 3;
            MemorySegment v = n == 0 ? MemorySegment.NULL : a.allocateFrom(I64, viewersXyz);
            MemorySegment p = a.allocate(LodPolicy.BYTES, 8);
            p.set(I32, 0, policy.blocksPerCell());
            p.set(I32, 4, policy.maxLevel());
            p.set(I32, 8, policy.radius());
            p.set(I32, 12, policy.incompleteDepth());
            p.set(I32, 16, policy.standbyFrom());
            p.set(I32, 20, policy.incompleteMinLevel());
            int r = (int) h.invokeExact(pointer(), v, n, p);
            if (r < 0) throw failure("cv_lod_set_view", r);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * OWNER THREAD. Collects finished work, re-selects, schedules builds and returns this tick's events (the view
     * is reused, valid until the next tick).
     *
     * @param maxMeshBytes mesh bytes handed out for roots not touched by readiness changes; {@code <= 0}: unlimited
     */
    public LodEvents tick(long maxMeshBytes) {
        MethodHandle h = require(CendaVoxelWorld.LOD_TICK, "cv_lod_tick");
        budgetSeg.set(I64, 0, maxMeshBytes);
        try {
            int n = (int) h.invokeExact(pointer(), budgetSeg, eventsOut);
            if (n < 0) throw failure("cv_lod_tick", n);
            events.reset(eventsOut.get(ADDR, 0), n);
            return events;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** OWNER THREAD. The mesh of a SHOW / UPDATE event of the current tick. */
    public LodMesh mesh(int slot) {
        MethodHandle h = require(CendaVoxelWorld.LOD_MESH_GET, "cv_lod_mesh_get");
        try {
            MemorySegment m = (MemorySegment) h.invokeExact(pointer(), slot);
            if (m.equals(MemorySegment.NULL)) throw new IllegalArgumentException("no LOD mesh in slot " + slot + " this tick");
            return new LodMesh(m);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ---- tools / tests -----------------------------------------------------------------------------------------

    /**
     * Any thread, blocking: a node's cells (32768 each, {@link CubeWorld#cellIndex} order; either array may be
     * null). Returns true when the node is complete.
     */
    public boolean readNode(int level, long nx, long ny, long nz, short[] ids, byte[] cells) {
        MethodHandle h = require(CendaVoxelWorld.LOD_READ_NODE, "cv_lod_read_node");
        try (Arena a = Arena.ofConfined()) {
            MemorySegment idSeg = ids == null ? MemorySegment.NULL : a.allocate(ValueLayout.JAVA_SHORT, CendaVoxelWorld.CUBE_CELLS);
            MemorySegment cellSeg = cells == null ? MemorySegment.NULL : a.allocate(ValueLayout.JAVA_BYTE, CendaVoxelWorld.CUBE_CELLS);
            int r = (int) h.invokeExact(pointer(), level, nx, ny, nz, idSeg, cellSeg, MemorySegment.NULL);
            if (r < 0) throw failure("cv_lod_read_node", r);
            if (ids != null) MemorySegment.copy(idSeg, ValueLayout.JAVA_SHORT, 0, ids, 0, CendaVoxelWorld.CUBE_CELLS);
            if (cells != null) MemorySegment.copy(cellSeg, ValueLayout.JAVA_BYTE, 0, cells, 0, CendaVoxelWorld.CUBE_CELLS);
            return r == 1;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Any thread, blocking: the node's content digest (builds it when needed). */
    public long nodeDigest(int level, long nx, long ny, long nz) {
        MethodHandle h = require(CendaVoxelWorld.LOD_READ_NODE, "cv_lod_read_node");
        try (Arena a = Arena.ofConfined()) {
            MemorySegment d = a.allocate(I64);
            int r = (int) h.invokeExact(pointer(), level, nx, ny, nz, MemorySegment.NULL, MemorySegment.NULL, d);
            if (r < 0) throw failure("cv_lod_read_node", r);
            return d.get(I64, 0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * Any thread, blocking: meshes a node now. The returned view (and its segments) stays valid until the calling
     * thread's next {@code buildMesh}.
     */
    public LodMesh buildMesh(int level, long nx, long ny, long nz) {
        MethodHandle h = require(CendaVoxelWorld.LOD_BUILD_MESH, "cv_lod_build_mesh");
        MemorySegment out = BUILD_OUT.get();
        try {
            int r = (int) h.invokeExact(pointer(), level, nx, ny, nz, out);
            if (r < 0) throw failure("cv_lod_build_mesh", r);
            return new LodMesh(out);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private static final ThreadLocal<MemorySegment> BUILD_OUT =
        ThreadLocal.withInitial(() -> Arena.ofAuto().allocate(CendaVoxelWorld.LOD_MESH_BYTES, 8));

    /** Any thread: blocks until no node is dirty and no job is queued or running. */
    public void waitIdle() {
        MethodHandle h = require(CendaVoxelWorld.LOD_WAIT_IDLE, "cv_lod_wait_idle");
        try {
            int r = (int) h.invokeExact(pointer());
            if (r < 0) throw failure("cv_lod_wait_idle", r);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Any thread: commits written node records (durable after this). */
    public void flush() {
        MethodHandle h = require(CendaVoxelWorld.LOD_FLUSH, "cv_lod_flush");
        try {
            int r = (int) h.invokeExact(pointer());
            if (r < 0) throw failure("cv_lod_flush", r);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public LodStats stats() {
        MethodHandle h = require(CendaVoxelWorld.LOD_STATS_GET, "cv_lod_stats_get");
        try (Arena a = Arena.ofConfined()) {
            MemorySegment s = a.allocate(CendaVoxelWorld.LOD_STATS_BYTES, 8);
            h.invokeExact(pointer(), s);
            return LodStats.read(s);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** True when the library exports the octree LOD. */
    public static boolean isAvailable() {
        return CendaVoxelWorld.isAvailable() && CendaVoxelWorld.LOD_CREATE != null && CendaVoxelWorld.LOD_STATS_GET != null;
    }
}
