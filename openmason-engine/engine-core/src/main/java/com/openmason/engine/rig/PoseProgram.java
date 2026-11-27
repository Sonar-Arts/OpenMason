package com.openmason.engine.rig;

import com.openmason.engine.format.oma.ParsedAnimClip;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;

/**
 * A list of pose operations ({@code cr_pose_op}, CR ABI 2, OpenMason #156) a {@link PoseWorkspace} runs in one
 * native call: sample, blend, override / additive layers under {@link BoneMask}s, additive deltas, inertialized
 * transitions ({@link Inertializer}) and root-motion removal. Semantics in {@code cenda/rig.h} "Pose pipeline".
 *
 * <p>Reusable: {@link #clear()} and rebuild it every frame; once its buffers have grown, building and running
 * allocate nothing (use the {@link RigClip} overloads — {@link RigClip#of} looks the clip up in a cache). The
 * program keeps every clip, mask and inertializer it names reachable until it is cleared. Not thread-safe.
 */
public final class PoseProgram {

    /** Blend rotations as quaternions (default: per Euler component, the ABI 1 rule). */
    public static final int QUAT = 1;
    /** Additive delta / layer in model (mesh) space. */
    public static final int MESH_SPACE = 1 << 1;
    /** Override layer with Maya's passthrough off: a partial weight blends from the rest pose. */
    public static final int NO_PASSTHROUGH = 1 << 2;
    /** Sample: drive only the joints the clip's name mask covers (an ABI 1 overlay). */
    public static final int CLIP_MASK = 1 << 3;
    /** Sample: every joint driven, unkeyed joints at rest (a montage; every morph channel too). */
    public static final int DRIVE_ALL = 1 << 4;
    /**
     * Sample (OpenMason #171): only the morph weights (channel defaults, then the clip's morph tracks); joints
     * untouched.
     */
    public static final int MORPHS_ONLY = 1 << 5;

    /** Layer modes. */
    public static final int OVERRIDE = 0;
    public static final int ADDITIVE = 1;

    /** Root-motion axes (= {@code CM_ROOT_*}). */
    public static final int ROOT_X = 1;
    public static final int ROOT_Y = 1 << 1;
    public static final int ROOT_Z = 1 << 2;
    public static final int ROOT_YAW = 1 << 3;

    static final int OP_REST = 0;
    static final int OP_SAMPLE = 1;
    static final int OP_COPY = 2;
    static final int OP_BLEND = 3;
    static final int OP_LAYER = 4;
    static final int OP_MAKE_ADDITIVE = 5;
    static final int OP_INERTIALIZE = 6;
    static final int OP_REMOVE_ROOT = 7;

    private MemorySegment ops = MemorySegment.NULL;
    private int capacity;
    private int count;
    private Object[] keep = new Object[0];
    private int[] weightOffset = new int[0];   // per op: first float of its BLEND weights, -1 otherwise
    private MemorySegment weights = MemorySegment.NULL;
    private int weightCapacity;
    private int weightCount;

    /** Forgets every op (and the references it held). */
    public PoseProgram clear() {
        Arrays.fill(keep, 0, count, null);
        count = 0;
        weightCount = 0;
        return this;
    }

    /** Number of ops. */
    public int size() {
        return count;
    }

    MemorySegment ops() {
        return count == 0 ? MemorySegment.NULL : ops;
    }

    private long next(int op, int dst, Object ref) {
        if (count == capacity) {
            int cap = Math.max(8, capacity * 2);
            MemorySegment grown = Arena.ofAuto().allocate(CendaRig.OP_BYTES * cap, 8);
            if (count > 0) grown.copyFrom(ops.asSlice(0, CendaRig.OP_BYTES * count));
            ops = grown;
            keep = Arrays.copyOf(keep, cap);
            weightOffset = Arrays.copyOf(weightOffset, cap);
            capacity = cap;
        }
        long o = CendaRig.OP_BYTES * count;
        for (long z = 0; z < CendaRig.OP_BYTES; z += 8) ops.set(ValueLayout.JAVA_LONG, o + z, 0L);  // no slice object
        ops.set(CendaRig.I32, o, op);
        ops.set(CendaRig.I32, o + 4, dst);
        keep[count] = ref;
        weightOffset[count] = -1;
        count++;
        return o;
    }

    private static MemorySegment handleOf(RigClip clip) {
        if (clip == null) throw new IllegalArgumentException("clip is null");
        return clip.handle();
    }

    /** dst = the rest pose (nothing driven). */
    public PoseProgram rest(int dst) {
        next(OP_REST, dst, null);
        return this;
    }

    /** dst = {@code clip} at {@code time} elapsed seconds (wrapped / clamped); {@code flags}: {@link #CLIP_MASK}. */
    public PoseProgram sample(int dst, RigClip clip, float time, int flags) {
        long o = next(OP_SAMPLE, dst, clip);
        ops.set(CendaRig.ADDR, o + 16, handleOf(clip));
        ops.set(CendaRig.F32, o + 24, time);
        ops.set(CendaRig.I32, o + 40, flags);
        return this;
    }

    /** {@link #sample(int, RigClip, float, int)} through the {@link RigClip#of} cache. */
    public PoseProgram sample(int dst, ParsedAnimClip clip, float time, int flags) {
        return sample(dst, RigClip.of(clip), time, flags);
    }

    /** dst = src. */
    public PoseProgram copy(int dst, int src) {
        long o = next(OP_COPY, dst, null);
        ops.set(CendaRig.I32, o + 8, src);
        return this;
    }

    /**
     * dst = the weighted blend of slots {@code first .. first+count-1} with {@code weights[0..count)} (normalized;
     * <= 0 skipped; a single positive weight copies exactly). {@code flags}: {@link #QUAT}.
     */
    public PoseProgram blend(int dst, int first, int count, float[] weights, int flags) {
        if (count < 1 || weights.length < count) throw new IllegalArgumentException("need count >= 1 weights");
        long o = next(OP_BLEND, dst, null);
        int at = reserveWeights(count);
        MemorySegment.copy(weights, 0, this.weights, CendaRig.F32, 4L * at, count);
        weightOffset[this.count - 1] = at;
        ops.set(CendaRig.I32, o + 8, first);
        ops.set(CendaRig.I32, o + 12, count);
        ops.set(ValueLayout.JAVA_LONG, o + 16, this.weights.address() + 4L * at);  // no slice object
        ops.set(CendaRig.I32, o + 40, flags);
        return this;
    }

    private int reserveWeights(int n) {
        if (weightCount + n > weightCapacity) {
            int cap = Math.max(16, Math.max(weightCount + n, weightCapacity * 2));
            MemorySegment grown = Arena.ofAuto().allocate(4L * cap, 8);
            if (weightCount > 0) grown.copyFrom(weights.asSlice(0, 4L * weightCount));
            weights = grown;
            weightCapacity = cap;
            // Earlier BLEND ops point into the old pool: repoint them.
            for (int i = 0; i < count; i++) {
                if (weightOffset[i] >= 0) {
                    ops.set(ValueLayout.JAVA_LONG, CendaRig.OP_BYTES * i + 16, weights.address() + 4L * weightOffset[i]);
                }
            }
        }
        int at = weightCount;
        weightCount += n;
        return at;
    }

    /**
     * dst layered with src: {@link #OVERRIDE} (lerp toward src) or {@link #ADDITIVE} (src is a delta from
     * {@link #makeAdditive}), at {@code weight} x the mask's weight per joint ({@code mask} null = 1).
     * {@code flags}: {@link #QUAT}, {@link #MESH_SPACE}, {@link #NO_PASSTHROUGH}.
     */
    public PoseProgram layer(int dst, int src, BoneMask mask, float weight, int mode, int flags) {
        long o = next(OP_LAYER, dst, mask);
        ops.set(CendaRig.I32, o + 8, src);
        ops.set(CendaRig.ADDR, o + 16, mask == null ? MemorySegment.NULL : mask.handle());
        ops.set(CendaRig.F32, o + 28, weight);
        ops.set(CendaRig.I32, o + 36, mode);
        ops.set(CendaRig.I32, o + 40, flags);
        return this;
    }

    /** dst = src - reference (an additive delta); {@code flags}: {@link #MESH_SPACE}. */
    public PoseProgram makeAdditive(int dst, int src, int reference, int flags) {
        long o = next(OP_MAKE_ADDITIVE, dst, null);
        ops.set(CendaRig.I32, o + 8, src);
        ops.set(CendaRig.I32, o + 12, reference);
        ops.set(CendaRig.I32, o + 40, flags);
        return this;
    }

    /** dst += the inertializer's decaying transition offset after {@code dt} more seconds (records dst). */
    public PoseProgram inertialize(int dst, Inertializer inertializer, float dt) {
        long o = next(OP_INERTIALIZE, dst, inertializer);
        ops.set(CendaRig.ADDR, o + 16, inertializer.handle());
        ops.set(CendaRig.F32, o + 32, dt);
        return this;
    }

    /** In dst, {@code joint} keeps {@code clip}'s first-frame values on {@code axes} ({@link #ROOT_X} ...). */
    public PoseProgram removeRootMotion(int dst, RigClip clip, int joint, int axes) {
        long o = next(OP_REMOVE_ROOT, dst, clip);
        ops.set(CendaRig.I32, o + 12, joint);
        ops.set(CendaRig.ADDR, o + 16, handleOf(clip));
        ops.set(CendaRig.I32, o + 44, axes);
        return this;
    }
}
