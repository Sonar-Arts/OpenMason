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
 * must move too although no track drives the rider.
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
}
