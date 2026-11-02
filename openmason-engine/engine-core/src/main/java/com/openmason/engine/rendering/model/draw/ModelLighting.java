package com.openmason.engine.rendering.model.draw;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Per-draw lighting inputs of the {@link ModelRenderer} lit pass.
 *
 * <p>Fragment brightness is {@code ambient · (0.5 + 0.55 · diffuse · shadow)},
 * scaled by {@code mix(0.3, 1, skyLight)}, plus the shader extension's extra light
 * and {@code selfGlow}; then distance fog toward {@code fogColor}.
 *
 * @param ambient      ambient light, 0..1 (day/night scaled by the host)
 * @param keyDirection direction toward the key light (sun, moon or preview lamp), world space
 * @param skyLight     sampled sky light at the model, 0..1 (1 = open sky)
 * @param selfGlow     flat extra brightness, 0 = none (a torch lit by its own flame)
 * @param fogDensity   exponential fog density, 0 = no fog
 * @param fogColor     fog colour
 * @param cameraPos    camera position in the space the part matrices produce (fog distance)
 * @param baseColor    albedo of batches drawn untextured ({@link MaterialTextures} returned 0)
 */
public record ModelLighting(float ambient, Vector3fc keyDirection, float skyLight, float selfGlow,
                            float fogDensity, Vector3fc fogColor, Vector3fc cameraPos,
                            Vector3fc baseColor) {

    /** Preview ambient: fully lit. */
    public static final float PREVIEW_AMBIENT = 1.0f;

    /**
     * Preview key light in <em>camera</em> space (toward the light): above, a little
     * to the left and in front of the viewer. Camera-relative so an orbiting camera
     * always sees the same lit side.
     */
    public static final Vector3fc PREVIEW_KEY_LIGHT_VIEW = new Vector3f(-0.35f, 0.6f, 0.7f).normalize();

    /** White: untextured faces show the lighting alone. */
    public static final Vector3fc DEFAULT_BASE_COLOR = new Vector3f(1.0f, 1.0f, 1.0f);

    private static final Vector3fc ZERO = new Vector3f();
    private static final Vector3fc DEFAULT_FOG = new Vector3f(0.1f, 0.3f, 0.5f);

    /**
     * The preview rig used by editor viewports and in-game model previews: full
     * ambient, the {@link #PREVIEW_KEY_LIGHT_VIEW camera-relative key light}, open
     * sky, no glow, no fog. Everything world-driven is off.
     *
     * @param viewMatrix the camera view matrix (null = key light in world space as authored)
     */
    public static ModelLighting preview(Matrix4f viewMatrix) {
        return preview(viewMatrix, DEFAULT_BASE_COLOR);
    }

    /** {@link #preview(Matrix4f)} with a base colour for untextured batches. */
    public static ModelLighting preview(Matrix4f viewMatrix, Vector3fc baseColor) {
        return new ModelLighting(PREVIEW_AMBIENT,
                previewKeyLight(viewMatrix, new Matrix4f(), new Vector3f()),
                1.0f, 0f, 0f, DEFAULT_FOG, ZERO, baseColor);
    }

    /**
     * The preview key light in world space (toward the light, unit length): the
     * camera-space {@link #PREVIEW_KEY_LIGHT_VIEW} rotated by the inverse view.
     */
    public static Vector3f previewKeyLight(Matrix4f viewMatrix, Matrix4f scratch, Vector3f out) {
        out.set(PREVIEW_KEY_LIGHT_VIEW);
        if (viewMatrix == null) return out;
        return scratch.set(viewMatrix).invert().transformDirection(out).normalize();
    }

    /** This lighting with another self-glow. */
    public ModelLighting withSelfGlow(float glow) {
        return new ModelLighting(ambient, keyDirection, skyLight, Math.max(0f, glow),
                fogDensity, fogColor, cameraPos, baseColor);
    }

    /** This lighting with another base colour. */
    public ModelLighting withBaseColor(Vector3fc color) {
        return new ModelLighting(ambient, keyDirection, skyLight, selfGlow,
                fogDensity, fogColor, cameraPos, color);
    }
}
