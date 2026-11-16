package com.openmason.engine.nav;

import com.openmason.engine.physics.PhysicsWorld;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.ref.Cleaner;

import static com.openmason.engine.nav.CendaNav.rethrow;

/**
 * How one body turns, hops and strokes (native {@code cn_steering}, OpenMason #116): the state its
 * {@link GroundAgent} steers with (jump cooldown, hop boost), also usable directly by host code that
 * moves the body itself — fleeing straight away, paddling at a bank. Like the agent it never moves the
 * body: calls fill an {@link AgentApply}. The rules live in C++; this class only marshals.
 *
 * <p>Owned by the thread simulating the body.
 */
public final class NavSteering implements AutoCloseable {

    private static final Cleaner CLEANER = Cleaner.create();

    private final MemorySegment steering;
    private final Cleaner.Cleanable cleanable;
    private final SteeringDesc desc;
    private final MemorySegment body;
    private final MemorySegment apply;
    private boolean closed;

    private NavSteering(SteeringDesc desc, MemorySegment steering) {
        this.desc = desc;
        this.steering = steering;
        Arena arena = Arena.ofAuto();
        this.body = arena.allocate(CendaNav.BODY_BYTES, 8);
        this.apply = arena.allocate(CendaNav.APPLY_BYTES, 8);
        this.cleanable = CLEANER.register(this, new Destroy(steering));
    }

    /** @throws IllegalStateException when Cenda navigation is unavailable (the message says why) */
    public static NavSteering create(SteeringDesc desc) {
        CendaNav.requireAvailable();
        try (Arena a = Arena.ofConfined()) {
            MemorySegment d = a.allocate(CendaNav.STEERING_DESC_BYTES, 8);
            desc.write(d);
            MemorySegment st = (MemorySegment) CendaNav.STEERING_CREATE.invokeExact(d);
            if (st.equals(MemorySegment.NULL)) {
                throw new IllegalArgumentException("cn_steering_create: " + CendaNav.lastError());
            }
            return new NavSteering(desc, st);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private record Destroy(MemorySegment steering) implements Runnable {
        @Override
        public void run() {
            try {
                CendaNav.STEERING_DESTROY.invokeExact(steering);
            } catch (Throwable ignored) {
                // the process is releasing it
            }
        }
    }

    public SteeringDesc desc() {
        return desc;
    }

    MemorySegment handle() {
        if (closed) {
            throw new IllegalStateException("NavSteering is closed");
        }
        return steering;
    }

    /** Advances the cooldowns; the hop boost ends when the body stands. Once per tick, before steering. */
    public void tick(boolean onGround, float deltaTime) {
        try {
            int rc = (int) CendaNav.STEERING_TICK.invokeExact(handle(), onGround ? 1 : 0, deltaTime);
            if (rc != 0) {
                throw new IllegalStateException("cn_steering_tick: " + CendaNav.lastError());
            }
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * One tick of steering along the normalised horizontal heading (dirX, dirZ): turn toward it, hop
     * an obstacle in the way when {@code allowJump} (from water: stroke over a shore), and set the
     * horizontal velocity.
     *
     * @param physics where the obstacle probe looks (null: no probe)
     */
    public void steerAlong(PhysicsWorld physics, AgentBody b, float dirX, float dirZ, float speedMultiplier,
                           float deltaTime, boolean allowJump, AgentApply out) {
        CendaNav.writeBody(body, b);
        MemorySegment phys = physics == null || physics.isClosed() ? MemorySegment.NULL : physics.nativeHandle();
        try {
            int rc = (int) CendaNav.STEERING_STEER.invokeExact(handle(), phys, body, dirX, dirZ, speedMultiplier,
                deltaTime, allowJump ? 1 : 0, apply);
            if (rc != 0) {
                throw new IllegalStateException("cn_steering_steer: " + CendaNav.lastError());
            }
        } catch (Throwable t) {
            throw rethrow(t);
        }
        out.read(apply);
    }

    /**
     * Gets the body upward: a swim stroke in water, a jump when standing, one cooldown for both.
     *
     * @return whether it pushed ({@code out} says which)
     */
    public boolean requestJump(AgentBody b, AgentApply out) {
        CendaNav.writeBody(body, b);
        try {
            int pushed = (int) CendaNav.STEERING_REQUEST_JUMP.invokeExact(handle(), body, apply);
            out.read(apply);
            return pushed == 1;
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
