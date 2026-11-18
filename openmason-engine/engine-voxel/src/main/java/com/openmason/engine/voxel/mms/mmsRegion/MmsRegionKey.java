package com.openmason.engine.voxel.mms.mmsRegion;

/**
 * A 3D render region's coordinates (rx, ry, rz): a region is
 * {@link MmsChunkRegion#REGION_SPAN}^3 cubes = {@link #REGION_BLOCKS}^3 blocks,
 * {@code rx = floor(cx / 8)} (the native contract's {@code CV_REGION_SHIFT}).
 * Full 64-bit triples — never packed into one long (a packed key aliases
 * far-apart regions once a coordinate leaves its bit field) — hashed by
 * chaining splitmix64's finaliser over the three axes ({@link #hash}).
 */
public record MmsRegionKey(long rx, long ry, long rz) {

    /** Blocks along one cube edge, as a shift (cube = 32^3 blocks). */
    public static final int CUBE_SHIFT = 5;
    /** Blocks along one region edge, as a shift (8 cubes = 256 blocks). */
    public static final int BLOCK_SHIFT = CUBE_SHIFT + MmsChunkRegion.REGION_SHIFT;
    /** Blocks along one region edge: the region-local mesh coordinates span [0, 256] on every axis. */
    public static final int REGION_BLOCKS = 1 << BLOCK_SHIFT;

    /** The region holding cube (cx, cy, cz) (arithmetic shift = floor division, negatives included). */
    public static MmsRegionKey ofCube(long cx, long cy, long cz) {
        int s = MmsChunkRegion.REGION_SHIFT;
        return new MmsRegionKey(cx >> s, cy >> s, cz >> s);
    }

    /** The region holding block (bx, by, bz). */
    public static MmsRegionKey ofBlock(long bx, long by, long bz) {
        return new MmsRegionKey(bx >> BLOCK_SHIFT, by >> BLOCK_SHIFT, bz >> BLOCK_SHIFT);
    }

    /** Lowest block X of the region: the world origin its region-local meshes are relative to. */
    public long minBlockX() {
        return rx << BLOCK_SHIFT;
    }

    public long minBlockY() {
        return ry << BLOCK_SHIFT;
    }

    public long minBlockZ() {
        return rz << BLOCK_SHIFT;
    }

    /** The cube's offset inside this region in blocks (0..224 per axis), for hosts building region-local meshes. */
    public static int cubeLocalOffset(long cubeCoordinate) {
        return (int) (cubeCoordinate & (MmsChunkRegion.REGION_SPAN - 1)) << CUBE_SHIFT;
    }

    @Override
    public int hashCode() {
        return (int) hash(rx, ry, rz);
    }

    /**
     * Chained splitmix64 finaliser: {@code mix(mix(mix(x) + y) + z)} — every
     * axis passes through a full avalanche before the next is folded in, so
     * neighbouring keys spread like random ones. (The
     * XOR-of-products form {@code x*C1 ^ y*C2 ^ z*C3} collides on ~38% of a
     * 16^3 neighbourhood before the finaliser even runs.)
     */
    public static long hash(long x, long y, long z) {
        return mix(mix(mix(x) + y) + z);
    }

    private static long mix(long h) {
        h ^= h >>> 30;
        h *= 0xbf58476d1ce4e5b9L;
        h ^= h >>> 27;
        h *= 0x94d049bb133111ebL;
        h ^= h >>> 31;
        return h;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof MmsRegionKey k && k.rx == rx && k.ry == ry && k.rz == rz;
    }
}
