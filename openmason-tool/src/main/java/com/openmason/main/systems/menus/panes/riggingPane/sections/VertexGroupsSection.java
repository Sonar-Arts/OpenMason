package com.openmason.main.systems.menus.panes.riggingPane.sections;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.main.systems.ViewportController;
import com.openmason.main.systems.skeleton.vertexdata.VertexDataAuthoring;
import com.openmason.main.systems.skeleton.vertexdata.VertexDataHistory;
import com.openmason.main.systems.skeleton.vertexdata.VertexGroupStore;
import com.openmason.main.systems.themes.utils.ImGuiComponents;
import com.openmason.main.systems.themes.utils.ThemedWidgets;
import imgui.ImGui;
import imgui.flag.ImGuiInputTextFlags;
import imgui.type.ImBoolean;
import imgui.type.ImString;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rigging pane "Vertex Groups" section (OpenMason #172): named, weighted vertex sets (what masks hide - a garment's
 * covered skin). List with a "Hide" preview toggle per group, new group from the vertex selection, assign / remove
 * the selected vertices with a weight, select a group's vertices, rename, reorder, delete. Thin presentation over
 * {@link VertexDataAuthoring}: every edit is one {@link VertexDataHistory} step (undo domain {@code morph}, shared
 * with the {@code vgroup_*} MCP tools); "Hide" only changes the preview state. ASCII strings only.
 */
public class VertexGroupsSection {

    private ViewportController viewport;
    private String selected;
    private final ImString newName = new ImString("Group", 64);
    private final ImString rename = new ImString(64);
    private String renameFor;
    private final float[] weight = {1f};
    private String status;
    private boolean statusError;

    public void setViewport(ViewportController viewport) {
        this.viewport = viewport;
    }

    public void render() {
        if (viewport == null) return;
        ImGuiComponents.renderCompactSectionHeader("Vertex Groups");
        ImGui.spacing();
        VertexDataAuthoring data = viewport.getVertexData();
        List<OMOFormat.VertexGroupData> groups = data.groups().groups();
        if (selected != null && data.groups().indexOf(selected) < 0) selected = null;

        Set<Integer> selection = viewport.getSelectedVertices();
        ImGui.textDisabled(selection.isEmpty() ? "Select vertices (vertex mode) to assign them"
                : selection.size() + " vertices selected");

        ImGui.setNextItemWidth(ImGui.getContentRegionAvailX() * 0.5f);
        ImGui.inputText("##vg_new_name", newName);
        ImGui.sameLine();
        if (ImGui.button("New Group##vg_new", -1, 0)) {
            act("New group", () -> {
                String name = newName.get().isBlank() ? "Group" : newName.get().trim();
                Map<String, int[]> verts = data.localVertices(selection, viewport.getVertexIndexBridge());
                float w = weight[0];
                data.edit("New Vertex Group", () -> {
                    data.groups().add(name);
                    for (Map.Entry<String, int[]> e : verts.entrySet()) {
                        data.groups().assign(name, e.getKey(), e.getValue(), w, null);
                    }
                });
                selected = data.groups().get(name).name();
                status = "Group '" + selected + "'" + (verts.isEmpty() ? " (empty)" : "");
            });
        }
        if (ImGui.isItemHovered()) ImGui.setTooltip("Creates a group holding the selected vertices at the weight below");

        renderList(data, groups);
        ImGui.setNextItemWidth(ImGui.getContentRegionAvailX() * 0.6f);
        ImGui.sliderFloat("Weight##vg_weight", weight, 0f, 1f, "%.2f");
        if (selected != null) renderSelected(data, data.groups().get(selected), selection);

        ImGui.spacing();
        renderHistory(data.history());
        if (status != null) {
            if (statusError) ThemedWidgets.inlineError(status);
            else ImGui.textDisabled(status);
        }
    }

    private void renderList(VertexDataAuthoring data, List<OMOFormat.VertexGroupData> groups) {
        if (groups.isEmpty()) {
            ImGui.textDisabled("No vertex groups");
            return;
        }
        float row = ImGui.getFrameHeightWithSpacing();
        float height = Math.min(8, groups.size()) * row + ImGui.getStyle().getFramePaddingY() * 2;
        if (ImGui.beginChild("##vg_list", 0, height, true)) {
            float nameWidth = ImGui.getContentRegionAvailX() * 0.65f;
            for (OMOFormat.VertexGroupData g : groups) {
                boolean sel = g.name().equals(selected);
                String label = g.name() + "  (" + VertexGroupStore.rowCount(g) + ")";
                if (ImGui.selectable(label + "##vg_row_" + g.name(), sel, 0, nameWidth, 0)) selected = g.name();
                ImGui.sameLine();
                ImBoolean hidden = new ImBoolean(data.preview().isHidden(g.name()));
                if (ImGui.checkbox("Hide##vg_hide_" + g.name(), hidden)) data.preview().setHidden(g.name(), hidden.get());
                if (ImGui.isItemHovered()) {
                    ImGui.setTooltip("Hide the group's faces in the preview (a face hides when all its vertices are in "
                            + "the group); not saved");
                }
            }
        }
        ImGui.endChild();
    }

    private void renderSelected(VertexDataAuthoring data, OMOFormat.VertexGroupData group, Set<Integer> selection) {
        if (group == null) return;
        ThemedWidgets.sectionLabel("Group: " + group.name());
        if (!group.name().equals(renameFor)) {
            rename.set(group.name());
            renameFor = group.name();
        }
        ImGui.setNextItemWidth(ImGui.getContentRegionAvailX() * 0.6f);
        if (ImGui.inputText("Name##vg_rename", rename, ImGuiInputTextFlags.EnterReturnsTrue)
                && !rename.get().trim().equals(group.name())) {
            act("Rename", () -> {
                data.renameGroup(group.name(), rename.get());
                selected = data.groups().get(rename.get().trim()).name();
            });
            renameFor = null;
        }

        boolean any = !selection.isEmpty();
        float third = (ImGui.getContentRegionAvailX() - ImGui.getStyle().getItemSpacingX() * 2) / 3f;
        if (!any) ImGui.beginDisabled();
        if (ImGui.button("Assign##vg_assign", third, 0)) {
            float w = weight[0];
            act("Assign", () -> {
                Map<String, int[]> verts = data.localVertices(selection, viewport.getVertexIndexBridge());
                int[] n = new int[1];
                data.edit("Assign Vertex Group", () -> {
                    for (Map.Entry<String, int[]> e : verts.entrySet()) {
                        n[0] += data.groups().assign(group.name(), e.getKey(), e.getValue(), w, null);
                    }
                });
                status = n[0] + " vertices assigned";
            });
        }
        if (ImGui.isItemHovered()) ImGui.setTooltip("Adds the selected vertices at the weight (replaces their weight)");
        ImGui.sameLine();
        if (ImGui.button("Remove##vg_unassign", third, 0)) {
            act("Remove", () -> {
                Map<String, int[]> verts = data.localVertices(selection, viewport.getVertexIndexBridge());
                int[] n = new int[1];
                data.edit("Remove From Vertex Group", () -> {
                    for (Map.Entry<String, int[]> e : verts.entrySet()) {
                        n[0] += data.groups().unassign(group.name(), e.getKey(), e.getValue());
                    }
                });
                status = n[0] + " vertices removed";
            });
        }
        if (!any) ImGui.endDisabled();
        ImGui.sameLine();
        if (ImGui.button("Select##vg_select", third, 0)) {
            act("Select", () -> {
                Set<Integer> verts = data.uniqueVertices(group.name(), viewport.getVertexIndexBridge());
                viewport.selectVertices(verts);
                status = verts.size() + " vertices selected";
            });
        }
        if (ImGui.isItemHovered()) ImGui.setTooltip("Selects the group's vertices (weight above 0) in vertex mode");

        int index = data.groups().indexOf(group.name());
        int count = data.groups().size();
        if (index <= 0) ImGui.beginDisabled();
        if (ImGui.button("Up##vg_up", third, 0)) act("Move", () -> data.edit("Move Vertex Group",
                () -> data.groups().move(group.name(), index - 1)));
        if (index <= 0) ImGui.endDisabled();
        ImGui.sameLine();
        if (index >= count - 1) ImGui.beginDisabled();
        if (ImGui.button("Down##vg_down", third, 0)) act("Move", () -> data.edit("Move Vertex Group",
                () -> data.groups().move(group.name(), index + 1)));
        if (index >= count - 1) ImGui.endDisabled();
        ImGui.sameLine();
        if (ThemedWidgets.dangerSoftButton("Delete##vg_delete", third, 0)) {
            act("Delete", () -> data.edit("Delete Vertex Group", () -> data.groups().remove(group.name())));
            selected = null;
        }
    }

    private void renderHistory(VertexDataHistory history) {
        float half = (ImGui.getContentRegionAvailX() - ImGui.getStyle().getItemSpacingX()) * 0.5f;
        boolean canUndo = history.canUndo(), canRedo = history.canRedo();
        if (!canUndo) ImGui.beginDisabled();
        if (ImGui.button("Undo##vg_undo", half, 0)) history.undo();
        if (canUndo && ImGui.isItemHovered()) ImGui.setTooltip("Undo " + history.undoDescription());
        if (!canUndo) ImGui.endDisabled();
        ImGui.sameLine();
        if (!canRedo) ImGui.beginDisabled();
        if (ImGui.button("Redo##vg_redo", half, 0)) history.redo();
        if (canRedo && ImGui.isItemHovered()) ImGui.setTooltip("Redo " + history.redoDescription());
        if (!canRedo) ImGui.endDisabled();
    }

    private void act(String what, Runnable edit) {
        try {
            status = null;
            edit.run();
            statusError = false;
        } catch (RuntimeException e) {
            status = what + " failed: " + e.getMessage();
            statusError = true;
        }
    }
}
