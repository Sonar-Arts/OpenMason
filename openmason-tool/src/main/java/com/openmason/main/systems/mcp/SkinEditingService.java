package com.openmason.main.systems.mcp;

import com.openmason.engine.rig.Skin;
import com.openmason.main.systems.ViewportController;
import com.openmason.main.systems.services.EditorServices;
import com.openmason.main.systems.skeleton.SkinAuthoring;
import com.openmason.main.systems.skeleton.SkinCommandHistory;
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
 * Thread-safe facade over skin-weight authoring for the MCP skin tools. Every call marshals to the
 * main thread ({@link MainThreadExecutor}, same contract as {@link BoneEditingService}); every mutation
 * is one {@link SkinCommandHistory} step (undo domain {@code skin}, shared with the rigging pane). The
 * weight math is native ({@link SkinAuthoring} -> {@code com.openmason.engine.rig.Skin}).
 *
 * <p>Parts and joints are addressed by id or name; a joint may be a bone or a part (bone names win
 * over part names). Positions are model space; vertex indices are part-local.
 */
public final class SkinEditingService {

    private static final long DEFAULT_TIMEOUT_MS = 10_000;
    /** Most vertex rows skin_get_weights returns unless the caller lists vertices. */
    static final int MAX_ROWS = 512;

    /** What the tools edit: the authoring facade and its undo history. */
    public record Context(SkinAuthoring authoring, SkinCommandHistory history) {}

    private final Supplier<Context> source;

    public SkinEditingService(EditorServices editorServices) {
        this(() -> {
            ViewportController vp = editorServices == null ? null : editorServices.getViewport3D();
            return vp == null ? null : new Context(vp.getSkinAuthoring(), vp.getSkinHistory());
        });
    }

    /** Over an explicit context (headless tests). */
    SkinEditingService(Supplier<Context> source) {
        this.source = source;
    }

    // ===================== Read =====================

    public Map<String, Object> info() {
        return onMain(() -> {
            Context c = require();
            SkinAuthoring a = c.authoring();
            List<Map<String, Object>> rows = new ArrayList<>();
            int total = 0;
            for (String partId : a.partIds()) {
                int weighted = a.weightedCount(partId);
                total += weighted;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("part", a.jointName(partId));
                row.put("verts", a.vertexCount(partId));
                row.put("weighted", weighted);
                if (weighted > 0) row.put("joints", a.jointsUsed(partId).stream().map(a::jointName).toList());
                rows.add(row);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("weighted", total);
            out.put("bones", a.boneJoints().size());
            out.put("parts", rows);
            return out;
        });
    }

    /**
     * The influences of a part's vertices: the listed ones (unweighted rows say so), or every weighted
     * vertex (at most {@link #MAX_ROWS}).
     */
    public Map<String, Object> getWeights(String part, int[] vertices) {
        return onMain(() -> {
            SkinAuthoring a = require().authoring();
            String partId = part(a, part);
            int n = a.vertexCount(partId);
            Skin s = a.skins().skinOf(partId);
            List<Map<String, Object>> rows = new ArrayList<>();
            boolean truncated = false;
            if (vertices != null && vertices.length > 0) {
                for (int v : vertices) {
                    if (v < 0 || v >= n) throw McpErrors.outOfRange("vertex", v, 0, n - 1, "part '" + part + "'", "skin_info");
                    rows.add(row(a, s == null || s.vertexCount() != n ? null : s.get(v), v));
                }
            } else if (s != null && s.vertexCount() == n) {
                List<Skin.Influence> all = s.exportInfluences();
                truncated = all.size() > MAX_ROWS;
                for (int i = 0; i < Math.min(MAX_ROWS, all.size()); i++) rows.add(row(a, all.get(i), all.get(i).vertex()));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("part", a.jointName(partId));
            out.put("verts", n);
            out.put("weighted", a.weightedCount(partId));
            out.put("vertices", rows);
            if (truncated) out.put("truncated", true);
            return out;
        });
    }

    private static Map<String, Object> row(SkinAuthoring a, Skin.Influence inf, int vertex) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("v", vertex);
        if (inf == null || inf.joints().length == 0) {
            r.put("follows_part", true);
            return r;
        }
        List<String> names = new ArrayList<>();
        for (int j : inf.joints()) names.add(a.registryJointName(j));
        r.put("joints", names);
        r.put("weights", round(inf.weights()));
        return r;
    }

    private static float[] round(float[] w) {
        float[] out = new float[w.length];
        for (int i = 0; i < w.length; i++) out[i] = Math.round(w[i] * 10000f) / 10000f;
        return out;
    }

    // ===================== Mutate =====================

    /** One entry of {@link #setWeights}: a vertex and its influences (empty = follows its part). */
    public record WeightEntry(int vertex, List<String> joints, float[] weights) {}

    public McpAck setWeights(String part, List<WeightEntry> entries) {
        return onMain(() -> {
            Context c = require();
            SkinAuthoring a = c.authoring();
            String partId = part(a, part);
            int n = a.vertexCount(partId);
            List<List<String>> resolved = new ArrayList<>();
            for (WeightEntry e : entries) {
                if (e.vertex() < 0 || e.vertex() >= n) {
                    throw McpErrors.outOfRange("vertex", e.vertex(), 0, n - 1, "part '" + part + "'", "skin_info");
                }
                if (e.joints().size() != e.weights().length) {
                    throw new IllegalArgumentException("vertex " + e.vertex() + ": joints and weights differ in length");
                }
                List<String> ids = new ArrayList<>();
                for (String j : e.joints()) ids.add(joint(a, j));
                resolved.add(ids);
            }
            c.history().record("Set Weights", () -> {
                for (int i = 0; i < entries.size(); i++) {
                    a.setWeights(partId, entries.get(i).vertex(), resolved.get(i), entries.get(i).weights());
                }
            });
            return McpAck.ok().with("part", a.jointName(partId)).with("vertices", entries.size())
                    .with("weighted", a.weightedCount(partId));
        });
    }

    public McpAck autoWeights(List<String> parts, List<String> bones, Float falloff, Float minWeight, boolean rigid) {
        return onMain(() -> {
            Context c = require();
            SkinAuthoring a = c.authoring();
            List<String> partIds = parts(a, parts);
            List<String> boneIds = new ArrayList<>();
            if (bones != null) {
                for (String b : bones) {
                    String id = joint(a, b);
                    if (a.boneJoints().stream().noneMatch(j -> j.id().equals(id))) {
                        throw new IllegalArgumentException("'" + b + "' is a part, not a bone");
                    }
                    boneIds.add(id);
                }
            }
            float f = rigid ? 0f : (falloff == null ? 2f : falloff);
            float m = minWeight == null ? 0.05f : minWeight;
            int[] written = new int[1];
            c.history().record("Auto Weights", () -> written[0] = a.autoWeights(partIds, boneIds, f, m));
            return McpAck.ok().with("parts", partIds.size()).with("vertices", written[0]);
        });
    }

    public McpAck normalize(List<String> parts) {
        return op("Normalize Weights", parts, (a, ids) -> a.normalize(ids), "weighted");
    }

    public McpAck prune(float minWeight, List<String> parts) {
        return op("Prune Weights", parts, (a, ids) -> a.prune(ids, minWeight), "removed");
    }

    public McpAck clear(String part) {
        return op("Clear Weights", part == null ? null : List.of(part), (a, ids) -> a.clear(ids), "cleared");
    }

    public McpAck mirror(String axis, int sourceSign, Float tolerance, List<String> parts) {
        int ax = switch (axis == null ? "x" : axis.trim().toLowerCase()) {
            case "x" -> 0;
            case "y" -> 1;
            case "z" -> 2;
            default -> throw McpErrors.invalidEnum("axis", axis, List.of("x", "y", "z"));
        };
        if (sourceSign != 1 && sourceSign != -1) throw new IllegalArgumentException("source_sign must be 1 or -1");
        float tol = tolerance == null ? 0.01f : tolerance;
        return op("Mirror Weights", parts, (a, ids) -> a.mirror(ids, ax, sourceSign, tol), "written");
    }

    public McpAck paint(String part, float[] center, float radius, String joint, String mode, float strength) {
        Skin.PaintMode m = paintMode(mode);
        if (center == null || center.length != 3) throw new IllegalArgumentException("center must be [x,y,z]");
        if (!(radius > 0f)) throw new IllegalArgumentException("radius must be > 0");
        return onMain(() -> {
            Context c = require();
            SkinAuthoring a = c.authoring();
            String jointId = joint(a, joint);
            List<String> ids = parts(a, part == null ? null : List.of(part));
            int[] changed = new int[1];
            c.history().record("Paint Weights", () ->
                    changed[0] = a.paint(ids, center[0], center[1], center[2], radius, jointId, m,
                            Math.max(0f, Math.min(1f, strength))));
            return McpAck.ok().with("changed", changed[0]);
        });
    }

    // ===================== Undo / redo =====================

    public boolean undo() {
        return onMain(() -> {
            Context c = source.get();
            return c != null && c.history().undo();
        });
    }

    public boolean redo() {
        return onMain(() -> {
            Context c = source.get();
            return c != null && c.history().redo();
        });
    }

    // ===================== Helpers =====================

    private interface PartsOp {
        int apply(SkinAuthoring authoring, List<String> partIds);
    }

    private McpAck op(String description, List<String> parts, PartsOp op, String countKey) {
        return onMain(() -> {
            Context c = require();
            SkinAuthoring a = c.authoring();
            List<String> ids = parts(a, parts);
            int[] n = new int[1];
            c.history().record(description, () -> n[0] = op.apply(a, ids));
            return McpAck.ok().with("parts", ids.size()).with(countKey, n[0]);
        });
    }

    static Skin.PaintMode paintMode(String mode) {
        String m = mode == null ? "add" : mode.trim().toLowerCase();
        return switch (m) {
            case "add" -> Skin.PaintMode.ADD;
            case "subtract" -> Skin.PaintMode.SUBTRACT;
            case "replace" -> Skin.PaintMode.REPLACE;
            case "smooth" -> Skin.PaintMode.SMOOTH;
            default -> throw McpErrors.invalidEnum("mode", m, List.of("add", "subtract", "replace", "smooth"));
        };
    }

    private Context require() {
        Context c = source.get();
        if (c == null) throw new IllegalStateException("Skin editing not available: viewport not initialized");
        return c;
    }

    private static String part(SkinAuthoring a, String idOrName) {
        String id = a.resolvePart(idOrName);
        if (id == null) {
            throw McpErrors.unknownEntity("part", String.valueOf(idOrName),
                    a.parts().getAllParts().stream().map(p -> p.name()).toList(), "list_parts");
        }
        return id;
    }

    /** The listed parts, or every part with geometry when the list is null/empty. */
    private static List<String> parts(SkinAuthoring a, List<String> idsOrNames) {
        if (idsOrNames == null || idsOrNames.isEmpty()) return a.partIds();
        List<String> out = new ArrayList<>();
        for (String p : idsOrNames) out.add(part(a, p));
        return out;
    }

    private static String joint(SkinAuthoring a, String idOrName) {
        String id = a.resolveJoint(idOrName);
        if (id == null) {
            throw McpErrors.unknownEntity("joint", String.valueOf(idOrName),
                    a.joints().stream().map(SkinAuthoring.JointRef::name).toList(), "bone_list");
        }
        return id;
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
