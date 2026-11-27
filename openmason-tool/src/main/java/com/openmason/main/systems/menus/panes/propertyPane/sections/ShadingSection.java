package com.openmason.main.systems.menus.panes.propertyPane.sections;

import com.openmason.engine.modeling.EditableModel;
import com.openmason.engine.modeling.shading.ModelShading;
import com.openmason.main.systems.ViewportController;
import com.openmason.main.systems.menus.panes.propertyPane.interfaces.IPanelSection;
import com.openmason.main.systems.shading.ShadingOps;
import com.openmason.main.systems.viewport.state.EdgeSelectionState;
import com.openmason.main.systems.viewport.state.FaceSelectionState;
import imgui.ImGui;
import imgui.type.ImFloat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Model Properties "Shading" (OpenMason #169): Shade Smooth / Flat / Auto Smooth, Mark / Clear Sharp and Clear
 * Custom Normals for the current selection - the selected faces (edges for Mark / Clear Sharp), else the selected
 * parts, else the whole model. Thin ImGui over {@link ShadingOps}; each button is one undo step.
 */
public final class ShadingSection implements IPanelSection {

    private static final Logger logger = LoggerFactory.getLogger(ShadingSection.class);

    private ViewportController viewport;
    private final ImFloat autoSmoothDegrees = new ImFloat(30f);
    private String lastError;

    public void setViewport(ViewportController viewport) {
        this.viewport = viewport;
    }

    @Override
    public boolean isVisible() {
        return viewport != null && viewport.getModelRenderer() != null && ModelShading.isAvailable();
    }

    @Override
    public String getSectionName() {
        return "Shading";
    }

    @Override
    public void render() {
        EditableModel model = viewport.getModelRenderer();
        int[] faces = selectedFaces();
        int[] edges = selectedEdgeVertices();
        List<String> parts = selectedParts();
        String target = faces != null ? faces.length + " selected face(s)"
                : parts != null ? parts.size() + " selected part(s)" : "the whole model";
        ImGui.textDisabled("Applies to " + target);

        float w = (ImGui.getContentRegionAvailX() - ImGui.getStyle().getItemSpacingX()) / 2f;
        if (ImGui.button("Shade Smooth", w, 0)) {
            run(model, "Shade Smooth", () -> ShadingOps.apply(model, ShadingOps.Mode.SMOOTH, faces, parts, 0f));
        }
        ImGui.sameLine();
        if (ImGui.button("Shade Flat", w, 0)) {
            run(model, "Shade Flat", () -> ShadingOps.apply(model, ShadingOps.Mode.FLAT, faces, parts, 0f));
        }

        ImGui.setNextItemWidth(w);
        ImGui.dragFloat("##autoSmoothDegrees", autoSmoothDegrees.getData(), 0.5f, 0f, 180f, "%.1f deg");
        ImGui.sameLine();
        if (ImGui.button("Shade Auto Smooth", w, 0)) {
            float degrees = autoSmoothDegrees.get();
            run(model, "Shade Auto Smooth", () -> ShadingOps.apply(model, ShadingOps.Mode.AUTO, faces, parts,
                    degrees));
        }

        ImGui.beginDisabled(edges == null);
        if (ImGui.button("Mark Sharp", w, 0)) {
            run(model, "Mark Sharp", () -> ShadingOps.sharp(model,
                    ShadingOps.edgesFromUniqueVertices(model, edges), true, null));
        }
        ImGui.sameLine();
        if (ImGui.button("Clear Sharp", w, 0)) {
            run(model, "Clear Sharp", () -> ShadingOps.sharp(model,
                    ShadingOps.edgesFromUniqueVertices(model, edges), false, null));
        }
        ImGui.endDisabled();
        if (edges == null && ImGui.isItemHovered()) {
            ImGui.setTooltip("Select edges to mark or clear them sharp");
        }

        if (ImGui.button("Clear All Sharp", w, 0)) {
            run(model, "Clear All Sharp", () -> ShadingOps.sharp(model, null, false, parts));
        }
        ImGui.sameLine();
        if (ImGui.button("Clear Custom Normals", w, 0)) {
            run(model, "Clear Custom Normals",
                    () -> ShadingOps.apply(model, ShadingOps.Mode.CLEAR_CUSTOM, faces, parts, 0f));
        }
        if (lastError != null) {
            ImGui.textWrapped(lastError);
        }
    }

    private void run(EditableModel model, String label, Runnable action) {
        try {
            ShadingOps.recorded(model, viewport.getCommandHistory(), viewport.getRendererSynchronizer(), label,
                    action);
            lastError = null;
        } catch (RuntimeException e) {
            lastError = label + " failed: " + e.getMessage();
            logger.warn("{} failed", label, e);
        }
    }

    /** The selected faces (global ids), or null. */
    private int[] selectedFaces() {
        FaceSelectionState s = viewport.getFaceSelectionState();
        if (s == null || !s.hasSelection()) return null;
        Set<Integer> ids = s.getSelectedFaceIndices();
        return ids.isEmpty() ? null : ids.stream().mapToInt(Integer::intValue).toArray();
    }

    /** The selected edges as unique vertex pairs, or null. */
    private int[] selectedEdgeVertices() {
        EdgeSelectionState s = viewport.getEdgeSelectionState();
        if (s == null || !s.hasSelection()) return null;
        List<Integer> pairs = new ArrayList<>();
        for (int edge : s.getSelectedEdgeIndices()) {
            int[] v = s.getEdgeVertexIndices(edge);
            if (v != null && v.length == 2) {
                pairs.add(v[0]);
                pairs.add(v[1]);
            }
        }
        return pairs.isEmpty() ? null : pairs.stream().mapToInt(Integer::intValue).toArray();
    }

    /** The selected parts, or null (= the whole model). */
    private List<String> selectedParts() {
        Set<String> ids = viewport.getPartManager().getSelectedPartIds();
        return ids == null || ids.isEmpty() ? null : List.copyOf(ids);
    }
}
