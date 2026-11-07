package com.openmason.engine.rendering.particles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A named collection of {@link ParticleEffect}s: built-in presets plus whatever a game or tool loads from
 * JSON. Lookups are by name; registering a name again replaces the effect.
 *
 * <h2>JSON ({@value #FORMAT} v{@value #VERSION})</h2>
 * <pre>{@code
 * { "format": "openmason.particles", "version": 1,
 *   "effects": [ { "name": "fire", "lifetime": [0.5, 0.9], "rate": 60,
 *                  "shape": { "type": "disc", "radius": 0.25 },
 *                  "speed": [0.6, 1.2], "direction": [0, 1, 0], "spread": 12,
 *                  "gravity": [0, 1.2, 0], "drag": 1.5, "wind": 0.3,
 *                  "startSize": [0.35, 0.5], "endSize": [0.05, 0.1], "sizeKeys": [[0, 0.6], [0.2, 1], [1, 1]],
 *                  "colorStart": "#FFD060FF", "colorEnd": "#FF300000",
 *                  "colorKeys": [[0, "#FFF0C0FF"], [0.4, "#FF9020FF"], [1, "#60100060"]], "colorJitter": 0.15,
 *                  "fadeIn": 0.1, "fadeOut": 0.4, "additive": 1, "emissive": true, "softness": 0.3,
 *                  "orientation": "camera",          // camera | velocity | axis_y | flat
 *                  "stretch": 0.03,                  // velocity orientation: trail = speed × stretch
 *                  "rotation": [0, 360], "spin": [-90, 90],
 *                  "sprite": "soft_disc",            // soft_disc | square | atlas
 *                  "frames": { "start": 0, "count": 4, "animate": true },
 *                  "collision": { "enabled": true, "bounce": 0.3, "friction": 0.7 },
 *                  "subEmitters": [ { "effect": "smoke", "trigger": "death", "count": 1, "inherit": 0.2 },
 *                                   { "effect": "embers", "trigger": "trail", "rate": 8 } ],
 *                  "maxLive": 500, "priority": "normal", "lodDistance": 64 } ] }
 * }</pre>
 * Every member but {@code name} is optional and defaults to the {@link ParticleEffect.Builder} value; a
 * scalar is accepted wherever a range is. Angles are degrees, colours {@code #RRGGBB} or {@code #RRGGBBAA}.
 * Sub-emitters name their effect: it may be defined anywhere in the same document or already be in the
 * library; reference cycles are rejected. {@link #write} emits every member, so a write/read round trip
 * reproduces each effect exactly.
 */
public final class ParticleLibrary {

    public static final String FORMAT = "openmason.particles";
    public static final int VERSION = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final Map<String, ParticleEffect> effects = new LinkedHashMap<>();

    /** An empty library. */
    public ParticleLibrary() {
    }

    /** A library pre-filled with {@link Presets}. */
    public static ParticleLibrary withBuiltIns() {
        ParticleLibrary lib = new ParticleLibrary();
        for (ParticleEffect e : Presets.ALL) {
            lib.register(e);
        }
        return lib;
    }

    /** Registers {@code effect} and, if not already present, the effects its sub-emitters spawn. */
    public ParticleLibrary register(ParticleEffect effect) {
        for (ParticleEffect.SubEmitter s : effect.subEmitters()) {
            if (!effects.containsKey(s.effect().name())) {
                register(s.effect());
            }
        }
        effects.put(effect.name(), effect);
        return this;
    }

    /**
     * Registers exactly {@code effect} under its name (replacing in place, keeping its position) without
     * pulling in its sub-emitter targets — for editors that manage the whole set themselves.
     */
    public ParticleLibrary put(ParticleEffect effect) {
        effects.put(effect.name(), effect);
        return this;
    }

    /** Removes the effect called {@code name}; returns whether it was present. */
    public boolean remove(String name) {
        return effects.remove(name) != null;
    }

    public Optional<ParticleEffect> find(String name) {
        return Optional.ofNullable(effects.get(name));
    }

    /** The effect called {@code name}; throws if absent. */
    public ParticleEffect get(String name) {
        ParticleEffect e = effects.get(name);
        if (e == null) {
            throw new IllegalArgumentException("no particle effect named '" + name + "'; have " + effects.keySet());
        }
        return e;
    }

    public boolean contains(String name) {
        return effects.containsKey(name);
    }

    public Collection<ParticleEffect> effects() {
        return Collections.unmodifiableCollection(effects.values());
    }

    public int size() {
        return effects.size();
    }

    // ------------------------------------------------------------------------------------------------
    // JSON
    // ------------------------------------------------------------------------------------------------

    /** Reads effects from a {@value #FORMAT} document and registers them. Returns how many were read. */
    public int load(InputStream in) throws IOException {
        JsonNode root = MAPPER.readTree(in);
        if (root == null || !root.isObject()) {
            throw new IOException("particle library must be a JSON object");
        }
        String format = root.path("format").asText(FORMAT);
        if (!FORMAT.equals(format)) {
            throw new IOException("not a particle library: format '" + format + "'");
        }
        int version = root.path("version").asInt(VERSION);
        if (version > VERSION) {
            throw new IOException("particle library version " + version + " is newer than supported " + VERSION);
        }
        JsonNode list = root.path("effects");
        if (!list.isArray()) {
            throw new IOException("particle library has no 'effects' array");
        }
        Map<String, JsonNode> nodes = new LinkedHashMap<>();
        int index = 0;
        for (JsonNode node : list) {
            String name = node.isObject() ? node.path("name").asText("") : "";
            if (name.isBlank()) {
                throw new IOException("bad particle effect #" + index + ": effect needs a name");
            }
            if (nodes.put(name, node) != null) {
                throw new IOException("particle effect '" + name + "' is defined twice");
            }
            index++;
        }
        Map<String, ParticleEffect> built = new HashMap<>();
        for (String name : nodes.keySet()) {
            try {
                resolve(name, nodes, built, new LinkedHashSet<>());
            } catch (IllegalArgumentException ex) {
                throw new IOException("bad particle effect '" + name + "': " + ex.getMessage(), ex);
            }
        }
        for (String name : nodes.keySet()) {
            effects.put(name, built.get(name));
        }
        return nodes.size();
    }

    private ParticleEffect resolve(String name, Map<String, JsonNode> nodes, Map<String, ParticleEffect> built,
                                   Set<String> chain) {
        ParticleEffect done = built.get(name);
        if (done != null) {
            return done;
        }
        JsonNode node = nodes.get(name);
        if (node == null) {
            ParticleEffect existing = effects.get(name);
            if (existing == null) {
                throw new IllegalArgumentException("sub-emitter effect '" + name + "' is not defined");
            }
            return existing;
        }
        if (!chain.add(name)) {
            throw new IllegalArgumentException("sub-emitter cycle: " + String.join(" -> ", chain) + " -> " + name);
        }
        ParticleEffect e = parseEffect(node, child -> resolve(child, nodes, built, chain));
        chain.remove(name);
        built.put(name, e);
        return e;
    }

    /** Writes every effect as a {@value #FORMAT} document. */
    public void write(OutputStream out) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("format", FORMAT);
        root.put("version", VERSION);
        ArrayNode list = root.putArray("effects");
        for (ParticleEffect e : effects.values()) {
            list.add(toJson(e));
        }
        MAPPER.writeValue(out, root);
    }

    /** One effect as JSON text (editor copy/paste). */
    public static String toJsonText(ParticleEffect e) {
        try {
            return MAPPER.writeValueAsString(toJson(e));
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    static ParticleEffect parseEffect(JsonNode n, java.util.function.Function<String, ParticleEffect> subs) {
        if (!n.isObject()) {
            throw new IllegalArgumentException("effect must be an object");
        }
        String name = n.path("name").asText("");
        if (name.isBlank()) {
            throw new IllegalArgumentException("effect needs a name");
        }
        ParticleEffect d = ParticleEffect.builder(name).build();
        ParticleEffect.Builder b = d.toBuilder();

        ParticleEffect.Range r;
        if ((r = range(n, "lifetime")) != null) b.lifetime(r.min(), r.max());
        if (n.has("rate")) b.rate(f(n, "rate"));
        JsonNode shape = n.get("shape");
        if (shape != null) {
            ParticleEffect.EmitterShape type = parseEnum(ParticleEffect.EmitterShape.class,
                    shape.path("type").asText("point"));
            float[] half = vec3(shape, "half", new float[]{0, 0, 0});
            b.shape(type, (float) shape.path("radius").asDouble(0), half[0], half[1], half[2]);
        }
        if ((r = range(n, "speed")) != null) b.speed(r.min(), r.max());
        float[] dir = vec3(n, "direction", null);
        if (dir != null) b.direction(dir[0], dir[1], dir[2]);
        if (n.has("spread")) b.spread(f(n, "spread"));
        float[] g = vec3(n, "gravity", null);
        if (g != null) b.gravity(g[0], g[1], g[2]);
        if (n.has("drag")) b.drag(f(n, "drag"));
        if (n.has("wind")) b.wind(f(n, "wind"));
        if ((r = range(n, "startSize")) != null) b.startSize(r.min(), r.max());
        if ((r = range(n, "endSize")) != null) b.endSize(r.min(), r.max());
        JsonNode sizeKeys = n.get("sizeKeys");
        if (sizeKeys != null) {
            List<ParticleEffect.SizeKey> keys = new ArrayList<>();
            for (JsonNode k : pairs(sizeKeys, "sizeKeys")) {
                keys.add(new ParticleEffect.SizeKey(number(k.get(0), "sizeKeys t"), number(k.get(1), "sizeKeys scale")));
            }
            b.sizeKeys(keys);
        }
        int c0 = n.has("colorStart") ? parseColor(n.get("colorStart").asText()) : d.colorStart();
        int c1 = n.has("colorEnd") ? parseColor(n.get("colorEnd").asText()) : c0;
        b.colors(c0, c1);
        JsonNode colorKeys = n.get("colorKeys");
        if (colorKeys != null) {
            List<ParticleEffect.ColorKey> keys = new ArrayList<>();
            for (JsonNode k : pairs(colorKeys, "colorKeys")) {
                keys.add(new ParticleEffect.ColorKey(number(k.get(0), "colorKeys t"), parseColor(k.get(1).asText())));
            }
            b.colorKeys(keys);
        }
        if (n.has("colorJitter")) b.colorJitter(f(n, "colorJitter"));
        b.fade(n.has("fadeIn") ? f(n, "fadeIn") : d.fadeIn(), n.has("fadeOut") ? f(n, "fadeOut") : d.fadeOut());
        if (n.has("additive")) b.additive(f(n, "additive"));
        if (n.has("emissive")) b.emissive(n.get("emissive").asBoolean());
        if (n.has("softness")) b.softness(f(n, "softness"));
        if (n.has("orientation")) b.orientation(parseEnum(ParticleEffect.Orientation.class, n.get("orientation").asText()));
        if (n.has("stretch")) b.stretch(f(n, "stretch"));
        if ((r = range(n, "rotation")) != null) b.rotation(r.min(), r.max());
        if ((r = range(n, "spin")) != null) b.spin(r.min(), r.max());
        if (n.has("sprite")) b.sprite(parseEnum(ParticleEffect.Sprite.class, n.get("sprite").asText()));
        JsonNode frames = n.get("frames");
        if (frames != null) {
            ParticleEffect.Sprite sprite = n.has("sprite")
                    ? parseEnum(ParticleEffect.Sprite.class, n.get("sprite").asText()) : ParticleEffect.Sprite.ATLAS;
            b.atlas(frames.path("start").asInt(0), frames.path("count").asInt(1),
                    frames.path("animate").asBoolean(false));
            b.sprite(sprite);
        }
        JsonNode collision = n.get("collision");
        if (collision != null && collision.isObject()) {
            b.collides((float) collision.path("bounce").asDouble(d.bounce()),
                    (float) collision.path("friction").asDouble(d.friction()));
            b.collides(collision.path("enabled").asBoolean(true));
        }
        JsonNode subNodes = n.get("subEmitters");
        if (subNodes != null) {
            if (!subNodes.isArray()) {
                throw new IllegalArgumentException("'subEmitters' must be an array");
            }
            List<ParticleEffect.SubEmitter> list = new ArrayList<>();
            for (JsonNode s : subNodes) {
                String target = s.path("effect").asText("");
                if (target.isBlank()) {
                    throw new IllegalArgumentException("sub-emitter needs an 'effect' name");
                }
                ParticleEffect.Trigger trigger = parseEnum(ParticleEffect.Trigger.class, s.path("trigger").asText("death"));
                list.add(new ParticleEffect.SubEmitter(subs.apply(target), trigger, s.path("count").asInt(1),
                        (float) s.path("rate").asDouble(0), (float) s.path("inherit").asDouble(0)));
            }
            b.subEmitters(list);
        }
        if (n.has("maxLive")) b.maxLive(n.get("maxLive").asInt());
        if (n.has("priority")) b.priority(parseEnum(ParticleEffect.Priority.class, n.get("priority").asText()));
        if (n.has("lodDistance")) b.lodDistance(f(n, "lodDistance"));
        return b.build();
    }

    static ObjectNode toJson(ParticleEffect e) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("name", e.name());
        putRange(n, "lifetime", e.lifetime());
        n.put("rate", e.rate());
        ObjectNode shape = n.putObject("shape");
        shape.put("type", lower(e.shape()));
        shape.put("radius", e.radius());
        shape.putArray("half").add(e.halfX()).add(e.halfY()).add(e.halfZ());
        putRange(n, "speed", e.speed());
        n.putArray("direction").add(e.dirX()).add(e.dirY()).add(e.dirZ());
        n.put("spread", e.spread());
        n.putArray("gravity").add(e.gravityX()).add(e.gravityY()).add(e.gravityZ());
        n.put("drag", e.drag());
        n.put("wind", e.windInfluence());
        putRange(n, "startSize", e.startSize());
        putRange(n, "endSize", e.endSize());
        ArrayNode sizeKeys = n.putArray("sizeKeys");
        for (ParticleEffect.SizeKey k : e.sizeKeys()) {
            sizeKeys.addArray().add(k.t()).add(k.scale());
        }
        n.put("colorStart", formatColor(e.colorStart()));
        n.put("colorEnd", formatColor(e.colorEnd()));
        ArrayNode colorKeys = n.putArray("colorKeys");
        for (ParticleEffect.ColorKey k : e.colorKeys()) {
            colorKeys.addArray().add(k.t()).add(formatColor(k.rgba()));
        }
        n.put("colorJitter", e.colorJitter());
        n.put("fadeIn", e.fadeIn());
        n.put("fadeOut", e.fadeOut());
        n.put("additive", e.additive());
        n.put("emissive", e.emissive());
        n.put("softness", e.softness());
        n.put("orientation", lower(e.orientation()));
        n.put("stretch", e.stretch());
        putRange(n, "rotation", e.rotation());
        putRange(n, "spin", e.spin());
        n.put("sprite", lower(e.sprite()));
        ObjectNode frames = n.putObject("frames");
        frames.put("start", e.frameStart());
        frames.put("count", e.frameCount());
        frames.put("animate", e.animateFrames());
        ObjectNode c = n.putObject("collision");
        c.put("enabled", e.collides());
        c.put("bounce", e.bounce());
        c.put("friction", e.friction());
        ArrayNode subs = n.putArray("subEmitters");
        for (ParticleEffect.SubEmitter s : e.subEmitters()) {
            ObjectNode o = subs.addObject();
            o.put("effect", s.effect().name());
            o.put("trigger", lower(s.trigger()));
            o.put("count", s.count());
            o.put("rate", s.rate());
            o.put("inherit", s.inheritVelocity());
        }
        n.put("maxLive", e.maxLive());
        n.put("priority", lower(e.priority()));
        n.put("lodDistance", e.lodDistance());
        return n;
    }

    // ---- JSON helpers ----

    private static String lower(Enum<?> e) {
        return e.name().toLowerCase(Locale.ROOT);
    }

    private static float f(JsonNode n, String key) {
        return number(n.get(key), key);
    }

    private static float number(JsonNode v, String what) {
        if (v == null || !v.isNumber()) {
            throw new IllegalArgumentException("'" + what + "' must be a number");
        }
        return v.floatValue();
    }

    private static List<JsonNode> pairs(JsonNode v, String key) {
        if (!v.isArray()) {
            throw new IllegalArgumentException("'" + key + "' must be an array of [t, value] pairs");
        }
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode k : v) {
            if (!k.isArray() || k.size() != 2) {
                throw new IllegalArgumentException("'" + key + "' entries must be [t, value]");
            }
            out.add(k);
        }
        return out;
    }

    private static ParticleEffect.Range range(JsonNode n, String key) {
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isNumber()) {
            return ParticleEffect.Range.of(v.floatValue());
        }
        if (v.isArray() && v.size() == 2 && v.get(0).isNumber() && v.get(1).isNumber()) {
            return ParticleEffect.Range.of(v.get(0).floatValue(), v.get(1).floatValue());
        }
        throw new IllegalArgumentException("'" + key + "' must be a number or [min, max]");
    }

    private static float[] vec3(JsonNode n, String key, float[] fallback) {
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) {
            return fallback;
        }
        if (!v.isArray() || v.size() != 3) {
            throw new IllegalArgumentException("'" + key + "' must be [x, y, z]");
        }
        return new float[]{v.get(0).floatValue(), v.get(1).floatValue(), v.get(2).floatValue()};
    }

    private static void putRange(ObjectNode n, String key, ParticleEffect.Range r) {
        n.putArray(key).add(r.min()).add(r.max());
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String text) {
        try {
            return Enum.valueOf(type, text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown " + type.getSimpleName() + " '" + text + "'");
        }
    }

    /** Parses {@code #RRGGBB} (opaque) or {@code #RRGGBBAA} into {@code 0xRRGGBBAA}. */
    public static int parseColor(String text) {
        String s = text.startsWith("#") ? text.substring(1) : text;
        if (s.length() != 6 && s.length() != 8) {
            throw new IllegalArgumentException("colour must be #RRGGBB or #RRGGBBAA: '" + text + "'");
        }
        try {
            long v = Long.parseLong(s, 16);
            return s.length() == 6 ? (int) ((v << 8) | 0xFF) : (int) v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("colour must be hex: '" + text + "'");
        }
    }

    public static String formatColor(int rgba) {
        return String.format(Locale.ROOT, "#%08X", rgba);
    }

    // ------------------------------------------------------------------------------------------------
    // Built-in presets
    // ------------------------------------------------------------------------------------------------

    /** Ready-made, game-agnostic effects. Sizes assume one world unit ≈ one block (1 m). */
    public static final class Presets {
        private Presets() {
        }

        /** Slow grey billows that swell, soften against geometry and fade. */
        public static final ParticleEffect SMOKE = ParticleEffect.builder("smoke")
                .lifetime(2.2f, 3.6f).rate(10).disc(0.2f)
                .speed(0.25f, 0.6f).direction(0, 1, 0).spread(18)
                .gravity(0, 0.5f, 0).drag(0.6f).wind(1f)
                .startSize(0.4f, 0.55f).endSize(1.3f, 1.8f).sizeKey(0f, 0.5f).sizeKey(0.25f, 1f).sizeKey(1f, 1f)
                .colors(0x505050C0, 0x9A9A9A70).colorJitter(0.2f)
                .fade(0.15f, 0.5f).softness(0.6f).rotation(0, 360).spin(-25, 25)
                .lodDistance(96).build();

        /** Rising additive flame tongues, white-hot core to deep red; pair with {@link #SMOKE} and {@link #EMBERS}. */
        public static final ParticleEffect FIRE = ParticleEffect.builder("fire")
                .lifetime(0.45f, 0.9f).rate(55).disc(0.22f)
                .speed(0.5f, 1.1f).direction(0, 1, 0).spread(12)
                .gravity(0, 1.0f, 0).drag(1.6f).wind(0.25f)
                .startSize(0.32f, 0.48f).endSize(0.04f, 0.1f).sizeKey(0f, 0.6f).sizeKey(0.15f, 1f).sizeKey(1f, 1f)
                .colorKey(0f, 0xFFF4D0FF).colorKey(0.25f, 0xFFC040FF).colorKey(0.6f, 0xF05010E0)
                .colorKey(1f, 0x80180060).colorJitter(0.15f)
                .fade(0.1f, 0.4f).additive(1f).emissive(true).softness(0.25f)
                .rotation(0, 360).spin(-90, 90).lodDistance(64).build();

        /** Small upright flame for torches and candles: never tilts when the camera looks down at it. */
        public static final ParticleEffect FLAME = ParticleEffect.builder("flame")
                .lifetime(0.3f, 0.5f).rate(45).disc(0.03f)
                .speed(0.5f, 0.8f).direction(0, 1, 0).spread(6)
                .gravity(0, 1.5f, 0).drag(1.5f)
                .size(0.11f, 0.02f).orientation(ParticleEffect.Orientation.AXIS_Y)
                .colorKey(0f, 0xFFF8E0FF).colorKey(0.4f, 0xFFB030FF).colorKey(1f, 0xC0300040)
                .fade(0.1f, 0.3f).additive(1f).emissive(true).lodDistance(48).build();

        /** Tiny glowing specks drifting up from a fire or torch. */
        public static final ParticleEffect EMBERS = ParticleEffect.builder("embers")
                .lifetime(0.8f, 1.8f).rate(6).disc(0.15f)
                .speed(0.4f, 1.0f).direction(0, 1, 0).spread(30)
                .gravity(0, 0.6f, 0).drag(0.5f).wind(0.8f)
                .size(0.06f, 0.03f).colors(0xFFB040FF, 0xFF4020FF)
                .fade(0f, 0.3f).additive(1f).emissive(true)
                .priority(ParticleEffect.Priority.AMBIENT).lodDistance(32).build();

        /** Fast hot streaks that fall, bounce off blocks and die (anvils, impacts, short circuits). Burst it. */
        public static final ParticleEffect SPARKS = ParticleEffect.builder("sparks")
                .lifetime(0.6f, 1.3f).rate(0).point()
                .speed(3f, 6.5f).direction(0, 1, 0).spread(70)
                .gravity(0, -14f, 0).drag(0.6f)
                .size(0.045f, 0.02f).stretched(0.03f).colors(0xFFF0A0FF, 0xFF6010FF)
                .fade(0f, 0.3f).additive(1f).emissive(true)
                .sprite(ParticleEffect.Sprite.SQUARE).collides(0.45f, 0.6f).build();

        /** Square debris that tumbles and settles on the blocks below — tint it per block. Burst it. */
        public static final ParticleEffect DEBRIS = ParticleEffect.builder("debris")
                .lifetime(0.8f, 1.4f).rate(0).box(0.3f, 0.3f, 0.3f)
                .speed(1.2f, 3f).direction(0, 1, 0).spread(55)
                .gravity(0, -20f, 0).drag(0.2f)
                .size(0.11f, 0.08f).color(0xFFFFFFFF).colorJitter(0.25f)
                .fade(0f, 0.15f).sprite(ParticleEffect.Sprite.SQUARE)
                .rotation(0, 360).collides(0.15f, 0.55f).maxLive(4000).build();

        /** Light dust puffs kicked up by footsteps or landings. Burst it. */
        public static final ParticleEffect DUST = ParticleEffect.builder("dust")
                .lifetime(0.6f, 1.1f).rate(0).disc(0.3f)
                .speed(0.3f, 0.9f).direction(0, 1, 0).spread(80)
                .gravity(0, -0.4f, 0).drag(2.5f).wind(0.6f)
                .startSize(0.15f, 0.25f).endSize(0.35f, 0.5f)
                .colors(0xB8A88A90, 0xB8A88A50).colorJitter(0.1f).fade(0.05f, 0.6f).softness(0.25f)
                .lodDistance(48).build();

        /** Expanding ring lying flat on a surface (rain hits, footsteps in water). */
        public static final ParticleEffect RIPPLE = ParticleEffect.builder("ripple")
                .lifetime(0.5f, 0.7f).rate(0).point().speed(0, 0).spread(0)
                .size(0.05f, 0.45f).orientation(ParticleEffect.Orientation.FLAT)
                .colors(0xDDEEFF90, 0xDDEEFF30).fade(0f, 0.6f)
                .priority(ParticleEffect.Priority.AMBIENT).build();

        /** Droplets thrown up where a raindrop lands. */
        public static final ParticleEffect RAIN_SPLASH = ParticleEffect.builder("rain-splash")
                .lifetime(0.15f, 0.3f).rate(0).point()
                .speed(0.8f, 1.6f).direction(0, 1, 0).spread(60)
                .gravity(0, -12f, 0).size(0.03f, 0.02f).stretched(0.04f)
                .colors(0xC8DCFFC0, 0xC8DCFF80).fade(0f, 0.4f)
                .priority(ParticleEffect.Priority.AMBIENT).build();

        /** Rain streaks over a box volume that splash and ripple where they land (attach above the camera). */
        public static final ParticleEffect RAIN = ParticleEffect.builder("rain")
                .lifetime(1.5f, 1.5f).rate(900).box(8f, 0.5f, 8f)
                .speed(9f, 11f).direction(0, -1, 0).spread(4).wind(0.6f)
                .size(0.03f, 0.03f).stretched(0.05f).colors(0xC0D4FFB0, 0xC0D4FFB0).fade(0f, 0f)
                .collides(0f, 0f)
                .subEmitter(ParticleEffect.SubEmitter.on(ParticleEffect.Trigger.COLLISION, RAIN_SPLASH, 3))
                .subEmitter(ParticleEffect.SubEmitter.on(ParticleEffect.Trigger.COLLISION, RIPPLE, 1))
                .priority(ParticleEffect.Priority.AMBIENT).build();

        /** Water droplets thrown up by a splash; they streak, fall back and land. Burst it. */
        public static final ParticleEffect SPLASH = ParticleEffect.builder("splash")
                .lifetime(0.5f, 0.9f).rate(0).disc(0.35f)
                .speed(2f, 4.2f).direction(0, 1, 0).spread(35)
                .gravity(0, -16f, 0).drag(0.4f)
                .size(0.07f, 0.05f).stretched(0.03f).colors(0xC8E4FFD0, 0xA0C8FF90)
                .fade(0f, 0.25f).additive(0.3f).collides(0f, 0.3f).build();

        /**
         * Gentle snowfall over a box volume (attach the emitter ~6 above the camera). Flakes fall at ~0.5/s
         * terminal speed, so they live long enough to land and settle before melting away.
         */
        public static final ParticleEffect SNOW = ParticleEffect.builder("snow")
                .lifetime(14f, 18f).rate(120).box(12f, 0.5f, 12f)
                .speed(0.1f, 0.3f).direction(0, -1, 0).spread(40)
                .gravity(0, -0.6f, 0).drag(1.2f).wind(1f)
                .size(0.07f, 0.06f).color(0xFFFFFFE6)
                .fade(0.1f, 0.1f).collides(0f, 0f).priority(ParticleEffect.Priority.AMBIENT).build();

        /** Swirling additive motes for enchantments, portals and pickups. */
        public static final ParticleEffect MAGIC = ParticleEffect.builder("magic")
                .lifetime(0.8f, 1.6f).rate(30).sphere(0.6f)
                .speed(0.2f, 0.6f).direction(0, 1, 0).spread(180)
                .gravity(0, 0.5f, 0).drag(1.2f)
                .startSize(0.12f, 0.2f).endSize(0.02f, 0.05f)
                .colors(0xB070FFFF, 0x40D0FFFF).colorJitter(0.3f)
                .fade(0.2f, 0.4f).additive(1f).emissive(true).spin(-180, 180).lodDistance(48).build();

        /** Fireball flash for explosions: each fireball leaves a smoke puff as it burns out. Burst it. */
        public static final ParticleEffect EXPLOSION = ParticleEffect.builder("explosion")
                .lifetime(0.35f, 0.7f).rate(0).sphere(0.8f)
                .speed(2f, 5f).direction(0, 1, 0).spread(180)
                .drag(3.5f).startSize(0.8f, 1.3f).endSize(1.8f, 2.6f)
                .colorKey(0f, 0xFFFFF0FF).colorKey(0.3f, 0xFFD070FF).colorKey(0.7f, 0xE05010C0).colorKey(1f, 0x60200040)
                .fade(0f, 0.4f).additive(0.85f).emissive(true).softness(0.8f).rotation(0, 360).spin(-60, 60)
                .subEmitter(ParticleEffect.SubEmitter.on(ParticleEffect.Trigger.DEATH, SMOKE, 1).inheriting(0.3f))
                .priority(ParticleEffect.Priority.CRITICAL).build();

        /** Glittering trail a firework rocket leaves behind. */
        public static final ParticleEffect FIREWORK_TRAIL = ParticleEffect.builder("firework-trail")
                .lifetime(0.3f, 0.6f).rate(0).point().speed(0.1f, 0.4f).spread(180)
                .gravity(0, -2f, 0).size(0.05f, 0.01f).colors(0xFFE0A0FF, 0xFF804080)
                .fade(0f, 0.5f).additive(1f).emissive(true).build();

        /** The star burst at the top of a firework: coloured streaks that droop and fade. */
        public static final ParticleEffect FIREWORK_BURST = ParticleEffect.builder("firework-burst")
                .lifetime(0.9f, 1.4f).rate(0).point().speed(5f, 8f).spread(180)
                .gravity(0, -3f, 0).drag(1.4f).size(0.12f, 0.05f).stretched(0.06f)
                .colorKey(0f, 0xFFFFFFFF).colorKey(0.15f, 0xFF60D0FF).colorKey(1f, 0x6040FF80).colorJitter(0.3f)
                .fade(0f, 0.4f).additive(1f).emissive(true).build();

        /** A rocket that climbs with a glitter trail and bursts into stars when it burns out. Burst one. */
        public static final ParticleEffect FIREWORK = ParticleEffect.builder("firework")
                .lifetime(1.1f, 1.1f).rate(0).point().speed(11f, 11f).direction(0, 1, 0).spread(4)
                .gravity(0, -6f, 0).size(0.08f, 0.08f).stretched(0.02f).color(0xFFF0C0FF).fade(0f, 0f)
                .additive(1f).emissive(true)
                .subEmitter(ParticleEffect.SubEmitter.trail(FIREWORK_TRAIL, 90))
                .subEmitter(ParticleEffect.SubEmitter.on(ParticleEffect.Trigger.DEATH, FIREWORK_BURST, 120).inheriting(0.3f))
                .priority(ParticleEffect.Priority.CRITICAL).build();

        /** Every preset (sub-emitter targets before the effects that use them). */
        public static final List<ParticleEffect> ALL = List.of(
                SMOKE, FIRE, FLAME, EMBERS, SPARKS, DEBRIS, DUST, RIPPLE, RAIN_SPLASH, RAIN, SPLASH, SNOW, MAGIC,
                EXPLOSION, FIREWORK_TRAIL, FIREWORK_BURST, FIREWORK);
    }
}
