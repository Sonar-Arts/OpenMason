package com.openmason.engine.entity.model;

/**
 * A decoded material texture: tightly-packed RGBA8 pixels ready for GPU upload.
 *
 * <p>Rows run top-to-bottom, in source PNG order — the texture is uploaded
 * exactly as authored, with the OMO's texture coordinates used verbatim. Held
 * as a plain {@code byte[]} so the loader stays free of native memory and
 * GL-thread requirements; the renderer wraps it in a direct buffer at upload.
 *
 * <p>{@code baseColor} (OMO 1.9) multiplies the texture when drawn (Blender / glTF semantics); a material with a base
 * colour and no image loads as a 1x1 white image carrying it.
 *
 * @param width     texture width in pixels
 * @param height    texture height in pixels
 * @param rgba      {@code width * height * 4} bytes, RGBA8, top row first
 * @param baseColor RGBA base colour (4 floats), or null = white
 */
public record MaterialImage(int width, int height, byte[] rgba, float[] baseColor) {

    public MaterialImage {
        if (baseColor != null) {
            if (baseColor.length != 4) throw new IllegalArgumentException("baseColor must hold 4 floats (RGBA)");
            baseColor = baseColor.clone();
        }
    }

    /** An image without a base colour. */
    public MaterialImage(int width, int height, byte[] rgba) {
        this(width, height, rgba, null);
    }

    /** A 1x1 white image carrying {@code baseColor} (a colour-only material). */
    public static MaterialImage solid(float[] baseColor) {
        return new MaterialImage(1, 1, new byte[]{-1, -1, -1, -1}, baseColor);
    }

    @Override
    public float[] baseColor() {
        return baseColor == null ? null : baseColor.clone();
    }
}
