package com.openmason.engine.rig;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.ref.Cleaner;

/**
 * One body's mounts ({@code cr_mounts}, cenda/socket.h, Stonebreak #459): items hung on the host's sockets by a
 * TWO-SIDED attachment (Roblox attachments, Unreal item sockets). A mount = a host socket (index into the host's
 * {@link SocketSet}; -1 = the host lacks it, the mount never resolves), an {@code offset} applied on the socket and the
 * item's own {@code attach} point (item space): {@code item frame = socket frame · offset · attach⁻¹}. Named item
 * POINTS (a torch's flame, a rod's tip, a second grip) ride the item: {@code point frame = item frame · point}.
 *
 * <p>{@link #resolve} writes every mount's and point's frame for one posed palette in one native call; read them with
 * {@link #frame} / {@link #point}. Thread-confined; resolve and the reads allocate nothing (changing the mounts does).
 */
public final class MountSet implements AutoCloseable {

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
                    CendaRig.MOUNTS_DESTROY.invokeExact(h);
                } catch (Throwable ignored) {
                    // best effort
                }
            }
        }
    }

    private final State state;
    private final Cleaner.Cleanable cleanable;
    /** pre, root. */
    private final MemorySegment inputs = Arena.ofAuto().allocate(2 * 64L, 16);
    private final MemorySegment preSlice = inputs.asSlice(0, 64);
    private final MemorySegment rootSlice = inputs.asSlice(64, 64);
    private MemorySegment frames = MemorySegment.NULL;
    private MemorySegment points = MemorySegment.NULL;
    private MemorySegment valid = MemorySegment.NULL;
    /** Sizes the outputs were allocated for; -1 = recompute. */
    private int mountCount = -1;
    private int pointCount;
    private int[] pointBase = new int[0];

    public MountSet() {
        CendaRig.requireAvailable();
        try {
            MemorySegment h = (MemorySegment) CendaRig.MOUNTS_CREATE.invokeExact();
            if (h.equals(MemorySegment.NULL)) throw new IllegalStateException("cr_mounts_create: " + CendaRig.lastError());
            this.state = new State(h);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
        this.cleanable = CLEANER.register(this, state);
    }

    private MemorySegment handle() {
        MemorySegment h = state.handle;
        if (h == null) throw new IllegalStateException("mount set closed");
        return h;
    }

    /**
     * Sets mount {@code name} (case-insensitive, one item per name) on host socket {@code socket}; null offset /
     * attach = identity. Replacing a mount keeps its index and drops its points. Returns the mount's index.
     */
    public int set(String name, int socket, Matrix4fc offset, Matrix4fc attach) {
        try (Arena arena = Arena.ofConfined()) {
            int rc = (int) CendaRig.MOUNTS_SET.invokeExact(handle(), arena.allocateFrom(name), socket,
                    mat(arena, offset), mat(arena, attach));
            if (rc < 0) throw new IllegalArgumentException("cr_mounts_set: " + CendaRig.lastError());
            mountCount = -1;
            return rc;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    /** Removes mount {@code name}; later mounts move down one index. False when there is none. */
    public boolean remove(String name) {
        try (Arena arena = Arena.ofConfined()) {
            int rc = (int) CendaRig.MOUNTS_REMOVE.invokeExact(handle(), arena.allocateFrom(name));
            mountCount = -1;
            return rc == CendaRig.OK;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    public void clear() {
        try {
            int rc = (int) CendaRig.MOUNTS_CLEAR.invokeExact(handle());
            if (rc != CendaRig.OK) throw new IllegalStateException("cr_mounts_clear: " + CendaRig.lastError());
            mountCount = -1;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    public int count() {
        try {
            return (int) CendaRig.MOUNTS_COUNT.invokeExact(handle());
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    /** Index of mount {@code name} (case-insensitive), or -1. */
    public int find(String name) {
        try (Arena arena = Arena.ofConfined()) {
            return (int) CendaRig.MOUNTS_FIND.invokeExact(handle(), arena.allocateFrom(name));
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    /** Adds (or replaces by name) a point of mount {@code mount}'s item, in item space; returns its index there. */
    public int setPoint(int mount, String name, Matrix4fc local) {
        try (Arena arena = Arena.ofConfined()) {
            int rc = (int) CendaRig.MOUNTS_SET_POINT.invokeExact(handle(), mount, arena.allocateFrom(name),
                    mat(arena, local));
            if (rc < 0) throw new IllegalArgumentException("cr_mounts_set_point: " + CendaRig.lastError());
            mountCount = -1;
            return rc;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    /** Index of point {@code name} of mount {@code mount}, or -1. */
    public int findPoint(int mount, String name) {
        try (Arena arena = Arena.ofConfined()) {
            return (int) CendaRig.MOUNTS_FIND_POINT.invokeExact(handle(), mount, arena.allocateFrom(name));
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    /**
     * Every mount's and point's frame on {@code palette} (the host posed into it; {@code sockets} = the host's set).
     * {@code pre} / {@code root} as for {@link SocketSet#frame}. Returns the number of mounts that resolved.
     */
    public int resolve(SocketSet sockets, JointPalette palette, Matrix4fc pre, Matrix4fc root) {
        if (mountCount < 0) layout();
        MemorySegment preSeg = MemorySegment.NULL;
        MemorySegment rootSeg = MemorySegment.NULL;
        if (pre != null) {
            JointPalette.write(inputs, 0, pre);
            preSeg = preSlice;
        }
        if (root != null) {
            JointPalette.write(inputs, 1, root);
            rootSeg = rootSlice;
        }
        MemorySegment skin = palette != null ? palette.nativeSkin() : MemorySegment.NULL;
        int joints = palette != null ? palette.count() : 0;
        int rc;
        try {
            rc = (int) CendaRig.MOUNTS_RESOLVE.invokeExact(handle(), sockets.handle(), skin, joints, preSeg, rootSeg,
                    frames, points, valid);
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
        if (rc < 0) throw new IllegalArgumentException("cr_mounts_resolve: " + CendaRig.lastError());
        return rc;
    }

    /** Whether mount {@code mount} resolved in the last {@link #resolve}. */
    public boolean valid(int mount) {
        return mount >= 0 && mount < mountCount && valid.get(ValueLayout.JAVA_BYTE, mount) != 0;
    }

    /** Mount {@code mount}'s item frame from the last {@link #resolve}, or null when it did not resolve. */
    public Matrix4f frame(int mount, Matrix4f dest) {
        return valid(mount) ? JointPalette.read(frames, mount, dest) : null;
    }

    /** Point {@code point} of mount {@code mount} from the last {@link #resolve}, or null. */
    public Matrix4f point(int mount, int point, Matrix4f dest) {
        if (!valid(mount) || point < 0 || pointBase[mount] + point >= (mount + 1 < mountCount ? pointBase[mount + 1]
                : pointCount)) {
            return null;
        }
        return JointPalette.read(points, pointBase[mount] + point, dest);
    }

    /** The position of point {@code point} of mount {@code mount} (its frame's translation), or null. */
    public Vector3f pointPosition(int mount, int point, Vector3f dest) {
        if (!valid(mount) || point < 0 || pointBase[mount] + point >= (mount + 1 < mountCount ? pointBase[mount + 1]
                : pointCount)) {
            return null;
        }
        long o = 64L * (pointBase[mount] + point);
        return dest.set(points.get(CendaRig.F32, o + 48), points.get(CendaRig.F32, o + 52),
                points.get(CendaRig.F32, o + 56));
    }

    private void layout() {
        try {
            int n = (int) CendaRig.MOUNTS_COUNT.invokeExact(handle());
            int total = (int) CendaRig.MOUNTS_TOTAL_POINTS.invokeExact(handle());
            int[] base = new int[n];
            for (int i = 0; i < n; i++) base[i] = (int) CendaRig.MOUNTS_POINT_BASE.invokeExact(handle(), i);
            Arena arena = Arena.ofAuto();
            frames = n > 0 ? arena.allocate(64L * n, 16) : MemorySegment.NULL;
            points = total > 0 ? arena.allocate(64L * total, 16) : MemorySegment.NULL;
            valid = n > 0 ? arena.allocate(n) : MemorySegment.NULL;
            pointBase = base;
            pointCount = total;
            mountCount = n;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    @Override
    public void close() {
        cleanable.clean();
    }

    private static MemorySegment mat(Arena arena, Matrix4fc m) {
        if (m == null) return MemorySegment.NULL;
        MemorySegment s = arena.allocate(64, 16);
        JointPalette.write(s, 0, m);
        return s;
    }
}
