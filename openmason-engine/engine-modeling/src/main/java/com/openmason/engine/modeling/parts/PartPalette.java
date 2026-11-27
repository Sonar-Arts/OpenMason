package com.openmason.engine.modeling.parts;

import com.openmason.engine.modeling.editable.RenderMesh;
import com.openmason.engine.rig.JointPalette;
import com.openmason.engine.rig.PackedSkin;
import com.openmason.engine.rig.Skin;
import org.joml.Matrix4f;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The editable model's part and bone transforms as GPU matrices (OpenMason #175 F2). GL-free.
 *
 * <p>The part manager bakes every visible part's effective world matrix {@code B_p} into the combined mesh at each
 * structural rebuild ({@link ModelPartManager#getBakedWorldMatrix}). This class binds every render corner to a
 * palette SLOT once per structural rebuild ({@link #refresh}, a per-corner {@link PackedSkin}) and fills the slots
 * every frame ({@link #fill}):
 * <ul>
 *   <li>slot 0: corners no part owns: {@code base};</li>
 *   <li>slot {@code 1 + i}: the rigid corners of part {@code i}: {@code base · W_i · B_i⁻¹} ({@code W_i} = the part's
 *       CURRENT effective world matrix; exactly {@code base} while {@code W_i == B_i});</li>
 *   <li>one slot per (skinned part {@code p}, source joint {@code j}) pair while the {@link PartSkinSource} poses:
 *       {@code base · K_j · R_p · B_p⁻¹}: the baked vertex back to part-local space, to its rest (bind) placement
 *       {@code R_p}, then skinned by {@code K_j}. Weights are the source skin's own {@link Skin#pack} quantization, so
 *       the GPU result equals {@link Skin#deform} of the rest positions with the source's skin matrices.</li>
 * </ul>
 * A part / bone gizmo drag or a pose change therefore costs a palette upload; the vertices are re-baked only when
 * the part manager rebuilds (gizmo release).
 */
public final class PartPalette {

    /** Marker the native packer writes for unweighted vertices (rigid to their part). */
    private static final int RIGID_MARK = 65535;

    private String[] slotPart = new String[0];
    private int[] slotJoint = new int[0];
    private int slotCount;
    private PackedSkin packed;

    private boolean structureDirty = true;
    private boolean packedPosing;
    private long packedRevision;
    private int packedCorners = -1;
    private boolean lastDeformed;
    private long lastFillRebuild = -1;
    private long version;

    private final Matrix4f scratch = new Matrix4f();
    private final Matrix4f scratch2 = new Matrix4f();

    /** The corner bindings must be rebuilt (a structural derive: corners, faces or parts changed). */
    public void invalidate() {
        structureDirty = true;
    }

    /** The current per-corner skin (null before the first {@link #refresh}). */
    public PackedSkin packed() {
        return packed;
    }

    /** Bumped by every rebuild of the corner bindings (hosts upload {@link #packed()} when it moved). */
    public long version() {
        return version;
    }

    /** Palette slots of the current binding. */
    public int slotCount() {
        return slotCount;
    }

    /**
     * Whether the last {@link #fill} moved anything away from the baked vertices, and the part manager has not
     * re-baked since.
     */
    public boolean lastFillDeformed(ModelPartManager parts) {
        return lastDeformed && lastFillRebuild == parts.getRebuildCount();
    }

    /**
     * Rebuilds the corner bindings when stale.
     *
     * @param mesh    the render mesh the GPU draws (corners)
     * @param soupMap the import's editable-vertex-id → combined-soup-indices map (null = unknown: skinned parts
     *                then draw rigidly)
     * @param parts   the part manager
     * @return the new per-corner skin when it changed (upload it), else null
     */
    public PackedSkin refresh(RenderMesh mesh, int[][] soupMap, ModelPartManager parts) {
        PartSkinSource source = parts.getPartSkinSource();
        boolean posing = source != null && source.posing();
        long revision = posing ? source.revision() : 0L;
        int corners = mesh != null ? mesh.cornerCount() : 0;
        if (!structureDirty && packed != null && posing == packedPosing && revision == packedRevision
                && corners == packedCorners) {
            return null;
        }
        build(mesh, soupMap, parts.getAllParts(), parts, posing ? source : null);
        structureDirty = false;
        packedPosing = posing;
        packedRevision = revision;
        packedCorners = corners;
        return packed;
    }

    private void build(RenderMesh mesh, int[][] soupMap, List<ModelPartDescriptor> parts, ModelPartManager pm,
                       PartSkinSource source) {
        int corners = mesh != null ? mesh.cornerCount() : 0;
        int partCount = parts.size();

        // Slots: 0 static, 1 + i rigid part i, then skinned (part, joint) pairs.
        Map<Long, Integer> pairSlots = new HashMap<>();
        int[] pairJoint = new int[8];
        int[] pairPart = new int[8];
        int slots = 1 + partCount;

        // Face → part index (visible parts' stable face ranges).
        int[] cornerPart = new int[corners];
        Arrays.fill(cornerPart, -1);
        if (mesh != null && corners > 0) {
            int maxFace = -1;
            for (int f : mesh.triangleToFaceId()) maxFace = Math.max(maxFace, f);
            int[] facePart = new int[maxFace + 1];
            Arrays.fill(facePart, -1);
            for (int i = 0; i < partCount; i++) {
                MeshRange r = parts.get(i).meshRange();
                if (r == null || r.vertexCount() == 0) continue;
                int end = Math.min(facePart.length, r.faceStart() + r.faceCount());
                for (int f = Math.max(0, r.faceStart()); f < end; f++) facePart[f] = i;
            }
            int[] indices = mesh.indices();
            int[] triFace = mesh.triangleToFaceId();
            for (int t = 0; t < triFace.length; t++) {
                int f = triFace[t];
                int p = f >= 0 && f < facePart.length ? facePart[f] : -1;
                for (int k = 0; k < 3; k++) {
                    int c = indices[t * 3 + k];
                    if (c >= 0 && c < corners) cornerPart[c] = p;
                }
            }
        }

        // Skinned parts (only while posing): their own packed skins over part-local vertices.
        PackedSkin[] partSkin = new PackedSkin[partCount];
        if (source != null && soupMap != null && mesh != null) {
            for (int i = 0; i < partCount; i++) {
                ModelPartDescriptor p = parts.get(i);
                Skin skin = source.skinOf(p.id());
                MeshRange r = p.meshRange();
                PartMeshRebuilder.PartGeometry geo = pm.getPartGeometry(p.id());
                if (skin == null || skin.weightedCount() == 0 || r == null || geo == null
                        || r.vertexCount() != geo.vertexCount() || skin.vertexCount() != geo.vertexCount()) {
                    continue;
                }
                int[] mark = new int[skin.vertexCount()];
                Arrays.fill(mark, RIGID_MARK);
                partSkin[i] = skin.pack(mark);
            }
        }

        int[] ranges = new int[corners * 2];
        short[] influences = new short[Math.max(2, corners * 2)];
        int records = 0;
        for (int c = 0; c < corners; c++) {
            int p = cornerPart[c];
            PackedSkin ps = p >= 0 ? partSkin[p] : null;
            int local = ps != null ? localVertex(mesh, soupMap, c, parts.get(p).meshRange()) : -1;
            ranges[c * 2] = records;
            if (ps == null || local < 0) {
                influences = grow(influences, records + 1);
                influences[records * 2] = (short) (p >= 0 ? 1 + p : 0);
                influences[records * 2 + 1] = (short) PackedSkin.WEIGHT_ONE;
                records++;
                ranges[c * 2 + 1] = 1;
                continue;
            }
            int off = ps.ranges()[local * 2];
            int count = ps.ranges()[local * 2 + 1];
            influences = grow(influences, records + count);
            for (int k = 0; k < count; k++) {
                int joint = Short.toUnsignedInt(ps.influences()[(off + k) * 2]);
                short weight = ps.influences()[(off + k) * 2 + 1];
                int slot;
                if (joint == RIGID_MARK) {
                    slot = 1 + p;
                } else {
                    long key = ((long) p << 32) | joint;
                    Integer s = pairSlots.get(key);
                    if (s == null) {
                        s = slots++;
                        pairSlots.put(key, s);
                        int pi = s - 1 - partCount;
                        if (pi >= pairJoint.length) {
                            pairJoint = Arrays.copyOf(pairJoint, pi * 2 + 2);
                            pairPart = Arrays.copyOf(pairPart, pi * 2 + 2);
                        }
                        pairJoint[pi] = joint;
                        pairPart[pi] = p;
                    }
                    slot = s;
                }
                influences[(records + k) * 2] = (short) slot;
                influences[(records + k) * 2 + 1] = weight;
            }
            records += count;
            ranges[c * 2 + 1] = count;
        }

        slotCount = slots;
        slotPart = new String[slots];
        slotJoint = new int[slots];
        slotJoint[0] = -1;
        for (int i = 0; i < partCount; i++) {
            slotPart[1 + i] = parts.get(i).id();
            slotJoint[1 + i] = -1;
        }
        for (int s = 1 + partCount; s < slots; s++) {
            int pi = s - 1 - partCount;
            slotPart[s] = parts.get(pairPart[pi]).id();
            slotJoint[s] = pairJoint[pi];
        }
        packed = new PackedSkin(ranges, Arrays.copyOf(influences, records * 2));
        version++;
    }

    /** The part-local vertex corner {@code c} stands for inside {@code range}, or -1. */
    private static int localVertex(RenderMesh mesh, int[][] soupMap, int c, MeshRange range) {
        int vertexId = mesh.cornerToVertexId()[c];
        if (vertexId < 0 || vertexId >= soupMap.length || soupMap[vertexId] == null) return -1;
        for (int s : soupMap[vertexId]) {
            if (range.containsVertex(s)) return range.toLocalVertex(s);
        }
        return -1;
    }

    private static short[] grow(short[] a, int records) {
        return records * 2 <= a.length ? a : Arrays.copyOf(a, Math.max(records * 2, a.length * 2));
    }

    /**
     * Fills {@code out} with this frame's slot matrices (see the class doc), sized to {@link #slotCount()}.
     *
     * @param base the model matrix every slot starts with
     * @return true when any slot moves its corners away from the baked positions
     */
    public boolean fill(ModelPartManager parts, Matrix4f base, JointPalette out) {
        int slots = Math.max(1, slotCount);
        out.ensureCapacity(slots);
        out.set(0, base, base);
        boolean deformed = false;
        Map<String, Matrix4f> current = new HashMap<>();
        for (int s = 1; s < slotCount; s++) {
            String partId = slotPart[s];
            Matrix4f baked = parts.getBakedWorldMatrix(partId);
            if (baked == null) {
                out.set(s, base, base);
                continue;
            }
            Matrix4f world = current.computeIfAbsent(partId, parts::getEffectiveWorldMatrix);
            if (slotJoint[s] < 0) {
                if (world.equals(baked)) {
                    out.set(s, base, base);
                } else {
                    scratch.set(base).mul(world).mul(parts.getBakedInverse(partId, scratch2));
                    out.set(s, scratch, scratch);
                    deformed = true;
                }
            }
        }
        if (hasPairs()) {
            fillPairs(parts, base, out);
            deformed = true; // a posed skin
        }
        out.nativeWritten(slots);
        lastDeformed = deformed;
        lastFillRebuild = parts.getRebuildCount();
        return deformed;
    }

    private boolean hasPairs() {
        for (int s = 1; s < slotCount; s++) if (slotJoint[s] >= 0) return true;
        return false;
    }

    private void fillPairs(ModelPartManager parts, Matrix4f base, JointPalette out) {
        PartSkinSource source = parts.getPartSkinSource();
        PartSkinSource.Pose pose = source != null && source.posing() ? source.pose(parts.getAllParts()) : null;
        Map<String, Matrix4f> current = new HashMap<>();
        for (int s = 1; s < slotCount; s++) {
            if (slotJoint[s] < 0) continue;
            String partId = slotPart[s];
            Matrix4f baked = parts.getBakedWorldMatrix(partId);
            if (baked == null) {
                out.set(s, base, base);
                continue;
            }
            Matrix4f rest = pose != null ? pose.partRest().get(partId) : null;
            int j = slotJoint[s];
            Matrix4f k = pose != null && j < pose.jointSkin().length ? pose.jointSkin()[j] : null;
            if (rest == null) {
                // Unresolvable pose: the corner follows its part rigidly.
                Matrix4f world = current.computeIfAbsent(partId, parts::getEffectiveWorldMatrix);
                scratch.set(base).mul(world);
            } else {
                scratch.set(base);
                if (k != null) scratch.mul(k);
                scratch.mul(rest);
            }
            scratch.mul(parts.getBakedInverse(partId, scratch2));
            out.set(s, scratch, scratch);
        }
    }

    /**
     * The CPU twin of the GPU skin: every corner of {@code bakedCorners} ({@code x,y,z} per corner) moved by its
     * slots in {@code palette} with the packed unorm16 weights, accumulated in double (tests, diagnostics).
     */
    public float[] deform(float[] bakedCorners, JointPalette palette) {
        float[] out = new float[bakedCorners.length];
        if (packed == null) return bakedCorners.clone();
        Matrix4f m = new Matrix4f();
        int n = Math.min(packed.vertexCount(), bakedCorners.length / 3);
        for (int c = 0; c < n; c++) {
            double x = 0, y = 0, z = 0;
            float px = bakedCorners[c * 3], py = bakedCorners[c * 3 + 1], pz = bakedCorners[c * 3 + 2];
            int off = packed.ranges()[c * 2];
            int count = packed.ranges()[c * 2 + 1];
            for (int k = 0; k < count; k++) {
                int slot = Short.toUnsignedInt(packed.influences()[(off + k) * 2]);
                double w = Short.toUnsignedInt(packed.influences()[(off + k) * 2 + 1]) / 65535.0;
                palette.skinMatrix(slot, m);
                x += w * (m.m00() * px + m.m10() * py + m.m20() * pz + m.m30());
                y += w * (m.m01() * px + m.m11() * py + m.m21() * pz + m.m31());
                z += w * (m.m02() * px + m.m12() * py + m.m22() * pz + m.m32());
            }
            out[c * 3] = (float) x;
            out[c * 3 + 1] = (float) y;
            out[c * 3 + 2] = (float) z;
        }
        return out;
    }
}
