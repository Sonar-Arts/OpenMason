package com.openmason.main.systems.menus.animationEditor.io;

import com.openmason.engine.modeling.parts.ModelPartManager;
import com.openmason.main.systems.menus.animationEditor.data.AnimationClip;
import com.openmason.main.systems.menus.animationEditor.data.Joints;
import com.openmason.main.systems.menus.animationEditor.data.Keyframe;
import com.openmason.main.systems.menus.animationEditor.data.Track;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * File I/O for {@code .oma} animation clips. Owns the serializer pair and the
 * post-load rebind step that maps saved partIds onto the currently-loaded model.
 *
 * <p>Extracted from the controller so editing/history concerns and persistence
 * concerns live in separate classes.
 */
public final class OMAClipIO {

    private static final Logger logger = LoggerFactory.getLogger(OMAClipIO.class);

    private final OMASerializer serializer = new OMASerializer();
    private final OMADeserializer deserializer = new OMADeserializer();

    /**
     * Save {@code clip} to {@code filePath}. Joint-name hints are populated from
     * {@code partManager} so future loads can rebind by name if IDs drift.
     */
    public boolean save(AnimationClip clip, String filePath, ModelPartManager partManager) {
        return save(clip, filePath, Joints.of(partManager, null));
    }

    /**
     * Save {@code clip}; track refs carry their joint's name (part or bone) as a
     * rebind hint and bone tracks are marked {@code "targetType": "bone"}.
     */
    public boolean save(AnimationClip clip, String filePath, Joints joints) {
        return serializer.save(clip, filePath, joints::nameOf, joints::isBone);
    }

    /**
     * Load a clip from {@code filePath}, then rebind any tracks whose saved
     * partId no longer matches a part on {@code partManager}, using the saved
     * name hint as a fallback. Returns {@code null} if the file fails to parse.
     */
    public AnimationClip load(String filePath, ModelPartManager partManager) {
        return load(filePath, Joints.of(partManager, null));
    }

    /** Load and rebind every track against the model's joints (parts and bones). */
    public AnimationClip load(String filePath, Joints joints) {
        AnimationClip loaded = deserializer.load(filePath);
        if (loaded == null) return null;
        rebindTracksByName(loaded, joints);
        return loaded;
    }

    /** Serialize to in-memory archive bytes (SBE/SBO embedding). Null on failure. */
    public byte[] toBytes(AnimationClip clip, ModelPartManager partManager) {
        return toBytes(clip, Joints.of(partManager, null));
    }

    /** Serialize to in-memory archive bytes, naming and marking joints like {@link #save}. */
    public byte[] toBytes(AnimationClip clip, Joints joints) {
        try {
            return serializer.toBytes(clip, joints::nameOf, joints::isBone);
        } catch (java.io.IOException ex) {
            logger.error("Failed to serialize clip '{}' to bytes", clip.name(), ex);
            return null;
        }
    }

    /** Parse archive bytes and rebind against the model, like {@link #load(String, ModelPartManager)}. */
    public AnimationClip fromBytes(byte[] omaBytes, String sourceLabel, ModelPartManager partManager) {
        return fromBytes(omaBytes, sourceLabel, Joints.of(partManager, null));
    }

    /** Parse archive bytes and rebind against the model's joints. */
    public AnimationClip fromBytes(byte[] omaBytes, String sourceLabel, Joints joints) {
        AnimationClip loaded = deserializer.load(omaBytes, sourceLabel);
        if (loaded == null) return null;
        rebindTracksByName(loaded, joints);
        return loaded;
    }

    /** Rebind against parts only (see {@link #rebindTracksByName(AnimationClip, Joints)}). */
    public static void rebindTracksByName(AnimationClip clip, ModelPartManager partManager) {
        rebindTracksByName(clip, Joints.of(partManager, null));
    }

    /**
     * For each track whose joint id is missing on the current model (neither a
     * part nor a bone), look up by name hint — parts and bones, exact then
     * case-insensitive — and rewrite the track's key. Tracks with no match are
     * kept as orphans so the user can rebind manually. Public so the controller
     * can re-run it when the active model changes while the editor is open.
     */
    public static void rebindTracksByName(AnimationClip clip, Joints joints) {
        if (joints == null || (joints.parts() == null && joints.bones() == null)) return;
        Map<String, Track> remap = new LinkedHashMap<>();
        for (var entry : new ArrayList<>(clip.tracks().entrySet())) {
            String savedId = entry.getKey();
            Track savedTrack = entry.getValue();

            if (joints.exists(savedId)) {
                remap.put(savedId, savedTrack);
                continue;
            }

            String matchId = joints.idByName(savedTrack.partNameHint());
            if (matchId != null && !remap.containsKey(matchId)) {
                Track rebound = new Track(matchId);
                rebound.setPartNameHint(joints.nameOf(matchId));
                for (Keyframe kf : savedTrack.keyframes()) {
                    rebound.upsert(kf);
                }
                remap.put(matchId, rebound);
                logger.info("Rebound animation track '{}' (saved id '{}') to current joint id '{}'",
                        rebound.partNameHint(), savedId, matchId);
            } else {
                remap.put(savedId, savedTrack);
                logger.warn("Animation track for joint '{}' (name hint '{}') has no matching part or bone in the loaded model",
                        savedId, savedTrack.partNameHint());
            }
        }
        clip.tracks().clear();
        clip.tracks().putAll(remap);
    }
}
