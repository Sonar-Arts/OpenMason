package com.openmason.main.systems.particles.mcp;

import com.openmason.engine.rendering.particles.ParticleFrame;
import com.openmason.engine.rendering.particles.ParticleRenderer;
import com.openmason.engine.rendering.particles.ParticleSpriteLayout;
import com.openmason.engine.rendering.particles.ParticleSystem;
import com.openmason.engine.rendering.particles.ParticleTextureArray;
import com.openmason.engine.rendering.particles.omfx.OmfxDocument;
import com.openmason.main.systems.mcp.McpImageCodec;
import com.openmason.main.systems.mcp.McpImageContent;
import com.openmason.main.systems.particles.PreviewGround;
import com.openmason.main.systems.threading.MainThreadExecutor;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Renders an FX document the way the engine draws it in a game: the system is simulated headlessly
 * ({@link FxSimulation}, same options as {@code fx_simulate}), then its particles at the end of the run are drawn by
 * the engine {@link ParticleRenderer} into an offscreen target over the Particle Editor's floor grid and read back
 * as a PNG. The camera frames the live particles unless the caller places it. Needs the editor's GL context (the
 * draw runs on the main thread); nothing on screen changes.
 */
public final class FxCaptureService {

    private static final long TIMEOUT_MS = 15_000;
    private static final float FOV = (float) Math.toRadians(55);
    static final int MIN_SIZE = 128, MAX_SIZE = 1024, DEFAULT_SIZE = 512;

    /**
     * Where the camera is: orbit angles in radians around {@code target} at {@code distance}; null members are
     * chosen to frame the particles (default angles 0.6 / 0.35, the editor preview's).
     */
    public record Camera(Float yaw, Float pitch, Float distance, double[] target) {
        public static final Camera AUTO = new Camera(null, null, null, null);
    }

    /** One capture: the image plus the run report and the camera actually used. */
    public record Capture(McpImageContent image, Map<String, Object> report) {
    }

    public Capture capture(OmfxDocument doc, FxSimulation.Options sim, Camera camera, int size, boolean ground) {
        int px = Math.clamp(size, MIN_SIZE, MAX_SIZE);
        FxSimulation.Run run = FxSimulation.run(doc, sim);
        ParticleSystem system = run.system();
        ParticleSpriteLayout sprites = ParticleSpriteLayout.pack(doc.effects(), doc.spriteLoader(null),
                ParticleSpriteLayout.DEFAULT_CELL_SIZE);

        float[] view = frameCamera(system, camera, sim.at());
        BufferedImage image = await(MainThreadExecutor.submit(() -> render(system, sprites, view, px, ground)));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("id", doc.id());
        report.put("t", sim.seconds());
        report.put("live", system.liveCount());
        report.put("camera", Map.of("yaw", round(view[0]), "pitch", round(view[1]), "distance", round(view[2]),
                "target", List.of(round(view[3]), round(view[4]), round(view[5]))));
        if (!sprites.missing().isEmpty()) {
            report.put("missingSprites", sprites.missing());
        }
        report.put("warnings", run.report().get("warnings"));
        return new Capture(new McpImageContent(McpImageCodec.encodePngBase64(image), "image/png"), report);
    }

    /** {yaw, pitch, distance, tx, ty, tz}: given values kept, the rest chosen to fit the live particles. */
    static float[] frameCamera(ParticleSystem system, Camera camera, double[] at) {
        int n = system.liveCount();
        double[] min = {at[0] - 0.5, at[1], at[2] - 0.5};
        double[] max = {at[0] + 0.5, at[1] + 1, at[2] + 0.5};
        if (n > 0) {
            min = new double[]{Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
            max = new double[]{-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
            for (int i = 0; i < n; i++) {
                double[] p = {system.worldX(i), system.worldY(i), system.worldZ(i)};
                for (int a = 0; a < 3; a++) {
                    min[a] = Math.min(min[a], p[a]);
                    max[a] = Math.max(max[a], p[a]);
                }
            }
        }
        double[] target = camera.target() != null ? camera.target()
                : new double[]{(min[0] + max[0]) / 2, (min[1] + max[1]) / 2, (min[2] + max[2]) / 2};
        double radius = 0.5;
        for (int a = 0; a < 3; a++) {
            radius = Math.max(radius, (max[a] - min[a]) / 2);
        }
        float distance = camera.distance() != null ? camera.distance()
                : (float) Math.min(200.0, radius / Math.tan(FOV / 2) * 1.35 + 0.5);
        return new float[]{camera.yaw() != null ? camera.yaw() : 0.6f, camera.pitch() != null ? camera.pitch() : 0.35f,
                distance, (float) target[0], (float) target[1], (float) target[2]};
    }

    /** GL thread: draws the system offscreen and reads it back; restores every binding it touches. */
    private static BufferedImage render(ParticleSystem system, ParticleSpriteLayout layout, float[] cam, int size,
                                        boolean drawGround) {
        // Any GL call without a current context aborts the JVM (LWJGL fatal error), so check first. GLFW answers
        // 0 when there is none (or GLFW was never initialised); GL.getCapabilities can still hold a stale object.
        if (org.lwjgl.glfw.GLFW.glfwGetCurrentContext() == 0L) {
            throw new IllegalStateException("fx_capture needs the editor's GL context (run Open Mason with its "
                    + "window); fx_simulate works headlessly");
        }
        int prevFbo = glGetInteger(GL_FRAMEBUFFER_BINDING);
        int prevTex = glGetInteger(GL_TEXTURE_BINDING_2D);
        int[] prevViewport = new int[4];
        glGetIntegerv(GL_VIEWPORT, prevViewport);
        float[] prevClear = new float[4];
        glGetFloatv(GL_COLOR_CLEAR_VALUE, prevClear);
        boolean prevScissor = glIsEnabled(GL_SCISSOR_TEST);
        boolean prevDepth = glIsEnabled(GL_DEPTH_TEST);
        int prevPack = glGetInteger(GL_PACK_ALIGNMENT);

        int color = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, color);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, size, size, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        int depth = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, depth);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, size, size);
        int fbo = glGenFramebuffers();
        ParticleRenderer renderer = null;
        ParticleTextureArray sprites = null;
        PreviewGround ground = null;
        try {
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, color, 0);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depth);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("could not create the capture framebuffer");
            }
            renderer = new ParticleRenderer();
            ParticleFrame frame = new ParticleFrame();
            if (layout.layerCount() > 0) {
                sprites = ParticleTextureArray.upload(layout);
                frame.sprites = sprites;
            }
            float yaw = cam[0], pitch = cam[1], distance = cam[2];
            float ex = (float) (Math.sin(yaw) * Math.cos(pitch)) * distance + cam[3];
            float ey = (float) Math.sin(pitch) * distance + cam[4];
            float ez = (float) (Math.cos(yaw) * Math.cos(pitch)) * distance + cam[5];
            Matrix4f view = new Matrix4f().setLookAt(ex, ey, ez, cam[3], cam[4], cam[5], 0f, 1f, 0f);
            Matrix4f projection = new Matrix4f().setPerspective(FOV, 1f, 0.05f, 400f);
            Matrix4f viewProjection = new Matrix4f(projection).mul(view);
            frame.camera(view, projection, ex, ey, ez);
            system.setViewer(ex, ey, ez);

            glViewport(0, 0, size, size);
            glDisable(GL_SCISSOR_TEST);
            float[] c = PreviewGround.CLEAR;
            glClearColor(c[0], c[1], c[2], c[3]);
            glClearDepth(1.0);
            glDepthMask(true);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_LESS);
            if (drawGround) {
                ground = new PreviewGround();
                ground.draw(viewProjection);
            }
            frame.depthTexture = 0;
            renderer.render(system, frame);

            ByteBuffer pixels = BufferUtils.createByteBuffer(size * size * 4);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo);
            glPixelStorei(GL_PACK_ALIGNMENT, 1);
            glReadPixels(0, 0, size, size, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            int[] argb = new int[size * size];
            for (int y = 0; y < size; y++) {
                int src = (size - 1 - y) * size * 4; // GL rows are bottom-up
                for (int x = 0; x < size; x++) {
                    int o = src + x * 4;
                    argb[y * size + x] = 0xFF000000 | ((pixels.get(o) & 0xFF) << 16)
                            | ((pixels.get(o + 1) & 0xFF) << 8) | (pixels.get(o + 2) & 0xFF);
                }
            }
            BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, size, size, argb, 0, size);
            return image;
        } finally {
            if (ground != null) ground.close();
            if (sprites != null) sprites.close();
            if (renderer != null) renderer.close();
            glBindFramebuffer(GL_FRAMEBUFFER, prevFbo);
            glDeleteFramebuffers(fbo);
            glDeleteRenderbuffers(depth);
            glDeleteTextures(color);
            glBindTexture(GL_TEXTURE_2D, prevTex);
            glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
            glClearColor(prevClear[0], prevClear[1], prevClear[2], prevClear[3]);
            glPixelStorei(GL_PACK_ALIGNMENT, prevPack);
            if (prevScissor) glEnable(GL_SCISSOR_TEST); else glDisable(GL_SCISSOR_TEST);
            if (prevDepth) glEnable(GL_DEPTH_TEST); else glDisable(GL_DEPTH_TEST);
        }
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IllegalStateException("fx_capture timed out waiting for the GL thread (is the editor window "
                    + "running?)", e);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) throw re;
            throw new IllegalStateException(cause.getMessage(), cause);
        }
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
