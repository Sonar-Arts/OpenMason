package com.openmason.engine.rendering.particles.graph;

import java.util.List;
import java.util.Objects;

/**
 * A custom per-particle attribute a graph declares (beyond the built-ins: position, velocity, age, lifetime,
 * normalizedAge, rotation, spin, startSize, endSize). New particles start at {@code defaults}. Attributes no stage
 * reads are never allocated (dead-attribute elimination).
 */
public record FxAttribute(String name, FxType type, List<Float> defaults) {

    public FxAttribute {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        defaults = List.copyOf(defaults == null || defaults.isEmpty() ? zeros(type.width) : defaults);
        if (name.isBlank() || name.contains(".")) {
            throw new IllegalArgumentException("attribute name must be non-blank and dot-free: '" + name + "'");
        }
        if (defaults.size() != type.width) {
            throw new IllegalArgumentException("attribute '" + name + "': " + type + " needs " + type.width
                    + " default values");
        }
    }

    private static List<Float> zeros(int n) {
        return n == 1 ? List.of(0f) : List.of(0f, 0f, 0f);
    }

    public static FxAttribute scalar(String name, float initial) {
        return new FxAttribute(name, FxType.FLOAT, List.of(initial));
    }

    public static FxAttribute vector(String name, float x, float y, float z) {
        return new FxAttribute(name, FxType.VEC3, List.of(x, y, z));
    }
}
