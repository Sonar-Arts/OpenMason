package com.openmason.engine.rendering.postfx;

import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.openmason.engine.rendering.shaders.ShaderResourceLoader;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;

/**
 * The final pass: draws the HDR scene into the bound (display) framebuffer through
 * exposure, the night grade and the fitted ACES curve ({@code shaders/postfx/tonemap.frag}).
 * Writes colour only; depth testing and depth writes are off while it draws.
 */
final class ToneMapPass {

    private final ShaderProgram program;

    ToneMapPass() {
        program = new ShaderProgram();
        program.createVertexShader(ShaderResourceLoader.load("/shaders/postfx/fullscreen.vert"));
        program.createFragmentShader(ShaderResourceLoader.load("/shaders/postfx/tonemap.frag"));
        program.link();
        program.createUniform("sceneColor");
        program.createUniform("exposure");
        program.createUniform("nightAdaptation");
    }

    void draw(int sceneColorTexture, FullscreenQuad quad, PostFxFrameParams params) {
        glDisable(GL_DEPTH_TEST);
        glDepthMask(false);
        glDisable(GL_BLEND);
        program.bind();
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, sceneColorTexture);
        program.setUniform("sceneColor", 0);
        program.setUniform("exposure", params.exposure());
        program.setUniform("nightAdaptation", params.nightAdaptation());
        quad.draw();
        program.unbind();
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    void cleanup() {
        program.cleanup();
    }
}
