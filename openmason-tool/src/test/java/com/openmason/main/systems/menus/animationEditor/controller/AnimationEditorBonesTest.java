package com.openmason.main.systems.menus.animationEditor.controller;

import com.openmason.engine.format.oma.OMAReader;
import com.openmason.engine.format.oma.ParsedAnimClip;
import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.modeling.parts.ModelPartManager;
import com.openmason.engine.rig.CendaRig;
import com.openmason.main.systems.menus.animationEditor.AnimTestModels;
import com.openmason.main.systems.menus.animationEditor.data.Keyframe;
import com.openmason.main.systems.menus.animationEditor.data.Track;
import com.openmason.main.systems.skeleton.BoneStore;
import com.openmason.main.systems.skeleton.RigWiring;
import com.openmason.main.systems.skeleton.SkinStore;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipInputStream;

import static com.openmason.main.systems.menus.animationEditor.AnimTestModels.idOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Bones as animation joints: keying, auto-key from the gizmo, OMA save/load + rebinding. */
class AnimationEditorBonesTest {

    @TempDir
    Path tempDir;

    private ModelPartManager pm;
    private BoneStore bones;
    private AnimationEditorController c;

    @BeforeEach
    void bind() {
        assumeTrue(CendaRig.isAvailable(), () -> "native rig unavailable: " + CendaRig.unavailableReason());
        pm = AnimTestModels.model("hand");
        bones = new BoneStore();
        RigWiring.connect(pm, bones, new SkinStore());
        bones.setBones(List.of(new OMOFormat.BoneEntry("bone-arm", "Arm", null,
                0, 0, 0, 1, 2, 3, 0, 0, 15, 0, 2, 0)));
        c = new AnimationEditorController();
        c.setBoneStoreSupplier(() -> bones);
        c.bindViewport(pm);
        c.beginSession();
    }

    @Test
    void bonesAreJointsWithoutBeingOrphans() {
        assertTrue(c.joints().isBone("bone-arm"));
        assertEquals("Arm", c.joints().nameOf("bone-arm"));
        assertEquals("bone-arm", c.joints().resolve("arm"));
        c.insertKeyframe("bone-arm", AnimTestModels.kf(0f, 1f));
        assertTrue(c.orphanTracks().isEmpty());
    }

    @Test
    void insertKeyframeAtPlayheadKeysTheBonesCurrentLocal() {
        c.insertKeyframeAtPlayhead("bone-arm");
        Track track = c.state().clip().trackFor("bone-arm");
        assertNotNull(track);
        Keyframe kf = track.get(0);
        assertEquals(new Vector3f(1, 2, 3), kf.position(), "rest local position");
        assertEquals(new Vector3f(0, 0, 15), kf.rotation());
        assertEquals(new Vector3f(1, 1, 1), kf.scale(), "bones key unit scale");
        assertTrue(bones.isPoseActive(), "keying applies the preview, posing the bone");
        assertEquals(15f, bones.getLocalRotation("bone-arm").z, 1e-5f);
    }

    @Test
    void gizmoEditAutoKeysIntoThePoseLayerNotTheRest() {
        c.state().setAutoKey(true);
        bones.setSelectedBoneId("bone-arm");
        bones.editLocal("bone-arm", null, new Vector3f(0, 0, 40));
        bones.editLocal("bone-arm", null, new Vector3f(0, 0, 45)); // same drag: amends

        assertEquals(15f, bones.getById("bone-arm").rotZ(), "rest untouched");
        Track track = c.state().clip().trackFor("bone-arm");
        assertEquals(1, track.size());
        assertEquals(45f, track.get(0).rotation().z, 1e-5f);
        assertEquals(45f, bones.getLocalRotation("bone-arm").z, 1e-5f, "the viewport shows the key");

        assertTrue(c.undo());
        assertNull(c.state().clip().trackFor("bone-arm"), "one undo step removes the whole drag");
    }

    @Test
    void withoutAutoKeyAnUnanimatedBoneTakesARestEdit() {
        bones.editLocal("bone-arm", new Vector3f(9, 9, 9), null);
        assertEquals(9f, bones.getById("bone-arm").posX());
        assertNull(c.state().clip().trackFor("bone-arm"));

        c.insertKeyframeAtPlayhead("bone-arm");
        bones.editLocal("bone-arm", null, new Vector3f(0, 0, 70));
        assertEquals(15f, bones.getById("bone-arm").rotZ(), "an animated bone is posed, not rest-edited");
        assertEquals("bone-arm", c.state().unkeyedPartId());
    }

    @Test
    void endedSessionEditsTheRestAgain() {
        c.state().setAutoKey(true);
        c.endSession();
        bones.editLocal("bone-arm", null, new Vector3f(0, 0, 33));
        assertEquals(33f, bones.getById("bone-arm").rotZ());
        assertFalse(bones.isPoseActive());
    }

    @Test
    void saveMarksBoneTracksAndLoadRebindsThemByName() throws Exception {
        c.insertKeyframe("bone-arm", AnimTestModels.kf(0f, 1f));
        c.insertKeyframe(idOf(pm, "hand"), AnimTestModels.kf(0f, 2f));
        Path file = tempDir.resolve("wave.omanim");
        assertTrue(c.saveAs(file.toString()));

        String manifest = readManifest(file).replaceAll("\\s", "");
        assertTrue(manifest.contains("\"targetType\":\"bone\""), manifest);
        assertEquals(1, manifest.split("targetType").length - 1, "only the bone track is marked");

        ParsedAnimClip parsed = new OMAReader().read(Files.newInputStream(file));
        assertNotNull(parsed);

        // Same skeleton under a new bone id: the track rebinds by name.
        bones.setBones(List.of(new OMOFormat.BoneEntry("bone-arm-2", "Arm", null,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0)));
        assertTrue(c.load(file.toString()));
        assertNotNull(c.state().clip().trackFor("bone-arm-2"));
        assertNull(c.state().clip().trackFor("bone-arm"));
        assertTrue(c.orphanTracks().isEmpty());
    }

    private static String readManifest(Path file) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(file))) {
            for (var e = zis.getNextEntry(); e != null; e = zis.getNextEntry()) {
                if (e.getName().equals("manifest.json")) return new String(zis.readAllBytes());
            }
        }
        throw new AssertionError("no manifest");
    }
}
