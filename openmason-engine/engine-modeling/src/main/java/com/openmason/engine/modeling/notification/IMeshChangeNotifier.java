package com.openmason.engine.modeling.notification;

import com.openmason.engine.modeling.MeshChangeListener;
import com.openmason.engine.modeling.topology.MeshTopology;
import org.joml.Vector3f;

/**
 * Interface for mesh change notification using the Observer pattern.
 * Notifies listeners when vertex positions change or geometry is rebuilt.
 */
public interface IMeshChangeNotifier {

    /**
     * Add a listener to receive mesh change notifications.
     *
     * @param listener The listener to add
     */
    void addListener(MeshChangeListener listener);

    /**
     * Remove a listener from mesh change notifications.
     *
     * @param listener The listener to remove
     */
    void removeListener(MeshChangeListener listener);

    /**
     * Notify all listeners that a vertex position has changed.
     *
     * @param uniqueIndex The unique vertex index
     * @param newPosition The new position
     * @param affectedMeshIndices All mesh indices that were updated
     */
    void notifyVertexPositionChanged(int uniqueIndex, Vector3f newPosition, int[] affectedMeshIndices);

    /**
     * Notify all listeners that several vertices moved at once (one drag frame / one undo step): one call per
     * listener instead of one per vertex. Parallel arrays: vertex {@code i} is {@code uniqueIndices[i]}, its new
     * position {@code positions[3i..3i+2]}, its corners {@code affectedMeshIndices[i]}.
     */
    default void notifyVertexPositionsChanged(int[] uniqueIndices, float[] positions, int[][] affectedMeshIndices) {
        Vector3f p = new Vector3f();
        for (int i = 0; i < uniqueIndices.length; i++) {
            p.set(positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2]);
            notifyVertexPositionChanged(uniqueIndices[i], p, affectedMeshIndices[i]);
        }
    }

    /**
     * Notify all listeners that geometry has been rebuilt.
     * Called after subdivision, UV mode change, or model loading.
     */
    void notifyGeometryRebuilt();

    /**
     * Get the number of registered listeners.
     *
     * @return Number of listeners
     */
    int getListenerCount();

    /**
     * Check if a listener is registered.
     *
     * @param listener The listener to check
     * @return true if registered
     */
    boolean hasListener(MeshChangeListener listener);

    /**
     * Notify all listeners that the mesh topology has been rebuilt.
     * Called after subdivision, model loading, or any structural change.
     *
     * @param topology The new topology index, or null if unavailable
     */
    void notifyTopologyRebuilt(MeshTopology topology);
}
