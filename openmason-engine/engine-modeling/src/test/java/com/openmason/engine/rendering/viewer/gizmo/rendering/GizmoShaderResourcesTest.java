package com.openmason.engine.rendering.viewer.gizmo.rendering;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #91 moved the gizmo GLSL out of engine-core's {@code shaders/} into this module's {@code shaders/modeling/}
 * (a sub-dir, so no resource package is split between the two modules). {@link GizmoShaderLoader} loads them
 * through its own class, so they must resolve from this module; a missing file would only show up as an
 * editor viewport without a gizmo.
 */
class GizmoShaderResourcesTest {

    @Test
    void gizmoShadersResolveFromTheModelingModule() throws IOException {
        for (String path : new String[]{"/shaders/modeling/gizmo.vert", "/shaders/modeling/gizmo.frag"}) {
            try (InputStream in = GizmoShaderLoader.class.getResourceAsStream(path)) {
                assertNotNull(in, path + " is missing from openmason.engine.modeling");
                String source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(source.contains("#version") && source.contains("main"), path + " is not GLSL");
            }
        }
    }
}
