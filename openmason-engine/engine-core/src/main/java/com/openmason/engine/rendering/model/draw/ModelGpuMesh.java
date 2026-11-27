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
 * {@link #updateNormals} ran, octahedral normals ({@code ivec2} of int16, attribute 4) for {@link ModelNormalGlsl};
 * once {@link #updateMorphs} ran, the shape keys (OpenMason #171, {@link ModelMorphGlsl}): per-vertex record ranges
 * ({@code uvec2}, attribute 3), the delta and normal-delta buffer textures ({@code GL_RGBA32F}) and the per-frame
 * weight buffer texture ({@code GL_R32F}, {@link #uploadMorphWeights}).
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
    // Morph targets (#171).
    private com.openmason.engine.rig.RigMorphs morphs;
    private int morphRangeVbo;
    private int morphDeltaBuffer, morphDeltaTexture;
    private int morphNormalBuffer, morphNormalTexture;
    private int morphWeightBuffer, morphWeightTexture;
    private long morphBytes;
    private float[] morphClamped;
    private FloatBuffer morphUpload;

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

    /** True when shape keys were uploaded ({@link #updateMorphs}). */
    public boolean hasMorphs() {
        return morphs != null;
    }

    /** The uploaded shape keys, or null. */
    public com.openmason.engine.rig.RigMorphs morphs() {
        return morphs;
    }

    /** True when the uploaded shape keys carry normal deltas. */
    public boolean morphsHaveNormals() {
        return morphs != null && morphNormalTexture != 0;
    }

    /**
     * Uploads the mesh's shape keys ({@code RigMorphs.pack()}, once): the per-vertex record ranges on attribute
     * {@link ModelMorphGlsl#RANGE_LOCATION}, the delta (and, when any, normal-delta) buffer textures and the weight
     * buffer texture, filled with the targets' clamped default weights. Null (or no targets) removes them.
     *
     * @throws IllegalArgumentException when the targets cover another vertex count than the position buffer
     */
    public void updateMorphs(com.openmason.engine.rig.RigMorphs targets) {
        ensureCreated();
        int previousVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        GL30.glBindVertexArray(vao);
        long before = morphBytes;
        if (targets == null || targets.targetCount() == 0) {
            GL20.glDisableVertexAttribArray(ModelMorphGlsl.RANGE_LOCATION);
            GL30.glBindVertexArray(previousVao);
            deleteMorphObjects();
            morphs = null;
            morphBytes = 0;
            retrack(-before);
            return;
        }
        if (positionFloats > 0 && targets.vertexCount() != positionFloats / 3) {
            GL30.glBindVertexArray(previousVao);
            throw new IllegalArgumentException("morph targets cover " + targets.vertexCount() + " vertices, the mesh has "
                    + positionFloats / 3);
        }
        com.openmason.engine.rig.RigMorphs.Packed packed = targets.pack();
        if (morphRangeVbo == 0) {
            morphRangeVbo = GL15.glGenBuffers();
            morphDeltaBuffer = GL15.glGenBuffers();
            morphDeltaTexture = GL11.glGenTextures();
            morphWeightBuffer = GL15.glGenBuffers();
            morphWeightTexture = GL11.glGenTextures();
        }
        int n = packed.vertexCount();
        IntBuffer ranges = memAllocInt(Math.max(2, n * 2));
        try {
            ranges.put(packed.ranges()).flip();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, morphRangeVbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, ranges, usage);
            GL30.glVertexAttribIPointer(ModelMorphGlsl.RANGE_LOCATION, 2, GL11.GL_UNSIGNED_INT, 8, 0L);
            GL20.glEnableVertexAttribArray(ModelMorphGlsl.RANGE_LOCATION);
        } finally {
            memFree(ranges);
        }
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(previousVao);
        int previousTexture = GL11.glGetInteger(GL31.GL_TEXTURE_BINDING_BUFFER);
        bufferTexture(morphDeltaBuffer, morphDeltaTexture, packed.deltas(), GL30.GL_RGBA32F);
        if (packed.normals() != null) {
            if (morphNormalBuffer == 0) {
                morphNormalBuffer = GL15.glGenBuffers();
                morphNormalTexture = GL11.glGenTextures();
            }
            bufferTexture(morphNormalBuffer, morphNormalTexture, packed.normals(), GL30.GL_RGBA32F);
        } else if (morphNormalBuffer != 0) {
            GL15.glDeleteBuffers(morphNormalBuffer);
            GL11.glDeleteTextures(morphNormalTexture);
            morphNormalBuffer = morphNormalTexture = 0;
        }
        morphs = targets;
        morphClamped = new float[targets.targetCount()];
        if (morphUpload != null) memFree(morphUpload);
        morphUpload = memAllocFloat(targets.targetCount());
        bufferTexture(morphWeightBuffer, morphWeightTexture, targets.clampWeights(null, morphClamped), GL30.GL_R32F);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, previousTexture);
        morphBytes = 8L * n + 16L * packed.recordCount() * (packed.normals() != null ? 2 : 1)
                + 4L * targets.targetCount();
        retrack(morphBytes - before);
        uploadedBytes += morphBytes;
    }

    /** Fills a buffer texture (DYNAMIC: the weights change every frame). Leaves the buffer texture bound. */
    private void bufferTexture(int buffer, int texture, float[] data, int internalFormat) {
        FloatBuffer fb = memAllocFloat(Math.max(4, data.length));
        try {
            fb.put(data).flip();
            if (data.length == 0) fb.put(0, 0f).limit(4);
            GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, buffer);
            GL15.glBufferData(GL31.GL_TEXTURE_BUFFER, fb,
                    internalFormat == GL30.GL_R32F ? GL15.GL_DYNAMIC_DRAW : usage);
            GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, 0);
        } finally {
            memFree(fb);
        }
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, texture);
        GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, internalFormat, buffer);
    }

    /**
     * The per-draw shape-key weights (OpenMason #171): {@code weights} (raw, one per target in the rig's channel order;
     * null = every target's default) clamped natively to each target's range and written into the weight buffer
     * texture - {@code targetCount} floats, the only per-frame morph upload. No-op without morphs. Allocation-free.
     */
    public void uploadMorphWeights(float[] weights) {
        if (morphs == null) return;
        float[] clamped = weights == null ? morphs.clampWeights(defaults(), morphClamped)
                : morphs.clampWeights(weights, morphClamped);
        morphUpload.clear();
        morphUpload.put(clamped, 0, morphs.targetCount()).flip();
        GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, morphWeightBuffer);
        GL15.glBufferSubData(GL31.GL_TEXTURE_BUFFER, 0L, morphUpload);
        GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, 0);
        uploadedBytes += 4L * morphs.targetCount();
    }

    private float[] defaultWeights;

    private float[] defaults() {
        if (defaultWeights == null || defaultWeights.length != morphs.targetCount()) {
            defaultWeights = morphs.defaultWeights();
        }
        return defaultWeights;
    }

    /**
     * Binds the delta, normal-delta and weight buffer textures to {@link ModelMorphGlsl#DELTAS_UNIT} /
     * {@link ModelMorphGlsl#NORMALS_UNIT} / {@link ModelMorphGlsl#WEIGHTS_UNIT} (restores the active unit to 0); no-op
     * without morphs.
     */
    public void bindMorphs() {
        if (morphs == null) return;
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + ModelMorphGlsl.DELTAS_UNIT);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, morphDeltaTexture);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + ModelMorphGlsl.NORMALS_UNIT);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, morphNormalTexture);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + ModelMorphGlsl.WEIGHTS_UNIT);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, morphWeightTexture);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    /** Unbinds the three morph units (active unit 0 after). */
    public static void unbindMorphs() {
        for (int unit : new int[]{ModelMorphGlsl.DELTAS_UNIT, ModelMorphGlsl.NORMALS_UNIT, ModelMorphGlsl.WEIGHTS_UNIT}) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0);
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    /**
     * Uploads this draw's weights, binds the morph textures and switches the bound {@code program}'s morphing on
     * ({@code u_morphed}, {@code u_morphNormals}); returns false (program untouched) without morphs. Pair with
     * {@link #endMorphs}.
     */
    public boolean beginMorphs(com.openmason.engine.rendering.shaders.ShaderProgram program, float[] weights) {
        if (morphs == null) return false;
        uploadMorphWeights(weights);
        bindMorphs();
        program.setBool("u_morphed", true);
        program.setBool("u_morphNormals", morphsHaveNormals());
        return true;
    }

    /** Switches the bound {@code program}'s morphing off and unbinds the morph units. */
    public static void endMorphs(com.openmason.engine.rendering.shaders.ShaderProgram program) {
        program.setBool("u_morphed", false);
        program.setBool("u_morphNormals", false);
        unbindMorphs();
    }

    private void deleteMorphObjects() {
        if (morphRangeVbo != 0) {
            GL15.glDeleteBuffers(morphRangeVbo);
            GL15.glDeleteBuffers(morphDeltaBuffer);
            GL11.glDeleteTextures(morphDeltaTexture);
            GL15.glDeleteBuffers(morphWeightBuffer);
            GL11.glDeleteTextures(morphWeightTexture);
        }
        if (morphNormalBuffer != 0) {
            GL15.glDeleteBuffers(morphNormalBuffer);
            GL11.glDeleteTextures(morphNormalTexture);
        }
        morphRangeVbo = morphDeltaBuffer = morphDeltaTexture = morphWeightBuffer = morphWeightTexture = 0;
        morphNormalBuffer = morphNormalTexture = 0;
        if (morphUpload != null) {
            memFree(morphUpload);
            morphUpload = null;
        }
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
        java.nio.ShortBuffer buffer = staging((long) n * 4L).asShortBuffer();
        buffer.put(octNormals, 0, n * 2).flip();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, normalVbo);
        if (n == normalVertices) {
            GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, buffer);
        } else {
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buffer, usage);
        }
        GL30.glVertexAttribIPointer(ModelNormalGlsl.NORMAL_LOCATION, 2, GL11.GL_SHORT, 4, 0L);
        GL20.glEnableVertexAttribArray(ModelNormalGlsl.NORMAL_LOCATION);
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

    /**
     * Sub-upload of corners {@code [first, first + count)} of {@code positions} (#175: a drag frame re-sends only the
     * touched corners). Falls back to {@link #updatePositions} when the buffer does not hold
     * {@code positions.length} floats yet.
     */
    public void updatePositionRange(float[] positions, int first, int count) {
        if (positions == null || positions.length != positionFloats) {
            updatePositions(positions);
            return;
        }
        uploadFloatRange(positionVbo, positions, first * 3, count * 3);
    }

    /** {@link #updatePositionRange} for the UV buffer ({@code u,v} per corner). */
    public void updateUvRange(float[] uvs, int first, int count) {
        if (uvs == null || uvs.length != uvFloats) {
            updateUvs(uvs);
            return;
        }
        uploadFloatRange(uvVbo, uvs, first * 2, count * 2);
    }

    /**
     * Sub-upload of the octahedral normals of corners {@code [first, first + count)} (see {@link #updateNormals});
     * a full upload when the buffer does not hold {@code octNormals.length / 2} corners yet.
     */
    public void updateNormalsRange(short[] octNormals, int first, int count) {
        int n = octNormals == null ? 0 : octNormals.length / 2;
        if (n == 0 || n != normalVertices || normalVbo == 0) {
            updateNormals(octNormals);
            return;
        }
        int from = Math.max(0, first);
        int to = Math.min(n, first + count);
        if (to <= from) return;
        java.nio.ShortBuffer buffer = staging((long) (to - from) * 4L).asShortBuffer();
        buffer.put(octNormals, from * 2, (to - from) * 2).flip();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, normalVbo);
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, (long) from * 4L, buffer);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        uploadedBytes += (long) (to - from) * 4L;
    }

    private void uploadFloatRange(int buffer, float[] data, int firstFloat, int floatCount) {
        int from = Math.max(0, firstFloat);
        int to = Math.min(data.length, firstFloat + floatCount);
        if (to <= from) return;
        FloatBuffer fb = staging((long) (to - from) * Float.BYTES).asFloatBuffer();
        fb.put(data, from, to - from).flip();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, (long) from * Float.BYTES, fb);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        uploadedBytes += (long) (to - from) * Float.BYTES;
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
            IntBuffer buffer = staging((long) length * Integer.BYTES).asIntBuffer();
            buffer.put(indices).flip();
            if (length == indexCount) {
                GL15.glBufferSubData(GL15.GL_ELEMENT_ARRAY_BUFFER, 0L, buffer);
            } else {
                GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, buffer, usage);
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
            FloatBuffer fb = staging((long) length * Float.BYTES).asFloatBuffer();
            fb.put(data).flip();
            if (length == previousLength) {
                GL15.glBufferSubData(target, 0L, fb);
            } else {
                GL15.glBufferData(target, fb, usage);
            }
        }
        GL15.glBindBuffer(target, 0);
        retrack((long) length * Float.BYTES - (long) previousLength * Float.BYTES);
        uploadedBytes += (long) length * Float.BYTES;
        return length;
    }

    // One grow-only native staging buffer for the per-edit uploads (#175): a drag re-sends positions every frame,
    // and a fresh malloc/free of the whole array per upload was pure overhead. GL thread only; freed by close().
    private java.nio.ByteBuffer staging;

    /** The staging buffer, at least {@code bytes} long, position 0, limit {@code bytes}, native order. */
    private java.nio.ByteBuffer staging(long bytes) {
        if (bytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("upload too large: " + bytes + " bytes");
        }
        int size = (int) bytes;
        if (staging == null || staging.capacity() < size) {
            if (staging != null) org.lwjgl.system.MemoryUtil.memFree(staging);
            staging = org.lwjgl.system.MemoryUtil.memAlloc(Math.max(size, 4096));
        }
        staging.clear().limit(size);
        return staging;
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
        deleteMorphObjects();
        if (staging != null) {
            org.lwjgl.system.MemoryUtil.memFree(staging);
            staging = null;
        }
        morphs = null;
        morphBytes = 0;
        vao = positionVbo = uvVbo = ebo = skinRangeVbo = influenceBuffer = influenceTexture = 0;
        skinVertices = skinInfluences = normalVbo = normalVertices = 0;
        positionFloats = uvFloats = indexCount = 0;
        if (trackedBytes > 0) {
            GpuMemoryTracker.getInstance().untrack(category, trackedBytes);
        }
        trackedBytes = 0;
    }
}
