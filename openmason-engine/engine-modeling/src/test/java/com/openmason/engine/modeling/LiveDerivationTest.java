package com.openmason.engine.modeling;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.modeling.core.EditCostStats;
import com.openmason.engine.modeling.core.MeshGpuState;
import com.openmason.engine.modeling.core.MeshRebuildPipeline;
import com.openmason.engine.modeling.editable.EditableFace;
import com.openmason.engine.modeling.editable.EditableMesh;
import com.openmason.engine.modeling.editable.MeshImporter;
import com.openmason.engine.modeling.editable.RenderMesh;
import com.openmason.engine.modeling.editable.RenderMeshBuilder;
import com.openmason.engine.modeling.topology.MeshTopology;
import com.openmason.engine.modeling.uv.FaceTextureManager;
import com.openmason.engine.modeling.uv.FaceTextureMapping;
import com.openmason.engine.modeling.uv.MaterialDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #175 INTEG, GL-free: the editor's rebuild pipeline on the native single pass. Drag frames refresh the live
 * derivation in place and end exactly where a full derive of the moved mesh ends (render mesh, UVs, triangulation,
 * topology face geometry, dihedral angles); a commit then derives nothing; the dirty corner ranges cover every changed
 * corner; an import derives on its own native mesh with the same result as a fresh derive; the batched authored-UV
 * check equals the per-face one; a released derivation falls back to the full refresh.
 */
class LiveDerivationTest {

    @AfterEach
    void stats() {
        EditCostStats.setEnabled(false);
        EditCostStats.reset();
    }

    /** A wavy grid of quads, 4 own corners per quad (an OMO soup), one face per quad. */
    private static OMOFormat.MeshData grid(int w, int h) {
        float[] v = new float[w * h * 12];
        int[] idx = new int[w * h * 6];
        int[] faces = new int[w * h * 2];
        int q = 0;
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < h; j++) {
                float[][] c = {{i, j}, {i + 1, j}, {i + 1, j + 1}, {i, j + 1}};
                for (int k = 0; k < 4; k++) {
                    v[q * 12 + k * 3] = c[k][0];
                    v[q * 12 + k * 3 + 1] = c[k][1];
                    v[q * 12 + k * 3 + 2] = 0.2f * (float) Math.sin(c[k][0] * 0.7 + c[k][1]);
                }
                int b = q * 4;
                System.arraycopy(new int[] {b, b + 1, b + 2, b, b + 2, b + 3}, 0, idx, q * 6, 6);
                faces[q * 2] = q;
                faces[q * 2 + 1] = q;
                q++;
            }
        }
        return new OMOFormat.MeshData(v, new float[w * h * 8], idx, faces, "FLAT");
    }

    /** A grid model whose faces are PROJECTED into material regions (some rotated / sub-regions), so UVs move. */
    private static EditableModel model(int w, int h) {
        EditableModel m = new EditableModel();
        m.loadMeshDataAsPart(grid(w, h), "Root");
        FaceTextureManager ftm = m.getFaceTextureManager();
        ftm.registerMaterial(new MaterialDefinition(300, "M", 0, MaterialDefinition.RenderLayer.OPAQUE,
                MaterialDefinition.MaterialProperties.NONE));
        FaceTextureMapping.UVRotation[] rot = FaceTextureMapping.UVRotation.values();
        for (int f = 0; f < w * h; f++) {
            FaceTextureMapping.UVRegion region = f % 3 == 0 ? FaceTextureMapping.FULL_REGION
                    : new FaceTextureMapping.UVRegion(0.25f, 0.1f, 0.75f, 0.6f);
            ftm.setFaceMapping(new FaceTextureMapping(f, 300, region, rot[f % rot.length]));
        }
        m.refreshUVs();
        return m;
    }

    private static MeshRebuildPipeline pipeline(EditableModel m) {
        try {
            var f = EditableModel.class.getDeclaredField("rebuildPipeline");
            f.setAccessible(true);
            return (MeshRebuildPipeline) f.get(m);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** The model's derived state equals a fresh native derivation of a copy of its mesh, bit for bit. */
    private static void assertEqualsFullDerive(String ctx, EditableModel m) {
        MeshRebuildPipeline p = pipeline(m);
        EditableMesh copy = p.getEditableMesh().deepCopy();
        try (RenderMeshBuilder.Derivation full = RenderMeshBuilder.derive(copy, m.getFaceTextureManager())) {
            RenderMesh a = full.renderMesh();
            RenderMesh b = p.getLastRenderMesh();
            assertArrayEquals(a.vertices(), b.vertices(), ctx + ": positions");
            assertArrayEquals(a.texCoords(), b.texCoords(), ctx + ": uvs");
            assertArrayEquals(a.indices(), b.indices(), ctx + ": indices");
            assertArrayEquals(a.triangleToFaceId(), b.triangleToFaceId(), ctx + ": tri faces");
            assertArrayEquals(a.vertices(), m.getAllMeshVertexPositions(), ctx + ": vertex manager positions");
            assertArrayEquals(a.indices(), m.getIndices(), ctx + ": vertex manager indices");
            MeshTopology ta = full.topology();
            MeshTopology tb = m.getTopology();
            assertEquals(ta.getEdgeCount(), tb.getEdgeCount(), ctx);
            for (int f = 0; f < ta.getFaceCount(); f++) {
                assertEquals(ta.getFaceNormal(f), tb.getFaceNormal(f), ctx + ": normal " + f);
                assertEquals(ta.getFaceCentroid(f), tb.getFaceCentroid(f), ctx + ": centroid " + f);
                assertEquals(ta.getFaceArea(f), tb.getFaceArea(f), ctx + ": area " + f);
            }
            for (int e = 0; e < ta.getEdgeCount(); e++) {
                assertEquals(ta.getDihedralAngle(e), tb.getDihedralAngle(e), ctx + ": dihedral " + e);
            }
        }
    }

    @Test
    void dragFramesKeepTheLiveDerivationEqualToAFullDerive() {
        EditableModel m = model(7, 6);
        MeshRebuildPipeline p = pipeline(m);
        assertTrue(p.isDerivationLive());
        RenderMesh record = p.getLastRenderMesh();
        MeshTopology topology = m.getTopology();
        MeshGpuState gpu = m.getGpuState();
        Random r = new Random(5);
        boolean sawUvs = false;
        boolean sawIndices = false;
        for (int frame = 0; frame < 40; frame++) {
            gpu.clear();
            float[] before = m.getAllMeshVertexPositions();
            float[] uvBefore = m.getTexCoords().clone();
            int[] indicesBefore = m.getIndices().clone();
            int n = 1 + r.nextInt(5);
            int[] ids = new int[n];
            float[] xyz = new float[n * 3];
            for (int i = 0; i < n; i++) {
                ids[i] = r.nextInt(m.getUniqueVertexCount());
                var pos = m.getUniqueVertexPosition(ids[i]);
                xyz[i * 3] = pos.x + (r.nextFloat() - 0.5f) * 0.6f;
                xyz[i * 3 + 1] = pos.y + (r.nextFloat() - 0.5f) * 0.6f;
                xyz[i * 3 + 2] = pos.z + (r.nextFloat() - 0.5f) * 1.5f; // non-planar: re-triangulates
            }
            m.moveVertices(ids, xyz);
            assertEqualsFullDerive("frame " + frame, m);
            assertSame(record, p.getLastRenderMesh(), "a move keeps the render mesh record (in place)");
            assertSame(topology, m.getTopology(), "a move keeps the topology object");

            // Every changed corner lies inside the dirty ranges
            float[] after = m.getAllMeshVertexPositions();
            int[] spans = gpu.positionSpans();
            assertTrue(gpu.positionsDirty());
            if (spans != null) {
                for (int c = 0; c < after.length / 3; c++) {
                    boolean changed = after[c * 3] != before[c * 3] || after[c * 3 + 1] != before[c * 3 + 1]
                            || after[c * 3 + 2] != before[c * 3 + 2];
                    if (changed) assertTrue(inSpans(spans, c), "frame " + frame + ": corner " + c);
                }
            }
            if (!Arrays.equals(uvBefore, m.getTexCoords())) {
                sawUvs = true;
                assertTrue(gpu.uvsDirty(), "re-projected UVs are re-sent");
                int[] uvSpans = gpu.uvSpans();
                float[] uv = m.getTexCoords();
                if (uvSpans != null) {
                    for (int c = 0; c < uv.length / 2; c++) {
                        if (uv[c * 2] != uvBefore[c * 2] || uv[c * 2 + 1] != uvBefore[c * 2 + 1]) {
                            assertTrue(inSpans(uvSpans, c), "frame " + frame + ": uv corner " + c);
                        }
                    }
                }
            }
            if (!Arrays.equals(indicesBefore, m.getIndices())) {
                sawIndices = true;
                assertTrue(p.isDrawBatchesDirty(), "a re-triangulation re-sorts the index buffer");
            }
            p.clearDrawBatchesDirty();
        }
        assertTrue(sawUvs, "the fixture re-projects UVs");
        assertTrue(sawIndices, "the fixture re-triangulates");

        EditCostStats.reset();
        EditCostStats.setEnabled(true);
        m.commitVertexPositions();
        assertEquals(0, EditCostStats.totalCalls(EditCostStats.Span.DERIVE), "a commit derives nothing");
        assertEquals(1, EditCostStats.totalCalls(EditCostStats.Span.DRAG_COMMIT));
        assertEqualsFullDerive("after commit", m);
    }

    private static boolean inSpans(int[] spans, int corner) {
        for (int i = 0; i < spans.length; i += 2) {
            if (corner >= spans[i] && corner < spans[i] + spans[i + 1]) return true;
        }
        return false;
    }

    @Test
    void aPositionRefreshThroughTheLiveDerivationEqualsAFullDerive() {
        EditableModel m = model(5, 5);
        EditCostStats.reset();
        EditCostStats.setEnabled(true);
        assertTrue(m.scaleFaces(new int[] {3, 4, 8, 9}, 1.7f, null));
        assertEquals(0, EditCostStats.totalCalls(EditCostStats.Span.RENDER_MESH), "no new derivation");
        assertEquals(1, EditCostStats.totalCalls(EditCostStats.Span.MOVE_REFRESH));
        assertEqualsFullDerive("scale", m);
    }

    @Test
    void aReleasedDerivationFallsBackToTheFullRefresh() {
        EditableModel m = model(4, 4);
        MeshRebuildPipeline p = pipeline(m);
        p.releaseNative();
        assertFalse(p.isDerivationLive());
        m.moveVertices(new int[] {6}, new float[] {1.3f, 1.4f, 0.9f});
        EditCostStats.reset();
        EditCostStats.setEnabled(true);
        m.commitVertexPositions();
        assertEquals(1, EditCostStats.totalCalls(EditCostStats.Span.DERIVE), "the commit derives in full");
        assertTrue(p.isDerivationLive());
        assertEqualsFullDerive("fallback", m);
    }

    @Test
    void anImportDerivesOnItsOwnNativeMeshLikeAFreshDerive() {
        OMOFormat.MeshData g = grid(6, 4);
        FaceTextureManager ftm = new FaceTextureManager();
        ftm.registerMaterial(new MaterialDefinition(301, "M", 0, MaterialDefinition.RenderLayer.OPAQUE,
                MaterialDefinition.MaterialProperties.NONE));
        for (int f = 0; f < 24; f += 2) ftm.assignDefaultMapping(f, 301);
        MeshImporter.Imported imported = MeshImporter.importForDerive(g.vertices(), g.texCoords(), g.indices(),
                g.triangleToFaceId());
        MeshImporter.ImportResult plain = MeshImporter.importSoup(g.vertices(), g.texCoords(), g.indices(),
                g.triangleToFaceId());
        assertTrue(plain.mesh().contentEquals(imported.result().mesh()));
        assertEquals(Arrays.deepToString(plain.vertexIdToSoupIndices()),
                Arrays.deepToString(imported.result().vertexIdToSoupIndices()));
        try (RenderMeshBuilder.Derivation a = RenderMeshBuilder.deriveImported(imported.nativeMesh(),
                     imported.result().mesh(), ftm);
             RenderMeshBuilder.Derivation b = RenderMeshBuilder.derive(plain.mesh(), ftm)) {
            assertArrayEquals(b.renderMesh().vertices(), a.renderMesh().vertices());
            assertArrayEquals(b.renderMesh().texCoords(), a.renderMesh().texCoords());
            assertArrayEquals(b.renderMesh().indices(), a.renderMesh().indices());
            assertArrayEquals(b.renderMesh().triangleToFaceId(), a.renderMesh().triangleToFaceId());
            assertArrayEquals(b.renderMesh().cornerToVertexId(), a.renderMesh().cornerToVertexId());
            assertEquals(b.topology().getEdgeCount(), a.topology().getEdgeCount());
            for (int e = 0; e < b.topology().getEdgeCount(); e++) {
                assertEquals(b.topology().getEdge(e).vertexA(), a.topology().getEdge(e).vertexA());
                assertEquals(b.topology().getEdge(e).vertexB(), a.topology().getEdge(e).vertexB());
                assertEquals(b.topology().getDihedralAngle(e), a.topology().getDihedralAngle(e));
            }
            for (int f = 0; f < 24; f++) {
                assertEquals(b.topology().getFaceNormal(f), a.topology().getFaceNormal(f));
            }
        }
    }

    @Test
    void aPartRebakeImportsOnceAndDerivesOnce() {
        EditableModel m = model(4, 3);
        EditCostStats.reset();
        EditCostStats.setEnabled(true);
        m.getPartManager().refreshCombinedMesh();
        assertEquals(1, EditCostStats.totalCalls(EditCostStats.Span.IMPORT_SOUP));
        assertEquals(1, EditCostStats.totalCalls(EditCostStats.Span.DERIVE));
        assertEquals(1, EditCostStats.totalCalls(EditCostStats.Span.RENDER_MESH), "one derivation, one topology");
        assertEquals(1, EditCostStats.totalCalls(EditCostStats.Span.TOPOLOGY));
        assertTrue(pipeline(m).isDerivationLive());
        assertEqualsFullDerive("rebake", m);
    }

    @Test
    void theBatchedAuthoredUvCheckEqualsThePerFaceCheck() {
        OMOFormat.MeshData g = grid(5, 4);
        // Authored UVs: half are the projection (what the editor saves), half are not.
        EditableMesh mesh = MeshImporter.importSoup(g.vertices(), g.texCoords(), g.indices(),
                g.triangleToFaceId()).mesh();
        List<EditableFace> faces = new ArrayList<>(mesh.faces());
        List<FaceTextureMapping> mappings = new ArrayList<>();
        FaceTextureMapping.UVRotation[] rot = FaceTextureMapping.UVRotation.values();
        EditableMesh authored = new EditableMesh();
        for (int v = 0; v < mesh.vertexCount(); v++) authored.addVertex(mesh.position(v));
        for (int i = 0; i < faces.size(); i++) {
            EditableFace face = faces.get(i);
            FaceTextureMapping mapping = new FaceTextureMapping(face.faceId(), 302,
                    new FaceTextureMapping.UVRegion(0.1f * (i % 4), 0f, 0.5f + 0.1f * (i % 4), 0.5f), rot[i % 4]);
            mappings.add(mapping);
            float[] uvs;
            try (RenderMeshBuilder.Derivation d = single(mesh, face, mapping)) {
                uvs = d.renderMesh().texCoords().clone();
            }
            if (i % 2 == 1) uvs[0] += 0.01f;
            if (i % 5 == 0) uvs[1] += 1e-5f; // within tolerance
            authored.addFaceWithId(face.faceId(), face.loop(), uvs);
        }
        List<EditableFace> authoredFaces = new ArrayList<>(authored.faces());
        boolean[] batch = RenderMeshBuilder.authoredUVsMatchProjection(authored, authoredFaces, mappings, 2.5e-4f);
        for (int i = 0; i < authoredFaces.size(); i++) {
            assertEquals(RenderMeshBuilder.authoredUVsMatchProjection(authored, authoredFaces.get(i),
                    mappings.get(i), 2.5e-4f), batch[i], "face " + i);
            assertEquals(i % 2 == 0, batch[i], "face " + i);
        }
    }

    /** The face alone, derived with its mapping (no authored UVs). */
    private static RenderMeshBuilder.Derivation single(EditableMesh mesh, EditableFace face, FaceTextureMapping m) {
        EditableMesh one = new EditableMesh();
        int[] loop = new int[face.loopLength()];
        for (int k = 0; k < loop.length; k++) loop[k] = one.addVertex(mesh.position(face.vertexAt(k)));
        one.addFaceWithId(face.faceId(), loop);
        FaceTextureManager ftm = new FaceTextureManager();
        ftm.setFaceMapping(m);
        return RenderMeshBuilder.derive(one, ftm);
    }

    @Test
    void dirtyRangesMergeAndCap() {
        MeshGpuState s = new MeshGpuState();
        s.clear();
        s.cornersChanged(new int[] {10, 5, 40, 2}, false);
        s.cornersChanged(new int[] {14, 4, 100, 1}, true);
        assertArrayEquals(new int[] {10, 8, 40, 2, 100, 1}, s.positionSpans());
        assertArrayEquals(new int[] {14, 4, 100, 1}, s.uvSpans());
        s.positionsChanged();
        assertEquals(null, s.positionSpans(), "a whole-buffer change wins");
        s.cornersChanged(new int[] {1, 1}, false);
        assertEquals(null, s.positionSpans(), "and stays whole until the sync");
        s.clear();
        int[] many = new int[40];
        for (int i = 0; i < 20; i++) {
            many[i * 2] = i * 10;
            many[i * 2 + 1] = 1;
        }
        s.cornersChanged(many, false);
        s.cornersChanged(new int[] {500, 3}, false);
        assertArrayEquals(new int[] {0, 503}, s.positionSpans(), "too many ranges collapse to one");
    }
}
