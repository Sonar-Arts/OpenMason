package com.openmason.main.systems.mcp;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.modeling.parts.ModelPartManager;
import com.openmason.main.systems.menus.animationEditor.AnimTestModels;
import com.openmason.main.systems.menus.animationEditor.controller.AnimationEditorController;
import com.openmason.main.systems.menus.animationEditor.data.Track;
import com.openmason.main.systems.skeleton.BoneStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.openmason.main.systems.menus.animationEditor.AnimTestModels.idOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** anim_* tools address joints: parts and bones by id or name, orphan tracks by key. */
class AnimationJointResolveTest {

    @Test
    void resolvesPartsBonesAndOrphanTracks() {
        ModelPartManager pm = AnimTestModels.model("hand");
        BoneStore bones = new BoneStore();
        bones.setBones(List.of(new OMOFormat.BoneEntry("bone-1", "Forearm", null,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0)));
        AnimationEditorController c = new AnimationEditorController();
        c.setBoneStoreSupplier(() -> bones);
        c.bindViewport(pm);
        c.state().clip().tracks().put("ghost", new Track("ghost"));

        assertEquals(idOf(pm, "hand"), AnimationEditingService.resolvePartId(c, "hand"));
        assertEquals(idOf(pm, "hand"), AnimationEditingService.resolvePartId(c, idOf(pm, "hand")));
        assertEquals("bone-1", AnimationEditingService.resolvePartId(c, "bone-1"));
        assertEquals("bone-1", AnimationEditingService.resolvePartId(c, "Forearm"));
        assertEquals("bone-1", AnimationEditingService.resolvePartId(c, "forearm"), "case-insensitive fallback");
        assertEquals("ghost", AnimationEditingService.resolvePartId(c, "ghost"));
        assertNull(AnimationEditingService.resolvePartId(c, "nothing"));
    }

    @Test
    void anUnboundEditorSaysToOpenItInsteadOfReturningNothing() {
        AnimationEditorController c = new AnimationEditorController();
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AnimationEditingService.resolvePartId(c, "forearm"));
        assertTrue(e.getMessage().contains("anim_open_editor"), e.getMessage());
        // Orphan tracks in the clip still resolve without a model.
        c.state().clip().tracks().put("ghost", new Track("ghost"));
        assertEquals("ghost", AnimationEditingService.resolvePartId(c, "ghost"));
    }
}
