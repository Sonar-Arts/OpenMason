package com.openmason.engine.rig;

import com.openmason.engine.format.oma.ParsedAnimClip;
import org.joml.Matrix4f;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;

/**
 * Poses a {@link Rig} into a {@link JointPalette} through the native {@code cr_rig_pose}: base clip +
 * weighted overlays + per-joint extras (head tracking, code-driven turns). Holds reusable native
 * scratch, so a poser belongs to one thread (keep one per renderer / use {@link #local()}).
 */
public final class RigPoser {

    /** One overlay layer: clip, elapsed seconds since it started (unwrapped), caller weight 0..1. */
    public record Overlay(ParsedAnimClip clip, float time, float weight) {}

    private static final ThreadLocal<RigPoser> LOCAL = ThreadLocal.withInitial(RigPoser::new);

    /** The calling thread's poser. */
    public static RigPoser local() {
        return LOCAL.get();
    }

    private MemorySegment layers = MemorySegment.NULL;
    private int layerCapacity;
    private MemorySegment extras = MemorySegment.NULL;
    private int extraCapacity;
    private int extraCount;
    private MemorySegment base = MemorySegment.NULL;
    private MemorySegment locals = MemorySegment.NULL;
    private int localsCapacity;

    public RigPoser() {
        base = Arena.ofAuto().allocate(64, 16);
    }

    /** Forget the extras added since the last pose. */
    public RigPoser clearExtras() {
        extraCount = 0;
        return this;
    }

    /**
     * Adds a per-joint extra for the next pose: pre-rotation yaw/pitch in degrees (applied about the
     * joint's pivot before its own rotation — head tracking) and additive Euler degrees.
     * Joints < 0 are ignored. Extras stay until {@link #clearExtras()}.
     */
    public RigPoser extra(int joint, float preYawDeg, float prePitchDeg, float addX, float addY, float addZ) {
        if (joint < 0) return this;
        if (extraCount == extraCapacity) {
            int cap = Math.max(4, extraCapacity * 2);
            MemorySegment grown = Arena.ofAuto().allocate(CendaRig.EXTRA_BYTES * cap, 8);
            if (extraCount > 0) grown.copyFrom(extras.asSlice(0, CendaRig.EXTRA_BYTES * extraCount));
            extras = grown;
            extraCapacity = cap;
        }
        long o = CendaRig.EXTRA_BYTES * extraCount++;
        extras.set(CendaRig.I32, o, joint);
        extras.set(CendaRig.F32, o + 4, preYawDeg);
        extras.set(CendaRig.F32, o + 8, prePitchDeg);
        extras.set(CendaRig.F32, o + 12, addX);
        extras.set(CendaRig.F32, o + 16, addY);
        extras.set(CendaRig.F32, o + 20, addZ);
        return this;
    }

    /**
     * Poses {@code rig}: {@code baseClip} at {@code baseTime} elapsed seconds (null = rest pose),
     * {@code overlays} folded on top, the extras added since {@link #clearExtras()} applied, all under
     * {@code baseMatrix} (null = identity). Writes the skin and world matrices of every joint into
     * {@code out} (grown as needed).
     */
    public JointPalette pose(Rig rig, ParsedAnimClip baseClip, float baseTime, List<Overlay> overlays,
                             Matrix4f baseMatrix, JointPalette out) {
        int n = rig.jointCount();
        out.ensureCapacity(n);
        int layerCount = overlays == null ? 0 : overlays.size();
        if (layerCount > layerCapacity) {
            layerCapacity = Math.max(4, layerCount);
            layers = Arena.ofAuto().allocate(CendaRig.LAYER_BYTES * layerCapacity, 8);
        }
        // The native clips must stay reachable until the call returns.
        RigClip baseNative = RigClip.of(baseClip);
        RigClip[] keep = layerCount == 0 ? null : new RigClip[layerCount];
        for (int i = 0; i < layerCount; i++) {
            Overlay ov = overlays.get(i);
            RigClip c = ov == null ? null : RigClip.of(ov.clip());
            if (keep != null) keep[i] = c;
            long o = CendaRig.LAYER_BYTES * i;
            layers.set(CendaRig.ADDR, o, c == null ? MemorySegment.NULL : c.handle());
            layers.set(CendaRig.F32, o + 8, ov == null ? 0f : ov.time());
            layers.set(CendaRig.F32, o + 12, ov == null ? 0f : ov.weight());
        }
        MemorySegment baseSeg = MemorySegment.NULL;
        if (baseMatrix != null) {
            JointPalette.write(base, 0, baseMatrix);
            baseSeg = base;
        }
        // Typed locals: a conditional passed straight to invokeExact is typed Object.
        MemorySegment clipSeg = baseNative == null ? MemorySegment.NULL : baseNative.handle();
        MemorySegment layerSeg = layerCount == 0 ? MemorySegment.NULL : layers;
        MemorySegment extraSeg = extraCount == 0 ? MemorySegment.NULL : extras;
        try {
            int rc = (int) CendaRig.RIG_POSE.invokeExact(rig.handle(), clipSeg, baseTime,
                layerSeg, layerCount, extraSeg, extraCount,
                baseSeg, out.skinSegment(), out.worldSegment(), MemorySegment.NULL);
            if (rc != CendaRig.OK) throw new IllegalArgumentException("cr_rig_pose: " + CendaRig.lastError());
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
        java.lang.ref.Reference.reachabilityFence(keep);
        java.lang.ref.Reference.reachabilityFence(baseNative);
        out.setCount(n);
        return out;
    }

    /**
     * The one pose call per character per frame (CR ABI 2): runs {@code program} on {@code workspace}, then
     * composes {@code slot} with the extras added since {@link #clearExtras()} under {@code baseMatrix} (null =
     * identity) into {@code out}. Allocation-free once the program, the extras and {@code out} have grown.
     */
    public JointPalette evaluate(PoseWorkspace workspace, PoseProgram program, int slot, Matrix4f baseMatrix,
                                 JointPalette out) {
        int n = workspace.rig().jointCount();
        out.ensureCapacity(n);
        MemorySegment baseSeg = MemorySegment.NULL;
        if (baseMatrix != null) {
            JointPalette.write(base, 0, baseMatrix);
            baseSeg = base;
        }
        MemorySegment extraSeg = extraCount == 0 ? MemorySegment.NULL : extras;
        try {
            int rc = (int) CendaRig.POSES_EVALUATE.invokeExact(workspace.handle(), program.ops(), program.size(), slot,
                extraSeg, extraCount, baseSeg, out.skinSegment(), out.worldSegment(), MemorySegment.NULL);
            if (rc != CendaRig.OK) throw new IllegalArgumentException("cr_poses_evaluate: " + CendaRig.lastError());
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
        java.lang.ref.Reference.reachabilityFence(program);
        out.setCount(n);
        return out;
    }

    /**
     * The MORPH WEIGHTS (OpenMason #171) of the frame {@link #pose} composes from the same inputs: the rig's channel
     * defaults, {@code baseClip}'s morph tracks at {@code baseTime}, then each overlay's (its clip mask, weight x the
     * clip's fade envelope, ordered by priority - the ABI 1 overlay fold) as a pose program on {@code workspace} slots
     * 0 and 1 ({@code cr_rig_pose} composes joints only). Writes min(channels, out.length) weights and returns the
     * channel count; a rig without channels returns 0 and runs nothing. {@code program} is reused scratch.
     */
    public int morphWeights(PoseWorkspace workspace, PoseProgram program, ParsedAnimClip baseClip, float baseTime,
                            List<Overlay> overlays, float[] out) {
        Rig rig = workspace.rig();
        int n = rig.morphCount();
        if (n == 0) return 0;
        program.clear();
        RigClip baseNative = RigClip.of(baseClip);
        if (baseNative != null) {
            program.sample(0, baseNative, baseTime, PoseProgram.MORPHS_ONLY);
        } else {
            program.rest(0);
        }
        int layerCount = overlays == null ? 0 : overlays.size();
        if (layerCount > 0) {
            // The ABI 1 fold: weight x fade envelope, <= 0 skipped, stable by priority.
            Overlay[] live = new Overlay[layerCount];
            float[] weight = new float[layerCount];
            int count = 0;
            for (Overlay ov : overlays) {
                if (ov == null || ov.clip() == null) continue;
                RigClip c = RigClip.of(ov.clip());
                float w = ov.weight() * c.weightAt(ov.time());
                if (w <= 0f) continue;
                live[count] = ov;
                weight[count++] = w;
            }
            Integer[] order = new Integer[count];
            for (int i = 0; i < count; i++) order[i] = i;
            java.util.Arrays.sort(order, java.util.Comparator.comparingInt(i -> live[i].clip().layer().priority()));
            for (int i : order) {
                program.sample(1, RigClip.of(live[i].clip()), live[i].time(),
                    PoseProgram.CLIP_MASK | PoseProgram.MORPHS_ONLY);
                program.layer(0, 1, null, weight[i], PoseProgram.OVERRIDE, 0);
            }
        }
        workspace.run(program);
        program.clear();
        java.lang.ref.Reference.reachabilityFence(baseNative);
        return workspace.morphWeights(0, out);
    }

    /**
     * Poses {@code rig} from explicit local transforms ({@code locals}: 9 floats per joint — pos, rot
     * degrees, scale — or null for the rest pose) plus the current extras, under {@code baseMatrix}.
     */
    public JointPalette poseLocals(Rig rig, float[] localValues, Matrix4f baseMatrix, JointPalette out) {
        int n = rig.jointCount();
        out.ensureCapacity(n);
        MemorySegment localSeg = MemorySegment.NULL;
        if (localValues != null) {
            if (localValues.length < n * 9) {
                throw new IllegalArgumentException("Need 9 local floats per joint: " + localValues.length + " < " + n * 9);
            }
            if (localsCapacity < n) {
                localsCapacity = Math.max(n, localsCapacity * 2);
                locals = Arena.ofAuto().allocate(36L * localsCapacity, 4);
            }
            MemorySegment.copy(localValues, 0, locals, CendaRig.F32, 0, n * 9);
            localSeg = locals;
        }
        MemorySegment baseSeg = MemorySegment.NULL;
        if (baseMatrix != null) {
            JointPalette.write(base, 0, baseMatrix);
            baseSeg = base;
        }
        MemorySegment extraSeg = extraCount == 0 ? MemorySegment.NULL : extras;
        try {
            int rc = (int) CendaRig.RIG_POSE_LOCALS.invokeExact(rig.handle(), localSeg,
                extraSeg, extraCount,
                baseSeg, out.skinSegment(), out.worldSegment(), MemorySegment.NULL);
            if (rc != CendaRig.OK) throw new IllegalArgumentException("cr_rig_pose_locals: " + CendaRig.lastError());
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
        out.setCount(n);
        return out;
    }

    /**
     * The local pose of every joint for a frame (what {@link #pose} composes): 9 floats per joint
     * (pos, rot degrees, scale) into {@code out} ({@code jointCount * 9}).
     */
    public float[] localPose(Rig rig, ParsedAnimClip baseClip, float baseTime, List<Overlay> overlays, float[] out) {
        int n = rig.jointCount();
        int layerCount = overlays == null ? 0 : overlays.size();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment layerSeg = arena.allocate(CendaRig.LAYER_BYTES * Math.max(1, layerCount), 8);
            RigClip[] keep = new RigClip[layerCount];
            for (int i = 0; i < layerCount; i++) {
                Overlay ov = overlays.get(i);
                keep[i] = ov == null ? null : RigClip.of(ov.clip());
                long o = CendaRig.LAYER_BYTES * i;
                layerSeg.set(CendaRig.ADDR, o, keep[i] == null ? MemorySegment.NULL : keep[i].handle());
                layerSeg.set(CendaRig.F32, o + 8, ov == null ? 0f : ov.time());
                layerSeg.set(CendaRig.F32, o + 12, ov == null ? 0f : ov.weight());
            }
            RigClip baseNative = RigClip.of(baseClip);
            MemorySegment result = arena.allocate(36L * n, 4);
            MemorySegment clipSeg = baseNative == null ? MemorySegment.NULL : baseNative.handle();
            MemorySegment layersArg = layerCount == 0 ? MemorySegment.NULL : layerSeg;
            int rc = (int) CendaRig.RIG_LOCAL_POSE.invokeExact(rig.handle(), clipSeg, baseTime,
                layersArg, layerCount, result);
            if (rc != CendaRig.OK) throw new IllegalArgumentException("cr_rig_local_pose: " + CendaRig.lastError());
            MemorySegment.copy(result, CendaRig.F32, 0, out, 0, n * 9);
            java.lang.ref.Reference.reachabilityFence(keep);
            java.lang.ref.Reference.reachabilityFence(baseNative);
            return out;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }
}
