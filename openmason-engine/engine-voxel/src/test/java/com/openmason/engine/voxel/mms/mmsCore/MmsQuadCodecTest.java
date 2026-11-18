package com.openmason.engine.voxel.mms.mmsCore;

import com.openmason.engine.voxel.mms.mmsGeometry.MmsCuboidGenerator;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the QUAD16 record: the decoded corners of a packed quad are exactly
 * the vertices {@link MmsCuboidGenerator#generateScaledFaceVertices} emits for
 * the same face rectangle, UVs match the mapper's affine frame for all eight
 * orientations, and the builder round-trips through {@link MmsMeshData}.
 */
class MmsQuadCodecTest {

    private final MmsCuboidGenerator cuboids = new MmsCuboidGenerator();

    @Test
    void cornersMatchTheCuboidGeneratorForEveryFace() {
        for (int face = 0; face < 6; face++) {
            int w = 3, h = 5;
            MmsQuadMeshBuilder b = new MmsQuadMeshBuilder(1).setOrigin(1024f, 0f, -512f);
            b.addQuad(7, 70, 9, face, w, h, 0, false, false, 12, 1f, 0.75f, 0.5f, 0.25f);
            MmsMeshData mesh = b.build();
            float[] expected = cuboids.generateScaledFaceVertices(face, 1024f + 7, 70f, -512f + 9, w, h);
            float[] actual = mesh.getVertexPositions();
            assertArrayEquals(expected, actual, 0f, "face " + face + " corners");
            float[] n = mesh.getVertexNormals();
            float[] en = cuboids.generateFaceNormals(face);
            assertArrayEquals(en, n, 0f, "face " + face + " normals");
            float[] light = mesh.getLightValues();
            assertEquals(1f, light[0]);
            assertEquals(0.25f, light[3], 0.01f);
            assertArrayEquals(new float[]{12, 12, 12, 12}, mesh.getLayerIndices());
            assertArrayEquals(new int[]{0, 2, 1, 0, 3, 2}, mesh.getIndices());
            assertTrue(MmsMeshValidator.validate(mesh).isValid());
        }
    }

    @Test
    void orientationRoundTripsAllEightUvFrames() {
        for (int o = 0; o < 8; o++) {
            float u0 = MmsQuadCodec.frameU0(o), v0 = MmsQuadCodec.frameV0(o);
            float duU = MmsQuadCodec.frameDuU(o), duV = MmsQuadCodec.frameDuV(o);
            float dvU = MmsQuadCodec.frameDvU(o), dvV = MmsQuadCodec.frameDvV(o);
            assertEquals(o, MmsQuadCodec.orientation(u0, v0, duU, duV, dvU, dvV), "orientation " + o);
            // Frame corners stay inside the unit square.
            assertTrue(u0 + duU == 0f || u0 + duU == 1f);
            assertTrue(v0 + dvV == 0f || v0 + dvV == 1f || (o & 4) != 0);
        }
        assertEquals(-1, MmsQuadCodec.orientation(0.5f, 0f, 1f, 0f, 0f, 1f));
        assertEquals(-1, MmsQuadCodec.orientation(0f, 0f, 1f, 1f, 0f, 1f));
    }

    @Test
    void builderRefusesQuadsBeyondTheSharedIndexLimit() {
        MmsQuadMeshBuilder b = new MmsQuadMeshBuilder(16);
        for (int i = 0; i < MmsQuadCodec.MAX_QUADS_PER_DRAW; i++) {
            assertTrue(b.addQuad(i & 255, i >> 8, 0, 0, 1, 1, 0, false, false, 0, 1, 1, 1, 1));
        }
        assertTrue(b.isFull());
        assertTrue(!b.addQuad(0, 0, 0, 0, 1, 1, 0, false, false, 0, 1, 1, 1, 1));
        assertEquals(MmsQuadCodec.MAX_QUADS_PER_DRAW * 4, b.build().getVertexCount());
    }

    /** The pre-2026-11-17 word1 (4-bit w/h), kept to prove the widened layout is identical for w, h <= 16. */
    private static int oldWord1(int w, int h, int orientation, boolean alpha, boolean translucent, int layer) {
        return (h - 1) | (orientation << 4) | ((alpha ? 1 : 0) << 7) | ((translucent ? 1 : 0) << 8)
            | (layer << 9) | ((w - 1) << 25);
    }

    @Test
    void widenedExtentsRoundTripAndKeepTheFourBitLayoutUpTo16() {
        for (int w = 1; w <= MmsQuadCodec.MAX_EXTENT; w++) {
            for (int h = 1; h <= MmsQuadCodec.MAX_EXTENT; h++) {
                int o = (w + h) & 7;
                int layer = (w * 1000 + h) & 0xFFFF;
                int w1 = MmsQuadCodec.word1(w, h, o, (w & 1) != 0, (h & 1) != 0, layer);
                assertEquals(w, MmsQuadCodec.width(w1));
                assertEquals(h, MmsQuadCodec.height(w1));
                assertEquals(o, MmsQuadCodec.orientation(w1));
                assertEquals(layer, MmsQuadCodec.layer(w1));
                assertEquals((w & 1) != 0, MmsQuadCodec.alpha(w1));
                assertEquals((h & 1) != 0, MmsQuadCodec.translucent(w1));
                assertEquals(0, w1 >>> 31, "bit 31 stays spare");
                if (w <= 16 && h <= 16) {
                    assertEquals(oldWord1(w, h, o, (w & 1) != 0, (h & 1) != 0, layer), w1, "w=" + w + " h=" + h);
                }
            }
        }
        // Golden vectors shared with the native packer (cenda tests/voxel_mesh_test.cpp, test_packing).
        assertEquals(31 << 25, MmsQuadCodec.word1(32, 1, 0, false, false, 0));
        assertEquals(15 | (1 << 30), MmsQuadCodec.word1(1, 32, 0, false, false, 0));
        assertEquals((7 << 4) | (1 << 7) | (1 << 8) | (65535 << 9) | (16 << 25) | (1 << 30),
            MmsQuadCodec.word1(17, 17, 7, true, true, 65535));
        assertEquals(5 | (200 << 8) | (17 << 18) | (3 << 26), MmsQuadCodec.word0(5, 200, 17, 3));
        assertEquals(0xFF804000, MmsQuadCodec.word2(0f, 64f / 255f, 128f / 255f, 1f));
        assertThrows(IllegalArgumentException.class, () -> MmsQuadCodec.word1(33, 1, 0, false, false, 0));
        assertThrows(IllegalArgumentException.class, () -> MmsQuadCodec.word1(1, 0, 0, false, false, 0));
    }

    @Test
    void aWholeCubeFaceIsOneRecord() {
        for (int face = 0; face < 6; face++) {
            MmsQuadMeshBuilder b = new MmsQuadMeshBuilder(1).setOrigin(256f, 0f, -256f);
            b.addQuad(32, 64, 96, face, 32, 32, 0, false, false, 1, 1f, 1f, 1f, 1f);
            MmsMeshData mesh = b.build();
            float[] expected = cuboids.generateScaledFaceVertices(face, 256f + 32, 64f, -256f + 96, 32, 32);
            assertArrayEquals(expected, mesh.getVertexPositions(), 0f, "face " + face + " corners");
        }
    }

    @Test
    void uvsScaleWithTheRectangleInTheAuthoredFrame() {
        MmsQuadMeshBuilder b = new MmsQuadMeshBuilder(1);
        b.addQuad(0, 0, 0, 0, 4, 2, 0, true, false, 3, 1f, 1f, 1f, 1f);
        MmsMeshData mesh = b.build();
        float[] uv = mesh.getTextureCoordinates();
        // Top face corner offsets (0,1,1),(1,1,1),(1,1,0),(0,1,0): u = x·w, v = z·h
        assertArrayEquals(new float[]{0, 2, 4, 2, 4, 0, 0, 0}, uv, 0f);
        assertArrayEquals(new float[]{1, 1, 1, 1}, mesh.getAlphaTestFlags());
        ByteBuffer raw = ByteBuffer.wrap(mesh.getPackedVertexData()).order(ByteOrder.nativeOrder());
        assertEquals(16, mesh.getPackedVertexData().length);
        // Both extents live in word1 now: word0's top nibble went to the wider y field.
        assertEquals(4, MmsQuadCodec.width(raw.getInt(4)));
        assertEquals(2, MmsQuadCodec.height(raw.getInt(4)));
    }
}
