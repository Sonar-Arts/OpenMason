package com.openmason.engine.voxel.mms.mmsRegion;

import com.openmason.engine.cearl.CearlCompiler;
import com.openmason.engine.cearl.CearlDispatcher;
import com.openmason.engine.cearl.CearlKernel;
import com.openmason.engine.rendering.RenderOrigin;
import com.openmason.engine.voxel.mms.mmsCore.MmsUploadBufferPool;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;

import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL32;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Map;

/**
 * GPU-driven per-mesh culling for region rendering (GL 4.3+): a compute
 * kernel frustum-tests every live draw command in a region against six
 * render-space planes and writes a {@code DrawElementsIndirectCommand} per
 * command (culled or disabled meshes get {@code instanceCount = 0}); the region
 * then draws with ONE {@code glMultiDrawElementsIndirect} — no per-mesh CPU
 * visibility work, no per-command CPU packing.
 *
 * <p>The kernel is authored in <b>CEARL</b> ({@code cearl/engine/culling.CEARL}, an
 * engine resource) and runs through {@link CearlDispatcher}. Layouts: 48-byte
 * MeshMeta ({@code minb}, {@code maxb} region-local, {@code draw =
 * (indexCount, firstIndex, baseVertex, flags)}), 20-byte tightly packed
 * commands. {@code mesh_count} stays a uniform on purpose — ring ranges are
 * alignment-padded, so the bound range's own length can overstate the live
 * count.
 *
 * <p><b>Coordinates</b> (cubic runtime, 2026-11-17): AABBs are region-local and
 * the kernel adds the region's camera-relative origin
 * ({@link MmsChunkRegion#renderOrigin}, a per-dispatch uniform computed in
 * long/double), so planes come straight from the render-space
 * {@code projection * view} — no absolute float world coordinate anywhere.
 * The old four vertical keep-box planes are gone: hosts disable meshes
 * instead ({@link MmsRegionMeshHandle#setEnabled}, bit 0 of the flags word).
 * {@link #aabbVisible} is the same test on the CPU, used by
 * {@link MmsChunkRegion#appendVisible} for the GL 3.2 / macOS 4.1 path.
 *
 * <p>Pass shape (all GL-thread): {@link #beginPass} once with the pass's
 * frustum planes, {@link #cull} per region (dispatches the compute),
 * {@link #endCull} once (single command barrier), then {@link #draw} per
 * region and {@link #endDraw}. Batching every dispatch before the barrier
 * keeps it to one barrier per pass.
 *
 * <p>Plane convention: each plane is {@code (a,b,c,d)} with
 * {@code a·x + b·y + c·z + d >= 0} for points inside the frustum (JOML's
 * {@code Matrix4f.frustumPlane} output, unnormalized is fine). Each cull writes the
 * region's mesh metadata and the kernel's commands into a fenced
 * {@link MmsIndirectRing}, so no pass rewrites data a queued dispatch or
 * draw still reads.
 *
 * <p><b>Hi-Z occlusion</b> (2026-11-17, camera passes only; the CPU path is
 * untouched): a two-phase pass over the same pass shape.
 * <ol>
 *   <li>{@link #beginEarlyPass(float[])}, cull/endCull/draw/endDraw: draws the
 *       enabled in-frustum meshes the previous camera pass found visible (a region
 *       without a usable history draws everything in the frustum);</li>
 *   <li>{@link #buildHiZ} / {@link #buildHiZFromTexture}: reduces the depth phase 1
 *       produced into a max-depth pyramid ({@link MmsHiZPyramid}) with this frame's
 *       render-space view-projection - all on the GPU (a texture is sampled directly,
 *       a framebuffer's depth is blitted to an internal texture first; no pixel
 *       transfer, so no render-thread stall);</li>
 *   <li>{@link #beginLatePass()}, cull/endCull/draw/endDraw: re-tests every enabled
 *       in-frustum mesh against the pyramid, stores the verdict as next frame's
 *       history and draws the visible meshes phase 1 did not draw.</li>
 * </ol>
 * The previous frame only decides what is drawn EARLY, never what is culled, so a
 * camera move cannot open a hole: anything visible now is drawn by one phase or the
 * other in this frame. No reprojection is needed because phase 2 tests against
 * depth of the SAME frame. Disabled ({@code -Dopenmason.hiz=off},
 * {@link #setHiZEnabled}, or a driver that rejects the kernels) the early pass is a
 * plain frustum pass and the late pass draws nothing — the same sequence of calls
 * stays correct. The enable bit keeps its meaning in every phase. Counters:
 * {@link #hizStats()} (read back asynchronously, a few frames late).
 */
public final class MmsGpuCuller implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(MmsGpuCuller.class);

    /** {@code -Dopenmason.hiz=off} disables Hi-Z occlusion for every culler (the early/late calls stay valid). */
    public static final boolean HIZ_DEFAULT = !"off".equalsIgnoreCase(System.getProperty("openmason.hiz", "on"));

    /**
     * Classpath location of the kernel source. Module-namespaced deliberately:
     * resource directories are JPMS packages, and two modules with files in
     * the same directory (the game plan lives in the game's {@code cearl/})
     * split the package and kill the boot layer — each module keeps its CEARL
     * under {@code cearl/<module>/}.
     */
    static final String CULL_RESOURCE = "/cearl/engine/culling.CEARL";

    /**
     * Compiles the kernel from its resource file (no GL needed) — exposed so
     * the headless tests can pin the kernel's shape without a context.
     */
    public static CearlKernel compileKernel() {
        return compileKernel("cull_commands");
    }

    /**
     * Compiles one kernel of {@code culling.CEARL}: {@code cull_commands} (frustum),
     * {@code cull_early} / {@code cull_late} (Hi-Z phases) or {@code hiz_reduce}
     * (pyramid level). No GL needed.
     */
    public static CearlKernel compileKernel(String name) {
        String source;
        try (java.io.InputStream in = MmsGpuCuller.class.getResourceAsStream(CULL_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(CULL_RESOURCE + " missing from the engine jar");
            }
            source = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot read " + CULL_RESOURCE, e);
        }
        return CearlCompiler.compile(source, "cearl/engine/culling.CEARL", Map.of())
            .kernel(name);
    }

    /** Whether the current context can run the GPU-cull path. */
    public static boolean isSupported() {
        try {
            return GL.getCapabilities().OpenGL43;
        } catch (IllegalStateException e) {
            return false; // No context on this thread.
        }
    }

    /** Bytes per {@code DrawElementsIndirectCommand} (5 uints, tightly packed). */
    private static final int CMD_STRIDE = 20;

    private final CearlDispatcher dispatcher;
    private final MmsIndirectRing commands;
    private boolean closed;

    // ─── Hi-Z state ─────────────────────────────────────────────────────────────
    /** Pass kinds: frustum only, Hi-Z phase 1, Hi-Z phase 2. */
    private enum Mode { FRUSTUM, EARLY, LATE }

    private Mode mode = Mode.FRUSTUM;
    private boolean hizEnabled = HIZ_DEFAULT;
    private boolean hizFailed;
    /** Whether the current early/late pair runs the occlusion kernels (fixed at beginEarlyPass). */
    private boolean pairActive;
    private CearlDispatcher earlyDispatcher;
    private CearlDispatcher lateDispatcher;
    private MmsHiZPyramid pyramid;
    /** Incremented by every beginEarlyPass; regions remember the epoch their early cull ran in. */
    private long epoch;
    private final float[] earlyPlanes = new float[24];
    // Counters: STATS_SLOTS rotating slots of 4 u32 (early drawn, tested, occluded, late drawn).
    private static final int STATS_SLOTS = 4;
    private static final int STATS_WORDS = 4;
    private int statsBuffer;
    private final long[] statsFence = new long[STATS_SLOTS];
    private final long[] statsEpoch = new long[STATS_SLOTS];
    private int statsSlot;
    private HiZStats lastStats = HiZStats.NONE;
    private long lastStatsEpoch = -1;
    private int passEarlyCommands;
    private int passLateCommands;

    /**
     * Hi-Z counters of one camera frame (from the GPU, read back without stalling, so
     * typically 1-3 frames old). {@code tested} = enabled in-frustum commands tested in
     * phase 2, {@code occluded} = of those, hidden by the pyramid; {@code earlyDrawn} /
     * {@code lateDrawn} = commands each phase drew; {@code frame} = the culler's epoch.
     */
    public record HiZStats(long frame, int earlyDrawn, int tested, int occluded, int lateDrawn) {
        public static final HiZStats NONE = new HiZStats(-1, 0, 0, 0, 0);
    }

    /** Compiles and links the cull kernel. Throws when the driver rejects it. */
    public MmsGpuCuller() {
        this.dispatcher = CearlDispatcher.create(compileKernel());
        this.commands = new MmsIndirectRing();
    }

    /** Frustum planes, as the kernel takes them. */
    static final int PLANES = 6;
    private final float[] passPlanes = new float[PLANES * 4];
    /** Column-path compatibility keep box (world XZ), or null. */
    private float[] passKeepXZ;
    private final Vector3f regionOrigin = new Vector3f();

    /**
     * Starts a cull pass: binds the compute program and uploads the pass's six
     * RENDER-SPACE frustum planes ({@code planes} holds 6×4 floats, from the
     * render-space {@code projection * view}). The caller's bound program (the
     * pass's draw shader) is saved and restored by {@link #endCull} so the
     * indirect draws render with it.
     */
    public void beginPass(float[] planes) {
        System.arraycopy(planes, 0, passPlanes, 0, PLANES * 4);
        passKeepXZ = null;
        mode = Mode.FRUSTUM;
        dispatcher.begin();
        dispatcher.uniform4fv("planes", passPlanes);
    }

    // ─── Hi-Z two-phase camera pass ─────────────────────────────────────────────

    /** Whether Hi-Z occlusion runs (default {@link #HIZ_DEFAULT}; false after a kernel failed to build). */
    public boolean isHiZEnabled() {
        return hizEnabled && !hizFailed;
    }

    /** Enables/disables Hi-Z; takes effect at the next {@link #beginEarlyPass}. */
    public void setHiZEnabled(boolean enabled) {
        this.hizEnabled = enabled;
    }

    /** The pyramid the late pass reads (null until Hi-Z first runs). */
    public MmsHiZPyramid pyramid() {
        return pyramid;
    }

    /**
     * Phase 1 of a Hi-Z camera pass: like {@link #beginPass(float[])} (same
     * render-space planes), but each region draws only the meshes its history marks
     * visible (everything in the frustum when it has none). Follow with
     * cull/endCull/draw/endDraw, then {@link #buildHiZ}, then {@link #beginLatePass()}.
     * With Hi-Z disabled this is exactly {@link #beginPass(float[])}.
     */
    public void beginEarlyPass(float[] planes) {
        System.arraycopy(planes, 0, passPlanes, 0, PLANES * 4);
        System.arraycopy(planes, 0, earlyPlanes, 0, PLANES * 4);
        passKeepXZ = null;
        epoch++;
        pairActive = isHiZEnabled() && ensureHiZ();
        if (pyramid != null) {
            pyramid.invalidate();
        }
        passEarlyCommands = 0;
        passLateCommands = 0;
        if (!pairActive) {
            mode = Mode.FRUSTUM;
            dispatcher.begin();
            dispatcher.uniform4fv("planes", passPlanes);
            return;
        }
        mode = Mode.EARLY;
        harvestStats();
        statsSlot = (int) (epoch % STATS_SLOTS);
        statsEpoch[statsSlot] = epoch;
        if (statsFence[statsSlot] != 0) {
            GL32.glDeleteSync(statsFence[statsSlot]);
            statsFence[statsSlot] = 0;
        }
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, statsBuffer);
        GL43.glClearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, GL30.GL_R32UI,
            (long) statsSlot * STATS_WORDS * Integer.BYTES, (long) STATS_WORDS * Integer.BYTES,
            GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, (ByteBuffer) null);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
        earlyDispatcher.begin();
        earlyDispatcher.uniform4fv("planes", passPlanes);
        earlyDispatcher.uniform1u("stats_base", statsSlot * STATS_WORDS);
        earlyDispatcher.bindBuffer("stats", statsBuffer);
    }

    /**
     * Builds the Hi-Z pyramid from the depth phase 1 drew: {@code framebuffer} (0 =
     * default) inside the viewport {@code (x, y, w, h)}; {@code renderViewProj} is
     * the render-space {@code projection * view} of the pass (the matrix the planes
     * came from). No-op when the current pair runs without Hi-Z. Call between the
     * early pass's {@link #endDraw()} and {@link #beginLatePass()}.
     */
    public void buildHiZ(int framebuffer, int x, int y, int w, int h, Matrix4fc renderViewProj) {
        if (pairActive) {
            pyramid.captureFramebuffer(framebuffer, x, y, w, h, renderViewProj);
        }
    }

    /**
     * {@link #buildHiZ} from a single-sampled {@code w x h} depth texture, sampled
     * directly (cheaper than {@link #buildHiZ}: no blit). Prefer it when the pass
     * renders into a depth texture.
     */
    public void buildHiZFromTexture(int depthTexture, int w, int h, Matrix4fc renderViewProj) {
        if (pairActive) {
            pyramid.captureTexture(depthTexture, w, h, renderViewProj);
        }
    }

    /**
     * Phase 2 of a Hi-Z camera pass (reuses the early pass's planes): each region's
     * cull tests its enabled in-frustum meshes against the pyramid, records next
     * frame's history and leaves only the newly visible ones to draw. Follow with
     * cull/endCull/draw/endDraw over the same regions. With Hi-Z off for this pair,
     * {@link #cull} returns 0 for every region (phase 1 already drew everything).
     * Without a pyramid ({@link #buildHiZ} not called) nothing counts as occluded.
     */
    public void beginLatePass() {
        System.arraycopy(earlyPlanes, 0, passPlanes, 0, PLANES * 4);
        passKeepXZ = null;
        if (!pairActive) {
            mode = Mode.LATE;
            return;
        }
        mode = Mode.LATE;
        boolean usePyramid = pyramid.isValid();
        lateDispatcher.begin();
        lateDispatcher.uniform4fv("planes", passPlanes);
        lateDispatcher.uniform1u("stats_base", statsSlot * STATS_WORDS);
        lateDispatcher.bindBuffer("stats", statsBuffer);
        lateDispatcher.uniform1u("use_pyramid", usePyramid ? 1 : 0);
        if (usePyramid) {
            lateDispatcher.uniform4fv("view_proj", pyramid.viewProjColumns());
            lateDispatcher.uniform1u("screen_w", pyramid.width());
            lateDispatcher.uniform1u("screen_h", pyramid.height());
            lateDispatcher.uniform1u("levels", pyramid.levels());
            lateDispatcher.bindBuffer("pyramid", pyramid.bufferId());
        } else {
            lateDispatcher.uniform4fv("view_proj", IDENTITY_COLUMNS);
            lateDispatcher.uniform1u("screen_w", 1);
            lateDispatcher.uniform1u("screen_h", 1);
            lateDispatcher.uniform1u("levels", 1);
            // Any valid buffer: the kernel never reads it without use_pyramid.
            lateDispatcher.bindBuffer("pyramid", statsBuffer);
        }
    }

    private static final float[] IDENTITY_COLUMNS = {
        1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};

    /** The latest Hi-Z counters read back from the GPU ({@link HiZStats#NONE} before any). */
    public HiZStats hizStats() {
        harvestStats();
        return lastStats;
    }

    /** Commands the last early / late pass dispatched (CPU side, this frame). */
    public int lastEarlyCommands() {
        return passEarlyCommands;
    }

    public int lastLateCommands() {
        return passLateCommands;
    }

    /** Lazily builds the Hi-Z kernels + buffers; false (and Hi-Z off for good) when the driver rejects them. */
    private boolean ensureHiZ() {
        if (hizFailed) {
            return false;
        }
        if (earlyDispatcher != null) {
            return true;
        }
        try {
            earlyDispatcher = CearlDispatcher.create(compileKernel("cull_early"));
            lateDispatcher = CearlDispatcher.create(compileKernel("cull_late"));
            pyramid = new MmsHiZPyramid(CearlDispatcher.create(compileKernel("hiz_reduce")));
            statsBuffer = GL15.glGenBuffers();
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, statsBuffer);
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,
                (long) STATS_SLOTS * STATS_WORDS * Integer.BYTES, GL15.GL_DYNAMIC_READ);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
            return true;
        } catch (RuntimeException e) {
            hizFailed = true;
            LOGGER.error("[MmsGpuCuller] Hi-Z kernels failed to build; frustum culling only from now on", e);
            closeHiZ();
            return false;
        }
    }

    /** Reads every finished stats slot without waiting; keeps the newest. */
    private void harvestStats() {
        if (statsBuffer == 0) {
            return;
        }
        for (int s = 0; s < STATS_SLOTS; s++) {
            long fence = statsFence[s];
            if (fence == 0) {
                continue;
            }
            int status = GL32.glClientWaitSync(fence, 0, 0L);
            if (status != GL32.GL_ALREADY_SIGNALED && status != GL32.GL_CONDITION_SATISFIED) {
                continue;
            }
            GL32.glDeleteSync(fence);
            statsFence[s] = 0;
            if (statsEpoch[s] <= lastStatsEpoch) {
                continue;
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer words = stack.mallocInt(STATS_WORDS);
                GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, statsBuffer);
                GL15.glGetBufferSubData(GL31.GL_COPY_READ_BUFFER, (long) s * STATS_WORDS * Integer.BYTES, words);
                GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, 0);
                lastStats = new HiZStats(statsEpoch[s], words.get(0), words.get(1), words.get(2), words.get(3));
                lastStatsEpoch = statsEpoch[s];
            }
        }
    }

    /** Ensures the region's history buffer holds {@code count} commands; false when it was (re)allocated. */
    private static boolean ensureVis(MmsChunkRegion region, int count) {
        if (region.hizVisBufferId != 0 && region.hizVisCapacity >= count) {
            return true;
        }
        if (region.hizVisBufferId != 0) {
            GL15.glDeleteBuffers(region.hizVisBufferId);
        }
        int capacity = Math.max(64, Integer.highestOneBit(Math.max(1, count - 1)) << 1);
        region.hizVisBufferId = GL15.glGenBuffers();
        region.hizVisCapacity = capacity;
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, region.hizVisBufferId);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, (long) capacity * Integer.BYTES, GL15.GL_DYNAMIC_COPY);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
        region.hizHistoryVersion = -1;
        return false;
    }

    /**
     * The phase-1 rule for a region: 2 = draw the history's visible set, 1 = no
     * usable history, draw everything in the frustum.
     */
    static int earlyRule(int historyVersion, int listVersion) {
        return historyVersion == listVersion ? 2 : 1;
    }

    /**
     * What phase 2 may assume phase 1 drew: its own rule when the region's early
     * cull ran in this pair over the same live list, else 0 (nothing — phase 2 then
     * draws every visible mesh; a double draw is harmless, a skipped one is a hole).
     */
    static int lateRule(long earlyEpoch, long epoch, int earlyVersion, int listVersion, int earlyRule) {
        return earlyEpoch == epoch && earlyVersion == listVersion ? earlyRule : 0;
    }

    /**
     * Column-world compatibility: {@code worldPlanes} are WORLD-space planes
     * (a {@code RenderOrigin.acceptWorldSpace}'d matrix), rebased here to
     * render space in double; {@code keepXZ = {minX, minZ, maxX, maxZ}} (world,
     * null = none) drops meshes wholly outside the vertical box by clearing
     * their enable bit in the uploaded metadata — what the four keep-box
     * planes did.
     *
     * @deprecated pass render-space planes to {@link #beginPass(float[])} and
     *             disable out-of-handover meshes with
     *             {@link MmsRegionMeshHandle#setEnabled}; removed with the column path.
     */
    @Deprecated
    public void beginPass(float[] worldPlanes, float[] keepXZ) {
        float[] render = new float[PLANES * 4];
        worldToRenderPlanes(worldPlanes, RenderOrigin.blockX(), RenderOrigin.blockY(),
            RenderOrigin.blockZ(), render);
        beginPass(render);
        passKeepXZ = keepXZ == null ? null : keepXZ.clone();
    }

    /**
     * Rebases world-space planes onto a render origin: a world point is
     * {@code p_render + origin}, so {@code n·p_world + d >= 0} becomes
     * {@code n·p_render + (d + n·origin) >= 0}. Computed in double.
     */
    static void worldToRenderPlanes(float[] worldPlanes, long ox, long oy, long oz, float[] out) {
        for (int p = 0; p < PLANES; p++) {
            int b = p * 4;
            float a = worldPlanes[b], bb = worldPlanes[b + 1], c = worldPlanes[b + 2];
            out[b] = a;
            out[b + 1] = bb;
            out[b + 2] = c;
            out[b + 3] = (float) (worldPlanes[b + 3] + (double) a * ox + (double) bb * oy + (double) c * oz);
        }
    }

    /**
     * The kernel's test on the CPU: the positive vertex of the region-local
     * box {@code [min, max]} shifted by the region's render-space origin must
     * be on the inside of all six planes. Same float operation order as the
     * kernel (pick, add origin, then dot + d).
     */
    public static boolean aabbVisible(float[] planes, float ox, float oy, float oz,
                                      float minX, float minY, float minZ,
                                      float maxX, float maxY, float maxZ) {
        for (int p = 0; p < PLANES; p++) {
            int b = p * 4;
            float a = planes[b], bb = planes[b + 1], c = planes[b + 2], d = planes[b + 3];
            float vx = (a > 0f ? maxX : minX) + ox;
            float vy = (bb > 0f ? maxY : minY) + oy;
            float vz = (c > 0f ? maxZ : minZ) + oz;
            if (a * vx + bb * vy + c * vz + d < 0f) {
                return false;
            }
        }
        return true;
    }

    /**
     * Dispatches the frustum cull for one region's live draw commands. Both
     * the kernel's metadata input and its command output take fresh space in
     * the fenced ring, so nothing a queued dispatch or draw still reads is
     * ever rewritten. Returns the command count the region's indirect draw
     * will submit (0 = nothing to do).
     */
    public int cull(MmsChunkRegion region) {
        int count = region.gpuCommandCount();
        if (count == 0) {
            return 0;
        }
        if (mode == Mode.LATE && !pairActive) {
            region.gpuCmdBufferId = 0;
            return 0; // Hi-Z off for this pair: phase 1 drew everything in the frustum.
        }
        int metaBytes = count * MmsChunkRegion.GPU_META_STRIDE;
        ByteBuffer meta = MmsUploadBufferPool.acquire(metaBytes);
        region.writeGpuCullMeta(meta, passKeepXZ);
        meta.flip();
        long metaOffset = commands.allocate(metaBytes);
        int metaBuffer = commands.bufferId();
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, metaBuffer);
        GL15.glBufferSubData(GL31.GL_COPY_WRITE_BUFFER, metaOffset, meta);
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);

        long cmdBytes = (long) count * CMD_STRIDE;
        // May grow the ring; the metadata then stays in the retired buffer,
        // which lives until the pass ends.
        region.gpuCmdOffsetBytes = commands.allocate(cmdBytes);
        region.gpuCmdBufferId = commands.bufferId();

        region.renderOrigin(regionOrigin);
        CearlDispatcher d = switch (mode) {
            case FRUSTUM -> dispatcher;
            case EARLY -> earlyDispatcher;
            case LATE -> lateDispatcher;
        };
        d.bindBufferRange("meshes", metaBuffer, metaOffset, metaBytes);
        d.bindBufferRange("cmds", region.gpuCmdBufferId, region.gpuCmdOffsetBytes, cmdBytes);
        d.uniform4f("origin", regionOrigin.x, regionOrigin.y, regionOrigin.z, 0f);
        d.uniform1u("mesh_count", count);
        if (mode == Mode.EARLY) {
            boolean kept = ensureVis(region, count);
            int rule = kept ? earlyRule(region.hizHistoryVersion, region.meshListVersion) : 1;
            region.hizEarlyEpoch = epoch;
            region.hizEarlyRule = rule;
            region.hizEarlyVersion = region.meshListVersion;
            d.bindBuffer("vis", region.hizVisBufferId);
            d.uniform1u("history", rule == 2 ? 1 : 0);
            passEarlyCommands += count;
        } else if (mode == Mode.LATE) {
            int rule = lateRule(region.hizEarlyEpoch, epoch, region.hizEarlyVersion, region.meshListVersion,
                region.hizEarlyRule);
            if (!ensureVis(region, count) && rule == 2) {
                rule = 0; // The history phase 1 read is gone with the old buffer.
            }
            d.bindBuffer("vis", region.hizVisBufferId);
            d.uniform1u("early_rule", rule);
            region.hizHistoryVersion = region.meshListVersion;
            passLateCommands += count;
        }
        d.dispatch(count);
        return count;
    }

    /**
     * Ends the cull phase: restores the caller's draw shader and issues one
     * barrier making every dispatched command buffer visible to the indirect
     * draws that follow.
     */
    public void endCull() {
        switch (mode) {
            case FRUSTUM -> {
                dispatcher.end();
                GL42.glMemoryBarrier(GL42.GL_COMMAND_BARRIER_BIT);
            }
            case EARLY -> {
                earlyDispatcher.end();
                GL42.glMemoryBarrier(GL42.GL_COMMAND_BARRIER_BIT);
            }
            case LATE -> {
                if (pairActive) {
                    lateDispatcher.end();
                    // Commands for the draws; history for next frame's early kernel;
                    // counters for the asynchronous read-back.
                    GL42.glMemoryBarrier(GL42.GL_COMMAND_BARRIER_BIT | GL43.GL_SHADER_STORAGE_BARRIER_BIT
                        | GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
                }
            }
        }
    }

    /**
     * Draws one culled region: binds its VAO + indirect buffer and issues a
     * single {@code glMultiDrawElementsIndirect} over every live mesh (culled
     * commands are zero-instance no-ops). Caller owns shader/state setup.
     */
    public void draw(MmsChunkRegion region) {
        int count = region.gpuCommandCount();
        if (count == 0 || region.gpuCmdBufferId == 0) {
            return;
        }
        region.bind();
        GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, region.gpuCmdBufferId);
        GL43.glMultiDrawElementsIndirect(GL11.GL_TRIANGLES, region.indexType(),
            region.gpuCmdOffsetBytes, count, 0);
    }

    /**
     * Restores indirect-buffer and VAO bindings after the pass's draws and
     * fences the pass's commands, so the ring reuses them only once the GPU
     * has drawn them.
     */
    public void endDraw() {
        GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, 0);
        GL30.glBindVertexArray(0);
        commands.endPass();
        if (mode == Mode.LATE && pairActive) {
            statsFence[statsSlot] = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        }
        if (mode == Mode.LATE) {
            pairActive = false;
        }
        mode = Mode.FRUSTUM;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        dispatcher.close();
        commands.close();
        closeHiZ();
    }

    private void closeHiZ() {
        if (earlyDispatcher != null) {
            earlyDispatcher.close();
            earlyDispatcher = null;
        }
        if (lateDispatcher != null) {
            lateDispatcher.close();
            lateDispatcher = null;
        }
        if (pyramid != null) {
            pyramid.close();
            pyramid = null;
        }
        for (int s = 0; s < STATS_SLOTS; s++) {
            if (statsFence[s] != 0) {
                GL32.glDeleteSync(statsFence[s]);
                statsFence[s] = 0;
            }
        }
        if (statsBuffer != 0) {
            GL15.glDeleteBuffers(statsBuffer);
            statsBuffer = 0;
        }
    }
}
