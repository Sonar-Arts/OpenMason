package com.openmason.engine.rig;

import java.lang.foreign.MemorySegment;
import java.lang.ref.Cleaner;

/**
 * Inertialized transitions ({@code cr_inertia}, CR ABI 2, OpenMason #156; Unreal inertialization, Bollo GDC
 * 2018). Run {@link PoseProgram#inertialize} on the final pose every frame (it records the output); on a source
 * switch call {@link #request} first: the difference between the last output and the new source, and the last
 * output's velocity, decay to zero over the blend time with no hitch, while the old source is no longer evaluated.
 * Keep the rig open while it is in use. One thread at a time.
 */
public final class Inertializer implements AutoCloseable {

    private static final Cleaner CLEANER = Cleaner.create();

    private final Rig rig;
    private final State state;
    private final Cleaner.Cleanable cleanable;

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
                    CendaRig.INERTIA_DESTROY.invokeExact(h);
                } catch (Throwable ignored) {
                    // best effort
                }
            }
        }
    }

    private Inertializer(Rig rig, MemorySegment handle) {
        this.rig = rig;
        this.state = new State(handle);
        this.cleanable = CLEANER.register(this, state);
    }

    public static Inertializer create(Rig rig) {
        CendaRig.requireAvailable();
        try {
            MemorySegment h = (MemorySegment) CendaRig.INERTIA_CREATE.invokeExact(rig.handle());
            if (h.equals(MemorySegment.NULL)) throw new IllegalArgumentException("cr_inertia_create: " + CendaRig.lastError());
            return new Inertializer(rig, h);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    public Rig rig() {
        return rig;
    }

    MemorySegment handle() {
        MemorySegment h = state.handle;
        if (h == null) throw new IllegalStateException("inertializer closed");
        return h;
    }

    /** Starts a transition measured at the next {@link PoseProgram#inertialize}; {@code <= 0} = an instant cut. */
    public void request(float blendSeconds) {
        try {
            int rc = (int) CendaRig.INERTIA_REQUEST.invokeExact(handle(), blendSeconds);
            if (rc != CendaRig.OK) throw new IllegalArgumentException("cr_inertia_request: " + CendaRig.lastError());
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    /** Forgets the recorded frames and any running transition (a teleport, a respawn). */
    public void reset() {
        try {
            int rc = (int) CendaRig.INERTIA_RESET.invokeExact(handle());
            if (rc != CendaRig.OK) throw new IllegalArgumentException("cr_inertia_reset: " + CendaRig.lastError());
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    /** True while a transition is pending or running. */
    public boolean active() {
        try {
            return (int) CendaRig.INERTIA_ACTIVE.invokeExact(handle()) != 0;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    @Override
    public void close() {
        cleanable.clean();
    }
}
