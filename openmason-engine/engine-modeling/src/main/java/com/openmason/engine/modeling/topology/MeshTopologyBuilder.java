package com.openmason.engine.modeling.topology;

import com.openmason.engine.modeling.editable.CendaMesh;
import com.openmason.engine.modeling.editable.EditableMesh;
import com.openmason.engine.modeling.editable.RenderMesh;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Static factory that builds {@link MeshTopology} from the authoritative
 * {@link EditableMesh} and its derived {@link RenderMesh}.
 *
 * <p>Face loops and winding come from the editable mesh VERBATIM — nothing is
 * reconstructed from triangles: every edge in the topology is a real
 * polygon-boundary edge, including hole outlines on multi-face open meshes.
 *
 * <p>Index-mapping data ("unique" = editable vertex id, "mesh" = render corner
 * index) comes from the render mesh's exact corner maps — no epsilon welding.
 *
 * <p>Thin binding over the native mesh pipeline (TOPOLOGY stage of {@code co_mesh_*}, {@link CendaMesh}; OpenMason
 * #175 F3): edges (ids in first-use order), edge → face lists and face adjacency in {@code java.util.HashSet}
 * iteration order, face normals / centroids / areas (float Newell), dihedral angles and edge kinds are native, bit for
 * bit the Java this replaced ({@code MeshPipelineParityTest}; dihedral angles within 1 ulp). This class assembles the
 * Java query services ({@link MeshTopology}'s sub-services) from those arrays.
 *
 * <p>Thread-safe: stateless factory methods.
 */
public final class MeshTopologyBuilder {

    private static final Logger logger = LoggerFactory.getLogger(MeshTopologyBuilder.class);

    private MeshTopologyBuilder() {
        // Utility class
    }

    /**
     * Build a MeshTopology.
     *
     * @param mesh       Authoritative mesh (loops + winding)
     * @param renderMesh Derived render mesh (corner maps, triangle→face, positions)
     * @return Immutable MeshTopology, or null for an empty mesh
     */
    public static MeshTopology build(EditableMesh mesh, RenderMesh renderMesh) {
        if (mesh == null || renderMesh == null || mesh.faceCount() == 0
                || renderMesh.cornerCount() == 0) {
            logger.debug("Cannot build topology: empty mesh");
            return null;
        }
        // Face geometry reads each vertex's FIRST render corner (as the Java builder did); a vertex without corners
        // is in no face, its position does not matter.
        int vertexCount = mesh.vertexCount();
        float[] corners = renderMesh.vertices();
        int[][] vertexToCorners = renderMesh.vertexIdToCorners();
        float[] positions = new float[vertexCount * 3];
        Vector3f p = new Vector3f();
        for (int v = 0; v < vertexCount; v++) {
            if (v < vertexToCorners.length && vertexToCorners[v].length > 0) {
                int c = vertexToCorners[v][0];
                positions[v * 3] = corners[c * 3];
                positions[v * 3 + 1] = corners[c * 3 + 1];
                positions[v * 3 + 2] = corners[c * 3 + 2];
            } else {
                mesh.position(v, p);
                positions[v * 3] = p.x;
                positions[v * 3 + 1] = p.y;
                positions[v * 3 + 2] = p.z;
            }
        }
        try (CendaMesh m = CendaMesh.create()) {
            m.setFaces(mesh, positions, CendaMesh.STAGE_TOPOLOGY);
            return assemble(m, renderMesh);
        }
    }

    /**
     * The topology of a native mesh whose TOPOLOGY stage is current (binding internal, used by
     * {@code RenderMeshBuilder.derive}); {@code renderMesh} supplies the index mapping. Null for an empty mesh.
     */
    public static MeshTopology fromNative(CendaMesh nativeMesh, EditableMesh mesh, RenderMesh renderMesh) {
        if (mesh == null || renderMesh == null || mesh.faceCount() == 0 || renderMesh.cornerCount() == 0) {
            return null;
        }
        return assemble(nativeMesh, renderMesh);
    }

    /**
     * After {@code co_mesh_move_vertices} (binding internal, used by {@code RenderMeshBuilder.Derivation}): copies the
     * refreshed face normals / centroids / areas of {@code movedFaceIds} and the dihedral angles of their edges into
     * {@code topology} in place, and points its planarity checks at the new corner positions.
     */
    public static void refreshMoved(MeshTopology topology, CendaMesh nativeMesh, int[] movedFaceIds,
                                    float[] cornerPositions) {
        FaceGeometryCache geometry = topology.faceGeometryCache();
        geometry.setVertices(cornerPositions);
        if (movedFaceIds.length == 0) {
            return;
        }
        float[] normals = nativeMesh.floats(CendaMesh.A_FACE_NORMALS);
        float[] centroids = nativeMesh.floats(CendaMesh.A_FACE_CENTROIDS);
        float[] areas = nativeMesh.floats(CendaMesh.A_FACE_AREAS);
        float[] dihedral = nativeMesh.floats(CendaMesh.A_EDGE_DIHEDRAL);
        DihedralAngleCache angles = topology.dihedralAngleCache();
        for (int fid : movedFaceIds) {
            geometry.update(fid,
                new Vector3f(normals[fid * 3], normals[fid * 3 + 1], normals[fid * 3 + 2]),
                new Vector3f(centroids[fid * 3], centroids[fid * 3 + 1], centroids[fid * 3 + 2]),
                areas[fid]);
            MeshFace face = topology.getFace(fid);
            if (face != null) {
                for (int e : face.edgeIds()) {
                    if (e >= 0 && e < dihedral.length) {
                        angles.update(e, dihedral[e]);
                    }
                }
            }
        }
    }

    /** Assembles the Java query services from the native TOPOLOGY arrays. */
    private static MeshTopology assemble(CendaMesh m, RenderMesh renderMesh) {
        CendaMesh.Info info = m.info();
        int faceIdUpperBound = info.faceIdUpperBound();
        int uniqueVertexCount = info.vertexCount();
        int triangleCount = renderMesh.triangleCount();

        // Edges (ids in first-use order) with their face lists (HashSet iteration order).
        int[] edgeVertices = m.ints(CendaMesh.A_EDGE_VERTICES);
        int[][] edgeFaces = m.csr(CendaMesh.A_EDGE_FACE_OFFSETS, CendaMesh.A_EDGE_FACES);
        int edgeCount = edgeFaces.length;
        MeshEdge[] edges = new MeshEdge[edgeCount];
        Map<Long, Integer> edgeKeyToId = new HashMap<>(edgeCount * 2);
        for (int e = 0; e < edgeCount; e++) {
            edges[e] = new MeshEdge(e, edgeVertices[2 * e], edgeVertices[2 * e + 1], edgeFaces[e]);
            edgeKeyToId.put(MeshEdge.canonicalKey(edgeVertices[2 * e], edgeVertices[2 * e + 1]), e);
        }

        // Faces from the authoritative loops (insertion order) with their loop edges.
        int[] faceIds = m.ints(CendaMesh.A_FACE_IDS);
        int[] loopOffsets = m.ints(CendaMesh.A_LOOP_OFFSETS);
        int[] loopVertices = m.ints(CendaMesh.A_LOOP_VERTICES);
        int[] cornerEdges = m.ints(CendaMesh.A_CORNER_EDGES);
        MeshFace[] faces = new MeshFace[faceIds.length];
        MeshFace[] facesById = new MeshFace[faceIdUpperBound];
        for (int f = 0; f < faces.length; f++) {
            faces[f] = new MeshFace(faceIds[f],
                Arrays.copyOfRange(loopVertices, loopOffsets[f], loopOffsets[f + 1]),
                Arrays.copyOfRange(cornerEdges, loopOffsets[f], loopOffsets[f + 1]));
            facesById[faceIds[f]] = faces[f];
        }

        // Vertex adjacency.
        List<List<Integer>> immutableVertexToEdges =
            unmodifiableRows(m.csr(CendaMesh.A_VERTEX_EDGE_OFFSETS, CendaMesh.A_VERTEX_EDGES));
        List<List<Integer>> immutableVertexToFaces =
            unmodifiableRows(m.csr(CendaMesh.A_VERTEX_FACE_OFFSETS, CendaMesh.A_VERTEX_FACES));

        // Face-to-face adjacency by face id (gaps: empty) + the shared edge per pair.
        int[][] adjacent = m.csr(CendaMesh.A_FACE_ADJ_OFFSETS, CendaMesh.A_FACE_ADJ);
        int[][] adjacentEdges = m.csr(CendaMesh.A_FACE_ADJ_OFFSETS, CendaMesh.A_FACE_ADJ_EDGES);
        Map<Long, Integer> facePairToEdgeId = new HashMap<>();
        for (int fid = 0; fid < adjacent.length; fid++) {
            for (int i = 0; i < adjacent[fid].length; i++) {
                facePairToEdgeId.put(MeshGeometry.canonicalFacePairKey(fid, adjacent[fid][i]), adjacentEdges[fid][i]);
            }
        }
        List<List<Integer>> immutableFaceToAdjacentFaces = Collections.unmodifiableList(unmodifiableRows(adjacent));

        boolean uniform = info.uniform();
        int firstCount = info.firstFaceVertexCount();

        // Index mapping — exact corner maps from the render mesh.
        int[] meshToUniqueMapping = renderMesh.cornerToVertexId().clone();
        int[][] vertexIdToCorners = renderMesh.vertexIdToCorners();
        int[][] uniqueToMeshIndices = new int[uniqueVertexCount][];
        for (int i = 0; i < uniqueVertexCount; i++) {
            uniqueToMeshIndices[i] = i < vertexIdToCorners.length
                ? vertexIdToCorners[i].clone() : new int[0];
        }
        int[] triangleToFaceId = renderMesh.triangleToFaceId().clone();

        // Face geometry by face id (gaps: null / 0).
        float[] normals = m.floats(CendaMesh.A_FACE_NORMALS);
        float[] centroids = m.floats(CendaMesh.A_FACE_CENTROIDS);
        float[] faceAreas = m.floats(CendaMesh.A_FACE_AREAS);
        Vector3f[] faceNormals = new Vector3f[faceIdUpperBound];
        Vector3f[] faceCentroids = new Vector3f[faceIdUpperBound];
        for (int fid : faceIds) {
            faceNormals[fid] = new Vector3f(normals[fid * 3], normals[fid * 3 + 1], normals[fid * 3 + 2]);
            faceCentroids[fid] = new Vector3f(centroids[fid * 3], centroids[fid * 3 + 1], centroids[fid * 3 + 2]);
        }

        int[] kindOrdinals = m.ints(CendaMesh.A_EDGE_KIND);
        EdgeKind[] kinds = new EdgeKind[edgeCount];
        EdgeKind[] all = EdgeKind.values();
        for (int e = 0; e < edgeCount; e++) {
            kinds[e] = all[kindOrdinals[e]];
        }

        Map<Long, Integer> immutableEdgeKeyToId = Collections.unmodifiableMap(edgeKeyToId);
        Map<Long, Integer> immutableFacePairToEdgeId = Collections.unmodifiableMap(facePairToEdgeId);

        IndexMappingQuery indexMappingQuery = new IndexMappingQuery(
                meshToUniqueMapping, uniqueToMeshIndices, uniqueVertexCount,
                triangleToFaceId, triangleCount);

        TopologyMetadataQuery topologyMetadataQuery = new TopologyMetadataQuery(
                uniform, firstCount, faces);

        ElementAdjacencyQuery elementAdjacencyQuery = new ElementAdjacencyQuery(
                edges, facesById, immutableVertexToEdges, immutableVertexToFaces,
                immutableFaceToAdjacentFaces, immutableFacePairToEdgeId);

        FaceGeometryCache faceGeometryCache = new FaceGeometryCache(
                facesById, uniqueToMeshIndices, faceNormals, faceCentroids,
                faceAreas, renderMesh.vertices());

        EdgeClassifier edgeClassifier = new EdgeClassifier(kinds);

        DihedralAngleCache dihedralAngleCache = new DihedralAngleCache(
                edges, m.floats(CendaMesh.A_EDGE_DIHEDRAL));

        VertexClassifier vertexClassifier = new VertexClassifier(
                edges, immutableVertexToEdges, uniform, firstCount);

        FaceEdgeTraversal faceEdgeTraversal = new FaceEdgeTraversal(facesById);

        EdgeLoopTracer edgeLoopTracer = new EdgeLoopTracer(edges, facesById);

        FaceLoopTracer faceLoopTracer = new FaceLoopTracer(edges, facesById);

        FaceIslandDetector faceIslandDetector = new FaceIslandDetector(
                faceIdUpperBound, immutableFaceToAdjacentFaces);

        VertexRingQuery vertexRingQuery = new VertexRingQuery(
                edges, facesById, immutableVertexToEdges, immutableVertexToFaces,
                immutableEdgeKeyToId);

        VertexAdjacencyQuery vertexAdjacencyQuery = new VertexAdjacencyQuery(
                immutableEdgeKeyToId, edges, immutableVertexToEdges);

        VertexBoundaryWalker vertexBoundaryWalker = new VertexBoundaryWalker(
                edges, immutableVertexToEdges);

        MeshTopology topology = new MeshTopology(
                edges, facesById, immutableEdgeKeyToId,
                indexMappingQuery, topologyMetadataQuery, elementAdjacencyQuery,
                faceGeometryCache, dihedralAngleCache,
                edgeClassifier, vertexClassifier, faceEdgeTraversal,
                edgeLoopTracer, faceLoopTracer, faceIslandDetector,
                vertexRingQuery, vertexAdjacencyQuery, vertexBoundaryWalker);

        logger.debug("Built MeshTopology: {} edges, {} faces, {} vertices, {} triangles, uniform={} (vpf={})",
            edgeCount, faces.length, uniqueVertexCount, triangleCount, uniform, firstCount);

        return topology;
    }

    private static List<List<Integer>> unmodifiableRows(int[][] rows) {
        List<List<Integer>> out = new ArrayList<>(rows.length);
        for (int[] row : rows) {
            List<Integer> list = new ArrayList<>(row.length);
            for (int v : row) {
                list.add(v);
            }
            out.add(Collections.unmodifiableList(list));
        }
        return out;
    }
}
