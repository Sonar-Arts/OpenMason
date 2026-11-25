package com.openmason.main.systems.menus.animationEditor.panels;

import com.openmason.engine.modeling.parts.ModelPartManager;
import com.openmason.main.systems.menus.animationEditor.AnimTestModels;
import com.openmason.main.systems.menus.animationEditor.data.Track;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.openmason.main.systems.menus.animationEditor.AnimTestModels.idOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PartRowsTest {

    @Test
    void rowsFollowHierarchyThenOrphans() {
        ModelPartManager pm = AnimTestModels.model("body", "leg", "foot", "head");
        assertTrue(pm.setPartParent(idOf(pm, "leg"), idOf(pm, "body")));
        assertTrue(pm.setPartParent(idOf(pm, "foot"), idOf(pm, "leg")));
        assertTrue(pm.setPartParent(idOf(pm, "head"), idOf(pm, "body")));

        Track orphan = new Track("ghost");
        orphan.setPartNameHint("tail");

        List<PartRows.Row> rows = PartRows.build(pm, List.of(orphan));
        assertEquals(List.of("body", "leg", "foot", "head", "tail"),
                rows.stream().map(PartRows.Row::label).toList());
        assertEquals(List.of(0, 1, 2, 1, 0),
                rows.stream().map(PartRows.Row::depth).toList());
        assertTrue(rows.get(4).isOrphan());
        assertEquals("ghost", rows.get(4).id());
    }

    @Test
    void bonesAreRowsInTheUnifiedHierarchy() {
        ModelPartManager pm = AnimTestModels.model("body", "hand", "head");
        com.openmason.main.systems.skeleton.BoneStore bones = new com.openmason.main.systems.skeleton.BoneStore();
        // spine under the body PART, arm under spine; the hand PART hangs under the arm BONE.
        bones.setBones(List.of(
                bone("arm", "Arm", "spine"),
                bone("spine", "Spine", idOf(pm, "body")),
                bone("root", "Root", null)));
        assertTrue(pm.setPartParent(idOf(pm, "hand"), "arm"));
        assertTrue(pm.setPartParent(idOf(pm, "head"), idOf(pm, "body")));

        List<PartRows.Row> rows = PartRows.build(pm, bones, List.of());
        assertEquals(List.of("body", "head", "Spine", "Arm", "hand", "Root"),
                rows.stream().map(PartRows.Row::label).toList());
        assertEquals(List.of(0, 1, 1, 2, 3, 0), rows.stream().map(PartRows.Row::depth).toList());
        assertTrue(rows.get(2).isBone());
        assertTrue(!rows.get(2).isOrphan());
        assertTrue(rows.get(4).part() != null);
    }

    private static com.openmason.engine.format.omo.OMOFormat.BoneEntry bone(String id, String name, String parent) {
        return new com.openmason.engine.format.omo.OMOFormat.BoneEntry(id, name, parent,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0);
    }

    @Test
    void orphanWithoutHintShowsShortenedId() {
        List<PartRows.Row> rows = PartRows.build(null, List.of(new Track("0123456789abcdef")));
        assertEquals("01234567…", rows.get(0).label());
    }
}
