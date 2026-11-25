package com.openmason.main.systems.character;

import com.openmason.engine.character.CharacterOverlay;
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

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL33C.*;

/**
 * The Character tab's viewport (OpenMason #163): the model drawn by the engine {@link ModelRenderer} in the
 * character's reference pose (the native overlay's skin matrices), with the native overlay on top - bones coloured
 * by role state, joint dots, retarget chains, socket frames, eye and hip lines, capsule - into its own frame
 * buffer. Presentation only: what to draw and where comes from {@link CharacterOverlay}. GL thread only.
 */
final class CharacterViewport implements AutoCloseable {

    static final float FOV = (float) Math.toRadians(45);

    // Overlay toggles.
    boolean showMesh = true;
    boolean showBones = true;
    boolean showChains = true;
    boolean showSockets = true;
    boolean showCapsule = true;
    boolean showLines = true;
    boolean showGround = true;
    boolean xray = true;

    private final ModelRenderer renderer = new ModelRenderer();
    private final PreviewGround ground = new PreviewGround();
    private final JointPalette palette = new JointPalette();
    private final Matrix4f view = new Matrix4f();
    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f viewProjection = new Matrix4f();
    private final Matrix4f inverse = new Matrix4f();
    private final Vector3f eye = new Vector3f();
    private final Vector3f target = new Vector3f(0, 0.9f, 0);
    private final Vector4f flat = new Vector4f(0.72f, 0.74f, 0.78f, 1f);
    private float yaw = (float) Math.toRadians(200);
    private float pitch = 0.18f;
    private float distance = 3.5f;

    private EntityGeometry uploadedFor;
    private UploadedModel gpu;
    private final Lines lines = new Lines();

    private int fbo;
    private int colorTex;
    private int depthRb;
    private int width;
    private int height;

    CharacterViewport() {
        renderer.initialize();
    }

    void frame(float[] min, float[] max) {
        target.set((min[0] + max[0]) / 2, (min[1] + max[1]) / 2, (min[2] + max[2]) / 2);
        float dx = max[0] - min[0], dy = max[1] - min[1], dz = max[2] - min[2];
        float radius = (float) Math.max(0.2, Math.sqrt(dx * dx + dy * dy + dz * dz) / 2);
        distance = Math.max(0.5f, radius / (float) Math.tan(FOV / 2) * 1.1f);
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

    /**
     * Draws a {@code w x h} frame of the document (the overlay carries the reference pose) and returns the colour
     * texture. selectedJoint / selectedSocket / selectedRoleJoint are highlighted (-1 = none).
     */
    int render(CharacterDocument doc, int w, int h, int selectedJoint, int selectedSocket) {
        if (w < 8 || h < 8) {
            return colorTex;
        }
        ensureTarget(w, h);
        eye.set((float) (Math.sin(yaw) * Math.cos(pitch)), (float) Math.sin(pitch), (float) (Math.cos(yaw) * Math.cos(pitch)))
                .mul(distance).add(target);
        view.setLookAt(eye.x, eye.y, eye.z, target.x, target.y, target.z, 0f, 1f, 0f);
        projection.setPerspective(FOV, w / (float) h, 0.02f, 400f);
        projection.mul(view, viewProjection);

        int prevFbo = glGetInteger(GL_FRAMEBUFFER_BINDING);
        int[] prevViewport = new int[4];
        float[] prevClear = new float[4];
        glGetIntegerv(GL_VIEWPORT, prevViewport);
        glGetFloatv(GL_COLOR_CLEAR_VALUE, prevClear);
        boolean prevScissor = glIsEnabled(GL_SCISSOR_TEST);
        boolean prevDepth = glIsEnabled(GL_DEPTH_TEST);
        boolean prevBlend = glIsEnabled(GL_BLEND);
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
            if (showGround) {
                ground.draw(viewProjection);
            }
            CharacterOverlay overlay = doc.overlay();
            if (showMesh) {
                drawModel(doc, overlay);
            }
            buildLines(doc, overlay, selectedJoint, selectedSocket);
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            if (xray) {
                glDisable(GL_DEPTH_TEST);
            }
            lines.draw(viewProjection);
        } finally {
            glBindFramebuffer(GL_FRAMEBUFFER, prevFbo);
            glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
            glClearColor(prevClear[0], prevClear[1], prevClear[2], prevClear[3]);
            if (prevScissor) glEnable(GL_SCISSOR_TEST); else glDisable(GL_SCISSOR_TEST);
            if (prevDepth) glEnable(GL_DEPTH_TEST); else glDisable(GL_DEPTH_TEST);
            if (prevBlend) glEnable(GL_BLEND); else glDisable(GL_BLEND);
        }
        return colorTex;
    }

    private void drawModel(CharacterDocument doc, CharacterOverlay overlay) {
        EntityGeometry g = doc.geometry();
        if (g.indices().length == 0) {
            return;
        }
        if (uploadedFor != g) {
            if (gpu != null) gpu.close();
            gpu = UploadedModel.upload(g);
            uploadedFor = g;
        }
        int n = overlay.jointCount();
        palette.ensureCapacity(n);
        MemorySegment.copy(overlay.skin(), 0, palette.nativeSkin(), ValueLayout.JAVA_FLOAT, 0, n * 16);
        MemorySegment.copy(overlay.world(), 0, palette.nativeWorld(), ValueLayout.JAVA_FLOAT, 0, n * 16);
        palette.nativeWritten(n);
        if (g.materials().isEmpty()) {
            renderer.drawFlat(gpu.mesh(), gpu.plan(), palette, view, projection, flat);
        } else {
            renderer.drawLit(gpu.mesh(), gpu.plan(), gpu, palette, view, projection, ModelLighting.preview(view), null);
        }
    }

    // Colours.
    private static final float[] MAPPED = {0.35f, 0.85f, 0.45f, 1f};
    private static final float[] UNMAPPED = {0.6f, 0.62f, 0.68f, 0.7f};
    private static final float[] ERROR = {0.95f, 0.35f, 0.3f, 1f};
    private static final float[] WARN = {0.95f, 0.8f, 0.3f, 1f};
    private static final float[] SELECTED = {0.3f, 0.75f, 1f, 1f};

    static float[] stateColor(int state, boolean mapped) {
        if ((state & CharacterOverlay.ROLE_ERROR) != 0) return ERROR;
        if ((state & CharacterOverlay.ROLE_MIRROR_MISMATCH) != 0) return WARN;
        return mapped ? MAPPED : UNMAPPED;
    }

    private void buildLines(CharacterDocument doc, CharacterOverlay o, int selectedJoint, int selectedSocket) {
        lines.clear();
        float[] p = o.points();
        float[] t = o.tails();
        int[] roles = o.roles();
        int[] states = o.states();
        boolean hasBones = doc.rig().joints().stream().anyMatch(j -> j.kind() == com.openmason.engine.rig.RigJoint.Kind.BONE);
        if (showBones) {
            for (int i = 0; i < o.jointCount(); i++) {
                boolean bone = doc.rig().joints().get(i).kind() == com.openmason.engine.rig.RigJoint.Kind.BONE;
                if (hasBones && !bone) continue; // roles map bones when the rig has them
                float[] c = i == selectedJoint ? SELECTED : stateColor(states[i], roles[i] != -1);
                if (bone) {
                    lines.line(p[i * 3], p[i * 3 + 1], p[i * 3 + 2], t[i * 3], t[i * 3 + 1], t[i * 3 + 2], c);
                }
                lines.point(p[i * 3], p[i * 3 + 1], p[i * 3 + 2], c, roles[i] != -1 || i == selectedJoint ? 9f : 5f);
            }
        }
        if (showChains) {
            float[] s = o.segments();
            for (int i = 0; i < o.segmentCount(); i++) {
                if (o.segmentKinds()[i] != CharacterOverlay.SEGMENT_CHAIN) continue;
                float[] c = chainColor(o.segmentIndices()[i]);
                lines.line(s[i * 6], s[i * 6 + 1], s[i * 6 + 2], s[i * 6 + 3], s[i * 6 + 4], s[i * 6 + 5], c);
            }
        }
        if (showLines) {
            float[] s = o.segments();
            for (int i = 0; i < o.segmentCount(); i++) {
                int k = o.segmentKinds()[i];
                if (k == CharacterOverlay.SEGMENT_CHAIN) continue;
                float[] c = k == CharacterOverlay.SEGMENT_EYE_LINE ? new float[] {0.4f, 0.9f, 1f, 0.9f}
                        : new float[] {1f, 0.6f, 0.9f, 0.9f};
                lines.line(s[i * 6], s[i * 6 + 1], s[i * 6 + 2], s[i * 6 + 3], s[i * 6 + 4], s[i * 6 + 5], c);
            }
        }
        if (showSockets) {
            float[] m = o.socketMatrices();
            float len = Math.max(0.04f, distance * 0.03f);
            for (int i = 0; i < o.socketCount(); i++) {
                int b = i * 16;
                float ox = m[b + 12], oy = m[b + 13], oz = m[b + 14];
                for (int a = 0; a < 3; a++) {
                    float ax = m[b + a * 4], ay = m[b + a * 4 + 1], az = m[b + a * 4 + 2];
                    float l = (float) Math.sqrt(ax * ax + ay * ay + az * az);
                    if (l < 1e-6f) continue;
                    float[] c = a == 0 ? new float[] {1f, 0.3f, 0.3f, 1f} : a == 1 ? new float[] {0.3f, 1f, 0.3f, 1f}
                            : new float[] {0.35f, 0.5f, 1f, 1f};
                    lines.line(ox, oy, oz, ox + ax / l * len, oy + ay / l * len, oz + az / l * len, c);
                }
                lines.point(ox, oy, oz, i == selectedSocket ? SELECTED : new float[] {1f, 0.85f, 0.2f, 1f},
                        i == selectedSocket ? 11f : 8f);
            }
        }
        if (showCapsule) {
            CharacterOverlay.Capsule cap = o.capsule();
            float r = cap.radius();
            if (r > 0 && cap.height() > 0) {
                float[] c = {0.55f, 0.75f, 1f, 0.55f};
                float bx = cap.baseX(), by = cap.baseY(), bz = cap.baseZ();
                float lo = by + Math.min(r, cap.height() / 2), hi = by + Math.max(cap.height() - r, cap.height() / 2);
                int seg = 32;
                for (int i = 0; i < seg; i++) {
                    double a0 = Math.PI * 2 * i / seg, a1 = Math.PI * 2 * (i + 1) / seg;
                    float x0 = (float) Math.cos(a0) * r, z0 = (float) Math.sin(a0) * r;
                    float x1 = (float) Math.cos(a1) * r, z1 = (float) Math.sin(a1) * r;
                    lines.line(bx + x0, lo, bz + z0, bx + x1, lo, bz + z1, c);
                    lines.line(bx + x0, hi, bz + z0, bx + x1, hi, bz + z1, c);
                }
                for (int k = 0; k < 4; k++) {
                    double a = Math.PI / 2 * k;
                    float x = (float) Math.cos(a) * r, z = (float) Math.sin(a) * r;
                    lines.line(bx + x, lo, bz + z, bx + x, hi, bz + z, c);
                    // End caps: quarter arcs from the rim to each pole.
                    int steps = 8;
                    for (int i = 0; i < steps; i++) {
                        double b0 = Math.PI / 2 * i / steps, b1 = Math.PI / 2 * (i + 1) / steps;
                        float c0 = (float) Math.cos(b0), s0 = (float) Math.sin(b0);
                        float c1 = (float) Math.cos(b1), s1 = (float) Math.sin(b1);
                        lines.line(bx + x * c0, hi + r * s0, bz + z * c0, bx + x * c1, hi + r * s1, bz + z * c1, c);
                        lines.line(bx + x * c0, lo - r * s0, bz + z * c0, bx + x * c1, lo - r * s1, bz + z * c1, c);
                    }
                }
            }
        }
    }

    private static final float[][] CHAIN_COLORS = {
        {1f, 0.55f, 0.2f, 1f}, {0.75f, 0.45f, 1f, 1f}, {0.2f, 0.9f, 0.85f, 1f}, {1f, 0.4f, 0.65f, 1f},
        {0.6f, 0.9f, 0.3f, 1f}, {0.95f, 0.95f, 0.4f, 1f}};

    static float[] chainColor(int chain) {
        return CHAIN_COLORS[Math.floorMod(chain, CHAIN_COLORS.length)];
    }

    /** The model-space ray through a pixel of the last frame (origin then direction). */
    float[] ray(float px, float py) {
        if (width == 0 || height == 0) {
            return null;
        }
        float nx = px / width * 2f - 1f;
        float ny = 1f - py / height * 2f;
        viewProjection.invert(inverse);
        Vector4f near = new Vector4f(nx, ny, -1f, 1f).mul(inverse);
        Vector4f far = new Vector4f(nx, ny, 1f, 1f).mul(inverse);
        near.div(near.w);
        far.div(far.w);
        return new float[] {near.x, near.y, near.z, far.x - near.x, far.y - near.y, far.z - near.z};
    }

    /** Pick tolerance: ~8 px at the clicked depth. */
    float tolerancePerDistance() {
        return (float) (Math.tan(FOV / 2) * 2 / Math.max(1, height) * 8);
    }

    private void ensureTarget(int w, int h) {
        if (w == width && h == height && fbo != 0) {
            return;
        }
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
        releaseTarget();
        if (gpu != null) {
            gpu.close();
            gpu = null;
        }
        uploadedFor = null;
        lines.close();
        ground.close();
        renderer.cleanup();
    }

    /** A batch of coloured lines and points (position + RGBA), re-uploaded each frame. */
    private static final class Lines implements AutoCloseable {
        private float[] lineData = new float[7 * 512];
        private float[] pointData = new float[8 * 256];
        private int lineCount;
        private int pointCount;
        private int program;
        private int vao;
        private int vbo;
        private int uViewProj;

        void clear() {
            lineCount = 0;
            pointCount = 0;
        }

        void line(float ax, float ay, float az, float bx, float by, float bz, float[] c) {
            if ((lineCount + 2) * 7 > lineData.length) lineData = java.util.Arrays.copyOf(lineData, lineData.length * 2);
            put(lineData, lineCount++ * 7, ax, ay, az, c);
            put(lineData, lineCount++ * 7, bx, by, bz, c);
        }

        void point(float x, float y, float z, float[] c, float size) {
            if ((pointCount + 1) * 8 > pointData.length) pointData = java.util.Arrays.copyOf(pointData, pointData.length * 2);
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
            if (program == 0) {
                init();
            }
            int prevProgram = glGetInteger(GL_CURRENT_PROGRAM);
            int prevVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
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
                glVertexAttrib1f(2, 1f);
                glDisableVertexAttribArray(2);
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
            try (MemoryStack stack = MemoryStack.stackPush()) {
                FloatBuffer buf = floats * 4 < 64 * 1024 ? stack.mallocFloat(floats) : org.lwjgl.BufferUtils.createFloatBuffer(floats);
                buf.put(data, 0, floats).flip();
                glBufferData(GL_ARRAY_BUFFER, buf, GL_STREAM_DRAW);
            }
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
                    void main() {
                        v_color = a_color;
                        gl_Position = u_viewProj * vec4(a_pos, 1.0);
                        gl_PointSize = a_size;
                    }
                    """;
            String fs = """
                    #version 330 core
                    in vec4 v_color;
                    out vec4 o_color;
                    void main() { o_color = v_color; }
                    """;
            program = glCreateProgram();
            int v = shader(GL_VERTEX_SHADER, vs);
            int f = shader(GL_FRAGMENT_SHADER, fs);
            glAttachShader(program, v);
            glAttachShader(program, f);
            glLinkProgram(program);
            glDeleteShader(v);
            glDeleteShader(f);
            if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
                throw new IllegalStateException("character overlay shader: " + glGetProgramInfoLog(program));
            }
            uViewProj = glGetUniformLocation(program, "u_viewProj");
            vao = glGenVertexArrays();
            vbo = glGenBuffers();
        }

        private static int shader(int type, String src) {
            int s = glCreateShader(type);
            glShaderSource(s, src);
            glCompileShader(s);
            if (glGetShaderi(s, GL_COMPILE_STATUS) == GL_FALSE) {
                throw new IllegalStateException("character overlay shader: " + glGetShaderInfoLog(s));
            }
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
