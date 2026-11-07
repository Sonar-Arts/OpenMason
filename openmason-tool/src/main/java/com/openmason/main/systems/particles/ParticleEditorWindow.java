package com.openmason.main.systems.particles;

import com.openmason.engine.rendering.particles.ParticleEffect;
import com.openmason.engine.rendering.particles.ParticleLibrary;
import com.openmason.main.systems.io.BuiltinFileTypes;
import com.openmason.main.systems.menus.dialogs.FileDialogService;
import com.openmason.main.systems.services.StatusService;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiTreeNodeFlags;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
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
 * Tools -> Particle Editor: author {@code .omfx} particle libraries ({@link ParticleLibrary} JSON) with a live
 * preview drawn by the engine particle renderer.
 *
 * <p>Left: the library's effects (new / duplicate / delete). Centre: the preview (drag to orbit, wheel to
 * zoom; continuous or looping-burst playback, floor + soft particles toggles, budget stats). Right: every
 * {@link ParticleEffect} field. Edits rebuild the immutable effect through its builder, cascade to effects
 * that spawn it ({@link ParticleEditorModel#apply}) and hot-swap into the running preview, so dragging a
 * slider updates particles already in flight. An invalid value (e.g. a range whose max is below its min) is
 * reported and not applied.
 *
 * <p>Strings are ASCII only: the editor's ImGui font has no typographic punctuation.
 */
public final class ParticleEditorWindow {

    private static final Logger logger = LoggerFactory.getLogger(ParticleEditorWindow.class);
    private static final String TITLE = "Particle Editor";

    private final ParticleEditorModel model = new ParticleEditorModel();
    private final FileDialogService fileDialogs;
    private final StatusService status;
    private final ImBoolean open = new ImBoolean(false);
    private ParticlePreview preview;

    private final ImString nameBuffer = new ImString(64);
    private String nameBufferFor;
    private String error;
    private Runnable pendingDiscard;
    private boolean discardRequested;

    /** The selected effect at the start of the inspector pass, and the builder collecting this frame's edits. */
    private ParticleEffect current;
    private ParticleEffect.Builder edit;

    public ParticleEditorWindow(FileDialogService fileDialogs, StatusService status) {
        this.fileDialogs = fileDialogs;
        this.status = status;
    }

    public void show() {
        open.set(true);
    }

    public boolean isOpen() {
        return open.get();
    }

    /** Opens {@code path} (file opener / Open...). */
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
    // Frame
    // ------------------------------------------------------------------------------------------------

    public void render() {
        if (!open.get()) {
            return;
        }
        ImGui.setNextWindowSize(1180, 720, ImGuiCond.FirstUseEver);
        String title = TITLE + model.file().map(p -> " - " + p.getFileName()).orElse("") + (model.isDirty() ? " *" : "")
                + "###particleEditor";
        if (ImGui.begin(title, open, ImGuiWindowFlags.MenuBar)) {
            renderMenuBar();
            float listWidth = 190f, inspectorWidth = 360f;
            float h = ImGui.getContentRegionAvailY();
            ImGui.beginChild("##effects", listWidth, h, true);
            renderEffectList();
            ImGui.endChild();
            ImGui.sameLine();
            float previewWidth = Math.max(160f, ImGui.getContentRegionAvailX() - inspectorWidth - 8f);
            ImGui.beginChild("##preview", previewWidth, h, false);
            renderPreview(previewWidth);
            ImGui.endChild();
            ImGui.sameLine();
            ImGui.beginChild("##inspector", 0, h, true);
            renderInspector();
            ImGui.endChild();
            renderDiscardPopup();
        }
        ImGui.end();
    }

    private void renderMenuBar() {
        if (!ImGui.beginMenuBar()) {
            return;
        }
        if (ImGui.beginMenu("File")) {
            if (ImGui.menuItem("New (built-in presets)")) {
                guardUnsaved(() -> {
                    model.newDocument();
                    restartPreview();
                });
            }
            if (ImGui.menuItem("Open...")) {
                fileDialogs.showOpenDialog(FileDialogService.StartIn.TYPE_FOLDER, p -> openFile(Path.of(p)),
                        BuiltinFileTypes.OMFX);
            }
            if (ImGui.menuItem("Save", "", false, true)) {
                save(model.file().orElse(null));
            }
            if (ImGui.menuItem("Save As...")) {
                save(null);
            }
            ImGui.endMenu();
        }
        if (ImGui.beginMenu("Edit")) {
            if (ImGui.menuItem("Copy effect as JSON", "", false, model.selected().isPresent())) {
                model.selected().ifPresent(e -> {
                    ImGui.setClipboardText(ParticleLibrary.toJsonText(e));
                    status.updateStatus("Copied '" + e.name() + "' as JSON");
                });
            }
            ImGui.endMenu();
        }
        ImGui.endMenuBar();
    }

    private void save(Path path) {
        if (path == null) {
            String name = model.file().map(p -> p.getFileName().toString()).orElse("particles.omfx");
            fileDialogs.showSaveDialog(BuiltinFileTypes.OMFX, name, null, p -> save(Path.of(p)));
            return;
        }
        try {
            model.save(path);
            status.updateStatus("Saved particle library " + path.getFileName());
        } catch (Exception e) {
            logger.warn("Could not save particle library {}", path, e);
            error = "Could not save: " + e.getMessage();
        }
    }

    private void guardUnsaved(Runnable action) {
        if (model.isDirty()) {
            pendingDiscard = action;
            discardRequested = true; // opened at window level: popups resolve against the window's ID stack
        } else {
            action.run();
        }
    }

    private void renderDiscardPopup() {
        if (discardRequested) {
            ImGui.openPopup("Discard changes?##particles");
            discardRequested = false;
        }
        if (ImGui.beginPopupModal("Discard changes?##particles", ImGuiWindowFlags.AlwaysAutoResize)) {
            ImGui.text("The particle library has unsaved changes.");
            if (ImGui.button("Discard")) {
                Runnable action = pendingDiscard;
                pendingDiscard = null;
                ImGui.closeCurrentPopup();
                if (action != null) {
                    action.run();
                }
            }
            ImGui.sameLine();
            if (ImGui.button("Cancel")) {
                pendingDiscard = null;
                ImGui.closeCurrentPopup();
            }
            ImGui.endPopup();
        }
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

        float controlsHeight = ImGui.getFrameHeightWithSpacing() * 3 + 6f;
        float imageHeight = Math.max(120f, ImGui.getContentRegionAvailY() - controlsHeight);
        int texture = preview.render((int) width, (int) imageHeight, ImGui.getIO().getDeltaTime());
        ImGui.image(texture, width, imageHeight, 0, 1, 1, 0); // GL rows are bottom-up
        if (ImGui.isItemHovered()) {
            if (ImGui.isMouseDragging(0)) {
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
                change(b -> b.sprite(sprite));
            }
            if (current.sprite() == ParticleEffect.Sprite.ATLAS) {
                int[] frames = {current.frameStart(), current.frameCount()};
                if (ImGui.dragInt2("first frame, count", frames, 0.1f, 0, ParticleEffect.MAX_ATLAS_FRAMES - 1)) {
                    change(b -> b.atlas(Math.max(0, frames[0]), Math.max(1, frames[1]), current.animateFrames()));
                }
                if (ImGui.checkbox("animate over life", current.animateFrames())) {
                    change(b -> b.atlas(current.frameStart(), current.frameCount(), !current.animateFrames()));
                }
                ImGui.textDisabled("The preview has no atlas: frames show white.");
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
