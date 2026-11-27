package com.openmason.engine.modeling.editable;

import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Triangulates a polygon loop, correctly handling concave polygons and loops
 * with collinear vertices (e.g. {@code [A, midpoint, B, ...]} after edge
 * subdivision).
 *
 * <p>Thin binding over the native mesh pipeline ({@code co_polygon_triangulate}, {@link CendaMesh}; OpenMason #175
 * F3), bit for bit the Java this replaced: the loop normalized to its extent → Newell normal → projection onto the
 * best-fit plane → convex fast path (fan) → ear clipping. Always emits exactly {@code loop.length - 2} triangles; on
 * degenerate input it falls back to a fan with a warning rather than failing.
 */
public final class PolygonTriangulator {

    private static final Logger logger = LoggerFactory.getLogger(PolygonTriangulator.class);

    private PolygonTriangulator() {
        // Static utility — no instantiation
    }

    /**
     * Triangulate the polygon formed by {@code loop} (ordered boundary positions).
     *
     * @param loop Polygon boundary positions in winding order (≥ 3)
     * @return Loop-local index triples, {@code 3 * (loop.length - 2)} entries,
     *         wound consistently with the input loop
     */
    public static int[] triangulate(Vector3f[] loop) {
        int n = loop.length;
        if (n < 3) {
            throw new IllegalArgumentException("Polygon needs >= 3 vertices: " + n);
        }
        if (n == 3) {
            return new int[]{0, 1, 2};
        }
        float[] xyz = new float[n * 3];
        for (int i = 0; i < n; i++) {
            xyz[i * 3] = loop[i].x;
            xyz[i * 3 + 1] = loop[i].y;
            xyz[i * 3 + 2] = loop[i].z;
        }
        CendaMesh.Triangulation t = CendaMesh.triangulate(xyz);
        logFallback(t.flags(), n, -1);
        return t.indices();
    }

    /** Logs a fan fallback of the native triangulator the way the Java triangulator did. */
    static void logFallback(int flags, int n, int faceId) {
        if (flags == 0) {
            return;
        }
        String face = faceId >= 0 ? "face " + faceId + ": " : "";
        if ((flags & CendaMesh.FACE_TRI_DEGENERATE_NORMAL) != 0) {
            logger.warn("{}Degenerate polygon normal ({} vertices) — falling back to fan triangulation", face, n);
        } else if ((flags & CendaMesh.FACE_TRI_ZERO_AREA) != 0) {
            logger.warn("{}Zero-area polygon ({} vertices) — falling back to fan triangulation", face, n);
        } else if ((flags & CendaMesh.FACE_TRI_NO_EAR) != 0) {
            logger.warn("{}Ear clipping found no ear ({} vertices) — finishing with fan", face, n);
        }
    }

    /**
     * Newell normal of a polygon loop — robust for non-planar and collinear
     * inputs, unlike a single cross product of the first three vertices.
     * Points out of a counter-clockwise loop. Not normalized.
     *
     * <p>Kept in Java on purpose (#175 F3): pure math used by edit ops per face, cheaper than a native call; the
     * native triangulator computes the same value (double accumulation relative to {@code loop[0]}).
     */
    public static Vector3f newellNormal(Vector3f[] loop) {
        double nx = 0, ny = 0, nz = 0;
        int n = loop.length;
        if (n == 0) return new Vector3f();
        Vector3f origin = loop[0];
        for (int i = 0; i < n; i++) {
            Vector3f c = loop[i];
            Vector3f x = loop[(i + 1) % n];
            double cy = (double) c.y - origin.y, xy = (double) x.y - origin.y;
            double cz = (double) c.z - origin.z, xz = (double) x.z - origin.z;
            double cx = (double) c.x - origin.x, xx = (double) x.x - origin.x;
            nx += (cy - xy) * (cz + xz);
            ny += (cz - xz) * (cx + xx);
            nz += (cx - xx) * (cy + xy);
        }
        return new Vector3f((float) nx, (float) ny, (float) nz);
    }
}
