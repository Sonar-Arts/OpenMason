package com.openmason.engine.rendering.particles;

import java.util.List;

import static com.openmason.engine.rendering.particles.FxBinding.Target.*;
import static com.openmason.engine.rendering.particles.FxEventHandler.Trigger.*;

/**
 * Built-in composite effects made of the {@link ParticleLibrary.Presets}: what an {@link FxSystem} adds over one
 * effect — layers, a timeline, exposed parameters, events between emitters and to the host, a light and sounds.
 * Sound names are free-form here (no {@code sounds[]} definitions): the host resolves them.
 */
public final class FxPresets {

    private FxPresets() {
    }

    /**
     * Fire, embers and a smoke column that comes in once the fire has caught, with a warm flickering light.
     * Parameter {@code intensity} (1) scales emission and the light; {@code tint} colours the flames.
     */
    public static final FxSystem CAMPFIRE = FxSystem.builder("campfire")
            .emitter(FxEmitter.builder("fire", ParticleLibrary.Presets.FIRE).build())
            .emitter(FxEmitter.builder("embers", ParticleLibrary.Presets.EMBERS).offset(0, 0.15f, 0).build())
            .emitter(FxEmitter.builder("smoke", ParticleLibrary.Presets.SMOKE).offset(0, 0.6f, 0).delay(0.8f).build())
            .parameter(FxParameter.scalar("intensity", 1f))
            .parameter(FxParameter.color("tint", 0xFFFFFFFF))
            .bind(FxBinding.of("intensity", RATE))
            .bind(FxBinding.of("intensity", LIGHT_INTENSITY))
            .bind(FxBinding.of("tint", TINT, "fire"))
            .light(FxLight.steady(0xFF9A48FF, 1.4f, 9f).flickering(0.3f, 7f).offset(0, 0.4f, 0))
            .cue(FxSoundCue.onPlay("fire-ignite"))
            .cue(FxSoundCue.onStop("fire-out"))
            .build();

    /**
     * A one-shot blast: fireballs, sparks and debris at once, a flash that dies in 0.6 s. Debris landing kicks up
     * dust and (sometimes) reports {@code debris-land} to the host with a {@code debris-hit} sound. Parameter
     * {@code scale} (1) scales size, speed and the flash; {@code debrisTint} colours the debris like the blasted
     * block. Raise {@code shockwave} for an extra ring of dust.
     */
    public static final FxSystem EXPLOSION = FxSystem.builder("explosion")
            .emitter(FxEmitter.builder("fireball", ParticleLibrary.Presets.EXPLOSION).burst(24).duration(0).build())
            .emitter(FxEmitter.builder("sparks", ParticleLibrary.Presets.SPARKS).burst(70).duration(0).build())
            .emitter(FxEmitter.builder("debris", ParticleLibrary.Presets.DEBRIS).burst(40).duration(0).build())
            .emitter(FxEmitter.builder("dust", ParticleLibrary.Presets.DUST).eventOnly().build())
            .parameter(FxParameter.scalar("scale", 1f))
            .parameter(FxParameter.color("debrisTint", 0xFFFFFFFF))
            .bind(FxBinding.of("scale", SIZE))
            .bind(FxBinding.of("scale", SPEED))
            .bind(FxBinding.of("scale", LIGHT_INTENSITY))
            .bind(FxBinding.of("debrisTint", TINT, "debris"))
            .on(FxEventHandler.spawn(COLLISION, "debris", "dust", 2).inheriting(0.1f))
            .on(FxEventHandler.forward(COLLISION, "debris", "debris-land").withChance(0.25f).playing("debris-hit"))
            .on(FxEventHandler.spawn(CUSTOM, "shockwave", "dust", 40))
            .light(FxLight.steady(0xFFD890FF, 6f, 16f)
                    .withKeys(List.of(new FxLight.Key(0f, 1f), new FxLight.Key(0.12f, 0.7f), new FxLight.Key(0.6f, 0f)), false))
            .cue(FxSoundCue.onPlay("explosion"))
            .build();

    /**
     * A rocket that climbs and bursts into stars where it burns out: the burst is an FX event (rocket DEATH →
     * stars), forwarded to the host as {@code boom} with a {@code firework-boom} sound — unlike
     * {@link ParticleLibrary.Presets#FIREWORK}, whose burst is a plain sub-emitter the game never hears about.
     * Parameter {@code color} tints the stars.
     */
    public static final FxSystem FIREWORK = FxSystem.builder("firework")
            .emitter(FxEmitter.builder("rocket", ParticleLibrary.Presets.FIREWORK.toBuilder().name("firework-rocket")
                    .subEmitters(List.of(ParticleEffect.SubEmitter.trail(ParticleLibrary.Presets.FIREWORK_TRAIL, 90)))
                    .build()).burst(1).duration(0).build())
            .emitter(FxEmitter.builder("stars", ParticleLibrary.Presets.FIREWORK_BURST).eventOnly().build())
            .parameter(FxParameter.color("color", 0xFFFFFFFF))
            .bind(FxBinding.of("color", TINT, "stars"))
            .on(FxEventHandler.spawn(DEATH, "rocket", "stars", 120).inheriting(0.3f).forwarding("boom")
                    .playing("firework-boom"))
            .cue(FxSoundCue.onPlay("firework-launch"))
            .build();

    /** Every built-in FX system. */
    public static final List<FxSystem> ALL = List.of(CAMPFIRE, EXPLOSION, FIREWORK);
}
