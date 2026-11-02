package com.openmason.engine.rendering.model.draw;

import com.openmason.engine.entity.sbe.SbeAttachmentPoint;
import com.openmason.engine.entity.sbe.SbeModelGeometry;
import com.openmason.engine.entity.sbe.SbePart;
import com.openmason.engine.format.oma.ParsedAnimClip;
import com.openmason.engine.format.oma.ParsedAnimTrack;
import com.openmason.engine.format.oma.ParsedKeyframe;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-math tests of the engine pose solver over neutral {@link ClipLayers}. */
class ModelPoseSolverTest {

    private static final float EPS = 1e-5f;

    private static SbePart part(String id, String parent, Vector3f origin) {
        return new SbePart(id, id, parent, origin, new Vector3f(), new Vector3f(), new Vector3f(1), List.of());
    }

    private static SbeModelGeometry geometry(List<SbePart> parts, List<SbeAttachmentPoint> sockets) {
        return new SbeModelGeometry(new float[0], new float[0], new int[0], parts, Map.of(), 0f, sockets);
    }

    /** Constant clip rotating {@code partId} by {@code rotDeg}. */
    private static ParsedAnimClip rotation(String partId, Vector3f rotDeg, boolean loop) {
        ParsedKeyframe k0 = new ParsedKeyframe(0f, new Vector3f(), new Vector3f(rotDeg), new Vector3f(1), "LINEAR");
        ParsedKeyframe k1 = new ParsedKeyframe(1f, new Vector3f(), new Vector3f(rotDeg), new Vector3f(1), "LINEAR");
        return new ParsedAnimClip("Clip", 24f, 1f, loop,
                List.of(new ParsedAnimTrack(partId, partId, List.of(k0, k1))));
    }

    private static Map<String, Matrix4f> solve(SbeModelGeometry g, ClipLayers layers, Matrix4f base) {
        Map<String, Matrix4f> out = new java.util.HashMap<>();
        ModelPoseSolver.forEachPartMatrix(g, layers, base, 0f, 0f, null,
                (index, part, m) -> out.put(part.id(), new Matrix4f(m)));
        return out;
    }

    @Test
    void restPoseLeavesEveryPartAtTheBase() {
        SbeModelGeometry g = geometry(List.of(part("body", null, new Vector3f()),
                part("head", "body", new Vector3f(0, 1, 0))), List.of());
        Matrix4f base = new Matrix4f().translate(3, 4, 5);
        for (Matrix4f m : solve(g, ClipLayers.REST, base).values()) {
            assertTrue(m.equals(base, EPS));
        }
        // A null frame is the rest pose too.
        for (Matrix4f m : solve(g, null, base).values()) {
            assertTrue(m.equals(base, EPS));
        }
    }

    @Test
    void childInheritsItsAnimatedParentsDelta() {
        Vector3f pivot = new Vector3f(0, 1, 0);
        SbeModelGeometry g = geometry(List.of(part("arm", null, pivot), part("hand", "arm", new Vector3f())),
                List.of());
        ClipLayers swing = ClipLayers.single(rotation("arm", new Vector3f(90, 0, 0), true), 0.5f);
        Map<String, Matrix4f> m = solve(g, swing, new Matrix4f());

        Matrix4f expectedArm = new Matrix4f().translate(pivot).rotateX((float) Math.toRadians(90))
                .translate(-pivot.x, -pivot.y, -pivot.z);
        assertTrue(m.get("arm").equals(expectedArm, EPS), m.get("arm").toString());
        assertTrue(m.get("hand").equals(expectedArm, EPS), "un-animated child rides its parent");
    }

    @Test
    void partMatricesPairEachPartWithItsIndex() {
        SbeModelGeometry g = geometry(List.of(part("a", null, new Vector3f()), part("b", null, new Vector3f()),
                part("c", null, new Vector3f())), List.of());
        List<Integer> seen = new ArrayList<>();
        ModelPoseSolver.partMatrices(g, ClipLayers.REST, new Matrix4f(), 0f, 0f, null)
                .forEachPart((index, matrix) -> seen.add(index));
        assertEquals(List.of(0, 1, 2), seen);
    }

    @Test
    void zeroWeightAndClipLessOverlaysAreIgnored() {
        SbeModelGeometry g = geometry(List.of(part("arm", null, new Vector3f())), List.of());
        ClipLayers layers = new ClipLayers(null, 0f, List.of(
                new ClipLayers.Overlay(rotation("arm", new Vector3f(0, 45, 0), true), 0.5f, 0f),
                new ClipLayers.Overlay(null, 0.5f, 1f)));
        assertTrue(solve(g, layers, new Matrix4f()).get("arm").equals(new Matrix4f(), EPS));
    }

    @Test
    void fullWeightOverlayDrivesTheMaskedPart() {
        SbeModelGeometry g = geometry(List.of(part("arm", null, new Vector3f())), List.of());
        ClipLayers layers = new ClipLayers(null, 0f, List.of(
                new ClipLayers.Overlay(rotation("arm", new Vector3f(0, 45, 0), true), 0.5f, 1f)));
        Matrix4f arm = solve(g, layers, new Matrix4f()).get("arm");
        assertTrue(arm.equals(new Matrix4f().rotateY((float) Math.toRadians(45)), 1e-4f), arm.toString());
    }

    @Test
    void socketMountsOnItsHostPartAndUnknownSocketsAreNull() {
        SbePart arm = part("arm", null, new Vector3f());
        SbeAttachmentPoint hand = new SbeAttachmentPoint("s1", "hand", "arm", "arm",
                new Vector3f(0, 0, 1), new Vector3f());
        SbeModelGeometry g = geometry(List.of(arm), List.of(hand));
        ClipLayers turn = ClipLayers.single(rotation("arm", new Vector3f(0, 90, 0), true), 0.25f);

        Matrix4f socket = ModelPoseSolver.socketWorldMatrix(g, turn, new Matrix4f(), 0f, 0f, "HAND", new Matrix4f());
        assertNotNull(socket);
        Vector3f tip = socket.transformPosition(new Vector3f());
        assertTrue(tip.equals(new Vector3f(1, 0, 0), 1e-4f), "socket follows the 90° turn: " + tip);

        assertNull(ModelPoseSolver.socketWorldMatrix(g, turn, new Matrix4f(), 0f, 0f, "missing", new Matrix4f()));
        assertNull(ModelPoseSolver.socketWorldMatrix(null, turn, new Matrix4f(), 0f, 0f, "hand", new Matrix4f()));
    }
}
