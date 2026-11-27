package com.openmason.main.systems.viewport.viewportRendering;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.modeling.EditableModel;
import com.openmason.engine.modeling.core.EditCostStats;
import com.openmason.engine.modeling.core.EditCostStats.Span;
import com.openmason.engine.modeling.parts.ModelPartDescriptor;
import com.openmason.engine.modeling.parts.ModelPartManager;
import com.openmason.engine.modeling.parts.PartMeshRebuilder;
import com.openmason.engine.modeling.parts.PartTransform;
import com.openmason.engine.modeling.uv.FaceTextureManager;
import com.openmason.engine.modeling.uv.MaterialDefinition;
import com.openmason.engine.rendering.model.draw.ModelLighting;
import com.openmason.engine.rendering.shaders.ShaderManager;
import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.openmason.engine.rendering.shaders.ShaderType;
import com.openmason.engine.rendering.viewer.ViewerRenderContext;
import com.openmason.engine.rendering.viewer.camera.ViewerCamera;
import com.openmason.engine.rendering.viewer.transform.TransformState;
import com.openmason.main.systems.rendering.model.editing.subrenders.edge.EdgeHoverDetector;
import com.openmason.main.systems.rendering.model.editing.subrenders.edge.EdgeRenderer;
import com.openmason.main.systems.rendering.model.editing.subrenders.face.FaceRenderer;
import com.openmason.main.systems.rendering.model.editing.subrenders.face.FaceTranslationHandler;
import com.openmason.main.systems.rendering.model.editing.subrenders.vertex.VertexRenderer;
import com.openmason.main.systems.rendering.model.editing.subrenders.vertex.VertexTranslationHandler;
import com.openmason.main.systems.skeleton.BoneStore;
import com.openmason.main.systems.skeleton.RigWiring;
import com.openmason.main.systems.viewport.ViewportUIState;
import com.openmason.main.systems.viewport.state.EditMode;
import com.openmason.main.systems.viewport.state.EditModeManager;
import com.openmason.main.systems.viewport.state.FaceSelectionState;
import com.openmason.main.systems.viewport.state.VertexSelectionState;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * #175 F0: the high-poly edit benchmark. A ~55k-triangle model (four torus parts, 6912 quads each, per-face
 * materials) is loaded into a real {@link EditableModel} with the real vertex / edge / face overlays attached, then
 * the editor's interactive paths are timed end to end with GL (hidden window): load, a single-vertex drag frame, a
 * 49-vertex face drag frame, a drag frame with the mouse still, drag release, a part transform, a group move, hover
 * picking with the mouse still, and a select-all overlay frame. Prints a table (and writes it to
 * {@code target/edit-bench-<label>.txt}). Opt in with {@code -Dopenmason.bench=true}
 * ({@code -Dopenmason.bench.label=before|after}).
 */
class HighPolyEditBenchGlTest {

    private static final int PARTS = 4;
    private static final int U = 96;
    private static final int V = 72;
    private static final int W = 1280;
    private static final int H = 720;
    private static final int FRAMES = 20;

    private long window;
    private int fbo, colorTex, depthRb;
    private final List<Integer> textures = new ArrayList<>();
    private ShaderManager shaders;
    private EditableModel model;
    private VertexRenderer vertexRenderer;
    private EdgeRenderer edgeRenderer;
    private FaceRenderer faceRenderer;
    private ViewerCamera camera;
    private ViewerRenderContext context;
    private TransformState transform;
    private ViewportUIState uiState;
    private BoneStore bones;
    private final StringBuilder report = new StringBuilder();

    @BeforeEach
    void createContext() {
        assumeTrue(Boolean.getBoolean("openmason.bench"), "opt in with -Dopenmason.bench=true");
        assertTrue(glfwInit(), "GLFW display unavailable");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        window = glfwCreateWindow(16, 16, "High-poly edit bench", 0, 0);
        assertNotEquals(0, window);
        glfwMakeContextCurrent(window);
        GL.createCapabilities();

        colorTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, colorTex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, W, H, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        depthRb = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, depthRb);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, W, H);
        fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colorTex, 0);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depthRb);
        assertEquals(GL_FRAMEBUFFER_COMPLETE, glCheckFramebufferStatus(GL_FRAMEBUFFER));
        glViewport(0, 0, W, H);

        shaders = new ShaderManager();
        shaders.initialize();
        camera = new ViewerCamera();
        camera.setAspectRatio((float) W / H);
        camera.setDistance(14f);
        camera.updateMatrices();
        context = new ViewerRenderContext(camera);
        transform = new TransformState();
        uiState = new ViewportUIState();
        EditCostStats.reset();
        EditCostStats.setEnabled(true);
    }

    @AfterEach
    void destroyContext() {
        if (window == 0) return;
        EditCostStats.setEnabled(false);
        EditModeManager.getInstance().setMode(EditMode.NONE);
        if (vertexRenderer != null) vertexRenderer.cleanup();
        if (edgeRenderer != null) edgeRenderer.cleanup();
        if (faceRenderer != null) faceRenderer.cleanup();
        if (model != null) model.cleanup();
        if (shaders != null) shaders.cleanup();
        for (int t : textures) glDeleteTextures(t);
        glDeleteFramebuffers(fbo);
        glDeleteRenderbuffers(depthRb);
        glDeleteTextures(colorTex);
        GL.setCapabilities(null);
        glfwDestroyWindow(window);
        glfwTerminate();
    }

    // =========================================================================
    // Model
    // =========================================================================

    /** One torus part as an OMO-style soup: 4 own corners per quad, 2 triangles per face. */
    private static PartMeshRebuilder.PartGeometry torus(float cx) {
        float big = 1.2f, small = 0.45f;
        float[] v = new float[U * V * 4 * 3];
        float[] uv = new float[U * V * 4 * 2];
        int[] idx = new int[U * V * 6];
        int[] faces = new int[U * V * 2];
        int q = 0;
        for (int i = 0; i < U; i++) {
            for (int j = 0; j < V; j++) {
                int[][] ij = {{i, j}, {i + 1, j}, {i + 1, j + 1}, {i, j + 1}};
                for (int c = 0; c < 4; c++) {
                    double a = 2 * Math.PI * (ij[c][0] % U) / U;
                    double b = 2 * Math.PI * (ij[c][1] % V) / V;
                    int o = (q * 4 + c) * 3;
                    v[o] = cx + (float) ((big + small * Math.cos(b)) * Math.cos(a));
                    v[o + 1] = (float) (small * Math.sin(b));
                    v[o + 2] = (float) ((big + small * Math.cos(b)) * Math.sin(a));
                }
                int base = q * 4;
                int[] t = {base, base + 1, base + 2, base, base + 2, base + 3};
                System.arraycopy(t, 0, idx, q * 6, 6);
                faces[q * 2] = q;
                faces[q * 2 + 1] = q;
                q++;
            }
        }
        return PartMeshRebuilder.PartGeometry.of(v, uv, idx, faces);
    }

    private int texture() {
        int t = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, t);
        ByteBuffer px = org.lwjgl.BufferUtils.createByteBuffer(16 * 4);
        for (int i = 0; i < 16; i++) px.put((byte) 200).put((byte) 180).put((byte) 160).put((byte) -1);
        px.flip();
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 4, 4, 0, GL_RGBA, GL_UNSIGNED_BYTE, px);
        glBindTexture(GL_TEXTURE_2D, 0);
        textures.add(t);
        return t;
    }

    /**
     * The editor's OMO load sequence (ModelOperationService.loadOMOModelFromFile) on the bench model: parts,
     * face textures (+ authored-UV adoption, UV refresh), skeleton. Returns the wall time in ms.
     */
    private double load() {
        model = new EditableModel();
        model.initialize();
        vertexRenderer = new VertexRenderer();
        edgeRenderer = new EdgeRenderer();
        faceRenderer = new FaceRenderer();
        vertexRenderer.initialize();
        edgeRenderer.initialize();
        faceRenderer.initialize();
        vertexRenderer.setModelRenderer(model);
        edgeRenderer.setModelRenderer(model);
        faceRenderer.setEditableModel(model);
        vertexRenderer.setEnabled(true);
        edgeRenderer.setEnabled(true);
        faceRenderer.setEnabled(true);
        bones = new BoneStore();
        RigWiring.connect(model.getPartManager(), bones, null);

        List<ModelPartDescriptor> descriptors = new ArrayList<>();
        Map<String, PartMeshRebuilder.PartGeometry> geometry = new LinkedHashMap<>();
        for (int p = 0; p < PARTS; p++) {
            String id = "part" + p;
            descriptors.add(new ModelPartDescriptor(id, "Part " + p, PartTransform.identity(), null, true, false,
                    null));
            geometry.put(id, torus(-4.5f + 3f * p));
        }
        int[] materials = new int[PARTS];
        for (int p = 0; p < PARTS; p++) materials[p] = texture();

        EditCostStats.reset();
        long t0 = System.nanoTime();
        LoadSequence.run(model, bones, descriptors, geometry, ftm -> {
            for (int p = 0; p < PARTS; p++) {
                ftm.registerMaterial(new MaterialDefinition(500 + p, "M" + p, materials[p],
                        MaterialDefinition.RenderLayer.OPAQUE, MaterialDefinition.MaterialProperties.NONE));
            }
            int faceId = 0;
            for (int p = 0; p < PARTS; p++) {
                for (int f = 0; f < U * V; f++) ftm.assignDefaultMapping(faceId++, 500 + p);
            }
        });
        return (System.nanoTime() - t0) / 1e6;
    }

    // =========================================================================
    // Frame
    // =========================================================================

    private final ModelLighting lighting = new ModelLighting(0.3f, new Vector3f(0.6f, 0.3f, 0.75f).normalize(), 1f,
            0f, 0f, new Vector3f(), new Vector3f(), new Vector3f(1));

    /** One viewport frame: the model's lit pass (GPU sync included) + the three overlays, finished. */
    private void drawFrame() {
        Matrix4f modelMatrix = transform.getTransformMatrix();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glEnable(GL_DEPTH_TEST);
        model.render(shaders.modelRenderer(), modelMatrix, camera.getViewMatrix(), camera.getProjectionMatrix(),
                lighting);
        ShaderProgram basic = shaders.getShaderProgram(ShaderType.BASIC);
        ShaderProgram face = shaders.getShaderProgram(ShaderType.FACE);
        ShaderProgram point = shaders.getShaderProgram(ShaderType.VERTEX);
        long t = EditCostStats.begin();
        faceRenderer.render(basic, face, point, context, modelMatrix);
        edgeRenderer.render(basic, context, modelMatrix);
        vertexRenderer.render(point, context, modelMatrix);
        EditCostStats.end(Span.OVERLAY_DRAW, t);
        glFinish();
    }

    private long uploaded;

    /** Ends a frame for the stats: model upload bytes since the previous frame. */
    private void endFrame() {
        long now = model.getUploadedGpuBytes();
        EditCostStats.count(EditCostStats.Counter.MODEL_UPLOAD_BYTES, now - uploaded);
        uploaded = now;
        EditCostStats.endFrame();
    }

    private interface Step {
        void run(int frame);
    }

    /** Average ms of {@code step + drawFrame} over {@link #FRAMES} frames (after one warm-up). */
    private double frames(String name, Step step) {
        step.run(-1);
        drawFrame();
        endFrame();
        EditCostStats.reset();
        uploaded = model.getUploadedGpuBytes();
        long t0 = System.nanoTime();
        for (int f = 0; f < FRAMES; f++) {
            step.run(f);
            drawFrame();
            endFrame();
        }
        double ms = (System.nanoTime() - t0) / 1e6 / FRAMES;
        row(name, ms);
        return ms;
    }

    private void row(String name, double ms) {
        StringBuilder spans = new StringBuilder();
        for (Span s : Span.values()) {
            long calls = EditCostStats.totalCalls(s);
            if (calls > 0) {
                spans.append(String.format(Locale.ROOT, " %s=%.2fms/%d", s.name(),
                        EditCostStats.totalNanos(s) / 1e6, calls));
            }
        }
        String line = String.format(Locale.ROOT, "%-34s %10.2f ms | draws %d uploads %d modelKB %.1f |%s",
                name, ms,
                EditCostStats.total(EditCostStats.Counter.DRAW_CALLS),
                EditCostStats.total(EditCostStats.Counter.BUFFER_UPLOADS),
                EditCostStats.total(EditCostStats.Counter.MODEL_UPLOAD_BYTES) / 1024.0,
                spans);
        System.out.println("[edit-bench] " + line);
        report.append(line).append('\n');
    }

    // =========================================================================
    // The bench
    // =========================================================================

    @Test
    void highPolyEditBench() throws Exception {
        double loadMs = load();
        row("load (parts+textures+bones)", loadMs);
        report.append(String.format(Locale.ROOT, "  model: %d triangles, %d corners, %d vertices, %d faces%n",
                model.getTriangleCount(), model.getTotalVertexCount(), model.getUniqueVertexCount(),
                model.getFaceCount()));
        drawFrame();
        endFrame();

        // Idle frame (nothing selected, nothing moving)
        frames("idle frame", f -> { });

        // Hover with the mouse still (vertex + face renderers + the edge controller's detector)
        EditModeManager.getInstance().setMode(EditMode.VERTEX);
        frames("hover still (vertex+edge+face)", f -> hoverAll(W / 2f, H / 2f));
        frames("hover moving", f -> hoverAll(W / 2f + (f & 1) * 3, H / 2f));
        EditModeManager.getInstance().setMode(EditMode.NONE);

        // Single-vertex drag (VertexTranslationHandler, the tool's real path)
        EditModeManager.getInstance().setMode(EditMode.VERTEX);
        VertexSelectionState vSel = new VertexSelectionState();
        int vtx = 0;
        vSel.selectVertex(vtx, model.getUniqueVertexPosition(vtx));
        vertexRenderer.updateSelectionSet(vSel.getSelectedVertexIndices());
        VertexTranslationHandler vh = new VertexTranslationHandler(vSel, vertexRenderer, edgeRenderer,
                faceRenderer, model, uiState, pipeline(), transform);
        vh.updateCamera(camera.getViewMatrix(), camera.getProjectionMatrix(), W, H);
        assertTrue(vh.handleMousePress(W / 2f, H / 2f));
        frames("vertex drag frame (1 vertex)", f -> vh.handleMouseMove(W / 2f + 5 + f, H / 2f + f * 0.5f));
        frames("vertex drag frame, mouse still", f -> vh.handleMouseMove(W / 2f + 30, H / 2f + 12));
        EditCostStats.reset();
        long t0 = System.nanoTime();
        vh.handleMouseRelease(W / 2f + 30, H / 2f + 12);
        row("vertex drag release", (System.nanoTime() - t0) / 1e6);
        EditModeManager.getInstance().setMode(EditMode.NONE);

        // Face drag of a 6x6 face block = 49 vertices (FaceTranslationHandler)
        EditModeManager.getInstance().setMode(EditMode.FACE);
        FaceSelectionState fSel = new FaceSelectionState();
        Set<Integer> faceBlock = new LinkedHashSet<>();
        for (int i = 0; i < 6; i++) {
            for (int j = 0; j < 6; j++) {
                int faceId = i * V + j;
                fSel.toggleFace(faceId, faceRenderer.getTriangleVertexPositionsForFace(faceId),
                        faceRenderer.getTriangleVertexIndicesForFace(faceId));
                faceBlock.add(faceId);
            }
        }
        faceRenderer.updateSelectionSet(fSel.getSelectedFaceIndices());
        FaceTranslationHandler fh = new FaceTranslationHandler(fSel, faceRenderer, vertexRenderer, edgeRenderer,
                model, uiState, pipeline(), transform);
        fh.updateCamera(camera.getViewMatrix(), camera.getProjectionMatrix(), W, H);
        assertTrue(fh.handleMousePress(W / 2f, H / 2f));
        frames("face drag frame (36 faces/49 verts)", f -> fh.handleMouseMove(W / 2f + 5 + f, H / 2f));
        frames("face drag frame, mouse still", f -> fh.handleMouseMove(W / 2f + 30, H / 2f));
        EditCostStats.reset();
        t0 = System.nanoTime();
        fh.handleMouseRelease(W / 2f + 30, H / 2f);
        row("face drag release", (System.nanoTime() - t0) / 1e6);
        faceRenderer.clearSelection();
        EditModeManager.getInstance().setMode(EditMode.NONE);

        // Part transform: one part, then a group move of all parts (one call per part, as the gizmo does)
        ModelPartManager pm = model.getPartManager();
        frames("part transform (1 part)", f -> pm.setPartTransform("part1",
                PartTransform.identity().withTranslation(new Vector3f(0, 0.01f * (f + 2), 0))));
        frames("group move (4 parts, per part)", f -> {
            for (int p = 0; p < PARTS; p++) {
                pm.setPartTransform("part" + p,
                        PartTransform.identity().withTranslation(new Vector3f(0.01f * (f + 2), 0, 0)));
            }
        });

        // Select-all overlay frames (each mode), selection change cost first
        for (EditMode mode : new EditMode[] {EditMode.VERTEX, EditMode.EDGE, EditMode.FACE}) {
            EditModeManager.getInstance().setMode(mode);
            EditCostStats.reset();
            t0 = System.nanoTime();
            selectAll(mode);
            drawFrame();
            endFrame();
            row("select-all " + mode + " (change+frame)", (System.nanoTime() - t0) / 1e6);
            frames("select-all " + mode + " frame", f -> { });
            vertexRenderer.clearSelection();
            edgeRenderer.clearSelection();
            faceRenderer.clearSelection();
        }
        EditModeManager.getInstance().setMode(EditMode.NONE);

        String label = System.getProperty("openmason.bench.label", "run");
        Path out = Path.of("target", "edit-bench-" + label + ".txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString());
        System.out.println("[edit-bench] written " + out.toAbsolutePath());
    }

    private ViewportRenderPipeline pipeline() {
        return new ViewportRenderPipeline(shaders, model, null, uiState, null, transform);
    }

    private void selectAll(EditMode mode) {
        switch (mode) {
            case VERTEX -> {
                Set<Integer> all = new LinkedHashSet<>();
                for (int i = 0; i < vertexRenderer.getVertexCount(); i++) all.add(i);
                vertexRenderer.updateSelectionSet(all);
            }
            case EDGE -> {
                Set<Integer> all = new LinkedHashSet<>();
                for (int i = 0; i < edgeRenderer.getEdgeCount(); i++) all.add(i);
                edgeRenderer.updateSelectionSet(all);
            }
            case FACE -> {
                Set<Integer> all = new HashSet<>();
                for (int i = 0; i < faceRenderer.getFaceCount(); i++) all.add(i);
                faceRenderer.updateSelectionSet(new LinkedHashSet<>(all));
            }
            default -> { }
        }
    }

    /** The per-frame hover work of the three controllers. */
    private void hoverAll(float mx, float my) {
        Matrix4f m = transform.getTransformMatrix();
        vertexRenderer.handleMouseMove(mx, my, camera.getViewMatrix(), camera.getProjectionMatrix(), m, W, H);
        HoverBench.edge(edgeRenderer, mx, my, camera.getViewMatrix(), camera.getProjectionMatrix(), m, W, H);
        faceRenderer.handleMouseMove(mx, my, camera.getViewMatrix(), camera.getProjectionMatrix(), m, W, H);
    }

    /** The edge controller's hover query (EdgeInputController.updateEdgeHover). */
    static final class HoverBench {
        static void edge(EdgeRenderer edges, float mx, float my, Matrix4f view, Matrix4f proj, Matrix4f model,
                         int w, int h) {
            long t = EditCostStats.begin();
            int hit = EdgeHoverDetector.detectHoveredEdge(mx, my, w, h, view, proj, model,
                    edges.getEdgePositions(), edges.getEdgeCount(), edges.getLineWidth());
            edges.setHoveredEdgeIndex(hit);
            EditCostStats.end(Span.HOVER, t);
        }
    }

    /** The load sequence the editor runs after deserializing an OMO (kept in step with ModelOperationService). */
    static final class LoadSequence {
        static void run(EditableModel model, BoneStore bones, List<ModelPartDescriptor> parts,
                        Map<String, PartMeshRebuilder.PartGeometry> geometry,
                        java.util.function.Consumer<FaceTextureManager> faceTextures) {
            model.getPartManager().replaceAllParts(parts, geometry);
            faceTextures.accept(model.getFaceTextureManager());
            model.adoptAuthoredUVs();
            model.markDrawBatchesDirty();
            model.refreshUVs();
            bones.load(List.of());
        }
    }
}
