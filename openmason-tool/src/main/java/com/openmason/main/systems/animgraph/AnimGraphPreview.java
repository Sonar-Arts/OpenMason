package com.openmason.main.systems.animgraph;

import com.openmason.engine.entity.model.EntityGeometry;
import com.openmason.engine.rendering.model.draw.ModelLighting;
import com.openmason.engine.rendering.model.draw.ModelRenderer;
import com.openmason.engine.rendering.model.draw.UploadedModel;
import com.openmason.engine.rig.JointPalette;
import com.openmason.main.systems.particles.PreviewGround;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL33C.*;

/**
 * The Animation Graph tab's preview viewport (OpenMason #164): the preview character drawn by the engine
 * {@link ModelRenderer} with the palette the native graph instance evaluated, over the shared preview floor, into its
 * own frame buffer. Presentation only. GL thread only.
 */
final class AnimGraphPreview implements AutoCloseable {

    private static final float FOV = (float) Math.toRadians(45);

    private final ModelRenderer renderer = new ModelRenderer();
    private final PreviewGround ground = new PreviewGround();
    private final Matrix4f view = new Matrix4f();
    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f viewProjection = new Matrix4f();
    private final Vector3f eye = new Vector3f();
    private final Vector3f target = new Vector3f(0, 0.9f, 0);
    private final Vector4f flat = new Vector4f(0.72f, 0.74f, 0.78f, 1f);
    private float yaw = (float) Math.toRadians(210);
    private float pitch = 0.15f;
    private float distance = 3.5f;
    private EntityGeometry uploadedFor;
    private UploadedModel gpu;
    private int fbo;
    private int colorTex;
    private int depthRb;
    private int width;
    private int height;
    /** The Eyes preview (the graph's first-person view from the character's eyes). */
    boolean eyes;
    final com.openmason.main.systems.animation.EyesView eyesView = new com.openmason.main.systems.animation.EyesView();
    private String eyesProblem;
    /** The held item (a game's {@code HeldItemProvider}); set by the workspace, null = none. */
    com.openmason.main.systems.animation.HeldItemPreview held;

    /** What the Eyes preview needs beside the body pose. */
    record EyesInput(com.openmason.engine.rig.Rig rig, com.openmason.engine.character.CharacterDefinition character,
                     com.openmason.main.systems.animation.EyesView.Poser poser) {}

    AnimGraphPreview() {
        renderer.initialize();
    }

    void frame(float[] min, float[] max) {
        target.set((min[0] + max[0]) / 2, (min[1] + max[1]) / 2, (min[2] + max[2]) / 2);
        float dx = max[0] - min[0], dy = max[1] - min[1], dz = max[2] - min[2];
        float radius = (float) Math.max(0.2, Math.sqrt(dx * dx + dy * dy + dz * dz) / 2);
        distance = Math.max(0.5f, radius / (float) Math.tan(FOV / 2) * 1.2f);
    }

    void orbit(float dx, float dy) {
        yaw -= dx * 0.01f;
        pitch = Math.max(-1.45f, Math.min(1.45f, pitch + dy * 0.01f));
    }

    void zoom(float wheel) {
        distance = Math.max(0.2f, Math.min(80f, distance * (float) Math.pow(0.88, wheel)));
    }

    /** Draws the geometry posed by the palette (null palette = nothing to draw: the floor only). */
    int render(EntityGeometry geometry, JointPalette palette, int w, int h) {
        return render(geometry, palette, w, h, null);
    }

    String eyesProblem() {
        return eyesProblem;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    /** As {@link #render(EntityGeometry, JointPalette, int, int)}; from the character's eyes while {@link #eyes}. */
    int render(EntityGeometry geometry, JointPalette palette, int w, int h, EyesInput eyesInput) {
        if (w < 8 || h < 8) return colorTex;
        ensureTarget(w, h);
        boolean firstPerson = false;
        eyesProblem = null;
        if (eyes) {
            firstPerson = eyesInput != null && palette != null && eyesView.update(eyesInput.rig(), eyesInput.character(),
                    palette, new Matrix4f(), eyesInput.poser(), w, h);
            if (!firstPerson) eyesProblem = eyesInput == null || palette == null ? "no preview" : eyesView.problem();
        }
        if (firstPerson) {
            view.set(eyesView.view());
            projection.set(eyesView.worldProjection());
        } else {
            eye.set((float) (Math.sin(yaw) * Math.cos(pitch)), (float) Math.sin(pitch), (float) (Math.cos(yaw) * Math.cos(pitch)))
                    .mul(distance).add(target);
            view.setLookAt(eye.x, eye.y, eye.z, target.x, target.y, target.z, 0f, 1f, 0f);
            projection.setPerspective(FOV, w / (float) h, 0.02f, 400f);
        }
        projection.mul(view, viewProjection);
        JointPalette drawPalette = firstPerson ? eyesView.palette() : palette;
        Matrix4f drawProjection = firstPerson ? eyesView.projection() : projection;
        int prevFbo = glGetInteger(GL_FRAMEBUFFER_BINDING);
        int[] vp = new int[4];
        float[] clear = new float[4];
        glGetIntegerv(GL_VIEWPORT, vp);
        glGetFloatv(GL_COLOR_CLEAR_VALUE, clear);
        boolean scissor = glIsEnabled(GL_SCISSOR_TEST);
        boolean depth = glIsEnabled(GL_DEPTH_TEST);
        try {
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glViewport(0, 0, width, height);
            glDisable(GL_SCISSOR_TEST);
            glClearColor(PreviewGround.CLEAR[0], PreviewGround.CLEAR[1], PreviewGround.CLEAR[2], 1f);
            glClearDepth(1.0);
            glDepthMask(true);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_LESS);
            ground.draw(viewProjection);
            if (geometry != null && palette != null && geometry.indices().length > 0) {
                if (uploadedFor != geometry) {
                    if (gpu != null) gpu.close();
                    gpu = UploadedModel.upload(geometry);
                    uploadedFor = geometry;
                }
                if (geometry.materials().isEmpty()) {
                    renderer.drawFlat(gpu.mesh(), gpu.plan(), drawPalette, view, drawProjection, flat);
                } else {
                    renderer.drawLit(gpu.mesh(), gpu.plan(), gpu, drawPalette, view, drawProjection,
                            ModelLighting.preview(view), null);
                }
                if (held != null && held.active()) {
                    held.draw(geometry, eyesInput != null ? eyesInput.character() : null, drawPalette, view,
                            drawProjection);
                }
            }
        } finally {
            glBindFramebuffer(GL_FRAMEBUFFER, prevFbo);
            glViewport(vp[0], vp[1], vp[2], vp[3]);
            glClearColor(clear[0], clear[1], clear[2], clear[3]);
            if (scissor) glEnable(GL_SCISSOR_TEST); else glDisable(GL_SCISSOR_TEST);
            if (depth) glEnable(GL_DEPTH_TEST); else glDisable(GL_DEPTH_TEST);
        }
        return colorTex;
    }

    private void ensureTarget(int w, int h) {
        if (w == width && h == height && fbo != 0) return;
        releaseTarget();
        width = w;
        height = h;
        int prevFbo = glGetInteger(GL_FRAMEBUFFER_BINDING);
        int prevTex = glGetInteger(GL_TEXTURE_BINDING_2D);
        colorTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, colorTex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glBindTexture(GL_TEXTURE_2D, prevTex);
        depthRb = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, depthRb);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, w, h);
        fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colorTex, 0);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depthRb);
        glBindFramebuffer(GL_FRAMEBUFFER, prevFbo);
    }

    private void releaseTarget() {
        if (fbo != 0) glDeleteFramebuffers(fbo);
        if (colorTex != 0) glDeleteTextures(colorTex);
        if (depthRb != 0) glDeleteRenderbuffers(depthRb);
        fbo = colorTex = depthRb = 0;
    }

    @Override
    public void close() {
        eyesView.close();
        if (held != null) held.close();
        releaseTarget();
        if (gpu != null) gpu.close();
        gpu = null;
        uploadedFor = null;
        ground.close();
        renderer.cleanup();
    }
}
