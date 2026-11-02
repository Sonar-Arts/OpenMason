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

    /** Corners and triangles were re-derived (topology or face change). */
    void structureChanged();
}
