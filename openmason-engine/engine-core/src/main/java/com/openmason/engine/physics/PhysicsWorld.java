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

import static com.openmason.engine.physics.CendaPhysics.BODY_BYTES;
import static com.openmason.engine.physics.CendaPhysics.BOX_BYTES;
import static com.openmason.engine.physics.CendaPhysics.DESC_BYTES;
import static com.openmason.engine.physics.CendaPhysics.F32;
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
                MethodType.methodType(int.class, MemorySegment.class, int.class, int.class, int.class, int.class,
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
    private final MemorySegment vec;      // float[6]: origin + direction / from + to / a box
    private final MemorySegment cells;    // grown on demand (nearestOpenCell)
    private MemorySegment boxes;          // grown on demand (rayBoxes / shapes)
    private MemorySegment floats;         // grown on demand (rayBoxes out)
    private final MemorySegment cellOut;  // uint16 shape + uint16 fluid
    private final MemorySegment statsOut;

    private PhysicsWorld(VoxelPhysicsSource source, int brickShift, int cacheBricks, int unloadedShape) {
        this.source = source;
        int edge = 1 << brickShift;
        this.fillShapes = new short[edge * edge * edge];
        this.fillFluids = new short[edge * edge * edge];
        this.body = arena.allocate(BODY_BYTES, 8);
        this.integrator = arena.allocate(INTEGRATOR_BYTES, 8);
        this.sample = arena.allocate(SAMPLE_BYTES, 8);
        this.hit = arena.allocate(HIT_BYTES, 8);
        this.vec = arena.allocate(F32, 6);
        this.cells = arena.allocate(I32, 3 * 64);
        this.boxes = arena.allocate(BOX_BYTES * SHAPE_MAX_BOXES, 8);
        this.floats = arena.allocate(F32, 64);
        this.cellOut = arena.allocate(I16, 2);
        this.statsOut = arena.allocate(STATS_BYTES, 8);

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
    private int fillUpcall(MemorySegment user, int x0, int y0, int z0, int sx, int sy, int sz,
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
            MemorySegment.copy(boxes, 0, this.boxes, F32, 0, count * 6);
        }
        try {
            int rc = (int) CendaPhysics.SHAPE_DEFINE.invokeExact(w(), id, this.boxes, count, category, flags);
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

    /** Any thread. The cell is refilled the next time a query touches it. */
    public void invalidateCell(int x, int y, int z) {
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

    /** Any thread. The half-open box {@code [x0,x1) x [y0,y1) x [z0,z1)} is refilled when next touched. */
    public void invalidateBox(int x0, int y0, int z0, int x1, int y1, int z1) {
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

    /** The cell's shape id (pulling it in if needed). */
    public int cellShape(int x, int y, int z) {
        readCell(x, y, z);
        return Short.toUnsignedInt(cellOut.get(I16, 0));
    }

    /** The cell's fluid id (pulling it in if needed). */
    public int cellFluid(int x, int y, int z) {
        readCell(x, y, z);
        return Short.toUnsignedInt(cellOut.get(I16, 2));
    }

    private void readCell(int x, int y, int z) {
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

    /** Adds a world-space box. Returns its id (&gt; 0). */
    public int addBox(float minX, float minY, float minZ, float maxX, float maxY, float maxZ,
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

    public void setBox(int id, float minX, float minY, float minZ, float maxX, float maxY, float maxZ,
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

    private static void putBox(MemorySegment s, float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        s.set(F32, 0, minX);
        s.set(F32, 4, minY);
        s.set(F32, 8, minZ);
        s.set(F32, 12, maxX);
        s.set(F32, 16, maxY);
        s.set(F32, 20, maxZ);
    }

    // ---- cell and point queries ----------------------------------------------------------------------

    /**
     * Height above the cell floor of the tallest box of the cell's shape (in {@code mask}) that
     * overlaps the world-space XZ footprint (inclusive edges, clipped to the cell); 0 when none does.
     */
    public float cellTop(int x, int y, int z, float minX, float minZ, float maxX, float maxZ, int mask) {
        try {
            return (float) CendaPhysics.CELL_TOP.invokeExact(w(), x, y, z, minX, minZ, maxX, maxZ, mask);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@link #cellTop} over the whole cell. */
    public float cellTop(int x, int y, int z, int mask) {
        return cellTop(x, y, z, x, z, x + 1.0f, z + 1.0f, mask);
    }

    /** Whether any collider in {@code mask} overlaps the box by more than the contact tolerance. */
    public boolean overlaps(float minX, float minY, float minZ, float maxX, float maxY, float maxZ, int mask) {
        putBox(vec, minX, minY, minZ, maxX, maxY, maxZ);
        try {
            return (int) CendaPhysics.OVERLAPS.invokeExact(w(), vec, mask) == 1;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * Of {@code count} candidate cells ({@code xyz} triplets in {@code cellsXyz}), the index of the one
     * holding nothing in {@code mask} whose centre is nearest {@code (px, py, pz)} (ties to the earlier
     * candidate). With {@code preferenceGiven} false, the first open candidate. -1 when none is open.
     */
    public int nearestOpenCell(int[] cellsXyz, int count, boolean preferenceGiven, float px, float py, float pz,
                               int mask) {
        if (count <= 0) return -1;
        if (count > 64) throw new IllegalArgumentException("at most 64 candidates");
        MemorySegment.copy(cellsXyz, 0, cells, I32, 0, count * 3);
        MemorySegment pref = MemorySegment.NULL;
        if (preferenceGiven) {
            vec.set(F32, 0, px);
            vec.set(F32, 4, py);
            vec.set(F32, 8, pz);
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

    /** Measures fluid around a body (see {@code cp_fluid_measure}) into {@code out}. */
    public FluidSample measureFluid(float x, float feetY, float z, float height, float halfX,
                                    float flowBaseY, float flowHeight, int mask, FluidSample out) {
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

    private void writeBody(PhysicsBody b) {
        MemorySegment s = body;
        s.set(F32, 0, b.x);
        s.set(F32, 4, b.y);
        s.set(F32, 8, b.z);
        s.set(F32, 12, b.vx);
        s.set(F32, 16, b.vy);
        s.set(F32, 20, b.vz);
        s.set(F32, 24, b.halfX);
        s.set(F32, 28, b.halfZ);
        s.set(F32, 32, b.below);
        s.set(F32, 36, b.above);
        s.set(F32, 40, b.stepHeight);
        s.set(F32, 44, b.partialStepHeight);
        s.set(I32, 48, b.mask);
        s.set(I32, 52, b.flags);
        s.set(I32, 56, 0);
        s.set(I32, 60, 0);
    }

    private void readBody(PhysicsBody b) {
        MemorySegment s = body;
        b.x = s.get(F32, 0);
        b.y = s.get(F32, 4);
        b.z = s.get(F32, 8);
        b.vx = s.get(F32, 12);
        b.vy = s.get(F32, 16);
        b.vz = s.get(F32, 20);
        b.flags = s.get(I32, 52);
        b.contacts = s.get(I32, 56);
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

    private void readSample(FluidSample out) {
        out.submersion = sample.get(F32, 0);
        out.surfaceY = sample.get(F32, 4);
        out.flowX = sample.get(F32, 8);
        out.flowY = sample.get(F32, 12);
        out.flowZ = sample.get(F32, 16);
        out.flowSamples = sample.get(I32, 20);
    }

    // ---- rays and sweeps -----------------------------------------------------------------------------

    /**
     * First collider in {@code mask} or fluid cell in {@code fluidMask} along the ray within
     * {@code maxDistance} (world units; the direction need not be normalised). Fills {@code out};
     * returns whether something was hit.
     */
    public boolean raycast(float ox, float oy, float oz, float dx, float dy, float dz, float maxDistance,
                           int mask, int fluidMask, RayHit out) {
        vec.set(F32, 0, ox);
        vec.set(F32, 4, oy);
        vec.set(F32, 8, oz);
        vec.set(F32, 12, dx);
        vec.set(F32, 16, dy);
        vec.set(F32, 20, dz);
        try {
            int r = (int) CendaPhysics.RAYCAST.invokeExact(w(), vec, vec.asSlice(12), maxDistance, mask, fluidMask, hit);
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
    public boolean sweep(float fromX, float fromY, float fromZ, float toX, float toY, float toZ,
                         float hx, float hy, float hz, int mask, RayHit out) {
        vec.set(F32, 0, fromX);
        vec.set(F32, 4, fromY);
        vec.set(F32, 8, fromZ);
        vec.set(F32, 12, toX);
        vec.set(F32, 16, toY);
        vec.set(F32, 20, toZ);
        try {
            int r = (int) CendaPhysics.SWEEP.invokeExact(w(), vec, vec.asSlice(12), hx, hy, hz, mask, hit);
            readHit(out);
            return r == 1;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private void readHit(RayHit out) {
        out.kind = hit.get(I32, 0);
        out.face = hit.get(I32, 4);
        out.x = hit.get(I32, 8);
        out.y = hit.get(I32, 12);
        out.z = hit.get(I32, 16);
        out.id = hit.get(I32, 20);
        out.distance = hit.get(F32, 24);
        out.px = hit.get(F32, 28);
        out.py = hit.get(F32, 32);
        out.pz = hit.get(F32, 36);
        out.user = hit.get(I64, 40);
    }

    /**
     * Ray {@code origin + t * direction} (t in units of the direction's length) against {@code count}
     * boxes (6 floats each, world space) grown by {@code margin}. Returns the index of the box with the
     * smallest entry t &lt;= {@code maxT}, or -1; {@code outT} (may be null, else at least {@code count}
     * long) receives each entry t (+inf when missed, 0 when inside). Owner thread (uses this world's
     * scratch), reads no cells.
     */
    public int rayBoxes(float ox, float oy, float oz, float dx, float dy, float dz, float maxT,
                        float[] boxesXyz, int count, float margin, float[] outT) {
        if (count <= 0) return -1;
        long need = BOX_BYTES * count;
        if (boxes.byteSize() < need) {
            boxes = arena.allocate(Math.max(need, boxes.byteSize() * 2), 8);
        }
        if (floats.byteSize() < 4L * count) {
            floats = arena.allocate(F32, Math.max(count, floats.byteSize() / 2));
        }
        MemorySegment.copy(boxesXyz, 0, boxes, F32, 0, count * 6);
        vec.set(F32, 0, ox);
        vec.set(F32, 4, oy);
        vec.set(F32, 8, oz);
        vec.set(F32, 12, dx);
        vec.set(F32, 16, dy);
        vec.set(F32, 20, dz);
        try {
            MemorySegment tOut = outT == null ? MemorySegment.NULL : floats;
            int best = (int) CendaPhysics.RAY_BOXES.invokeExact(vec, vec.asSlice(12), maxT, boxes, count, margin, tOut);
            if (outT != null) {
                MemorySegment.copy(floats, F32, 0, outT, 0, count);
            }
            return best;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }
}
