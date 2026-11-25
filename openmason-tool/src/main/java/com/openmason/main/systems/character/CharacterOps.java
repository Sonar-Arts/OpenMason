package com.openmason.main.systems.character;

import com.fasterxml.jackson.databind.JsonNode;
import com.openmason.engine.character.CharacterDefinition;
import com.openmason.engine.character.CharacterDefinition.Vec3;
import com.openmason.engine.rig.Rig;
import com.openmason.engine.rig.RigJoint;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Applies {@code char_edit} operations (OpenMason #163) to a {@link CharacterDocument} as ONE undo step, all or
 * nothing: each op is a plain native setter call, the batch runs inside one edit-session group and a failing op
 * cancels the group. Joint references are a joint id, a joint name, or {@code role:<role>} (the joint the role
 * maps to). Marshalling only; the definition's rules (locking, validation) are native.
 *
 * <pre>
 *   {op:"role", role, joint|null}                {op:"pose", joint, rot:[x,y,z]|null}
 *   {op:"limit", joint, min:[..], max:[..]} | {op:"limit", joint, remove:true}
 *   {op:"socket", name, joint, pos?, rot?, scale?} | {op:"socket", name, remove:true}
 *   {op:"standard_socket", name}                  ("grip_r", "grip_l", "head", "eyes", "back")
 *   {op:"chain", name, start, end, goal?} | {op:"chain", name, remove:true}
 *   {op:"goal", name, joint|null}
 *   {op:"metrics", height?, eye_height?, width?, hip_height?, overridden?:[names]}
 *   {op:"compute_metrics"}                        (measures the reference pose; overridden values kept)
 *   {op:"field", name: name|objectId|profile|animGraph|modelRef|facing, value}
 * </pre>
 */
public final class CharacterOps {

    private CharacterOps() {
    }

    /** Applies the ops as one undo step labelled {@code label}; returns one line per op. */
    public static List<String> apply(CharacterDocument doc, JsonNode ops, String label) {
        if (ops == null || !ops.isArray() || ops.isEmpty()) {
            throw new IllegalArgumentException("ops must be a non-empty array");
        }
        List<String> done = new ArrayList<>();
        doc.edit(label == null || label.isBlank() ? "edit character" : label, null, () -> {
            int i = 0;
            for (JsonNode op : ops) {
                try {
                    done.add(applyOne(doc, op));
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException("ops[" + i + "] (" + op.path("op").asText("?") + "): "
                            + e.getMessage(), e);
                }
                i++;
            }
        });
        return done;
    }

    private static String applyOne(CharacterDocument doc, JsonNode op) {
        CharacterDefinition def = doc.definition();
        String kind = text(op, "op");
        if (kind == null) {
            throw new IllegalArgumentException("missing 'op'");
        }
        boolean remove = op.path("remove").asBoolean(false);
        switch (kind) {
            case "role" -> {
                String role = req(op, "role");
                JsonNode j = op.get("joint");
                if (remove || j == null || j.isNull()) {
                    def.removeRole(role);
                    return "role " + role + " cleared";
                }
                String id = joint(doc, j.asText());
                def.setRole(role, id);
                return "role " + role + " -> " + nameOf(doc.rig(), id);
            }
            case "pose" -> {
                String id = joint(doc, req(op, "joint"));
                JsonNode rot = op.get("rot");
                if (remove || rot == null || rot.isNull()) {
                    def.removeReferenceRotation(id);
                    return "reference rotation of " + nameOf(doc.rig(), id) + " cleared";
                }
                def.setReferenceRotation(id, vec(rot, "rot"));
                return "reference rotation of " + nameOf(doc.rig(), id) + " set";
            }
            case "limit" -> {
                String id = joint(doc, req(op, "joint"));
                if (remove) {
                    def.removeLimit(id);
                    return "limit of " + nameOf(doc.rig(), id) + " removed";
                }
                def.setLimit(id, vec(op.get("min"), "min"), vec(op.get("max"), "max"));
                return "limit of " + nameOf(doc.rig(), id) + " set";
            }
            case "socket" -> {
                String name = req(op, "name");
                if (remove) {
                    def.removeSocket(name);
                    return "socket " + name + " removed";
                }
                CharacterDefinition.Socket old = def.sockets().stream().filter(s -> s.name().equals(name))
                        .findFirst().orElse(null);
                String id = op.has("joint") ? joint(doc, req(op, "joint")) : old != null ? old.jointId() : null;
                if (id == null) {
                    throw new IllegalArgumentException("socket " + name + " needs a joint");
                }
                Vec3 pos = op.has("pos") ? vec(op.get("pos"), "pos") : old != null ? old.pos() : Vec3.ZERO;
                Vec3 rot = op.has("rot") ? vec(op.get("rot"), "rot") : old != null ? old.rotDeg() : Vec3.ZERO;
                Vec3 scale = op.has("scale") ? vec(op.get("scale"), "scale") : old != null ? old.scale() : Vec3.ONE;
                def.setSocket(new CharacterDefinition.Socket(name, id, pos, rot, scale));
                return "socket " + name + " on " + nameOf(doc.rig(), id);
            }
            case "standard_socket" -> {
                String name = req(op, "name");
                def.placeStandardSocket(doc.rig(), name);
                return "standard socket " + name + " placed";
            }
            case "chain" -> {
                String name = req(op, "name");
                if (remove) {
                    def.removeChain(name);
                    return "chain " + name + " removed";
                }
                String goal = text(op, "goal");
                def.setChain(new CharacterDefinition.Chain(name, joint(doc, req(op, "start")),
                        joint(doc, req(op, "end")), goal == null ? "" : goal));
                return "chain " + name + " set";
            }
            case "goal" -> {
                String name = req(op, "name");
                JsonNode j = op.get("joint");
                if (remove || j == null || j.isNull()) {
                    def.removeIkGoal(name);
                    return "goal " + name + " removed";
                }
                String id = joint(doc, j.asText());
                def.setIkGoal(name, id);
                return "goal " + name + " -> " + nameOf(doc.rig(), id);
            }
            case "metrics" -> {
                CharacterDefinition.Metrics m = def.metrics();
                Set<CharacterDefinition.Metric> over = EnumSet.noneOf(CharacterDefinition.Metric.class);
                over.addAll(m.overridden());
                float height = num(op, "height", m.height(), over, CharacterDefinition.Metric.HEIGHT);
                float eye = num(op, "eye_height", m.eyeHeight(), over, CharacterDefinition.Metric.EYE_HEIGHT);
                float width = num(op, "width", m.width(), over, CharacterDefinition.Metric.WIDTH);
                float hip = num(op, "hip_height", m.hipHeight(), over, CharacterDefinition.Metric.HIP_HEIGHT);
                if (op.has("overridden")) {
                    over.clear();
                    for (JsonNode n : op.get("overridden")) {
                        over.add(CharacterDefinition.Metric.valueOf(n.asText().toUpperCase(Locale.ROOT)));
                    }
                }
                def.setMetrics(new CharacterDefinition.Metrics(height, eye, width, hip, over));
                return "metrics set";
            }
            case "compute_metrics" -> {
                float[] lo = doc.boundsMin();
                float[] hi = doc.boundsMax();
                def.setMetrics(def.computeMetrics(doc.rig(), new Vec3(lo[0], lo[1], lo[2]), new Vec3(hi[0], hi[1], hi[2])));
                CharacterDefinition.Metrics m = def.metrics();
                return String.format(Locale.ROOT, "metrics: height %.3f eye %.3f width %.3f hips %.3f", m.height(),
                        m.eyeHeight(), m.width(), m.hipHeight());
            }
            case "field" -> {
                String name = req(op, "name");
                String value = op.path("value").isNull() ? null : op.path("value").asText(null);
                switch (name) {
                    case "name" -> def.setName(value);
                    case "objectId" -> def.setObjectId(value);
                    case "profile" -> def.setProfile(value);
                    case "animGraph" -> def.setAnimGraph(value);
                    case "modelRef" -> def.setModelRef(value);
                    case "facing" -> def.setFacing(value);
                    default -> throw new IllegalArgumentException("unknown field " + name
                            + " (name, objectId, profile, animGraph, modelRef, facing)");
                }
                return name + " set";
            }
            default -> throw new IllegalArgumentException("unknown op " + kind + " (role, pose, limit, socket, "
                    + "standard_socket, chain, goal, metrics, compute_metrics, field)");
        }
    }

    /** A joint id from an id, a name or {@code role:<role>}. */
    public static String joint(CharacterDocument doc, String ref) {
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("empty joint reference");
        }
        if (ref.startsWith("role:")) {
            String id = doc.definition().roles().get(ref.substring(5));
            if (id == null) {
                throw new IllegalArgumentException("role " + ref.substring(5) + " is not mapped");
            }
            return id;
        }
        int i = doc.rig().find(ref);
        if (i < 0) {
            throw new IllegalArgumentException("no joint " + ref);
        }
        return doc.rig().joints().get(i).id();
    }

    /** A joint's display name (its id when unnamed or unknown). */
    public static String nameOf(Rig rig, String id) {
        int i = id == null ? -1 : rig.find(id);
        if (i < 0) {
            return id;
        }
        RigJoint j = rig.joints().get(i);
        return j.name() == null || j.name().isBlank() ? j.id() : j.name();
    }

    /** Names of {@link com.openmason.engine.character.CharacterOverlay}{@code .ROLE_*} state bits. */
    public static List<String> stateNames(int s) {
        List<String> out = new ArrayList<>();
        if ((s & com.openmason.engine.character.CharacterOverlay.ROLE_MAPPED) != 0) out.add("mapped");
        if ((s & com.openmason.engine.character.CharacterOverlay.ROLE_REQUIRED) != 0) out.add("required");
        if ((s & com.openmason.engine.character.CharacterOverlay.ROLE_JOINT_MISSING) != 0) out.add("joint_missing");
        if ((s & com.openmason.engine.character.CharacterOverlay.ROLE_DUPLICATE) != 0) out.add("duplicate");
        if ((s & com.openmason.engine.character.CharacterOverlay.ROLE_HIERARCHY) != 0) out.add("hierarchy");
        if ((s & com.openmason.engine.character.CharacterOverlay.ROLE_SIDE_MISMATCH) != 0) out.add("side_mismatch");
        if ((s & com.openmason.engine.character.CharacterOverlay.ROLE_MIRROR_MISMATCH) != 0) out.add("mirror_mismatch");
        if ((s & com.openmason.engine.character.CharacterOverlay.ROLE_ERROR) != 0) out.add("error");
        return out;
    }

    private static String text(JsonNode op, String field) {
        JsonNode n = op.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }

    private static String req(JsonNode op, String field) {
        String s = text(op, field);
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException("missing '" + field + "'");
        }
        return s;
    }

    private static Vec3 vec(JsonNode n, String field) {
        if (n == null || !n.isArray() || n.size() != 3) {
            throw new IllegalArgumentException("'" + field + "' must be [x, y, z]");
        }
        return new Vec3((float) n.get(0).asDouble(), (float) n.get(1).asDouble(), (float) n.get(2).asDouble());
    }

    private static float num(JsonNode op, String field, float old, Set<CharacterDefinition.Metric> over,
                             CharacterDefinition.Metric metric) {
        if (!op.has(field)) {
            return old;
        }
        over.add(metric); // a typed value is an override (compute_metrics keeps it)
        return (float) op.get(field).asDouble();
    }
}
