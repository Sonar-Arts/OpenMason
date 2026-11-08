package com.openmason.main.systems.particles.fx;

import com.openmason.engine.rendering.particles.graph.FxModule;
import com.openmason.engine.rendering.particles.graph.FxNode;
import com.openmason.engine.rendering.particles.graph.FxOp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One custom FX module as the graph editor edits it (OM #104). The engine IR ({@link FxModule}) only holds finished
 * graphs - every input wired, nodes in evaluation order - while a person edits through unfinished states: a node
 * just dropped, a wire pulled off, a cycle on the way to being fixed. The draft holds any of those plus node
 * positions; {@link #lower} turns it into a module (topologically sorted) or says what is missing, per node.
 * Immutable: every edit returns a new draft.
 *
 * @param name  the module's name
 * @param nodes nodes in the order they were made
 */
public record FxGraphDraft(String name, List<DraftNode> nodes) {

    /** Horizontal / vertical spacing of the automatic layout. */
    public static final float COLUMN = 210f, ROW = 92f;

    /**
     * A node: an {@link FxNode} whose inputs may be unconnected ({@code ""}), plus its canvas position.
     *
     * @param inputs exactly {@link FxOp#arity} entries; {@code ""} = not connected
     */
    public record DraftNode(String id, FxOp op, List<String> inputs, List<Float> values, String attribute,
                            float x, float y) {
        public DraftNode {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(op, "op");
            inputs = List.copyOf(inputs == null ? List.of() : inputs);
            values = List.copyOf(values == null ? List.of() : values);
            if (inputs.size() != op.arity) {
                List<String> fixed = new ArrayList<>(inputs);
                while (fixed.size() < op.arity) fixed.add("");
                while (fixed.size() > op.arity) fixed.removeLast();
                inputs = List.copyOf(fixed);
            }
        }

        DraftNode withInputs(List<String> v) { return new DraftNode(id, op, v, values, attribute, x, y); }
        DraftNode at(float nx, float ny) { return new DraftNode(id, op, inputs, values, attribute, nx, ny); }
    }

    /** What lowering produced: the module, or (when {@code module} is null) the problems by node id ("" = graph). */
    public record Lowered(FxModule module, Map<String, String> problems) {
        public boolean ok() {
            return module != null;
        }
    }

    public FxGraphDraft {
        Objects.requireNonNull(name, "name");
        nodes = List.copyOf(nodes == null ? List.of() : nodes);
    }

    /** An empty custom module (one {@code dt} source, so it is never nodeless). */
    public static FxGraphDraft empty(String name) {
        return new FxGraphDraft(name, List.of()).addNode(FxOp.DT, 0f, 0f);
    }

    /** The draft of {@code module}: positions from {@code layout} (node id -> {x, y}), the rest laid out. */
    public static FxGraphDraft of(FxModule module, Map<String, float[]> layout) {
        Map<String, Integer> depth = new HashMap<>();
        Map<Integer, Integer> rows = new HashMap<>();
        List<DraftNode> out = new ArrayList<>();
        for (FxNode n : module.nodes()) {
            int d = 0;
            for (String in : n.inputs()) {
                d = Math.max(d, depth.getOrDefault(in, 0) + 1);
            }
            depth.put(n.id(), d);
            int row = rows.merge(d, 1, Integer::sum) - 1;
            float[] at = layout == null ? null : layout.get(n.id());
            float x = at != null ? at[0] : d * COLUMN;
            float y = at != null ? at[1] : row * ROW;
            out.add(new DraftNode(n.id(), n.op(), n.inputs(), n.values(), n.attribute(), x, y));
        }
        return new FxGraphDraft(module.name(), out);
    }

    public DraftNode node(String id) {
        for (DraftNode n : nodes) {
            if (n.id().equals(id)) {
                return n;
            }
        }
        return null;
    }

    /** Node positions by id (what the document's layout file keeps). */
    public Map<String, float[]> layout() {
        Map<String, float[]> out = new LinkedHashMap<>();
        for (DraftNode n : nodes) {
            out.put(n.id(), new float[]{n.x(), n.y()});
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------
    // Edits
    // ------------------------------------------------------------------------------------------------

    /** Adds an {@code op} node at {@code (x, y)} with an unused id and sensible defaults. */
    public FxGraphDraft addNode(FxOp op, float x, float y) {
        if (op.intrinsic()) {
            throw new IllegalArgumentException(op.id() + " is only used by built-in modules");
        }
        Set<String> taken = new HashSet<>();
        nodes.forEach(n -> taken.add(n.id()));
        String id = FxSystemViews.uniqueName(taken, op.id());
        List<Float> values = switch (op) {
            case CONST -> List.of(0f);
            case CURVE -> List.of(0f, 0f, 1f, 1f);
            default -> List.of();
        };
        String attribute = op == FxOp.READ ? "position" : op == FxOp.WRITE ? "velocity" : null;
        List<DraftNode> next = new ArrayList<>(nodes);
        next.add(new DraftNode(id, op, null, values, attribute, x, y));
        return new FxGraphDraft(name, next);
    }

    /** Removes node {@code id}; inputs that read it become unconnected. */
    public FxGraphDraft removeNode(String id) {
        List<DraftNode> next = new ArrayList<>();
        for (DraftNode n : nodes) {
            if (n.id().equals(id)) {
                continue;
            }
            List<String> in = new ArrayList<>(n.inputs());
            in.replaceAll(s -> s.equals(id) ? "" : s);
            next.add(n.withInputs(in));
        }
        return new FxGraphDraft(name, next);
    }

    /** Wires {@code from}'s output into input {@code input} of {@code to}. */
    public FxGraphDraft connect(String from, String to, int input) {
        if (from.equals(to)) {
            throw new IllegalArgumentException("a node cannot read itself");
        }
        if (node(from) == null) {
            throw new IllegalArgumentException("no node '" + from + "'");
        }
        if (node(from).op() == FxOp.WRITE) {
            throw new IllegalArgumentException("'" + from + "' writes an attribute and has no output");
        }
        return replace(to, n -> {
            if (input < 0 || input >= n.inputs().size()) {
                throw new IllegalArgumentException("'" + to + "' has no input " + input);
            }
            List<String> in = new ArrayList<>(n.inputs());
            in.set(input, from);
            return n.withInputs(in);
        });
    }

    public FxGraphDraft disconnect(String to, int input) {
        return replace(to, n -> {
            List<String> in = new ArrayList<>(n.inputs());
            if (input >= 0 && input < in.size()) {
                in.set(input, "");
            }
            return n.withInputs(in);
        });
    }

    public FxGraphDraft move(String id, float x, float y) {
        return replace(id, n -> n.at(x, y));
    }

    public FxGraphDraft setValues(String id, List<Float> values) {
        return replace(id, n -> new DraftNode(n.id(), n.op(), n.inputs(), values, n.attribute(), n.x(), n.y()));
    }

    public FxGraphDraft setAttribute(String id, String attribute) {
        return replace(id, n -> new DraftNode(n.id(), n.op(), n.inputs(), n.values(),
                attribute == null || attribute.isBlank() ? null : attribute.strip(), n.x(), n.y()));
    }

    /** Changes a node's operator, keeping the inputs that still fit and the values when the kind of value stays. */
    public FxGraphDraft setOp(String id, FxOp op) {
        if (op.intrinsic()) {
            throw new IllegalArgumentException(op.id() + " is only used by built-in modules");
        }
        return replace(id, n -> {
            List<Float> values = op == n.op() ? n.values() : switch (op) {
                case CONST -> n.op() == FxOp.CONST ? n.values() : List.of(0f);
                case CURVE -> List.of(0f, 0f, 1f, 1f);
                default -> List.of();
            };
            boolean attr = op == FxOp.READ || op == FxOp.WRITE;
            String attribute = attr ? (n.attribute() != null ? n.attribute() : "position") : null;
            return new DraftNode(n.id(), op, n.inputs(), values, attribute, n.x(), n.y());
        });
    }

    /** Renames node {@code id}; wires that read it follow. */
    public FxGraphDraft rename(String id, String to) {
        String target = to == null ? "" : to.strip();
        if (target.isEmpty()) {
            throw new IllegalArgumentException("a node id cannot be empty");
        }
        if (!target.equals(id) && node(target) != null) {
            throw new IllegalArgumentException("node '" + target + "' already exists");
        }
        List<DraftNode> next = new ArrayList<>();
        for (DraftNode n : nodes) {
            List<String> in = new ArrayList<>(n.inputs());
            in.replaceAll(s -> s.equals(id) ? target : s);
            String nid = n.id().equals(id) ? target : n.id();
            next.add(new DraftNode(nid, n.op(), in, n.values(), n.attribute(), n.x(), n.y()));
        }
        return new FxGraphDraft(name, next);
    }

    public FxGraphDraft withName(String moduleName) {
        return new FxGraphDraft(moduleName, nodes);
    }

    private FxGraphDraft replace(String id, java.util.function.UnaryOperator<DraftNode> op) {
        List<DraftNode> next = new ArrayList<>(nodes.size());
        boolean found = false;
        for (DraftNode n : nodes) {
            if (n.id().equals(id)) {
                next.add(op.apply(n));
                found = true;
            } else {
                next.add(n);
            }
        }
        if (!found) {
            throw new IllegalArgumentException("no node '" + id + "'");
        }
        return new FxGraphDraft(name, next);
    }

    // ------------------------------------------------------------------------------------------------
    // Lowering
    // ------------------------------------------------------------------------------------------------

    /**
     * The module this draft describes, nodes in a valid evaluation order (inputs before readers; otherwise the
     * order they were made), or the reasons it is not one yet: unconnected inputs, wires from removed nodes,
     * cycles, missing attributes, malformed constants. Type errors are the compiler's, reported when the document
     * re-reads the effect.
     */
    public Lowered lower() {
        Map<String, String> problems = new LinkedHashMap<>();
        if (nodes.isEmpty()) {
            problems.put("", "a custom module needs at least one node");
            return new Lowered(null, problems);
        }
        Map<String, DraftNode> byId = new LinkedHashMap<>();
        for (DraftNode n : nodes) {
            if (byId.put(n.id(), n) != null) {
                problems.put(n.id(), "duplicate node id");
            }
        }
        for (DraftNode n : nodes) {
            for (int i = 0; i < n.inputs().size(); i++) {
                String in = n.inputs().get(i);
                if (in.isEmpty()) {
                    problems.putIfAbsent(n.id(), "input " + (i + 1) + " is not connected");
                } else if (!byId.containsKey(in)) {
                    problems.putIfAbsent(n.id(), "input " + (i + 1) + " reads '" + in + "', which is gone");
                } else if (byId.get(in).op() == FxOp.WRITE) {
                    problems.putIfAbsent(n.id(), "input " + (i + 1) + " reads a write node, which has no value");
                }
            }
            boolean needsAttribute = n.op() == FxOp.READ || n.op() == FxOp.WRITE;
            if (needsAttribute && (n.attribute() == null || n.attribute().isBlank())) {
                problems.putIfAbsent(n.id(), n.op().id() + " needs an attribute");
            }
        }
        if (!problems.isEmpty()) {
            return new Lowered(null, problems);
        }
        // Kahn's algorithm, stable: among ready nodes the earliest made goes first.
        List<DraftNode> order = new ArrayList<>();
        Set<String> placed = new HashSet<>();
        boolean progress = true;
        while (order.size() < nodes.size() && progress) {
            progress = false;
            for (DraftNode n : nodes) {
                if (placed.contains(n.id()) || !placed.containsAll(n.inputs())) {
                    continue;
                }
                order.add(n);
                placed.add(n.id());
                progress = true;
            }
        }
        if (order.size() < nodes.size()) {
            for (DraftNode n : nodes) {
                if (!placed.contains(n.id())) {
                    problems.put(n.id(), "is part of a cycle");
                }
            }
            return new Lowered(null, problems);
        }
        List<FxNode> out = new ArrayList<>(order.size());
        try {
            for (DraftNode n : order) {
                out.add(new FxNode(n.id(), n.op(), n.inputs(), n.values(), n.attribute()));
            }
            return new Lowered(new FxModule(name, null, out), Collections.emptyMap());
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage() == null ? "invalid" : e.getMessage();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("node '([^']+)'").matcher(msg);
            problems.put(m.find() ? m.group(1) : "", msg.replaceFirst("^node '[^']+': ", ""));
            return new Lowered(null, problems);
        }
    }
}
