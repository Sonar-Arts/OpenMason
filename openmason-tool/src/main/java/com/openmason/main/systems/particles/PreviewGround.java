package com.openmason.main.systems.particles;

import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.opengl.GL33C.*;

/**
 * The particle preview's floor: a 40 × 40 block grid at y = 0 that fades into the clear colour, drawn opaque with
 * depth so particles sort and soft-fade against it. Shared by the Particle Editor preview and the MCP
 * {@code fx_capture} renderer so both show effects on the same ground. GL thread only.
 */
public final class PreviewGround implements AutoCloseable {

    /** The colour the grid fades into; clear the target with it so the edge is seamless. */
    public static final float[] CLEAR = {0.07f, 0.08f, 0.11f, 1f};

    private final int program;
    private final int vao;
    private final int viewProj;

    public PreviewGround() {
        program = compile();
        viewProj = glGetUniformLocation(program, "u_viewProj");
        vao = glGenVertexArrays();
    }

    /** Draws the floor; restores program, VAO, blend and cull state. */
    public void draw(Matrix4f viewProjection) {
        int prevProgram = glGetInteger(GL_CURRENT_PROGRAM);
        int prevVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        boolean prevBlend = glIsEnabled(GL_BLEND);
        boolean prevCull = glIsEnabled(GL_CULL_FACE);
        glDisable(GL_BLEND);
        glDisable(GL_CULL_FACE);
        glUseProgram(program);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            glUniformMatrix4fv(viewProj, false, viewProjection.get(stack.mallocFloat(16)));
        }
        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glBindVertexArray(prevVao);
        glUseProgram(prevProgram);
        if (prevBlend) glEnable(GL_BLEND);
        if (prevCull) glEnable(GL_CULL_FACE);
    }

    private static int compile() {
        String vs = """
                #version 330 core
                uniform mat4 u_viewProj;
                out vec2 v_xz;
                void main() {
                    vec2 c = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1)) * 40.0 - 20.0;
                    v_xz = c;
                    gl_Position = u_viewProj * vec4(c.x, 0.0, c.y, 1.0);
                }
                """;
        String fs = """
                #version 330 core
                in vec2 v_xz;
                out vec4 o;
                void main() {
                    vec2 g = abs(fract(v_xz) - 0.5);
                    float line = step(0.47, max(g.x, g.y));
                    float fade = clamp(1.0 - length(v_xz) / 20.0, 0.0, 1.0);
                    vec3 base = vec3(0.16, 0.18, 0.15);
                    o = vec4(mix(vec3(0.07, 0.08, 0.11), base * (1.0 + 0.35 * line), fade), 1.0);
                }
                """;
        int program = glCreateProgram();
        int v = shader(GL_VERTEX_SHADER, vs), f = shader(GL_FRAGMENT_SHADER, fs);
        glAttachShader(program, v);
        glAttachShader(program, f);
        glLinkProgram(program);
        glDeleteShader(v);
        glDeleteShader(f);
        if (glGetProgrami(program, GL_LINK_STATUS) != GL_TRUE) {
            throw new IllegalStateException("particle preview floor: " + glGetProgramInfoLog(program));
        }
        return program;
    }

    private static int shader(int type, String src) {
        int s = glCreateShader(type);
        glShaderSource(s, src);
        glCompileShader(s);
        if (glGetShaderi(s, GL_COMPILE_STATUS) != GL_TRUE) {
            throw new IllegalStateException("particle preview floor: " + glGetShaderInfoLog(s));
        }
        return s;
    }

    @Override
    public void close() {
        glDeleteProgram(program);
        glDeleteVertexArrays(vao);
    }
}
