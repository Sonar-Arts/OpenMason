package com.openmason.engine.rendering.model.draw;

import com.openmason.engine.entity.sbe.SbeAttachmentPoint;
import com.openmason.engine.entity.sbe.SbeModelGeometry;
import com.openmason.engine.entity.sbe.SbePart;
import com.openmason.engine.format.oma.AnimLayering;
import com.openmason.engine.format.oma.AnimSampler;
import com.openmason.engine.format.oma.ParsedAnimClip;
import com.openmason.engine.format.oma.ParsedAnimTrack;
import com.openmason.engine.rendering.RenderOrigin;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * GL-free pose math for part-based models: per-part world matrices from a base
 * transform, the rest pose and a {@link ClipLayers} frame, and the world frame of
 * named attachment points (sockets). The single source of the per-part transform
 * pipeline — every {@link ModelRenderer} pass draws with these matrices, and
 * attachments mount at {@link #socketWorldMatrix} so they follow the animation.
 *
 * <p>Transform pipeline (rest-pose model space → world):
 * <pre>
 *   base       = T(position) · Ry(yaw) · S(scale)
 *   partMatrix = base · W_pose(part) · W_rest(part)⁻¹
 *   W(part)    = W(parent) · M(part)
 *   M(pos,rot,scale,origin) = T(pos) · T(origin) · R_xyz(rot) · S(scale) · T(-origin)
 * </pre>
 * Head tracking inserts {@code Ry(yaw) · Rx(pitch)} before the head's local pose
 * rotation, so its descendants inherit the turn about the animated neck. Socket
 * positions/rotations/scales are authored in rest-pose model space, so
 * <pre>
 *   socketWorld = partMatrix(host part) · T(localPos) · R_xyz(localRotDeg) · S(localScale)
 * </pre>
 * and the returned matrix is used directly as the attached model's base transform.
 */
public final class ModelPoseSolver {

    private static final Logger logger = LoggerFactory.getLogger(ModelPoseSolver.class);

    /** Receives the computed world matrix for one part. The matrix is reused after the call. */
    @FunctionalInterface
    public interface PartConsumer {
        void accept(int partIndex, SbePart part, Matrix4f partMatrix);
    }

    /**
     * Code-driven rotation for named parts, layered on top of whatever the clip (or
     * rest pose) gives them — how gameplay drives a part no clip can know about.
     * Rotates about the part's own origin, so children follow.
     */
    @FunctionalInterface
    public interface PartOverrides {
        /** Euler degrees added to {@code partName}'s rotation, or null to leave it alone. */
        Vector3f extraRotationDeg(String partName);
    }

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
     * Walks the geometry's parts in order, computing each part's world matrix from
     * the base transform plus its hierarchy-propagated pose delta. Un-animated
     * children inherit their parent's delta. With overlays, the per-part pose is
     * composed via {@link AnimLayering#blendPart}. {@code headYawDeg}/
     * {@code headPitchDeg} rotate the head (first part whose name contains "head")
     * and its descendants about its neck pivot.
     *
     * @param overrides code-driven part rotations, or null
     */
    public static void forEachPartMatrix(SbeModelGeometry geometry, ClipLayers layers, Matrix4f base,
                                         float headYawDeg, float headPitchDeg,
                                         PartOverrides overrides, PartConsumer consumer) {
        ResolvedAnim resolved = resolve(layers);
        Matrix4f partMatrix = new Matrix4f();
        HierarchyFrame frame = new HierarchyFrame(geometry, resolved, headYawDeg, headPitchDeg, overrides);
        List<SbePart> parts = geometry.parts();
        for (int i = 0; i < parts.size(); i++) {
            SbePart part = parts.get(i);
            computePartMatrix(partMatrix, frame, part, base);
            consumer.accept(i, part, partMatrix);
        }
    }

    /** The posed part matrices as a {@link PartMatrices} source for {@link ModelRenderer}. */
    public static PartMatrices partMatrices(SbeModelGeometry geometry, ClipLayers layers, Matrix4f base,
                                            float headYawDeg, float headPitchDeg, PartOverrides overrides) {
        return consumer -> forEachPartMatrix(geometry, layers, base, headYawDeg, headPitchDeg, overrides,
                (index, part, matrix) -> consumer.accept(index, matrix));
    }

    /**
     * World matrix of the named socket (case-insensitive) at the given pose, written
     * into {@code dest} and returned — or {@code null} when the socket is unknown, or
     * names a host part that no longer exists (id and name fallback both miss), so
     * callers skip rendering rather than mount at the model origin.
     */
    public static Matrix4f socketWorldMatrix(SbeModelGeometry geometry, ClipLayers layers, Matrix4f base,
                                             float headYawDeg, float headPitchDeg,
                                             String socketName, Matrix4f dest) {
        if (geometry == null || socketName == null) return null;

        SbeAttachmentPoint socket = null;
        for (SbeAttachmentPoint candidate : geometry.attachmentPoints()) {
            if (socketName.equalsIgnoreCase(candidate.name())) {
                socket = candidate;
                break;
            }
        }
        if (socket == null) return null;

        if (socket.isModelRoot()) {
            dest.set(base);
        } else {
            SbePart host = resolveHostPart(geometry, socket);
            if (host == null) return null;
            computePartMatrix(dest, new HierarchyFrame(geometry, resolve(layers), headYawDeg, headPitchDeg, null),
                    host, base);
        }

        Vector3f pos = socket.localPos();
        Vector3f rot = socket.localRotDeg();
        Vector3f scl = socket.localScale();
        return dest
                .translate(pos)
                .rotateXYZ((float) Math.toRadians(rot.x),
                        (float) Math.toRadians(rot.y),
                        (float) Math.toRadians(rot.z))
                .scale(scl);
    }

    /** Host part by id, falling back to name; null when both miss. */
    private static SbePart resolveHostPart(SbeModelGeometry geometry, SbeAttachmentPoint socket) {
        if (socket.parentPartId() != null && !socket.parentPartId().isBlank()) {
            for (SbePart part : geometry.parts()) {
                if (socket.parentPartId().equals(part.id())) {
                    return part;
                }
            }
        }
        if (socket.parentPartName() != null && !socket.parentPartName().isBlank()) {
            for (SbePart part : geometry.parts()) {
                if (socket.parentPartName().equals(part.name())) {
                    return part;
                }
            }
        }
        return null;
    }

    /** One part's world matrix into {@code dest}: {@code base · D(part)}. */
    private static void computePartMatrix(Matrix4f dest, HierarchyFrame frame, SbePart part, Matrix4f base) {
        Matrix4f delta = frame.delta(part);
        if (delta == null) {
            dest.set(base);
        } else {
            dest.set(base).mul(delta);
        }
    }

    /** A part's own local pose for the frame; {@code null} when nothing animates it. */
    private static AnimSampler.PartPose samplePartPose(SbePart part, ResolvedAnim resolved) {
        ParsedAnimTrack track = resolved.baseTracks().get(part.id());
        if (track == null) {
            track = trackByName(resolved.baseClip(), part.name());
        }
        if (!resolved.overlays().isEmpty()) {
            // Rest pose references the part's own vectors — no copies. blendPart
            // returns this same instance when nothing animates the part.
            AnimSampler.PartPose restPose = new AnimSampler.PartPose(
                    part.restPos(), part.restRot(), part.restScale());
            AnimSampler.PartPose pose = AnimLayering.blendPart(restPose, track, resolved.baseTime(),
                    resolved.overlays(), part.id(), part.name());
            return (pose == restPose && track == null) ? null : pose;
        }
        return track != null ? AnimSampler.sample(track, resolved.baseTime()) : null;
    }

    /**
     * Per-frame pose deltas with the part hierarchy propagated.
     *
     * <p>Mesh vertices are baked in model space at the rest pose, and a part's
     * rest/animated transforms are LOCAL to its parent. A part's model-space delta is
     * <pre>
     *   D(p) = W_anim(p) · W_rest(p)⁻¹
     *        = D(parent) · W_rest(parent) · L_anim(p) · L_rest(p)⁻¹ · W_rest(parent)⁻¹
     * </pre>
     * A part nothing animates inherits its parent's delta, so a child stays attached
     * when only the parent is keyed. Cycles / unknown parents count as root-level.
     */
    private static final class HierarchyFrame {
        private final ResolvedAnim resolved;
        private final String headPartId;
        private final float headYawRad;
        private final float headPitchRad;
        private final PartOverrides overrides;
        private final Map<String, SbePart> byId;
        private final Map<String, Matrix4f> deltas = new HashMap<>();
        private final Map<String, Matrix4f> restWorld = new HashMap<>();
        /** Sentinel for "no delta" (identity) so the memo can record it. */
        private static final Matrix4f IDENTITY = new Matrix4f();
        private static final int MAX_DEPTH = 32;

        HierarchyFrame(SbeModelGeometry geometry, ResolvedAnim resolved,
                       float headYawDeg, float headPitchDeg, PartOverrides overrides) {
            this.resolved = resolved;
            this.overrides = overrides;
            this.headPartId = (headYawDeg != 0f || headPitchDeg != 0f) ? headPartId(geometry) : null;
            this.headYawRad = (float) Math.toRadians(headYawDeg);
            this.headPitchRad = (float) Math.toRadians(headPitchDeg);
            this.byId = new HashMap<>(geometry.parts().size() * 2);
            for (SbePart p : geometry.parts()) {
                if (p.id() != null) byId.put(p.id(), p);
            }
        }

        private SbePart parentOf(SbePart part) {
            String pid = part.parentId();
            if (pid == null || pid.isBlank()) return null;
            SbePart parent = byId.get(pid);
            return parent == part ? null : parent;
        }

        Matrix4f delta(SbePart part) {
            return delta(part, 0);
        }

        private Matrix4f delta(SbePart part, int depth) {
            Matrix4f cached = deltas.get(part.id());
            if (cached != null) return cached == IDENTITY ? null : cached;

            AnimSampler.PartPose pose = samplePartPose(part, resolved);
            boolean trackedHead = headPartId != null && headPartId.equals(part.id());
            Vector3f extraRot = overrides != null ? overrides.extraRotationDeg(part.name()) : null;
            SbePart parent = depth < MAX_DEPTH ? parentOf(part) : null;
            Matrix4f parentDelta = parent != null ? delta(parent, depth + 1) : null;

            Matrix4f result;
            if (pose == null && !trackedHead && extraRot == null) {
                // L_anim == L_rest: inherit the parent's delta verbatim.
                result = parentDelta;
            } else {
                Vector3f origin = part.restOrigin();
                Vector3f rotDeg = pose != null ? pose.rotationDeg() : part.restRot();
                if (extraRot != null) {
                    rotDeg = new Vector3f(rotDeg).add(extraRot);
                }
                // Tracking belongs in the local pose, so facial children and their
                // sockets inherit it along with any animated ancestors.
                Matrix4f local = partTransform(new Matrix4f(),
                        pose != null ? pose.position() : part.restPos(),
                        rotDeg,
                        pose != null ? pose.scale() : part.restScale(), origin,
                        trackedHead ? headYawRad : 0f, trackedHead ? headPitchRad : 0f);
                Matrix4f restInverse = partTransform(new Matrix4f(),
                        part.restPos(), part.restRot(), part.restScale(), origin, 0f, 0f).invert();
                local.mul(restInverse); // L_anim · L_rest⁻¹
                if (parent == null) {
                    result = local;
                } else {
                    Matrix4f wr = restWorld(parent, depth + 1);
                    result = new Matrix4f();
                    if (parentDelta != null) result.set(parentDelta);
                    result.mul(wr).mul(local).mul(wr.invert(new Matrix4f()));
                }
            }
            deltas.put(part.id(), result == null ? IDENTITY : result);
            return result;
        }

        /** Rest transform of a part chained from the root (model space). */
        private Matrix4f restWorld(SbePart part, int depth) {
            Matrix4f cached = restWorld.get(part.id());
            if (cached != null) return cached;
            Matrix4f m = new Matrix4f();
            SbePart parent = depth < MAX_DEPTH ? parentOf(part) : null;
            if (parent != null) m.set(restWorld(parent, depth + 1));
            partTransform(m, part.restPos(), part.restRot(), part.restScale(), part.restOrigin(), 0f, 0f);
            restWorld.put(part.id(), m);
            return m;
        }
    }

    /** A render-ready frame: base clip with wrapped time + track lookup, and live overlays. */
    private record ResolvedAnim(ParsedAnimClip baseClip, float baseTime,
                                Map<String, ParsedAnimTrack> baseTracks,
                                List<AnimLayering.OverlayFrame> overlays) {}

    private static final ResolvedAnim REST = new ResolvedAnim(null, 0f, Collections.emptyMap(), List.of());

    /**
     * Wraps clip times and combines each overlay's envelope with the clip's own
     * fade ({@link AnimLayering#clipWeight}); zero-weight or clip-less overlays are
     * dropped so the per-part loop stays on its fast path.
     */
    private static ResolvedAnim resolve(ClipLayers layers) {
        if (layers == null) return REST;
        ParsedAnimClip baseClip = layers.baseClip();
        float baseTime = 0f;
        Map<String, ParsedAnimTrack> baseTracks = Collections.emptyMap();
        if (baseClip != null) {
            baseTime = AnimSampler.wrapTime(layers.baseTime(), baseClip.duration(), baseClip.loop());
            baseTracks = baseClip.trackByPartId();
        }

        List<AnimLayering.OverlayFrame> overlays = List.of();
        if (!layers.overlays().isEmpty()) {
            overlays = new ArrayList<>(layers.overlays().size());
            for (ClipLayers.Overlay overlay : layers.overlays()) {
                ParsedAnimClip clip = overlay.clip();
                if (clip == null) continue;
                float weight = overlay.weight() * AnimLayering.clipWeight(clip, overlay.time());
                if (weight <= 0f) continue;
                float wrapped = AnimSampler.wrapTime(overlay.time(), clip.duration(), clip.loop());
                overlays.add(new AnimLayering.OverlayFrame(clip, wrapped, weight));
            }
        }
        return new ResolvedAnim(baseClip, baseTime, baseTracks, overlays);
    }

    /** One-time diagnostic guard so the head-part lookup logs at most once. */
    private static volatile boolean headLookupLogged = false;

    /** Part id of the first part whose name contains "head" (case-insensitive), or null. */
    private static String headPartId(SbeModelGeometry geometry) {
        String found = null;
        for (SbePart part : geometry.parts()) {
            String n = part.name();
            if (n != null && n.toLowerCase(Locale.ROOT).contains("head")) {
                found = part.id();
                break;
            }
        }
        if (!headLookupLogged) {
            headLookupLogged = true;
            StringBuilder names = new StringBuilder();
            for (SbePart part : geometry.parts()) {
                names.append(part.name()).append(' ');
            }
            logger.debug("Head-tracking part lookup: head={} parts=[{}]", found, names.toString().trim());
        }
        return found;
    }

    /**
     * Post-multiplies a part's local TRS transform onto {@code dest}:
     * {@code T(pos) · T(origin) · Ry(headYaw) · Rx(headPitch) · R(rot) · S(scale) · T(-origin)}.
     */
    private static Matrix4f partTransform(Matrix4f dest, Vector3f pos, Vector3f rotDeg,
                                          Vector3f scale, Vector3f origin,
                                          float headYawRad, float headPitchRad) {
        return dest
                .translate(pos)
                .translate(origin)
                .rotateY(headYawRad)
                .rotateX(headPitchRad)
                .rotateXYZ((float) Math.toRadians(rotDeg.x),
                        (float) Math.toRadians(rotDeg.y),
                        (float) Math.toRadians(rotDeg.z))
                .scale(scale)
                .translate(-origin.x, -origin.y, -origin.z);
    }

    /** Fallback track lookup by part name when the part id does not match. */
    private static ParsedAnimTrack trackByName(ParsedAnimClip clip, String partName) {
        if (clip == null || partName == null) return null;
        for (ParsedAnimTrack track : clip.tracks()) {
            if (partName.equals(track.partName())) {
                return track;
            }
        }
        return null;
    }
}
