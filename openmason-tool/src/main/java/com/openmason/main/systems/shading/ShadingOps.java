package com.openmason.main.systems.shading;

import com.openmason.engine.modeling.EditableModel;
import com.openmason.engine.modeling.parts.MeshRange;
import com.openmason.engine.modeling.parts.ModelPartDescriptor;
import com.openmason.engine.modeling.parts.ModelPartManager;
import com.openmason.engine.modeling.parts.PartMeshRebuilder;
import com.openmason.engine.modeling.shading.ModelShading;
import com.openmason.main.systems.services.commands.MeshSnapshot;
import com.openmason.main.systems.services.commands.ModelCommandHistory;
import com.openmason.main.systems.services.commands.RendererSynchronizer;
import com.openmason.main.systems.services.commands.SnapshotCommand;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;

/**
 * The editor's shading actions (OpenMason #169): Shade Smooth / Flat / Auto Smooth, Mark / Clear Sharp, Clear
 * Custom Normals, for the Model Properties "Shading" section and the {@code shading_*} MCP tools. Thin: it turns a
 * selection (face ids, edge endpoints, a part) into one {@link ModelShading} call; the shading itself is native.
 * {@link #recorded} makes an action one step of the model's undo history (snapshots carry the shading).
 */
public final class ShadingOps {

    private ShadingOps() {
    }

    /** What to do with faces. */
    public enum Mode {
        SMOOTH, FLAT, AUTO, CLEAR_CUSTOM
    }

    /**
     * Applies a face mode.
     *
     * @param faces   global face ids, or null = every face of {@code keys}
     * @param keys    store keys (part ids), or null = the whole model
     * @param degrees the auto smooth angle (AUTO only)
     */
    public static void apply(EditableModel model, Mode mode, int[] faces, List<String> keys, float degrees) {
        ModelShading shading = model.getShading();
        switch (mode) {
            case SMOOTH -> {
                if (faces != null) shading.setSmooth(faces, true);
                else shading.setSmoothAll(keys, true);
            }
            case FLAT -> {
                if (faces != null) shading.setSmooth(faces, false);
                else shading.setSmoothAll(keys, false);
            }
            case AUTO -> shading.setAutoSmooth(keys, faces, degrees);
            case CLEAR_CUSTOM -> shading.clearCustomNormals(keys, faces);
        }
    }

    /**
     * Marks or clears sharp edges given as model-space endpoint positions (6 floats per edge); null edges with
     * {@code sharp == false} clears every sharp edge of {@code keys} (null = all).
     */
    public static void sharp(EditableModel model, float[] edges, boolean sharp, List<String> keys) {
        if (edges == null) {
            if (!sharp) model.getShading().clearAllSharp(keys);
            return;
        }
        model.getShading().markSharp(keys, edges, sharp);
    }

    /** Edge endpoints of the editor's unique vertex pairs (2 per edge) as 6 floats per edge. */
    public static float[] edgesFromUniqueVertices(EditableModel model, int[] uniquePairs) {
        float[] out = new float[(uniquePairs.length / 2) * 6];
        for (int i = 0; i + 1 < uniquePairs.length; i += 2) {
            Vector3f a = model.getUniqueVertexPosition(uniquePairs[i]);
            Vector3f b = model.getUniqueVertexPosition(uniquePairs[i + 1]);
            if (a == null || b == null) {
                throw new IllegalArgumentException("unknown vertex in edge " + (i / 2));
            }
            int o = (i / 2) * 6;
            out[o] = a.x;
            out[o + 1] = a.y;
            out[o + 2] = a.z;
            out[o + 3] = b.x;
            out[o + 4] = b.y;
            out[o + 5] = b.z;
        }
        return out;
    }

    /**
     * Edge endpoints of part-local vertex pairs (the {@code part_mesh} vertex indices) in model space: the part's
     * geometry through its effective (baked) transform.
     */
    public static float[] edgesFromPartVertices(ModelPartManager parts, ModelPartDescriptor part, int[] localPairs) {
        PartMeshRebuilder.PartGeometry geo = parts.getPartGeometry(part.id());
        if (geo == null || geo.vertices() == null) {
            throw new IllegalArgumentException("part has no geometry: " + part.name());
        }
        float[] v = geo.vertices();
        int count = v.length / 3;
        Matrix4f world = parts.getEffectiveWorldMatrix(part.id());
        float[] out = new float[(localPairs.length / 2) * 6];
        Vector3f p = new Vector3f();
        for (int i = 0; i < localPairs.length; i++) {
            int local = localPairs[i];
            if (local < 0 || local >= count) {
                throw new IllegalArgumentException("vertex " + local + " out of range (part has " + count
                        + " vertices)");
            }
            world.transformPosition(v[local * 3], v[local * 3 + 1], v[local * 3 + 2], p);
            out[i * 3] = p.x;
            out[i * 3 + 1] = p.y;
            out[i * 3 + 2] = p.z;
        }
        return out;
    }

    /** Global face ids of part-local ones (validated against the part's range). */
    public static int[] globalFaces(ModelPartDescriptor part, int[] localFaces) {
        MeshRange range = part.meshRange();
        if (range == null) throw new IllegalArgumentException("part has no mesh: " + part.name());
        int[] out = new int[localFaces.length];
        for (int i = 0; i < localFaces.length; i++) {
            int f = localFaces[i];
            if (f < 0 || f >= range.faceCount()) {
                throw new IllegalArgumentException("face " + f + " out of range (part has " + range.faceCount()
                        + " faces)");
            }
            out[i] = range.faceStart() + f;
        }
        return out;
    }

    /**
     * Runs an action as one undoable model step (snapshot before / after, which carry the shading); nothing is
     * recorded when it changed nothing. Without a history the action just runs.
     */
    public static void recorded(EditableModel model, ModelCommandHistory history, RendererSynchronizer sync,
                                String label, Runnable action) {
        if (history == null || sync == null) {
            action.run();
            return;
        }
        MeshSnapshot before = MeshSnapshot.capture(model);
        action.run();
        MeshSnapshot after = MeshSnapshot.capture(model);
        if (!before.snapshot().contentEquals(after.snapshot())) {
            history.pushCompleted(SnapshotCommand.custom(label, before, after, model, sync));
        }
    }
}
