package com.openmason.main.systems.skeleton;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Snapshot undo/redo for {@link SkinStore} (one entry per brush stroke / weight operation), shared by
 * the rigging pane and the MCP skin tools. Same shape as {@link BoneCommandHistory}.
 */
public final class SkinCommandHistory {

    private static final int MAX_HISTORY_SIZE = 50;

    private record Entry(SkinStore.Snapshot before, SkinStore.Snapshot after, String description) {}

    private final SkinStore store;
    private final Deque<Entry> undoStack = new ArrayDeque<>();
    private final Deque<Entry> redoStack = new ArrayDeque<>();
    private Runnable onChange = () -> { };

    public SkinCommandHistory(SkinStore store) {
        this.store = store;
    }

    /**
     * Called after every recorded edit, undo and redo (OM #165: weight edits mark the model dirty - the skin is
     * saved in the .omo).
     */
    public void setOnChange(Runnable onChange) {
        this.onChange = onChange == null ? () -> { } : onChange;
    }

    /** Runs {@code mutation} and records it as one undo step (skipped when nothing changed). */
    public void record(String description, Runnable mutation) {
        SkinStore.Snapshot before = store.snapshot();
        mutation.run();
        push(before, store.snapshot(), description);
    }

    public void push(SkinStore.Snapshot before, SkinStore.Snapshot after, String description) {
        if (before == null || after == null || SkinStore.sameSnapshot(before, after)) return;
        undoStack.push(new Entry(before, after, description));
        while (undoStack.size() > MAX_HISTORY_SIZE) undoStack.removeLast();
        redoStack.clear();
        onChange.run();
    }

    public boolean undo() {
        Entry e = undoStack.poll();
        if (e == null) return false;
        store.restore(e.before());
        redoStack.push(e);
        onChange.run();
        return true;
    }

    public boolean redo() {
        Entry e = redoStack.poll();
        if (e == null) return false;
        store.restore(e.after());
        undoStack.push(e);
        onChange.run();
        return true;
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    public String undoDescription() {
        Entry e = undoStack.peek();
        return e == null ? null : e.description();
    }

    public void clear() {
        undoStack.clear();
        redoStack.clear();
    }
}
