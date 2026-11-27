package com.openmason.main.systems.rendering.model;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.modeling.EditableModel;
import com.openmason.engine.modeling.parts.ModelPartDescriptor;
import com.openmason.engine.modeling.parts.ModelPartManager;
import com.openmason.engine.modeling.parts.PartMeshRebuilder;
import com.openmason.engine.modeling.parts.PartTransform;
import com.openmason.engine.rendering.model.draw.JointPaletteTexture;
import com.openmason.engine.rendering.model.draw.ModelLighting;
import com.openmason.engine.rendering.model.draw.ModelRenderer;
import com.openmason.engine.rendering.model.draw.ModelSkinGlsl;
import com.openmason.engine.rig.CendaRig;
import com.openmason.engine.rig.JointPalette;
import com.openmason.engine.rig.Skin;
import com.openmason.main.systems.skeleton.BoneStore;
import com.openmason.main.systems.skeleton.RigWiring;
import com.openmason.main.systems.skeleton.SkinStore;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL31.*;

/**
 * OpenMason #175 F2 on real GL: the editable model's GPU skin (per-corner {@code PackedSkin} + the part palette)
 * moves exactly what the CPU twin computes - read back with transform feedback through the engine's own
 * {@link ModelSkinGlsl} - for a part drag and for a posed skinned part, and a palette-drawn drag renders the same
 * picture as the re-baked mesh. Needs a display: opt in with {@code -Dopenmason.model.gl=true}.
 */
class EditableModelPaletteGlTest {

    private static final int SIZE = 64;

    private long window;
    private int fbo, colorTex, depthRb;
    private ModelRenderer renderer;
    private EditableModel model;
    private final Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(60), 1f, 0.1f, 100f);
    private final Matrix4f placement = new Matrix4f().translate(-1.5f, 0, -6);

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
        window = glfwCreateWindow(16, 16, "Editable model palette test", 0, 0);
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

    private List<String> twoCubes() {
        ModelPartManager pm = model.getPartManager();
        String a = pm.addPartFromGeometry("a", cube(0f), new Vector3f()).id();
        String b = pm.addPartFromGeometry("b", cube(3f), new Vector3f(3f, 0, 0)).id();
        return List.of(a, b);
    }

    /** The engine's skinning GLSL run over every corner of the model's GPU mesh, captured by transform feedback. */
    private float[] gpuSkinnedCorners(Matrix4f base) {
        model.render(renderer, base, new Matrix4f(), projection, lighting()); // syncs the GPU mesh + skin
        JointPalette palette = model.computePalette(base);
        int corners = model.getTotalVertexCount();

        String vs = "#version 330 core\n" + ModelSkinGlsl.VERTEX + """
                layout (location = 0) in vec3 aPos;
                out vec3 tfPos;
                void main() {
                    tfPos = skinPosition(skinMatrix(), aPos).xyz;
                    gl_Position = vec4(0.0);
                }
                """;
        int shader = glCreateShader(GL_VERTEX_SHADER);
        glShaderSource(shader, vs);
        glCompileShader(shader);
        assertEquals(GL_TRUE, glGetShaderi(shader, GL_COMPILE_STATUS), glGetShaderInfoLog(shader));
        int program = glCreateProgram();
        glAttachShader(program, shader);
        glTransformFeedbackVaryings(program, new CharSequence[]{"tfPos"}, GL_INTERLEAVED_ATTRIBS);
        glLinkProgram(program);
        assertEquals(GL_TRUE, glGetProgrami(program, GL_LINK_STATUS), glGetProgramInfoLog(program));

        int out = glGenBuffers();
        glBindBuffer(GL_TRANSFORM_FEEDBACK_BUFFER, out);
        glBufferData(GL_TRANSFORM_FEEDBACK_BUFFER, corners * 12L, GL_STATIC_READ);
        try (JointPaletteTexture paletteTexture = new JointPaletteTexture()) {
            paletteTexture.upload(palette);
            paletteTexture.bind(JointPaletteTexture.DEFAULT_UNIT);
            model.getGpuMesh().bindSkinInfluences(ModelSkinGlsl.INFLUENCES_UNIT);
            glUseProgram(program);
            glUniform1i(glGetUniformLocation(program, "u_jointPalette"), JointPaletteTexture.DEFAULT_UNIT);
            glUniform1i(glGetUniformLocation(program, "u_skinInfluences"), ModelSkinGlsl.INFLUENCES_UNIT);
            glUniform1i(glGetUniformLocation(program, "u_skinned"), 1);
            glBindVertexArray(model.getGpuMesh().vao());
            glBindBufferBase(GL_TRANSFORM_FEEDBACK_BUFFER, 0, out);
            glEnable(GL_RASTERIZER_DISCARD);
            glBeginTransformFeedback(GL_POINTS);
            glDrawArrays(GL_POINTS, 0, corners);
            glEndTransformFeedback();
            glDisable(GL_RASTERIZER_DISCARD);
            glBindVertexArray(0);
            glUseProgram(0);
            JointPaletteTexture.unbind(JointPaletteTexture.DEFAULT_UNIT);
            JointPaletteTexture.unbind(ModelSkinGlsl.INFLUENCES_UNIT);
        }
        FloatBuffer result = BufferUtils.createFloatBuffer(corners * 3);
        glGetBufferSubData(GL_TRANSFORM_FEEDBACK_BUFFER, 0, result);
        glBindBufferBase(GL_TRANSFORM_FEEDBACK_BUFFER, 0, 0);
        glBindBuffer(GL_TRANSFORM_FEEDBACK_BUFFER, 0);
        glDeleteBuffers(out);
        glDeleteProgram(program);
        glDeleteShader(shader);
        float[] a = new float[corners * 3];
        result.get(a);
        return a;
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

    @Test
    void aDraggedPartIsSkinnedOnTheGpuExactlyAsTheCpuTwin() {
        List<String> ids = twoCubes();
        ModelPartManager pm = model.getPartManager();
        PartTransform t = pm.getPartById(ids.get(1)).orElseThrow().transform();
        Matrix4f base = new Matrix4f().translate(0.5f, -0.25f, 1f).rotateX(0.2f);
        long rebuilds = pm.getRebuildCount();
        pm.beginTransformInteraction();
        pm.setPartTransform(ids.get(1), new PartTransform(t.origin(), new Vector3f(0.4f, 1.1f, -0.3f),
                new Vector3f(15, 30, 45), new Vector3f(1.2f, 0.8f, 1f)));
        float[] gpu = gpuSkinnedCorners(base);
        assertEquals(rebuilds, pm.getRebuildCount(), "the GPU path re-bakes nothing");
        assertArrayEquals(model.getDisplayedCornerPositions(base), gpu, 1e-4f);
        pm.endTransformInteraction();
    }

    @Test
    void aPaletteDrawnDragLooksLikeTheRebakedMesh() {
        List<String> ids = twoCubes();
        ModelPartManager pm = model.getPartManager();
        PartTransform t = pm.getPartById(ids.get(1)).orElseThrow().transform();
        pm.beginTransformInteraction();
        pm.setPartTransform(ids.get(1), new PartTransform(t.origin(), new Vector3f(0, 0.6f, 0),
                new Vector3f(0, 35, 20), new Vector3f(1, 1, 1)));
        int[] dragged = drawFrame();
        pm.endTransformInteraction();
        int[] baked = drawFrame();
        int lit = 0, different = 0;
        for (int i = 0; i < baked.length; i++) {
            if (baked[i] != 0) lit++;
            if (baked[i] != dragged[i]) different++;
        }
        assertTrue(lit > 200, "both cubes are drawn: " + lit);
        // Same picture up to edge rasterisation of the float-different (W · B⁻¹ vs baked) vertices.
        assertTrue(different <= 12, different + " of " + lit + " pixels differ");
    }

    @Test
    void aPosedSkinIsGpuSkinnedLikeTheCpuTwinAndSkinDeform() {
        ModelPartManager pm = model.getPartManager();
        float[] local = {0, 0, 0, 0, 2, 0, 1, 2, 0};
        ModelPartDescriptor arm = pm.addPartFromGeometry("arm",
                PartMeshRebuilder.PartGeometry.of(local.clone(), null, new int[]{0, 1, 2}, new int[]{0}),
                new Vector3f());
        PartTransform rest = new PartTransform(new Vector3f(), new Vector3f(2, 0, 0), new Vector3f(),
                new Vector3f(1, 1, 1));
        pm.setPartTransform(arm.id(), rest);
        BoneStore bones = new BoneStore();
        SkinStore skins = new SkinStore();
        RigWiring.connect(pm, bones, skins);
        bones.setBones(List.of(new OMOFormat.BoneEntry("elbow", "elbow", null,
                0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0)));
        Skin skin = skins.ensureSkin(arm.id(), local, null);
        int elbow = skins.jointIndex("elbow");
        skin.set(1, new int[]{elbow}, new float[]{1f});
        skin.set(2, new int[]{elbow, skins.jointIndex(arm.id())}, new float[]{0.7f, 0.3f});
        skins.fireChanged();
        long rebuilds = pm.getRebuildCount();
        bones.setPose(Map.of("elbow", new BoneStore.BonePose(new Vector3f(), new Vector3f(0, 0, 90))),
                id -> arm.id().equals(id) ? rest : null, true);

        Matrix4f base = new Matrix4f();
        float[] gpu = gpuSkinnedCorners(base);
        assertEquals(rebuilds, pm.getRebuildCount(), "posing re-bakes nothing");
        float[] cpu = model.getDisplayedCornerPositions(base);
        assertArrayEquals(cpu, gpu, 1e-4f);
        // (2,2,0) -> (-1,3,0) fully on the bone; (3,2,0) 0.7 bone (-1,4,0) + 0.3 part (3,2,0) = (0.2,3.4,0).
        float[] baked = model.getAllMeshVertexPositions();
        for (int c = 0; c < baked.length / 3; c++) {
            float[] want = baked[c * 3 + 1] < 1f ? new float[]{2, 0, 0}
                    : baked[c * 3] < 2.5f ? new float[]{-1, 3, 0} : new float[]{0.2f, 3.4f, 0};
            assertArrayEquals(want, new float[]{gpu[c * 3], gpu[c * 3 + 1], gpu[c * 3 + 2]}, 2e-4f, "corner " + c);
        }
    }
}
