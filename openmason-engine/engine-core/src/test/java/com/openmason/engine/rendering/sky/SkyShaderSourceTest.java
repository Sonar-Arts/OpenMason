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

    @Test
    void everyUniformTheRendererSetsIsDeclared() throws IOException {
        String shaders = resource("/shaders/sky/sky.vert") + resource("/shaders/sky/sky.frag");
        String renderer = Files.readString(
                Path.of("src/main/java/com/openmason/engine/rendering/sky/SkyRenderer.java"));
        Matcher m = Pattern.compile("createUniform\\(\"(\\w+)\"\\)").matcher(renderer);
        int count = 0;
        while (m.find()) {
            String name = m.group(1);
            assertTrue(Pattern.compile("uniform\\s+\\w+\\s+" + name + "\\s*;").matcher(shaders).find(),
                    "sky shaders do not declare uniform " + name);
            count++;
        }
        assertTrue(count >= 20, "found only " + count + " uniforms; did the renderer change shape?");
    }

    @Test
    void theSkyIsDitheredLast() throws IOException {
        String frag = resource("/shaders/sky/sky.frag");
        assertTrue(frag.contains("FragColor = vec4(finalColor + ditherTpdf(gl_FragCoord.xy), 1.0);"), frag);
    }
}
