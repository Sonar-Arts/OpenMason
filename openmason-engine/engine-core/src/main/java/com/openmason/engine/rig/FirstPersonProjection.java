package com.openmason.engine.rig;

import org.joml.Matrix4f;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * The first-person projection of a camera ({@code cr_first_person_projection}, OpenMason #161, Unreal 5.5
 * first-person FOV and scale): the character's first-person joints drawn with their own vertical field of view
 * (independent of the world's) and squeezed into a thin depth slab just past the world's near plane, so they never
 * clip into walls yet keep their own depth order; plus the PULL, a world-space scale about the eye that leaves the
 * image alone but moves lighting, fog and shadow lookups next to the camera. Compose the first-person palette with
 * {@code base = pull() x the character's base} and draw it with the camera view and {@link #compute}'s projection.
 *
 * <p>Settings: a value {@code <= 0} takes the native default (FOV: the world's; near 0.01; far 2; depth scale 0.5,
 * the slab = [n, n·(1 + depthScale)] for the world near plane n; pull 0.2, 1 = true positions). Thread-confined;
 * {@link #compute} allocates nothing.
 */
public final class FirstPersonProjection {

    private final MemorySegment desc = Arena.ofAuto().allocate(CendaRig.FIRST_PERSON_DESC_BYTES, 8);
    private final MemorySegment view = Arena.ofAuto().allocate(64, 16);
    private final MemorySegment world = Arena.ofAuto().allocate(64, 16);
    private final MemorySegment projection = Arena.ofAuto().allocate(64, 16);
    private final MemorySegment pull = Arena.ofAuto().allocate(64, 16);

    /** Vertical field of view of the first-person view in degrees ({@code <= 0}: the world's). */
    public FirstPersonProjection fovY(float degrees) {
        desc.set(CendaRig.F32, 0, degrees);
        return this;
    }

    /** First-person near plane and far end of its depth range, true view depths. */
    public FirstPersonProjection depthRange(float near, float far) {
        desc.set(CendaRig.F32, 4, near);
        desc.set(CendaRig.F32, 8, far);
        return this;
    }

    /** Slab thickness as a fraction of the world's near plane. */
    public FirstPersonProjection depthScale(float scale) {
        desc.set(CendaRig.F32, 12, scale);
        return this;
    }

    /** Lighting pull about the eye, {@code 0 < pull <= 1}. */
    public FirstPersonProjection pull(float pull) {
        desc.set(CendaRig.F32, 16, pull);
        return this;
    }

    /**
     * Computes the first-person projection and pull for a camera {@code view} (world to view) and the world's
     * OpenGL perspective {@code worldProjection}. Either output may be null.
     *
     * @throws IllegalArgumentException when the world projection is not a perspective or a setting is invalid
     */
    public void compute(Matrix4f viewMatrix, Matrix4f worldProjection, Matrix4f outProjection, Matrix4f outPull) {
        CendaRig.requireAvailable();
        write(view, viewMatrix);
        write(world, worldProjection);
        // Locals: a conditional expression as an invokeExact argument types as Object.
        MemorySegment projOut = outProjection == null ? MemorySegment.NULL : projection;
        MemorySegment pullOut = outPull == null ? MemorySegment.NULL : pull;
        int rc;
        try {
            rc = (int) CendaRig.FIRST_PERSON_PROJECTION.invokeExact(view, world, desc, projOut, pullOut);
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
        if (rc != CendaRig.OK) throw new IllegalArgumentException("cr_first_person_projection: " + CendaRig.lastError());
        if (outProjection != null) JointPalette.read(projection, 0, outProjection);
        if (outPull != null) JointPalette.read(pull, 0, outPull);
    }

    private static void write(MemorySegment s, Matrix4f m) {
        JointPalette.write(s, 0, m);
    }
}
