package com.openmason.main.systems.rendering.model.editing.picking;

import com.openmason.engine.modeling.EditableModel;
import com.openmason.engine.modeling.parts.ModelPartDescriptor;
import com.openmason.engine.modeling.parts.PartMeshRebuilder;
import com.openmason.engine.modeling.parts.PartTransform;
import com.openmason.engine.rendering.model.draw.ModelLighting;
import com.openmason.engine.rendering.model.draw.ModelRenderer;
import com.openmason.main.systems.rendering.model.editing.subrenders.edge.EdgeHoverDetector;
import com.openmason.main.systems.rendering.model.editing.subrenders.face.FaceHoverDetector;
import com.openmason.main.systems.rendering.model.editing.subrenders.vertex.VertexHoverDetector;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;

/**
 * #175 F4 bench: hover / brush picking on the ~55k-triangle edit-bench model (four tori, 6912 quads each), CPU
 * detectors (before) against the ID-buffer pass (after): cost per pick, per frame with the mouse moving and still,
 * the pass itself (CPU issue + GPU time from a timer query) and a click's synchronous resolve. Prints a table and
 * writes {@code target/pick-bench.txt}. Opt in with {@code -Dopenmason.bench=true}.
 */
class PickingBenchGlTest {

    private static final int PARTS = 4;
    private static final int U = 96;
    private static final int V = 72;
    private static final int W = 1280;
    private static final int H = 720;
    private static final float POINT = 7f;
    private static final float LINE = 1.5f;

    private long window;
    private int fbo, colorTex, depthRb;
    private ModelRenderer renderer;
    private EditableModel model;
    private final Matrix4f view = new Matrix4f().lookAt(0, 4, 9, 0, 0, 0, 0, 1, 0);
    private final Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(60), (float) W / H, 0.1f,
            100f);
    private final Matrix4f modelMatrix = new Matrix4f();
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
        window = glfwCreateWindow(16, 16, "Pick bench", 0, 0);
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
        renderer = new ModelRenderer();
        renderer.initialize();
        model = new EditableModel();
        model.initialize();
    }

    @AfterEach
    void destroyContext() {
        if (window == 0) return;
        ModelPicking.close();
        if (model != null) model.cleanup();
        if (renderer != null) renderer.cleanup();
        glDeleteFramebuffers(fbo);
        glDeleteRenderbuffers(depthRb);
        glDeleteTextures(colorTex);
        GL.setCapabilities(null);
        glfwDestroyWindow(window);
        glfwTerminate();
    }

    private static PartMeshRebuilder.PartGeometry torus(float cx) {
        float big = 1.2f, small = 0.45f;
        float[] v = new float[U * V * 4 * 3];
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
                System.arraycopy(new int[]{base, base + 1, base + 2, base, base + 2, base + 3}, 0, idx, q * 6, 6);
                faces[q * 2] = q;
                faces[q * 2 + 1] = q;
                q++;
            }
        }
        return PartMeshRebuilder.PartGeometry.of(v, new float[U * V * 8], idx, faces);
    }

    private void load() {
        List<ModelPartDescriptor> descriptors = new ArrayList<>();
        Map<String, PartMeshRebuilder.PartGeometry> geometry = new LinkedHashMap<>();
        for (int p = 0; p < PARTS; p++) {
            descriptors.add(new ModelPartDescriptor("part" + p, "Part " + p, PartTransform.identity(), null, true,
                    false, null));
            geometry.put("part" + p, torus(-4.5f + 3f * p));
        }
        model.getPartManager().replaceAllParts(descriptors, geometry);
    }

    private static ModelLighting lighting() {
        return new ModelLighting(0.3f, new Vector3f(0.6f, 0.3f, 0.75f).normalize(), 1f, 0f, 0f,
                new Vector3f(), new Vector3f(), new Vector3f(1));
    }

    private boolean gpu;

    private void frame() {
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glViewport(0, 0, W, H);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glEnable(GL_DEPTH_TEST);
        model.render(renderer, modelMatrix, view, projection, lighting());
        if (gpu) ModelPicking.afterModelDraw(renderer, model, modelMatrix, true);
        glFinish();
    }

    // Overlay inputs (what the renderers hold)
    private float[] uniq, edges, tris;

    private void overlays() {
        uniq = model.getAllUniqueVertexPositions();
        edges = model.extractEdgePositions();
        int[] idx = model.getTriangleIndices();
        float[] corners = model.getAllMeshVertexPositions();
        tris = new float[idx.length * 3];
        for (int i = 0; i < idx.length; i++) System.arraycopy(corners, idx[i] * 3, tris, i * 3, 3);
    }

    /** One hover frame's picks through the public detectors (GPU first when a pass runs) + the brush point. */
    private int hover(float mx, float my) {
        int v = VertexHoverDetector.detectHoveredVertex(mx, my, W, H, view, projection, modelMatrix, uniq,
                uniq.length / 3, POINT);
        int e = EdgeHoverDetector.detectHoveredEdge(mx, my, W, H, view, projection, modelMatrix, edges,
                edges.length / 6, LINE);
        int t = FaceHoverDetector.detectHoveredTriangle(mx, my, W, H, view, projection, modelMatrix, tris,
                tris.length / 9);
        return v + e + t;
    }

    private void row(String name, double value, String unit) {
        String line = String.format(Locale.ROOT, "%-52s %10.4f %s", name, value, unit);
        System.out.println("[pick-bench] " + line);
        report.append(line).append('\n');
    }

    @Test
    void pickBench() throws Exception {
        load();
        overlays();
        report.append(String.format(Locale.ROOT, "model: %d triangles, %d corners, %d vertices, %d edge records%n",
                model.getTriangleCount(), model.getTotalVertexCount(), uniq.length / 3, edges.length / 6));
        float mx = W / 2f + 0.5f, my = H / 2f + 0.5f;
        int reps = 200;

        // ---- BEFORE: the CPU detectors ----
        gpu = false;
        frame();
        for (int i = 0; i < 20; i++) hover(mx, my); // warm-up
        long t0 = System.nanoTime();
        for (int i = 0; i < reps; i++) {
            VertexHoverDetector.detectHoveredVertexCpu(mx + (i & 3), my, W, H, view, projection, modelMatrix, uniq,
                    uniq.length / 3, POINT);
        }
        row("CPU vertex pick", (System.nanoTime() - t0) / 1e6 / reps, "ms/pick");
        t0 = System.nanoTime();
        for (int i = 0; i < reps; i++) {
            EdgeHoverDetector.detectHoveredEdgeCpu(mx + (i & 3), my, W, H, view, projection, modelMatrix, edges,
                    edges.length / 6, LINE);
        }
        row("CPU edge pick", (System.nanoTime() - t0) / 1e6 / reps, "ms/pick");
        t0 = System.nanoTime();
        for (int i = 0; i < reps; i++) {
            FaceHoverDetector.detectHoveredTriangleCpu(mx + (i & 3), my, W, H, view, projection, modelMatrix, tris,
                    tris.length / 9);
        }
        row("CPU face (= brush ray) pick", (System.nanoTime() - t0) / 1e6 / reps, "ms/pick");
        int frames = 60;
        t0 = System.nanoTime();
        for (int f = 0; f < frames; f++) {
            hover(mx + (f % 20) * 3, my);
            frame();
        }
        double cpuMoving = (System.nanoTime() - t0) / 1e6 / frames;
        row("CPU hover frame, mouse moving (draw + 3 picks)", cpuMoving, "ms/frame");
        t0 = System.nanoTime();
        for (int f = 0; f < frames; f++) frame();
        double drawOnly = (System.nanoTime() - t0) / 1e6 / frames;
        row("draw only (reference)", drawOnly, "ms/frame");

        // ---- AFTER: the ID-buffer pass ----
        gpu = true;
        frame();
        frame();
        hover(mx, my);
        frame();
        frame();
        ModelPickPass pass = ModelPicking.pass();
        assertTrue(pass != null && pass.isAvailable());
        assertTrue(pass.queryTriangle(mx, my, W, H, view, projection, modelMatrix, tris.length / 9)
                != ModelPickPass.PENDING, "answered");
        // Answered queries (cursor inside the last window, nothing changed), one hover per frame as the overlays ask
        long answeredNanos = 0, brushNanos = 0;
        Vector3f out = new Vector3f();
        for (int i = 0; i < reps; i++) {
            long s0 = System.nanoTime();
            hover(mx + (i & 3), my);
            long s1 = System.nanoTime();
            ModelPicking.surface(mx + (i & 3), my, W, H, view, projection, modelMatrix, out);
            brushNanos += System.nanoTime() - s1;
            answeredNanos += s1 - s0;
            frame();
        }
        row("GPU answered picks (vertex/edge/face, window hit)", answeredNanos / 1e6 / reps / 3, "ms/pick");
        row("GPU brush surface point (answered)", brushNanos / 1e6 / reps, "ms/pick");

        long passes0 = pass.passCount(), nanos0 = pass.passNanos();
        t0 = System.nanoTime();
        for (int f = 0; f < frames; f++) {
            hover(mx + (f % 20) * 3, my);
            frame();
        }
        double gpuMoving = (System.nanoTime() - t0) / 1e6 / frames;
        long movingPasses = pass.passCount() - passes0;
        row("GPU hover frame, mouse moving (draw + picks + passes)", gpuMoving, "ms/frame");
        row("  passes rendered while moving 3 px/frame", movingPasses, "of " + frames + " frames");
        row("  CPU time issuing a pass (render + readback calls)",
                (pass.passNanos() - nanos0) / 1e6 / Math.max(1, movingPasses), "ms/pass");

        // Mouse still: no passes, gates idle (the detectors are not even called by the overlays then)
        long stillPasses = pass.passCount();
        t0 = System.nanoTime();
        for (int f = 0; f < frames; f++) {
            hover(mx + 30, my);
            frame();
        }
        row("GPU hover frame, mouse still (detectors forced each frame)", (System.nanoTime() - t0) / 1e6 / frames,
                "ms/frame");
        row("  passes while still (incl. settling on the new spot)", pass.passCount() - stillPasses, "of " + frames);

        // GPU time of one pass: a timer query around a forced window (fresh mouse = pending, rendered by the hook)
        int query = glGenQueries();
        double gpuMs = 0;
        int samples = 20;
        for (int i = 0; i < samples; i++) {
            hover(100 + i * 40, 100 + (i % 5) * 80); // far jump: pending
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            model.render(renderer, modelMatrix, view, projection, lighting());
            glBeginQuery(GL_TIME_ELAPSED, query);
            ModelPicking.afterModelDraw(renderer, model, modelMatrix, true);
            glEndQuery(GL_TIME_ELAPSED);
            gpuMs += glGetQueryObjecti64(query, GL_QUERY_RESULT) / 1e6;
            glFinish();
        }
        glDeleteQueries(query);
        row("GPU time of one pass (faces+edges+points, 64x64)", gpuMs / samples, "ms/pass");

        // Click on a stale window: synchronous render + read
        double syncMs = 0;
        for (int i = 0; i < samples; i++) {
            hover(200 + i * 30, 500 - (i % 4) * 60);
            long s = System.nanoTime();
            assertTrue(ModelPicking.resolveForClick());
            syncMs += (System.nanoTime() - s) / 1e6;
            frame();
        }
        row("click on a stale window (sync pass + readback)", syncMs / samples, "ms/click");

        row("corner table build, first (cold JIT, " + pass.tableBuildCount() + " builds)",
                pass.tableBuildNanos() / 1e6 / Math.max(1, pass.tableBuildCount()), "ms/build");

        for (int i = 0; i < 5; i++) PickCornerTable.build(model); // warm
        long tb = System.nanoTime();
        for (int i = 0; i < 10; i++) PickCornerTable.build(model);
        row("corner table build, warm", (System.nanoTime() - tb) / 1e6 / 10, "ms/build");

        Path file = Path.of("target", "pick-bench.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, report.toString());
        System.out.println("[pick-bench] written " + file.toAbsolutePath());
    }
}
