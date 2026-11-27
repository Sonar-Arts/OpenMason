package com.openmason.main.systems.skeleton;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.modeling.EditableModel;
import com.openmason.engine.modeling.parts.ModelPartDescriptor;
import com.openmason.engine.modeling.parts.ModelPartManager;
import com.openmason.engine.modeling.parts.PartMeshRebuilder;
import com.openmason.engine.modeling.parts.PartTransform;
import com.openmason.engine.rig.CendaRig;
import com.openmason.engine.rig.JointPalette;
import com.openmason.engine.rig.Skin;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The skinned preview on the GPU path (OpenMason #175 F2; was the CPU deformer + re-weld): one triangle part (rest
 * position x+2) and one bone whose head sits at (0,1,0). Two vertices are weighted fully to the bone, one is
 * unweighted (follows its part). A pose is palette-only: no rebuild, the baked vertices stay at rest, and what the
 * GPU draws (the CPU twin of its skinning, {@link EditableModel#getDisplayedCornerPositions}) equals the old CPU
 * deformer's results (the expectations below are its frozen outputs) and {@link Skin#deform}.
 */
class SkinPosePaletteTest {

    private static final float[] LOCAL = {0, 0, 0, 0, 2, 0, 1, 2, 0};
    private static final float[] REST = {2, 0, 0, 2, 2, 0, 3, 2, 0};

    private EditableModel model;
    private ModelPartManager pm;
    private BoneStore bones;
    private SkinStore skins;
    private String armId;
    private PartTransform rest;

    @BeforeEach
    void model() {
        assumeTrue(CendaRig.isAvailable(), () -> "native rig unavailable: " + CendaRig.unavailableReason());
        model = new EditableModel();
        pm = model.getPartManager();
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

    /** The drawn position of every corner, keyed back to the vertex it was baked from (REST order). */
    private float[] shownInRestOrder() {
        float[] baked = model.getAllMeshVertexPositions();
        float[] shown = model.getDisplayedCornerPositions(new Matrix4f());
        float[] out = new float[REST.length];
        for (int c = 0; c < baked.length / 3; c++) {
            for (int v = 0; v < REST.length / 3; v++) {
                if (Math.abs(baked[c * 3] - REST[v * 3]) < 1e-5f && Math.abs(baked[c * 3 + 1] - REST[v * 3 + 1]) < 1e-5f
                        && Math.abs(baked[c * 3 + 2] - REST[v * 3 + 2]) < 1e-5f) {
                    System.arraycopy(shown, c * 3, out, v * 3, 3);
                }
            }
        }
        return out;
    }

    @Test
    void restIsTheRigidBake() {
        assertArrayEquals(REST, shownInRestOrder(), 1e-5f);
        assertFalse(model.isDisplayDeformed());
    }

    @Test
    void posedBoneSkinsItsWeightedVerticesOnThePaletteAlone() {
        long rebuilds = pm.getRebuildCount();
        bones.setPose(Map.of("elbow", new BoneStore.BonePose(new Vector3f(), new Vector3f(0, 0, 90))),
                id -> armId.equals(id) ? rest : null, true);
        // (2,2,0) and (3,2,0) rotate 90 deg about Z around the head (0,1,0); (2,0,0) stays.
        assertArrayEquals(new float[]{2, 0, 0, -1, 3, 0, -1, 4, 0}, shownInRestOrder(), 1e-5f);
        assertTrue(model.isDisplayDeformed());
        assertEquals(rebuilds, pm.getRebuildCount(), "posing re-bakes nothing");
        assertArrayEquals(REST, sortedLike(model.getAllMeshVertexPositions()), 1e-5f, "baked vertices stay at rest");

        bones.clearPose();
        assertArrayEquals(REST, shownInRestOrder(), 1e-5f);
        assertEquals(rebuilds, pm.getRebuildCount(), "clearing the pose re-bakes nothing either");
        assertFalse(model.isDisplayDeformed());
    }

    @Test
    void theGpuSkinEqualsSkinDeformOfTheRestPositions() {
        bones.setPose(Map.of("elbow", new BoneStore.BonePose(new Vector3f(0.1f, 0, 0), new Vector3f(10, 20, 70))),
                id -> armId.equals(id) ? rest : null, true);
        SkinPosePalette source = (SkinPosePalette) pm.getPartSkinSource();
        var pose = source.pose(pm.getAllParts());
        // Skin.deform of the REST (bind) positions with the registry's skin matrices.
        JointPalette k = new JointPalette();
        List<String> registry = skins.jointIds();
        k.ensureCapacity(registry.size());
        for (int j = 0; j < registry.size(); j++) {
            Matrix4f m = pose.jointSkin()[j] != null ? pose.jointSkin()[j] : new Matrix4f();
            k.set(j, m, m);
        }
        // Unweighted vertices follow the part: the registry joint of the arm (posed part = its rest here).
        int armJoint = skins.jointIndex(armId);
        k.ensureCapacity(registry.size() + 1);
        Matrix4f armK = new Matrix4f();
        k.set(armJoint, armK, armK);
        int[] partJoint = {armJoint, armJoint, armJoint};
        float[] expected = Skin.deform(skins.skinOf(armId), partJoint, REST, k, null);
        assertArrayEquals(expected, shownInRestOrder(), 1e-5f);
    }

    @Test
    void weightsBindAgainstThePreviewRestNotTheCurrentPartPose() {
        // The preview moved the part (x+2 -> x+5) while the bone stays at rest: the unweighted vertex
        // follows the part, the bone-weighted ones keep their rest (bind) placement.
        bones.setPose(Map.of(), id -> armId.equals(id) ? rest : null, false);
        pm.setPartTransform(armId, new PartTransform(new Vector3f(), new Vector3f(5, 0, 0),
                new Vector3f(), new Vector3f(1, 1, 1)));
        float[] baked = model.getAllMeshVertexPositions();
        float[] shown = model.getDisplayedCornerPositions(new Matrix4f());
        float[] expected = {5, 0, 0, 2, 2, 0, 3, 2, 0};
        for (int c = 0; c < baked.length / 3; c++) {
            int v = Math.round(baked[c * 3 + 1]) == 0 ? 0 : (Math.round(baked[c * 3]) == 5 ? 1 : 2);
            assertArrayEquals(new float[]{expected[v * 3], expected[v * 3 + 1], expected[v * 3 + 2]},
                    new float[]{shown[c * 3], shown[c * 3 + 1], shown[c * 3 + 2]}, 1e-5f, "corner " + c);
        }
        // Clearing the pose re-bakes the part (a rebuild happened while posing) - the bake is current again.
        bones.clearPose();
        assertArrayEquals(model.getAllMeshVertexPositions(), model.getDisplayedCornerPositions(new Matrix4f()));
    }

    @Test
    void halfWeightBlendsPartAndBone() {
        Skin skin = skins.skinOf(armId);
        skin.set(2, new int[]{skins.jointIndex("elbow"), skins.jointIndex(armId)}, new float[]{0.5f, 0.5f});
        skins.fireChanged();
        bones.setPose(Map.of("elbow", new BoneStore.BonePose(new Vector3f(), new Vector3f(0, 0, 90))),
                id -> armId.equals(id) ? rest : null, true);
        // (3,2,0): bone -> (-1,4,0), part -> (3,2,0); halfway = (1,3,0), within the unorm16 weight quantum.
        float[] v = shownInRestOrder();
        assertArrayEquals(new float[]{1, 3, 0}, new float[]{v[6], v[7], v[8]}, 1e-4f);
    }

    @Test
    void aWeightEditWhilePosingRepacksWithoutARebuild() {
        bones.setPose(Map.of("elbow", new BoneStore.BonePose(new Vector3f(), new Vector3f(0, 0, 90))),
                id -> armId.equals(id) ? rest : null, true);
        long rebuilds = pm.getRebuildCount();
        skins.skinOf(armId).set(2, new int[0], new float[0]);
        skins.fireChanged();
        float[] v = shownInRestOrder();
        assertArrayEquals(new float[]{3, 2, 0}, new float[]{v[6], v[7], v[8]}, 1e-5f, "unweighted: follows the part");
        assertEquals(rebuilds, pm.getRebuildCount());
    }

    private static float[] sortedLike(float[] positions) {
        float[] out = new float[REST.length];
        for (int v = 0; v < REST.length / 3; v++) {
            for (int c = 0; c < positions.length / 3; c++) {
                if (Math.abs(positions[c * 3] - REST[v * 3]) < 1e-5f
                        && Math.abs(positions[c * 3 + 1] - REST[v * 3 + 1]) < 1e-5f) {
                    System.arraycopy(positions, c * 3, out, v * 3, 3);
                }
            }
        }
        return out;
    }
}
