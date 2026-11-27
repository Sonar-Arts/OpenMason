package com.openmason.main.systems.rendering.model.editing.picking;

import com.openmason.engine.rendering.model.draw.ModelSkinGlsl;

/**
 * GLSL of the ID pass (#175 F4). One vertex stage for the three programs: the model's own position path (shape keys,
 * then GPU skinning, then the part matrix - {@code ModelShaderSource.LIT_VERTEX}'s math), plus the corner's
 * {@link PickCornerTable} row fetched by {@code gl_VertexID} (the corner index whatever order the index buffer is
 * in). Targets are {@code R32UI}; every id is written +1 so 0 means "nothing here". GL 3.3 core: no compute, no SSBO.
 */
final class ModelPickShaders {

    private ModelPickShaders() {
    }

    static final String VERTEX = """
            #version 330 core
            layout (location = 0) in vec3 aPos;
            """ + ModelSkinGlsl.VERTEX + """

            uniform mat4 model;
            uniform mat4 view;
            uniform mat4 projection;
            uniform usamplerBuffer u_pickCorners;
            uniform float u_pointSize;

            flat out uvec4 vCorner;
            flat out uint vCornerId;

            void main() {
                vec4 worldPos = model * skinPosition(skinMatrix(), morphPosition(aPos));
                gl_Position = projection * view * worldPos;
                vCorner = texelFetch(u_pickCorners, gl_VertexID);
                vCornerId = uint(gl_VertexID);
                gl_PointSize = u_pointSize;
            }
            """;

    /** Faces: the face's first triangle (the face overlay's triangle index). */
    static final String FACE_FRAGMENT = """
            #version 330 core
            flat in uvec4 vCorner;
            flat in uint vCornerId;
            layout (location = 0) out uint pickId;

            void main() {
                pickId = vCorner.r == 0xFFFFFFFFu ? 0u : vCorner.r + 1u;
            }
            """;

    /**
     * Vertices as round points of the overlay's size: a point covers the pixels within {@code pointSize / 2} of the
     * vertex - the CPU detector's radius; depth-tested, so the nearest visible one wins.
     */
    static final String POINT_FRAGMENT = """
            #version 330 core
            flat in uvec4 vCorner;
            flat in uint vCornerId;
            layout (location = 0) out uint pickId;

            void main() {
                vec2 d = gl_PointCoord - vec2(0.5);
                if (dot(d, d) > 0.25) discard;
                pickId = vCorner.g == 0xFFFFFFFFu ? 0u : vCorner.g + 1u;
            }
            """;

    /**
     * Edges: each drawn triangle emits the sides that are face-loop edges (one corner's loop successor is the other;
     * triangulation diagonals are dropped) as 1-pixel lines carrying the edge id. Drawing the plan's triangles (not an
     * own edge list) keeps hidden parts / vertex groups out by construction.
     */
    static final String EDGE_GEOMETRY = """
            #version 330 core
            layout (triangles) in;
            layout (line_strip, max_vertices = 6) out;

            flat in uvec4 vCorner[];
            flat in uint vCornerId[];
            flat out uint gEdge;

            void side(int a, int b) {
                uint id = 0xFFFFFFFFu;
                if (vCorner[a].b == vCornerId[b]) id = vCorner[a].a;
                else if (vCorner[b].b == vCornerId[a]) id = vCorner[b].a;
                if (id == 0xFFFFFFFFu) return;
                gEdge = id + 1u;
                gl_Position = gl_in[a].gl_Position;
                EmitVertex();
                gEdge = id + 1u;
                gl_Position = gl_in[b].gl_Position;
                EmitVertex();
                EndPrimitive();
            }

            void main() {
                side(0, 1);
                side(1, 2);
                side(2, 0);
            }
            """;

    static final String EDGE_FRAGMENT = """
            #version 330 core
            flat in uint gEdge;
            layout (location = 0) out uint pickId;

            void main() {
                pickId = gEdge;
            }
            """;
}
