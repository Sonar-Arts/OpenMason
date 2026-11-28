package com.openmason.main.systems.mcp;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.modeling.parts.ModelPartDescriptor;
import com.openmason.engine.modeling.parts.ModelPartManager;
import com.openmason.engine.modeling.parts.PartMeshRebuilder;
import com.openmason.engine.rendering.viewer.camera.CameraMath;
import com.openmason.engine.rendering.viewer.camera.ViewerCamera;
import com.openmason.engine.rendering.viewer.gizmo.GizmoDisplayMode;
import com.openmason.engine.rendering.viewer.gizmo.GizmoState;
import com.openmason.main.systems.ViewportController;
import com.openmason.main.systems.services.EditorServices;
import com.openmason.main.systems.skeleton.BoneStore;
import com.openmason.main.systems.threading.MainThreadExecutor;
import com.openmason.main.systems.viewport.ViewportUIState;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * View-only control of the model viewport for the MCP server: the orbit camera
 * ({@code viewport_camera_*}) and the transform gizmo's visibility/mode ({@code viewport_gizmo}).
 *
 * <p>Thin plumbing over the existing {@link ViewerCamera} / gizmo state, run on the main/GL
 * thread via {@link MainThreadExecutor} like every other MCP service. Nothing here touches the
 * model: no undo entries, no dirty flag. Camera changes SNAP (the camera's setters set both the
 * current and the interpolation target), so the next frame - and the next {@code viewport_capture}
 * - shows the new view. Coordinates are the viewport's world space, which is model space unless
 * the Transform panel's model transform is not the identity (reported as
 * {@code model_transform_identity}).
 */
public final class ViewportViewService {

    private static final long DEFAULT_TIMEOUT_MS = 5000;
    /** Framing radius floor for a bone (a zero-length bone still frames as a small close-up). */
    private static final float MIN_BONE_RADIUS = 0.05f;
    /** Radius around {@code point} when the caller gives none: a quarter block. */
    static final float DEFAULT_POINT_RADIUS = 0.25f;

    private final EditorServices editorServices;

    public ViewportViewService(EditorServices editorServices) {
        this.editorServices = editorServices;
    }

    /** Camera fields to change; null = keep. {@code eye} replaces distance/yaw/pitch. */
    public record CameraChange(Vector3f target, Float distance, Float yaw, Float pitch, Float fov, Vector3f eye) {
    }

    /** What to frame (all given subjects are united) and from where; null/empty = not given. */
    public record FrameRequest(List<String> parts, String bone, Vector3f boxMin, Vector3f boxMax,
                               Vector3f point, Float radius, String view, Float yaw, Float pitch,
                               Float margin) {
    }

    // ===================== Camera =====================

    public Map<String, Object> getCamera() {
        return await(MainThreadExecutor.submit(() -> describe(requireViewport(), null)));
    }

    public Map<String, Object> setCamera(CameraChange change) {
        if (change.eye() != null && (change.distance() != null || change.yaw() != null || change.pitch() != null)) {
            throw new IllegalArgumentException(
                    "eye sets distance/yaw/pitch itself; pass either eye (+ target) or distance/yaw/pitch");
        }
        return await(MainThreadExecutor.submit(() -> {
            ViewportController vp = requireViewport();
            ViewerCamera camera = arcball(vp);
            Vector3f target = change.target() != null ? new Vector3f(change.target()) : camera.getTarget();
            Float distance = change.distance();
            Float yaw = change.yaw();
            Float pitch = change.pitch();
            if (change.eye() != null) {
                ViewportCameraFraming.Orbit orbit = ViewportCameraFraming.fromEye(change.eye(), target);
                distance = orbit.distance();
                yaw = orbit.yaw();
                pitch = orbit.pitch();
            }
            List<String> clamped = new ArrayList<>();
            camera.setTarget(target);
            if (distance != null) {
                camera.setDistance(distance);
                noteClamp(clamped, "distance", distance, camera.getDistance());
            }
            if (yaw != null) {
                camera.setYaw(yaw);
            }
            if (pitch != null) {
                camera.setPitch(pitch);
                noteClamp(clamped, "pitch", pitch, camera.getPitch());
            }
            if (change.fov() != null) {
                camera.setFov(change.fov());
                noteClamp(clamped, "fov", change.fov(), camera.getFov());
            }
            settle(vp, camera);
            Map<String, Object> out = describe(vp, null);
            if (!clamped.isEmpty()) out.put("clamped", clamped);
            return out;
        }));
    }

    public Map<String, Object> frame(FrameRequest request) {
        float margin = ViewportCameraFraming.margin(request.margin());
        float[] preset = request.view() != null ? ViewportCameraFraming.presetAngles(request.view()) : null;
        if ((request.boxMin() == null) != (request.boxMax() == null)) {
            throw new IllegalArgumentException("box_min and box_max go together");
        }
        return await(MainThreadExecutor.submit(() -> {
            ViewportController vp = requireViewport();
            Matrix4f model = vp.getTransformState().getTransformMatrix();
            ViewportCameraFraming.Bounds bounds = new ViewportCameraFraming.Bounds();
            float minRadius = ViewportCameraFraming.MIN_RADIUS;
            boolean subjectGiven = false;

            if (request.parts() != null && !request.parts().isEmpty()) {
                ModelPartManager pm = requirePartManager(vp);
                for (String ref : request.parts()) {
                    addPart(bounds, pm, resolvePart(pm, ref), model);
                }
                subjectGiven = true;
            }
            if (request.bone() != null) {
                BoneStore bones = vp.getBoneStore();
                OMOFormat.BoneEntry bone = resolveBone(bones, request.bone());
                Vector3f head = bones.getJointWorldPosition(bone.id());
                Vector3f tail = bones.getTailWorldPosition(bone.id());
                if (head != null) bounds.add(model.transformPosition(head));
                if (tail != null) bounds.add(model.transformPosition(tail));
                minRadius = Math.max(minRadius, MIN_BONE_RADIUS);
                subjectGiven = true;
            }
            if (request.boxMin() != null) {
                bounds.add(request.boxMin()).add(request.boxMax());
                subjectGiven = true;
            }
            if (request.point() != null) {
                float r = request.radius() != null ? Math.abs(request.radius()) : DEFAULT_POINT_RADIUS;
                Vector3f p = request.point();
                bounds.add(p.x - r, p.y - r, p.z - r).add(p.x + r, p.y + r, p.z + r);
                subjectGiven = true;
            }
            if (!subjectGiven) {
                ModelPartManager pm = requirePartManager(vp);
                for (ModelPartDescriptor part : pm.getAllParts()) {
                    if (part.visible()) addPart(bounds, pm, part, model);
                }
            }
            if (bounds.isEmpty()) {
                throw new IllegalStateException("Nothing to frame: the model has no visible parts "
                        + "(pass parts, bone, box_min/box_max or point)");
            }

            ViewerCamera camera = arcball(vp);
            float yaw = request.yaw() != null ? request.yaw() : preset != null ? preset[0] : camera.getYaw();
            float pitch = request.pitch() != null ? request.pitch() : preset != null ? preset[1] : camera.getPitch();
            float radius = Math.max(bounds.radius(), minRadius);
            float aspect = vp.getViewportUIState().getAspectRatio();
            float distance = ViewportCameraFraming.fitDistance(radius, camera.getFov(), aspect, margin);

            camera.setTarget(bounds.center());
            camera.setYaw(yaw);
            camera.setPitch(pitch);
            camera.setDistance(distance);
            settle(vp, camera);

            Map<String, Object> subject = new LinkedHashMap<>();
            subject.put("center", vec(bounds.center()));
            subject.put("radius", McpNumbers.round4(radius));
            subject.put("min", vec(bounds.min()));
            subject.put("max", vec(bounds.max()));
            Map<String, Object> out = describe(vp, subject);
            out.put("margin", McpNumbers.round4(margin));
            List<String> clamped = new ArrayList<>();
            noteClamp(clamped, "distance", distance, camera.getDistance());
            noteClamp(clamped, "pitch", pitch, camera.getPitch());
            if (!clamped.isEmpty()) out.put("clamped", clamped);
            return out;
        }));
    }

    /** The camera's own default orbit (the Camera pane's Reset), applied at once. */
    public Map<String, Object> resetCamera() {
        return await(MainThreadExecutor.submit(() -> {
            ViewportController vp = requireViewport();
            ViewerCamera camera = arcball(vp);
            camera.reset();
            // reset() only retargets the interpolation; one full-step update lands on it now.
            camera.update(1f);
            settle(vp, camera);
            return describe(vp, null);
        }));
    }

    // ===================== Gizmo =====================

    /**
     * Show/hide the transform gizmo and/or set its mode; null = keep. Mirrors the UI: in
     * auto-show mode an explicit show/hide is a manual override (like Ctrl+T) that the next
     * selection change clears.
     */
    public Map<String, Object> gizmo(Boolean visible, String mode, Boolean uniformScale) {
        GizmoState.Mode parsedMode = mode != null ? parseGizmoMode(mode) : null;
        return await(MainThreadExecutor.submit(() -> {
            ViewportController vp = requireViewport();
            if (parsedMode != null) vp.setGizmoMode(parsedMode);
            if (uniformScale != null) vp.setGizmoUniformScaling(uniformScale);
            GizmoState state = vp.getGizmoState();
            if (visible != null) {
                if (state.getDisplayMode() == GizmoDisplayMode.AUTO_SHOW_ON_SELECT) {
                    state.setManualOverrideActive(true);
                }
                vp.setGizmoEnabled(visible);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("visible", vp.isGizmoEnabled());
            out.put("mode", vp.getGizmoMode().name().toLowerCase(Locale.ROOT));
            out.put("uniform_scale", vp.getGizmoUniformScaling());
            out.put("space", "world");
            out.put("display_mode", state.getDisplayMode() == GizmoDisplayMode.AUTO_SHOW_ON_SELECT
                    ? "auto_show_on_select" : "manual_toggle");
            return out;
        }));
    }

    static GizmoState.Mode parseGizmoMode(String mode) {
        String m = mode.trim().toLowerCase(Locale.ROOT);
        return switch (m) {
            case "translate", "move" -> GizmoState.Mode.TRANSLATE;
            case "rotate" -> GizmoState.Mode.ROTATE;
            case "scale" -> GizmoState.Mode.SCALE;
            default -> throw McpErrors.invalidEnum("mode", mode, List.of("translate", "rotate", "scale"));
        };
    }

    // ===================== Internals =====================

    /** The viewport camera, switched to orbit mode (every tool here drives the orbit camera). */
    private static ViewerCamera arcball(ViewportController vp) {
        ViewerCamera camera = vp.getCamera();
        if (camera == null) throw new IllegalStateException("Viewport camera not available");
        if (camera.getCameraMode() != ViewerCamera.CameraMode.ARCBALL) {
            camera.setCameraMode(ViewerCamera.CameraMode.ARCBALL);
            vp.getViewportUIState().getCurrentCameraModeIndex().set(0);
        }
        return camera;
    }

    /** Rebuild the matrices now and show the new values in the Camera pane's sliders. */
    private static void settle(ViewportController vp, ViewerCamera camera) {
        camera.updateMatrices();
        ViewportUIState ui = vp.getViewportUIState();
        float yaw = camera.getYaw();
        ui.updateCameraState(camera.getDistance(), camera.getPitch(), yaw > 180f ? yaw - 360f : yaw,
                camera.getFov());
    }

    private static Map<String, Object> describe(ViewportController vp, Map<String, Object> subject) {
        ViewerCamera camera = vp.getCamera();
        if (camera == null) throw new IllegalStateException("Viewport camera not available");
        Vector3f eye = camera.getPosition();
        Vector3f target = camera.getTarget();
        Vector3f forward = new Vector3f(target).sub(eye);
        if (forward.lengthSquared() > 0f) forward.normalize();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", camera.getCameraMode() == ViewerCamera.CameraMode.ARCBALL ? "arcball" : "first_person");
        out.put("projection", "perspective");
        out.put("target", vec(target));
        out.put("distance", McpNumbers.round4(camera.getDistance()));
        out.put("yaw", McpNumbers.round4(camera.getYaw()));
        out.put("pitch", McpNumbers.round4(camera.getPitch()));
        out.put("fov", McpNumbers.round4(camera.getFov()));
        out.put("eye", vec(eye));
        out.put("forward", vec(forward));
        out.put("aspect", McpNumbers.round4(vp.getViewportUIState().getAspectRatio()));
        out.put("model_transform_identity",
                vp.getTransformState().getTransformMatrix().equals(new Matrix4f(), 1e-6f));
        if (subject != null) out.put("framed", subject);
        return out;
    }

    private static void addPart(ViewportCameraFraming.Bounds bounds, ModelPartManager pm,
                                ModelPartDescriptor part, Matrix4f model) {
        Matrix4f m = new Matrix4f(model).mul(pm.getEffectiveWorldMatrix(part.id()));
        PartMeshRebuilder.PartGeometry geo = pm.getPartGeometry(part.id());
        float[] v = geo != null ? geo.vertices() : null;
        if (v == null || v.length < 3) {
            bounds.add(m.transformPosition(new Vector3f()));
            return;
        }
        Vector3f p = new Vector3f();
        for (int i = 0; i + 2 < v.length; i += 3) {
            bounds.add(m.transformPosition(p.set(v[i], v[i + 1], v[i + 2])));
        }
    }

    private static ModelPartDescriptor resolvePart(ModelPartManager pm, String ref) {
        Optional<ModelPartDescriptor> p = pm.getPartById(ref).or(() -> pm.getPartByName(ref));
        return p.orElseThrow(() -> McpErrors.unknownEntity("part", ref,
                pm.getAllParts().stream().map(ModelPartDescriptor::name).toList(), "list_parts"));
    }

    private static OMOFormat.BoneEntry resolveBone(BoneStore bones, String ref) {
        if (bones == null) throw new IllegalStateException("Bone store not available");
        OMOFormat.BoneEntry b = bones.getById(ref);
        if (b == null) b = bones.getByName(ref);
        if (b == null) {
            throw McpErrors.unknownEntity("bone", ref,
                    bones.getBones().stream().map(OMOFormat.BoneEntry::name).toList(), "bone_list");
        }
        return b;
    }

    private ViewportController requireViewport() {
        ViewportController vp = editorServices != null ? editorServices.getViewport3D() : null;
        if (vp == null) throw new IllegalStateException("Viewport not initialized");
        return vp;
    }

    private static ModelPartManager requirePartManager(ViewportController vp) {
        ModelPartManager pm = vp.getPartManager();
        if (pm == null) throw new IllegalStateException("Part manager not available (no model loaded?)");
        return pm;
    }

    private static void noteClamp(List<String> clamped, String field, float requested, float actual) {
        if (Math.abs(requested - actual) > 1e-4f) clamped.add(field);
    }

    private static float[] vec(Vector3f v) {
        return new float[]{McpNumbers.round4(v.x), McpNumbers.round4(v.y), McpNumbers.round4(v.z)};
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("Viewport view operation timed out on main thread", e);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        }
    }
}
