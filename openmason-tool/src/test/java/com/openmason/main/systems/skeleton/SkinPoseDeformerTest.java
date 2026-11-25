package com.openmason.main.systems.skeleton;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.modeling.parts.ModelPartDescriptor;
import com.openmason.engine.modeling.parts.ModelPartManager;
import com.openmason.engine.modeling.parts.PartMeshRebuilder;
import com.openmason.engine.modeling.parts.PartTransform;
import com.openmason.engine.rig.CendaRig;
import com.openmason.engine.rig.Skin;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The skinned preview on a tiny model: one triangle part (rest position x+2) and one bone whose head
 * sits at (0,1,0). Two vertices are weighted fully to the bone, one is unweighted (follows its part).
 */
class SkinPoseDeformerTest {

    private static final float[] LOCAL = {0, 0, 0, 0, 2, 0, 1, 2, 0};

    private ModelPartManager pm;
    private BoneStore bones;
    private SkinStore skins;
    private String armId;
    private PartTransform rest;
    private final AtomicReference<PartMeshRebuilder.RebuildResult> last = new AtomicReference<>();

    @BeforeEach
    void model() {
        assumeTrue(CendaRig.isAvailable(), () -> "native rig unavailable: " + CendaRig.unavailableReason());
        pm = new ModelPartManager();
        pm.setMeshConsumer(last::set);
        ModelPartDescriptor arm = pm.addPartFromGeometry("arm",
                PartMeshRebuilder.PartGeometry.of(LOCAL.clone(), null, new int[]{0, 1, 2}, new int[]{0}),
                new Vector3f());
        armId = arm.id();
        rest = new PartTransform(new Vector3f(), new Vector3f(2, 0, 0), new Vector3f(), new Vector3f(1, 1, 1));
        pm.setPartTransform(armId, rest);

        bones = new BoneStore();
        skins = new SkinStore();
        RigWiring.connect(pm, bones, skins);
        bones.setBones(List.of(new OMOFormat.BoneEntry("elbow", "elbow", null,
                0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0)));

        Skin skin = skins.ensureSkin(armId, LOCAL, null);
        int elbow = skins.jointIndex("elbow");
        skin.set(1, new int[]{elbow}, new float[]{1f});
        skin.set(2, new int[]{elbow}, new float[]{1f});
        skins.fireChanged();
    }

    @Test
    void restIsTheRigidBake() {
        assertArrayEquals(new float[]{2, 0, 0, 2, 2, 0, 3, 2, 0}, last.get().combinedVertices(), 1e-5f);
    }

    @Test
    void posedBoneSkinsItsWeightedVerticesAndTheRestFollowTheirPart() {
        bones.setPose(Map.of("elbow", new BoneStore.BonePose(new Vector3f(), new Vector3f(0, 0, 90))),
                id -> armId.equals(id) ? rest : null, true);
        // (2,2,0) and (3,2,0) rotate 90 deg about Z around the head (0,1,0); (2,0,0) stays.
        assertArrayEquals(new float[]{2, 0, 0, -1, 3, 0, -1, 4, 0}, last.get().combinedVertices(), 1e-5f);

        bones.clearPose();
        assertArrayEquals(new float[]{2, 0, 0, 2, 2, 0, 3, 2, 0}, last.get().combinedVertices(), 1e-5f);
    }

    @Test
    void weightsBindAgainstThePreviewRestNotTheCurrentPartPose() {
        // The preview moved the part (x+2 -> x+5) while the bone stays at rest: the unweighted vertex
        // follows the part, the bone-weighted ones keep their rest (bind) placement.
        bones.setPose(Map.of(), id -> armId.equals(id) ? rest : null, false);
        pm.setPartTransform(armId, new PartTransform(new Vector3f(), new Vector3f(5, 0, 0),
                new Vector3f(), new Vector3f(1, 1, 1)));
        assertArrayEquals(new float[]{5, 0, 0, 2, 2, 0, 3, 2, 0}, last.get().combinedVertices(), 1e-5f);
    }

    @Test
    void halfWeightBlendsPartAndBone() {
        Skin skin = skins.skinOf(armId);
        skin.set(2, new int[]{skins.jointIndex("elbow"), skins.jointIndex(armId)}, new float[]{0.5f, 0.5f});
        bones.setPose(Map.of("elbow", new BoneStore.BonePose(new Vector3f(), new Vector3f(0, 0, 90))),
                id -> armId.equals(id) ? rest : null, true);
        // (3,2,0): bone -> (-1,4,0), part -> (3,2,0); halfway = (1,3,0).
        float[] v = last.get().combinedVertices();
        assertArrayEquals(new float[]{1, 3, 0}, new float[]{v[6], v[7], v[8]}, 1e-5f);
    }
}
