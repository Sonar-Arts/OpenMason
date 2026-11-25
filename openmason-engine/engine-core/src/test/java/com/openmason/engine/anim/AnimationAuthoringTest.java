package com.openmason.engine.anim;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.format.omo.OMOReader;
import com.openmason.engine.rig.Rig;
import com.openmason.engine.rig.RigModel;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * OpenMason #165: the Java binding of the native authoring session ({@code cenda/anim_author.h}) on the re-rigged
 * goblin fixture and its Walking clip - lossless open, layers, bake, the control rig's FK -> IK -> FK round trip,
 * history. The rules are tested natively (ctest anim_author).
 */
class AnimationAuthoringTest {

    private static final Path GOBLIN = Path.of("../../test-fixtures/stonebreak/resources/sbe/Mobs/SB_Goblin.sbe");

    private byte[] omo;
    private byte[] walking;

    @BeforeEach
    void load() throws IOException {
        assumeTrue(CendaAnimClip.isAvailable(), "native clip module unavailable");
        assumeTrue(Files.isRegularFile(GOBLIN), "missing " + GOBLIN.toAbsolutePath());
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(GOBLIN))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (e.getName().equals("model.omo")) omo = in.readAllBytes();
                if (e.getName().equals("states/Walking/clip.omanim")) walking = in.readAllBytes();
            }
        }
        assertNotNull(omo);
        assertNotNull(walking);
    }

    private Rig rig() throws IOException {
        OMOReader.ReadResult read = new OMOReader().read(new ByteArrayInputStream(omo));
        List<RigModel.PartSpec> parts = new ArrayList<>();
        for (OMOFormat.PartEntry p : read.parts()) {
            parts.add(new RigModel.PartSpec(p.id(), p.name(), p.parentId(), p.vertexCount() > 0,
                new Vector3f(p.originX(), p.originY(), p.originZ()), new Vector3f(p.posX(), p.posY(), p.posZ()),
                new Vector3f(p.rotX(), p.rotY(), p.rotZ()), new Vector3f(p.scaleX(), p.scaleY(), p.scaleZ())));
        }
        return RigModel.build(parts, read.bones(), null, null, null).rig();
    }

    private static double worldDiff(Rig rig, float[] a, float[] b) {
        com.openmason.engine.rig.JointPalette pa = new com.openmason.engine.rig.RigPoser().poseLocals(rig, a, new org.joml.Matrix4f(), new com.openmason.engine.rig.JointPalette());
        com.openmason.engine.rig.JointPalette pb = new com.openmason.engine.rig.RigPoser().poseLocals(rig, b, new org.joml.Matrix4f(), new com.openmason.engine.rig.JointPalette());
        double m = 0;
        org.joml.Matrix4f ma = new org.joml.Matrix4f(), mb = new org.joml.Matrix4f();
        for (int j = 0; j < rig.jointCount(); j++) {
            pa.worldMatrix(j, ma);
            pb.worldMatrix(j, mb);
            m = Math.max(m, Math.abs(ma.m30() - mb.m30()) + Math.abs(ma.m31() - mb.m31()) + Math.abs(ma.m32() - mb.m32()));
        }
        return m;
    }

    @Test
    void aClipOpensLosslesslyAndLayersBake() throws IOException {
        Rig rig = rig();
        try (AnimClipDocument clip = AnimClipDocument.read(walking);
             AnimationAuthoring a = AnimationAuthoring.create(clip, rig, null)) {
            assertArrayEquals(clip.write(), a.write(), "nothing authored: the same file");
            assertFalse(a.dirty());
            assertTrue(a.view().contains("\"trivial\":true"));
            float[] before = a.pose(0.3f, AnimationAuthoring.STAGE_FINAL, null);
            a.apply("[{\"op\":\"layer\",\"name\":\"Arms\",\"weight\":0.5},"
                + "{\"op\":\"key\",\"layer\":\"Arms\",\"joint\":\"elbow_l\",\"time\":0,\"rot\":[0,0,60]}]", "arms", null);
            assertTrue(a.view().contains("\"trivial\":false"));
            float[] after = a.pose(0.3f, AnimationAuthoring.STAGE_FINAL, null);
            assertTrue(worldDiff(rig, before, after) > 0.01, "the layer moves the arm");
            // The playable clip is the bake: it poses like the session at its keys.
            try (AnimClipDocument baked = a.playableClip()) {
                assertTrue(baked.write().length > 0);
            }
            assertEquals("arms", a.undoLabel());
            assertTrue(a.undo());
            assertArrayEquals(clip.write(), a.write());
        }
    }

    @Test
    void fkToIkToFkLeavesThePoseUnchanged() throws IOException {
        Rig rig = rig();
        try (AnimClipDocument clip = AnimClipDocument.read(walking);
             AnimationAuthoring a = AnimationAuthoring.create(clip, rig, null)) {
            a.apply("[{\"op\":\"effector\",\"name\":\"armL\",\"kind\":\"twoBone\",\"upper\":\"shoulder_l\",\"mid\":\"elbow_l\","
                + "\"end\":\"wrist_l\"}]", "effector", null);
            float t = 0.37f;
            float[] fk = a.pose(t, AnimationAuthoring.STAGE_FINAL, null);
            a.apply("[{\"op\":\"match\",\"effector\":\"armL\",\"time\":0.37,\"to\":\"ik\"}]", "to ik", null);
            assertTrue(worldDiff(rig, fk, a.pose(t, AnimationAuthoring.STAGE_FINAL, null)) < 1e-4);
            assertTrue(a.handles(t).contains("\"armL\""));
            a.apply("[{\"op\":\"match\",\"effector\":\"armL\",\"time\":0.37,\"to\":\"fk\"}]", "to fk", null);
            assertTrue(worldDiff(rig, fk, a.pose(t, AnimationAuthoring.STAGE_FINAL, null)) < 1e-4);
            assertEquals(3, a.stepCount());
        }
    }
}
