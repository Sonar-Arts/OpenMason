package com.openmason.engine.rig;

import com.openmason.engine.format.oma.AnimLayerMeta;
import com.openmason.engine.format.oma.Easing;
import com.openmason.engine.format.oma.ParsedAnimClip;
import com.openmason.engine.format.oma.ParsedAnimTrack;
import com.openmason.engine.format.oma.ParsedKeyframe;
import com.openmason.engine.format.oma.ParsedMorphTrack;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.ref.Cleaner;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The native twin ({@code cr_clip}) of a {@link ParsedAnimClip}. {@link #of} converts a clip once and
 * caches the result by clip identity for as long as the clip is reachable; the native clip is freed when
 * it no longer is. Immutable and thread-safe.
 */
public final class RigClip {

    private static final Cleaner CLEANER = Cleaner.create();
    private static final ConcurrentHashMap<IdentityKey, RigClip> CACHE = new ConcurrentHashMap<>();
    private static final ReferenceQueue<ParsedAnimClip> QUEUE = new ReferenceQueue<>();

    private final MemorySegment handle;
    private final int trackCount;

    private static final class IdentityKey extends WeakReference<ParsedAnimClip> {
        private final int hash;

        IdentityKey(ParsedAnimClip clip, ReferenceQueue<ParsedAnimClip> queue) {
            super(clip, queue);
            this.hash = System.identityHashCode(clip);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof IdentityKey other)) return false;
            Object mine = get();
            return mine != null && mine == other.get();
        }
    }

    private record Destroy(MemorySegment handle) implements Runnable {
        @Override
        public void run() {
            try {
                CendaRig.CLIP_DESTROY.invokeExact(handle);
            } catch (Throwable ignored) {
                // best effort
            }
        }
    }

    private RigClip(MemorySegment handle, int trackCount) {
        this.handle = handle;
        this.trackCount = trackCount;
        CLEANER.register(this, new Destroy(handle));
    }

    /** The native clip for {@code clip} (null for null), converted on first use. */
    public static RigClip of(ParsedAnimClip clip) {
        if (clip == null) return null;
        purge();
        IdentityKey probe = new IdentityKey(clip, null);
        RigClip cached = CACHE.get(probe);
        if (cached != null) return cached;
        RigClip created = convert(clip);
        RigClip raced = CACHE.putIfAbsent(new IdentityKey(clip, QUEUE), created);
        return raced != null ? raced : created;
    }

    private static void purge() {
        java.lang.ref.Reference<? extends ParsedAnimClip> ref;
        while ((ref = QUEUE.poll()) != null) {
            CACHE.remove(ref);
        }
    }

    /** Converts without caching (editors that rebuild clips constantly own the result). */
    public static RigClip convert(ParsedAnimClip clip) {
        CendaRig.requireAvailable();
        List<ParsedAnimTrack> tracks = clip.tracks();
        int keyCount = 0;
        for (ParsedAnimTrack t : tracks) keyCount += t.keyframes().size();
        try (Arena arena = Arena.ofConfined()) {
            AnimLayerMeta layer = clip.layer();
            MemorySegment desc = arena.allocate(CendaRig.CLIP_DESC_BYTES, 4);
            desc.set(CendaRig.F32, 0, clip.duration());
            desc.set(CendaRig.I32, 4, clip.loop() ? 1 : 0);
            desc.set(CendaRig.I32, 8, layer.type() == AnimLayerMeta.LayerType.OVERLAY ? 1 : 0);
            desc.set(CendaRig.I32, 12, layer.priority());
            desc.set(CendaRig.F32, 16, layer.fadeInSeconds());
            desc.set(CendaRig.F32, 20, layer.fadeOutSeconds());

            MemorySegment trackSeg = arena.allocate(Math.max(1, tracks.size()) * CendaRig.TRACK_BYTES, 4);
            MemorySegment keySeg = arena.allocate(Math.max(1, keyCount) * CendaRig.KEY_BYTES, 4);
            List<String> ids = new ArrayList<>(tracks.size());
            List<String> names = new ArrayList<>(tracks.size());
            int k = 0;
            for (int t = 0; t < tracks.size(); t++) {
                ParsedAnimTrack track = tracks.get(t);
                trackSeg.set(CendaRig.I32, t * CendaRig.TRACK_BYTES, k);
                trackSeg.set(CendaRig.I32, t * CendaRig.TRACK_BYTES + 4, track.keyframes().size());
                for (ParsedKeyframe kf : track.keyframes()) {
                    long o = k * CendaRig.KEY_BYTES;
                    keySeg.set(CendaRig.F32, o, kf.time());
                    keySeg.set(CendaRig.F32, o + 4, kf.position().x);
                    keySeg.set(CendaRig.F32, o + 8, kf.position().y);
                    keySeg.set(CendaRig.F32, o + 12, kf.position().z);
                    keySeg.set(CendaRig.F32, o + 16, kf.rotation().x);
                    keySeg.set(CendaRig.F32, o + 20, kf.rotation().y);
                    keySeg.set(CendaRig.F32, o + 24, kf.rotation().z);
                    keySeg.set(CendaRig.F32, o + 28, kf.scale().x);
                    keySeg.set(CendaRig.F32, o + 32, kf.scale().y);
                    keySeg.set(CendaRig.F32, o + 36, kf.scale().z);
                    // Easing ordinals equal CR_EASE_* (LINEAR, EASE_IN, EASE_OUT, EASE_IN_OUT, STEP).
                    keySeg.set(CendaRig.I32, o + 40, Easing.fromString(kf.easing()).ordinal());
                    keySeg.set(CendaRig.I32, o + 44, 0);
                    k++;
                }
                ids.add(track.partId());
                names.add(track.partName());
            }
            List<String> mask = layer.maskParts();
            MemorySegment handle = (MemorySegment) CendaRig.CLIP_CREATE.invokeExact(desc,
                trackSeg, tracks.size(), keySeg, keyCount,
                CendaRig.strings(arena, ids), CendaRig.strings(arena, names),
                CendaRig.strings(arena, mask), mask.size());
            if (handle.equals(MemorySegment.NULL)) {
                throw new IllegalArgumentException("cr_clip_create: " + CendaRig.lastError());
            }
            RigClip created = new RigClip(handle, tracks.size());
            setMorphTracks(arena, handle, clip.morphTracks());
            return created;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    /**
     * Gives a freshly created native clip its OMANIM 1.3 morph tracks ({@code cr_clip_set_morph_tracks}, OpenMason
     * #171) - before the clip is bound to any rig. No tracks: nothing (the clip samples exactly as before).
     */
    private static void setMorphTracks(Arena arena, MemorySegment handle, List<ParsedMorphTrack> morphs)
            throws Throwable {
        if (morphs == null || morphs.isEmpty()) return;
        int keyCount = 0;
        for (ParsedMorphTrack m : morphs) keyCount += m.keys().size();
        MemorySegment offsets = arena.allocate(4L * (morphs.size() + 1), 4);
        MemorySegment keys = arena.allocate(CendaRig.MORPH_KEY_BYTES * Math.max(1, keyCount), 4);
        List<String> names = new ArrayList<>(morphs.size());
        int k = 0;
        for (int t = 0; t < morphs.size(); t++) {
            ParsedMorphTrack m = morphs.get(t);
            names.add(m.name());
            offsets.setAtIndex(CendaRig.I32, t, k);
            for (ParsedMorphTrack.Key key : m.keys()) {
                long o = CendaRig.MORPH_KEY_BYTES * k++;
                keys.set(CendaRig.F32, o, key.time());
                keys.set(CendaRig.F32, o + 4, key.value());
                keys.set(CendaRig.F32, o + 8, key.inTangent());
                keys.set(CendaRig.F32, o + 12, key.outTangent());
                keys.set(CendaRig.I32, o + 16, key.interp());
            }
        }
        offsets.setAtIndex(CendaRig.I32, morphs.size(), k);
        int rc = (int) CendaRig.CLIP_SET_MORPH_TRACKS.invokeExact(handle, morphs.size(),
            CendaRig.strings(arena, names), offsets, keys);
        if (rc != CendaRig.OK) throw new IllegalArgumentException("cr_clip_set_morph_tracks: " + CendaRig.lastError());
    }

    /** Number of morph tracks the native clip carries (OMANIM 1.3, OpenMason #171). */
    public int morphTrackCount() {
        try {
            return (int) CendaRig.CLIP_MORPH_TRACK_COUNT.invokeExact(handle);
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    MemorySegment handle() {
        return handle;
    }

    public int trackCount() {
        return trackCount;
    }

    /** The clip's track for a rig joint, -1 when none drives it. */
    public int trackFor(Rig rig, int joint) {
        try {
            return (int) CendaRig.CLIP_TRACK_FOR.invokeExact(handle, rig.handle(), joint);
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    /** The overlay fade envelope at {@code elapsed} unwrapped seconds, in [0,1]. */
    public float weightAt(float elapsed) {
        try {
            return (float) CendaRig.CLIP_WEIGHT.invokeExact(handle, elapsed);
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    /** Wraps (loop) or clamps elapsed seconds into a clip's range — the rule posing applies. */
    public static float wrapTime(float elapsed, float duration, boolean loop) {
        CendaRig.requireAvailable();
        try {
            return (float) CendaRig.WRAP_TIME.invokeExact(elapsed, duration, loop ? 1 : 0);
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }

    private static final ThreadLocal<MemorySegment> ROOT_SCRATCH =
        ThreadLocal.withInitial(() -> Arena.ofAuto().allocate(CendaRig.ROOT_MOTION_BYTES, 4));

    /**
     * The root motion of {@code joint}'s track over {@code [prevTime, prevTime + delta]} elapsed seconds on
     * {@code axes} ({@link PoseProgram#ROOT_X} ...): {@code out[0..2]} translation in the root's frame at the
     * start of the step (its yaw removed when {@link PoseProgram#ROOT_YAW} is extracted), {@code out[3]} the
     * yaw turn in degrees. Looping clips wrap and count whole cycles. A joint the clip does not key moves nothing.
     */
    public float[] rootMotion(Rig rig, int joint, int axes, float prevTime, float delta, float[] out) {
        MemorySegment seg = ROOT_SCRATCH.get();
        try {
            int rc = (int) CendaRig.CLIP_ROOT_MOTION.invokeExact(handle, rig.handle(), joint, axes, prevTime, delta, seg);
            if (rc != CendaRig.OK) throw new IllegalArgumentException("cr_clip_root_motion: " + CendaRig.lastError());
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
        MemorySegment.copy(seg, CendaRig.F32, 0, out, 0, 4);
        return out;
    }

    /**
     * Samples one track at a wrapped time into {@code out}: pos[0..2], rotDeg[3..5], scale[6..8].
     */
    public void sampleTrack(int track, float time, float[] out) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment p = arena.allocate(36, 4);
            int rc = (int) CendaRig.CLIP_SAMPLE_TRACK.invokeExact(handle, track, time,
                p, p.asSlice(12), p.asSlice(24));
            if (rc != CendaRig.OK) throw new IllegalArgumentException("cr_clip_sample_track: " + CendaRig.lastError());
            for (int i = 0; i < 9; i++) out[i] = p.getAtIndex(CendaRig.F32, i);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaRig.rethrow(t);
        }
    }
}
