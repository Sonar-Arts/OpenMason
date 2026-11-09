package com.openmason.main.systems.particles;

import com.openmason.api.workspace.WorkspaceDock;
import com.openmason.engine.rendering.particles.omfx.OmfxDocument;
import com.openmason.engine.rendering.particles.omfx.OmfxMigration;
import com.openmason.main.systems.io.BuiltinFileTypes;
import com.openmason.main.systems.layout.BuiltinWorkspaces;
import com.openmason.main.systems.layout.WorkspaceState;
import com.openmason.main.systems.menus.dialogs.FileDialogService;
import com.openmason.main.systems.menus.dialogs.ModalDialogs;
import com.openmason.main.systems.particles.fx.FxEditorDocument;
import com.openmason.main.systems.services.StatusService;
import com.openmason.main.systems.services.drop.PendingFileDrops;
import imgui.ImGui;
import imgui.flag.ImGuiDir;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The FX editor (OM #104): a closable workspace tab ({@code Scene | UI | ... | Particles}) authoring {@code .omfx}
 * v2 FX systems in the Niagara / VFX Graph mould, with a live preview drawn by the engine particle renderer.
 *
 * <p>Docked panels, arranged by {@link #buildDefaultLayout}:
 * <ul>
 *   <li>{@value #SYSTEM_WINDOW} - emitters with enable/solo and a timeline track each (cycles, bursts, playhead);</li>
 *   <li>{@value #PREVIEW_WINDOW} - the system playing live (orbit, zoom, floor, wind, speed, replay);</li>
 *   <li>{@value #STACK_WINDOW} - the selected emitter's timeline fields and its effect's stage stacks: built-in
 *       modules show their fields, custom modules open in the graph; Events and Render sections;</li>
 *   <li>{@value #GRAPH_WINDOW} - the node graph of the open custom module, problems drawn on nodes;</li>
 *   <li>{@value #PARAMS_WINDOW} - exposed parameters (live preview sliders), bindings, events, light, cues;</li>
 *   <li>{@value #SPRITES_WINDOW} - the document's embedded sprites (browse or drop images).</li>
 * </ul>
 * Every edit is a step of {@link FxEditorDocument}'s undo history (Ctrl+Z / Ctrl+Shift+Z or Ctrl+Y) and reaches
 * the running preview without restarting it: changed effects are hot-swapped, the instance retargeted. Like every
 * closable tab it ends only when the user closes it; unsaved changes then ask Save / Don't Save / Cancel
 * ({@link #allowClose}). It can pop out into its own window. Strings are ASCII only: the editor's ImGui font has no
 * typographic punctuation.
 */
public final class ParticleEditorWorkspace implements WorkspaceDock {

    private static final Logger logger = LoggerFactory.getLogger(ParticleEditorWorkspace.class);

    /** Workspace id (stored name in .omp/imgui layouts). */
    public static final String ID = BuiltinWorkspaces.PARTICLES;
    /** Bump when the curated default layout changes, to force a one-time rebuild. */
    public static final int LAYOUT_VERSION = 3; // 3: one rebuild for imgui.ini files still holding the v1 arrangement

    static final String SYSTEM_WINDOW = "FX System";
    static final String PREVIEW_WINDOW = "FX Preview";
    static final String STACK_WINDOW = "Emitter Stack";
    static final String GRAPH_WINDOW = "FX Graph";
    static final String PARAMS_WINDOW = "FX Parameters";
    static final String SPRITES_WINDOW = "FX Sprites";
    /** Every docked panel {@link #renderPanels} submits; {@link #buildDefaultLayout} places each one. */
    static final List<String> PANEL_WINDOWS = List.of(SYSTEM_WINDOW, PREVIEW_WINDOW, STACK_WINDOW, GRAPH_WINDOW,
            PARAMS_WINDOW, SPRITES_WINDOW);
    private static final String UNSAVED_POPUP = "Unsaved FX##particlesUnsaved";
    private static final String MIGRATE_POPUP = "Open v1 Particle Library##particlesMigrate";
    /** What a fresh tab shows. */
    static final String DEFAULT_PRESET = "campfire";

    private final FileDialogService fileDialogs;
    private final StatusService status;
    private final WorkspaceState workspaces;
    private final FxEditorContext ctx = new FxEditorContext(FxEditorDocument.create(DEFAULT_PRESET));
    private final FxEditorPanels panels;
    private final FxGraphPanel graph;
    /** Export FX (#102): the document through the registered export targets. */
    private final com.openmason.main.systems.particles.export.FxExportWindow exportWindow;
    private Runnable resetLayout = () -> { };
    private FxPreview preview;
    private boolean reloadSprites;

    /** What runs once unsaved changes were saved or discarded (New, Open, closing the tab). */
    private Runnable pendingAfterUnsaved;
    private boolean unsavedRequested;
    private boolean wasShown;
    /** A v1 library being migrated: its documents, to pick one from. */
    private OmfxMigration.Result migrating;
    private Path migratingFrom;
    private boolean migrateRequested;

    public ParticleEditorWorkspace(FileDialogService fileDialogs, StatusService status, WorkspaceState workspaces) {
        this.exportWindow = new com.openmason.main.systems.particles.export.FxExportWindow(
                new com.openmason.main.systems.particles.export.FxExportController(
                        new com.openmason.main.systems.particles.export.FxExportService(
                                com.openmason.main.systems.io.WriteRoots.forInterface(null))), fileDialogs);
        this.fileDialogs = fileDialogs;
        this.status = status;
        this.workspaces = workspaces;
        this.graph = new FxGraphPanel(ctx);
        this.panels = new FxEditorPanels(ctx,
                fileDialogs == null ? null : cb -> fileDialogs.showOpenPNGDialog(cb::accept),
                graph::open);
    }

    /** The host's layout gate rebuilds this workspace's layout on the next frame (Reset Layout). */
    public void setResetLayoutAction(Runnable resetLayout) {
        this.resetLayout = resetLayout == null ? () -> { } : resetLayout;
    }

    /** Opens the tab (or brings it forward, raising its window when popped out). */
    public void show() {
        workspaces.open(ID);
    }

    public boolean isOpen() {
        return workspaces.isOpen(ID);
    }

    /**
     * Opens {@code path} in the tab (file opener / Open... / drops); asks first when the current document is
     * unsaved. A v1 particle library offers its effects, each as a new FX document.
     */
    public void openFile(Path path) {
        show();
        guardUnsaved(() -> {
            try {
                ctx.setDocument(FxEditorDocument.open(path));
                restartPreview();
                status.updateStatus("Opened FX " + path.getFileName());
            } catch (IllegalArgumentException v1) {
                try {
                    migrating = FxEditorDocument.migrate(path);
                    migratingFrom = path;
                    migrateRequested = true;
                } catch (Exception e) {
                    logger.warn("Could not read {}", path, e);
                    ctx.setError("Could not open " + path.getFileName() + ": " + e.getMessage());
                }
            } catch (Exception e) {
                logger.warn("Could not open FX document {}", path, e);
                ctx.setError("Could not open " + path.getFileName() + ": " + e.getMessage());
            }
        });
    }

    /** Starts a new unsaved document from a preset (asks first when the current one is unsaved). */
    public void newFromPreset(String preset) {
        show();
        guardUnsaved(() -> {
            try {
                ctx.setDocument(FxEditorDocument.create(preset));
                restartPreview();
            } catch (IllegalArgumentException e) {
                ctx.setError(e.getMessage());
            }
        });
    }

    /** Opens a custom module in the FX Graph from {@code effect:stage:index} (dev hook; stage like particle_update). */
    public void openGraph(String spec) {
        String[] parts = spec.split(":");
        try {
            graph.open(new FxEditorDocument.ModuleRef(parts[0],
                    com.openmason.engine.rendering.particles.graph.FxStageKind.valueOf(parts[1].toUpperCase(Locale.ROOT)),
                    Integer.parseInt(parts[2])));
        } catch (RuntimeException e) {
            ctx.setError("Cannot open graph '" + spec + "': " + e.getMessage());
        }
    }

    /** Opens one of a migrating v1 library's documents as a new unsaved document. */
    void openMigrated(String id) {
        if (migrating == null) {
            return;
        }
        OmfxDocument doc = migrating.document(id);
        ctx.setDocument(FxEditorDocument.of(doc, null));
        ctx.setNote("Migrated '" + id + "' from the v1 library " + migratingFrom.getFileName()
                + ". Save it as a new .omfx (the library is left as it is).");
        migrating = null;
        migratingFrom = null;
        restartPreview();
    }

    /** The open document's editing state (tests). */
    FxEditorContext context() {
        return ctx;
    }

    /** The v1 library documents waiting to be picked (tests). */
    List<String> migrationChoices() {
        return migrating == null ? List.of() : migrating.documents().stream().map(OmfxDocument::id).toList();
    }

    // ------------------------------------------------------------------------------------------------
    // WorkspaceDock
    // ------------------------------------------------------------------------------------------------

    /** System + Parameters/Sprites | Preview over Graph | Emitter Stack. */
    @Override
    public void buildDefaultLayout(int dockspaceId, float width, float height) {
        if (width <= 0 || height <= 0) {
            width = 1400;
            height = 860;
        }
        imgui.internal.ImGui.dockBuilderRemoveNode(dockspaceId);
        // DockSpace flag: a host-submitted dockspace, not a floating node (else a popped-out tab's
        // DockSpace() asserts "twice a frame"; see the Texture workspace).
        imgui.internal.ImGui.dockBuilderAddNode(dockspaceId, imgui.internal.flag.ImGuiDockNodeFlags.DockSpace);
        imgui.internal.ImGui.dockBuilderSetNodeSize(dockspaceId, width, height);
        ImInt left = new ImInt(), rest = new ImInt(), right = new ImInt(), center = new ImInt();
        ImInt leftTop = new ImInt(), leftBottom = new ImInt(), centerTop = new ImInt(), centerBottom = new ImInt();
        imgui.internal.ImGui.dockBuilderSplitNode(dockspaceId, ImGuiDir.Left, 0.27f, left, rest);
        imgui.internal.ImGui.dockBuilderSplitNode(rest.get(), ImGuiDir.Right, 0.36f, right, center);
        imgui.internal.ImGui.dockBuilderSplitNode(left.get(), ImGuiDir.Up, 0.48f, leftTop, leftBottom);
        // Split off the graph BELOW: the remaining node (the preview) stays the dockspace's one central node.
        imgui.internal.ImGui.dockBuilderSplitNode(center.get(), ImGuiDir.Down, 0.4f, centerBottom, centerTop);
        imgui.internal.ImGui.dockBuilderDockWindow(SYSTEM_WINDOW, leftTop.get());
        imgui.internal.ImGui.dockBuilderDockWindow(PARAMS_WINDOW, leftBottom.get());
        imgui.internal.ImGui.dockBuilderDockWindow(SPRITES_WINDOW, leftBottom.get());
        imgui.internal.ImGui.dockBuilderDockWindow(PREVIEW_WINDOW, centerTop.get());
        imgui.internal.ImGui.dockBuilderDockWindow(GRAPH_WINDOW, centerBottom.get());
        imgui.internal.ImGui.dockBuilderDockWindow(STACK_WINDOW, right.get());
        var node = imgui.internal.ImGui.dockBuilderGetNode(centerTop.get());
        if (node != null) {
            // The preview absorbs window resizing; the side columns keep their width.
            node.addLocalFlags(imgui.internal.flag.ImGuiDockNodeFlags.CentralNode);
        }
        imgui.internal.ImGui.dockBuilderFinish(dockspaceId);
    }

    @Override
    public List<String> panelWindows() {
        return PANEL_WINDOWS;
    }

    /** File actions, undo/redo and the document name, above the dockspace. */
    @Override
    public void renderHeader() {
        ImGui.setNextItemWidth(110);
        if (ImGui.beginCombo("##new", "New")) {
            for (String preset : FxEditorDocument.presets()) {
                if (ImGui.selectable(preset)) {
                    newFromPreset(preset);
                }
            }
            ImGui.endCombo();
        }
        ParticleEditorWidgets.hint("Start a new FX document from a preset system or effect");
        ImGui.sameLine();
        if (ImGui.button("Open...") && fileDialogs != null) {
            fileDialogs.showOpenDialog(FileDialogService.StartIn.TYPE_FOLDER, p -> openFile(Path.of(p)),
                    BuiltinFileTypes.OMFX);
        }
        ImGui.sameLine();
        if (ImGui.button("Save")) {
            save(ctx.doc().file().orElse(null), null);
        }
        ImGui.sameLine();
        if (ImGui.button("Save As...")) {
            save(null, null);
        }
        ImGui.sameLine();
        ImGui.textDisabled("|");
        ImGui.sameLine();
        FxEditorDocument doc = ctx.doc();
        if (!doc.canUndo()) ImGui.beginDisabled();
        if (ImGui.button("Undo")) {
            undo();
        }
        if (!doc.canUndo()) ImGui.endDisabled();
        ImGui.sameLine();
        if (!doc.canRedo()) ImGui.beginDisabled();
        if (ImGui.button("Redo")) {
            redo();
        }
        if (!doc.canRedo()) ImGui.endDisabled();
        ImGui.sameLine();
        ImGui.textDisabled("|");
        ImGui.sameLine();
        if (ImGui.button("Export FX...")) {
            openExport();
        }
        ParticleEditorWidgets.hint("Export the FX system: .omfx v2, or a game's own format (deploys into the game)");
        ImGui.sameLine();
        if (ImGui.button("Reset Layout")) {
            resetLayout.run();
        }
        ImGui.sameLine();
        ImGui.textDisabled(doc.id() + "  -  " + doc.file().map(p -> p.getFileName().toString()).orElse("(unsaved)")
                + (doc.isDirty() ? " *" : ""));
    }

    @Override
    public void renderPanels(float deltaTime, boolean shown) {
        if (!shown) {
            wasShown = false;
            if (!isOpen()) {
                onClosed();
            }
            return;
        }
        processDrops();
        handleShortcuts();

        if (ImGui.begin(SYSTEM_WINDOW)) {
            renderMessages();
            panels.renderSystem();
        }
        ImGui.end();
        if (ImGui.begin(PREVIEW_WINDOW, ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse)) {
            renderPreview(ImGui.getContentRegionAvailX());
        }
        ImGui.end();
        if (ImGui.begin(STACK_WINDOW)) {
            panels.renderStack();
        }
        ImGui.end();
        if (ImGui.begin(GRAPH_WINDOW, ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse)) {
            graph.render();
        }
        ImGui.end();
        if (ImGui.begin(PARAMS_WINDOW)) {
            panels.renderParameters();
        }
        ImGui.end();
        if (ImGui.begin(SPRITES_WINDOW)) {
            panels.renderSprites();
        }
        ImGui.end();

        if (!ImGui.isAnyItemActive()) {
            ctx.doc().seal(); // the drag (if any) ended: the next edit is its own undo step
        }
        if (preview != null) {
            preview.show(ctx.previewSystem());
        }
        if (!wasShown) {
            wasShown = true;
            ImGui.setWindowFocus(PREVIEW_WINDOW);
        }
        exportWindow.render();
        renderUnsavedDialog();
        renderMigrateDialog();
    }

    private void renderMessages() {
        if (ctx.error() != null) {
            ImGui.textColored(0.95f, 0.45f, 0.4f, 1f, ctx.error());
            ImGui.sameLine();
            if (ImGui.smallButton("ok##err")) {
                ctx.setError(null);
            }
        }
        if (ctx.note() != null) {
            ImGui.textColored(0.95f, 0.8f, 0.4f, 1f, ctx.note());
            ImGui.sameLine();
            if (ImGui.smallButton("ok##note")) {
                ctx.setNote(null);
            }
        }
    }

    private void handleShortcuts() {
        var io = ImGui.getIO();
        if (io.getWantTextInput() || !io.getKeyCtrl()) {
            return;
        }
        if (ImGui.isKeyPressed(ImGuiKey.Z, false)) {
            if (io.getKeyShift()) redo(); else undo();
        } else if (ImGui.isKeyPressed(ImGuiKey.Y, false)) {
            redo();
        } else if (ImGui.isKeyPressed(ImGuiKey.S, false)) {
            save(ctx.doc().file().orElse(null), null);
        }
    }

    void undo() {
        ctx.undo();
    }

    void redo() {
        ctx.redo();
    }

    /** Dropped files while the tab is shown: images become sprites, .omfx files open. */
    private void processDrops() {
        while (PendingFileDrops.hasPending()) {
            String[] files = PendingFileDrops.poll();
            if (files == null) {
                continue;
            }
            for (String f : files) {
                String lower = f.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".omfx")) {
                    openFile(Path.of(f));
                } else {
                    FxEditorPanels.embedFile(ctx, Path.of(f));
                }
            }
        }
    }

    /** Opens Export FX on the document, its sprite files next to it embedded so the export is self-contained. */
    void openExport() {
        FxExportSource source = FxExportSource.of(ctx.doc().document(), ctx.documentFolder());
        List<String> notes = new ArrayList<>();
        notes.add("Exports the FX system '" + ctx.doc().id() + "' (" + ctx.doc().system().emitters().size()
                + " emitter(s)).");
        if (!source.missing().isEmpty()) {
            notes.add("Sprite files not found, exported as names the game must provide: "
                    + String.join(", ", source.missing()));
        }
        exportWindow.show(source.document(), notes);
    }

    /** The Export FX window's state (tests). */
    com.openmason.main.systems.particles.export.FxExportController exportController() {
        return exportWindow.controller();
    }

    /** The user closes the tab: unsaved changes ask Save / Don't Save / Cancel first. */
    @Override
    public boolean allowClose(Runnable proceed) {
        if (!ctx.doc().isDirty()) {
            return true;
        }
        workspaces.set(ID); // come forward to ask
        pendingAfterUnsaved = proceed;
        unsavedRequested = true;
        return false;
    }

    /** The tab closed: the session ends (preview GL released, a fresh document next time it opens). */
    private void onClosed() {
        cleanup();
        if (ctx.doc().isDirty() || ctx.doc().file().isPresent() || !DEFAULT_PRESET.equals(ctx.doc().id())) {
            ctx.setDocument(FxEditorDocument.create(DEFAULT_PRESET));
        }
        exportWindow.controller().close();
        migrating = null;
    }

    // ------------------------------------------------------------------------------------------------
    // Files and dialogs
    // ------------------------------------------------------------------------------------------------

    /** Saves to {@code path} (null = ask where), then runs {@code after} if the save succeeded. */
    private void save(Path path, Runnable after) {
        if (path == null) {
            if (fileDialogs == null) {
                return;
            }
            String name = ctx.doc().file().map(p -> p.getFileName().toString()).orElse(ctx.doc().id() + ".omfx");
            fileDialogs.showSaveDialog(BuiltinFileTypes.OMFX, name, null, p -> save(Path.of(p), after));
            return;
        }
        try {
            ctx.doc().save(path);
            status.updateStatus("Saved FX " + path.getFileName());
            if (after != null) {
                after.run();
            }
        } catch (Exception e) {
            logger.warn("Could not save FX document {}", path, e);
            ctx.setError("Could not save: " + e.getMessage());
        }
    }

    private void guardUnsaved(Runnable action) {
        if (ctx.doc().isDirty()) {
            pendingAfterUnsaved = action;
            unsavedRequested = true; // opened from renderPanels: popups resolve against one ID stack
        } else {
            action.run();
        }
    }

    private void renderUnsavedDialog() {
        if (unsavedRequested) {
            ModalDialogs.openIfNeeded(UNSAVED_POPUP);
            unsavedRequested = false;
        }
        if (!ModalDialogs.begin(UNSAVED_POPUP, 380)) {
            return;
        }
        ImGui.textWrapped("The FX document '" + ctx.doc().id() + "' has unsaved changes. Save them first?");
        ModalDialogs.buttonsBegin();
        if (ModalDialogs.primary("Save", true)) {
            Runnable after = takePending();
            ModalDialogs.close();
            save(ctx.doc().file().orElse(null), after);
        }
        ImGui.sameLine();
        if (ModalDialogs.danger("Don't Save", true)) {
            Runnable after = takePending();
            ModalDialogs.close();
            if (after != null) {
                after.run();
            }
        }
        ImGui.sameLine();
        if (ModalDialogs.cancel()) {
            pendingAfterUnsaved = null;
            ModalDialogs.close();
        }
        ModalDialogs.end();
    }

    private void renderMigrateDialog() {
        if (migrateRequested) {
            ModalDialogs.openIfNeeded(MIGRATE_POPUP);
            migrateRequested = false;
        }
        if (!ModalDialogs.begin(MIGRATE_POPUP, 420)) {
            return;
        }
        if (migrating == null) {
            ModalDialogs.close();
            ModalDialogs.end();
            return;
        }
        ImGui.textWrapped(migratingFrom.getFileName() + " is a v1 particle library. Each of its effects becomes an"
                + " FX document of its own: pick one to open (save it as a new .omfx).");
        for (String w : migrating.warnings()) {
            ImGui.textColored(0.95f, 0.8f, 0.4f, 1f, w);
        }
        for (OmfxDocument d : migrating.documents()) {
            if (ImGui.selectable(d.id())) {
                String id = d.id();
                ModalDialogs.close();
                openMigrated(id);
                break;
            }
        }
        ModalDialogs.buttonsBegin();
        if (ModalDialogs.cancel()) {
            migrating = null;
            ModalDialogs.close();
        }
        ModalDialogs.end();
    }

    private Runnable takePending() {
        Runnable r = pendingAfterUnsaved;
        pendingAfterUnsaved = null;
        return r;
    }

    // ------------------------------------------------------------------------------------------------
    // Preview
    // ------------------------------------------------------------------------------------------------

    private void restartPreview() {
        graph.open(null);
        if (preview != null) {
            preview.show(ctx.previewSystem());
            preview.restart();
        }
    }

    private void renderPreview(float width) {
        if (preview == null) {
            try {
                preview = new FxPreview();
                panels.setPreview(preview);
            } catch (RuntimeException e) {
                ImGui.textWrapped("Preview unavailable: " + e.getMessage());
                return;
            }
        }
        preview.show(ctx.previewSystem());
        try {
            preview.useSprites(ctx.doc().document(), ctx.documentFolder(), reloadSprites);
        } catch (RuntimeException e) {
            ctx.setError("Sprites: " + e.getMessage());
        }
        reloadSprites = false;

        float controlsHeight = ImGui.getFrameHeightWithSpacing() * 2 + 6f;
        float imageHeight = Math.max(120f, ImGui.getContentRegionAvailY() - controlsHeight);
        int texture = preview.render((int) width, (int) imageHeight, ImGui.getIO().getDeltaTime());
        float imageX = ImGui.getCursorScreenPosX(), imageY = ImGui.getCursorScreenPosY();
        ImGui.image(texture, width, imageHeight, 0, 1, 1, 0); // GL rows are bottom-up
        // An invisible button over the image owns the mouse, so dragging orbits instead of moving the panel.
        ImGui.setCursorScreenPos(imageX, imageY);
        ImGui.invisibleButton("##orbit", width, imageHeight);
        if (ImGui.isItemHovered() || ImGui.isItemActive()) {
            if (ImGui.isItemActive() && ImGui.isMouseDragging(0)) {
                preview.orbit(ImGui.getIO().getMouseDeltaX(), ImGui.getIO().getMouseDeltaY());
            }
            float wheel = ImGui.getIO().getMouseWheel();
            if (wheel != 0f) {
                preview.zoom(wheel);
            }
        }

        if (ImGui.button(preview.playing ? "Pause" : "Play")) {
            preview.playing = !preview.playing;
        }
        ImGui.sameLine();
        if (ImGui.button("Restart")) {
            preview.restart();
        }
        ImGui.sameLine();
        if (ImGui.button("Frame")) {
            preview.frameParticles();
        }
        ImGui.sameLine();
        if (ImGui.checkbox("Loop", preview.loop)) {
            preview.loop = !preview.loop;
        }
        ParticleEditorWidgets.hint("Replay the system when it ends");
        ImGui.sameLine();
        ImGui.setNextItemWidth(90);
        float[] speed = {preview.timeScale};
        if (ImGui.sliderFloat("speed", speed, 0.05f, 2f, "%.2fx")) {
            preview.timeScale = speed[0];
        }
        ImGui.sameLine();
        if (ImGui.checkbox("Floor", preview.showGround)) {
            preview.showGround = !preview.showGround;
            preview.restart();
        }
        ImGui.sameLine();
        if (ImGui.checkbox("Soft", preview.softParticles)) {
            preview.softParticles = !preview.softParticles;
        }
        ImGui.sameLine();
        ImGui.setNextItemWidth(90);
        float[] wind = {preview.wind};
        if (ImGui.sliderFloat("wind", wind, -5f, 5f, "%.1f")) {
            preview.wind = wind[0];
        }
        ImGui.sameLine();
        if (ImGui.smallButton("Reload images")) {
            reloadSprites = true;
        }
        ImGui.textDisabled(String.format(Locale.ROOT, "t %.2fs   live %d   drawn %d   draws %d   dropped %d   gpu %s   heap %s",
                preview.time(), preview.live(), preview.drawn(), preview.drawCalls(), preview.dropped(),
                ParticleEditorWidgets.bytes(preview.gpuBytes()), ParticleEditorWidgets.bytes(preview.heapBytes())));
    }

    /** Releases the preview's GL resources (render thread, at shutdown). */
    public void cleanup() {
        if (preview != null) {
            preview.close();
            preview = null;
            panels.setPreview(null);
        }
        panels.close();
    }
}
