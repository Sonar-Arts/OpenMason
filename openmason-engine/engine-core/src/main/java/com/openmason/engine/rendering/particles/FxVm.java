package com.openmason.engine.rendering.particles;

/**
 * The CPU backend's interpreter (Niagara VectorVM style): an {@link FxProgram} runs <b>one instruction across a
 * whole chunk of particles</b> at a time over float registers, so dispatch costs once per instruction per chunk,
 * not per particle, and every arithmetic instruction is a plain {@code float[]} loop the JIT can vectorise (and
 * that ports 1:1 to C++/SIMD for Cenda). Particle columns are gathered into registers by index and scattered back.
 *
 * <p>One VM per {@link ParticleSystem}; its register files grow to the largest program the system compiled (at
 * compile time, never per frame). Simulation thread only.
 */
final class FxVm {

    /** Particles per chunk: registers stay small enough for the L1/L2 caches. */
    static final int CHUNK = 256;

    // ---- opcodes ----
    static final int CONST = 0, DT = 1, WINDX = 2, WINDY = 3, WINDZ = 4, EAGE = 5, RANDOM = 6, LOAD = 7, STORE = 8,
            BCAST = 9, ADD = 10, SUB = 11, MUL = 12, DIV = 13, MIN = 14, MAX = 15, NEG = 16, ABS = 17, SQRT = 18,
            SIN = 19, COS = 20, EXP = 21, FLOOR = 22, SAFEINV = 23, LERP = 24, CLAMP = 25, CURVE = 26, NOISE = 27,
            CURL = 28, COLLIDE = 29, TRAIL = 30, OUTRATE = 31;

    /** Instruction length (opcode + operands) per opcode. */
    static final int[] LENGTH = {3, 2, 2, 2, 2, 2, 2, 3, 3, 3, 4, 4, 4, 4, 4, 4, 3, 3, 3, 3, 3, 3, 3, 3, 5, 5, 4, 5,
            7, 4, 1, 2};
    static final String[] NAMES = {"const", "dt", "windx", "windy", "windz", "eage", "random", "load", "store",
            "bcast", "add", "sub", "mul", "div", "min", "max", "neg", "abs", "sqrt", "sin", "cos", "exp", "floor",
            "safeinv", "lerp", "clamp", "curve", "noise", "curl", "collide", "trail", "outrate"};

    // ---- column ids (LOAD/STORE operands); custom attribute components follow BUILTIN_COLUMNS ----
    static final int COL_PX = 0, COL_PY = 1, COL_PZ = 2, COL_VX = 3, COL_VY = 4, COL_VZ = 5, COL_AGE = 6,
            COL_INVLIFE = 7, COL_ROT = 8, COL_SPIN = 9, COL_SIZE0 = 10, COL_SIZE1 = 11, BUILTIN_COLUMNS = 12;

    private final ParticleSystem system;
    private final ParticleRandom random;
    private float[][] lanes = new float[0][];
    private float[][] uniforms = new float[0][];
    private final int[] chunk = new int[CHUNK];
    /** The current chunk's particles are {@code chunk[0] .. chunk[0]+n−1}: loads and stores are block copies. */
    private boolean contiguous;
    private final float[] curl = new float[3];
    private final float[] curlScratch = new float[9];

    // ---- the current run ----
    private CompiledEffect effect;
    private float dt, windX, windY, windZ, emitterAge, rateOut;

    FxVm(ParticleSystem system, ParticleRandom random) {
        this.system = system;
        this.random = random;
    }

    /** Grows the register files for {@code p} (compile time only). */
    void reserve(FxProgram p) {
        if (p == null) {
            return;
        }
        if (lanes.length < p.laneRegisters) {
            float[][] grown = new float[p.laneRegisters][];
            for (int i = 0; i < grown.length; i++) {
                grown[i] = i < lanes.length ? lanes[i] : new float[CHUNK];
            }
            lanes = grown;
        }
        if (uniforms.length < p.uniformRegisters) {
            float[][] grown = new float[p.uniformRegisters][];
            for (int i = 0; i < grown.length; i++) {
                grown[i] = i < uniforms.length ? uniforms[i] : new float[1];
            }
            uniforms = grown;
        }
    }

    /**
     * Runs {@code p} over {@code count} particles of {@code e}: indices {@code idx[from..from+count)}, or the
     * contiguous range {@code [from, from+count)} when {@code idx} is null.
     */
    void runParticles(FxProgram p, CompiledEffect e, int[] idx, int from, int count, float stepDt,
                      float wx, float wy, float wz) {
        effect = e;
        dt = stepDt;
        windX = wx;
        windY = wy;
        windZ = wz;
        exec(p, p.uniformCode, uniforms, 1);
        for (int base = 0; base < count; base += CHUNK) {
            int n = Math.min(CHUNK, count - base);
            int[] ix = chunk;
            if (idx == null) {
                for (int k = 0; k < n; k++) {
                    ix[k] = from + base + k;
                }
                contiguous = true;
            } else {
                System.arraycopy(idx, from + base, ix, 0, n);
                // Indices of one effect's group ascend without repeats, so a span of n means no gaps.
                contiguous = ix[n - 1] - ix[0] == n - 1;
            }
            exec(p, p.laneCode, lanes, n);
        }
    }

    /** Runs an EMITTER_UPDATE program for one emitter; returns its spawn rate (particles/s). */
    float runEmitter(FxProgram p, CompiledEffect e, float age, float stepDt, float wx, float wy, float wz) {
        effect = e;
        dt = stepDt;
        emitterAge = age;
        windX = wx;
        windY = wy;
        windZ = wz;
        rateOut = e.rate;
        exec(p, p.uniformCode, uniforms, 1);
        return rateOut;
    }

    private float[] column(int c) {
        ParticleSystem s = system;
        return switch (c) {
            case COL_PX -> s.px;
            case COL_PY -> s.py;
            case COL_PZ -> s.pz;
            case COL_VX -> s.vx;
            case COL_VY -> s.vy;
            case COL_VZ -> s.vz;
            case COL_AGE -> s.age;
            case COL_INVLIFE -> s.invLife;
            case COL_ROT -> s.rot;
            case COL_SPIN -> s.spin;
            case COL_SIZE0 -> s.size0;
            case COL_SIZE1 -> s.size1;
            default -> s.customColumn(effect.customColumns[c - BUILTIN_COLUMNS]);
        };
    }

    private void exec(FxProgram p, int[] code, float[][] r, int n) {
        final int[] ix = chunk;
        for (int pc = 0; pc < code.length; ) {
            int op = code[pc];
            switch (op) {
                case CONST -> fill(r[code[pc + 1]], n, p.constants[code[pc + 2]]);
                case DT -> fill(r[code[pc + 1]], n, dt);
                case WINDX -> fill(r[code[pc + 1]], n, windX);
                case WINDY -> fill(r[code[pc + 1]], n, windY);
                case WINDZ -> fill(r[code[pc + 1]], n, windZ);
                case EAGE -> fill(r[code[pc + 1]], n, emitterAge);
                case RANDOM -> {
                    float[] d = r[code[pc + 1]];
                    for (int k = 0; k < n; k++) d[k] = random.nextFloat();
                }
                case LOAD -> {
                    float[] d = r[code[pc + 1]], col = column(code[pc + 2]);
                    if (contiguous) {
                        System.arraycopy(col, ix[0], d, 0, n);
                    } else {
                        for (int k = 0; k < n; k++) d[k] = col[ix[k]];
                    }
                }
                case STORE -> {
                    float[] col = column(code[pc + 1]), a = r[code[pc + 2]];
                    if (contiguous) {
                        System.arraycopy(a, 0, col, ix[0], n);
                    } else {
                        for (int k = 0; k < n; k++) col[ix[k]] = a[k];
                    }
                }
                case BCAST -> fill(r[code[pc + 1]], n, uniforms[code[pc + 2]][0]);
                case ADD -> {
                    float[] d = r[code[pc + 1]], a = r[code[pc + 2]], b = r[code[pc + 3]];
                    for (int k = 0; k < n; k++) d[k] = a[k] + b[k];
                }
                case SUB -> {
                    float[] d = r[code[pc + 1]], a = r[code[pc + 2]], b = r[code[pc + 3]];
                    for (int k = 0; k < n; k++) d[k] = a[k] - b[k];
                }
                case MUL -> {
                    float[] d = r[code[pc + 1]], a = r[code[pc + 2]], b = r[code[pc + 3]];
                    for (int k = 0; k < n; k++) d[k] = a[k] * b[k];
                }
                case DIV -> {
                    float[] d = r[code[pc + 1]], a = r[code[pc + 2]], b = r[code[pc + 3]];
                    for (int k = 0; k < n; k++) d[k] = a[k] / b[k];
                }
                case MIN -> {
                    float[] d = r[code[pc + 1]], a = r[code[pc + 2]], b = r[code[pc + 3]];
                    for (int k = 0; k < n; k++) d[k] = Math.min(a[k], b[k]);
                }
                case MAX -> {
                    float[] d = r[code[pc + 1]], a = r[code[pc + 2]], b = r[code[pc + 3]];
                    for (int k = 0; k < n; k++) d[k] = Math.max(a[k], b[k]);
                }
                case NEG, ABS, SQRT, SIN, COS, EXP, FLOOR, SAFEINV -> {
                    float[] d = r[code[pc + 1]], a = r[code[pc + 2]];
                    for (int k = 0; k < n; k++) d[k] = unary(op, a[k]);
                }
                case LERP -> {
                    float[] d = r[code[pc + 1]], a = r[code[pc + 2]], b = r[code[pc + 3]], t = r[code[pc + 4]];
                    for (int k = 0; k < n; k++) d[k] = a[k] + (b[k] - a[k]) * t[k];
                }
                case CLAMP -> {
                    float[] d = r[code[pc + 1]], x = r[code[pc + 2]], lo = r[code[pc + 3]], hi = r[code[pc + 4]];
                    for (int k = 0; k < n; k++) d[k] = Math.min(Math.max(x[k], lo[k]), hi[k]);
                }
                case CURVE -> {
                    float[] d = r[code[pc + 1]], a = r[code[pc + 2]], keys = p.curves[code[pc + 3]];
                    for (int k = 0; k < n; k++) d[k] = curve(keys, a[k]);
                }
                case NOISE -> {
                    float[] d = r[code[pc + 1]], x = r[code[pc + 2]], y = r[code[pc + 3]], z = r[code[pc + 4]];
                    for (int k = 0; k < n; k++) d[k] = FxNoise.noise(x[k], y[k], z[k]);
                }
                case CURL -> {
                    float[] dx = r[code[pc + 1]], dy = r[code[pc + 2]], dz = r[code[pc + 3]];
                    float[] x = r[code[pc + 4]], y = r[code[pc + 5]], z = r[code[pc + 6]];
                    float[] c = curl, sc = curlScratch;
                    for (int k = 0; k < n; k++) {
                        FxNoise.curl(x[k], y[k], z[k], c, sc);
                        dx[k] = c[0];
                        dy[k] = c[1];
                        dz[k] = c[2];
                    }
                }
                case COLLIDE -> {
                    float[] vx = r[code[pc + 1]], vy = r[code[pc + 2]], vz = r[code[pc + 3]];
                    CompiledEffect e = effect;
                    for (int k = 0; k < n; k++) system.moveParticle(ix[k], e, vx[k], vy[k], vz[k], dt);
                }
                case TRAIL -> {
                    CompiledEffect e = effect;
                    for (int k = 0; k < n; k++) system.queueTrail(e, ix[k], dt);
                }
                case OUTRATE -> rateOut = r[code[pc + 1]][0];
                default -> throw new IllegalStateException("bad FX opcode " + op);
            }
            pc += LENGTH[op];
        }
    }

    private static void fill(float[] d, int n, float v) {
        for (int k = 0; k < n; k++) d[k] = v;
    }

    /** The unary ops, shared with constant folding so folded and run-time results are identical. */
    static float unary(int op, float a) {
        return switch (op) {
            case NEG -> -a;
            case ABS -> Math.abs(a);
            case SQRT -> (float) Math.sqrt(a);
            case SIN -> (float) Math.sin(a);
            case COS -> (float) Math.cos(a);
            case EXP -> (float) Math.exp(a);
            case FLOOR -> (float) Math.floor(a);
            case SAFEINV -> a > 1e-12f ? 1f / a : 0f;
            default -> throw new IllegalArgumentException("not unary: " + op);
        };
    }

    /** The binary ops, shared with constant folding. */
    static float binary(int op, float a, float b) {
        return switch (op) {
            case ADD -> a + b;
            case SUB -> a - b;
            case MUL -> a * b;
            case DIV -> a / b;
            case MIN -> Math.min(a, b);
            case MAX -> Math.max(a, b);
            default -> throw new IllegalArgumentException("not binary: " + op);
        };
    }

    /** Piecewise-linear curve: {@code keys} = t0, v0, t1, v1, … ascending in t, held at both ends. */
    static float curve(float[] keys, float t) {
        int last = keys.length - 2;
        if (t <= keys[0]) {
            return keys[1];
        }
        for (int i = 2; i <= last; i += 2) {
            if (t <= keys[i]) {
                float t0 = keys[i - 2], span = keys[i] - t0;
                float v0 = keys[i - 1];
                return span <= 0f ? keys[i + 1] : v0 + (keys[i + 1] - v0) * ((t - t0) / span);
            }
        }
        return keys[last + 1];
    }
}
