package com.openmason.main.systems.skeleton.vertexdata;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.modeling.parts.MeshRange;
import com.openmason.engine.modeling.parts.ModelPartDescriptor;
import com.openmason.engine.modeling.parts.ModelPartManager;
import com.openmason.engine.modeling.parts.PartMeshRebuilder;
import com.openmason.engine.modeling.parts.PartTransform;
import com.openmason.engine.rig.CendaRig;
import com.openmason.engine.rig.RigMorphs;
import com.openmason.main.systems.rendering.model.io.omo.OmoVertexDataCarry;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The editor's shape keys (#171) and vertex groups (#172): edits + undo/redo, rows that follow topology edits
 * (nearest vertex, natively) and deleted parts, capture from vertex moves in MODEL space, selection mapping, the
 * save carry, the preview state and the soup targets the scene viewport will upload. Two strip parts, every vertex
 * at a distinct position (no coincident split vertices, so nearest-vertex ties cannot blur the assertions).
 */
class VertexDataAuthoringTest {

    private ModelPartManager parts;
    private VertexDataAuthoring data;
    private String a;
    private String b;

    /** A strip of n quads along +x: 2(n+1) distinct vertices. */
    private static PartMeshRebuilder.PartGeometry strip(int n, float y0) {
        float[] v = new float[(n + 1) * 2 * 3];
        for (int i = 0; i <= n; i++) {
            v[i * 6] = i;
            v[i * 6 + 1] = y0;
            v[i * 6 + 3] = i;
            v[i * 6 + 4] = y0 + 1;
        }
        int[] idx = new int[n * 6];
        int[] faces = new int[n * 2];
        for (int i = 0; i < n; i++) {
            int b0 = i * 2;
            idx[i * 6] = b0;
            idx[i * 6 + 1] = b0 + 2;
            idx[i * 6 + 2] = b0 + 1;
            idx[i * 6 + 3] = b0 + 1;
            idx[i * 6 + 4] = b0 + 2;
            idx[i * 6 + 5] = b0 + 3;
            faces[i * 2] = i;
            faces[i * 2 + 1] = i;
        }
        return PartMeshRebuilder.PartGeometry.of(v, new float[v.length / 3 * 2], idx, faces);
    }

    @BeforeEach
    void setUp() {
        parts = new ModelPartManager();
        a = parts.addPartFromGeometry("a", strip(2, 0f), new Vector3f()).id();
        b = parts.addPartFromGeometry("b", strip(1, 5f), new Vector3f()).id();
        data = new VertexDataAuthoring(parts);
    }

    private static OMOFormat.MorphPart rows(String part, int[] v, float... d) {
        return new OMOFormat.MorphPart(part, v, d, null);
    }

    @Test
    void groupsEditUndoAndRedo() {
        VertexDataHistory h = data.history();
        int[] dirty = {0};
        h.setOnChange(() -> dirty[0]++);
        data.edit("New", () -> data.groups().add("Skin"));
        data.edit("Assign", () -> data.groups().assign("Skin", a, new int[] {0, 1, 2}, 0.5f, null));
        data.edit("Assign", () -> data.groups().assign("skin", a, new int[] {2}, 2f, null)); // clamped, replaces
        Map<Integer, Float> rows = VertexGroupStore.rowsOf(data.groups().get("Skin"), a);
        assertEquals(Map.of(0, 0.5f, 1, 0.5f, 2, 1f), rows);
        data.edit("Unassign", () -> data.groups().unassign("Skin", a, new int[] {1}));
        assertEquals(2, VertexGroupStore.rowCount(data.groups().get("Skin")));
        data.renameGroup("Skin", "Torso");
        assertNull(data.groups().get("Skin"));
        assertEquals(5, dirty[0]);

        assertThrows(IllegalArgumentException.class, () -> data.edit("dup", () -> data.groups().add("torso")));
        assertEquals(5, dirty[0], "a refused edit records nothing");

        assertTrue(h.undo());
        assertNotNull(data.groups().get("Skin"));
        assertTrue(h.undo());
        assertEquals(3, VertexGroupStore.rowCount(data.groups().get("Skin")));
        assertTrue(h.redo());
        assertTrue(h.redo());
        assertNotNull(data.groups().get("Torso"));
        while (h.undo()) { }
        assertTrue(data.groups().isEmpty());
    }

    @Test
    void shapeKeysEditUndoAndRedo() {
        data.edit("Add", () -> data.shapeKeys().add("Smile", 0f, 0f, 1f, List.of(rows(a, new int[] {1}, 0, 1, 0)), false));
        data.edit("Add", () -> data.shapeKeys().add("Frown", 0f, -1f, 1f, List.of(), false));
        // Merge: vertex 1 overwritten, vertex 3 added; a zero row is dropped (sparse).
        data.edit("Rows", () -> data.shapeKeys().setPartRows("Smile",
                rows(a, new int[] {1, 3, 4}, 0, 2, 0, 0, 0, 1, 0, 0, 0), true));
        OMOFormat.MorphPart part = data.shapeKeys().get("Smile").parts().get(0);
        assertArrayEquals(new int[] {1, 3}, part.vertices());
        assertArrayEquals(new float[] {0, 2, 0, 0, 0, 1}, part.deltas());
        data.edit("Params", () -> data.shapeKeys().setParams("Frown", 0.5f, null, 2f));
        data.edit("Move", () -> data.shapeKeys().move("Frown", 0));
        assertEquals("Frown", data.shapeKeys().morphs().get(0).name());
        data.preview().setValue("Smile", 0.7f);
        data.renameShapeKey("Smile", "Grin");
        assertEquals(0.7f, data.preview().value(data.shapeKeys().get("Grin")), "the preview follows a rename");

        assertThrows(IllegalArgumentException.class, () -> data.shapeKeys().setParams("Frown", null, 3f, 2f));
        assertThrows(IllegalArgumentException.class, () -> data.shapeKeys().add("Basis", 0, 0, 1, List.of(), false));
        assertThrows(IllegalArgumentException.class, () -> data.shapeKeys().add("grin", 0, 0, 1, List.of(), false));

        VertexDataHistory h = data.history();
        assertTrue(h.undo()); // rename
        assertNotNull(data.shapeKeys().get("Smile"));
        assertFalse(data.preview().hasValue("Grin"), "undo forgets the preview of a name that is gone");
        assertTrue(h.undo()); // move
        assertEquals("Smile", data.shapeKeys().morphs().get(0).name());
        assertTrue(h.undo()); // params
        assertEquals(1f, data.shapeKeys().get("Frown").max());
        assertTrue(h.redo());
        assertEquals(2f, data.shapeKeys().get("Frown").max());
        data.edit("Remove", () -> data.shapeKeys().remove("Frown"));
        assertFalse(h.canRedo(), "a new edit drops the redo branch");
        assertEquals(1, data.shapeKeys().size());
    }

    @Test
    void rowsFollowTopologyEditsAndDeletedParts() {
        assumeTrue(CendaRig.isAvailable(), "native rig unavailable");
        data.edit("Add", () -> data.shapeKeys().add("Lift", 0f, 0f, 1f,
                List.of(rows(a, new int[] {5}, 0, 1, 0), rows(b, new int[] {0}, 1, 0, 0)), false));
        data.edit("Group", () -> {
            data.groups().add("Tip");
            data.groups().assign("Tip", a, new int[] {4, 5}, 0.25f, null);
        });
        assertTrue(data.history().canUndo());

        // An edit that inserts a vertex right next to vertex 5 (2, 1) and puts it FIRST: every index shifts by one.
        float[] old = parts.getPartGeometry(a).vertices();
        float[] moved = new float[old.length + 3];
        moved[0] = 2.05f;
        moved[1] = 1.02f;
        System.arraycopy(old, 0, moved, 3, old.length);
        int[] idx = parts.getPartGeometry(a).indices().clone();
        for (int i = 0; i < idx.length; i++) idx[i]++;
        assertTrue(parts.replacePartGeometry(a, PartMeshRebuilder.PartGeometry.of(moved, new float[moved.length / 3 * 2],
                idx, parts.getPartGeometry(a).triangleToFaceId())));

        assertTrue(data.reconcile());
        OMOFormat.MorphPart lift = data.shapeKeys().get("Lift").parts().stream()
                .filter(p -> p.partId().equals(a)).findFirst().orElseThrow();
        assertArrayEquals(new int[] {0, 6}, lift.vertices(), "old 5 is new 6, and the new vertex took its row");
        assertEquals(Map.of(0, 0.25f, 5, 0.25f, 6, 0.25f), VertexGroupStore.rowsOf(data.groups().get("Tip"), a));
        assertFalse(data.history().canUndo(), "snapshots of the old vertex order are dropped");
        assertFalse(data.reconcile(), "nothing changed since");

        assertTrue(parts.removePart(b));
        data.reconcile();
        assertTrue(data.shapeKeys().get("Lift").parts().stream().noneMatch(p -> p.partId().equals(b)));
        assertNotNull(data.shapeKeys().get("Lift"), "a key outlives the part (drivers name it)");
    }

    @Test
    void aPartThatJustGotDataIsRemappedOnItsFirstTopologyEdit() {
        assumeTrue(CendaRig.isAvailable(), "native rig unavailable");
        data.edit("Group", () -> {
            data.groups().add("G");
            data.groups().assign("G", b, new int[] {3}, 1f, null);
        });
        float[] old = parts.getPartGeometry(b).vertices();
        float[] grown = java.util.Arrays.copyOf(old, old.length + 3);
        grown[old.length] = 1.01f;
        grown[old.length + 1] = 6.01f;
        assertTrue(parts.replacePartGeometry(b, PartMeshRebuilder.PartGeometry.of(grown, new float[grown.length / 3 * 2],
                parts.getPartGeometry(b).indices(), parts.getPartGeometry(b).triangleToFaceId())));
        assertEquals(Map.of(3, 1f, 4, 1f), VertexGroupStore.rowsOf(data.groups().get("G"), b));
    }

    @Test
    void captureRecordsModelSpaceMovesAndGivesTheBasisBack() {
        // Part a is scaled 2x and moved: a local move of 0.5 is a model-space delta of 1.
        parts.setPartTransform(a, new PartTransform(new Vector3f(), new Vector3f(3, 0, 0), new Vector3f(),
                new Vector3f(2, 2, 2)));
        assertThrows(IllegalStateException.class, () -> data.captureDeltas());
        data.beginCapture();
        assertTrue(data.isCapturing());
        float[] before = parts.getPartGeometry(a).vertices().clone();
        assertTrue(parts.updatePartVertex(a, 4, 2f, 0.5f, 0f) > 0);
        List<OMOFormat.MorphPart> rows = data.captureDeltas();
        assertEquals(1, rows.size());
        assertArrayEquals(new int[] {4}, rows.get(0).vertices());
        assertArrayEquals(new float[] {0, 1, 0}, rows.get(0).deltas(), 1e-6f);

        Map<String, PartMeshRebuilder.PartGeometry> basis = data.basisGeometry();
        assertEquals(Set.of(a), basis.keySet());
        assertArrayEquals(before, basis.get(a).vertices());

        int index = data.finishCapture("Raise", false);
        assertFalse(data.isCapturing());
        assertEquals("Raise", data.shapeKeys().morphs().get(index).name());
        assertTrue(data.history().undo());
        assertTrue(data.shapeKeys().isEmpty());

        data.beginCapture();
        assertThrows(IllegalStateException.class, () -> data.finishCapture("Nothing", false), "no vertex moved");
        data.cancelCapture();
        assertFalse(data.isCapturing());
    }

    @Test
    void selectionMapsThroughTheSoupBothWays() {
        MeshRange rb = parts.getPartById(b).map(ModelPartDescriptor::meshRange).orElseThrow();
        // Identity bridge: unique u = soup vertex u (the welded editor would map several soups to one unique).
        int total = rb.vertexStart() + rb.vertexCount();
        VertexDataAuthoring.IndexBridge bridge = new VertexDataAuthoring.IndexBridge() {
            @Override
            public int uniqueCount() {
                return total;
            }

            @Override
            public int[] soupOfUnique(int unique) {
                return new int[] {unique};
            }
        };
        Map<String, int[]> local = data.localVertices(List.of(1, rb.vertexStart() + 2, 0), bridge);
        assertArrayEquals(new int[] {0, 1}, local.get(a));
        assertArrayEquals(new int[] {2}, local.get(b));

        data.edit("G", () -> {
            data.groups().add("G");
            data.groups().assign("G", b, new int[] {2, 3}, 1f, null);
            data.groups().assign("G", a, new int[] {0}, 0f, null); // weight 0: not a member
        });
        assertEquals(Set.of(rb.vertexStart() + 2, rb.vertexStart() + 3), data.uniqueVertices("G", bridge));
    }

    @Test
    void theCarryAndThePreviewSeeTheEdits() {
        assumeTrue(CendaRig.isAvailable(), "native rig unavailable");
        data.edit("K", () -> data.shapeKeys().add("K", 0.25f, 0f, 1f, List.of(rows(b, new int[] {1}, 0, 0, 1)), false));
        data.edit("G", () -> {
            data.groups().add("G");
            data.groups().assign("G", a, new int[] {2}, 1f, null);
        });
        OmoVertexDataCarry carry = data.carry();
        assertEquals(data.shapeKeys().morphs(), carry.morphs());
        assertEquals(data.groups().groups(), carry.groups());

        VertexDataPreview p = data.preview();
        long rev = p.revision();
        assertArrayEquals(new float[] {0.25f}, p.weights(data.shapeKeys().morphs()), "unset = the default");
        p.setValue("K", 0.9f);
        p.setHidden("G", true);
        assertTrue(p.revision() > rev);
        assertArrayEquals(new float[] {0.9f}, p.weights(data.shapeKeys().morphs()));
        assertEquals(Set.of("G"), p.hiddenGroups());

        try (RigMorphs soup = data.soupMorphs()) {
            assertNotNull(soup);
            MeshRange rb = parts.getPartById(b).map(ModelPartDescriptor::meshRange).orElseThrow();
            assertEquals(rb.vertexStart() + rb.vertexCount(), soup.vertexCount());
            RigMorphs.Rows r = soup.targetRows(0);
            assertArrayEquals(new int[] {rb.vertexStart() + 1}, r.vertices(), "part-local rows land on the soup");
        }

        data.edit("Remove", () -> data.groups().remove("G"));
        assertTrue(p.hiddenGroups().isEmpty(), "a removed group is no longer hidden");
        data.clear();
        assertTrue(data.shapeKeys().isEmpty());
        assertFalse(data.history().canUndo());
    }
}
