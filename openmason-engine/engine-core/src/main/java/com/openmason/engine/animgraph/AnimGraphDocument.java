package com.openmason.engine.animgraph;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.ref.Cleaner;
import java.lang.ref.Reference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * An editable {@code .omanimgraph} (OpenMason #164): a thin binding over the native {@code cg_doc_*}
 * ({@code cenda/anim_graph_edit.h}; the operations are documented in libomformat's {@code omanimgraph_edit.hpp}).
 * The document may be a draft while it is edited; every change is {@link #apply}: a JSON array of operations, ONE
 * undo step, all or nothing. {@link #view()} and {@link #problems()} are the editor's model as JSON; {@link #compile()}
 * gives a runtime {@link AnimGraph} once there are no errors. Thread-confined; freed by {@link #close()} or, as a
 * backstop, when unreachable.
 */
public final class AnimGraphDocument implements AutoCloseable {

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
                    CendaAnimGraph.DOC_DESTROY.invokeExact(h);
                } catch (Throwable ignored) {
                    // best effort at shutdown
                }
            }
        }
    }

    private final State state;
    private final Cleaner.Cleanable cleanable;

    private AnimGraphDocument(MemorySegment handle) {
        this.state = new State(handle);
        this.cleanable = CLEANER.register(this, state);
    }

    /** An empty document (no nodes); name / profile may be null. */
    public static AnimGraphDocument create(String name, String profile) {
        CendaAnimGraph.requireAvailable();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment n = name == null ? MemorySegment.NULL : arena.allocateFrom(name);
            MemorySegment p = profile == null ? MemorySegment.NULL : arena.allocateFrom(profile);
            MemorySegment h = (MemorySegment) CendaAnimGraph.DOC_CREATE.invokeExact(n, p);
            if (h.equals(MemorySegment.NULL)) throw new IllegalStateException("cg_doc_create: " + CendaAnimGraph.lastError());
            return new AnimGraphDocument(h);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaAnimGraph.rethrow(t);
        }
    }

    /** Reads an {@code .omanimgraph} (a draft included). */
    public static AnimGraphDocument read(String json) throws AnimGraph.GraphFormatException {
        CendaAnimGraph.requireAvailable();
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = arena.allocate(Math.max(1, bytes.length));
            MemorySegment.copy(bytes, 0, data, ValueLayout.JAVA_BYTE, 0, bytes.length);
            MemorySegment rc = arena.allocate(CendaAnimGraph.I32);
            MemorySegment h = (MemorySegment) CendaAnimGraph.DOC_READ.invokeExact(data, (long) bytes.length, rc);
            if (h.equals(MemorySegment.NULL)) {
                throw new AnimGraph.GraphFormatException(CendaAnimGraph.lastError(), rc.get(CendaAnimGraph.I32, 0) == -4);
            }
            return new AnimGraphDocument(h);
        } catch (AnimGraph.GraphFormatException | RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaAnimGraph.rethrow(t);
        }
    }

    public static AnimGraphDocument read(Path file) throws IOException {
        return read(Files.readString(file));
    }

    /** The document as {@code .omanimgraph} text (canonical: deterministic). */
    public String json() {
        return text(CendaAnimGraph.DOC_JSON);
    }

    /**
     * Applies operations as one undo step (mergeKey: consecutive applies with the same key fold into one step; null =
     * never). All or nothing.
     *
     * @return one result line per operation (JSON array text)
     * @throws IllegalArgumentException with "ops[i] (op): why" when an operation is refused (nothing changed)
     */
    public String apply(String opsJson, String label, String mergeKey) {
        byte[] bytes = opsJson.getBytes(StandardCharsets.UTF_8);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = arena.allocate(Math.max(1, bytes.length));
            MemorySegment.copy(bytes, 0, data, ValueLayout.JAVA_BYTE, 0, bytes.length);
            MemorySegment l = label == null ? MemorySegment.NULL : arena.allocateFrom(label);
            MemorySegment k = mergeKey == null ? MemorySegment.NULL : arena.allocateFrom(mergeKey);
            int rc = (int) CendaAnimGraph.DOC_APPLY.invokeExact(handle(), data, (long) bytes.length, l, k);
            if (rc < 0) throw new IllegalArgumentException(CendaAnimGraph.lastError());
            return CendaAnimGraph.string((MemorySegment) CendaAnimGraph.DOC_RESULTS.invokeExact(handle()));
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaAnimGraph.rethrow(t);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /** The editor's model (nodes with positions and inputs, machines, parameters, problems) as JSON. */
    public String view() {
        return text(CendaAnimGraph.DOC_VIEW);
    }

    /** Everything wrong, as a JSON array [{severity, node, subject, message}]. */
    public String problems() {
        return text(CendaAnimGraph.DOC_PROBLEMS);
    }

    /** True when there are no errors ({@link #compile()} succeeds). */
    public boolean valid() {
        return query(CendaAnimGraph.DOC_VALID) != 0;
    }

    /**
     * The runtime graph of the current document.
     *
     * @throws IllegalStateException while the document has errors (the first one)
     */
    public AnimGraph compile() {
        try {
            MemorySegment g = (MemorySegment) CendaAnimGraph.DOC_COMPILE.invokeExact(handle());
            if (g.equals(MemorySegment.NULL)) throw new IllegalStateException(CendaAnimGraph.lastError());
            return AnimGraph.adopt(g);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaAnimGraph.rethrow(t);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    public boolean undo() {
        return query(CendaAnimGraph.DOC_UNDO) == 0;
    }

    public boolean redo() {
        return query(CendaAnimGraph.DOC_REDO) == 0;
    }

    public boolean canUndo() {
        return query(CendaAnimGraph.DOC_CAN_UNDO) != 0;
    }

    public boolean canRedo() {
        return query(CendaAnimGraph.DOC_CAN_REDO) != 0;
    }

    public String undoLabel() {
        return label(0);
    }

    public String redoLabel() {
        return label(1);
    }

    public void seal() {
        query(CendaAnimGraph.DOC_SEAL);
    }

    /** Changes whenever the document changes (editor cache key). */
    public long revision() {
        try {
            return (long) CendaAnimGraph.DOC_REVISION.invokeExact(handle());
        } catch (Throwable t) {
            throw CendaAnimGraph.rethrow(t);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    public boolean dirty() {
        return query(CendaAnimGraph.DOC_DIRTY) != 0;
    }

    public void markSaved() {
        query(CendaAnimGraph.DOC_MARK_SAVED);
    }

    public int stepCount() {
        return query(CendaAnimGraph.DOC_STEP_COUNT);
    }

    /** Forgets the history; the current state becomes the base. */
    public void reset() {
        query(CendaAnimGraph.DOC_RESET);
    }

    /** Writes {@link #json()} to a file and marks the document saved. */
    public void save(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(file, json());
        markSaved();
    }

    @Override
    public void close() {
        cleanable.clean();
    }

    private MemorySegment handle() {
        MemorySegment h = state.handle;
        if (h == null) throw new IllegalStateException("AnimGraphDocument is closed");
        return h;
    }

    private String label(int which) {
        try {
            String s = CendaAnimGraph.string((MemorySegment) CendaAnimGraph.DOC_LABEL.invokeExact(handle(), which));
            return s == null ? "" : s;
        } catch (Throwable t) {
            throw CendaAnimGraph.rethrow(t);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    private String text(java.lang.invoke.MethodHandle mh) {
        try {
            String s = CendaAnimGraph.string((MemorySegment) mh.invokeExact(handle()));
            return s == null ? "" : s;
        } catch (Throwable t) {
            throw CendaAnimGraph.rethrow(t);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    private int query(java.lang.invoke.MethodHandle mh) {
        try {
            return (int) mh.invokeExact(handle());
        } catch (Throwable t) {
            throw CendaAnimGraph.rethrow(t);
        } finally {
            Reference.reachabilityFence(this);
        }
    }
}
