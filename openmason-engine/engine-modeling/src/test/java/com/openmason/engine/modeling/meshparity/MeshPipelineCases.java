package com.openmason.engine.modeling.meshparity;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.format.omo.OmoDocument;
import com.openmason.engine.modeling.editable.EditableMesh;
import com.openmason.engine.modeling.uv.FaceTextureManager;
import com.openmason.engine.modeling.uv.FaceTextureMapping;
import org.joml.Vector3f;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Inputs of the mesh pipeline parity harness (OpenMason #175 F3): the hand-traced fixtures of
 * {@code cenda/tests/fixtures/mesh_pipeline}, every fixture OMO (Stonebreak test-fixture sbe/sbo {@code model.omo}
 * entries + {@code cenda/tests/golden/omo}), seeded random soups and seeded random editable meshes.
 */
final class MeshPipelineCases {

    private MeshPipelineCases() {
    }

    /** Input kinds (fixture README). */
    enum Kind { WELD, SOUP, EDITABLE }

    /**
     * One pipeline input.
     *
     * @param faceIds / loops / cornerUVs  EDITABLE faces in insertion order (cornerUVs[i] may be null)
     * @param bumpUpperBound              EDITABLE: a face id added then removed after the faces (-1 = none), so
     *                                    the upper bound exceeds the largest live id
     * @param mappings                    face mappings (any kind with faces)
     * @param expected                    fixture expectations or null
     */
    record MeshCase(String name, Kind kind, float[] vertices, float[] texCoords, int[] indices, int[] triFace,
                    float epsilon, float[] positions, int[] faceIds, int[][] loops, float[][] cornerUVs,
                    int bumpUpperBound, List<FaceTextureMapping> mappings, JsonNode expected, float tolerance) {

        EditableMesh editableMesh() {
            EditableMesh mesh = new EditableMesh();
            for (int v = 0; v < positions.length / 3; v++) {
                mesh.addVertex(new Vector3f(positions[3 * v], positions[3 * v + 1], positions[3 * v + 2]));
            }
            for (int f = 0; f < faceIds.length; f++) {
                mesh.addFaceWithId(faceIds[f], loops[f], cornerUVs[f]);
            }
            if (bumpUpperBound >= 0) {
                mesh.addFaceWithId(bumpUpperBound, loops[0]);
                mesh.removeFace(bumpUpperBound);
            }
            return mesh;
        }

        FaceTextureManager textureManager() {
            FaceTextureManager ftm = new FaceTextureManager();
            for (FaceTextureMapping m : mappings) {
                ftm.setFaceMapping(m);
            }
            return ftm;
        }
    }

    static Path engineDir() {
        Path p = Path.of("").toAbsolutePath();
        // Surefire runs in the module directory (openmason-engine/engine-modeling).
        return p.getFileName().toString().equals("engine-modeling") ? p.getParent() : p.resolve("openmason-engine");
    }

    static Path fixtureDir() {
        return engineDir().resolve("cenda/tests/fixtures/mesh_pipeline");
    }

    // ── Hand-traced fixtures ────────────────────────────────────────────────

    static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    static float f(JsonNode n) {
        return n.isBigDecimal() ? Float.parseFloat(n.decimalValue().toString()) : (float) n.asDouble();
    }

    static float[] floats(JsonNode a) {
        if (a == null || a.isNull()) return null;
        float[] out = new float[a.size()];
        for (int i = 0; i < out.length; i++) out[i] = f(a.get(i));
        return out;
    }

    static int[] ints(JsonNode a) {
        if (a == null || a.isNull()) return null;
        int[] out = new int[a.size()];
        for (int i = 0; i < out.length; i++) out[i] = a.get(i).asInt();
        return out;
    }

    static List<MeshCase> fixtures() throws IOException {
        List<MeshCase> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(fixtureDir())) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                out.add(fixture(JSON.readTree(file.toFile())));
            }
        }
        return out;
    }

    static MeshCase fixture(JsonNode root) {
        JsonNode in = root.get("input");
        Kind kind = Kind.valueOf(in.get("kind").asText().toUpperCase());
        float eps = in.has("epsilon") ? f(in.get("epsilon")) : 1e-4f;
        List<FaceTextureMapping> mappings = new ArrayList<>();
        if (in.has("faceMappings")) {
            for (JsonNode m : in.get("faceMappings")) {
                mappings.add(new FaceTextureMapping(m.get("faceId").asInt(), m.get("materialId").asInt(),
                        new FaceTextureMapping.UVRegion(f(m.get("u0")), f(m.get("v0")), f(m.get("u1")),
                                f(m.get("v1"))),
                        FaceTextureMapping.UVRotation.fromDegrees(m.path("rotation").asInt(0)), true,
                        m.path("authoredUVs").asBoolean(false)));
            }
        }
        int[] faceIds = null;
        int[][] loops = null;
        float[][] uvs = null;
        if (kind == Kind.EDITABLE) {
            JsonNode faces = in.get("faces");
            faceIds = new int[faces.size()];
            loops = new int[faces.size()][];
            uvs = new float[faces.size()][];
            for (int i = 0; i < faces.size(); i++) {
                faceIds[i] = faces.get(i).get("id").asInt();
                loops[i] = ints(faces.get(i).get("loop"));
                uvs[i] = floats(faces.get(i).get("cornerUVs"));
            }
        }
        return new MeshCase("fixture/" + root.get("name").asText(), kind, floats(in.get("vertices")),
                floats(in.get("texCoords")), ints(in.get("indices")), ints(in.get("triangleToFaceId")), eps,
                floats(in.get("positions")), faceIds, loops, uvs, -1, mappings, root.get("expected"),
                (float) root.path("tolerance").asDouble(1e-6));
    }

    // ── OMO corpus ──────────────────────────────────────────────────────────

    /** Every fixture OMO as a SOUP case, twice: mappings as loaded, and every mapping flagged authoredUVs. */
    static List<MeshCase> omoCorpus() throws IOException {
        List<MeshCase> out = new ArrayList<>();
        Path golden = engineDir().resolve("cenda/tests/golden/omo");
        if (Files.isDirectory(golden)) {
            try (Stream<Path> files = Files.list(golden)) {
                for (Path p : files.filter(x -> x.toString().endsWith(".omo")).sorted().toList()) {
                    addOmo(out, "golden/" + p.getFileName(), Files.readAllBytes(p));
                }
            }
        }
        Path sb = engineDir().getParent().resolve("test-fixtures/stonebreak/resources");
        if (Files.isDirectory(sb)) {
            try (Stream<Path> files = Files.walk(sb)) {
                for (Path p : files.filter(x -> x.toString().endsWith(".sbe") || x.toString().endsWith(".sbo"))
                        .sorted().toList()) {
                    try (InputStream is = Files.newInputStream(p); ZipInputStream zip = new ZipInputStream(is)) {
                        ZipEntry e;
                        while ((e = zip.getNextEntry()) != null) {
                            if (e.getName().endsWith(".omo")) {
                                addOmo(out, "stonebreak/" + sb.relativize(p) + "!" + e.getName(),
                                        zip.readAllBytes());
                            }
                        }
                    }
                }
            }
        }
        return out;
    }

    private static void addOmo(List<MeshCase> out, String name, byte[] bytes) {
        OmoDocument doc;
        try {
            doc = OmoDocument.read(bytes);
        } catch (IOException e) {
            return;
        }
        try (doc) {
            OMOFormat.MeshData mesh = doc.mesh();
            if (mesh == null || !mesh.hasCustomGeometry() || mesh.indices() == null) return;
            List<FaceTextureMapping> mappings = new ArrayList<>();
            List<FaceTextureMapping> authored = new ArrayList<>();
            for (OMOFormat.FaceMappingEntry m : doc.faceMappings()) {
                FaceTextureMapping.UVRotation rot;
                try {
                    rot = FaceTextureMapping.UVRotation.fromDegrees(m.uvRotationDegrees());
                } catch (IllegalArgumentException bad) {
                    rot = FaceTextureMapping.UVRotation.NONE;
                }
                FaceTextureMapping ftm = new FaceTextureMapping(m.faceId(), m.materialId(),
                        new FaceTextureMapping.UVRegion(m.u0(), m.v0(), m.u1(), m.v1()), rot, m.autoResize());
                mappings.add(ftm);
                authored.add(ftm.withAuthoredUVs(true));
            }
            out.add(soup(name, mesh.vertices(), mesh.texCoords(), mesh.indices(), mesh.triangleToFaceId(),
                    mappings));
            if (mesh.texCoords() != null && !authored.isEmpty()) {
                out.add(soup(name + "#authored", mesh.vertices(), mesh.texCoords(), mesh.indices(),
                        mesh.triangleToFaceId(), authored));
            }
        } catch (RuntimeException ignored) {
            // a document without a readable mesh is not a mesh pipeline input
        }
    }

    static MeshCase soup(String name, float[] vertices, float[] texCoords, int[] indices, int[] triFace,
                         List<FaceTextureMapping> mappings) {
        return new MeshCase(name, Kind.SOUP, vertices, texCoords, indices, triFace, 1e-4f, null, null, null, null,
                -1, mappings, null, 0);
    }

    // ── Seeded random soups ─────────────────────────────────────────────────

    static List<MeshCase> randomSoups(long seed, int count) {
        List<MeshCase> out = new ArrayList<>();
        SplittableRandom rnd = new SplittableRandom(seed);
        for (int i = 0; i < count; i++) {
            out.add(randomSoup("soup/" + seed + "/" + i, rnd.split()));
        }
        return out;
    }

    private static MeshCase randomSoup(String name, SplittableRandom rnd) {
        List<float[]> verts = new ArrayList<>();
        List<int[]> tris = new ArrayList<>();
        List<Integer> faces = new ArrayList<>();
        int structured = rnd.nextInt(1, 6);
        int faceId = rnd.nextInt(0, 3);
        for (int s = 0; s < structured; s++) {
            int pattern = rnd.nextInt(6);
            int fid = rnd.nextInt(10) == 0 ? -1 - rnd.nextInt(3) : faceId;
            faceId += rnd.nextInt(10) == 0 ? rnd.nextInt(2, 40) : 1;
            switch (pattern) {
                case 0, 1 -> polygonSoup(rnd, verts, tris, faces, fid, rnd.nextInt(3, 11), pattern == 1);
                case 2 -> ringBand(rnd, verts, tris, faces, fid);
                case 3 -> bowTie(rnd, verts, tris, faces, fid);
                case 4 -> gridQuads(rnd, verts, tris, faces, fid);
                default -> randomTriangles(rnd, verts, tris, faces, fid);
            }
        }
        // Near-duplicates (within / around epsilon) of existing corners.
        int extra = rnd.nextInt(0, 6);
        for (int k = 0; k < extra && !verts.isEmpty(); k++) {
            float[] p = verts.get(rnd.nextInt(verts.size()));
            float d = switch (rnd.nextInt(4)) {
                case 0 -> 5e-5f;
                case 1 -> 1e-4f;
                case 2 -> 1.5e-4f;
                default -> -9e-5f;
            };
            verts.add(new float[]{p[0] + d, p[1], p[2] - d * 0.5f});
        }
        float[] vertices = new float[verts.size() * 3];
        for (int v = 0; v < verts.size(); v++) {
            System.arraycopy(verts.get(v), 0, vertices, 3 * v, 3);
        }
        // Corrupt / degenerate triangles.
        if (rnd.nextInt(6) == 0) {
            tris.add(new int[]{0, verts.size() + rnd.nextInt(3), 1});
            faces.add(0);
        }
        if (rnd.nextInt(6) == 0) {
            tris.add(new int[]{-1, 0, 1});
            faces.add(1);
        }
        if (rnd.nextInt(5) == 0 && verts.size() > 1) {
            tris.add(new int[]{0, 0, 1});
            faces.add(2);
        }
        int[] indices = new int[tris.size() * 3 + (rnd.nextInt(8) == 0 ? rnd.nextInt(1, 3) : 0)];
        for (int t = 0; t < tris.size(); t++) {
            System.arraycopy(tris.get(t), 0, indices, 3 * t, 3);
        }
        int[] triFace;
        int mode = rnd.nextInt(8);
        if (mode == 0) {
            triFace = null;
        } else {
            int len = mode == 1 ? Math.max(0, faces.size() - rnd.nextInt(1, 4)) : faces.size();
            triFace = new int[len];
            for (int t = 0; t < len; t++) triFace[t] = faces.get(t);
        }
        float[] texCoords = null;
        if (rnd.nextBoolean()) {
            int n = verts.size() * 2 - (rnd.nextInt(10) == 0 ? 1 : 0);
            texCoords = new float[Math.max(0, n)];
            for (int k = 0; k < texCoords.length; k++) {
                texCoords[k] = rnd.nextInt(5) == 0 ? -0.0f : (float) rnd.nextDouble();
            }
        }
        List<FaceTextureMapping> mappings = randomMappings(rnd, faceId + 2);
        return new MeshCase(name, Kind.SOUP, vertices, texCoords, indices, triFace, 1e-4f, null, null, null, null,
                -1, mappings, null, 0);
    }

    private static float grid(SplittableRandom rnd) {
        return rnd.nextInt(-4, 5) * 0.5f;
    }

    private static float[] randomPoint(SplittableRandom rnd) {
        if (rnd.nextInt(3) == 0) {
            return new float[]{(float) rnd.nextDouble(-3, 3), (float) rnd.nextDouble(-3, 3),
                    (float) rnd.nextDouble(-3, 3)};
        }
        return new float[]{grid(rnd), grid(rnd), grid(rnd)};
    }

    /** A planar n-gon (convex, or a star = concave) triangulated as a fan, soup style (own corner copies). */
    private static void polygonSoup(SplittableRandom rnd, List<float[]> verts, List<int[]> tris, List<Integer> faces,
                                    int fid, int n, boolean concave) {
        float[][] ring = polygon(rnd, n, concave);
        float[] c = randomPoint(rnd);
        int axis = rnd.nextInt(3);
        boolean flip = rnd.nextBoolean();
        for (int i = 1; i < n - 1; i++) {
            int[] corners = {0, i, i + 1};
            if (flip) corners = new int[]{0, i + 1, i};
            int[] tri = new int[3];
            for (int k = 0; k < 3; k++) {
                tri[k] = verts.size();
                verts.add(place(ring[corners[k]], c, axis));
            }
            tris.add(tri);
            faces.add(fid);
        }
    }

    static float[][] polygon(SplittableRandom rnd, int n, boolean concave) {
        float[][] ring = new float[n][];
        double r = rnd.nextDouble(0.2, 2.0);
        double phase = rnd.nextDouble(0, Math.PI);
        for (int i = 0; i < n; i++) {
            double a = phase + 2 * Math.PI * i / n;
            double rr = concave && (i % 2 == 1) ? r * 0.35 : r;
            ring[i] = new float[]{(float) (rr * Math.cos(a)), (float) (rr * Math.sin(a))};
        }
        return ring;
    }

    private static float[] place(float[] p2, float[] c, int axis) {
        return switch (axis) {
            case 0 -> new float[]{c[0] + p2[0], c[1] + p2[1], c[2]};
            case 1 -> new float[]{c[0] + p2[0], c[1], c[2] + p2[1]};
            default -> new float[]{c[0], c[1] + p2[0], c[2] + p2[1]};
        };
    }

    /** An annulus (outer + inner ring): the boundary walk closes early -> insertion-order fallback. */
    private static void ringBand(SplittableRandom rnd, List<float[]> verts, List<int[]> tris, List<Integer> faces,
                                 int fid) {
        int n = rnd.nextInt(3, 7);
        float[] c = randomPoint(rnd);
        int base = verts.size();
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * i / n;
            verts.add(new float[]{c[0] + (float) (2 * Math.cos(a)), c[1] + (float) (2 * Math.sin(a)), c[2]});
            verts.add(new float[]{c[0] + (float) Math.cos(a), c[1] + (float) Math.sin(a), c[2]});
        }
        for (int i = 0; i < n; i++) {
            int o0 = base + 2 * i, i0 = o0 + 1, o1 = base + 2 * ((i + 1) % n), i1 = o1 + 1;
            tris.add(new int[]{o0, o1, i1});
            tris.add(new int[]{o0, i1, i0});
            faces.add(fid);
            faces.add(fid);
        }
    }

    /** Two triangles touching in one vertex (bow tie). */
    private static void bowTie(SplittableRandom rnd, List<float[]> verts, List<int[]> tris, List<Integer> faces,
                               int fid) {
        float[] c = randomPoint(rnd);
        int b = verts.size();
        verts.add(c);
        verts.add(new float[]{c[0] + 1, c[1] + 1, c[2]});
        verts.add(new float[]{c[0] + 1, c[1] - 1, c[2]});
        verts.add(new float[]{c[0] - 1, c[1] + 1, c[2]});
        verts.add(new float[]{c[0] - 1, c[1] - 1, c[2]});
        tris.add(new int[]{b, b + 1, b + 2});
        tris.add(new int[]{b, b + 4, b + 3});
        faces.add(fid);
        faces.add(fid);
    }

    /** A grid of quads, each its own face (shared positions duplicated per corner) with an id offset. */
    private static void gridQuads(SplittableRandom rnd, List<float[]> verts, List<int[]> tris, List<Integer> faces,
                                  int fid) {
        int w = rnd.nextInt(1, 4), h = rnd.nextInt(1, 4);
        float[] c = randomPoint(rnd);
        float z = rnd.nextBoolean() ? 0 : 0.25f;
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int b = verts.size();
                float zz = ((x + y) & 1) == 0 ? 0 : z;
                verts.add(new float[]{c[0] + x, c[1] + y, c[2]});
                verts.add(new float[]{c[0] + x + 1, c[1] + y, c[2] + zz});
                verts.add(new float[]{c[0] + x + 1, c[1] + y + 1, c[2]});
                verts.add(new float[]{c[0] + x, c[1] + y + 1, c[2] + zz});
                tris.add(new int[]{b, b + 1, b + 2});
                tris.add(new int[]{b, b + 2, b + 3});
                int id = fid < 0 ? fid : fid + 100 + x * 7 + y;
                faces.add(id);
                faces.add(id);
            }
        }
    }

    /** Random triangles over grid points (shared ids, coincident positions, random face grouping). */
    private static void randomTriangles(SplittableRandom rnd, List<float[]> verts, List<int[]> tris,
                                        List<Integer> faces, int fid) {
        int b = verts.size();
        int n = rnd.nextInt(3, 12);
        for (int i = 0; i < n; i++) {
            verts.add(new float[]{rnd.nextInt(0, 3), rnd.nextInt(0, 3), rnd.nextInt(0, 2)});
        }
        int t = rnd.nextInt(1, 10);
        for (int i = 0; i < t; i++) {
            tris.add(new int[]{b + rnd.nextInt(n), b + rnd.nextInt(n), b + rnd.nextInt(n)});
            faces.add(rnd.nextInt(3) == 0 ? fid + rnd.nextInt(3) : fid);
        }
    }

    static List<FaceTextureMapping> randomMappings(SplittableRandom rnd, int faceIdBound) {
        List<FaceTextureMapping> out = new ArrayList<>();
        int count = rnd.nextInt(0, Math.max(1, faceIdBound));
        for (int i = 0; i < count; i++) {
            out.add(randomMapping(rnd, rnd.nextInt(0, Math.max(1, faceIdBound))));
        }
        return out;
    }

    static FaceTextureMapping randomMapping(SplittableRandom rnd, int faceId) {
        FaceTextureMapping.UVRegion region = switch (rnd.nextInt(6)) {
            case 0 -> FaceTextureMapping.FULL_REGION;
            case 1 -> new FaceTextureMapping.UVRegion(-0.0f, 0.0f, 1.0f, 1.0f);
            case 2 -> new FaceTextureMapping.UVRegion(0.0f, -0.0f, 1.0f, 1.0f);
            case 3 -> new FaceTextureMapping.UVRegion(0.25f, 0.5f, 0.5f, 1.0f);
            default -> {
                float u0 = (float) rnd.nextDouble(), v0 = (float) rnd.nextDouble();
                yield new FaceTextureMapping.UVRegion(u0, v0, u0 + (float) rnd.nextDouble(0, 0.5),
                        v0 + (float) rnd.nextDouble(0, 0.5));
            }
        };
        FaceTextureMapping.UVRotation rot = FaceTextureMapping.UVRotation.values()[rnd.nextInt(4)];
        int material = rnd.nextInt(3) == 0 ? rnd.nextInt(1, 4) : 0;
        return new FaceTextureMapping(faceId, material, region, rot, rnd.nextBoolean(), rnd.nextBoolean());
    }

    // ── Seeded random editable meshes ───────────────────────────────────────

    static List<MeshCase> randomEditables(long seed, int count) {
        List<MeshCase> out = new ArrayList<>();
        SplittableRandom rnd = new SplittableRandom(seed);
        for (int i = 0; i < count; i++) {
            out.add(randomEditable("editable/" + seed + "/" + i, rnd.split()));
        }
        return out;
    }

    private static MeshCase randomEditable(String name, SplittableRandom rnd) {
        List<float[]> pos = new ArrayList<>();
        List<int[]> loops = new ArrayList<>();
        int shapes = rnd.nextInt(1, 7);
        for (int s = 0; s < shapes; s++) {
            switch (rnd.nextInt(7)) {
                case 0 -> { // n-gon, convex or star
                    int n = rnd.nextInt(3, 12);
                    float[][] ring = polygon(rnd, n, rnd.nextBoolean());
                    float[] c = randomPoint(rnd);
                    int axis = rnd.nextInt(3);
                    int[] loop = new int[n];
                    for (int i = 0; i < n; i++) {
                        loop[i] = pos.size();
                        pos.add(place(ring[i], c, axis));
                    }
                    loops.add(rnd.nextBoolean() ? loop : reversed(loop));
                }
                case 1 -> { // collinear loop: a quad with midpoints on two edges
                    float[] c = randomPoint(rnd);
                    float[][] pts = {{0, 0}, {0.5f, 0}, {1, 0}, {1, 1}, {0.5f, 1}, {0, 1}};
                    int[] loop = new int[pts.length];
                    for (int i = 0; i < pts.length; i++) {
                        loop[i] = pos.size();
                        pos.add(place(pts[i], c, rnd.nextInt(3)));
                    }
                    loops.add(loop);
                }
                case 2 -> { // non-manifold fin: 3 quads on one edge
                    float[] c = randomPoint(rnd);
                    int a = pos.size();
                    pos.add(c);
                    pos.add(new float[]{c[0], c[1] + 1, c[2]});
                    int[][] wings = {{1, 0}, {0, 1}, {-1, 0}};
                    for (int[] w : wings) {
                        int p = pos.size();
                        pos.add(new float[]{c[0] + w[0], c[1], c[2] + w[1]});
                        pos.add(new float[]{c[0] + w[0], c[1] + 1, c[2] + w[1]});
                        loops.add(new int[]{a, p, p + 1, a + 1});
                    }
                }
                case 3 -> { // a cube (shared vertices)
                    float[] c = randomPoint(rnd);
                    int b = pos.size();
                    for (int k = 0; k < 8; k++) {
                        pos.add(new float[]{c[0] + (k & 1), c[1] + ((k >> 1) & 1), c[2] + ((k >> 2) & 1)});
                    }
                    int[][] q = {{0, 2, 3, 1}, {4, 5, 7, 6}, {0, 1, 5, 4}, {2, 6, 7, 3}, {0, 4, 6, 2}, {1, 3, 7, 5}};
                    for (int[] f : q) loops.add(new int[]{b + f[0], b + f[1], b + f[2], b + f[3]});
                }
                case 4 -> { // degenerate faces: coincident / collinear points
                    float[] c = randomPoint(rnd);
                    int b = pos.size();
                    boolean coincident = rnd.nextBoolean();
                    for (int k = 0; k < 4; k++) {
                        pos.add(coincident ? c.clone() : new float[]{c[0] + k, c[1] + 2 * k, c[2]});
                    }
                    loops.add(new int[]{b, b + 1, b + 2, b + 3});
                }
                case 5 -> { // tiny / far face
                    float sc = rnd.nextBoolean() ? 1e-5f : 1e3f;
                    float[] c = rnd.nextBoolean() ? new float[]{1e4f, -2e4f, 3e3f} : randomPoint(rnd);
                    float[][] ring = polygon(rnd, rnd.nextInt(4, 8), true);
                    int[] loop = new int[ring.length];
                    for (int i = 0; i < ring.length; i++) {
                        loop[i] = pos.size();
                        pos.add(place(new float[]{ring[i][0] * sc, ring[i][1] * sc}, c, rnd.nextInt(3)));
                    }
                    loops.add(loop);
                }
                default -> { // non-planar random loop over existing + new vertices
                    int n = rnd.nextInt(3, 7);
                    int[] loop = new int[n];
                    for (int i = 0; i < n; i++) {
                        loop[i] = pos.size();
                        pos.add(randomPoint(rnd));
                    }
                    loops.add(loop);
                }
            }
        }
        // Orphan vertices.
        int orphans = rnd.nextInt(0, 3);
        for (int k = 0; k < orphans; k++) pos.add(randomPoint(rnd));
        // Shuffle vertex ids so loops are not ascending.
        int vc = pos.size();
        int[] perm = new int[vc];
        for (int i = 0; i < vc; i++) perm[i] = i;
        if (rnd.nextBoolean()) {
            for (int i = vc - 1; i > 0; i--) {
                int j = rnd.nextInt(i + 1);
                int t = perm[i];
                perm[i] = perm[j];
                perm[j] = t;
            }
        }
        float[] positions = new float[vc * 3];
        for (int v = 0; v < vc; v++) System.arraycopy(pos.get(v), 0, positions, 3 * perm[v], 3);
        int fc = loops.size();
        int[] faceIds = new int[fc];
        int[][] l = new int[fc][];
        float[][] uvs = new float[fc][];
        int next = rnd.nextInt(0, 3);
        for (int f = 0; f < fc; f++) {
            faceIds[f] = next;
            next += rnd.nextInt(4) == 0 ? rnd.nextInt(2, 9) : 1;
            int[] loop = loops.get(f).clone();
            for (int i = 0; i < loop.length; i++) loop[i] = perm[loop[i]];
            l[f] = loop;
            if (rnd.nextBoolean()) {
                float[] uv = new float[loop.length * 2];
                for (int k = 0; k < uv.length; k++) uv[k] = rnd.nextInt(6) == 0 ? -0.0f : (float) rnd.nextDouble();
                uvs[f] = uv;
            }
        }
        if (rnd.nextBoolean()) { // insertion order != id order
            for (int i = fc - 1; i > 0; i--) {
                int j = rnd.nextInt(i + 1);
                int t = faceIds[i];
                faceIds[i] = faceIds[j];
                faceIds[j] = t;
            }
        }
        int bump = rnd.nextInt(4) == 0 ? next + rnd.nextInt(0, 5) : -1;
        List<FaceTextureMapping> mappings = new ArrayList<>();
        for (int f = 0; f < fc; f++) {
            if (rnd.nextInt(3) != 0) mappings.add(randomMapping(rnd, faceIds[f]));
        }
        if (rnd.nextInt(4) == 0) mappings.add(randomMapping(rnd, next + 50)); // mapping of a face not in the mesh
        return new MeshCase(name, Kind.EDITABLE, null, null, null, null, 1e-4f, positions, faceIds, l, uvs, bump,
                mappings, null, 0);
    }

    private static int[] reversed(int[] a) {
        int[] r = new int[a.length];
        for (int i = 0; i < a.length; i++) r[i] = a[a.length - 1 - i];
        return r;
    }
}
