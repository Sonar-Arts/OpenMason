package com.openmason.main.systems.skeleton;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.modeling.core.EditCostStats;
import com.openmason.engine.modeling.parts.ModelPartManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * #175 F1 load coalescing: an OMO load restores the skeleton BEFORE the parts and quietly, so the bone change does
 * not trigger its own whole-model part rebuild; a plain {@link BoneStore#load(List)} still does.
 */
class BoneStoreQuietLoadTest {

    @AfterEach
    void stats() {
        EditCostStats.setEnabled(false);
        EditCostStats.reset();
    }

    private static OMOFormat.BoneEntry bone(String id) {
        return new OMOFormat.BoneEntry(id, id, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0);
    }

    @Test
    void aQuietLoadDoesNotRebuildTheParts() {
        ModelPartManager parts = new ModelPartManager();
        BoneStore bones = new BoneStore();
        RigWiring.connect(parts, bones, null);
        EditCostStats.reset();
        EditCostStats.setEnabled(true);

        bones.load(List.of(bone("root")), false);
        assertEquals(0, EditCostStats.totalCalls(EditCostStats.Span.REBUILD_COMBINED), "quiet load: no rebuild");
        assertEquals(1, bones.size());

        bones.load(List.of(bone("root"), bone("tip")));
        assertEquals(1, EditCostStats.totalCalls(EditCostStats.Span.REBUILD_COMBINED), "plain load: one rebuild");
    }
}
