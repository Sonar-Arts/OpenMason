package com.openmason.main.systems.io;

import com.openmason.engine.entity.model.EntityModelLoader;
import com.openmason.engine.entity.model.EntityModelSource;
import com.openmason.engine.format.omo.CendaImport;
import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.format.omo.OmoDocument;
import com.openmason.engine.format.spi.Formats;
import com.openmason.engine.modeling.EditableModel;
import com.openmason.engine.modeling.uv.FaceTextureManager;
import com.openmason.engine.modeling.uv.FaceTextureMapping;
import com.openmason.engine.modeling.uv.MaterialDefinition;
import com.openmason.main.systems.rendering.model.editable.BlockModel;
import com.openmason.main.systems.rendering.model.io.omo.OMODeserializer;
import com.openmason.main.systems.rendering.model.io.omo.OMOSerializer;
import com.openmason.main.systems.rendering.model.io.omo.OmoExportAssembler;
import com.openmason.main.systems.rendering.model.io.omo.OmoPartRestorer;
import com.openmason.main.systems.rendering.model.io.omo.OmoVertexDataCarry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * File > Import Model / {@code model_import} (#173) over the native importer's fixtures: convert, open the result
 * the way the editor's load path does (parts, face textures with authored UVs, base colours, shading, carried
 * vertex groups / morphs), save it the way the editor's save path does, reload: the editor shows the same model.
 * GL-free (material textures are not uploaded).
 */
class ModelImportRoundTripTest {

    static final Path FIXTURES = Path.of("..", "openmason-engine", "cenda", "tests", "fixtures", "import");
    /** The importer's own conversion fixtures (skinned, animated). */
    static final Path OMO_FIXTURES = FIXTURES.resolveSibling("import_omo");

    @TempDir
    Path dir;

    @BeforeAll
    static void needsNative() {
        assumeTrue(CendaImport.isAvailable(), () -> "native importer: " + CendaImport.unavailableReason());
        assumeTrue(Files.isDirectory(FIXTURES), "import fixtures not found at " + FIXTURES.toAbsolutePath());
    }

    /** What the editor holds after a load. */
    record Loaded(EditableModel model, OMODeserializer reader, BlockModel block, OmoVertexDataCarry carry,
                  Map<Integer, byte[]> pngs) {
    }

    /** The editor's load path (ModelOperationService.loadOMOModelFromFile), minus the GL texture uploads. */
    static Loaded open(Path omo) {
        OMODeserializer reader = new OMODeserializer();
        BlockModel block = reader.load(omo.toString());
        assertNotNull(block, "the deserializer reads " + omo.getFileName());
        EditableModel model = new EditableModel();
        OMOFormat.MeshData mesh = reader.getLastLoadedMeshData();
        List<OMOFormat.PartEntry> entries = reader.getLastLoadedPartEntries();
        Map<Integer, Integer> faceIds = null;
        if (entries != null && !entries.isEmpty()) {
            faceIds = OmoPartRestorer.restore(model.getPartManager(), mesh, entries,
                    reader.getLastLoadedBoneEntries()).faceIds();
        } else {
            model.loadMeshDataAsPart(mesh, block.getName());
        }
        Map<Integer, byte[]> pngs = new HashMap<>();
        OMOFormat.FaceTextureData ftd = reader.getLastLoadedFaceTextureData();
        if (ftd != null) {
            FaceTextureManager ftm = model.getFaceTextureManager();
            for (OMOFormat.MaterialEntry m : ftd.materials()) {
                ftm.registerMaterial(new MaterialDefinition(m.materialId(),
                        m.name() == null || m.name().isBlank() ? "Material" : m.name(), 0,
                        MaterialDefinition.RenderLayer.valueOf(m.renderLayer() == null ? "OPAQUE" : m.renderLayer()),
                        new MaterialDefinition.MaterialProperties(m.emissive(), m.tintColor(), m.baseColor())));
                if (m.textureFile() != null && reader.getLastLoadedMaterialTextures() != null) {
                    byte[] png = reader.getLastLoadedMaterialTextures().get(m.textureFile());
                    if (png != null) pngs.put(m.materialId(), png);
                }
            }
            for (OMOFormat.FaceMappingEntry e : ftd.mappings()) {
                Integer face = faceIds == null ? Integer.valueOf(e.faceId()) : faceIds.get(e.faceId());
                if (face == null) continue;
                ftm.setFaceMapping(new FaceTextureMapping(face, e.materialId(),
                        new FaceTextureMapping.UVRegion(e.u0(), e.v0(), e.u1(), e.v1()),
                        FaceTextureMapping.UVRotation.fromDegrees(e.uvRotationDegrees()), e.autoResize()));
            }
            model.adoptAuthoredUVs();
            model.refreshUVs();
        }
        String root = model.getPartManager().getAllParts().isEmpty() ? null
                : model.getPartManager().getAllParts().get(0).id();
        model.getShading().load(mesh, entries, reader.getLastLoadedShadingData(), root);
        return new Loaded(model, reader, block,
                OmoVertexDataCarry.load(reader.getLastLoadedVertexGroups(), reader.getLastLoadedMorphs()), pngs);
    }

    /** The editor's save path (ModelOperationService.saveModelToFileInner) for these sections. */
    static void save(Loaded l, Path target) {
        EditableModel model = l.model();
        OMOFormat.MeshData mesh = model.toMeshData();
        List<OMOFormat.PartEntry> entries = OmoExportAssembler.extractPartEntries(model.getPartManager(), mesh, true);
        OMOSerializer writer = new OMOSerializer();
        writer.setMeshData(mesh);
        OMOFormat.FaceTextureData ftd = OmoExportAssembler.extractFaceTextureData(model.getFaceTextureManager());
        if (ftd != null) writer.setFaceTextureData(ftd, l.pngs());
        writer.setPartEntries(entries);
        writer.setBoneEntries(l.reader().getLastLoadedBoneEntries());
        writer.setShadingData(model.getShading().toSavedLayout(mesh, entries));
        writer.setVertexGroups(l.carry().groupsInSavedLayout(model.getPartManager(), entries));
        writer.setMorphs(l.carry().morphsInSavedLayout(model.getPartManager(), entries));
        assertTrue(writer.save(l.block(), target.toString()), "the editor saves " + target.getFileName());
    }

    @Test
    void skinnedGltfRoundTrips() throws IOException {
        roundTrip("skin_strip.gltf");
    }

    @Test
    void morphGltfRoundTrips() throws IOException {
        roundTrip("morph_cube.gltf");
    }

    @Test
    void texturedGltfRoundTrips() throws IOException {
        roundTrip("textured_png.gltf");
    }

    @Test
    void objRoundTrips() throws IOException {
        roundTrip("model.obj");
    }

    @Test
    void animatedGltfRoundTrips() throws IOException {
        roundTrip("animation.gltf");
    }


    private void roundTrip(String fixture) throws IOException {
        roundTrip(FIXTURES.resolve(fixture));
    }

    private void roundTrip(Path file) throws IOException {
        String fixture = file.getFileName().toString();
        CendaImport.Result result = ModelImportService.convert(file, CendaImport.Options.defaults());
        assertTrue(result.counts().faces() > 0, fixture + " imports faces");
        Path imported = dir.resolve("imported.omo");
        Files.write(imported, result.omo());

        Loaded first = open(imported);
        save(first, dir.resolve("saved.omo"));
        Loaded second = open(dir.resolve("saved.omo"));

        assertArrayEquals(first.model().getTexCoords(), second.model().getTexCoords(), 1e-6f,
                fixture + ": authored UVs survive open -> save -> open");
        assertArrayEquals(first.model().getAllMeshVertexPositions(), second.model().getAllMeshVertexPositions(),
                1e-4f);
        assertArrayEquals(first.model().getCornerNormals(), second.model().getCornerNormals(),
                fixture + ": shading survives open -> save -> open");
        assertEquals(baseColors(first.model()), baseColors(second.model()), fixture + ": base colours");
        assertEquals(result.counts().morphs(), second.carry().morphs().size(), fixture + ": morphs");
        assertEquals(result.counts().vertexGroups(), second.carry().groups().size(), fixture + ": vertex groups");

        // The editor shows what the importer authored: every drawn corner has the UV the file gives the same
        // corner (same face, same position); the editor may emit a face's corners in another order.
        try (OmoDocument doc = OmoDocument.read(result.omo())) {
            assertCornersMatch(doc.mesh(), first.model().toMeshData(), fixture);
        }
    }

    private static void assertCornersMatch(OMOFormat.MeshData authored, OMOFormat.MeshData shown, String fixture) {
        Map<String, float[]> uvOf = new HashMap<>();
        cornerUvs(authored, (key, uv) -> uvOf.putIfAbsent(key, uv));
        int[] checked = {0};
        cornerUvs(shown, (key, uv) -> {
            float[] expected = uvOf.get(key);
            assertNotNull(expected, fixture + ": a drawn corner the file does not have: " + key);
            assertArrayEquals(expected, uv, 1e-5f, fixture + ": imported UVs are drawn as authored at " + key);
            checked[0]++;
        });
        assertTrue(checked[0] > 0);
    }

    /** (face id, rounded position) -> UV of every corner. */
    private static void cornerUvs(OMOFormat.MeshData mesh, java.util.function.BiConsumer<String, float[]> out) {
        int[] idx = mesh.indices();
        int[] faces = mesh.triangleToFaceId();
        float[] v = mesh.vertices();
        float[] uv = mesh.texCoords();
        for (int t = 0; t < idx.length / 3; t++) {
            for (int c = 0; c < 3; c++) {
                int i = idx[t * 3 + c];
                String key = faces[t] + "@" + Math.round(v[i * 3] * 1e4f) + "," + Math.round(v[i * 3 + 1] * 1e4f)
                        + "," + Math.round(v[i * 3 + 2] * 1e4f);
                out.accept(key, new float[] {uv[i * 2], uv[i * 2 + 1]});
            }
        }
    }

    private static Map<Integer, List<Float>> baseColors(EditableModel model) {
        Map<Integer, List<Float>> out = new HashMap<>();
        for (MaterialDefinition m : model.getFaceTextureManager().getAllMaterials()) {
            float[] c = m.properties().baseColor();
            if (c != null) out.put(m.materialId(), List.of(c[0], c[1], c[2], c[3]));
        }
        return out;
    }

    @Test
    void smoothImportsShadeLikeTheFile() throws IOException {
        CendaImport.Result result = ModelImportService.convert(FIXTURES.resolve("textured_png.gltf"),
                CendaImport.Options.defaults());
        Path imported = dir.resolve("m.omo");
        Files.write(imported, result.omo());
        try (OmoDocument doc = OmoDocument.read(result.omo())) {
            assertNotNull(doc.shading(), "glTF NORMALs become smooth faces + custom normals");
        }
        Loaded l = open(imported);
        assertTrue(l.model().getShading().hasShading());
        assertNotNull(l.model().getCornerNormals());
    }

    @Test
    void theTexturedImportKeepsItsBaseColourOnASave() throws IOException {
        CendaImport.Result result = ModelImportService.convert(FIXTURES.resolve("textured_png.gltf"),
                CendaImport.Options.defaults());
        Path imported = dir.resolve("t.omo");
        Files.write(imported, result.omo());
        Loaded first = open(imported);
        assertTrue(!baseColors(first.model()).isEmpty(), "the importer writes a base colour for every material");
        save(first, dir.resolve("t2.omo"));
        try (OmoDocument doc = OmoDocument.read(Files.readAllBytes(dir.resolve("t2.omo")))) {
            assertTrue(doc.materials().stream().allMatch(m -> m.baseColor() != null), "saved with base colours");
        }
    }

    @Test
    void clipsAreSavedBesideTheProjectsOtherClipsWithoutOverwriting() throws IOException {
        CendaImport.Result result = ModelImportService.convert(OMO_FIXTURES.resolve("rig.gltf"),
                CendaImport.Options.defaults());
        assertEquals(2, result.clips().size(), "rig.gltf converts its two animations");
        Path folder = ModelImportService.animationsFolder(dir.toString());
        List<Path> first = ModelImportService.saveClips(folder, "Strip", result.clips());
        List<Path> second = ModelImportService.saveClips(folder, "Strip", result.clips());
        assertEquals(result.clips().size(), first.size());
        assertTrue(first.get(0).getFileName().toString().startsWith("Strip - "));
        assertTrue(second.get(0).getFileName().toString().contains("(2)"), "never overwrites");
        assertArrayEquals(result.clips().get(0).omanim(), Files.readAllBytes(first.get(0)));
    }

    @Test
    void anUnsupportedFileIsRefused() {
        assertThrows(IOException.class, () -> ModelImportService.convert(dir.resolve("x.fbx"),
                CendaImport.Options.defaults()));
        assertThrows(IOException.class, () -> ModelImportService.convert(FIXTURES.resolve("bad_json.gltf"),
                CendaImport.Options.defaults()));
    }

    @Test
    void importedModelsAreAttachableModels() throws IOException {
        Formats.register(new ImportedModelCodec());
        assertTrue(EntityModelLoader.canLoad("hat.glb"));
        assertTrue(EntityModelLoader.loadableExtensions().containsAll(List.of(".gltf", ".obj")));
        Path obj = FIXTURES.resolve("model.obj");
        EntityModelSource source = EntityModelLoader.importSource(obj);
        assertTrue(source.baseModel().length > 0);
        try (InputStream in = Files.newInputStream(obj)) {
            assertTrue(new ImportedModelCodec().importEntity(obj.toString(), in).baseModel().length > 0);
        }
    }
}
