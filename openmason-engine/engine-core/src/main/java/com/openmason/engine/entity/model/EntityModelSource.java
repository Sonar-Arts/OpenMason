package com.openmason.engine.entity.model;

import com.openmason.engine.format.fx.FxData;
import com.openmason.engine.format.sound.SoundData;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The neutral input of an entity model (#88): generic engine pieces only — OMO model bytes, OMANIM
 * clip bytes, shared sound and FX data — so the engine never parses a game's container format.
 * A game's {@link EntityModelImporter} unpacks its container into one of these; the engine's
 * {@link EntityModelLoader} turns it into a render-ready {@link EntityAsset}.
 *
 * @param objectId      the asset's id ({@code mygame:cow}); also the {@link EntityAsset#objectId()}
 * @param displayName   human name, for logs (may equal the id)
 * @param baseModel     the default variant's OMO bytes ({@link EntityAsset#DEFAULT_VARIANT})
 * @param variantModels further appearance variants: name → OMO bytes (insertion order kept)
 * @param stateClips    behaviour state name → OMANIM clip bytes (insertion order kept)
 * @param sounds        sound bindings, or null
 * @param soundBytes    embedded sound samples keyed by entry filename
 * @param fx            FX bindings, or null
 */
public record EntityModelSource(String objectId,
                                String displayName,
                                byte[] baseModel,
                                Map<String, byte[]> variantModels,
                                Map<String, byte[]> stateClips,
                                SoundData sounds,
                                Map<String, byte[]> soundBytes,
                                FxData fx) {

    public EntityModelSource {
        Objects.requireNonNull(objectId, "objectId");
        Objects.requireNonNull(baseModel, "baseModel");
        displayName = displayName == null ? objectId : displayName;
        variantModels = variantModels == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(variantModels));
        stateClips = stateClips == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(stateClips));
        soundBytes = soundBytes == null ? Map.of() : Map.copyOf(soundBytes);
    }

    /** A bare model: one variant, no clips, sounds or FX (an attachable accessory). */
    public static EntityModelSource modelOnly(String objectId, byte[] omoBytes) {
        return new EntityModelSource(objectId, objectId, omoBytes, Map.of(), Map.of(), null, Map.of(), null);
    }
}
