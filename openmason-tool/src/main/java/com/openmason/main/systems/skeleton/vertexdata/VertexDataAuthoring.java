package com.openmason.main.systems.skeleton.vertexdata;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.modeling.parts.MeshRange;
import com.openmason.engine.modeling.parts.ModelPartDescriptor;
import com.openmason.engine.modeling.parts.ModelPartManager;
import com.openmason.engine.modeling.parts.PartMeshRebuilder;
import com.openmason.engine.rig.RigMorphs;
import com.openmason.main.systems.rendering.model.io.omo.OmoVertexDataCarry;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The editor's shape keys (#171) and vertex groups (#172) of the live model: one {@link ShapeKeyStore}, one
 * {@link VertexGroupStore}, their shared {@link VertexDataHistory} (undo domain {@code morph}) and the
 * {@link VertexDataPreview}. Shared by the rigging pane's Shape Keys / Vertex Groups sections, the
 * {@code morph_*} / {@code vgroup_*} MCP tools and the .omo load / save. Marshalling only (Java feature freeze):
 *
 * <ul>
 *   <li><b>Mesh edits</b> ({@link #reconcile}): every access first compares each part that has data with the
 *       positions last seen; a part whose vertex count changed has its rows carried to its new vertices by nearest
 *       vertex (native search, {@link VertexRemap}; the rows are re-indexed in Java), a deleted part loses its rows.
 *       The same rule as {@code SkinStore.reconcile}, run lazily because the mesh bake is another subsystem's. A
 *       remap clears the vertex-data undo history (its snapshots index the old vertices).</li>
 *   <li><b>Capture</b> (Blender's "new shape from mix" workflow): {@link #beginCapture} remembers every part's
 *       positions as the BASIS, the user moves vertices with the normal tools, {@link #captureDeltas} turns
 *       "current - basis" into MODEL-space rows (the space the .omo mesh and the runtime's deltas are in: both
 *       positions go through the part's effective rest matrix), and the host puts the basis back
 *       ({@link #basisGeometry}) as a normal model undo step.</li>
 *   <li><b>Selection</b>: the viewport selects UNIQUE vertices; {@link #localVertices} maps them through a
 *       {@link IndexBridge} (unique -> live mesh -> combined soup -> part range) to part-local vertices, and
 *       {@link #uniqueVertices} maps a group back.</li>
 *   <li><b>Save</b>: {@link #carry()} hands the current values to {@link OmoVertexDataCarry}, which writes them in
 *       the saved vertex layout (like the skin's {@code OmoSkinExport}).</li>
 * </ul>
 * Main thread only.
 */
public final class VertexDataAuthoring {

    /** Vertex-index translation of the live viewport (unique selection index -> combined soup vertices). */
    public interface IndexBridge {
        /** Unique (selectable) vertices in the viewport. */
        int uniqueCount();

        /** The combined-soup vertices a unique vertex stands for (empty = unknown). */
        int[] soupOfUnique(int unique);
    }

    /** Positions below this (model units) count as unmoved when capturing. */
    public static final float CAPTURE_EPSILON = 1e-6f;

    private final ModelPartManager parts;
    private final ShapeKeyStore shapes = new ShapeKeyStore();
    private final VertexGroupStore groups = new VertexGroupStore();
    private final VertexDataHistory history = new VertexDataHistory(shapes, groups);
    private final VertexDataPreview preview = new VertexDataPreview();
    private final Map<String, float[]> knownPositions = new HashMap<>();
    private Map<String, float[]> basis;
    private long dataRevision;

    public VertexDataAuthoring(ModelPartManager parts) {
        this.parts = Objects.requireNonNull(parts, "parts");
        Runnable changed = () -> {
            dataRevision++;
            Set<String> keyNames = new HashSet<>();
            for (OMOFormat.MorphData m : shapes.morphs()) keyNames.add(m.name());
            Set<String> groupNames = new HashSet<>();
            for (OMOFormat.VertexGroupData g : groups.groups()) groupNames.add(g.name());
            preview.retain(keyNames, groupNames);
            // A part that just got data (an edit, an undo) is measured now, so the next topology edit can remap it.
            Set<String> withData = new LinkedHashSet<>(shapes.partIds());
            withData.addAll(groups.partIds());
            for (String id : withData) {
                float[] now = knownPositions.containsKey(id) ? null : positionsOf(id);
                if (now != null) remember(id, now);
            }
        };
        shapes.addListener(changed);
        groups.addListener(changed);
    }

    public ModelPartManager parts() {
        return parts;
    }

    public ShapeKeyStore shapeKeys() {
        reconcile();
        return shapes;
    }

    public VertexGroupStore groups() {
        reconcile();
        return groups;
    }

    public VertexDataHistory history() {
        return history;
    }

    public VertexDataPreview preview() {
        return preview;
    }

    /** Bumped by every change of either store (edits, undo, load, topology remaps). */
    public long dataRevision() {
        return dataRevision;
    }

    // ---- load / save -----------------------------------------------------------------------------

    /** Replaces both stores with a load's data (null = none); history, preview and capture reset. */
    public void load(List<OMOFormat.VertexGroupData> groupData, List<OMOFormat.MorphData> morphData) {
        knownPositions.clear();
        basis = null;
        shapes.load(morphData);
        groups.load(groupData);
        history.clear();
        preview.clear();
        rememberAll();
    }

    /** Everything gone (a new model). */
    public void clear() {
        load(null, null);
    }

    /** The current data for the next save (rows still per part-local vertex; see {@link OmoVertexDataCarry}). */
    public OmoVertexDataCarry carry() {
        reconcile();
        return OmoVertexDataCarry.load(groups.groups(), shapes.morphs());
    }

    // ---- mesh edits ------------------------------------------------------------------------------

    /**
     * Keeps the rows in step with the current geometry (see the class doc). Silent apart from the data revision.
     * Returns true when rows moved or were dropped.
     */
    public boolean reconcile() {
        Set<String> withData = new LinkedHashSet<>(shapes.partIds());
        withData.addAll(groups.partIds());
        boolean changed = false;
        for (String partId : withData) {
            float[] now = positionsOf(partId);
            if (now == null) {
                changed |= shapes.dropPart(partId);
                changed |= groups.dropPart(partId);
                knownPositions.remove(partId);
                continue;
            }
            float[] known = knownPositions.get(partId);
            if (known != null && known.length != now.length) {
                int[] source = VertexRemap.nearestSource(known, now);
                changed |= shapes.remapPart(partId, source);
                changed |= groups.remapPart(partId, source);
            }
            remember(partId, now);
        }
        for (String id : List.copyOf(knownPositions.keySet())) {
            if (!withData.contains(id)) knownPositions.remove(id);
        }
        if (changed) {
            dataRevision++;
            history.clear();
        }
        return changed;
    }

    /** Remembers every part's current positions (after a load / a recorded edit). */
    private void rememberAll() {
        for (ModelPartDescriptor p : parts.getAllParts()) {
            float[] now = positionsOf(p.id());
            if (now != null) remember(p.id(), now);
        }
    }

    /**
     * Keeps the part's LIVE position array (no copy; O(1) per call, the sections reconcile every frame): vertex moves
     * edit it in place, so it always holds the latest positions, and a topology edit installs a NEW array, leaving
     * this one as the "before" the remap needs.
     */
    private void remember(String partId, float[] now) {
        if (knownPositions.get(partId) != now) knownPositions.put(partId, now);
    }

    /** A part's live local positions (not a copy), or null when the part is gone / has no geometry. */
    private float[] positionsOf(String partId) {
        if (parts.getPartById(partId).isEmpty()) return null;
        PartMeshRebuilder.PartGeometry g = parts.getPartGeometry(partId);
        return g == null || g.vertices() == null ? null : g.vertices();
    }

    /** Vertex count of a part (0 when it has none). */
    public int vertexCount(String partId) {
        float[] p = positionsOf(partId);
        return p == null ? 0 : p.length / 3;
    }

    /** Every part with geometry, in model order. */
    public List<String> partIds() {
        List<String> out = new ArrayList<>();
        for (ModelPartDescriptor p : parts.getAllParts()) if (vertexCount(p.id()) > 0) out.add(p.id());
        return out;
    }

    /** A part id from an id or a name (exact, then case-insensitive); null when unknown. */
    public String resolvePart(String idOrName) {
        if (idOrName == null || idOrName.isBlank()) return null;
        if (parts.getPartById(idOrName).isPresent()) return idOrName;
        for (ModelPartDescriptor p : parts.getAllParts()) if (p.name().equals(idOrName)) return p.id();
        for (ModelPartDescriptor p : parts.getAllParts()) if (p.name().equalsIgnoreCase(idOrName)) return p.id();
        return null;
    }

    public String partName(String partId) {
        return parts.getPartById(partId).map(ModelPartDescriptor::name).orElse(partId);
    }

    // ---- recorded edits --------------------------------------------------------------------------

    /** Runs {@code edit} on the reconciled stores as one undo step ({@code morph} domain). */
    public void edit(String description, Runnable edit) {
        reconcile();
        history.record(description, edit);
    }

    /**
     * {@link #edit} that renames {@code renamedFrom} (a shape key or a group) to {@code renamedTo}: the preview
     * choice (slider value, hidden flag) follows the name when the edit succeeds.
     */
    public void edit(String description, Runnable edit, String renamedFrom, String renamedTo) {
        VertexDataPreview.Choice choice = renamedFrom == null ? null : preview.choice(renamedFrom);
        edit(description, edit);
        if (choice != null && renamedTo != null) preview.apply(renamedTo.trim(), choice);
    }

    /** Renames a shape key (one undo step); the preview value follows it. */
    public void renameShapeKey(String name, String newName) {
        String old = requireKey(name).name();
        edit("Rename Shape Key", () -> shapes.rename(old, newName), old, newName);
    }

    /** Renames a vertex group (one undo step); the preview's hidden flag follows it. */
    public void renameGroup(String name, String newName) {
        OMOFormat.VertexGroupData g = groups().get(name);
        if (g == null) throw new IllegalArgumentException("no vertex group named '" + name + "'");
        String old = g.name();
        edit("Rename Vertex Group", () -> groups.rename(old, newName), old, newName);
    }

    private OMOFormat.MorphData requireKey(String name) {
        OMOFormat.MorphData m = shapeKeys().get(name);
        if (m == null) throw new IllegalArgumentException("no shape key named '" + name + "'");
        return m;
    }

    // ---- capture ---------------------------------------------------------------------------------

    /** Remembers the current positions of every part as the basis of a new shape key. */
    public void beginCapture() {
        reconcile();
        Map<String, float[]> b = new LinkedHashMap<>();
        for (ModelPartDescriptor p : parts.getAllParts()) {
            float[] now = positionsOf(p.id());
            if (now != null) b.put(p.id(), now.clone());
        }
        basis = b;
    }

    public boolean isCapturing() {
        return basis != null;
    }

    public void cancelCapture() {
        basis = null;
    }

    /**
     * Model-space rows "current - basis" per part (only vertices that moved more than {@link #CAPTURE_EPSILON}).
     * Throws when no capture is running or a part's vertex count changed since {@link #beginCapture} (shape keys
     * record vertex MOVES; topology edits belong in the basis).
     */
    public List<OMOFormat.MorphPart> captureDeltas() {
        if (basis == null) throw new IllegalStateException("no capture running: begin one first");
        List<OMOFormat.MorphPart> out = new ArrayList<>();
        Vector3f a = new Vector3f(), b = new Vector3f();
        for (Map.Entry<String, float[]> e : basis.entrySet()) {
            float[] now = positionsOf(e.getKey());
            if (now == null) continue;
            float[] was = e.getValue();
            if (now.length != was.length) {
                throw new IllegalStateException("part '" + partName(e.getKey()) + "' changed its vertex count since "
                        + "the capture began; shape keys record vertex moves only");
            }
            Matrix4f m = parts.getEffectiveWorldMatrix(e.getKey());
            List<Integer> vs = new ArrayList<>();
            List<float[]> ds = new ArrayList<>();
            for (int v = 0; v < now.length / 3; v++) {
                if (now[v * 3] == was[v * 3] && now[v * 3 + 1] == was[v * 3 + 1] && now[v * 3 + 2] == was[v * 3 + 2]) {
                    continue;
                }
                m.transformPosition(a.set(now[v * 3], now[v * 3 + 1], now[v * 3 + 2]));
                m.transformPosition(b.set(was[v * 3], was[v * 3 + 1], was[v * 3 + 2]));
                a.sub(b);
                if (Math.abs(a.x) <= CAPTURE_EPSILON && Math.abs(a.y) <= CAPTURE_EPSILON
                        && Math.abs(a.z) <= CAPTURE_EPSILON) {
                    continue;
                }
                vs.add(v);
                ds.add(new float[] {a.x, a.y, a.z});
            }
            if (vs.isEmpty()) continue;
            int[] vv = new int[vs.size()];
            float[] dd = new float[vv.length * 3];
            for (int k = 0; k < vv.length; k++) {
                vv[k] = vs.get(k);
                System.arraycopy(ds.get(k), 0, dd, k * 3, 3);
            }
            out.add(new OMOFormat.MorphPart(e.getKey(), vv, dd, null));
        }
        return out;
    }

    /**
     * The parts whose positions differ from the basis, with their basis geometry (current indices / UVs, basis
     * positions) - what the host writes back ({@code ModelPartManager.replacePartGeometry}) inside a model undo step
     * after a capture. Empty when nothing moved or no capture runs.
     */
    public Map<String, PartMeshRebuilder.PartGeometry> basisGeometry() {
        Map<String, PartMeshRebuilder.PartGeometry> out = new LinkedHashMap<>();
        if (basis == null) return out;
        for (Map.Entry<String, float[]> e : basis.entrySet()) {
            PartMeshRebuilder.PartGeometry g = parts.getPartGeometry(e.getKey());
            if (g == null || g.vertices() == null || g.vertices().length != e.getValue().length) continue;
            if (Arrays.equals(g.vertices(), e.getValue())) continue;
            out.put(e.getKey(), PartMeshRebuilder.PartGeometry.of(e.getValue().clone(), g.texCoords(), g.indices(),
                    g.triangleToFaceId()));
        }
        return out;
    }

    /** Ends the capture: the rows become a new shape key (or replace a same-named key's rows); one undo step. */
    public int finishCapture(String name, boolean replace) {
        List<OMOFormat.MorphPart> rows = captureDeltas();
        if (rows.isEmpty()) throw new IllegalStateException("no vertex moved since the capture began");
        int[] index = new int[1];
        edit("Capture Shape Key", () -> index[0] = shapes.add(name, 0f, 0f, 1f, rows, replace));
        basis = null;
        return index[0];
    }

    // ---- selection -------------------------------------------------------------------------------

    /** Part-local vertices (sorted, per part in model order) of the given unique viewport vertices. */
    public Map<String, int[]> localVertices(Collection<Integer> uniqueVertices, IndexBridge bridge) {
        Map<String, TreeSet<Integer>> acc = new LinkedHashMap<>();
        List<ModelPartDescriptor> all = parts.getAllParts();
        for (int u : uniqueVertices) {
            for (int soup : bridge.soupOfUnique(u)) {
                for (ModelPartDescriptor p : all) {
                    MeshRange r = p.meshRange();
                    if (r == null || !r.containsVertex(soup)) continue;
                    acc.computeIfAbsent(p.id(), k -> new TreeSet<>()).add(r.toLocalVertex(soup));
                    break;
                }
            }
        }
        Map<String, int[]> out = new LinkedHashMap<>();
        for (ModelPartDescriptor p : all) {
            TreeSet<Integer> s = acc.get(p.id());
            if (s != null) out.put(p.id(), s.stream().mapToInt(Integer::intValue).toArray());
        }
        return out;
    }

    /** The unique viewport vertices that stand for any member (weight above 0) of a group. */
    public Set<Integer> uniqueVertices(String group, IndexBridge bridge) {
        OMOFormat.VertexGroupData g = groups().get(group);
        Set<Integer> out = new TreeSet<>();
        if (g == null) return out;
        Set<Integer> members = new HashSet<>();
        for (OMOFormat.GroupPart gp : g.parts()) {
            MeshRange r = parts.getPartById(gp.partId()).map(ModelPartDescriptor::meshRange).orElse(null);
            if (r == null) continue;
            int[] v = gp.vertices();
            float[] w = gp.weights();
            for (int k = 0; k < v.length; k++) {
                if (w[k] > 0f && v[k] < r.vertexCount()) members.add(r.toGlobalVertex(v[k]));
            }
        }
        if (members.isEmpty()) return out;
        for (int u = 0; u < bridge.uniqueCount(); u++) {
            for (int soup : bridge.soupOfUnique(u)) {
                if (members.contains(soup)) {
                    out.add(u);
                    break;
                }
            }
        }
        return out;
    }

    // ---- preview (scene viewport consumer, see VertexDataPreview) --------------------------------

    /**
     * The shape keys as native targets over the COMBINED soup (part-local vertex + its part's
     * {@code MeshRange.vertexStart}), or null without shape keys. The caller closes it.
     */
    public RigMorphs soupMorphs() {
        List<OMOFormat.MorphData> keys = shapeKeys().morphs();
        if (keys.isEmpty()) return null;
        List<ModelPartDescriptor> all = parts.getAllParts();
        List<String> ids = new ArrayList<>(all.size());
        int[] first = new int[all.size()];
        int[] count = new int[all.size()];
        int total = 0;
        for (int i = 0; i < all.size(); i++) {
            ModelPartDescriptor p = all.get(i);
            ids.add(p.id());
            MeshRange r = p.meshRange();
            first[i] = r == null ? 0 : r.vertexStart();
            count[i] = r == null ? 0 : r.vertexCount();
            if (r != null) total = Math.max(total, r.vertexStart() + r.vertexCount());
        }
        return RigMorphs.fromParts(keys, ids, first, count, total);
    }

    /**
     * The vertex groups as CSR rows over the COMBINED soup (part-local vertex + its part's {@code MeshRange.vertexStart};
     * rows past a part's current vertex count are skipped), or null without groups.
     */
    public com.openmason.engine.modeling.VertexDataPreviewSource.SoupGroups soupGroups() {
        List<OMOFormat.VertexGroupData> all = groups().groups();
        if (all.isEmpty()) return null;
        List<String> names = new ArrayList<>(all.size());
        int[] offsets = new int[all.size() + 1];
        int[] vertices = new int[64];
        float[] weights = new float[64];
        int rows = 0;
        for (int g = 0; g < all.size(); g++) {
            OMOFormat.VertexGroupData group = all.get(g);
            names.add(group.name());
            for (OMOFormat.GroupPart gp : group.parts()) {
                MeshRange r = parts.getPartById(gp.partId()).map(ModelPartDescriptor::meshRange).orElse(null);
                if (r == null) continue;
                int[] v = gp.vertices();
                float[] w = gp.weights();
                for (int k = 0; k < v.length; k++) {
                    if (v[k] < 0 || v[k] >= r.vertexCount()) continue;
                    if (rows == vertices.length) {
                        vertices = java.util.Arrays.copyOf(vertices, rows * 2);
                        weights = java.util.Arrays.copyOf(weights, rows * 2);
                    }
                    vertices[rows] = r.toGlobalVertex(v[k]);
                    weights[rows++] = w == null || k >= w.length ? 1f : w[k];
                }
            }
            offsets[g + 1] = rows;
        }
        return new com.openmason.engine.modeling.VertexDataPreviewSource.SoupGroups(names, offsets,
                java.util.Arrays.copyOf(vertices, rows), java.util.Arrays.copyOf(weights, rows));
    }

    private com.openmason.engine.modeling.VertexDataPreviewSource previewSource;

    /**
     * This data as the scene viewport's preview source ({@code EditableModel.setVertexDataPreview}, #175 INTEG): the
     * shape keys / vertex groups over the combined soup and the {@link VertexDataPreview} choices. One per instance.
     */
    public com.openmason.engine.modeling.VertexDataPreviewSource previewSource() {
        if (previewSource == null) {
            previewSource = new com.openmason.engine.modeling.VertexDataPreviewSource() {
                // The weights are re-read only when the preview or the data changed.
                private long weightsKey = Long.MIN_VALUE;
                private long weightsData = Long.MIN_VALUE;
                private float[] weights;

                @Override
                public long dataRevision() {
                    reconcile();
                    return VertexDataAuthoring.this.dataRevision();
                }

                @Override
                public RigMorphs soupMorphs() {
                    return VertexDataAuthoring.this.soupMorphs();
                }

                @Override
                public SoupGroups soupGroups() {
                    return VertexDataAuthoring.this.soupGroups();
                }

                // A data change can change the key order / count the weights follow: either bumps the revision.
                private long seenPreview = Long.MIN_VALUE;
                private long seenData = Long.MIN_VALUE;
                private long revision;

                @Override
                public long previewRevision() {
                    long p = preview.revision();
                    long d = VertexDataAuthoring.this.dataRevision();
                    if (p != seenPreview || d != seenData) {
                        seenPreview = p;
                        seenData = d;
                        revision++;
                    }
                    return revision;
                }

                @Override
                public float[] previewWeights() {
                    long key = preview.revision();
                    long data = VertexDataAuthoring.this.dataRevision();
                    if (weights == null || key != weightsKey || data != weightsData) {
                        List<OMOFormat.MorphData> keys = shapeKeys().morphs();
                        weights = keys.isEmpty() ? null : preview.weights(keys);
                        weightsKey = key;
                        weightsData = data;
                    }
                    return weights;
                }

                @Override
                public java.util.Set<String> hiddenGroups() {
                    return preview.hiddenGroups();
                }
            };
        }
        return previewSource;
    }
}
