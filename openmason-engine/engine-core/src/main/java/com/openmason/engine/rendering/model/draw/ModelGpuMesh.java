package com.openmason.engine.rendering.model.draw;

import com.openmason.engine.diagnostics.GpuMemoryTracker;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.system.MemoryUtil.memAllocFloat;
import static org.lwjgl.system.MemoryUtil.memAllocInt;
import static org.lwjgl.system.MemoryUtil.memFree;

/**
 * The GPU side of one model mesh: a VAO over separate position ({@code vec3},
 * attribute 0) and UV ({@code vec2}, attribute 1) buffers plus an element buffer; once
 * {@link #updateSkin} ran, the skin ranges ({@code uvec2} of uint32, attribute 2) and the influence buffer
 * texture ({@code GL_RG16UI}, bound by {@link #bindSkinInfluences}) for {@link ModelSkinGlsl}; once
 * {@link #updateNormals} ran, octahedral normals ({@code ivec2} of int16, attribute 4) for {@link ModelNormalGlsl}.
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
    private int skinRangeVbo;
    private int influenceBuffer;
    private int influenceTexture;
    private int skinVertices;
    private int skinInfluences;
    private int normalVbo;
    private int normalVertices;

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

    /** True when per-vertex skin attributes were uploaded ({@link #updateSkin}). */
    public boolean hasSkin() {
        return skinVertices > 0;
    }

    /** True when per-vertex normals were uploaded ({@link #updateNormals}). */
    public boolean hasNormals() {
        return normalVertices > 0;
    }

    /**
     * Uploads the skin (rig ABI 3 packing, see {@link com.openmason.engine.rig.PackedSkin}): the per-vertex
     * {@code (offset, count)} ranges on attribute {@link ModelSkinGlsl#RANGE_LOCATION} and the influences into the
     * buffer texture {@link #bindSkinInfluences} binds. Null removes them.
     */
    public void updateSkin(com.openmason.engine.rig.PackedSkin skin) {
        ensureCreated();
        int previousVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        GL30.glBindVertexArray(vao);
        long before = skinBytes();
        if (skin == null) {
            GL20.glDisableVertexAttribArray(ModelSkinGlsl.RANGE_LOCATION);
            GL30.glBindVertexArray(previousVao);
            skinVertices = skinInfluences = 0;
            retrack(skinBytes() - before);
            return;
        }
        if (skinRangeVbo == 0) {
            skinRangeVbo = GL15.glGenBuffers();
            influenceBuffer = GL15.glGenBuffers();
            influenceTexture = GL11.glGenTextures();
        }
        int n = skin.vertexCount();
        int records = skin.influenceCount();
        IntBuffer ranges = memAllocInt(Math.max(2, n * 2));
        java.nio.ShortBuffer influences = org.lwjgl.system.MemoryUtil.memAllocShort(Math.max(2, records * 2));
        try {
            ranges.put(skin.ranges()).flip();
            influences.put(skin.influences()).flip();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, skinRangeVbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, ranges, usage);
            GL30.glVertexAttribIPointer(ModelSkinGlsl.RANGE_LOCATION, 2, GL11.GL_UNSIGNED_INT, 8, 0L);
            GL20.glEnableVertexAttribArray(ModelSkinGlsl.RANGE_LOCATION);
            GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, influenceBuffer);
            GL15.glBufferData(GL31.GL_TEXTURE_BUFFER, influences, usage);
            GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, 0);
            int previousTexture = GL11.glGetInteger(GL31.GL_TEXTURE_BINDING_BUFFER);
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, influenceTexture);
            GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RG16UI, influenceBuffer);
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, previousTexture);
        } finally {
            memFree(ranges);
            org.lwjgl.system.MemoryUtil.memFree(influences);
        }
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(previousVao);
        skinVertices = n;
        skinInfluences = records;
        retrack(skinBytes() - before);
        uploadedBytes += skinBytes();
    }

    private long skinBytes() {
        return (long) skinVertices * 8L + (long) skinInfluences * 4L;
    }

    /** The influence buffer texture (0 without a skin). */
    public int skinInfluenceTexture() {
        return skinVertices > 0 ? influenceTexture : 0;
    }

    /** Binds the influence buffer texture to {@code unit} (restores the active unit to 0); no-op without a skin. */
    public void bindSkinInfluences(int unit) {
        if (skinVertices == 0) return;
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, influenceTexture);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    /**
     * Uploads per-vertex octahedral normals (2 x int16 per vertex, {@link ModelNormalGlsl}; the pair
     * {@link ModelNormalGlsl#FLAT} marks a flat-shaded corner) on attribute {@link ModelNormalGlsl#NORMAL_LOCATION}.
     * Null (or empty) removes them: every corner then shades flat.
     */
    public void updateNormals(short[] octNormals) {
        ensureCreated();
        int previousVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        GL30.glBindVertexArray(vao);
        int n = octNormals == null ? 0 : octNormals.length / 2;
        long before = (long) normalVertices * 4L;
        if (n == 0) {
            GL20.glDisableVertexAttribArray(ModelNormalGlsl.NORMAL_LOCATION);
            GL30.glBindVertexArray(previousVao);
            normalVertices = 0;
            retrack(-before);
            return;
        }
        if (normalVbo == 0) normalVbo = GL15.glGenBuffers();
        java.nio.ShortBuffer buffer = org.lwjgl.system.MemoryUtil.memAllocShort(n * 2);
        try {
            buffer.put(octNormals, 0, n * 2).flip();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, normalVbo);
            if (n == normalVertices) {
                GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, buffer);
            } else {
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buffer, usage);
            }
            GL30.glVertexAttribIPointer(ModelNormalGlsl.NORMAL_LOCATION, 2, GL11.GL_SHORT, 4, 0L);
            GL20.glEnableVertexAttribArray(ModelNormalGlsl.NORMAL_LOCATION);
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(buffer);
        }
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(previousVao);
        normalVertices = n;
        retrack((long) n * 4L - before);
        uploadedBytes += (long) n * 4L;
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
        if (skinRangeVbo != 0) {
            GL15.glDeleteBuffers(skinRangeVbo);
            GL15.glDeleteBuffers(influenceBuffer);
            GL11.glDeleteTextures(influenceTexture);
        }
        if (normalVbo != 0) GL15.glDeleteBuffers(normalVbo);
        vao = positionVbo = uvVbo = ebo = skinRangeVbo = influenceBuffer = influenceTexture = 0;
        skinVertices = skinInfluences = normalVbo = normalVertices = 0;
        positionFloats = uvFloats = indexCount = 0;
        if (trackedBytes > 0) {
            GpuMemoryTracker.getInstance().untrack(category, trackedBytes);
        }
        trackedBytes = 0;
    }
}
