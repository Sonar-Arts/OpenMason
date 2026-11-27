package com.openmason.main.systems.rendering.model.editing.subrenders.face;

import com.openmason.engine.modeling.MeshChangeListener;
import com.openmason.engine.modeling.core.EditCostStats;
import com.openmason.engine.modeling.topology.MeshFace;
import com.openmason.engine.modeling.topology.MeshTopology;
import com.openmason.main.systems.rendering.model.editing.subrenders.DirtySpans;
import com.openmason.main.systems.rendering.model.editing.subrenders.HoverQueryGate;
import com.openmason.main.systems.rendering.model.editing.subrenders.MeshOverlayTheme;
import com.openmason.main.systems.viewport.state.EditModeManager;
import com.openmason.engine.rendering.viewer.ViewerRenderContext;
import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.openmason.engine.modeling.EditableModel;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Renders Blender-style face highlights in the 3D viewport:
 * <ul>
 *   <li><b>Translucent fills</b> — selected faces are tinted orange, the active
 *       (last-selected) face white, and the hovered face gets a faint pre-highlight
 *       (FACE shader, depth writes off)</li>
 *   <li><b>Boundary outlines</b> — GL_LINES over the fill for crisp face borders</li>
 *   <li><b>Face dots</b> — centroid points for every face while in FACE edit mode,
 *       colored by selection state like Blender's facedots (VERTEX point shader)</li>
 * </ul>
 * Colors come from {@link MeshOverlayTheme}.
 *
 * <p>Cost model (#175): a structural rebuild builds its lookups once (face → triangles, corner → triangle slots,
 * vertex → outline slots); a vertex move then touches only that vertex's slots in CPU mirrors of the three
 * buffers, and each frame flushes the dirty ranges in a few uploads. Selection and hover state is baked into the
 * outline / dot colours and into element lists when it CHANGES, so a frame draws the selected set with one call
 * per layer however many faces are selected.
 *
 * @see FaceHoverDetector
 */
public class FaceRenderer implements MeshChangeListener {

    private static final Logger logger = LoggerFactory.getLogger(FaceRenderer.class);

    /** Floats per line vertex: position(3) + color(3). */
    private static final int LINE_FLOATS_PER_VERTEX = 6;
    private static final int LINE_STRIDE_BYTES = LINE_FLOATS_PER_VERTEX * Float.BYTES;
    private static final int LINE_COLOR_OFFSET_BYTES = 3 * Float.BYTES;
    private static final int VERTICES_PER_LINE_SEGMENT = 2;

    /** Line widths. */
    private static final float HOVER_LINE_WIDTH = 2.0f;
    private static final float SELECTED_LINE_WIDTH = 2.0f;

    /** Colors (Blender default theme, see MeshOverlayTheme). */
    private static final Vector3f DEFAULT_EDGE_COLOR = new Vector3f(0.0f, 0.0f, 0.0f); // never drawn
    private static final Vector3f HOVER_EDGE_COLOR = MeshOverlayTheme.FACE_HOVER_OUTLINE;
    private static final Vector3f SELECTED_EDGE_COLOR = MeshOverlayTheme.FACE_SELECT_OUTLINE;
    private static final Vector3f ACTIVE_EDGE_COLOR = MeshOverlayTheme.FACE_ACTIVE_OUTLINE;

    /** Depth range far value biasing overlays toward the camera on coplanar geometry. */
    private static final double OVERLAY_DEPTH_RANGE_FAR = 0.99999;

    // ── Socket (attachment point) demarcation ──
    /** Cyan matching the socket viewport markers and the rigging tree glyph. */
    private static final Vector3f SOCKET_FACE_FILL = new Vector3f(0.35f, 0.80f, 0.95f);
    private static final float SOCKET_FACE_ALPHA = 0.18f;
    private static final Vector3f SOCKET_FACE_DOT = new Vector3f(0.35f, 0.80f, 0.95f);
    /** A face is "socketed" when a socket sits at its centroid within this distance. */
    private static final float SOCKET_FACE_MATCH_DISTANCE = 0.01f;

    // ── Outline (line) buffer: every face's boundary segments, face-contiguous ──
    private int lineVao = 0;
    private int lineVbo = 0;
    private int lineEbo = 0;
    private boolean lineResourcesInitialized = false;
    private float[] lineData = null;
    private final DirtySpans lineDirty = new DirtySpans();
    /** Per-face: [startLineVertex, lineVertexCount] in the line VBO. */
    private int[] faceLineOffsets = null;
    private int[] faceLineCounts = null;
    private int totalLineVertices = 0;
    /** Unique vertex -> the line vertices that sit on it (CSR). */
    private int[] vertexLineStart = null;
    private int[] vertexLineRef = null;
    private int lineElementCount = 0;

    // ── Fill buffer: every face's triangles, face-contiguous (position only, FACE shader) ──
    private int fillVao = 0;
    private int fillVbo = 0;
    private int fillEbo = 0;
    private boolean fillResourcesInitialized = false;
    private boolean fillDataDirty = true;
    private float[] fillData = null;
    private final DirtySpans fillDirty = new DirtySpans();
    private int totalFillVertices = 0;
    private int fillElementCount = 0;

    // ── Face dots (pos+color, VERTEX point shader); dot index == face index ──
    private int dotVao = 0;
    private int dotVbo = 0;
    private boolean dotResourcesInitialized = false;
    private boolean dotDataDirty = true;
    private float[] dotData = null;
    private final DirtySpans dotDirty = new DirtySpans();
    private int dotCount = 0;

    // ── State ──
    private boolean initialized = false;
    private boolean enabled = false;
    private int faceCount = 0;

    // Hover / selection (insertion order preserved so the last-selected face
    // can be shown as the "active" element, like Blender)
    private int hoveredFaceIndex = -1;
    private Set<Integer> selectedFaceIndices = new LinkedHashSet<>();
    private int activeFaceIndex = -1;
    private int editingFaceIndex = -1;
    /** Selection changed: element lists and baked colours are rebuilt on the next render. */
    private boolean selectionDirty = true;
    /** Hover changed: only the old and new hovered faces are re-baked on the next render. */
    private boolean hoverDirty = false;
    /** Faces whose baked outline colour is not the default (to reset on the next selection change). */
    private int[] bakedFaces = new int[0];
    private int bakedHover = -1;
    private byte[] bakedState = new byte[0];

    // Socket demarcation: model-space socket positions pushed by the pipeline
    // each frame; face ids re-derived (socket at face centroid) when either
    // the sockets or the face geometry change.
    private final List<Vector3f> socketPositions = new ArrayList<>();
    private final Set<Integer> socketFaceIds = new HashSet<>();
    private boolean socketFacesDirty = false;

    // EditableModel integration + per-rebuild lookups
    private EditableModel genericModelRenderer = null;
    private float[] trianglePositions = null;
    private int triangleCount = 0;
    /** Face -> its triangles (CSR over face ids); CSR position p = fill vertices 3p..3p+2. */
    private int[] faceTriStart = new int[1];
    private int[] faceTris = new int[0];
    /** Triangle -> its CSR position (fill slot) and its face. */
    private int[] triSlot = new int[0];
    private int[] triFace = new int[0];
    /** Corner -> the (triangle * 3 + corner-in-triangle) refs that use it (CSR). */
    private int[] cornerTriStart = new int[1];
    private int[] cornerTriRef = new int[0];
    /** Unique vertex positions (x,y,z per editable vertex id), kept current by vertex moves. */
    private float[] uniquePositions = null;
    /** Face visit stamps for de-duplicating the faces touched by one move batch. */
    private int[] faceStamp = new int[0];
    private int stamp = 0;

    private final HoverQueryGate hoverGate = new HoverQueryGate();

    // =========================================================================
    // Initialization
    // =========================================================================

    public void initialize() {
        if (initialized) {
            return;
        }
        // Nothing heavyweight here — GL resources are created lazily
        initialized = true;
        logger.debug("FaceRenderer initialized");
    }

    // =========================================================================
    // Face data loading
    // =========================================================================

    /**
     * Rebuild face data from EditableModel (primary path).
     */
    public void rebuildFromEditableModel() {
        long editCostSpan = EditCostStats.begin();
        try {
            rebuildFromEditableModelNow();
        } finally {
            EditCostStats.end(EditCostStats.Span.FACE_OVERLAY, editCostSpan);
        }
    }

    private void rebuildFromEditableModelNow() {
        if (!initialized || genericModelRenderer == null) {
            return;
        }
        hoverGate.invalidate();

        // An empty mesh is a benign transient: every model load clears the part
        // set (empty rebuild) before the real rebuild repopulates it. Only warn
        // when geometry exists without a mapping — that is the anomalous case.
        triangleCount = genericModelRenderer.getTriangleCount();
        if (triangleCount == 0) {
            faceCount = 0;
            trianglePositions = null;
            faceTriStart = new int[1];
            faceTris = new int[0];
            totalFillVertices = 0;
            totalLineVertices = 0;
            dotCount = 0;
            return;
        }

        if (!genericModelRenderer.hasTriangleToFaceMapping()) {
            logger.warn("EditableModel has no triangle-to-face mapping");
            return;
        }

        try {
            float[] meshVertices = genericModelRenderer.meshVertexPositionsView();
            int[] triangleIndices = genericModelRenderer.triangleIndicesView();
            if (meshVertices == null || triangleIndices == null) {
                logger.warn("Cannot rebuild face data: mesh data is null");
                return;
            }
            uniquePositions = genericModelRenderer.getAllUniqueVertexPositions();
            buildTriangleLookups(meshVertices, triangleIndices);
            rebuildLineVBO();

            // Fill + dot VBOs are rebuilt lazily on next render; socket-face
            // matching re-derives against the new centroids; selection lists and
            // baked colours follow the new buffers
            bakedState = new byte[faceCount];
            bakedFaces = new int[0];
            bakedHover = -1;
            fillDataDirty = true;
            dotDataDirty = true;
            socketFacesDirty = true;
            selectionDirty = true;

            logger.debug("Rebuilt face data: {} faces, {} triangles", faceCount, triangleCount);
        } catch (Exception e) {
            logger.error("Error rebuilding face data from EditableModel", e);
        }
    }

    /** Face -> triangles, triangle -> slot/face, corner -> triangle refs, hover triangle positions. */
    private void buildTriangleLookups(float[] meshVertices, int[] triangleIndices) {
        int tris = triangleCount;
        triFace = new int[tris];
        int maxFace = -1;
        for (int t = 0; t < tris; t++) {
            int f = genericModelRenderer.getOriginalFaceIdForTriangle(t);
            triFace[t] = f;
            if (f > maxFace) maxFace = f;
        }
        int faceSpan = maxFace + 1;
        // Distinct face ids (the model's original face count)
        boolean[] seen = new boolean[faceSpan];
        int distinct = 0;
        for (int t = 0; t < tris; t++) {
            int f = triFace[t];
            if (f >= 0 && !seen[f]) {
                seen[f] = true;
                distinct++;
            }
        }
        faceCount = distinct;

        faceTriStart = new int[faceSpan + 1];
        for (int t = 0; t < tris; t++) {
            if (triFace[t] >= 0) faceTriStart[triFace[t] + 1]++;
        }
        for (int f = 0; f < faceSpan; f++) faceTriStart[f + 1] += faceTriStart[f];
        faceTris = new int[faceTriStart[faceSpan]];
        triSlot = new int[tris];
        Arrays.fill(triSlot, -1);
        int[] cursor = Arrays.copyOf(faceTriStart, faceSpan);
        for (int t = 0; t < tris; t++) {
            int f = triFace[t];
            if (f < 0) continue;
            int p = cursor[f]++;
            faceTris[p] = t;
            triSlot[t] = p;
        }
        faceStamp = new int[Math.max(faceSpan, 1)];
        stamp = 0;

        int corners = meshVertices.length / 3;
        cornerTriStart = new int[corners + 1];
        for (int i = 0; i < tris * 3; i++) {
            int c = triangleIndices[i];
            if (c >= 0 && c < corners) cornerTriStart[c + 1]++;
        }
        for (int c = 0; c < corners; c++) cornerTriStart[c + 1] += cornerTriStart[c];
        cornerTriRef = new int[cornerTriStart[corners]];
        int[] cc = Arrays.copyOf(cornerTriStart, corners);
        for (int i = 0; i < tris * 3; i++) {
            int c = triangleIndices[i];
            if (c >= 0 && c < corners) cornerTriRef[cc[c]++] = i;
        }

        trianglePositions = new float[tris * 9];
        for (int i = 0; i < tris * 3; i++) {
            int c = triangleIndices[i];
            if (c >= 0 && c < corners) System.arraycopy(meshVertices, c * 3, trianglePositions, i * 3, 3);
        }
    }

    // =========================================================================
    // Line VBO — boundary edges for every face
    // =========================================================================

    /**
     * Build a GL_LINES VBO containing the boundary edges of every face.
     * Format: interleaved pos(3) + color(3) per vertex, same as EdgeRenderer.
     * Default color is black (edges won't actually be drawn unless hovered/selected).
     */
    private void rebuildLineVBO() {
        if (genericModelRenderer == null) {
            return;
        }

        MeshTopology topology = genericModelRenderer.getTopology();
        if (topology == null || faceCount == 0 || uniquePositions == null) {
            totalLineVertices = 0;
            return;
        }

        // First pass: count total line vertices
        faceLineOffsets = new int[faceCount];
        faceLineCounts = new int[faceCount];
        totalLineVertices = 0;
        int uniqueCount = uniquePositions.length / 3;
        int[] perVertex = new int[uniqueCount + 1];

        for (int f = 0; f < faceCount; f++) {
            MeshFace face = topology.getFace(f);
            int edgeCount = (face != null) ? face.vertexIndices().length : 0;
            faceLineOffsets[f] = totalLineVertices;
            faceLineCounts[f] = edgeCount * VERTICES_PER_LINE_SEGMENT;
            totalLineVertices += edgeCount * VERTICES_PER_LINE_SEGMENT;
            if (face != null) {
                for (int v : face.vertexIndices()) {
                    if (v >= 0 && v < uniqueCount) perVertex[v + 1] += 2; // vertex A of one segment, B of another
                }
            }
        }

        if (totalLineVertices == 0) {
            return;
        }

        for (int v = 0; v < uniqueCount; v++) perVertex[v + 1] += perVertex[v];
        vertexLineStart = perVertex;
        vertexLineRef = new int[perVertex[uniqueCount]];
        int[] cursor = Arrays.copyOf(perVertex, uniqueCount);

        // Second pass: fill vertex data + vertex -> line slots
        float[] data = new float[totalLineVertices * LINE_FLOATS_PER_VERTEX];
        for (int f = 0; f < faceCount; f++) {
            MeshFace face = topology.getFace(f);
            if (face == null) continue;
            int[] vertIndices = face.vertexIndices();
            int baseVertex = faceLineOffsets[f];
            for (int i = 0; i < vertIndices.length; i++) {
                int a = vertIndices[i];
                int b = vertIndices[(i + 1) % vertIndices.length];
                int vIdx = baseVertex + i * VERTICES_PER_LINE_SEGMENT;
                writeLinePosition(data, vIdx, a);
                writeLinePosition(data, vIdx + 1, b);
                writeColor(data, vIdx, DEFAULT_EDGE_COLOR);
                writeColor(data, vIdx + 1, DEFAULT_EDGE_COLOR);
                if (a >= 0 && a < uniqueCount) vertexLineRef[cursor[a]++] = vIdx;
                if (b >= 0 && b < uniqueCount) vertexLineRef[cursor[b]++] = vIdx + 1;
            }
        }
        lineData = data;
        lineDirty.clear();
        lineElementCount = 0;

        ensureLineResources();
        glBindBuffer(GL_ARRAY_BUFFER, lineVbo);
        glBufferData(GL_ARRAY_BUFFER, data, GL_DYNAMIC_DRAW);
        EditCostStats.count(EditCostStats.Counter.BUFFER_UPLOADS, 1);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        logger.debug("Built face line VBO: {} faces, {} line vertices", faceCount, totalLineVertices);
    }

    private void writeLinePosition(float[] data, int lineVertex, int uniqueVertex) {
        int o = lineVertex * LINE_FLOATS_PER_VERTEX;
        if (uniqueVertex >= 0 && uniqueVertex * 3 + 2 < uniquePositions.length) {
            data[o] = uniquePositions[uniqueVertex * 3];
            data[o + 1] = uniquePositions[uniqueVertex * 3 + 1];
            data[o + 2] = uniquePositions[uniqueVertex * 3 + 2];
        }
    }

    private static void writeColor(float[] data, int lineVertex, Vector3f color) {
        int o = lineVertex * LINE_FLOATS_PER_VERTEX + 3;
        data[o] = color.x;
        data[o + 1] = color.y;
        data[o + 2] = color.z;
    }

    private void ensureLineResources() {
        if (lineResourcesInitialized) {
            return;
        }

        lineVao = glGenVertexArrays();
        lineVbo = glGenBuffers();
        lineEbo = glGenBuffers();

        glBindVertexArray(lineVao);
        glBindBuffer(GL_ARRAY_BUFFER, lineVbo);
        glBufferData(GL_ARRAY_BUFFER, 0, GL_DYNAMIC_DRAW);

        // Position (location 0)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, LINE_STRIDE_BYTES, 0);
        glEnableVertexAttribArray(0);

        // Color (location 1)
        glVertexAttribPointer(1, 3, GL_FLOAT, false, LINE_STRIDE_BYTES, LINE_COLOR_OFFSET_BYTES);
        glEnableVertexAttribArray(1);

        // Selected-face outline element list (VAO state)
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, lineEbo);

        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        lineResourcesInitialized = true;
    }

    // =========================================================================
    // Fill VBO — translucent per-face triangle overlays (Blender face_select)
    // =========================================================================

    private void ensureFillResources() {
        if (fillResourcesInitialized) {
            return;
        }

        fillVao = glGenVertexArrays();
        fillVbo = glGenBuffers();
        fillEbo = glGenBuffers();

        glBindVertexArray(fillVao);
        glBindBuffer(GL_ARRAY_BUFFER, fillVbo);
        glBufferData(GL_ARRAY_BUFFER, 0, GL_DYNAMIC_DRAW);

        // Position only (location 0) — color comes from the FACE shader's uColor/uAlpha
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0);
        glEnableVertexAttribArray(0);

        // Selected-face fill element list (VAO state)
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, fillEbo);

        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        fillResourcesInitialized = true;
    }

    /**
     * Build a GL_TRIANGLES VBO with every face's triangles stored contiguously (face order), so a single face is
     * one glDrawArrays range and a face set is one element list. Sourced from the triangle positions cache.
     */
    private void rebuildFillVBO() {
        if (trianglePositions == null || faceCount == 0) {
            totalFillVertices = 0;
            return;
        }

        totalFillVertices = faceTris.length * 3;
        if (totalFillVertices == 0) {
            return;
        }

        float[] data = new float[totalFillVertices * 3];
        for (int p = 0; p < faceTris.length; p++) {
            System.arraycopy(trianglePositions, faceTris[p] * 9, data, p * 9, 9);
        }
        fillData = data;
        fillDirty.clear();

        ensureFillResources();

        glBindBuffer(GL_ARRAY_BUFFER, fillVbo);
        glBufferData(GL_ARRAY_BUFFER, data, GL_DYNAMIC_DRAW);
        EditCostStats.count(EditCostStats.Counter.BUFFER_UPLOADS, 1);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        logger.debug("Built face fill VBO: {} faces, {} fill vertices", faceCount, totalFillVertices);
    }

    private int faceFillOffset(int faceIndex) {
        return faceTriStart[faceIndex] * 3;
    }

    private int faceFillCount(int faceIndex) {
        return (faceTriStart[faceIndex + 1] - faceTriStart[faceIndex]) * 3;
    }

    private void drawFaceFill(int faceIndex) {
        if (faceIndex < 0 || faceIndex + 1 >= faceTriStart.length) {
            return;
        }
        int count = faceFillCount(faceIndex);
        if (count > 0) {
            glDrawArrays(GL_TRIANGLES, faceFillOffset(faceIndex), count);
            EditCostStats.count(EditCostStats.Counter.DRAW_CALLS, 1);
        }
    }

    // =========================================================================
    // Dot VBO — face centroid points (Blender facedots)
    // =========================================================================

    private void ensureDotResources() {
        if (dotResourcesInitialized) {
            return;
        }

        dotVao = glGenVertexArrays();
        dotVbo = glGenBuffers();

        glBindVertexArray(dotVao);
        glBindBuffer(GL_ARRAY_BUFFER, dotVbo);
        glBufferData(GL_ARRAY_BUFFER, 0, GL_DYNAMIC_DRAW);

        // Interleaved position(3) + color(3) for the VERTEX point shader
        int stride = 6 * Float.BYTES;
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, stride, 3 * Float.BYTES);
        glEnableVertexAttribArray(1);

        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);

        dotResourcesInitialized = true;
    }

    /**
     * Build one centroid point per face, colored by selection state
     * (black default, orange selected, white active). Dot index == face index.
     */
    private void rebuildDotVBO() {
        if (faceCount == 0) {
            dotCount = 0;
            return;
        }

        float[] data = new float[faceCount * 6];
        Vector3f centroid = new Vector3f();

        for (int f = 0; f < faceCount; f++) {
            if (!computeFaceCentroid(f, centroid)) {
                centroid.set(0, 0, 0);
            }
            Vector3f color = resolveFaceDotColor(f);
            int off = f * 6;
            data[off]     = centroid.x;
            data[off + 1] = centroid.y;
            data[off + 2] = centroid.z;
            data[off + 3] = color.x;
            data[off + 4] = color.y;
            data[off + 5] = color.z;
        }

        dotData = data;
        dotCount = faceCount;
        dotDirty.clear();

        ensureDotResources();

        glBindBuffer(GL_ARRAY_BUFFER, dotVbo);
        glBufferData(GL_ARRAY_BUFFER, data, GL_DYNAMIC_DRAW);
        EditCostStats.count(EditCostStats.Counter.BUFFER_UPLOADS, 1);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    /** Re-writes one face's dot centroid into the dot mirror (after its vertices moved). */
    private void refreshDotPosition(int faceIndex, Vector3f scratch) {
        if (dotData == null || faceIndex < 0 || faceIndex >= dotCount) return;
        if (!computeFaceCentroid(faceIndex, scratch)) return;
        int off = faceIndex * 6;
        dotData[off] = scratch.x;
        dotData[off + 1] = scratch.y;
        dotData[off + 2] = scratch.z;
        dotDirty.mark(off, off + 3);
    }

    /** Re-writes one face's dot colour into the dot mirror when it changed. */
    private void refreshDotColor(int faceIndex) {
        if (dotData == null || faceIndex < 0 || faceIndex >= dotCount) return;
        Vector3f c = resolveFaceDotColor(faceIndex);
        int off = faceIndex * 6 + 3;
        if (dotData[off] != c.x || dotData[off + 1] != c.y || dotData[off + 2] != c.z) {
            dotData[off] = c.x;
            dotData[off + 1] = c.y;
            dotData[off + 2] = c.z;
            dotDirty.mark(off, off + 3);
        }
    }

    /**
     * Compute a face centroid. Prefers the topology's ordered boundary vertices;
     * falls back to averaging the face's cached triangle vertices.
     */
    private boolean computeFaceCentroid(int faceIndex, Vector3f out) {
        if (genericModelRenderer != null && uniquePositions != null) {
            MeshTopology topology = genericModelRenderer.getTopology();
            if (topology != null && faceIndex < topology.getFaceCount()) {
                MeshFace face = topology.getFace(faceIndex);
                if (face != null) {
                    int[] vertIndices = face.vertexIndices();
                    if (vertIndices.length > 0) {
                        float x = 0, y = 0, z = 0;
                        int used = 0;
                        for (int vi : vertIndices) {
                            if (vi < 0 || vi * 3 + 2 >= uniquePositions.length) continue;
                            x += uniquePositions[vi * 3];
                            y += uniquePositions[vi * 3 + 1];
                            z += uniquePositions[vi * 3 + 2];
                            used++;
                        }
                        if (used > 0) {
                            out.set(x / used, y / used, z / used);
                            return true;
                        }
                    }
                }
            }
        }

        // Fallback: average the face's triangle vertices (duplicates weight slightly)
        if (trianglePositions == null || faceIndex < 0 || faceIndex + 1 >= faceTriStart.length) {
            return false;
        }
        out.set(0, 0, 0);
        int used = 0;
        for (int p = faceTriStart[faceIndex]; p < faceTriStart[faceIndex + 1]; p++) {
            int off = faceTris[p] * 9;
            for (int v = 0; v < 3; v++) {
                out.add(trianglePositions[off + v * 3],
                        trianglePositions[off + v * 3 + 1],
                        trianglePositions[off + v * 3 + 2]);
                used++;
            }
        }
        if (used == 0) {
            return false;
        }
        out.div(used);
        return true;
    }

    private Vector3f resolveFaceDotColor(int faceIndex) {
        if (faceIndex == activeFaceIndex && selectedFaceIndices.contains(faceIndex)) {
            return MeshOverlayTheme.FACE_DOT_ACTIVE;
        }
        if (selectedFaceIndices.contains(faceIndex)) {
            return MeshOverlayTheme.FACE_DOT_SELECT;
        }
        if (socketFaceIds.contains(faceIndex)) {
            return SOCKET_FACE_DOT;
        }
        return MeshOverlayTheme.FACE_DOT;
    }

    // =========================================================================
    // Socket (attachment point) demarcation
    // =========================================================================

    /**
     * Push the current model-space socket positions (from the attachment
     * store). Cheap when unchanged; a change re-derives which faces are
     * "socketed" and recolors the face dots on the next render.
     */
    public void setSocketPositions(List<Vector3f> positions) {
        List<Vector3f> incoming = positions != null ? positions : List.of();
        if (incoming.equals(socketPositions)) {
            return;
        }
        socketPositions.clear();
        for (Vector3f p : incoming) {
            socketPositions.add(new Vector3f(p));
        }
        socketFacesDirty = true;
    }

    /**
     * Re-derive which faces carry a socket: a face is marked when a socket sits
     * at its centroid (where the "Add Socket" face action places them, within
     * {@link #SOCKET_FACE_MATCH_DISTANCE}). Sockets moved off a face stop
     * marking it — the demarcation reflects where sockets actually are, and
     * survives topology edits without persisting fragile face ids.
     *
     * @return true when the set of socketed faces changed
     */
    private boolean refreshSocketFaceIds() {
        Set<Integer> found = new HashSet<>();
        if (!socketPositions.isEmpty() && faceCount > 0) {
            float maxDistSq = SOCKET_FACE_MATCH_DISTANCE * SOCKET_FACE_MATCH_DISTANCE;
            Vector3f centroid = new Vector3f();
            for (int f = 0; f < faceCount; f++) {
                if (!computeFaceCentroid(f, centroid)) {
                    continue;
                }
                for (Vector3f socket : socketPositions) {
                    if (socket.distanceSquared(centroid) <= maxDistSq) {
                        found.add(f);
                        break;
                    }
                }
            }
        }
        if (found.equals(socketFaceIds)) {
            return false;
        }
        Set<Integer> changed = new HashSet<>(socketFaceIds);
        changed.addAll(found);
        socketFaceIds.clear();
        socketFaceIds.addAll(found);
        for (int f : changed) refreshDotColor(f);
        return true;
    }

    // =========================================================================
    // Selection / hover baking (runs when they change, not per frame)
    // =========================================================================

    private static final byte STATE_DEFAULT = 0;
    private static final byte STATE_SELECTED = 1;
    private static final byte STATE_ACTIVE = 2;
    private static final byte STATE_HOVER = 3;

    private byte outlineState(int face) {
        if (face == hoveredFaceIndex) return STATE_HOVER;
        if (selectedFaceIndices.contains(face)) {
            return face == activeFaceIndex ? STATE_ACTIVE : STATE_SELECTED;
        }
        return STATE_DEFAULT;
    }

    /** Rebuilds the selected-face element lists and re-bakes the outline / dot colours of changed faces. */
    private void applySelection() {
        // Reset what was baked last time, then bake the current selection + hover.
        for (int f : bakedFaces) bake(f);
        bake(bakedHover);
        int[] next = new int[selectedFaceIndices.size()];
        int n = 0;
        for (int f : selectedFaceIndices) {
            if (f >= 0 && f < faceCount) {
                next[n++] = f;
                bake(f);
            }
        }
        bakedFaces = Arrays.copyOf(next, n);
        bake(hoveredFaceIndex);
        bakedHover = hoveredFaceIndex;

        // Element lists: selected faces' outline vertices (all selected), fills (selected minus active)
        if (lineResourcesInitialized && faceLineOffsets != null) {
            int total = 0;
            for (int f : bakedFaces) total += faceLineCounts[f];
            int[] elements = new int[total];
            int k = 0;
            for (int f : bakedFaces) {
                for (int v = faceLineOffsets[f], e = v + faceLineCounts[f]; v < e; v++) elements[k++] = v;
            }
            uploadElements(lineVao, lineEbo, elements);
            lineElementCount = total;
        }
        if (fillResourcesInitialized && totalFillVertices > 0) {
            boolean hasActive = activeFaceIndex >= 0 && activeFaceIndex < faceCount
                    && selectedFaceIndices.contains(activeFaceIndex);
            int total = 0;
            for (int f : bakedFaces) {
                if (!(hasActive && f == activeFaceIndex)) total += faceFillCount(f);
            }
            int[] elements = new int[total];
            int k = 0;
            for (int f : bakedFaces) {
                if (hasActive && f == activeFaceIndex) continue;
                for (int v = faceFillOffset(f), e = v + faceFillCount(f); v < e; v++) elements[k++] = v;
            }
            uploadElements(fillVao, fillEbo, elements);
            fillElementCount = total;
        }
    }

    /** Hover-only change: re-bake the previously and the newly hovered face. */
    private void applyHover() {
        bake(bakedHover);
        bake(hoveredFaceIndex);
        bakedHover = hoveredFaceIndex;
    }

    /** Writes a face's outline colours (line mirror) and dot colour (dot mirror) for its current state. */
    private void bake(int face) {
        if (face < 0 || face >= faceCount) return;
        byte state = outlineState(face);
        if (lineData != null && faceLineOffsets != null && face < bakedState.length && bakedState[face] != state) {
            bakedState[face] = state;
            Vector3f color = switch (state) {
                case STATE_HOVER -> HOVER_EDGE_COLOR;
                case STATE_ACTIVE -> ACTIVE_EDGE_COLOR;
                case STATE_SELECTED -> SELECTED_EDGE_COLOR;
                default -> DEFAULT_EDGE_COLOR;
            };
            int start = faceLineOffsets[face];
            int end = start + faceLineCounts[face];
            for (int v = start; v < end; v++) writeColor(lineData, v, color);
            lineDirty.mark(start * LINE_FLOATS_PER_VERTEX, end * LINE_FLOATS_PER_VERTEX);
        }
        refreshDotColor(face);
    }

    private static void uploadElements(int vao, int ebo, int[] elements) {
        glBindVertexArray(vao);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, elements, GL_DYNAMIC_DRAW);
        EditCostStats.count(EditCostStats.Counter.BUFFER_UPLOADS, 1);
        glBindVertexArray(0);
    }

    // =========================================================================
    // Rendering
    // =========================================================================

    /**
     * Render Blender-style face highlights: translucent fills for selected/active/hovered
     * faces, crisp boundary outlines on top, and face-centroid dots in FACE edit mode.
     *
     * @param lineShader  BASIC shader for boundary outlines
     * @param fillShader  FACE shader (uColor + uAlpha) for translucent fills
     * @param pointShader VERTEX point shader for face dots
     */
    public void render(ShaderProgram lineShader, ShaderProgram fillShader, ShaderProgram pointShader,
                       ViewerRenderContext context, Matrix4f modelMatrix) {
        if (!initialized || !enabled || faceCount == 0) {
            return;
        }

        boolean faceMode = EditModeManager.getInstance().isFaceEditingAllowed();
        boolean hasHover = hoveredFaceIndex >= 0 && hoveredFaceIndex < faceCount;
        boolean hasSelection = !selectedFaceIndices.isEmpty();
        if (!faceMode && !hasHover && !hasSelection) {
            return;
        }

        try {
            Matrix4f viewMatrix = context.getCamera().getViewMatrix();
            Matrix4f projectionMatrix = context.getCamera().getProjectionMatrix();
            Matrix4f mvpMatrix = new Matrix4f(projectionMatrix).mul(viewMatrix).mul(modelMatrix);

            if (fillDataDirty) {
                rebuildFillVBO();
                fillDataDirty = false;
                selectionDirty = true;
            }
            if (dotDataDirty && faceMode) {
                rebuildDotVBO();
                dotDataDirty = false;
            }
            // Socket-face matching feeds dot colors (re-coloured where the socketed set changed)
            if (socketFacesDirty) {
                refreshSocketFaceIds();
                socketFacesDirty = false;
            }
            if (selectionDirty) {
                applySelection();
                selectionDirty = false;
                hoverDirty = false;
            } else if (hoverDirty) {
                applyHover();
                hoverDirty = false;
            }
            lineDirty.flush(lineVbo, lineData);
            fillDirty.flush(fillVbo, fillData);
            if (!dotDataDirty) {
                dotDirty.flush(dotVbo, dotData);
            }

            int prevDepthFunc = glGetInteger(GL_DEPTH_FUNC);
            glDepthFunc(GL_LEQUAL);
            glDepthRange(0.0, OVERLAY_DEPTH_RANGE_FAR); // subtle bias to resolve coplanar z-fighting
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

            // ── Layer 1: translucent fills (depth writes off) ──
            boolean hasActive = activeFaceIndex >= 0 && activeFaceIndex < faceCount
                    && selectedFaceIndices.contains(activeFaceIndex);
            boolean hasSocketFaces = faceMode && !socketFaceIds.isEmpty();
            if ((hasSelection || hasHover || hasSocketFaces)
                    && totalFillVertices > 0 && fillResourcesInitialized) {
                fillShader.use();
                fillShader.setMat4("uMVPMatrix", mvpMatrix);
                glDepthMask(false);
                glBindVertexArray(fillVao);

                // Socketed faces (few): cyan demarcation, drawn first so selection/hover
                // tints paint over it (a selected socketed face still reads selected).
                if (hasSocketFaces) {
                    fillShader.setVec3("uColor", SOCKET_FACE_FILL);
                    fillShader.setFloat("uAlpha", SOCKET_FACE_ALPHA);
                    for (int faceIdx : socketFaceIds) {
                        if (faceIdx >= 0 && faceIdx < faceCount
                                && !selectedFaceIndices.contains(faceIdx)
                                && faceIdx != hoveredFaceIndex) {
                            drawFaceFill(faceIdx);
                        }
                    }
                }

                if (hasSelection) {
                    // Every selected face but the active one: one element draw
                    if (fillElementCount > 0) {
                        fillShader.setVec3("uColor", MeshOverlayTheme.FACE_SELECT_FILL);
                        fillShader.setFloat("uAlpha", MeshOverlayTheme.FACE_SELECT_ALPHA);
                        glDrawElements(GL_TRIANGLES, fillElementCount, GL_UNSIGNED_INT, 0L);
                        EditCostStats.count(EditCostStats.Counter.DRAW_CALLS, 1);
                    }

                    // Active face gets Blender's white tint instead of the orange fill
                    if (hasActive) {
                        fillShader.setVec3("uColor", MeshOverlayTheme.FACE_ACTIVE_FILL);
                        fillShader.setFloat("uAlpha", MeshOverlayTheme.FACE_ACTIVE_ALPHA);
                        drawFaceFill(activeFaceIndex);
                    }
                }

                // Hover pre-highlight on unselected faces only
                if (hasHover && !selectedFaceIndices.contains(hoveredFaceIndex)) {
                    fillShader.setVec3("uColor", MeshOverlayTheme.FACE_HOVER_FILL);
                    fillShader.setFloat("uAlpha", MeshOverlayTheme.FACE_HOVER_ALPHA);
                    drawFaceFill(hoveredFaceIndex);
                }

                glBindVertexArray(0);
                glDepthMask(true);
            }

            // ── Layer 2: boundary outlines (colours baked per face) ──
            if ((hasSelection || hasHover) && totalLineVertices > 0 && lineResourcesInitialized) {
                lineShader.use();
                lineShader.setMat4("uMVPMatrix", mvpMatrix);
                lineShader.setFloat("uIntensity", 1.0f);
                glBindVertexArray(lineVao);

                if (hasSelection && lineElementCount > 0) {
                    glLineWidth(SELECTED_LINE_WIDTH);
                    glDrawElements(GL_LINES, lineElementCount, GL_UNSIGNED_INT, 0L);
                    EditCostStats.count(EditCostStats.Counter.DRAW_CALLS, 1);
                }

                // Hovered face on top
                if (hasHover && faceLineOffsets != null && hoveredFaceIndex < faceLineOffsets.length
                        && faceLineCounts[hoveredFaceIndex] > 0) {
                    glLineWidth(HOVER_LINE_WIDTH);
                    glDrawArrays(GL_LINES, faceLineOffsets[hoveredFaceIndex], faceLineCounts[hoveredFaceIndex]);
                    EditCostStats.count(EditCostStats.Counter.DRAW_CALLS, 1);
                }

                glBindVertexArray(0);
            }

            // ── Layer 3: face dots (FACE edit mode only, like Blender facedots) ──
            if (faceMode && dotCount > 0 && dotResourcesInitialized && pointShader != null) {
                pointShader.use();
                pointShader.setMat4("uMVPMatrix", mvpMatrix);
                pointShader.setFloat("uIntensity", 1.0f);
                pointShader.setFloat("uPointSize", MeshOverlayTheme.FACE_DOT_SIZE);
                glEnable(GL_VERTEX_PROGRAM_POINT_SIZE);
                glBindVertexArray(dotVao);

                glDrawArrays(GL_POINTS, 0, dotCount);
                EditCostStats.count(EditCostStats.Counter.DRAW_CALLS, 1);

                // Hovered face's dot drawn slightly larger for affordance
                if (hasHover) {
                    pointShader.setFloat("uPointSize", MeshOverlayTheme.FACE_DOT_HOVER_SIZE);
                    glDrawArrays(GL_POINTS, hoveredFaceIndex, 1);
                    EditCostStats.count(EditCostStats.Counter.DRAW_CALLS, 1);
                }

                glBindVertexArray(0);
                glDisable(GL_VERTEX_PROGRAM_POINT_SIZE);
            }

            glDepthRange(0.0, 1.0);
            glDepthFunc(prevDepthFunc);

        } catch (Exception e) {
            logger.error("Error rendering face highlights", e);
        } finally {
            glUseProgram(0);
        }
    }

    // =========================================================================
    // Hover detection
    // =========================================================================

    public void handleMouseMove(float mouseX, float mouseY,
                               Matrix4f viewMatrix, Matrix4f projectionMatrix,
                               Matrix4f modelMatrix,
                               int viewportWidth, int viewportHeight) {
        if (!initialized || !enabled || faceCount == 0) {
            hoverGate.invalidate();
            return;
        }
        // Same mouse, camera, model and geometry as the last pick: the answer stands (#175)
        if (!hoverGate.shouldPick(mouseX, mouseY, viewMatrix, projectionMatrix, modelMatrix,
                viewportWidth, viewportHeight)) {
            return;
        }
        long editCostSpan = EditCostStats.begin();
        try {
            int newHoveredFace = -1;
            if (trianglePositions != null && triangleCount > 0) {
                int hitTriangle = FaceHoverDetector.detectHoveredTriangle(
                    mouseX, mouseY,
                    viewportWidth, viewportHeight,
                    viewMatrix, projectionMatrix, modelMatrix,
                    trianglePositions, triangleCount
                );
                if (hitTriangle >= 0 && hitTriangle < triFace.length) {
                    newHoveredFace = triFace[hitTriangle];
                }
            }
            setHovered(newHoveredFace);
        } finally {
            EditCostStats.end(EditCostStats.Span.HOVER, editCostSpan);
        }
    }

    private void setHovered(int face) {
        if (face != hoveredFaceIndex) {
            hoveredFaceIndex = face;
            hoverDirty = true;
            logger.debug("Face hover changed to {} (total faces: {})", hoveredFaceIndex, faceCount);
        }
    }

    public void clearHover() {
        hoverGate.invalidate();
        if (hoveredFaceIndex != -1) {
            logger.debug("Clearing face hover (was face {})", hoveredFaceIndex);
            setHovered(-1);
        }
    }

    // =========================================================================
    // Face vertex access (used by face translation, texture editor, etc.)
    // =========================================================================

    /**
     * Get ordered boundary vertices for a face from topology.
     */
    public Vector3f[] getFaceVertices(int faceIndex) {
        if (faceIndex < 0 || faceIndex >= faceCount) {
            return null;
        }
        if (genericModelRenderer != null) {
            var topology = genericModelRenderer.getTopology();
            if (topology != null && faceIndex < topology.getFaceCount()) {
                var face = topology.getFace(faceIndex);
                if (face != null) {
                    int[] vertexIndices = face.vertexIndices();
                    Vector3f[] vertices = new Vector3f[vertexIndices.length];
                    for (int i = 0; i < vertexIndices.length; i++) {
                        Vector3f pos = genericModelRenderer.getUniqueVertexPosition(vertexIndices[i]);
                        if (pos == null) return null;
                        vertices[i] = new Vector3f(pos);
                    }
                    return vertices;
                }
            }
        }
        return null;
    }

    public int[] getTriangleVertexIndicesForFace(int originalFaceId) {
        if (genericModelRenderer == null || originalFaceId < 0 || originalFaceId + 1 >= faceTriStart.length) {
            return null;
        }
        int start = faceTriStart[originalFaceId];
        int end = faceTriStart[originalFaceId + 1];
        if (start == end) {
            return null;
        }

        int[] triangleIndices = genericModelRenderer.triangleIndicesView();
        if (triangleIndices == null) {
            return null;
        }

        Set<Integer> uniqueVertexIndices = new LinkedHashSet<>();
        for (int p = start; p < end; p++) {
            int baseIndex = faceTris[p] * 3;
            if (baseIndex + 2 < triangleIndices.length) {
                uniqueVertexIndices.add(triangleIndices[baseIndex]);
                uniqueVertexIndices.add(triangleIndices[baseIndex + 1]);
                uniqueVertexIndices.add(triangleIndices[baseIndex + 2]);
            }
        }

        return uniqueVertexIndices.stream().mapToInt(Integer::intValue).toArray();
    }

    public Vector3f[] getTriangleVertexPositionsForFace(int originalFaceId) {
        int[] indices = getTriangleVertexIndicesForFace(originalFaceId);
        if (indices == null || genericModelRenderer == null) {
            return null;
        }

        Vector3f[] positions = new Vector3f[indices.length];
        for (int i = 0; i < indices.length; i++) {
            positions[i] = genericModelRenderer.getVertexPosition(indices[i]);
            if (positions[i] == null) return null;
        }
        return positions;
    }

    // =========================================================================
    // Cleanup
    // =========================================================================

    public void cleanup() {
        if (genericModelRenderer != null) {
            genericModelRenderer.removeMeshChangeListener(this);
            genericModelRenderer = null;
        }

        if (lineVao != 0) {
            glDeleteVertexArrays(lineVao);
            lineVao = 0;
        }
        if (lineVbo != 0) {
            glDeleteBuffers(lineVbo);
            lineVbo = 0;
        }
        if (lineEbo != 0) {
            glDeleteBuffers(lineEbo);
            lineEbo = 0;
        }
        if (fillVao != 0) {
            glDeleteVertexArrays(fillVao);
            fillVao = 0;
        }
        if (fillVbo != 0) {
            glDeleteBuffers(fillVbo);
            fillVbo = 0;
        }
        if (fillEbo != 0) {
            glDeleteBuffers(fillEbo);
            fillEbo = 0;
        }
        if (dotVao != 0) {
            glDeleteVertexArrays(dotVao);
            dotVao = 0;
        }
        if (dotVbo != 0) {
            glDeleteBuffers(dotVbo);
            dotVbo = 0;
        }

        lineResourcesInitialized = false;
        fillResourcesInitialized = false;
        dotResourcesInitialized = false;
        fillDataDirty = true;
        dotDataDirty = true;
        selectionDirty = true;
        hoverDirty = false;
        lineData = null;
        fillData = null;
        dotData = null;
        lineDirty.clear();
        fillDirty.clear();
        dotDirty.clear();
        bakedFaces = new int[0];
        bakedHover = -1;
        bakedState = new byte[0];
        lineElementCount = 0;
        fillElementCount = 0;
        socketFaceIds.clear();
        socketFacesDirty = true;
        totalFillVertices = 0;
        dotCount = 0;
        activeFaceIndex = -1;
        faceCount = 0;
        totalLineVertices = 0;
        hoverGate.invalidate();
        initialized = false;
        logger.debug("FaceRenderer cleaned up");
    }

    // =========================================================================
    // MeshChangeListener
    // =========================================================================

    @Override
    public void onVertexPositionChanged(int uniqueIndex, Vector3f newPosition, int[] affectedMeshIndices) {
        onVertexPositionsChanged(new int[] {uniqueIndex},
                new float[] {newPosition.x, newPosition.y, newPosition.z},
                new int[][] {affectedMeshIndices != null ? affectedMeshIndices : new int[0]});
    }

    /**
     * Moved vertices (#175): each vertex's corners update the hover triangles and the fill mirror through the
     * corner → triangle lookup, its outline slots through the vertex → line lookup, and the dots of the faces
     * around it are re-centred. O(corners moved), no scan of the mesh; uploads happen once in {@link #render}.
     */
    @Override
    public void onVertexPositionsChanged(int[] uniqueIndices, float[] positions, int[][] affectedMeshIndices) {
        if (!initialized || trianglePositions == null) return;
        long editCostSpan = EditCostStats.begin();
        try {
            hoverGate.invalidate();
            if (++stamp == Integer.MAX_VALUE) {
                Arrays.fill(faceStamp, 0);
                stamp = 1;
            }
            int[] touched = new int[16];
            int touchedCount = 0;
            for (int i = 0; i < uniqueIndices.length; i++) {
                float x = positions[i * 3], y = positions[i * 3 + 1], z = positions[i * 3 + 2];
                int u = uniqueIndices[i];
                if (uniquePositions != null && u >= 0 && u * 3 + 2 < uniquePositions.length) {
                    uniquePositions[u * 3] = x;
                    uniquePositions[u * 3 + 1] = y;
                    uniquePositions[u * 3 + 2] = z;
                }
                // Outline slots of this vertex
                if (lineData != null && vertexLineStart != null && u >= 0 && u + 1 < vertexLineStart.length) {
                    for (int r = vertexLineStart[u]; r < vertexLineStart[u + 1]; r++) {
                        int o = vertexLineRef[r] * LINE_FLOATS_PER_VERTEX;
                        lineData[o] = x;
                        lineData[o + 1] = y;
                        lineData[o + 2] = z;
                        lineDirty.mark(o, o + 3);
                    }
                }
                // Triangle corners (hover + fill) and the faces they belong to
                int[] corners = affectedMeshIndices[i];
                if (corners == null) continue;
                for (int c : corners) {
                    if (c < 0 || c + 1 >= cornerTriStart.length) continue;
                    for (int r = cornerTriStart[c]; r < cornerTriStart[c + 1]; r++) {
                        int ref = cornerTriRef[r];
                        int t = ref / 3;
                        int k = ref % 3;
                        int o = t * 9 + k * 3;
                        trianglePositions[o] = x;
                        trianglePositions[o + 1] = y;
                        trianglePositions[o + 2] = z;
                        int slot = triSlot[t];
                        if (slot >= 0 && fillData != null) {
                            int fo = (slot * 3 + k) * 3;
                            fillData[fo] = x;
                            fillData[fo + 1] = y;
                            fillData[fo + 2] = z;
                            fillDirty.mark(fo, fo + 3);
                        }
                        int f = triFace[t];
                        if (f >= 0 && f < faceStamp.length && faceStamp[f] != stamp) {
                            faceStamp[f] = stamp;
                            if (touchedCount == touched.length) touched = Arrays.copyOf(touched, touchedCount * 2);
                            touched[touchedCount++] = f;
                        }
                    }
                }
            }
            if (!dotDataDirty) {
                Vector3f scratch = new Vector3f();
                for (int i = 0; i < touchedCount; i++) refreshDotPosition(touched[i], scratch);
            }
            if (!socketPositions.isEmpty()) {
                socketFacesDirty = true;
            }
        } finally {
            EditCostStats.end(EditCostStats.Span.OVERLAY_MOVE, editCostSpan);
        }
    }

    @Override
    public void onGeometryRebuilt() {
        logger.debug("FaceRenderer received geometry rebuild notification");
        rebuildFromEditableModel();
    }

    // =========================================================================
    // Getters / setters
    // =========================================================================

    public boolean isEnabled() { return enabled; }

    public void setEnabled(boolean enabled) {
        if (enabled != this.enabled) hoverGate.invalidate();
        this.enabled = enabled;
    }

    public boolean isInitialized() { return initialized; }
    public int getHoveredFaceIndex() { return hoveredFaceIndex; }
    public int getFaceCount() { return faceCount; }

    public int getSelectedFaceIndex() {
        return selectedFaceIndices.isEmpty() ? -1 : selectedFaceIndices.iterator().next();
    }

    public void setSelectedFace(int faceIndex) {
        selectedFaceIndices.clear();
        activeFaceIndex = -1;
        if (faceIndex >= 0 && faceIndex < faceCount) {
            selectedFaceIndices.add(faceIndex);
            activeFaceIndex = faceIndex;
        }
        selectionDirty = true;
    }

    public void updateSelectionSet(Set<Integer> indices) {
        selectedFaceIndices.clear();
        activeFaceIndex = -1;
        if (indices != null) {
            // Insertion order from the selection state is preserved (LinkedHashSet),
            // so the last valid index is the active element, like Blender
            for (Integer index : indices) {
                if (index >= 0 && index < faceCount) {
                    selectedFaceIndices.add(index);
                    activeFaceIndex = index;
                }
            }
        }
        selectionDirty = true;
    }

    public Set<Integer> getSelectedFaceIndices() {
        return new HashSet<>(selectedFaceIndices);
    }

    public boolean isFaceSelected(int index) {
        return selectedFaceIndices.contains(index);
    }

    public void clearSelection() {
        selectedFaceIndices.clear();
        activeFaceIndex = -1;
        selectionDirty = true;
    }

    // CPU mirrors, for tests (same package): the listener-updated copies must equal a fresh rebuild's.
    float[] trianglePositionsView() { return trianglePositions; }
    float[] fillDataView() { return fillData; }
    float[] lineDataView() { return lineData; }
    float[] dotDataView() { return dotData; }

    public void setEditingFaceIndex(int faceIndex) {
        this.editingFaceIndex = faceIndex;
    }

    public void setEditableModel(EditableModel renderer) {
        if (this.genericModelRenderer != null) {
            this.genericModelRenderer.removeMeshChangeListener(this);
        }

        this.genericModelRenderer = renderer;

        if (renderer != null) {
            renderer.addMeshChangeListener(this);
            rebuildFromEditableModel();
        }

        logger.debug("FaceRenderer {} EditableModel",
            renderer != null ? "connected to" : "disconnected from");
    }
}
