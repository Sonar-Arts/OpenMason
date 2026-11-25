package com.openmason.main.systems.character;

import com.openmason.engine.character.CharacterDefinition;
import com.openmason.engine.character.CharacterDefinition.Vec3;
import com.openmason.engine.character.CharacterOverlay;
import com.openmason.engine.character.CharacterProfile;
import com.openmason.engine.rig.Rig;
import com.openmason.engine.rig.RigJoint;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiTableFlags;
import imgui.flag.ImGuiTreeNodeFlags;
import imgui.type.ImString;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The Character tab's panel content (OpenMason #163), ImGui only: every action is a native setter or call run
 * through {@link CharacterDocument#edit} (one undo step; drags share a merge key so one drag is one step).
 * Selection state lives here; the definition, checks and layouts come from Cenda.
 */
final class CharacterPanels {

    private static final String[] FACINGS = {"-z", "+z", "-x", "+x"};

    private final CharacterSessions sessions;
    private int selectedRole = -1;   // profile role index
    private boolean assigning;       // the next joint pick maps the selected role
    private int selectedJoint = -1;  // rig joint index
    private int selectedSocket = -1; // definition socket index
    private int selectedChain = -1;
    private int selectedGoal = -1;
    private boolean bonesOnly = true;
    private final ImString filter = new ImString(64);
    private final ImString nameField = new ImString(128);
    private final ImString newSocket = new ImString(64);
    private final ImString newChain = new ImString(64);
    private final ImString newGoal = new ImString(64);
    private String error;
    private String note;

    CharacterPanels(CharacterSessions sessions) {
        this.sessions = sessions;
    }

    void reset() {
        selectedRole = selectedJoint = selectedSocket = selectedChain = selectedGoal = -1;
        assigning = false;
        error = null;
        note = null;
    }

    void setError(String error) {
        this.error = error;
    }

    int selectedJoint() {
        return selectedJoint;
    }

    int selectedSocket() {
        return selectedSocket;
    }

    static void hint(String text) {
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip(text);
        }
    }

    /** Runs an edit; a refusal (locked mapping, bad value) becomes the error line instead of an exception. */
    private boolean edit(CharacterDocument doc, String label, String mergeKey, Runnable r) {
        try {
            doc.edit(label, mergeKey, r);
            return true;
        } catch (RuntimeException e) {
            error = e.getMessage();
            return false;
        }
    }

    void undo() {
        sessions.current().ifPresent(d -> {
            try {
                d.undo();
            } catch (RuntimeException e) {
                error = e.getMessage();
            }
        });
    }

    void redo() {
        sessions.current().ifPresent(d -> {
            try {
                d.redo();
            } catch (RuntimeException e) {
                error = e.getMessage();
            }
        });
    }

    void renderMessages() {
        if (error != null) {
            ImGui.textColored(0.95f, 0.45f, 0.4f, 1f, error);
            ImGui.sameLine();
            if (ImGui.smallButton("ok##err")) error = null;
        }
        if (note != null) {
            ImGui.textColored(0.95f, 0.8f, 0.4f, 1f, note);
            ImGui.sameLine();
            if (ImGui.smallButton("ok##note")) note = null;
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Characterization

    void renderDefinition(CharacterDocument doc) {
        CharacterDefinition def = doc.definition();
        CharacterProfile profile = doc.profile();
        boolean locked = def.locked();

        if (!ImGui.isItemActive()) nameField.set(def.name() == null ? "" : def.name());
        ImGui.setNextItemWidth(-1);
        if (ImGui.inputTextWithHint("##charName", "Character name", nameField)) {
            String v = nameField.get();
            edit(doc, "rename", "name", () -> def.setName(v));
        }
        if (doc.profileProblem() != null) {
            ImGui.textColored(0.95f, 0.8f, 0.4f, 1f, doc.profileProblem());
        }

        if (locked) ImGui.beginDisabled();
        ImGui.setNextItemWidth(130);
        if (ImGui.beginCombo("Profile", profile.id())) {
            for (String id : CharacterProfile.builtinIds()) {
                if (ImGui.selectable(id, id.equals(profile.id()))) {
                    edit(doc, "profile", null, () -> def.setProfile(id));
                    selectedRole = -1;
                }
            }
            ImGui.endCombo();
        }
        ImGui.sameLine();
        String facing = def.facing() == null || def.facing().isBlank() ? "-z" : def.facing();
        ImGui.setNextItemWidth(60);
        if (ImGui.beginCombo("Facing", facing)) {
            for (String f : FACINGS) {
                if (ImGui.selectable(f, f.equals(facing))) {
                    edit(doc, "facing", null, () -> def.setFacing(f));
                }
            }
            ImGui.endCombo();
        }
        hint("The model's forward axis (auto-map detects it)");
        if (ImGui.button("Auto-Map")) {
            edit(doc, "auto-map", null, () -> {
                var r = def.autoMap(doc.rig(), profile);
                note = "Auto-map: " + r.matches().size() + " role(s), facing " + (def.facing().isBlank() ? "-z" : def.facing());
            });
        }
        hint("Map roles from the skeleton: names + positions (sides come from geometry)");
        ImGui.sameLine();
        if (ImGui.button("Mirror L>R")) mirror(doc, CharacterProfile.Side.LEFT, CharacterDefinition.MIRROR_ROLES);
        hint("Map the right side's roles to the joints mirroring the left side's");
        ImGui.sameLine();
        if (ImGui.button("Mirror R>L")) mirror(doc, CharacterProfile.Side.RIGHT, CharacterDefinition.MIRROR_ROLES);
        if (locked) ImGui.endDisabled();
        ImGui.sameLine();
        if (ImGui.button(locked ? "Unlock" : "Lock")) {
            edit(doc, locked ? "unlock" : "lock", null, () -> {
                if (locked) def.unlock();
                else def.lock(doc.rig(), profile);
            });
        }
        hint("Lock freezes roles, skeleton, reference pose, profile and facing (refused while there are errors)");

        int[] states = doc.roleStates();
        List<CharacterProfile.Role> roles = profile.roles();
        int mapped = 0, missing = 0;
        for (int i = 0; i < roles.size(); i++) {
            if ((states[i] & CharacterOverlay.ROLE_MAPPED) != 0) mapped++;
            else if ((states[i] & CharacterOverlay.ROLE_REQUIRED) != 0) missing++;
        }
        ImGui.text(mapped + " of " + roles.size() + " roles mapped");
        if (missing > 0) {
            ImGui.sameLine();
            ImGui.textColored(0.95f, 0.45f, 0.4f, 1f, missing + " required missing");
        }

        renderAssignmentBar(doc);
        renderDiagram(doc, states);
    }

    private void mirror(CharacterDocument doc, CharacterProfile.Side from, int what) {
        edit(doc, "mirror", null, () -> {
            var r = doc.definition().mirror(doc.rig(), doc.profile(), from, what);
            long skipped = r.notes().stream().filter(i -> i.severity() == CharacterDefinition.Severity.WARNING).count();
            note = "Mirror: " + r.count() + " written" + (skipped > 0 ? ", " + skipped + " skipped (see Validation)" : "");
        });
    }

    private void renderAssignmentBar(CharacterDocument doc) {
        List<CharacterProfile.Role> roles = doc.profile().roles();
        if (selectedRole < 0 || selectedRole >= roles.size()) {
            ImGui.textDisabled("Pick a slot, then a joint (hierarchy or viewport).");
            return;
        }
        String role = roles.get(selectedRole).name();
        String joint = doc.definition().roles().get(role);
        ImGui.text("Role " + role + ": " + (joint == null ? "(unmapped)" : CharacterOps.nameOf(doc.rig(), joint)));
        ImGui.sameLine();
        if (ImGui.smallButton(assigning ? "Picking...##assign" : "Assign##assign")) {
            assigning = !assigning;
        }
        hint("Then click a joint in the hierarchy or the viewport");
        if (joint != null) {
            ImGui.sameLine();
            if (ImGui.smallButton("Clear##role")) {
                edit(doc, "clear role", null, () -> doc.definition().removeRole(role));
            }
            ImGui.sameLine();
            if (ImGui.smallButton("Select joint##role")) {
                selectedJoint = doc.rig().find(joint);
            }
        }
    }

    private void renderDiagram(CharacterDocument doc, int[] states) {
        CharacterProfile p = doc.profile();
        List<CharacterProfile.Role> roles = p.roles();
        float[] xy = p.diagram();
        float avail = ImGui.getContentRegionAvailX();
        float size = Math.max(160f, Math.min(avail, ImGui.getContentRegionAvailY() - 4f));
        float ox = ImGui.getCursorScreenPosX() + (avail - size) / 2f;
        float oy = ImGui.getCursorScreenPosY();
        ImGui.invisibleButton("##diagram", avail, size);
        boolean clicked = ImGui.isItemClicked(0);
        ImDrawList dl = ImGui.getWindowDrawList();
        dl.addRectFilled(ox, oy, ox + size, oy + size, ImGui.colorConvertFloat4ToU32(0.1f, 0.11f, 0.14f, 1f), 6f);
        Map<String, String> mapped = doc.definition().roles();
        // Bones of the figure: each role to its parent.
        int boneColor = ImGui.colorConvertFloat4ToU32(0.4f, 0.42f, 0.48f, 1f);
        for (int i = 0; i < roles.size(); i++) {
            String parent = roles.get(i).parent();
            if (parent == null) continue;
            for (int j = 0; j < roles.size(); j++) {
                if (roles.get(j).name().equals(parent)) {
                    dl.addLine(ox + xy[i * 2] * size, oy + xy[i * 2 + 1] * size, ox + xy[j * 2] * size,
                            oy + xy[j * 2 + 1] * size, boneColor, 1.5f);
                }
            }
        }
        float mx = ImGui.getIO().getMousePosX(), my = ImGui.getIO().getMousePosY();
        int hovered = -1;
        float best = Float.MAX_VALUE;
        for (int i = 0; i < roles.size(); i++) {
            CharacterProfile.Role r = roles.get(i);
            float cx = ox + xy[i * 2] * size, cy = oy + xy[i * 2 + 1] * size;
            boolean small = isSmall(r.name());
            float rad = size * (small ? 0.009f : 0.017f) + 2f;
            int s = states[i];
            boolean isMapped = (s & CharacterOverlay.ROLE_MAPPED) != 0;
            float[] c;
            if ((s & CharacterOverlay.ROLE_ERROR) != 0) c = new float[] {0.95f, 0.35f, 0.3f};
            else if ((s & CharacterOverlay.ROLE_MIRROR_MISMATCH) != 0) c = new float[] {0.95f, 0.8f, 0.3f};
            else if (isMapped) c = new float[] {0.35f, 0.85f, 0.45f};
            else c = new float[] {0.38f, 0.4f, 0.46f};
            int fill = ImGui.colorConvertFloat4ToU32(c[0], c[1], c[2], 1f);
            dl.addCircleFilled(cx, cy, rad, fill);
            if ((s & CharacterOverlay.ROLE_REQUIRED) != 0) {
                dl.addCircle(cx, cy, rad + 1.5f, ImGui.colorConvertFloat4ToU32(0.9f, 0.9f, 0.95f, 0.8f), 0, 1.2f);
            }
            if (i == selectedRole) {
                dl.addCircle(cx, cy, rad + 4f, ImGui.colorConvertFloat4ToU32(0.3f, 0.75f, 1f, 1f), 0, 2.5f);
            }
            float d = (mx - cx) * (mx - cx) + (my - cy) * (my - cy);
            if (d < (rad + 4f) * (rad + 4f) && d < best) {
                best = d;
                hovered = i;
            }
        }
        if (hovered >= 0 && ImGui.isItemHovered()) {
            CharacterProfile.Role r = roles.get(hovered);
            String joint = mapped.get(r.name());
            ImGui.setTooltip(r.name() + (r.required() ? " (required)" : "") + "\n"
                    + (joint == null ? "unmapped" : CharacterOps.nameOf(doc.rig(), joint)) + "\n"
                    + String.join(", ", CharacterOps.stateNames(states[hovered])));
        }
        if (clicked && hovered >= 0) {
            selectedRole = hovered;
            assigning = true;
            String joint = mapped.get(roles.get(hovered).name());
            if (joint != null) selectedJoint = doc.rig().find(joint);
        }
    }

    private static boolean isSmall(String role) {
        return role.contains("proximal") || role.contains("intermediate") || role.contains("distal")
                || role.contains("twist") || role.startsWith("eye") || role.equals("jaw");
    }

    /** A joint was picked (hierarchy / viewport): maps the armed role, else selects it. */
    void pickJoint(CharacterDocument doc, int joint) {
        selectedJoint = joint;
        selectedSocket = -1;
        List<CharacterProfile.Role> roles = doc.profile().roles();
        if (assigning && selectedRole >= 0 && selectedRole < roles.size() && joint >= 0) {
            String role = roles.get(selectedRole).name();
            String id = doc.rig().joints().get(joint).id();
            if (edit(doc, "set role " + role, null, () -> doc.definition().setRole(role, id))) {
                assigning = false;
            }
            return;
        }
        // Select the joint's role slot, if any.
        String id = joint < 0 ? null : doc.rig().joints().get(joint).id();
        selectedRole = -1;
        for (Map.Entry<String, String> e : doc.definition().roles().entrySet()) {
            if (e.getValue().equals(id)) {
                for (int i = 0; i < roles.size(); i++) {
                    if (roles.get(i).name().equals(e.getKey())) selectedRole = i;
                }
            }
        }
    }

    void pick(CharacterDocument doc, CharacterOverlay.Pick pick) {
        switch (pick.kind()) {
            case JOINT -> pickJoint(doc, pick.index());
            case SOCKET -> {
                selectedSocket = doc.overlay().socketIndices()[pick.index()];
                selectedJoint = -1;
            }
            default -> {
                if (!assigning) {
                    selectedJoint = -1;
                    selectedSocket = -1;
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Hierarchy

    void renderHierarchy(CharacterDocument doc) {
        Rig rig = doc.rig();
        boolean hasBones = rig.joints().stream().anyMatch(j -> j.kind() == RigJoint.Kind.BONE);
        if (hasBones) {
            if (ImGui.checkbox("Bones only", bonesOnly)) bonesOnly = !bonesOnly;
            hint("Roles map bones when the model has them");
            ImGui.sameLine();
        }
        ImGui.setNextItemWidth(-1);
        ImGui.inputTextWithHint("##jointFilter", "Filter", filter);
        Map<String, String> roleOf = new java.util.HashMap<>();
        doc.definition().roles().forEach((role, joint) -> roleOf.merge(joint, role, (a, b) -> a + ", " + b));
        String f = filter.get().trim().toLowerCase(Locale.ROOT);
        ImGui.beginChild("##joints");
        List<List<Integer>> children = new ArrayList<>();
        for (int i = 0; i < rig.jointCount(); i++) children.add(new ArrayList<>());
        List<Integer> roots = new ArrayList<>();
        for (int i = 0; i < rig.jointCount(); i++) {
            int parent = rig.joints().get(i).parent();
            if (parent < 0) roots.add(i);
            else children.get(parent).add(i);
        }
        boolean onlyBones = hasBones && bonesOnly;
        if (!f.isEmpty()) {
            for (int i = 0; i < rig.jointCount(); i++) {
                RigJoint j = rig.joints().get(i);
                if (onlyBones && j.kind() != RigJoint.Kind.BONE) continue;
                String role = roleOf.get(j.id());
                if (label(j).toLowerCase(Locale.ROOT).contains(f) || (role != null && role.contains(f))) {
                    jointRow(doc, i, role, ImGuiTreeNodeFlags.Leaf | ImGuiTreeNodeFlags.NoTreePushOnOpen);
                }
            }
        } else {
            for (int r : roots) tree(doc, r, children, roleOf, onlyBones);
        }
        ImGui.endChild();
    }

    private void tree(CharacterDocument doc, int i, List<List<Integer>> children, Map<String, String> roleOf, boolean onlyBones) {
        RigJoint j = doc.rig().joints().get(i);
        if (onlyBones && j.kind() != RigJoint.Kind.BONE) {
            for (int c : children.get(i)) tree(doc, c, children, roleOf, true); // parts are skipped, bones below them kept
            return;
        }
        boolean leaf = children.get(i).stream().noneMatch(c -> !onlyBones || hasBoneBelow(doc.rig(), c, children));
        int flags = ImGuiTreeNodeFlags.OpenOnArrow | ImGuiTreeNodeFlags.DefaultOpen | ImGuiTreeNodeFlags.SpanAvailWidth;
        if (leaf) flags |= ImGuiTreeNodeFlags.Leaf;
        boolean open = jointRow(doc, i, roleOf.get(j.id()), flags);
        if (open) {
            for (int c : children.get(i)) tree(doc, c, children, roleOf, onlyBones);
            ImGui.treePop();
        }
    }

    private static boolean hasBoneBelow(Rig rig, int i, List<List<Integer>> children) {
        if (rig.joints().get(i).kind() == RigJoint.Kind.BONE) return true;
        for (int c : children.get(i)) if (hasBoneBelow(rig, c, children)) return true;
        return false;
    }

    private boolean jointRow(CharacterDocument doc, int i, String role, int flags) {
        RigJoint j = doc.rig().joints().get(i);
        if (i == selectedJoint) flags |= ImGuiTreeNodeFlags.Selected;
        String text = label(j) + (j.kind() == RigJoint.Kind.BONE ? "" : "  (part)");
        boolean open = ImGui.treeNodeEx("##j" + i, flags, text);
        if (ImGui.isItemClicked(0) && !ImGui.isItemToggledOpen()) {
            pickJoint(doc, i);
        }
        if (role != null) {
            ImGui.sameLine();
            ImGui.textColored(0.35f, 0.85f, 0.45f, 1f, "[" + role + "]");
        }
        return open && (flags & ImGuiTreeNodeFlags.NoTreePushOnOpen) == 0;
    }

    private static String label(RigJoint j) {
        return j.name() == null || j.name().isBlank() ? j.id() : j.name();
    }

    // ------------------------------------------------------------------------------------------------
    // Reference pose

    void renderPose(CharacterDocument doc) {
        CharacterDefinition def = doc.definition();
        boolean locked = def.locked();
        if (locked) ImGui.beginDisabled();
        ImGui.textDisabled("Fit to the profile (rest normalization):");
        List<String> poses = new ArrayList<>();
        poses.add("T");
        poses.addAll(doc.profile().poseNames());
        for (String pose : poses) {
            if (ImGui.button("Fit " + pose + "-pose")) {
                edit(doc, "fit " + pose + "-pose", null, () -> {
                    int n = def.fitReferencePose(doc.rig(), doc.profile(), pose);
                    note = "Fit " + pose + ": " + n + " joint(s) turned";
                });
            }
            ImGui.sameLine();
        }
        if (ImGui.button("Clear")) {
            edit(doc, "clear reference pose", null, () -> {
                for (CharacterDefinition.JointRotation r : def.referencePose()) def.removeReferenceRotation(r.jointId());
            });
        }
        hint("Back to the rest pose");
        ImGui.separator();
        if (selectedJoint >= 0 && selectedJoint < doc.rig().jointCount()) {
            RigJoint j = doc.rig().joints().get(selectedJoint);
            ImGui.text("Joint " + label(j));
            CharacterDefinition.JointRotation current = def.referencePose().stream()
                    .filter(r -> r.jointId().equals(j.id())).findFirst().orElse(null);
            float[] rot = current != null
                    ? new float[] {current.rotDeg().x(), current.rotDeg().y(), current.rotDeg().z()}
                    : new float[] {j.rotDeg().x(), j.rotDeg().y(), j.rotDeg().z()};
            if (ImGui.dragFloat3("Rotation##pose", rot, 0.25f, -360f, 360f, "%.2f")) {
                Vec3 v = new Vec3(rot[0], rot[1], rot[2]);
                edit(doc, "rotate " + label(j), "pose:" + j.id(), () -> def.setReferenceRotation(j.id(), v));
            }
            hint("Local Euler XYZ degrees (absolute, like an animation key)");
            ImGui.textDisabled(String.format(Locale.ROOT, "rest %.1f %.1f %.1f", j.rotDeg().x(), j.rotDeg().y(), j.rotDeg().z()));
            if (current != null) {
                ImGui.sameLine();
                if (ImGui.smallButton("Reset joint")) {
                    edit(doc, "reset " + label(j), null, () -> def.removeReferenceRotation(j.id()));
                }
            }
        } else {
            ImGui.textDisabled("Select a joint to rotate it by hand.");
        }
        if (locked) ImGui.endDisabled();
        ImGui.separator();
        ImGui.textDisabled(def.referencePose().size() + " posed joint(s)");
        for (CharacterDefinition.JointRotation r : def.referencePose()) {
            int idx = doc.rig().find(r.jointId());
            if (ImGui.selectable(CharacterOps.nameOf(doc.rig(), r.jointId()) + String.format(Locale.ROOT,
                    "##p%d  %.1f %.1f %.1f", idx, r.rotDeg().x(), r.rotDeg().y(), r.rotDeg().z()), idx == selectedJoint)) {
                pickJoint(doc, idx);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Chains and goals

    void renderChains(CharacterDocument doc) {
        CharacterDefinition def = doc.definition();
        Rig rig = doc.rig();
        String sel = selectedJoint >= 0 && selectedJoint < rig.jointCount() ? rig.joints().get(selectedJoint).id() : null;
        ImGui.textDisabled("Retarget chains (start is an ancestor of end)");
        List<CharacterDefinition.Chain> chains = def.chains();
        if (ImGui.beginTable("##chains", 4, ImGuiTableFlags.RowBg | ImGuiTableFlags.BordersInnerV | ImGuiTableFlags.Resizable)) {
            ImGui.tableSetupColumn("Chain");
            ImGui.tableSetupColumn("Start");
            ImGui.tableSetupColumn("End");
            ImGui.tableSetupColumn("Goal");
            ImGui.tableHeadersRow();
            for (int i = 0; i < chains.size(); i++) {
                CharacterDefinition.Chain c = chains.get(i);
                ImGui.tableNextRow();
                ImGui.tableNextColumn();
                float[] col = CharacterViewport.chainColor(i);
                ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, col[0], col[1], col[2], 1f);
                if (ImGui.selectable(c.name() + "##c" + i, i == selectedChain,
                        imgui.flag.ImGuiSelectableFlags.SpanAllColumns)) {
                    selectedChain = i;
                }
                ImGui.popStyleColor();
                ImGui.tableNextColumn();
                ImGui.text(CharacterOps.nameOf(rig, c.startJointId()));
                ImGui.tableNextColumn();
                ImGui.text(CharacterOps.nameOf(rig, c.endJointId()));
                ImGui.tableNextColumn();
                ImGui.text(c.goal() == null ? "" : c.goal());
            }
            ImGui.endTable();
        }
        if (selectedChain >= 0 && selectedChain < chains.size()) {
            CharacterDefinition.Chain c = chains.get(selectedChain);
            if (sel == null) ImGui.beginDisabled();
            if (ImGui.smallButton("Start <- joint")) {
                edit(doc, "chain start", null, () -> def.setChain(new CharacterDefinition.Chain(c.name(), sel, c.endJointId(), c.goal())));
            }
            ImGui.sameLine();
            if (ImGui.smallButton("End <- joint")) {
                edit(doc, "chain end", null, () -> def.setChain(new CharacterDefinition.Chain(c.name(), c.startJointId(), sel, c.goal())));
            }
            if (sel == null) ImGui.endDisabled();
            ImGui.sameLine();
            ImGui.setNextItemWidth(110);
            if (ImGui.beginCombo("Goal##chainGoal", c.goal() == null || c.goal().isBlank() ? "(none)" : c.goal())) {
                if (ImGui.selectable("(none)")) {
                    edit(doc, "chain goal", null, () -> def.setChain(new CharacterDefinition.Chain(c.name(), c.startJointId(), c.endJointId(), "")));
                }
                for (CharacterDefinition.IkGoal g : def.ikGoals()) {
                    if (ImGui.selectable(g.name())) {
                        edit(doc, "chain goal", null, () -> def.setChain(new CharacterDefinition.Chain(c.name(), c.startJointId(), c.endJointId(), g.name())));
                    }
                }
                ImGui.endCombo();
            }
            ImGui.sameLine();
            if (ImGui.smallButton("Remove##chain")) {
                edit(doc, "remove chain", null, () -> def.removeChain(c.name()));
                selectedChain = -1;
            }
        }
        ImGui.setNextItemWidth(140);
        ImGui.inputTextWithHint("##newChain", "chain name", newChain);
        ImGui.sameLine();
        boolean canAdd = sel != null && !newChain.get().isBlank();
        if (!canAdd) ImGui.beginDisabled();
        if (ImGui.button("Add on joint##chain")) {
            String n = newChain.get().trim();
            edit(doc, "add chain", null, () -> def.setChain(new CharacterDefinition.Chain(n, sel, sel, "")));
            newChain.set("");
        }
        if (!canAdd) ImGui.endDisabled();
        hint("A one-joint chain on the selected joint; then set its end");

        ImGui.separator();
        ImGui.textDisabled("IK goals");
        List<CharacterDefinition.IkGoal> goals = def.ikGoals();
        for (int i = 0; i < goals.size(); i++) {
            CharacterDefinition.IkGoal g = goals.get(i);
            if (ImGui.selectable(g.name() + "  ->  " + CharacterOps.nameOf(rig, g.jointId()) + "##g" + i, i == selectedGoal)) {
                selectedGoal = i;
            }
        }
        if (selectedGoal >= 0 && selectedGoal < goals.size()) {
            CharacterDefinition.IkGoal g = goals.get(selectedGoal);
            if (sel == null) ImGui.beginDisabled();
            if (ImGui.smallButton("Joint <- selected##goal")) {
                edit(doc, "goal joint", null, () -> def.setIkGoal(g.name(), sel));
            }
            if (sel == null) ImGui.endDisabled();
            ImGui.sameLine();
            if (ImGui.smallButton("Remove##goal")) {
                edit(doc, "remove goal", null, () -> def.removeIkGoal(g.name()));
                selectedGoal = -1;
            }
        }
        ImGui.setNextItemWidth(140);
        ImGui.inputTextWithHint("##newGoal", "goal name", newGoal);
        ImGui.sameLine();
        boolean canGoal = sel != null && !newGoal.get().isBlank();
        if (!canGoal) ImGui.beginDisabled();
        if (ImGui.button("Add on joint##goal")) {
            String n = newGoal.get().trim();
            edit(doc, "add goal", null, () -> def.setIkGoal(n, sel));
            newGoal.set("");
        }
        if (!canGoal) ImGui.endDisabled();
    }

    // ------------------------------------------------------------------------------------------------
    // Sockets

    void renderSockets(CharacterDocument doc) {
        CharacterDefinition def = doc.definition();
        Rig rig = doc.rig();
        ImGui.textDisabled("Standard sockets (placed on their role's joint):");
        for (CharacterDefinition.StandardSocket s : CharacterDefinition.standardSockets()) {
            if (ImGui.smallButton(s.name())) {
                edit(doc, "place " + s.name(), null, () -> def.placeStandardSocket(rig, s.name()));
            }
            hint("On the " + s.role() + " role's joint");
            ImGui.sameLine();
        }
        if (ImGui.smallButton("Mirror L>R##sockets")) mirror(doc, CharacterProfile.Side.LEFT, CharacterDefinition.MIRROR_SOCKETS);
        ImGui.sameLine();
        if (ImGui.smallButton("Mirror R>L##sockets")) mirror(doc, CharacterProfile.Side.RIGHT, CharacterDefinition.MIRROR_SOCKETS);
        ImGui.separator();
        List<CharacterDefinition.Socket> sockets = def.sockets();
        for (int i = 0; i < sockets.size(); i++) {
            CharacterDefinition.Socket s = sockets.get(i);
            if (ImGui.selectable(s.name() + "  ->  " + CharacterOps.nameOf(rig, s.jointId()) + "##s" + i, i == selectedSocket)) {
                selectedSocket = i;
            }
        }
        String sel = selectedJoint >= 0 && selectedJoint < rig.jointCount() ? rig.joints().get(selectedJoint).id() : null;
        if (selectedSocket >= 0 && selectedSocket < sockets.size()) {
            CharacterDefinition.Socket s = sockets.get(selectedSocket);
            ImGui.separator();
            ImGui.text("Socket " + s.name());
            String key = "socket:" + s.name();
            float[] pos = {s.pos().x(), s.pos().y(), s.pos().z()};
            if (ImGui.dragFloat3("Position", pos, 0.002f, -100f, 100f, "%.3f")) {
                edit(doc, "move " + s.name(), key, () -> def.setSocket(new CharacterDefinition.Socket(s.name(),
                        s.jointId(), new Vec3(pos[0], pos[1], pos[2]), s.rotDeg(), s.scale())));
            }
            hint("Rest model space (moves with the joint)");
            float[] rot = {s.rotDeg().x(), s.rotDeg().y(), s.rotDeg().z()};
            if (ImGui.dragFloat3("Rotation##socket", rot, 0.25f, -360f, 360f, "%.1f")) {
                edit(doc, "rotate " + s.name(), key, () -> def.setSocket(new CharacterDefinition.Socket(s.name(),
                        s.jointId(), s.pos(), new Vec3(rot[0], rot[1], rot[2]), s.scale())));
            }
            float[] scale = {s.scale().x(), s.scale().y(), s.scale().z()};
            if (ImGui.dragFloat3("Scale##socket", scale, 0.005f, 0.001f, 100f, "%.3f")) {
                edit(doc, "scale " + s.name(), key, () -> def.setSocket(new CharacterDefinition.Socket(s.name(),
                        s.jointId(), s.pos(), s.rotDeg(), new Vec3(scale[0], scale[1], scale[2]))));
            }
            if (sel == null) ImGui.beginDisabled();
            if (ImGui.smallButton("Joint <- selected##socket")) {
                edit(doc, "socket joint", null, () -> def.setSocket(new CharacterDefinition.Socket(s.name(), sel,
                        s.pos(), s.rotDeg(), s.scale())));
            }
            if (sel == null) ImGui.endDisabled();
            ImGui.sameLine();
            if (ImGui.smallButton("Remove##socket")) {
                edit(doc, "remove socket", null, () -> def.removeSocket(s.name()));
                selectedSocket = -1;
            }
        }
        ImGui.separator();
        ImGui.setNextItemWidth(140);
        ImGui.inputTextWithHint("##newSocket", "socket name", newSocket);
        ImGui.sameLine();
        boolean canAdd = sel != null && !newSocket.get().isBlank();
        if (!canAdd) ImGui.beginDisabled();
        if (ImGui.button("Add on joint##socket")) {
            String n = newSocket.get().trim();
            float[] pt = doc.restPoint(selectedJoint);
            // At the joint's REST point: sockets live in rest model space (the viewport shows the reference pose).
            edit(doc, "add socket", null, () -> def.setSocket(new CharacterDefinition.Socket(n, sel,
                    new Vec3(pt[0], pt[1], pt[2]), Vec3.ZERO, Vec3.ONE)));
            newSocket.set("");
        }
        if (!canAdd) ImGui.endDisabled();
        hint("A custom socket on the selected joint");
    }

    // ------------------------------------------------------------------------------------------------
    // Metrics

    void renderMetrics(CharacterDocument doc, CharacterViewport viewport) {
        CharacterDefinition def = doc.definition();
        CharacterDefinition.Metrics m = def.metrics();
        if (ImGui.button("Compute")) {
            float[] lo = doc.boundsMin(), hi = doc.boundsMax();
            edit(doc, "compute metrics", null, () -> def.setMetrics(def.computeMetrics(doc.rig(),
                    new Vec3(lo[0], lo[1], lo[2]), new Vec3(hi[0], hi[1], hi[2]))));
        }
        hint("Measure the reference pose (typed values are kept as overrides)");
        metric(doc, "Height", CharacterDefinition.Metric.HEIGHT, m.height(), m);
        metric(doc, "Eye height", CharacterDefinition.Metric.EYE_HEIGHT, m.eyeHeight(), m);
        metric(doc, "Width", CharacterDefinition.Metric.WIDTH, m.width(), m);
        metric(doc, "Hip height", CharacterDefinition.Metric.HIP_HEIGHT, m.hipHeight(), m);
        CharacterOverlay.Capsule cap = doc.overlay().capsule();
        ImGui.separator();
        ImGui.textDisabled(String.format(Locale.ROOT, "Capsule preview: radius %.3f, height %.3f", cap.radius(), cap.height()));
        if (viewport != null) {
            if (ImGui.checkbox("Capsule", viewport.showCapsule)) viewport.showCapsule = !viewport.showCapsule;
            ImGui.sameLine();
            if (ImGui.checkbox("Eye / hip lines", viewport.showLines)) viewport.showLines = !viewport.showLines;
        }
    }

    private void metric(CharacterDocument doc, String label, CharacterDefinition.Metric which, float value,
                        CharacterDefinition.Metrics m) {
        boolean overridden = m.overridden().contains(which);
        if (ImGui.checkbox("##ov" + which, overridden)) {
            Set<CharacterDefinition.Metric> over = EnumSet.noneOf(CharacterDefinition.Metric.class);
            over.addAll(m.overridden());
            if (overridden) over.remove(which); else over.add(which);
            edit(doc, "metric override", null, () -> doc.definition().setMetrics(new CharacterDefinition.Metrics(
                    m.height(), m.eyeHeight(), m.width(), m.hipHeight(), over)));
        }
        hint("Overridden: Compute keeps the typed value");
        ImGui.sameLine();
        float[] v = {value};
        ImGui.setNextItemWidth(120);
        if (ImGui.dragFloat(label, v, 0.002f, 0f, 1000f, "%.3f")) {
            Set<CharacterDefinition.Metric> over = EnumSet.noneOf(CharacterDefinition.Metric.class);
            over.addAll(m.overridden());
            over.add(which);
            float h = which == CharacterDefinition.Metric.HEIGHT ? v[0] : m.height();
            float e = which == CharacterDefinition.Metric.EYE_HEIGHT ? v[0] : m.eyeHeight();
            float w = which == CharacterDefinition.Metric.WIDTH ? v[0] : m.width();
            float hip = which == CharacterDefinition.Metric.HIP_HEIGHT ? v[0] : m.hipHeight();
            edit(doc, "edit " + label.toLowerCase(Locale.ROOT), "metric:" + which,
                    () -> doc.definition().setMetrics(new CharacterDefinition.Metrics(h, e, w, hip, over)));
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Validation

    void renderValidation(CharacterDocument doc) {
        List<CharacterDefinition.Issue> issues = doc.issues();
        long errors = issues.stream().filter(i -> i.severity() == CharacterDefinition.Severity.ERROR).count();
        long warnings = issues.stream().filter(i -> i.severity() == CharacterDefinition.Severity.WARNING).count();
        if (errors == 0) ImGui.textColored(0.35f, 0.85f, 0.45f, 1f, "No errors");
        else ImGui.textColored(0.95f, 0.45f, 0.4f, 1f, errors + " error(s)");
        ImGui.sameLine();
        ImGui.textDisabled(warnings + " warning(s)" + (doc.definition().locked() ? "   mapping locked" : ""));
        List<CharacterProfile.Role> roles = doc.profile().roles();
        for (int i = 0; i < issues.size(); i++) {
            CharacterDefinition.Issue is = issues.get(i);
            float[] c = switch (is.severity()) {
                case ERROR -> new float[] {0.95f, 0.45f, 0.4f};
                case WARNING -> new float[] {0.95f, 0.8f, 0.4f};
                default -> new float[] {0.7f, 0.72f, 0.78f};
            };
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, c[0], c[1], c[2], 1f);
            if (ImGui.selectable(is.message() + "##i" + i)) {
                for (int r = 0; r < roles.size(); r++) {
                    if (roles.get(r).name().equals(is.subject())) {
                        selectedRole = r;
                        String joint = doc.definition().roles().get(is.subject());
                        if (joint != null) selectedJoint = doc.rig().find(joint);
                    }
                }
                int j = doc.rig().find(is.subject());
                if (j >= 0) selectedJoint = j;
            }
            ImGui.popStyleColor();
        }
    }

    // ------------------------------------------------------------------------------------------------

    void renderViewportToggles(CharacterViewport v) {
        if (ImGui.checkbox("Mesh", v.showMesh)) v.showMesh = !v.showMesh;
        ImGui.sameLine();
        if (ImGui.checkbox("Bones", v.showBones)) v.showBones = !v.showBones;
        ImGui.sameLine();
        if (ImGui.checkbox("Chains", v.showChains)) v.showChains = !v.showChains;
        ImGui.sameLine();
        if (ImGui.checkbox("Sockets", v.showSockets)) v.showSockets = !v.showSockets;
        ImGui.sameLine();
        if (ImGui.checkbox("Capsule##vp", v.showCapsule)) v.showCapsule = !v.showCapsule;
        ImGui.sameLine();
        if (ImGui.checkbox("Lines", v.showLines)) v.showLines = !v.showLines;
        ImGui.sameLine();
        if (ImGui.checkbox("X-ray", v.xray)) v.xray = !v.xray;
        hint("Overlay drawn through the mesh");
        ImGui.sameLine();
        if (ImGui.checkbox("Floor", v.showGround)) v.showGround = !v.showGround;
    }
}
