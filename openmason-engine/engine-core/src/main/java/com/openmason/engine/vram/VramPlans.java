package com.openmason.engine.vram;

import com.openmason.engine.diagnostics.GpuMemoryTracker;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The process-wide active VRAM plan — the seam between compiled CEARL plans
 * and the allocation call sites ({@code MmsChunkRegion} arenas, the LOD region
 * batcher, the staging ring).
 *
 * <p>The <b>builtin plan</b>: the near-terrain pools ({@code chunk_mesh},
 * {@code chunk_water}, {@code chunk_stamp}) are sized for the cubic runtime's
 * 3D regions (8x8x8 cubes = 256^3 blocks, region-local pulled quads; see
 * {@link #CUBE_REGION_MESH}); the LOD pools, the staging ring and the
 * {@link #defaultArena() default growth policy} keep the constants they shipped
 * with before CEARL existed.
 * {@link #install} merges an incoming plan over the builtin pools: a plan only
 * has to name what it changes, and a pool it omits keeps its proven defaults.
 *
 * <p>Install once at startup, before any GL resources are created; reads are
 * lock-free afterwards.
 */
public final class VramPlans {

    public static final String POOL_CHUNK_MESH = "chunk_mesh";
    /** Near-chunk water regions (falls back to chunk_mesh's policy when a plan doesn't name it). */
    public static final String POOL_CHUNK_WATER = "chunk_water";
    /** Near-chunk stamp regions — non-cube SBO geometry under a pulled atlas format. */
    public static final String POOL_CHUNK_STAMP = "chunk_stamp";
    public static final String POOL_LOD_TERRAIN = "lod_terrain";
    public static final String POOL_LOD_WATER = "lod_water";
    public static final String POOL_STAGING = "staging";

    /** The pre-CEARL growth policy: ×1.75 growth, 25% reserve, 4-element alignment, no trim. */
    private static final VramArenaPolicy DEFAULT_GROWTH_CHUNK = new VramArenaPolicy(
        640L * 1024,          // 16384 vertices at the 40-byte stride
        48L * 1024,           // 24576 u16 indices
        1.75, 0.25, 4, 0, false);

    /**
     * Cubic region terrain arena (2026-11-17). A region is 256x256 blocks of
     * footprint — four times the old 8x8-column region — but only the cubes
     * that actually hold faces upload anything (uniform air/stone cubes are
     * empty meshes and cost nothing), and the surface usually splits across
     * two Y regions. The old dense column region carried ~1.3 MiB of 16-byte
     * pulled quads, so a surface region starts at 2 MiB (131072 quads) and
     * grows sparse (page commitment) where the driver allows; trim at 40%
     * gives back what a region stops using. Indices are unused by pulled
     * regions (kept for the compact/legacy formats).
     */
    public static final VramArenaPolicy CUBE_REGION_MESH = new VramArenaPolicy(
        2L << 20, 64L * 1024, 1.5, 0.125, 4, 0.4, true);

    /**
     * Cubic region water (WATERQUAD16, greedy-merged per cube): only regions
     * holding a sea level or lakes upload, and a flat sea is a handful of
     * 32-wide records per cube — 512 KiB covers a full ocean region.
     */
    public static final VramArenaPolicy CUBE_REGION_WATER = new VramArenaPolicy(
        512L * 1024, 24L * 1024, 1.5, 0.125, 4, 0.4, true);

    /**
     * Cubic region stamps (COMPACT20 vertices + u32 indices from the native
     * stamp emitter: snow layers, stairs, slabs, crosses, models). ~45 KiB per
     * old 16x16 chunk in a tundra ⇒ ~3 MiB for a dense 256^2 surface region;
     * start at 1 MiB. Index bytes are sized for u32 (6 indices per 4 vertices of
     * 20 B ⇒ 0.3x the vertex bytes).
     */
    public static final VramArenaPolicy CUBE_REGION_STAMP = new VramArenaPolicy(
        1L << 20, 320L * 1024, 1.5, 0.125, 4, 0.4, true);

    private static final VramPlan BUILTIN = buildBuiltin();

    private static volatile VramPlan active = BUILTIN;

    private VramPlans() {
    }

    private static VramPlan buildBuiltin() {
        LinkedHashMap<String, VramPool> pools = new LinkedHashMap<>();
        pools.put(POOL_CHUNK_MESH, new VramPool(POOL_CHUNK_MESH,
            GpuMemoryTracker.Category.CHUNK_MESH, 0, 100,
            VramPool.Storage.STATIC, VramPool.Grow.SPARSE, CUBE_REGION_MESH));
        pools.put(POOL_CHUNK_WATER, new VramPool(POOL_CHUNK_WATER,
            GpuMemoryTracker.Category.CHUNK_MESH, 0, 90,
            VramPool.Storage.STATIC, VramPool.Grow.SPARSE, CUBE_REGION_WATER));
        pools.put(POOL_CHUNK_STAMP, new VramPool(POOL_CHUNK_STAMP,
            GpuMemoryTracker.Category.CHUNK_MESH, 0, 95,
            VramPool.Storage.STATIC, VramPool.Grow.SPARSE, CUBE_REGION_STAMP));
        pools.put(POOL_LOD_TERRAIN, new VramPool(POOL_LOD_TERRAIN,
            GpuMemoryTracker.Category.CHUNK_MESH, 0, 60,
            VramPool.Storage.STATIC, VramPool.Grow.COPY,
            new VramArenaPolicy(5L * 1024 * 1024, 384L * 1024, 1.75, 0.25, 4, 0, false)));
        pools.put(POOL_LOD_WATER, new VramPool(POOL_LOD_WATER,
            GpuMemoryTracker.Category.CHUNK_MESH, 0, 60,
            VramPool.Storage.STATIC, VramPool.Grow.COPY,
            new VramArenaPolicy(1280L * 1024, 96L * 1024, 1.75, 0.25, 4, 0, false)));
        pools.put(POOL_STAGING, new VramPool(POOL_STAGING,
            GpuMemoryTracker.Category.OTHER, 8L * 1024 * 1024, 10,
            VramPool.Storage.PERSISTENT, VramPool.Grow.COPY, null));
        return new VramPlan("builtin", 0, 0, pools, List.of());
    }

    public static VramPlan active() {
        return active;
    }

    public static VramPlan builtin() {
        return BUILTIN;
    }

    /**
     * Installs a compiled plan, merged over the builtin pools (installed pools
     * win by name; omitted pools inherit the builtin defaults).
     */
    public static void install(VramPlan plan) {
        LinkedHashMap<String, VramPool> merged = new LinkedHashMap<>(BUILTIN.pools());
        merged.putAll(plan.pools());
        active = new VramPlan(plan.name(), plan.deviceBudgetBytes(), plan.headroom(),
            merged, plan.pressureRules());
    }

    /** Back to the builtin plan (tests, and the failure path of a bad plan file). */
    public static void reset() {
        active = BUILTIN;
    }

    /**
     * The arena policy for a pool: the active plan's, falling back to the
     * builtin pool of the same name, then to chunk-mesh defaults. Never null —
     * an allocation site always has a policy to run with.
     */
    public static VramArenaPolicy arena(String poolName) {
        VramPool pool = active.pool(poolName);
        if (pool != null && pool.arena() != null) {
            return pool.arena();
        }
        VramPool builtin = BUILTIN.pool(poolName);
        if (builtin != null && builtin.arena() != null) {
            return builtin.arena();
        }
        return DEFAULT_GROWTH_CHUNK;
    }

    /** The pre-CEARL default growth policy (for callers with explicit capacities). */
    public static VramArenaPolicy defaultArena() {
        return DEFAULT_GROWTH_CHUNK;
    }

    /** A pool's budget in bytes, or {@code fallback} when unset/undeclared. */
    public static long budgetBytes(String poolName, long fallback) {
        VramPool pool = active.pool(poolName);
        if (pool != null && pool.budgetBytes() > 0) {
            return pool.budgetBytes();
        }
        return fallback;
    }

    /** Current plan pressure measured against tracked GPU bytes (NaN = no budget). */
    public static double currentPressure() {
        return active.pressure(GpuMemoryTracker.getInstance().getTotalBytes());
    }
}
