package com.openmason.engine.nav;

import com.openmason.engine.physics.PhysicsWorld;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.ref.Cleaner;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static com.openmason.engine.nav.CendaNav.F32;
import static com.openmason.engine.nav.CendaNav.F64;
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
 * <p><b>Coordinates (OpenMason #142, CN ABI 3).</b> Cells are ABSOLUTE {@code long}s ({@link #cell},
 * the Y bounds, {@link Reach} cells); Y is signed and unbounded ({@link #Y_UNBOUNDED_MIN},
 * {@link #Y_UNBOUNDED_MAX}). Positions in raw queries, the waypoints they return and reach surfaces
 * are {@code double}s relative to this world's frame origin ({@link #setOrigin}, default 0) — keep it
 * equal to the physics world's. Agents have their own frame ({@link GroundAgent#setOrigin}); probes
 * use the physics world's. Moving an origin never invalidates a queued search or a finished result:
 * routes are stored against absolute cells and read out in the frame current at the time. Searches do
 * their float math relative to an anchor near their start, so precision does not depend on the
 * distance from 0.
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

    /** No lower Y bound ({@link #create}, {@link AirProfile}, {@link NavFlood}). */
    public static final long Y_UNBOUNDED_MIN = Long.MIN_VALUE;
    /** No upper Y bound. */
    public static final long Y_UNBOUNDED_MAX = Long.MAX_VALUE;

    private static final Cleaner CLEANER = Cleaner.create();

    /** Service counters; {@code averageMicros} is the mean search time. */
    public record Stats(long submitted, long rejected, long completed, long failed, long partial, int inFlight,
                        long averageMicros) {
    }

    /** A ground search: from the walker's FEET to a goal (frame positions). */
    public record GroundQuery(double feetX, double feetY, double feetZ, double goalX, double goalY, double goalZ,
                              float goalRadius, int snapDown, int snapUp, GroundProfile profile, NavLimits limits) {
    }

    /** An air search: from the flyer's position to a goal (frame positions), nudged onto flyable air within goalSnapCells. */
    public record AirQuery(double fromX, double fromY, double fromZ, double goalX, double goalY, double goalZ,
                           float goalRadius, int goalSnapCells, AirProfile profile, NavLimits limits) {
    }

    /**
     * Cells a walker can reach ({@link #reachGround}), cheapest first: cell {@code i}'s ABSOLUTE feet
     * cell, the index of the cell it was reached from ({@code -1} for the start), the frame height its
     * feet rest at and its route cost. Walk {@code parent} back from any cell for the way there.
     */
    public record Reach(int count, long[] cells, int[] parents, double[] surfaces, float[] costs) {
        public long x(int i) {
            return cells[i * 3];
        }

        public long y(int i) {
            return cells[i * 3 + 1];
        }

        public long z(int i) {
            return cells[i * 3 + 2];
        }

        public int parent(int i) {
            return parents[i];
        }

        public double surface(int i) {
            return surfaces[i];
        }

        public float cost(int i) {
            return costs[i];
        }
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
    private long originX, originY, originZ;

    private NavWorld(PhysicsWorld physics, MemorySegment world) {
        this.physics = physics;
        this.world = world;
        this.topOut = arena.allocate(F32, 1);
        this.statsOut = Arena.ofAuto().allocate(CendaNav.STATS_BYTES, 8);
        this.query = arena.allocate(CendaNav.GROUND_QUERY_BYTES, 8);
        this.info = arena.allocate(CendaNav.SEARCH_INFO_BYTES, 8);
        this.points = arena.allocate(F64, 3 * 64);
        this.cleanable = CLEANER.register(this, new Destroy(world));
    }

    /**
     * Navigation over {@code physics}.
     *
     * @param solidMask   shape categories that occupy space (a walker's collision categories)
     * @param hazardMask  shape categories that are impassable hazards (0 = none)
     * @param fluidMask   fluid categories that are liquid
     * @param minY        absolute; cells below are unknown ({@link #Y_UNBOUNDED_MIN}: none)
     * @param maxY        absolute; cells at or above are unknown ({@link #Y_UNBOUNDED_MAX}: none)
     * @param maxInFlight queued searches before refusing more (&lt;= 0 picks 64)
     * @throws IllegalStateException when Cenda navigation is unavailable (the message says why)
     */
    public static NavWorld create(PhysicsWorld physics, int solidMask, int hazardMask, int fluidMask, long minY,
                                  long maxY, int maxInFlight) {
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
            desc.set(I32, 20, maxInFlight);
            desc.set(I64, 24, minY);
            desc.set(I64, 32, maxY);
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

    // ---- the frame --------------------------------------------------------------------------------

    /**
     * Moves this world's frame origin (raw queries, their waypoints, reach surfaces). Queued searches
     * and finished results stay valid. Owner thread.
     */
    public void setOrigin(long ox, long oy, long oz) {
        try {
            int rc = (int) CendaNav.WORLD_SET_ORIGIN.invokeExact(w(), ox, oy, oz);
            if (rc != 0) {
                throw new IllegalStateException("cn_world_set_origin: " + CendaNav.lastError());
            }
        } catch (Throwable t) {
            throw rethrow(t);
        }
        originX = ox;
        originY = oy;
        originZ = oz;
    }

    public long originX() {
        return originX;
    }

    public long originY() {
        return originY;
    }

    public long originZ() {
        return originZ;
    }

    // ---- cells and the service -----------------------------------------------------------------

    /** The (absolute) cell's flags ({@code CELL_*}), as a search classifies it. */
    public int cell(long x, long y, long z) {
        try {
            return (int) CendaNav.CELL.invokeExact(w(), x, y, z, topOut);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** The cell's top as a search sees it (quantised to 1/1024): solid height or liquid surface, else 0. */
    public float cellTop(long x, long y, long z) {
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

    // ---- reach ------------------------------------------------------------------------------------

    /**
     * Every cell a walker standing at its feet can reach by the ground search's own moves (native
     * {@code cn_reach_ground}): cheapest first, within {@code radius} columns of the start, at most
     * {@code maxCells}, none costing more than {@code maxCost}. Synchronous, owner thread.
     */
    public Reach reachGround(double feetX, double feetY, double feetZ, int snapDown, int snapUp, int radius,
                             int maxCells, float maxCost, GroundProfile profile) {
        try (Arena a = Arena.ofConfined()) {
            // cn_reach_query (96 bytes): double feet[3]; int32 snap_down, snap_up, radius, max_cells;
            // float max_cost; int32 reserved; profile (48).
            MemorySegment q = a.allocate(CendaNav.REACH_QUERY_BYTES, 8);
            q.set(F64, 0, feetX);
            q.set(F64, 8, feetY);
            q.set(F64, 16, feetZ);
            q.set(I32, 24, snapDown);
            q.set(I32, 28, snapUp);
            q.set(I32, 32, radius);
            q.set(I32, 36, maxCells);
            q.set(F32, 40, maxCost);
            q.set(I32, 44, 0);
            CendaNav.writeGroundProfile(q, 48, profile);
            MemorySegment out = a.allocate(CendaNav.REACH_CELL_BYTES * Math.max(1, maxCells), 8);
            int n = (int) CendaNav.REACH_GROUND.invokeExact(w(), q, out, maxCells);
            if (n < 0) {
                throw new IllegalArgumentException("cn_reach_ground: " + CendaNav.lastError());
            }
            long[] cells = new long[n * 3];
            int[] parents = new int[n];
            double[] surfaces = new double[n];
            float[] costs = new float[n];
            // cn_reach_cell (40 bytes): int64 x, y, z; int32 parent; float cost; double surface.
            for (int i = 0; i < n; i++) {
                long o = i * CendaNav.REACH_CELL_BYTES;
                cells[i * 3] = out.get(I64, o);
                cells[i * 3 + 1] = out.get(I64, o + 8);
                cells[i * 3 + 2] = out.get(I64, o + 16);
                parents[i] = out.get(I32, o + 24);
                costs[i] = out.get(F32, o + 28);
                surfaces[i] = out.get(F64, o + 32);
            }
            return new Reach(n, cells, parents, surfaces, costs);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ---- raw searches ----------------------------------------------------------------------------

    /** Queues a ground search. Returns its id, or 0 when the queue is full. */
    public long searchGround(GroundQuery q) {
        // cn_ground_query (128 bytes): double feet[3], goal[3]; float goal_radius; int32 snap_down,
        // snap_up, reserved; profile (48); limits (16).
        MemorySegment s = query;
        s.set(F64, 0, q.feetX());
        s.set(F64, 8, q.feetY());
        s.set(F64, 16, q.feetZ());
        s.set(F64, 24, q.goalX());
        s.set(F64, 32, q.goalY());
        s.set(F64, 40, q.goalZ());
        s.set(F32, 48, q.goalRadius());
        s.set(I32, 52, q.snapDown());
        s.set(I32, 56, q.snapUp());
        s.set(I32, 60, 0);
        CendaNav.writeGroundProfile(s, 64, q.profile());
        CendaNav.writeLimits(s, 112, q.limits());
        try {
            return (long) CendaNav.SEARCH_GROUND.invokeExact(w(), s);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Queues an air search. Returns its id, or 0 when the queue is full. */
    public long searchAir(AirQuery q) {
        // cn_air_query (120 bytes): double from[3], goal[3]; float goal_radius; int32 goal_snap_cells;
        // profile (48); limits (16).
        MemorySegment s = query;
        s.set(F64, 0, q.fromX());
        s.set(F64, 8, q.fromY());
        s.set(F64, 16, q.fromZ());
        s.set(F64, 24, q.goalX());
        s.set(F64, 32, q.goalY());
        s.set(F64, 40, q.goalZ());
        s.set(F32, 48, q.goalRadius());
        s.set(I32, 52, q.goalSnapCells());
        CendaNav.writeAirProfile(s, 56, q.profile());
        CendaNav.writeLimits(s, 104, q.limits());
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
            if (points.byteSize() < (long) n * 24L) {
                points = arena.allocate(F64, (long) n * 3L);
            }
            int got = (int) CendaNav.SEARCH_POINTS.invokeExact(w(), id, points, n);
            double[] xyz = new double[got * 3];
            MemorySegment.copy(points, F64, 0, xyz, 0, xyz.length);
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
