package com.openmason.main.systems.skeleton;

import com.openmason.engine.rig.Skin;
import org.joml.Vector3f;

/**
 * The viewport weight-paint mode's settings, shared by the rigging pane's Skin section (controls), the
 * viewport input (brush strokes) and the render pipeline (heat map + brush cursor). Plain state; the
 * painting itself is {@link SkinAuthoring#paint}. Main thread only.
 */
public final class WeightPaintState {

    private boolean active;
    private String jointId;
    private float radius = 0.25f;
    private float strength = 0.5f;
    private Skin.PaintMode mode = Skin.PaintMode.ADD;
    /** Model-space brush hit under the mouse, null when the mouse is off the mesh. */
    private Vector3f cursor;

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
        if (!active) cursor = null;
    }

    public void toggle() {
        setActive(!active);
    }

    /** The joint (part or bone id) the brush paints and the heat map shows; null = none chosen. */
    public String getJointId() {
        return jointId;
    }

    public void setJointId(String jointId) {
        this.jointId = jointId;
    }

    /** Brush radius in model units. */
    public float getRadius() {
        return radius;
    }

    public void setRadius(float radius) {
        this.radius = Math.max(0.001f, radius);
    }

    /** Brush strength in [0, 1] (the REPLACE target weight). */
    public float getStrength() {
        return strength;
    }

    public void setStrength(float strength) {
        this.strength = Math.max(0f, Math.min(1f, strength));
    }

    public Skin.PaintMode getMode() {
        return mode;
    }

    public void setMode(Skin.PaintMode mode) {
        this.mode = mode == null ? Skin.PaintMode.ADD : mode;
    }

    public Vector3f getCursor() {
        return cursor;
    }

    public void setCursor(Vector3f cursor) {
        this.cursor = cursor == null ? null : new Vector3f(cursor);
    }
}
