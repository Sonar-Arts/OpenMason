package com.openmason.main.systems.viewport.viewportRendering.bones;

import com.openmason.main.systems.skeleton.SkinAuthoring;
import com.openmason.main.systems.skeleton.WeightPaintState;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Weight-paint overlay: the visible mesh drawn over the model with the target joint's per-vertex weight
 * as a heat ramp (blue 0 -> green -> red 1), plus the brush circle at the cursor. Presentation only: the
 * weights come from the native skin ({@link SkinAuthoring#jointWeights}), positions from the part
 * geometry at rest. GL thread only; restores the program, VAO, blend, depth and polygon-offset state.
 */
public final class WeightHeatRenderer {

    private static final Logger logger = LoggerFactory.getLogger(WeightHeatRenderer.class);
    private static final int CIRCLE_SEGMENTS = 64;

    private int program;
    private int mvpLoc;
    private int solidLoc;
    private int colorLoc;
    private int meshVao, meshVbo, meshEbo;
    private int circleVao, circleVbo;
    private int meshIndexCount;
    private boolean initialized;

    // Rebuild key: the skin changed (listener), the joint, or the geometry stamp.
    private boolean dirty = true;
    private String builtJoint;
    private long builtStamp;

    /** Marks the mesh data stale (skin weights changed). */
    public void markDirty() {
        dirty = true;
    }

    private void initialize() {
        program = compile();
        mvpLoc = glGetUniformLocation(program, "u_mvp");
        solidLoc = glGetUniformLocation(program, "u_solid");
        colorLoc = glGetUniformLocation(program, "u_color");
        meshVao = glGenVertexArrays();
        meshVbo = glGenBuffers();
        meshEbo = glGenBuffers();
        glBindVertexArray(meshVao);
        glBindBuffer(GL_ARRAY_BUFFER, meshVbo);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 16, 0L);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 1, GL_FLOAT, false, 16, 12L);
        glEnableVertexAttribArray(1);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, meshEbo);
        glBindVertexArray(0);

        circleVao = glGenVertexArrays();
        circleVbo = glGenBuffers();
        glBindVertexArray(circleVao);
        glBindBuffer(GL_ARRAY_BUFFER, circleVbo);
        glBufferData(GL_ARRAY_BUFFER, (long) CIRCLE_SEGMENTS * 16, GL_DYNAMIC_DRAW);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 16, 0L);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 1, GL_FLOAT, false, 16, 12L);
        glEnableVertexAttribArray(1);
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        initialized = true;
    }

    public void render(SkinAuthoring skin, WeightPaintState state, Matrix4f view, Matrix4f projection,
                       Matrix4f model) {
        if (skin == null || state == null || !state.isActive()) return;
        try {
            if (!initialized) initialize();
            rebuildIfNeeded(skin, state.getJointId());

            int prevProgram = glGetInteger(GL_CURRENT_PROGRAM);
            int prevVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
            boolean prevBlend = glIsEnabled(GL_BLEND);
            boolean prevDepth = glIsEnabled(GL_DEPTH_TEST);
            boolean prevCull = glIsEnabled(GL_CULL_FACE);
            boolean prevOffset = glIsEnabled(GL_POLYGON_OFFSET_FILL);
            int prevDepthFunc = glGetInteger(GL_DEPTH_FUNC);
            try {
                Matrix4f mvp = new Matrix4f(projection).mul(view).mul(model);
                glUseProgram(program);
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    glUniformMatrix4fv(mvpLoc, false, mvp.get(stack.mallocFloat(16)));
                }
                glEnable(GL_BLEND);
                glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
                glDisable(GL_CULL_FACE);

                if (meshIndexCount > 0) {
                    glEnable(GL_DEPTH_TEST);
                    glDepthFunc(GL_LEQUAL);
                    glEnable(GL_POLYGON_OFFSET_FILL);
                    glPolygonOffset(-1f, -1f);
                    glUniform1i(solidLoc, 0);
                    glBindVertexArray(meshVao);
                    glDrawElements(GL_TRIANGLES, meshIndexCount, GL_UNSIGNED_INT, 0L);
                }

                Vector3f cursor = state.getCursor();
                if (cursor != null) {
                    uploadCircle(cursor, state.getRadius(), view, model);
                    glDisable(GL_DEPTH_TEST);
                    glUniform1i(solidLoc, 1);
                    glUniform4f(colorLoc, 1f, 1f, 1f, 0.9f);
                    glBindVertexArray(circleVao);
                    glDrawArrays(GL_LINE_LOOP, 0, CIRCLE_SEGMENTS);
                }
            } finally {
                glBindVertexArray(prevVao);
                glUseProgram(prevProgram);
                glDepthFunc(prevDepthFunc);
                setEnabled(GL_BLEND, prevBlend);
                setEnabled(GL_DEPTH_TEST, prevDepth);
                setEnabled(GL_CULL_FACE, prevCull);
                setEnabled(GL_POLYGON_OFFSET_FILL, prevOffset);
            }
        } catch (RuntimeException e) {
            logger.error("Error rendering weight heat map", e);
        }
    }

    private static void setEnabled(int cap, boolean on) {
        if (on) glEnable(cap);
        else glDisable(cap);
    }

    private void rebuildIfNeeded(SkinAuthoring skin, String joint) {
        long stamp = skin.geometryStamp();
        if (!dirty && stamp == builtStamp && java.util.Objects.equals(joint, builtJoint)) return;
        dirty = false;
        builtStamp = stamp;
        builtJoint = joint;

        List<float[]> vertexChunks = new ArrayList<>();
        List<int[]> indexChunks = new ArrayList<>();
        int vertexTotal = 0, indexTotal = 0;
        for (var part : skin.parts().getAllParts()) {
            if (!part.visible()) continue;
            float[] pos = skin.modelPositions(part.id());
            int[] tri = skin.triangles(part.id());
            int n = pos.length / 3;
            if (n == 0 || tri.length == 0) continue;
            float[] w = joint == null ? new float[n] : skin.jointWeights(part.id(), joint);
            float[] interleaved = new float[n * 4];
            for (int v = 0; v < n; v++) {
                interleaved[v * 4] = pos[v * 3];
                interleaved[v * 4 + 1] = pos[v * 3 + 1];
                interleaved[v * 4 + 2] = pos[v * 3 + 2];
                interleaved[v * 4 + 3] = v < w.length ? w[v] : 0f;
            }
            int[] shifted = new int[tri.length];
            for (int i = 0; i < tri.length; i++) shifted[i] = tri[i] + vertexTotal;
            vertexChunks.add(interleaved);
            indexChunks.add(shifted);
            vertexTotal += n;
            indexTotal += shifted.length;
        }
        float[] vertices = new float[vertexTotal * 4];
        int[] indices = new int[indexTotal];
        int vo = 0, io = 0;
        for (float[] c : vertexChunks) {
            System.arraycopy(c, 0, vertices, vo, c.length);
            vo += c.length;
        }
        for (int[] c : indexChunks) {
            System.arraycopy(c, 0, indices, io, c.length);
            io += c.length;
        }
        glBindVertexArray(meshVao);
        glBindBuffer(GL_ARRAY_BUFFER, meshVbo);
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_DYNAMIC_DRAW);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, meshEbo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_DYNAMIC_DRAW);
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        meshIndexCount = indexTotal;
    }

    /** A camera-facing circle of model-space radius {@code r} around {@code c}. */
    private void uploadCircle(Vector3f c, float r, Matrix4f view, Matrix4f model) {
        Matrix4f modelView = new Matrix4f(view).mul(model);
        Vector3f eye = new Matrix4f(modelView).invert().transformPosition(new Vector3f());
        Vector3f normal = new Vector3f(eye).sub(c);
        if (normal.lengthSquared() < 1e-12f) normal.set(0, 0, 1);
        normal.normalize();
        Vector3f helper = Math.abs(normal.y) < 0.99f ? new Vector3f(0, 1, 0) : new Vector3f(1, 0, 0);
        Vector3f u = new Vector3f(helper).cross(normal).normalize();
        Vector3f v = new Vector3f(normal).cross(u).normalize();
        float[] data = new float[CIRCLE_SEGMENTS * 4];
        for (int i = 0; i < CIRCLE_SEGMENTS; i++) {
            double a = 2 * Math.PI * i / CIRCLE_SEGMENTS;
            float cs = (float) Math.cos(a) * r, sn = (float) Math.sin(a) * r;
            data[i * 4] = c.x + u.x * cs + v.x * sn;
            data[i * 4 + 1] = c.y + u.y * cs + v.y * sn;
            data[i * 4 + 2] = c.z + u.z * cs + v.z * sn;
        }
        glBindBuffer(GL_ARRAY_BUFFER, circleVbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0L, data);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    private static int compile() {
        String vs = """
                #version 330 core
                layout(location = 0) in vec3 a_pos;
                layout(location = 1) in float a_weight;
                uniform mat4 u_mvp;
                out float v_weight;
                void main() {
                    v_weight = a_weight;
                    gl_Position = u_mvp * vec4(a_pos, 1.0);
                }
                """;
        String fs = """
                #version 330 core
                in float v_weight;
                uniform bool u_solid;
                uniform vec4 u_color;
                out vec4 o;
                vec3 heat(float w) {
                    w = clamp(w, 0.0, 1.0);
                    vec3 c0 = vec3(0.05, 0.10, 0.85);
                    vec3 c1 = vec3(0.05, 0.75, 0.85);
                    vec3 c2 = vec3(0.10, 0.85, 0.15);
                    vec3 c3 = vec3(0.95, 0.85, 0.10);
                    vec3 c4 = vec3(0.90, 0.10, 0.05);
                    if (w < 0.25) return mix(c0, c1, w / 0.25);
                    if (w < 0.50) return mix(c1, c2, (w - 0.25) / 0.25);
                    if (w < 0.75) return mix(c2, c3, (w - 0.50) / 0.25);
                    return mix(c3, c4, (w - 0.75) / 0.25);
                }
                void main() {
                    o = u_solid ? u_color : vec4(heat(v_weight), 0.85);
                }
                """;
        int p = glCreateProgram();
        int v = shader(GL_VERTEX_SHADER, vs), f = shader(GL_FRAGMENT_SHADER, fs);
        glAttachShader(p, v);
        glAttachShader(p, f);
        glLinkProgram(p);
        glDeleteShader(v);
        glDeleteShader(f);
        if (glGetProgrami(p, GL_LINK_STATUS) != GL_TRUE) {
            throw new IllegalStateException("weight heat shader: " + glGetProgramInfoLog(p));
        }
        return p;
    }

    private static int shader(int type, String src) {
        int s = glCreateShader(type);
        glShaderSource(s, src);
        glCompileShader(s);
        if (glGetShaderi(s, GL_COMPILE_STATUS) != GL_TRUE) {
            throw new IllegalStateException("weight heat shader: " + glGetShaderInfoLog(s));
        }
        return s;
    }

    public void cleanup() {
        if (!initialized) return;
        glDeleteProgram(program);
        glDeleteVertexArrays(meshVao);
        glDeleteBuffers(meshVbo);
        glDeleteBuffers(meshEbo);
        glDeleteVertexArrays(circleVao);
        glDeleteBuffers(circleVbo);
        initialized = false;
    }
}
