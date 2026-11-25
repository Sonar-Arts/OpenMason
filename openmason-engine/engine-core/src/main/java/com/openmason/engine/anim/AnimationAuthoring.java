package com.openmason.engine.anim;

import com.openmason.engine.character.CharacterDefinition;
import com.openmason.engine.character.Retargeter;
import com.openmason.engine.rig.Rig;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.ref.Cleaner;
import java.lang.ref.Reference;
import java.nio.charset.StandardCharsets;

/**
 * An animation authoring session (OpenMason #165): a thin binding over the native {@code cm_author_*}
 * ({@code cenda/anim_author.h}; the authoring data and every operation are documented in libomformat's
 * {@code omanim_author.hpp}). One clip for one rig: layers (override / additive, keyable weight, mask, mute, solo,
 * passthrough) composed by the rig's pose operations - the runtime's code - and control-rig effectors solved by the
 * modifier stack, always baked into the clip's joint tracks. Every change is {@link #apply}: ONE undo step, all or
 * nothing. Thread-confined; the rig and character must outlive it.
 */
public final class AnimationAuthoring implements AutoCloseable {

    /** {@link #pose} stages. */
    public static final int STAGE_LAYERS = 0;
    public static final int STAGE_FINAL = 1;

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
                    CendaAnimClip.AU_DESTROY.invokeExact(h);
                } catch (Throwable ignored) {
                    // best effort at shutdown
                }
            }
        }
    }

    private final State state;
    private final Cleaner.Cleanable cleanable;
    private final Rig rig;
    private final CharacterDefinition character;

    private AnimationAuthoring(MemorySegment handle, Rig rig, CharacterDefinition character) {
        this.state = new State(handle);
        this.cleanable = CLEANER.register(this, state);
        this.rig = rig;
        this.character = character;
    }

    /** A session over a copy of {@code clip} (its authoring data read when present); character may be null. */
    public static AnimationAuthoring create(AnimClipDocument clip, Rig rig, CharacterDefinition character) {
        CendaAnimClip.requireAvailable();
        try {
            MemorySegment c = character == null ? MemorySegment.NULL : character.nativeHandle();
            MemorySegment h = (MemorySegment) CendaAnimClip.AU_CREATE.invokeExact(clip.nativeHandle(), rig.nativeHandle(), c);
            if (h.equals(MemorySegment.NULL)) throw new IllegalArgumentException("cm_author_create: " + CendaAnimClip.lastError());
            return new AnimationAuthoring(h, rig, character);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaAnimClip.rethrow(t);
        } finally {
            Reference.reachabilityFence(clip);
        }
    }

    public Rig rig() {
        return rig;
    }

    /** The .omanim to save (authoring data included when the session needs it). */
    public byte[] write() {
        try {
            MemorySegment b = (MemorySegment) CendaAnimClip.AU_WRITE.invokeExact(handle());
            if (b.equals(MemorySegment.NULL)) throw new IllegalStateException("cm_author_write: " + CendaAnimClip.lastError());
            try {
                long size = (long) CendaAnimClip.BYTES_SIZE.invokeExact(b);
                MemorySegment data = (MemorySegment) CendaAnimClip.BYTES_DATA.invokeExact(b);
                return data.reinterpret(size).toArray(ValueLayout.JAVA_BYTE);
            } finally {
                CendaAnimClip.BYTES_DESTROY.invokeExact(b);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaAnimClip.rethrow(t);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /** A copy of the playable clip (baked tracks + clip data); the caller closes it. */
    public AnimClipDocument playableClip() {
        try {
            MemorySegment c = (MemorySegment) CendaAnimClip.AU_CLIP.invokeExact(handle());
            if (c.equals(MemorySegment.NULL)) throw new IllegalStateException("cm_author_clip: " + CendaAnimClip.lastError());
            MemorySegment copy = (MemorySegment) CendaAnimClip.CLONE.invokeExact(c);
            return AnimClipDocument.adopt(copy);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaAnimClip.rethrow(t);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Applies operations as one undo step (mergeKey folds consecutive applies; null = never). All or nothing.
     *
     * @return the per-operation result lines (JSON array text)
     * @throws IllegalArgumentException "ops[i] (op): why" when refused (nothing changed)
     */
    public String apply(String opsJson, String label, String mergeKey) {
        byte[] bytes = opsJson.getBytes(StandardCharsets.UTF_8);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = arena.allocate(Math.max(1, bytes.length));
            MemorySegment.copy(bytes, 0, data, ValueLayout.JAVA_BYTE, 0, bytes.length);
            MemorySegment l = label == null ? MemorySegment.NULL : arena.allocateFrom(label);
            MemorySegment k = mergeKey == null ? MemorySegment.NULL : arena.allocateFrom(mergeKey);
            int rc = (int) CendaAnimClip.AU_APPLY.invokeExact(handle(), data, (long) bytes.length, l, k);
            if (rc < 0) throw new IllegalArgumentException(CendaAnimClip.lastError());
            return CendaAnimClip.string((MemorySegment) CendaAnimClip.AU_RESULTS.invokeExact(handle()));
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaAnimClip.rethrow(t);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /** The editor's model (header, layers with tracks and key times, effectors, notifies, curves, markers...) as JSON. */
    public String view() {
        return text((MemorySegment h) -> (MemorySegment) CendaAnimClip.AU_VIEW.invokeExact(h));
    }

    /** Control-rig handles at {@code time} as JSON (target, weight, pole, where FK puts the chain end). */
    public String handles(float time) {
        return text((MemorySegment h) -> (MemorySegment) CendaAnimClip.AU_HANDLES.invokeExact(h, time));
    }

    /** A layer's own value of a joint at {@code time}: pos, rot degrees, scale (the rest pose where it has no track). */
    public float[] layerValue(int layer, int joint, float time) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(ValueLayout.JAVA_FLOAT, 9);
            int rc = (int) CendaAnimClip.AU_LAYER_VALUE.invokeExact(handle(), layer, joint, time, seg);
            if (rc != 0) throw new IllegalArgumentException("layerValue: " + CendaAnimClip.lastError());
            return seg.toArray(ValueLayout.JAVA_FLOAT);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaAnimClip.rethrow(t);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Root motion preview: how far the clip's root-motion joint moved since time 0 on its axes - dx, dy, dz (model
     * space) and yaw degrees; zeros without root motion. Subtract it to show the clip in place.
     */
    public float[] rootMotion(float time) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(ValueLayout.JAVA_FLOAT, 4);
            int rc = (int) CendaAnimClip.AU_ROOT_MOTION.invokeExact(handle(), time, seg);
            if (rc != 0) throw new IllegalStateException("cm_author_root_motion: " + CendaAnimClip.lastError());
            return seg.toArray(ValueLayout.JAVA_FLOAT);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaAnimClip.rethrow(t);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /** The pose at {@code time}: joint_count x 9 local floats (pos, rot degrees, scale). */
    public float[] pose(float time, int stage, float[] out) {
        int n = rig.jointCount() * 9;
        float[] dst = out != null && out.length >= n ? out : new float[n];
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(ValueLayout.JAVA_FLOAT, Math.max(1, n));
            int rc = (int) CendaAnimClip.AU_POSE.invokeExact(handle(), time, stage, seg);
            if (rc != 0) throw new IllegalStateException("cm_author_pose: " + CendaAnimClip.lastError());
            MemorySegment.copy(seg, ValueLayout.JAVA_FLOAT, 0, dst, 0, n);
            return dst;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaAnimClip.rethrow(t);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /** Bakes a retargeted copy of {@code source} (#160) into a layer (created when missing): one undo step. */
    public void importRetargeted(AnimClipDocument source, Retargeter retargeter, float fps, String layer) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment l = layer == null ? MemorySegment.NULL : arena.allocateFrom(layer);
            int rc = (int) CendaAnimClip.AU_IMPORT_RETARGET.invokeExact(handle(), source.nativeHandle(), retargeter.nativeHandle(), fps, l);
            if (rc != 0) throw new IllegalArgumentException("importRetargeted: " + CendaAnimClip.lastError());
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaAnimClip.rethrow(t);
        } finally {
            Reference.reachabilityFence(source);
            Reference.reachabilityFence(retargeter);
        }
    }

    public boolean undo() {
        return query(CendaAnimClip.AU_UNDO) == 0;
    }

    public boolean redo() {
        return query(CendaAnimClip.AU_REDO) == 0;
    }

    public boolean canUndo() {
        return query(CendaAnimClip.AU_CAN_UNDO) != 0;
    }

    public boolean canRedo() {
        return query(CendaAnimClip.AU_CAN_REDO) != 0;
    }

    public String undoLabel() {
        return label(0);
    }

    public String redoLabel() {
        return label(1);
    }

    public void seal() {
        query(CendaAnimClip.AU_SEAL);
    }

    public long revision() {
        try {
            return (long) CendaAnimClip.AU_REVISION.invokeExact(handle());
        } catch (Throwable t) {
            throw CendaAnimClip.rethrow(t);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    public boolean dirty() {
        return query(CendaAnimClip.AU_DIRTY) != 0;
    }

    public void markSaved() {
        query(CendaAnimClip.AU_MARK_SAVED);
    }

    public int stepCount() {
        return query(CendaAnimClip.AU_STEP_COUNT);
    }

    @Override
    public void close() {
        cleanable.clean();
        Reference.reachabilityFence(rig);
        Reference.reachabilityFence(character);
    }

    private MemorySegment handle() {
        MemorySegment h = state.handle;
        if (h == null) throw new IllegalStateException("AnimationAuthoring is closed");
        return h;
    }

    @FunctionalInterface
    private interface TextCall {
        MemorySegment call(MemorySegment handle) throws Throwable;
    }

    private String text(TextCall call) {
        try {
            String s = CendaAnimClip.string(call.call(handle()));
            return s == null ? "" : s;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaAnimClip.rethrow(t);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    private String label(int which) {
        try {
            String s = CendaAnimClip.string((MemorySegment) CendaAnimClip.AU_LABEL.invokeExact(handle(), which));
            return s == null ? "" : s;
        } catch (Throwable t) {
            throw CendaAnimClip.rethrow(t);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    private int query(java.lang.invoke.MethodHandle mh) {
        try {
            return (int) mh.invokeExact(handle());
        } catch (Throwable t) {
            throw CendaAnimClip.rethrow(t);
        } finally {
            Reference.reachabilityFence(this);
        }
    }
}
