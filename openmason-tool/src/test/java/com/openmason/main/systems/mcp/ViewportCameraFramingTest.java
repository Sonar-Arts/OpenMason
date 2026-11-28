package com.openmason.main.systems.mcp;

import com.openmason.engine.rendering.viewer.camera.ArcballCameraController;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Headless checks of the {@code viewport_camera_*} math: orbit convention, presets and framing. */
class ViewportCameraFramingTest {

    private static final float EPS = 1e-4f;

    @Test
    void eyeMatchesTheArcballController() {
        ArcballCameraController arcball = new ArcballCameraController();
        Vector3f target = new Vector3f(0.3f, 1.2f, -0.4f);
        arcball.setTarget(target);
        arcball.setDistance(2.5f);
        arcball.setYaw(123f);
        arcball.setPitch(-17f);
        Vector3f expected = arcball.getPosition();
        Vector3f actual = ViewportCameraFraming.eye(target, 2.5f, 123f, -17f);
        assertEquals(expected.x, actual.x, EPS);
        assertEquals(expected.y, actual.y, EPS);
        assertEquals(expected.z, actual.z, EPS);
    }

    @Test
    void fromEyeInvertsEye() {
        Vector3f target = new Vector3f(1f, 2f, 3f);
        Vector3f eye = ViewportCameraFraming.eye(target, 4f, 250f, 33f);
        ViewportCameraFraming.Orbit orbit = ViewportCameraFraming.fromEye(eye, target);
        assertEquals(4f, orbit.distance(), EPS);
        assertEquals(250f, orbit.yaw(), 1e-3f);
        assertEquals(33f, orbit.pitch(), 1e-3f);
        assertThrows(IllegalArgumentException.class, () -> ViewportCameraFraming.fromEye(target, target));
    }

    @Test
    void frontPresetLooksAtTheMinusZFace() {
        // Models face -Z: the front view puts the camera on -Z looking toward +Z.
        float[] front = ViewportCameraFraming.presetAngles("front");
        Vector3f eye = ViewportCameraFraming.eye(new Vector3f(), 1f, front[0], front[1]);
        assertEquals(-1f, eye.z, EPS);
        assertEquals(0f, eye.x, EPS);
        // Right view = the model's right side (+X).
        float[] right = ViewportCameraFraming.presetAngles("right");
        assertEquals(1f, ViewportCameraFraming.eye(new Vector3f(), 1f, right[0], right[1]).x, EPS);
        float[] left = ViewportCameraFraming.presetAngles("left");
        assertEquals(-1f, ViewportCameraFraming.eye(new Vector3f(), 1f, left[0], left[1]).x, EPS);
        float[] top = ViewportCameraFraming.presetAngles("top");
        assertTrue(ViewportCameraFraming.eye(new Vector3f(), 1f, top[0], top[1]).y > 0.99f);
        float[] quarter = ViewportCameraFraming.presetAngles("three_quarter");
        Vector3f q = ViewportCameraFraming.eye(new Vector3f(), 1f, quarter[0], quarter[1]);
        assertTrue(q.x > 0 && q.y > 0 && q.z < 0, "three_quarter = front-right, above: " + q);
        IllegalArgumentException bad = assertThrows(IllegalArgumentException.class,
                () -> ViewportCameraFraming.presetAngles("fornt"));
        assertTrue(bad.getMessage().contains("front"), bad.getMessage());
    }

    @Test
    void framingABoxWithAPresetFitsItsSphereAtTheMargin() {
        // A 1 x 2 x 0.5 box: sphere radius = half the diagonal.
        ViewportCameraFraming.Bounds box = new ViewportCameraFraming.Bounds()
                .add(-0.5f, 0f, -0.25f).add(0.5f, 2f, 0.25f);
        float radius = box.radius();
        assertEquals((float) Math.sqrt(1 + 4 + 0.25) / 2f, radius, EPS);
        assertEquals(1f, box.center().y, EPS);

        float vfov = 45f;
        float margin = 1.15f;
        // Landscape: the vertical fov is the narrower one.
        float d = ViewportCameraFraming.fitDistance(radius, vfov, 16f / 9f, margin);
        assertEquals(radius * margin / Math.sin(Math.toRadians(vfov / 2)), d, 1e-3);
        // The sphere's angular radius is exactly half the fov, divided by the margin.
        double angular = Math.asin(radius / d);
        assertEquals(Math.toRadians(vfov / 2), angular * margin, 0.02);

        // Portrait: the horizontal fov is narrower, so the camera backs off further.
        float portrait = ViewportCameraFraming.fitDistance(radius, vfov, 0.5f, margin);
        assertTrue(portrait > d, "portrait " + portrait + " vs landscape " + d);
        double halfH = Math.atan(Math.tan(Math.toRadians(vfov / 2)) * 0.5);
        assertEquals(radius * margin / Math.sin(halfH), portrait, 1e-3);

        // Every box corner projects inside the frame from the front preset.
        float[] front = ViewportCameraFraming.presetAngles("front");
        Vector3f eye = ViewportCameraFraming.eye(box.center(), d, front[0], front[1]);
        Vector3f fwd = new Vector3f(box.center()).sub(eye).normalize();
        for (float x : new float[]{-0.5f, 0.5f}) {
            for (float y : new float[]{0f, 2f}) {
                for (float z : new float[]{-0.25f, 0.25f}) {
                    Vector3f toCorner = new Vector3f(x, y, z).sub(eye).normalize();
                    double off = Math.toDegrees(Math.acos(Math.clamp(toCorner.dot(fwd), -1f, 1f)));
                    assertTrue(off < vfov / 2, "corner off-axis " + off);
                }
            }
        }
    }

    @Test
    void marginDefaultsAndClamps() {
        assertEquals(ViewportCameraFraming.DEFAULT_MARGIN, ViewportCameraFraming.margin(null));
        assertEquals(ViewportCameraFraming.MIN_MARGIN, ViewportCameraFraming.margin(0f));
        assertEquals(ViewportCameraFraming.MAX_MARGIN, ViewportCameraFraming.margin(1e6f));
        assertEquals(2f, ViewportCameraFraming.margin(2f));
        // A point frames at the minimum radius instead of collapsing to distance 0.
        assertTrue(ViewportCameraFraming.fitDistance(0f, 45f, 1f, 1f) > 0f);
    }
}
