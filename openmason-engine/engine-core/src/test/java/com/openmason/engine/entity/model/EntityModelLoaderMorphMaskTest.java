package com.openmason.engine.entity.model;

import com.openmason.engine.format.omo.CendaOmo;
import com.openmason.engine.format.omo.MaskPlan;
import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.format.omo.OMOReader;
import com.openmason.engine.format.omo.OmoDocument;
import com.openmason.engine.rendering.model.IndexedDrawBatch;
import com.openmason.engine.rendering.model.draw.ModelAppearance;
import com.openmason.engine.rendering.model.draw.ModelDrawPlan;
import com.openmason.engine.rig.CendaRig;
import com.openmason.engine.rig.RigMorphs;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * OMO 1.9 shape keys (#171) and vertex groups (#172) at load: {@link EntityModelLoader} turns the part-local morph rows
 * into the rig's morph targets and the group rows into a {@link MaskPlan}, both THROUGH the split of vertices two
 * parts share (a copy moves and masks like its source); hiding a group removes exactly the triangles wholly inside it;
 * a model without either loads exactly as before.
 */
class EntityModelLoaderMorphMaskTest {

    @BeforeAll
    static void needsNative() {
        assumeTrue(CendaOmo.isAvailable(), () -> "native model module: " + CendaOmo.unavailableReason());
        assumeTrue(CendaRig.isAvailable(), () -> "native rig: " + CendaRig.unavailableReason());
        assumeTrue(MaskPlan.isAvailable(), () -> "native masks: " + MaskPlan.unavailableReason());
    }

    // Part "a" = vertices 0..3 (quad: t0 0,1,2 / t1 0,2,3, face 0); part "b" = vertices 4..7 (face 1: t2 4,5,6 and
    // t3 4,6,2 - it borrows a's vertex 2, which the loader splits into a copy for b).
    private static final float[] POSITIONS = {
            0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0,
            2, 0, 0, 3, 0, 0, 3, 1, 0, 2, 1, 0};
    private static final int[] INDICES = {0, 1, 2, 0, 2, 3, 4, 5, 6, 4, 6, 2};

    private static OMOReader.ReadResult model(boolean withMorphsAndGroups) throws Exception {
        try (OmoDocument doc = OmoDocument.create()) {
            doc.setObjectName("shirt-test");
            doc.setModelType("custom");
            doc.setGeometry(new OMOFormat.GeometryData(1, 1, 1, new OMOFormat.Position(0, 0, 0)));
            doc.setMesh(new OMOFormat.MeshData(POSITIONS, new float[16], INDICES, new int[]{0, 0, 1, 1}, null));
            doc.setParts(List.of(
                    new OMOFormat.PartEntry("a", "torso", 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 0, 4, 0, 6, 0, 1, true, false),
                    new OMOFormat.PartEntry("b", "head", 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 4, 4, 6, 6, 1, 1, true, false)));
            if (withMorphsAndGroups) {
                doc.setMorphs(List.of(new OMOFormat.MorphData("bulge", 0f, 0f, 1f, List.of(
                        new OMOFormat.MorphPart("a", new int[]{2}, new float[]{0, 0, 1}, null)))));
                doc.setVertexGroups(List.of(
                        new OMOFormat.VertexGroupData("covered_shirt", List.of(
                                new OMOFormat.GroupPart("a", new int[]{0, 1, 2, 3}, new float[]{1, 1, 1, 1}))),
                        new OMOFormat.VertexGroupData("face", List.of(
                                new OMOFormat.GroupPart("b", new int[]{0, 1, 2}, new float[]{1, 1, 1})))));
            }
            return new OMOReader().read(doc.write());
        }
    }

    @Test
    void aModelWithoutShapeKeysOrGroupsLoadsAsBefore() throws Exception {
        EntityGeometry g = EntityModelLoader.buildGeometry(model(false));
        assertNull(g.morphs());
        assertNull(g.maskPlan());
        assertEquals(0, g.rig().rig().morphCount());
        // The split only rewrote b's use of vertex 2 (to the copy 8); nothing else moved.
        assertArrayEquals(new int[]{0, 1, 2, 0, 2, 3, 4, 5, 6, 4, 6, 8}, g.indices());
        ModelDrawPlan plan = ModelDrawPlan.of(g);
        assertSame(plan, plan.forAppearance(new ModelAppearance().setHiddenGroups(List.of("covered_shirt"))),
                "no mask plan: groups are ignored");
    }

    @Test
    void morphRowsFollowTheSplitCopy() throws Exception {
        EntityGeometry g = EntityModelLoader.buildGeometry(model(true));
        assertEquals(9, g.vertices().length / 3, "vertex 2 split for part b");
        RigMorphs m = g.morphs();
        assertNotNull(m);
        assertEquals(9, m.vertexCount());
        assertEquals(List.of("bulge"), m.targets().stream().map(RigMorphs.Target::name).toList());
        assertArrayEquals(new int[]{2, 8}, m.targetRows(0).vertices(), "the copy carries its source's delta");
        assertEquals(1, g.rig().rig().morphCount(), "the targets are the rig's channels");
        float[] out = new float[27];
        m.apply(new float[]{1f}, g.vertices(), null, out, null);
        assertEquals(1f, out[2 * 3 + 2]);
        assertEquals(1f, out[8 * 3 + 2]);
    }

    @Test
    void groupsMaskWholeTrianglesThroughTheSplit() throws Exception {
        EntityGeometry g = EntityModelLoader.buildGeometry(model(true));
        MaskPlan plan = g.maskPlan();
        assertNotNull(plan);
        assertEquals(List.of("covered_shirt", "face"), plan.groupNames());
        // Triangles only moved inside their part: every face's index range still holds the same triangles.
        assertEquals(Set.of(tri(0, 1, 2), tri(0, 2, 3)), trianglesIn(g.indices(), 0, 6));
        assertEquals(Set.of(tri(4, 5, 6), tri(4, 6, 8)), trianglesIn(g.indices(), 6, 12));

        ModelDrawPlan draw = ModelDrawPlan.of(g);
        // Hiding the shirt's covered skin removes a's two triangles (all their vertices are in it). b's t3 touches the
        // copy 8 (in covered_shirt through its source) but has vertices outside: it stays - no gap at the hem.
        assertEquals(Set.of(tri(4, 5, 6), tri(4, 6, 8)), drawn(g, draw, hide("covered_shirt")));
        // "face" holds 4, 5, 6: only t2 is wholly inside.
        assertEquals(Set.of(tri(0, 1, 2), tri(0, 2, 3), tri(4, 6, 8)), drawn(g, draw, hide("face")));
        assertEquals(Set.of(tri(4, 6, 8)), drawn(g, draw, hide("covered_shirt", "face")));
        // KEEP = Blender's Mask modifier: only triangles inside the group.
        assertEquals(Set.of(tri(4, 5, 6)), drawn(g, draw, new ModelAppearance().setKeptGroups(List.of("face"))));
        // Unknown names change nothing; no groups = the plan itself.
        assertEquals(drawn(g, draw, null), drawn(g, draw, hide("hat")));
        assertSame(draw, draw.forAppearance(new ModelAppearance()));
    }

    @Test
    void masksAreResolvedOncePerDistinctGroupSet() throws Exception {
        EntityGeometry g = EntityModelLoader.buildGeometry(model(true));
        ModelDrawPlan draw = ModelDrawPlan.of(g);
        ModelAppearance a = hide("covered_shirt");
        ModelDrawPlan first = draw.forAppearance(a);
        assertSame(first, draw.forAppearance(a), "cached per appearance");
        assertSame(first, draw.forAppearance(hide("covered_shirt")), "and per group set (another body, same outfit)");
        assertSame(a.groupMask(), a.setHiddenGroups(List.of("covered_shirt")).groupMask(), "unchanged = same key");
        assertTrue(first != draw.forAppearance(a.setHiddenGroups(List.of("face"))));
    }

    private static ModelAppearance hide(String... groups) {
        return new ModelAppearance().setHiddenGroups(List.of(groups));
    }

    private static List<Integer> tri(int a, int b, int c) {
        return List.of(a, b, c);
    }

    private static Set<List<Integer>> trianglesIn(int[] indices, int from, int to) {
        Set<List<Integer>> out = new HashSet<>();
        for (int i = from; i < to; i += 3) out.add(List.of(indices[i], indices[i + 1], indices[i + 2]));
        return out;
    }

    /** The triangles the untextured (flat / shadow) batches of every part draw for {@code appearance}. */
    private static Set<List<Integer>> drawn(EntityGeometry g, ModelDrawPlan plan, ModelAppearance appearance) {
        ModelDrawPlan p = plan.forAppearance(appearance);
        Set<List<Integer>> out = new HashSet<>();
        List<IndexedDrawBatch> all = new ArrayList<>();
        for (int i = 0; i < p.partCount(); i++) all.addAll(p.forPart(i).untextured());
        for (IndexedDrawBatch b : all) {
            out.addAll(trianglesIn(g.indices(), b.indexStart(), b.indexStart() + b.indexCount()));
        }
        int count = all.stream().mapToInt(IndexedDrawBatch::indexCount).sum();
        assertEquals(out.size() * 3, count, "no triangle drawn twice: " + Arrays.toString(g.indices()));
        return out;
    }
}
