package com.openmason.engine.rendering.particles;

import com.openmason.engine.diagnostics.GpuMemoryTracker;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Objects;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Every particle sprite texture on the GPU as one {@code GL_TEXTURE_2D_ARRAY}: one sprite or flipbook frame per
 * layer, all {@link ParticleSpriteLayout#cellSize()} square, mip-mapped, premultiplied. A particle's frame code is
 * its layer index, so any mix of textured effects still draws in the renderer's single call.
 *
 * <pre>{@code
 * ParticleTextureArray sprites = ParticleTextureArray.build(library.effects(), loader, 64);
 * frame.sprites = sprites;          // the renderer binds it; the system resolves names to layers from it
 * ...
 * sprites.close();
 * }</pre>
 *
 * Effects added later need a new array (build one, swap {@code frame.sprites}, close the old one): their
 * particles show the missing-texture checker until then. Charged to {@link GpuMemoryTracker.Category#PARTICLES}.
 * Render-thread only.
 */
public final class ParticleTextureArray implements ParticleSpriteLayers, AutoCloseable {

    private final ParticleSpriteLayout layout;
    private int texture;
    private final int mipLevels;
    private long trackedBytes;

    private ParticleTextureArray(ParticleSpriteLayout layout) {
        this.layout = layout;
        int cell = layout.cellSize();
        mipLevels = 32 - Integer.numberOfLeadingZeros(cell);
        if (layout.layerCount() == 0) {
            return; // nothing to sample: the renderer binds its own white layer
        }
        int maxLayers = glGetInteger(GL_MAX_ARRAY_TEXTURE_LAYERS);
        if (layout.layerCount() > maxLayers) {
            throw new IllegalStateException("particle sprites need " + layout.layerCount()
                    + " array layers; this GL allows " + maxLayers);
        }
        int maxSize = glGetInteger(GL_MAX_TEXTURE_SIZE);
        if (cell > maxSize) {
            throw new IllegalStateException("particle sprite cell " + cell + " exceeds GL_MAX_TEXTURE_SIZE " + maxSize);
        }
        byte[] pixels = layout.pixels();
        ByteBuffer upload = MemoryUtil.memAlloc(pixels.length);
        int prevTexture = glGetInteger(GL_TEXTURE_BINDING_2D_ARRAY);
        int prevUnpack = glGetInteger(GL_UNPACK_ALIGNMENT);
        int prevRowLength = glGetInteger(GL_UNPACK_ROW_LENGTH);
        int prevImageHeight = glGetInteger(GL_UNPACK_IMAGE_HEIGHT);
        int prevUnpackBuffer = glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
        try {
            upload.put(pixels).flip();
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
            glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
            glPixelStorei(GL_UNPACK_IMAGE_HEIGHT, 0);
            texture = glGenTextures();
            glBindTexture(GL_TEXTURE_2D_ARRAY, texture);
            glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_RGBA8, cell, cell, layout.layerCount(), 0,
                    GL_RGBA, GL_UNSIGNED_BYTE, upload);
            // Premultiplied texels, so the box-filtered mips never pull in a transparent texel's colour.
            glGenerateMipmap(GL_TEXTURE_2D_ARRAY);
            glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
            glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        } finally {
            glBindTexture(GL_TEXTURE_2D_ARRAY, prevTexture);
            glPixelStorei(GL_UNPACK_ALIGNMENT, prevUnpack);
            glPixelStorei(GL_UNPACK_ROW_LENGTH, prevRowLength);
            glPixelStorei(GL_UNPACK_IMAGE_HEIGHT, prevImageHeight);
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, prevUnpackBuffer);
            MemoryUtil.memFree(upload);
        }
        trackedBytes = bytesFor(cell, layout.layerCount());
        GpuMemoryTracker.getInstance().track(GpuMemoryTracker.Category.PARTICLES, trackedBytes);
    }

    /** Uploads a packed layout. */
    public static ParticleTextureArray upload(ParticleSpriteLayout layout) {
        return new ParticleTextureArray(Objects.requireNonNull(layout, "layout"));
    }

    /** Packs and uploads every texture {@code effects} reference (see {@link ParticleSpriteLayout#pack}). */
    public static ParticleTextureArray build(Collection<ParticleEffect> effects, ParticleSpriteLoader loader,
                                             int cellSize) {
        return upload(ParticleSpriteLayout.pack(effects, loader, cellSize));
    }

    /** GPU bytes of a full mip chain of {@code layers} RGBA8 layers of {@code cell²}. */
    static long bytesFor(int cell, int layers) {
        long total = 0;
        for (int s = cell; ; s = Math.max(1, s / 2)) {
            total += (long) s * s * 4 * layers;
            if (s == 1) {
                return total;
            }
        }
    }

    @Override
    public int layerOf(String name) {
        return layout.layerOf(name);
    }

    public ParticleSpriteLayout layout() { return layout; }

    /** The GL texture name ({@code GL_TEXTURE_2D_ARRAY}), 0 when no sprite was packed or after {@link #close()}. */
    public int texture() { return texture; }

    public int layerCount() { return layout.layerCount(); }

    /** Mip levels in the chain (level 0 included). */
    public int mipLevels() { return mipLevels; }

    /** GPU bytes held (all layers, all mips). */
    public long gpuBytes() { return trackedBytes; }

    @Override
    public void close() {
        if (texture != 0) {
            glDeleteTextures(texture);
            texture = 0;
        }
        if (trackedBytes != 0) {
            GpuMemoryTracker.getInstance().untrack(GpuMemoryTracker.Category.PARTICLES, trackedBytes);
            trackedBytes = 0;
        }
    }
}
