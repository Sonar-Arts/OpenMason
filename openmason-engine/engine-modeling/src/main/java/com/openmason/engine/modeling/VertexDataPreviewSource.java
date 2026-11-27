package com.openmason.engine.modeling;

import com.openmason.engine.rig.RigMorphs;

import java.util.List;
import java.util.Set;

/**
 * What the editor's scene viewport previews of the model's shape keys (#171) and vertex groups (#172), as the host
 * (the tool's rigging data) hands it to {@link EditableModel#setVertexDataPreview}. Everything is in the COMBINED
 * soup's vertex space (a part's {@code MeshRange.vertexStart} + part-local vertex, the space of the last part re-bake);
 * the model maps it onto its render corners. Polled once per draw: keep the revision getters cheap.
 */
public interface VertexDataPreviewSource {

    /** Bumped whenever the shape keys or vertex groups (the data, not the preview choices) change. */
    long dataRevision();

    /** The shape keys as native targets over the combined soup, or null without any. The caller closes it. */
    RigMorphs soupMorphs();

    /** The vertex groups over the combined soup, or null without any. */
    SoupGroups soupGroups();

    /** Bumped whenever a preview choice (a slider value, a hidden group) changes. */
    long previewRevision();

    /** Raw preview weights, one per target of {@link #soupMorphs()} in its order (null = the targets' defaults). */
    float[] previewWeights();

    /** The vertex groups hidden in the preview (their covered triangles are not drawn). */
    Set<String> hiddenGroups();

    /**
     * Vertex groups as CSR rows: group {@code g} = rows {@code [rowOffsets[g], rowOffsets[g + 1])}, each a combined
     * soup vertex and its weight (in the group when > 0).
     */
    record SoupGroups(List<String> names, int[] rowOffsets, int[] vertices, float[] weights) {
        public SoupGroups {
            names = List.copyOf(names);
        }
    }
}
