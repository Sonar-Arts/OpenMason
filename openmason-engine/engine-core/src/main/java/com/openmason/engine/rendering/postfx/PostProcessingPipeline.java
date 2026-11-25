package com.openmason.engine.rendering.postfx;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL20.glUseProgram;
import static org.lwjgl.opengl.GL30.*;

/**
 * Coordinates the HDR scene target, screen-space effects and the final tone-mapping pass.
 *
 * <p>Usage per frame: {@link #beginFrame()} → render the 3D scene normally →
 * {@link #endFrame(PostFxFrameParams)}. The scene renders into a {@code GL_R11F_G11F_B10F}
 * target: the scene shaders' own (display-referred, gamma 2.2) colour, unclamped, so light
 * brighter than white survives. {@code endFrame} lets each registered
 * {@link PostProcessingEffect} composite into that HDR scene (god rays add to it), blits the
 * scene's depth to the default framebuffer, and finally draws the scene there through
 * exposure, the night grade and the fitted ACES curve ({@link ToneMapPass}).</p>
 *
 * <p>After {@code endFrame} returns, GL state matches what overlay/UI code expects following a
 * world render: default framebuffer bound, window viewport, depth test on (GL_LESS), depth
 * writes on, blending off, no program/VAO/texture bound. UI drawn after it is not tone mapped.</p>
 */
public class PostProcessingPipeline {

    private static final Logger LOGGER = Logger.getLogger(PostProcessingPipeline.class.getName());

    private final RenderTarget sceneTarget;
    private final FullscreenQuad quad;
    private final ToneMapPass toneMap;
    private final List<PostProcessingEffect> effects = new ArrayList<>();

    /**
     * Whether the depth blit to the default framebuffer is supported. Depth blits fail with
     * GL_INVALID_OPERATION when the default framebuffer's depth format differs from the scene
     * target's; in that case depth is not copied from then on.
     */
    private boolean depthBlitSupported = true;
    private boolean depthBlitProbed = false;

    public PostProcessingPipeline(int width, int height) {
        sceneTarget = new RenderTarget(width, height, true, GL_R11F_G11F_B10F);
        quad = new FullscreenQuad();
        toneMap = new ToneMapPass();
    }

    /**
     * Registers an effect, initializing its GPU resources at the current output size.
     */
    public void addEffect(PostProcessingEffect effect) {
        effect.init(sceneTarget.getWidth(), sceneTarget.getHeight());
        effects.add(effect);
    }

    /**
     * Binds the scene framebuffer and clears it. Call before rendering the 3D scene.
     */
    public void beginFrame() {
        sceneTarget.bind();
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    }

    /**
     * Composites the effects into the HDR scene, copies its depth to the default framebuffer,
     * tone maps it there, and restores the GL state contract expected by overlay/UI rendering.
     */
    public void endFrame(PostFxFrameParams params) {
        int width = sceneTarget.getWidth();
        int height = sceneTarget.getHeight();

        // Effects add into the HDR scene, so they pass through the tone map with it.
        sceneTarget.bind();
        for (PostProcessingEffect effect : effects) {
            effect.apply(sceneTarget.getColorTexture(), sceneTarget.getDepthTexture(),
                    width, height, quad, params);
        }

        blitDepthToDefaultFramebuffer(width, height);

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, width, height);
        toneMap.draw(sceneTarget.getColorTexture(), quad, params);

        // State contract: leave GL exactly as overlay/UI code expects after a world render.
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, width, height);
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LESS);
        glDepthMask(true);
        glDisable(GL_BLEND);
        glUseProgram(0);
        glBindVertexArray(0);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    /**
     * Copies the scene's depth to the default framebuffer, for overlays that depth-test
     * against the world (the pause menu's depth curtain).
     */
    private void blitDepthToDefaultFramebuffer(int width, int height) {
        if (!depthBlitSupported) {
            return;
        }
        glBindFramebuffer(GL_READ_FRAMEBUFFER, sceneTarget.getFboId());
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);

        if (!depthBlitProbed) {
            // Drain any pre-existing error so the probe below is unambiguous.
            while (glGetError() != GL_NO_ERROR) {
                // discard
            }
        }

        glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_DEPTH_BUFFER_BIT, GL_NEAREST);

        if (!depthBlitProbed) {
            depthBlitProbed = true;
            if (glGetError() != GL_NO_ERROR) {
                // Depth format mismatch with the default framebuffer: skip depth from now on.
                // Downstream depth-dependent draws (pause-menu depth curtain) then see the
                // pre-frame default depth buffer.
                depthBlitSupported = false;
                LOGGER.log(Level.WARNING,
                        "Depth blit to the default framebuffer rejected by driver; overlays will not see scene depth");
            }
        }
    }

    /**
     * Resizes the scene target and all effects. Safe to call with zero dimensions
     * (minimized window) — the call is ignored.
     */
    public void resize(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        sceneTarget.resize(width, height);
        for (PostProcessingEffect effect : effects) {
            effect.resize(width, height);
        }
    }

    public void cleanup() {
        for (PostProcessingEffect effect : effects) {
            effect.cleanup();
        }
        effects.clear();
        toneMap.cleanup();
        quad.cleanup();
        sceneTarget.cleanup();
    }
}
