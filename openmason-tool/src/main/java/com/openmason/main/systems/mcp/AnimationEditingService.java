package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.annotation.JsonValue;
import com.openmason.engine.modeling.parts.ModelPartDescriptor;
import com.openmason.engine.modeling.parts.ModelPartManager;
import com.openmason.main.systems.services.EditorServices;
import com.openmason.main.systems.menus.animationEditor.AnimationEditorImGui;
import com.openmason.main.systems.menus.animationEditor.controller.AnimationEditorController;
import com.openmason.main.systems.menus.animationEditor.data.AnimationClip;
import com.openmason.main.systems.menus.animationEditor.data.Easing;
import com.openmason.main.systems.menus.animationEditor.data.Keyframe;
import com.openmason.main.systems.menus.animationEditor.data.Track;
import com.openmason.main.systems.menus.animationEditor.state.AnimationEditorState;
import com.openmason.main.systems.threading.MainThreadExecutor;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Thread-safe facade over the Animation Editor's controller. Every method
 * marshals to the main/GL thread via {@link MainThreadExecutor} so MCP HTTP
 * threads can safely drive clip edits, transport, and persistence.
 *
 * <p>Mirrors the contract established by {@link ModelEditingService}.
 */
public final class AnimationEditingService {

    private static final long DEFAULT_TIMEOUT_MS = 5000;

    private final EditorServices editorServices;

    public AnimationEditingService(EditorServices editorServices) {
        this.editorServices = editorServices;
    }

    // ===================== Read =====================

    public AnimationInfo getAnimationInfo() {
        return await(MainThreadExecutor.submit(() -> {
            AnimationEditorController c = optController();
            if (c == null) return new AnimationInfo(false, null, 0, 0, false, null, null, 0, false, false, 0, null);
            return describeOnThread(c);
        }));
    }

    /**
     * Opens the animation editor bound to the model the viewport holds — what Tools → Animation Editor
     * does — so the keyframe tools can resolve the model's parts and bones. Already open: re-binds only
     * if the viewport now holds another model (the preview's rest snapshot is kept otherwise).
     *
     * @throws IllegalStateException when the editor or the viewport does not exist, or no model is loaded
     */
    public AnimationInfo openEditor() {
        return await(MainThreadExecutor.submit(() -> {
            AnimationEditorImGui editor = editorServices.getAnimationEditor();
            if (editor == null) throw new IllegalStateException("Animation editor not initialized");
            var viewport = editorServices.getViewport3D();
            ModelPartManager parts = viewport != null ? viewport.getPartManager() : null;
            if (parts == null) {
                throw new IllegalStateException("No model is open in the viewport; open or create a model first");
            }
            editor.openFor(parts);
            return describeOnThread(editor.getController());
        }));
    }

    public List<TrackView> listTracks() {
        return await(MainThreadExecutor.submit(() -> {
            AnimationEditorController c = requireController();
            var joints = c.joints();
            AnimationClip clip = c.state().clip();
            List<TrackView> out = new ArrayList<>(clip.tracks().size());
            for (Map.Entry<String, Track> e : clip.tracks().entrySet()) {
                String partId = e.getKey();
                String name = joints.nameOf(partId);
                String partName = name != null ? name : e.getValue().partNameHint();
                out.add(new TrackView(partId, partName, e.getValue().size()));
            }
            return out;
        }));
    }

    public Optional<List<KeyframeView>> listKeyframes(String partIdOrName) {
        return await(MainThreadExecutor.submit(() -> {
            AnimationEditorController c = requireController();
            String partId = resolvePartId(c, partIdOrName);
            if (partId == null) return Optional.<List<KeyframeView>>empty();
            Track track = c.state().clip().trackFor(partId);
            if (track == null) return Optional.of(List.<KeyframeView>of());
            List<KeyframeView> out = new ArrayList<>(track.size());
            for (int i = 0; i < track.size(); i++) {
                out.add(KeyframeView.from(i, track.get(i)));
            }
            return Optional.of(out);
        }));
    }

    public Optional<KeyframeView> getKeyframe(String partIdOrName, int index) {
        return await(MainThreadExecutor.submit(() -> {
            AnimationEditorController c = requireController();
            String partId = resolvePartId(c, partIdOrName);
            if (partId == null) return Optional.<KeyframeView>empty();
            Track track = c.state().clip().trackFor(partId);
            if (track == null || index < 0 || index >= track.size()) return Optional.<KeyframeView>empty();
            return Optional.of(KeyframeView.from(index, track.get(index)));
        }));
    }

    // ===================== Mutate: clip metadata =====================

    public AnimationInfo setClipName(String name) {
        return runAndDescribe(c -> c.setClipName(name));
    }

    public AnimationInfo setClipFps(float fps) {
        return runAndDescribe(c -> c.setClipFps(fps));
    }

    public AnimationInfo setClipDuration(float duration) {
        return runAndDescribe(c -> c.setClipDuration(duration));
    }

    public AnimationInfo setClipLoop(boolean loop) {
        return runAndDescribe(c -> c.setClipLoop(loop));
    }

    // ===================== Mutate: layer metadata =====================

    /** Set the clip's layer type: "BASE" or "OVERLAY". */
    public AnimationInfo setLayerType(String type) {
        return runAndDescribe(c -> c.setLayerType(
                com.openmason.engine.format.oma.AnimLayerMeta.LayerType.fromString(type)));
    }

    /**
     * Replace the overlay's part mask. Entries are part NAMES (ids are
     * accepted and resolved to names when possible); empty/null = all parts.
     */
    public AnimationInfo setLayerMask(List<String> maskParts) {
        return runAndDescribe(c -> {
            List<String> names = new ArrayList<>();
            if (maskParts != null) {
                var joints = c.joints();
                for (String entry : maskParts) {
                    if (entry == null || entry.isBlank()) continue;
                    String jointName = joints.nameOf(entry); // an id (part or bone) → its name
                    names.add(jointName != null ? jointName : entry);
                }
            }
            c.setMaskParts(names);
        });
    }

    /** Set the overlay fade durations; pass null to leave a value unchanged. */
    public AnimationInfo setLayerFades(Float fadeInSeconds, Float fadeOutSeconds) {
        return runAndDescribe(c -> {
            if (fadeInSeconds != null) c.setLayerFadeIn(fadeInSeconds);
            if (fadeOutSeconds != null) c.setLayerFadeOut(fadeOutSeconds);
        });
    }

    public AnimationInfo setLayerPriority(int priority) {
        return runAndDescribe(c -> c.setLayerPriority(priority));
    }

    // ===================== Mutate: transport =====================

    public AnimationInfo setPlayhead(float seconds) {
        return runAndDescribe(c -> {
            c.state().setPlayhead(seconds);
            c.applyCurrentPose();
        });
    }

    public AnimationInfo play() {
        return runAndDescribe(c -> c.state().setPlaying(true));
    }

    public AnimationInfo pause() {
        return runAndDescribe(c -> c.state().setPlaying(false));
    }

    public AnimationInfo stop() {
        return runAndDescribe(c -> {
            c.state().setPlaying(false);
            c.state().setPlayhead(0f);
            c.applyCurrentPose();
        });
    }

    public AnimationInfo applyPoseAtPlayhead() {
        return runAndDescribe(AnimationEditorController::applyCurrentPose);
    }

    // ===================== Mutate: keyframes =====================

    public Optional<KeyframeView> insertKeyframeAtPlayhead(String partIdOrName) {
        return await(MainThreadExecutor.submit(() -> {
            AnimationEditorController c = requireController();
            String partId = resolvePartId(c, partIdOrName);
            if (partId == null) return Optional.<KeyframeView>empty();
            c.insertKeyframeAtPlayhead(partId);
            Track track = c.state().clip().trackFor(partId);
            if (track == null || track.isEmpty()) return Optional.<KeyframeView>empty();
            int idx = indexAtTime(track, c.state().playhead());
            return idx >= 0 ? Optional.of(KeyframeView.from(idx, track.get(idx))) : Optional.<KeyframeView>empty();
        }));
    }

    public Optional<KeyframeView> insertKeyframe(String partIdOrName, float time,
                                                  Vector3f position, Vector3f rotation, Vector3f scale,
                                                  String easingName) {
        return await(MainThreadExecutor.submit(() -> {
            AnimationEditorController c = requireController();
            String partId = resolvePartId(c, partIdOrName);
            if (partId == null) return Optional.<KeyframeView>empty();
            ModelPartManager pm = c.partManager();
            Vector3f pos = position;
            Vector3f rot = rotation;
            Vector3f scl = scale;
            if ((pos == null || rot == null || scl == null) && pm != null) {
                Optional<ModelPartDescriptor> p = pm.getPartById(partId);
                if (p.isPresent()) {
                    if (pos == null) pos = new Vector3f(p.get().transform().position());
                    if (rot == null) rot = new Vector3f(p.get().transform().rotation());
                    if (scl == null) scl = new Vector3f(p.get().transform().scale());
                } else if (c.boneStore() != null && c.boneStore().getById(partId) != null) {
                    // A bone: its current (posed or rest) local pose; bones have unit scale.
                    if (pos == null) pos = c.boneStore().getLocalPosition(partId);
                    if (rot == null) rot = c.boneStore().getLocalRotation(partId);
                    if (scl == null) scl = new Vector3f(1, 1, 1);
                }
            }
            if (pos == null) pos = new Vector3f();
            if (rot == null) rot = new Vector3f();
            if (scl == null) scl = new Vector3f(1, 1, 1);
            Easing easing = Easing.fromString(easingName);
            Keyframe kf = new Keyframe(time, pos, rot, scl, easing);
            c.insertKeyframe(partId, kf);
            Track refreshed = c.state().clip().trackFor(partId);
            if (refreshed == null || refreshed.isEmpty()) return Optional.<KeyframeView>empty();
            int idx = indexAtTime(refreshed, time);
            if (idx < 0) idx = refreshed.size() - 1;
            return Optional.of(KeyframeView.from(idx, refreshed.get(idx)));
        }));
    }

    public Optional<KeyframeView> editKeyframe(String partIdOrName, int index,
                                                Float time,
                                                Vector3f position, Vector3f rotation, Vector3f scale,
                                                String easingName) {
        return await(MainThreadExecutor.submit(() -> {
            AnimationEditorController c = requireController();
            String partId = resolvePartId(c, partIdOrName);
            if (partId == null) return Optional.<KeyframeView>empty();
            Track track = c.state().clip().trackFor(partId);
            if (track == null || index < 0 || index >= track.size()) return Optional.<KeyframeView>empty();
            Keyframe current = track.get(index);
            float newTime = time != null ? time : current.time();
            Vector3f newPos = position != null ? position : new Vector3f(current.position());
            Vector3f newRot = rotation != null ? rotation : new Vector3f(current.rotation());
            Vector3f newScale = scale != null ? scale : new Vector3f(current.scale());
            Easing easing = easingName != null ? Easing.fromString(easingName) : current.easing();
            Keyframe updated = new Keyframe(newTime, newPos, newRot, newScale, easing);
            c.editKeyframe(partId, index, updated);
            Track refreshed = c.state().clip().trackFor(partId);
            if (refreshed == null) return Optional.<KeyframeView>empty();
            int newIndex = indexAtTime(refreshed, newTime);
            if (newIndex < 0) newIndex = Math.min(index, refreshed.size() - 1);
            return Optional.of(KeyframeView.from(newIndex, refreshed.get(newIndex)));
        }));
    }

    public boolean deleteKeyframe(String partIdOrName, int index) {
        return await(MainThreadExecutor.submit(() -> {
            AnimationEditorController c = requireController();
            String partId = resolvePartId(c, partIdOrName);
            if (partId == null) return false;
            Track track = c.state().clip().trackFor(partId);
            if (track == null || index < 0 || index >= track.size()) return false;
            c.deleteKeyframe(partId, index);
            return true;
        }));
    }

    public boolean deleteTrack(String partIdOrName) {
        return await(MainThreadExecutor.submit(() -> {
            AnimationEditorController c = requireController();
            String partId = resolvePartId(c, partIdOrName);
            if (partId == null) return false;
            return c.deleteTrack(partId);
        }));
    }

    // ===================== Undo / Redo =====================

    public boolean undo() {
        return await(MainThreadExecutor.submit(() -> requireController().undo()));
    }

    public boolean redo() {
        return await(MainThreadExecutor.submit(() -> requireController().redo()));
    }

    // ===================== File I/O =====================

    public AnimationInfo newClip() {
        return runAndDescribe(AnimationEditorController::newClip);
    }

    public boolean save() {
        return await(MainThreadExecutor.submit(() -> requireController().save()));
    }

    /** The clip's own file path, or null when it has never been saved. */
    public String currentFilePath() {
        return await(MainThreadExecutor.submit(() -> requireController().state().filePath()));
    }

    public boolean saveAs(String filePath) {
        return await(MainThreadExecutor.submit(() -> requireController().saveAs(filePath)));
    }

    public boolean load(String filePath) {
        return await(MainThreadExecutor.submit(() -> requireController().load(filePath)));
    }

    // ===================== Helpers =====================

    private AnimationEditorController optController() {
        AnimationEditorImGui editor = editorServices.getAnimationEditor();
        return editor != null ? editor.getController() : null;
    }

    /** The error every model-dependent tool gives while the editor is not bound to a model. */
    static final String NOT_BOUND = "The animation editor is not open on the model, so its parts and bones "
            + "cannot be resolved; call anim_open_editor first";

    private AnimationEditorController requireController() {
        AnimationEditorController c = optController();
        if (c == null) throw new IllegalStateException("Animation editor not initialized");
        return c;
    }

    /**
     * A track key from a joint id or name: a part or a bone of the bound model (id, then exact name,
     * then case-insensitive name — the runtime binding order), else an existing track's key (orphans).
     */
    static String resolvePartId(AnimationEditorController c, String partIdOrName) {
        if (partIdOrName == null || partIdOrName.isBlank()) return null;
        if (c.partManager() == null && !c.state().clip().tracks().containsKey(partIdOrName)) {
            // Not bound to a model: nothing can resolve. Say so instead of a silent null.
            throw new IllegalStateException(NOT_BOUND);
        }
        String joint = c.partManager() != null ? c.joints().resolve(partIdOrName) : null;
        if (joint != null) return joint;
        return c.state().clip().tracks().containsKey(partIdOrName) ? partIdOrName : null;
    }

    private static int indexAtTime(Track track, float time) {
        for (int i = 0; i < track.size(); i++) {
            if (Math.abs(track.get(i).time() - time) < 1e-4f) return i;
        }
        return -1;
    }

    private AnimationInfo runAndDescribe(java.util.function.Consumer<AnimationEditorController> action) {
        return await(MainThreadExecutor.submit(() -> {
            AnimationEditorController c = requireController();
            action.accept(c);
            return describeOnThread(c);
        }));
    }

    private AnimationInfo describeOnThread(AnimationEditorController c) {
        AnimationEditorState s = c.state();
        AnimationClip clip = s.clip();
        return new AnimationInfo(
                true,
                clip.name(), clip.fps(), clip.duration(), clip.loop(), clip.modelRef(),
                s.filePath(),
                clip.tracks().size(),
                s.dirty(), s.playing(), s.playhead(),
                LayerInfo.from(clip));
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("Operation timed out on main thread", e);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        }
    }

    // ===================== DTOs =====================

    public record AnimationInfo(
            boolean available,
            String name, float fps, float duration, boolean loop, String modelRef,
            String filePath,
            int trackCount,
            boolean dirty, boolean playing, float playhead,
            LayerInfo layer
    ) {}

    /** Layering metadata (format v1.1): BASE/OVERLAY, part-name mask, fades, priority. */
    public record LayerInfo(String type, List<String> maskParts,
                            float fadeInSeconds, float fadeOutSeconds, int priority) {
        static LayerInfo from(AnimationClip clip) {
            return new LayerInfo(clip.layerType().name(), List.copyOf(clip.maskParts()),
                    clip.fadeInSeconds(), clip.fadeOutSeconds(), clip.layerPriority());
        }
    }

    /** Serializes as a compact {@code [x, y, z]} array to keep MCP responses small. */
    public record Vec3(float x, float y, float z) {
        public static Vec3 from(Vector3f v) { return new Vec3(v.x, v.y, v.z); }

        @JsonValue
        public float[] xyz() {
            return new float[] {x, y, z};
        }
    }

    public record TrackView(String partId, String partName, int keyframeCount) {}

    public record KeyframeView(int index, float time, Vec3 position, Vec3 rotation, Vec3 scale, String easing) {
        public static KeyframeView from(int index, Keyframe kf) {
            return new KeyframeView(
                    index, kf.time(),
                    Vec3.from(kf.position()), Vec3.from(kf.rotation()), Vec3.from(kf.scale()),
                    kf.easing() != null ? kf.easing().name() : Easing.LINEAR.name());
        }
    }

}
