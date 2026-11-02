package com.openmason.engine.rendering.viewer.passes;

import com.openmason.engine.rendering.model.draw.ModelLighting;
import com.openmason.engine.rendering.model.draw.ModelRenderer;
import com.openmason.engine.modeling.EditableModel;
import com.openmason.engine.rendering.viewer.ViewerFrame;
import com.openmason.engine.rendering.viewer.ViewerPass;
import com.openmason.engine.rendering.viewer.ViewerPassOrder;
import com.openmason.engine.rendering.viewer.scene.ModelInstance;
import com.openmason.engine.rendering.viewer.scene.ModelScene;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Draws every visible instance in a {@link ModelScene} through the engine
 * {@link ModelRenderer} — the same draw path and preview lighting games use for
 * entity models, so a scene looks as it will in game.
 *
 * <p>One {@link EditableModel} per distinct model, drawn once per instance with
 * that instance's matrix, so placing the same model twenty times costs twenty draws
 * and one upload rather than twenty of each.
 */
public final class ModelInstancePass implements ViewerPass {

    /** Solid-view grey for unrendered mode. */
    public static final Vector3f UNRENDERED_GRAY = new Vector3f(0.53f, 0.53f, 0.53f);

    private final ModelScene scene;

    public ModelInstancePass(ModelScene scene) {
        this.scene = java.util.Objects.requireNonNull(scene, "scene");
    }

    @Override
    public int order() {
        return ViewerPassOrder.CONTENT;
    }

    @Override
    public String name() {
        return "model-instances";
    }

    @Override
    public void render(ViewerFrame frame) {
        if (scene.isEmpty()) {
            return;
        }

        ModelRenderer modelRenderer = frame.shaders().modelRenderer();
        Matrix4f view = frame.context().getCamera().getViewMatrix();
        Matrix4f projection = frame.context().getCamera().getProjectionMatrix();

        boolean unrendered = frame.settings().isUnrendered();
        ModelLighting lighting = unrendered
                ? ModelLighting.preview(view, UNRENDERED_GRAY)
                : ModelLighting.preview(view);

        for (ModelInstance instance : scene.instances()) {
            if (!instance.isVisible()) {
                continue;
            }
            EditableModel renderer = instance.model().renderer();
            if (renderer == null || !renderer.isInitialized()) {
                continue;
            }
            renderer.setForceUnrendered(unrendered);
            renderer.render(modelRenderer, instance.modelMatrix(), view, projection, lighting);
        }
    }
}
