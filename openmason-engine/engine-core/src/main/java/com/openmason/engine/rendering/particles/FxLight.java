package com.openmason.engine.rendering.particles;

import java.util.List;

/**
 * The point light an {@link FxSystem} gives off (a campfire's glow, an explosion's flash). The engine only computes
 * it; the host feeds it to its lighting ({@link FxWorld#forEachLight}).
 *
 * <p>Intensity = {@link #intensity} × the {@link #keys} curve at the instance's age × flicker × the instance's stop
 * fade × any {@link FxBinding.Target#LIGHT_INTENSITY} bindings. Keys are (seconds, multiplier) pairs, linear between
 * keys, held after the last one (end on 0 for a flash) or wrapped when {@link #loopKeys}. Flicker is smooth noise:
 * {@code 1 − flicker × noise(age × flickerHz)}, different per instance.
 *
 * @param color     {@code 0xRRGGBBAA} (alpha ignored)
 * @param intensity base intensity (host units)
 * @param radius    reach in blocks (host decides how to use it)
 * @param keys      intensity multiplier over the instance's age; empty = constant 1
 * @param loopKeys  repeat the keys with the last key's time as the period
 * @param flicker   0..1 depth of the flicker
 * @param flickerHz flicker speed
 * @param offsetX   position relative to the instance
 * @param offsetY   position relative to the instance
 * @param offsetZ   position relative to the instance
 */
public record FxLight(int color, float intensity, float radius, List<Key> keys, boolean loopKeys,
                      float flicker, float flickerHz, float offsetX, float offsetY, float offsetZ) {

    /** Intensity multiplier {@code value} at {@code time} seconds. */
    public record Key(float time, float value) {
        public Key {
            if (!(time >= 0f) || !Float.isFinite(time) || !Float.isFinite(value)) {
                throw new IllegalArgumentException("light key must have a finite time >= 0 and a finite value");
            }
        }
    }

    public FxLight {
        keys = List.copyOf(keys == null ? List.of() : keys);
        if (!(intensity >= 0f) || !Float.isFinite(intensity) || !(radius >= 0f) || !Float.isFinite(radius)) {
            throw new IllegalArgumentException("light intensity and radius must be finite and >= 0");
        }
        if (!(flicker >= 0f && flicker <= 1f) || !(flickerHz >= 0f) || !Float.isFinite(flickerHz)) {
            throw new IllegalArgumentException("light flicker must be in [0, 1] and flickerHz >= 0");
        }
        for (int i = 1; i < keys.size(); i++) {
            if (keys.get(i).time() < keys.get(i - 1).time()) {
                throw new IllegalArgumentException("light keys must be in ascending time order");
            }
        }
        if (loopKeys && (keys.isEmpty() || keys.get(keys.size() - 1).time() <= 0f)) {
            throw new IllegalArgumentException("looping light keys need a last key after 0 s");
        }
    }

    /** A steady light. */
    public static FxLight steady(int color, float intensity, float radius) {
        return new FxLight(color, intensity, radius, List.of(), false, 0f, 0f, 0f, 0f, 0f);
    }

    public FxLight flickering(float depth, float hz) {
        return new FxLight(color, intensity, radius, keys, loopKeys, depth, hz, offsetX, offsetY, offsetZ);
    }

    public FxLight withKeys(List<Key> curve, boolean loop) {
        return new FxLight(color, intensity, radius, curve, loop, flicker, flickerHz, offsetX, offsetY, offsetZ);
    }

    public FxLight offset(float x, float y, float z) {
        return new FxLight(color, intensity, radius, keys, loopKeys, flicker, flickerHz, x, y, z);
    }

    /** The key curve at {@code age} seconds (1 without keys). */
    public float curve(double age) {
        int n = keys.size();
        if (n == 0) {
            return 1f;
        }
        double t = age;
        if (loopKeys) {
            double period = keys.get(n - 1).time();
            t = age - period * Math.floor(age / period);
        }
        if (t <= keys.get(0).time()) {
            return keys.get(0).value();
        }
        for (int i = 1; i < n; i++) {
            Key b = keys.get(i);
            if (t <= b.time()) {
                Key a = keys.get(i - 1);
                float span = b.time() - a.time();
                return span <= 0f ? b.value() : a.value() + (b.value() - a.value()) * (float) ((t - a.time()) / span);
            }
        }
        return keys.get(n - 1).value();
    }
}
