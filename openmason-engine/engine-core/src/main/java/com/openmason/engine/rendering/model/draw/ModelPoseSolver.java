package com.openmason.engine.rendering.model.draw;

import com.openmason.engine.entity.model.EntityAttachmentPoint;
import com.openmason.engine.entity.model.EntityGeometry;
import com.openmason.engine.entity.model.EntityPart;
import com.openmason.engine.entity.model.EntitySockets;
import com.openmason.engine.rendering.RenderOrigin;
import com.openmason.engine.rig.JointPalette;
import com.openmason.engine.rig.Rig;
import com.openmason.engine.rig.RigJoint;
import com.openmason.engine.rig.RigModel;
import com.openmason.engine.rig.RigPoser;
import com.openmason.engine.rig.SocketSet;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Pose adapter for part/bone models over the native rig ({@code cenda/rig.h}, {@link RigPoser}): the
 * per-joint SKIN matrices {@code K(j) = base·W_pose(j)·W_rest(j)⁻¹} of a {@link ClipLayers} frame, and
 * the world frame of named attachment points (sockets). The pose math (clip sampling, layering, the
 * part/bone hierarchy, head tracking) is native; this class only marshals.
 *
 * <p>Joint {@code i} of a model is its part {@code i}; bones follow the parts. A rigid part's vertices
 * draw with its own skin matrix — what {@link #forEachPartMatrix} hands out per part — and skinned
 * vertices blend the matrices of their joints ({@link #pose} → {@link JointPalette}).
 *
 * <pre>
 *   base        = T(position) · Ry(yaw) · S(scale)
 *   K(joint)    = base · W_pose(joint) · W_rest(joint)⁻¹
 *   socketWorld = K(host joint) · T(localPos) · R_xyz(localRotDeg) · S(localScale)
 * </pre>
 * Head tracking inserts {@code Ry(yaw) · Rx(pitch)} before the head joint's local pose rotation, so its
 * descendants inherit the turn about the animated neck ({@link Rig#headJoint()}).
 */
public final class ModelPoseSolver {

    /** Receives the computed world matrix for one part. The matrix is reused after the call. */
    @FunctionalInterface
    public interface PartConsumer {
        void accept(int partIndex, EntityPart part, Matrix4f partMatrix);
    }

    /**
     * Code-driven rotation for named joints (parts or bones), layered on top of whatever the clip (or
     * rest pose) gives them — how gameplay drives a joint no clip can know about. Rotates about the
     * joint's own pivot, so children follow.
     */
    @FunctionalInterface
    public interface PartOverrides {
        /** Euler degrees added to {@code partName}'s rotation, or null to leave it alone. */
        Vector3f extraRotationDeg(String partName);
    }

    private static final ThreadLocal<JointPalette> SCRATCH = ThreadLocal.withInitial(JointPalette::new);

    private ModelPoseSolver() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * The base model transform {@code T(position) · Ry(yawDegrees) · S(scale)},
     * built in <b>render space</b> ({@link RenderOrigin}). Model meshes carry no
     * normals — the lit pass differentiates the interpolated position — and a
     * derivative amplifies coordinate error, so absolute world coordinates degrade
     * into per-pixel lighting noise far from the origin. Every matrix downstream is
     * therefore render-space; {@link RenderOrigin#toWorld} recovers world positions.
     */
    public static Matrix4f baseMatrix(Vector3f position, float yawDegrees, Vector3f scale) {
        return RenderOrigin.modelAt(position.x, position.y, position.z)
                .rotateY((float) Math.toRadians(yawDegrees))
                .scale(scale);
    }

    /**
     * Poses every joint of {@code geometry}'s rig into {@code out} (skin + world matrices).
     *
     * @param overrides code-driven joint rotations, or null
     */
    public static JointPalette pose(EntityGeometry geometry, ClipLayers layers, Matrix4f base,
                                    float headYawDeg, float headPitchDeg, PartOverrides overrides,
                                    JointPalette out) {
        RigModel model = geometry.rig();
        Rig rig = model.rig();
        RigPoser poser = RigPoser.local().clearExtras();
        int head = (headYawDeg != 0f || headPitchDeg != 0f) ? rig.headJoint() : -1;
        boolean headDone = false;
        if (overrides != null) {
            List<RigJoint> joints = model.joints();
            for (int j = 0; j < joints.size(); j++) {
                Vector3f extra = overrides.extraRotationDeg(joints.get(j).name());
                boolean isHead = j == head;
                if (extra == null && !isHead) continue;
                poser.extra(j, isHead ? headYawDeg : 0f, isHead ? headPitchDeg : 0f,
                        extra != null ? extra.x : 0f, extra != null ? extra.y : 0f, extra != null ? extra.z : 0f);
                headDone |= isHead;
            }
        }
        if (head >= 0 && !headDone) {
            poser.extra(head, headYawDeg, headPitchDeg, 0f, 0f, 0f);
        }
        ClipLayers l = layers != null ? layers : ClipLayers.REST;
        List<RigPoser.Overlay> overlays = List.of();
        if (!l.overlays().isEmpty()) {
            overlays = new ArrayList<>(l.overlays().size());
            for (ClipLayers.Overlay o : l.overlays()) {
                overlays.add(new RigPoser.Overlay(o.clip(), o.time(), o.weight()));
            }
        }
        poser.pose(rig, l.baseClip(), l.baseTime(), overlays, base, out);
        poser.clearExtras();
        return out;
    }

    /** Per thread: a two-slot pose workspace per rig model with morph channels (weak keys: models may be unloaded). */
    private static final ThreadLocal<java.util.Map<RigModel, MorphScratch>> MORPH_SCRATCH =
            ThreadLocal.withInitial(java.util.WeakHashMap::new);

    private record MorphScratch(com.openmason.engine.rig.PoseWorkspace workspace,
                                com.openmason.engine.rig.PoseProgram program) {}

    /**
     * The morph (shape-key) weights of the frame {@link #pose} draws from the same {@code layers} (OpenMason #171):
     * one per target of {@code geometry}'s morphs ({@link EntityGeometry#morphs()}), the channel defaults with the
     * clips' OMANIM 1.3 morph tracks folded in like the joints ({@link RigPoser#morphWeights}). Returns {@code out}
     * (a new array when it is null or too short), or null when the model has no morph targets - draw with the
     * defaults then.
     */
    public static float[] morphWeights(EntityGeometry geometry, ClipLayers layers, float[] out) {
        RigModel model = geometry.rig();
        int n = model.morphCount();
        if (n == 0) return null;
        float[] result = out != null && out.length >= n ? out : new float[n];
        MorphScratch scratch = MORPH_SCRATCH.get().computeIfAbsent(model, m -> new MorphScratch(
                com.openmason.engine.rig.PoseWorkspace.create(m.rig(), 2), new com.openmason.engine.rig.PoseProgram()));
        ClipLayers l = layers != null ? layers : ClipLayers.REST;
        List<RigPoser.Overlay> overlays = List.of();
        if (!l.overlays().isEmpty()) {
            overlays = new ArrayList<>(l.overlays().size());
            for (ClipLayers.Overlay o : l.overlays()) {
                overlays.add(new RigPoser.Overlay(o.clip(), o.time(), o.weight()));
            }
        }
        RigPoser.local().morphWeights(scratch.workspace(), scratch.program(), l.baseClip(), l.baseTime(), overlays,
                result);
        return result;
    }

    /**
     * Walks the geometry's parts in order, handing each part's skin matrix (its rigid draw matrix) to
     * {@code consumer}. {@code headYawDeg}/{@code headPitchDeg} turn the head joint and its descendants
     * about its pivot.
     *
     * @param overrides code-driven joint rotations, or null
     */
    public static void forEachPartMatrix(EntityGeometry geometry, ClipLayers layers, Matrix4f base,
                                         float headYawDeg, float headPitchDeg,
                                         PartOverrides overrides, PartConsumer consumer) {
        JointPalette palette = pose(geometry, layers, base, headYawDeg, headPitchDeg, overrides, SCRATCH.get());
        Matrix4f partMatrix = new Matrix4f();
        List<EntityPart> parts = geometry.parts();
        for (int i = 0; i < parts.size(); i++) {
            consumer.accept(i, parts.get(i), palette.skinMatrix(i, partMatrix));
        }
    }

    /** The posed part matrices as a {@link PartMatrices} source for {@link ModelRenderer}. */
    public static PartMatrices partMatrices(EntityGeometry geometry, ClipLayers layers, Matrix4f base,
                                            float headYawDeg, float headPitchDeg, PartOverrides overrides) {
        return consumer -> forEachPartMatrix(geometry, layers, base, headYawDeg, headPitchDeg, overrides,
                (index, part, matrix) -> consumer.accept(index, matrix));
    }

    /**
     * World matrix of the named socket (case-insensitive) at the given pose, written
     * into {@code dest} and returned — or {@code null} when the socket is unknown, or
     * names a host joint that no longer exists (id and name fallback both miss), so
     * callers skip rendering rather than mount at the model origin.
     */
    public static Matrix4f socketWorldMatrix(EntityGeometry geometry, ClipLayers layers, Matrix4f base,
                                             float headYawDeg, float headPitchDeg,
                                             String socketName, Matrix4f dest) {
        if (geometry == null || socketName == null) return null;
        SocketSet sockets = EntitySockets.of(geometry);
        int index = sockets.find(socketName);
        if (index < 0) return null;
        JointPalette palette = sockets.info(index).joint() == SocketSet.ROOT ? null
                : pose(geometry, layers, base, headYawDeg, headPitchDeg, null, SCRATCH.get());
        return sockets.frame(index, palette, null, base, dest);
    }

    /**
     * Socket frame from an already-posed palette (no second pose): {@code K(host)·T·R·S}, the base for
     * a model-root socket. Null when the socket or its host is unknown. Resolved natively over the model's
     * {@link EntitySockets} (Stonebreak #459).
     */
    public static Matrix4f socketWorldMatrix(EntityGeometry geometry, JointPalette palette, Matrix4f base,
                                             String socketName, Matrix4f dest) {
        if (geometry == null || socketName == null) return null;
        SocketSet sockets = EntitySockets.of(geometry);
        int index = sockets.find(socketName);
        if (index < 0) return null;
        return sockets.frame(index, palette, null, base, dest);
    }

    /** Host joint (part or bone) by id, falling back to exact name; -1 when both miss. */
    public static int hostJoint(EntityGeometry geometry, EntityAttachmentPoint socket) {
        RigModel model = geometry.rig();
        if (socket.parentPartId() != null && !socket.parentPartId().isBlank()) {
            int j = model.jointOf(socket.parentPartId());
            if (j >= 0) return j;
        }
        if (socket.parentPartName() != null && !socket.parentPartName().isBlank()) {
            List<RigJoint> joints = model.joints();
            for (int j = 0; j < joints.size(); j++) {
                if (socket.parentPartName().equals(joints.get(j).name())) return j;
            }
        }
        return -1;
    }
}
