package com.openmason.engine.modeling.topology;

/**
 * Per-edge topological kind ({@link EdgeKind#OPEN}, {@link EdgeKind#MANIFOLD}, {@link EdgeKind#NON_MANIFOLD}) as a
 * parallel array alongside {@link MeshEdge}[]. Constructed by {@link MeshTopologyBuilder} and composed into
 * {@link MeshTopology}.
 *
 * <p>Sharp / seam / crease edge state is not kept here any more: smooth shading (sharp edges, auto smooth) is the
 * native shading store's ({@code modeling.shading.ModelShading}, OpenMason #169).
 */
public final class EdgeClassifier {

    private final EdgeKind[] kinds;

    /** Package-private constructor used by {@link MeshTopologyBuilder}. */
    EdgeClassifier(MeshEdge[] edges) {
        this.kinds = new EdgeKind[edges.length];
        for (int i = 0; i < edges.length; i++) {
            kinds[i] = EdgeKind.fromAdjacentFaceCount(edges[i].adjacentFaceCount());
        }
    }

    /** The kind of an edge, or null when out of range. */
    public EdgeKind getKind(int edgeId) {
        return edgeId < 0 || edgeId >= kinds.length ? null : kinds[edgeId];
    }

    public boolean isOpen(int edgeId) {
        return getKind(edgeId) == EdgeKind.OPEN;
    }

    public boolean isManifold(int edgeId) {
        return getKind(edgeId) == EdgeKind.MANIFOLD;
    }

    public boolean isNonManifold(int edgeId) {
        return getKind(edgeId) == EdgeKind.NON_MANIFOLD;
    }

    /** Number of edges of a kind. */
    public int countByKind(EdgeKind kind) {
        int count = 0;
        for (EdgeKind k : kinds) {
            if (k == kind) {
                count++;
            }
        }
        return count;
    }
}
