package com.openmason.main.systems.menus.dialogs;

import com.openmason.engine.format.omo.CendaImport;
import com.openmason.main.systems.io.ModelImportService;
import com.openmason.main.systems.services.ModelOperationService;
import com.openmason.main.systems.services.StatusService;
import com.openmason.main.systems.stateHandling.ModelState;
import com.openmason.main.systems.themes.utils.ThemeColors;
import com.openmason.main.systems.themes.utils.ThemedWidgets;
import imgui.ImGui;
import imgui.type.ImBoolean;
import imgui.type.ImFloat;
import imgui.type.ImInt;

import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * File > Import Model (OpenMason #173): the options of the native importer for one glTF / OBJ file, then a summary
 * of what came in (counts, the importer's warnings, saved clips). Thin presentation over
 * {@link ModelImportService}; the conversion is native. The imported model opens as an unsaved copy.
 */
public final class ModelImportDialog {

    private static final String OPTIONS_ID = "Import Model##modelImportOptions";
    private static final String SUMMARY_ID = "Import Finished##modelImportSummary";
    private static final String PROGRESS_ID = "Importing##modelImportProgress";
    private static final float WIDTH = 460f;
    private static final int[] TEXTURE_LIMITS = {0, 256, 512, 1024, 2048, 4096};
    private static final String[] TEXTURE_LIMIT_LABELS = {"Unlimited", "256", "512", "1024", "2048", "4096"};

    private final ModelOperationService modelOperations;
    private final ModelState modelState;
    private final StatusService statusService;
    private Supplier<String> projectDirectory = () -> null;

    private enum Phase { CLOSED, OPTIONS, IMPORTING, SUMMARY }

    private Phase phase = Phase.CLOSED;
    private Path source;
    private ModalDialogs.Anchor anchor;
    private ModelImportService.Outcome outcome;

    // The native conversion runs on a worker (#175: it used to block the UI thread); the model opens on the UI
    // thread once it is done.
    private java.util.concurrent.CompletableFuture<CendaImport.Result> pending;
    private Path pendingClipFolder;
    private long importStartNanos;

    // Options (kept between imports, like a file dialog's last folder).
    private final ImFloat unitScale = new ImFloat(1f);
    private final ImBoolean openMasonAxis = new ImBoolean(true);
    private final ImBoolean mergeQuads = new ImBoolean(true);
    private final ImBoolean splitParts = new ImBoolean(true);
    private final ImBoolean animations = new ImBoolean(true);
    private final ImBoolean morphs = new ImBoolean(true);
    private final ImBoolean vertexGroups = new ImBoolean(true);
    private final ImBoolean saveClips = new ImBoolean(true);
    private final ImInt textureLimit = new ImInt(0);

    public ModelImportDialog(ModelOperationService modelOperations, ModelState modelState,
                             StatusService statusService) {
        this.modelOperations = modelOperations;
        this.modelState = modelState;
        this.statusService = statusService;
    }

    /** The open project's folder supplier (clips are saved to its Animations folder). */
    public void setProjectDirectory(Supplier<String> projectDirectory) {
        this.projectDirectory = projectDirectory != null ? projectDirectory : () -> null;
    }

    /** Shows the options for {@code file}. */
    public void show(Path file) {
        this.source = file;
        this.outcome = null;
        this.anchor = null;
        this.phase = Phase.OPTIONS;
    }

    public boolean isOpen() {
        return phase != Phase.CLOSED;
    }

    /** Renders the dialog (every frame, outside other windows). */
    public void render() {
        if (phase == Phase.CLOSED) return;
        if (anchor == null) anchor = ModalDialogs.Anchor.capture();
        if (phase == Phase.OPTIONS) {
            ModalDialogs.openIfNeeded(OPTIONS_ID);
            renderOptions();
        } else if (phase == Phase.IMPORTING) {
            ModalDialogs.openIfNeeded(PROGRESS_ID);
            renderImporting();
        } else {
            ModalDialogs.openIfNeeded(SUMMARY_ID);
            renderSummary();
        }
    }

    private void renderOptions() {
        if (!ModalDialogs.begin(OPTIONS_ID, anchor, WIDTH)) return;
        ImGui.textWrapped("Import " + source.getFileName() + " as a new, unsaved model.");
        ImGui.textDisabled(source.toString());
        if (modelState != null && modelState.isModelLoaded() && modelState.hasUnsavedChanges()) {
            ThemedWidgets.statusTextWrapped(ThemeColors.Tone.WARNING,
                    "The current model has unsaved changes; importing replaces it in the editor.");
        }
        ImGui.spacing();

        ImGui.setNextItemWidth(120f);
        ImGui.inputFloat("Unit scale", unitScale, 0.1f, 1f, "%.4f");
        if (unitScale.get() <= 0f) unitScale.set(1f);
        ImGui.checkbox("Turn to Open Mason axes (-Z forward)", openMasonAxis);
        ImGui.checkbox("Merge triangles into quads", mergeQuads);
        ImGui.checkbox("One part per mesh node", splitParts);
        ImGui.checkbox("Animations", animations);
        ImGui.checkbox("Shape keys (morphs)", morphs);
        ImGui.checkbox("Vertex groups", vertexGroups);
        ImGui.setNextItemWidth(120f);
        ImGui.combo("Max texture size", textureLimit, TEXTURE_LIMIT_LABELS);

        Path clipFolder = ModelImportService.animationsFolder(projectDirectory.get());
        if (clipFolder != null) {
            ImGui.beginDisabled(!animations.get());
            ImGui.checkbox("Save animation clips to the project (" + ModelImportService.ANIMATIONS_FOLDER + "/)",
                    saveClips);
            ImGui.endDisabled();
        } else if (animations.get()) {
            ImGui.textDisabled("No project is open: animation clips are not saved.");
        }

        ModalDialogs.buttonsBegin();
        if (ModalDialogs.primary("Import", true)) {
            startImport(clipFolder != null && animations.get() && saveClips.get() ? clipFolder : null);
            ModalDialogs.close();
        }
        if (ModalDialogs.cancel()) {
            phase = Phase.CLOSED;
            ModalDialogs.close();
        }
        ModalDialogs.end();
    }

    /** Starts the native conversion on a worker thread and shows the progress modal. */
    private void startImport(Path clipFolder) {
        CendaImport.Options options = new CendaImport.Options(unitScale.get(), !openMasonAxis.get(),
                mergeQuads.get(), splitParts.get(), animations.get(), morphs.get(), vertexGroups.get(), false,
                TEXTURE_LIMITS[Math.clamp(textureLimit.get(), 0, TEXTURE_LIMITS.length - 1)], 1f);
        Path file = source;
        pendingClipFolder = clipFolder;
        importStartNanos = System.nanoTime();
        pending = new java.util.concurrent.CompletableFuture<>();
        java.util.concurrent.CompletableFuture<CendaImport.Result> future = pending;
        Thread worker = new Thread(() -> {
            try {
                future.complete(ModelImportService.convert(file, options));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        }, "model-import");
        worker.setDaemon(true);
        worker.start();
        if (statusService != null) statusService.updateStatus("Importing " + file.getFileName() + "...");
        phase = Phase.IMPORTING;
    }

    /** The progress modal; when the worker is done, opens the model here on the UI thread. */
    private void renderImporting() {
        if (!ModalDialogs.begin(PROGRESS_ID, anchor, WIDTH)) return;
        if (pending != null && pending.isDone()) {
            finishImport();
            ModalDialogs.close();
            ModalDialogs.end();
            return;
        }
        double seconds = (System.nanoTime() - importStartNanos) / 1e9;
        ImGui.textWrapped("Importing " + source.getFileName() + "...");
        ImGui.textDisabled(String.format(java.util.Locale.ROOT, "%.1f s", seconds));
        ModalDialogs.end();
    }

    private void finishImport() {
        CendaImport.Result result = null;
        String failure = null;
        try {
            result = pending.join();
        } catch (java.util.concurrent.CompletionException | java.util.concurrent.CancellationException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            failure = "Import failed: " + cause.getMessage();
        }
        pending = null;
        outcome = result != null
                ? ModelImportService.open(modelOperations, source, result, pendingClipFolder)
                : new ModelImportService.Outcome(false, failure, null, null, null, null);
        if (statusService != null) statusService.updateStatus(outcome.message());
        phase = Phase.SUMMARY;
    }

    private void renderSummary() {
        if (!ModalDialogs.begin(SUMMARY_ID, anchor, WIDTH)) return;
        if (outcome.opened()) {
            ThemedWidgets.statusTextWrapped(ThemeColors.Tone.SUCCESS,
                    "Imported " + source.getFileName() + ": " + ModelImportService.summary(outcome.counts()) + ".");
            ImGui.textWrapped("The model is open as an unsaved copy - use Save Model As to keep it.");
        } else {
            ThemedWidgets.statusTextWrapped(ThemeColors.Tone.ERROR, outcome.message());
        }
        if (!outcome.clipNames().isEmpty()) {
            ImGui.spacing();
            if (outcome.savedClips().isEmpty()) {
                ImGui.textWrapped(outcome.clipNames().size() + " animation clip(s) were not saved: "
                        + String.join(", ", outcome.clipNames()));
            } else {
                ImGui.textWrapped("Saved " + outcome.savedClips().size() + " animation clip(s):");
                for (Path p : outcome.savedClips()) ImGui.bulletText(p.getFileName().toString());
            }
        }
        if (!outcome.warnings().isEmpty()) {
            ImGui.spacing();
            ThemedWidgets.statusText(ThemeColors.Tone.WARNING, outcome.warnings().size() + " warning(s):");
            ImGui.beginChild("##importWarnings", WIDTH - 20f, Math.min(180f, 22f * outcome.warnings().size() + 8f),
                    true);
            for (String w : outcome.warnings()) ImGui.textWrapped(w);
            ImGui.endChild();
        }
        ModalDialogs.buttonsBegin();
        if (ModalDialogs.primary("Close", true)) {
            phase = Phase.CLOSED;
            ModalDialogs.close();
        }
        ModalDialogs.end();
    }
}
