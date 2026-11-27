package com.openmason.main.systems.rendering.model.editing.picking;

import com.openmason.engine.modeling.EditableModel;
import com.openmason.engine.modeling.parts.ModelPartManager;
import com.openmason.engine.modeling.parts.PartMeshRebuilder;
import com.openmason.engine.modeling.parts.PartTransform;
import com.openmason.engine.modeling.uv.MaterialDefinition;
import com.openmason.engine.rendering.model.IndexedDrawBatch;
import com.openmason.engine.rendering.model.draw.MaterialTextures;
import com.openmason.engine.rendering.model.draw.ModelDrawPlan;
import com.openmason.engine.rendering.model.draw.ModelLighting;
import com.openmason.engine.rendering.model.draw.ModelRenderer;
import com.openmason.engine.rendering.viewer.math.CoordinateSystem;
import com.openmason.main.systems.rendering.model.editing.subrenders.edge.EdgeHoverDetector;
import com.openmason.main.systems.rendering.model.editing.subrenders.face.FaceHoverDetector;
import com.openmason.main.systems.rendering.model.editing.subrenders.vertex.VertexHoverDetector;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * OpenMason #175 F4 on real GL: the ID-buffer pick pass against the CPU detectors. Unoccluded picks equal the CPU
 * answer (vertices, edges, faces, the weight-paint surface point); occluded elements are not picked; the
 * material-sorted and a permuted index buffer, a draw plan cut down to visible ranges and a palette-deformed (dragged)
 * part all map to the right editor ids; the snap radii match the overlays; the readback is a frame late with no
 * stall, answers moves inside its window at once, re-renders nothing while nothing changes, and a click resolves a
 * stale window synchronously. Needs a display: opt in with {@code -Dopenmason.model.gl=true}.
 */
class ModelPickPassGlTest {

    private static final int W = 320;
    private static final int H = 240;
    private static final float POINT = 7f;
    private static final float LINE = 1.5f;

    private long window;
    private int fbo, colorTex, depthRb;
    private final List<Integer> textures = new ArrayList<>();
    private ModelRenderer renderer;
    private EditableModel model;
    private final Matrix4f view = new Matrix4f().lookAt(0, 0, 5, 0, 0, 0, 0, 1, 0);
    private final Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(60), (float) W / H, 0.1f,
            100f);
    private final Matrix4f modelMatrix = new Matrix4f();

    @BeforeEach
    void createContext() {
        assumeTrue(Boolean.getBoolean("openmason.model.gl"), "opt in with -Dopenmason.model.gl=true");
        assertTrue(glfwInit(), "GLFW display unavailable");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        window = glfwCreateWindow(16, 16, "Model pick pass test", 0, 0);
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
        for (int t : textures) glDeleteTextures(t);
        glDeleteFramebuffers(fbo);
        glDeleteRenderbuffers(depthRb);
        glDeleteTextures(colorTex);
        int error = glGetError();
        GL.setCapabilities(null);
        glfwDestroyWindow(window);
        glfwTerminate();
        assertEquals(GL_NO_ERROR, error, "GL error during the test");
    }

    // =========================================================================
    // Geometry
    // =========================================================================

    /** An n x n grid of quads in the XY plane at {@code z}, {@code size} wide, centred on (cx, cy); 4 own corners each. */
    private static PartMeshRebuilder.PartGeometry grid(int n, float size, float cx, float cy, float z) {
        float[] v = new float[n * n * 12];
        int[] idx = new int[n * n * 6];
        int[] faces = new int[n * n * 2];
        float step = size / n;
        int q = 0;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                float x0 = cx - size / 2 + i * step, y0 = cy - size / 2 + j * step;
                float[] c = {x0, y0, z, x0 + step, y0, z, x0 + step, y0 + step, z, x0, y0 + step, z};
                System.arraycopy(c, 0, v, q * 12, 12);
                int b = q * 4;
                System.arraycopy(new int[]{b, b + 1, b + 2, b, b + 2, b + 3}, 0, idx, q * 6, 6);
                faces[q * 2] = q;
                faces[q * 2 + 1] = q;
                q++;
            }
        }
        return PartMeshRebuilder.PartGeometry.of(v, new float[n * n * 8], idx, faces);
    }

    private static PartMeshRebuilder.PartGeometry cube(float cx, float cy, float cz, float h) {
        float[] v = {
            -h, -h,  h,   h, -h,  h,   h,  h,  h,  -h,  h,  h,
             h, -h, -h,  -h, -h, -h,  -h,  h, -h,   h,  h, -h,
            -h, -h, -h,  -h, -h,  h,  -h,  h,  h,  -h,  h, -h,
             h, -h,  h,   h, -h, -h,   h,  h, -h,   h,  h,  h,
            -h,  h,  h,   h,  h,  h,   h,  h, -h,  -h,  h, -h,
            -h, -h, -h,   h, -h, -h,   h, -h,  h,  -h, -h,  h,
        };
        for (int i = 0; i < v.length; i += 3) {
            v[i] += cx;
            v[i + 1] += cy;
            v[i + 2] += cz;
        }
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

    private String addPart(String name, PartMeshRebuilder.PartGeometry geometry) {
        return model.getPartManager().addPartFromGeometry(name, geometry, new Vector3f()).id();
    }

    // =========================================================================
    // Frame + queries
    // =========================================================================

    private static ModelLighting lighting() {
        return new ModelLighting(0.3f, new Vector3f(0.6f, 0.3f, 0.75f).normalize(), 1f, 0f, 0f,
                new Vector3f(), new Vector3f(), new Vector3f(1));
    }

    /** One viewport frame: the lit draw, then the pipeline's pick hook; finished so readbacks land. */
    private void frame() {
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glViewport(0, 0, W, H);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        model.render(renderer, modelMatrix, view, projection, lighting());
        hook();
    }

    /** The first frames: the hook asks the renderer to capture the model's draw, the next draw is captured. */
    private void start() {
        frame();
        frame();
    }

    private void hook() {
        ModelPicking.afterModelDraw(renderer, model, modelMatrix, true);
        glFinish();
    }

    private ModelPickPass pass() {
        ModelPickPass p = ModelPicking.pass();
        assertTrue(p != null && p.isAvailable(), "the pick pass is running");
        return p;
    }

    private interface Query {
        int run();
    }

    /** Asks until the GPU answers (one frame per pending answer). */
    private int answer(Query q) {
        for (int i = 0; i < 4; i++) {
            int r = q.run();
            assertNotEquals(ModelPickPass.UNAVAILABLE, r, "GPU picking available");
            if (r != ModelPickPass.PENDING) return r;
            frame();
        }
        throw new AssertionError("the GPU never answered");
    }

    private int gpuVertex(float mx, float my) {
        int count = model.getAllUniqueVertexPositions().length / 3;
        return answer(() -> pass().queryVertex(mx, my, W, H, view, projection, modelMatrix, count, POINT));
    }

    private int gpuEdge(float mx, float my) {
        int count = model.extractEdgePositions().length / 6;
        return answer(() -> pass().queryEdge(mx, my, W, H, view, projection, modelMatrix, count, LINE * 2));
    }

    /** The GPU face under the cursor (its first triangle mapped to the face), -1 for none. */
    private int gpuFace(float mx, float my) {
        int t = answer(() -> pass().queryTriangle(mx, my, W, H, view, projection, modelMatrix,
                model.getTriangleCount()));
        return t < 0 ? -1 : model.getOriginalFaceIdForTriangle(t);
    }

    private int cpuVertex(float mx, float my) {
        float[] p = model.getAllUniqueVertexPositions();
        return VertexHoverDetector.detectHoveredVertexCpu(mx, my, W, H, view, projection, modelMatrix, p,
                p.length / 3, POINT);
    }

    private int cpuEdge(float mx, float my) {
        float[] e = model.extractEdgePositions();
        return EdgeHoverDetector.detectHoveredEdgeCpu(mx, my, W, H, view, projection, modelMatrix, e, e.length / 6,
                LINE);
    }

    private int cpuFace(float mx, float my) {
        int[] idx = model.getTriangleIndices();
        float[] corners = model.getAllMeshVertexPositions();
        float[] tri = new float[idx.length * 3];
        for (int i = 0; i < idx.length; i++) System.arraycopy(corners, idx[i] * 3, tri, i * 3, 3);
        int t = FaceHoverDetector.detectHoveredTriangleCpu(mx, my, W, H, view, projection, modelMatrix, tri,
                idx.length / 3);
        return t < 0 ? -1 : model.getOriginalFaceIdForTriangle(t);
    }

    /** Screen position (viewport coords, y down) of a model-space point, snapped to its pixel's centre. */
    private float[] screen(float x, float y, float z) {
        Vector4f c = new Vector4f(x, y, z, 1f);
        new Matrix4f(projection).mul(view).mul(modelMatrix).transform(c);
        float sx = (c.x / c.w + 1f) * 0.5f * W;
        float sy = (1f - c.y / c.w) * 0.5f * H;
        float px = (float) Math.floor(sx) + 0.5f;
        float row = (float) Math.floor(H - sy);
        return new float[]{px, H - (row + 0.5f)};
    }

    // =========================================================================
    // Tests
    // =========================================================================

    @Test
    void unoccludedPicksEqualTheCpuDetectors() {
        addPart("grid", grid(6, 3f, 0f, 0f, 0f));
        start();
        float[] uniq = model.getAllUniqueVertexPositions();
        int compared = 0;
        for (int u = 0; u < uniq.length / 3; u++) {
            float[] s = screen(uniq[u * 3], uniq[u * 3 + 1], uniq[u * 3 + 2]);
            int cpu = cpuVertex(s[0], s[1]);
            assertTrue(cpu >= 0, "CPU picks the vertex under the cursor");
            assertEquals(cpu, gpuVertex(s[0], s[1]), "vertex " + u);
            compared++;
        }
        float[] edges = model.extractEdgePositions();
        for (int e = 0; e < edges.length / 6; e++) {
            float[] s = screen((edges[e * 6] + edges[e * 6 + 3]) / 2, (edges[e * 6 + 1] + edges[e * 6 + 4]) / 2,
                    (edges[e * 6 + 2] + edges[e * 6 + 5]) / 2);
            int cpu = cpuEdge(s[0], s[1]);
            assertTrue(cpu >= 0);
            assertEquals(cpu, gpuEdge(s[0], s[1]), "edge record " + e);
            compared++;
        }
        for (int f = 0; f < 36; f++) {
            int i = f % 6, j = f / 6;
            float step = 3f / 6;
            float[] s = screen(-1.5f + (i + 0.5f) * step, -1.5f + (j + 0.5f) * step, 0f);
            assertEquals(cpuFace(s[0], s[1]), gpuFace(s[0], s[1]), "face " + f);
            assertEquals(f, gpuFace(s[0], s[1]));
            compared++;
        }
        // Dense scan: every pixel of a block over the grid, faces agree (rasterization vs ray at shared edges aside)
        int agree = 0, total = 0;
        for (int y = 40; y < 200; y += 3) {
            for (int x = 80; x < 240; x += 3) {
                float mx = x + 0.5f, my = y + 0.5f;
                total++;
                if (cpuFace(mx, my) == gpuFace(mx, my)) agree++;
            }
        }
        assertTrue(agree >= total * 0.98, "faces agree on " + agree + " / " + total);
        assertTrue(compared > 100);
    }

    @Test
    void occludedElementsAreNotPicked() {
        addPart("back", grid(8, 2f, 0f, 0f, 0f));
        addPart("front", grid(1, 3f, 0f, 0f, 1.5f)); // one big quad in front of the whole back grid
        start();
        int frontFace = gpuFace(screen(0, 0, 1.5f)[0], screen(0, 0, 1.5f)[1]);
        assertTrue(frontFace >= 0);
        // A back vertex well inside the front quad: the CPU test finds it, the ID buffer does not
        float[] s = screen(0.25f, 0.25f, 0f);
        assertTrue(cpuVertex(s[0], s[1]) >= 0, "CPU picks the hidden vertex");
        assertEquals(-1, gpuVertex(s[0], s[1]), "an occluded vertex is not picked");
        float[] m = screen(0.375f, 0.25f, 0f); // middle of a hidden back edge
        assertTrue(cpuEdge(m[0], m[1]) >= 0, "CPU picks the hidden edge");
        assertEquals(-1, gpuEdge(m[0], m[1]), "an occluded edge is not picked");
        assertEquals(cpuFace(s[0], s[1]), gpuFace(s[0], s[1]), "the front face is under the cursor");
        assertEquals(frontFace, gpuFace(s[0], s[1]));

        // Hiding the front part makes the back grid pickable
        String front = model.getPartManager().getAllParts().get(1).id();
        model.getPartManager().setPartVisible(front, false);
        frame();
        assertEquals(cpuVertex(s[0], s[1]), gpuVertex(s[0], s[1]));
        assertTrue(gpuVertex(s[0], s[1]) >= 0);
    }

    @Test
    void materialSortedIndexBufferMapsToEditorFaceIds() {
        addPart("grid", grid(6, 3f, 0f, 0f, 0f));
        int[] mats = {texture(), texture(), texture()};
        var ftm = model.getFaceTextureManager();
        for (int k = 0; k < mats.length; k++) {
            ftm.registerMaterial(new MaterialDefinition(700 + k, "M" + k, mats[k],
                    MaterialDefinition.RenderLayer.OPAQUE, MaterialDefinition.MaterialProperties.NONE));
        }
        for (int f = 0; f < 36; f++) ftm.assignDefaultMapping(f, 700 + (f * 7) % 3); // interleaved materials
        model.markDrawBatchesDirty();
        model.refreshUVs();
        start();
        List<IndexedDrawBatch> batches = renderer.capturedDraw().plan().forPart(0).textured();
        assertEquals(3, batches.size(), "triangles are grouped by material: the GPU order is not the editor's");
        assertEveryFaceCentreMatches(6, 3f);
    }

    @Test
    void permutedIndexBufferMapsToEditorFaceIds() {
        addPart("grid", grid(6, 3f, 0f, 0f, 0f));
        start();
        // A mask plan reorders triangles; only gl_VertexID reaches the table, so any order picks the same ids
        int[] idx = model.getTriangleIndices();
        int tris = idx.length / 3;
        int[] permuted = new int[idx.length];
        for (int t = 0; t < tris; t++) {
            int src = (t * 7 + 3) % tris; // 7 is coprime with 72
            System.arraycopy(idx, src * 3, permuted, t * 3, 3);
        }
        model.getGpuMesh().updateIndices(permuted);
        frame();
        assertEveryFaceCentreMatches(6, 3f);
        float[] uniq = model.getAllUniqueVertexPositions();
        for (int u = 0; u < uniq.length / 3; u += 5) {
            float[] s = screen(uniq[u * 3], uniq[u * 3 + 1], uniq[u * 3 + 2]);
            assertEquals(cpuVertex(s[0], s[1]), gpuVertex(s[0], s[1]));
        }
    }

    @Test
    void theIdPassDrawsExactlyTheVisibleRangesOfThePlan() {
        String back = addPart("back", grid(4, 2f, 0f, 0f, 0f));
        addPart("front", grid(1, 3f, 0f, 0f, 1.5f));
        start();
        float[] s = screen(0.25f, 0.25f, 0f);
        int frontFace = gpuFace(s[0], s[1]);
        // Draw-range visibility (what a hidden part / vertex group becomes): the lit pass draws the plan without the
        // front quad's two triangles (the last 6 indices); the pick follows on the next frame.
        ModelDrawPlan plan = renderer.capturedDraw().plan();
        int indexCount = model.getGpuMesh().indexCount();
        ModelDrawPlan cut = plan.masked(new int[]{0, indexCount - 6});
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        renderer.drawLit(model.getGpuMesh(), cut, MaterialTextures.UNTEXTURED, model.computePalette(modelMatrix),
                view, projection, lighting(), null);
        hook();
        int behind = answer(() -> pass().queryTriangle(s[0], s[1], W, H, view, projection, modelMatrix,
                model.getTriangleCount()));
        assertTrue(behind >= 0);
        assertNotEquals(frontFace, model.getOriginalFaceIdForTriangle(behind), "the cut-out front quad is gone");
        assertEquals(cpuVertex(s[0], s[1]), answer(() -> pass().queryVertex(s[0], s[1], W, H, view, projection,
                modelMatrix, model.getAllUniqueVertexPositions().length / 3, POINT)));
        assertFalse(back.isEmpty());
    }

    @Test
    void aPaletteDeformedPartIsPickedWhereItIsDrawn() {
        addPart("a", cube(-1.2f, 0f, 0f, 0.5f));
        String b = addPart("b", cube(1.2f, 0f, 0f, 0.5f));
        start();
        ModelPartManager pm = model.getPartManager();
        PartTransform t = pm.getPartById(b).orElseThrow().transform();
        long rebuilds = pm.getRebuildCount();
        pm.beginTransformInteraction();
        pm.setPartTransform(b, new PartTransform(t.origin(), new Vector3f(0, 1.2f, 0), t.rotation(), t.scale()));
        frame();
        assertTrue(model.isDisplayDeformed(), "the part moves through the palette");
        assertEquals(rebuilds, pm.getRebuildCount(), "no re-bake during the drag");

        // Cube b's front face (face id 6, +Z) is drawn 1.2 up: picked there, not at its rest place
        float[] drawn = screen(1.2f, 1.2f, 0.5f);
        float[] rest = screen(1.2f, 0f, 0.5f);
        assertEquals(6, gpuFace(drawn[0], drawn[1]), "picks follow the displayed geometry");
        assertEquals(-1, gpuFace(rest[0], rest[1]), "nothing is drawn at the rest place any more");
        assertEquals(6, cpuFace(rest[0], rest[1]), "(the CPU test still sees the rest bake)");

        // A displayed corner of cube b picks its welded vertex
        float[] displayed = model.getDisplayedCornerPositions(modelMatrix);
        int corner = 24; // cube b's first corner (front face, -x -y)
        float[] s = screen(displayed[corner * 3], displayed[corner * 3 + 1], displayed[corner * 3 + 2]);
        assertEquals(model.getUniqueIndexForMeshVertex(corner), gpuVertex(s[0], s[1]));
        pm.endTransformInteraction();
    }

    @Test
    void facesPickTheShownPreviewWhileVerticesAndEdgesStayOnTheEditCage() {
        addPart("grid", grid(2, 2f, 0f, 0f, 0f));
        start();
        // A scene-preview shape key (default weight 1) shifts face 0 (the bottom-left quad) 3 units right on the GPU
        int[] idx = model.getTriangleIndices();
        java.util.TreeSet<Integer> face0 = new java.util.TreeSet<>();
        for (int t = 0; t < idx.length / 3; t++) {
            if (model.getOriginalFaceIdForTriangle(t) == 0) {
                for (int k = 0; k < 3; k++) face0.add(idx[t * 3 + k]);
            }
        }
        int[] rows = face0.stream().mapToInt(Integer::intValue).toArray();
        float[] deltas = new float[rows.length * 3];
        for (int r = 0; r < rows.length; r++) deltas[r * 3] = 3f;
        com.openmason.engine.rig.RigMorphs morphs = com.openmason.engine.rig.RigMorphs.create(
                model.getTotalVertexCount(), List.of("shift"), new float[]{1f, 0f, 1f}, new int[]{0, rows.length},
                rows, deltas, null);
        model.getGpuMesh().updateMorphs(morphs);
        frame();

        float[] shown = screen(2.5f, -0.5f, 0f);
        float[] cage = screen(-0.5f, -0.5f, 0f);
        assertEquals(0, gpuFace(shown[0], shown[1]), "the face is picked where the preview shows it");
        assertEquals(-1, gpuFace(cage[0], cage[1]), "nothing is shown at its cage place");
        // The bottom-left corner vertex belongs to face 0 only: picked at its CAGE position (where the overlay
        // draws it), not where the preview moved it
        float[] v = screen(-1f, -1f, 0f);
        float[] moved = screen(2f, -1f, 0f);
        int cpu = cpuVertex(v[0], v[1]);
        assertTrue(cpu >= 0);
        assertEquals(cpu, gpuVertex(v[0], v[1]));
        assertEquals(-1, gpuVertex(moved[0], moved[1]));
        // Same for an edge of face 0 (its bottom side)
        float[] e = screen(-0.5f, -1f, 0f);
        assertEquals(cpuEdge(e[0], e[1]), gpuEdge(e[0], e[1]));
        assertTrue(gpuEdge(e[0], e[1]) >= 0);
    }

    @Test
    void vertexAndEdgeSnapRadiiMatchTheOverlays() {
        addPart("grid", grid(2, 3f, 0f, 0f, 0f));
        start();
        // The centre vertex (0,0,0): inside the point radius (3.5 px) at 2 px, outside at 6 px
        float[] c = screen(0f, 0f, 0f);
        int centre = gpuVertex(c[0], c[1]);
        assertTrue(centre >= 0);
        assertEquals(centre, gpuVertex(c[0] + 2, c[1]));
        assertEquals(centre, gpuVertex(c[0], c[1] - 2));
        assertEquals(cpuVertex(c[0] + 2, c[1]), gpuVertex(c[0] + 2, c[1]));
        assertEquals(-1, gpuVertex(c[0] + 6, c[1]));
        assertEquals(-1, cpuVertex(c[0] + 6, c[1]));
        // An edge (the vertical one through x=0 above the centre): 2 px off picks it (threshold 3 px), 6 px does not
        float[] e = screen(0f, 0.75f, 0f);
        int edge = gpuEdge(e[0], e[1]);
        assertTrue(edge >= 0);
        assertEquals(edge, gpuEdge(e[0] + 2, e[1]));
        assertEquals(cpuEdge(e[0] + 2, e[1]), gpuEdge(e[0] + 2, e[1]));
        assertEquals(-1, gpuEdge(e[0] + 6, e[1]));
        assertEquals(-1, cpuEdge(e[0] + 6, e[1]));
    }

    @Test
    void theReadbackIsOneFrameLateAndNeverRendersWhileNothingChanges() {
        addPart("grid", grid(6, 3f, 0f, 0f, 0f));
        start();
        ModelPickPass p = pass();
        int count = model.getAllUniqueVertexPositions().length / 3;
        float[] s = screen(0f, 0f, 0f);
        assertEquals(ModelPickPass.PENDING, p.queryVertex(s[0], s[1], W, H, view, projection, modelMatrix, count,
                POINT), "nothing read back yet");
        frame(); // renders the window, readback in flight; finished by glFinish
        int id = p.queryVertex(s[0], s[1], W, H, view, projection, modelMatrix, count, POINT);
        assertTrue(id >= 0, "answered a frame later");
        assertEquals(0, p.syncPassCount(), "no stall");
        assertEquals(1, p.landedCount());

        // A small move stays inside the window: answered at once, no new pass
        long passes = p.passCount();
        assertEquals(-1, p.queryVertex(s[0] + 8, s[1], W, H, view, projection, modelMatrix, count, POINT));
        assertEquals(passes, p.passCount());

        // Mouse still, model still: frames render nothing
        for (int i = 0; i < 5; i++) {
            p.queryVertex(s[0] + 8, s[1], W, H, view, projection, modelMatrix, count, POINT);
            frame();
        }
        assertEquals(passes, p.passCount(), "a still mouse costs no pass");
        long epoch = p.epoch();
        for (int i = 0; i < 3; i++) frame();
        assertEquals(epoch, p.epoch(), "nothing invalidates the gates while nothing changes");

        // A far jump is pending; a click resolves it synchronously
        float[] far = screen(1.0f, -1.0f, 0f);
        assertEquals(ModelPickPass.PENDING, p.queryVertex(far[0], far[1], W, H, view, projection, modelMatrix, count,
                POINT));
        assertTrue(ModelPicking.resolveForClick());
        assertEquals(1, p.syncPassCount());
        assertEquals(cpuVertex(far[0], far[1]), p.queryVertex(far[0], far[1], W, H, view, projection, modelMatrix,
                count, POINT));
        assertFalse(ModelPicking.resolveForClick(), "nothing stale: no second stall");

        // A moved vertex invalidates the answer until the next frame's window
        model.updateVertexPosition(0, new Vector3f(-1.5f, -1.5f, 0.4f));
        assertEquals(ModelPickPass.PENDING, p.queryVertex(far[0], far[1], W, H, view, projection, modelMatrix, count,
                POINT));
        // Another model in the viewport: queries fall back to the CPU
        ModelPicking.afterModelDraw(renderer, model, modelMatrix, false);
        assertEquals(ModelPickPass.UNAVAILABLE, p.queryVertex(far[0], far[1], W, H, view, projection, modelMatrix,
                count, POINT));
    }

    @Test
    void theBrushSurfacePointIsTheVisibleSurface() {
        addPart("back", grid(4, 2f, 0f, 0f, 0f));
        addPart("front", grid(1, 1f, 0.5f, 0.5f, 0.75f));
        start();
        for (float[] at : new float[][]{{-0.6f, -0.4f, 0f}, {0.5f, 0.5f, 0.75f}, {0.3f, -0.7f, 0f}}) {
            float[] s = screen(at[0], at[1], at[2]);
            Vector3f out = new Vector3f();
            int r = answer(() -> pass().querySurface(s[0], s[1], W, H, view, projection, modelMatrix, out));
            assertEquals(1, r);
            // The CPU ray through the same mouse position meets the visible plane at z = at[2]
            CoordinateSystem.Ray ray = CoordinateSystem.createWorldRayFromScreen(s[0], s[1], W, H, view,
                    projection);
            float tz = (at[2] - ray.origin().z) / ray.direction().z;
            Vector3f expect = new Vector3f(ray.direction()).mul(tz).add(ray.origin());
            assertTrue(out.distance(expect) < 2e-3f, out + " vs " + expect);
        }
        float[] off = screen(2.5f, 0f, 0f);
        Vector3f out = new Vector3f();
        assertEquals(-1, answer(() -> pass().querySurface(off[0], off[1], W, H, view, projection, modelMatrix, out)));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void assertEveryFaceCentreMatches(int n, float size) {
        float step = size / n;
        for (int f = 0; f < n * n; f++) {
            int i = f % n, j = f / n;
            float[] s = screen(-size / 2 + (i + 0.5f) * step, -size / 2 + (j + 0.5f) * step, 0f);
            assertEquals(f, cpuFace(s[0], s[1]));
            assertEquals(f, gpuFace(s[0], s[1]), "face " + f);
        }
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
}
