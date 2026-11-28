package com.openmason.main.systems.mcp;

import com.openmason.api.ServiceRegistry;
import com.openmason.engine.modeling.parts.ModelPartManager;
import com.openmason.engine.modeling.parts.PartShapeFactory;
import com.openmason.engine.modeling.parts.PartTransform;
import com.openmason.engine.rendering.viewer.camera.CameraMath;
import com.openmason.engine.rendering.viewer.camera.ViewerCamera;
import com.openmason.main.systems.ViewportController;
import com.openmason.main.systems.services.EditorServices;
import com.openmason.main.systems.threading.MainThreadExecutor;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code viewport_camera_*} / {@code viewport_gizmo} service against a real (GL-less)
 * {@link ViewportController}: camera changes snap, clamp like the UI, frame real part geometry,
 * and nothing lands in the model's undo history.
 */
class ViewportViewServiceTest {

    private static final float EPS = 1e-3f;

    private ViewportController viewport;
    private ViewportViewService service;

    @BeforeEach
    void setUp() {
        MainThreadExecutor.bindToCurrentThread();
        viewport = new ViewportController();
        viewport.getViewportUIState().setDimensions(1600, 900);
        ServiceRegistry registry = new ServiceRegistry();
        registry.put(ViewportController.class, viewport);
        service = new ViewportViewService(new EditorServices(registry));
    }

    @Test
    void setSnapsAtOnceAndReportsTheEye() {
        Map<String, Object> out = service.setCamera(new ViewportViewService.CameraChange(
                new Vector3f(1, 2, 3), 2f, 30f, 10f, 50f, null));
        ViewerCamera camera = viewport.getCamera();
        // Current values, not just interpolation targets: the next rendered frame is the new view.
        assertEquals(2f, camera.getDistance(), EPS);
        assertEquals(30f, camera.getYaw(), EPS);
        assertEquals(10f, camera.getPitch(), EPS);
        assertEquals(50f, camera.getFov(), EPS);
        Vector3f eye = ViewportCameraFraming.eye(new Vector3f(1, 2, 3), 2f, 30f, 10f);
        assertArrayEquals(new float[]{eye.x, eye.y, eye.z}, (float[]) out.get("eye"), EPS);
        assertEquals(eye.x, camera.getPosition().x, EPS);
        assertEquals("arcball", out.get("mode"));
        assertEquals(true, out.get("model_transform_identity"));
        // Camera pane sliders follow.
        assertEquals(2f, viewport.getViewportUIState().getCameraDistance().get(), EPS);
    }

    @Test
    void setClampsLikeTheUiAndSaysSo() {
        Map<String, Object> out = service.setCamera(new ViewportViewService.CameraChange(
                null, 500f, null, 120f, 5f, null));
        assertEquals(List.of("distance", "pitch", "fov"), out.get("clamped"));
        assertEquals(100f, (float) out.get("distance"), EPS);
        assertEquals(89f, (float) out.get("pitch"), EPS);
        assertEquals(10f, (float) out.get("fov"), EPS);
    }

    @Test
    void eyeAimsAtTheTargetAndExcludesOrbitFields() {
        service.setCamera(new ViewportViewService.CameraChange(
                new Vector3f(0, 1, 0), null, null, null, null, new Vector3f(0, 1, -3)));
        ViewerCamera camera = viewport.getCamera();
        assertEquals(3f, camera.getDistance(), EPS);
        assertEquals(180f, camera.getYaw(), EPS);   // eye on -Z = the model's front
        assertEquals(0f, camera.getPitch(), EPS);
        assertThrows(IllegalArgumentException.class, () -> service.setCamera(new ViewportViewService.CameraChange(
                null, 2f, null, null, null, new Vector3f(1, 1, 1))));
    }

    @Test
    void frameFitsAPartFromAPreset() {
        ModelPartManager pm = viewport.getPartManager();
        var part = pm.addPart("arm", PartShapeFactory.create(PartShapeFactory.Shape.CUBE, "arm",
                new Vector3f(0.25f, 0.75f, 0.25f)));
        pm.setPartTransform(part.id(), new PartTransform(new Vector3f(), new Vector3f(2, 1, 0),
                new Vector3f(), new Vector3f(1, 1, 1)));
        boolean couldUndo = viewport.getCommandHistory().canUndo();

        Map<String, Object> out = service.frame(new ViewportViewService.FrameRequest(
                List.of("arm"), null, null, null, null, null, "right", null, null, 1.5f));

        ViewerCamera camera = viewport.getCamera();
        @SuppressWarnings("unchecked")
        Map<String, Object> framed = (Map<String, Object>) out.get("framed");
        float[] center = (float[]) framed.get("center");
        assertEquals(2f, center[0], EPS);
        assertEquals(camera.getTarget().x, center[0], EPS);
        assertEquals(90f, camera.getYaw(), EPS);
        assertEquals(0f, camera.getPitch(), EPS);
        float radius = (float) framed.get("radius");
        float expected = ViewportCameraFraming.fitDistance(radius, camera.getFov(), 16f / 9f, 1.5f);
        assertEquals(expected, camera.getDistance(), EPS);
        assertEquals(couldUndo, viewport.getCommandHistory().canUndo(), "view changes are not edits");

        // A box frames without any model; a half-given box is refused.
        service.frame(new ViewportViewService.FrameRequest(null, null, new Vector3f(-1, -1, -1),
                new Vector3f(1, 1, 1), null, null, "top", null, null, null));
        assertEquals(89f, camera.getPitch(), EPS);
        assertThrows(IllegalArgumentException.class, () -> service.frame(new ViewportViewService.FrameRequest(
                null, null, new Vector3f(), null, null, null, null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> service.frame(new ViewportViewService.FrameRequest(
                List.of("leg"), null, null, null, null, null, null, null, null, null)));
    }

    @Test
    void resetLandsOnTheDefaultOrbitAtOnce() {
        service.setCamera(new ViewportViewService.CameraChange(new Vector3f(5, 5, 5), 3f, 10f, 10f, null, null));
        Map<String, Object> out = service.resetCamera();
        ViewerCamera fresh = new ViewerCamera();
        assertEquals(fresh.getDistance(), (float) out.get("distance"), EPS);
        // Same direction; yaw is reported normalized to [0, 360).
        assertEquals(CameraMath.normalizeAngle(fresh.getYaw()), (float) out.get("yaw"), EPS);
        assertEquals(fresh.getPitch(), (float) out.get("pitch"), EPS);
        assertArrayEquals(new float[]{0, 0, 0}, (float[]) out.get("target"), EPS);
    }

    @Test
    void gizmoHidesShowsAndSwitchesMode() {
        Map<String, Object> out = service.gizmo(false, "rotate", true);
        assertFalse(viewport.isGizmoEnabled());
        assertEquals(false, out.get("visible"));
        assertEquals("rotate", out.get("mode"));
        assertEquals(true, out.get("uniform_scale"));
        assertEquals("world", out.get("space"));

        out = service.gizmo(true, null, null);
        assertTrue(viewport.isGizmoEnabled());
        assertEquals("rotate", out.get("mode"), "omitted fields keep their value");
        assertThrows(IllegalArgumentException.class, () -> service.gizmo(null, "shear", null));
    }
}
