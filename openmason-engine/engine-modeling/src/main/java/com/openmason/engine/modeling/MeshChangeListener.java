package com.openmason.engine.modeling;

import com.openmason.engine.modeling.topology.MeshTopology;
import org.joml.Vector3f;

/**
 * Listener interface for mesh change events from EditableModel.
 * Implements the Observer pattern to replace fragile position-based matching
 * with robust index-based notifications.
 *
 * <p>Observers receive notifications when:
 * <ul>
 *   <li>A vertex position changes (with both unique and mesh indices)</li>
 *   <li>Geometry is rebuilt (after subdivision, UV mode change, etc.)</li>
 * </ul>
 *
 * <p>This enables VertexRenderer, EdgeRenderer, and FaceRenderer to stay
 * synchronized with EditableModel without searching by position.
 */
public interface MeshChangeListener {

    /**
     * Called when a vertex position has been updated.
     * The listener receives both the unique vertex index and all affected mesh indices.
     *
     * @param uniqueIndex The unique vertex index (0 to uniqueVertexCount-1)
     * @param newPosition The new position of the vertex
     * @param affectedMeshIndices All mesh vertex indices that were updated
     *                            (multiple mesh vertices share the same position)
     */
    void onVertexPositionChanged(int uniqueIndex, Vector3f newPosition, int[] affectedMeshIndices);

    /**
     * Called once when several vertices moved together (a drag frame, an undo step). Parallel arrays: vertex
     * {@code i} is {@code uniqueIndices[i]}, its new position {@code positions[3i..3i+2]}, its corners
     * {@code affectedMeshIndices[i]}. The arrays are only valid during the call.
     *
     * <p>The default forwards each vertex to {@link #onVertexPositionChanged}; overlays override it to update
     * their buffers once per batch.
     */
    default void onVertexPositionsChanged(int[] uniqueIndices, float[] positions, int[][] affectedMeshIndices) {
        Vector3f p = new Vector3f();
        for (int i = 0; i < uniqueIndices.length; i++) {
            p.set(positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2]);
            onVertexPositionChanged(uniqueIndices[i], p, affectedMeshIndices[i]);
        }
    }

    /**
     * Called when the entire geometry has been rebuilt.
     * This occurs after:
     * <ul>
     *   <li>Edge subdivision (new vertices and triangles added)</li>
     *   <li>UV mode change (geometry regenerated)</li>
     *   <li>Model loading (parts changed)</li>
     * </ul>
     *
     * <p>Listeners should do a full rebuild of their derived data structures
     * rather than incremental updates.
     */
    void onGeometryRebuilt();

    /**
     * Called when the mesh topology index has been rebuilt.
     * This occurs after subdivision, model loading, or any operation that
     * changes the edge/face structure.
     *
     * <p>Default implementation is a no-op for backward compatibility.
     *
     * @param topology The new topology index, or null if unavailable
     */
    default void onTopologyRebuilt(MeshTopology topology) {
        // Default no-op for backward compatibility
    }
}
