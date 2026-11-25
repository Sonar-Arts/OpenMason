package com.openmason.engine.rig;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * OpenMason #161 bindings: {@link FirstPersonProjection} (own FOV independent of the world's, the depth slab in
 * front of a wall 0.1 ahead, the pull) and {@link HeadBobStabilizer} through JOML. The math is pinned by the native
 * {@code first_person} ctest.
 */
class FirstPersonProjectionTest {

    @BeforeEach
    void requireNative() {
        assumeTrue(CendaRig.isAvailable(), "native rig unavailable: " + CendaRig.unavailableReason());
    }

    private static float ndcZ(Matrix4f proj, Matrix4f view, Matrix4f pull, Vector3f world) {
        Vector4f p = new Vector4f(world, 1f);
        if (pull != null) pull.transform(p);
        view.transform(p);
        proj.transform(p);
        return p.z / p.w;
    }

    @Test
    void theFirstPersonFovIsIndependentAndTheArmsSitInFrontOfAWall() {
        Matrix4f view = new Matrix4f().rotateY(0.3f).translate(-4, -1.6f, -2);
        Vector3f eye = new Vector3f(4, 1.6f, 2);
        Vector3f forward = new Vector3f();
        view.positiveZ(forward).negate();  // world direction the camera looks along
        FirstPersonProjection fp = new FirstPersonProjection().fovY(65f).depthRange(0.01f, 2f).depthScale(0.5f).pull(0.2f);
        Matrix4f proj = new Matrix4f(), pull = new Matrix4f();
        for (float worldFov : new float[]{60f, 90f, 110f}) {
            Matrix4f world = new Matrix4f().perspective((float) Math.toRadians(worldFov), 16f / 9f, 0.05f, 1000f);
            fp.compute(view, world, proj, pull);
            assertEquals(1.0 / Math.tan(Math.toRadians(32.5)), proj.m11(), 1e-5, "FP FOV ignores the world FOV " + worldFov);
            assertEquals(world.m11() / world.m00(), proj.m11() / proj.m00(), 1e-5, "same aspect");
            float wall = ndcZ(world, view, null, new Vector3f(forward).mul(0.1f).add(eye));
            float prev = -2f;
            for (float d : new float[]{0.01f, 0.1f, 0.5f, 1f, 2f}) {
                float z = ndcZ(proj, view, pull, new Vector3f(forward).mul(d).add(eye));
                assertTrue(z > prev && z < wall, "depth " + d + ": " + z + " (wall " + wall + ")");
                prev = z;
            }
            assertEquals(-1f, ndcZ(proj, view, pull, new Vector3f(forward).mul(0.01f).add(eye)), 1e-4f);
        }
        Vector3f p = pull.transformPosition(new Vector3f(5, 2, 1));
        assertEquals(eye.x + 0.2f * (5 - eye.x), p.x, 1e-5f);
        assertEquals(eye.y + 0.2f * (2 - eye.y), p.y, 1e-5f);
        Matrix4f ortho = new Matrix4f().ortho(-1, 1, -1, 1, 0.1f, 10f);
        assertThrows(IllegalArgumentException.class, () -> fp.compute(view, ortho, proj, null));
    }

    @Test
    void theStabilizerPassesSmallBobAndCutsLargeBob() {
        try (HeadBobStabilizer s = new HeadBobStabilizer()) {
            Matrix4f base = new Matrix4f().translation(10, 0, -3).rotateY(1.1f);
            Vector3f out = new Vector3f();
            for (int i = 0; i < 120; i++) {
                Vector3f raw = base.transformPosition(new Vector3f(0, 1.6f + 0.01f * (float) Math.sin(i * 0.2), -0.1f));
                s.update(raw, base, 1 / 60f, out);
                assertEquals(0f, out.distance(raw), 1e-5f);
            }
            s.reset();
            float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
            for (int i = 0; i < 600; i++) {
                float y = 1.6f + 0.05f * (float) Math.sin(2 * Math.PI * 2 * i / 60.0);
                s.update(new Vector3f(0, y, 0), null, 1 / 60f, out);
                if (i >= 300) {
                    lo = Math.min(lo, out.y);
                    hi = Math.max(hi, out.y);
                }
            }
            assertTrue(hi - lo < 0.07f && hi - lo > 0.04f, "0.10 of bob -> " + (hi - lo));
        }
    }
}
