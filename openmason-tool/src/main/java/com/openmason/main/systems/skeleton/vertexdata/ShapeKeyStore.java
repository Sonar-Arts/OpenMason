package com.openmason.main.systems.skeleton.vertexdata;

import com.openmason.engine.format.omo.OMOFormat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Session-level shape keys (morph targets, OMO 1.9 {@code morphs}, OpenMason #171) of the loaded model, in their
 * file form: an ordered list of {@link OMOFormat.MorphData} values (name, default, min, max, and per PART the sparse
 * rows {part-local vertex, MODEL-space position delta, optional normal delta}). The values are immutable, so an
 * undo snapshot is the list itself. Rows are per part over the part's own vertex order (like {@code SkinStore}), so
 * edits of other parts never shift them; a topology edit of the part remaps them by nearest vertex
 * ({@link VertexDataAuthoring#reconcile}).
 *
 * <p>Thin by design (Java feature freeze): no deformation math lives here - previews and the runtime apply the
 * targets natively ({@code cenda/rig.h} {@code cr_morphs}). This class only validates names / ranges and replaces
 * values. Candidate for native: an editable morph document with its own history, like {@code cm_author}.
 * Main thread only.
 */
public final class ShapeKeyStore {

    /** Undo snapshot: the target list (immutable values). */
    public record Snapshot(List<OMOFormat.MorphData> morphs) {
        public Snapshot {
            morphs = List.copyOf(morphs);
        }
    }

    /** Blender's shape-key slider range limit. */
    public static final float RANGE_LIMIT = 10f;

    private List<OMOFormat.MorphData> morphs = List.of();
    private final List<Runnable> listeners = new ArrayList<>();

    /** Fired after every change (edits, load, clear, undo/redo restore, topology remap). */
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

    /** The targets in order. */
    public List<OMOFormat.MorphData> morphs() {
        return morphs;
    }

    public boolean isEmpty() {
        return morphs.isEmpty();
    }

    public int size() {
        return morphs.size();
    }

    /** Index of a target by name (exact, then case-insensitive; a leading {@code morph:} is accepted); -1 = none. */
    public int indexOf(String name) {
        if (name == null) return -1;
        String n = name.regionMatches(true, 0, "morph:", 0, 6) ? name.substring(6) : name;
        for (int i = 0; i < morphs.size(); i++) if (morphs.get(i).name().equals(n)) return i;
        for (int i = 0; i < morphs.size(); i++) if (morphs.get(i).name().equalsIgnoreCase(n)) return i;
        return -1;
    }

    /** The target, or null. */
    public OMOFormat.MorphData get(String name) {
        int i = indexOf(name);
        return i < 0 ? null : morphs.get(i);
    }

    /** Rows a target holds over every part. */
    public static int rowCount(OMOFormat.MorphData m) {
        int n = 0;
        for (OMOFormat.MorphPart p : m.parts()) n += p.vertices().length;
        return n;
    }

    /** The part ids any target has rows for. */
    public List<String> partIds() {
        List<String> out = new ArrayList<>();
        for (OMOFormat.MorphData m : morphs) {
            for (OMOFormat.MorphPart p : m.parts()) if (!out.contains(p.partId())) out.add(p.partId());
        }
        return out;
    }

    // ---- edits -----------------------------------------------------------------------------------

    /** Replaces everything (a model load; null = none). */
    public void load(List<OMOFormat.MorphData> data) {
        morphs = data == null ? List.of() : List.copyOf(data);
        fire();
    }

    public void clear() {
        load(null);
    }

    /**
     * Adds a target at the end (or replaces the rows of the same-named one when {@code replace}). Rows with an
     * all-zero delta are dropped (the runtime does the same). Returns the target's index.
     */
    public int add(String name, float defaultWeight, float min, float max, List<OMOFormat.MorphPart> parts,
                   boolean replace) {
        String n = checkName(name);
        checkRange(defaultWeight, min, max);
        List<OMOFormat.MorphPart> rows = sparse(parts);
        int existing = indexOf(n);
        List<OMOFormat.MorphData> next = new ArrayList<>(morphs);
        if (existing >= 0) {
            if (!replace) throw new IllegalArgumentException("a shape key named '" + morphs.get(existing).name() + "' exists");
            OMOFormat.MorphData old = morphs.get(existing);
            next.set(existing, new OMOFormat.MorphData(old.name(), old.defaultWeight(), old.min(), old.max(), rows));
            set(next);
            return existing;
        }
        next.add(new OMOFormat.MorphData(n, defaultWeight, min, max, rows));
        set(next);
        return next.size() - 1;
    }

    /** Replaces (or, with {@code merge}, overwrites per vertex) one part's rows of a target. */
    public void setPartRows(String name, OMOFormat.MorphPart rows, boolean merge) {
        int i = require(name);
        OMOFormat.MorphData m = morphs.get(i);
        List<OMOFormat.MorphPart> parts = new ArrayList<>();
        OMOFormat.MorphPart prior = null;
        for (OMOFormat.MorphPart p : m.parts()) {
            if (p.partId().equals(rows.partId())) prior = p;
            else parts.add(p);
        }
        OMOFormat.MorphPart written = merge && prior != null ? mergeRows(prior, rows) : rows;
        parts.add(written);
        replaceAt(i, new OMOFormat.MorphData(m.name(), m.defaultWeight(), m.min(), m.max(), sparse(parts)));
    }

    public void remove(String name) {
        int i = require(name);
        List<OMOFormat.MorphData> next = new ArrayList<>(morphs);
        next.remove(i);
        set(next);
    }

    public void rename(String name, String newName) {
        int i = require(name);
        String n = checkName(newName);
        int clash = indexOf(n);
        if (clash >= 0 && clash != i) throw new IllegalArgumentException("a shape key named '" + n + "' exists");
        OMOFormat.MorphData m = morphs.get(i);
        replaceAt(i, new OMOFormat.MorphData(n, m.defaultWeight(), m.min(), m.max(), m.parts()));
    }

    /** Sets default / min / max (null = keep). */
    public void setParams(String name, Float defaultWeight, Float min, Float max) {
        int i = require(name);
        OMOFormat.MorphData m = morphs.get(i);
        float d = defaultWeight != null ? defaultWeight : m.defaultWeight();
        float lo = min != null ? min : m.min();
        float hi = max != null ? max : m.max();
        checkRange(d, lo, hi);
        replaceAt(i, new OMOFormat.MorphData(m.name(), d, lo, hi, m.parts()));
    }

    /** Moves a target to {@code index} (clamped). */
    public void move(String name, int index) {
        int i = require(name);
        List<OMOFormat.MorphData> next = new ArrayList<>(morphs);
        OMOFormat.MorphData m = next.remove(i);
        next.add(Math.max(0, Math.min(next.size(), index)), m);
        set(next);
    }

    // ---- topology (index remapping only; called by VertexDataAuthoring.reconcile) ----------------

    /**
     * A part's vertices changed: new vertex v takes the rows of old vertex {@code source[v]} (-1 = none). Silent
     * (no listener call): it runs inside a reconcile. Returns true when anything changed.
     */
    boolean remapPart(String partId, int[] source) {
        boolean changed = false;
        List<OMOFormat.MorphData> next = new ArrayList<>(morphs.size());
        for (OMOFormat.MorphData m : morphs) {
            List<OMOFormat.MorphPart> parts = new ArrayList<>(m.parts().size());
            for (OMOFormat.MorphPart p : m.parts()) {
                if (!p.partId().equals(partId)) {
                    parts.add(p);
                    continue;
                }
                parts.add(remapRows(p, source));
                changed = true;
            }
            next.add(new OMOFormat.MorphData(m.name(), m.defaultWeight(), m.min(), m.max(), sparse(parts)));
        }
        if (changed) morphs = List.copyOf(next);
        return changed;
    }

    /** A part was deleted: its rows go (the targets stay - drivers address them by name). Silent. */
    boolean dropPart(String partId) {
        boolean changed = false;
        List<OMOFormat.MorphData> next = new ArrayList<>(morphs.size());
        for (OMOFormat.MorphData m : morphs) {
            List<OMOFormat.MorphPart> parts = new ArrayList<>(m.parts());
            changed |= parts.removeIf(p -> p.partId().equals(partId));
            next.add(new OMOFormat.MorphData(m.name(), m.defaultWeight(), m.min(), m.max(), parts));
        }
        if (changed) morphs = List.copyOf(next);
        return changed;
    }

    static OMOFormat.MorphPart remapRows(OMOFormat.MorphPart p, int[] source) {
        int[] v = p.vertices();
        float[] d = p.deltas();
        float[] n = p.normals();
        Map<Integer, Integer> rowOf = new HashMap<>();
        for (int r = 0; r < v.length; r++) rowOf.putIfAbsent(v[r], r);
        List<Integer> outV = new ArrayList<>();
        List<Integer> outRow = new ArrayList<>();
        for (int nv = 0; nv < source.length; nv++) {
            Integer r = source[nv] < 0 ? null : rowOf.get(source[nv]);
            if (r == null) continue;
            outV.add(nv);
            outRow.add(r);
        }
        int[] vv = new int[outV.size()];
        float[] dd = new float[vv.length * 3];
        float[] nn = n == null ? null : new float[vv.length * 3];
        for (int k = 0; k < vv.length; k++) {
            vv[k] = outV.get(k);
            int r = outRow.get(k);
            System.arraycopy(d, r * 3, dd, k * 3, 3);
            if (nn != null) System.arraycopy(n, r * 3, nn, k * 3, 3);
        }
        return new OMOFormat.MorphPart(p.partId(), vv, dd, nn);
    }

    // ---- undo ------------------------------------------------------------------------------------

    public Snapshot snapshot() {
        return new Snapshot(morphs);
    }

    public void restore(Snapshot snapshot) {
        morphs = List.copyOf(snapshot.morphs());
        fire();
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private void set(List<OMOFormat.MorphData> next) {
        morphs = List.copyOf(next);
        fire();
    }

    private void replaceAt(int i, OMOFormat.MorphData m) {
        List<OMOFormat.MorphData> next = new ArrayList<>(morphs);
        next.set(i, m);
        set(next);
    }

    private int require(String name) {
        int i = indexOf(name);
        if (i < 0) throw new IllegalArgumentException("no shape key named '" + name + "'");
        return i;
    }

    static String checkName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.regionMatches(true, 0, "morph:", 0, 6)) n = n.substring(6).trim();
        if (n.isEmpty()) throw new IllegalArgumentException("a shape key needs a name");
        if (n.toLowerCase(Locale.ROOT).equals("basis")) {
            throw new IllegalArgumentException("'Basis' is the mesh itself, not a shape key");
        }
        return n;
    }

    static void checkRange(float defaultWeight, float min, float max) {
        if (!Float.isFinite(defaultWeight) || !Float.isFinite(min) || !Float.isFinite(max)) {
            throw new IllegalArgumentException("default, min and max must be finite");
        }
        if (min > max) throw new IllegalArgumentException("min " + min + " is above max " + max);
        if (min < -RANGE_LIMIT || max > RANGE_LIMIT) {
            throw new IllegalArgumentException("the range is limited to -" + RANGE_LIMIT + " .. " + RANGE_LIMIT);
        }
    }

    /** Drops all-zero rows and empty parts (sparse, like the runtime). */
    static List<OMOFormat.MorphPart> sparse(List<OMOFormat.MorphPart> parts) {
        List<OMOFormat.MorphPart> out = new ArrayList<>();
        if (parts == null) return out;
        for (OMOFormat.MorphPart p : parts) {
            int[] v = p.vertices();
            float[] d = p.deltas();
            float[] n = p.normals();
            for (float f : d) {
                if (!Float.isFinite(f)) throw new IllegalArgumentException("deltas must be finite");
            }
            List<Integer> keep = new ArrayList<>();
            for (int r = 0; r < v.length; r++) {
                if (v[r] < 0) throw new IllegalArgumentException("negative vertex " + v[r]);
                boolean zero = d[r * 3] == 0f && d[r * 3 + 1] == 0f && d[r * 3 + 2] == 0f
                        && (n == null || (n[r * 3] == 0f && n[r * 3 + 1] == 0f && n[r * 3 + 2] == 0f));
                if (!zero) keep.add(r);
            }
            if (keep.isEmpty()) continue;
            if (keep.size() == v.length) {
                out.add(p);
                continue;
            }
            int[] vv = new int[keep.size()];
            float[] dd = new float[vv.length * 3];
            float[] nn = n == null ? null : new float[vv.length * 3];
            for (int k = 0; k < vv.length; k++) {
                int r = keep.get(k);
                vv[k] = v[r];
                System.arraycopy(d, r * 3, dd, k * 3, 3);
                if (nn != null) System.arraycopy(n, r * 3, nn, k * 3, 3);
            }
            out.add(new OMOFormat.MorphPart(p.partId(), vv, dd, nn));
        }
        return out;
    }

    /** {@code b}'s rows overwrite {@code a}'s per vertex; the rest of {@code a} stays. */
    private static OMOFormat.MorphPart mergeRows(OMOFormat.MorphPart a, OMOFormat.MorphPart b) {
        java.util.TreeMap<Integer, float[]> rows = new java.util.TreeMap<>();
        boolean normals = a.normals() != null || b.normals() != null;
        put(rows, a, normals);
        put(rows, b, normals);
        int[] v = new int[rows.size()];
        float[] d = new float[v.length * 3];
        float[] n = normals ? new float[v.length * 3] : null;
        int k = 0;
        for (Map.Entry<Integer, float[]> e : rows.entrySet()) {
            v[k] = e.getKey();
            System.arraycopy(e.getValue(), 0, d, k * 3, 3);
            if (n != null) System.arraycopy(e.getValue(), 3, n, k * 3, 3);
            k++;
        }
        return new OMOFormat.MorphPart(a.partId(), v, d, n);
    }

    private static void put(Map<Integer, float[]> rows, OMOFormat.MorphPart p, boolean normals) {
        int[] v = p.vertices();
        float[] d = p.deltas();
        float[] n = p.normals();
        for (int r = 0; r < v.length; r++) {
            float[] row = new float[normals ? 6 : 3];
            System.arraycopy(d, r * 3, row, 0, 3);
            if (n != null) System.arraycopy(n, r * 3, row, 3, 3);
            rows.put(v[r], row);
        }
    }
}
