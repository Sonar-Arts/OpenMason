package com.openmason.engine.voxel.mms.mmsRegion;

import com.openmason.engine.cearl.CearlDispatcher;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL41;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;

/**
 * The hierarchical depth (Hi-Z) pyramid {@link MmsGpuCuller}'s phase-2 occlusion
 * test reads (GL 4.3+). One shader-storage buffer of f32 holds a max-depth mip
 * chain of the viewport's depth down to 1 x 1, laid out exactly as
 * {@code culling.CEARL} documents (level 0 = half resolution at offset 0, ceil
 * halving, each texel = the FARTHEST depth it covers).
 *
 * <p><b>Capture</b> never transfers pixels: a small compute pass ({@link #SEED_GLSL})
 * {@code texelFetch}es the depth TEXTURE and writes level 0 straight into the
 * storage buffer, then {@code hiz_reduce} (a CEARL kernel) builds each further
 * level from the one below. Sources: a depth texture ({@link #captureTexture},
 * sampled directly) or any framebuffer's depth buffer ({@link #captureFramebuffer},
 * 0 = the default framebuffer), which is first blitted GPU-side into an internal
 * depth texture of the same format. (The first version read the depth with
 * {@code glReadPixels(DEPTH_COMPONENT, FLOAT)} into a pixel-pack buffer; from a
 * D24 target the driver converted it on the render thread, ~4 ms/frame at
 * 1080p.) The depth must be single-sampled and use GL's default convention ({@code [0,1]},
 * {@code GL_LESS}, cleared to 1, no {@code glClipControl}); the view-projection
 * passed alongside must be the RENDER-SPACE matrix the depth was drawn with.
 *
 * <p>{@link #levelOffset}, {@link #levelEdge}, {@link #reduce} and
 * {@link #occluded} are the kernels' math on the CPU (headless tests, and the
 * documentation of the GPU contract). GL-thread confined.
 */
public final class MmsHiZPyramid implements AutoCloseable {

    private final CearlDispatcher reduce;
    private int buffer;
    private long capacityBytes;
    private int seedProgram;
    private int seedSampler;
    private int seedSrcSize = -1;
    private int seedDstSize = -1;
    private int copyFramebuffer;
    private int copyTexture;
    private int copyFormat;
    private int copyWidth;
    private int copyHeight;
    private int width;
    private int height;
    private int levels;
    private boolean valid;
    private boolean closed;
    private final Matrix4f viewProj = new Matrix4f();
    private final float[] viewProjColumns = new float[16];

    /**
     * Created by {@link MmsGpuCuller}; owns (and closes) the {@code hiz_reduce}
     * dispatcher. Links the seed program now, so a driver that rejects it fails
     * here (where the culler turns Hi-Z off) rather than mid-frame.
     */
    MmsHiZPyramid(CearlDispatcher reduce) {
        this.reduce = reduce;
        try {
            ensureSeedProgram();
        } catch (RuntimeException e) {
            reduce.close();
            throw e;
        }
    }

    // ─── Layout (shared with culling.CEARL) ─────────────────────────────────────

    /** Pyramid levels for a {@code w x h} viewport: level 0 is half resolution, the last is 1 x 1. */
    public static int levelCount(int w, int h) {
        int lw = (w + 1) / 2;
        int lh = (h + 1) / 2;
        int n = 1;
        while (lw > 1 || lh > 1) {
            lw = (lw + 1) / 2;
            lh = (lh + 1) / 2;
            n++;
        }
        return n;
    }

    /** Edge (width or height) of level {@code k} for a viewport edge of {@code n} pixels. */
    public static int levelEdge(int n, int k) {
        int e = (n + 1) / 2;
        for (int j = 0; j < k; j++) {
            e = (e + 1) / 2;
        }
        return e;
    }

    /** Element offset of level {@code k} in the buffer (level 0 starts at 0). */
    public static long levelOffset(int w, int h, int k) {
        long off = 0;
        int lw = w;
        int lh = h;
        for (int j = 0; j < k; j++) {
            lw = (lw + 1) / 2;
            lh = (lh + 1) / 2;
            off += (long) lw * lh;
        }
        return off;
    }

    /** Total elements of a {@code w x h} pyramid (every level). */
    public static long totalElements(int w, int h) {
        return levelOffset(w, h, levelCount(w, h));
    }

    /**
     * Builds a whole pyramid on the CPU from a viewport depth image ({@code w*h}
     * floats, row 0 = bottom) — the seed pass for level 0, then {@code hiz_reduce}
     * level by level (same max over the same clamped 2x2 sources).
     */
    public static float[] reduce(float[] depth, int w, int h) {
        int levels = levelCount(w, h);
        float[] out = new float[(int) totalElements(w, h)];
        int sw = w;
        int sh = h;
        long src = 0;
        float[] in = depth;
        for (int k = 0; k < levels; k++) {
            int dw = levelEdge(w, k);
            int dh = levelEdge(h, k);
            long dst = levelOffset(w, h, k);
            for (int y = 0; y < dh; y++) {
                for (int x = 0; x < dw; x++) {
                    int x0 = Math.min(x * 2, sw - 1);
                    int x1 = Math.min(x * 2 + 1, sw - 1);
                    int y0 = Math.min(y * 2, sh - 1);
                    int y1 = Math.min(y * 2 + 1, sh - 1);
                    float a = in[(int) (src + (long) y0 * sw + x0)];
                    float b = in[(int) (src + (long) y0 * sw + x1)];
                    float c = in[(int) (src + (long) y1 * sw + x0)];
                    float d = in[(int) (src + (long) y1 * sw + x1)];
                    out[(int) (dst + (long) y * dw + x)] = Math.max(Math.max(a, b), Math.max(c, d));
                }
            }
            in = out;
            src = dst;
            sw = dw;
            sh = dh;
        }
        return out;
    }

    /**
     * {@code cull_late}'s occlusion verdict on the CPU: whether the region-local box
     * {@code [min, max]} shifted by the region's render-space origin is hidden
     * behind {@code pyramid} ({@link #reduce} output) of a {@code w x h} viewport
     * drawn with {@code viewProj} (render space). Same float operations as the kernel.
     */
    public static boolean occluded(float[] pyramid, int w, int h, Matrix4fc viewProj,
                                   float ox, float oy, float oz,
                                   float minX, float minY, float minZ,
                                   float maxX, float maxY, float maxZ) {
        int levels = levelCount(w, h);
        float nx0 = 1.0e30f, ny0 = 1.0e30f, nx1 = -1.0e30f, ny1 = -1.0e30f, nz0 = 1.0e30f;
        for (int c = 0; c < 8; c++) {
            float px = ((c & 1) != 0 ? maxX : minX) + ox;
            float py = ((c & 2) != 0 ? maxY : minY) + oy;
            float pz = ((c & 4) != 0 ? maxZ : minZ) + oz;
            float cx = viewProj.m00() * px + viewProj.m10() * py + viewProj.m20() * pz + viewProj.m30();
            float cy = viewProj.m01() * px + viewProj.m11() * py + viewProj.m21() * pz + viewProj.m31();
            float cz = viewProj.m02() * px + viewProj.m12() * py + viewProj.m22() * pz + viewProj.m32();
            float cw = viewProj.m03() * px + viewProj.m13() * py + viewProj.m23() * pz + viewProj.m33();
            if (cw <= 0.00001f) {
                return false; // A corner on/behind the camera plane: never occluded.
            }
            float inv = 1.0f / cw;
            nx0 = Math.min(nx0, cx * inv);
            ny0 = Math.min(ny0, cy * inv);
            nx1 = Math.max(nx1, cx * inv);
            ny1 = Math.max(ny1, cy * inv);
            nz0 = Math.min(nz0, cz * inv);
        }
        float fw = w;
        float fh = h;
        float sx0 = clamp((nx0 * 0.5f + 0.5f) * fw, 0f, fw - 1f);
        float sy0 = clamp((ny0 * 0.5f + 0.5f) * fh, 0f, fh - 1f);
        float sx1 = clamp((nx1 * 0.5f + 0.5f) * fw, 0f, fw - 1f);
        float sy1 = clamp((ny1 * 0.5f + 0.5f) * fh, 0f, fh - 1f);
        float nearDepth = nz0 * 0.5f + 0.5f;
        float span = Math.max((float) ((int) sx1 - (int) sx0), (float) ((int) sy1 - (int) sy0)) + 1f;
        int k = 0;
        float texel = 2f;
        while (texel < span && k + 1 < levels) {
            texel *= 2f;
            k++;
        }
        int lw = levelEdge(w, k);
        int lh = levelEdge(h, k);
        long base = levelOffset(w, h, k);
        int tx0 = Math.min((int) sx0 >>> (k + 1), lw - 1);
        int tx1 = Math.min((int) sx1 >>> (k + 1), lw - 1);
        int ty0 = Math.min((int) sy0 >>> (k + 1), lh - 1);
        int ty1 = Math.min((int) sy1 >>> (k + 1), lh - 1);
        float farDepth = 0f;
        for (int ty = ty0; ty <= ty1; ty++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                farDepth = Math.max(farDepth, pyramid[(int) (base + (long) ty * lw + tx)]);
            }
        }
        return nearDepth > farDepth;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ─── GPU capture ────────────────────────────────────────────────────────────

    /** Texture unit the seed pass samples the depth on (saved and restored around the pass). */
    static final int SEED_UNIT = 15;
    private static final int SEED_LOCAL = 8;

    /**
     * Level 0 straight from the depth texture: each invocation takes the farthest
     * of its 2x2 pixels ({@code texelFetch}, no filtering, no compare), clamped at
     * odd edges — {@code hiz_reduce}'s rule with the depth image as the source.
     */
    static final String SEED_GLSL = """
        #version 430 core
        layout(local_size_x = 8, local_size_y = 8) in;
        layout(std430, binding = 0) writeonly buffer Pyramid { float pyramid[]; };
        uniform sampler2D u_depth;
        uniform ivec2 u_src_size;
        uniform ivec2 u_dst_size;
        void main() {
            ivec2 t = ivec2(gl_GlobalInvocationID.xy);
            if (t.x >= u_dst_size.x || t.y >= u_dst_size.y) {
                return;
            }
            ivec2 p0 = t * 2;
            ivec2 p1 = min(p0 + 1, u_src_size - 1);
            float a = texelFetch(u_depth, p0, 0).r;
            float b = texelFetch(u_depth, ivec2(p1.x, p0.y), 0).r;
            float c = texelFetch(u_depth, ivec2(p0.x, p1.y), 0).r;
            float d = texelFetch(u_depth, p1, 0).r;
            pyramid[t.y * u_dst_size.x + t.x] = max(max(a, b), max(c, d));
        }
        """;

    /**
     * Builds the pyramid from the depth of {@code framebuffer} (0 = default)
     * inside the viewport rectangle {@code (x, y, w, h)}; {@code renderViewProj}
     * is the render-space {@code projection * view} that depth was drawn with.
     * The depth is first blitted ({@code GL_NEAREST}, GPU-side) into an internal
     * depth texture of the same format, then seeded like {@link #captureTexture}.
     * Restores the read/draw framebuffer bindings and the scissor test.
     */
    public void captureFramebuffer(int framebuffer, int x, int y, int w, int h, Matrix4fc renderViewProj) {
        ensureOpen();
        valid = false;
        if (w <= 0 || h <= 0) {
            return;
        }
        int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int prevDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        boolean copied = false;
        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, framebuffer);
            int format = sourceDepthFormat(framebuffer);
            if (format != 0) {
                ensureCopyTarget(format, w, h);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, copyFramebuffer);
                if (scissor) {
                    GL11.glDisable(GL11.GL_SCISSOR_TEST);
                }
                GL30.glBlitFramebuffer(x, y, x + w, y + h, 0, 0, w, h, GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
                copied = true;
            }
        } finally {
            if (scissor) {
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
            }
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
        }
        if (copied) {
            build(copyTexture, false, w, h, renderViewProj);
        }
    }

    /**
     * Builds the pyramid from a single-sampled depth texture ({@code GL_TEXTURE_2D},
     * any depth or depth-stencil format) whose level 0 is at least {@code w x h};
     * the texture is sampled directly, no copy. Must not be attached to the bound
     * draw framebuffer while depth writes are pending a feedback loop (it is only
     * read here). Restores the active texture unit, the unit's texture + sampler
     * bindings, the texture's depth-stencil mode and the bound program.
     */
    public void captureTexture(int depthTexture, int w, int h, Matrix4fc renderViewProj) {
        ensureOpen();
        valid = false;
        if (w <= 0 || h <= 0 || depthTexture == 0) {
            return;
        }
        build(depthTexture, true, w, h, renderViewProj);
    }

    /** GL internal format matching the read framebuffer's depth (+ stencil) buffer, 0 when it has none. */
    private static int sourceDepthFormat(int framebuffer) {
        int depthPoint = framebuffer == 0 ? GL11.GL_DEPTH : GL30.GL_DEPTH_ATTACHMENT;
        int stencilPoint = framebuffer == 0 ? GL11.GL_STENCIL : GL30.GL_STENCIL_ATTACHMENT;
        int type = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_READ_FRAMEBUFFER, depthPoint,
            GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        if (type == GL11.GL_NONE) {
            return 0;
        }
        int depthBits = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_READ_FRAMEBUFFER, depthPoint,
            GL30.GL_FRAMEBUFFER_ATTACHMENT_DEPTH_SIZE);
        if (depthBits == 0) {
            return 0;
        }
        int component = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_READ_FRAMEBUFFER, depthPoint,
            GL30.GL_FRAMEBUFFER_ATTACHMENT_COMPONENT_TYPE);
        boolean stencil = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_READ_FRAMEBUFFER, stencilPoint,
            GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE) != GL11.GL_NONE
            && GL30.glGetFramebufferAttachmentParameteri(GL30.GL_READ_FRAMEBUFFER, stencilPoint,
            GL30.GL_FRAMEBUFFER_ATTACHMENT_STENCIL_SIZE) > 0;
        return depthFormat(depthBits, component == GL11.GL_FLOAT, stencil);
    }

    /** The sized depth format with these bits (what a same-format blit target must use). */
    static int depthFormat(int depthBits, boolean floatDepth, boolean stencil) {
        if (floatDepth) {
            return stencil ? GL30.GL_DEPTH32F_STENCIL8 : GL30.GL_DEPTH_COMPONENT32F;
        }
        if (stencil) {
            return GL30.GL_DEPTH24_STENCIL8;
        }
        return switch (depthBits) {
            case 16 -> GL14.GL_DEPTH_COMPONENT16;
            case 32 -> GL14.GL_DEPTH_COMPONENT32;
            default -> GL14.GL_DEPTH_COMPONENT24;
        };
    }

    /** (Re)creates the blit target when the format changes or the viewport outgrows it. */
    private void ensureCopyTarget(int format, int w, int h) {
        if (copyTexture != 0 && copyFormat == format && copyWidth >= w && copyHeight >= h) {
            return;
        }
        if (copyFramebuffer == 0) {
            copyFramebuffer = GL30.glGenFramebuffers();
        }
        // Left bound as DRAW: captureFramebuffer blits into it next and restores the caller's binding.
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, copyFramebuffer);
        if (copyTexture != 0) {
            // Detach depth AND stencil first: a packed -> depth-only switch would otherwise leave the old
            // texture on the stencil point (deleting it only detaches it from a BOUND framebuffer).
            GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT,
                GL11.GL_TEXTURE_2D, 0, 0);
            GL11.glDeleteTextures(copyTexture);
        }
        int newWidth = copyFormat == format ? Math.max(w, copyWidth) : w;
        int newHeight = copyFormat == format ? Math.max(h, copyHeight) : h;
        int prevActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + SEED_UNIT);
        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        copyTexture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, copyTexture);
        GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, format, newWidth, newHeight);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        GL13.glActiveTexture(prevActive);
        copyFormat = format;
        copyWidth = newWidth;
        copyHeight = newHeight;
        boolean packed = format == GL30.GL_DEPTH24_STENCIL8 || format == GL30.GL_DEPTH32F_STENCIL8;
        GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER,
            packed ? GL30.GL_DEPTH_STENCIL_ATTACHMENT : GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, copyTexture, 0);
        GL11.glDrawBuffer(GL11.GL_NONE);
    }

    /** Lazily links the seed program and its sampler (throws when the driver rejects them). */
    private void ensureSeedProgram() {
        if (seedProgram != 0) {
            return;
        }
        int shader = GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);
        GL20.glShaderSource(shader, SEED_GLSL);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException("Hi-Z seed shader rejected: " + log);
        }
        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, shader);
        GL20.glLinkProgram(program);
        GL20.glDeleteShader(shader);
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(program);
            GL20.glDeleteProgram(program);
            throw new IllegalStateException("Hi-Z seed program failed to link: " + log);
        }
        seedProgram = program;
        seedSrcSize = GL20.glGetUniformLocation(program, "u_src_size");
        seedDstSize = GL20.glGetUniformLocation(program, "u_dst_size");
        GL41.glProgramUniform1i(program, GL20.glGetUniformLocation(program, "u_depth"), SEED_UNIT);
        // The sampler overrides whatever the depth texture carries: no compare (a plain sampler2D reading a
        // compare-mode texture is undefined), nearest, and complete without mipmaps.
        seedSampler = GL33.glGenSamplers();
        GL33.glSamplerParameteri(seedSampler, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);
        GL33.glSamplerParameteri(seedSampler, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL33.glSamplerParameteri(seedSampler, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL33.glSamplerParameteri(seedSampler, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL33.glSamplerParameteri(seedSampler, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
    }

    /** Ensures {@link #buffer} holds a {@code w x h} pyramid (grows by 1.5x, never shrinks). */
    private void ensureBuffer(int w, int h) {
        long needed = totalElements(w, h) * Float.BYTES;
        if (buffer != 0 && needed <= capacityBytes) {
            return;
        }
        if (buffer != 0) {
            GL15.glDeleteBuffers(buffer);
        }
        capacityBytes = Math.max(needed, capacityBytes + capacityBytes / 2);
        buffer = GL15.glGenBuffers();
        int prev = GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, buffer);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, capacityBytes, GL15.GL_DYNAMIC_COPY);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, prev);
    }

    /**
     * Seeds level 0 from {@code depthTexture} (texel {@code (0,0)} = viewport
     * corner) and reduces the rest of the chain. All GPU work, no transfer.
     */
    private void build(int depthTexture, boolean callerTexture, int w, int h, Matrix4fc renderViewProj) {
        ensureSeedProgram();
        ensureBuffer(w, h);
        width = w;
        height = h;
        levels = levelCount(w, h);
        viewProj.set(renderViewProj);
        viewProj.get(viewProjColumns);
        int dw = levelEdge(w, 0);
        int dh = levelEdge(h, 0);

        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + SEED_UNIT);
        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int prevSampler = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTexture);
        int prevMode = GL11.GL_DEPTH_COMPONENT;
        if (callerTexture) {
            // Sample depth, not stencil, of a packed depth-stencil texture (texture state, not sampler state).
            prevMode = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL43.GL_DEPTH_STENCIL_TEXTURE_MODE);
            if (prevMode != GL11.GL_DEPTH_COMPONENT) {
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL43.GL_DEPTH_STENCIL_TEXTURE_MODE, GL11.GL_DEPTH_COMPONENT);
            }
        }
        GL33.glBindSampler(SEED_UNIT, seedSampler);
        try {
            GL20.glUseProgram(seedProgram);
            GL20.glUniform2i(seedSrcSize, w, h);
            GL20.glUniform2i(seedDstSize, dw, dh);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, buffer);
            GL43.glDispatchCompute((dw + SEED_LOCAL - 1) / SEED_LOCAL, (dh + SEED_LOCAL - 1) / SEED_LOCAL, 1);
            GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        } finally {
            GL33.glBindSampler(SEED_UNIT, prevSampler);
            if (prevMode != GL11.GL_DEPTH_COMPONENT) {
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL43.GL_DEPTH_STENCIL_TEXTURE_MODE, prevMode);
            }
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
            GL13.glActiveTexture(prevActive);
            GL20.glUseProgram(prevProgram);
        }

        // The rest of the chain: one dispatch per level, a storage barrier between levels.
        if (levels > 1) {
            reduce.begin();
            try {
                reduce.bindBuffer("pyramid", buffer);
                for (int k = 1; k < levels; k++) {
                    int sw = levelEdge(w, k - 1);
                    int sh = levelEdge(h, k - 1);
                    int lw = levelEdge(w, k);
                    int lh = levelEdge(h, k);
                    reduce.uniform1u("src_offset", (int) levelOffset(w, h, k - 1));
                    reduce.uniform1u("src_w", sw);
                    reduce.uniform1u("src_h", sh);
                    reduce.uniform1u("dst_offset", (int) levelOffset(w, h, k));
                    reduce.uniform1u("dst_w", lw);
                    reduce.uniform1u("dst_h", lh);
                    reduce.dispatch(lw * lh);
                    GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
                }
            } finally {
                reduce.end();
            }
        }
        valid = true;
    }

    /** True when a capture has built the pyramid (cleared by {@link #invalidate}). */
    public boolean isValid() {
        return valid && !closed;
    }

    /** Marks the pyramid stale (the next phase-2 pass then tests nothing as occluded). */
    public void invalidate() {
        valid = false;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int levels() {
        return levels;
    }

    /** The storage buffer (GL id) holding the levels; 0 before the first capture. */
    public int bufferId() {
        return buffer;
    }

    /** The captured render-space view-projection, column-major (16 floats, JOML {@code get(float[])} order). */
    float[] viewProjColumns() {
        return viewProjColumns;
    }

    /** The captured render-space view-projection. */
    public Matrix4fc viewProj() {
        return viewProj;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Hi-Z pyramid is closed");
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        valid = false;
        reduce.close();
        if (buffer != 0) {
            GL15.glDeleteBuffers(buffer);
            buffer = 0;
        }
        if (seedProgram != 0) {
            GL20.glDeleteProgram(seedProgram);
            seedProgram = 0;
        }
        if (seedSampler != 0) {
            GL33.glDeleteSamplers(seedSampler);
            seedSampler = 0;
        }
        if (copyFramebuffer != 0) {
            GL30.glDeleteFramebuffers(copyFramebuffer);
            copyFramebuffer = 0;
        }
        if (copyTexture != 0) {
            GL11.glDeleteTextures(copyTexture);
            copyTexture = 0;
        }
    }
}
