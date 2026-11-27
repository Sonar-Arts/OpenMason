package com.openmason.engine.rendering.model.draw;

import com.openmason.engine.entity.model.EntityFace;
import com.openmason.engine.entity.model.EntityGeometry;
import com.openmason.engine.entity.model.EntityPart;
import com.openmason.engine.format.oma.ParsedAnimClip;
import com.openmason.engine.format.oma.ParsedAnimTrack;
import com.openmason.engine.format.oma.ParsedKeyframe;
import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.rig.CendaRig;
import com.openmason.engine.rig.JointPalette;
import com.openmason.engine.rig.RigModel;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Real GL: the engine model renderer skins on the GPU from a native rig's palette. Needs a display: opt
 * in with {@code -Dopenmason.model.gl=true}.
 *
 * <p>Scene: camera at the origin looking down −Z, 90° FOV, 64×64. Two parts, each a small square at
 * {@code z = −2}: "mover" centred at x = −0.5 (pixel 24) and "rider" centred at x = +0.5 (pixel 40). A
 * clip moves "mover" by +1 in x (16 px). The rider's vertices carry skin weights to "mover", so they
 * must move too although no track drives the rider. Rig ABI 3 (#170): a vertex with more than 4 influences blends
 * all of them; corner normals (#169) light the lit pass, flat corners keep the derivative face normal, and the
 * material base colour multiplies the texture.
 */
class ModelSkinningGlTest {

    private static final int SIZE = 64;

    private long window;
    private int fbo, colorTex, depthRb;
    private ModelRenderer renderer;
    private UploadedModel model;
    private final Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(90), 1f, 0.1f, 100f);

    @BeforeEach
    void createContext() {
        assumeTrue(Boolean.getBoolean("openmason.model.gl"), "opt in with -Dopenmason.model.gl=true");
        assumeTrue(CendaRig.isAvailable(), "native rig unavailable");
        assertTrue(glfwInit(), "GLFW display unavailable");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        window = glfwCreateWindow(16, 16, "Model skinning test", 0, 0);
        assertNotEquals(0, window);
        glfwMakeContextCurrent(window);
        GL.createCapabilities();

        colorTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, colorTex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, SIZE, SIZE, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        glBindTexture(GL_TEXTURE_2D, 0);
        depthRb = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, depthRb);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, SIZE, SIZE);
        fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colorTex, 0);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depthRb);
        assertEquals(GL_FRAMEBUFFER_COMPLETE, glCheckFramebufferStatus(GL_FRAMEBUFFER));
        glViewport(0, 0, SIZE, SIZE);

        renderer = new ModelRenderer();
        renderer.initialize();
        assertTrue(renderer.isInitialized());
    }

    @AfterEach
    void destroyContext() {
        if (window == 0) return;
        if (model != null) model.close();
        if (renderer != null) renderer.cleanup();
        glDeleteFramebuffers(fbo);
        glDeleteRenderbuffers(depthRb);
        glDeleteTextures(colorTex);
        int error = glGetError();
        GL.setCapabilities(null);
        glfwDestroyWindow(window);
        glfwTerminate();
        assertEquals(GL_NO_ERROR, error, "GL error during the test");
    }

    private static EntityGeometry geometry(boolean riderSkinnedToMover) {
        float h = 0.15f;
        float[] v = {
                -0.5f - h, -h, -2, -0.5f + h, -h, -2, -0.5f + h, h, -2, -0.5f - h, h, -2,
                0.5f - h, -h, -2, 0.5f + h, -h, -2, 0.5f + h, h, -2, 0.5f - h, h, -2};
        int[] idx = {0, 1, 2, 0, 2, 3, 4, 5, 6, 4, 6, 7};
        List<EntityPart> parts = List.of(
                new EntityPart("m", "mover", null, new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f(1),
                        List.of(new EntityFace(0, 0, 0, 6))),
                new EntityPart("r", "rider", null, new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f(1),
                        List.of(new EntityFace(1, 0, 6, 6))));
        OMOFormat.SkinData skin = null;
        if (riderSkinnedToMover) {
            List<OMOFormat.SkinVertex> rows = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) rows.add(new OMOFormat.SkinVertex(i, new int[]{0}, new float[]{1f}));
            skin = new OMOFormat.SkinData(List.of("m"), List.of(new OMOFormat.PartSkin("r", rows)));
        }
        int[] partOf = EntityGeometry.partJointOfVertex(parts, idx, v);
        RigModel rig = RigModel.build(EntityGeometry.partSpecs(parts), List.of(), skin, new int[]{0, 4}, partOf);
        return new EntityGeometry(v, new float[16], idx, parts, Map.of(), -h, List.of(), rig);
    }

    private static ParsedAnimClip moveMover() {
        return new ParsedAnimClip("move", 30f, 1f, true, List.of(new ParsedAnimTrack("m", "mover", List.of(
                new ParsedKeyframe(0f, new Vector3f(1, 0, 0), new Vector3f(), new Vector3f(1), "LINEAR")))));
    }

    private int[] drawAndRead(EntityGeometry geometry, ClipLayers layers) {
        if (model != null) model.close();
        model = UploadedModel.upload(geometry);
        JointPalette palette = ModelPoseSolver.pose(geometry, layers, new Matrix4f(), 0f, 0f, null, new JointPalette());
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glClearColor(0, 0, 0, 0);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        renderer.drawFlat(model.mesh(), model.plan(), palette, new Matrix4f(), projection, new Vector4f(1, 0, 0, 1));
        ByteBuffer px = org.lwjgl.BufferUtils.createByteBuffer(SIZE * SIZE * 4);
        glReadPixels(0, 0, SIZE, SIZE, GL_RGBA, GL_UNSIGNED_BYTE, px);
        int[] red = new int[SIZE];
        int row = SIZE / 2;
        for (int x = 0; x < SIZE; x++) red[x] = px.get((row * SIZE + x) * 4) & 0xFF;
        return red;
    }

    @Test
    void restPoseDrawsBothSquaresWhereAuthored() {
        int[] row = drawAndRead(geometry(true), ClipLayers.REST);
        assertTrue(row[24] > 0, "mover at pixel 24");
        assertTrue(row[40] > 0, "rider at pixel 40");
        assertEquals(0, row[32]);
        assertEquals(0, row[56]);
    }

    @Test
    void aRigidPartMovesWithItsJoint() {
        int[] row = drawAndRead(geometry(false), ClipLayers.single(moveMover(), 0f));
        assertEquals(0, row[24], "mover left pixel 24");
        assertTrue(row[40] > 0, "mover arrived at 40 (rider also there)");
        assertEquals(0, row[56], "the unweighted rider stayed put");
    }

    @Test
    void skinnedVerticesFollowTheirWeightedJoint() {
        int[] row = drawAndRead(geometry(true), ClipLayers.single(moveMover(), 0f));
        assertEquals(0, row[24]);
        assertTrue(row[40] > 0, "mover now at 40");
        assertTrue(row[56] > 0, "rider skinned to mover moved +16 px to 56");
    }

    /**
     * Six joints d0..d5 (no geometry) and the rider square, every rider vertex weighted EQUALLY to all six. A clip
     * moves only d5, by +6: the blend of all six moves the rider by +1 (16 px). ABI 2's 4-influence cap kept d0..d3
     * (equal weights: the lower joints) and the rider would not have moved.
     */
    @Test
    void sixInfluencesAllBlendOnTheGpu() {
        float h = 0.15f;
        float[] v = {0.5f - h, -h, -2, 0.5f + h, -h, -2, 0.5f + h, h, -2, 0.5f - h, h, -2};
        int[] idx = {0, 1, 2, 0, 2, 3};
        List<EntityPart> parts = new java.util.ArrayList<>();
        List<String> jointIds = new java.util.ArrayList<>();
        for (int k = 0; k < 6; k++) {
            parts.add(new EntityPart("d" + k, "d" + k, null, new Vector3f(), new Vector3f(), new Vector3f(),
                    new Vector3f(1), List.of()));
            jointIds.add("d" + k);
        }
        parts.add(new EntityPart("r", "rider", null, new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f(1),
                List.of(new EntityFace(0, 0, 0, 6))));
        List<OMOFormat.SkinVertex> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) rows.add(new OMOFormat.SkinVertex(i, new int[]{0, 1, 2, 3, 4, 5},
                new float[]{1, 1, 1, 1, 1, 1}));
        OMOFormat.SkinData skin = new OMOFormat.SkinData(jointIds, List.of(new OMOFormat.PartSkin("r", rows)));
        int[] partOf = EntityGeometry.partJointOfVertex(parts, idx, v);
        RigModel rig = RigModel.build(EntityGeometry.partSpecs(parts), List.of(), skin, new int[7], partOf);
        assertEquals(6, rig.packed().ranges()[1], "all six influences packed");
        EntityGeometry g = new EntityGeometry(v, new float[8], idx, parts, Map.of(), -h, List.of(), rig);
        ParsedAnimClip moveD5 = new ParsedAnimClip("move", 30f, 1f, true, List.of(new ParsedAnimTrack("d5", "d5",
                List.of(new ParsedKeyframe(0f, new Vector3f(6, 0, 0), new Vector3f(), new Vector3f(1), "LINEAR")))));

        int[] rest = drawAndRead(g, ClipLayers.REST);
        assertTrue(rest[40] > 0, "rider at pixel 40");
        int[] row = drawAndRead(g, ClipLayers.single(moveD5, 0f));
        assertEquals(0, row[40], "the rider left pixel 40");
        assertTrue(row[56] > 0, "the six-way blend moved it +1 (16 px)");
    }

    /** A white-textured square facing the camera at x = +0.5 (pixel 40), with the given corner normals. */
    private static EntityGeometry litSquare(short[] normals, float[] baseColor) {
        float h = 0.3f;
        float[] v = {0.5f - h, -h, -2, 0.5f + h, -h, -2, 0.5f + h, h, -2, 0.5f - h, h, -2};
        int[] idx = {0, 1, 2, 0, 2, 3};
        List<EntityPart> parts = List.of(new EntityPart("s", "square", null, new Vector3f(), new Vector3f(),
                new Vector3f(), new Vector3f(1), List.of(new EntityFace(0, 0, 0, 6))));
        var white = new com.openmason.engine.entity.model.MaterialImage(1, 1, new byte[]{-1, -1, -1, -1}, baseColor);
        return new EntityGeometry(v, new float[8], idx, parts, Map.of(0, white), -h, List.of(), null, normals);
    }

    /** Lit pass, key light toward +Z (the camera), ambient 0.8; RGB of pixel (40, 32). */
    private int[] drawLitPixel(EntityGeometry geometry) {
        if (model != null) model.close();
        model = UploadedModel.upload(geometry);
        JointPalette palette = ModelPoseSolver.pose(geometry, ClipLayers.REST, new Matrix4f(), 0f, 0f, null,
                new JointPalette());
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glClearColor(0, 0, 0, 0);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        ModelLighting lighting = new ModelLighting(0.8f, new Vector3f(0, 0, 1), 1f, 0f, 0f, new Vector3f(),
                new Vector3f(), new Vector3f(1));
        renderer.drawLit(model.mesh(), model.plan(), model, palette, new Matrix4f(), projection, lighting, null);
        ByteBuffer px = org.lwjgl.BufferUtils.createByteBuffer(4);
        glReadPixels(40, SIZE / 2, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, px);
        return new int[]{px.get(0) & 0xFF, px.get(1) & 0xFF, px.get(2) & 0xFF};
    }

    private static short[] all(int vertices, short x, short y) {
        short[] n = new short[vertices * 2];
        for (int i = 0; i < vertices; i++) {
            n[i * 2] = x;
            n[i * 2 + 1] = y;
        }
        return n;
    }

    @Test
    void cornerNormalsLightTheLitPassAndFlatCornersKeepTheFaceNormal() {
        int[] none = drawLitPixel(litSquare(null, null));
        // Face normal +Z toward the light: 0.8 * (0.5 + 0.55) = 0.84.
        assertEquals(214, none[0], 2);
        int[] flat = drawLitPixel(litSquare(all(4, ModelNormalGlsl.FLAT, ModelNormalGlsl.FLAT), null));
        assertEquals(none[0], flat[0], "FLAT corners light exactly like a mesh without normals");
        // Oct (16383, 0) decodes to normalize(0.5, 0, 0.5): 45 degrees off the light -> 0.8 * (0.5 + 0.55 * 0.7071).
        int[] tilted = drawLitPixel(litSquare(all(4, (short) 16383, (short) 0), null));
        assertEquals(Math.round(255 * 0.8f * (0.5f + 0.55f * 0.70711f)), tilted[0], 2);
        // A normal on the far side of the drawn face (oct (32767, 32767) = -Z) is flipped onto the viewer's side
        // (models draw double-sided, as the derivative normal always did): lit like +Z.
        int[] backward = drawLitPixel(litSquare(all(4, (short) 32767, (short) 32767), null));
        assertEquals(none[0], backward[0], 2);
    }

    @Test
    void theMaterialBaseColourMultipliesTheTexture() {
        int[] lit = drawLitPixel(litSquare(null, new float[]{0.5f, 1f, 0.25f, 1f}));
        assertEquals(Math.round(214 * 0.5f), lit[0], 2);
        assertEquals(214, lit[1], 2);
        assertEquals(Math.round(214 * 0.25f), lit[2], 2);
    }
}
