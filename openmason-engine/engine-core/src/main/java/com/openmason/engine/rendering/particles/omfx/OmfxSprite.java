package com.openmason.engine.rendering.particles.omfx;

import com.openmason.engine.format.omt.OMTArchive;
import com.openmason.engine.format.omt.OMTReader;
import com.openmason.engine.format.omt.OmtCompositor;
import com.openmason.engine.format.omt.TextureBytes;
import com.openmason.engine.rendering.particles.ParticleImage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

/**
 * A sprite image embedded in an {@code .omfx} document (OM #101): the pixels behind a texture name an effect lists
 * ({@link com.openmason.engine.rendering.particles.ParticleEffect#textures}). The bytes are kept exactly as stored —
 * a PNG, or an OMT layer stack that is flattened when the sprite is decoded.
 *
 * @param name  the texture name effects use
 * @param bytes the PNG or OMT file bytes
 */
public record OmfxSprite(String name, byte[] bytes) {

    /** How the bytes are encoded. */
    public enum Kind {
        PNG("png"),
        OMT("omt");

        /** File extension used for the archive entry. */
        public final String extension;

        Kind(String extension) {
            this.extension = extension;
        }
    }

    public OmfxSprite {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(bytes, "bytes");
        if (name.isBlank()) {
            throw new IllegalArgumentException("sprite name is blank");
        }
        bytes = bytes.clone();
    }

    /** The stored bytes (a copy). */
    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    /** PNG when the bytes carry the PNG signature, else OMT. */
    public Kind kind() {
        return TextureBytes.isPng(bytes) ? Kind.PNG : Kind.OMT;
    }

    /**
     * Fails with a reason when the bytes are neither a PNG nor a readable OMT archive (the reader calls this, so a
     * damaged sprite is reported at load, not as a magenta checker later).
     */
    void check() throws IOException {
        if (kind() == Kind.PNG) {
            if (TextureBytes.pngSize(bytes) == null) {
                throw new IOException("sprite '" + name + "': truncated PNG header");
            }
            return;
        }
        try {
            new OMTReader().read(bytes);
        } catch (IOException | RuntimeException e) {
            throw new IOException("sprite '" + name + "' is neither a PNG nor an OMT: " + e.getMessage(), e);
        }
    }

    /** Decodes the image (an OMT's visible layers flattened). */
    public ParticleImage decode() throws IOException {
        if (kind() == Kind.PNG) {
            return ParticleImage.decode(new ByteArrayInputStream(bytes));
        }
        OMTArchive archive = new OMTReader().read(bytes);
        OmtCompositor.Composited flat = OmtCompositor.composite(archive, OmfxSprite::decodePng);
        if (flat == null) {
            throw new IOException("sprite '" + name + "': the OMT has no visible pixels");
        }
        byte[] src = flat.rgba();
        int[] rgba = new int[flat.width() * flat.height()];
        for (int i = 0; i < rgba.length; i++) {
            int o = i * 4;
            rgba[i] = ((src[o] & 0xFF) << 24) | ((src[o + 1] & 0xFF) << 16) | ((src[o + 2] & 0xFF) << 8)
                    | (src[o + 3] & 0xFF);
        }
        return new ParticleImage(flat.width(), flat.height(), rgba);
    }

    private static OmtCompositor.PngDecoder.Decoded decodePng(byte[] png) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
            if (image == null) {
                return null;
            }
            int w = image.getWidth(), h = image.getHeight();
            int[] argb = image.getRGB(0, 0, w, h, null, 0, w);
            byte[] out = new byte[argb.length * 4];
            for (int i = 0; i < argb.length; i++) {
                int c = argb[i];
                out[i * 4] = (byte) (c >>> 16);
                out[i * 4 + 1] = (byte) (c >>> 8);
                out[i * 4 + 2] = (byte) c;
                out[i * 4 + 3] = (byte) (c >>> 24);
            }
            return new OmtCompositor.PngDecoder.Decoded(w, h, out);
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof OmfxSprite s && name.equals(s.name) && Arrays.equals(bytes, s.bytes);
    }

    @Override
    public int hashCode() {
        return 31 * name.hashCode() + Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "OmfxSprite[" + name + ", " + kind() + ", " + bytes.length + " bytes]";
    }
}
