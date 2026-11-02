package com.openmason.engine.rendering.model.draw;

/**
 * Host-supplied additions to the {@link ModelRenderer} lit shader — how a game
 * adds lighting the engine doesn't manage (dynamic point lights) without forking
 * the shader.
 *
 * <p>{@code glsl} is spliced into the fragment shader after the engine's own
 * uniforms and the CSM shadow block. When non-empty it must define:
 * <pre>
 *   // Extra radiance at the fragment (before selfGlow is added).
 *   vec3 modelExtraLight(vec3 worldPos, vec3 normal, float sunVisibility);
 *   // Combine the sun/ambient-lit colour with the extra radiance.
 *   vec3 modelApplyExtraLight(vec3 lit, vec3 albedo, vec3 extra);
 * </pre>
 * and may read the engine uniforms {@code u_ambientLight}, {@code u_skyLight},
 * {@code u_keyDirection} and {@code u_selfGlow}. Empty uses the engine defaults:
 * no extra light, and extra radiance added on top of the lit colour, capped at the
 * albedo.
 *
 * @param glsl              fragment-shader extension source ("" for none)
 * @param shadowTextureUnit texture unit the CSM shadow sampler is assigned to (kept
 *                          off unit 0 even while shadows are disabled — sharing it
 *                          with the 2D sampler is a GL error on strict drivers)
 */
public record ModelShaderExtension(String glsl, int shadowTextureUnit) {

    /** Texture unit the engine assigns the CSM sampler to unless a host says otherwise. */
    public static final int DEFAULT_SHADOW_TEXTURE_UNIT = 5;

    /** No extension. */
    public static final ModelShaderExtension NONE = new ModelShaderExtension("", DEFAULT_SHADOW_TEXTURE_UNIT);

    public ModelShaderExtension {
        glsl = glsl == null ? "" : glsl;
        if (shadowTextureUnit <= 0) {
            throw new IllegalArgumentException("Shadow texture unit must not be 0: " + shadowTextureUnit);
        }
    }

    /** True when the host supplies its own extra-light functions. */
    public boolean hasGlsl() {
        return !glsl.isBlank();
    }
}
