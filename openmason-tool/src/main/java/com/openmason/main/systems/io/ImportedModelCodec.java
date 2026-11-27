package com.openmason.main.systems.io;

import com.openmason.engine.entity.model.EntityModelImporter;
import com.openmason.engine.entity.model.EntityModelSource;
import com.openmason.engine.format.omo.CendaImport;
import com.openmason.engine.format.spi.FormatCodec;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * glTF 2.0 / Wavefront OBJ as an engine format (OpenMason #173): the native importer ({@link CendaImport}) turns
 * the file into an OMO, so imported models appear wherever the editor lists entity models (e.g. the Rigging pane's
 * Attachable Model dialog, through {@code EntityModelLoader}). Registered by the tool's core plugin with
 * {@code Formats.register} - games load OMO, so the engine itself ships no importer codec.
 */
public final class ImportedModelCodec implements FormatCodec<CendaImport.Result>, EntityModelImporter {

    /** Codec id. */
    public static final String ID = "openmason.import";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "glTF / OBJ Model";
    }

    @Override
    public List<String> extensions() {
        return CendaImport.EXTENSIONS;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Class<CendaImport.Result> modelType() {
        return CendaImport.Result.class;
    }

    @Override
    public CendaImport.Result read(Path file) throws IOException {
        return CendaImport.importFile(file, CendaImport.Options.defaults());
    }

    @Override
    public boolean sniff(byte[] head) {
        // glTF binary: "glTF" magic. JSON glTF and OBJ have no reliable signature (extension only).
        return head != null && head.length >= 4 && head[0] == 'g' && head[1] == 'l' && head[2] == 'T'
                && head[3] == 'F';
    }

    /**
     * Imports the bytes as a model-only source (its default variant is the imported model; clips are not
     * carried). When {@code sourceId} names an existing file, external references resolve in its folder.
     */
    @Override
    public EntityModelSource importEntity(String sourceId, InputStream in) throws IOException {
        byte[] bytes = in.readAllBytes();
        Path base = null;
        CendaImport.Format format = CendaImport.Format.AUTO;
        if (sourceId != null) {
            String lower = sourceId.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".obj")) format = CendaImport.Format.OBJ;
            else if (lower.endsWith(".gltf") || lower.endsWith(".glb")) format = CendaImport.Format.GLTF;
            try {
                Path p = Path.of(sourceId);
                if (Files.isRegularFile(p) && p.toAbsolutePath().getParent() != null) {
                    base = p.toAbsolutePath().getParent();
                }
            } catch (RuntimeException ignored) {
                // a resource id, not a path
            }
        }
        CendaImport.Result result = CendaImport.importBytes(bytes, format, base, CendaImport.Options.defaults());
        String id = "imported";
        if (sourceId != null && !sourceId.isBlank()) {
            int slash = Math.max(sourceId.lastIndexOf('/'), sourceId.lastIndexOf('\\'));
            id = sourceId.substring(slash + 1);
        }
        return EntityModelSource.modelOnly(id, result.omo());
    }
}
