package com.openmason.main.systems.particles.fx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openmason.engine.rendering.particles.FxEmitter;
import com.openmason.engine.rendering.particles.FxParameter;
import com.openmason.engine.rendering.particles.FxPresets;
import com.openmason.engine.rendering.particles.FxSystem;
import com.openmason.engine.rendering.particles.ParticleEffect;
import com.openmason.engine.rendering.particles.ParticleLibrary;
import com.openmason.engine.rendering.particles.graph.FxAttribute;
import com.openmason.engine.rendering.particles.graph.FxGraph;
import com.openmason.engine.rendering.particles.graph.FxGraphJson;
import com.openmason.engine.rendering.particles.graph.FxModule;
import com.openmason.engine.rendering.particles.graph.FxStage;
import com.openmason.engine.rendering.particles.graph.FxStageKind;
import com.openmason.engine.rendering.particles.omfx.OmfxCodec;
import com.openmason.engine.rendering.particles.omfx.OmfxDocument;
import com.openmason.engine.rendering.particles.omfx.OmfxMigration;
import com.openmason.engine.rendering.particles.omfx.OmfxSprite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * The FX editor's document (OM #104): one {@code .omfx} v2 {@link OmfxDocument} with undo/redo, edited the way the
 * {@code fx_*} MCP tools edit it - through the system's JSON view ({@link OmfxCodec#toSystemJson}) and the strict
 * reader ({@link OmfxCodec#withSystemJson}), so every edit is all-or-nothing and names stay the references. GL- and
 * ImGui-free.
 *
 * <p><b>Undo.</b> Each edit is one step unless it carries the merge key of the step before it and the history was
 * not {@link #seal sealed} in between: a slider drag is one step. The open graph draft is part of each step.
 *
 * <p><b>Recompile triggers.</b> Every edit reports a {@link Change}: the effects whose definition changed (so the
 * preview hot-swaps exactly those, see {@link FxHotSwap}) and whether the system changed at all.
 *
 * <p><b>Graph drafts.</b> A custom module opened in the graph editor ({@link #openGraph}) is edited as an
 * {@link FxGraphDraft}; whenever the draft lowers to a module that compiles, the module in the document follows
 * it. Until then the document keeps the last working module and {@link #draftProblems} says why per node. Node
 * positions live in the archive entry {@value #LAYOUT_FILE} (an extra file the format keeps as it is).
 */
public final class FxEditorDocument {

    /** Undo depth. */
    public static final int HISTORY = 200;
    /** Archive entry holding graph node positions. */
    public static final String LAYOUT_FILE = "editor/graph-layout.json";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Where a module sits: an effect's stage stack, at {@code index}. */
    public record ModuleRef(String effect, FxStageKind stage, int index) {
        public ModuleRef {
            Objects.requireNonNull(effect, "effect");
            Objects.requireNonNull(stage, "stage");
        }
    }

    /** What an edit changed. */
    public record Change(Set<String> effects, boolean system) {
        public static final Change NONE = new Change(Set.of(), false);

        public Change {
            effects = Set.copyOf(effects);
        }

        public boolean any() {
            return system || !effects.isEmpty();
        }
    }

    private record State(OmfxDocument doc, ModuleRef draftRef, FxGraphDraft draft) {
    }

    private OmfxDocument doc;
    private Path file;
    private boolean dirty;
    private ModuleRef draftRef;
    private FxGraphDraft draft;
    private Map<String, String> draftProblems = Map.of();
    private final Deque<State> undo = new ArrayDeque<>();
    private final Deque<State> redo = new ArrayDeque<>();
    private String mergeKey;

    private FxEditorDocument(OmfxDocument doc, Path file, boolean dirty) {
        this.doc = Objects.requireNonNull(doc, "doc");
        this.file = file;
        this.dirty = dirty;
    }

    // ------------------------------------------------------------------------------------------------
    // Creating, opening, saving
    // ------------------------------------------------------------------------------------------------

    /** Wraps an existing document ({@code file} = where it saves; null = unsaved). */
    public static FxEditorDocument of(OmfxDocument doc, Path file) {
        return new FxEditorDocument(doc, file, false);
    }

    /** The FX and effect presets a new document can start from. */
    public static List<String> presets() {
        List<String> out = new ArrayList<>();
        FxPresets.ALL.forEach(s -> out.add(s.name()));
        ParticleLibrary.Presets.ALL.forEach(e -> out.add(e.name()));
        return out;
    }

    /**
     * A new document started from {@code preset} (an FX or effect preset name): no file yet, and not dirty until
     * it is edited (an untouched preset has nothing to lose).
     */
    public static FxEditorDocument create(String preset) {
        return new FxEditorDocument(OmfxDocument.of(presetSystem(preset)), null, false);
    }

    static FxSystem presetSystem(String preset) {
        for (FxSystem s : FxPresets.ALL) {
            if (s.name().equals(preset)) {
                return s;
            }
        }
        ParticleEffect e = presetEffect(preset);
        return FxSystem.of(e, OmfxMigration.defaultBurst(e));
    }

    static ParticleEffect presetEffect(String preset) {
        for (ParticleEffect e : ParticleLibrary.Presets.ALL) {
            if (e.name().equals(preset)) {
                return e;
            }
        }
        throw new IllegalArgumentException("no preset '" + preset + "' (known: " + presets() + ")");
    }

    /**
     * Opens a v2 archive. A v1 particle library is refused with a reason; {@link #migrate} lists the documents it
     * becomes, one per effect, to pick from.
     */
    public static FxEditorDocument open(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        if (!OmfxCodec.isArchive(bytes)) {
            throw new IllegalArgumentException(path.getFileName() + " is a v1 particle library, not an FX document");
        }
        return new FxEditorDocument(OmfxCodec.read(bytes), path, false);
    }

    /** The documents a v1 library at {@code path} migrates to (sprite files next to it embedded), with warnings. */
    public static OmfxMigration.Result migrate(Path path) throws IOException {
        Path folder = path.toAbsolutePath().getParent();
        return OmfxMigration.load(Files.readAllBytes(path),
                OmfxMigration.Options.defaults().withSprites(OmfxMigration.files(folder)));
    }

    /** Writes the document to {@code path} (atomically) and makes it the document's file. */
    public void save(Path path) throws IOException {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        OmfxCodec.write(doc, tmp);
        Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        file = path;
        dirty = false;
        seal();
    }

    // ------------------------------------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------------------------------------

    public OmfxDocument document() {
        return doc;
    }

    public FxSystem system() {
        return doc.system();
    }

    public String id() {
        return doc.id();
    }

    public Optional<Path> file() {
        return Optional.ofNullable(file);
    }

    public boolean isDirty() {
        return dirty;
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    /** Ends the current merge: the next edit is its own undo step (an ImGui item was released). */
    public void seal() {
        mergeKey = null;
    }

    /** The editable JSON view (a fresh copy). */
    public ObjectNode view() {
        return OmfxCodec.toSystemJson(doc);
    }

    public FxEmitter emitter(String name) {
        for (FxEmitter e : doc.system().emitters()) {
            if (e.name().equals(name)) {
                return e;
            }
        }
        return null;
    }

    /** The effect called {@code name} (emitters' and their sub-emitters'), or null. */
    public ParticleEffect effect(String name) {
        for (ParticleEffect e : doc.effects()) {
            if (e.name().equals(name)) {
                return e;
            }
        }
        return null;
    }

    /** Emitters that play {@code effect} directly. */
    public List<String> emittersPlaying(String effect) {
        List<String> out = new ArrayList<>();
        for (FxEmitter e : doc.system().emitters()) {
            if (e.effect().name().equals(effect)) {
                out.add(e.name());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------
    // The edit core
    // ------------------------------------------------------------------------------------------------

    /**
     * Edits the JSON view and re-reads it. Throws {@link IllegalArgumentException} with the reader's reason when the
     * result is not a valid document (nothing changes then).
     */
    public Change edit(String key, Consumer<ObjectNode> mutate) {
        ObjectNode view = view();
        mutate.accept(view);
        OmfxDocument next;
        try {
            next = OmfxCodec.withSystemJson(doc, view);
        } catch (IOException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        return commit(key, next, draftRef, draft);
    }

    /** Replaces the whole document (sprites, extensions, extra files) as one step. */
    public Change replace(String key, OmfxDocument next) {
        return commit(key, Objects.requireNonNull(next, "next"), draftRef, draft);
    }

    private Change commit(String key, OmfxDocument next, ModuleRef nextRef, FxGraphDraft nextDraft) {
        next = FxHotSwap.canonicalize(doc, next);
        boolean docSame = next.equals(doc);
        if (docSame && Objects.equals(nextDraft, draft) && Objects.equals(nextRef, draftRef)) {
            return Change.NONE;
        }
        boolean merge = key != null && key.equals(mergeKey) && !undo.isEmpty();
        if (!merge) {
            undo.push(new State(doc, draftRef, draft));
            while (undo.size() > HISTORY) {
                undo.removeLast();
            }
        }
        redo.clear();
        mergeKey = key;
        OmfxDocument before = doc;
        doc = next;
        draftRef = nextRef;
        draft = nextDraft;
        if (!docSame) {
            dirty = true;
        }
        syncDraft();
        return diff(before, next);
    }

    public Change undo() {
        return step(undo, redo);
    }

    public Change redo() {
        return step(redo, undo);
    }

    private Change step(Deque<State> from, Deque<State> to) {
        if (from.isEmpty()) {
            return Change.NONE;
        }
        to.push(new State(doc, draftRef, draft));
        State s = from.pop();
        OmfxDocument before = doc;
        doc = s.doc();
        draftRef = s.draftRef();
        draft = s.draft();
        dirty = true;
        mergeKey = null;
        syncDraft();
        return diff(before, doc);
    }

    /** The effects whose definition differs between two documents (by name), and whether the system changed. */
    static Change diff(OmfxDocument a, OmfxDocument b) {
        Map<String, ParticleEffect> before = new HashMap<>();
        for (ParticleEffect e : a.effects()) {
            before.put(e.name(), e);
        }
        Set<String> changed = new LinkedHashSet<>();
        for (ParticleEffect e : b.effects()) {
            ParticleEffect old = before.get(e.name());
            if (old != null && !old.equals(e)) {
                changed.add(e.name());
            }
        }
        return new Change(changed, !a.system().equals(b.system()));
    }

    // ------------------------------------------------------------------------------------------------
    // Emitters
    // ------------------------------------------------------------------------------------------------

    /** Adds an emitter playing a copy of preset {@code effectPreset}; returns its name. */
    public String addEmitter(String effectPreset) {
        ParticleEffect preset = presetEffect(effectPreset);
        String[] made = new String[1];
        edit(null, v -> {
            List<String> effects = FxSystemViews.names(v.get("effects"));
            String effectName = FxSystemViews.uniqueName(effects, preset.name());
            ObjectNode effect = ParticleLibrary.toJsonNode(preset.toBuilder().name(effectName).build());
            ((ArrayNode) v.get("effects")).add(effect);
            for (JsonNode sub : effect.path("subEmitters")) {
                // Preset sub-emitter targets come along under their own names (or the ones already there).
                String child = sub.path("effect").asText();
                if (FxSystemViews.find(v.get("effects"), child) == null) {
                    ((ArrayNode) v.get("effects")).add(ParticleLibrary.toJsonNode(presetEffect(child)));
                }
            }
            String name = FxSystemViews.uniqueName(FxSystemViews.names(v.get("emitters")), effectName);
            ObjectNode em = emitterJson(FxEmitter.of(name, preset, OmfxMigration.defaultBurst(preset)), effectName);
            ((ArrayNode) v.get("emitters")).add(em);
            made[0] = name;
        });
        return made[0];
    }

    private ObjectNode emitterJson(FxEmitter e, String effectName) {
        // The view's emitter shape, via a one-emitter system.
        ObjectNode view = OmfxCodec.toSystemJson(OmfxDocument.of(FxSystem.builder("x").emitter(e).build()));
        ObjectNode em = (ObjectNode) view.get("emitters").get(0);
        em.put("effect", effectName);
        return em;
    }

    /** Duplicates an emitter together with its effect (so the copy can be edited on its own); returns its name. */
    public String duplicateEmitter(String name) {
        String[] made = new String[1];
        edit(null, v -> {
            ObjectNode source = (ObjectNode) require(v.get("emitters"), "emitter", name);
            ObjectNode copy = source.deepCopy();
            String effectName = copy.path("effect").asText();
            ObjectNode effect = ((ObjectNode) require(v.get("effects"), "effect", effectName)).deepCopy();
            String newEffect = FxSystemViews.uniqueName(FxSystemViews.names(v.get("effects")), effectName);
            effect.put("name", newEffect);
            ((ArrayNode) v.get("effects")).add(effect);
            String newName = FxSystemViews.uniqueName(FxSystemViews.names(v.get("emitters")), name);
            copy.put("name", newName);
            copy.put("effect", newEffect);
            ArrayNode emitters = (ArrayNode) v.get("emitters");
            emitters.insert(FxSystemViews.indexOf(emitters, name) + 1, copy);
            made[0] = newName;
        });
        return made[0];
    }

    /**
     * Removes an emitter, the bindings/handlers/cues that name it and an effect nothing plays any more; returns
     * how many of those references went. The last emitter cannot go (a system has at least one).
     */
    public int removeEmitter(String name) {
        int[] dropped = new int[1];
        edit(null, v -> {
            ArrayNode emitters = (ArrayNode) v.get("emitters");
            int i = FxSystemViews.indexOf(emitters, name);
            if (i < 0) {
                throw new IllegalArgumentException("no emitter '" + name + "'");
            }
            if (emitters.size() == 1) {
                throw new IllegalArgumentException("a system needs at least one emitter");
            }
            emitters.remove(i);
            dropped[0] = FxSystemViews.dropEmitterReferences(v, name);
            FxSystemViews.removeNames(v.get("effects"), FxSystemViews.unusedEffects(v));
        });
        return dropped[0];
    }

    public Change renameEmitter(String from, String to) {
        String target = to == null ? "" : to.strip();
        return edit(null, v -> {
            if (target.isEmpty()) {
                throw new IllegalArgumentException("an emitter name cannot be empty");
            }
            if (target.equals(from)) {
                return;
            }
            if (FxSystemViews.find(v.get("emitters"), target) != null) {
                throw new IllegalArgumentException("an emitter '" + target + "' already exists");
            }
            ((ObjectNode) require(v.get("emitters"), "emitter", from)).put("name", target);
            FxSystemViews.rewriteReferences(v, "emitters", from, target);
        });
    }

    /** Moves an emitter {@code delta} places in the list (draw and timeline order). */
    public Change moveEmitter(String name, int delta) {
        return edit(null, v -> {
            ArrayNode emitters = (ArrayNode) v.get("emitters");
            int i = FxSystemViews.indexOf(emitters, name);
            int j = Math.max(0, Math.min(emitters.size() - 1, i + delta));
            if (i < 0 || i == j) {
                return;
            }
            JsonNode n = emitters.remove(i);
            emitters.insert(j, n);
        });
    }

    /** Sets one member of an emitter's JSON (timeline, offset, scales, tint, direction). */
    public Change setEmitterField(String name, String field, JsonNode value) {
        return edit("emitter:" + name + ":" + field, v ->
                ((ObjectNode) require(v.get("emitters"), "emitter", name)).set(field, value));
    }

    // ------------------------------------------------------------------------------------------------
    // Effects and their module stacks
    // ------------------------------------------------------------------------------------------------

    /**
     * Replaces effect {@code name} with {@code next} (built from it in the inspector); a new name renames it and
     * everything that plays or spawns it follows. {@code key} merges a drag into one step.
     */
    public Change putEffect(String key, String name, ParticleEffect next) {
        String newName = next.name();
        Change c = edit(key, v -> {
            ArrayNode effects = (ArrayNode) v.get("effects");
            int i = FxSystemViews.indexOf(effects, name);
            if (i < 0) {
                throw new IllegalArgumentException("no effect '" + name + "'");
            }
            if (!newName.equals(name) && FxSystemViews.find(effects, newName) != null) {
                throw new IllegalArgumentException("an effect '" + newName + "' already exists");
            }
            effects.set(i, ParticleLibrary.toJsonNode(next));
            if (!newName.equals(name)) {
                FxSystemViews.rewriteReferences(v, "effects", name, newName);
            }
        });
        if (!newName.equals(name) && draftRef != null && draftRef.effect().equals(name)) {
            draftRef = new ModuleRef(newName, draftRef.stage(), draftRef.index());
        }
        return c;
    }

    /** The modules stage {@code kind} of effect {@code effect} runs (the default stack when it lists none). */
    public List<FxModule> stack(String effect, FxStageKind kind) {
        ParticleEffect e = requireEffect(effect);
        return e.graph() == null ? FxGraph.defaultStack(kind) : e.graph().stack(kind);
    }

    /** Custom attributes of {@code effect}'s graph. */
    public List<FxAttribute> attributes(String effect) {
        ParticleEffect e = requireEffect(effect);
        return e.graph() == null ? List.of() : e.graph().attributes();
    }

    /** Replaces stage {@code kind} of {@code effect} (a stack equal to the default is written as "default"). */
    public Change setStack(String effect, FxStageKind kind, List<FxModule> modules) {
        return setStack(null, effect, kind, modules);
    }

    private Change setStack(String key, String effect, FxStageKind kind, List<FxModule> modules) {
        ParticleEffect e = requireEffect(effect);
        FxGraph g = e.graph() == null ? FxGraph.of(List.of()) : e.graph();
        List<FxStage> stages = new ArrayList<>();
        for (FxStage s : g.stages()) {
            if (s.kind() != kind) {
                stages.add(s);
            }
        }
        if (!modules.equals(FxGraph.defaultStack(kind))) {
            stages.add(new FxStage(kind, modules));
            stages.sort(java.util.Comparator.comparing(FxStage::kind));
        }
        return setGraph(key, effect, new FxGraph(FxGraph.VERSION, g.attributes(), stages));
    }

    /** Replaces {@code effect}'s custom attributes. */
    public Change setAttributes(String effect, List<FxAttribute> attributes) {
        ParticleEffect e = requireEffect(effect);
        FxGraph g = e.graph() == null ? FxGraph.of(List.of()) : e.graph();
        return setGraph(null, effect, new FxGraph(FxGraph.VERSION, attributes, g.stages()));
    }

    private Change setGraph(String key, String effect, FxGraph graph) {
        boolean empty = graph.stages().isEmpty() && graph.attributes().isEmpty();
        return edit(key, v -> {
            ObjectNode n = (ObjectNode) require(v.get("effects"), "effect", effect);
            if (empty) {
                n.remove("graph");
            } else {
                n.set("graph", FxGraphJson.toJson(graph));
            }
        });
    }

    public Change addModule(String effect, FxStageKind kind, FxModule module, int index) {
        List<FxModule> s = new ArrayList<>(stack(effect, kind));
        s.add(Math.max(0, Math.min(s.size(), index)), module);
        return setStack(effect, kind, s);
    }

    public Change removeModule(String effect, FxStageKind kind, int index) {
        List<FxModule> s = new ArrayList<>(stack(effect, kind));
        s.remove(index);
        return setStack(effect, kind, s);
    }

    public Change moveModule(String effect, FxStageKind kind, int index, int delta) {
        List<FxModule> s = new ArrayList<>(stack(effect, kind));
        int j = Math.max(0, Math.min(s.size() - 1, index + delta));
        if (j == index) {
            return Change.NONE;
        }
        s.add(j, s.remove(index));
        return setStack(effect, kind, s);
    }

    public FxModule module(ModuleRef ref) {
        ParticleEffect e = effect(ref.effect());
        if (e == null) {
            return null;
        }
        List<FxModule> s = e.graph() == null ? FxGraph.defaultStack(ref.stage()) : e.graph().stack(ref.stage());
        return ref.index() >= 0 && ref.index() < s.size() ? s.get(ref.index()) : null;
    }

    private ParticleEffect requireEffect(String name) {
        ParticleEffect e = effect(name);
        if (e == null) {
            throw new IllegalArgumentException("no effect '" + name + "'");
        }
        return e;
    }

    // ------------------------------------------------------------------------------------------------
    // Parameters, bindings, events
    // ------------------------------------------------------------------------------------------------

    /** Adds or replaces a parameter ({@code previous} = its old name for a rename, null to add). */
    public Change putParameter(String key, String previous, FxParameter p) {
        ObjectNode one = (ObjectNode) OmfxCodec.toSystemJson(OmfxDocument.of(FxSystem.builder("x")
                .emitter(doc.system().emitters().get(0)).parameter(p).build())).get("parameters").get(0);
        return edit(key, v -> {
            ArrayNode params = (ArrayNode) v.get("parameters");
            int i = previous == null ? -1 : FxSystemViews.indexOf(params, previous);
            if (!p.name().equals(previous) && FxSystemViews.find(params, p.name()) != null) {
                throw new IllegalArgumentException("a parameter '" + p.name() + "' already exists");
            }
            if (i < 0) {
                params.add(one);
            } else {
                params.set(i, one);
                if (!p.name().equals(previous)) {
                    FxSystemViews.rewriteReferences(v, "parameters", previous, p.name());
                }
            }
        });
    }

    /** Removes a parameter and its bindings; returns how many bindings went. */
    public int removeParameter(String name) {
        int[] dropped = new int[1];
        edit(null, v -> {
            ArrayNode params = (ArrayNode) v.get("parameters");
            int i = FxSystemViews.indexOf(params, name);
            if (i < 0) {
                throw new IllegalArgumentException("no parameter '" + name + "'");
            }
            params.remove(i);
            dropped[0] = FxSystemViews.dropParameterReferences(v, name);
        });
        return dropped[0];
    }

    /** Appends an entry to a replaced section ({@code bindings}, {@code events}, {@code cues}). */
    public Change addEntry(String section, ObjectNode entry) {
        return edit(null, v -> ((ArrayNode) v.get(section)).add(entry.deepCopy()));
    }

    public Change removeEntry(String section, int index) {
        return edit(null, v -> ((ArrayNode) v.get(section)).remove(index));
    }

    /** Sets one member of entry {@code index} of a replaced section. */
    public Change setEntryField(String section, int index, String field, JsonNode value) {
        return edit(section + ":" + index + ":" + field, v -> ((ObjectNode) v.get(section).get(index)).set(field, value));
    }

    /** Replaces the light (null removes it). */
    public Change setLight(String key, JsonNode light) {
        return edit(key, v -> v.set("light", light == null ? null : light.deepCopy()));
    }

    // ------------------------------------------------------------------------------------------------
    // Sprites
    // ------------------------------------------------------------------------------------------------

    /** Embeds a PNG/OMT as sprite {@code name} (replacing one of that name); the bytes must decode. */
    public Change embedSprite(String name, byte[] bytes) throws IOException {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a sprite needs a name");
        }
        OmfxSprite sprite = new OmfxSprite(name.strip(), bytes);
        sprite.decode();
        return replace(null, doc.withSprite(sprite));
    }

    public Change removeSprite(String name) {
        return replace(null, doc.withoutSprite(name));
    }

    /** Effects whose texture list names {@code sprite}. */
    public List<String> spriteUsers(String sprite) {
        List<String> out = new ArrayList<>();
        for (ParticleEffect e : doc.effects()) {
            if (e.textures().contains(sprite)) {
                out.add(e.name());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------
    // Graph drafts
    // ------------------------------------------------------------------------------------------------

    public ModuleRef draftRef() {
        return draftRef;
    }

    public FxGraphDraft draft() {
        return draft;
    }

    /** Why the open draft is not (yet) the document's module: node id ("" = the whole graph) -> reason. */
    public Map<String, String> draftProblems() {
        return draftProblems;
    }

    /**
     * Opens the custom module at {@code ref} in the graph editor (view state, not an undo step); null closes it.
     * Built-in modules have no graph.
     */
    public void openGraph(ModuleRef ref) {
        if (ref == null) {
            draftRef = null;
            draft = null;
            draftProblems = Map.of();
            return;
        }
        FxModule m = module(ref);
        if (m == null) {
            throw new IllegalArgumentException("no module at " + ref);
        }
        if (m.isBuiltin()) {
            throw new IllegalArgumentException("'" + m.name() + "' is built in: its settings are fields, not a graph");
        }
        draftRef = ref;
        draft = FxGraphDraft.of(m, layoutOf(ref, m.name()));
        draftProblems = Map.of();
    }

    /**
     * Applies {@code op} to the open draft. When the result lowers and compiles, the module in the document
     * becomes it (positions saved too); otherwise only the draft changes and {@link #draftProblems} says why.
     * Either way it is one undo step (merged with the previous one under the same {@code key}).
     */
    public Change editGraph(String key, UnaryOperator<FxGraphDraft> op) {
        if (draft == null) {
            throw new IllegalStateException("no graph is open");
        }
        FxGraphDraft next = op.apply(draft);
        FxGraphDraft.Lowered lowered = next.lower();
        if (!lowered.ok()) {
            commit(key, doc, draftRef, next);
            draftProblems = lowered.problems();
            return Change.NONE;
        }
        OmfxDocument withLayout = withLayout(doc, draftRef, next);
        ModuleRef ref = draftRef;
        try {
            Change c = applyModule(key, ref, lowered.module(), withLayout, next);
            draftProblems = Map.of();
            return c;
        } catch (IllegalArgumentException compile) {
            commit(key, doc, draftRef, next);
            draftProblems = compileProblems(next.name(), compile.getMessage());
            return Change.NONE;
        }
    }

    private Change applyModule(String key, ModuleRef ref, FxModule module, OmfxDocument base, FxGraphDraft nextDraft) {
        ParticleEffect e = requireEffect(ref.effect());
        List<FxModule> s = new ArrayList<>(e.graph() == null ? FxGraph.defaultStack(ref.stage())
                : e.graph().stack(ref.stage()));
        s.set(ref.index(), module);
        FxGraph g = e.graph() == null ? FxGraph.of(List.of()) : e.graph();
        List<FxStage> stages = new ArrayList<>();
        for (FxStage st : g.stages()) {
            if (st.kind() != ref.stage()) {
                stages.add(st);
            }
        }
        stages.add(new FxStage(ref.stage(), s));
        stages.sort(java.util.Comparator.comparing(FxStage::kind));
        FxGraph graph = new FxGraph(FxGraph.VERSION, g.attributes(), stages);
        ObjectNode view = OmfxCodec.toSystemJson(base);
        ((ObjectNode) require(view.get("effects"), "effect", ref.effect())).set("graph", FxGraphJson.toJson(graph));
        OmfxDocument next;
        try {
            next = OmfxCodec.withSystemJson(base, view);
        } catch (IOException ex) {
            throw new IllegalArgumentException(ex.getMessage(), ex);
        }
        return commit(key, next, ref, nextDraft);
    }

    /** Compiler messages name nodes {@code <module>.<node>}; pin them on the draft's nodes. */
    static Map<String, String> compileProblems(String module, String message) {
        String msg = message == null ? "does not compile" : message;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("node '([^']+)': (.*)$").matcher(msg);
        if (m.find()) {
            String full = m.group(1);
            String prefix = module + ".";
            String node = full.startsWith(prefix) ? full.substring(prefix.length()) : "";
            return Map.of(node, node.isEmpty() ? msg : m.group(2));
        }
        return Map.of("", msg);
    }

    /** Keeps the draft in step after an edit or undo moved things under it. */
    private void syncDraft() {
        if (draftRef == null) {
            return;
        }
        FxModule m = module(draftRef);
        if (m == null || m.isBuiltin()) {
            draftRef = null;
            draft = null;
            draftProblems = Map.of();
            return;
        }
        if (draft == null || !draft.name().equals(m.name())) {
            draft = FxGraphDraft.of(m, layoutOf(draftRef, m.name()));
            draftProblems = Map.of();
            return;
        }
        FxGraphDraft.Lowered lowered = draft.lower();
        if (lowered.ok() && !lowered.module().equals(m)) {
            // The document moved under a finished draft (an edit elsewhere in the stack): follow the document.
            FxGraphDraft fresh = FxGraphDraft.of(m, draft.layout());
            if (!fresh.lower().ok() || !fresh.lower().module().equals(lowered.module())) {
                draft = fresh;
            }
        }
        draftProblems = lowered.ok() ? Map.of() : lowered.problems();
    }

    // ------------------------------------------------------------------------------------------------
    // Node layout (an extra archive entry)
    // ------------------------------------------------------------------------------------------------

    static String layoutKey(ModuleRef ref, String moduleName) {
        return ref.effect() + "|" + ref.stage().name().toLowerCase(java.util.Locale.ROOT) + "|" + ref.index() + "|"
                + moduleName;
    }

    /** Saved node positions of a module (empty when none). */
    public Map<String, float[]> layoutOf(ModuleRef ref, String moduleName) {
        Map<String, float[]> out = new LinkedHashMap<>();
        JsonNode node = readLayout(doc).path("modules").path(layoutKey(ref, moduleName));
        for (var it = node.fields(); it.hasNext(); ) {
            var f = it.next();
            if (f.getValue().isArray() && f.getValue().size() == 2) {
                out.put(f.getKey(), new float[]{(float) f.getValue().get(0).asDouble(),
                        (float) f.getValue().get(1).asDouble()});
            }
        }
        return out;
    }

    private static ObjectNode readLayout(OmfxDocument d) {
        byte[] bytes = d.extraFiles().get(LAYOUT_FILE);
        if (bytes != null) {
            try {
                JsonNode n = JSON.readTree(bytes);
                if (n instanceof ObjectNode o) {
                    return o;
                }
            } catch (IOException ignored) {
                // a broken layout file only costs positions
            }
        }
        ObjectNode fresh = JSON.createObjectNode();
        fresh.put("version", 1);
        fresh.putObject("modules");
        return fresh;
    }

    private static OmfxDocument withLayout(OmfxDocument d, ModuleRef ref, FxGraphDraft draft) {
        ObjectNode layout = readLayout(d);
        ObjectNode modules = layout.with("modules");
        ObjectNode entry = JSON.createObjectNode();
        for (FxGraphDraft.DraftNode n : draft.nodes()) {
            entry.putArray(n.id()).add(Math.round(n.x())).add(Math.round(n.y()));
        }
        modules.set(layoutKey(ref, draft.name()), entry);
        Map<String, byte[]> extras = new LinkedHashMap<>(d.extraFiles());
        try {
            extras.put(LAYOUT_FILE, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(layout)
                    .getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return new OmfxDocument(d.author(), d.system(), d.sprites(), d.soundFiles(), d.extensions(), extras);
    }

    private static JsonNode require(JsonNode list, String what, String name) {
        JsonNode n = FxSystemViews.find(list, name);
        if (n == null) {
            throw new IllegalArgumentException("no " + what + " '" + name + "'");
        }
        return n;
    }
}
