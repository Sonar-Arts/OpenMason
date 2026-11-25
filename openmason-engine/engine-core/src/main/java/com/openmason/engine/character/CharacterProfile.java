package com.openmason.engine.character;

import com.openmason.engine.character.CharacterDefinition.Vec3;
import com.openmason.engine.rig.Rig;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.ref.Cleaner;
import java.lang.ref.Reference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A skeleton profile (OpenMason #154): the named roles a character's joints map onto (Unity Humanoid Avatar /
 * Godot SkeletonProfile / HumanIK skeleton definition). Built in: {@code humanoid}, {@code quadruped},
 * {@code bird}; custom ones are {@code .omprofile} JSON files or exported from a rig with {@link #fromRig}.
 * A thin binding over the native {@code cc_profile_*}; the format is documented in
 * {@code cenda/libomformat/include/omformat/omprofile.hpp}.
 *
 * <p>Profile frame: +Y up, the character faces -Z, its own left is -X. Immutable; freed by {@link #close()}
 * or, as a backstop, when unreachable.
 */
public final class CharacterProfile implements AutoCloseable {

    private static final Cleaner CLEANER = Cleaner.create();

    public enum Side { CENTER, LEFT, RIGHT }

    /**
     * One role. {@code parent} / {@code mirror} are null when absent; {@code direction} is the role's direction
     * in the reference pose (profile frame), empty for roles that keep their rest orientation.
     */
    public record Role(String name, String parent, String mirror, Side side, boolean required, Optional<Vec3> direction) {
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
                    CendaCharacter.PROFILE_DESTROY.invokeExact(h);
                } catch (Throwable ignored) {
                    // best effort at shutdown
                }
            }
        }
    }

    private final State state;
    private final Cleaner.Cleanable cleanable;

    private CharacterProfile(MemorySegment handle) {
        this.state = new State(handle);
        this.cleanable = CLEANER.register(this, state);
    }

    /** The ids of the profiles that ship with the engine. */
    public static List<String> builtinIds() {
        CendaCharacter.requireAvailable();
        try {
            int n = (int) CendaCharacter.PROFILE_BUILTIN_COUNT.invokeExact();
            List<String> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                out.add(CendaCharacter.string((MemorySegment) CendaCharacter.PROFILE_BUILTIN_ID.invokeExact(i)));
            }
            return List.copyOf(out);
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    /** A built-in profile ({@code humanoid}, {@code quadruped}, {@code bird}). */
    public static CharacterProfile builtin(String id) {
        CendaCharacter.requireAvailable();
        Objects.requireNonNull(id, "id");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment h = (MemorySegment) CendaCharacter.PROFILE_BUILTIN.invokeExact(arena.allocateFrom(id));
            if (h.equals(MemorySegment.NULL)) throw new IllegalArgumentException(CendaCharacter.lastError());
            return new CharacterProfile(h);
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    /** Parses an {@code .omprofile} (JSON). */
    public static CharacterProfile read(byte[] json) throws CharacterFormatException {
        CendaCharacter.requireAvailable();
        Objects.requireNonNull(json, "json");
        if (json.length == 0) throw new CharacterFormatException("empty .omprofile", false);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = arena.allocate(json.length);
            MemorySegment.copy(json, 0, data, ValueLayout.JAVA_BYTE, 0, json.length);
            MemorySegment result = arena.allocate(CendaCharacter.I32);
            MemorySegment h = (MemorySegment) CendaCharacter.PROFILE_READ.invokeExact(data, (long) json.length, result);
            if (h.equals(MemorySegment.NULL)) {
                throw new CharacterFormatException(CendaCharacter.lastError(),
                    result.get(CendaCharacter.I32, 0) == CendaCharacter.ERR_VERSION);
            }
            return new CharacterProfile(h);
        } catch (CharacterFormatException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    public static CharacterProfile read(Path file) throws IOException {
        return read(Files.readAllBytes(file));
    }

    /**
     * A profile with one required role per joint the rig would be characterized by (its bones, or its parts
     * when it has none): names, parents, left/right pairs and reference directions from the rig's rest pose,
     * in the facing of {@code source} (null = -z). For creatures no built-in profile fits.
     */
    public static CharacterProfile fromRig(Rig rig, CharacterDefinition source, String id) {
        CendaCharacter.requireAvailable();
        Objects.requireNonNull(rig, "rig");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment src = source == null ? MemorySegment.NULL : source.handle();
            MemorySegment name = id == null ? MemorySegment.NULL : arena.allocateFrom(id);
            MemorySegment h = (MemorySegment) CendaCharacter.PROFILE_FROM_RIG.invokeExact(rig.nativeHandle(), src, name);
            if (h.equals(MemorySegment.NULL)) throw new IllegalArgumentException(CendaCharacter.lastError());
            return new CharacterProfile(h);
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        } finally {
            Reference.reachabilityFence(rig);
            Reference.reachabilityFence(source);
        }
    }

    /** The profile as {@code .omprofile} JSON. */
    public String write() {
        try {
            MemorySegment b = (MemorySegment) CendaCharacter.PROFILE_WRITE.invokeExact(handle());
            if (b.equals(MemorySegment.NULL)) throw new IllegalStateException(CendaCharacter.lastError());
            try {
                long size = (long) CendaCharacter.BYTES_SIZE.invokeExact(b);
                MemorySegment data = (MemorySegment) CendaCharacter.BYTES_DATA.invokeExact(b);
                return new String(data.reinterpret(size).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
            } finally {
                CendaCharacter.BYTES_DESTROY.invokeExact(b);
            }
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    public void write(Path file) throws IOException {
        Files.writeString(file, write());
    }

    public String id() {
        return string(CendaCharacter.PROFILE_ID);
    }

    public String name() {
        return string(CendaCharacter.PROFILE_NAME);
    }

    public String description() {
        return string(CendaCharacter.PROFILE_DESCRIPTION);
    }

    /** The roles, parents before children. */
    public List<Role> roles() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment h = handle();
            int n = (int) CendaCharacter.PROFILE_ROLE_COUNT.invokeExact(h);
            List<Role> out = new ArrayList<>(n);
            MemorySegment name = arena.allocate(CendaCharacter.ADDR), parent = arena.allocate(CendaCharacter.ADDR);
            MemorySegment mirror = arena.allocate(CendaCharacter.ADDR);
            MemorySegment side = arena.allocate(CendaCharacter.I32), required = arena.allocate(CendaCharacter.I32);
            MemorySegment dir = arena.allocate(CendaCharacter.F32, 3), has = arena.allocate(CendaCharacter.I32);
            for (int i = 0; i < n; i++) {
                int rc = (int) CendaCharacter.PROFILE_ROLE_GET.invokeExact(h, i, name, parent, mirror, side, required, dir, has);
                if (rc != CendaCharacter.OK) throw new IllegalStateException("role: " + CendaCharacter.lastError());
                Optional<Vec3> direction = has.get(CendaCharacter.I32, 0) == 0 ? Optional.empty()
                    : Optional.of(new Vec3(dir.getAtIndex(CendaCharacter.F32, 0), dir.getAtIndex(CendaCharacter.F32, 1),
                        dir.getAtIndex(CendaCharacter.F32, 2)));
                out.add(new Role(text(name), blankToNull(text(parent)), blankToNull(text(mirror)),
                    Side.values()[Math.clamp(side.get(CendaCharacter.I32, 0), 0, 2)], required.get(CendaCharacter.I32, 0) != 0,
                    direction));
            }
            return List.copyOf(out);
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    @Override
    public void close() {
        cleanable.clean();
    }

    MemorySegment handle() {
        MemorySegment h = state.handle;
        if (h == null) throw new IllegalStateException("CharacterProfile is closed");
        return h;
    }

    private String string(int field) {
        try {
            return CendaCharacter.string((MemorySegment) CendaCharacter.PROFILE_GET_STRING.invokeExact(handle(), field));
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    private static String text(MemorySegment slot) {
        String s = CendaCharacter.string(slot.get(CendaCharacter.ADDR, 0));
        return s == null ? "" : s;
    }

    private static String blankToNull(String s) {
        return s.isEmpty() ? null : s;
    }
}
