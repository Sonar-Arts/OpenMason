package com.openmason.engine.modeling.meshparity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openmason.engine.modeling.editable.CendaMesh;
import com.openmason.engine.modeling.editable.EditableFace;
import com.openmason.engine.modeling.editable.EditableMesh;
import com.openmason.engine.modeling.editable.MeshImporter;
import com.openmason.engine.modeling.editable.PolygonTriangulator;
import com.openmason.engine.modeling.editable.RenderMesh;
import com.openmason.engine.modeling.editable.RenderMeshBuilder;
import com.openmason.engine.modeling.editable.VertexWelder;
import com.openmason.engine.modeling.meshparity.MeshPipelineCases.Kind;
import com.openmason.engine.modeling.meshparity.MeshPipelineCases.MeshCase;
import com.openmason.engine.modeling.meshparity.legacy.LegacyMeshImporter;
import com.openmason.engine.modeling.meshparity.legacy.LegacyPolygonTriangulator;
import com.openmason.engine.modeling.meshparity.legacy.LegacyRenderMeshBuilder;
import com.openmason.engine.modeling.meshparity.legacy.LegacyTopology;
import com.openmason.engine.modeling.meshparity.legacy.LegacyVertexWelder;
import com.openmason.engine.modeling.topology.MeshEdge;
import com.openmason.engine.modeling.topology.MeshFace;
import com.openmason.engine.modeling.topology.MeshTopology;
import com.openmason.engine.modeling.topology.MeshTopologyBuilder;
import com.openmason.engine.modeling.uv.FaceTextureManager;
import com.openmason.engine.modeling.uv.FaceTextureMapping;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Parity harness of the native mesh pipeline ({@code co_mesh_*}, OpenMason #175 F3; design in
 * {@code cenda/tests/fixtures/mesh_pipeline/README.md}).
 *
 * <p>The reference is the Java the native port replaced, kept VERBATIM in test scope
 * ({@code meshparity.legacy}: VertexWelder, MeshImporter, PolygonTriangulator, RenderMeshBuilder and the algorithmic
 * part of MeshTopologyBuilder / MeshGeometry). Chosen over frozen dump files: both paths stay alive in one JVM, so
 * the corpus can grow (new seeds, new fixture models) and drag frames can be generated without regenerating
 * goldens. Every comparison is bit for bit ({@code floatToRawIntBits}; NaN where Java has NaN), except the dihedral
 * angles (Java {@code Math.acos} vs the C library: 1 ulp).
 *
 * <ul>
 *   <li>{@link #fixturesMatchTheirHandTracesAndTheNativePipeline} (always): the 9 hand-traced fixtures - the frozen
 *       Java against their expectations, native and the production classes against the frozen Java.</li>
 *   <li>{@link #corpusParity} (opt-in {@code -Dopenmason.meshpipeline.parity=true}): every fixture OMO, seeded
 *       random soups and editable meshes; native arrays and the production classes vs the frozen Java.</li>
 *   <li>{@link #dragParity} (same opt-in): random vertex moves, native {@code co_mesh_move_vertices} and the
 *       production {@link RenderMeshBuilder.Derivation#movePositions} vs a frozen-Java full rebuild.</li>
 *   <li>{@code -Dopenmason.meshPipeline.dumpGoldens=<dir>}: writes the frozen Java's outputs of every case run as
 *       {@code <dir>/<name>.java.json} (floats as raw int bits).</li>
 * </ul>
 */
class MeshPipelineParityTest {

    static final boolean CORPUS = Boolean.getBoolean("openmason.meshpipeline.parity");
    static final String DUMP = System.getProperty("openmason.meshPipeline.dumpGoldens");

    // ── Legacy run ──────────────────────────────────────────────────────────

    /** The frozen Java's outputs of one case. weld* null when the case has no weld; topo null for empty meshes. */
    record Legacy(int[] soupToVertex, float[] weldPositions, int[][] vertexToSoup, EditableMesh mesh,
                  List<LegacyMeshImporter.ImportWarning> warnings, FaceTextureManager ftm, RenderMesh rm,
                  LegacyTopology topo) {
    }

    static Legacy runLegacy(MeshCase c) {
        if (c.kind() == Kind.WELD) {
            LegacyVertexWelder.WeldResult w = LegacyVertexWelder.weld(c.vertices(), c.epsilon());
            return new Legacy(w.soupToWelded(), w.weldedPositions(), group(w.soupToWelded(), w.weldedCount()),
                    null, null, null, null, null);
        }
        int[] soupToVertex = null;
        float[] weldPositions = null;
        int[][] vertexToSoup = null;
        EditableMesh mesh;
        List<LegacyMeshImporter.ImportWarning> warnings = new ArrayList<>();
        if (c.kind() == Kind.SOUP) {
            if (c.vertices() != null && c.vertices().length >= 9 && c.indices() != null && c.indices().length >= 3) {
                LegacyVertexWelder.WeldResult w = LegacyVertexWelder.weld(c.vertices());
                soupToVertex = w.soupToWelded();
                weldPositions = w.weldedPositions();
            }
            LegacyMeshImporter.ImportResult r = LegacyMeshImporter.importSoup(c.vertices(), c.texCoords(),
                    c.indices(), c.triFace(), warnings::add);
            mesh = r.mesh();
            vertexToSoup = r.vertexIdToSoupIndices();
        } else {
            mesh = c.editableMesh();
        }
        FaceTextureManager ftm = c.textureManager();
        RenderMesh rm = LegacyRenderMeshBuilder.build(mesh, ftm);
        return new Legacy(soupToVertex, weldPositions, vertexToSoup, mesh, warnings, ftm, rm,
                LegacyTopology.build(mesh, rm));
    }

    static int[][] group(int[] soupToVertex, int count) {
        int[] sizes = new int[count];
        for (int v : soupToVertex) sizes[v]++;
        int[][] out = new int[count][];
        for (int v = 0; v < count; v++) out[v] = new int[sizes[v]];
        int[] fill = new int[count];
        for (int s = 0; s < soupToVertex.length; s++) out[soupToVertex[s]][fill[soupToVertex[s]]++] = s;
        return out;
    }

    private static final Pattern FIRST_INT = Pattern.compile("(\\d+)");

    /** (kind ordinal, face id, value) per warning; value = the first number of the message (counts). */
    static int[][] warningTriples(List<LegacyMeshImporter.ImportWarning> warnings) {
        int[][] out = new int[warnings.size()][];
        for (int i = 0; i < out.length; i++) {
            LegacyMeshImporter.ImportWarning w = warnings.get(i);
            Matcher m = FIRST_INT.matcher(w.message());
            out[i] = new int[]{w.kind().ordinal(), w.faceId(), m.find() ? Integer.parseInt(m.group(1)) : -1};
        }
        return out;
    }

    // ── Report ──────────────────────────────────────────────────────────────

    static final class Report {
        int cases;
        long arrays;
        long elements;
        long mismatchCount;
        final List<String> mismatches = new ArrayList<>();

        void miss(String ctx, String array, int index, String java, String nat) {
            mismatchCount++;
            if (mismatches.size() < 200) {
                mismatches.add(ctx + " | " + array + "[" + index + "] java=" + java + " native=" + nat);
            }
        }

        void ints(String ctx, String array, int[] java, int[] nat) {
            arrays++;
            if (java.length != nat.length) {
                miss(ctx, array + ".length", -1, "" + java.length, "" + nat.length);
                return;
            }
            elements += java.length;
            for (int i = 0; i < java.length; i++) {
                if (java[i] != nat[i]) {
                    miss(ctx, array, i, "" + java[i], "" + nat[i]);
                    return;
                }
            }
        }

        void floats(String ctx, String array, float[] java, float[] nat, int ulps) {
            arrays++;
            if (java.length != nat.length) {
                miss(ctx, array + ".length", -1, "" + java.length, "" + nat.length);
                return;
            }
            elements += java.length;
            for (int i = 0; i < java.length; i++) {
                if (!sameFloat(java[i], nat[i], ulps)) {
                    miss(ctx, array, i, fstr(java[i]), fstr(nat[i]));
                    return;
                }
            }
        }

        void csr(String ctx, String array, int[][] java, int[][] nat) {
            arrays++;
            if (java.length != nat.length) {
                miss(ctx, array + ".rows", -1, "" + java.length, "" + nat.length);
                return;
            }
            for (int r = 0; r < java.length; r++) {
                elements += java[r].length;
                if (!Arrays.equals(java[r], nat[r])) {
                    miss(ctx, array, r, Arrays.toString(java[r]), Arrays.toString(nat[r]));
                    return;
                }
            }
        }

        void check(String ctx, String what, boolean ok, String java, String nat) {
            arrays++;
            elements++;
            if (!ok) miss(ctx, what, -1, java, nat);
        }

        String summary() {
            return cases + " cases, " + arrays + " arrays, " + elements + " elements compared, " + mismatchCount
                    + " mismatches" + (mismatches.isEmpty() ? "" : ":\n  " + String.join("\n  ", mismatches));
        }
    }

    static boolean sameFloat(float a, float b, int ulps) {
        if (Float.isNaN(a) || Float.isNaN(b)) return Float.isNaN(a) && Float.isNaN(b);
        int ia = Float.floatToRawIntBits(a), ib = Float.floatToRawIntBits(b);
        if (ia == ib) return true;
        if (ulps == 0 || (ia < 0) != (ib < 0)) return false;
        return Math.abs((long) ia - ib) <= ulps;
    }

    static String fstr(float f) {
        return f + "(0x" + Integer.toHexString(Float.floatToRawIntBits(f)) + ")";
    }

    // ── Native vs frozen Java ───────────────────────────────────────────────

    static float[] meshPositions(EditableMesh mesh) {
        float[] out = new float[mesh.vertexCount() * 3];
        Vector3f p = new Vector3f();
        for (int v = 0; v < mesh.vertexCount(); v++) {
            mesh.position(v, p);
            out[3 * v] = p.x;
            out[3 * v + 1] = p.y;
            out[3 * v + 2] = p.z;
        }
        return out;
    }

    /** Native = co_mesh_import / co_mesh_set_faces + every stage, on the case's own input. */
    static CendaMesh runNative(MeshCase c) {
        CendaMesh cm = CendaMesh.create();
        if (c.kind() == Kind.WELD) {
            cm.weld(c.vertices(), c.epsilon());
            return cm;
        }
        int n = c.mappings().size();
        int[] ids = new int[n];
        for (int i = 0; i < n; i++) ids[i] = c.mappings().get(i).faceId();
        cm.setMappings(ids, c.mappings().toArray(new FaceTextureMapping[0]), n);
        if (c.kind() == Kind.SOUP) {
            cm.importSoup(c.vertices(), c.texCoords(), c.indices(), c.triFace(), 1e-4f, CendaMesh.STAGE_ALL);
        } else {
            cm.setFaces(c.editableMesh(), CendaMesh.STAGE_ALL & ~CendaMesh.STAGE_WELD);
        }
        return cm;
    }

    static void compareNative(String ctx, Legacy l, CendaMesh cm, Report r) {
        CendaMesh.Info info = cm.info();
        if (l.soupToVertex() != null) {
            r.ints(ctx, "SOUP_TO_VERTEX", l.soupToVertex(), cm.ints(CendaMesh.A_SOUP_TO_VERTEX));
            r.csr(ctx, "VERTEX_SOUP", l.vertexToSoup(),
                    cm.csr(CendaMesh.A_VERTEX_SOUP_OFFSETS, CendaMesh.A_VERTEX_SOUP));
            r.floats(ctx, "VERTEX_POSITIONS(weld)", l.weldPositions(), cm.floats(CendaMesh.A_VERTEX_POSITIONS), 0);
        }
        if (l.mesh() == null) return;
        EditableMesh mesh = l.mesh();
        r.check(ctx, "vertexCount", info.vertexCount() == mesh.vertexCount(), "" + mesh.vertexCount(),
                "" + info.vertexCount());
        r.check(ctx, "faceIdUpperBound", info.faceIdUpperBound() == mesh.faceIdUpperBound(),
                "" + mesh.faceIdUpperBound(), "" + info.faceIdUpperBound());
        r.floats(ctx, "VERTEX_POSITIONS", meshPositions(mesh), cm.floats(CendaMesh.A_VERTEX_POSITIONS), 0);
        int fc = mesh.faceCount();
        int[] faceIds = new int[fc];
        int[][] loops = new int[fc][];
        int f = 0;
        for (EditableFace face : mesh.faces()) {
            faceIds[f] = face.faceId();
            loops[f++] = face.loop();
        }
        r.ints(ctx, "FACE_IDS", faceIds, cm.ints(CendaMesh.A_FACE_IDS));
        r.csr(ctx, "LOOPS", loops, cm.csr(CendaMesh.A_LOOP_OFFSETS, CendaMesh.A_LOOP_VERTICES));
        int[] flags = cm.ints(CendaMesh.A_FACE_FLAGS);
        float[] loopUVs = cm.floats(CendaMesh.A_LOOP_UVS);
        int[] offsets = cm.ints(CendaMesh.A_LOOP_OFFSETS);
        f = 0;
        for (EditableFace face : mesh.faces()) {
            boolean authored = f < flags.length && (flags[f] & CendaMesh.FACE_AUTHORED_UVS) != 0;
            r.check(ctx, "FACE_FLAGS.authored[" + f + "]", authored == face.hasAuthoredUVs(),
                    "" + face.hasAuthoredUVs(), "" + authored);
            if (face.hasAuthoredUVs() && f + 1 < offsets.length) {
                r.floats(ctx, "LOOP_UVS(face " + face.faceId() + ")", face.cornerUVs(),
                        Arrays.copyOfRange(loopUVs, 2 * offsets[f], 2 * offsets[f + 1]), 0);
            }
            f++;
        }
        if (l.warnings() != null && l.soupToVertex() != null) {
            int[][] java = warningTriples(l.warnings());
            int[] nat = cm.ints(CendaMesh.A_WARNINGS);
            int[][] natRows = new int[nat.length / 3][];
            for (int i = 0; i < natRows.length; i++) natRows[i] = Arrays.copyOfRange(nat, 3 * i, 3 * i + 3);
            r.csr(ctx, "WARNINGS", java, natRows);
        }
        RenderMesh rm = l.rm();
        r.ints(ctx, "TRI_INDICES", rm.indices(), cm.ints(CendaMesh.A_TRI_INDICES));
        r.ints(ctx, "TRI_FACE_IDS", rm.triangleToFaceId(), cm.ints(CendaMesh.A_TRI_FACE_IDS));
        r.floats(ctx, "CORNER_POSITIONS", rm.vertices(), cm.floats(CendaMesh.A_CORNER_POSITIONS), 0);
        r.floats(ctx, "CORNER_UVS", rm.texCoords(), cm.floats(CendaMesh.A_CORNER_UVS), 0);
        r.ints(ctx, "LOOP_VERTICES(cornerToVertexId)", rm.cornerToVertexId(), cm.ints(CendaMesh.A_LOOP_VERTICES));
        r.csr(ctx, "VERTEX_CORNERS", rm.vertexIdToCorners(),
                cm.csr(CendaMesh.A_VERTEX_CORNER_OFFSETS, CendaMesh.A_VERTEX_CORNERS));
        compareNativeTopology(ctx, l, cm, info, r);
    }

    static void compareNativeTopology(String ctx, Legacy l, CendaMesh cm, CendaMesh.Info info, Report r) {
        LegacyTopology t = l.topo();
        if (t == null) {
            r.check(ctx, "edgeCount(empty)", info.edgeCount() == 0, "0", "" + info.edgeCount());
            return;
        }
        int[] ev = cm.ints(CendaMesh.A_EDGE_VERTICES);
        int[] javaEv = new int[t.edges().length * 2];
        for (int e = 0; e < t.edges().length; e++) {
            javaEv[2 * e] = t.edges()[e][0];
            javaEv[2 * e + 1] = t.edges()[e][1];
        }
        r.ints(ctx, "EDGE_VERTICES", javaEv, ev);
        r.csr(ctx, "EDGE_FACES", t.edgeFaces(), cm.csr(CendaMesh.A_EDGE_FACE_OFFSETS, CendaMesh.A_EDGE_FACES));
        r.ints(ctx, "CORNER_EDGES", concat(t.faceEdges()), cm.ints(CendaMesh.A_CORNER_EDGES));
        r.csr(ctx, "VERTEX_EDGES", t.vertexEdges(),
                cm.csr(CendaMesh.A_VERTEX_EDGE_OFFSETS, CendaMesh.A_VERTEX_EDGES));
        r.csr(ctx, "VERTEX_FACES", t.vertexFaces(),
                cm.csr(CendaMesh.A_VERTEX_FACE_OFFSETS, CendaMesh.A_VERTEX_FACES));
        int[][] adj = cm.csr(CendaMesh.A_FACE_ADJ_OFFSETS, CendaMesh.A_FACE_ADJ);
        r.csr(ctx, "FACE_ADJ", t.faceAdjacent(), adj);
        int[][] adjEdges = cm.csr(CendaMesh.A_FACE_ADJ_OFFSETS, CendaMesh.A_FACE_ADJ_EDGES);
        int[][] javaAdjEdges = new int[t.faceAdjacent().length][];
        for (int fid = 0; fid < javaAdjEdges.length; fid++) {
            int[] row = t.faceAdjacent()[fid];
            javaAdjEdges[fid] = new int[row.length];
            for (int i = 0; i < row.length; i++) {
                Integer e = t.facePairToEdgeId().get(LegacyTopology.canonicalKey(fid, row[i]));
                javaAdjEdges[fid][i] = e == null ? -1 : e;
            }
        }
        r.csr(ctx, "FACE_ADJ_EDGES", javaAdjEdges, adjEdges);
        int ub = t.faceAreas().length;
        float[] normals = cm.floats(CendaMesh.A_FACE_NORMALS);
        float[] centroids = cm.floats(CendaMesh.A_FACE_CENTROIDS);
        r.check(ctx, "FACE_NORMALS.length", normals.length == 3 * ub, "" + 3 * ub, "" + normals.length);
        r.check(ctx, "FACE_CENTROIDS.length", centroids.length == 3 * ub, "" + 3 * ub, "" + centroids.length);
        if (normals.length == 3 * ub && centroids.length == 3 * ub) {
            List<Float> jn = new ArrayList<>(), nn = new ArrayList<>(), jc = new ArrayList<>(), nc = new ArrayList<>();
            for (int fid : t.faceIds()) {
                Vector3f n = t.faceNormals()[fid], c = t.faceCentroids()[fid];
                jn.addAll(List.of(n.x, n.y, n.z));
                jc.addAll(List.of(c.x, c.y, c.z));
                for (int k = 0; k < 3; k++) {
                    nn.add(normals[3 * fid + k]);
                    nc.add(centroids[3 * fid + k]);
                }
            }
            r.floats(ctx, "FACE_NORMALS", toArray(jn), toArray(nn), 0);
            r.floats(ctx, "FACE_CENTROIDS", toArray(jc), toArray(nc), 0);
        }
        r.floats(ctx, "FACE_AREAS", t.faceAreas(), cm.floats(CendaMesh.A_FACE_AREAS), 0);
        r.floats(ctx, "EDGE_DIHEDRAL", t.dihedral(), cm.floats(CendaMesh.A_EDGE_DIHEDRAL), 1);
        r.ints(ctx, "EDGE_KIND", t.edgeKinds(), cm.ints(CendaMesh.A_EDGE_KIND));
        r.check(ctx, "uniform", info.uniform() == t.uniform(), "" + t.uniform(), "" + info.uniform());
        r.check(ctx, "firstFaceVertexCount", info.firstFaceVertexCount() == t.firstCount(), "" + t.firstCount(),
                "" + info.firstFaceVertexCount());
        for (int e = 0; e < t.edges().length; e += Math.max(1, t.edges().length / 64)) {
            int found = cm.findEdge(t.edges()[e][1], t.edges()[e][0]);
            r.check(ctx, "findEdge(" + e + ")", found == e, "" + e, "" + found);
        }
    }

    static int[] concat(int[][] rows) {
        int n = 0;
        for (int[] row : rows) n += row.length;
        int[] out = new int[n];
        int i = 0;
        for (int[] row : rows) {
            System.arraycopy(row, 0, out, i, row.length);
            i += row.length;
        }
        return out;
    }

    static float[] toArray(List<Float> list) {
        float[] out = new float[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i);
        return out;
    }

    // ── Production classes vs frozen Java ───────────────────────────────────

    static void compareProduction(String ctx, MeshCase c, Legacy l, Report r) {
        if (c.kind() == Kind.WELD) {
            VertexWelder.WeldResult w = VertexWelder.weld(c.vertices(), c.epsilon());
            r.ints(ctx, "prod VertexWelder.soupToWelded", l.soupToVertex(), w.soupToWelded());
            r.floats(ctx, "prod VertexWelder.weldedPositions", l.weldPositions(), w.weldedPositions(), 0);
            r.check(ctx, "prod VertexWelder.weldedCount", w.weldedCount() == l.vertexToSoup().length,
                    "" + l.vertexToSoup().length, "" + w.weldedCount());
            return;
        }
        if (c.kind() == Kind.SOUP) {
            List<MeshImporter.ImportWarning> warnings = new ArrayList<>();
            MeshImporter.ImportResult imp = MeshImporter.importSoup(c.vertices(), c.texCoords(), c.indices(),
                    c.triFace(), warnings::add);
            compareMeshes(ctx, l.mesh(), imp.mesh(), r);
            r.csr(ctx, "prod ImportResult.vertexIdToSoupIndices", l.vertexToSoup(), imp.vertexIdToSoupIndices());
            List<String> jw = l.warnings().stream().map(w -> w.faceId() + "/" + w.kind() + "/" + w.message()).toList();
            List<String> pw = warnings.stream().map(w -> w.faceId() + "/" + w.kind() + "/" + w.message()).toList();
            r.check(ctx, "prod ImportWarnings", jw.equals(pw), jw.toString(), pw.toString());
        }
        RenderMesh prm = RenderMeshBuilder.build(l.mesh(), l.ftm());
        compareRenderMeshes(ctx, "prod RenderMeshBuilder", l.rm(), prm, r);
        MeshTopology pt = MeshTopologyBuilder.build(l.mesh(), prm);
        compareTopology(ctx, "prod MeshTopology", l, pt, r);
        try (RenderMeshBuilder.Derivation d = RenderMeshBuilder.derive(l.mesh(), l.ftm())) {
            compareRenderMeshes(ctx, "prod derive.renderMesh", l.rm(), d.renderMesh(), r);
            compareTopology(ctx, "prod derive.topology", l, d.topology(), r);
        }
        // PolygonTriangulator + authoredUVsMatchProjection per face (capped).
        int checked = 0;
        for (EditableFace face : l.mesh().faces()) {
            if (checked++ >= 64) break;
            Vector3f[] loop = new Vector3f[face.loopLength()];
            for (int i = 0; i < loop.length; i++) loop[i] = l.mesh().position(face.vertexAt(i));
            r.ints(ctx, "prod PolygonTriangulator.triangulate(face " + face.faceId() + ")",
                    LegacyPolygonTriangulator.triangulate(loop.clone()), PolygonTriangulator.triangulate(loop));
            Vector3f jn = LegacyPolygonTriangulator.newellNormal(loop), pn = PolygonTriangulator.newellNormal(loop);
            r.floats(ctx, "prod PolygonTriangulator.newellNormal(face " + face.faceId() + ")",
                    new float[]{jn.x, jn.y, jn.z}, new float[]{pn.x, pn.y, pn.z}, 0);
            if (face.hasAuthoredUVs()) {
                FaceTextureMapping m = l.ftm().getFaceMapping(face.faceId());
                for (float tol : new float[]{0f, 1e-4f}) {
                    boolean j = LegacyRenderMeshBuilder.authoredUVsMatchProjection(l.mesh(), face, m, tol);
                    boolean p = RenderMeshBuilder.authoredUVsMatchProjection(l.mesh(), face, m, tol);
                    r.check(ctx, "prod authoredUVsMatchProjection(face " + face.faceId() + ", " + tol + ")", j == p,
                            "" + j, "" + p);
                }
            }
        }
    }

    static void compareMeshes(String ctx, EditableMesh java, EditableMesh prod, Report r) {
        r.check(ctx, "prod mesh.vertexCount", java.vertexCount() == prod.vertexCount(), "" + java.vertexCount(),
                "" + prod.vertexCount());
        r.check(ctx, "prod mesh.faceIdUpperBound", java.faceIdUpperBound() == prod.faceIdUpperBound(),
                "" + java.faceIdUpperBound(), "" + prod.faceIdUpperBound());
        if (java.vertexCount() == prod.vertexCount()) {
            r.floats(ctx, "prod mesh.positions", meshPositions(java), meshPositions(prod), 0);
        }
        List<EditableFace> jf = new ArrayList<>(java.faces()), pf = new ArrayList<>(prod.faces());
        r.check(ctx, "prod mesh.faceCount", jf.size() == pf.size(), "" + jf.size(), "" + pf.size());
        for (int i = 0; i < Math.min(jf.size(), pf.size()); i++) {
            EditableFace a = jf.get(i), b = pf.get(i);
            r.check(ctx, "prod mesh.face[" + i + "].id", a.faceId() == b.faceId(), "" + a.faceId(), "" + b.faceId());
            r.ints(ctx, "prod mesh.face[" + i + "].loop", a.loop(), b.loop());
            r.check(ctx, "prod mesh.face[" + i + "].hasUVs", a.hasAuthoredUVs() == b.hasAuthoredUVs(),
                    "" + a.hasAuthoredUVs(), "" + b.hasAuthoredUVs());
            if (a.hasAuthoredUVs() && b.hasAuthoredUVs()) {
                r.floats(ctx, "prod mesh.face[" + i + "].cornerUVs", a.cornerUVs(), b.cornerUVs(), 0);
            }
        }
    }

    static void compareRenderMeshes(String ctx, String what, RenderMesh j, RenderMesh p, Report r) {
        r.floats(ctx, what + ".vertices", j.vertices(), p.vertices(), 0);
        r.floats(ctx, what + ".texCoords", j.texCoords(), p.texCoords(), 0);
        r.ints(ctx, what + ".indices", j.indices(), p.indices());
        r.ints(ctx, what + ".triangleToFaceId", j.triangleToFaceId(), p.triangleToFaceId());
        r.ints(ctx, what + ".cornerToVertexId", j.cornerToVertexId(), p.cornerToVertexId());
        r.csr(ctx, what + ".vertexIdToCorners", j.vertexIdToCorners(), p.vertexIdToCorners());
    }

    static void compareTopology(String ctx, String what, Legacy l, MeshTopology p, Report r) {
        LegacyTopology t = l.topo();
        if (t == null || p == null) {
            r.check(ctx, what + " null", t == null && p == null, "" + (t == null), "" + (p == null));
            return;
        }
        int ec = t.edges().length;
        r.check(ctx, what + ".edgeCount", p.getEdgeCount() == ec, "" + ec, "" + p.getEdgeCount());
        if (p.getEdgeCount() != ec) return;
        int[] ev = new int[2 * ec], kinds = new int[ec];
        int[][] ef = new int[ec][];
        float[] dihedral = new float[ec];
        int[] byVertices = new int[ec], javaIds = new int[ec];
        for (int e = 0; e < ec; e++) {
            MeshEdge edge = p.getEdge(e);
            ev[2 * e] = edge.vertexA();
            ev[2 * e + 1] = edge.vertexB();
            ef[e] = edge.adjacentFaceIds();
            kinds[e] = p.getEdgeKind(e).ordinal();
            dihedral[e] = p.getDihedralAngle(e);
            MeshEdge bv = p.getEdgeByVertices(edge.vertexB(), edge.vertexA());
            byVertices[e] = bv == null ? -1 : bv.edgeId();
            javaIds[e] = e;
        }
        r.ints(ctx, what + ".edges", concat(t.edges()), ev);
        r.csr(ctx, what + ".edgeFaces", t.edgeFaces(), ef);
        r.ints(ctx, what + ".edgeKinds", t.edgeKinds(), kinds);
        r.floats(ctx, what + ".dihedral", t.dihedral(), dihedral, 1);
        r.ints(ctx, what + ".getEdgeByVertices", javaIds, byVertices);
        int ub = t.faceAreas().length;
        r.check(ctx, what + ".faceCount", p.getFaceCount() == ub, "" + ub, "" + p.getFaceCount());
        int[][] loops = new int[t.faceIds().length][];
        int[][] faceEdges = new int[t.faceIds().length][];
        List<Float> jg = new ArrayList<>(), pg = new ArrayList<>();
        for (int s = 0; s < t.faceIds().length; s++) {
            int fid = t.faceIds()[s];
            MeshFace mf = p.getFace(fid);
            loops[s] = mf == null ? new int[0] : mf.vertexIndices();
            faceEdges[s] = mf == null ? new int[0] : mf.edgeIds();
            Vector3f jn = t.faceNormals()[fid], jc = t.faceCentroids()[fid];
            Vector3f pn = p.getFaceNormal(fid), pc = p.getFaceCentroid(fid);
            jg.addAll(List.of(jn.x, jn.y, jn.z, jc.x, jc.y, jc.z, t.faceAreas()[fid]));
            pg.addAll(List.of(pn.x, pn.y, pn.z, pc.x, pc.y, pc.z, p.getFaceArea(fid)));
        }
        int[][] javaLoops = new int[t.faceIds().length][];
        int s0 = 0;
        for (EditableFace face : l.mesh().faces()) javaLoops[s0++] = face.loop();
        r.csr(ctx, what + ".faces.loop", javaLoops, loops);
        r.csr(ctx, what + ".faces.edgeIds", t.faceEdges(), faceEdges);
        r.floats(ctx, what + ".faceGeometry", toArray(jg), toArray(pg), 0);
        int gapNormals = 0;
        for (int fid = 0; fid < ub; fid++) {
            if (t.faceNormals()[fid] == null && p.getFaceNormal(fid) != null) gapNormals++;
        }
        r.check(ctx, what + ".gapNormalsNull", gapNormals == 0, "0", "" + gapNormals);
        int vc = t.vertexEdges().length;
        int[][] ve = new int[vc][], vf = new int[vc][];
        for (int v = 0; v < vc; v++) {
            ve[v] = p.getEdgesForVertex(v).stream().mapToInt(Integer::intValue).toArray();
            vf[v] = p.getFacesForVertex(v).stream().mapToInt(Integer::intValue).toArray();
        }
        r.csr(ctx, what + ".edgesForVertex", t.vertexEdges(), ve);
        r.csr(ctx, what + ".facesForVertex", t.vertexFaces(), vf);
        int[][] adj = new int[ub][], shared = new int[ub][], javaShared = new int[ub][];
        for (int fid = 0; fid < ub; fid++) {
            adj[fid] = p.getAdjacentFaces(fid).stream().mapToInt(Integer::intValue).toArray();
            int[] row = t.faceAdjacent()[fid];
            shared[fid] = new int[row.length];
            javaShared[fid] = new int[row.length];
            for (int i = 0; i < row.length; i++) {
                MeshEdge se = p.getSharedEdge(fid, row[i]);
                shared[fid][i] = se == null ? -1 : se.edgeId();
                Integer je = t.facePairToEdgeId().get(LegacyTopology.canonicalKey(fid, row[i]));
                javaShared[fid][i] = je == null ? -1 : je;
            }
        }
        r.csr(ctx, what + ".adjacentFaces", t.faceAdjacent(), adj);
        r.csr(ctx, what + ".sharedEdge", javaShared, shared);
        r.check(ctx, what + ".uniform", p.isUniformTopology() == t.uniform() &&
                p.getUniformVerticesPerFace() == (t.uniform() ? t.firstCount() : -1), t.uniform() + "/" + t.firstCount(),
                p.isUniformTopology() + "/" + p.getUniformVerticesPerFace());
        RenderMesh rm = l.rm();
        int cc = rm.cornerCount();
        int[] c2v = new int[cc];
        for (int c = 0; c < cc; c++) c2v[c] = p.getUniqueIndexForMeshVertex(c);
        r.ints(ctx, what + ".uniqueIndexForMeshVertex", rm.cornerToVertexId(), c2v);
        int[][] v2c = new int[l.mesh().vertexCount()][];
        for (int v = 0; v < v2c.length; v++) v2c[v] = p.getMeshIndicesForUniqueVertex(v);
        r.csr(ctx, what + ".meshIndicesForUniqueVertex", rm.vertexIdToCorners(), v2c);
        int tc = rm.triangleCount();
        int[] t2f = new int[tc];
        for (int i = 0; i < tc; i++) t2f[i] = p.getOriginalFaceIdForTriangle(i);
        r.ints(ctx, what + ".originalFaceIdForTriangle", rm.triangleToFaceId(), t2f);
        r.check(ctx, what + ".counts", p.getTriangleCount() == tc && p.getUniqueVertexCount() == v2c.length,
                tc + "/" + v2c.length, p.getTriangleCount() + "/" + p.getUniqueVertexCount());
    }

    // ── Runs ────────────────────────────────────────────────────────────────

    static void runCase(MeshCase c, Report r, boolean production) {
        r.cases++;
        String ctx = c.name();
        Legacy l;
        try {
            l = runLegacy(c);
        } catch (RuntimeException e) {
            r.check(ctx, "legacy run", false, e.toString(), "-");
            return;
        }
        dump(c, l);
        try (CendaMesh cm = runNative(c)) {
            compareNative(ctx, l, cm, r);
        } catch (RuntimeException e) {
            r.check(ctx, "native run", false, "-", e.toString());
        }
        if (production) {
            try {
                compareProduction(ctx, c, l, r);
            } catch (RuntimeException e) {
                r.check(ctx, "production run", false, "-", e.toString());
            }
        }
    }

    @Test
    void fixturesMatchTheirHandTracesAndTheNativePipeline() throws IOException {
        List<MeshCase> fixtures = MeshPipelineCases.fixtures();
        assertTrue(fixtures.size() >= 9, "fixtures: " + fixtures.size());
        Report expected = new Report();
        Report r = new Report();
        for (MeshCase c : fixtures) {
            Legacy l = runLegacy(c);
            FixtureExpectations.check(c, l, expected);
            runCase(c, r, true);
        }
        System.out.println("[mesh-parity] fixtures vs hand traces: " + expected.summary());
        System.out.println("[mesh-parity] fixtures native/production vs frozen Java: " + r.summary());
        assertTrue(expected.mismatchCount == 0, expected.summary());
        assertTrue(r.mismatchCount == 0, r.summary());
    }

    @Test
    void corpusParity() throws IOException {
        assumeTrue(CORPUS, "opt-in: -Dopenmason.meshpipeline.parity=true");
        List<MeshCase> corpus = new ArrayList<>();
        corpus.addAll(MeshPipelineCases.omoCorpus());
        int omo = corpus.size();
        corpus.addAll(MeshPipelineCases.randomSoups(0x5EED175L, 400));
        corpus.addAll(MeshPipelineCases.randomEditables(0xF3F3L, 400));
        Report r = new Report();
        long t0 = System.nanoTime();
        for (MeshCase c : corpus) runCase(c, r, true);
        System.out.printf("[mesh-parity] corpus (%d OMO cases + %d generated) in %.1f s: %s%n", omo,
                corpus.size() - omo, (System.nanoTime() - t0) / 1e9, r.summary());
        assertTrue(r.mismatchCount == 0, r.summary());
    }

    @Test
    void dragParity() throws IOException {
        assumeTrue(CORPUS, "opt-in: -Dopenmason.meshpipeline.parity=true");
        List<MeshCase> cases = new ArrayList<>();
        cases.addAll(MeshPipelineCases.randomEditables(0xD4A6L, 150));
        cases.addAll(MeshPipelineCases.randomSoups(0xD4A7L, 100));
        cases.addAll(MeshPipelineCases.fixtures().stream().filter(c -> c.kind() != Kind.WELD).toList());
        List<MeshCase> omo = MeshPipelineCases.omoCorpus();
        for (int i = 0; i < omo.size(); i += 7) cases.add(omo.get(i));
        Report r = new Report();
        SplittableRandom rnd = new SplittableRandom(0xD2A6);
        int frames = 0;
        for (MeshCase c : cases) {
            Legacy l0 = runLegacy(c);
            if (l0.mesh() == null || l0.mesh().faceCount() == 0) continue;
            r.cases++;
            frames += DragParity.run(c, l0, rnd.split(), r);
        }
        System.out.println("[mesh-parity] drag: " + frames + " frames, " + r.summary());
        assertTrue(r.mismatchCount == 0, r.summary());
    }

    // ── Golden dumps ────────────────────────────────────────────────────────

    static void dump(MeshCase c, Legacy l) {
        if (DUMP == null || DUMP.isBlank()) return;
        try {
            ObjectNode root = MeshPipelineCases.JSON.createObjectNode();
            root.put("format", "openmason.meshPipelineJavaDump").put("version", 1).put("name", c.name());
            root.put("floats", "raw int bits (Float.floatToRawIntBits)");
            if (l.soupToVertex() != null) {
                ObjectNode w = root.putObject("weld");
                ints(w.putArray("soupToVertex"), l.soupToVertex());
                bits(w.putArray("positions"), l.weldPositions());
                rows(w.putArray("vertexToSoup"), l.vertexToSoup());
            }
            if (l.mesh() != null) {
                ObjectNode f = root.putObject("faces");
                ArrayNode faces = f.putArray("faces");
                for (EditableFace face : l.mesh().faces()) {
                    ObjectNode o = faces.addObject().put("id", face.faceId());
                    ints(o.putArray("loop"), face.loop());
                    if (face.hasAuthoredUVs()) bits(o.putArray("cornerUVs"), face.cornerUVs());
                    else o.putNull("cornerUVs");
                }
                f.put("faceIdUpperBound", l.mesh().faceIdUpperBound());
                bits(f.putArray("positions"), meshPositions(l.mesh()));
                rows(f.putArray("warnings"), warningTriples(l.warnings()));
                RenderMesh rm = l.rm();
                ObjectNode rn = root.putObject("render");
                bits(rn.putArray("vertices"), rm.vertices());
                bits(rn.putArray("texCoords"), rm.texCoords());
                ints(rn.putArray("indices"), rm.indices());
                ints(rn.putArray("triangleToFaceId"), rm.triangleToFaceId());
                ints(rn.putArray("cornerToVertexId"), rm.cornerToVertexId());
                rows(rn.putArray("vertexIdToCorners"), rm.vertexIdToCorners());
                LegacyTopology t = l.topo();
                if (t != null) {
                    ObjectNode tn = root.putObject("topology");
                    rows(tn.putArray("edges"), t.edges());
                    rows(tn.putArray("edgeFaces"), t.edgeFaces());
                    rows(tn.putArray("faceEdges"), t.faceEdges());
                    rows(tn.putArray("vertexEdges"), t.vertexEdges());
                    rows(tn.putArray("vertexFaces"), t.vertexFaces());
                    rows(tn.putArray("faceAdjacency"), t.faceAdjacent());
                    ArrayNode geo = tn.putArray("faceGeometry");
                    for (int fid : t.faceIds()) {
                        Vector3f n = t.faceNormals()[fid], ce = t.faceCentroids()[fid];
                        bits(geo.addArray(), new float[]{fid, n.x, n.y, n.z, ce.x, ce.y, ce.z, t.faceAreas()[fid]});
                    }
                    bits(tn.putArray("edgeDihedral"), t.dihedral());
                    ints(tn.putArray("edgeKinds"), t.edgeKinds());
                }
            }
            Path dir = Path.of(DUMP);
            Files.createDirectories(dir);
            String file = c.name().replaceAll("[^A-Za-z0-9._-]", "_") + ".java.json";
            MeshPipelineCases.JSON.writeValue(dir.resolve(file).toFile(), root);
        } catch (IOException e) {
            throw new IllegalStateException("dump " + c.name(), e);
        }
    }

    private static void ints(ArrayNode a, int[] v) {
        for (int x : v) a.add(x);
    }

    private static void bits(ArrayNode a, float[] v) {
        for (float x : v) a.add(Float.floatToRawIntBits(x));
    }

    private static void rows(ArrayNode a, int[][] v) {
        for (int[] row : v) ints(a.addArray(), row);
    }

    /** Fixture expectation check (README format). */
    static final class FixtureExpectations {

        /** Expected arrays absent from a fixture are not checked. */
        static void exInts(Report r, String ctx, String array, int[] expected, int[] java) {
            if (expected != null) r.ints(ctx, array, expected, java == null ? new int[0] : java);
        }

        static void exCsr(Report r, String ctx, String array, int[][] expected, int[][] java) {
            if (expected != null) r.csr(ctx, array, expected, java == null ? new int[0][] : java);
        }

        static void check(MeshCase c, Legacy l, Report r) {
            JsonNode exp = c.expected();
            if (exp == null) return;
            String ctx = c.name() + " (hand trace)";
            float tol = c.tolerance();
            r.cases++;
            if (exp.has("weld")) {
                JsonNode w = exp.get("weld");
                exInts(r, ctx, "weld.soupToVertex", MeshPipelineCases.ints(w.get("soupToVertex")), l.soupToVertex());
                approx(ctx, "weld.positions", MeshPipelineCases.floats(w.get("positions")), l.weldPositions(), tol, r);
                exCsr(r, ctx, "weld.vertexToSoup", rowsOf(w.get("vertexToSoup")), l.vertexToSoup());
            }
            if (exp.has("faces")) {
                JsonNode f = exp.get("faces");
                List<EditableFace> faces = new ArrayList<>(l.mesh().faces());
                JsonNode ef = f.get("faces");
                r.check(ctx, "faces.count", ef.size() == faces.size(), "" + ef.size(), "" + faces.size());
                for (int i = 0; i < Math.min(ef.size(), faces.size()); i++) {
                    JsonNode e = ef.get(i);
                    EditableFace face = faces.get(i);
                    r.check(ctx, "faces[" + i + "].id", e.get("id").asInt() == face.faceId(), e.get("id").asText(),
                            "" + face.faceId());
                    exInts(r, ctx, "faces[" + i + "].loop", MeshPipelineCases.ints(e.get("loop")), face.loop());
                    float[] uv = MeshPipelineCases.floats(e.get("cornerUVs"));
                    r.check(ctx, "faces[" + i + "].hasUVs", (uv != null) == face.hasAuthoredUVs(), "" + (uv != null),
                            "" + face.hasAuthoredUVs());
                    if (uv != null && face.hasAuthoredUVs()) {
                        approx(ctx, "faces[" + i + "].cornerUVs", uv, face.cornerUVs(), tol, r);
                    }
                }
                if (f.has("faceIdUpperBound")) {
                    r.check(ctx, "faceIdUpperBound", f.get("faceIdUpperBound").asInt() == l.mesh().faceIdUpperBound(),
                            f.get("faceIdUpperBound").asText(), "" + l.mesh().faceIdUpperBound());
                }
                int[][] expW = new int[f.path("warnings").size()][];
                for (int i = 0; i < expW.length; i++) {
                    JsonNode w = f.get("warnings").get(i);
                    expW[i] = new int[]{LegacyMeshImporter.ImportWarning.Kind.valueOf(w.get("kind").asText()).ordinal(),
                            w.get("faceId").asInt(), w.get("value").asInt()};
                }
                exCsr(r, ctx, "faces.warnings", expW, warningTriples(l.warnings()));
            }
            if (exp.has("render")) {
                JsonNode e = exp.get("render");
                RenderMesh rm = l.rm();
                approx(ctx, "render.vertices", MeshPipelineCases.floats(e.get("vertices")), rm.vertices(), tol, r);
                approx(ctx, "render.texCoords", MeshPipelineCases.floats(e.get("texCoords")), rm.texCoords(), tol, r);
                exInts(r, ctx, "render.indices", MeshPipelineCases.ints(e.get("indices")), rm.indices());
                exInts(r, ctx, "render.triangleToFaceId", MeshPipelineCases.ints(e.get("triangleToFaceId")),
                        rm.triangleToFaceId());
                exInts(r, ctx, "render.cornerToVertexId", MeshPipelineCases.ints(e.get("cornerToVertexId")),
                        rm.cornerToVertexId());
                exCsr(r, ctx, "render.vertexIdToCorners", rowsOf(e.get("vertexIdToCorners")), rm.vertexIdToCorners());
                if (e.has("triangleCount")) {
                    r.check(ctx, "render.triangleCount", e.get("triangleCount").asInt() == rm.triangleCount(),
                            e.get("triangleCount").asText(), "" + rm.triangleCount());
                }
            }
            if (exp.has("topology")) {
                topology(ctx, exp.get("topology"), l, tol, r);
            }
        }

        static void topology(String ctx, JsonNode e, Legacy l, float tol, Report r) {
            LegacyTopology t = l.topo();
            if (t == null) {
                r.check(ctx, "topology", false, "expected", "null");
                return;
            }
            exCsr(r, ctx, "topology.edges", rowsOf(e.get("edges")), t.edges());
            exCsr(r, ctx, "topology.edgeFaces", rowsOf(e.get("edgeFaces")), t.edgeFaces());
            int slot = 0;
            for (int fid : t.faceIds()) {
                JsonNode fe = e.path("faceEdges").get(Integer.toString(fid));
                if (fe != null) {
                    exInts(r, ctx, "topology.faceEdges[" + fid + "]", MeshPipelineCases.ints(fe), t.faceEdges()[slot]);
                }
                slot++;
            }
            exCsr(r, ctx, "topology.vertexEdges", rowsOf(e.get("vertexEdges")), t.vertexEdges());
            exCsr(r, ctx, "topology.vertexFaces", rowsOf(e.get("vertexFaces")), t.vertexFaces());
            JsonNode adj = e.get("faceAdjacency");
            if (adj != null) {
                for (int fid = 0; fid < t.faceAdjacent().length; fid++) {
                    JsonNode a = adj.get(Integer.toString(fid));
                    int[] faces = a == null ? new int[0] : MeshPipelineCases.ints(a.get("faces"));
                    exInts(r, ctx, "topology.faceAdjacency[" + fid + "].faces", faces, t.faceAdjacent()[fid]);
                    if (a != null) {
                        int[] edges = MeshPipelineCases.ints(a.get("edges"));
                        int[] java = new int[t.faceAdjacent()[fid].length];
                        for (int i = 0; i < java.length; i++) {
                            Integer s = t.facePairToEdgeId().get(
                                    LegacyTopology.canonicalKey(fid, t.faceAdjacent()[fid][i]));
                            java[i] = s == null ? -1 : s;
                        }
                        exInts(r, ctx, "topology.faceAdjacency[" + fid + "].edges", edges, java);
                    }
                }
            }
            vec(ctx, "faceNormals", e.get("faceNormals"), t.faceNormals(), tol, r);
            vec(ctx, "faceCentroids", e.get("faceCentroids"), t.faceCentroids(), tol, r);
            if (e.has("faceAreas")) {
                e.get("faceAreas").fields().forEachRemaining(en -> approx(ctx, "faceAreas[" + en.getKey() + "]",
                        new float[]{MeshPipelineCases.f(en.getValue())},
                        new float[]{t.faceAreas()[Integer.parseInt(en.getKey())]}, tol, r));
            }
            if (e.has("edgeKinds")) {
                JsonNode k = e.get("edgeKinds");
                int[] exp = new int[k.size()];
                for (int i = 0; i < exp.length; i++) {
                    exp[i] = switch (k.get(i).asText()) {
                        case "OPEN" -> 0;
                        case "MANIFOLD" -> 1;
                        default -> 2;
                    };
                }
                exInts(r, ctx, "topology.edgeKinds", exp, t.edgeKinds());
            }
            if (e.has("edgeDihedral")) {
                JsonNode d = e.get("edgeDihedral");
                float[] exp = new float[d.size()];
                for (int i = 0; i < exp.length; i++) exp[i] = d.get(i).isNull() ? Float.NaN : MeshPipelineCases.f(d.get(i));
                approx(ctx, "topology.edgeDihedral", exp, t.dihedral(), tol, r);
            }
        }

        static void vec(String ctx, String what, JsonNode map, Vector3f[] java, float tol, Report r) {
            if (map == null) return;
            map.fields().forEachRemaining(en -> {
                Vector3f v = java[Integer.parseInt(en.getKey())];
                approx(ctx, what + "[" + en.getKey() + "]", MeshPipelineCases.floats(en.getValue()),
                        // A gap id: Java holds null, the fixtures write the native 0 vector.
                        v == null ? new float[3] : new float[]{v.x, v.y, v.z}, tol, r);
            });
        }

        static void approx(String ctx, String array, float[] exp, float[] java, float tol, Report r) {
            r.arrays++;
            if (exp == null) return;
            if (java == null || exp.length != java.length) {
                r.miss(ctx, array + ".length", -1, exp == null ? "null" : "" + exp.length,
                        java == null ? "null" : "" + java.length);
                return;
            }
            r.elements += exp.length;
            for (int i = 0; i < exp.length; i++) {
                boolean ok = Float.isNaN(exp[i]) ? Float.isNaN(java[i]) : Math.abs(exp[i] - java[i]) <= tol;
                if (!ok) {
                    r.miss(ctx, array, i, "expected " + exp[i], "java " + java[i]);
                    return;
                }
            }
        }

        static int[][] rowsOf(JsonNode a) {
            if (a == null) return null;
            int[][] out = new int[a.size()][];
            for (int i = 0; i < out.length; i++) out[i] = MeshPipelineCases.ints(a.get(i));
            return out;
        }
    }
}
