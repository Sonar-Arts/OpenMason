package com.openmason.main.systems.skeleton;

import com.openmason.engine.modeling.parts.ModelPartDescriptor;
import com.openmason.engine.modeling.parts.ModelPartManager;
import com.openmason.engine.modeling.parts.PartTransform;
import com.openmason.main.systems.services.commands.ModelCommand;
import com.openmason.main.systems.services.commands.ModelCommandHistory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One undo step for a rig-hierarchy edit (OpenMason #165 rig-revamp gap): reparenting a part under a bone or a part,
 * detaching it, re-parenting a bone, and the bone-endpoint snap that goes with dropping a part on a bone. These used
 * to change the part manager and the bone store directly - no undo, and the model never became dirty. Captures every
 * part's parent + local transform and the skeleton before and after, and lives on the MODEL history (so it marks
 * the model dirty and shares Ctrl+Z with modelling).
 */
public final class RigHierarchyCommand implements ModelCommand {

    private record PartState(String parentId, PartTransform transform) {
    }

    private final String description;
    private final ModelPartManager parts;
    private final BoneStore bones;
    private final Map<String, PartState> partsBefore;
    private final Map<String, PartState> partsAfter;
    private final BoneSnapshot bonesBefore;
    private final BoneSnapshot bonesAfter;
    private final Runnable onRestored;

    private RigHierarchyCommand(String description, ModelPartManager parts, BoneStore bones,
                                Map<String, PartState> partsBefore, Map<String, PartState> partsAfter,
                                BoneSnapshot bonesBefore, BoneSnapshot bonesAfter, Runnable onRestored) {
        this.description = description;
        this.parts = parts;
        this.bones = bones;
        this.partsBefore = partsBefore;
        this.partsAfter = partsAfter;
        this.bonesBefore = bonesBefore;
        this.bonesAfter = bonesAfter;
        this.onRestored = onRestored;
    }

    /**
     * Runs {@code edit} and records it on {@code history} as one step when it changed anything (history may be null:
     * the edit just runs).
     *
     * @return the edit's result
     */
    public static <T> T record(String description, ModelPartManager parts, BoneStore bones, ModelCommandHistory history,
                               Runnable onRestored, java.util.function.Supplier<T> edit) {
        if (history == null || parts == null) {
            return edit.get();
        }
        Map<String, PartState> before = capture(parts);
        BoneSnapshot bonesBefore = bones == null ? null : BoneSnapshot.capture(bones);
        T result = edit.get();
        Map<String, PartState> after = capture(parts);
        BoneSnapshot bonesAfter = bones == null ? null : BoneSnapshot.capture(bones);
        boolean bonesChanged = bones != null && !bonesBefore.equalsSnapshot(bonesAfter);
        if (!before.equals(after) || bonesChanged) {
            history.pushCompleted(new RigHierarchyCommand(description, parts, bones, before, after, bonesBefore,
                    bonesAfter, onRestored));
        }
        return result;
    }

    private static Map<String, PartState> capture(ModelPartManager parts) {
        Map<String, PartState> out = new LinkedHashMap<>();
        for (ModelPartDescriptor p : parts.getAllParts()) {
            out.put(p.id(), new PartState(p.parentId(), p.transform()));
        }
        return out;
    }

    private void restore(Map<String, PartState> state, BoneSnapshot skeleton) {
        if (bones != null && skeleton != null) {
            skeleton.restore(bones);
        }
        // Detach first, then attach: restoring parents one by one could otherwise meet a transient cycle.
        for (Map.Entry<String, PartState> e : state.entrySet()) {
            ModelPartDescriptor now = parts.getPartById(e.getKey()).orElse(null);
            if (now != null && !Objects.equals(now.parentId(), e.getValue().parentId())) {
                parts.setPartParent(e.getKey(), null);
            }
        }
        for (Map.Entry<String, PartState> e : state.entrySet()) {
            ModelPartDescriptor now = parts.getPartById(e.getKey()).orElse(null);
            if (now == null) continue;
            if (!Objects.equals(now.parentId(), e.getValue().parentId())) {
                parts.setPartParent(e.getKey(), e.getValue().parentId());
            }
            if (!Objects.equals(now.transform(), e.getValue().transform())) {
                parts.setPartTransform(e.getKey(), e.getValue().transform());
            }
        }
        if (bones != null) bones.invalidate();
        if (onRestored != null) onRestored.run();
    }

    @Override
    public void execute() {
        restore(partsAfter, bonesAfter);
    }

    @Override
    public void undo() {
        restore(partsBefore, bonesBefore);
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public boolean canMergeWith(ModelCommand other) {
        return false;
    }

    @Override
    public ModelCommand mergeWith(ModelCommand other) {
        throw new UnsupportedOperationException("rig hierarchy edits do not merge");
    }
}
