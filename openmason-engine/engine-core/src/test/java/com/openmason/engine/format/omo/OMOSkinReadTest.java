package com.openmason.engine.format.omo;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** OMO 1.8 bone + skin reading in the engine reader (native codec, #167). */
class OMOSkinReadTest {

    private static final String HEADER = """
            "version":"1.8","objectName":"T","modelType":"cube","textureFile":"texture.omt",
            "geometry":{"width":1,"height":1,"depth":1,"position":{"x":0,"y":0,"z":0}}
            """;

    @BeforeAll
    static void needsNative() {
        assumeTrue(CendaOmo.isAvailable(), () -> "native model module: " + CendaOmo.unavailableReason());
    }

    private static OMOReader.ReadResult read(String sections) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry(OMOFormat.MANIFEST_FILENAME));
            zos.write(("{" + HEADER + (sections.isEmpty() ? "" : "," + sections) + "}")
                    .getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return new OMOReader().read(baos.toByteArray());
    }

    @Test
    void bonesReadWithDefaultsAndPartParents() throws Exception {
        var bones = read("""
                "bones":[{"id":"b1","name":"spine","parentBoneId":"part-1","posY":1.5,"endpointY":0.5},
                         {"id":"b2"},
                         {"name":"no id"}]""").bones();
        assertEquals(2, bones.size());
        assertEquals("part-1", bones.get(0).parentBoneId());
        assertEquals(1.5f, bones.get(0).posY());
        assertEquals(0.5f, bones.get(0).endpointY());
        assertEquals("b2", bones.get(1).name(), "a nameless bone is named by its id");
        assertTrue(bones.get(1).isRoot());
    }

    @Test
    void skinReadsPerPartRowsAndSkipsMalformedOnes() throws Exception {
        OMOFormat.SkinData skin = read("""
                "skin":{"joints":["b1","part-1"],
                 "parts":[{"partId":"part-1","vertices":[[0, 0, 0.75, 1, 0.25],
                                                           [1, 5, 1.0],
                                                           [2, 0],
                                                           [3, 1, 1.0]]},
                          {"vertices":[[0,0,1]]}]}""").skin();
        assertEquals(2, skin.jointIds().size());
        assertEquals(1, skin.parts().size());
        var vertices = skin.parts().get(0).vertices();
        assertEquals(2, vertices.size(), "out-of-range joint and even-length rows are skipped");
        assertArrayEquals(new int[]{0, 1}, vertices.get(0).joints());
        assertArrayEquals(new float[]{0.75f, 0.25f}, vertices.get(0).weights());
        assertEquals(3, vertices.get(1).localVertex());
        assertTrue(!skin.isEmpty());
        assertNull(read("").skin());
        assertNull(read("\"skin\":{}").skin(), "a skin without weighted vertices reads as none");
    }
}
