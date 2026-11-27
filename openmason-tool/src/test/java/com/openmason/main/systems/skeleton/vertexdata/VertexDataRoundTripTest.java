package com.openmason.main.systems.skeleton.vertexdata;

import com.openmason.engine.format.omo.CendaImport;
import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.modeling.EditableModel;
import com.openmason.main.systems.io.ModelImportService;
import com.openmason.main.systems.rendering.model.editable.BlockModel;
import com.openmason.main.systems.rendering.model.io.omo.OMODeserializer;
import com.openmason.main.systems.rendering.model.io.omo.OMOSerializer;
import com.openmason.main.systems.rendering.model.io.omo.OmoExportAssembler;
import com.openmason.main.systems.rendering.model.io.omo.OmoPartRestorer;
import com.openmason.main.systems.rendering.model.io.omo.OmoVertexDataCarry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Shape keys and vertex groups survive the editor's .omo path: an imported model with shape keys (the importer's
 * {@code morph_cube.gltf}: Smile, Frown) opens into the stores the way {@code ModelOperationService} loads it, is
 * edited (a new key, a key's range and rows, a renamed key, groups with weights), saved the way the editor saves
 * ({@link VertexDataAuthoring#carry} -> the saved layout), reopened: the stores hold exactly the edited values, and a
 * second save / open changes nothing. (The importer's own file is first saved once by the editor: its vertex layout
 * differs, so indices there are not comparable.)
 */
class VertexDataRoundTripTest {

    static final Path FIXTURE = Path.of("..", "openmason-engine", "cenda", "tests", "fixtures", "import", "morph_cube.gltf");

    @TempDir
    Path dir;

    @BeforeAll
    static void needsNative() {
        assumeTrue(CendaImport.isAvailable(), () -> "native importer: " + CendaImport.unavailableReason());
        assumeTrue(Files.isRegularFile(FIXTURE), "import fixture missing at " + FIXTURE.toAbsolutePath());
    }

    record Opened(EditableModel model, BlockModel block, VertexDataAuthoring data) {}

    /** ModelOperationService's load path for the parts and the vertex data. */
    static Opened open(Path omo) {
        OMODeserializer reader = new OMODeserializer();
        BlockModel block = reader.load(omo.toString());
        assertNotNull(block);
        EditableModel model = new EditableModel();
        List<OMOFormat.PartEntry> entries = reader.getLastLoadedPartEntries();
        if (entries != null && !entries.isEmpty()) {
            OmoPartRestorer.restore(model.getPartManager(), reader.getLastLoadedMeshData(), entries,
                    reader.getLastLoadedBoneEntries());
        } else {
            model.loadMeshDataAsPart(reader.getLastLoadedMeshData(), block.getName());
        }
        VertexDataAuthoring data = new VertexDataAuthoring(model.getPartManager());
        data.load(reader.getLastLoadedVertexGroups(), reader.getLastLoadedMorphs());
        return new Opened(model, block, data);
    }

    /** ModelOperationService's save path for these sections. */
    static void save(Opened o, Path target) {
        OMOFormat.MeshData mesh = o.model().toMeshData();
        OmoVertexDataCarry carry = o.data().carry();
        List<OMOFormat.PartEntry> entries = OmoExportAssembler.extractPartEntries(o.model().getPartManager(), mesh, true);
        OMOSerializer writer = new OMOSerializer();
        writer.setMeshData(mesh);
        writer.setPartEntries(entries);
        writer.setVertexGroups(carry.groupsInSavedLayout(o.model().getPartManager(), entries));
        writer.setMorphs(carry.morphsInSavedLayout(o.model().getPartManager(), entries));
        assertTrue(writer.save(o.block(), target.toString()));
    }

    /** Every vertex at vertex v's position (sorted). */
    private static int[] coincident(float[] pos, int v) {
        return java.util.stream.IntStream.range(0, pos.length / 3)
                .filter(i -> pos[i * 3] == pos[v * 3] && pos[i * 3 + 1] == pos[v * 3 + 1] && pos[i * 3 + 2] == pos[v * 3 + 2])
                .toArray();
    }

    @Test
    void editedShapeKeysAndGroupsRoundTrip() throws Exception {
        CendaImport.Result result = ModelImportService.convert(FIXTURE, CendaImport.Options.defaults());
        Path imported = dir.resolve("imported.omo");
        Files.write(imported, result.omo());

        // The importer's vertex layout is not the editor's: one editor save first (rows follow the written vertices,
        // so a vertex the editor writes twice carries its row twice), then the editor's own file is the "load".
        Path normalized = dir.resolve("normalized.omo");
        Opened imported0 = open(imported);
        int importedSmile = ShapeKeyStore.rowCount(imported0.data().shapeKeys().get("Smile"));
        save(imported0, normalized);
        Opened first = open(normalized);
        VertexDataAuthoring d = first.data();
        assertTrue(ShapeKeyStore.rowCount(d.shapeKeys().get("Smile")) >= importedSmile, "no row lost on the way in");
        assertEquals(List.of("Smile", "Frown"), d.shapeKeys().morphs().stream().map(OMOFormat.MorphData::name).toList());
        String part = d.partIds().get(0);
        int n = d.vertexCount(part);
        assertTrue(n >= 8);

        d.edit("Range", () -> d.shapeKeys().setParams("Frown", 0.5f, -1f, 2f));
        d.renameShapeKey("Smile", "Grin");
        // Rows go on every split vertex of a corner (what the editor's selection does: a picked vertex stands for
        // all its coincident soup vertices); the written mesh welds them.
        float[] pos = first.model().getPartManager().getPartGeometry(part).vertices();
        int[] cornerA = coincident(pos, 0), cornerB = coincident(pos, n - 1);
        int[] blinkV = java.util.stream.IntStream.concat(java.util.Arrays.stream(cornerA), java.util.Arrays.stream(cornerB))
                .sorted().toArray();
        float[] blinkD = new float[blinkV.length * 3];
        for (int k = 0; k < blinkV.length; k++) {
            boolean a = java.util.Arrays.binarySearch(cornerA, blinkV[k]) >= 0;
            blinkD[k * 3] = a ? 0f : 0.5f;
            blinkD[k * 3 + 1] = a ? -0.25f : 0f;
        }
        d.edit("Rows", () -> d.shapeKeys().add("Blink", 0f, 0f, 1f,
                List.of(new OMOFormat.MorphPart(part, blinkV, blinkD, null)), false));
        int[] top = coincident(pos, 1);
        d.edit("Groups", () -> {
            d.groups().add("Top");
            d.groups().assign("Top", part, top, 0.75f, null);
            d.groups().add("Empty");
        });
        List<OMOFormat.MorphData> keys = d.shapeKeys().morphs();
        List<OMOFormat.VertexGroupData> groups = d.groups().groups();

        Path saved = dir.resolve("saved.omo");
        save(first, saved);
        Opened second = open(saved);
        assertEquals(keys, second.data().shapeKeys().morphs(), "shape keys: load -> edit -> save -> load");
        assertEquals(groups, second.data().groups().groups(), "vertex groups (an empty one too): load -> edit -> save -> load");

        Path again = dir.resolve("again.omo");
        save(second, again);
        Opened third = open(again);
        assertEquals(second.data().shapeKeys().morphs(), third.data().shapeKeys().morphs());
        assertEquals(second.data().groups().groups(), third.data().groups().groups());
    }
}
