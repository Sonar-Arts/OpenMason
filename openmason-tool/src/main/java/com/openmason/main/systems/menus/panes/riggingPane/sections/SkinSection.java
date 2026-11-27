package com.openmason.main.systems.menus.panes.riggingPane.sections;

import com.openmason.engine.modeling.parts.ModelPartDescriptor;
import com.openmason.engine.rig.Skin;
import com.openmason.main.systems.ViewportController;
import com.openmason.main.systems.keybinds.KeybindRegistry;
import com.openmason.main.systems.skeleton.SkinAuthoring;
import com.openmason.main.systems.skeleton.SkinCommandHistory;
import com.openmason.main.systems.skeleton.WeightPaintState;
import com.openmason.main.systems.themes.utils.ImGuiComponents;
import com.openmason.main.systems.themes.utils.ThemeColors;
import com.openmason.main.systems.themes.utils.ThemedWidgets;
import com.openmason.main.systems.viewport.ViewportKeybindActions;
import imgui.ImGui;
import imgui.type.ImBoolean;
import imgui.type.ImFloat;
import imgui.type.ImInt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntSupplier;

/**
 * Rigging pane "Skin" section: per-vertex skin weights of the selected parts (or all parts). Bind
 * (auto weights over chosen bones, rigid nearest-bone), clean-up (clear, normalize, prune, mirror) and
 * the weight-paint brush settings. Thin presentation: every action is one {@link SkinCommandHistory}
 * step around a {@link SkinAuthoring} call, which marshals to the native skin ops.
 */
public class SkinSection {

    private static final String[] AXES = {"X", "Y", "Z"};
    private static final String[] DIRECTIONS = {"+ to -", "- to +"};
    private static final String[] MODES = {"Add", "Subtract", "Replace", "Smooth"};

    private ViewportController viewport;

    private final ImBoolean allParts = new ImBoolean(false);
    private final ImFloat falloff = new ImFloat(2.0f);
    private final ImFloat minWeight = new ImFloat(0.05f);
    private final ImBoolean rigid = new ImBoolean(false);
    /** Auto weights: nearest bones per vertex, 0 = unlimited (every bone, like Blender). */
    private final ImInt maxInfluences = new ImInt(0);
    private final ImFloat pruneBelow = new ImFloat(0.05f);
    private final ImInt mirrorAxis = new ImInt(0);
    private final ImInt mirrorDirection = new ImInt(0);
    private final ImFloat mirrorTolerance = new ImFloat(0.01f);
    private final ImFloat brushRadius = new ImFloat(0.25f);
    private final ImFloat brushStrength = new ImFloat(0.5f);

    /** Bones left OUT of auto weights (default: every bone is used). */
    private final Set<String> excludedBones = new HashSet<>();
    private String status;
    private boolean statusError;

    public void setViewport(ViewportController viewport) {
        this.viewport = viewport;
    }

    public void render() {
        if (viewport == null) return;
        ImGuiComponents.renderCompactSectionHeader("Skin");
        ImGui.spacing();

        SkinAuthoring skin = viewport.getSkinAuthoring();
        List<String> targets = targetParts(skin);

        ImGui.checkbox("All parts##skin_all", allParts);
        if (ImGui.isItemHovered()) ImGui.setTooltip("Apply operations to every part instead of the selection");
        renderStats(skin, targets);

        ImGui.spacing();
        ThemedWidgets.sectionLabel("Bind");
        renderBind(skin, targets);

        ImGui.spacing();
        ThemedWidgets.sectionLabel("Clean Up");
        renderCleanUp(skin, targets);

        ImGui.spacing();
        ThemedWidgets.sectionLabel("Weight Paint");
        renderPaint(skin);

        ImGui.spacing();
        renderHistory();
        if (status != null) {
            if (statusError) ThemedWidgets.inlineError(status);
            else ImGui.textDisabled(status);
        }
    }

    // ---- sections ---------------------------------------------------------------------------------

    private void renderStats(SkinAuthoring skin, List<String> targets) {
        if (targets.isEmpty()) {
            ImGui.textDisabled(allParts.get() ? "The model has no parts" : "Select a part (or tick All parts)");
            return;
        }
        int verts = 0, weighted = 0;
        for (String id : targets) {
            verts += skin.vertexCount(id);
            weighted += skin.weightedCount(id);
        }
        String label = targets.size() == 1
                ? skin.parts().getPartById(targets.get(0)).map(ModelPartDescriptor::name).orElse("?")
                : targets.size() + " parts";
        ImGui.textDisabled(label + ": " + weighted + " / " + verts + " vertices weighted");
    }

    private void renderBind(SkinAuthoring skin, List<String> targets) {
        List<SkinAuthoring.JointRef> boneRefs = skin.boneJoints();
        if (boneRefs.isEmpty()) {
            ImGui.textDisabled("Add bones to bind weights to them");
            return;
        }
        float rowHeight = ImGui.getTextLineHeightWithSpacing();
        float listHeight = Math.min(6, boneRefs.size()) * rowHeight + ImGui.getStyle().getFramePaddingY() * 2;
        if (ImGui.beginChild("##skin_bones", 0, listHeight, true)) {
            for (SkinAuthoring.JointRef b : boneRefs) {
                ImBoolean on = new ImBoolean(!excludedBones.contains(b.id()));
                if (ImGui.checkbox(b.name() + "##skin_bone_" + b.id(), on)) {
                    if (on.get()) excludedBones.remove(b.id());
                    else excludedBones.add(b.id());
                }
            }
        }
        ImGui.endChild();
        if (ImGui.smallButton("All##skin_bones_all")) excludedBones.clear();
        ImGui.sameLine();
        if (ImGui.smallButton("None##skin_bones_none")) {
            for (SkinAuthoring.JointRef b : boneRefs) excludedBones.add(b.id());
        }

        ImGui.checkbox("Rigid (nearest bone)##skin_rigid", rigid);
        if (!rigid.get()) {
            ImGui.setNextItemWidth(ImGui.getContentRegionAvailX() * 0.5f);
            ImGui.dragFloat("Falloff##skin_falloff", falloff.getData(), 0.05f, 0.1f, 8f, "%.2f");
            if (ImGui.isItemHovered()) ImGui.setTooltip("Higher = weights fall off faster with distance from a bone");
            ImGui.setNextItemWidth(ImGui.getContentRegionAvailX() * 0.5f);
            ImGui.dragFloat("Min weight##skin_min", minWeight.getData(), 0.005f, 0f, 0.5f, "%.3f");
            ImGui.setNextItemWidth(ImGui.getContentRegionAvailX() * 0.5f);
            if (ImGui.inputInt("Max influences##skin_max_infl", maxInfluences)) {
                maxInfluences.set(Math.max(0, maxInfluences.get()));
            }
            if (ImGui.isItemHovered()) ImGui.setTooltip("Bones per vertex, nearest first; 0 = unlimited (every bone)");
        }

        List<String> chosen = new ArrayList<>();
        for (SkinAuthoring.JointRef b : boneRefs) if (!excludedBones.contains(b.id())) chosen.add(b.id());
        boolean enabled = !targets.isEmpty() && !chosen.isEmpty();
        if (!enabled) ImGui.beginDisabled();
        if (ThemedWidgets.accentButton("Auto Weights##skin_auto", -1, 0)) {
            float f = rigid.get() ? 0f : falloff.get();
            int limit = rigid.get() ? 0 : maxInfluences.get();
            act("Auto Weights", () -> skin.autoWeights(targets, chosen, f, minWeight.get(), limit),
                    n -> n + " vertices weighted");
        }
        if (!enabled) ImGui.endDisabled();
    }

    private void renderCleanUp(SkinAuthoring skin, List<String> targets) {
        boolean enabled = !targets.isEmpty();
        if (!enabled) ImGui.beginDisabled();
        float half = (ImGui.getContentRegionAvailX() - ImGui.getStyle().getItemSpacingX()) * 0.5f;
        if (ImGui.button("Normalize##skin_norm", half, 0)) {
            act("Normalize Weights", () -> skin.normalize(targets), n -> n + " weighted vertices normalized");
        }
        ImGui.sameLine();
        if (ThemedWidgets.dangerSoftButton("Clear Weights##skin_clear", half, 0)) {
            act("Clear Weights", () -> skin.clear(targets), n -> n + " vertices now follow their part");
        }

        ImGui.setNextItemWidth(ImGui.getContentRegionAvailX() * 0.5f);
        ImGui.dragFloat("##skin_prune_v", pruneBelow.getData(), 0.005f, 0f, 0.5f, "< %.3f");
        ImGui.sameLine();
        if (ImGui.button("Prune##skin_prune", -1, 0)) {
            float below = pruneBelow.get();
            act("Prune Weights", () -> skin.prune(targets, below), n -> n + " small influences removed");
        }

        ImGui.setNextItemWidth(ImGui.getContentRegionAvailX() * 0.25f);
        ImGui.combo("##skin_mirror_axis", mirrorAxis, AXES);
        ImGui.sameLine();
        ImGui.setNextItemWidth(ImGui.getContentRegionAvailX() * 0.45f);
        ImGui.combo("##skin_mirror_dir", mirrorDirection, DIRECTIONS);
        ImGui.sameLine();
        if (ImGui.button("Mirror##skin_mirror", -1, 0)) {
            int axis = mirrorAxis.get();
            int sign = mirrorDirection.get() == 0 ? 1 : -1;
            float tol = mirrorTolerance.get();
            act("Mirror Weights", () -> skin.mirror(targets, axis, sign, tol), n -> n + " vertices mirrored");
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Copies weights across the model-space plane; joints swap by name\n"
                    + "(_l/_r, .L/.R, left/right). Tick All parts to mirror between parts.");
        }
        ImGui.setNextItemWidth(ImGui.getContentRegionAvailX() * 0.5f);
        ImGui.dragFloat("Tolerance##skin_mirror_tol", mirrorTolerance.getData(), 0.001f, 0f, 1f, "%.3f");
        if (!enabled) ImGui.endDisabled();
    }

    private void renderPaint(SkinAuthoring skin) {
        WeightPaintState paint = viewport.getWeightPaintState();
        String shortcut = KeybindRegistry.getInstance().getShortcutDisplayName(ViewportKeybindActions.TOGGLE_WEIGHT_PAINT);
        boolean active = paint.isActive();
        if (active) ThemeColors.pushToggleOn();
        if (ImGui.button((active ? "Painting" : "Weight Paint") + "##skin_paint_toggle", -1, 0)) paint.toggle();
        if (active) ImGui.popStyleColor(3);
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Paint the chosen joint's weights in the viewport"
                    + (shortcut.isEmpty() ? "" : " (" + shortcut + ")")
                    + ".\nLeft-drag on the mesh paints; left-drag off it or Alt+drag orbits.");
        }

        // Joint picker: bones first, then parts.
        List<SkinAuthoring.JointRef> joints = new ArrayList<>(skin.boneJoints());
        for (SkinAuthoring.JointRef j : skin.joints()) if (!j.bone()) joints.add(j);
        if (paint.getJointId() != null && joints.stream().noneMatch(j -> j.id().equals(paint.getJointId()))) {
            paint.setJointId(null);
        }
        if (paint.getJointId() == null && !joints.isEmpty()) paint.setJointId(joints.get(0).id());
        String current = paint.getJointId() == null ? "(none)" : skin.jointName(paint.getJointId());
        ImGui.setNextItemWidth(-1);
        if (ImGui.beginCombo("##skin_paint_joint", "Joint: " + current)) {
            for (SkinAuthoring.JointRef j : joints) {
                boolean selected = j.id().equals(paint.getJointId());
                if (ImGui.selectable((j.bone() ? "bone  " : "part  ") + j.name() + "##skin_j_" + j.id(), selected)) {
                    paint.setJointId(j.id());
                }
                if (selected) ImGui.setItemDefaultFocus();
            }
            ImGui.endCombo();
        }

        ImInt mode = new ImInt(paint.getMode().ordinal());
        ImGui.setNextItemWidth(-1);
        if (ImGui.combo("##skin_paint_mode", mode, MODES)) {
            paint.setMode(Skin.PaintMode.values()[mode.get()]);
        }
        brushRadius.set(paint.getRadius());
        ImGui.setNextItemWidth(ImGui.getContentRegionAvailX() * 0.5f);
        if (ImGui.dragFloat("Radius##skin_radius", brushRadius.getData(), 0.005f, 0.005f, 10f, "%.3f")) {
            paint.setRadius(brushRadius.get());
        }
        brushStrength.set(paint.getStrength());
        ImGui.setNextItemWidth(ImGui.getContentRegionAvailX() * 0.5f);
        if (ImGui.sliderFloat("Strength##skin_strength", brushStrength.getData(), 0f, 1f, "%.2f")) {
            paint.setStrength(brushStrength.get());
        }
        if (active) {
            ImGui.textDisabled("Heat map: blue 0 .. red 1 for the chosen joint");
        }
    }

    private void renderHistory() {
        SkinCommandHistory history = viewport.getSkinHistory();
        float half = (ImGui.getContentRegionAvailX() - ImGui.getStyle().getItemSpacingX()) * 0.5f;
        boolean canUndo = history.canUndo(), canRedo = history.canRedo();
        if (!canUndo) ImGui.beginDisabled();
        if (ImGui.button("Undo##skin_undo", half, 0)) history.undo();
        if (canUndo && ImGui.isItemHovered()) ImGui.setTooltip("Undo " + history.undoDescription());
        if (!canUndo) ImGui.endDisabled();
        ImGui.sameLine();
        if (!canRedo) ImGui.beginDisabled();
        if (ImGui.button("Redo##skin_redo", half, 0)) history.redo();
        if (!canRedo) ImGui.endDisabled();
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private List<String> targetParts(SkinAuthoring skin) {
        if (allParts.get()) return skin.partIds();
        List<String> out = new ArrayList<>();
        Collection<String> selected = skin.parts().getSelectedPartIds();
        for (String id : skin.partIds()) if (selected.contains(id)) out.add(id);
        return out;
    }

    /** Runs one skin operation as one undo step and reports its count (or its error) below the section. */
    private void act(String description, IntSupplier op, java.util.function.IntFunction<String> message) {
        int[] result = new int[1];
        try {
            viewport.getSkinHistory().record(description, () -> result[0] = op.getAsInt());
            status = description + ": " + message.apply(result[0]);
            statusError = false;
        } catch (RuntimeException e) {
            status = description + " failed: " + e.getMessage();
            statusError = true;
        }
    }
}
