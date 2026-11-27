// Cenda corner normals + shading store (OpenMason #169): co_mesh_normals / co_doc_compute_normals with Blender
// semantics (analytic cases with exact expectations, a brute-force reference over random meshes), the octahedral
// snorm16 codec, and the editor's co_shading store (re-soup, reconcile, drag, document round trips). Plain asserts.
//
// Blender fixtures: every "*.normals" file in CENDA_NORMALS_FIXTURES (written by fixtures/normals/
// export_blender_normals.py inside Blender) is checked corner by corner within 0.5 degrees. Format, one record per
// line ('#' comments): "v x y z" soup vertex, "t a b c face" triangle, "s face" smooth face, "e a b" sharp edge
// (soup vertices), "a degrees" auto smooth, "n x y z" Blender's corner normal of each soup vertex in "v" order.

#include "cenda/model.h"
#include "cenda/model_shading.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <map>
#include <random>
#include <set>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

namespace {

int failures = 0;

#define CHECK(cond)                                                                       \
    do {                                                                                  \
        if (!(cond)) {                                                                    \
            std::fprintf(stderr, "%s:%d: CHECK failed: %s\n", __FILE__, __LINE__, #cond); \
            ++failures;                                                                   \
        }                                                                                 \
    } while (0)

constexpr double kPi = 3.14159265358979323846;

using V3 = std::array<double, 3>;

V3 v3sub(const V3& a, const V3& b) { return {a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }
V3 v3add(const V3& a, const V3& b) { return {a[0] + b[0], a[1] + b[1], a[2] + b[2]}; }
V3 v3scale(const V3& a, double s) { return {a[0] * s, a[1] * s, a[2] * s}; }
double v3dot(const V3& a, const V3& b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
V3 v3cross(const V3& a, const V3& b) {
    return {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
}
V3 v3norm(const V3& a) { return v3scale(a, 1.0 / std::sqrt(v3dot(a, a))); }

// Angle in degrees between a float3 normal and an expected direction.
double angle_deg(const float* n, const V3& e) {
    const V3 a{n[0], n[1], n[2]};
    const double c = v3dot(v3norm(a), v3norm(e));
    return std::acos(std::clamp(c, -1.0, 1.0)) * 180.0 / kPi;
}

bool near3(const float* n, const V3& e, double eps = 2e-6) {
    return std::fabs(n[0] - e[0]) <= eps && std::fabs(n[1] - e[1]) <= eps && std::fabs(n[2] - e[2]) <= eps;
}

bool same3(const float* a, const float* b) { return a[0] == b[0] && a[1] == b[1] && a[2] == b[2]; }

bool finite_unit(const float* n) {
    if (!std::isfinite(n[0]) || !std::isfinite(n[1]) || !std::isfinite(n[2])) return false;
    const double l = std::sqrt(double{n[0]} * n[0] + double{n[1]} * n[1] + double{n[2]} * n[2]);
    return std::fabs(l - 1.0) < 1e-5;
}

// A soup built from polygons: every corner is its own soup vertex, fan-triangulated from corner `start`.
struct Soup {
    std::vector<float> pos;
    std::vector<int32_t> idx;
    std::vector<int32_t> face;          // per triangle
    std::vector<int32_t> vertex_face;   // per soup vertex
    std::vector<V3> vertex_pos;         // per soup vertex (exact)
    std::vector<std::vector<int32_t>> face_vertices;  // soup vertices of each polygon, loop order

    int32_t add_vertex(const V3& p, int32_t f) {
        pos.push_back(static_cast<float>(p[0]));
        pos.push_back(static_cast<float>(p[1]));
        pos.push_back(static_cast<float>(p[2]));
        vertex_face.push_back(f);
        vertex_pos.push_back(p);
        return static_cast<int32_t>(vertex_face.size() - 1);
    }

    // Adds polygon `loop` (CCW = front) as face f; returns the first soup vertex.
    int32_t polygon(const std::vector<V3>& loop, int32_t f, std::size_t start = 0) {
        std::vector<int32_t> vs;
        for (const V3& p : loop) vs.push_back(add_vertex(p, f));
        const std::size_t n = vs.size();
        for (std::size_t i = 1; i + 1 < n; ++i) {
            idx.push_back(vs[start % n]);
            idx.push_back(vs[(start + i) % n]);
            idx.push_back(vs[(start + i + 1) % n]);
            face.push_back(f);
        }
        if (face_vertices.size() <= static_cast<std::size_t>(f)) face_vertices.resize(static_cast<std::size_t>(f) + 1);
        face_vertices[static_cast<std::size_t>(f)] = vs;
        return vs.front();
    }

    [[nodiscard]] int64_t vcount() const { return static_cast<int64_t>(vertex_face.size()); }
    [[nodiscard]] int64_t icount() const { return static_cast<int64_t>(idx.size()); }
};

struct Opts {
    const uint32_t* smooth = nullptr;  // NULL = all smooth
    int64_t smooth_words = 0;
    std::vector<uint32_t> sharp;
    float auto_deg = 0.0f;
    std::vector<int32_t> custom_v;
    std::vector<int16_t> custom_oct;
    float eps = 0.0f;
    const int32_t* groups = nullptr;
};

const uint32_t kNoWords[1] = {0};  // a non-NULL empty bitset = every face flat

std::vector<float> run(const Soup& s, const Opts& o, std::vector<int16_t>* oct = nullptr, int32_t* rc_out = nullptr) {
    std::vector<float> out(static_cast<std::size_t>(s.vcount()) * 3, -7.0f);
    std::vector<int16_t> octs(static_cast<std::size_t>(s.vcount()) * 2);
    const int32_t rc = co_mesh_normals(s.pos.data(), s.vcount(), s.idx.data(), s.icount(), s.face.data(), o.groups,
                                       o.smooth, o.smooth_words, o.sharp.data(),
                                       static_cast<int64_t>(o.sharp.size() / 2), o.auto_deg, o.custom_v.data(),
                                       o.custom_oct.data(), static_cast<int64_t>(o.custom_v.size()), o.eps, out.data(),
                                       octs.data());
    if (rc_out != nullptr) *rc_out = rc;
    if (rc != CO_OK && rc_out == nullptr) {
        std::fprintf(stderr, "co_mesh_normals failed: %s\n", co_shading_last_error());
        ++failures;
    }
    if (oct != nullptr) *oct = octs;
    return out;
}

const float* nrm(const std::vector<float>& n, int32_t v) { return n.data() + 3 * static_cast<std::ptrdiff_t>(v); }

// --- Shapes -----------------------------------------------------------------------------------------------------

// Axis cube [-1, 1]^3: faces 0..5 = +X, -X, +Y, -Y, +Z, -Z, quads (2 triangles), CCW from outside.
Soup cube(std::size_t start = 0) {
    Soup s;
    const std::array<std::vector<V3>, 6> faces = {{
        {{1, -1, -1}, {1, 1, -1}, {1, 1, 1}, {1, -1, 1}},
        {{-1, -1, -1}, {-1, -1, 1}, {-1, 1, 1}, {-1, 1, -1}},
        {{-1, 1, -1}, {-1, 1, 1}, {1, 1, 1}, {1, 1, -1}},
        {{-1, -1, -1}, {1, -1, -1}, {1, -1, 1}, {-1, -1, 1}},
        {{-1, -1, 1}, {1, -1, 1}, {1, 1, 1}, {-1, 1, 1}},
        {{-1, -1, -1}, {-1, 1, -1}, {1, 1, -1}, {1, -1, -1}},
    }};
    for (int32_t f = 0; f < 6; ++f) s.polygon(faces[static_cast<std::size_t>(f)], f, start);
    return s;
}

const std::array<V3, 6> kCubeNormals = {{{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}};

// Sharp pairs for every polygon edge of every face (soup vertex pairs).
std::vector<uint32_t> all_edges(const Soup& s) {
    std::vector<uint32_t> pairs;
    for (const auto& fv : s.face_vertices) {
        for (std::size_t i = 0; i < fv.size(); ++i) {
            pairs.push_back(static_cast<uint32_t>(fv[i]));
            pairs.push_back(static_cast<uint32_t>(fv[(i + 1) % fv.size()]));
        }
    }
    return pairs;
}

// Cylinder of radius 1, height 2 (y -1..1): `sides` side quads (faces 0..sides-1), caps = faces sides (top),
// sides+1 (bottom) as n-gons.
Soup cylinder(int sides) {
    Soup s;
    const auto at = [&](int i, double y) {
        const double a = 2.0 * kPi * i / sides;
        return V3{std::cos(a), y, std::sin(a)};
    };
    for (int i = 0; i < sides; ++i) {
        // outward: (i, -1) -> (i, +1) -> (i+1, +1) -> (i+1, -1) seen from outside is CCW
        s.polygon({at(i, -1), at(i, 1), at(i + 1, 1), at(i + 1, -1)}, i);
    }
    std::vector<V3> top, bottom;
    for (int i = sides - 1; i >= 0; --i) top.push_back(at(i, 1));
    for (int i = 0; i < sides; ++i) bottom.push_back(at(i, -1));
    s.polygon(top, sides);
    s.polygon(bottom, sides + 1);
    return s;
}

// --- Analytic cases ---------------------------------------------------------------------------------------------

void test_cube_flat() {
    const Soup s = cube();
    Opts o;
    o.smooth = kNoWords;
    o.smooth_words = 0;
    const auto n = run(s, o);
    for (int32_t v = 0; v < s.vcount(); ++v) {
        const V3& e = kCubeNormals[static_cast<std::size_t>(s.vertex_face[static_cast<std::size_t>(v)])];
        CHECK(n[3 * static_cast<std::size_t>(v)] == static_cast<float>(e[0]));
        CHECK(n[3 * static_cast<std::size_t>(v) + 1] == static_cast<float>(e[1]));
        CHECK(n[3 * static_cast<std::size_t>(v) + 2] == static_cast<float>(e[2]));
    }
}

void test_cube_smooth() {
    const Soup s = cube();
    const auto n = run(s, Opts{});
    const double k = 1.0 / std::sqrt(3.0);
    for (int32_t v = 0; v < s.vcount(); ++v) {
        const V3& p = s.vertex_pos[static_cast<std::size_t>(v)];
        CHECK(near3(nrm(n, v), {p[0] * k, p[1] * k, p[2] * k}));
    }
}

void test_cube_sharp_equals_flat() {
    const Soup s = cube();
    Opts flat;
    flat.smooth = kNoWords;
    const auto nf = run(s, flat);
    Opts sharp;
    sharp.sharp = all_edges(s);
    const auto ns = run(s, sharp);
    CHECK(nf == ns);
    // Auto smooth 30 degrees on a smooth cube = flat.
    Opts autos;
    autos.auto_deg = 30.0f;
    CHECK(run(s, autos) == nf);
    // Only an angle ABOVE the threshold splits: 91 keeps the cube smooth, 89 splits it.
    autos.auto_deg = 91.0f;
    CHECK(run(s, autos) == run(s, Opts{}));
    autos.auto_deg = 89.0f;
    CHECK(run(s, autos) == nf);
    // One sharp edge on the smooth cube: the corners along it split, the far corners stay (+-1,+-1,+-1).
    const uint32_t a = static_cast<uint32_t>(s.face_vertices[0][2]);  // (1, 1, 1) on +X
    const uint32_t b = static_cast<uint32_t>(s.face_vertices[0][1]);  // (1, 1, -1) on +X
    Opts one;
    one.sharp = {a, b};
    const auto n1 = run(s, one);
    // At (1,1,1) the +X/+Y edge is sharp, but +Y still reaches +X through +Z (both other edges are smooth): the
    // vertex keeps one fan.
    CHECK(near3(nrm(n1, static_cast<int32_t>(a)), {1 / std::sqrt(3.0), 1 / std::sqrt(3.0), 1 / std::sqrt(3.0)}));
}

void test_cylinder_auto_smooth() {
    const int sides = 32;
    const Soup s = cylinder(sides);
    Opts o;
    o.auto_deg = 30.0f;
    const auto n = run(s, o);
    double worst = 0.0;
    for (int32_t v = 0; v < s.vcount(); ++v) {
        const int32_t f = s.vertex_face[static_cast<std::size_t>(v)];
        const V3& p = s.vertex_pos[static_cast<std::size_t>(v)];
        V3 e;
        if (f < sides) {
            e = {p[0], 0, p[2]};  // radial: the side fan has two equal-angle faces symmetric around it
        } else {
            e = f == sides ? V3{0, 1, 0} : V3{0, -1, 0};
        }
        worst = std::max(worst, angle_deg(nrm(n, v), e));
    }
    CHECK(worst < 1e-4);
    // All smooth without auto: the rim corners blend side and cap.
    const auto ns = run(s, Opts{});
    CHECK(angle_deg(nrm(ns, s.face_vertices[0][1]), {1, 1, 0}) < 10.0);
}

void test_corner_angle_weighting() {
    // Apex O with two triangles across edge OB: T1 = O A B (normal +Z, angle 90 at O), T2 = O B C.
    Soup s;
    const V3 O{0, 0, 0}, A{1, 0, 0}, B{0, 1, 0}, C{-1, 1, -1};
    const int32_t t1 = s.polygon({O, A, B}, 0);
    const int32_t t2 = s.polygon({O, B, C}, 1);
    const auto n = run(s, Opts{});
    // Hand computed (Blender: sum of face normal x corner angle):
    //   n1 = (0, 0, 1), angle(O in T1) = 90 deg = pi/2
    //   n2 = normalize(B x C) = (-1, 0, 1) / sqrt(2), angle(O in T2) = acos(B.C / |C|) = acos(1/sqrt(3))
    const double w2 = std::acos(1.0 / std::sqrt(3.0));  // 0.9553166181245093
    const V3 expect_o = v3norm({-w2 / std::sqrt(2.0), 0.0, kPi / 2 + w2 / std::sqrt(2.0)});
    CHECK(near3(nrm(n, t1), expect_o));
    CHECK(near3(nrm(n, t2), expect_o));
    // B: angle in T1 = 45 deg (B->O, B->A), in T2 = 90 deg (B->O, B->C).
    const V3 expect_b = v3norm({-(kPi / 2) / std::sqrt(2.0), 0.0, kPi / 4 + (kPi / 2) / std::sqrt(2.0)});
    CHECK(near3(nrm(n, t1 + 2), expect_b));
    CHECK(near3(nrm(n, t2 + 1), expect_b));
    // Area weighting would differ by more than a degree (T2 is larger): the weighting matters.
    const V3 area = v3norm(v3add(v3scale({0, 0, 1}, 0.5), v3scale(v3norm({-1, 0, 1}), std::sqrt(2.0) / 2)));
    CHECK(angle_deg(nrm(n, t1), area) > 1.0);
    // A and C are on one face each: face normals exactly.
    CHECK(near3(nrm(n, t1 + 1), {0, 0, 1}, 0.0));
}

void test_quad_is_one_face() {
    // A non-planar quad made of two triangles: one face, one (vector area) normal for all four corners.
    Soup s;
    const V3 p0{0, 0, 0}, p1{1, 0, 0.3}, p2{1, 1, 0}, p3{0, 1, 0.3};
    s.polygon({p0, p1, p2, p3}, 0);
    Opts flat;
    flat.smooth = kNoWords;
    const auto nf = run(s, flat);
    const V3 newell = v3norm(v3add(v3cross(v3sub(p1, p0), v3sub(p2, p0)), v3cross(v3sub(p2, p0), v3sub(p3, p0))));
    for (int32_t v = 0; v < 4; ++v) CHECK(near3(nrm(nf, v), newell));
    CHECK(same3(nrm(nf, 0), nrm(nf, 1)) && same3(nrm(nf, 0), nrm(nf, 2)) && same3(nrm(nf, 0), nrm(nf, 3)));
    // Smooth and alone: every corner is a one-corner fan = the face normal (the diagonal is no edge).
    CHECK(run(s, Opts{}) == nf);
    // The same quad triangulated from the other diagonal gives the same normals.
    Soup t;
    t.polygon({p0, p1, p2, p3}, 0, 1);
    const auto nt = run(t, flat);
    for (int32_t v = 0; v < 4; ++v) CHECK(near3(nrm(nt, v), newell, 1e-6));
}

void test_ngons() {
    // Regular hexagon in the XZ plane facing +Y, flat: every corner (0, 1, 0).
    Soup s;
    std::vector<V3> hex;
    for (int i = 5; i >= 0; --i) hex.push_back({std::cos(i * kPi / 3), 0.0, std::sin(i * kPi / 3)});
    s.polygon(hex, 0);
    Opts flat;
    flat.smooth = kNoWords;
    const auto n = run(s, flat);
    for (int32_t v = 0; v < 6; ++v) CHECK(near3(nrm(n, v), {0, 1, 0}));

    // Concave L hexagon (+Z) with a reflex corner R = (1, 1); face F2 hangs on the edge R-(1, 2) from the notch.
    // Blender weights the reflex corner by its 90 deg measure (not 270).
    Soup l;
    const V3 R{1, 1, 0}, Q{1, 2, 0}, T{1.5, 1.5, 1};
    l.polygon({{0, 0, 0}, {2, 0, 0}, {2, 1, 0}, R, Q, {0, 2, 0}}, 0);
    const int32_t f2 = l.polygon({Q, R, T}, 1);
    const auto nl = run(l, Opts{});
    const V3 n2 = v3norm(v3cross(v3sub(R, Q), v3sub(T, Q)));
    const double a2 = std::acos(v3dot(v3norm(v3sub(Q, R)), v3norm(v3sub(T, R))));  // F2's angle at R
    const V3 expect = v3norm(v3add(v3scale({0, 0, 1}, kPi / 2), v3scale(n2, a2)));
    CHECK(near3(nrm(nl, 3), expect));       // L's corner at R
    CHECK(near3(nrm(nl, f2 + 1), expect));  // F2's corner at R
    // A 3/2 pi weight would be visibly different.
    const V3 wrong = v3norm(v3add(v3scale({0, 0, 1}, 1.5 * kPi), v3scale(n2, a2)));
    CHECK(angle_deg(nrm(nl, 3), wrong) > 1.0);
}

// Strip of three quads folded 90 deg twice: A (y = 0, facing -Y... built CCW), B, C.
Soup fold3() {
    Soup s;
    // A: in plane z = 0 (normal +Z), x 0..1, y -1..0; B: plane y = 0... keep it simple: A in XY (normal +Z),
    // B in XZ standing up at y = 1 (normal +Y), C in plane z = 1 (normal -Z ... folded back over A).
    s.polygon({{0, 0, 0}, {1, 0, 0}, {1, 1, 0}, {0, 1, 0}}, 0);   // A: +Z
    s.polygon({{0, 1, 0}, {1, 1, 0}, {1, 1, 1}, {0, 1, 1}}, 1);   // B: -Y... (computed below)
    s.polygon({{0, 1, 1}, {1, 1, 1}, {1, 0, 1}, {0, 0, 1}}, 2);   // C
    return s;
}

void test_mixed_flat_smooth() {
    const Soup s = fold3();
    const auto fn = [&](int32_t f) {
        const auto& fv = s.face_vertices[static_cast<std::size_t>(f)];
        const V3 a = s.vertex_pos[static_cast<std::size_t>(fv[0])], b = s.vertex_pos[static_cast<std::size_t>(fv[1])],
                 c = s.vertex_pos[static_cast<std::size_t>(fv[2])];
        return v3norm(v3cross(v3sub(b, a), v3sub(c, a)));
    };
    const V3 nA = fn(0), nB = fn(1), nC = fn(2);
    const uint32_t words[1] = {0b011};  // A, B smooth; C flat
    Opts o;
    o.smooth = words;
    o.smooth_words = 1;
    const auto n = run(s, o);
    const V3 ab = v3norm(v3add(nA, nB));  // 90 deg each
    const auto& fa = s.face_vertices[0];
    const auto& fb = s.face_vertices[1];
    const auto& fc = s.face_vertices[2];
    CHECK(near3(nrm(n, fa[2]), ab) && near3(nrm(n, fa[3]), ab));     // A at the A-B edge
    CHECK(near3(nrm(n, fb[0]), ab) && near3(nrm(n, fb[1]), ab));     // B at the A-B edge
    CHECK(near3(nrm(n, fa[0]), nA, 0.0) && near3(nrm(n, fa[1]), nA, 0.0));
    // B at the B-C edge: C is flat, so that edge splits; B's corners there are one-corner fans = nB exactly.
    CHECK(near3(nrm(n, fb[2]), nB, 0.0) && near3(nrm(n, fb[3]), nB, 0.0));
    for (const int32_t v : fc) CHECK(near3(nrm(n, v), nC, 0.0));
    // Inconsistent winding: flip B; the A-B edge now runs the same way in both faces -> sharp.
    Soup w;
    w.polygon({{0, 0, 0}, {1, 0, 0}, {1, 1, 0}, {0, 1, 0}}, 0);
    w.polygon({{0, 1, 1}, {1, 1, 1}, {1, 1, 0}, {0, 1, 0}}, 1);
    const auto nw = run(w, Opts{});
    CHECK(near3(nrm(nw, 2), {0, 0, 1}, 0.0));
}

void test_custom_override() {
    const Soup s = cube();
    const float want[3] = {0.6f, 0.8f, 0.0f};
    int16_t oct[2];
    CHECK(co_oct_encode(want, oct, 1) == CO_OK);
    Opts o;
    o.custom_v = {5, 99999, -1};  // out-of-range rows are ignored
    o.custom_oct = {oct[0], oct[1], 1, 1, 2, 2};
    std::vector<int16_t> octs;
    const auto n = run(s, o, &octs);
    CHECK(angle_deg(nrm(n, 5), {0.6, 0.8, 0.0}) < 0.004);
    CHECK(octs[10] == oct[0] && octs[11] == oct[1]);
    // Other vertices unchanged.
    const auto base = run(s, Opts{});
    CHECK(same3(nrm(n, 4), nrm(base, 4)));
}

void test_degenerate() {
    // A smooth quad plus a zero-area (collinear) triangle face on its edge, plus a lone collapsed triangle.
    Soup s;
    s.polygon({{0, 0, 0}, {1, 0, 0}, {1, 1, 0}, {0, 1, 0}}, 0);
    const int32_t deg = s.polygon({{1, 0, 0}, {0, 0, 0}, {0.5, 0, 0}}, 1);  // collinear
    const int32_t lone = s.polygon({{5, 5, 5}, {5, 5, 5}, {5, 5, 5}}, 2);    // one point
    // Plus a quad whose soup contains an extra collapsed sliver triangle (same face).
    const int32_t q = s.polygon({{0, 0, 3}, {1, 0, 3}, {1, 1, 3}, {0, 1, 3}}, 3);
    s.idx.insert(s.idx.end(), {q, q, q + 1});
    s.face.push_back(3);
    for (const bool smooth : {true, false}) {
        Opts o;
        if (!smooth) o.smooth = kNoWords;
        const auto n = run(s, o);
        for (int32_t v = 0; v < s.vcount(); ++v) CHECK(finite_unit(nrm(n, v)));
        for (int32_t v = 0; v < 4; ++v) CHECK(near3(nrm(n, v), {0, 0, 1}, 0.0));
        for (int32_t v = q; v < q + 4; ++v) CHECK(near3(nrm(n, v), {0, 0, 1}, 0.0));
        // The degenerate face's corners take the neighbour's normal (fan when smooth, vertex fallback when flat;
        // its middle corner touches nothing else and falls back to +Z).
        for (int32_t v = deg; v < deg + 3; ++v) CHECK(near3(nrm(n, v), {0, 0, 1}, 0.0));
        for (int32_t v = lone; v < lone + 3; ++v) CHECK(near3(nrm(n, v), {0, 0, 1}, 0.0));
    }
    // A sliver with a tiny real normal far from +Z: a fan of zero weight never produces NaN.
    Soup t;
    t.polygon({{0, 0, 0}, {1, 0, 0}, {0, 0, 1}}, 0);  // normal -Y
    t.polygon({{0, 0, 0}, {0, 0, 1}, {0, 0, 0.5}}, 1);  // collinear on the shared edge
    const auto nt = run(t, Opts{});
    for (int32_t v = 0; v < t.vcount(); ++v) CHECK(finite_unit(nrm(nt, v)));
    CHECK(near3(nrm(nt, 3), {0, -1, 0}, 0.0));
}

void test_non_manifold() {
    // Three quads on one edge (x axis), fanned at 0, 120, 240 degrees: every corner keeps its face normal.
    Soup s;
    for (int i = 0; i < 3; ++i) {
        const double a = 2.0 * kPi * i / 3.0;
        const V3 d{0, std::cos(a), std::sin(a)};
        s.polygon({{0, 0, 0}, {1, 0, 0}, v3add({1, 0, 0}, d), d}, i);
    }
    const auto n = run(s, Opts{});
    Opts flat;
    flat.smooth = kNoWords;
    CHECK(n == run(s, flat));
}

void test_weld_epsilon() {
    const auto two = [](double gap) {
        Soup s;
        s.polygon({{0, 0, 0}, {1, 0, 0}, {1, 1, 0}, {0, 1, 0}}, 0);                      // +Z
        s.polygon({{0, 1 + gap, 0}, {1, 1 + gap, 0}, {1, 1 + gap, 1}, {0, 1 + gap, 1}}, 1);  // -Y side wall
        return s;
    };
    {
        const Soup s = two(5e-5);
        const auto n = run(s, Opts{});
        CHECK(angle_deg(nrm(n, 2), {0, -1, 1}) < 0.01);  // welded: smooth across (the 5e-5 gap tilts it a little)
    }
    {
        const Soup s = two(2e-4);
        const auto n = run(s, Opts{});
        CHECK(near3(nrm(n, 2), {0, 0, 1}, 0.0));  // apart: boundary
        Opts wide;
        wide.eps = 1e-3f;
        const auto nw = run(s, wide);
        CHECK(angle_deg(nrm(nw, 2), {0, -1, 1}) < 0.05);
    }
}

void test_groups() {
    // The same folded pair in two groups: no smoothing across groups; sharp pairs still match by position.
    Soup s;
    s.polygon({{0, 0, 0}, {1, 0, 0}, {1, 1, 0}, {0, 1, 0}}, 0);
    s.polygon({{0, 1, 0}, {1, 1, 0}, {1, 1, 1}, {0, 1, 1}}, 1);
    s.polygon({{0, 0, 5}, {1, 0, 5}, {1, 1, 5}, {0, 1, 5}}, 2);
    s.polygon({{0, 1, 5}, {1, 1, 5}, {1, 1, 6}, {0, 1, 6}}, 3);
    const auto joined = run(s, Opts{});
    CHECK(angle_deg(nrm(joined, 2), {0, -1, 1}) < 1e-4);
    const int32_t groups[8] = {0, 0, 1, 1, 2, 2, 2, 2};
    Opts g;
    g.groups = groups;
    const auto split = run(s, g);
    CHECK(near3(nrm(split, 2), {0, 0, 1}, 0.0));
    CHECK(same3(nrm(split, 10), nrm(joined, 10)));  // group 2 still smooth inside
    // A sharp pair on face 2's soup vertices splits faces 2/3 inside their group.
    g.sharp = {static_cast<uint32_t>(s.face_vertices[2][2]), static_cast<uint32_t>(s.face_vertices[2][3])};
    const auto sharp = run(s, g);
    CHECK(near3(nrm(sharp, s.face_vertices[2][2]), {0, 0, 1}, 0.0));
}

void test_errors() {
    const Soup s = cube();
    std::vector<float> out(static_cast<std::size_t>(s.vcount()) * 3);
    CHECK(co_mesh_normals(s.pos.data(), s.vcount(), s.idx.data(), s.icount() - 1, nullptr, nullptr, nullptr, 0, nullptr,
                          0, 0, nullptr, nullptr, 0, 0, out.data(), nullptr) == CO_ERR_ARG);
    CHECK(std::string(co_shading_last_error()).find("multiple of 3") != std::string::npos);
    std::vector<int32_t> bad = s.idx;
    bad[4] = 999;
    CHECK(co_mesh_normals(s.pos.data(), s.vcount(), bad.data(), s.icount(), nullptr, nullptr, nullptr, 0, nullptr, 0, 0,
                          nullptr, nullptr, 0, 0, out.data(), nullptr) == CO_ERR_RANGE);
    std::vector<int32_t> faces = s.face;
    faces[0] = -3;
    CHECK(co_mesh_normals(s.pos.data(), s.vcount(), s.idx.data(), s.icount(), faces.data(), nullptr, nullptr, 0, nullptr,
                          0, 0, nullptr, nullptr, 0, 0, out.data(), nullptr) == CO_ERR_RANGE);
    CHECK(co_mesh_normals(s.pos.data(), s.vcount(), s.idx.data(), s.icount(), nullptr, nullptr, nullptr, 0, nullptr, 0,
                          0, nullptr, nullptr, 0, 0, nullptr, nullptr) == CO_ERR_ARG);
    // NULL face ids = one face per triangle; an unreferenced vertex gets +Z.
    std::vector<float> pos = s.pos;
    pos.insert(pos.end(), {9, 9, 9});
    std::vector<float> out2(pos.size());
    CHECK(co_mesh_normals(pos.data(), static_cast<int64_t>(pos.size() / 3), s.idx.data(), s.icount(), nullptr, nullptr,
                          kNoWords, 0, nullptr, 0, 0, nullptr, nullptr, 0, 0, out2.data(), nullptr) == CO_OK);
    CHECK(std::string(co_shading_last_error()).empty());
    CHECK(out2[out2.size() - 1] == 1.0f && out2[out2.size() - 2] == 0.0f);
}

void test_sparse_face_ids() {
    // Face ids far apart (the binary-search path) shade exactly like dense ones.
    const Soup s = cube();
    Soup sparse = s;
    for (auto& f : sparse.face) f = f * 1000003 + 7;
    std::vector<uint32_t> words;
    for (int32_t f = 0; f < 6; f += 2) {
        const auto id = static_cast<uint32_t>(f * 1000003 + 7);
        if (words.size() <= id / 32) words.resize(id / 32 + 1, 0u);
        words[id / 32] |= 1u << (id % 32);
    }
    const uint32_t dense_words[1] = {0b010101};
    Opts a, b;
    a.smooth = dense_words;
    a.smooth_words = 1;
    b.smooth = words.data();
    b.smooth_words = static_cast<int64_t>(words.size());
    CHECK(run(s, a) == run(sparse, b));
}

// --- Octahedral codec ------------------------------------------------------------------------------------------

void test_oct() {
    std::mt19937 rng(1234);
    std::normal_distribution<double> g(0.0, 1.0);
    double worst = 0.0, sum = 0.0;
    int count = 0;
    const auto check_one = [&](V3 d) {
        d = v3norm(d);
        const float f[3] = {static_cast<float>(d[0]), static_cast<float>(d[1]), static_cast<float>(d[2])};
        int16_t o[2];
        float back[3];
        co_oct_encode(f, o, 1);
        co_oct_decode(o, back, 1);
        CHECK(o[0] != -32768 && o[1] != -32768);
        const double a = angle_deg(back, {f[0], f[1], f[2]});
        worst = std::max(worst, a);
        sum += a;
        ++count;
    };
    for (int i = 0; i < 200000; ++i) check_one({g(rng), g(rng), g(rng)});
    for (int x = -1; x <= 1; ++x) {
        for (int y = -1; y <= 1; ++y) {
            for (int z = -1; z <= 1; ++z) {
                if (x != 0 || y != 0 || z != 0) check_one({double(x), double(y), double(z)});
            }
        }
    }
    std::printf("oct snorm16: max error %.5f deg, mean %.5f deg over %d normals\n", worst, sum / count, count);
    CHECK(worst < 0.004);
    CHECK(sum / count < 0.002);
    // Axes encode exactly.
    const float axes[18] = {1, 0, 0, -1, 0, 0, 0, 1, 0, 0, -1, 0, 0, 0, 1, 0, 0, -1};
    int16_t o[12];
    float back[18];
    CHECK(co_oct_encode(axes, o, 6) == CO_OK && co_oct_decode(o, back, 6) == CO_OK);
    for (int i = 0; i < 18; ++i) CHECK(back[i] == axes[i]);
    CHECK(o[8] == 0 && o[9] == 0);  // +Z = (0, 0)
    // Zero / non-finite -> +Z; -32768 decodes like -32767.
    const float zero[6] = {0, 0, 0, NAN, 1, 0};
    CHECK(co_oct_encode(zero, o, 2) == CO_OK);
    CHECK(o[0] == 0 && o[1] == 0 && o[2] == 0 && o[3] == 0);
    const int16_t lo[4] = {-32768, 0, -32767, 0};
    co_oct_decode(lo, back, 2);
    CHECK(back[0] == back[3] && back[1] == back[4] && back[2] == back[5]);
    CHECK(co_oct_encode(nullptr, o, 1) == CO_ERR_ARG);
}

// --- Brute-force reference on random polygon meshes -------------------------------------------------------------

struct PolyMesh {
    std::vector<V3> verts;                    // mesh vertices
    std::vector<std::vector<int32_t>> faces;  // loops of mesh vertex ids (CCW = front)
};

// Random heightfield of quads, triangle pairs and 6-corner double cells; some faces flipped.
PolyMesh random_mesh(std::mt19937& rng, int nx, int ny) {
    std::uniform_real_distribution<double> h(0.0, 0.6);
    std::uniform_int_distribution<int> pick(0, 9);
    PolyMesh m;
    for (int j = 0; j <= ny; ++j) {
        for (int i = 0; i <= nx; ++i) m.verts.push_back({double(i), double(j), h(rng)});
    }
    const auto id = [&](int i, int j) { return static_cast<int32_t>(j * (nx + 1) + i); };
    std::vector<uint8_t> used(static_cast<std::size_t>(nx * ny), 0);
    for (int j = 0; j < ny; ++j) {
        for (int i = 0; i < nx; ++i) {
            if (used[static_cast<std::size_t>(j * nx + i)] != 0) continue;
            const int r = pick(rng);
            std::vector<std::vector<int32_t>> add;
            if (r < 2 && i + 1 < nx && used[static_cast<std::size_t>(j * nx + i + 1)] == 0) {
                used[static_cast<std::size_t>(j * nx + i + 1)] = 1;
                add.push_back({id(i, j), id(i + 1, j), id(i + 2, j), id(i + 2, j + 1), id(i + 1, j + 1), id(i, j + 1)});
            } else if (r < 5) {
                add.push_back({id(i, j), id(i + 1, j), id(i + 1, j + 1)});
                add.push_back({id(i, j), id(i + 1, j + 1), id(i, j + 1)});
            } else {
                add.push_back({id(i, j), id(i + 1, j), id(i + 1, j + 1), id(i, j + 1)});
            }
            used[static_cast<std::size_t>(j * nx + i)] = 1;
            for (auto& f : add) {
                if (pick(rng) == 0) std::reverse(f.begin(), f.end());  // inconsistent winding now and then
                m.faces.push_back(f);
            }
        }
    }
    return m;
}

// Blender's rules evaluated straight from the polygon loops, O(F^2) per vertex.
std::vector<V3> reference_corner_normals(const PolyMesh& m, const std::vector<uint8_t>& smooth,
                                          const std::set<std::pair<int32_t, int32_t>>& sharp, double auto_deg,
                                          std::vector<std::vector<V3>>& out) {
    const std::size_t F = m.faces.size();
    std::vector<V3> fn(F);
    for (std::size_t f = 0; f < F; ++f) {
        V3 n{0, 0, 0};
        const auto& L = m.faces[f];
        for (std::size_t i = 0; i < L.size(); ++i) {  // Newell
            const V3& a = m.verts[static_cast<std::size_t>(L[i])];
            const V3& b = m.verts[static_cast<std::size_t>(L[(i + 1) % L.size()])];
            n[0] += (a[1] - b[1]) * (a[2] + b[2]);
            n[1] += (a[2] - b[2]) * (a[0] + b[0]);
            n[2] += (a[0] - b[0]) * (a[1] + b[1]);
        }
        fn[f] = v3norm(n);
    }
    // Directed uses of each undirected edge.
    std::map<std::pair<int32_t, int32_t>, std::vector<std::pair<std::size_t, int>>> uses;
    for (std::size_t f = 0; f < F; ++f) {
        const auto& L = m.faces[f];
        for (std::size_t i = 0; i < L.size(); ++i) {
            const int32_t a = L[i], b = L[(i + 1) % L.size()];
            uses[{std::min(a, b), std::max(a, b)}].push_back({f, a < b ? 1 : -1});
        }
    }
    const double cosine = auto_deg > 0 ? std::cos(auto_deg * kPi / 180.0) : -2.0;
    const auto smooth_edge = [&](int32_t a, int32_t b, std::size_t& other, std::size_t from) {
        const auto& u = uses.at({std::min(a, b), std::max(a, b)});
        if (u.size() != 2 || u[0].first == u[1].first || u[0].second == u[1].second) return false;
        const std::size_t f0 = u[0].first, f1 = u[1].first;
        if (smooth[f0] == 0 || smooth[f1] == 0) return false;
        if (sharp.count({std::min(a, b), std::max(a, b)}) != 0) return false;
        if (v3dot(fn[f0], fn[f1]) < cosine) return false;
        other = f0 == from ? f1 : f0;
        return true;
    };
    out.assign(F, {});
    for (std::size_t f = 0; f < F; ++f) {
        const auto& L = m.faces[f];
        for (std::size_t i = 0; i < L.size(); ++i) {
            if (smooth[f] == 0) {
                out[f].push_back(fn[f]);
                continue;
            }
            const int32_t v = L[i];
            std::vector<std::size_t> fan{f};
            for (std::size_t k = 0; k < fan.size(); ++k) {
                const auto& G = m.faces[fan[k]];
                const auto at = static_cast<std::size_t>(std::find(G.begin(), G.end(), v) - G.begin());
                for (const int32_t x : {G[(at + 1) % G.size()], G[(at + G.size() - 1) % G.size()]}) {
                    std::size_t other = 0;
                    if (smooth_edge(v, x, other, fan[k]) && std::find(fan.begin(), fan.end(), other) == fan.end()) {
                        fan.push_back(other);
                    }
                }
            }
            if (fan.size() == 1) {
                out[f].push_back(fn[f]);
                continue;
            }
            V3 sum{0, 0, 0};
            for (const std::size_t g : fan) {
                const auto& G = m.faces[g];
                const auto at = static_cast<std::size_t>(std::find(G.begin(), G.end(), v) - G.begin());
                const V3& p = m.verts[static_cast<std::size_t>(v)];
                const V3 e1 = v3sub(m.verts[static_cast<std::size_t>(G[(at + 1) % G.size()])], p);
                const V3 e0 = v3sub(m.verts[static_cast<std::size_t>(G[(at + G.size() - 1) % G.size()])], p);
                const double angle = std::acos(std::clamp(v3dot(v3norm(e0), v3norm(e1)), -1.0, 1.0));
                sum = v3add(sum, v3scale(fn[g], angle));
            }
            out[f].push_back(v3norm(sum));
        }
    }
    return fn;
}

// The polygon mesh as a shuffled soup: faces in random order, random triangulation start, then soup vertices
// randomly permuted. Returns the soup and, per soup vertex, (face, loop corner).
Soup soup_of(const PolyMesh& m, std::mt19937& rng, std::vector<std::pair<int32_t, int32_t>>& corner) {
    std::vector<std::size_t> order(m.faces.size());
    for (std::size_t i = 0; i < order.size(); ++i) order[i] = i;
    std::shuffle(order.begin(), order.end(), rng);
    Soup s;
    corner.clear();
    for (const std::size_t f : order) {
        const auto& L = m.faces[f];
        std::vector<V3> loop;
        for (const int32_t v : L) loop.push_back(m.verts[static_cast<std::size_t>(v)]);
        std::uniform_int_distribution<std::size_t> st(0, L.size() - 1);
        // A fan from a reflex corner would fold; the shapes here are convex in plan, so any start works.
        s.polygon(loop, static_cast<int32_t>(f), st(rng));
        for (std::size_t i = 0; i < L.size(); ++i) corner.push_back({static_cast<int32_t>(f), static_cast<int32_t>(i)});
    }
    // Permute soup vertices.
    const std::size_t n = s.vertex_face.size();
    std::vector<int32_t> perm(n);
    for (std::size_t i = 0; i < n; ++i) perm[i] = static_cast<int32_t>(i);
    std::shuffle(perm.begin(), perm.end(), rng);  // old -> new
    Soup p;
    p.pos.resize(n * 3);
    p.vertex_face.resize(n);
    p.vertex_pos.resize(n);
    std::vector<std::pair<int32_t, int32_t>> pc(n);
    for (std::size_t i = 0; i < n; ++i) {
        const auto to = static_cast<std::size_t>(perm[i]);
        std::copy_n(s.pos.data() + 3 * i, 3, p.pos.data() + 3 * to);
        p.vertex_face[to] = s.vertex_face[i];
        p.vertex_pos[to] = s.vertex_pos[i];
        pc[to] = corner[i];
    }
    for (const int32_t v : s.idx) p.idx.push_back(perm[static_cast<std::size_t>(v)]);
    p.face = s.face;
    p.face_vertices.resize(s.face_vertices.size());
    for (std::size_t f = 0; f < s.face_vertices.size(); ++f) {
        for (const int32_t v : s.face_vertices[f]) p.face_vertices[f].push_back(perm[static_cast<std::size_t>(v)]);
    }
    corner = pc;
    return p;
}

void test_random_reference() {
    std::mt19937 rng(20261127);
    double worst = 0.0;
    int corners = 0;
    for (int iter = 0; iter < 40; ++iter) {
        const PolyMesh m = random_mesh(rng, 4 + iter % 5, 3 + iter % 4);
        std::uniform_int_distribution<int> coin(0, 3);
        std::vector<uint8_t> smooth(m.faces.size());
        std::vector<uint32_t> words((m.faces.size() + 31) / 32, 0u);
        for (std::size_t f = 0; f < m.faces.size(); ++f) {
            smooth[f] = coin(rng) != 0 ? 1 : 0;
            if (smooth[f] != 0) words[f / 32] |= 1u << (f % 32);
        }
        std::set<std::pair<int32_t, int32_t>> sharp;
        for (const auto& L : m.faces) {
            for (std::size_t i = 0; i < L.size(); ++i) {
                if (coin(rng) == 0 && coin(rng) == 0) {
                    sharp.insert({std::min(L[i], L[(i + 1) % L.size()]), std::max(L[i], L[(i + 1) % L.size()])});
                }
            }
        }
        const double auto_deg = iter % 3 == 0 ? 0.0 : 15.0 + 5.0 * (iter % 7);
        std::vector<std::vector<V3>> expect;
        reference_corner_normals(m, smooth, sharp, auto_deg, expect);

        std::vector<std::pair<int32_t, int32_t>> corner;
        const Soup s = soup_of(m, rng, corner);
        // Sharp pairs as soup vertices of some face corners on that edge.
        Opts o;
        o.smooth = words.data();
        o.smooth_words = static_cast<int64_t>(words.size());
        o.auto_deg = static_cast<float>(auto_deg);
        for (const auto& [a, b] : sharp) {
            for (std::size_t f = 0; f < m.faces.size(); ++f) {
                const auto& L = m.faces[f];
                const auto ia = std::find(L.begin(), L.end(), a), ib = std::find(L.begin(), L.end(), b);
                if (ia == L.end() || ib == L.end()) continue;
                const auto& fv = s.face_vertices[f];
                o.sharp.push_back(static_cast<uint32_t>(fv[static_cast<std::size_t>(ia - L.begin())]));
                o.sharp.push_back(static_cast<uint32_t>(fv[static_cast<std::size_t>(ib - L.begin())]));
                break;
            }
        }
        const auto n = run(s, o);
        for (int32_t v = 0; v < s.vcount(); ++v) {
            const auto [f, i] = corner[static_cast<std::size_t>(v)];
            const V3& e = expect[static_cast<std::size_t>(f)][static_cast<std::size_t>(i)];
            const double a = angle_deg(nrm(n, v), e);
            worst = std::max(worst, a);
            ++corners;
            if (a > 1e-3) {
                std::fprintf(stderr, "random mesh %d: corner (face %d, %d) off by %.6f deg\n", iter, f, i, a);
                ++failures;
                break;
            }
        }
    }
    std::printf("random reference: %d corners, worst %.2e deg\n", corners, worst);
}

// --- co_doc_compute_normals -----------------------------------------------------------------------------------

co_doc* doc_of(const Soup& s) {
    co_doc* d = co_doc_create();
    co_doc_mesh_set_array(d, CO_MESH_VERTICES, s.pos.data(), static_cast<int64_t>(s.pos.size()));
    co_doc_mesh_set_array(d, CO_MESH_INDICES, s.idx.data(), s.icount());
    co_doc_mesh_set_array(d, CO_MESH_TRIANGLE_FACE_IDS, s.face.data(), static_cast<int64_t>(s.face.size()));
    return d;
}

co_part make_part(const char* id, int32_t vs, int32_t vc, int32_t is, int32_t ic, int32_t fs, int32_t fc) {
    co_part p{};
    p.id = id;
    p.scale[0] = p.scale[1] = p.scale[2] = 1.0f;
    p.vertex_start = vs;
    p.vertex_count = vc;
    p.index_start = is;
    p.index_count = ic;
    p.face_start = fs;
    p.face_count = fc;
    p.visible = 1;
    return p;
}

void test_doc_compute() {
    const Soup s = cube();
    co_doc* d = doc_of(s);
    std::vector<float> out(static_cast<std::size_t>(s.vcount()) * 3);
    std::vector<int16_t> oct(static_cast<std::size_t>(s.vcount()) * 2);
    // No smooth set = all flat.
    CHECK(co_doc_compute_normals(d, out.data(), oct.data()) == CO_OK);
    Opts flat;
    flat.smooth = kNoWords;
    std::vector<int16_t> oct_ref;
    CHECK(out == run(s, flat, &oct_ref));
    CHECK(oct == oct_ref);
    // All smooth + one custom normal + auto smooth 30 -> equals co_mesh_normals with the same inputs.
    const uint32_t words[1] = {0x3F};
    co_doc_shading_set_smooth(d, words, 1);
    const float deg = 30.0f;
    co_doc_shading_set_auto_smooth(d, &deg);
    const int32_t cv[1] = {7};
    const int16_t co[2] = {1000, -2000};
    co_doc_shading_set_custom_normals(d, cv, co, 1);
    CHECK(co_doc_compute_normals(d, out.data(), oct.data()) == CO_OK);
    Opts o;
    o.smooth = words;
    o.smooth_words = 1;
    o.auto_deg = 30.0f;
    o.custom_v = {7};
    o.custom_oct = {1000, -2000};
    CHECK(out == run(s, o, &oct_ref));
    CHECK(oct == oct_ref && oct[14] == 1000 && oct[15] == -2000);
    co_doc_destroy(d);

    // Two parts folded against each other: they shade apart; partless they smooth across.
    Soup f;
    f.polygon({{0, 0, 0}, {1, 0, 0}, {1, 1, 0}, {0, 1, 0}}, 0);
    f.polygon({{0, 1, 0}, {1, 1, 0}, {1, 1, 1}, {0, 1, 1}}, 1);
    co_doc* pd = doc_of(f);
    const uint32_t both[1] = {0x3};
    co_doc_shading_set_smooth(pd, both, 1);
    std::vector<float> o2(static_cast<std::size_t>(f.vcount()) * 3);
    CHECK(co_doc_compute_normals(pd, o2.data(), nullptr) == CO_OK);
    CHECK(angle_deg(nrm(o2, 2), {0, -1, 1}) < 1e-4);
    const co_part parts[2] = {make_part("a", 0, 4, 0, 6, 0, 1), make_part("b", 4, 4, 6, 6, 1, 1)};
    CHECK(co_doc_parts_set(pd, parts, 2) == CO_OK);
    CHECK(co_doc_compute_normals(pd, o2.data(), nullptr) == CO_OK);
    CHECK(near3(nrm(o2, 2), {0, 0, 1}, 0.0));
    CHECK(co_doc_compute_normals(nullptr, o2.data(), nullptr) == CO_ERR_ARG);
    co_doc_destroy(pd);
    co_doc* empty = co_doc_create();
    CHECK(co_doc_compute_normals(empty, o2.data(), nullptr) == CO_ERR_ARG);
    co_doc_destroy(empty);
}

// --- The shading store -----------------------------------------------------------------------------------------

std::vector<float> store_compute(const co_shading* st, const Soup& s, std::vector<int16_t>* oct = nullptr) {
    std::vector<float> out(static_cast<std::size_t>(s.vcount()) * 3, -7.0f);
    std::vector<int16_t> o(static_cast<std::size_t>(s.vcount()) * 2);
    const int32_t rc =
        co_shading_compute(st, s.pos.data(), s.vcount(), s.idx.data(), s.icount(), s.face.data(), out.data(), o.data());
    if (rc != CO_OK) {
        std::fprintf(stderr, "co_shading_compute failed: %s\n", co_shading_last_error());
        ++failures;
    }
    if (oct != nullptr) *oct = o;
    return out;
}

// Per corner (face, mesh position) -> normal, independent of soup order.
std::map<std::tuple<int32_t, double, double, double>, std::array<float, 3>> by_corner(const Soup& s,
                                                                                     const std::vector<float>& n) {
    std::map<std::tuple<int32_t, double, double, double>, std::array<float, 3>> m;
    for (int32_t v = 0; v < s.vcount(); ++v) {
        const V3& p = s.vertex_pos[static_cast<std::size_t>(v)];
        m[{s.vertex_face[static_cast<std::size_t>(v)], p[0], p[1], p[2]}] = {n[3 * static_cast<std::size_t>(v)],
                                                                             n[3 * static_cast<std::size_t>(v) + 1],
                                                                             n[3 * static_cast<std::size_t>(v) + 2]};
    }
    return m;
}

void test_store_resoup() {
    std::mt19937 rng(77);
    const PolyMesh m = random_mesh(rng, 6, 5);
    std::vector<std::pair<int32_t, int32_t>> corner;
    const Soup s1 = soup_of(m, rng, corner);
    co_shading* st = co_shading_create(0.0f);
    CHECK(co_shading_weld_epsilon(st) == CO_SHADING_DEFAULT_WELD_EPSILON);
    CHECK(co_shading_revision(st) == 0);
    // Smooth every other face, sharp the first face's edges, one custom normal.
    std::vector<int32_t> faces;
    for (int32_t f = 0; f < static_cast<int32_t>(m.faces.size()); f += 2) faces.push_back(f);
    CHECK(co_shading_set_smooth(st, faces.data(), static_cast<int64_t>(faces.size()), 1) == CO_OK);
    CHECK(co_shading_is_smooth(st, 0) == 1 && co_shading_is_smooth(st, 1) == 0);
    std::vector<uint32_t> pairs;
    for (std::size_t i = 0; i < s1.face_vertices[2].size(); ++i) {
        pairs.push_back(static_cast<uint32_t>(s1.face_vertices[2][i]));
        pairs.push_back(static_cast<uint32_t>(s1.face_vertices[2][(i + 1) % s1.face_vertices[2].size()]));
    }
    CHECK(co_shading_mark_sharp(st, s1.pos.data(), s1.vcount(), pairs.data(), static_cast<int64_t>(pairs.size() / 2), 1) ==
          CO_OK);
    const int64_t sharp_count = co_shading_sharp_edges(st, nullptr, 0);
    CHECK(sharp_count == static_cast<int64_t>(pairs.size() / 2));
    // Marking again changes nothing.
    const uint64_t rev = co_shading_revision(st);
    co_shading_mark_sharp(st, s1.pos.data(), s1.vcount(), pairs.data(), static_cast<int64_t>(pairs.size() / 2), 1);
    CHECK(co_shading_sharp_edges(st, nullptr, 0) == sharp_count && co_shading_revision(st) == rev);
    const int32_t cv[1] = {s1.face_vertices[4][0]};
    const int16_t co[2] = {-5000, 12000};
    CHECK(co_shading_set_custom_normals(st, s1.pos.data(), s1.vcount(), s1.idx.data(), s1.icount(), s1.face.data(), cv, co,
                                        1) == CO_OK);
    CHECK(co_shading_custom_normals(st, nullptr, nullptr, nullptr, 0) == 1);
    const auto n1 = store_compute(st, s1);
    {
        // Equal to co_mesh_normals with the same data on this soup.
        std::vector<uint32_t> words((m.faces.size() + 31) / 32, 0u);
        for (const int32_t f : faces) words[static_cast<std::size_t>(f) / 32] |= 1u << (f % 32);
        Opts o;
        o.smooth = words.data();
        o.smooth_words = static_cast<int64_t>(words.size());
        o.sharp = pairs;
        o.custom_v = {cv[0]};
        o.custom_oct = {co[0], co[1]};
        CHECK(run(s1, o) == n1);
    }
    // Re-soup (new order, new triangulation starts, permuted vertices): the same corner normals.
    const Soup s2 = soup_of(m, rng, corner);
    const auto n2 = store_compute(st, s2);
    const auto c1 = by_corner(s1, n1), c2 = by_corner(s2, n2);
    CHECK(c1.size() == c2.size());
    double worst = 0.0;
    for (const auto& [k, a] : c1) {
        const auto& b = c2.at(k);
        worst = std::max(worst, angle_deg(b.data(), {a[0], a[1], a[2]}));
    }
    CHECK(worst < 1e-5);
    // Clearing the sharp edges given by soup pairs of the NEW soup removes them.
    std::vector<uint32_t> pairs2;
    for (std::size_t i = 0; i < s2.face_vertices[2].size(); ++i) {
        pairs2.push_back(static_cast<uint32_t>(s2.face_vertices[2][(i + 1) % s2.face_vertices[2].size()]));
        pairs2.push_back(static_cast<uint32_t>(s2.face_vertices[2][i]));
    }
    CHECK(co_shading_mark_sharp(st, s2.pos.data(), s2.vcount(), pairs2.data(), static_cast<int64_t>(pairs2.size() / 2),
                                0) == CO_OK);
    CHECK(co_shading_sharp_edges(st, nullptr, 0) == 0);
    std::vector<uint32_t> bad = {0, 999999};
    CHECK(co_shading_mark_sharp(st, s2.pos.data(), s2.vcount(), bad.data(), 1, 1) == CO_ERR_RANGE);
    // Clone is independent.
    co_shading* copy = co_shading_clone(st);
    co_shading_clear_custom_normals(st, nullptr, 0);
    CHECK(co_shading_custom_normals(st, nullptr, nullptr, nullptr, 0) == 0);
    CHECK(co_shading_custom_normals(copy, nullptr, nullptr, nullptr, 0) == 1);
    const int32_t keep_face[1] = {12345};
    co_shading_clear_custom_normals(copy, keep_face, 1);
    CHECK(co_shading_custom_normals(copy, nullptr, nullptr, nullptr, 0) == 1);
    const int32_t its_face[1] = {s1.vertex_face[static_cast<std::size_t>(cv[0])]};
    co_shading_clear_custom_normals(copy, its_face, 1);
    CHECK(co_shading_custom_normals(copy, nullptr, nullptr, nullptr, 0) == 0);
    const int32_t neg[1] = {-1};
    CHECK(co_shading_set_smooth(copy, neg, 1, 1) == CO_ERR_RANGE);
    CHECK(co_shading_set_auto_smooth(copy, 40.0f) == CO_OK && co_shading_auto_smooth(copy) == 40.0f);
    CHECK(co_shading_set_auto_smooth(copy, NAN) == CO_OK && co_shading_auto_smooth(copy) == 0.0f);
    co_shading_destroy(copy);
    co_shading_destroy(st);
}

void test_store_reconcile() {
    Soup s = cube();
    co_shading* st = co_shading_create(0.0f);
    const int32_t all[6] = {0, 1, 2, 3, 4, 5};
    co_shading_set_smooth(st, all, 6, 1);
    // Sharp: the +X/+Y edge (x = 1, y = 1) and the +Y/+Z edge.
    const auto& px = s.face_vertices[0];  // (1,-1,-1) (1,1,-1) (1,1,1) (1,-1,1)
    const auto& pz = s.face_vertices[4];  // (-1,-1,1) (1,-1,1) (1,1,1) (-1,1,1)
    const uint32_t pairs[4] = {static_cast<uint32_t>(px[1]), static_cast<uint32_t>(px[2]), static_cast<uint32_t>(pz[2]),
                               static_cast<uint32_t>(pz[3])};
    co_shading_mark_sharp(st, s.pos.data(), s.vcount(), pairs, 2, 1);
    // Custom normals on +X and on +Y.
    const int32_t cv[2] = {px[0], s.face_vertices[2][0]};
    const int16_t co[4] = {100, 200, 300, 400};
    co_shading_set_custom_normals(st, s.pos.data(), s.vcount(), s.idx.data(), s.icount(), s.face.data(), cv, co, 2);
    // Delete faces +X (0) and +Y (2): their shared edge vanishes, the +Y/+Z edge stays as +Z's boundary edge.
    Soup t;
    for (int32_t f = 0; f < 6; ++f) {
        if (f == 0 || f == 2) continue;
        std::vector<V3> loop;
        for (const int32_t v : s.face_vertices[static_cast<std::size_t>(f)]) loop.push_back(s.vertex_pos[static_cast<std::size_t>(v)]);
        t.polygon(loop, f);
    }
    int64_t dropped[3] = {-1, -1, -1};
    const uint64_t rev = co_shading_revision(st);
    CHECK(co_shading_reconcile(st, t.pos.data(), t.vcount(), t.idx.data(), t.icount(), t.face.data(), dropped) == CO_OK);
    CHECK(dropped[0] == 2 && dropped[1] == 1 && dropped[2] == 2);
    CHECK(co_shading_revision(st) > rev);
    CHECK(co_shading_is_smooth(st, 0) == 0 && co_shading_is_smooth(st, 4) == 1);
    CHECK(co_shading_sharp_edges(st, nullptr, 0) == 1);
    float e[6];
    co_shading_sharp_edges(st, e, 1);
    CHECK(e[0] == -1 && e[1] == 1 && e[2] == 1 && e[3] == 1 && e[4] == 1 && e[5] == 1);
    // A second reconcile is a no-op.
    const uint64_t rev2 = co_shading_revision(st);
    co_shading_reconcile(st, t.pos.data(), t.vcount(), t.idx.data(), t.icount(), t.face.data(), dropped);
    CHECK(dropped[0] == 0 && dropped[1] == 0 && dropped[2] == 0 && co_shading_revision(st) == rev2);
    co_shading_destroy(st);
}

void test_store_drag() {
    Soup s = cube();
    co_shading* st = co_shading_create(0.0f);
    const int32_t all[6] = {0, 1, 2, 3, 4, 5};
    co_shading_set_smooth(st, all, 6, 1);
    const auto& px = s.face_vertices[0];
    // +X/+Z and +X/+Y at (1,1,1): two sharp edges cut +X out of that vertex's fan (one alone would not).
    const uint32_t pair[4] = {static_cast<uint32_t>(px[2]), static_cast<uint32_t>(px[3]), static_cast<uint32_t>(px[1]),
                              static_cast<uint32_t>(px[2])};
    co_shading_mark_sharp(st, s.pos.data(), s.vcount(), pair, 2, 1);
    const auto before = store_compute(st, s);
    // Drag (1,1,1) -> (1.4,1.2,1.1) in the soup.
    const float from[3] = {1, 1, 1}, to[3] = {1.4f, 1.2f, 1.1f};
    Soup moved = s;
    for (int32_t v = 0; v < moved.vcount(); ++v) {
        float* p = moved.pos.data() + 3 * v;
        if (p[0] == 1 && p[1] == 1 && p[2] == 1) {
            std::copy_n(to, 3, p);
            moved.vertex_pos[static_cast<std::size_t>(v)] = {to[0], to[1], to[2]};
        }
    }
    // Reference: co_mesh_normals with the sharp pair on the moved soup.
    Opts o;
    o.sharp = {pair[0], pair[1], pair[2], pair[3]};
    const auto expect = run(moved, o);
    Opts smooth_only;
    const auto unsharp = run(moved, smooth_only);
    CHECK(expect != unsharp);
    // Without move_vertices the key no longer resolves (the edge renders smooth) ...
    CHECK(store_compute(st, moved) == unsharp);
    // ... with it the edge follows the drag.
    CHECK(co_shading_move_vertices(st, from, to, 1) == CO_OK);
    CHECK(store_compute(st, moved) == expect);
    float e[12];
    CHECK(co_shading_sharp_edges(st, e, 2) == 2);
    for (int k = 0; k < 2; ++k) {
        const float* a = e + 6 * k;
        CHECK((a[3] == to[0] && a[4] == to[1] && a[5] == to[2]) || (a[0] == to[0] && a[1] == to[1] && a[2] == to[2]));
    }
    // Before the drag the split corner was exactly the +X normal.
    CHECK(near3(nrm(before, px[2]), {1, 0, 0}, 0.0));
    co_shading_destroy(st);
}

void test_store_doc_round_trip() {
    std::mt19937 rng(9);
    const PolyMesh m = random_mesh(rng, 5, 4);
    std::vector<std::pair<int32_t, int32_t>> corner;
    const Soup s = soup_of(m, rng, corner);
    co_doc* d = doc_of(s);
    // Smooth faces, sharp pairs (soup pairs of real edges), auto smooth, custom rows.
    std::vector<uint32_t> words((m.faces.size() + 31) / 32, 0u);
    for (std::size_t f = 0; f < m.faces.size(); f += 3) words[f / 32] |= 1u << (f % 32);
    co_doc_shading_set_smooth(d, words.data(), static_cast<int64_t>(words.size()));
    std::vector<uint32_t> pairs;
    for (std::size_t f = 1; f < m.faces.size(); f += 4) {
        const auto& fv = s.face_vertices[f];
        pairs.push_back(static_cast<uint32_t>(fv[0]));
        pairs.push_back(static_cast<uint32_t>(fv[1]));
    }
    co_doc_shading_set_sharp_edges(d, pairs.data(), static_cast<int64_t>(pairs.size() / 2));
    const float deg = 35.0f;
    co_doc_shading_set_auto_smooth(d, &deg);
    const int32_t cv[2] = {s.face_vertices[3][1], s.face_vertices[6][0]};
    const int16_t co[4] = {11, -22, 3333, 4444};
    co_doc_shading_set_custom_normals(d, cv, co, 2);
    std::vector<float> ref(static_cast<std::size_t>(s.vcount()) * 3);
    std::vector<int16_t> ref_oct(static_cast<std::size_t>(s.vcount()) * 2);
    CHECK(co_doc_compute_normals(d, ref.data(), ref_oct.data()) == CO_OK);

    co_shading* st = co_shading_from_doc(d, nullptr, 0.0f);
    CHECK(st != nullptr);
    CHECK(co_shading_auto_smooth(st) == 35.0f);
    std::vector<int16_t> oct;
    CHECK(store_compute(st, s, &oct) == ref);
    CHECK(oct == ref_oct);

    // Into a fresh document with the same mesh: the same normals, the same smooth set, sharp/custom sets equal by
    // position.
    co_doc* d2 = doc_of(s);
    CHECK(co_shading_to_doc(st, d2, nullptr) == CO_OK);
    std::vector<float> out(ref.size());
    CHECK(co_doc_compute_normals(d2, out.data(), nullptr) == CO_OK);
    CHECK(out == ref);
    const uint32_t* w2 = nullptr;
    const int64_t wc = co_doc_shading_smooth(d2, &w2);
    std::vector<uint32_t> trimmed = words;
    while (!trimmed.empty() && trimmed.back() == 0) trimmed.pop_back();
    CHECK(wc == static_cast<int64_t>(trimmed.size()) && std::equal(trimmed.begin(), trimmed.end(), w2));
    const uint32_t* p2 = nullptr;
    CHECK(co_doc_shading_sharp_edges(d2, &p2) == static_cast<int64_t>(pairs.size() / 2));
    const int32_t* rv = nullptr;
    const int16_t* ro = nullptr;
    CHECK(co_doc_shading_custom_normals(d2, &rv, &ro) == 2);
    // Round trip again: the store written by the store reads back identical.
    co_shading* st2 = co_shading_from_doc(d2, nullptr, 0.0f);
    co_doc* d3 = doc_of(s);
    co_shading_to_doc(st2, d3, nullptr);
    co_bytes* b2 = co_doc_write_manifest(d2);
    co_bytes* b3 = co_doc_write_manifest(d3);
    CHECK(b2 != nullptr && b3 != nullptr && co_bytes_size(b2) == co_bytes_size(b3) &&
          std::equal(co_bytes_data(b2), co_bytes_data(b2) + co_bytes_size(b2), co_bytes_data(b3)));
    co_bytes_destroy(b2);
    co_bytes_destroy(b3);
    // A whole write of a store without auto smooth unsets it.
    co_shading_set_auto_smooth(st2, 0.0f);
    co_shading_to_doc(st2, d3, nullptr);
    float got = 0.0f;
    CHECK(co_doc_shading_auto_smooth(d3, &got) == 0);
    co_shading_destroy(st2);
    co_doc_destroy(d3);
    co_doc_destroy(d2);
    co_shading_destroy(st);
    co_doc_destroy(d);
    CHECK(co_shading_from_doc(nullptr, nullptr, 0.0f) == nullptr);
}

void test_store_parts() {
    // Two parts: cube A (faces 0..5, vertices 0..23) and cube B shifted (faces 6..11, vertices 24..47).
    Soup s = cube();
    {
        Soup b = cube(1);
        const int32_t base = static_cast<int32_t>(s.vcount());
        for (int32_t v = 0; v < b.vcount(); ++v) {
            V3 p = b.vertex_pos[static_cast<std::size_t>(v)];
            p[0] += 5.0;
            s.add_vertex(p, b.vertex_face[static_cast<std::size_t>(v)] + 6);
        }
        for (const int32_t i : b.idx) s.idx.push_back(i + base);
        for (const int32_t f : b.face) s.face.push_back(f + 6);
        for (const auto& fv : b.face_vertices) {
            std::vector<int32_t> shifted;
            for (const int32_t v : fv) shifted.push_back(v + base);
            s.face_vertices.push_back(shifted);
        }
    }
    co_doc* d = doc_of(s);
    const co_part parts[2] = {make_part("A", 0, 24, 0, 36, 0, 6), make_part("B", 24, 24, 36, 36, 6, 6)};
    co_doc_parts_set(d, parts, 2);
    // Part A: all smooth, one sharp edge, one custom normal (written by hand into the doc).
    const uint32_t words[1] = {0x3F};
    co_doc_shading_set_smooth(d, words, 1);
    const uint32_t a_pair[2] = {static_cast<uint32_t>(s.face_vertices[0][1]), static_cast<uint32_t>(s.face_vertices[0][2])};
    co_doc_shading_set_sharp_edges(d, a_pair, 1);
    const int32_t a_cv[1] = {3};
    const int16_t a_co[2] = {7, 8};
    co_doc_shading_set_custom_normals(d, a_cv, a_co, 1);

    // Part B's store: local ids; nothing of A leaks in.
    co_shading* sb = co_shading_from_doc(d, &parts[1], 0.0f);
    CHECK(sb != nullptr);
    CHECK(co_shading_smooth_words(sb, nullptr) == 0);
    CHECK(co_shading_sharp_edges(sb, nullptr, 0) == 0);
    CHECK(co_shading_custom_normals(sb, nullptr, nullptr, nullptr, 0) == 0);
    // Edit B: smooth all its (local) faces, mark a sharp edge on its local soup, write back.
    const int32_t local_all[6] = {0, 1, 2, 3, 4, 5};
    co_shading_set_smooth(sb, local_all, 6, 1);
    const float* positions = nullptr;
    {
        const void* data = nullptr;
        co_doc_mesh_array(d, CO_MESH_VERTICES, &data);
        positions = static_cast<const float*>(data) + 24 * 3;
    }
    const uint32_t b_pair[2] = {static_cast<uint32_t>(s.face_vertices[6][0] - 24),
                                static_cast<uint32_t>(s.face_vertices[6][1] - 24)};
    CHECK(co_shading_mark_sharp(sb, positions, 24, b_pair, 1, 1) == CO_OK);
    CHECK(co_shading_to_doc(sb, d, &parts[1]) == CO_OK);
    // A's share kept; B's added.
    const uint32_t* w = nullptr;
    CHECK(co_doc_shading_smooth(d, &w) == 1 && w[0] == 0xFFFu);
    const uint32_t* p = nullptr;
    const int64_t np = co_doc_shading_sharp_edges(d, &p);
    CHECK(np == 2);
    if (np == 2) {
        CHECK(p[0] == a_pair[0] && p[1] == a_pair[1]);
        CHECK(p[2] >= 24 && p[3] >= 24);
    }
    const int32_t* rv = nullptr;
    const int16_t* ro = nullptr;
    CHECK(co_doc_shading_custom_normals(d, &rv, &ro) == 1 && rv[0] == 3);
    // The part store computes what the document computes for part B.
    std::vector<float> whole(static_cast<std::size_t>(s.vcount()) * 3);
    CHECK(co_doc_compute_normals(d, whole.data(), nullptr) == CO_OK);
    Soup local;
    for (int32_t v = 24; v < 48; ++v) local.add_vertex(s.vertex_pos[static_cast<std::size_t>(v)], s.vertex_face[static_cast<std::size_t>(v)] - 6);
    for (std::size_t i = 36; i < 72; ++i) local.idx.push_back(s.idx[i] - 24);
    for (std::size_t t = 12; t < 24; ++t) local.face.push_back(s.face[t] - 6);
    const auto nb = store_compute(sb, local);
    CHECK(std::equal(nb.begin(), nb.end(), whole.begin() + 72));
    // Bad part range.
    const co_part bad = make_part("X", 40, 24, 0, 36, 0, 6);
    CHECK(co_shading_from_doc(d, &bad, 0.0f) == nullptr);
    co_shading_destroy(sb);
    co_doc_destroy(d);
}

// --- Blender fixtures ------------------------------------------------------------------------------------------

void test_blender_fixtures() {
    namespace fs = std::filesystem;
    const fs::path dir(CENDA_NORMALS_FIXTURES);
    int files = 0;
    if (fs::is_directory(dir)) {
        for (const auto& entry : fs::directory_iterator(dir)) {
            if (entry.path().extension() != ".normals") continue;
            ++files;
            std::ifstream in(entry.path());
            Soup s;
            std::vector<uint32_t> words, sharp;
            float deg = 0.0f;
            std::vector<float> expect;
            std::string line;
            while (std::getline(in, line)) {
                std::istringstream ls(line);
                std::string tag;
                if (!(ls >> tag) || tag[0] == '#') continue;
                if (tag == "v") {
                    double x, y, z;
                    ls >> x >> y >> z;
                    s.add_vertex({x, y, z}, -1);
                } else if (tag == "t") {
                    int32_t a, b, c, f;
                    ls >> a >> b >> c >> f;
                    s.idx.insert(s.idx.end(), {a, b, c});
                    s.face.push_back(f);
                } else if (tag == "s") {
                    uint32_t f;
                    ls >> f;
                    if (words.size() <= f / 32) words.resize(f / 32 + 1, 0u);
                    words[f / 32] |= 1u << (f % 32);
                } else if (tag == "e") {
                    uint32_t a, b;
                    ls >> a >> b;
                    sharp.insert(sharp.end(), {a, b});
                } else if (tag == "a") {
                    ls >> deg;
                } else if (tag == "n") {
                    float x, y, z;
                    ls >> x >> y >> z;
                    expect.insert(expect.end(), {x, y, z});
                }
            }
            Opts o;
            o.smooth = words.empty() ? kNoWords : words.data();
            o.smooth_words = static_cast<int64_t>(words.size());
            o.sharp = sharp;
            o.auto_deg = deg;
            const auto n = run(s, o);
            CHECK(expect.size() == n.size());
            double worst = 0.0;
            for (std::size_t v = 0; v < std::min(expect.size(), n.size()) / 3; ++v) {
                worst = std::max(worst, angle_deg(n.data() + 3 * v, {expect[3 * v], expect[3 * v + 1], expect[3 * v + 2]}));
            }
            std::printf("blender fixture %s: %zu corners, worst %.4f deg\n", entry.path().filename().c_str(),
                        n.size() / 3, worst);
            CHECK(worst < 0.5);
        }
    }
    if (files == 0) std::printf("blender fixtures: none in %s (export some with export_blender_normals.py)\n", dir.c_str());
}

}  // namespace

int main() {
    CHECK(co_abi_version() == CO_ABI_VERSION);
    test_cube_flat();
    test_cube_smooth();
    test_cube_sharp_equals_flat();
    test_cylinder_auto_smooth();
    test_corner_angle_weighting();
    test_quad_is_one_face();
    test_ngons();
    test_mixed_flat_smooth();
    test_custom_override();
    test_degenerate();
    test_non_manifold();
    test_weld_epsilon();
    test_groups();
    test_errors();
    test_sparse_face_ids();
    test_oct();
    test_random_reference();
    test_doc_compute();
    test_store_resoup();
    test_store_reconcile();
    test_store_drag();
    test_store_doc_round_trip();
    test_store_parts();
    test_blender_fixtures();
    if (failures != 0) {
        std::fprintf(stderr, "normals: %d check(s) failed\n", failures);
        return 1;
    }
    std::printf("normals: all checks passed\n");
    return 0;
}
