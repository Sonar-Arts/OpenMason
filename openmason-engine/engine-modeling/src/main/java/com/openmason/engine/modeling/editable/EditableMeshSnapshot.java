package com.openmason.engine.modeling.editable;

import com.openmason.engine.modeling.uv.FaceTextureMapping;
import com.openmason.engine.modeling.uv.MaterialDefinition;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Engine-side undo unit: an exact deep copy of an {@link EditableMesh} plus
 * the per-face texture mappings and materials that key off its face ids, and
 * the import's soup-index mapping (part-system index space).
 *
 * <p>Restoring from this snapshot is exact — unlike the legacy array-based
 * snapshot path there is no re-weld, so two vertices dragged within welding
 * distance of each other cannot be silently merged by an undo.
 *
 * @param mesh                Deep copy of the mesh at capture time
 * @param faceMappings        Face id → texture mapping (immutable records)
 * @param materials           Material id → definition (immutable records)
 * @param vertexIdToSoupIndices ALL combined-soup vertex indices per editable
 *                              vertex id at capture time, or null
 * @param shading               the model's shading stores at capture time (#169), or null = not captured
 */
public record EditableMeshSnapshot(
    EditableMesh mesh,
    Map<Integer, FaceTextureMapping> faceMappings,
    Map<Integer, MaterialDefinition> materials,
    int[][] vertexIdToSoupIndices,
    com.openmason.engine.modeling.shading.ModelShading.Snapshot shading) {

    /** A snapshot without shading. */
    public EditableMeshSnapshot(EditableMesh mesh, Map<Integer, FaceTextureMapping> faceMappings,
                                Map<Integer, MaterialDefinition> materials, int[][] vertexIdToSoupIndices) {
        this(mesh, faceMappings, materials, vertexIdToSoupIndices, null);
    }

    /**
     * Capture a snapshot. The mesh is deep-copied; mapping/material maps are
     * copied (their values are immutable records).
     */
    public static EditableMeshSnapshot capture(EditableMesh mesh,
                                               Map<Integer, FaceTextureMapping> faceMappings,
                                               Map<Integer, MaterialDefinition> materials,
                                               int[][] vertexIdToSoupIndices) {
        return capture(mesh, faceMappings, materials, vertexIdToSoupIndices, null);
    }

    /** {@link #capture(EditableMesh, Map, Map, int[][])} plus the model's shading (already an independent copy). */
    public static EditableMeshSnapshot capture(EditableMesh mesh,
                                               Map<Integer, FaceTextureMapping> faceMappings,
                                               Map<Integer, MaterialDefinition> materials,
                                               int[][] vertexIdToSoupIndices,
                                               com.openmason.engine.modeling.shading.ModelShading.Snapshot shading) {
        return new EditableMeshSnapshot(
            mesh.deepCopy(),
            new LinkedHashMap<>(faceMappings),
            new LinkedHashMap<>(materials),
            deepCopy(vertexIdToSoupIndices),
            shading);
    }

    private static int[][] deepCopy(int[][] mapping) {
        if (mapping == null) {
            return null;
        }
        int[][] copy = new int[mapping.length][];
        for (int i = 0; i < mapping.length; i++) {
            copy[i] = mapping[i] != null ? mapping[i].clone() : null;
        }
        return copy;
    }

    /** A fresh deep copy of the captured mesh (safe to hand to a live renderer). */
    public EditableMesh meshCopy() {
        return mesh.deepCopy();
    }

    /**
     * Exact content comparison against another snapshot — used to detect
     * no-op mutations (identical before/after ⇒ nothing to undo).
     */
    public boolean contentEquals(EditableMeshSnapshot other) {
        if (other == this) {
            return true;
        }
        return other != null
            && mesh.contentEquals(other.mesh)
            && faceMappings.equals(other.faceMappings)
            && materials.equals(other.materials)
            && java.util.Arrays.deepEquals(vertexIdToSoupIndices, other.vertexIdToSoupIndices)
            && shadingEquals(shading, other.shading);
    }

    private static boolean shadingEquals(com.openmason.engine.modeling.shading.ModelShading.Snapshot a,
                                         com.openmason.engine.modeling.shading.ModelShading.Snapshot b) {
        if (a == null) return b == null || b.isEmpty();
        return a.contentEquals(b);
    }
}
