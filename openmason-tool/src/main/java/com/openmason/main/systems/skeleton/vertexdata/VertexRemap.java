package com.openmason.main.systems.skeleton.vertexdata;

import com.openmason.engine.rig.Skin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Which old vertex each vertex of an edited part stands for, so per-vertex data (shape-key deltas, vertex-group
 * weights) survives a topology edit the way skin weights do ({@code SkinStore.reconcile}): every new vertex takes
 * the data of its NEAREST old vertex. The nearest search is native: the old vertices are written into a throwaway
 * {@link Skin} with vertex i weighted 1 to "joint" i, and {@link Skin#transfer} (cenda/rig.h
 * {@code cr_skin_transfer}, a spatial grid) hands every new vertex its nearest old vertex's single influence - its
 * joint IS the source index. Java only reads the indices back.
 *
 * <p>Candidate for native: a direct {@code nearest_vertex_map(old, new) -> source[]} (or {@code cr_morphs_transfer}
 * / a group equivalent) would drop the throwaway skin.
 */
public final class VertexRemap {

    private VertexRemap() {
    }

    /**
     * For each new vertex (x,y,z triples), the index of the nearest old vertex; -1 for every vertex when there are
     * no old vertices.
     */
    public static int[] nearestSource(float[] oldPositions, float[] newPositions) {
        int oldCount = oldPositions == null ? 0 : oldPositions.length / 3;
        int newCount = newPositions == null ? 0 : newPositions.length / 3;
        int[] source = new int[newCount];
        Arrays.fill(source, -1);
        if (oldCount == 0 || newCount == 0) return source;
        List<Skin.Influence> identity = new ArrayList<>(oldCount);
        float[] one = {1f};
        for (int i = 0; i < oldCount; i++) identity.add(new Skin.Influence(i, new int[] {i}, one));
        try (Skin ids = Skin.of(oldCount, identity);
             Skin moved = ids.transfer(oldPositions, newPositions)) {
            for (Skin.Influence inf : moved.exportInfluences()) {
                if (inf.vertex() >= 0 && inf.vertex() < newCount && inf.joints().length > 0) {
                    source[inf.vertex()] = inf.joints()[0];
                }
            }
        }
        return source;
    }
}
