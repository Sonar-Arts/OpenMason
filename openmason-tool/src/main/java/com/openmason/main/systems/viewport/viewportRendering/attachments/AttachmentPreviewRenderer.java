package com.openmason.main.systems.viewport.viewportRendering.attachments;

import com.openmason.engine.entity.sbe.SbeModelGeometry;
import com.openmason.engine.rendering.model.draw.ModelLighting;
import com.openmason.engine.rendering.model.draw.ModelRenderer;
import com.openmason.engine.rendering.model.draw.PartMatrices;
import com.openmason.engine.rendering.model.draw.UploadedModel;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Draws "socket test" models in the tool viewport: a decoded accessory
 * ({@link SbeModelGeometry}, loaded via the game's attachable-asset pipeline)
 * posed at a socket's world frame, so authors can adjust the socket live
 * against the model that will actually be attached in-game.
 *
 * <p>Draws through the engine {@link ModelRenderer} (#90) — the path games draw
 * the same accessory with — using the preview lighting rig. Rest pose only: at
 * rest every part matrix equals the base matrix, so every part draws with the
 * socket frame. Untextured assets fall back to a flat tint, mirroring the game's
 * colored fallback. Uploads are cached per geometry (assets are cached singletons
 * in the loader) and created lazily on the GL thread.
 */
public class AttachmentPreviewRenderer {

    private static final Logger logger = LoggerFactory.getLogger(AttachmentPreviewRenderer.class);

    private static final Vector4f UNTEXTURED_TINT = new Vector4f(0.85f, 0.85f, 0.85f, 1f);

    private boolean initialized = false;

    private final Map<SbeModelGeometry, UploadedModel> gpuByGeometry = new IdentityHashMap<>();

    public void initialize() {
        initialized = true;
    }

    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Draw one preview geometry with the given model matrix (the socket's
     * world frame — position, orientation, AND scale — composed with the
     * viewport's model-level transform by the caller).
     */
    public void render(ModelRenderer renderer, SbeModelGeometry geometry, Matrix4f modelMatrix,
                       Matrix4f viewMatrix, Matrix4f projectionMatrix) {
        if (!initialized || renderer == null || geometry == null || geometry.indices().length == 0) {
            return;
        }
        UploadedModel gpu = gpuByGeometry.computeIfAbsent(geometry, g -> {
            UploadedModel uploaded = UploadedModel.upload(g);
            logger.debug("Uploaded preview geometry: {} indices, {} materials",
                    g.indices().length, uploaded.plan().materialIds().size());
            return uploaded;
        });

        int partCount = geometry.parts().size();
        PartMatrices restPose = consumer -> {
            for (int i = 0; i < partCount; i++) {
                consumer.accept(i, modelMatrix);
            }
        };
        if (geometry.materials().isEmpty()) {
            renderer.drawFlat(gpu.mesh(), gpu.plan(), restPose, viewMatrix, projectionMatrix, UNTEXTURED_TINT);
        } else {
            renderer.drawLit(gpu.mesh(), gpu.plan(), gpu, restPose, viewMatrix, projectionMatrix,
                    ModelLighting.preview(viewMatrix), null);
        }
    }

    public void cleanup() {
        for (UploadedModel gpu : gpuByGeometry.values()) {
            gpu.close();
        }
        gpuByGeometry.clear();
        initialized = false;
    }
}
