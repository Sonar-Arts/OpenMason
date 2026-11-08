package com.openmason.engine.rendering.particles;

import com.openmason.engine.rendering.particles.graph.FxAttribute;
import com.openmason.engine.rendering.particles.graph.FxGraph;
import com.openmason.engine.rendering.particles.graph.FxModule;
import com.openmason.engine.rendering.particles.graph.FxNode;
import com.openmason.engine.rendering.particles.graph.FxOp;
import com.openmason.engine.rendering.particles.graph.FxStageKind;
import com.openmason.engine.rendering.particles.graph.FxType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Compiles an effect's {@link FxGraph} (or its default stacks) into {@link FxProgram}s for the CPU backend.
 *
 * <ol>
 *   <li><b>Lower</b> built-in modules from the effect's fields ({@code forces} = gravity + wind + drag, {@code move},
 *       {@code spin}, {@code trail}, {@code rate}) into ordinary operator nodes.</li>
 *   <li><b>Fuse</b> each stage's modules into one SSA list (one pass over the particles per stage).</li>
 *   <li><b>Check</b> types and attributes.</li>
 *   <li><b>Eliminate</b> dead nodes and dead custom attributes (written but never read anywhere).</li>
 *   <li><b>Fold</b> constant subgraphs at compile time and hoist per-frame uniform math (dt, wind and what depends
 *       only on them) out of the particle loop.</li>
 *   <li><b>Generate</b> code: vec3 ops split into float lanes, attribute reads/writes forwarded through registers
 *       (each column loaded and stored at most once per stage), registers reused after their last use.</li>
 * </ol>
 * Float expressions keep exactly the evaluation order the graph spells out (no algebraic rewrites), which is why the
 * built-in modules reproduce the old hand-written loop bit for bit.
 */
final class FxCompiler {

    /** What the compiler needs from an effect (its fields that built-in modules lower from). */
    record Input(String name, FxGraph graph, float gravityX, float gravityY, float gravityZ, float wind, float drag,
                 boolean collides, boolean trail, float rate) {

        static Input of(ParticleEffect e) {
            boolean trail = false;
            for (ParticleEffect.SubEmitter s : e.subEmitters()) {
                trail |= s.trigger() == ParticleEffect.Trigger.TRAIL;
            }
            return new Input(e.name(), e.graph(), e.gravityX(), e.gravityY(), e.gravityZ(), e.windInfluence(),
                    e.drag(), e.collides(), trail, e.rate());
        }
    }

    /** The compiled stages plus the custom attribute layout they use. */
    static final class Result {
        /** Null when PARTICLE_SPAWN is only the built-in {@code initialize}. */
        final FxProgram spawn;
        final FxProgram update;
        /** Null when EMITTER_UPDATE is only the built-in {@code rate}. */
        final FxProgram emitter;
        /** Live custom attribute components ("heat", "swirl.x", …): program column {@code BUILTIN_COLUMNS + i}. */
        final String[] customNames;
        final float[] customDefaults;

        Result(FxProgram spawn, FxProgram update, FxProgram emitter, String[] customNames, float[] customDefaults) {
            this.spawn = spawn;
            this.update = update;
            this.emitter = emitter;
            this.customNames = customNames;
            this.customDefaults = customDefaults;
        }
    }

    private static final FxGraph DEFAULT = FxGraph.of(List.of());

    private FxCompiler() {
    }

    static Result compile(Input in) {
        FxGraph graph = in.graph == null ? DEFAULT : in.graph;
        for (FxStageKind kind : new FxStageKind[]{FxStageKind.EMITTER_SPAWN, FxStageKind.EVENT, FxStageKind.RENDER}) {
            if (!graph.stack(kind).isEmpty()) {
                throw new IllegalArgumentException(in.name + ": " + kind + " modules are not supported by the CPU "
                        + "backend (events run as sub-emitters / FX handlers, render settings are effect fields)");
            }
        }
        Stage spawn = expand(in, graph, FxStageKind.PARTICLE_SPAWN);
        Stage update = expand(in, graph, FxStageKind.PARTICLE_UPDATE);
        Stage emitter = expand(in, graph, FxStageKind.EMITTER_UPDATE);
        for (Stage s : List.of(spawn, update, emitter)) {
            s.infer(graph);
        }

        // Dead-attribute elimination: a custom attribute lives only if some live node reads it (fixpoint).
        Set<String> liveCustom = new HashSet<>();
        while (true) {
            spawn.markLive(liveCustom);
            update.markLive(liveCustom);
            Set<String> read = new HashSet<>(liveCustom);
            spawn.collectReads(read);
            update.collectReads(read);
            if (read.equals(liveCustom)) {
                break;
            }
            liveCustom = read;
        }
        emitter.markLive(Set.of());

        List<String> customNames = new ArrayList<>();
        List<Float> customDefaults = new ArrayList<>();
        Map<String, int[]> customColumn = new HashMap<>();
        for (FxAttribute a : graph.attributes()) {
            if (!liveCustom.contains(a.name())) {
                continue;
            }
            int[] cols = new int[a.type().width];
            for (int c = 0; c < cols.length; c++) {
                cols[c] = FxVm.BUILTIN_COLUMNS + customNames.size() + c;
            }
            customColumn.put(a.name(), cols);
            for (int c = 0; c < a.type().width; c++) {
                customNames.add(a.type() == FxType.FLOAT ? a.name() : a.name() + "." + "xyz".charAt(c));
                customDefaults.add(a.defaults().get(c));
            }
        }
        float[] defaults = new float[customDefaults.size()];
        for (int i = 0; i < defaults.length; i++) {
            defaults[i] = customDefaults.get(i);
        }
        FxProgram spawnProgram = spawn.hasCustom ? new Gen(spawn, customColumn, in.rate).run() : null;
        FxProgram updateProgram = new Gen(update, customColumn, in.rate).run();
        FxProgram emitterProgram = emitter.hasCustom ? new Gen(emitter, customColumn, in.rate).run() : null;
        return new Result(spawnProgram, updateProgram, emitterProgram, customNames.toArray(new String[0]), defaults);
    }

    // ------------------------------------------------------------------------------------------------
    // Expansion: stack -> flat SSA node list (stage fusion), built-ins lowered
    // ------------------------------------------------------------------------------------------------

    private static Stage expand(Input in, FxGraph graph, FxStageKind kind) {
        List<FxModule> stack = graph.stack(kind);
        Stage stage = new Stage(kind, in.name);
        Set<String> builtinsSeen = new HashSet<>();
        for (int m = 0; m < stack.size(); m++) {
            FxModule module = stack.get(m);
            List<FxNode> nodes;
            if (module.isBuiltin()) {
                String b = module.builtin();
                if (!builtinsSeen.add(b)) {
                    throw new IllegalArgumentException(in.name + ": built-in '" + b + "' listed twice in " + kind);
                }
                nodes = lower(in, kind, b, m);
            } else {
                stage.hasCustom = true;
                nodes = module.nodes();
            }
            Map<String, Integer> local = new HashMap<>();
            for (FxNode n : nodes) {
                int[] inputs = new int[n.inputs().size()];
                for (int k = 0; k < inputs.length; k++) {
                    inputs[k] = local.get(n.inputs().get(k));
                }
                float[] values = new float[n.values().size()];
                for (int k = 0; k < values.length; k++) {
                    values[k] = n.values().get(k);
                }
                local.put(n.id(), stage.nodes.size());
                stage.nodes.add(new N(module.name() + "." + n.id(), n.op(), inputs, values, n.attribute()));
            }
        }
        if (kind == FxStageKind.PARTICLE_UPDATE && !builtinsSeen.contains("lifetime")) {
            throw new IllegalArgumentException(in.name + ": PARTICLE_UPDATE must start with the built-in 'lifetime'");
        }
        if (kind == FxStageKind.PARTICLE_SPAWN && !builtinsSeen.contains("initialize")) {
            throw new IllegalArgumentException(in.name + ": PARTICLE_SPAWN must start with the built-in 'initialize'");
        }
        return stage;
    }

    /** A built-in module as operator nodes (lifetime/initialize are markers: the system runs them natively). */
    private static List<FxNode> lower(Input in, FxStageKind kind, String builtin, int position) {
        boolean update = kind == FxStageKind.PARTICLE_UPDATE;
        switch (builtin) {
            case "lifetime", "initialize" -> {
                boolean right = builtin.equals("lifetime") ? update : kind == FxStageKind.PARTICLE_SPAWN;
                if (!right || position != 0) {
                    throw new IllegalArgumentException(in.name + ": built-in '" + builtin + "' must come first in "
                            + (builtin.equals("lifetime") ? "PARTICLE_UPDATE" : "PARTICLE_SPAWN"));
                }
                return List.of();
            }
            case "forces" -> {
                requireStage(in, builtin, update);
                // v = (v + (gravity + wind × influence) × dt) × exp(−drag × dt): the old loop's exact expression.
                return List.of(
                        FxNode.constant("g", in.gravityX, in.gravityY, in.gravityZ),
                        FxNode.op("w", FxOp.WIND),
                        FxNode.constant("wi", in.wind),
                        FxNode.op("ww", FxOp.MUL, "w", "wi"),
                        FxNode.op("a", FxOp.ADD, "g", "ww"),
                        FxNode.op("dt", FxOp.DT),
                        FxNode.op("ad", FxOp.MUL, "a", "dt"),
                        FxNode.read("v", "velocity"),
                        FxNode.op("v2", FxOp.ADD, "v", "ad"),
                        FxNode.constant("nd", -in.drag),
                        FxNode.op("x", FxOp.MUL, "nd", "dt"),
                        FxNode.op("dm", FxOp.EXP, "x"),
                        FxNode.op("v3", FxOp.MUL, "v2", "dm"),
                        FxNode.write("out", "velocity", "v3"));
            }
            case "move" -> {
                requireStage(in, builtin, update);
                if (in.collides) {
                    return List.of(FxNode.read("v", "velocity"), new FxNode("c", FxOp.COLLIDE, List.of("v"), List.of(), null));
                }
                return List.of(
                        FxNode.read("p", "position"),
                        FxNode.read("v", "velocity"),
                        FxNode.op("dt", FxOp.DT),
                        FxNode.op("d", FxOp.MUL, "v", "dt"),
                        FxNode.op("p2", FxOp.ADD, "p", "d"),
                        FxNode.write("out", "position", "p2"));
            }
            case "spin" -> {
                requireStage(in, builtin, update);
                return List.of(
                        FxNode.read("r", "rotation"),
                        FxNode.read("s", "spin"),
                        FxNode.op("dt", FxOp.DT),
                        FxNode.op("sd", FxOp.MUL, "s", "dt"),
                        FxNode.op("r2", FxOp.ADD, "r", "sd"),
                        FxNode.write("out", "rotation", "r2"));
            }
            case "trail" -> {
                requireStage(in, builtin, update);
                return in.trail ? List.of(new FxNode("t", FxOp.TRAIL, List.of(), List.of(), null)) : List.of();
            }
            case "rate" -> {
                requireStage(in, builtin, kind == FxStageKind.EMITTER_UPDATE);
                return List.of(FxNode.constant("r", in.rate), FxNode.write("out", "spawnRate", "r"));
            }
            default -> throw new IllegalArgumentException(in.name + ": unknown built-in module '" + builtin + "'");
        }
    }

    private static void requireStage(Input in, String builtin, boolean ok) {
        if (!ok) {
            throw new IllegalArgumentException(in.name + ": built-in '" + builtin + "' is in the wrong stage");
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Nodes and stages
    // ------------------------------------------------------------------------------------------------

    /** A node of a fused stage. */
    private static final class N {
        final String id;
        final FxOp op;
        final int[] in;
        final float[] values;
        final String attr;
        FxType type;
        boolean uniform;
        boolean live;
        float[] constant;   // folded value, or null
        int lastUse = -1;   // index of the last live consumer
        int[] regs;         // lane registers per component (varying), or uniform registers (uniform)

        N(String id, FxOp op, int[] in, float[] values, String attr) {
            this.id = id;
            this.op = op;
            this.in = in;
            this.values = values;
            this.attr = attr;
        }
    }

    /** Built-in attribute: type, columns, writable. */
    private record Attr(FxType type, int[] columns, boolean writable) {
    }

    private static final Map<String, Attr> PARTICLE_ATTRS = Map.of(
            "position", new Attr(FxType.VEC3, new int[]{FxVm.COL_PX, FxVm.COL_PY, FxVm.COL_PZ}, true),
            "velocity", new Attr(FxType.VEC3, new int[]{FxVm.COL_VX, FxVm.COL_VY, FxVm.COL_VZ}, true),
            "age", new Attr(FxType.FLOAT, new int[]{FxVm.COL_AGE}, true),
            "lifetime", new Attr(FxType.FLOAT, new int[0], false),
            "normalizedAge", new Attr(FxType.FLOAT, new int[0], false),
            "rotation", new Attr(FxType.FLOAT, new int[]{FxVm.COL_ROT}, true),
            "spin", new Attr(FxType.FLOAT, new int[]{FxVm.COL_SPIN}, true),
            "startSize", new Attr(FxType.FLOAT, new int[]{FxVm.COL_SIZE0}, true),
            "endSize", new Attr(FxType.FLOAT, new int[]{FxVm.COL_SIZE1}, true));

    private static final class Stage {
        final FxStageKind kind;
        final String effect;
        final List<N> nodes = new ArrayList<>();
        boolean hasCustom;

        Stage(FxStageKind kind, String effect) {
            this.kind = kind;
            this.effect = effect;
        }

        boolean emitterStage() {
            return kind == FxStageKind.EMITTER_UPDATE;
        }

        IllegalArgumentException error(N n, String message) {
            return new IllegalArgumentException(effect + ": " + kind + " node '" + n.id + "': " + message);
        }

        /** Types, attribute checks and uniformity. */
        void infer(FxGraph graph) {
            for (N n : nodes) {
                FxType[] t = new FxType[n.in.length];
                boolean allUniform = true;
                for (int k = 0; k < n.in.length; k++) {
                    t[k] = nodes.get(n.in[k]).type;
                    allUniform &= nodes.get(n.in[k]).uniform;
                }
                n.type = switch (n.op) {
                    case CONST -> n.values.length == 3 ? FxType.VEC3 : FxType.FLOAT;
                    case READ -> attrType(graph, n, false);
                    case WRITE -> {
                        FxType at = attrType(graph, n, true);
                        if (t[0] != at) {
                            throw error(n, "writes a " + t[0] + " to " + at + " attribute '" + n.attr + "'");
                        }
                        yield null;
                    }
                    case DT, RANDOM -> FxType.FLOAT;
                    case WIND -> FxType.VEC3;
                    case ADD, SUB, MUL, DIV, MIN, MAX -> broadcast(n, t[0], t[1]);
                    case NEG, ABS, SQRT, SIN, COS, EXP, FLOOR -> t[0];
                    case LERP, CLAMP -> broadcast(n, broadcast(n, t[0], t[1]), t[2]);
                    case VEC3 -> {
                        expect(n, t, FxType.FLOAT, FxType.FLOAT, FxType.FLOAT);
                        yield FxType.VEC3;
                    }
                    case X, Y, Z, LENGTH -> {
                        expect(n, t, FxType.VEC3);
                        yield FxType.FLOAT;
                    }
                    case NORMALIZE, CURL -> {
                        expect(n, t, FxType.VEC3);
                        yield FxType.VEC3;
                    }
                    case NOISE -> {
                        expect(n, t, FxType.VEC3);
                        yield FxType.FLOAT;
                    }
                    case DOT -> {
                        expect(n, t, FxType.VEC3, FxType.VEC3);
                        yield FxType.FLOAT;
                    }
                    case CROSS -> {
                        expect(n, t, FxType.VEC3, FxType.VEC3);
                        yield FxType.VEC3;
                    }
                    case CURVE -> {
                        expect(n, t, FxType.FLOAT);
                        yield FxType.FLOAT;
                    }
                    case COLLIDE -> {
                        expect(n, t, FxType.VEC3);
                        yield null;
                    }
                    case TRAIL -> null;
                };
                n.uniform = switch (n.op) {
                    case CONST, DT, WIND -> true;
                    case RANDOM -> emitterStage();
                    case READ -> emitterStage();
                    case WRITE, COLLIDE, TRAIL -> false;
                    default -> allUniform;
                };
                if (emitterStage() && (n.op == FxOp.COLLIDE || n.op == FxOp.TRAIL)) {
                    throw error(n, n.op.id() + " only runs on particles");
                }
            }
        }

        private FxType attrType(FxGraph graph, N n, boolean write) {
            if (emitterStage()) {
                switch (n.attr) {
                    case "spawnRate" -> {
                        return FxType.FLOAT;
                    }
                    case "emitterAge" -> {
                        if (write) throw error(n, "emitterAge is read-only");
                        return FxType.FLOAT;
                    }
                    default -> throw error(n, "EMITTER_UPDATE has spawnRate and emitterAge, not '" + n.attr + "'");
                }
            }
            Attr builtin = PARTICLE_ATTRS.get(n.attr);
            if (builtin != null) {
                if (write && !builtin.writable) {
                    throw error(n, "'" + n.attr + "' is read-only");
                }
                return builtin.type;
            }
            FxAttribute custom = graph.attribute(n.attr);
            if (custom == null) {
                throw error(n, "unknown attribute '" + n.attr + "' (declare custom attributes on the graph)");
            }
            return custom.type();
        }

        private FxType broadcast(N n, FxType a, FxType b) {
            if (a == b || b == FxType.FLOAT) {
                return a;
            }
            if (a == FxType.FLOAT) {
                return b;
            }
            throw error(n, "mismatched operand types " + a + " and " + b);
        }

        private void expect(N n, FxType[] got, FxType... want) {
            if (!Arrays.equals(got, want)) {
                throw error(n, n.op.id() + " takes " + Arrays.toString(want) + ", got " + Arrays.toString(got));
            }
        }

        /** Marks nodes reaching a sink live; writes to custom attributes count only when the attribute is live. */
        void markLive(Set<String> liveCustom) {
            for (N n : nodes) {
                n.live = false;
                n.lastUse = -1;
            }
            for (int i = nodes.size() - 1; i >= 0; i--) {
                N n = nodes.get(i);
                boolean root = switch (n.op) {
                    case WRITE -> emitterStage() || PARTICLE_ATTRS.containsKey(n.attr) || liveCustom.contains(n.attr);
                    case COLLIDE, TRAIL -> true;
                    default -> false;
                };
                if (root) {
                    n.live = true;
                }
                if (n.live) {
                    for (int src : n.in) {
                        N s = nodes.get(src);
                        s.live = true;
                        s.lastUse = Math.max(s.lastUse, i);
                    }
                }
            }
        }

        void collectReads(Set<String> into) {
            for (N n : nodes) {
                if (n.live && n.op == FxOp.READ && !PARTICLE_ATTRS.containsKey(n.attr)) {
                    into.add(n.attr);
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Code generation
    // ------------------------------------------------------------------------------------------------

    private static final class Gen {
        final Stage stage;
        final Map<String, int[]> customColumn;
        final float effectRate;
        final IntList ucode = new IntList(), lcode = new IntList();
        final List<Float> constants = new ArrayList<>();
        final Map<Integer, Integer> constIndex = new LinkedHashMap<>();
        final Map<Integer, Integer> constUreg = new LinkedHashMap<>();
        final List<float[]> curves = new ArrayList<>();
        int uregs;

        // lane register allocation
        int[] holders = new int[16];
        int lregs;
        final IntList free = new IntList();
        final Map<Integer, Integer> bcast = new HashMap<>();
        // attribute column cache (forwarding): column -> lane register, and the columns not yet stored
        final Map<Integer, Integer> cache = new HashMap<>();
        final Set<Integer> dirty = new TreeSet<>();
        // shared per-run sources (common subexpressions across modules)
        int dtReg = -1, ageReg = -1;
        int[] windRegs;
        // emitter stage: current spawnRate uniform register (-1 = not yet read or written)
        int rateReg = -1;
        boolean rateWritten;

        Gen(Stage stage, Map<String, int[]> customColumn, float effectRate) {
            this.stage = stage;
            this.customColumn = customColumn;
            this.effectRate = effectRate;
        }

        FxProgram run() {
            List<N> nodes = stage.nodes;
            for (int i = 0; i < nodes.size(); i++) {
                N n = nodes.get(i);
                if (!n.live) {
                    continue;
                }
                fold(n);
                if (n.uniform) {
                    emitUniform(n);
                } else {
                    emitLane(n);
                }
                for (int src : n.in) {
                    N s = nodes.get(src);
                    if (s.lastUse == i && !s.uniform && s.regs != null) {
                        for (int r : s.regs) {
                            release(r);
                        }
                    }
                }
            }
            for (int col : dirty) {
                lcode.add(FxVm.STORE, col, cache.get(col));
            }
            if (stage.emitterStage() && rateWritten) {
                ucode.add(FxVm.OUTRATE, rateReg);
            }
            float[] c = new float[constants.size()];
            for (int k = 0; k < c.length; k++) {
                c[k] = constants.get(k);
            }
            return new FxProgram(stage.kind, ucode.toArray(), lcode.toArray(), c, curves.toArray(new float[0][]),
                    Math.max(1, uregs), lregs);
        }

        // ---- constant folding ----

        private void fold(N n) {
            List<N> nodes = stage.nodes;
            switch (n.op) {
                case CONST -> n.constant = n.values.clone();
                case READ, WRITE, DT, WIND, RANDOM, COLLIDE, TRAIL -> {
                }
                default -> {
                    float[][] args = new float[n.in.length][];
                    for (int k = 0; k < args.length; k++) {
                        args[k] = nodes.get(n.in[k]).constant;
                        if (args[k] == null) {
                            return;
                        }
                    }
                    n.constant = evaluate(n, args);
                }
            }
        }

        /** Evaluates a pure op on constants with exactly the instruction sequence {@link #emitMath} generates. */
        private float[] evaluate(N n, float[][] a) {
            int w = n.type.width;
            float[] out = new float[w];
            switch (n.op) {
                case ADD, SUB, MUL, DIV, MIN, MAX -> {
                    int op = binaryOp(n.op);
                    for (int c = 0; c < w; c++) out[c] = FxVm.binary(op, comp(a[0], c), comp(a[1], c));
                }
                case NEG, ABS, SQRT, SIN, COS, EXP, FLOOR -> {
                    int op = unaryOp(n.op);
                    for (int c = 0; c < w; c++) out[c] = FxVm.unary(op, a[0][c]);
                }
                case LERP -> {
                    for (int c = 0; c < w; c++) {
                        float x = comp(a[0], c), y = comp(a[1], c);
                        out[c] = x + (y - x) * comp(a[2], c);
                    }
                }
                case CLAMP -> {
                    for (int c = 0; c < w; c++) out[c] = Math.min(Math.max(comp(a[0], c), comp(a[1], c)), comp(a[2], c));
                }
                case VEC3 -> {
                    out[0] = a[0][0];
                    out[1] = a[1][0];
                    out[2] = a[2][0];
                }
                case X -> out[0] = a[0][0];
                case Y -> out[0] = a[0][1];
                case Z -> out[0] = a[0][2];
                case DOT -> out[0] = dot(a[0], a[1]);
                case LENGTH -> out[0] = (float) Math.sqrt(dot(a[0], a[0]));
                case NORMALIZE -> {
                    float inv = FxVm.unary(FxVm.SAFEINV, (float) Math.sqrt(dot(a[0], a[0])));
                    for (int c = 0; c < 3; c++) out[c] = a[0][c] * inv;
                }
                case CROSS -> {
                    out[0] = a[0][1] * a[1][2] - a[0][2] * a[1][1];
                    out[1] = a[0][2] * a[1][0] - a[0][0] * a[1][2];
                    out[2] = a[0][0] * a[1][1] - a[0][1] * a[1][0];
                }
                case CURVE -> out[0] = FxVm.curve(n.values, a[0][0]);
                case NOISE -> out[0] = FxNoise.noise(a[0][0], a[0][1], a[0][2]);
                case CURL -> FxNoise.curl(a[0][0], a[0][1], a[0][2], out, new float[9]);
                default -> throw new IllegalStateException("cannot fold " + n.op);
            }
            return out;
        }

        private static float dot(float[] a, float[] b) {
            return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
        }

        private static float comp(float[] v, int c) {
            return v.length == 1 ? v[0] : v[c];
        }

        // ---- uniform code (once per run) ----

        private int constReg(float v) {
            int bits = Float.floatToRawIntBits(v);
            Integer r = constUreg.get(bits);
            if (r == null) {
                Integer k = constIndex.get(bits);
                if (k == null) {
                    k = constants.size();
                    constants.add(v);
                    constIndex.put(bits, k);
                }
                r = uregs++;
                ucode.add(FxVm.CONST, r, k);
                constUreg.put(bits, r);
            }
            return r;
        }

        private void emitUniform(N n) {
            List<N> nodes = stage.nodes;
            if (n.constant != null) {
                n.regs = new int[n.constant.length];
                for (int c = 0; c < n.regs.length; c++) {
                    n.regs[c] = constReg(n.constant[c]);
                }
                return;
            }
            switch (n.op) {
                case DT -> {
                    if (dtReg < 0) {
                        dtReg = single(FxVm.DT);
                    }
                    n.regs = new int[]{dtReg};
                }
                case WIND -> {
                    if (windRegs == null) {
                        windRegs = new int[]{single(FxVm.WINDX), single(FxVm.WINDY), single(FxVm.WINDZ)};
                    }
                    n.regs = windRegs;
                }
                case RANDOM -> n.regs = new int[]{single(FxVm.RANDOM)};
                case READ -> {
                    if (n.attr.equals("emitterAge")) {
                        if (ageReg < 0) {
                            ageReg = single(FxVm.EAGE);
                        }
                        n.regs = new int[]{ageReg};
                    } else {
                        if (rateReg < 0) {
                            rateReg = constReg(effectRate);
                        }
                        n.regs = new int[]{rateReg};
                    }
                }
                default -> n.regs = emitMath(n, ucode, true, nodes);
            }
        }

        private int single(int op) {
            int r = uregs++;
            ucode.add(op, r);
            return r;
        }

        // ---- lane code (per chunk) ----

        private void emitLane(N n) {
            List<N> nodes = stage.nodes;
            if (stage.emitterStage()) {
                if (n.op == FxOp.WRITE) {
                    rateReg = nodes.get(n.in[0]).regs[0];
                    rateWritten = true;
                    return;
                }
                throw new IllegalStateException("emitter stage node is not uniform: " + n.id);
            }
            switch (n.op) {
                case RANDOM -> {
                    int r = alloc();
                    lcode.add(FxVm.RANDOM, r);
                    n.regs = new int[]{r};
                }
                case READ -> n.regs = read(n.attr);
                case WRITE -> write(n.attr, nodes.get(n.in[0]));
                case COLLIDE -> {
                    N v = nodes.get(n.in[0]);
                    int vx = lane(v, 0), vy = lane(v, 1), vz = lane(v, 2);
                    flush(FxVm.COL_PX, FxVm.COL_PY, FxVm.COL_PZ, FxVm.COL_AGE, FxVm.COL_SIZE0, FxVm.COL_SIZE1);
                    lcode.add(FxVm.COLLIDE, vx, vy, vz);
                    // The collider wrote position and velocity: forget cached (and pending) values of both.
                    invalidate(FxVm.COL_PX, FxVm.COL_PY, FxVm.COL_PZ, FxVm.COL_VX, FxVm.COL_VY, FxVm.COL_VZ);
                }
                case TRAIL -> {
                    flush(FxVm.COL_PX, FxVm.COL_PY, FxVm.COL_PZ, FxVm.COL_VX, FxVm.COL_VY, FxVm.COL_VZ);
                    lcode.add(FxVm.TRAIL);
                }
                default -> n.regs = emitMath(n, lcode, false, nodes);
            }
        }

        private int[] columns(String attr) {
            Attr builtin = PARTICLE_ATTRS.get(attr);
            if (builtin != null) {
                return builtin.columns;
            }
            return customColumn.get(attr);
        }

        private int[] read(String attr) {
            switch (attr) {
                case "normalizedAge" -> {
                    int a = column(FxVm.COL_AGE), il = column(FxVm.COL_INVLIFE);
                    int r = alloc();
                    lcode.add(FxVm.MUL, r, a, il);
                    return new int[]{r};
                }
                case "lifetime" -> {
                    int one = bcast(constReg(1f)), il = column(FxVm.COL_INVLIFE);
                    int r = alloc();
                    lcode.add(FxVm.DIV, r, one, il);
                    return new int[]{r};
                }
                default -> {
                    int[] cols = columns(attr);
                    int[] regs = new int[cols.length];
                    for (int c = 0; c < cols.length; c++) {
                        regs[c] = column(cols[c]);
                        hold(regs[c]);
                    }
                    return regs;
                }
            }
        }

        /** The register holding {@code col} for this chunk (loaded once, then forwarded). */
        private int column(int col) {
            Integer r = cache.get(col);
            if (r == null) {
                r = alloc();
                lcode.add(FxVm.LOAD, r, col);
                cache.put(col, r);
            }
            return r;
        }

        private void write(String attr, N value) {
            int[] cols = columns(attr);
            for (int c = 0; c < cols.length; c++) {
                int r = lane(value, c);
                hold(r);
                Integer old = cache.put(cols[c], r);
                if (old != null) {
                    release(old);
                }
                dirty.add(cols[c]);
            }
        }

        private void flush(int... cols) {
            for (int col : cols) {
                if (dirty.remove(col)) {
                    lcode.add(FxVm.STORE, col, cache.get(col));
                }
            }
        }

        private void invalidate(int... cols) {
            for (int col : cols) {
                dirty.remove(col);
                Integer r = cache.remove(col);
                if (r != null) {
                    release(r);
                }
            }
        }

        /** Lane register for component {@code c} of {@code n} (uniform values are broadcast once). */
        private int lane(N n, int c) {
            int idx = n.type.width == 1 ? 0 : c;
            return n.uniform ? bcast(n.regs[idx]) : n.regs[idx];
        }

        private int bcast(int ureg) {
            Integer r = bcast.get(ureg);
            if (r == null) {
                r = alloc(); // held forever: broadcast registers are never rewritten
                lcode.add(FxVm.BCAST, r, ureg);
                bcast.put(ureg, r);
            }
            return r;
        }

        /** Arithmetic, split into float lanes; returns the result registers. */
        private int[] emitMath(N n, IntList code, boolean uniform, List<N> nodes) {
            N a = n.in.length > 0 ? nodes.get(n.in[0]) : null;
            N b = n.in.length > 1 ? nodes.get(n.in[1]) : null;
            N t = n.in.length > 2 ? nodes.get(n.in[2]) : null;
            int w = n.type.width;
            switch (n.op) {
                case ADD, SUB, MUL, DIV, MIN, MAX -> {
                    int op = binaryOp(n.op);
                    int[] out = new int[w];
                    for (int c = 0; c < w; c++) {
                        out[c] = newReg(uniform);
                        code.add(op, out[c], src(a, c, uniform), src(b, c, uniform));
                    }
                    return out;
                }
                case NEG, ABS, SQRT, SIN, COS, EXP, FLOOR -> {
                    int op = unaryOp(n.op);
                    int[] out = new int[w];
                    for (int c = 0; c < w; c++) {
                        out[c] = newReg(uniform);
                        code.add(op, out[c], src(a, c, uniform));
                    }
                    return out;
                }
                case LERP, CLAMP -> {
                    int op = n.op == FxOp.LERP ? FxVm.LERP : FxVm.CLAMP;
                    int[] out = new int[w];
                    for (int c = 0; c < w; c++) {
                        out[c] = newReg(uniform);
                        code.add(op, out[c], src(a, c, uniform), src(b, c, uniform), src(t, c, uniform));
                    }
                    return out;
                }
                case VEC3 -> {
                    int[] out = {src(a, 0, uniform), src(b, 0, uniform), src(t, 0, uniform)};
                    holdAll(out, uniform);
                    return out;
                }
                case X, Y, Z -> {
                    int[] out = {src(a, n.op.ordinal() - FxOp.X.ordinal(), uniform)};
                    holdAll(out, uniform);
                    return out;
                }
                case DOT -> {
                    return new int[]{dot(code, uniform, a, b)};
                }
                case LENGTH -> {
                    int d = dot(code, uniform, a, a);
                    int r = newReg(uniform);
                    code.add(FxVm.SQRT, r, d);
                    drop(d, uniform);
                    return new int[]{r};
                }
                case NORMALIZE -> {
                    int d = dot(code, uniform, a, a);
                    int len = newReg(uniform);
                    code.add(FxVm.SQRT, len, d);
                    int inv = newReg(uniform);
                    code.add(FxVm.SAFEINV, inv, len);
                    int[] out = new int[3];
                    for (int c = 0; c < 3; c++) {
                        out[c] = newReg(uniform);
                        code.add(FxVm.MUL, out[c], src(a, c, uniform), inv);
                    }
                    drop(d, uniform);
                    drop(len, uniform);
                    drop(inv, uniform);
                    return out;
                }
                case CROSS -> {
                    int[][] terms = {{1, 2, 2, 1}, {2, 0, 0, 2}, {0, 1, 1, 0}};
                    int[] out = new int[3];
                    for (int c = 0; c < 3; c++) {
                        int[] k = terms[c];
                        int p = newReg(uniform), q = newReg(uniform);
                        code.add(FxVm.MUL, p, src(a, k[0], uniform), src(b, k[1], uniform));
                        code.add(FxVm.MUL, q, src(a, k[2], uniform), src(b, k[3], uniform));
                        out[c] = newReg(uniform);
                        code.add(FxVm.SUB, out[c], p, q);
                        drop(p, uniform);
                        drop(q, uniform);
                    }
                    return out;
                }
                case CURVE -> {
                    int k = curves.size();
                    curves.add(n.values.clone());
                    int r = newReg(uniform);
                    code.add(FxVm.CURVE, r, src(a, 0, uniform), k);
                    return new int[]{r};
                }
                case NOISE -> {
                    int r = newReg(uniform);
                    code.add(FxVm.NOISE, r, src(a, 0, uniform), src(a, 1, uniform), src(a, 2, uniform));
                    return new int[]{r};
                }
                case CURL -> {
                    int x = src(a, 0, uniform), y = src(a, 1, uniform), z = src(a, 2, uniform);
                    int[] out = {newReg(uniform), newReg(uniform), newReg(uniform)};
                    code.add(FxVm.CURL, out[0], out[1], out[2], x, y, z);
                    return out;
                }
                default -> throw new IllegalStateException("no math lowering for " + n.op);
            }
        }

        /** (a·b) as ((ax·bx + ay·by) + az·bz), the order the folder uses. */
        private int dot(IntList code, boolean uniform, N a, N b) {
            int x = newReg(uniform), y = newReg(uniform), s = newReg(uniform);
            code.add(FxVm.MUL, x, src(a, 0, uniform), src(b, 0, uniform));
            code.add(FxVm.MUL, y, src(a, 1, uniform), src(b, 1, uniform));
            code.add(FxVm.ADD, s, x, y);
            drop(x, uniform);
            drop(y, uniform);
            int z = newReg(uniform), d = newReg(uniform);
            code.add(FxVm.MUL, z, src(a, 2, uniform), src(b, 2, uniform));
            code.add(FxVm.ADD, d, s, z);
            drop(s, uniform);
            drop(z, uniform);
            return d;
        }

        private int src(N n, int c, boolean uniform) {
            if (uniform) {
                return n.regs[n.type.width == 1 ? 0 : c];
            }
            return lane(n, c);
        }

        private int newReg(boolean uniform) {
            return uniform ? uregs++ : alloc();
        }

        private void drop(int r, boolean uniform) {
            if (!uniform) {
                release(r);
            }
        }

        private void holdAll(int[] regs, boolean uniform) {
            if (!uniform) {
                for (int r : regs) {
                    hold(r);
                }
            }
        }

        // ---- lane register allocation: a register returns to the free list when nothing holds it ----

        private int alloc() {
            int r;
            if (free.size > 0) {
                r = free.removeLast();
            } else {
                r = lregs++;
                if (r >= holders.length) {
                    holders = Arrays.copyOf(holders, holders.length * 2);
                }
            }
            holders[r] = 1;
            return r;
        }

        private void hold(int r) {
            holders[r]++;
        }

        private void release(int r) {
            if (bcast.containsValue(r)) {
                return;
            }
            if (--holders[r] == 0) {
                free.add(r);
            }
        }
    }

    private static int binaryOp(FxOp op) {
        return switch (op) {
            case ADD -> FxVm.ADD;
            case SUB -> FxVm.SUB;
            case MUL -> FxVm.MUL;
            case DIV -> FxVm.DIV;
            case MIN -> FxVm.MIN;
            case MAX -> FxVm.MAX;
            default -> throw new IllegalArgumentException(op.id());
        };
    }

    private static int unaryOp(FxOp op) {
        return switch (op) {
            case NEG -> FxVm.NEG;
            case ABS -> FxVm.ABS;
            case SQRT -> FxVm.SQRT;
            case SIN -> FxVm.SIN;
            case COS -> FxVm.COS;
            case EXP -> FxVm.EXP;
            case FLOOR -> FxVm.FLOOR;
            default -> throw new IllegalArgumentException(op.id());
        };
    }

    /** Growable int list for code emission. */
    private static final class IntList {
        int[] a = new int[64];
        int size;

        void add(int... values) {
            for (int v : values) {
                if (size == a.length) {
                    a = Arrays.copyOf(a, size * 2);
                }
                a[size++] = v;
            }
        }

        int removeLast() {
            return a[--size];
        }

        int[] toArray() {
            return Arrays.copyOf(a, size);
        }
    }
}
