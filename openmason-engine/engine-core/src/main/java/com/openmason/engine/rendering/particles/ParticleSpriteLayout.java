package com.openmason.engine.rendering.particles;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The GL-free half of {@link ParticleTextureArray}: which sprite textures exist, which layer each one gets, and
 * the pixels of every layer, ready to upload.
 *
 * <p>Every sprite (and every flipbook frame) is one layer of a {@code cellSize × cellSize} array — there is no
 * authored atlas, and layers never bleed into each other under linear filtering or mip-mapping the way cells
 * of a packed 2D atlas do. Images of any size are resampled to the cell: an area average when shrinking, nearest
 * texel for whole-number enlargements (pixel art stays crisp), bilinear otherwise. Non-square images stretch to
 * the square cell (the particle quad is square). Pixels are stored <b>premultiplied</b>, so filtering and mips
 * never darken a sprite's edge with the colour of its transparent texels.
 *
 * <p>Layer order is the order names are first referenced, so the same effects always pack the same way.
 * Immutable.
 */
public final class ParticleSpriteLayout implements ParticleSpriteLayers {

    private static final Logger LOG = LoggerFactory.getLogger(ParticleSpriteLayout.class);

    /** Default cell edge in texels (the issue's 64×64: enough for soft smoke, small for many layers). */
    public static final int DEFAULT_CELL_SIZE = 64;
    /** Largest cell edge accepted. */
    public static final int MAX_CELL_SIZE = 1024;
    /** Most layers a layout may hold: frame codes at or above this are reserved for the procedural sprites. */
    public static final int MAX_LAYERS = CompiledEffect.FIRST_PROCEDURAL_FRAME;

    /** An empty layout: no layers, nothing missing. */
    public static final ParticleSpriteLayout EMPTY =
            new ParticleSpriteLayout(DEFAULT_CELL_SIZE, List.of(), List.of(), new byte[0]);

    private final int cellSize;
    private final List<String> names;
    private final Map<String, Integer> layers;
    private final List<String> missing;
    private final byte[] pixels;

    private ParticleSpriteLayout(int cellSize, List<String> names, List<String> missing, byte[] pixels) {
        this.cellSize = cellSize;
        this.names = List.copyOf(names);
        this.missing = List.copyOf(missing);
        this.pixels = pixels;
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < names.size(); i++) {
            map.put(names.get(i), i);
        }
        this.layers = Collections.unmodifiableMap(map);
    }

    /** Packs every texture {@code effects} (and their sub-emitter effects) reference. */
    public static ParticleSpriteLayout pack(Collection<ParticleEffect> effects, ParticleSpriteLoader loader,
                                            int cellSize) {
        return packNames(referencedTextures(effects), loader, cellSize);
    }

    /**
     * Packs {@code names} (duplicates ignored) at {@code cellSize}. Names the loader cannot supply (null or an
     * exception) are skipped and listed in {@link #missing()}; their particles show the missing-texture checker.
     */
    public static ParticleSpriteLayout packNames(Collection<String> names, ParticleSpriteLoader loader, int cellSize) {
        Objects.requireNonNull(loader, "loader");
        if (cellSize < 1 || cellSize > MAX_CELL_SIZE) {
            throw new IllegalArgumentException("sprite cell size must be in [1, " + MAX_CELL_SIZE + "]: " + cellSize);
        }
        List<String> packed = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<byte[]> cells = new ArrayList<>();
        for (String name : new LinkedHashSet<>(names)) {
            ParticleImage image;
            try {
                image = loader.load(name);
            } catch (Exception e) {
                LOG.warn("particle sprite '{}' failed to load: {}", name, e.toString());
                image = null;
            }
            if (image == null) {
                missing.add(name);
                continue;
            }
            if (packed.size() >= MAX_LAYERS) {
                throw new IllegalArgumentException("more than " + MAX_LAYERS + " particle sprite textures");
            }
            packed.add(name);
            cells.add(toCell(image, cellSize));
        }
        if (!missing.isEmpty()) {
            LOG.warn("particle sprites missing (shown as a checker): {}", missing);
        }
        int cellBytes = cellSize * cellSize * 4;
        byte[] pixels = new byte[cellBytes * cells.size()];
        for (int i = 0; i < cells.size(); i++) {
            System.arraycopy(cells.get(i), 0, pixels, i * cellBytes, cellBytes);
        }
        return new ParticleSpriteLayout(cellSize, packed, missing, pixels);
    }

    /**
     * Every texture name a {@link ParticleEffect.Sprite#TEXTURE} effect in {@code effects} uses, following
     * sub-emitters, in first-use order without duplicates. Procedural effects contribute nothing even if they
     * keep names from an earlier texture setting.
     */
    public static List<String> referencedTextures(Collection<ParticleEffect> effects) {
        Set<String> out = new LinkedHashSet<>();
        Map<ParticleEffect, Boolean> seen = new IdentityHashMap<>();
        for (ParticleEffect e : effects) {
            collect(e, out, seen);
        }
        return List.copyOf(out);
    }

    private static void collect(ParticleEffect e, Set<String> out, Map<ParticleEffect, Boolean> seen) {
        if (seen.put(e, Boolean.TRUE) != null) {
            return;
        }
        if (e.sprite() == ParticleEffect.Sprite.TEXTURE) {
            out.addAll(e.textures());
        }
        for (ParticleEffect.SubEmitter s : e.subEmitters()) {
            collect(s.effect(), out, seen);
        }
    }

    @Override
    public int layerOf(String name) {
        Integer layer = layers.get(name);
        return layer == null ? -1 : layer;
    }

    public int cellSize() { return cellSize; }

    public int layerCount() { return names.size(); }

    /** Packed texture names in layer order. */
    public List<String> names() { return names; }

    /** Names that were asked for but could not be loaded. */
    public List<String> missing() { return missing; }

    /** Bytes of one layer's level-0 pixels. */
    public int layerBytes() { return cellSize * cellSize * 4; }

    /**
     * All layers' level-0 pixels back to back: premultiplied RGBA bytes, each layer {@code cellSize²} texels,
     * top row first. The array is the layout's own; do not modify it.
     */
    byte[] pixels() { return pixels; }

    /** The premultiplied {@code 0xRRGGBBAA} texel at column {@code x}, row {@code y} (0 = top) of {@code layer}. */
    public int texel(int layer, int x, int y) {
        int o = layer * layerBytes() + (y * cellSize + x) * 4;
        return (pixels[o] & 0xFF) << 24 | (pixels[o + 1] & 0xFF) << 16 | (pixels[o + 2] & 0xFF) << 8 | (pixels[o + 3] & 0xFF);
    }

    // ------------------------------------------------------------------------------------------------
    // Resampling
    // ------------------------------------------------------------------------------------------------

    /** The image premultiplied and resampled to {@code cell × cell}, as RGBA bytes (top row first). */
    static byte[] toCell(ParticleImage image, int cell) {
        int w = image.width(), h = image.height();
        float[] src = new float[w * h * 4];
        int[] rgba = image.rgba();
        for (int i = 0; i < rgba.length; i++) {
            int c = rgba[i];
            float a = (c & 0xFF) / 255f;
            src[i * 4] = (c >>> 24) / 255f * a;
            src[i * 4 + 1] = ((c >>> 16) & 0xFF) / 255f * a;
            src[i * 4 + 2] = ((c >>> 8) & 0xFF) / 255f * a;
            src[i * 4 + 3] = a;
        }
        float[] wide = resampleAxis(src, w, h, cell, true);
        float[] out = resampleAxis(wide, cell, h, cell, false);
        byte[] bytes = new byte[cell * cell * 4];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Math.round(Math.max(0f, Math.min(1f, out[i])) * 255f);
        }
        return bytes;
    }

    /**
     * Resamples one axis of a {@code w × h} RGBA float image to {@code outLen}: X when {@code horizontal}
     * (result {@code outLen × h}), else Y (result {@code w × outLen}).
     */
    private static float[] resampleAxis(float[] in, int w, int h, int outLen, boolean horizontal) {
        int n = horizontal ? w : h;
        if (n == outLen) {
            return in;
        }
        int lines = horizontal ? h : w;
        int outW = horizontal ? outLen : w;
        float[] out = new float[(horizontal ? outLen * h : w * outLen) * 4];
        for (int line = 0; line < lines; line++) {
            for (int o = 0; o < outLen; o++) {
                int dst = (horizontal ? line * outW + o : o * outW + line) * 4;
                if (n > outLen) {
                    // Shrink: average the source texels the output texel covers (fractional at the ends).
                    double x0 = (double) o * n / outLen, x1 = (double) (o + 1) * n / outLen;
                    for (int i = (int) Math.floor(x0); i < Math.min(n, (int) Math.ceil(x1)); i++) {
                        float wgt = (float) ((Math.min(x1, i + 1) - Math.max(x0, i)) / (x1 - x0));
                        int s = index(i, line, w, horizontal);
                        for (int ch = 0; ch < 4; ch++) {
                            out[dst + ch] += in[s + ch] * wgt;
                        }
                    }
                } else if (outLen % n == 0) {
                    // Whole-number enlargement: replicate texels (pixel art stays sharp).
                    int s = index(o * n / outLen, line, w, horizontal);
                    System.arraycopy(in, s, out, dst, 4);
                } else {
                    double sx = (o + 0.5) * n / outLen - 0.5;
                    int i0 = (int) Math.floor(sx);
                    float f = (float) (sx - i0);
                    int a = index(Math.max(0, i0), line, w, horizontal);
                    int b = index(Math.min(n - 1, i0 + 1), line, w, horizontal);
                    for (int ch = 0; ch < 4; ch++) {
                        out[dst + ch] = in[a + ch] + (in[b + ch] - in[a + ch]) * f;
                    }
                }
            }
        }
        return out;
    }

    private static int index(int i, int line, int w, boolean horizontal) {
        return (horizontal ? line * w + i : i * w + line) * 4;
    }
}
