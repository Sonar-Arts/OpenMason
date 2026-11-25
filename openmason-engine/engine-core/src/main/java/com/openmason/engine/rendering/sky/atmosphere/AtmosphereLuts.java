package com.openmason.engine.rendering.sky.atmosphere;

import com.openmason.engine.rendering.postfx.FullscreenQuad;
import com.openmason.engine.rendering.postfx.RenderTarget;
import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.openmason.engine.rendering.shaders.ShaderResourceLoader;
import org.joml.Vector3fc;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_ACTIVE_TEXTURE;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL20.GL_CURRENT_PROGRAM;
import static org.lwjgl.opengl.GL20.glUseProgram;
import static org.lwjgl.opengl.GL30.*;

/**
 * The look-up tables of Hillaire's sky (EGSR 2020, "A Scalable and Production Ready Sky and
 * Atmosphere Rendering Technique"), drawn as fragment passes into float targets (full floats:
 * deep in twilight the sky's light falls to 1e-8 of the day's, below what half floats hold):
 * <ul>
 *   <li><b>Transmittance</b> (256x64): how much light survives from a point to the top of the
 *       atmosphere, by height and direction.</li>
 *   <li><b>Multiple scattering</b> (32x32): the light scattered more than once, summed over all
 *       orders, by height and light direction.</li>
 *   <li><b>Sky view</b> (192x108, one per light): the sky's radiance round the viewer, latitude by
 *       longitude from the light's azimuth, with most texels near the horizon.</li>
 * </ul>
 * The first two depend only on the {@link AtmosphereSettings} and are rebuilt only when those
 * change; the sky views follow the sun, the moon and the viewer's height and are rebuilt every
 * {@link #update}. Radiance is per unit illuminance of the light. Main (GL) thread only.
 */
public final class AtmosphereLuts {

    public static final int TRANSMITTANCE_WIDTH = 256, TRANSMITTANCE_HEIGHT = 64;
    public static final int MULTISCATTER_SIZE = 32;
    public static final int SKYVIEW_WIDTH = 192, SKYVIEW_HEIGHT = 108;

    /** Texture unit the passes bind their input LUTs on (restored afterwards). */
    private static final int INPUT_UNIT = 0;

    private final FullscreenQuad quad = new FullscreenQuad();
    private final RenderTarget transmittance =
            new RenderTarget(TRANSMITTANCE_WIDTH, TRANSMITTANCE_HEIGHT, false, GL_RGBA32F);
    private final RenderTarget multiScatter =
            new RenderTarget(MULTISCATTER_SIZE, MULTISCATTER_SIZE, false, GL_RGBA32F);
    private final RenderTarget sunSkyView = new RenderTarget(SKYVIEW_WIDTH, SKYVIEW_HEIGHT, false, GL_RGBA32F);
    private final RenderTarget moonSkyView = new RenderTarget(SKYVIEW_WIDTH, SKYVIEW_HEIGHT, false, GL_RGBA32F);

    private final ShaderProgram transmittanceProgram = program("transmittance_lut.frag");
    private final ShaderProgram multiScatterProgram = program("multiscatter_lut.frag");
    private final ShaderProgram skyViewProgram = program("skyview_lut.frag");

    private AtmosphereSettings built;

    private static ShaderProgram program(String fragment) {
        ShaderProgram program = new ShaderProgram();
        program.createVertexShader(ShaderResourceLoader.load("/shaders/postfx/fullscreen.vert"));
        program.createFragmentShader(ShaderResourceLoader.load("/shaders/sky/atmosphere/" + fragment));
        program.link();
        return program;
    }

    /**
     * Brings the LUTs up to date: the transmittance and multiple scattering if {@code settings}
     * differ from those they were built with, the two sky views always.
     *
     * @param viewHeight the viewer's distance from the planet's centre, km
     */
    public void update(AtmosphereSettings settings, Vector3fc sunDir, Vector3fc moonDir, float viewHeight) {
        int drawFbo = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int readFbo = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        int[] viewport = new int[4];
        glGetIntegerv(GL_VIEWPORT, viewport);
        boolean depthTest = glIsEnabled(GL_DEPTH_TEST);
        boolean blend = glIsEnabled(GL_BLEND);
        boolean cull = glIsEnabled(GL_CULL_FACE);
        int program = glGetInteger(GL_CURRENT_PROGRAM);
        int vertexArray = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        int activeUnit = glGetInteger(GL_ACTIVE_TEXTURE);
        glActiveTexture(GL_TEXTURE0 + INPUT_UNIT);
        int inputTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
        glActiveTexture(GL_TEXTURE0 + INPUT_UNIT + 1);
        int inputUnit1Texture = glGetInteger(GL_TEXTURE_BINDING_2D);

        glDisable(GL_DEPTH_TEST);
        glDisable(GL_BLEND);
        glDisable(GL_CULL_FACE);

        if (!settings.equals(built)) {
            drawTransmittance(settings);
            drawMultiScatter(settings);
            built = settings;
        }
        drawSkyView(sunSkyView, settings, sunDir.y(), viewHeight);
        drawSkyView(moonSkyView, settings, moonDir.y(), viewHeight);

        glActiveTexture(GL_TEXTURE0 + INPUT_UNIT + 1);
        glBindTexture(GL_TEXTURE_2D, inputUnit1Texture);
        glActiveTexture(GL_TEXTURE0 + INPUT_UNIT);
        glBindTexture(GL_TEXTURE_2D, inputTexture);
        glActiveTexture(activeUnit);
        glUseProgram(program);
        glBindVertexArray(vertexArray);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, drawFbo);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, readFbo);
        glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        if (depthTest) glEnable(GL_DEPTH_TEST);
        if (blend) glEnable(GL_BLEND);
        if (cull) glEnable(GL_CULL_FACE);
    }

    private void drawTransmittance(AtmosphereSettings settings) {
        transmittance.bind();
        transmittanceProgram.bind();
        settings.applyTo(transmittanceProgram);
        quad.draw();
    }

    private void drawMultiScatter(AtmosphereSettings settings) {
        multiScatter.bind();
        multiScatterProgram.bind();
        settings.applyTo(multiScatterProgram);
        bindInput(multiScatterProgram, "uTransmittanceLut", INPUT_UNIT, transmittance.getColorTexture());
        quad.draw();
    }

    private void drawSkyView(RenderTarget target, AtmosphereSettings settings, float lightZenithCos,
                             float viewHeight) {
        target.bind();
        skyViewProgram.bind();
        settings.applyTo(skyViewProgram);
        skyViewProgram.setFloat("uViewHeight", viewHeight);
        skyViewProgram.setFloat("uLightZenithCos", lightZenithCos);
        bindInput(skyViewProgram, "uTransmittanceLut", INPUT_UNIT, transmittance.getColorTexture());
        bindInput(skyViewProgram, "uMultiScatterLut", INPUT_UNIT + 1, multiScatter.getColorTexture());
        quad.draw();
    }

    private static void bindInput(ShaderProgram program, String sampler, int unit, int texture) {
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_2D, texture);
        program.setInt(sampler, unit);
    }

    public int transmittanceTexture() {
        return transmittance.getColorTexture();
    }

    public int sunSkyViewTexture() {
        return sunSkyView.getColorTexture();
    }

    public int moonSkyViewTexture() {
        return moonSkyView.getColorTexture();
    }

    public void cleanup() {
        transmittanceProgram.cleanup();
        multiScatterProgram.cleanup();
        skyViewProgram.cleanup();
        transmittance.cleanup();
        multiScatter.cleanup();
        sunSkyView.cleanup();
        moonSkyView.cleanup();
        quad.cleanup();
    }
}
