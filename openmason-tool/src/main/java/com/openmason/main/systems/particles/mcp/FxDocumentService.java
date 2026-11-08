package com.openmason.main.systems.particles.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openmason.engine.format.omt.OMTReader;
import com.openmason.engine.format.omt.TextureBytes;
import com.openmason.engine.rendering.particles.FxEmitter;
import com.openmason.engine.rendering.particles.FxPresets;
import com.openmason.engine.rendering.particles.FxSystem;
import com.openmason.engine.rendering.particles.ParticleEffect;
import com.openmason.engine.rendering.particles.ParticleLibrary;
import com.openmason.engine.rendering.particles.omfx.OmfxCodec;
import com.openmason.engine.rendering.particles.omfx.OmfxDocument;
import com.openmason.engine.rendering.particles.omfx.OmfxMigration;
import com.openmason.engine.rendering.particles.omfx.OmfxSprite;
import com.openmason.main.systems.io.AssetWriteService;
import com.openmason.main.systems.io.AssetWriteService.WriteOutcome;
import com.openmason.main.systems.io.AssetWriteService.WriteRequest;
import com.openmason.main.systems.io.BuiltinFileTypes;
import com.openmason.main.systems.io.WriteKind;
import com.openmason.main.systems.mcp.McpAck;
import com.openmason.main.systems.mcp.McpErrors;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The MCP's FX documents (OM #101 {@code .omfx} v2): open in memory by id (the system name), edited through the
 * format's own editable {@code system.json} view ({@link OmfxCodec#toSystemJson}/{@link OmfxCodec#withSystemJson}),
 * so an agent reads and writes exactly the file's JSON and every edit passes the file reader's validation
 * (all-or-nothing: a rejected edit changes nothing). Undo/redo per document. GL-free and editor-free: the Particles
 * tab edits v1 libraries until OM #104, so these documents are the agent's own; files are written through the
 * {@link AssetWriteService} sandbox. Thread-safe (one lock; saves wait on the main thread outside it).
 */
public final class FxDocumentService {

    /** Undo depth per document. */
    static final int HISTORY = 64;
    static final WriteKind KIND = WriteKind.of(BuiltinFileTypes.OMFX);

    /** Sections of the editable view: named ones are upserted by name, the rest replaced. */
    static final List<String> NAMED = List.of("effects", "emitters", "parameters");
    static final List<String> REPLACED = List.of("bindings", "events", "cues", "sounds");

    private static final class Session {
        OmfxDocument doc;
        Path file;
        boolean dirty;
        String origin;
        final Deque<OmfxDocument> undo = new ArrayDeque<>();
        final Deque<OmfxDocument> redo = new ArrayDeque<>();
    }

    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private final AssetWriteService writes;

    /** @param writes the write sandbox (null: in-memory only, file tools refuse) */
    public FxDocumentService(AssetWriteService writes) {
        this.writes = writes;
    }

    // ------------------------------------------------------------------------------------------------
    // Catalogue and documents
    // ------------------------------------------------------------------------------------------------

    /** The built-in FX systems and effects a document can start from. */
    public Object presets() {
        List<Map<String, Object>> systems = new ArrayList<>();
        for (FxSystem s : FxPresets.ALL) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s.name());
            m.put("emitters", s.emitters().stream().map(FxEmitter::name).toList());
            m.put("parameters", s.parameters().stream().map(p -> p.name() + ":" + p.type().name().toLowerCase())
                    .toList());
            m.put("light", s.light() != null);
            systems.add(m);
        }
        List<Map<String, Object>> effects = new ArrayList<>();
        for (ParticleEffect e : ParticleLibrary.Presets.ALL) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", e.name());
            m.put(e.rate() > 0f ? "rate" : "burst", e.rate() > 0f ? e.rate() : OmfxMigration.defaultBurst(e));
            m.put("life", e.lifetime().min() + ".." + e.lifetime().max());
            if (!e.subEmitters().isEmpty()) {
                m.put("spawns", e.subEmitters().stream().map(s -> s.effect().name()).toList());
            }
            effects.add(m);
        }
        return Map.of("systems", systems, "effects", effects);
    }

    /**
     * A new in-memory document {@code id}: a copy of the FX preset or effect preset {@code preset} (an effect
     * becomes a one-emitter system), or a one-emitter system with a default effect.
     */
    public synchronized Object create(String id, String preset) {
        requireId(id);
        if (sessions.containsKey(id)) {
            throw new IllegalArgumentException("An FX document '" + id + "' is already open (fx_close it, or pick "
                    + "another id)");
        }
        FxSystem system;
        if (preset == null || preset.isBlank()) {
            ParticleEffect effect = ParticleEffect.builder(id).build();
            system = FxSystem.of(effect, OmfxMigration.defaultBurst(effect));
        } else {
            system = presetSystem(preset);
            system = system.toBuilder().name(id).build();
        }
        Session s = new Session();
        s.doc = OmfxDocument.of(system);
        s.dirty = true;
        s.origin = preset == null || preset.isBlank() ? "new" : "preset:" + preset;
        sessions.put(id, s);
        return info(id, s);
    }

    private static FxSystem presetSystem(String preset) {
        for (FxSystem s : FxPresets.ALL) {
            if (s.name().equals(preset)) {
                return s;
            }
        }
        for (ParticleEffect e : ParticleLibrary.Presets.ALL) {
            if (e.name().equals(preset)) {
                return FxSystem.of(e, OmfxMigration.defaultBurst(e));
            }
        }
        List<String> known = new ArrayList<>();
        FxPresets.ALL.forEach(s -> known.add(s.name()));
        ParticleLibrary.Presets.ALL.forEach(e -> known.add(e.name()));
        throw McpErrors.unknownEntity("preset", preset, known, "fx_presets");
    }

    /**
     * Opens an {@code .omfx}: a v2 archive as one document saved back in place, a v1 particle library migrated
     * (one single-emitter document per effect, sprites embedded from files next to it; never saved over the v1
     * file).
     */
    public Object open(String path) throws IOException {
        if (writes == null) {
            throw new IllegalStateException("file access is unavailable (no write sandbox)");
        }
        Path file = writes.sandbox().resolveExisting(path);
        byte[] bytes = Files.readAllBytes(file);
        boolean v2 = OmfxCodec.isArchive(bytes);
        OmfxMigration.Result result = OmfxMigration.load(bytes,
                OmfxMigration.Options.defaults().withSprites(OmfxMigration.files(file.getParent())));
        synchronized (this) {
            for (OmfxDocument d : result.documents()) {
                Session old = sessions.get(d.id());
                if (old != null && old.dirty) {
                    throw new IllegalArgumentException("FX document '" + d.id() + "' is open with unsaved changes: "
                            + "fx_save or fx_close {discard:true} it first");
                }
            }
            List<Object> opened = new ArrayList<>();
            for (OmfxDocument d : result.documents()) {
                Session s = new Session();
                s.doc = d;
                s.file = v2 ? file : null;
                s.dirty = !v2;
                s.origin = v2 ? "file" : "migrated-v1:" + file.getFileName();
                sessions.put(d.id(), s);
                opened.add(info(d.id(), s));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("version", v2 ? 2 : 1);
            out.put("documents", opened);
            if (!result.warnings().isEmpty()) {
                out.put("warnings", result.warnings());
            }
            if (!v2) {
                out.put("note", "v1 library migrated; fx_save writes each system as a new v2 file");
            }
            return out;
        }
    }

    public synchronized Object documents() {
        List<Object> out = new ArrayList<>();
        sessions.forEach((id, s) -> out.add(info(id, s)));
        return Map.of("documents", out);
    }

    /** The document's current state (for simulation/capture). */
    public synchronized OmfxDocument document(String id) {
        return session(id).doc;
    }

    /** The document id {@code id} names (or the only open document when null). */
    public synchronized String resolveId(String id) {
        return idOf(session(id));
    }

    /**
     * The editable view: the whole {@code system.json} shape, or one section ({@code emitters}, {@code light}...,
     * {@code effect:<name>}), plus sprites and external texture names.
     */
    public synchronized Object get(String id, String section) {
        Session s = session(id);
        ObjectNode view = OmfxCodec.toSystemJson(s.doc);
        Map<String, Object> out = new LinkedHashMap<>(info(idOf(s), s));
        if (section == null || section.isBlank()) {
            out.put("system", view);
        } else if (section.startsWith("effect:")) {
            String name = section.substring("effect:".length());
            JsonNode effect = find(view.get("effects"), name);
            if (effect == null) {
                throw McpErrors.unknownEntity("effect", name, names(view.get("effects")), "fx_get");
            }
            out.put("effect", effect);
        } else if (view.has(section)) {
            out.put(section, view.get(section));
        } else {
            List<String> valid = new ArrayList<>();
            view.fieldNames().forEachRemaining(valid::add);
            valid.add("effect:<name>");
            throw McpErrors.invalidEnum("section", section, valid);
        }
        out.put("sprites", sprites(s.doc));
        Set<String> external = s.doc.externalTextures();
        if (!external.isEmpty()) {
            out.put("externalTextures", external);
        }
        if (!s.doc.extensions().keys().isEmpty()) {
            out.put("extensions", s.doc.extensions().keys());
        }
        return out;
    }

    /**
     * Applies {@code edit} atomically: {@code effects}/{@code emitters}/{@code parameters} entries are upserted by
     * {@code name} (members given replace the old ones; {@code rename} renames and rewrites every reference),
     * {@code remove} lists names to drop per section, {@code bindings}/{@code events}/{@code cues}/{@code sounds}
     * replace their section, {@code light} replaces the light (null removes it), {@code author} sets the author.
     */
    public synchronized Object set(String id, JsonNode edit) throws IOException {
        Session s = session(id);
        ObjectNode view = OmfxCodec.toSystemJson(s.doc);
        List<String> changed = new ArrayList<>();
        for (String section : NAMED) {
            if (edit.has(section)) {
                upsert(view, section, edit.get(section));
                changed.add(section);
            }
        }
        JsonNode remove = edit.get("remove");
        if (remove != null && !remove.isNull()) {
            if (!remove.isObject()) {
                throw new IllegalArgumentException("'remove' must be an object like {\"effects\": [\"name\"]}");
            }
            for (var it = remove.fields(); it.hasNext(); ) {
                var e = it.next();
                if (!NAMED.contains(e.getKey())) {
                    throw McpErrors.invalidEnum("remove section", e.getKey(), NAMED);
                }
                removeNamed(view, e.getKey(), e.getValue());
                changed.add("-" + e.getKey());
            }
        }
        for (String section : REPLACED) {
            if (edit.has(section)) {
                JsonNode v = edit.get(section);
                if (!v.isArray()) {
                    throw new IllegalArgumentException("'" + section + "' replaces the whole section: pass an array");
                }
                view.set(section, v.deepCopy());
                changed.add(section);
            }
        }
        if (edit.has("light")) {
            view.set("light", edit.get("light").deepCopy());
            changed.add("light");
        }
        OmfxDocument next = OmfxCodec.withSystemJson(s.doc, view);
        if (edit.hasNonNull("author")) {
            next = next.withAuthor(edit.get("author").asText());
            changed.add("author");
        }
        if (changed.isEmpty()) {
            throw new IllegalArgumentException("Nothing to change: pass effects, emitters, parameters, remove, "
                    + "bindings, events, cues, sounds, light or author");
        }
        commit(s, next);
        return McpAck.ok().with("id", idOf(s)).with("changed", changed)
                .with("emitters", next.system().emitters().size()).with("effects", next.effects().size())
                .with("externalTextures", next.externalTextures().isEmpty() ? null : next.externalTextures());
    }

    /** Embeds the PNG/OMT at {@code path} as sprite {@code name} (replacing one of that name), or removes it. */
    public Object sprite(String id, String name, String path, boolean remove) throws IOException {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("'name' (the texture name effects list) is required");
        }
        byte[] bytes = null;
        if (!remove) {
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("pass 'path' (a .png or .omt) to embed, or remove:true");
            }
            if (writes == null) {
                throw new IllegalStateException("file access is unavailable (no write sandbox)");
            }
            bytes = Files.readAllBytes(writes.sandbox().resolveExisting(path));
            new OmfxSprite(name, bytes).decode(); // fails with a reason for anything but a PNG / OMT
        }
        synchronized (this) {
            Session s = session(id);
            OmfxDocument next;
            if (remove) {
                next = s.doc.withoutSprite(name);
                if (next == s.doc) {
                    throw McpErrors.unknownEntity("sprite", name,
                            s.doc.sprites().stream().map(OmfxSprite::name).toList(), "fx_get");
                }
            } else {
                next = s.doc.withSprite(new OmfxSprite(name, bytes));
            }
            commit(s, next);
            return McpAck.ok().with("id", idOf(s)).with("sprite", name).with("removed", remove ? true : null)
                    .with("usedBy", usedBy(next, name))
                    .with("size", remove ? null : size(next.sprite(name)));
        }
    }

    public synchronized Object undo(String id, boolean redo) {
        Session s = session(id);
        Deque<OmfxDocument> from = redo ? s.redo : s.undo;
        Deque<OmfxDocument> to = redo ? s.undo : s.redo;
        if (from.isEmpty()) {
            throw new IllegalArgumentException("Nothing to " + (redo ? "redo" : "undo") + " in '" + idOf(s) + "'");
        }
        to.push(s.doc);
        s.doc = from.pop();
        s.dirty = true;
        return McpAck.ok().with("id", idOf(s)).with(redo ? "redone" : "undone", true)
                .with("undo", s.undo.size()).with("redo", s.redo.size());
    }

    /**
     * Writes the document as {@code .omfx} v2: back to its own file when it has one and no path is given,
     * otherwise to {@code path} (sandbox roots, e.g. {@code project:Particles/fire.omfx}; a bare name lands in the
     * project's Particles folder) or wherever the user picks ({@code prompt}).
     */
    public Object save(String id, String path, boolean prompt, boolean overwrite) {
        if (writes == null) {
            throw new IllegalStateException("file access is unavailable (no write sandbox)");
        }
        OmfxDocument snapshot;
        Path own;
        String docId;
        synchronized (this) {
            Session s = session(id);
            snapshot = s.doc;
            own = s.file;
            docId = idOf(s);
        }
        AssetWriteService.Writer writer = target -> {
            OmfxCodec.write(snapshot, target);
            return true;
        };
        WriteOutcome outcome;
        if (path == null && !prompt && own != null) {
            outcome = writes.saveInPlace(KIND, own.toString(), writer);
        } else {
            String raw = path != null ? path : (prompt ? null : fileName(docId));
            outcome = writes.save(WriteRequest.of(KIND, raw, prompt, overwrite, fileName(docId))
                    .withDetails(List.of("FX system " + docId + " (.omfx v2)")), writer);
        }
        if (!outcome.ok()) {
            return outcome;
        }
        synchronized (this) {
            Session s = sessions.get(docId);
            if (s != null) {
                s.file = Path.of(outcome.path());
                s.dirty = s.doc != snapshot; // edits made while the write waited stay unsaved
                s.origin = "file";
            }
        }
        return McpAck.ok().with("id", docId).with("saved", outcome.path())
                .with("promptedUser", Boolean.TRUE.equals(outcome.promptedUser()));
    }

    public synchronized Object close(String id, boolean discard) {
        Session s = session(id);
        String docId = idOf(s);
        if (s.dirty && !discard) {
            throw new IllegalArgumentException("'" + docId + "' has unsaved changes: fx_save it, or fx_close "
                    + "{discard:true}");
        }
        sessions.remove(docId);
        return McpAck.ok().with("closed", docId).with("open", sessions.size());
    }

    // ------------------------------------------------------------------------------------------------
    // Editable-view helpers
    // ------------------------------------------------------------------------------------------------

    private void upsert(ObjectNode view, String section, JsonNode patches) {
        if (!patches.isArray()) {
            throw new IllegalArgumentException("'" + section + "' must be an array of objects with a 'name'");
        }
        ArrayNode list = (ArrayNode) view.get(section);
        for (JsonNode patch : patches) {
            if (!patch.isObject() || !patch.path("name").isTextual() || patch.get("name").asText().isBlank()) {
                throw new IllegalArgumentException("every '" + section + "' entry needs a 'name'");
            }
            String name = patch.get("name").asText();
            ObjectNode target = (ObjectNode) find(list, name);
            if (target == null) {
                target = list.addObject();
            }
            for (var it = patch.fields(); it.hasNext(); ) {
                var f = it.next();
                if (!f.getKey().equals("rename")) {
                    target.set(f.getKey(), f.getValue().deepCopy());
                }
            }
            JsonNode rename = patch.get("rename");
            if (rename != null && !rename.isNull()) {
                String to = rename.asText();
                if (to.isBlank()) {
                    throw new IllegalArgumentException("'rename' must be a non-blank name");
                }
                if (find(list, to) != null) {
                    throw new IllegalArgumentException(section + ": '" + to + "' already exists");
                }
                target.put("name", to);
                rewriteReferences(view, section, name, to);
            }
        }
    }

    /** After a rename, every place the old name was referenced from follows it. */
    private static void rewriteReferences(ObjectNode view, String section, String from, String to) {
        switch (section) {
            case "effects" -> {
                for (JsonNode e : view.get("emitters")) {
                    replaceText((ObjectNode) e, "effect", from, to);
                }
                for (JsonNode e : view.get("effects")) {
                    for (JsonNode sub : e.path("subEmitters")) {
                        replaceText((ObjectNode) sub, "effect", from, to);
                    }
                }
            }
            case "emitters" -> {
                for (JsonNode b : view.get("bindings")) {
                    replaceText((ObjectNode) b, "emitter", from, to);
                }
                for (JsonNode h : view.get("events")) {
                    if (!"custom".equalsIgnoreCase(h.path("trigger").asText())) {
                        replaceText((ObjectNode) h, "source", from, to);
                    }
                    replaceText((ObjectNode) h, "spawnInto", from, to);
                }
                for (JsonNode c : view.get("cues")) {
                    replaceText((ObjectNode) c, "emitter", from, to);
                }
            }
            case "parameters" -> {
                for (JsonNode b : view.get("bindings")) {
                    replaceText((ObjectNode) b, "parameter", from, to);
                }
            }
            default -> { }
        }
    }

    private static void replaceText(ObjectNode n, String key, String from, String to) {
        if (from.equals(n.path(key).asText(null))) {
            n.put(key, to);
        }
    }

    private static void removeNamed(ObjectNode view, String section, JsonNode names) {
        if (!names.isArray()) {
            throw new IllegalArgumentException("remove." + section + " must be an array of names");
        }
        ArrayNode list = (ArrayNode) view.get(section);
        for (JsonNode n : names) {
            String name = n.asText();
            boolean found = false;
            for (int i = 0; i < list.size(); i++) {
                if (name.equals(list.get(i).path("name").asText())) {
                    list.remove(i);
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw McpErrors.unknownEntity(section.substring(0, section.length() - 1), name, names(list), "fx_get");
            }
        }
    }

    private static JsonNode find(JsonNode list, String name) {
        if (list == null) {
            return null;
        }
        for (JsonNode n : list) {
            if (name.equals(n.path("name").asText())) {
                return n;
            }
        }
        return null;
    }

    private static List<String> names(JsonNode list) {
        List<String> out = new ArrayList<>();
        if (list != null) {
            list.forEach(n -> out.add(n.path("name").asText()));
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------
    // Sessions
    // ------------------------------------------------------------------------------------------------

    private void commit(Session s, OmfxDocument next) {
        if (next.equals(s.doc)) {
            return;
        }
        s.undo.push(s.doc);
        while (s.undo.size() > HISTORY) {
            s.undo.removeLast();
        }
        s.redo.clear();
        s.doc = next;
        s.dirty = true;
    }

    private Session session(String id) {
        if (id == null || id.isBlank()) {
            if (sessions.size() == 1) {
                return sessions.values().iterator().next();
            }
            if (sessions.isEmpty()) {
                throw new IllegalArgumentException("No FX document is open: fx_new or fx_open one first");
            }
            throw new IllegalArgumentException("Several FX documents are open; pass id (one of "
                    + String.join(", ", sessions.keySet()) + ")");
        }
        Session s = sessions.get(id);
        if (s == null) {
            throw McpErrors.unknownEntity("FX document", id, sessions.keySet(), "fx_documents");
        }
        return s;
    }

    private String idOf(Session s) {
        for (Map.Entry<String, Session> e : sessions.entrySet()) {
            if (e.getValue() == s) {
                return e.getKey();
            }
        }
        return s.doc.id();
    }

    private Map<String, Object> info(String id, Session s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("file", s.file == null ? null : s.file.toString());
        m.put("dirty", s.dirty);
        m.put("origin", s.origin);
        m.put("emitters", s.doc.system().emitters().stream().map(FxEmitter::name).toList());
        m.put("endless", s.doc.system().endless());
        m.values().removeIf(v -> v == null);
        return m;
    }

    private static List<Object> sprites(OmfxDocument doc) {
        List<Object> out = new ArrayList<>();
        for (OmfxSprite sp : doc.sprites()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", sp.name());
            m.put("kind", sp.kind().extension);
            m.put("size", size(sp));
            out.add(m);
        }
        return out;
    }

    private static String size(OmfxSprite sp) {
        byte[] b = sp.bytes();
        try {
            int[] wh = sp.kind() == OmfxSprite.Kind.PNG ? TextureBytes.pngSize(b) : omtSize(b);
            return wh == null ? "?" : wh[0] + "x" + wh[1];
        } catch (IOException e) {
            return "?";
        }
    }

    private static int[] omtSize(byte[] b) throws IOException {
        var canvas = new OMTReader().read(b).canvasSize();
        return new int[]{canvas.width(), canvas.height()};
    }

    private static List<String> usedBy(OmfxDocument doc, String texture) {
        List<String> out = new ArrayList<>();
        for (ParticleEffect e : doc.effects()) {
            if (e.textures().contains(texture)) {
                out.add(e.name());
            }
        }
        return out;
    }

    private static void requireId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("'id' (the FX system's name) is required");
        }
    }

    /** The bare file name a document saves under by default. */
    static String fileName(String id) {
        String stem = id.replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("^\\.+", "");
        return (stem.isEmpty() ? "fx" : stem) + OmfxCodec.FILE_EXTENSION;
    }
}
