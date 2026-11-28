package com.openmason.main.systems.animation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.engine.anim.AnimClipDocument;
import com.openmason.engine.anim.AnimationAuthoring;
import com.openmason.engine.rig.JointPalette;
import com.openmason.engine.rig.RigPoser;
import com.openmason.main.systems.character.CharacterDocument;
import org.joml.Matrix4f;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * One open animation (OpenMason #165): a clip being authored for a model - the native {@link AnimationAuthoring}
 * session (layers, control rig, notifies / curves / markers, root motion, additive; one undo history shared by the
 * Animation tab and the {@code anim_*} MCP tools) and the model it animates, loaded through #163's
 * {@link CharacterDocument} (an .omo, any model container a plugin reads, or an .omchar for role-addressed clips).
 * Playback state (time, playing, loop view) lives here too. GL- and ImGui-free; main thread only.
 */
public final class AnimationSession implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final CharacterDocument model;
    private final AnimationAuthoring authoring;
    private Path file;
    private final RigPoser poser = new RigPoser();
    private final JointPalette palette = new JointPalette();
    private float[] locals;

    // Playback / editing state.
    private float time;
    private boolean playing;
    private int stage = AnimationAuthoring.STAGE_FINAL;
    private boolean inPlace;
    private long viewRevision = Long.MIN_VALUE;
    private com.openmason.engine.character.CharacterOverlay overlay;
    private long overlayRevision = Long.MIN_VALUE;
    private float overlayTime = Float.NaN;
    private int overlayStage = -1;
    private JsonNode view = JSON.createObjectNode();

    private AnimationSession(CharacterDocument model, AnimationAuthoring authoring, Path file) {
        this.model = model;
        this.authoring = authoring;
        this.file = file;
    }

    /** A new clip for a model / character file. */
    public static AnimationSession create(Path modelFile, String name, float fps, float duration) throws IOException {
        CharacterDocument m = loadModel(modelFile, null);
        try (AnimClipDocument clip = AnimClipDocument.create()) {
            AnimationAuthoring a = AnimationAuthoring.create(clip, m.rig(), characterOf(m));
            a.apply(String.format(java.util.Locale.ROOT, "[{\"op\":\"header\",\"name\":%s,\"fps\":%s,\"duration\":%s}]",
                    JSON.writeValueAsString(name == null || name.isBlank() ? "New Clip" : name), fps, duration), "new clip", null);
            a.markSaved();
            return new AnimationSession(m, a, null);
        } catch (IOException | RuntimeException e) {
            m.close();
            throw e;
        }
    }

    /** Opens an .omanim for a model / character file (the clip's modelRef when {@code modelFile} is null). */
    public static AnimationSession open(Path clipFile, Path modelFile, Path projectRoot) throws IOException {
        byte[] bytes = Files.readAllBytes(clipFile);
        Path model = modelFile;
        try (AnimClipDocument clip = AnimClipDocument.read(bytes)) {
            if (model == null) {
                String ref = clip.modelRef().orElse(null);
                if (ref == null || ref.isBlank()) {
                    throw new IOException(clipFile.getFileName() + " names no model (modelRef): choose the model to animate");
                }
                model = resolve(ref, clipFile, projectRoot);
            }
            CharacterDocument m = loadModel(model, projectRoot);
            try {
                return new AnimationSession(m, AnimationAuthoring.create(clip, m.rig(), characterOf(m)), clipFile);
            } catch (RuntimeException e) {
                m.close();
                throw e;
            }
        }
    }

    private static Path resolve(String ref, Path clip, Path projectRoot) throws IOException {
        Path p = Path.of(ref);
        if (p.isAbsolute() && Files.isRegularFile(p)) return p;
        Path parent = clip.toAbsolutePath().getParent();
        if (!p.isAbsolute() && parent != null && Files.isRegularFile(parent.resolve(p))) return parent.resolve(p).normalize();
        if (!p.isAbsolute() && projectRoot != null && Files.isRegularFile(projectRoot.resolve(p))) return projectRoot.resolve(p).normalize();
        // A model id ("SB_Goblin"): a file of that stem under the project.
        if (projectRoot != null && Files.isDirectory(projectRoot)) {
            try (var walk = Files.walk(projectRoot, 6)) {
                Optional<Path> hit = walk.filter(Files::isRegularFile)
                        .filter(f -> stem(f).equals(ref) && !f.toString().endsWith(".omanim")).findFirst();
                if (hit.isPresent()) return hit.get();
            }
        }
        throw new IOException("model '" + ref + "' of " + clip.getFileName() + " not found: choose the model to animate");
    }

    private static String stem(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    private static CharacterDocument loadModel(Path file, Path projectRoot) throws IOException {
        return file.getFileName().toString().endsWith(".omchar") ? CharacterDocument.open(file, projectRoot)
                : CharacterDocument.fromModel(file);
    }

    /** The character definition when the model is an .omchar (role-addressed tracks bind through it). */
    private static com.openmason.engine.character.CharacterDefinition characterOf(CharacterDocument m) {
        return m.file().map(f -> f.toString().endsWith(".omchar")).orElse(false) ? m.definition() : null;
    }

    // ------------------------------------------------------------------------------------------------

    public AnimationAuthoring authoring() {
        return authoring;
    }

    public CharacterDocument model() {
        return model;
    }

    public Optional<Path> file() {
        return Optional.ofNullable(file);
    }

    public boolean isDirty() {
        return authoring.dirty();
    }

    /** The authoring view (header, layers + tracks + key times, effectors, notifies, curves, markers...), per revision. */
    public JsonNode view() {
        long rev = authoring.revision();
        if (rev != viewRevision) {
            try {
                view = JSON.readTree(authoring.view());
            } catch (IOException e) {
                view = JSON.createObjectNode();
            }
            viewRevision = rev;
        }
        return view;
    }

    public String displayName() {
        String n = view().path("name").asText("");
        return n.isBlank() ? (file == null ? "clip" : stem(file)) : n;
    }

    public float duration() {
        return (float) view().path("duration").asDouble(1);
    }

    public float fps() {
        return (float) view().path("fps").asDouble(30);
    }

    public String apply(String ops, String label, String mergeKey) {
        return authoring.apply(ops, label, mergeKey);
    }

    public boolean undo() {
        return authoring.undo();
    }

    public boolean redo() {
        return authoring.redo();
    }

    /** Writes the .omanim (authoring data included when needed); the model is referenced by path. */
    public void save(Path path) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.write(path, bytesFor(path));
        saved(path);
    }

    /** The bytes {@link #save} writes; the model reference is set (relative when close) first. */
    public byte[] bytesFor(Path target) {
        Path modelFile = model.file().filter(f -> f.toString().endsWith(".omchar")).or(model::modelFile).orElse(null);
        if (modelFile != null) {
            String ref = modelFile.toAbsolutePath().normalize().toString();
            Path dir = target.toAbsolutePath().getParent();
            if (dir != null) {
                Path rel = dir.normalize().relativize(modelFile.toAbsolutePath().normalize());
                int up = 0;
                for (Path part : rel) if (part.toString().equals("..")) up++;
                if (up <= 2) ref = rel.toString().replace('\\', '/');
            }
            String current = view().path("modelRef").asText(null);
            if (!ref.equals(current)) {
                try {
                    authoring.apply("[{\"op\":\"header\",\"modelRef\":" + JSON.writeValueAsString(ref) + "}]", "model reference", null);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        return authoring.write();
    }

    /** Forgets the file (a clip handed over by another editor: Save asks where). */
    public void detachFile() {
        file = null;
    }

    public void saved(Path path) {
        authoring.markSaved();
        file = path;
    }

    // ------------------------------------------------------------------------------------------------
    // Playback

    public float time() {
        return time;
    }

    public void setTime(float t) {
        float d = duration();
        time = d > 0 ? Math.max(0f, Math.min(d, t)) : 0f;
    }

    public boolean playing() {
        return playing;
    }

    public void setPlaying(boolean playing) {
        this.playing = playing;
    }

    /** LAYERS (FK only) or FINAL (with the control rig, what is baked). */
    public int stage() {
        return stage;
    }

    public void setStage(int stage) {
        this.stage = stage;
    }

    /** Root motion preview: true shows the clip in place (the root-motion joint's travel removed). */
    public boolean inPlace() {
        return inPlace;
    }

    public void setInPlace(boolean inPlace) {
        this.inPlace = inPlace;
    }

    /** The model matrix for the preview: the root motion travel subtracted when {@link #inPlace}. */
    public Matrix4f baseMatrix(float t) {
        Matrix4f m = new Matrix4f();
        if (!inPlace) return m;
        float[] d = authoring.rootMotion(t);
        return m.rotateY((float) Math.toRadians(-d[3])).translate(-d[0], -d[1], -d[2]);
    }

    /**
     * Joints, chains and picking in the CURRENT pose (#165 gap: picking must not use the rest pose while a clip
     * plays): the native character overlay over the posed locals, rebuilt when time, stage or the clip change.
     */
    public com.openmason.engine.character.CharacterOverlay overlay() {
        long rev = authoring.revision();
        if (overlay == null || rev != overlayRevision || time != overlayTime || stage != overlayStage) {
            float[] l = authoring.pose(time, stage, null);
            com.openmason.engine.character.CharacterProfile profile;
            try {
                profile = model.profile();
            } catch (RuntimeException e) {
                profile = null;
            }
            var next = com.openmason.engine.character.CharacterOverlay.build(model.definition(), model.rig(), profile, l,
                    model.boundsMin(), model.boundsMax());
            if (overlay != null) overlay.close();
            overlay = next;
            overlayRevision = rev;
            overlayTime = time;
            overlayStage = stage;
        }
        return overlay;
    }

    /** Advances playback (wrapping at the end) and returns the posed palette. */
    public JointPalette tick(float dt) {
        if (playing) {
            float d = duration();
            time = d > 0 ? (time + dt) % d : 0f;
        }
        return poseAt(time);
    }

    /** The palette of the pose at t (the stage chosen). */
    public JointPalette poseAt(float t) {
        locals = authoring.pose(t, stage, locals);
        return poser.poseLocals(model.rig(), locals, baseMatrix(t), palette);
    }

    public float[] locals() {
        return locals;
    }

    /**
     * The last posed locals ({@link #poseAt}) posed again under {@code base} into {@code out} (the Eyes preview's
     * first-person pose); null before the first pose.
     */
    public JointPalette poseWith(org.joml.Matrix4fc base, JointPalette out) {
        if (locals == null) return null;
        return poser.poseLocals(model.rig(), locals, new Matrix4f(base), out);
    }

    // ------------------------------------------------------------------------------------------------
    // Shape keys (OpenMason #171): the clip's morph tracks over the model's shape keys

    private com.openmason.engine.anim.AnimClipDocument morphClip;
    private long morphClipRevision = Long.MIN_VALUE;
    private java.util.List<String> morphTrackNames = java.util.List.of();
    private float[] morphWeights;

    /** The model's shape keys (its mesh's morph targets, in target order); empty when it has none. */
    public java.util.List<com.openmason.engine.rig.RigMorphs.Target> shapeKeys() {
        com.openmason.engine.rig.RigMorphs m = model.geometry().morphs();
        return m == null ? java.util.List.of() : m.targets();
    }

    /**
     * The shape-key weights of the BAKED clip at t (every layer composed; what the runtime plays), one per
     * {@link #shapeKeys()} target: its morph track's value, else the key's default. Null when the model has no shape
     * keys. Marshalling only: the playable clip is copied once per edit and sampled natively.
     */
    public float[] morphWeights(float t) {
        java.util.List<com.openmason.engine.rig.RigMorphs.Target> keys = shapeKeys();
        if (keys.isEmpty()) return null;
        long rev = authoring.revision();
        if (morphClip == null || rev != morphClipRevision) {
            if (morphClip != null) morphClip.close();
            morphClip = authoring.playableClip();
            morphClipRevision = rev;
            morphTrackNames = morphClip.morphTracks().stream().map(com.openmason.engine.anim.AnimClipDocument.Curve::name)
                    .toList();
        }
        if (morphWeights == null || morphWeights.length != keys.size()) morphWeights = new float[keys.size()];
        for (int i = 0; i < keys.size(); i++) {
            int track = morphTrack(keys.get(i).name());
            morphWeights[i] = track >= 0 ? morphClip.sampleMorph(track, t) : keys.get(i).defaultWeight();
        }
        return morphWeights;
    }

    private int morphTrack(String name) {
        for (int i = 0; i < morphTrackNames.size(); i++) if (morphTrackNames.get(i).equals(name)) return i;
        for (int i = 0; i < morphTrackNames.size(); i++) if (morphTrackNames.get(i).equalsIgnoreCase(name)) return i;
        return -1;
    }

    @Override
    public void close() {
        if (morphClip != null) morphClip.close();
        morphClip = null;
        if (overlay != null) overlay.close();
        overlay = null;
        authoring.close();
        model.close();
    }
}
