package com.openmason.engine.modeling;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.modeling.uv.FaceTextureManager;
import com.openmason.engine.modeling.uv.FaceTextureMapping;
import com.openmason.engine.modeling.uv.MaterialDefinition;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Authored corner UVs on mapped faces (#169 / #173 data-loss fix): an imported or hand-authored UV layout on a face
 * with an explicit material mapping used to be thrown away (every mapped face was re-projected into its region).
 * Loaded UVs that are not the region projection are now kept; UVs the editor itself projected (texture-editor
 * models) stay projected, so they still follow region and geometry edits exactly as before.
 */
class AuthoredUvTest {

    private static final int MATERIAL = 7;

    /** The {@code ModelShadingTest} cube with the given UVs (24 corners x 2). */
    private static OMOFormat.MeshData cube(float[] uvs) {
        float h = 0.5f;
        float[] v = {
            -h, -h,  h,   h, -h,  h,   h,  h,  h,  -h,  h,  h,
             h, -h, -h,  -h, -h, -h,  -h,  h, -h,   h,  h, -h,
            -h, -h, -h,  -h, -h,  h,  -h,  h,  h,  -h,  h, -h,
             h, -h,  h,   h, -h, -h,   h,  h, -h,   h,  h,  h,
            -h,  h,  h,   h,  h,  h,   h,  h, -h,  -h,  h, -h,
            -h, -h, -h,   h, -h, -h,   h, -h,  h,  -h, -h,  h,
        };
        int[] idx = new int[36];
        int[] faceIds = new int[12];
        for (int f = 0; f < 6; f++) {
            int b = f * 4;
            int[] q = {b, b + 1, b + 2, b, b + 2, b + 3};
            System.arraycopy(q, 0, idx, f * 6, 6);
            faceIds[f * 2] = f;
            faceIds[f * 2 + 1] = f;
        }
        return new OMOFormat.MeshData(v, uvs, idx, faceIds, "FLAT");
    }

    private static void registerMaterial(FaceTextureManager ftm) {
        ftm.registerMaterial(new MaterialDefinition(MATERIAL, "M", 0, MaterialDefinition.RenderLayer.OPAQUE,
                MaterialDefinition.MaterialProperties.NONE));
    }

    private static FaceTextureMapping mapping(int face, float u0, float v0, float u1, float v1) {
        return new FaceTextureMapping(face, MATERIAL, new FaceTextureMapping.UVRegion(u0, v0, u1, v1),
                FaceTextureMapping.UVRotation.NONE, true);
    }

    /** UVs of face {@code face}'s four corners in the render mesh (corners are emitted per face, 4 each here). */
    private static float[] faceUvs(EditableModel model, int face) {
        return Arrays.copyOfRange(model.getTexCoords(), face * 8, face * 8 + 8);
    }

    /** The region projection of face 0 into (0.5, 0.5)-(1, 1), as the texture editor saves it. */
    private static float[] projectedFace0() {
        EditableModel model = new EditableModel();
        model.loadMeshDataAsPart(cube(new float[48]), "Root");
        registerMaterial(model.getFaceTextureManager());
        model.getFaceTextureManager().setFaceMapping(mapping(0, 0.5f, 0.5f, 1f, 1f));
        model.refreshUVs();
        return faceUvs(model, 0);
    }

    private static final float[] AUTHORED_FACE1 = {0.1f, 0.9f, 0.3f, 0.85f, 0.25f, 0.6f, 0.05f, 0.62f};

    private static EditableModel loaded() {
        float[] uvs = new float[48];
        System.arraycopy(projectedFace0(), 0, uvs, 0, 8);
        System.arraycopy(AUTHORED_FACE1, 0, uvs, 8, 8);
        EditableModel model = new EditableModel();
        model.loadMeshDataAsPart(cube(uvs), "Root");
        FaceTextureManager ftm = model.getFaceTextureManager();
        registerMaterial(ftm);
        ftm.setFaceMapping(mapping(0, 0.5f, 0.5f, 1f, 1f));
        ftm.setFaceMapping(mapping(1, 0f, 0f, 1f, 1f)); // an import: full region, its own UV layout
        return model;
    }

    @Test
    void withoutAdoptionMappedFacesAreProjectedAsBefore() {
        EditableModel model = loaded();
        model.refreshUVs();
        assertFalse(Arrays.equals(AUTHORED_FACE1, faceUvs(model, 1)), "old behaviour: re-projected");
    }

    @Test
    void loadedUvLayoutsAreKeptAndEditorProjectionsStayProjected() {
        EditableModel model = loaded();
        assertEquals(1, model.adoptAuthoredUVs(), "only the face whose UVs are not the projection");
        model.refreshUVs();
        assertArrayEquals(AUTHORED_FACE1, faceUvs(model, 1), 0f);
        assertArrayEquals(projectedFace0(), faceUvs(model, 0), 1e-6f);
        assertTrue(model.getFaceTextureManager().getFaceMapping(1).authoredUVs());
        assertFalse(model.getFaceTextureManager().getFaceMapping(0).authoredUVs());

        // The saved mesh carries the authored UVs (load -> save keeps them).
        float[] saved = model.toMeshData().texCoords();
        assertArrayEquals(AUTHORED_FACE1, Arrays.copyOfRange(saved, 8, 16), 0f);
    }

    @Test
    void aTextureEditorFaceStillFollowsItsRegion() {
        EditableModel model = loaded();
        model.adoptAuthoredUVs();
        model.getFaceTextureManager().setFaceMapping(mapping(0, 0f, 0f, 0.5f, 0.5f));
        model.refreshUVs();
        float[] uv = faceUvs(model, 0);
        for (int i = 0; i < uv.length; i++) {
            assertTrue(uv[i] >= -1e-6f && uv[i] <= 0.5f + 1e-6f, "projected into the new region: " + uv[i]);
        }
    }

    @Test
    void aNewMappingOnAnAuthoredFaceProjectsIt() {
        EditableModel model = loaded();
        model.adoptAuthoredUVs();
        // Another material / region (e.g. the user gives the face its own texture): a new mapping, projected.
        model.getFaceTextureManager().setFaceMapping(mapping(1, 0.25f, 0.25f, 0.75f, 0.75f));
        model.refreshUVs();
        assertFalse(Arrays.equals(AUTHORED_FACE1, faceUvs(model, 1)));
        // Undo of that change (the old mapping record comes back) shows the authored layout again.
        model.getFaceTextureManager().setFaceMapping(mapping(1, 0f, 0f, 1f, 1f).withAuthoredUVs(true));
        model.refreshUVs();
        assertArrayEquals(AUTHORED_FACE1, faceUvs(model, 1), 0f);
    }

    @Test
    void authoredUvsSurviveAPartTransform() {
        EditableModel model = loaded();
        model.adoptAuthoredUVs();
        model.refreshUVs();
        var root = model.getPartManager().getAllParts().get(0);
        model.getPartManager().setPartTransform(root.id(), new com.openmason.engine.modeling.parts.PartTransform(
                new org.joml.Vector3f(), new org.joml.Vector3f(1f, 0f, 0f), new org.joml.Vector3f(0f, 45f, 0f),
                new org.joml.Vector3f(1f, 1f, 1f)));
        assertArrayEquals(AUTHORED_FACE1, faceUvs(model, 1), 0f);
    }
}
