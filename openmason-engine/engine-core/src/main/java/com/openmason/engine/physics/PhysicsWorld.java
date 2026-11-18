package com.openmason.engine.physics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.ref.Cleaner;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static com.openmason.engine.physics.CendaPhysics.AABB_BYTES;
import static com.openmason.engine.physics.CendaPhysics.BODY_BYTES;
import static com.openmason.engine.physics.CendaPhysics.BOX_BYTES;
import static com.openmason.engine.physics.CendaPhysics.DESC_BYTES;
import static com.openmason.engine.physics.CendaPhysics.F32;
import static com.openmason.engine.physics.CendaPhysics.F64;
import static com.openmason.engine.physics.CendaPhysics.FLUID_BYTES;
import static com.openmason.engine.physics.CendaPhysics.HIT_BYTES;
import static com.openmason.engine.physics.CendaPhysics.I16;
import static com.openmason.engine.physics.CendaPhysics.I32;
import static com.openmason.engine.physics.CendaPhysics.I64;
import static com.openmason.engine.physics.CendaPhysics.INTEGRATOR_BYTES;
import static com.openmason.engine.physics.CendaPhysics.SAMPLE_BYTES;
import static com.openmason.engine.physics.CendaPhysics.STATS_BYTES;
import static com.openmason.engine.physics.CendaPhysics.rethrow;

/**
 * One native physics world ({@code cp_world}, OpenMason #117): voxel collision over a brick cache
 * the host fills on demand ({@link VoxelPhysicsSource}), swept box movement with step-up and
 * grounding, DDA raycasts, box sweeps, fluid submersion and buoyancy, and dynamic boxes. The
 * behaviour lives in C++ ({@code cenda/native/kernels/src/physics/}); this class only marshals.
 *
 * <p><b>Shapes and categories.</b> The host defines what each shape id looks like (boxes in the unit
 * cell, a category mask, {@link #SHAPE_PARTIAL}) and fills cells with shape ids and fluid ids. Every
 * query takes a category mask, so one cache answers walking, picking and landing with different
 * rules. Shape 0 is empty, fluid 0 is none.
 *
 * <p><b>Keeping the cache honest.</b> The cache keeps what it pulled until told otherwise: the host
 * calls {@link #invalidateCell}/{@link #invalidateBox} whenever a cell's shape or fluid may have
 * changed (block edits, state changes, chunk loads/unloads). Invalidation is lazy and cheap — the
 * region is refilled the next time a query touches it.
 *
 * <p><b>Coordinates (OpenMason #142, CP ABI 2).</b> Cells are ABSOLUTE {@code long}s everywhere
 * (the source's regions, invalidation, cell queries, {@link RayHit} cells). Positions — bodies,
 * dynamic boxes, rays, sweeps, footprints, fluid points, hit points — are {@code double}s RELATIVE to
 * the world's frame origin ({@link #setOrigin}, default 0): the cell holding a local position
 * {@code p} is {@code origin + floor(p)}. Geometry runs in that local frame, so precision depends on
 * the distance from the origin (~1/4000 block at 2^40 from it); a host that keeps the origin near its
 * players gets that precision across the whole {@code long} range. Moving the origin keeps every
 * cached cell valid (the cache is keyed by absolute cells) and every dynamic box in its absolute
 * place.
 *
 * <p><b>Threads.</b> The {@code invalidate*} methods may be called from any thread. Every other
 * method belongs to the world's owner thread; the source is called on it. Result objects passed in
 * are filled in place.
 */
public final class PhysicsWorld implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(PhysicsWorld.class);

    /** A body may step onto this shape with its partial step height (snow layers, stair treads). */
    public static final int SHAPE_PARTIAL = 1;
    /** Shapes/fluids per table. Id 0 is reserved (empty / none). */
    public static final int MAX_SHAPES = 65536;
    public static final int MAX_FLUIDS = 65536;
    public static final int SHAPE_MAX_BOXES = 16;

    private static final Cleaner CLEANER = Cleaner.create();
    private static final MethodHandle FILL_TARGET;

    static {
        try {
            FILL_TARGET = MethodHandles.lookup().findVirtual(PhysicsWorld.class, "fillUpcall",
                MethodType.methodType(int.class, MemorySegment.class, long.class, long.class, long.class, int.class,
                    int.class, int.class, MemorySegment.class, MemorySegment.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final ReentrantReadWriteLock lifetime = new ReentrantReadWriteLock();
    private final Cleaner.Cleanable cleanable;
    private volatile MemorySegment world;

    private final VoxelPhysicsSource source;
    private final short[] fillShapes;
    private final short[] fillFluids;
    private boolean fillFailureLogged;

    // Owner-thread scratch, freed with the world object.
    private final Arena arena = Arena.ofAuto();
    private final MemorySegment body;
    private final MemorySegment integrator;
    private final MemorySegment sample;
    private final MemorySegment hit;
    private final MemorySegment vec;      // double[6]: origin + direction / from + to / a box
    private final MemorySegment cells;    // int64 xyz triplets (nearestOpenCell)
    private final MemorySegment shapeBoxes; // cp_box floats (defineShape)
    private MemorySegment boxes;          // cp_aabb doubles, grown on demand (rayBoxes)
    private MemorySegment doubles;        // grown on demand (rayBoxes out)
    private final MemorySegment cellOut;  // uint16 shape + uint16 fluid
    private final MemorySegment statsOut;
    private final MemorySegment originOut;
    private long originX, originY, originZ;

    private PhysicsWorld(VoxelPhysicsSource source, int brickShift, int cacheBricks, int unloadedShape) {
        this.source = source;
        int edge = 1 << brickShift;
        this.fillShapes = new short[edge * edge * edge];
        this.fillFluids = new short[edge * edge * edge];
        this.body = arena.allocate(BODY_BYTES, 8);
        this.integrator = arena.allocate(INTEGRATOR_BYTES, 8);
        this.sample = arena.allocate(SAMPLE_BYTES, 8);
        this.hit = arena.allocate(HIT_BYTES, 8);
        this.vec = arena.allocate(F64, 6);
        this.cells = arena.allocate(I64, 3 * 64);
        this.shapeBoxes = arena.allocate(BOX_BYTES * SHAPE_MAX_BOXES, 8);
        this.boxes = arena.allocate(AABB_BYTES * 16, 8);
        this.doubles = arena.allocate(F64, 64);
        this.cellOut = arena.allocate(I16, 2);
        this.statsOut = arena.allocate(STATS_BYTES, 8);
        this.originOut = arena.allocate(I64, 3);

        MemorySegment stub = Linker.nativeLinker().upcallStub(FILL_TARGET.bindTo(this), CendaPhysics.FILL_FN, arena);
        MemorySegment desc = arena.allocate(DESC_BYTES, 8);
        desc.set(CendaPhysics.ADDR, 0, stub);
        desc.set(CendaPhysics.ADDR, 8, MemorySegment.NULL);
        desc.set(I32, 16, brickShift);
        desc.set(I32, 20, cacheBricks);
        desc.set(I16, 24, (short) unloadedShape);
        desc.set(I16, 26, (short) 0);
        desc.set(I32, 28, 0);
        try {
            MemorySegment w = (MemorySegment) CendaPhysics.WORLD_CREATE.invokeExact(desc);
            if (w.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("cp_world_create failed: " + CendaPhysics.lastError());
            }
            this.world = w;
        } catch (Throwable t) {
            throw rethrow(t);
        }
        // The cleaner action must not reach this object; it holds the native pointer only. The
        // upcall stub lives in this object's arena, which stays alive as long as it is reachable —
        // and an unreachable world receives no calls, so the stub is never used after it is gone.
        this.cleanable = CLEANER.register(this, new Destroy(world));
    }

    /**
     * Creates a native physics world.
     *
     * @param source        the host's cells
     * @param brickShift    brick edge = 1 &lt;&lt; brickShift cells, 3..5; match the host's chunk size
     *                      when it is a power of two (a brick then never spans two chunks)
     * @param cacheBricks   bricks kept before the least recently used go (&lt;= 0 picks 2048)
     * @param unloadedShape what a cell of an unloaded region is to physics (0 = empty)
     * @throws IllegalStateException when Cenda physics is unavailable (the message says why)
     */
    public static PhysicsWorld create(VoxelPhysicsSource source, int brickShift, int cacheBricks, int unloadedShape) {
        if (!CendaPhysics.isAvailable()) {
            throw new IllegalStateException("Cenda physics unavailable: " + CendaPhysics.unavailableReason());
        }
        if (source == null) {
            throw new IllegalArgumentException("source");
        }
        if (brickShift < 3 || brickShift > 5) {
            throw new IllegalArgumentException("brickShift must be 3..5, was " + brickShift);
        }
        return new PhysicsWorld(source, brickShift, cacheBricks, unloadedShape);
    }

    private record Destroy(MemorySegment world) implements Runnable {
        @Override
        public void run() {
            try {
                CendaPhysics.WORLD_DESTROY.invokeExact(world);
            } catch (Throwable ignored) {
                // nothing to do: the process is releasing the world
            }
        }
    }

    // Called by native code inside an owner-thread call. Must never throw: an exception escaping an
    // upcall terminates the JVM. A failing source reads as "not loaded" (nothing cached).
    @SuppressWarnings("unused")
    private int fillUpcall(MemorySegment user, long x0, long y0, long z0, int sx, int sy, int sz,
                           MemorySegment shapesOut, MemorySegment fluidsOut) {
        try {
            int n = sx * sy * sz;
            java.util.Arrays.fill(fillShapes, 0, n, (short) 0);
            java.util.Arrays.fill(fillFluids, 0, n, (short) 0);
            if (!source.fill(x0, y0, z0, sx, sy, sz, fillShapes, fillFluids)) {
                return 0;
            }
            long bytes = (long) n * 2L;
            MemorySegment.copy(fillShapes, 0, shapesOut.reinterpret(bytes), I16, 0, n);
            MemorySegment.copy(fillFluids, 0, fluidsOut.reinterpret(bytes), I16, 0, n);
            return 1;
        } catch (Throwable t) {
            if (!fillFailureLogged) {
                fillFailureLogged = true;
                LOGGER.error("Physics source failed to fill [{}, {}, {}] x [{}, {}, {}]; treating it as unloaded",
                    x0, y0, z0, sx, sy, sz, t);
            }
            return 0;
        }
    }

    @Override
    public void close() {
        lifetime.writeLock().lock();
        try {
            if (world != null) {
                world = null;
                cleanable.clean();
            }
        } finally {
            lifetime.writeLock().unlock();
        }
    }

    public boolean isClosed() {
        return world == null;
    }

    /**
     * The native {@code cp_world}, for engine bindings whose native code reads this world's cache
     * (Cenda navigation, {@code com.openmason.engine.nav}). Owner thread; invalid once the world is
     * closed (close those users first).
     *
     * @throws IllegalStateException when the world is closed
     */
    public MemorySegment nativeHandle() {
        return w();
    }

    private MemorySegment w() {
        MemorySegment current = world;
        if (current == null) {
            throw new IllegalStateException("PhysicsWorld is closed");
        }
        return current;
    }

    // ---- the frame ---------------------------------------------------------------------------------

    /**
     * Moves the frame origin (an absolute cell). Every position passed in or returned afterwards is
     * relative to it. Cached cells stay valid; dynamic boxes keep their absolute place (their stored
     * coordinates shift by old - new). Owner thread.
     */
    public void setOrigin(long ox, long oy, long oz) {
        try {
            int rc = (int) CendaPhysics.SET_ORIGIN.invokeExact(w(), ox, oy, oz);
            if (rc != 0) {
                throw new IllegalStateException("cp_set_origin: " + CendaPhysics.lastError());
            }
        } catch (Throwable t) {
            throw rethrow(t);
        }
        originX = ox;
        originY = oy;
        originZ = oz;
    }

    /** The frame origin's absolute x (the cell of local 0). */
    public long originX() {
        return originX;
    }

    public long originY() {
        return originY;
    }

    public long originZ() {
        return originZ;
    }

    /** The native origin, read back (for checks; {@link #originX} etc. mirror it). */
    long[] nativeOrigin() {
        try {
            int rc = (int) CendaPhysics.GET_ORIGIN.invokeExact(w(), originOut);
            if (rc != 0) {
                throw new IllegalStateException("cp_get_origin: " + CendaPhysics.lastError());
            }
        } catch (Throwable t) {
            throw rethrow(t);
        }
        return new long[] {originOut.get(I64, 0), originOut.get(I64, 8), originOut.get(I64, 16)};
    }

    // ---- tables ------------------------------------------------------------------------------------

    /**
     * Defines or redefines a shape (1 .. MAX_SHAPES-1).
     *
     * @param boxes    6 floats per box ({@code minX, minY, minZ, maxX, maxY, maxZ}) in the unit cell;
     *                 empty makes the shape empty
     * @param category the categories it belongs to (queries match {@code category & mask})
     * @param flags    {@link #SHAPE_PARTIAL} or 0
     */
    public void defineShape(int id, float[] boxes, int category, int flags) {
        int count = boxes == null ? 0 : boxes.length / 6;
        if (count > SHAPE_MAX_BOXES) {
            throw new IllegalArgumentException("a shape has at most " + SHAPE_MAX_BOXES + " boxes, got " + count);
        }
        if (count > 0) {
            MemorySegment.copy(boxes, 0, this.shapeBoxes, F32, 0, count * 6);
        }
        try {
            int rc = (int) CendaPhysics.SHAPE_DEFINE.invokeExact(w(), id, this.shapeBoxes, count, category, flags);
            if (rc != 0) {
                throw new IllegalArgumentException("cp_shape_define(" + id + "): " + CendaPhysics.lastError());
            }
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * Defines or redefines a fluid (1 .. MAX_FLUIDS-1): its surface height in the cell (0..1), the
     * force of its current on a body (acceleration) and its categories.
     */
    public void defineFluid(int id, float surface, float flowX, float flowY, float flowZ, int category) {
        MemorySegment f = sample; // 24 bytes, same size as cp_fluid
        f.set(F32, 0, surface);
        f.set(F32, 4, flowX);
        f.set(F32, 8, flowY);
        f.set(F32, 12, flowZ);
        f.set(I32, 16, category);
        f.set(I32, 20, 0);
        try {
            int rc = (int) CendaPhysics.FLUID_DEFINE.invokeExact(w(), id, f);
            if (rc != 0) {
                throw new IllegalArgumentException("cp_fluid_define(" + id + "): " + CendaPhysics.lastError());
            }
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ---- the cache ----------------------------------------------------------------------------------

    /** Any thread. The (absolute) cell is refilled the next time a query touches it. */
    public void invalidateCell(long x, long y, long z) {
        lifetime.readLock().lock();
        try {
            MemorySegment current = world;
            if (current != null) {
                CendaPhysics.INVALIDATE_CELL.invokeExact(current, x, y, z);
            }
        } catch (Throwable t) {
            throw rethrow(t);
        } finally {
            lifetime.readLock().unlock();
        }
    }

    /** Any thread. The half-open absolute box {@code [x0,x1) x [y0,y1) x [z0,z1)} is refilled when next touched. */
    public void invalidateBox(long x0, long y0, long z0, long x1, long y1, long z1) {
        lifetime.readLock().lock();
        try {
            MemorySegment current = world;
            if (current != null) {
                CendaPhysics.INVALIDATE_BOX.invokeExact(current, x0, y0, z0, x1, y1, z1);
            }
        } catch (Throwable t) {
            throw rethrow(t);
        } finally {
            lifetime.readLock().unlock();
        }
    }

    /** Any thread. Forgets every cached cell. */
    public void invalidateAll() {
        lifetime.readLock().lock();
        try {
            MemorySegment current = world;
            if (current != null) {
                CendaPhysics.INVALIDATE_ALL.invokeExact(current);
            }
        } catch (Throwable t) {
            throw rethrow(t);
        } finally {
            lifetime.readLock().unlock();
        }
    }

    /** The (absolute) cell's shape id (pulling it in if needed). */
    public int cellShape(long x, long y, long z) {
        readCell(x, y, z);
        return Short.toUnsignedInt(cellOut.get(I16, 0));
    }

    /** The (absolute) cell's fluid id (pulling it in if needed). */
    public int cellFluid(long x, long y, long z) {
        readCell(x, y, z);
        return Short.toUnsignedInt(cellOut.get(I16, 2));
    }

    private void readCell(long x, long y, long z) {
        try {
            int rc = (int) CendaPhysics.CELL_GET.invokeExact(w(), x, y, z, cellOut, cellOut.asSlice(2));
            if (rc != 0) {
                throw new IllegalStateException(CendaPhysics.lastError());
            }
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public PhysicsStats stats() {
        MemorySegment s = statsOut;
        try {
            CendaPhysics.STATS_GET.invokeExact(w(), s);
        } catch (Throwable t) {
            throw rethrow(t);
        }
        return new PhysicsStats(s.get(I64, 0), s.get(I64, 8), s.get(I64, 16), s.get(I32, 24), s.get(I32, 28),
            s.get(I32, 32));
    }

    // ---- dynamic boxes -------------------------------------------------------------------------------

    /** Adds a box (frame coordinates). Returns its id (&gt; 0). */
    public int addBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                      int category, int flags, long user) {
        putBox(vec, minX, minY, minZ, maxX, maxY, maxZ);
        try {
            int id = (int) CendaPhysics.BOX_ADD.invokeExact(w(), vec, category, flags, user);
            if (id <= 0) {
                throw new IllegalArgumentException("cp_box_add: " + CendaPhysics.lastError());
            }
            return id;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public void setBox(int id, double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                       int category, int flags) {
        putBox(vec, minX, minY, minZ, maxX, maxY, maxZ);
        try {
            int rc = (int) CendaPhysics.BOX_SET.invokeExact(w(), id, vec, category, flags);
            if (rc != 0) {
                throw new IllegalArgumentException("cp_box_set(" + id + "): " + CendaPhysics.lastError());
            }
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Removes a box; false when the id names no live box. */
    public boolean removeBox(int id) {
        try {
            return (int) CendaPhysics.BOX_REMOVE.invokeExact(w(), id) == 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public void clearBoxes() {
        try {
            CendaPhysics.BOX_CLEAR.invokeExact(w());
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private static void putBox(MemorySegment s, double minX, double minY, double minZ, double maxX, double maxY,
                               double maxZ) {
        s.set(F64, 0, minX);
        s.set(F64, 8, minY);
        s.set(F64, 16, minZ);
        s.set(F64, 24, maxX);
        s.set(F64, 32, maxY);
        s.set(F64, 40, maxZ);
    }

    // ---- cell and point queries ----------------------------------------------------------------------

    /**
     * Height above the cell floor of the tallest box of the (absolute) cell's shape (in {@code mask})
     * that overlaps the XZ footprint (frame coordinates, inclusive edges, clipped to the cell); 0 when
     * none does.
     */
    public float cellTop(long x, long y, long z, double minX, double minZ, double maxX, double maxZ, int mask) {
        try {
            return (float) CendaPhysics.CELL_TOP.invokeExact(w(), x, y, z, minX, minZ, maxX, maxZ, mask);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@link #cellTop} over the whole (absolute) cell. */
    public float cellTop(long x, long y, long z, int mask) {
        double lx = (double) (x - originX), lz = (double) (z - originZ);
        return cellTop(x, y, z, lx, lz, lx + 1.0, lz + 1.0, mask);
    }

    /** Whether any collider in {@code mask} overlaps the box (frame coordinates) by more than the contact tolerance. */
    public boolean overlaps(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, int mask) {
        putBox(vec, minX, minY, minZ, maxX, maxY, maxZ);
        try {
            return (int) CendaPhysics.OVERLAPS.invokeExact(w(), vec, mask) == 1;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * Of {@code count} candidate cells (absolute {@code xyz} triplets in {@code cellsXyz}), the index of
     * the one holding nothing in {@code mask} whose centre is nearest the frame position
     * {@code (px, py, pz)} (ties to the earlier candidate). With {@code preferenceGiven} false, the first
     * open candidate. -1 when none is open.
     */
    public int nearestOpenCell(long[] cellsXyz, int count, boolean preferenceGiven, double px, double py, double pz,
                               int mask) {
        if (count <= 0) return -1;
        if (count > 64) throw new IllegalArgumentException("at most 64 candidates");
        MemorySegment.copy(cellsXyz, 0, cells, I64, 0, count * 3);
        MemorySegment pref = MemorySegment.NULL;
        if (preferenceGiven) {
            vec.set(F64, 0, px);
            vec.set(F64, 8, py);
            vec.set(F64, 16, pz);
            pref = vec;
        }
        try {
            return (int) CendaPhysics.NEAREST_OPEN_CELL.invokeExact(w(), cells, count, pref, mask);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ---- bodies --------------------------------------------------------------------------------------

    /** Moves the body by its velocity times {@code dt} (see {@code cp_move}). Updates it in place. */
    public void move(PhysicsBody b, float dt) {
        writeBody(b);
        try {
            int rc = (int) CendaPhysics.MOVE.invokeExact(w(), body, dt);
            if (rc != 0) {
                throw new IllegalArgumentException("cp_move: " + CendaPhysics.lastError());
            }
        } catch (Throwable t) {
            throw rethrow(t);
        }
        readBody(b);
    }

    /**
     * Integrates and moves one body (see {@code cp_step}): fluid, gravity, the move, ground friction.
     * {@code out} (may be null) receives the fluid sample taken before the move.
     */
    public void step(PhysicsBody b, float dt, PhysicsIntegrator params, FluidSample out) {
        writeBody(b);
        writeIntegrator(params);
        try {
            int n = (int) CendaPhysics.STEP.invokeExact(w(), body, 1, dt, integrator, sample);
            if (n != 1) {
                throw new IllegalArgumentException("cp_step: " + CendaPhysics.lastError());
            }
        } catch (Throwable t) {
            throw rethrow(t);
        }
        readBody(b);
        if (out != null) readSample(out);
    }

    /** Measures fluid around a body (frame positions; see {@code cp_fluid_measure}) into {@code out}. */
    public FluidSample measureFluid(double x, double feetY, double z, float height, float halfX,
                                    double flowBaseY, float flowHeight, int mask, FluidSample out) {
        try {
            int rc = (int) CendaPhysics.FLUID_MEASURE.invokeExact(w(), x, feetY, z, height, halfX, flowBaseY,
                flowHeight, mask, sample);
            if (rc != 0) {
                throw new IllegalArgumentException("cp_fluid_measure: " + CendaPhysics.lastError());
            }
        } catch (Throwable t) {
            throw rethrow(t);
        }
        readSample(out);
        return out;
    }

    /**
     * One step of vertical fluid forces as a pure function (see {@code cp_buoyancy_vy}). Static: it
     * reads no world.
     */
    public static float buoyancyVy(float vy, float submersion, float dt, PhysicsIntegrator params) {
        if (!CendaPhysics.isAvailable()) {
            throw new IllegalStateException("Cenda physics unavailable: " + CendaPhysics.unavailableReason());
        }
        try (Arena call = Arena.ofConfined()) {
            MemorySegment p = call.allocate(INTEGRATOR_BYTES, 8);
            putIntegrator(p, params);
            return (float) CendaPhysics.BUOYANCY_VY.invokeExact(vy, submersion, dt, p);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // cp_body (80 bytes): double x, y, z; float vx, vy, vz, half_x, half_z, below, above, step,
    // partial step; uint32 mask, flags, contacts, reserved[2].
    private void writeBody(PhysicsBody b) {
        MemorySegment s = body;
        s.set(F64, 0, b.x);
        s.set(F64, 8, b.y);
        s.set(F64, 16, b.z);
        s.set(F32, 24, b.vx);
        s.set(F32, 28, b.vy);
        s.set(F32, 32, b.vz);
        s.set(F32, 36, b.halfX);
        s.set(F32, 40, b.halfZ);
        s.set(F32, 44, b.below);
        s.set(F32, 48, b.above);
        s.set(F32, 52, b.stepHeight);
        s.set(F32, 56, b.partialStepHeight);
        s.set(I32, 60, b.mask);
        s.set(I32, 64, b.flags);
        s.set(I32, 68, 0);
        s.set(I32, 72, 0);
        s.set(I32, 76, 0);
    }

    private void readBody(PhysicsBody b) {
        MemorySegment s = body;
        b.x = s.get(F64, 0);
        b.y = s.get(F64, 8);
        b.z = s.get(F64, 16);
        b.vx = s.get(F32, 24);
        b.vy = s.get(F32, 28);
        b.vz = s.get(F32, 32);
        b.flags = s.get(I32, 64);
        b.contacts = s.get(I32, 68);
    }

    private void writeIntegrator(PhysicsIntegrator p) {
        putIntegrator(integrator, p);
    }

    private static void putIntegrator(MemorySegment s, PhysicsIntegrator p) {
        s.set(F32, 0, p.gravity());
        s.set(F32, 4, p.groundFriction());
        s.set(F32, 8, p.floatSubmersion());
        s.set(F32, 12, p.driftSpeed());
        s.set(F32, 16, p.fluidDrag());
        s.set(I32, 20, p.fluidMask());
        s.set(I32, 24, 0);
        s.set(I32, 28, 0);
    }

    // cp_fluid_sample (32 bytes): double surface_y; float submersion, flow xyz; int32 samples, reserved.
    private void readSample(FluidSample out) {
        out.surfaceY = sample.get(F64, 0);
        out.submersion = sample.get(F32, 8);
        out.flowX = sample.get(F32, 12);
        out.flowY = sample.get(F32, 16);
        out.flowZ = sample.get(F32, 20);
        out.flowSamples = sample.get(I32, 24);
    }

    // ---- rays and sweeps -----------------------------------------------------------------------------

    /**
     * First collider in {@code mask} or fluid cell in {@code fluidMask} along the ray (frame origin)
     * within {@code maxDistance} (world units; the direction need not be normalised). Fills {@code out}
     * (absolute cell, frame hit point); returns whether something was hit.
     */
    public boolean raycast(double ox, double oy, double oz, double dx, double dy, double dz, double maxDistance,
                           int mask, int fluidMask, RayHit out) {
        put6(ox, oy, oz, dx, dy, dz);
        try {
            int r = (int) CendaPhysics.RAYCAST.invokeExact(w(), vec, vec.asSlice(24), maxDistance, mask, fluidMask, hit);
            readHit(out);
            return r == 1;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * Sweeps a box of half extents {@code (hx, hy, hz)} centred at {@code from} to {@code to}; the first
     * collider in {@code mask} it would touch. {@code out.distance} is the fraction travelled (0..1).
     */
    public boolean sweep(double fromX, double fromY, double fromZ, double toX, double toY, double toZ,
                         float hx, float hy, float hz, int mask, RayHit out) {
        put6(fromX, fromY, fromZ, toX, toY, toZ);
        try {
            int r = (int) CendaPhysics.SWEEP.invokeExact(w(), vec, vec.asSlice(24), hx, hy, hz, mask, hit);
            readHit(out);
            return r == 1;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private void put6(double a, double b, double c, double d, double e, double f) {
        vec.set(F64, 0, a);
        vec.set(F64, 8, b);
        vec.set(F64, 16, c);
        vec.set(F64, 24, d);
        vec.set(F64, 32, e);
        vec.set(F64, 40, f);
    }

    // cp_hit (80 bytes): int32 kind, face; int64 x, y, z; int32 id, reserved; double distance, px, py,
    // pz; uint64 user.
    private void readHit(RayHit out) {
        out.kind = hit.get(I32, 0);
        out.face = hit.get(I32, 4);
        out.x = hit.get(I64, 8);
        out.y = hit.get(I64, 16);
        out.z = hit.get(I64, 24);
        out.id = hit.get(I32, 32);
        out.distance = hit.get(F64, 40);
        out.px = hit.get(F64, 48);
        out.py = hit.get(F64, 56);
        out.pz = hit.get(F64, 64);
        out.user = hit.get(I64, 72);
    }

    /**
     * Ray {@code origin + t * direction} (t in units of the direction's length) against {@code count}
     * boxes (6 doubles each, in the same frame as the ray — the host's) grown by {@code margin}.
     * Returns the index of the box with the smallest entry t &lt;= {@code maxT}, or -1; {@code outT}
     * (may be null, else at least {@code count} long) receives each entry t (+inf when missed, 0 when
     * inside). Owner thread (uses this world's scratch), reads no cells.
     */
    public int rayBoxes(double ox, double oy, double oz, double dx, double dy, double dz, double maxT,
                        double[] boxesXyz, int count, double margin, double[] outT) {
        if (count <= 0) return -1;
        long need = AABB_BYTES * count;
        if (boxes.byteSize() < need) {
            boxes = arena.allocate(Math.max(need, boxes.byteSize() * 2), 8);
        }
        if (doubles.byteSize() < 8L * count) {
            doubles = arena.allocate(F64, Math.max(count, doubles.byteSize() / 4));
        }
        MemorySegment.copy(boxesXyz, 0, boxes, F64, 0, count * 6);
        put6(ox, oy, oz, dx, dy, dz);
        try {
            MemorySegment tOut = outT == null ? MemorySegment.NULL : doubles;
            int best = (int) CendaPhysics.RAY_BOXES.invokeExact(vec, vec.asSlice(24), maxT, boxes, count, margin, tOut);
            if (outT != null) {
                MemorySegment.copy(doubles, F64, 0, outT, 0, count);
            }
            return best;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }
}
