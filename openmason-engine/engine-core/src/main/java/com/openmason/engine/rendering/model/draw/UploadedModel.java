package com.openmason.engine.rendering.model.draw;

import com.openmason.engine.diagnostics.GpuMemoryTracker;
import com.openmason.engine.entity.model.MaterialImage;
import com.openmason.engine.entity.model.EntityGeometry;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.system.MemoryUtil.memAlloc;
import static org.lwjgl.system.MemoryUtil.memFree;

/**
 * An upload-once model on the GPU: its static {@link ModelGpuMesh}, the
 * {@link ModelDrawPlan} and one texture per referenced material — everything
 * {@link ModelRenderer} needs to draw a loaded entity model (skin, corner normals, shape keys, material base colours; the plan carries
 * the model's vertex-group mask plan, its index buffer is the plan's reordered one).
 *
 * <p>Only materials the plan's textured batches reference get a texture (model
 * files can retain many unused editor materials). Textures are nearest-filtered
 * and edge-clamped. Needs a GL context.
 */
public final class UploadedModel implements AutoCloseable, MaterialTextures {

    private final ModelGpuMesh mesh;
    private final ModelDrawPlan plan;
    private final Map<Integer, Integer> textures = new HashMap<>();
    private final Map<Integer, float[]> baseColors = new HashMap<>();
    private long trackedTextureBytes;

    private UploadedModel(ModelGpuMesh mesh, ModelDrawPlan plan) {
        this.mesh = mesh;
        this.plan = plan;
    }

    /** Uploads {@code geometry}'s mesh and referenced material images. */
    public static UploadedModel upload(EntityGeometry geometry) {
        ModelDrawPlan plan = ModelDrawPlan.of(geometry);
        ModelGpuMesh mesh = new ModelGpuMesh(false, GpuMemoryTracker.Category.ENTITY_MESH);
        mesh.upload(geometry.vertices(), geometry.texCoords(), geometry.indices());
        mesh.updateSkin(geometry.rig().packed());
        mesh.updateNormals(geometry.normals());
        mesh.updateMorphs(geometry.morphs());
        UploadedModel model = new UploadedModel(mesh, plan);
        for (int materialId : plan.materialIds()) {
            MaterialImage image = geometry.materials().get(materialId);
            model.textures.put(materialId, model.uploadTexture(image));
            if (image.baseColor() != null) model.baseColors.put(materialId, image.baseColor());
        }
        return model;
    }

    public ModelGpuMesh mesh() {
        return mesh;
    }

    public ModelDrawPlan plan() {
        return plan;
    }

    /** The material's texture; batches without one are skipped. */
    @Override
    public int textureFor(int materialId) {
        Integer texture = textures.get(materialId);
        return texture != null ? texture : -1;
    }

    /** The material's OMO 1.9 base colour (multiplies its texture), or null = white. */
    @Override
    public float[] baseColorFor(int materialId) {
        return baseColors.get(materialId);
    }

    private int uploadTexture(MaterialImage image) {
        int textureId = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

        ByteBuffer pixels = memAlloc(image.rgba().length);
        try {
            pixels.put(image.rgba()).flip();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8,
                    image.width(), image.height(), 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        } finally {
            memFree(pixels);
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        long bytes = (long) image.width() * image.height() * 4L;
        GpuMemoryTracker.getInstance().track(GpuMemoryTracker.Category.TEXTURE_ATLAS, bytes);
        trackedTextureBytes += bytes;
        return textureId;
    }

    @Override
    public void close() {
        mesh.close();
        for (int textureId : textures.values()) {
            GL11.glDeleteTextures(textureId);
        }
        textures.clear();
        if (trackedTextureBytes > 0) {
            GpuMemoryTracker.getInstance().untrack(GpuMemoryTracker.Category.TEXTURE_ATLAS, trackedTextureBytes);
            trackedTextureBytes = 0;
        }
    }
}
