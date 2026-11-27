package com.openmason.main.systems.io;

import com.openmason.engine.format.omo.CendaImport;
import com.openmason.engine.format.omo.OmoDocument;
import com.openmason.main.systems.rendering.model.factory.BlankModelFactory;
import com.openmason.main.systems.services.ModelOperationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * File > Import Model and {@code model_import} (OpenMason #173): a glTF 2.0 ({@code .gltf} / {@code .glb}) or
 * Wavefront OBJ file becomes an UNSAVED model in the editor. Parsing and conversion are native
 * ({@link CendaImport}, {@code co_import_*}); this class only stages the result: the OMO opens through the normal
 * load path as an imported copy ({@link ModelOperationService#openExtractedAssetModel}; Save forces Save As), and
 * the glTF animations, when the caller asks, are written next to the project's other clips as
 * {@code <project>/Animations/<model> - <clip>.omanim} (never over an existing file: a numbered name instead).
 */
public final class ModelImportService {

    private static final Logger logger = LoggerFactory.getLogger(ModelImportService.class);

    /** The project sub-folder imported clips go to. */
    public static final String ANIMATIONS_FOLDER = "Animations";

    private ModelImportService() {
    }

    /**
     * What an import did.
     *
     * @param opened     the model opened in the editor
     * @param message    one line for the status bar / the caller
     * @param counts     what the importer produced (null when it failed)
     * @param warnings   the importer's warnings
     * @param clipNames  the converted animation clips
     * @param savedClips the clip files written (empty when the clips were not saved)
     */
    public record Outcome(boolean opened, String message, CendaImport.Counts counts, List<String> warnings,
                          List<String> clipNames, List<Path> savedClips) {
        public Outcome {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            clipNames = clipNames == null ? List.of() : List.copyOf(clipNames);
            savedClips = savedClips == null ? List.of() : List.copyOf(savedClips);
        }

        static Outcome failed(String message) {
            return new Outcome(false, message, null, List.of(), List.of(), List.of());
        }
    }

    /** Runs the native importer (any thread). The OMO it returns always carries a {@code texture.omt}. */
    public static CendaImport.Result convert(Path file, CendaImport.Options options) throws IOException {
        if (!CendaImport.canImport(file.getFileName().toString())) {
            throw new IOException("not an importable model (expected .gltf, .glb or .obj): " + file.getFileName());
        }
        CendaImport.Result result = CendaImport.importFile(file, options);
        return new CendaImport.Result(withTexture(result.omo()), result.clips(), result.warnings(), result.counts());
    }

    /**
     * Opens a converted model in the editor and saves its clips (main thread: it loads the model).
     *
     * @param clipFolder where the clips go, or null to not save them
     */
    public static Outcome open(ModelOperationService ops, Path source, CendaImport.Result result, Path clipFolder) {
        String name = displayName(source);
        ModelOperationService.OpenAssetResult opened = ops.openExtractedAssetModel(result.omo(), name,
                source.getFileName().toString());
        List<String> clipNames = result.clips().stream().map(CendaImport.Clip::name).toList();
        if (!opened.opened()) {
            return new Outcome(false, "Import failed: " + opened.message(), result.counts(), result.warnings(),
                    clipNames, List.of());
        }
        List<Path> saved = List.of();
        List<String> warnings = new ArrayList<>(result.warnings());
        if (clipFolder != null && !result.clips().isEmpty()) {
            try {
                saved = saveClips(clipFolder, name, result.clips());
            } catch (IOException e) {
                warnings.add("Animation clips not saved: " + e.getMessage());
                logger.warn("Clips of {} not saved", source, e);
            }
        }
        String message = "Imported " + source.getFileName() + " (" + summary(result.counts()) + ")"
                + (warnings.isEmpty() ? "" : ", " + warnings.size() + " warning(s)")
                + (saved.isEmpty() ? "" : ", " + saved.size() + " clip(s) saved") + " - use Save As to keep it";
        return new Outcome(true, message, result.counts(), warnings, clipNames, saved);
    }

    /** {@link #convert} then {@link #open}; failures become a failed outcome. */
    public static Outcome importAndOpen(ModelOperationService ops, Path source, CendaImport.Options options,
                                        Path clipFolder) {
        CendaImport.Result result;
        try {
            result = convert(source, options);
        } catch (IOException | RuntimeException e) {
            logger.warn("Import of {} failed", source, e);
            return Outcome.failed("Import failed: " + e.getMessage());
        }
        return open(ops, source, result, clipFolder);
    }

    /**
     * Writes clips as {@code <model> - <clip>.omanim} in {@code folder} (created when missing). An existing file is
     * never replaced: the name gets " (2)", " (3)" ...
     */
    public static List<Path> saveClips(Path folder, String modelName, List<CendaImport.Clip> clips) throws IOException {
        Files.createDirectories(folder);
        List<Path> out = new ArrayList<>();
        for (CendaImport.Clip clip : clips) {
            String base = sanitize(modelName) + " - " + sanitize(clip.name());
            Path target = folder.resolve(base + ".omanim");
            for (int n = 2; Files.exists(target); n++) {
                target = folder.resolve(base + " (" + n + ").omanim");
            }
            Files.write(target, clip.omanim());
            out.add(target);
        }
        return out;
    }

    /** The project's clip folder, or null without a project. */
    public static Path animationsFolder(String projectDirectory) {
        if (projectDirectory == null || projectDirectory.isBlank()) return null;
        return Path.of(projectDirectory).resolve(ANIMATIONS_FOLDER);
    }

    /** The model's name: the file name without its extension. */
    public static String displayName(Path source) {
        String file = source.getFileName().toString();
        int dot = file.lastIndexOf('.');
        return dot > 0 ? file.substring(0, dot) : file;
    }

    /** "3 parts, 120 faces, 2 materials, 1 clip". */
    public static String summary(CendaImport.Counts c) {
        if (c == null) return "nothing imported";
        List<String> parts = new ArrayList<>();
        parts.add(plural(c.parts(), "part"));
        parts.add(plural(c.faces(), "face"));
        parts.add(plural(c.materials(), "material"));
        if (c.bones() > 0) parts.add(plural(c.bones(), "bone"));
        if (c.morphs() > 0) parts.add(plural(c.morphs(), "morph"));
        if (c.vertexGroups() > 0) parts.add(plural(c.vertexGroups(), "vertex group"));
        if (c.clips() > 0) parts.add(plural(c.clips(), "clip"));
        return String.join(", ", parts);
    }

    private static String plural(int n, String word) {
        return n + " " + word + (n == 1 ? "" : "s");
    }

    /** A file-name-safe version of a name (ASCII, no path separators). */
    private static String sanitize(String name) {
        String s = name == null || name.isBlank() ? "clip" : name.trim();
        StringBuilder out = new StringBuilder();
        for (char ch : s.toCharArray()) {
            out.append(ch < 32 || ch > 126 || "/\\:*?\"<>|".indexOf(ch) >= 0 ? '_' : ch);
        }
        return out.toString().toLowerCase(Locale.ROOT).equals(".") ? "clip" : out.toString();
    }

    /** The editor's loader needs a {@code texture.omt}; an import has none (every face has a material). */
    private static byte[] withTexture(byte[] omo) throws IOException {
        try (OmoDocument doc = OmoDocument.read(omo)) {
            if (doc.texture() != null && doc.texture().length > 0) return omo;
            doc.setTexture(BlankModelFactory.blankTextureOmt());
            return doc.write();
        }
    }
}
