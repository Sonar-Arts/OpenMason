package com.openmason.engine.entity.model;

import com.openmason.engine.format.oma.ParsedAnimClip;
import com.openmason.engine.format.fx.FxData;
import com.openmason.engine.format.sound.SoundData;

import java.util.Map;

/**
 * A fully resolved, immutable entity asset, built by {@link EntityModelLoader} from a neutral
 * {@link EntityModelSource} (which a game's container format importer produces, #88).
 *
 * <p>Holds one {@link EntityGeometry} per appearance variant and one
 * {@link ParsedAnimClip} per behaviour state, plus the source's {@code objectId}
 * by which the registry indexes it. The asset carries no behavioural knowledge
 * of which entity it represents — variant and state names are plain strings
 * keyed by whatever the author chose — so it can back any data-driven entity.
 *
 * @param objectId   the asset's object id (e.g. {@code mygame:cow})
 * @param variants   variant name (e.g. {@code Default}) → resolved geometry
 * @param clips      state name (e.g. {@code Idle}) → animation clip
 * @param sounds     the asset's sound bindings, or null when it declares
 *                   none; resolved for playback by the audio layer
 * @param soundBytes embedded sound sample bytes keyed by archive entry
 *                   filename ; empty when no def embeds audio
 * @param fx         the asset's FX bindings, or null when it declares none
 * @param character  the container's character definition ({@code .omchar} bytes, #153), or null
 */
public record EntityAsset(
        String objectId,
        Map<String, EntityGeometry> variants,
        Map<String, ParsedAnimClip> clips,
        SoundData sounds,
        Map<String, byte[]> soundBytes,
        FxData fx,
        byte[] character,
        Map<String, byte[]> clipBytes
) {
    /** An asset without a character definition. */
    public EntityAsset(String objectId, Map<String, EntityGeometry> variants, Map<String, ParsedAnimClip> clips,
                       SoundData sounds, Map<String, byte[]> soundBytes, FxData fx) {
        this(objectId, variants, clips, sounds, soundBytes, fx, null, Map.of());
    }

    /** An asset without the clips' container bytes (built in code: tests, block animations). */
    public EntityAsset(String objectId, Map<String, EntityGeometry> variants, Map<String, ParsedAnimClip> clips,
                       SoundData sounds, Map<String, byte[]> soundBytes, FxData fx, byte[] character) {
        this(objectId, variants, clips, sounds, soundBytes, fx, character, Map.of());
    }

    /** True when the container embedded a character definition ({@code .omchar}, #153). */
    public boolean hasCharacter() {
        return character != null && character.length > 0;
    }

    /** The variant used when a requested name is unknown. */
    public static final String DEFAULT_VARIANT = "Default";

    public EntityAsset {
        variants = variants == null ? Map.of() : Map.copyOf(variants);
        clips = clips == null ? Map.of() : Map.copyOf(clips);
        soundBytes = soundBytes == null ? Map.of() : Map.copyOf(soundBytes);
        clipBytes = clipBytes == null ? Map.of() : Map.copyOf(clipBytes);
    }

    /** An asset without FX bindings. */
    public EntityAsset(String objectId,
                          Map<String, EntityGeometry> variants,
                          Map<String, ParsedAnimClip> clips,
                          SoundData sounds,
                          Map<String, byte[]> soundBytes) {
        this(objectId, variants, clips, sounds, soundBytes, null, null);
    }

    /** Soundless convenience constructor (bare OMO/SBO attachables, tests). */
    public EntityAsset(String objectId,
                          Map<String, EntityGeometry> variants,
                          Map<String, ParsedAnimClip> clips) {
        this(objectId, variants, clips, null, Map.of(), null, null);
    }

    /**
     * Geometry for a variant, matched case-insensitively. Falls back to
     * {@link #DEFAULT_VARIANT} when the name is unknown.
     */
    public EntityGeometry geometryFor(String variantName) {
        if (variantName != null) {
            for (Map.Entry<String, EntityGeometry> e : variants.entrySet()) {
                if (e.getKey().equalsIgnoreCase(variantName)) {
                    return e.getValue();
                }
            }
        }
        return variants.get(DEFAULT_VARIANT);
    }

    /** Animation clip for a state name, or {@code null} if absent or {@code stateName} is null. */
    public ParsedAnimClip clipFor(String stateName) {
        return stateName != null ? clips.get(stateName) : null;
    }

    /**
     * The {@code .omanim} container bytes of a state's clip as the asset shipped them, or null: what the native clip
     * runtime reads ({@code AnimClipDocument.read}) to bind the clip into an animation graph (#457). The arrays are
     * shared; never modify them.
     */
    public byte[] clipBytesFor(String stateName) {
        return stateName != null ? clipBytes.get(stateName) : null;
    }

    /** Bytes of an embedded sound sample by entry filename, or null. */
    public byte[] soundBytesFor(String filename) {
        return soundBytes.get(filename);
    }
}
