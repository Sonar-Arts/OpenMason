package com.openmason.engine.rendering.particles;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * One sprite texture's pixels as the host hands them to the engine: {@code width × height} texels,
 * {@code 0xRRGGBBAA} with straight (non-premultiplied) alpha, row-major with the <b>top</b> row first (image
 * order). The packer resamples it to the array's cell size and premultiplies it.
 */
public record ParticleImage(int width, int height, int[] rgba) {

    public ParticleImage {
        Objects.requireNonNull(rgba, "rgba");
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("sprite image must be at least 1x1: " + width + "x" + height);
        }
        if (rgba.length != width * height) {
            throw new IllegalArgumentException("sprite image has " + rgba.length + " texels, expected "
                    + width + "x" + height);
        }
    }

    /** The texel at column {@code x}, row {@code y} (row 0 = top). */
    public int texel(int x, int y) {
        return rgba[y * width + x];
    }

    /** Converts a decoded image (any type; alpha kept, straight). */
    public static ParticleImage of(BufferedImage image) {
        int w = image.getWidth(), h = image.getHeight();
        int[] argb = image.getRGB(0, 0, w, h, null, 0, w);
        int[] rgba = new int[argb.length];
        for (int i = 0; i < argb.length; i++) {
            int c = argb[i];
            rgba[i] = (c << 8) | (c >>> 24);
        }
        return new ParticleImage(w, h, rgba);
    }

    /** Decodes a PNG (or any format ImageIO reads); the stream is not closed. */
    public static ParticleImage decode(InputStream in) throws IOException {
        BufferedImage image = ImageIO.read(in);
        if (image == null) {
            throw new IOException("not a readable image");
        }
        return of(image);
    }
}
