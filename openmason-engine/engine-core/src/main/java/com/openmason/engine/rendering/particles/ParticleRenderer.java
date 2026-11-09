package com.openmason.engine.rendering.particles;

import com.openmason.engine.diagnostics.GpuMemoryTracker;
import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.openmason.engine.rendering.shaders.ShaderResourceLoader;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Draws a {@link ParticleSystem}: one upload per frame and <b>one instanced draw call</b> for every mix of
 * effects, blend styles (additive and alpha, via premultiplied alpha), orientations and sprites — or two
 * draws when the frame splits particles around a surface such as water.
 *
 * <h2>Frame protocol</h2>
 * <pre>{@code
 * renderer.render(system, frame);            // prepare + drawAll, the common case
 * // or, with frame.splitAtHeight(seaLevel):
 * renderer.prepare(system, frame);           // cull, sort, split, upload once
 * renderer.drawBehind();  drawWater();  renderer.drawFront();
 * }</pre>
 *
 * <h2>GPU resources</h2>
 * <ul>
 *   <li>One instance buffer of {@value #SEGMENTS} ring segments, <b>sized to the visible particle count</b>,
 *       not the budget: it starts at {@value #MIN_SEGMENT_INSTANCES} records per segment, doubles when a
 *       frame needs more and halves back after {@value #SHRINK_WINDOW_FRAMES} frames of using under a quarter.
 *       Each frame maps the next segment {@code UNSYNCHRONIZED | INVALIDATE_RANGE} and the
 *       {@link ParticleBatchBuilder} writes records straight into the mapping (no staging copy). A fence per
 *       segment guarantees the GPU finished reading it before it is rewritten.</li>
 *   <li>No vertex or index buffer: quad corners come from {@code gl_VertexID}.</li>
 *   <li>A 1×1 white texture (2D, for soft-particle depth) and a one-layer white array (sprites) bound when
 *       the host has none.</li>
 * </ul>
 * The ring is charged to {@link GpuMemoryTracker.Category#PARTICLES} and released by {@link #close()}.
 * Uniform locations are cached; nothing is allocated on the Java heap per frame.
 *
 * <h2>GL state</h2>
 * Each draw is depth-tested without depth writes, culling off, blend {@code (ONE, ONE_MINUS_SRC_ALPHA)}, the
 * sprite array ({@link ParticleFrame#sprites}) on texture unit {@value #SPRITE_UNIT} with no sampler object
 * (a UI library's leftover sampler would override the array's filtering), and scene depth (soft particles) on unit {@value #DEPTH_UNIT}.
 * Everything it changes is restored afterwards unless {@link #setRestoreState(boolean) setRestoreState(false)}.
 * The host's depth function is used as-is. Requires GL 3.3; render-thread only.
 */
public final class ParticleRenderer implements AutoCloseable {

    public static final String VERTEX_SHADER = "/shaders/particles/particle.vert";
    public static final String FRAGMENT_SHADER = "/shaders/particles/particle.frag";
    public static final int SEGMENTS = 3;
    public static final int SPRITE_UNIT = 0;
    public static final int DEPTH_UNIT = 1;
    /** Smallest ring segment, in instances. */
    public static final int MIN_SEGMENT_INSTANCES = 1024;
    /** Frames of low use before the ring shrinks. */
    public static final int SHRINK_WINDOW_FRAMES = 600;

    private static final String LIGHT_MARKER = "//@PARTICLE_LIGHT";
    private static final long FENCE_WAIT_NANOS = 1_000_000_000L;

    private final ParticleBatchBuilder builder = new ParticleBatchBuilder();
    private final ShaderProgram shader;
    private final int locViewProj, locCamRight, locCamUp, locCamPos, locAmbient, locFogDensity,
            locFogColor, locSoftEnabled, locDepthParams;
    private final FloatBuffer matrixScratch = MemoryUtil.memAllocFloat(16);
    private final Matrix4f viewProjection = new Matrix4f();
    private final int whiteTexture;
    private final int whiteArray;
    private final int vao;

    private int buffer;
    private final long[] fences = new long[SEGMENTS];
    private int segmentInstances;
    private long segmentBytes;
    private long trackedBytes;
    private int segment = SEGMENTS - 1;
    private int windowPeak;
    private int windowFrames;
    /** Lazily allocated only if a driver refuses to map (never on conformant drivers). */
    private ByteBuffer fallback;

    // ---- the prepared frame ----
    private ParticleFrame frame;
    private int prepared;
    private int preparedBehind;
    private boolean uploaded;

    private boolean restoreState = true;
    private boolean closed;

    // ---- stats ----
    private int lastDrawn;
    private int lastDrawCalls;
    private long uploadedBytesTotal;
    private long fenceWaits;
    private int reallocations;
    /** GPU simulation of the system's GPU effects (OM #100); null until a GPU-enabled system is prepared. */
    private ParticleGpuBackend gpu;
    /** The last prepared system is the GPU backend's (its pools are part of this frame). */
    private boolean gpuThisFrame;

    public ParticleRenderer() {
        this(ParticleShaderExtension.NONE);
    }

    /**
     * A renderer with host GLSL hooks: {@code particleLight} (non-emissive lighting) and/or {@code particleColor}
     * (final colour of every particle, e.g. weather fog); see {@link ParticleShaderExtension}.
     */
    public ParticleRenderer(ParticleShaderExtension extension) {
        String vertex = ShaderResourceLoader.load(VERTEX_SHADER, ParticleRenderer.class::getResourceAsStream);
        if (!vertex.contains(LIGHT_MARKER)) {
            throw new IllegalStateException("particle vertex shader lost its lighting marker");
        }
        shader = new ShaderProgram();
        shader.createVertexShader(vertex.replace(LIGHT_MARKER, extension.source()));
        shader.createFragmentShader(ShaderResourceLoader.load(FRAGMENT_SHADER, ParticleRenderer.class::getResourceAsStream));
        shader.link();
        int program = shader.getProgramId();
        locViewProj = glGetUniformLocation(program, "u_viewProj");
        locCamRight = glGetUniformLocation(program, "u_camRight");
        locCamUp = glGetUniformLocation(program, "u_camUp");
        locCamPos = glGetUniformLocation(program, "u_camPos");
        locAmbient = glGetUniformLocation(program, "u_ambient");
        locFogDensity = glGetUniformLocation(program, "u_fogDensity");
        locFogColor = glGetUniformLocation(program, "u_fogColor");
        locSoftEnabled = glGetUniformLocation(program, "u_softEnabled");
        locDepthParams = glGetUniformLocation(program, "u_depthParams");
        int previousProgram = glGetInteger(GL_CURRENT_PROGRAM);
        glUseProgram(program);
        glUniform1i(glGetUniformLocation(program, "u_sprites"), SPRITE_UNIT);
        glUniform1i(glGetUniformLocation(program, "u_sceneDepth"), DEPTH_UNIT);
        glUseProgram(previousProgram);

        int previousActive = glGetInteger(GL_ACTIVE_TEXTURE);
        glActiveTexture(GL_TEXTURE0 + SPRITE_UNIT);
        int previousTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
        int previousArray = glGetInteger(GL_TEXTURE_BINDING_2D_ARRAY);
        int previousUnpackBuffer = glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
        whiteTexture = glGenTextures();
        whiteArray = glGenTextures();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer white = stack.malloc(4).putInt(0, 0xFFFFFFFF);
            glBindTexture(GL_TEXTURE_2D, whiteTexture);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, white);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glBindTexture(GL_TEXTURE_2D_ARRAY, whiteArray);
            glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_RGBA8, 1, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, white);
            glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        }
        glBindTexture(GL_TEXTURE_2D, previousTexture);
        glBindTexture(GL_TEXTURE_2D_ARRAY, previousArray);
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, previousUnpackBuffer);
        glActiveTexture(previousActive);

        int previousVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        vao = glGenVertexArrays();
        glBindVertexArray(vao);
        for (int a = ParticleVertexFormat.ATTRIB_POSITION; a <= ParticleVertexFormat.ATTRIB_AXIS_SOFTNESS; a++) {
            glEnableVertexAttribArray(a);
            glVertexAttribDivisor(a, 1);
        }
        glBindVertexArray(previousVao);
    }

    /** Whether draws restore the GL state they touched (default true). */
    public void setRestoreState(boolean restore) {
        this.restoreState = restore;
    }

    /** Prepares and draws everything: {@link #prepare} then {@link #drawAll()}. Returns particles drawn. */
    public int render(ParticleSystem system, ParticleFrame frame) {
        prepare(system, frame);
        return drawAll();
    }

    /**
     * Culls, sorts, splits (if {@code frame.split}) and uploads {@code system} for {@code frame}; the draw
     * calls that follow use this upload. Returns the number of particles prepared.
     */
    public int prepare(ParticleSystem system, ParticleFrame frame) {
        if (closed) {
            throw new IllegalStateException("particle renderer is closed");
        }
        this.frame = frame;
        lastDrawCalls = 0;
        lastDrawn = 0;
        uploaded = false;
        if (system.gpuEnabled() && !ParticleGpuBackend.isSupported()) {
            system.fallBackToCpu(); // this context has no compute shaders: GPU effects run on the CPU
        }
        gpuThisFrame = system.gpuEnabled();
        int n = builder.prepare(system, frame);
        prepared = n;
        preparedBehind = builder.behindCount();
        trackUsage(n);
        if (n > 0) {
            upload(n);
        }
        if (gpuThisFrame) {
            if (gpu == null || gpu.system() != system) {
                if (gpu != null) {
                    gpu.close();
                }
                gpu = new ParticleGpuBackend(system);
            }
            // After the CPU upload: in a merged frame the CPU records join the GPU sort (copied on the GPU).
            gpu.step(frame, buffer, segment * segmentBytes, uploaded ? n : 0, builder.sortedLastBuild());
            if (gpu.merged() && uploaded) {
                if (fences[segment] != 0L) {
                    glDeleteSync(fences[segment]);
                }
                fences[segment] = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            }
        }
        return n;
    }

    /** Packs and uploads the CPU batch's {@code n} records into the next ring segment. */
    private void upload(int n) {
        ensureSegment(n);

        segment = (segment + 1) % SEGMENTS;
        awaitSegment(segment);
        long offset = segment * segmentBytes;
        long bytes = (long) n * ParticleVertexFormat.STRIDE;
        int prevArrayBuffer = glGetInteger(GL_ARRAY_BUFFER_BINDING);
        glBindBuffer(GL_ARRAY_BUFFER, buffer);
        long address = nglMapBufferRange(GL_ARRAY_BUFFER, offset, bytes,
                GL_MAP_WRITE_BIT | GL_MAP_INVALIDATE_RANGE_BIT | GL_MAP_UNSYNCHRONIZED_BIT);
        if (address != 0L) {
            builder.pack(address);
            uploaded = glUnmapBuffer(GL_ARRAY_BUFFER); // false = contents lost (mode switch); skip the frame
        } else {
            if (fallback == null || fallback.capacity() < segmentBytes) {
                if (fallback != null) {
                    MemoryUtil.memFree(fallback);
                }
                fallback = MemoryUtil.memAlloc((int) segmentBytes);
            }
            builder.pack(MemoryUtil.memAddress(fallback));
            nglBufferSubData(GL_ARRAY_BUFFER, offset, bytes, MemoryUtil.memAddress(fallback));
            uploaded = true;
        }
        glBindBuffer(GL_ARRAY_BUFFER, prevArrayBuffer);
        if (uploaded) {
            uploadedBytesTotal += bytes;
        }
    }

    /** This frame's GPU pools and CPU batch are drawn as one sorted set (see {@link ParticleGpuBackend}). */
    private boolean merged() {
        return gpuThisFrame && gpu.merged();
    }

    /** Draws every prepared particle (GPU-simulated pools after the CPU batch). */
    public int drawAll() {
        if (merged()) {
            return gpu.drawMerged(this, true) + gpu.drawMerged(this, false);
        }
        int n = drawRange(0, prepared);
        if (gpuThisFrame) {
            n += gpu.drawPools(this, true) + gpu.drawPools(this, false);
        }
        return n;
    }

    /** Draws the prepared particles across the split plane from the camera (all of them without a split). */
    public int drawBehind() {
        if (frame == null || !frame.split) {
            return drawAll();
        }
        if (merged()) {
            return gpu.drawMerged(this, true);
        }
        int n = drawRange(0, preparedBehind);
        return gpuThisFrame ? n + gpu.drawPools(this, true) : n;
    }

    /** Draws the prepared particles on the camera's side of the split plane (none without a split). */
    public int drawFront() {
        if (frame == null || !frame.split) {
            return 0;
        }
        if (merged()) {
            return gpu.drawMerged(this, false);
        }
        int n = drawRange(preparedBehind, prepared - preparedBehind);
        return gpuThisFrame ? n + gpu.drawPools(this, false) : n;
    }

    /**
     * The GPU backend simulating {@code system}'s {@link ParticleEffect.Simulation#GPU} effects (OM #100), created on
     * the first {@link #prepare} of a GPU-enabled system; null otherwise. Host collision data
     * ({@link ParticleGpuBackend#setHeightField}) goes here.
     */
    public ParticleGpuBackend gpu() {
        return gpu;
    }

    private int drawRange(int first, int n) {
        if (!uploaded || n <= 0) {
            return 0;
        }
        long base = segment * segmentBytes + (long) first * ParticleVertexFormat.STRIDE;
        return drawInstances(buffer, base, n, 0, 0L);
    }

    /**
     * One instanced draw of {@link ParticleVertexFormat} records in {@code vertexBuffer} from byte {@code base}:
     * {@code n} instances, or — when {@code indirectBuffer} is non-zero — a {@code DrawArraysIndirectCommand} the GPU
     * wrote at {@code indirectOffset} (GPU-simulated pools; {@code n} is then only the statistics' estimate).
     * Sets and (optionally) restores the shared state: program, uniforms, sprite array, scene depth, blending.
     */
    int drawInstances(int vertexBuffer, long base, int n, int indirectBuffer, long indirectOffset) {
        if (frame == null) {
            return 0;
        }
        ParticleFrame f = frame;
        boolean restore = restoreState;
        int prevProgram = 0, prevVao = 0, prevArrayBuffer = 0, prevActive = 0, prevSprites = 0, prevSampler = 0, prevDepth = 0;
        int prevSrcRgb = 0, prevDstRgb = 0, prevSrcAlpha = 0, prevDstAlpha = 0;
        boolean prevBlend = false, prevDepthTest = false, prevDepthMask = false, prevCull = false;
        if (restore) {
            prevProgram = glGetInteger(GL_CURRENT_PROGRAM);
            prevVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
            prevArrayBuffer = glGetInteger(GL_ARRAY_BUFFER_BINDING);
            prevActive = glGetInteger(GL_ACTIVE_TEXTURE);
            glActiveTexture(GL_TEXTURE0 + SPRITE_UNIT);
            prevSprites = glGetInteger(GL_TEXTURE_BINDING_2D_ARRAY);
            prevSampler = glGetInteger(GL_SAMPLER_BINDING);
            glActiveTexture(GL_TEXTURE0 + DEPTH_UNIT);
            prevDepth = glGetInteger(GL_TEXTURE_BINDING_2D);
            prevBlend = glIsEnabled(GL_BLEND);
            prevSrcRgb = glGetInteger(GL_BLEND_SRC_RGB);
            prevDstRgb = glGetInteger(GL_BLEND_DST_RGB);
            prevSrcAlpha = glGetInteger(GL_BLEND_SRC_ALPHA);
            prevDstAlpha = glGetInteger(GL_BLEND_DST_ALPHA);
            prevDepthTest = glIsEnabled(GL_DEPTH_TEST);
            prevDepthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
            prevCull = glIsEnabled(GL_CULL_FACE);
        }

        glUseProgram(shader.getProgramId());
        f.projection.mul(f.view, viewProjection);
        viewProjection.get(matrixScratch);
        glUniformMatrix4fv(locViewProj, false, matrixScratch);
        Matrix4f v = f.view;
        glUniform3f(locCamRight, v.m00(), v.m10(), v.m20());
        glUniform3f(locCamUp, v.m01(), v.m11(), v.m21());
        glUniform3f(locCamPos, f.cameraPosition.x, f.cameraPosition.y, f.cameraPosition.z);
        glUniform1f(locAmbient, f.ambient);
        glUniform1f(locFogDensity, f.fogDensity);
        glUniform3f(locFogColor, f.fogColor.x, f.fogColor.y, f.fogColor.z);
        glUniform1i(locSoftEnabled, f.depthTexture != 0 ? 1 : 0);
        glUniform2f(locDepthParams, f.projection.m22(), f.projection.m32());
        if (f.uniforms != null) {
            f.uniforms.accept(shader);
        }

        glActiveTexture(GL_TEXTURE0 + DEPTH_UNIT);
        glBindTexture(GL_TEXTURE_2D, f.depthTexture != 0 ? f.depthTexture : whiteTexture);
        glActiveTexture(GL_TEXTURE0 + SPRITE_UNIT);
        int sprites = f.sprites != null ? f.sprites.texture() : 0;
        glBindTexture(GL_TEXTURE_2D_ARRAY, sprites != 0 ? sprites : whiteArray);
        glBindSampler(SPRITE_UNIT, 0);

        glEnable(GL_BLEND);
        glBlendFuncSeparate(GL_ONE, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        glEnable(GL_DEPTH_TEST);
        glDepthMask(false);
        glDisable(GL_CULL_FACE);

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer);
        int stride = ParticleVertexFormat.STRIDE;
        glVertexAttribPointer(ParticleVertexFormat.ATTRIB_POSITION, 3, GL_FLOAT, false, stride,
                base + ParticleVertexFormat.OFFSET_POSITION);
        glVertexAttribPointer(ParticleVertexFormat.ATTRIB_COLOR, 4, GL_UNSIGNED_BYTE, true, stride,
                base + ParticleVertexFormat.OFFSET_COLOR);
        glVertexAttribPointer(ParticleVertexFormat.ATTRIB_SIZE_ROTATION, 2, GL_HALF_FLOAT, false, stride,
                base + ParticleVertexFormat.OFFSET_SIZE_ROTATION);
        glVertexAttribIPointer(ParticleVertexFormat.ATTRIB_FRAME_FLAGS, 2, GL_UNSIGNED_SHORT, stride,
                base + ParticleVertexFormat.OFFSET_FRAME_FLAGS);
        glVertexAttribPointer(ParticleVertexFormat.ATTRIB_AXIS_SOFTNESS, 4, GL_HALF_FLOAT, false, stride,
                base + ParticleVertexFormat.OFFSET_AXIS_SOFTNESS);
        if (indirectBuffer != 0) {
            int prevIndirect = glGetInteger(org.lwjgl.opengl.GL40C.GL_DRAW_INDIRECT_BUFFER_BINDING);
            glBindBuffer(org.lwjgl.opengl.GL40C.GL_DRAW_INDIRECT_BUFFER, indirectBuffer);
            org.lwjgl.opengl.GL40C.glDrawArraysIndirect(GL_TRIANGLE_STRIP, indirectOffset);
            glBindBuffer(org.lwjgl.opengl.GL40C.GL_DRAW_INDIRECT_BUFFER, prevIndirect);
        } else {
            glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, n);
            // A newer fence covers every earlier command, so it replaces this segment's previous one.
            if (fences[segment] != 0L) {
                glDeleteSync(fences[segment]);
            }
            fences[segment] = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        }
        lastDrawCalls++;
        lastDrawn += n;

        if (restore) {
            glUseProgram(prevProgram);
            glBindVertexArray(prevVao);
            glBindBuffer(GL_ARRAY_BUFFER, prevArrayBuffer);
            glActiveTexture(GL_TEXTURE0 + DEPTH_UNIT);
            glBindTexture(GL_TEXTURE_2D, prevDepth);
            glActiveTexture(GL_TEXTURE0 + SPRITE_UNIT);
            glBindTexture(GL_TEXTURE_2D_ARRAY, prevSprites);
            glBindSampler(SPRITE_UNIT, prevSampler);
            glActiveTexture(prevActive);
            setEnabled(GL_BLEND, prevBlend);
            glBlendFuncSeparate(prevSrcRgb, prevDstRgb, prevSrcAlpha, prevDstAlpha);
            setEnabled(GL_DEPTH_TEST, prevDepthTest);
            glDepthMask(prevDepthMask);
            setEnabled(GL_CULL_FACE, prevCull);
        }
        return n;
    }

    private static void setEnabled(int cap, boolean enabled) {
        if (enabled) {
            glEnable(cap);
        } else {
            glDisable(cap);
        }
    }

    private void awaitSegment(int s) {
        long fence = fences[s];
        if (fence == 0L) {
            return;
        }
        int status = glClientWaitSync(fence, 0, 0L);
        if (status == GL_TIMEOUT_EXPIRED) {
            fenceWaits++;
            do {
                status = glClientWaitSync(fence, GL_SYNC_FLUSH_COMMANDS_BIT, FENCE_WAIT_NANOS);
            } while (status == GL_TIMEOUT_EXPIRED);
        }
        glDeleteSync(fence);
        fences[s] = 0L;
    }

    // ------------------------------------------------------------------------------------------------
    // Usage-sized ring
    // ------------------------------------------------------------------------------------------------

    /** Halves the ring after a window of frames that never used a quarter of it. */
    private void trackUsage(int n) {
        windowPeak = Math.max(windowPeak, n);
        if (++windowFrames < SHRINK_WINDOW_FRAMES) {
            return;
        }
        int want = Math.max(MIN_SEGMENT_INSTANCES, ceilPow2(windowPeak * 2));
        if (segmentInstances > MIN_SEGMENT_INSTANCES && windowPeak * 4 < segmentInstances && want < segmentInstances) {
            allocateRing(want);
        }
        windowPeak = 0;
        windowFrames = 0;
    }

    private void ensureSegment(int n) {
        if (n > segmentInstances) {
            allocateRing(Math.max(MIN_SEGMENT_INSTANCES, ceilPow2(n)));
        }
    }

    private static int ceilPow2(int n) {
        return n <= 1 ? 1 : Integer.highestOneBit(n - 1) << 1;
    }

    private void allocateRing(int instances) {
        releaseBuffer();
        segmentInstances = instances;
        segmentBytes = (long) instances * ParticleVertexFormat.STRIDE;
        long total = segmentBytes * SEGMENTS;
        int prevArrayBuffer = glGetInteger(GL_ARRAY_BUFFER_BINDING);
        buffer = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, buffer);
        glBufferData(GL_ARRAY_BUFFER, total, GL_STREAM_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, prevArrayBuffer);
        trackedBytes = total;
        GpuMemoryTracker.getInstance().track(GpuMemoryTracker.Category.PARTICLES, trackedBytes);
        segment = SEGMENTS - 1;
        reallocations++;
    }

    private void releaseBuffer() {
        for (int s = 0; s < SEGMENTS; s++) {
            if (fences[s] != 0L) {
                glDeleteSync(fences[s]);
                fences[s] = 0L;
            }
        }
        if (buffer != 0) {
            glDeleteBuffers(buffer); // the driver keeps it alive for draws still in flight
            buffer = 0;
        }
        if (trackedBytes != 0) {
            GpuMemoryTracker.getInstance().untrack(GpuMemoryTracker.Category.PARTICLES, trackedBytes);
            trackedBytes = 0;
        }
        segmentInstances = 0;
        segmentBytes = 0;
    }

    // ---- stats ----

    /** Particles drawn since the last {@link #prepare} (all draw calls). */
    public int lastDrawn() { return lastDrawn; }
    /** Draw calls issued since the last {@link #prepare}. */
    public int lastDrawCalls() { return lastDrawCalls; }
    /** Bytes written into the instance buffer since creation. */
    public long uploadedBytesTotal() { return uploadedBytesTotal; }
    /** Frames that had to block on a ring-segment fence (GPU more than two frames behind). */
    public long fenceWaits() { return fenceWaits; }
    /** GPU bytes held by the instance ring. */
    public long gpuBytes() { return trackedBytes; }
    /** Instances one ring segment holds. */
    public int segmentInstances() { return segmentInstances; }
    /** Times the ring was (re)allocated (growth or shrink). */
    public int reallocations() { return reallocations; }
    /** The builder used for the last frame (debug views). */
    public ParticleBatchBuilder builder() { return builder; }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (gpu != null) {
            gpu.close();
            gpu = null;
        }
        releaseBuffer();
        glDeleteVertexArrays(vao);
        glDeleteTextures(whiteTexture);
        glDeleteTextures(whiteArray);
        shader.cleanup();
        MemoryUtil.memFree(matrixScratch);
        if (fallback != null) {
            MemoryUtil.memFree(fallback);
            fallback = null;
        }
    }
}
