package com.openmason.main.systems.particles;

import com.openmason.engine.rendering.particles.FxHost;
import com.openmason.engine.rendering.particles.FxInstance;
import com.openmason.engine.rendering.particles.FxParameter;
import com.openmason.engine.rendering.particles.FxSystem;
import com.openmason.engine.rendering.particles.FxWorld;
import com.openmason.engine.rendering.particles.ParticleFrame;
import com.openmason.engine.rendering.particles.ParticleGpuBackend;
import com.openmason.engine.rendering.particles.ParticleRenderer;
import com.openmason.engine.rendering.particles.ParticleSpriteLayout;
import com.openmason.engine.rendering.particles.ParticleSystem;
import com.openmason.engine.rendering.particles.ParticleTextureArray;
import com.openmason.engine.rendering.particles.omfx.OmfxDocument;
import com.openmason.main.systems.particles.fx.FxHotSwap;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static org.lwjgl.opengl.GL33C.*;

/**
 * The FX editor's live preview (OM #104): the document's {@link FxSystem} played by a real {@link FxWorld} over a
 * {@link ParticleSystem} and drawn by the engine {@link ParticleRenderer} into an offscreen target over a grid
 * floor - the path a game uses, soft particles and floor collisions included.
 *
 * <p>Edits never restart the simulation: changed effects are hot-swapped ({@link ParticleSystem#replace}, planned
 * by {@link FxHotSwap}) and the running instance is retargeted to the new system ({@link FxInstance#retarget}),
 * keeping its clock and live particles. A system that ends (an explosion) replays after a short pause when
 * {@link #loop} is on.
 *
 * <p>Render-thread only. Saves and restores the framebuffer, viewport, scissor, depth test and clear colour around
 * its own drawing so it can be called in the middle of an ImGui frame.
 */
final class FxPreview implements AutoCloseable {

    private static final int BUDGET = 60_000;
    private static final float FOV = (float) Math.toRadians(55);
    private static final float REPLAY_PAUSE = 0.6f;

    private final ParticleRenderer renderer = new ParticleRenderer();
    /** Half extent (blocks) of the GPU height field standing in for the preview floor. */
    private static final int GROUND_HALF = 64;
    /** The floor state last given to the GPU backend (null = not yet). */
    private Boolean gpuGround;
    private final ParticleFrame frame = new ParticleFrame();
    private final Matrix4f view = new Matrix4f();
    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f viewProjection = new Matrix4f();
    private final Vector3f eye = new Vector3f();
    private final Vector3f target = new Vector3f(0f, 0.8f, 0f);

    private ParticleSystem particles;
    private FxWorld world;
    private FxInstance instance;
    private FxSystem shown;
    private float replayIn = -1f;
    private float autoFrameIn = -1f;
    /** Exposed parameter values the user set in the preview (kept across edits and replays). */
    private final Map<String, float[]> params = new LinkedHashMap<>();
    /** Recent host events and sounds, newest first. */
    private final Deque<String> log = new ArrayDeque<>();

    private ParticleTextureArray sprites;
    private List<String> spriteNames = List.of();
    private OmfxDocument spriteDoc;
    private Path spriteBase;

    private final int[] viewportScratch = new int[4];
    private final float[] clearScratch = new float[4];
    private int width, height;
    private int fbo, colorTex, depthRb, depthCopyFbo, depthCopyTex;
    private final PreviewGround ground = new PreviewGround();

    // ---- user controls ----
    boolean playing = true;
    boolean loop = true;
    float timeScale = 1f;
    boolean showGround = true;
    boolean softParticles = true;
    float wind;
    /** Where the system plays, above the floor. */
    float height0 = 0.05f;
    float yaw = 0.6f, pitch = 0.35f, distance = 5f;

    FxPreview() {
        rebuildWorld();
    }

    private void rebuildWorld() {
        if (world != null) {
            world.close();
        }
        particles = new ParticleSystem(BUDGET);
        // GPU effects (OM #100) simulate on the GPU by themselves here (this thread has the GL context).
        gpuGround = null;
        particles.setCollider((x, y, z) -> showGround && y < 0);
        if (sprites != null) {
            frame.sprites = sprites;
        }
        world = new FxWorld(particles);
        world.setHost(new FxHost() {
            @Override
            public void event(FxInstance inst, String name, double x, double y, double z) {
                note(String.format(Locale.ROOT, "%.2fs event %s", inst.time(), name));
            }

            @Override
            public void sound(FxInstance inst, String sound, double x, double y, double z, float volume) {
                note(String.format(Locale.ROOT, "%.2fs sound %s", inst.time(), sound));
            }
        });
        instance = null;
    }

    private void note(String line) {
        log.addFirst(line);
        while (log.size() > 12) {
            log.removeLast();
        }
    }

    /**
     * Plays {@code next}: the first time (or after a restart) from the start, otherwise live - changed effects are
     * swapped and the running instance follows the new system.
     */
    void show(FxSystem next) {
        Objects.requireNonNull(next, "next");
        if (next == shown && instance != null) {
            return;
        }
        if (instance == null || shown == null) {
            shown = next;
            start();
            autoFrameIn = 1.2f;
            return;
        }
        if (!next.name().equals(shown.name())) {
            shown = next;
            restart();
            autoFrameIn = 1.2f;
            return;
        }
        for (FxHotSwap.Swap s : FxHotSwap.plan(shown, next)) {
            particles.replace(s.old(), s.updated());
        }
        instance.retarget(next);
        shown = next;
        applyParams();
    }

    /** Clears every particle and plays the system from its start. */
    void restart() {
        rebuildWorld();
        start();
    }

    private void start() {
        replayIn = -1f;
        if (shown == null) {
            return;
        }
        instance = world.play(shown, 0, height0, 0);
        applyParams();
    }

    /** Sets a preview value of an exposed parameter (not saved: the document keeps the defaults). */
    void setParam(String name, float... values) {
        params.put(name, values.clone());
        applyParams();
    }

    /** The preview value of a parameter, or null when it runs at its default. */
    float[] param(String name) {
        return params.get(name);
    }

    /** Back to every parameter's default (replays the system). */
    void resetParams() {
        params.clear();
        restart();
    }

    private void applyParams() {
        if (instance == null || shown == null) {
            return;
        }
        for (FxParameter p : shown.parameters()) {
            float[] v = params.get(p.name());
            if (v == null) {
                continue;
            }
            switch (p.type()) {
                case FLOAT -> instance.setParam(p.name(), v[0]);
                case VECTOR -> instance.setParam(p.name(), v[0], v[1], v[2]);
                case COLOR -> instance.setParamColor(p.name(), ParticleEditorWidgets.pack(v));
            }
        }
    }

    /** Raises a custom event on the running instance; false when nothing handles it. */
    boolean raise(String event) {
        return instance != null && instance.raise(event);
    }

    /** Seconds the running instance has played. */
    double time() {
        return instance == null ? 0.0 : instance.time();
    }

    List<String> log() {
        return List.copyOf(log);
    }

    /**
     * Keeps the sprite array in step with the document: embedded sprites first, then image files next to the
     * document. Repacks only when the names effects use, the embedded sprites or the folder change (or
     * {@code reload}).
     */
    void useSprites(OmfxDocument doc, Path baseDir, boolean reload) {
        List<String> names = ParticleSpriteLayout.referencedTextures(doc.effects());
        boolean same = sprites != null && names.equals(spriteNames) && Objects.equals(baseDir, spriteBase)
                && spriteDoc != null && spriteDoc.sprites().equals(doc.sprites());
        if (!reload && same) {
            return;
        }
        ParticleTextureArray next = ParticleTextureArray.upload(ParticleSpriteLayout.packNames(names,
                doc.spriteLoader(new ParticleSpriteFiles(baseDir)), ParticleSpriteLayout.DEFAULT_CELL_SIZE));
        if (sprites != null) {
            sprites.close();
        }
        sprites = next;
        spriteNames = names;
        spriteBase = baseDir;
        spriteDoc = doc;
        frame.sprites = next;
    }

    /** Texture names the last {@link #useSprites} could not load (drawn as a checker). */
    List<String> missingSprites() {
        return sprites == null ? List.of() : sprites.layout().missing();
    }

    /** Points the camera at the live particles' bounds. */
    void frameParticles() {
        int n = particles.liveCount();
        if (n == 0) {
            return;
        }
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            double x = particles.worldX(i), y = particles.worldY(i), z = particles.worldZ(i);
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
        }
        target.set((float) (minX + maxX) / 2f, (float) (minY + maxY) / 2f, (float) (minZ + maxZ) / 2f);
        double dx = maxX - minX, dy = maxY - minY, dz = maxZ - minZ;
        float radius = (float) Math.max(0.3, Math.sqrt(dx * dx + dy * dy + dz * dz) / 2);
        distance = Math.max(1.2f, Math.min(60f, radius / (float) Math.tan(FOV / 2) * 1.25f));
    }

    void orbit(float dx, float dy) {
        yaw -= dx * 0.01f;
        pitch = Math.max(-1.45f, Math.min(1.45f, pitch + dy * 0.01f));
    }

    void zoom(float wheel) {
        distance = Math.max(0.6f, Math.min(60f, distance * (float) Math.pow(0.88, wheel)));
    }

    /** Simulates {@code dt} seconds and draws a {@code w x h} frame; returns the colour texture to show. */
    int render(int w, int h, float dt) {
        if (w < 8 || h < 8) {
            return colorTex;
        }
        ensureTarget(w, h);
        step(dt);

        eye.set((float) (Math.sin(yaw) * Math.cos(pitch)), (float) Math.sin(pitch), (float) (Math.cos(yaw) * Math.cos(pitch)))
                .mul(distance).add(target);
        view.setLookAt(eye.x, eye.y, eye.z, target.x, target.y, target.z, 0f, 1f, 0f);
        projection.setPerspective(FOV, w / (float) h, 0.05f, 400f);
        projection.mul(view, viewProjection);
        frame.camera(view, projection, eye.x, eye.y, eye.z);
        particles.setViewer(eye.x, eye.y, eye.z);

        int prevFbo = glGetInteger(GL_FRAMEBUFFER_BINDING);
        boolean prevScissor = glIsEnabled(GL_SCISSOR_TEST);
        boolean prevDepth = glIsEnabled(GL_DEPTH_TEST);
        int[] prevViewport = viewportScratch;
        float[] prevClear = clearScratch;
        glGetIntegerv(GL_VIEWPORT, prevViewport);
        glGetFloatv(GL_COLOR_CLEAR_VALUE, prevClear);
        try {
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glViewport(0, 0, width, height);
            glDisable(GL_SCISSOR_TEST);
            glClearColor(0.07f, 0.08f, 0.11f, 1f);
            glClearDepth(1.0);
            glDepthMask(true);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_LESS);
            if (showGround) {
                ground.draw(viewProjection);
            }
            frame.depthTexture = 0;
            if (softParticles) {
                glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo);
                glBindFramebuffer(GL_DRAW_FRAMEBUFFER, depthCopyFbo);
                glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_DEPTH_BUFFER_BIT, GL_NEAREST);
                glBindFramebuffer(GL_FRAMEBUFFER, fbo);
                frame.depthTexture = depthCopyTex;
            }
            renderer.render(particles, frame);
            syncGpuGround();
        } finally {
            glBindFramebuffer(GL_FRAMEBUFFER, prevFbo);
            glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
            glClearColor(prevClear[0], prevClear[1], prevClear[2], prevClear[3]);
            if (prevScissor) glEnable(GL_SCISSOR_TEST); else glDisable(GL_SCISSOR_TEST);
            if (prevDepth) glEnable(GL_DEPTH_TEST); else glDisable(GL_DEPTH_TEST);
        }
        return colorTex;
    }

    private void step(float dt) {
        if (!playing || shown == null) {
            return;
        }
        float scaled = Math.min(dt * timeScale, ParticleSystem.MAX_STEP);
        particles.setWind(wind, 0f, 0f);
        if (instance != null && instance.isAlive()) {
            world.update(scaled);
        } else if (loop) {
            if (replayIn < 0f) {
                replayIn = REPLAY_PAUSE;
            }
            replayIn -= scaled;
            world.update(scaled);
            if (replayIn <= 0f) {
                if (instance != null) {
                    instance.release();
                }
                start();
            }
        } else {
            world.update(scaled);
        }
        if (autoFrameIn > 0f) {
            autoFrameIn -= scaled;
            if (autoFrameIn <= 0f) {
                frameParticles();
            }
        }
    }

    // ---- stats ----

    int live() { return particles.liveCount(); }
    long dropped() { return particles.droppedTotal(); }
    /** The preview floor (y = 0 where shown) as the GPU backend's height field. */
    private void syncGpuGround() {
        ParticleGpuBackend gpu = renderer.gpu();
        if (gpu == null || Boolean.valueOf(showGround).equals(gpuGround)) {
            return;
        }
        if (showGround) {
            gpu.setHeightField(-GROUND_HALF, -GROUND_HALF, 1f, GROUND_HALF * 2, GROUND_HALF * 2,
                    new float[GROUND_HALF * 2 * GROUND_HALF * 2]);
        } else {
            gpu.clearHeightField();
        }
        gpuGround = showGround;
    }

    int drawn() { return renderer.lastDrawn(); }
    int drawCalls() { return renderer.lastDrawCalls(); }
    long gpuBytes() { return renderer.gpuBytes() + (sprites == null ? 0 : sprites.gpuBytes()); }
    long heapBytes() { return particles.memoryBytes(); }

    // ------------------------------------------------------------------------------------------------
    // GL resources
    // ------------------------------------------------------------------------------------------------

    private void ensureTarget(int w, int h) {
        if (w == width && h == height && fbo != 0) {
            return;
        }
        releaseTarget();
        width = w;
        height = h;
        int prevFbo = glGetInteger(GL_FRAMEBUFFER_BINDING);
        int prevTex = glGetInteger(GL_TEXTURE_BINDING_2D);
        colorTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, colorTex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        depthCopyTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, depthCopyTex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, w, h, 0, GL_DEPTH_COMPONENT, GL_UNSIGNED_INT, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glBindTexture(GL_TEXTURE_2D, prevTex);

        depthRb = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, depthRb);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, w, h);
        fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colorTex, 0);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depthRb);
        depthCopyFbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, depthCopyFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depthCopyTex, 0);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer none = stack.ints(GL_NONE);
            glDrawBuffers(none);
        }
        glReadBuffer(GL_NONE);
        glBindFramebuffer(GL_FRAMEBUFFER, prevFbo);
    }

    private void releaseTarget() {
        if (fbo != 0) glDeleteFramebuffers(fbo);
        if (depthCopyFbo != 0) glDeleteFramebuffers(depthCopyFbo);
        if (colorTex != 0) glDeleteTextures(colorTex);
        if (depthCopyTex != 0) glDeleteTextures(depthCopyTex);
        if (depthRb != 0) glDeleteRenderbuffers(depthRb);
        fbo = depthCopyFbo = colorTex = depthCopyTex = depthRb = 0;
    }

    @Override
    public void close() {
        releaseTarget();
        ground.close();
        renderer.close();
        if (world != null) {
            world.close();
            world = null;
        }
        if (sprites != null) {
            sprites.close();
            sprites = null;
        }
    }
}
