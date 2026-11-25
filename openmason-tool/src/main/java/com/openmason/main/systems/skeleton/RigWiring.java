package com.openmason.main.systems.skeleton;

import com.openmason.engine.modeling.parts.ModelPartManager;

/**
 * Connects a model's parts and skeleton into one joint hierarchy for the editor (shared by the live
 * viewport and headless model documents):
 * <ul>
 *   <li>a bone parented to a part inherits the part's effective world matrix;</li>
 *   <li>a part parented to a bone inherits the bone's TAIL frame (rotating the bone rotates it);</li>
 *   <li>any skeleton change (rest edit or pose) rebuilds the part manager's baked mesh;</li>
 *   <li>with a {@link SkinStore}, the skinned preview ({@link SkinPoseDeformer}) deforms weighted
 *       vertices while the pose layer is active, and a weight edit during a pose re-bakes.</li>
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
        if (skins != null) {
            parts.setCombinedMeshDeformer(new SkinPoseDeformer(parts, bones, skins));
            skins.addListener(() -> {
                if (bones.isPoseActive()) parts.refreshCombinedMesh();
            });
        }
    }
}
