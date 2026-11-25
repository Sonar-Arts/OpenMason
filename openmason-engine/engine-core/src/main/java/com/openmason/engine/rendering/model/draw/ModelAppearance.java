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
 * </ul>
 * Reused across frames; not thread-safe.
 */
public final class ModelAppearance {

    /** Texture unit of the dye mask (free in the engine and Stonebreak). */
    public static final int MASK_UNIT = 30;

    /** One material's dye: {@code maskTexture} 0 = none (whole material by tint 0); tints 4 x rgb. */
    public record Dye(int maskTexture, float[] tints) {}

    private long[] visible;
    private final Map<Integer, Dye> dyes = new HashMap<>();

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
