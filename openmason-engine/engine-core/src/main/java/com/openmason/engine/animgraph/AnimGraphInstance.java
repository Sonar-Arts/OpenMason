package com.openmason.engine.animgraph;

import com.openmason.engine.anim.AnimClipDocument;
import com.openmason.engine.character.CharacterDefinition;
import com.openmason.engine.rig.JointPalette;
import com.openmason.engine.rig.Rig;
import org.joml.Matrix4f;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.ref.Cleaner;
import java.util.ArrayList;
import java.util.List;

/**
 * One character's animation graph state ({@code cg_instance}; OpenMason #157): parameter values, the clips bound
 * to the graph's slots, montages, per-node state and pose buffers, all native. {@link #evaluate} is the one call per
 * frame (update, pose, compose); afterwards {@link #notifyCount()}, curves, {@link #rootMotion} and
 * {@link #debug} read the frame's outputs. Same parameters and time steps give bit-identical poses.
 *
 * <p>Keep the graph, the rig and the character open while the instance is in use. One thread at a time. Setting
 * parameters by index and evaluating allocate nothing; look indices up once ({@link AnimGraph#param}).
 */
public final class AnimGraphInstance implements AutoCloseable {

    private static final Cleaner CLEANER = Cleaner.create();

    /** {@link #setRootMotion}: the root moves in the pose (default). */
    public static final int ROOT_MOTION_KEEP = 0;
    /** {@link #setRootMotion}: the root is pinned in the pose; {@link #rootMotion} reports the motion. */
    public static final int ROOT_MOTION_EXTRACT = 1;

    /** A notify edge the last frame crossed: {@code source} = clip slot (-1 for a montage, then {@code montage}). */
    public record Notify(String name, String payloadJson, int source, int montage, int index, int phase, float time,
                         float weight) {}

    /** A montage slot's state; {@code montage} -1 = idle. */
    public record MontageStatus(int montage, int section, float time, float weight, boolean blendingOut) {}

    /** Per-node debug snapshot. */
    public record NodeDebug(float influence, float time, float progress, int state, boolean active, boolean bound) {}

    private final AnimGraph graph;
    private final Rig rig;
    private final CharacterDefinition character;
    private final State state;
    private final Cleaner.Cleanable cleanable;
    private final int unresolvedMaskEntries;
    private final MemorySegment scratch;
    private final MemorySegment base;
    private MemorySegment extras = MemorySegment.NULL;
    private int extraCapacity;
    private int extraCount;

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
                    CendaAnimGraph.INSTANCE_DESTROY.invokeExact(h);
                } catch (Throwable ignored) {
                    // best effort
                }
            }
        }
    }

    private AnimGraphInstance(AnimGraph graph, Rig rig, CharacterDefinition character, MemorySegment handle, int unresolved) {
        this.graph = graph;
        this.rig = rig;
        this.character = character;
        this.state = new State(handle);
        this.cleanable = CLEANER.register(this, state);
        this.unresolvedMaskEntries = unresolved;
        this.scratch = Arena.ofAuto().allocate(64, 8);
        this.base = Arena.ofAuto().allocate(64, 16);
    }

    /**
     * An instance of {@code graph} posing {@code rig}; {@code character} (may be null) binds role masks and role
     * tracks.
     */
    public static AnimGraphInstance create(AnimGraph graph, Rig rig, CharacterDefinition character) {
        CendaAnimGraph.requireAvailable();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment unresolved = arena.allocate(CendaAnimGraph.I32);
            MemorySegment c = character == null ? MemorySegment.NULL : character.nativeHandle();
            MemorySegment h = (MemorySegment) CendaAnimGraph.INSTANCE_CREATE.invokeExact(graph.handle(), rig.nativeHandle(), c,
                unresolved);
            if (h.equals(MemorySegment.NULL)) throw new IllegalArgumentException("cg_instance_create: " + CendaAnimGraph.lastError());
            return new AnimGraphInstance(graph, rig, character, h, unresolved.get(CendaAnimGraph.I32, 0));
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaAnimGraph.rethrow(t);
        }
    }

    public AnimGraph graph() {
        return graph;
    }

    public Rig rig() {
        return rig;
    }

    public CharacterDefinition character() {
        return character;
    }

    /** Mask entries whose role or joint this rig / character lacks (they weigh nothing). */
    public int unresolvedMaskEntries() {
        return unresolvedMaskEntries;
    }

    private MemorySegment handle() {
        MemorySegment h = state.handle;
        if (h == null) throw new IllegalStateException("animation graph instance closed");
        return h;
    }

    /** Back to the start: parameter defaults, entry states, no montages. Bindings stay. */
    public void reset() {
        try {
            CendaAnimGraph.check((int) CendaAnimGraph.INSTANCE_RESET.invokeExact(handle()), "reset");
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaAnimGraph.rethrow(t);
        }
    }

    /**
     * Binds a clip to a slot (copied; role tracks bound to the character); null unbinds. Returns the number of role
     * tracks the character does not map.
     */
    public int bindSlot(int slot, AnimClipDocument clip) {
        try {
            MemorySegment c = clip == null ? MemorySegment.NULL : clip.nativeHandle();
            CendaAnimGraph.check((int) CendaAnimGraph.BIND_SLOT.invokeExact(handle(), slot, c, scratch), "bindSlot");
            java.lang.ref.Reference.reachabilityFence(clip);
            return scratch.get(CendaAnimGraph.I32, 0);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaAnimGraph.rethrow(t);
        }
    }

    public int bindSlot(String slot, AnimClipDocument clip) {
        int index = graph.slot(slot);
        if (index < 0) throw new IllegalArgumentException("no slot " + slot);
        return bindSlot(index, clip);
    }

    /** Registers a montage clip (its sections are point notifies named {@code section:<name>}); returns its id. */
    public int addMontage(AnimClipDocument clip) {
        try {
            int id = (int) CendaAnimGraph.ADD_MONTAGE.invokeExact(handle(), clip.nativeHandle());
            CendaAnimGraph.check(id, "addMontage");
            java.lang.ref.Reference.reachabilityFence(clip);
            return id;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaAnimGraph.rethrow(t);
        }
    }

    public void setFloat(int param, float value) {
        try {
            CendaAnimGraph.check((int) CendaAnimGraph.SET_FLOAT.invokeExact(handle(), param, value), "setFloat");
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaAnimGraph.rethrow(t);
        }
    }

    public void setInt(int param, int value) {
        try {
            CendaAnimGraph.check((int) CendaAnimGraph.SET_INT.invokeExact(handle(), param, value), "setInt");
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaAnimGraph.rethrow(t);
        }
    }

    public void setBool(int param, boolean value) {
        try {
            CendaAnimGraph.check((int) CendaAnimGraph.SET_BOOL.invokeExact(handle(), param, value ? 1 : 0), "setBool");
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaAnimGraph.rethrow(t);
        }
    }

    /** Sets a trigger; it stays set until a transition consumes it. */
    public void fireTrigger(int param) {
        try {
            CendaAnimGraph.check((int) CendaAnimGraph.FIRE_TRIGGER.invokeExact(handle(), param), "fireTrigger");
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaAnimGraph.rethrow(t);
        }
    }

    public void resetTrigger(int param) {
        try {
            CendaAnimGraph.check((int) CendaAnimGraph.RESET_TRIGGER.invokeExact(handle(), param), "resetTrigger");
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaAnimGraph.rethrow(t);
        }
    }

    public void setVec2(int param, float x, float y) {
        try {
            CendaAnimGraph.check((int) CendaAnimGraph.SET_VEC2.invokeExact(handle(), param, x, y), "setVec2");
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaAnimGraph.rethrow(t);
        }
    }

    /** A parameter's value (x; a vec2's y in {@code out[1]}). */
    public float[] param(int param, float[] out) {
        try {
            CendaAnimGraph.check((int) CendaAnimGraph.GET_PARAM.invokeExact(handle(), param, scratch), "param");
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaAnimGraph.rethrow(t);
        }
        out[0] = scratch.get(CendaAnimGraph.F32, 0);
        out[1] = scratch.get(CendaAnimGraph.F32, 4);
        return out;
    }

    public void setRootMotion(int mode) {
        try {
            CendaAnimGraph.check((int) CendaAnimGraph.SET_ROOT_MOTION.invokeExact(handle(), mode), "setRootMotion");
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaAnimGraph.rethrow(t);
        }
    }

    /** Walks the shortest transition chain to {@code state}, one transition a frame; throws when none leads there. */
    public void travel(int machineNode, int stateIndex) {
        try {
            CendaAnimGraph.check((int) CendaAnimGraph.TRAVEL.invokeExact(handle(), machineNode, stateIndex), "travel");
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaAnimGraph.rethrow(t);
        }
    }

    /** The machine's current state index (the target of a running transition). */
    public int state(int machineNode) {
        try {
            CendaAnimGraph.check((int) CendaAnimGraph.STATE.invokeExact(handle(), machineNode, scratch,
                scratch.asSlice(4), scratch.asSlice(8)), "state");
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaAnimGraph.rethrow(t);
        }
        return scratch.get(CendaAnimGraph.I32, 0);
    }

    /** The running transition's progress (1 = settled). */
    public float stateBlend(int machineNode) {
        state(machineNode);
        return scratch.get(CendaAnimGraph.F32, 8);
    }

    /** Plays a montage into a montage slot (see anim_graph.h); {@code section} null = the start. */
    public void playMontage(int slot, int montage, float rate, float blendIn, float blendOut, String section) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment s = section == null ? MemorySegment.NULL : arena.allocateFrom(section);
            CendaAnimGraph.check((int) CendaAnimGraph.MONTAGE_PLAY.invokeExact(handle(), slot, montage, rate, blendIn,
                blendOut, s), "playMontage");
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaAnimGraph.rethrow(t);
        }
    }

    public void stopMontage(int slot, float blendOut) {
        try {
            CendaAnimGraph.check((int) CendaAnimGraph.MONTAGE_STOP.invokeExact(handle(), slot, blendOut), "stopMontage");
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaAnimGraph.rethrow(t);
        }
    }

    public void jumpMontage(int slot, String section) {
        try (Arena arena = Arena.ofConfined()) {
            CendaAnimGraph.check((int) CendaAnimGraph.MONTAGE_JUMP.invokeExact(handle(), slot, arena.allocateFrom(section)),
                "jumpMontage");
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaAnimGraph.rethrow(t);
        }
    }

    /** After {@code section} play {@code next} (null = play on; equal = loop). */
    public void setMontageNext(int slot, String section, String next) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment n = next == null ? MemorySegment.NULL : arena.allocateFrom(next);
            CendaAnimGraph.check((int) CendaAnimGraph.MONTAGE_SET_NEXT.invokeExact(handle(), slot,
                arena.allocateFrom(section), n), "setMontageNext");
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaAnimGraph.rethrow(t);
        }
    }

    public MontageStatus montageStatus(int slot) {
        try {
            CendaAnimGraph.check((int) CendaAnimGraph.MONTAGE_STATUS.invokeExact(handle(), slot, scratch), "montageStatus");
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaAnimGraph.rethrow(t);
        }
        return new MontageStatus(scratch.get(CendaAnimGraph.I32, 0), scratch.get(CendaAnimGraph.I32, 4),
            scratch.get(CendaAnimGraph.F32, 8), scratch.get(CendaAnimGraph.F32, 12), scratch.get(CendaAnimGraph.I32, 16) != 0);
    }

    /** Forget the extras added since the last frame. */
    public AnimGraphInstance clearExtras() {
        extraCount = 0;
        return this;
    }

    /** A per-joint extra for the next {@link #evaluate} (head tracking / code-driven turns, as {@code RigPoser}). */
    public AnimGraphInstance extra(int joint, float preYawDeg, float prePitchDeg, float addX, float addY, float addZ) {
        if (joint < 0) return this;
        if (extraCount == extraCapacity) {
            int cap = Math.max(4, extraCapacity * 2);
            MemorySegment grown = Arena.ofAuto().allocate(CendaAnimGraph.EXTRA_BYTES * cap, 8);
            if (extraCount > 0) grown.copyFrom(extras.asSlice(0, CendaAnimGraph.EXTRA_BYTES * extraCount));
            extras = grown;
            extraCapacity = cap;
        }
        long o = CendaAnimGraph.EXTRA_BYTES * extraCount++;
        extras.set(CendaAnimGraph.I32, o, joint);
        extras.set(CendaAnimGraph.F32, o + 4, preYawDeg);
        extras.set(CendaAnimGraph.F32, o + 8, prePitchDeg);
        extras.set(CendaAnimGraph.F32, o + 12, addX);
        extras.set(CendaAnimGraph.F32, o + 16, addY);
        extras.set(CendaAnimGraph.F32, o + 20, addZ);
        return this;
    }

    /**
     * The one call per frame: advances by {@code dt} seconds, poses, and composes with the current extras under
     * {@code baseMatrix} (null = identity) into {@code out}. Allocation-free once {@code out} has grown.
     */
    public JointPalette evaluate(float dt, Matrix4f baseMatrix, JointPalette out) {
        int n = rig.jointCount();
        out.ensureCapacity(n);
        MemorySegment baseSeg = MemorySegment.NULL;
        if (baseMatrix != null) {
            writeMatrix(base, baseMatrix);
            baseSeg = base;
        }
        MemorySegment extraSeg = extraCount == 0 ? MemorySegment.NULL : extras;
        try {
            CendaAnimGraph.check((int) CendaAnimGraph.EVALUATE.invokeExact(handle(), dt, extraSeg, extraCount, baseSeg,
                out.nativeSkin(), out.nativeWorld(), MemorySegment.NULL), "evaluate");
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaAnimGraph.rethrow(t);
        }
        out.nativeWritten(n);
        return out;
    }

    private static void writeMatrix(MemorySegment s, Matrix4f m) {
        s.set(CendaAnimGraph.F32, 0, m.m00()); s.set(CendaAnimGraph.F32, 4, m.m01());
        s.set(CendaAnimGraph.F32, 8, m.m02()); s.set(CendaAnimGraph.F32, 12, m.m03());
        s.set(CendaAnimGraph.F32, 16, m.m10()); s.set(CendaAnimGraph.F32, 20, m.m11());
        s.set(CendaAnimGraph.F32, 24, m.m12()); s.set(CendaAnimGraph.F32, 28, m.m13());
        s.set(CendaAnimGraph.F32, 32, m.m20()); s.set(CendaAnimGraph.F32, 36, m.m21());
        s.set(CendaAnimGraph.F32, 40, m.m22()); s.set(CendaAnimGraph.F32, 44, m.m23());
        s.set(CendaAnimGraph.F32, 48, m.m30()); s.set(CendaAnimGraph.F32, 52, m.m31());
        s.set(CendaAnimGraph.F32, 56, m.m32()); s.set(CendaAnimGraph.F32, 60, m.m33());
    }

    /** The last frame's local pose: 9 floats per joint (pos, rot degrees, scale). */
    public float[] localPose(float[] out) {
        int n = rig.jointCount();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(36L * n, 4);
            CendaAnimGraph.check((int) CendaAnimGraph.LOCAL_POSE.invokeExact(handle(), seg), "localPose");
            MemorySegment.copy(seg, CendaAnimGraph.F32, 0, out, 0, n * 9);
            return out;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaAnimGraph.rethrow(t);
        }
    }

    public int notifyCount() {
        try {
            return (int) CendaAnimGraph.NOTIFY_COUNT.invokeExact(handle());
        } catch (Throwable t) {
            throw CendaAnimGraph.rethrow(t);
        }
    }

    public Notify notifyAt(int index) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment rec = arena.allocate(CendaAnimGraph.NOTIFY_BYTES, 4);
            MemorySegment name = arena.allocate(CendaAnimGraph.ADDR);
            MemorySegment payload = arena.allocate(CendaAnimGraph.ADDR);
            CendaAnimGraph.check((int) CendaAnimGraph.NOTIFY_GET.invokeExact(handle(), index, rec, name, payload), "notify");
            String p = CendaAnimGraph.string(payload.get(CendaAnimGraph.ADDR, 0));
            return new Notify(CendaAnimGraph.string(name.get(CendaAnimGraph.ADDR, 0)), p == null || p.isEmpty() ? null : p,
                rec.get(CendaAnimGraph.I32, 0), rec.get(CendaAnimGraph.I32, 4), rec.get(CendaAnimGraph.I32, 8),
                rec.get(CendaAnimGraph.I32, 12), rec.get(CendaAnimGraph.F32, 16), rec.get(CendaAnimGraph.F32, 20));
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaAnimGraph.rethrow(t);
        }
    }

    /** Every notify the last frame crossed. */
    public List<Notify> notifies() {
        int n = notifyCount();
        List<Notify> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(notifyAt(i));
        return out;
    }

    /** Curve names of the bound clips, in first-bound order (indices for {@link #curve}). */
    public List<String> curveNames() {
        try {
            int n = (int) CendaAnimGraph.CURVE_COUNT.invokeExact(handle());
            List<String> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) out.add(CendaAnimGraph.string((MemorySegment) CendaAnimGraph.CURVE_NAME.invokeExact(handle(), i)));
            return out;
        } catch (Throwable t) {
            throw CendaAnimGraph.rethrow(t);
        }
    }

    /** The last frame's value of curve {@code index}. */
    public float curve(int index) {
        try {
            return (float) CendaAnimGraph.CURVE_VALUE.invokeExact(handle(), index);
        } catch (Throwable t) {
            throw CendaAnimGraph.rethrow(t);
        }
    }

    /** The last frame's root motion: {@code out[0..2]} translation (root start frame), {@code out[3]} yaw degrees. */
    public float[] rootMotion(float[] out) {
        try {
            CendaAnimGraph.check((int) CendaAnimGraph.ROOT_MOTION.invokeExact(handle(), scratch), "rootMotion");
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaAnimGraph.rethrow(t);
        }
        MemorySegment.copy(scratch, CendaAnimGraph.F32, 0, out, 0, 4);
        return out;
    }

    /** One record per graph node. */
    public List<NodeDebug> debug() {
        int n = graph.nodes().size();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(CendaAnimGraph.DEBUG_BYTES * Math.max(1, n), 4);
            CendaAnimGraph.check((int) CendaAnimGraph.DEBUG.invokeExact(handle(), seg), "debug");
            List<NodeDebug> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                long o = CendaAnimGraph.DEBUG_BYTES * i;
                int flags = seg.get(CendaAnimGraph.I32, o + 16);
                out.add(new NodeDebug(seg.get(CendaAnimGraph.F32, o), seg.get(CendaAnimGraph.F32, o + 4),
                    seg.get(CendaAnimGraph.F32, o + 8), seg.get(CendaAnimGraph.I32, o + 12), (flags & 1) != 0, (flags & 2) != 0));
            }
            return out;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaAnimGraph.rethrow(t);
        }
    }

    @Override
    public void close() {
        cleanable.clean();
    }
}
