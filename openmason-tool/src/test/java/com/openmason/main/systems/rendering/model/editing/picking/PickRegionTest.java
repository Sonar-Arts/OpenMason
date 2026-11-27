package com.openmason.main.systems.rendering.model.editing.picking;

import com.openmason.main.systems.rendering.model.editing.subrenders.HoverQueryGate;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenMason #175 F4, GL-free: the readback window's pixel addressing (viewport y down, GL rows up), the edge search
 * (nearest pixel centre within the threshold, ties to the lower id), the depth unprojection back to model space, and
 * the hover gate's retry / epoch rules without a GPU (epoch 0, gates behave as before).
 */
class PickRegionTest {

    private static PickRegion region(int w, int h, int originX, int originY) {
        PickRegion r = new PickRegion(16);
        r.valid = true;
        r.viewportWidth = w;
        r.viewportHeight = h;
        r.originX = originX;
        r.originY = originY;
        return r;
    }

    @Test
    void theMousePixelIsAddressedInGlRows() {
        PickRegion r = region(100, 80, 40, 30);
        // Mouse (45.5, 80 - 35.5) = GL pixel (45, 35) = window cell (5, 5)
        assertEquals(5 * 16 + 5, r.cell(45.5f, 80 - 35.5f, 0));
        assertEquals(-1, r.cell(39.5f, 40f, 0), "left of the window");
        assertEquals(-1, r.cell(41.5f, 80 - 35.5f, 2), "too close to the edge for a 2-pixel radius");
        assertEquals(1 * 16 + 3, r.cell(43.5f, 80 - 31.5f, 1));
        assertEquals(0f, r.offCentre(48f, 80 - 38f), 1e-6f);
    }

    @Test
    void theNearestEdgePixelWithinTheThresholdWins() {
        PickRegion r = region(100, 80, 40, 30);
        // Edge 7 two pixels right of the mouse pixel, edge 3 two pixels left: equal distance -> lower id
        r.edges[5 * 16 + 7] = 7 + 1;
        r.edges[5 * 16 + 3] = 3 + 1;
        float mx = 45.5f, my = 80 - 35.5f;
        assertEquals(3, r.nearestId(r.edges, mx, my, 3f));
        r.edges[6 * 16 + 5] = 9 + 1; // one pixel up: nearer
        assertEquals(9, r.nearestId(r.edges, mx, my, 3f));
        assertEquals(-1, r.nearestId(r.edges, mx, my, 0.5f), "nothing under the mouse itself");
    }

    @Test
    void depthUnprojectsToTheModelSpacePoint() {
        PickRegion r = region(200, 100, 0, 0);
        r.view.lookAt(0, 0, 5, 0, 0, 0, 0, 1, 0);
        r.projection.perspective((float) Math.toRadians(60), 2f, 0.1f, 100f);
        r.model.translation(1, 0, 0).scale(2f);
        Vector3f local = new Vector3f(0.25f, -0.1f, 0.3f);
        Vector4f clip = new Vector4f(local, 1f);
        new Matrix4f(r.projection).mul(r.view).mul(r.model).transform(clip);
        float mx = (clip.x / clip.w + 1f) * 0.5f * 200;
        float my = (1f - clip.y / clip.w) * 0.5f * 100;
        int cell = 0;
        r.faces[cell] = 1;
        r.depth[cell] = (clip.z / clip.w) * 0.5f + 0.5f;
        Vector3f out = new Vector3f();
        assertTrue(r.surfacePoint(cell, mx, my, out));
        assertTrue(out.distance(local) < 1e-4f, out.toString());
        r.faces[cell] = 0;
        assertFalse(r.surfacePoint(cell, mx, my, out), "no face drawn there");
    }

    @Test
    void theHoverGateRetriesAPendingPickAndIgnoresAnAbsentGpu() {
        assertEquals(0L, ModelPicking.epoch(), "no pass, no epoch");
        HoverQueryGate gate = new HoverQueryGate();
        Matrix4f m = new Matrix4f();
        assertTrue(gate.shouldPick(10, 10, m, m, m, 100, 100));
        assertFalse(gate.shouldPick(10, 10, m, m, m, 100, 100), "same inputs: no pick");
        // A detector whose GPU answer is pending asks the running gate to pick again next frame
        assertTrue(gate.shouldPick(11, 10, m, m, m, 100, 100));
        HoverQueryGate.retryCurrentPick();
        assertTrue(gate.shouldPick(11, 10, m, m, m, 100, 100), "retried");
        assertFalse(gate.shouldPick(11, 10, m, m, m, 100, 100));
        assertEquals(ModelPicking.UNAVAILABLE, ModelPicking.vertex(1, 1, 10, 10, m, m, m, 4, 7f),
                "without a pass every query falls back to the CPU");
    }
}
