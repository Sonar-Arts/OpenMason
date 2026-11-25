package com.openmason.engine.anim;

import com.openmason.engine.character.CharacterDefinition;
import com.openmason.engine.format.oma.AnimLayerMeta;
import com.openmason.engine.format.oma.Easing;
import com.openmason.engine.format.oma.ParsedAnimClip;
import com.openmason.engine.format.oma.ParsedAnimTrack;
import com.openmason.engine.format.oma.ParsedKeyframe;
import org.joml.Vector3f;

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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * An {@code .omanim} animation clip (OpenMason #155, format 1.2): tracks plus notifies, float curves, sync
 * markers, root motion and additive settings. A thin binding over the native {@code cm_*} module: the codec,
 * editing model and clip-data queries ({@link #fire}, {@link #sampleCurve}, {@link #markerPosition}, role
 * binding) all live in Cenda. Reads 1.0/1.1/1.2, writes 1.2; every value comes back bit-identical, and the
 * 1.1 readers ({@code OMAReader}, the tool's deserializer) still read what it writes.
 *
 * <p>Keys reuse {@link ParsedKeyframe}; {@link #toParsedClip()} hands the clip to the existing runtime
 * ({@code RigClip.of}). Not thread-safe: one thread at a time. Freed by {@link #close()} or, as a backstop,
 * when unreachable.
 */
public final class AnimClipDocument implements AutoCloseable {

    private static final Cleaner CLEANER = Cleaner.create();

    /** Header fields; {@code hasLayer} false = a 1.0 clip without a layer block. */
    public record Header(float fps, float duration, boolean loop, boolean hasLayer, float fadeInSeconds,
                         float fadeOutSeconds, int priority) {
    }

    /** One track; {@code role} null = joint-addressed (then {@code partId} is the joint id). */
    public record Track(String partId, String partName, String role, boolean bone, List<ParsedKeyframe> keys) {
        public Track {
            keys = keys == null ? List.of() : List.copyOf(keys);
        }

        /** A track keyed by a profile role (plays on any character of that profile). */
        public static Track forRole(String role, boolean bone, List<ParsedKeyframe> keys) {
            return new Track(null, null, role, bone, keys);
        }
    }

    /** A named event; {@code durationSeconds > 0} = a range (BEGIN and END); payload = a JSON object or null. */
    public record Notify(String name, float time, float durationSeconds, String payloadJson) {
    }

    public enum Phase { POINT, BEGIN, END }

    /** One notify edge a step of playback crossed. */
    public record Fired(int notifyIndex, Phase phase, float time) {
    }

    public enum Interp { LINEAR, STEP, CUBIC }

    /** One curve key; tangents (value per second) are used by CUBIC segments. */
    public record CurveKey(float time, float value, float inTangent, float outTangent, Interp interp) {
        public static CurveKey linear(float time, float value) {
            return new CurveKey(time, value, 0f, 0f, Interp.LINEAR);
        }
    }

    public record Curve(String name, List<CurveKey> keys) {
        public Curve {
            keys = List.copyOf(keys);
        }
    }

    public record SyncMarker(String name, float time) {
    }

    /** Where a time lies between sync markers ({@code marker} -1 = before the first of a non-looping clip). */
    public record MarkerPosition(int marker, float fraction) {
    }

    public enum RootAxis { X, Y, Z, YAW }

    /** The root-motion track (a part id or {@code role:<role>}) and the axes extracted from it. */
    public record RootMotion(String joint, Set<RootAxis> axes) {
        public RootMotion {
            axes = axes == null || axes.isEmpty() ? Set.of() : Set.copyOf(axes);
        }
    }

    public enum AdditiveReference { NONE, FRAME, CLIP, CHARACTER }

    public record Additive(AdditiveReference reference, float time, String clipRef, boolean meshSpace) {
        public static final Additive NONE = new Additive(AdditiveReference.NONE, 0f, null, false);
    }

    /** {@link #bindRoles}: the bound copy and how many role tracks had no joint on the character. */
    public record Bound(AnimClipDocument clip, int droppedTracks) {
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
                    CendaAnimClip.DESTROY.invokeExact(h);
                } catch (Throwable ignored) {
                    // best effort at shutdown
                }
            }
        }
    }

    private final State state;
    private final Cleaner.Cleanable cleanable;

    private AnimClipDocument(MemorySegment handle) {
        this.state = new State(handle);
        this.cleanable = CLEANER.register(this, state);
    }

    private static AnimClipDocument wrap(MemorySegment h, String what) {
        if (h.equals(MemorySegment.NULL)) throw new IllegalStateException(what + ": " + CendaAnimClip.lastError());
        return new AnimClipDocument(h);
    }

    // --- Lifetime and I/O ------------------------------------------------------------------------

    public static AnimClipDocument create() {
        CendaAnimClip.requireAvailable();
        try {
            return wrap((MemorySegment) CendaAnimClip.CREATE.invokeExact(), "create");
        } catch (Throwable t) {
            throw CendaAnimClip.rethrow(t);
        }
    }

    /** Parses an {@code .omanim} archive (1.0, 1.1 or 1.2). */
    public static AnimClipDocument read(byte[] bytes) throws IOException {
        CendaAnimClip.requireAvailable();
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length == 0) throw new IOException("empty .omanim");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = arena.allocate(bytes.length);
            MemorySegment.copy(bytes, 0, data, ValueLayout.JAVA_BYTE, 0, bytes.length);
            MemorySegment h = (MemorySegment) CendaAnimClip.READ.invokeExact(data, (long) bytes.length, MemorySegment.NULL);
            if (h.equals(MemorySegment.NULL)) throw new IOException(CendaAnimClip.lastError());
            return new AnimClipDocument(h);
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaAnimClip.rethrow(t);
        }
    }

    public static AnimClipDocument read(Path file) throws IOException {
        return read(Files.readAllBytes(file));
    }

    /** Serializes as {@code .omanim} 1.2 (deterministic). */
    public byte[] write() {
        try {
            MemorySegment b = (MemorySegment) CendaAnimClip.WRITE.invokeExact(handle());
            if (b.equals(MemorySegment.NULL)) throw new IllegalStateException("write: " + CendaAnimClip.lastError());
            try {
                long size = (long) CendaAnimClip.BYTES_SIZE.invokeExact(b);
                MemorySegment data = (MemorySegment) CendaAnimClip.BYTES_DATA.invokeExact(b);
                return data.reinterpret(size).toArray(ValueLayout.JAVA_BYTE);
            } finally {
                CendaAnimClip.BYTES_DESTROY.invokeExact(b);
            }
        } catch (Throwable t) {
            throw CendaAnimClip.rethrow(t);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    public void write(Path file) throws IOException {
        Files.write(file, write());
    }

    public AnimClipDocument copy() {
        try {
            return wrap((MemorySegment) CendaAnimClip.CLONE.invokeExact(handle()), "copy");
        } catch (Throwable t) {
            throw CendaAnimClip.rethrow(t);
        }
    }

    @Override
    public void close() {
        cleanable.clean();
    }

    MemorySegment handle() {
        MemorySegment h = state.handle;
        if (h == null) throw new IllegalStateException("AnimClipDocument is closed");
        return h;
    }

    /**
     * The native {@code cm_clip*}, for other Cenda bindings that take a clip (the animation graph copies what it
     * needs during the call). Keep this document reachable for the call; never free the handle.
     */
    public MemorySegment nativeHandle() {
        return handle();
    }

    // --- Header ----------------------------------------------------------------------------------

    public String name() {
        return getString(CendaAnimClip.FIELD_NAME);
    }

    public void setName(String name) {
        setString(CendaAnimClip.FIELD_NAME, name);
    }

    /** The version the clip was read as ("1.2" for a new clip). */
    public String version() {
        return getString(CendaAnimClip.FIELD_VERSION);
    }

    public Optional<String> modelRef() {
        try {
            if ((int) CendaAnimClip.MODEL_REF_NULL.invokeExact(handle()) != 0) return Optional.empty();
            return Optional.of(getString(CendaAnimClip.FIELD_MODEL_REF));
        } catch (Throwable t) {
            throw CendaAnimClip.rethrow(t);
        }
    }

    /** null writes JSON null. */
    public void setModelRef(String modelRef) {
        setString(CendaAnimClip.FIELD_MODEL_REF, modelRef);
    }

    public AnimLayerMeta.LayerType layerType() {
        return AnimLayerMeta.LayerType.fromString(getString(CendaAnimClip.FIELD_LAYER_TYPE));
    }

    public void setLayerType(AnimLayerMeta.LayerType type) {
        setString(CendaAnimClip.FIELD_LAYER_TYPE, Objects.requireNonNull(type, "type").name());
    }

    public Header header() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment h = arena.allocate(CendaAnimClip.HEADER_BYTES, 4);
            check((int) CendaAnimClip.HEADER_GET.invokeExact(handle(), h), "header");
            return new Header(h.get(CendaAnimClip.F32, 0), h.get(CendaAnimClip.F32, 4), h.get(CendaAnimClip.I32, 8) != 0,
                h.get(CendaAnimClip.I32, 12) != 0, h.get(CendaAnimClip.F32, 16), h.get(CendaAnimClip.F32, 20),
                h.get(CendaAnimClip.I32, 24));
        } catch (Throwable t) {
            throw CendaAnimClip.rethrow(t);
        }
    }

    public void setHeader(Header header) {
        Objects.requireNonNull(header, "header");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment h = arena.allocate(CendaAnimClip.HEADER_BYTES, 4);
            h.set(CendaAnimClip.F32, 0, header.fps());
            h.set(CendaAnimClip.F32, 4, header.duration());
            h.set(CendaAnimClip.I32, 8, header.loop() ? 1 : 0);
            h.set(CendaAnimClip.I32, 12, header.hasLayer() ? 1 : 0);
            h.set(CendaAnimClip.F32, 16, header.fadeInSeconds());
            h.set(CendaAnimClip.F32, 20, header.fadeOutSeconds());
            h.set(CendaAnimClip.I32, 24, header.priority());
            check((int) CendaAnimClip.HEADER_SET.invokeExact(handle(), h), "setHeader");
        } catch (Throwable t) {
            throw CendaAnimClip.rethrow(t);
        }
    }

    /** The overlay mask (part names; empty = every part). */
    public List<String> mask() {
        try {
            MemorySegment h = handle();
            int n = (int) CendaAnimClip.MASK_COUNT.invokeExact(h);
            List<String> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) out.add(CendaAnimClip.string((MemorySegment) CendaAnimClip.MASK_GET.invokeExact(h, i)));
            return List.copyOf(out);
        } catch (Throwable t) {
            throw CendaAnimClip.rethrow(t);
        }
    }

    public void setMask(List<String> parts) {
        try (Arena arena = Arena.ofConfined()) {
            List<String> list = parts == null ? List.of() : parts;
            MemorySegment array = list.isEmpty() ? MemorySegment.NULL : arena.allocate(CendaAnimClip.ADDR, list.size());
            for (int i = 0; i < list.size(); i++) array.setAtIndex(CendaAnimClip.ADDR, i, cstr(arena, list.get(i)));
            check((int) CendaAnimClip.MASK_SET.invokeExact(handle(), array, list.size()), "setMask");
        } catch (Throwable t) {
            throw CendaAnimClip.rethrow(t);
        }
    }

    // --- Tracks ----------------------------------------------------------------------------------

    public List<Track> tracks() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment h = handle();
            int n = (int) CendaAnimClip.TRACK_COUNT.invokeExact(h);
            List<Track> out = new ArrayList<>(n);
            MemorySegment id = slot(arena), name = slot(arena), role = slot(arena);
            MemorySegment bone = arena.allocate(CendaAnimClip.I32), count = arena.allocate(CendaAnimClip.I32);
            for (int i = 0; i < n; i++) {
                check((int) CendaAnimClip.TRACK_GET.invokeExact(h, i, id, name, role, bone, count), "track");
                int k = count.get(CendaAnimClip.I32, 0);
                MemorySegment keys = arena.allocate(CendaAnimClip.KEY_BYTES * Math.max(1, k), 4);
                int got = (int) CendaAnimClip.TRACK_KEYS.invokeExact(h, i, keys, k);
                if (got < 0) check(got, "keys");
                List<ParsedKeyframe> list = new ArrayList<>(k);
                for (int j = 0; j < k; j++) list.add(key(keys, CendaAnimClip.KEY_BYTES * j));
                String r = read(role);
                out.add(new Track(read(id), read(name), r.isEmpty() ? null : r, bone.get(CendaAnimClip.I32, 0) != 0, list));
            }
            return List.copyOf(out);
        } catch (Throwable t) {
            throw CendaAnimClip.rethrow(t);
        }
    }

    /** Adds or replaces a track (by role, or else by part id); returns its index. */
    public int setTrack(Track track) {
        Objects.requireNonNull(track, "track");
        try (Arena arena = Arena.ofConfined()) {
            List<ParsedKeyframe> keys = track.keys();
            MemorySegment k = arena.allocate(CendaAnimClip.KEY_BYTES * Math.max(1, keys.size()), 4);
            for (int j = 0; j < keys.size(); j++) putKey(k, CendaAnimClip.KEY_BYTES * j, keys.get(j));
            int rc = (int) CendaAnimClip.TRACK_SET.invokeExact(handle(), cstr(arena, track.partId()), cstr(arena, track.partName()),
                cstr(arena, track.role()), track.bone() ? 1 : 0, k, keys.size());
            if (rc < 0) check(rc, "setTrack");
            return rc;
        } catch (Throwable t) {
            throw CendaAnimClip.rethrow(t);
        }
    }

    public void removeTrack(int index) {
        try {
            check((int) CendaAnimClip.TRACK_REMOVE.invokeExact(handle(), index), "removeTrack");
        } catch (Throwable t) {
            throw CendaAnimClip.rethrow(t);
        }
    }

    // --- Notifies --------------------------------------------------------------------------------

    /** The notifies, in time order. */
    public List<Notify> notifies() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment h = handle();
            int n = (int) CendaAnimClip.NOTIFY_COUNT.invokeExact(h);
            List<Notify> out = new ArrayList<>(n);
            MemorySegment name = slot(arena), payload = slot(arena);
            MemorySegment time = arena.allocate(CendaAnimClip.F32), duration = arena.allocate(CendaAnimClip.F32);
            for (int i = 0; i < n; i++) {
                check((int) CendaAnimClip.NOTIFY_GET.invokeExact(h, i, name, time, duration, payload), "notify");
                String p = read(payload);
                out.add(new Notify(read(name), time.get(CendaAnimClip.F32, 0), duration.get(CendaAnimClip.F32, 0),
                    p.isEmpty() ? null : p));
            }
            return List.copyOf(out);
        } catch (Throwable t) {
            throw CendaAnimClip.rethrow(t);
        }
    }

    /** Inserts in time order; returns the new index. */
    public int addNotify(Notify notify) {
        Objects.requireNonNull(notify, "notify");
        try (Arena arena = Arena.ofConfined()) {
            int rc = (int) CendaAnimClip.NOTIFY_ADD.invokeExact(handle(), cstr(arena, notify.name()), notify.time(),
                notify.durationSeconds(), cstr(arena, notify.payloadJson()));
            if (rc < 0) check(rc, "addNotify");
            return rc;
        } catch (Throwable t) {
            throw CendaAnimClip.rethrow(t);
        }
    }

    public void removeNotify(int index) {
        try {
            check((int) CendaAnimClip.NOTIFY_REMOVE.invokeExact(handle(), index), "removeNotify");
        } catch (Throwable t) {
            throw CendaAnimClip.rethrow(t);
        }
    }

    /**
     * The notify edges a step of playback crosses, in playback order ({@code cm_clip_fire}): forward
     * {@code [prev, prev+delta)}, backward {@code (prev+delta, prev]}, wrapping when the clip loops; a step as
     * long as the clip fires every edge once.
     *
     * @param prevTime the clip time before the step
     * @param delta    signed advance (play rate x dt)
     */
    public List<Fired> fire(float prevTime, float delta) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment h = handle();
            int n = (int) CendaAnimClip.FIRE.invokeExact(h, prevTime, delta, MemorySegment.NULL, 0);
            if (n <= 0) {
                if (n < 0) check(n, "fire");
                return List.of();
            }
            MemorySegment out = arena.allocate(CendaAnimClip.FIRED_BYTES * n, 4);
            n = (int) CendaAnimClip.FIRE.invokeExact(h, prevTime, delta, out, n);
            List<Fired> list = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                long o = CendaAnimClip.FIRED_BYTES * i;
                list.add(new Fired(out.get(CendaAnimClip.I32, o), Phase.values()[out.get(CendaAnimClip.I32, o + 4)],
                    out.get(CendaAnimClip.F32, o + 8)));
            }
            return List.copyOf(list);
        } catch (Throwable t) {
            throw CendaAnimClip.rethrow(t);
        }
    }

    // --- Curves ----------------------------------------------------------------------------------

    public List<Curve> curves() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment h = handle();
            int n = (int) CendaAnimClip.CURVE_COUNT.invokeExact(h);
            List<Curve> out = new ArrayList<>(n);
            MemorySegment name = slot(arena), count = arena.allocate(CendaAnimClip.I32);
            for (int i = 0; i < n; i++) {
                check((int) CendaAnimClip.CURVE_GET.invokeExact(h, i, name, count), "curve");
                int k = count.get(CendaAnimClip.I32, 0);
                MemorySegment keys = arena.allocate(CendaAnimClip.CURVE_KEY_BYTES * Math.max(1, k), 4);
                int got = (int) CendaAnimClip.CURVE_KEYS.invokeExact(h, i, keys, k);
                if (got < 0) check(got, "curveKeys");
                List<CurveKey> list = new ArrayList<>(k);
                for (int j = 0; j < k; j++) {
                    long o = CendaAnimClip.CURVE_KEY_BYTES * j;
                    list.add(new CurveKey(keys.get(CendaAnimClip.F32, o), keys.get(CendaAnimClip.F32, o + 4),
                        keys.get(CendaAnimClip.F32, o + 8), keys.get(CendaAnimClip.F32, o + 12),
                        Interp.values()[keys.get(CendaAnimClip.I32, o + 16)]));
                }
                out.add(new Curve(read(name), list));
            }
            return List.copyOf(out);
        } catch (Throwable t) {
            throw CendaAnimClip.rethrow(t);
        }
    }

    /** Adds or replaces a curve (keys sorted by time); returns its index. */
    public int setCurve(Curve curve) {
        Objects.requireNonNull(curve, "curve");
        if (curve.keys().isEmpty()) throw new IllegalArgumentException("a curve needs keys; use removeCurve");
        try (Arena arena = Arena.ofConfined()) {
            List<CurveKey> keys = curve.keys();
            MemorySegment k = arena.allocate(CendaAnimClip.CURVE_KEY_BYTES * keys.size(), 4);
            for (int j = 0; j < keys.size(); j++) {
                long o = CendaAnimClip.CURVE_KEY_BYTES * j;
                CurveKey key = keys.get(j);
                k.set(CendaAnimClip.F32, o, key.time());
                k.set(CendaAnimClip.F32, o + 4, key.value());
                k.set(CendaAnimClip.F32, o + 8, key.inTangent());
                k.set(CendaAnimClip.F32, o + 12, key.outTangent());
                k.set(CendaAnimClip.I32, o + 16, key.interp().ordinal());
            }
            int rc = (int) CendaAnimClip.CURVE_SET.invokeExact(handle(), cstr(arena, curve.name()), k, keys.size());
            if (rc < 0) check(rc, "setCurve");
            return rc;
        } catch (Throwable t) {
            throw CendaAnimClip.rethrow(t);
        }
    }

    public void removeCurve(String name) {
        try (Arena arena = Arena.ofConfined()) {
            check((int) CendaAnimClip.CURVE_SET.invokeExact(handle(), cstr(arena, name), MemorySegment.NULL, 0), "removeCurve");
        } catch (Throwable t) {
            throw CendaAnimClip.rethrow(t);
        }
    }

    /** The index of a curve, -1 when none. */
    public int curveIndex(String name) {
        try (Arena arena = Arena.ofConfined()) {
            return (int) CendaAnimClip.CURVE_FIND.invokeExact(handle(), cstr(arena, name));
        } catch (Throwable t) {
            throw CendaAnimClip.rethrow(t);
        }
    }

    /** A curve's value at a time (clamped to its first/last key). */
    public float sampleCurve(int index, float time) {
        try {
            return (float) CendaAnimClip.CURVE_SAMPLE.invokeExact(handle(), index, time);
        } catch (Throwable t) {
            throw CendaAnimClip.rethrow(t);
        }
    }

    // --- Sync markers ----------------------------------------------------------------------------

    public List<SyncMarker> markers() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment h = handle();
            int n = (int) CendaAnimClip.MARKER_COUNT.invokeExact(h);
            List<SyncMarker> out = new ArrayList<>(n);
            MemorySegment name = slot(arena), time = arena.allocate(CendaAnimClip.F32);
            for (int i = 0; i < n; i++) {
                check((int) CendaAnimClip.MARKER_GET.invokeExact(h, i, name, time), "marker");
                out.add(new SyncMarker(read(name), time.get(CendaAnimClip.F32, 0)));
            }
            return List.copyOf(out);
        } catch (Throwable t) {
            throw CendaAnimClip.rethrow(t);
        }
    }

    public int addMarker(SyncMarker marker) {
        Objects.requireNonNull(marker, "marker");
        try (Arena arena = Arena.ofConfined()) {
            int rc = (int) CendaAnimClip.MARKER_ADD.invokeExact(handle(), cstr(arena, marker.name()), marker.time());
            if (rc < 0) check(rc, "addMarker");
            return rc;
        } catch (Throwable t) {
            throw CendaAnimClip.rethrow(t);
        }
    }

    public void removeMarker(int index) {
        try {
            check((int) CendaAnimClip.MARKER_REMOVE.invokeExact(handle(), index), "removeMarker");
        } catch (Throwable t) {
            throw CendaAnimClip.rethrow(t);
        }
    }

    public MarkerPosition markerPosition(float time) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment m = arena.allocate(CendaAnimClip.I32), f = arena.allocate(CendaAnimClip.F32);
            check((int) CendaAnimClip.MARKER_POSITION.invokeExact(handle(), time, m, f), "markerPosition");
            return new MarkerPosition(m.get(CendaAnimClip.I32, 0), f.get(CendaAnimClip.F32, 0));
        } catch (Throwable t) {
            throw CendaAnimClip.rethrow(t);
        }
    }

    /** The clip time at {@code fraction} of the way from {@code marker} to the next. */
    public float markerTime(int marker, float fraction) {
        try {
            return (float) CendaAnimClip.MARKER_TIME.invokeExact(handle(), marker, fraction);
        } catch (Throwable t) {
            throw CendaAnimClip.rethrow(t);
        }
    }

    // --- Root motion and additive ----------------------------------------------------------------

    public Optional<RootMotion> rootMotion() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment joint = slot(arena), axes = arena.allocate(CendaAnimClip.I32);
            check((int) CendaAnimClip.ROOT_GET.invokeExact(handle(), joint, axes), "rootMotion");
            String j = read(joint);
            if (j.isEmpty()) return Optional.empty();
            int bits = axes.get(CendaAnimClip.I32, 0);
            Set<RootAxis> set = EnumSet.noneOf(RootAxis.class);
            for (RootAxis a : RootAxis.values()) {
                if ((bits & (1 << a.ordinal())) != 0) set.add(a);
            }
            return Optional.of(new RootMotion(j, set));
        } catch (Throwable t) {
            throw CendaAnimClip.rethrow(t);
        }
    }

    /** null clears it. */
    public void setRootMotion(RootMotion rootMotion) {
        try (Arena arena = Arena.ofConfined()) {
            int bits = 0;
            if (rootMotion != null) {
                for (RootAxis a : rootMotion.axes()) bits |= 1 << a.ordinal();
            }
            check((int) CendaAnimClip.ROOT_SET.invokeExact(handle(), cstr(arena, rootMotion == null ? null : rootMotion.joint()),
                bits), "setRootMotion");
        } catch (Throwable t) {
            throw CendaAnimClip.rethrow(t);
        }
    }

    public Additive additive() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ref = arena.allocate(CendaAnimClip.I32), time = arena.allocate(CendaAnimClip.F32);
            MemorySegment clip = slot(arena), mesh = arena.allocate(CendaAnimClip.I32);
            check((int) CendaAnimClip.ADDITIVE_GET.invokeExact(handle(), ref, time, clip, mesh), "additive");
            String c = read(clip);
            return new Additive(AdditiveReference.values()[ref.get(CendaAnimClip.I32, 0)], time.get(CendaAnimClip.F32, 0),
                c.isEmpty() ? null : c, mesh.get(CendaAnimClip.I32, 0) != 0);
        } catch (Throwable t) {
            throw CendaAnimClip.rethrow(t);
        }
    }

    public void setAdditive(Additive additive) {
        Additive a = additive == null ? Additive.NONE : additive;
        try (Arena arena = Arena.ofConfined()) {
            check((int) CendaAnimClip.ADDITIVE_SET.invokeExact(handle(), a.reference().ordinal(), a.time(), cstr(arena, a.clipRef()),
                a.meshSpace() ? 1 : 0), "setAdditive");
        } catch (Throwable t) {
            throw CendaAnimClip.rethrow(t);
        }
    }

    // --- Roles and the runtime -------------------------------------------------------------------

    /** A copy whose role tracks address the character's joints (role tracks the character lacks are dropped). */
    public Bound bindRoles(CharacterDefinition character) {
        Objects.requireNonNull(character, "character");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment dropped = arena.allocate(CendaAnimClip.I32);
            MemorySegment h = (MemorySegment) CendaAnimClip.BIND_ROLES.invokeExact(handle(), character.nativeHandle(), dropped);
            return new Bound(wrap(h, "bindRoles"), dropped.get(CendaAnimClip.I32, 0));
        } catch (Throwable t) {
            throw CendaAnimClip.rethrow(t);
        } finally {
            Reference.reachabilityFence(character);
        }
    }

    /** A copy whose tracks on joints the character maps to roles become role tracks (portable across characters). */
    public AnimClipDocument toRoles(CharacterDefinition character) {
        Objects.requireNonNull(character, "character");
        try {
            return wrap((MemorySegment) CendaAnimClip.TO_ROLES.invokeExact(handle(), character.nativeHandle()), "toRoles");
        } catch (Throwable t) {
            throw CendaAnimClip.rethrow(t);
        } finally {
            Reference.reachabilityFence(character);
        }
    }

    /**
     * The clip as the existing runtime reads it ({@code RigClip.of}): name, header, layer, and the tracks (role
     * tracks keep their {@code role:<role>} part id, which binds to no joint: {@link #bindRoles} them first).
     */
    public ParsedAnimClip toParsedClip() {
        Header h = header();
        List<ParsedAnimTrack> tracks = new ArrayList<>();
        for (Track t : tracks()) tracks.add(new ParsedAnimTrack(t.partId(), t.partName(), t.keys()));
        AnimLayerMeta layer = h.hasLayer()
            ? new AnimLayerMeta(layerType(), mask(), h.fadeInSeconds(), h.fadeOutSeconds(), h.priority())
            : AnimLayerMeta.base();
        return new ParsedAnimClip(name(), h.fps(), h.duration(), h.loop(), tracks, layer);
    }

    // --- Marshalling -----------------------------------------------------------------------------

    private String getString(int field) {
        try {
            String s = CendaAnimClip.string((MemorySegment) CendaAnimClip.GET_STRING.invokeExact(handle(), field));
            return s == null ? "" : s;
        } catch (Throwable t) {
            throw CendaAnimClip.rethrow(t);
        }
    }

    private void setString(int field, String value) {
        try (Arena arena = Arena.ofConfined()) {
            check((int) CendaAnimClip.SET_STRING.invokeExact(handle(), field, cstr(arena, value)), "set");
        } catch (Throwable t) {
            throw CendaAnimClip.rethrow(t);
        }
    }

    private static ParsedKeyframe key(MemorySegment s, long o) {
        int easing = s.get(CendaAnimClip.I32, o + 40);
        Easing[] all = Easing.values();
        return new ParsedKeyframe(s.get(CendaAnimClip.F32, o),
            new Vector3f(s.get(CendaAnimClip.F32, o + 4), s.get(CendaAnimClip.F32, o + 8), s.get(CendaAnimClip.F32, o + 12)),
            new Vector3f(s.get(CendaAnimClip.F32, o + 16), s.get(CendaAnimClip.F32, o + 20), s.get(CendaAnimClip.F32, o + 24)),
            new Vector3f(s.get(CendaAnimClip.F32, o + 28), s.get(CendaAnimClip.F32, o + 32), s.get(CendaAnimClip.F32, o + 36)),
            (easing >= 0 && easing < all.length ? all[easing] : Easing.LINEAR).name());
    }

    private static void putKey(MemorySegment s, long o, ParsedKeyframe k) {
        s.set(CendaAnimClip.F32, o, k.time());
        putVec(s, o + 4, k.position());
        putVec(s, o + 16, k.rotation());
        putVec(s, o + 28, k.scale());
        s.set(CendaAnimClip.I32, o + 40, Easing.fromString(k.easing()).ordinal());
        s.set(CendaAnimClip.I32, o + 44, 0);
    }

    private static void putVec(MemorySegment s, long o, Vector3f v) {
        s.set(CendaAnimClip.F32, o, v.x);
        s.set(CendaAnimClip.F32, o + 4, v.y);
        s.set(CendaAnimClip.F32, o + 8, v.z);
    }

    private static MemorySegment cstr(Arena arena, String s) {
        return s == null ? MemorySegment.NULL : arena.allocateFrom(s);
    }

    private static MemorySegment slot(Arena arena) {
        return arena.allocate(CendaAnimClip.ADDR);
    }

    private static String read(MemorySegment slot) {
        String s = CendaAnimClip.string(slot.get(CendaAnimClip.ADDR, 0));
        return s == null ? "" : s;
    }

    private static void check(int rc, String what) {
        if (rc != CendaAnimClip.OK) throw new IllegalArgumentException(what + ": " + CendaAnimClip.lastError());
    }
}
