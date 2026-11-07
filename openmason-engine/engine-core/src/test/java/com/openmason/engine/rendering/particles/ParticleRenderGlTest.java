package com.openmason.engine.rendering.particles;

import com.openmason.engine.diagnostics.GpuMemoryTracker;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;

/**
 * Real GL rendering of particles into an offscreen 64×64 target, read back pixel by pixel. Needs a display:
 * opt in with {@code -Dopenmason.particles.gl=true}.
 *
 * <p>Scene: camera at the origin looking down −Z, 90° FOV, square viewport. A particle of world size 1 at
 * {@code z = −2} covers NDC ±0.25, i.e. pixels 24..39 on both axes, centred on pixel 32.
 */
class ParticleRenderGlTest {

    private static final int SIZE = 64;
    private static final int C = SIZE / 2;

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
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        window = glfwCreateWindow(16, 16, "Particle rendering test", 0, 0);
        assertNotEquals(0, window);
        glfwMakeContextCurrent(window);
        GL.createCapabilities();

        colorTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, colorTex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, SIZE, SIZE, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
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
        glDepthFunc(GL_LESS);

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
    // Helpers
    // ------------------------------------------------------------------------------------------------

    private static ParticleEffect.Builder still(String name) {
        return ParticleEffect.builder(name).lifetime(10, 10).speed(0, 0).spread(0).rate(0).fade(0, 0)
                .size(1, 1).color(0xFFFFFFFF);
    }

    private static ParticleEffect square(String name, int rgba) {
        return still(name).sprite(ParticleEffect.Sprite.SQUARE).color(rgba).build();
    }

    private void clear(float r, float g, float b) {
        glClearColor(r, g, b, 1f);
        glClearDepth(1.0);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    }

    private int draw(ParticleSystem s) {
        int n = renderer.render(s, frame);
        assertEquals(GL_NO_ERROR, glGetError());
        return n;
    }

    /** RGBA 0..255 of window pixel (x, y), y up. */
    private int[] pixel(int x, int y) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer px = stack.malloc(4);
            glReadPixels(x, y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, px);
            return new int[]{px.get(0) & 0xFF, px.get(1) & 0xFF, px.get(2) & 0xFF, px.get(3) & 0xFF};
        }
    }

    private float depthAt(int x, int y) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer d = stack.mallocFloat(1);
            glReadPixels(x, y, 1, 1, GL_DEPTH_COMPONENT, GL_FLOAT, d);
            return d.get(0);
        }
    }

    private float windowDepthOf(float viewZ) {
        Vector4f clip = projection.transform(new Vector4f(0, 0, viewZ, 1));
        return (clip.z / clip.w + 1f) * 0.5f;
    }

    private static void assertRgb(int[] px, int r, int g, int b, int tolerance, String what) {
        assertTrue(Math.abs(px[0] - r) <= tolerance && Math.abs(px[1] - g) <= tolerance
                        && Math.abs(px[2] - b) <= tolerance,
                what + ": expected rgb(" + r + "," + g + "," + b + ") ±" + tolerance
                        + " but was rgb(" + px[0] + "," + px[1] + "," + px[2] + ")");
    }

    private static boolean lit(int[] px) {
        return px[0] + px[1] + px[2] > 0;
    }

    // ------------------------------------------------------------------------------------------------
    // Shape and placement
    // ------------------------------------------------------------------------------------------------

    @Test
    void softDiscIsBrightInTheMiddleFallsOffAndLeavesCornersClear() {
        ParticleSystem s = new ParticleSystem(4);
        s.burst(still("soft").build(), 0, 0, -2, 1);
        clear(0, 0, 0);
        assertEquals(1, draw(s));
        int centre = pixel(C, C)[0];
        int inner = pixel(C + 4, C)[0];
        int rim = pixel(C + 7, C)[0];
        assertTrue(centre > 190, "centre " + centre);
        assertTrue(centre > inner && inner > rim, "monotone falloff " + centre + " > " + inner + " > " + rim);
        assertTrue(rim < 40, "rim " + rim);
        assertRgb(pixel(24, 24), 0, 0, 0, 0, "quad corner lies outside the inscribed disc");
        assertRgb(pixel(2, 2), 0, 0, 0, 0, "background");
        // Round: equal distance along X and Y gives equal coverage.
        assertEquals(pixel(C + 5, C)[0], pixel(C, C + 5)[0], 2);
    }

    @Test
    void squareCoversExactlyItsProjectedFootprint() {
        ParticleSystem s = new ParticleSystem(4);
        s.burst(square("sq", 0xFF8040FF), 0, 0, -2, 1);
        clear(0, 0, 0);
        draw(s);
        assertRgb(pixel(C, C), 255, 128, 64, 1, "inside");
        for (int edge : new int[]{24, 39}) {
            assertTrue(lit(pixel(edge, C)) && lit(pixel(C, edge)), "edge pixel " + edge + " is inside");
        }
        for (int out : new int[]{22, 41}) {
            assertFalse(lit(pixel(out, C)) || lit(pixel(C, out)), "pixel " + out + " is outside");
        }
    }

    @Test
    void particlesSitWhereTheyAreInTheWorld() {
        ParticleSystem s = new ParticleSystem(4);
        // x = +1 at z = -2 projects to NDC 0.5 → pixel 48.
        s.burst(still("right").sprite(ParticleEffect.Sprite.SQUARE).size(0.25f, 0.25f).build(), 1, -1, -2, 1);
        clear(0, 0, 0);
        draw(s);
        assertTrue(lit(pixel(48, 16)), "projected to (48, 16)");
        assertFalse(lit(pixel(C, C)));
        assertFalse(lit(pixel(16, 48)));
    }

    @Test
    void rotationTurnsTheQuadAroundTheViewAxis() {
        ParticleSystem s = new ParticleSystem(4);
        s.burst(square("flat", 0xFFFFFFFF), 0, 0, -2, 1);
        clear(0, 0, 0);
        draw(s);
        assertFalse(lit(pixel(42, C)), "unrotated: beyond the half-width");
        assertTrue(lit(pixel(39, 39)), "unrotated: corner covered");

        s.clearParticles();
        s.burst(still("diamond").sprite(ParticleEffect.Sprite.SQUARE).rotation(45, 45).build(), 0, 0, -2, 1);
        clear(0, 0, 0);
        draw(s);
        assertTrue(lit(pixel(42, C)), "45°: the diamond's tip reaches past the old edge");
        assertFalse(lit(pixel(39, 39)), "45°: the old corner is uncovered");
    }

    // ------------------------------------------------------------------------------------------------
    // Blending and ordering
    // ------------------------------------------------------------------------------------------------

    @Test
    void translucentParticlesCompositeBackToFrontWhateverTheSpawnOrder() {
        ParticleEffect nearRed = still("red").sprite(ParticleEffect.Sprite.SQUARE).color(0xFF000080).build();
        ParticleEffect farBlue = still("blue").sprite(ParticleEffect.Sprite.SQUARE).size(2, 2).color(0x0000FF80).build();
        for (boolean redFirst : new boolean[]{true, false}) {
            ParticleSystem s = new ParticleSystem(4);
            if (redFirst) {
                s.burst(nearRed, 0, 0, -2, 1);
                s.burst(farBlue, 0, 0, -4, 1);
            } else {
                s.burst(farBlue, 0, 0, -4, 1);
                s.burst(nearRed, 0, 0, -2, 1);
            }
            clear(0, 0, 0);
            draw(s);
            // Blue (α .5) over black, then red (α .5) over that: (0.5, 0, 0.25).
            assertRgb(pixel(C, C), 128, 0, 64, 3, "near red over far blue, red spawned first=" + redFirst);
        }
    }

    @Test
    void additiveParticlesAccumulateLightAndNeverDarken() {
        ParticleSystem s = new ParticleSystem(4);
        ParticleEffect glow = still("glow").sprite(ParticleEffect.Sprite.SQUARE).color(0x505050FF).additive(1).build();
        s.burst(glow, 0, 0, -2, 2);
        clear(0.25f, 0.25f, 0.25f);
        draw(s);
        assertRgb(pixel(C, C), 64 + 160, 64 + 160, 64 + 160, 2, "background + 2 × 80");
        assertRgb(pixel(4, 4), 64, 64, 64, 1, "untouched background");
    }

    @Test
    void halfAdditiveSitsBetweenGlowAndCover() {
        ParticleSystem s = new ParticleSystem(4);
        s.burst(still("half").sprite(ParticleEffect.Sprite.SQUARE).color(0xFF0000FF).additive(0.5f).build(), 0, 0, -2, 1);
        clear(0, 0, 1);
        draw(s);
        // Premultiplied (1, 0, 0, 0.5) over blue: blue keeps half its light, red adds fully.
        assertRgb(pixel(C, C), 255, 0, 128, 2, "half additive");
    }

    @Test
    void particlesAreDepthTestedButNeverWriteDepth() {
        float wall = windowDepthOf(-3f);
        ParticleSystem s = new ParticleSystem(4);
        ParticleEffect sq = still("d").sprite(ParticleEffect.Sprite.SQUARE).size(0.25f, 0.25f).build();
        s.burst(sq, -1, 0, -2, 1);   // in front of the "wall" → visible at pixel 16
        s.burst(sq, 1.0, 0, -4, 1);  // behind it → hidden at pixel 40
        glClearColor(0, 0, 0, 1);
        glClearDepth(wall);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        draw(s);
        assertTrue(lit(pixel(16, C)), "in front of the depth wall");
        assertFalse(lit(pixel(40, C)), "behind the depth wall");
        assertEquals(wall, depthAt(16, C), 1e-6f, "depth buffer untouched where a particle drew");
    }

    // ------------------------------------------------------------------------------------------------
    // Sprites and lighting
    // ------------------------------------------------------------------------------------------------

    @Test
    void atlasFramesSampleTheirOwnCellUprightFromTheImageTop() {
        // 2×2 atlas of single texels, image top row first: red, green / blue, white.
        int atlas = texture(2, 2, new int[]{0xFF0000FF, 0x00FF00FF, 0x0000FFFF, 0xFFFFFFFF});
        frame.atlas(atlas, 2, 2);
        int[][] expected = {{255, 0, 0}, {0, 255, 0}, {0, 0, 255}, {255, 255, 255}};
        for (int f = 0; f < 4; f++) {
            ParticleSystem s = new ParticleSystem(4);
            s.burst(still("frame" + f).atlas(f, 1, false).build(), 0, 0, -2, 1);
            clear(0, 0, 0);
            draw(s);
            assertRgb(pixel(C, C), expected[f][0], expected[f][1], expected[f][2], 1, "frame " + f);
        }
        glDeleteTextures(atlas);

        // One 1×2 sprite (red over green): its top must face up on screen.
        int upright = texture(1, 2, new int[]{0xFF0000FF, 0x00FF00FF});
        frame.atlas(upright, 1, 1);
        ParticleSystem s = new ParticleSystem(4);
        s.burst(still("upright").atlas(0, 1, false).build(), 0, 0, -2, 1);
        clear(0, 0, 0);
        draw(s);
        assertRgb(pixel(C, 36), 255, 0, 0, 1, "image top is the top of the quad");
        assertRgb(pixel(C, 28), 0, 255, 0, 1, "image bottom is the bottom of the quad");
        glDeleteTextures(upright);
    }

    @Test
    void atlasTransparencyCutsTheSpriteShape() {
        int atlas = texture(2, 1, new int[]{0xFFFFFFFF, 0xFFFFFF00});
        frame.atlas(atlas, 1, 1);
        ParticleSystem s = new ParticleSystem(4);
        s.burst(still("cut").atlas(0, 1, false).color(0x00FF00FF).build(), 0, 0, -2, 1);
        clear(0, 0, 0);
        draw(s);
        assertRgb(pixel(28, C), 0, 255, 0, 1, "opaque texel tinted by the particle colour");
        assertRgb(pixel(36, C), 0, 0, 0, 0, "transparent texel draws nothing");
        glDeleteTextures(atlas);
    }

    @Test
    void ambientDarkensLitParticlesButNotEmissiveOnes() {
        frame.ambient = 0.5f;
        ParticleSystem s = new ParticleSystem(4);
        ParticleEffect.Builder base = still("lit").sprite(ParticleEffect.Sprite.SQUARE).size(0.5f, 0.5f);
        s.burst(base.build(), -1, 0, -2, 1);                               // pixel 16
        s.burst(base.name("emissive").emissive(true).build(), 1, 0, -2, 1); // pixel 48
        clear(0, 0, 0);
        draw(s);
        assertRgb(pixel(16, C), 128, 128, 128, 2, "lit particle at half ambient");
        assertRgb(pixel(48, C), 255, 255, 255, 1, "emissive ignores ambient");
    }

    @Test
    void fogPullsCoverToTheFogColourAndFadesGlow() {
        frame.fogDensity = 1f;
        frame.fogColor.set(0, 1, 0);
        float fog = (float) (1 - Math.exp(-Math.sqrt(5))); // centres at (±1, 0, -2): distance √5
        int keep = Math.round(255 * (1 - fog));
        ParticleSystem s = new ParticleSystem(4);
        ParticleEffect.Builder base = still("fogged").sprite(ParticleEffect.Sprite.SQUARE).size(0.5f, 0.5f);
        s.burst(base.build(), -1, 0, -2, 1);
        s.burst(base.name("glow").additive(1).build(), 1, 0, -2, 1);
        clear(0, 0, 0);
        draw(s);
        assertRgb(pixel(16, C), keep, 255, keep, 3, "opaque white in green fog");
        assertRgb(pixel(48, C), keep, keep, keep, 3, "additive glow fades into fog");
    }

    // ------------------------------------------------------------------------------------------------
    // Resources, batching, state
    // ------------------------------------------------------------------------------------------------

    @Test
    void ringBufferStreamsManyFramesAndIsAccountedAndReleased() {
        GpuMemoryTracker tracker = GpuMemoryTracker.getInstance();
        long before = tracker.getBytes(GpuMemoryTracker.Category.PARTICLES);
        ParticleSystem s = new ParticleSystem(1000);
        ParticleEffect dot = still("dot").sprite(ParticleEffect.Sprite.SQUARE).size(0.2f, 0.2f).build();
        for (int f = 0; f < 3 * ParticleRenderer.SEGMENTS + 2; f++) {
            s.clearParticles();
            float x = -1.2f + 0.2f * f;
            s.burst(dot, x, 0, -2, 1);
            clear(0, 0, 0);
            assertEquals(1, draw(s));
            int px = Math.round((x / 2f + 1f) * 0.5f * SIZE);
            assertTrue(lit(pixel(px, C)), "frame " + f + " drew at its own position " + px);
            assertFalse(lit(pixel(px - 4, C)) || lit(pixel(px + 4, C)), "frame " + f + " shows no stale particle");
        }
        // Sized to what is visible (one particle → the minimum segment), not the 1000-particle budget.
        long expected = (long) ParticleRenderer.SEGMENTS * ParticleRenderer.MIN_SEGMENT_INSTANCES
                * ParticleVertexFormat.STRIDE;
        assertEquals(expected, renderer.gpuBytes());
        assertEquals(before + expected, tracker.getBytes(GpuMemoryTracker.Category.PARTICLES));
        renderer.close();
        renderer = null;
        assertEquals(before, tracker.getBytes(GpuMemoryTracker.Category.PARTICLES), "released on close");
    }

    @Test
    void sixtyFiveThousandMixedParticlesDrawInOneCall() {
        int n = 65_536;
        ParticleSystem s = new ParticleSystem(n);
        int atlas = texture(1, 1, new int[]{0xFFFFFFFF});
        frame.atlas(atlas, 1, 1);
        ParticleEffect[] mix = {
                still("soft").size(0.02f, 0.02f).build(),
                still("sq").sprite(ParticleEffect.Sprite.SQUARE).size(0.02f, 0.02f).color(0xFF000080).build(),
                still("add").size(0.02f, 0.02f).additive(1).emissive(true).build(),
                still("atlas").atlas(0, 1, false).size(0.02f, 0.02f).build()};
        for (int i = 0; i < 4; i++) {
            s.burst(mix[i].toBuilder().box(1.5f, 1.5f, 0.5f).priority(ParticleEffect.Priority.CRITICAL).build(),
                    0, 0, -4, n / 4);
        }
        assertEquals(n, s.liveCount());
        long uploaded = renderer.uploadedBytesTotal();
        clear(0, 0, 0);
        assertEquals(n, draw(s));
        assertEquals(1, renderer.lastDrawCalls(), "every effect, sprite and blend style in one draw");
        assertEquals((long) n * ParticleVertexFormat.STRIDE, renderer.uploadedBytesTotal() - uploaded);
        assertTrue(renderer.builder().sortedLastBuild());
        glDeleteTextures(atlas);
    }

    @Test
    void nothingVisibleMeansNoUploadAndNoDraw() {
        ParticleSystem s = new ParticleSystem(4);
        s.burst(square("behind", 0xFFFFFFFF), 0, 0, 3, 1);
        clear(0, 0, 0);
        assertEquals(0, draw(s));
        assertEquals(0, renderer.lastDrawCalls());
        assertEquals(0, renderer.uploadedBytesTotal());
        assertEquals(0, renderer.gpuBytes(), "the ring is created lazily on the first visible frame");
    }

    @Test
    void renderRestoresTheGlStateItTouches() {
        ParticleSystem s = new ParticleSystem(4);
        s.burst(square("state", 0xFFFFFFFF), 0, 0, -2, 1);
        int vao = glGenVertexArrays();
        int buffer = glGenBuffers();
        int tex = texture(1, 1, new int[]{0xFFFFFFFF});
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, buffer);
        int tex1 = texture(1, 1, new int[]{0xFFFFFFFF});
        glActiveTexture(GL_TEXTURE0 + ParticleRenderer.DEPTH_UNIT);
        glBindTexture(GL_TEXTURE_2D, tex1);
        glActiveTexture(GL_TEXTURE0 + ParticleRenderer.ATLAS_UNIT);
        glBindTexture(GL_TEXTURE_2D, tex);
        glActiveTexture(GL_TEXTURE3);
        glDisable(GL_BLEND);
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);
        glDepthMask(true);
        glEnable(GL_CULL_FACE);
        glDisable(GL_DEPTH_TEST);
        clear(0, 0, 0);
        assertEquals(1, draw(s));

        assertEquals(0, glGetInteger(GL_CURRENT_PROGRAM));
        assertEquals(vao, glGetInteger(GL_VERTEX_ARRAY_BINDING));
        assertEquals(buffer, glGetInteger(GL_ARRAY_BUFFER_BINDING));
        assertEquals(GL_TEXTURE3, glGetInteger(GL_ACTIVE_TEXTURE));
        glActiveTexture(GL_TEXTURE0 + ParticleRenderer.ATLAS_UNIT);
        assertEquals(tex, glGetInteger(GL_TEXTURE_BINDING_2D));
        glActiveTexture(GL_TEXTURE0 + ParticleRenderer.DEPTH_UNIT);
        assertEquals(tex1, glGetInteger(GL_TEXTURE_BINDING_2D));
        assertFalse(glIsEnabled(GL_BLEND));
        assertEquals(GL_SRC_ALPHA, glGetInteger(GL_BLEND_SRC_RGB));
        assertEquals(GL_ONE_MINUS_SRC_ALPHA, glGetInteger(GL_BLEND_DST_RGB));
        assertEquals(GL_ZERO, glGetInteger(GL_BLEND_SRC_ALPHA));
        assertEquals(GL_ONE, glGetInteger(GL_BLEND_DST_ALPHA));
        assertTrue(glGetBoolean(GL_DEPTH_WRITEMASK));
        assertTrue(glIsEnabled(GL_CULL_FACE));
        assertFalse(glIsEnabled(GL_DEPTH_TEST));

        glBindVertexArray(0);
        glDeleteVertexArrays(vao);
        glDeleteBuffers(buffer);
        glDeleteTextures(tex);
        glDeleteTextures(tex1);
    }

    @Test
    void ringFollowsTheVisibleCountGrowingAndShrinkingBack() {
        ParticleEffect dot = still("ring").sprite(ParticleEffect.Sprite.SQUARE).size(0.01f, 0.01f)
                .box(1, 1, 0).priority(ParticleEffect.Priority.CRITICAL).build();
        long minimum = (long) ParticleRenderer.SEGMENTS * ParticleRenderer.MIN_SEGMENT_INSTANCES * ParticleVertexFormat.STRIDE;
        ParticleSystem s = new ParticleSystem(200_000);
        s.burst(dot, 0, 0, -4, 10);
        clear(0, 0, 0);
        draw(s);
        assertEquals(minimum, renderer.gpuBytes(), "a 200k budget with 10 visible holds the minimum ring");

        s.burst(dot, 0, 0, -4, 5000);
        draw(s);
        assertEquals(8192, renderer.segmentInstances(), "grew to the next power of two");
        assertEquals((long) ParticleRenderer.SEGMENTS * 8192 * ParticleVertexFormat.STRIDE, renderer.gpuBytes());

        s.clearParticles();
        s.burst(dot, 0, 0, -4, 10);
        int reallocations = renderer.reallocations();
        // The window holding the 5000-particle frame keeps the ring; the next clean window shrinks it.
        for (int f = 0; f < 2 * ParticleRenderer.SHRINK_WINDOW_FRAMES; f++) {
            draw(s);
        }
        assertEquals(minimum, renderer.gpuBytes(), "shrank back after a full window of light use");
        assertEquals(reallocations + 1, renderer.reallocations());
        clear(0, 0, 0);
        draw(s);
        assertTrue(lit(pixel(C, C)) || renderer.lastDrawn() == 10, "still draws after shrinking");
    }

    // ------------------------------------------------------------------------------------------------
    // Orientations
    // ------------------------------------------------------------------------------------------------

    @Test
    void velocityStretchedParticlesStreakBackAlongTheirMotion() {
        ParticleSystem s = new ParticleSystem(4);
        // Moving +X at 5 b/s with stretch 0.2: a 1-block trail behind it (x from -1 to 0 at z = -2).
        s.burst(still("streak").sprite(ParticleEffect.Sprite.SQUARE).size(0.1f, 0.1f)
                .speed(5, 5).direction(1, 0, 0).stretched(0.2f).build(), 0, 0, -2, 1);
        clear(0, 0, 0);
        draw(s);
        assertTrue(lit(pixel(18, C)) && lit(pixel(25, C)) && lit(pixel(31, C)), "the trail behind it");
        assertFalse(lit(pixel(36, C)), "nothing ahead of the head");
        assertFalse(lit(pixel(25, C + 4)), "a thin streak, not a big quad");
    }

    @Test
    void axisLockedQuadsStayUprightAndFlatQuadsLieDown() {
        ParticleEffect upright = still("upright").sprite(ParticleEffect.Sprite.SQUARE)
                .orientation(ParticleEffect.Orientation.AXIS_Y).build();
        ParticleEffect flat = still("flat").sprite(ParticleEffect.Sprite.SQUARE)
                .orientation(ParticleEffect.Orientation.FLAT).build();
        ParticleEffect facing = still("facing").sprite(ParticleEffect.Sprite.SQUARE).build();

        // Looking along -Z: flat quads are edge-on, upright and camera-facing quads are full squares.
        assertEquals(256, coverage(upright, false), 8);
        assertEquals(256, coverage(facing, false), 8);
        assertTrue(coverage(flat, false) < 20, "flat quad seen edge-on");

        // Looking straight down: upright quads are edge-on, flat and camera-facing quads are full squares.
        assertTrue(coverage(upright, true) < 20, "upright quad seen from above");
        assertEquals(256, coverage(flat, true), 8);
        assertEquals(256, coverage(facing, true), 8);
    }

    /** Lit pixels of one quad 2 blocks in front of the camera, viewed horizontally or from straight above. */
    private int coverage(ParticleEffect effect, boolean fromAbove) {
        ParticleSystem s = new ParticleSystem(4);
        if (fromAbove) {
            s.burst(effect, 0, -2, 0, 1);
            frame.camera(new Matrix4f().lookAt(0, 0, 0, 0, -1, 0, 0, 0, -1), projection, 0, 0, 0);
        } else {
            s.burst(effect, 0, 0, -2, 1);
            frame.camera(new Matrix4f(), projection, 0, 0, 0);
        }
        clear(0, 0, 0);
        draw(s);
        int lit = 0;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                if (lit(pixel(x, y))) lit++;
            }
        }
        frame.camera(new Matrix4f(), projection, 0, 0, 0);
        return lit;
    }

    // ------------------------------------------------------------------------------------------------
    // Scene integration: soft particles, host lighting, split around water
    // ------------------------------------------------------------------------------------------------

    @Test
    void softParticlesFadeWhereTheyMeetSceneGeometry() {
        int depthTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, depthTex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, SIZE, SIZE, 0, GL_DEPTH_COMPONENT, GL_UNSIGNED_INT,
                (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glBindTexture(GL_TEXTURE_2D, 0);
        int depthFbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, depthFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depthTex, 0);
        glDrawBuffer(GL_NONE);
        glReadBuffer(GL_NONE);
        assertEquals(GL_FRAMEBUFFER_COMPLETE, glCheckFramebufferStatus(GL_FRAMEBUFFER));

        ParticleSystem s = new ParticleSystem(4);
        ParticleEffect.Builder base = still("soft").sprite(ParticleEffect.Sprite.SQUARE).size(0.5f, 0.5f);
        s.burst(base.softness(1f).build(), -1, 0, -2, 1);   // pixel 16
        s.burst(base.name("hard").softness(0f).build(), 1, 0, -2, 1); // pixel 48
        for (float[] c : new float[][]{{-2.2f, 51}, {-2.5f, 128}, {-50f, 255}}) {
            // "Scene" = a wall at depth c[0] in both the sampled depth texture and the target's depth buffer.
            float wall = windowDepthOf(c[0]);
            glBindFramebuffer(GL_FRAMEBUFFER, depthFbo);
            glClearDepth(wall);
            glClear(GL_DEPTH_BUFFER_BIT);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glClearColor(0, 0, 0, 1);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            glClearDepth(wall);
            glClear(GL_DEPTH_BUFFER_BIT);
            frame.depthTexture = depthTex;
            draw(s);
            assertRgb(pixel(16, C), (int) c[1], (int) c[1], (int) c[1], 4,
                    "soft particle 2 blocks deep, wall at " + -c[0]);
            assertRgb(pixel(48, C), 255, 255, 255, 1, "softness 0 keeps hard edges");
        }
        frame.depthTexture = 0;
        glClearDepth(1.0);
        clear(0, 0, 0);
        draw(s);
        assertRgb(pixel(16, C), 255, 255, 255, 1, "no depth texture = soft particles off");
        glDeleteFramebuffers(depthFbo);
        glDeleteTextures(depthTex);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    }

    @Test
    void hostLightingExtensionLightsNonEmissiveParticles() {
        renderer.close();
        renderer = new ParticleRenderer(new ParticleShaderExtension("""
                uniform vec3 u_hostLight;
                vec3 particleLight(vec3 renderPos) { return u_hostLight * (renderPos.x < 0.0 ? 1.0 : 0.5); }
                """));
        frame.uniforms = shader -> shader.setVec3("u_hostLight", new org.joml.Vector3f(1f, 0.5f, 0f));
        ParticleSystem s = new ParticleSystem(4);
        ParticleEffect.Builder base = still("lit").sprite(ParticleEffect.Sprite.SQUARE).size(0.5f, 0.5f);
        s.burst(base.build(), -1, 0, -2, 1);
        s.burst(base.name("half").build(), 1, 0.6, -2, 1);
        s.burst(base.name("glow").emissive(true).build(), 1, -0.6, -2, 1);
        clear(0, 0, 0);
        draw(s);
        frame.uniforms = null;
        assertRgb(pixel(16, C), 255, 128, 0, 2, "host light colour");
        assertRgb(pixel(48, 41), 128, 64, 0, 2, "host light varies with position");
        assertRgb(pixel(48, 23), 255, 255, 255, 1, "emissive ignores host light");
    }

    @Test
    void splitDrawsParticlesBehindAndInFrontOfAWaterSurfaceFromOneUpload() {
        ParticleSystem s = new ParticleSystem(8);
        ParticleEffect.Builder base = still("w").sprite(ParticleEffect.Sprite.SQUARE).size(0.5f, 0.5f);
        s.burst(base.color(0xFF0000FF).build(), -1, -0.6, -2, 1);          // under water, left
        s.burst(base.name("above").color(0x00FF00FF).build(), 1, 0.6, -2, 1); // above, right
        frame.splitAtHeight(0f); // camera at y = 0: on the surface, counts as above
        long uploaded = renderer.uploadedBytesTotal();
        clear(0, 0, 0);
        assertEquals(2, renderer.prepare(s, frame));
        assertEquals(1, renderer.drawBehind());
        assertTrue(lit(pixel(16, 23)), "underwater particle in the behind pass");
        assertFalse(lit(pixel(48, 41)), "above-water particle not yet");
        // "Water": tint everything drawn so far, as a translucent surface would.
        glEnable(GL_SCISSOR_TEST);
        glScissor(0, 0, SIZE, SIZE);
        glClearColor(0, 0, 1, 1);
        assertEquals(1, renderer.drawFront());
        glDisable(GL_SCISSOR_TEST);
        assertTrue(lit(pixel(48, 41)), "above-water particle in the front pass");
        assertEquals(2, renderer.lastDrawCalls());
        assertEquals(2L * ParticleVertexFormat.STRIDE, renderer.uploadedBytesTotal() - uploaded, "uploaded once");
        frame.noSplit();
    }

    /** RGBA8 texture from {@code 0xRRGGBBAA} texels, first row = image top; nearest filtering. */
    private static int texture(int w, int h, int[] rgba) {
        int tex = glGenTextures();
        int previousActive = glGetInteger(GL_ACTIVE_TEXTURE);
        glActiveTexture(GL_TEXTURE7);
        glBindTexture(GL_TEXTURE_2D, tex);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer data = stack.malloc(w * h * 4);
            for (int i = 0; i < w * h; i++) {
                data.put((byte) (rgba[i] >>> 24)).put((byte) (rgba[i] >>> 16)).put((byte) (rgba[i] >>> 8))
                        .put((byte) rgba[i]);
            }
            data.flip();
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, data);
        }
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindTexture(GL_TEXTURE_2D, 0);
        glActiveTexture(previousActive);
        return tex;
    }
}
