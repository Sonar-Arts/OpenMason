package com.openmason.engine.rendering;

import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.lwjgl.opengl.GL15;

import java.util.HashMap;
import java.util.Map;

/**
 * The frame's rendering origin: every position handed to a shader is expressed
 * relative to this point instead of relative to world zero.
 *
 * <h2>Why</h2>
 * Shader arithmetic is {@code float32}, whose spacing grows with magnitude —
 * 0.001 blocks at 10k from spawn, 0.008 at 100k, a whole block at 2^24 and
 * 65536 blocks at 2^40. Feeding absolute world coordinates through
 * {@code projection * view * model} therefore quantizes geometry in proportion
 * to how far the player has walked (jittering terrain, z-fighting, per-pixel
 * noise on mobs whose normals are differentiated out of the interpolated
 * position). Rebasing on a point near the camera makes the error scale with
 * distance <em>from the viewer</em> instead, so it is permanently sub-pixel at
 * any world coordinate the int64 block grid can name.
 *
 * <h2>The invariant</h2>
 * Everything a shader sees lives in "render space" — world axes, translated by
 * {@code -origin} on all three axes:
 * <ul>
 *   <li>view matrix: built at the camera's render-space position
 *       ({@link #toRender(Vector3dc, Vector3f)} of the double camera position);</li>
 *   <li>model matrices: {@code M_render = T(-origin) * M_world} — use {@link #modelAt};</li>
 *   <li>mesh / region origin attributes: baked relative and re-baked on every
 *       origin step, see {@link #createOriginBuffer(long, long, long, float)};</li>
 *   <li>light-space matrices: {@code L_render = L_world * T(+origin)} — {@link #acceptRenderSpace};</li>
 *   <li>loose world positions in uniforms, cull AABBs, LOD node and particle
 *       origins: {@link #relative(long, long, long, Vector3f)} /
 *       {@link #relative(double, double, double, Vector3f)}.</li>
 * </ul>
 * Because the same translation is applied to both sides of every product, the
 * rendered result is identical — only the precision changes.
 *
 * <h2>Exactness</h2>
 * The origin is an int64 block coordinate (a multiple of {@link #GRID}), so
 * {@code block - origin} is an exact {@code long} subtraction anywhere in the
 * int64 range, and its float cast is exact while the difference stays under
 * 2^24 blocks — i.e. for everything within 16 million blocks of the camera.
 * Double inputs are split into {@code floor} (exact long) plus fraction before
 * subtracting, so they keep the double's full absolute precision too.
 *
 * <h2>Why it is snapped</h2>
 * The origin moves in {@link #GRID}-block steps rather than tracking the camera
 * continuously. A moving origin shifts the coordinate system the shadow
 * cascades texel-snap against and the phase of world-space procedural patterns
 * (water waves), so each move costs a one-frame shimmer; snapping makes that
 * cost land once per {@value #GRID} blocks travelled, and it bounds the number
 * of origin-buffer re-bakes. Near-camera coordinates stay within
 * ~{@value #GRID}·√3 of zero, where float32 spacing is ~8e-6 blocks.
 *
 * <p>Since the cubic-chunk runtime (2026-11-17) Y is rebased like X and Z: the
 * world is no longer a bounded column, so the vertical axis needs the same
 * protection. Shaders that need absolute world Y (fog by altitude, sea level)
 * must receive the quantity relative to the origin from the host (or add
 * {@link #blockY()} back where float precision allows).
 *
 * <p>Compatibility: {@link #update(float, float)} is the column-world entry
 * point — it snaps X/Z only and pins Y to 0, so a host that has not been ported
 * yet behaves exactly as before. New hosts call {@link #update(double, double, double)}.
 *
 * <p>All methods are render-thread only.
 */
public final class RenderOrigin {

    /**
     * Blocks between origin steps. A power of two so the snap is exact and
     * {@code meshOrigin - origin} is exact for integer mesh origins.
     */
    public static final int GRID = 64;

    private static long originX;
    private static long originY;
    private static long originZ;

    /**
     * Live per-mesh origin buffers, keyed by GL buffer id, valued by the
     * <em>absolute</em> origin they were created with. Held so
     * {@link #refreshBuffers} can re-bake them when the origin steps; the mesh
     * data itself never moves.
     */
    private static final Map<Integer, Registered> ORIGIN_BUFFERS = new HashMap<>();

    /** An absolute origin as exact block + fraction in [0, 1), plus the format's position scale. */
    private record Registered(long bx, long by, long bz, double fx, double fy, double fz, float scale) {
    }

    private static final Vector3f SCRATCH = new Vector3f();

    private RenderOrigin() {}

    // ── The origin ──────────────────────────────────────────────────────────

    /** Current origin X as an exact block coordinate (a multiple of {@link #GRID}). */
    public static long blockX() {
        return originX;
    }

    /** Current origin Y as an exact block coordinate (a multiple of {@link #GRID}). */
    public static long blockY() {
        return originY;
    }

    /** Current origin Z as an exact block coordinate (a multiple of {@link #GRID}). */
    public static long blockZ() {
        return originZ;
    }

    /** Current origin X as a float (exact below 2^24; prefer {@link #blockX()} / {@link #relative}). */
    public static float x() {
        return (float) originX;
    }

    /** Current origin Y as a float (exact below 2^24; prefer {@link #blockY()} / {@link #relative}). */
    public static float y() {
        return (float) originY;
    }

    /** Current origin Z as a float (exact below 2^24; prefer {@link #blockZ()} / {@link #relative}). */
    public static float z() {
        return (float) originZ;
    }

    /** The grid cell (lowest corner) containing {@code coordinate}, as an exact block coordinate. */
    public static long snap(double coordinate) {
        return Math.floorDiv(floorToLong(coordinate), GRID) * GRID;
    }

    /**
     * Re-snaps the origin to the grid cell containing the camera on all three
     * axes and re-bakes every live mesh origin buffer if it moved. Call once
     * per frame on the render thread, before any pass reads a matrix or builds
     * a uniform.
     *
     * @return true when the origin stepped this frame
     */
    public static boolean update(double cameraX, double cameraY, double cameraZ) {
        return moveTo(snap(cameraX), snap(cameraY), snap(cameraZ));
    }

    /** {@link #update(double, double, double)} from a double camera position. */
    public static boolean update(Vector3dc camera) {
        return update(camera.x(), camera.y(), camera.z());
    }

    /**
     * Column-world compatibility: snaps X and Z, keeps Y at 0 (the pre-cubic
     * behaviour). Hosts that render a bounded column and still subtract only
     * {@link #x()}/{@link #z()} by hand keep working unchanged.
     *
     * @deprecated use {@link #update(double, double, double)}; removed once the
     *             game's renderer is on the cubic runtime (Phase 3).
     */
    @Deprecated
    public static boolean update(float cameraX, float cameraZ) {
        return moveTo(snap(cameraX), 0L, snap(cameraZ));
    }

    private static boolean moveTo(long x, long y, long z) {
        if (x == originX && y == originY && z == originZ) {
            return false;
        }
        originX = x;
        originY = y;
        originZ = z;
        refreshBuffers();
        return true;
    }

    /**
     * Resets the origin to world zero without touching GL — for tests and for
     * teardown between worlds, where no mesh buffers are live.
     */
    public static void reset() {
        originX = 0L;
        originY = 0L;
        originZ = 0L;
        ORIGIN_BUFFERS.clear();
    }

    // ── World → render space ────────────────────────────────────────────────

    /** {@code blockX - origin.x} as a float: exact while the difference is under 2^24 blocks. */
    public static float relativeX(long blockX) {
        return (float) (blockX - originX);
    }

    /** {@code blockY - origin.y} as a float: exact while the difference is under 2^24 blocks. */
    public static float relativeY(long blockY) {
        return (float) (blockY - originY);
    }

    /** {@code blockZ - origin.z} as a float: exact while the difference is under 2^24 blocks. */
    public static float relativeZ(long blockZ) {
        return (float) (blockZ - originZ);
    }

    /** {@code worldX - origin.x}, computed exactly in long + double, then rounded once to float. */
    public static float relativeX(double worldX) {
        return (float) relative(worldX, originX);
    }

    /** {@code worldY - origin.y}, computed exactly in long + double, then rounded once to float. */
    public static float relativeY(double worldY) {
        return (float) relative(worldY, originY);
    }

    /** {@code worldZ - origin.z}, computed exactly in long + double, then rounded once to float. */
    public static float relativeZ(double worldZ) {
        return (float) relative(worldZ, originZ);
    }

    /**
     * The camera-relative position of an integer block point — the one way
     * regions, LOD nodes, particles, shadows and culling turn a world point
     * into render space. Exact for any int64 input within 2^24 of the camera.
     */
    public static Vector3f relative(long blockX, long blockY, long blockZ, Vector3f dest) {
        return dest.set(relativeX(blockX), relativeY(blockY), relativeZ(blockZ));
    }

    /** The camera-relative position of a double world point (one float rounding per axis). */
    public static Vector3f relative(double worldX, double worldY, double worldZ, Vector3f dest) {
        return dest.set(relativeX(worldX), relativeY(worldY), relativeZ(worldZ));
    }

    /** {@code world - origin} for a double world position — the camera, an entity, a light. */
    public static Vector3f toRender(Vector3dc world, Vector3f dest) {
        return relative(world.x(), world.y(), world.z(), dest);
    }

    /**
     * {@code world - origin} for a float world position. A float world position
     * has already lost precision far out; prefer the double/long overloads.
     */
    public static Vector3f toRender(Vector3fc world, Vector3f dest) {
        return relative(world.x(), world.y(), world.z(), dest);
    }

    /**
     * A shared-scratch {@link #toRender} for uniform uploads. The result is
     * valid only until the next call — never store it.
     */
    public static Vector3f toRender(Vector3fc world) {
        return toRender(world, SCRATCH);
    }

    /** A shared-scratch {@link #toRender(Vector3dc, Vector3f)}; valid until the next call. */
    public static Vector3f toRender(Vector3dc world) {
        return toRender(world, SCRATCH);
    }

    /** {@code render + origin} in double — the exact inverse of the rebase. */
    public static Vector3d toWorld(Vector3fc render, Vector3d dest) {
        return dest.set(render.x() + (double) originX, render.y() + (double) originY,
            render.z() + (double) originZ);
    }

    /** {@code render + origin} in float (loses precision far from world zero; prefer the double overload). */
    public static Vector3f toWorld(Vector3fc render, Vector3f dest) {
        return dest.set((float) (render.x() + (double) originX), (float) (render.y() + (double) originY),
            (float) (render.z() + (double) originZ));
    }

    /** A model matrix translated to a world position, in render space. */
    public static Matrix4f modelAt(double worldX, double worldY, double worldZ) {
        return modelAt(new Matrix4f(), worldX, worldY, worldZ);
    }

    /** {@link #modelAt} into a caller-owned matrix, for allocation-free paths. */
    public static Matrix4f modelAt(Matrix4f dest, double worldX, double worldY, double worldZ) {
        return dest.translation(relativeX(worldX), relativeY(worldY), relativeZ(worldZ));
    }

    /**
     * Rebases a matrix that was built to consume <em>world</em> positions so it
     * consumes render-space ones instead ({@code m * T(+origin)}) — the
     * conversion for any matrix standing to the left of a position, such as a
     * light-space matrix fitted in world coordinates but used to draw
     * render-space geometry. A float matrix built in world space is only as
     * precise as float at that magnitude; far from world zero build it around
     * the origin instead.
     *
     * <p>Mutates and returns {@code m}.
     */
    public static Matrix4f acceptRenderSpace(Matrix4f m) {
        return m.translate((float) originX, (float) originY, (float) originZ);
    }

    /**
     * The inverse of {@link #acceptRenderSpace} ({@code m * T(-origin)}).
     *
     * @deprecated nothing on the GPU is uploaded in world space any more (region
     *             cull AABBs are region-local + a camera-relative region origin);
     *             kept for the column-world game until Phase 3.
     */
    @Deprecated
    public static Matrix4f acceptWorldSpace(Matrix4f m) {
        return m.translate((float) -originX, (float) -originY, (float) -originZ);
    }

    // ── Per-mesh origin buffers ─────────────────────────────────────────────

    /**
     * Creates the 16-byte per-instance origin attribute buffer for a mesh (or
     * a whole region) whose stored positions are relative to the integer block
     * {@code (meshOriginX, meshOriginY, meshOriginZ)}, baked into render space
     * and registered for re-baking on origin steps. Exact for the full int64
     * range.
     *
     * <p>{@code scale} rides in {@code w} — see {@code MmsVertexFormat}, whose
     * shaders compute {@code pos = aOrigin.xyz + position * aOrigin.w}.
     */
    public static int createOriginBuffer(long meshOriginX, long meshOriginY, long meshOriginZ, float scale) {
        return register(new Registered(meshOriginX, meshOriginY, meshOriginZ, 0d, 0d, 0d, scale));
    }

    /** {@link #createOriginBuffer(long, long, long, float)} for a non-integer (double) origin. */
    public static int createOriginBuffer(double meshOriginX, double meshOriginY, double meshOriginZ, float scale) {
        long bx = floorToLong(meshOriginX);
        long by = floorToLong(meshOriginY);
        long bz = floorToLong(meshOriginZ);
        return register(new Registered(bx, by, bz,
            meshOriginX - bx, meshOriginY - by, meshOriginZ - bz, scale));
    }

    /**
     * Float-origin compatibility overload (column-world meshes).
     *
     * @deprecated use the long or double overload.
     */
    @Deprecated
    public static int createOriginBuffer(float meshOriginX, float meshOriginY,
                                         float meshOriginZ, float scale) {
        return createOriginBuffer((double) meshOriginX, (double) meshOriginY, (double) meshOriginZ, scale);
    }

    private static int register(Registered origin) {
        int id = GL15.glGenBuffers();
        ORIGIN_BUFFERS.put(id, origin);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, id);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, baked(origin, new float[4]), GL15.GL_STATIC_DRAW);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        return id;
    }

    /**
     * Drops a buffer from the re-bake set. Call immediately before deleting it;
     * a buffer left registered would be written to after deletion.
     */
    public static void releaseOriginBuffer(int id) {
        ORIGIN_BUFFERS.remove(id);
    }

    /** Number of live registered origin buffers — for diagnostics and tests. */
    public static int liveOriginBufferCount() {
        return ORIGIN_BUFFERS.size();
    }

    /**
     * Re-bakes every live origin buffer against the current origin. One
     * 16-byte write per registered buffer (one per region on the cubic path),
     * which is why the origin steps on a coarse grid rather than following the
     * camera.
     */
    private static void refreshBuffers() {
        if (ORIGIN_BUFFERS.isEmpty()) {
            return;
        }
        float[] staging = new float[4];
        for (Map.Entry<Integer, Registered> entry : ORIGIN_BUFFERS.entrySet()) {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, entry.getKey());
            GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, baked(entry.getValue(), staging));
        }
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    /** The render-space (x, y, z, scale) of a registered origin. Package-private for tests. */
    static float[] baked(long bx, long by, long bz, double fx, double fy, double fz, float scale, float[] out) {
        return baked(new Registered(bx, by, bz, fx, fy, fz, scale), out);
    }

    private static float[] baked(Registered o, float[] out) {
        out[0] = (float) ((double) (o.bx - originX) + o.fx);
        out[1] = (float) ((double) (o.by - originY) + o.fy);
        out[2] = (float) ((double) (o.bz - originZ) + o.fz);
        out[3] = o.scale;
        return out;
    }

    // ── Exact double helpers ────────────────────────────────────────────────

    /** {@code world - origin} with the integer part subtracted exactly in long. */
    private static double relative(double world, long origin) {
        long whole = floorToLong(world);
        return (double) (whole - origin) + (world - whole);
    }

    /**
     * {@code floor(value)} as a long. Doubles past 2^53 are already integers,
     * and past 2^63 the cast saturates (Java semantics), which is the best a
     * double can say about such a coordinate anyway.
     */
    static long floorToLong(double value) {
        return (long) Math.floor(value);
    }
}
