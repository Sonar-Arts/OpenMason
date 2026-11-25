package com.openmason.engine.character;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.ref.Cleaner;
import java.lang.ref.Reference;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The undo history of one {@link CharacterDefinition} (OpenMason #163): a thin binding over the native
 * {@code cc_charedit_*} ({@code cenda/character_edit.h}). Edits are ordinary definition setters run inside
 * {@link #begin}/{@link #commit} (or {@link #edit}); the outermost commit records ONE undo step when the
 * definition changed. A merge key folds consecutive commits with the same key into one step (a drag) until
 * {@link #seal()}. {@link #cancel()} puts back the state the outermost group started from.
 *
 * <p>The session keeps its definition reachable; close the session before the definition. Thread-confined like
 * the definition.
 */
public final class CharacterEditSession implements AutoCloseable {

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
                    CendaCharacter.ED_DESTROY.invokeExact(h);
                } catch (Throwable ignored) {
                    // best effort at shutdown
                }
            }
        }
    }

    private final CharacterDefinition definition;
    private final State state;
    private final Cleaner.Cleanable cleanable;

    private CharacterEditSession(CharacterDefinition definition, MemorySegment handle) {
        this.definition = definition;
        this.state = new State(handle);
        this.cleanable = CLEANER.register(this, state);
    }

    /** A session over the definition; its current state is the base (not dirty). maxSteps <= 0 = 200. */
    public static CharacterEditSession create(CharacterDefinition definition, int maxSteps) {
        Objects.requireNonNull(definition, "definition");
        CendaCharacter.requireAvailable();
        try {
            MemorySegment h = (MemorySegment) CendaCharacter.ED_CREATE.invokeExact(definition.handle(), maxSteps);
            if (h.equals(MemorySegment.NULL)) throw new IllegalStateException("cc_charedit_create: " + CendaCharacter.lastError());
            return new CharacterEditSession(definition, h);
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    public CharacterDefinition definition() {
        return definition;
    }

    /** Opens a group; mergeKey null or empty never merges. */
    public void begin(String label, String mergeKey) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment l = label == null ? MemorySegment.NULL : arena.allocateFrom(label);
            MemorySegment k = mergeKey == null ? MemorySegment.NULL : arena.allocateFrom(mergeKey);
            int rc = (int) CendaCharacter.ED_BEGIN.invokeExact(handle(), l, k);
            if (rc < 0) throw new IllegalStateException("begin: " + CendaCharacter.lastError());
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        }
    }

    /** What a commit did. */
    public enum Commit { NOTHING, NEW_STEP, MERGED }

    /** Closes a group; an inner group always reports {@link Commit#NOTHING}. */
    public Commit commit() {
        int rc = call(CendaCharacter.ED_COMMIT, "commit");
        return rc == 1 ? Commit.NEW_STEP : rc == 2 ? Commit.MERGED : Commit.NOTHING;
    }

    /** Closes every open group and restores the state the outermost one started from; true when that undid something. */
    public boolean cancel() {
        return call(CendaCharacter.ED_CANCEL, "cancel") == 1;
    }

    /**
     * Runs an edit as one group: committed when it returns, cancelled (all-or-nothing) when it throws.
     */
    public <T> T edit(String label, String mergeKey, Supplier<T> edit) {
        begin(label, mergeKey);
        T result;
        try {
            result = edit.get();
        } catch (RuntimeException | Error e) {
            if (depth() > 0) cancel();
            throw e;
        }
        commit();
        return result;
    }

    /** {@link #edit(String, String, Supplier)} without a result. */
    public void edit(String label, String mergeKey, Runnable edit) {
        edit(label, mergeKey, () -> {
            edit.run();
            return null;
        });
    }

    public void seal() {
        call(CendaCharacter.ED_SEAL, "seal");
    }

    /** False when there is nothing to undo. */
    public boolean undo() {
        return undoRedo(true);
    }

    /** False when there is nothing to redo. */
    public boolean redo() {
        return undoRedo(false);
    }

    private boolean undoRedo(boolean undo) {
        try {
            int rc = undo ? (int) CendaCharacter.ED_UNDO.invokeExact(handle()) : (int) CendaCharacter.ED_REDO.invokeExact(handle());
            if (rc == CendaCharacter.ERR_RANGE) return false;
            if (rc < 0) throw new IllegalStateException((undo ? "undo: " : "redo: ") + CendaCharacter.lastError());
            return true;
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    public boolean canUndo() {
        return query(CendaCharacter.ED_CAN_UNDO) != 0;
    }

    public boolean canRedo() {
        return query(CendaCharacter.ED_CAN_REDO) != 0;
    }

    /** The label of the step undo would take, "" when none. */
    public String undoLabel() {
        return label(0);
    }

    /** The label of the step redo would take, "" when none. */
    public String redoLabel() {
        return label(1);
    }

    private String label(int which) {
        try {
            String s = CendaCharacter.string((MemorySegment) CendaCharacter.ED_LABEL.invokeExact(handle(), which));
            return s == null ? "" : s;
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /** Changes whenever the definition's state changes through the session (cache key for editors). */
    public long revision() {
        try {
            return (long) CendaCharacter.ED_REVISION.invokeExact(handle());
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /** True when the state differs from the one last marked saved. */
    public boolean dirty() {
        return query(CendaCharacter.ED_DIRTY) != 0;
    }

    public void markSaved() {
        call(CendaCharacter.ED_MARK_SAVED, "markSaved");
    }

    public int depth() {
        return query(CendaCharacter.ED_DEPTH);
    }

    public int stepCount() {
        return query(CendaCharacter.ED_STEP_COUNT);
    }

    /** Forgets the history; the current state becomes the base. */
    public void reset() {
        call(CendaCharacter.ED_RESET, "reset");
    }

    @Override
    public void close() {
        cleanable.clean();
        Reference.reachabilityFence(definition);
    }

    MemorySegment handle() {
        MemorySegment h = state.handle;
        if (h == null) throw new IllegalStateException("CharacterEditSession is closed");
        return h;
    }

    private int query(java.lang.invoke.MethodHandle mh) {
        try {
            return (int) mh.invokeExact(handle());
        } catch (Throwable t) {
            throw CendaCharacter.rethrow(t);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    private int call(java.lang.invoke.MethodHandle mh, String what) {
        int rc = query(mh);
        if (rc < 0) throw new IllegalStateException(what + ": " + CendaCharacter.lastError());
        return rc;
    }
}
