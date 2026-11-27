package com.openmason.main.systems.animation;

import com.fasterxml.jackson.databind.JsonNode;
import com.openmason.engine.anim.AnimClipDocument;
import com.openmason.engine.anim.CendaAnimClip;
import com.openmason.engine.format.omo.CendaImport;
import com.openmason.main.systems.io.ModelImportService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Shape-key keys in the Animation tab (#171): on a model with shape keys (the importer's {@code morph_cube.gltf}:
 * Smile, Frown), {@code morph_key} operations (what the inspector sliders send) make a morph track on the layer, the
 * dope sheet sees it in the view, the preview samples the baked weights, and the clip round trips through OMANIM 1.3:
 * saved, read back as a plain clip (morph tracks), reopened for editing with the same weights.
 */
class AnimationShapeKeyTest {

    static final Path FIXTURE = Path.of("..", "openmason-engine", "cenda", "tests", "fixtures", "import", "morph_cube.gltf");

    @TempDir
    Path dir;

    @BeforeAll
    static void needsNative() {
        assumeTrue(CendaImport.isAvailable(), () -> "native importer: " + CendaImport.unavailableReason());
        assumeTrue(CendaAnimClip.isAvailable(), "native clip module unavailable");
        assumeTrue(Files.isRegularFile(FIXTURE), "import fixture missing at " + FIXTURE.toAbsolutePath());
    }

    @Test
    void shapeKeyKeysRoundTripThroughOmanim13() throws Exception {
        Path model = dir.resolve("Face.omo");
        Files.write(model, ModelImportService.convert(FIXTURE, CendaImport.Options.defaults()).omo());
        Path clipFile = dir.resolve("Blink.omanim");
        float[] expected;
        try (AnimationSession s = AnimationSession.create(model, "Blink", 30f, 1f)) {
            List<String> keys = s.shapeKeys().stream().map(t -> t.name()).toList();
            assertEquals(List.of("Smile", "Frown"), keys);
            float[] defaults = s.morphWeights(0f).clone();
            assertArrayEquals(new float[] {s.shapeKeys().get(0).defaultWeight(), s.shapeKeys().get(1).defaultWeight()},
                    defaults, "no track: the key's default");

            s.apply("[{\"op\":\"morph_key\",\"morph\":\"Smile\",\"time\":0,\"value\":0},"
                    + "{\"op\":\"morph_key\",\"morph\":\"Smile\",\"time\":1,\"value\":1}]", "key smile", null);
            s.apply("[{\"op\":\"morph_key\",\"morph\":\"morph:Frown\",\"time\":0.5,\"value\":0.8,\"interp\":\"STEP\"}]",
                    "key frown", null);

            JsonNode morphs = s.view().path("layers").path(0).path("morphs");
            assertEquals(2, morphs.size(), "the layer's morph tracks: the dope sheet rows");
            float[] mid = s.morphWeights(0.5f);
            assertEquals(0.5f, mid[0], 1e-5f);
            assertEquals(0.8f, mid[1], 1e-5f);

            // Undo is the authoring history (one step per apply).
            assertTrue(s.undo());
            assertEquals(1, s.view().path("layers").path(0).path("morphs").size());
            assertTrue(s.redo());

            byte[] bytes = s.bytesFor(clipFile);
            Files.write(clipFile, bytes);
            s.saved(clipFile);
            expected = s.morphWeights(0.25f).clone();
        }

        try (AnimClipDocument clip = AnimClipDocument.read(Files.readAllBytes(clipFile))) {
            assertEquals("1.3", clip.version(), "a clip with morph tracks is written as OMANIM 1.3");
            List<AnimClipDocument.Curve> tracks = clip.morphTracks();
            assertEquals(List.of("Smile", "Frown"), tracks.stream().map(AnimClipDocument.Curve::name).toList());
            assertEquals(2, tracks.get(0).keys().size());
            assertEquals(AnimClipDocument.Interp.STEP, tracks.get(1).keys().get(0).interp());
            assertEquals(0.25f, clip.sampleMorph(0, 0.25f), 1e-5f);
        }

        try (AnimationSession reopened = AnimationSession.open(clipFile, model, null)) {
            float[] w = reopened.morphWeights(0.25f);
            assertNotNull(w);
            assertArrayEquals(expected, w, 1e-6f);
        }
    }
}
