package com.openmason.engine.rig;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.ref.Cleaner;

/**
 * Head-bob stabilizer for a first-person camera on a posed point ({@code cr_stabilizer}, OpenMason #161). Separates
 * bob from intended motion by amplitude, in the character's model space (so walking and turning never lag): a
 * deviation up to {@code threshold} passes through exactly, larger bob is removed by a slow critically damped
 * filter, and anything beyond {@code maxOffset} (crouch, mantle, stagger) drags the filter along. A value
 * {@code <= 0} takes the native default (threshold 0.02, maxOffset 0.1, time constant 0.3 s, snap distance 1).
 * Thread-confined; {@link #update} allocates nothing.
 */
public final class HeadBobStabilizer implements AutoCloseable {

    private static final Cleaner CLEANER = Cleaner.create();

    private final State state;
    private final Cleaner.Cleanable cleanable;
    private final MemorySegment point = Arena.ofAuto().allocate(12, 4);
    private final MemorySegment base = Arena.ofAuto().allocate(64, 16);

    private static final class State implements Runnable {
        volatile MemorySegment handle;

        State(MemorySegment handle) {
            this.handle = handle;
        }

        @Override
        public void run() {
            MemorySegment h = handle;
            handle = null;
            if (h != null && !h.equals(MemorySegment.NULL)) {
                try {
                    CendaRig.STABILIZER_DESTROY.invokeExact(h);
                } catch (Throwable ignored) {
                    // best effort
                }
            }
        }
    }

    public HeadBobStabilizer() {
        this(0f, 0f, 0f, 0f);
    }

    public HeadBobStabilizer(float threshold, float maxOffset, float timeConstant, float snapDistance) {
        CendaRig.requireAvailable();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment d = arena.allocate(CendaRig.STABILIZER_DESC_BYTES, 4);
            d.set(CendaRig.F32, 0, threshold);
            d.set(CendaRig.F32, 4, maxOffset);
            d.set(CendaRig.F32, 8, timeConstant);
            d.set(CendaRig.F32, 12, snapDistance);
            MemorySegment h = (MemorySegment) CendaRig.STABILIZER_CREATE.invokeExact(d);
            if (h.equals(MemorySegment.NULL)) throw new IllegalStateException("cr_stabilizer_create: " + CendaRig.lastError());
            this.state = new State(h);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
        this.cleanable = CLEANER.register(this, state);
    }

    private MemorySegment handle() {
        MemorySegment h = state.handle;
        if (h == null) throw new IllegalStateException("stabilizer closed");
        return h;
    }

    /** The next update starts at its raw point (after a teleport, respawn or a view switch). */
    public void reset() {
        try {
            int rc = (int) CendaRig.STABILIZER_RESET.invokeExact(handle());
            if (rc != CendaRig.OK) throw new IllegalStateException("cr_stabilizer_reset: " + CendaRig.lastError());
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    /**
     * One frame: the raw world point (e.g. {@code CharacterDefinition.eyesPoint}), the character's base matrix
     * (null = identity) and {@code dt} seconds; writes the stabilized world point into {@code out} (may be
     * {@code rawPoint}) and returns it.
     */
    public Vector3f update(Vector3f rawPoint, Matrix4f baseMatrix, float dt, Vector3f out) {
        point.set(CendaRig.F32, 0, rawPoint.x);
        point.set(CendaRig.F32, 4, rawPoint.y);
        point.set(CendaRig.F32, 8, rawPoint.z);
        MemorySegment b = MemorySegment.NULL;
        if (baseMatrix != null) {
            JointPalette.write(base, 0, baseMatrix);
            b = base;
        }
        int rc;
        try {
            rc = (int) CendaRig.STABILIZER_UPDATE.invokeExact(handle(), point, b, dt, point);
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
        if (rc != CendaRig.OK) throw new IllegalArgumentException("cr_stabilizer_update: " + CendaRig.lastError());
        return out.set(point.get(CendaRig.F32, 0), point.get(CendaRig.F32, 4), point.get(CendaRig.F32, 8));
    }

    @Override
    public void close() {
        cleanable.clean();
    }
}
