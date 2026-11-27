package com.openmason.main.systems.skeleton;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.modeling.parts.ModelPartDescriptor;
import com.openmason.engine.modeling.parts.ModelPartManager;
import com.openmason.engine.modeling.parts.PartMeshRebuilder;
import com.openmason.engine.modeling.parts.PartSkinSource;
import com.openmason.engine.modeling.parts.PartTransform;
import com.openmason.engine.rig.JointPalette;
import com.openmason.engine.rig.Rig;
import com.openmason.engine.rig.RigModel;
import com.openmason.engine.rig.RigPoser;
import com.openmason.engine.rig.Skin;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The editor's skinned preview as a GPU palette source (OpenMason #175 F2; replaces the CPU deformer that re-baked
 * and re-welded posed positions). While the {@link BoneStore} pose layer is active, the editable model skins every
 * weighted part on the GPU ({@code PartPalette}); this class only marshals the pose, all math is the native rig's
 * ({@code cenda/rig.h}):
 * <ol>
 *   <li>a rig of the model's parts and bones ({@link RigModel#build}: part {@code i} = joint {@code i}, then the
 *       bones) whose REST is the preview's rest snapshot ({@link BoneStore#partRestOf}, the bones' rest entries);</li>
 *   <li>the POSE = the current local values (part transforms, posed bones) through {@link RigPoser#poseLocals} →
 *       skin matrices {@code K}, remapped onto the skin registry's joint order;</li>
 *   <li>each part's rest world matrix {@code R_p}: weighted vertices bind against it.</li>
 * </ol>
 * It is also the part manager's post-bake hook that keeps the skins in step with edited / deleted parts
 * ({@link SkinStore#reconcile}); it never moves a baked vertex.
 */
public final class SkinPosePalette implements PartSkinSource, ModelPartManager.CombinedMeshDeformer {

    private final ModelPartManager partManager;
    private final BoneStore boneStore;
    private final SkinStore skinStore;

    private final RigPoser poser = new RigPoser();
    private final JointPalette posed = new JointPalette();
    private final JointPalette rest = new JointPalette();
    private long revision;

    public SkinPosePalette(ModelPartManager partManager, BoneStore boneStore, SkinStore skinStore) {
        this.partManager = partManager;
        this.boneStore = boneStore;
        this.skinStore = skinStore;
        skinStore.addListener(() -> revision++);
    }

    /** True when the preview currently skins anything. */
    @Override
    public boolean posing() {
        return boneStore.isPoseActive() && !skinStore.isEmpty();
    }

    @Override
    public long revision() {
        return revision;
    }

    @Override
    public Skin skinOf(String partId) {
        return skinStore.skinOf(partId);
    }

    /** Post-bake hook: every rebuild (rest included) keeps the skins in step with edited / deleted parts. */
    @Override
    public void deform(PartMeshRebuilder.RebuildResult result) {
        if (skinStore.reconcile(this::localPositions)) revision++;
    }

    @Override
    public Pose pose(List<ModelPartDescriptor> parts) {
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

            List<String> registry = skinStore.jointIds();
            Matrix4f[] jointSkin = new Matrix4f[registry.size()];
            for (int k = 0; k < jointSkin.length; k++) {
                int j = model.jointOf(registry.get(k));
                jointSkin[k] = j >= 0 ? posed.skinMatrix(j, new Matrix4f()) : null;
            }
            Map<String, Matrix4f> partRest = new HashMap<>();
            for (int i = 0; i < parts.size(); i++) {
                partRest.put(parts.get(i).id(), rest.worldMatrix(i, new Matrix4f()));
            }
            return new Pose(jointSkin, partRest);
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
