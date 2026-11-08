package com.openmason.engine.rendering.particles;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;

/**
 * Golden-image tests: every library preset (plus a texture flipbook, textured smoke, translucency layering
 * and a composite campfire) is simulated deterministically for a fixed time, drawn every frame through the real
 * {@link ParticleRenderer} over a ground plane, and the final frame is compared with a reference PNG in
 * {@code src/test/resources/particles/goldens/}.
 *
 * <p>Each scene also carries <b>semantic checks</b> on the picture (where the effect sits, its colour, how
 * spread out it is), because a golden recorded from this renderer only proves the picture did not change —
 * the checks prove it looks like the effect it claims to be.
 *
 * <ul>
 *   <li>Run: {@code -Dopenmason.particles.gl=true} (needs a display).</li>
 *   <li>(Re)record goldens after an intended visual change: add {@code -Dopenmason.particles.record=true},
 *       then review the PNGs before committing them.</li>
 *   <li>Every run writes {@code target/particle-goldens/}: each scene's actual image, a {@code -diff} image for
 *       mismatches, and {@code contact-sheet.png} with every scene side by side.</li>
 * </ul>
 * Tolerance absorbs driver rounding: a pixel differs when any channel is off by more than
 * {@value #PIXEL_TOLERANCE}; a scene fails when more than {@value #MAX_DIFF_FRACTION} of its pixels differ.
 */
class ParticleGoldenGlTest {

    private static final int SIZE = 160;
    private static final float DT = 1f / 60f;
    private static final int PIXEL_TOLERANCE = 10;
    private static final double MAX_DIFF_FRACTION = 0.005;
    private static final float[] BACKGROUND = {0.07f, 0.08f, 0.11f};
    private static final String GOLDEN_DIR = "/particles/goldens/";
    private static final Path SOURCE_GOLDENS = Path.of("src/test/resources/particles/goldens");
    private static final Path OUTPUT = Path.of("target/particle-goldens");

    private long window;
    private int fbo, colorTex, depthRb, depthCopyFbo, depthCopyTex;
    private int groundProgram, groundVao, groundViewProj, groundColor;
    private ParticleRenderer renderer;
    private final ParticleFrame frame = new ParticleFrame();
    private ParticleTextureArray sceneSprites;
    private final Map<String, BufferedImage> rendered = new LinkedHashMap<>();

    // ------------------------------------------------------------------------------------------------
    // Scenes
    // ------------------------------------------------------------------------------------------------

    /** A deterministic scene: set up a system, simulate {@code seconds}, check the final picture. */
    private record Scene(String name, Vector3f eye, Vector3f target, float seconds, boolean ground,
                         Consumer<SceneSetup> setup, Consumer<Picture> checks) {
    }

    /** What a scene's setup can touch. */
    private record SceneSetup(ParticleSystem system, ParticleFrame frame, ParticleGoldenGlTest test) {
    }

    private static final ParticleCollider FLOOR = (x, y, z) -> y < 0;

    private List<Scene> scenes() {
        ParticleLibrary lib = ParticleLibrary.withBuiltIns();
        List<Scene> list = new ArrayList<>();

        list.add(new Scene("fire", v(0, 0.55f, 1.5f), v(0, 0.4f, 0), 1.5f, true,
                s -> {
                    s.system.createEmitter(lib.get("fire"), 0, 0.02, 0);
                    s.system.createEmitter(lib.get("embers"), 0, 0.15, 0);
                },
                p -> {
                    Picture.Stats st = p.stats();
                    Vector3f base = p.project(0, 0, 0);
                    assertTrue(st.count > 300, "a visible flame: " + st.count + " px");
                    assertTrue(st.centroidY < base.y, "flame rises above its base (screen y down)");
                    assertTrue(st.meanR > st.meanG && st.meanG > st.meanB, "warm: " + st);
                    assertTrue(st.maxLuma > 200, "emissive core is bright: " + st.maxLuma);
                }));

        list.add(new Scene("smoke", v(0.8f, 1.3f, 4.5f), v(0.8f, 1.3f, 0), 4f, true,
                s -> {
                    s.system.setWind(0.8f, 0, 0);
                    s.system.createEmitter(lib.get("smoke"), 0, 0.1, 0);
                },
                p -> {
                    Picture.Stats st = p.stats();
                    assertTrue(st.count > 800, "billowing column: " + st.count);
                    assertTrue(Math.abs(st.meanR - st.meanG) < 6 && Math.abs(st.meanG - st.meanB) < 10,
                            "grey: " + st);
                    assertTrue(st.centroidX > p.project(0, 0, 0).x, "drifts downwind (+X)");
                    assertTrue(st.height > SIZE * 0.35, "tall: " + st.height);
                }));

        list.add(new Scene("sparks", v(0, 1.2f, 3.2f), v(0, 0.7f, 0), 0.3f, true,
                s -> s.system.burst(lib.get("sparks"), 0, 1, 0, 160),
                p -> {
                    Picture.Stats st = p.stats();
                    assertTrue(st.width > SIZE * 0.4, "sparks fly wide: " + st.width);
                    assertTrue(st.meanR > st.meanB + 40, "hot: " + st);
                    assertTrue(st.count < SIZE * SIZE * 0.08, "sparks are specks, not a blob: " + st.count);
                }));

        list.add(new Scene("debris", v(0, 0.9f, 2.2f), v(0, 0.1f, 0), 0.75f, true,
                s -> s.system.burst(lib.get("debris"), 0, 0.5, 0, 0, 1, 0, 90, 0x8B5A2BFF),
                p -> {
                    Picture.Stats st = p.stats();
                    Vector3f floor = p.project(0, 0, 0);
                    assertTrue(st.count > 150, "debris visible: " + st.count);
                    assertTrue(st.meanR > st.meanG && st.meanG > st.meanB, "tinted brown: " + st);
                    assertTrue(Math.abs(st.centroidY - floor.y) < SIZE * 0.2,
                            "settled near the floor: centroid " + st.centroidY + " vs floor " + floor.y);
                }));

        list.add(new Scene("dust", v(0, 0.6f, 2f), v(0, 0.2f, 0), 0.4f, true,
                s -> s.system.burst(lib.get("dust"), 0, 0.05, 0, 50),
                p -> {
                    Picture.Stats st = p.stats();
                    assertTrue(st.count > 200, "a puff: " + st.count);
                    assertTrue(st.width > st.height, "dust spreads sideways more than up");
                }));

        list.add(new Scene("splash", v(0, 1.2f, 3f), v(0, 0.7f, 0), 0.32f, true,
                s -> s.system.burst(lib.get("splash"), 0, 0, 0, 140),
                p -> {
                    Picture.Stats st = p.stats();
                    assertTrue(st.meanB > st.meanR, "watery blue: " + st);
                    assertTrue(st.centroidY < p.project(0, 0, 0).y, "thrown upward");
                }));

        list.add(new Scene("snow", v(0, 1.6f, 5f), v(0, 1.4f, 0), 15f, true,
                s -> {
                    s.system.setWind(0.4f, 0, 0);
                    s.system.createEmitter(lib.get("snow"), 0, 6, 0);
                },
                p -> {
                    Picture.Stats st = p.stats();
                    assertTrue(st.width > SIZE * 0.8 && st.height > SIZE * 0.5, "fills the view: " + st);
                    assertTrue(st.meanR > 100 && Math.abs(st.meanR - st.meanB) < 20
                            && Math.abs(st.meanG - st.meanB) < 20, "white flakes: " + st);
                }));

        list.add(new Scene("magic", v(0, 1f, 2f), v(0, 1f, 0), 1.5f, false,
                s -> s.system.createEmitter(lib.get("magic"), 0, 1, 0),
                p -> {
                    Picture.Stats st = p.stats();
                    Vector3f c = p.project(0, 1, 0);
                    assertTrue(Math.abs(st.centroidX - c.x) < SIZE * 0.12, "centred on the emitter (x)");
                    assertTrue(st.meanB > st.meanG * 0.8 && st.meanR + st.meanB > 2 * st.meanG, "violet/cyan: " + st);
                }));

        list.add(new Scene("explosion", v(0, 1.4f, 5f), v(0, 1f, 0), 0.25f, true,
                s -> {
                    s.system.burst(lib.get("explosion"), 0, 1, 0, 60);
                    s.system.burst(lib.get("smoke"), 0, 1, 0, 20);
                    s.system.burst(lib.get("sparks"), 0, 1, 0, 0, 1, 0, 80, 0xFFFFFFFF);
                },
                p -> {
                    Picture.Stats st = p.stats();
                    assertTrue(st.count > SIZE * SIZE * 0.12, "a big fireball: " + st.count);
                    assertTrue(st.maxLuma > 230, "blinding core: " + st.maxLuma);
                }));

        list.add(new Scene("texture-flipbook", v(0, 1f, 3f), v(0, 1f, 0), 1.2f, false,
                s -> {
                    s.frame.sprites = s.test.sprites(ringImages());
                    s.system.createEmitter(ParticleEffect.builder("rings").lifetime(1.2f, 1.2f).rate(6)
                            .speed(1.2f, 1.2f).direction(0, 1, 0).spread(0).size(0.4f, 0.4f).fade(0, 0)
                            .textures(List.of("ring_red", "ring_yellow", "ring_green", "ring_blue"), true)
                            .emissive(true).build(), 0, 0.2, 0);
                },
                p -> {
                    // Frames run red → yellow → green → blue from the bottom (young) to the top (old).
                    Vector3f young = p.project(0, 0.5f, 0), old = p.project(0, 1.55f, 0);
                    assertTrue(p.dominant((int) young.x, (int) young.y, 10) == 'r', "young particles show frame 0 (red)");
                    assertTrue(p.dominant((int) old.x, (int) old.y, 10) == 'b', "old particles show frame 3 (blue)");
                }));

        list.add(new Scene("textured-smoke", v(0.8f, 1.3f, 4.5f), v(0.8f, 1.3f, 0), 4f, true,
                s -> {
                    s.frame.sprites = s.test.sprites(puffImages());
                    s.system.setWind(0.8f, 0, 0);
                    // The smoke preset with its own puff images (one picked per particle) instead of the disc.
                    s.system.createEmitter(lib.get("smoke").toBuilder()
                            .textures(List.of("puff_0", "puff_1", "puff_2", "puff_3"), false).build(), 0, 0.1, 0);
                },
                p -> {
                    Picture.Stats st = p.stats();
                    assertTrue(st.count > 800, "billowing column: " + st.count);
                    assertTrue(Math.abs(st.meanR - st.meanG) < 6 && Math.abs(st.meanG - st.meanB) < 10,
                            "grey, no magenta missing-texture checker: " + st);
                    assertTrue(st.centroidX > p.project(0, 0, 0).x, "drifts downwind (+X)");
                    assertTrue(st.height > SIZE * 0.35, "tall: " + st.height);
                }));

        list.add(new Scene("translucency-layers", v(0, 0, 0), v(0, 0, -1), 0f, false,
                s -> {
                    // Five overlapping translucent discs at increasing depth, spawned in scrambled order.
                    int[] colours = {0xFF3030C0, 0x30FF30C0, 0x3030FFC0, 0xFFFF30C0, 0xFF30FFC0};
                    int[] order = {3, 0, 4, 1, 2};
                    for (int k : order) {
                        ParticleEffect e = ParticleEffect.builder("layer" + k).lifetime(5, 5).rate(0)
                                .speed(0, 0).fade(0, 0).color(colours[k]).size(1.2f + k * 0.5f, 1.2f + k * 0.5f)
                                .sprite(ParticleEffect.Sprite.SQUARE).build();
                        s.system.burst(e, -0.6 + k * 0.5, 0, -2 - k, 1);
                    }
                },
                p -> {
                    // The nearest square (red, k = 0) must be on top where it overlaps the others.
                    Vector3f red = p.project(-0.6f, 0, -2);
                    assertEquals('r', p.dominant((int) red.x, (int) red.y, 2), "nearest layer drawn last");
                }));

        list.add(new Scene("campfire", v(0.4f, 1.1f, 3.2f), v(0, 0.9f, 0), 2.5f, true,
                s -> {
                    s.system.setWind(0.3f, 0, 0);
                    s.system.createEmitter(lib.get("smoke"), 0, 0.5, 0).setRateScale(1.5f);
                    s.system.createEmitter(lib.get("fire"), 0, 0.02, 0);
                    s.system.createEmitter(lib.get("embers"), 0, 0.2, 0).setRateScale(2);
                    s.system.burst(lib.get("sparks"), 0, 0.3, 0, 0, 1, 0, 25, 0xFFFFFFFF);
                },
                p -> {
                    Picture.Stats st = p.stats();
                    assertTrue(st.count > 1500, "fire + smoke column: " + st.count);
                    Vector3f top = p.project(0, 2.0f, 0), base = p.project(0, 0.15f, 0);
                    assertTrue(p.luma((int) base.x, (int) base.y) > p.luma((int) top.x, (int) top.y),
                            "glowing base, dim smoke above");
                }));
        list.add(new Scene("rain", v(0, 1.4f, 4.5f), v(0, 0.6f, 0), 2.5f, true,
                s -> {
                    s.system.setWind(0.6f, 0, 0);
                    s.system.createEmitter(lib.get("rain"), 0, 7, -2);
                },
                p -> {
                    Picture.Stats st = p.stats();
                    assertTrue(st.count > 500, "a curtain of rain: " + st.count);
                    assertTrue(st.meanB > st.meanR, "cool blue-grey: " + st);
                    // Ripples and splashes on the ground in front of the camera.
                    int onGround = 0;
                    for (int y = (int) p.project(0, 0, -1).y; y < SIZE; y++) {
                        for (int x = 0; x < SIZE; x++) {
                            if (p.luma(x, y) > 90) onGround++;
                        }
                    }
                    assertTrue(onGround > 40, "splashes/ripples where it lands: " + onGround);
                }));

        list.add(new Scene("firework", v(0, 6f, 10f), v(0, 7.5f, 0), 1.7f, true,
                s -> s.system.burst(lib.get("firework"), 0, 0, 0, 1),
                p -> {
                    Picture.Stats st = p.stats();
                    Vector3f high = p.project(0, 6, 0);
                    assertTrue(st.centroidY < high.y, "burst high in the sky");
                    assertTrue(st.width > SIZE * 0.3 && st.height > SIZE * 0.3, "a round star burst: " + st);
                    assertTrue(st.meanB > st.meanG, "coloured stars: " + st);
                }));

        list.add(new Scene("torch-flame", v(0, 0.45f, 0.9f), v(0, 0.2f, 0), 1f, true,
                s -> s.system.createEmitter(lib.get("flame"), 0, 0.02, 0),
                p -> {
                    Picture.Stats st = p.stats();
                    assertTrue(st.count > 40, "a visible flame: " + st.count);
                    assertTrue(st.height > st.width, "a rising upright flame: " + st);
                    assertTrue(st.meanR > st.meanB, "warm: " + st);
                }));
        return list;
    }

    // ------------------------------------------------------------------------------------------------
    // Test factory
    // ------------------------------------------------------------------------------------------------

    @TestFactory
    List<DynamicTest> goldenScenes() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Scene scene : scenes()) {
            tests.add(DynamicTest.dynamicTest(scene.name, () -> runScene(scene)));
        }
        tests.add(DynamicTest.dynamicTest("deterministic re-render", () -> {
            Scene campfire = scenes().stream().filter(s -> s.name.equals("campfire")).findFirst().orElseThrow();
            BufferedImage a = render(campfire);
            BufferedImage b = render(campfire);
            assertEquals(0, diffCount(a, b, 0, null), "same seed + same steps = identical pixels");
        }));
        tests.add(DynamicTest.dynamicTest("contact sheet", this::writeContactSheet));
        return tests;
    }

    private void runScene(Scene scene) throws IOException {
        BufferedImage actual = render(scene);
        rendered.put(scene.name, actual);
        Files.createDirectories(OUTPUT);
        ImageIO.write(actual, "png", OUTPUT.resolve(scene.name + ".png").toFile());

        scene.checks.accept(new Picture(actual, frame));

        if (Boolean.getBoolean("openmason.particles.record")) {
            Files.createDirectories(SOURCE_GOLDENS);
            ImageIO.write(actual, "png", SOURCE_GOLDENS.resolve(scene.name + ".png").toFile());
            return;
        }
        BufferedImage golden = loadGolden(scene.name);
        assertNotNull(golden, "no golden for '" + scene.name + "' — record with -Dopenmason.particles.record=true");
        assertEquals(SIZE, golden.getWidth());
        BufferedImage diff = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        int differing = diffCount(actual, golden, PIXEL_TOLERANCE, diff);
        double fraction = differing / (double) (SIZE * SIZE);
        if (fraction > MAX_DIFF_FRACTION) {
            ImageIO.write(diff, "png", OUTPUT.resolve(scene.name + "-diff.png").toFile());
            fail(String.format("%s: %.2f%% of pixels differ from the golden (limit %.2f%%); see %s",
                    scene.name, fraction * 100, MAX_DIFF_FRACTION * 100, OUTPUT.toAbsolutePath()));
        }
    }

    /** Simulates the scene from scratch, drawing every frame (exercising the ring), and reads back the last. */
    private BufferedImage render(Scene scene) {
        ParticleSystem system = new ParticleSystem(20_000, 0xC0FFEE);
        system.setCollider(FLOOR);
        frame.sprites = null;
        frame.ambient = 1f;
        frame.fogDensity = 0f;
        frame.depthTexture = depthCopyTex;
        Matrix4f view = new Matrix4f().lookAt(scene.eye, scene.target, new Vector3f(0, 1, 0));
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(60), 1f, 0.05f, 200f);
        frame.camera(view, projection, scene.eye.x, scene.eye.y, scene.eye.z);
        scene.setup.accept(new SceneSetup(system, frame, this));

        int steps = Math.round(scene.seconds / DT);
        for (int i = 0; i <= steps; i++) {
            if (i > 0) {
                system.update(DT);
            }
            glClearColor(BACKGROUND[0], BACKGROUND[1], BACKGROUND[2], 1f);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            if (scene.ground) {
                drawGround(projection.mul(view, new Matrix4f()));
            }
            copySceneDepth(); // soft particles read the opaque scene's depth, as a game would provide it
            renderer.render(system, frame);
            assertEquals(GL_NO_ERROR, glGetError());
        }
        if (sceneSprites != null) {
            sceneSprites.close();
            sceneSprites = null;
            frame.sprites = null;
        }
        return readBack();
    }

    // ------------------------------------------------------------------------------------------------
    // GL scaffolding
    // ------------------------------------------------------------------------------------------------

    @BeforeEach
    void createContext() {
        assumeTrue(Boolean.getBoolean("openmason.particles.gl"), "opt in with -Dopenmason.particles.gl=true");
        assertTrue(glfwInit(), "GLFW display unavailable");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        window = glfwCreateWindow(16, 16, "Particle golden images", 0, 0);
        assertNotEquals(0, window);
        glfwMakeContextCurrent(window);
        GL.createCapabilities();

        colorTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, colorTex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, SIZE, SIZE, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
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
        glDepthFunc(GL_LESS);

        depthCopyTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, depthCopyTex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, SIZE, SIZE, 0, GL_DEPTH_COMPONENT, GL_UNSIGNED_INT,
                (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glBindTexture(GL_TEXTURE_2D, 0);
        depthCopyFbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, depthCopyFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depthCopyTex, 0);
        glDrawBuffer(GL_NONE);
        glReadBuffer(GL_NONE);
        assertEquals(GL_FRAMEBUFFER_COMPLETE, glCheckFramebufferStatus(GL_FRAMEBUFFER));
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);

        createGround();
        renderer = new ParticleRenderer();
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
        glDeleteFramebuffers(depthCopyFbo);
        glDeleteTextures(depthCopyTex);
        glDeleteProgram(groundProgram);
        glDeleteVertexArrays(groundVao);
        glDeleteFramebuffers(fbo);
        glDeleteRenderbuffers(depthRb);
        glDeleteTextures(colorTex);
        int error = glGetError();
        GL.setCapabilities(null);
        glfwDestroyWindow(window);
        glfwTerminate();
        assertEquals(GL_NO_ERROR, error);
    }

    /** A 24×24 ground quad at y = 0 with a faint grid, depth-writing, so particles land on and behind it. */
    private void createGround() {
        String vs = """
                #version 330 core
                uniform mat4 u_viewProj;
                out vec2 v_xz;
                void main() {
                    vec2 c = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1)) * 24.0 - 12.0;
                    v_xz = c;
                    gl_Position = u_viewProj * vec4(c.x, 0.0, c.y, 1.0);
                }
                """;
        String fs = """
                #version 330 core
                uniform vec3 u_color;
                in vec2 v_xz;
                out vec4 o;
                void main() {
                    vec2 g = abs(fract(v_xz) - 0.5);
                    float line = step(0.47, max(g.x, g.y));
                    o = vec4(u_color * (1.0 + 0.35 * line), 1.0);
                }
                """;
        groundProgram = glCreateProgram();
        int v = compile(GL_VERTEX_SHADER, vs), f = compile(GL_FRAGMENT_SHADER, fs);
        glAttachShader(groundProgram, v);
        glAttachShader(groundProgram, f);
        glLinkProgram(groundProgram);
        assertEquals(GL_TRUE, glGetProgrami(groundProgram, GL_LINK_STATUS), glGetProgramInfoLog(groundProgram));
        glDeleteShader(v);
        glDeleteShader(f);
        groundViewProj = glGetUniformLocation(groundProgram, "u_viewProj");
        groundColor = glGetUniformLocation(groundProgram, "u_color");
        groundVao = glGenVertexArrays();
    }

    private static int compile(int type, String src) {
        int s = glCreateShader(type);
        glShaderSource(s, src);
        glCompileShader(s);
        assertEquals(GL_TRUE, glGetShaderi(s, GL_COMPILE_STATUS), glGetShaderInfoLog(s));
        return s;
    }

    private void drawGround(Matrix4f viewProj) {
        glUseProgram(groundProgram);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            glUniformMatrix4fv(groundViewProj, false, viewProj.get(stack.mallocFloat(16)));
        }
        glUniform3f(groundColor, 0.16f, 0.18f, 0.15f);
        glBindVertexArray(groundVao);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glBindVertexArray(0);
        glUseProgram(0);
    }

    private void copySceneDepth() {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, depthCopyFbo);
        glBlitFramebuffer(0, 0, SIZE, SIZE, 0, 0, SIZE, SIZE, GL_DEPTH_BUFFER_BIT, GL_NEAREST);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    }

    /** Uploads a scene's sprite images (64-px cells); released after the scene renders. */
    ParticleTextureArray sprites(Map<String, ParticleImage> images) {
        sceneSprites = ParticleTextureArray.upload(ParticleSpriteLayout.packNames(images.keySet(), images::get,
                ParticleSpriteLayout.DEFAULT_CELL_SIZE));
        assertTrue(sceneSprites.layout().missing().isEmpty());
        return sceneSprites;
    }

    /** Four 16-px ring sprites, one image each: red, yellow, green, blue. */
    static Map<String, ParticleImage> ringImages() {
        int cell = 16;
        String[] names = {"ring_red", "ring_yellow", "ring_green", "ring_blue"};
        int[] colours = {0xFF2020, 0xFFE020, 0x20FF40, 0x3060FF};
        Map<String, ParticleImage> out = new LinkedHashMap<>();
        for (int f = 0; f < 4; f++) {
            int[] px = new int[cell * cell];
            for (int y = 0; y < cell; y++) {
                for (int x = 0; x < cell; x++) {
                    float dx = x + 0.5f - cell / 2f, dy = y + 0.5f - cell / 2f;
                    float d = (float) Math.sqrt(dx * dx + dy * dy) / (cell / 2f);
                    float a = Math.max(0f, 1f - Math.abs(d - 0.65f) / 0.3f);
                    px[y * cell + x] = colours[f] << 8 | Math.round(a * 255);
                }
            }
            out.put(names[f], new ParticleImage(cell, cell, px));
        }
        return out;
    }

    /**
     * Four 64-px smoke puffs: a soft round falloff broken up by deterministic value noise, so each puff has its
     * own lumpy outline (what a textured smoke sprite looks like, without shipping an image in the tests).
     */
    static Map<String, ParticleImage> puffImages() {
        int cell = 64;
        Map<String, ParticleImage> out = new LinkedHashMap<>();
        for (int v = 0; v < 4; v++) {
            int[] px = new int[cell * cell];
            for (int y = 0; y < cell; y++) {
                for (int x = 0; x < cell; x++) {
                    float u = (x + 0.5f) / cell * 2f - 1f, w = (y + 0.5f) / cell * 2f - 1f;
                    float d = (float) Math.sqrt(u * u + w * w);
                    float noise = 0.6f * valueNoise(x / 12f, y / 12f, v) + 0.4f * valueNoise(x / 5f, y / 5f, v + 17);
                    float a = Math.max(0f, Math.min(1f, (1f - d) * 1.6f - 0.25f + (noise - 0.5f) * 0.9f));
                    int grey = 200 + Math.round(55 * noise);
                    px[y * cell + x] = grey << 24 | grey << 16 | grey << 8 | Math.round(a * a * 255);
                }
            }
            out.put("puff_" + v, new ParticleImage(cell, cell, px));
        }
        return out;
    }

    private static float valueNoise(float x, float y, int seed) {
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
        float fx = x - x0, fy = y - y0;
        fx = fx * fx * (3 - 2 * fx);
        fy = fy * fy * (3 - 2 * fy);
        float a = hash(x0, y0, seed), b = hash(x0 + 1, y0, seed), c = hash(x0, y0 + 1, seed), d = hash(x0 + 1, y0 + 1, seed);
        return a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy;
    }

    private static float hash(int x, int y, int seed) {
        int h = x * 374761393 + y * 668265263 + seed * 1274126177;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) & 0xFFFF) / 65535f;
    }

    private BufferedImage readBack() {
        ByteBuffer px = MemoryUtil.memAlloc(SIZE * SIZE * 4);
        try {
            glReadPixels(0, 0, SIZE, SIZE, GL_RGBA, GL_UNSIGNED_BYTE, px);
            BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    int i = ((SIZE - 1 - y) * SIZE + x) * 4; // GL rows are bottom-up
                    img.setRGB(x, y, ((px.get(i) & 0xFF) << 16) | ((px.get(i + 1) & 0xFF) << 8) | (px.get(i + 2) & 0xFF));
                }
            }
            return img;
        } finally {
            MemoryUtil.memFree(px);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Image comparison and output
    // ------------------------------------------------------------------------------------------------

    private static BufferedImage loadGolden(String name) throws IOException {
        try (InputStream in = ParticleGoldenGlTest.class.getResourceAsStream(GOLDEN_DIR + name + ".png")) {
            return in == null ? null : ImageIO.read(in);
        }
    }

    /** Pixels whose largest channel difference exceeds {@code tolerance}; paints them red in {@code diff}. */
    private static int diffCount(BufferedImage a, BufferedImage b, int tolerance, BufferedImage diff) {
        int count = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                int p = a.getRGB(x, y), q = b.getRGB(x, y);
                int d = Math.max(Math.abs(((p >> 16) & 0xFF) - ((q >> 16) & 0xFF)),
                        Math.max(Math.abs(((p >> 8) & 0xFF) - ((q >> 8) & 0xFF)), Math.abs((p & 0xFF) - (q & 0xFF))));
                boolean differs = d > tolerance;
                if (differs) count++;
                if (diff != null) {
                    int grey = (((p >> 16) & 0xFF) + ((p >> 8) & 0xFF) + (p & 0xFF)) / 9;
                    diff.setRGB(x, y, differs ? 0xFF0000 : (grey << 16) | (grey << 8) | grey);
                }
            }
        }
        return count;
    }

    private void writeContactSheet() throws IOException {
        assumeTrue(!rendered.isEmpty());
        int cols = 4, label = 14, n = rendered.size();
        int rows = (n + cols - 1) / cols;
        BufferedImage sheet = new BufferedImage(cols * SIZE, rows * (SIZE + label), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
        int i = 0;
        for (Map.Entry<String, BufferedImage> e : rendered.entrySet()) {
            int x = (i % cols) * SIZE, y = (i / cols) * (SIZE + label);
            g.drawImage(e.getValue(), x, y + label, null);
            g.setColor(Color.LIGHT_GRAY);
            g.drawString(e.getKey(), x + 4, y + 11);
            i++;
        }
        g.dispose();
        ImageIO.write(sheet, "png", OUTPUT.resolve("contact-sheet.png").toFile());
    }

    private static Vector3f v(float x, float y, float z) {
        return new Vector3f(x, y, z);
    }

    // ------------------------------------------------------------------------------------------------
    // Picture analysis for the semantic checks
    // ------------------------------------------------------------------------------------------------

    /** A rendered frame plus the camera that made it, with helpers for "what is where" checks. */
    private static final class Picture {
        private final BufferedImage img;
        private final Matrix4f viewProj;
        private final int bg;

        Picture(BufferedImage img, ParticleFrame frame) {
            this.img = img;
            this.viewProj = frame.projection.mul(frame.view, new Matrix4f());
            this.bg = (Math.round(BACKGROUND[0] * 255) << 16) | (Math.round(BACKGROUND[1] * 255) << 8)
                    | Math.round(BACKGROUND[2] * 255);
        }

        /** Screen position (x right, y down, pixels) of a world point. */
        Vector3f project(float x, float y, float z) {
            Vector4f c = viewProj.transform(new Vector4f(x, y, z, 1));
            return new Vector3f((c.x / c.w + 1) * 0.5f * SIZE, (1 - (c.y / c.w + 1) * 0.5f) * SIZE, c.z / c.w);
        }

        int luma(int x, int y) {
            int p = img.getRGB(clamp(x), clamp(y));
            return (((p >> 16) & 0xFF) * 3 + ((p >> 8) & 0xFF) * 6 + (p & 0xFF)) / 10;
        }

        /** 'r', 'g' or 'b': the strongest channel summed over a square of radius {@code r}. */
        char dominant(int cx, int cy, int r) {
            long sr = 0, sg = 0, sb = 0;
            for (int y = cy - r; y <= cy + r; y++) {
                for (int x = cx - r; x <= cx + r; x++) {
                    int p = img.getRGB(clamp(x), clamp(y));
                    sr += (p >> 16) & 0xFF;
                    sg += (p >> 8) & 0xFF;
                    sb += p & 0xFF;
                }
            }
            return sr >= sg && sr >= sb ? 'r' : sg >= sb ? 'g' : 'b';
        }

        private static int clamp(int v) {
            return Math.max(0, Math.min(SIZE - 1, v));
        }

        /** Statistics over pixels that differ from both the background and the ground. */
        Stats stats() {
            int n = 0, minX = SIZE, maxX = -1, minY = SIZE, maxY = -1, maxLuma = 0;
            long sx = 0, sy = 0, sr = 0, sg = 0, sb = 0;
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    int p = img.getRGB(x, y);
                    int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                    if (!isParticle(r, g, b)) {
                        continue;
                    }
                    n++;
                    sx += x;
                    sy += y;
                    sr += r;
                    sg += g;
                    sb += b;
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                    maxLuma = Math.max(maxLuma, (r * 3 + g * 6 + b) / 10);
                }
            }
            if (n == 0) {
                return new Stats(0, 0, 0, 0, 0, 0, 0, 0, 0);
            }
            return new Stats(n, sx / (float) n, sy / (float) n, maxX - minX + 1, maxY - minY + 1,
                    sr / (float) n, sg / (float) n, sb / (float) n, maxLuma);
        }

        /** Far enough from the background and the (dark green-grey, gridded) ground to be a particle. */
        private boolean isParticle(int r, int g, int b) {
            int br = (bg >> 16) & 0xFF, bgc = (bg >> 8) & 0xFF, bb = bg & 0xFF;
            boolean background = Math.abs(r - br) + Math.abs(g - bgc) + Math.abs(b - bb) < 24;
            // Ground: 0.16/0.18/0.15 (×1.35 on grid lines) → max channel ≤ 63 and near-grey-green.
            boolean ground = Math.max(r, Math.max(g, b)) <= 64 && Math.abs(r - b) < 10 && g >= r && g - r < 10;
            return !background && !ground;
        }

        record Stats(int count, float centroidX, float centroidY, int width, int height,
                     float meanR, float meanG, float meanB, int maxLuma) {
        }
    }
}
