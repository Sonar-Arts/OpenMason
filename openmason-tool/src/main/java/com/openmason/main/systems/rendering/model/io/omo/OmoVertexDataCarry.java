package com.openmason.main.systems.rendering.model.io.omo;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.modeling.parts.MeshRange;
import com.openmason.engine.modeling.parts.ModelPartDescriptor;
import com.openmason.engine.modeling.parts.ModelPartManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OMO 1.9 vertex groups and morphs (shape keys) on their way to a save: the values of the editor's stores
 * ({@code skeleton.vertexdata.VertexDataAuthoring#carry}: the rigging pane's Shape Keys / Vertex Groups sections and
 * the {@code morph_*} / {@code vgroup_*} MCP tools edit them, #171 / #172), or a headless load passed through. Rows
 * are kept per part by part-local vertex (the restored part's own vertex order, like the skin) and written back in
 * the layout of the file being written exactly like {@link OmoSkinExport}: every written vertex of a part takes the
 * row of the part vertex it stands for ({@link ModelPartManager#resolveCombinedVertex}). Index marshalling only.
 */
public final class OmoVertexDataCarry {

    /** Nothing carried. */
    public static final OmoVertexDataCarry EMPTY = new OmoVertexDataCarry(List.of(), List.of());

    private final List<OMOFormat.VertexGroupData> groups;
    private final List<OMOFormat.MorphData> morphs;

    private OmoVertexDataCarry(List<OMOFormat.VertexGroupData> groups, List<OMOFormat.MorphData> morphs) {
        this.groups = groups;
        this.morphs = morphs;
    }

    /** The groups and morphs of a load (null = none). */
    public static OmoVertexDataCarry load(List<OMOFormat.VertexGroupData> groups, List<OMOFormat.MorphData> morphs) {
        List<OMOFormat.VertexGroupData> g = groups == null ? List.of() : List.copyOf(groups);
        List<OMOFormat.MorphData> m = morphs == null ? List.of() : List.copyOf(morphs);
        return g.isEmpty() && m.isEmpty() ? EMPTY : new OmoVertexDataCarry(g, m);
    }

    public boolean hasData() {
        return !groups.isEmpty() || !morphs.isEmpty();
    }

    public List<OMOFormat.VertexGroupData> groups() {
        return groups;
    }

    public List<OMOFormat.MorphData> morphs() {
        return morphs;
    }

    /** The vertex groups in the layout of the part entries being written, or null when none survives. */
    public List<OMOFormat.VertexGroupData> groupsInSavedLayout(ModelPartManager parts,
                                                               List<OMOFormat.PartEntry> entries) {
        if (groups.isEmpty() || parts == null || entries == null || entries.isEmpty()) return null;
        List<OMOFormat.VertexGroupData> out = new ArrayList<>();
        for (OMOFormat.VertexGroupData group : groups) {
            Map<String, OMOFormat.GroupPart> byPart = new HashMap<>();
            for (OMOFormat.GroupPart gp : group.parts()) byPart.put(gp.partId(), gp);
            List<OMOFormat.GroupPart> written = new ArrayList<>();
            for (OMOFormat.PartEntry entry : entries) {
                OMOFormat.GroupPart gp = byPart.get(entry.id());
                if (gp == null) continue;
                Map<Integer, Integer> rowOf = rowIndex(gp.vertices());
                float[] weights = gp.weights();
                List<int[]> rows = remap(parts, entry, rowOf);
                if (rows.isEmpty()) continue;
                int[] v = new int[rows.size()];
                float[] w = new float[rows.size()];
                for (int i = 0; i < v.length; i++) {
                    v[i] = rows.get(i)[0];
                    w[i] = weights[rows.get(i)[1]];
                }
                written.add(new OMOFormat.GroupPart(entry.id(), v, w));
            }
            // A group keeps its slot even when no row survives (an empty group made in the editor, #172).
            out.add(new OMOFormat.VertexGroupData(group.name(), written));
        }
        return out.isEmpty() ? null : out;
    }

    /** The morphs in the layout of the part entries being written, or null when none survives. */
    public List<OMOFormat.MorphData> morphsInSavedLayout(ModelPartManager parts, List<OMOFormat.PartEntry> entries) {
        if (morphs.isEmpty() || parts == null || entries == null || entries.isEmpty()) return null;
        List<OMOFormat.MorphData> out = new ArrayList<>();
        for (OMOFormat.MorphData morph : morphs) {
            Map<String, OMOFormat.MorphPart> byPart = new HashMap<>();
            for (OMOFormat.MorphPart mp : morph.parts()) byPart.put(mp.partId(), mp);
            List<OMOFormat.MorphPart> written = new ArrayList<>();
            for (OMOFormat.PartEntry entry : entries) {
                OMOFormat.MorphPart mp = byPart.get(entry.id());
                if (mp == null) continue;
                Map<Integer, Integer> rowOf = rowIndex(mp.vertices());
                float[] deltas = mp.deltas();
                float[] normals = mp.normals();
                List<int[]> rows = remap(parts, entry, rowOf);
                if (rows.isEmpty()) continue;
                int[] v = new int[rows.size()];
                float[] d = new float[rows.size() * 3];
                float[] n = normals == null ? null : new float[rows.size() * 3];
                for (int i = 0; i < v.length; i++) {
                    int src = rows.get(i)[1];
                    v[i] = rows.get(i)[0];
                    System.arraycopy(deltas, src * 3, d, i * 3, 3);
                    if (n != null) System.arraycopy(normals, src * 3, n, i * 3, 3);
                }
                written.add(new OMOFormat.MorphPart(entry.id(), v, d, n));
            }
            // A morph keeps its slot even when no row survives (drivers address morphs by name).
            out.add(new OMOFormat.MorphData(morph.name(), morph.defaultWeight(), morph.min(), morph.max(), written));
        }
        return out.isEmpty() ? null : out;
    }

    private static Map<Integer, Integer> rowIndex(int[] vertices) {
        Map<Integer, Integer> rowOf = new HashMap<>();
        for (int i = 0; i < vertices.length; i++) rowOf.putIfAbsent(vertices[i], i);
        return rowOf;
    }

    /** {written local vertex, source row} for every written vertex of the entry's part that has a row. */
    private static List<int[]> remap(ModelPartManager parts, OMOFormat.PartEntry entry, Map<Integer, Integer> rowOf) {
        List<int[]> rows = new ArrayList<>();
        if (entry.vertexCount() <= 0) return rows;
        MeshRange range = parts.getPartById(entry.id()).map(ModelPartDescriptor::meshRange).orElse(null);
        if (range == null || range.vertexCount() <= 0) return rows;
        for (int local = 0; local < entry.vertexCount(); local++) {
            int saved = entry.vertexStart() + local;
            for (int soupIndex : parts.resolveCombinedVertex(saved)) {
                if (!range.containsVertex(soupIndex)) continue;
                Integer row = rowOf.get(range.toLocalVertex(soupIndex));
                if (row != null) rows.add(new int[] {local, row});
                break;
            }
        }
        return rows;
    }
}
