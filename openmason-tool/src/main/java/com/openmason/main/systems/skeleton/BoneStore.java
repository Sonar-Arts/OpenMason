package com.openmason.main.systems.skeleton;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.modeling.parts.PartTransform;
import com.openmason.engine.rig.Rig;
import com.openmason.engine.rig.RigJoint;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Session-level skeleton storage for the currently loaded model.
 *
 * <p>Holds the editable list of {@link OMOFormat.BoneEntry} records (the REST pose) plus an optional
 * POSE layer (posed local position/rotation per bone, set by the animation preview), and exposes the
 * bones' world frames. The frames come from the native rig ({@code cenda/rig.h} BONE joints, through
 * {@link Rig#restWorld}): head = {@code parentFrame·T(origin)·T(pos)·R(rot)}, tail =
 * {@code head·T(endpoint)} — children (bones or parts) inherit the tail. Java only marshals: a chain
 * whose root bone hangs under a non-bone node (a part) is solved natively relative to that root, and
 * the external parent frame (from {@link #setExternalParentResolver}) is multiplied on afterwards.
 * Parent cycles (malformed data) are cut where they are detected.
 *
 * <p>While the pose layer is active ({@link #isPoseActive()}), the world frames use each posed bone's
 * posed local values (bones without a pose entry use their rest values), so parts parented to bones
 * follow the preview through the part manager's external resolver. {@link #clearPose()} returns to
 * rest. Rest edits ({@link #put}) never touch the pose layer.
 *
 * <p>Single responsibility: own the bone list + pose layer and expose derived frames.
 */
public final class BoneStore {

    private static final Logger logger = LoggerFactory.getLogger(BoneStore.class);

    /** A bone's posed local values (scale is always 1 — bones carry no geometric scale). */
    public record BonePose(Vector3fc position, Vector3fc rotation) {
        public BonePose {
            position = new Vector3f(position != null ? position : new Vector3f());
            rotation = new Vector3f(rotation != null ? rotation : new Vector3f());
        }
    }

    private final List<OMOFormat.BoneEntry> bones = new ArrayList<>();
    private final Map<String, OMOFormat.BoneEntry> byId = new HashMap<>();

    // Pose layer (a posed preview of the Scene model; the Animation tab poses its own viewport).
    private final Map<String, BonePose> pose = new LinkedHashMap<>();
    private boolean poseActive;
    private Function<String, PartTransform> partRestLookup;

    // Native solve cache: frames relative to each chain's external root frame.
    private boolean cacheDirty = true;
    private Map<String, Integer> indexById = Map.of();
    private int[] chainRoot = new int[0];
    private Matrix4f[] relHead = new Matrix4f[0];
    private Matrix4f[] relTail = new Matrix4f[0];

    /** Re-entrancy guard for cross-store parent resolution (parts ↔ bones). */
    private final Set<String> resolvingExternal = new HashSet<>();

    /**
     * Resolves a parent matrix for a {@code parentBoneId} that is not a known bone id.
     * Used when bones are nested under non-bone nodes (e.g. parts) in a unified hierarchy.
     * Returning {@code null} treats the bone as a root.
     */
    private Function<String, Matrix4f> externalParentResolver;

    /**
     * Fired after any structural mutation (set, put, remove, clear) and pose change. Lets external
     * stores (e.g. a part manager that composes its transforms with bone matrices) rebuild.
     */
    private Runnable onChange;

    /**
     * Id of the bone the editor currently treats as "selected", or {@code null} when
     * no bone is selected. Used by the gizmo system to target the joint location.
     */
    private String selectedBoneId;

    public String getSelectedBoneId() { return selectedBoneId; }

    public void setSelectedBoneId(String boneId) { this.selectedBoneId = boneId; }

    /** Wire an external parent resolver (e.g. backed by a part manager's world matrices). */
    public void setExternalParentResolver(Function<String, Matrix4f> resolver) {
        this.externalParentResolver = resolver;
        invalidate();
    }

    /** Wire a callback fired after any structural mutation or pose change. */
    public void setOnChange(Runnable onChange) {
        this.onChange = onChange;
    }

    private void fireOnChange() {
        if (onChange != null) onChange.run();
    }

    /** Listeners told when the skeleton's CONTENT changes (not poses or selection, not a {@link #load}). */
    private final List<Runnable> editListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Subscribe to edits of the skeleton itself: put, remove, replace, clear (gizmo, inspector, hierarchy, MCP,
     * undo/redo). The model state marks the model dirty from here (bones are saved in the .omo); a {@link #load}
     * does not fire it.
     */
    public void addEditListener(Runnable listener) {
        editListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    private void fireEdited() {
        for (Runnable l : editListeners) l.run();
    }

    /** {@link #setBones} as a LOAD (opening a model): no edit notification, so the model stays clean. */
    public void load(List<OMOFormat.BoneEntry> entries) {
        load(entries, true);
    }

    /**
     * {@link #load(List)} that may skip the change callback (the part rebuild): an OMO load restores the bones
     * BEFORE the parts, whose own rebuild then sees the new skeleton, so the callback's rebuild would only repeat
     * it (#175).
     */
    public void load(List<OMOFormat.BoneEntry> entries, boolean notifyChange) {
        replace(entries, notifyChange);
    }

    /** Replace the entire skeleton. Null or empty clears the skeleton. Pose entries of gone bones drop. */
    public void setBones(List<OMOFormat.BoneEntry> entries) {
        List<OMOFormat.BoneEntry> before = List.copyOf(bones);
        replace(entries);
        if (!before.equals(bones)) fireEdited();
    }

    private void replace(List<OMOFormat.BoneEntry> entries) {
        replace(entries, true);
    }

    private void replace(List<OMOFormat.BoneEntry> entries, boolean notifyChange) {
        bones.clear();
        byId.clear();
        if (entries != null) {
            for (OMOFormat.BoneEntry b : entries) {
                if (b == null) continue;
                bones.add(b);
                byId.put(b.id(), b);
            }
        }
        pose.keySet().retainAll(byId.keySet());
        invalidate();
        if (notifyChange) fireOnChange();
    }

    /** Add or replace a single bone (by id; a replaced bone keeps its list position). A REST edit. */
    public void put(OMOFormat.BoneEntry entry) {
        Objects.requireNonNull(entry, "entry");
        OMOFormat.BoneEntry existing = byId.get(entry.id());
        if (existing != null) {
            bones.set(bones.indexOf(existing), entry);
        } else {
            bones.add(entry);
        }
        byId.put(entry.id(), entry);
        invalidate();
        fireOnChange();
        if (!entry.equals(existing)) fireEdited();
    }

    /** Remove a bone by id. Children of the removed bone become roots. */
    public boolean remove(String boneId) {
        OMOFormat.BoneEntry existing = byId.remove(boneId);
        if (existing == null) {
            return false;
        }
        bones.remove(existing);
        pose.remove(boneId);
        for (int i = 0; i < bones.size(); i++) {
            OMOFormat.BoneEntry b = bones.get(i);
            if (boneId.equals(b.parentBoneId())) {
                OMOFormat.BoneEntry r = withParent(b, null);
                bones.set(i, r);
                byId.put(r.id(), r);
            }
        }
        invalidate();
        fireOnChange();
        fireEdited();
        return true;
    }

    /** Clear all bones. */
    public void clear() {
        if (bones.isEmpty()) return;
        bones.clear();
        byId.clear();
        pose.clear();
        invalidate();
        fireOnChange();
        fireEdited();
    }

    public boolean isEmpty() {
        return bones.isEmpty();
    }

    public int size() {
        return bones.size();
    }

    /** Returns an unmodifiable snapshot of the current bone list (rest pose). */
    public List<OMOFormat.BoneEntry> getBones() {
        return Collections.unmodifiableList(new ArrayList<>(bones));
    }

    public OMOFormat.BoneEntry getById(String id) {
        return id == null ? null : byId.get(id);
    }

    /** First bone with this exact name, else case-insensitive; null when none. */
    public OMOFormat.BoneEntry getByName(String name) {
        if (name == null || name.isBlank()) return null;
        for (OMOFormat.BoneEntry b : bones) {
            if (name.equals(b.name())) return b;
        }
        for (OMOFormat.BoneEntry b : bones) {
            if (name.equalsIgnoreCase(b.name())) return b;
        }
        return null;
    }

    /** Force the world-pose cache to rebuild on next query. */
    public void invalidate() {
        cacheDirty = true;
    }

    // ===================== Pose layer =====================

    /** True while the animation preview poses the skeleton (and the model's parts). */
    public boolean isPoseActive() {
        return poseActive;
    }

    /** The bone's pose entry, or null when it shows its rest values. */
    public BonePose getPose(String boneId) {
        return poseActive ? pose.get(boneId) : null;
    }

    /** Ids of the bones that currently carry a pose entry. */
    public Set<String> posedBoneIds() {
        return poseActive ? Set.copyOf(pose.keySet()) : Set.of();
    }

    /**
     * The rest transform of a part while the pose layer is active (the preview's rest snapshot), or
     * null when unknown / not posing. The skinned preview binds weights against these.
     */
    public PartTransform partRestOf(String partId) {
        Function<String, PartTransform> lookup = partRestLookup;
        return poseActive && lookup != null ? lookup.apply(partId) : null;
    }

    /**
     * Replace the whole pose layer and activate it. Bones absent from {@code poses} show their rest
     * values. {@code partRest} gives the parts' rest transforms for the duration of the pose.
     *
     * @param notify fire the change callback (part rebuild) when something changed
     * @return true when the effective pose changed (including activation)
     */
    public boolean setPose(Map<String, BonePose> poses, Function<String, PartTransform> partRest, boolean notify) {
        Map<String, BonePose> next = new LinkedHashMap<>();
        if (poses != null) {
            for (Map.Entry<String, BonePose> e : poses.entrySet()) {
                if (e.getValue() != null && byId.containsKey(e.getKey())) next.put(e.getKey(), e.getValue());
            }
        }
        boolean changed = !poseActive || !pose.equals(next);
        pose.clear();
        pose.putAll(next);
        poseActive = true;
        partRestLookup = partRest;
        if (changed) {
            invalidate();
            if (notify) fireOnChange();
        }
        return changed;
    }

    /** Pose one bone (activating the layer if needed) and notify. */
    public void setBonePose(String boneId, Vector3fc position, Vector3fc rotation) {
        if (!byId.containsKey(boneId)) return;
        BonePose next = new BonePose(position, rotation);
        if (poseActive && next.equals(pose.get(boneId))) return;
        pose.put(boneId, next);
        poseActive = true;
        invalidate();
        fireOnChange();
    }

    /**
     * Drop the pose layer (rest restore).
     *
     * @param notify fire the change callback when the layer was active
     * @return true when the layer was active
     */
    public boolean clearPose(boolean notify) {
        boolean was = poseActive;
        pose.clear();
        poseActive = false;
        partRestLookup = null;
        if (was) {
            invalidate();
            if (notify) fireOnChange();
        }
        return was;
    }

    /** {@link #clearPose(boolean)} with notification. */
    public void clearPose() {
        clearPose(true);
    }

    /** The bone's effective local position: posed while the pose layer has it, else rest. */
    public Vector3f getLocalPosition(String boneId) {
        BonePose p = getPose(boneId);
        if (p != null) return new Vector3f(p.position());
        OMOFormat.BoneEntry b = byId.get(boneId);
        return b == null ? null : new Vector3f(b.posX(), b.posY(), b.posZ());
    }

    /** The bone's effective local rotation (Euler XYZ degrees): posed, else rest. */
    public Vector3f getLocalRotation(String boneId) {
        BonePose p = getPose(boneId);
        if (p != null) return new Vector3f(p.rotation());
        OMOFormat.BoneEntry b = byId.get(boneId);
        return b == null ? null : new Vector3f(b.rotX(), b.rotY(), b.rotZ());
    }

    /**
     * A viewport (gizmo) edit of a bone's local position/rotation (null = unchanged): a REST edit.
     * (Animation is authored in the Animation tab's own viewport since OM #165.)
     */
    public void editLocal(String boneId, Vector3f position, Vector3f rotation) {
        OMOFormat.BoneEntry b = byId.get(boneId);
        if (b == null) return;
        Vector3f pos = position != null ? position : getLocalPosition(boneId);
        Vector3f rot = rotation != null ? rotation : getLocalRotation(boneId);
        put(new OMOFormat.BoneEntry(
                b.id(), b.name(), b.parentBoneId(),
                b.originX(), b.originY(), b.originZ(),
                pos.x, pos.y, pos.z,
                rot.x, rot.y, rot.z,
                b.endpointX(), b.endpointY(), b.endpointZ()));
    }

    // ===================== Frames =====================

    /**
     * Returns the head-frame world matrix for the given bone — the matrix whose
     * translation is the head joint position and whose rotation is the bone's
     * applied (posed or rest) rotation. A fresh copy; {@code null} if the bone is unknown.
     */
    public Matrix4f getWorldTransform(String boneId) {
        Integer i = index(boneId);
        if (i == null) return null;
        return external(i).mul(relHead[i]);
    }

    /**
     * Returns the tail-frame world matrix for the given bone — head matrix
     * translated by the bone's local-space {@code endpoint} offset. Children
     * (parts or other bones) inherit from this frame, so rotating a bone rotates
     * everything below it. A fresh copy; {@code null} if the bone is unknown.
     */
    public Matrix4f getTailWorldTransform(String boneId) {
        Integer i = index(boneId);
        if (i == null) return null;
        return external(i).mul(relTail[i]);
    }

    /**
     * Returns the world-space joint position (head) for the given bone, or
     * {@code null} if the bone is unknown.
     */
    public Vector3f getJointWorldPosition(String boneId) {
        Matrix4f world = getWorldTransform(boneId);
        return world == null ? null : world.transformPosition(new Vector3f());
    }

    /** World-space tail position for the given bone, or {@code null} if unknown. */
    public Vector3f getTailWorldPosition(String boneId) {
        Matrix4f world = getTailWorldTransform(boneId);
        return world == null ? null : world.transformPosition(new Vector3f());
    }

    /**
     * Snapshot of head joint world positions keyed by bone id. Useful for renderers
     * that want to walk every bone once per frame.
     */
    public Map<String, Vector3f> snapshotJointWorldPositions() {
        Map<String, Vector3f> out = new LinkedHashMap<>(bones.size());
        for (OMOFormat.BoneEntry b : List.copyOf(bones)) {
            Vector3f v = getJointWorldPosition(b.id());
            if (v != null) out.put(b.id(), v);
        }
        return out;
    }

    /** Snapshot of tail world positions keyed by bone id. */
    public Map<String, Vector3f> snapshotTailWorldPositions() {
        Map<String, Vector3f> out = new LinkedHashMap<>(bones.size());
        for (OMOFormat.BoneEntry b : List.copyOf(bones)) {
            Vector3f v = getTailWorldPosition(b.id());
            if (v != null) out.put(b.id(), v);
        }
        return out;
    }

    // ===================== Native solve =====================

    private Integer index(String boneId) {
        if (boneId == null || !byId.containsKey(boneId)) return null;
        ensureSolved();
        return indexById.get(boneId);
    }

    /**
     * Rebuild the relative frames through the native rig: every bone is a BONE joint, a bone whose
     * parent is another bone is its child, every other bone is a chain root (its external parent
     * frame is applied at query time). Posed bones use their posed local values.
     */
    private void ensureSolved() {
        if (!cacheDirty) return;
        int n = bones.size();
        Map<String, Integer> idx = new HashMap<>(n * 2);
        for (int i = 0; i < n; i++) idx.put(bones.get(i).id(), i);

        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            String p = bones.get(i).parentBoneId();
            Integer pi = (p == null || p.isBlank()) ? null : idx.get(p);
            parent[i] = pi == null || pi == i ? -1 : pi;
        }
        cutCycles(parent);
        int[] roots = new int[n];
        for (int i = 0; i < n; i++) {
            int r = i;
            while (parent[r] >= 0) r = parent[r];
            roots[i] = r;
        }

        Matrix4f[] head = new Matrix4f[n];
        Matrix4f[] tail = new Matrix4f[n];
        if (n > 0) {
            List<RigJoint> joints = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                OMOFormat.BoneEntry b = bones.get(i);
                BonePose p = poseActive ? pose.get(b.id()) : null;
                Vector3fc pos = p != null ? p.position() : new Vector3f(b.posX(), b.posY(), b.posZ());
                Vector3fc rot = p != null ? p.rotation() : new Vector3f(b.rotX(), b.rotY(), b.rotZ());
                joints.add(RigJoint.bone(b.id(), b.name(), parent[i], false,
                        new Vector3f(b.originX(), b.originY(), b.originZ()), pos, rot,
                        new Vector3f(b.endpointX(), b.endpointY(), b.endpointZ())));
            }
            try (Rig rig = Rig.create(joints)) {
                rig.restWorld(head, tail);
            }
        }
        this.indexById = idx;
        this.chainRoot = roots;
        this.relHead = head;
        this.relTail = tail;
        this.cacheDirty = false;
    }

    /** Cut every parent cycle at the joint where the walk detects it (that joint becomes a root). */
    private void cutCycles(int[] parent) {
        int n = parent.length;
        int[] state = new int[n]; // 0 = unvisited, 1 = on the current walk, 2 = done
        for (int start = 0; start < n; start++) {
            if (state[start] != 0) continue;
            List<Integer> walk = new ArrayList<>();
            int cur = start;
            while (cur >= 0 && state[cur] == 0) {
                state[cur] = 1;
                walk.add(cur);
                int next = parent[cur];
                if (next >= 0 && state[next] == 1) {
                    logger.warn("Bone hierarchy cycle detected at '{}' — breaking chain", bones.get(cur).id());
                    parent[cur] = -1;
                    next = -1;
                }
                cur = next;
            }
            for (int w : walk) state[w] = 2;
        }
    }

    /** A fresh copy of the external frame of bone {@code i}'s chain root (identity for true roots). */
    private Matrix4f external(int i) {
        OMOFormat.BoneEntry root = bones.get(chainRoot[i]);
        String parentId = root.parentBoneId();
        if (parentId == null || parentId.isBlank() || byId.containsKey(parentId) || externalParentResolver == null) {
            return new Matrix4f();
        }
        if (!resolvingExternal.add(root.id())) {
            logger.warn("Bone/part hierarchy cycle through bone '{}' — treating it as a root", root.id());
            return new Matrix4f();
        }
        try {
            Matrix4f m = externalParentResolver.apply(parentId);
            return m != null ? new Matrix4f(m) : new Matrix4f();
        } finally {
            resolvingExternal.remove(root.id());
        }
    }

    private static OMOFormat.BoneEntry withParent(OMOFormat.BoneEntry b, String parent) {
        return new OMOFormat.BoneEntry(
                b.id(), b.name(), parent,
                b.originX(), b.originY(), b.originZ(),
                b.posX(), b.posY(), b.posZ(),
                b.rotX(), b.rotY(), b.rotZ(),
                b.endpointX(), b.endpointY(), b.endpointZ());
    }
}
