package com.openmason.engine.rendering.sky.atmosphere;

import com.openmason.engine.rendering.shaders.ShaderProgram;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * A planet's atmosphere, as {@link AtmosphereLuts} renders it: its size and the three media
 * that scatter and absorb light in it. Distances in kilometres, coefficients per kilometre.
 * Immutable; the LUTs that depend only on it rebuild when a different one is given.
 *
 * @param bottomRadius         the planet's radius
 * @param topRadius            the radius of the atmosphere's top
 * @param rayleighScattering   air molecules' scattering at the ground (no absorption)
 * @param rayleighScaleHeight  height over which their density falls by e
 * @param mieScattering        aerosols' scattering at the ground
 * @param mieAbsorption        aerosols' absorption at the ground
 * @param mieScaleHeight       height over which their density falls by e
 * @param mieG                 the aerosols' phase asymmetry (Cornette-Shanks g): forward-scattering near 1
 * @param ozoneAbsorption      ozone's absorption at the peak of its layer; it does not scatter. This is
 *                             what keeps the zenith blue through the blue hour: sunlight reaching it at a
 *                             grazing angle crosses enough ozone for its Chappuis band to take the orange
 * @param ozoneCenter          altitude of the ozone layer's peak; its density is a tent round it
 * @param ozoneWidth           the tent's full width
 * @param groundAlbedo         the ground's diffuse reflectance, for the light it bounces into the sky
 */
public record AtmosphereSettings(float bottomRadius, float topRadius,
                                 Vector3fc rayleighScattering, float rayleighScaleHeight,
                                 Vector3fc mieScattering, Vector3fc mieAbsorption, float mieScaleHeight,
                                 float mieG,
                                 Vector3fc ozoneAbsorption, float ozoneCenter, float ozoneWidth,
                                 Vector3fc groundAlbedo) {

    public AtmosphereSettings {
        // Copies, so a caller's later change cannot alter settings the LUTs were built from.
        rayleighScattering = new Vector3f(rayleighScattering);
        mieScattering = new Vector3f(mieScattering);
        mieAbsorption = new Vector3f(mieAbsorption);
        ozoneAbsorption = new Vector3f(ozoneAbsorption);
        groundAlbedo = new Vector3f(groundAlbedo);
    }

    /**
     * The Earth, as Hillaire 2020 and Unreal Engine's sky atmosphere default to: Rayleigh
     * (5.802, 13.558, 33.1)e-3/km with an 8 km scale height; Mie scattering 3.996e-3/km and
     * absorption 4.4e-3/km with a 1.2 km scale height, g = 0.8; ozone absorption
     * (0.650, 1.881, 0.085)e-3/km in a tent 30 km wide centred at 25 km.
     */
    public static AtmosphereSettings earth() {
        return new AtmosphereSettings(6360f, 6460f,
                new Vector3f(5.802e-3f, 13.558e-3f, 33.1e-3f), 8f,
                new Vector3f(3.996e-3f), new Vector3f(4.4e-3f), 1.2f, 0.8f,
                new Vector3f(0.650e-3f, 1.881e-3f, 0.085e-3f), 25f, 30f,
                new Vector3f(0.3f));
    }

    /** Sets {@code atmosphere.glsl}'s uniforms on {@code shader}, which must be bound. */
    public void applyTo(ShaderProgram shader) {
        shader.setFloat("uBottomRadius", bottomRadius);
        shader.setFloat("uTopRadius", topRadius);
        shader.setVec3("uRayleighScattering", new Vector3f(rayleighScattering));
        shader.setFloat("uRayleighScaleHeight", rayleighScaleHeight);
        shader.setVec3("uMieScattering", new Vector3f(mieScattering));
        shader.setVec3("uMieAbsorption", new Vector3f(mieAbsorption));
        shader.setFloat("uMieScaleHeight", mieScaleHeight);
        shader.setFloat("uMieG", mieG);
        shader.setVec3("uOzoneAbsorption", new Vector3f(ozoneAbsorption));
        shader.setFloat("uOzoneCenter", ozoneCenter);
        shader.setFloat("uOzoneWidth", ozoneWidth);
        shader.setVec3("uGroundAlbedo", new Vector3f(groundAlbedo));
    }
}
