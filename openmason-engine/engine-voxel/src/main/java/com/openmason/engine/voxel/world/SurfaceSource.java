package com.openmason.engine.voxel.world;

/**
 * The host's answer for never-generated space ({@code cv_surface_fn}): for the 32x32 cells of column cube
 * (cx, cz), at {@code [lz * 32 + lx]}, the y of the first free cell above the topmost sky-opaque block the
 * generator WOULD place ({@code sky}) and above the topmost non-air block ({@code top}, fluids included).
 *
 * <p>Called from native code on ANY thread that calls into the world (mesh workers too), possibly
 * concurrently, never while the world holds a lock. It must not call back into the same world. Return
 * false when unknown (never-generated space then counts as open sky). An exception is caught and read as
 * "unknown" (an exception escaping an upcall would kill the JVM).
 */
@FunctionalInterface
public interface SurfaceSource {
    boolean surface(long cx, long cz, long[] sky, long[] top);
}
