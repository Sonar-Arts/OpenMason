package com.openmason.engine.character;

import com.openmason.engine.rig.Rig;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.ref.Cleaner;
import java.lang.ref.Reference;
import java.util.Objects;

/**
 * What a character editor draws over its model (OpenMason #163): the rig posed in the reference pose (or given
 * locals), each joint's point, tail, profile role and role state, retarget chains, eye and hip lines, socket
 * frames and the capsule preview, plus ray picking. Built natively ({@code cc_char_overlay},
 * {@code cenda/character_edit.h}) and copied into plain arrays here; build a new one when the definition's
 * revision changes. Picking calls back into the native overlay, which this object keeps until {@link #close()}.
 */
public final class CharacterOverlay implements AutoCloseable {

    /** Segment kinds. */
    public static final int SEGMENT_CHAIN = 0;
    public static final int SEGMENT_EYE_LINE = 1;
    public static final int SEGMENT_HIP_LINE = 2;

    /** Role-state bits ({@code CC_ROLE_*}). */
    public static final int ROLE_MAPPED = 1;
    public static final int ROLE_REQUIRED = 1 << 1;
    public static final int ROLE_JOINT_MISSING = 1 << 2;
    public static final int ROLE_DUPLICATE = 1 << 3;
    public static final int ROLE_HIERARCHY = 1 << 4;
    public static final int ROLE_SIDE_MISMATCH = 1 << 5;
    public static final int ROLE_MIRROR_MISMATCH = 1 << 6;
    public static final int ROLE_ERROR = 1 << 7;

    /** Pick kinds. */
    public enum PickKind { NONE, JOINT, SOCKET }

    public record Pick(PickKind kind, int index, float distance) {
        public static final Pick NONE = new Pick(PickKind.NONE, -1, Float.POSITIVE_INFINITY);
    }

    /** The capsule preview: base (feet, centred on the hips), height, radius, eye and hip heights. */
    public record Capsule(float baseX, float baseY, float baseZ, float height, float radius, float eyeHeight,
                          float hipHeight) {
    }

    private final int jointCount;
    private final float[] points;
    private final float[] tails;
    private final int[] roles;
    private final int[] states;
    private final float[] segments;
    private final int[] segmentKinds;
    private final int[] segmentIndices;
    private final float[] socketMatrices;
    private final int[] socketJoints;
    private final int[] socketIndices;
    private final int[] socketStandard;
    private final Capsule capsule;
    private final float[] skin;
    private final float[] world;
    private static final Cleaner CLEANER = Cleaner.create();

    private static final class State implements Runnable {
        volatile MemorySegment handle;

        State(MemorySegment handle) {
            this.handle = handle;
        }

        @Override
        public void run() {
            MemorySegment h = handle;
            handle = null;
            if (h != null && !h.equals(MemorySegment.NULL)) {
                try {
                    CendaCharacter.OV_DESTROY.invokeExact(h);
                } catch (Throwable ignored) {
                    // best effort at shutdown
                }
            }
        }
    }

    private final State state;
    private Cleaner.Cleanable cleanable;

    private CharacterOverlay(MemorySegment h) throws Throwable {
        this.state = new State(h);
        jointCount = (int) CendaCharacter.OV_JOINT_COUNT.invokeExact(h);
        points = new float[jointCount * 3];
        tails = new float[jointCount * 3];
        roles = new int[jointCount];
        states = new int[jointCount];
        MemorySegment j = ((MemorySegment) CendaCharacter.OV_JOINTS.invokeExact(h)).reinterpret(jointCount * 32L);
        for (int i = 0; i < jointCount; i++) {
            long o = i * 32L;
            for (int k = 0; k < 3; k++) {
                points[i * 3 + k] = j.get(ValueLayout.JAVA_FLOAT, o + k * 4L);
                tails[i * 3 + k] = j.get(ValueLayout.JAVA_FLOAT, o + 12 + k * 4L);
            }
            roles[i] = j.get(ValueLayout.JAVA_INT, o + 24);
            states[i] = j.get(ValueLayout.JAVA_INT, o + 28);
        }
        int ns = (int) CendaCharacter.OV_SEGMENT_COUNT.invokeExact(h);
        segments = new float[ns * 6];
        segmentKinds = new int[ns];
        segmentIndices = new int[ns];
        if (ns > 0) {
            MemorySegment s = ((MemorySegment) CendaCharacter.OV_SEGMENTS.invokeExact(h)).reinterpret(ns * 32L);
            for (int i = 0; i < ns; i++) {
                long o = i * 32L;
                for (int k = 0; k < 6; k++) segments[i * 6 + k] = s.get(ValueLayout.JAVA_FLOAT, o + k * 4L);
                segmentKinds[i] = s.get(ValueLayout.JAVA_INT, o + 24);
                segmentIndices[i] = s.get(ValueLayout.JAVA_INT, o + 28);
            }
        }
        int nk = (int) CendaCharacter.OV_SOCKET_COUNT.invokeExact(h);
        socketMatrices = new float[nk * 16];
        socketJoints = new int[nk];
        socketIndices = new int[nk];
        socketStandard = new int[nk];
        if (nk > 0) {
            MemorySegment s = ((MemorySegment) CendaCharacter.OV_SOCKETS.invokeExact(h)).reinterpret(nk * 80L);
            for (int i = 0; i < nk; i++) {
                long o = i * 80L;
                for (int k = 0; k < 16; k++) socketMatrices[i * 16 + k] = s.get(ValueLayout.JAVA_FLOAT, o + k * 4L);
                socketJoints[i] = s.get(ValueLayout.JAVA_INT, o + 64);
                socketIndices[i] = s.get(ValueLayout.JAVA_INT, o + 68);
                socketStandard[i] = s.get(ValueLayout.JAVA_INT, o + 72);
            }
        }
        MemorySegment c = ((MemorySegment) CendaCharacter.OV_CAPSULE.invokeExact(h)).reinterpret(32);
        capsule = new Capsule(c.get(ValueLayout.JAVA_FLOAT, 0), c.get(ValueLayout.JAVA_FLOAT, 4),
            c.get(ValueLayout.JAVA_FLOAT, 8), c.get(ValueLayout.JAVA_FLOAT, 12), c.get(ValueLayout.JAVA_FLOAT, 16),
            c.get(ValueLayout.JAVA_FLOAT, 20), c.get(ValueLayout.JAVA_FLOAT, 24));
        skin = ((MemorySegment) CendaCharacter.OV_SKIN.invokeExact(h)).reinterpret(jointCount * 64L)
            .toArray(ValueLayout.JAVA_FLOAT);
        world = ((MemorySegment) CendaCharacter.OV_WORLD.invokeExact(h)).reinterpret(jointCount * 64L)
            .toArray(ValueLayout.JAVA_FLOAT);
    }

    /**
     * Builds the overlay.
     *
     * @param profile   may be null (no roles or states)
     * @param locals    9 floats per joint (pos, rot degrees, scale), or null for the reference pose
     * @param boundsMin the model's rest mesh bounds, or null (with boundsMax) to use the joints'
     */
    public static CharacterOverlay build(CharacterDefinition definition, Rig rig, CharacterProfile profile, float[] locals,
                                         float[] boundsMin, float[] boundsMax) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(rig, "rig");
        CendaCharacter.requireAvailable();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment l = locals == null ? MemorySegment.NULL : arena.allocateFrom(ValueLayout.JAVA_FLOAT, locals);
            MemorySegment lo = boundsMin == null ? MemorySegment.NULL : arena.allocateFrom(ValueLayout.JAVA_FLOAT, boundsMin);
            MemorySegment hi = boundsMax == null ? MemorySegment.NULL : arena.allocateFrom(ValueLayout.JAVA_FLOAT, boundsMax);
            MemorySegment p = profile == null ? MemorySegment.NULL : profile.handle();
            MemorySegment h = (MemorySegment) CendaCharacter.OVERLAY.invokeExact(definition.handle(), rig.nativeHandle(), p, l, lo, hi);
            if (h.equals(MemorySegment.NULL)) throw new IllegalArgumentException("overlay: " + CendaCharacter.lastError());
            CharacterOverlay overlay;
            try {
                overlay = new CharacterOverlay(h);
            } catch (Throwable t) {
                CendaCharacter.OV_DESTROY.invokeExact(h);
                throw t;
            }
            overlay.cleanable = CLEANER.register(overlay, overlay.state);
            return overlay;
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        } finally {
            Reference.reachabilityFence(definition);
            Reference.reachabilityFence(rig);
            Reference.reachabilityFence(profile);
        }
    }

    public int jointCount() {
        return jointCount;
    }

    /** Joint points (bone head / part pivot), 3 floats per joint, model space. */
    public float[] points() {
        return points;
    }

    /** Joint tails (bone tail; a part's pivot again), 3 floats per joint. */
    public float[] tails() {
        return tails;
    }

    /** Profile role index per joint: -1 none, -2 a role the profile lacks. */
    public int[] roles() {
        return roles;
    }

    /** {@code ROLE_*} bits of each joint's role (0 without one). */
    public int[] states() {
        return states;
    }

    public int segmentCount() {
        return segmentKinds.length;
    }

    /** 6 floats per segment: a then b. */
    public float[] segments() {
        return segments;
    }

    public int[] segmentKinds() {
        return segmentKinds;
    }

    /** Chain index for chain segments, else -1. */
    public int[] segmentIndices() {
        return segmentIndices;
    }

    public int socketCount() {
        return socketJoints.length;
    }

    /** 16 floats (column-major) per socket: its joint's skin matrix x T R S. */
    public float[] socketMatrices() {
        return socketMatrices;
    }

    public int[] socketJoints() {
        return socketJoints;
    }

    /** The socket's index in the definition. */
    public int[] socketIndices() {
        return socketIndices;
    }

    /** The standard-socket index, -1 for a custom socket. */
    public int[] socketStandard() {
        return socketStandard;
    }

    public Capsule capsule() {
        return capsule;
    }

    /** Skin matrices of the pose, 16 floats per joint (what the model draws with). */
    public float[] skin() {
        return skin;
    }

    /** World matrices of the pose, 16 floats per joint. */
    public float[] world() {
        return world;
    }

    /**
     * The joint point or socket origin nearest a model-space ray within {@code tolerance + tolerancePerDistance x
     * distance}; the nearest along the ray among hits wins. candidatesOnly: parts are not picked when the rig has bones.
     */
    public Pick pick(float ox, float oy, float oz, float dx, float dy, float dz, float tolerance,
                     float tolerancePerDistance, boolean candidatesOnly) {
        MemorySegment h = state.handle;
        if (h == null) throw new IllegalStateException("CharacterOverlay is closed");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment o = arena.allocateFrom(ValueLayout.JAVA_FLOAT, ox, oy, oz);
            MemorySegment d = arena.allocateFrom(ValueLayout.JAVA_FLOAT, dx, dy, dz);
            MemorySegment index = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment dist = arena.allocate(ValueLayout.JAVA_FLOAT);
            int kind = (int) CendaCharacter.OV_PICK.invokeExact(h, o, d, tolerance, tolerancePerDistance,
                candidatesOnly ? 1 : 0, index, dist);
            if (kind < 0) throw new IllegalArgumentException("pick: " + CendaCharacter.lastError());
            if (kind == 0) return Pick.NONE;
            return new Pick(kind == 1 ? PickKind.JOINT : PickKind.SOCKET, index.get(ValueLayout.JAVA_INT, 0),
                dist.get(ValueLayout.JAVA_FLOAT, 0));
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    @Override
    public void close() {
        cleanable.clean();
    }
}
