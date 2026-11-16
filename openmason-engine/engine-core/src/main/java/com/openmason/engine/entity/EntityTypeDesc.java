package com.openmason.engine.entity;

/**
 * An entity type as the game describes it to the engine ({@code ce_type_desc} + model id). A
 * spawned entity's body starts from these sizes; renderers, collision and replication can read the
 * type generically instead of asking the game.
 *
 * @param width               body size on x
 * @param height              body height above the entity position
 * @param length              body size on z
 * @param legHeight           legs below the position: the body box starts at {@code y - legHeight}
 * @param modelYawOffsetDeg   yaw between world forward (+Z) and the way the model was authored
 * @param flags               game-defined bits
 * @param modelId             the model asset id, or null when the type has no model
 */
public record EntityTypeDesc(float width, float height, float length, float legHeight,
                             float modelYawOffsetDeg, int flags, String modelId) {
}
