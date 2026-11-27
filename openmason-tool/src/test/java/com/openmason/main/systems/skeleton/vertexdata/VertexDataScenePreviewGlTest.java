package com.openmason.main.systems.skeleton.vertexdata;

import com.openmason.engine.format.omo.MaskPlan;
import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.modeling.EditableModel;
import com.openmason.engine.modeling.core.EditCostStats;
import com.openmason.engine.modeling.parts.ModelPartManager;
import com.openmason.engine.modeling.parts.PartMeshRebuilder;
import com.openmason.engine.modeling.parts.PartTransform;
import com.openmason.engine.rendering.model.draw.ModelLighting;
import com.openmason.engine.rendering.model.draw.ModelRenderer;
import com.openmason.engine.rig.CendaRig;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL31.*;

/**
 * OpenMason #175 INTEG on real GL: the scene viewport's model draw previews the rigging pane's shape-key sliders
 * (GPU morph targets mapped from the combined soup onto the editor's render corners) and hidden vertex groups (a
 * mask plan over the editor's material-sorted index buffer) live - no re-bake, no derive - and the pictures equal
 * the same geometry made for real (the part moved by the key's delta / the part hidden). The uploaded triangle
 * order the model reports maps the GPU index buffer back to its render triangles. Needs a display: opt in with
 * {@code -Dopenmason.model.gl=true}.
 */
class VertexDataScenePreviewGlTest {

    private static final int SIZE = 96;

    private long window;
    private int fbo, colorTex, depthRb;
    private ModelRenderer renderer;
    private EditableModel model;
    private final Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(60), 1f, 0.1f, 100f);
    private final Matrix4f placement = new Matrix4f().translate(-1.5f, -0.4f, -6);

    @BeforeEach
    void createContext() {
        assumeTrue(Boolean.getBoolean("openmason.model.gl"), "opt in with -Dopenmason.model.gl=true");
        assumeTrue(CendaRig.isAvailable() && MaskPlan.isAvailable(), "native rig / masks unavailable");
        assertTrue(glfwInit(), "GLFW display unavailable");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        window = glfwCreateWindow(16, 16, "Scene preview test", 0, 0);
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

        renderer = new ModelRenderer();
        renderer.initialize();
        model = new EditableModel();
        model.initialize();
    }

    @AfterEach
    void destroyContext() {
        if (window == 0) return;
        EditCostStats.setEnabled(false);
        EditCostStats.reset();
        if (model != null) model.cleanup();
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

    private static PartMeshRebuilder.PartGeometry cube(float cx) {
        float h = 0.5f;
        float[] v = {
            -h, -h,  h,   h, -h,  h,   h,  h,  h,  -h,  h,  h,
             h, -h, -h,  -h, -h, -h,  -h,  h, -h,   h,  h, -h,
            -h, -h, -h,  -h, -h,  h,  -h,  h,  h,  -h,  h, -h,
             h, -h,  h,   h, -h, -h,   h,  h, -h,   h,  h,  h,
            -h,  h,  h,   h,  h,  h,   h,  h, -h,  -h,  h, -h,
            -h, -h, -h,   h, -h, -h,   h, -h,  h,  -h, -h,  h,
        };
        for (int i = 0; i < v.length; i += 3) v[i] += cx;
        int[] idx = new int[36];
        int[] faceIds = new int[12];
        for (int f = 0; f < 6; f++) {
            int b = f * 4;
            System.arraycopy(new int[]{b, b + 1, b + 2, b, b + 2, b + 3}, 0, idx, f * 6, 6);
            faceIds[f * 2] = f;
            faceIds[f * 2 + 1] = f;
        }
        return PartMeshRebuilder.PartGeometry.of(v, new float[48], idx, faceIds);
    }

    private static ModelLighting lighting() {
        return new ModelLighting(0.3f, new Vector3f(0.6f, 0.3f, 0.75f).normalize(), 1f, 0f, 0f,
                new Vector3f(), new Vector3f(), new Vector3f(1));
    }

    private int[] drawFrame() {
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glClearColor(0, 0, 0, 0);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glEnable(GL_DEPTH_TEST);
        model.render(renderer, placement, new Matrix4f(), projection, lighting());
        ByteBuffer px = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
        glReadPixels(0, 0, SIZE, SIZE, GL_RGBA, GL_UNSIGNED_BYTE, px);
        int[] out = new int[SIZE * SIZE];
        for (int i = 0; i < out.length; i++) out[i] = px.getInt(i * 4);
        return out;
    }

    private static int lit(int[] frame) {
        int n = 0;
        for (int p : frame) if (p != 0) n++;
        return n;
    }

    private static int differing(int[] a, int[] b) {
        int n = 0;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) n++;
        return n;
    }

    private static int[] range(int n) {
        int[] a = new int[n];
        for (int i = 0; i < n; i++) a[i] = i;
        return a;
    }

    @Test
    void shapeKeySlidersAndHiddenGroupsShowLiveInTheSceneDraw() {
        ModelPartManager pm = model.getPartManager();
        String a = pm.addPartFromGeometry("a", cube(0f), new Vector3f()).id();
        String b = pm.addPartFromGeometry("b", cube(3f), new Vector3f(3f, 0, 0)).id();
        VertexDataAuthoring data = new VertexDataAuthoring(pm);
        model.setVertexDataPreview(data.previewSource());

        float[] lift = new float[24 * 3];
        for (int v = 0; v < 24; v++) lift[v * 3 + 1] = 0.8f;
        data.shapeKeys().add("lift", 0f, 0f, 1f,
                List.of(new OMOFormat.MorphPart(b, range(24), lift, null)), false);
        data.groups().add("hideA");
        data.groups().assign("hideA", a, range(24), 1f, null);

        int[] rest = drawFrame();
        assertTrue(lit(rest) > 400, "both cubes drawn: " + lit(rest));
        assertTrue(model.getGpuMesh().hasMorphs(), "the shape keys reached the GPU mesh");

        // The slider deforms the draw live: no re-bake, no derivation
        long rebuilds = pm.getRebuildCount();
        EditCostStats.reset();
        EditCostStats.setEnabled(true);
        data.preview().setValue("lift", 1f);
        int[] lifted = drawFrame();
        assertEquals(rebuilds, pm.getRebuildCount(), "a slider re-bakes nothing");
        assertEquals(0, EditCostStats.totalCalls(EditCostStats.Span.DERIVE), "a slider derives nothing");
        assertTrue(differing(rest, lifted) > 50, "the key moves cube b on screen");

        // Hiding a group cuts its triangles out of the draw, live
        data.preview().setHidden("hideA", true);
        int[] liftedNoA = drawFrame();
        assertEquals(rebuilds, pm.getRebuildCount(), "hiding a group re-bakes nothing");
        assertEquals(0, EditCostStats.totalCalls(EditCostStats.Span.DERIVE), "hiding a group derives nothing");
        assertTrue(lit(liftedNoA) < lit(lifted) - 150, "cube a vanished: " + lit(liftedNoA) + " vs " + lit(lifted));
        assertUploadedOrderMapsTheIndexBuffer();

        // Reference: the same geometry made for real (cube b moved by the key's delta, cube a hidden)
        data.preview().setValue("lift", 0f);
        data.preview().setHidden("hideA", false);
        PartTransform t = pm.getPartById(b).orElseThrow().transform();
        pm.setPartTransform(b, new PartTransform(t.origin(), new Vector3f(0, 0.8f, 0), t.rotation(), t.scale()));
        int[] movedForReal = drawFrame();
        assertTrue(differing(lifted, movedForReal) <= 12,
                differing(lifted, movedForReal) + " px differ between the slider and the moved part");
        pm.setPartVisible(a, false);
        int[] bothForReal = drawFrame();
        assertTrue(differing(liftedNoA, bothForReal) <= 12,
                differing(liftedNoA, bothForReal) + " px differ between the hidden group and the hidden part");
    }

    /** The GPU index buffer, slot by slot, is the render triangle {@code getUploadedTriangleOrder()} names. */
    private void assertUploadedOrderMapsTheIndexBuffer() {
        int[] order = model.getUploadedTriangleOrder();
        int[] indices = model.getIndices();
        int count = model.getGpuMesh().indexCount();
        assertEquals(indices.length, count);
        assertEquals(indices.length / 3, order.length);
        glBindVertexArray(model.getGpuMesh().vao());
        IntBuffer uploaded = BufferUtils.createIntBuffer(count);
        glGetBufferSubData(GL_ELEMENT_ARRAY_BUFFER, 0, uploaded);
        glBindVertexArray(0);
        for (int slot = 0; slot < order.length; slot++) {
            for (int k = 0; k < 3; k++) {
                assertEquals(indices[order[slot] * 3 + k], uploaded.get(slot * 3 + k), "slot " + slot);
            }
        }
    }
}
