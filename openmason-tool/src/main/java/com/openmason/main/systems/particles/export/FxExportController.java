package com.openmason.main.systems.particles.export;

import com.openmason.api.asset.AssetSectionHost;
import com.openmason.api.asset.AssetSurface;
import com.openmason.api.fx.FxExportTarget;
import com.openmason.engine.rendering.particles.omfx.OmfxDocument;
import com.openmason.main.systems.menus.dialogs.AssetSectionSet;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * The Export FX window's state and rules (#102), ImGui- and GL-free: the system being exported, the registered
 * {@link FxExportTarget}s, the plugins' game-data sections ({@link AssetSurface#FX_EXPORT}), and export / deploy
 * with the result shown as one message. A deploy that would replace a different game file stops at a conflict the
 * user resolves ({@link #resolveConflict}).
 */
public final class FxExportController {

    private final FxExportService service;
    private final AssetSectionSet<OmfxDocument> sections;
    private OmfxDocument source;
    private List<String> sourceNotes = List.of();
    private int selected;
    private String message;
    private boolean failed;
    private FxExportService.Plan conflict;
    private Path conflictPath;

    public FxExportController(FxExportService service) {
        this.service = service;
        this.sections = new AssetSectionSet<>(AssetSurface.FX_EXPORT, new Host());
    }

    /** Starts exporting {@code document}; {@code notes} are shown above the form (missing sprite files...). */
    public void open(OmfxDocument document, List<String> notes) {
        source = document;
        sourceNotes = List.copyOf(notes);
        message = null;
        failed = false;
        conflict = null;
        sections.load(document);
        List<FxExportTarget> targets = targets();
        if (selected >= targets.size()) {
            selected = 0;
        }
        // prefer the first target that can deploy now: a game plugin's container over the neutral source format
        for (int i = 0; i < targets.size(); i++) {
            if (service.deployment(targets.get(i)).deployable()) {
                selected = i;
                break;
            }
        }
    }

    public boolean isOpen() {
        return source != null;
    }

    public void close() {
        source = null;
        conflict = null;
        sections.close();
    }

    public List<FxExportTarget> targets() {
        return FxExportService.registered();
    }

    public FxExportTarget target() {
        List<FxExportTarget> targets = targets();
        return targets.isEmpty() ? null : targets.get(Math.min(selected, targets.size() - 1));
    }

    public int selectedIndex() {
        return selected;
    }

    public void select(int index) {
        if (index != selected) {
            selected = index;
            message = null;
            conflict = null;
        }
    }

    public OmfxDocument source() {
        return source;
    }

    public List<String> notes() {
        return sourceNotes;
    }

    public AssetSectionSet<OmfxDocument> sections() {
        return sections;
    }

    /** The document with every section's form applied: what the exporter receives. */
    public OmfxDocument document() {
        return sections.write(source);
    }

    public FxExportService.Deployment deployment() {
        FxExportTarget t = target();
        return t == null ? new FxExportService.Deployment(false, null, "no export target is registered")
                : service.deployment(t);
    }

    /** The default file name for the selected target. */
    public String fileName() {
        FxExportTarget t = target();
        return t == null ? "" : FxExportService.fileName(t, source.id());
    }

    /** Exports to {@code file} (the user picked it). */
    public void exportTo(Path file) {
        FxExportService.Plan plan = planOrReport();
        if (plan == null) {
            return;
        }
        try {
            service.writeTo(plan, file);
            report("Exported " + source.id() + " to " + file, false);
            sections.written();
        } catch (IOException | RuntimeException e) {
            report("Export failed: " + e.getMessage(), true);
        }
    }

    /** Deploys into the game target's checkout; a different file already there becomes a conflict to resolve. */
    public void deploy() {
        FxExportService.Plan plan = planOrReport();
        if (plan == null) {
            return;
        }
        deploy(plan, false);
    }

    /** True while a deploy waits on Replace / Keep. */
    public boolean hasConflict() {
        return conflict != null;
    }

    public Path conflictPath() {
        return conflictPath;
    }

    /** Replace the game's file ({@code replace}) or keep it. */
    public void resolveConflict(boolean replace) {
        FxExportService.Plan plan = conflict;
        conflict = null;
        if (plan == null) {
            return;
        }
        if (replace) {
            deploy(plan, true);
        } else {
            report("Not deployed: kept the game's " + conflictPath.getFileName(), false);
        }
    }

    public String message() {
        return message;
    }

    public boolean failed() {
        return failed;
    }

    private void deploy(FxExportService.Plan plan, boolean overwrite) {
        try {
            FxExportService.Deployed d = service.deploy(plan, overwrite);
            if (d.conflict()) {
                conflict = plan;
                conflictPath = d.path();
                report(null, false);
            } else if (!d.written()) {
                report("Already deployed: " + d.path() + " is up to date", false);
            } else {
                report("Deployed " + source.id() + " to " + d.path(), false);
                sections.written();
            }
        } catch (IOException | RuntimeException e) {
            report("Deploy failed: " + e.getMessage(), true);
        }
    }

    private FxExportService.Plan planOrReport() {
        FxExportTarget t = target();
        if (t == null || source == null) {
            report("Nothing to export", true);
            return null;
        }
        AssetSectionSet.Problem blocking = sections.firstError();
        if (blocking != null) {
            report(blocking.section().title() + ": " + blocking.message(), true);
            return null;
        }
        try {
            FxExportService.Plan plan = service.plan(t, document());
            if (!plan.ok()) {
                report(t.displayName() + " cannot carry this system: " + String.join("; ", plan.problems()), true);
                return null;
            }
            return plan;
        } catch (IOException | RuntimeException e) {
            report("Export failed: " + e.getMessage(), true);
            return null;
        }
    }

    private void report(String text, boolean error) {
        message = text;
        failed = error;
    }

    /** What the sections are told about the system being exported. */
    private final class Host implements AssetSectionHost {
        @Override
        public AssetSurface<?> surface() {
            return AssetSurface.FX_EXPORT;
        }

        @Override
        public String objectId() {
            return source == null ? "" : source.id();
        }

        @Override
        public String objectType() {
            return "fx";
        }

        @Override
        public boolean texturePayload() {
            return false;
        }

        @Override
        public void markDirty() {
        }
    }
}
