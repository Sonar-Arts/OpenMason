package com.openmason.engine.rendering.particles;

import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43C.*;

/**
 * The GPU backend (OM #100) on a real GL 4.3 context, rendered offscreen and read back. Opt in with
 * {@code -Dopenmason.particles.gl=true}; images of the CPU/GPU comparisons land in {@code target/particle-gpu/}.
 *
 * <p>Scene: camera at the origin looking down −Z, 90° FOV, square {@value #SIZE}² viewport: a particle of size 1 at
 * {@code z = −2} covers NDC ±0.25 around the centre.
 */
class ParticleGpuGlTest {

    private static final int SIZE = 128;
    private static final int C = SIZE / 2;
    private static final float DT = 1f / 60f;

    private long window;
    private int fbo, colorTex, depthRb;
    private ParticleRenderer renderer;
    private final ParticleFrame frame = new ParticleFrame();
    private final Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(90), 1f, 0.1f, 100f);

    @BeforeEach
    void createContext() {
        assumeTrue(Boolean.getBoolean("openmason.particles.gl"), "opt in with -Dopenmason.particles.gl=true");
        assertTrue(glfwInit(), "GLFW display unavailable");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        window = glfwCreateWindow(16, 16, "GPU particle test", 0, 0);
        assertNotEquals(0, window);
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        assumeTrue(ParticleGpuBackend.isSupported(), "needs OpenGL 4.3");

        colorTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, colorTex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, SIZE, SIZE, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        glBindTexture(GL_TEXTURE_2D, 0);
        depthRb = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, depthRb);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, SIZE, SIZE);
        fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colorTex, 0);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depthRb);
        assertEquals(GL_FRAMEBUFFER_COMPLETE, glCheckFramebufferStatus(GL_FRAMEBUFFER));
        glViewport(0, 0, SIZE, SIZE);
        glEnable(GL_DEPTH_TEST);
        renderer = new ParticleRenderer();
        frame.camera(new Matrix4f(), projection, 0, 0, 0);
        assertEquals(GL_NO_ERROR, glGetError());
    }

    @AfterEach
    void destroyContext() {
        if (window == 0) {
            return;
        }
        if (renderer != null) {
            renderer.close();
        }
        glDeleteFramebuffers(fbo);
        glDeleteRenderbuffers(depthRb);
        glDeleteTextures(colorTex);
        int error = glGetError();
        GL.setCapabilities(null);
        glfwDestroyWindow(window);
        glfwTerminate();
        assertEquals(GL_NO_ERROR, error, "GL error during the test");
    }

    // ------------------------------------------------------------------------------------------------

    private static ParticleEffect.Builder square(String name, int rgba) {
        return ParticleEffect.builder(name).lifetime(10, 10).speed(0, 0).spread(0).rate(0).fade(0, 0).size(1, 1)
                .sprite(ParticleEffect.Sprite.SQUARE).color(rgba);
    }

    private static ParticleEffect gpu(ParticleEffect e) {
        return e.toBuilder().simulation(ParticleEffect.Simulation.GPU).build();
    }

    private static ParticleSystem gpuSystem() {
        ParticleSystem s = new ParticleSystem(1 << 16);
        s.enableGpu();
        return s;
    }

    /** One frame: simulate (CPU part), then prepare (GPU step) and draw everything. */
    private void frame(ParticleSystem s) {
        clear();
        s.update(DT);
        renderer.render(s, frame);
        assertEquals(GL_NO_ERROR, glGetError());
    }

    /** A frame boundary: a real frame presents, here the GPU catches up (readbacks are a frame or two behind). */
    private static void present() {
        glFinish();
    }

    private void clear() {
        glClearColor(0f, 0f, 0f, 1f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    }

    private int[] image() {
        ByteBuffer px = MemoryUtil.memAlloc(SIZE * SIZE * 4);
        try {
            glFinish();
            glReadPixels(0, 0, SIZE, SIZE, GL_RGBA, GL_UNSIGNED_BYTE, px);
            int[] out = new int[SIZE * SIZE];
            for (int i = 0; i < out.length; i++) {
                out[i] = px.getInt(i * 4);
            }
            return out;
        } finally {
            MemoryUtil.memFree(px);
        }
    }

    private static int r(int px) { return px & 0xFF; }
    private static int g(int px) { return (px >>> 8) & 0xFF; }
    private static int b(int px) { return (px >>> 16) & 0xFF; }

    private static int at(int[] img, int x, int y) {
        return img[y * SIZE + x];
    }

    // ------------------------------------------------------------------------------------------------

    @Test
    void aDeterministicParticleDrawsExactlyWhereTheCpuOneDoes() {
        ParticleEffect cpuFx = square("still", 0xFF8040FF).build();
        ParticleSystem cpu = new ParticleSystem(64);
        cpu.burst(cpuFx, 0.3, -0.2, -2, 1);
        frame(cpu);
        int[] expected = image();

        ParticleSystem s = gpuSystem();
        s.burst(gpu(cpuFx), 0.3, -0.2, -2, 1);
        frame(s);
        int[] actual = image();
        assertNotNull(renderer.gpu());
        assertEquals(1, renderer.gpu().poolCount());
        int lit = 0;
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], "pixel " + (i % SIZE) + "," + (i / SIZE));
            lit += r(actual[i]) > 0 ? 1 : 0;
        }
        assertTrue(lit > 200, "the particle covers its square: " + lit);
        assertEquals(0, s.liveCount(), "GPU particles are not in the CPU pool");
    }

    @Test
    void particlesMoveLikeTheCpuOnesAndDieOnTime() {
        ParticleEffect fall = square("fall", 0xFFFFFFFF).lifetime(0.5f, 0.5f).gravity(0, -6f, 0).size(0.2f, 0.2f)
                .build();
        ParticleSystem cpu = new ParticleSystem(64);
        ParticleSystem s = gpuSystem();
        cpu.burst(fall, 0, 1, -3, 1);
        s.burst(gpu(fall), 0, 1, -3, 1);
        for (int f = 0; f < 20; f++) {
            frame(cpu);
            int[] a = image();
            frame(s);
            int[] b = image();
            assertEquals(centroidY(a), centroidY(b), 1.0, "frame " + f + ": same height on screen");
        }
        for (int f = 0; f < 15; f++) {
            frame(s);
        }
        assertEquals(0, litPixels(image()), "dead after its lifetime");
    }

    @Test
    void fxInstancesFinishWhenTheirGpuParticlesAreCertainlyGone() {
        ParticleSystem s = gpuSystem();
        FxWorld fx = new FxWorld(s);
        FxSystem burst = FxSystem.of(gpu(square("puff", 0xFFFFFFFF).lifetime(0.3f, 0.3f).build()), 20);
        FxInstance inst = fx.play(burst, 0, 0, -3);
        for (int f = 0; f < 3; f++) {
            fx.update(DT);
            clear();
            renderer.render(s, frame);
            present();
        }
        assertTrue(inst.isAlive(), "particles in flight");
        assertEquals(20, inst.liveParticles());
        for (int f = 0; f < 60; f++) {
            fx.update(DT);
            clear();
            renderer.render(s, frame);
            present();
        }
        assertFalse(inst.isAlive(), "retired after lifetime + readback latency");
        inst.release();
        fx.close();
    }

    @Test
    void alphaParticlesAreSortedBackToFront() {
        // Two alpha particles of one effect (one pool), the far one spawned last: unsorted, it would cover the near
        // one. (Sorting is per pool: different GPU effects are not sorted against each other.)
        ParticleSystem one = gpuSystem();
        ParticleEffect half = gpu(square("half", 0xFFFFFF80).build());
        one.burst(half, 0, 0, -2, 1);
        one.burst(half, 0.6, 0, -4, 1);
        frame(one);
        int[] img = image();
        int overlap = at(img, C + 4, C);
        int nearOnly = at(img, C - 12, C);
        assertTrue(r(overlap) > r(nearOnly), "the far particle shows through the near one where they overlap");
    }

    @Test
    void theSplitPlaneSeparatesBehindAndFront() {
        ParticleSystem s = gpuSystem();
        ParticleEffect e = gpu(square("water", 0xFFFFFFFF).size(0.4f, 0.4f).build());
        s.burst(e, 0, 0.6, -3, 1);  // above the "water" at y = 0 (camera side: camera at y 0.1 is above)
        s.burst(e, 0, -0.6, -3, 1); // below it
        frame.camera(new Matrix4f().translate(0, -0.1f, 0), projection, 0, 0.1f, 0).splitAtHeight(0f);
        try {
            clear();
            s.update(DT);
            renderer.prepare(s, frame);
            renderer.drawBehind();
            int[] behind = image();
            clear();
            renderer.drawFront();
            int[] front = image();
            assertTrue(centroidY(behind) < C, "the particle under the plane draws behind");
            assertTrue(centroidY(front) > C, "the one above draws in front");
        } finally {
            frame.camera(new Matrix4f(), projection, 0, 0, 0).noSplit();
        }
    }

    @Test
    void collidingParticlesLandOnTheHeightField() {
        ParticleSystem s = gpuSystem();
        ParticleEffect rock = gpu(square("rock", 0xFFFFFFFF).size(0.2f, 0.2f).gravity(0, -20f, 0)
                .collides(0f, 0.5f).build());
        frame(s); // creates the backend
        float[] ground = new float[16 * 16];
        java.util.Arrays.fill(ground, -1f);
        renderer.gpu().setHeightField(-8, -8, 1f, 16, 16, ground);
        s.burst(rock, 0, 1, -3, 1);
        for (int f = 0; f < 90; f++) {
            frame(s);
        }
        // Resting at y = -1 + radius 0.1: on screen at y/z = -0.9/3 = -0.3 of the half view.
        double expected = C + (-0.9 / 3.0) * C;
        assertEquals(expected, centroidY(image()), 2.0, "rests on the ground, not below it");
    }

    @Test
    void deathEventsReachTheFxWorldAsynchronously() {
        ParticleSystem s = gpuSystem();
        FxWorld fx = new FxWorld(s);
        List<String> events = new ArrayList<>();
        fx.setHost(new FxHost() {
            @Override
            public void event(FxInstance instance, String name, double x, double y, double z) {
                events.add(name + "@" + Math.round(y * 10) / 10.0);
            }
        });
        ParticleEffect pop = gpu(square("pop", 0xFFFFFFFF).lifetime(0.1f, 0.1f).build());
        FxSystem sys = FxSystem.builder("pops").emitter(FxEmitter.builder("e", pop).burst(5).duration(0).build())
                .on(FxEventHandler.forward(FxEventHandler.Trigger.DEATH, "e", "popped")).build();
        fx.fire(sys, 0, 2, -3);
        for (int f = 0; f < 30; f++) {
            fx.update(DT);
            clear();
            renderer.render(s, frame);
            present();
        }
        assertEquals(5, events.size(), "every death came back: " + events);
        assertTrue(events.stream().allMatch(e -> e.equals("popped@2.0")), "at the particles' position: " + events);
        fx.close();
    }

    @Test
    void aHotSwappedGpuEffectRebuildsItsPool() {
        ParticleSystem s = gpuSystem();
        ParticleEffect red = gpu(square("swap", 0xFF0000FF).build());
        s.burst(red, 0, 0, -2, 1);
        frame(s);
        assertTrue(r(at(image(), C, C)) > 200, "red before the swap");
        long bytes = renderer.gpu().gpuBytes();
        ParticleEffect blue = gpu(square("swap", 0x0000FFFF).build());
        assertTrue(s.replace(red, blue));
        frame(s); // no new burst: the particle in flight carries on in the new pool, under the new parameters
        int px = at(image(), C, C);
        assertTrue(b(px) > 200 && r(px) < 20, "the new pool draws the new parameters: rgb(" + r(px) + "," + g(px)
                + "," + b(px) + ")");
        assertEquals(bytes, renderer.gpu().gpuBytes(), "the new pool adopted the old one's buffers, nothing leaked");
    }

    @Test
    void customGraphModulesLookTheSameOnBothBackends() {
        com.openmason.engine.rendering.particles.graph.FxGraph swirl = com.openmason.engine.rendering.particles.graph
                .FxGraph.of(java.util.List.of(), com.openmason.engine.rendering.particles.graph.FxGraph.withModule(
                        com.openmason.engine.rendering.particles.graph.FxStageKind.PARTICLE_UPDATE,
                        com.openmason.engine.rendering.particles.graph.FxModules.curlNoiseVelocity(6f, 0.7f, 0.3f),
                        "forces"));
        ParticleEffect curl = ParticleEffect.builder("curl").rate(400).lifetime(2f, 2f).speed(0f, 0.2f).sphere(1.2f)
                .size(0.15f, 0.15f).sprite(ParticleEffect.Sprite.SQUARE).colors(0xFFFFFFFF, 0xFFFFFFFF).fade(0, 0)
                .graph(swirl).build();
        frame.camera(new Matrix4f().lookAt(0, 0, 4f, 0, 0, 0, 0, 1, 0), projection, 0, 0, 4f);
        try {
            int[] a = run(new ParticleSystem(1 << 16), curl);
            int[] b = run(gpuSystem(), gpu(curl));
            try {
                Files.createDirectories(Path.of("target/particle-gpu"));
                save(Path.of("target/particle-gpu/curl-cpu.png"), a);
                save(Path.of("target/particle-gpu/curl-gpu.png"), b);
            } catch (IOException ignored) {
                // images are for review only
            }
            int la = litPixels(a), lb = litPixels(b);
            assertTrue(la > 150, "the swirl covers the view: " + la);
            assertEquals(la, lb, la * 0.3, "the curl field spreads the GPU particles alike");
            assertEquals(spreadX(a), spreadX(b), spreadX(a) * 0.3, "same swirl extent");
        } finally {
            frame.camera(new Matrix4f(), projection, 0, 0, 0);
        }
    }

    private static double spreadX(int[] img) {
        double sum = 0, sq = 0, n = 0;
        for (int i = 0; i < img.length; i++) {
            if (r(img[i]) + g(img[i]) + b(img[i]) > 6) {
                double x = i % SIZE;
                sum += x;
                sq += x * x;
                n++;
            }
        }
        return n == 0 ? 0 : Math.sqrt(sq / n - (sum / n) * (sum / n));
    }

    @Test
    void differentEffectsAndCpuParticlesAreSortedTogether() {
        // GPU effects in different pools: a red square behind a half-transparent green one, spawned front first.
        ParticleSystem s = gpuSystem();
        s.burst(gpu(square("green", 0x00FF0080).build()), 0, 0, -2, 1);
        s.burst(gpu(square("red", 0xFF0000FF).build()), 0, 0, -3, 1);
        frame(s);
        int px = at(image(), C, C);
        assertTrue(g(px) > 100 && r(px) > 60, "green blended over red across pools: rgb(" + r(px) + "," + g(px)
                + "," + b(px) + ")");
        // A CPU particle behind a translucent GPU one, and a translucent CPU one in front of an opaque GPU one.
        ParticleSystem mixed = gpuSystem();
        mixed.burst(square("cpu-red", 0xFF0000FF).build(), -0.6, 0, -3, 1);
        mixed.burst(gpu(square("gpu-green", 0x00FF0080).build()), -0.6, 0, -2, 1);
        mixed.burst(gpu(square("gpu-blue", 0x0000FFFF).build()), 0.6, 0, -3, 1);
        mixed.burst(square("cpu-white", 0xFFFFFF80).build(), 0.6, 0, -2, 1);
        frame(mixed);
        int[] img = image();
        int left = at(img, C - 19, C), right = at(img, C + 19, C);
        assertTrue(g(left) > 100 && r(left) > 60, "GPU green over CPU red: rgb(" + r(left) + "," + g(left) + ","
                + b(left) + ")");
        assertTrue(b(right) > 150 && r(right) > 100, "CPU white over GPU blue: rgb(" + r(right) + "," + g(right) + ","
                + b(right) + ")");
    }

    @Test
    void subEmittersSpawnTheirChildrenOnTheGpu() {
        ParticleEffect child = square("child", 0x00FF00FF).size(0.3f, 0.3f).build();
        ParticleEffect parent = gpu(square("parent", 0xFF0000FF).size(0.3f, 0.3f).lifetime(0.1f, 0.1f)
                .subEmitter(ParticleEffect.SubEmitter.on(ParticleEffect.Trigger.DEATH, child, 1)).build());
        ParticleSystem s = gpuSystem();
        s.burst(parent, 0, 0, -2, 1);
        frame(s);
        int px = at(image(), C, C);
        assertTrue(r(px) > 200 && g(px) < 50, "the parent first");
        for (int f = 0; f < 10; f++) {
            frame(s);
        }
        px = at(image(), C, C);
        assertTrue(g(px) > 200 && r(px) < 50, "then its child, born where it died: rgb(" + r(px) + "," + g(px) + ","
                + b(px) + ")");
        assertEquals(0, s.liveCount(), "no CPU particles were involved");
        assertTrue(s.compile(child).gpu, "the CPU-authored child was promoted to the GPU");

        // TRAIL: a moving parent leaves children behind it.
        ParticleEffect dot = square("dot", 0xFFFFFFFF).size(0.1f, 0.1f).lifetime(1f, 1f).build();
        ParticleEffect comet = gpu(square("comet", 0xFF0000FF).size(0.1f, 0.1f).speed(3f, 3f).direction(1, 0, 0)
                .subEmitter(ParticleEffect.SubEmitter.trail(dot, 60)).build());
        ParticleSystem t = gpuSystem();
        t.burst(comet, -1.5, 0, -3, 1);
        for (int f = 0; f < 40; f++) {
            frame(t);
        }
        assertTrue(spreadX(image()) > 8, "a trail of children along the path");
    }

    @Test
    void particlesCollideWithTheVoxelVolumeLikeOnTheCpu() {
        // A wall at x = 1 (cells x >= 1 solid up to y < 3) and a half slab floor under x < 1 (solid height 0.5 at y = -2).
        ParticleCollider world = new ParticleCollider() {
            @Override public boolean isSolid(int x, int y, int z) { return solidHeight(x, y, z) > 0; }
            @Override public float solidHeight(int x, int y, int z) {
                if (x >= 1 && y < 3) return 1f;
                if (y < -2) return 1f;
                return y == -2 ? 0.5f : 0f;
            }
        };
        ParticleEffect thrown = square("thrown", 0xFFFFFFFF).size(0.2f, 0.2f).speed(6f, 6f).direction(1, 0, 0)
                .gravity(0, -20f, 0).collides(0f, 0.2f).build();
        ParticleSystem cpu = new ParticleSystem(64);
        cpu.setCollider(world);
        ParticleSystem s = gpuSystem();
        frame(s);
        renderer.gpu().setCollisionVolume(-8, -8, -8, 16, 16, 16, world);
        cpu.burst(thrown, 0, 0, -3, 1);
        s.burst(gpu(thrown), 0, 0, -3, 1);
        for (int f = 0; f < 60; f++) {
            cpu.update(DT);
            frame(s);
        }
        double cpuX = cpu.worldX(0), cpuY = cpu.worldY(0);
        assertTrue(cpuX < 1, "the CPU particle stopped at the wall: " + cpuX);
        assertEquals(-1.4, cpuY, 0.05, "and rests on the half slab: " + cpuY);
        // On screen (camera at the origin, z = -3): GPU particle where the CPU one is.
        int[] img = image();
        double expectX = C + (cpuX / 3.0) * C, expectY = C + (cpuY / 3.0) * C;
        assertEquals(expectX, centroidX(img), 2.0, "same stop at the wall on the GPU");
        assertEquals(expectY, centroidY(img), 2.0, "same rest height on the half slab on the GPU");
    }

    @Test
    void instancesRetireAsSoonAsTheirGpuParticlesAreGone() {
        ParticleSystem s = gpuSystem();
        FxWorld fx = new FxWorld(s);
        // 0.2 s particles: the ledger alone retires at 0.2 + the 0.25 s readback margin; the GPU count says sooner.
        FxInstance inst = fx.play(FxSystem.of(gpu(square("quick", 0xFFFFFFFF).lifetime(0.2f, 0.2f).build()), 5), 0, 0, -3);
        int frames = 0;
        do {
            fx.update(DT);
            clear();
            renderer.render(s, frame);
            present();
            frames++;
        } while (inst.isAlive() && frames < 400);
        assertFalse(inst.isAlive());
        assertTrue(frames < 0.4f / DT, "retired from the GPU count, not the 0.45 s ledger bound: " + frames);
        inst.release();
        fx.close();
    }

    @Test
    void theEventRingGrowsAfterAnOverflow() {
        ParticleSystem s = gpuSystem();
        FxWorld fx = new FxWorld(s);
        int[] events = {0};
        fx.setHost(new FxHost() {
            @Override
            public void event(FxInstance instance, String name, double x, double y, double z) {
                events[0]++;
            }
        });
        ParticleEffect pop = gpu(square("pops", 0xFFFFFFFF).lifetime(0.05f, 0.05f).maxLive(8192).build());
        FxSystem sys = FxSystem.builder("many").emitter(FxEmitter.builder("e", pop).burst(3000).duration(0).build())
                .on(FxEventHandler.forward(FxEventHandler.Trigger.DEATH, "e", "popped")).build();
        for (int round = 0; round < 2; round++) {
            events[0] = 0;
            fx.fire(sys, 0, 0, -3);
            for (int f = 0; f < 20; f++) {
                fx.update(DT);
                clear();
                renderer.render(s, frame);
                present();
            }
        }
        assertTrue(renderer.gpu().eventCapacity(pop) >= 3000, "grown to the burst: "
                + renderer.gpu().eventCapacity(pop) + ", pools " + renderer.gpu().poolCount() + ", events "
                + events[0] + ", dropped " + s.gpuQueue().eventsDroppedTotal);
        assertEquals(3000, events[0], "after growing, a 3000-particle burst reports every death");
        fx.close();
    }

    private static double centroidX(int[] img) {
        double sum = 0, weight = 0;
        for (int i = 0; i < img.length; i++) {
            int w = r(img[i]) + g(img[i]) + b(img[i]);
            sum += (i % SIZE) * (double) w;
            weight += w;
        }
        return weight == 0 ? Double.NaN : sum / weight;
    }

    @Test
    void theClearRequestEmptiesThePools() {
        ParticleSystem s = gpuSystem();
        s.burst(gpu(square("x", 0xFFFFFFFF).build()), 0, 0, -2, 10);
        frame(s);
        assertTrue(litPixels(image()) > 0);
        s.clearParticles();
        frame(s);
        assertEquals(0, litPixels(image()));
    }

    /**
     * Every preset the GPU can run, simulated on both backends for one second and drawn from the same camera: the
     * images match statistically (random streams differ): lit area within 35%, mean colour of lit pixels within 40
     * per channel. Images saved for review.
     */
    @Test
    void presetsLookTheSameOnBothBackends() throws IOException {
        Path dir = Path.of("target/particle-gpu");
        Files.createDirectories(dir);
        frame.camera(new Matrix4f().lookAt(0, 1.5f, 5f, 0, 1f, 0, 0, 1, 0), projection, 0, 1.5f, 5f);
        List<String> report = new ArrayList<>();
        for (ParticleEffect p : ParticleLibrary.Presets.ALL) {
            if (!p.subEmitters().isEmpty()) {
                continue;
            }
            ParticleEffect cpuFx = p.toBuilder().rate(Math.max(p.rate(), 0f)).build();
            int[] cpuImg = run(new ParticleSystem(1 << 16), cpuFx);
            ParticleSystem s = gpuSystem();
            int[] gpuImg = run(s, gpu(cpuFx));
            save(dir.resolve(p.name() + "-cpu.png"), cpuImg);
            save(dir.resolve(p.name() + "-gpu.png"), gpuImg);
            int a = litPixels(cpuImg), b = litPixels(gpuImg);
            float[] ma = meanLit(cpuImg), mb = meanLit(gpuImg);
            report.add(String.format("%-16s lit cpu %5d gpu %5d  mean cpu (%3.0f,%3.0f,%3.0f) gpu (%3.0f,%3.0f,%3.0f)",
                    p.name(), a, b, ma[0], ma[1], ma[2], mb[0], mb[1], mb[2]));
            assertEquals(a, b, Math.max(40, a * 0.35), p.name() + " lit area");
            if (a < 40) {
                continue; // a few specks at this distance (embers, sparks): area only, colour is noise
            }
            for (int c = 0; c < 3; c++) {
                assertEquals(ma[c], mb[c], 40f, p.name() + " mean colour channel " + c);
            }
        }
        Files.write(dir.resolve("report.txt"), report);
        frame.camera(new Matrix4f(), projection, 0, 0, 0);
    }

    /** One second of {@code e}: emitted continuously at the origin if it has a rate, else bursts every 0.25 s. */
    private int[] run(ParticleSystem s, ParticleEffect e) {
        ParticleEmitter em = e.rate() > 0 ? s.createEmitter(e, 0, 0.5, 0) : null;
        for (int f = 0; f < 60; f++) {
            if (em == null && f % 15 == 0) {
                s.burst(e, 0, 1, 0, 40);
            }
            frame(s);
        }
        return image();
    }

    // ------------------------------------------------------------------------------------------------

    private static int litPixels(int[] img) {
        int n = 0;
        for (int px : img) {
            if (r(px) + g(px) + b(px) > 6) {
                n++;
            }
        }
        return n;
    }

    private static float[] meanLit(int[] img) {
        double sr = 0, sg = 0, sb = 0;
        int n = 0;
        for (int px : img) {
            if (r(px) + g(px) + b(px) > 6) {
                sr += r(px);
                sg += g(px);
                sb += b(px);
                n++;
            }
        }
        return n == 0 ? new float[3] : new float[]{(float) (sr / n), (float) (sg / n), (float) (sb / n)};
    }

    private static double centroidY(int[] img) {
        double sum = 0, weight = 0;
        for (int i = 0; i < img.length; i++) {
            int w = r(img[i]) + g(img[i]) + b(img[i]);
            sum += (i / SIZE) * (double) w;
            weight += w;
        }
        return weight == 0 ? Double.NaN : sum / weight;
    }

    private static void save(Path file, int[] img) throws IOException {
        BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int px = img[y * SIZE + x];
                out.setRGB(x, SIZE - 1 - y, (r(px) << 16) | (g(px) << 8) | b(px));
            }
        }
        ImageIO.write(out, "png", file.toFile());
    }
}
