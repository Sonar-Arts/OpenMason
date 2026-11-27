package com.openmason.main.systems.rendering.model.editing.subrenders.vertex;

import com.openmason.engine.modeling.EditableModel;
import com.openmason.engine.modeling.MeshChangeListener;
import com.openmason.engine.modeling.core.EditCostStats;
import com.openmason.main.systems.rendering.model.editing.subrenders.DirtySpans;
import com.openmason.main.systems.rendering.model.editing.subrenders.HoverQueryGate;
import com.openmason.main.systems.rendering.model.editing.subrenders.MeshOverlayTheme;
import com.openmason.main.systems.viewport.state.EditModeManager;
import com.openmason.engine.rendering.viewer.ViewerRenderContext;
import com.openmason.engine.rendering.shaders.ShaderProgram;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Renders model vertices as colored points, similar to Blender's vertex display mode.
 * Colors follow Blender's edit-mode convention ({@link MeshOverlayTheme}): black default,
 * orange selected, white for the active (last-selected) vertex.
 *
 * <p>Implements MeshChangeListener to receive notifications from EditableModel
 * when vertex positions change or geometry is rebuilt.
 *
 * <p>Cost model (#175): the interleaved VBO has a CPU mirror; moves and selection / hover changes write the mirror
 * and mark dirty ranges that each frame flushes in a few uploads. The selected set is redrawn larger from one
 * element list built when the selection changes, so a frame is three draw calls at most.
 */
public class VertexRenderer implements MeshChangeListener {

    private static final Logger logger = LoggerFactory.getLogger(VertexRenderer.class);

    private static final int FLOATS_PER_VERTEX = 6;

    // OpenGL resources
    private int vao = 0;
    private int vbo = 0;
    private int selectedEbo = 0;
    private int vertexCount = 0;
    private boolean initialized = false;

    // Rendering state
    private boolean enabled = false;
    private float pointSize = 7.0f;
    private float selectedPointSize = 10.0f;
    // Blender-style colors: black default, orange selected, white active (see MeshOverlayTheme)
    private final Vector3f defaultVertexColor = MeshOverlayTheme.VERTEX;
    private final Vector3f selectedVertexColor = MeshOverlayTheme.VERTEX_SELECT;
    private final Vector3f activeVertexColor = MeshOverlayTheme.VERTEX_ACTIVE;
    private final Vector3f hoverVertexColor = MeshOverlayTheme.VERTEX_HOVER;
    private final Vector3f modifiedVertexColor = MeshOverlayTheme.VERTEX_MODIFIED;

    // Hover state
    private int hoveredVertexIndex = -1; // -1 means no vertex is hovered
    private float[] vertexPositions = null; // Store positions for hit testing (unique vertices only)

    // Selection state - supports multi-selection (insertion order preserved so the
    // last-selected vertex can be shown as the "active" element, like Blender)
    private int selectedVertexIndex = -1; // -1 means no vertex is selected (for backward compat)
    private Set<Integer> selectedVertexIndices = new LinkedHashSet<>(); // Multi-selection support
    private int activeVertexIndex = -1; // Last-selected vertex, rendered white
    private Set<Integer> modifiedVertices = new HashSet<>(); // Indices of modified vertices

    // CPU mirror of the VBO + what is baked into it
    private float[] vboData = null;
    private final DirtySpans dirty = new DirtySpans();
    private int[] bakedSelection = new int[0];
    private int bakedHover = -1;
    private int bakedActive = -1;
    private boolean selectionDirty = false;
    private boolean hoverDirty = false;
    private int selectedElementCount = 0;

    private final HoverQueryGate hoverGate = new HoverQueryGate();

    // Reference to EditableModel for observer pattern
    private EditableModel modelRenderer;

    /**
     * Set the EditableModel that this VertexRenderer observes.
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

        logger.debug("VertexRenderer {} model renderer",
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

            // Interleaved position(3) + color(3)
            int stride = FLOATS_PER_VERTEX * Float.BYTES;
            glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0);
            glEnableVertexAttribArray(0);
            glVertexAttribPointer(1, 3, GL_FLOAT, false, stride, 3 * Float.BYTES);
            glEnableVertexAttribArray(1);

            // Selected-vertex element list (VAO state)
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, selectedEbo);

            glBindVertexArray(0);
            glBindBuffer(GL_ARRAY_BUFFER, 0);

            initialized = true;

        } catch (Exception e) {
            logger.error("Failed to initialize VertexRenderer", e);
            cleanup();
            throw new RuntimeException("VertexRenderer initialization failed", e);
        }
    }

    /**
     * Re-read every unique vertex from the EditableModel and re-upload the buffer.
     */
    public void updateVertexDataFromModel() {
        if (!initialized) {
            logger.warn("VertexRenderer not initialized, cannot update vertex data");
            return;
        }

        if (modelRenderer == null) {
            logger.warn("EditableModel not set");
            vertexCount = 0;
            vertexPositions = null;
            return;
        }

        try {
            float[] uniquePositions = modelRenderer.getAllUniqueVertexPositions();
            vertexPositions = uniquePositions;
            vertexCount = uniquePositions != null ? uniquePositions.length / 3 : 0;
            rebuildVBO();
            logger.debug("Updated vertex data from EditableModel: {} vertices", vertexCount);
        } catch (Exception e) {
            logger.error("Error updating vertex data from EditableModel", e);
        }
    }

    public void handleMouseMove(float mouseX, float mouseY,
                               Matrix4f viewMatrix, Matrix4f projectionMatrix,
                               Matrix4f modelMatrix,
                               int viewportWidth, int viewportHeight) {
        if (!initialized || !enabled) {
            hoverGate.invalidate();
            return;
        }

        // Skip hover detection if not in VERTEX edit mode
        if (!EditModeManager.getInstance().isVertexEditingAllowed()) {
            setHovered(-1);
            hoverGate.invalidate();
            return;
        }

        if (vertexPositions == null || vertexCount == 0) {
            return;
        }

        // Same mouse, camera, model and geometry as the last pick: the answer stands (#175)
        if (!hoverGate.shouldPick(mouseX, mouseY, viewMatrix, projectionMatrix, modelMatrix,
                viewportWidth, viewportHeight)) {
            return;
        }

        long span = EditCostStats.begin();
        try {
            // Detect hovered vertex using screen-space point size detection
            int newHoveredVertex = VertexHoverDetector.detectHoveredVertex(
                mouseX, mouseY,
                viewportWidth, viewportHeight,
                viewMatrix, projectionMatrix, modelMatrix,
                vertexPositions,
                vertexCount,
                pointSize  // Use actual vertex point size for accurate detection
            );
            setHovered(newHoveredVertex);
        } finally {
            EditCostStats.end(EditCostStats.Span.HOVER, span);
        }
    }

    private void setHovered(int index) {
        if (index != hoveredVertexIndex) {
            int previousHover = hoveredVertexIndex;
            hoveredVertexIndex = index;
            hoverDirty = true;
            logger.debug("Vertex hover changed: {} -> {} (total vertices: {})",
                        previousHover, hoveredVertexIndex, vertexCount);
        }
    }

    public void render(ShaderProgram shader, ViewerRenderContext context, Matrix4f modelMatrix) {
        if (!initialized) {
            logger.warn("VertexRenderer not initialized");
            return;
        }

        if (!enabled) {
            return;
        }

        if (vertexCount == 0) {
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

            // Enable point sprite rendering (shader controls gl_PointSize)
            glEnable(GL_VERTEX_PROGRAM_POINT_SIZE);
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

            int prevDepthFunc = glGetInteger(GL_DEPTH_FUNC);
            glDepthFunc(GL_LEQUAL);

            glBindVertexArray(vao);

            // Flat Blender-style dots: state colors are baked into the VBO
            // (black default, orange selected, white active, hover pre-highlight), no glow
            shader.setFloat("uIntensity", 1.0f);

            // Base pass: every vertex in one draw call
            shader.setFloat("uPointSize", pointSize);
            glDrawArrays(GL_POINTS, 0, vertexCount);
            EditCostStats.count(EditCostStats.Counter.DRAW_CALLS, 1);

            // Selected vertices redrawn slightly larger on top (one element draw)
            if (selectedElementCount > 0) {
                shader.setFloat("uPointSize", selectedPointSize);
                glDrawElements(GL_POINTS, selectedElementCount, GL_UNSIGNED_INT, 0L);
                EditCostStats.count(EditCostStats.Counter.DRAW_CALLS, 1);
            }

            // Hovered vertex: pre-highlight color (baked), drawn larger
            if (hoveredVertexIndex >= 0 && hoveredVertexIndex < vertexCount
                    && !selectedVertexIndices.contains(hoveredVertexIndex)) {
                shader.setFloat("uPointSize", pointSize + 2.0f);
                glDrawArrays(GL_POINTS, hoveredVertexIndex, 1);
                EditCostStats.count(EditCostStats.Counter.DRAW_CALLS, 1);
            }

            glBindVertexArray(0);
            glDepthFunc(prevDepthFunc);
            glDisable(GL_VERTEX_PROGRAM_POINT_SIZE);
            glDisable(GL_BLEND);

        } catch (Exception e) {
            logger.error("Error rendering vertices", e);
        } finally {
            glUseProgram(0);
        }
    }

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
        vertexCount = 0;
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
     * Updates the vertex directly by unique index - no position search needed.
     */
    @Override
    public void onVertexPositionChanged(int uniqueIndex, Vector3f newPosition, int[] affectedMeshIndices) {
        long span = EditCostStats.begin();
        try {
            if (!initialized || vertexPositions == null) {
                return;
            }
            if (uniqueIndex < 0 || uniqueIndex >= vertexCount) {
                logger.warn("onVertexPositionChanged: uniqueIndex {} out of bounds (vertexCount={})",
                    uniqueIndex, vertexCount);
                return;
            }
            writePosition(uniqueIndex, newPosition.x, newPosition.y, newPosition.z);
        } finally {
            EditCostStats.end(EditCostStats.Span.OVERLAY_MOVE, span);
        }
    }

    /** Batched vertex moves (#175): mirror writes only, one flush in {@link #render}. */
    @Override
    public void onVertexPositionsChanged(int[] uniqueIndices, float[] positions, int[][] affectedMeshIndices) {
        long span = EditCostStats.begin();
        try {
            if (!initialized || vertexPositions == null) {
                return;
            }
            for (int i = 0; i < uniqueIndices.length; i++) {
                int u = uniqueIndices[i];
                if (u >= 0 && u < vertexCount) {
                    writePosition(u, positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2]);
                }
            }
        } finally {
            EditCostStats.end(EditCostStats.Span.OVERLAY_MOVE, span);
        }
    }

    @Override
    public void onGeometryRebuilt() {
        logger.debug("VertexRenderer received geometry rebuild notification");
        rebuildFromModel();
    }

    private void rebuildFromModel() {
        long span = EditCostStats.begin();
        try {
            rebuildFromModelNow();
        } finally {
            EditCostStats.end(EditCostStats.Span.VERTEX_OVERLAY, span);
        }
    }

    private void rebuildFromModelNow() {
        if (modelRenderer == null) {
            return;
        }

        if (!initialized) {
            logger.warn("Cannot rebuild from model: VertexRenderer not initialized");
            return;
        }

        float[] uniquePositions = modelRenderer.getAllUniqueVertexPositions();
        if (uniquePositions == null || uniquePositions.length == 0) {
            vertexCount = 0;
            vertexPositions = null;
            vboData = null;
            return;
        }

        vertexPositions = uniquePositions;
        vertexCount = uniquePositions.length / 3;

        rebuildVBO();

        logger.debug("VertexRenderer rebuilt from model: {} unique vertices", vertexCount);
    }

    /** Writes a moved vertex into the hit-test array and the mirror (flushed at the next render). */
    private void writePosition(int index, float x, float y, float z) {
        int posIndex = index * 3;
        vertexPositions[posIndex] = x;
        vertexPositions[posIndex + 1] = y;
        vertexPositions[posIndex + 2] = z;
        if (vboData != null) {
            int o = index * FLOATS_PER_VERTEX;
            vboData[o] = x;
            vboData[o + 1] = y;
            vboData[o + 2] = z;
            dirty.mark(o, o + 3);
        }
        hoverGate.invalidate();
    }

    private Vector3f resolveVertexColor(int index) {
        if (index == hoveredVertexIndex && !selectedVertexIndices.contains(index)) {
            return hoverVertexColor;
        }
        if (index == activeVertexIndex && selectedVertexIndices.contains(index)) {
            return activeVertexColor;
        }
        if (selectedVertexIndices.contains(index)) {
            return selectedVertexColor;
        }
        if (modifiedVertices.contains(index)) {
            return modifiedVertexColor;
        }
        return defaultVertexColor;
    }

    /** Re-writes a vertex's colour into the mirror when its state colour changed. */
    private void bake(int index) {
        if (vboData == null || index < 0 || index >= vertexCount) return;
        Vector3f c = resolveVertexColor(index);
        int o = index * FLOATS_PER_VERTEX + 3;
        if (vboData[o] != c.x || vboData[o + 1] != c.y || vboData[o + 2] != c.z) {
            vboData[o] = c.x;
            vboData[o + 1] = c.y;
            vboData[o + 2] = c.z;
            dirty.mark(o, o + 3);
        }
    }

    /** Re-bakes the colours of the vertices whose state changed and rebuilds the selected element list. */
    private void applySelection() {
        for (int v : bakedSelection) bake(v);
        bake(bakedHover);
        bake(bakedActive);
        int[] next = new int[selectedVertexIndices.size()];
        int n = 0;
        for (int v : selectedVertexIndices) {
            if (v >= 0 && v < vertexCount) {
                next[n++] = v;
                bake(v);
            }
        }
        bakedSelection = Arrays.copyOf(next, n);
        bake(hoveredVertexIndex);
        bakedHover = hoveredVertexIndex;
        bakedActive = activeVertexIndex;

        int[] elements = Arrays.copyOf(next, n);
        glBindVertexArray(vao);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, selectedEbo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, elements, GL_DYNAMIC_DRAW);
        EditCostStats.count(EditCostStats.Counter.BUFFER_UPLOADS, 1);
        glBindVertexArray(0);
        selectedElementCount = n;
        selectionDirty = false;
        hoverDirty = false;
    }

    private void applyHover() {
        int previous = bakedHover;
        bakedHover = hoveredVertexIndex;
        bake(previous);
        bake(hoveredVertexIndex);
        hoverDirty = false;
    }

    public void setSelectedVertex(int index) {
        if (index < -1 || (index >= 0 && index >= vertexCount)) {
            logger.warn("Invalid vertex index for selection: {} (max: {})", index, vertexCount - 1);
            return;
        }

        selectedVertexIndices.clear();
        if (index >= 0) {
            selectedVertexIndices.add(index);
        }
        selectedVertexIndex = index;
        activeVertexIndex = index;
        selectionDirty = true;

        logger.debug("Selected vertex set to: {}", index);
    }

    public void updateSelectionSet(Set<Integer> indices) {
        selectedVertexIndices.clear();
        activeVertexIndex = -1;
        if (indices != null) {
            // Insertion order from the selection state is preserved (LinkedHashSet),
            // so the last valid index is the active element, like Blender
            for (Integer index : indices) {
                if (index >= 0 && index < vertexCount) {
                    selectedVertexIndices.add(index);
                    activeVertexIndex = index;
                }
            }
        }
        // Update backward compat field
        selectedVertexIndex = selectedVertexIndices.isEmpty() ? -1 : selectedVertexIndices.iterator().next();
        selectionDirty = true;

        logger.debug("Updated vertex selection set: {} vertices", selectedVertexIndices.size());
    }

    public Set<Integer> getSelectedVertexIndices() {
        return new HashSet<>(selectedVertexIndices);
    }

    public boolean isVertexSelected(int index) {
        return selectedVertexIndices.contains(index);
    }

    public int getSelectedVertexIndex() {
        return selectedVertexIndex;
    }

    public void clearSelection() {
        selectedVertexIndices.clear();
        selectedVertexIndex = -1;
        activeVertexIndex = -1;
        selectionDirty = true;

        logger.debug("Vertex selection cleared");
    }

    public void updateVertexPosition(int uniqueIndex, Vector3f position) {
        if (!initialized) {
            logger.warn("Cannot update vertex position: renderer not initialized");
            return;
        }
        if (position == null || vertexPositions == null
                || uniqueIndex < 0 || uniqueIndex >= vertexCount) {
            logger.warn("Cannot update vertex position: invalid index {} (count: {})",
                    uniqueIndex, vertexCount);
            return;
        }
        writePosition(uniqueIndex, position.x, position.y, position.z);
    }

    public int getHoveredVertexIndex() {
        return hoveredVertexIndex;
    }

    public Vector3f getVertexPosition(int index) {
        if (vertexPositions == null || index < 0 || index >= vertexCount) {
            return null;
        }

        int posIndex = index * 3;
        return new Vector3f(
            vertexPositions[posIndex],
            vertexPositions[posIndex + 1],
            vertexPositions[posIndex + 2]
        );
    }

    public float[] getAllVertexPositions() {
        return vertexPositions;
    }

    public int getVertexCount() {
        return vertexCount;
    }

    /** Rebuilds the mirror (positions + state colours) and uploads it whole. */
    private void rebuildVBO() {
        if (!initialized || vertexPositions == null || vertexCount == 0) {
            return;
        }

        float[] vertexData = new float[vertexCount * FLOATS_PER_VERTEX];
        for (int i = 0; i < vertexCount; i++) {
            int posIndex = i * 3;
            int dataIndex = i * FLOATS_PER_VERTEX;
            vertexData[dataIndex] = vertexPositions[posIndex];
            vertexData[dataIndex + 1] = vertexPositions[posIndex + 1];
            vertexData[dataIndex + 2] = vertexPositions[posIndex + 2];
            Vector3f color = resolveVertexColor(i);
            vertexData[dataIndex + 3] = color.x;
            vertexData[dataIndex + 4] = color.y;
            vertexData[dataIndex + 5] = color.z;
        }
        vboData = vertexData;
        dirty.clear();
        hoverGate.invalidate();
        // Selected indices beyond the new count drop out of the element list on the next render
        bakedSelection = new int[0];
        bakedHover = -1;
        bakedActive = -1;
        selectionDirty = true;

        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, vertexData, GL_DYNAMIC_DRAW);
        EditCostStats.count(EditCostStats.Counter.BUFFER_UPLOADS, 1);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        logger.debug("Rebuilt VBO with {} vertices", vertexCount);
    }

    // Getters and setters

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        if (enabled != this.enabled) hoverGate.invalidate();
        this.enabled = enabled;
    }

    public void setPointSize(float pointSize) {
        this.pointSize = Math.max(1.0f, Math.min(15.0f, pointSize));
        this.selectedPointSize = this.pointSize + 3.0f;
        hoverGate.invalidate();
        logger.trace("Point size set to: {}", this.pointSize);
    }

    public boolean isInitialized() {
        return initialized;
    }

}
