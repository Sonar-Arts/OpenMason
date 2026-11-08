package com.openmason.main.systems.particles;

import com.openmason.engine.rendering.particles.ParticleEffect;
import imgui.ImGui;
import imgui.flag.ImGuiTreeNodeFlags;
import imgui.type.ImInt;

import java.util.Locale;

/**
 * Small ImGui helpers shared by the FX editor's panels (OM #104): value widgets that report a new value instead of
 * writing it, colour packing, enum combos. Strings are ASCII only - the editor font has no typographic punctuation.
 */
final class ParticleEditorWidgets {

    private ParticleEditorWidgets() {
    }

    interface FloatSink { void accept(float v); }
    interface RangeSink { void accept(float min, float max); }
    interface ColorSink { void accept(int rgba); }

    static boolean header(String label, boolean open) {
        return ImGui.collapsingHeader(label, open ? ImGuiTreeNodeFlags.DefaultOpen : 0);
    }

    static void dragFloat(String label, float value, float speed, float min, float max, FloatSink sink) {
        float[] v = {value};
        if (ImGui.dragFloat(label, v, speed, min, max)) {
            sink.accept(Math.max(min, Math.min(max, v[0])));
        }
    }

    static void sliderFloat(String label, float value, float min, float max, FloatSink sink) {
        float[] v = {value};
        if (ImGui.sliderFloat(label, v, min, max)) {
            sink.accept(Math.max(min, Math.min(max, v[0])));
        }
    }

    /** A min/max pair; dragging one past the other carries the other with it so the range stays valid. */
    static void range(String label, ParticleEffect.Range r, float speed, float min, float max, RangeSink sink) {
        float[] v = {r.min(), r.max()};
        if (ImGui.dragFloat2(label, v, speed, min, max)) {
            float a = Math.max(min, Math.min(max, v[0]));
            float b = Math.max(min, Math.min(max, v[1]));
            if (a != r.min() && a > b) b = a;
            if (b != r.max() && b < a) a = b;
            sink.accept(a, b);
        }
    }

    static void color(String label, int rgba, ColorSink sink) {
        float[] c = rgba(rgba);
        if (ImGui.colorEdit4(label, c, imgui.flag.ImGuiColorEditFlags.AlphaBar)) {
            sink.accept(pack(c));
        }
    }

    static <E extends Enum<E>> E enumCombo(String label, E value, E[] values) {
        ImInt i = new ImInt(value.ordinal());
        if (ImGui.combo(label, i, names(values))) {
            return values[i.get()];
        }
        return value;
    }

    static String[] names(Enum<?>[] values) {
        String[] out = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = pretty(values[i]);
        }
        return out;
    }

    static String pretty(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    static float[] rgba(int c) {
        return new float[]{(c >>> 24) / 255f, ((c >>> 16) & 0xFF) / 255f, ((c >>> 8) & 0xFF) / 255f, (c & 0xFF) / 255f};
    }

    static int pack(float[] c) {
        return (channel(c[0]) << 24) | (channel(c[1]) << 16) | (channel(c[2]) << 8) | channel(c.length > 3 ? c[3] : 1f);
    }

    private static int channel(float v) {
        return Math.round(Math.max(0f, Math.min(1f, v)) * 255f);
    }

    static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    static String hex(int rgba) {
        return String.format(Locale.ROOT, "#%08X", rgba);
    }

    static String bytes(long b) {
        return b >= 1 << 20 ? String.format(Locale.ROOT, "%.1f MB", b / (double) (1 << 20))
                : String.format(Locale.ROOT, "%.0f KB", b / 1024.0);
    }

    static void hint(String text) {
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip(text);
        }
    }
}
