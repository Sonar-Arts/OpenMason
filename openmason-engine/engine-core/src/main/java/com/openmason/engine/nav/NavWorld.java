package com.openmason.engine.nav;

import com.openmason.engine.physics.PhysicsWorld;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.ref.Cleaner;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static com.openmason.engine.nav.CendaNav.F32;
import static com.openmason.engine.nav.CendaNav.I32;
import static com.openmason.engine.nav.CendaNav.I64;
import static com.openmason.engine.nav.CendaNav.rethrow;

/**
 * Navigation over one physics world (native {@code cn_world}, OpenMason #116): the cells routes are
 * planned over are the physics cache's own shapes and fluids, classified by category masks, and the
 * search service runs the searches agents (and {@link #searchGround}/{@link #searchAir}) queue —
 * {@link #step} once per simulation tick, on the owner thread, with an expansion budget. The rules
 * live in C++ ({@code cenda/native/kernels/src/nav/}, contract in {@code nav.h}); this class only
 * marshals.
 *
 * <p><b>Threads.</b> Owned by the physics world's owner thread; {@link #stats()} may be read from
 * any thread. Close it before its physics world.
 */
public final class NavWorld implements AutoCloseable {

    /** Cell flags ({@link #cell}). Open is 0. */
    public static final int CELL_UNKNOWN = 1;
    public static final int CELL_SOLID = 2;
    public static final int CELL_LIQUID = 4;
    public static final int CELL_HAZARD = 8;

    /** Search outcomes. */
    public static final int SEARCH_FOUND = 0;
    public static final int SEARCH_PARTIAL_BUDGET = 1;
    public static final int SEARCH_PARTIAL_UNREACHABLE = 2;
    public static final int SEARCH_NO_PATH = 3;
    public static final int SEARCH_CANCELLED = 4;
    public static final int SEARCH_OUT_OF_RANGE = 5;

    private static final Cleaner CLEANER = Cleaner.create();

    /** Service counters; {@code averageMicros} is the mean search time. */
    public record Stats(long submitted, long rejected, long completed, long failed, long partial, int inFlight,
                        long averageMicros) {
    }

    /** A ground search: from the walker's FEET to a goal. */
    public record GroundQuery(float feetX, float feetY, float feetZ, float goalX, float goalY, float goalZ,
                              float goalRadius, int snapDown, int snapUp, GroundProfile profile, NavLimits limits) {
    }

    /** An air search: from the flyer's position to a goal, nudged onto flyable air within goalSnapCells. */
    public record AirQuery(float fromX, float fromY, float fromZ, float goalX, float goalY, float goalZ,
                           float goalRadius, int goalSnapCells, AirProfile profile, NavLimits limits) {
    }

    /** A finished search. */
    public record Outcome(int status, NavPath path, int expansions, float cost) {
        public boolean reachedGoal() {
            return status == SEARCH_FOUND;
        }
    }

    private final ReentrantReadWriteLock lifetime = new ReentrantReadWriteLock();
    private final PhysicsWorld physics; // kept reachable: the native world reads its cache
    private final Cleaner.Cleanable cleanable;
    private volatile MemorySegment world;

    // Owner-thread scratch.
    private final Arena arena = Arena.ofAuto();
    private final MemorySegment topOut;
    private final MemorySegment statsOut;
    private final MemorySegment query;
    private final MemorySegment info;
    private MemorySegment points;

    private NavWorld(PhysicsWorld physics, MemorySegment world) {
        this.physics = physics;
        this.world = world;
        this.topOut = arena.allocate(F32, 1);
        this.statsOut = Arena.ofAuto().allocate(CendaNav.STATS_BYTES, 8);
        this.query = arena.allocate(CendaNav.GROUND_QUERY_BYTES, 8);
        this.info = arena.allocate(CendaNav.SEARCH_INFO_BYTES, 8);
        this.points = arena.allocate(F32, 3 * 64);
        this.cleanable = CLEANER.register(this, new Destroy(world));
    }

    /**
     * Navigation over {@code physics}.
     *
     * @param solidMask   shape categories that occupy space (a walker's collision categories)
     * @param hazardMask  shape categories that are impassable hazards (0 = none)
     * @param fluidMask   fluid categories that are liquid
     * @param minY        cells below are unknown
     * @param maxY        cells at or above are unknown
     * @param maxInFlight queued searches before refusing more (&lt;= 0 picks 64)
     * @throws IllegalStateException when Cenda navigation is unavailable (the message says why)
     */
    public static NavWorld create(PhysicsWorld physics, int solidMask, int hazardMask, int fluidMask, int minY,
                                  int maxY, int maxInFlight) {
        CendaNav.requireAvailable();
        if (physics == null) {
            throw new IllegalArgumentException("physics");
        }
        try (Arena a = Arena.ofConfined()) {
            MemorySegment desc = a.allocate(CendaNav.WORLD_DESC_BYTES, 8);
            desc.set(CendaNav.ADDR, 0, physics.nativeHandle());
            desc.set(I32, 8, solidMask);
            desc.set(I32, 12, hazardMask);
            desc.set(I32, 16, fluidMask);
            desc.set(I32, 20, minY);
            desc.set(I32, 24, maxY);
            desc.set(I32, 28, maxInFlight);
            MemorySegment w = (MemorySegment) CendaNav.WORLD_CREATE.invokeExact(desc);
            if (w.equals(MemorySegment.NULL)) {
                throw new IllegalArgumentException("cn_world_create: " + CendaNav.lastError());
            }
            return new NavWorld(physics, w);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private record Destroy(MemorySegment world) implements Runnable {
        @Override
        public void run() {
            try {
                CendaNav.WORLD_DESTROY.invokeExact(world);
            } catch (Throwable ignored) {
                // the process is releasing the world
            }
        }
    }

    /** The physics world routes are planned over. */
    public PhysicsWorld physics() {
        return physics;
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

    private MemorySegment w() {
        MemorySegment current = world;
        if (current == null) {
            throw new IllegalStateException("NavWorld is closed");
        }
        return current;
    }

    /** The native world for an agent call, or NULL when closed (an agent then has no service). */
    static MemorySegment handleOrNull(NavWorld nav) {
        if (nav == null) {
            return MemorySegment.NULL;
        }
        MemorySegment current = nav.world;
        return current == null ? MemorySegment.NULL : current;
    }

    // ---- cells and the service -----------------------------------------------------------------

    /** The cell's flags ({@code CELL_*}), as a search classifies it. */
    public int cell(int x, int y, int z) {
        try {
            return (int) CendaNav.CELL.invokeExact(w(), x, y, z, topOut);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** The cell's top as a search sees it (quantised to 1/1024): solid height or liquid surface, else 0. */
    public float cellTop(int x, int y, int z) {
        cell(x, y, z);
        return topOut.get(F32, 0);
    }

    /**
     * Runs queued searches until {@code expansionBudget} expansions are spent this step (a started
     * search always finishes). Call once per simulation tick. Returns how many ran.
     */
    public int step(int expansionBudget) {
        try {
            return (int) CendaNav.WORLD_STEP.invokeExact(w(), expansionBudget);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Service counters. Any thread; zeros once closed. */
    public Stats stats() {
        lifetime.readLock().lock();
        try {
            MemorySegment current = world;
            if (current == null) {
                return new Stats(0, 0, 0, 0, 0, 0, 0);
            }
            synchronized (statsOut) {
                CendaNav.STATS_GET.invokeExact(current, statsOut);
                long completed = statsOut.get(I64, 16);
                long nanos = statsOut.get(I64, 40);
                return new Stats(statsOut.get(I64, 0), statsOut.get(I64, 8), completed, statsOut.get(I64, 24),
                    statsOut.get(I64, 32), statsOut.get(I32, 48), completed == 0 ? 0 : nanos / completed / 1000L);
            }
        } catch (Throwable t) {
            throw rethrow(t);
        } finally {
            lifetime.readLock().unlock();
        }
    }

    // ---- raw searches ----------------------------------------------------------------------------

    /** Queues a ground search. Returns its id, or 0 when the queue is full. */
    public long searchGround(GroundQuery q) {
        MemorySegment s = query;
        s.set(F32, 0, q.feetX());
        s.set(F32, 4, q.feetY());
        s.set(F32, 8, q.feetZ());
        s.set(F32, 12, q.goalX());
        s.set(F32, 16, q.goalY());
        s.set(F32, 20, q.goalZ());
        s.set(F32, 24, q.goalRadius());
        s.set(I32, 28, q.snapDown());
        s.set(I32, 32, q.snapUp());
        s.set(I32, 36, 0);
        CendaNav.writeGroundProfile(s, 40, q.profile());
        CendaNav.writeLimits(s, 88, q.limits());
        try {
            return (long) CendaNav.SEARCH_GROUND.invokeExact(w(), s);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Queues an air search. Returns its id, or 0 when the queue is full. */
    public long searchAir(AirQuery q) {
        MemorySegment s = query;
        s.set(F32, 0, q.fromX());
        s.set(F32, 4, q.fromY());
        s.set(F32, 8, q.fromZ());
        s.set(F32, 12, q.goalX());
        s.set(F32, 16, q.goalY());
        s.set(F32, 20, q.goalZ());
        s.set(F32, 24, q.goalRadius());
        s.set(I32, 28, q.goalSnapCells());
        CendaNav.writeAirProfile(s, 32, q.profile());
        CendaNav.writeLimits(s, 72, q.limits());
        try {
            return (long) CendaNav.SEARCH_AIR.invokeExact(w(), s);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** The finished search, or null while it is queued. */
    public Outcome poll(long id) {
        try {
            int rc = (int) CendaNav.SEARCH_POLL.invokeExact(w(), id, info);
            if (rc < 0) {
                throw new IllegalArgumentException("unknown search " + id);
            }
            if (rc == 0) {
                return null;
            }
            int status = info.get(I32, 0);
            int n = info.get(I32, 4);
            boolean complete = info.get(I32, 8) != 0;
            int expansions = info.get(I32, 12);
            float cost = info.get(F32, 16);
            if (points.byteSize() < (long) n * 12L) {
                points = arena.allocate(F32, (long) n * 3L);
            }
            int got = (int) CendaNav.SEARCH_POINTS.invokeExact(w(), id, points, n);
            float[] xyz = new float[got * 3];
            MemorySegment.copy(points, F32, 0, xyz, 0, xyz.length);
            return new Outcome(status, xyz.length == 0 ? NavPath.EMPTY : new NavPath(xyz, complete), expansions, cost);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Cancels a queued search or forgets a finished one. */
    public void release(long id) {
        try {
            CendaNav.SEARCH_RELEASE.invokeExact(w(), id);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }
}
