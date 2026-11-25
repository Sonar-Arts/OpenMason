package com.openmason.engine.rendering.model.draw;

import com.openmason.engine.character.CharacterDefinition;
import com.openmason.engine.character.CharacterProfile;
import com.openmason.engine.entity.model.EntityFace;
import com.openmason.engine.entity.model.EntityGeometry;
import com.openmason.engine.entity.model.EntityModelLoader;
import com.openmason.engine.entity.model.EntityPart;
import com.openmason.engine.entity.model.MaterialImage;
import com.openmason.engine.format.omo.OMOReader;
import com.openmason.engine.rendering.shadow.CascadeCalculator;
import com.openmason.engine.rendering.shadow.CascadedShadowMap;
import com.openmason.engine.rendering.shadow.ShadowCascade;
import com.openmason.engine.rendering.shadow.ShadowSettings;
import com.openmason.engine.rendering.shadow.ShadowUniforms;
import com.openmason.engine.rig.BoneMask;
import com.openmason.engine.rig.CendaRig;
import com.openmason.engine.rig.FirstPersonProjection;
import com.openmason.engine.rig.JointPalette;
import com.openmason.engine.rig.Rig;
import com.openmason.engine.rig.RigPoser;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * OpenMason #161 acceptance on real GL: one character, three views, drawn by the engine model renderer with no
 * first-person code in it (the first-person palette carries the pull, the first-person projection the FOV and depth
 * slab). Needs a display: opt in with {@code -Dopenmason.model.gl=true}.
 *
 * <p>Character: the re-rigged Stonebreak goblin (its arm and head parts hang under real bones), auto-mapped onto the
 * humanoid profile, arms raised forward, an {@code eyes} socket inside its head box. Checks:
 * <ul>
 *   <li>first-person arms are drawn in front of a wall 0.1 ahead of the eyes (the same arms drawn as world geometry
 *       are behind it), and their image does not change with the world FOV;</li>
 *   <li>the world-space body, never drawn in the owner's main view, still darkens the floor through the cascaded
 *       shadow map;</li>
 *   <li>the head is hidden from the owner (the camera sits inside it) and visible to a second camera.</li>
 * </ul>
 */
class FirstPersonGlTest {

    private static final Path GOBLIN = Path.of("../../test-fixtures/stonebreak/resources/sbe/Mobs/SB_Goblin.sbe");
    private static final int SIZE = 128;
    private static final Vector4f RED = new Vector4f(1, 0, 0, 1);
    private static final Vector4f BLUE = new Vector4f(0, 0, 1, 1);
    private static final Vector4f GREEN = new Vector4f(0, 1, 0, 1);

    private long window;
    private int fbo, colorTex, depthRb;
    private ModelRenderer renderer;
    private UploadedModel goblin;
    private Rig rig;
    private CharacterDefinition character;
    private CharacterProfile humanoid;
    private Vector3f forward;
    private float[] armsForward;

    @BeforeEach
    void createContext() throws Exception {
        assumeTrue(Boolean.getBoolean("openmason.model.gl"), "opt in with -Dopenmason.model.gl=true");
        assumeTrue(CendaRig.isAvailable(), "native rig unavailable");
        assumeTrue(Files.isRegularFile(GOBLIN), "goblin fixture missing at " + GOBLIN.toAbsolutePath());
        assertTrue(glfwInit(), "GLFW display unavailable");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        window = glfwCreateWindow(16, 16, "First-person test", 0, 0);
        assertNotEquals(0, window);
        glfwMakeContextCurrent(window);
        GL.createCapabilities();

        colorTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, colorTex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, SIZE, SIZE, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        glBindTexture(GL_TEXTURE_2D, 0);
        depthRb = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, depthRb);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, SIZE, SIZE);
        fbo = glGenFramebuffers();
        bindTarget();
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colorTex, 0);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depthRb);
        assertEquals(GL_FRAMEBUFFER_COMPLETE, glCheckFramebufferStatus(GL_FRAMEBUFFER));

        renderer = new ModelRenderer();
        renderer.initialize();
        assertTrue(renderer.isInitialized());

        EntityGeometry geometry = EntityModelLoader.buildGeometry(new OMOReader().read(new ByteArrayInputStream(omo())));
        goblin = UploadedModel.upload(geometry);
        rig = geometry.rig().rig();
        humanoid = CharacterProfile.builtin("humanoid");
        character = CharacterDefinition.create();
        character.setSkeleton(rig);
        character.autoMap(rig, humanoid);
        String facing = character.facing();
        forward = "+z".equals(facing) ? new Vector3f(0, 0, 1) : new Vector3f(0, 0, -1);
        assumeTrue(facing == null || facing.isEmpty() || facing.endsWith("z"), "facing " + facing);

        // The eyes socket: the centre of the head box, rest model space (inside the head).
        int headPart = rig.find("head");
        int[] partOf = geometry.rig().partJointOfVertex();
        Vector3f lo = new Vector3f(Float.MAX_VALUE), hi = new Vector3f(-Float.MAX_VALUE);
        float[] v = geometry.vertices();
        for (int i = 0; i < partOf.length; i++) {
            if (partOf[i] != headPart) continue;
            Vector3f p = new Vector3f(v[i * 3], v[i * 3 + 1], v[i * 3 + 2]);
            lo.min(p);
            hi.max(p);
        }
        assertTrue(hi.x > lo.x, "head part has vertices");
        Vector3f centre = new Vector3f(lo).add(hi).mul(0.5f);
        character.setSocket(new CharacterDefinition.Socket("eyes", rig.joints().get(headPart).id(),
            new CharacterDefinition.Vec3(centre.x, centre.y, centre.z), new CharacterDefinition.Vec3(0, 0, 0),
            new CharacterDefinition.Vec3(1, 1, 1)));

        // Arms raised forward: turn each upper-arm role joint by +-90 about X, whichever points the hand ahead.
        float[] rest = RigPoser.local().localPose(rig, null, 0f, List.of(), new float[rig.jointCount() * 9]);
        armsForward = rest.clone();
        for (String side : new String[]{"l", "r"}) {
            int arm = joint("upper_arm_" + side), hand = joint("hand_" + side) >= 0 ? joint("hand_" + side) : joint("lower_arm_" + side);
            assertTrue(arm >= 0 && hand >= 0, "arm roles on side " + side);
            float best = 0f, bestReach = -Float.MAX_VALUE;
            for (float angle : new float[]{90f, -90f}) {
                float[] trial = armsForward.clone();
                trial[arm * 9 + 3] = rest[arm * 9 + 3] + angle;
                JointPalette p = RigPoser.local().poseLocals(rig, trial, null, new JointPalette());
                Vector3f a = p.worldMatrix(arm, new Matrix4f()).getTranslation(new Vector3f());
                Vector3f h = p.worldMatrix(hand, new Matrix4f()).getTranslation(new Vector3f());
                float reach = h.sub(a).dot(forward);
                if (reach > bestReach) {
                    bestReach = reach;
                    best = angle;
                }
            }
            armsForward[arm * 9 + 3] = rest[arm * 9 + 3] + best;
        }
    }

    @AfterEach
    void destroyContext() {
        if (window == 0) return;
        if (goblin != null) goblin.close();
        if (character != null) character.close();
        if (humanoid != null) humanoid.close();
        if (rig != null) rig.close();
        if (renderer != null) renderer.cleanup();
        glDeleteFramebuffers(fbo);
        glDeleteRenderbuffers(depthRb);
        glDeleteTextures(colorTex);
        int error = glGetError();
        GL.setCapabilities(null);
        glfwDestroyWindow(window);
        glfwTerminate();
        assertEquals(GL_NO_ERROR, error, "GL error during the test");
    }

    private static byte[] omo() throws Exception {
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(GOBLIN))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (e.getName().equals("model.omo")) return in.readAllBytes();
            }
        }
        throw new AssertionError("no model.omo in the goblin");
    }

    private int joint(String role) {
        String id = character.roles().get(role);
        return id == null ? -1 : rig.find(id);
    }

    private void bindTarget() {
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glViewport(0, 0, SIZE, SIZE);
    }

    private static void clear() {
        glClearColor(0, 0, 0, 0);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    }

    private static byte[] read() {
        ByteBuffer px = org.lwjgl.BufferUtils.createByteBuffer(SIZE * SIZE * 4);
        glReadPixels(0, 0, SIZE, SIZE, GL_RGBA, GL_UNSIGNED_BYTE, px);
        byte[] out = new byte[SIZE * SIZE * 4];
        px.get(out);
        return out;
    }

    /** {@code -Dopenmason.model.gl.dump=<dir>}: writes the frame as a PNG there (for eyeballing). */
    private static void dump(byte[] px, String name) {
        String dir = System.getProperty("openmason.model.gl.dump");
        if (dir == null || dir.isEmpty()) return;
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(SIZE, SIZE, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int i = (y * SIZE + x) * 4;
                int a = Math.max(px[i + 3] & 0xFF, 40);
                img.setRGB(x, SIZE - 1 - y, a << 24 | (px[i] & 0xFF) << 16 | (px[i + 1] & 0xFF) << 8 | (px[i + 2] & 0xFF));
            }
        }
        try {
            Files.createDirectories(Path.of(dir));
            javax.imageio.ImageIO.write(img, "png", Path.of(dir, name + ".png").toFile());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static int count(byte[] px, int r, int g, int b) {
        int n = 0;
        for (int i = 0; i < px.length; i += 4) {
            if (Math.abs((px[i] & 0xFF) - r) < 8 && Math.abs((px[i + 1] & 0xFF) - g) < 8 && Math.abs((px[i + 2] & 0xFF) - b) < 8
                && (px[i + 3] & 0xFF) > 0) {
                n++;
            }
        }
        return n;
    }

    private static int[] pixel(byte[] px, int x, int y) {
        int i = (y * SIZE + x) * 4;
        return new int[]{px[i] & 0xFF, px[i + 1] & 0xFF, px[i + 2] & 0xFF, px[i + 3] & 0xFF};
    }

    /** A quad (two triangles) as its own one-part model; textured white when {@code lit}. */
    private static UploadedModel quad(Vector3f a, Vector3f b, Vector3f c, Vector3f d, boolean lit) {
        float[] v = {a.x, a.y, a.z, b.x, b.y, b.z, c.x, c.y, c.z, d.x, d.y, d.z};
        float[] uv = {0, 0, 1, 0, 1, 1, 0, 1};
        int[] idx = {0, 1, 2, 0, 2, 3};
        List<EntityPart> parts = List.of(new EntityPart("q", "quad", null, new Vector3f(), new Vector3f(), new Vector3f(),
            new Vector3f(1), List.of(new EntityFace(0, lit ? 1 : 0, 0, 6))));
        Map<Integer, MaterialImage> materials = lit
            ? Map.of(1, new MaterialImage(1, 1, new byte[]{(byte) 255, (byte) 255, (byte) 255, (byte) 255}))
            : Map.of();
        return UploadedModel.upload(new EntityGeometry(v, uv, idx, parts, materials, 0f, List.of()));
    }

    private Matrix4f eyesView(JointPalette body, float pitchDeg, Vector3f outEye) {
        assertEquals(CharacterDefinition.EYES_SOCKET, character.eyesPoint(rig, body, outEye));
        Vector3f look = new Vector3f(forward).rotateAxis((float) Math.toRadians(pitchDeg),
            forward.z < 0 ? 1 : -1, 0, 0);
        return new Matrix4f().lookAt(outEye, new Vector3f(outEye).add(look), new Vector3f(0, 1, 0));
    }

    private JointPalette pose(Matrix4f base) {
        return RigPoser.local().poseLocals(rig, armsForward, base, new JointPalette());
    }

    @Test
    void firstPersonArmsStayInFrontOfAWallATenthOfABlockAhead() {
        JointPalette body = pose(null);
        Vector3f eye = new Vector3f();
        Matrix4f view = eyesView(body, -25f, eye);
        Vector3f c = new Vector3f(forward).mul(0.1f).add(eye);
        Vector3f side = new Vector3f(forward).cross(0, 1, 0).normalize().mul(3f);
        UploadedModel wall = quad(new Vector3f(c).sub(side).add(0, -3, 0), new Vector3f(c).add(side).add(0, -3, 0),
            new Vector3f(c).add(side).add(0, 3, 0), new Vector3f(c).sub(side).add(0, 3, 0), false);
        try (wall; BoneMask fpMask = BoneMask.create(rig, 0f)) {
            assertEquals(0, character.viewMask(rig, CharacterDefinition.VIEW_FIRST_PERSON, fpMask));
            Matrix4f worldProj = new Matrix4f().perspective((float) Math.toRadians(90), 1f, 0.05f, 100f);
            FirstPersonProjection fpp = new FirstPersonProjection().fovY(75f);
            Matrix4f fpProj = new Matrix4f(), pull = new Matrix4f();
            fpp.compute(view, worldProj, fpProj, pull);
            JointPalette fp = pose(pull).hide(rig, fpMask);
            JointPalette world = pose(null).hide(rig, fpMask);
            JointPalette identity = JointPalette.single(new Matrix4f());

            bindTarget();
            clear();
            renderer.drawFlat(wall.mesh(), wall.plan(), identity, view, worldProj, BLUE);
            renderer.drawFlat(goblin.mesh(), goblin.plan(), fp, view, fpProj, RED);
            byte[] firstPerson = read();
            dump(firstPerson, "first-person-over-wall");
            int armPixels = count(firstPerson, 255, 0, 0);

            clear();
            renderer.drawFlat(wall.mesh(), wall.plan(), identity, view, worldProj, BLUE);
            renderer.drawFlat(goblin.mesh(), goblin.plan(), world, view, worldProj, RED);
            byte[] worldFrame = read();
            dump(worldFrame, "world-arms-behind-wall");
            int clipped = count(worldFrame, 255, 0, 0);

            // Only the arms: the rest of the body (around the camera) is hidden in the first-person view.
            clear();
            renderer.drawFlat(goblin.mesh(), goblin.plan(), fp, view, fpProj, RED);
            int alone = count(read(), 255, 0, 0);

            System.out.printf("[first-person-gl] arm pixels: first person %d (alone %d), as world geometry %d%n",
                armPixels, alone, clipped);
            assertTrue(armPixels > 150, "first-person arms visible in front of the wall: " + armPixels);
            assertEquals(alone, armPixels, "the wall hides none of the first-person arms");
            assertTrue(clipped < armPixels / 10, "the same arms as world geometry are behind the wall: " + clipped);

            // The first-person image does not depend on the world FOV.
            for (float worldFov : new float[]{60f, 110f}) {
                Matrix4f otherWorld = new Matrix4f().perspective((float) Math.toRadians(worldFov), 1f, 0.05f, 100f);
                Matrix4f otherProj = new Matrix4f(), otherPull = new Matrix4f();
                fpp.compute(view, otherWorld, otherProj, otherPull);
                clear();
                renderer.drawFlat(goblin.mesh(), goblin.plan(), pose(otherPull).hide(rig, fpMask), view, otherProj, RED);
                assertArrayEquals(redMask(alone(fp, view, fpProj)), redMask(read()), "world FOV " + worldFov);
            }
        }
    }

    private byte[] alone(JointPalette fp, Matrix4f view, Matrix4f proj) {
        clear();
        renderer.drawFlat(goblin.mesh(), goblin.plan(), fp, view, proj, RED);
        return read();
    }

    private static boolean[] redMask(byte[] px) {
        boolean[] m = new boolean[px.length / 4];
        for (int i = 0; i < m.length; i++) m[i] = (px[i * 4] & 0xFF) > 128;
        return m;
    }

    private static void assertArrayEquals(boolean[] a, boolean[] b, String message) {
        int diff = 0;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) diff++;
        assertEquals(0, diff, message + ": " + diff + " pixels differ");
    }

    @Test
    void theWorldSpaceBodyCastsAShadowTheOwnerSees() {
        JointPalette body = pose(null);
        Vector3f eye = new Vector3f();
        Matrix4f view = eyesView(body, -70f, eye);
        float floorY = Float.MAX_VALUE;
        Matrix4f m = new Matrix4f();
        for (int j = 0; j < rig.jointCount(); j++) floorY = Math.min(floorY, body.worldMatrix(j, m).m31());
        floorY -= 0.05f;
        UploadedModel floor = quad(new Vector3f(-4, floorY, 4), new Vector3f(4, floorY, 4), new Vector3f(4, floorY, -4),
            new Vector3f(-4, floorY, -4), true);
        ShadowSettings settings = ShadowSettings.defaults();
        try (floor; CascadedShadowMap csm = new CascadedShadowMap(settings.resolution(), ShadowSettings.CASCADE_COUNT)) {
            Matrix4f worldProj = new Matrix4f().perspective((float) Math.toRadians(90), 1f, 0.05f, 100f);
            // The sun behind the camera, high: the body's shadow falls forward, in front of the owner's feet.
            Vector3f sun = new Vector3f(forward).negate().mul(0.35f).add(0, 1, 0).normalize();
            ShadowCascade[] cascades = new ShadowCascade[ShadowSettings.CASCADE_COUNT];
            for (int i = 0; i < cascades.length; i++) cascades[i] = new ShadowCascade();
            new CascadeCalculator().updateRelative(cascades, view, worldProj, sun, settings, null);
            int unit = renderer.extension().shadowTextureUnit();
            ModelLighting lighting = new ModelLighting(1f, sun, 1f, 0f, 0f, new Vector3f(), eye, new Vector3f(1));

            byte[] shadowed = floorPass(floor, csm, cascades, body, view, worldProj, lighting, sun, unit, settings);
            byte[] open = floorPass(floor, csm, cascades, null, view, worldProj, lighting, sun, unit, settings);
            dump(shadowed, "owner-floor-with-body-shadow");
            dump(open, "owner-floor-no-caster");
            int darker = 0, lit = 0;
            for (int i = 0; i < open.length; i += 4) {
                int a = open[i] & 0xFF, b = shadowed[i] & 0xFF;
                if (a > 0) lit++;
                if (a - b > 40) darker++;
            }
            System.out.printf("[first-person-gl] floor pixels %d, in the body's shadow %d%n", lit, darker);
            assertTrue(lit > SIZE * SIZE / 4, "the floor fills the view");
            assertTrue(darker > 100, "the world-space body's shadow is on the floor: " + darker);
        }
    }

    private byte[] floorPass(UploadedModel floor, CascadedShadowMap csm, ShadowCascade[] cascades, JointPalette caster, Matrix4f view,
                             Matrix4f proj, ModelLighting lighting, Vector3f sun, int unit, ShadowSettings settings) {
        for (int i = 0; i < cascades.length; i++) {
            csm.beginCascade(i);
            if (caster != null) {
                renderer.drawFlat(goblin.mesh(), goblin.plan(), caster, cascades[i].lightViewRender,
                    cascades[i].lightProj, GREEN);
            }
        }
        csm.endShadowPass();
        bindTarget();
        clear();
        // The owner's main view draws only the floor: the world-space body is hidden from it.
        renderer.drawLit(floor.mesh(), floor.plan(), floor, JointPalette.single(new Matrix4f()),
            view, proj, lighting, shader -> {
                csm.bindForSampling(unit);
                ShadowUniforms.apply(shader, cascades, sun, 1f, settings.pcfRadius(), unit);
            });
        return read();
    }

    @Test
    void theHeadIsHiddenFromTheOwnerAndVisibleToASecondCamera() {
        JointPalette body = pose(null);
        Vector3f eye = new Vector3f();
        Matrix4f ownerView = eyesView(body, 0f, eye);
        Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(90), 1f, 0.05f, 100f);
        try (BoneMask headless = BoneMask.create(rig, 1f)) {
            assertEquals(0, character.viewMask(rig, CharacterDefinition.VIEW_HEADLESS, headless));
            JointPalette owner = new JointPalette().copyFrom(body).hide(rig, headless);
            int c = SIZE / 2;

            bindTarget();
            clear();
            renderer.drawFlat(goblin.mesh(), goblin.plan(), body, ownerView, proj, GREEN);
            int[] inside = pixel(read(), c, c);
            clear();
            renderer.drawFlat(goblin.mesh(), goblin.plan(), owner, ownerView, proj, GREEN);
            int[] hidden = pixel(read(), c, c);
            assertEquals(255, inside[1], "control: without hiding, the camera sits inside the head");
            assertEquals(0, hidden[3], "the owner does not see its own head");

            // A second camera in front of the character, looking back at the head.
            Vector3f other = new Vector3f(forward).mul(2.5f).add(eye);
            Matrix4f otherView = new Matrix4f().lookAt(other, eye, new Vector3f(0, 1, 0));
            clear();
            renderer.drawFlat(goblin.mesh(), goblin.plan(), body, otherView, proj, GREEN);
            byte[] second = read();
            dump(second, "second-camera-body");
            int[] seen = pixel(second, c, c);
            clear();
            renderer.drawFlat(goblin.mesh(), goblin.plan(), owner, otherView, proj, GREEN);
            byte[] headlessFrame = read();
            dump(headlessFrame, "second-camera-headless-palette");
            int[] ownerOnly = pixel(headlessFrame, c, c);
            assertEquals(255, seen[1], "the second camera sees the head");
            assertEquals(0, ownerOnly[3], "the owner's headless palette really removed it (from any camera)");
        }
    }
}
