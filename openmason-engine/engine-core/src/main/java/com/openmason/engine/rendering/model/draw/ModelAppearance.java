package com.openmason.engine.rendering.model.draw;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-draw appearance input of {@link ModelRenderer} (OpenMason #162): which parts draw and how materials are dyed,
 * as resolved natively by {@code character.Appearance} (the host copies the results in; nothing is decided here).
 * <ul>
 *   <li>Visible parts: a bitset over joints (part i = joint i, bit i of word i/64, {@code Appearance.visible}); a
 *       hidden part issues no draw call. Null = every part.</li>
 *   <li>Dyes per material id: 4 rgb tints and an optional RGBA dye-mask texture (GL name, bound on
 *       {@link #MASK_UNIT}); without a mask the material is dyed whole by tint 0. The lit pass multiplies the albedo
 *       by {@code 1 + sum_k mask_k (tint_k - 1)}.</li>
 *   <li>Morph weights (shape keys, OpenMason #171): one raw weight per morph target of the mesh (the rig's channel
 *       order; e.g. {@code AnimGraphInstance.morphWeights} or {@code ModelPoseSolver.morphWeights}), clamped and
 *       uploaded by the renderer per draw. Null = the targets' default weights. The array is read at draw time (not
 *       copied): refill it each frame.</li>
 *   <li>Vertex-group masks (OpenMason #172): HIDDEN groups remove every triangle wholly inside them (a garment's
 *       covered skin), KEPT groups keep only the triangles inside them (Blender's Mask modifier). Resolved against the
 *       model's mask plan once per change ({@link ModelDrawPlan#forAppearance}, cached by {@link #groupMask()}); a
 *       model without vertex groups ignores them.</li>
 * </ul>
 * Reused across frames; not thread-safe.
 */
public final class ModelAppearance {

    /** Texture unit of the dye mask (free in the engine and Stonebreak). */
    public static final int MASK_UNIT = 30;

    /** One material's dye: {@code maskTexture} 0 = none (whole material by tint 0); tints 4 x rgb. */
    public record Dye(int maskTexture, float[] tints) {}

    /**
     * The hidden / kept vertex-group names of an appearance: an immutable value, the cache key of a masked draw plan
     * (equal sets = the same ranges).
     */
    public record GroupMask(java.util.Set<String> hidden, java.util.Set<String> kept) {
        public GroupMask {
            hidden = java.util.Set.copyOf(hidden);
            kept = java.util.Set.copyOf(kept);
        }
    }

    private long[] visible;
    private final Map<Integer, Dye> dyes = new HashMap<>();
    private float[] morphWeights;
    private java.util.Set<String> hiddenGroups = java.util.Set.of();
    private java.util.Set<String> keptGroups = java.util.Set.of();
    private GroupMask groupMask;

    /** The visible-joint bitset (copied); null = every part draws. */
    public ModelAppearance setVisible(long[] bits) {
        this.visible = bits == null ? null : Arrays.copyOf(bits, bits.length);
        return this;
    }

    public ModelAppearance setDye(int materialId, int maskTexture, float[] tints) {
        if (tints == null || tints.length < 12) throw new IllegalArgumentException("tints: 4 x rgb");
        dyes.put(materialId, new Dye(maskTexture, Arrays.copyOf(tints, 12)));
        return this;
    }

    public ModelAppearance clearDyes() {
        dyes.clear();
        return this;
    }

    /** Raw morph weights for the next draws (null = defaults); read at draw time, not copied. */
    public ModelAppearance setMorphWeights(float[] weights) {
        this.morphWeights = weights;
        return this;
    }

    public float[] morphWeights() {
        return morphWeights;
    }

    /** Vertex groups whose covered triangles are hidden (null / empty = none). */
    public ModelAppearance setHiddenGroups(java.util.Collection<String> groups) {
        java.util.Set<String> next = groups == null ? java.util.Set.of() : java.util.Set.copyOf(groups);
        if (!next.equals(hiddenGroups)) {
            hiddenGroups = next;
            groupMask = null;
        }
        return this;
    }

    /** Vertex groups the model is cut down to (Blender Mask modifier; null / empty = no cut). */
    public ModelAppearance setKeptGroups(java.util.Collection<String> groups) {
        java.util.Set<String> next = groups == null ? java.util.Set.of() : java.util.Set.copyOf(groups);
        if (!next.equals(keptGroups)) {
            keptGroups = next;
            groupMask = null;
        }
        return this;
    }

    public java.util.Set<String> hiddenGroups() {
        return hiddenGroups;
    }

    public java.util.Set<String> keptGroups() {
        return keptGroups;
    }

    /** The current group mask (the same instance until the groups change), or null when no group is hidden or kept. */
    public GroupMask groupMask() {
        if (hiddenGroups.isEmpty() && keptGroups.isEmpty()) return null;
        GroupMask m = groupMask;
        if (m == null) {
            m = new GroupMask(hiddenGroups, keptGroups);
            groupMask = m;
        }
        return m;
    }

    /** Whether part {@code part} draws. */
    public boolean partVisible(int part) {
        if (visible == null) return true;
        int word = part >>> 6;
        return word < visible.length && (visible[word] & (1L << (part & 63))) != 0;
    }

    /** The dye of a material, or null. */
    public Dye dye(int materialId) {
        return dyes.get(materialId);
    }

    boolean hasDyes() {
        return !dyes.isEmpty();
    }
}
