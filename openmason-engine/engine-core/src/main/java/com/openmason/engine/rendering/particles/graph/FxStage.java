package com.openmason.engine.rendering.particles.graph;

import java.util.List;
import java.util.Objects;

/** One stage's module stack, run top to bottom. */
public record FxStage(FxStageKind kind, List<FxModule> modules) {

    public FxStage {
        Objects.requireNonNull(kind, "kind");
        modules = List.copyOf(modules == null ? List.of() : modules);
    }

    public static FxStage of(FxStageKind kind, FxModule... modules) {
        return new FxStage(kind, List.of(modules));
    }
}
