package com.openmason.main.systems.mcp;

import com.openmason.main.systems.services.EditorServices;
import com.openmason.main.systems.ViewportController;
import com.openmason.main.systems.threading.MainThreadExecutor;
import org.lwjgl.BufferUtils;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.lwjgl.opengl.GL11.GL_PACK_ALIGNMENT;
import static org.lwjgl.opengl.GL11.GL_RGB;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glGetInteger;
import static org.lwjgl.opengl.GL11.glGetTexImage;
import static org.lwjgl.opengl.GL11.glPixelStorei;

/**
 * Captures the 3D viewport's offscreen framebuffer as a downscaled PNG for the
 * MCP server, so an LLM can visually verify the model while editing it.
 *
 * <p>The GL readback runs on the main/GL thread via {@link MainThreadExecutor}
 * (same contract as the other MCP services); the captured frame is whatever the
 * viewport last rendered with its current camera. The image is downscaled to
 * {@code max_size} on its longest side before encoding, never upscaled — the
 * true source resolution is capped by the on-screen panel-sized FBO
 * ({@code ViewportMainView} resizes it to the panel each frame), so frames
 * larger than the panel would require an offscreen high-res render (deferred).
 */
public final class ViewportCaptureService {

    private static final long DEFAULT_TIMEOUT_MS = 10_000;

    /** Default longest-side size — full panel detail on most screens. */
    static final int DEFAULT_MAX_SIZE = 1024;
    private static final int MIN_SIZE = 64;
    private static final int MAX_SIZE = 2048;

    private final EditorServices editorServices;
    /** The tab previews by capture name ("animation", "animgraph"); null = Scene only. */
    private final java.util.function.Function<String, PreviewViewport> previews;

    public ViewportCaptureService(EditorServices editorServices) {
        this(editorServices, null);
    }

    public ViewportCaptureService(EditorServices editorServices,
                                  java.util.function.Function<String, PreviewViewport> previews) {
        this.editorServices = editorServices;
        this.previews = previews;
    }

    public McpImageContent capture(int maxSize) {
        return capture(maxSize, null);
    }

    /**
     * Captures {@code view}: "scene" (default; the Scene viewport's last frame) or a tab preview ("animation",
     * "animgraph"), which is rendered NOW at the size it was last shown at, so a hidden tab never returns a stale frame.
     */
    public McpImageContent capture(int maxSize, String view) {
        int target = Math.clamp(maxSize, MIN_SIZE, MAX_SIZE);
        BufferedImage frame = await(MainThreadExecutor.submit(() -> view == null || view.equals("scene")
                ? readFramebuffer() : readPreview(view)));
        BufferedImage scaled = McpImageCodec.downscale(frame, target);
        return new McpImageContent(McpImageCodec.encodePngBase64(scaled), "image/png");
    }

    /** A tab preview's frame, rendered now (GL thread). */
    private BufferedImage readPreview(String view) {
        PreviewViewport preview = previews == null ? null : previews.apply(view);
        if (preview == null) throw new IllegalArgumentException("unknown view '" + view + "' (scene, animation, animgraph)");
        int[] frame = preview.renderFrame(0, 0);
        if (frame == null) throw new IllegalStateException("nothing open in the " + view + " tab");
        return readTexture(frame[0], frame[1], frame[2]);
    }

    /** Read the viewport FBO color texture into a BufferedImage (must run on the GL thread). */
    private BufferedImage readFramebuffer() {
        ViewportController vp = editorServices.getViewport3D();
        if (vp == null || !vp.isInitialized()) {
            throw new IllegalStateException("Viewport not initialized");
        }
        return readTexture(vp.getColorTexture(), vp.getFramebufferWidth(), vp.getFramebufferHeight());
    }

    /** An RGBA8 color texture into a BufferedImage, rows flipped (GL thread). */
    static BufferedImage readTexture(int textureId, int width, int height) {
        if (textureId <= 0 || width <= 0 || height <= 0) {
            throw new IllegalStateException("Viewport framebuffer not ready — has a frame been rendered yet?");
        }

        int previousBinding = glGetInteger(GL_TEXTURE_BINDING_2D);
        int previousAlignment = glGetInteger(GL_PACK_ALIGNMENT);
        ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 3);
        try {
            glPixelStorei(GL_PACK_ALIGNMENT, 1);
            glBindTexture(GL_TEXTURE_2D, textureId);
            glGetTexImage(GL_TEXTURE_2D, 0, GL_RGB, GL_UNSIGNED_BYTE, pixels);
        } finally {
            glBindTexture(GL_TEXTURE_2D, previousBinding);
            glPixelStorei(GL_PACK_ALIGNMENT, previousAlignment);
        }

        // GL rows are bottom-up; flip while copying into the image.
        int[] argb = new int[width * height];
        for (int y = 0; y < height; y++) {
            int srcRow = (height - 1 - y) * width * 3;
            int dstRow = y * width;
            for (int x = 0; x < width; x++) {
                int o = srcRow + x * 3;
                argb[dstRow + x] = ((pixels.get(o) & 0xFF) << 16)
                        | ((pixels.get(o + 1) & 0xFF) << 8)
                        | (pixels.get(o + 2) & 0xFF);
            }
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, width, height, argb, 0, width);
        return image;
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("Viewport capture timed out on main thread", e);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        }
    }
}
