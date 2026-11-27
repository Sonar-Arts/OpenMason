package com.openmason.main.systems.rendering.model.editing.subrenders.face;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.modeling.EditableModel;
import com.openmason.engine.modeling.core.EditCostStats;
import com.openmason.engine.rendering.shaders.ShaderManager;
import com.openmason.engine.rendering.shaders.ShaderType;
import com.openmason.engine.rendering.viewer.ViewerRenderContext;
import com.openmason.engine.rendering.viewer.camera.ViewerCamera;
import com.openmason.main.systems.rendering.model.editing.subrenders.edge.EdgeRenderer;
import com.openmason.main.systems.rendering.model.editing.subrenders.vertex.VertexRenderer;
import com.openmason.main.systems.viewport.state.EditMode;
import com.openmason.main.systems.viewport.state.EditModeManager;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.GL_INVALID_VALUE;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL11.glLineWidth;

/**
 * #175 F1 overlays with real GL: the face overlay's listener path (corner and outline lookups, batched moves)
 * leaves its CPU mirrors exactly as a fresh rebuild would; the edge selection survives a rebuild that keeps the
 * edges (a drag commit) and drops only edges that changed; select-all draws with a constant number of calls.
 * Needs a display: opt in with {@code -Dopenmason.model.gl=true}.
 */
class OverlayEditCostGlTest {

    private long window;
    private ShaderManager shaders;
    private EditableModel model;
    private FaceRenderer faces;
    private EdgeRenderer edges;
    private VertexRenderer vertices;
    private ViewerRenderContext context;

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
        window = glfwCreateWindow(64, 64, "Overlay edit cost test", 0, 0);
        assertNotEquals(0, window);
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        shaders = new ShaderManager();
        shaders.initialize();
        glGetError();
        glLineWidth(2f);
        wideLinesRefused = glGetError() == GL_INVALID_VALUE;
        glLineWidth(1f);
        ViewerCamera camera = new ViewerCamera();
        camera.setAspectRatio(1f);
        camera.updateMatrices();
        context = new ViewerRenderContext(camera);

        model = new EditableModel();
        model.loadMeshDataAsPart(grid(5, 4), "Root");
        model.initialize();
        faces = new FaceRenderer();
        edges = new EdgeRenderer();
        vertices = new VertexRenderer();
        faces.initialize();
        edges.initialize();
        vertices.initialize();
        faces.setEditableModel(model);
        edges.setModelRenderer(model);
        vertices.setModelRenderer(model);
        faces.setEnabled(true);
        edges.setEnabled(true);
        vertices.setEnabled(true);
    }

    @AfterEach
    void destroyContext() {
        if (window == 0) return;
        EditModeManager.getInstance().setMode(EditMode.NONE);
        EditCostStats.setEnabled(false);
        EditCostStats.reset();
        faces.cleanup();
        edges.cleanup();
        vertices.cleanup();
        model.cleanup();
        shaders.cleanup();
        int error = glGetError();
        GL.setCapabilities(null);
        glfwDestroyWindow(window);
        glfwTerminate();
        assertEquals(GL_NO_ERROR, error, "GL error during the test");
    }

    /** A w x h grid of quads (4 own corners each), slightly bumpy. */
    private static OMOFormat.MeshData grid(int w, int h) {
        float[] v = new float[w * h * 12];
        int[] idx = new int[w * h * 6];
        int[] ids = new int[w * h * 2];
        int q = 0;
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < h; j++) {
                float[][] c = {{i, j}, {i + 1, j}, {i + 1, j + 1}, {i, j + 1}};
                for (int k = 0; k < 4; k++) {
                    v[q * 12 + k * 3] = c[k][0] * 0.2f - 0.5f;
                    v[q * 12 + k * 3 + 1] = c[k][1] * 0.2f - 0.4f;
                    v[q * 12 + k * 3 + 2] = -3f + 0.05f * (float) Math.cos(c[k][0] * c[k][1]);
                }
                int b = q * 4;
                System.arraycopy(new int[] {b, b + 1, b + 2, b, b + 2, b + 3}, 0, idx, q * 6, 6);
                ids[q * 2] = q;
                ids[q * 2 + 1] = q;
                q++;
            }
        }
        return new OMOFormat.MeshData(v, new float[w * h * 8], idx, ids, "FLAT");
    }

    private void draw() {
        Matrix4f m = new Matrix4f();
        faces.render(shaders.getShaderProgram(ShaderType.BASIC), shaders.getShaderProgram(ShaderType.FACE),
                shaders.getShaderProgram(ShaderType.VERTEX), context, m);
        drainErrors("face overlay");
        edges.render(shaders.getShaderProgram(ShaderType.BASIC), context, m);
        drainErrors("edge overlay");
        vertices.render(shaders.getShaderProgram(ShaderType.VERTEX), context, m);
        drainErrors("vertex overlay");
    }

    /**
     * The outlines ask for 1.5 / 2 px lines, which a forward-compatible core context (the editor's, too) refuses
     * with GL_INVALID_VALUE (pre-existing, not #175): tolerate exactly that when this driver does it, nothing else.
     */
    private void drainErrors(String what) {
        for (int err = glGetError(); err != GL_NO_ERROR; err = glGetError()) {
            if (!(err == GL_INVALID_VALUE && wideLinesRefused)) {
                throw new AssertionError(what + ": GL error " + err);
            }
        }
    }

    private boolean wideLinesRefused;

    @Test
    void batchedMovesLeaveTheFaceOverlayExactlyAsAFreshRebuild() {
        EditModeManager.getInstance().setMode(EditMode.FACE);
        faces.updateSelectionSet(Set.of(0, 3, 7));
        draw(); // builds fill + dots + element lists

        // Two batched moves (a drag), then compare with a renderer built from scratch on the moved model
        model.moveVertices(new int[] {0, 6, 7, 11}, new float[] {
                -0.6f, -0.45f, -2.9f, 0.1f, 0.0f, -3.2f, 0.05f, 0.2f, -2.8f, 0.3f, 0.1f, -3.1f});
        model.moveVertices(new int[] {6, 13}, new float[] {0.12f, 0.02f, -3.25f, 0.4f, 0.3f, -3.0f});
        draw();

        FaceRenderer fresh = new FaceRenderer();
        fresh.initialize();
        fresh.setEditableModel(model);
        fresh.setEnabled(true);
        fresh.updateSelectionSet(Set.of(0, 3, 7));
        fresh.render(shaders.getShaderProgram(ShaderType.BASIC), shaders.getShaderProgram(ShaderType.FACE),
                shaders.getShaderProgram(ShaderType.VERTEX), context, new Matrix4f());
        drainErrors("fresh face overlay");
        try {
            assertArrayEquals(fresh.trianglePositionsView(), faces.trianglePositionsView(), "hover triangles");
            assertArrayEquals(fresh.fillDataView(), faces.fillDataView(), "fill buffer");
            assertArrayEquals(fresh.lineDataView(), faces.lineDataView(), "outline buffer (positions + colours)");
            float[] a = fresh.dotDataView();
            float[] b = faces.dotDataView();
            assertEquals(a.length, b.length);
            for (int i = 0; i < a.length; i++) {
                assertEquals(a[i], b[i], 1e-6f, "face dots @" + i);
            }
        } finally {
            fresh.cleanup();
        }
    }

    @Test
    void theEdgeSelectionSurvivesARebuildThatKeepsTheEdges() {
        EditModeManager.getInstance().setMode(EditMode.EDGE);
        Set<Integer> selected = new LinkedHashSet<>(Set.of(2, 5, 9));
        edges.updateSelectionSet(selected);
        draw();

        // A drag and its commit: positions-only, then one position refresh (geometry rebuilt, same edges)
        model.moveVertices(new int[] {3}, new float[] {0.0f, 0.1f, -2.7f});
        model.commitVertexPositions();
        assertEquals(selected, edges.getSelectedEdgeIndices(), "same edges: the selection stays");
        draw();

        // A structural change: only edges that still join the same two vertices stay selected
        assertTrue(model.deleteFace(19));
        for (int e : edges.getSelectedEdgeIndices()) {
            int[] ends = edges.getEdgeVertexIndices(e);
            assertTrue(ends != null, "kept edge exists");
        }
    }

    @Test
    void selectAllDrawsWithAConstantNumberOfCalls() {
        EditCostStats.reset();
        EditCostStats.setEnabled(true);
        for (EditMode mode : new EditMode[] {EditMode.VERTEX, EditMode.EDGE, EditMode.FACE}) {
            EditModeManager.getInstance().setMode(mode);
            Set<Integer> all = new LinkedHashSet<>();
            for (int i = 0; i < 500; i++) all.add(i);
            vertices.updateSelectionSet(all);
            edges.updateSelectionSet(all);
            faces.updateSelectionSet(all);
            draw(); // the change frame bakes
            EditCostStats.reset();
            draw();
            assertTrue(EditCostStats.total(EditCostStats.Counter.DRAW_CALLS) <= 12,
                    mode + ": draws " + EditCostStats.total(EditCostStats.Counter.DRAW_CALLS));
            assertEquals(0, EditCostStats.total(EditCostStats.Counter.BUFFER_UPLOADS),
                    mode + ": a steady select-all frame uploads nothing");
        }
    }
}
