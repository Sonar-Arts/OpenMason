package com.openmason.main.systems.mcp;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PixelArtCaptureTest {

    private static final int RED = 0xFFFF0000;
    private static final int OUTLINE = 0xFF2A1A10;

    private static PixelArtCapture.Options plain(int maxSize) {
        return new PixelArtCapture.Options(maxSize, false, null);
    }

    private static BufferedImage sprite(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB); // all transparent
        img.setRGB(1, 1, RED);
        img.setRGB(2, 1, OUTLINE);
        return img;
    }

    @Test
    void smallSpritesAreFlooredToMinTexelPixelsEvenWhenAskedSmall() {
        // The agents asked for 512: a 32x32 sprite used to come back at 16 px/texel.
        BufferedImage out = PixelArtCapture.render(sprite(32, 32), plain(512));
        assertEquals(32 * PixelArtCapture.MIN_TEXEL_PX, out.getWidth());
        assertEquals(32 * PixelArtCapture.MIN_TEXEL_PX, out.getHeight());
    }

    @Test
    void floorIsCappedByTheSizeLimit() {
        // 64 * 40 = 2560 > 2048: the cap wins, factor 32.
        assertEquals(32, PixelArtCapture.factorFor(64, 64, plain(256)));
        assertEquals(2048, PixelArtCapture.render(sprite(64, 64), plain(256)).getWidth());
    }

    @Test
    void largerRequestsStillHonoured() {
        assertEquals(64, PixelArtCapture.factorFor(32, 32, plain(2048)));
    }

    @Test
    void oversizedCanvasesAreDownscaledToTheCap() {
        BufferedImage big = new BufferedImage(4096, 1024, BufferedImage.TYPE_INT_ARGB);
        BufferedImage out = PixelArtCapture.render(big, plain(1024));
        assertEquals(1024, out.getWidth());
        assertEquals(256, out.getHeight());
    }

    @Test
    void midSizeCanvasOverTheRequestKeepsTheRequestedCap() {
        BufferedImage img = new BufferedImage(1500, 750, BufferedImage.TYPE_INT_ARGB);
        assertEquals(1024, PixelArtCapture.render(img, plain(1024)).getWidth());
    }

    @Test
    void transparencyBecomesAHalfTexelCheckerNotBlack() {
        BufferedImage out = PixelArtCapture.render(sprite(16, 16), plain(64));
        int f = PixelArtCapture.factorFor(16, 16, plain(64));
        assertEquals(BufferedImage.TYPE_INT_RGB, out.getType(), "no alpha left for a preprocessor to drop");
        int a = out.getRGB(0, 0);
        int b = out.getRGB(f / 2, 0);
        assertEquals(PixelArtCapture.CHECKER_LIGHT, a);
        assertEquals(PixelArtCapture.CHECKER_DARK, b);
        assertNotEquals(0xFF000000, a);
        // A painted texel stays uniform across its whole cell.
        for (int dy = 0; dy < f; dy++) {
            for (int dx = 0; dx < f; dx++) {
                assertEquals(RED, out.getRGB(f + dx, f + dy));
            }
        }
        assertEquals(OUTLINE, out.getRGB(2 * f + f / 2, f + f / 2));
    }

    @Test
    void transparentBackgroundKeepsAlpha() {
        PixelArtCapture.Options o = new PixelArtCapture.Options(64, false,
                PixelArtCapture.Background.parse("transparent"));
        BufferedImage out = PixelArtCapture.render(sprite(16, 16), o);
        assertEquals(BufferedImage.TYPE_INT_ARGB, out.getType());
        assertEquals(0, out.getRGB(0, 0) >>> 24);
    }

    @Test
    void solidBackgroundAndHalfAlphaComposite() {
        BufferedImage img = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(1, 0, 0x80FF0000);
        PixelArtCapture.Options o = new PixelArtCapture.Options(64, false,
                PixelArtCapture.Background.parse("#0000ff"));
        BufferedImage out = PixelArtCapture.render(img, o);
        assertEquals(0xFF0000FF, out.getRGB(0, 0));
        int f = PixelArtCapture.factorFor(2, 2, o);
        assertEquals(0xFF80007F, out.getRGB(f, 0), "50% red over blue");
    }

    @Test
    void backgroundParsing() {
        assertTrue(PixelArtCapture.Background.parse(null) instanceof PixelArtCapture.Background.Checker);
        assertTrue(PixelArtCapture.Background.parse("CHECKER") instanceof PixelArtCapture.Background.Checker);
        assertEquals(new PixelArtCapture.Background.Solid(0x12ab34), PixelArtCapture.Background.parse("12AB34"));
        assertThrows(IllegalArgumentException.class, () -> PixelArtCapture.Background.parse("pink"));
    }

    @Test
    void gridAddsRulersAndKeepsTexelsAddressable() {
        PixelArtCapture.Options o = new PixelArtCapture.Options(512, true, null);
        BufferedImage out = PixelArtCapture.render(sprite(32, 32), o);
        int f = PixelArtCapture.factorFor(32, 32, o);
        int m = PixelArtCapture.RULER_PX;
        assertEquals(m + 32 * f, out.getWidth());
        assertTrue(out.getWidth() <= PixelArtCapture.MAX_SIZE);
        // Texel (1,1) centre is still the painted colour, offset by the ruler band.
        assertEquals(RED, out.getRGB(m + f + f / 2, m + f + f / 2));
        // Major line at texel 8: differs from the checker either side of it.
        int y = m + 20 * f + f / 4;
        assertNotEquals(out.getRGB(m + 8 * f - 3, y), out.getRGB(m + 8 * f, y));
        // The ruler band has label pixels in it (some non-background pixel).
        boolean text = false;
        for (int x = m; x < m + 2 * f && !text; x++) {
            for (int yy = 0; yy < m && !text; yy++) text = (out.getRGB(x, yy) & 0xFFFFFF) != 0x1E1E24;
        }
        assertTrue(text, "x ruler labels drawn");
    }

    @Test
    void labelStepAvoidsOverlap() {
        assertEquals(1, PixelArtCapture.labelStep(40, 22, 16));
        assertEquals(2, PixelArtCapture.labelStep(12, 22, 16));
        assertEquals(8, PixelArtCapture.labelStep(4, 30, 16));
    }
}
