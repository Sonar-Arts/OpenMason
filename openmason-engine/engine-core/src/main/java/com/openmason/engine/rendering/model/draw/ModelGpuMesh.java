package com.openmason.engine.rendering.model.draw;

import com.openmason.engine.diagnostics.GpuMemoryTracker;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.system.MemoryUtil.memAllocFloat;
import static org.lwjgl.system.MemoryUtil.memAllocInt;
import static org.lwjgl.system.MemoryUtil.memFree;

/**
 * The GPU side of one model mesh: a VAO over separate position ({@code vec3},
 * attribute 0) and UV ({@code vec2}, attribute 1) buffers plus an element buffer.
 *
 * <p>Positions and UVs live in their own buffers so an interactive edit that only
 * moves vertices re-uploads 12 bytes per corner instead of the whole vertex. A
 * same-size update is a {@code glBufferSubData}; a size change reallocates.
 * {@link #uploadedBytes()} counts every byte sent, for edit-cost measurement.
 *
 * <p>All methods except the accessors need a current GL context.
 */
public final class ModelGpuMesh implements AutoCloseable {

    private final int usage;
    private final GpuMemoryTracker.Category category;

    private int vao;
    private int positionVbo;
    private int uvVbo;
    private int ebo;

    private int positionFloats;
    private int uvFloats;
    private int indexCount;

    private long trackedBytes;
    private long uploadedBytes;

    /**
     * @param dynamic  true for meshes edited in place (editor), false for upload-once assets
     * @param category GPU memory tracker category to account the buffers under
     */
    public ModelGpuMesh(boolean dynamic, GpuMemoryTracker.Category category) {
        this.usage = dynamic ? GL15.GL_DYNAMIC_DRAW : GL15.GL_STATIC_DRAW;
        this.category = category;
    }

    /** True once {@link #upload} (or any update) created the GL objects. */
    public boolean isCreated() {
        return vao != 0;
    }

    /** VAO to bind for drawing; 0 before the first upload. */
    public int vao() {
        return vao;
    }

    /** Number of indices in the element buffer. */
    public int indexCount() {
        return indexCount;
    }

    /** Number of vertices (corners) in the position buffer. */
    public int vertexCount() {
        return positionFloats / 3;
    }

    /** Total bytes sent to the GPU by this mesh since creation. */
    public long uploadedBytes() {
        return uploadedBytes;
    }

    /** Full upload of all three buffers. */
    public void upload(float[] positions, float[] uvs, int[] indices) {
        ensureCreated();
        updatePositions(positions);
        updateUvs(uvs);
        updateIndices(indices);
    }

    /** Replace the position buffer ({@code x,y,z} per corner). */
    public void updatePositions(float[] positions) {
        ensureCreated();
        positionFloats = uploadFloats(GL15.GL_ARRAY_BUFFER, positionVbo, positions, positionFloats);
    }

    /** Replace the UV buffer ({@code u,v} per corner). */
    public void updateUvs(float[] uvs) {
        ensureCreated();
        uvFloats = uploadFloats(GL15.GL_ARRAY_BUFFER, uvVbo, uvs, uvFloats);
    }

    /** Replace the element buffer. */
    public void updateIndices(int[] indices) {
        ensureCreated();
        int length = indices == null ? 0 : indices.length;
        // The element buffer binding is VAO state: bind the VAO so it records it.
        int previousVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ebo);
        if (length == 0) {
            GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, 0L, usage);
        } else {
            IntBuffer buffer = memAllocInt(length);
            try {
                buffer.put(indices).flip();
                if (length == indexCount) {
                    GL15.glBufferSubData(GL15.GL_ELEMENT_ARRAY_BUFFER, 0L, buffer);
                } else {
                    GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, buffer, usage);
                }
            } finally {
                memFree(buffer);
            }
        }
        GL30.glBindVertexArray(previousVao);
        retrack((long) length * Integer.BYTES - (long) indexCount * Integer.BYTES);
        uploadedBytes += (long) length * Integer.BYTES;
        indexCount = length;
    }

    private int uploadFloats(int target, int buffer, float[] data, int previousLength) {
        int length = data == null ? 0 : data.length;
        GL15.glBindBuffer(target, buffer);
        if (length == 0) {
            GL15.glBufferData(target, 0L, usage);
        } else {
            FloatBuffer fb = memAllocFloat(length);
            try {
                fb.put(data).flip();
                if (length == previousLength) {
                    GL15.glBufferSubData(target, 0L, fb);
                } else {
                    GL15.glBufferData(target, fb, usage);
                }
            } finally {
                memFree(fb);
            }
        }
        GL15.glBindBuffer(target, 0);
        retrack((long) length * Float.BYTES - (long) previousLength * Float.BYTES);
        uploadedBytes += (long) length * Float.BYTES;
        return length;
    }

    private void ensureCreated() {
        if (vao != 0) return;
        vao = GL30.glGenVertexArrays();
        positionVbo = GL15.glGenBuffers();
        uvVbo = GL15.glGenBuffers();
        ebo = GL15.glGenBuffers();

        int previousVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, positionVbo);
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 3 * Float.BYTES, 0);
        GL20.glEnableVertexAttribArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, uvVbo);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 2 * Float.BYTES, 0);
        GL20.glEnableVertexAttribArray(1);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ebo);
        GL30.glBindVertexArray(previousVao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    private void retrack(long delta) {
        if (delta > 0) {
            GpuMemoryTracker.getInstance().track(category, delta);
        } else if (delta < 0) {
            GpuMemoryTracker.getInstance().untrack(category, -delta);
        }
        trackedBytes += delta;
    }

    /** Delete the GL objects and release the tracked memory. Safe to call twice. */
    @Override
    public void close() {
        if (vao == 0) return;
        GL30.glDeleteVertexArrays(vao);
        GL15.glDeleteBuffers(positionVbo);
        GL15.glDeleteBuffers(uvVbo);
        GL15.glDeleteBuffers(ebo);
        vao = positionVbo = uvVbo = ebo = 0;
        positionFloats = uvFloats = indexCount = 0;
        if (trackedBytes > 0) {
            GpuMemoryTracker.getInstance().untrack(category, trackedBytes);
        }
        trackedBytes = 0;
    }
}
