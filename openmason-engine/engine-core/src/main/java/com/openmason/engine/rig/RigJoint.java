package com.openmason.engine.rig;

import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * One joint of a {@link Rig}: a mesh part or a mesh-less bone (see {@code cenda/rig.h} for the transform
 * rule of each kind). Immutable; the vectors are copied in.
 *
 * @param id          stable id (part / bone UUID) — what clip tracks key on
 * @param name        display name — the fallback track key and the overlay mask key
 * @param kind        {@link Kind#PART} or {@link Kind#BONE}
 * @param parent      index of the parent joint in the rig's list, -1 for a root
 * @param hasGeometry the joint moves rendered geometry (a part with faces, or a joint skin weights use)
 * @param origin      part: pivot; bone: head offset in the parent frame
 * @param pos         rest translation
 * @param rotDeg      rest Euler XYZ degrees
 * @param scale       rest scale
 * @param endpoint    bone tail in the bone's local frame (zero for parts)
 */
public record RigJoint(String id, String name, Kind kind, int parent, boolean hasGeometry,
                       Vector3fc origin, Vector3fc pos, Vector3fc rotDeg, Vector3fc scale, Vector3fc endpoint,
                       boolean hidden) {

    /** A joint its author did not hide. */
    public RigJoint(String id, String name, Kind kind, int parent, boolean hasGeometry,
                    Vector3fc origin, Vector3fc pos, Vector3fc rotDeg, Vector3fc scale, Vector3fc endpoint) {
        this(id, name, kind, parent, hasGeometry, origin, pos, rotDeg, scale, endpoint, false);
    }

    /** Joint kinds (values = {@code CR_JOINT_*}). */
    public enum Kind { PART, BONE }

    public RigJoint {
        kind = kind == null ? Kind.PART : kind;
        origin = copy(origin, 0f);
        pos = copy(pos, 0f);
        rotDeg = copy(rotDeg, 0f);
        scale = copy(scale, 1f);
        endpoint = copy(endpoint, 0f);
    }

    private static Vector3fc copy(Vector3fc v, float fill) {
        return v == null ? new Vector3f(fill) : new Vector3f(v);
    }

    /** A part joint. */
    public static RigJoint part(String id, String name, int parent, boolean hasGeometry,
                                Vector3fc origin, Vector3fc pos, Vector3fc rotDeg, Vector3fc scale) {
        return new RigJoint(id, name, Kind.PART, parent, hasGeometry, origin, pos, rotDeg, scale, null);
    }

    /** A bone joint (rest scale 1). */
    public static RigJoint bone(String id, String name, int parent, boolean hasGeometry,
                                Vector3fc origin, Vector3fc pos, Vector3fc rotDeg, Vector3fc endpoint) {
        return new RigJoint(id, name, Kind.BONE, parent, hasGeometry, origin, pos, rotDeg, null, endpoint);
    }

    /** A copy with another geometry flag. */
    public RigJoint withGeometry(boolean geometry) {
        return new RigJoint(id, name, kind, parent, geometry, origin, pos, rotDeg, scale, endpoint, hidden);
    }

    /**
     * A copy with another hidden flag ({@code CR_JOINT_HIDDEN}: the author hid the part; appearance resolution
     * starts its visibility from it, OpenMason #162; posing ignores it).
     */
    public RigJoint withHidden(boolean hide) {
        return new RigJoint(id, name, kind, parent, hasGeometry, origin, pos, rotDeg, scale, endpoint, hide);
    }
}
