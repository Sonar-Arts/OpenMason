package com.openmason.main.systems.viewport.viewportRendering.gizmo.interaction;

import com.openmason.engine.rendering.viewer.gizmo.interaction.ITransformTarget;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.main.systems.skeleton.BoneStore;
import com.openmason.engine.rendering.viewer.transform.TransformState;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Gizmo transform target for the currently-selected bone in a {@link BoneStore}.
 *
 * <p>Treats the bone's local {@code pos} / {@code rot} fields as the editable values — the posed
 * values while the animation preview poses the skeleton (an edit then goes through
 * {@link BoneStore#editLocal}, whose pose-edit handler poses / auto-keys it), the rest values
 * otherwise.
 * Bone {@code origin} is the rest pivot in parent space and stays untouched — drags
 * adjust the {@code pos} offset so the joint visibly moves while the rest pose is
 * preserved. Scale is intentionally a no-op since bones are pure transform nodes
 * with no geometric scale of their own.
 *
 * <p>Single responsibility: bridge the gizmo interaction layer to {@link BoneStore}.
 * Selection is read from {@link BoneStore#getSelectedBoneId()} so the same handle
 * always works whether the user picks a bone in the hierarchy or via some future
 * viewport picker.
 *
 * <p>A drag is one transform interaction (OpenMason #175 F2): every rest edit during it draws through the editable
 * model's GPU palette, and the baked mesh is rebuilt once on release ({@link #endDrag}).
 */
public class BoneTransformTarget implements ITransformTarget {

    private final BoneStore boneStore;
    private final TransformState transformState;

    public BoneTransformTarget(BoneStore boneStore, TransformState transformState) {
        this.boneStore = boneStore;
        this.transformState = transformState;
    }

    @Override
    public Vector3f getPosition() {
        OMOFormat.BoneEntry b = selected();
        if (b == null) return new Vector3f();
        return boneStore.getLocalPosition(b.id());
    }

    @Override
    public Vector3f getRotation() {
        OMOFormat.BoneEntry b = selected();
        if (b == null) return new Vector3f();
        return boneStore.getLocalRotation(b.id());
    }

    @Override
    public Vector3f getScale() {
        return new Vector3f(1, 1, 1);
    }

    @Override
    public void setPosition(float x, float y, float z) {
        OMOFormat.BoneEntry b = selected();
        if (b == null) return;
        boneStore.editLocal(b.id(), new Vector3f(x, y, z), null);
    }

    @Override
    public void setPosition(float x, float y, float z, boolean snap, float snapIncrement) {
        if (snap && snapIncrement > 0) {
            x = Math.round(x / snapIncrement) * snapIncrement;
            y = Math.round(y / snapIncrement) * snapIncrement;
            z = Math.round(z / snapIncrement) * snapIncrement;
        }
        setPosition(x, y, z);
    }

    @Override
    public void setRotation(float x, float y, float z) {
        OMOFormat.BoneEntry b = selected();
        if (b == null) return;
        boneStore.editLocal(b.id(), null, new Vector3f(x, y, z));
    }

    @Override
    public void setScale(float x, float y, float z) {
        // Bones do not carry geometric scale.
    }

    @Override
    public Vector3f getWorldCenter() {
        OMOFormat.BoneEntry b = selected();
        if (b == null) return new Vector3f();
        Vector3f jointWorld = boneStore.getJointWorldPosition(b.id());
        if (jointWorld == null) return new Vector3f();

        Matrix4f modelTransform = transformState.getTransformMatrix();
        return modelTransform.transformPosition(jointWorld);
    }

    @Override
    public boolean isActive() {
        return selected() != null;
    }

    @Override
    public String getTargetName() {
        OMOFormat.BoneEntry b = selected();
        return b == null ? "No Bone Selected" : "Bone: " + b.name();
    }

    // True between beginDrag and endDrag: the part manager defers its re-bake (GPU palette path, #175 F2).
    private boolean interactionOpen;

    /** A gizmo drag starts: rest edits during it move the drawn model through matrices only. */
    @Override
    public void beginDrag() {
        if (interactionOpen) boneStore.endEditInteraction();
        boneStore.beginEditInteraction();
        interactionOpen = true;
    }

    /** The drag ends: the model is re-baked once with the final skeleton. */
    @Override
    public void endDrag() {
        if (!interactionOpen) return;
        interactionOpen = false;
        boneStore.endEditInteraction();
    }

    private OMOFormat.BoneEntry selected() {
        String id = boneStore.getSelectedBoneId();
        return id == null ? null : boneStore.getById(id);
    }
}
