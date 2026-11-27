package com.openmason.engine.rig;

import com.openmason.engine.format.omo.OMOFormat;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The rig of one model: its joints (every part first — joint index {@code i} is part {@code i} — then
 * every bone), which part joint each vertex rigidly follows, and the authored skin weights. Immutable;
 * the native {@link Rig} and the packed GPU attributes are built on first use and shared.
 */
public final class RigModel {

    /** One part as the rig sees it (rest transform in its parent's frame). */
    public record PartSpec(String id, String name, String parentId, boolean hasGeometry,
                           Vector3f origin, Vector3f pos, Vector3f rotDeg, Vector3f scale, boolean hidden) {
        /** A part its author did not hide. */
        public PartSpec(String id, String name, String parentId, boolean hasGeometry,
                        Vector3f origin, Vector3f pos, Vector3f rotDeg, Vector3f scale) {
            this(id, name, parentId, hasGeometry, origin, pos, rotDeg, scale, false);
        }
    }

    private final List<RigJoint> joints;
    private final int partCount;
    private final int[] partJointOfVertex;
    private final List<Skin.Influence> influences;
    private final Map<String, Integer> byId;

    private final RigMorphs morphs;

    private volatile Rig rig;
    private volatile PackedSkin packed;

    private RigModel(List<RigJoint> joints, int partCount, int[] partJointOfVertex, List<Skin.Influence> influences,
                     RigMorphs morphs) {
        this.morphs = morphs != null && morphs.targetCount() > 0 ? morphs : null;
        this.joints = List.copyOf(joints);
        this.partCount = partCount;
        this.partJointOfVertex = partJointOfVertex;
        this.influences = List.copyOf(influences);
        this.byId = new HashMap<>();
        for (int i = 0; i < this.joints.size(); i++) {
            String id = this.joints.get(i).id();
            if (id != null) this.byId.putIfAbsent(id, i);
        }
    }

    /**
     * Builds the rig of a model.
     *
     * @param parts             the parts, in part order
     * @param bones             the skeleton (may be empty)
     * @param skin              skin weights by part-local vertex (may be null)
     * @param partVertexStart   first combined-buffer vertex of each part (for {@code skin}); may be null
     *                          when there is no skin
     * @param partJointOfVertex the part index each combined vertex belongs to (-1 = none, treated as 0)
     */
    public static RigModel build(List<PartSpec> parts, List<OMOFormat.BoneEntry> bones, OMOFormat.SkinData skin,
                                 int[] partVertexStart, int[] partJointOfVertex) {
        return build(parts, bones, skin, partVertexStart, partJointOfVertex, null);
    }

    /**
     * {@link #build(List, List, OMOFormat.SkinData, int[], int[])} with the model's morph targets (OpenMason #171,
     * over the same combined vertices; null = none): they become the native rig's morph channels when it is built
     * ({@link Rig#create(List, RigMorphs)}), before anything can pose it. The model keeps {@code morphs} (it must stay
     * open while the model is in use).
     */
    public static RigModel build(List<PartSpec> parts, List<OMOFormat.BoneEntry> bones, OMOFormat.SkinData skin,
                                 int[] partVertexStart, int[] partJointOfVertex, RigMorphs morphs) {
        Objects.requireNonNull(parts, "parts");
        List<OMOFormat.BoneEntry> boneList = bones == null ? List.of() : bones;
        int vertexCount = partJointOfVertex == null ? 0 : partJointOfVertex.length;

        Map<String, Integer> partIndex = new HashMap<>();
        for (int i = 0; i < parts.size(); i++) {
            if (parts.get(i).id() != null) partIndex.putIfAbsent(parts.get(i).id(), i);
        }
        Map<String, Integer> boneIndex = new HashMap<>();
        for (int b = 0; b < boneList.size(); b++) {
            boneIndex.putIfAbsent(boneList.get(b).id(), parts.size() + b);
        }

        // Which joints skin weights use (they count as moving geometry for head tracking).
        boolean[] skinned = new boolean[parts.size() + boneList.size()];
        List<Skin.Influence> influences = new ArrayList<>();
        if (skin != null && !skin.isEmpty()) {
            int[] jointOfSkinJoint = new int[skin.jointIds().size()];
            for (int k = 0; k < jointOfSkinJoint.length; k++) {
                String id = skin.jointIds().get(k);
                Integer j = partIndex.containsKey(id) ? partIndex.get(id) : boneIndex.get(id);
                jointOfSkinJoint[k] = j == null ? -1 : j;
            }
            for (OMOFormat.PartSkin ps : skin.parts()) {
                Integer p = partIndex.get(ps.partId());
                if (p == null || partVertexStart == null || p >= partVertexStart.length) continue;
                int start = partVertexStart[p];
                for (OMOFormat.SkinVertex sv : ps.vertices()) {
                    int v = start + sv.localVertex();
                    if (sv.localVertex() < 0 || v < 0 || v >= vertexCount) continue;
                    List<Integer> js = new ArrayList<>();
                    List<Float> ws = new ArrayList<>();
                    for (int k = 0; k < sv.joints().length; k++) {
                        int j = jointOfSkinJoint[sv.joints()[k]];
                        if (j < 0) continue;
                        js.add(j);
                        ws.add(sv.weights()[k]);
                        skinned[j] = true;
                    }
                    if (js.isEmpty()) continue;
                    int[] ja = js.stream().mapToInt(Integer::intValue).toArray();
                    float[] wa = new float[ws.size()];
                    for (int k = 0; k < wa.length; k++) wa[k] = ws.get(k);
                    influences.add(new Skin.Influence(v, ja, wa));
                }
            }
        }

        List<RigJoint> joints = new ArrayList<>(parts.size() + boneList.size());
        for (int i = 0; i < parts.size(); i++) {
            PartSpec p = parts.get(i);
            joints.add(RigJoint.part(p.id(), p.name(), parentOf(p.parentId(), i, partIndex, boneIndex),
                p.hasGeometry() || skinned[i], p.origin(), p.pos(), p.rotDeg(), p.scale()).withHidden(p.hidden()));
        }
        for (int b = 0; b < boneList.size(); b++) {
            OMOFormat.BoneEntry e = boneList.get(b);
            int self = parts.size() + b;
            joints.add(RigJoint.bone(e.id(), e.name(), parentOf(e.parentBoneId(), self, partIndex, boneIndex),
                skinned[self],
                new Vector3f(e.originX(), e.originY(), e.originZ()),
                new Vector3f(e.posX(), e.posY(), e.posZ()),
                new Vector3f(e.rotX(), e.rotY(), e.rotZ()),
                new Vector3f(e.endpointX(), e.endpointY(), e.endpointZ())));
        }
        if (joints.isEmpty()) {
            joints.add(RigJoint.part("synthetic:root", "root", -1, true, null, null, null, null));
        }
        int[] partJoints = partJointOfVertex == null ? new int[0] : partJointOfVertex.clone();
        for (int v = 0; v < partJoints.length; v++) {
            if (partJoints[v] < 0 || partJoints[v] >= joints.size()) partJoints[v] = 0;
        }
        if (morphs != null && morphs.targetCount() > 0 && morphs.vertexCount() != partJoints.length) {
            throw new IllegalArgumentException("morph targets cover " + morphs.vertexCount() + " vertices, the model has "
                    + partJoints.length);
        }
        return new RigModel(joints, Math.max(1, parts.size()), partJoints, influences, morphs);
    }

    /** A rig with no bones and no skin: one joint per part (the pre-rig model). */
    public static RigModel ofParts(List<PartSpec> parts, int[] partJointOfVertex) {
        return build(parts, List.of(), null, null, partJointOfVertex);
    }

    /** A parent id resolved across parts and bones; unknown or self = root. */
    private static int parentOf(String parentId, int self, Map<String, Integer> parts, Map<String, Integer> bones) {
        if (parentId == null || parentId.isBlank()) return -1;
        Integer p = parts.get(parentId);
        if (p == null) p = bones.get(parentId);
        return p == null || p == self ? -1 : p;
    }

    public List<RigJoint> joints() {
        return joints;
    }

    public int jointCount() {
        return joints.size();
    }

    /** Parts occupy joints {@code [0, partCount)}. */
    public int partCount() {
        return partCount;
    }

    /** Joint index of an id (part or bone), -1 when unknown. */
    public int jointOf(String id) {
        Integer j = id == null ? null : byId.get(id);
        return j == null ? -1 : j;
    }

    /** True when the model has bones or authored weights (more than rigid parts). */
    public boolean isSkeletal() {
        return joints.size() > partCount || !influences.isEmpty();
    }

    /** The part joint each vertex follows when it has no weights. Do not modify. */
    public int[] partJointOfVertex() {
        return partJointOfVertex;
    }

    /** Authored influences, by combined vertex index. */
    public List<Skin.Influence> influences() {
        return influences;
    }

    /** The native rig (built on first use). */
    public Rig rig() {
        Rig r = rig;
        if (r == null) {
            synchronized (this) {
                r = rig;
                if (r == null) {
                    r = Rig.create(joints, morphs);
                    rig = r;
                }
            }
        }
        return r;
    }

    /** The model's morph targets (shape keys, OpenMason #171), or null when it has none. */
    public RigMorphs morphs() {
        return morphs;
    }

    /** Number of morph targets (= the rig's morph channels); 0 without. */
    public int morphCount() {
        return morphs == null ? 0 : morphs.targetCount();
    }

    /** A fresh editable skin holding the authored weights (caller closes it). */
    public Skin newSkin() {
        return Skin.of(partJointOfVertex.length, influences);
    }

    /** Packed GPU joint/weight attributes for every vertex (built on first use). */
    public PackedSkin packed() {
        PackedSkin p = packed;
        if (p == null) {
            synchronized (this) {
                p = packed;
                if (p == null) {
                    if (influences.isEmpty()) {
                        p = PackedSkin.rigid(partJointOfVertex);
                    } else {
                        try (Skin skin = newSkin()) {
                            p = skin.pack(partJointOfVertex);
                        }
                    }
                    packed = p;
                }
            }
        }
        return p;
    }
}
