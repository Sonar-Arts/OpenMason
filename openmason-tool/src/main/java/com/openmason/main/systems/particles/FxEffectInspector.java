package com.openmason.main.systems.particles;

import com.openmason.engine.rendering.particles.ParticleEffect;
import imgui.ImGui;
import imgui.flag.ImGuiDir;
import imgui.type.ImInt;
import imgui.type.ImString;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.UnaryOperator;

import static com.openmason.main.systems.particles.ParticleEditorWidgets.clamp01;
import static com.openmason.main.systems.particles.ParticleEditorWidgets.color;
import static com.openmason.main.systems.particles.ParticleEditorWidgets.dragFloat;
import static com.openmason.main.systems.particles.ParticleEditorWidgets.enumCombo;
import static com.openmason.main.systems.particles.ParticleEditorWidgets.pack;
import static com.openmason.main.systems.particles.ParticleEditorWidgets.range;
import static com.openmason.main.systems.particles.ParticleEditorWidgets.rgba;
import static com.openmason.main.systems.particles.ParticleEditorWidgets.sliderFloat;

/**
 * The fields of an effect's built-in modules (OM #104): each built-in module of the emitter stack lowers from some
 * of the effect's fields, and this draws exactly those under the module - {@code initialize} the spawn shape and
 * launch, {@code lifetime} colour/size over life, {@code forces} gravity/drag/wind, {@code move} collision,
 * {@code spin} spin, {@code rate} the spawn rate - plus the Events (sub-emitters) and Render sections.
 *
 * <p>One frame's edits collect in a builder ({@link #begin}/{@link #end}) and land as one document edit keyed by the
 * effect, so a drag is one undo step.
 */
final class FxEffectInspector {

    private final FxEditorContext ctx;
    private ParticleEffect current;
    private ParticleEffect.Builder edit;
    private final ImString textureBuffer = new ImString(256);
    private final ImString nameBuffer = new ImString(64);
    private String nameBufferFor;
    /** A sprite picked in a file dialog (may arrive later), embedded and appended on the next frame. */
    volatile java.nio.file.Path pendingSpriteFile;
    private final java.util.function.Consumer<java.util.function.Consumer<String>> pngPicker;
    private final java.util.function.Predicate<String> missing;

    /**
     * @param pngPicker opens a file dialog for an image (null in tests)
     * @param missing   whether the preview could not load a texture name
     */
    FxEffectInspector(FxEditorContext ctx, java.util.function.Consumer<java.util.function.Consumer<String>> pngPicker,
                      java.util.function.Predicate<String> missing) {
        this.ctx = ctx;
        this.pngPicker = pngPicker;
        this.missing = missing;
    }

    void begin(ParticleEffect effect) {
        current = effect;
        edit = null;
    }

    /** Applies this frame's edits (if any) as one edit of the effect. */
    void end() {
        if (edit == null) {
            return;
        }
        ParticleEffect.Builder pending = edit;
        edit = null;
        String name = current.name();
        try {
            ParticleEffect next = pending.build();
            ctx.apply(() -> ctx.doc().putEffect("effect:" + name, name, next));
        } catch (IllegalArgumentException e) {
            ctx.setError(e.getMessage());
        }
    }

    private void change(UnaryOperator<ParticleEffect.Builder> op) {
        if (edit == null) {
            edit = current.toBuilder();
        }
        op.apply(edit);
    }

    // ---- identity ----

    void renderName() {
        if (!current.name().equals(nameBufferFor)) {
            nameBuffer.set(current.name());
            nameBufferFor = current.name();
        }
        ImGui.setNextItemWidth(200);
        ImGui.inputText("effect name", nameBuffer);
        if (ImGui.isItemDeactivatedAfterEdit() && !nameBuffer.get().isBlank()
                && !nameBuffer.get().strip().equals(current.name())) {
            change(b -> b.name(nameBuffer.get().strip()));
        }
    }

    // ---- built-in modules ----

    /** The fields of built-in module {@code id}; false when it has none to show. */
    boolean renderBuiltin(String id) {
        switch (id) {
            case "rate" -> dragFloat("rate (/s)", current.rate(), 0.5f, 0f, 100_000f, v -> change(b -> b.rate(v)));
            case "initialize" -> renderInitialize();
            case "lifetime" -> renderLifetime();
            case "forces" -> renderForces();
            case "move" -> renderMove();
            case "spin" -> range("spin (deg/s)", current.spin(), 1f, -7200f, 7200f, (a, z) -> change(b -> b.spin(a, z)));
            case "trail" -> ImGui.textDisabled("Trail sub-emitters are listed under Events.");
            default -> {
                return false;
            }
        }
        return true;
    }

    private void renderInitialize() {
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
        range("speed", current.speed(), 0.02f, 0f, 500f, (a, z) -> change(b -> b.speed(a, z)));
        float[] dir = {current.dirX(), current.dirY(), current.dirZ()};
        if (ImGui.dragFloat3("direction", dir, 0.01f, -1f, 1f)) {
            change(b -> b.direction(dir[0], dir[1], dir[2]));
        }
        sliderFloat("spread (deg)", current.spread(), 0f, 180f, v -> change(b -> b.spread(v)));
        range("start size", current.startSize(), 0.005f, 0f, 100f, (a, z) -> change(b -> b.startSize(a, z)));
        range("end size", current.endSize(), 0.005f, 0f, 100f, (a, z) -> change(b -> b.endSize(a, z)));
        range("rotation (deg)", current.rotation(), 1f, -3600f, 3600f, (a, z) -> change(b -> b.rotation(a, z)));
        sliderFloat("brightness jitter", current.colorJitter(), 0f, 1f, v -> change(b -> b.colorJitter(v)));
    }

    private void renderLifetime() {
        if (current.colorKeys().isEmpty()) {
            color("start colour", current.colorStart(), c -> change(b -> b.colors(c, current.colorEnd())));
            color("end colour", current.colorEnd(), c -> change(b -> b.colors(current.colorStart(), c)));
        }
        renderColorKeys();
        sliderFloat("fade in", current.fadeIn(), 0f, 1f, v -> change(b -> b.fade(v, current.fadeOut())));
        sliderFloat("fade out", current.fadeOut(), 0f, 1f, v -> change(b -> b.fade(current.fadeIn(), v)));
        renderSizeKeys();
    }

    private void renderForces() {
        float[] g = {current.gravityX(), current.gravityY(), current.gravityZ()};
        if (ImGui.dragFloat3("gravity", g, 0.05f, -200f, 200f)) {
            change(b -> b.gravity(g[0], g[1], g[2]));
        }
        dragFloat("drag", current.drag(), 0.01f, 0f, 50f, v -> change(b -> b.drag(v)));
        dragFloat("wind influence", current.windInfluence(), 0.01f, -10f, 10f, v -> change(b -> b.wind(v)));
    }

    private void renderMove() {
        if (ImGui.checkbox("collides with the world", current.collides())) {
            change(b -> b.collides(!current.collides()));
        }
        if (current.collides()) {
            sliderFloat("bounce", current.bounce(), 0f, 1f,
                    v -> change(b -> b.collides(v, current.friction()).collides(current.collides())));
            sliderFloat("friction (kept)", current.friction(), 0f, 1f,
                    v -> change(b -> b.collides(current.bounce(), v).collides(current.collides())));
        }
    }

    // ---- Render stage ----

    void renderRender(List<String> embeddedSprites) {
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
        ParticleEffect.Sprite sprite = enumCombo("sprite", current.sprite(), ParticleEffect.Sprite.values());
        if (sprite != current.sprite()) {
            if (sprite == ParticleEffect.Sprite.TEXTURE && current.textures().isEmpty()) {
                if (!embeddedSprites.isEmpty()) {
                    change(b -> b.textures(List.of(embeddedSprites.get(0)), false));
                } else {
                    ctx.setNote("A texture sprite needs an image: embed one in FX Sprites, or Browse... below.");
                    browse();
                }
            } else {
                change(b -> b.sprite(sprite));
            }
        }
        if (current.sprite() == ParticleEffect.Sprite.TEXTURE) {
            renderTextures(embeddedSprites);
        }
        ImGui.separator();
        int[] maxLive = {current.maxLive()};
        if (ImGui.dragInt("max live (0 = none)", maxLive, 1f, 0, 1_000_000)) {
            change(b -> b.maxLive(Math.max(0, maxLive[0])));
        }
        ParticleEffect.Priority p = enumCombo("priority", current.priority(), ParticleEffect.Priority.values());
        if (p != current.priority()) {
            change(b -> b.priority(p));
        }
        dragFloat("LOD distance (0 = off)", current.lodDistance(), 0.5f, 0f, 2000f, v -> change(b -> b.lodDistance(v)));
        ParticleEffect.Simulation sim = enumCombo("simulation", current.simulation(),
                ParticleEffect.Simulation.values());
        if (sim != current.simulation()) {
            change(b -> b.simulation(sim));
        }
        if (current.simulation() == ParticleEffect.Simulation.GPU) {
            ImGui.textDisabled("GPU: compute shaders (OM #100) for dense effects; sub-emitter targets run there too.");
            ImGui.textDisabled("Events arrive a few frames late; runs on the CPU where GL 4.3 is missing.");
        }
    }

    /** The texture list: flipbook frames in order. Names are embedded sprites or image files next to the document. */
    private void renderTextures(List<String> embedded) {
        List<String> names = new ArrayList<>(current.textures());
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
            boolean remove = ImGui.smallButton("x");
            if (last) ImGui.endDisabled();
            ImGui.sameLine();
            String name = names.get(i);
            boolean isEmbedded = embedded.contains(name);
            if (!isEmbedded && missing.test(name)) {
                ImGui.textColored(0.95f, 0.45f, 0.4f, 1f, name + "  (not found)");
            } else {
                ImGui.text(name + (isEmbedded ? "" : "  (file)"));
            }
            ImGui.popID();
            if (remove) {
                names.remove(i);
                changed = true;
                break;
            }
        }
        if (names.size() < ParticleEffect.MAX_TEXTURES) {
            List<String> options = new ArrayList<>(embedded);
            options.removeAll(names);
            if (!options.isEmpty()) {
                ImInt pick = new ImInt(-1);
                ImGui.setNextItemWidth(160);
                if (ImGui.combo("##addsprite", pick, options.toArray(String[]::new)) && pick.get() >= 0) {
                    names.add(options.get(pick.get()));
                    changed = true;
                }
                ImGui.sameLine();
                ImGui.textDisabled("add embedded");
            }
            ImGui.setNextItemWidth(Math.max(80f, ImGui.getContentRegionAvailX() - 140f));
            ImGui.inputTextWithHint("##texname", "file name next to the document", textureBuffer);
            ImGui.sameLine();
            String typed = textureBuffer.get().strip();
            if (ImGui.button("Add") && !typed.isEmpty()) {
                names.add(typed);
                textureBuffer.set("");
                changed = true;
            }
            ImGui.sameLine();
            if (ImGui.button("Browse...")) {
                browse();
            }
            ParticleEditorWidgets.hint("Embeds a PNG or .omt in the document and adds it as a frame");
        }
        if (changed && !names.isEmpty()) {
            change(b -> b.textures(names, current.animateFrames()));
        }
        if (names.size() > 1 && ImGui.checkbox("flipbook over life (off: one frame per particle)", current.animateFrames())) {
            change(b -> b.animateFrames(!current.animateFrames()));
        }
    }

    private void browse() {
        if (pngPicker != null) {
            pngPicker.accept(p -> pendingSpriteFile = java.nio.file.Path.of(p));
        }
    }

    /** Embeds a sprite picked last frame and appends it to {@code effect}'s frames. */
    void applyPendingSprite(ParticleEffect effect) {
        java.nio.file.Path file = pendingSpriteFile;
        if (file == null) {
            return;
        }
        pendingSpriteFile = null;
        String name = FxEditorPanels.embedFile(ctx, file);
        if (name == null) {
            return;
        }
        ParticleEffect latest = ctx.doc().effect(effect.name());
        if (latest == null) {
            return;
        }
        List<String> names = new ArrayList<>(latest.sprite() == ParticleEffect.Sprite.TEXTURE
                ? latest.textures() : List.of());
        if (names.size() < ParticleEffect.MAX_TEXTURES && !names.contains(name)) {
            names.add(name);
            ParticleEffect next = latest.toBuilder().textures(names, latest.animateFrames()).build();
            ctx.apply(() -> ctx.doc().putEffect(null, latest.name(), next));
        }
    }

    // ---- Events stage ----

    void renderSubEmitters(List<String> candidates) {
        List<ParticleEffect.SubEmitter> subs = new ArrayList<>(current.subEmitters());
        boolean changed = false;
        if (subs.isEmpty()) {
            ImGui.textDisabled("No particle sub-emitters.");
        }
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
                ParticleEffect target = ctx.doc().effect(options.get(pick.get()));
                if (target != null) {
                    subs.set(i, new ParticleEffect.SubEmitter(target, s.trigger(), s.count(), s.rate(), s.inheritVelocity()));
                    changed = true;
                }
            }
            ImGui.sameLine();
            ImInt trig = new ImInt(s.trigger().ordinal());
            ImGui.setNextItemWidth(95);
            if (ImGui.combo("##trigger", trig, ParticleEditorWidgets.names(ParticleEffect.Trigger.values()))) {
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
                ParticleEffect.SubEmitter cur = subs.get(i);
                subs.set(i, new ParticleEffect.SubEmitter(cur.effect(), cur.trigger(), cur.count(), cur.rate(), inherit[0]));
                changed = true;
            }
            ImGui.popID();
        }
        if (subs.size() < ParticleEffect.MAX_SUB_EMITTERS && !candidates.isEmpty() && ImGui.smallButton("+ sub-emitter")) {
            ParticleEffect target = ctx.doc().effect(candidates.get(0));
            if (target != null) {
                subs.add(ParticleEffect.SubEmitter.on(ParticleEffect.Trigger.DEATH, target, 1));
                changed = true;
            }
        }
        if (changed) {
            change(b -> b.subEmitters(subs));
        }
    }

    // ---- keys ----

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
}
