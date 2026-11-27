package com.openmason.engine.modeling.core;

/**
 * Receives "the GPU copy of the mesh is stale" notices from the mesh
 * subsystems. GL-free: subsystems only report what changed; the owning model
 * syncs its GPU mesh on the next draw (on the GL thread), so any number of
 * edits between two frames upload once.
 */
public interface IMeshGpuSync {

    /** Corner positions changed (interactive vertex drag; UVs and triangles unchanged). */
    void positionsChanged();

    /** Corner positions and UVs were re-derived; triangles unchanged. */
    void verticesChanged();

    /**
     * Only the corners in {@code spans} ({@code first, count} pairs, ascending) changed position (and UVs when
     * {@code uvs}): a positions-only move of the live derivation (#175). Lets the GPU sync upload those ranges only.
     * The default treats it as a whole-buffer change.
     */
    default void cornersChanged(int[] spans, boolean uvs) {
        if (uvs) {
            verticesChanged();
        } else {
            positionsChanged();
        }
    }

    /** Corners and triangles were re-derived (topology or face change). */
    void structureChanged();

    /** Shading changed (smooth flags, sharp edges, custom normals; #169): the corner normals must be recomputed. */
    default void normalsChanged() {
    }
}
