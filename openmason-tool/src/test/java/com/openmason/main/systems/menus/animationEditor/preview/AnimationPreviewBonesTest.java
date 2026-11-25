package com.openmason.main.systems.menus.animationEditor.preview;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.modeling.parts.ModelPartManager;
import com.openmason.engine.rig.CendaRig;
import com.openmason.main.systems.menus.animationEditor.AnimTestModels;
import com.openmason.main.systems.menus.animationEditor.data.AnimationClip;
import com.openmason.main.systems.menus.animationEditor.data.Easing;
import com.openmason.main.systems.menus.animationEditor.data.Keyframe;
import com.openmason.main.systems.skeleton.BoneStore;
import com.openmason.main.systems.skeleton.RigWiring;
import com.openmason.main.systems.skeleton.SkinStore;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.openmason.main.systems.menus.animationEditor.AnimTestModels.idOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Bone tracks drive the skeleton's pose layer; parts under a posed bone follow it. */
class AnimationPreviewBonesTest {

    private ModelPartManager pm;
    private BoneStore bones;
    private String hand;

    @BeforeEach
    void model() {
        assumeTrue(CendaRig.isAvailable(), () -> "native rig unavailable: " + CendaRig.unavailableReason());
        pm = AnimTestModels.model("hand");
        hand = idOf(pm, "hand");
        bones = new BoneStore();
        RigWiring.connect(pm, bones, new SkinStore());
        bones.setBones(List.of(new OMOFormat.BoneEntry("arm", "Arm", null,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0)));
        assertTrue(pm.setPartParent(hand, "arm"));
    }

    private static Keyframe rotZ(float t, float deg) {
        return new Keyframe(t, new Vector3f(), new Vector3f(0, 0, deg), new Vector3f(1, 1, 1), Easing.LINEAR);
    }

    private Vector3f handOrigin() {
        return pm.getEffectiveWorldMatrix(hand).transformPosition(new Vector3f());
    }

    @Test
    void boneTrackPosesTheSkeletonAndPartsUnderIt() {
        assertEquals(2f, handOrigin().y, 1e-5f, "the hand hangs at the bone's tail");

        AnimationClip clip = AnimationClip.blank();
        clip.ensureTrack("arm").upsert(rotZ(0f, 90f));
        AnimationPreviewPipeline preview = new AnimationPreviewPipeline(pm, bones);
        preview.captureRestPose();
        preview.applyPose(clip, 0f);

        assertTrue(bones.isPoseActive());
        assertTrue(preview.isAnimatedBone("arm"));
        assertNotNull(bones.getPose("arm"));
        assertEquals(0f, bones.getById("arm").rotZ(), "the rest bone is never written");
        Vector3f origin = handOrigin();
        assertEquals(-2f, origin.x, 1e-5f, "the hand swung with the bone");
        assertEquals(0f, origin.y, 1e-5f);
        assertTrue(bones.partRestOf(hand) != null, "the skinned preview sees the part rest snapshot");

        preview.restoreRestPose();
        assertFalse(bones.isPoseActive());
        assertEquals(2f, handOrigin().y, 1e-5f);

        preview.applyPose(clip, 0f);
        preview.release();
        assertFalse(bones.isPoseActive());
        assertNull(bones.getPose("arm"));
        assertEquals(2f, handOrigin().y, 1e-5f);
    }

    @Test
    void overlayMaskNamesBones() {
        AnimationClip base = AnimationClip.blank();
        base.ensureTrack("arm").upsert(rotZ(0f, 10f));
        AnimationClip overlay = AnimationClip.blank();
        overlay.setLayerType(com.openmason.engine.format.oma.AnimLayerMeta.LayerType.OVERLAY);
        overlay.setFadeInSeconds(0f);
        overlay.ensureTrack("arm").upsert(rotZ(0f, 50f));

        AnimationPreviewPipeline preview = new AnimationPreviewPipeline(pm, bones);
        preview.captureRestPose();
        overlay.setMaskParts(List.of("arm")); // bone name, case-insensitive
        preview.applyPose(overlay, 0f, base);
        assertEquals(50f, bones.getLocalRotation("arm").z, 1e-4f, "masked-in bone takes the overlay");

        overlay.setMaskParts(List.of("hand"));
        preview.applyPose(overlay, 0f, base);
        assertEquals(10f, bones.getLocalRotation("arm").z, 1e-4f, "masked-out bone keeps the base");
    }

    @Test
    void untrackedBonesShowRest() {
        AnimationClip clip = AnimationClip.blank();
        clip.ensureTrack(hand).upsert(AnimTestModels.kf(0f, 1f));
        AnimationPreviewPipeline preview = new AnimationPreviewPipeline(pm, bones);
        preview.captureRestPose();
        preview.applyPose(clip, 0f);
        assertFalse(preview.isAnimatedBone("arm"));
        assertNull(bones.getPose("arm"));
        Matrix4f world = pm.getEffectiveWorldMatrix(hand);
        assertEquals(1f, world.transformPosition(new Vector3f()).x, 1e-5f);
    }
}
