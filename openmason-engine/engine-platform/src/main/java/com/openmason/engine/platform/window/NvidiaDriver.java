package com.openmason.engine.platform.window;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NVIDIA driver knowledge the display backend needs, read without a GL/EGL context (#96, from
 * Stonebreak's {@code DisplayBackend}). The backend has to be chosen before {@code glfwInit()} and
 * without touching a context: initializing EGL on Wayland and then using X11/GLX in the same process
 * makes the driver reject {@code glXMakeCurrent} with BadAccess. So the version comes from
 * {@code /proc/driver/nvidia/version}, the same version {@code GL_VERSION} reports later.
 */
public final class NvidiaDriver {

    private static final Logger logger = LoggerFactory.getLogger(NvidiaDriver.class);

    /**
     * Driver versions whose native-Wayland EGL path is broken badly enough (libEGL_nvidia SIGSEGV)
     * that {@link BackendPolicy#AUTO_WAYLAND} pins X11 (XWayland) instead.
     */
    public static final Set<String> WAYLAND_BLOCKED = Set.of(
            "595.71.05",
            // Open Kernel Module builds; same libEGL_nvidia SIGSEGV, still unfixed on the 595 branch.
            "595.84",
            "595.91.07",
            "595.99.02",
            "595.104.02"
    );

    /**
     * The first dotted number on the "NVRM version:" line works across driver flavors that word the
     * line differently ("... Kernel Module  595.71.05 ..." for the proprietary module,
     * "... Open Kernel Module for x86_64  610.43.02 ..." for the open one).
     */
    private static final Pattern VERSION = Pattern.compile("([0-9]+(?:\\.[0-9]+)+)");

    private static final Path VERSION_FILE = Path.of("/proc/driver/nvidia/version");

    private NvidiaDriver() {
    }

    /** The loaded driver's version, or null (no proprietary NVIDIA driver, nouveau, unreadable). */
    public static String detect() {
        try {
            if (!Files.isReadable(VERSION_FILE)) {
                return null;
            }
            return parseVersion(Files.readAllLines(VERSION_FILE));
        } catch (Exception e) {
            logger.debug("Could not read NVIDIA driver version: {}", e.toString());
            return null;
        }
    }

    /**
     * The driver version ("595.71.05") from the lines of {@code /proc/driver/nvidia/version}, or null
     * when no NVRM line carries one.
     */
    public static String parseVersion(Iterable<String> procVersionLines) {
        for (String line : procVersionLines) {
            // Only the NVRM line carries the driver version; other lines (the GCC version) also
            // contain dotted numbers we must not pick up.
            if (!line.contains("NVRM")) {
                continue;
            }
            Matcher m = VERSION.matcher(line);
            if (m.find()) {
                return m.group(1);
            }
        }
        return null;
    }

    /** True when {@code version} is on the native-Wayland blocklist. */
    public static boolean isWaylandBlocked(String version) {
        return version != null && WAYLAND_BLOCKED.contains(version);
    }
}
