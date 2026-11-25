package com.openmason.main.systems.viewport.input;

import com.openmason.engine.rendering.viewer.math.CoordinateSystem;
import com.openmason.engine.rendering.viewer.transform.TransformState;
import com.openmason.main.systems.skeleton.SkinAuthoring;
import com.openmason.main.systems.skeleton.SkinCommandHistory;
import com.openmason.main.systems.skeleton.SkinStore;
import com.openmason.main.systems.skeleton.WeightPaintState;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * Weight-paint brush strokes in the viewport. While weight paint is on: the mouse ray picks the visible
 * mesh (the editor's ray/triangle test, {@link SkinAuthoring#raycast}) to place the brush cursor; a left
 * press ON the mesh starts a stroke (one undo step: snapshot on press, pushed on release), and dragging
 * lays dabs ({@link SkinAuthoring#paint}, native) spaced a fraction of the radius apart. A left press off
 * the mesh, or with Alt held, falls through to the camera, so orbiting still works.
 */
public final class WeightPaintController {

    private static final Logger logger = LoggerFactory.getLogger(WeightPaintController.class);

    /** Dabs are laid when the hit moved this fraction of the radius since the last one. */
    private static final float DAB_SPACING = 0.25f;

    private WeightPaintState state;
    private Supplier<SkinAuthoring> authoring;
    private SkinCommandHistory history;
    private TransformState transformState;

    private boolean stroking;
    private SkinStore.Snapshot strokeBefore;
    private Vector3f lastDab;

    public void wire(WeightPaintState state, Supplier<SkinAuthoring> authoring, SkinCommandHistory history,
                     TransformState transformState) {
        this.state = state;
        this.authoring = authoring;
        this.history = history;
        this.transformState = transformState;
    }

    public boolean isActive() {
        return state != null && state.isActive();
    }

    public boolean isStroking() {
        return stroking;
    }

    /** True when the brush consumed the input (lower-priority controllers then skip this frame). */
    public boolean handleInput(InputContext context) {
        if (!isActive() || authoring == null) {
            if (stroking) endStroke();
            return false;
        }
        SkinAuthoring skin = authoring.get();
        Vector3f hit = null;
        if (context.mouseInBounds || stroking) {
            hit = pick(skin, context);
        }
        state.setCursor(hit);

        if (stroking) {
            if (context.mouseDown) {
                if (hit != null && (lastDab == null
                        || hit.distance(lastDab) >= state.getRadius() * DAB_SPACING)) {
                    dab(skin, hit);
                }
            } else {
                endStroke();
            }
            return true;
        }

        if (context.mouseClicked && context.mouseInBounds && !context.altDown && hit != null) {
            if (state.getJointId() == null) {
                logger.debug("Weight paint: no target joint chosen");
                return true;
            }
            stroking = true;
            strokeBefore = skin.skins().snapshot();
            lastDab = null;
            dab(skin, hit);
            return true;
        }
        return false;
    }

    private void dab(SkinAuthoring skin, Vector3f hit) {
        try {
            skin.paint(skin.partIds(), hit.x, hit.y, hit.z, state.getRadius(), state.getJointId(),
                    state.getMode(), state.getStrength());
        } catch (RuntimeException e) {
            logger.warn("Weight paint dab failed: {}", e.getMessage());
        }
        lastDab = new Vector3f(hit);
    }

    private void endStroke() {
        stroking = false;
        lastDab = null;
        if (strokeBefore != null && history != null && authoring != null) {
            history.push(strokeBefore, authoring.get().skins().snapshot(), "Paint Weights");
        }
        strokeBefore = null;
    }

    /** The mouse ray in MODEL space against the visible mesh; null when it misses. */
    private Vector3f pick(SkinAuthoring skin, InputContext context) {
        if (context.viewportWidth <= 0 || context.viewportHeight <= 0) return null;
        try {
            CoordinateSystem.Ray ray = CoordinateSystem.createWorldRayFromScreen(context.mouseX, context.mouseY,
                    context.viewportWidth, context.viewportHeight, context.viewMatrix, context.projectionMatrix);
            Matrix4f inverse = transformState != null
                    ? transformState.getTransformMatrix().invert() : new Matrix4f();
            Vector3f origin = inverse.transformPosition(new Vector3f(ray.origin()));
            Vector3f direction = inverse.transformDirection(new Vector3f(ray.direction()));
            SkinAuthoring.Hit hit = skin.raycast(origin, direction);
            return hit == null ? null : hit.point();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
