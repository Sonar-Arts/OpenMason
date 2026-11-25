package com.openmason.engine.character;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.ref.Cleaner;
import java.util.ArrayList;
import java.util.List;

/**
 * A character appearance record ({@code cc_look}, OpenMason #162; Roblox's HumanoidDescription as data): slot
 * name -> value, saved with a player as JSON and replicated as compact binary. Unknown slots and value types
 * survive every round trip; {@link #diff} / {@link #apply} replicate only what changed. Resolve one against a
 * character with {@link Appearance#resolve}. Not thread-safe.
 */
public final class Look implements AutoCloseable {

    private static final Cleaner CLEANER = Cleaner.create();

    /** Value types (= {@code CC_LOOK_*}); other values are types this reader does not know. */
    public static final int REMOVE = 0;
    public static final int COLOR = 1;
    public static final int BOOL = 2;
    public static final int STRING = 3;
    public static final int NUMBER = 4;
    public static final int JSON = 5;

    /**
     * One slot. {@code color} (0..1, 8 bits per channel) for {@link #COLOR}, {@code flag} for {@link #BOOL},
     * {@code number} for {@link #NUMBER}, {@code text} for {@link #STRING} / {@link #JSON} (an unknown type's
     * payload as hex).
     */
    public record Slot(String name, int type, float[] color, boolean flag, float number, String text) {}

    /** The look could not be read: corrupt, or ({@link #newerVersion()}) a newer major version. */
    public static final class LookFormatException extends IllegalArgumentException {
        private final boolean newer;

        LookFormatException(String message, boolean newer) {
            super(message);
            this.newer = newer;
        }

        public boolean newerVersion() {
            return newer;
        }
    }

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
                    CendaAppearance.LOOK_DESTROY.invokeExact(h);
                } catch (Throwable ignored) {
                    // best effort
                }
            }
        }
    }

    private final State state;
    private final Cleaner.Cleanable cleanable;

    private Look(MemorySegment handle) {
        if (handle.equals(MemorySegment.NULL)) throw new IllegalStateException("cc_look: " + CendaCharacter.lastError());
        this.state = new State(handle);
        this.cleanable = CLEANER.register(this, state);
    }

    /** An empty record. */
    public static Look create() {
        CendaCharacter.requireAvailable();
        try {
            return new Look((MemorySegment) CendaAppearance.LOOK_CREATE.invokeExact());
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    /** Reads either encoding (JSON or binary). */
    public static Look read(byte[] bytes) {
        CendaCharacter.requireAvailable();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = arena.allocateFrom(ValueLayout.JAVA_BYTE, bytes);
            MemorySegment rc = arena.allocate(4, 4);
            MemorySegment h = (MemorySegment) CendaAppearance.LOOK_READ.invokeExact(data, (long) bytes.length, rc);
            if (h.equals(MemorySegment.NULL)) {
                int code = rc.get(ValueLayout.JAVA_INT, 0);
                throw new LookFormatException(CendaCharacter.lastError(), code == -4);
            }
            return new Look(h);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    MemorySegment handle() {
        MemorySegment h = state.handle;
        if (h == null) throw new IllegalStateException("look closed");
        return h;
    }

    public Look copy() {
        try {
            return new Look((MemorySegment) CendaAppearance.LOOK_CLONE.invokeExact(handle()));
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    /** The JSON encoding (saves, tools). */
    public byte[] writeJson() {
        try {
            byte[] b = CendaAppearance.take((MemorySegment) CendaAppearance.LOOK_WRITE_JSON.invokeExact(handle()));
            if (b == null) throw new IllegalStateException("cc_look_write_json: " + CendaCharacter.lastError());
            return b;
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    /** The binary encoding (network). */
    public byte[] writeBinary() {
        try {
            byte[] b = CendaAppearance.take((MemorySegment) CendaAppearance.LOOK_WRITE_BINARY.invokeExact(handle()));
            if (b == null) throw new IllegalStateException("cc_look_write_binary: " + CendaCharacter.lastError());
            return b;
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    /** The character's object id the record is for ("" = any). */
    public String character() {
        try {
            return CendaCharacter.string((MemorySegment) CendaAppearance.LOOK_CHARACTER.invokeExact(handle()));
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    public Look setCharacter(String objectId) {
        try (Arena arena = Arena.ofConfined()) {
            CendaAppearance.check((int) CendaAppearance.LOOK_SET_CHARACTER.invokeExact(handle(),
                arena.allocateFrom(objectId == null ? "" : objectId)), "setCharacter");
            return this;
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    public int count() {
        try {
            return (int) CendaAppearance.LOOK_COUNT.invokeExact(handle());
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    /** The slot named {@code name}, or null. */
    public Slot get(String name) {
        try (Arena arena = Arena.ofConfined()) {
            int i = (int) CendaAppearance.LOOK_FIND.invokeExact(handle(), arena.allocateFrom(name));
            return i < 0 ? null : slot(i);
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    /** Every slot in record order. */
    public List<Slot> slots() {
        int n = count();
        List<Slot> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(slot(i));
        return out;
    }

    private Slot slot(int index) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment name = arena.allocate(ValueLayout.ADDRESS), type = arena.allocate(4, 4),
                color = arena.allocate(12, 4), flag = arena.allocate(4, 4), number = arena.allocate(4, 4),
                text = arena.allocate(ValueLayout.ADDRESS);
            CendaAppearance.check((int) CendaAppearance.LOOK_GET.invokeExact(handle(), index, name, type, color, flag,
                number, text), "slot");
            return new Slot(CendaCharacter.string(name.get(ValueLayout.ADDRESS, 0)), type.get(ValueLayout.JAVA_INT, 0),
                color.toArray(ValueLayout.JAVA_FLOAT), flag.get(ValueLayout.JAVA_INT, 0) != 0,
                number.get(ValueLayout.JAVA_FLOAT, 0), CendaCharacter.string(text.get(ValueLayout.ADDRESS, 0)));
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    /** A colour (0..1 per channel; stored with 8 bits each). */
    public Look setColor(String name, float r, float g, float b) {
        try (Arena arena = Arena.ofConfined()) {
            CendaAppearance.check((int) CendaAppearance.LOOK_SET_COLOR.invokeExact(handle(), arena.allocateFrom(name),
                arena.allocateFrom(ValueLayout.JAVA_FLOAT, r, g, b)), "setColor");
            return this;
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    public Look setBool(String name, boolean value) {
        try (Arena arena = Arena.ofConfined()) {
            CendaAppearance.check((int) CendaAppearance.LOOK_SET_BOOL.invokeExact(handle(), arena.allocateFrom(name),
                value ? 1 : 0), "setBool");
            return this;
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    /** A string (a body name, an item id; "" = empty). "#rrggbb" is refused: that is a colour. */
    public Look setString(String name, String value) {
        try (Arena arena = Arena.ofConfined()) {
            CendaAppearance.check((int) CendaAppearance.LOOK_SET_STRING.invokeExact(handle(), arena.allocateFrom(name),
                arena.allocateFrom(value)), "setString");
            return this;
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    public Look setNumber(String name, float value) {
        try (Arena arena = Arena.ofConfined()) {
            CendaAppearance.check((int) CendaAppearance.LOOK_SET_NUMBER.invokeExact(handle(), arena.allocateFrom(name),
                value), "setNumber");
            return this;
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    /** A JSON object or array, kept verbatim (minified). */
    public Look setJson(String name, String json) {
        try (Arena arena = Arena.ofConfined()) {
            CendaAppearance.check((int) CendaAppearance.LOOK_SET_JSON.invokeExact(handle(), arena.allocateFrom(name),
                arena.allocateFrom(json)), "setJson");
            return this;
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    public Look remove(String name) {
        try (Arena arena = Arena.ofConfined()) {
            CendaAppearance.check((int) CendaAppearance.LOOK_REMOVE.invokeExact(handle(), arena.allocateFrom(name)),
                "remove");
            return this;
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    /** Same character and slots with the same values (order ignored). */
    public boolean sameAs(Look other) {
        try {
            return (int) CendaAppearance.LOOK_EQUAL.invokeExact(handle(), other.handle()) == 1;
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    /** A patch turning this record into {@code to} (changed slots, removals, the character if it changed). */
    public Look diff(Look to) {
        try {
            return new Look((MemorySegment) CendaAppearance.LOOK_DIFF.invokeExact(handle(), to.handle()));
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    /** Applies a patch (or overwrites with any record's slots). */
    public Look apply(Look patch) {
        try {
            CendaAppearance.check((int) CendaAppearance.LOOK_APPLY.invokeExact(handle(), patch.handle()), "apply");
            return this;
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    @Override
    public void close() {
        cleanable.clean();
    }
}
