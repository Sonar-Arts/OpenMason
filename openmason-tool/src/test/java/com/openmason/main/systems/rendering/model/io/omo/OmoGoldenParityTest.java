package com.openmason.main.systems.rendering.model.io.omo;

import com.openmason.engine.format.omo.CendaOmo;
import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.main.systems.rendering.model.editable.BlockModel;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The editor's OMO load + save now runs through the native codec (OpenMason #167). For every golden in
 * {@code cenda/tests/golden/omo} (frozen from the former Java writer by {@link OmoGoldenFreezeTest}) this test does
 * exactly what the freeze did - {@link OMODeserializer#load} then {@link OMOSerializer} setters and save - and
 * requires every archive entry (name, order and bytes, manifest.json included) to equal the golden's.
 */
class OmoGoldenParityTest {

    private static final Path REPO = Path.of("").toAbsolutePath().getParent();   // module dir -> repo root
    private static final Path GOLDEN = REPO.resolve("openmason-engine/cenda/tests/golden/omo");

    @Test
    void javaLoadSaveThroughNativeReproducesEveryGolden() throws IOException {
        assumeTrue(CendaOmo.isAvailable(), () -> "native model module: " + CendaOmo.unavailableReason());
        List<Path> goldens;
        try (Stream<Path> s = Files.list(GOLDEN)) {
            goldens = s.filter(p -> p.toString().endsWith(".omo")).sorted().toList();
        }
        assertTrue(goldens.size() >= 400, "golden set missing: " + goldens.size() + " files in " + GOLDEN);

        Path tmp = Files.createTempDirectory("omo-parity");
        List<String> failed = new ArrayList<>();
        int passed = 0;
        for (Path golden : goldens) {
            String problem = resaveAndCompare(golden, tmp.resolve(golden.getFileName()));
            if (problem == null) {
                passed++;
            } else {
                failed.add(golden.getFileName() + ": " + problem);
            }
        }
        System.out.println("[omo-parity] " + passed + "/" + goldens.size() + " goldens reproduced");
        failed.stream().limit(20).forEach(m -> System.out.println("[omo-parity] FAILED " + m));
        assertTrue(failed.isEmpty(), failed.size() + " goldens differ, first: " + (failed.isEmpty() ? "" : failed.get(0)));
    }

    /** Null when the re-save matches the golden entry for entry, else what differs. */
    private static String resaveAndCompare(Path golden, Path out) throws IOException {
        OMODeserializer d = new OMODeserializer();
        BlockModel model = d.load(golden.toString());
        if (model == null) {
            return "load refused it";
        }
        // Exactly the freeze test's setter sequence.
        OMOSerializer s = new OMOSerializer();
        OMOFormat.FaceTextureData ftd = d.getLastLoadedFaceTextureData();
        if (ftd != null) {
            Map<String, byte[]> byFile = d.getLastLoadedMaterialTextures();
            Map<Integer, byte[]> byId = new LinkedHashMap<>();
            if (byFile != null) {
                for (OMOFormat.MaterialEntry m : ftd.materials()) {
                    byte[] png = m.textureFile() == null ? null : byFile.get(m.textureFile());
                    if (png != null) {
                        byId.put(m.materialId(), png);
                    }
                }
            }
            s.setFaceTextureData(ftd, byId);
        }
        if (d.getLastLoadedPartEntries() != null) s.setPartEntries(d.getLastLoadedPartEntries());
        if (d.getLastLoadedModelTransform() != null) s.setModelTransform(d.getLastLoadedModelTransform());
        if (d.getLastLoadedBoneEntries() != null) s.setBoneEntries(d.getLastLoadedBoneEntries());
        if (d.getLastLoadedAttachmentPointEntries() != null) {
            s.setAttachmentPointEntries(d.getLastLoadedAttachmentPointEntries());
        }
        if (d.getLastLoadedSkinData() != null) s.setSkinData(d.getLastLoadedSkinData());
        OMOFormat.MeshData mesh = d.getLastLoadedMeshData();
        boolean ok = mesh != null ? s.save(model, out.toString(), mesh) : s.save(model, out.toString());
        if (!ok) {
            return "save failed";
        }
        Map<String, byte[]> want = entries(golden);
        Map<String, byte[]> have = entries(out);
        if (!List.copyOf(want.keySet()).equals(List.copyOf(have.keySet()))) {
            return "entries " + have.keySet() + " != golden " + want.keySet();
        }
        for (Map.Entry<String, byte[]> e : want.entrySet()) {
            if (!Arrays.equals(e.getValue(), have.get(e.getKey()))) {
                return "entry " + e.getKey() + " differs (" + have.get(e.getKey()).length + " vs golden "
                        + e.getValue().length + " bytes)";
            }
        }
        return null;
    }

    private static Map<String, byte[]> entries(Path zip) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                out.put(e.getName(), in.readAllBytes());
            }
        }
        return out;
    }
}
