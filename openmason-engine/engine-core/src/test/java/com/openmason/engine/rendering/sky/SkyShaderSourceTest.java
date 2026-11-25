package com.openmason.engine.rendering.sky;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every uniform {@link SkyRenderer} sets is declared by the sky shaders: a missing one only
 * shows on a GL context, as a startup crash in {@code createUniform}.
 */
class SkyShaderSourceTest {

    private static String resource(String path) throws IOException {
        try (InputStream in = SkyRenderer.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** A shader source with its {@code #include "/path"} lines expanded (each file once). */
    private static String expanded(String path, java.util.Set<String> seen) throws IOException {
        if (!seen.add(path)) return "";
        StringBuilder out = new StringBuilder();
        String text = resource(path);
        Matcher inc = Pattern.compile("^#include\\s+\"([^\"]+)\"", Pattern.MULTILINE).matcher(text);
        int at = 0;
        while (inc.find()) {
            out.append(text, at, inc.start()).append(expanded(inc.group(1), seen));
            at = inc.end();
        }
        return out.append(text.substring(at)).toString();
    }

    @Test
    void everyUniformTheRendererSetsIsDeclared() throws IOException {
        java.util.Set<String> seen = new java.util.HashSet<>();
        String shaders = expanded("/shaders/sky/sky.vert", seen) + expanded("/shaders/sky/sky.frag", seen);
        String renderer = Files.readString(
                Path.of("src/main/java/com/openmason/engine/rendering/sky/SkyRenderer.java"));
        java.util.List<String> names = new java.util.ArrayList<>();
        Matcher m = Pattern.compile("createUniform\\(\"(\\w+)\"\\)").matcher(renderer);
        while (m.find()) names.add(m.group(1));
        // Uniforms registered in a loop over PAINTED_UNIFORMS (the hand-painted sky).
        Matcher painted = Pattern.compile("PAINTED_UNIFORMS\\s*=\\s*\\{([^}]*)}").matcher(renderer);
        assertTrue(painted.find(), "SkyRenderer.PAINTED_UNIFORMS not found");
        Matcher quoted = Pattern.compile("\"(\\w+)\"").matcher(painted.group(1));
        while (quoted.find()) names.add(quoted.group(1));
        for (String name : names) {
            assertTrue(Pattern.compile("uniform\\s+\\w+\\s+" + name + "\\s*;").matcher(shaders).find(),
                    "sky shaders do not declare uniform " + name);
        }
        assertTrue(names.size() >= 20, "found only " + names.size() + " uniforms; did the renderer change shape?");
    }

    /**
     * The sky is dithered against banding: one TPDF dither per pixel, added in working space to the physical sky
     * and to the painted sky (before the tone curve turns it into screen colour, 21a677e2).
     */
    @Test
    void theSkyIsDithered() throws IOException {
        String frag = resource("/shaders/sky/sky.frag");
        assertTrue(frag.contains("vec3 dither = vec3(ditherTpdf(gl_FragCoord.xy));"), "the per-pixel dither");
        assertTrue(frag.contains("working += dither * (1.0 - paintedSky);"), "the physical sky is dithered");
        assertTrue(frag.contains("paintedSkyColor(direction, pixel, paintedDisc) + dither"), "the painted sky is dithered");
    }
}
