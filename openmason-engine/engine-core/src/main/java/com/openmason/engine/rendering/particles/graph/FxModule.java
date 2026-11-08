package com.openmason.engine.rendering.particles.graph;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One entry of a stage's stack: either a <b>built-in</b> module ({@link #builtin} names it, no nodes — the compiler
 * lowers it from the effect's own fields, e.g. {@code forces} = gravity + wind + drag) or a <b>custom</b> module
 * (a small graph of {@link FxNode}s).
 *
 * @param name    label (diagnostics, editors)
 * @param builtin built-in module id, or null for a custom module
 * @param nodes   the custom module's operators, in evaluation order (empty for a built-in)
 */
public record FxModule(String name, String builtin, List<FxNode> nodes) {

    public FxModule {
        Objects.requireNonNull(name, "name");
        nodes = List.copyOf(nodes == null ? List.of() : nodes);
        if (builtin != null && !nodes.isEmpty()) {
            throw new IllegalArgumentException("module '" + name + "': a built-in module has no nodes");
        }
        if (builtin == null && nodes.isEmpty()) {
            throw new IllegalArgumentException("module '" + name + "': a custom module needs nodes");
        }
        Set<String> seen = new HashSet<>();
        for (FxNode n : nodes) {
            for (String in : n.inputs()) {
                if (!seen.contains(in)) {
                    throw new IllegalArgumentException("module '" + name + "': node '" + n.id() + "' reads '" + in
                            + "', which is not an earlier node");
                }
            }
            if (!seen.add(n.id())) {
                throw new IllegalArgumentException("module '" + name + "': duplicate node id '" + n.id() + "'");
            }
            if (n.op().intrinsic()) {
                throw new IllegalArgumentException("module '" + name + "': " + n.op().id() + " is built-in only");
            }
        }
    }

    public static FxModule builtin(String id) {
        return new FxModule(id, id, List.of());
    }

    public static FxModule custom(String name, FxNode... nodes) {
        return new FxModule(name, null, List.of(nodes));
    }

    public boolean isBuiltin() {
        return builtin != null;
    }
}
