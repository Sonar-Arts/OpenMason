package com.openmason.engine.rendering.model.draw;

/**
 * Resolves a draw batch's material id to the GL texture the lit pass samples.
 *
 * <ul>
 *   <li>{@code > 0} — bind that texture.</li>
 *   <li>{@code 0} — draw the batch untextured in the lighting's base colour.</li>
 *   <li>{@code < 0} — skip the batch.</li>
 * </ul>
 */
@FunctionalInterface
public interface MaterialTextures {

    /** Every batch untextured (solid / "unrendered" display). */
    MaterialTextures UNTEXTURED = materialId -> 0;

    int textureFor(int materialId);

    /**
     * The material's base colour (OMO 1.9, RGBA, Blender / glTF semantics: it multiplies the texture), or null =
     * white. Resolvers whose textures already hold the colour (the editor's solid-colour PNGs) keep the default.
     */
    default float[] baseColorFor(int materialId) {
        return null;
    }
}
