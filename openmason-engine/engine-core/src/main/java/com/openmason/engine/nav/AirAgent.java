package com.openmason.engine.nav;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.ref.Cleaner;

import static com.openmason.engine.nav.CendaNav.F32;
import static com.openmason.engine.nav.CendaNav.I32;
import static com.openmason.engine.nav.CendaNav.rethrow;

/**
 * One flyer's navigation (native {@code cn_air_agent}, OpenMason #116): routes to a horizon along the
 * bearing to a far destination, re-planned as the flyer advances, and publishes the point to fly at
 * ({@link #steerTarget}). It does not steer. The state machine lives in C++; this class only marshals.
 *
 * <p><b>Threads.</b> As {@link GroundAgent}: calls on the simulating thread, {@link #view()} and
 * {@link #path()} readable anywhere.
 */
public final class AirAgent implements AutoCloseable {

    private static final Cleaner CLEANER = Cleaner.create();

    private final MemorySegment agent;
    private final Cleaner.Cleanable cleanable;
    private final AgentView view = new AgentView();
    private volatile NavPath path = NavPath.EMPTY;
    private int fetchedVersion;
    private boolean closed;

    private final Arena arena = Arena.ofAuto();
    private final MemorySegment viewOut;
    private final MemorySegment target;
    private final MemorySegment completeOut;
    private MemorySegment points;

    private AirAgent(MemorySegment agent) {
        this.agent = agent;
        this.viewOut = arena.allocate(CendaNav.VIEW_BYTES, 8);
        this.target = arena.allocate(F32, 3);
        this.completeOut = arena.allocate(I32, 1);
        this.points = arena.allocate(F32, 3 * 16);
        this.cleanable = CLEANER.register(this, new Destroy(agent));
    }

    /**
     * An agent for a flyer at (x, y, z); its cruise altitude starts at y.
     *
     * @throws IllegalStateException when Cenda navigation is unavailable (the message says why)
     */
    public static AirAgent create(AirAgentDesc desc, float x, float y, float z) {
        CendaNav.requireAvailable();
        try (Arena a = Arena.ofConfined()) {
            MemorySegment d = a.allocate(CendaNav.AIR_AGENT_DESC_BYTES, 8);
            desc.write(d);
            MemorySegment ag = (MemorySegment) CendaNav.AIR_CREATE.invokeExact(d, x, y, z);
            if (ag.equals(MemorySegment.NULL)) {
                throw new IllegalArgumentException("cn_air_agent_create: " + CendaNav.lastError());
            }
            return new AirAgent(ag);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private record Destroy(MemorySegment agent) implements Runnable {
        @Override
        public void run() {
            try {
                CendaNav.AIR_DESTROY.invokeExact(agent);
            } catch (Throwable ignored) {
                // the process is releasing the agent
            }
        }
    }

    public AgentView view() {
        return view;
    }

    /** The route being flown; never null, possibly {@link NavPath#EMPTY}. */
    public NavPath path() {
        return path;
    }

    private MemorySegment a() {
        if (closed) {
            throw new IllegalStateException("AirAgent is closed");
        }
        return agent;
    }

    /** The altitude routes prefer: they hold it and leave it only where terrain forces them to. */
    public void setCruiseAltitude(float altitude) {
        try {
            CendaNav.AIR_SET_CRUISE.invokeExact(a(), altitude);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Sets the far destination. Cheap to repeat every tick; re-planning is throttled. */
    public void moveTo(float x, float y, float z) {
        try {
            check((int) CendaNav.AIR_MOVE_TO.invokeExact(a(), x, y, z, viewOut), "cn_air_agent_move_to");
        } catch (Throwable t) {
            throw rethrow(t);
        }
        published();
    }

    /** Abandons the destination and the route. {@code world} may be null. */
    public void stop(NavWorld world) {
        try {
            check((int) CendaNav.AIR_STOP.invokeExact(a(), NavWorld.handleOrNull(world), viewOut), "cn_air_agent_stop");
        } catch (Throwable t) {
            throw rethrow(t);
        }
        published();
    }

    /** Drops the route but keeps the destination, searching again at once. */
    public void replan(NavWorld world) {
        try {
            check((int) CendaNav.AIR_REPLAN.invokeExact(a(), NavWorld.handleOrNull(world), viewOut),
                "cn_air_agent_replan");
        } catch (Throwable t) {
            throw rethrow(t);
        }
        published();
    }

    /** One tick at the flyer's position. {@code world} may be null (no service: the agent backs off). */
    public void tick(NavWorld world, float x, float y, float z, float deltaTime) {
        try {
            check((int) CendaNav.AIR_TICK.invokeExact(a(), NavWorld.handleOrNull(world), x, y, z, deltaTime, viewOut),
                "cn_air_agent_tick");
        } catch (Throwable t) {
            throw rethrow(t);
        }
        published();
    }

    /**
     * The point to fly at from (x, y, z), written to {@code out[0..2]}: the next waypoint, or the
     * horizon along the bearing while there is no route.
     */
    public float[] steerTarget(float x, float y, float z, float[] out) {
        try {
            check((int) CendaNav.AIR_STEER_TARGET.invokeExact(a(), x, y, z, target), "cn_air_agent_steer_target");
        } catch (Throwable t) {
            throw rethrow(t);
        }
        out[0] = target.get(F32, 0);
        out[1] = target.get(F32, 4);
        out[2] = target.get(F32, 8);
        return out;
    }

    private static void check(int rc, String call) {
        if (rc != 0) {
            throw new IllegalStateException(call + ": " + CendaNav.lastError());
        }
    }

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
            int n = (int) CendaNav.AIR_PATH.invokeExact(agent, MemorySegment.NULL, 0, completeOut);
            if (n == 0) {
                return NavPath.EMPTY;
            }
            if (points.byteSize() < (long) n * 12L) {
                points = arena.allocate(F32, (long) n * 3L);
            }
            n = (int) CendaNav.AIR_PATH.invokeExact(agent, points, n, completeOut);
            float[] xyz = new float[n * 3];
            MemorySegment.copy(points, F32, 0, xyz, 0, xyz.length);
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
