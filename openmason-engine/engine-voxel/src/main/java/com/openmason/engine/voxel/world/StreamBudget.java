package com.openmason.engine.voxel.world;

/**
 * Per-tick limits of {@link CubeStream#tick} ({@code cv_budget}). A value {@code <= 0} means unlimited.
 * Installs are what costs the owner thread (about 45 us for a terrain cube, a few us for a uniform one);
 * meshes and mesh bytes bound what the host uploads to the GPU in one frame.
 *
 * @param maxInstalls  cubes installed into the world this tick
 * @param maxMeshes    GPU meshes handed out this tick
 * @param maxMeshBytes bytes of handed-out meshes this tick (one mesh larger than this still goes alone)
 * @param maxUnloads   cubes announced for unloading this tick
 */
public record StreamBudget(int maxInstalls, int maxMeshes, long maxMeshBytes, int maxUnloads) {

    /** No limits. */
    public static final StreamBudget UNLIMITED = new StreamBudget(0, 0, 0, 0);

    /** {@code sizeof(cv_budget)}. */
    static final long BYTES = CendaVoxelWorld.BUDGET_BYTES;
}
