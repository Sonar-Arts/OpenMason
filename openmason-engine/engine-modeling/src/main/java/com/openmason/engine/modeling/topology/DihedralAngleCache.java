package com.openmason.engine.modeling.topology;

/**
 * Per-edge dihedral angles (radians, 0..pi; NaN unless the edge has exactly two faces).
 *
 * <p>The values are the native mesh pipeline's ({@code co_mesh_*} TOPOLOGY stage, OpenMason #175 F3); a positions-only
 * refresh copies the touched edges' new angles in through {@link MeshTopologyBuilder#refreshMoved}. The Java
 * dirty-recompute this class used to carry is gone (it was never triggered: every edit rebuilt the topology).
 *
 * @see MeshTopologyBuilder
 */
public final class DihedralAngleCache {

    private final MeshEdge[] edges;
    private final float[] dihedralAngles;

    /**
     * Package-private constructor used by {@link MeshTopologyBuilder}.
     *
     * @param edges          Edge array (shared reference)
     * @param dihedralAngles Dihedral angles per edge (owned by this cache)
     */
    DihedralAngleCache(MeshEdge[] edges, float[] dihedralAngles) {
        this.edges = edges;
        this.dihedralAngles = dihedralAngles;
    }

    /**
     * Get the dihedral angle at an edge in radians.
     *
     * @param edgeId Edge identifier (0..edgeCount-1)
     * @return Angle in radians (0..pi), or {@code Float.NaN} if undefined or out of range
     */
    public float getDihedralAngle(int edgeId) {
        if (edgeId < 0 || edgeId >= edges.length) {
            return Float.NaN;
        }
        return dihedralAngles[edgeId];
    }

    /** Replaces one edge's angle (a positions-only refresh). Package-private. */
    void update(int edgeId, float angle) {
        if (edgeId >= 0 && edgeId < dihedralAngles.length) {
            dihedralAngles[edgeId] = angle;
        }
    }
}
