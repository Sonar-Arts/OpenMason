package com.openmason.main.systems.rendering.model.io.omo;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.main.systems.rendering.model.editable.BlockModel;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Freezes the OMO golden set for the native codec (OpenMason #167): every fixture OMO (and every model.omo
 * inside a fixture SBE/SBO) is loaded with {@link OMODeserializer} and saved again with {@link OMOSerializer}
 * - the Java editor's own load + save - into {@code cenda/tests/golden/omo/}. The native ctest {@code omo}
 * then requires read + write of each golden to reproduce its entries byte for byte.
 *
 * <p>Opt-in ({@code -Dopenmason.omo.golden.freeze=true}): re-freeze only on purpose, and only with the Java
 * writer as it was before the codec moved native.
 */
class OmoGoldenFreezeTest {

    private static final Path REPO = Path.of("").toAbsolutePath().getParent();   // module dir -> repo root
    private static final Path OUT = REPO.resolve("openmason-engine/cenda/tests/golden/omo");

    @Test
    void freezeTheGoldenSet() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean("openmason.omo.golden.freeze"),
                "opt-in: -Dopenmason.omo.golden.freeze=true");
        Files.createDirectories(OUT);
        List<Path> roots = new ArrayList<>();
        roots.add(REPO.resolve("test-fixtures/stonebreak/resources"));
        String extra = System.getProperty("openmason.omo.golden.extra");   // e.g. the Stonebreak resources dir
        if (extra != null && !extra.isBlank()) {
            for (String p : extra.split(java.io.File.pathSeparator)) {
                roots.add(Path.of(p));
            }
        }
        Path tmp = Files.createTempDirectory("omo-golden");
        int written = 0;
        List<String> failed = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            List<Path> files;
            try (Stream<Path> s = Files.walk(root)) {
                files = s.filter(Files::isRegularFile).sorted().toList();
            }
            for (Path f : files) {
                String n = f.getFileName().toString().toLowerCase();
                String base = root.relativize(f).toString().replace('/', '_').replace('\\', '_');
                if (n.endsWith(".omo")) {
                    written += resave(Files.readAllBytes(f), base, tmp, failed);
                } else if (n.endsWith(".sbe") || n.endsWith(".sbo")) {
                    try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(f))) {
                        ZipEntry e;
                        while ((e = zin.getNextEntry()) != null) {
                            if (!e.isDirectory() && e.getName().endsWith("model.omo")) {
                                String name = base + "__" + e.getName().replace('/', '_');
                                written += resave(zin.readAllBytes(), name, tmp, failed);
                            }
                        }
                    }
                }
            }
        }
        System.out.println("[omo-golden] wrote " + written + " files to " + OUT + ", failed " + failed.size());
        failed.forEach(m -> System.out.println("[omo-golden] FAILED " + m));
        assertTrue(written > 0, "no fixtures found");
    }

    private static int resave(byte[] omo, String name, Path tmp, List<String> failed) throws IOException {
        Path in = tmp.resolve("in_" + name.replaceAll("[^A-Za-z0-9._-]", "_"));
        if (!in.toString().endsWith(".omo")) {
            in = Path.of(in + ".omo");
        }
        Files.write(in, omo);
        OMODeserializer d = new OMODeserializer();
        BlockModel model = d.load(in.toString());
        if (model == null) {
            failed.add(name + " (Java load refused it)");
            return 0;
        }
        OMOSerializer s = new OMOSerializer();
        OMOFormat.FaceTextureData ftd = d.getLastLoadedFaceTextureData();
        if (ftd != null) {
            // the deserializer keys material PNGs by ZIP entry name, the serializer by material id
            Map<String, byte[]> byFile = d.getLastLoadedMaterialTextures();
            Map<Integer, byte[]> byId = new java.util.LinkedHashMap<>();
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
        String outName = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (!outName.endsWith(".omo")) outName += ".omo";
        Path out = OUT.resolve(outName);
        OMOFormat.MeshData mesh = d.getLastLoadedMeshData();
        boolean ok = mesh != null ? s.save(model, out.toString(), mesh) : s.save(model, out.toString());
        if (!ok) {
            failed.add(name + " (Java save failed)");
            return 0;
        }
        assertNotNull(Files.readAllBytes(out));
        return 1;
    }

    @SuppressWarnings("unused")
    private static InputStream stream(byte[] b) {
        return new ByteArrayInputStream(b);
    }
}
