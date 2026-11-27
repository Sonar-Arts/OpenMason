package com.openmason.engine.modeling.meshparity;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.modeling.EditableModel;
import com.openmason.engine.modeling.editable.EditableMesh;
import com.openmason.engine.modeling.editable.MeshImporter;
import com.openmason.engine.modeling.editable.RenderMesh;
import com.openmason.engine.modeling.editable.RenderMeshBuilder;
import com.openmason.engine.modeling.meshparity.legacy.LegacyMeshImporter;
import com.openmason.engine.modeling.meshparity.legacy.LegacyRenderMeshBuilder;
import com.openmason.engine.modeling.meshparity.legacy.LegacyTopology;
import com.openmason.engine.modeling.topology.MeshTopologyBuilder;
import com.openmason.engine.modeling.uv.FaceTextureManager;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Headless timing of the mesh pipeline on a ~54k-triangle model (opt-in {@code -Dopenmason.meshpipeline.bench=true};
 * prints medians). "Java" = the frozen pre-#175-F3 classes ({@code meshparity.legacy}), "native" = the production
 * bindings over {@code co_mesh_*}; {@code EditableModel} rows run the real editor load / drag path.
 */
class MeshPipelineBenchmarkTest {

    private static final class HeadlessModel extends EditableModel {
    }

    /** A UV sphere of quads (rings x segments quads = 2 x triangles), soup style (own corners per face). */
    static OMOFormat.MeshData sphere(int rings, int segments) {
        int quads = rings * segments;
        float[] v = new float[quads * 4 * 3];
        float[] uv = new float[quads * 4 * 2];
        int[] idx = new int[quads * 6];
        int[] tri = new int[quads * 2];
        int q = 0;
        for (int r = 0; r < rings; r++) {
            for (int s = 0; s < segments; s++) {
                int[][] c = {{r, s}, {r, s + 1}, {r + 1, s + 1}, {r + 1, s}};
                for (int k = 0; k < 4; k++) {
                    double th = Math.PI * (c[k][0] + 0.5) / (rings + 1);
                    double ph = 2 * Math.PI * c[k][1] / segments;
                    int o = (q * 4 + k);
                    v[o * 3] = (float) (Math.sin(th) * Math.cos(ph));
                    v[o * 3 + 1] = (float) Math.cos(th);
                    v[o * 3 + 2] = (float) (Math.sin(th) * Math.sin(ph));
                    uv[o * 2] = (float) c[k][1] / segments;
                    uv[o * 2 + 1] = (float) c[k][0] / rings;
                }
                int b = q * 4;
                System.arraycopy(new int[]{b, b + 1, b + 2, b, b + 2, b + 3}, 0, idx, q * 6, 6);
                tri[q * 2] = q;
                tri[q * 2 + 1] = q;
                q++;
            }
        }
        return new OMOFormat.MeshData(v, uv, idx, tri, "FLAT");
    }

    static double medianMs(int runs, Runnable r) {
        r.run(); // warm-up
        r.run();
        double[] ms = new double[runs];
        for (int i = 0; i < runs; i++) {
            long t0 = System.nanoTime();
            r.run();
            ms[i] = (System.nanoTime() - t0) / 1e6;
        }
        Arrays.sort(ms);
        return ms[runs / 2];
    }

    @Test
    void fiftyFourThousandTriangles() {
        assumeTrue(Boolean.getBoolean("openmason.meshpipeline.bench"), "opt-in: -Dopenmason.meshpipeline.bench=true");
        OMOFormat.MeshData data = sphere(150, 180); // 27,000 quads = 54,000 triangles
        assertEquals(54_000, data.indices().length / 3);
        FaceTextureManager ftm = new FaceTextureManager();
        int runs = 7;

        double javaImport = medianMs(runs, () -> LegacyMeshImporter.importSoup(data.vertices(), data.texCoords(),
                data.indices(), data.triangleToFaceId()));
        double nativeImport = medianMs(runs, () -> MeshImporter.importSoup(data.vertices(), data.texCoords(),
                data.indices(), data.triangleToFaceId()));
        EditableMesh mesh = MeshImporter.importSoup(data.vertices(), data.texCoords(), data.indices(),
                data.triangleToFaceId()).mesh();

        double javaRender = medianMs(runs, () -> LegacyRenderMeshBuilder.build(mesh, ftm));
        double nativeRender = medianMs(runs, () -> RenderMeshBuilder.build(mesh, ftm));
        RenderMesh rm = RenderMeshBuilder.build(mesh, ftm);
        double javaTopo = medianMs(runs, () -> LegacyTopology.build(mesh, rm));
        double nativeTopo = medianMs(runs, () -> MeshTopologyBuilder.build(mesh, rm));
        double nativeDerive = medianMs(runs, () -> {
            try (RenderMeshBuilder.Derivation d = RenderMeshBuilder.derive(mesh, ftm)) {
                d.renderMesh();
            }
        });

        // Drag: one vertex moved, then a full rebuild (Java) vs the incremental refresh (native).
        int[] moved = {mesh.faces().iterator().next().vertexAt(0)};
        Vector3f p = mesh.position(moved[0]);
        double javaDrag = medianMs(runs, () -> {
            p.y += 1e-3f;
            mesh.setPosition(moved[0], p);
            RenderMesh r = LegacyRenderMeshBuilder.build(mesh, ftm);
            LegacyTopology.build(mesh, r);
        });
        double nativeDrag;
        try (RenderMeshBuilder.Derivation d = RenderMeshBuilder.derive(mesh, ftm)) {
            nativeDrag = medianMs(runs * 3, () -> {
                p.y += 1e-3f;
                mesh.setPosition(moved[0], p);
                d.movePositions(mesh, moved);
            });
        }

        // The editor path: EditableModel load (import + derive + caches + topology) and a positions frame through
        // EditableModel.updateVertexPositions (MeshRebuildPipeline.refreshPositionsFromEditable = a full derive until
        // the drag path calls RenderMeshBuilder.Derivation.movePositions).
        double modelLoad = medianMs(5, () -> new HeadlessModel().loadMeshData(data));
        HeadlessModel model = new HeadlessModel();
        model.loadMeshData(data);
        float[] all = model.getAllMeshVertexPositions();
        double modelDrag = medianMs(5, () -> {
            all[1] += 1e-3f;
            model.updateVertexPositions(all);
        });
        // "Before" estimate for the load: the same EditableModel overhead around the frozen Java chain.
        double modelLoadJava = modelLoad - (nativeImport + nativeRender + nativeTopo) + (javaImport + javaRender
                + javaTopo);

        System.out.printf("[mesh-bench] 54k tris (%d soup vertices, %d faces):%n", data.vertices().length / 3,
                mesh.faceCount());
        System.out.printf("[mesh-bench]   import    Java %8.2f ms   native %8.2f ms%n", javaImport, nativeImport);
        System.out.printf("[mesh-bench]   render    Java %8.2f ms   native %8.2f ms%n", javaRender, nativeRender);
        System.out.printf("[mesh-bench]   topology  Java %8.2f ms   native %8.2f ms (incl. Java query services)%n",
                javaTopo, nativeTopo);
        System.out.printf("[mesh-bench]   chain     Java %8.2f ms   native %8.2f ms (import+render+topology)%n",
                javaImport + javaRender + javaTopo, nativeImport + nativeRender + nativeTopo);
        System.out.printf("[mesh-bench]   derive (render+topology in one native pass) %8.2f ms%n", nativeDerive);
        System.out.printf("[mesh-bench]   drag 1 vertex: Java full rebuild %8.2f ms   native movePositions %8.3f ms%n",
                javaDrag, nativeDrag);
        System.out.printf("[mesh-bench]   EditableModel.loadMeshData native %8.2f ms (Java chain estimate %8.2f ms),"
                + " updateVertexPositions (full positions refresh) %8.2f ms%n", modelLoad, modelLoadJava, modelDrag);
    }
}
