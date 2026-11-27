package com.openmason.engine.modeling.topology;

import com.openmason.engine.modeling.ModelingConstants;
import org.joml.Vector3f;

import java.util.List;

/**
 * Stateless geometry computations for mesh topology elements.
 *
 * <p>Pure functions with no side effects — safe to call from any thread.
 * Face normals, centroids, areas and dihedral angles are computed by the native mesh pipeline
 * ({@code co_mesh_*} TOPOLOGY stage, OpenMason #175 F3); what is left here is the planarity measure and the face pair
 * key the Java query services use.
 */
public final class MeshGeometry {

    private MeshGeometry() {
        // Utility class
    }

    /**
     * Compute the maximum distance from any vertex to the best-fit plane of a face.
     * The plane is defined by the face normal and centroid.
     *
     * <p>Trivially returns 0 for triangles (3 vertices are always coplanar).
     * For quads and n-gons, measures the absolute distance from each vertex
     * to the plane, returning the maximum.
     *
     * @param uniqueVertexIndices Ordered unique vertex indices of the face
     * @param uniqueToMesh       Mapping from unique vertex index to mesh indices
     * @param vertices           Vertex positions (x,y,z interleaved, indexed by mesh index)
     * @param normal             Precomputed face normal (unit vector)
     * @param centroid           Precomputed face centroid
     * @return Maximum absolute distance from any vertex to the face plane,
     *         or 0 for triangles and degenerate faces
     */
    public static float computeMaxDistanceToPlane(int[] uniqueVertexIndices, int[][] uniqueToMesh,
                                                   float[] vertices, Vector3f normal, Vector3f centroid) {
        int count = uniqueVertexIndices.length;
        if (count <= 3) {
            return 0.0f;
        }

        float normalLen = normal.lengthSquared();
        if (normalLen < ModelingConstants.DEGENERATE_NORMAL_EPSILON_SQ) {
            return 0.0f;
        }

        float maxDist = 0.0f;
        for (int i = 0; i < count; i++) {
            int meshIdx = uniqueToMesh[uniqueVertexIndices[i]][0];
            float vx = vertices[meshIdx * 3]     - centroid.x;
            float vy = vertices[meshIdx * 3 + 1] - centroid.y;
            float vz = vertices[meshIdx * 3 + 2] - centroid.z;

            float dist = Math.abs(vx * normal.x + vy * normal.y + vz * normal.z);
            if (dist > maxDist) {
                maxDist = dist;
            }
        }
        return maxDist;
    }

    /**
     * Compute a canonical key for a pair of face IDs.
     * Packs the smaller ID into the high bits, the larger into the low bits.
     *
     * @param faceA First face ID
     * @param faceB Second face ID
     * @return Packed long suitable for use as a map key
     */
    public static long canonicalFacePairKey(int faceA, int faceB) {
        int min = Math.min(faceA, faceB);
        int max = Math.max(faceA, faceB);
        return ((long) min << 32) | (max & 0xFFFFFFFFL);
    }
}
