package com.openmason.engine.rendering.model.draw;

import com.openmason.engine.diagnostics.GpuMemoryTracker;
import com.openmason.engine.rig.JointPalette;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;

import java.nio.FloatBuffer;

/**
 * The GPU copy of a {@link JointPalette}: a buffer texture (RGBA32F, 4 texels = one column-major
 * matrix per joint) that {@link ModelSkinGlsl} reads. One instance is re-filled for every draw
 * (orphan + sub-upload), so it holds whichever palette was uploaded last. Needs a GL context.
 */
public final class JointPaletteTexture implements AutoCloseable {

    /** Texture unit the palette binds to unless a host says otherwise (free in engine and game). */
    public static final int DEFAULT_UNIT = 31;

    private int buffer;
    private int texture;
    private long capacityBytes;

    /** Uploads the palette's skin matrices. */
    public void upload(JointPalette palette) {
        upload(palette.skinBuffer());
    }

    /** Uploads {@code matrices} (16 floats per joint). */
    public void upload(FloatBuffer matrices) {
        ensureCreated();
        long bytes = (long) matrices.remaining() * Float.BYTES;
        GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, buffer);
        if (bytes > capacityBytes) {
            long grown = Math.max(bytes, Math.max(1024L, capacityBytes * 2));
            GL15.glBufferData(GL31.GL_TEXTURE_BUFFER, grown, GL15.GL_STREAM_DRAW);
            GpuMemoryTracker.getInstance().track(GpuMemoryTracker.Category.ENTITY_MESH, grown - capacityBytes);
            capacityBytes = grown;
            // A new data store must be re-attached to the texture.
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, texture);
            GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RGBA32F, buffer);
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0);
        } else {
            // Orphan so a draw still reading the previous palette never stalls this upload.
            GL15.glBufferData(GL31.GL_TEXTURE_BUFFER, capacityBytes, GL15.GL_STREAM_DRAW);
        }
        GL15.glBufferSubData(GL31.GL_TEXTURE_BUFFER, 0L, matrices);
        GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, 0);
    }

    /** Binds the palette texture to {@code unit} (restores the active unit to 0). */
    public void bind(int unit) {
        ensureCreated();
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, texture);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    /** Unbinds whatever buffer texture is on {@code unit}. */
    public static void unbind(int unit) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    private void ensureCreated() {
        if (texture != 0) return;
        buffer = GL15.glGenBuffers();
        texture = GL11.glGenTextures();
    }

    @Override
    public void close() {
        if (texture == 0) return;
        GL11.glDeleteTextures(texture);
        GL15.glDeleteBuffers(buffer);
        texture = buffer = 0;
        if (capacityBytes > 0) {
            GpuMemoryTracker.getInstance().untrack(GpuMemoryTracker.Category.ENTITY_MESH, capacityBytes);
        }
        capacityBytes = 0;
    }
}
