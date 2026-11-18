package com.openmason.engine.voxel.world;

/**
 * Cube coordinates (cx, cy, cz) = block coordinates floor-divided by 32. Full 64-bit triples: never pack
 * them into one long (a packed key aliases far-apart cubes once a coordinate leaves its bit field).
 */
public record CubePos(long x, long y, long z) {

    /** The cube holding block (bx, by, bz) (arithmetic shift = floor division). */
    public static CubePos ofBlock(long bx, long by, long bz) {
        return new CubePos(bx >> 5, by >> 5, bz >> 5);
    }

    /** Lowest block coordinate of the cube along each axis. */
    public long minBlockX() {
        return x << 5;
    }

    public long minBlockY() {
        return y << 5;
    }

    public long minBlockZ() {
        return z << 5;
    }

    @Override
    public int hashCode() {
        return (int) hash(x, y, z);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CubePos p && p.x == x && p.y == y && p.z == z;
    }

    /** The engine's triple hash (native twin: int3_key.hpp hash3): chained polynomial
     *  ((x * K1 + y) * K2 + z), then splitmix64's finaliser. */
    public static long hash(long x, long y, long z) {
        long h = x * 0x9e3779b97f4a7c15L + y;
        h = h * 0xc2b2ae3d27d4eb4fL + z;
        h ^= h >>> 30;
        h *= 0xbf58476d1ce4e5b9L;
        h ^= h >>> 27;
        h *= 0x94d049bb133111ebL;
        h ^= h >>> 31;
        return h;
    }
}
