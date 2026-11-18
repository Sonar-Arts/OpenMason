package com.openmason.engine.voxel.mms.mmsRegion;

import com.openmason.engine.cearl.CearlDispatcher;
import com.openmason.engine.rendering.RenderOrigin;
import com.openmason.engine.voxel.mms.mmsCore.MmsSharedQuadIndexBuffer;
import com.openmason.engine.voxel.mms.mmsCore.MmsStagingRing;
import com.openmason.engine.voxel.mms.mmsCore.MmsVertexFormat;
import com.openmason.engine.vram.VramPlans;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43C.*;

/**
 * Hi-Z two-phase occlusion on a real GL context (opt in with {@code -Dopenmason.mms.gl=true}):
 * a wall's depth hides the boxes behind it, the boxes beside it are drawn, and over a sweep of
 * camera moves (history carried frame to frame) nothing a hardware occlusion query sees is ever
 * left undrawn — every in-frustum enabled box the query finds visible is drawn by phase 1 or
 * phase 2 of that very frame. The GPU pyramid equals its CPU twin, the GPU verdicts equal
 * {@link MmsHiZPyramid#occluded}, and the whole scene sits at ±2^40 (render space). The GPU-built
 * pyramid equals the CPU reduction of what {@code glReadPixels(DEPTH_COMPONENT, FLOAT)} returns (the
 * first capture path) bit for bit, for every depth format through both capture paths, with the
 * caller's GL state restored; and a 1080p capture costs the render thread well under 0.2 ms.
 */
class MmsHiZGlTest {

    private static final int W = 250;   // odd-sized pyramid edges on purpose
    private static final int H = 187;
    private static final long BASE = 1L << 40;

    private long window;
    private final List<MmsChunkRegion> regions = new ArrayList<>();
    private int fbo;
    private int colorRb;
    private int depthTex;
    private int program;
    private int vao;
    private int vbo;
    private int query;

    @BeforeEach
    void createContext() {
        assumeTrue(Boolean.getBoolean("openmason.mms.gl"), "opt in with -Dopenmason.mms.gl=true");
        assertTrue(glfwInit(), "GLFW display unavailable");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        window = glfwCreateWindow(16, 16, "Hi-Z test", 0, 0);
        assertNotEquals(0, window);
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        assumeTrue(MmsGpuCuller.isSupported(), "needs OpenGL 4.3");
        RenderOrigin.reset();

        fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        colorRb = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, colorRb);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, W, H);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, colorRb);
        depthTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, depthTex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT32F, W, H, 0, GL_DEPTH_COMPONENT, GL_FLOAT,
            (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depthTex, 0);
        assertEquals(GL_FRAMEBUFFER_COMPLETE, glCheckFramebufferStatus(GL_FRAMEBUFFER));
        glViewport(0, 0, W, H);

        program = glCreateProgram();
        int vs = shader(GL_VERTEX_SHADER, """
            #version 430 core
            layout(location = 0) in vec3 p;
            uniform mat4 vp;
            void main() { gl_Position = vp * vec4(p, 1.0); }
            """);
        int fs = shader(GL_FRAGMENT_SHADER, """
            #version 430 core
            out vec4 color;
            void main() { color = vec4(1.0); }
            """);
        glAttachShader(program, vs);
        glAttachShader(program, fs);
        glLinkProgram(program);
        assertEquals(GL_TRUE, glGetProgrami(program, GL_LINK_STATUS), glGetProgramInfoLog(program));
        glDeleteShader(vs);
        glDeleteShader(fs);
        vao = glGenVertexArrays();
        vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 12, 0L);
        glEnableVertexAttribArray(0);
        glBindVertexArray(0);
        query = glGenQueries();
    }

    private static int shader(int type, String src) {
        int s = glCreateShader(type);
        glShaderSource(s, src);
        glCompileShader(s);
        assertEquals(GL_TRUE, glGetShaderi(s, GL_COMPILE_STATUS), glGetShaderInfoLog(s));
        return s;
    }

    @AfterEach
    void destroyContext() {
        if (window == 0) {
            return;
        }
        for (MmsChunkRegion r : regions) {
            r.delete();
        }
        MmsStagingRing ring = MmsStagingRing.sharedInstance();
        if (ring != null) {
            ring.close();
        }
        MmsSharedQuadIndexBuffer.invalidate();
        RenderOrigin.reset();
        if (program != 0) {
            glDeleteProgram(program);
            glDeleteVertexArrays(vao);
            glDeleteBuffers(vbo);
            glDeleteQueries(query);
            glDeleteFramebuffers(fbo);
            glDeleteRenderbuffers(colorRb);
            glDeleteTextures(depthTex);
        }
        int error = glGetError();
        GL.setCapabilities(null);
        glfwDestroyWindow(window);
        glfwTerminate();
        window = 0;
        assertEquals(GL_NO_ERROR, error, "GL error during the test");
    }

    // ─── Scene ─────────────────────────────────────────────────────────────────────────────────────

    /** A scene box in coordinates relative to (BASE, BASE, BASE) = the region corner. */
    private record Box(float x0, float y0, float z0, float x1, float y1, float z1, boolean enabled) {
    }

    private static final Box WALL = new Box(-6, -5, -10.5f, 6, 5, -10, true);

    private static List<Box> scene() {
        List<Box> boxes = new ArrayList<>();
        boxes.add(WALL);
        for (int i = 0; i < 5; i++) {
            float x = -4 + i * 2;
            boxes.add(new Box(x - 0.5f, -1, -20 - i, x + 0.5f, 1, -19 - i, true));  // behind the wall
        }
        for (int i = 0; i < 4; i++) {
            float x = 8 + i * 4f;
            boxes.add(new Box(x, -1, -22, x + 1, 1, -21, true));                  // right of it
            boxes.add(new Box(-x - 1, -1, -22, -x, 1, -21, true));                // left of it
        }
        boxes.add(new Box(-0.5f, 6, -30, 0.5f, 7, -29, true));                    // above it
        boxes.add(new Box(-1, -1, -40, 1, 1, -39, false));                       // disabled, behind
        boxes.add(new Box(12, -1, -15, 13, 1, -14, false));                      // disabled, in the open
        boxes.add(new Box(-0.2f, -0.2f, -5, 0.2f, 0.2f, -4.6f, true));           // in front of the wall
        boxes.add(new Box(-1, -1, 30, 1, 1, 31, true));                          // behind the camera
        return boxes;
    }

    private MmsChunkRegion buildRegion(List<Box> boxes) {
        MmsChunkRegion region = new MmsChunkRegion(MmsRegionKey.ofBlock(BASE, BASE, BASE), MmsVertexFormat.QUAD16,
            VramPlans.CUBE_REGION_MESH);
        regions.add(region);
        assertEquals(BASE, region.originBlockX());
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment quad = arena.allocate(16L, 16);
            for (Box b : boxes) {
                MmsRegionMeshHandle h = region.uploadQuads(quad, 1, b.x0, b.y0, b.z0, b.x1, b.y1, b.z1);
                h.setEnabled(b.enabled);
            }
        }
        return region;
    }

    /** Render-space view-projection of a camera at scene position {@code cam} looking along {@code dir}. */
    private static Matrix4f viewProj(Vector3f cam, Vector3f dir) {
        RenderOrigin.update((double) BASE + cam.x, (double) BASE + cam.y, (double) BASE + cam.z);
        Vector3f eye = RenderOrigin.relative((double) BASE + cam.x, (double) BASE + cam.y,
            (double) BASE + cam.z, new Vector3f());
        return new Matrix4f().perspective((float) Math.toRadians(70), (float) W / H, 0.1f, 500f)
            .lookAt(eye, new Vector3f(eye).add(dir), new Vector3f(0, 1, 0));
    }

    private static float[] planes(Matrix4f vp) {
        float[] planes = new float[24];
        Vector4f p = new Vector4f();
        for (int i = 0; i < 6; i++) {
            vp.frustumPlane(i, p);
            planes[i * 4] = p.x;
            planes[i * 4 + 1] = p.y;
            planes[i * 4 + 2] = p.z;
            planes[i * 4 + 3] = p.w;
        }
        return planes;
    }

    /** The six faces of a box in render space (36 vertices). */
    private static float[] boxTriangles(Box b, Vector3f o) {
        float x0 = b.x0 + o.x, y0 = b.y0 + o.y, z0 = b.z0 + o.z;
        float x1 = b.x1 + o.x, y1 = b.y1 + o.y, z1 = b.z1 + o.z;
        float[][] c = {
            {x0, y0, z0}, {x1, y0, z0}, {x1, y1, z0}, {x0, y1, z0},
            {x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}};
        int[] faces = {0, 1, 2, 0, 2, 3, 4, 6, 5, 4, 7, 6, 0, 4, 5, 0, 5, 1,
            3, 2, 6, 3, 6, 7, 0, 3, 7, 0, 7, 4, 1, 5, 6, 1, 6, 2};
        float[] out = new float[faces.length * 3];
        for (int i = 0; i < faces.length; i++) {
            System.arraycopy(c[faces[i]], 0, out, i * 3, 3);
        }
        return out;
    }

    private void drawTriangles(float[] vertices, Matrix4f vp) {
        glUseProgram(program);
        FloatBuffer m = BufferUtils.createFloatBuffer(16);
        vp.get(m);
        glUniformMatrix4fv(glGetUniformLocation(program, "vp"), false, m);
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STREAM_DRAW);
        glDrawArrays(GL_TRIANGLES, 0, vertices.length / 3);
        glBindVertexArray(0);
        glUseProgram(0);
    }

    /** Whether any fragment of the box passes GL_LESS against the current depth (hardware truth). */
    private boolean querySeesBox(Box b, Vector3f o, Matrix4f vp) {
        glDepthMask(false);
        glColorMask(false, false, false, false);
        glBeginQuery(GL_ANY_SAMPLES_PASSED, query);
        drawTriangles(boxTriangles(b, o), vp);
        glEndQuery(GL_ANY_SAMPLES_PASSED);
        glDepthMask(true);
        glColorMask(true, true, true, true);
        return glGetQueryObjecti(query, GL_QUERY_RESULT) != 0;
    }

    private static int[] instanceCounts(MmsChunkRegion region, int count) {
        if (region.gpuCmdBufferId == 0) {
            return new int[count];
        }
        ByteBuffer b = readBack(region.gpuCmdBufferId, region.gpuCmdOffsetBytes, count * 20)
            .order(ByteOrder.nativeOrder());
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            out[i] = b.getInt(i * 20 + 4);
        }
        return out;
    }

    private static int[] visBits(MmsChunkRegion region, int count) {
        ByteBuffer b = readBack(region.hizVisBufferId, 0, count * 4).order(ByteOrder.nativeOrder());
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            out[i] = b.getInt(i * 4);
        }
        return out;
    }

    /** The framebuffer's depth as {@code glReadPixels(DEPTH_COMPONENT, FLOAT)} returns it (row 0 = bottom). */
    private static float[] readDepth(int framebuffer, int x, int y, int w, int h) {
        glMemoryBarrier(GL_ALL_BARRIER_BITS);
        int prev = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, framebuffer);
        FloatBuffer px = BufferUtils.createFloatBuffer(w * h);
        glPixelStorei(GL_PACK_ALIGNMENT, 4);
        glReadPixels(x, y, w, h, GL_DEPTH_COMPONENT, GL_FLOAT, px);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, prev);
        float[] out = new float[w * h];
        px.get(out);
        return out;
    }

    private static void assertPyramidEquals(float[] expected, MmsHiZPyramid pyramid, String what) {
        assertPyramidEquals(expected, pyramid, what, 0);
    }

    /** Bit-exact unless {@code ulps} > 0 (allowed distance in float ulps). */
    private static void assertPyramidEquals(float[] expected, MmsHiZPyramid pyramid, String what, int ulps) {
        assertTrue(pyramid.isValid(), what + ": pyramid built");
        int total = expected.length;
        FloatBuffer gpu = readBack(pyramid.bufferId(), 0, total * 4).asFloatBuffer();
        for (int i = 0; i < total; i++) {
            float g = gpu.get(i);
            int distance = Math.abs(Float.floatToRawIntBits(g) - Float.floatToRawIntBits(expected[i]));
            if (distance > ulps) {
                assertEquals(expected[i], g, what + ": pyramid element " + i);
            }
        }
    }

    private static ByteBuffer readBack(int buffer, long offset, int length) {
        glMemoryBarrier(GL_ALL_BARRIER_BITS);
        glFinish();
        ByteBuffer out = ByteBuffer.allocateDirect(length).order(ByteOrder.nativeOrder());
        glBindBuffer(GL_COPY_READ_BUFFER, buffer);
        glGetBufferSubData(GL_COPY_READ_BUFFER, offset, out);
        glBindBuffer(GL_COPY_READ_BUFFER, 0);
        return out;
    }

    /** One two-phase camera frame; returns {early, late, vis} per mesh. */
    private int[][] frame(MmsGpuCuller culler, MmsChunkRegion region, Matrix4f vp, boolean viaTexture) {
        int n = region.gpuCommandCount();
        Vector3f o = region.renderOrigin(new Vector3f());
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glViewport(0, 0, W, H);
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LESS);
        glClearDepth(1.0);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        culler.beginEarlyPass(planes(vp));
        culler.cull(region);
        culler.endCull();
        int[] early = instanceCounts(region, n);
        culler.endDraw();
        // Phase 1's "draw": the wall is the only geometry with depth (it is drawn whenever phase 1 drew it,
        // and always on frame one); the other boxes are probes, not occluders.
        drawTriangles(boxTriangles(WALL, o), vp);

        if (viaTexture) {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            culler.buildHiZFromTexture(depthTex, W, H, vp);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        } else {
            culler.buildHiZ(fbo, 0, 0, W, H, vp);
        }
        culler.beginLatePass();
        culler.cull(region);
        culler.endCull();
        int[] late = instanceCounts(region, n);
        culler.endDraw();
        int[] vis = region.hizVisBufferId == 0 ? new int[n] : visBits(region, n);
        return new int[][]{early, late, vis};
    }

    @Test
    void wallHidesWhatIsBehindItAndCameraMovesNeverCullAnythingVisible() {
        List<Box> boxes = scene();
        MmsChunkRegion region = buildRegion(boxes);
        int n = boxes.size();
        int occludedSeen = 0;
        int lateDrawsSeen = 0;
        int frames = 0;
        try (MmsGpuCuller culler = new MmsGpuCuller()) {
            assertTrue(culler.isHiZEnabled());
            List<Vector3f[]> poses = new ArrayList<>();
            poses.add(new Vector3f[]{new Vector3f(0, 0, 0), new Vector3f(0, 0, -1)});
            poses.add(new Vector3f[]{new Vector3f(0, 0, 0), new Vector3f(0, 0, -1)});
            for (int s = 0; s <= 40; s++) {   // strafe right past the wall's edge, then back left
                float x = s <= 20 ? s * 0.7f : (40 - s) * -0.7f;
                poses.add(new Vector3f[]{new Vector3f(x, 0.3f * (s % 3), -s * 0.1f),
                    new Vector3f((s % 5 - 2) * 0.05f, 0, -1).normalize()});
            }
            for (int s = 0; s < 12; s++) {     // turn on the spot (things enter from the frustum edge)
                double yaw = Math.toRadians(-30 + s * 6);
                poses.add(new Vector3f[]{new Vector3f(0, 0, 2),
                    new Vector3f((float) Math.sin(yaw), 0, (float) -Math.cos(yaw))});
            }
            for (Vector3f[] pose : poses) {
                Matrix4f vp = viewProj(pose[0], pose[1]);
                float[] planes = planes(vp);
                int[][] r = frame(culler, region, vp, frames % 2 == 0);
                int[] early = r[0], late = r[1], vis = r[2];
                Vector3f o = region.renderOrigin(new Vector3f());

                // The pyramid: GPU levels == CPU twin of the depth as glReadPixels(FLOAT) sees it.
                MmsHiZPyramid pyramid = culler.pyramid();
                float[] cpuPyramid = MmsHiZPyramid.reduce(readDepth(fbo, 0, 0, W, H), W, H);
                assertPyramidEquals(cpuPyramid, pyramid, "frame " + frames);

                for (int i = 0; i < n; i++) {
                    Box b = boxes.get(i);
                    boolean inFrustum = MmsGpuCuller.aabbVisible(planes, o.x, o.y, o.z,
                        b.x0, b.y0, b.z0, b.x1, b.y1, b.z1);
                    String what = "frame " + frames + " mesh " + i;
                    assertFalse(early[i] != 0 && late[i] != 0, what + ": drawn twice");
                    if (!b.enabled || !inFrustum) {
                        assertEquals(0, early[i] + late[i], what + ": disabled/out of frustum but drawn");
                        assertEquals(0, vis[i], what + ": history of an undrawable mesh");
                        continue;
                    }
                    boolean cpuOccluded = MmsHiZPyramid.occluded(cpuPyramid, W, H, vp, o.x, o.y, o.z,
                        b.x0, b.y0, b.z0, b.x1, b.y1, b.z1);
                    assertEquals(cpuOccluded ? 0 : 1, vis[i], what + ": GPU verdict == CPU twin");
                    boolean drawn = early[i] != 0 || late[i] != 0;
                    if (i != 0 && querySeesBox(b, o, vp)) {
                        assertTrue(drawn, what + ": visible to an occlusion query but culled");
                        assertEquals(1, vis[i], what + ": visible to an occlusion query but marked occluded");
                    }
                    if (vis[i] == 0) {
                        occludedSeen++;
                    }
                    if (late[i] != 0) {
                        lateDrawsSeen++;
                    }
                }
                frames++;
            }
            assertTrue(occludedSeen > 20, "the wall must hide boxes: " + occludedSeen);
            assertTrue(lateDrawsSeen > 3, "moves must reveal boxes for phase 2: " + lateDrawsSeen);

            glFinish();
            MmsGpuCuller.HiZStats stats = culler.hizStats();
            assertTrue(stats.frame() > 0, "counters read back: " + stats);
            assertTrue(stats.tested() > 0 && stats.tested() >= stats.occluded(), stats.toString());
        }
    }

    @Test
    void historyDecidesPhaseOneAndAListChangeNeverHidesAnything() {
        List<Box> boxes = scene();
        MmsChunkRegion region = buildRegion(boxes);
        int n = boxes.size();
        try (MmsGpuCuller culler = new MmsGpuCuller()) {
            Matrix4f vp = viewProj(new Vector3f(0, 0, 0), new Vector3f(0, 0, -1));
            int[][] first = frame(culler, region, vp, false);
            int[][] second = frame(culler, region, vp, false);
            for (int i = 0; i < n; i++) {
                assertEquals(first[2][i], second[0][i], "frame 2 phase 1 = frame 1 history, mesh " + i);
                assertEquals(0, second[1][i], "a static view leaves nothing for phase 2, mesh " + i);
            }
            int behind = 1; // first box behind the wall
            assertEquals(1, first[0][behind], "no history: phase 1 draws the whole frustum");
            assertEquals(0, first[2][behind], "the wall hides it");
            assertEquals(0, second[0][behind] + second[1][behind], "frame 2 skips it in both phases");
            int beside = 10; // right of the wall at x 16..17, z -22
            assertEquals(1, first[2][beside], "the box beside the wall is visible");
            assertEquals(1, second[0][beside], "and drawn by phase 1 from then on");

            // A mesh uploaded between the phases: phase 2 may no longer trust what phase 1 drew -> it draws
            // every visible mesh (doubles are harmless, holes are not).
            culler.beginEarlyPass(planes(vp));
            culler.cull(region);
            culler.endCull();
            culler.endDraw();
            try (Arena arena = Arena.ofConfined()) {
                region.uploadQuads(arena.allocate(16L, 16), 1, 19, -1, -31, 20, 1, -30);
            }
            drawTriangles(boxTriangles(WALL, region.renderOrigin(new Vector3f())), vp);
            culler.buildHiZ(fbo, 0, 0, W, H, vp);
            culler.beginLatePass();
            culler.cull(region);
            culler.endCull();
            int[] late = instanceCounts(region, n + 1);
            culler.endDraw();
            int[] vis = visBits(region, n + 1);
            for (int i = 0; i <= n; i++) {
                assertEquals(vis[i], late[i], "list changed: phase 2 draws every visible mesh, mesh " + i);
            }
            assertEquals(1, vis[n], "the new mesh is visible (and drawn by phase 2)");
        }
    }

    @Test
    void disabledHiZIsAPlainFrustumPassAndPhaseTwoDrawsNothing() {
        List<Box> boxes = scene();
        MmsChunkRegion region = buildRegion(boxes);
        int n = boxes.size();
        try (MmsGpuCuller culler = new MmsGpuCuller()) {
            culler.setHiZEnabled(false);
            Matrix4f vp = viewProj(new Vector3f(0, 0, 0), new Vector3f(0, 0, -1));
            float[] planes = planes(vp);
            Vector3f o = region.renderOrigin(new Vector3f());
            for (int f = 0; f < 2; f++) {
                int[][] r = frame(culler, region, vp, false);
                for (int i = 0; i < n; i++) {
                    Box b = boxes.get(i);
                    boolean expected = b.enabled && MmsGpuCuller.aabbVisible(planes, o.x, o.y, o.z,
                        b.x0, b.y0, b.z0, b.x1, b.y1, b.z1);
                    assertEquals(expected ? 1 : 0, r[0][i], "frustum verdict, mesh " + i);
                    assertEquals(0, r[1][i], "phase 2 is empty without Hi-Z, mesh " + i);
                }
            }
            assertEquals(MmsGpuCuller.HiZStats.NONE, culler.hizStats());
        }
    }

    // ─── Seed pass: formats, sub-rectangles, state, cost ───────────────────────────────────────────

    private record DepthSource(String name, int format, boolean texture) {
        boolean packed() {
            return format == GL_DEPTH24_STENCIL8 || format == GL_DEPTH32F_STENCIL8;
        }

        /**
         * Allowed float-ulp distance from glReadPixels: D24/D32F convert identically (bit-exact, the formats
         * the engine uses); the driver's unorm16 -> float in texelFetch may round 1 ulp differently than its
         * pixel-pack path (far below D16's own 1/65535 step).
         */
        int ulps() {
            return format == GL_DEPTH_COMPONENT16 ? 1 : 0;
        }
    }

    private static final List<DepthSource> SOURCES = List.of(
        new DepthSource("D24 texture", GL_DEPTH_COMPONENT24, true),
        new DepthSource("D24S8 texture", GL_DEPTH24_STENCIL8, true),
        new DepthSource("D32F texture", GL_DEPTH_COMPONENT32F, true),
        new DepthSource("D32FS8 texture", GL_DEPTH32F_STENCIL8, true),
        new DepthSource("D16 texture", GL_DEPTH_COMPONENT16, true),
        new DepthSource("D24 renderbuffer", GL_DEPTH_COMPONENT24, false),
        new DepthSource("D24S8 renderbuffer", GL_DEPTH24_STENCIL8, false),
        new DepthSource("D32F renderbuffer", GL_DEPTH_COMPONENT32F, false),
        new DepthSource("D32FS8 renderbuffer", GL_DEPTH32F_STENCIL8, false),
        new DepthSource("D16 renderbuffer", GL_DEPTH_COMPONENT16, false));

    /** A colour + depth target of {@code w x h}; returns {fbo, colorRb, depth object}. */
    private static int[] target(DepthSource src, int w, int h) {
        int fb = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fb);
        int color = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, color);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, w, h);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, color);
        int attach = src.packed() ? GL_DEPTH_STENCIL_ATTACHMENT : GL_DEPTH_ATTACHMENT;
        int depth;
        if (src.texture()) {
            depth = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, depth);
            glTexStorage2D(GL_TEXTURE_2D, 1, src.format(), w, h);
            glBindTexture(GL_TEXTURE_2D, 0);
            glFramebufferTexture2D(GL_FRAMEBUFFER, attach, GL_TEXTURE_2D, depth, 0);
        } else {
            depth = glGenRenderbuffers();
            glBindRenderbuffer(GL_RENDERBUFFER, depth);
            glRenderbufferStorage(GL_RENDERBUFFER, src.format(), w, h);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, attach, GL_RENDERBUFFER, depth);
        }
        assertEquals(GL_FRAMEBUFFER_COMPLETE, glCheckFramebufferStatus(GL_FRAMEBUFFER), src.name());
        return new int[]{fb, color, depth};
    }

    private static void deleteTarget(DepthSource src, int[] t) {
        glDeleteFramebuffers(t[0]);
        glDeleteRenderbuffers(t[1]);
        if (src.texture()) {
            glDeleteTextures(t[2]);
        } else {
            glDeleteRenderbuffers(t[2]);
        }
    }

    /** Random slanted triangles (NDC, identity matrix) over a cleared depth: many distinct depth values. */
    private void drawRandomDepth(int fb, int w, int h, long seed, int triangles) {
        glBindFramebuffer(GL_FRAMEBUFFER, fb);
        glViewport(0, 0, w, h);
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LESS);
        glDepthMask(true);
        glClearDepth(1.0);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);
        Random rnd = new Random(seed);
        float[] v = new float[triangles * 9];
        for (int i = 0; i < v.length; i += 3) {
            v[i] = rnd.nextFloat() * 2.4f - 1.2f;
            v[i + 1] = rnd.nextFloat() * 2.4f - 1.2f;
            v[i + 2] = rnd.nextFloat() * 1.9f - 0.95f;
        }
        drawTriangles(v, new Matrix4f());
    }

    private static MmsHiZPyramid newPyramid() {
        return new MmsHiZPyramid(CearlDispatcher.create(MmsGpuCuller.compileKernel("hiz_reduce")));
    }

    @Test
    void everyDepthFormatSeedsTheSamePyramidAsReadPixelsAndRestoresState() {
        int sw = 257;
        int sh = 131;
        int sentinelTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, sentinelTex);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, 4, 4);
        glBindTexture(GL_TEXTURE_2D, 0);
        int sentinelSampler = glGenSamplers();
        try (MmsHiZPyramid pyramid = newPyramid()) {
            int seed = 0;
            for (DepthSource src : SOURCES) {
                int[] t = target(src, sw, sh);
                try {
                    drawRandomDepth(t[0], sw, sh, ++seed, 60);
                    float[] full = readDepth(t[0], 0, 0, sw, sh);
                    long distinct = Arrays.stream(boxed(full)).distinct().count();
                    assertTrue(distinct > 100, src.name() + ": scene has varied depth (" + distinct + ")");
                    Matrix4f vp = new Matrix4f();

                    // Framebuffer path, whole target, with hostile caller state around it.
                    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fbo);
                    glBindFramebuffer(GL_READ_FRAMEBUFFER, t[0]);
                    glEnable(GL_SCISSOR_TEST);
                    glScissor(0, 0, 1, 1);
                    glActiveTexture(GL_TEXTURE3);
                    glBindTexture(GL_TEXTURE_2D, sentinelTex);
                    glActiveTexture(GL_TEXTURE0 + MmsHiZPyramid.SEED_UNIT);
                    glBindTexture(GL_TEXTURE_2D, sentinelTex);
                    glBindSampler(MmsHiZPyramid.SEED_UNIT, sentinelSampler);
                    glActiveTexture(GL_TEXTURE3);
                    glUseProgram(program);
                    pyramid.captureFramebuffer(t[0], 0, 0, sw, sh, vp);
                    assertEquals(fbo, glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING), src.name() + ": draw fb restored");
                    assertEquals(t[0], glGetInteger(GL_READ_FRAMEBUFFER_BINDING), src.name() + ": read fb restored");
                    assertTrue(glIsEnabled(GL_SCISSOR_TEST), src.name() + ": scissor restored");
                    assertEquals(GL_TEXTURE3, glGetInteger(GL_ACTIVE_TEXTURE), src.name() + ": active unit restored");
                    assertEquals(sentinelTex, glGetInteger(GL_TEXTURE_BINDING_2D), src.name() + ": unit 3 texture");
                    assertEquals(program, glGetInteger(GL_CURRENT_PROGRAM), src.name() + ": program restored");
                    glActiveTexture(GL_TEXTURE0 + MmsHiZPyramid.SEED_UNIT);
                    assertEquals(sentinelTex, glGetInteger(GL_TEXTURE_BINDING_2D), src.name() + ": seed unit texture");
                    assertEquals(sentinelSampler, glGetInteger(GL_SAMPLER_BINDING), src.name() + ": seed unit sampler");
                    glBindSampler(MmsHiZPyramid.SEED_UNIT, 0);
                    glBindTexture(GL_TEXTURE_2D, 0);
                    glActiveTexture(GL_TEXTURE0);
                    glDisable(GL_SCISSOR_TEST);
                    glUseProgram(0);
                    assertEquals(GL_NO_ERROR, glGetError(), src.name() + ": framebuffer capture");
                    assertPyramidEquals(MmsHiZPyramid.reduce(full, sw, sh), pyramid, src.name() + " fb", src.ulps());
                    assertEquals(sw, pyramid.width());
                    assertEquals(sh, pyramid.height());

                    // Framebuffer path, a viewport inside the target (odd offset and size).
                    int x = 13, y = 7, w = 201, h = 101;
                    pyramid.captureFramebuffer(t[0], x, y, w, h, vp);
                    assertEquals(GL_NO_ERROR, glGetError(), src.name() + ": sub-rectangle capture");
                    assertPyramidEquals(MmsHiZPyramid.reduce(readDepth(t[0], x, y, w, h), w, h), pyramid,
                        src.name() + " fb sub-rect", src.ulps());

                    if (src.texture()) {
                        // Texture path: compare mode on and (packed) stencil mode selected - both must be
                        // overridden for the read and the texture's own state left alone.
                        glBindFramebuffer(GL_FRAMEBUFFER, 0);
                        glBindTexture(GL_TEXTURE_2D, t[2]);
                        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_REF_TO_TEXTURE);
                        if (src.packed()) {
                            glTexParameteri(GL_TEXTURE_2D, GL_DEPTH_STENCIL_TEXTURE_MODE, GL_STENCIL_INDEX);
                        }
                        glBindTexture(GL_TEXTURE_2D, 0);
                        pyramid.captureTexture(t[2], sw, sh, vp);
                        assertEquals(GL_NO_ERROR, glGetError(), src.name() + ": texture capture");
                        glBindTexture(GL_TEXTURE_2D, t[2]);
                        assertEquals(src.packed() ? GL_STENCIL_INDEX : GL_DEPTH_COMPONENT,
                            glGetTexParameteri(GL_TEXTURE_2D, GL_DEPTH_STENCIL_TEXTURE_MODE),
                            src.name() + ": depth-stencil mode restored");
                        glTexParameteri(GL_TEXTURE_2D, GL_DEPTH_STENCIL_TEXTURE_MODE, GL_DEPTH_COMPONENT);
                        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_NONE);
                        glBindTexture(GL_TEXTURE_2D, 0);
                        assertPyramidEquals(MmsHiZPyramid.reduce(full, sw, sh), pyramid, src.name() + " texture", src.ulps());
                    }
                } finally {
                    deleteTarget(src, t);
                }
            }
        } finally {
            glDeleteTextures(sentinelTex);
            glDeleteSamplers(sentinelSampler);
        }
    }

    private static Float[] boxed(float[] a) {
        Float[] out = new Float[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = a[i];
        }
        return out;
    }

    /**
     * The render-thread cost of one 1080p capture with GPU work in flight (two frames queued, like a swap
     * chain): the old {@code glReadPixels(DEPTH_COMPONENT, FLOAT)} pixel-pack copy (measured as reference,
     * same scene) against the seed pass through both paths. Prints CPU and GPU times; asserts the seed
     * pass's median CPU time is well under 0.2 ms.
     */
    @Test
    void a1080pCaptureCostsTheRenderThreadAlmostNothing() {
        int sw = 1920;
        int sh = 1080;
        DepthSource rb = new DepthSource("D24S8 renderbuffer", GL_DEPTH24_STENCIL8, false);
        DepthSource tex = new DepthSource("D24S8 texture", GL_DEPTH24_STENCIL8, true);
        int[] rbTarget = target(rb, sw, sh);
        int[] texTarget = target(tex, sw, sh);
        int legacyPbo = glGenBuffers();
        glBindBuffer(GL_PIXEL_PACK_BUFFER, legacyPbo);
        glBufferData(GL_PIXEL_PACK_BUFFER, (long) sw * sh * 4, GL_DYNAMIC_COPY);
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
        int[] timers = new int[2];
        glGenQueries(timers);
        try (MmsHiZPyramid pyramid = newPyramid()) {
            Matrix4f vp = new Matrix4f();
            double[] legacy = measure(sw, sh, rbTarget[0], timers, () -> {
                glBindFramebuffer(GL_READ_FRAMEBUFFER, rbTarget[0]);
                glBindBuffer(GL_PIXEL_PACK_BUFFER, legacyPbo);
                glReadPixels(0, 0, sw, sh, GL_DEPTH_COMPONENT, GL_FLOAT, 0L);
                glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
            });
            double[] viaFb = measure(sw, sh, rbTarget[0], timers,
                () -> pyramid.captureFramebuffer(rbTarget[0], 0, 0, sw, sh, vp));
            double[] viaTex = measure(sw, sh, texTarget[0], timers,
                () -> pyramid.captureTexture(texTarget[2], sw, sh, vp));
            assertEquals(GL_NO_ERROR, glGetError());
            System.out.printf("[hiz-cost] 1920x1080 D24S8, %d frames, CPU median/mean/p95 ms | GPU mean ms%n", FRAMES);
            report("legacy glReadPixels->PBO", legacy);
            report("captureFramebuffer (blit+seed)", viaFb);
            report("captureTexture (seed)", viaTex);
            assertTrue(viaFb[0] < 0.2, "captureFramebuffer median CPU " + viaFb[0] + " ms");
            assertTrue(viaTex[0] < 0.2, "captureTexture median CPU " + viaTex[0] + " ms");
        } finally {
            glDeleteQueries(timers);
            glDeleteBuffers(legacyPbo);
            deleteTarget(rb, rbTarget);
            deleteTarget(tex, texTarget);
        }
    }

    private static final int FRAMES = 100;
    private static final int WARMUP = 15;

    private static void report(String what, double[] r) {
        System.out.printf("[hiz-cost]   %-32s %.4f / %.4f / %.4f | %.4f%n", what, r[0], r[1], r[2], r[3]);
    }

    /** {median, mean, p95} CPU ms of {@code capture} and the GPU mean ms (timer query around it). */
    private double[] measure(int w, int h, int fb, int[] timers, Runnable capture) {
        long[] fences = new long[2];
        boolean[] timed = new boolean[2];
        double[] cpu = new double[FRAMES];
        double gpuSum = 0;
        int gpuFrames = 0;
        int layerCount = 64;
        float[] quad = {-1, -1, 0, 1, -1, 0, 1, 1, 0, -1, -1, 0, 1, 1, 0, -1, 1, 0};
        float[] layers = new float[quad.length * layerCount];
        for (int l = 0; l < layerCount; l++) {   // back to front: every layer passes the depth test, full fill
            for (int i = 0; i < quad.length; i += 3) {
                layers[l * quad.length + i] = quad[i];
                layers[l * quad.length + i + 1] = quad[i + 1];
                layers[l * quad.length + i + 2] = 0.9f - l * (1.8f / layerCount);
            }
        }
        for (int f = 0; f < WARMUP + FRAMES; f++) {
            int slot = f & 1;
            if (fences[slot] != 0) {   // at most two frames in flight
                glClientWaitSync(fences[slot], GL_SYNC_FLUSH_COMMANDS_BIT, Long.MAX_VALUE);
                glDeleteSync(fences[slot]);
                fences[slot] = 0;
            }
            if (timed[slot]) {         // that frame is done: its timer is ready without a stall
                gpuSum += glGetQueryObjecti64(timers[slot], GL_QUERY_RESULT) / 1e6;
                gpuFrames++;
                timed[slot] = false;
            }
            glBindFramebuffer(GL_FRAMEBUFFER, fb);
            glViewport(0, 0, w, h);
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_LESS);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            drawTriangles(layers, new Matrix4f());
            boolean measured = f >= WARMUP;
            if (measured) {
                glBeginQuery(GL_TIME_ELAPSED, timers[slot]);
            }
            long t0 = System.nanoTime();
            capture.run();
            long t1 = System.nanoTime();
            if (measured) {
                glEndQuery(GL_TIME_ELAPSED);
                cpu[f - WARMUP] = (t1 - t0) / 1e6;
            }
            fences[slot] = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            timed[slot] = measured;
        }
        glFinish();
        for (int slot = 0; slot < 2; slot++) {
            if (fences[slot] != 0) {
                glDeleteSync(fences[slot]);
            }
            if (timed[slot]) {
                gpuSum += glGetQueryObjecti64(timers[slot], GL_QUERY_RESULT) / 1e6;
                gpuFrames++;
            }
        }
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        double[] sorted = cpu.clone();
        Arrays.sort(sorted);
        return new double[]{sorted[FRAMES / 2], Arrays.stream(cpu).average().orElse(0),
            sorted[(int) (FRAMES * 0.95)], gpuSum / Math.max(1, gpuFrames)};
    }
}
