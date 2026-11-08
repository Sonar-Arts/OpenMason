package com.openmason.main.systems.particles.fx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Helpers over the editable system view of an {@code .omfx} document ({@code OmfxCodec.toSystemJson}): the JSON
 * both the FX editor (OM #104) and the {@code fx_*} MCP tools edit, so a person and an agent change a document the
 * same way. Effects, emitters and parameters are referenced by name everywhere in the view, which is what makes
 * renames and live re-reads simple.
 */
public final class FxSystemViews {

    private FxSystemViews() {
    }

    /** The entry called {@code name} in a named list ({@code effects}, {@code emitters}, {@code parameters}). */
    public static JsonNode find(JsonNode list, String name) {
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

    /** Index of {@code name} in a named list, or -1. */
    public static int indexOf(JsonNode list, String name) {
        if (list == null) {
            return -1;
        }
        for (int i = 0; i < list.size(); i++) {
            if (name.equals(list.get(i).path("name").asText())) {
                return i;
            }
        }
        return -1;
    }

    public static List<String> names(JsonNode list) {
        List<String> out = new ArrayList<>();
        if (list != null) {
            list.forEach(n -> out.add(n.path("name").asText()));
        }
        return out;
    }

    /** {@code base}, or {@code base-2}, {@code base-3}... whichever is not taken. */
    public static String uniqueName(Collection<String> taken, String base) {
        String stem = base == null || base.isBlank() ? "item" : base.strip();
        if (!taken.contains(stem)) {
            return stem;
        }
        for (int i = 2; ; i++) {
            String candidate = stem + "-" + i;
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }
    }

    /** After renaming an entry of {@code section}, every place the old name was referenced from follows it. */
    public static void rewriteReferences(ObjectNode view, String section, String from, String to) {
        switch (section) {
            case "effects" -> {
                for (JsonNode e : view.path("emitters")) {
                    replaceText((ObjectNode) e, "effect", from, to);
                }
                for (JsonNode e : view.path("effects")) {
                    for (JsonNode sub : e.path("subEmitters")) {
                        replaceText((ObjectNode) sub, "effect", from, to);
                    }
                }
            }
            case "emitters" -> {
                for (JsonNode b : view.path("bindings")) {
                    replaceText((ObjectNode) b, "emitter", from, to);
                }
                for (JsonNode h : view.path("events")) {
                    if (!"custom".equalsIgnoreCase(h.path("trigger").asText())) {
                        replaceText((ObjectNode) h, "source", from, to);
                    }
                    replaceText((ObjectNode) h, "spawnInto", from, to);
                }
                for (JsonNode c : view.path("cues")) {
                    replaceText((ObjectNode) c, "emitter", from, to);
                }
            }
            case "parameters" -> {
                for (JsonNode b : view.path("bindings")) {
                    replaceText((ObjectNode) b, "parameter", from, to);
                }
            }
            default -> { }
        }
    }

    /**
     * Drops what refers to emitter {@code name} (bindings scoped to it, handlers from or into it, its start cues);
     * returns how many entries went.
     */
    public static int dropEmitterReferences(ObjectNode view, String name) {
        int dropped = removeWhere(view.path("bindings"), b -> name.equals(b.path("emitter").asText(null)));
        dropped += removeWhere(view.path("events"), h ->
                (!"custom".equalsIgnoreCase(h.path("trigger").asText()) && name.equals(h.path("source").asText(null)))
                        || name.equals(h.path("spawnInto").asText(null)));
        dropped += removeWhere(view.path("cues"), c -> name.equals(c.path("emitter").asText(null)));
        return dropped;
    }

    /** Drops the bindings of parameter {@code name}; returns how many went. */
    public static int dropParameterReferences(ObjectNode view, String name) {
        return removeWhere(view.path("bindings"), b -> name.equals(b.path("parameter").asText(null)));
    }

    /** Names of effects no emitter plays and no other effect spawns (the reader would drop them silently). */
    public static List<String> unusedEffects(ObjectNode view) {
        List<String> used = new ArrayList<>();
        for (JsonNode e : view.path("emitters")) {
            used.add(e.path("effect").asText());
        }
        boolean grew = true;
        while (grew) {
            grew = false;
            for (JsonNode e : view.path("effects")) {
                if (!used.contains(e.path("name").asText())) {
                    continue;
                }
                for (JsonNode sub : e.path("subEmitters")) {
                    String child = sub.path("effect").asText();
                    if (!used.contains(child)) {
                        used.add(child);
                        grew = true;
                    }
                }
            }
        }
        List<String> unused = new ArrayList<>();
        for (String n : names(view.path("effects"))) {
            if (!used.contains(n)) {
                unused.add(n);
            }
        }
        return unused;
    }

    /** Removes the named entries of a list (absent names are ignored). */
    public static void removeNames(JsonNode list, Collection<String> names) {
        removeWhere(list, n -> names.contains(n.path("name").asText()));
    }

    private static int removeWhere(JsonNode list, java.util.function.Predicate<JsonNode> test) {
        if (!(list instanceof ArrayNode array)) {
            return 0;
        }
        int removed = 0;
        for (int i = array.size() - 1; i >= 0; i--) {
            if (test.test(array.get(i))) {
                array.remove(i);
                removed++;
            }
        }
        return removed;
    }

    private static void replaceText(ObjectNode n, String key, String from, String to) {
        if (from.equals(n.path(key).asText(null))) {
            n.put(key, to);
        }
    }
}
