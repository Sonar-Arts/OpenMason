package com.openmason.main.systems.mcp;

import com.openmason.main.systems.services.EditorServices;
import com.openmason.main.systems.io.AssetWriteService;
import com.openmason.main.systems.io.AssetWriteService.WriteOutcome;
import com.openmason.main.systems.io.AssetWriteService.WriteRequest;
import com.openmason.main.systems.io.WriteKind;
import com.openmason.main.systems.io.WriteTarget;
import com.openmason.main.systems.services.ModelOperationService;
import com.openmason.main.systems.stateHandling.ModelState;
import com.openmason.main.systems.threading.MainThreadExecutor;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The model editor's file lifecycle for agents: save the current model as
 * {@code .omo} (through the write sandbox / Save Sheet), open a {@code .omo},
 * start a blank one. Replacing unsaved work is approval-gated exactly like
 * {@code asset_open}.
 */
public final class ModelFileService {

    private static final long MAIN_TIMEOUT_S = 30;

    private final EditorServices editorServices;
    private final AssetWriteService writes;

    public ModelFileService(EditorServices editorServices, AssetWriteService writes) {
        this.editorServices = editorServices;
        this.writes = writes;
    }

    /** Snapshot of the editor state read on the main thread. */
    private record Snapshot(boolean loaded, boolean canSave, boolean dirty, boolean hasFile,
                            String filePath, String name) {
    }

    // ---------------------------------------------------------------- save

    public WriteOutcome save(String filePath, boolean prompt, boolean overwrite) {
        return save(filePath, prompt, overwrite, List.of(), false);
    }

    /**
     * @param extraDetails         extra lines for the Save Sheet (e.g. why an export needs this)
     * @param requiresPriorOmoSave marks the sheet as the .omo step of an export chain
     */
    public WriteOutcome save(String filePath, boolean prompt, boolean overwrite,
                             List<String> extraDetails, boolean requiresPriorOmoSave) {
        Snapshot s = snapshot();
        if (!s.loaded()) {
            throw new IllegalArgumentException("no_model: nothing is loaded in the model editor");
        }
        if (!s.canSave()) {
            throw new IllegalArgumentException("read_only_model: the loaded model is a read-only "
                    + "browser reference — create or open an .omo first");
        }
        ModelOperationService ops = requireOps();
        AssetWriteService.Writer writer = p -> ops.saveModelToPath(p.toString());
        if (filePath == null && !prompt && s.hasFile()) {
            return writes.saveInPlace(WriteKind.OMO, s.filePath(), writer);
        }
        String suggested = s.name() == null || s.name().isBlank() ? "model" : s.name();
        List<String> details = new ArrayList<>();
        details.add("Model: " + suggested);
        details.addAll(extraDetails);
        return writes.save(WriteRequest.of(WriteKind.OMO, filePath, prompt, overwrite, suggested)
                .withDetails(details).withPriorOmoSave(requiresPriorOmoSave), writer);
    }

    // ---------------------------------------------------------------- open

    public Map<String, Object> open(String filePath, int timeoutSeconds) {
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("file_path is required");
        }
        // Same grammar as writes (absolute / project: / game: / bare name) and
        // the same containment rule: agents read models from the roots only.
        WriteTarget t = writes.sandbox().resolve(WriteKind.OMO, filePath);
        if (!Files.isRegularFile(t.path())) {
            throw new IllegalArgumentException("no_such_file: " + t.path());
        }
        Snapshot s = snapshot();
        if (s.loaded() && s.dirty()) {
            Map<String, Object> declined = confirmReplace("model_open",
                    "An agent asks to open '" + t.fileName() + "' into the editor",
                    List.of("File: " + t.path(), "The current model has unsaved changes."),
                    timeoutSeconds);
            if (declined != null) {
                return declined;
            }
        }
        ModelOperationService ops = requireOps();
        String path = t.path().toString();
        return onMain(() -> {
            ops.loadOMOModel(path);
            ModelState st = editorServices.getModelState();
            boolean ok = st != null && st.isModelLoaded() && path.equals(st.getCurrentOMOFilePath());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("opened", ok);
            out.put("path", path);
            if (ok) {
                out.put("name", st.getCurrentModelPath());
            } else {
                out.put("reason", "load_failed");
                out.put("message", "the model did not load — see the Open Mason log");
            }
            return out;
        });
    }

    // -------------------------------------------------------------- import

    /**
     * Imports a glTF / OBJ file (#173) into the editor as an unsaved model (replacing unsaved work asks the user).
     *
     * @param saveClipsTo a folder for the glTF animations ({@code project:Animations}, {@code project} = that, an
     *                    absolute folder inside a root), or null to not save them
     */
    public Map<String, Object> importModel(String filePath,
                                           com.openmason.engine.format.omo.CendaImport.Options options,
                                           String saveClipsTo, int timeoutSeconds) {
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("path is required");
        }
        java.nio.file.Path source = writes.sandbox().resolveExisting(filePath);
        if (!com.openmason.engine.format.omo.CendaImport.canImport(source.getFileName().toString())) {
            throw new IllegalArgumentException("unsupported_format: " + source.getFileName()
                    + " (import reads .gltf, .glb and .obj)");
        }
        java.nio.file.Path clipFolder = null;
        if (saveClipsTo != null && !saveClipsTo.isBlank()) {
            String folder = saveClipsTo.trim().equalsIgnoreCase("project")
                    ? "project:" + com.openmason.main.systems.io.ModelImportService.ANIMATIONS_FOLDER : saveClipsTo.trim();
            clipFolder = writes.sandbox().resolve(WriteKind.OMANIM,
                    folder.replaceFirst("[/\\\\]+$", "") + "/probe.omanim").path().getParent();
        }
        Snapshot s = snapshot();
        if (s.loaded() && s.dirty()) {
            Map<String, Object> declined = confirmReplace("model_import",
                    "An agent asks to import '" + source.getFileName() + "' into the editor",
                    List.of("File: " + source, "The current model has unsaved changes."), timeoutSeconds);
            if (declined != null) {
                return declined;
            }
        }
        com.openmason.engine.format.omo.CendaImport.Result result;
        try {
            result = com.openmason.main.systems.io.ModelImportService.convert(source, options);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("import_failed: " + e.getMessage(), e);
        }
        ModelOperationService ops = requireOps();
        java.nio.file.Path clips = clipFolder;
        com.openmason.main.systems.io.ModelImportService.Outcome outcome = onMain(() ->
                com.openmason.main.systems.io.ModelImportService.open(ops, source, result, clips));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("opened", outcome.opened());
        out.put("message", outcome.message());
        com.openmason.engine.format.omo.CendaImport.Counts c = outcome.counts();
        if (c != null) {
            Map<String, Object> counts = new LinkedHashMap<>();
            counts.put("parts", c.parts());
            counts.put("bones", c.bones());
            counts.put("faces", c.faces());
            counts.put("triangles", c.triangles());
            counts.put("vertices", c.vertices());
            counts.put("materials", c.materials());
            counts.put("max_influences", c.maxInfluences());
            counts.put("morphs", c.morphs());
            counts.put("vertex_groups", c.vertexGroups());
            counts.put("clips", c.clips());
            out.put("counts", counts);
        }
        out.put("warnings", outcome.warnings());
        if (!outcome.clipNames().isEmpty()) out.put("clips", outcome.clipNames());
        if (!outcome.savedClips().isEmpty()) {
            out.put("saved_clips", outcome.savedClips().stream().map(java.nio.file.Path::toString).toList());
        }
        return out;
    }

    // ----------------------------------------------------------------- new

    public Map<String, Object> newModel(int timeoutSeconds) {
        Snapshot s = snapshot();
        if (s.loaded() && s.dirty()) {
            Map<String, Object> declined = confirmReplace("model_new",
                    "An agent asks to start a new blank model",
                    List.of("The current model has unsaved changes."), timeoutSeconds);
            if (declined != null) {
                return declined;
            }
        }
        ModelOperationService ops = requireOps();
        return onMain(() -> {
            ops.newModel();
            ModelState st = editorServices.getModelState();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("opened", st != null && st.isModelLoaded());
            out.put("name", st != null ? st.getCurrentModelPath() : null);
            return out;
        });
    }

    // ------------------------------------------------------------- helpers

    /** Null when approved; otherwise the structured decline to return. */
    private Map<String, Object> confirmReplace(String action, String title, List<String> details,
                                               int timeoutSeconds) {
        return McpApprovals.confirm(editorServices != null ? editorServices.getApprovalGate() : null,
                action, title, details, timeoutSeconds);
    }

    private Snapshot snapshot() {
        return onMain(() -> {
            ModelState st = editorServices != null ? editorServices.getModelState() : null;
            if (st == null) {
                throw new IllegalStateException("Model editor unavailable — is the UI running?");
            }
            return new Snapshot(st.isModelLoaded(), st.canSaveModel(), st.hasUnsavedChanges(),
                    st.hasOMOFile(), st.getCurrentOMOFilePath(), st.getCurrentModelPath());
        });
    }

    private ModelOperationService requireOps() {
        ModelOperationService ops = editorServices != null ? editorServices.getModelOperations() : null;
        if (ops == null) {
            throw new IllegalStateException("Model editor unavailable — is the UI running?");
        }
        return ops;
    }

    private static <T> T onMain(java.util.concurrent.Callable<T> task) {
        try {
            return MainThreadExecutor.submit(task).get(MAIN_TIMEOUT_S, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("Operation timed out on main thread", e);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(cause);
        }
    }
}
