package com.openmason.engine.character;

import com.openmason.engine.rig.Rig;
import com.openmason.engine.rig.RigJoint;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.ref.Cleaner;
import java.lang.ref.Reference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * An {@code .omchar} character definition (OpenMason #153): which joint of a rigged model plays which
 * profile role, the reference (retarget) pose, joint limits, IK goals, retarget chains, standard sockets
 * and body measurements. A thin binding over the native {@code cc_*} module: the format, the editing
 * model, the checks and the measurements all live in Cenda.
 *
 * <p>Every reference into the skeleton is a joint id (the part or bone UUID the rig uses). Rotations are
 * Euler XYZ degrees, the rig's convention. Setters keyed by a name or joint replace an existing entry in
 * place or append; the {@code remove*} methods delete.
 *
 * <p>Not thread-safe: one thread at a time. Freed by {@link #close()} or, as a backstop, when unreachable.
 */
public final class CharacterDefinition implements AutoCloseable {

    private static final Cleaner CLEANER = Cleaner.create();

    /** A 3-vector (blocks or degrees). */
    public record Vec3(float x, float y, float z) {
        public static final Vec3 ZERO = new Vec3(0f, 0f, 0f);
        public static final Vec3 ONE = new Vec3(1f, 1f, 1f);
    }

    /** One joint of the skeleton the definition was made for; {@code parentId} null for a root. */
    public record SkeletonJoint(String id, String name, String parentId, RigJoint.Kind kind) {
    }

    /** A joint's absolute local rotation in the reference pose. */
    public record JointRotation(String jointId, Vec3 rotDeg) {
    }

    /** Rotation limits of a joint, relative to the reference pose. */
    public record JointLimit(String jointId, Vec3 minDeg, Vec3 maxDeg) {
    }

    /** A named IK goal and the joint it moves (a hand, a foot). */
    public record IkGoal(String name, String jointId) {
    }

    /** A retarget chain from {@code startJointId} down to {@code endJointId}; {@code goal} null for none. */
    public record Chain(String name, String startJointId, String endJointId, String goal) {
    }

    /**
     * A socket: a frame in rest model space moved by its joint's skin matrix (the OMO attachment-point
     * convention). Standard names: {@code grip_r}, {@code grip_l}, {@code head}, {@code eyes}, {@code back}.
     */
    public record Socket(String name, String jointId, Vec3 pos, Vec3 rotDeg, Vec3 scale) {
    }

    /** Body measurements (blocks, feet at 0). */
    public enum Metric { HEIGHT, EYE_HEIGHT, WIDTH, HIP_HEIGHT }

    /** Measurements; {@code overridden} = values the author typed, kept by {@link #computeMetrics}. */
    public record Metrics(float height, float eyeHeight, float width, float hipHeight, Set<Metric> overridden) {
        public Metrics {
            overridden = overridden == null || overridden.isEmpty() ? Set.of() : Set.copyOf(overridden);
        }
    }

    public enum Severity { INFO, WARNING, ERROR }

    /** Check codes (values = {@code CC_CHECK_*}). */
    public enum IssueCode {
        NO_SKELETON(1), HASH_MISMATCH(2), UNKNOWN_JOINT(3), JOINT_MISSING(4), JOINT_ADDED(5),
        JOINT_REPARENTED(6), JOINT_KIND_CHANGED(7), JOINT_RENAMED(8), CHAIN_ORDER(9), CHAIN_GOAL(10),
        LIMIT_INVERTED(11), METRIC_NEGATIVE(12),
        // Profile checks (validate) and auto-map notes.
        REQUIRED_MISSING(13), DUPLICATE_JOINT(14), HIERARCHY_ORDER(15), MIRROR_ASYMMETRIC(16), SIDE_MISMATCH(17),
        UNKNOWN_ROLE(18), PROFILE_MISMATCH(19), SIDE_FROM_GEOMETRY(20), PROMOTED(21), FACING(22), OTHER(0);

        private final int code;

        IssueCode(int code) {
            this.code = code;
        }

        static IssueCode of(int code) {
            for (IssueCode c : values()) {
                if (c.code == code) return c;
            }
            return OTHER;
        }
    }

    /** One finding of {@link #check(Rig)}; {@code subject} is the joint id, chain, socket or metric concerned. */
    public record Issue(Severity severity, IssueCode code, String subject, String message) {
    }

    /** One role {@link #autoMap} assigned; {@code confidence} in (0, 1]. */
    public record RoleMatch(String role, String jointId, float confidence) {
    }

    /** What {@link #autoMap} did: the roles it assigned (profile order) and its notes. */
    public record AutoMapResult(List<RoleMatch> matches, List<Issue> notes) {
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
                    CendaCharacter.DESTROY.invokeExact(h);
                } catch (Throwable ignored) {
                    // best effort at shutdown
                }
            }
        }
    }

    private final State state;
    private final Cleaner.Cleanable cleanable;

    private CharacterDefinition(MemorySegment handle) {
        this.state = new State(handle);
        this.cleanable = CLEANER.register(this, state);
    }

    // --- Lifetime and I/O ------------------------------------------------------------------------

    /** An empty definition. */
    public static CharacterDefinition create() {
        CendaCharacter.requireAvailable();
        try {
            MemorySegment h = (MemorySegment) CendaCharacter.CREATE.invokeExact();
            if (h.equals(MemorySegment.NULL)) throw new IllegalStateException("cc_char_create: " + CendaCharacter.lastError());
            return new CharacterDefinition(h);
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    /** Parses an {@code .omchar} archive. */
    public static CharacterDefinition read(byte[] bytes) throws CharacterFormatException {
        CendaCharacter.requireAvailable();
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length == 0) throw new CharacterFormatException("empty .omchar", false);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = arena.allocate(bytes.length);
            MemorySegment.copy(bytes, 0, data, ValueLayout.JAVA_BYTE, 0, bytes.length);
            MemorySegment result = arena.allocate(CendaCharacter.I32);
            MemorySegment h = (MemorySegment) CendaCharacter.READ.invokeExact(data, (long) bytes.length, result);
            if (h.equals(MemorySegment.NULL)) {
                boolean newer = result.get(CendaCharacter.I32, 0) == CendaCharacter.ERR_VERSION;
                throw new CharacterFormatException(CendaCharacter.lastError(), newer);
            }
            return new CharacterDefinition(h);
        } catch (CharacterFormatException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    /** Reads an {@code .omchar} file. */
    public static CharacterDefinition read(Path file) throws IOException {
        return read(Files.readAllBytes(file));
    }

    /** Serializes the definition (deterministic: identical definitions give identical bytes). */
    public byte[] write() {
        try {
            MemorySegment b = (MemorySegment) CendaCharacter.WRITE.invokeExact(handle());
            if (b.equals(MemorySegment.NULL)) throw new IllegalStateException("cc_char_write: " + CendaCharacter.lastError());
            try {
                long size = (long) CendaCharacter.BYTES_SIZE.invokeExact(b);
                MemorySegment data = (MemorySegment) CendaCharacter.BYTES_DATA.invokeExact(b);
                return data.reinterpret(size).toArray(ValueLayout.JAVA_BYTE);
            } finally {
                CendaCharacter.BYTES_DESTROY.invokeExact(b);
            }
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /** Writes the definition to a file. */
    public void write(Path file) throws IOException {
        Files.write(file, write());
    }

    @Override
    public void close() {
        cleanable.clean();
    }

    /**
     * The native {@code cc_char*}, for other Cenda bindings that take a character (for example
     * {@code com.openmason.engine.anim}). Valid while this definition is open; keep it reachable for the call.
     */
    public MemorySegment nativeHandle() {
        return handle();
    }

    MemorySegment handle() {
        MemorySegment h = state.handle;
        if (h == null) throw new IllegalStateException("CharacterDefinition is closed");
        return h;
    }

    // --- Strings ---------------------------------------------------------------------------------

    public String name() {
        return getString(CendaCharacter.FIELD_NAME);
    }

    public void setName(String name) {
        setString(CendaCharacter.FIELD_NAME, name);
    }

    public String objectId() {
        return getString(CendaCharacter.FIELD_OBJECT_ID);
    }

    public void setObjectId(String objectId) {
        setString(CendaCharacter.FIELD_OBJECT_ID, objectId);
    }

    /** The skeleton profile id (for example {@code humanoid}). */
    public String profile() {
        return getString(CendaCharacter.FIELD_PROFILE);
    }

    public void setProfile(String profile) {
        setString(CendaCharacter.FIELD_PROFILE, profile);
    }

    /** The default animation graph reference ("" = none). */
    public String animGraph() {
        return getString(CendaCharacter.FIELD_ANIM_GRAPH);
    }

    public void setAnimGraph(String animGraph) {
        setString(CendaCharacter.FIELD_ANIM_GRAPH, animGraph);
    }

    /** The skeleton model by object id or path when it is not embedded ("" = none). */
    public String modelRef() {
        return getString(CendaCharacter.FIELD_MODEL_REF);
    }

    public void setModelRef(String modelRef) {
        setString(CendaCharacter.FIELD_MODEL_REF, modelRef);
    }

    /** The appearance section as minified JSON ("" = none); opaque until OpenMason #162. */
    public String appearanceJson() {
        return getString(CendaCharacter.FIELD_APPEARANCE);
    }

    /** Sets the appearance section; must be a JSON object, null or "" clears it. */
    public void setAppearanceJson(String json) {
        setString(CendaCharacter.FIELD_APPEARANCE, json);
    }

    /** The model's forward axis: {@code -z} (the engine convention, also when unset), {@code +z}, {@code -x} or {@code +x}. */
    public String facing() {
        String f = getString(CendaCharacter.FIELD_FACING);
        return f.isEmpty() ? "-z" : f;
    }

    /** Sets the facing ({@code -z}, {@code +z}, {@code -x}, {@code +x}; null or "" = unset). */
    public void setFacing(String facing) {
        setString(CendaCharacter.FIELD_FACING, facing);
    }

    /** The stored skeleton structure hash ("" when no skeleton is recorded). */
    public String jointHash() {
        return getString(CendaCharacter.FIELD_JOINT_HASH);
    }

    private String getString(int field) {
        try {
            return CendaCharacter.string((MemorySegment) CendaCharacter.GET_STRING.invokeExact(handle(), field));
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    private void setString(int field, String value) {
        try (Arena arena = Arena.ofConfined()) {
            check((int) CendaCharacter.SET_STRING.invokeExact(handle(), field, cstr(arena, value)), "set");
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    // --- Skeleton --------------------------------------------------------------------------------

    /** The joint set this definition was made for. */
    public List<SkeletonJoint> skeleton() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment h = handle();
            int n = (int) CendaCharacter.JOINT_COUNT.invokeExact(h);
            List<SkeletonJoint> out = new ArrayList<>(n);
            MemorySegment id = slot(arena), name = slot(arena), parent = slot(arena);
            MemorySegment kind = arena.allocate(CendaCharacter.I32);
            for (int i = 0; i < n; i++) {
                check((int) CendaCharacter.JOINT_GET.invokeExact(h, i, id, name, parent, kind), "joint");
                String p = read(parent);
                out.add(new SkeletonJoint(read(id), read(name), p.isEmpty() ? null : p,
                    kind.get(CendaCharacter.I32, 0) == 1 ? RigJoint.Kind.BONE : RigJoint.Kind.PART));
            }
            return List.copyOf(out);
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    /** Records the rig's joints as this definition's skeleton (and recomputes the joint hash). */
    public void setSkeleton(Rig rig) {
        Objects.requireNonNull(rig, "rig");
        try {
            check((int) CendaCharacter.SET_SKELETON.invokeExact(handle(), rig.nativeHandle()), "setSkeleton");
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        } finally {
            Reference.reachabilityFence(rig);
        }
    }

    /** The embedded skeleton model (an {@code .omo}), if any. */
    public Optional<byte[]> embeddedModel() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = slot(arena);
            MemorySegment size = arena.allocate(CendaCharacter.I64);
            check((int) CendaCharacter.MODEL_GET.invokeExact(handle(), data, size), "model");
            long n = size.get(CendaCharacter.I64, 0);
            if (n == 0) return Optional.empty();
            return Optional.of(data.get(CendaCharacter.ADDR, 0).reinterpret(n).toArray(ValueLayout.JAVA_BYTE));
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    /** Embeds a model under {@code entryName} (null = {@code model.omo}); null or empty bytes remove it. */
    public void setEmbeddedModel(byte[] omo, String entryName) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = MemorySegment.NULL;
            long n = omo == null ? 0 : omo.length;
            if (n > 0) {
                data = arena.allocate(n);
                MemorySegment.copy(omo, 0, data, ValueLayout.JAVA_BYTE, 0, omo.length);
            }
            check((int) CendaCharacter.MODEL_SET.invokeExact(handle(), data, n, cstr(arena, entryName)), "setModel");
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    // --- Roles -----------------------------------------------------------------------------------

    /**
     * A weighted bone mask authored by role (OpenMason #156): sets the joint {@code role} maps to in {@code rig}
     * on {@code mask} ({@link com.openmason.engine.rig.BoneMask#set}; {@code flags} BoneMask.BRANCH, blend depth
     * as there). Throws when the role is unmapped or its joint is not in the rig.
     */
    public void maskRole(com.openmason.engine.rig.Rig rig, com.openmason.engine.rig.BoneMask mask, String role,
                         float weight, int flags, int blendDepth) {
        try (Arena arena = Arena.ofConfined()) {
            check((int) CendaCharacter.MASK_ROLE.invokeExact(handle(), rig.nativeHandle(), mask.nativeHandle(),
                arena.allocateFrom(role), weight, flags, blendDepth), "maskRole");
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** Role -> joint id, in file order. */
    public Map<String, String> roles() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment h = handle();
            int n = (int) CendaCharacter.ROLE_COUNT.invokeExact(h);
            Map<String, String> out = new LinkedHashMap<>();
            MemorySegment role = slot(arena), joint = slot(arena);
            for (int i = 0; i < n; i++) {
                check((int) CendaCharacter.ROLE_GET.invokeExact(h, i, role, joint), "role");
                out.put(read(role), read(joint));
            }
            return java.util.Collections.unmodifiableMap(out);
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    public void setRole(String role, String jointId) {
        Objects.requireNonNull(jointId, "jointId");
        setRoleRaw(role, jointId);
    }

    public void removeRole(String role) {
        setRoleRaw(role, null);
    }

    private void setRoleRaw(String role, String jointId) {
        try (Arena arena = Arena.ofConfined()) {
            check((int) CendaCharacter.ROLE_SET.invokeExact(handle(), cstr(arena, role), cstr(arena, jointId)), "setRole");
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    // --- Reference pose and limits ---------------------------------------------------------------

    public List<JointRotation> referencePose() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment h = handle();
            int n = (int) CendaCharacter.POSE_COUNT.invokeExact(h);
            List<JointRotation> out = new ArrayList<>(n);
            MemorySegment joint = slot(arena), rot = vec(arena);
            for (int i = 0; i < n; i++) {
                check((int) CendaCharacter.POSE_GET.invokeExact(h, i, joint, rot), "pose");
                out.add(new JointRotation(read(joint), vec(rot)));
            }
            return List.copyOf(out);
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    public void setReferenceRotation(String jointId, Vec3 rotDeg) {
        Objects.requireNonNull(rotDeg, "rotDeg");
        setPoseRaw(jointId, rotDeg);
    }

    public void removeReferenceRotation(String jointId) {
        setPoseRaw(jointId, null);
    }

    private void setPoseRaw(String jointId, Vec3 rot) {
        try (Arena arena = Arena.ofConfined()) {
            check((int) CendaCharacter.POSE_SET.invokeExact(handle(), cstr(arena, jointId), vec(arena, rot)), "setPose");
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    public List<JointLimit> limits() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment h = handle();
            int n = (int) CendaCharacter.LIMIT_COUNT.invokeExact(h);
            List<JointLimit> out = new ArrayList<>(n);
            MemorySegment joint = slot(arena), min = vec(arena), max = vec(arena);
            for (int i = 0; i < n; i++) {
                check((int) CendaCharacter.LIMIT_GET.invokeExact(h, i, joint, min, max), "limit");
                out.add(new JointLimit(read(joint), vec(min), vec(max)));
            }
            return List.copyOf(out);
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    public void setLimit(String jointId, Vec3 minDeg, Vec3 maxDeg) {
        Objects.requireNonNull(minDeg, "minDeg");
        Objects.requireNonNull(maxDeg, "maxDeg");
        setLimitRaw(jointId, minDeg, maxDeg);
    }

    public void removeLimit(String jointId) {
        setLimitRaw(jointId, null, null);
    }

    private void setLimitRaw(String jointId, Vec3 min, Vec3 max) {
        try (Arena arena = Arena.ofConfined()) {
            check((int) CendaCharacter.LIMIT_SET.invokeExact(handle(), cstr(arena, jointId), vec(arena, min),
                vec(arena, max)), "setLimit");
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    // --- IK goals and chains ---------------------------------------------------------------------

    public List<IkGoal> ikGoals() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment h = handle();
            int n = (int) CendaCharacter.GOAL_COUNT.invokeExact(h);
            List<IkGoal> out = new ArrayList<>(n);
            MemorySegment name = slot(arena), joint = slot(arena);
            for (int i = 0; i < n; i++) {
                check((int) CendaCharacter.GOAL_GET.invokeExact(h, i, name, joint), "goal");
                out.add(new IkGoal(read(name), read(joint)));
            }
            return List.copyOf(out);
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    public void setIkGoal(String name, String jointId) {
        Objects.requireNonNull(jointId, "jointId");
        setGoalRaw(name, jointId);
    }

    public void removeIkGoal(String name) {
        setGoalRaw(name, null);
    }

    private void setGoalRaw(String name, String jointId) {
        try (Arena arena = Arena.ofConfined()) {
            check((int) CendaCharacter.GOAL_SET.invokeExact(handle(), cstr(arena, name), cstr(arena, jointId)), "setIkGoal");
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    public List<Chain> chains() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment h = handle();
            int n = (int) CendaCharacter.CHAIN_COUNT.invokeExact(h);
            List<Chain> out = new ArrayList<>(n);
            MemorySegment name = slot(arena), start = slot(arena), end = slot(arena), goal = slot(arena);
            for (int i = 0; i < n; i++) {
                check((int) CendaCharacter.CHAIN_GET.invokeExact(h, i, name, start, end, goal), "chain");
                String g = read(goal);
                out.add(new Chain(read(name), read(start), read(end), g.isEmpty() ? null : g));
            }
            return List.copyOf(out);
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    public void setChain(Chain chain) {
        Objects.requireNonNull(chain, "chain");
        Objects.requireNonNull(chain.startJointId(), "startJointId");
        Objects.requireNonNull(chain.endJointId(), "endJointId");
        setChainRaw(chain.name(), chain.startJointId(), chain.endJointId(), chain.goal());
    }

    public void removeChain(String name) {
        setChainRaw(name, null, null, null);
    }

    private void setChainRaw(String name, String start, String end, String goal) {
        try (Arena arena = Arena.ofConfined()) {
            check((int) CendaCharacter.CHAIN_SET.invokeExact(handle(), cstr(arena, name), cstr(arena, start),
                cstr(arena, end), cstr(arena, goal)), "setChain");
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    // --- Sockets ---------------------------------------------------------------------------------

    public List<Socket> sockets() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment h = handle();
            int n = (int) CendaCharacter.SOCKET_COUNT.invokeExact(h);
            List<Socket> out = new ArrayList<>(n);
            MemorySegment name = slot(arena), joint = slot(arena);
            MemorySegment pos = vec(arena), rot = vec(arena), scale = vec(arena);
            for (int i = 0; i < n; i++) {
                check((int) CendaCharacter.SOCKET_GET.invokeExact(h, i, name, joint, pos, rot, scale), "socket");
                out.add(new Socket(read(name), read(joint), vec(pos), vec(rot), vec(scale)));
            }
            return List.copyOf(out);
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    /** Adds or replaces a socket; null pos / rot mean zero, null scale means one. */
    public void setSocket(Socket socket) {
        Objects.requireNonNull(socket, "socket");
        Objects.requireNonNull(socket.jointId(), "jointId");
        try (Arena arena = Arena.ofConfined()) {
            check((int) CendaCharacter.SOCKET_SET.invokeExact(handle(), cstr(arena, socket.name()),
                cstr(arena, socket.jointId()), vec(arena, socket.pos()), vec(arena, socket.rotDeg()),
                vec(arena, socket.scale())), "setSocket");
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    public void removeSocket(String name) {
        try (Arena arena = Arena.ofConfined()) {
            check((int) CendaCharacter.SOCKET_SET.invokeExact(handle(), cstr(arena, name), MemorySegment.NULL,
                MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL), "removeSocket");
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    // --- Metrics ---------------------------------------------------------------------------------

    public Metrics metrics() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment m = arena.allocate(CendaCharacter.METRICS_BYTES, 4);
            check((int) CendaCharacter.METRICS_GET.invokeExact(handle(), m), "metrics");
            return metrics(m);
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    public void setMetrics(Metrics metrics) {
        Objects.requireNonNull(metrics, "metrics");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment m = arena.allocate(CendaCharacter.METRICS_BYTES, 4);
            m.set(CendaCharacter.F32, 0, metrics.height());
            m.set(CendaCharacter.F32, 4, metrics.eyeHeight());
            m.set(CendaCharacter.F32, 8, metrics.width());
            m.set(CendaCharacter.F32, 12, metrics.hipHeight());
            int bits = 0;
            for (Metric metric : metrics.overridden()) bits |= 1 << metric.ordinal();
            m.set(CendaCharacter.I32, 16, bits);
            m.set(CendaCharacter.I32, 20, 0);
            check((int) CendaCharacter.METRICS_SET.invokeExact(handle(), m), "setMetrics");
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    /**
     * Measures the character in its reference pose (see {@code cc_char_compute_metrics}): height and width
     * from the model's rest-pose mesh bounds, hip height from the {@code hips} role, eye height from the
     * {@code eyes} socket, else the {@code eye_l}/{@code eye_r} roles, else the {@code head} role. Overridden
     * metrics keep their stored values. Does not change the definition; pass the result to
     * {@link #setMetrics} to keep it.
     */
    public Metrics computeMetrics(Rig rig, Vec3 boundsMin, Vec3 boundsMax) {
        Objects.requireNonNull(rig, "rig");
        Objects.requireNonNull(boundsMin, "boundsMin");
        Objects.requireNonNull(boundsMax, "boundsMax");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment m = arena.allocate(CendaCharacter.METRICS_BYTES, 4);
            check((int) CendaCharacter.COMPUTE_METRICS.invokeExact(handle(), rig.nativeHandle(), vec(arena, boundsMin),
                vec(arena, boundsMax), m), "computeMetrics");
            return metrics(m);
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        } finally {
            Reference.reachabilityFence(rig);
        }
    }

    private static Metrics metrics(MemorySegment m) {
        int bits = m.get(CendaCharacter.I32, 16);
        Set<Metric> over = EnumSet.noneOf(Metric.class);
        for (Metric metric : Metric.values()) {
            if ((bits & (1 << metric.ordinal())) != 0) over.add(metric);
        }
        return new Metrics(m.get(CendaCharacter.F32, 0), m.get(CendaCharacter.F32, 4), m.get(CendaCharacter.F32, 8),
            m.get(CendaCharacter.F32, 12), over);
    }

    // --- Checks ----------------------------------------------------------------------------------

    /**
     * Checks the definition on its own and, with a rig (may be null), against that rig: the recorded joint
     * set vs the rig's joints and every joint reference.
     */
    public List<Issue> check(Rig rig) {
        try {
            // A typed local: a conditional passed straight to invokeExact is typed Object.
            MemorySegment rigHandle = rig == null ? MemorySegment.NULL : rig.nativeHandle();
            MemorySegment r = (MemorySegment) CendaCharacter.CHECK.invokeExact(handle(), rigHandle);
            return takeReport(r, "cc_char_check");
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        } finally {
            Reference.reachabilityFence(rig);
        }
    }

    // --- Characterization (OpenMason #154) -------------------------------------------------------

    /**
     * Replaces the role map from the rig (see {@code cenda/character.h}): candidates are the rig's bones (its
     * parts when it has none), names are scored against the profile's aliases, roles are assigned parents
     * first, fingers/tails structurally, and an optional parent role's joint is promoted into a missing
     * required role. Sides come from the joints' positions, not their names. Sets the profile id and, when
     * the facing is unset and the profile can detect it, the facing.
     *
     * @throws IllegalStateException when the mapping is locked
     */
    public AutoMapResult autoMap(Rig rig, CharacterProfile profile) {
        Objects.requireNonNull(rig, "rig");
        Objects.requireNonNull(profile, "profile");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment m = (MemorySegment) CendaCharacter.AUTO_MAP.invokeExact(handle(), rig.nativeHandle(), profile.handle());
            if (m.equals(MemorySegment.NULL)) {
                String why = CendaCharacter.lastError();
                if (locked()) throw new IllegalStateException(why);
                throw new IllegalArgumentException("autoMap: " + why);
            }
            try {
                int n = (int) CendaCharacter.AUTOMAP_COUNT.invokeExact(m);
                List<RoleMatch> matches = new ArrayList<>(n);
                MemorySegment role = slot(arena), joint = slot(arena);
                MemorySegment conf = arena.allocate(CendaCharacter.F32);
                for (int i = 0; i < n; i++) {
                    check((int) CendaCharacter.AUTOMAP_GET.invokeExact(m, i, role, joint, conf), "autoMap");
                    matches.add(new RoleMatch(read(role), read(joint), conf.get(CendaCharacter.F32, 0)));
                }
                MemorySegment notes = (MemorySegment) CendaCharacter.AUTOMAP_NOTES.invokeExact(m);
                return new AutoMapResult(List.copyOf(matches), issues(notes, arena));
            } finally {
                CendaCharacter.AUTOMAP_DESTROY.invokeExact(m);
            }
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        } finally {
            Reference.reachabilityFence(rig);
            Reference.reachabilityFence(profile);
        }
    }

    /**
     * {@link #check(Rig)} plus the profile checks: required roles, one joint per role, hierarchy order, sides
     * (by position, in the definition's facing), mirror symmetry, unknown roles, profile id.
     */
    public List<Issue> validate(Rig rig, CharacterProfile profile) {
        Objects.requireNonNull(profile, "profile");
        try {
            MemorySegment rigHandle = rig == null ? MemorySegment.NULL : rig.nativeHandle();
            MemorySegment r = (MemorySegment) CendaCharacter.VALIDATE.invokeExact(handle(), rigHandle, profile.handle());
            return takeReport(r, "cc_char_validate");
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        } finally {
            Reference.reachabilityFence(rig);
            Reference.reachabilityFence(profile);
        }
    }

    /**
     * Replaces the reference pose with the profile's (rest normalization): every mapped role with a profile
     * direction is turned, parents first, until it points along that direction in the model's frame.
     *
     * @return the number of joints turned
     * @throws IllegalStateException when the mapping is locked
     */
    public int fitReferencePose(Rig rig, CharacterProfile profile) {
        Objects.requireNonNull(rig, "rig");
        Objects.requireNonNull(profile, "profile");
        try {
            int rc = (int) CendaCharacter.FIT_REFERENCE_POSE.invokeExact(handle(), rig.nativeHandle(), profile.handle());
            if (rc < 0) check(rc, "fitReferencePose");
            return rc;
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        } finally {
            Reference.reachabilityFence(rig);
            Reference.reachabilityFence(profile);
        }
    }

    /** True when the role mapping is locked. */
    public boolean locked() {
        try {
            return (int) CendaCharacter.LOCKED.invokeExact(handle()) != 0;
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    /**
     * Locks the role mapping (roles, skeleton, reference pose, profile, facing) after validating it.
     *
     * @throws IllegalStateException with the first validation error when it does not validate
     */
    public void lock(Rig rig, CharacterProfile profile) {
        Objects.requireNonNull(profile, "profile");
        try {
            MemorySegment rigHandle = rig == null ? MemorySegment.NULL : rig.nativeHandle();
            int rc = (int) CendaCharacter.LOCK.invokeExact(handle(), rigHandle, profile.handle());
            if (rc != CendaCharacter.OK) throw new IllegalStateException(CendaCharacter.lastError());
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        } finally {
            Reference.reachabilityFence(rig);
            Reference.reachabilityFence(profile);
        }
    }

    public void unlock() {
        try {
            check((int) CendaCharacter.UNLOCK.invokeExact(handle()), "unlock");
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    /** Reads a report this call owns and destroys it. */
    private static List<Issue> takeReport(MemorySegment report, String what) throws Throwable {
        if (report.equals(MemorySegment.NULL)) throw new IllegalStateException(what + ": " + CendaCharacter.lastError());
        try (Arena arena = Arena.ofConfined()) {
            return issues(report, arena);
        } finally {
            CendaCharacter.REPORT_DESTROY.invokeExact(report);
        }
    }

    private static List<Issue> issues(MemorySegment report, Arena arena) throws Throwable {
        int n = (int) CendaCharacter.REPORT_COUNT.invokeExact(report);
        List<Issue> out = new ArrayList<>(n);
        MemorySegment sev = arena.allocate(CendaCharacter.I32), code = arena.allocate(CendaCharacter.I32);
        MemorySegment subject = slot(arena), message = slot(arena);
        for (int i = 0; i < n; i++) {
            check((int) CendaCharacter.REPORT_GET.invokeExact(report, i, sev, code, subject, message), "report");
            int s = sev.get(CendaCharacter.I32, 0);
            Severity severity = s >= 2 ? Severity.ERROR : s == 1 ? Severity.WARNING : Severity.INFO;
            out.add(new Issue(severity, IssueCode.of(code.get(CendaCharacter.I32, 0)), read(subject), read(message)));
        }
        return List.copyOf(out);
    }

    // --- Marshalling -----------------------------------------------------------------------------

    private static MemorySegment cstr(Arena arena, String s) {
        return s == null ? MemorySegment.NULL : arena.allocateFrom(s);
    }

    private static MemorySegment slot(Arena arena) {
        return arena.allocate(CendaCharacter.ADDR);
    }

    private static String read(MemorySegment slot) {
        String s = CendaCharacter.string(slot.get(CendaCharacter.ADDR, 0));
        return s == null ? "" : s;
    }

    private static MemorySegment vec(Arena arena) {
        return arena.allocate(CendaCharacter.F32, 3);
    }

    private static MemorySegment vec(Arena arena, Vec3 v) {
        if (v == null) return MemorySegment.NULL;
        MemorySegment s = vec(arena);
        s.setAtIndex(CendaCharacter.F32, 0, v.x());
        s.setAtIndex(CendaCharacter.F32, 1, v.y());
        s.setAtIndex(CendaCharacter.F32, 2, v.z());
        return s;
    }

    private static Vec3 vec(MemorySegment s) {
        return new Vec3(s.getAtIndex(CendaCharacter.F32, 0), s.getAtIndex(CendaCharacter.F32, 1),
            s.getAtIndex(CendaCharacter.F32, 2));
    }

    private static void check(int rc, String what) {
        if (rc == CendaCharacter.ERR_LOCKED) {
            throw new IllegalStateException(what + ": " + CendaCharacter.lastError());
        }
        if (rc != CendaCharacter.OK) {
            throw new IllegalArgumentException(what + ": " + CendaCharacter.lastError());
        }
    }
}
