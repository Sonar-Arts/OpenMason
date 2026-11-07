package com.openmason.engine.rendering.particles;

/**
 * Host-supplied GLSL for the particle shader — how a game adds what the engine doesn't manage (sky light,
 * torch point lights, regional weather fog…) without forking the shader. Mirrors {@code ModelShaderExtension}
 * for models.
 *
 * <p>{@link #glsl} is spliced into the <b>vertex</b> shader: both hooks run per quad corner, four times per
 * particle, never per fragment. It may declare its own uniforms (set them through {@link ParticleFrame#uniforms})
 * and read the engine uniforms {@code u_ambient}, {@code u_camPos}, {@code u_fogDensity} and {@code u_fogColor}.
 * It defines the hooks it declares; the engine supplies defaults for the others:
 * <pre>
 *   // definesLight: light multiplier for a NON-emissive particle centred at renderPos (render space).
 *   // Default: vec3(u_ambient).
 *   vec3 particleLight(vec3 renderPos);
 *
 *   // definesColor: the particle's final colour, for EVERY particle (emissive ones too), after lighting and the
 *   // engine's exponential fog. Premultiplied in and out: rgb is already x coverage, and a = coverage x
 *   // (1 - additive) - so an additive particle has a = 0 and should fade, not be covered, e.g. a fog blend is
 *   // rgb = mix(rgb, fogColor * a, f). Default: returns color unchanged.
 *   vec4 particleColor(vec4 color, vec3 renderPos);
 * </pre>
 * A host that applies its own fog in {@code particleColor} usually sets {@link ParticleFrame#fogDensity} to 0.
 *
 * @param glsl         vertex-shader source defining the declared hooks ("" with no hooks = {@link #NONE})
 * @param definesLight {@code glsl} defines {@code particleLight}
 * @param definesColor {@code glsl} defines {@code particleColor}
 */
public record ParticleShaderExtension(String glsl, boolean definesLight, boolean definesColor) {

    /** No extension: ambient lighting, the engine fog only. */
    public static final ParticleShaderExtension NONE = new ParticleShaderExtension("", false, false);

    static final String DEFAULT_LIGHT = "vec3 particleLight(vec3 renderPos) { return vec3(u_ambient); }\n";
    static final String DEFAULT_COLOR = "vec4 particleColor(vec4 color, vec3 renderPos) { return color; }\n";

    public ParticleShaderExtension {
        glsl = glsl == null ? "" : glsl;
        boolean hasSource = !glsl.isBlank();
        if (hasSource != (definesLight || definesColor)) {
            throw new IllegalArgumentException(hasSource
                    ? "particle shader extension GLSL declares no hook (particleLight / particleColor)"
                    : "particle shader extension declares a hook but has no GLSL");
        }
        if (definesLight && !glsl.contains("particleLight")) {
            throw new IllegalArgumentException("particle shader extension declares particleLight but does not define it");
        }
        if (definesColor && !glsl.contains("particleColor")) {
            throw new IllegalArgumentException("particle shader extension declares particleColor but does not define it");
        }
    }

    /** Lighting only ({@code glsl} defines {@code particleLight}); "" = {@link #NONE}. Kept for existing hosts. */
    public ParticleShaderExtension(String glsl) {
        this(glsl, glsl != null && !glsl.isBlank(), false);
    }

    /** {@code glsl} defines {@code particleLight}. */
    public static ParticleShaderExtension lighting(String glsl) {
        return new ParticleShaderExtension(glsl, true, false);
    }

    /** {@code glsl} defines {@code particleColor} (fog, tint, grading). */
    public static ParticleShaderExtension coloring(String glsl) {
        return new ParticleShaderExtension(glsl, false, true);
    }

    /** {@code glsl} defines the hooks flagged. */
    public static ParticleShaderExtension of(String glsl, boolean definesLight, boolean definesColor) {
        return new ParticleShaderExtension(glsl, definesLight, definesColor);
    }

    public boolean hasGlsl() {
        return !glsl.isBlank();
    }

    /** What replaces the vertex shader's hook marker: the host GLSL plus defaults for undeclared hooks. */
    String source() {
        StringBuilder s = new StringBuilder();
        if (hasGlsl()) {
            s.append(glsl).append('\n');
        }
        if (!definesLight) {
            s.append(DEFAULT_LIGHT);
        }
        if (!definesColor) {
            s.append(DEFAULT_COLOR);
        }
        return s.toString();
    }
}
