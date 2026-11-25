package com.openmason.main.systems.skeleton;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.modeling.parts.MeshRange;
import com.openmason.engine.modeling.parts.ModelPartDescriptor;
import com.openmason.engine.modeling.parts.ModelPartManager;
import com.openmason.engine.modeling.parts.PartMeshRebuilder;
import com.openmason.engine.modeling.parts.PartTransform;
import com.openmason.engine.rig.JointPalette;
import com.openmason.engine.rig.Rig;
import com.openmason.engine.rig.RigModel;
import com.openmason.engine.rig.RigPoser;
import com.openmason.engine.rig.Skin;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * The editor's skinned preview: a {@link ModelPartManager.CombinedMeshDeformer} that, while the
 * {@link BoneStore} pose layer is active, replaces the rigidly baked vertices of every part that has
 * skin weights with their skinned positions. All math is native ({@code cenda/rig.h}); this class
 * only marshals:
 * <ol>
 *   <li>a rig of the model's parts and bones ({@link RigModel#build}: part {@code i} = joint
 *       {@code i}, then the bones) whose REST is the preview's rest snapshot
 *       ({@link BoneStore#partRestOf}, the bones' rest entries);</li>
 *   <li>the POSE = the current local values (part transforms, posed bones) through
 *       {@link RigPoser#poseLocals} → skin matrices {@code K};</li>
 *   <li>per weighted part: its part-local vertices to rest model space (a palette holding the rest
 *       world matrices, {@link Skin#deform} with no skin), then {@link Skin#deform} with a copy of the
 *       part's skin whose REGISTRY joint indices are remapped onto the rig's joint order.</li>
 * </ol>
 * At rest (no pose layer) it does nothing: {@code K} is the identity there.
 */
public final class SkinPoseDeformer implements ModelPartManager.CombinedMeshDeformer {

    private final ModelPartManager partManager;
    private final BoneStore boneStore;
    private final SkinStore skinStore;

    private final RigPoser poser = new RigPoser();
    private final JointPalette posed = new JointPalette();
    private final JointPalette rest = new JointPalette();
    private final JointPalette restAsSkin = new JointPalette();

    public SkinPoseDeformer(ModelPartManager partManager, BoneStore boneStore, SkinStore skinStore) {
        this.partManager = partManager;
        this.boneStore = boneStore;
        this.skinStore = skinStore;
    }

    /** True when a rebuild right now would deform anything. */
    public boolean isActive() {
        return boneStore.isPoseActive() && !skinStore.isEmpty();
    }

    @Override
    public void deform(PartMeshRebuilder.RebuildResult result) {
        // Every rebuild (rest included) keeps the skins in step with edited / deleted parts.
        skinStore.reconcile(this::localPositions);
        if (!isActive()) return;
        List<ModelPartDescriptor> parts = partManager.getAllParts();
        boolean any = false;
        for (ModelPartDescriptor p : parts) {
            if (weightedAndBaked(p, result)) {
                any = true;
                break;
            }
        }
        if (!any) return;

        // 1. Rig with REST = the preview's rest snapshot (parts) + the bones' rest entries.
        List<RigModel.PartSpec> specs = new ArrayList<>(parts.size());
        for (ModelPartDescriptor p : parts) {
            PartTransform r = restOf(p);
            specs.add(new RigModel.PartSpec(p.id(), p.name(), p.parentId(), true,
                    new Vector3f(r.origin()), new Vector3f(r.position()),
                    new Vector3f(r.rotation()), new Vector3f(r.scale())));
        }
        List<OMOFormat.BoneEntry> bones = boneStore.getBones();
        RigModel model = RigModel.build(specs, bones, null, null, null);

        // 2. POSE = current locals: part transforms, then bones (posed or rest), scale 1.
        float[] locals = new float[model.jointCount() * 9];
        for (int i = 0; i < parts.size(); i++) {
            PartTransform t = parts.get(i).transform();
            put(locals, i, t.position(), t.rotation(), t.scale());
        }
        Vector3f one = new Vector3f(1f, 1f, 1f);
        for (int b = 0; b < bones.size(); b++) {
            String id = bones.get(b).id();
            put(locals, parts.size() + b, boneStore.getLocalPosition(id), boneStore.getLocalRotation(id), one);
        }

        Rig rig = model.rig();
        try {
            poser.clearExtras();
            poser.poseLocals(rig, locals, null, posed);
            poser.poseLocals(rig, null, null, rest);
            // A palette whose "skin" matrices are the rest world frames: deforming part-local
            // vertices with it yields their rest (bind) model-space positions.
            restAsSkin.ensureCapacity(model.jointCount());
            Matrix4f w = new Matrix4f();
            for (int j = 0; j < model.jointCount(); j++) {
                rest.worldMatrix(j, w);
                restAsSkin.set(j, w, w);
            }

            // Registry joint index -> rig joint index.
            List<String> registry = skinStore.jointIds();
            int[] remap = new int[registry.size()];
            for (int k = 0; k < remap.length; k++) remap[k] = model.jointOf(registry.get(k));

            float[] out = result.combinedVertices();
            for (int i = 0; i < parts.size(); i++) {
                ModelPartDescriptor p = parts.get(i);
                if (!weightedAndBaked(p, result)) continue;
                MeshRange range = result.partRanges().get(p.id());
                PartMeshRebuilder.PartGeometry geo = partManager.getPartGeometry(p.id());
                int count = geo.vertexCount();
                int[] partJoint = new int[count];
                java.util.Arrays.fill(partJoint, i);
                float[] restPositions = Skin.deform(null, partJoint, geo.vertices(), restAsSkin, null);
                float[] skinned;
                try (Skin copy = skinStore.skinOf(p.id()).copy()) {
                    if (remap.length > 0) copy.remapJoints(remap);
                    skinned = Skin.deform(copy, partJoint, restPositions, posed, null);
                }
                System.arraycopy(skinned, 0, out, range.vertexStart() * 3, count * 3);
            }
        } finally {
            rig.close();
        }
    }

    /** A part's current part-local positions, or null when the part no longer exists. */
    private float[] localPositions(String partId) {
        if (partManager.getPartById(partId).isEmpty()) return null;
        PartMeshRebuilder.PartGeometry geo = partManager.getPartGeometry(partId);
        if (geo == null || geo.vertices() == null) return new float[0];
        int n = Math.min(geo.vertexCount() * 3, geo.vertices().length);
        return n == geo.vertices().length ? geo.vertices() : java.util.Arrays.copyOf(geo.vertices(), n);
    }

    private PartTransform restOf(ModelPartDescriptor p) {
        PartTransform r = boneStore.partRestOf(p.id());
        return r != null ? r : p.transform();
    }

    private boolean weightedAndBaked(ModelPartDescriptor p, PartMeshRebuilder.RebuildResult result) {
        Skin skin = skinStore.skinOf(p.id());
        if (skin == null || skin.weightedCount() == 0) return false;
        MeshRange range = result.partRanges().get(p.id());
        PartMeshRebuilder.PartGeometry geo = partManager.getPartGeometry(p.id());
        return range != null && geo != null && geo.vertices() != null
                && range.vertexCount() == geo.vertexCount()
                && skin.vertexCount() == geo.vertexCount()
                && (range.vertexStart() + range.vertexCount()) * 3 <= result.combinedVertices().length;
    }

    private static void put(float[] locals, int joint, Vector3f pos, Vector3f rot, Vector3f scale) {
        int o = joint * 9;
        locals[o] = pos.x;
        locals[o + 1] = pos.y;
        locals[o + 2] = pos.z;
        locals[o + 3] = rot.x;
        locals[o + 4] = rot.y;
        locals[o + 5] = rot.z;
        locals[o + 6] = scale.x;
        locals[o + 7] = scale.y;
        locals[o + 8] = scale.z;
    }
}
