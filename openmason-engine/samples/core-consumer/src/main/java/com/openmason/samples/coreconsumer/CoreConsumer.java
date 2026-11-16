package com.openmason.samples.coreconsumer;

import com.openmason.engine.cenda.CendaKernels;
import com.openmason.engine.net.protocol.codec.WireCompression;
import com.openmason.engine.nav.CendaNav;
import com.openmason.engine.nav.GroundProfile;
import com.openmason.engine.nav.NavLimits;
import com.openmason.engine.nav.NavWorld;
import com.openmason.engine.physics.CendaPhysics;
import com.openmason.engine.physics.PhysicsWorld;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.Arrays;

/**
 * Uses a slice of {@code openmason.engine.core} the way an unrelated game would: native navigation
 * ({@code com.openmason.engine.nav} over a {@code PhysicsWorld}) routes around a wall on a small floor,
 * the wire codec round-trips a payload, and the native noise kernels sample a grid. The native parts
 * run when the Cenda library is built. Nothing here needs the UI runtime, the voxel stack or Skija (#387).
 */
public final class CoreConsumer {

    /**
     * What one run produced, so a test can check it without parsing output. The route fields are -1 /
     * false when the native library is not built.
     */
    public record Report(int waypoints, float pathCost, boolean routedThroughTheGap, boolean payloadRoundTripped,
                         String noise) {}

    private CoreConsumer() {
    }

    public static void main(String[] args) {
        Report report = run();
        System.out.println("route " + report.waypoints() + " waypoints, cost " + report.pathCost()
            + (report.routedThroughTheGap() ? ", through the gap" : ""));
        System.out.println("wire payload round-trip: " + report.payloadRoundTripped());
        System.out.println("noise: " + report.noise());
    }

    public static Report run() {
        if (!CendaPhysics.isAvailable() || !CendaNav.isAvailable()) {
            return new Report(-1, -1f, false, payloadRoundTrips(), sampleNoise());
        }
        try (PhysicsWorld physics = PhysicsWorld.create(WalledFloor::fill, 4, 0, 0)) {
            physics.defineShape(WalledFloor.BLOCK, new float[] {0, 0, 0, 1, 1, 1}, WalledFloor.SOLID, 0);
            try (NavWorld nav = NavWorld.create(physics, WalledFloor.SOLID, 0, 0, 0, 16, 8)) {
                GroundProfile walker = new GroundProfile(0.9f, 0, 0.5f, 1.125f, 1.5f, 3.0f, false, 3.0f, 8.0f,
                    0.5f, 2.0f, 0.5f);
                long id = nav.searchGround(new NavWorld.GroundQuery(0.5f, 1, 0.5f, 7.5f, 1, 0.5f, 0f, 4, 2, walker,
                    new NavLimits(500, Float.MAX_VALUE, 1.0f)));
                nav.step(Integer.MAX_VALUE);
                NavWorld.Outcome route = nav.poll(id);
                nav.release(id);
                // The wall at x = 4 is open only at z = 7, so a route that got past it reached that row
                // (straight runs are collapsed, so the gap itself need not be a waypoint).
                boolean throughGap = route.reachedGoal();
                float farthestZ = 0f;
                for (int i = 0; i < route.path().size(); i++) {
                    farthestZ = Math.max(farthestZ, route.path().z(i));
                }
                throughGap &= farthestZ >= 7.5f;
                return new Report(route.path().size(), route.cost(), throughGap, payloadRoundTrips(), sampleNoise());
            }
        }
    }

    private static boolean payloadRoundTrips() {
        byte[] raw = new byte[4096];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) (i / 512);
        }
        ByteBuf buf = Unpooled.buffer();
        try {
            WireCompression.writeBlob(buf, raw, 1 << 20, WireCompression.localDecodeMask());
            return Arrays.equals(raw, WireCompression.readBlob(buf, 1 << 20)) && buf.readableBytes() == 0;
        } finally {
            buf.release();
        }
    }

    private static String sampleNoise() {
        if (!CendaKernels.isAvailable()) {
            return "native kernels not built (pure-Java consumer still works)";
        }
        long node = CendaKernels.createSimplexFbm(3, 2.0f, 0.5f, 1f / 64f);
        try {
            float[] out = new float[16 * 16];
            CendaKernels.fillGrid2D(node, out, 0f, 0f, 16, 16, 1f, 1f, 1337);
            float min = Float.MAX_VALUE;
            float max = -Float.MAX_VALUE;
            for (float v : out) {
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
            return "16x16 simplex fbm in [" + min + ", " + max + "] (" + CendaKernels.simdLevel() + ")";
        } finally {
            CendaKernels.destroy(node);
        }
    }

    /** An 8x8 floor (y 0) with a wall at x = 4 except a gap at z = 7; walk from (0, 0) to (7, 0). */
    static final class WalledFloor {
        static final int SIZE = 8;
        static final int SOLID = 1;
        static final short BLOCK = 1;

        private static boolean solid(int x, int y, int z) {
            if (x < 0 || z < 0 || x >= SIZE || z >= SIZE) {
                return false;
            }
            return y == 0 || (x == 4 && z != SIZE - 1 && (y == 1 || y == 2));
        }

        static boolean fill(int x0, int y0, int z0, int sx, int sy, int sz, short[] shapes, short[] fluids) {
            for (int y = 0; y < sy; y++) {
                for (int z = 0; z < sz; z++) {
                    for (int x = 0; x < sx; x++) {
                        if (solid(x0 + x, y0 + y, z0 + z)) {
                            shapes[(y * sz + z) * sx + x] = BLOCK;
                        }
                    }
                }
            }
            return true;
        }
    }
}
