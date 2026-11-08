package com.openmason.main.systems.particles;

import com.openmason.api.workspace.WorkspaceDock;
import com.openmason.engine.rendering.particles.ParticleEffect;
import com.openmason.engine.rendering.particles.ParticleLibrary;
import com.openmason.main.systems.io.BuiltinFileTypes;
import com.openmason.main.systems.layout.BuiltinWorkspaces;
import com.openmason.main.systems.layout.WorkspaceState;
import com.openmason.main.systems.menus.dialogs.FileDialogService;
import com.openmason.main.systems.menus.dialogs.ModalDialogs;
import com.openmason.main.systems.services.StatusService;
import imgui.ImGui;
import imgui.flag.ImGuiDir;
import imgui.flag.ImGuiTreeNodeFlags;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImInt;
import imgui.type.ImString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * The Particle Editor: a closable workspace tab ({@code Scene | UI | ... | Particles}) authoring {@code .omfx}
 * particle libraries ({@link ParticleLibrary} JSON) with a live preview drawn by the engine particle renderer.
 *
 * <p>Follows the workspace-tab framework ({@link WorkspaceDock}): a toolbar in {@link #renderHeader} (file
 * actions, Copy JSON, Reset Layout), and three docked panels arranged by {@link #buildDefaultLayout} and drawn in
 * {@link #renderPanels} - {@value #EFFECTS_WINDOW} (the library's effects), {@value #PREVIEW_WINDOW} (drag to
 * orbit, wheel to zoom; continuous or looping-burst playback, floor + soft-particle toggles, budget stats) and
 * {@value #INSPECTOR_WINDOW} (every {@link ParticleEffect} field). Like every closable tab it ends only when the
 * user closes it ({@link WorkspaceState#requestClose}); unsaved changes then ask Save / Don't Save / Cancel
 * ({@link #allowClose}). It can pop out into its own window like any workspace.
 *
 * <p>Edits rebuild the immutable effect through its builder, cascade to effects that spawn it
 * ({@link ParticleEditorModel#apply}) and hot-swap into the running preview, so dragging a slider updates
 * particles already in flight. An invalid value is reported and not applied. Strings are ASCII only: the
 * editor's ImGui font has no typographic punctuation.
 */
public final class ParticleEditorWorkspace implements WorkspaceDock {

    private static final Logger logger = LoggerFactory.getLogger(ParticleEditorWorkspace.class);

    /** Workspace id (stored name in .omp/imgui layouts). */
    public static final String ID = BuiltinWorkspaces.PARTICLES;
    /** Bump when the curated default layout changes, to force a one-time rebuild. */
    public static final int LAYOUT_VERSION = 1;

    static final String EFFECTS_WINDOW = "Particle Effects";
    static final String PREVIEW_WINDOW = "Particle Preview";
    static final String INSPECTOR_WINDOW = "Particle Inspector";
    private static final String UNSAVED_POPUP = "Unsaved Particle Library##particlesUnsaved";

    private final ParticleEditorModel model = new ParticleEditorModel();
    private final FileDialogService fileDialogs;
    private final StatusService status;
    private final WorkspaceState workspaces;
    private Runnable resetLayout = () -> { };
    private ParticlePreview preview;

    private final ImString nameBuffer = new ImString(64);
    private final ImString textureBuffer = new ImString(256);
    /** A texture picked in the file dialog, appended to the selected effect on the next inspector frame. */
    private volatile String pendingTexture;
    private boolean reloadSprites;
    private String nameBufferFor;
    private String error;

    /** What runs once unsaved changes were saved or discarded (New, Open, closing the tab). */
    private Runnable pendingAfterUnsaved;
    private boolean unsavedRequested;
    private boolean wasShown;

    /** The selected effect at the start of the inspector pass, and the builder collecting this frame's edits. */
    private ParticleEffect current;
    private ParticleEffect.Builder edit;

    public ParticleEditorWorkspace(FileDialogService fileDialogs, StatusService status, WorkspaceState workspaces) {
        this.fileDialogs = fileDialogs;
        this.status = status;
        this.workspaces = workspaces;
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

    /** Opens {@code path} in the tab (file opener / Open...); asks first when the current library is unsaved. */
    public void openFile(Path path) {
        show();
        guardUnsaved(() -> {
            try {
                model.open(path);
                restartPreview();
                status.updateStatus("Opened particle library " + path.getFileName());
            } catch (Exception e) {
                logger.warn("Could not open particle library {}", path, e);
                error = "Could not open " + path.getFileName() + ": " + e.getMessage();
            }
        });
    }

    /** Selects the effect called {@code name} (ignored if absent). */
    public void select(String name) {
        model.select(name);
        restartPreview();
    }

    ParticleEditorModel model() {
        return model;
    }

    // ------------------------------------------------------------------------------------------------
    // WorkspaceDock
    // ------------------------------------------------------------------------------------------------

    /** Effects | Preview | Inspector. */
    @Override
    public void buildDefaultLayout(int dockspaceId, float width, float height) {
        if (width <= 0 || height <= 0) {
            width = 1200;
            height = 800;
        }
        imgui.internal.ImGui.dockBuilderRemoveNode(dockspaceId);
        // DockSpace flag: a host-submitted dockspace, not a floating node (else a popped-out tab's
        // DockSpace() asserts "twice a frame"; see the Texture workspace).
        imgui.internal.ImGui.dockBuilderAddNode(dockspaceId, imgui.internal.flag.ImGuiDockNodeFlags.DockSpace);
        imgui.internal.ImGui.dockBuilderSetNodeSize(dockspaceId, width, height);
        ImInt left = new ImInt(), rest = new ImInt(), right = new ImInt(), center = new ImInt();
        imgui.internal.ImGui.dockBuilderSplitNode(dockspaceId, ImGuiDir.Left, 0.17f, left, rest);
        imgui.internal.ImGui.dockBuilderSplitNode(rest.get(), ImGuiDir.Right, 0.34f, right, center);
        imgui.internal.ImGui.dockBuilderDockWindow(EFFECTS_WINDOW, left.get());
        imgui.internal.ImGui.dockBuilderDockWindow(PREVIEW_WINDOW, center.get());
        imgui.internal.ImGui.dockBuilderDockWindow(INSPECTOR_WINDOW, right.get());
        var node = imgui.internal.ImGui.dockBuilderGetNode(center.get());
        if (node != null) {
            // The preview absorbs window resizing; the side columns keep their width.
            node.addLocalFlags(imgui.internal.flag.ImGuiDockNodeFlags.CentralNode);
        }
        imgui.internal.ImGui.dockBuilderFinish(dockspaceId);
    }

    /** File actions and the document name, above the dockspace. */
    @Override
    public void renderHeader() {
        if (ImGui.button("New")) {
            guardUnsaved(() -> {
                model.newDocument();
                restartPreview();
            });
        }
        ImGui.sameLine();
        if (ImGui.button("Open...")) {
            fileDialogs.showOpenDialog(FileDialogService.StartIn.TYPE_FOLDER, p -> openFile(Path.of(p)),
                    BuiltinFileTypes.OMFX);
        }
        ImGui.sameLine();
        if (ImGui.button("Save")) {
            save(model.file().orElse(null), null);
        }
        ImGui.sameLine();
        if (ImGui.button("Save As...")) {
            save(null, null);
        }
        ImGui.sameLine();
        ImGui.textDisabled("|");
        ImGui.sameLine();
        boolean hasSelection = model.selected().isPresent();
        if (ImGui.button("Copy JSON") && hasSelection) {
            model.selected().ifPresent(e -> {
                ImGui.setClipboardText(ParticleLibrary.toJsonText(e));
                status.updateStatus("Copied '" + e.name() + "' as JSON");
            });
        }
        ImGui.sameLine();
        if (ImGui.button("Reset Layout")) {
            resetLayout.run();
        }
        ImGui.sameLine();
        ImGui.textDisabled(model.file().map(p -> p.getFileName().toString()).orElse("(built-in presets, unsaved)")
                + (model.isDirty() ? " *" : ""));
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
        if (ImGui.begin(EFFECTS_WINDOW)) {
            renderEffectList();
        }
        ImGui.end();
        if (ImGui.begin(PREVIEW_WINDOW, ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse)) {
            renderPreview(ImGui.getContentRegionAvailX());
        }
        ImGui.end();
        if (ImGui.begin(INSPECTOR_WINDOW)) {
            renderInspector();
        }
        ImGui.end();
        if (!wasShown) {
            // The tab just came forward: start the preview on the selection again (it paused while hidden).
            wasShown = true;
            ImGui.setWindowFocus(PREVIEW_WINDOW);
        }
        renderUnsavedDialog();
    }

    /** The user closes the tab: unsaved changes ask Save / Don't Save / Cancel first. */
    @Override
    public boolean allowClose(Runnable proceed) {
        if (!model.isDirty()) {
            return true;
        }
        workspaces.set(ID); // come forward to ask
        pendingAfterUnsaved = proceed;
        unsavedRequested = true;
        return false;
    }

    /** The tab closed: the session ends (preview GL released, fresh document next time it opens). */
    private void onClosed() {
        if (preview != null) {
            preview.close();
            preview = null;
        }
        if (model.isDirty() || model.file().isPresent()) {
            model.newDocument();
        }
        error = null;
    }

    // ------------------------------------------------------------------------------------------------
    // Files and the unsaved-changes dialog
    // ------------------------------------------------------------------------------------------------

    /** Saves to {@code path} (null = ask where), then runs {@code after} if the save succeeded. */
    private void save(Path path, Runnable after) {
        if (path == null) {
            String name = model.file().map(p -> p.getFileName().toString()).orElse("particles.omfx");
            fileDialogs.showSaveDialog(BuiltinFileTypes.OMFX, name, null, p -> save(Path.of(p), after));
            return;
        }
        try {
            model.save(path);
            status.updateStatus("Saved particle library " + path.getFileName());
            if (after != null) {
                after.run();
            }
        } catch (Exception e) {
            logger.warn("Could not save particle library {}", path, e);
            error = "Could not save: " + e.getMessage();
        }
    }

    private void guardUnsaved(Runnable action) {
        if (model.isDirty()) {
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
        ImGui.textWrapped("The particle library has unsaved changes. Save them first?");
        ModalDialogs.buttonsBegin();
        if (ModalDialogs.primary("Save", true)) {
            Runnable after = takePending();
            ModalDialogs.close();
            save(model.file().orElse(null), after);
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

    private Runnable takePending() {
        Runnable r = pendingAfterUnsaved;
        pendingAfterUnsaved = null;
        return r;
    }

    // ------------------------------------------------------------------------------------------------
    // Effect list
    // ------------------------------------------------------------------------------------------------

    private void renderEffectList() {
        if (ImGui.button("New")) {
            model.create("effect");
            restartPreview();
        }
        ImGui.sameLine();
        if (ImGui.button("Duplicate") && model.selected().isPresent()) {
            model.duplicateSelected();
            restartPreview();
        }
        ImGui.sameLine();
        if (ImGui.button("Delete") && model.selected().isPresent()) {
            List<String> users = model.deleteSelected();
            if (users.isEmpty()) {
                restartPreview();
            } else {
                error = "Still spawned by: " + String.join(", ", users);
            }
        }
        ImGui.separator();
        String selected = model.selected().map(ParticleEffect::name).orElse(null);
        for (String name : model.names()) {
            if (ImGui.selectable(name, name.equals(selected))) {
                model.select(name);
                error = null;
                restartPreview();
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Preview
    // ------------------------------------------------------------------------------------------------

    private void restartPreview() {
        if (preview != null) {
            model.selected().ifPresent(preview::show);
            preview.restart();
        }
    }

    private void renderPreview(float width) {
        if (preview == null) {
            try {
                preview = new ParticlePreview();
                model.selected().ifPresent(preview::show);
            } catch (RuntimeException e) {
                ImGui.textWrapped("Preview unavailable: " + e.getMessage());
                return;
            }
        }
        model.selected().ifPresent(preview::show);
        try {
            preview.useSprites(model.library().effects(), documentFolder(), reloadSprites);
        } catch (RuntimeException e) {
            error = "Sprites: " + e.getMessage();
        }
        reloadSprites = false;

        float controlsHeight = ImGui.getFrameHeightWithSpacing() * 3 + 6f;
        float imageHeight = Math.max(120f, ImGui.getContentRegionAvailY() - controlsHeight);
        int texture = preview.render((int) width, (int) imageHeight, ImGui.getIO().getDeltaTime());
        float imageX = ImGui.getCursorScreenPosX(), imageY = ImGui.getCursorScreenPosY();
        ImGui.image(texture, width, imageHeight, 0, 1, 1, 0); // GL rows are bottom-up
        // An invisible button over the image owns the mouse, so dragging orbits instead of moving the
        // (possibly floating or popped-out) panel.
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
        if (ImGui.button("Burst")) {
            preview.burstNow();
        }
        ImGui.sameLine();
        if (ImGui.button("Frame")) {
            preview.frameParticles();
        }
        ImGui.sameLine();
        ImInt mode = new ImInt(preview.mode.ordinal());
        ImGui.setNextItemWidth(110);
        if (ImGui.combo("##mode", mode, new String[]{"Continuous", "Burst loop"})) {
            preview.mode = ParticlePreview.Mode.values()[mode.get()];
            preview.restart();
        }
        ImGui.sameLine();
        ImGui.setNextItemWidth(90);
        float[] speed = {preview.timeScale};
        if (ImGui.sliderFloat("speed", speed, 0.05f, 2f, "%.2fx")) {
            preview.timeScale = speed[0];
        }

        if (preview.mode == ParticlePreview.Mode.BURST) {
            int[] count = {preview.burstCount};
            ImGui.setNextItemWidth(110);
            if (ImGui.dragInt("count", count, 1f, 1, 5000)) preview.burstCount = count[0];
            ImGui.sameLine();
            float[] every = {preview.burstInterval};
            ImGui.setNextItemWidth(110);
            if (ImGui.dragFloat("every (s)", every, 0.05f, 0.1f, 20f)) preview.burstInterval = every[0];
            ImGui.sameLine();
        }
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
        float[] height = {preview.emitterHeight};
        if (ImGui.dragFloat("height", height, 0.05f, 0f, 50f)) {
            preview.emitterHeight = Math.max(0f, height[0]);
            preview.restart();
        }
        ImGui.sameLine();
        ImGui.setNextItemWidth(90);
        float[] wind = {preview.wind};
        if (ImGui.sliderFloat("wind", wind, -5f, 5f, "%.1f")) {
            preview.wind = wind[0];
        }
        if (model.selected().map(e -> e.rate() == 0f).orElse(false) && preview.mode == ParticlePreview.Mode.CONTINUOUS) {
            ImGui.textDisabled("Rate is 0: this effect only spawns in bursts. Use Burst or the burst loop.");
        }
        ImGui.textDisabled(String.format(Locale.ROOT, "live %d   drawn %d   draws %d   dropped %d   gpu %s   heap %s",
                preview.live(), preview.drawn(), preview.drawCalls(), preview.dropped(),
                bytes(preview.gpuBytes()), bytes(preview.heapBytes())));
    }

    private static String bytes(long b) {
        return b >= 1 << 20 ? String.format(Locale.ROOT, "%.1f MB", b / (double) (1 << 20))
                : String.format(Locale.ROOT, "%.0f KB", b / 1024.0);
    }

    // ------------------------------------------------------------------------------------------------
    // Inspector
    // ------------------------------------------------------------------------------------------------

    private ParticleEffect.Builder edit() {
        if (edit == null) {
            edit = current.toBuilder();
        }
        return edit;
    }

    private void change(UnaryOperator<ParticleEffect.Builder> op) {
        op.apply(edit());
    }

    private void renderInspector() {
        Optional<ParticleEffect> sel = model.selected();
        if (sel.isEmpty()) {
            ImGui.textDisabled("No effect selected.");
            return;
        }
        current = sel.get();
        edit = null;
        if (error != null) {
            ImGui.textColored(0.95f, 0.45f, 0.4f, 1f, error);
            ImGui.separator();
        }

        if (!current.name().equals(nameBufferFor)) {
            nameBuffer.set(current.name());
            nameBufferFor = current.name();
        }
        ImGui.inputText("name", nameBuffer);
        if (ImGui.isItemDeactivatedAfterEdit() && !nameBuffer.get().isBlank()) {
            change(b -> b.name(nameBuffer.get().strip()));
        }
        applyPendingTexture();

        if (header("Emission", true)) {
            dragFloat("rate (/s)", current.rate(), 0.5f, 0f, 100_000f, v -> change(b -> b.rate(v)));
            range("lifetime (s)", current.lifetime(), 0.01f, 0.01f, 600f, (a, z) -> change(b -> b.lifetime(a, z)));
            ParticleEffect.EmitterShape shape = enumCombo("shape", current.shape(), ParticleEffect.EmitterShape.values());
            if (shape != current.shape()) {
                change(b -> b.shape(shape, Math.max(current.radius(), 0.5f), Math.max(current.halfX(), 0.5f),
                        Math.max(current.halfY(), 0.5f), Math.max(current.halfZ(), 0.5f)));
            }
            switch (current.shape()) {
                case SPHERE, DISC -> dragFloat("radius", current.radius(), 0.01f, 0f, 100f,
                        v -> change(b -> b.shape(current.shape(), v, current.halfX(), current.halfY(), current.halfZ())));
                case BOX -> {
                    float[] half = {current.halfX(), current.halfY(), current.halfZ()};
                    if (ImGui.dragFloat3("half extents", half, 0.01f, 0f, 100f)) {
                        change(b -> b.box(Math.max(0, half[0]), Math.max(0, half[1]), Math.max(0, half[2])));
                    }
                }
                case POINT -> { }
            }
        }

        if (header("Motion", true)) {
            range("speed", current.speed(), 0.02f, 0f, 500f, (a, z) -> change(b -> b.speed(a, z)));
            float[] dir = {current.dirX(), current.dirY(), current.dirZ()};
            if (ImGui.dragFloat3("direction", dir, 0.01f, -1f, 1f)) {
                change(b -> b.direction(dir[0], dir[1], dir[2]));
            }
            sliderFloat("spread (deg)", current.spread(), 0f, 180f, v -> change(b -> b.spread(v)));
            float[] g = {current.gravityX(), current.gravityY(), current.gravityZ()};
            if (ImGui.dragFloat3("gravity", g, 0.05f, -200f, 200f)) {
                change(b -> b.gravity(g[0], g[1], g[2]));
            }
            dragFloat("drag", current.drag(), 0.01f, 0f, 50f, v -> change(b -> b.drag(v)));
            dragFloat("wind influence", current.windInfluence(), 0.01f, -10f, 10f, v -> change(b -> b.wind(v)));
        }

        if (header("Size", false)) {
            range("start size", current.startSize(), 0.005f, 0f, 100f, (a, z) -> change(b -> b.startSize(a, z)));
            range("end size", current.endSize(), 0.005f, 0f, 100f, (a, z) -> change(b -> b.endSize(a, z)));
            renderSizeKeys();
        }

        if (header("Colour", false)) {
            if (current.colorKeys().isEmpty()) {
                color("start", current.colorStart(), c -> change(b -> b.colors(c, current.colorEnd())));
                color("end", current.colorEnd(), c -> change(b -> b.colors(current.colorStart(), c)));
            }
            renderColorKeys();
            sliderFloat("brightness jitter", current.colorJitter(), 0f, 1f, v -> change(b -> b.colorJitter(v)));
            sliderFloat("fade in", current.fadeIn(), 0f, 1f, v -> change(b -> b.fade(v, current.fadeOut())));
            sliderFloat("fade out", current.fadeOut(), 0f, 1f, v -> change(b -> b.fade(current.fadeIn(), v)));
        }

        if (header("Look", false)) {
            sliderFloat("additive", current.additive(), 0f, 1f, v -> change(b -> b.additive(v)));
            if (ImGui.checkbox("emissive (ignores scene light)", current.emissive())) {
                change(b -> b.emissive(!current.emissive()));
            }
            dragFloat("softness (blocks)", current.softness(), 0.01f, 0f, 20f, v -> change(b -> b.softness(v)));
            ParticleEffect.Orientation o = enumCombo("orientation", current.orientation(), ParticleEffect.Orientation.values());
            if (o != current.orientation()) {
                change(b -> b.orientation(o).stretch(o == ParticleEffect.Orientation.VELOCITY && current.stretch() == 0f
                        ? 0.03f : current.stretch()));
            }
            if (current.orientation() == ParticleEffect.Orientation.VELOCITY) {
                dragFloat("stretch (s)", current.stretch(), 0.001f, 0f, 2f, v -> change(b -> b.stretch(v)));
            }
            range("rotation (deg)", current.rotation(), 1f, -3600f, 3600f, (a, z) -> change(b -> b.rotation(a, z)));
            range("spin (deg/s)", current.spin(), 1f, -7200f, 7200f, (a, z) -> change(b -> b.spin(a, z)));
            ParticleEffect.Sprite sprite = enumCombo("sprite", current.sprite(), ParticleEffect.Sprite.values());
            if (sprite != current.sprite()) {
                if (sprite == ParticleEffect.Sprite.TEXTURE && current.textures().isEmpty()) {
                    browseTexture(); // a texture sprite needs an image before it is valid
                } else {
                    change(b -> b.sprite(sprite));
                }
            }
            if (current.sprite() == ParticleEffect.Sprite.TEXTURE) {
                renderTextures();
            }
        }

        if (header("Collision", false)) {
            if (ImGui.checkbox("collides", current.collides())) {
                change(b -> b.collides(!current.collides()));
            }
            sliderFloat("bounce", current.bounce(), 0f, 1f, v -> change(b -> b.collides(v, current.friction()).collides(current.collides())));
            sliderFloat("friction (kept)", current.friction(), 0f, 1f, v -> change(b -> b.collides(current.bounce(), v).collides(current.collides())));
        }

        if (header("Sub-emitters", false)) {
            renderSubEmitters();
        }

        if (header("Budget", false)) {
            int[] maxLive = {current.maxLive()};
            if (ImGui.dragInt("max live (0 = none)", maxLive, 1f, 0, ParticleEffectLimits.MAX_LIVE)) {
                change(b -> b.maxLive(Math.max(0, maxLive[0])));
            }
            ParticleEffect.Priority p = enumCombo("priority", current.priority(), ParticleEffect.Priority.values());
            if (p != current.priority()) {
                change(b -> b.priority(p));
            }
            dragFloat("LOD distance (0 = off)", current.lodDistance(), 0.5f, 0f, 2000f, v -> change(b -> b.lodDistance(v)));
        }

        commit();
    }

    /** Builds this frame's edits (if any) and applies them; a builder/validation error is shown, not applied. */
    private void commit() {
        if (edit == null) {
            return;
        }
        ParticleEffect.Builder pending = edit;
        edit = null;
        try {
            List<ParticleEditorModel.Swap> swaps = model.apply(pending.build());
            if (preview != null) {
                preview.hotSwap(swaps);
            }
            error = null;
        } catch (IllegalArgumentException e) {
            error = e.getMessage();
        }
    }

    // ---- sprite textures ----

    private void renderTextures() {
        List<String> names = new ArrayList<>(current.textures());
        List<String> missing = preview == null ? List.of() : preview.missingSprites();
        ImGui.textDisabled(names.size() > 1 ? "Textures (flipbook frames, in order)" : "Texture");
        boolean changed = false;
        for (int i = 0; i < names.size(); i++) {
            ImGui.pushID("tex" + i);
            if (ImGui.arrowButton("##up", ImGuiDir.Up) && i > 0) {
                names.add(i - 1, names.remove(i));
                changed = true;
            }
            ImGui.sameLine();
            if (ImGui.arrowButton("##down", ImGuiDir.Down) && i < names.size() - 1) {
                names.add(i + 1, names.remove(i));
                changed = true;
            }
            ImGui.sameLine();
            boolean last = names.size() == 1;
            if (last) ImGui.beginDisabled();
            if (ImGui.smallButton("x")) {
                names.remove(i);
                changed = true;
                if (last) ImGui.endDisabled();
                ImGui.popID();
                break;
            }
            if (last) ImGui.endDisabled();
            ImGui.sameLine();
            String name = names.get(i);
            if (missing.contains(name)) {
                ImGui.textColored(0.95f, 0.45f, 0.4f, 1f, name + "  (not found)");
            } else {
                ImGui.text(name);
            }
            ImGui.popID();
        }
        ImGui.setNextItemWidth(Math.max(80f, ImGui.getContentRegionAvailX() - 140f));
        ImGui.inputText("##texname", textureBuffer);
        ImGui.sameLine();
        String typed = textureBuffer.get().strip();
        if (ImGui.button("Add") && !typed.isEmpty() && names.size() < ParticleEffect.MAX_TEXTURES) {
            names.add(typed);
            textureBuffer.set("");
            changed = true;
        }
        ImGui.sameLine();
        if (ImGui.button("Browse...")) {
            browseTexture();
        }
        if (changed && !names.isEmpty()) {
            change(b -> b.textures(names, current.animateFrames()));
        }
        if (names.size() > 1 && ImGui.checkbox("flipbook over life (off: one per particle)", current.animateFrames())) {
            change(b -> b.animateFrames(!current.animateFrames()));
        }
        if (model.file().isEmpty()) {
            ImGui.textDisabled("Names are image paths next to the document: save it to load relative ones.");
        }
        if (ImGui.smallButton("Reload images")) {
            reloadSprites = true;
        }
    }

    /** Picks a PNG and appends it (the callback may come later; applied on the next inspector frame). */
    private void browseTexture() {
        fileDialogs.showOpenPNGDialog(p -> pendingTexture = ParticleSpriteFiles.nameFor(Path.of(p), documentFolder()));
    }

    private void applyPendingTexture() {
        String name = pendingTexture;
        if (name == null) {
            return;
        }
        pendingTexture = null;
        List<String> names = new ArrayList<>(current.sprite() == ParticleEffect.Sprite.TEXTURE ? current.textures() : List.of());
        if (names.size() < ParticleEffect.MAX_TEXTURES) {
            names.add(name);
            change(b -> b.textures(names, current.animateFrames()));
        }
    }

    private Path documentFolder() {
        return model.file().map(p -> p.toAbsolutePath().getParent()).orElse(null);
    }

    // ---- keys and sub-emitters ----

    private void renderColorKeys() {
        List<ParticleEffect.ColorKey> keys = new ArrayList<>(current.colorKeys());
        ImGui.textDisabled(keys.isEmpty() ? "Gradient: start -> end. Add keys for more stops." : "Gradient keys (t, colour)");
        boolean changed = false;
        for (int i = 0; i < keys.size(); i++) {
            ImGui.pushID("ck" + i);
            float[] t = {keys.get(i).t()};
            ImGui.setNextItemWidth(70);
            if (ImGui.dragFloat("##t", t, 0.005f, 0f, 1f, "%.3f")) {
                keys.set(i, new ParticleEffect.ColorKey(clamp01(t[0]), keys.get(i).rgba()));
                changed = true;
            }
            ImGui.sameLine();
            float[] c = rgba(keys.get(i).rgba());
            if (ImGui.colorEdit4("##c", c, imgui.flag.ImGuiColorEditFlags.AlphaBar)) {
                keys.set(i, new ParticleEffect.ColorKey(keys.get(i).t(), pack(c)));
                changed = true;
            }
            ImGui.sameLine();
            if (ImGui.smallButton("x")) {
                keys.remove(i);
                changed = true;
                ImGui.popID();
                break;
            }
            ImGui.popID();
        }
        if (keys.size() < ParticleEffect.MAX_KEYS && ImGui.smallButton("+ colour key")) {
            if (keys.isEmpty()) {
                keys.add(new ParticleEffect.ColorKey(0f, current.colorStart()));
                keys.add(new ParticleEffect.ColorKey(1f, current.colorEnd()));
            } else {
                keys.add(new ParticleEffect.ColorKey(1f, keys.get(keys.size() - 1).rgba()));
            }
            changed = true;
        }
        if (changed) {
            keys.sort(Comparator.comparingDouble(ParticleEffect.ColorKey::t));
            change(b -> b.colorKeys(keys));
        }
    }

    private void renderSizeKeys() {
        List<ParticleEffect.SizeKey> keys = new ArrayList<>(current.sizeKeys());
        ImGui.textDisabled(keys.isEmpty() ? "Size curve: none (x1). Add keys to shape it." : "Size curve keys (t, x)");
        boolean changed = false;
        for (int i = 0; i < keys.size(); i++) {
            ImGui.pushID("sk" + i);
            float[] v = {keys.get(i).t(), keys.get(i).scale()};
            ImGui.setNextItemWidth(200);
            if (ImGui.dragFloat2("##k", v, 0.005f, 0f, 20f, "%.3f")) {
                keys.set(i, new ParticleEffect.SizeKey(clamp01(v[0]), Math.max(0f, v[1])));
                changed = true;
            }
            ImGui.sameLine();
            if (ImGui.smallButton("x")) {
                keys.remove(i);
                changed = true;
                ImGui.popID();
                break;
            }
            ImGui.popID();
        }
        if (keys.size() < ParticleEffect.MAX_KEYS && ImGui.smallButton("+ size key")) {
            keys.add(new ParticleEffect.SizeKey(keys.isEmpty() ? 0f : 1f, 1f));
            changed = true;
        }
        if (changed) {
            keys.sort(Comparator.comparingDouble(ParticleEffect.SizeKey::t));
            change(b -> b.sizeKeys(keys));
        }
    }

    private void renderSubEmitters() {
        List<ParticleEffect.SubEmitter> subs = new ArrayList<>(current.subEmitters());
        List<String> candidates = model.subEmitterCandidates();
        boolean changed = false;
        for (int i = 0; i < subs.size(); i++) {
            ParticleEffect.SubEmitter s = subs.get(i);
            ImGui.pushID("se" + i);
            List<String> options = new ArrayList<>(candidates);
            if (!options.contains(s.effect().name())) {
                options.add(0, s.effect().name());
            }
            ImInt pick = new ImInt(options.indexOf(s.effect().name()));
            ImGui.setNextItemWidth(140);
            if (ImGui.combo("##effect", pick, options.toArray(String[]::new))) {
                ParticleEffect target = model.library().get(options.get(pick.get()));
                subs.set(i, new ParticleEffect.SubEmitter(target, s.trigger(), s.count(), s.rate(), s.inheritVelocity()));
                changed = true;
            }
            ImGui.sameLine();
            ImInt trig = new ImInt(s.trigger().ordinal());
            ImGui.setNextItemWidth(95);
            if (ImGui.combo("##trigger", trig, names(ParticleEffect.Trigger.values()))) {
                ParticleEffect.Trigger t = ParticleEffect.Trigger.values()[trig.get()];
                subs.set(i, new ParticleEffect.SubEmitter(s.effect(), t, Math.max(1, s.count()),
                        t == ParticleEffect.Trigger.TRAIL && s.rate() <= 0f ? 10f : s.rate(), s.inheritVelocity()));
                changed = true;
            }
            ImGui.sameLine();
            if (ImGui.smallButton("x")) {
                subs.remove(i);
                changed = true;
                ImGui.popID();
                break;
            }
            s = subs.get(i);
            if (s.trigger() == ParticleEffect.Trigger.TRAIL) {
                float[] rate = {s.rate()};
                ImGui.setNextItemWidth(120);
                if (ImGui.dragFloat("per second", rate, 0.5f, 0.1f, 10_000f)) {
                    subs.set(i, new ParticleEffect.SubEmitter(s.effect(), s.trigger(), s.count(), Math.max(0.1f, rate[0]), s.inheritVelocity()));
                    changed = true;
                }
            } else {
                int[] count = {s.count()};
                ImGui.setNextItemWidth(120);
                if (ImGui.dragInt("count", count, 0.2f, 1, 10_000)) {
                    subs.set(i, new ParticleEffect.SubEmitter(s.effect(), s.trigger(), Math.max(1, count[0]), s.rate(), s.inheritVelocity()));
                    changed = true;
                }
            }
            ImGui.sameLine();
            float[] inherit = {s.inheritVelocity()};
            ImGui.setNextItemWidth(80);
            if (ImGui.dragFloat("inherit", inherit, 0.01f, -2f, 2f)) {
                s = subs.get(i);
                subs.set(i, new ParticleEffect.SubEmitter(s.effect(), s.trigger(), s.count(), s.rate(), inherit[0]));
                changed = true;
            }
            ImGui.popID();
        }
        if (subs.size() < ParticleEffect.MAX_SUB_EMITTERS && !candidates.isEmpty() && ImGui.smallButton("+ sub-emitter")) {
            subs.add(ParticleEffect.SubEmitter.on(ParticleEffect.Trigger.DEATH, model.library().get(candidates.get(0)), 1));
            changed = true;
        }
        if (changed) {
            change(b -> b.subEmitters(subs));
        }
    }

    // ---- widget helpers ----

    private static boolean header(String label, boolean open) {
        return ImGui.collapsingHeader(label, open ? ImGuiTreeNodeFlags.DefaultOpen : 0);
    }

    private interface FloatSink { void accept(float v); }
    private interface RangeSink { void accept(float min, float max); }
    private interface ColorSink { void accept(int rgba); }

    private static void dragFloat(String label, float value, float speed, float min, float max, FloatSink sink) {
        float[] v = {value};
        if (ImGui.dragFloat(label, v, speed, min, max)) {
            sink.accept(Math.max(min, Math.min(max, v[0])));
        }
    }

    private static void sliderFloat(String label, float value, float min, float max, FloatSink sink) {
        float[] v = {value};
        if (ImGui.sliderFloat(label, v, min, max)) {
            sink.accept(Math.max(min, Math.min(max, v[0])));
        }
    }

    /** A min/max pair; dragging one past the other carries the other with it so the range stays valid. */
    private static void range(String label, ParticleEffect.Range r, float speed, float min, float max, RangeSink sink) {
        float[] v = {r.min(), r.max()};
        if (ImGui.dragFloat2(label, v, speed, min, max)) {
            float a = Math.max(min, Math.min(max, v[0]));
            float b = Math.max(min, Math.min(max, v[1]));
            if (a != r.min() && a > b) b = a;
            if (b != r.max() && b < a) a = b;
            sink.accept(a, b);
        }
    }

    private static void color(String label, int rgba, ColorSink sink) {
        float[] c = rgba(rgba);
        if (ImGui.colorEdit4(label, c, imgui.flag.ImGuiColorEditFlags.AlphaBar)) {
            sink.accept(pack(c));
        }
    }

    private static <E extends Enum<E>> E enumCombo(String label, E value, E[] values) {
        ImInt i = new ImInt(value.ordinal());
        if (ImGui.combo(label, i, names(values))) {
            return values[i.get()];
        }
        return value;
    }

    private static String[] names(Enum<?>[] values) {
        String[] out = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = values[i].name().toLowerCase(Locale.ROOT).replace('_', ' ');
        }
        return out;
    }

    static float[] rgba(int c) {
        return new float[]{(c >>> 24) / 255f, ((c >>> 16) & 0xFF) / 255f, ((c >>> 8) & 0xFF) / 255f, (c & 0xFF) / 255f};
    }

    static int pack(float[] c) {
        return (channel(c[0]) << 24) | (channel(c[1]) << 16) | (channel(c[2]) << 8) | channel(c[3]);
    }

    private static int channel(float v) {
        return Math.round(Math.max(0f, Math.min(1f, v)) * 255f);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    /** Releases the preview's GL resources (render thread, at shutdown). */
    public void cleanup() {
        if (preview != null) {
            preview.close();
            preview = null;
        }
    }

    /** UI limits that are not effect validation rules. */
    private static final class ParticleEffectLimits {
        static final int MAX_LIVE = 1_000_000;
    }
}
