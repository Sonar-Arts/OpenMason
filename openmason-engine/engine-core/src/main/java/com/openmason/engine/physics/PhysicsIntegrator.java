package com.openmason.engine.physics;

/**
 * Integration parameters for {@link PhysicsWorld#step} ({@code cp_integrator}): gravity, ground
 * friction and fluid response. The values are the host's tuning; the engine has none of its own.
 *
 * @param gravity          signed acceleration on y (e.g. -40)
 * @param groundFriction   horizontal exp decay per second while standing (0 = none)
 * @param floatSubmersion  fraction under water at neutral buoyancy; &lt;= 0 = no buoyancy
 * @param driftSpeed       buoyancy alone never pushes faster than this upward
 * @param fluidDrag        vertical exp decay per second, scaled by submersion
 * @param fluidMask        fluid categories that act on the body (0 = none)
 */
public record PhysicsIntegrator(float gravity, float groundFriction, float floatSubmersion, float driftSpeed,
                                float fluidDrag, int fluidMask) {
}
