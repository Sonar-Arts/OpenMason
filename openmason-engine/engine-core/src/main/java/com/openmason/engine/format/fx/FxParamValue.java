package com.openmason.engine.format.fx;

import java.util.List;
import java.util.Objects;

/**
 * The value an {@link FxDef} gives one of its FX's parameters, overriding the parameter's default for every
 * instance the binding plays. The number of components says what it is, matching the three kinds an FX system
 * declares: 1 = a scalar, 3 = a vector, 4 = an RGBA colour (0..1). Which kind a parameter actually is depends
 * on the FX, which the format does not know; the runtime (and the editor, when it knows the FX) checks that.
 *
 * <p>Wire shape: a scalar is a bare JSON number, the others are number arrays.
 *
 * @param components 1, 3 or 4 finite numbers
 */
public record FxParamValue(List<Float> components) {

    public FxParamValue {
        Objects.requireNonNull(components, "components");
        components = List.copyOf(components);
        int n = components.size();
        if (n != 1 && n != 3 && n != 4) {
            throw new IllegalArgumentException("an FX parameter value has 1, 3 or 4 components, got " + n);
        }
        for (Float c : components) {
            if (c == null || !Float.isFinite(c)) {
                throw new IllegalArgumentException("FX parameter components must be finite numbers: " + components);
            }
        }
    }

    public static FxParamValue scalar(float value) {
        return new FxParamValue(List.of(value));
    }

    public static FxParamValue vector(float x, float y, float z) {
        return new FxParamValue(List.of(x, y, z));
    }

    public static FxParamValue color(float r, float g, float b, float a) {
        return new FxParamValue(List.of(r, g, b, a));
    }

    public int size() {
        return components.size();
    }

    public boolean isScalar() {
        return components.size() == 1;
    }

    public boolean isVector() {
        return components.size() == 3;
    }

    public boolean isColor() {
        return components.size() == 4;
    }

    /** Component {@code i}, or 0 past the end (so callers can read x/y/z/w uniformly). */
    public float get(int i) {
        return i < components.size() ? components.get(i) : 0f;
    }
}
