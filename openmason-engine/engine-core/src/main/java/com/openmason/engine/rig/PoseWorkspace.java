package com.openmason.engine.rig;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.ref.Cleaner;

/**
 * Pose slots of one {@link Rig} ({@code cr_poses}, CR ABI 2, OpenMason #156): the local poses a
 * {@link PoseProgram} reads and writes. Allocated once; running a program never allocates. Keep the rig open
 * while the workspace is in use. Belongs to one thread at a time.
 */
public final class PoseWorkspace implements AutoCloseable {

    private static final Cleaner CLEANER = Cleaner.create();

    private final Rig rig;
    private final int slots;
    private final State state;
    private final Cleaner.Cleanable cleanable;
    private final MemorySegment localScratch;
    private final MemorySegment drivenScratch;

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
                    CendaRig.POSES_DESTROY.invokeExact(h);
                } catch (Throwable ignored) {
                    // best effort
                }
            }
        }
    }

    private PoseWorkspace(Rig rig, int slots, MemorySegment handle) {
        this.rig = rig;
        this.slots = slots;
        this.state = new State(handle);
        this.cleanable = CLEANER.register(this, state);
        int n = rig.jointCount();
        this.localScratch = Arena.ofAuto().allocate(36L * n, 4);
        this.drivenScratch = Arena.ofAuto().allocate(Math.max(1, n), 1);
    }

    /** {@code slots} (1..1024) rest poses of {@code rig}. */
    public static PoseWorkspace create(Rig rig, int slots) {
        CendaRig.requireAvailable();
        try {
            MemorySegment h = (MemorySegment) CendaRig.POSES_CREATE.invokeExact(rig.handle(), slots);
            if (h.equals(MemorySegment.NULL)) throw new IllegalArgumentException("cr_poses_create: " + CendaRig.lastError());
            return new PoseWorkspace(rig, slots, h);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    public Rig rig() {
        return rig;
    }

    public int slotCount() {
        return slots;
    }

    MemorySegment handle() {
        MemorySegment h = state.handle;
        if (h == null) throw new IllegalStateException("pose workspace closed");
        return h;
    }

    /**
     * A slot's local values ({@code jointCount * 9}: pos, rot degrees, scale) and driven flags
     * ({@code jointCount}, 0/1); either may be null.
     */
    public void get(int slot, float[] local, byte[] driven) {
        int n = rig.jointCount();
        try {
            int rc = (int) CendaRig.POSES_GET.invokeExact(handle(), slot, localScratch, drivenScratch);
            if (rc != CendaRig.OK) throw new IllegalArgumentException("cr_poses_get: " + CendaRig.lastError());
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
        if (local != null) MemorySegment.copy(localScratch, CendaRig.F32, 0, local, 0, n * 9);
        if (driven != null) MemorySegment.copy(drivenScratch, ValueLayout.JAVA_BYTE, 0, driven, 0, n);
    }

    /** Sets a slot (an editor's current transforms); {@code driven} null = every joint driven. */
    public void set(int slot, float[] local, byte[] driven) {
        int n = rig.jointCount();
        if (local.length < n * 9) throw new IllegalArgumentException("need 9 floats per joint");
        MemorySegment.copy(local, 0, localScratch, CendaRig.F32, 0, n * 9);
        MemorySegment drivenSeg = MemorySegment.NULL;
        if (driven != null) {
            MemorySegment.copy(driven, 0, drivenScratch, ValueLayout.JAVA_BYTE, 0, n);
            drivenSeg = drivenScratch;
        }
        try {
            int rc = (int) CendaRig.POSES_SET.invokeExact(handle(), slot, localScratch, drivenSeg);
            if (rc != CendaRig.OK) throw new IllegalArgumentException("cr_poses_set: " + CendaRig.lastError());
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    /**
     * A slot's morph weights (OpenMason #171; one per rig morph channel) into {@code out} (min(channels, length)
     * written); returns the rig's channel count. Allocation-free once the workspace has grown its scratch.
     */
    public int morphWeights(int slot, float[] out) {
        int n = rig.morphCount();
        if (n == 0 || out == null) return n;
        if (morphScratch == null || morphScratch.byteSize() < 4L * n) morphScratch = Arena.ofAuto().allocate(4L * n, 4);
        try {
            int rc = (int) CendaRig.POSES_MORPH_WEIGHTS.invokeExact(handle(), slot, morphScratch, MemorySegment.NULL, n);
            if (rc < 0) throw new IllegalArgumentException("cr_poses_morph_weights: " + CendaRig.lastError());
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
        MemorySegment.copy(morphScratch, CendaRig.F32, 0, out, 0, Math.min(n, out.length));
        return n;
    }

    private MemorySegment morphScratch;

    /** Runs {@code program} on these slots (no composition; see {@link RigPoser#evaluate}). */
    public void run(PoseProgram program) {
        try {
            int rc = (int) CendaRig.POSES_RUN.invokeExact(handle(), program.ops(), program.size());
            if (rc != CendaRig.OK) throw new IllegalArgumentException("cr_poses_run: " + CendaRig.lastError());
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
        java.lang.ref.Reference.reachabilityFence(program);
    }

    @Override
    public void close() {
        cleanable.clean();
    }
}
