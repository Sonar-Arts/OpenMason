package com.openmason.main.systems.particles.mcp;

import com.openmason.engine.rendering.particles.FxPresets;
import com.openmason.engine.rendering.particles.FxSystem;
import com.openmason.engine.rendering.particles.ParticleEffect;
import com.openmason.engine.rendering.particles.omfx.OmfxDocument;
import com.openmason.engine.rendering.particles.omfx.OmfxSprite;
import com.openmason.main.systems.threading.MainThreadExecutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.glfw.GLFW.*;

/**
 * {@code fx_capture} through a real (hidden) GL context: the engine renderer draws the simulated system into the
 * capture target and the PNG shows it. Opt in with {@code -Dopenmason.particles.gl=true} (needs a display).
 */
class FxCaptureGlTest {

    private static long window;

    @BeforeAll
    static void context() {
        assumeTrue(Boolean.getBoolean("openmason.particles.gl"), "opt in with -Dopenmason.particles.gl=true");
        assertTrue(glfwInit(), "GLFW display unavailable");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        window = glfwCreateWindow(16, 16, "fx_capture test", 0, 0);
        assertNotEquals(0L, window, "no GL 3.3 core context");
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        MainThreadExecutor.bindToCurrentThread();
    }

    @AfterAll
    static void release() {
        if (window != 0) {
            glfwDestroyWindow(window);
            glfwTerminate();
        }
    }

    @Test
    void aCampfireCaptureShowsWarmFlamesOverTheFloor() throws Exception {
        FxCaptureService.Capture c = new FxCaptureService().capture(OmfxDocument.of(FxPresets.CAMPFIRE),
                FxSimulation.Options.of(1.5f), FxCaptureService.Camera.AUTO, 256, true);
        BufferedImage img = decode(c);
        assertEquals(256, img.getWidth());
        int warm = 0, background = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                if (r > 180 && r > b + 60 && g > 60) warm++;
                if (Math.abs(r - 18) < 6 && Math.abs(g - 20) < 6 && Math.abs(b - 28) < 6) background++;
            }
        }
        assertTrue(warm > 300, "fire pixels: " + warm);
        assertTrue(background > 1000, "the clear colour shows around the floor: " + background);
        assertTrue((int) c.report().get("live") > 0);
    }

    @Test
    void embeddedSpritesAreDrawnAndTheCallerCanPlaceTheCamera() throws Exception {
        BufferedImage swatch = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                swatch.setRGB(x, y, 0xFF00FF00); // opaque green
            }
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(swatch, "png", png);
        ParticleEffect blobs = ParticleEffect.builder("blobs").rate(200).lifetime(3, 3).sphere(0.6f).speed(0, 0)
                .size(0.5f, 0.5f).textures(List.of("green"), false).fade(0, 0).emissive(true).build();
        OmfxDocument doc = OmfxDocument.of(FxSystem.of(blobs, 1))
                .withSprite(new OmfxSprite("green", png.toByteArray()));
        FxCaptureService.Capture c = new FxCaptureService().capture(doc, FxSimulation.Options.of(1f),
                new FxCaptureService.Camera(0f, 0f, 4f, new double[]{0, 0, 0}), 128, false);
        BufferedImage img = decode(c);
        int green = 0, magenta = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                if (g > 150 && r < 80 && b < 80) green++;
                if (r > 150 && b > 150 && g < 80) magenta++;
            }
        }
        assertTrue(green > 500, "the embedded sprite colours the particles: " + green);
        assertEquals(0, magenta, "no missing-texture checker");
        assertNull(c.report().get("missingSprites"));
        assertEquals(4.0, ((java.util.Map<?, ?>) c.report().get("camera")).get("distance"));
    }

    private static BufferedImage decode(FxCaptureService.Capture c) throws Exception {
        assertEquals("image/png", c.image().mimeType());
        byte[] png = Base64.getDecoder().decode(c.image().base64Data());
        String out = System.getProperty("openmason.particles.capture.out"); // eyeball the captures
        if (out != null) {
            java.nio.file.Path dir = java.nio.file.Files.createDirectories(java.nio.file.Path.of(out));
            java.nio.file.Files.write(dir.resolve(c.report().get("id") + ".png"), png);
        }
        return ImageIO.read(new ByteArrayInputStream(png));
    }
}
