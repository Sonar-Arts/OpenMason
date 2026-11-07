package com.openmason.engine.rendering.particles;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** GL-free: what each extension splices into the vertex shader, and the declarations it refuses. */
class ParticleShaderExtensionTest {

    private static final String LIGHT = "vec3 particleLight(vec3 renderPos) { return vec3(1.0); }";
    private static final String COLOR = "vec4 particleColor(vec4 color, vec3 renderPos) { return color.bgra; }";

    @Test
    void noExtensionGetsBothEngineDefaults() {
        String src = ParticleShaderExtension.NONE.source();
        assertTrue(src.contains(ParticleShaderExtension.DEFAULT_LIGHT));
        assertTrue(src.contains(ParticleShaderExtension.DEFAULT_COLOR));
        assertEquals(ParticleShaderExtension.NONE, new ParticleShaderExtension(""), "\"\" is NONE");
    }

    @Test
    void theOneArgumentFormStaysLightingOnly() {
        ParticleShaderExtension e = new ParticleShaderExtension(LIGHT);
        assertTrue(e.definesLight());
        assertFalse(e.definesColor());
        assertFalse(e.source().contains(ParticleShaderExtension.DEFAULT_LIGHT), "the host's light replaces the default");
        assertTrue(e.source().contains(ParticleShaderExtension.DEFAULT_COLOR), "colour falls back to the default");
    }

    @Test
    void aColourHookKeepsTheDefaultLight() {
        String src = ParticleShaderExtension.coloring(COLOR).source();
        assertTrue(src.contains(COLOR));
        assertTrue(src.contains(ParticleShaderExtension.DEFAULT_LIGHT));
        assertFalse(src.contains(ParticleShaderExtension.DEFAULT_COLOR));
    }

    @Test
    void bothHooksReplaceBothDefaults() {
        String src = ParticleShaderExtension.of(LIGHT + "\n" + COLOR, true, true).source();
        assertFalse(src.contains(ParticleShaderExtension.DEFAULT_LIGHT));
        assertFalse(src.contains(ParticleShaderExtension.DEFAULT_COLOR));
    }

    @Test
    void inconsistentDeclarationsAreRefusedBeforeTheyReachTheGlslCompiler() {
        assertThrows(IllegalArgumentException.class, () -> ParticleShaderExtension.of(LIGHT, false, false),
                "GLSL with no declared hook");
        assertThrows(IllegalArgumentException.class, () -> ParticleShaderExtension.of("", true, false),
                "a hook with no GLSL");
        assertThrows(IllegalArgumentException.class, () -> ParticleShaderExtension.coloring(LIGHT),
                "declares particleColor but defines only particleLight");
        assertThrows(IllegalArgumentException.class, () -> ParticleShaderExtension.lighting(COLOR),
                "declares particleLight but defines only particleColor");
    }
}
