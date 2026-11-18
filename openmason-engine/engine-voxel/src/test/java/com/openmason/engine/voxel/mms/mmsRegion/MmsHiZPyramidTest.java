package com.openmason.engine.voxel.mms.mmsRegion;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Hi-Z pyramid's CPU twin (the layout and verdict {@code culling.CEARL} implements, pinned against
 * the GPU in {@link MmsHiZGlTest}): level layout, max reduction over odd edges, the occlusion test's
 * conservativeness against brute force, and the two-phase bookkeeping rules.
 */
class MmsHiZPyramidTest {

    @Test
    void layoutHalvesWithCeilDownToOneTexel() {
        assertEquals(1, MmsHiZPyramid.levelCount(1, 1));
        assertEquals(1, MmsHiZPyramid.levelCount(2, 2));
        assertEquals(2, MmsHiZPyramid.levelCount(3, 2));
        int w = 1920, h = 1080;
        int levels = MmsHiZPyramid.levelCount(w, h);
        assertEquals(1, MmsHiZPyramid.levelEdge(w, levels - 1));
        assertEquals(1, MmsHiZPyramid.levelEdge(h, levels - 1));
        assertEquals(960, MmsHiZPyramid.levelEdge(w, 0));
        assertEquals(270, MmsHiZPyramid.levelEdge(h, 1));
        assertEquals(0L, MmsHiZPyramid.levelOffset(w, h, 0), "level 0 first: the raw depth never enters the buffer");
        assertEquals(960L * 540, MmsHiZPyramid.levelOffset(w, h, 1));
        long expected = 0;
        for (int k = 0; k < levels; k++) {
            assertEquals(expected, MmsHiZPyramid.levelOffset(w, h, k), "offset of level " + k);
            expected += (long) MmsHiZPyramid.levelEdge(w, k) * MmsHiZPyramid.levelEdge(h, k);
        }
        assertEquals(expected, MmsHiZPyramid.totalElements(w, h));
    }

    @Test
    void everyTexelHoldsTheFarthestDepthOfThePixelsItCovers() {
        Random rnd = new Random(7);
        int w = 37, h = 23;
        float[] depth = new float[w * h];
        for (int i = 0; i < depth.length; i++) {
            depth[i] = rnd.nextFloat();
        }
        float[] pyr = MmsHiZPyramid.reduce(depth, w, h);
        for (int k = 0; k < MmsHiZPyramid.levelCount(w, h); k++) {
            int lw = MmsHiZPyramid.levelEdge(w, k);
            int lh = MmsHiZPyramid.levelEdge(h, k);
            long base = MmsHiZPyramid.levelOffset(w, h, k);
            int t = 1 << (k + 1);
            for (int ty = 0; ty < lh; ty++) {
                for (int tx = 0; tx < lw; tx++) {
                    float max = 0f;
                    for (int y = ty * t; y < Math.min(h, (ty + 1) * t); y++) {
                        for (int x = tx * t; x < Math.min(w, (tx + 1) * t); x++) {
                            max = Math.max(max, depth[y * w + x]);
                        }
                    }
                    assertEquals(max, pyr[(int) (base + (long) ty * lw + tx)], "level " + k + " texel " + tx + "," + ty);
                }
            }
        }
    }

    @Test
    void occlusionIsConservativeAgainstEveryPixelUnderTheBox() {
        Random rnd = new Random(42);
        int w = 160, h = 97;
        Matrix4f vp = new Matrix4f().perspective((float) Math.toRadians(70), (float) w / h, 0.1f, 300f)
            .lookAt(new Vector3f(0, 0, 0), new Vector3f(0, 0, -1), new Vector3f(0, 1, 0));
        int occludedCount = 0;
        int openCount = 0;
        for (int scene = 0; scene < 40; scene++) {
            // A depth image of a few walls at random distances (window depth of a plane at view distance d).
            float[] depth = new float[w * h];
            java.util.Arrays.fill(depth, 1f);
            for (int wall = 0; wall < 3; wall++) {
                float d = 3 + rnd.nextFloat() * 40;
                float dz = windowDepth(vp, d);
                int x0 = rnd.nextInt(w), x1 = Math.min(w, x0 + 10 + rnd.nextInt(w));
                int y0 = rnd.nextInt(h), y1 = Math.min(h, y0 + 10 + rnd.nextInt(h));
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        depth[y * w + x] = Math.min(depth[y * w + x], dz);
                    }
                }
            }
            float[] pyr = MmsHiZPyramid.reduce(depth, w, h);
            for (int b = 0; b < 50; b++) {
                float cz = -(2 + rnd.nextFloat() * 80);
                float cx = (rnd.nextFloat() - 0.5f) * -cz * 1.6f;
                float cy = (rnd.nextFloat() - 0.5f) * -cz;
                float s = 0.2f + rnd.nextFloat() * 4;
                boolean occ = MmsHiZPyramid.occluded(pyr, w, h, vp, 0, 0, 0,
                    cx - s, cy - s, cz - s, cx + s, cy + s, cz + s);
                if (!occ) {
                    openCount++;
                    continue;
                }
                occludedCount++;
                // Brute force: every pixel the box's screen rect touches is nearer than the box's nearest point.
                float[] rect = screenRect(vp, w, h, cx - s, cy - s, cz - s, cx + s, cy + s, cz + s);
                for (int y = (int) rect[1]; y <= (int) rect[3]; y++) {
                    for (int x = (int) rect[0]; x <= (int) rect[2]; x++) {
                        assertTrue(depth[y * w + x] < rect[4],
                            "scene " + scene + " box " + b + ": pixel " + x + "," + y + " shows the box");
                    }
                }
            }
        }
        assertTrue(occludedCount > 50 && openCount > 50, occludedCount + " occluded / " + openCount + " open");
    }

    @Test
    void boxesCrossingTheCameraPlaneAreNeverOccluded() {
        int w = 64, h = 64;
        float[] depth = new float[w * h]; // everything at depth 0: the nearest possible occluder
        float[] pyr = MmsHiZPyramid.reduce(depth, w, h);
        Matrix4f vp = new Matrix4f().perspective(1.2f, 1f, 0.1f, 100f);
        assertFalse(MmsHiZPyramid.occluded(pyr, w, h, vp, 0, 0, 0, -1, -1, -5, 1, 1, 0.5f),
            "a corner behind the camera");
        assertTrue(MmsHiZPyramid.occluded(pyr, w, h, vp, 0, 0, 0, -1, -1, -6, 1, 1, -5),
            "fully in front, behind a depth-0 occluder");
    }

    @Test
    void twoPhaseRulesOnlyEverErrTowardDrawing() {
        assertEquals(2, MmsGpuCuller.earlyRule(5, 5), "matching history: phase 1 draws the visible set");
        assertEquals(1, MmsGpuCuller.earlyRule(-1, 5), "no history: phase 1 draws the whole frustum");
        assertEquals(1, MmsGpuCuller.earlyRule(4, 5), "stale list: no history");
        assertEquals(2, MmsGpuCuller.lateRule(9, 9, 5, 5, 2));
        assertEquals(1, MmsGpuCuller.lateRule(9, 9, 5, 5, 1));
        assertEquals(0, MmsGpuCuller.lateRule(8, 9, 5, 5, 2), "phase 1 skipped the region this frame");
        assertEquals(0, MmsGpuCuller.lateRule(9, 9, 5, 6, 2), "list changed between the phases");
    }

    private static float windowDepth(Matrix4f vp, float distance) {
        Vector4f c = new Vector4f(0, 0, -distance, 1).mul(vp);
        return c.z / c.w * 0.5f + 0.5f;
    }

    /** {x0, y0, x1, y1, nearestWindowDepth} of a box fully in front of the camera, clamped to the screen. */
    private static float[] screenRect(Matrix4f vp, int w, int h, float x0, float y0, float z0,
                                      float x1, float y1, float z1) {
        float nx0 = Float.MAX_VALUE, ny0 = Float.MAX_VALUE, nx1 = -Float.MAX_VALUE, ny1 = -Float.MAX_VALUE;
        float nz = Float.MAX_VALUE;
        for (int c = 0; c < 8; c++) {
            Vector4f p = new Vector4f((c & 1) != 0 ? x1 : x0, (c & 2) != 0 ? y1 : y0, (c & 4) != 0 ? z1 : z0, 1)
                .mul(vp);
            nx0 = Math.min(nx0, p.x / p.w);
            ny0 = Math.min(ny0, p.y / p.w);
            nx1 = Math.max(nx1, p.x / p.w);
            ny1 = Math.max(ny1, p.y / p.w);
            nz = Math.min(nz, p.z / p.w);
        }
        return new float[]{
            Math.clamp((nx0 * 0.5f + 0.5f) * w, 0, w - 1), Math.clamp((ny0 * 0.5f + 0.5f) * h, 0, h - 1),
            Math.clamp((nx1 * 0.5f + 0.5f) * w, 0, w - 1), Math.clamp((ny1 * 0.5f + 0.5f) * h, 0, h - 1),
            nz * 0.5f + 0.5f};
    }
}
