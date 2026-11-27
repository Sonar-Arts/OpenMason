package com.openmason.engine.character;

import com.openmason.engine.rig.PackedSkin;
import com.openmason.engine.rig.Rig;
import com.openmason.engine.rig.Skin;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.ref.Cleaner;
import java.lang.ref.Reference;
import java.util.List;
import java.util.Objects;

/**
 * Skinned equipment fitted to one character ({@code cc_garment}, OpenMason #162): boots, gloves, tunics that bend
 * with the body, Unreal leader pose / Godot shared skeleton style. The garment's skin joints are remapped onto the
 * body's once, so it draws with the BODY'S joint palette (upload {@link #positions()} + {@link #pack()} and draw it
 * with the palette the body uses; no per-frame cost). A garment authored on another character of the same profile
 * is first fitted onto this one's rest pose through both reference poses.
 */
public final class Garment implements AutoCloseable {

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
                    CendaAppearance.GARMENT_DESTROY.invokeExact(h);
                } catch (Throwable ignored) {
                    // best effort
                }
            }
        }
    }

    private final State state;
    private final Cleaner.Cleanable cleanable;
    private final int vertexCount;
    private final int unresolved;

    private Garment(MemorySegment handle, int vertexCount, int unresolved) {
        this.state = new State(handle);
        this.cleanable = CLEANER.register(this, state);
        this.vertexCount = vertexCount;
        this.unresolved = unresolved;
    }

    /**
     * Fits a garment to {@code target}.
     *
     * @param source     the character it was authored on, null = the target itself (leader pose only)
     * @param sourceRig  that character's rig (null with {@code source} null)
     * @param jointRefs  the garment skin's joints: an id or name in the source rig, or {@code role:<r>}
     * @param positions  rest positions in the source's model space (x, y, z per vertex)
     * @param skin       weights over {@code jointRefs} indices (an unweighted vertex follows ref 0)
     */
    public static Garment fit(CharacterDefinition target, Rig targetRig, CharacterDefinition source, Rig sourceRig,
                              List<String> jointRefs, float[] positions, Skin skin) {
        CendaCharacter.requireAvailable();
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(targetRig, "targetRig");
        if ((source == null) != (sourceRig == null)) throw new IllegalArgumentException("source and sourceRig go together");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment refs = arena.allocate(ValueLayout.ADDRESS, jointRefs.size());
            for (int i = 0; i < jointRefs.size(); i++) refs.setAtIndex(ValueLayout.ADDRESS, i, arena.allocateFrom(jointRefs.get(i)));
            MemorySegment pos = arena.allocateFrom(ValueLayout.JAVA_FLOAT, positions);
            MemorySegment d = arena.allocate(CendaAppearance.GARMENT_DESC_BYTES, 8);
            d.set(ValueLayout.ADDRESS, 0, source == null ? MemorySegment.NULL : source.handle());
            d.set(ValueLayout.ADDRESS, 8, sourceRig == null ? MemorySegment.NULL : sourceRig.nativeHandle());
            d.set(ValueLayout.ADDRESS, 16, refs);
            d.set(ValueLayout.JAVA_INT, 24, jointRefs.size());
            d.set(ValueLayout.ADDRESS, 32, pos);
            d.set(ValueLayout.JAVA_INT, 40, positions.length / 3);
            d.set(ValueLayout.ADDRESS, 48, skin.nativeHandle());
            MemorySegment unresolved = arena.allocate(4, 4);
            MemorySegment h = (MemorySegment) CendaAppearance.GARMENT_CREATE.invokeExact(target.handle(),
                targetRig.nativeHandle(), d, unresolved);
            if (h.equals(MemorySegment.NULL)) throw new IllegalArgumentException("cc_garment_create: " + CendaCharacter.lastError());
            return new Garment(h, positions.length / 3, unresolved.get(ValueLayout.JAVA_INT, 0));
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        } finally {
            Reference.reachabilityFence(target);
            Reference.reachabilityFence(targetRig);
            Reference.reachabilityFence(source);
            Reference.reachabilityFence(sourceRig);
            Reference.reachabilityFence(skin);
        }
    }

    private MemorySegment handle() {
        MemorySegment h = state.handle;
        if (h == null) throw new IllegalStateException("garment closed");
        return h;
    }

    /** Joint refs that mapped to no target joint (their weight went to the nearest mapped ancestor). */
    public int unresolved() {
        return unresolved;
    }

    public int vertexCount() {
        return vertexCount;
    }

    /** The fitted rest positions in the target's model space. */
    public float[] positions() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(12L * vertexCount, 4);
            CendaAppearance.check((int) CendaAppearance.GARMENT_POSITIONS.invokeExact(handle(), out), "positions");
            return out.toArray(ValueLayout.JAVA_FLOAT);
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    /** GPU skin form over the TARGET's joints (rig ABI 3 packing): draw with the body's palette. */
    public PackedSkin pack() {
        try (Arena arena = Arena.ofConfined()) {
            int records = (int) CendaAppearance.GARMENT_PACK_SIZE.invokeExact(handle());
            CendaAppearance.check(records, "pack_size");
            MemorySegment ranges = arena.allocate(ValueLayout.JAVA_INT, 2L * Math.max(1, vertexCount));
            MemorySegment influences = arena.allocate(ValueLayout.JAVA_SHORT, 2L * Math.max(1, records));
            CendaAppearance.check((int) CendaAppearance.GARMENT_PACK.invokeExact(handle(), records, ranges, influences),
                "pack");
            return new PackedSkin(ranges.asSlice(0, 8L * vertexCount).toArray(ValueLayout.JAVA_INT),
                influences.asSlice(0, 4L * records).toArray(ValueLayout.JAVA_SHORT));
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    /** The target joint a ref resolved to, -1 = unresolved. */
    public int joint(int ref) {
        try {
            return (int) CendaAppearance.GARMENT_JOINT.invokeExact(handle(), ref);
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    @Override
    public void close() {
        cleanable.clean();
    }
}
