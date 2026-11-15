package com.openmason.engine.voxel.stamp;

import com.openmason.engine.format.mesh.ParsedMeshData;
import com.openmason.engine.voxel.stamp.StampMeshProcessor.BlockStamp;
import com.openmason.engine.voxel.stamp.StampMeshProcessor.FaceStamp;
import com.openmason.engine.voxel.IBlockType;
import com.openmason.engine.voxel.ILayerIndexProvider;
import com.openmason.engine.voxel.ITextureCoordProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Interior geometry must ride its OWN material's layer. A shaped model with 3D
 * detail (a cactus's thorn prickle boxes) carries per-triangle authored face ids
 * past the six MMS faces, each mapped to its own material in the SBO's face
 * textures — the geometric MMS face a detail triangle points along would squeeze
 * the block's side texture onto every tiny detail face. Flush triangles keep the
 * uniform per-MMS-face layer, so cube-shaped stamps are texture-identical to the
 * shipped path, and a mesh without authored face ids keeps the shipped behaviour.
 */
class StampMeshProcessorAuthoredLayerTest {

    private record FakeBlock(int id) implements IBlockType {
        @Override public int getId() { return id; }
        @Override public String getName() { return "Fake"; }
        @Override public boolean isSolid() { return true; }
        @Override public boolean isBreakable() { return true; }
        @Override public boolean isTransparent() { return false; }
        @Override public boolean isAir() { return false; }
    }

    /**
     * Layer provider: MMS faces resolve per face (100 + face); authored faces
     * ride their own material's layer, falling back to the geometric face.
     */
    private record FakeProvider(Map<Integer, Float> authoredByFace) implements ILayerIndexProvider {
        @Override
        public int getBlockFaceLayer(IBlockType blockType, int face) {
            return 100 + face;
        }

        @Override
        public float getBlockFaceLayerForAuthoredFace(IBlockType blockType, String stateName,
                                                       int authoredFaceId, int mmsFace) {
            Float layer = authoredByFace.get(authoredFaceId);
            return layer != null ? layer : getBlockFaceLayer(blockType, stateName, mmsFace);
        }
    }

    private record FakeUV() implements ITextureCoordProvider {
        @Override
        public float[] getBlockFaceUVs(IBlockType blockType, int face) {
            return new float[]{0f, 0f, 1f, 1f};
        }
    }

    /** 2 flush top-boundary triangles (authored id 4) + 2 interior detail triangles (authored id 7). */
    private ParsedMeshData mesh(int[] triangleToFaceId) {
        float[] verts = {
                // top quad at y = 0.5 — flush on the +y cell boundary
                -0.5f, 0.5f, -0.5f,  0.5f, 0.5f, -0.5f,  0.5f, 0.5f, 0.5f,
                -0.5f, 0.5f, -0.5f,  0.5f, 0.5f, 0.5f,  -0.5f, 0.5f, 0.5f,
                // detail quad inside the cell at y = 0.1 — not on any boundary
                -0.1f, 0.1f, -0.1f,  0.1f, 0.1f, -0.1f,  0.1f, 0.1f, 0.1f,
                -0.1f, 0.1f, -0.1f,  0.1f, 0.1f, 0.1f,  -0.1f, 0.1f, 0.1f,
        };
        float[] uvs = new float[24];
        int[] indices = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
        return new ParsedMeshData(verts, uvs, indices, triangleToFaceId, "FLAT");
    }

    private BlockModelSource sbo(ParsedMeshData mesh) {
        return BlockModelSource.of(mesh);
    }

    private static void assertAllLayers(FaceStamp faceStamp, float expected) {
        for (float layer : faceStamp.layers()) {
            assertEquals(expected, layer, 1e-4f);
        }
    }

    @Test
    void interiorTrianglesRideTheirOwnMaterialLayer() {
        FakeProvider provider = new FakeProvider(Map.of(7, 20.0f));
        StampMeshProcessor processor = new StampMeshProcessor();
        assertTrue(processor.process(new FakeBlock(47), sbo(mesh(new int[]{4, 4, 7, 7})),
                new FakeUV(), provider));

        BlockStamp stamp = processor.getBlockStamp(new FakeBlock(47));

        // Flush top triangles keep the uniform per-MMS-face layer (shipped path):
        // their authored id 4 has no mapped material here, so the provider falls
        // back to the geometric MMS face's layer.
        assertAllLayers(stamp.faces()[0], 100.0f);
        for (int f = 1; f < 6; f++) {
            assertEquals(0, stamp.faces()[f].vertexCount(), "only the top bucket carries flush triangles");
        }

        // Interior detail triangles ride the authored face's material layer (20.0),
        // not the geometric MMS face they point along.
        boolean found = false;
        for (FaceStamp interior : stamp.interior()) {
            if (interior.vertexCount() == 0) continue;
            assertAllLayers(interior, 20.0f);
            found = true;
        }
        assertTrue(found, "the detail quad must land in the interior stamps");
    }

    @Test
    void withoutAuthoredMappingsInteriorKeepsTheUniformFaceLayer() {
        // Present triangleToFaceId but no authored mapping registered: the provider
        // falls back to the geometric MMS face's layer — the shipped behaviour.
        FakeProvider provider = new FakeProvider(Map.of());
        StampMeshProcessor processor = new StampMeshProcessor();
        assertTrue(processor.process(new FakeBlock(47), sbo(mesh(new int[]{4, 4, 7, 7})),
                new FakeUV(), provider));

        BlockStamp stamp = processor.getBlockStamp(new FakeBlock(47));
        assertAllLayers(stamp.faces()[0], 100.0f);
        for (FaceStamp interior : stamp.interior()) {
            for (float layer : interior.layers()) {
                assertTrue(layer >= 100f && layer < 106f, "fallback must resolve to a geometric face layer");
            }
        }
    }

    @Test
    void aMeshWithoutAuthoredFaceIdsKeepsTheShippedBehaviour() {
        // Null triangleToFaceId (legacy file): the uniform per-MMS-face layer is
        // used for both buckets — the shipped behaviour.
        FakeProvider provider = new FakeProvider(Map.of(7, 20.0f));
        StampMeshProcessor processor = new StampMeshProcessor();
        assertTrue(processor.process(new FakeBlock(47), sbo(mesh(null)), new FakeUV(), provider));

        BlockStamp stamp = processor.getBlockStamp(new FakeBlock(47));
        assertAllLayers(stamp.faces()[0], 100.0f);
        for (FaceStamp interior : stamp.interior()) {
            for (float layer : interior.layers()) {
                assertTrue(layer >= 100f && layer < 106f,
                        "no authored ids → the uniform per-MMS-face layer, not the material's");
            }
        }
    }
}
