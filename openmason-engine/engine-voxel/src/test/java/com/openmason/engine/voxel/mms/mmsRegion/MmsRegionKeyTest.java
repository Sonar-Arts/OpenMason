package com.openmason.engine.voxel.mms.mmsRegion;

import com.openmason.engine.voxel.VoxelWorldLimits;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 3D region keying: floor semantics for negatives, int64 range, no packed-key aliasing. */
class MmsRegionKeyTest {

    @Test
    void regionIsEightCubesOf32BlocksPerAxis() {
        assertEquals(256, MmsRegionKey.REGION_BLOCKS);
        assertEquals(8, MmsChunkRegion.REGION_SPAN);
        assertTrue(VoxelWorldLimits.CUBE_REGION_FITS_FORMATS,
            "QUAD16/WATERQUAD16/COMPACT20 must address a 256^3 region on every axis");
    }

    @Test
    void negativeCubesFloorIntoTheRegionBelow() {
        assertEquals(new MmsRegionKey(0, 0, 0), MmsRegionKey.ofCube(0, 7, 0));
        assertEquals(new MmsRegionKey(-1, -1, -1), MmsRegionKey.ofCube(-1, -8, -3));
        assertEquals(new MmsRegionKey(-2, 1, 0), MmsRegionKey.ofCube(-9, 8, 7));
        // Stonebreak's world floor: y = -384 is cube -12, region -2 (blocks [-512, -256)).
        MmsRegionKey floor = MmsRegionKey.ofBlock(0, -384, 0);
        assertEquals(-2, floor.ry());
        assertEquals(-512, floor.minBlockY());
        assertEquals(floor, MmsRegionKey.ofCube(0, -12, 0));
    }

    @Test
    void blockAndCubeKeyingAgreeAndOriginsAreRegionCorners() {
        long[] samples = {0, 1, 31, 32, 255, 256, -1, -32, -33, -256, -257, 1L << 40, -(1L << 40) - 5,
            Long.MAX_VALUE / 4, Long.MIN_VALUE / 4};
        for (long x : samples) {
            for (long y : new long[]{-385, -384, 0, 639, 640}) {
                MmsRegionKey k = MmsRegionKey.ofBlock(x, y, -x);
                assertEquals(k, MmsRegionKey.ofCube(x >> 5, y >> 5, (-x) >> 5));
                assertTrue(k.minBlockX() <= x && x < k.minBlockX() + MmsRegionKey.REGION_BLOCKS, "x " + x);
                assertTrue(k.minBlockY() <= y && y < k.minBlockY() + MmsRegionKey.REGION_BLOCKS, "y " + y);
                assertTrue(k.minBlockZ() <= -x && -x < k.minBlockZ() + MmsRegionKey.REGION_BLOCKS);
                int local = MmsRegionKey.cubeLocalOffset(x >> 5);
                assertEquals((x >> 5 << 5) - k.minBlockX(), local, "cube offset inside the region");
                assertTrue(local >= 0 && local <= 224);
            }
        }
    }

    /** Keys that a (rx << 32 | rz) packing would alias stay distinct, and the hash spreads them. */
    @Test
    void farApartRegionsNeverAlias() {
        MmsRegionKey a = new MmsRegionKey(1L << 32, 0, 0);
        MmsRegionKey b = new MmsRegionKey(0, 0, 0);
        assertNotEquals(a, b);
        assertNotEquals(new MmsRegionKey(0, 1, 0), new MmsRegionKey(0, 0, 1));
        Set<Integer> hashes = new HashSet<>();
        for (int x = -8; x < 8; x++) {
            for (int y = -8; y < 8; y++) {
                for (int z = -8; z < 8; z++) {
                    hashes.add(new MmsRegionKey(x, y, z).hashCode());
                }
            }
        }
        assertTrue(hashes.size() >= 4094, "4096 neighbouring keys should hash (almost) uniquely: " + hashes.size());
    }
}
