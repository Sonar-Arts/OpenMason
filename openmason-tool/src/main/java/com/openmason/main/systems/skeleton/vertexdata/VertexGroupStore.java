package com.openmason.main.systems.skeleton.vertexdata;

import com.openmason.engine.format.omo.OMOFormat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Session-level vertex groups (OMO 1.9 {@code vertexGroups}, OpenMason #172) of the loaded model, in their file
 * form: an ordered list of {@link OMOFormat.VertexGroupData} values (name, and per PART the weighted part-local
 * vertices). Membership follows the runtime mask rule (cenda/model_mask.h): a vertex is IN a group when its weight
 * is above 0. Values are immutable, so an undo snapshot is the list itself; a topology edit of a part remaps its
 * rows by nearest vertex ({@link VertexDataAuthoring#reconcile}).
 *
 * <p>Thin by design (Java feature freeze): the mask plans that hide groups are native ({@code co_mask_plan});
 * this class only validates names and replaces values. Candidate for native together with {@link ShapeKeyStore}.
 * Main thread only.
 */
public final class VertexGroupStore {

    /** Undo snapshot: the group list (immutable values). */
    public record Snapshot(List<OMOFormat.VertexGroupData> groups) {
        public Snapshot {
            groups = List.copyOf(groups);
        }
    }

    private List<OMOFormat.VertexGroupData> groups = List.of();
    private final List<Runnable> listeners = new ArrayList<>();

    /** Fired after every change (edits, load, clear, undo/redo restore). */
    public void addListener(Runnable listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    private void fire() {
        for (Runnable l : List.copyOf(listeners)) l.run();
    }

    // ---- read ------------------------------------------------------------------------------------

    public List<OMOFormat.VertexGroupData> groups() {
        return groups;
    }

    public boolean isEmpty() {
        return groups.isEmpty();
    }

    public int size() {
        return groups.size();
    }

    /** Index by name (exact, then case-insensitive); -1 = none. */
    public int indexOf(String name) {
        if (name == null) return -1;
        for (int i = 0; i < groups.size(); i++) if (groups.get(i).name().equals(name)) return i;
        for (int i = 0; i < groups.size(); i++) if (groups.get(i).name().equalsIgnoreCase(name)) return i;
        return -1;
    }

    public OMOFormat.VertexGroupData get(String name) {
        int i = indexOf(name);
        return i < 0 ? null : groups.get(i);
    }

    /** Rows of a group over every part. */
    public static int rowCount(OMOFormat.VertexGroupData g) {
        int n = 0;
        for (OMOFormat.GroupPart p : g.parts()) n += p.vertices().length;
        return n;
    }

    /** The weights of a group's part as part-local vertex -> weight (sorted), empty when none. */
    public static Map<Integer, Float> rowsOf(OMOFormat.VertexGroupData g, String partId) {
        Map<Integer, Float> out = new TreeMap<>();
        if (g == null) return out;
        for (OMOFormat.GroupPart p : g.parts()) {
            if (!p.partId().equals(partId)) continue;
            int[] v = p.vertices();
            float[] w = p.weights();
            for (int r = 0; r < v.length; r++) out.put(v[r], w[r]);
        }
        return out;
    }

    // ---- edits -----------------------------------------------------------------------------------

    public void load(List<OMOFormat.VertexGroupData> data) {
        groups = data == null ? List.of() : List.copyOf(data);
        fire();
    }

    public void clear() {
        load(null);
    }

    /** Adds an empty group at the end; returns its index. */
    public int add(String name) {
        String n = checkName(name);
        if (indexOf(n) >= 0) throw new IllegalArgumentException("a vertex group named '" + n + "' exists");
        List<OMOFormat.VertexGroupData> next = new ArrayList<>(groups);
        next.add(new OMOFormat.VertexGroupData(n, List.of()));
        set(next);
        return next.size() - 1;
    }

    public void remove(String name) {
        int i = require(name);
        List<OMOFormat.VertexGroupData> next = new ArrayList<>(groups);
        next.remove(i);
        set(next);
    }

    public void rename(String name, String newName) {
        int i = require(name);
        String n = checkName(newName);
        int clash = indexOf(n);
        if (clash >= 0 && clash != i) throw new IllegalArgumentException("a vertex group named '" + n + "' exists");
        replaceAt(i, new OMOFormat.VertexGroupData(n, groups.get(i).parts()));
    }

    public void move(String name, int index) {
        int i = require(name);
        List<OMOFormat.VertexGroupData> next = new ArrayList<>(groups);
        OMOFormat.VertexGroupData g = next.remove(i);
        next.add(Math.max(0, Math.min(next.size(), index)), g);
        set(next);
    }

    /**
     * Assigns part-local vertices with a weight (clamped to [0, 1]); an assigned vertex's weight is replaced.
     * {@code weights} null = {@code weight} for all, else one per vertex. Returns the rows written.
     */
    public int assign(String name, String partId, int[] vertices, float weight, float[] weights) {
        int i = require(name);
        Objects.requireNonNull(partId, "partId");
        if (vertices == null || vertices.length == 0) return 0;
        if (weights != null && weights.length != vertices.length) {
            throw new IllegalArgumentException("weights holds one value per vertex");
        }
        OMOFormat.VertexGroupData g = groups.get(i);
        Map<Integer, Float> rows = rowsOf(g, partId);
        for (int k = 0; k < vertices.length; k++) {
            if (vertices[k] < 0) throw new IllegalArgumentException("negative vertex " + vertices[k]);
            float w = weights != null ? weights[k] : weight;
            if (!Float.isFinite(w)) throw new IllegalArgumentException("weights must be finite");
            rows.put(vertices[k], Math.max(0f, Math.min(1f, w)));
        }
        replaceAt(i, withPart(g, partId, rows));
        return vertices.length;
    }

    /** Removes part-local vertices from a group ({@code vertices} null = the whole part); returns rows removed. */
    public int unassign(String name, String partId, int[] vertices) {
        int i = require(name);
        OMOFormat.VertexGroupData g = groups.get(i);
        Map<Integer, Float> rows = rowsOf(g, partId);
        int before = rows.size();
        if (vertices == null) rows.clear();
        else for (int v : vertices) rows.remove(v);
        if (rows.size() == before) return 0;
        replaceAt(i, withPart(g, partId, rows));
        return before - rows.size();
    }

    // ---- topology (index remapping only) ---------------------------------------------------------

    /** New vertex v takes the weight of old vertex {@code source[v]} (-1 = none). Silent; true when changed. */
    boolean remapPart(String partId, int[] source) {
        boolean changed = false;
        List<OMOFormat.VertexGroupData> next = new ArrayList<>(groups.size());
        for (OMOFormat.VertexGroupData g : groups) {
            boolean has = g.parts().stream().anyMatch(p -> p.partId().equals(partId));
            if (!has) {
                next.add(g);
                continue;
            }
            Map<Integer, Float> old = rowsOf(g, partId);
            Map<Integer, Float> rows = new TreeMap<>();
            for (int nv = 0; nv < source.length; nv++) {
                Float w = source[nv] < 0 ? null : old.get(source[nv]);
                if (w != null) rows.put(nv, w);
            }
            next.add(withPart(g, partId, rows));
            changed = true;
        }
        if (changed) groups = List.copyOf(next);
        return changed;
    }

    /** A part was deleted: its rows go (the groups stay). Silent. */
    boolean dropPart(String partId) {
        boolean changed = false;
        List<OMOFormat.VertexGroupData> next = new ArrayList<>(groups.size());
        for (OMOFormat.VertexGroupData g : groups) {
            List<OMOFormat.GroupPart> parts = new ArrayList<>(g.parts());
            changed |= parts.removeIf(p -> p.partId().equals(partId));
            next.add(new OMOFormat.VertexGroupData(g.name(), parts));
        }
        if (changed) groups = List.copyOf(next);
        return changed;
    }

    /** The part ids any group has rows for. */
    public List<String> partIds() {
        List<String> out = new ArrayList<>();
        for (OMOFormat.VertexGroupData g : groups) {
            for (OMOFormat.GroupPart p : g.parts()) if (!out.contains(p.partId())) out.add(p.partId());
        }
        return out;
    }

    // ---- undo ------------------------------------------------------------------------------------

    public Snapshot snapshot() {
        return new Snapshot(groups);
    }

    public void restore(Snapshot snapshot) {
        groups = List.copyOf(snapshot.groups());
        fire();
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static OMOFormat.VertexGroupData withPart(OMOFormat.VertexGroupData g, String partId,
                                                      Map<Integer, Float> rows) {
        List<OMOFormat.GroupPart> parts = new ArrayList<>();
        boolean placed = false;
        for (OMOFormat.GroupPart p : g.parts()) {
            if (!p.partId().equals(partId)) {
                parts.add(p);
                continue;
            }
            if (!placed && !rows.isEmpty()) parts.add(part(partId, rows));
            placed = true;
        }
        if (!placed && !rows.isEmpty()) parts.add(part(partId, rows));
        return new OMOFormat.VertexGroupData(g.name(), parts);
    }

    private static OMOFormat.GroupPart part(String partId, Map<Integer, Float> rows) {
        int[] v = new int[rows.size()];
        float[] w = new float[v.length];
        int k = 0;
        for (Map.Entry<Integer, Float> e : new TreeMap<>(rows).entrySet()) {
            v[k] = e.getKey();
            w[k] = e.getValue();
            k++;
        }
        return new OMOFormat.GroupPart(partId, v, w);
    }

    private void set(List<OMOFormat.VertexGroupData> next) {
        groups = List.copyOf(next);
        fire();
    }

    private void replaceAt(int i, OMOFormat.VertexGroupData g) {
        List<OMOFormat.VertexGroupData> next = new ArrayList<>(groups);
        next.set(i, g);
        set(next);
    }

    private int require(String name) {
        int i = indexOf(name);
        if (i < 0) throw new IllegalArgumentException("no vertex group named '" + name + "'");
        return i;
    }

    static String checkName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) throw new IllegalArgumentException("a vertex group needs a name");
        return n;
    }
}
