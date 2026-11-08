package com.openmason.engine.format.fx;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One FX binding in an asset manifest's {@code fx[]} section (SBO 1.12+ / SBE 1.6+): when {@code event} happens
 * to the asset, play the FX named {@code fx}.
 *
 * <p>The event names follow the {@code sounds[]} section ({@link FxData} lists the standard ones, others are
 * free-form). Several bindings may share an event; the runtime plays all of them.
 *
 * <p>{@code fx} names an FX the game ships, as the game names it (an {@code .omfx} system id such as
 * {@code campfire}, or a game's own id such as {@code mygame:campfire}); the format does not resolve it.
 *
 * @param event           event name (never blank)
 * @param fx              FX id (never blank)
 * @param offsetX         X of where the FX plays, in the asset's local space (blocks), relative to the
 *                        position the game reports the event at (a block's centre, an entity's feet)
 * @param offsetY         Y of the offset
 * @param offsetZ         Z of the offset
 * @param params          FX parameter name → value overriding the FX's default; never null, may be empty
 * @param tintFromTexture whether the game tints the FX with a colour sampled from the asset's texture (debris
 *                        coloured like the block it came from); how the colour is picked and which parameter it
 *                        drives is the game's choice
 */
public record FxDef(
        String event,
        String fx,
        float offsetX,
        float offsetY,
        float offsetZ,
        Map<String, FxParamValue> params,
        boolean tintFromTexture
) {
    public FxDef {
        Objects.requireNonNull(event, "fx event cannot be null");
        Objects.requireNonNull(fx, "fx id cannot be null");
        if (event.isBlank()) {
            throw new IllegalArgumentException("fx event cannot be blank");
        }
        if (fx.isBlank()) {
            throw new IllegalArgumentException("fx id for event '" + event + "' cannot be blank");
        }
        if (!Float.isFinite(offsetX) || !Float.isFinite(offsetY) || !Float.isFinite(offsetZ)) {
            throw new IllegalArgumentException("fx offset for event '" + event + "' must be finite");
        }
        Map<String, FxParamValue> copy = new LinkedHashMap<>();
        if (params != null) {
            for (Map.Entry<String, FxParamValue> e : params.entrySet()) {
                String name = e.getKey();
                if (name == null || name.isBlank()) {
                    throw new IllegalArgumentException("fx parameter name for event '" + event + "' is blank");
                }
                copy.put(name, Objects.requireNonNull(e.getValue(), "value of fx parameter " + name));
            }
        }
        params = Collections.unmodifiableMap(copy);
    }

    /** A binding with no offset, parameter overrides or tint. */
    public static FxDef of(String event, String fx) {
        return new FxDef(event, fx, 0f, 0f, 0f, Map.of(), false);
    }

    /** True when the binding moves the FX away from the event position. */
    public boolean hasOffset() {
        return offsetX != 0f || offsetY != 0f || offsetZ != 0f;
    }
}
