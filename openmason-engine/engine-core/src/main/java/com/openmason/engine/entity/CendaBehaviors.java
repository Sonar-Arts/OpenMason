package com.openmason.engine.entity;

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
 * FFM symbols of the Cenda behaviour scheduler ({@code cb_*} in {@code libcenda_kernels}, contract in
 * {@code cenda/native/kernels/include/cenda/behavior.h}, OpenMason #115). Only the library handshake
 * and the method handles live here; {@link BehaviorScheduler} is the API.
 *
 * <p>No Java fallback: when the library is missing or fails the {@code cb_abi_version} handshake,
 * {@link #isAvailable()} is false, {@link #unavailableReason()} says why, and
 * {@link BehaviorScheduler#create} throws with that reason.
 */
public final class CendaBehaviors {

    private static final Logger LOGGER = LoggerFactory.getLogger(CendaBehaviors.class);

    /** Must equal {@code CB_ABI_VERSION} in behavior.h (build-kernels.sh checks both sides). */
    static final int EXPECTED_ABI = 1;

    static final ValueLayout.OfInt I32 = ValueLayout.JAVA_INT;
    static final ValueLayout.OfFloat F32 = ValueLayout.JAVA_FLOAT;
    static final java.lang.foreign.AddressLayout ADDR = ValueLayout.ADDRESS;

    /** {@code cb_behavior_desc}. */
    static final long DESC_BYTES = 16;

    static final int OP_SHOULD_CONTINUE = 0;
    static final int OP_CAN_START = 1;
    static final int OP_START = 2;
    static final int OP_STOP = 3;
    static final int OP_TICK = 4;
    static final int OP_RANDOM = 5;

    static final int OK = 0;
    static final int ERR_HOST = -2;

    /** {@code cb_host_fn}: (user, op, index, dt, out_random) -> int. */
    static final FunctionDescriptor HOST_FN = FunctionDescriptor.of(I32, ADDR, I32, I32, F32, ADDR);

    private static final String FAILURE;
    static final MethodHandle LAST_ERROR;
    static final MethodHandle CREATE;
    static final MethodHandle DESTROY;
    static final MethodHandle TICK;
    static final MethodHandle STOP_ALL;
    static final MethodHandle LEADER;
    static final MethodHandle IS_RUNNING;
    static final MethodHandle RUNNING;

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
                    lookup.find("cb_abi_version").orElseThrow(
                        () -> new IllegalStateException("library has no behaviour scheduler (cb_abi_version missing)")),
                    FunctionDescriptor.of(I32));
                int have = (int) abi.invokeExact();
                if (have != EXPECTED_ABI) {
                    failure = "Cenda behaviour scheduler at " + lib + " has ABI " + have + " (expected " + EXPECTED_ABI
                        + "); rebuild with openmason-engine/cenda/build-kernels.sh";
                    lookup = null;
                } else {
                    LOGGER.info("Cenda behaviour scheduler (ABI {}) loaded from {}", have, lib);
                }
            }
        } catch (Throwable t) {
            failure = "Cenda behaviour scheduler failed to load: " + t;
            lookup = null;
        }
        if (failure != null) {
            LOGGER.error("Behaviour scheduler unavailable: {}", failure);
        }
        FAILURE = failure;
        // Calls that reach the host upcall must NOT be critical; the pure queries can be.
        Linker.Option critical = Linker.Option.critical(false);
        LAST_ERROR = handle(lookup, "cb_last_error", FunctionDescriptor.of(ADDR));
        CREATE = handle(lookup, "cb_scheduler_create", FunctionDescriptor.of(ADDR, ADDR, I32));
        DESTROY = handle(lookup, "cb_scheduler_destroy", FunctionDescriptor.ofVoid(ADDR));
        TICK = handle(lookup, "cb_scheduler_tick", FunctionDescriptor.of(I32, ADDR, ADDR, ADDR, F32));
        STOP_ALL = handle(lookup, "cb_scheduler_stop_all", FunctionDescriptor.of(I32, ADDR, ADDR, ADDR));
        LEADER = handle(lookup, "cb_scheduler_leader", FunctionDescriptor.of(I32, ADDR), critical);
        IS_RUNNING = handle(lookup, "cb_scheduler_is_running", FunctionDescriptor.of(I32, ADDR, I32), critical);
        RUNNING = handle(lookup, "cb_scheduler_running", FunctionDescriptor.of(I32, ADDR, ADDR, I32), critical);
    }

    private CendaBehaviors() {
    }

    private static MethodHandle handle(SymbolLookup lookup, String name, FunctionDescriptor fd, Linker.Option... options) {
        if (lookup == null) return null;
        return lookup.find(name)
            .map(symbol -> Linker.nativeLinker().downcallHandle(symbol, fd, options))
            .orElseThrow(() -> new IllegalStateException("Cenda behaviour scheduler symbol missing: " + name));
    }

    /** True when the native scheduler loaded and passed its handshake. */
    public static boolean isAvailable() {
        return FAILURE == null;
    }

    /** Why the native scheduler is unavailable, or null when it is available. */
    public static String unavailableReason() {
        return FAILURE;
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
}
