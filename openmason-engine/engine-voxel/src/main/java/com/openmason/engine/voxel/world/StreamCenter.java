package com.openmason.engine.voxel.world;

/**
 * One viewer of a {@link CubeStream} ({@code cv_center}): the surface-following load shape around a block
 * position. A cube is wanted when its column lies inside the horizontal disc of {@code radiusH} and it is
 * inside the ellipsoid ({@code radiusH} x {@code radiusV}), inside its column's surface band
 * ({@code bandBelow} under the lowest top / sky surface to {@code bandAbove} over the highest top), or seen
 * by the stream's visibility walk. Rules: {@code voxel_world.h} ({@code cv_center}, "STREAMER RULES").
 *
 * @param x         block x of the viewer
 * @param y         block y
 * @param z         block z
 * @param radiusH   horizontal radius in blocks (render distance)
 * @param radiusV   vertical ellipsoid half-height in blocks
 * @param bandBelow blocks kept loaded below the column top (e.g. 16)
 * @param bandAbove blocks kept loaded above the column top (e.g. 8)
 */
public record StreamCenter(long x, long y, long z, int radiusH, int radiusV, int bandBelow, int bandAbove) {

    /** {@code sizeof(cv_center)}. */
    static final long BYTES = CendaVoxelWorld.CENTER_BYTES;

    /** A viewer with the default surface band (16 below, 8 above). */
    public static StreamCenter of(long x, long y, long z, int radiusH, int radiusV) {
        return new StreamCenter(x, y, z, radiusH, radiusV, 16, 8);
    }
}
