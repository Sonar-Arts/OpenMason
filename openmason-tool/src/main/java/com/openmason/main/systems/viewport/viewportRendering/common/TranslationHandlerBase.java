package com.openmason.main.systems.viewport.viewportRendering.common;

import com.openmason.engine.modeling.EditableModel;
import com.openmason.engine.modeling.ModelingConstants;
import com.openmason.main.systems.rendering.model.editing.subrenders.face.FaceRenderer;
import com.openmason.main.systems.viewport.ViewportUIState;
import com.openmason.engine.rendering.viewer.math.CoordinateSystem;
import com.openmason.engine.rendering.viewer.math.RaycastUtil;
import com.openmason.engine.rendering.viewer.transform.TransformState;
import com.openmason.engine.rendering.viewer.math.SnappingUtil;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Abstract base class for translation handlers.
 * Provides common functionality for plane-constrained dragging.
 * Follows DRY principle by centralizing shared logic between
 * VertexTranslationHandler and EdgeTranslationHandler.
 *
 * Uses Template Method pattern for drag behavior.
 */
public abstract class TranslationHandlerBase implements ITranslationHandler {

    private static final Logger logger = LoggerFactory.getLogger(TranslationHandlerBase.class);

    // Dependencies
    protected ViewportUIState viewportState;
    protected final TransformState transformState;

    // Cached camera matrices
    protected Matrix4f viewMatrix = new Matrix4f();
    protected Matrix4f projectionMatrix = new Matrix4f();
    protected int viewportWidth = 1;
    protected int viewportHeight = 1;

    // Drag state
    protected boolean isDragging = false;
    protected boolean hasMovedDuringDrag = false;
    protected float dragStartMouseX = 0.0f;
    protected float dragStartMouseY = 0.0f;
    protected Vector3f initialDragHitPoint = null; // Initial plane hit point for delta calculation

    /**
     * Creates a new TranslationHandlerBase.
     *
     * @param viewportState The viewport state for grid snapping settings
     * @param transformState The transform state for model space conversions
     */
    protected TranslationHandlerBase(ViewportUIState viewportState, TransformState transformState) {
        if (transformState == null) {
            throw new IllegalArgumentException("TransformState cannot be null");
        }

        this.viewportState = viewportState;
        this.transformState = transformState;
    }

    /**
     * Update viewport state for snapping settings.
     */
    public void updateViewportState(ViewportUIState viewportState) {
        this.viewportState = viewportState;
    }

    @Override
    public void updateCamera(Matrix4f view, Matrix4f projection, int width, int height) {
        if (view == null || projection == null) {
            throw new IllegalArgumentException("Matrices cannot be null");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Viewport dimensions must be positive");
        }

        this.viewMatrix.set(view);
        this.projectionMatrix.set(projection);
        this.viewportWidth = width;
        this.viewportHeight = height;
    }

    @Override
    public boolean isDragging() {
        return isDragging;
    }

    /**
     * Selects the optimal working plane based on camera direction.
     * Uses Blender-style plane selection: choose plane perpendicular to dominant camera axis.
     *
     * @param cameraDirection The camera's forward direction vector
     * @return Normal vector of the optimal working plane
     */
    protected Vector3f selectOptimalPlane(Vector3f cameraDirection) {
        // Find dominant camera axis by checking absolute values
        Vector3f absDir = new Vector3f(
                Math.abs(cameraDirection.x),
                Math.abs(cameraDirection.y),
                Math.abs(cameraDirection.z)
        );

        // Choose plane perpendicular to dominant axis
        if (absDir.x > absDir.y && absDir.x > absDir.z) {
            return new Vector3f(1, 0, 0); // YZ plane
        } else if (absDir.y > absDir.z) {
            return new Vector3f(0, 1, 0); // XZ plane
        } else {
            return new Vector3f(0, 0, 1); // XY plane
        }
    }

    /**
     * Gets the camera's forward direction vector from the view matrix.
     *
     * @return Camera forward direction, or null if view matrix is not set
     */
    protected Vector3f getCameraDirection() {
        if (viewMatrix == null) {
            return null;
        }

        // Extract forward vector from view matrix (negative Z axis in view space)
        Matrix4f invView = new Matrix4f(viewMatrix).invert();
        return new Vector3f(-invView.m20(), -invView.m21(), -invView.m22()).normalize();
    }

    /**
     * Applies grid snapping to a position.
     *
     * @param position The position to snap
     * @return Snapped position
     */
    protected Vector3f applyGridSnapping(Vector3f position) {
        if (viewportState == null) {
            return position;
        }

        float increment = viewportState.getGridSnappingIncrement().get();

        return new Vector3f(
                SnappingUtil.snapToGrid(position.x, increment),
                SnappingUtil.snapToGrid(position.y, increment),
                SnappingUtil.snapToGrid(position.z, increment)
        );
    }

    /**
     * Applies grid snapping to a delta vector.
     *
     * @param delta The delta to snap
     * @return Snapped delta
     */
    protected Vector3f applyGridSnappingToDelta(Vector3f delta) {
        if (viewportState == null) {
            return delta;
        }

        float increment = viewportState.getGridSnappingIncrement().get();

        return new Vector3f(
                SnappingUtil.snapToGrid(delta.x, increment),
                SnappingUtil.snapToGrid(delta.y, increment),
                SnappingUtil.snapToGrid(delta.z, increment)
        );
    }

    /**
     * Creates a ray from mouse position using current camera matrices.
     *
     * @param mouseX Mouse X position
     * @param mouseY Mouse Y position
     * @return Ray from mouse position into world space
     */
    protected CoordinateSystem.Ray createMouseRay(float mouseX, float mouseY) {
        return CoordinateSystem.createWorldRayFromScreen(
                mouseX, mouseY,
                viewportWidth, viewportHeight,
                viewMatrix, projectionMatrix
        );
    }

    /**
     * Intersects a ray with a plane and returns the intersection point.
     *
     * @param ray Ray to intersect
     * @param planePoint Point on the plane
     * @param planeNormal Normal vector of the plane
     * @param fallbackPosition Position to return if no valid intersection
     * @return Intersection point, or fallbackPosition if no valid intersection
     */
    protected Vector3f intersectRayPlane(CoordinateSystem.Ray ray, Vector3f planePoint,
                                         Vector3f planeNormal, Vector3f fallbackPosition) {
        float t = RaycastUtil.intersectRayPlane(ray, planePoint, planeNormal);

        if (Float.isInfinite(t) || t < 0) {
            return fallbackPosition;
        }

        Vector3f intersection = RaycastUtil.getPointOnRay(ray, t);

        // Fallback check: if plane is nearly parallel to view direction
        Vector3f cameraDirection = getCameraDirection();
        if (cameraDirection != null) {
            float dotProduct = Math.abs(planeNormal.dot(cameraDirection));
            if (dotProduct < 0.1f) {
                // Plane is nearly parallel - use XY plane as fallback
                logger.trace("Plane parallel to camera (dot={}), using XY fallback",
                        String.format("%.3f", dotProduct));
                planeNormal = new Vector3f(0, 0, 1);
                t = RaycastUtil.intersectRayPlane(ray, planePoint, planeNormal);
                if (!Float.isInfinite(t) && t >= 0) {
                    intersection = RaycastUtil.getPointOnRay(ray, t);
                }
            }
        }

        return intersection;
    }

    /**
     * Converts a world-space position to model-space.
     *
     * @param worldPos Position in world space
     * @return Position in model space
     */
    protected Vector3f worldToModelSpace(Vector3f worldPos) {
        Matrix4f modelMatrix = transformState.getTransformMatrix();
        Matrix4f inverseModelMatrix = new Matrix4f(modelMatrix).invert();

        Vector4f worldPos4 = new Vector4f(worldPos.x, worldPos.y, worldPos.z, 1.0f);
        Vector4f modelPos4 = inverseModelMatrix.transform(worldPos4);

        return new Vector3f(modelPos4.x, modelPos4.y, modelPos4.z);
    }

    /**
     * Converts a world-space delta to model-space delta.
     * Only transforms the direction, not the position.
     *
     * @param worldDelta Delta in world space
     * @return Delta in model space
     */
    protected Vector3f worldToModelSpaceDelta(Vector3f worldDelta) {
        Matrix4f modelMatrix = transformState.getTransformMatrix();
        Matrix4f inverseModelMatrix = new Matrix4f(modelMatrix).invert();

        // Transform delta as a direction (w=0)
        Vector4f worldDelta4 = new Vector4f(worldDelta.x, worldDelta.y, worldDelta.z, 0.0f);
        Vector4f modelDelta4 = inverseModelMatrix.transform(worldDelta4);

        return new Vector3f(modelDelta4.x, modelDelta4.y, modelDelta4.z);
    }

    /**
     * Validates camera matrices before use.
     *
     * @return true if matrices are valid, false otherwise
     */
    protected boolean validateCameraMatrices() {
        if (viewMatrix == null || projectionMatrix == null) {
            logger.warn("Camera matrices not set");
            return false;
        }
        if (viewportWidth <= 0 || viewportHeight <= 0) {
            logger.warn("Invalid viewport dimensions: {}x{}", viewportWidth, viewportHeight);
            return false;
        }
        return true;
    }

    /**
     * Calculates and stores the initial hit point on the drag plane.
     * Call this in handleMousePress after setting up the plane.
     * This enables delta-based movement (no jump to mouse position).
     *
     * @param mouseX Mouse X position
     * @param mouseY Mouse Y position
     * @param planePoint Point on the drag plane
     * @param planeNormal Normal of the drag plane
     */
    protected void calculateInitialDragHitPoint(float mouseX, float mouseY,
                                                 Vector3f planePoint, Vector3f planeNormal) {
        CoordinateSystem.Ray ray = createMouseRay(mouseX, mouseY);
        initialDragHitPoint = intersectRayPlane(ray, planePoint, planeNormal, new Vector3f(planePoint));
        logger.trace("Initial drag hit point: ({}, {}, {})",
                String.format("%.2f", initialDragHitPoint.x),
                String.format("%.2f", initialDragHitPoint.y),
                String.format("%.2f", initialDragHitPoint.z));
    }

    /**
     * Calculates the movement delta from initial hit point to current mouse position.
     * Returns the world-space delta that should be applied to original positions.
     *
     * @param mouseX Current mouse X position
     * @param mouseY Current mouse Y position
     * @param planePoint Point on the drag plane
     * @param planeNormal Normal of the drag plane
     * @return Delta from initial hit point to current hit point, or null if invalid
     */
    protected Vector3f calculateDragDelta(float mouseX, float mouseY,
                                          Vector3f planePoint, Vector3f planeNormal) {
        if (initialDragHitPoint == null) {
            logger.warn("Initial drag hit point not set");
            return null;
        }

        CoordinateSystem.Ray ray = createMouseRay(mouseX, mouseY);
        Vector3f currentHitPoint = intersectRayPlane(ray, planePoint, planeNormal, initialDragHitPoint);

        // Return delta from initial to current
        return new Vector3f(currentHitPoint).sub(initialDragHitPoint);
    }

    /**
     * Clears the initial drag hit point.
     * Call this when drag ends or is cancelled.
     */
    protected void clearInitialDragHitPoint() {
        initialDragHitPoint = null;
    }

    // Last model-space delta pushed to the model in this drag (null = nothing pushed yet).
    private Vector3f lastAppliedDelta;

    /**
     * Per-frame gate (#175): true when {@code modelDelta} differs from the delta last pushed in this drag (and
     * records it). The handlers call their mouse-move work every frame; with the mouse still the delta repeats
     * and there is nothing to do.
     */
    protected boolean deltaChanged(Vector3f modelDelta) {
        if (lastAppliedDelta != null && lastAppliedDelta.equals(modelDelta)) {
            return false;
        }
        lastAppliedDelta = new Vector3f(modelDelta);
        return true;
    }

    /** Forget the last pushed delta (drag start / end / cancel). */
    protected void resetDeltaGate() {
        lastAppliedDelta = null;
    }

    /**
     * Push a set of moved unique-vertex positions into the EditableModel (the single source of truth) through
     * the positions-only path (#175): one {@link EditableModel#moveVertices} call - the model writes each vertex
     * and its corners once and tells the vertex / edge / face overlays once (batched listener). Topology, UV
     * re-projection and the index re-sort wait for {@link #commitVertexPositions} at drag release.
     *
     * @param gmr          The model renderer (single source of truth)
     * @param faceRenderer Unused since #175 (the face overlay follows through its mesh listener); kept for callers
     * @param newPositions Moved unique vertex id → new model-space position
     */
    protected static void pushVertexPositionsToModel(EditableModel gmr,
                                                     FaceRenderer faceRenderer,
                                                     Map<Integer, Vector3f> newPositions) {
        if (gmr == null || newPositions == null || newPositions.isEmpty()) {
            return;
        }
        int[] ids = new int[newPositions.size()];
        float[] positions = new float[ids.length * 3];
        int i = 0;
        for (Map.Entry<Integer, Vector3f> entry : newPositions.entrySet()) {
            Vector3f pos = entry.getValue();
            ids[i] = entry.getKey();
            positions[i * 3] = pos.x;
            positions[i * 3 + 1] = pos.y;
            positions[i * 3 + 2] = pos.z;
            i++;
        }
        gmr.moveVertices(ids, positions);
    }

    /** Ends a positions-only drag: one model position refresh (UVs, triangulation, topology, overlays). */
    protected static void commitVertexPositions(EditableModel gmr) {
        if (gmr != null) {
            gmr.commitVertexPositions();
        }
    }

    /**
     * Back-propagate the current positions of the given unique vertices into the part geometry (so the edit
     * survives a later part rebuild) in one batch: every render corner of every vertex, one pass per part.
     */
    protected static void syncPartVertices(EditableModel gmr, Iterable<Integer> uniqueVertexIds) {
        if (gmr == null || gmr.getPartManager() == null) {
            return;
        }
        int[] corners = new int[16];
        int n = 0;
        for (int ui : uniqueVertexIds) {
            int[] mis = gmr.getMeshIndicesForUniqueVertex(ui);
            if (mis == null) continue;
            for (int mi : mis) {
                if (n == corners.length) corners = java.util.Arrays.copyOf(corners, n * 2);
                corners[n++] = mi;
            }
        }
        syncPartCorners(gmr, java.util.Arrays.copyOf(corners, n));
    }

    /** Back-propagate the current positions of the given render corners into the part geometry (one batch). */
    protected static void syncPartCorners(EditableModel gmr, int[] corners) {
        if (gmr == null || gmr.getPartManager() == null || corners == null || corners.length == 0) {
            return;
        }
        float[] all = gmr.meshVertexPositionsView();
        if (all == null) {
            return;
        }
        int[] valid = new int[corners.length];
        float[] positions = new float[corners.length * 3];
        int n = 0;
        for (int mi : corners) {
            if (mi < 0 || mi * 3 + 2 >= all.length) continue;
            valid[n] = mi;
            System.arraycopy(all, mi * 3, positions, n * 3, 3);
            n++;
        }
        gmr.getPartManager().syncPartVerticesFromWorldPos(
                java.util.Arrays.copyOf(valid, n), java.util.Arrays.copyOf(positions, n * 3));
    }

    /**
     * Commit a drag-vertex-onto-vertex merge: every dragged vertex resting
     * within {@link ModelingConstants#POSITION_MATCH_EPSILON} of a stationary
     * vertex is TOPOLOGICALLY merged into that vertex via
     * {@link EditableModel#mergeVertices} — faces genuinely share the
     * kept vertex afterward. Positions are untouched (the drag already moved
     * them together); orphan vertices (no render corners) are never targets.
     *
     * <p>Call after the final positions are committed to the model. A merge is
     * a structural change: the model fires a geometry rebuild, so callers must
     * clear stale selections afterwards.
     *
     * <p>The nearest-target search runs over a {@link com.openmason.engine.modeling.core.PointGrid} of the
     * stationary live vertices built once (#175), not a scan of every vertex per dragged vertex; the choice is
     * the same (nearest within epsilon, the higher vertex id on a tie).
     *
     * @param gmr              The model renderer (single source of truth)
     * @param draggedVertexIds Unique vertex ids that were dragged
     * @return true if at least one merge was applied
     */
    protected static boolean mergeCoincidentDraggedVertices(EditableModel gmr,
                                                            Set<Integer> draggedVertexIds) {
        if (gmr == null || draggedVertexIds == null || draggedVertexIds.isEmpty()) {
            return false;
        }
        float[] positions = gmr.getAllUniqueVertexPositions();
        if (positions == null) {
            return false;
        }
        int vertexCount = positions.length / 3;
        float epsilonSq = ModelingConstants.POSITION_MATCH_EPSILON * ModelingConstants.POSITION_MATCH_EPSILON;

        // Stationary vertices, indexed once (liveness is checked only for candidates within epsilon).
        int[] stationary = new int[vertexCount];
        int count = 0;
        for (int v = 0; v < vertexCount; v++) {
            if (!draggedVertexIds.contains(v)) {
                stationary[count++] = v;
            }
        }
        com.openmason.engine.modeling.core.PointGrid grid = new com.openmason.engine.modeling.core.PointGrid(
                positions, java.util.Arrays.copyOf(stationary, count), ModelingConstants.POSITION_MATCH_EPSILON);

        // Dragged vertex → nearest coincident stationary vertex, grouped by target.
        Map<Integer, List<Integer>> mergedByTarget = new LinkedHashMap<>();
        int[] best = new int[1];
        float[] bestDistSq = new float[1];
        for (int dragged : draggedVertexIds) {
            if (dragged < 0 || dragged >= vertexCount) {
                continue;
            }
            best[0] = -1;
            bestDistSq[0] = epsilonSq;
            grid.query(positions[dragged * 3], positions[dragged * 3 + 1], positions[dragged * 3 + 2], epsilonSq,
                    (v, distSq) -> {
                        if ((distSq < bestDistSq[0] || (distSq == bestDistSq[0] && v > best[0]))
                                && isLiveVertex(gmr, v)) {
                            best[0] = v;
                            bestDistSq[0] = distSq;
                        }
                    });
            if (best[0] >= 0) {
                mergedByTarget.computeIfAbsent(best[0], k -> new ArrayList<>()).add(dragged);
            }
        }

        // EditableMesh vertex ids are stable (orphans are kept, ids never
        // shift), so groups computed up front stay valid across merges.
        boolean merged = false;
        for (Map.Entry<Integer, List<Integer>> entry : mergedByTarget.entrySet()) {
            int[] mergedIds = new int[entry.getValue().size()];
            for (int i = 0; i < mergedIds.length; i++) {
                mergedIds[i] = entry.getValue().get(i);
            }
            merged |= gmr.mergeVertices(entry.getKey(), mergedIds);
        }
        return merged;
    }

    /** A vertex still referenced by faces (orphans have no render corners). */
    private static boolean isLiveVertex(EditableModel gmr, int uniqueVertexId) {
        int[] corners = gmr.getMeshIndicesForUniqueVertex(uniqueVertexId);
        return corners != null && corners.length > 0;
    }
}
