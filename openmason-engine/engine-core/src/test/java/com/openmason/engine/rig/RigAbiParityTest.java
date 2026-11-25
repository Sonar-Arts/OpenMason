package com.openmason.engine.rig;

import com.openmason.engine.format.oma.AnimLayerMeta;
import com.openmason.engine.format.oma.OMAReader;
import com.openmason.engine.format.oma.ParsedAnimClip;
import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.format.omo.OMOReader;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * OpenMason #156 acceptance: CR ABI 2 reproduces the ABI 1 pose output BIT FOR BIT on every fixture SBE.
 *
 * <p>Every clip of every Stonebreak fixture SBE is posed as a base clip over a time sweep (wrapping, clamping,
 * negative times) under a non-trivial base matrix with head tracking; overlay stacks reuse the fixture clips as
 * OVERLAY clips (masked / unmasked, fades, priorities, partial weights). The digest of every skin, world and local
 * float is compared with {@code rig/abi1-parity.txt}, frozen from the ABI 1 library before the ABI 2 work began
 * (2026-11-24). Re-freezing is a reviewed act: {@code -Dopenmason.rig.parity.freeze=true}.
 *
 * <p>The frozen digests come from Linux x86-64 (glibc libm); other platforms only run the same-platform checks
 * (the ABI 1 entry point against the ABI 2 pose program), since sin/cos may round differently there.
 */
class RigAbiParityTest {

    private static final Path SBE_ROOT = Path.of("../../test-fixtures/stonebreak/resources/sbe");
    private static final Path GOLDEN = Path.of("src/test/resources/rig/abi1-parity.txt");
    /**
     * Fixtures whose shipped asset changed on purpose after the golden was frozen keep their frozen copy here (same
     * relative path; it replaces the fixture): the goblin, re-rigged for #160 (GoblinRerigParityTest proves the
     * re-rigged asset poses its clips like this copy).
     */
    static final Path FROZEN_ROOT = Path.of("src/test/resources/rig/frozen-fixtures");

    /** Elapsed times as fractions of the clip duration, plus absolute offsets (seconds). */
    private static final float[] FRACTIONS = {0f, 0.25f, 0.5f, 0.77f, 1f, 1.37f, 2.5f};
    private static final float[] ABSOLUTE = {0.013f, -0.4f, 0.1f};

    @BeforeEach
    void requireNative() {
        assumeTrue(CendaRig.isAvailable(), "native rig unavailable: " + CendaRig.unavailableReason());
        assumeTrue(Files.isDirectory(SBE_ROOT), "Stonebreak fixtures missing at " + SBE_ROOT.toAbsolutePath());
    }

    /** One fixture: its rig and its clips by entry name (sorted). */
    record Fixture(String name, RigModel model, Map<String, ParsedAnimClip> clips) {}

    static List<Fixture> fixtures() throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(SBE_ROOT)) {
            files = walk.filter(p -> p.toString().endsWith(".sbe")).sorted().toList();
        }
        List<Fixture> out = new ArrayList<>();
        for (Path fixture : files) {
            Path frozen = FROZEN_ROOT.resolve(SBE_ROOT.relativize(fixture));
            Fixture f = load(Files.isRegularFile(frozen) ? frozen : fixture,
                SBE_ROOT.relativize(fixture).toString().replace('\\', '/'));
            if (f != null) out.add(f);
        }
        return out;
    }

    /** One SBE's rig and clips (null when it has no model). */
    static Fixture load(Path file, String name) throws IOException {
        byte[] omo = null;
        Map<String, ParsedAnimClip> clips = new TreeMap<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(file))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (e.getName().equals("model.omo")) omo = in.readAllBytes();
                else if (e.getName().endsWith(".omanim")) clips.put(e.getName(), new OMAReader().read(in.readAllBytes()));
            }
        }
        if (omo == null) return null;
        OMOReader.ReadResult read = new OMOReader().read(new ByteArrayInputStream(omo));
        List<RigModel.PartSpec> parts = new ArrayList<>();
        for (OMOFormat.PartEntry p : read.parts()) {
            parts.add(new RigModel.PartSpec(p.id(), p.name(), p.parentId(), p.vertexCount() > 0,
                new Vector3f(p.originX(), p.originY(), p.originZ()), new Vector3f(p.posX(), p.posY(), p.posZ()),
                new Vector3f(p.rotX(), p.rotY(), p.rotZ()), new Vector3f(p.scaleX(), p.scaleY(), p.scaleZ())));
        }
        return new Fixture(name, RigModel.build(parts, read.bones(), null, null, null), clips);
    }

    /** A copy of {@code clip} as an OVERLAY layer. */
    static ParsedAnimClip asOverlay(ParsedAnimClip clip, List<String> mask, float fadeIn, float fadeOut, int priority,
                                    boolean loop) {
        return new ParsedAnimClip(clip.name() + "#overlay", clip.fps(), clip.duration(), loop, clip.tracks(),
            new AnimLayerMeta(AnimLayerMeta.LayerType.OVERLAY, mask, fadeIn, fadeOut, priority));
    }

    static float[] times(float duration) {
        float[] t = new float[FRACTIONS.length + ABSOLUTE.length];
        for (int i = 0; i < FRACTIONS.length; i++) t[i] = FRACTIONS[i] * duration;
        System.arraycopy(ABSOLUTE, 0, t, FRACTIONS.length, ABSOLUTE.length);
        return t;
    }

    static Matrix4f baseMatrix() {
        return new Matrix4f().translate(3.25f, -1.5f, 7.75f).rotateY((float) Math.toRadians(37.5)).scale(1.0625f);
    }

    /** Accumulates float bits into a SHA-256 digest. */
    static final class Digest {
        private final MessageDigest sha;
        private final byte[] buf = new byte[4];

        Digest() {
            try {
                sha = MessageDigest.getInstance("SHA-256");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        void add(float v) {
            int bits = Float.floatToRawIntBits(v);
            buf[0] = (byte) bits;
            buf[1] = (byte) (bits >>> 8);
            buf[2] = (byte) (bits >>> 16);
            buf[3] = (byte) (bits >>> 24);
            sha.update(buf);
        }

        void add(JointPalette p, int n) {
            Matrix4f m = new Matrix4f();
            for (int j = 0; j < n; j++) {
                p.skinMatrix(j, m);
                addMatrix(m);
                p.worldMatrix(j, m);
                addMatrix(m);
            }
        }

        void addMatrix(Matrix4f m) {
            float[] f = new float[16];
            m.get(f);
            for (float v : f) add(v);
        }

        void add(float[] values) {
            for (float v : values) add(v);
        }

        String hex() {
            return HexFormat.of().formatHex(sha.digest());
        }
    }

    /** A pose request: base clip + overlays (+ the head extra). */
    record Frame(ParsedAnimClip base, float baseTime, List<RigPoser.Overlay> overlays) {}

    /** Poses one frame through the path under test into {@code digest}. */
    @FunctionalInterface
    interface PosePath {
        void pose(Rig rig, Frame frame, int head, Matrix4f base, JointPalette palette, float[] locals);
    }

    /** The ABI 1 entry points: cr_rig_pose + cr_rig_local_pose. */
    static final PosePath LEGACY = (rig, frame, head, base, palette, locals) -> {
        RigPoser poser = RigPoser.local().clearExtras();
        if (head >= 0) poser.extra(head, 23f, -11f, 0f, 0f, 0f);
        poser.pose(rig, frame.base(), frame.baseTime(), frame.overlays(), base, palette);
        poser.clearExtras();
        poser.localPose(rig, frame.base(), frame.baseTime(), frame.overlays(), locals);
    };

    /**
     * The same frame as an ABI 2 pose program: sample the base (or rest), then per overlay in ascending priority
     * (stable) a clip-masked sample and an override layer at the overlay's weight x fade envelope; one
     * {@code cr_poses_evaluate}.
     */
    static final class ProgramPath implements PosePath {
        private Rig rig;
        private PoseWorkspace workspace;
        private final PoseProgram program = new PoseProgram();
        private final List<RigPoser.Overlay> sorted = new ArrayList<>();

        @Override
        public void pose(Rig rig, Frame frame, int head, Matrix4f base, JointPalette palette, float[] locals) {
            if (this.rig != rig) {
                if (workspace != null) workspace.close();
                this.rig = rig;
                this.workspace = PoseWorkspace.create(rig, 2);
            }
            program.clear();
            if (frame.base() != null) program.sample(0, frame.base(), frame.baseTime(), 0);
            else program.rest(0);
            sorted.clear();
            for (RigPoser.Overlay o : frame.overlays()) {
                if (o != null && o.clip() != null) sorted.add(o);
            }
            sorted.sort(java.util.Comparator.comparingInt(o -> o.clip().layer().priority()));  // stable
            for (RigPoser.Overlay o : sorted) {
                RigClip clip = RigClip.of(o.clip());
                float w = o.weight() * clip.weightAt(o.time());
                if (w <= 0f) continue;
                program.sample(1, clip, o.time(), PoseProgram.CLIP_MASK);
                program.layer(0, 1, null, w, PoseProgram.OVERRIDE, 0);
            }
            RigPoser poser = RigPoser.local().clearExtras();
            if (head >= 0) poser.extra(head, 23f, -11f, 0f, 0f, 0f);
            poser.evaluate(workspace, program, 0, base, palette);
            poser.clearExtras();
            workspace.get(0, locals, null);
        }
    }

    /** Every scenario of every fixture: name -> frames. */
    static Map<String, List<Frame>> scenarios(Fixture f) {
        Map<String, List<Frame>> out = new LinkedHashMap<>();
        List<ParsedAnimClip> clips = new ArrayList<>(f.clips().values());
        List<String> names = new ArrayList<>(f.clips().keySet());
        out.put("rest", List.of(new Frame(null, 0f, List.of())));
        for (int c = 0; c < clips.size(); c++) {
            ParsedAnimClip clip = clips.get(c);
            List<Frame> frames = new ArrayList<>();
            for (float t : times(clip.duration())) frames.add(new Frame(clip, t, List.of()));
            out.put("base " + names.get(c), frames);
        }
        if (clips.isEmpty()) return out;
        // Overlay stacks: each clip over the next one (cyclic), masked to a third of the joints by name.
        List<RigJoint> joints = f.model().joints();
        List<String> third = new ArrayList<>();
        for (int j = 0; j < joints.size(); j += 3) {
            String n = joints.get(j).name();
            if (n != null && !n.isEmpty()) third.add(j % 2 == 0 ? n.toUpperCase(Locale.ROOT) : n);
        }
        for (int c = 0; c < clips.size(); c++) {
            ParsedAnimClip base = clips.get(c);
            ParsedAnimClip over = clips.get((c + 1) % clips.size());
            ParsedAnimClip third2 = clips.get((c + 2) % clips.size());
            ParsedAnimClip masked = asOverlay(over, third, 0.2f, 0.3f, 2, false);
            ParsedAnimClip full = asOverlay(third2, List.of(), 0f, 0f, 1, true);
            ParsedAnimClip faded = asOverlay(over, List.of(), 0.15f, 0.25f, 0, over.loop());
            List<Frame> frames = new ArrayList<>();
            float[] ts = times(Math.max(base.duration(), 0.5f));
            for (int i = 0; i < ts.length; i++) {
                float t = ts[i];
                float w = (i % 3 == 0) ? 1f : (i % 3 == 1 ? 0.35f : 0.8f);
                frames.add(new Frame(base, t, List.of(new RigPoser.Overlay(masked, t * 0.5f, w))));
                frames.add(new Frame(base, t, List.of(
                    new RigPoser.Overlay(masked, t * 0.25f + 0.05f, w),
                    new RigPoser.Overlay(full, t, 1f - w * 0.5f),
                    new RigPoser.Overlay(faded, t * 0.75f, 1f))));
                frames.add(new Frame(null, t, List.of(new RigPoser.Overlay(faded, t, w))));
            }
            out.put("overlays " + names.get(c), frames);
        }
        return out;
    }

    /** name -> digest for every fixture scenario through {@code path}. */
    static Map<String, String> digests(PosePath path) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        Matrix4f base = baseMatrix();
        JointPalette palette = new JointPalette();
        for (Fixture f : fixtures()) {
            try (Rig rig = Rig.create(f.model().joints())) {
                int n = rig.jointCount();
                float[] locals = new float[n * 9];
                int head = rig.headJoint();
                for (Map.Entry<String, List<Frame>> s : scenarios(f).entrySet()) {
                    Digest d = new Digest();
                    for (Frame frame : s.getValue()) {
                        path.pose(rig, frame, head, base, palette, locals);
                        d.add(palette, n);
                        d.add(locals);
                    }
                    out.put(f.name() + " | " + s.getKey(), d.hex());
                }
            }
        }
        return out;
    }

    static boolean goldenPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return os.contains("linux") && (arch.equals("amd64") || arch.equals("x86_64"));
    }

    static Map<String, String> readGolden() throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : Files.readAllLines(GOLDEN, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            int at = line.lastIndexOf(" = ");
            out.put(line.substring(0, at), line.substring(at + 3));
        }
        return out;
    }

    @Test
    void poseProgramsReproduceTheAbi1Output() throws IOException {
        Map<String, String> legacy = digests(LEGACY);
        Map<String, String> program = digests(new ProgramPath());
        assertEquals(legacy, program, "the pose program differs from cr_rig_pose");
        assumeTrue(goldenPlatform(), "frozen digests are Linux x86-64 only");
        assertEquals(readGolden(), program, "the pose program differs from the frozen ABI 1 output");
    }

    @Test
    void abi1EntryPointsMatchTheFrozenAbi1Output() throws IOException {
        Map<String, String> now = digests(LEGACY);
        assertTrue(now.size() > 16, "expected scenarios for every fixture, got " + now.size());
        if (Boolean.getBoolean("openmason.rig.parity.freeze")) {
            StringBuilder sb = new StringBuilder();
            sb.append("# cr_rig_pose / cr_rig_local_pose output digests (SHA-256 of the float bits) per fixture scenario.\n");
            sb.append("# Frozen from the CR ABI 1 library on Linux x86-64; see RigAbiParityTest.\n");
            now.forEach((k, v) -> sb.append(k).append(" = ").append(v).append('\n'));
            Files.createDirectories(GOLDEN.getParent());
            Files.writeString(GOLDEN, sb.toString(), StandardCharsets.UTF_8);
            return;
        }
        assumeTrue(goldenPlatform(), "frozen digests are Linux x86-64 only");
        Map<String, String> golden = readGolden();
        assertEquals(golden.keySet(), now.keySet(), "scenario set changed");
        List<String> mismatched = new ArrayList<>();
        golden.forEach((k, v) -> {
            if (!v.equals(now.get(k))) mismatched.add(k);
        });
        assertTrue(mismatched.isEmpty(), mismatched.size() + " scenarios differ from ABI 1: " + mismatched);
    }
}
