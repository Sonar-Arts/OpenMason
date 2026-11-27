package com.openmason.main.systems.mcp;

import com.openmason.engine.modeling.EditableModel;
import com.openmason.engine.modeling.parts.ModelPartDescriptor;
import com.openmason.engine.modeling.parts.ModelPartManager;
import com.openmason.engine.modeling.shading.ModelShading;
import com.openmason.main.systems.ViewportController;
import com.openmason.main.systems.services.EditorServices;
import com.openmason.main.systems.shading.ShadingOps;
import com.openmason.main.systems.threading.MainThreadExecutor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The {@code shading_*} MCP tools (OpenMason #169): smooth / flat / auto smooth shading, sharp edges and custom
 * normals of the model editor's model, through the native per-part shading stores. Faces and vertices are
 * PART-LOCAL ids (as {@code part_mesh} lists them). Every change is one step of the model history (undo domain
 * {@code model}).
 */
public final class ShadingEditingService {

    private static final long TIMEOUT_MS = 10_000;

    /** What the tools act on: the editor's model, its parts and its undo history (null history = unrecorded). */
    public record Context(EditableModel model, ModelPartManager parts,
                          com.openmason.main.systems.services.commands.ModelCommandHistory history,
                          com.openmason.main.systems.services.commands.RendererSynchronizer sync) {
    }

    private final java.util.function.Supplier<Context> context;

    public ShadingEditingService(EditorServices editorServices) {
        this(() -> {
            ViewportController vp = editorServices != null ? editorServices.getViewport3D() : null;
            if (vp == null || vp.getModelRenderer() == null) return null;
            return new Context(vp.getModelRenderer(), vp.getPartManager(), vp.getCommandHistory(),
                    vp.getRendererSynchronizer());
        });
    }

    /** Over an explicit context (tests, headless hosts). */
    public ShadingEditingService(java.util.function.Supplier<Context> context) {
        this.context = context;
    }

    private void recorded(String label, Runnable action) {
        Context c = requireContext();
        ShadingOps.recorded(c.model(), c.history(), c.sync(), label, action);
    }

    /**
     * Shade faces: mode smooth | flat | auto (smooth + auto smooth angle) | clear_custom (drop custom normals).
     *
     * @param part  part id or name; null = every part
     * @param faces part-local face ids; null = the whole part(s)
     */
    public Map<String, Object> set(String part, int[] faces, String mode, Float angle) {
        ShadingOps.Mode m = parseMode(mode);
        if (m == ShadingOps.Mode.AUTO && (angle == null || !(angle > 0f) || angle > 180f)) {
            throw new IllegalArgumentException("auto needs angle in (0, 180] degrees");
        }
        if (faces != null && part == null) {
            throw new IllegalArgumentException("faces are part-local: give the part too");
        }
        return onMain(() -> {
            EditableModel model = requireModel();
            ModelPartDescriptor p = part == null ? null : resolve(part);
            int[] global = faces == null ? null : ShadingOps.globalFaces(p, faces);
            List<String> keys = p == null ? null : List.of(p.id());
            recorded("Shading: " + m.name().toLowerCase(),
                    () -> ShadingOps.apply(model, m, global, keys, angle == null ? 0f : angle));
            return summary(model, p);
        });
    }

    /**
     * Marks (sharp) or clears edges given as part-local vertex pairs; no edges with sharp false clears every sharp
     * edge of the part (null part = all).
     */
    public Map<String, Object> sharp(String part, List<int[]> edges, boolean sharp) {
        if ((edges == null || edges.isEmpty()) && sharp) {
            throw new IllegalArgumentException("edges required to mark sharp: [[a, b], ...] part-local vertices");
        }
        if (edges != null && !edges.isEmpty() && part == null) {
            throw new IllegalArgumentException("edge vertices are part-local: give the part too");
        }
        return onMain(() -> {
            EditableModel model = requireModel();
            ModelPartDescriptor p = part == null ? null : resolve(part);
            List<String> keys = p == null ? null : List.of(p.id());
            float[] positions = null;
            if (edges != null && !edges.isEmpty()) {
                int[] pairs = new int[edges.size() * 2];
                for (int i = 0; i < edges.size(); i++) {
                    int[] e = edges.get(i);
                    if (e == null || e.length != 2 || e[0] == e[1]) {
                        throw new IllegalArgumentException("edges[" + i + "] must be two different vertices");
                    }
                    pairs[i * 2] = e[0];
                    pairs[i * 2 + 1] = e[1];
                }
                positions = ShadingOps.edgesFromPartVertices(requireParts(), p, pairs);
            }
            float[] edgePositions = positions;
            recorded(sharp ? "Mark Sharp" : "Clear Sharp", () -> ShadingOps.sharp(model, edgePositions, sharp, keys));
            return summary(model, p);
        });
    }

    /** The shading of a part (null = every part): smooth faces, sharp edges, custom normals, auto smooth. */
    public Map<String, Object> get(String part) {
        return onMain(() -> {
            EditableModel model = requireModel();
            return summary(model, part == null ? null : resolve(part));
        });
    }

    private Map<String, Object> summary(EditableModel model, ModelPartDescriptor only) {
        ModelPartManager parts = requireParts();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ModelShading.Summary s : model.getShading().summary()) {
            if (only != null && !only.id().equals(s.key())) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            Optional<ModelPartDescriptor> p = s.key().isEmpty() ? Optional.empty() : parts.getPartById(s.key());
            row.put("part", p.map(ModelPartDescriptor::name).orElse(s.key().isEmpty() ? "(no part)" : s.key()));
            row.put("faces", s.faces());
            row.put("smooth_faces", s.smoothFaces());
            row.put("sharp_edges", s.sharpEdges());
            row.put("custom_normals", s.customNormals());
            if (s.autoSmoothDegrees() > 0f) row.put("auto_smooth", s.autoSmoothDegrees());
            if (only != null && s.smoothFaces() > 0 && s.smoothFaces() < s.faces()) {
                row.put("smooth_face_ids", smoothLocalFaces(model, s.key()));
            }
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("parts", rows);
        return out;
    }

    private static List<Integer> smoothLocalFaces(EditableModel model, String key) {
        List<Integer> out = new ArrayList<>();
        var store = model.getShading().existingStore(key);
        if (store == null) return out;
        int[] words = store.smoothWords();
        for (int w = 0; w < words.length && out.size() < 500; w++) {
            for (int b = 0; b < 32; b++) {
                if (((words[w] >>> b) & 1) != 0) out.add(w * 32 + b);
            }
        }
        return out;
    }

    private static ShadingOps.Mode parseMode(String mode) {
        if (mode == null) throw new IllegalArgumentException("mode is required: smooth, flat, auto or clear_custom");
        return switch (mode.trim().toLowerCase()) {
            case "smooth" -> ShadingOps.Mode.SMOOTH;
            case "flat" -> ShadingOps.Mode.FLAT;
            case "auto" -> ShadingOps.Mode.AUTO;
            case "clear_custom" -> ShadingOps.Mode.CLEAR_CUSTOM;
            default -> throw McpErrors.invalidEnum("mode", mode, List.of("smooth", "flat", "auto", "clear_custom"));
        };
    }

    private Context requireContext() {
        Context c = context.get();
        if (c == null || c.model() == null) {
            throw new IllegalStateException("Model editor unavailable - is the UI running?");
        }
        return c;
    }

    private EditableModel requireModel() {
        EditableModel model = requireContext().model();
        if (!ModelShading.isAvailable()) {
            throw new IllegalStateException("Native shading unavailable (Cenda library missing or mismatched)");
        }
        return model;
    }

    private ModelPartManager requireParts() {
        ModelPartManager parts = requireContext().parts();
        if (parts == null) throw new IllegalStateException("Viewport not initialized");
        return parts;
    }

    private ModelPartDescriptor resolve(String idOrName) {
        ModelPartManager pm = requireParts();
        return pm.getPartById(idOrName).or(() -> pm.getPartByName(idOrName)).orElseThrow(() ->
                McpErrors.unknownEntity("part", idOrName,
                        pm.getAllParts().stream().map(ModelPartDescriptor::name).toList(), "list_parts"));
    }

    private static <T> T onMain(java.util.concurrent.Callable<T> task) {
        CompletableFuture<T> future = MainThreadExecutor.submit(task);
        try {
            return future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("Operation timed out on main thread", e);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        }
    }
}
