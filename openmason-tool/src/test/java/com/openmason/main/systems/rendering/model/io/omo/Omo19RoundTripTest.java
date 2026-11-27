package com.openmason.main.systems.rendering.model.io.omo;

import com.openmason.engine.format.omo.CendaOmo;
import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.format.omo.OMOReader;
import com.openmason.engine.format.omo.OmoDocument;
import com.openmason.main.systems.rendering.model.editable.BlockModel;
import com.openmason.main.systems.rendering.model.editable.CubeGeometry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * OMO 1.9 data (#168) through the Java tool path over the native codec (#167): set on the serializer, save, load
 * with the deserializer and the engine reader, equal. Plus version handling ("1.10" is newer than "1.9" and read,
 * a newer major is refused) and the base-colour-only material.
 */
class Omo19RoundTripTest {

    @TempDir
    Path dir;

    @BeforeAll
    static void needsNative() {
        assumeTrue(CendaOmo.isAvailable(), () -> "native model module: " + CendaOmo.unavailableReason());
    }

    private static final OMOFormat.MeshData MESH = new OMOFormat.MeshData(
            new float[]{0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0},
            new float[]{0, 0, 1, 0, 1, 1, 0, 1},
            new int[]{0, 1, 2, 0, 2, 3},
            new int[]{0, 0},
            "FLAT");

    private static final OMOFormat.PartEntry PART = new OMOFormat.PartEntry("p1", "Body",
            0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 0, 4, 0, 6, 0, 1, true, false);

    private static final OMOFormat.ShadingData SHADING = new OMOFormat.ShadingData(
            new int[]{0b101, 0, 0x80000000}, new int[]{0, 1, 2, 3}, 30f,
            new int[]{0, 2}, new short[]{100, -200, 32767, -32767});

    private static final List<OMOFormat.VertexGroupData> GROUPS = List.of(
            new OMOFormat.VertexGroupData("arm", List.of(
                    new OMOFormat.GroupPart("p1", new int[]{0, 1, 3}, new float[]{1f, 0.5f, 0.25f}))),
            new OMOFormat.VertexGroupData("empty", List.of()));

    private static final List<OMOFormat.MorphData> MORPHS = List.of(
            new OMOFormat.MorphData("smile", 0.25f, -1f, 2f, List.of(
                    new OMOFormat.MorphPart("p1", new int[]{1, 2}, new float[]{0.1f, 0, 0, 0, 0.2f, 0}, null))),
            new OMOFormat.MorphData("blink", 0f, 0f, 1f, List.of(
                    new OMOFormat.MorphPart("p1", new int[]{3}, new float[]{0, -0.5f, 0},
                            new float[]{0, 0, 1}))));

    private static final OMOFormat.MaterialEntry TEXTURED =
            new OMOFormat.MaterialEntry(1, "wood", "material_1.png", "OPAQUE", false, -1);
    private static final OMOFormat.MaterialEntry COLOURED =
            new OMOFormat.MaterialEntry(2, "paint", null, "CUTOUT", true, 0x11223344,
                    new float[]{0.5f, 0.25f, 1f, 0.75f});

    private Path save(boolean with19Data) throws IOException {
        Path texture = dir.resolve("dummy.omt");
        Files.write(texture, new byte[]{1, 2, 3, 4});
        BlockModel model = new BlockModel("Nine", new CubeGeometry(), texture);
        OMOSerializer s = new OMOSerializer();
        s.setPartEntries(List.of(PART));
        Map<Integer, byte[]> pngs = new LinkedHashMap<>();
        pngs.put(1, new byte[]{9, 8, 7});
        s.setFaceTextureData(new OMOFormat.FaceTextureData(
                List.of(new OMOFormat.FaceMappingEntry(0, with19Data ? 2 : 1, 0, 0, 1, 1, 90, false)),
                with19Data ? List.of(TEXTURED, COLOURED) : List.of(TEXTURED)), pngs);
        if (with19Data) {
            s.setShadingData(SHADING);
            s.setVertexGroups(GROUPS);
            s.setMorphs(MORPHS);
        }
        Path out = dir.resolve(with19Data ? "nine.omo" : "eight.omo");
        assertTrue(s.save(model, out.toString(), MESH), "save");
        return out;
    }

    private static String manifest(Path omo) throws IOException {
        try (OmoDocument doc = OmoDocument.read(Files.readAllBytes(omo))) {
            return doc.manifest();
        }
    }

    @Test
    void nineteenDataRoundTripsThroughTheToolAndTheEngineReader() throws IOException {
        Path omo = save(true);
        assertTrue(manifest(omo).contains("\"version\" : \"1.9\""), "1.9 data is written as 1.9");

        OMODeserializer d = new OMODeserializer();
        assertNotNull(d.load(omo.toString()));
        assertEquals("1.9", d.getLastLoadedVersion());
        assertEquals(SHADING, d.getLastLoadedShadingData());
        assertEquals(GROUPS, d.getLastLoadedVertexGroups());
        assertEquals(MORPHS, d.getLastLoadedMorphs());
        assertEquals(List.of(TEXTURED, COLOURED), d.getLastLoadedFaceTextureData().materials());
        assertEquals(MESH, d.getLastLoadedMeshData());

        OMOReader.ReadResult r = new OMOReader().read(Files.readAllBytes(omo));
        assertEquals(SHADING, r.shading());
        assertEquals(GROUPS, r.vertexGroups());
        assertEquals(MORPHS, r.morphs());
        assertEquals(2, r.materials().size());
        assertArrayEquals(new byte[]{9, 8, 7}, r.materials().get(0).texturePng());
        assertNull(r.materials().get(0).baseColor());
        assertNull(r.materials().get(1).texturePng(), "a base-colour material has no image");
        assertArrayEquals(new float[]{0.5f, 0.25f, 1f, 0.75f}, r.materials().get(1).baseColor());

        // Save again from what was loaded: the same archive.
        OMOSerializer s = new OMOSerializer();
        s.setPartEntries(d.getLastLoadedPartEntries());
        s.setFaceTextureData(d.getLastLoadedFaceTextureData(), Map.of(1, new byte[]{9, 8, 7}));
        s.setShadingData(d.getLastLoadedShadingData());
        s.setVertexGroups(d.getLastLoadedVertexGroups());
        s.setMorphs(d.getLastLoadedMorphs());
        Path again = dir.resolve("again.omo");
        assertTrue(s.save(new BlockModel("Nine", new CubeGeometry(), dir.resolve("dummy.omt")), again.toString(),
                d.getLastLoadedMeshData()));
        assertEquals(manifest(omo), manifest(again));
    }

    @Test
    void withoutNineteenDataTheFileStays18() throws IOException {
        Path omo = save(false);
        String manifest = manifest(omo);
        assertTrue(manifest.contains("\"version\" : \"1.8\""));
        OMODeserializer d = new OMODeserializer();
        assertNotNull(d.load(omo.toString()));
        assertNull(d.getLastLoadedShadingData());
        assertNull(d.getLastLoadedVertexGroups());
        assertNull(d.getLastLoadedMorphs());
        assertNull(d.getLastLoadedFaceTextureData().materials().get(0).baseColor());
    }

    @Test
    void versionsCompareAsIntegers() {
        assertTrue(CendaOmo.compareVersions("1.10", "1.9") > 0, "1.10 is newer than 1.9");
        assertTrue(CendaOmo.compareVersions("1.8", "1.10") < 0);
        assertEquals(0, CendaOmo.compareVersions("1.8", "1.8"));
        assertThrows(IllegalArgumentException.class, () -> CendaOmo.compareVersions("one", "1.8"));
    }

    private Path withVersion(String version) throws IOException {
        String manifest = """
                {"version":"%s","objectName":"V","modelType":"cube","textureFile":"texture.omt",
                 "geometry":{"width":16,"height":16,"depth":16,"position":{"x":0,"y":0,"z":0}}}
                """.formatted(version);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(manifest.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("texture.omt"));
            zip.write(new byte[]{1, 2, 3});
            zip.closeEntry();
        }
        Path out = dir.resolve("v" + version + ".omo");
        Files.write(out, bytes.toByteArray());
        return out;
    }

    @Test
    void aNewerMinorIsReadAndANewerMajorRefused() throws IOException {
        OMODeserializer d = new OMODeserializer();
        assertNotNull(d.load(withVersion("1.10").toString()), "1.10 is a 1.x file");
        assertEquals("1.10", d.getLastLoadedVersion());
        assertNull(d.load(withVersion("2.0").toString()), "a newer major is refused");
        assertNull(d.load(withVersion("0.9").toString()), "older than the minimum supported version");

        assertThrows(OmoDocument.VersionException.class,
                () -> new OMOReader().read(Files.readAllBytes(withVersion("2.0"))));
        assertEquals("1.10", new OMOReader().read(Files.readAllBytes(withVersion("1.10"))).document().version());
    }
}
