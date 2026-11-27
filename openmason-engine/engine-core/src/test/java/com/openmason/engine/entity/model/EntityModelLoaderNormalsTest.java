package com.openmason.engine.entity.model;

import com.openmason.engine.format.omo.CendaNormals;
import com.openmason.engine.format.omo.CendaOmo;
import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.format.omo.OMOReader;
import com.openmason.engine.format.omo.OmoDocument;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Corner normals at load (OpenMason #169): {@link EntityModelLoader} computes them once per model through the native
 * {@code co_mesh_normals} from the OMO 1.9 shading section; flat corners carry the FLAT marker; a model without a
 * shading section (every OMO before 1.9) has none, so it keeps its exact flat look. Also the OMO 1.9 base colour.
 */
class EntityModelLoaderNormalsTest {

    @BeforeAll
    static void needsNative() {
        assumeTrue(CendaOmo.isAvailable(), () -> "native model module: " + CendaOmo.unavailableReason());
        assumeTrue(CendaNormals.isAvailable(), () -> "native normals: " + CendaNormals.unavailableReason());
    }

    // A unit cube as the editor writes it: 6 quad faces (face ids 0..5), 4 soup vertices each, 2 triangles each.
    private static final float[][] FACE_CORNERS = {
            {1, -1, -1, 1, 1, -1, 1, 1, 1, 1, -1, 1},      // +X
            {-1, -1, 1, -1, 1, 1, -1, 1, -1, -1, -1, -1},  // -X
            {-1, 1, -1, -1, 1, 1, 1, 1, 1, 1, 1, -1},      // +Y
            {-1, -1, 1, -1, -1, -1, 1, -1, -1, 1, -1, 1},  // -Y
            {1, -1, 1, 1, 1, 1, -1, 1, 1, -1, -1, 1},      // +Z
            {-1, -1, -1, -1, 1, -1, 1, 1, -1, 1, -1, -1},  // -Z
    };

    private static float[] positions() {
        float[] p = new float[6 * 4 * 3];
        for (int f = 0; f < 6; f++) System.arraycopy(FACE_CORNERS[f], 0, p, f * 12, 12);
        return p;
    }

    private static int[] indices() {
        int[] idx = new int[6 * 6];
        for (int f = 0; f < 6; f++) {
            int b = f * 4;
            int[] tri = {b, b + 1, b + 2, b, b + 2, b + 3};
            System.arraycopy(tri, 0, idx, f * 6, 6);
        }
        return idx;
    }

    private static int[] faceIds() {
        int[] ids = new int[12];
        for (int t = 0; t < 12; t++) ids[t] = t / 2;
        return ids;
    }

    private static OMOReader.ReadResult cube(OMOFormat.ShadingData shading, float[] baseColor) throws Exception {
        try (OmoDocument doc = OmoDocument.create()) {
            doc.setObjectName("cube");
            doc.setModelType("cube");
            doc.setGeometry(new OMOFormat.GeometryData(1, 1, 1, new OMOFormat.Position(0, 0, 0)));
            doc.setMesh(new OMOFormat.MeshData(positions(), new float[24 * 2], indices(), faceIds(), null));
            doc.setParts(List.of(new OMOFormat.PartEntry("p", "body", 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1,
                    0, 24, 0, 36, 0, 6, true, false)));
            if (baseColor != null) {
                doc.setMaterials(List.of(new OMOFormat.MaterialEntry(1, "paint", null, "OPAQUE", false, -1, baseColor)));
                java.util.List<OMOFormat.FaceMappingEntry> mappings = new java.util.ArrayList<>();
                for (int f = 0; f < 6; f++) mappings.add(new OMOFormat.FaceMappingEntry(f, 1, 0, 0, 1, 1, 0, false));
                doc.setFaceMappings(mappings);
            }
            if (shading != null) doc.setShading(shading);
            return new OMOReader().read(doc.write());
        }
    }

    @Test
    void aSmoothCubeGetsTheNativeCornerNormals() throws Exception {
        OMOFormat.ShadingData shading = new OMOFormat.ShadingData(new int[]{0b111111}, null, null, null, null);
        OMOReader.ReadResult omo = cube(shading, null);
        assertEquals(shading.smoothWords()[0], omo.shading().smoothWords()[0], "the shading section round-trips");
        EntityGeometry g = EntityModelLoader.buildGeometry(omo);

        // Exactly co_mesh_normals over the same soup (one part = one group): no corner is FLAT.
        short[] expected = CendaNormals.meshNormals(positions(), indices(), faceIds(), new int[12],
                new int[]{0b111111}, null, 0f, null, null, 0f).oct();
        assertArrayEquals(expected, g.normals());
        assertTrue(g.hasSmoothNormals());
        // Angle-weighted over three right-angled faces: every corner normal is the cube's diagonal.
        float[] n = CendaNormals.octDecode(g.normals());
        float[] p = positions();
        for (int v = 0; v < 24; v++) {
            for (int c = 0; c < 3; c++) {
                assertEquals(Math.signum(p[v * 3 + c]) / (float) Math.sqrt(3), n[v * 3 + c], 1e-3f, "corner " + v);
            }
        }
    }

    @Test
    void flatFacesAreMarkedFlatAndSmoothOnesAreNot() throws Exception {
        // Only +X is smooth: its neighbours are flat, so its fans end at its own edges (= its face normal), and every
        // corner of the 5 flat faces carries the FLAT marker (the renderer lights it with the drawn face normal).
        EntityGeometry g = EntityModelLoader.buildGeometry(
                cube(new OMOFormat.ShadingData(new int[]{0b1}, null, null, null, null), null));
        short[] normals = g.normals();
        for (int v = 0; v < 24; v++) {
            boolean flat = normals[v * 2] == Short.MIN_VALUE && normals[v * 2 + 1] == Short.MIN_VALUE;
            assertEquals(v >= 4, flat, "vertex " + v);
        }
        float[] n = CendaNormals.octDecode(java.util.Arrays.copyOf(normals, 8));
        for (int v = 0; v < 4; v++) {
            assertEquals(1f, n[v * 3], 1e-6f);
            assertEquals(0f, n[v * 3 + 1], 1e-6f);
            assertEquals(0f, n[v * 3 + 2], 1e-6f);
        }
    }

    @Test
    void aModelWithoutShadingIsFlatEverywhere() throws Exception {
        // OMO 1.8 (no shading section) and a 1.9 section with nothing smooth: no normals, the exact old flat look.
        OMOReader.ReadResult old = cube(null, null);
        assertNull(old.shading());
        EntityGeometry g = EntityModelLoader.buildGeometry(old);
        assertNull(g.normals());
        assertFalse(g.hasSmoothNormals());
        assertNull(EntityModelLoader.cornerNormals(new OMOFormat.ShadingData(new int[]{0}, null, 30f, null, null),
                g.parts(), positions(), indices(), faceIds()));
    }

    @Test
    void aColourOnlyMaterialDrawsWhiteTimesItsBaseColour() throws Exception {
        float[] red = {0.8f, 0.1f, 0.1f, 1f};
        EntityGeometry g = EntityModelLoader.buildGeometry(cube(null, red));
        MaterialImage image = g.materials().get(1);
        assertEquals(1, image.width());
        assertArrayEquals(new byte[]{-1, -1, -1, -1}, image.rgba());
        assertArrayEquals(red, image.baseColor());
        assertNotEquals(null, g.materials().get(1));
    }
}
