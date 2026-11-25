package com.openmason.engine.format.omo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** OMO 1.8 bone + skin parsing in the engine reader. */
class OMOSkinReadTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void bonesParseWithDefaultsAndPartParents() throws Exception {
        var bones = OMOReader.parseBones(mapper.readTree("""
                [{"id":"b1","name":"spine","parentBoneId":"part-1","posY":1.5,"endpointY":0.5},
                 {"id":"b2"},
                 {"name":"no id"}]"""));
        assertEquals(2, bones.size());
        assertEquals("part-1", bones.get(0).parentBoneId());
        assertEquals(1.5f, bones.get(0).posY());
        assertEquals(0.5f, bones.get(0).endpointY());
        assertEquals("b2", bones.get(1).name(), "a nameless bone is named by its id");
        assertTrue(bones.get(1).isRoot());
    }

    @Test
    void skinParsesPerPartRowsAndSkipsMalformedOnes() throws Exception {
        OMOFormat.SkinData skin = OMOReader.parseSkin(mapper.readTree("""
                {"joints":["b1","part-1"],
                 "parts":[{"partId":"part-1","vertices":[[0, 0, 0.75, 1, 0.25],
                                                           [1, 5, 1.0],
                                                           [2, 0],
                                                           [3, 1, 1.0]]},
                          {"vertices":[[0,0,1]]}]}"""));
        assertEquals(2, skin.jointIds().size());
        assertEquals(1, skin.parts().size());
        var vertices = skin.parts().get(0).vertices();
        assertEquals(2, vertices.size(), "out-of-range joint and even-length rows are skipped");
        assertArrayEquals(new int[]{0, 1}, vertices.get(0).joints());
        assertArrayEquals(new float[]{0.75f, 0.25f}, vertices.get(0).weights());
        assertEquals(3, vertices.get(1).localVertex());
        assertTrue(!skin.isEmpty());
        assertNull(OMOReader.parseSkin(null));
        assertTrue(OMOReader.parseSkin(mapper.readTree("{}")).isEmpty());
    }
}
