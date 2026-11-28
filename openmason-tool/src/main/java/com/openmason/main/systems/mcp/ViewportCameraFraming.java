package com.openmason.main.systems.mcp;

import com.openmason.engine.rendering.viewer.camera.CameraMath;
import org.joml.Vector3f;

import java.util.List;
import java.util.Locale;

/**
 * GL-free camera math behind the {@code viewport_camera_*} MCP tools: the orbit camera's
 * spherical convention (same formula as {@code ArcballCameraController}), eye-to-orbit
 * conversion, view presets and "fit a bounding sphere in the frustum" framing.
 *
 * <p>Orbit convention: {@code eye = target + distance * (cos(pitch) sin(yaw), sin(pitch),
 * cos(pitch) cos(yaw))}, degrees, Y up. Yaw 0 puts the camera on +Z looking toward -Z;
 * Stonebreak models face -Z (right hand +X), so their FRONT is seen from yaw 180.
 */
public final class ViewportCameraFraming {

    /** Margin used when the caller gives none: the subject fills ~87% of the shorter side. */
    public static final float DEFAULT_MARGIN = 1.15f;
    public static final float MIN_MARGIN = 0.1f;
    public static final float MAX_MARGIN = 50f;
    /** Smallest framing radius, so a point or a zero-length bone still gets a sane close-up. */
    public static final float MIN_RADIUS = 0.01f;

    /** View presets, named by the side of the MODEL the camera sees (model faces -Z, right hand +X). */
    public static final List<String> PRESETS =
            List.of("front", "back", "left", "right", "top", "bottom", "three_quarter");

    private ViewportCameraFraming() {
    }

    /** An orbit camera state: pivot, distance and angles in degrees. */
    public record Orbit(Vector3f target, float distance, float yaw, float pitch) {
    }

    /** Eye position of an orbit, exactly as the arc-ball controller computes it. */
    public static Vector3f eye(Vector3f target, float distance, float yawDeg, float pitchDeg) {
        double yaw = Math.toRadians(yawDeg);
        double pitch = Math.toRadians(pitchDeg);
        double cosPitch = Math.cos(pitch);
        return new Vector3f(
                (float) (target.x + distance * cosPitch * Math.sin(yaw)),
                (float) (target.y + distance * Math.sin(pitch)),
                (float) (target.z + distance * cosPitch * Math.cos(yaw)));
    }

    /**
     * The orbit that puts the camera at {@code eye} looking at {@code target} (unclamped; the
     * camera clamps distance and pitch when it is applied).
     */
    public static Orbit fromEye(Vector3f eye, Vector3f target) {
        Vector3f off = new Vector3f(eye).sub(target);
        float d = off.length();
        if (!(d > 1e-6f)) {
            throw new IllegalArgumentException("eye and target are the same point; give an eye away from the target");
        }
        float pitch = (float) Math.toDegrees(Math.asin(Math.clamp(off.y / d, -1f, 1f)));
        float yaw = CameraMath.normalizeAngle((float) Math.toDegrees(Math.atan2(off.x, off.z)));
        return new Orbit(new Vector3f(target), d, yaw, pitch);
    }

    /**
     * {@code {yaw, pitch}} in degrees for a view preset, or throws a teaching error. Pitches stay
     * inside the camera's +-89 limit, so top/bottom look almost straight down/up.
     */
    public static float[] presetAngles(String preset) {
        String p = preset == null ? "" : preset.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (p) {
            case "front" -> new float[]{180f, 0f};
            case "back" -> new float[]{0f, 0f};
            case "right" -> new float[]{90f, 0f};
            case "left" -> new float[]{270f, 0f};
            case "top" -> new float[]{180f, CameraMath.MAX_PITCH};
            case "bottom" -> new float[]{180f, CameraMath.MIN_PITCH};
            case "three_quarter", "threequarter", "3/4" -> new float[]{135f, 25f};
            default -> throw McpErrors.invalidEnum("view", preset, PRESETS);
        };
    }

    /**
     * Orbit distance at which a sphere of {@code radius} fits the view: the sphere's angular
     * radius equals half the narrower field of view, scaled by {@code margin} (> 1 leaves room
     * around it, < 1 crops into it). Not clamped to the camera's limits.
     *
     * @param vfovDeg vertical field of view, degrees
     * @param aspect  width / height of the viewport
     */
    public static float fitDistance(float radius, float vfovDeg, float aspect, float margin) {
        double halfV = Math.toRadians(vfovDeg) * 0.5;
        double a = aspect > 0 && Float.isFinite(aspect) ? aspect : 1.0;
        double halfH = Math.atan(Math.tan(halfV) * a);
        double half = Math.min(halfV, halfH);
        float r = Math.max(radius, MIN_RADIUS);
        return (float) (r * margin / Math.sin(half));
    }

    /** Clamp a margin argument into its accepted range (default when null). */
    public static float margin(Float requested) {
        if (requested == null || !Float.isFinite(requested)) {
            return DEFAULT_MARGIN;
        }
        return Math.clamp(requested, MIN_MARGIN, MAX_MARGIN);
    }

    /** Axis-aligned bounds accumulated point by point; empty until the first point. */
    public static final class Bounds {
        private final Vector3f min = new Vector3f(Float.POSITIVE_INFINITY);
        private final Vector3f max = new Vector3f(Float.NEGATIVE_INFINITY);
        private boolean empty = true;

        public Bounds add(float x, float y, float z) {
            min.set(Math.min(min.x, x), Math.min(min.y, y), Math.min(min.z, z));
            max.set(Math.max(max.x, x), Math.max(max.y, y), Math.max(max.z, z));
            empty = false;
            return this;
        }

        public Bounds add(Vector3f p) {
            return add(p.x, p.y, p.z);
        }

        public boolean isEmpty() {
            return empty;
        }

        public Vector3f min() {
            return new Vector3f(min);
        }

        public Vector3f max() {
            return new Vector3f(max);
        }

        public Vector3f center() {
            return new Vector3f(min).add(max).mul(0.5f);
        }

        /** Radius of the bounding sphere around {@link #center()} (half the box diagonal). */
        public float radius() {
            return new Vector3f(max).sub(min).length() * 0.5f;
        }
    }
}
