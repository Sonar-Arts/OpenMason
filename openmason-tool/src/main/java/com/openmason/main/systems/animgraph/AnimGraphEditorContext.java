package com.openmason.main.systems.animgraph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openmason.engine.animgraph.AnimGraph;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Animation Graph tab's editing state (OpenMason #164): the open session, the selection, the document view
 * parsed once per revision, the preview's run state and its debugger snapshot. Every edit goes through
 * {@link #apply} (one native undo step; a refusal becomes the error line). ImGui-free.
 */
final class AnimGraphEditorContext {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AnimGraphSessions sessions;
    String selectedNode;
    String selectedLink;   // "<parent>|<at>"
    String selectedMachine;
    String selectedState;
    int selectedTransition = -1;
    String error;
    String note;
    boolean playing = true;
    float speed = 1f;

    private long viewRevision = Long.MIN_VALUE;
    private AnimGraphSession viewFor;
    private JsonNode view = JSON.createObjectNode();
    private ObjectNode debug;
    private final Map<String, Double> influence = new HashMap<>();

    AnimGraphEditorContext(AnimGraphSessions sessions) {
        this.sessions = sessions;
    }

    AnimGraphSession session() {
        return sessions.current().orElse(null);
    }

    void reset() {
        selectedNode = selectedLink = selectedMachine = selectedState = null;
        selectedTransition = -1;
        error = note = null;
        debug = null;
        influence.clear();
        viewRevision = Long.MIN_VALUE;
    }

    /** The document's view (nodes, inputs, machines, parameters, problems), parsed once per revision. */
    JsonNode view() {
        AnimGraphSession s = session();
        if (s == null) return JSON.createObjectNode();
        long rev = s.document().revision();
        if (rev != viewRevision || s != viewFor) {
            try {
                view = JSON.readTree(s.document().view());
            } catch (IOException e) {
                view = JSON.createObjectNode();
            }
            viewRevision = rev;
            viewFor = s;
            if (selectedMachine == null || view.path("machines").get(selectedMachine) == null) {
                var it = view.path("machines").fieldNames();
                selectedMachine = it.hasNext() ? it.next() : null;
            }
        }
        return view;
    }

    /** Applies operations (one undo step; mergeKey folds a drag). False (and the error line) when refused. */
    boolean apply(String ops, String label, String mergeKey) {
        AnimGraphSession s = session();
        if (s == null) return false;
        try {
            s.apply(ops, label, mergeKey);
            return true;
        } catch (RuntimeException e) {
            error = e.getMessage();
            return false;
        }
    }

    void selectNode(String id) {
        selectedNode = id;
        selectedLink = null;
        if (id != null && view().path("machines").get(id) != null) selectedMachine = id;
    }

    JsonNode selectedNodeView() {
        if (selectedNode == null) return null;
        for (JsonNode n : view().path("nodes")) {
            if (n.path("id").asText().equals(selectedNode)) return n;
        }
        return null;
    }

    /** The node's member JSON from the document text (the inspector edits these). */
    JsonNode nodeJson(String id) {
        AnimGraphSession s = session();
        if (s == null || id == null) return null;
        try {
            for (JsonNode n : JSON.readTree(s.document().json()).path("nodes")) {
                if (n.path("id").asText().equals(id)) return n;
            }
        } catch (IOException ignored) {
            // none
        }
        return null;
    }

    JsonNode documentJson() {
        AnimGraphSession s = session();
        if (s == null) return JSON.createObjectNode();
        try {
            return JSON.readTree(s.document().json());
        } catch (IOException e) {
            return JSON.createObjectNode();
        }
    }

    String freshId(String base) {
        for (int i = 1; ; i++) {
            String id = base + i;
            boolean taken = false;
            for (JsonNode n : view().path("nodes")) taken |= n.path("id").asText().equals(id);
            if (!taken) return id;
        }
    }

    String freshState(JsonNode machine, String base) {
        for (int i = 1; ; i++) {
            String id = base + i;
            boolean taken = false;
            for (JsonNode s : machine.path("states")) taken |= s.path("name").asText().equals(id);
            if (!taken) return id;
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Preview and debugger

    boolean preview() {
        AnimGraphSession s = session();
        return s != null && s.previewProblem() == null;
    }

    /** Steps the live preview (when playing) and refreshes the debugger snapshot. */
    void tick(float dt) {
        AnimGraphSession s = session();
        if (s == null) {
            debug = null;
            influence.clear();
            return;
        }
        if (playing && s.previewProblem() == null) s.step(dt * speed);
        debug = s.debug();
        influence.clear();
        for (JsonNode n : debug.path("nodes")) {
            if (n.path("active").asBoolean()) influence.put(n.path("id").asText(), n.path("influence").asDouble());
        }
    }

    void stepOnce() {
        AnimGraphSession s = session();
        if (s != null && s.previewProblem() == null) {
            s.step(1f / 60f);
            debug = s.debug();
        }
    }

    ObjectNode debug() {
        return debug;
    }

    Map<String, Double> influenceById() {
        return influence;
    }

    String debugState(String machine) {
        if (debug == null) return null;
        for (JsonNode n : debug.path("nodes")) {
            if (n.path("id").asText().equals(machine)) return n.path("state").asText(null);
        }
        return null;
    }

    void travel(String machine, String state) {
        AnimGraphSession s = session();
        if (s == null || s.previewProblem() != null) return;
        AnimGraph g = s.graph();
        for (AnimGraph.Node n : g.nodes()) {
            if (n.id().equals(machine)) {
                List<String> states = g.states(n.index());
                int i = states.indexOf(state);
                if (i >= 0) s.instance().travel(n.index(), i);
            }
        }
    }
}
