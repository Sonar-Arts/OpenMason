package com.openmason.main.systems.menus.panes.riggingPane.sections;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.modeling.parts.PartMeshRebuilder;
import com.openmason.main.systems.ViewportController;
import com.openmason.main.systems.skeleton.vertexdata.BasisRestore;
import com.openmason.main.systems.skeleton.vertexdata.ShapeKeyStore;
import com.openmason.main.systems.skeleton.vertexdata.VertexDataAuthoring;
import com.openmason.main.systems.skeleton.vertexdata.VertexDataHistory;
import com.openmason.main.systems.skeleton.vertexdata.VertexDataPreview;
import com.openmason.main.systems.themes.utils.ImGuiComponents;
import com.openmason.main.systems.themes.utils.ThemedWidgets;
import imgui.ImGui;
import imgui.flag.ImGuiInputTextFlags;
import imgui.type.ImString;

import java.util.List;
import java.util.Map;

/**
 * Rigging pane "Shape Keys" section (OpenMason #171): the model's shape keys (morph targets) - list with preview
 * sliders, a new key captured from vertex moves (Blender: sculpt from the basis, then "new shape from mix"), rename,
 * reorder, remove, default / min / max. Thin presentation over {@link VertexDataAuthoring}: every edit is one
 * {@link VertexDataHistory} step (undo domain {@code morph}, shared with the {@code morph_*} MCP tools); the sliders
 * only change the {@link VertexDataPreview}. ASCII strings only.
 */
public class ShapeKeysSection {

    private ViewportController viewport;
    private String selected;
    private final ImString newName = new ImString("Key", 64);
    private final ImString rename = new ImString(64);
    private String renameFor;
    private final float[] defaultValue = {0f};
    private final float[] range = {0f, 1f};
    private String status;
    private boolean statusError;

    public void setViewport(ViewportController viewport) {
        this.viewport = viewport;
    }

    public void render() {
        if (viewport == null) return;
        ImGuiComponents.renderCompactSectionHeader("Shape Keys");
        ImGui.spacing();
        VertexDataAuthoring data = viewport.getVertexData();
        List<OMOFormat.MorphData> keys = data.shapeKeys().morphs();
        if (selected != null && data.shapeKeys().indexOf(selected) < 0) selected = null;

        renderCapture(data);
        ImGui.spacing();
        renderList(data, keys);
        if (selected != null) {
            ImGui.spacing();
            renderSelected(data, data.shapeKeys().get(selected));
        }
        ImGui.spacing();
        renderHistory(data.history());
        if (status != null) {
            if (statusError) ThemedWidgets.inlineError(status);
            else ImGui.textDisabled(status);
        }
    }

    // ---- capture ----------------------------------------------------------------------------------

    private void renderCapture(VertexDataAuthoring data) {
        if (!data.isCapturing()) {
            if (ThemedWidgets.accentButton("New Shape Key##sk_begin", -1, 0)) {
                data.beginCapture();
                status = "Capturing: move vertices, then Capture";
                statusError = false;
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Remembers the mesh as the basis. Move vertices with the normal tools,\n"
                        + "then Capture: the moves become a shape key and the mesh returns to the basis.");
            }
            return;
        }
        ImGui.textDisabled("Capturing: move vertices, then Capture");
        ImGui.setNextItemWidth(ImGui.getContentRegionAvailX() * 0.5f);
        ImGui.inputText("Name##sk_new_name", newName);
        float half = (ImGui.getContentRegionAvailX() - ImGui.getStyle().getItemSpacingX()) * 0.5f;
        if (ThemedWidgets.accentButton("Capture##sk_capture", half, 0)) capture(data);
        ImGui.sameLine();
        if (ImGui.button("Cancel##sk_cancel", half, 0)) {
            data.cancelCapture();
            status = "Capture cancelled (the moves stay on the mesh; undo them with Ctrl+Z)";
            statusError = false;
        }
    }

    private void capture(VertexDataAuthoring data) {
        try {
            Map<String, PartMeshRebuilder.PartGeometry> basis = data.basisGeometry();
            String name = newName.get().isBlank() ? "Key" : newName.get().trim();
            int index = data.finishCapture(uniqueName(data, name), false);
            OMOFormat.MorphData key = data.shapeKeys().morphs().get(index);
            selected = key.name();
            data.preview().setValue(key.name(), 1f);
            BasisRestore.apply(viewport, basis);
            status = "Shape key '" + key.name() + "': " + ShapeKeyStore.rowCount(key) + " vertices";
            statusError = false;
        } catch (RuntimeException e) {
            status = "Capture failed: " + e.getMessage();
            statusError = true;
        }
    }

    private static String uniqueName(VertexDataAuthoring data, String base) {
        String n = base;
        for (int i = 1; data.shapeKeys().indexOf(n) >= 0; i++) n = base + "." + String.format("%03d", i);
        return n;
    }

    // ---- list -------------------------------------------------------------------------------------

    private void renderList(VertexDataAuthoring data, List<OMOFormat.MorphData> keys) {
        if (keys.isEmpty()) {
            ImGui.textDisabled("No shape keys");
            return;
        }
        VertexDataPreview preview = data.preview();
        float row = ImGui.getFrameHeightWithSpacing();
        float height = Math.min(8, keys.size()) * row + ImGui.getStyle().getFramePaddingY() * 2;
        if (ImGui.beginChild("##sk_list", 0, height, true)) {
            float nameWidth = ImGui.getContentRegionAvailX() * 0.45f;
            for (OMOFormat.MorphData k : keys) {
                boolean sel = k.name().equals(selected);
                if (ImGui.selectable(k.name() + "##sk_row_" + k.name(), sel, 0, nameWidth, 0)) selected = k.name();
                ImGui.sameLine();
                float[] v = {preview.value(k)};
                ImGui.setNextItemWidth(-1);
                if (ImGui.sliderFloat("##sk_val_" + k.name(), v, k.min(), k.max(), "%.2f")) {
                    preview.setValue(k.name(), v[0]);
                }
                if (ImGui.isItemHovered()) ImGui.setTooltip("Preview value (not saved); default " + k.defaultWeight());
            }
        }
        ImGui.endChild();
        if (ImGui.smallButton("Reset preview##sk_reset")) preview.resetValues();
        if (ImGui.isItemHovered()) ImGui.setTooltip("Every slider back to its key's default");
    }

    // ---- selected key -----------------------------------------------------------------------------

    private void renderSelected(VertexDataAuthoring data, OMOFormat.MorphData key) {
        if (key == null) return;
        ThemedWidgets.sectionLabel("Key: " + key.name());
        if (!key.name().equals(renameFor)) {
            rename.set(key.name());
            renameFor = key.name();
            defaultValue[0] = key.defaultWeight();
            range[0] = key.min();
            range[1] = key.max();
        }
        ImGui.setNextItemWidth(ImGui.getContentRegionAvailX() * 0.6f);
        if (ImGui.inputText("Name##sk_rename", rename, ImGuiInputTextFlags.EnterReturnsTrue)
                && !rename.get().trim().equals(key.name())) {
            act("Rename", () -> {
                data.renameShapeKey(key.name(), rename.get());
                selected = data.shapeKeys().get(rename.get().trim()).name();
            });
            renameFor = null;
        }
        ImGui.setNextItemWidth(ImGui.getContentRegionAvailX() * 0.6f);
        ImGui.dragFloat("Default##sk_default", defaultValue, 0.01f, key.min(), key.max(), "%.3f");
        if (ImGui.isItemDeactivatedAfterEdit()) {
            float d = defaultValue[0];
            act("Default", () -> data.edit("Shape Key Default",
                    () -> data.shapeKeys().setParams(key.name(), d, null, null)));
            renameFor = null;
        }
        ImGui.setNextItemWidth(ImGui.getContentRegionAvailX() * 0.6f);
        ImGui.dragFloat2("Range##sk_range", range, 0.01f, -ShapeKeyStore.RANGE_LIMIT, ShapeKeyStore.RANGE_LIMIT, "%.2f");
        if (ImGui.isItemHovered()) ImGui.setTooltip("Min / max: the value's hard range (Blender's Range Min / Max)");
        if (ImGui.isItemDeactivatedAfterEdit()) {
            float lo = range[0], hi = range[1];
            act("Range", () -> data.edit("Shape Key Range", () -> {
                float d = Math.max(lo, Math.min(hi, key.defaultWeight()));
                data.shapeKeys().setParams(key.name(), d, lo, hi);
            }));
            renameFor = null;
        }
        ImGui.textDisabled(ShapeKeyStore.rowCount(key) + " vertices over " + key.parts().size() + " part(s)");

        int index = data.shapeKeys().indexOf(key.name());
        int count = data.shapeKeys().size();
        float third = (ImGui.getContentRegionAvailX() - ImGui.getStyle().getItemSpacingX() * 2) / 3f;
        if (index <= 0) ImGui.beginDisabled();
        if (ImGui.button("Up##sk_up", third, 0)) act("Move", () -> data.edit("Move Shape Key",
                () -> data.shapeKeys().move(key.name(), index - 1)));
        if (index <= 0) ImGui.endDisabled();
        ImGui.sameLine();
        if (index >= count - 1) ImGui.beginDisabled();
        if (ImGui.button("Down##sk_down", third, 0)) act("Move", () -> data.edit("Move Shape Key",
                () -> data.shapeKeys().move(key.name(), index + 1)));
        if (index >= count - 1) ImGui.endDisabled();
        ImGui.sameLine();
        if (ThemedWidgets.dangerSoftButton("Remove##sk_remove", third, 0)) {
            act("Remove", () -> data.edit("Remove Shape Key", () -> data.shapeKeys().remove(key.name())));
            selected = null;
        }
    }

    private void renderHistory(VertexDataHistory history) {
        float half = (ImGui.getContentRegionAvailX() - ImGui.getStyle().getItemSpacingX()) * 0.5f;
        boolean canUndo = history.canUndo(), canRedo = history.canRedo();
        if (!canUndo) ImGui.beginDisabled();
        if (ImGui.button("Undo##sk_undo", half, 0)) history.undo();
        if (canUndo && ImGui.isItemHovered()) ImGui.setTooltip("Undo " + history.undoDescription());
        if (!canUndo) ImGui.endDisabled();
        ImGui.sameLine();
        if (!canRedo) ImGui.beginDisabled();
        if (ImGui.button("Redo##sk_redo", half, 0)) history.redo();
        if (canRedo && ImGui.isItemHovered()) ImGui.setTooltip("Redo " + history.redoDescription());
        if (!canRedo) ImGui.endDisabled();
    }

    private void act(String what, Runnable edit) {
        try {
            edit.run();
            status = null;
        } catch (RuntimeException e) {
            status = what + " failed: " + e.getMessage();
            statusError = true;
        }
    }
}
