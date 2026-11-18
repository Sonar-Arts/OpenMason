package com.openmason.engine.rendering;

import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The render-space rebase: that it is algebraically a no-op, that it rebases
 * all three axes from a double camera, and that it is exact far beyond float
 * range (int64 block origins, ±2^40 cameras).
 *
 * <p>No GL here — {@link RenderOrigin#update} only touches buffers when meshes
 * are registered, and none are in a unit test.
 */
class RenderOriginTest {

    /** A camera 100k blocks out, where float32 spacing is ~0.0078 blocks. */
    private static final float FAR_X = 100_000f;
    private static final float FAR_Z = 100_000f;
    /** 2^40 blocks: float32 spacing there is 65536 blocks. */
    private static final long P40 = 1L << 40;

    @AfterEach
    void resetOrigin() {
        RenderOrigin.reset();
    }

    /** Snapping lands on the grid cell containing the camera on every axis, including negatives. */
    @Test
    void snapsAllThreeAxesToTheGridCellContainingTheCamera() {
        assertTrue(RenderOrigin.update(100_050.5, -10.25, -0.5));
        assertEquals(100_032L, RenderOrigin.blockX(), "floor(100050.5/64)*64");
        assertEquals(-64L, RenderOrigin.blockY(), "Y is rebased too, negatives floor downward");
        assertEquals(-64L, RenderOrigin.blockZ(), "-0.5 lies in the cell [-64, 0)");

        // Inside the same cell nothing moves — which is the point of snapping.
        assertFalse(RenderOrigin.update(100_050.0, -10.0, -1.0));
        assertFalse(RenderOrigin.update(100_095.99, -0.01, -63.0));
        assertTrue(RenderOrigin.update(100_095.99, 0.0, -63.0), "crossing a Y cell edge steps");
        assertEquals(0L, RenderOrigin.blockY());
        assertTrue(RenderOrigin.update(100_096.0, 0.0, -63.0), "crossing an X cell edge steps");
    }

    @Test
    void rebasesInYAboveAndBelowTheOldColumn() {
        RenderOrigin.update(0.0, 999_999.75, 0.0);
        assertEquals(999_936L, RenderOrigin.blockY());
        Vector3f out = RenderOrigin.relative(0.0, 1_000_064.5, 0.0, new Vector3f());
        assertEquals(128.5f, out.y, "exact: 1000064.5 - 999936");

        RenderOrigin.update(0.0, -384.0, 0.0);
        assertEquals(-384L, RenderOrigin.blockY(), "the cubic world's floor is on the grid");
        assertEquals(-1f, RenderOrigin.relativeY(-385L));
    }

    /** The column-world compatibility entry point keeps Y pinned at 0. */
    @Test
    @SuppressWarnings("deprecation")
    void columnCompatibilityUpdateLeavesYAlone() {
        RenderOrigin.update(0.0, 500.0, 0.0);
        RenderOrigin.update(FAR_X, FAR_Z);
        assertEquals(0L, RenderOrigin.blockY());
        Vector3f out = RenderOrigin.toRender(new Vector3f(FAR_X, 91.5f, FAR_Z), new Vector3f());
        assertEquals(91.5f, out.y);
        assertEquals((float) (FAR_X - RenderOrigin.blockX()), out.x);
    }

    /** relative(long) is an exact long subtraction: no rounding anywhere near the camera, at any magnitude. */
    @Test
    void relativeBlockPointsAreExactAtPlusMinus2To40() {
        for (long base : new long[]{P40, -P40, P40 * 4096, -P40 * 4096}) {
            RenderOrigin.update((double) base + 10.5, (double) -base + 3.25, (double) base - 7.75);
            long ox = RenderOrigin.blockX(), oy = RenderOrigin.blockY(), oz = RenderOrigin.blockZ();
            assertEquals(0, ox % RenderOrigin.GRID);
            assertEquals(0, oy % RenderOrigin.GRID);
            assertEquals(0, oz % RenderOrigin.GRID);
            for (int d = -300; d <= 300; d += 37) {
                Vector3f r = RenderOrigin.relative(ox + d, oy - d, oz + 2L * d, new Vector3f());
                assertEquals((float) d, r.x, "x at base " + base);
                assertEquals((float) -d, r.y, "y at base " + base);
                assertEquals((float) (2 * d), r.z, "z at base " + base);
            }
        }
    }

    /**
     * A double camera at ±2^40 + 0.3: an absolute float can't hold even the
     * integer part (65536-block spacing), the rebased float keeps the fraction
     * to double precision (2^40 double spacing is 2^-12 blocks).
     */
    @Test
    void doublePositionsKeepTheirFractionAtPlusMinus2To40() {
        for (double sign : new double[]{1, -1}) {
            double camera = sign * P40 + 0.3;
            RenderOrigin.update(camera, camera, camera);
            double exact = camera - RenderOrigin.blockX();   // exact: both ~2^40, difference < 64
            float rebased = RenderOrigin.relativeX(camera);
            assertEquals(exact, rebased, 1e-5, "rebased fraction survives at " + camera);
            assertTrue(exact >= 0 && exact < RenderOrigin.GRID);
            assertTrue(Math.ulp((float) camera) > 1000,
                "sanity: absolute float32 at 2^40 has a " + Math.ulp((float) camera) + "-block spacing");
            assertEquals(exact, RenderOrigin.relativeY(camera), 1e-5);
            assertEquals(exact, RenderOrigin.relativeZ(camera), 1e-5);
        }
    }

    /** World → render → world is exact in double, so no Java-side consumer loses anything. */
    @Test
    void worldRoundTripIsExact() {
        RenderOrigin.update(FAR_X, 300.0, FAR_Z);
        Vector3f world = new Vector3f(FAR_X + 13.25f, 64f, FAR_Z - 7.5f);
        Vector3f back = RenderOrigin.toWorld(RenderOrigin.toRender(world, new Vector3f()), new Vector3f());
        assertEquals(world.x, back.x);
        assertEquals(world.y, back.y);
        assertEquals(world.z, back.z);

        RenderOrigin.update((double) P40, (double) -P40, 0.0);
        Vector3d far = new Vector3d(P40 + 13.25, -P40 + 2.5, 7.5);
        Vector3d farBack = RenderOrigin.toWorld(RenderOrigin.toRender(far, new Vector3f()), new Vector3d());
        assertEquals(far.x, farBack.x);
        assertEquals(far.y, farBack.y);
        assertEquals(far.z, farBack.z);
    }

    /** Registered origin buffers bake to the exact camera-relative origin (long + fraction). */
    @Test
    void bakedOriginsAreExactCameraRelative() {
        RenderOrigin.update((double) -P40 + 100.0, 700.0, (double) P40 * 8 - 5.0);
        float[] out = RenderOrigin.baked(-P40 + 256, 768, P40 * 8 - 256, 0, 0, 0, -1f, new float[4]);
        assertEquals(256f - 64f, out[0]);
        assertEquals(768f - 640f, out[1]);
        assertEquals(-256f + 64f, out[2]);
        assertEquals(-1f, out[3]);
        out = RenderOrigin.baked(0, 0, 0, 0.5, 0.25, 0.125, 1f, new float[4]);
        assertEquals((float) (P40 - 64 + 0.5), out[0], "far from zero the float rounds once, sanely");
        assertEquals(-640f + 0.25f, out[1]);
    }

    /**
     * The algebra: {@code (M · T(+origin)) · (p - origin)} is the same transform
     * as {@code M · p}, now with a non-zero Y origin.
     */
    @Test
    void rebasingAMatrixIsANoOpOnTheResult() {
        RenderOrigin.update(FAR_X, 200.0, FAR_Z);
        Matrix4f worldConsumer = new Matrix4f()
                .perspective((float) Math.toRadians(70), 16f / 9f, 0.1f, 1000f)
                .lookAt(new Vector3f(FAR_X, 210f, FAR_Z), new Vector3f(FAR_X + 1f, 210f, FAR_Z), new Vector3f(0, 1, 0));

        Vector3f world = new Vector3f(FAR_X + 5f, 208f, FAR_Z + 2f);
        Vector3f render = RenderOrigin.toRender(world, new Vector3f());
        assertEquals(208f - 192f, render.y);

        Vector3f viaWorld = worldConsumer.transformPosition(new Vector3f(world));
        Vector3f viaRender = RenderOrigin
                .acceptRenderSpace(new Matrix4f(worldConsumer))
                .transformPosition(new Vector3f(render));
        assertEquals(viaWorld.x, viaRender.x, 1e-3f);
        assertEquals(viaWorld.y, viaRender.y, 1e-3f);
        assertEquals(viaWorld.z, viaRender.z, 1e-3f);
    }

    /** modelAt takes double world positions and lands them camera-relative. */
    @Test
    void modelAtIsCameraRelative() {
        RenderOrigin.update((double) P40, 64.0, (double) -P40);
        Vector3f t = RenderOrigin.modelAt(P40 + 1.5, 70.0, -P40 - 2.0).getTranslation(new Vector3f());
        assertEquals(1.5f, t.x);
        assertEquals(6f, t.y);
        assertEquals(-2f, t.z);
    }

    /**
     * The terrain half of the original bug: the camera position is an arbitrary
     * fraction, and absolute float coordinates snap the whole scene to a
     * lattice that grows with distance from spawn.
     */
    @Test
    void arbitraryFractionsSurviveTheRebaseAndNotAbsoluteCoordinates() {
        double exact = 100_000.3;
        RenderOrigin.update(exact, 0.0, 0.0);

        float absolute = (float) exact;
        double absoluteError = Math.abs(absolute - exact);

        float rebased = RenderOrigin.relativeX(exact);
        double rebasedError = Math.abs(rebased - (exact - RenderOrigin.blockX()));

        assertTrue(absoluteError > 3e-3,
                "expected ~1/128 block of quantization at 100k, got " + absoluteError);
        assertTrue(rebasedError < 1e-6,
                "expected the rebase to recover sub-micron precision, got " + rebasedError);
    }

    /**
     * The mob half of the bug: derivatives of the interpolated position amplify
     * coordinate error by the reciprocal of the per-pixel delta.
     */
    @Test
    void perPixelDerivativeSurvivesTheRebase() {
        float mobX = 10_000f;
        RenderOrigin.update(mobX, 0.0, 0.0);
        float localTranslate = RenderOrigin.relativeX((double) mobX);

        float[][] cases = {{0.004f, 0.02f}, {0.0012f, 0.15f}, {0.0006f, 0.60f}};
        for (float[] c : cases) {
            float delta = c[0];
            float absoluteDerivative = (mobX + delta) - (mobX + 0f);
            float renderDerivative = (localTranslate + delta) - (localTranslate + 0f);

            float absoluteError = Math.abs(absoluteDerivative - delta) / delta;
            float renderError = Math.abs(renderDerivative - delta) / delta;

            assertTrue(absoluteError > c[1],
                    "delta " + delta + ": expected absolute coordinates to mangle the derivative");
            assertTrue(renderError < 0.01f,
                    "delta " + delta + ": expected the rebase to preserve the derivative, got " + renderError);
        }
    }
}
