package com.openmason.main.systems.mcp;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.modeling.parts.PartMeshRebuilder;
import com.openmason.main.systems.ViewportController;
import com.openmason.main.systems.services.EditorServices;
import com.openmason.main.systems.skeleton.vertexdata.BasisRestore;
import com.openmason.main.systems.skeleton.vertexdata.ShapeKeyStore;
import com.openmason.main.systems.skeleton.vertexdata.VertexDataAuthoring;
import com.openmason.main.systems.skeleton.vertexdata.VertexGroupStore;
import com.openmason.main.systems.threading.MainThreadExecutor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Thread-safe facade for the {@code morph_*} (shape keys, #171) and {@code vgroup_*} (vertex groups, #172) MCP
 * tools over the viewport's {@link VertexDataAuthoring}. Every call marshals to the main thread (the
 * {@link SkinEditingService} contract); every edit is one undo step of the {@code morph} domain, shared with the
 * rigging pane. Parts are addressed by id or name; vertex indices are part-local; deltas are MODEL space.
 */
public final class VertexDataEditingService {

    private static final long DEFAULT_TIMEOUT_MS = 10_000;
    /** Most rows a list call returns. */
    static final int MAX_ROWS = 512;

    /** What the tools edit; {@code viewport} may be null (headless: no basis restore, no selection). */
    public record Context(VertexDataAuthoring data, ViewportController viewport) {}

    private final Supplier<Context> source;

    public VertexDataEditingService(EditorServices editorServices) {
        this(() -> {
            ViewportController vp = editorServices == null ? null : editorServices.getViewport3D();
            return vp == null ? null : new Context(vp.getVertexData(), vp);
        });
    }

    /** Over an explicit context (headless tests). */
    VertexDataEditingService(Supplier<Context> source) {
        this.source = source;
    }

    // ===================== Shape keys =====================

    public Map<String, Object> morphList(String key, String part) {
        return onMain(() -> {
            VertexDataAuthoring d = require().data();
            ShapeKeyStore store = d.shapeKeys();
            Map<String, Object> out = new LinkedHashMap<>();
            if (key != null) {
                OMOFormat.MorphData m = requireKey(store, key);
                out.put("key", keyRow(d, m));
                if (part != null) {
                    String partId = part(d, part);
                    List<Map<String, Object>> rows = new ArrayList<>();
                    for (OMOFormat.MorphPart mp : m.parts()) {
                        if (!mp.partId().equals(partId)) continue;
                        int[] v = mp.vertices();
                        float[] dl = mp.deltas();
                        for (int r = 0; r < Math.min(v.length, MAX_ROWS); r++) {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("v", v[r]);
                            row.put("d", round(dl[r * 3], dl[r * 3 + 1], dl[r * 3 + 2]));
                            rows.add(row);
                        }
                        if (v.length > MAX_ROWS) out.put("truncated", true);
                    }
                    out.put("rows", rows);
                }
                return out;
            }
            List<Map<String, Object>> keys = new ArrayList<>();
            for (OMOFormat.MorphData m : store.morphs()) keys.add(keyRow(d, m));
            out.put("keys", keys);
            out.put("capturing", d.isCapturing());
            return out;
        });
    }

    private static Map<String, Object> keyRow(VertexDataAuthoring d, OMOFormat.MorphData m) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("name", m.name());
        r.put("default", m.defaultWeight());
        r.put("min", m.min());
        r.put("max", m.max());
        r.put("value", d.preview().value(m));
        r.put("vertices", ShapeKeyStore.rowCount(m));
        List<String> parts = new ArrayList<>();
        for (OMOFormat.MorphPart p : m.parts()) parts.add(d.partName(p.partId()));
        r.put("parts", parts);
        return r;
    }

    /** One {@code morph_set} call: members apply in the order create, rename, params, index, remove; value is preview. */
    public record MorphEdit(String name, boolean create, boolean remove, String rename, Float value, Float defaultWeight,
                            Float min, Float max, Integer index) {}

    public McpAck morphSet(MorphEdit e) {
        return onMain(() -> {
            VertexDataAuthoring d = require().data();
            ShapeKeyStore store = d.shapeKeys();
            if (e.name() == null || e.name().isBlank()) throw new IllegalArgumentException("name is required");
            boolean edits = e.create() || e.remove() || e.rename() != null || e.defaultWeight() != null
                    || e.min() != null || e.max() != null || e.index() != null;
            if (!e.create() && store.indexOf(e.name()) < 0) {
                throw McpErrors.unknownEntity("shape key", e.name(),
                        store.morphs().stream().map(OMOFormat.MorphData::name).toList(), "morph_list");
            }
            String[] name = {e.name().trim()};
            if (edits) {
                String old = e.create() ? null : store.get(e.name()).name();
                String to = e.rename() != null && !e.remove() ? e.rename() : null;
                d.edit("Shape Key", () -> {
                    if (e.create()) {
                        float lo = e.min() != null ? e.min() : 0f, hi = e.max() != null ? e.max() : 1f;
                        float def = e.defaultWeight() != null ? e.defaultWeight() : Math.max(lo, Math.min(hi, 0f));
                        int i = store.add(name[0], def, lo, hi, List.of(), false);
                        name[0] = store.morphs().get(i).name();
                    } else {
                        name[0] = old;
                    }
                    if (e.rename() != null) {
                        store.rename(name[0], e.rename());
                        name[0] = store.get(e.rename().trim()).name();
                    }
                    if (!e.create() && (e.defaultWeight() != null || e.min() != null || e.max() != null)) {
                        store.setParams(name[0], e.defaultWeight(), e.min(), e.max());
                    }
                    if (e.index() != null) store.move(name[0], e.index());
                    if (e.remove()) store.remove(name[0]);
                }, to == null ? null : old, to);
            }
            if (e.value() != null && !e.remove()) {
                OMOFormat.MorphData m = store.get(name[0]);
                if (m != null) d.preview().setValue(m.name(), Math.max(m.min(), Math.min(m.max(), e.value())));
            }
            McpAck ack = McpAck.ok().with("keys", store.size());
            OMOFormat.MorphData m = e.remove() ? null : store.get(name[0]);
            if (m != null) ack.with("key", keyRow(d, m));
            return ack;
        });
    }

    /**
     * {@code morph_capture}: {@code begin} remembers the mesh as the basis; {@code mesh} turns the vertex moves since
     * into the key {@code name} and (unless {@code keepMesh}) puts the basis back as a model undo step; {@code cancel}
     * forgets the basis; {@code deltas} writes explicit model-space rows for one part (key created when missing).
     */
    public McpAck morphCapture(String mode, String name, String part, int[] vertices, float[] deltas, boolean replace,
                               boolean keepMesh) {
        String m = mode == null ? "mesh" : mode.trim().toLowerCase();
        return onMain(() -> {
            Context c = require();
            VertexDataAuthoring d = c.data();
            switch (m) {
                case "begin" -> {
                    d.beginCapture();
                    return McpAck.ok().with("capturing", true)
                            .with("next", "move vertices, then morph_capture {mode:'mesh', name}");
                }
                case "cancel" -> {
                    d.cancelCapture();
                    return McpAck.ok().with("capturing", false);
                }
                case "mesh" -> {
                    requireName(name);
                    Map<String, PartMeshRebuilder.PartGeometry> basis = d.basisGeometry();
                    int index = d.finishCapture(name, replace);
                    int restored = keepMesh ? 0 : BasisRestore.apply(c.viewport(), basis);
                    OMOFormat.MorphData key = d.shapeKeys().morphs().get(index);
                    return McpAck.ok().with("key", keyRow(d, key)).with("restored_parts", restored);
                }
                case "deltas" -> {
                    requireName(name);
                    String partId = part(d, part);
                    if (vertices == null || vertices.length == 0) throw new IllegalArgumentException("vertices is required");
                    if (deltas == null || deltas.length != vertices.length * 3) {
                        throw new IllegalArgumentException("deltas needs 3 numbers (model-space x,y,z) per vertex");
                    }
                    int n = d.vertexCount(partId);
                    for (int v : vertices) {
                        if (v < 0 || v >= n) throw McpErrors.outOfRange("vertex", v, 0, n - 1, "part '" + part + "'", "list_parts");
                    }
                    OMOFormat.MorphPart rows = new OMOFormat.MorphPart(partId, vertices, deltas, null);
                    ShapeKeyStore store = d.shapeKeys();
                    d.edit("Shape Key Deltas", () -> {
                        if (store.indexOf(name) < 0) store.add(name, 0f, 0f, 1f, List.of(rows), false);
                        else store.setPartRows(name, rows, !replace);
                    });
                    return McpAck.ok().with("key", keyRow(d, store.get(name)));
                }
                default -> throw McpErrors.invalidEnum("mode", m, List.of("begin", "mesh", "cancel", "deltas"));
            }
        });
    }

    // ===================== Vertex groups =====================

    public Map<String, Object> vgroupList(String group, String part) {
        return onMain(() -> {
            VertexDataAuthoring d = require().data();
            VertexGroupStore store = d.groups();
            Map<String, Object> out = new LinkedHashMap<>();
            if (group != null) {
                OMOFormat.VertexGroupData g = requireGroup(store, group);
                out.put("group", groupRow(d, g));
                if (part != null) {
                    String partId = part(d, part);
                    List<Map<String, Object>> rows = new ArrayList<>();
                    Map<Integer, Float> w = VertexGroupStore.rowsOf(g, partId);
                    for (Map.Entry<Integer, Float> e : w.entrySet()) {
                        if (rows.size() >= MAX_ROWS) {
                            out.put("truncated", true);
                            break;
                        }
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("v", e.getKey());
                        row.put("w", Math.round(e.getValue() * 10000f) / 10000f);
                        rows.add(row);
                    }
                    out.put("rows", rows);
                }
                return out;
            }
            List<Map<String, Object>> groups = new ArrayList<>();
            for (OMOFormat.VertexGroupData g : store.groups()) groups.add(groupRow(d, g));
            out.put("groups", groups);
            return out;
        });
    }

    private static Map<String, Object> groupRow(VertexDataAuthoring d, OMOFormat.VertexGroupData g) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("name", g.name());
        r.put("vertices", VertexGroupStore.rowCount(g));
        Map<String, Integer> parts = new LinkedHashMap<>();
        for (OMOFormat.GroupPart p : g.parts()) parts.merge(d.partName(p.partId()), p.vertices().length, Integer::sum);
        r.put("parts", parts);
        if (d.preview().isHidden(g.name())) r.put("hidden", true);
        return r;
    }

    /** One {@code vgroup_set} call: create, rename, assign / unassign, index, remove (in that order); hidden = preview. */
    public record GroupEdit(String name, boolean create, boolean remove, String rename, String part, int[] vertices,
                            Float weight, float[] weights, boolean unassign, Integer index, Boolean hidden) {}

    public McpAck vgroupSet(GroupEdit e) {
        return onMain(() -> {
            VertexDataAuthoring d = require().data();
            VertexGroupStore store = d.groups();
            if (e.name() == null || e.name().isBlank()) throw new IllegalArgumentException("name is required");
            if (!e.create() && store.indexOf(e.name()) < 0) {
                throw McpErrors.unknownEntity("vertex group", e.name(),
                        store.groups().stream().map(OMOFormat.VertexGroupData::name).toList(), "vgroup_list");
            }
            String partId = e.part() == null ? null : part(d, e.part());
            if (e.vertices() != null && e.vertices().length > 0) {
                if (partId == null) throw new IllegalArgumentException("part is required with vertices");
                int n = d.vertexCount(partId);
                for (int v : e.vertices()) {
                    if (v < 0 || v >= n) throw McpErrors.outOfRange("vertex", v, 0, n - 1, "part '" + e.part() + "'", "list_parts");
                }
            }
            boolean edits = e.create() || e.remove() || e.rename() != null || e.index() != null
                    || (e.unassign() && partId != null) || (e.vertices() != null && e.vertices().length > 0);
            String[] name = {e.name().trim()};
            int[] rows = {0};
            if (edits) {
                String old = e.create() ? null : store.get(e.name()).name();
                String to = e.rename() != null && !e.remove() ? e.rename() : null;
                d.edit("Vertex Group", () -> {
                    if (e.create()) {
                        int i = store.add(name[0]);
                        name[0] = store.groups().get(i).name();
                    } else {
                        name[0] = old;
                    }
                    if (e.rename() != null) {
                        store.rename(name[0], e.rename());
                        name[0] = store.get(e.rename().trim()).name();
                    }
                    if (partId != null && e.unassign()) {
                        rows[0] = store.unassign(name[0], partId, e.vertices() == null || e.vertices().length == 0
                                ? null : e.vertices());
                    } else if (partId != null && e.vertices() != null && e.vertices().length > 0) {
                        rows[0] = store.assign(name[0], partId, e.vertices(), e.weight() == null ? 1f : e.weight(),
                                e.weights());
                    }
                    if (e.index() != null) store.move(name[0], e.index());
                    if (e.remove()) store.remove(name[0]);
                }, to == null ? null : old, to);
            }
            if (e.hidden() != null && !e.remove()) d.preview().setHidden(name[0], e.hidden());
            McpAck ack = McpAck.ok().with("groups", store.size());
            if (rows[0] > 0) ack.with(e.unassign() ? "removed" : "assigned", rows[0]);
            OMOFormat.VertexGroupData g = e.remove() ? null : store.get(name[0]);
            if (g != null) ack.with("group", groupRow(d, g));
            return ack;
        });
    }

    // ===================== Undo / redo =====================

    public boolean undo() {
        return onMain(() -> {
            Context c = source.get();
            return c != null && c.data().history().undo();
        });
    }

    public boolean redo() {
        return onMain(() -> {
            Context c = source.get();
            return c != null && c.data().history().redo();
        });
    }

    // ===================== Helpers =====================

    private Context require() {
        Context c = source.get();
        if (c == null) throw new IllegalStateException("Shape keys / vertex groups not available: viewport not initialized");
        return c;
    }

    private static void requireName(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
    }

    private static OMOFormat.MorphData requireKey(ShapeKeyStore store, String key) {
        OMOFormat.MorphData m = store.get(key);
        if (m == null) {
            throw McpErrors.unknownEntity("shape key", key, store.morphs().stream().map(OMOFormat.MorphData::name).toList(),
                    "morph_list");
        }
        return m;
    }

    private static OMOFormat.VertexGroupData requireGroup(VertexGroupStore store, String group) {
        OMOFormat.VertexGroupData g = store.get(group);
        if (g == null) {
            throw McpErrors.unknownEntity("vertex group", group,
                    store.groups().stream().map(OMOFormat.VertexGroupData::name).toList(), "vgroup_list");
        }
        return g;
    }

    private static String part(VertexDataAuthoring d, String idOrName) {
        String id = d.resolvePart(idOrName);
        if (id == null) {
            throw McpErrors.unknownEntity("part", String.valueOf(idOrName),
                    d.parts().getAllParts().stream().map(p -> p.name()).toList(), "list_parts");
        }
        return id;
    }

    private static float[] round(float x, float y, float z) {
        return new float[] {Math.round(x * 1e5f) / 1e5f, Math.round(y * 1e5f) / 1e5f, Math.round(z * 1e5f) / 1e5f};
    }

    private static <T> T onMain(java.util.concurrent.Callable<T> task) {
        CompletableFuture<T> future = MainThreadExecutor.submit(task);
        try {
            return future.get(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("Operation timed out on main thread", e);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        }
    }
}
