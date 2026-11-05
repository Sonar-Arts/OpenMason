package com.openmason.engine.voxel.sbo;

import com.openmason.engine.format.mesh.ParsedMeshData;
import com.openmason.engine.format.sbo.SBOFormat;
import com.openmason.engine.format.sbo.SBOParseResult;
import com.openmason.engine.voxel.IBlockType;
import com.openmason.engine.voxel.ILayerIndexProvider;
import com.openmason.engine.voxel.ITextureCoordProvider;
import com.openmason.engine.voxel.sbo.SBOMeshProcessor.BlockStamp;
import com.openmason.engine.voxel.sbo.SBOMeshProcessor.FaceStamp;
import com.openmason.engine.voxel.sbo.sboRenderer.SBOFaceConventions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Geometry that pokes out through one side of the cell (an ore's nuggets) sits
 * inside that neighbour's cell, so it must cull with that side instead of
 * riding the never-culled interior bucket — otherwise every buried ore emits
 * its whole nugget mesh. Geometry inside the cell, or poking out through two
 * sides at once, stays interior.
 */
class SBOMeshProcessorProtrusionTest {

    private record FakeBlock(int id) implements IBlockType {
        @Override public int getId() { return id; }
        @Override public String getName() { return "Fake"; }
        @Override public boolean isSolid() { return true; }
        @Override public boolean isBreakable() { return true; }
        @Override public boolean isTransparent() { return false; }
        @Override public boolean isAir() { return false; }
    }

    private record FakeLayers() implements ILayerIndexProvider {
        @Override public int getBlockFaceLayer(IBlockType blockType, int face) { return face; }
    }

    private record FakeUV() implements ITextureCoordProvider {
        @Override public float[] getBlockFaceUVs(IBlockType blockType, int face) {
            return new float[]{0f, 0f, 1f, 1f};
        }
    }

    private static BlockStamp stamp(float[] verts) {
        int[] indices = new int[verts.length / 3];
        for (int i = 0; i < indices.length; i++) indices[i] = i;
        ParsedMeshData mesh = new ParsedMeshData(verts, new float[indices.length * 2], indices, null, "FLAT");
        SBOFormat.Document manifest = new SBOFormat.Document(
                "1.8", "stonebreak:fake", "Fake", "block", "Stonebreak Ores",
                "checksum", "author", "desc", "createdAt", "model.omo",
                null, null, null, null, null, null);
        SBOParseResult sbo = new SBOParseResult(manifest, null, mesh, null, null, null,
                null, null, null, null, null, null);
        SBOMeshProcessor processor = new SBOMeshProcessor();
        assertTrue(processor.process(new FakeBlock(9), sbo, new FakeUV(), new FakeLayers()));
        return processor.getBlockStamp(new FakeBlock(9));
    }

    private static int interiorVerts(BlockStamp s) {
        int n = 0;
        for (FaceStamp f : s.interior()) n += f.vertexCount();
        return n;
    }

    private static int protrudingVerts(BlockStamp s, int side) {
        int n = 0;
        for (FaceStamp f : s.protruding()[side]) n += f.vertexCount();
        return n;
    }

    @Test
    void aNuggetPokingOutOfTheEastSideCullsWithTheEastFace() {
        // nugget top facing +Y, straddling the east plane (x 0.45 → 0.6)
        BlockStamp s = stamp(new float[]{
                0.45f, 0.1f, -0.1f,  0.45f, 0.1f, 0.1f,  0.6f, 0.1f, 0.1f,
        });
        assertEquals(3, protrudingVerts(s, SBOFaceConventions.MMS_EAST));
        assertEquals(3, s.protruding()[SBOFaceConventions.MMS_EAST][SBOFaceConventions.MMS_TOP].vertexCount(),
                "the slot keeps the face it points along for lighting");
        assertEquals(0, interiorVerts(s));
        assertTrue(s.hasProtrusions());
    }

    @Test
    void negativeSidesMapToTheirOwnFaces() {
        BlockStamp s = stamp(new float[]{
                -0.6f, 0.1f, -0.1f,  -0.45f, 0.1f, 0.1f,  -0.45f, 0.1f, -0.1f,   // west
                -0.1f, -0.6f, -0.1f,  0.1f, -0.45f, 0.1f,  -0.1f, -0.45f, 0.1f,  // bottom
                -0.1f, 0.1f, -0.6f,  0.1f, 0.1f, -0.45f,  -0.1f, -0.1f, -0.45f,  // north
        });
        assertEquals(3, protrudingVerts(s, SBOFaceConventions.MMS_WEST));
        assertEquals(3, protrudingVerts(s, SBOFaceConventions.MMS_BOTTOM));
        assertEquals(3, protrudingVerts(s, SBOFaceConventions.MMS_NORTH));
        assertEquals(0, interiorVerts(s));
    }

    @Test
    void inCellAndTwoSidedGeometryStaysInterior() {
        BlockStamp s = stamp(new float[]{
                -0.1f, 0.1f, -0.1f,  0.1f, 0.1f, -0.1f,  0.1f, 0.1f, 0.1f,       // inside the cell
                0.45f, 0.45f, 0.0f,  0.6f, 0.45f, 0.1f,  0.45f, 0.6f, 0.1f,      // pokes out east AND top
        });
        assertEquals(6, interiorVerts(s));
        assertFalse(s.hasProtrusions());
    }

    @Test
    void flushBoundaryGeometryIsNotAProtrusion() {
        BlockStamp s = stamp(new float[]{
                -0.5f, 0.5f, -0.5f,  0.5f, 0.5f, 0.5f,  0.5f, 0.5f, -0.5f,
        });
        assertEquals(3, s.faces()[SBOFaceConventions.MMS_TOP].vertexCount());
        assertFalse(s.hasProtrusions());
    }

    @Test
    void rotationCarriesProtrusionsToTheTurnedSide() {
        BlockStamp s = stamp(new float[]{
                -0.1f, 0.1f, 0.45f,  0.1f, 0.1f, 0.45f,  0.1f, 0.1f, 0.6f,       // pokes out south
        });
        BlockStamp turned = SBOStampRotator.rotateY(s, 1); // south → east
        assertEquals(3, protrudingVerts(turned, SBOFaceConventions.MMS_EAST));
        assertEquals(0, protrudingVerts(turned, SBOFaceConventions.MMS_SOUTH));

        BlockStamp flipped = SBOStampRotator.flipY(stamp(new float[]{
                -0.1f, 0.45f, -0.1f,  0.1f, 0.6f, 0.1f,  0.1f, 0.45f, -0.1f,     // pokes out top
        }));
        assertEquals(3, protrudingVerts(flipped, SBOFaceConventions.MMS_BOTTOM));
        assertEquals(0, protrudingVerts(flipped, SBOFaceConventions.MMS_TOP));
    }
}
