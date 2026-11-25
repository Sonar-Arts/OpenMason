package com.openmason.main.systems.scripting.commands;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openmason.engine.anim.AnimClipDocument;
import com.openmason.engine.format.oma.AnimLayerMeta;
import com.openmason.engine.format.oma.ParsedKeyframe;
import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.main.systems.scripting.doc.ModelDocument;
import com.openmason.engine.modeling.parts.ModelPartDescriptor;
import com.openmason.engine.modeling.parts.PartTransform;
import org.joml.Vector3f;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Scripted animation authoring: builds DETACHED {@code .omanim} clips in
 * memory as native clips ({@link AnimClipDocument}, OM #165) — identical
 * semantics live and headless (clips never touch the Animation tab's open clip
 * or its undo history; open a written file there to inspect it).
 *
 * <p>Any joint keys: a PART (track keyed by its id, its name as the binding
 * hint) or a BONE (#165: bones are joints too; the track carries the bone
 * flag). Omitted pose components default to the part's CURRENT transform or
 * the bone's rest values, so "key the rest pose at t=0" is just
 * {@code clip.key(joint, 0)}.
 *
 * <p>Saves are DEFERRED: {@code save()} validates and queues; the executor
 * flushes queued files only after the whole script succeeds, so a failing
 * script writes nothing.
 */
public final class AnimCommands {

    /** Trace hook shared with {@link ModelCommands} (single replayable funnel). */
    interface Tracer {
        void trace(String op, Consumer<ObjectNode> fill);
    }

    private record PendingSave(String clipName, Path absolutePath) {
    }

    private final ModelDocument doc;
    private final Tracer tracer;
    private final Path baseDir;

    private final Map<String, AnimClipDocument> clips = new LinkedHashMap<>();
    private final List<PendingSave> pendingSaves = new ArrayList<>();
    private String currentClip;

    AnimCommands(ModelDocument doc, Tracer tracer, Path baseDir) {
        this.doc = doc;
        this.tracer = tracer;
        this.baseDir = baseDir;
    }

    /** Compact clip digest. */
    public record ClipInfo(String name, float duration, float fps, boolean loop,
                           int tracks, int keyframes) {
    }

    // ===================== Commands =====================

    /** Create a new clip and make it current. Names must be unique per run. */
    public String createClip(String name, Float duration, Float fps, Boolean loop) {
        if (name == null || name.isBlank()) {
            throw new CommandException("clip name is required");
        }
        if (clips.containsKey(name)) {
            throw new CommandException("A clip named '" + name + "' already exists in this run",
                    "clip names identify the clip in later anim ops; pick a unique name");
        }
        AnimClipDocument clip = AnimClipDocument.create();
        clip.setName(name);
        clip.setHeader(new AnimClipDocument.Header(fps != null ? fps : 30f, duration != null ? duration : 1f,
                loop == null || loop, true, 0.1f, 0.1f, 0));
        clips.put(name, clip);
        currentClip = name;

        tracer.trace("anim_clip", op -> {
            op.put("name", name);
            if (duration != null) op.put("duration", duration);
            if (fps != null) op.put("fps", fps);
            if (loop != null) op.put("loop", loop);
        });
        return name;
    }

    /** A joint scripts can key: a part or a bone, with its default (current / rest) local values. */
    private record Joint(String id, String name, boolean bone, Vector3f pos, Vector3f rot, Vector3f scale) {
    }

    /**
     * Upsert a keyframe on a joint's track (a part or a bone). Omitted pose
     * components come from the part's current transform / the bone's rest
     * values; easing defaults to LINEAR.
     */
    public void key(String clipOrNull, String jointIdOrName, float time,
                    Vector3f position, Vector3f rotation, Vector3f scale, String easingName) {
        AnimClipDocument clip = resolveClip(clipOrNull);
        Joint joint = resolveJoint(jointIdOrName);
        float duration = clip.header().duration();
        if (time < 0) {
            throw new CommandException("keyframe time must be >= 0, got " + time);
        }
        if (time > duration + 1e-4f) {
            throw new CommandException("keyframe time " + time + " is past clip '"
                    + clip.name() + "' duration " + duration,
                    "extend the clip duration first (anim_clip / om.anim.clip(duration=...))");
        }
        String easing = parseEasing(easingName);
        ParsedKeyframe kf = new ParsedKeyframe(time,
                position != null ? new Vector3f(position) : new Vector3f(joint.pos()),
                rotation != null ? new Vector3f(rotation) : new Vector3f(joint.rot()),
                scale != null ? new Vector3f(scale) : new Vector3f(joint.scale()),
                easing);
        List<ParsedKeyframe> keys = new ArrayList<>();
        for (AnimClipDocument.Track t : clip.tracks()) {
            if (t.role() == null && joint.id().equals(t.partId())) keys.addAll(t.keys());
        }
        keys.removeIf(k -> Math.abs(k.time() - time) <= 1e-4f);
        keys.add(kf);
        keys.sort(java.util.Comparator.comparingDouble(ParsedKeyframe::time));
        clip.setTrack(new AnimClipDocument.Track(joint.id(), joint.name(), null, joint.bone(), keys));

        String clipName = clip.name();
        tracer.trace("anim_key", op -> {
            op.put("clip", clipName);
            op.put("part", joint.name());
            op.put("time", time);
            op.set("position", vecNode(op, kf.position()));
            op.set("rotation", vecNode(op, kf.rotation()));
            op.set("scale", vecNode(op, kf.scale()));
            if (!"LINEAR".equals(easing)) op.put("easing", easing);
        });
    }

    /** Update the clip's mixing-layer metadata (any subset of fields). */
    public void setLayer(String clipOrNull, String type, List<String> maskParts,
                         Float fadeIn, Float fadeOut, Integer priority) {
        AnimClipDocument clip = resolveClip(clipOrNull);
        if (type == null && maskParts == null && fadeIn == null && fadeOut == null && priority == null) {
            throw new CommandException(
                    "pass at least one of type, mask, fade_in, fade_out, priority");
        }
        if (fadeIn != null) requireNonNegative(fadeIn, "fade_in");
        if (fadeOut != null) requireNonNegative(fadeOut, "fade_out");
        if (type != null) {
            clip.setLayerType(parseLayerType(type));
        }
        if (maskParts != null) {
            List<String> names = new ArrayList<>();
            for (String maskEntry : maskParts) {
                names.add(resolveJoint(maskEntry).name()); // mask is by joint NAME
            }
            clip.setMask(names);
        }
        AnimClipDocument.Header h = clip.header();
        clip.setHeader(new AnimClipDocument.Header(h.fps(), h.duration(), h.loop(), true,
                fadeIn != null ? fadeIn : h.fadeInSeconds(), fadeOut != null ? fadeOut : h.fadeOutSeconds(),
                priority != null ? priority : h.priority()));

        String clipName = clip.name();
        tracer.trace("anim_layer", op -> {
            op.put("clip", clipName);
            if (type != null) op.put("type", clip.layerType().name());
            if (maskParts != null) {
                var arr = op.arrayNode();
                clip.mask().forEach(arr::add);
                op.set("mask", arr);
            }
            if (fadeIn != null) op.put("fade_in", fadeIn);
            if (fadeOut != null) op.put("fade_out", fadeOut);
            if (priority != null) op.put("priority", priority);
        });
    }

    /**
     * Queue the clip for writing as {@code .omanim}. Validated now (path
     * resolvability, non-empty clip), written only after the script succeeds.
     */
    public void save(String clipOrNull, String path) {
        AnimClipDocument clip = resolveClip(clipOrNull);
        if (path == null || path.isBlank()) {
            throw new CommandException("save path is required",
                    "e.g. \"idle.omanim\" (relative to the CLI output) or an absolute path");
        }
        if (clip.tracks().isEmpty()) {
            throw new CommandException("clip '" + clip.name() + "' has no keyframes to save",
                    "add at least one key before saving");
        }
        Path resolved = Path.of(path.toLowerCase(Locale.ROOT).endsWith(".omanim") ? path : path + ".omanim");
        if (!resolved.isAbsolute()) {
            if (baseDir == null) {
                throw new CommandException("relative save path '" + path
                        + "' needs a working directory",
                        "live runs have none — use an absolute path");
            }
            resolved = baseDir.resolve(resolved);
        }
        pendingSaves.add(new PendingSave(clip.name(), resolved.normalize()));

        String clipName = clip.name();
        String tracePath = path;
        tracer.trace("anim_save", op -> {
            op.put("clip", clipName);
            op.put("path", tracePath);
        });
    }

    // ===================== Queries =====================

    public ClipInfo info(String clipOrNull) {
        AnimClipDocument clip = resolveClip(clipOrNull);
        List<AnimClipDocument.Track> tracks = clip.tracks();
        int keys = tracks.stream().mapToInt(t -> t.keys().size()).sum();
        AnimClipDocument.Header h = clip.header();
        return new ClipInfo(clip.name(), h.duration(), h.fps(), h.loop(), tracks.size(), keys);
    }

    public List<String> clipNames() {
        return List.copyOf(clips.keySet());
    }

    // ===================== Executor hooks =====================

    /**
     * Write all queued clips. Called by the executor only after the script
     * succeeded; throws on the first failure (earlier files stay written —
     * the failure names the path).
     *
     * @return absolute paths written, in save order
     */
    public List<String> flushSaves() {
        List<String> written = new ArrayList<>();
        for (PendingSave pending : pendingSaves) {
            AnimClipDocument clip = clips.get(pending.clipName());
            try {
                Path parent = pending.absolutePath().getParent();
                if (parent != null) {
                    java.nio.file.Files.createDirectories(parent);
                }
            } catch (java.io.IOException e) {
                throw new CommandException("Cannot create directory for "
                        + pending.absolutePath() + ": " + e.getMessage());
            }
            try {
                clip.write(pending.absolutePath());
            } catch (java.io.IOException | RuntimeException e) {
                throw new CommandException("Failed to write " + pending.absolutePath() + ": " + e.getMessage(),
                        "check the directory exists and is writable");
            }
            written.add(pending.absolutePath().toString());
        }
        pendingSaves.clear();
        return written;
    }

    // ===================== Internals =====================

    private AnimClipDocument resolveClip(String clipOrNull) {
        String name = clipOrNull != null && !clipOrNull.isBlank() ? clipOrNull : currentClip;
        if (name == null) {
            throw new CommandException("no clip exists yet",
                    "create one first: anim_clip / om.anim.clip(name, duration=..)");
        }
        AnimClipDocument clip = clips.get(name);
        if (clip == null) {
            throw new CommandException("No clip '" + name + "'. Known clips: "
                    + String.join(", ", clips.keySet()),
                    "clip names come from anim_clip / om.anim.clip");
        }
        return clip;
    }

    private Joint resolveJoint(String idOrName) {
        if (idOrName == null || idOrName.isBlank()) {
            throw new CommandException("joint (part or bone) name is required");
        }
        var part = doc.parts().getPartById(idOrName).or(() -> doc.parts().getPartByName(idOrName));
        if (part.isPresent()) {
            ModelPartDescriptor p = part.get();
            PartTransform t = p.transform();
            return new Joint(p.id(), p.name(), false, new Vector3f(t.position()), new Vector3f(t.rotation()),
                    new Vector3f(t.scale()));
        }
        var bones = doc.bones();
        if (bones != null) {
            OMOFormat.BoneEntry b = bones.getById(idOrName);
            if (b == null) b = bones.getByName(idOrName);
            if (b != null) {
                return new Joint(b.id(), b.name(), true, new Vector3f(b.posX(), b.posY(), b.posZ()),
                        new Vector3f(b.rotX(), b.rotY(), b.rotZ()), new Vector3f(1f, 1f, 1f));
            }
        }
        List<String> known = new ArrayList<>(doc.parts().getAllParts().stream().map(ModelPartDescriptor::name).toList());
        if (bones != null) bones.getBones().forEach(b -> known.add(b.name()));
        throw new CommandException("No part or bone '" + idOrName + "' (known: "
                + String.join(", ", known.subList(0, Math.min(known.size(), 20))) + ")",
                "animation tracks bind to the model's parts and bones");
    }

    private static String parseEasing(String name) {
        if (name == null || name.isBlank()) return "LINEAR";
        String e = name.trim().toUpperCase(Locale.ROOT);
        if (!List.of("LINEAR", "EASE_IN", "EASE_OUT", "EASE_IN_OUT", "STEP").contains(e)) {
            throw new CommandException("Unknown easing '" + name + "'",
                    "valid: linear, ease_in, ease_out, ease_in_out, step");
        }
        return e;
    }

    private static AnimLayerMeta.LayerType parseLayerType(String name) {
        try {
            return AnimLayerMeta.LayerType.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new CommandException("Unknown layer type '" + name + "'",
                    "valid: base (drives the whole model), overlay (owns only its masked parts)");
        }
    }

    private static void requireNonNegative(float value, String what) {
        if (value < 0) {
            throw new CommandException(what + " must be >= 0, got " + value);
        }
    }

    private static com.fasterxml.jackson.databind.node.ArrayNode vecNode(ObjectNode owner, Vector3f v) {
        var arr = owner.arrayNode();
        arr.add(v.x).add(v.y).add(v.z);
        return arr;
    }
}
