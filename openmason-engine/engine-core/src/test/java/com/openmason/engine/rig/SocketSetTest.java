package com.openmason.engine.rig;

import com.openmason.engine.character.CharacterDefinition;
import com.openmason.engine.entity.model.EntityAttachmentPoint;
import com.openmason.engine.entity.model.EntityGeometry;
import com.openmason.engine.entity.model.EntityModelLoader;
import com.openmason.engine.entity.model.EntitySockets;
import com.openmason.engine.format.omo.OMOReader;
import com.openmason.engine.rendering.model.draw.ModelPoseSolver;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Stonebreak #459: the native socket set ({@code cr_sockets}) and mounts ({@code cr_mounts}) against the Java socket
 * lookup they replaced ({@code ModelPoseSolver.socketWorldMatrix} before #459, frozen below as {@link #legacyFrame}):
 * every attachment point of every Stonebreak fixture SBE, on random poses with a non-trivial base, both palette
 * conventions (posed with the base / in model space + pre). Plus the binding's own rules (character sockets win,
 * two-sided mounts, points, unresolved mounts, allocation-free resolve).
 */
class SocketSetTest {

    private static final Path SBE_ROOT = Path.of("../../test-fixtures/stonebreak/resources/sbe");
    private static final float TOLERANCE = 2e-5f;

    @BeforeAll
    static void native_() {
        assumeTrue(CendaRig.isAvailable(), "Cenda rig unavailable: " + CendaRig.unavailableReason());
    }

    record Fixture(String name, EntityGeometry geometry) {}

    static List<Fixture> fixtures() throws IOException {
        assumeTrue(Files.isDirectory(SBE_ROOT), "Stonebreak fixtures missing at " + SBE_ROOT.toAbsolutePath());
        List<Path> files;
        try (Stream<Path> walk = Files.walk(SBE_ROOT)) {
            files = walk.filter(p -> p.toString().endsWith(".sbe")).sorted().toList();
        }
        List<Fixture> out = new ArrayList<>();
        for (Path file : files) {
            EntityGeometry g = geometry(file);
            if (g != null) out.add(new Fixture(SBE_ROOT.relativize(file).toString().replace('\\', '/'), g));
        }
        return out;
    }

    static EntityGeometry geometry(Path sbe) throws IOException {
        try (InputStream raw = Files.newInputStream(sbe); ZipInputStream in = new ZipInputStream(raw)) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (e.getName().endsWith(".omo")) {
                    return EntityModelLoader.buildGeometry(new OMOReader().read(new ByteArrayInputStream(in.readAllBytes())));
                }
            }
        }
        return null;
    }

    // ---- the pre-#459 Java lookup, frozen ----------------------------------------------------------------

    static Matrix4f legacyFrame(EntityGeometry geometry, JointPalette palette, Matrix4f base, String socketName,
                                Matrix4f dest) {
        EntityAttachmentPoint socket = null;
        for (EntityAttachmentPoint candidate : geometry.attachmentPoints()) {
            if (socketName.equalsIgnoreCase(candidate.name())) {
                socket = candidate;
                break;
            }
        }
        if (socket == null) return null;
        if (socket.isModelRoot()) {
            dest.set(base);
        } else {
            int host = legacyHostJoint(geometry, socket);
            if (host < 0 || host >= palette.count()) return null;
            palette.skinMatrix(host, dest);
        }
        Vector3f rot = socket.localRotDeg();
        return dest.translate(socket.localPos())
                .rotateXYZ((float) Math.toRadians(rot.x), (float) Math.toRadians(rot.y), (float) Math.toRadians(rot.z))
                .scale(socket.localScale());
    }

    static int legacyHostJoint(EntityGeometry geometry, EntityAttachmentPoint socket) {
        RigModel model = geometry.rig();
        if (socket.parentPartId() != null && !socket.parentPartId().isBlank()) {
            int j = model.jointOf(socket.parentPartId());
            if (j >= 0) return j;
        }
        if (socket.parentPartName() != null && !socket.parentPartName().isBlank()) {
            List<RigJoint> joints = model.joints();
            for (int j = 0; j < joints.size(); j++) {
                if (socket.parentPartName().equals(joints.get(j).name())) return j;
            }
        }
        return -1;
    }

    // ---- poses ----------------------------------------------------------------------------------------

    static float[] randomLocals(Rig rig, Random random) {
        List<RigJoint> joints = rig.joints();
        float[] v = new float[joints.size() * 9];
        for (int j = 0; j < joints.size(); j++) {
            RigJoint joint = joints.get(j);
            int o = j * 9;
            v[o] = joint.pos().x() + (random.nextFloat() - 0.5f) * 0.1f;
            v[o + 1] = joint.pos().y() + (random.nextFloat() - 0.5f) * 0.1f;
            v[o + 2] = joint.pos().z() + (random.nextFloat() - 0.5f) * 0.1f;
            v[o + 3] = joint.rotDeg().x() + (random.nextFloat() - 0.5f) * 90f;
            v[o + 4] = joint.rotDeg().y() + (random.nextFloat() - 0.5f) * 90f;
            v[o + 5] = joint.rotDeg().z() + (random.nextFloat() - 0.5f) * 90f;
            v[o + 6] = joint.scale().x();
            v[o + 7] = joint.scale().y();
            v[o + 8] = joint.scale().z();
        }
        return v;
    }

    static boolean near(Matrix4f a, Matrix4f b) {
        float scale = Math.max(1f, Math.abs(b.m30()) + Math.abs(b.m31()) + Math.abs(b.m32()));
        return a.equals(b, TOLERANCE * scale);
    }

    @Test
    void everyFixtureSocketMatchesTheOldLookup() throws IOException {
        Random random = new Random(459);
        Matrix4f base = new Matrix4f().translate(12.5f, -3f, 40.25f).rotateY(1.1f).scale(0.75f);
        int checked = 0;
        int sockets = 0;
        for (Fixture f : fixtures()) {
            EntityGeometry g = f.geometry();
            Rig rig = g.rig().rig();
            SocketSet set = EntitySockets.of(g);
            JointPalette based = new JointPalette();
            JointPalette model = new JointPalette();
            for (int trial = 0; trial < 12; trial++) {
                float[] locals = randomLocals(rig, random);
                RigPoser.local().poseLocals(rig, locals, base, based);
                RigPoser.local().poseLocals(rig, locals, null, model);
                for (EntityAttachmentPoint p : g.attachmentPoints()) {
                    Matrix4f want = legacyFrame(g, based, base, p.name(), new Matrix4f());
                    Matrix4f got = ModelPoseSolver.socketWorldMatrix(g, based, base, p.name(), new Matrix4f());
                    if (want == null) {
                        assertNull(got, f.name() + " " + p.name());
                        continue;
                    }
                    assertNotNull(got, f.name() + " " + p.name());
                    assertTrue(near(got, want), f.name() + " " + p.name() + "\n" + got + "\nwant\n" + want);
                    // Model-space palette: pre = base gives the same frame.
                    int index = set.find(p.name());
                    Matrix4f viaPre = set.frame(index, model, base, base, new Matrix4f());
                    assertTrue(near(viaPre, want), f.name() + " " + p.name() + " (pre)");
                    checked++;
                }
            }
            sockets += g.attachmentPoints().size();
        }
        assertTrue(sockets > 10, "fixtures carry attachment points: " + sockets);
        assertTrue(checked > 100, "checked " + checked);
    }

    @Test
    void characterSocketsWinAndMountsAreTwoSided() throws IOException {
        Path player = SBE_ROOT.resolve("Mobs/SB_Player.sbe");
        assumeTrue(Files.isRegularFile(player), "player fixture missing");
        EntityGeometry g = geometry(player);
        Rig rig = g.rig().rig();
        int hand = rig.find("hand_right") >= 0 ? rig.find("hand_right") : rig.joints().size() - 1;
        try (CharacterDefinition c = CharacterDefinition.create()) {
            c.setSkeleton(rig);
            String handId = rig.joints().get(hand).id();
            c.setSocket(new CharacterDefinition.Socket("Hatzone", handId, new CharacterDefinition.Vec3(0f, 0.1f, 0f),
                    new CharacterDefinition.Vec3(0f, 0f, 30f), new CharacterDefinition.Vec3(1f, 1f, 1f)));
            c.setSocket(new CharacterDefinition.Socket("grip_r", handId, new CharacterDefinition.Vec3(0f, -0.05f, 0f),
                    new CharacterDefinition.Vec3(0f, 0f, 0f), new CharacterDefinition.Vec3(1f, 1f, 1f)));
            try (SocketSet set = EntitySockets.build(g, c)) {
                int hat = set.find("hatzone");
                assertTrue(hat >= 0);
                SocketSet.Info info = set.info(hat);
                assertEquals(SocketSet.CHARACTER, info.source());
                assertEquals(hand, info.joint());
                int grip = set.find("GRIP_R");
                assertTrue(grip >= 0);

                JointPalette palette = new JointPalette();
                Matrix4f base = new Matrix4f().translate(1f, 2f, 3f).rotateY(0.4f);
                RigPoser.local().poseLocals(rig, randomLocals(rig, new Random(7)), null, palette);
                Matrix4f socket = set.frame(grip, palette, base, base, new Matrix4f());

                Matrix4f attach = new Matrix4f().translate(0f, -0.3f, 0f).rotateZ(-0.7f);
                Matrix4f offset = new Matrix4f().rotateY(0.5f);
                Matrix4f tip = new Matrix4f().translate(0f, 0.45f, 0f);
                try (MountSet mounts = new MountSet()) {
                    int held = mounts.set("held", grip, offset, attach);
                    assertEquals(0, mounts.setPoint(held, "tip", tip));
                    int gone = mounts.set("gone", -1, null, null);
                    assertEquals(1, mounts.resolve(set, palette, base, base));
                    Matrix4f item = mounts.frame(held, new Matrix4f());
                    // The item's attach point lands on socket · offset.
                    assertTrue(item.mul(attach, new Matrix4f()).equals(socket.mul(offset, new Matrix4f()), 1e-5f));
                    assertTrue(mounts.point(held, 0, new Matrix4f()).equals(item.mul(tip, new Matrix4f()), 1e-5f));
                    Vector3f tipPos = mounts.pointPosition(held, 0, new Vector3f());
                    assertTrue(tipPos.equals(item.transformPosition(new Vector3f(0f, 0.45f, 0f)), 1e-5f));
                    assertFalse(mounts.valid(gone));
                    assertNull(mounts.frame(gone, new Matrix4f()));
                    assertNull(mounts.point(held, 1, new Matrix4f()));

                    // Resolve allocates nothing once laid out.
                    mounts.resolve(set, palette, base, base);
                    java.lang.reflect.Method meter = allocationMeter();
                    assumeTrue(meter != null, "no allocation meter");
                    Matrix4f scratch = new Matrix4f();
                    for (int i = 0; i < 2000; i++) mounts.resolve(set, palette, base, base);  // warm up
                    long before = allocated(meter);
                    for (int i = 0; i < 2000; i++) {
                        mounts.resolve(set, palette, base, base);
                        mounts.frame(held, scratch);
                        mounts.pointPosition(held, 0, tipPos);
                    }
                    long used = allocated(meter) - before;
                    assertTrue(used < 16 * 1024, "resolve allocated " + used + " bytes over 2000 frames");

                    // Removing shifts, replacing drops points.
                    assertTrue(mounts.remove("held"));
                    assertEquals(0, mounts.find("gone"));
                    int again = mounts.set("held", grip, null, null);
                    assertEquals(-1, mounts.findPoint(again, "tip"));
                }
            }
        }
    }

    /** {@code com.sun.management.ThreadMXBean#getCurrentThreadAllocatedBytes}, reflectively (no jdk.management read). */
    private static java.lang.reflect.Method allocationMeter() {
        try {
            java.lang.reflect.Method m = Class.forName("com.sun.management.ThreadMXBean")
                .getMethod("getCurrentThreadAllocatedBytes");
            m.setAccessible(true);
            return (long) m.invoke(java.lang.management.ManagementFactory.getThreadMXBean()) >= 0 ? m : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static long allocated(java.lang.reflect.Method meter) {
        try {
            return (long) meter.invoke(java.lang.management.ManagementFactory.getThreadMXBean());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
