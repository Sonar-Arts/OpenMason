package com.openmason.engine.rendering.particles;

import java.util.Objects;

/**
 * A typed, named input of an {@link FxSystem} (Niagara "user parameter", VFX Graph "exposed property"): the game
 * drives "rain at 0.7" or "debris tinted like stone" through {@link FxInstance#setParam} without editing the asset.
 * What a parameter changes is declared by {@link FxBinding}s.
 *
 * @param name unique within the system
 * @param type value kind
 * @param x    default: the scalar, vector X or colour red (0..1)
 * @param y    default vector Y / green
 * @param z    default vector Z / blue
 * @param w    default alpha (colours)
 */
public record FxParameter(String name, Type type, float x, float y, float z, float w) {

    public enum Type {
        /** One number (intensity, scale). */
        FLOAT,
        /** Three numbers (direction, offset, wind). */
        VECTOR,
        /** RGBA 0..1 (tint). */
        COLOR
    }

    public FxParameter {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        if (name.isBlank()) {
            throw new IllegalArgumentException("parameter name is blank");
        }
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z) || !Float.isFinite(w)) {
            throw new IllegalArgumentException(name + ": default must be finite");
        }
    }

    public static FxParameter scalar(String name, float value) {
        return new FxParameter(name, Type.FLOAT, value, 0f, 0f, 0f);
    }

    public static FxParameter vector(String name, float x, float y, float z) {
        return new FxParameter(name, Type.VECTOR, x, y, z, 0f);
    }

    /** A colour parameter defaulting to {@code 0xRRGGBBAA}. */
    public static FxParameter color(String name, int rgba) {
        return new FxParameter(name, Type.COLOR, (rgba >>> 24) / 255f, ((rgba >>> 16) & 0xFF) / 255f,
                ((rgba >>> 8) & 0xFF) / 255f, (rgba & 0xFF) / 255f);
    }
}
