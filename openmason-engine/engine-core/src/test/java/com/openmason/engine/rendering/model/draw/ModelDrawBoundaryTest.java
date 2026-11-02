package com.openmason.engine.rendering.model.draw;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #90: the engine model renderer stays game-agnostic and format-neutral. No
 * compiled class in {@code rendering.model.draw} may refer to a game, the Open
 * Mason tool, the modeling module, or the SBE <em>file format</em> types (it
 * consumes the runtime {@code entity.sbe} model, which survives SBE moving to a
 * plugin). Scans bytecode, so fully-qualified names and reflection strings count.
 */
class ModelDrawBoundaryTest {

    private static final List<String> FORBIDDEN = List.of(
            "com/stonebreak/", "com.stonebreak.",
            "com/openmason/main/", "com.openmason.main.",
            "com/openmason/engine/modeling/", "com.openmason.engine.modeling.",
            "com/openmason/engine/format/sbe/", "com.openmason.engine.format.sbe.");

    @Test
    void drawPackageReferencesNoGameToolModelingOrSbeFormatType() throws Exception {
        Path root = Path.of(ModelRenderer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path pkg = root.resolve("com/openmason/engine/rendering/model/draw");
        assertTrue(Files.isDirectory(pkg), "expected compiled classes at " + pkg);
        List<String> offenders;
        try (Stream<Path> files = Files.list(pkg)) {
            offenders = files.filter(p -> p.toString().endsWith(".class"))
                    .filter(ModelDrawBoundaryTest::crossesBoundary)
                    .map(p -> pkg.relativize(p).toString())
                    .sorted()
                    .toList();
        }
        assertTrue(offenders.isEmpty(), "model draw classes crossing the boundary: " + offenders);
        try (Stream<Path> files = Files.list(pkg)) {
            assertFalse(files.findAny().isEmpty());
        }
    }

    private static boolean crossesBoundary(Path classFile) {
        try {
            String text = new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
            return FORBIDDEN.stream().anyMatch(text::contains);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
