package com.openmason.engine.rendering.shadow;

import com.openmason.engine.rendering.RenderOrigin;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Computes per-cascade light-space matrices for cascaded sun shadows.
 *
 * <p>Each cascade fits a texel-snapped orthographic volume around the bounding
 * sphere of a camera-frustum slice. Bounding spheres (rather than tight boxes)
 * keep the ortho extents constant as the camera rotates, and snapping the sphere
 * center to shadow-map texel increments in light space stops shadow edges from
 * shimmering as the camera translates.
 *
 * <p><b>Render-space fitting</b> (cubic runtime, 2026-11-17): {@link #updateRelative} fits from the
 * camera-relative view matrix the frame draws with (the camera's {@link RenderOrigin} render position),
 * so every float in the fit stays small at any world coordinate. The texel snap is still anchored to the
 * ABSOLUTE world — the window center's world light-space position {@code R * centerRender + R * origin}
 * must be a whole number of texels, where {@code R * origin} enters only modulo the texel size, computed
 * exactly enough at any int64 origin ({@link #lightResidue}) — so the shadow volume moves in whole texels
 * both as the camera pans and when {@link RenderOrigin} steps, with no re-phasing anywhere.
 * The old world-space {@link #update} entry points are deprecated: their float world view matrix loses
 * the snap (and the fit) far from world zero.
 *
 * <p>Stateless apart from scratch vectors; render-thread only.
 */
public final class CascadeCalculator {

    private static final float CASCADE_NEAR = 0.3f;

    // Scratch objects — avoid per-frame allocation.
    private final Matrix4f cameraWorld = new Matrix4f();
    private final Vector3f corner = new Vector3f();
    private final Vector3f centerLight = new Vector3f();
    private final Vector3f lookTarget = new Vector3f();
    private final Vector3f up = new Vector3f();

    /**
     * Recomputes every cascade in {@code cascades}.
     *
     * @param cascades   output array, length {@link ShadowSettings#CASCADE_COUNT}
     * @param viewMatrix camera view matrix
     * @param projMatrix camera perspective projection (fov/aspect are extracted from it)
     * @param sunDirection normalized direction from the scene toward the sun
     * @param settings   shadow tunables
     * @deprecated fits in float world space (jitters far from world zero); use
     *             {@link #updateRelative(ShadowCascade[], Matrix4fc, Matrix4fc, Vector3fc, ShadowSettings, boolean[])}
     */
    @Deprecated
    public void update(ShadowCascade[] cascades, Matrix4f viewMatrix, Matrix4f projMatrix,
                       Vector3f sunDirection, ShadowSettings settings) {
        update(cascades, viewMatrix, projMatrix, sunDirection, settings, null);
    }

    /**
     * Recomputes the cascades selected by {@code updateMask} (null = all). Skipped
     * cascades keep their previous matrices, which stay consistent with the depth
     * already rendered into their map layer — callers that stagger cascade updates
     * across frames must also skip re-rendering the same cascades.
     *
     * @deprecated fits in float world space (jitters far from world zero); use
     *             {@link #updateRelative(ShadowCascade[], Matrix4fc, Matrix4fc, Vector3fc, ShadowSettings, boolean[])}
     */
    @Deprecated
    public void update(ShadowCascade[] cascades, Matrix4f viewMatrix, Matrix4f projMatrix,
                       Vector3f sunDirection, ShadowSettings settings, boolean[] updateMask) {
        // Extract fov/aspect from the projection: m11 = 1/tan(fovY/2), aspect = m11/m00.
        float tanHalfFovY = 1.0f / projMatrix.m11();
        float aspect = projMatrix.m11() / projMatrix.m00();

        viewMatrix.invert(cameraWorld);

        // Light "view" looks along the direction light travels (away from the sun).
        // Degenerate up vector when the sun is at zenith — fall back to +Z.
        lookTarget.set(sunDirection).negate();
        if (Math.abs(sunDirection.y) > 0.99f) {
            up.set(0, 0, 1);
        } else {
            up.set(0, 1, 0);
        }

        float texel;
        for (int i = 0; i < cascades.length; i++) {
            if (updateMask != null && !updateMask[i]) {
                continue;
            }
            ShadowCascade c = cascades[i];
            float near = i == 0 ? CASCADE_NEAR : settings.splitFar(i - 1);
            float far = settings.splitFar(i);
            c.splitFar = far;

            // Bounding sphere of the frustum slice: center on the view axis between
            // the two planes, radius to the far plane's corner. Computing it in view
            // space keeps the radius rotation-invariant.
            float farHalfH = far * tanHalfFovY;
            float farHalfW = farHalfH * aspect;
            float nearHalfH = near * tanHalfFovY;
            float nearHalfW = nearHalfH * aspect;

            // Optimal center depth along the axis minimizing max corner distance.
            float farCornerSq = farHalfW * farHalfW + farHalfH * farHalfH;
            float nearCornerSq = nearHalfW * nearHalfW + nearHalfH * nearHalfH;
            float centerZ = (far * far + farCornerSq - near * near - nearCornerSq)
                    / (2.0f * (far - near));
            centerZ = Math.max(near, Math.min(far, centerZ));
            float radius = (float) Math.sqrt(
                    (far - centerZ) * (far - centerZ) + farCornerSq);
            radius = Math.max(radius, (float) Math.sqrt(
                    (centerZ - near) * (centerZ - near) + nearCornerSq));
            c.radius = radius;

            // Slice center in world space: camera position + forward * centerZ.
            corner.set(0, 0, -centerZ);
            cameraWorld.transformPosition(corner, c.centerWorld);

            c.lightView.setLookAt(
                    0, 0, 0,
                    lookTarget.x, lookTarget.y, lookTarget.z,
                    up.x, up.y, up.z);

            // Snap the light-space center to texel increments so the ortho window
            // moves in whole texels — otherwise shadow edges crawl as the camera pans.
            texel = (2.0f * radius) / settings.resolution();
            c.texelWorldSize = texel;
            c.lightView.transformPosition(c.centerWorld, centerLight);
            float snappedX = (float) Math.floor(centerLight.x / texel) * texel;
            float snappedY = (float) Math.floor(centerLight.y / texel) * texel;

            // In light view space the scene lies along -Z; centerLight.z is negative
            // for points in front. Extend the near plane toward the sun by
            // casterBackup so off-screen casters still write depth.
            float zNear = -centerLight.z - radius - settings.casterBackup();
            float zFar = -centerLight.z + radius;

            c.lightProj.setOrtho(
                    snappedX - radius, snappedX + radius,
                    snappedY - radius, snappedY + radius,
                    zNear, zFar);
            c.lightProj.mul(c.lightView, c.lightViewProj);
            c.markWorldFitted();
        }
    }

    /**
     * Fits the cascades selected by {@code updateMask} (null = all) from the RENDER-SPACE camera view
     * matrix (built at the camera's position relative to the current {@link RenderOrigin}) and leaves
     * every refitted cascade's render-space matrices ready ({@link ShadowCascade#lightViewProjRender},
     * {@link ShadowCascade#lightViewRender}). Call {@link ShadowCascade#rebaseToRenderSpace()} on the
     * cascades skipped by the mask (or simply on all of them, it is exact) so a staggered cascade follows
     * an origin step.
     *
     * @param renderView   camera view matrix in render space (eye = camera render position)
     * @param projMatrix   camera perspective projection (fov/aspect are extracted from it)
     * @param sunDirection normalized direction from the scene toward the sun
     */
    public void updateRelative(ShadowCascade[] cascades, Matrix4fc renderView, Matrix4fc projMatrix,
                               Vector3fc sunDirection, ShadowSettings settings, boolean[] updateMask) {
        updateRelative(cascades, renderView, projMatrix, sunDirection, settings, updateMask,
            RenderOrigin.blockX(), RenderOrigin.blockY(), RenderOrigin.blockZ());
    }

    /**
     * {@link #updateRelative(ShadowCascade[], Matrix4fc, Matrix4fc, Vector3fc, ShadowSettings, boolean[])}
     * with the render origin given explicitly ({@code renderView} must be relative to it).
     */
    public void updateRelative(ShadowCascade[] cascades, Matrix4fc renderView, Matrix4fc projMatrix,
                               Vector3fc sunDirection, ShadowSettings settings, boolean[] updateMask,
                               long originX, long originY, long originZ) {
        float tanHalfFovY = 1.0f / projMatrix.m11();
        float aspect = projMatrix.m11() / projMatrix.m00();
        renderView.invert(cameraWorld); // camera -> render space

        lookTarget.set(sunDirection).negate();
        if (Math.abs(sunDirection.y()) > 0.99f) {
            up.set(0, 0, 1);
        } else {
            up.set(0, 1, 0);
        }

        for (int i = 0; i < cascades.length; i++) {
            if (updateMask != null && !updateMask[i]) {
                continue;
            }
            ShadowCascade c = cascades[i];
            float near = i == 0 ? CASCADE_NEAR : settings.splitFar(i - 1);
            float far = settings.splitFar(i);
            c.splitFar = far;

            float farHalfH = far * tanHalfFovY;
            float farHalfW = farHalfH * aspect;
            float nearHalfH = near * tanHalfFovY;
            float nearHalfW = nearHalfH * aspect;
            float farCornerSq = farHalfW * farHalfW + farHalfH * farHalfH;
            float nearCornerSq = nearHalfW * nearHalfW + nearHalfH * nearHalfH;
            float centerZ = (far * far + farCornerSq - near * near - nearCornerSq)
                    / (2.0f * (far - near));
            centerZ = Math.max(near, Math.min(far, centerZ));
            float radius = (float) Math.sqrt(
                    (far - centerZ) * (far - centerZ) + farCornerSq);
            radius = Math.max(radius, (float) Math.sqrt(
                    (centerZ - near) * (centerZ - near) + nearCornerSq));
            c.radius = radius;

            // Slice center in render space (small floats at any world position).
            corner.set(0, 0, -centerZ);
            cameraWorld.transformPosition(corner, c.centerRender);

            c.lightView.setLookAt(
                    0, 0, 0,
                    lookTarget.x, lookTarget.y, lookTarget.z,
                    up.x, up.y, up.z);

            float texel = (2.0f * radius) / settings.resolution();
            c.texelWorldSize = texel;
            c.lightView.transformPosition(c.centerRender, centerLight);

            // Snap on the ABSOLUTE world lattice: the window center's world light-space position
            // (render position + R * origin) must be a whole number of texels. Only R * origin MOD texel
            // matters, and it is computed exactly enough (~1e-9 texel at any int64 origin, see
            // lightResidue) - forming R * origin itself in double would cost ~0.02 texel at 2^40.
            Matrix4f r = c.lightView; // rotation only (eye at 0)
            double t = texel;
            double anchorX = lightResidue(r.m00(), r.m10(), r.m20(), originX, originY, originZ, t);
            double anchorY = lightResidue(r.m01(), r.m11(), r.m21(), originX, originY, originZ, t);
            double snappedX = Math.floor((centerLight.x + anchorX) / t) * t - anchorX;
            double snappedY = Math.floor((centerLight.y + anchorY) / t) * t - anchorY;

            float zNear = -centerLight.z - radius - settings.casterBackup();
            float zFar = -centerLight.z + radius;
            c.lightProj.setOrtho(
                    (float) snappedX - radius, (float) snappedX + radius,
                    (float) snappedY - radius, (float) snappedY + radius,
                    zNear, zFar);
            c.markRenderFitted(originX, originY, originZ);
        }
    }

    /**
     * {@code (a*ox + b*oy + c*oz) mod t} (sign of the result unspecified, magnitude below {@code t}) for
     * float coefficients and int64 coordinates, accurate to ~1e-9 {@code t}: each coordinate is split into
     * 20-bit chunks, and {@code (2^(20k) * coef) mod t} is stepped exactly ({@code x * 2^20} and {@code %}
     * are exact in IEEE double), so every product stays below {@code 2^20 t}. Package-private for tests.
     */
    static double lightResidue(float a, float b, float c, long ox, long oy, long oz, double t) {
        double r = axisResidue(a, ox, t) + axisResidue(b, oy, t) + axisResidue(c, oz, t);
        return r % t;
    }

    private static double axisResidue(double coef, long o, double t) {
        double q = coef % t;
        double acc = 0;
        long rest = o;
        for (int k = 0; k < 3; k++) {
            acc += (double) (rest & 0xFFFFF) * q;   // chunk in [0, 2^20)
            rest >>= 20;                            // floor division: the chunks of a negative o stay positive
            q = (q * 1048576.0) % t;
        }
        acc += (double) rest * q;                   // top chunk, signed, |rest| < 2^4
        return acc % t;
    }
}
