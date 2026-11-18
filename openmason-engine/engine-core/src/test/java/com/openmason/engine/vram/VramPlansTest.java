package com.openmason.engine.vram;

import com.openmason.engine.diagnostics.GpuMemoryTracker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The VRAM plan runtime: builtin parity with the pre-CEARL constants,
 * install/merge semantics, and the fallback chain allocation sites rely on.
 */
class VramPlansTest {

    @AfterEach
    void resetPlan() {
        VramPlans.reset();
    }

    /**
     * The builtin pools: the LOD pools and the staging ring keep the constants
     * the allocators shipped with before CEARL existed (128k/192k and 32k/48k
     * element counts); the near-terrain pools are sized for the cubic runtime's
     * 3D regions (2026-11-17), each with its own policy.
     */
    @Test
    void builtinPools() {
        VramArenaPolicy chunk = VramPlans.arena(VramPlans.POOL_CHUNK_MESH);
        assertEquals(2L << 20, chunk.vertexInitialBytes());
        assertEquals(64L << 10, chunk.indexInitialBytes());
        assertEquals(1.5, chunk.growthFactor());
        assertEquals(0.125, chunk.growthReserve());
        assertEquals(4, chunk.alignElements());
        assertEquals(0.4, chunk.trimFraction());
        assertTrue(chunk.sparseGrowth());
        assertEquals(VramPlans.CUBE_REGION_WATER, VramPlans.arena(VramPlans.POOL_CHUNK_WATER));
        assertEquals(VramPlans.CUBE_REGION_STAMP, VramPlans.arena(VramPlans.POOL_CHUNK_STAMP));
        // u32 stamp indices: 6 per 4 COMPACT20 vertices of 20 B = 0.3x the vertex bytes.
        assertTrue(VramPlans.CUBE_REGION_STAMP.indexInitialBytes()
            >= VramPlans.CUBE_REGION_STAMP.vertexInitialBytes() * 3 / 10);

        VramArenaPolicy lod = VramPlans.arena(VramPlans.POOL_LOD_TERRAIN);
        assertEquals(128 * 1024 * 40L, lod.vertexInitialBytes());
        assertEquals(192 * 1024 * 2L, lod.indexInitialBytes());

        VramArenaPolicy water = VramPlans.arena(VramPlans.POOL_LOD_WATER);
        assertEquals(32 * 1024 * 40L, water.vertexInitialBytes());
        assertEquals(48 * 1024 * 2L, water.indexInitialBytes());

        assertEquals(8L << 20, VramPlans.budgetBytes(VramPlans.POOL_STAGING, 0));

        // The default growth policy (explicit-capacity regions, unknown pools) is unchanged.
        assertEquals(16 * 1024 * 40L, VramPlans.defaultArena().vertexInitialBytes());
        assertEquals(1.75, VramPlans.defaultArena().growthFactor());
        assertEquals(0, VramPlans.defaultArena().trimFraction());
    }

    @Test
    void installMergesOverBuiltinPools() {
        LinkedHashMap<String, VramPool> pools = new LinkedHashMap<>();
        pools.put(VramPlans.POOL_CHUNK_MESH, new VramPool(VramPlans.POOL_CHUNK_MESH,
            GpuMemoryTracker.Category.CHUNK_MESH, 0, 100,
            VramPool.Storage.STATIC, VramPool.Grow.COPY,
            new VramArenaPolicy(1L << 20, 64L << 10, 2.0, 0.5, 8, 0, false)));
        VramPlans.install(new VramPlan("custom", 1L << 30, 0.1, pools, List.of()));

        assertEquals("custom", VramPlans.active().name());
        // Overridden pool serves the new policy...
        assertEquals(1L << 20, VramPlans.arena(VramPlans.POOL_CHUNK_MESH).vertexInitialBytes());
        assertEquals(2.0, VramPlans.arena(VramPlans.POOL_CHUNK_MESH).growthFactor());
        // ...while omitted pools keep their builtin defaults.
        assertEquals(128 * 1024 * 40L,
            VramPlans.arena(VramPlans.POOL_LOD_TERRAIN).vertexInitialBytes());
        assertEquals(8L << 20, VramPlans.budgetBytes(VramPlans.POOL_STAGING, 0));
    }

    @Test
    void resetRestoresBuiltin() {
        VramPlans.install(new VramPlan("temp", 0, 0, new LinkedHashMap<>(), List.of()));
        assertEquals("temp", VramPlans.active().name());
        VramPlans.reset();
        assertEquals("builtin", VramPlans.active().name());
    }

    @Test
    void unknownPoolFallsBackToChunkDefaults() {
        VramArenaPolicy p = VramPlans.arena("no_such_pool");
        assertEquals(1.75, p.growthFactor());
        assertEquals(640L << 10, p.vertexInitialBytes());
    }

    @Test
    void pressureMathMatchesTheSoftBudget() {
        VramPlan plan = new VramPlan("p", 1000, 0.2, new LinkedHashMap<>(), List.of());
        assertEquals(800, plan.softBudgetBytes());
        assertEquals(0.5, plan.pressure(400), 1e-9);
        assertTrue(Double.isNaN(VramPlans.builtin().pressure(123)));
    }
}
