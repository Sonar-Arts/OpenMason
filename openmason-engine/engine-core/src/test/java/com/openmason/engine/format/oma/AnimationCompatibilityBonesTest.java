package com.openmason.engine.format.oma;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** OMO 1.8: bones are joints, so a clip that keys a bone is compatible with the model. */
class AnimationCompatibilityBonesTest {

    @TempDir
    Path dir;

    @Test
    void omoJointIdsIncludeBones() throws Exception {
        Path omo = dir.resolve("m.omo");
        try (OutputStream out = Files.newOutputStream(omo); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write("""
                    {"version":"1.8","parts":[{"id":"p1"},{"id":"p2"}],
                     "bones":[{"id":"b1","name":"Arm"},{"id":"b2","name":"Hand","parentBoneId":"b1"}]}
                    """.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        List<String> ids = AnimationCompatibility.readOMOPartIds(omo);
        assertEquals(List.of("p1", "p2", "b1", "b2"), ids);
        assertTrue(AnimationCompatibility.check(List.of("p1", "b2"), ids).isCompatible());
    }
}
