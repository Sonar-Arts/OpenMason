package com.openmason.engine.rendering.particles;

/**
 * Host-supplied lighting for the particle shader — how a game lights particles with what the engine doesn't
 * manage (sky light, torch point lights, shadows) without forking the shader. Mirrors
 * {@code ModelShaderExtension} for models.
 *
 * <p>{@code glsl} is spliced into the <b>vertex</b> shader (lighting is evaluated per corner, four times per
 * particle, never per fragment). When non-empty it must define
 * <pre>
 *   // Light multiplier for a non-emissive particle centred at renderPos (render space).
 *   vec3 particleLight(vec3 renderPos);
 * </pre>
 * and may read the engine uniforms {@code u_ambient}, {@code u_camPos} and its own uniforms, which the host
 * sets through {@link ParticleFrame#uniforms}. Empty uses the engine default {@code vec3(u_ambient)}.
 * Emissive particles never call it.
 */
public record ParticleShaderExtension(String glsl) {

    /** No extension: non-emissive particles are lit by {@link ParticleFrame#ambient} alone. */
    public static final ParticleShaderExtension NONE = new ParticleShaderExtension("");

    static final String DEFAULT_GLSL = "vec3 particleLight(vec3 renderPos) { return vec3(u_ambient); }\n";

    public ParticleShaderExtension {
        glsl = glsl == null ? "" : glsl;
    }

    public boolean hasGlsl() {
        return !glsl.isBlank();
    }

    String source() {
        return hasGlsl() ? glsl + "\n" : DEFAULT_GLSL;
    }
}
