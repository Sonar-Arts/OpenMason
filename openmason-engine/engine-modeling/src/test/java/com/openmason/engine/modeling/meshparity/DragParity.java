package com.openmason.engine.modeling.meshparity;

import com.openmason.engine.modeling.editable.CendaMesh;
import com.openmason.engine.modeling.editable.EditableFace;
import com.openmason.engine.modeling.editable.EditableMesh;
import com.openmason.engine.modeling.editable.RenderMesh;
import com.openmason.engine.modeling.editable.RenderMeshBuilder;
import com.openmason.engine.modeling.meshparity.MeshPipelineCases.MeshCase;
import com.openmason.engine.modeling.meshparity.MeshPipelineParityTest.Legacy;
import com.openmason.engine.modeling.meshparity.MeshPipelineParityTest.Report;
import com.openmason.engine.modeling.meshparity.legacy.LegacyRenderMeshBuilder;
import com.openmason.engine.modeling.meshparity.legacy.LegacyTopology;
import com.openmason.engine.modeling.uv.FaceTextureMapping;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * Drag parity (README step 4): random vertex moves applied to the native mesh with {@code co_mesh_move_vertices}
 * and to a Java {@link EditableMesh} with {@code setPosition}, then compared against a frozen-Java FULL rebuild
 * (render mesh + topology).
 */
final class DragParity {

    static final int FRAMES = 6;

    private DragParity() {
    }

    /** @return frames run */
    static int run(MeshCase c, Legacy l0, SplittableRandom rnd, Report r) {
        EditableMesh mesh = l0.mesh().deepCopy();
        int n = c.mappings().size();
        int[] ids = new int[n];
        for (int i = 0; i < n; i++) ids[i] = c.mappings().get(i).faceId();
        List<EditableFace> faces = new ArrayList<>(mesh.faces());
        try (CendaMesh cm = CendaMesh.create();
             RenderMeshBuilder.Derivation derivation = RenderMeshBuilder.derive(mesh, l0.ftm())) {
            cm.setMappings(ids, c.mappings().toArray(new FaceTextureMapping[0]), n);
            cm.setFaces(mesh, CendaMesh.STAGE_TRIANGULATE | CendaMesh.STAGE_RENDER | CendaMesh.STAGE_TOPOLOGY);
            for (int frame = 0; frame < FRAMES; frame++) {
                int[] moved = pickVertices(mesh, faces, rnd);
                float[] xyz = new float[moved.length * 3];
                for (int i = 0; i < moved.length; i++) {
                    Vector3f p = newPosition(mesh, faces, moved[i], rnd);
                    mesh.setPosition(moved[i], p);
                    xyz[3 * i] = p.x;
                    xyz[3 * i + 1] = p.y;
                    xyz[3 * i + 2] = p.z;
                }
                cm.moveVertices(moved, xyz);
                derivation.movePositions(mesh, moved);
                RenderMesh rm = LegacyRenderMeshBuilder.build(mesh, l0.ftm());
                Legacy l = new Legacy(null, null, null, mesh, null, l0.ftm(), rm, LegacyTopology.build(mesh, rm));
                String ctx = c.name() + " drag frame " + frame;
                MeshPipelineParityTest.compareNative(ctx, l, cm, r);
                production(ctx, l, derivation, r);
            }
        }
        return FRAMES;
    }

    /** The production incremental path ({@link RenderMeshBuilder.Derivation#movePositions}) vs the full rebuild. */
    static void production(String ctx, Legacy l, RenderMeshBuilder.Derivation derivation, Report r) {
        MeshPipelineParityTest.compareRenderMeshes(ctx, "prod Derivation.renderMesh", l.rm(), derivation.renderMesh(),
                r);
        MeshPipelineParityTest.compareTopology(ctx, "prod Derivation.topology", l, derivation.topology(), r);
    }

    private static int[] pickVertices(EditableMesh mesh, List<EditableFace> faces, SplittableRandom rnd) {
        Set<Integer> out = new LinkedHashSet<>();
        switch (rnd.nextInt(4)) {
            case 0 -> { // a whole face
                EditableFace f = faces.get(rnd.nextInt(faces.size()));
                for (int v : f.loop()) out.add(v);
            }
            case 1 -> { // everything (incl. orphans)
                for (int v = 0; v < mesh.vertexCount(); v++) out.add(v);
            }
            default -> {
                int k = rnd.nextInt(1, 5);
                for (int i = 0; i < k; i++) out.add(rnd.nextInt(mesh.vertexCount()));
            }
        }
        int[] a = out.stream().mapToInt(Integer::intValue).toArray();
        if (a.length > 1 && rnd.nextInt(8) == 0) { // a repeated id: the last write wins on both sides
            int[] b = java.util.Arrays.copyOf(a, a.length + 1);
            b[a.length] = a[0];
            return b;
        }
        return a;
    }

    private static Vector3f newPosition(EditableMesh mesh, List<EditableFace> faces, int v, SplittableRandom rnd) {
        Vector3f p = mesh.position(v);
        switch (rnd.nextInt(6)) {
            case 0 -> { // collapse onto another vertex of one of its faces (degenerate)
                for (EditableFace f : faces) {
                    if (f.containsVertex(v)) {
                        int o = f.vertexAt((f.indexOf(v) + 1) % f.loopLength());
                        return mesh.position(o);
                    }
                }
                return p;
            }
            case 1 -> {
                return p.add((float) rnd.nextDouble(-2, 2), (float) rnd.nextDouble(-2, 2),
                        (float) rnd.nextDouble(-2, 2));
            }
            case 2 -> {
                return p.mul(-1f, 1f, 1f);
            }
            case 3 -> {
                return new Vector3f(p.x, p.y, p.z + 1e-5f);
            }
            default -> {
                return p.add((float) rnd.nextDouble(-0.1, 0.1), 0.05f, (float) rnd.nextDouble(-0.1, 0.1));
            }
        }
    }
}
