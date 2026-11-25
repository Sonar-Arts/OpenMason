package com.openmason.main.systems.skeleton;

import com.openmason.engine.rig.CendaRig;
import com.openmason.engine.rig.Skin;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Skins follow mesh edits (nearest-vertex transfer from the last positions seen) and part deletion. */
class SkinStoreReconcileTest {

    @Test
    void anEditedPartKeepsItsWeightsAndADeletedPartLosesItsSkin() {
        assumeTrue(CendaRig.isAvailable(), "native rig unavailable");
        SkinStore store = new SkinStore();
        int bone = store.jointIndex("bone");
        float[] before = {0, 0, 0, 0, 1, 0, 0, 2, 0};
        Skin skin = store.ensureSkin("arm", before, null);
        skin.set(2, new int[]{bone}, new float[]{1f});

        int[] fired = {0};
        store.addListener(() -> fired[0]++);
        Map<String, float[]> mesh = new HashMap<>();
        mesh.put("arm", before);
        assertFalse(store.reconcile(mesh::get), "nothing changed");

        // A subdivide-like edit: a new vertex next to the weighted one.
        float[] after = {0, 0, 0, 0, 1, 0, 0, 2, 0, 0, 1.9f, 0};
        mesh.put("arm", after);
        assertTrue(store.reconcile(mesh::get));
        Skin moved = store.skinOf("arm");
        assertEquals(4, moved.vertexCount());
        assertArrayEquals(new int[]{bone}, moved.get(3).joints(), "new vertex took its nearest's weights");
        assertArrayEquals(new int[]{bone}, moved.get(2).joints());
        assertEquals(0, moved.get(0).joints().length);

        // Also through ensureSkin (an authoring call after an edit nobody reported).
        float[] again = {0, 0, 0, 0, 2, 0};
        Skin viaEnsure = store.ensureSkin("arm", again, null);
        assertEquals(2, viaEnsure.vertexCount());
        assertArrayEquals(new int[]{bone}, viaEnsure.get(1).joints());

        mesh.remove("arm");
        assertTrue(store.reconcile(mesh::get));
        assertNull(store.skinOf("arm"));
        assertEquals(0, fired[0], "reconcile runs inside the bake: it never notifies");
    }
}
