package com.openmason.engine.rendering.particles.graph;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The FX graph IR — the source of truth for an effect's behaviour (OM #99): custom attributes plus a module stack
 * per {@link FxStageKind}. Versioned, serialisable ({@link FxGraphJson}) and backend-neutral: the CPU backend
 * compiles it to a batch program today, a GPU backend can compile the same IR later.
 *
 * <p>A stage the graph leaves out runs its default stack ({@link #defaultStack}); a stage it lists runs exactly the
 * listed modules, so custom modules go anywhere in the order and built-ins can be dropped or reordered.
 */
public record FxGraph(int version, List<FxAttribute> attributes, List<FxStage> stages) {

    /** IR version this engine writes and reads. */
    public static final int VERSION = 1;

    public FxGraph {
        attributes = List.copyOf(attributes == null ? List.of() : attributes);
        stages = List.copyOf(stages == null ? List.of() : stages);
        if (version != VERSION) {
            throw new IllegalArgumentException("unsupported FX graph version " + version + " (this engine reads "
                    + VERSION + ")");
        }
        Set<FxStageKind> kinds = new HashSet<>();
        for (FxStage s : stages) {
            if (!kinds.add(s.kind())) {
                throw new IllegalArgumentException("stage " + s.kind() + " listed twice");
            }
        }
        Set<String> names = new HashSet<>();
        for (FxAttribute a : attributes) {
            if (!names.add(a.name())) {
                throw new IllegalArgumentException("attribute '" + a.name() + "' declared twice");
            }
        }
    }

    /** A graph of the given stages and attributes at the current version. */
    public static FxGraph of(List<FxAttribute> attributes, FxStage... stages) {
        return new FxGraph(VERSION, attributes, List.of(stages));
    }

    /** The stage of {@code kind}, or null when the graph leaves it at its default stack. */
    public FxStage stage(FxStageKind kind) {
        for (FxStage s : stages) {
            if (s.kind() == kind) {
                return s;
            }
        }
        return null;
    }

    public FxAttribute attribute(String name) {
        for (FxAttribute a : attributes) {
            if (a.name().equals(name)) {
                return a;
            }
        }
        return null;
    }

    /**
     * The built-in stack of {@code kind}, i.e. today's fixed effect fields as modules:
     * PARTICLE_SPAWN = {@code initialize}; PARTICLE_UPDATE = {@code lifetime, forces, move, spin, trail};
     * EMITTER_UPDATE = {@code rate}; the others have none.
     */
    public static List<FxModule> defaultStack(FxStageKind kind) {
        return switch (kind) {
            case PARTICLE_SPAWN -> List.of(FxModule.builtin("initialize"));
            case PARTICLE_UPDATE -> List.of(FxModule.builtin("lifetime"), FxModule.builtin("forces"),
                    FxModule.builtin("move"), FxModule.builtin("spin"), FxModule.builtin("trail"));
            case EMITTER_UPDATE -> List.of(FxModule.builtin("rate"));
            default -> List.of();
        };
    }

    /** The modules {@code kind} runs: the graph's stack, or the default one. */
    public List<FxModule> stack(FxStageKind kind) {
        FxStage s = stage(kind);
        return s == null ? defaultStack(kind) : s.modules();
    }

    /**
     * The default stack of {@code kind} with {@code custom} inserted before the built-in named {@code before}
     * (appended when null or absent): the common way to add one module to an effect.
     */
    public static FxStage withModule(FxStageKind kind, FxModule custom, String before) {
        List<FxModule> stack = new ArrayList<>(defaultStack(kind));
        int at = stack.size();
        for (int i = 0; i < stack.size(); i++) {
            if (Objects.equals(stack.get(i).builtin(), before)) {
                at = i;
                break;
            }
        }
        stack.add(at, custom);
        return new FxStage(kind, stack);
    }
}
