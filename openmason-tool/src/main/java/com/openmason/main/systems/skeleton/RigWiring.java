package com.openmason.main.systems.skeleton;

import com.openmason.engine.modeling.parts.ModelPartManager;

/**
 * Connects a model's parts and skeleton into one joint hierarchy for the editor (shared by the live
 * viewport and headless model documents):
 * <ul>
 *   <li>a bone parented to a part inherits the part's effective world matrix;</li>
 *   <li>a part parented to a bone inherits the bone's TAIL frame (rotating the bone rotates it);</li>
 *   <li>a skeleton REST change rebuilds the part manager's baked mesh (deferred to the end of an open gizmo
 *       interaction, {@link ModelPartManager#beginTransformInteraction});</li>
 *   <li>a preview POSE change only moves matrices: the editable model draws it through its GPU palette
 *       (OpenMason #175 F2) and nothing is re-baked; clearing the pose re-bakes only when a rebuild happened
 *       while posing;</li>
 *   <li>with a {@link SkinStore}, the skinned preview ({@link SkinPosePalette}) skins weighted vertices on the GPU
 *       while the pose layer is active.</li>
 * </ul>
 */
public final class RigWiring {

    private RigWiring() {
    }

    /** Wire {@code bones} (and optionally {@code skins}) to {@code parts}. */
    public static void connect(ModelPartManager parts, BoneStore bones, SkinStore skins) {
        bones.setExternalParentResolver(
                nodeId -> parts.getPartById(nodeId).isPresent()
                        ? parts.getEffectiveWorldMatrix(nodeId)
                        : null);
        parts.setExternalParentResolver(
                nodeId -> bones.getById(nodeId) != null
                        ? bones.getTailWorldTransform(nodeId)
                        : null);
        bones.setOnChange(parts::notifyExternalHierarchyChanged);
        bones.setInteractionHooks(parts::beginTransformInteraction, parts::endTransformInteraction);
        bones.setOnPoseChange(() -> {
            parts.notifyExternalPoseChanged();
            if (!bones.isPoseActive()) parts.rebakeIfStale();
        });
        if (skins != null) {
            SkinPosePalette preview = new SkinPosePalette(parts, bones, skins);
            parts.setPartSkinSource(preview);
            parts.setCombinedMeshDeformer(preview);
        }
    }
}
