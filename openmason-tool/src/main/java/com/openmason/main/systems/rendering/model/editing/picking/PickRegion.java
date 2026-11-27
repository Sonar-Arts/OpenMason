package com.openmason.main.systems.rendering.model.editing.picking;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * GL-free copy of one ID-pass readback (#175 F4): a {@code size x size} window of the viewport around the cursor,
 * with the inputs it was rendered for. Ids are stored +1 (0 = nothing drawn there). Pixel rows are GL rows (row 0 at
 * the bottom); a mouse position in viewport coordinates (y down) maps to pixel {@code (floor(x), floor(h - y))}.
 */
public final class PickRegion {

    /** Kind bits: which id targets a pass filled. */
    public static final int FACES = 1, EDGES = 2, VERTICES = 4;

    final int size;
    final int[] faces;
    final int[] edges;
    final int[] vertices;
    final float[] depth;

    boolean valid;
    int originX, originY;
    int viewportWidth, viewportHeight;
    final Matrix4f view = new Matrix4f();
    final Matrix4f projection = new Matrix4f();
    final Matrix4f model = new Matrix4f();
    long drawSignature;
    long geometryRevision;
    int tableVersion;
    float pointSize;
    int kinds;

    PickRegion(int size) {
        this.size = size;
        int n = size * size;
        faces = new int[n];
        edges = new int[n];
        vertices = new int[n];
        depth = new float[n];
    }

    /** Copies the inputs (not the pixels) of {@code other}. */
    void stampFrom(PickRegion other) {
        valid = other.valid;
        originX = other.originX;
        originY = other.originY;
        viewportWidth = other.viewportWidth;
        viewportHeight = other.viewportHeight;
        view.set(other.view);
        projection.set(other.projection);
        model.set(other.model);
        drawSignature = other.drawSignature;
        geometryRevision = other.geometryRevision;
        tableVersion = other.tableVersion;
        pointSize = other.pointSize;
        kinds = other.kinds;
    }

    /** True when this readback was rendered for exactly these camera / model / viewport inputs. */
    boolean sameView(int w, int h, Matrix4f viewMatrix, Matrix4f projectionMatrix, Matrix4f modelMatrix) {
        return valid && w == viewportWidth && h == viewportHeight && view.equals(viewMatrix)
                && projection.equals(projectionMatrix) && model.equals(modelMatrix);
    }

    /** Index of the pixel under the mouse, or -1 when it (with {@code margin} pixels around it) is outside. */
    int cell(float mouseX, float mouseY, int margin) {
        int x = (int) Math.floor(mouseX) - originX;
        int y = (int) Math.floor(viewportHeight - mouseY) - originY;
        if (x - margin < 0 || y - margin < 0 || x + margin >= size || y + margin >= size) {
            return -1;
        }
        return y * size + x;
    }

    /** Distance in pixels from the mouse to the window's centre (re-centre the next pass when it grows). */
    float offCentre(float mouseX, float mouseY) {
        float cx = originX + size * 0.5f;
        float cy = originY + size * 0.5f;
        return Math.max(Math.abs(mouseX - cx), Math.abs((viewportHeight - mouseY) - cy));
    }

    /** The id drawn under the mouse in {@code target}: id, or -1 for nothing. {@code cell} from {@link #cell}. */
    static int idAt(int[] target, int cell) {
        return target[cell] - 1;
    }

    /**
     * The id in {@code target} nearest the mouse within {@code radius} pixels (pixel centres), or -1. Ties keep the
     * lower id, so a result does not depend on the scan order.
     */
    int nearestId(int[] target, float mouseX, float mouseY, float radius) {
        int r = (int) Math.ceil(radius);
        int px = (int) Math.floor(mouseX) - originX;
        int py = (int) Math.floor(viewportHeight - mouseY) - originY;
        float sy = viewportHeight - mouseY;
        int best = -1;
        float bestD = Float.POSITIVE_INFINITY;
        for (int y = Math.max(0, py - r); y <= Math.min(size - 1, py + r); y++) {
            for (int x = Math.max(0, px - r); x <= Math.min(size - 1, px + r); x++) {
                int id = target[y * size + x] - 1;
                if (id < 0) continue;
                float dx = originX + x + 0.5f - mouseX;
                float dy = originY + y + 0.5f - sy;
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                if (d > radius) continue;
                if (d < bestD || (d == bestD && id < best)) {
                    bestD = d;
                    best = id;
                }
            }
        }
        return best;
    }

    /**
     * The surface point under the mouse in MODEL space (the depth unprojected through this readback's matrices), or
     * false when no face covers that pixel.
     */
    boolean surfacePoint(int cell, float mouseX, float mouseY, Vector3f out) {
        if (faces[cell] == 0) return false;
        float d = depth[cell];
        if (d >= 1.0f) return false;
        float ndcX = 2f * mouseX / viewportWidth - 1f;
        float ndcY = 1f - 2f * mouseY / viewportHeight;
        float ndcZ = 2f * d - 1f;
        Matrix4f inverse = new Matrix4f(projection).mul(view).mul(model).invert();
        Vector4f p = new Vector4f(ndcX, ndcY, ndcZ, 1f);
        inverse.transform(p);
        if (p.w == 0f) return false;
        out.set(p.x / p.w, p.y / p.w, p.z / p.w);
        return true;
    }
}
