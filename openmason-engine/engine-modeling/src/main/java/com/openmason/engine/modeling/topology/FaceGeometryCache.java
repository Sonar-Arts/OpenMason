package com.openmason.engine.modeling.topology;

import org.joml.Vector3f;

/**
 * Per-face normals, centroids, and areas, by face id (gaps: null / 0).
 *
 * <p>The values are the native mesh pipeline's ({@code co_mesh_*} TOPOLOGY stage, OpenMason #175 F3). A positions-only
 * refresh ({@code RenderMeshBuilder.Derivation.movePositions}) copies the touched faces' new values in through
 * {@link MeshTopologyBuilder#refreshMoved}; the Java dirty-recompute this class used to carry is gone (it was never
 * triggered: every edit rebuilt the topology).
 *
 * @see MeshTopologyBuilder
 */
public final class FaceGeometryCache {

    private final MeshFace[] faces;
    private final int[][] uniqueToMeshIndices;
    private final Vector3f[] faceNormals;
    private final Vector3f[] faceCentroids;
    private final float[] faceAreas;
    private float[] verticesRef;

    /**
     * Package-private constructor used by {@link MeshTopologyBuilder}.
     */
    FaceGeometryCache(MeshFace[] faces, int[][] uniqueToMeshIndices,
                      Vector3f[] faceNormals, Vector3f[] faceCentroids,
                      float[] faceAreas, float[] vertices) {
        this.faces = faces;
        this.uniqueToMeshIndices = uniqueToMeshIndices;
        this.faceNormals = faceNormals;
        this.faceCentroids = faceCentroids;
        this.faceAreas = faceAreas;
        this.verticesRef = vertices;
    }

    /**
     * Get the cached normal for a face.
     *
     * @param faceId Face identifier
     * @return The face normal (unit vector), or null if out of range
     */
    public Vector3f getFaceNormal(int faceId) {
        if (faceId < 0 || faceId >= faceNormals.length) {
            return null;
        }
        return faceNormals[faceId];
    }

    /**
     * Get the cached centroid for a face.
     *
     * @param faceId Face identifier
     * @return The face centroid, or null if out of range
     */
    public Vector3f getFaceCentroid(int faceId) {
        if (faceId < 0 || faceId >= faceCentroids.length) {
            return null;
        }
        return faceCentroids[faceId];
    }

    /**
     * Get the cached area for a face.
     *
     * @param faceId Face identifier
     * @return The face area, or {@code Float.NaN} if out of range
     */
    public float getFaceArea(int faceId) {
        if (faceId < 0 || faceId >= faceAreas.length) {
            return Float.NaN;
        }
        return faceAreas[faceId];
    }

    /**
     * Check whether a face is planar within a given tolerance.
     *
     * @param faceId    Face identifier
     * @param tolerance Maximum allowed distance from the face plane (must be &ge; 0)
     * @return true if all vertices lie within tolerance of the face plane
     */
    public boolean isFacePlanar(int faceId, float tolerance) {
        if (faceId < 0 || faceId >= faces.length || verticesRef == null) {
            return false;
        }
        MeshFace face = faces[faceId];
        if (face == null) {
            return false;
        }
        if (face.vertexCount() <= 3) {
            return true;
        }
        float maxDist = MeshGeometry.computeMaxDistanceToPlane(
                face.vertexIndices(), uniqueToMeshIndices, verticesRef,
                faceNormals[faceId], faceCentroids[faceId]);
        return maxDist <= tolerance;
    }

    /** Replaces one face's geometry (a positions-only refresh). Package-private. */
    void update(int faceId, Vector3f normal, Vector3f centroid, float area) {
        if (faceId >= 0 && faceId < faceNormals.length && faces[faceId] != null) {
            faceNormals[faceId] = normal;
            faceCentroids[faceId] = centroid;
            faceAreas[faceId] = area;
        }
    }

    /** The corner positions the planarity check reads (a positions-only refresh). Package-private. */
    void setVertices(float[] vertices) {
        this.verticesRef = vertices;
    }

    /**
     * Direct reference to the face normals array.
     * Package-private.
     */
    Vector3f[] normals() {
        return faceNormals;
    }

    /**
     * Direct reference to the face areas array.
     * Package-private.
     */
    float[] areas() {
        return faceAreas;
    }
}
