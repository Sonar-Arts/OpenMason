package com.openmason.engine.rendering.particles;

import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

/** GL-free cull / sort / pack stage: exactly what ends up in the instance buffer. */
class ParticleBatchBuilderTest {

    private final ByteBuffer out = MemoryUtil.memAlloc(64 * 1024 * ParticleVertexFormat.STRIDE)
            .order(ByteOrder.nativeOrder());

    @AfterEach
    void free() {
        MemoryUtil.memFree(out);
    }

    /** Camera at the origin looking down -Z, 90° vertical FOV. */
    static ParticleFrame frame() {
        ParticleFrame f = new ParticleFrame();
        f.camera(new Matrix4f(), new Matrix4f().perspective((float) Math.toRadians(90), 1f, 0.1f, 200f), 0, 0, 0);
        return f;
    }

    private static ParticleEffect.Builder still(String name) {
        return ParticleEffect.builder(name).lifetime(10, 10).speed(0, 0).spread(0).rate(0).fade(0, 0);
    }

    private float posZ(int k) { return out.getFloat(k * ParticleVertexFormat.STRIDE + 8); }
    private float posX(int k) { return out.getFloat(k * ParticleVertexFormat.STRIDE); }
    private int colorByte(int k, int channel) { return out.get(k * ParticleVertexFormat.STRIDE + 12 + channel) & 0xFF; }
    private float size(int k) { return Float.float16ToFloat(out.getShort(k * ParticleVertexFormat.STRIDE + 16)); }
    private float rotation(int k) { return Float.float16ToFloat(out.getShort(k * ParticleVertexFormat.STRIDE + 18)); }
    private int frameCode(int k) { return out.getShort(k * ParticleVertexFormat.STRIDE + 20) & 0xFFFF; }
    private int flags(int k) { return out.getShort(k * ParticleVertexFormat.STRIDE + 22) & 0xFFFF; }
    private float axis(int k, int c) { return Float.float16ToFloat(out.getShort(k * ParticleVertexFormat.STRIDE + 24 + 2 * c)); }
    private float softness(int k) { return Float.float16ToFloat(out.getShort(k * ParticleVertexFormat.STRIDE + 30)); }

    @Test
    void culledParticlesAreNeitherSortedNorPacked() {
        ParticleSystem s = new ParticleSystem(10);
        ParticleEffect e = still("e").build();
        s.burst(e, 0, 0, -5, 1);   // visible
        s.burst(e, 0, 0, 5, 1);    // behind the camera
        s.burst(e, 50, 0, -5, 1);  // far outside the side planes
        s.burst(e, 0, 0, -150, 1); // inside the frustum but beyond maxDistance
        ParticleFrame f = frame();
        f.maxDistance = 100;
        ParticleBatchBuilder b = new ParticleBatchBuilder();
        assertEquals(1, b.prepare(s, f));
        b.pack(out);
        assertEquals(-5f, posZ(0));
    }

    @Test
    void particlesStraddlingTheFrustumEdgeAreKept() {
        ParticleSystem s = new ParticleSystem(4);
        // Centre 0.3 outside the right plane (x = -z) but the 1-wide quad reaches in.
        s.burst(still("big").size(1, 1).build(), 5.3, 0, -5, 1);
        assertEquals(1, new ParticleBatchBuilder().prepare(s, frame()));
    }

    @Test
    void alphaParticlesAreOrderedBackToFrontWhateverTheSpawnOrder() {
        ParticleSystem s = new ParticleSystem(64);
        ParticleEffect e = still("alpha").build();
        float[] zs = {-3, -40, -7, -1, -22, -15, -2, -9};
        for (float z : zs) {
            s.burst(e, 0, 0, z, 1);
        }
        ParticleBatchBuilder b = new ParticleBatchBuilder();
        assertEquals(zs.length, b.prepare(s, frame()));
        assertTrue(b.sortedLastBuild());
        b.pack(out);
        for (int k = 1; k < zs.length; k++) {
            assertTrue(posZ(k - 1) <= posZ(k), "far (more negative z) first: " + posZ(k - 1) + " then " + posZ(k));
        }
        assertEquals(-40f, posZ(0));
        assertEquals(-1f, posZ(zs.length - 1));
    }

    @Test
    void radixSortHandlesThousandsAndIsStableForEqualDepths() {
        ParticleSystem s = new ParticleSystem(20_000, 7);
        ParticleEffect e = still("cloud").box(30, 30, 0).build();
        for (int z = 1; z <= 20; z++) {
            s.burst(e, 0, 0, -z * 3, 1000);
        }
        ParticleBatchBuilder b = new ParticleBatchBuilder();
        int n = b.prepare(s, frame());
        assertTrue(n > 5000);
        b.pack(out);
        // View depth = -z for a camera looking down -Z: non-increasing depth == non-decreasing z.
        for (int k = 1; k < n; k++) {
            assertTrue(posZ(k - 1) <= posZ(k));
            if (posZ(k - 1) == posZ(k)) {
                assertTrue(b.visibleSlot(k - 1) < b.visibleSlot(k), "equal depths keep pool order");
            }
        }
    }

    @Test
    void purelyAdditiveBatchesSkipTheSort() {
        ParticleSystem s = new ParticleSystem(16);
        ParticleEffect glow = still("glow").additive(1).build();
        s.burst(glow, 0, 0, -3, 1);
        s.burst(glow, 0, 0, -9, 1);
        ParticleBatchBuilder b = new ParticleBatchBuilder();
        assertEquals(2, b.prepare(s, frame()));
        assertFalse(b.sortedLastBuild(), "order-independent: no sort cost");
        s.burst(still("smoke").build(), 0, 0, -5, 1);
        b.prepare(s, frame());
        assertTrue(b.sortedLastBuild());
    }

    @Test
    void recordsCarryPremultipliedColourSizeRotationFrameAndFlags() {
        ParticleSystem s = new ParticleSystem(8);
        ParticleEffect e = still("rec").colors(0xFF804080, 0x00000000).additive(0.5f).emissive(true)
                .size(2, 4).rotation(90, 90).sprite(ParticleEffect.Sprite.SQUARE).build();
        s.burst(e, 1.5, 2.5, -3.5, 1);
        ParticleBatchBuilder b = new ParticleBatchBuilder();
        b.prepare(s, frame());
        b.pack(out);
        assertEquals(1.5f, posX(0));
        assertEquals(-3.5f, posZ(0));
        // alpha = 0x80/255 ≈ 0.502; rgb premultiplied; stored alpha × (1 - additive).
        assertEquals(128, colorByte(0, 0), 1);
        assertEquals(64, colorByte(0, 1), 1);
        assertEquals(32, colorByte(0, 2), 1);
        assertEquals(64, colorByte(0, 3), 1);
        assertEquals(2f, size(0));
        assertEquals((float) Math.toRadians(90), rotation(0), 2e-3f);
        assertEquals(ParticleVertexFormat.FRAME_SQUARE, frameCode(0));
        assertEquals(ParticleVertexFormat.FLAG_EMISSIVE, flags(0));

        // Half-way through life: size and colour interpolate.
        s.age[0] = 5f;
        b.prepare(s, frame());
        b.pack(out);
        assertEquals(3f, size(0));
        // r 127.5 premultiplied by alpha (128 → 0 half-way = 64/255).
        assertEquals(32, colorByte(0, 0), 1);
    }

    @Test
    void fadeRampsScaleAlphaAtBothEnds() {
        ParticleSystem s = new ParticleSystem(4);
        ParticleEffect e = still("fade").lifetime(1, 1).fade(0.2f, 0.5f).color(0xFFFFFFFF).build();
        s.burst(e, 0, 0, -2, 1);
        ParticleBatchBuilder b = new ParticleBatchBuilder();
        float[][] cases = {{0.1f, 0.5f}, {0.3f, 1f}, {0.5f, 1f}, {0.75f, 0.5f}, {0.95f, 0.1f}};
        for (float[] c : cases) {
            s.age[0] = c[0];
            b.prepare(s, frame());
            b.pack(out);
            assertEquals(c[1] * 255, colorByte(0, 3), 1.5, "alpha at t=" + c[0]);
        }
    }

    @Test
    void textureFramesAnimateOverLifeOrStayRandomlyPicked() {
        ParticleSystem s = new ParticleSystem(400, 3);
        java.util.Map<String, Integer> layers = java.util.Map.of("a", 8, "b", 9, "c", 10, "d", 11,
                "x", 4, "y", 5, "z", 6);
        s.setSprites(name -> layers.getOrDefault(name, -1));
        ParticleEffect anim = still("anim").lifetime(1, 1)
                .textures(java.util.List.of("a", "b", "c", "d"), true).build();
        s.burst(anim, 0, 0, -2, 1);
        ParticleBatchBuilder b = new ParticleBatchBuilder();
        float[] ages = {0f, 0.24f, 0.26f, 0.6f, 0.99f};
        int[] frames = {8, 8, 9, 10, 11};
        for (int i = 0; i < ages.length; i++) {
            s.age[0] = ages[i];
            b.prepare(s, frame());
            b.pack(out);
            assertEquals(frames[i], frameCode(0), "frame at t=" + ages[i]);
        }
        s.clearParticles();
        ParticleEffect pick = still("pick").textures(java.util.List.of("x", "y", "z"), false).build();
        s.burst(pick, 0, 0, -2, 300);
        int n = b.prepare(s, frame());
        b.pack(out);
        int[] seen = new int[16];
        for (int k = 0; k < n; k++) {
            seen[frameCode(k)]++;
        }
        assertTrue(seen[4] > 50 && seen[5] > 50 && seen[6] > 50, "each of frames 4..6 picked");
        assertEquals(n, seen[4] + seen[5] + seen[6]);
    }

    @Test
    void textureNamesResolveToLayersAndReResolveWhenTheSpritesChange() {
        ParticleSystem s = new ParticleSystem(16);
        ParticleEffect flip = still("flip").lifetime(1, 1)
                .textures(java.util.List.of("one", "two", "gone"), true).build();
        s.burst(flip, 0, 0, -2, 1);
        ParticleBatchBuilder b = new ParticleBatchBuilder();
        s.age[0] = 0.1f;
        b.prepare(s, frame());
        b.pack(out);
        assertEquals(ParticleVertexFormat.FRAME_MISSING, frameCode(0), "no sprites yet: the missing checker");

        s.setSprites(name -> switch (name) { case "one" -> 3; case "two" -> 7; default -> -1; });
        b.prepare(s, frame());
        b.pack(out);
        assertEquals(3, frameCode(0));
        s.age[0] = 0.5f;
        b.prepare(s, frame());
        b.pack(out);
        assertEquals(7, frameCode(0));
        s.age[0] = 0.9f;
        b.prepare(s, frame());
        b.pack(out);
        assertEquals(ParticleVertexFormat.FRAME_MISSING, frameCode(0), "an unpacked frame shows the checker");

        // Hot-swap to a shorter list: the live particle keeps a valid frame.
        ParticleEffect shorter = flip.toBuilder().textures(java.util.List.of("two"), true).build();
        assertTrue(s.replace(flip, shorter));
        b.prepare(s, frame());
        b.pack(out);
        assertEquals(7, frameCode(0));
    }

    @Test
    void proceduralSpritesKeepTheirCodesWhateverTheLayers() {
        ParticleSystem s = new ParticleSystem(16);
        s.setSprites(name -> 0);
        s.burst(still("disc").build(), 0, 0, -2, 1);
        s.burst(still("sq").sprite(ParticleEffect.Sprite.SQUARE)
                .textureNames(java.util.List.of("kept")).build(), 0, 0, -2, 1);
        ParticleBatchBuilder b = new ParticleBatchBuilder();
        int n = b.prepare(s, frame());
        b.pack(out);
        java.util.Set<Integer> codes = new java.util.HashSet<>();
        for (int k = 0; k < n; k++) {
            codes.add(frameCode(k));
        }
        assertEquals(java.util.Set.of(ParticleVertexFormat.FRAME_SOFT_DISC, ParticleVertexFormat.FRAME_SQUARE), codes);
    }

    @Test
    void rotationIsWrappedIntoHalfFloatFriendlyRange() {
        ParticleSystem s = new ParticleSystem(4);
        s.burst(still("spin").sprite(ParticleEffect.Sprite.SQUARE).build(), 0, 0, -2, 1);
        s.rot[0] = (float) (Math.PI * 2 * 1000 + 0.5);
        ParticleBatchBuilder b = new ParticleBatchBuilder();
        b.prepare(s, frame());
        b.pack(out);
        assertEquals(0.5f, rotation(0), 0.01f);
    }

    @Test
    void renderOriginAndAnchorAreAppliedAtFullPrecision() {
        ParticleSystem s = new ParticleSystem(4);
        s.setAnchor(5_000_000, 0, 5_000_000);
        s.burst(still("far").build(), 5_000_000.125, 1.5, 4_999_996.875, 1);
        ParticleFrame f = frame().origin(5_000_000, 0, 5_000_000);
        ParticleBatchBuilder b = new ParticleBatchBuilder();
        assertEquals(1, b.prepare(s, f));
        b.pack(out);
        assertEquals(0.125f, posX(0), 1e-6f);
        assertEquals(-3.125f, posZ(0), 1e-6f);
    }

    @Test
    void zeroSizedParticlesAreSkipped() {
        ParticleSystem s = new ParticleSystem(4);
        s.burst(still("vanish").size(0, 0).build(), 0, 0, -2, 1);
        assertEquals(0, new ParticleBatchBuilder().prepare(s, frame()));
    }
    @Test
    void colourGradientKeysAreFollowedAcrossLife() {
        ParticleSystem s = new ParticleSystem(4);
        ParticleEffect e = still("grad").lifetime(1, 1).sprite(ParticleEffect.Sprite.SQUARE)
                .colorKey(0f, 0xFF0000FF).colorKey(0.5f, 0x00FF00FF).colorKey(1f, 0x0000FFFF).build();
        s.burst(e, 0, 0, -2, 1);
        ParticleBatchBuilder b = new ParticleBatchBuilder();
        float[][] cases = {{0f, 255, 0, 0}, {0.25f, 128, 128, 0}, {0.5f, 0, 255, 0}, {0.75f, 0, 128, 128}, {0.999f, 0, 0, 255}};
        for (float[] c : cases) {
            s.age[0] = c[0];
            b.prepare(s, frame());
            b.pack(out);
            assertEquals(c[1], colorByte(0, 0), 3, "red at t=" + c[0]);
            assertEquals(c[2], colorByte(0, 1), 3, "green at t=" + c[0]);
            assertEquals(c[3], colorByte(0, 2), 3, "blue at t=" + c[0]);
        }
    }

    @Test
    void sizeCurveScalesTheLinearSize() {
        ParticleSystem s = new ParticleSystem(4);
        ParticleEffect e = still("grow").lifetime(1, 1).size(2, 2)
                .sizeKey(0f, 0f).sizeKey(0.2f, 1.5f).sizeKey(1f, 0.5f).build();
        s.burst(e, 0, 0, -3, 1);
        ParticleBatchBuilder b = new ParticleBatchBuilder();
        float[][] cases = {{0.1f, 1.5f}, {0.2f, 3f}, {0.6f, 2f}, {1f - 1e-4f, 1f}};
        for (float[] c : cases) {
            s.age[0] = c[0];
            assertEquals(1, b.prepare(s, frame()));
            b.pack(out);
            assertEquals(c[1], size(0), 0.03f, "size at t=" + c[0]);
        }
        s.age[0] = 0f;
        assertEquals(0, b.prepare(s, frame()), "a zero-size moment is culled");
    }

    @Test
    void velocityStretchedRecordsCarryTheTrailAndOrientation() {
        ParticleSystem s = new ParticleSystem(4);
        ParticleEffect streak = still("streak").speed(10, 10).direction(1, 0, 0).stretched(0.05f).softness(0.5f).build();
        s.burst(streak, 0, 0, -3, 1);
        ParticleBatchBuilder b = new ParticleBatchBuilder();
        b.prepare(s, frame());
        b.pack(out);
        assertEquals(0.5f, axis(0, 0), 1e-3f, "trail = velocity × stretch");
        assertEquals(0f, axis(0, 1));
        assertEquals(0.5f, softness(0));
        assertEquals(ParticleEffect.Orientation.VELOCITY.ordinal(),
                flags(0) >> ParticleVertexFormat.ORIENTATION_SHIFT);

        s.clearParticles();
        s.burst(still("upright").orientation(ParticleEffect.Orientation.AXIS_Y).speed(10, 10).build(), 0, 0, -3, 1);
        b.prepare(s, frame());
        b.pack(out);
        assertEquals(0f, axis(0, 1), "only velocity-stretched particles carry a trail");
        assertEquals(ParticleEffect.Orientation.AXIS_Y.ordinal(), flags(0) >> ParticleVertexFormat.ORIENTATION_SHIFT);
    }

    @Test
    void aLongTrailKeepsItsParticleInViewWhenTheHeadIsOutside() {
        ParticleSystem s = new ParticleSystem(4);
        // Head 0.6 beyond the right plane at z=-5 (x = 5.6), trail 2 blocks back into view.
        s.burst(still("streak").size(0.05f, 0.05f).speed(20, 20).direction(1, 0, 0).stretched(0.1f).build(),
                5.6, 0, -5, 1);
        assertEquals(1, new ParticleBatchBuilder().prepare(s, frame()));
        s.clearParticles();
        s.burst(still("dot").size(0.05f, 0.05f).speed(20, 20).direction(1, 0, 0).build(), 5.6, 0, -5, 1);
        assertEquals(0, new ParticleBatchBuilder().prepare(s, frame()), "without a trail it is culled");
    }

    @Test
    void splitPlanePutsFarSideParticlesFirstAndKeepsEachGroupSorted() {
        ParticleSystem s = new ParticleSystem(64);
        ParticleEffect e = still("p").build();
        // Camera at y=0 looking down -Z; "water" plane at y = -1. Below it: y = -2; above: y = 0.
        float[][] spots = {{-2, -3}, {0, -6}, {-2, -9}, {0, -2}, {-2, -5}, {0, -10}};
        for (float[] p : spots) {
            s.burst(e, 0, p[0], p[1], 1);
        }
        ParticleFrame f = frame().splitAtHeight(-1f);
        ParticleBatchBuilder b = new ParticleBatchBuilder();
        assertEquals(6, b.prepare(s, f));
        assertEquals(3, b.behindCount());
        b.pack(out);
        for (int k = 0; k < 6; k++) {
            float y = out.getFloat(k * ParticleVertexFormat.STRIDE + 4);
            assertEquals(k < 3 ? -2f : 0f, y, "group " + (k < 3 ? "behind" : "front"));
        }
        assertTrue(posZ(0) < posZ(1) && posZ(1) < posZ(2), "behind group back to front");
        assertTrue(posZ(3) < posZ(4) && posZ(4) < posZ(5), "front group back to front");
        f.noSplit();
        b.prepare(s, f);
        assertEquals(0, b.behindCount());
    }
}
