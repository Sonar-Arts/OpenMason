package com.openmason.api.fx;

import com.openmason.engine.rendering.particles.FxParameter;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * FX a plugin knows beyond the project's {@code .omfx} files, contributed through
 * {@code PluginContext.fxCatalogSources()}: typically the FX a game already ships (Stonebreak's {@code sbfx/}
 * folder). The SBO/SBE editors' FX tab checks bindings against them and offers them in its picker, so binding a
 * shipped FX by its game id ({@code stonebreak:torch_fire}) is known, with its parameters, instead of "unknown".
 *
 * <p>{@link Lister#list} runs when the FX tab scans (first use and Rescan), on the UI thread: keep it to a folder
 * read. A failing lister is reported as an unreadable source, never thrown at the editor.
 *
 * @param id           unique id ({@code <plugin>.<what>})
 * @param displayName  shown before each entry's origin in the FX picker ("Stonebreak")
 * @param gameTargetId the game target these FX belong to (listed only while it is active), or null for any
 * @param lister       reads the entries
 */
public record FxCatalogSource(String id, String displayName, String gameTargetId, Lister lister) {

    public FxCatalogSource {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(lister, "lister");
        if (id.isBlank()) {
            throw new IllegalArgumentException("FX catalog source id is blank");
        }
        if (displayName == null || displayName.isBlank()) {
            displayName = id;
        }
        if (gameTargetId != null && gameTargetId.isBlank()) {
            gameTargetId = null;
        }
    }

    /** True when the source applies while {@code activeTargetId} (null = none) is the project's game target. */
    public boolean appliesTo(String activeTargetId) {
        return gameTargetId == null || gameTargetId.equals(activeTargetId);
    }

    /** Reads a source's entries. */
    @FunctionalInterface
    public interface Lister {
        List<Entry> list() throws IOException;
    }

    /**
     * One FX the source knows.
     *
     * @param id         the id an asset binding names it by (namespaced ids also match by the name after the colon)
     * @param origin     where it comes from ("sbfx/SB_Torch_Fire.sbfx")
     * @param parameters the parameters a binding may override
     */
    public record Entry(String id, String origin, List<FxParameter> parameters) {
        public Entry {
            Objects.requireNonNull(id, "id");
            if (id.isBlank()) {
                throw new IllegalArgumentException("FX catalog entry id is blank");
            }
            origin = origin == null ? "" : origin;
            parameters = parameters == null ? List.of() : List.copyOf(parameters);
        }
    }
}
