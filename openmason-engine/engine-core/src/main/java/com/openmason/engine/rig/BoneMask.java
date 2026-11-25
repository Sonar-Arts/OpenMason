package com.openmason.engine.rig;

import com.openmason.engine.format.oma.ParsedAnimClip;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.ref.Cleaner;

/**
 * A weighted bone mask ({@code cr_mask}, CR ABI 2, OpenMason #156; Unity Avatar Mask, Unreal layered blend per
 * bone): a weight in [0,1] per joint of one {@link Rig}, read by {@link PoseProgram#layer}. Branch sets with a
 * blend depth ramp the weight down a chain. Keep the rig open while the mask is in use.
 */
public final class BoneMask implements AutoCloseable {

    private static final Cleaner CLEANER = Cleaner.create();

    /** {@link #set}: the joint's descendants take the weight too. */
    public static final int BRANCH = 1;

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
                    CendaRig.MASK_DESTROY.invokeExact(h);
                } catch (Throwable ignored) {
                    // best effort
                }
            }
        }
    }

    private BoneMask(Rig rig, MemorySegment handle) {
        this.rig = rig;
        this.state = new State(handle);
        this.cleanable = CLEANER.register(this, state);
    }

    /** A mask over {@code rig}'s joints, every weight {@code fill}. */
    public static BoneMask create(Rig rig, float fill) {
        CendaRig.requireAvailable();
        try {
            MemorySegment h = (MemorySegment) CendaRig.MASK_CREATE.invokeExact(rig.handle(), fill);
            if (h.equals(MemorySegment.NULL)) throw new IllegalArgumentException("cr_mask_create: " + CendaRig.lastError());
            return new BoneMask(rig, h);
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
        if (h == null) throw new IllegalStateException("bone mask closed");
        return h;
    }

    /** The native {@code cr_mask*} for other Cenda bindings (character role masks); never free it. */
    public MemorySegment nativeHandle() {
        return handle();
    }

    private static void check(int rc, String what) {
        if (rc != CendaRig.OK) throw new IllegalArgumentException(what + ": " + CendaRig.lastError());
    }

    public BoneMask fill(float weight) {
        try {
            check((int) CendaRig.MASK_FILL.invokeExact(handle(), weight), "cr_mask_fill");
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
        return this;
    }

    /**
     * Sets {@code joint} (and with {@link #BRANCH} its descendants) to {@code weight}; {@code blendDepth > 0}
     * ramps it: a joint d levels below gets {@code weight * min(1, (d+1)/blendDepth)}.
     */
    public BoneMask set(int joint, float weight, int flags, int blendDepth) {
        try {
            check((int) CendaRig.MASK_SET.invokeExact(handle(), joint, weight, flags, blendDepth), "cr_mask_set");
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
        return this;
    }

    /** {@link #set} on the joint {@code idOrName} names ({@link Rig#find}); unknown names throw. */
    public BoneMask set(String idOrName, float weight, int flags, int blendDepth) {
        int joint = rig.find(idOrName);
        if (joint < 0) throw new IllegalArgumentException("no joint '" + idOrName + "' in the rig");
        return set(joint, weight, flags, blendDepth);
    }

    /** Every joint's weight. */
    public float[] weights() {
        int n = rig.jointCount();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(4L * n, 4);
            check((int) CendaRig.MASK_GET.invokeExact(handle(), out), "cr_mask_get");
            float[] w = new float[n];
            MemorySegment.copy(out, CendaRig.F32, 0, w, 0, n);
            return w;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    public BoneMask setAll(float[] weights) {
        int n = rig.jointCount();
        if (weights.length < n) throw new IllegalArgumentException("need one weight per joint");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment in = arena.allocate(4L * n, 4);
            MemorySegment.copy(weights, 0, in, CendaRig.F32, 0, n);
            check((int) CendaRig.MASK_SET_ALL.invokeExact(handle(), in), "cr_mask_set_all");
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
        return this;
    }

    /** 1 on the joints {@code clip}'s layer mask covers (all for an empty mask), else 0. */
    public BoneMask fromClip(ParsedAnimClip clip) {
        RigClip c = RigClip.of(clip);
        try {
            check((int) CendaRig.MASK_FROM_CLIP.invokeExact(handle(), c.handle()), "cr_mask_from_clip");
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
        java.lang.ref.Reference.reachabilityFence(c);
        return this;
    }

    @Override
    public void close() {
        cleanable.clean();
    }
}
