package com.openmason.engine.platform.boundary;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The platform layer stays game-agnostic (#96): no compiled class refers to the game, the Open Mason
 * tool, the voxel or modeling modules, and none carries Stonebreak content (titles, app ids, its
 * {@code Settings}). Scans bytecode, so reflection strings count and comments do not. The legacy
 * {@code stonebreak.<key>} property spellings are read through {@code EngineProperties}, which builds
 * them at runtime, so they never appear here as constants.
 */
class PlatformBoundaryTest {

    private static final List<String> FORBIDDEN = List.of(
            "com/stonebreak/", "com.stonebreak.",
            "com/openmason/main/", "com.openmason.main.",
            "com/openmason/engine/voxel/", "com.openmason.engine.voxel.",
            "com/openmason/engine/modeling/", "com.openmason.engine.modeling.");

    @Test
    void platformClassesNeverReferenceTheGameTheToolOrOtherModules() throws Exception {
        Path classes = Path.of(com.openmason.engine.platform.window.Window.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        assertTrue(Files.isDirectory(classes), "expected the module's compiled classes directory, got " + classes);
        List<String> offenders;
        try (Stream<Path> files = Files.walk(classes)) {
            offenders = files.filter(p -> p.toString().endsWith(".class"))
                    .filter(PlatformBoundaryTest::offends)
                    .map(p -> classes.relativize(p).toString())
                    .sorted()
                    .toList();
        }
        assertTrue(offenders.isEmpty(), "platform classes crossing a boundary or naming the game: " + offenders);
    }

    private static boolean offends(Path classFile) {
        try {
            // Constant-pool UTF-8 entries are ASCII for these names, so a Latin-1 view is exact.
            String bytes = new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
            return FORBIDDEN.stream().anyMatch(bytes::contains)
                    || bytes.toLowerCase(Locale.ROOT).contains("stonebreak");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
