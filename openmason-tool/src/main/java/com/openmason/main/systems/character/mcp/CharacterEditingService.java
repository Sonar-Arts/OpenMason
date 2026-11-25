package com.openmason.main.systems.character.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.openmason.engine.character.CharacterDefinition;
import com.openmason.engine.character.CharacterOverlay;
import com.openmason.engine.character.CharacterProfile;
import com.openmason.engine.rig.Rig;
import com.openmason.engine.rig.RigJoint;
import com.openmason.main.systems.character.CharacterDocument;
import com.openmason.main.systems.character.CharacterOps;
import com.openmason.main.systems.character.CharacterSessions;
import com.openmason.main.systems.io.AssetWriteService;
import com.openmason.main.systems.io.BuiltinFileTypes;
import com.openmason.main.systems.io.WriteKind;
import com.openmason.main.systems.io.AssetWriteService.WriteOutcome;
import com.openmason.main.systems.mcp.McpAck;
import com.openmason.main.systems.threading.MainThreadExecutor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * The {@code char_*} MCP tools' backend (OpenMason #163): every call runs on the main thread against the ONE
 * character the Character tab shows ({@link CharacterSessions}), and every editing call is ONE undo step of its
 * native history (all-or-nothing). Characterization, validation, mirroring, fitting and socket placement are
 * native calls; this class marshals arguments and shapes the replies.
 */
public final class CharacterEditingService {

    static final WriteKind KIND = WriteKind.of(BuiltinFileTypes.OMCHAR);
    private static final long TIMEOUT_MS = 30_000;

    private final Supplier<CharacterSessions> sessions;
    private final AssetWriteService writes;

    public CharacterEditingService(Supplier<CharacterSessions> sessions, AssetWriteService writes) {
        this.sessions = sessions;
        this.writes = writes;
    }

    private CharacterSessions sessions() {
        CharacterSessions s = sessions.get();
        if (s == null) {
            throw new IllegalStateException("the Character workspace is unavailable");
        }
        return s;
    }

    // ------------------------------------------------------------------------------------------------

    /** Opens an .omchar ({@code path}) or starts a new character for a model file ({@code model}). */
    public Object open(String path, String model, boolean discard) {
        if ((path == null) == (model == null)) {
            throw new IllegalArgumentException("give exactly one of 'path' (an .omchar) or 'model' (a model to characterize)");
        }
        Path file = writes == null ? Path.of(path != null ? path : model)
                : writes.sandbox().resolveExisting(path != null ? path : model);
        return onMain(() -> {
            CharacterSessions s = sessions();
            s.current().ifPresent(d -> {
                if (d.isDirty() && !discard) {
                    throw new IllegalStateException("'" + d.displayName() + "' has unsaved changes: char_save it, or pass "
                            + "discard:true");
                }
            });
            CharacterDocument doc;
            try {
                doc = path != null ? CharacterDocument.open(file, s.projectRoot()) : CharacterDocument.fromModel(file);
            } catch (IOException e) {
                throw new IllegalArgumentException(e.getMessage(), e);
            }
            s.replace(doc);
            return summary(doc);
        });
    }

    public Object close(boolean discard) {
        return onMain(() -> {
            CharacterSessions s = sessions();
            CharacterDocument d = s.current().orElse(null);
            if (d == null) {
                return McpAck.ok().with("closed", false);
            }
            if (d.isDirty() && !discard) {
                throw new IllegalStateException("'" + d.displayName() + "' has unsaved changes: char_save it, or pass discard:true");
            }
            s.close();
            return McpAck.ok().with("closed", true);
        });
    }

    /** The character (or one section: roles, joints, pose, limits, chains, goals, sockets, metrics, issues, profile). */
    public Object get(String section) {
        return onMain(() -> {
            CharacterDocument d = sessions().require();
            if (section == null || section.isBlank()) {
                Map<String, Object> out = summary(d);
                out.put("roles", roles(d));
                out.put("sockets", sockets(d));
                out.put("chains", chains(d));
                out.put("goals", goals(d));
                out.put("metrics", metrics(d));
                out.put("issues", issues(d.issues()));
                return out;
            }
            return switch (section) {
                case "roles" -> roles(d);
                case "joints" -> joints(d);
                case "pose" -> pose(d);
                case "limits" -> limits(d);
                case "chains" -> chains(d);
                case "goals" -> goals(d);
                case "sockets" -> sockets(d);
                case "metrics" -> metrics(d);
                case "issues" -> issues(d.issues());
                case "profile" -> profile(d);
                default -> throw new IllegalArgumentException("unknown section " + section
                        + " (roles, joints, pose, limits, chains, goals, sockets, metrics, issues, profile)");
            };
        });
    }

    /** A batch of edits, one undo step, all or nothing (see {@link CharacterOps}). */
    public Object edit(JsonNode ops, String label) {
        return onMain(() -> {
            CharacterDocument d = sessions().require();
            List<String> done = CharacterOps.apply(d, ops, label);
            return withState(d, McpAck.ok().with("applied", done));
        });
    }

    /** Auto-map onto a profile (default: the definition's): roles, facing, chains and IK goals. */
    public Object autoMap(String profileId) {
        return onMain(() -> {
            CharacterDocument d = sessions().require();
            CharacterDefinition.AutoMapResult[] result = new CharacterDefinition.AutoMapResult[1];
            d.edit("auto-map", null, () -> {
                if (profileId != null && !profileId.isBlank()) {
                    d.definition().setProfile(profileId);
                    d.syncProfile();
                }
                result[0] = d.definition().autoMap(d.rig(), d.profile());
            });
            List<Map<String, Object>> matches = new ArrayList<>();
            for (CharacterDefinition.RoleMatch m : result[0].matches()) {
                matches.add(Map.of("role", m.role(), "joint", CharacterOps.nameOf(d.rig(), m.jointId()),
                        "confidence", Math.round(m.confidence() * 100) / 100.0));
            }
            return withState(d, McpAck.ok().with("profile", d.profile().id()).with("facing", facing(d))
                    .with("mapped", matches.size()).with("matches", matches).with("notes", issues(result[0].notes()))
                    .with("unmappedRequired", unmappedRequired(d)));
        });
    }

    public Object mirror(String from, List<String> what) {
        return onMain(() -> {
            CharacterDocument d = sessions().require();
            CharacterProfile.Side side = switch (from == null ? "left" : from.toLowerCase(Locale.ROOT)) {
                case "left", "l" -> CharacterProfile.Side.LEFT;
                case "right", "r" -> CharacterProfile.Side.RIGHT;
                default -> throw new IllegalArgumentException("from must be left or right");
            };
            int bits = 0;
            for (String w : what == null || what.isEmpty() ? List.of("roles", "limits", "sockets") : what) {
                bits |= switch (w) {
                    case "roles" -> CharacterDefinition.MIRROR_ROLES;
                    case "limits" -> CharacterDefinition.MIRROR_LIMITS;
                    case "sockets" -> CharacterDefinition.MIRROR_SOCKETS;
                    default -> throw new IllegalArgumentException("what: roles, limits, sockets");
                };
            }
            int mask = bits;
            CharacterDefinition.MirrorResult[] r = new CharacterDefinition.MirrorResult[1];
            d.edit("mirror " + side.name().toLowerCase(Locale.ROOT), null,
                    () -> r[0] = d.definition().mirror(d.rig(), d.profile(), side, mask));
            return withState(d, McpAck.ok().with("written", r[0].count()).with("notes", issues(r[0].notes())));
        });
    }

    /** Fits a named profile pose ("T", "A", ...) and/or sets explicit rotations {joint: [x,y,z] | null}. */
    public Object referencePose(String fit, JsonNode rotations, boolean clear) {
        return onMain(() -> {
            CharacterDocument d = sessions().require();
            int[] turned = {-1};
            d.edit("reference pose", null, () -> {
                CharacterDefinition def = d.definition();
                if (clear) {
                    for (CharacterDefinition.JointRotation r : def.referencePose()) def.removeReferenceRotation(r.jointId());
                }
                if (fit != null && !fit.isBlank()) {
                    turned[0] = def.fitReferencePose(d.rig(), d.profile(), fit);
                }
                if (rotations != null && rotations.isObject()) {
                    rotations.fields().forEachRemaining(e -> {
                        String id = CharacterOps.joint(d, e.getKey());
                        JsonNode v = e.getValue();
                        if (v == null || v.isNull()) {
                            def.removeReferenceRotation(id);
                        } else {
                            def.setReferenceRotation(id, new CharacterDefinition.Vec3((float) v.get(0).asDouble(),
                                    (float) v.get(1).asDouble(), (float) v.get(2).asDouble()));
                        }
                    });
                }
            });
            McpAck ack = McpAck.ok().with("posedJoints", d.definition().referencePose().size())
                    .with("poses", poses(d.profile()));
            if (turned[0] >= 0) ack.with("fitted", turned[0]);
            return withState(d, ack);
        });
    }

    /** Places standard sockets ("all" or names) in one step and lists the sockets. */
    public Object sockets(List<String> place) {
        return onMain(() -> {
            CharacterDocument d = sessions().require();
            List<String> names = new ArrayList<>();
            if (place != null) {
                for (String n : place) {
                    if ("all".equals(n)) {
                        CharacterDefinition.standardSockets().forEach(s -> names.add(s.name()));
                    } else {
                        names.add(n);
                    }
                }
            }
            List<String> skipped = new ArrayList<>();
            if (!names.isEmpty()) {
                d.edit("place sockets", null, () -> {
                    for (String n : names) {
                        try {
                            d.definition().placeStandardSocket(d.rig(), n);
                        } catch (IllegalArgumentException e) {
                            if (place.contains("all")) skipped.add(n + ": " + e.getMessage());
                            else throw e;
                        }
                    }
                });
            }
            List<Map<String, Object>> standard = new ArrayList<>();
            for (CharacterDefinition.StandardSocket s : CharacterDefinition.standardSockets()) {
                standard.add(Map.of("name", s.name(), "role", s.role()));
            }
            return withState(d, McpAck.ok().with("sockets", sockets(d)).with("standard", standard)
                    .with("skipped", skipped));
        });
    }

    public Object validate() {
        return onMain(() -> {
            CharacterDocument d = sessions().require();
            List<CharacterDefinition.Issue> all = d.issues();
            long errors = all.stream().filter(i -> i.severity() == CharacterDefinition.Severity.ERROR).count();
            long warnings = all.stream().filter(i -> i.severity() == CharacterDefinition.Severity.WARNING).count();
            return McpAck.ok().with("errors", errors).with("warnings", warnings).with("issues", issues(all))
                    .with("unmappedRequired", unmappedRequired(d)).with("locked", d.definition().locked());
        });
    }

    public Object lock(boolean unlock) {
        return onMain(() -> {
            CharacterDocument d = sessions().require();
            d.edit(unlock ? "unlock" : "lock", null, () -> {
                if (unlock) d.definition().unlock();
                else d.definition().lock(d.rig(), d.profile());
            });
            return withState(d, McpAck.ok().with("locked", d.definition().locked()));
        });
    }

    public Object undo(boolean redo) {
        return onMain(() -> {
            CharacterDocument d = sessions().require();
            String label = redo ? d.session().redoLabel() : d.session().undoLabel();
            boolean done = redo ? d.redo() : d.undo();
            return withState(d, McpAck.ok().with(redo ? "redone" : "undone", done ? label : null));
        });
    }

    /**
     * Writes the .omchar: back to its own file when it has one and no path is given, else to {@code path} (sandbox
     * roots; a bare name lands in the project's Characters folder) or where the user picks ({@code prompt}).
     */
    public Object save(String path, boolean prompt, boolean overwrite) {
        if (writes == null) {
            throw new IllegalStateException("file access is unavailable (no write sandbox)");
        }
        Object[] state = onMain(() -> {
            CharacterDocument d = sessions().require();
            return new Object[] {d, d.file().orElse(null), d.displayName()};
        });
        CharacterDocument doc = (CharacterDocument) state[0];
        Path own = (Path) state[1];
        String name = state[2] + ".omchar";
        AssetWriteService.Writer writer = target -> {
            byte[] bytes = onMain(() -> doc.bytesFor(target));
            java.nio.file.Files.write(target, bytes);
            return true;
        };
        WriteOutcome outcome;
        if (path == null && !prompt && own != null) {
            outcome = writes.saveInPlace(KIND, own.toString(), writer);
        } else {
            outcome = writes.save(AssetWriteService.WriteRequest.of(KIND, path != null ? path : (prompt ? null : name),
                    prompt, overwrite, name).withDetails(List.of("Character " + state[2] + " (.omchar)")), writer);
        }
        if (!outcome.ok()) {
            return outcome;
        }
        Path saved = Path.of(outcome.path());
        onMain(() -> {
            doc.saved(saved);
            return null;
        });
        return McpAck.ok().with("saved", outcome.path()).with("promptedUser", Boolean.TRUE.equals(outcome.promptedUser()));
    }

    // ------------------------------------------------------------------------------------------------
    // Views

    private static Map<String, Object> summary(CharacterDocument d) {
        CharacterDefinition def = d.definition();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", d.displayName());
        out.put("file", d.file().map(Path::toString).orElse(null));
        out.put("model", d.modelFile().map(Path::toString).orElse(def.embeddedModel().isPresent() ? "(embedded)" : null));
        out.put("profile", d.profile().id());
        if (d.profileProblem() != null) out.put("profileProblem", d.profileProblem());
        out.put("facing", facing(d));
        out.put("locked", def.locked());
        out.put("joints", d.rig().jointCount());
        out.put("bones", d.rig().joints().stream().filter(j -> j.kind() == RigJoint.Kind.BONE).count());
        out.put("mapped", def.roles().size());
        out.put("unmappedRequired", unmappedRequired(d));
        out.put("dirty", d.isDirty());
        out.put("undo", d.session().undoLabel());
        out.put("redo", d.session().redoLabel());
        return out;
    }

    private static Object withState(CharacterDocument d, McpAck ack) {
        List<CharacterDefinition.Issue> issues = d.issues();
        ack.with("errors", issues.stream().filter(i -> i.severity() == CharacterDefinition.Severity.ERROR).count());
        ack.with("dirty", d.isDirty());
        return ack;
    }

    private static String facing(CharacterDocument d) {
        String f = d.definition().facing();
        return f == null || f.isBlank() ? "-z" : f;
    }

    private static List<String> unmappedRequired(CharacterDocument d) {
        int[] states = d.roleStates();
        List<CharacterProfile.Role> roles = d.profile().roles();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < roles.size(); i++) {
            if ((states[i] & CharacterOverlay.ROLE_REQUIRED) != 0 && (states[i] & CharacterOverlay.ROLE_MAPPED) == 0) {
                out.add(roles.get(i).name());
            }
        }
        return out;
    }

    private static List<Map<String, Object>> roles(CharacterDocument d) {
        int[] states = d.roleStates();
        Map<String, String> mapped = d.definition().roles();
        List<CharacterProfile.Role> roles = d.profile().roles();
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < roles.size(); i++) {
            CharacterProfile.Role r = roles.get(i);
            String joint = mapped.get(r.name());
            if (joint == null && !r.required()) continue; // optional unmapped roles: left out
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", r.name());
            m.put("joint", joint == null ? null : CharacterOps.nameOf(d.rig(), joint));
            if (joint != null) m.put("jointId", joint);
            m.put("state", CharacterOps.stateNames(states[i]));
            out.add(m);
        }
        for (Map.Entry<String, String> e : mapped.entrySet()) {
            if (roles.stream().noneMatch(r -> r.name().equals(e.getKey()))) {
                out.add(Map.of("role", e.getKey(), "joint", CharacterOps.nameOf(d.rig(), e.getValue()), "state",
                        List.of("not_in_profile")));
            }
        }
        return out;
    }

    private static List<Map<String, Object>> joints(CharacterDocument d) {
        Rig rig = d.rig();
        Map<String, String> roleOf = new LinkedHashMap<>();
        d.definition().roles().forEach((role, joint) -> roleOf.put(joint, role));
        List<Map<String, Object>> out = new ArrayList<>();
        for (RigJoint j : rig.joints()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", j.name());
            m.put("kind", j.kind() == RigJoint.Kind.BONE ? "bone" : "part");
            m.put("parent", j.parent() < 0 ? null : rig.joints().get(j.parent()).name());
            if (roleOf.containsKey(j.id())) m.put("role", roleOf.get(j.id()));
            out.add(m);
        }
        return out;
    }

    private static List<Map<String, Object>> pose(CharacterDocument d) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CharacterDefinition.JointRotation r : d.definition().referencePose()) {
            out.add(Map.of("joint", CharacterOps.nameOf(d.rig(), r.jointId()), "rot", vec(r.rotDeg())));
        }
        return out;
    }

    private static List<Map<String, Object>> limits(CharacterDocument d) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CharacterDefinition.JointLimit l : d.definition().limits()) {
            out.add(Map.of("joint", CharacterOps.nameOf(d.rig(), l.jointId()), "min", vec(l.minDeg()), "max", vec(l.maxDeg())));
        }
        return out;
    }

    private static List<Map<String, Object>> chains(CharacterDocument d) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CharacterDefinition.Chain c : d.definition().chains()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.name());
            m.put("start", CharacterOps.nameOf(d.rig(), c.startJointId()));
            m.put("end", CharacterOps.nameOf(d.rig(), c.endJointId()));
            if (c.goal() != null && !c.goal().isBlank()) m.put("goal", c.goal());
            out.add(m);
        }
        return out;
    }

    private static List<Map<String, Object>> goals(CharacterDocument d) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CharacterDefinition.IkGoal g : d.definition().ikGoals()) {
            out.add(Map.of("name", g.name(), "joint", CharacterOps.nameOf(d.rig(), g.jointId())));
        }
        return out;
    }

    private static List<Map<String, Object>> sockets(CharacterDocument d) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CharacterDefinition.Socket s : d.definition().sockets()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s.name());
            m.put("joint", CharacterOps.nameOf(d.rig(), s.jointId()));
            m.put("pos", vec(s.pos()));
            m.put("rot", vec(s.rotDeg()));
            if (!s.scale().equals(CharacterDefinition.Vec3.ONE)) m.put("scale", vec(s.scale()));
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> metrics(CharacterDocument d) {
        CharacterDefinition.Metrics m = d.definition().metrics();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("height", round(m.height()));
        out.put("eye_height", round(m.eyeHeight()));
        out.put("width", round(m.width()));
        out.put("hip_height", round(m.hipHeight()));
        out.put("overridden", m.overridden().stream().map(x -> x.name().toLowerCase(Locale.ROOT)).toList());
        return out;
    }

    private static Map<String, Object> profile(CharacterDocument d) {
        CharacterProfile p = d.profile();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", p.id());
        out.put("builtin", CharacterProfile.builtinIds());
        out.put("poses", poses(p));
        List<Map<String, Object>> roles = new ArrayList<>();
        for (CharacterProfile.Role r : p.roles()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", r.name());
            if (r.parent() != null) m.put("parent", r.parent());
            if (r.required()) m.put("required", true);
            if (r.side() != CharacterProfile.Side.CENTER) m.put("side", r.side().name().toLowerCase(Locale.ROOT));
            roles.add(m);
        }
        out.put("roles", roles);
        return out;
    }

    private static List<String> poses(CharacterProfile p) {
        List<String> out = new ArrayList<>();
        out.add("T");
        out.addAll(p.poseNames());
        return out;
    }

    private static List<Map<String, Object>> issues(List<CharacterDefinition.Issue> issues) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CharacterDefinition.Issue i : issues) {
            out.add(Map.of("severity", i.severity().name().toLowerCase(Locale.ROOT), "code",
                    i.code().name().toLowerCase(Locale.ROOT), "subject", i.subject(), "message", i.message()));
        }
        return out;
    }

    private static List<Double> vec(CharacterDefinition.Vec3 v) {
        return List.of(round(v.x()), round(v.y()), round(v.z()));
    }

    private static double round(float f) {
        return Math.round(f * 10000.0) / 10000.0;
    }

    // ------------------------------------------------------------------------------------------------

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
