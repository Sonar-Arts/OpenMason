package com.openmason.engine.rendering.particles;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43C.*;

/**
 * OM #100 acceptance benchmark: one million GPU particles simulated, culled and drawn within a frame budget on the
 * reference GPU, with no CPU work per particle. Opt in with {@code -Dopenmason.particles.gl=true
 * -Dopenmason.particles.bench=true}; the budget is {@code -Dopenmason.particles.gpuBudgetMs} (default 8 ms,
 * measured with GL timer queries over a 1280×720 target).
 *
 * <ul>
 *   <li>GPU: simulate + pack + draw of 1M additive particles (no sort), median over 60 frames, within budget;
 *       the alpha-blended (sorted) variant is measured and reported too.</li>
 *   <li>CPU: the frame's submission cost at 1M particles is within a small factor of the cost at 1k — it does not
 *       grow with the particle count.</li>
 * </ul>
 */
class ParticleGpuBenchmarkGlTest {

    private static final int W = 1280, H = 720;
    private static final int MILLION = 1 << 20;

    @Test
    void aMillionGpuParticlesFitTheFrameBudget() {
        assumeTrue(Boolean.getBoolean("openmason.particles.gl") && Boolean.getBoolean("openmason.particles.bench"),
                "opt in with -Dopenmason.particles.gl=true -Dopenmason.particles.bench=true");
        float budgetMs = Float.parseFloat(System.getProperty("openmason.particles.gpuBudgetMs", "8"));
        assertTrue(glfwInit());
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        long window = glfwCreateWindow(16, 16, "GPU particle benchmark", 0, 0);
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        int fbo = glGenFramebuffers(), color = glGenTextures(), depth = glGenRenderbuffers();
        try {
            assumeTrue(ParticleGpuBackend.isSupported(), "needs OpenGL 4.3");
            glBindTexture(GL_TEXTURE_2D, color);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, W, H, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            glBindRenderbuffer(GL_RENDERBUFFER, depth);
            glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, W, H);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, color, 0);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depth);
            glViewport(0, 0, W, H);
            glEnable(GL_DEPTH_TEST);

            Result additive = run(MILLION, 0f);
            Result cpuSmall = run(1024, 0f);
            Result sorted = run(MILLION, 0.5f);
            String report = String.format(
                    "1M additive: GPU %.2f ms, CPU submit %.3f ms, alive %d | 1M alpha (sorted): GPU %.2f ms |"
                            + " 1k additive: CPU submit %.3f ms (budget %.1f ms)",
                    additive.gpuMs, additive.cpuMs, additive.alive, sorted.gpuMs, cpuSmall.cpuMs, budgetMs);
            System.out.println("[particles-gpu-bench] " + report);
            assertTrue(additive.alive > MILLION * 0.95, "a million particles live: " + additive.alive);
            assertTrue(additive.gpuMs <= budgetMs, report);
            assertTrue(additive.cpuMs <= Math.max(0.5, cpuSmall.cpuMs * 4), "CPU cost must not grow with N: " + report);
        } finally {
            glDeleteFramebuffers(fbo);
            glDeleteTextures(color);
            glDeleteRenderbuffers(depth);
            GL.setCapabilities(null);
            glfwDestroyWindow(window);
            glfwTerminate();
        }
    }

    private record Result(double gpuMs, double cpuMs, int alive) {
    }

    /** {@code n} long-lived, slowly drifting particles in a box in front of the camera; median of 60 frames. */
    private static Result run(int n, float additive) {
        ParticleEffect field = ParticleEffect.builder("field-" + n + "-" + additive)
                .simulation(ParticleEffect.Simulation.GPU).maxLive(n).rate(0)
                .lifetime(30f, 30f).box(20f, 10f, 20f).speed(0f, 0.3f).spread(180).gravity(0, -0.05f, 0)
                .drag(0.1f).size(0.03f, 0.03f).colors(0xFFC08040, 0xFFC08040).fade(0f, 0f)
                .additive(1f - additive).sprite(ParticleEffect.Sprite.SQUARE).build();
        ParticleSystem s = new ParticleSystem(1024);
        s.enableGpu();
        ParticleRenderer renderer = new ParticleRenderer();
        ParticleFrame frame = new ParticleFrame();
        frame.camera(new Matrix4f().lookAt(0, 2, 30, 0, 0, 0, 0, 1, 0),
                new Matrix4f().perspective((float) Math.toRadians(70), W / (float) H, 0.1f, 200f), 0, 2, 30);
        int query = glGenQueries();
        try {
            s.burst(field, 0, 0, 0, n);
            int frames = 80, warm = 20;
            double[] gpu = new double[frames - warm];
            double[] cpu = new double[frames - warm];
            for (int f = 0; f < frames; f++) {
                glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                glBeginQuery(GL_TIME_ELAPSED, query);
                long t0 = System.nanoTime();
                s.update(1f / 60f);
                renderer.render(s, frame);
                long t1 = System.nanoTime();
                glEndQuery(GL_TIME_ELAPSED);
                long ns = glGetQueryObjecti64(query, GL_QUERY_RESULT);
                if (f >= warm) {
                    gpu[f - warm] = ns / 1e6;
                    cpu[f - warm] = (t1 - t0) / 1e6;
                }
            }
            assertEquals(GL_NO_ERROR, glGetError());
            return new Result(median(gpu), median(cpu), renderer.gpu().lastAlive());
        } finally {
            glDeleteQueries(query);
            renderer.close();
        }
    }

    private static double median(double[] v) {
        double[] c = v.clone();
        Arrays.sort(c);
        return c[c.length / 2];
    }
}
