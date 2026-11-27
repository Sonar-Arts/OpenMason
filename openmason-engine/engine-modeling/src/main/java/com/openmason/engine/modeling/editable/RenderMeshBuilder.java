package com.openmason.engine.modeling.editable;

import com.openmason.engine.modeling.topology.MeshTopology;
import com.openmason.engine.modeling.topology.MeshTopologyBuilder;
import com.openmason.engine.modeling.uv.FaceTextureMapping;
import com.openmason.engine.modeling.uv.IFaceTextureManager;
import com.openmason.engine.modeling.uv.MaterialDefinition;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Derives the render representation from an {@link EditableMesh}: emits one
 * corner per face-loop vertex (per-face duplication — a cube stays 24 render
 * vertices), projects corner UVs into each face's
 * {@link FaceTextureMapping.UVRegion}, and triangulates loops (the
 * {@link PolygonTriangulator} rules).
 *
 * <p>Because corners are never shared across faces, material seams need no
 * special handling — the legacy seam-duplication/strip machinery is
 * unnecessary by construction.
 *
 * <p>The UV projection is the tangent-frame region mapping (Newell normal → tangent frame → bounds normalized to
 * 0..1 → V flip → rotation → region). A face's AUTHORED corner UVs win over the projection when the face has no
 * explicit mapping, or when its mapping says they are authored ({@link FaceTextureMapping#authoredUVs()}).
 *
 * <p>Thin binding over the native mesh pipeline ({@code co_mesh_set_faces} + {@code co_mesh_build}, {@link CendaMesh};
 * OpenMason #175 F3), bit for bit the Java this replaced ({@code MeshPipelineParityTest}). {@link #derive} keeps the
 * native mesh alive for incremental positions-only refreshes ({@link Derivation#movePositions}).
 */
public final class RenderMeshBuilder {

    private static final Logger logger = LoggerFactory.getLogger(RenderMeshBuilder.class);

    private static final int TRI_FALLBACK_FLAGS = CendaMesh.FACE_TRI_DEGENERATE_NORMAL
            | CendaMesh.FACE_TRI_ZERO_AREA | CendaMesh.FACE_TRI_NO_EAR;

    private RenderMeshBuilder() {
        // Static utility — no instantiation
    }

    /**
     * Build the render mesh for all live faces, in face insertion order.
     *
     * @param mesh           Source mesh
     * @param textureManager Per-face UV region/rotation source; faces without a
     *                       mapping get the full region (legacy behavior).
     *                       May be {@code null} (all faces full-region).
     */
    public static RenderMesh build(EditableMesh mesh, IFaceTextureManager textureManager) {
        try (CendaMesh m = CendaMesh.create()) {
            m.setMappings(mesh, textureManager);
            m.setFaces(mesh, CendaMesh.STAGE_RENDER);
            logTriangulationFallbacks(m);
            return renderMeshOf(m);
        }
    }

    /**
     * Render mesh AND topology of {@code mesh} in one native pass (= {@link #build} +
     * {@link MeshTopologyBuilder#build}), keeping the native mesh for incremental drags. The caller owns the result
     * (close it, or let the cleaner do it).
     */
    public static Derivation derive(EditableMesh mesh, IFaceTextureManager textureManager) {
        CendaMesh m = CendaMesh.create();
        try {
            m.setMappings(mesh, textureManager);
            m.setFaces(mesh, CendaMesh.STAGE_RENDER | CendaMesh.STAGE_TOPOLOGY);
            logTriangulationFallbacks(m);
            RenderMesh rm = renderMeshOf(m);
            return new Derivation(m, rm, MeshTopologyBuilder.fromNative(m, mesh, rm));
        } catch (RuntimeException e) {
            m.close();
            throw e;
        }
    }

    /**
     * A derivation that stays bound to its native mesh: {@link #renderMesh()} / {@link #topology()} plus the
     * incremental positions-only refresh a drag needs.
     *
     * <p><b>Drag path</b>: after {@code EditableMesh.setPosition} on some vertices, call
     * {@link #movePositions(EditableMesh, int[])} with those vertex ids instead of a full rebuild. Native cost is
     * O(moved corners + the touched faces' triangulation and edges); the result equals a full {@link #build} +
     * {@link MeshTopologyBuilder#build} for the new positions, bit for bit (drag parity in
     * {@code MeshPipelineParityTest}). Only POSITIONS may change between calls: after any structural edit (faces,
     * loops, authored UVs) or a mapping change, derive again. Not thread-safe (one owner thread).
     */
    public static final class Derivation implements AutoCloseable {

        private final CendaMesh nativeMesh;
        private RenderMesh renderMesh;
        private final MeshTopology topology;
        private int[] lastMovedFaceIds = new int[0];

        private Derivation(CendaMesh nativeMesh, RenderMesh renderMesh, MeshTopology topology) {
            this.nativeMesh = nativeMesh;
            this.renderMesh = renderMesh;
            this.topology = topology;
        }

        /** The current render mesh (a new record after every {@link #movePositions} that changed something). */
        public RenderMesh renderMesh() {
            return renderMesh;
        }

        /**
         * The topology (null for an empty mesh, like {@link MeshTopologyBuilder#build}). The SAME object across
         * {@link #movePositions} calls: its face normals / centroids / areas and dihedral angles are refreshed in
         * place for the touched faces; its structure cannot change on a move.
         */
        public MeshTopology topology() {
            return topology;
        }

        /**
         * Positions-only refresh ({@code co_mesh_move_vertices}): reads the CURRENT positions of {@code vertexIds}
         * from {@code mesh} (the same mesh this derivation was made from, already moved with
         * {@code setPosition}) and updates the render mesh (new corner positions, projected UVs, re-triangulated
         * indices of the touched faces) and the topology's face geometry and dihedral angles.
         *
         * <p>The new {@link #renderMesh()} gets fresh {@code vertices}, {@code texCoords} and {@code indices} arrays;
         * {@code triangleToFaceId}, {@code cornerToVertexId} and {@code vertexIdToCorners} are shared with the
         * previous record (a move cannot change them).
         *
         * @return {@link CendaMesh} {@code CHANGED_*} bits: POSITIONS when anything moved, UVS when a projected UV
         *         changed, INDICES when a touched face re-triangulated differently (re-upload the index buffer),
         *         TOPOLOGY when the topology geometry was refreshed; 0 when nothing moved
         */
        public int movePositions(EditableMesh mesh, int[] vertexIds) {
            if (vertexIds == null || vertexIds.length == 0) {
                lastMovedFaceIds = new int[0];
                return 0;
            }
            float[] xyz = new float[vertexIds.length * 3];
            Vector3f p = new Vector3f();
            for (int i = 0; i < vertexIds.length; i++) {
                mesh.position(vertexIds[i], p);
                xyz[i * 3] = p.x;
                xyz[i * 3 + 1] = p.y;
                xyz[i * 3 + 2] = p.z;
            }
            int changes = nativeMesh.moveVertices(vertexIds, xyz);
            int[] slots = nativeMesh.ints(CendaMesh.A_MOVED_FACES);
            int[] faceIds = nativeMesh.ints(CendaMesh.A_FACE_IDS);
            lastMovedFaceIds = new int[slots.length];
            for (int i = 0; i < slots.length; i++) lastMovedFaceIds[i] = faceIds[slots[i]];
            if (changes == 0) {
                return 0;
            }
            RenderMesh old = renderMesh;
            renderMesh = new RenderMesh(
                nativeMesh.floats(CendaMesh.A_CORNER_POSITIONS),
                nativeMesh.floats(CendaMesh.A_CORNER_UVS),
                nativeMesh.ints(CendaMesh.A_TRI_INDICES),
                old.triangleToFaceId(), old.cornerToVertexId(), old.vertexIdToCorners());
            if (topology != null) {
                MeshTopologyBuilder.refreshMoved(topology, nativeMesh, lastMovedFaceIds, renderMesh.vertices());
            }
            return changes;
        }

        /** Face ids the last {@link #movePositions} touched (ascending face slot order). */
        public int[] lastMovedFaceIds() {
            return lastMovedFaceIds.clone();
        }

        @Override
        public void close() {
            nativeMesh.close();
        }
    }

    /** The RENDER stage arrays of a native mesh as a {@link RenderMesh}. */
    static RenderMesh renderMeshOf(CendaMesh m) {
        return new RenderMesh(
            m.floats(CendaMesh.A_CORNER_POSITIONS),
            m.floats(CendaMesh.A_CORNER_UVS),
            m.ints(CendaMesh.A_TRI_INDICES),
            m.ints(CendaMesh.A_TRI_FACE_IDS),
            m.ints(CendaMesh.A_LOOP_VERTICES),
            m.csr(CendaMesh.A_VERTEX_CORNER_OFFSETS, CendaMesh.A_VERTEX_CORNERS));
    }

    /** The Java builder warned per face that fell back to a fan; the native one reports it in the face flags. */
    private static void logTriangulationFallbacks(CendaMesh m) {
        if (!logger.isWarnEnabled()) {
            return;
        }
        int[] flags = m.ints(CendaMesh.A_FACE_FLAGS);
        int[] offsets = null;
        int[] faceIds = null;
        for (int f = 0; f < flags.length; f++) {
            if ((flags[f] & TRI_FALLBACK_FLAGS) != 0) {
                if (offsets == null) {
                    offsets = m.ints(CendaMesh.A_LOOP_OFFSETS);
                    faceIds = m.ints(CendaMesh.A_FACE_IDS);
                }
                PolygonTriangulator.logFallback(flags[f] & TRI_FALLBACK_FLAGS, offsets[f + 1] - offsets[f],
                    faceIds[f]);
            }
        }
    }

    /** True when a mapping is not the implicit default (another material, or a sub-region). */
    public static boolean isExplicit(FaceTextureMapping mapping) {
        return mapping != null
            && (mapping.materialId() != MaterialDefinition.DEFAULT.materialId()
                || !mapping.uvRegion().equals(FaceTextureMapping.FULL_REGION));
    }

    /**
     * True when the face's authored corner UVs equal (within {@code tolerance}
     * per coordinate) what the projection into {@code mapping}'s region gives —
     * UVs the editor itself projected and saved, as opposed to an authored UV
     * layout. False for a face without authored UVs.
     *
     * <p>The projection is the native one: the face alone (its loop positions, no authored UVs) derived with
     * {@code mapping} (null = full region, no rotation).
     */
    public static boolean authoredUVsMatchProjection(EditableMesh mesh, EditableFace face,
                                                     FaceTextureMapping mapping, float tolerance) {
        float[] authored = face.cornerUVs();
        if (authored == null) {
            return false;
        }
        int n = face.loopLength();
        EditableMesh single = new EditableMesh();
        int[] loop = new int[n];
        Vector3f p = new Vector3f();
        for (int i = 0; i < n; i++) {
            mesh.position(face.vertexAt(i), p);
            loop[i] = single.addVertex(p);
        }
        single.addFaceWithId(0, loop);
        float[] projected;
        try (CendaMesh m = CendaMesh.create()) {
            if (mapping != null) {
                m.setMappings(new int[]{0}, new FaceTextureMapping[]{mapping}, 1);
            }
            m.setFaces(single, CendaMesh.STAGE_RENDER);
            projected = m.floats(CendaMesh.A_CORNER_UVS);
        }
        for (int i = 0; i < projected.length; i++) {
            if (Math.abs(projected[i] - authored[i]) > tolerance) {
                return false;
            }
        }
        return true;
    }
}
