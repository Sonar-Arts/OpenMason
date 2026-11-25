package com.openmason.engine.rig;

import org.joml.Matrix4f;
import org.joml.Vector3fc;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.ref.Cleaner;
import java.util.ArrayList;
import java.util.List;

/**
 * A native rig ({@code cr_rig}): an immutable joint forest that {@link RigPoser} poses and
 * {@link Skin} weights refer to by joint index. Thread-safe once created (any number of threads may
 * pose it at once). Freed by {@link #close()} or, as a backstop, when unreachable.
 */
public final class Rig implements AutoCloseable {

    private static final Cleaner CLEANER = Cleaner.create();

    /** {@code cr_rig_flags}: a parent cycle was cut. */
    public static final int FLAG_CYCLE_BROKEN = 1;
    /** {@code cr_rig_flags}: a parent index was out of range. */
    public static final int FLAG_BAD_PARENT = 2;

    private final List<RigJoint> joints;
    private final State state;
    private final Cleaner.Cleanable cleanable;
    private final int headJoint;

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
                    CendaRig.RIG_DESTROY.invokeExact(h);
                } catch (Throwable ignored) {
                    // best effort at shutdown
                }
            }
        }
    }

    private Rig(List<RigJoint> joints, MemorySegment handle) {
        this.joints = List.copyOf(joints);
        this.state = new State(handle);
        this.cleanable = CLEANER.register(this, state);
        try {
            this.headJoint = (int) CendaRig.RIG_FIND_HEAD.invokeExact(handle);
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    /**
     * Builds a rig over {@code joints} (1..65535).
     *
     * @throws IllegalStateException when the native rig is unavailable
     * @throws IllegalArgumentException when the joints are rejected
     */
    public static Rig create(List<RigJoint> joints) {
        CendaRig.requireAvailable();
        if (joints == null || joints.isEmpty()) {
            throw new IllegalArgumentException("A rig needs at least one joint");
        }
        try (Arena arena = Arena.ofConfined()) {
            int n = joints.size();
            MemorySegment descs = arena.allocate(CendaRig.JOINT_BYTES * n, 8);
            List<String> ids = new ArrayList<>(n);
            List<String> names = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                RigJoint j = joints.get(i);
                long o = CendaRig.JOINT_BYTES * i;
                descs.set(CendaRig.I32, o, j.parent());
                descs.set(CendaRig.I32, o + 4, j.kind() == RigJoint.Kind.BONE ? 1 : 0);
                descs.set(CendaRig.I32, o + 8, (j.hasGeometry() ? 1 : 0) | (j.hidden() ? 2 : 0));  // CR_JOINT_*
                descs.set(CendaRig.I32, o + 12, 0);
                putVec(descs, o + 16, j.origin());
                putVec(descs, o + 28, j.pos());
                putVec(descs, o + 40, j.rotDeg());
                putVec(descs, o + 52, j.scale());
                putVec(descs, o + 64, j.endpoint());
                descs.set(CendaRig.F32, o + 76, 0f);
                ids.add(j.id());
                names.add(j.name());
            }
            MemorySegment handle = (MemorySegment) CendaRig.RIG_CREATE.invokeExact(descs, n,
                CendaRig.strings(arena, ids), CendaRig.strings(arena, names));
            if (handle.equals(MemorySegment.NULL)) {
                throw new IllegalArgumentException("cr_rig_create: " + CendaRig.lastError());
            }
            return new Rig(joints, handle);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    private static void putVec(MemorySegment s, long offset, Vector3fc v) {
        s.set(CendaRig.F32, offset, v.x());
        s.set(CendaRig.F32, offset + 4, v.y());
        s.set(CendaRig.F32, offset + 8, v.z());
    }

    MemorySegment handle() {
        MemorySegment h = state.handle;
        if (h == null) throw new IllegalStateException("Rig is closed");
        return h;
    }

    /**
     * The native {@code cr_rig*}, for other Cenda bindings that take a rig argument (for example
     * {@code com.openmason.engine.character}). Valid while this rig is open; the caller must keep the rig
     * reachable for the duration of its native call and must never free the handle.
     */
    public MemorySegment nativeHandle() {
        return handle();
    }

    /** The joints this rig was built from, in index order. */
    public List<RigJoint> joints() {
        return joints;
    }

    public int jointCount() {
        return joints.size();
    }

    /** {@code FLAG_*} bits describing repairs made at creation. */
    public int flags() {
        try {
            return (int) CendaRig.RIG_FLAGS.invokeExact(handle());
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    /** Joint index by id, else exact name, else case-insensitive name; -1 when none. */
    public int find(String idOrName) {
        if (idOrName == null || idOrName.isEmpty()) return -1;
        try (Arena arena = Arena.ofConfined()) {
            return (int) CendaRig.RIG_FIND.invokeExact(handle(), arena.allocateFrom(idOrName));
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    /** The head-tracking joint (see {@code cr_rig_find_head}); -1 when none. */
    public int headJoint() {
        return headJoint;
    }

    /**
     * Rest-pose matrices (no base): {@code world[j]} = the joint's head frame, {@code childFrame[j]} = the
     * frame its children inherit (a bone's tail). Either array may be null; each needs
     * {@link #jointCount()} entries.
     */
    public void restWorld(Matrix4f[] world, Matrix4f[] childFrame) {
        int n = jointCount();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment w = world != null ? arena.allocate(64L * n, 4) : MemorySegment.NULL;
            MemorySegment c = childFrame != null ? arena.allocate(64L * n, 4) : MemorySegment.NULL;
            int rc = (int) CendaRig.RIG_REST_WORLD.invokeExact(handle(), w, c);
            if (rc != CendaRig.OK) throw new IllegalStateException("cr_rig_rest_world: " + CendaRig.lastError());
            for (int i = 0; i < n; i++) {
                if (world != null) world[i] = JointPalette.read(w, i, world[i] != null ? world[i] : new Matrix4f());
                if (childFrame != null) {
                    childFrame[i] = JointPalette.read(c, i, childFrame[i] != null ? childFrame[i] : new Matrix4f());
                }
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    /** Frees the native rig. Safe to call twice; the rig is unusable afterwards. */
    @Override
    public void close() {
        cleanable.clean();
    }
}
