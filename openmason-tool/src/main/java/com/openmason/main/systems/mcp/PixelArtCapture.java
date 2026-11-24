package com.openmason.main.systems.mcp;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Locale;

/**
 * Renders a small pixel-art canvas into an image a vision model can actually read.
 *
 * <p>Three problems with a plain upscale, measured on agent sessions (2026-11-24):
 * <ul>
 *   <li><b>Too small.</b> Agents asked for 256-512 px, so a 32x32 sprite got 8-16 screen pixels per
 *       texel - less than one vision patch (typically 14-16 px, often merged 2x2 or 3x3). Captures are
 *       now floored at {@link #MIN_TEXEL_PX} screen pixels per texel (capped by the size limit).</li>
 *   <li><b>Transparency reads as black.</b> Vision preprocessors drop alpha, and (0,0,0,0) becomes
 *       black - a sprite's dark outline vanishes into it. Transparent texels are composited onto a
 *       grey checkerboard whose cells are HALF a texel, so they can never be mistaken for a painted
 *       (uniform) grey texel.</li>
 *   <li><b>No coordinates.</b> A model that sees a wrong pixel cannot name it. {@code grid} adds
 *       texel lines, a stronger line every 8 texels and x/y rulers.</li>
 * </ul>
 * Pure (no editor state, no GL) so it is unit-testable.
 */
public final class PixelArtCapture {

    /** Floor for screen pixels per texel on small canvases (about 3 vision patches across). */
    public static final int MIN_TEXEL_PX = 40;
    static final int MIN_SIZE = 64;
    static final int MAX_SIZE = 2048;
    /** Ruler band width/height when {@code grid} is on. */
    static final int RULER_PX = 44;
    /** Below this many screen pixels per texel, texel lines would cover the art: rulers only. */
    static final int MIN_GRID_LINE_FACTOR = 6;

    static final int CHECKER_LIGHT = 0xFFC4C4C4;
    static final int CHECKER_DARK = 0xFF9A9A9A;
    private static final int RULER_BG = 0xFF1E1E24;
    private static final int RULER_TEXT = 0xFFF0F0F0;
    private static final int MINOR_LINE = 0x55000000;
    private static final int MAJOR_LINE = 0xD000B4FF;

    /** How transparent texels are shown. */
    public sealed interface Background {
        /** Keep alpha (the old behaviour; most vision models then see black). */
        record Transparent() implements Background {}
        /** Half-texel grey checkerboard (default). */
        record Checker() implements Background {}
        /** Composite onto one opaque colour. */
        record Solid(int rgb) implements Background {}

        /** Parses {@code "checker"}, {@code "transparent"} or {@code "#rrggbb"}; null = checker. */
        static Background parse(String value) {
            if (value == null || value.isBlank()) return new Checker();
            String v = value.trim().toLowerCase(Locale.ROOT);
            switch (v) {
                case "checker": return new Checker();
                case "transparent": case "none": return new Transparent();
                default:
                    String hex = v.startsWith("#") ? v.substring(1) : v;
                    if (hex.matches("[0-9a-f]{6}")) return new Solid(Integer.parseInt(hex, 16));
                    throw new IllegalArgumentException("background must be 'checker', 'transparent' or "
                            + "'#rrggbb', got '" + value + "'");
            }
        }
    }

    /**
     * @param maxSize    requested longest side (clamped to [64, 2048], then floored so small canvases
     *                   get at least {@link #MIN_TEXEL_PX} px per texel where the cap allows)
     * @param grid       draw texel lines + rulers
     * @param background how transparent texels are shown
     */
    public record Options(int maxSize, boolean grid, Background background) {
        public Options {
            if (background == null) background = new Background.Checker();
        }
    }

    private PixelArtCapture() {
    }

    /** Whole screen-pixels per texel the capture will use for a {@code w x h} canvas (0 = downscaled). */
    static int factorFor(int w, int h, Options o) {
        int longest = Math.max(w, h);
        int target = Math.clamp(o.maxSize(), MIN_SIZE, MAX_SIZE);
        if (longest <= target) {
            // Only small canvases are raised; a big imported texture keeps the requested cap.
            target = Math.max(target, Math.min(MAX_SIZE, longest * MIN_TEXEL_PX));
        }
        int room = target - (o.grid() ? RULER_PX : 0);
        return room / longest;
    }

    public static BufferedImage render(BufferedImage canvas, Options o) {
        int w = canvas.getWidth();
        int h = canvas.getHeight();
        int factor = factorFor(w, h, o);
        if (factor < 1) {
            // Larger than the cap (an imported texture): area-average down, no grid.
            BufferedImage flat = composite(canvas, 1, o.background());
            return McpImageCodec.downscale(flat, Math.clamp(o.maxSize(), MIN_SIZE, MAX_SIZE));
        }
        BufferedImage art = composite(canvas, factor, o.background());
        if (!o.grid()) return art;
        return withGrid(art, w, h, factor);
    }

    /** Nearest-neighbour upscale with transparency resolved against the background. */
    static BufferedImage composite(BufferedImage canvas, int factor, Background bg) {
        int w = canvas.getWidth();
        int h = canvas.getHeight();
        boolean keepAlpha = bg instanceof Background.Transparent;
        BufferedImage out = new BufferedImage(w * factor, h * factor,
                keepAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        int cell = Math.max(1, factor / 2);
        int[] row = new int[w * factor];
        for (int ty = 0; ty < h; ty++) {
            for (int dy = 0; dy < factor; dy++) {
                int py = ty * factor + dy;
                for (int tx = 0; tx < w; tx++) {
                    int argb = canvas.getRGB(tx, ty);
                    for (int dx = 0; dx < factor; dx++) {
                        int px = tx * factor + dx;
                        row[px] = keepAlpha ? argb : over(argb, backgroundAt(bg, px, py, cell));
                    }
                }
                out.setRGB(0, py, row.length, 1, row, 0, row.length);
            }
        }
        return out;
    }

    private static int backgroundAt(Background bg, int px, int py, int cell) {
        if (bg instanceof Background.Solid s) return 0xFF000000 | s.rgb();
        return ((px / cell + py / cell) & 1) == 0 ? CHECKER_LIGHT : CHECKER_DARK;
    }

    /** Source-over of {@code argb} on an opaque {@code dst}. */
    static int over(int argb, int dst) {
        int a = argb >>> 24;
        if (a == 255) return argb;
        if (a == 0) return dst;
        int r = mix((argb >> 16) & 0xFF, (dst >> 16) & 0xFF, a);
        int g = mix((argb >> 8) & 0xFF, (dst >> 8) & 0xFF, a);
        int b = mix(argb & 0xFF, dst & 0xFF, a);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int mix(int s, int d, int a) {
        return (s * a + d * (255 - a) + 127) / 255;
    }

    /** Places the art below/right of x/y rulers and draws texel lines over it. */
    private static BufferedImage withGrid(BufferedImage art, int w, int h, int factor) {
        int m = RULER_PX;
        BufferedImage out = new BufferedImage(art.getWidth() + m, art.getHeight() + m, art.getType());
        Graphics2D g = out.createGraphics();
        try {
            g.setColor(new Color(RULER_BG, true));
            g.fillRect(0, 0, out.getWidth(), out.getHeight());
            g.drawImage(art, m, m, null);

            if (factor >= MIN_GRID_LINE_FACTOR) {
                g.setColor(new Color(MINOR_LINE, true));
                for (int x = 1; x < w; x++) if (x % 8 != 0) g.fillRect(m + x * factor, m, 1, h * factor);
                for (int y = 1; y < h; y++) if (y % 8 != 0) g.fillRect(m, m + y * factor, w * factor, 1);
            }
            g.setColor(new Color(MAJOR_LINE, true));
            for (int x = 8; x < w; x += 8) g.fillRect(m + x * factor - 1, m, 2, h * factor);
            for (int y = 8; y < h; y += 8) g.fillRect(m, m + y * factor - 1, w * factor, 2);

            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int fontPx = Math.clamp(Math.round(factor * 0.5f), 10, 18);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fontPx));
            FontMetrics fm = g.getFontMetrics();
            int step = labelStep(factor, fm.stringWidth(String.valueOf(Math.max(w, h) - 1)) + 6,
                    fm.getAscent() + 4);
            g.setColor(new Color(RULER_TEXT, true));
            for (int x = 0; x < w; x += step) {
                String s = String.valueOf(x);
                int cx = m + x * factor + factor / 2;
                g.drawString(s, cx - fm.stringWidth(s) / 2, (m + fm.getAscent()) / 2);
            }
            for (int y = 0; y < h; y += step) {
                String s = String.valueOf(y);
                int cy = m + y * factor + factor / 2;
                g.drawString(s, (m - fm.stringWidth(s)) / 2, cy + fm.getAscent() / 2 - 1);
            }
        } finally {
            g.dispose();
        }
        return out;
    }

    /** Smallest of 1/2/4/8/16/... texels between labels such that labels do not overlap. */
    static int labelStep(int factor, int labelWidth, int labelHeight) {
        int need = Math.max(labelWidth, labelHeight);
        int step = 1;
        while (step * factor < need) step *= 2;
        return step;
    }
}
