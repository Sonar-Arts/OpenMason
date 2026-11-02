package com.openmason.engine.rendering.model.draw;

import org.joml.Matrix4f;

/**
 * Supplies the world (render-space) matrix of every part a draw covers, in part
 * order. {@link ModelPoseSolver} produces these for animated entity models; a
 * model whose part transforms are baked into its vertices (an editable model) is
 * {@link #single} part with its model matrix.
 */
@FunctionalInterface
public interface PartMatrices {

    /** Receives one part's matrix. The matrix may be reused after the call returns. */
    @FunctionalInterface
    interface Consumer {
        void accept(int partIndex, Matrix4f partMatrix);
    }

    void forEachPart(Consumer consumer);

    /** One part (index 0) drawn with {@code modelMatrix}. */
    static PartMatrices single(Matrix4f modelMatrix) {
        return consumer -> consumer.accept(0, modelMatrix);
    }
}
