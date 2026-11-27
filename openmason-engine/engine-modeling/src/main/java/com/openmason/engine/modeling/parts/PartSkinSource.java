package com.openmason.engine.modeling.parts;

import com.openmason.engine.rig.Skin;
import org.joml.Matrix4f;

import java.util.List;
import java.util.Map;

/**
 * Where the editor's GPU skinned preview reads its skins and pose from (OpenMason #175 F2). GL-free: the part
 * manager only holds it ({@link ModelPartManager#setPartSkinSource}); {@link PartPalette} turns it into the
 * per-corner skin and the joint palette the editable model draws with. Without a source, or while it is not
 * {@link #posing()}, every part draws rigidly with its own matrix.
 *
 * <p>Joint indices in {@link #skinOf} influences and in {@link Pose#jointSkin} are this source's own joint space
 * (the editor's skin registry).
 */
public interface PartSkinSource {

    /** True while a pose deforms the skinned parts; false = skins are ignored (the rigid bake is the rest). */
    boolean posing();

    /** Changes whenever a skin's weights, or which parts carry skins, change (forces a re-pack). */
    long revision();

    /** The part's skin over its part-local vertices, or null when the part is rigid. */
    Skin skinOf(String partId);

    /**
     * The current pose, or null when it cannot be resolved (the skinned parts then draw rigidly).
     *
     * @param parts the model's parts in manager order
     */
    Pose pose(List<ModelPartDescriptor> parts);

    /**
     * A resolved pose.
     *
     * @param jointSkin per source joint its skin matrix {@code K_j = W_posed(j) · W_rest(j)⁻¹} (null entry =
     *                  identity)
     * @param partRest  per part id its rest (bind) world matrix {@code R_p}: weighted vertices bind against it
     */
    record Pose(Matrix4f[] jointSkin, Map<String, Matrix4f> partRest) {
    }
}
