package com.openmason.engine.voxel.world;

import java.lang.foreign.MemorySegment;

import static com.openmason.engine.voxel.world.CendaVoxelWorld.I64;

/**
 * Counters of a {@link CubeLod} ({@code cv_lod_stats}) for the debug overlay and tests. {@code selectUsLast} is the
 * planner thread's last selection pass (it no longer runs inside the tick); {@code residentMeshBytes} = node meshes
 * the LOD holds natively (drawn, standby, being replaced); {@code plannerStates} = nodes the selection tracks.
 */
public record LodStats(long nodesCached, long cacheBytes, long nodesDirty, long jobsQueued, long jobsRunning,
                       long nodesBuiltTotal, long nodesLoadedTotal, long nodesSavedTotal, long meshesBuiltTotal,
                       long drawnNodes, long drawnBytes, long fallbackFootprints, long standbyNodes, long readyColumns,
                       long selectUsLast, long tickUsLast, long tickUsMax, long errorsTotal, long corruptTotal,
                       long residentMeshBytes, long plannerStates) {

    static LodStats read(MemorySegment s) {
        long[] v = new long[21];
        for (int i = 0; i < v.length; i++) v[i] = s.get(I64, 8L * i);
        return new LodStats(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9], v[10], v[11], v[12], v[13],
            v[14], v[15], v[16], v[17], v[18], v[19], v[20]);
    }

    /** No dirty node, no queued or running job. */
    public boolean idle() {
        return nodesDirty == 0 && jobsQueued == 0 && jobsRunning == 0;
    }
}
