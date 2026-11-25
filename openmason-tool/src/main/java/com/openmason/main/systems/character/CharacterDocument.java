package com.openmason.main.systems.character;

import com.openmason.engine.character.CharacterDefinition;
import com.openmason.engine.character.CharacterEditSession;
import com.openmason.engine.character.CharacterFormatException;
import com.openmason.engine.character.CharacterOverlay;
import com.openmason.engine.character.CharacterProfile;
import com.openmason.engine.entity.model.EntityAsset;
import com.openmason.engine.entity.model.EntityGeometry;
import com.openmason.engine.entity.model.EntityModelLoader;
import com.openmason.engine.rig.Rig;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * One open character (OpenMason #163): an {@code .omchar} definition, its native undo history
 * ({@link CharacterEditSession}), the model it characterizes (any format the engine model loader reads: .omo, or a
 * model container a game plugin reads) and the profile it maps onto. GL- and ImGui-free:
 * the Character tab and the {@code char_*} MCP tools both edit through this object, so they share one history.
 *
 * <p>Thin by design (Java feature freeze): every edit, check and measurement is a native call; this class only
 * loads files, finds the model and profile, and caches what the native side computed per revision.
 * Main thread only.
 */
public final class CharacterDocument implements AutoCloseable {

    /** The profile used when the definition names none (or one that cannot be found). */
    public static final String DEFAULT_PROFILE = "humanoid";

    private final CharacterDefinition definition;
    private final CharacterEditSession session;
    private final EntityGeometry geometry;
    private final Rig rig;
    private final Path modelFile;
    private final float[] boundsMin = new float[3];
    private final float[] boundsMax = new float[3];
    private Path file;
    private CharacterProfile profile;
    private String profileProblem;

    // Per-revision caches.
    private long cachedRevision = Long.MIN_VALUE;
    private int[] roleStates;
    private List<CharacterDefinition.Issue> issues;
    private CharacterOverlay overlay;

    private CharacterDocument(CharacterDefinition definition, EntityGeometry geometry, Path modelFile, Path file) {
        this.definition = definition;
        this.geometry = geometry;
        this.rig = geometry.rig().rig();
        this.modelFile = modelFile;
        this.file = file;
        computeBounds();
        resolveProfile();
        this.session = CharacterEditSession.create(definition, 0);
    }

    /**
     * A new character for a model file: the definition records the model's joint set, references the model by path
     * and is named after it; nothing is mapped yet. Not dirty (like a fresh document) until edited.
     */
    public static CharacterDocument fromModel(Path model) throws IOException {
        EntityGeometry geometry = loadGeometry(model);
        CharacterDefinition def = CharacterDefinition.create();
        try {
            def.setName(stem(model));
            def.setModelRef(model.toAbsolutePath().normalize().toString());
            def.setProfile(DEFAULT_PROFILE);
            def.setSkeleton(geometry.rig().rig());
            return new CharacterDocument(def, geometry, model, null);
        } catch (RuntimeException e) {
            def.close();
            throw e;
        }
    }

    /**
     * Opens an {@code .omchar}: its embedded model, else its model reference (absolute, relative to the file, or
     * relative to {@code projectRoot} when given).
     */
    public static CharacterDocument open(Path omchar, Path projectRoot) throws IOException {
        CharacterDefinition def;
        try {
            def = CharacterDefinition.read(omchar);
        } catch (CharacterFormatException e) {
            throw new IOException(omchar.getFileName() + ": " + e.getMessage(), e);
        }
        try {
            Optional<byte[]> embedded = def.embeddedModel();
            EntityGeometry geometry;
            Path modelFile = null;
            if (embedded.isPresent()) {
                geometry = geometryOf(EntityModelLoader.build(EntityModelLoader.importSource(
                        omchar.getFileName() + "!model.omo", new ByteArrayInputStream(embedded.get()))), omchar);
            } else {
                modelFile = resolveModel(def.modelRef(), omchar, projectRoot);
                geometry = loadGeometry(modelFile);
            }
            return new CharacterDocument(def, geometry, modelFile, omchar);
        } catch (IOException | RuntimeException e) {
            def.close();
            throw e;
        }
    }

    /** The model a reference names: absolute, beside the .omchar, or under the project root. */
    static Path resolveModel(String ref, Path omchar, Path projectRoot) throws IOException {
        if (ref == null || ref.isBlank()) {
            throw new IOException(omchar.getFileName() + " has no embedded model and no model reference");
        }
        Path p = Path.of(ref);
        if (p.isAbsolute() && Files.isRegularFile(p)) {
            return p;
        }
        Path parent = omchar.toAbsolutePath().getParent();
        if (!p.isAbsolute() && parent != null && Files.isRegularFile(parent.resolve(p))) {
            return parent.resolve(p).normalize();
        }
        if (!p.isAbsolute() && projectRoot != null && Files.isRegularFile(projectRoot.resolve(p))) {
            return projectRoot.resolve(p).normalize();
        }
        throw new IOException("model '" + ref + "' of " + omchar.getFileName() + " not found");
    }

    private static EntityGeometry loadGeometry(Path model) throws IOException {
        if (!Files.isRegularFile(model)) {
            throw new IOException("no such model: " + model);
        }
        if (!EntityModelLoader.canLoad(model.getFileName().toString())) {
            throw new IOException("cannot read " + model.getFileName() + " (readable: "
                    + String.join(", ", EntityModelLoader.loadableExtensions()) + ")");
        }
        return geometryOf(EntityModelLoader.build(EntityModelLoader.importSource(model)), model);
    }

    private static EntityGeometry geometryOf(EntityAsset asset, Path from) throws IOException {
        EntityGeometry g = asset.geometryFor(EntityAsset.DEFAULT_VARIANT);
        if (g == null || g.rig() == null) {
            throw new IOException(from.getFileName() + " has no model geometry");
        }
        return g;
    }

    private static String stem(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    // ------------------------------------------------------------------------------------------------

    public CharacterDefinition definition() {
        return definition;
    }

    public CharacterEditSession session() {
        return session;
    }

    public Rig rig() {
        return rig;
    }

    public EntityGeometry geometry() {
        return geometry;
    }

    public Optional<Path> file() {
        return Optional.ofNullable(file);
    }

    public Optional<Path> modelFile() {
        return Optional.ofNullable(modelFile);
    }

    public CharacterProfile profile() {
        return profile;
    }

    /** Why the definition's profile could not be used (the default stands in), or null. */
    public String profileProblem() {
        return profileProblem;
    }

    public boolean isDirty() {
        return session.dirty();
    }

    public String displayName() {
        String n = definition.name();
        return n == null || n.isBlank() ? (file != null ? stem(file) : "character") : n;
    }

    /** Rest mesh bounds (model space). */
    public float[] boundsMin() {
        return boundsMin.clone();
    }

    public float[] boundsMax() {
        return boundsMax.clone();
    }

    /** Runs edits as one undo step (cancelled when they throw); refreshes the profile when it changed. */
    public void edit(String label, String mergeKey, Runnable edit) {
        String before = definition.profile();
        session.edit(label, mergeKey, edit);
        if (!java.util.Objects.equals(before, definition.profile())) {
            resolveProfile();
        }
    }

    public boolean undo() {
        boolean done = session.undo();
        if (done) resolveProfile();
        return done;
    }

    public boolean redo() {
        boolean done = session.redo();
        if (done) resolveProfile();
        return done;
    }

    /** Saves to {@code path}; a path model reference beside the file is stored relative to it. */
    public void save(Path path) throws IOException {
        relativizeModelRef(path);
        byte[] bytes = definition.write();
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(path, bytes);
        session.markSaved();
        file = path;
    }

    /** The bytes {@link #save} writes (for sandboxed writers); call {@link #saved(Path)} after writing them. */
    public byte[] bytesFor(Path path) {
        relativizeModelRef(path);
        return definition.write();
    }

    public void saved(Path path) {
        session.markSaved();
        file = path;
    }

    private void relativizeModelRef(Path target) {
        if (modelFile == null || definition.embeddedModel().isPresent()) {
            return;
        }
        Path dir = target.toAbsolutePath().getParent();
        Path model = modelFile.toAbsolutePath().normalize();
        String ref = model.toString();
        if (dir != null && model.getRoot() != null && model.getRoot().equals(dir.normalize().getRoot())) {
            // Relative while the two stay close (a project's Characters/ and Models/ folders): the pair can move together.
            Path rel = dir.normalize().relativize(model);
            int up = 0;
            for (Path part : rel) {
                if (part.toString().equals("..")) up++;
            }
            if (up <= 2) {
                ref = rel.toString().replace('\\', '/');
            }
        }
        if (!ref.equals(definition.modelRef())) {
            String r = ref;
            session.edit("model reference", null, () -> definition.setModelRef(r));
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Derived state (native results cached per revision)

    private void refresh() {
        long rev = session.revision();
        if (rev == cachedRevision) {
            return;
        }
        cachedRevision = rev;
        roleStates = definition.roleStates(rig, profile);
        issues = definition.validate(rig, profile);
        if (overlay != null) {
            overlay.close();
        }
        overlay = CharacterOverlay.build(definition, rig, profile, null, boundsMin, boundsMax);
    }

    /** {@link CharacterOverlay}{@code .ROLE_*} bits per profile role. */
    public int[] roleStates() {
        refresh();
        return roleStates;
    }

    /** Validation against the rig and the profile. */
    public List<CharacterDefinition.Issue> issues() {
        refresh();
        return issues;
    }

    /** The reference-pose overlay (owned by the document; valid until the next edit). */
    public CharacterOverlay overlay() {
        refresh();
        return overlay;
    }

    /** A joint's point (bone head / part pivot) in the REST pose, model space (where sockets are authored). */
    public float[] restPoint(int joint) {
        List<com.openmason.engine.rig.RigJoint> joints = rig.joints();
        float[] locals = new float[joints.size() * 9];
        for (int i = 0; i < joints.size(); i++) {
            var j = joints.get(i);
            locals[i * 9] = j.pos().x();
            locals[i * 9 + 1] = j.pos().y();
            locals[i * 9 + 2] = j.pos().z();
            locals[i * 9 + 3] = j.rotDeg().x();
            locals[i * 9 + 4] = j.rotDeg().y();
            locals[i * 9 + 5] = j.rotDeg().z();
            locals[i * 9 + 6] = j.scale().x();
            locals[i * 9 + 7] = j.scale().y();
            locals[i * 9 + 8] = j.scale().z();
        }
        try (CharacterOverlay rest = CharacterOverlay.build(definition, rig, null, locals, null, null)) {
            float[] p = rest.points();
            return new float[] {p[joint * 3], p[joint * 3 + 1], p[joint * 3 + 2]};
        }
    }

    /** Forces the caches to rebuild (profile replaced without an edit). */
    void invalidate() {
        cachedRevision = Long.MIN_VALUE;
    }

    private void computeBounds() {
        float[] v = geometry.vertices();
        if (v.length < 3) {
            return;
        }
        for (int k = 0; k < 3; k++) {
            boundsMin[k] = Float.POSITIVE_INFINITY;
            boundsMax[k] = Float.NEGATIVE_INFINITY;
        }
        for (int i = 0; i + 2 < v.length; i += 3) {
            for (int k = 0; k < 3; k++) {
                boundsMin[k] = Math.min(boundsMin[k], v[i + k]);
                boundsMax[k] = Math.max(boundsMax[k], v[i + k]);
            }
        }
    }

    /** Re-resolves the profile after {@code definition().setProfile} inside an edit (the edit does it at its end). */
    public void syncProfile() {
        resolveProfile();
    }

    /** The definition's profile: a built-in id, else an .omprofile named like it beside the file. */
    private void resolveProfile() {
        String id = definition.profile();
        if (id == null || id.isBlank()) {
            id = DEFAULT_PROFILE;
        }
        if (profile != null && id.equals(profileId(profile))) {
            return;
        }
        CharacterProfile next = null;
        profileProblem = null;
        if (CharacterProfile.builtinIds().contains(id)) {
            next = CharacterProfile.builtin(id);
        } else {
            Path dir = file != null ? file.toAbsolutePath().getParent() : null;
            Path candidate = dir == null ? null : dir.resolve(id + ".omprofile");
            if (candidate != null && Files.isRegularFile(candidate)) {
                try {
                    next = CharacterProfile.read(candidate);
                } catch (IOException | RuntimeException e) {
                    profileProblem = "profile " + id + ": " + e.getMessage();
                }
            } else {
                profileProblem = "profile " + id + " is not built in and no " + id + ".omprofile is beside the file";
            }
            if (next == null) {
                next = CharacterProfile.builtin(DEFAULT_PROFILE);
            }
        }
        if (profile != null) {
            profile.close();
        }
        profile = next;
        invalidate();
    }

    private static String profileId(CharacterProfile p) {
        return p.id();
    }

    @Override
    public void close() {
        if (overlay != null) {
            overlay.close();
            overlay = null;
        }
        session.close();
        definition.close();
        if (profile != null) {
            profile.close();
        }
    }
}
