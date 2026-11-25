package com.openmason.engine.rendering.model.draw;

import com.openmason.engine.character.Appearance;
import com.openmason.engine.character.CharacterDefinition;
import com.openmason.engine.character.CharacterProfile;
import com.openmason.engine.character.Garment;
import com.openmason.engine.entity.model.EntityFace;
import com.openmason.engine.entity.model.EntityGeometry;
import com.openmason.engine.entity.model.EntityModelLoader;
import com.openmason.engine.entity.model.EntityPart;
import com.openmason.engine.entity.model.MaterialImage;
import com.openmason.engine.format.omo.OMOReader;
import com.openmason.engine.rig.CendaRig;
import com.openmason.engine.rig.JointPalette;
import com.openmason.engine.rig.Rig;
import com.openmason.engine.rig.RigJoint;
import com.openmason.engine.rig.RigPoser;
import com.openmason.engine.rig.Skin;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * OpenMason #162 on real GL: the engine model renderer applies a natively resolved appearance - hidden parts issue
 * no draw calls, materials are dyed whole or through an RGBA dye mask - and a skinned garment draws with the body's
 * own joint palette. Needs a display: opt in with {@code -Dopenmason.model.gl=true} (PNGs with
 * {@code -Dopenmason.model.gl.dump=<dir>}).
 */
class AppearanceGlTest {

    private static final Path GOBLIN = Path.of("../../test-fixtures/stonebreak/resources/sbe/Mobs/SB_Goblin.sbe");
    private static final int SIZE = 128;

    private long window;
    private int fbo, colorTex, depthRb;
    private ModelRenderer renderer;
    private final List<AutoCloseable> closers = new ArrayList<>();

    @BeforeEach
    void createContext() {
        assumeTrue(Boolean.getBoolean("openmason.model.gl"), "opt in with -Dopenmason.model.gl=true");
        assumeTrue(CendaRig.isAvailable(), "native rig unavailable");
        assertTrue(glfwInit(), "GLFW display unavailable");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        window = glfwCreateWindow(16, 16, "Appearance test", 0, 0);
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
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colorTex, 0);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depthRb);
        assertEquals(GL_FRAMEBUFFER_COMPLETE, glCheckFramebufferStatus(GL_FRAMEBUFFER));
        glViewport(0, 0, SIZE, SIZE);
        renderer = new ModelRenderer();
        renderer.initialize();
    }

    @AfterEach
    void destroyContext() throws Exception {
        if (window == 0) return;
        for (int i = closers.size() - 1; i >= 0; i--) closers.get(i).close();
        renderer.cleanup();
        glDeleteFramebuffers(fbo);
        glDeleteRenderbuffers(depthRb);
        glDeleteTextures(colorTex);
        int error = glGetError();
        GL.setCapabilities(null);
        glfwDestroyWindow(window);
        glfwTerminate();
        assertEquals(GL_NO_ERROR, error, "GL error during the test");
    }

    private <T extends AutoCloseable> T keep(T c) {
        closers.add(c);
        return c;
    }

    private static EntityGeometry goblinGeometry() throws Exception {
        assumeTrue(Files.isRegularFile(GOBLIN), "goblin fixture missing");
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(GOBLIN))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (e.getName().equals("model.omo")) {
                    return EntityModelLoader.buildGeometry(new OMOReader().read(new ByteArrayInputStream(in.readAllBytes())));
                }
            }
        }
        throw new AssertionError("no model.omo");
    }

    private static void clear() {
        glClearColor(0, 0, 0, 0);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    }

    private static byte[] read(String dumpName) {
        ByteBuffer px = org.lwjgl.BufferUtils.createByteBuffer(SIZE * SIZE * 4);
        glReadPixels(0, 0, SIZE, SIZE, GL_RGBA, GL_UNSIGNED_BYTE, px);
        byte[] out = new byte[SIZE * SIZE * 4];
        px.get(out);
        String dir = System.getProperty("openmason.model.gl.dump");
        if (dir != null && !dir.isEmpty()) {
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(SIZE, SIZE, java.awt.image.BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    int i = (y * SIZE + x) * 4;
                    img.setRGB(x, SIZE - 1 - y, (out[i] & 0xFF) << 16 | (out[i + 1] & 0xFF) << 8 | (out[i + 2] & 0xFF));
                }
            }
            try {
                Files.createDirectories(Path.of(dir));
                javax.imageio.ImageIO.write(img, "png", Path.of(dir, "appearance-" + dumpName + ".png").toFile());
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
        return out;
    }

    private static int drawn(byte[] px) {
        int n = 0;
        for (int i = 3; i < px.length; i += 4) if ((px[i] & 0xFF) > 0) n++;
        return n;
    }

    /** A front camera on the goblin (it faces -Z): from -Z, looking at its chest. */
    private static Matrix4f frontView(Rig rig, JointPalette body) {
        Vector3f lo = new Vector3f(Float.MAX_VALUE), hi = new Vector3f(-Float.MAX_VALUE);
        Matrix4f m = new Matrix4f();
        for (int j = 0; j < rig.jointCount(); j++) {
            Vector3f p = body.worldMatrix(j, m).getTranslation(new Vector3f());
            lo.min(p);
            hi.max(p);
        }
        Vector3f centre = new Vector3f(lo).add(hi).mul(0.5f);
        float height = hi.y - lo.y;
        return new Matrix4f().lookAt(new Vector3f(centre).add(0, 0, -1.6f * height), centre, new Vector3f(0, 1, 0));
    }

    private static final Matrix4f PROJ = new Matrix4f().perspective((float) Math.toRadians(60), 1f, 0.05f, 100f);

    @Test
    void hiddenPartsDrawNothingAndDyesTintTheLitPass() throws Exception {
        EntityGeometry geometry = goblinGeometry();
        UploadedModel goblin = keep(UploadedModel.upload(geometry));
        Rig rig = geometry.rig().rig();
        CharacterProfile humanoid = keep(CharacterProfile.builtin("humanoid"));
        CharacterDefinition def = keep(CharacterDefinition.create());
        def.setSkeleton(rig);
        def.autoMap(rig, humanoid);
        StringBuilder parts = new StringBuilder();
        for (String name : List.of("head", "snout", "brow", "eye_l", "eye_r", "ear_l", "ear_r")) {
            if (parts.length() > 0) parts.append(", ");
            parts.append('"').append(rig.joints().get(rig.find(name)).id()).append('"');
        }
        StringBuilder materials = new StringBuilder();
        for (int id : goblin.plan().materialIds()) {
            if (materials.length() > 0) materials.append(", ");
            materials.append("{\"id\": ").append(id).append(", \"tint\": \"skin\"}");
        }
        def.setAppearanceJson("""
            {"tints": [{"name": "skin"}], "materials": [%s],
             "groups": [{"name": "head", "parts": [%s]}]}
            """.formatted(materials, parts));
        Appearance app = keep(Appearance.create(def, rig));
        JointPalette body = RigPoser.local().poseLocals(rig, null, null, new JointPalette());
        Matrix4f view = frontView(rig, body);
        ModelLighting lighting = ModelLighting.preview(view);

        // Visibility: the head group off -> its parts issue no draws.
        clear();
        renderer.drawFlat(goblin.mesh(), goblin.plan(), body, view, PROJ, new Vector4f(0, 1, 0, 1));
        int all = drawn(read("all"));
        app.resolve(keep(app.defaults()).setBool("head", false));
        ModelAppearance headless = new ModelAppearance().setVisible(app.visible(null));
        clear();
        renderer.drawFlat(goblin.mesh(), goblin.plan(), body, view, PROJ, new Vector4f(0, 1, 0, 1), headless);
        int without = drawn(read("headless"));
        System.out.printf("[appearance-gl] goblin pixels %d, head group hidden %d%n", all, without);
        assertTrue(all > 800 && without < all - 150, all + " -> " + without);

        // Dye: every material dyed pure red -> the lit goblin has no green or blue left.
        clear();
        renderer.drawLit(goblin.mesh(), goblin.plan(), goblin, body, view, PROJ, lighting, null, null);
        byte[] plain = read("plain");
        app.resolve(keep(app.defaults()).setColor("skin", 1, 0, 0));
        ModelAppearance red = new ModelAppearance();
        for (Appearance.DyedMaterial m : app.materials()) red.setDye(m.materialId(), 0, m.tints());
        clear();
        renderer.drawLit(goblin.mesh(), goblin.plan(), goblin, body, view, PROJ, lighting, null, red);
        byte[] dyed = read("dyed");
        int lit = 0, greenBefore = 0, greenAfter = 0;
        for (int i = 0; i < dyed.length; i += 4) {
            if ((dyed[i + 3] & 0xFF) == 0) continue;
            lit++;
            greenBefore += plain[i + 1] & 0xFF;
            greenAfter += (dyed[i + 1] & 0xFF) + (dyed[i + 2] & 0xFF);
        }
        System.out.printf("[appearance-gl] dyed %d pixels: green before %d, green+blue after %d%n", lit, greenBefore, greenAfter);
        assertTrue(lit > 800 && greenBefore > lit * 10 && greenAfter == 0);
    }

    @Test
    void aDyeMaskSplitsAMaterialBetweenTwoTints() {
        // A white quad facing the camera, UVs 0..1; mask: left half R (tint 0 = red), right half G (tint 1 = blue).
        float[] v = {-1, -1, 0, 1, -1, 0, 1, 1, 0, -1, 1, 0};
        float[] uv = {0, 0, 1, 0, 1, 1, 0, 1};
        int[] idx = {0, 1, 2, 0, 2, 3};
        List<EntityPart> parts = List.of(new EntityPart("q", "quad", null, new Vector3f(), new Vector3f(), new Vector3f(),
            new Vector3f(1), List.of(new EntityFace(0, 1, 0, 6))));
        byte[] white = {(byte) 255, (byte) 255, (byte) 255, (byte) 255};
        UploadedModel quad = keep(UploadedModel.upload(new EntityGeometry(v, uv, idx, parts,
            Map.of(1, new MaterialImage(1, 1, white)), 0f, List.of())));
        int mask = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, mask);
        ByteBuffer m = org.lwjgl.BufferUtils.createByteBuffer(8);
        m.put(new byte[]{(byte) 255, 0, 0, 0, 0, (byte) 255, 0, 0}).flip();
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 2, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, m);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glBindTexture(GL_TEXTURE_2D, 0);
        try {
            Matrix4f view = new Matrix4f().lookAt(0, 0, 3, 0, 0, 0, 0, 1, 0);
            ModelAppearance a = new ModelAppearance()
                .setDye(1, mask, new float[]{1, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1});
            clear();
            renderer.drawLit(quad.mesh(), quad.plan(), quad, JointPalette.single(new Matrix4f()), view, PROJ,
                ModelLighting.preview(view), null, a);
            byte[] px = read("mask");
            int left = (SIZE / 2 * SIZE + SIZE / 4) * 4, right = (SIZE / 2 * SIZE + 3 * SIZE / 4) * 4;
            System.out.printf("[appearance-gl] mask: left %d,%d,%d right %d,%d,%d%n", px[left] & 0xFF, px[left + 1] & 0xFF,
                px[left + 2] & 0xFF, px[right] & 0xFF, px[right + 1] & 0xFF, px[right + 2] & 0xFF);
            assertTrue((px[left] & 0xFF) > 100 && (px[left + 1] & 0xFF) == 0 && (px[left + 2] & 0xFF) == 0, "left is red");
            assertTrue((px[right] & 0xFF) == 0 && (px[right + 1] & 0xFF) == 0 && (px[right + 2] & 0xFF) > 100, "right is blue");
        } finally {
            glDeleteTextures(mask);
        }
    }

    @Test
    void aSkinnedGarmentDrawsWithTheBodysPalette() throws Exception {
        EntityGeometry geometry = goblinGeometry();
        UploadedModel goblin = keep(UploadedModel.upload(geometry));
        Rig rig = geometry.rig().rig();
        CharacterProfile humanoid = keep(CharacterProfile.builtin("humanoid"));
        CharacterDefinition def = keep(CharacterDefinition.create());
        def.setSkeleton(rig);
        def.autoMap(rig, humanoid);
        int shoulder = rig.find("shoulder_l"), elbow = rig.find("elbow_l"), wrist = rig.find("wrist_l");
        Matrix4f[] world = new Matrix4f[rig.jointCount()], child = new Matrix4f[rig.jointCount()];
        for (int i = 0; i < world.length; i++) {
            world[i] = new Matrix4f();
            child[i] = new Matrix4f();
        }
        rig.restWorld(world, child);
        Vector3f a = world[shoulder].getTranslation(new Vector3f()), b = world[elbow].getTranslation(new Vector3f()),
            c = world[wrist].getTranslation(new Vector3f());
        // A closed sleeve tube around the arm (radius 0.2, so it covers the arm's boxes).
        Vector3f[] path = {a, new Vector3f(a).lerp(b, 0.5f), b, new Vector3f(b).lerp(c, 0.5f), c};
        int sides = 12;
        float[] pos = new float[path.length * sides * 3];
        for (int r = 0; r < path.length; r++) {
            for (int s = 0; s < sides; s++) {
                double ang = 2 * Math.PI * s / sides;
                int o = (r * sides + s) * 3;
                pos[o] = path[r].x + 0.2f * (float) Math.cos(ang);
                pos[o + 1] = path[r].y;
                pos[o + 2] = path[r].z + 0.2f * (float) Math.sin(ang);
            }
        }
        List<Integer> tri = new ArrayList<>();
        for (int r = 0; r + 1 < path.length; r++) {
            for (int s = 0; s < sides; s++) {
                int i0 = r * sides + s, i1 = r * sides + (s + 1) % sides;
                tri.addAll(List.of(i0, i0 + sides, i1, i1, i0 + sides, i1 + sides));
            }
        }
        int[] idx = tri.stream().mapToInt(Integer::intValue).toArray();
        int n = pos.length / 3;
        Skin skin = keep(Skin.create(n));
        skin.autoWeights(rig, pos, 0, n, new int[]{shoulder, elbow, wrist}, 2f, 0.01f);
        Garment sleeve = keep(Garment.fit(def, rig, null, null, rig.joints().stream().map(RigJoint::name).toList(), pos, skin));
        List<EntityPart> parts = List.of(new EntityPart("s", "sleeve", null, new Vector3f(), new Vector3f(), new Vector3f(),
            new Vector3f(1), List.of(new EntityFace(0, -1, 0, idx.length))));
        UploadedModel garment = keep(UploadedModel.upload(new EntityGeometry(sleeve.positions(), new float[n * 2], idx, parts,
            Map.of(), 0f, List.of())));
        garment.mesh().updateSkin(sleeve.pack());

        // The arm raised: the body's palette poses the garment too.
        float[] locals = RigPoser.local().localPose(rig, null, 0f, List.of(), new float[rig.jointCount() * 9]);
        locals[shoulder * 9 + 5] += 70f;
        locals[elbow * 9 + 3] += 50f;
        JointPalette body = RigPoser.local().poseLocals(rig, locals, null, new JointPalette());
        Matrix4f view = frontView(rig, RigPoser.local().poseLocals(rig, null, null, new JointPalette()));
        // Only the sleeved parts: the pixels the sleeve has to cover.
        long[] armBits = new long[(rig.jointCount() + 63) / 64];
        for (String name : List.of("upper_arm_l", "forearm_l")) {  // the sleeve ends at the wrist
            int j = rig.find(name);
            armBits[j >>> 6] |= 1L << (j & 63);
        }
        clear();
        renderer.drawFlat(goblin.mesh(), goblin.plan(), body, view, PROJ, new Vector4f(0, 1, 0, 1),
            new ModelAppearance().setVisible(armBits));
        byte[] arm = read("arm");
        clear();
        renderer.drawFlat(garment.mesh(), garment.plan(), body, view, PROJ, new Vector4f(1, 0, 0, 1));
        byte[] covered = read("sleeve");
        JointPalette rest = RigPoser.local().poseLocals(rig, null, null, new JointPalette());
        clear();
        renderer.drawFlat(garment.mesh(), garment.plan(), rest, view, PROJ, new Vector4f(1, 0, 0, 1));
        byte[] atRest = read("sleeve-rest");
        int armPixels = 0, inside = 0, moved = 0;
        for (int i = 0; i < arm.length; i += 4) {
            boolean isArm = (arm[i + 3] & 0xFF) > 0, isSleeve = (covered[i + 3] & 0xFF) > 0;
            if (isArm) {
                armPixels++;
                if (isSleeve) inside++;
            }
            if (isSleeve != ((atRest[i + 3] & 0xFF) > 0)) moved++;
        }
        System.out.printf("[appearance-gl] raised arm %d px, covered by the sleeve %d; sleeve pixels that moved %d%n",
            armPixels, inside, moved);
        assertTrue(armPixels > 100, "the arm is in view");
        assertTrue(inside >= armPixels * 9 / 10, "the sleeve follows the raised arm");
        assertTrue(moved > 100, "the sleeve moved with the body's palette");
    }
}
