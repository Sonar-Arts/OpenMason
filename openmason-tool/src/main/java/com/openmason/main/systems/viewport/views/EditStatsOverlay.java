package com.openmason.main.systems.viewport.views;

import com.openmason.engine.modeling.EditableModel;
import com.openmason.engine.modeling.core.EditCostStats;
import com.openmason.engine.modeling.core.EditCostStats.Counter;
import com.openmason.engine.modeling.core.EditCostStats.Span;
import com.openmason.main.omConfig;
import com.openmason.main.systems.themes.utils.ThemeColors;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiCol;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The viewport's edit-cost stats overlay (#175 F0): frame time, the model rebuild / overlay / picking spans
 * ({@link EditCostStats}, last frame they ran + max over the last second), model upload KB per frame, overlay
 * draw calls and buffer uploads, and the model's size. View > Edit Stats Overlay toggles it (saved in the app
 * config, off by default); recording is enabled only while it shows, so it costs nothing otherwise. Thin ImGui
 * presentation, ASCII only.
 */
public final class EditStatsOverlay {

    private static Boolean enabled;
    private static long lastUploaded = -1;
    private static EditableModel lastModel;

    private EditStatsOverlay() {
    }

    /** True when the overlay shows (read from the app config on first use). */
    public static boolean isEnabled() {
        if (enabled == null) {
            enabled = new omConfig().isEditStatsOverlayEnabled();
            EditCostStats.setEnabled(enabled);
        }
        return enabled;
    }

    /** Toggles the overlay and saves the choice. */
    public static void toggle() {
        boolean on = !isEnabled();
        enabled = on;
        EditCostStats.setEnabled(on);
        if (on) {
            EditCostStats.reset();
            lastUploaded = -1;
        }
        omConfig config = new omConfig();
        config.setEditStatsOverlayEnabled(on);
        config.saveConfiguration();
    }

    /**
     * Closes the stats frame and draws the overlay in the viewport image's top-right corner. Call once per
     * viewport frame, after the 3D render.
     */
    public static void render(ImDrawList drawList, float imageX, float imageY, float imageWidth,
                              EditableModel model) {
        if (!isEnabled()) {
            return;
        }
        if (model != null) {
            long uploaded = model.getUploadedGpuBytes();
            if (model != lastModel || lastUploaded < 0 || uploaded < lastUploaded) {
                lastUploaded = uploaded;
                lastModel = model;
            }
            EditCostStats.count(Counter.MODEL_UPLOAD_BYTES, uploaded - lastUploaded);
            lastUploaded = uploaded;
        }
        EditCostStats.endFrame();

        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT, "Frame %6.2f ms   max %6.2f", EditCostStats.frameMs(),
                EditCostStats.maxFrameMs()));
        if (model != null) {
            lines.add(String.format(Locale.ROOT, "Model %d tris  %d corners  %d verts", model.getTriangleCount(),
                    model.getTotalVertexCount(), model.getUniqueVertexCount()));
        }
        lines.add(String.format(Locale.ROOT, "Upload %8.1f KB/frame  max %8.1f",
                EditCostStats.last(Counter.MODEL_UPLOAD_BYTES) / 1024.0,
                EditCostStats.max(Counter.MODEL_UPLOAD_BYTES) / 1024.0));
        lines.add(String.format(Locale.ROOT, "Overlay draws %d  uploads %d  (max %d / %d)",
                EditCostStats.last(Counter.DRAW_CALLS), EditCostStats.last(Counter.BUFFER_UPLOADS),
                EditCostStats.max(Counter.DRAW_CALLS), EditCostStats.max(Counter.BUFFER_UPLOADS)));
        for (Span span : Span.values()) {
            double max = EditCostStats.maxMs(span);
            double last = EditCostStats.lastMs(span);
            if (max <= 0 && last <= 0) continue;
            lines.add(String.format(Locale.ROOT, "%-15s %8.2f ms  max %8.2f", span.label(), last, max));
        }

        ImVec2 size = new ImVec2();
        float width = 0;
        float lineHeight = ImGui.getTextLineHeight();
        for (String line : lines) {
            ImGui.calcTextSize(size, line);
            width = Math.max(width, size.x);
        }
        float pad = 6f;
        float x1 = imageX + imageWidth - 10f;
        float x0 = x1 - width - pad * 2;
        float y0 = imageY + 10f;
        float y1 = y0 + lines.size() * lineHeight + pad * 2;
        drawList.addRectFilled(x0, y0, x1, y1, ThemeColors.u32(ImGuiCol.WindowBg, 0.85f), 4f);
        drawList.addRect(x0, y0, x1, y1, ThemeColors.u32(ImGuiCol.Border, 0.85f), 4f);
        int color = ThemeColors.u32(ImGuiCol.Text, 0.95f);
        float y = y0 + pad;
        for (String line : lines) {
            drawList.addText(x0 + pad, y, color, line);
            y += lineHeight;
        }
    }
}
