package com.openmason.api.fx;

import java.io.IOException;
import java.util.List;

/**
 * Turns an FX system into a game's own container (#102), whatever format the game ships. Engine and editor
 * stay game-neutral — they hand over the checked source ({@link FxExportInput}) and write the returned bytes where
 * the {@link FxExportTarget}'s file type says. Runs on the thread that exports (the UI thread for the Particles tab,
 * an MCP worker for agents); must not touch GL.
 */
@FunctionalInterface
public interface FxExporter {

    /** The file's bytes. Throw (with a reason) when the system cannot be shipped in this container. */
    byte[] export(FxExportInput input) throws IOException;

    /**
     * Problems that block the export, as one line each (a feature the container cannot carry, a missing game id),
     * checked before {@link #export}; empty = ready. Defaults to none.
     */
    default List<String> problems(FxExportInput input) {
        return List.of();
    }
}
