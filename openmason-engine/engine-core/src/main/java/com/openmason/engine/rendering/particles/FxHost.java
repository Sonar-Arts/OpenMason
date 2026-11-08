package com.openmason.engine.rendering.particles;

/**
 * The game's side of FX: named events an {@link FxSystem} forwards ({@link FxEventHandler#forward}) and sounds it
 * plays. Called on the thread running {@link FxWorld#update}, after the simulation step, never while particles are
 * being integrated. The {@code name} strings are the asset's own (no allocation per call); compare with
 * {@code equals} or switch on them. Positions are world coordinates.
 */
public interface FxHost {

    FxHost NONE = new FxHost() { };

    /** A forwarded event: game logic for "the firework burst here" or "debris landed". */
    default void event(FxInstance instance, String name, double x, double y, double z) {
    }

    /**
     * Play the sound event {@code sound} at {@code (x, y, z)}: look it up in {@code instance.system().sounds()} (the
     * shared {@code format.sound} definitions) or the host's own bank.
     */
    default void sound(FxInstance instance, String sound, double x, double y, double z, float volume) {
    }
}
