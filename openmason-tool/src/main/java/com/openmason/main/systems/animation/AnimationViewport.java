package com.openmason.main.systems.animation;

import com.openmason.engine.entity.model.EntityGeometry;
import com.openmason.engine.rendering.model.draw.ModelLighting;
import com.openmason.engine.rendering.model.draw.ModelRenderer;
import com.openmason.engine.rendering.model.draw.UploadedModel;
import com.openmason.engine.rig.JointPalette;
import com.openmason.main.systems.particles.PreviewGround;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Arrays;

import static org.lwjgl.opengl.GL33C.*;

/**
 * The Animation tab's viewport (OpenMason #165; USER DECISION 2026-11-25: its own viewport, not the Scene's): the
 * animated model drawn by the engine {@link ModelRenderer} with the session's palette, plus an overlay of lines and
 * points (skeleton, the selected joint, control-rig targets, poles) into its own frame buffer. Presentation only:
 * poses come from the native authoring session, picking from the native character overlay. GL thread only.
 */
final class AnimationViewport implements AutoCloseable {

    static final float FOV = (float) Math.toRadians(45);

    boolean showMesh = true;
    boolean showSkeleton = true;
    boolean xray = true;

    private final ModelRenderer renderer = new ModelRenderer();
    private final PreviewGround ground = new PreviewGround();
    final Matrix4f view = new Matrix4f();
    final Matrix4f projection = new Matrix4f();
    final Matrix4f viewProjection = new Matrix4f();
    private final Matrix4f inverse = new Matrix4f();
    private final Vector3f eye = new Vector3f();
    private final Vector3f target = new Vector3f(0, 0.9f, 0);
    private final Vector4f flat = new Vector4f(0.72f, 0.74f, 0.78f, 1f);
    private float yaw = (float) Math.toRadians(210);
    private float pitch = 0.15f;
    private float distance = 3.5f;
    private EntityGeometry uploadedFor;
    private UploadedModel gpu;
    private int fbo, colorTex, depthRb, width, height;
    private final Lines lines = new Lines();

    AnimationViewport() {
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

    void pan(float dx, float dy) {
        Vector3f right = new Vector3f((float) Math.cos(yaw), 0, (float) -Math.sin(yaw));
        float s = distance * 0.0016f;
        target.add(right.mul(-dx * s)).add(0, dy * s, 0);
    }

    Lines lines() {
        return lines;
    }

    Vector3f eye() {
        return new Vector3f(eye);
    }

    int render(EntityGeometry geometry, JointPalette palette, int w, int h) {
        return render(geometry, palette, w, h, null);
    }

    /** Shape keys (#171): appearance carrying the clip's morph weights (one per target; null = defaults). */
    private final com.openmason.engine.rendering.model.draw.ModelAppearance appearance =
            new com.openmason.engine.rendering.model.draw.ModelAppearance();

    int render(EntityGeometry geometry, JointPalette palette, int w, int h, float[] morphWeights) {
        if (w < 8 || h < 8) return colorTex;
        appearance.setMorphWeights(morphWeights);
        ensureTarget(w, h);
        eye.set((float) (Math.sin(yaw) * Math.cos(pitch)), (float) Math.sin(pitch), (float) (Math.cos(yaw) * Math.cos(pitch)))
                .mul(distance).add(target);
        view.setLookAt(eye.x, eye.y, eye.z, target.x, target.y, target.z, 0f, 1f, 0f);
        projection.setPerspective(FOV, w / (float) h, 0.02f, 400f);
        projection.mul(view, viewProjection);
        int prevFbo = glGetInteger(GL_FRAMEBUFFER_BINDING);
        int[] vp = new int[4];
        float[] clear = new float[4];
        glGetIntegerv(GL_VIEWPORT, vp);
        glGetFloatv(GL_COLOR_CLEAR_VALUE, clear);
        boolean scissor = glIsEnabled(GL_SCISSOR_TEST), depth = glIsEnabled(GL_DEPTH_TEST), blend = glIsEnabled(GL_BLEND);
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
            if (showMesh && geometry != null && palette != null && geometry.indices().length > 0) {
                if (uploadedFor != geometry) {
                    if (gpu != null) gpu.close();
                    gpu = UploadedModel.upload(geometry);
                    uploadedFor = geometry;
                }
                if (geometry.materials().isEmpty()) {
                    renderer.drawFlat(gpu.mesh(), gpu.plan(), palette, view, projection, flat, appearance);
                } else {
                    renderer.drawLit(gpu.mesh(), gpu.plan(), gpu, palette, view, projection, ModelLighting.preview(view),
                            null, appearance);
                }
            }
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            if (xray) glDisable(GL_DEPTH_TEST);
            lines.draw(viewProjection);
        } finally {
            glBindFramebuffer(GL_FRAMEBUFFER, prevFbo);
            glViewport(vp[0], vp[1], vp[2], vp[3]);
            glClearColor(clear[0], clear[1], clear[2], clear[3]);
            if (scissor) glEnable(GL_SCISSOR_TEST); else glDisable(GL_SCISSOR_TEST);
            if (depth) glEnable(GL_DEPTH_TEST); else glDisable(GL_DEPTH_TEST);
            if (blend) glEnable(GL_BLEND); else glDisable(GL_BLEND);
        }
        return colorTex;
    }

    /** The model-space ray through a pixel of the last frame: origin then direction. */
    float[] ray(float px, float py) {
        if (width == 0 || height == 0) return null;
        float nx = px / width * 2f - 1f, ny = 1f - py / height * 2f;
        viewProjection.invert(inverse);
        Vector4f near = new Vector4f(nx, ny, -1f, 1f).mul(inverse);
        Vector4f far = new Vector4f(nx, ny, 1f, 1f).mul(inverse);
        near.div(near.w);
        far.div(far.w);
        return new float[] {near.x, near.y, near.z, far.x - near.x, far.y - near.y, far.z - near.z};
    }

    /** Screen position (pixels in the last frame) of a model-space point, or null behind the camera. */
    float[] project(float x, float y, float z) {
        Vector4f p = new Vector4f(x, y, z, 1f).mul(viewProjection);
        if (p.w <= 1e-6f) return null;
        return new float[] {(p.x / p.w * 0.5f + 0.5f) * width, (1f - (p.y / p.w * 0.5f + 0.5f)) * height};
    }

    float tolerancePerDistance() {
        return (float) (Math.tan(FOV / 2) * 2 / Math.max(1, height) * 8);
    }

    private void ensureTarget(int w, int h) {
        if (w == width && h == height && fbo != 0) return;
        releaseTarget();
        width = w;
        height = h;
        int prevFbo = glGetInteger(GL_FRAMEBUFFER_BINDING), prevTex = glGetInteger(GL_TEXTURE_BINDING_2D);
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
        releaseTarget();
        if (gpu != null) gpu.close();
        gpu = null;
        uploadedFor = null;
        lines.close();
        ground.close();
        renderer.cleanup();
    }

    /** Coloured lines and points (position + RGBA), re-uploaded each frame. */
    static final class Lines implements AutoCloseable {
        private float[] lineData = new float[7 * 512];
        private float[] pointData = new float[8 * 256];
        private int lineCount, pointCount, program, vao, vbo, uViewProj;

        void clear() {
            lineCount = 0;
            pointCount = 0;
        }

        void line(float ax, float ay, float az, float bx, float by, float bz, float[] c) {
            if ((lineCount + 2) * 7 > lineData.length) lineData = Arrays.copyOf(lineData, lineData.length * 2);
            put(lineData, lineCount++ * 7, ax, ay, az, c);
            put(lineData, lineCount++ * 7, bx, by, bz, c);
        }

        void point(float x, float y, float z, float[] c, float size) {
            if ((pointCount + 1) * 8 > pointData.length) pointData = Arrays.copyOf(pointData, pointData.length * 2);
            int o = pointCount++ * 8;
            put(pointData, o, x, y, z, c);
            pointData[o + 7] = size;
        }

        private static void put(float[] d, int o, float x, float y, float z, float[] c) {
            d[o] = x;
            d[o + 1] = y;
            d[o + 2] = z;
            d[o + 3] = c[0];
            d[o + 4] = c[1];
            d[o + 5] = c[2];
            d[o + 6] = c[3];
        }

        void draw(Matrix4f viewProjection) {
            if (program == 0) init();
            int prevProgram = glGetInteger(GL_CURRENT_PROGRAM), prevVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
            boolean prevPointSize = glIsEnabled(GL_PROGRAM_POINT_SIZE);
            glUseProgram(program);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                glUniformMatrix4fv(uViewProj, false, viewProjection.get(stack.mallocFloat(16)));
            }
            glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glEnable(GL_PROGRAM_POINT_SIZE);
            if (lineCount > 0) {
                upload(lineData, lineCount * 7, 7);
                glDisableVertexAttribArray(2);
                glVertexAttrib1f(2, 1f);
                glDrawArrays(GL_LINES, 0, lineCount);
            }
            if (pointCount > 0) {
                upload(pointData, pointCount * 8, 8);
                glEnableVertexAttribArray(2);
                glVertexAttribPointer(2, 1, GL_FLOAT, false, 32, 28);
                glDrawArrays(GL_POINTS, 0, pointCount);
            }
            glBindVertexArray(prevVao);
            glUseProgram(prevProgram);
            if (!prevPointSize) glDisable(GL_PROGRAM_POINT_SIZE);
        }

        private void upload(float[] data, int floats, int stride) {
            FloatBuffer buf = org.lwjgl.BufferUtils.createFloatBuffer(floats);
            buf.put(data, 0, floats).flip();
            glBufferData(GL_ARRAY_BUFFER, buf, GL_STREAM_DRAW);
            glEnableVertexAttribArray(0);
            glVertexAttribPointer(0, 3, GL_FLOAT, false, stride * 4, 0);
            glEnableVertexAttribArray(1);
            glVertexAttribPointer(1, 4, GL_FLOAT, false, stride * 4, 12);
        }

        private void init() {
            String vs = """
                    #version 330 core
                    layout(location = 0) in vec3 a_pos;
                    layout(location = 1) in vec4 a_color;
                    layout(location = 2) in float a_size;
                    uniform mat4 u_viewProj;
                    out vec4 v_color;
                    void main() { v_color = a_color; gl_Position = u_viewProj * vec4(a_pos, 1.0); gl_PointSize = a_size; }
                    """;
            String fs = """
                    #version 330 core
                    in vec4 v_color;
                    out vec4 o_color;
                    void main() { o_color = v_color; }
                    """;
            program = glCreateProgram();
            int v = shader(GL_VERTEX_SHADER, vs), f = shader(GL_FRAGMENT_SHADER, fs);
            glAttachShader(program, v);
            glAttachShader(program, f);
            glLinkProgram(program);
            glDeleteShader(v);
            glDeleteShader(f);
            if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
                throw new IllegalStateException("animation overlay shader: " + glGetProgramInfoLog(program));
            }
            uViewProj = glGetUniformLocation(program, "u_viewProj");
            vao = glGenVertexArrays();
            vbo = glGenBuffers();
        }

        private static int shader(int type, String src) {
            int s = glCreateShader(type);
            glShaderSource(s, src);
            glCompileShader(s);
            if (glGetShaderi(s, GL_COMPILE_STATUS) == GL_FALSE) throw new IllegalStateException("animation overlay shader: " + glGetShaderInfoLog(s));
            return s;
        }

        @Override
        public void close() {
            if (program != 0) glDeleteProgram(program);
            if (vao != 0) glDeleteVertexArrays(vao);
            if (vbo != 0) glDeleteBuffers(vbo);
            program = vao = vbo = 0;
        }
    }
}
