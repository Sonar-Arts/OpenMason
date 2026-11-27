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
            return new Derivation(m, rm, assembleTopology(m, mesh, rm));
        } catch (RuntimeException e) {
            m.close();
            throw e;
        }
    }

    /**
     * Render mesh AND topology of a freshly imported mesh (#175 F3 + INTEG): {@code imported} is the native mesh of
     * {@link MeshImporter#importForDerive} (FACES current) and {@code mesh} the {@link EditableMesh} that import
     * returned. The mappings are set and TRIANGULATE / RENDER / TOPOLOGY built on that native mesh - the soup is
     * imported once and derived once, the mesh is never handed back to native code. Equals {@link #derive} of
     * {@code mesh} bit for bit. Takes ownership of {@code imported} (closed with the derivation, or on failure).
     */
    public static Derivation deriveImported(CendaMesh imported, EditableMesh mesh,
                                            IFaceTextureManager textureManager) {
        try {
            imported.setMappings(mesh, textureManager);
            imported.build(CendaMesh.STAGE_TRIANGULATE | CendaMesh.STAGE_RENDER | CendaMesh.STAGE_TOPOLOGY);
            logTriangulationFallbacks(imported);
            RenderMesh rm = renderMeshOf(imported);
            return new Derivation(imported, rm, assembleTopology(imported, mesh, rm));
        } catch (RuntimeException e) {
            imported.close();
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
            int changes = moveNative(mesh, vertexIds);
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

        /**
         * {@link #movePositions} for a long-lived editor mesh (#175 INTEG): the SAME {@link #renderMesh()} record is
         * kept and only the touched corners' positions and UVs are copied into its arrays (the {@link
         * #lastCornerSpans()}), the index array is rewritten in place when a face re-triangulated (INDICES), and the
         * topology is refreshed in place. O(moved corners) Java work instead of copying every array per drag frame.
         * Readers that hold {@code renderMesh()}'s arrays see the new values; take a copy to keep the old ones.
         *
         * @return the {@link CendaMesh} {@code CHANGED_*} bits, as {@link #movePositions}
         */
        public int movePositionsInPlace(EditableMesh mesh, int[] vertexIds) {
            int changes = moveNative(mesh, vertexIds);
            if (changes == 0) {
                return 0;
            }
            RenderMesh rm = renderMesh;
            java.lang.foreign.MemorySegment positions = nativeMesh.view(CendaMesh.A_CORNER_POSITIONS);
            java.lang.foreign.MemorySegment uvs = (changes & CendaMesh.CHANGED_UVS) != 0
                ? nativeMesh.view(CendaMesh.A_CORNER_UVS) : null;
            for (int s = 0; s < lastCornerSpans.length; s += 2) {
                int first = lastCornerSpans[s];
                int count = lastCornerSpans[s + 1];
                java.lang.foreign.MemorySegment.copy(positions, java.lang.foreign.ValueLayout.JAVA_FLOAT,
                    first * 12L, rm.vertices(), first * 3, count * 3);
                if (uvs != null) {
                    java.lang.foreign.MemorySegment.copy(uvs, java.lang.foreign.ValueLayout.JAVA_FLOAT,
                        first * 8L, rm.texCoords(), first * 2, count * 2);
                }
            }
            if ((changes & CendaMesh.CHANGED_INDICES) != 0) {
                java.lang.foreign.MemorySegment indices = nativeMesh.view(CendaMesh.A_TRI_INDICES);
                java.lang.foreign.MemorySegment.copy(indices, java.lang.foreign.ValueLayout.JAVA_INT, 0L,
                    rm.indices(), 0, rm.indices().length);
            }
            if (topology != null) {
                MeshTopologyBuilder.refreshMoved(topology, nativeMesh, lastMovedFaceIds, rm.vertices());
            }
            return changes;
        }

        /**
         * The render-corner ranges the last move touched: {@code first, count} pairs, ascending and disjoint (the
         * corners of the touched faces - every moved position and re-projected UV is inside; nearby ranges are
         * merged, at most {@value #MAX_SPANS} pairs). For range uploads of positions / UVs. Empty before a move.
         */
        public int[] lastCornerSpans() {
            return lastCornerSpans.clone();
        }

        /** Merge two touched corner ranges closer than this (a few draw-sized uploads beat many tiny ones). */
        private static final int SPAN_GAP = 256;
        static final int MAX_SPANS = 8;

        private int[] lastCornerSpans = new int[0];

        /** The native move + the touched faces / corner spans (shared by both move flavours). */
        private int moveNative(EditableMesh mesh, int[] vertexIds) {
            if (vertexIds == null || vertexIds.length == 0) {
                lastMovedFaceIds = new int[0];
                lastCornerSpans = new int[0];
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
            java.lang.foreign.MemorySegment slots = nativeMesh.view(CendaMesh.A_MOVED_FACES);
            java.lang.foreign.MemorySegment faceIds = nativeMesh.view(CendaMesh.A_FACE_IDS);
            java.lang.foreign.MemorySegment offsets = nativeMesh.view(CendaMesh.A_LOOP_OFFSETS);
            java.lang.foreign.ValueLayout.OfInt i32 = java.lang.foreign.ValueLayout.JAVA_INT;
            int n = slots == null ? 0 : (int) (slots.byteSize() / 4);
            lastMovedFaceIds = new int[n];
            int[] spans = new int[Math.max(2, Math.min(n, 64) * 2)];
            int spanCount = 0;
            for (int i = 0; i < n; i++) {
                int slot = slots.getAtIndex(i32, i);
                lastMovedFaceIds[i] = faceIds.getAtIndex(i32, slot);
                int start = offsets.getAtIndex(i32, slot);
                int end = offsets.getAtIndex(i32, slot + 1);
                if (spanCount > 0 && start - spans[spanCount - 1] <= SPAN_GAP) {
                    spans[spanCount - 1] = Math.max(spans[spanCount - 1], end);
                } else {
                    if (spanCount == spans.length) spans = java.util.Arrays.copyOf(spans, spans.length * 2);
                    spans[spanCount++] = start;
                    spans[spanCount++] = end;
                }
            }
            if (spanCount > MAX_SPANS * 2) {
                spans[1] = spans[spanCount - 1];
                spanCount = 2;
            }
            int[] out = new int[spanCount];
            for (int s = 0; s < spanCount; s += 2) {
                out[s] = spans[s];
                out[s + 1] = spans[s + 1] - spans[s];
            }
            lastCornerSpans = out;
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

    /** The Java topology services over the native arrays (timed as the edit-cost TOPOLOGY span). */
    private static MeshTopology assembleTopology(CendaMesh m, EditableMesh mesh, RenderMesh rm) {
        long t = com.openmason.engine.modeling.core.EditCostStats.begin();
        try {
            return MeshTopologyBuilder.fromNative(m, mesh, rm);
        } finally {
            com.openmason.engine.modeling.core.EditCostStats.end(
                com.openmason.engine.modeling.core.EditCostStats.Span.TOPOLOGY, t);
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
     * {@link #authoredUVsMatchProjection(EditableMesh, EditableFace, FaceTextureMapping, float)} for many faces in ONE
     * native derivation (#175 INTEG: a load checks every mapped face; one native mesh per face was the load's largest
     * cost after the derive): each face is copied alone (its own corners, no authored UVs) into one batch mesh, so
     * every projection is exactly the single-face one. {@code mappings.get(i)} belongs to {@code faces.get(i)}
     * (null = full region, no rotation).
     */
    public static boolean[] authoredUVsMatchProjection(EditableMesh mesh, java.util.List<EditableFace> faces,
                                                       java.util.List<FaceTextureMapping> mappings,
                                                       float tolerance) {
        int n = faces.size();
        boolean[] out = new boolean[n];
        if (n == 0) {
            return out;
        }
        EditableMesh batch = new EditableMesh();
        int[] ids = new int[n];
        FaceTextureMapping[] mapped = new FaceTextureMapping[n];
        int mappedCount = 0;
        Vector3f p = new Vector3f();
        for (int i = 0; i < n; i++) {
            EditableFace face = faces.get(i);
            int[] loop = new int[face.loopLength()];
            for (int k = 0; k < loop.length; k++) {
                mesh.position(face.vertexAt(k), p);
                loop[k] = batch.addVertex(p);
            }
            batch.addFaceWithId(i, loop);
            FaceTextureMapping mapping = mappings.get(i);
            if (mapping != null) {
                ids[mappedCount] = i;
                mapped[mappedCount++] = mapping;
            }
        }
        float[] projected;
        try (CendaMesh m = CendaMesh.create()) {
            m.setMappings(ids, mapped, mappedCount);
            m.setFaces(batch, CendaMesh.STAGE_RENDER);
            projected = m.floats(CendaMesh.A_CORNER_UVS);
        }
        int corner = 0;
        for (int i = 0; i < n; i++) {
            EditableFace face = faces.get(i);
            float[] authored = face.cornerUVs();
            int len = face.loopLength() * 2;
            boolean match = authored != null && authored.length >= len;
            for (int k = 0; match && k < len; k++) {
                if (Math.abs(projected[corner * 2 + k] - authored[k]) > tolerance) {
                    match = false;
                }
            }
            out[i] = match;
            corner += face.loopLength();
        }
        return out;
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
