package com.openmason.main.systems.rendering.model.editing.subrenders.edge;

import com.openmason.engine.modeling.core.EditCostStats;
import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.openmason.engine.modeling.EditableModel;
import com.openmason.engine.modeling.MeshChangeListener;
import com.openmason.main.systems.rendering.model.editing.subrenders.DirtySpans;
import com.openmason.main.systems.rendering.model.editing.subrenders.HoverQueryGate;
import com.openmason.main.systems.rendering.model.editing.subrenders.MeshOverlayTheme;
import com.openmason.main.systems.rendering.model.editing.subrenders.vertex.VertexRenderer;
import com.openmason.engine.modeling.topology.MeshEdge;
import com.openmason.engine.modeling.topology.MeshTopology;
import com.openmason.engine.rendering.viewer.ViewerRenderContext;
import com.openmason.engine.modeling.mesh.edgeOperations.MeshEdgeGeometryQuery;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Renders model edges as lines in the 3D viewport.
 * Provides edge visualization, selection, and hover detection for model editing.
 *
 * <p>This renderer complements the VertexRenderer by displaying edges as lines,
 * allowing users to select and manipulate edges during model editing operations.
 * Supports hover highlighting, selection feedback, and real-time position updates
 * in response to vertex transformations.
 *
 * <p><b>Key Features:</b>
 * <ul>
 *   <li>Renders edges as GL_LINES with configurable width and colors</li>
 *   <li>Blender-style colors ({@link MeshOverlayTheme}): near-black wire, orange
 *       selection, white active (last-selected) edge, light hover pre-highlight</li>
 *   <li>Index-based edge updates via topology adjacency</li>
 * </ul>
 *
 * <p><b>Cost model (#175):</b> the interleaved VBO has a CPU mirror. Vertex moves and selection / hover changes
 * write the mirror and mark dirty ranges; each frame flushes them in a few uploads. Selection colours are baked
 * when the selection CHANGES and the selected set draws again on top from one element list, so a frame is at most
 * four draw calls (all, selected, active, hovered) however many edges are selected. A rebuild that keeps an
 * edge's id and endpoints keeps it selected.
 *
 * <p><b>Thread Safety:</b> This class is not thread-safe. All methods must be called
 * from the OpenGL context thread.
 *
 * <p><b>Data Format:</b> Edges are stored in an interleaved VBO format with 6 floats
 * per vertex (x, y, z, r, g, b). Edge positions are cached separately for hover detection.
 *
 * @see VertexRenderer
 */
public class EdgeRenderer implements MeshChangeListener {

    private static final Logger logger = LoggerFactory.getLogger(EdgeRenderer.class);

    /** Default line width for edge rendering in pixels. */
    private static final float DEFAULT_LINE_WIDTH = 1.5f;

    /**
     * Depth range far value for edge rendering.
     * Slightly less than 1.0 to bias coplanar edges toward the camera.
     * Must be very close to 1.0 to avoid back-face edges bleeding through front faces.
     */
    private static final double EDGE_DEPTH_RANGE_FAR = 0.99999;

    /** Normal intensity for edge rendering. */
    private static final float NORMAL_INTENSITY = 1.0f;

    /** Number of vertices per edge (start and end points). */
    private static final int VERTICES_PER_EDGE = 2;

    /** Number of floats per edge position (2 endpoints × 3 coordinates). */
    private static final int FLOATS_PER_EDGE = 6;

    /** Floats per VBO vertex (position + color). */
    private static final int FLOATS_PER_VERTEX = 6;

    /** Vertex attribute stride in bytes (position + color: 6 floats). */
    private static final int VERTEX_STRIDE_BYTES = FLOATS_PER_VERTEX * Float.BYTES;

    /** Color attribute offset in bytes (after 3 position floats). */
    private static final int COLOR_OFFSET_BYTES = 3 * Float.BYTES;

    /** Index value indicating no edge is selected or hovered. */
    private static final int NO_EDGE_SELECTED = -1;

    private static final byte STATE_DEFAULT = 0;
    private static final byte STATE_SELECTED = 1;
    private static final byte STATE_ACTIVE = 2;
    private static final byte STATE_HOVER = 3;

    // OpenGL resources
    private int vao = 0;
    private int vbo = 0;
    private int selectedEbo = 0;

    /** Number of edges currently loaded. */
    private int edgeCount = 0;

    /** Whether the renderer has been initialized. */
    private boolean initialized = false;

    /** Whether edge rendering is enabled. */
    private boolean enabled = false;

    /** Line width for edge rendering in pixels. */
    private float lineWidth = DEFAULT_LINE_WIDTH;

    private final Vector3f edgeColor = MeshOverlayTheme.EDGE;
    private final Vector3f hoverEdgeColor = MeshOverlayTheme.EDGE_HOVER;
    private final Vector3f selectedEdgeColor = MeshOverlayTheme.EDGE_SELECT;
    private final Vector3f activeEdgeColor = MeshOverlayTheme.EDGE_ACTIVE;

    /** Index of currently hovered edge, or -1 if no edge is hovered. */
    private int hoveredEdgeIndex = NO_EDGE_SELECTED;

    /** Cached edge positions for hover detection [x1,y1,z1, x2,y2,z2, ...]. */
    private float[] edgePositions = null;

    /** CPU mirror of the interleaved VBO (position + color per edge endpoint). */
    private float[] vboData = null;
    private final DirtySpans dirty = new DirtySpans();

    /** Edge endpoints (unique vertex ids) of the current buffer: a rebuild keeps selected edges whose ids match. */
    private int[] edgeVertexA = new int[0];
    private int[] edgeVertexB = new int[0];

    // Selection state - supports multi-selection (insertion order preserved so the
    // last-selected edge can be shown as the "active" element, like Blender)
    private int selectedEdgeIndex = NO_EDGE_SELECTED;
    private Set<Integer> selectedEdgeIndices = new LinkedHashSet<>();
    private int activeEdgeIndex = NO_EDGE_SELECTED;

    /** Selection / hover baked into the VBO colours (re-baked when they change). */
    private byte[] bakedState = new byte[0];
    private int[] bakedSelection = new int[0];
    private int bakedHover = NO_EDGE_SELECTED;
    private int bakedActive = NO_EDGE_SELECTED;
    private boolean selectionDirty = false;
    private boolean hoverDirty = false;
    private int selectedElementCount = 0;

    // Topology index for O(1) adjacency queries
    private MeshTopology topology = null;

    private final HoverQueryGate hoverGate = new HoverQueryGate();

    /**
     * Reference to EditableModel for observer pattern.
     * Receives notifications when vertex positions change or geometry is rebuilt.
     */
    private EditableModel modelRenderer;

    /**
     * Set the EditableModel that this EdgeRenderer observes.
     * Registers as a MeshChangeListener to receive index-based updates.
     *
     * @param renderer The EditableModel to observe, or null to disconnect
     */
    public void setModelRenderer(EditableModel renderer) {
        if (this.modelRenderer != null) {
            this.modelRenderer.removeMeshChangeListener(this);
        }

        this.modelRenderer = renderer;

        if (renderer != null) {
            renderer.addMeshChangeListener(this);
            rebuildFromModel();
        }

        logger.debug("EdgeRenderer {} model renderer",
            renderer != null ? "connected to" : "disconnected from");
    }

    /**
     * Get the associated EditableModel.
     *
     * @return The model renderer, or null if not set
     */
    public EditableModel getModelRenderer() {
        return modelRenderer;
    }

    /**
     * Initializes the edge renderer and allocates OpenGL resources.
     * Creates a VAO and VBO with interleaved vertex attributes (position + color)
     * and the selected-edge element buffer.
     *
     * @throws RuntimeException if OpenGL resource allocation fails
     */
    public void initialize() {
        if (initialized) {
            return;
        }

        try {
            vao = glGenVertexArrays();
            vbo = glGenBuffers();
            selectedEbo = glGenBuffers();

            glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferData(GL_ARRAY_BUFFER, 0, GL_DYNAMIC_DRAW);

            glVertexAttribPointer(0, 3, GL_FLOAT, false, VERTEX_STRIDE_BYTES, 0);
            glEnableVertexAttribArray(0);
            glVertexAttribPointer(1, 3, GL_FLOAT, false, VERTEX_STRIDE_BYTES, COLOR_OFFSET_BYTES);
            glEnableVertexAttribArray(1);

            // Selected-edge element list (VAO state)
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, selectedEbo);

            glBindVertexArray(0);
            glBindBuffer(GL_ARRAY_BUFFER, 0);

            initialized = true;

        } catch (Exception e) {
            logger.error("Failed to initialize EdgeRenderer", e);
            cleanup();
            throw new RuntimeException("EdgeRenderer initialization failed", e);
        }
    }

    /**
     * Update edge data from EditableModel (EditableModel is single source of truth).
     * This method gets edge data directly from EditableModel instead of extracting from ModelDefinition.
     * EditableModel provides the authoritative mesh topology after any subdivisions or modifications.
     */
    public void updateEdgeDataFromModel() {
        if (!initialized) {
            logger.warn("EdgeRenderer not initialized, cannot update edge data");
            return;
        }

        if (modelRenderer == null) {
            logger.warn("EditableModel not set");
            edgeCount = 0;
            edgePositions = null;
            return;
        }

        try {
            float[] extractedPositions = modelRenderer.extractEdgePositions();
            if (extractedPositions == null || extractedPositions.length % FLOATS_PER_EDGE != 0) {
                logger.warn("Edge extraction failed, clearing edge data");
                edgeCount = 0;
                edgePositions = null;
                return;
            }
            uploadAll(extractedPositions);
            logger.debug("Updated edge data from EditableModel: {} edges", edgeCount);
        } catch (Exception e) {
            logger.error("Error updating edge data from EditableModel", e);
        }
    }

    /** Builds the mirror (positions + baked colours) for {@code positions} and uploads it whole. */
    private void uploadAll(float[] positions) {
        edgePositions = positions;
        edgeCount = positions.length / FLOATS_PER_EDGE;
        int vertices = edgeCount * VERTICES_PER_EDGE;
        vboData = new float[vertices * FLOATS_PER_VERTEX];
        for (int v = 0; v < vertices; v++) {
            System.arraycopy(positions, v * 3, vboData, v * FLOATS_PER_VERTEX, 3);
            int o = v * FLOATS_PER_VERTEX + 3;
            vboData[o] = edgeColor.x;
            vboData[o + 1] = edgeColor.y;
            vboData[o + 2] = edgeColor.z;
        }
        bakedState = new byte[edgeCount];
        bakedSelection = new int[0];
        bakedHover = NO_EDGE_SELECTED;
        bakedActive = NO_EDGE_SELECTED;
        selectionDirty = true;
        dirty.clear();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, vboData, GL_DYNAMIC_DRAW);
        EditCostStats.count(EditCostStats.Counter.BUFFER_UPLOADS, 1);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        hoverGate.invalidate();
    }

    /**
     * Renders edges as lines with hover highlighting: every edge in one draw (state colours baked), then the
     * selected set again from its element list, then the active and the hovered edge on top.
     *
     * @param shader the shader program to use for rendering
     * @param context the render context containing camera matrices
     * @param modelMatrix the model transformation matrix (for gizmo transforms)
     */
    public void render(ShaderProgram shader, ViewerRenderContext context, Matrix4f modelMatrix) {
        if (!initialized || !enabled || edgeCount == 0) {
            return;
        }

        try {
            if (selectionDirty) {
                applySelection();
            } else if (hoverDirty) {
                applyHover();
            }
            dirty.flush(vbo, vboData);

            shader.use();
            Matrix4f viewMatrix = context.getCamera().getViewMatrix();
            Matrix4f projectionMatrix = context.getCamera().getProjectionMatrix();
            Matrix4f mvpMatrix = new Matrix4f(projectionMatrix).mul(viewMatrix).mul(modelMatrix);
            shader.setMat4("uMVPMatrix", mvpMatrix);
            shader.setFloat("uIntensity", NORMAL_INTENSITY);

            // Configure OpenGL state — depth range bias prevents z-fighting on coplanar cut edges
            glLineWidth(lineWidth);
            int prevDepthFunc = glGetInteger(GL_DEPTH_FUNC);
            glDepthFunc(GL_LEQUAL);
            glDepthRange(0.0, EDGE_DEPTH_RANGE_FAR);
            glBindVertexArray(vao);

            // 1: every edge (selected / active / hovered colours are baked)
            glDrawArrays(GL_LINES, 0, edgeCount * VERTICES_PER_EDGE);
            EditCostStats.count(EditCostStats.Counter.DRAW_CALLS, 1);

            // 2: selected edges again on top (one element draw)
            if (selectedElementCount > 0) {
                glDrawElements(GL_LINES, selectedElementCount, GL_UNSIGNED_INT, 0L);
                EditCostStats.count(EditCostStats.Counter.DRAW_CALLS, 1);
            }

            // 3: active (last-selected) edge, white like Blender
            boolean hasActive = activeEdgeIndex >= 0 && activeEdgeIndex < edgeCount
                    && selectedEdgeIndices.contains(activeEdgeIndex);
            if (hasActive && activeEdgeIndex != hoveredEdgeIndex) {
                glDrawArrays(GL_LINES, activeEdgeIndex * VERTICES_PER_EDGE, VERTICES_PER_EDGE);
                EditCostStats.count(EditCostStats.Counter.DRAW_CALLS, 1);
            }

            // 4: hovered edge on top
            if (hoveredEdgeIndex >= 0 && hoveredEdgeIndex < edgeCount) {
                glDrawArrays(GL_LINES, hoveredEdgeIndex * VERTICES_PER_EDGE, VERTICES_PER_EDGE);
                EditCostStats.count(EditCostStats.Counter.DRAW_CALLS, 1);
            }

            glBindVertexArray(0);
            glDepthRange(0.0, 1.0);
            glDepthFunc(prevDepthFunc);

        } catch (Exception e) {
            logger.error("Error rendering edges", e);
        } finally {
            glUseProgram(0);
        }
    }

    // =========================================================================
    // Selection / hover baking (runs when they change, not per frame)
    // =========================================================================

    private byte stateOf(int edge) {
        if (edge == hoveredEdgeIndex) return STATE_HOVER;
        if (selectedEdgeIndices.contains(edge)) {
            boolean hasActive = activeEdgeIndex >= 0 && selectedEdgeIndices.contains(activeEdgeIndex);
            return hasActive && edge == activeEdgeIndex ? STATE_ACTIVE : STATE_SELECTED;
        }
        return STATE_DEFAULT;
    }

    private void bake(int edge) {
        if (edge < 0 || edge >= edgeCount || vboData == null || edge >= bakedState.length) return;
        byte state = stateOf(edge);
        if (bakedState[edge] == state) return;
        bakedState[edge] = state;
        Vector3f color = switch (state) {
            case STATE_HOVER -> hoverEdgeColor;
            case STATE_ACTIVE -> activeEdgeColor;
            case STATE_SELECTED -> selectedEdgeColor;
            default -> edgeColor;
        };
        int start = edge * VERTICES_PER_EDGE;
        for (int v = start; v < start + VERTICES_PER_EDGE; v++) {
            int o = v * FLOATS_PER_VERTEX + 3;
            vboData[o] = color.x;
            vboData[o + 1] = color.y;
            vboData[o + 2] = color.z;
        }
        dirty.mark(start * FLOATS_PER_VERTEX, (start + VERTICES_PER_EDGE) * FLOATS_PER_VERTEX);
    }

    /** Re-bakes the colours of the edges whose state changed and rebuilds the selected element list. */
    private void applySelection() {
        for (int e : bakedSelection) bake(e);
        bake(bakedHover);
        bake(bakedActive);
        int[] next = new int[selectedEdgeIndices.size()];
        int n = 0;
        for (int e : selectedEdgeIndices) {
            if (e >= 0 && e < edgeCount) {
                next[n++] = e;
                bake(e);
            }
        }
        bakedSelection = Arrays.copyOf(next, n);
        bake(hoveredEdgeIndex);
        bakedHover = hoveredEdgeIndex;
        bakedActive = activeEdgeIndex;

        int[] elements = new int[n * VERTICES_PER_EDGE];
        for (int i = 0; i < n; i++) {
            elements[i * 2] = next[i] * VERTICES_PER_EDGE;
            elements[i * 2 + 1] = next[i] * VERTICES_PER_EDGE + 1;
        }
        glBindVertexArray(vao);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, selectedEbo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, elements, GL_DYNAMIC_DRAW);
        EditCostStats.count(EditCostStats.Counter.BUFFER_UPLOADS, 1);
        glBindVertexArray(0);
        selectedElementCount = elements.length;
        selectionDirty = false;
        hoverDirty = false;
    }

    private void applyHover() {
        int previous = bakedHover;
        bakedHover = hoveredEdgeIndex;
        bake(previous);
        bake(hoveredEdgeIndex);
        hoverDirty = false;
    }

    /**
     * Cleans up OpenGL resources.
     * Deletes the VAO and VBO, freeing GPU memory.
     *
     * <p>It is safe to call this method multiple times or when not initialized.
     */
    public void cleanup() {
        if (modelRenderer != null) {
            modelRenderer.removeMeshChangeListener(this);
            modelRenderer = null;
        }

        if (vao != 0) {
            glDeleteVertexArrays(vao);
            vao = 0;
        }
        if (vbo != 0) {
            glDeleteBuffers(vbo);
            vbo = 0;
        }
        if (selectedEbo != 0) {
            glDeleteBuffers(selectedEbo);
            selectedEbo = 0;
        }
        edgeCount = 0;
        vboData = null;
        dirty.clear();
        selectedElementCount = 0;
        hoverGate.invalidate();
        initialized = false;
    }

    // =========================================================================
    // MESH CHANGE LISTENER IMPLEMENTATION - Index-based updates from EditableModel
    // =========================================================================

    /**
     * Called when a vertex position has been updated in EditableModel.
     * Updates all edges connected to the changed vertex using index-based lookup.
     */
    @Override
    public void onVertexPositionChanged(int uniqueIndex, Vector3f newPosition, int[] affectedMeshIndices) {
        long editCostSpan = EditCostStats.begin();
        try {
            if (!initialized || edgePositions == null || topology == null) {
                return;
            }
            updateEdgesConnectedToVertexByIndex(uniqueIndex, newPosition);
        } finally {
            EditCostStats.end(EditCostStats.Span.OVERLAY_MOVE, editCostSpan);
        }
    }

    /** Batched vertex moves (#175): mirror writes only, one flush in {@link #render}. */
    @Override
    public void onVertexPositionsChanged(int[] uniqueIndices, float[] positions, int[][] affectedMeshIndices) {
        long editCostSpan = EditCostStats.begin();
        try {
            if (!initialized || edgePositions == null || topology == null) {
                return;
            }
            for (int i = 0; i < uniqueIndices.length; i++) {
                writeEndpoints(uniqueIndices[i], positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2]);
            }
            hoverGate.invalidate();
        } finally {
            EditCostStats.end(EditCostStats.Span.OVERLAY_MOVE, editCostSpan);
        }
    }

    /**
     * Called when the entire geometry has been rebuilt in EditableModel.
     * Triggers a full rebuild of edge data from the model.
     */
    @Override
    public void onGeometryRebuilt() {
        logger.debug("EdgeRenderer received geometry rebuild notification");
        rebuildFromModel();
    }

    /**
     * Called when the mesh topology has been rebuilt.
     * Stores the topology reference for O(1) adjacency queries.
     */
    @Override
    public void onTopologyRebuilt(MeshTopology topology) {
        this.topology = topology;
        logger.debug("EdgeRenderer received topology update: {} edges",
            topology != null ? topology.getEdgeCount() : 0);
    }

    private void rebuildFromModel() {
        long editCostSpan = EditCostStats.begin();
        try {
            rebuildFromModelNow();
        } finally {
            EditCostStats.end(EditCostStats.Span.EDGE_OVERLAY, editCostSpan);
        }
    }

    /**
     * Rebuild edge data from the associated EditableModel.
     * Called when initially connected or when geometry is rebuilt.
     * Uses MeshTopology for edge extraction when available.
     *
     * <p>Selected edges whose id still names the same two vertices stay selected (a drag commit or another
     * positions-only rebuild keeps every edge); edges that changed or vanished drop out.
     */
    private void rebuildFromModelNow() {
        if (modelRenderer == null || !initialized) {
            return;
        }

        MeshTopology topo = modelRenderer.getTopology();
        if (topo == null) {
            logger.debug("No topology available, clearing edge data");
            edgeCount = 0;
            edgePositions = null;
            vboData = null;
            this.topology = null;
            hoveredEdgeIndex = NO_EDGE_SELECTED;
            selectedEdgeIndex = NO_EDGE_SELECTED;
            selectedEdgeIndices.clear();
            activeEdgeIndex = NO_EDGE_SELECTED;
            return;
        }

        this.topology = topo;

        int newEdgeCount = topo.getEdgeCount();
        float[] newEdgePositions = new float[newEdgeCount * FLOATS_PER_EDGE];
        int[] newA = new int[newEdgeCount];
        int[] newB = new int[newEdgeCount];
        float[] unique = modelRenderer.getAllUniqueVertexPositions();

        for (int e = 0; e < newEdgeCount; e++) {
            MeshEdge edge = topo.getEdge(e);
            newA[e] = edge.vertexA();
            newB[e] = edge.vertexB();
            int a = edge.vertexA() * 3;
            int b = edge.vertexB() * 3;
            if (unique != null && a >= 0 && b >= 0 && a + 2 < unique.length && b + 2 < unique.length) {
                int offset = e * FLOATS_PER_EDGE;
                System.arraycopy(unique, a, newEdgePositions, offset, 3);
                System.arraycopy(unique, b, newEdgePositions, offset + 3, 3);
            }
        }

        // Keep the selected / active edges whose id still means the same edge
        Set<Integer> keep = new LinkedHashSet<>();
        for (int e : selectedEdgeIndices) {
            if (sameEdge(e, newA, newB)) keep.add(e);
        }
        boolean keepActive = sameEdge(activeEdgeIndex, newA, newB) && keep.contains(activeEdgeIndex);
        edgeVertexA = newA;
        edgeVertexB = newB;

        uploadAll(newEdgePositions);

        hoveredEdgeIndex = NO_EDGE_SELECTED;
        selectedEdgeIndices.clear();
        selectedEdgeIndices.addAll(keep);
        activeEdgeIndex = keepActive ? activeEdgeIndex : NO_EDGE_SELECTED;
        selectedEdgeIndex = selectedEdgeIndices.isEmpty() ? NO_EDGE_SELECTED : selectedEdgeIndices.iterator().next();
        selectionDirty = true;

        logger.debug("EdgeRenderer rebuilt from topology: {} edges ({} stay selected)", edgeCount, keep.size());
    }

    /** True when edge {@code e} of the previous buffer and of the new one join the same two vertices. */
    private boolean sameEdge(int e, int[] newA, int[] newB) {
        return e >= 0 && e < edgeVertexA.length && e < newA.length
                && ((edgeVertexA[e] == newA[e] && edgeVertexB[e] == newB[e])
                    || (edgeVertexA[e] == newB[e] && edgeVertexB[e] == newA[e]));
    }

    // Getters and setters

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        if (enabled != this.enabled) hoverGate.invalidate();
        this.enabled = enabled;
    }

    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Returns the index of the currently hovered edge.
     *
     * @return hovered edge index, or -1 if no edge is hovered
     */
    public int getHoveredEdgeIndex() {
        return hoveredEdgeIndex;
    }

    /**
     * Sets the hovered edge index.
     * Called by input controller after hover detection via raycasting.
     *
     * @param edgeIndex the index of the edge to set as hovered, or -1 to clear hover
     */
    public void setHoveredEdgeIndex(int edgeIndex) {
        if (edgeIndex != hoveredEdgeIndex) {
            int previousHover = hoveredEdgeIndex;
            hoveredEdgeIndex = edgeIndex;
            hoverDirty = true;

            logger.debug("Edge hover changed: {} -> {} (total edges: {})",
                    previousHover, hoveredEdgeIndex, edgeCount);
        }
        if (edgeIndex < 0) {
            // A forced clear (vertex priority, mode change): the next pick must run again
            hoverGate.invalidate();
        }
    }

    /**
     * Hover pick through this renderer's gate (#175): runs {@link EdgeHoverDetector} only when the mouse, the
     * camera / model matrices, the viewport or the edge geometry changed since the last pick.
     */
    public void updateHover(float mouseX, float mouseY, Matrix4f viewMatrix, Matrix4f projectionMatrix,
                            Matrix4f modelMatrix, int viewportWidth, int viewportHeight) {
        if (edgePositions == null || edgeCount == 0) {
            return;
        }
        if (!hoverGate.shouldPick(mouseX, mouseY, viewMatrix, projectionMatrix, modelMatrix,
                viewportWidth, viewportHeight)) {
            return;
        }
        long span = EditCostStats.begin();
        try {
            int hit = EdgeHoverDetector.detectHoveredEdge(mouseX, mouseY, viewportWidth, viewportHeight,
                    viewMatrix, projectionMatrix, modelMatrix, edgePositions, edgeCount, lineWidth);
            if (hit != hoveredEdgeIndex) {
                int previousHover = hoveredEdgeIndex;
                hoveredEdgeIndex = hit;
                hoverDirty = true;
                logger.debug("Edge hover changed: {} -> {} (total edges: {})", previousHover, hit, edgeCount);
            }
        } finally {
            EditCostStats.end(EditCostStats.Span.HOVER, span);
        }
    }

    /**
     * Returns the edge positions array for hover detection.
     *
     * @return edge positions array in format [x1,y1,z1, x2,y2,z2, ...], or null if no edges
     */
    public float[] getEdgePositions() {
        return edgePositions;
    }

    public int getEdgeCount() {
        return edgeCount;
    }

    /**
     * Returns the line width used for edge rendering.
     *
     * @return line width in pixels
     */
    public float getLineWidth() {
        return lineWidth;
    }

    /**
     * Update the selection with a set of edge indices (multi-selection).
     * @param indices Set of edge indices to select
     */
    public void updateSelectionSet(Set<Integer> indices) {
        selectedEdgeIndices.clear();
        activeEdgeIndex = NO_EDGE_SELECTED;
        if (indices != null) {
            // Insertion order from the selection state is preserved (LinkedHashSet),
            // so the last valid index is the active element, like Blender
            for (Integer index : indices) {
                if (index >= 0 && index < edgeCount) {
                    selectedEdgeIndices.add(index);
                    activeEdgeIndex = index;
                }
            }
        }
        selectedEdgeIndex = selectedEdgeIndices.isEmpty() ? NO_EDGE_SELECTED : selectedEdgeIndices.iterator().next();
        selectionDirty = true;
        logger.debug("Updated edge selection set: {} edges", selectedEdgeIndices.size());
    }

    /** The edge indices this renderer currently highlights as selected (a copy). */
    public Set<Integer> getSelectedEdgeIndices() {
        return new LinkedHashSet<>(selectedEdgeIndices);
    }

    /**
     * Clears the edge selection.
     */
    public void clearSelection() {
        selectedEdgeIndex = NO_EDGE_SELECTED;
        selectedEdgeIndices.clear();
        activeEdgeIndex = NO_EDGE_SELECTED;
        selectionDirty = true;
    }

    /**
     * Returns the endpoint positions of an edge.
     *
     * @param edgeIndex the index of the edge to query
     * @return array containing [endpoint1, endpoint2], or null if index is invalid
     */
    public Vector3f[] getEdgeEndpoints(int edgeIndex) {
        MeshEdgeGeometryQuery query = new MeshEdgeGeometryQuery();
        return query.getEdgeVertices(edgeIndex, edgePositions, VERTICES_PER_EDGE);
    }

    /**
     * Updates edges connected to a single vertex using topology adjacency. Writes the CPU copies; the GPU buffer
     * receives the change at the next {@link #render} (one flush per frame).
     *
     * @param vertexIndex the unique vertex index that was moved
     * @param newPosition the new position for the vertex
     */
    public void updateEdgesConnectedToVertexByIndex(int vertexIndex, Vector3f newPosition) {
        if (!initialized) {
            logger.warn("Cannot update edges: renderer not initialized");
            return;
        }

        if (topology == null) {
            logger.warn("Cannot update edges by index: topology not available.");
            return;
        }
        writeEndpoints(vertexIndex, newPosition.x, newPosition.y, newPosition.z);
        hoverGate.invalidate();
    }

    private void writeEndpoints(int vertexIndex, float x, float y, float z) {
        if (edgePositions == null || vboData == null) return;
        java.util.List<Integer> connectedEdgeIds = topology.getEdgesForVertex(vertexIndex);
        for (int edgeId : connectedEdgeIds) {
            if (edgeId < 0 || edgeId >= edgeCount) continue;
            MeshEdge edge = topology.getEdge(edgeId);
            if (edge == null) continue;
            if (edge.vertexA() == vertexIndex) writeEndpoint(edgeId, 0, x, y, z);
            if (edge.vertexB() == vertexIndex) writeEndpoint(edgeId, 1, x, y, z);
        }
    }

    private void writeEndpoint(int edgeId, int end, float x, float y, float z) {
        int p = edgeId * FLOATS_PER_EDGE + end * 3;
        edgePositions[p] = x;
        edgePositions[p + 1] = y;
        edgePositions[p + 2] = z;
        int o = (edgeId * VERTICES_PER_EDGE + end) * FLOATS_PER_VERTEX;
        vboData[o] = x;
        vboData[o + 1] = y;
        vboData[o + 2] = z;
        dirty.mark(o, o + 3);
    }

    /**
     * Returns the unique vertex indices for a given edge.
     *
     * @param edgeIndex the edge index to query
     * @return array of [vertexIndex1, vertexIndex2], or null if topology is not available
     */
    public int[] getEdgeVertexIndices(int edgeIndex) {
        if (topology == null) {
            return null;
        }
        MeshEdge edge = topology.getEdge(edgeIndex);
        if (edge == null) {
            return null;
        }
        return new int[] { edge.vertexA(), edge.vertexB() };
    }

}
