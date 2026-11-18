package com.openmason.engine.voxel.world;

/**
 * Block table flags of the Cenda voxel world ({@code CV_BLOCK_*} in voxel_world.h): what a block id IS to
 * the engine. The host passes one {@code short} of flags per id to {@link CubeWorld#setBlockTable}.
 */
public final class BlockFlags {

    /** Meshed by the native mesher as a unit cube (else host geometry). */
    public static final int CUBE = 0x0001;
    /** Face-culling transparency (leaves, glass): faces are drawn against it; same ids cull each other. */
    public static final int TRANSPARENT = 0x0002;
    /** Solid for ambient occlusion and the opacity mask. */
    public static final int OPAQUE = 0x0004;
    /** Stops sky light: counts for the surface tracker's SKY channel. */
    public static final int SKY_OPAQUE = 0x0008;
    /** Closes the visibility-graph flood (full opaque cubes only). */
    public static final int VIS_OPAQUE = 0x0010;
    /** Drawn in the translucent layer (ice); implies TRANSPARENT. */
    public static final int TRANSLUCENT = 0x0020;
    /** Not a cube: the host (or a bound native model) meshes it. */
    public static final int HOST_GEOMETRY = 0x0040;

    private BlockFlags() {
    }
}
