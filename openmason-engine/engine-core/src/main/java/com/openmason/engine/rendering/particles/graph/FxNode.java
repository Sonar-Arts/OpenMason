package com.openmason.engine.rendering.particles.graph;

import java.util.List;
import java.util.Objects;

/**
 * One operator of a module: SSA — it has an id, reads earlier nodes of the same module by id, and is evaluated
 * once. {@code values} carries constants (CONST, CURVE keys); {@code attribute} names what READ/WRITE touch.
 *
 * @param id        unique within its module
 * @param op        the operator
 * @param inputs    ids of earlier nodes in the same module ({@link FxOp#arity} of them)
 * @param values    numbers the op takes (empty when none)
 * @param attribute READ/WRITE target, else null
 */
public record FxNode(String id, FxOp op, List<String> inputs, List<Float> values, String attribute) {

    public FxNode {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(op, "op");
        inputs = List.copyOf(inputs == null ? List.of() : inputs);
        values = List.copyOf(values == null ? List.of() : values);
        if (id.isBlank()) {
            throw new IllegalArgumentException("node id is blank");
        }
        if (inputs.size() != op.arity) {
            throw new IllegalArgumentException("node '" + id + "': " + op.id() + " takes " + op.arity + " inputs, got "
                    + inputs.size());
        }
        boolean needsAttribute = op == FxOp.READ || op == FxOp.WRITE;
        if (needsAttribute != (attribute != null && !attribute.isBlank())) {
            throw new IllegalArgumentException("node '" + id + "': only read/write name an attribute");
        }
        if (op == FxOp.CONST && values.size() != 1 && values.size() != 3) {
            throw new IllegalArgumentException("node '" + id + "': const takes 1 or 3 values");
        }
        if (op == FxOp.CURVE) {
            if (values.size() < 2 || values.size() % 2 != 0) {
                throw new IllegalArgumentException("node '" + id + "': curve takes t, v pairs");
            }
            for (int k = 2; k < values.size(); k += 2) {
                if (values.get(k) < values.get(k - 2)) {
                    throw new IllegalArgumentException("node '" + id + "': curve keys must ascend in t");
                }
            }
        }
        for (Float v : values) {
            if (v == null || !Float.isFinite(v)) {
                throw new IllegalArgumentException("node '" + id + "': values must be finite");
            }
        }
    }

    // ---- terse factories for building graphs in code ----

    public static FxNode constant(String id, float v) {
        return new FxNode(id, FxOp.CONST, List.of(), List.of(v), null);
    }

    public static FxNode constant(String id, float x, float y, float z) {
        return new FxNode(id, FxOp.CONST, List.of(), List.of(x, y, z), null);
    }

    public static FxNode read(String id, String attribute) {
        return new FxNode(id, FxOp.READ, List.of(), List.of(), attribute);
    }

    public static FxNode write(String id, String attribute, String value) {
        return new FxNode(id, FxOp.WRITE, List.of(value), List.of(), attribute);
    }

    public static FxNode op(String id, FxOp op, String... inputs) {
        return new FxNode(id, op, List.of(inputs), List.of(), null);
    }

    public static FxNode curve(String id, String input, float... keys) {
        Float[] boxed = new Float[keys.length];
        for (int i = 0; i < keys.length; i++) {
            boxed[i] = keys[i];
        }
        return new FxNode(id, FxOp.CURVE, List.of(input), List.of(boxed), null);
    }
}
