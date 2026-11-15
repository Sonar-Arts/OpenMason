package com.openmason.engine.format.spi;

import com.openmason.engine.entity.model.EntityModelImporter;
import com.openmason.engine.entity.model.EntityModelLoader;
import com.openmason.engine.entity.model.EntityModelSource;
import com.openmason.engine.format.omt.TextureBytes;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The format plugin points (#88): the engine ships no game format; a game registers its codecs,
 * texture containers and entity model importers, and the engine finds them by extension.
 */
class FormatsTest {

    /** A game container ({@code .tcard}): a codec, a texture container and an entity model importer. */
    static final class Card implements FormatCodec<String>, TextureDecoder, EntityModelImporter {
        @Override public String id() { return "test.card"; }
        @Override public String displayName() { return "Test Card"; }
        @Override public List<String> extensions() { return List.of(".tcard"); }
        @Override public Class<String> modelType() { return String.class; }
        @Override public String read(Path file) throws IOException { return java.nio.file.Files.readString(file); }

        @Override
        public byte[] unwrapOmt(byte[] data) throws IOException {
            return entry(data, "texture.omt");
        }

        @Override
        public EntityModelSource importEntity(String sourceId, InputStream in) throws IOException {
            byte[] model = entry(in.readAllBytes(), "model.omo");
            if (model == null) {
                throw new IOException("no model in " + sourceId);
            }
            return EntityModelSource.modelOnly("test:" + sourceId, model);
        }
    }

    static {
        Card card = new Card();
        Formats.register((FormatCodec<?>) card);
        Formats.register((TextureDecoder) card);
    }

    @Test
    void theEngineShipsNoGameFormat() {
        for (FormatCodec<?> c : Formats.codecs()) {
            assertTrue(c.id().startsWith("test."), "a format the engine discovered by itself: " + c.id());
        }
        assertTrue(EntityModelLoader.canLoad("hat.omo"), "bare OMO is the engine's own");
    }

    @Test
    void registeredCodecsAreFoundByExtensionCaseInsensitively() {
        assertEquals("test.card", Formats.forFileName("Ace.TCARD").orElseThrow().id());
        assertEquals("test.card", Formats.forPath(Path.of("deck", "ace.tcard")).orElseThrow().id());
        assertTrue(Formats.forFileName("model.omo").isEmpty());
        assertTrue(Formats.forFileName(null).isEmpty());
        assertEquals("test.card", Formats.codec("test.card").orElseThrow().id());
        assertTrue(Formats.textureContainerExtensions().contains(".tcard"));
        assertEquals("test.card", Formats.textureDecoderFor("x.tcard").orElseThrow().id());
        assertTrue(EntityModelLoader.canLoad("ace.tcard"));
        assertTrue(EntityModelLoader.loadableExtensions().containsAll(List.of(".tcard", ".omo")));
        assertFalse(EntityModelLoader.canLoad("ace.unknown"));
    }

    @Test
    void zipContainersAreNotSniffed() throws IOException {
        assertTrue(Formats.sniff(zip("manifest.json", "{}".getBytes(StandardCharsets.UTF_8))).isEmpty());
    }

    @Test
    void aRegisteredContainerUnwrapsItsOmtAndForeignBytesDoNot() throws IOException {
        byte[] omt = "not-really-an-omt".getBytes(StandardCharsets.UTF_8);
        byte[] card = zip("manifest.json", "{}".getBytes(StandardCharsets.UTF_8), "texture.omt", omt);
        assertArrayEquals(omt, Formats.unwrapTexture(card));

        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
        assertNull(Formats.unwrapTexture(png));
        assertNull(Formats.unwrapTexture(zip("manifest.json", "{}".getBytes(StandardCharsets.UTF_8))));
        assertNull(Formats.unwrapTexture(new byte[0]));
        assertNull(Formats.unwrapTexture(null));
        // The embedded bytes are not an OMT: the probe says "not a texture" instead of throwing.
        assertNull(TextureBytes.archive(card));
    }

    @Test
    void anEntityContainerWithoutAnImporterIsRefusedByName() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> EntityModelLoader.load("/x/ghost.unknown", p -> new java.io.ByteArrayInputStream(new byte[0])));
        assertTrue(String.valueOf(e.getCause()).contains("No entity model importer"), e.toString());
    }

    @Test
    void aContainerIsImportedThroughItsImporter() throws IOException {
        byte[] model = {1, 2, 3};
        EntityModelSource source = EntityModelLoader.importSource("ace.tcard",
                new java.io.ByteArrayInputStream(zip("model.omo", model)));
        assertEquals("test:ace.tcard", source.objectId());
        assertArrayEquals(model, source.baseModel());
    }

    private static byte[] entry(byte[] zip, String name) throws IOException {
        try (ZipInputStream in = new ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                if (e.getName().equals(name)) {
                    return in.readAllBytes();
                }
            }
        }
        return null;
    }

    private static byte[] zip(Object... nameThenBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            for (int i = 0; i < nameThenBytes.length; i += 2) {
                z.putNextEntry(new ZipEntry((String) nameThenBytes[i]));
                z.write((byte[]) nameThenBytes[i + 1]);
                z.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
