package com.openmason.engine.modeling.editable;

import com.openmason.engine.modeling.ModelingConstants;

/**
 * Position welding for the import path: collapses coincident soup vertices
 * (within {@link ModelingConstants#POSITION_EPSILON}) into shared welded ids.
 *
 * <p>Thin binding over the native mesh pipeline ({@code co_mesh_weld}, {@link CendaMesh}; OpenMason #175 F3): a
 * spatial-hash grid (cell = 2x epsilon, 27-cell neighbour search, first match in cell insertion order - not the
 * nearest) exactly as the Java this replaced, proven bit for bit by {@code MeshPipelineParityTest}.
 */
public final class VertexWelder {

    private VertexWelder() {
        // Static utility — no instantiation
    }

    /**
     * Result of a weld pass.
     *
     * @param soupToWelded  For each soup vertex index, its welded vertex id
     * @param weldedPositions Welded positions (x,y,z interleaved), one entry
     *                        per welded id, taken from the first occurrence
     * @param weldedCount   Number of welded vertices
     */
    public record WeldResult(int[] soupToWelded, float[] weldedPositions, int weldedCount) {
    }

    /** Weld with the canonical {@link ModelingConstants#POSITION_EPSILON}. */
    public static WeldResult weld(float[] vertices) {
        return weld(vertices, ModelingConstants.POSITION_EPSILON);
    }

    /**
     * Weld coincident positions in a soup vertex array.
     *
     * @param vertices Soup positions (x,y,z interleaved)
     * @param epsilon  Distance below which two positions are the same vertex (must be {@code > 0}; the native
     *                 pipeline reads a non-positive epsilon as the default, so it is refused here)
     */
    public static WeldResult weld(float[] vertices, float epsilon) {
        if (vertices == null || vertices.length == 0) {
            return new WeldResult(new int[0], new float[0], 0);
        }
        if (vertices.length % 3 != 0) {
            throw new IllegalArgumentException(
                "Vertex array length must be divisible by 3: " + vertices.length);
        }
        if (!(epsilon > 0.0f)) {
            throw new IllegalArgumentException("Weld epsilon must be > 0: " + epsilon);
        }
        try (CendaMesh m = CendaMesh.create()) {
            m.weld(vertices, epsilon);
            float[] positions = m.floats(CendaMesh.A_VERTEX_POSITIONS);
            return new WeldResult(m.ints(CendaMesh.A_SOUP_TO_VERTEX), positions, positions.length / 3);
        }
    }
}
