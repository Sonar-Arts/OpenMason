package com.openmason.engine.animgraph;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.ref.Cleaner;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A compiled animation graph asset ({@code .omanimgraph}, {@code cg_graph}; OpenMason #157): parameters, clip slots,
 * masks and a tree of nodes, written against profile roles. Immutable and shareable between threads and
 * {@link AnimGraphInstance}s. The format and the codec are native ({@code omformat/omanimgraph.hpp} documents the
 * file); this class only reads the compiled tables.
 */
public final class AnimGraph implements AutoCloseable {

    private static final Cleaner CLEANER = Cleaner.create();

    /** Parameter types ({@code CG_PARAM_*}). */
    public enum ParamType { FLOAT, INT, BOOL, TRIGGER, VEC2 }

    /** Node types ({@code CG_NODE_*}). */
    public enum NodeType { CLIP, BLEND1D, BLEND2D, STATE_MACHINE, LAYER, SLOT }

    public record Param(int index, String name, ParamType type) {}

    /** A node: its index, id, type and parent node index (-1 for the root and unused nodes). */
    public record Node(int index, String id, NodeType type, int parent) {}

    /** A refused file: {@link #newerVersion()} when it is a newer major version. */
    public static final class GraphFormatException extends IOException {
        private final boolean newerVersion;

        GraphFormatException(String message, boolean newerVersion) {
            super(message);
            this.newerVersion = newerVersion;
        }

        public boolean newerVersion() {
            return newerVersion;
        }
    }

    private final State state;
    private final Cleaner.Cleanable cleanable;
    private final String name;
    private final String profile;
    private final List<Param> params;
    private final List<String> slots;
    private final List<Node> nodes;
    private final List<String> montageSlots;
    private final List<String> targets;
    private final int modifierCount;

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
                    CendaAnimGraph.GRAPH_DESTROY.invokeExact(h);
                } catch (Throwable ignored) {
                    // best effort
                }
            }
        }
    }

    private AnimGraph(MemorySegment handle) throws Throwable {
        this.state = new State(handle);
        this.cleanable = CLEANER.register(this, state);
        this.name = CendaAnimGraph.string((MemorySegment) CendaAnimGraph.GRAPH_NAME.invokeExact(handle));
        this.profile = CendaAnimGraph.string((MemorySegment) CendaAnimGraph.GRAPH_PROFILE.invokeExact(handle));
        List<Param> ps = new ArrayList<>();
        List<Node> ns = new ArrayList<>();
        List<String> ss = new ArrayList<>();
        List<String> ms = new ArrayList<>();
        List<String> ts = new ArrayList<>();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment strOut = arena.allocate(CendaAnimGraph.ADDR);
            MemorySegment i1 = arena.allocate(CendaAnimGraph.I32);
            MemorySegment i2 = arena.allocate(CendaAnimGraph.I32);
            int pc = (int) CendaAnimGraph.PARAM_COUNT.invokeExact(handle);
            for (int i = 0; i < pc; i++) {
                CendaAnimGraph.check((int) CendaAnimGraph.PARAM_GET.invokeExact(handle, i, strOut, i1), "param");
                ps.add(new Param(i, CendaAnimGraph.string(strOut.get(CendaAnimGraph.ADDR, 0)),
                    ParamType.values()[i1.get(CendaAnimGraph.I32, 0)]));
            }
            int sc = (int) CendaAnimGraph.SLOT_COUNT.invokeExact(handle);
            for (int i = 0; i < sc; i++) ss.add(CendaAnimGraph.string((MemorySegment) CendaAnimGraph.SLOT_NAME.invokeExact(handle, i)));
            int nc = (int) CendaAnimGraph.NODE_COUNT.invokeExact(handle);
            for (int i = 0; i < nc; i++) {
                CendaAnimGraph.check((int) CendaAnimGraph.NODE_GET.invokeExact(handle, i, strOut, i1, i2), "node");
                ns.add(new Node(i, CendaAnimGraph.string(strOut.get(CendaAnimGraph.ADDR, 0)),
                    NodeType.values()[i1.get(CendaAnimGraph.I32, 0)], i2.get(CendaAnimGraph.I32, 0)));
            }
            int tc = (int) CendaAnimGraph.TARGET_COUNT.invokeExact(handle);
            for (int i = 0; i < tc; i++) ts.add(CendaAnimGraph.string((MemorySegment) CendaAnimGraph.TARGET_NAME.invokeExact(handle, i)));
            this.modifierCount = (int) CendaAnimGraph.MODIFIER_COUNT.invokeExact(handle);
            int mc = (int) CendaAnimGraph.MONTAGE_SLOT_COUNT.invokeExact(handle);
            for (int i = 0; i < mc; i++) {
                ms.add(CendaAnimGraph.string((MemorySegment) CendaAnimGraph.MONTAGE_SLOT_NAME.invokeExact(handle, i)));
            }
        }
        this.params = Collections.unmodifiableList(ps);
        this.slots = Collections.unmodifiableList(ss);
        this.nodes = Collections.unmodifiableList(ns);
        this.montageSlots = Collections.unmodifiableList(ms);
        this.targets = Collections.unmodifiableList(ts);
    }

    /** Takes ownership of a compiled {@code cg_graph*} (from {@link AnimGraphDocument#compile()}). */
    static AnimGraph adopt(MemorySegment handle) {
        try {
            return new AnimGraph(handle);
        } catch (Throwable t) {
            try {
                CendaAnimGraph.GRAPH_DESTROY.invokeExact(handle);
            } catch (Throwable ignored) {
                // already failing
            }
            throw CendaAnimGraph.rethrow(t);
        }
    }

    /** Parses and compiles {@code .omanimgraph} JSON. */
    public static AnimGraph read(byte[] json) throws GraphFormatException {
        CendaAnimGraph.requireAvailable();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment text = arena.allocate(Math.max(1, json.length));
            MemorySegment.copy(json, 0, text, java.lang.foreign.ValueLayout.JAVA_BYTE, 0, json.length);
            MemorySegment rc = arena.allocate(CendaAnimGraph.I32);
            MemorySegment h = (MemorySegment) CendaAnimGraph.GRAPH_READ.invokeExact(text, (long) json.length, rc);
            if (h.equals(MemorySegment.NULL)) {
                int code = rc.get(CendaAnimGraph.I32, 0);
                throw new GraphFormatException(CendaAnimGraph.lastError(), code == CendaAnimGraph.ERR_VERSION);
            }
            return new AnimGraph(h);
        } catch (GraphFormatException | RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaAnimGraph.rethrow(t);
        }
    }

    public static AnimGraph read(String json) throws GraphFormatException {
        return read(json.getBytes(StandardCharsets.UTF_8));
    }

    public static AnimGraph read(Path file) throws IOException {
        return read(Files.readAllBytes(file));
    }

    /** The graph as {@code .omanimgraph} JSON (deterministic). */
    public byte[] write() {
        try {
            MemorySegment b = (MemorySegment) CendaAnimGraph.GRAPH_WRITE.invokeExact(handle());
            if (b.equals(MemorySegment.NULL)) throw new IllegalStateException("cg_graph_write: " + CendaAnimGraph.lastError());
            try {
                long size = (long) CendaAnimGraph.BYTES_SIZE.invokeExact(b);
                MemorySegment data = ((MemorySegment) CendaAnimGraph.BYTES_DATA.invokeExact(b)).reinterpret(size);
                return data.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
            } finally {
                CendaAnimGraph.BYTES_DESTROY.invokeExact(b);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw CendaAnimGraph.rethrow(t);
        }
    }

    MemorySegment handle() {
        MemorySegment h = state.handle;
        if (h == null) throw new IllegalStateException("animation graph closed");
        return h;
    }

    public String name() {
        return name;
    }

    /** The profile the graph is written against ("" = any). */
    public String profile() {
        return profile;
    }

    public List<Param> params() {
        return params;
    }

    /** Index of a parameter, -1 when none. */
    public int param(String paramName) {
        for (Param p : params) {
            if (p.name().equals(paramName)) return p.index();
        }
        return -1;
    }

    /** Clip slot names, in index order. */
    public List<String> slots() {
        return slots;
    }

    public int slot(String slotName) {
        return slots.indexOf(slotName);
    }

    public List<Node> nodes() {
        return nodes;
    }

    /** Index of the node with this id, -1 when none. */
    public int node(String id) {
        for (Node n : nodes) {
            if (n.id().equals(id)) return n.index();
        }
        return -1;
    }

    /** The states of a state-machine node, in file order (conduits included). */
    public List<String> states(int machineNode) {
        List<String> out = new ArrayList<>();
        try {
            int n = (int) CendaAnimGraph.STATE_COUNT.invokeExact(handle(), machineNode);
            for (int i = 0; i < n; i++) {
                out.add(CendaAnimGraph.string((MemorySegment) CendaAnimGraph.STATE_NAME.invokeExact(handle(), machineNode, i)));
            }
        } catch (Throwable t) {
            throw CendaAnimGraph.rethrow(t);
        }
        return out;
    }

    /** Montage slot names, in index order. */
    public List<String> montageSlots() {
        return montageSlots;
    }

    public int montageSlot(String slotName) {
        return montageSlots.indexOf(slotName);
    }

    /** Pose-modifier targets the host sets ({@link AnimGraphInstance#setTarget}), in index order. */
    public List<String> targets() {
        return targets;
    }

    public int target(String targetName) {
        return targets.indexOf(targetName);
    }

    /** Number of first-person view layers on the graph's root (#161; {@code "view": "firstPerson"}). */
    public int firstPersonLayerCount() {
        try {
            return (int) CendaAnimGraph.FIRST_PERSON_LAYER_COUNT.invokeExact(handle());
        } catch (Throwable t) {
            throw CendaAnimGraph.rethrow(t);
        }
    }

    /** Number of pose modifiers in the graph's stack (#158). */
    public int modifierCount() {
        return modifierCount;
    }

    @Override
    public void close() {
        cleanable.clean();
    }
}
