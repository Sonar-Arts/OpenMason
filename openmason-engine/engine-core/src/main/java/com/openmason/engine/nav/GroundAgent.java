package com.openmason.engine.nav;

import com.openmason.engine.physics.PhysicsWorld;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.ref.Cleaner;

import static com.openmason.engine.nav.CendaNav.F64;
import static com.openmason.engine.nav.CendaNav.I32;
import static com.openmason.engine.nav.CendaNav.rethrow;

/**
 * One walker's navigation (native {@code cn_ground_agent}, OpenMason #116): tell it where to go and
 * each {@link #tick} it throttles searches, adopts results, follows the waypoints, steers, jumps
 * where the route planned a rise, notices being wedged and replans (steering through the body's
 * {@link NavSteering}). It never moves the body: every
 * call that decides something fills an {@link AgentApply} for the host to apply. The state machine
 * lives in C++ ({@code nav_agents.cpp}); this class only marshals.
 *
 * <p><b>Frame (OpenMason #142).</b> Every position the agent takes or gives (body, goal, route, view)
 * is a {@code double} relative to the agent's own frame origin — given at {@link #create}, moved by
 * {@link #setOrigin}; keep it equal to the physics world's. The agent talks to the world in absolute
 * cells, so its frame never has to match the nav world's.
 *
 * <p><b>Threads.</b> Calls belong to the thread simulating the body. {@link #view()} and
 * {@link #path()} may be read from any thread (a debug overlay): they are snapshots published after
 * each call.
 */
public final class GroundAgent implements AutoCloseable {

    private static final Cleaner CLEANER = Cleaner.create();

    private final MemorySegment agent;
    private final Cleaner.Cleanable cleanable;
    private final GroundAgentDesc desc;
    private final AgentView view = new AgentView();
    private volatile NavPath path = NavPath.EMPTY;
    private int fetchedVersion;
    private boolean closed;

    private final Arena arena = Arena.ofAuto();
    private final MemorySegment body;
    private final MemorySegment apply;
    private final MemorySegment viewOut;
    private final MemorySegment completeOut;
    private MemorySegment points;

    private GroundAgent(GroundAgentDesc desc, MemorySegment agent) {
        this.desc = desc;
        this.agent = agent;
        this.body = arena.allocate(CendaNav.BODY_BYTES, 8);
        this.apply = arena.allocate(CendaNav.APPLY_BYTES, 8);
        this.viewOut = arena.allocate(CendaNav.VIEW_BYTES, 8);
        this.completeOut = arena.allocate(I32, 1);
        this.points = arena.allocate(F64, 3 * 32);
        this.cleanable = CLEANER.register(this, new Destroy(agent));
    }

    /**
     * An agent in frame 0 for a body at (x, y, z) (seeds the stuck detector).
     *
     * @throws IllegalStateException when Cenda navigation is unavailable (the message says why)
     */
    public static GroundAgent create(GroundAgentDesc desc, double x, double y, double z) {
        return create(desc, 0L, 0L, 0L, x, y, z);
    }

    /**
     * An agent whose frame origin is (originX, originY, originZ) (absolute), for a body at the frame
     * position (x, y, z).
     *
     * @throws IllegalStateException when Cenda navigation is unavailable (the message says why)
     */
    public static GroundAgent create(GroundAgentDesc desc, long originX, long originY, long originZ, double x,
                                     double y, double z) {
        CendaNav.requireAvailable();
        try (Arena a = Arena.ofConfined()) {
            MemorySegment d = a.allocate(CendaNav.GROUND_AGENT_DESC_BYTES, 8);
            desc.write(d);
            MemorySegment o = CendaNav.origin(a, originX, originY, originZ);
            MemorySegment ag = (MemorySegment) CendaNav.GROUND_CREATE.invokeExact(d, o, x, y, z);
            if (ag.equals(MemorySegment.NULL)) {
                throw new IllegalArgumentException("cn_ground_agent_create: " + CendaNav.lastError());
            }
            return new GroundAgent(desc, ag);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private record Destroy(MemorySegment agent) implements Runnable {
        @Override
        public void run() {
            try {
                CendaNav.GROUND_DESTROY.invokeExact(agent);
            } catch (Throwable ignored) {
                // the process is releasing the agent
            }
        }
    }

    public GroundAgentDesc desc() {
        return desc;
    }

    /** The agent's state after its last call. */
    public AgentView view() {
        return view;
    }

    /** The route being followed; never null, possibly {@link NavPath#EMPTY}. */
    public NavPath path() {
        return path;
    }

    private MemorySegment a() {
        if (closed) {
            throw new IllegalStateException("GroundAgent is closed");
        }
        return agent;
    }

    /**
     * Moves the agent's frame origin: its goal, route and stuck anchor shift by old - new, and every
     * later position is in the new frame.
     */
    public void setOrigin(long ox, long oy, long oz) {
        try {
            int rc = (int) CendaNav.GROUND_SET_ORIGIN.invokeExact(a(), ox, oy, oz);
            check(rc, "cn_ground_agent_set_origin");
        } catch (Throwable t) {
            throw rethrow(t);
        }
        // The route's numbers changed (same version natively): fetch it again.
        path = fetchPath();
    }

    /**
     * Sets where to go (frame position); cheap to repeat every tick with a moving target.
     *
     * @param goalRadius how close counts as arrival
     * @param speed      multiplier on the body's speed while walking this route
     */
    public void moveTo(double x, double y, double z, float goalRadius, float speed) {
        try {
            int rc = (int) CendaNav.GROUND_MOVE_TO.invokeExact(a(), x, y, z, goalRadius, speed, viewOut);
            check(rc, "cn_ground_agent_move_to");
        } catch (Throwable t) {
            throw rethrow(t);
        }
        published();
    }

    /** Abandons the destination and the route; {@code out} gets a stop. {@code world} may be null. */
    public void stop(NavWorld world, AgentApply out) {
        try {
            int rc = (int) CendaNav.GROUND_STOP.invokeExact(a(), NavWorld.handleOrNull(world), apply, viewOut);
            check(rc, "cn_ground_agent_stop");
        } catch (Throwable t) {
            throw rethrow(t);
        }
        out.read(apply);
        published();
    }

    /** Drops the route but keeps the destination, searching again at once. */
    public void replan(NavWorld world) {
        try {
            int rc = (int) CendaNav.GROUND_REPLAN.invokeExact(a(), NavWorld.handleOrNull(world), viewOut);
            check(rc, "cn_ground_agent_replan");
        } catch (Throwable t) {
            throw rethrow(t);
        }
        published();
    }

    /**
     * One tick.
     *
     * @param steering the body's steering (its cooldowns tick first)
     * @param world    the search service (null when there is none: the agent backs off)
     * @param physics  where the obstacle probe looks (null: no probe)
     * @param out      what to apply to the body
     */
    public void tick(NavSteering steering, NavWorld world, PhysicsWorld physics, AgentBody b, float deltaTime,
                     AgentApply out) {
        MemorySegment s = body;
        CendaNav.writeBody(s, b);
        MemorySegment phys = physics == null || physics.isClosed() ? MemorySegment.NULL : physics.nativeHandle();
        try {
            int rc = (int) CendaNav.GROUND_TICK.invokeExact(a(), steering.handle(), NavWorld.handleOrNull(world), phys,
                s, deltaTime, apply, viewOut);
            check(rc, "cn_ground_agent_tick");
        } catch (Throwable t) {
            throw rethrow(t);
        }
        out.read(apply);
        published();
    }

    private static void check(int rc, String call) {
        if (rc != 0) {
            throw new IllegalStateException(call + ": " + CendaNav.lastError());
        }
    }

    /** Publishes the view, and the route when it changed. */
    private void published() {
        CendaNav.readView(viewOut, view);
        int version = view.pathVersion();
        if (version != fetchedVersion) {
            fetchedVersion = version;
            path = fetchPath();
        }
    }

    private NavPath fetchPath() {
        try {
            int n = (int) CendaNav.GROUND_PATH.invokeExact(agent, MemorySegment.NULL, 0, completeOut);
            if (n == 0) {
                return NavPath.EMPTY;
            }
            if (points.byteSize() < (long) n * 24L) {
                points = arena.allocate(F64, (long) n * 3L);
            }
            n = (int) CendaNav.GROUND_PATH.invokeExact(agent, points, n, completeOut);
            double[] xyz = new double[n * 3];
            MemorySegment.copy(points, F64, 0, xyz, 0, xyz.length);
            return new NavPath(xyz, completeOut.get(I32, 0) != 0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            cleanable.clean();
        }
    }
}
