package com.openmason.main.systems.skeleton;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.modeling.parts.ModelPartDescriptor;
import com.openmason.main.systems.scripting.commands.ModelCommands;
import com.openmason.main.systems.scripting.doc.HeadlessModelDocument;
import com.openmason.main.systems.services.commands.ModelCommandHistory;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OpenMason #165 rig-revamp gaps: binding a part to a bone (and the endpoint snap with it) is one undo step on the
 * MODEL history, which marks the model dirty; skin-weight history steps fire the model's dirty callback too.
 */
class RigEditHistoryTest {

    @TempDir
    Path tmp;

    private static OMOFormat.BoneEntry bone(String id, float endY) {
        return new OMOFormat.BoneEntry(id, id, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, endY, 0);
    }

    @Test
    void bindingAPartToABoneIsOneUndoableModelStep() {
        HeadlessModelDocument doc = new HeadlessModelDocument();
        new ModelCommands(doc, new ObjectMapper(), tmp).createPart("cube", "hand", new Vector3f(1, 1, 1),
                new Vector3f(0, 2, 0), null, null);
        BoneStore bones = doc.bones();
        bones.put(bone("arm", 1f));
        ModelCommandHistory history = new ModelCommandHistory();
        AtomicInteger dirty = new AtomicInteger();
        history.setOnHistoryChange(dirty::incrementAndGet);
        ModelPartDescriptor hand = doc.parts().getPartByName("hand").orElseThrow();
        String before = hand.parentId();

        boolean changed = RigHierarchyCommand.record("Bind Part to Bone", doc.parts(), bones, history, null, () -> {
            bones.put(new OMOFormat.BoneEntry("arm", "arm", null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2f, 0));  // the snap
            return doc.parts().setPartParent(hand.id(), "arm");
        });
        assertTrue(changed);
        assertEquals("arm", doc.parts().getPartById(hand.id()).orElseThrow().parentId());
        assertTrue(history.canUndo());
        assertEquals("Bind Part to Bone", history.getUndoDescription());
        assertEquals(1, dirty.get(), "recording dirties the model");

        history.undo();
        assertEquals(before, doc.parts().getPartById(hand.id()).orElseThrow().parentId());
        assertEquals(1f, bones.getById("arm").endpointY(), 1e-6, "the endpoint snap is undone with it");
        history.redo();
        assertEquals("arm", doc.parts().getPartById(hand.id()).orElseThrow().parentId());
        assertEquals(2f, bones.getById("arm").endpointY(), 1e-6);
        assertEquals(3, dirty.get());

        // An edit that changes nothing records nothing.
        history.clear();
        RigHierarchyCommand.record("noop", doc.parts(), bones, history, null, () -> false);
        assertFalse(history.canUndo());
    }

    @Test
    void skinHistoryStepsDirtyTheModel() {
        SkinStore skins = new SkinStore();
        SkinCommandHistory history = new SkinCommandHistory(skins);
        AtomicInteger dirty = new AtomicInteger();
        history.setOnChange(dirty::incrementAndGet);
        history.record("nothing", () -> { });
        assertEquals(0, dirty.get(), "an edit that changes nothing is not recorded");
        history.record("bind", () -> skins.ensureSkin("body", new float[] {0, 0, 0, 1, 0, 0}, null));
        assertEquals(1, dirty.get());
        assertTrue(history.undo());
        assertTrue(history.redo());
        assertEquals(3, dirty.get());
        assertEquals(List.of("body"), skins.partIds());
    }
}
