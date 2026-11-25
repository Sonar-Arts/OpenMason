package com.openmason.engine.character;

import com.openmason.engine.anim.AnimClipDocument;
import com.openmason.engine.rig.Rig;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.ref.Cleaner;
import java.util.ArrayList;
import java.util.List;

/**
 * Animation retargeting between two characterized skeletons (OpenMason #160; native {@code cc_retarget_*},
 * contract in {@code cenda/retarget.h}): chains paired by name, both characters taken from their reference poses,
 * an op stack (pelvis motion, FK chains, stride warp, root height, IK goals). Binding only.
 *
 * <p>Runtime: {@link #pose} (one local pose in, one out) or
 * {@link com.openmason.engine.animgraph.AnimGraphInstance#bindSlotRetargeted}; baked: {@link #bake}. Keep the
 * rigs and characters open while the retargeter is in use; one thread at a time.
 */
public final class Retargeter implements AutoCloseable {

    private static final Cleaner CLEANER = Cleaner.create();
    private static final long OP_BYTES = 32;
    private static final long CHAIN_BYTES = 24;

    public enum OpType { PELVIS, FK, STRIDE_WARP, ROOT_HEIGHT, IK }

    /** One op of the stack; {@code params} (up to 6) as in retarget.h. */
    public record Op(OpType type, float weight, float... params) {
        public Op {
            if (params.length > 6) throw new IllegalArgumentException("at most 6 parameters");
            params = params.clone();
        }
    }

    public enum FkMode { INTERPOLATED, ONE_TO_ONE, OFF }

    public enum FkSpace { GLOBAL, LOCAL }

    public record ChainSettings(float fkWeight, FkMode fkMode, FkSpace fkSpace, float ikWeight, boolean autoAlign) {
        public static final ChainSettings DEFAULT = new ChainSettings(1f, FkMode.INTERPOLATED, FkSpace.GLOBAL, 1f, true);
    }

    /** A chain both characters have: joints on each side, whether IK solves it. */
    public record Chain(String name, int sourceJoints, int targetJoints, boolean ik) {}

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
                    CendaCharacter.RT_DESTROY.invokeExact(h);
                } catch (Throwable ignored) {
                    // best effort
                }
            }
        }
    }

    private final Rig source;
    private final Rig target;
    private final State state;
    private final Cleaner.Cleanable cleanable;
    private final MemorySegment srcLocal;
    private final MemorySegment dstLocal;
    private final MemorySegment driven;

    private Retargeter(Rig source, Rig target, MemorySegment handle) {
        this.source = source;
        this.target = target;
        this.state = new State(handle);
        this.cleanable = CLEANER.register(this, state);
        Arena arena = Arena.ofAuto();
        this.srcLocal = arena.allocate(9L * 4 * source.jointCount(), 4);
        this.dstLocal = arena.allocate(9L * 4 * target.jointCount(), 4);
        this.driven = arena.allocate(Math.max(1, target.jointCount()));
    }

    /** Pairs the two characters' retarget chains (auto-map writes them from the profile). */
    public static Retargeter create(Rig sourceRig, CharacterDefinition source, Rig targetRig, CharacterDefinition target) {
        CendaCharacter.requireAvailable();
        try {
            MemorySegment h = (MemorySegment) CendaCharacter.RT_CREATE.invokeExact(sourceRig.nativeHandle(),
                source.nativeHandle(), targetRig.nativeHandle(), target.nativeHandle());
            if (h.equals(MemorySegment.NULL)) throw new IllegalArgumentException("cc_retarget_create: " + CendaCharacter.lastError());
            return new Retargeter(sourceRig, targetRig, h);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    /** The native handle (for other bindings: the animation graph). */
    public MemorySegment nativeHandle() {
        MemorySegment h = state.handle;
        if (h == null) throw new IllegalStateException("retargeter closed");
        return h;
    }

    public Rig sourceRig() {
        return source;
    }

    public Rig targetRig() {
        return target;
    }

    /** The automatic pelvis scale (hip-height ratio, target / source). */
    public float scale() {
        try {
            return (float) CendaCharacter.RT_SCALE.invokeExact(nativeHandle());
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    public List<Chain> chains() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment name = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment ints = arena.allocate(12, 4);
            int n = (int) CendaCharacter.RT_CHAIN_COUNT.invokeExact(nativeHandle());
            List<Chain> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                check((int) CendaCharacter.RT_CHAIN_GET.invokeExact(nativeHandle(), i, name, ints, ints.asSlice(4), ints.asSlice(8)),
                    "chain");
                out.add(new Chain(CendaCharacter.string(name.get(ValueLayout.ADDRESS, 0)), ints.get(ValueLayout.JAVA_INT, 0),
                    ints.get(ValueLayout.JAVA_INT, 4), ints.get(ValueLayout.JAVA_INT, 8) != 0));
            }
            return out;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    public ChainSettings chain(String name) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment s = arena.allocate(CHAIN_BYTES, 4);
            check((int) CendaCharacter.RT_GET_CHAIN.invokeExact(nativeHandle(), arena.allocateFrom(name), s), "chain");
            return new ChainSettings(s.get(ValueLayout.JAVA_FLOAT, 0), FkMode.values()[s.get(ValueLayout.JAVA_INT, 4)],
                FkSpace.values()[s.get(ValueLayout.JAVA_INT, 8)], s.get(ValueLayout.JAVA_FLOAT, 12), s.get(ValueLayout.JAVA_INT, 16) != 0);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    public void setChain(String name, ChainSettings settings) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment s = arena.allocate(CHAIN_BYTES, 4);
            s.set(ValueLayout.JAVA_FLOAT, 0, settings.fkWeight());
            s.set(ValueLayout.JAVA_INT, 4, settings.fkMode().ordinal());
            s.set(ValueLayout.JAVA_INT, 8, settings.fkSpace().ordinal());
            s.set(ValueLayout.JAVA_FLOAT, 12, settings.ikWeight());
            s.set(ValueLayout.JAVA_INT, 16, settings.autoAlign() ? 1 : 0);
            check((int) CendaCharacter.RT_SET_CHAIN.invokeExact(nativeHandle(), arena.allocateFrom(name), s), "setChain");
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    /**
     * The way a chain's middle joint bends when the source limb is straight (profile frame: +Y up, faces -Z), or
     * null for none (the FK bend). Defaults to the target's built-in profile (humanoid knees -Z, elbows +Z).
     */
    public void setBend(String chain, org.joml.Vector3fc direction) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment d = MemorySegment.NULL;
            if (direction != null) {
                d = arena.allocate(12, 4);
                d.set(ValueLayout.JAVA_FLOAT, 0, direction.x());
                d.set(ValueLayout.JAVA_FLOAT, 4, direction.y());
                d.set(ValueLayout.JAVA_FLOAT, 8, direction.z());
            }
            check((int) CendaCharacter.RT_SET_BEND.invokeExact(nativeHandle(), arena.allocateFrom(chain), d), "setBend");
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    public List<Op> ops() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment o = arena.allocate(OP_BYTES, 4);
            int n = (int) CendaCharacter.RT_OP_COUNT.invokeExact(nativeHandle());
            List<Op> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                check((int) CendaCharacter.RT_OP_GET.invokeExact(nativeHandle(), i, o), "op");
                float[] p = new float[6];
                for (int k = 0; k < 6; k++) p[k] = o.get(ValueLayout.JAVA_FLOAT, 8 + 4L * k);
                out.add(new Op(OpType.values()[o.get(ValueLayout.JAVA_INT, 0)], o.get(ValueLayout.JAVA_FLOAT, 4), p));
            }
            return out;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    /** Replaces the op stack (run in this order). */
    public void setOps(List<Op> ops) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment o = arena.allocate(OP_BYTES * Math.max(1, ops.size()), 4);
            for (int i = 0; i < ops.size(); i++) {
                Op op = ops.get(i);
                long base = OP_BYTES * i;
                o.set(ValueLayout.JAVA_INT, base, op.type().ordinal());
                o.set(ValueLayout.JAVA_FLOAT, base + 4, op.weight());
                for (int k = 0; k < op.params().length; k++) o.set(ValueLayout.JAVA_FLOAT, base + 8 + 4L * k, op.params()[k]);
            }
            check((int) CendaCharacter.RT_SET_OPS.invokeExact(nativeHandle(), o, ops.size()), "setOps");
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    /**
     * One pose: {@code sourceLocal} ({@code sourceRig().jointCount() * 9} floats, as {@code RigPoser.localPose}) in,
     * {@code targetLocal} out; {@code driven} (may be null) gets which target joints the retarget drives.
     */
    public void pose(float[] sourceLocal, float[] targetLocal, boolean[] drivenOut) {
        int ns = source.jointCount(), nd = target.jointCount();
        if (sourceLocal.length < ns * 9 || targetLocal.length < nd * 9) throw new IllegalArgumentException("pose arrays too short");
        MemorySegment.copy(sourceLocal, 0, srcLocal, ValueLayout.JAVA_FLOAT, 0, ns * 9);
        try {
            check((int) CendaCharacter.RT_POSE.invokeExact(nativeHandle(), srcLocal, dstLocal, driven), "pose");
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
        MemorySegment.copy(dstLocal, ValueLayout.JAVA_FLOAT, 0, targetLocal, 0, nd * 9);
        if (drivenOut != null) {
            for (int j = 0; j < Math.min(nd, drivenOut.length); j++) drivenOut[j] = driven.get(ValueLayout.JAVA_BYTE, j) != 0;
        }
    }

    /** A clip made for the source character baked for the target at {@code fps} (OMANIM 1.2). */
    public AnimClipDocument bake(AnimClipDocument sourceClip, float fps) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment rc = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment h = (MemorySegment) CendaCharacter.RT_BAKE.invokeExact(nativeHandle(), sourceClip.nativeHandle(), fps, rc);
            if (h.equals(MemorySegment.NULL)) throw new IllegalArgumentException("bake: " + CendaCharacter.lastError());
            return AnimClipDocument.adopt(h);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    private static void check(int rc, String what) {
        if (rc < 0) throw new IllegalArgumentException(what + ": " + CendaCharacter.lastError());
    }

    @Override
    public void close() {
        cleanable.clean();
    }
}
