package com.openmason.engine.rendering.shadow;

import com.openmason.engine.rendering.RenderOrigin;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CascadeCalculator#updateRelative}: the cascade fit from the camera-relative (render-space) view
 * matrix. Its promises hold at ±2^40 exactly as at world zero — split distances, radii and texel sizes
 * are identical, every slice corner lands inside its volume, and a static world point moves only by
 * whole shadow-map texels as the camera pans sub-texel amounts AND as {@link RenderOrigin} steps (the
 * snap grid is anchored to absolute world coordinates in double). A staggered cascade rebased after an
 * origin step still maps world points where it did when it was fitted.
 */
class CascadeCalculatorRelativeTest {

    private static final float CASCADE_NEAR = 0.3f;
    private static final long FAR = 1L << 40;

    private final CascadeCalculator calculator = new CascadeCalculator();
    private final ShadowSettings settings = ShadowSettings.defaults();
    private final Vector3f sun = new Vector3f(0.4f, 0.8f, 0.2f).normalize();

    private static ShadowCascade[] fresh() {
        ShadowCascade[] cascades = new ShadowCascade[ShadowSettings.CASCADE_COUNT];
        for (int i = 0; i < cascades.length; i++) {
            cascades[i] = new ShadowCascade();
        }
        return cascades;
    }

    private static Matrix4f perspective() {
        return new Matrix4f().perspective((float) Math.toRadians(60.0), 16.0f / 9.0f, 0.1f, 1000.0f);
    }

    /** A camera at a double world position, rendered relative to {@code origin}. */
    private record Camera(Vector3d world, float yawDegrees, long ox, long oy, long oz) {

        static Camera at(double x, double y, double z, float yaw) {
            return new Camera(new Vector3d(x, y, z), yaw, RenderOrigin.snap(x), RenderOrigin.snap(y),
                RenderOrigin.snap(z));
        }

        Vector3f renderEye() {
            return new Vector3f((float) (world.x - ox), (float) (world.y - oy), (float) (world.z - oz));
        }

        Matrix4f renderView() {
            Vector3f eye = renderEye();
            float yaw = (float) Math.toRadians(yawDegrees);
            Vector3f forward = new Vector3f((float) Math.sin(yaw), -0.2f, -(float) Math.cos(yaw)).normalize();
            return new Matrix4f().lookAt(eye, new Vector3f(eye).add(forward), new Vector3f(0, 1, 0));
        }
    }

    private ShadowCascade[] fit(Camera cam) {
        ShadowCascade[] cascades = fresh();
        calculator.updateRelative(cascades, cam.renderView(), perspective(), sun, settings, null,
            cam.ox, cam.oy, cam.oz);
        return cascades;
    }

    /** Shadow-map texel coordinates (x, y) of a double world point through a cascade drawn at origin o. */
    private float[] texelOf(ShadowCascade c, double wx, double wy, double wz, long ox, long oy, long oz) {
        Vector4f clip = new Vector4f((float) (wx - ox), (float) (wy - oy), (float) (wz - oz), 1f)
            .mul(c.lightViewProjRender);
        int res = settings.resolution();
        return new float[]{(clip.x * 0.5f + 0.5f) * res, (clip.y * 0.5f + 0.5f) * res};
    }

    private static float frac(float v) {
        return v - (float) Math.floor(v);
    }

    /** Distance from a to b on the unit circle of fractions. */
    private static float fracDistance(float a, float b) {
        float d = Math.abs(frac(a) - frac(b));
        return Math.min(d, 1f - d);
    }

    @Test
    void atWorldZeroTheRelativeFitEqualsTheWorldFit() {
        Camera cam = new Camera(new Vector3d(12.25, 70.5, -31.75), 37, 0, 0, 0); // render space = world
        ShadowCascade[] relative = fit(cam);
        ShadowCascade[] world = fresh();
        @SuppressWarnings("deprecation")
        Runnable legacy = () -> calculator.update(world, cam.renderView(), perspective(), sun, settings, null);
        legacy.run();
        for (int i = 0; i < relative.length; i++) {
            world[i].rebaseToRenderSpace(0, 0, 0);
            assertEquals(world[i].splitFar, relative[i].splitFar);
            assertEquals(world[i].radius, relative[i].radius);
            assertTrue(world[i].lightViewProjRender.equals(relative[i].lightViewProjRender, 1e-5f),
                "cascade " + i + "\n" + world[i].lightViewProjRender + "\n" + relative[i].lightViewProjRender);
        }
    }

    @Test
    void splitsRadiiAndTexelsAreTheSameAtTwoToTheFortyAsAtZero() {
        ShadowCascade[] near = fit(Camera.at(0.3, 70.2, 0.7, 20));
        for (double base : new double[]{FAR, -FAR}) {
            ShadowCascade[] far = fit(Camera.at(base + 0.3, 70.2, -base + 0.7, 20));
            for (int i = 0; i < near.length; i++) {
                assertEquals(near[i].splitFar, far[i].splitFar, "split " + i);
                assertEquals(near[i].radius, far[i].radius, "radius " + i);
                assertEquals(near[i].texelWorldSize, far[i].texelWorldSize, "texel " + i);
                assertEquals(base + 0.3, far[i].centerWorldExact.x, far[i].radius, "center follows the camera");
            }
        }
    }

    @Test
    void everySliceCornerLandsInsideItsVolumeFarFromZero() {
        for (double base : new double[]{0, FAR, -FAR}) {
            Camera cam = Camera.at(base + 120.5, 71.2, -base - 48.9, 37);
            ShadowCascade[] cascades = fit(cam);
            Matrix4f proj = perspective();
            Matrix4f cameraToRender = cam.renderView().invert(new Matrix4f());
            float tanHalfFovY = 1.0f / proj.m11();
            float aspect = proj.m11() / proj.m00();
            float ndcSlack = 2.0f * (2.0f / settings.resolution());
            for (int i = 0; i < cascades.length; i++) {
                float nearD = i == 0 ? CASCADE_NEAR : settings.splitFar(i - 1);
                for (float depth : new float[]{nearD, settings.splitFar(i)}) {
                    float halfH = depth * tanHalfFovY;
                    float halfW = halfH * aspect;
                    for (int sx = -1; sx <= 1; sx += 2) {
                        for (int sy = -1; sy <= 1; sy += 2) {
                            Vector3f corner = cameraToRender.transformPosition(
                                new Vector3f(sx * halfW, sy * halfH, -depth));
                            Vector4f clip = new Vector4f(corner, 1f).mul(cascades[i].lightViewProjRender);
                            String what = "base " + base + " cascade " + i;
                            assertTrue(Math.abs(clip.x) <= 1f + ndcSlack, what + " X " + clip.x);
                            assertTrue(Math.abs(clip.y) <= 1f + ndcSlack, what + " Y " + clip.y);
                            assertTrue(Math.abs(clip.z) <= 1f + 1e-3f, what + " Z " + clip.z);
                        }
                    }
                }
            }
        }
    }

    @Test
    void panningAndOriginStepsMoveAStaticPointByWholeTexelsAtTwoToTheForty() {
        for (double base : new double[]{FAR, -FAR}) {
            double px = base + 10.0, py = 68.0, pz = -base - 8.0;   // a static world point near the camera
            float[][] first = null;
            int originSteps = 0;
            long lastOx = Long.MIN_VALUE;
            for (int frame = 0; frame < 120; frame++) {
                // Sub-texel pans that cross several 64-block RenderOrigin cells.
                Camera cam = Camera.at(base + frame * 1.37, 70.0 + frame * 0.011, -base - frame * 0.53, 15);
                if (lastOx != Long.MIN_VALUE && cam.ox != lastOx) {
                    originSteps++;
                }
                lastOx = cam.ox;
                ShadowCascade[] cascades = fit(cam);
                float[][] texels = new float[cascades.length][];
                for (int i = 0; i < cascades.length; i++) {
                    texels[i] = texelOf(cascades[i], px, py, pz, cam.ox, cam.oy, cam.oz);
                }
                if (first == null) {
                    first = texels;
                    continue;
                }
                for (int i = 0; i < cascades.length; i++) {
                    assertTrue(fracDistance(first[i][0], texels[i][0]) < 0.005f,
                        "base " + base + " frame " + frame + " cascade " + i + " X slid by a fractional texel: "
                            + first[i][0] + " -> " + texels[i][0]);
                    assertTrue(fracDistance(first[i][1], texels[i][1]) < 0.005f,
                        "base " + base + " frame " + frame + " cascade " + i + " Y slid by a fractional texel: "
                            + first[i][1] + " -> " + texels[i][1]);
                }
            }
            assertTrue(originSteps >= 2, "the pan must cross RenderOrigin steps: " + originSteps);
        }
    }

    @Test
    void aStaggeredCascadeRebasedAfterAnOriginStepStillMapsTheWorldTheSameWay() {
        Camera before = Camera.at(FAR + 63.5, 70, -FAR + 1.5, 0);
        ShadowCascade[] cascades = fit(before);
        double px = FAR + 70.25, py = 66.0, pz = -FAR - 3.5;
        float[][] fitted = new float[cascades.length][];
        for (int i = 0; i < cascades.length; i++) {
            fitted[i] = texelOf(cascades[i], px, py, pz, before.ox, before.oy, before.oz);
        }
        Camera after = Camera.at(FAR + 64.5, 70, -FAR + 1.5, 0);    // one block on: the origin steps by 64
        assertEquals(before.ox + 64, after.ox);
        calculator.updateRelative(cascades, after.renderView(), perspective(), sun, settings,
            new boolean[]{true, false, false}, after.ox, after.oy, after.oz);
        for (int i = 1; i < cascades.length; i++) {
            cascades[i].rebaseToRenderSpace(after.ox, after.oy, after.oz);   // the staggered ones
            float[] now = texelOf(cascades[i], px, py, pz, after.ox, after.oy, after.oz);
            assertEquals(fitted[i][0], now[0], 1e-3f, "cascade " + i + " X");
            assertEquals(fitted[i][1], now[1], 1e-3f, "cascade " + i + " Y");
            assertEquals((float) (cascades[i].centerWorldExact.x - after.ox), cascades[i].centerRender.x, 1e-4f);
        }
    }

    @Test
    void lightResidueIsExactModuloTheTexelAtAnyInt64Origin() {
        java.util.Random rnd = new java.util.Random(3);
        for (int i = 0; i < 500; i++) {
            float a = rnd.nextFloat() * 2 - 1, b = rnd.nextFloat() * 2 - 1, c = rnd.nextFloat() * 2 - 1;
            long ox = rnd.nextLong() >> rnd.nextInt(24), oy = rnd.nextLong() >> rnd.nextInt(24);
            long oz = i % 7 == 0 ? Long.MIN_VALUE + rnd.nextInt(100) : rnd.nextLong() >> rnd.nextInt(24);
            double t = (2.0f * (1 + rnd.nextFloat() * 200)) / 2048;
            java.math.BigDecimal exact = new java.math.BigDecimal(a).multiply(java.math.BigDecimal.valueOf(ox))
                .add(new java.math.BigDecimal(b).multiply(java.math.BigDecimal.valueOf(oy)))
                .add(new java.math.BigDecimal(c).multiply(java.math.BigDecimal.valueOf(oz)))
                .remainder(new java.math.BigDecimal(t));
            double got = CascadeCalculator.lightResidue(a, b, c, ox, oy, oz, t);
            double diff = (got - exact.doubleValue()) / t;
            diff -= Math.rint(diff);   // congruence mod t is all that matters
            assertEquals(0.0, diff, 1e-7, "case " + i);
        }
    }
}
