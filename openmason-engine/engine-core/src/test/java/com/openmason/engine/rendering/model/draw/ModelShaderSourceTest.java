package com.openmason.engine.rendering.model.draw;

import com.openmason.engine.rendering.shadow.ShadowGlsl;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lit-shader assembly and the preview lighting rig, without a GL context. */
class ModelShaderSourceTest {

    @Test
    void withoutExtensionTheEngineDefinesTheExtraLightHooks() {
        String fragment = ModelShaderSource.litFragment(ModelShaderExtension.NONE);
        assertEquals(1, count(fragment, "vec3 modelExtraLight("));
        assertEquals(1, count(fragment, "vec3 modelApplyExtraLight("));
        assertTrue(fragment.contains(ShadowGlsl.FUNCTIONS), "CSM receiver spliced in");
        assertTrue(fragment.indexOf(ShadowGlsl.FUNCTIONS) < fragment.indexOf("void main()"));
    }

    @Test
    void hostExtensionReplacesTheDefaultHooksAndPrecedesMain() {
        String glsl = """
                uniform int u_hostLights;
                vec3 modelExtraLight(vec3 worldPos, vec3 normal, float sunVisibility) { return vec3(u_hostLights); }
                vec3 modelApplyExtraLight(vec3 lit, vec3 albedo, vec3 extra) { return lit + extra; }
                """;
        String fragment = ModelShaderSource.litFragment(new ModelShaderExtension(glsl, 5));
        assertEquals(1, count(fragment, "vec3 modelExtraLight("), "default hook must not be duplicated");
        assertTrue(fragment.contains("uniform int u_hostLights;"));
        assertTrue(fragment.indexOf("u_hostLights;") < fragment.indexOf("void main()"));
        assertTrue(fragment.indexOf(ShadowGlsl.FUNCTIONS) < fragment.indexOf("u_hostLights;"),
                "extensions may call csmShadowFactor");
    }

    @Test
    void extensionMustKeepTheShadowSamplerOffUnitZero() {
        assertThrows(IllegalArgumentException.class, () -> new ModelShaderExtension("", 0));
        assertFalse(new ModelShaderExtension(null, 6).hasGlsl());
    }

    @Test
    void litMainUsesOnlyNeutralUniformNames() {
        String fragment = ModelShaderSource.litFragment(ModelShaderExtension.NONE);
        for (String uniform : new String[]{"u_fogDensity", "u_fogColor", "u_cameraPos", "u_ambientLight",
                "u_keyDirection", "u_skyLight", "u_selfGlow", "u_useTexture", "u_baseColor"}) {
            assertTrue(fragment.contains("uniform") && fragment.contains(uniform), uniform);
        }
        assertFalse(fragment.contains("underwater"), "fog is generic; the host decides what it means");
    }

    @Test
    void previewKeyLightIsCameraRelative() {
        Vector3f atIdentity = ModelLighting.previewKeyLight(new Matrix4f(), new Matrix4f(), new Vector3f());
        assertTrue(atIdentity.equals(new Vector3f(ModelLighting.PREVIEW_KEY_LIGHT_VIEW), 1e-6f));

        // Orbit the camera 90° about Y: the world-space light turns with it.
        Matrix4f view = new Matrix4f().rotateY((float) Math.toRadians(90));
        Vector3f turned = ModelLighting.previewKeyLight(view, new Matrix4f(), new Vector3f());
        Vector3f expected = new Matrix4f(view).invert()
                .transformDirection(new Vector3f(ModelLighting.PREVIEW_KEY_LIGHT_VIEW)).normalize();
        assertTrue(turned.equals(expected, 1e-6f));
        assertEquals(1f, turned.length(), 1e-6f);

        assertTrue(ModelLighting.previewKeyLight(null, new Matrix4f(), new Vector3f())
                .equals(new Vector3f(ModelLighting.PREVIEW_KEY_LIGHT_VIEW), 0f));
    }

    @Test
    void previewRigIsFullyLitWithNoFogOrGlow() {
        ModelLighting preview = ModelLighting.preview(new Matrix4f());
        assertEquals(ModelLighting.PREVIEW_AMBIENT, preview.ambient());
        assertEquals(1f, preview.skyLight());
        assertEquals(0f, preview.fogDensity());
        assertEquals(0f, preview.selfGlow());
        assertEquals(0.25f, preview.withSelfGlow(0.25f).selfGlow());
        assertEquals(0f, preview.withSelfGlow(-1f).selfGlow(), "glow is clamped at zero");
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) n++;
        return n;
    }
}
