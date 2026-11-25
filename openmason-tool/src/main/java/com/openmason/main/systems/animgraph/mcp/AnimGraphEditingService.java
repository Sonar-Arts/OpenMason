package com.openmason.main.systems.animgraph.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openmason.main.systems.animgraph.AnimGraphSession;
import com.openmason.main.systems.animgraph.AnimGraphSessions;
import com.openmason.main.systems.io.AssetWriteService;
import com.openmason.main.systems.io.AssetWriteService.WriteOutcome;
import com.openmason.main.systems.io.BuiltinFileTypes;
import com.openmason.main.systems.io.WriteKind;
import com.openmason.main.systems.mcp.McpAck;
import com.openmason.main.systems.threading.MainThreadExecutor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * The {@code animgraph_*} MCP tools' backend (OpenMason #164): every call runs on the main thread against the ONE
 * graph the Animation Graph tab shows ({@link AnimGraphSessions}); each apply is ONE native undo step (all or
 * nothing). The edits, checks, compilation and evaluation are native; this class marshals and shapes replies.
 */
public final class AnimGraphEditingService {

    static final WriteKind KIND = WriteKind.of(BuiltinFileTypes.OMANIMGRAPH);
    private static final long TIMEOUT_MS = 30_000;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Supplier<AnimGraphSessions> sessions;
    private final AssetWriteService writes;

    public AnimGraphEditingService(Supplier<AnimGraphSessions> sessions, AssetWriteService writes) {
        this.sessions = sessions;
        this.writes = writes;
    }

    private AnimGraphSessions sessions() {
        AnimGraphSessions s = sessions.get();
        if (s == null) throw new IllegalStateException("the Animation Graph workspace is unavailable");
        return s;
    }

    private void guard(boolean discard) {
        sessions().current().ifPresent(g -> {
            if (g.isDirty() && !discard) {
                throw new IllegalStateException("'" + g.displayName() + "' has unsaved changes: animgraph_save it, or pass discard:true");
            }
        });
    }

    public Object create(String name, String profile, boolean discard) {
        return onMain(() -> {
            guard(discard);
            AnimGraphSession g = AnimGraphSession.create(name, profile);
            sessions().replace(g);
            return summary(g);
        });
    }

    public Object open(String path, boolean discard) {
        Path file = writes == null ? Path.of(path) : writes.sandbox().resolveExisting(path);
        return onMain(() -> {
            guard(discard);
            AnimGraphSession g;
            try {
                g = AnimGraphSession.open(file);
            } catch (IOException e) {
                throw new IllegalArgumentException(file.getFileName() + ": " + e.getMessage(), e);
            }
            sessions().replace(g);
            return summary(g);
        });
    }

    public Object close(boolean discard) {
        return onMain(() -> {
            AnimGraphSession g = sessions().current().orElse(null);
            if (g == null) return McpAck.ok().with("closed", false);
            guard(discard);
            sessions().close();
            return McpAck.ok().with("closed", true);
        });
    }

    /** section: view (default: nodes, inputs, machines, problems), json (the file text), problems. */
    public Object get(String section) {
        return onMain(() -> {
            AnimGraphSession g = sessions().require();
            String s = section == null || section.isBlank() ? "view" : section;
            return switch (s) {
                case "view" -> JSON.readTree(g.document().view());
                case "json" -> JSON.readTree(g.document().json());
                case "problems" -> JSON.readTree(g.document().problems());
                default -> throw new IllegalArgumentException("section: view, json or problems");
            };
        });
    }

    public Object apply(JsonNode ops, String label) {
        if (ops == null || !ops.isArray()) throw new IllegalArgumentException("ops must be an array");
        return onMain(() -> {
            AnimGraphSession g = sessions().require();
            String results = g.apply(ops.toString(), label, null);
            return state(g, McpAck.ok().with("applied", JSON.readTree(results)));
        });
    }

    public Object undo(boolean redo) {
        return onMain(() -> {
            AnimGraphSession g = sessions().require();
            String label = redo ? g.document().redoLabel() : g.document().undoLabel();
            boolean done = redo ? g.redo() : g.undo();
            return state(g, McpAck.ok().with(redo ? "redone" : "undone", done ? label : null));
        });
    }

    /** Sets the preview character and slot sources ({slot: "model:<clip>" | "file:<path>" | null}). */
    public Object preview(String character, JsonNode slots) {
        return onMain(() -> {
            AnimGraphSession g = sessions().require();
            Map<String, String> map = new LinkedHashMap<>();
            if (slots != null && slots.isObject()) {
                slots.fields().forEachRemaining(e -> map.put(e.getKey(), e.getValue().isNull() ? null : e.getValue().asText()));
            }
            if (character != null || !map.isEmpty()) g.setPreview(character, map);
            McpAck ack = McpAck.ok().with("character", g.characterPath()).with("slots", g.slotSources())
                    .with("embeddedClips", g.embeddedClipNames()).with("bound", g.boundSlots());
            String problem = g.previewProblem();
            if (problem != null) ack.with("problem", problem);
            return ack;
        });
    }

    /**
     * Sets parameters, fires triggers and evaluates `frames` frames of `dt`: the LIVE preview (what the tab's
     * Debugger shows; restart:true starts it over first), or a fresh headless instance (headless:true; the live
     * preview is untouched). Returns the debugger snapshot.
     */
    public Object evaluate(JsonNode params, List<String> triggers, int frames, float dt, boolean restart, boolean headless) {
        if (frames < 0 || frames > 100_000) throw new IllegalArgumentException("frames must be 0..100000");
        if (!(dt >= 0f) || dt > 1f) throw new IllegalArgumentException("dt must be in [0, 1]");
        return onMain(() -> {
            AnimGraphSession g = sessions().require();
            Map<String, String> types = g.parameterTypes();
            if (params != null && params.isObject()) {
                params.fields().forEachRemaining(e -> {
                    String type = types.get(e.getKey());
                    if (type == null) throw new IllegalArgumentException("unknown parameter " + e.getKey());
                    JsonNode v = e.getValue();
                    switch (type) {
                        case "float" -> g.setParam(e.getKey(), (float) v.asDouble());
                        case "int" -> g.setParam(e.getKey(), v.asInt());
                        case "bool" -> g.setParam(e.getKey(), v.asBoolean());
                        case "vec2" -> g.setParam(e.getKey(), new float[] {(float) v.get(0).asDouble(), (float) v.get(1).asDouble()});
                        default -> throw new IllegalArgumentException("parameter " + e.getKey() + " is a trigger: list it in triggers");
                    }
                });
            }
            List<String> fire = triggers == null ? List.of() : triggers;
            if (headless) return g.evaluateHeadless(fire, frames, dt);
            if (restart) g.restart();
            for (String t : fire) g.fireTrigger(t);
            for (int i = 0; i < frames; i++) g.step(dt);
            ObjectNode out = g.debug();
            return out;
        });
    }

    /**
     * Writes the .omanimgraph (drafts too: the problems are reported): in place, or `path` (a bare name lands in the
     * project's AnimGraphs folder), or where the user picks.
     */
    public Object save(String path, boolean prompt, boolean overwrite) {
        if (writes == null) throw new IllegalStateException("file access is unavailable (no write sandbox)");
        Object[] st = onMain(() -> {
            AnimGraphSession g = sessions().require();
            return new Object[] {g, g.file().orElse(null), g.displayName(), g.document().json(), g.document().valid()};
        });
        AnimGraphSession g = (AnimGraphSession) st[0];
        Path own = (Path) st[1];
        String name = st[2] + ".omanimgraph";
        String text = (String) st[3];
        AssetWriteService.Writer writer = target -> {
            java.nio.file.Files.writeString(target, text);
            return true;
        };
        WriteOutcome outcome;
        if (path == null && !prompt && own != null) {
            outcome = writes.saveInPlace(KIND, own.toString(), writer);
        } else {
            outcome = writes.save(AssetWriteService.WriteRequest.of(KIND, path != null ? path : (prompt ? null : name),
                    prompt, overwrite, name).withDetails(List.of("Animation graph " + st[2])), writer);
        }
        if (!outcome.ok()) return outcome;
        Path saved = Path.of(outcome.path());
        onMain(() -> {
            g.saved(saved);
            return null;
        });
        McpAck ack = McpAck.ok().with("saved", outcome.path()).with("promptedUser", Boolean.TRUE.equals(outcome.promptedUser()));
        if (!(Boolean) st[4]) ack.with("note", "saved as a draft: the graph still has errors (animgraph_get section:problems)");
        return ack;
    }

    // ------------------------------------------------------------------------------------------------

    private static Map<String, Object> summary(AnimGraphSession g) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", g.displayName());
        out.put("file", g.file().map(Path::toString).orElse(null));
        JsonNode view = JSON.readTree(g.document().view());
        out.put("nodes", view.path("nodes").size());
        List<String> errors = new ArrayList<>();
        for (JsonNode p : view.path("problems")) {
            if (p.path("severity").asText().equals("error")) errors.add(p.path("message").asText());
        }
        out.put("errors", errors.size());
        out.put("valid", g.document().valid());
        out.put("dirty", g.isDirty());
        return out;
    }

    private static Object state(AnimGraphSession g, McpAck ack) throws IOException {
        JsonNode problems = JSON.readTree(g.document().problems());
        int errors = 0;
        int warnings = 0;
        for (JsonNode p : problems) {
            if (p.path("severity").asText().equals("error")) errors++;
            else if (p.path("severity").asText().equals("warning")) warnings++;
        }
        ack.with("valid", g.document().valid()).with("errors", errors).with("warnings", warnings).with("dirty", g.isDirty());
        return ack;
    }

    private static <T> T onMain(Callable<T> task) {
        CompletableFuture<T> future = MainThreadExecutor.submit(task);
        try {
            return future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("Operation timed out on main thread", e);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        }
    }
}
