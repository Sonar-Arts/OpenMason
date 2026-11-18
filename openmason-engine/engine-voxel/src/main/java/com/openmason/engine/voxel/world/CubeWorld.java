package com.openmason.engine.voxel.world;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.ref.Cleaner;

import static com.openmason.engine.voxel.world.CendaVoxelWorld.ADDR;
import static com.openmason.engine.voxel.world.CendaVoxelWorld.COLUMN_CELLS;
import static com.openmason.engine.voxel.world.CendaVoxelWorld.CUBE_CELLS;
import static com.openmason.engine.voxel.world.CendaVoxelWorld.ERR_EXISTS;
import static com.openmason.engine.voxel.world.CendaVoxelWorld.ERR_MISSING;
import static com.openmason.engine.voxel.world.CendaVoxelWorld.I16;
import static com.openmason.engine.voxel.world.CendaVoxelWorld.I32;
import static com.openmason.engine.voxel.world.CendaVoxelWorld.I64;
import static com.openmason.engine.voxel.world.CendaVoxelWorld.failure;
import static com.openmason.engine.voxel.world.CendaVoxelWorld.isNeed;
import static com.openmason.engine.voxel.world.CendaVoxelWorld.needed;
import static com.openmason.engine.voxel.world.CendaVoxelWorld.rethrow;

/**
 * One native voxel world ({@code cv_world}, OpenMason #141): cubes of 32^3 blocks addressed by 64-bit
 * coordinates, paletted storage, per-cube masks, host layers and blobs, revisions and the dirty set, and
 * the surface tracker (#144). The behaviour lives in C++ ({@code cenda/native/kernels/src/voxel_world/},
 * contract {@code voxel_world.h}); this class only marshals.
 *
 * <p><b>Coordinates.</b> Block coordinates are {@code long} (x, y, z); cube coordinates are block
 * coordinates {@code >> 5}. Cell arrays of one cube are in {@link #cellIndex} order.
 *
 * <p><b>Results.</b> Reads of an unloaded cube answer {@link #MISSING} (or false/null), except cells known
 * to be air (at or above their column's TOP, see voxel_world.h "KNOWN AIR"). Bad arguments (writes outside
 * the vertical bounds, bad layers) throw {@link IllegalArgumentException}; corrupt bytes too.
 *
 * <p><b>Threads.</b> Every method may be called from any thread concurrently, except {@link #close()}, which
 * must not race other calls (close the world after its workers stopped). Buffers passed in are only used
 * during the call. {@link #meshCube} returns a per-thread object that the next call on the same thread
 * reuses.
 */
public final class CubeWorld implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(CubeWorld.class);

    /** {@link #get} and friends: the cube is not loaded and the cell is not known air. */
    public static final int MISSING = ERR_MISSING;
    /** {@link #uniformId}: the cube holds more than one block id. */
    public static final int NOT_UNIFORM = -1;

    public static final int CUBE_EDGE = 32;
    public static final int CUBE_SHIFT = 5;
    public static final int MAX_LAYERS = 8;
    public static final long UNBOUNDED_MIN = Long.MIN_VALUE;
    public static final long UNBOUNDED_MAX = Long.MAX_VALUE;

    /** Layer flags ({@code CV_LAYER_*}). */
    public static final int LAYER_SAVED = 1;
    public static final int LAYER_NETWORK = 2;
    public static final int LAYER_MESHED = 4;

    /** Encode parts ({@code CV_PART*}). */
    public static final int PART_BLOCKS = 0x00000001;
    public static final int PART_BLOB = 0x80000000;
    public static final int PARTS_SAVED = 0xFFFFFFFF;
    public static final int PARTS_NETWORK = 0x7FFFFFFF;

    public static int partLayer(int layer) {
        return 0x100 << layer;
    }

    /** Cell index of cube-local (lx, ly, lz), each 0..31 ({@code CV_CELL_INDEX}). */
    public static int cellIndex(int lx, int ly, int lz) {
        return (ly << 10) | (lz << 5) | lx;
    }

    private static final Cleaner CLEANER = Cleaner.create();
    private static final MethodHandle SURFACE_TARGET;

    static {
        try {
            SURFACE_TARGET = MethodHandles.lookup().findVirtual(CubeWorld.class, "surfaceUpcall",
                MethodType.methodType(int.class, MemorySegment.class, long.class, long.class, MemorySegment.class,
                    MemorySegment.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final long minY;
    private final long maxY;
    private final int airId;
    private final SurfaceSource source;
    private final Arena arena = Arena.ofAuto();   // the upcall stub; alive while this object is reachable
    private final Cleaner.Cleanable cleanable;
    private volatile MemorySegment world;
    private volatile boolean surfaceFailureLogged;

    private CubeWorld(long minY, long maxY, int airId, SurfaceSource source, MemorySegment generator) {
        this.minY = minY;
        this.maxY = maxY;
        this.airId = airId;
        this.source = source;
        MemorySegment desc = arena.allocate(CendaVoxelWorld.WORLD_DESC_BYTES, 8);
        desc.set(I64, 0, minY);
        desc.set(I64, 8, maxY);
        MemorySegment stub = source == null ? MemorySegment.NULL
            : Linker.nativeLinker().upcallStub(SURFACE_TARGET.bindTo(this), CendaVoxelWorld.SURFACE_FN, arena);
        desc.set(ADDR, 16, stub);
        desc.set(ADDR, 24, MemorySegment.NULL);
        desc.set(I16, 32, (short) airId);
        desc.set(I16, 34, (short) 0);
        desc.set(I32, 36, 0);
        try {
            MemorySegment w = generator == null
                ? (MemorySegment) CendaVoxelWorld.WORLD_CREATE.invokeExact(desc)
                : (MemorySegment) CendaVoxelWorld.require(CendaVoxelWorld.WORLD_CREATE_WITH_GENERATOR,
                    "cv_world_create_with_generator").invokeExact(desc, generator);
            if (w.equals(MemorySegment.NULL)) {
                throw new IllegalArgumentException("cv_world_create failed: " + CendaVoxelWorld.lastError());
            }
            this.world = w;
        } catch (Throwable t) {
            throw rethrow(t);
        }
        // The cleaner action holds only the native pointer. The upcall stub lives in this object's arena: an
        // unreachable world receives no calls, so the stub is never used after it is freed.
        this.cleanable = CLEANER.register(this, new Destroy(world));
    }

    /**
     * Creates a native voxel world.
     *
     * @param minY    lowest block y (inclusive), or {@link #UNBOUNDED_MIN}
     * @param maxY    highest block y (exclusive), or {@link #UNBOUNDED_MAX}
     * @param airId   the empty block id
     * @param surface never-generated surface (may be null: never-generated space is open sky)
     * @throws IllegalStateException when the Cenda voxel world is unavailable (the message says why)
     */
    public static CubeWorld create(long minY, long maxY, int airId, SurfaceSource surface) {
        if (!CendaVoxelWorld.isAvailable()) {
            throw new IllegalStateException("Cenda voxel world unavailable: " + CendaVoxelWorld.unavailableReason());
        }
        if (minY >= maxY) throw new IllegalArgumentException("minY must be below maxY");
        if (airId < 0 || airId > 0xFFFF) throw new IllegalArgumentException("airId " + airId);
        return new CubeWorld(minY, maxY, airId, surface, null);
    }

    /**
     * Creates a native voxel world whose never-generated surface is a native generator's
     * {@code column_surface} ({@code cv_world_create_with_generator}): the world calls it directly on any thread,
     * with no Java upcall. This is the world a {@link CubeStream} over the same generator needs (known air, the
     * visibility walk and never-generated columns then agree with the generator). Java-implemented surfaces (e.g.
     * a profile computed in Java) use {@link #create} with a {@link SurfaceSource}.
     *
     * @param generator a native {@code cv_generator*} (the game lib's, or {@link CubeStream#createTestGenerator});
     *                  it must outlive the world
     * @throws IllegalStateException when the Cenda voxel world is unavailable (the message says why)
     * @throws IllegalArgumentException when the generator was built against another {@code CV_ABI_VERSION}
     */
    public static CubeWorld createForGenerator(long minY, long maxY, int airId, MemorySegment generator) {
        if (!CendaVoxelWorld.isAvailable()) {
            throw new IllegalStateException("Cenda voxel world unavailable: " + CendaVoxelWorld.unavailableReason());
        }
        if (minY >= maxY) throw new IllegalArgumentException("minY must be below maxY");
        if (airId < 0 || airId > 0xFFFF) throw new IllegalArgumentException("airId " + airId);
        if (generator == null || generator.equals(MemorySegment.NULL)) {
            throw new IllegalArgumentException("generator is required");
        }
        return new CubeWorld(minY, maxY, airId, null, generator);
    }

    private record Destroy(MemorySegment world) implements Runnable {
        @Override
        public void run() {
            try {
                CendaVoxelWorld.WORLD_DESTROY.invokeExact(world);
            } catch (Throwable ignored) {
                // the process is releasing the world
            }
        }
    }

    // Native code calls this on any thread. Must never throw.
    @SuppressWarnings("unused")
    private int surfaceUpcall(MemorySegment user, long cx, long cz, MemorySegment skyOut, MemorySegment topOut) {
        try {
            long[][] buf = UPCALL_BUFFERS.get();
            long[] sky = buf[0];
            long[] top = buf[1];
            java.util.Arrays.fill(sky, Long.MIN_VALUE);
            java.util.Arrays.fill(top, Long.MIN_VALUE);
            if (!source.surface(cx, cz, sky, top)) return 0;
            long bytes = COLUMN_CELLS * 8L;
            MemorySegment.copy(sky, 0, skyOut.reinterpret(bytes), I64, 0, COLUMN_CELLS);
            MemorySegment.copy(top, 0, topOut.reinterpret(bytes), I64, 0, COLUMN_CELLS);
            return 1;
        } catch (Throwable t) {
            if (!surfaceFailureLogged) {
                surfaceFailureLogged = true;
                LOGGER.error("Surface source failed for column ({}, {}); treating it as unknown", cx, cz, t);
            }
            return 0;
        }
    }

    private static final ThreadLocal<long[][]> UPCALL_BUFFERS =
        ThreadLocal.withInitial(() -> new long[][] {new long[COLUMN_CELLS], new long[COLUMN_CELLS]});

    @Override
    public void close() {
        if (world != null) {
            world = null;
            cleanable.clean();
        }
    }

    /** The native cv_world pointer for other bindings in this package (streamer, mesher helpers). */
    MemorySegment pointer() {
        MemorySegment w = world;
        if (w == null) throw new IllegalStateException("CubeWorld is closed");
        return w;
    }

    public long minY() {
        return minY;
    }

    public long maxY() {
        return maxY;
    }

    public int airId() {
        return airId;
    }

    // ---- per-thread native scratch ------------------------------------------------------------------------

    static final class Scratch {
        final MemorySegment small = Arena.ofAuto().allocate(64, 8);
        final MemorySegment cells = Arena.ofAuto().allocate(CUBE_CELLS * 2L, 8);
        final MemorySegment cells2 = Arena.ofAuto().allocate(CUBE_CELLS * 2L, 8);
        final MemorySegment column = Arena.ofAuto().allocate(COLUMN_CELLS * 8L, 8);
        private MemorySegment bytes = Arena.ofAuto().allocate(64 * 1024, 8);
        private MemorySegment longs = Arena.ofAuto().allocate(3 * 8 * 1024, 8);

        MemorySegment bytes(long n) {
            if (bytes.byteSize() < n) bytes = Arena.ofAuto().allocate(Math.max(n, bytes.byteSize() * 2), 8);
            return bytes;
        }

        MemorySegment longs(long n) {
            if (longs.byteSize() < n * 8) longs = Arena.ofAuto().allocate(Math.max(n * 8, longs.byteSize() * 2), 8);
            return longs;
        }
    }

    static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private static void checkCells(int length, String what) {
        if (length < CUBE_CELLS) throw new IllegalArgumentException(what + " needs " + CUBE_CELLS + " cells");
    }

    // ---- block table + layers -----------------------------------------------------------------------------

    /** Replaces the block table: {@code flags[id]} ({@link BlockFlags}) for ids {@code [0, flags.length)}. */
    public void setBlockTable(short[] flags) {
        if (flags.length > 65536) throw new IllegalArgumentException("more than 65536 block ids");
        try (Arena a = Arena.ofConfined()) {
            MemorySegment seg = a.allocate(Math.max(2L, flags.length * 2L), 8);
            MemorySegment.copy(flags, 0, seg, I16, 0, flags.length);
            int r = (int) CendaVoxelWorld.BLOCK_TABLE_SET.invokeExact(pointer(), seg, flags.length);
            if (r < 0) throw failure("cv_block_table_set", r);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Declares what a host layer is ({@link #LAYER_SAVED}, {@link #LAYER_NETWORK}, {@link #LAYER_MESHED}). */
    public void defineLayer(int layer, int flags) {
        try {
            int r = (int) CendaVoxelWorld.LAYER_DEFINE.invokeExact(pointer(), layer, flags);
            if (r < 0) throw failure("cv_layer_define", r);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ---- cubes --------------------------------------------------------------------------------------------

    /** Inserts a cube filled with one block; false when it already exists. */
    public boolean createCube(long cx, long cy, long cz, int fillId) {
        try {
            int r = (int) CendaVoxelWorld.CUBE_CREATE.invokeExact(pointer(), cx, cy, cz, (char) fillId);
            if (r == ERR_EXISTS) return false;
            if (r < 0) throw failure("cv_cube_create", r);
            return true;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Removes a cube (its tracker contribution stays); false when it was not loaded. */
    public boolean removeCube(long cx, long cy, long cz) {
        try {
            int r = (int) CendaVoxelWorld.CUBE_REMOVE.invokeExact(pointer(), cx, cy, cz);
            if (r == ERR_MISSING) return false;
            if (r < 0) throw failure("cv_cube_remove", r);
            return true;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public boolean hasCube(long cx, long cy, long cz) {
        try {
            return (int) CendaVoxelWorld.CUBE_EXISTS.invokeExact(pointer(), cx, cy, cz) == 1;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public long cubeCount() {
        try {
            return (long) CendaVoxelWorld.CUBE_COUNT.invokeExact(pointer());
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Every loaded cube as triples (x0, y0, z0, x1, ...), unspecified order. */
    public long[] listCubes() {
        return triples(CendaVoxelWorld.CUBE_LIST, "cv_cube_list");
    }

    private long[] triples(MethodHandle call, String name) {
        Scratch s = SCRATCH.get();
        long cap = 1024;
        try {
            for (;;) {
                MemorySegment out = s.longs(3 * cap);
                long n = (long) call.invokeExact(pointer(), out, cap);
                if (isNeed(n)) {
                    cap = needed(n) + 64;
                    continue;
                }
                if (n < 0) throw failure(name, n);
                long[] result = new long[(int) (3 * n)];
                MemorySegment.copy(out, I64, 0, result, 0, result.length);
                return result;
            }
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Creates or replaces a whole cube from {@link #cellIndex}-ordered ids (layers reset, blob emptied). */
    public void writeIds(long cx, long cy, long cz, short[] ids) {
        checkCells(ids.length, "ids");
        MemorySegment seg = SCRATCH.get().cells;
        MemorySegment.copy(ids, 0, seg, I16, 0, CUBE_CELLS);
        writeIds(cx, cy, cz, seg);
    }

    /** As {@link #writeIds(long, long, long, short[])} from native memory (32768 uint16). */
    public void writeIds(long cx, long cy, long cz, MemorySegment ids) {
        try {
            int r = (int) CendaVoxelWorld.CUBE_WRITE_IDS.invokeExact(pointer(), cx, cy, cz, ids);
            if (r < 0) throw failure("cv_cube_write_ids", r);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Reads a cube's ids; false when it is not loaded. */
    public boolean readIds(long cx, long cy, long cz, short[] out) {
        checkCells(out.length, "out");
        MemorySegment seg = SCRATCH.get().cells;
        if (!readIds(cx, cy, cz, seg)) return false;
        MemorySegment.copy(seg, I16, 0, out, 0, CUBE_CELLS);
        return true;
    }

    public boolean readIds(long cx, long cy, long cz, MemorySegment out) {
        try {
            int r = (int) CendaVoxelWorld.CUBE_READ_IDS.invokeExact(pointer(), cx, cy, cz, out);
            if (r == ERR_MISSING) return false;
            if (r < 0) throw failure("cv_cube_read_ids", r);
            return true;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** The cube's block id when every cell holds it, {@link #NOT_UNIFORM}, or {@link #MISSING}. */
    public int uniformId(long cx, long cy, long cz) {
        MemorySegment out = SCRATCH.get().small;
        try {
            int r = (int) CendaVoxelWorld.CUBE_UNIFORM.invokeExact(pointer(), cx, cy, cz, out);
            if (r == ERR_MISSING) return MISSING;
            if (r < 0) throw failure("cv_cube_uniform", r);
            return r == 1 ? Short.toUnsignedInt(out.get(I16, 0)) : NOT_UNIFORM;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** cv_cube_encode bytes of a cube ({@link #PARTS_SAVED}, {@link #PARTS_NETWORK} or a mask), null when missing. */
    public byte[] encode(long cx, long cy, long cz, int parts) {
        Scratch s = SCRATCH.get();
        long cap = 64 * 1024;
        try {
            for (;;) {
                MemorySegment out = s.bytes(cap);
                long n = (long) CendaVoxelWorld.CUBE_ENCODE.invokeExact(pointer(), cx, cy, cz, parts, out, out.byteSize());
                if (isNeed(n)) {
                    cap = needed(n);
                    continue;
                }
                if (n == ERR_MISSING) return null;
                if (n < 0) throw failure("cv_cube_encode", n);
                return out.asSlice(0, n).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
            }
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Creates or replaces a cube from {@link #encode} bytes; corrupt bytes throw IllegalArgumentException. */
    public void decode(long cx, long cy, long cz, byte[] bytes) {
        MemorySegment seg = SCRATCH.get().bytes(Math.max(1, bytes.length));
        MemorySegment.copy(bytes, 0, seg, java.lang.foreign.ValueLayout.JAVA_BYTE, 0, bytes.length);
        decode(cx, cy, cz, seg, bytes.length);
    }

    public void decode(long cx, long cy, long cz, MemorySegment bytes, long length) {
        try {
            int r = (int) CendaVoxelWorld.CUBE_DECODE.invokeExact(pointer(), cx, cy, cz, bytes, length);
            if (r < 0) throw failure("cv_cube_decode", r);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ---- blocks -------------------------------------------------------------------------------------------

    /** Block id, air outside the bounds or in known air, {@link #MISSING} for unknown cells. */
    public int get(long x, long y, long z) {
        try {
            int r = (int) CendaVoxelWorld.GET.invokeExact(pointer(), x, y, z);
            if (r < 0 && r != MISSING) throw failure("cv_get", r);
            return r;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Writes a block; returns the previous id, or {@link #MISSING} when the cube is not loaded. */
    public int set(long x, long y, long z, int id) {
        try {
            int r = (int) CendaVoxelWorld.SET.invokeExact(pointer(), x, y, z, (char) id);
            if (r < 0 && r != MISSING) throw failure("cv_set", r);
            return r;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * Reads the box [x0, x0+sx) x [y0, y0+sy) x [z0, z0+sz) into {@code out[((y-y0)*sz + (z-z0))*sx + (x-x0)]};
     * unknown cells of missing cubes get {@code missingId}. Returns the number of unknown cells.
     */
    public long readBox(long x0, long y0, long z0, int sx, int sy, int sz, short[] out, int missingId) {
        long n = (long) sx * sy * sz;
        if (sx <= 0 || sy <= 0 || sz <= 0 || out.length < n) throw new IllegalArgumentException("bad box");
        MemorySegment seg = SCRATCH.get().bytes(n * 2);
        long unknown = readBox(x0, y0, z0, sx, sy, sz, seg, missingId);
        MemorySegment.copy(seg, I16, 0, out, 0, (int) n);
        return unknown;
    }

    public long readBox(long x0, long y0, long z0, int sx, int sy, int sz, MemorySegment out, int missingId) {
        try {
            long r = (long) CendaVoxelWorld.READ_BOX.invokeExact(pointer(), x0, y0, z0, sx, sy, sz, out, (char) missingId);
            if (r < 0) throw failure("cv_read_box", r);
            return r;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ---- host layers ---------------------------------------------------------------------------------------

    /** Layer value, 0 outside the bounds, {@link #MISSING} when the cube is not loaded. */
    public int getLayer(int layer, long x, long y, long z) {
        try {
            int r = (int) CendaVoxelWorld.LAYER_GET.invokeExact(pointer(), layer, x, y, z);
            if (r < 0 && r != MISSING) throw failure("cv_layer_get", r);
            return r;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Writes a layer value; returns the previous one or {@link #MISSING}. */
    public int setLayer(int layer, long x, long y, long z, int value) {
        try {
            int r = (int) CendaVoxelWorld.LAYER_SET.invokeExact(pointer(), layer, x, y, z, (char) value);
            if (r < 0 && r != MISSING) throw failure("cv_layer_set", r);
            return r;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public boolean readLayer(int layer, long cx, long cy, long cz, short[] out) {
        checkCells(out.length, "out");
        MemorySegment seg = SCRATCH.get().cells;
        try {
            int r = (int) CendaVoxelWorld.LAYER_READ_CUBE.invokeExact(pointer(), layer, cx, cy, cz, seg);
            if (r == ERR_MISSING) return false;
            if (r < 0) throw failure("cv_layer_read_cube", r);
        } catch (Throwable t) {
            throw rethrow(t);
        }
        MemorySegment.copy(seg, I16, 0, out, 0, CUBE_CELLS);
        return true;
    }

    /** Replaces a whole layer of a cube; false when the cube is not loaded. */
    public boolean writeLayer(int layer, long cx, long cy, long cz, short[] values) {
        checkCells(values.length, "values");
        MemorySegment seg = SCRATCH.get().cells;
        MemorySegment.copy(values, 0, seg, I16, 0, CUBE_CELLS);
        try {
            int r = (int) CendaVoxelWorld.LAYER_WRITE_CUBE.invokeExact(pointer(), layer, cx, cy, cz, seg);
            if (r == ERR_MISSING) return false;
            if (r < 0) throw failure("cv_layer_write_cube", r);
            return true;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** The non-zero cells of a layer in one cube. */
    public record LayerCells(short[] index, short[] value, int count) {
    }

    /** Non-zero cells of a layer in one cube (cell indices + values), or null when the cube is not loaded. */
    public LayerCells scanLayer(int layer, long cx, long cy, long cz) {
        Scratch s = SCRATCH.get();
        try {
            MemorySegment idx = s.cells;
            MemorySegment val = s.cells2;
            long n = (long) CendaVoxelWorld.LAYER_SCAN.invokeExact(pointer(), layer, cx, cy, cz, idx, val, (long) CUBE_CELLS);
            if (n == ERR_MISSING) return null;
            if (n < 0) throw failure("cv_layer_scan", n);
            short[] i = new short[(int) n];
            short[] v = new short[(int) n];
            MemorySegment.copy(idx, I16, 0, i, 0, (int) n);
            MemorySegment.copy(val, I16, 0, v, 0, (int) n);
            return new LayerCells(i, v, (int) n);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ---- blob ----------------------------------------------------------------------------------------------

    /** Sets the cube's host blob (empty array clears it); false when the cube is not loaded. */
    public boolean setBlob(long cx, long cy, long cz, byte[] data) {
        MemorySegment seg = SCRATCH.get().bytes(Math.max(1, data.length));
        MemorySegment.copy(data, 0, seg, java.lang.foreign.ValueLayout.JAVA_BYTE, 0, data.length);
        try {
            int r = (int) CendaVoxelWorld.CUBE_SET_BLOB.invokeExact(pointer(), cx, cy, cz, seg, (long) data.length);
            if (r == ERR_MISSING) return false;
            if (r < 0) throw failure("cv_cube_set_blob", r);
            return true;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** The cube's host blob (empty when none), or null when the cube is not loaded. */
    public byte[] getBlob(long cx, long cy, long cz) {
        Scratch s = SCRATCH.get();
        long cap = 4096;
        try {
            for (;;) {
                MemorySegment out = s.bytes(cap);
                long n = (long) CendaVoxelWorld.CUBE_GET_BLOB.invokeExact(pointer(), cx, cy, cz, out, out.byteSize());
                if (isNeed(n)) {
                    cap = needed(n);
                    continue;
                }
                if (n == ERR_MISSING) return null;
                if (n < 0) throw failure("cv_cube_get_blob", n);
                return out.asSlice(0, n).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
            }
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ---- revisions + dirty set -----------------------------------------------------------------------------

    public long worldRevision() {
        try {
            return (long) CendaVoxelWorld.WORLD_REVISION.invokeExact(pointer());
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** The cube's revision, 0 when it is not loaded. */
    public long cubeRevision(long cx, long cy, long cz) {
        try {
            return (long) CendaVoxelWorld.CUBE_REVISION.invokeExact(pointer(), cx, cy, cz);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Takes every cube that needs re-meshing, as triples (x0, y0, z0, x1, ...). */
    public long[] drainDirty() {
        return triples(CendaVoxelWorld.DRAIN_DIRTY, "cv_drain_dirty");
    }

    // ---- surface tracker (#144) ----------------------------------------------------------------------------

    /** First free y above the topmost sky-opaque block at (x, z); {@link #minY()} when nothing is known. */
    public long surfaceY(long x, long z) {
        return columnValue(CendaVoxelWorld.SURFACE_Y, "cv_surface_y", x, z);
    }

    /** First free y above the topmost non-air block at (x, z); {@link #minY()} when nothing is known. */
    public long topY(long x, long z) {
        return columnValue(CendaVoxelWorld.TOP_Y, "cv_top_y", x, z);
    }

    private long columnValue(MethodHandle call, String name, long x, long z) {
        MemorySegment out = SCRATCH.get().small;
        try {
            int r = (int) call.invokeExact(pointer(), x, z, out);
            if (r < 0 && r != ERR_MISSING) throw failure(name, r);
            return out.get(I64, 0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** The 32x32 SKY surface of column (cx, cz) at {@code out[lz * 32 + lx]}; false when nothing is known. */
    public boolean surfaceColumn(long cx, long cz, long[] out) {
        if (out.length < COLUMN_CELLS) throw new IllegalArgumentException("out needs 1024 entries");
        MemorySegment seg = SCRATCH.get().column;
        try {
            int r = (int) CendaVoxelWorld.SURFACE_COLUMN.invokeExact(pointer(), cx, cz, seg);
            if (r < 0 && r != ERR_MISSING) throw failure("cv_surface_column", r);
            MemorySegment.copy(seg, I64, 0, out, 0, COLUMN_CELLS);
            return r == 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@link #topRange}: min/max TOP and min SKY over a column's 32x32 cells. */
    public record TopRange(long topMin, long topMax, long skyMin, boolean known) {
    }

    public TopRange topRange(long cx, long cz) {
        MemorySegment out = SCRATCH.get().small;
        try {
            int r = (int) CendaVoxelWorld.TOP_RANGE.invokeExact(pointer(), cx, cz, out, out.asSlice(8), out.asSlice(16));
            if (r < 0 && r != ERR_MISSING) throw failure("cv_top_range", r);
            return new TopRange(out.get(I64, 0), out.get(I64, 8), out.get(I64, 16), r == 0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** The column record (tracker + generated runs), or null when the world has no such column. */
    public byte[] encodeColumn(long cx, long cz) {
        Scratch s = SCRATCH.get();
        long cap = 16 * 1024;
        try {
            for (;;) {
                MemorySegment out = s.bytes(cap);
                long n = (long) CendaVoxelWorld.COLUMN_ENCODE.invokeExact(pointer(), cx, cz, out, out.byteSize());
                if (isNeed(n)) {
                    cap = needed(n);
                    continue;
                }
                if (n == ERR_MISSING) return null;
                if (n < 0) throw failure("cv_column_encode", n);
                return out.asSlice(0, n).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
            }
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Creates or replaces a column from {@link #encodeColumn} bytes (corrupt bytes throw). */
    public void decodeColumn(long cx, long cz, byte[] bytes) {
        MemorySegment seg = SCRATCH.get().bytes(Math.max(1, bytes.length));
        MemorySegment.copy(bytes, 0, seg, java.lang.foreign.ValueLayout.JAVA_BYTE, 0, bytes.length);
        try {
            int r = (int) CendaVoxelWorld.COLUMN_DECODE.invokeExact(pointer(), cx, cz, seg, (long) bytes.length);
            if (r < 0) throw failure("cv_column_decode", r);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Forgets a column (after encoding it). False when it still has loaded cubes or does not exist. */
    public boolean removeColumn(long cx, long cz) {
        try {
            int r = (int) CendaVoxelWorld.COLUMN_REMOVE.invokeExact(pointer(), cx, cz);
            if (r == ERR_EXISTS || r == ERR_MISSING) return false;
            if (r < 0) throw failure("cv_column_remove", r);
            return true;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ---- mesher + visibility (#143) -------------------------------------------------------------------------

    /**
     * Meshes a cube ({@code CV_MESH_*} flags) into this thread's reusable {@link CubeMesh} (grown and
     * retried on {@code CV_NEED}); null when the cube is not loaded. The result is valid until the next
     * meshCube on the same thread.
     */
    public CubeMesh meshCube(long cx, long cy, long cz, int flags) {
        return CubeMesh.forThread().mesh(pointer(), cx, cy, cz, flags) ? CubeMesh.forThread() : null;
    }

    /** The cube's 15 face-pair visibility bits, or {@link #MISSING}. */
    public int cubeVisibility(long cx, long cy, long cz) {
        try {
            int r = (int) CendaVoxelWorld.require(CendaVoxelWorld.CUBE_VISIBILITY, "cv_cube_visibility")
                .invokeExact(pointer(), cx, cy, cz);
            if (r < 0 && r != MISSING) throw failure("cv_cube_visibility", r);
            return r;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ---- host models, fluids, face textures (native host geometry, #143) ---------------------------------------

    /** {@link ModelQuad#flags}: alpha-blended (translucent pass). */
    public static final int MODEL_TRANSLUCENT = 1;
    /** {@link ModelQuad#flags}: alpha-tested (cutout). */
    public static final int MODEL_CUTOUT = 2;
    /** {@link ModelQuad#flags}: no ambient occlusion (sky light only). */
    public static final int MODEL_NO_AO = 4;
    /** {@link ModelQuad#cullFace}: never culled (crosses, interior geometry). */
    public static final int CULL_NEVER = 6;
    private static final long MODEL_QUAD_BYTES = 96;
    /** Lowest cell-local model corner coordinate {@link #defineModel} accepts ({@code CV_MODEL_CORNER_MIN}). */
    public static final float MODEL_CORNER_MIN = -4f;
    /** Highest cell-local model corner coordinate {@link #defineModel} accepts ({@code CV_MODEL_CORNER_MAX}). */
    public static final float MODEL_CORNER_MAX = 5f;

    /**
     * One quad of a host model ({@code cv_model_quad}): four cell-local corners {@code pos[corner * 3 + axis]}
     * in [{@link #MODEL_CORNER_MIN}, {@link #MODEL_CORNER_MAX}] (usually [0, 1]; a model may reach 4 cells past
     * its anchor), their UVs {@code uv[corner * 2 + c]}, the normal, the texture-array layer, the face (0..5,
     * cv_quad order) whose covering neighbour hides it or {@link #CULL_NEVER}, and {@code MODEL_*} flags. Drawn
     * as triangles (0,1,2) (0,2,3). A quad with a corner outside [-1, 2] is never culled (one neighbour cell
     * cannot hide it) and neighbour face rules skip it; see {@code cv_model_quad} in voxel_world.h.
     */
    public record ModelQuad(float[] pos, float[] uv, float[] normal, int textureLayer, int cullFace, int flags) {
        public ModelQuad {
            if (pos.length != 12 || uv.length != 8 || normal.length != 3) {
                throw new IllegalArgumentException("ModelQuad needs 12 position, 8 uv and 3 normal floats");
            }
        }
    }

    /** Defines (or replaces) host model {@code modelId} (0..65535). */
    public void defineModel(int modelId, java.util.List<ModelQuad> quads) {
        MethodHandle h = CendaVoxelWorld.require(CendaVoxelWorld.MODEL_DEFINE, "cv_model_define");
        try (Arena a = Arena.ofConfined()) {
            MemorySegment seg = a.allocate(Math.max(1, quads.size()) * MODEL_QUAD_BYTES, 8);
            for (int i = 0; i < quads.size(); i++) {
                ModelQuad q = quads.get(i);
                long base = i * MODEL_QUAD_BYTES;
                MemorySegment.copy(q.pos(), 0, seg, java.lang.foreign.ValueLayout.JAVA_FLOAT, base, 12);
                MemorySegment.copy(q.uv(), 0, seg, java.lang.foreign.ValueLayout.JAVA_FLOAT, base + 48, 8);
                MemorySegment.copy(q.normal(), 0, seg, java.lang.foreign.ValueLayout.JAVA_FLOAT, base + 80, 3);
                seg.set(java.lang.foreign.ValueLayout.JAVA_SHORT, base + 92, (short) q.textureLayer());
                seg.set(java.lang.foreign.ValueLayout.JAVA_BYTE, base + 94, (byte) q.cullFace());
                seg.set(java.lang.foreign.ValueLayout.JAVA_BYTE, base + 95, (byte) q.flags());
            }
            int r = (int) h.invokeExact(pointer(), modelId, seg, quads.size());
            if (r < 0) throw failure("cv_model_define", r);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * Binds {@code (blockId, stateValue of layer stateLayer)} to a model; {@code stateLayer < 0} binds every
     * state of the block; {@code modelId < 0} removes the binding.
     */
    public void bindBlockModel(int blockId, int stateLayer, int stateValue, int modelId) {
        MethodHandle h = CendaVoxelWorld.require(CendaVoxelWorld.BLOCK_MODEL_BIND, "cv_block_model_bind");
        try {
            int r = (int) h.invokeExact(pointer(), (char) blockId, stateLayer, (char) stateValue, modelId);
            if (r < 0) throw failure("cv_block_model_bind", r);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * Meshes cells of block {@code fluidId} as fluid (WATERQUAD16) from host layer {@code layer}: value =
     * level(0..7) | falling << 3 | source << 4 | (river flow octant + 1) << 5, 0 = source.
     * {@code surfaceByLevel} = 8 surface heights in the cell; [0] is used for sources and falling cells.
     */
    public void setFluid(int layer, int fluidId, float[] surfaceByLevel) {
        if (surfaceByLevel.length != 8) throw new IllegalArgumentException("surfaceByLevel needs 8 floats");
        fluidDesc(layer, fluidId, surfaceByLevel);
    }

    /** Stops meshing {@code fluidId} as a fluid. */
    public void removeFluid(int fluidId) {
        fluidDesc(-1, fluidId, new float[8]);
    }

    private void fluidDesc(int layer, int fluidId, float[] surface) {
        MethodHandle h = CendaVoxelWorld.require(CendaVoxelWorld.FLUID_SET, "cv_fluid_set");
        try (Arena a = Arena.ofConfined()) {
            MemorySegment d = a.allocate(40, 8);
            d.set(I32, 0, layer);
            d.set(java.lang.foreign.ValueLayout.JAVA_SHORT, 4, (short) fluidId);
            MemorySegment.copy(surface, 0, d, java.lang.foreign.ValueLayout.JAVA_FLOAT, 8, 8);
            int r = (int) h.invokeExact(pointer(), d);
            if (r < 0) throw failure("cv_fluid_set", r);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** One cube face's texture for the GPU packer ({@code cv_face_tex}): array layer, UV orientation 0..7. */
    public record FaceTexture(int layer, int orientation, boolean alphaTest) {
    }

    /** Sets the six face textures (cv_quad face order) the packer writes into QUAD16 records of {@code blockId}. */
    public void setBlockFaces(int blockId, FaceTexture[] faces) {
        if (faces.length != 6) throw new IllegalArgumentException("six faces expected");
        MethodHandle h = CendaVoxelWorld.require(CendaVoxelWorld.BLOCK_FACES_SET, "cv_block_faces_set");
        try (Arena a = Arena.ofConfined()) {
            MemorySegment seg = a.allocate(24, 8);
            for (int f = 0; f < 6; f++) {
                seg.set(java.lang.foreign.ValueLayout.JAVA_SHORT, f * 4L, (short) faces[f].layer());
                seg.set(java.lang.foreign.ValueLayout.JAVA_BYTE, f * 4L + 2, (byte) faces[f].orientation());
                seg.set(java.lang.foreign.ValueLayout.JAVA_BYTE, f * 4L + 3, (byte) (faces[f].alphaTest() ? 1 : 0));
            }
            int r = (int) h.invokeExact(pointer(), (char) blockId, seg);
            if (r < 0) throw failure("cv_block_faces_set", r);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@link #addFaceRule} kind: drop the face when the neighbour across it is one of the ids. */
    public static final int FACE_RULE_CULL_WHEN_NEIGHBOUR_IN = 1;
    /** {@link #addFaceRule} kind: draw the face opaque (alpha test + blending off) when the neighbour is one of the ids. */
    public static final int FACE_RULE_OPAQUE_WHEN_NEIGHBOUR_IN = 2;
    /** {@link #addFaceRule} face mask: all six faces. */
    public static final int ALL_FACES = 0x3F;

    /** {@link #addFaceRule} face mask bit of a cv_quad face (0 top, 1 bottom, 2 north, 3 south, 4 east, 5 west). */
    public static int faceBit(int face) {
        return 1 << face;
    }

    /**
     * Adds a neighbour face rule (voxel_world.h {@code cv_face_rule_add}): faces in {@code faceMask} of
     * {@code blockId} cells (every state when {@code stateLayer < 0}, else only cells whose layer holds
     * {@code stateValue}) whose neighbour across the face is one of {@code neighbourIds} are culled
     * ({@link #FACE_RULE_CULL_WHEN_NEIGHBOUR_IN}) or drawn opaque ({@link #FACE_RULE_OPAQUE_WHEN_NEIGHBOUR_IN}).
     * Applies to cube faces, model quads with a cull face and (cull only) fluid faces.
     */
    public void addFaceRule(int blockId, int stateLayer, int stateValue, int faceMask, int kind, int... neighbourIds) {
        if (neighbourIds.length == 0) throw new IllegalArgumentException("at least one neighbour id");
        MethodHandle h = CendaVoxelWorld.require(CendaVoxelWorld.FACE_RULE_ADD, "cv_face_rule_add");
        try (Arena a = Arena.ofConfined()) {
            MemorySegment ids = a.allocate(2L * neighbourIds.length, 2);
            for (int i = 0; i < neighbourIds.length; i++) {
                ids.set(java.lang.foreign.ValueLayout.JAVA_SHORT, 2L * i, (short) neighbourIds[i]);
            }
            int r = (int) h.invokeExact(pointer(), (char) blockId, stateLayer, (char) stateValue, (byte) faceMask, kind,
                ids, neighbourIds.length);
            if (r < 0) throw failure("cv_face_rule_add", r);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Removes every face rule of {@code blockId}, or of every block when {@code blockId < 0}. */
    public void clearFaceRules(int blockId) {
        MethodHandle h = CendaVoxelWorld.require(CendaVoxelWorld.FACE_RULES_CLEAR, "cv_face_rules_clear");
        try {
            int r = (int) h.invokeExact(pointer(), blockId);
            if (r < 0) throw failure("cv_face_rules_clear", r);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@link #visibleCubes}: visible present cubes and unknown missing ones, both as triples. */
    public record VisibleCubes(long[] visible, long[] missing, long missingTotal) {
    }

    /**
     * Visibility-graph walk from the camera cube (voxel_world.h {@code cv_visible_cubes}); {@code planes}
     * is null or 24 floats (6 planes a,b,c,d in camera-cube-relative block coordinates). Allocates its
     * result arrays on every call; per-frame callers use
     * {@link #visibleCubes(long, long, long, int, float[], VisibleCubeBuffers)}. {@code missing} holds at
     * most {@code missingCap} cubes, {@code missingTotal} counts all of them.
     */
    public VisibleCubes visibleCubes(long camCx, long camCy, long camCz, int maxRadius, float[] planes, int missingCap) {
        MethodHandle h = CendaVoxelWorld.require(CendaVoxelWorld.VISIBLE_CUBES, "cv_visible_cubes");
        Scratch s = SCRATCH.get();
        try (Arena a = Arena.ofConfined()) {
            MemorySegment planeSeg = MemorySegment.NULL;
            if (planes != null) {
                if (planes.length < 24) throw new IllegalArgumentException("planes needs 24 floats");
                planeSeg = a.allocate(24 * 4L, 8);
                MemorySegment.copy(planes, 0, planeSeg, java.lang.foreign.ValueLayout.JAVA_FLOAT, 0, 24);
            }
            MemorySegment missingCount = a.allocate(8, 8);
            long cap = 4096;
            long missCap = Math.max(1, missingCap);
            MemorySegment missing = a.allocate(3 * missCap * 8, 8);
            for (;;) {
                MemorySegment out = s.longs(3 * cap);
                long n = (long) h.invokeExact(pointer(), camCx, camCy, camCz, maxRadius, planeSeg, out, cap,
                    missing, missCap, missingCount);
                if (isNeed(n)) {
                    // The native call reports "needed" for whichever list overflowed and never truncates:
                    // grow the one that did (the missing list too - the old loop grew only the visible one
                    // and spun forever once more than missingCap cubes were missing).
                    long total = missingCount.get(I64, 0);
                    if (total > missCap) {
                        missCap = total + 64;
                        missing = a.allocate(3 * missCap * 8, 8);
                    } else {
                        cap = needed(n) + 64;
                    }
                    continue;
                }
                if (n < 0) throw failure("cv_visible_cubes", n);
                long[] visible = new long[(int) (3 * n)];
                MemorySegment.copy(out, I64, 0, visible, 0, visible.length);
                long total = missingCount.get(I64, 0);
                long[] miss = new long[(int) (3 * Math.min(total, Math.max(0, missingCap)))];
                MemorySegment.copy(missing, I64, 0, miss, 0, miss.length);
                return new VisibleCubes(visible, miss, total);
            }
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * {@link #visibleCubes(long, long, long, int, float[], int)} into caller-owned, reusable buffers — no
     * allocation per call once {@code out} has grown to the walk's size (it grows itself when the native
     * call reports an overflow, then the walk is retried). {@code planes} (null or 24 floats) is copied
     * into {@code out}'s native plane block. Returns the visible count; {@code out} holds both lists.
     */
    public int visibleCubes(long camCx, long camCy, long camCz, int maxRadius, float[] planes,
                            VisibleCubeBuffers out) {
        MemorySegment planeSeg = out.planes(planes);
        for (;;) {
            long n = visibleCubesInto(camCx, camCy, camCz, maxRadius, planeSeg, out.visible(),
                out.missing(), out.missingCountOut());
            long missing = out.missingCountOut().get(I64, 0);
            if (n < 0) {
                out.ensure(missing > out.missingCapacity() ? 0 : -n, missing);
                continue;
            }
            out.set((int) n, (int) missing);
            return (int) n;
        }
    }

    /**
     * The raw walk into caller memory ({@code cv_visible_cubes} as is): {@code planes} = 24 native floats
     * or {@link MemorySegment#NULL}; {@code outXyz} / {@code outMissing} hold {@code byteSize / 24} triples
     * each ({@code outMissing} may be NULL to skip the list); {@code outMissingCount} (8 bytes, or NULL)
     * receives the missing count. Returns the visible count, or {@code -needed} (negative) when a list was
     * too small: the visible list when {@code needed} visible cubes did not fit, else the missing list
     * (compare the reported missing count with its capacity). Nothing is written on overflow.
     */
    public long visibleCubesInto(long camCx, long camCy, long camCz, int maxRadius, MemorySegment planes,
                                 MemorySegment outXyz, MemorySegment outMissing, MemorySegment outMissingCount) {
        MethodHandle h = CendaVoxelWorld.require(CendaVoxelWorld.VISIBLE_CUBES, "cv_visible_cubes");
        long cap = outXyz.byteSize() / 24;
        long missingCap = outMissing.equals(MemorySegment.NULL) ? 0 : outMissing.byteSize() / 24;
        try {
            long n = (long) h.invokeExact(pointer(), camCx, camCy, camCz, maxRadius, planes, outXyz, cap,
                outMissing, missingCap, outMissingCount);
            if (isNeed(n)) return -needed(n);
            if (n < 0) throw failure("cv_visible_cubes", n);
            return n;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }
}
