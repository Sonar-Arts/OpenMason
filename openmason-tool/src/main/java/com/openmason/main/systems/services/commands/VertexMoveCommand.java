package com.openmason.main.systems.services.commands;

import com.openmason.engine.modeling.EditableModel;
import com.openmason.engine.modeling.parts.ModelPartManager;
import org.joml.Vector3f;

import java.util.Map;
import java.util.Set;

/**
 * Delta-based command for vertex, edge, and face translation.
 *
 * <p>Stores per-vertex old/new positions. The same class is used for all three
 * drag types — only the description differs (DRY principle).
 *
 * <p>Consecutive drags of the same vertex set are merged into a single undo step
 * via {@link #canMergeWith} / {@link #mergeWith}.
 */
public final class VertexMoveCommand implements ModelCommand {

    /**
     * Per-vertex position delta.
     */
    public record VertexDelta(int index, Vector3f oldPos, Vector3f newPos) {}

    private final Map<Integer, VertexDelta> deltas;
    private final String description;
    private final EditableModel gmr;
    private final RendererSynchronizer synchronizer;

    public VertexMoveCommand(Map<Integer, VertexDelta> deltas,
                             String description,
                             EditableModel gmr,
                             RendererSynchronizer synchronizer) {
        this.deltas = Map.copyOf(deltas);
        this.description = description;
        this.gmr = gmr;
        this.synchronizer = synchronizer;
    }

    @Override
    public void execute() {
        apply(true);
    }

    @Override
    public void undo() {
        apply(false);
    }

    /**
     * Moves every affected vertex ONCE (#175): the deltas are keyed by render corner and a vertex has several,
     * so they are folded to editable vertices and sent as one positions-only move (one batched overlay update),
     * then one position refresh and one batched part-geometry sync.
     */
    private void apply(boolean forward) {
        Map<Integer, Vector3f> byVertex = new java.util.LinkedHashMap<>();
        for (VertexDelta delta : deltas.values()) {
            int vertex = gmr.getUniqueIndexForMeshVertex(delta.index());
            if (vertex >= 0) {
                byVertex.put(vertex, forward ? delta.newPos() : delta.oldPos());
            }
        }
        int[] ids = new int[byVertex.size()];
        float[] positions = new float[ids.length * 3];
        int i = 0;
        for (Map.Entry<Integer, Vector3f> e : byVertex.entrySet()) {
            ids[i] = e.getKey();
            positions[i * 3] = e.getValue().x;
            positions[i * 3 + 1] = e.getValue().y;
            positions[i * 3 + 2] = e.getValue().z;
            i++;
        }
        gmr.moveVertices(ids, positions);
        gmr.commitVertexPositions();
        ModelPartManager pm = gmr.getPartManager();
        if (pm != null) {
            syncParts(pm, deltas.values(), forward);
        }
        synchronizer.synchronize();
    }

    /** Back-propagates the deltas' new (or old) positions into the part geometry in one batch. */
    public static void syncParts(ModelPartManager pm, java.util.Collection<VertexDelta> deltas, boolean newPositions) {
        int[] corners = new int[deltas.size()];
        float[] positions = new float[corners.length * 3];
        int i = 0;
        for (VertexDelta delta : deltas) {
            Vector3f p = newPositions ? delta.newPos() : delta.oldPos();
            corners[i] = delta.index();
            positions[i * 3] = p.x;
            positions[i * 3 + 1] = p.y;
            positions[i * 3 + 2] = p.z;
            i++;
        }
        pm.syncPartVerticesFromWorldPos(corners, positions);
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public boolean canMergeWith(ModelCommand other) {
        if (!(other instanceof VertexMoveCommand otherMove)) {
            return false;
        }
        return deltas.keySet().equals(otherMove.deltas.keySet());
    }

    @Override
    public ModelCommand mergeWith(ModelCommand other) {
        VertexMoveCommand otherMove = (VertexMoveCommand) other;

        // Keep this command's old positions, take other's new positions
        Map<Integer, VertexDelta> merged = new java.util.HashMap<>();
        for (Map.Entry<Integer, VertexDelta> entry : deltas.entrySet()) {
            int idx = entry.getKey();
            VertexDelta mine = entry.getValue();
            VertexDelta theirs = otherMove.deltas.get(idx);
            merged.put(idx, new VertexDelta(idx, mine.oldPos(), theirs.newPos()));
        }

        return new VertexMoveCommand(merged, description, gmr, synchronizer);
    }

    /**
     * Get the set of vertex indices affected by this command.
     */
    public Set<Integer> getAffectedVertexIndices() {
        return deltas.keySet();
    }
}
