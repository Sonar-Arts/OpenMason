package com.openmason.main.systems.particles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.openmason.engine.rendering.particles.FxBinding;
import com.openmason.engine.rendering.particles.FxEmitter;
import com.openmason.engine.rendering.particles.FxEventHandler;
import com.openmason.engine.rendering.particles.FxParameter;
import com.openmason.engine.rendering.particles.FxSoundCue;
import com.openmason.engine.rendering.particles.FxSystem;
import com.openmason.engine.rendering.particles.ParticleEffect;
import com.openmason.engine.rendering.particles.ParticleImage;
import com.openmason.engine.rendering.particles.ParticleLibrary;
import com.openmason.engine.rendering.particles.graph.FxAttribute;
import com.openmason.engine.rendering.particles.graph.FxModule;
import com.openmason.engine.rendering.particles.graph.FxModules;
import com.openmason.engine.rendering.particles.graph.FxStageKind;
import com.openmason.engine.rendering.particles.graph.FxType;
import com.openmason.engine.rendering.particles.omfx.OmfxCodec;
import com.openmason.engine.rendering.particles.omfx.OmfxDocument;
import com.openmason.engine.rendering.particles.omfx.OmfxSprite;
import com.openmason.main.systems.particles.fx.FxEditorDocument;
import com.openmason.main.systems.particles.fx.FxGraphDraft;
import com.openmason.main.systems.particles.fx.FxSystemViews;
import com.openmason.main.systems.particles.fx.FxTimeline;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiDir;
import imgui.flag.ImGuiTableFlags;
import imgui.flag.ImGuiTreeNodeFlags;
import imgui.type.ImInt;
import imgui.type.ImString;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import static com.openmason.main.systems.particles.ParticleEditorWidgets.hint;
import static org.lwjgl.opengl.GL33C.*;

/**
 * The FX editor's docked panels other than the preview and the graph (OM #104): the system view (emitters with
 * enable/solo and a timeline track each), the emitter stack (Niagara-style stage sections listing modules; built-in
 * modules show their fields), exposed parameters with live preview sliders plus bindings, events, light and sound
 * cues, and the document's embedded sprites. Every edit goes through {@link FxEditorContext#apply}.
 */
final class FxEditorPanels {

    /** Stages whose module stacks the CPU backend runs (the others are fixed sections). */
    static final FxStageKind[] STACK_STAGES = {FxStageKind.EMITTER_UPDATE, FxStageKind.PARTICLE_SPAWN,
            FxStageKind.PARTICLE_UPDATE};
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final FxEditorContext ctx;
    private final FxEffectInspector inspector;
    private final Consumer<FxEditorDocument.ModuleRef> openGraph;
    private final Consumer<Consumer<String>> imagePicker;
    private FxPreview preview;

    private final ImString renameBuffer = new ImString(64);
    private String renaming;
    private final ImString newParamName = new ImString(64);
    private final ImInt newParamType = new ImInt(0);
    private final ImString textBuffer = new ImString(256);
    private String textFor;
    private final ImString newAttrName = new ImString(64);
    private final ImInt newAttrType = new ImInt(0);
    /** Work to run after the inspector committed this frame (stack edits read the latest effect). */
    private final List<Runnable> deferred = new ArrayList<>();
    private final Map<String, int[]> thumbnails = new HashMap<>();
    private final Map<String, OmfxSprite> thumbnailSource = new HashMap<>();
    /** An image picked in the sprites panel's file dialog. */
    volatile Path pendingSpriteFile;

    FxEditorPanels(FxEditorContext ctx, Consumer<Consumer<String>> imagePicker,
                   Consumer<FxEditorDocument.ModuleRef> openGraph) {
        this.ctx = ctx;
        this.imagePicker = imagePicker;
        this.openGraph = openGraph;
        this.inspector = new FxEffectInspector(ctx, imagePicker,
                name -> preview != null && preview.missingSprites().contains(name));
    }

    void setPreview(FxPreview preview) {
        this.preview = preview;
    }

    // ------------------------------------------------------------------------------------------------
    // System: emitters + timeline
    // ------------------------------------------------------------------------------------------------

    void renderSystem() {
        FxEditorDocument doc = ctx.doc();
        FxSystem system = doc.system();
        FxEmitter sel = ctx.selected();

        ImGui.setNextItemWidth(130);
        if (ImGui.beginCombo("##addEmitter", "+ Emitter")) {
            for (ParticleEffect e : ParticleLibrary.Presets.ALL) {
                if (ImGui.selectable(e.name())) {
                    String made = ctx.apply(() -> doc.addEmitter(e.name()));
                    if (made != null) {
                        ctx.select(made);
                    }
                }
            }
            ImGui.endCombo();
        }
        hint("Add an emitter playing a copy of a preset effect");
        ImGui.sameLine();
        if (ImGui.button("Duplicate") && sel != null) {
            String made = ctx.apply(() -> doc.duplicateEmitter(sel.name()));
            if (made != null) {
                ctx.select(made);
            }
        }
        ImGui.sameLine();
        if (ImGui.button("Remove") && sel != null) {
            Integer dropped = ctx.apply(() -> doc.removeEmitter(sel.name()));
            if (dropped != null && dropped > 0) {
                ctx.setNote("Removed '" + sel.name() + "' and " + dropped + " binding/event/cue entries that named it.");
            }
        }
        ImGui.sameLine();
        if (ImGui.arrowButton("##emUp", ImGuiDir.Up) && sel != null) {
            ctx.apply(() -> doc.moveEmitter(sel.name(), -1));
        }
        ImGui.sameLine();
        if (ImGui.arrowButton("##emDown", ImGuiDir.Down) && sel != null) {
            ctx.apply(() -> doc.moveEmitter(sel.name(), 1));
        }

        double length = FxTimeline.length(system);
        double horizon = Double.isInfinite(length) ? 0.0 : length;
        for (FxEmitter e : system.emitters()) {
            horizon = Math.max(horizon, e.delay() + (e.duration() == FxEmitter.FOREVER ? 3.0 : e.duration()));
        }
        horizon = Math.max(2.0, Math.min(60.0, Math.ceil(horizon * 1.15)));
        double now = preview == null ? 0.0 : preview.time();

        int flags = ImGuiTableFlags.RowBg | ImGuiTableFlags.BordersInnerV | ImGuiTableFlags.SizingFixedFit;
        if (ImGui.beginTable("##emitters", 4, flags)) {
            ImGui.tableSetupColumn("on");
            ImGui.tableSetupColumn("solo");
            ImGui.tableSetupColumn("emitter", imgui.flag.ImGuiTableColumnFlags.WidthFixed, 150f);
            ImGui.tableSetupColumn("timeline", imgui.flag.ImGuiTableColumnFlags.WidthStretch);
            ImGui.tableHeadersRow();
            drawRuler(horizon);
            for (FxEmitter e : system.emitters()) {
                ImGui.tableNextRow();
                ImGui.pushID("em_" + e.name());
                ImGui.tableSetColumnIndex(0);
                if (ImGui.checkbox("##on", !ctx.isMuted(e.name()))) {
                    ctx.setMuted(e.name(), !ctx.isMuted(e.name()));
                }
                hint("Enabled in the preview (not saved)");
                ImGui.tableSetColumnIndex(1);
                if (ImGui.checkbox("##solo", ctx.isSoloed(e.name()))) {
                    ctx.setSoloed(e.name(), !ctx.isSoloed(e.name()));
                }
                hint("Solo: the preview plays only soloed emitters (events still spawn into the others)");
                ImGui.tableSetColumnIndex(2);
                renderEmitterName(e, sel);
                ImGui.tableSetColumnIndex(3);
                drawTrack(e, horizon, now);
                ImGui.popID();
            }
            ImGui.endTable();
        }
        ImGui.textDisabled(String.format(Locale.ROOT, "t = %.2f s   length %s   %d emitter(s)", now,
                Double.isInfinite(length) ? "endless" : String.format(Locale.ROOT, "%.2f s", length),
                system.emitters().size()));
    }

    private void renderEmitterName(FxEmitter e, FxEmitter sel) {
        if (e.name().equals(renaming)) {
            ImGui.setNextItemWidth(-1);
            if (ImGui.inputText("##rename", renameBuffer, imgui.flag.ImGuiInputTextFlags.EnterReturnsTrue)
                    || ImGui.isItemDeactivated()) {
                String to = renameBuffer.get().strip();
                String from = e.name();
                renaming = null;
                if (!to.isEmpty() && !to.equals(from) && ctx.apply(() -> ctx.doc().renameEmitter(from, to)) != null) {
                    ctx.renamed(from, to);
                }
            }
            if (ImGui.isWindowAppearing() || renameBuffer.get().equals(e.name())) {
                ImGui.setKeyboardFocusHere(-1);
            }
            return;
        }
        String label = e.name() + (e.effect().name().equals(e.name()) ? "" : "  (" + e.effect().name() + ")");
        if (ImGui.selectable(label, sel != null && sel.name().equals(e.name()))) {
            ctx.select(e.name());
        }
        if (ImGui.isItemHovered() && ImGui.isMouseDoubleClicked(0)) {
            renaming = e.name();
            renameBuffer.set(e.name());
        }
        hint("Double-click to rename");
    }

    private float trackX0, trackX1;

    private void drawRuler(double horizon) {
        ImGui.tableNextRow();
        ImGui.tableSetColumnIndex(3);
        float x0 = ImGui.getCursorScreenPosX();
        float w = Math.max(40f, ImGui.getContentRegionAvailX());
        float y = ImGui.getCursorScreenPosY();
        trackX0 = x0;
        trackX1 = x0 + w;
        ImDrawList dl = ImGui.getWindowDrawList();
        int tick = ImGui.getColorU32(0.6f, 0.6f, 0.65f, 0.8f);
        double step = horizon <= 4 ? 0.5 : horizon <= 12 ? 1 : horizon <= 30 ? 5 : 10;
        for (double t = 0; t <= horizon + 1e-6; t += step) {
            float x = (float) (x0 + w * t / horizon);
            dl.addLine(x, y + 10, x, y + 16, tick);
            dl.addText(x + 2, y, tick, String.format(Locale.ROOT, t == Math.rint(t) ? "%.0fs" : "%.1fs", t));
        }
        ImGui.dummy(w, 16);
    }

    private void drawTrack(FxEmitter e, double horizon, double now) {
        float x0 = trackX0, x1 = trackX1;
        float w = x1 - x0;
        float y = ImGui.getCursorScreenPosY() + 2;
        float h = ImGui.getTextLineHeight();
        ImDrawList dl = ImGui.getWindowDrawList();
        boolean silent = ctx.isMuted(e.name())
                || (ctx.doc().system().emitters().stream().anyMatch(o -> ctx.isSoloed(o.name())) && !ctx.isSoloed(e.name()));
        int bar = silent ? ImGui.getColorU32(0.4f, 0.4f, 0.45f, 0.6f) : ImGui.getColorU32(0.32f, 0.6f, 0.95f, 0.85f);
        int burst = ImGui.getColorU32(1f, 0.75f, 0.3f, silent ? 0.4f : 1f);
        dl.addRectFilled(x0, y, x1, y + h, ImGui.getColorU32(0.15f, 0.16f, 0.2f, 0.6f));
        for (FxTimeline.Cycle c : FxTimeline.cycles(e, horizon)) {
            float a = (float) (x0 + w * c.start() / horizon);
            float b = (float) (x0 + w * c.end() / horizon);
            if (b - a >= 1f) {
                dl.addRectFilled(a, y + 2, b, y + h - 2, bar, 3f);
            }
            if (c.burst()) {
                dl.addRectFilled(a - 1.5f, y, a + 1.5f, y + h, burst);
            }
        }
        if (e.burst() == 0 && e.rateScale() == 0f) {
            dl.addText(x0 + 4, y, ImGui.getColorU32(0.7f, 0.7f, 0.75f, 0.9f), "event-only");
        }
        double t = Double.isInfinite(horizon) ? 0 : Math.min(now, horizon);
        float px = (float) (x0 + w * t / horizon);
        dl.addLine(px, y - 2, px, y + h + 2, ImGui.getColorU32(1f, 0.35f, 0.35f, 1f), 2f);
        ImGui.dummy(Math.max(1f, ImGui.getContentRegionAvailX()), h + 4);
    }

    // ------------------------------------------------------------------------------------------------
    // Emitter stack
    // ------------------------------------------------------------------------------------------------

    void renderStack() {
        FxEditorDocument doc = ctx.doc();
        FxEmitter e = ctx.selected();
        if (e == null) {
            ImGui.textDisabled("No emitter selected.");
            return;
        }
        ParticleEffect effect = doc.effect(e.effect().name());
        inspector.applyPendingSprite(effect);
        effect = doc.effect(e.effect().name());
        ImGui.text("Emitter '" + e.name() + "' plays '" + effect.name() + "'");
        List<String> sharers = doc.emittersPlaying(effect.name());
        if (sharers.size() > 1) {
            ImGui.textColored(1f, 0.75f, 0.3f, 1f, "Shared with " + (sharers.size() - 1)
                    + " other emitter(s): edits apply to all. Duplicate the emitter to edit a copy.");
        }

        if (ParticleEditorWidgets.header("Emitter (timeline and placement)", true)) {
            renderEmitterFields(e);
        }

        deferred.clear();
        inspector.begin(effect);
        inspector.renderName();
        for (FxStageKind stage : STACK_STAGES) {
            renderStage(effect, stage);
        }
        if (ParticleEditorWidgets.header("Events", false)) {
            List<String> candidates = new ArrayList<>();
            for (ParticleEffect other : doc.document().effects()) {
                if (!other.name().equals(effect.name())) {
                    candidates.add(other.name());
                }
            }
            ImGui.textDisabled("Particle sub-emitters (each particle spawns these):");
            inspector.renderSubEmitters(candidates);
            ImGui.separator();
            ImGui.textDisabled("FX events from this emitter (see FX Parameters > Events for all):");
            renderHandlersOf(e.name());
        }
        if (ParticleEditorWidgets.header("Render", false)) {
            inspector.renderRender(spriteNames());
        }
        if (ParticleEditorWidgets.header("Custom attributes", false)) {
            renderAttributes(effect);
        }
        inspector.end();
        for (Runnable r : deferred) {
            r.run();
        }
        deferred.clear();
    }

    private void renderEmitterFields(FxEmitter e) {
        FxEditorDocument doc = ctx.doc();
        String n = e.name();
        dragField(n, "delay", "delay (s)", e.delay(), 0.01f, 0f, 3600f);
        boolean forever = e.duration() == FxEmitter.FOREVER;
        if (ImGui.checkbox("runs until stopped", forever)) {
            ctx.apply(() -> doc.setEmitterField(n, "duration", forever ? DoubleNode.valueOf(1.0)
                    : TextNode.valueOf("forever")));
        }
        if (!forever) {
            dragField(n, "duration", "duration (s)", e.duration(), 0.01f, 0f, 3600f);
            int[] loops = {e.loops()};
            ImGui.setNextItemWidth(160);
            if (ImGui.dragInt("loops (0 = forever)", loops, 0.1f, 0, 100_000)) {
                ctx.apply(() -> doc.setEmitterField(n, "loops", IntNode.valueOf(Math.max(0, loops[0]))));
            }
            dragField(n, "interval", "interval (s)", e.interval(), 0.01f, 0f, 3600f);
        }
        int[] burst = {e.burst()};
        ImGui.setNextItemWidth(160);
        if (ImGui.dragInt("burst per cycle", burst, 0.5f, 0, 1_000_000)) {
            ctx.apply(() -> doc.setEmitterField(n, "burst", IntNode.valueOf(Math.max(0, burst[0]))));
        }
        dragField(n, "rateScale", "rate scale", e.rateScale(), 0.01f, 0f, 1000f);
        dragField(n, "sizeScale", "size scale", e.sizeScale(), 0.01f, 0f, 1000f);
        dragField(n, "speedScale", "speed scale", e.speedScale(), 0.01f, 0f, 1000f);
        float[] off = {e.offsetX(), e.offsetY(), e.offsetZ()};
        if (ImGui.dragFloat3("offset", off, 0.01f, -1000f, 1000f)) {
            ArrayNode a = JSON.arrayNode().add(off[0]).add(off[1]).add(off[2]);
            ctx.apply(() -> doc.setEmitterField(n, "offset", a));
        }
        float[] tint = ParticleEditorWidgets.rgba(e.tint());
        if (ImGui.colorEdit4("tint", tint, imgui.flag.ImGuiColorEditFlags.AlphaBar)) {
            ctx.apply(() -> doc.setEmitterField(n, "tint", TextNode.valueOf(ParticleEditorWidgets.hex(
                    ParticleEditorWidgets.pack(tint)))));
        }
        boolean dir = e.hasDirection();
        if (ImGui.checkbox("override launch direction", dir)) {
            ctx.apply(() -> doc.setEmitterField(n, "direction", dir ? NullNode.instance
                    : JSON.arrayNode().add(0).add(1).add(0)));
        }
        if (dir) {
            float[] d = {e.dirX(), e.dirY(), e.dirZ()};
            if (ImGui.dragFloat3("direction", d, 0.01f, -1f, 1f)) {
                ctx.apply(() -> doc.setEmitterField(n, "direction", JSON.arrayNode().add(d[0]).add(d[1]).add(d[2])));
            }
        }
    }

    private void dragField(String emitter, String field, String label, float value, float speed, float min, float max) {
        float[] v = {value};
        ImGui.setNextItemWidth(160);
        if (ImGui.dragFloat(label, v, speed, min, max)) {
            float clamped = Math.max(min, Math.min(max, v[0]));
            ctx.apply(() -> ctx.doc().setEmitterField(emitter, field, DoubleNode.valueOf(clamped)));
        }
    }

    private static String stageLabel(FxStageKind stage) {
        return switch (stage) {
            case EMITTER_UPDATE -> "Emitter Update";
            case PARTICLE_SPAWN -> "Particle Spawn (initialize)";
            case PARTICLE_UPDATE -> "Particle Update";
            default -> ParticleEditorWidgets.pretty(stage);
        };
    }

    private static List<String> builtinsOf(FxStageKind stage) {
        return switch (stage) {
            case EMITTER_UPDATE -> List.of("rate");
            case PARTICLE_SPAWN -> List.of("initialize");
            case PARTICLE_UPDATE -> List.of("lifetime", "forces", "move", "spin", "trail");
            default -> List.of();
        };
    }

    private void renderStage(ParticleEffect effect, FxStageKind stage) {
        if (!ParticleEditorWidgets.header(stageLabel(stage), true)) {
            return;
        }
        FxEditorDocument doc = ctx.doc();
        String eff = effect.name();
        List<FxModule> stack = doc.stack(eff, stage);
        ImGui.pushID("stage_" + stage.name());
        for (int i = 0; i < stack.size(); i++) {
            FxModule m = stack.get(i);
            int index = i;
            ImGui.pushID(i);
            boolean open = ImGui.treeNodeEx("##mod", ImGuiTreeNodeFlags.DefaultOpen | ImGuiTreeNodeFlags.AllowOverlap,
                    m.name() + (m.isBuiltin() ? "" : "   [graph: " + m.nodes().size() + " nodes]"));
            ImGui.sameLine(Math.max(160f, ImGui.getContentRegionAvailX() - 70f + ImGui.getCursorPosX()));
            if (ImGui.arrowButton("##up", ImGuiDir.Up)) {
                deferred.add(() -> ctx.apply(() -> doc.moveModule(eff, stage, index, -1)));
            }
            ImGui.sameLine();
            if (ImGui.arrowButton("##down", ImGuiDir.Down)) {
                deferred.add(() -> ctx.apply(() -> doc.moveModule(eff, stage, index, 1)));
            }
            ImGui.sameLine();
            if (ImGui.smallButton("x")) {
                deferred.add(() -> ctx.apply(() -> doc.removeModule(eff, stage, index)));
            }
            hint("Remove the module (built-ins can come back through + Add module)");
            if (open) {
                if (m.isBuiltin()) {
                    if (!inspector.renderBuiltin(m.builtin())) {
                        ImGui.textDisabled("No settings.");
                    }
                } else {
                    if (ImGui.button("Edit graph")) {
                        FxEditorDocument.ModuleRef ref = new FxEditorDocument.ModuleRef(eff, stage, index);
                        deferred.add(() -> openGraph.accept(ref));
                    }
                    ImGui.sameLine();
                    ImGui.textDisabled("custom module - open it in FX Graph");
                }
                ImGui.treePop();
            }
            ImGui.popID();
        }
        ImGui.setNextItemWidth(170);
        if (ImGui.beginCombo("##add", "+ Add module")) {
            for (String b : builtinsOf(stage)) {
                if (stack.stream().noneMatch(m -> b.equals(m.builtin())) && ImGui.selectable("built-in: " + b)) {
                    int at = b.equals("lifetime") || b.equals("initialize") ? 0 : stack.size();
                    deferred.add(() -> ctx.apply(() -> doc.addModule(eff, stage, FxModule.builtin(b), at)));
                }
            }
            for (FxModule ready : readyMade(stage)) {
                if (ImGui.selectable(ready.name())) {
                    int at = insertBeforeMove(stack);
                    deferred.add(() -> ctx.apply(() -> doc.addModule(eff, stage, ready, at)));
                }
            }
            if (ImGui.selectable("empty custom module")) {
                List<String> names = stack.stream().map(FxModule::name).toList();
                String name = FxSystemViews.uniqueName(names, "custom");
                FxModule empty = FxGraphDraft.empty(name).lower().module();
                int at = stack.size();
                deferred.add(() -> {
                    if (ctx.apply(() -> doc.addModule(eff, stage, empty, at)) != null) {
                        openGraph.accept(new FxEditorDocument.ModuleRef(eff, stage, at));
                    }
                });
            }
            ImGui.endCombo();
        }
        ImGui.popID();
    }

    private static int insertBeforeMove(List<FxModule> stack) {
        for (int i = 0; i < stack.size(); i++) {
            if ("forces".equals(stack.get(i).builtin()) || "move".equals(stack.get(i).builtin())) {
                return i;
            }
        }
        return stack.size();
    }

    private static List<FxModule> readyMade(FxStageKind stage) {
        return switch (stage) {
            case PARTICLE_UPDATE -> List.of(FxModules.curlNoiseVelocity(2f, 0.5f, 0.5f),
                    FxModules.attractor(0f, 1f, 0f, 4f));
            case EMITTER_UPDATE -> List.of(FxModules.pulsingRate(1f));
            default -> List.of();
        };
    }

    private void renderAttributes(ParticleEffect effect) {
        FxEditorDocument doc = ctx.doc();
        List<FxAttribute> attrs = new ArrayList<>(doc.attributes(effect.name()));
        ImGui.textDisabled("Per-particle values your graphs read and write (besides position, velocity, age...).");
        for (int i = 0; i < attrs.size(); i++) {
            FxAttribute a = attrs.get(i);
            ImGui.pushID("attr" + i);
            ImGui.text(a.name() + " (" + ParticleEditorWidgets.pretty(a.type()) + ")");
            ImGui.sameLine();
            float[] d = new float[a.type().width];
            for (int k = 0; k < d.length; k++) d[k] = a.defaults().get(k);
            ImGui.setNextItemWidth(180);
            boolean changed = d.length == 1 ? ImGui.dragFloat("##def", d, 0.01f) : ImGui.dragFloat3("##def", d, 0.01f);
            ImGui.sameLine();
            boolean remove = ImGui.smallButton("x");
            ImGui.popID();
            int index = i;
            if (changed) {
                List<Float> values = new ArrayList<>();
                for (float v : d) values.add(v);
                attrs.set(index, new FxAttribute(a.name(), a.type(), values));
                List<FxAttribute> next = List.copyOf(attrs);
                deferred.add(() -> ctx.apply(() -> doc.setAttributes(effect.name(), next)));
            }
            if (remove) {
                attrs.remove(index);
                List<FxAttribute> next = List.copyOf(attrs);
                deferred.add(() -> ctx.apply(() -> doc.setAttributes(effect.name(), next)));
                break;
            }
        }
        ImGui.setNextItemWidth(120);
        ImGui.inputTextWithHint("##attrName", "name", newAttrName);
        ImGui.sameLine();
        ImGui.setNextItemWidth(80);
        ImGui.combo("##attrType", newAttrType, new String[]{"float", "vec3"});
        ImGui.sameLine();
        if (ImGui.button("Add attribute") && !newAttrName.get().isBlank()) {
            String name = newAttrName.get().strip();
            FxType type = newAttrType.get() == 0 ? FxType.FLOAT : FxType.VEC3;
            List<FxAttribute> next = new ArrayList<>(attrs);
            deferred.add(() -> {
                try {
                    next.add(new FxAttribute(name, type, null));
                    if (ctx.apply(() -> doc.setAttributes(effect.name(), next)) != null) {
                        newAttrName.set("");
                    }
                } catch (IllegalArgumentException ex) {
                    ctx.setError(ex.getMessage());
                }
            });
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Parameters, bindings, events, light, cues
    // ------------------------------------------------------------------------------------------------

    void renderParameters() {
        FxEditorDocument doc = ctx.doc();
        FxSystem system = doc.system();
        if (ParticleEditorWidgets.header("Exposed parameters", true)) {
            ImGui.textDisabled("Inputs the game sets per instance. Sliders drive the preview only; defaults are saved.");
            for (FxParameter p : system.parameters()) {
                ImGui.pushID("param_" + p.name());
                renderParameter(p);
                ImGui.popID();
            }
            ImGui.setNextItemWidth(120);
            ImGui.inputTextWithHint("##pname", "name", newParamName);
            ImGui.sameLine();
            ImGui.setNextItemWidth(90);
            ImGui.combo("##ptype", newParamType, new String[]{"float", "vector", "color"});
            ImGui.sameLine();
            if (ImGui.button("Add parameter") && !newParamName.get().isBlank()) {
                String name = newParamName.get().strip();
                FxParameter p = switch (newParamType.get()) {
                    case 1 -> FxParameter.vector(name, 0f, 0f, 0f);
                    case 2 -> FxParameter.color(name, 0xFFFFFFFF);
                    default -> FxParameter.scalar(name, 1f);
                };
                if (ctx.apply(() -> doc.putParameter(null, null, p)) != null) {
                    newParamName.set("");
                }
            }
            if (preview != null && ImGui.smallButton("Reset preview values")) {
                preview.resetParams();
            }
        }
        if (ParticleEditorWidgets.header("Bindings (parameter -> value)", false)) {
            ObjectNode binding = sample(FxSystem.builder("x").emitter(system.emitters().get(0))
                    .parameter(FxParameter.scalar("p", 1f)).bind(FxBinding.of("p", FxBinding.Target.RATE, null))
                    .build(), "bindings");
            if (!system.parameters().isEmpty()) {
                binding.put("parameter", system.parameters().get(0).name());
            }
            renderSection("bindings", binding, system.parameters().isEmpty() ? "Add a parameter first." : null);
        }
        if (ParticleEditorWidgets.header("Events", false)) {
            renderEvents(system);
        }
        if (ParticleEditorWidgets.header("Light", false)) {
            renderLight(system);
        }
        if (ParticleEditorWidgets.header("Sound cues", false)) {
            ObjectNode cue = sample(FxSystem.builder("x").emitter(system.emitters().get(0))
                    .cue(FxSoundCue.onPlay("sound")).build(), "cues");
            renderSection("cues", cue, null);
            ImGui.textDisabled("Sounds are names the game resolves (or the document's embedded sounds).");
        }
        List<String> log = preview == null ? List.of() : preview.log();
        if (!log.isEmpty() && ParticleEditorWidgets.header("Preview log", false)) {
            for (String line : log) {
                ImGui.textDisabled(line);
            }
        }
    }

    private void renderParameter(FxParameter p) {
        FxEditorDocument doc = ctx.doc();
        ImGui.text(p.name() + "  (" + ParticleEditorWidgets.pretty(p.type()) + ")");
        ImGui.sameLine();
        if (ImGui.smallButton("x")) {
            Integer dropped = ctx.apply(() -> doc.removeParameter(p.name()));
            if (dropped != null && dropped > 0) {
                ctx.setNote("Removed parameter '" + p.name() + "' and its " + dropped + " binding(s).");
            }
            return;
        }
        float[] def = {p.x(), p.y(), p.z(), p.w()};
        ImGui.setNextItemWidth(220);
        boolean changed = switch (p.type()) {
            case FLOAT -> ImGui.dragFloat("default", def, 0.01f);
            case VECTOR -> ImGui.dragFloat3("default", def, 0.01f);
            case COLOR -> ImGui.colorEdit4("default", def, imgui.flag.ImGuiColorEditFlags.AlphaBar);
        };
        if (changed) {
            FxParameter next = new FxParameter(p.name(), p.type(), def[0], def[1], def[2], def[3]);
            ctx.apply(() -> doc.putParameter("param:" + p.name(), p.name(), next));
        }
        if (preview == null) {
            return;
        }
        float[] live = preview.param(p.name());
        float[] v = live != null ? live.clone() : new float[]{p.x(), p.y(), p.z(), p.w()};
        ImGui.setNextItemWidth(220);
        boolean moved = switch (p.type()) {
            case FLOAT -> ImGui.sliderFloat("preview", v, Math.min(0f, p.x()), Math.max(4f, p.x() * 4f));
            case VECTOR -> ImGui.dragFloat3("preview", v, 0.01f);
            case COLOR -> ImGui.colorEdit4("preview", v, imgui.flag.ImGuiColorEditFlags.AlphaBar);
        };
        if (moved) {
            preview.setParam(p.name(), v);
        }
    }

    private void renderEvents(FxSystem system) {
        ObjectNode handler = sample(FxSystem.builder("x").emitter(system.emitters().get(0))
                .on(FxEventHandler.forward(FxEventHandler.Trigger.DEATH, system.emitters().get(0).name(), "event"))
                .build(), "events");
        ImGui.textDisabled("Particle deaths/impacts of an emitter, or custom events the game raises, spawn into");
        ImGui.textDisabled("emitters, reach the game by name ('forward') and play sounds.");
        renderSection("events", handler, null);
        List<String> custom = new ArrayList<>();
        for (FxEventHandler h : system.handlers()) {
            if (h.trigger() == FxEventHandler.Trigger.CUSTOM && !custom.contains(h.source())) {
                custom.add(h.source());
            }
        }
        if (!custom.isEmpty() && preview != null) {
            ImGui.textDisabled("Raise in the preview:");
            for (String name : custom) {
                ImGui.sameLine();
                if (ImGui.smallButton(name)) {
                    preview.raise(name);
                }
            }
        }
    }

    /** Handlers whose source is {@code emitter} (the stack's Events section). */
    private void renderHandlersOf(String emitter) {
        ObjectNode view = ctx.doc().view();
        ArrayNode events = (ArrayNode) view.get("events");
        boolean any = false;
        for (int i = 0; i < events.size(); i++) {
            JsonNode h = events.get(i);
            if (!emitter.equals(h.path("source").asText()) || "custom".equalsIgnoreCase(h.path("trigger").asText())) {
                continue;
            }
            any = true;
            ImGui.pushID("ev" + i);
            renderEntry("events", i, (ObjectNode) h);
            ImGui.popID();
            ImGui.separator();
        }
        if (!any) {
            ImGui.textDisabled("None.");
        }
        if (ImGui.smallButton("+ event from this emitter")) {
            ObjectNode h = sample(FxSystem.builder("x").emitter(ctx.doc().system().emitters().get(0))
                    .on(FxEventHandler.forward(FxEventHandler.Trigger.DEATH,
                            ctx.doc().system().emitters().get(0).name(), "event")).build(), "events");
            h.put("source", emitter);
            deferred.add(() -> ctx.apply(() -> ctx.doc().addEntry("events", h)));
        }
    }

    private void renderLight(FxSystem system) {
        FxEditorDocument doc = ctx.doc();
        boolean has = system.light() != null;
        if (ImGui.checkbox("the system casts a light", has)) {
            if (has) {
                ctx.apply(() -> doc.setLight(null, null));
            } else {
                ObjectNode light = JSON.objectNode();
                light.put("color", "#FFC080FF");
                light.put("intensity", 1.0);
                light.put("radius", 8.0);
                ctx.apply(() -> doc.setLight(null, light));
            }
        }
        if (!has) {
            return;
        }
        ObjectNode light = (ObjectNode) doc.view().get("light");
        for (var it = light.fields(); it.hasNext(); ) {
            var f = it.next();
            ImGui.pushID("light_" + f.getKey());
            JsonNode next = valueWidget(f.getKey(), f.getValue(), "light");
            ImGui.popID();
            if (next != null) {
                ObjectNode copy = light.deepCopy();
                copy.set(f.getKey(), next);
                ctx.apply(() -> doc.setLight("light:" + f.getKey(), copy));
                return;
            }
        }
        ImGui.textDisabled("keys: [seconds, intensity] pairs over the instance's life (empty = constant).");
    }

    /** The view entry {@code section}[0] of a one-entry sample system: the shape a new entry starts from. */
    private static ObjectNode sample(FxSystem s, String section) {
        return (ObjectNode) OmfxCodec.toSystemJson(OmfxDocument.of(s)).get(section).get(0).deepCopy();
    }

    /** A list section of the view ({@code bindings}, {@code events}, {@code cues}) as editable rows. */
    private void renderSection(String section, ObjectNode fresh, String cannotAdd) {
        FxEditorDocument doc = ctx.doc();
        ArrayNode list = (ArrayNode) doc.view().get(section);
        for (int i = 0; i < list.size(); i++) {
            ImGui.pushID(section + i);
            boolean removed = renderEntry(section, i, (ObjectNode) list.get(i));
            ImGui.popID();
            ImGui.separator();
            if (removed) {
                break;
            }
        }
        if (cannotAdd != null) {
            ImGui.textDisabled(cannotAdd);
        } else if (ImGui.smallButton("+ add##" + section)) {
            ctx.apply(() -> doc.addEntry(section, fresh));
        }
    }

    /** One entry's members as widgets; true when it was removed. */
    private boolean renderEntry(String section, int index, ObjectNode entry) {
        if (ImGui.smallButton("x")) {
            ctx.apply(() -> ctx.doc().removeEntry(section, index));
            return true;
        }
        ImGui.sameLine();
        ImGui.beginGroup();
        for (var it = entry.fields(); it.hasNext(); ) {
            var f = it.next();
            ImGui.pushID(f.getKey());
            JsonNode next = valueWidget(f.getKey(), f.getValue(), section);
            ImGui.popID();
            if (next != null) {
                ctx.apply(() -> ctx.doc().setEntryField(section, index, f.getKey(), next));
            }
        }
        ImGui.endGroup();
        return false;
    }

    /** A widget for one JSON member; returns the new value when it was edited. */
    private JsonNode valueWidget(String key, JsonNode value, String section) {
        List<String> choices = choices(key, section);
        ImGui.setNextItemWidth(200);
        if (choices != null) {
            String current = value.isNull() ? "(none)" : value.asText();
            if (ImGui.beginCombo(key, current)) {
                for (String c : choices) {
                    if (ImGui.selectable(c, c.equals(current))) {
                        return c.equals("(none)") ? NullNode.instance : TextNode.valueOf(c);
                    }
                }
                ImGui.endCombo();
            }
            return null;
        }
        if (value.isBoolean()) {
            if (ImGui.checkbox(key, value.asBoolean())) {
                return BooleanNode.valueOf(!value.asBoolean());
            }
            return null;
        }
        if (value.isIntegralNumber()) {
            int[] v = {value.asInt()};
            return ImGui.dragInt(key, v, 0.2f) ? IntNode.valueOf(v[0]) : null;
        }
        if (value.isNumber()) {
            float[] v = {value.floatValue()};
            return ImGui.dragFloat(key, v, 0.01f) ? DoubleNode.valueOf(v[0]) : null;
        }
        if (value.isArray() && value.size() == 3 && value.get(0).isNumber()) {
            float[] v = {value.get(0).floatValue(), value.get(1).floatValue(), value.get(2).floatValue()};
            return ImGui.dragFloat3(key, v, 0.01f) ? JSON.arrayNode().add(v[0]).add(v[1]).add(v[2]) : null;
        }
        if (value.isTextual() && value.asText().startsWith("#") && value.asText().length() == 9) {
            float[] c = ParticleEditorWidgets.rgba((int) Long.parseLong(value.asText().substring(1), 16));
            if (ImGui.colorEdit4(key, c, imgui.flag.ImGuiColorEditFlags.AlphaBar)) {
                return TextNode.valueOf(ParticleEditorWidgets.hex(ParticleEditorWidgets.pack(c)));
            }
            return null;
        }
        if (value.isTextual() || value.isNull()) {
            String id = section + ":" + key + ":" + System.identityHashCode(value);
            if (!id.equals(textFor) || !ImGui.isAnyItemActive()) {
                textBuffer.set(value.isNull() ? "" : value.asText());
            }
            if (ImGui.inputTextWithHint(key, value.isNull() ? "(none)" : "", textBuffer)) {
                textFor = id;
            }
            if (ImGui.isItemDeactivatedAfterEdit()) {
                String t = textBuffer.get().strip();
                return t.isEmpty() ? NullNode.instance : TextNode.valueOf(t);
            }
            return null;
        }
        ImGui.textDisabled(key + ": " + value);
        return null;
    }

    /** Fixed vocabularies for some members (enums and references). */
    private List<String> choices(String key, String section) {
        FxSystem s = ctx.doc().system();
        List<String> emitters = s.emitters().stream().map(FxEmitter::name).toList();
        switch (key) {
            case "target" -> {
                List<String> out = new ArrayList<>();
                for (FxBinding.Target t : FxBinding.Target.values()) out.add(t.name().toLowerCase(Locale.ROOT));
                return out;
            }
            case "trigger" -> {
                List<String> out = new ArrayList<>();
                Enum<?>[] values = section.equals("cues") ? FxSoundCue.Trigger.values() : FxEventHandler.Trigger.values();
                for (Enum<?> t : values) out.add(t.name().toLowerCase(Locale.ROOT));
                return out;
            }
            case "parameter" -> {
                return s.parameters().stream().map(FxParameter::name).toList();
            }
            case "emitter", "spawnInto" -> {
                List<String> out = new ArrayList<>();
                out.add("(none)");
                out.addAll(emitters);
                return out;
            }
            default -> {
                return null;
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Sprites
    // ------------------------------------------------------------------------------------------------

    List<String> spriteNames() {
        return ctx.doc().document().sprites().stream().map(OmfxSprite::name).toList();
    }

    void renderSprites() {
        FxEditorDocument doc = ctx.doc();
        Path picked = pendingSpriteFile;
        if (picked != null) {
            pendingSpriteFile = null;
            embedFile(ctx, picked);
        }
        if (ImGui.button("Embed PNG / OMT...") && imagePicker != null) {
            imagePicker.accept(p -> pendingSpriteFile = Path.of(p));
        }
        ImGui.sameLine();
        ImGui.textDisabled("or drop image files onto the window");
        List<OmfxSprite> sprites = doc.document().sprites();
        if (sprites.isEmpty()) {
            ImGui.textDisabled("No embedded sprites. Effects with a texture sprite list them in their Render section.");
        }
        for (OmfxSprite s : sprites) {
            ImGui.pushID("sprite_" + s.name());
            int tex = thumbnail(s);
            if (tex != 0) {
                ImGui.image(tex, 48, 48);
            } else {
                ImGui.dummy(48, 48);
            }
            ImGui.sameLine();
            ImGui.beginGroup();
            ImGui.text(s.name());
            List<String> users = doc.spriteUsers(s.name());
            ImGui.textDisabled(ParticleEditorWidgets.bytes(s.bytes().length)
                    + (users.isEmpty() ? "   unused" : "   used by " + String.join(", ", users)));
            if (ImGui.smallButton("Remove")) {
                ctx.apply(() -> doc.removeSprite(s.name()));
                if (!users.isEmpty()) {
                    ctx.setNote("'" + s.name() + "' is still named by " + String.join(", ", users)
                            + ": they now look for a file of that name.");
                }
            }
            ImGui.endGroup();
            ImGui.popID();
        }
        List<String> external = new ArrayList<>();
        for (ParticleEffect e : doc.document().effects()) {
            for (String t : e.textures()) {
                if (doc.document().sprite(t) == null && !external.contains(t)) {
                    external.add(t);
                }
            }
        }
        if (!external.isEmpty()) {
            ImGui.separator();
            ImGui.textDisabled("Texture files (not embedded, resolved next to the document):");
            for (String t : external) {
                boolean missing = preview != null && preview.missingSprites().contains(t);
                if (missing) {
                    ImGui.textColored(0.95f, 0.45f, 0.4f, 1f, t + "  (not found)");
                } else {
                    ImGui.text(t);
                }
            }
        }
    }

    /** Embeds an image file as a sprite named after it (made unique); returns the name, or null on failure. */
    static String embedFile(FxEditorContext ctx, Path file) {
        String fileName = file.getFileName().toString();
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".png") && !lower.endsWith(".omt")) {
            ctx.setError("Only .png and .omt images can be embedded: " + fileName);
            return null;
        }
        String stem = fileName.substring(0, fileName.lastIndexOf('.'));
        List<String> taken = ctx.doc().document().sprites().stream().map(OmfxSprite::name).toList();
        String name = FxSystemViews.uniqueName(taken, stem);
        try {
            byte[] bytes = Files.readAllBytes(file);
            ctx.doc().embedSprite(name, bytes);
            ctx.afterChange();
            ctx.setNote("Embedded '" + name + "'.");
            return name;
        } catch (IOException | IllegalArgumentException e) {
            ctx.setError("Could not embed " + fileName + ": " + e.getMessage());
            return null;
        }
    }

    /** A small GL texture of an embedded sprite (cached per sprite object). */
    private int thumbnail(OmfxSprite s) {
        int[] cached = thumbnails.get(s.name());
        if (cached != null && thumbnailSource.get(s.name()) == s) {
            return cached[0];
        }
        if (cached != null) {
            glDeleteTextures(cached[0]);
        }
        int tex = 0;
        try {
            ParticleImage img = s.decode();
            ByteBuffer buf = org.lwjgl.BufferUtils.createByteBuffer(img.width() * img.height() * 4);
            for (int p : img.rgba()) {
                buf.put((byte) (p >>> 24)).put((byte) (p >>> 16)).put((byte) (p >>> 8)).put((byte) p);
            }
            buf.flip();
            int prev = glGetInteger(GL_TEXTURE_BINDING_2D);
            tex = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, tex);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, img.width(), img.height(), 0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glBindTexture(GL_TEXTURE_2D, prev);
        } catch (IOException | RuntimeException e) {
            tex = 0;
        }
        thumbnails.put(s.name(), new int[]{tex});
        thumbnailSource.put(s.name(), s);
        return tex;
    }

    /** Frees the thumbnail textures (render thread). */
    void close() {
        for (int[] t : thumbnails.values()) {
            if (t[0] != 0) {
                glDeleteTextures(t[0]);
            }
        }
        thumbnails.clear();
        thumbnailSource.clear();
    }
}
