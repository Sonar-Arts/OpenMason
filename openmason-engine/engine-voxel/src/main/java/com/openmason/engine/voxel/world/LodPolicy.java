package com.openmason.engine.voxel.world;

/**
 * Band policy of a {@link CubeLod} ({@code cv_lod_policy}): level {@code k} is drawn from
 * {@code (1 << k) * blocksPerCell} blocks on (FastLOD's constant on-screen cell size), up to {@code maxLevel},
 * out to {@code radius} blocks.
 *
 * @param blocksPerCell      K, at least 46 (keeps horizontally adjacent levels within one of each other)
 * @param maxLevel           coarsest level drawn (at most the LOD's built {@code maxLevel})
 * @param radius             blocks; footprints farther from every viewer are not covered
 * @param incompleteDepth    levels an incomplete (unexplored) footprint is refined before falling back (0..8)
 * @param standbyFrom        blocks: fully READY leaves at least this far get their meshes built natively so a
 *                           COLUMN_UNREADY switches back in the same tick; negative = no standby
 * @param incompleteMinLevel finest level an incomplete footprint is refined to (its explored parts drawn there,
 *                           the rest FALLBACK): 0 = the default (level 1), {@link #INCOMPLETE_LEVEL_0} = level 0,
 *                           else 1..16
 */
public record LodPolicy(int blocksPerCell, int maxLevel, int radius, int incompleteDepth, int standbyFrom,
                        int incompleteMinLevel) {

    static final long BYTES = CendaVoxelWorld.LOD_POLICY_BYTES;

    /** {@link #incompleteMinLevel}: refine incomplete footprints down to level 0 (one cube column). */
    public static final int INCOMPLETE_LEVEL_0 = -1;

    /** The default incomplete refinement floor (level 1). */
    public LodPolicy(int blocksPerCell, int maxLevel, int radius, int incompleteDepth, int standbyFrom) {
        this(blocksPerCell, maxLevel, radius, incompleteDepth, standbyFrom, 0);
    }
}
