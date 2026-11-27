package com.openmason.engine.rendering.model.draw;

import com.openmason.engine.anim.AnimClipDocument;
import com.openmason.engine.entity.model.EntityFace;
import com.openmason.engine.entity.model.EntityGeometry;
import com.openmason.engine.entity.model.EntityModelLoader;
import com.openmason.engine.entity.model.EntityPart;
import com.openmason.engine.format.oma.ParsedAnimClip;
import com.openmason.engine.format.oma.ParsedAnimTrack;
import com.openmason.engine.format.oma.ParsedKeyframe;
import com.openmason.engine.format.omo.MaskPlan;
import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.format.omo.OMOReader;
import com.openmason.engine.format.omo.OmoDocument;
import com.openmason.engine.rig.CendaRig;
import com.openmason.engine.rig.JointPalette;
import com.openmason.engine.rig.RigModel;
import com.openmason.engine.rig.RigMorphs;
import com.openmason.engine.rig.Skin;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
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
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL31.GL_TEXTURE_BUFFER;

/**
 * Real GL (opt in with {@code -Dopenmason.model.gl=true}): shape keys (#171) and vertex-group masks (#172) on the GPU.
 * <ul>
 *   <li>The engine GLSL ({@link ModelSkinGlsl#VERTEX} with {@link ModelMorphGlsl}) run over a {@link ModelGpuMesh} with
 *       transform feedback: morphed positions == {@code cr_morph_apply}, morphed normals == its normals, and
 *       morphed-then-skinned == {@code cr_skin_deform_morphed}; weights from a sampled OMANIM 1.3 clip reach it.</li>
 *   <li>{@link ModelRenderer} draws the morphed shape (pixels) and, given an appearance hiding a group, issues exactly
 *       the surviving triangles (a primitives-generated query).</li>
 *   <li>A mesh without shape keys draws the same pixels with or without an appearance carrying weights.</li>
 * </ul>
 */
class ModelMorphGlTest {

    private static final int SIZE = 64;
    private static final float EPS = 2e-5f;

    private long window;
    private int fbo, colorTex, depthRb;
    private ModelRenderer renderer;
    private final Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(90), 1f, 0.1f, 100f);

    @BeforeEach
    void createContext() {
        assumeTrue(Boolean.getBoolean("openmason.model.gl"), "opt in with -Dopenmason.model.gl=true");
        assumeTrue(CendaRig.isAvailable(), "native rig unavailable");
        assumeTrue(MaskPlan.isAvailable(), "native masks unavailable");
        assertTrue(glfwInit(), "GLFW display unavailable");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        window = glfwCreateWindow(16, 16, "Model morph test", 0, 0);
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

    // --- fixtures ----------------------------------------------------------------------------------------

    /**
     * Two parts of a small square each at z = -2: "mover" centred at x = -0.5, "rider" at x = +0.5 (pixels 24 / 40).
     * Shape key "slide" moves every rider vertex by +1 in x (16 px) and turns its normal; "tilt" moves two mover
     * corners in z (normal deltas too). Rider vertices optionally skinned to the mover.
     */
    private static EntityGeometry squares(boolean riderSkinned) {
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
        RigMorphs morphs = RigMorphs.create(8, List.of("slide", "tilt"), new float[]{0, 0, 1, 0, -1, 1},
                new int[]{0, 4, 6},
                new int[]{4, 5, 6, 7, 1, 2},
                new float[]{1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0.25f, 0, 0, -0.125f},
                new float[]{0.5f, 0, 0, 0.5f, 0, 0, 0, 0.5f, 0, 0, 0, 0, 0, 0, 0, 0.25f, 0, 0});
        OMOFormat.SkinData skin = null;
        if (riderSkinned) {
            List<OMOFormat.SkinVertex> rows = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) rows.add(new OMOFormat.SkinVertex(i, new int[]{0}, new float[]{1f}));
            skin = new OMOFormat.SkinData(List.of("m"), List.of(new OMOFormat.PartSkin("r", rows)));
        }
        int[] partOf = EntityGeometry.partJointOfVertex(parts, idx, v);
        RigModel rig = RigModel.build(EntityGeometry.partSpecs(parts), List.of(), skin, new int[]{0, 4}, partOf, morphs);
        return new EntityGeometry(v, new float[16], idx, parts, MATERIALS, -h, List.of(), rig);
    }

    /** One colour-only material (id 0, what both faces use), so the lit pass draws the squares too. */
    private static final Map<Integer, com.openmason.engine.entity.model.MaterialImage> MATERIALS =
            Map.of(0, com.openmason.engine.entity.model.MaterialImage.solid(new float[]{0.8f, 0.5f, 0.2f, 1f}));

    private static ParsedAnimClip moveMover() {
        return new ParsedAnimClip("move", 30f, 1f, true, List.of(new ParsedAnimTrack("m", "mover", List.of(
                new ParsedKeyframe(0f, new Vector3f(0, 0.5f, 0), new Vector3f(0, 0, 10), new Vector3f(1),
                        "LINEAR")))));
    }

    // --- transform feedback over the engine GLSL --------------------------------------------------------

    private static final String TF_VERTEX = """
            #version 330 core
            layout (location = 0) in vec3 aPos;
            """ + ModelSkinGlsl.VERTEX + """
            out vec3 vPos;
            out vec3 vNorm;
            void main() {
                mat4 s = skinMatrix();
                vPos = skinPosition(s, morphPosition(aPos)).xyz;
                vNorm = morphNormal(vec3(0.0, 0.0, 1.0));
                gl_Position = vec4(0.0);
            }
            """;

    private record Captured(float[] positions, float[] normals) {}

    /** Runs the engine morph + skin GLSL over every vertex of {@code mesh} with {@code weights} and {@code palette}. */
    private static Captured capture(ModelGpuMesh mesh, float[] weights, JointPalette palette) {
        int vs = glCreateShader(GL_VERTEX_SHADER);
        glShaderSource(vs, TF_VERTEX);
        glCompileShader(vs);
        assertEquals(GL_TRUE, glGetShaderi(vs, GL_COMPILE_STATUS), glGetShaderInfoLog(vs));
        int program = glCreateProgram();
        glAttachShader(program, vs);
        glTransformFeedbackVaryings(program, new CharSequence[]{"vPos", "vNorm"}, GL_INTERLEAVED_ATTRIBS);
        glLinkProgram(program);
        assertEquals(GL_TRUE, glGetProgrami(program, GL_LINK_STATUS), glGetProgramInfoLog(program));
        glUseProgram(program);
        glUniform1i(glGetUniformLocation(program, "u_jointPalette"), JointPaletteTexture.DEFAULT_UNIT);
        glUniform1i(glGetUniformLocation(program, "u_skinInfluences"), ModelSkinGlsl.INFLUENCES_UNIT);
        glUniform1i(glGetUniformLocation(program, "u_morphDeltas"), ModelMorphGlsl.DELTAS_UNIT);
        glUniform1i(glGetUniformLocation(program, "u_morphNormalDeltas"), ModelMorphGlsl.NORMALS_UNIT);
        glUniform1i(glGetUniformLocation(program, "u_morphWeights"), ModelMorphGlsl.WEIGHTS_UNIT);
        glUniform1i(glGetUniformLocation(program, "u_skinned"), palette != null ? 1 : 0);
        glUniform1i(glGetUniformLocation(program, "u_morphed"), 1);
        glUniform1i(glGetUniformLocation(program, "u_morphNormals"), mesh.morphsHaveNormals() ? 1 : 0);

        JointPaletteTexture paletteTexture = new JointPaletteTexture();
        if (palette != null) {
            paletteTexture.upload(palette);
            paletteTexture.bind(JointPaletteTexture.DEFAULT_UNIT);
            mesh.bindSkinInfluences(ModelSkinGlsl.INFLUENCES_UNIT);
        }
        mesh.uploadMorphWeights(weights);
        mesh.bindMorphs();

        int n = mesh.vertexCount();
        int tf = glGenBuffers();
        glBindBuffer(GL_TRANSFORM_FEEDBACK_BUFFER, tf);
        glBufferData(GL_TRANSFORM_FEEDBACK_BUFFER, 24L * n, GL_STATIC_READ);
        glBindBufferBase(GL_TRANSFORM_FEEDBACK_BUFFER, 0, tf);
        glEnable(GL_RASTERIZER_DISCARD);
        glBindVertexArray(mesh.vao());
        glBeginTransformFeedback(GL_POINTS);
        glDrawArrays(GL_POINTS, 0, n);
        glEndTransformFeedback();
        glDisable(GL_RASTERIZER_DISCARD);
        glBindVertexArray(0);
        FloatBuffer out = BufferUtils.createFloatBuffer(6 * n);
        glGetBufferSubData(GL_TRANSFORM_FEEDBACK_BUFFER, 0L, out);
        glBindBufferBase(GL_TRANSFORM_FEEDBACK_BUFFER, 0, 0);
        glDeleteBuffers(tf);
        ModelGpuMesh.unbindMorphs();
        JointPaletteTexture.unbind(JointPaletteTexture.DEFAULT_UNIT);
        JointPaletteTexture.unbind(ModelSkinGlsl.INFLUENCES_UNIT);
        paletteTexture.close();
        glUseProgram(0);
        glDeleteProgram(program);
        glDeleteShader(vs);

        float[] p = new float[n * 3], nn = new float[n * 3];
        for (int v = 0; v < n; v++) {
            for (int c = 0; c < 3; c++) {
                p[v * 3 + c] = out.get(v * 6 + c);
                nn[v * 3 + c] = out.get(v * 6 + 3 + c);
            }
        }
        return new Captured(p, nn);
    }

    private static ModelGpuMesh upload(EntityGeometry g) {
        ModelGpuMesh mesh = new ModelGpuMesh(false, com.openmason.engine.diagnostics.GpuMemoryTracker.Category.ENTITY_MESH);
        mesh.upload(g.vertices(), g.texCoords(), g.indices());
        mesh.updateSkin(g.rig().packed());
        mesh.updateMorphs(g.morphs());
        return mesh;
    }

    private static float[] up(int vertexCount) {
        float[] n = new float[vertexCount * 3];
        for (int v = 0; v < vertexCount; v++) n[v * 3 + 2] = 1f;
        return n;
    }

    @Test
    void gpuMorphEqualsTheNativeApply() {
        EntityGeometry g = squares(false);
        try (ModelGpuMesh mesh = upload(g)) {
            assertTrue(mesh.hasMorphs());
            assertTrue(mesh.morphsHaveNormals());
            for (float[] weights : new float[][]{{0f, 0f}, {1f, 0f}, {0.37f, -0.8f}, {3f, 5f}, {0.5f, Float.NaN}}) {
                Captured gpu = capture(mesh, weights, null);
                float[] cpuP = new float[24], cpuN = new float[24];
                g.morphs().apply(weights, g.vertices(), up(8), cpuP, cpuN);
                assertArrayEquals(cpuP, gpu.positions(), EPS, "positions " + java.util.Arrays.toString(weights));
                assertArrayEquals(cpuN, gpu.normals(), EPS, "normals " + java.util.Arrays.toString(weights));
            }
            // Null weights = the targets' defaults (slide 0, tilt 0).
            assertArrayEquals(g.vertices(), capture(mesh, null, null).positions(), 0f);
        }
    }

    @Test
    void morphedThenSkinnedEqualsTheNativeCpuDeform() {
        EntityGeometry g = squares(true);
        JointPalette palette = ModelPoseSolver.pose(g, ClipLayers.single(moveMover(), 0f),
                new Matrix4f().translation(0.1f, 0f, 0f), 0f, 0f, null, new JointPalette());
        float[] weights = {0.75f, 0.5f};
        try (ModelGpuMesh mesh = upload(g); Skin skin = g.rig().newSkin()) {
            Captured gpu = capture(mesh, weights, palette);
            float[] cpu = Skin.deformMorphed(skin, g.rig().partJointOfVertex(), g.morphs(), weights, g.vertices(),
                    palette, null);
            assertArrayEquals(cpu, gpu.positions(), EPS);
        }
    }

    @Test
    void weightsFromAnOmanim13ClipReachTheShader() throws Exception {
        EntityGeometry g = squares(false);
        byte[] bytes;
        try (AnimClipDocument doc = AnimClipDocument.create()) {
            doc.setName("slide");
            doc.setHeader(new AnimClipDocument.Header(30f, 1f, false, false, 0f, 0f, 0));
            doc.setMorphTrack(new AnimClipDocument.Curve("slide", List.of(
                    AnimClipDocument.CurveKey.linear(0f, 0f), AnimClipDocument.CurveKey.linear(1f, 1f))));
            bytes = doc.write();
        }
        ParsedAnimClip clip;
        try (AnimClipDocument doc = AnimClipDocument.read(bytes)) {
            clip = doc.toParsedClip();
        }
        float[] weights = ModelPoseSolver.morphWeights(g, ClipLayers.single(clip, 0.5f), null);
        assertArrayEquals(new float[]{0.5f, 0f}, weights, 1e-6f);
        try (ModelGpuMesh mesh = upload(g)) {
            Captured gpu = capture(mesh, weights, null);
            assertEquals(0.5f + 0.15f + 0.5f, gpu.positions()[5 * 3], EPS, "rider corner slid by half a unit");
        }

        // The real renderer: at the clip's end the rider's square slides +1 (pixel 40 -> 56).
        UploadedModel model = UploadedModel.upload(g);
        try {
            JointPalette rest = ModelPoseSolver.pose(g, ClipLayers.REST, new Matrix4f(), 0f, 0f, null, new JointPalette());
            int[] before = drawRow(model, rest, new ModelAppearance());
            assertTrue(before[40] > 0 && before[56] == 0, "rider at 40 with default weights");
            float[] end = ModelPoseSolver.morphWeights(g, ClipLayers.single(clip, 1f), null);
            int[] after = drawRow(model, rest, new ModelAppearance().setMorphWeights(end));
            assertEquals(0, after[40], "rider left 40");
            assertTrue(after[56] > 0, "rider slid to 56");
            assertTrue(after[24] > 0, "the mover stayed");
        } finally {
            model.close();
        }
    }

    private int[] drawRow(UploadedModel model, JointPalette palette, ModelAppearance appearance) {
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glClearColor(0, 0, 0, 0);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        renderer.drawFlat(model.mesh(), model.plan(), palette, new Matrix4f(), projection, new Vector4f(1, 0, 0, 1),
                appearance);
        ByteBuffer px = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
        glReadPixels(0, 0, SIZE, SIZE, GL_RGBA, GL_UNSIGNED_BYTE, px);
        int[] red = new int[SIZE];
        for (int x = 0; x < SIZE; x++) red[x] = px.get((SIZE / 2 * SIZE + x) * 4) & 0xFF;
        return red;
    }

    private int[] drawAll(UploadedModel model, JointPalette palette, ModelAppearance appearance, boolean lit) {
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glClearColor(0, 0, 0, 0);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        if (lit) {
            renderer.drawLit(model.mesh(), model.plan(), model, palette, new Matrix4f(), projection,
                    ModelLighting.preview(new Matrix4f()), null, appearance);
        } else {
            renderer.drawFlat(model.mesh(), model.plan(), palette, new Matrix4f(), projection,
                    new Vector4f(0.2f, 0.6f, 1f, 1f), appearance);
        }
        ByteBuffer px = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
        glReadPixels(0, 0, SIZE, SIZE, GL_RGBA, GL_UNSIGNED_BYTE, px);
        int[] out = new int[SIZE * SIZE];
        for (int i = 0; i < out.length; i++) out[i] = px.getInt(i * 4);
        return out;
    }

    // --- masks -----------------------------------------------------------------------------------------

    /** The loader test's two-part model (a = covered_shirt quad, b = "face" + a hem triangle borrowing a vertex). */
    private static EntityGeometry masked() throws Exception {
        float[] positions = {
                -1, -0.5f, -2, 0, -0.5f, -2, 0, 0.5f, -2, -1, 0.5f, -2,
                0.2f, -0.5f, -2, 1, -0.5f, -2, 1, 0.5f, -2, 0.2f, 0.5f, -2};
        try (OmoDocument doc = OmoDocument.create()) {
            doc.setObjectName("mask-test");
            doc.setModelType("custom");
            doc.setGeometry(new OMOFormat.GeometryData(1, 1, 1, new OMOFormat.Position(0, 0, 0)));
            doc.setMesh(new OMOFormat.MeshData(positions, new float[16], new int[]{0, 1, 2, 0, 2, 3, 4, 5, 6, 4, 6, 2},
                    new int[]{0, 0, 1, 1}, null));
            doc.setParts(List.of(
                    new OMOFormat.PartEntry("a", "torso", 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 0, 4, 0, 6, 0, 1, true, false),
                    new OMOFormat.PartEntry("b", "head", 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 4, 4, 6, 6, 1, 1, true, false)));
            doc.setVertexGroups(List.of(
                    new OMOFormat.VertexGroupData("covered_shirt", List.of(
                            new OMOFormat.GroupPart("a", new int[]{0, 1, 2, 3}, new float[]{1, 1, 1, 1}))),
                    new OMOFormat.VertexGroupData("face", List.of(
                            new OMOFormat.GroupPart("b", new int[]{0, 1, 2}, new float[]{1, 1, 1})))));
            return EntityModelLoader.buildGeometry(new OMOReader().read(doc.write()));
        }
    }

    private int primitives(UploadedModel model, JointPalette palette, ModelAppearance appearance) {
        int query = glGenQueries();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glBeginQuery(GL_PRIMITIVES_GENERATED, query);
        renderer.drawFlat(model.mesh(), model.plan(), palette, new Matrix4f(), projection, new Vector4f(1), appearance);
        glEndQuery(GL_PRIMITIVES_GENERATED);
        int n = glGetQueryObjecti(query, GL_QUERY_RESULT);
        glDeleteQueries(query);
        return n;
    }

    @Test
    void hidingAGroupDrawsExactlyTheSurvivingTriangles() throws Exception {
        EntityGeometry g = masked();
        UploadedModel model = UploadedModel.upload(g);
        try {
            JointPalette rest = ModelPoseSolver.pose(g, ClipLayers.REST, new Matrix4f(), 0f, 0f, null, new JointPalette());
            assertEquals(4, primitives(model, rest, null));
            assertEquals(4, primitives(model, rest, new ModelAppearance()));
            assertEquals(2, primitives(model, rest, new ModelAppearance().setHiddenGroups(List.of("covered_shirt"))),
                    "the covered quad's two triangles are gone; the hem triangle stays");
            assertEquals(3, primitives(model, rest, new ModelAppearance().setHiddenGroups(List.of("face"))));
            assertEquals(1, primitives(model, rest, new ModelAppearance().setKeptGroups(List.of("face"))));
            // And in pixels: the torso quad (left half of the frame) disappears.
            int[] all = drawAll(model, rest, null, false);
            int[] hidden = drawAll(model, rest, new ModelAppearance().setHiddenGroups(List.of("covered_shirt")), false);
            int left = SIZE / 2 * SIZE + 22;
            assertNotEquals(0, all[left]);
            assertEquals(0, hidden[left]);
        } finally {
            model.close();
        }
    }

    @Test
    void aMeshWithoutShapeKeysDrawsTheSame() {
        EntityGeometry g = squares(false);
        EntityGeometry plain = new EntityGeometry(g.vertices(), g.texCoords(), g.indices(), g.parts(), MATERIALS,
                g.restMinY(), List.of());
        UploadedModel model = UploadedModel.upload(plain);
        try {
            assertTrue(!model.mesh().hasMorphs());
            JointPalette rest = ModelPoseSolver.pose(plain, ClipLayers.REST, new Matrix4f(), 0f, 0f, null,
                    new JointPalette());
            for (boolean lit : new boolean[]{false, true}) {
                int[] a = drawAll(model, rest, null, lit);
                assertTrue(java.util.Arrays.stream(a).anyMatch(px -> px != 0), "something was drawn (lit=" + lit + ")");
                int[] b = drawAll(model, rest, new ModelAppearance().setMorphWeights(new float[]{1f, 1f}), lit);
                assertArrayEquals(a, b, "weights mean nothing without shape keys (lit=" + lit + ")");
            }
        } finally {
            model.close();
        }
        // And a morphed mesh at its default weights draws the same as the plain one.
        UploadedModel morphed = UploadedModel.upload(g);
        UploadedModel unmorphed = UploadedModel.upload(plain);
        try {
            JointPalette rest = ModelPoseSolver.pose(g, ClipLayers.REST, new Matrix4f(), 0f, 0f, null, new JointPalette());
            assertArrayEquals(drawAll(unmorphed, rest, null, true), drawAll(morphed, rest, null, true));
        } finally {
            morphed.close();
            unmorphed.close();
        }
    }

    @Test
    void theWeightUploadIsTheOnlyPerFrameMorphTraffic() {
        EntityGeometry g = squares(false);
        try (ModelGpuMesh mesh = upload(g)) {
            long before = mesh.uploadedBytes();
            mesh.uploadMorphWeights(new float[]{0.5f, 0.25f});
            assertEquals(8L, mesh.uploadedBytes() - before, "one float per target");
            glBindBuffer(GL_TEXTURE_BUFFER, 0);
        }
    }
}
