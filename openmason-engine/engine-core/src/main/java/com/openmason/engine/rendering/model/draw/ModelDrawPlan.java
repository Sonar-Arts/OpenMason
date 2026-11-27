package com.openmason.engine.rendering.model.draw;

import com.openmason.engine.entity.model.EntityFace;
import com.openmason.engine.entity.model.EntityGeometry;
import com.openmason.engine.entity.model.EntityPart;
import com.openmason.engine.rendering.model.IndexedDrawBatch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GL-free draw plan for one uploaded model: per part, the element-buffer ranges
 * to draw and the material each one uses. Compiled once per uploaded mesh and
 * reused every frame by {@link ModelRenderer}.
 *
 * <p>Every part carries two batch lists over the same index buffer:
 * <ul>
 *   <li>{@link PartBatches#textured()} — the lit pass. A batch's material id is
 *       resolved to a GL texture through {@link MaterialTextures} at draw time.</li>
 *   <li>{@link PartBatches#untextured()} — every face of the part with material
 *       {@code 0}, for passes that ignore materials (flat colour, wireframe,
 *       shadow casters). Faces the lit pass skips still draw here.</li>
 * </ul>
 *
 * <p>Part indices are positions in the source model's part list; the renderer
 * pairs them with the matrices from {@link PartMatrices}.
 */
public final class ModelDrawPlan {

    /** The batches one part draws, in authored order. */
    public record PartBatches(List<IndexedDrawBatch> textured, List<IndexedDrawBatch> untextured) {
        public PartBatches {
            textured = List.copyOf(textured);
            untextured = List.copyOf(untextured);
        }
    }

    private static final PartBatches EMPTY_PART = new PartBatches(List.of(), List.of());

    private final List<PartBatches> parts;
    private final Set<Integer> materialIds;
    /** The model's vertex-group mask plan (OpenMason #172), or null. */
    private final com.openmason.engine.format.omo.MaskPlan mask;
    /** Masked variants of this plan by group mask (a handful per model: one per distinct outfit). */
    private final java.util.concurrent.ConcurrentHashMap<ModelAppearance.GroupMask, ModelDrawPlan> masked =
            new java.util.concurrent.ConcurrentHashMap<>();

    private ModelDrawPlan(List<PartBatches> parts, Set<Integer> materialIds) {
        this(parts, materialIds, null);
    }

    private ModelDrawPlan(List<PartBatches> parts, Set<Integer> materialIds,
                          com.openmason.engine.format.omo.MaskPlan mask) {
        this.parts = List.copyOf(parts);
        this.materialIds = Set.copyOf(materialIds);
        this.mask = mask;
    }

    /** The vertex-group mask plan the plan's index ranges live in, or null (no vertex groups). */
    public com.openmason.engine.format.omo.MaskPlan maskPlan() {
        return mask;
    }

    /**
     * The plan to draw for {@code appearance} (OpenMason #172): this plan when the model has no vertex groups or the
     * appearance hides / keeps none; else the batches cut down to the mask plan's visible ranges for its
     * {@link ModelAppearance#groupMask()} - resolved natively once per distinct mask and cached (no per-frame CPU
     * work).
     */
    public ModelDrawPlan forAppearance(ModelAppearance appearance) {
        if (mask == null || appearance == null) return this;
        ModelAppearance.GroupMask key = appearance.groupMask();
        if (key == null) return this;
        ModelDrawPlan cached = masked.get(key);
        if (cached != null) return cached;
        return masked.computeIfAbsent(key, k -> masked(mask.ranges(k.hidden(), k.kept())));
    }

    /**
     * This plan bound to a vertex-group mask plan built over the index buffer it draws (OpenMason #172; a host that
     * sorts its own triangles, e.g. the editor's {@link #materialSorted} buffer, then uploads {@code mask.indices()}).
     * The mask plan keeps every triangle inside its key run, so this plan's batches stay valid on the reordered
     * buffer as long as each batch is one key run. Null = no mask. The caller keeps owning {@code mask}.
     */
    public ModelDrawPlan withMaskPlan(com.openmason.engine.format.omo.MaskPlan mask) {
        return new ModelDrawPlan(parts, materialIds, mask);
    }

    /**
     * This plan with every batch cut down to the visible index {@code ranges} ({@code first, count} pairs in buffer
     * order, as {@link com.openmason.engine.format.omo.MaskPlan#ranges} returns them); materials unchanged.
     */
    public ModelDrawPlan masked(int[] ranges) {
        List<PartBatches> out = new ArrayList<>(parts.size());
        for (PartBatches p : parts) {
            out.add(new PartBatches(clip(p.textured(), ranges), clip(p.untextured(), ranges)));
        }
        return new ModelDrawPlan(out, materialIds, null);
    }

    /**
     * {@code batches} intersected with the visible {@code ranges} ({@code first, count} pairs, sorted, disjoint): each
     * batch becomes its visible sub-ranges (same material; adjacent pieces merged). Hosts with their own batch lists
     * use this to honour a mask plan.
     */
    public static List<IndexedDrawBatch> clip(List<IndexedDrawBatch> batches, int[] ranges) {
        var out = new IndexedDrawBatch.Builder();
        int n = ranges.length / 2;
        for (IndexedDrawBatch b : batches) {
            int start = b.indexStart();
            int end = start + b.indexCount();
            // First range that ends after the batch starts.
            int lo = 0, hi = n;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (ranges[mid * 2] + ranges[mid * 2 + 1] <= start) lo = mid + 1;
                else hi = mid;
            }
            for (int r = lo; r < n && ranges[r * 2] < end; r++) {
                int s = Math.max(start, ranges[r * 2]);
                int e = Math.min(end, ranges[r * 2] + ranges[r * 2 + 1]);
                if (e > s) out.add(b.materialId(), s, e - s);
            }
        }
        return out.build();
    }

    /** Number of parts the plan covers. */
    public int partCount() {
        return parts.size();
    }

    /** Batches for part {@code index}; an empty plan entry for an unknown index. */
    public PartBatches forPart(int index) {
        return index >= 0 && index < parts.size() ? parts.get(index) : EMPTY_PART;
    }

    /** Every material id the textured batches reference (what needs a texture). */
    public Set<Integer> materialIds() {
        return materialIds;
    }

    /**
     * Plan for an entity model: one entry per {@link EntityPart}, faces coalesced in
     * authored order (never sorted — coplanar and transparent faces keep their
     * draw order). A face whose material has no image skips the lit pass, as the
     * original entity renderer did; it still draws in the untextured list.
     *
     * @throws IllegalArgumentException if a face's index range exceeds the mesh
     */
    public static ModelDrawPlan of(EntityGeometry geometry) {
        List<PartBatches> parts = new ArrayList<>(geometry.parts().size());
        Set<Integer> usedMaterials = new HashSet<>();
        for (EntityPart part : geometry.parts()) {
            var textured = new IndexedDrawBatch.Builder();
            var untextured = new IndexedDrawBatch.Builder();
            for (EntityFace face : part.faces()) {
                if ((long) face.indexStart() + face.indexCount() > geometry.indices().length) {
                    throw new IllegalArgumentException("Face index range exceeds mesh: " + face.faceId());
                }
                untextured.add(0, face.indexStart(), face.indexCount());
                if (face.indexCount() > 0 && geometry.materials().containsKey(face.materialId())) {
                    textured.add(face.materialId(), face.indexStart(), face.indexCount());
                    usedMaterials.add(face.materialId());
                }
            }
            parts.add(new PartBatches(textured.build(), untextured.build()));
        }
        return new ModelDrawPlan(parts, usedMaterials, geometry.maskPlan());
    }

    /**
     * The triangle order {@link #materialSorted} uploads: {@code order[slot]} = the input triangle drawn at triangle
     * slot {@code slot} (triangles grouped by key in order of first appearance, input order inside a group). For
     * hosts that map a GPU primitive id back to their triangles.
     */
    public static int[] materialSortOrder(int[] triangleKeys, int triangleCount) {
        Map<Integer, int[]> counts = new LinkedHashMap<>();
        for (int t = 0; t < triangleCount; t++) {
            counts.computeIfAbsent(triangleKeys[t], k -> new int[1])[0]++;
        }
        Map<Integer, int[]> cursor = new java.util.HashMap<>();
        int offset = 0;
        for (Map.Entry<Integer, int[]> e : counts.entrySet()) {
            cursor.put(e.getKey(), new int[]{offset});
            offset += e.getValue()[0];
        }
        int[] order = new int[triangleCount];
        for (int t = 0; t < triangleCount; t++) {
            order[cursor.get(triangleKeys[t])[0]++] = t;
        }
        return order;
    }

    /** Result of {@link #materialSorted}: the reordered index buffer and its single-part plan. */
    public record Sorted(int[] indices, ModelDrawPlan plan) {}

    /**
     * Single-part plan for a triangle soup whose triangles each carry a material
     * key (an editable model: one key per face). Triangles are grouped by key in
     * order of first appearance — keeping authored order inside each group — so a
     * model with {@code n} distinct materials draws in {@code n} batches. The
     * returned index buffer is the reordered copy to upload; the input is untouched.
     *
     * @param indices      triangle indices ({@code 3 * triangleCount})
     * @param triangleKeys material key per triangle (length {@code triangleCount})
     */
    public static Sorted materialSorted(int[] indices, int[] triangleKeys) {
        if (indices == null || indices.length == 0) {
            return new Sorted(new int[0], new ModelDrawPlan(List.of(EMPTY_PART), Set.of()));
        }
        if (indices.length % 3 != 0) {
            throw new IllegalArgumentException("Index count is not a multiple of 3: " + indices.length);
        }
        int triangleCount = indices.length / 3;
        if (triangleKeys == null || triangleKeys.length < triangleCount) {
            throw new IllegalArgumentException("Need one material key per triangle: "
                    + (triangleKeys == null ? 0 : triangleKeys.length) + " < " + triangleCount);
        }

        // Count triangles per key, in order of first appearance.
        Map<Integer, int[]> counts = new LinkedHashMap<>();
        for (int t = 0; t < triangleCount; t++) {
            counts.computeIfAbsent(triangleKeys[t], k -> new int[1])[0]++;
        }

        // Prefix offsets (in triangles) per key, then scatter.
        Map<Integer, int[]> cursor = new LinkedHashMap<>();
        var untextured = new IndexedDrawBatch.Builder();
        var textured = new IndexedDrawBatch.Builder();
        int offset = 0;
        for (Map.Entry<Integer, int[]> e : counts.entrySet()) {
            int count = e.getValue()[0];
            cursor.put(e.getKey(), new int[]{offset});
            textured.add(e.getKey(), offset * 3, count * 3);
            untextured.add(0, offset * 3, count * 3);
            offset += count;
        }

        int[] sorted = new int[triangleCount * 3];
        for (int t = 0; t < triangleCount; t++) {
            int[] c = cursor.get(triangleKeys[t]);
            int dst = c[0]++ * 3;
            int src = t * 3;
            sorted[dst] = indices[src];
            sorted[dst + 1] = indices[src + 1];
            sorted[dst + 2] = indices[src + 2];
        }

        PartBatches part = new PartBatches(textured.build(), untextured.build());
        return new Sorted(sorted, new ModelDrawPlan(List.of(part), counts.keySet()));
    }

    @Override
    public String toString() {
        return "ModelDrawPlan" + Arrays.toString(parts.toArray());
    }
}
