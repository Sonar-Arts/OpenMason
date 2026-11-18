package com.openmason.engine.voxel.world;

import java.lang.foreign.MemorySegment;

import static com.openmason.engine.voxel.world.CendaVoxelWorld.I64;

/**
 * Counters of a {@link CubeStream} ({@code cv_stream_stats}) for debug overlays and tests. A snapshot: fresh
 * when taken between ticks, otherwise the one of the last time the owner was idle.
 *
 * @param cubesLoaded        installed cubes the stream tracks
 * @param cubesGenerating    load/generate jobs in flight
 * @param cubesMeshing       mesh jobs in flight or queued
 * @param cubesQueued        wanted/support cubes absent from the world (latest plan)
 * @param generatedTotal     cubes generated
 * @param loadedTotal        cubes loaded from the store
 * @param savedTotal         cube records written
 * @param meshedTotal        meshes handed out (or found empty)
 * @param meshBytesPending   bytes of built meshes waiting for the hand-out budget
 * @param cubesWanted        WANTED cubes of the latest plan
 * @param cubesNeeded        WANTED + population SUPPORT cubes
 * @param populating         populations in flight or waiting
 * @param populatedTotal     populations run
 * @param installsPending    finished loads waiting for the install budget
 * @param meshesPending      built meshes waiting for the hand-out budget
 * @param unloadsPending     cubes announced or queued for unloading
 * @param unloadedTotal      cubes removed
 * @param columnsTracked     columns the stream tracks
 * @param columnsReady       columns currently READY (far LOD hidden)
 * @param walkCubes          cubes the visibility walks contributed to the latest plan
 * @param plansTotal         plans computed
 * @param planPending        1 while a plan is computing or not yet applied
 * @param planUsLast         planner time of the last plan (us)
 * @param tickUsLast         owner time of the last tick (us)
 * @param tickUsMax          longest tick so far (us)
 * @param staleMeshesTotal   meshes dropped because the cube changed while they were built
 * @param errorsTotal        store/generator/record errors
 * @param levelChangesTotal  cube level changes applied from plans (unchanged over two plans = settled)
 */
public record StreamStats(long cubesLoaded, long cubesGenerating, long cubesMeshing, long cubesQueued,
                          long generatedTotal, long loadedTotal, long savedTotal, long meshedTotal,
                          long meshBytesPending, long cubesWanted, long cubesNeeded, long populating,
                          long populatedTotal, long installsPending, long meshesPending, long unloadsPending,
                          long unloadedTotal, long columnsTracked, long columnsReady, long walkCubes,
                          long plansTotal, long planPending, long planUsLast, long tickUsLast, long tickUsMax,
                          long staleMeshesTotal, long errorsTotal, long levelChangesTotal) {

    static StreamStats read(MemorySegment s) {
        long[] v = new long[28];
        for (int i = 0; i < v.length; i++) v[i] = s.get(I64, i * 8L);
        return new StreamStats(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9], v[10], v[11], v[12],
            v[13], v[14], v[15], v[16], v[17], v[18], v[19], v[20], v[21], v[22], v[23], v[24], v[25], v[26], v[27]);
    }

    /** No work in flight or waiting (plans aside). */
    public boolean workIdle() {
        return cubesGenerating == 0 && installsPending == 0 && cubesMeshing == 0 && meshesPending == 0
            && populating == 0 && unloadsPending == 0 && cubesQueued == 0;
    }
}
