package com.openmason.engine.modeling.shading;

import com.openmason.engine.format.omo.CendaShadingStore;
import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.format.omo.OmoDocument;
import com.openmason.engine.modeling.editable.RenderMesh;
import com.openmason.engine.rendering.model.draw.ModelNormalGlsl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The editable model's smooth shading (OpenMason #169): thin wiring over the native shading store
 * ({@link CendaShadingStore}, {@code co_shading_*}), one store per PART on the part's own soup (part-local corner
 * indices and face ids = global face id minus the part's {@code faceStart}), exactly as the runtime groups normals
 * by part. Every shading rule (welding, key resolution, Blender corner normals, reconcile) is native; this class
 * slices the render mesh per part, tells the stores when positions moved or the topology changed, and assembles the
 * per-corner GPU normals with the runtime's FLAT rule ({@code EntityModelLoader.cornerNormals}).
 *
 * <p>Keeping the stores in step with the mesh: every {@link #sync()} compares each part's soup with the one the
 * store last saw. Same layout (corner count and per-corner face) = only positions changed (a drag, a part
 * transform): the moved welded positions are handed to {@code co_shading_move_vertices}. Another layout = a
 * topology edit: {@code co_shading_reconcile}. The rebuild pipeline syncs before and after every derivation
 * ({@link #beforeDerive()} / {@link #afterDerive()}), draws and edits sync lazily. A model whose parts have no store
 * costs nothing: no slicing, no native call, no GPU normals (flat, exactly as before).
 *
 * <p>Ownership of a face: the part with the greatest {@code faceStart <= faceId} (faces appended after the last
 * part by an edit stay with it); faces before every part, or a model without parts, use the {@link #UNOWNED} store
 * (global face ids).
 */
public final class ModelShading {

    private static final Logger logger = LoggerFactory.getLogger(ModelShading.class);

    /** Store key of faces no part owns (a partless model). */
    public static final String UNOWNED = "";

    /** A part's face id range in the render mesh. */
    public record PartSpan(String partId, int faceStart, int faceCount) {
    }

    /** Per-key shading summary ({@code shading_get}). */
    public record Summary(String key, int faces, int smoothFaces, int sharpEdges, int customNormals,
                          float autoSmoothDegrees) {
    }

    /** An exact copy of every store (undo unit). Immutable from the outside. */
    public static final class Snapshot {
        private final Map<String, CendaShadingStore> stores;

        private Snapshot(Map<String, CendaShadingStore> stores) {
            this.stores = stores;
        }

        /** True when both hold the same shading (stores that hold nothing count as absent). */
        public boolean contentEquals(Snapshot other) {
            if (other == this) return true;
            if (other == null) return isEmpty();
            Set<String> keys = new HashSet<>(stores.keySet());
            keys.addAll(other.stores.keySet());
            for (String k : keys) {
                CendaShadingStore a = stores.get(k);
                CendaShadingStore b = other.stores.get(k);
                if (a == null) {
                    if (!b.isEmpty()) return false;
                } else if (b == null) {
                    if (!a.isEmpty()) return false;
                } else if (!a.contentEquals(b)) {
                    return false;
                }
            }
            return true;
        }

        public boolean isEmpty() {
            for (CendaShadingStore s : stores.values()) {
                if (!s.isEmpty()) return false;
            }
            return true;
        }
    }

    private final Supplier<RenderMesh> mesh;
    private final Supplier<List<PartSpan>> spans;
    private Runnable changeListener = () -> { };

    private final Map<String, CendaShadingStore> stores = new LinkedHashMap<>();
    private final Map<String, Baseline> baselines = new HashMap<>();
    private final Map<String, CachedNormals> cache = new HashMap<>();
    private List<PartSpan> lastSpans = List.of();

    /**
     * @param mesh  the current render mesh (its positions are live: a drag updates them in place)
     * @param spans the parts' current face ranges
     */
    public ModelShading(Supplier<RenderMesh> mesh, Supplier<List<PartSpan>> spans) {
        this.mesh = mesh;
        this.spans = spans;
    }

    /** Called whenever the normals may have changed (shading edits, loads, restores). */
    public void setChangeListener(Runnable listener) {
        this.changeListener = listener != null ? listener : () -> { };
    }

    /** True when the native store is usable. */
    public static boolean isAvailable() {
        return CendaShadingStore.isAvailable();
    }

    // =========================================================================
    // Keeping the stores in step with the mesh
    // =========================================================================

    /** Before a derivation: the stores catch up with the current (outgoing) mesh under the spans it was cut with. */
    public void beforeDerive() {
        sync(lastSpans);
    }

    /** After a derivation: the stores follow the new mesh (moved positions or a new topology). */
    public void afterDerive() {
        sync();
    }

    /** Brings every store in step with the current mesh and part ranges. */
    public void sync() {
        sync(currentSpans());
    }

    private void sync(List<PartSpan> partSpans) {
        lastSpans = partSpans;
        if (stores.isEmpty()) {
            baselines.clear();
            return;
        }
        RenderMesh rm = mesh.get();
        if (rm == null) return;
        for (Slice slice : slice(rm, partSpans).values()) {
            if (slice.cornerCount() == 0) continue; // hidden part: keep its keys as they are
            CendaShadingStore store = stores.get(slice.key);
            Baseline base = baselines.get(slice.key);
            if (store != null && base != null) {
                if (base.sameLayout(slice)) {
                    moveChanged(store, base, slice);
                } else {
                    long[] dropped = store.reconcile(slice.soup);
                    if (dropped[0] + dropped[1] + dropped[2] > 0) {
                        logger.debug("Shading of '{}' reconciled: dropped {} smooth, {} sharp, {} custom",
                                slice.key, dropped[0], dropped[1], dropped[2]);
                    }
                }
            }
            baselines.put(slice.key, new Baseline(slice));
        }
    }

    private static void moveChanged(CendaShadingStore store, Baseline base, Slice slice) {
        float[] now = slice.soup.positions();
        float[] was = base.positions;
        if (Arrays.equals(now, was)) return;
        float[] oldP = new float[now.length];
        float[] newP = new float[now.length];
        Set<Long> seen = new HashSet<>();
        int n = 0;
        for (int c = 0; c < now.length / 3; c++) {
            int o = c * 3;
            if (now[o] == was[o] && now[o + 1] == was[o + 1] && now[o + 2] == was[o + 2]) continue;
            long key = (((long) Float.floatToIntBits(was[o]) * 31L + Float.floatToIntBits(was[o + 1])) * 31L)
                    + Float.floatToIntBits(was[o + 2]);
            if (!seen.add(key)) continue;
            System.arraycopy(was, o, oldP, n * 3, 3);
            System.arraycopy(now, o, newP, n * 3, 3);
            n++;
        }
        if (n > 0) {
            store.moveVertices(Arrays.copyOf(oldP, n * 3), Arrays.copyOf(newP, n * 3));
        }
    }

    /** Clears every store (a new / freshly loaded model without shading). */
    public void clear() {
        boolean had = !stores.isEmpty();
        stores.values().forEach(CendaShadingStore::close);
        stores.clear();
        baselines.clear();
        cache.clear();
        if (had) changeListener.run();
    }

    // =========================================================================
    // Normals
    // =========================================================================

    /**
     * The render mesh's corner normals as octahedral snorm16 pairs ({@code ModelGpuMesh.updateNormals}): corners
     * of smooth faces (and corners with a custom normal) get the native corner normal, every other corner the
     * {@link ModelNormalGlsl#FLAT} marker (the renderer lights it with the drawn face's own normal, exactly as a
     * mesh without normals). Null when nothing is smooth anywhere (the model draws flat, as before shading).
     * Only parts whose soup or store changed are recomputed.
     */
    public short[] cornerNormals() {
        if (stores.isEmpty()) return null;
        sync();
        RenderMesh rm = mesh.get();
        if (rm == null || rm.cornerCount() == 0) return null;
        short[] out = null;
        for (Slice slice : slice(rm, currentSpans()).values()) {
            CendaShadingStore store = stores.get(slice.key);
            if (store == null || slice.cornerCount() == 0) continue;
            int[] smooth = store.smoothWords();
            boolean anySmooth = false;
            for (int w : smooth) anySmooth |= w != 0;
            int customCount = store.customNormalCount();
            if (!anySmooth && customCount == 0) continue;
            short[] local = normalsOf(slice, store);
            boolean[] custom = customCount == 0 ? null : customCorners(slice, store);
            if (out == null) {
                out = new short[rm.cornerCount() * 2];
                Arrays.fill(out, ModelNormalGlsl.FLAT);
            }
            for (int c = 0; c < slice.cornerCount(); c++) {
                int f = slice.cornerFace[c];
                boolean faceSmooth = f >= 0 && (f >> 5) < smooth.length && ((smooth[f >> 5] >>> (f & 31)) & 1) != 0;
                if (faceSmooth || (custom != null && custom[c])) {
                    int g = slice.globalCorners[c];
                    out[g * 2] = local[c * 2];
                    out[g * 2 + 1] = local[c * 2 + 1];
                }
            }
        }
        return out;
    }

    private short[] normalsOf(Slice slice, CendaShadingStore store) {
        CachedNormals cached = cache.get(slice.key);
        long revision = store.revision();
        if (cached != null && cached.revision == revision && cached.store == store
                && Arrays.equals(cached.positions, slice.soup.positions())
                && Arrays.equals(cached.indices, slice.soup.indices())
                && Arrays.equals(cached.faces, slice.soup.triFaceIds())) {
            return cached.oct;
        }
        short[] oct = store.compute(slice.soup).oct();
        cache.put(slice.key, new CachedNormals(store, revision, slice.soup.positions().clone(),
                slice.soup.indices(), slice.soup.triFaceIds(), oct));
        return oct;
    }

    /** Corners that carry a stored custom normal: same face, welded position within the store's epsilon. */
    private static boolean[] customCorners(Slice slice, CendaShadingStore store) {
        float eps = store.weldEpsilon();
        float epsSq = eps * eps;
        Map<Integer, List<CendaShadingStore.CustomNormal>> byFace = new HashMap<>();
        for (CendaShadingStore.CustomNormal n : store.customNormals()) {
            byFace.computeIfAbsent(n.face(), k -> new ArrayList<>()).add(n);
        }
        boolean[] out = new boolean[slice.cornerCount()];
        float[] p = slice.soup.positions();
        for (int c = 0; c < out.length; c++) {
            List<CendaShadingStore.CustomNormal> list = byFace.get(slice.cornerFace[c]);
            if (list == null) continue;
            for (CendaShadingStore.CustomNormal n : list) {
                float dx = p[c * 3] - n.x(), dy = p[c * 3 + 1] - n.y(), dz = p[c * 3 + 2] - n.z();
                if (dx * dx + dy * dy + dz * dz < epsSq) {
                    out[c] = true;
                    break;
                }
            }
        }
        return out;
    }

    // =========================================================================
    // Edits (thin: each resolves keys and makes one native call per store)
    // =========================================================================

    /** The store of a key, created (all flat) on first use. */
    public CendaShadingStore store(String key) {
        CendaShadingStore s = stores.get(key);
        if (s == null) {
            sync(); // record the baseline the new store starts from
            s = CendaShadingStore.create();
            stores.put(key, s);
            RenderMesh rm = mesh.get();
            if (rm != null) {
                Slice slice = slice(rm, currentSpans()).get(key);
                if (slice != null) baselines.put(key, new Baseline(slice));
            }
        }
        return s;
    }

    /** The store of a key, or null when it has none. */
    public CendaShadingStore existingStore(String key) {
        return stores.get(key);
    }

    /** Store keys (part ids, {@link #UNOWNED}) that currently own faces of the mesh. */
    public List<String> keys() {
        RenderMesh rm = mesh.get();
        if (rm == null) return List.of();
        List<String> out = new ArrayList<>();
        for (Slice s : slice(rm, currentSpans()).values()) {
            if (s.cornerCount() > 0) out.add(s.key);
        }
        return out;
    }

    /** The store key that owns a global face id. */
    public String keyOfFace(int faceId) {
        PartSpan owner = owner(sortedSpans(currentSpans()), faceId);
        return owner == null ? UNOWNED : owner.partId();
    }

    /** Shade faces (global face ids) smooth or flat. */
    public void setSmooth(int[] globalFaceIds, boolean smooth) {
        sync();
        for (Map.Entry<String, int[]> e : localFaces(globalFaceIds).entrySet()) {
            if (!smooth && !stores.containsKey(e.getKey())) continue; // already flat
            store(e.getKey()).setSmooth(e.getValue(), smooth);
        }
        changed();
    }

    /** Shade every face of the keys (null = every key) smooth or flat. */
    public void setSmoothAll(List<String> keys, boolean smooth) {
        setSmooth(facesOf(keys), smooth);
    }

    /**
     * Shade Auto Smooth: the faces (null = every face of the keys / of the model) become smooth and their parts
     * get the auto smooth angle ({@code <= 0} = off).
     */
    public void setAutoSmooth(List<String> keys, int[] globalFaceIds, float degrees) {
        int[] faces = globalFaceIds != null ? globalFaceIds : facesOf(keys);
        setSmooth(faces, true);
        Set<String> touched = new HashSet<>(localFaces(faces).keySet());
        if (keys != null) touched.addAll(keys);
        for (String k : touched) store(k).setAutoSmooth(degrees);
        changed();
    }

    /**
     * Marks or clears sharp edges given as positions: {@code edges} holds 6 floats per edge (a.xyz, b.xyz) in model
     * space; every key (null = all keys that own faces) records them (an edge no part has stays inert and is
     * dropped by the next reconcile, a shared seam becomes sharp on both sides).
     */
    public void markSharp(List<String> keys, float[] edges, boolean sharp) {
        if (edges == null || edges.length < 6) return;
        sync();
        int n = edges.length / 6;
        int[] pairs = new int[n * 2];
        for (int i = 0; i < pairs.length; i++) pairs[i] = i;
        for (String k : keys != null ? keys : keys()) {
            if (!sharp && !stores.containsKey(k)) continue;
            store(k).markSharp(edges, pairs, sharp);
        }
        changed();
    }

    /** Clears every sharp edge of the keys (null = all). */
    public void clearAllSharp(List<String> keys) {
        for (String k : keys != null ? keys : new ArrayList<>(stores.keySet())) {
            CendaShadingStore s = stores.get(k);
            if (s != null) s.clearAllSharp();
        }
        changed();
    }

    /** Clears the custom normals of faces (global ids; null = every face of the keys, null keys = all). */
    public void clearCustomNormals(List<String> keys, int[] globalFaceIds) {
        sync();
        if (globalFaceIds == null) {
            for (String k : keys != null ? keys : new ArrayList<>(stores.keySet())) {
                CendaShadingStore s = stores.get(k);
                if (s != null) s.clearCustomNormals(null);
            }
        } else {
            for (Map.Entry<String, int[]> e : localFaces(globalFaceIds).entrySet()) {
                CendaShadingStore s = stores.get(e.getKey());
                if (s != null) s.clearCustomNormals(e.getValue());
            }
        }
        changed();
    }

    /** Per-key summary of every key that owns faces or holds shading. */
    public List<Summary> summary() {
        sync();
        RenderMesh rm = mesh.get();
        Map<String, Slice> slices = rm == null ? Map.of() : slice(rm, currentSpans());
        Set<String> keys = new java.util.LinkedHashSet<>(slices.keySet());
        keys.addAll(stores.keySet());
        List<Summary> out = new ArrayList<>();
        for (String k : keys) {
            Slice slice = slices.get(k);
            CendaShadingStore s = stores.get(k);
            int faces = slice == null ? 0 : slice.faceIds().length;
            int smooth = 0;
            if (s != null && slice != null) {
                for (int f : slice.faceIds()) if (s.isSmooth(f)) smooth++;
            }
            if (slice == null && (s == null || s.isEmpty())) continue;
            out.add(new Summary(k, faces, smooth, s == null ? 0 : s.sharpEdgeCount(),
                    s == null ? 0 : s.customNormalCount(), s == null ? 0f : s.autoSmooth()));
        }
        return out;
    }

    /** True when any store holds shading. */
    public boolean hasShading() {
        for (CendaShadingStore s : stores.values()) {
            if (!s.isEmpty()) return true;
        }
        return false;
    }

    private void changed() {
        changeListener.run();
    }

    // =========================================================================
    // Undo
    // =========================================================================

    /** An exact copy of every store, in step with the current mesh. */
    public Snapshot capture() {
        sync();
        Map<String, CendaShadingStore> copy = new LinkedHashMap<>();
        stores.forEach((k, s) -> copy.put(k, s.copy()));
        return new Snapshot(Collections.unmodifiableMap(copy));
    }

    /**
     * Restores a {@link #capture()} (after the mesh it belongs to was restored): the stores become copies of the
     * snapshot's, and the next sync takes the current mesh as their baseline (no move / reconcile).
     */
    public void restore(Snapshot snapshot) {
        if (snapshot == null) return;
        stores.values().forEach(CendaShadingStore::close);
        stores.clear();
        snapshot.stores.forEach((k, s) -> stores.put(k, s.copy()));
        baselines.clear();
        cache.clear();
        rebaseline();
        changed();
    }

    /** Takes the current mesh as every store's baseline (the stores already match it). */
    private void rebaseline() {
        lastSpans = currentSpans();
        if (stores.isEmpty()) return;
        RenderMesh rm = mesh.get();
        if (rm == null) return;
        for (Slice slice : slice(rm, lastSpans).values()) {
            if (slice.cornerCount() > 0) baselines.put(slice.key, new Baseline(slice));
        }
    }

    // =========================================================================
    // OMO 1.9 load / save (saved layout <-> per-part stores)
    // =========================================================================

    /**
     * Replaces every store with the shading of a loaded file. The saved mesh, its part entries (null = a partless
     * file whose whole mesh is the part {@code fallbackKey}) and its shading section give each part its store
     * ({@code co_shading_from_doc}: keys by face id and welded position, so the editor's own re-soup does not
     * matter). Call after the parts are restored; null / empty shading just clears.
     */
    public void load(OMOFormat.MeshData saved, List<OMOFormat.PartEntry> entries, OMOFormat.ShadingData shading,
                     String fallbackKey) {
        clear();
        if (shading == null || shading.isEmpty() || saved == null || !isAvailable()) return;
        try (OmoDocument doc = OmoDocument.create()) {
            doc.setMesh(saved);
            if (entries != null && !entries.isEmpty()) doc.setParts(entries);
            doc.setShading(shading);
            if (entries == null || entries.isEmpty()) {
                putIfAny(fallbackKey != null ? fallbackKey : UNOWNED, CendaShadingStore.fromDocument(doc, null));
            } else {
                for (OMOFormat.PartEntry entry : entries) {
                    if (entry.vertexCount() <= 0) continue;
                    try {
                        putIfAny(entry.id(), CendaShadingStore.fromDocument(doc, entry));
                    } catch (RuntimeException e) {
                        // A part with ranges the mesh cannot hold (a corrupt part the loader skipped).
                        logger.warn("Shading of part '{}' not loaded: {}", entry.id(), e.getMessage());
                    }
                }
            }
        }
        rebaseline();
        changed();
    }

    private void putIfAny(String key, CendaShadingStore store) {
        if (store.isEmpty()) {
            store.close();
        } else {
            stores.put(key, store);
        }
    }

    /**
     * The shading section in the layout an OMO is WRITTEN in: {@code saved} is the exact mesh being written and
     * {@code entries} its part entries (null = written partless: the stores write the whole mesh). Each part's
     * store resolves its keys against that mesh ({@code co_shading_to_doc}). Null when nothing is stored.
     */
    public OMOFormat.ShadingData toSavedLayout(OMOFormat.MeshData saved, List<OMOFormat.PartEntry> entries) {
        sync();
        if (!hasShading() || saved == null || !isAvailable()) return null;
        try (OmoDocument doc = OmoDocument.create()) {
            doc.setMesh(saved);
            boolean partless = entries == null || entries.isEmpty();
            if (!partless) doc.setParts(entries);
            Set<String> entryIds = new HashSet<>();
            if (!partless) for (OMOFormat.PartEntry e : entries) entryIds.add(e.id());
            // Whole-document writes first (they replace the section), then each part's share.
            for (Map.Entry<String, CendaShadingStore> e : stores.entrySet()) {
                if (e.getValue().isEmpty()) continue;
                if (partless || !entryIds.contains(e.getKey())) {
                    if (partless || UNOWNED.equals(e.getKey())) e.getValue().writeTo(doc, null);
                }
            }
            if (!partless) {
                for (OMOFormat.PartEntry entry : entries) {
                    CendaShadingStore s = stores.get(entry.id());
                    if (s == null || s.isEmpty() || entry.vertexCount() <= 0) continue;
                    s.writeTo(doc, entry);
                }
            }
            return doc.shading();
        }
    }

    // =========================================================================
    // Slicing the render mesh per part
    // =========================================================================

    private List<PartSpan> currentSpans() {
        List<PartSpan> s = spans != null ? spans.get() : null;
        return s != null ? s : List.of();
    }

    private static List<PartSpan> sortedSpans(List<PartSpan> spans) {
        List<PartSpan> sorted = new ArrayList<>(spans);
        sorted.sort((a, b) -> Integer.compare(a.faceStart(), b.faceStart()));
        return sorted;
    }

    /** The span with the greatest faceStart <= faceId (ties: the later one with faces), or null. */
    private static PartSpan owner(List<PartSpan> sorted, int faceId) {
        PartSpan best = null;
        for (PartSpan s : sorted) {
            if (s.faceStart() > faceId) break;
            if (best == null || s.faceStart() > best.faceStart() || s.faceCount() > 0) best = s;
        }
        return best;
    }

    private Map<String, int[]> localFaces(int[] globalFaceIds) {
        Map<String, List<Integer>> grouped = new LinkedHashMap<>();
        List<PartSpan> sorted = sortedSpans(currentSpans());
        if (globalFaceIds != null) {
            for (int f : globalFaceIds) {
                PartSpan owner = owner(sorted, f);
                String key = owner == null ? UNOWNED : owner.partId();
                int local = owner == null ? f : f - owner.faceStart();
                grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(local);
            }
        }
        Map<String, int[]> out = new LinkedHashMap<>();
        grouped.forEach((k, v) -> out.put(k, v.stream().mapToInt(Integer::intValue).toArray()));
        return out;
    }

    /** Every live global face id of the keys (null = all). */
    private int[] facesOf(List<String> keys) {
        RenderMesh rm = mesh.get();
        if (rm == null) return new int[0];
        Set<Integer> out = new java.util.TreeSet<>();
        List<PartSpan> sorted = sortedSpans(currentSpans());
        for (int f : rm.triangleToFaceId()) {
            if (keys == null) {
                out.add(f);
            } else {
                PartSpan owner = owner(sorted, f);
                if (keys.contains(owner == null ? UNOWNED : owner.partId())) out.add(f);
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    /** One key's part of the render mesh. */
    private record Slice(String key, int[] globalCorners, int[] cornerFace, CendaShadingStore.Soup soup) {
        int cornerCount() {
            return globalCorners.length;
        }

        int[] faceIds() {
            int[] faces = soup.triFaceIds();
            return faces == null ? new int[0] : Arrays.stream(faces).distinct().toArray();
        }
    }

    private static Map<String, Slice> slice(RenderMesh rm, List<PartSpan> partSpans) {
        List<PartSpan> sorted = sortedSpans(partSpans);
        int tris = rm.triangleCount();
        int corners = rm.cornerCount();
        int[] triToFace = rm.triangleToFaceId();
        int[] indices = rm.indices();

        // Key index per triangle (0 = UNOWNED, s + 1 = sorted span s).
        List<String> keyNames = new ArrayList<>();
        keyNames.add(UNOWNED);
        for (PartSpan s : sorted) keyNames.add(s.partId());
        int[] faceStartOfKey = new int[keyNames.size()];
        for (int i = 0; i < sorted.size(); i++) faceStartOfKey[i + 1] = sorted.get(i).faceStart();

        int[] triKey = new int[tris];
        Map<Integer, Integer> faceKey = new HashMap<>();
        for (int t = 0; t < tris; t++) {
            int f = triToFace[t];
            Integer k = faceKey.get(f);
            if (k == null) {
                PartSpan owner = owner(sorted, f);
                k = owner == null ? 0 : sorted.indexOf(owner) + 1;
                faceKey.put(f, k);
            }
            triKey[t] = k;
        }

        // Key and (global) face of every corner: corners are per face, so one key each.
        int[] cornerKey = new int[corners];
        int[] cornerFaceGlobal = new int[corners];
        Arrays.fill(cornerKey, -1);
        for (int t = 0; t < tris; t++) {
            for (int c = 0; c < 3; c++) {
                int v = indices[t * 3 + c];
                if (cornerKey[v] < 0) {
                    cornerKey[v] = triKey[t];
                    cornerFaceGlobal[v] = triToFace[t];
                }
            }
        }

        int keyCount = keyNames.size();
        int[] cornerCounts = new int[keyCount];
        int[] triCounts = new int[keyCount];
        for (int v = 0; v < corners; v++) if (cornerKey[v] >= 0) cornerCounts[cornerKey[v]]++;
        for (int t = 0; t < tris; t++) triCounts[triKey[t]]++;

        int[][] globalCorners = new int[keyCount][];
        int[][] cornerFace = new int[keyCount][];
        float[][] positions = new float[keyCount][];
        int[][] localIndices = new int[keyCount][];
        int[][] localFaces = new int[keyCount][];
        for (int k = 0; k < keyCount; k++) {
            globalCorners[k] = new int[cornerCounts[k]];
            cornerFace[k] = new int[cornerCounts[k]];
            positions[k] = new float[cornerCounts[k] * 3];
            localIndices[k] = new int[triCounts[k] * 3];
            localFaces[k] = new int[triCounts[k]];
        }
        int[] localOf = new int[corners];
        int[] cursor = new int[keyCount];
        float[] vertices = rm.vertices();
        for (int v = 0; v < corners; v++) {
            int k = cornerKey[v];
            if (k < 0) continue;
            int i = cursor[k]++;
            localOf[v] = i;
            globalCorners[k][i] = v;
            cornerFace[k][i] = cornerFaceGlobal[v] - faceStartOfKey[k];
            System.arraycopy(vertices, v * 3, positions[k], i * 3, 3);
        }
        Arrays.fill(cursor, 0);
        for (int t = 0; t < tris; t++) {
            int k = triKey[t];
            int i = cursor[k]++;
            for (int c = 0; c < 3; c++) localIndices[k][i * 3 + c] = localOf[indices[t * 3 + c]];
            localFaces[k][i] = triToFace[t] - faceStartOfKey[k];
        }

        Map<String, Slice> out = new LinkedHashMap<>();
        for (int k = 0; k < keyCount; k++) {
            if (k == 0 && cornerCounts[0] == 0) continue;
            out.put(keyNames.get(k), new Slice(keyNames.get(k), globalCorners[k], cornerFace[k],
                    new CendaShadingStore.Soup(positions[k], localIndices[k], localFaces[k])));
        }
        return out;
    }

    /** What a store last saw of its part: layout (per-corner face) and positions. */
    private static final class Baseline {
        final int[] cornerFace;
        final float[] positions;

        Baseline(Slice slice) {
            this.cornerFace = slice.cornerFace.clone();
            this.positions = slice.soup.positions().clone();
        }

        boolean sameLayout(Slice slice) {
            return Arrays.equals(cornerFace, slice.cornerFace);
        }
    }

    private record CachedNormals(CendaShadingStore store, long revision, float[] positions, int[] indices,
                                 int[] faces, short[] oct) {
    }
}
