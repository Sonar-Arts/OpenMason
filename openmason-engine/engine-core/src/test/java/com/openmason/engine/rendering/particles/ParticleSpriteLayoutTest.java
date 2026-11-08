package com.openmason.engine.rendering.particles;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.*;

/** GL-free packing of particle sprite textures into texture-array layers (#97). */
class ParticleSpriteLayoutTest {

    private static ParticleImage solid(int w, int h, int rgba) {
        int[] px = new int[w * h];
        java.util.Arrays.fill(px, rgba);
        return new ParticleImage(w, h, px);
    }

    @Test
    void layersFollowFirstUseIncludingSubEmittersWithoutDuplicates() {
        ParticleEffect spark = ParticleEffect.builder("spark").texture("spark").build();
        ParticleEffect smoke = ParticleEffect.builder("smoke").textures(List.of("smoke_0", "smoke_1"), true)
                .subEmitter(ParticleEffect.SubEmitter.on(ParticleEffect.Trigger.DEATH, spark, 1)).build();
        ParticleEffect again = ParticleEffect.builder("again").textures(List.of("smoke_1", "spark"), false).build();
        ParticleEffect disc = ParticleEffect.builder("disc").textureNames(List.of("ignored")).build();

        assertEquals(List.of("smoke_0", "smoke_1", "spark"),
                ParticleSpriteLayout.referencedTextures(List.of(smoke, again, disc)));

        List<String> asked = new ArrayList<>();
        ParticleSpriteLayout layout = ParticleSpriteLayout.pack(List.of(smoke, again, disc),
                name -> { asked.add(name); return solid(2, 2, 0xFFFFFFFF); }, 8);
        assertEquals(List.of("smoke_0", "smoke_1", "spark"), asked, "each image loaded once");
        assertEquals(3, layout.layerCount());
        assertEquals(0, layout.layerOf("smoke_0"));
        assertEquals(2, layout.layerOf("spark"));
        assertEquals(-1, layout.layerOf("ignored"));
        assertEquals(8 * 8 * 4 * 3, layout.pixels().length);
    }

    @Test
    void missingAndFailingImagesAreSkippedAndListed() {
        ParticleSpriteLayout layout = ParticleSpriteLayout.packNames(List.of("a", "gone", "broken", "b"), name ->
                switch (name) {
                    case "gone" -> null;
                    case "broken" -> throw new IOException("corrupt");
                    default -> solid(1, 1, 0xFF0000FF);
                }, 4);
        assertEquals(List.of("a", "b"), layout.names());
        assertEquals(List.of("gone", "broken"), layout.missing());
        assertEquals(1, layout.layerOf("b"), "a missing image takes no layer");
        assertEquals(-1, layout.layerOf("gone"));
    }

    @Test
    void texelsArePremultiplied() {
        ParticleSpriteLayout layout = ParticleSpriteLayout.packNames(List.of("half"),
                name -> solid(4, 4, 0xFF804080), 4);
        int t = layout.texel(0, 1, 1);
        assertEquals(0x80, t & 0xFF, "alpha kept");
        assertEquals(0x80, t >>> 24, 1, "red 255 × alpha 0.5");
        assertEquals(0x40, (t >>> 16) & 0xFF, 1, "green 128 × 0.5");
        assertEquals(0x20, (t >>> 8) & 0xFF, 1, "blue 64 × 0.5");
    }

    @Test
    void wholeNumberEnlargementKeepsPixelArtSharpAndTopRowFirst() {
        // 2×2: red, green / blue, white — enlarged ×4 must stay four hard-edged quadrants.
        ParticleImage img = new ParticleImage(2, 2, new int[]{0xFF0000FF, 0x00FF00FF, 0x0000FFFF, 0xFFFFFFFF});
        ParticleSpriteLayout layout = ParticleSpriteLayout.packNames(List.of("q"), name -> img, 8);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int expected = img.texel(x / 4, y / 4);
                assertEquals(expected, layout.texel(0, x, y), "texel " + x + "," + y);
            }
        }
    }

    @Test
    void shrinkingAveragesInPremultipliedSpace() {
        // A transparent red texel next to an opaque white one: the straight-alpha average would be pink;
        // premultiplied it is half-covered white (the transparent texel's colour must not leak in).
        ParticleImage img = new ParticleImage(2, 1, new int[]{0xFF000000, 0xFFFFFFFF});
        ParticleSpriteLayout layout = ParticleSpriteLayout.packNames(List.of("edge"), name -> img, 1);
        int t = layout.texel(0, 0, 0);
        assertEquals(0x80, t & 0xFF, 1);
        assertEquals(0x80, t >>> 24, 1);
        assertEquals(0x80, (t >>> 16) & 0xFF, 1, "no red from the transparent texel");
        assertEquals(0x80, (t >>> 8) & 0xFF, 1);
    }

    @Test
    void anySizeAndAspectFitsTheCell() {
        for (int[] wh : new int[][]{{3, 5}, {100, 7}, {1, 1}, {64, 64}, {130, 130}}) {
            ParticleSpriteLayout layout = ParticleSpriteLayout.packNames(List.of("s"),
                    name -> solid(wh[0], wh[1], 0x336699FF), 16);
            for (int y = 0; y < 16; y += 5) {
                for (int x = 0; x < 16; x += 5) {
                    int t = layout.texel(0, x, y);
                    assertEquals(0x33, t >>> 24, 1, wh[0] + "x" + wh[1]);
                    assertEquals(0x66, (t >>> 16) & 0xFF, 1);
                    assertEquals(0x99, (t >>> 8) & 0xFF, 1);
                    assertEquals(0xFF, t & 0xFF);
                }
            }
        }
    }

    @Test
    void cellSizeIsBounded() {
        assertThrows(IllegalArgumentException.class, () -> ParticleSpriteLayout.packNames(List.of(), n -> null, 0));
        assertThrows(IllegalArgumentException.class,
                () -> ParticleSpriteLayout.packNames(List.of(), n -> null, ParticleSpriteLayout.MAX_CELL_SIZE + 1));
        assertEquals(0, ParticleSpriteLayout.EMPTY.layerCount());
    }

    @Test
    void imagesDecodeFromPngWithStraightAlphaTopRowFirst() throws IOException {
        BufferedImage img = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, 0x80FF0000); // top-left: half-transparent red
        img.setRGB(1, 1, 0xFF00FF00);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(img, "png", png);
        ParticleImage decoded = ParticleImage.decode(new ByteArrayInputStream(png.toByteArray()));
        assertEquals(2, decoded.width());
        assertEquals(0xFF000080, decoded.texel(0, 0));
        assertEquals(0x00FF00FF, decoded.texel(1, 1));
        assertEquals(0, decoded.texel(1, 0) & 0xFF, "untouched texels are transparent");
        assertThrows(IOException.class, () -> ParticleImage.decode(new ByteArrayInputStream(new byte[]{1, 2, 3})));
    }

    @Test
    void textureSpritesValidateTheirNames() {
        assertThrows(IllegalArgumentException.class,
                () -> ParticleEffect.builder("x").sprite(ParticleEffect.Sprite.TEXTURE).build());
        assertThrows(IllegalArgumentException.class, () -> ParticleEffect.builder("x").texture(" ").build());
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i <= ParticleEffect.MAX_TEXTURES; i++) {
            tooMany.add("f" + i);
        }
        assertThrows(IllegalArgumentException.class,
                () -> ParticleEffect.builder("x").textures(tooMany, true).build());
    }

    @Test
    void gpuBytesCountTheWholeMipChain() {
        // 4×4 RGBA8: 64 + 16 + 4 bytes per layer.
        assertEquals((64 + 16 + 4) * 3, ParticleTextureArray.bytesFor(4, 3));
        assertEquals(4, ParticleTextureArray.bytesFor(1, 1));
    }
}
