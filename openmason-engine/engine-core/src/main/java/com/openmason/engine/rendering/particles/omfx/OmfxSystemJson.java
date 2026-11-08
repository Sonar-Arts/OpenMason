package com.openmason.engine.rendering.particles.omfx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openmason.engine.format.sound.SoundData;
import com.openmason.engine.format.sound.SoundDef;
import com.openmason.engine.rendering.particles.FxBinding;
import com.openmason.engine.rendering.particles.FxEmitter;
import com.openmason.engine.rendering.particles.FxEventHandler;
import com.openmason.engine.rendering.particles.FxLight;
import com.openmason.engine.rendering.particles.FxParameter;
import com.openmason.engine.rendering.particles.FxSoundCue;
import com.openmason.engine.rendering.particles.FxSystem;
import com.openmason.engine.rendering.particles.ParticleEffect;
import com.openmason.engine.rendering.particles.ParticleLibrary;
import com.openmason.engine.rendering.particles.graph.FxGraphJson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** The {@code system.json} member of an {@code .omfx} v2 archive (see {@link OmfxCodec} for the shape). */
final class OmfxSystemJson {

    private OmfxSystemJson() {
    }

    /** What {@link #read} returns: the system plus the archive data it pulled in. */
    record Parsed(FxSystem system, List<OmfxSprite> sprites, Map<String, byte[]> soundFiles, Set<String> usedEntries) {
    }

    /** What {@link #write} returns: system.json plus the graph and texture entries it names, in write order. */
    record Written(ObjectNode json, Map<String, byte[]> files) {
    }

    // ------------------------------------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------------------------------------

    static Parsed read(String id, JsonNode root, Map<String, byte[]> entries) throws IOException {
        Set<String> used = new HashSet<>();
        try {
            SoundData sounds = readSounds(root.get("sounds"));
            Map<String, byte[]> soundFiles = new LinkedHashMap<>();
            for (SoundDef d : sounds.sounds()) {
                if (!d.isEmbedded()) {
                    continue;
                }
                String path = d.filename();
                if (!OmfxCodec.isSoundPath(path)) {
                    throw new IllegalArgumentException("sound '" + d.event() + "': embedded file '" + path
                            + "' must be under " + OmfxCodec.SOUNDS_DIR);
                }
                byte[] data = entries.get(path);
                if (data == null) {
                    throw new IllegalArgumentException("sound '" + d.event() + "': file '" + path
                            + "' is missing from the archive");
                }
                if (!d.checksum().isEmpty() && !d.checksum().equalsIgnoreCase(OmfxCodec.sha256(data))) {
                    throw new IllegalArgumentException("sound '" + d.event() + "': '" + path
                            + "' does not match its checksum (damaged or replaced)");
                }
                soundFiles.put(path, data);
                used.add(path);
            }

            List<OmfxSprite> sprites = readSprites(root.get("sprites"), entries, used);
            Map<String, ParticleEffect> effects = readEffects(root.get("effects"), entries, used);

            FxSystem.Builder b = FxSystem.builder(id);
            for (JsonNode n : array(root, "emitters")) {
                b.emitter(readEmitter(n, effects));
            }
            for (JsonNode n : array(root, "parameters")) {
                b.parameter(readParameter(n));
            }
            for (JsonNode n : array(root, "bindings")) {
                b.bind(readBinding(n));
            }
            for (JsonNode n : array(root, "events")) {
                b.on(readHandler(n));
            }
            JsonNode light = root.get("light");
            if (light != null && !light.isNull()) {
                b.light(readLight(light));
            }
            b.sounds(sounds);
            for (JsonNode n : array(root, "cues")) {
                b.cue(readCue(n));
            }
            return new Parsed(b.build(), sprites, soundFiles, used);
        } catch (IllegalArgumentException | IOException e) {
            throw new IOException(OmfxCodec.SYSTEM + ": " + e.getMessage(), e);
        }
    }

    private static SoundData readSounds(JsonNode list) {
        if (list == null || list.isNull()) {
            return new SoundData(List.of());
        }
        if (!list.isArray()) {
            throw new IllegalArgumentException("'sounds' must be an array");
        }
        List<SoundDef> defs = new ArrayList<>();
        int i = 0;
        for (JsonNode n : list) {
            String at = "sounds[" + i++ + "]";
            object(n, at);
            float pitchMin = num(n, "pitchMin", 0.9f, at);
            float pitchMax = Math.max(num(n, "pitchMax", 1.1f, at), pitchMin);
            String file = optText(n, "file", at);
            String checksum = optText(n, "checksum", at);
            try {
                defs.add(new SoundDef(text(n, "event", at), file, file != null && checksum == null ? "" : checksum,
                        optText(n, "resource", at), num(n, "volume", 1f, at), pitchMin, pitchMax,
                        bool(n, "variation", true, at)));
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new IllegalArgumentException(at + ": " + e.getMessage(), e);
            }
        }
        return new SoundData(defs);
    }

    private static List<OmfxSprite> readSprites(JsonNode list, Map<String, byte[]> entries, Set<String> used)
            throws IOException {
        List<OmfxSprite> sprites = new ArrayList<>();
        if (list == null || list.isNull()) {
            return sprites;
        }
        if (!list.isArray()) {
            throw new IllegalArgumentException("'sprites' must be an array");
        }
        Set<String> names = new HashSet<>();
        int i = 0;
        for (JsonNode n : list) {
            String at = "sprites[" + i++ + "]";
            object(n, at);
            String name = text(n, "name", at);
            String file = text(n, "file", at);
            if (!file.startsWith(OmfxCodec.TEXTURES_DIR)) {
                throw new IllegalArgumentException("sprite '" + name + "': file '" + file + "' must be under "
                        + OmfxCodec.TEXTURES_DIR);
            }
            byte[] data = entries.get(file);
            if (data == null) {
                throw new IllegalArgumentException("sprite '" + name + "': file '" + file
                        + "' is missing from the archive");
            }
            if (!names.add(name)) {
                throw new IllegalArgumentException("sprite '" + name + "' is listed twice");
            }
            OmfxSprite sprite = new OmfxSprite(name, data);
            sprite.check();
            sprites.add(sprite);
            used.add(file);
        }
        return sprites;
    }

    private static Map<String, ParticleEffect> readEffects(JsonNode list, Map<String, byte[]> entries,
                                                           Set<String> used) throws IOException {
        if (list == null || !list.isArray()) {
            throw new IllegalArgumentException("'effects' must be an array");
        }
        ObjectNode library = OmfxCodec.MAPPER.createObjectNode();
        library.put("format", ParticleLibrary.FORMAT);
        library.put("version", ParticleLibrary.VERSION);
        ArrayNode out = library.putArray("effects");
        for (JsonNode n : list) {
            if (!n.isObject()) {
                throw new IllegalArgumentException("every effect must be an object");
            }
            ObjectNode effect = n.deepCopy();
            JsonNode graph = effect.get("graph");
            if (graph != null && graph.isTextual()) {
                String path = graph.asText();
                String name = effect.path("name").asText("?");
                if (!path.startsWith(OmfxCodec.GRAPHS_DIR)) {
                    throw new IllegalArgumentException("effect '" + name + "': graph '" + path + "' must be under "
                            + OmfxCodec.GRAPHS_DIR);
                }
                byte[] data = entries.get(path);
                if (data == null) {
                    throw new IllegalArgumentException("effect '" + name + "': graph '" + path
                            + "' is missing from the archive");
                }
                JsonNode tree;
                try {
                    tree = OmfxCodec.MAPPER.readTree(new String(data, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new IOException(path + ": not valid JSON: " + e.getMessage(), e);
                }
                effect.set("graph", tree);
                used.add(path);
            }
            out.add(effect);
        }
        ParticleLibrary lib = new ParticleLibrary();
        lib.load(library); // names, duplicates, sub-emitter references and cycles, graph IR: one shared reader
        Map<String, ParticleEffect> byName = new LinkedHashMap<>();
        for (ParticleEffect e : lib.effects()) {
            byName.put(e.name(), e);
        }
        return byName;
    }

    private static FxEmitter readEmitter(JsonNode n, Map<String, ParticleEffect> effects) {
        object(n, "emitter");
        String name = text(n, "name", "emitter");
        String at = "emitter '" + name + "'";
        String effectName = text(n, "effect", at);
        ParticleEffect effect = effects.get(effectName);
        if (effect == null) {
            throw new IllegalArgumentException(at + " plays effect '" + effectName + "', which is not in 'effects'");
        }
        FxEmitter.Builder b = FxEmitter.builder(name, effect);
        float[] offset = vec3(n, "offset", new float[]{0, 0, 0}, at);
        b.offset(offset[0], offset[1], offset[2]);
        b.delay(num(n, "delay", 0f, at));
        JsonNode duration = n.get("duration");
        if (duration != null && !duration.isNull()) {
            if (duration.isTextual() && duration.asText().equalsIgnoreCase("forever")) {
                b.forever();
            } else if (duration.isNumber()) {
                b.duration(duration.floatValue());
            } else {
                throw new IllegalArgumentException(at + ": 'duration' must be a number or \"forever\"");
            }
        }
        b.loops(integer(n, "loops", 1, at));
        b.interval(num(n, "interval", 0f, at));
        b.burst(integer(n, "burst", 0, at));
        b.rateScale(num(n, "rateScale", 1f, at));
        b.sizeScale(num(n, "sizeScale", 1f, at));
        b.speedScale(num(n, "speedScale", 1f, at));
        b.tint(color(n, "tint", 0xFFFFFFFF, at));
        float[] dir = vec3(n, "direction", null, at);
        if (dir != null) {
            b.direction(dir[0], dir[1], dir[2]);
        }
        try {
            return b.build();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(at + ": " + e.getMessage(), e);
        }
    }

    private static FxParameter readParameter(JsonNode n) {
        object(n, "parameter");
        String name = text(n, "name", "parameter");
        String at = "parameter '" + name + "'";
        FxParameter.Type type = enumOf(FxParameter.Type.class, text(n, "type", at), at);
        JsonNode d = n.get("default");
        float[] v = new float[4];
        switch (type) {
            case FLOAT -> {
                if (d != null && !d.isNull()) {
                    if (!d.isNumber()) {
                        throw new IllegalArgumentException(at + ": a float default must be a number");
                    }
                    v[0] = d.floatValue();
                }
            }
            case VECTOR -> v = numbers(d, 3, new float[]{0, 0, 0, 0}, at);
            case COLOR -> v = numbers(d, 4, new float[]{1, 1, 1, 1}, at);
        }
        return new FxParameter(name, type, v[0], v[1], v[2], v[3]);
    }

    private static FxBinding readBinding(JsonNode n) {
        object(n, "binding");
        String parameter = text(n, "parameter", "binding");
        String at = "binding of '" + parameter + "'";
        FxBinding.Target target = enumOf(FxBinding.Target.class, text(n, "target", at), at);
        return new FxBinding(parameter, target, optText(n, "emitter", at), num(n, "scale", 1f, at),
                num(n, "bias", 0f, at));
    }

    private static FxEventHandler readHandler(JsonNode n) {
        object(n, "event");
        String source = text(n, "source", "event");
        String at = "event on '" + source + "'";
        FxEventHandler.Trigger trigger = enumOf(FxEventHandler.Trigger.class, text(n, "trigger", at), at);
        try {
            return new FxEventHandler(trigger, source, optText(n, "spawnInto", at), integer(n, "count", 0, at),
                    num(n, "inherit", 0f, at), num(n, "chance", 1f, at), optText(n, "forward", at),
                    optText(n, "sound", at));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(at + ": " + e.getMessage(), e);
        }
    }

    private static FxLight readLight(JsonNode n) {
        String at = "light";
        object(n, at);
        List<FxLight.Key> keys = new ArrayList<>();
        JsonNode list = n.get("keys");
        if (list != null && !list.isNull()) {
            if (!list.isArray()) {
                throw new IllegalArgumentException("light: 'keys' must be an array of [seconds, value]");
            }
            for (JsonNode k : list) {
                if (!k.isArray() || k.size() != 2 || !k.get(0).isNumber() || !k.get(1).isNumber()) {
                    throw new IllegalArgumentException("light: every key must be [seconds, value]");
                }
                keys.add(new FxLight.Key(k.get(0).floatValue(), k.get(1).floatValue()));
            }
        }
        float[] offset = vec3(n, "offset", new float[]{0, 0, 0}, at);
        try {
            return new FxLight(color(n, "color", 0xFFFFFFFF, at), num(n, "intensity", 1f, at),
                    num(n, "radius", 8f, at), keys, bool(n, "loopKeys", false, at), num(n, "flicker", 0f, at),
                    num(n, "flickerHz", 0f, at), offset[0], offset[1], offset[2]);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("light: " + e.getMessage(), e);
        }
    }

    private static FxSoundCue readCue(JsonNode n) {
        object(n, "cue");
        String sound = text(n, "sound", "cue");
        String at = "cue '" + sound + "'";
        FxSoundCue.Trigger trigger = enumOf(FxSoundCue.Trigger.class, text(n, "trigger", at), at);
        try {
            return new FxSoundCue(trigger, optText(n, "emitter", at), sound, num(n, "volume", 1f, at));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(at + ": " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------------------------------------

    static Written write(OmfxDocument doc) {
        return write(doc, false);
    }

    /**
     * {@code inline}: the editable view ({@link OmfxCodec#toSystemJson}) — graphs as JSON objects in their effects,
     * no sprites table, no files; otherwise the archive form.
     */
    static Written write(OmfxDocument doc, boolean inline) {
        FxSystem system = doc.system();
        Map<String, byte[]> files = new LinkedHashMap<>();
        Set<String> taken = new HashSet<>();
        for (String extra : doc.extraFiles().keySet()) {
            taken.add(extra.toLowerCase(Locale.ROOT));
        }
        ObjectNode root = OmfxCodec.MAPPER.createObjectNode();

        ArrayNode effects = root.putArray("effects");
        for (ParticleEffect e : doc.effects()) {
            ObjectNode n = ParticleLibrary.toJsonNode(e);
            if (e.graph() != null && inline) {
                n.set("graph", FxGraphJson.toJson(e.graph()));
            } else if (e.graph() != null) {
                String path = OmfxCodec.uniquePath(OmfxCodec.GRAPHS_DIR, OmfxCodec.fileStem(e.name()), ".json", taken);
                files.put(path, FxGraphJson.write(e.graph()).getBytes(StandardCharsets.UTF_8));
                n.put("graph", path);
            }
            effects.add(n);
        }

        ArrayNode emitters = root.putArray("emitters");
        for (FxEmitter e : system.emitters()) {
            ObjectNode n = emitters.addObject();
            n.put("name", e.name());
            n.put("effect", e.effect().name());
            n.putArray("offset").add(e.offsetX()).add(e.offsetY()).add(e.offsetZ());
            n.put("delay", e.delay());
            if (e.duration() == FxEmitter.FOREVER) {
                n.put("duration", "forever");
            } else {
                n.put("duration", e.duration());
            }
            n.put("loops", e.loops());
            n.put("interval", e.interval());
            n.put("burst", e.burst());
            n.put("rateScale", e.rateScale());
            n.put("sizeScale", e.sizeScale());
            n.put("speedScale", e.speedScale());
            n.put("tint", ParticleLibrary.formatColor(e.tint()));
            if (e.hasDirection()) {
                n.putArray("direction").add(e.dirX()).add(e.dirY()).add(e.dirZ());
            } else {
                n.putNull("direction");
            }
        }

        ArrayNode parameters = root.putArray("parameters");
        for (FxParameter p : system.parameters()) {
            ObjectNode n = parameters.addObject();
            n.put("name", p.name());
            n.put("type", lower(p.type()));
            switch (p.type()) {
                case FLOAT -> n.put("default", p.x());
                case VECTOR -> n.putArray("default").add(p.x()).add(p.y()).add(p.z());
                case COLOR -> n.putArray("default").add(p.x()).add(p.y()).add(p.z()).add(p.w());
            }
        }

        ArrayNode bindings = root.putArray("bindings");
        for (FxBinding b : system.bindings()) {
            ObjectNode n = bindings.addObject();
            n.put("parameter", b.parameter());
            n.put("target", lower(b.target()));
            putText(n, "emitter", b.emitter());
            n.put("scale", b.scale());
            n.put("bias", b.bias());
        }

        ArrayNode events = root.putArray("events");
        for (FxEventHandler h : system.handlers()) {
            ObjectNode n = events.addObject();
            n.put("trigger", lower(h.trigger()));
            n.put("source", h.source());
            putText(n, "spawnInto", h.spawnInto());
            n.put("count", h.count());
            n.put("inherit", h.inheritVelocity());
            n.put("chance", h.chance());
            putText(n, "forward", h.forward());
            putText(n, "sound", h.sound());
        }

        FxLight light = system.light();
        if (light == null) {
            root.putNull("light");
        } else {
            ObjectNode n = root.putObject("light");
            n.put("color", ParticleLibrary.formatColor(light.color()));
            n.put("intensity", light.intensity());
            n.put("radius", light.radius());
            ArrayNode keys = n.putArray("keys");
            for (FxLight.Key k : light.keys()) {
                keys.addArray().add(k.time()).add(k.value());
            }
            n.put("loopKeys", light.loopKeys());
            n.put("flicker", light.flicker());
            n.put("flickerHz", light.flickerHz());
            n.putArray("offset").add(light.offsetX()).add(light.offsetY()).add(light.offsetZ());
        }

        ArrayNode sounds = root.putArray("sounds");
        for (SoundDef d : system.sounds().sounds()) {
            ObjectNode n = sounds.addObject();
            n.put("event", d.event());
            if (d.isEmbedded()) {
                n.put("file", d.filename());
                n.put("checksum", OmfxCodec.checksumFor(d, doc));
            } else {
                n.put("resource", d.resourcePath());
            }
            n.put("volume", d.volume());
            n.put("pitchMin", d.pitchMin());
            n.put("pitchMax", d.pitchMax());
            n.put("variation", d.variation());
        }

        ArrayNode cues = root.putArray("cues");
        for (FxSoundCue c : system.cues()) {
            ObjectNode n = cues.addObject();
            n.put("trigger", lower(c.trigger()));
            putText(n, "emitter", c.emitter());
            n.put("sound", c.sound());
            n.put("volume", c.volume());
        }

        if (inline) {
            return new Written(root, Map.of());
        }
        ArrayNode sprites = root.putArray("sprites");
        for (OmfxSprite s : doc.sprites()) {
            String path = OmfxCodec.uniquePath(OmfxCodec.TEXTURES_DIR, OmfxCodec.fileStem(s.name()),
                    "." + s.kind().extension, taken);
            files.put(path, s.bytes());
            ObjectNode n = sprites.addObject();
            n.put("name", s.name());
            n.put("file", path);
        }
        return new Written(root, files);
    }

    // ------------------------------------------------------------------------------------------------
    // JSON helpers (every failure names where it happened)
    // ------------------------------------------------------------------------------------------------

    private static Iterable<JsonNode> array(JsonNode root, String key) {
        JsonNode v = root.get(key);
        if (v == null || v.isNull()) {
            return List.of();
        }
        if (!v.isArray()) {
            throw new IllegalArgumentException("'" + key + "' must be an array");
        }
        return v;
    }

    private static void object(JsonNode n, String what) {
        if (n == null || !n.isObject()) {
            throw new IllegalArgumentException(what + " must be an object");
        }
    }

    private static String text(JsonNode n, String key, String at) {
        JsonNode v = n.get(key);
        if (v == null || !v.isTextual() || v.asText().isBlank()) {
            throw new IllegalArgumentException(at + ": '" + key + "' must be a non-blank string");
        }
        return v.asText();
    }

    private static String optText(JsonNode n, String key, String at) {
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.isTextual()) {
            throw new IllegalArgumentException(at + ": '" + key + "' must be a string or null");
        }
        return v.asText();
    }

    private static float num(JsonNode n, String key, float fallback, String at) {
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) {
            return fallback;
        }
        if (!v.isNumber()) {
            throw new IllegalArgumentException(at + ": '" + key + "' must be a number");
        }
        return v.floatValue();
    }

    private static int integer(JsonNode n, String key, int fallback, String at) {
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) {
            return fallback;
        }
        if (!v.isIntegralNumber() || !v.canConvertToInt()) {
            throw new IllegalArgumentException(at + ": '" + key + "' must be an integer");
        }
        return v.intValue();
    }

    private static boolean bool(JsonNode n, String key, boolean fallback, String at) {
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) {
            return fallback;
        }
        if (!v.isBoolean()) {
            throw new IllegalArgumentException(at + ": '" + key + "' must be true or false");
        }
        return v.booleanValue();
    }

    private static int color(JsonNode n, String key, int fallback, String at) {
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) {
            return fallback;
        }
        if (!v.isTextual()) {
            throw new IllegalArgumentException(at + ": '" + key + "' must be \"#RRGGBB\" or \"#RRGGBBAA\"");
        }
        try {
            return ParticleLibrary.parseColor(v.asText());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(at + ": " + e.getMessage(), e);
        }
    }

    private static float[] vec3(JsonNode n, String key, float[] fallback, String at) {
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) {
            return fallback;
        }
        return numbers(v, 3, fallback, at + " '" + key + "'");
    }

    /** Exactly {@code count} numbers into a 4-wide array (rest 0); null/absent = {@code fallback}. */
    private static float[] numbers(JsonNode v, int count, float[] fallback, String at) {
        if (v == null || v.isNull()) {
            return fallback;
        }
        if (!v.isArray() || v.size() != count) {
            throw new IllegalArgumentException(at + ": expected an array of " + count + " numbers");
        }
        float[] out = new float[4];
        for (int i = 0; i < count; i++) {
            if (!v.get(i).isNumber()) {
                throw new IllegalArgumentException(at + ": expected an array of " + count + " numbers");
            }
            out[i] = v.get(i).floatValue();
        }
        return out;
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String text, String at) {
        try {
            return Enum.valueOf(type, text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            StringBuilder known = new StringBuilder();
            for (E c : type.getEnumConstants()) {
                known.append(known.isEmpty() ? "" : ", ").append(lower(c));
            }
            throw new IllegalArgumentException(at + ": unknown " + type.getSimpleName() + " '" + text + "' (one of "
                    + known + ")");
        }
    }

    private static void putText(ObjectNode n, String key, String value) {
        if (value == null) {
            n.putNull(key);
        } else {
            n.put(key, value);
        }
    }

    private static String lower(Enum<?> e) {
        return e.name().toLowerCase(Locale.ROOT);
    }
}
