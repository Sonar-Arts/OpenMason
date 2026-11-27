package com.openmason.main.systems.skeleton;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.modeling.parts.ModelPartDescriptor;
import com.openmason.engine.modeling.parts.ModelPartManager;
import com.openmason.engine.modeling.parts.PartMeshRebuilder;
import com.openmason.engine.modeling.parts.PartTransform;
import com.openmason.engine.rig.Rig;
import com.openmason.engine.rig.RigModel;
import com.openmason.engine.rig.Skin;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Marshalling between the editor's model (parts, bones, per-part {@link SkinStore} skins) and the native
 * skin-authoring ops ({@code cenda/rig.h}, {@link Skin}). Shared by the rigging pane's Skin section,
 * the viewport weight-paint brush and the MCP skin tools. No weight math lives here: this class only
 * gathers positions, joint indices and triangle lists, calls one native op, and maps joint indices.
 *
 * <p><b>Spaces.</b> Skins are per part over the part's LOCAL vertices ({@link SkinStore}). Every op that
 * compares positions (auto weights, paint, mirror) runs on MODEL-space positions: the part's local
 * positions through its effective rest matrix ({@link ModelPartManager#getEffectiveWorldMatrix}), the
 * same space the rig's rest segments and the viewport mesh are in. Vertex indices stay part-local.
 *
 * <p><b>Joint indices.</b> Store skins hold REGISTRY indices ({@link SkinStore#jointIndex}). Auto weights
 * need a native {@link Rig}; it is built in the rest pose (the editor's current transforms) with
 * {@link RigModel#build} — parts first, then bones — so its indices are RIG indices. The op runs on a
 * temporary skin, which is then remapped rig -> registry ({@link Skin#remapJoints}) and copied into the
 * part's skin. Every other op takes registry indices directly.
 *
 * <p>Callers record undo ({@link SkinCommandHistory#record}) around these calls; nothing here does.
 * Main/GL thread only (the stores are not thread-safe).
 */
public final class SkinAuthoring {

    private static final Logger logger = LoggerFactory.getLogger(SkinAuthoring.class);

    /** One joint the skin may reference: a part or a bone of the live model. */
    public record JointRef(String id, String name, boolean bone) {}

    /** A ray/mesh hit in model space. */
    public record Hit(String partId, Vector3f point, float distance) {}

    private final ModelPartManager parts;
    private final BoneStore bones;
    private final SkinStore skins;

    public SkinAuthoring(ModelPartManager parts, BoneStore bones, SkinStore skins) {
        this.parts = Objects.requireNonNull(parts, "parts");
        this.bones = bones;
        this.skins = Objects.requireNonNull(skins, "skins");
    }

    public ModelPartManager parts() {
        return parts;
    }

    public SkinStore skins() {
        return skins;
    }

    // ---- joints ----------------------------------------------------------------------------------

    /** Every live joint: the parts (model order), then the bones. */
    public List<JointRef> joints() {
        List<JointRef> out = new ArrayList<>();
        for (ModelPartDescriptor p : parts.getAllParts()) out.add(new JointRef(p.id(), p.name(), false));
        for (OMOFormat.BoneEntry b : boneList()) out.add(new JointRef(b.id(), b.name(), true));
        return out;
    }

    /** The bones only. */
    public List<JointRef> boneJoints() {
        List<JointRef> out = new ArrayList<>();
        for (OMOFormat.BoneEntry b : boneList()) out.add(new JointRef(b.id(), b.name(), true));
        return out;
    }

    /**
     * A joint id from an id or a name: exact id first, then a bone name, then a part name, then the
     * same names case-insensitively. Null when nothing matches.
     */
    public String resolveJoint(String idOrName) {
        if (idOrName == null || idOrName.isBlank()) return null;
        List<JointRef> all = joints();
        for (JointRef j : all) if (j.id().equals(idOrName)) return j.id();
        for (JointRef j : all) if (j.bone() && idOrName.equals(j.name())) return j.id();
        for (JointRef j : all) if (!j.bone() && idOrName.equals(j.name())) return j.id();
        for (JointRef j : all) if (j.bone() && idOrName.equalsIgnoreCase(j.name())) return j.id();
        for (JointRef j : all) if (!j.bone() && idOrName.equalsIgnoreCase(j.name())) return j.id();
        return null;
    }

    /** Display name of a joint id (the id itself when the joint no longer exists). */
    public String jointName(String id) {
        if (id == null) return null;
        OMOFormat.BoneEntry b = bones != null ? bones.getById(id) : null;
        if (b != null) return b.name();
        return parts.getPartById(id).map(ModelPartDescriptor::name).orElse(id);
    }

    /** Joint name of a registry index ("?" for an index outside the registry). */
    public String registryJointName(int registryIndex) {
        List<String> ids = skins.jointIds();
        return registryIndex >= 0 && registryIndex < ids.size() ? jointName(ids.get(registryIndex)) : "?";
    }

    /** A part id from an id or a name, null when unknown. */
    public String resolvePart(String idOrName) {
        if (idOrName == null || idOrName.isBlank()) return null;
        if (parts.getPartById(idOrName).isPresent()) return idOrName;
        return parts.getPartByName(idOrName).map(ModelPartDescriptor::id)
                .or(() -> parts.getAllParts().stream().filter(p -> p.name().equalsIgnoreCase(idOrName))
                        .map(ModelPartDescriptor::id).findFirst())
                .orElse(null);
    }

    /** Every part id with geometry, in model order. */
    public List<String> partIds() {
        List<String> out = new ArrayList<>();
        for (ModelPartDescriptor p : parts.getAllParts()) {
            if (vertexCount(p.id()) > 0) out.add(p.id());
        }
        return out;
    }

    // ---- positions --------------------------------------------------------------------------------

    public int vertexCount(String partId) {
        PartMeshRebuilder.PartGeometry g = parts.getPartGeometry(partId);
        return g == null || g.vertices() == null ? 0 : g.vertices().length / 3;
    }

    /** The part's local positions (a copy). */
    public float[] localPositions(String partId) {
        PartMeshRebuilder.PartGeometry g = parts.getPartGeometry(partId);
        return g == null || g.vertices() == null ? new float[0] : g.vertices().clone();
    }

    /** The part's positions in model space at the rest pose (a copy). */
    public float[] modelPositions(String partId) {
        return cachedModelPositions(partId).clone();
    }

    /**
     * Model-space positions, recomputed only when the part's local positions (edited in place by vertex
     * moves, so compared by value) or effective matrix changed: brush dabs and hover picking ask every
     * frame. Never hand the array out mutable.
     */
    private float[] cachedModelPositions(String partId) {
        PartMeshRebuilder.PartGeometry g = parts.getPartGeometry(partId);
        Matrix4f m = parts.getEffectiveWorldMatrix(partId);
        CachedPositions c = positionCache.get(partId);
        float[] local = g == null || g.vertices() == null ? new float[0] : g.vertices();
        if (c != null && c.matrix.equals(m) && Arrays.equals(c.local, local)) return c.positions;
        float[] p = local.clone();
        Vector3f v = new Vector3f();
        for (int i = 0; i + 2 < p.length; i += 3) {
            m.transformPosition(v.set(p[i], p[i + 1], p[i + 2]));
            p[i] = v.x;
            p[i + 1] = v.y;
            p[i + 2] = v.z;
        }
        positionCache.put(partId, new CachedPositions(local.clone(), new Matrix4f(m), p));
        return p;
    }

    /**
     * Changes whenever any visible part's model-space positions, visibility or the part list changed
     * (overlays rebuild on it). Cheap: compares the cached arrays.
     */
    public long geometryStamp() {
        long h = 1;
        for (ModelPartDescriptor p : parts.getAllParts()) {
            h = h * 31 + p.id().hashCode();
            if (!p.visible()) continue;
            h = h * 31 + System.identityHashCode(cachedModelPositions(p.id()));
            PartMeshRebuilder.PartGeometry g = parts.getPartGeometry(p.id());
            h = h * 31 + (g == null ? 0 : System.identityHashCode(g.indices()));
        }
        return h;
    }

    private record CachedPositions(float[] local, Matrix4f matrix, float[] positions) {}

    private final java.util.Map<String, CachedPositions> positionCache = new java.util.HashMap<>();

    /** The part's triangle indices (part-local), empty when it has none. */
    public int[] triangles(String partId) {
        PartMeshRebuilder.PartGeometry g = parts.getPartGeometry(partId);
        if (g == null || g.indices() == null) return new int[0];
        int n = Math.min(g.indexCount(), g.indices().length);
        return n == g.indices().length ? g.indices() : Arrays.copyOf(g.indices(), n);
    }

    /** Registry index of the part's own joint, repeated per vertex (what unweighted vertices follow). */
    public int[] partJointOfVertex(String partId) {
        int[] out = new int[vertexCount(partId)];
        Arrays.fill(out, skins.jointIndex(partId));
        return out;
    }

    // ---- skins ------------------------------------------------------------------------------------

    /** The part's skin, created over its current vertices when missing. */
    public Skin skinFor(String partId) {
        // A changed vertex count (a mesh edit) is carried over natively from the positions the store
        // last saw for this part (nearest vertex); see SkinStore.reconcile.
        return skins.ensureSkin(partId, localPositions(partId), null);
    }

    /** Weighted vertex count of a part (0 without a skin). */
    public int weightedCount(String partId) {
        Skin s = skins.skinOf(partId);
        return s == null ? 0 : s.weightedCount();
    }

    /** The joint ids a part's weights reference, heaviest-use order not guaranteed. */
    public List<String> jointsUsed(String partId) {
        Skin s = skins.skinOf(partId);
        if (s == null) return List.of();
        Set<Integer> used = new LinkedHashSet<>();
        for (Skin.Influence inf : s.exportInfluences()) {
            for (int j : inf.joints()) used.add(j);
        }
        List<String> ids = skins.jointIds();
        List<String> out = new ArrayList<>();
        for (int j : used) if (j >= 0 && j < ids.size()) out.add(ids.get(j));
        return out;
    }

    /** Sets one vertex's influences by joint id (empty = follows its part). */
    public void setWeights(String partId, int vertex, List<String> jointIds, float[] weights) {
        int[] reg = new int[jointIds.size()];
        for (int k = 0; k < reg.length; k++) reg[k] = skins.jointIndex(jointIds.get(k));
        skinFor(partId).set(vertex, reg, weights);
        skins.fireChanged();
    }

    /** {@link #autoWeights(Collection, Collection, float, float, int)} with no influence limit (Blender's default). */
    public int autoWeights(Collection<String> partIds, Collection<String> boneIds, float falloff, float minWeight) {
        return autoWeights(partIds, boneIds, falloff, minWeight, Skin.UNLIMITED_INFLUENCES);
    }

    /**
     * Auto weights for {@code partIds} against {@code boneIds} (null/empty = every bone): each vertex
     * is weighted to the bones by distance to their rest segments ({@code maxInfluences} > 0 = only that many
     * nearest bones, 0 = every bone), {@code falloff} shaping the decay ({@code <= 0} = rigid to the nearest
     * bone), influences below {@code minWeight} pruned. Replaces the parts' weights. Returns the number of
     * vertices written.
     *
     * @throws IllegalArgumentException when there is no bone to weight to
     */
    public int autoWeights(Collection<String> partIds, Collection<String> boneIds, float falloff, float minWeight,
                           int maxInfluences) {
        List<String> targets = new ArrayList<>();
        Set<String> wanted = boneIds == null || boneIds.isEmpty() ? null : new LinkedHashSet<>(boneIds);
        for (OMOFormat.BoneEntry b : boneList()) {
            if (wanted == null || wanted.contains(b.id())) targets.add(b.id());
        }
        if (targets.isEmpty()) throw new IllegalArgumentException("Auto weights need at least one bone");

        RigModel model = restRigModel();
        Rig rig = model.rig();
        try {
            int[] rigBones = new int[targets.size()];
            for (int k = 0; k < rigBones.length; k++) rigBones[k] = model.jointOf(targets.get(k));
            int[] rigToRegistry = new int[model.jointCount()];
            for (int j = 0; j < rigToRegistry.length; j++) {
                String id = model.joints().get(j).id();
                rigToRegistry[j] = id == null || id.startsWith("synthetic:") ? -1 : skins.jointIndex(id);
            }
            int written = 0;
            for (String partId : partIds) {
                int n = vertexCount(partId);
                if (n == 0) continue;
                try (Skin temp = Skin.create(n)) {
                    temp.autoWeights(rig, cachedModelPositions(partId), 0, n, rigBones, Math.max(0f, falloff),
                            Math.max(0f, minWeight), Math.max(0, maxInfluences));
                    temp.remapJoints(rigToRegistry);
                    Skin target = skinFor(partId);
                    clearSkin(target);
                    List<Skin.Influence> inf = temp.exportInfluences();
                    target.importInfluences(inf);
                    written += inf.size();
                }
            }
            skins.fireChanged();
            return written;
        } finally {
            rig.close();
        }
    }

    /** Removes every weight of the parts (they follow their parts rigidly again). */
    public int clear(Collection<String> partIds) {
        int cleared = 0;
        for (String partId : partIds) {
            Skin s = skins.skinOf(partId);
            if (s == null) continue;
            cleared += s.weightedCount();
            clearSkin(s);
        }
        skins.fireChanged();
        return cleared;
    }

    /** Normalizes the parts' weights; returns how many weighted vertices remain. */
    public int normalize(Collection<String> partIds) {
        int n = 0;
        for (String partId : partIds) {
            Skin s = skins.skinOf(partId);
            if (s == null) continue;
            s.normalize();
            n += s.weightedCount();
        }
        skins.fireChanged();
        return n;
    }

    /** Drops influences below {@code minWeight}; returns how many were removed. */
    public int prune(Collection<String> partIds, float minWeight) {
        int removed = 0;
        for (String partId : partIds) {
            Skin s = skins.skinOf(partId);
            if (s != null) removed += s.prune(minWeight);
        }
        skins.fireChanged();
        return removed;
    }

    /**
     * Mirrors weights across the model-space plane {@code axis = 0} (0 X, 1 Y, 2 Z) from the side whose
     * coordinate sign is {@code sourceSign}, joints swapped by {@link SkinMirrorMap} name convention.
     * Runs over the listed parts as ONE combined vertex set, so a left arm part mirrors onto a right
     * arm part; an unweighted source vertex leaves its target unweighted (following its own part).
     * Coincident (split-UV) vertices of a written part then share their weights. Returns the number
     * of source vertices mirrored.
     */
    public int mirror(Collection<String> partIds, int axis, int sourceSign, float tolerance) {
        List<String> ids = new ArrayList<>();
        for (String p : partIds) if (vertexCount(p) > 0) ids.add(p);
        if (ids.isEmpty()) return 0;
        int[] start = new int[ids.size()];
        int total = 0;
        for (int i = 0; i < ids.size(); i++) {
            start[i] = total;
            total += vertexCount(ids.get(i));
        }
        float[] positions = new float[total * 3];
        List<Skin.Influence> combined = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            float[] p = cachedModelPositions(ids.get(i));
            System.arraycopy(p, 0, positions, start[i] * 3, p.length);
            Skin s = skins.skinOf(ids.get(i));
            if (s == null) continue;
            for (Skin.Influence inf : s.exportInfluences()) {
                combined.add(new Skin.Influence(start[i] + inf.vertex(), inf.joints(), inf.weights()));
            }
        }
        int[] jointMirror = jointMirrorMap();
        int written;
        try (Skin all = Skin.of(total, combined)) {
            written = all.mirror(positions, axis, sourceSign, Math.max(0f, tolerance), jointMirror);
            if (written > 0) {
                List<Skin.Influence> out = all.exportInfluences();
                int k = 0;
                for (int i = 0; i < ids.size(); i++) {
                    int end = start[i] + vertexCount(ids.get(i));
                    List<Skin.Influence> mine = new ArrayList<>();
                    while (k < out.size() && out.get(k).vertex() < end) {
                        Skin.Influence inf = out.get(k++);
                        mine.add(new Skin.Influence(inf.vertex() - start[i], inf.joints(), inf.weights()));
                    }
                    Skin existing = skins.skinOf(ids.get(i));
                    if (existing == null && mine.isEmpty()) continue;
                    Skin target = skinFor(ids.get(i));
                    clearSkin(target);
                    target.importInfluences(mine);
                }
            }
        }
        skins.fireChanged();
        return written;
    }

    /**
     * Registry joint index -> its mirrored partner's index, by name convention (parts pair with parts,
     * bones with bones). Partners missing from the registry are added to it.
     */
    public int[] jointMirrorMap() {
        List<JointRef> live = joints();
        for (JointRef j : live) skins.jointIndex(j.id());
        List<String> partNames = new ArrayList<>();
        List<String> boneNames = new ArrayList<>();
        List<JointRef> partRefs = new ArrayList<>();
        List<JointRef> boneRefs = new ArrayList<>();
        for (JointRef j : live) {
            if (j.bone()) {
                boneRefs.add(j);
                boneNames.add(j.name());
            } else {
                partRefs.add(j);
                partNames.add(j.name());
            }
        }
        int[] map = new int[skins.jointIds().size()];
        for (int i = 0; i < map.length; i++) map[i] = i;
        int[] pm = SkinMirrorMap.build(partNames);
        for (int i = 0; i < pm.length; i++) {
            map[skins.jointIndex(partRefs.get(i).id())] = skins.jointIndex(partRefs.get(pm[i]).id());
        }
        int[] bm = SkinMirrorMap.build(boneNames);
        for (int i = 0; i < bm.length; i++) {
            map[skins.jointIndex(boneRefs.get(i).id())] = skins.jointIndex(boneRefs.get(bm[i]).id());
        }
        return map;
    }

    /**
     * One brush dab at a model-space {@code center} on every listed part the sphere reaches (hidden parts
     * skipped). Returns the number of vertices changed.
     */
    public int paint(Collection<String> partIds, float cx, float cy, float cz, float radius, String jointId,
                     Skin.PaintMode mode, float strength) {
        int joint = skins.jointIndex(Objects.requireNonNull(jointId, "jointId"));
        int changed = 0;
        for (String partId : partIds) {
            if (!isVisible(partId) || vertexCount(partId) == 0) continue;
            float[] pos = cachedModelPositions(partId);
            if (!reaches(pos, cx, cy, cz, radius)) continue;
            Skin s = skinFor(partId);
            changed += s.paint(pos, partJointOfVertex(partId),
                    mode == Skin.PaintMode.SMOOTH ? triangles(partId) : null, null,
                    cx, cy, cz, radius, joint, mode, strength);
        }
        if (changed > 0) skins.fireChanged();
        return changed;
    }

    /**
     * Weight of {@code jointId} at each of the part's local vertices; an unweighted vertex counts 1 for
     * the part's own joint (it follows the part rigidly).
     */
    public float[] jointWeights(String partId, String jointId) {
        int n = vertexCount(partId);
        Skin s = skins.skinOf(partId);
        if (s == null || s.vertexCount() != n) {
            float[] out = new float[n];
            if (partId.equals(jointId)) Arrays.fill(out, 1f);
            return out;
        }
        int reg = partId.equals(jointId) ? skins.jointIndex(jointId) : skins.findJoint(jointId);
        if (reg < 0) return new float[n]; // never registered: no weighted vertex can carry it
        return s.jointWeights(reg, partJointOfVertex(partId));
    }

    // ---- picking ----------------------------------------------------------------------------------

    /** Nearest visible-mesh hit of a MODEL-space ray, or null. */
    public Hit raycast(Vector3f origin, Vector3f direction) {
        com.openmason.engine.rendering.viewer.math.CoordinateSystem.Ray ray =
                new com.openmason.engine.rendering.viewer.math.CoordinateSystem.Ray(origin, direction);
        Hit best = null;
        Vector3f a = new Vector3f(), b = new Vector3f(), c = new Vector3f();
        for (ModelPartDescriptor part : parts.getAllParts()) {
            if (!part.visible()) continue;
            float[] pos = cachedModelPositions(part.id());
            int[] tri = triangles(part.id());
            for (int t = 0; t + 2 < tri.length; t += 3) {
                int i0 = tri[t] * 3, i1 = tri[t + 1] * 3, i2 = tri[t + 2] * 3;
                if (i0 + 2 >= pos.length || i1 + 2 >= pos.length || i2 + 2 >= pos.length) continue;
                a.set(pos[i0], pos[i0 + 1], pos[i0 + 2]);
                b.set(pos[i1], pos[i1 + 1], pos[i1 + 2]);
                c.set(pos[i2], pos[i2 + 1], pos[i2 + 2]);
                float d = com.openmason.engine.rendering.viewer.math.RaycastUtil.intersectRayTriangle(ray, a, b, c);
                if (d < (best == null ? Float.POSITIVE_INFINITY : best.distance())) {
                    best = new Hit(part.id(), new Vector3f(direction).mul(d).add(origin), d);
                }
            }
        }
        return best;
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private boolean isVisible(String partId) {
        return parts.getPartById(partId).map(ModelPartDescriptor::visible).orElse(false);
    }

    private List<OMOFormat.BoneEntry> boneList() {
        return bones == null ? List.of() : bones.getBones();
    }

    /** Native clear: every influence remapped to "none" (vertices become unweighted). */
    private void clearSkin(Skin s) {
        int[] drop = new int[Math.max(1, skins.jointIds().size())];
        Arrays.fill(drop, -1);
        s.remapJoints(drop);
    }

    private static boolean reaches(float[] pos, float cx, float cy, float cz, float radius) {
        float r2 = radius * radius;
        for (int i = 0; i + 2 < pos.length; i += 3) {
            float dx = pos[i] - cx, dy = pos[i + 1] - cy, dz = pos[i + 2] - cz;
            if (dx * dx + dy * dy + dz * dz <= r2) return true;
        }
        return false;
    }

    /** The rest-pose rig of the live model (parts first, then bones), as {@link RigModel#build} lays it out. */
    public RigModel restRigModel() {
        List<RigModel.PartSpec> specs = new ArrayList<>();
        for (ModelPartDescriptor p : parts.getAllParts()) {
            PartTransform t = p.transform();
            specs.add(new RigModel.PartSpec(p.id(), p.name(), p.parentId(), vertexCount(p.id()) > 0,
                    new Vector3f(t.origin()), new Vector3f(t.position()), new Vector3f(t.rotation()),
                    new Vector3f(t.scale())));
        }
        return RigModel.build(specs, boneList(), null, null, null);
    }
}
