package com.openmason.main.systems.animgraph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openmason.engine.anim.AnimClipDocument;
import com.openmason.engine.animgraph.AnimGraph;
import com.openmason.engine.animgraph.AnimGraphDocument;
import com.openmason.engine.animgraph.AnimGraphInstance;
import com.openmason.engine.rig.JointPalette;
import com.openmason.main.systems.character.CharacterDocument;
import org.joml.Matrix4f;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * One open animation graph (OpenMason #164): the editable native document ({@link AnimGraphDocument}, one undo
 * history shared by the Animation Graph tab and the {@code animgraph_*} MCP tools) plus its live PREVIEW - a
 * character (an {@code .omchar} or a model, loaded through #163's {@link CharacterDocument}), a clip per slot
 * (embedded in the character's model container or an {@code .omanim} file) and parameter values - evaluated by the
 * native runtime. The preview setup is stored in the graph's {@code editor.preview} section (user decision
 * 2026-11-25: editor data lives in the .omanimgraph). GL- and ImGui-free; main thread only.
 *
 * <p>Thin: compiling, evaluating and every debug value are native; this class loads files, keeps the instance in
 * step with the document's revision (hot reload keeps parameter values and machine states) and packs the debugger
 * snapshot.
 */
public final class AnimGraphSession implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AnimGraphDocument doc;
    private Path file;

    // Preview.
    private String characterPath;
    private CharacterDocument character;
    private String characterProblem;
    private final Map<String, String> slotSources = new TreeMap<>();  // slot -> "model:<clip>" | "file:<path>"
    private final Map<String, AnimClipDocument> embeddedClips = new LinkedHashMap<>();
    private final Map<String, AnimClipDocument> fileClips = new HashMap<>();
    private final Map<String, Object> paramValues = new LinkedHashMap<>();  // name -> Float | Boolean | Integer | float[2]
    private AnimGraph graph;
    private AnimGraphInstance instance;
    private long builtRevision = Long.MIN_VALUE;
    private String previewProblem;
    private final JointPalette palette = new JointPalette();
    private float time;
    private final Map<String, Integer> boundSlots = new TreeMap<>();

    private AnimGraphSession(AnimGraphDocument doc, Path file) {
        this.doc = doc;
        this.file = file;
        readPreviewSetup();
    }

    public static AnimGraphSession create(String name, String profile) {
        return new AnimGraphSession(AnimGraphDocument.create(name, profile), null);
    }

    public static AnimGraphSession open(Path file) throws IOException {
        return new AnimGraphSession(AnimGraphDocument.read(file), file);
    }

    public AnimGraphDocument document() {
        return doc;
    }

    public java.util.Optional<Path> file() {
        return java.util.Optional.ofNullable(file);
    }

    public String displayName() {
        try {
            String n = JSON.readTree(doc.json()).path("name").asText("");
            if (!n.isBlank()) return n;
        } catch (IOException ignored) {
            // fall through
        }
        if (file != null) {
            String f = file.getFileName().toString();
            return f.endsWith(".omanimgraph") ? f.substring(0, f.length() - ".omanimgraph".length()) : f;
        }
        return "graph";
    }

    public boolean isDirty() {
        return doc.dirty();
    }

    /** Applies operations as one undo step; re-reads the preview setup when it changed. */
    public String apply(String opsJson, String label, String mergeKey) {
        String result = doc.apply(opsJson, label, mergeKey);
        readPreviewSetup();
        return result;
    }

    public boolean undo() {
        boolean done = doc.undo();
        readPreviewSetup();
        return done;
    }

    public boolean redo() {
        boolean done = doc.redo();
        readPreviewSetup();
        return done;
    }

    public void save(Path path) throws IOException {
        doc.save(path);
        file = path;
    }

    public void saved(Path path) {
        doc.markSaved();
        file = path;
    }

    // ------------------------------------------------------------------------------------------------
    // Preview setup (editor.preview: {character, slots: {slot: "model:<clip>" | "file:<path>"}})

    /** Sets the preview character and/or slot sources: one undo step (they live in the file's editor section). */
    public void setPreview(String characterRef, Map<String, String> slots) {
        ObjectNode preview = JSON.createObjectNode();
        String c = characterRef != null ? characterRef : characterPath;
        if (c != null && !c.isBlank()) preview.put("character", c);
        ObjectNode s = preview.putObject("slots");
        Map<String, String> merged = new TreeMap<>(slotSources);
        if (slots != null) {
            slots.forEach((k, v) -> {
                if (v == null || v.isBlank()) merged.remove(k);
                else merged.put(k, v);
            });
        }
        merged.forEach(s::put);
        ObjectNode op = JSON.createObjectNode();
        op.put("op", "editor");
        op.put("key", "preview");
        op.set("value", preview);
        apply("[" + op + "]", "preview setup", null);
    }

    private void readPreviewSetup() {
        JsonNode p;
        try {
            p = JSON.readTree(doc.view()).path("preview");
        } catch (IOException e) {
            p = JSON.createObjectNode();
        }
        String c = p.path("character").asText(null);
        Map<String, String> slots = new TreeMap<>();
        p.path("slots").fields().forEachRemaining(e -> slots.put(e.getKey(), e.getValue().asText()));
        boolean changed = !java.util.Objects.equals(c, characterPath) || !slots.equals(slotSources);
        if (!java.util.Objects.equals(c, characterPath)) {
            loadCharacter(c);
        }
        slotSources.clear();
        slotSources.putAll(slots);
        if (changed) builtRevision = Long.MIN_VALUE;
    }

    private void loadCharacter(String ref) {
        closeCharacter();
        characterPath = ref;
        characterProblem = null;
        if (ref == null || ref.isBlank()) return;
        Path p = resolve(ref);
        try {
            character = p.getFileName().toString().endsWith(".omchar") ? CharacterDocument.open(p, projectDir())
                    : CharacterDocument.fromModel(p);
            Path model = character.modelFile().orElse(null);
            if (model != null) readEmbeddedClips(model);
        } catch (IOException | RuntimeException e) {
            characterProblem = "character " + ref + ": " + e.getMessage();
            closeCharacter();
            characterPath = ref;
        }
    }

    /** Clips inside the model container (any zip holding .omanim entries; "states/Idle/clip.omanim" -> "Idle"). */
    private void readEmbeddedClips(Path model) {
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(model))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                String name = e.getName();
                if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".omanim")) continue;
                String[] parts = name.split("/");
                String stem = parts[parts.length - 1].substring(0, parts[parts.length - 1].length() - ".omanim".length());
                String clip = stem.equals("clip") && parts.length > 1 ? parts[parts.length - 2] : stem;
                try {
                    embeddedClips.put(clip, AnimClipDocument.read(in.readAllBytes()));
                } catch (IOException | RuntimeException bad) {
                    // an unreadable clip is simply not offered
                }
            }
        } catch (IOException notAZip) {
            // a model without embedded clips
        }
    }

    private Path resolve(String ref) {
        Path p = Path.of(ref);
        if (p.isAbsolute()) return p;
        if (file != null && file.toAbsolutePath().getParent() != null) {
            Path beside = file.toAbsolutePath().getParent().resolve(p);
            if (Files.exists(beside)) return beside;
        }
        Path project = projectDir();
        return project != null ? project.resolve(p) : p;
    }

    private Path projectRoot;

    /** The project folder relative preview paths resolve against; re-reads the preview setup. */
    public void setProjectRoot(Path root) {
        this.projectRoot = root;
        String c = characterPath;
        characterPath = null;
        if (c != null) {
            loadCharacter(c);
            builtRevision = Long.MIN_VALUE;
        }
    }

    private Path projectDir() {
        return projectRoot;
    }

    public CharacterDocument character() {
        return character;
    }

    public String characterPath() {
        return characterPath;
    }

    public Map<String, String> slotSources() {
        return Map.copyOf(slotSources);
    }

    /** The clips the preview character's model container holds. */
    public List<String> embeddedClipNames() {
        return List.copyOf(embeddedClips.keySet());
    }

    // ------------------------------------------------------------------------------------------------
    // Running the preview

    /** Why the preview cannot run (no character, graph errors...), or null. */
    public String previewProblem() {
        ensureBuilt();
        return previewProblem;
    }

    public AnimGraph graph() {
        ensureBuilt();
        return graph;
    }

    public AnimGraphInstance instance() {
        ensureBuilt();
        return instance;
    }

    /** Slot -> bind result (0 = bound, >0 = dropped tracks, -1 = no clip). */
    public Map<String, Integer> boundSlots() {
        ensureBuilt();
        return Map.copyOf(boundSlots);
    }

    public float time() {
        return time;
    }

    /** Parameter values the preview applies (kept across recompiles). */
    public void setParam(String name, Object value) {
        paramValues.put(name, value);
        if (instance != null && graph != null) applyParam(name, value);
    }

    public Object param(String name) {
        return paramValues.get(name);
    }

    public void fireTrigger(String name) {
        ensureBuilt();
        if (instance == null) return;
        for (AnimGraph.Param p : graph.params()) {
            if (p.name().equals(name) && p.type() == AnimGraph.ParamType.TRIGGER) instance.fireTrigger(p.index());
        }
    }

    /** Restarts the preview from the graph's entry states. */
    public void restart() {
        closeRuntime();  // no machine states to carry over
        builtRevision = Long.MIN_VALUE;
        time = 0f;
    }

    /** Advances the preview by dt seconds; returns the posed palette (null when the preview cannot run). */
    public JointPalette step(float dt) {
        ensureBuilt();
        if (instance == null) return null;
        instance.evaluate(dt, new Matrix4f(), palette);
        time += dt;
        return palette;
    }

    public JointPalette palette() {
        return palette;
    }

    private void ensureBuilt() {
        long rev = doc.revision();
        if (rev == builtRevision) return;
        builtRevision = rev;
        // Keep where every state machine was (hot reload), by machine id and state name.
        Map<String, String> machineStates = new HashMap<>();
        if (graph != null && instance != null) {
            for (AnimGraph.Node n : graph.nodes()) {
                if (n.type() == AnimGraph.NodeType.STATE_MACHINE) {
                    List<String> states = graph.states(n.index());
                    int s = instance.state(n.index());
                    if (s >= 0 && s < states.size()) machineStates.put(n.id(), states.get(s));
                }
            }
        }
        closeRuntime();
        previewProblem = null;
        if (!doc.valid()) {
            previewProblem = "the graph has errors (see Problems)";
            return;
        }
        if (character == null) {
            previewProblem = characterProblem != null ? characterProblem : "no preview character";
            return;
        }
        try {
            graph = doc.compile();
            instance = AnimGraphInstance.create(graph, character.rig(), character.definition());
        } catch (RuntimeException e) {
            previewProblem = e.getMessage();
            closeRuntime();
            return;
        }
        bindInto(graph, instance, boundSlots);
        paramValues.forEach(this::applyParam);
        for (AnimGraph.Node n : graph.nodes()) {
            String s = machineStates.get(n.id());
            if (s != null && n.type() == AnimGraph.NodeType.STATE_MACHINE) {
                int idx = graph.states(n.index()).indexOf(s);
                if (idx >= 0) instance.travel(n.index(), idx);
            }
        }
    }

    private void bindInto(AnimGraph g, AnimGraphInstance inst, Map<String, Integer> results) {
        for (String slot : g.slots()) {
            AnimClipDocument clip = clipFor(slot);
            int r = clip == null ? -1 : inst.bindSlot(slot, clip);
            if (results != null) results.put(slot, r);
        }
    }

    /**
     * A HEADLESS evaluation: a fresh instance of the current document on the preview character and clips, the
     * session's parameter values applied, then {@code frames} evaluates of {@code dt} (triggers fired before the
     * first). Returns its debugger snapshot; the live preview is untouched. Restarting the live preview and stepping
     * it with the same inputs gives the same numbers (the #164 debugger acceptance).
     */
    public ObjectNode evaluateHeadless(List<String> triggers, int frames, float dt) {
        ensureBuilt();
        ObjectNode out = JSON.createObjectNode();
        if (graph == null) {
            out.put("problem", previewProblem);
            return out;
        }
        try (AnimGraph g = doc.compile();
             AnimGraphInstance inst = AnimGraphInstance.create(g, character.rig(), character.definition())) {
            bindInto(g, inst, null);
            for (Map.Entry<String, Object> e : paramValues.entrySet()) applyParam(g, inst, e.getKey(), e.getValue());
            for (String t : triggers) {
                for (AnimGraph.Param p : g.params()) {
                    if (p.name().equals(t) && p.type() == AnimGraph.ParamType.TRIGGER) inst.fireTrigger(p.index());
                }
            }
            JointPalette pal = new JointPalette();
            float t = 0f;
            for (int i = 0; i < frames; i++) {
                inst.evaluate(dt, new Matrix4f(), pal);
                t += dt;
            }
            out.put("time", round(t));
            snapshot(g, inst, out);
        }
        return out;
    }

    private AnimClipDocument clipFor(String slot) {
        String src = slotSources.get(slot);
        if (src == null) return null;
        if (src.startsWith("model:")) return embeddedClips.get(src.substring(6));
        if (src.startsWith("file:")) {
            String path = src.substring(5);
            return fileClips.computeIfAbsent(path, p -> {
                try {
                    return AnimClipDocument.read(resolve(p));
                } catch (IOException | RuntimeException e) {
                    return null;
                }
            });
        }
        return null;
    }

    private void applyParam(String name, Object value) {
        applyParam(graph, instance, name, value);
    }

    private static void applyParam(AnimGraph graph, AnimGraphInstance instance, String name, Object value) {
        for (AnimGraph.Param p : graph.params()) {
            if (!p.name().equals(name)) continue;
            switch (p.type()) {
                case FLOAT -> instance.setFloat(p.index(), ((Number) value).floatValue());
                case INT -> instance.setInt(p.index(), ((Number) value).intValue());
                case BOOL -> instance.setBool(p.index(), Boolean.TRUE.equals(value));
                case VEC2 -> {
                    float[] v = (float[]) value;
                    instance.setVec2(p.index(), v[0], v[1]);
                }
                case TRIGGER -> {
                    // triggers fire through fireTrigger
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Debugger

    /**
     * The debugger snapshot of the last evaluate, as JSON: time, per node {id, active, influence, time, progress,
     * weights?, state?/previous?/blend?}, notifies, curves. The same numbers the native runtime reports (the tab's
     * Debugger and the MCP evaluate tool read this).
     */
    public ObjectNode debug() {
        ObjectNode out = JSON.createObjectNode();
        out.put("time", round(time));
        ensureBuilt();
        if (instance == null) {
            out.put("problem", previewProblem);
            return out;
        }
        snapshot(graph, instance, out);
        return out;
    }

    /** Packs a debug snapshot of any instance of {@code g} (also used for headless evaluations). */
    public static void snapshot(AnimGraph g, AnimGraphInstance inst, ObjectNode out) {
        List<AnimGraphInstance.NodeDebug> dbg = inst.debug();
        var nodes = out.putArray("nodes");
        for (AnimGraph.Node n : g.nodes()) {
            AnimGraphInstance.NodeDebug d = dbg.get(n.index());
            ObjectNode o = nodes.addObject();
            o.put("id", n.id());
            o.put("active", d.active());
            o.put("influence", round(d.influence()));
            if (n.type() == AnimGraph.NodeType.CLIP) {
                o.put("time", round(d.time()));
                o.put("bound", d.bound());
            }
            o.put("progress", round(d.progress()));
            if (n.type() != AnimGraph.NodeType.CLIP && n.type() != AnimGraph.NodeType.SLOT) {
                var w = o.putArray("weights");
                for (float f : inst.debugWeights(n.index())) w.add(round(f));
            }
            if (n.type() == AnimGraph.NodeType.STATE_MACHINE) {
                List<String> states = g.states(n.index());
                int s = inst.state(n.index());
                o.put("state", s >= 0 && s < states.size() ? states.get(s) : null);
                o.put("blend", round(inst.stateBlend(n.index())));
            }
        }
        var notifies = out.putArray("notifies");
        for (AnimGraphInstance.Notify n : inst.notifies()) notifies.add(n.name());
        ObjectNode curves = out.putObject("curves");
        List<String> names = inst.curveNames();
        for (int i = 0; i < names.size(); i++) curves.put(names.get(i), round(inst.curve(i)));
    }

    private static double round(float v) {
        return Math.round(v * 1e5) / 1e5;
    }

    private void closeRuntime() {
        if (instance != null) instance.close();
        if (graph != null) graph.close();
        instance = null;
        graph = null;
        boundSlots.clear();
    }

    private void closeCharacter() {
        if (character != null) character.close();
        character = null;
        embeddedClips.values().forEach(AnimClipDocument::close);
        embeddedClips.clear();
    }

    @Override
    public void close() {
        closeRuntime();
        closeCharacter();
        fileClips.values().forEach(c -> {
            if (c != null) c.close();
        });
        fileClips.clear();
        doc.close();
    }

    /** The parameters of the document's view: name -> type. */
    public Map<String, String> parameterTypes() {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            for (JsonNode p : JSON.readTree(doc.view()).path("parameters")) out.put(p.path("name").asText(), p.path("type").asText());
        } catch (IOException ignored) {
            // empty
        }
        return out;
    }

    /** All slot names of the document's view. */
    public List<String> slotNames() {
        List<String> out = new ArrayList<>();
        try {
            for (JsonNode s : JSON.readTree(doc.view()).path("slots")) out.add(s.asText());
        } catch (IOException ignored) {
            // empty
        }
        return out;
    }
}
