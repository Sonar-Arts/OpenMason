package com.openmason.main.systems.animation.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.engine.anim.AnimClipDocument;
import com.openmason.engine.anim.AnimationAuthoring;
import com.openmason.engine.character.Retargeter;
import com.openmason.engine.rig.JointPalette;
import com.openmason.engine.rig.Rig;
import com.openmason.main.systems.animation.AnimationSession;
import com.openmason.main.systems.animation.AnimationSessions;
import com.openmason.main.systems.character.CharacterDocument;
import com.openmason.main.systems.io.AssetWriteService;
import com.openmason.main.systems.io.AssetWriteService.WriteOutcome;
import com.openmason.main.systems.io.BuiltinFileTypes;
import com.openmason.main.systems.io.WriteKind;
import com.openmason.main.systems.mcp.McpAck;
import com.openmason.main.systems.threading.MainThreadExecutor;
import org.joml.Matrix4f;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * The {@code anim_*} MCP tools' backend (OpenMason #165): every call runs on the main thread against the ONE clip
 * the Animation tab shows ({@link AnimationSessions}); every edit is ONE undo step of the native authoring history
 * (all or nothing). Layers, keys, the control rig, matching, baking and retargeting are native; this class
 * marshals arguments and shapes the replies.
 */
public final class AnimationEditingService {

    static final WriteKind KIND = WriteKind.of(BuiltinFileTypes.OMANIM);
    private static final long TIMEOUT_MS = 30_000;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Supplier<AnimationSessions> sessions;
    private final AssetWriteService writes;

    public AnimationEditingService(Supplier<AnimationSessions> sessions, AssetWriteService writes) {
        this.sessions = sessions;
        this.writes = writes;
    }

    private AnimationSessions sessions() {
        AnimationSessions s = sessions.get();
        if (s == null) throw new IllegalStateException("the Animation workspace is unavailable");
        return s;
    }

    private Path existing(String raw) {
        return writes == null ? Path.of(raw) : writes.sandbox().resolveExisting(raw);
    }

    private static void guard(AnimationSessions s, boolean discard) {
        s.current().ifPresent(d -> {
            if (d.isDirty() && !discard) {
                throw new IllegalStateException("'" + d.displayName() + "' has unsaved changes: anim_save it, or pass discard:true");
            }
        });
    }

    // ------------------------------------------------------------------------------------------------

    /** A new clip for a model / character file. */
    public Object create(String model, String name, Float fps, Float duration, boolean discard) {
        if (model == null) throw new IllegalArgumentException("'model' (.omo, a plugin's model container, or an .omchar) is required");
        Path file = existing(model);
        return onMain(() -> {
            AnimationSessions s = sessions();
            guard(s, discard);
            AnimationSession doc;
            try {
                doc = AnimationSession.create(file, name, fps == null ? 30f : fps, duration == null ? 1f : duration);
            } catch (IOException e) {
                throw new IllegalArgumentException(e.getMessage(), e);
            }
            s.replace(doc);
            return info(doc);
        });
    }

    /** Opens an .omanim on its modelRef or on {@code model}. */
    public Object open(String path, String model, boolean discard) {
        if (path == null) throw new IllegalArgumentException("'path' (an .omanim) is required");
        Path file = existing(path);
        Path modelFile = model == null ? null : existing(model);
        return onMain(() -> {
            AnimationSessions s = sessions();
            guard(s, discard);
            AnimationSession doc;
            try {
                doc = AnimationSession.open(file, modelFile, s.projectRoot());
            } catch (IOException e) {
                throw new IllegalArgumentException(e.getMessage(), e);
            }
            s.replace(doc);
            return info(doc);
        });
    }

    public Object close(boolean discard) {
        return onMain(() -> {
            AnimationSessions s = sessions();
            if (s.current().isEmpty()) return McpAck.ok().with("closed", false);
            guard(s, discard);
            s.close();
            return McpAck.ok().with("closed", true);
        });
    }

    /** The authoring view: header, runtime layer, layers (tracks + key times), effectors (keys), events, playback. */
    public Object info() {
        return onMain(() -> info(sessions().require()));
    }

    private static Map<String, Object> info(AnimationSession d) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("file", d.file().map(Path::toString).orElse(null));
        out.put("model", d.model().displayName());
        out.put("character", d.model().file().map(f -> f.toString().endsWith(".omchar")).orElse(false));
        out.put("dirty", d.isDirty());
        out.put("time", d.time());
        out.put("playing", d.playing());
        out.put("clip", JSON.convertValue(d.view(), Map.class));
        return out;
    }

    /**
     * The pose at {@code time} (default: the playhead): per joint (all, or the named) the local value on the final
     * pose and its model-space point; plus the control-rig handles, and, with {@code layer}, that layer's own
     * values (what keying it would store).
     */
    public Object pose(Float time, List<String> joints, String layer, boolean fkOnly) {
        return onMain(() -> {
            AnimationSession d = sessions().require();
            float t = time == null ? d.time() : time;
            Rig rig = d.model().rig();
            int stage = fkOnly ? AnimationAuthoring.STAGE_LAYERS : AnimationAuthoring.STAGE_FINAL;
            float[] locals = d.authoring().pose(t, stage, null);
            JointPalette palette = new com.openmason.engine.rig.RigPoser().poseLocals(rig, locals, new Matrix4f(),
                    new JointPalette());
            int layerIndex = -1;
            if (layer != null) {
                JsonNode layers = d.view().path("layers");
                for (int i = 0; i < layers.size(); i++) {
                    if (layers.path(i).path("name").asText().equals(layer) || (layer.equals("base") && i == 0)) layerIndex = i;
                }
                if (layerIndex < 0) throw new IllegalArgumentException("no layer " + layer);
            }
            List<Map<String, Object>> out = new ArrayList<>();
            Matrix4f w = new Matrix4f();
            for (int j = 0; j < rig.jointCount(); j++) {
                var joint = rig.joints().get(j);
                if (joints != null && !joints.contains(joint.id()) && !joints.contains(joint.name())) continue;
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("joint", joint.id());
                o.put("name", joint.name());
                o.put("pos", round(locals, j * 9, 3));
                o.put("rot", round(locals, j * 9 + 3, 3));
                o.put("scale", round(locals, j * 9 + 6, 3));
                palette.worldMatrix(j, w);
                o.put("world", new float[] {r(w.m30()), r(w.m31()), r(w.m32())});
                if (layerIndex >= 0) {
                    float[] v = d.authoring().layerValue(layerIndex, j, t);
                    o.put("layerValue", Map.of("pos", round(v, 0, 3), "rot", round(v, 3, 3), "scale", round(v, 6, 3)));
                }
                out.add(o);
            }
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("time", t);
            res.put("stage", fkOnly ? "layers" : "final");
            res.put("joints", out);
            try {
                res.put("handles", JSON.readValue(d.authoring().handles(t), List.class));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            float[] rm = d.authoring().rootMotion(t);
            if (rm[0] != 0 || rm[1] != 0 || rm[2] != 0 || rm[3] != 0) res.put("rootMotion", rm);
            return res;
        });
    }

    private static float r(float v) {
        return Math.round(v * 1e5f) / 1e5f;
    }

    private static float[] round(float[] v, int off, int n) {
        float[] o = new float[n];
        for (int i = 0; i < n; i++) o[i] = r(v[off + i]);
        return o;
    }

    /** A batch of operations, ONE undo step, all or nothing (cenda omanim_author.hpp lists them). */
    public Object edit(JsonNode ops, String label) {
        if (ops == null || !ops.isArray() || ops.isEmpty()) throw new IllegalArgumentException("'ops' must be a non-empty array");
        return onMain(() -> {
            AnimationSession d = sessions().require();
            String results = d.apply(ops.toString(), label == null || label.isBlank() ? "anim_edit" : label, null);
            d.authoring().seal();
            try {
                return McpAck.ok().with("applied", JSON.readValue(results, List.class)).with("dirty", d.isDirty())
                        .with("steps", d.authoring().stepCount());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    public Object undo(boolean redo) {
        return onMain(() -> {
            AnimationSession d = sessions().require();
            String label = redo ? d.authoring().redoLabel() : d.authoring().undoLabel();
            boolean done = redo ? d.redo() : d.undo();
            return McpAck.ok().with(redo ? "redone" : "undone", done ? label : null).with("dirty", d.isDirty());
        });
    }

    /** play | pause | seek (time) | stage (fk_only) | in_place. */
    public Object transport(String action, Float time, Boolean value) {
        return onMain(() -> {
            AnimationSession d = sessions().require();
            switch (action == null ? "" : action) {
                case "play" -> d.setPlaying(true);
                case "pause" -> d.setPlaying(false);
                case "seek" -> {
                    if (time == null) throw new IllegalArgumentException("seek needs 'time'");
                    d.setPlaying(false);
                    d.setTime(time);
                }
                case "fk_only" -> d.setStage(Boolean.TRUE.equals(value) ? AnimationAuthoring.STAGE_LAYERS : AnimationAuthoring.STAGE_FINAL);
                case "in_place" -> d.setInPlace(Boolean.TRUE.equals(value));
                default -> throw new IllegalArgumentException("action: play, pause, seek, fk_only, in_place");
            }
            return McpAck.ok().with("time", d.time()).with("playing", d.playing())
                    .with("fkOnly", d.stage() == AnimationAuthoring.STAGE_LAYERS).with("inPlace", d.inPlace());
        });
    }

    /** Bakes a clip of another characterized model onto the open character as a layer (#160): one undo step. */
    public Object retarget(String sourceCharacter, String sourceClip, String layer) {
        if (sourceCharacter == null || sourceClip == null) {
            throw new IllegalArgumentException("'source_character' (.omchar) and 'source_clip' (.omanim) are required");
        }
        Path ch = existing(sourceCharacter), clip = existing(sourceClip);
        return onMain(() -> {
            AnimationSession d = sessions().require();
            AnimationSessions s = sessions();
            try (CharacterDocument src = CharacterDocument.open(ch, s.projectRoot());
                 AnimClipDocument c = AnimClipDocument.read(Files.readAllBytes(clip));
                 Retargeter rt = Retargeter.create(src.rig(), src.definition(), d.model().rig(), d.model().definition())) {
                String name = layer == null || layer.isBlank() ? "Retarget" : layer;
                d.authoring().importRetargeted(c, rt, d.fps(), name);
                return McpAck.ok().with("layer", name).with("dirty", d.isDirty()).with("steps", d.authoring().stepCount());
            } catch (IOException e) {
                throw new IllegalArgumentException(e.getMessage(), e);
            }
        });
    }

    public Object save(String path, boolean prompt, boolean overwrite) {
        if (writes == null) throw new IllegalStateException("file access is unavailable (no write sandbox)");
        Object[] state = onMain(() -> {
            AnimationSession d = sessions().require();
            return new Object[] {d, d.file().orElse(null), d.displayName()};
        });
        AnimationSession doc = (AnimationSession) state[0];
        Path own = (Path) state[1];
        String name = state[2] + ".omanim";
        AssetWriteService.Writer writer = target -> {
            byte[] bytes = onMain(() -> doc.bytesFor(target));
            Files.write(target, bytes);
            return true;
        };
        WriteOutcome outcome;
        if (path == null && !prompt && own != null) {
            outcome = writes.saveInPlace(KIND, own.toString(), writer);
        } else {
            outcome = writes.save(AssetWriteService.WriteRequest.of(KIND, path != null ? path : (prompt ? null : name),
                    prompt, overwrite, name).withDetails(List.of("Animation " + state[2] + " (.omanim)")), writer);
        }
        if (!outcome.ok()) return outcome;
        Path saved = Path.of(outcome.path());
        onMain(() -> {
            doc.saved(saved);
            return null;
        });
        return McpAck.ok().with("saved", outcome.path()).with("promptedUser", Boolean.TRUE.equals(outcome.promptedUser()));
    }

    private static <T> T onMain(Callable<T> task) {
        CompletableFuture<T> future = MainThreadExecutor.submit(task);
        try {
            return future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("Operation timed out on main thread", e);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        }
    }
}
