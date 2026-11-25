package com.openmason.engine.character;

import com.openmason.engine.rig.BoneMask;
import com.openmason.engine.rig.Rig;
import org.joml.Matrix4f;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.ref.Cleaner;
import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A character's appearance resolver ({@code cc_appearance}, OpenMason #162): the slots its .omchar declares
 * (tints and dye masks, part visibility groups, body variants, equipment), the items the host registered, and -
 * after {@link #resolve} of a {@link Look} - what to draw: the body variant, visible joints, dyed materials and
 * equipped items. Keep the definition and rig open while it is in use. Not thread-safe.
 */
public final class Appearance implements AutoCloseable {

    private static final Cleaner CLEANER = Cleaner.create();

    /** Slot kinds (= {@code CC_SLOT_*}). */
    public enum SlotKind { TINT, GROUP, BODY, EQUIPMENT }

    /** Item kinds (= {@code CC_ITEM_*}). */
    public static final int RIGID = 0;
    public static final int SKINNED = 1;

    /** A declared slot and the {@link Look} value type it takes. */
    public record Slot(String name, SlotKind kind, int type) {}

    /**
     * An equipment item. Rigid items sit on {@code socket} (null = the slot's) with an offset (Euler XYZ degrees;
     * a zero scale component counts as 1); skinned items are {@link Garment}s the host draws with the body palette.
     */
    public record Item(String id, String slot, int kind, String socket, float[] pos, float[] rotDeg, float[] scale,
                       List<String> hides) {
        public static Item skinned(String id, String slot, String... hides) {
            return new Item(id, slot, SKINNED, null, null, null, null, List.of(hides));
        }

        public static Item rigid(String id, String slot, String socket, float[] pos, float[] rotDeg, String... hides) {
            return new Item(id, slot, RIGID, socket, pos, rotDeg, null, List.of(hides));
        }
    }

    /**
     * A dyed material: {@code mask} = the .omchar archive entry of its RGBA dye mask (null = dyed whole by tint 0);
     * {@code tints} = 4 x rgb, one per mask channel (white = no tint).
     */
    public record DyedMaterial(int materialId, String mask, float[] tints) {}

    /**
     * An equipped item: rigid ones mount at {@code joint} with {@code socket} in that joint's REST model space (world
     * = the joint's skin matrix x socket); skinned ones have joint -1.
     */
    public record Equipped(String slot, int item, int kind, int joint, Matrix4f socket) {}

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
                    CendaAppearance.APP_DESTROY.invokeExact(h);
                } catch (Throwable ignored) {
                    // best effort
                }
            }
        }
    }

    private final State state;
    private final Cleaner.Cleanable cleanable;
    private final CharacterDefinition definition;
    private final Rig rig;
    private final int unresolved;
    private final long[] bits;
    private final MemorySegment bitsSeg;

    private Appearance(MemorySegment handle, CharacterDefinition definition, Rig rig, int unresolved) {
        this.state = new State(handle);
        this.cleanable = CLEANER.register(this, state);
        this.definition = definition;
        this.rig = rig;
        this.unresolved = unresolved;
        int words = (rig.jointCount() + 63) / 64;
        this.bits = new long[words];
        this.bitsSeg = Arena.ofAuto().allocate(8L * words, 8);
    }

    /**
     * Parses the definition's appearance section against {@code rig}.
     *
     * @throws IllegalArgumentException when the section is malformed
     */
    public static Appearance create(CharacterDefinition definition, Rig rig) {
        CendaCharacter.requireAvailable();
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(rig, "rig");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment rc = arena.allocate(4, 4), unresolved = arena.allocate(4, 4);
            MemorySegment h = (MemorySegment) CendaAppearance.APP_CREATE.invokeExact(definition.handle(),
                rig.nativeHandle(), rc, unresolved);
            if (h.equals(MemorySegment.NULL)) throw new IllegalArgumentException("cc_appearance_create: " + CendaCharacter.lastError());
            return new Appearance(h, definition, rig, unresolved.get(ValueLayout.JAVA_INT, 0));
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    private MemorySegment handle() {
        MemorySegment h = state.handle;
        if (h == null) throw new IllegalStateException("appearance closed");
        return h;
    }

    /** References the rig or character lacked at create (group parts, sockets, archive entries). */
    public int unresolved() {
        return unresolved;
    }

    public List<Slot> slots() {
        try (Arena arena = Arena.ofConfined()) {
            int n = (int) CendaAppearance.APP_SLOT_COUNT.invokeExact(handle());
            List<Slot> out = new ArrayList<>(n);
            MemorySegment name = arena.allocate(ValueLayout.ADDRESS), kind = arena.allocate(4, 4), type = arena.allocate(4, 4);
            for (int i = 0; i < n; i++) {
                CendaAppearance.check((int) CendaAppearance.APP_SLOT.invokeExact(handle(), i, name, kind, type), "slot");
                out.add(new Slot(CendaCharacter.string(name.get(ValueLayout.ADDRESS, 0)),
                    SlotKind.values()[kind.get(ValueLayout.JAVA_INT, 0)], type.get(ValueLayout.JAVA_INT, 0)));
            }
            return out;
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    /** Every declared slot at its default value. */
    public Look defaults() {
        Look look = Look.create();
        try {
            CendaAppearance.check((int) CendaAppearance.APP_DEFAULTS.invokeExact(handle(), look.handle()), "defaults");
            return look;
        } catch (Throwable t) {
            look.close();
            throw CendaCharacter.rethrow(t);
        }
    }

    /** Registers an item (an id registered again replaces the first); returns its index. */
    public int addItem(Item item) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment d = arena.allocate(CendaAppearance.ITEM_DESC_BYTES, 8);
            d.set(ValueLayout.ADDRESS, 0, arena.allocateFrom(item.id()));
            d.set(ValueLayout.ADDRESS, 8, arena.allocateFrom(item.slot()));
            d.set(ValueLayout.JAVA_INT, 16, item.kind());
            d.set(ValueLayout.ADDRESS, 24, item.socket() == null ? MemorySegment.NULL : arena.allocateFrom(item.socket()));
            putVec(d, 32, item.pos(), 0f);
            putVec(d, 44, item.rotDeg(), 0f);
            putVec(d, 56, item.scale(), 1f);
            List<String> hides = item.hides() == null ? List.of() : item.hides();
            MemorySegment h = arena.allocate(ValueLayout.ADDRESS, Math.max(1, hides.size()));
            for (int i = 0; i < hides.size(); i++) h.setAtIndex(ValueLayout.ADDRESS, i, arena.allocateFrom(hides.get(i)));
            d.set(ValueLayout.ADDRESS, 72, hides.isEmpty() ? MemorySegment.NULL : h);
            d.set(ValueLayout.JAVA_INT, 80, hides.size());
            int rc = (int) CendaAppearance.APP_ADD_ITEM.invokeExact(handle(), d);
            CendaAppearance.check(rc, "addItem");
            return rc;
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    private static void putVec(MemorySegment d, long o, float[] v, float fill) {
        for (int i = 0; i < 3; i++) d.set(ValueLayout.JAVA_FLOAT, o + 4L * i, v == null ? fill : v[i]);
    }

    /** Resolves a record; returns how many slots fell back to their defaults (wrong type, unknown value). */
    public int resolve(Look look) {
        try {
            int rc = (int) CendaAppearance.APP_RESOLVE.invokeExact(handle(), look.handle());
            CendaAppearance.check(rc, "resolve");
            return rc;
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        } finally {
            Reference.reachabilityFence(look);
        }
    }

    /** The resolved body: {name, model archive entry} ("" = the skeleton model). */
    public String[] body() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment name = arena.allocate(ValueLayout.ADDRESS), model = arena.allocate(ValueLayout.ADDRESS);
            CendaAppearance.check((int) CendaAppearance.APP_BODY.invokeExact(handle(), name, model), "body");
            return new String[]{CendaCharacter.string(name.get(ValueLayout.ADDRESS, 0)),
                CendaCharacter.string(model.get(ValueLayout.ADDRESS, 0))};
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    /**
     * The visible joints of the resolved record (bit j of word j/64), narrowed by {@code view} when non-null (a
     * {@link CharacterDefinition#viewMask} mask). The returned array is reused by the next call. Allocation-free.
     */
    public long[] visible(BoneMask view) {
        MemorySegment v = view == null ? MemorySegment.NULL : view.nativeHandle();
        try {
            CendaAppearance.check((int) CendaAppearance.APP_VISIBLE.invokeExact(handle(), v, bitsSeg, bits.length), "visible");
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        } finally {
            Reference.reachabilityFence(view);
        }
        for (int i = 0; i < bits.length; i++) bits[i] = bitsSeg.getAtIndex(ValueLayout.JAVA_LONG, i);
        return bits;
    }

    public List<DyedMaterial> materials() {
        try (Arena arena = Arena.ofConfined()) {
            int n = (int) CendaAppearance.APP_MATERIAL_COUNT.invokeExact(handle());
            List<DyedMaterial> out = new ArrayList<>(n);
            MemorySegment id = arena.allocate(4, 4), mask = arena.allocate(ValueLayout.ADDRESS), tints = arena.allocate(48, 4);
            for (int i = 0; i < n; i++) {
                CendaAppearance.check((int) CendaAppearance.APP_MATERIAL.invokeExact(handle(), i, id, mask, tints), "material");
                String m = CendaCharacter.string(mask.get(ValueLayout.ADDRESS, 0));
                out.add(new DyedMaterial(id.get(ValueLayout.JAVA_INT, 0), m == null || m.isEmpty() ? null : m,
                    tints.toArray(ValueLayout.JAVA_FLOAT)));
            }
            return out;
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    public List<Equipped> equipped() {
        try (Arena arena = Arena.ofConfined()) {
            int n = (int) CendaAppearance.APP_EQUIPPED_COUNT.invokeExact(handle());
            List<Equipped> out = new ArrayList<>(n);
            MemorySegment slot = arena.allocate(ValueLayout.ADDRESS), item = arena.allocate(4, 4), kind = arena.allocate(4, 4),
                joint = arena.allocate(4, 4), m = arena.allocate(64, 4);
            for (int i = 0; i < n; i++) {
                CendaAppearance.check((int) CendaAppearance.APP_EQUIPPED.invokeExact(handle(), i, slot, item, kind, joint, m),
                    "equipped");
                out.add(new Equipped(CendaCharacter.string(slot.get(ValueLayout.ADDRESS, 0)), item.get(ValueLayout.JAVA_INT, 0),
                    kind.get(ValueLayout.JAVA_INT, 0), joint.get(ValueLayout.JAVA_INT, 0),
                    new Matrix4f().set(m.toArray(ValueLayout.JAVA_FLOAT))));
            }
            return out;
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    public CharacterDefinition definition() {
        return definition;
    }

    public Rig rig() {
        return rig;
    }

    @Override
    public void close() {
        cleanable.clean();
    }
}
