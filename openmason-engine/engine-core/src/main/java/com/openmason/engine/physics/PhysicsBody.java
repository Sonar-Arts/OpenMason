package com.openmason.engine.physics;

/**
 * A moving axis-aligned box ({@code cp_body}): the host's mutable mirror, marshalled in and out of
 * every {@link PhysicsWorld#move}/{@link PhysicsWorld#step} call. The box spans
 * {@code [x - halfX, x + halfX] x [y - below, y + above] x [z - halfZ, z + halfZ]} around the host's
 * anchor point (feet for a player, body bottom with legs below for a mob, centre for a drop).
 */
public final class PhysicsBody {

    /** In: was standing. Out: is standing. */
    public static final int ON_GROUND = 1;
    /** A horizontal hit does not zero that axis' velocity (mobs slide along walls). */
    public static final int KEEP_VELOCITY = 2;
    /** Skip the probe that drops {@link #ON_GROUND} after walking off an edge. */
    public static final int NO_GROUND_PROBE = 4;
    /**
     * Mantle: the step rules apply while airborne too — a body blocked sideways climbs onto an
     * obstacle whose top is within its step limits of its feet, and ends the move standing there.
     */
    public static final int AIR_STEP = 8;

    public static final int CONTACT_NEG_X = 1;
    public static final int CONTACT_POS_X = 2;
    public static final int CONTACT_GROUND = 4;
    public static final int CONTACT_CEILING = 8;
    public static final int CONTACT_NEG_Z = 16;
    public static final int CONTACT_POS_Z = 32;
    public static final int CONTACT_STEPPED = 64;
    public static final int CONTACT_LIFTED = 128;
    public static final int CONTACT_LEFT_GROUND = 256;
    /** Any horizontal contact. */
    public static final int CONTACT_HORIZONTAL = CONTACT_NEG_X | CONTACT_POS_X | CONTACT_NEG_Z | CONTACT_POS_Z;

    public float x, y, z;
    public float vx, vy, vz;
    public float halfX, halfZ;
    public float below, above;
    /** Any obstacle up to this rise is climbed while standing. */
    public float stepHeight;
    /** {@link PhysicsWorld#SHAPE_PARTIAL} obstacles up to this rise are climbed too. */
    public float partialStepHeight;
    /** Collider categories this body meets. */
    public int mask;
    public int flags;
    /** Out: {@code CONTACT_*} of the last move. */
    public int contacts;

    public PhysicsBody() {
    }

    /** Sets the box: {@code halfX}/{@code halfZ} half extents, {@code below}/{@code above} the anchor. */
    public PhysicsBody box(float halfX, float halfZ, float below, float above) {
        this.halfX = halfX;
        this.halfZ = halfZ;
        this.below = below;
        this.above = above;
        return this;
    }

    public PhysicsBody steps(float stepHeight, float partialStepHeight) {
        this.stepHeight = stepHeight;
        this.partialStepHeight = partialStepHeight;
        return this;
    }

    public PhysicsBody position(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
        return this;
    }

    public PhysicsBody velocity(float vx, float vy, float vz) {
        this.vx = vx;
        this.vy = vy;
        this.vz = vz;
        return this;
    }

    public boolean onGround() {
        return (flags & ON_GROUND) != 0;
    }

    public void setOnGround(boolean onGround) {
        flags = onGround ? flags | ON_GROUND : flags & ~ON_GROUND;
    }

    public boolean hasContact(int contact) {
        return (contacts & contact) != 0;
    }
}
