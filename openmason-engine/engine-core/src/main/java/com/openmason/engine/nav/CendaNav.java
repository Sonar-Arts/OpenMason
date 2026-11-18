package com.openmason.engine.nav;

import com.openmason.engine.cenda.CendaKernels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

/**
 * FFM symbols of Cenda navigation ({@code cn_*} in {@code libcenda_kernels}, contract in
 * {@code cenda/native/kernels/include/cenda/nav.h}, OpenMason #116), plus the struct layouts. Only the
 * library handshake, the handles and the marshalling helpers live here; {@link NavWorld},
 * {@link GroundAgent}, {@link AirAgent}, {@link NavProbes} and {@link Steer} are the API.
 *
 * <p>No Java fallback: when the library is missing or fails the {@code cn_abi_version} handshake,
 * {@link #isAvailable()} is false and every factory throws with {@link #unavailableReason()}.
 *
 * <p>ABI 3 (OpenMason #142): cells are absolute {@code long}s, positions {@code double}s relative to a
 * frame origin (the nav world's, an agent's, or for probes the physics world's), Y signed and
 * unbounded.
 */
public final class CendaNav {

    private static final Logger LOGGER = LoggerFactory.getLogger(CendaNav.class);

    /** Must equal {@code CN_ABI_VERSION} in nav.h (build-kernels.sh checks both sides). */
    static final int EXPECTED_ABI = 3;

    static final ValueLayout.OfInt I32 = ValueLayout.JAVA_INT;
    static final ValueLayout.OfLong I64 = ValueLayout.JAVA_LONG;
    static final ValueLayout.OfFloat F32 = ValueLayout.JAVA_FLOAT;
    static final ValueLayout.OfDouble F64 = ValueLayout.JAVA_DOUBLE;
    static final java.lang.foreign.AddressLayout ADDR = ValueLayout.ADDRESS;

    // struct sizes (nav.h, ABI 3)
    static final long LIMITS_BYTES = 16;
    static final long GROUND_PROFILE_BYTES = 48;
    static final long AIR_PROFILE_BYTES = 48;
    static final long WORLD_DESC_BYTES = 40;
    static final long STATS_BYTES = 56;
    static final long GROUND_QUERY_BYTES = 128;
    static final long AIR_QUERY_BYTES = 120;
    static final long SEARCH_INFO_BYTES = 24;
    static final long BODY_BYTES = 64;
    static final long APPLY_BYTES = 24;
    static final long VIEW_BYTES = 48;
    static final long GROUND_AGENT_DESC_BYTES = 112;
    static final long STEERING_DESC_BYTES = 44;
    static final long REACH_QUERY_BYTES = 96;
    static final long REACH_CELL_BYTES = 40;
    static final long AIR_AGENT_DESC_BYTES = 104;
    static final long FLOOD_QUERY_BYTES = 56;
    static final long FLOOD_CELL_BYTES = 32;

    private static final String FAILURE;
    static final MethodHandle LAST_ERROR;
    static final MethodHandle WORLD_CREATE;
    static final MethodHandle WORLD_DESTROY;
    static final MethodHandle WORLD_SET_ORIGIN;
    static final MethodHandle CELL;
    static final MethodHandle WORLD_STEP;
    static final MethodHandle STATS_GET;
    static final MethodHandle SEARCH_GROUND;
    static final MethodHandle SEARCH_AIR;
    static final MethodHandle SEARCH_POLL;
    static final MethodHandle SEARCH_POINTS;
    static final MethodHandle SEARCH_RELEASE;
    static final MethodHandle STEERING_CREATE;
    static final MethodHandle STEERING_DESTROY;
    static final MethodHandle STEERING_TICK;
    static final MethodHandle STEERING_STEER;
    static final MethodHandle STEERING_REQUEST_JUMP;
    static final MethodHandle GROUND_CREATE;
    static final MethodHandle GROUND_DESTROY;
    static final MethodHandle GROUND_SET_ORIGIN;
    static final MethodHandle GROUND_MOVE_TO;
    static final MethodHandle GROUND_STOP;
    static final MethodHandle GROUND_REPLAN;
    static final MethodHandle GROUND_TICK;
    static final MethodHandle GROUND_PATH;
    static final MethodHandle AIR_CREATE;
    static final MethodHandle AIR_DESTROY;
    static final MethodHandle AIR_SET_ORIGIN;
    static final MethodHandle AIR_SET_CRUISE;
    static final MethodHandle AIR_MOVE_TO;
    static final MethodHandle AIR_STOP;
    static final MethodHandle AIR_REPLAN;
    static final MethodHandle AIR_TICK;
    static final MethodHandle AIR_STEER_TARGET;
    static final MethodHandle AIR_PATH;
    static final MethodHandle STEER_YAW_FOR;
    static final MethodHandle STEER_TURN;
    static final MethodHandle STEER_YAW_ERROR;
    static final MethodHandle PROBE_GROUND_LEVEL;
    static final MethodHandle PROBE_FLUID_TOP;
    static final MethodHandle PROBE_CLEAR;
    static final MethodHandle PROBE_COLUMN_PEAK;
    static final MethodHandle FLOOD;
    static final MethodHandle REACH_GROUND;

    static {
        String failure = null;
        SymbolLookup lookup = null;
        try {
            Path lib = CendaKernels.libraryPath().orElse(null);
            if (lib == null) {
                failure = "Cenda library not found (searched -Dcenda.kernels.path, CENDA_KERNELS_PATH, the build "
                    + "folders and the copy packaged in engine-core); build it with openmason-engine/cenda/build-kernels.sh";
            } else {
                lookup = SymbolLookup.libraryLookup(lib, Arena.global());
                MethodHandle abi = Linker.nativeLinker().downcallHandle(
                    lookup.find("cn_abi_version").orElseThrow(
                        () -> new IllegalStateException("library has no navigation (cn_abi_version missing)")),
                    FunctionDescriptor.of(I32));
                int have = (int) abi.invokeExact();
                if (have != EXPECTED_ABI) {
                    failure = "Cenda navigation at " + lib + " has ABI " + have + " (expected " + EXPECTED_ABI
                        + "); rebuild with openmason-engine/cenda/build-kernels.sh";
                    lookup = null;
                } else {
                    LOGGER.info("Cenda navigation (ABI {}) loaded from {}", have, lib);
                }
            }
        } catch (Throwable t) {
            failure = "Cenda navigation failed to load: " + t;
            lookup = null;
        }
        if (failure != null) {
            LOGGER.error("Navigation unavailable: {}", failure);
        }
        FAILURE = failure;
        // Navigation calls may reach the physics fill upcall (cache misses): never critical, except
        // the pure ones that touch no world.
        Linker.Option critical = Linker.Option.critical(false);
        LAST_ERROR = handle(lookup, "cn_last_error", FunctionDescriptor.of(ADDR));
        WORLD_CREATE = handle(lookup, "cn_world_create", FunctionDescriptor.of(ADDR, ADDR));
        WORLD_DESTROY = handle(lookup, "cn_world_destroy", FunctionDescriptor.ofVoid(ADDR));
        WORLD_SET_ORIGIN = handle(lookup, "cn_world_set_origin", FunctionDescriptor.of(I32, ADDR, I64, I64, I64),
            critical);
        CELL = handle(lookup, "cn_cell", FunctionDescriptor.of(I32, ADDR, I64, I64, I64, ADDR));
        WORLD_STEP = handle(lookup, "cn_world_step", FunctionDescriptor.of(I32, ADDR, I32));
        STATS_GET = handle(lookup, "cn_stats_get", FunctionDescriptor.ofVoid(ADDR, ADDR), critical);
        SEARCH_GROUND = handle(lookup, "cn_search_ground", FunctionDescriptor.of(I64, ADDR, ADDR), critical);
        SEARCH_AIR = handle(lookup, "cn_search_air", FunctionDescriptor.of(I64, ADDR, ADDR), critical);
        SEARCH_POLL = handle(lookup, "cn_search_poll", FunctionDescriptor.of(I32, ADDR, I64, ADDR), critical);
        SEARCH_POINTS = handle(lookup, "cn_search_points", FunctionDescriptor.of(I32, ADDR, I64, ADDR, I32), critical);
        SEARCH_RELEASE = handle(lookup, "cn_search_release", FunctionDescriptor.ofVoid(ADDR, I64), critical);
        GROUND_CREATE = handle(lookup, "cn_ground_agent_create", FunctionDescriptor.of(ADDR, ADDR, ADDR, F64, F64, F64));
        GROUND_DESTROY = handle(lookup, "cn_ground_agent_destroy", FunctionDescriptor.ofVoid(ADDR));
        GROUND_SET_ORIGIN = handle(lookup, "cn_ground_agent_set_origin",
            FunctionDescriptor.of(I32, ADDR, I64, I64, I64), critical);
        GROUND_MOVE_TO = handle(lookup, "cn_ground_agent_move_to",
            FunctionDescriptor.of(I32, ADDR, F64, F64, F64, F32, F32, ADDR), critical);
        GROUND_STOP = handle(lookup, "cn_ground_agent_stop", FunctionDescriptor.of(I32, ADDR, ADDR, ADDR, ADDR), critical);
        GROUND_REPLAN = handle(lookup, "cn_ground_agent_replan", FunctionDescriptor.of(I32, ADDR, ADDR, ADDR), critical);
        GROUND_TICK = handle(lookup, "cn_ground_agent_tick",
            FunctionDescriptor.of(I32, ADDR, ADDR, ADDR, ADDR, ADDR, F32, ADDR, ADDR));
        STEERING_CREATE = handle(lookup, "cn_steering_create", FunctionDescriptor.of(ADDR, ADDR));
        STEERING_DESTROY = handle(lookup, "cn_steering_destroy", FunctionDescriptor.ofVoid(ADDR));
        STEERING_TICK = handle(lookup, "cn_steering_tick", FunctionDescriptor.of(I32, ADDR, I32, F32), critical);
        STEERING_STEER = handle(lookup, "cn_steering_steer",
            FunctionDescriptor.of(I32, ADDR, ADDR, ADDR, F32, F32, F32, F32, I32, ADDR));
        STEERING_REQUEST_JUMP = handle(lookup, "cn_steering_request_jump", FunctionDescriptor.of(I32, ADDR, ADDR, ADDR),
            critical);
        GROUND_PATH = handle(lookup, "cn_ground_agent_path", FunctionDescriptor.of(I32, ADDR, ADDR, I32, ADDR), critical);
        AIR_CREATE = handle(lookup, "cn_air_agent_create", FunctionDescriptor.of(ADDR, ADDR, ADDR, F64, F64, F64));
        AIR_DESTROY = handle(lookup, "cn_air_agent_destroy", FunctionDescriptor.ofVoid(ADDR));
        AIR_SET_ORIGIN = handle(lookup, "cn_air_agent_set_origin", FunctionDescriptor.of(I32, ADDR, I64, I64, I64),
            critical);
        AIR_SET_CRUISE = handle(lookup, "cn_air_agent_set_cruise", FunctionDescriptor.ofVoid(ADDR, F64), critical);
        AIR_MOVE_TO = handle(lookup, "cn_air_agent_move_to", FunctionDescriptor.of(I32, ADDR, F64, F64, F64, ADDR), critical);
        AIR_STOP = handle(lookup, "cn_air_agent_stop", FunctionDescriptor.of(I32, ADDR, ADDR, ADDR), critical);
        AIR_REPLAN = handle(lookup, "cn_air_agent_replan", FunctionDescriptor.of(I32, ADDR, ADDR, ADDR), critical);
        AIR_TICK = handle(lookup, "cn_air_agent_tick", FunctionDescriptor.of(I32, ADDR, ADDR, F64, F64, F64, F32, ADDR),
            critical);
        AIR_STEER_TARGET = handle(lookup, "cn_air_agent_steer_target",
            FunctionDescriptor.of(I32, ADDR, F64, F64, F64, ADDR), critical);
        AIR_PATH = handle(lookup, "cn_air_agent_path", FunctionDescriptor.of(I32, ADDR, ADDR, I32, ADDR), critical);
        STEER_YAW_FOR = handle(lookup, "cn_steer_yaw_for", FunctionDescriptor.of(F32, F32, F32, F32), critical);
        STEER_TURN = handle(lookup, "cn_steer_turn", FunctionDescriptor.of(F32, F32, F32, F32), critical);
        STEER_YAW_ERROR = handle(lookup, "cn_steer_yaw_error", FunctionDescriptor.of(F32, F32, F32), critical);
        PROBE_GROUND_LEVEL = handle(lookup, "cn_probe_ground_level",
            FunctionDescriptor.of(F64, ADDR, F64, F64, F64, I32, I32, I32));
        PROBE_FLUID_TOP = handle(lookup, "cn_probe_fluid_top",
            FunctionDescriptor.of(F64, ADDR, F64, F64, F64, I32, I32, I32));
        PROBE_CLEAR = handle(lookup, "cn_probe_clear", FunctionDescriptor.of(I32, ADDR, F64, F64, F64, F32, I32));
        PROBE_COLUMN_PEAK = handle(lookup, "cn_probe_column_peak",
            FunctionDescriptor.of(F64, ADDR, F64, F64, F64, I32, I32));
        FLOOD = handle(lookup, "cn_flood", FunctionDescriptor.of(I32, ADDR, ADDR, ADDR, I32));
        REACH_GROUND = handle(lookup, "cn_reach_ground", FunctionDescriptor.of(I32, ADDR, ADDR, ADDR, I32));
    }

    private CendaNav() {
    }

    private static MethodHandle handle(SymbolLookup lookup, String name, FunctionDescriptor fd, Linker.Option... options) {
        if (lookup == null) return null;
        return lookup.find(name)
            .map(symbol -> Linker.nativeLinker().downcallHandle(symbol, fd, options))
            .orElseThrow(() -> new IllegalStateException("Cenda navigation symbol missing: " + name));
    }

    /** True when native navigation loaded and passed its handshake. */
    public static boolean isAvailable() {
        return FAILURE == null;
    }

    /** Why native navigation is unavailable, or null when it is available. */
    public static String unavailableReason() {
        return FAILURE;
    }

    static void requireAvailable() {
        if (FAILURE != null) {
            throw new IllegalStateException("Cenda navigation unavailable: " + FAILURE);
        }
    }

    static String lastError() {
        try {
            MemorySegment text = (MemorySegment) LAST_ERROR.invokeExact();
            return text.reinterpret(1024).getString(0);
        } catch (Throwable t) {
            return "unknown error";
        }
    }

    static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException r) return r;
        if (t instanceof Error e) throw e;
        return new IllegalStateException(t);
    }

    // ---- marshalling ----------------------------------------------------------------------------

    static void writeLimits(MemorySegment s, long o, NavLimits l) {
        s.set(I32, o, l.maxExpansions());
        s.set(F32, o + 4, l.maxCost());
        s.set(F32, o + 8, l.heuristicWeight());
        s.set(I32, o + 12, 0);
    }

    static void writeGroundProfile(MemorySegment s, long o, GroundProfile p) {
        s.set(F32, o, p.height());
        s.set(I32, o + 4, p.columnRadius());
        s.set(F32, o + 8, p.maxStepUp());
        s.set(F32, o + 12, p.maxClimb());
        s.set(F32, o + 16, p.waterEscapeClimb());
        s.set(F32, o + 20, p.maxFall());
        s.set(I32, o + 24, p.canSwim() ? 1 : 0);
        s.set(F32, o + 28, p.wadeCostMultiplier());
        s.set(F32, o + 32, p.swimCostMultiplier());
        s.set(F32, o + 36, p.stepCost());
        s.set(F32, o + 40, p.jumpCost());
        s.set(F32, o + 44, p.fallCostPerBlock());
    }

    // cn_air_profile (48 bytes): int32 cell_size; float altitude, climb, descend; int32 allow_unknown;
    // float unknown_cost; double cruise_y; int64 min_y, max_y.
    static void writeAirProfile(MemorySegment s, long o, AirProfile p) {
        s.set(I32, o, p.cellSize());
        s.set(F32, o + 4, p.altitudeCost());
        s.set(F32, o + 8, p.climbCost());
        s.set(F32, o + 12, p.descendCost());
        s.set(I32, o + 16, p.allowUnknown() ? 1 : 0);
        s.set(F32, o + 20, p.unknownCost());
        s.set(F64, o + 24, p.cruiseY());
        s.set(I64, o + 32, p.minY());
        s.set(I64, o + 40, p.maxY());
    }

    /** Writes a {@code cn_body} (64 bytes: double x, y, z; 8 floats; uint32 flags, reserved). */
    static void writeBody(MemorySegment s, AgentBody b) {
        s.set(F64, 0, b.x);
        s.set(F64, 8, b.y);
        s.set(F64, 16, b.z);
        s.set(F32, 24, b.yaw);
        s.set(F32, 28, b.legHeight);
        s.set(F32, 32, b.halfWidth);
        s.set(F32, 36, b.halfLength);
        s.set(F32, 40, b.moveSpeed);
        s.set(F32, 44, b.speedMultiplier);
        s.set(F32, 48, b.waterSpeedFactor);
        s.set(F32, 52, b.swimStrokeReach);
        s.set(I32, 56, (b.onGround ? 1 : 0) | (b.inWater ? 2 : 0) | (b.gliding ? 4 : 0));
        s.set(I32, 60, 0);
    }

    /** Reads a {@code cn_agent_view} (48 bytes) into an {@link AgentView}. */
    static void readView(MemorySegment v, AgentView out) {
        out.set(v.get(I32, 0), v.get(I32, 4), v.get(I32, 8) != 0, v.get(I32, 12),
            v.get(F64, 16), v.get(F64, 24), v.get(F64, 32), v.get(I32, 40));
    }

    /** Writes an absolute origin as three int64s ({@code const int64_t*} arguments). */
    static MemorySegment origin(java.lang.foreign.Arena arena, long ox, long oy, long oz) {
        MemorySegment o = arena.allocate(I64, 3);
        o.set(I64, 0, ox);
        o.set(I64, 8, oy);
        o.set(I64, 16, oz);
        return o;
    }
}
