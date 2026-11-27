package com.openmason.main.systems.skeleton.vertexdata;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Snapshot undo/redo over the {@link ShapeKeyStore} and the {@link VertexGroupStore} together (one entry per edit),
 * shared by the rigging pane's Shape Keys / Vertex Groups sections and the {@code morph_*} / {@code vgroup_*} MCP
 * tools (undo domain {@code morph}). Same shape as {@code SkinCommandHistory}; snapshots are the stores' immutable
 * value lists, so an entry costs two list references.
 */
public final class VertexDataHistory {

    private static final int MAX_HISTORY_SIZE = 100;

    /** Both stores at one moment. */
    public record Snapshot(ShapeKeyStore.Snapshot shapes, VertexGroupStore.Snapshot groups) {}

    private record Entry(Snapshot before, Snapshot after, String description) {}

    private final ShapeKeyStore shapes;
    private final VertexGroupStore groups;
    private final Deque<Entry> undoStack = new ArrayDeque<>();
    private final Deque<Entry> redoStack = new ArrayDeque<>();
    private Runnable onChange = () -> { };

    public VertexDataHistory(ShapeKeyStore shapes, VertexGroupStore groups) {
        this.shapes = shapes;
        this.groups = groups;
    }

    /** Called after every recorded edit, undo and redo (the data is saved in the .omo: the host marks it dirty). */
    public void setOnChange(Runnable onChange) {
        this.onChange = onChange == null ? () -> { } : onChange;
    }

    public Snapshot snapshot() {
        return new Snapshot(shapes.snapshot(), groups.snapshot());
    }

    /**
     * Runs {@code mutation} as one undo step (skipped when nothing changed). A mutation that throws leaves both
     * stores as they were (all or nothing) and rethrows.
     */
    public void record(String description, Runnable mutation) {
        Snapshot before = snapshot();
        try {
            mutation.run();
        } catch (RuntimeException e) {
            restoreQuietly(before);
            throw e;
        }
        push(before, snapshot(), description);
    }

    public void push(Snapshot before, Snapshot after, String description) {
        if (before == null || after == null || before.equals(after)) return;
        undoStack.push(new Entry(before, after, description));
        while (undoStack.size() > MAX_HISTORY_SIZE) undoStack.removeLast();
        redoStack.clear();
        onChange.run();
    }

    public boolean undo() {
        Entry e = undoStack.poll();
        if (e == null) return false;
        apply(e.before());
        redoStack.push(e);
        onChange.run();
        return true;
    }

    public boolean redo() {
        Entry e = redoStack.poll();
        if (e == null) return false;
        apply(e.after());
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

    public String redoDescription() {
        Entry e = redoStack.peek();
        return e == null ? null : e.description();
    }

    public void clear() {
        undoStack.clear();
        redoStack.clear();
    }

    private void apply(Snapshot s) {
        shapes.restore(s.shapes());
        groups.restore(s.groups());
    }

    private void restoreQuietly(Snapshot s) {
        if (!shapes.snapshot().equals(s.shapes())) shapes.restore(s.shapes());
        if (!groups.snapshot().equals(s.groups())) groups.restore(s.groups());
    }
}
