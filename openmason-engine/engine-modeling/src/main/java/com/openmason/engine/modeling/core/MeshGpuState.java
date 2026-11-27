package com.openmason.engine.modeling.core;

/**
 * GL-free dirty tracking for an editable model's GPU mesh: which arrays must be
 * re-sent on the next sync. Triangle order is tracked separately by the rebuild
 * pipeline's draw-batch flag, since material changes re-sort it too.
 *
 * <p>Positions and UVs also remember WHICH corners changed when every change since the last sync was a ranged one
 * ({@link #cornersChanged}, a drag frame of the live derivation, #175): {@link #positionSpans()} /
 * {@link #uvSpans()} then list the corner ranges to sub-upload; null means the whole buffer.
 */
public final class MeshGpuState implements IMeshGpuSync {

    /** More disjoint ranges than this collapse into their bounding range (a few uploads, never hundreds). */
    static final int MAX_SPANS = 16;

    private boolean positionsDirty = true;
    private boolean uvsDirty = true;
    private boolean indicesDirty = true;
    private boolean normalsDirty = true;
    // Dirty corner ranges (first, count pairs, ascending, disjoint); null while dirty = the whole buffer.
    private int[] positionSpans;
    private int[] uvSpans;

    @Override
    public void positionsChanged() {
        positionsDirty = true;
        positionSpans = null;
        normalsDirty = true;
    }

    @Override
    public void verticesChanged() {
        positionsDirty = true;
        uvsDirty = true;
        positionSpans = null;
        uvSpans = null;
        normalsDirty = true;
    }

    @Override
    public void cornersChanged(int[] spans, boolean uvs) {
        if (spans == null) {
            if (uvs) verticesChanged(); else positionsChanged();
            return;
        }
        positionSpans = positionsDirty ? (positionSpans == null ? null : union(positionSpans, spans)) : spans.clone();
        positionsDirty = true;
        if (uvs) {
            uvSpans = uvsDirty ? (uvSpans == null ? null : union(uvSpans, spans)) : spans.clone();
            uvsDirty = true;
        }
        normalsDirty = true;
    }

    @Override
    public void structureChanged() {
        positionsDirty = true;
        uvsDirty = true;
        indicesDirty = true;
        normalsDirty = true;
        positionSpans = null;
        uvSpans = null;
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

    /** The dirty position corner ranges ({@code first, count} pairs), or null = the whole buffer. */
    public int[] positionSpans() {
        return positionsDirty ? positionSpans : null;
    }

    /** The dirty UV corner ranges ({@code first, count} pairs), or null = the whole buffer. */
    public int[] uvSpans() {
        return uvsDirty ? uvSpans : null;
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
        positionSpans = null;
        uvSpans = null;
    }

    /** Union of two sorted disjoint range lists (touching / overlapping ranges merge), capped at {@link #MAX_SPANS}. */
    static int[] union(int[] a, int[] b) {
        int[] out = new int[a.length + b.length];
        int n = 0;
        int i = 0;
        int j = 0;
        while (i < a.length || j < b.length) {
            int first;
            int count;
            if (j >= b.length || (i < a.length && a[i] <= b[j])) {
                first = a[i];
                count = a[i + 1];
                i += 2;
            } else {
                first = b[j];
                count = b[j + 1];
                j += 2;
            }
            if (n > 0 && first <= out[n - 2] + out[n - 1]) {
                int end = Math.max(out[n - 2] + out[n - 1], first + count);
                out[n - 1] = end - out[n - 2];
            } else {
                out[n++] = first;
                out[n++] = count;
            }
        }
        if (n > MAX_SPANS * 2) {
            int end = out[n - 2] + out[n - 1];
            out[1] = end - out[0];
            n = 2;
        }
        return java.util.Arrays.copyOf(out, n);
    }
}
