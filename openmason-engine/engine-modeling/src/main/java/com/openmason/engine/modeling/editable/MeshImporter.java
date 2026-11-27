package com.openmason.engine.modeling.editable;

import com.openmason.engine.modeling.ModelingConstants;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Builds an {@link EditableMesh} from legacy triangle soup (duplicated
 * per-face corners + triangle indices + triangle→face mapping) — the OMO
 * on-disk representation and the part-rebuild input.
 *
 * <p>Steps:
 * <ol>
 *   <li>Weld coincident soup positions into shared vertex ids
 *       ({@link VertexWelder}); the welded id IS the editable vertex id.</li>
 *   <li>Group triangles by face id and drop triangles degenerate after
 *       welding (repeated welded ids).</li>
 *   <li>Reconstruct each face's polygon loop by directed boundary-edge
 *       walking over welded ids: a directed edge (a→b) is a boundary edge
 *       when its reverse (b→a) appears in no other triangle of the face.
 *       Working on welded ids (rather than raw corner indices) lets interior
 *       edges cancel even when the soup used coincident duplicate corners.</li>
 *   <li>Attach authored per-corner UVs (when the soup carries texCoords) so
 *       texturing that is not a per-face region projection — sphere lat/long
 *       grids, hand-authored UVs — survives the import.</li>
 * </ol>
 *
 * <p>Face ids are preserved verbatim — materials, UV regions and part face
 * ranges key off them.
 *
 * <p>Thin binding over the native mesh pipeline ({@code co_mesh_import}, {@link CendaMesh}; OpenMason #175 F3): every
 * step above is native, bit for bit the Java this replaced ({@code MeshPipelineParityTest}), including the
 * boundary-walk rules (a source with two boundary edges keeps its first position and its last target; the walk
 * succeeds only when it closes over every boundary edge) and the authored-UV pick (the first soup corner of the face
 * welded into the loop vertex). Face ids {@code >= 2^26} are refused natively (per-id arrays are sized by the
 * largest id).
 */
public final class MeshImporter {

    private static final Logger logger = LoggerFactory.getLogger(MeshImporter.class);

    /**
     * Import result.
     *
     * @param mesh                  Imported mesh
     * @param vertexIdToSoupIndices ALL soup vertex indices welded into each
     *                              editable vertex id (the part system's
     *                              combined-buffer index space). Welding can
     *                              cross part boundaries, so a shared seam
     *                              vertex may map into several parts' ranges —
     *                              consumers must consider every index, not
     *                              just a representative.
     */
    public record ImportResult(EditableMesh mesh, int[][] vertexIdToSoupIndices) {
    }

    /**
     * Structural problem encountered while importing soup. Emitted through the
     * optional warning sink so validators (e.g. WindingAnalyzer) can report
     * face-id contract violations instead of scraping the log.
     *
     * @param faceId Face the warning concerns, or -1 when not face-specific
     */
    public record ImportWarning(int faceId, Kind kind, String message) {

        /** Warning category. */
        public enum Kind {
            /** Directed boundary-edge walk did not close — the face id maps to
             *  soup that is not a triangulated simple polygon (contract violation). */
            BOUNDARY_WALK_FAILED,
            /** Face collapsed below 3 loop vertices after welding and was skipped. */
            DEGENERATE_FACE,
            /** Triangle indices exceeded the vertex array; triangles were dropped. */
            CORRUPT_INDICES
        }
    }

    private MeshImporter() {
        // Static utility — no instantiation
    }

    /** Position-only import (no authored UVs). */
    public static EditableMesh importSoup(float[] vertices, int[] indices, int[] triangleToFaceId) {
        return importSoup(vertices, null, indices, triangleToFaceId).mesh();
    }

    /**
     * Import triangle soup into a new {@link EditableMesh}.
     *
     * @param vertices          Soup positions (x,y,z interleaved)
     * @param texCoords         Soup UVs (u,v interleaved) or {@code null};
     *                          when present, faces carry them as authored UVs
     * @param indices           Triangle indices into the soup
     * @param triangleToFaceId  Face id per triangle; {@code null} means each
     *                          triangle is its own face (ids = triangle index)
     */
    public static ImportResult importSoup(float[] vertices, float[] texCoords,
                                          int[] indices, int[] triangleToFaceId) {
        return importSoup(vertices, texCoords, indices, triangleToFaceId, null);
    }

    /**
     * Import triangle soup, reporting structural problems to {@code warningSink}
     * (may be {@code null}). Warnings are additionally logged as before.
     */
    public static ImportResult importSoup(float[] vertices, float[] texCoords,
                                          int[] indices, int[] triangleToFaceId,
                                          Consumer<ImportWarning> warningSink) {
        EditableMesh mesh = new EditableMesh();
        if (vertices == null || vertices.length < 9 || indices == null || indices.length < 3) {
            return new ImportResult(mesh, new int[0][]);
        }
        if (vertices.length % 3 != 0) {
            throw new IllegalArgumentException(
                "Vertex array length must be divisible by 3: " + vertices.length);
        }
        int soupCount = vertices.length / 3;
        boolean hasUVs = texCoords != null && texCoords.length >= soupCount * 2;

        try (CendaMesh m = CendaMesh.create()) {
            m.importSoup(vertices, texCoords, indices, triangleToFaceId, ModelingConstants.POSITION_EPSILON, 0);

            float[] positions = m.floats(CendaMesh.A_VERTEX_POSITIONS);
            Vector3f p = new Vector3f();
            for (int w = 0; w < positions.length / 3; w++) {
                mesh.addVertex(p.set(positions[w * 3], positions[w * 3 + 1], positions[w * 3 + 2]));
            }

            // Warnings in the order Java's sink received them: CORRUPT_INDICES first, then per face in face order.
            int[] warnings = m.ints(CendaMesh.A_WARNINGS);
            for (int i = 0; i + 2 < warnings.length; i += 3) {
                report(warnings[i], warnings[i + 1], warnings[i + 2], soupCount, warningSink);
            }

            int[] faceIds = m.ints(CendaMesh.A_FACE_IDS);
            int[] offsets = m.ints(CendaMesh.A_LOOP_OFFSETS);
            int[] loops = m.ints(CendaMesh.A_LOOP_VERTICES);
            int[] flags = m.ints(CendaMesh.A_FACE_FLAGS);
            float[] loopUVs = m.floats(CendaMesh.A_LOOP_UVS);
            for (int f = 0; f < faceIds.length; f++) {
                int[] loop = Arrays.copyOfRange(loops, offsets[f], offsets[f + 1]);
                float[] cornerUVs = (flags[f] & CendaMesh.FACE_AUTHORED_UVS) != 0
                    ? Arrays.copyOfRange(loopUVs, offsets[f] * 2, offsets[f + 1] * 2)
                    : null;
                mesh.addFaceWithId(faceIds[f], loop, cornerUVs);
            }

            // All soup indices per welded vertex (part-system index space) —
            // welding crosses part boundaries, so every occurrence matters.
            int[][] vertexIdToSoupIndices = m.csr(CendaMesh.A_VERTEX_SOUP_OFFSETS, CendaMesh.A_VERTEX_SOUP);

            logger.debug("Imported soup: {} soup vertices -> {} shared vertices, {} faces (authoredUVs={})",
                soupCount, mesh.vertexCount(), mesh.faceCount(), hasUVs);
            return new ImportResult(mesh, vertexIdToSoupIndices);
        }
    }

    /** Logs and emits one native import warning with the Java importer's wording. */
    private static void report(int kind, int faceId, int value, int soupCount, Consumer<ImportWarning> sink) {
        switch (kind) {
            case CendaMesh.WARN_CORRUPT_INDICES -> {
                logger.error("Import dropped {} triangle(s) whose indices exceed the {}-vertex soup "
                        + "— the input geometry is corrupt (stale part ranges?)", value, soupCount);
                emit(sink, new ImportWarning(-1, ImportWarning.Kind.CORRUPT_INDICES,
                        value + " triangle(s) dropped: indices exceed the " + soupCount + "-vertex soup"));
            }
            case CendaMesh.WARN_BOUNDARY_WALK_FAILED -> {
                logger.warn("Boundary walk failed for face {} ({} triangles) — using insertion-order fallback",
                    faceId, value);
                emit(sink, new ImportWarning(faceId, ImportWarning.Kind.BOUNDARY_WALK_FAILED,
                        "boundary walk over " + value
                        + " triangles did not close — soup is not a triangulated simple polygon"));
            }
            case CendaMesh.WARN_DEGENERATE_FACE -> {
                logger.warn("Face {} degenerate after welding ({} loop vertices) — skipped", faceId, value);
                emit(sink, new ImportWarning(faceId, ImportWarning.Kind.DEGENERATE_FACE,
                        "face collapsed to " + value + " loop vertices after welding"));
            }
            default -> logger.warn("Unknown native import warning kind {} (face {}, value {})", kind, faceId, value);
        }
    }

    private static void emit(Consumer<ImportWarning> sink, ImportWarning warning) {
        if (sink != null) {
            sink.accept(warning);
        }
    }
}
