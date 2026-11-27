package com.openmason.engine.modeling.core;

/**
 * GL-free dirty tracking for an editable model's GPU mesh: which arrays must be
 * re-sent on the next sync. Triangle order is tracked separately by the rebuild
 * pipeline's draw-batch flag, since material changes re-sort it too.
 */
public final class MeshGpuState implements IMeshGpuSync {

    private boolean positionsDirty = true;
    private boolean uvsDirty = true;
    private boolean indicesDirty = true;
    private boolean normalsDirty = true;

    @Override
    public void positionsChanged() {
        positionsDirty = true;
        normalsDirty = true;
    }

    @Override
    public void verticesChanged() {
        positionsDirty = true;
        uvsDirty = true;
        normalsDirty = true;
    }

    @Override
    public void structureChanged() {
        positionsDirty = true;
        uvsDirty = true;
        indicesDirty = true;
        normalsDirty = true;
    }

    @Override
    public void normalsChanged() {
        normalsDirty = true;
    }

    /** Everything stale (a freshly created GPU mesh holds nothing). */
    public void markAll() {
        structureChanged();
    }

    public boolean positionsDirty() {
        return positionsDirty;
    }

    public boolean uvsDirty() {
        return uvsDirty;
    }

    public boolean indicesDirty() {
        return indicesDirty;
    }

    /** Corner normals must be recomputed (positions, topology or shading changed). */
    public boolean normalsDirty() {
        return normalsDirty;
    }

    /** True when nothing needs uploading. */
    public boolean isClean() {
        return !positionsDirty && !uvsDirty && !indicesDirty && !normalsDirty;
    }

    /** Called once the GPU copy holds the current arrays. */
    public void clear() {
        positionsDirty = false;
        uvsDirty = false;
        indicesDirty = false;
        normalsDirty = false;
    }
}
