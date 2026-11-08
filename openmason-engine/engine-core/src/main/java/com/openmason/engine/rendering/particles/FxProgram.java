package com.openmason.engine.rendering.particles;

import com.openmason.engine.rendering.particles.graph.FxStageKind;

import java.util.Arrays;

/**
 * One stage of an effect compiled for the CPU backend: a <em>uniform</em> part evaluated once per run (values that
 * are the same for every particle — constants, dt, wind and everything computed only from them) and a <em>lane</em>
 * part executed one instruction at a time across a chunk of particles held in float registers (Niagara VectorVM
 * style). Immutable; equal programs have equal code ({@link #equals}), which is how IR round trips are checked.
 *
 * <p>Instruction format: an opcode followed by its operands ({@link FxVm#LENGTH}); operands are register numbers,
 * column ids, constant or curve indices.
 */
public final class FxProgram {

    final FxStageKind stage;
    final int[] uniformCode;
    final int[] laneCode;
    final float[] constants;
    final float[][] curves;
    final int uniformRegisters;
    final int laneRegisters;

    FxProgram(FxStageKind stage, int[] uniformCode, int[] laneCode, float[] constants, float[][] curves,
              int uniformRegisters, int laneRegisters) {
        this.stage = stage;
        this.uniformCode = uniformCode;
        this.laneCode = laneCode;
        this.constants = constants;
        this.curves = curves;
        this.uniformRegisters = uniformRegisters;
        this.laneRegisters = laneRegisters;
    }

    public FxStageKind stage() { return stage; }

    /** Instructions run per chunk of particles. */
    public int laneInstructions() { return count(laneCode); }

    /** Instructions run once per run. */
    public int uniformInstructions() { return count(uniformCode); }

    public int laneRegisters() { return laneRegisters; }

    private static int count(int[] code) {
        int n = 0;
        for (int pc = 0; pc < code.length; pc += FxVm.LENGTH[code[pc]]) {
            n++;
        }
        return n;
    }

    /** Human-readable listing (debug views, tests). */
    public String disassemble() {
        StringBuilder sb = new StringBuilder();
        sb.append("; ").append(stage).append(" uniform\n");
        listing(sb, uniformCode, "u");
        sb.append("; lanes\n");
        listing(sb, laneCode, "r");
        return sb.toString();
    }

    private void listing(StringBuilder sb, int[] code, String reg) {
        for (int pc = 0; pc < code.length; pc += FxVm.LENGTH[code[pc]]) {
            int op = code[pc];
            sb.append("  ").append(FxVm.NAMES[op]);
            for (int k = 1; k < FxVm.LENGTH[op]; k++) {
                sb.append(k == 1 ? " " : ", ").append(code[pc + k]);
            }
            if (op == FxVm.CONST) {
                sb.append("  ; ").append(constants[code[pc + 2]]);
            }
            sb.append('\n');
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof FxProgram p && stage == p.stage && Arrays.equals(uniformCode, p.uniformCode)
                && Arrays.equals(laneCode, p.laneCode) && Arrays.equals(constants, p.constants)
                && Arrays.deepEquals(curves, p.curves) && uniformRegisters == p.uniformRegisters
                && laneRegisters == p.laneRegisters;
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(laneCode) * 31 + Arrays.hashCode(uniformCode);
    }

    @Override
    public String toString() {
        return "FxProgram[" + stage + ", " + uniformInstructions() + " uniform + " + laneInstructions()
                + " lane instructions, " + laneRegisters + " registers]";
    }
}
