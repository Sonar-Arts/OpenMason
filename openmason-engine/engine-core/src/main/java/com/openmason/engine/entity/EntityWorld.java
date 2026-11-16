package com.openmason.engine.entity;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.ref.Cleaner;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static com.openmason.engine.entity.CendaEntities.BODY_BYTES;
import static com.openmason.engine.entity.CendaEntities.CLIP_BYTES;
import static com.openmason.engine.entity.CendaEntities.F32;
import static com.openmason.engine.entity.CendaEntities.FILTER_BYTES;
import static com.openmason.engine.entity.CendaEntities.I32;
import static com.openmason.engine.entity.CendaEntities.I64;
import static com.openmason.engine.entity.CendaEntities.TYPE_DESC_BYTES;
import static com.openmason.engine.entity.CendaEntities.rethrow;

/**
 * One native entity world ({@code ce_world}, OpenMason #114): handles, the deferred spawn/despawn
 * lifecycle, the live order, entity-type descriptors, bodies + spatial queries and clip clocks.
 * The behaviour lives in C++ ({@code cenda/native/kernels/src/entity/}); this class only marshals.
 *
 * <p><b>Handles</b> are 64-bit, never 0; a handle kept after its entity left stops matching anything
 * (its slot's generation moved on). {@link #slotOf(long)} gives a small dense index a host can key
 * its own per-entity objects by.
 *
 * <p><b>Threads.</b> {@link #spawn} and {@link #despawn} may be called from any thread. Every other
 * method belongs to the world's owner thread (the one that commits). The query/output buffers are
 * reused per world, so results are valid until the owner's next call.
 *
 * <p><b>Lifetime.</b> {@link #close} frees the native world; owner calls after it throw, and an
 * off-thread spawn racing it returns 0. A world that is never closed is freed once unreachable.
 */
public final class EntityWorld implements AutoCloseable {

    /** {@code ce_state} values. */
    public static final int STATE_NONE = 0;
    public static final int STATE_PENDING = 1;
    public static final int STATE_LIVE = 2;
    public static final int STATE_REMOVING = 3;
    public static final int STATE_RETIRED = 4;

    /** Type ids are 0 .. MAX_TYPES-1. */
    public static final int MAX_TYPES = 4096;

    private static final int COUNT_LIVE = 0;
    private static final int COUNT_PENDING = 1;
    private static final int COUNT_REMOVING = 2;

    private static final int KIND_SPAWNS = 0;
    private static final int KIND_DESPAWNS = 1;
    private static final int KIND_BOX = 0;
    private static final int KIND_AABB = 1;

    private static final Cleaner CLEANER = Cleaner.create();

    private final ReentrantReadWriteLock lifetime = new ReentrantReadWriteLock();
    private final Cleaner.Cleanable cleanable;
    private volatile MemorySegment world;

    // Owner-thread scratch: grown on demand, freed with the world object.
    private final Arena arena = Arena.ofAuto();
    private MemorySegment handles;
    private MemorySegment bodies;
    private final MemorySegment filter;
    private final MemorySegment scratch; // ce_type_desc / ce_body / ce_clip_state / float[6] / out distance

    private EntityWorld(MemorySegment world) {
        this.world = world;
        this.handles = arena.allocate(I64, 256);
        this.bodies = arena.allocate(BODY_BYTES * 64, 8);
        this.filter = arena.allocate(FILTER_BYTES, 8);
        this.scratch = arena.allocate(64, 8);
        this.cleanable = CLEANER.register(this, new Destroy(world));
    }

    /**
     * Creates a native world. {@code cellSize} is the spatial grid's cell edge in world units
     * (&lt;= 0 picks 8).
     *
     * @throws IllegalStateException when the Cenda entity model is unavailable (the message says why)
     */
    public static EntityWorld create(float cellSize) {
        if (!CendaEntities.isAvailable()) {
            throw new IllegalStateException("Cenda entity model unavailable: " + CendaEntities.unavailableReason());
        }
        try {
            MemorySegment w = (MemorySegment) CendaEntities.WORLD_CREATE.invokeExact(cellSize);
            if (w.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("ce_world_create failed: " + CendaEntities.lastError());
            }
            return new EntityWorld(w);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** The dense slot index a handle names (0-based); stable while the entity is in the world. */
    public static int slotOf(long handle) {
        return (int) (handle & 0xFFFFFFFFL) - 1;
    }

    private record Destroy(MemorySegment world) implements Runnable {
        @Override
        public void run() {
            try {
                CendaEntities.WORLD_DESTROY.invokeExact(world);
            } catch (Throwable ignored) {
                // nothing to do: the process is releasing the world
            }
        }
    }

    private MemorySegment w() {
        MemorySegment current = world;
        if (current == null) {
            throw new IllegalStateException("EntityWorld is closed");
        }
        return current;
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

    // ---- types ---------------------------------------------------------------------------------

    public void defineType(int typeId, EntityTypeDesc desc) {
        try (Arena call = Arena.ofConfined()) {
            MemorySegment d = scratch;
            d.set(F32, 0, desc.width());
            d.set(F32, 4, desc.height());
            d.set(F32, 8, desc.length());
            d.set(F32, 12, desc.legHeight());
            d.set(F32, 16, desc.modelYawOffsetDeg());
            d.set(I32, 20, desc.flags());
            MemorySegment model = desc.modelId() == null ? MemorySegment.NULL : call.allocateFrom(desc.modelId());
            int rc = (int) CendaEntities.TYPE_DEFINE.invokeExact(w(), typeId, d, model);
            if (rc != 0) {
                throw new IllegalArgumentException("ce_type_define(" + typeId + "): " + CendaEntities.lastError());
            }
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** The type's descriptor, or null when it is not defined. */
    public EntityTypeDesc type(int typeId) {
        try {
            int ok = (int) CendaEntities.TYPE_GET.invokeExact(w(), typeId, scratch);
            if (ok == 0) {
                return null;
            }
            MemorySegment id = (MemorySegment) CendaEntities.TYPE_MODEL_ID.invokeExact(w(), typeId);
            String modelId = id.equals(MemorySegment.NULL) ? null : id.reinterpret(Integer.MAX_VALUE).getString(0);
            return new EntityTypeDesc(scratch.get(F32, 0), scratch.get(F32, 4), scratch.get(F32, 8),
                scratch.get(F32, 12), scratch.get(F32, 16), scratch.get(I32, 20), modelId);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ---- lifecycle -----------------------------------------------------------------------------

    /**
     * Queues an entity (any thread). Returns its handle (state PENDING until {@link #commitSpawns}),
     * or 0 when the type is undefined or the world is closed.
     */
    public long spawn(int typeId, int flags, float x, float y, float z, float yaw) {
        lifetime.readLock().lock();
        try {
            if (world == null) {
                return 0L;
            }
            return (long) CendaEntities.SPAWN.invokeExact(world, typeId, flags, x, y, z, yaw);
        } catch (Throwable t) {
            throw rethrow(t);
        } finally {
            lifetime.readLock().unlock();
        }
    }

    /**
     * Queues the entity's removal (any thread). True when it was pending (now cancelled) or live
     * (now removing); false when already removing/retired, stale, or the world is closed.
     */
    public boolean despawn(long handle) {
        lifetime.readLock().lock();
        try {
            if (world == null) {
                return false;
            }
            return (int) CendaEntities.DESPAWN.invokeExact(world, handle) == 1;
        } catch (Throwable t) {
            throw rethrow(t);
        } finally {
            lifetime.readLock().unlock();
        }
    }

    /** Brings every queued entity into the world, in queue order, into {@code out} (cleared first). */
    public void commitSpawns(HandleList out) {
        out.clear();
        drain(KIND_SPAWNS, out);
    }

    /** Takes every removing entity out of the world, in despawn order, into {@code out} (cleared first). */
    public void commitDespawns(HandleList out) {
        out.clear();
        drain(KIND_DESPAWNS, out);
    }

    private void drain(int kind, HandleList out) {
        int cap = (int) (handles.byteSize() / 8);
        try {
            while (true) {
                int n = kind == KIND_SPAWNS
                    ? (int) CendaEntities.COMMIT_SPAWNS.invokeExact(w(), handles, cap)
                    : (int) CendaEntities.COMMIT_DESPAWNS.invokeExact(w(), handles, cap);
                if (n < 0) {
                    throw new IllegalStateException(CendaEntities.lastError());
                }
                out.append(handles, n);
                if (n < cap) {
                    return;
                }
            }
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public int state(long handle) {
        try {
            return (int) CendaEntities.STATE.invokeExact(w(), handle);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** The entity's type id, or -1 when the handle is stale. */
    public int typeOf(long handle) {
        try {
            return (int) CendaEntities.TYPE_OF.invokeExact(w(), handle);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Entities in the world (live + removing). */
    public int liveCount() {
        return count(COUNT_LIVE);
    }

    public int pendingCount() {
        return count(COUNT_PENDING);
    }

    public int removingCount() {
        return count(COUNT_REMOVING);
    }

    private int count(int which) {
        try {
            return (int) CendaEntities.COUNT.invokeExact(w(), which);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** The entities in the world in live order, into {@code out} (cleared first). */
    public void live(HandleList out) {
        try {
            int total = (int) CendaEntities.LIVE.invokeExact(w(), handles, (int) (handles.byteSize() / 8));
            if (total > handles.byteSize() / 8) {
                growHandles(total);
                total = (int) CendaEntities.LIVE.invokeExact(w(), handles, (int) (handles.byteSize() / 8));
            }
            out.clear();
            out.append(handles, total);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * The queued (pending, not cancelled) entities in queue order, into a new array. Any thread; it
     * allocates its own buffer.
     */
    public long[] pending() {
        lifetime.readLock().lock();
        try (Arena call = Arena.ofConfined()) {
            if (world == null) {
                return new long[0];
            }
            int cap = 64;
            while (true) {
                MemorySegment buf = call.allocate(I64, cap);
                int total = (int) CendaEntities.PENDING.invokeExact(world, buf, cap);
                if (total <= cap) {
                    return buf.asSlice(0, total * 8L).toArray(I64);
                }
                cap = total + 16; // more were queued meanwhile: try again with room to spare
            }
        } catch (Throwable t) {
            throw rethrow(t);
        } finally {
            lifetime.readLock().unlock();
        }
    }

    // ---- bodies --------------------------------------------------------------------------------

    /**
     * Writes body {@code index} of the next {@link #syncBodies} batch. The body box is
     * [x - w/2, x + w/2] x [y - legHeight, y + height] x [z - l/2, z + l/2].
     */
    public void body(int index, long handle, float x, float y, float z, float yaw,
                     float width, float height, float length, float legHeight, int flags) {
        long base = index * BODY_BYTES;
        if (base + BODY_BYTES > bodies.byteSize()) {
            MemorySegment grown = arena.allocate(Math.max(bodies.byteSize() * 2, base + BODY_BYTES), 8);
            grown.copyFrom(bodies);
            bodies = grown;
        }
        MemorySegment b = bodies;
        b.set(I64, base, handle);
        b.set(F32, base + 8, x);
        b.set(F32, base + 12, y);
        b.set(F32, base + 16, z);
        b.set(F32, base + 20, yaw);
        b.set(F32, base + 24, width);
        b.set(F32, base + 28, height);
        b.set(F32, base + 32, length);
        b.set(F32, base + 36, legHeight);
        b.set(I32, base + 40, flags);
        b.set(I32, base + 44, 0);
    }

    /** Pushes bodies 0..count-1 written with {@link #body}; returns how many named an entity. */
    public int syncBodies(int count) {
        try {
            return (int) CendaEntities.SYNC_BODIES.invokeExact(w(), bodies, count);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** The entity's x, y, z, yaw, width, height, length, legHeight into {@code out8}; false when absent. */
    public boolean bodyOf(long handle, float[] out8) {
        try {
            MemorySegment b = scratch;
            if ((int) CendaEntities.BODY_GET.invokeExact(w(), handle, b) == 0) {
                return false;
            }
            for (int i = 0; i < 8; i++) {
                out8[i] = b.get(F32, 8 + 4L * i);
            }
            return true;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** The entity's body flags, or 0 when absent. */
    public int bodyFlags(long handle) {
        try {
            MemorySegment b = scratch;
            return (int) CendaEntities.BODY_GET.invokeExact(w(), handle, b) == 0 ? 0 : b.get(I32, 40);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ---- queries -------------------------------------------------------------------------------

    private MemorySegment filter(EntityFilter f) {
        if (f == null) {
            return MemorySegment.NULL;
        }
        filter.set(I32, 0, f.typeId());
        filter.set(I32, 4, f.requireFlags());
        filter.set(I32, 8, f.excludeFlags());
        filter.set(I32, 12, 0);
        filter.set(I64, 16, f.exclude());
        return filter;
    }

    /** Entities whose position is within {@code r} of (x, y, z), in live order, into {@code out}. */
    public void queryRadius(float x, float y, float z, float r, EntityFilter f, HandleList out) {
        try {
            MemorySegment fs = filter(f);
            int cap = (int) (handles.byteSize() / 8);
            int total = (int) CendaEntities.QUERY_RADIUS.invokeExact(w(), x, y, z, r, fs, handles, cap);
            if (total > cap) {
                growHandles(total);
                cap = (int) (handles.byteSize() / 8);
                total = (int) CendaEntities.QUERY_RADIUS.invokeExact(w(), x, y, z, r, fs, handles, cap);
            }
            out.clear();
            out.append(handles, total);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * The entity nearest (x, y, z) within {@code maxR} ({@code Float.POSITIVE_INFINITY} = any), or 0.
     * Ties go to the entity that entered the world first.
     */
    public long queryNearest(float x, float y, float z, float maxR, EntityFilter f) {
        try {
            return (long) CendaEntities.QUERY_NEAREST.invokeExact(w(), x, y, z, maxR, filter(f), MemorySegment.NULL);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * Entities whose position satisfies min &lt;= p &lt; max on every axis; an axis from -inf to +inf is
     * not tested. Into {@code out}, live order.
     */
    public void queryBox(float minX, float minY, float minZ, float maxX, float maxY, float maxZ,
                         EntityFilter f, HandleList out) {
        boxQuery(KIND_BOX, minX, minY, minZ, maxX, maxY, maxZ, f, out);
    }

    /** Entities whose body box overlaps the box (strict), into {@code out}, live order. */
    public void queryAabb(float minX, float minY, float minZ, float maxX, float maxY, float maxZ,
                          EntityFilter f, HandleList out) {
        boxQuery(KIND_AABB, minX, minY, minZ, maxX, maxY, maxZ, f, out);
    }

    private void boxQuery(int kind, float minX, float minY, float minZ, float maxX, float maxY, float maxZ,
                          EntityFilter f, HandleList out) {
        try {
            MemorySegment box = scratch;
            box.set(F32, 0, minX);
            box.set(F32, 4, minY);
            box.set(F32, 8, minZ);
            box.set(F32, 12, maxX);
            box.set(F32, 16, maxY);
            box.set(F32, 20, maxZ);
            MemorySegment fs = filter(f);
            int cap = (int) (handles.byteSize() / 8);
            int total = invokeBox(kind, box, fs, cap);
            if (total > cap) {
                growHandles(total);
                cap = (int) (handles.byteSize() / 8);
                total = invokeBox(kind, box, fs, cap);
            }
            out.clear();
            out.append(handles, total);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private int invokeBox(int kind, MemorySegment box, MemorySegment fs, int cap) throws Throwable {
        return kind == KIND_BOX
            ? (int) CendaEntities.QUERY_BOX.invokeExact(w(), box, fs, handles, cap)
            : (int) CendaEntities.QUERY_AABB.invokeExact(w(), box, fs, handles, cap);
    }

    /** Every entity in the world matching the filter, live order, into {@code out}. */
    public void queryAll(EntityFilter f, HandleList out) {
        try {
            MemorySegment fs = filter(f);
            int cap = (int) (handles.byteSize() / 8);
            int total = (int) CendaEntities.QUERY_ALL.invokeExact(w(), fs, handles, cap);
            if (total > cap) {
                growHandles(total);
                cap = (int) (handles.byteSize() / 8);
                total = (int) CendaEntities.QUERY_ALL.invokeExact(w(), fs, handles, cap);
            }
            out.clear();
            out.append(handles, total);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private void growHandles(int atLeast) {
        long n = Math.max(handles.byteSize() / 8 * 2, atLeast);
        handles = arena.allocate(I64, n);
    }

    // ---- clip clocks ---------------------------------------------------------------------------

    /** Plays {@code clip} (restarting the state clock when it differs); false when the handle is stale. */
    public boolean clipSet(long handle, int clip, int flags) {
        try {
            return (int) CendaEntities.CLIP_SET.invokeExact(w(), handle, clip, flags) == 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Advances the continuous clock by {@code dtTotal} and the state clock by {@code dtState}. */
    public boolean clipAdvance(long handle, float dtTotal, float dtState) {
        try {
            return (int) CendaEntities.CLIP_ADVANCE.invokeExact(w(), handle, dtTotal, dtState) == 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** The time to sample the entity's clip at, or NaN when the handle is stale. */
    public float clipTime(long handle) {
        try {
            return (float) CendaEntities.CLIP_TIME.invokeExact(w(), handle);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** The entity's clip clock, or null when the handle is stale. */
    public ClipState clip(long handle) {
        try {
            MemorySegment c = scratch;
            if ((int) CendaEntities.CLIP_GET.invokeExact(w(), handle, c) != 0) {
                return null;
            }
            return new ClipState(c.get(I32, 0), c.get(I32, 4), c.get(F32, 8), c.get(F32, 12));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Overwrites the entity's clip clock; false when the handle is stale. */
    public boolean clipPut(long handle, ClipState state) {
        try {
            MemorySegment c = scratch;
            c.set(I32, 0, state.clip());
            c.set(I32, 4, state.flags());
            c.set(F32, 8, state.stateTime());
            c.set(F32, 12, state.totalTime());
            return (int) CendaEntities.CLIP_PUT.invokeExact(w(), handle, c) == 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static {
        // Layout guard: the scratch segment must hold the largest struct it is used for.
        assert TYPE_DESC_BYTES <= 64 && BODY_BYTES <= 64 && CLIP_BYTES <= 64;
    }
}
