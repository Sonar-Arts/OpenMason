package com.openmason.main.systems.particles;

import com.openmason.engine.rendering.particles.ParticleEffect;
import com.openmason.engine.rendering.particles.ParticleEmitter;
import com.openmason.engine.rendering.particles.ParticleFrame;
import com.openmason.engine.rendering.particles.ParticleRenderer;
import com.openmason.engine.rendering.particles.ParticleSystem;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.List;

import static org.lwjgl.opengl.GL33C.*;

/**
 * The Particle Editor's live preview: one effect simulated by a real {@link ParticleSystem} and drawn by the
 * engine {@link ParticleRenderer} into an offscreen target over a grid floor — the same path a game uses,
 * including soft particles (the floor's depth is copied for them) and floor collisions. Edits hot-swap the
 * running effect ({@link ParticleSystem#replace}) so tweaking a value never restarts the simulation.
 *
 * <p>Render-thread only. Saves and restores the framebuffer, viewport, scissor, depth test and clear colour
 * around its own drawing so it can be called in the middle of an ImGui frame.
 */
final class ParticlePreview implements AutoCloseable {

    /** How the preview spawns the effect. */
    enum Mode { CONTINUOUS, BURST }

    private static final int BUDGET = 60_000;
    private static final float FOV = (float) Math.toRadians(55);

    private final ParticleRenderer renderer = new ParticleRenderer();
    private final ParticleFrame frame = new ParticleFrame();
    private final Matrix4f view = new Matrix4f();
    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f viewProjection = new Matrix4f();
    private final Vector3f eye = new Vector3f();
    private final Vector3f target = new Vector3f(0f, 0.8f, 0f);

    private ParticleSystem system = new ParticleSystem(BUDGET);
    private ParticleEffect effect;
    private ParticleEmitter emitter;
    private float burstClock;
    /** Seconds until the camera frames the effect after a selection (it needs time to develop); <0 = done. */
    private float autoFrameIn = -1f;

    private final int[] viewportScratch = new int[4];
    private final float[] clearScratch = new float[4];
    private int width, height;
    private int fbo, colorTex, depthRb, depthCopyFbo, depthCopyTex;
    private final int groundProgram, groundVao, groundViewProj;

    // ---- user controls ----
    boolean playing = true;
    float timeScale = 1f;
    Mode mode = Mode.CONTINUOUS;
    int burstCount = 60;
    float burstInterval = 1.5f;
    boolean showGround = true;
    boolean softParticles = true;
    float wind;
    /** Emitter height above the floor. */
    float emitterHeight = 0.05f;
    float yaw = 0.6f, pitch = 0.35f, distance = 4.5f;

    ParticlePreview() {
        groundProgram = compileGround();
        groundViewProj = glGetUniformLocation(groundProgram, "u_viewProj");
        groundVao = glGenVertexArrays();
    }

    /**
     * Shows {@code next} from scratch (selection changed). Burst-only effects (rate 0: sparks, debris,
     * fireworks) switch the preview to a burst loop — a continuous emitter would show nothing.
     */
    void show(ParticleEffect next) {
        if (next == effect) {
            return;
        }
        effect = next;
        if (next != null) {
            mode = next.rate() > 0f ? Mode.CONTINUOUS : Mode.BURST;
            // Launchers (bursts that spawn more through sub-emitters, e.g. fireworks) go one at a time.
            burstCount = next.subEmitters().isEmpty() ? 60 : 1;
            // Falling effects (rain, snow) start above the floor.
            emitterHeight = next.dirY() < -0.5f ? 6f : 0.05f;
        }
        restart();
        autoFrameIn = 1.2f;
    }

    /** Points the camera at the live particles' bounds so the whole effect is in view. */
    void frameParticles() {
        int n = system.liveCount();
        if (n == 0) {
            return;
        }
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            double x = system.worldX(i), y = system.worldY(i), z = system.worldZ(i);
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
        }
        target.set((float) (minX + maxX) / 2f, (float) (minY + maxY) / 2f, (float) (minZ + maxZ) / 2f);
        double dx = maxX - minX, dy = maxY - minY, dz = maxZ - minZ;
        float radius = (float) Math.max(0.3, Math.sqrt(dx * dx + dy * dy + dz * dz) / 2);
        distance = Math.max(1.2f, Math.min(60f, radius / (float) Math.tan(FOV / 2) * 1.25f));
    }

    /** Applies editor swaps to the running simulation without restarting it. */
    void hotSwap(List<ParticleEditorModel.Swap> swaps) {
        for (ParticleEditorModel.Swap s : swaps) {
            system.replace(s.old(), s.updated());
            if (s.old() == effect) {
                effect = s.updated();
            }
        }
    }

    /** Clears everything and starts the current effect again. */
    void restart() {
        system = new ParticleSystem(BUDGET);
        system.setCollider((x, y, z) -> showGround && y < 0);
        emitter = null;
        burstClock = 0f;
        if (effect != null && mode == Mode.CONTINUOUS) {
            emitter = system.createEmitter(effect, 0, emitterHeight, 0);
        }
    }

    void burstNow() {
        if (effect != null) {
            system.burst(effect, 0, emitterHeight, 0, burstCount);
        }
    }

    void orbit(float dx, float dy) {
        yaw -= dx * 0.01f;
        pitch = Math.max(-1.45f, Math.min(1.45f, pitch + dy * 0.01f));
    }

    void zoom(float wheel) {
        distance = Math.max(0.6f, Math.min(60f, distance * (float) Math.pow(0.88, wheel)));
    }

    /** Simulates {@code dt} seconds and draws a {@code w × h} frame; returns the colour texture to show. */
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
        system.setViewer(eye.x, eye.y, eye.z);

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
                drawGround();
            }
            frame.depthTexture = 0;
            if (softParticles) {
                glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo);
                glBindFramebuffer(GL_DRAW_FRAMEBUFFER, depthCopyFbo);
                glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_DEPTH_BUFFER_BIT, GL_NEAREST);
                glBindFramebuffer(GL_FRAMEBUFFER, fbo);
                frame.depthTexture = depthCopyTex;
            }
            renderer.render(system, frame);
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
        if (!playing || effect == null) {
            return;
        }
        float scaled = Math.min(dt * timeScale, ParticleSystem.MAX_STEP);
        system.setWind(wind, 0f, 0f);
        if (mode == Mode.BURST) {
            burstClock -= scaled;
            if (burstClock <= 0f) {
                burstNow();
                burstClock = Math.max(0.05f, burstInterval);
            }
        } else if (emitter == null) {
            restart();
        }
        system.update(scaled);
        if (autoFrameIn > 0f) {
            autoFrameIn -= scaled;
            if (autoFrameIn <= 0f) {
                frameParticles();
            }
        }
    }

    // ---- stats ----

    int live() { return system.liveCount(); }
    long dropped() { return system.droppedTotal(); }
    int drawn() { return renderer.lastDrawn(); }
    int drawCalls() { return renderer.lastDrawCalls(); }
    long gpuBytes() { return renderer.gpuBytes(); }
    long heapBytes() { return system.memoryBytes(); }

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

    private void drawGround() {
        int prevProgram = glGetInteger(GL_CURRENT_PROGRAM);
        int prevVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        boolean prevBlend = glIsEnabled(GL_BLEND);
        boolean prevCull = glIsEnabled(GL_CULL_FACE);
        glDisable(GL_BLEND);
        glDisable(GL_CULL_FACE);
        glUseProgram(groundProgram);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            glUniformMatrix4fv(groundViewProj, false, viewProjection.get(stack.mallocFloat(16)));
        }
        glBindVertexArray(groundVao);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glBindVertexArray(prevVao);
        glUseProgram(prevProgram);
        if (prevBlend) glEnable(GL_BLEND);
        if (prevCull) glEnable(GL_CULL_FACE);
    }

    private static int compileGround() {
        String vs = """
                #version 330 core
                uniform mat4 u_viewProj;
                out vec2 v_xz;
                void main() {
                    vec2 c = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1)) * 40.0 - 20.0;
                    v_xz = c;
                    gl_Position = u_viewProj * vec4(c.x, 0.0, c.y, 1.0);
                }
                """;
        String fs = """
                #version 330 core
                in vec2 v_xz;
                out vec4 o;
                void main() {
                    vec2 g = abs(fract(v_xz) - 0.5);
                    float line = step(0.47, max(g.x, g.y));
                    float fade = clamp(1.0 - length(v_xz) / 20.0, 0.0, 1.0);
                    vec3 base = vec3(0.16, 0.18, 0.15);
                    o = vec4(mix(vec3(0.07, 0.08, 0.11), base * (1.0 + 0.35 * line), fade), 1.0);
                }
                """;
        int program = glCreateProgram();
        int v = shader(GL_VERTEX_SHADER, vs), f = shader(GL_FRAGMENT_SHADER, fs);
        glAttachShader(program, v);
        glAttachShader(program, f);
        glLinkProgram(program);
        glDeleteShader(v);
        glDeleteShader(f);
        if (glGetProgrami(program, GL_LINK_STATUS) != GL_TRUE) {
            throw new IllegalStateException("particle preview floor: " + glGetProgramInfoLog(program));
        }
        return program;
    }

    private static int shader(int type, String src) {
        int s = glCreateShader(type);
        glShaderSource(s, src);
        glCompileShader(s);
        if (glGetShaderi(s, GL_COMPILE_STATUS) != GL_TRUE) {
            throw new IllegalStateException("particle preview floor: " + glGetShaderInfoLog(s));
        }
        return s;
    }

    @Override
    public void close() {
        releaseTarget();
        glDeleteProgram(groundProgram);
        glDeleteVertexArrays(groundVao);
        renderer.close();
    }
}
