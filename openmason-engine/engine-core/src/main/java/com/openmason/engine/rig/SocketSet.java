package com.openmason.engine.rig;

import com.openmason.engine.character.CharacterDefinition;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3fc;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.ref.Cleaner;

/**
 * Every socket of one host model ({@code cr_sockets}, cenda/socket.h, Stonebreak #459): named frames on rig joints (or
 * the model root) given in rest model space, the OMO attachment convention the {@code .omchar} sockets follow. Names
 * are case-insensitive and unique; a later add of a name replaces the earlier one when its source ranks at least as
 * high ({@link #HOST} > {@link #CHARACTER} > {@link #OMO}). Look names up once ({@link #find}); frames take indices.
 *
 * <p>Frame on a posed body: joint socket {@code pre · K(joint) · T·R·S}, root socket {@code root · T·R·S}; {@code pre}
 * = the base matrix for a model-space palette, identity (null) for one posed with its base; {@code root} = the base
 * (null = {@code pre}). Build once, then read from any thread ({@link #frame} uses per-thread scratch).
 */
public final class SocketSet implements AutoCloseable {

    /** Source ranks: an OMO attachment point, an {@code .omchar} socket, one the host added. */
    public static final int OMO = 0;
    public static final int CHARACTER = 1;
    public static final int HOST = 2;
    /** The joint of a socket on the model root. */
    public static final int ROOT = -1;

    /** What {@link #addCharacter} did. */
    public record Added(int added, int unresolved) {}

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
                    CendaRig.SOCKETS_DESTROY.invokeExact(h);
                } catch (Throwable ignored) {
                    // best effort
                }
            }
        }
    }

    /** Per-thread matrices for {@link #frame}: all three, then pre / root / out as slices (made once). */
    private record Scratch(MemorySegment all, MemorySegment pre, MemorySegment root, MemorySegment out) {
        static Scratch create() {
            MemorySegment all = Arena.ofAuto().allocate(3 * 64L, 16);
            return new Scratch(all, all.asSlice(0, 64), all.asSlice(64, 64), all.asSlice(128, 64));
        }
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::create);

    private final State state;
    private final Cleaner.Cleanable cleanable;
    /** Keeps the rig reachable while the native set points at it. */
    private final Rig rig;

    private SocketSet(MemorySegment handle, Rig rig) {
        this.state = new State(handle);
        this.cleanable = CLEANER.register(this, state);
        this.rig = rig;
    }

    /** A socket set over {@code rig} (null = root sockets only: an item's own points). */
    public static SocketSet create(Rig rig) {
        CendaRig.requireAvailable();
        try {
            MemorySegment rigHandle = rig != null ? rig.nativeHandle() : MemorySegment.NULL;
            MemorySegment h = (MemorySegment) CendaRig.SOCKETS_CREATE.invokeExact(rigHandle);
            if (h.equals(MemorySegment.NULL)) throw new IllegalStateException("cr_sockets_create: " + CendaRig.lastError());
            return new SocketSet(h, rig);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    MemorySegment handle() {
        MemorySegment h = state.handle;
        if (h == null) throw new IllegalStateException("socket set closed");
        return h;
    }

    /** For other Cenda bindings. */
    public MemorySegment nativeHandle() {
        return handle();
    }

    /** The rig the set was made over (null for a rig-less set). */
    public Rig rig() {
        return rig;
    }

    /**
     * Adds (or replaces, see the class comment) socket {@code name} on {@code joint} (a rig joint index or
     * {@link #ROOT}); null pos / rot = 0, null scale = 1. Returns its index.
     */
    public int add(String name, int joint, Vector3fc pos, Vector3fc rotDeg, Vector3fc scale, int source) {
        try (Arena arena = Arena.ofConfined()) {
            int rc = (int) CendaRig.SOCKETS_ADD.invokeExact(handle(), arena.allocateFrom(name), joint,
                    vec(arena, pos), vec(arena, rotDeg), vec(arena, scale), source);
            if (rc < 0) throw new IllegalArgumentException("cr_sockets_add: " + CendaRig.lastError());
            return rc;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    /**
     * An OMO attachment point: its joint by id, else by exact name; both null/blank = a root socket. Returns its
     * index, or -1 when the named joint is not in the rig (left out: such a socket never resolves).
     */
    public int addRef(String name, String jointId, String jointName, Vector3fc pos, Vector3fc rotDeg, Vector3fc scale,
                      int source) {
        try (Arena arena = Arena.ofConfined()) {
            int rc = (int) CendaRig.SOCKETS_ADD_REF.invokeExact(handle(), arena.allocateFrom(name),
                    str(arena, jointId), str(arena, jointName), vec(arena, pos), vec(arena, rotDeg), vec(arena, scale),
                    source);
            if (rc == -2) return -1;  // CR_ERR_RANGE: the joint is not in the rig
            if (rc < 0) throw new IllegalArgumentException("cr_sockets_add_ref: " + CendaRig.lastError());
            return rc;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    /** Adds every socket of {@code character} (source {@link #CHARACTER}), resolving joint ids on the set's rig. */
    public Added addCharacter(CharacterDefinition character) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment unresolved = arena.allocate(CendaRig.I32);
            int rc = (int) CendaRig.CHAR_SOCKETS_INTO.invokeExact(character.nativeHandle(), handle(), unresolved);
            if (rc < 0) throw new IllegalArgumentException("cc_char_sockets_into: " + CendaRig.lastError());
            return new Added(rc, unresolved.get(CendaRig.I32, 0));
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    public int count() {
        try {
            return (int) CendaRig.SOCKETS_COUNT.invokeExact(handle());
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    /** Index of {@code name} (case-insensitive), or -1. */
    public int find(String name) {
        if (name == null) return -1;
        try (Arena arena = Arena.ofConfined()) {
            return (int) CendaRig.SOCKETS_FIND.invokeExact(handle(), arena.allocateFrom(name));
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    /** One socket's name, joint, source and rest-model-space {@code T·R·S}. */
    public record Info(String name, int joint, int source, Matrix4f local) {}

    public Info info(int index) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment name = arena.allocate(CendaRig.ADDR);
            MemorySegment joint = arena.allocate(CendaRig.I32);
            MemorySegment source = arena.allocate(CendaRig.I32);
            MemorySegment local = arena.allocate(64, 16);
            int rc = (int) CendaRig.SOCKETS_GET.invokeExact(handle(), index, name, joint, source, local);
            if (rc != CendaRig.OK) throw new IndexOutOfBoundsException("cr_sockets_get: " + CendaRig.lastError());
            return new Info(name.get(CendaRig.ADDR, 0).reinterpret(4096).getString(0), joint.get(CendaRig.I32, 0),
                    source.get(CendaRig.I32, 0), JointPalette.read(local, 0, new Matrix4f()));
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    /**
     * Socket {@code index}'s frame on {@code palette} into {@code dest} (see the class comment for {@code pre} and
     * {@code root}); null when the socket's joint is outside the palette. Allocation-free.
     */
    public Matrix4f frame(int index, JointPalette palette, Matrix4fc pre, Matrix4fc root, Matrix4f dest) {
        Scratch s = SCRATCH.get();
        MemorySegment preSeg = MemorySegment.NULL;
        MemorySegment rootSeg = MemorySegment.NULL;
        if (pre != null) {
            JointPalette.write(s.all(), 0, pre);
            preSeg = s.pre();
        }
        if (root != null) {
            JointPalette.write(s.all(), 1, root);
            rootSeg = s.root();
        }
        MemorySegment out = s.out();
        MemorySegment skin = palette != null ? palette.nativeSkin() : MemorySegment.NULL;
        int joints = palette != null ? palette.count() : 0;
        int rc;
        try {
            rc = (int) CendaRig.SOCKETS_FRAME.invokeExact(handle(), index, skin, joints, preSeg, rootSeg, out);
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
        if (rc == -2) return null;
        if (rc != CendaRig.OK) throw new IllegalArgumentException("cr_sockets_frame: " + CendaRig.lastError());
        return JointPalette.read(out, 0, dest);
    }

    @Override
    public void close() {
        cleanable.clean();
    }

    static MemorySegment vec(Arena arena, Vector3fc v) {
        if (v == null) return MemorySegment.NULL;
        MemorySegment s = arena.allocate(12, 4);
        s.set(CendaRig.F32, 0, v.x());
        s.set(CendaRig.F32, 4, v.y());
        s.set(CendaRig.F32, 8, v.z());
        return s;
    }

    static MemorySegment str(Arena arena, String s) {
        return s == null ? MemorySegment.NULL : arena.allocateFrom(s);
    }
}
