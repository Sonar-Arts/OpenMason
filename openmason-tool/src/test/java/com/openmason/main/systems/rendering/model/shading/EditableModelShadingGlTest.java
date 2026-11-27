package com.openmason.main.systems.rendering.model.shading;

import com.openmason.engine.format.omo.CendaShadingStore;
import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.modeling.EditableModel;
import com.openmason.engine.modeling.uv.FaceTextureMapping;
import com.openmason.engine.modeling.uv.MaterialDefinition;
import com.openmason.engine.rendering.model.draw.ModelLighting;
import com.openmason.engine.rendering.model.draw.ModelRenderer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Real GL (editor side of #169 / #168): the editable model draws its shading through the engine model renderer (#169) - a flat cube face is one
 * colour, Shade Smooth gives it a gradient, Shade Flat brings back the exact flat look - and a material's base
 * colour multiplies its texture like the runtime (#168). Needs a display: opt in with
 * {@code -Dopenmason.model.gl=true}.
 *
 * <p>Scene: camera at the origin looking down -Z, 60 degree FOV, 64x64; a unit cube at z = -3 (front face +Z covers
 * roughly pixels 22..42); key light from the upper right.
 */
class EditableModelShadingGlTest {

    private static final int SIZE = 64;

    private long window;
    private int fbo, colorTex, depthRb, whiteTex;
    private ModelRenderer renderer;
    private EditableModel model;
    private final Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(60), 1f, 0.1f, 100f);
    private final Matrix4f placement = new Matrix4f().translate(0, 0, -3);

    @BeforeEach
    void createContext() {
        assumeTrue(Boolean.getBoolean("openmason.model.gl"), "opt in with -Dopenmason.model.gl=true");
        assumeTrue(CendaShadingStore.isAvailable(), "native shading unavailable");
        assertTrue(glfwInit(), "GLFW display unavailable");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        window = glfwCreateWindow(16, 16, "Editable model shading test", 0, 0);
        assertNotEquals(0, window);
        glfwMakeContextCurrent(window);
        GL.createCapabilities();

        colorTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, colorTex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, SIZE, SIZE, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        depthRb = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, depthRb);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, SIZE, SIZE);
        fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colorTex, 0);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depthRb);
        assertEquals(GL_FRAMEBUFFER_COMPLETE, glCheckFramebufferStatus(GL_FRAMEBUFFER));
        glViewport(0, 0, SIZE, SIZE);

        whiteTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, whiteTex);
        ByteBuffer white = org.lwjgl.BufferUtils.createByteBuffer(4).put(new byte[] {-1, -1, -1, -1}).flip();
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, white);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glBindTexture(GL_TEXTURE_2D, 0);

        renderer = new ModelRenderer();
        renderer.initialize();
        model = new EditableModel();
        model.loadMeshDataAsPart(cube(), "Root");
        model.initialize();
    }

    /** Unit cube soup (24 corners, 6 quads). */
    private static OMOFormat.MeshData cube() {
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
            System.arraycopy(new int[] {b, b + 1, b + 2, b, b + 2, b + 3}, 0, idx, f * 6, 6);
            faceIds[f * 2] = f;
            faceIds[f * 2 + 1] = f;
        }
        return new OMOFormat.MeshData(v, new float[48], idx, faceIds, "FLAT");
    }

    @AfterEach
    void destroyContext() {
        if (window == 0) return;
        if (model != null) model.cleanup();
        if (renderer != null) renderer.cleanup();
        glDeleteTextures(whiteTex);
        glDeleteFramebuffers(fbo);
        glDeleteRenderbuffers(depthRb);
        glDeleteTextures(colorTex);
        int error = glGetError();
        GL.setCapabilities(null);
        glfwDestroyWindow(window);
        glfwTerminate();
        assertEquals(GL_NO_ERROR, error, "GL error during the test");
    }

    /** RGB of the middle row across the front face (pixels 24..40). */
    private int[][] drawRow() {
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glClearColor(0, 0, 0, 0);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glEnable(GL_DEPTH_TEST);
        ModelLighting lighting = new ModelLighting(0.3f, new Vector3f(0.6f, 0.3f, 0.75f).normalize(), 1f, 0f, 0f,
                new Vector3f(), new Vector3f(), new Vector3f(1));
        model.render(renderer, placement, new Matrix4f(), projection, lighting);
        ByteBuffer px = org.lwjgl.BufferUtils.createByteBuffer(SIZE * 4);
        glReadPixels(0, SIZE / 2, SIZE, 1, GL_RGBA, GL_UNSIGNED_BYTE, px);
        int[][] out = new int[17][];
        for (int x = 24; x <= 40; x++) {
            int o = x * 4;
            out[x - 24] = new int[] {px.get(o) & 0xFF, px.get(o + 1) & 0xFF, px.get(o + 2) & 0xFF};
        }
        return out;
    }

    private static int spread(int[][] row) {
        int min = 255, max = 0;
        for (int[] p : row) {
            min = Math.min(min, p[0]);
            max = Math.max(max, p[0]);
        }
        return max - min;
    }

    @Test
    void flatSmoothFlatOnTheGpu() {
        int[][] flat = drawRow();
        assertTrue(flat[8][0] > 0, "the front face is drawn");
        assertTrue(spread(flat) <= 1, "a flat face is one colour");

        model.getShading().setSmoothAll(null, true);
        int[][] smooth = drawRow();
        assertTrue(spread(smooth) >= 6, "smooth corner normals give the face a gradient, spread " + spread(smooth));

        model.getShading().setSmoothAll(null, false);
        int[][] again = drawRow();
        for (int i = 0; i < flat.length; i++) assertArrayEquals(flat[i], again[i], "Shade Flat restores the look");
    }

    @Test
    void aMaterialBaseColourMultipliesItsTexture() {
        model.getFaceTextureManager().registerMaterial(new MaterialDefinition(5, "Red", whiteTex,
                MaterialDefinition.RenderLayer.OPAQUE, new MaterialDefinition.MaterialProperties(false, -1,
                new float[] {1f, 0f, 0f, 1f})));
        int[] faces = {0, 1, 2, 3, 4, 5};
        int[] mats = {5, 5, 5, 5, 5, 5};
        model.setFaceMaterials(faces, mats);
        assertEquals(5, model.getFaceTextureManager().getFaceMapping(0).materialId());
        assertEquals(FaceTextureMapping.FULL_REGION, model.getFaceTextureManager().getFaceMapping(0).uvRegion());
        int[] p = drawRow()[8];
        assertTrue(p[0] > 40, "red from the base colour: " + p[0]);
        assertTrue(p[1] <= 2 && p[2] <= 2, "white texture x red base colour has no green / blue: " + p[1] + "," + p[2]);
    }
}
