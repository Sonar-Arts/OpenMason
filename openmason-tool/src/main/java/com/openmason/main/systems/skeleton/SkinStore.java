package com.openmason.main.systems.skeleton;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.rig.Skin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Session-level skin weights of the loaded model: one native {@link Skin} per part, over that part's
 * own (part-local) vertices, so hiding, reordering or editing other parts never shifts them. Every
 * weight-editing operation is native ({@code cenda/rig.h}, {@link Skin}); this class only keeps the
 * per-part handles, the joint-id registry the skins index into, snapshots for undo, and the OMO
 * {@link OMOFormat.SkinData} conversion.
 *
 * <p>Joint indices inside these skins are REGISTRY indices ({@link #jointIndex}), stable across
 * skeleton edits; posing remaps them onto a rig's joint order. A vertex without weights follows its
 * part rigidly.
 */
public final class SkinStore {

    /** Undo snapshot: deep copies of every part skin plus the registry. */
    public record Snapshot(List<String> jointIds, Map<String, List<Skin.Influence>> parts,
                           Map<String, Integer> vertexCounts) {
        public Snapshot {
            jointIds = List.copyOf(jointIds);
            parts = Map.copyOf(parts);
            vertexCounts = Map.copyOf(vertexCounts);
        }
    }

    private final List<String> jointIds = new ArrayList<>();
    private final Map<String, Integer> jointIndexById = new HashMap<>();
    private final Map<String, Skin> skins = new LinkedHashMap<>();
    /** Each skinned part's local positions when last seen, the source for a transfer after a mesh edit. */
    private final Map<String, float[]> knownPositions = new HashMap<>();
    private final List<Runnable> listeners = new ArrayList<>();

    /** Fired after any change to weights or the registry. */
    public void addListener(Runnable listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    public void fireChanged() {
        for (Runnable l : List.copyOf(listeners)) l.run();
    }

    /** Registry index of a joint id (part or bone), added when absent. */
    public int jointIndex(String jointId) {
        Objects.requireNonNull(jointId, "jointId");
        Integer i = jointIndexById.get(jointId);
        if (i != null) return i;
        int idx = jointIds.size();
        jointIds.add(jointId);
        jointIndexById.put(jointId, idx);
        return idx;
    }

    /** Registry index of a joint id, -1 when the registry has never seen it. */
    public int findJoint(String jointId) {
        Integer i = jointId == null ? null : jointIndexById.get(jointId);
        return i == null ? -1 : i;
    }

    /** The joint ids, by registry index. */
    public List<String> jointIds() {
        return List.copyOf(jointIds);
    }

    /** True when no part carries a weighted vertex. */
    public boolean isEmpty() {
        for (Skin s : skins.values()) {
            if (s.weightedCount() > 0) return false;
        }
        return true;
    }

    /** Parts that have a skin (weighted or not). */
    public List<String> partIds() {
        return List.copyOf(skins.keySet());
    }

    /** The part's skin, or null when it has none. */
    public Skin skinOf(String partId) {
        return skins.get(partId);
    }

    /**
     * The part's skin, created (or resized by nearest-vertex transfer when its vertex count changed)
     * over {@code localPositions} (x,y,z per part-local vertex).
     */
    public Skin ensureSkin(String partId, float[] localPositions, float[] previousLocalPositions) {
        int count = localPositions == null ? 0 : localPositions.length / 3;
        Skin s = skins.get(partId);
        if (s == null) {
            s = Skin.create(count);
            skins.put(partId, s);
            remember(partId, localPositions);
            return s;
        }
        if (s.vertexCount() != count) {
            float[] previous = previousLocalPositions != null ? previousLocalPositions : knownPositions.get(partId);
            Skin moved = previous != null && previous.length / 3 == s.vertexCount()
                    ? s.transfer(previous, localPositions)
                    : Skin.create(count);
            s.close();
            skins.put(partId, moved);
            s = moved;
        }
        remember(partId, localPositions);
        return s;
    }

    /**
     * Keeps every skin in step with the current geometry, WITHOUT notifying listeners (it runs inside
     * the mesh bake): a part whose vertex count changed gets its weights carried over by nearest
     * vertex from the positions last seen (natively), a deleted part loses its skin, and the positions
     * are remembered for the next edit. {@code localPositionsOf} returns a part's current local
     * positions or null when the part no longer exists. Returns true when a skin changed.
     */
    public boolean reconcile(java.util.function.Function<String, float[]> localPositionsOf) {
        boolean changed = false;
        for (String partId : List.copyOf(skins.keySet())) {
            float[] now = localPositionsOf.apply(partId);
            Skin s = skins.get(partId);
            if (now == null) {
                s.close();
                skins.remove(partId);
                knownPositions.remove(partId);
                changed = true;
                continue;
            }
            if (s.vertexCount() != now.length / 3) {
                float[] previous = knownPositions.get(partId);
                Skin moved = previous != null && previous.length / 3 == s.vertexCount()
                        ? s.transfer(previous, now)
                        : Skin.create(now.length / 3);
                s.close();
                skins.put(partId, moved);
                changed = true;
            }
            remember(partId, now);
        }
        return changed;
    }

    private void remember(String partId, float[] positions) {
        if (positions == null) return;
        float[] known = knownPositions.get(partId);
        if (known == null || !java.util.Arrays.equals(known, positions)) {
            knownPositions.put(partId, positions.clone());
        }
    }

    /**
     * The part's geometry was edited: carry its weights to the new vertices (each takes its nearest
     * old vertex's weights). No-op when the part has no skin.
     */
    public void onPartGeometryChanged(String partId, float[] oldLocalPositions, float[] newLocalPositions) {
        Skin s = skins.get(partId);
        if (s == null) return;
        Skin moved = s.transfer(oldLocalPositions, newLocalPositions);
        s.close();
        skins.put(partId, moved);
        remember(partId, newLocalPositions);
        fireChanged();
    }

    /** Forgets a part's skin (the part was deleted). */
    public void removePart(String partId) {
        Skin s = skins.remove(partId);
        knownPositions.remove(partId);
        if (s != null) {
            s.close();
            fireChanged();
        }
    }

    /** Removes a joint's influence everywhere (the bone/part was deleted); vertices renormalize. */
    public void removeJoint(String jointId) {
        int j = findJoint(jointId);
        if (j < 0) return;
        for (Skin s : skins.values()) s.removeJoint(j);
        fireChanged();
    }

    /** Drops every skin and the registry. */
    public void clear() {
        for (Skin s : skins.values()) s.close();
        skins.clear();
        jointIds.clear();
        jointIndexById.clear();
        knownPositions.clear();
        fireChanged();
    }

    // ---- undo ------------------------------------------------------------------------------------

    public Snapshot snapshot() {
        Map<String, List<Skin.Influence>> parts = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map.Entry<String, Skin> e : skins.entrySet()) {
            parts.put(e.getKey(), e.getValue().exportInfluences());
            counts.put(e.getKey(), e.getValue().vertexCount());
        }
        return new Snapshot(jointIds, parts, counts);
    }

    public void restore(Snapshot snapshot) {
        for (Skin s : skins.values()) s.close();
        skins.clear();
        jointIds.clear();
        jointIndexById.clear();
        knownPositions.clear();
        for (String id : snapshot.jointIds()) jointIndex(id);
        for (Map.Entry<String, Integer> e : snapshot.vertexCounts().entrySet()) {
            skins.put(e.getKey(), Skin.of(e.getValue(), snapshot.parts().getOrDefault(e.getKey(), List.of())));
        }
        fireChanged();
    }

    /** Value equality of two snapshots (for no-op detection). */
    public static boolean sameSnapshot(Snapshot a, Snapshot b) {
        if (!a.jointIds().equals(b.jointIds()) || !a.vertexCounts().equals(b.vertexCounts())) return false;
        for (Map.Entry<String, List<Skin.Influence>> e : a.parts().entrySet()) {
            List<Skin.Influence> x = e.getValue();
            List<Skin.Influence> y = b.parts().get(e.getKey());
            if (y == null || x.size() != y.size()) return false;
            for (int i = 0; i < x.size(); i++) {
                Skin.Influence p = x.get(i), q = y.get(i);
                if (p.vertex() != q.vertex() || !java.util.Arrays.equals(p.joints(), q.joints())
                        || !java.util.Arrays.equals(p.weights(), q.weights())) {
                    return false;
                }
            }
        }
        return true;
    }

    // ---- OMO -------------------------------------------------------------------------------------

    /**
     * The weights in OMO 1.8 form, or null when nothing is weighted. Only joints in {@code liveJointIds}
     * (the model's current parts and bones; null = all) are written; influences on others are dropped.
     */
    public OMOFormat.SkinData toSkinData(java.util.Set<String> liveJointIds) {
        List<String> outJoints = new ArrayList<>();
        Map<Integer, Integer> remap = new HashMap<>();
        List<OMOFormat.PartSkin> parts = new ArrayList<>();
        for (Map.Entry<String, Skin> e : skins.entrySet()) {
            List<OMOFormat.SkinVertex> vertices = new ArrayList<>();
            for (Skin.Influence inf : e.getValue().exportInfluences()) {
                List<Integer> js = new ArrayList<>();
                List<Float> ws = new ArrayList<>();
                for (int k = 0; k < inf.joints().length; k++) {
                    int reg = inf.joints()[k];
                    if (reg < 0 || reg >= jointIds.size()) continue;
                    String id = jointIds.get(reg);
                    if (liveJointIds != null && !liveJointIds.contains(id)) continue;
                    Integer out = remap.get(reg);
                    if (out == null) {
                        out = outJoints.size();
                        outJoints.add(id);
                        remap.put(reg, out);
                    }
                    js.add(out);
                    ws.add(inf.weights()[k]);
                }
                if (js.isEmpty()) continue;
                float[] wa = new float[ws.size()];
                for (int k = 0; k < wa.length; k++) wa[k] = ws.get(k);
                vertices.add(new OMOFormat.SkinVertex(inf.vertex(), js.stream().mapToInt(Integer::intValue).toArray(), wa));
            }
            if (!vertices.isEmpty()) parts.add(new OMOFormat.PartSkin(e.getKey(), vertices));
        }
        return parts.isEmpty() ? null : new OMOFormat.SkinData(outJoints, parts);
    }

    /**
     * Replaces everything with {@code data} (null clears). {@code vertexCountOf} gives each part's
     * current vertex count; parts it does not know are skipped.
     */
    public void load(OMOFormat.SkinData data, java.util.function.ToIntFunction<String> vertexCountOf) {
        for (Skin s : skins.values()) s.close();
        skins.clear();
        jointIds.clear();
        jointIndexById.clear();
        knownPositions.clear();
        if (data != null) {
            int[] reg = new int[data.jointIds().size()];
            for (int k = 0; k < reg.length; k++) reg[k] = jointIndex(data.jointIds().get(k));
            for (OMOFormat.PartSkin ps : data.parts()) {
                int count = vertexCountOf.applyAsInt(ps.partId());
                if (count <= 0) continue;
                List<Skin.Influence> infl = new ArrayList<>();
                for (OMOFormat.SkinVertex sv : ps.vertices()) {
                    if (sv.localVertex() < 0 || sv.localVertex() >= count) continue;
                    int[] js = new int[sv.joints().length];
                    for (int k = 0; k < js.length; k++) js[k] = reg[sv.joints()[k]];
                    infl.add(new Skin.Influence(sv.localVertex(), js, sv.weights()));
                }
                skins.put(ps.partId(), Skin.of(count, infl));
            }
        }
        fireChanged();
    }
}
