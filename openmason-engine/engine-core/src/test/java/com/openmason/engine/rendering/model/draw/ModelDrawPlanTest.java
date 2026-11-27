package com.openmason.engine.rendering.model.draw;

import com.openmason.engine.entity.model.MaterialImage;
import com.openmason.engine.entity.model.EntityFace;
import com.openmason.engine.entity.model.EntityGeometry;
import com.openmason.engine.entity.model.EntityPart;
import com.openmason.engine.rendering.model.IndexedDrawBatch;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GL-free draw plans: entity part batching and the editor's material-sorted soup. */
class ModelDrawPlanTest {

    private static final MaterialImage IMAGE = new MaterialImage(1, 1, new byte[]{1, 2, 3, -1});

    private static EntityPart part(String id, EntityFace... faces) {
        return new EntityPart(id, id, null, new Vector3f(), new Vector3f(), new Vector3f(),
                new Vector3f(1), List.of(faces));
    }

    @Test
    void entityPlanCoalescesWithinEachPartAndSkipsOnlyFacesWithoutAnImage() {
        EntityPart first = part("head", new EntityFace(0, 7, 0, 6), new EntityFace(1, 7, 6, 3),
                new EntityFace(2, 99, 9, 3), new EntityFace(3, 7, 12, 6), new EntityFace(4, 8, 18, 3));
        EntityPart second = part("jaw", new EntityFace(5, 8, 21, 3));
        var geometry = new EntityGeometry(new float[0], new float[0], new int[24],
                List.of(first, second), Map.of(7, IMAGE, 8, IMAGE, 123, IMAGE), 0, List.of());

        ModelDrawPlan plan = ModelDrawPlan.of(geometry);

        assertEquals(2, plan.partCount());
        assertEquals(List.of(new IndexedDrawBatch(7, 0, 9), new IndexedDrawBatch(7, 12, 6),
                new IndexedDrawBatch(8, 18, 3)), plan.forPart(0).textured());
        assertEquals(List.of(new IndexedDrawBatch(0, 0, 21)), plan.forPart(0).untextured());
        assertEquals(List.of(new IndexedDrawBatch(8, 21, 3)), plan.forPart(1).textured());
        assertEquals(List.of(new IndexedDrawBatch(0, 21, 3)), plan.forPart(1).untextured());
        // Material 123 has an image but no face uses it: nothing to upload.
        assertEquals(Set.of(7, 8), plan.materialIds());
    }

    @Test
    void entityPlanKeepsSparseOutOfOrderRangesInAuthoredOrder() {
        EntityPart sparse = part("sparse", new EntityFace(0, -1, 9, 3), new EntityFace(1, -1, 0, 3),
                new EntityFace(2, -1, 6, 3));
        var geometry = new EntityGeometry(new float[0], new float[0], new int[12],
                List.of(sparse), Map.of(), 0, List.of());

        ModelDrawPlan plan = ModelDrawPlan.of(geometry);

        assertTrue(plan.forPart(0).textured().isEmpty());
        assertEquals(List.of(new IndexedDrawBatch(0, 9, 3), new IndexedDrawBatch(0, 0, 3),
                new IndexedDrawBatch(0, 6, 3)), plan.forPart(0).untextured());
    }

    @Test
    void entityPlanRejectsRangesBeyondTheIndexBuffer() {
        var geometry = new EntityGeometry(new float[0], new float[0], new int[3],
                List.of(part("bad", new EntityFace(0, 1, 0, 6))), Map.of(1, IMAGE), 0, List.of());
        assertThrows(IllegalArgumentException.class, () -> ModelDrawPlan.of(geometry));
    }

    @Test
    void unknownPartIndexDrawsNothing() {
        var geometry = new EntityGeometry(new float[0], new float[0], new int[3],
                List.of(part("one", new EntityFace(0, 1, 0, 3))), Map.of(1, IMAGE), 0, List.of());
        ModelDrawPlan plan = ModelDrawPlan.of(geometry);
        assertTrue(plan.forPart(5).textured().isEmpty());
        assertTrue(plan.forPart(-1).untextured().isEmpty());
    }

    @Test
    void materialSortGroupsTrianglesByKeyInFirstAppearanceOrder() {
        // Four triangles keyed B, A, B, A -> batches B (tris 0,2) then A (tris 1,3).
        int[] indices = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
        int[] keys = {20, 10, 20, 10};

        ModelDrawPlan.Sorted sorted = ModelDrawPlan.materialSorted(indices, keys);

        assertArrayEquals(new int[]{0, 1, 2, 6, 7, 8, 3, 4, 5, 9, 10, 11}, sorted.indices());
        assertEquals(List.of(new IndexedDrawBatch(20, 0, 6), new IndexedDrawBatch(10, 6, 6)),
                sorted.plan().forPart(0).textured());
        // The untextured list covers the whole buffer in one contiguous batch.
        assertEquals(List.of(new IndexedDrawBatch(0, 0, 12)), sorted.plan().forPart(0).untextured());
        assertEquals(Set.of(10, 20), sorted.plan().materialIds());
        assertArrayEquals(new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11}, indices, "input untouched");
    }

    @Test
    void singleKeySoupIsOneBatchInOriginalOrder() {
        int[] indices = {2, 1, 0, 5, 4, 3};
        ModelDrawPlan.Sorted sorted = ModelDrawPlan.materialSorted(indices, new int[2]);
        assertArrayEquals(indices, sorted.indices());
        assertEquals(List.of(new IndexedDrawBatch(0, 0, 6)), sorted.plan().forPart(0).textured());
    }

    @Test
    void emptySoupYieldsAnEmptyPlan() {
        ModelDrawPlan.Sorted sorted = ModelDrawPlan.materialSorted(new int[0], new int[0]);
        assertEquals(0, sorted.indices().length);
        assertTrue(sorted.plan().forPart(0).textured().isEmpty());
    }

    @Test
    void materialSortRejectsMissingKeysAndPartialTriangles() {
        assertThrows(IllegalArgumentException.class,
                () -> ModelDrawPlan.materialSorted(new int[]{0, 1, 2, 3, 4, 5}, new int[1]));
        assertThrows(IllegalArgumentException.class,
                () -> ModelDrawPlan.materialSorted(new int[]{0, 1}, new int[1]));
    }

    @Test
    void theSortOrderIsTheOrderMaterialSortedUploads() {
        java.util.Random r = new java.util.Random(3);
        int tris = 500;
        int[] indices = new int[tris * 3];
        int[] keys = new int[tris];
        for (int i = 0; i < indices.length; i++) indices[i] = r.nextInt(1000);
        for (int t = 0; t < tris; t++) keys[t] = r.nextInt(5) * 7;
        int[] sorted = ModelDrawPlan.materialSorted(indices, keys).indices();
        int[] order = ModelDrawPlan.materialSortOrder(keys, tris);
        for (int slot = 0; slot < tris; slot++) {
            for (int k = 0; k < 3; k++) {
                assertEquals(indices[order[slot] * 3 + k], sorted[slot * 3 + k], "slot " + slot);
            }
        }
    }
}
