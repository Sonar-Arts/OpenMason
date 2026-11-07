#version 330 core
// Open Mason particles: one instance = one quad, corners from gl_VertexID (triangle strip, no vertex buffer).
// Instance layout: com.openmason.engine.rendering.particles.ParticleVertexFormat.

layout(location = 0) in vec3 a_position;      // render space
layout(location = 1) in vec4 a_color;         // premultiplied; alpha = coverage * (1 - additive)
layout(location = 2) in vec2 a_sizeRotation;  // world size, rotation (radians)
layout(location = 3) in uvec2 a_frameFlags;   // frame code, flags (bit 0 emissive, bits 1-2 orientation)
layout(location = 4) in vec4 a_axisSoftness;  // trail axis (velocity * stretch), softness (blocks)

uniform mat4 u_viewProj;
uniform vec3 u_camRight;
uniform vec3 u_camUp;
uniform vec3 u_camPos;
uniform vec2 u_atlasGrid;    // columns, rows
uniform float u_ambient;
uniform float u_fogDensity;
uniform vec3 u_fogColor;

out vec2 v_local;
out vec2 v_atlasUv;
out vec4 v_color;
out float v_viewDepth;
out float v_softness;
flat out uint v_frame;

const uint FIRST_PROCEDURAL_FRAME = 0xFFF0u;
const uint CAMERA = 0u, VELOCITY = 1u, AXIS_Y = 2u, FLAT = 3u;

// ---- host lighting: defines vec3 particleLight(vec3 renderPos) ----
//@PARTICLE_LIGHT

void main() {
    vec2 corner = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1)) - 0.5;
    float size = a_sizeRotation.x;
    float s = sin(a_sizeRotation.y);
    float c = cos(a_sizeRotation.y);
    vec2 r = vec2(corner.x * c - corner.y * s, corner.x * s + corner.y * c) * size;
    uint orientation = (a_frameFlags.y >> 1) & 3u;

    vec3 world;
    vec3 trail = a_axisSoftness.xyz;
    float trailLength = length(trail);
    if (orientation == VELOCITY && trailLength > 1e-4) {
        // Long axis along the motion: head at the particle, tail trailing `trail` behind it.
        vec3 dir = trail / trailLength;
        vec3 side = cross(dir, u_camPos - a_position);
        float sideLength = length(side);
        side = sideLength > 1e-5 ? side / sideLength : u_camRight;
        world = a_position + side * (corner.x * size) + dir * (corner.y * size) - trail * (0.5 - corner.y);
    } else if (orientation == AXIS_Y) {
        vec3 toCamera = u_camPos - a_position;
        vec3 right = vec3(toCamera.z, 0.0, -toCamera.x);
        float rightLength = length(right);
        right = rightLength > 1e-5 ? right / rightLength : u_camRight;
        world = a_position + right * r.x + vec3(0.0, r.y, 0.0);
    } else if (orientation == FLAT) {
        world = a_position + vec3(r.x, 0.0, -r.y);
    } else {
        world = a_position + u_camRight * r.x + u_camUp * r.y;
    }
    gl_Position = u_viewProj * vec4(world, 1.0);
    v_viewDepth = gl_Position.w;
    v_softness = a_axisSoftness.w;

    v_local = corner + 0.5;
    uint frame = a_frameFlags.x;
    v_frame = frame;
    if (frame < FIRST_PROCEDURAL_FRAME) {
        float columns = u_atlasGrid.x;
        float f = float(frame);
        vec2 cell = vec2(mod(f, columns), floor(f / columns));
        // Row 0 is the top of the image (uploaded top row first), so flip V inside the cell.
        v_atlasUv = (cell + vec2(v_local.x, 1.0 - v_local.y)) / u_atlasGrid;
    } else {
        v_atlasUv = vec2(0.0);
    }

    vec4 color = a_color;
    if ((a_frameFlags.y & 1u) == 0u) {
        color.rgb *= particleLight(a_position);
    }
    if (u_fogDensity > 0.0) {
        float fog = 1.0 - exp(-u_fogDensity * distance(a_position, u_camPos));
        // Premultiplied: additive light fades out, covered colour is pulled toward the fog colour.
        color.rgb = color.rgb * (1.0 - fog) + u_fogColor * color.a * fog;
    }
    v_color = color;
}
