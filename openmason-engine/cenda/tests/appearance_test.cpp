// Cenda character appearance (OpenMason #162) conformance: the record (JSON + binary round trips, unknown slots
// and value types kept, newer versions refused, diff / apply patches), the .omchar appearance section (parsing and
// its refusals), resolution (tints and dye masks, visibility groups, authored hidden parts, equipment hiding groups,
// rigid sockets, view masks), and the acceptance: a skinned tunic follows a skinned test body through every clip
// without opening a gap wider than the body's own skin, both on the body it was made on (leader pose) and fitted onto
// a second body with other proportions. Plain asserts.

#include "cenda/appearance.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <functional>
#include <string>
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

bool near(double a, double b, double eps) { return std::fabs(a - b) <= eps; }

std::string text(cc_bytes* b) {
    std::string s(reinterpret_cast<const char*>(cc_bytes_data(b)), static_cast<size_t>(cc_bytes_size(b)));
    cc_bytes_destroy(b);
    return s;
}

cc_look* read(const std::string& s, int32_t* rc = nullptr) {
    int32_t r = 0;
    cc_look* l = cc_look_read(reinterpret_cast<const uint8_t*>(s.data()), static_cast<int64_t>(s.size()), &r);
    if (rc != nullptr) *rc = r;
    return l;
}

// ---- record ------------------------------------------------------------------------------------------------

void test_record() {
    cc_look* l = cc_look_create();
    cc_look_set_character(l, "stonebreak:player");
    const float skin[3] = {0.78f, 0.55f, 0.42f};
    CHECK(cc_look_set_color(l, "skin", skin) == CC_OK);
    CHECK(cc_look_set_bool(l, "hair", 0) == CC_OK);
    CHECK(cc_look_set_string(l, "head", "stonebreak:iron_helmet") == CC_OK);
    CHECK(cc_look_set_number(l, "height", 1.05f) == CC_OK);
    CHECK(cc_look_set_json(l, "future", "{\"pattern\": \"stripes\", \"scale\": [1, 2]}") == CC_OK);
    CHECK(cc_look_set_string(l, "x", "#aabbcc") == CC_ERR_ARG);  // a colour, not a string
    CHECK(cc_look_set_json(l, "x", "true") == CC_ERR_ARG);
    CHECK(cc_look_count(l) == 5);

    const std::string json = text(cc_look_write_json(l));
    const std::string bin = text(cc_look_write_binary(l));
    std::printf("[appearance] record: JSON %zu bytes, binary %zu bytes\n", json.size(), bin.size());
    CHECK(bin.size() < json.size() / 2);
    cc_look* fromJson = read(json);
    cc_look* fromBin = read(bin);
    CHECK(fromJson != nullptr && fromBin != nullptr);
    CHECK(cc_look_equal(l, fromJson) == 1 && cc_look_equal(l, fromBin) == 1);
    CHECK(text(cc_look_write_json(fromBin)) == json);   // binary -> JSON is byte-identical
    CHECK(text(cc_look_write_binary(fromJson)) == bin);  // and back
    float c[3];
    int32_t type = -1;
    CHECK(cc_look_get(fromBin, cc_look_find(fromBin, "skin"), nullptr, &type, c, nullptr, nullptr, nullptr) == CC_OK);
    CHECK(type == CC_LOOK_COLOR && near(c[0], 199.0 / 255.0, 1e-6) && near(c[2], 107.0 / 255.0, 1e-6));
    CHECK(std::string(cc_look_character(fromBin)) == "stonebreak:player");

    // A slot of a type this reader does not know (a newer writer's), in binary: kept through both encodings.
    std::string future = "OL";
    future += static_cast<char>(1);
    future += static_cast<char>(7);
    future += static_cast<char>(0);  // no character
    future += static_cast<char>(2);  // two entries
    future += static_cast<char>(4);
    future += "skin";
    future += static_cast<char>(CC_LOOK_COLOR);
    future += static_cast<char>(3);
    future += "\x10\x20\x30";
    future += static_cast<char>(5);
    future += "decal";
    future += static_cast<char>(42);  // unknown type
    future += static_cast<char>(4);
    future += "\x01\x02\xfe\xff";
    cc_look* f = read(future);
    CHECK(f != nullptr && cc_look_count(f) == 2);
    const char* hex = nullptr;
    CHECK(cc_look_get(f, 1, nullptr, &type, nullptr, nullptr, nullptr, &hex) == CC_OK && type == 42 &&
          std::string(hex) == "0102feff");
    const std::string fj = text(cc_look_write_json(f));
    CHECK(fj.find("\"$type\": 42") != std::string::npos);
    cc_look* f2 = read(fj);
    CHECK(f2 != nullptr);
    std::string again = text(cc_look_write_binary(f2));
    again[3] = 7;  // the writer stamps its own minor; everything else must match
    CHECK(again == future);

    // Refusals: newer major (both encodings), duplicate slots, garbage.
    int32_t rc = 0;
    std::string newer = bin;
    newer[2] = 2;
    CHECK(read(newer, &rc) == nullptr && rc == CC_ERR_VERSION);
    CHECK(read("{\"format\": \"omlook\", \"version\": \"2.0\", \"slots\": {}}", &rc) == nullptr && rc == CC_ERR_VERSION);
    CHECK(read("{\"format\": \"omlook\", \"version\": \"1.3\", \"slots\": {\"a\": true, \"a\": false}}", &rc) == nullptr &&
          rc == CC_ERR_FORMAT);
    cc_look* minor = read("{\"format\": \"omlook\", \"version\": \"1.3\", \"slots\": {\"a\": true}}", &rc);
    CHECK(minor != nullptr);  // a newer minor reads
    cc_look_destroy(minor);
    CHECK(read(std::string("OL\x01\x00\x05", 5), &rc) == nullptr && rc == CC_ERR_FORMAT);  // truncated
    std::string trailing = bin + "z";
    CHECK(read(trailing, &rc) == nullptr && rc == CC_ERR_FORMAT);

    // Patches: diff -> apply reproduces the target; removals travel as REMOVE entries; a patch round-trips binary.
    cc_look* to = cc_look_clone(l);
    const float red[3] = {1.f, 0.f, 0.f};
    cc_look_set_color(to, "skin", red);
    cc_look_remove(to, "height");
    cc_look_set_bool(to, "cape", 1);
    cc_look* patch = cc_look_diff(l, to);
    CHECK(cc_look_count(patch) == 3);
    cc_look* sent = read(text(cc_look_write_binary(patch)));
    cc_look* applied = cc_look_clone(l);
    CHECK(cc_look_apply(applied, sent) == CC_OK);
    CHECK(cc_look_equal(applied, to) == 1);
    cc_look* none = cc_look_diff(to, to);
    CHECK(cc_look_count(none) == 0);

    for (cc_look* x : {l, fromJson, fromBin, f, f2, to, patch, sent, applied, none}) cc_look_destroy(x);
}

// ---- a skinned test body ----------------------------------------------------------------------------------

enum {
    HIPS, SPINE, CHEST, HEAD, SHOULDER_L, UPPER_ARM_L, LOWER_ARM_L, HAND_L, SHOULDER_R, UPPER_ARM_R, LOWER_ARM_R,
    HAND_R, UPPER_LEG_L, LOWER_LEG_L, FOOT_L, UPPER_LEG_R, LOWER_LEG_R, FOOT_R, JOINTS
};
const char* kNames[JOINTS] = {"hips",        "spine",       "chest",       "head",        "shoulder_l", "upper_arm_l",
                              "lower_arm_l", "hand_l",      "shoulder_r",  "upper_arm_r", "lower_arm_r", "hand_r",
                              "upper_leg_l", "lower_leg_l", "foot_l",      "upper_leg_r", "lower_leg_r", "foot_r"};

cr_joint_desc bone(int parent, float px, float py, float pz, float ex, float ey, float ez) {
    cr_joint_desc d{};
    d.parent = parent;
    d.kind = CR_JOINT_BONE;
    d.flags = CR_JOINT_HAS_GEOMETRY;
    d.pos[0] = px;
    d.pos[1] = py;
    d.pos[2] = pz;
    d.scale[0] = d.scale[1] = d.scale[2] = 1.f;
    d.endpoint[0] = ex;
    d.endpoint[1] = ey;
    d.endpoint[2] = ez;
    return d;
}

// Proportions of a body (legs, arms, torso length scale; torso / limb radius).
struct Build {
    float leg = 1.f, arm = 1.f, torso = 1.f, girth = 1.f;
};

// The character's own left is -X (it faces -Z).
cr_rig* make_rig(const Build& b) {
    const float L = b.leg, A = b.arm, T = b.torso;
    cr_joint_desc j[JOINTS];
    j[HIPS] = bone(-1, 0, 0.9f * L, 0, 0, 0.15f * T, 0);
    j[SPINE] = bone(HIPS, 0, 0, 0, 0, 0.2f * T, 0);
    j[CHEST] = bone(SPINE, 0, 0, 0, 0, 0.2f * T, 0);
    j[HEAD] = bone(CHEST, 0, 0.05f, 0, 0, 0.25f, 0);
    j[SHOULDER_L] = bone(CHEST, 0, -0.03f, 0, -0.15f * b.girth, 0, 0);
    j[UPPER_ARM_L] = bone(SHOULDER_L, 0, 0, 0, 0, -0.28f * A, 0);
    j[LOWER_ARM_L] = bone(UPPER_ARM_L, 0, 0, 0, 0, -0.26f * A, 0);
    j[HAND_L] = bone(LOWER_ARM_L, 0, 0, 0, 0, -0.08f, 0);
    j[SHOULDER_R] = bone(CHEST, 0, -0.03f, 0, 0.15f * b.girth, 0, 0);
    j[UPPER_ARM_R] = bone(SHOULDER_R, 0, 0, 0, 0, -0.28f * A, 0);
    j[LOWER_ARM_R] = bone(UPPER_ARM_R, 0, 0, 0, 0, -0.26f * A, 0);
    j[HAND_R] = bone(LOWER_ARM_R, 0, 0, 0, 0, -0.08f, 0);
    j[UPPER_LEG_L] = bone(HIPS, -0.1f * b.girth, 0, 0, 0, -0.42f * L, 0);
    j[LOWER_LEG_L] = bone(UPPER_LEG_L, 0, 0, 0, 0, -0.4f * L, 0);
    j[FOOT_L] = bone(LOWER_LEG_L, 0, 0, 0, 0, 0, -0.12f);
    j[UPPER_LEG_R] = bone(HIPS, 0.1f * b.girth, 0, 0, 0, -0.42f * L, 0);
    j[LOWER_LEG_R] = bone(UPPER_LEG_R, 0, 0, 0, 0, -0.4f * L, 0);
    j[FOOT_R] = bone(LOWER_LEG_R, 0, 0, 0, 0, 0, -0.12f);
    return cr_rig_create(j, JOINTS, kNames, kNames);
}

cc_char* make_char(const cr_rig* rig, const char* id) {
    cc_char* c = cc_char_create();
    cc_char_set_string(c, CC_FIELD_OBJECT_ID, id);
    CHECK(cc_char_set_skeleton(c, rig) == CC_OK);
    for (int j = 0; j < JOINTS; ++j) cc_char_role_set(c, kNames[j], kNames[j]);
    return c;
}

struct Mesh {
    std::vector<float> pos;
    std::vector<int32_t> tri;
    cr_skin* skin = nullptr;
    // Pieces in build order (torso, arm/sleeve l, leg l, arm/sleeve r, leg r): their vertex and triangle ranges.
    std::vector<std::array<size_t, 4>> pieces;  // first vertex, vertex count, first triangle index, index count
};

// Bone head / tail points in rest model space.
void rest_points(const cr_rig* rig, int j, float head[3], float tail[3]) {
    std::vector<float> world(JOINTS * 16), child(JOINTS * 16);
    cr_rig_rest_world(rig, world.data(), child.data());
    for (int c = 0; c < 3; ++c) {
        head[c] = world[static_cast<size_t>(j) * 16 + 12 + static_cast<size_t>(c)];
        tail[c] = child[static_cast<size_t>(j) * 16 + 12 + static_cast<size_t>(c)];
    }
}

// A closed-ish tube (rings around the polyline through `points`, radius per point), appended to `m`. Returns the
// first vertex.
int32_t tube(Mesh& m, const std::vector<std::array<float, 3>>& points, const std::vector<float>& radius, int rings_per_seg,
             int sides) {
    const int32_t first = static_cast<int32_t>(m.pos.size() / 3);
    std::vector<std::array<float, 3>> path;
    std::vector<float> rad;
    for (size_t s = 0; s + 1 < points.size(); ++s) {
        for (int r = 0; r < rings_per_seg; ++r) {
            const float t = static_cast<float>(r) / static_cast<float>(rings_per_seg);
            std::array<float, 3> p{};
            for (int c = 0; c < 3; ++c) p[static_cast<size_t>(c)] = points[s][static_cast<size_t>(c)] * (1 - t) + points[s + 1][static_cast<size_t>(c)] * t;
            path.push_back(p);
            rad.push_back(radius[s] * (1 - t) + radius[s + 1] * t);
        }
    }
    path.push_back(points.back());
    rad.push_back(radius.back());
    for (size_t i = 0; i < path.size(); ++i) {
        // Frame: the local direction, and two perpendiculars.
        const auto& a = path[i == 0 ? 0 : i - 1];
        const auto& b = path[i + 1 < path.size() ? i + 1 : i];
        double d[3] = {b[0] - a[0], b[1] - a[1], b[2] - a[2]};
        const double ld = std::sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
        for (double& x : d) x /= ld;
        double u[3] = {0, 0, 1};
        if (std::fabs(d[2]) > 0.9) {
            u[0] = 1;
            u[2] = 0;
        }
        double v[3] = {d[1] * u[2] - d[2] * u[1], d[2] * u[0] - d[0] * u[2], d[0] * u[1] - d[1] * u[0]};
        const double lv = std::sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        for (double& x : v) x /= lv;
        const double w[3] = {d[1] * v[2] - d[2] * v[1], d[2] * v[0] - d[0] * v[2], d[0] * v[1] - d[1] * v[0]};
        for (int s = 0; s < sides; ++s) {
            const double ang = 2.0 * 3.14159265358979323846 * s / sides;
            for (int c = 0; c < 3; ++c) {
                m.pos.push_back(static_cast<float>(path[i][static_cast<size_t>(c)] + rad[i] * (std::cos(ang) * v[c] + std::sin(ang) * w[c])));
            }
        }
    }
    const int32_t rows = static_cast<int32_t>(path.size());
    for (int32_t r = 0; r + 1 < rows; ++r) {
        for (int s = 0; s < sides; ++s) {
            const int32_t a = first + r * sides + s, b = first + r * sides + (s + 1) % sides;
            const int32_t c = a + sides, d2 = b + sides;
            m.tri.insert(m.tri.end(), {a, c, b, b, c, d2});
        }
    }
    return first;
}

std::array<float, 3> pt(const cr_rig* rig, int j, bool tail) {
    float h[3], t[3];
    rest_points(rig, j, h, t);
    const float* p = tail ? t : h;
    return {p[0], p[1], p[2]};
}

// The body: a torso tube (hips..chest top), two arm tubes and two leg tubes, auto-weighted per region.
Mesh make_body(const cr_rig* rig, const Build& b) {
    Mesh m;
    const float g = b.girth;
    struct Region {
        int32_t first, count;
        std::vector<int32_t> bones;
    };
    std::vector<Region> regions;
    auto add = [&](std::vector<std::array<float, 3>> pts, std::vector<float> rad, int rings, int sides, std::vector<int32_t> bones) {
        const size_t tri0 = m.tri.size();
        const int32_t first = tube(m, pts, rad, rings, sides);
        regions.push_back({first, static_cast<int32_t>(m.pos.size() / 3) - first, std::move(bones)});
        m.pieces.push_back({static_cast<size_t>(first), m.pos.size() / 3 - static_cast<size_t>(first), tri0, m.tri.size() - tri0});
    };
    add({pt(rig, HIPS, false), pt(rig, SPINE, false), pt(rig, CHEST, false), pt(rig, CHEST, true)},
        {0.14f * g, 0.13f * g, 0.15f * g, 0.14f * g}, 4, 14, {HIPS, SPINE, CHEST});
    for (int side = 0; side < 2; ++side) {
        const int sh = side == 0 ? SHOULDER_L : SHOULDER_R;
        add({pt(rig, sh, false), pt(rig, sh + 1, false), pt(rig, sh + 2, false), pt(rig, sh + 3, false), pt(rig, sh + 3, true)},
            {0.06f * g, 0.055f * g, 0.045f * g, 0.035f * g, 0.03f * g}, 5, 10, {CHEST, sh, sh + 1, sh + 2, sh + 3});
        const int ul = side == 0 ? UPPER_LEG_L : UPPER_LEG_R;
        add({pt(rig, ul, false), pt(rig, ul + 1, false), pt(rig, ul + 2, false)}, {0.075f * g, 0.055f * g, 0.045f * g}, 6, 10,
            {HIPS, ul, ul + 1, ul + 2});
    }
    const int32_t n = static_cast<int32_t>(m.pos.size() / 3);
    m.skin = cr_skin_create(n);
    for (const Region& r : regions) {
        CHECK(cr_skin_auto_weights(m.skin, rig, m.pos.data(), r.first, r.count, r.bones.data(),
                                   static_cast<int32_t>(r.bones.size()), 2.f, 0.01f, 0) == CR_OK);
    }
    return m;
}

// The tunic: one tube over the torso (a little looser than it, other ring counts) and two sleeves to mid forearm.
Mesh make_tunic(const cr_rig* rig, const Build& b, float clearance) {
    Mesh m;
    const float g = b.girth, c = clearance;
    struct Region {
        int32_t first, count;
        std::vector<int32_t> bones;
    };
    std::vector<Region> regions;
    auto add = [&](std::vector<std::array<float, 3>> pts, std::vector<float> rad, int rings, int sides, std::vector<int32_t> bones) {
        const size_t tri0 = m.tri.size();
        const int32_t first = tube(m, pts, rad, rings, sides);
        regions.push_back({first, static_cast<int32_t>(m.pos.size() / 3) - first, std::move(bones)});
        m.pieces.push_back({static_cast<size_t>(first), m.pos.size() / 3 - static_cast<size_t>(first), tri0, m.tri.size() - tri0});
    };
    add({pt(rig, HIPS, false), pt(rig, SPINE, false), pt(rig, CHEST, false), pt(rig, CHEST, true)},
        {0.14f * g + c, 0.13f * g + c, 0.15f * g + c, 0.14f * g + c}, 3, 18, {HIPS, SPINE, CHEST});
    for (int side = 0; side < 2; ++side) {
        const int sh = side == 0 ? SHOULDER_L : SHOULDER_R;
        auto mid = pt(rig, sh + 2, false);
        const auto wrist = pt(rig, sh + 3, false);
        for (int k = 0; k < 3; ++k) mid[static_cast<size_t>(k)] = 0.5f * (mid[static_cast<size_t>(k)] + wrist[static_cast<size_t>(k)]);
        add({pt(rig, sh, false), pt(rig, sh + 1, false), pt(rig, sh + 2, false), mid},
            {0.06f * g + c, 0.055f * g + c, 0.045f * g + c, 0.04f * g + c}, 4, 12, {CHEST, sh, sh + 1, sh + 2});
    }
    const int32_t n = static_cast<int32_t>(m.pos.size() / 3);
    m.skin = cr_skin_create(n);
    for (const Region& r : regions) {
        CHECK(cr_skin_auto_weights(m.skin, rig, m.pos.data(), r.first, r.count, r.bones.data(),
                                   static_cast<int32_t>(r.bones.size()), 2.f, 0.01f, 0) == CR_OK);
    }
    return m;
}

// Clips as local rotation programs (degrees about X / Y / Z of the joints), sampled over one cycle.
struct Clip {
    const char* name;
    std::function<void(float t, std::vector<float>& locals)> pose;
};

void rot(std::vector<float>& l, int j, float x, float y, float z) {
    l[static_cast<size_t>(j) * 9 + 3] += x;
    l[static_cast<size_t>(j) * 9 + 4] += y;
    l[static_cast<size_t>(j) * 9 + 5] += z;
}

std::vector<Clip> clips() {
    const float pi2 = 2.f * 3.14159265f;
    return {
        {"walk", [=](float t, std::vector<float>& l) {
             const float s = std::sin(pi2 * t);
             rot(l, UPPER_LEG_L, 35 * s, 0, 0);
             rot(l, UPPER_LEG_R, -35 * s, 0, 0);
             rot(l, LOWER_LEG_L, -30 * std::max(0.f, -s), 0, 0);
             rot(l, LOWER_LEG_R, -30 * std::max(0.f, s), 0, 0);
             rot(l, UPPER_ARM_L, -30 * s, 0, 0);
             rot(l, UPPER_ARM_R, 30 * s, 0, 0);
             rot(l, LOWER_ARM_L, 25 + 15 * s, 0, 0);
             rot(l, LOWER_ARM_R, 25 - 15 * s, 0, 0);
             rot(l, SPINE, 0, 8 * s, 0);
         }},
        {"crouch", [=](float t, std::vector<float>& l) {
             const float k = 0.5f - 0.5f * std::cos(pi2 * t);
             rot(l, UPPER_LEG_L, 80 * k, 0, 0);
             rot(l, UPPER_LEG_R, 80 * k, 0, 0);
             rot(l, LOWER_LEG_L, -110 * k, 0, 0);
             rot(l, LOWER_LEG_R, -110 * k, 0, 0);
             rot(l, SPINE, 25 * k, 0, 0);
             l[HIPS * 9 + 1] -= 0.3f * k;
         }},
        {"reach", [=](float t, std::vector<float>& l) {
             const float k = 0.5f - 0.5f * std::cos(pi2 * t);
             rot(l, UPPER_ARM_L, 0, 0, -150 * k);
             rot(l, UPPER_ARM_R, -100 * k, 0, 0);
             rot(l, LOWER_ARM_R, 110 * k, 0, 0);
             rot(l, CHEST, 0, 0, 10 * k);
         }},
        {"twist", [=](float t, std::vector<float>& l) {
             const float s = std::sin(pi2 * t);
             rot(l, SPINE, 0, 30 * s, 0);
             rot(l, CHEST, 0, 25 * s, 0);
             rot(l, UPPER_ARM_L, 0, 0, -40);
             rot(l, UPPER_ARM_R, 0, 0, 40);
         }},
        {"wave", [=](float t, std::vector<float>& l) {
             const float s = std::sin(2 * pi2 * t);
             rot(l, UPPER_ARM_R, 0, 0, 120);
             rot(l, LOWER_ARM_R, 60 + 40 * s, 0, 0);
             rot(l, LOWER_ARM_L, 90, 0, 0);
             rot(l, UPPER_ARM_L, -60, 0, 0);
         }},
    };
}

// Point-triangle distance (Ericson, Real-Time Collision Detection 5.1.5).
double point_triangle(const double p[3], const float* a, const float* b, const float* c) {
    auto sub = [](const double* x, const float* y, double* o) { for (int i = 0; i < 3; ++i) o[i] = x[i] - y[i]; };
    auto subf = [](const float* x, const float* y, double* o) { for (int i = 0; i < 3; ++i) o[i] = double(x[i]) - y[i]; };
    auto dot3 = [](const double* x, const double* y) { return x[0] * y[0] + x[1] * y[1] + x[2] * y[2]; };
    double ab[3], ac[3], ap[3];
    subf(b, a, ab);
    subf(c, a, ac);
    sub(p, a, ap);
    const double d1 = dot3(ab, ap), d2 = dot3(ac, ap);
    double q[3];
    auto at = [&](double u, double v) { for (int i = 0; i < 3; ++i) q[i] = a[i] + u * ab[i] + v * ac[i]; };
    if (d1 <= 0 && d2 <= 0) {
        at(0, 0);
    } else {
        double bp[3];
        sub(p, b, bp);
        const double d3 = dot3(ab, bp), d4 = dot3(ac, bp);
        double cp[3];
        sub(p, c, cp);
        const double d5 = dot3(ab, cp), d6 = dot3(ac, cp);
        const double vc = d1 * d4 - d3 * d2, vb = d5 * d2 - d1 * d6, va = d3 * d6 - d5 * d4;
        if (d3 >= 0 && d4 <= d3) {
            at(1, 0);
        } else if (vc <= 0 && d1 >= 0 && d3 <= 0) {
            at(d1 / (d1 - d3), 0);
        } else if (d6 >= 0 && d5 <= d6) {
            at(0, 1);
        } else if (vb <= 0 && d2 >= 0 && d6 <= 0) {
            at(0, d2 / (d2 - d6));
        } else if (va <= 0 && (d4 - d3) >= 0 && (d5 - d6) >= 0) {
            const double w = (d4 - d3) / ((d4 - d3) + (d5 - d6));
            at(1 - w, w);
        } else {
            const double den = 1.0 / (va + vb + vc);
            at(vb * den, vc * den);
        }
    }
    const double dx = p[0] - q[0], dy = p[1] - q[1], dz = p[2] - q[2];
    return std::sqrt(dx * dx + dy * dy + dz * dz);
}

// The body piece each garment piece covers: the tunic's torso -> the torso, each sleeve -> its arm.
size_t covered(size_t garment_piece) { return garment_piece == 0 ? 0 : (garment_piece == 1 ? 1 : 3); }

// Per garment vertex: the distance to the body piece its garment piece covers (a sleeve is measured against its
// arm, never against the torso it brushes at the shoulder).
std::vector<double> clearance(const std::vector<float>& garment, const Mesh& g, const std::vector<float>& body, const Mesh& b) {
    const size_t n = garment.size() / 3;
    std::vector<double> d(n, 1e300);
    const std::vector<int32_t>& tri = b.tri;
    for (size_t piece = 0; piece < g.pieces.size(); ++piece) {
      const auto& bp = b.pieces[covered(piece)];
      for (size_t v = g.pieces[piece][0]; v < g.pieces[piece][0] + g.pieces[piece][1]; ++v) {
        const double p[3] = {garment[v * 3], garment[v * 3 + 1], garment[v * 3 + 2]};
        for (size_t t = bp[2]; t < bp[2] + bp[3]; t += 3) {
            const double dist = point_triangle(p, &body[static_cast<size_t>(tri[t]) * 3], &body[static_cast<size_t>(tri[t + 1]) * 3],
                                               &body[static_cast<size_t>(tri[t + 2]) * 3]);
            d[v] = std::min(d[v], dist);
        }
      }
    }
    return d;
}

// The widest a body edge stretches beyond its rest length: how far the body's own skin pulls apart.
double body_gap(const std::vector<float>& rest, const std::vector<float>& posed, const std::vector<int32_t>& tri) {
    double worst = 0.0;
    auto len = [](const std::vector<float>& p, int32_t a, int32_t b) {
        double s = 0;
        for (int c = 0; c < 3; ++c) {
            const double d = p[static_cast<size_t>(a) * 3 + static_cast<size_t>(c)] - p[static_cast<size_t>(b) * 3 + static_cast<size_t>(c)];
            s += d * d;
        }
        return std::sqrt(s);
    };
    for (size_t t = 0; t < tri.size(); t += 3) {
        for (int e = 0; e < 3; ++e) {
            const int32_t a = tri[t + static_cast<size_t>(e)], b = tri[t + static_cast<size_t>((e + 1) % 3)];
            worst = std::max(worst, len(posed, a, b) - len(rest, a, b));
        }
    }
    return worst;
}

std::vector<float> deform(const cr_rig* rig, const cr_skin* skin, const std::vector<float>& rest, const std::vector<float>& locals) {
    std::vector<float> k(JOINTS * 16);
    CHECK(cr_rig_pose_locals(rig, locals.data(), nullptr, 0, nullptr, k.data(), nullptr, nullptr) == CR_OK);
    const int32_t n = static_cast<int32_t>(rest.size() / 3);
    std::vector<int32_t> part(static_cast<size_t>(n), HIPS);
    std::vector<float> out(rest.size());
    CHECK(cr_skin_deform(skin, part.data(), rest.data(), n, k.data(), JOINTS, out.data()) == CR_OK);
    return out;
}

struct Result {
    double worst_lift = 0, worst_body_gap = 0, worst_ratio = 0;
    int failing_frames = 0, frames = 0;
    std::vector<double> clip_lift, clip_gap;  // per clip, in clips() order
};

// The printed numbers before rig ABI 3 (#170, 2026-11-27): per clip (walk, crouch, reach, twist, wave) the tunic's
// lift and the body's own stretch, to the 4 decimals they were printed with. Unweighted -> unorm16 deform and keeping
// every auto-weight influence must not move them: with falloff 2 / prune 0.01 no vertex keeps a 5th bone, and the
// quantized weights move a vertex by < 1e-4.
constexpr double kPrinted = 0.5e-4 + 1e-9;
const double kLiftA[5] = {0.0038, 0.0017, 0.0156, 0.0140, 0.0078};
const double kGapA[5] = {0.0423, 0.1072, 0.0668, 0.0302, 0.0652};
const double kLiftB[5] = {0.0044, 0.0006, 0.0123, 0.0090, 0.0078};
const double kGapB[5] = {0.0517, 0.1250, 0.0719, 0.0314, 0.0695};

void check_printed(const Result& r, const double* lift, const double* gap) {
    CHECK(r.clip_lift.size() == 5 && r.clip_gap.size() == 5);
    for (size_t c = 0; c < r.clip_lift.size() && c < 5; ++c) {
        CHECK(std::fabs(r.clip_lift[c] - lift[c]) <= kPrinted);
        CHECK(std::fabs(r.clip_gap[c] - gap[c]) <= kPrinted);
    }
}

// Runs every clip; per frame the tunic's lift off the body (clearance - rest clearance, its widest vertex) must
// stay within the body's own widest skin stretch that frame.
Result follow(const cr_rig* rig, const Mesh& body, const Mesh& pieces, const std::vector<float>& garment, const cr_skin* gskin,
              const char* label) {
    Result r;
    const std::vector<double> rest = clearance(garment, pieces, body.pos, body);
    std::vector<float> rest_locals(JOINTS * 9);
    CHECK(cr_rig_local_pose(rig, nullptr, 0.f, nullptr, 0, rest_locals.data()) == CR_OK);
    for (const Clip& c : clips()) {
        double clip_lift = 0, clip_gap = 0;
        for (int f = 0; f < 16; ++f) {
            std::vector<float> l = rest_locals;
            c.pose(static_cast<float>(f) / 16.f, l);
            const std::vector<float> bp = deform(rig, body.skin, body.pos, l);
            const std::vector<float> gp = deform(rig, gskin, garment, l);
            const std::vector<double> d = clearance(gp, pieces, bp, body);
            double lift = 0;
            size_t worst_v = 0;
            for (size_t v = 0; v < d.size(); ++v) {
                if (d[v] - rest[v] > lift) {
                    lift = d[v] - rest[v];
                    worst_v = v;
                }
            }
            const double gap = body_gap(body.pos, bp, body.tri);
            if (std::getenv("APPEARANCE_DEBUG") != nullptr && lift > gap) {
                std::printf("  %s f%d lift %.4f gap %.4f vertex %zu rest (%.3f %.3f %.3f) clearance %.4f -> %.4f\n", c.name, f,
                            lift, gap, worst_v, garment[worst_v * 3], garment[worst_v * 3 + 1], garment[worst_v * 3 + 2],
                            rest[worst_v], d[worst_v]);
            }
            ++r.frames;
            if (lift > gap) ++r.failing_frames;
            clip_lift = std::max(clip_lift, lift);
            clip_gap = std::max(clip_gap, gap);
            r.worst_ratio = std::max(r.worst_ratio, gap > 0 ? lift / gap : 0.0);
        }
        std::printf("[appearance] %s %-7s tunic lift %.4f, body's own stretch %.4f\n", label, c.name, clip_lift, clip_gap);
        r.clip_lift.push_back(clip_lift);
        r.clip_gap.push_back(clip_gap);
        r.worst_lift = std::max(r.worst_lift, clip_lift);
        r.worst_body_gap = std::max(r.worst_body_gap, clip_gap);
    }
    return r;
}

void test_garment_follows() {
    const Build a{};
    cr_rig* rig = make_rig(a);
    cc_char* chr = make_char(rig, "test:body_a");
    // A non-trivial reference pose on A (arms out, spine bent): the fit goes through it.
    const float out_l[3] = {10.f, 0.f, -35.f}, out_r[3] = {-5.f, 0.f, 35.f}, bend[3] = {8.f, 12.f, 0.f};
    cc_char_pose_set(chr, "upper_arm_l", out_l);
    cc_char_pose_set(chr, "upper_arm_r", out_r);
    cc_char_pose_set(chr, "spine", bend);
    Mesh body = make_body(rig, a);
    const float clear = 0.015f;
    Mesh tunic = make_tunic(rig, a, clear);
    std::printf("[appearance] body %zu vertices / %zu triangles, tunic %zu vertices\n", body.pos.size() / 3,
                body.tri.size() / 3, tunic.pos.size() / 3);
    // Rig ABI 3 (#170): auto weights consider every bone of a region, no longer only the 4 nearest. The arm regions
    // list 5 bones (chest, shoulder, upper arm, forearm, hand), but with falloff 2 the 5th-nearest bone's share is
    // below the 1% prune everywhere (max 3 influences per vertex), so every number this test prints is the same as
    // under ABI 2 (checked 2026-11-27: identical output before / after the change). The garment with more than 4
    // influences is the "wide" tunic further down.
    int32_t five = 0;
    for (int32_t v = 0; v < cr_skin_vertex_count(body.skin); ++v) five += cr_skin_influence_count(body.skin, v) > 4 ? 1 : 0;
    std::printf("[appearance] body: %d vertices keep more than 4 influences (max %d)\n", five, cr_skin_max_influences(body.skin));

    // Leader pose on the body it was made on: positions unchanged, joints by name.
    cc_garment_desc d{};
    d.joint_refs = kNames;
    d.ref_count = JOINTS;
    d.positions = tunic.pos.data();
    d.vertex_count = static_cast<int32_t>(tunic.pos.size() / 3);
    d.skin = tunic.skin;
    int32_t unresolved = -1;
    cc_garment* g = cc_garment_create(chr, rig, &d, &unresolved);
    CHECK(g != nullptr && unresolved == 0);
    std::vector<float> fitted(tunic.pos.size());
    cc_garment_positions(g, fitted.data());
    CHECK(fitted == tunic.pos);
    for (int j = 0; j < JOINTS; ++j) CHECK(cc_garment_joint(g, j) == j);
    // Packed (CC ABI 2 / rig ABI 3): a range per vertex, unorm16 weights summing to exactly 65535.
    const int32_t records = cc_garment_pack_size(g);
    CHECK(records >= static_cast<int32_t>(fitted.size() / 3));
    std::vector<uint32_t> ranges(fitted.size() / 3 * 2);
    std::vector<uint16_t> infl(static_cast<size_t>(records) * 2);
    CHECK(cc_garment_pack(g, records - 1, ranges.data(), infl.data()) == CC_ERR_ARG);
    CHECK(cc_garment_pack(g, records, ranges.data(), infl.data()) == CC_OK);
    int32_t widest = 0;
    for (size_t v = 0; v < ranges.size(); v += 2) {
        uint32_t qs = 0;
        for (uint32_t k = ranges[v]; k < ranges[v] + ranges[v + 1]; ++k) qs += infl[k * 2 + 1];
        CHECK(qs == 65535 && ranges[v + 1] >= 1);
        widest = std::max(widest, static_cast<int32_t>(ranges[v + 1]));
    }
    std::printf("[appearance] tunic packed: %d records, up to %d influences per vertex\n", records, widest);

    const Result r = follow(rig, body, tunic, fitted, cc_garment_skin(g), "A");
    std::printf("[appearance] A: worst lift %.4f, widest body stretch %.4f, %d/%d frames over\n", r.worst_lift,
                r.worst_body_gap, r.failing_frames, r.frames);
    CHECK(r.failing_frames == 0);
    check_printed(r, kLiftA, kGapA);

    // The same garment by roles, from an identical but separate character: the fit is the identity.
    cr_rig* twin = make_rig(a);
    cc_char* twin_chr = make_char(twin, "test:body_a2");
    cc_char_pose_set(twin_chr, "upper_arm_l", out_l);
    cc_char_pose_set(twin_chr, "upper_arm_r", out_r);
    cc_char_pose_set(twin_chr, "spine", bend);
    const char* roles[JOINTS];
    std::vector<std::string> role_names(JOINTS);
    for (int j = 0; j < JOINTS; ++j) {
        role_names[static_cast<size_t>(j)] = std::string("role:") + kNames[j];
        roles[j] = role_names[static_cast<size_t>(j)].c_str();
    }
    cc_garment_desc dt = d;
    dt.source = twin_chr;
    dt.source_rig = twin;
    dt.joint_refs = roles;
    cc_garment* gt = cc_garment_create(chr, rig, &dt, &unresolved);
    CHECK(gt != nullptr && unresolved == 0);
    std::vector<float> same(fitted.size());
    cc_garment_positions(gt, same.data());
    double worst = 0;
    for (size_t i = 0; i < same.size(); ++i) worst = std::max(worst, std::fabs(double(same[i]) - tunic.pos[i]));
    std::printf("[appearance] fit onto an identical body: worst move %.2e\n", worst);
    CHECK(worst < 1e-5);

    // Fitted onto another body (longer legs, shorter arms, longer torso, broader): it sits on that body's rest
    // pose about as far out as on its own, and follows it through every clip.
    const Build bb{1.15f, 0.9f, 1.1f, 1.2f};
    cr_rig* rig_b = make_rig(bb);
    cc_char* chr_b = make_char(rig_b, "test:body_b");
    Mesh body_b = make_body(rig_b, bb);
    cc_garment_desc db = d;
    db.source = chr;
    db.source_rig = rig;
    cc_garment* gb = cc_garment_create(chr_b, rig_b, &db, &unresolved);
    CHECK(gb != nullptr && unresolved == 0);
    std::vector<float> on_b(fitted.size());
    cc_garment_positions(gb, on_b.data());
    const std::vector<double> rest_b = clearance(on_b, tunic, body_b.pos, body_b);
    double lo = 1e9, hi = 0, mean = 0;
    for (double x : rest_b) {
        lo = std::min(lo, x);
        hi = std::max(hi, x);
        mean += x;
    }
    mean /= static_cast<double>(rest_b.size());
    std::printf("[appearance] fitted onto B: rest clearance %.4f..%.4f (mean %.4f; made with %.4f)\n", lo, hi, mean, clear);
    CHECK(lo > 0.0 && mean > 0.5 * clear && mean < 3.0 * clear);
    // Pre-#170 printed values: 0.0006..0.0211, mean 0.0146.
    CHECK(std::fabs(lo - 0.0006) <= kPrinted && std::fabs(hi - 0.0211) <= kPrinted && std::fabs(mean - 0.0146) <= kPrinted);
    const Result rb = follow(rig_b, body_b, tunic, on_b, cc_garment_skin(gb), "B");
    std::printf("[appearance] B: worst lift %.4f, widest body stretch %.4f, %d/%d frames over\n", rb.worst_lift,
                rb.worst_body_gap, rb.failing_frames, rb.frames);
    CHECK(rb.failing_frames == 0);
    check_printed(rb, kLiftB, kGapB);

    // A joint the target lacks falls back to its nearest mapped ancestor and is counted.
    const char* odd[JOINTS];
    for (int j = 0; j < JOINTS; ++j) odd[j] = kNames[j];
    odd[HAND_L] = "role:pinky_l";
    cc_garment_desc dm = d;
    dm.joint_refs = odd;
    cc_garment* gm = cc_garment_create(chr, rig, &dm, &unresolved);
    CHECK(gm != nullptr && unresolved == 1 && cc_garment_joint(gm, HAND_L) == -1);
    cc_garment_desc bad = d;
    bad.vertex_count = 3;
    CHECK(cc_garment_create(chr, rig, &bad, nullptr) == nullptr);

    // A "wide" tunic weighted to EVERY joint (no prune: JOINTS influences per vertex; ABI 2 kept 4): fitted onto the
    // identical twin by roles it still does not move, and onto B it still sits outside the body; packing keeps all.
    cr_skin* wide = cr_skin_create(static_cast<int32_t>(tunic.pos.size() / 3));
    std::vector<int32_t> every(JOINTS);
    for (int j = 0; j < JOINTS; ++j) every[static_cast<size_t>(j)] = j;
    CHECK(cr_skin_auto_weights(wide, rig, tunic.pos.data(), 0, cr_skin_vertex_count(wide), every.data(), JOINTS, 1.f, 0.f, 0)
          == CR_OK);
    CHECK(cr_skin_max_influences(wide) == JOINTS);
    cc_garment_desc dw = dt;
    dw.skin = wide;
    cc_garment* gw = cc_garment_create(chr, rig, &dw, &unresolved);
    CHECK(gw != nullptr && unresolved == 0);
    std::vector<float> wide_same(fitted.size());
    cc_garment_positions(gw, wide_same.data());
    double wide_worst = 0;
    for (size_t i = 0; i < wide_same.size(); ++i) wide_worst = std::max(wide_worst, std::fabs(double(wide_same[i]) - tunic.pos[i]));
    CHECK(wide_worst < 1e-5);
    CHECK(cr_skin_max_influences(cc_garment_skin(gw)) == JOINTS);
    CHECK(cc_garment_pack_size(gw) == static_cast<int32_t>(tunic.pos.size() / 3) * JOINTS);
    cc_garment_desc dwb = db;
    dwb.skin = wide;
    cc_garment* gwb = cc_garment_create(chr_b, rig_b, &dwb, &unresolved);
    CHECK(gwb != nullptr && unresolved == 0);
    std::vector<float> wide_b(fitted.size());
    cc_garment_positions(gwb, wide_b.data());
    bool finite = true;
    for (float x : wide_b) finite &= std::isfinite(x);
    CHECK(finite);
    std::printf("[appearance] wide tunic (%d influences per vertex): identical body worst move %.2e\n", JOINTS, wide_worst);
    cc_garment_destroy(gwb);
    cc_garment_destroy(gw);
    cr_skin_destroy(wide);

    for (cc_garment* x : {g, gt, gb, gm}) cc_garment_destroy(x);
    for (cr_skin* s : {body.skin, tunic.skin, body_b.skin}) cr_skin_destroy(s);
    for (cc_char* c : {chr, twin_chr, chr_b}) cc_char_destroy(c);
    for (cr_rig* x : {rig, twin, rig_b}) cr_rig_destroy(x);
}

// ---- resolution --------------------------------------------------------------------------------------------

const char* kSection = R"({
  "tints": [{"name": "skin", "default": "#c08060"}, {"name": "cloth", "default": "#304050"}, {"name": "hair"}],
  "materials": [{"id": 1, "tint": "skin"}, {"id": 4, "mask": "masks/tunic.png", "channels": {"r": "cloth", "b": "hair"}}],
  "groups": [{"name": "headwear", "parts": ["head"]}, {"name": "sleeves", "parts": ["upper_arm_l", "upper_arm_r"]},
             {"name": "cape", "parts": ["spine", "nope"], "visible": false}],
  "bodies": [{"name": "standard", "model": "model.omo"}, {"name": "broad", "model": "bodies/broad.omo"}],
  "equipment": [{"name": "helmet", "socket": "head", "hides": ["headwear"]},
                {"name": "torso", "kind": "skinned", "hides": ["sleeves"]}],
  "futureKey": {"kept": true}
})";

void test_resolution() {
    cr_rig* rig = make_rig(Build{});
    cc_char* c = make_char(rig, "test:body");
    const float at[3] = {0, 0.1f, -0.05f};
    cc_char_socket_set(c, "head", "head", at, nullptr, nullptr);
    CHECK(cc_char_set_string(c, CC_FIELD_APPEARANCE, kSection) == CC_OK);
    const uint8_t png[4] = {1, 2, 3, 4};
    cc_char_entry_set(c, "masks/tunic.png", png, 4);
    cc_char_entry_set(c, "bodies/broad.omo", png, 4);
    cc_char_model_set(c, png, 4, "model.omo");
    CHECK(cc_char_entry_find(c, "masks/tunic.png") >= 0 && cc_char_entry_set(c, "manifest.json", png, 4) == CC_ERR_ARG);

    int32_t rc = 0, unresolved = 0;
    cc_appearance* a = cc_appearance_create(c, rig, &rc, &unresolved);
    CHECK(a != nullptr && rc == CC_OK && unresolved == 1);  // the cape lists a part the rig lacks
    CHECK(cc_appearance_slot_count(a) == 9);
    const char* name = nullptr;
    int32_t kind = -1, type = -1;
    cc_appearance_slot(a, 6, &name, &kind, &type);
    CHECK(std::string(name) == "body" && kind == CC_SLOT_BODY && type == CC_LOOK_STRING);
    cc_look* defaults = cc_look_create();
    cc_appearance_defaults(a, defaults);
    CHECK(cc_look_count(defaults) == 9 && std::string(cc_look_character(defaults)) == "test:body");

    const int32_t words = 1;
    uint64_t bits = 0;
    CHECK(cc_appearance_visible(a, nullptr, &bits, words) == CC_OK);
    CHECK((bits >> SPINE & 1) == 0 && (bits >> HEAD & 1) == 1 && (bits >> UPPER_ARM_L & 1) == 1);  // cape hidden by default
    int32_t id = 0;
    const char* mask = nullptr;
    float tints[12];
    CHECK(cc_appearance_material_count(a) == 2);
    cc_appearance_material(a, 0, &id, &mask, tints);
    CHECK(id == 1 && std::string(mask).empty() && near(tints[0], 0xc0 / 255.0, 1e-6) && tints[3] == 1.f);
    cc_appearance_material(a, 1, &id, &mask, tints);
    CHECK(id == 4 && std::string(mask) == "masks/tunic.png" && near(tints[2], 0x50 / 255.0, 1e-6) && tints[3] == 1.f &&
          tints[6] == 1.f && tints[7] == 1.f);  // g has no tint; b = hair (white default)

    // Items: a helmet on the head socket, a skinned shirt.
    cc_item_desc helmet{};
    helmet.id = "iron_helmet";
    helmet.slot = "helmet";
    helmet.kind = CC_ITEM_RIGID;
    helmet.pos[1] = 0.02f;
    helmet.scale[0] = helmet.scale[1] = helmet.scale[2] = 1.f;
    CHECK(cc_appearance_add_item(a, &helmet) == 0);
    cc_item_desc shirt{};
    shirt.id = "wool_shirt";
    shirt.slot = "torso";
    shirt.kind = CC_ITEM_SKINNED;
    CHECK(cc_appearance_add_item(a, &shirt) == 1);
    cc_item_desc wrong = shirt;
    wrong.slot = "boots";
    CHECK(cc_appearance_add_item(a, &wrong) == CC_ERR_RANGE);

    cc_look* look = cc_look_create();
    const float pale[3] = {1.f, 0.9f, 0.8f};
    cc_look_set_color(look, "skin", pale);
    cc_look_set_bool(look, "cape", 1);
    cc_look_set_string(look, "body", "broad");
    cc_look_set_string(look, "helmet", "iron_helmet");
    cc_look_set_string(look, "torso", "wool_shirt");
    cc_look_set_string(look, "pet", "dog");     // unknown slot: ignored, kept in the record
    cc_look_set_bool(look, "headwear", 1);
    CHECK(cc_appearance_resolve(a, look) == 0);
    CHECK(cc_look_find(look, "pet") >= 0);
    const char* body = nullptr;
    const char* model = nullptr;
    cc_appearance_body(a, &body, &model);
    CHECK(std::string(body) == "broad" && std::string(model) == "bodies/broad.omo");
    cc_appearance_visible(a, nullptr, &bits, words);
    CHECK((bits >> SPINE & 1) == 1);         // cape shown
    CHECK((bits >> HEAD & 1) == 0);          // the helmet hides the headwear group
    CHECK((bits >> UPPER_ARM_L & 1) == 0);   // the shirt hides the sleeves
    CHECK((bits >> LOWER_ARM_L & 1) == 1);
    cc_appearance_material(a, 0, &id, &mask, tints);
    CHECK(tints[0] == 1.f && near(tints[1], 230.0 / 255.0, 1e-6));
    CHECK(cc_appearance_equipped_count(a) == 2);
    const char* slot = nullptr;
    int32_t item = -1, joint = -2;
    float m[16];
    cc_appearance_equipped(a, 0, &slot, &item, &kind, &joint, m);
    CHECK(std::string(slot) == "helmet" && item == 0 && kind == CC_ITEM_RIGID && joint == HEAD);
    CHECK(near(m[12], 0.0, 1e-6) && near(m[13], 0.12, 1e-6) && near(m[14], -0.05, 1e-6));  // socket x item offset
    cc_appearance_equipped(a, 1, &slot, &item, &kind, &joint, m);
    CHECK(std::string(slot) == "torso" && kind == CC_ITEM_SKINNED && joint == -1);

    // A view mask (first person: everything but the arms hidden) narrows visibility further.
    cr_mask* view = cr_mask_create(rig, 0.f);
    cr_mask_set(view, SHOULDER_L, 1.f, CR_MASK_BRANCH, 0);
    cc_appearance_visible(a, view, &bits, words);
    CHECK((bits >> HIPS & 1) == 0 && (bits >> LOWER_ARM_L & 1) == 1 && (bits >> UPPER_ARM_L & 1) == 0);

    // Fallbacks: wrong types, an unknown body, an item of another slot -> defaults, counted.
    cc_look* bad = cc_look_create();
    cc_look_set_bool(bad, "skin", 1);
    cc_look_set_string(bad, "body", "giant");
    cc_look_set_string(bad, "helmet", "wool_shirt");
    CHECK(cc_appearance_resolve(a, bad) == 3);
    cc_appearance_body(a, &body, &model);
    CHECK(std::string(body) == "standard" && cc_appearance_equipped_count(a) == 0);

    // Authored-hidden parts start hidden.
    cr_joint_desc hidden[2] = {bone(-1, 0, 0, 0, 0, 1, 0), bone(0, 0, 0, 0, 0, 1, 0)};
    hidden[1].flags |= CR_JOINT_HIDDEN;
    const char* hn[2] = {"a", "b"};
    cr_rig* hr = cr_rig_create(hidden, 2, hn, hn);
    cc_char* hc = cc_char_create();
    cc_char_set_skeleton(hc, hr);
    cc_appearance* ha = cc_appearance_create(hc, hr, &rc, nullptr);
    CHECK(ha != nullptr);
    cc_appearance_visible(ha, nullptr, &bits, 1);
    CHECK(bits == 1u);

    // Refused sections.
    auto refused = [&](const char* json) {
        cc_char_set_string(c, CC_FIELD_APPEARANCE, json);
        cc_appearance* x = cc_appearance_create(c, rig, &rc, nullptr);
        const bool ok = x == nullptr && rc == CC_ERR_FORMAT;
        cc_appearance_destroy(x);
        return ok;
    };
    CHECK(refused(R"({"tints": [{"name": "body"}]})"));
    CHECK(refused(R"({"tints": [{"name": "a"}], "groups": [{"name": "a", "parts": []}]})"));
    CHECK(refused(R"({"materials": [{"id": 1, "tint": "nope"}]})"));
    CHECK(refused(R"({"tints": [{"name": "a", "default": "red"}]})"));
    CHECK(refused(R"({"equipment": [{"name": "e", "hides": ["nope"]}]})"));
    CHECK(refused(R"({"equipment": [{"name": "e", "kind": "floating"}]})"));
    CHECK(refused(R"({"tints": [{"name": "a"}], "materials": [{"id": 1, "mask": "m.png", "channels": {}}]})"));

    for (cc_look* x : {defaults, look, bad}) cc_look_destroy(x);
    cr_mask_destroy(view);
    cc_appearance_destroy(a);
    cc_appearance_destroy(ha);
    cc_char_destroy(c);
    cc_char_destroy(hc);
    cr_rig_destroy(rig);
    cr_rig_destroy(hr);
}

}  // namespace

int main() {
    test_record();
    test_resolution();
    test_garment_follows();
    if (failures != 0) {
        std::fprintf(stderr, "appearance: %d failure(s)\n", failures);
        return 1;
    }
    std::printf("appearance: OK\n");
    return 0;
}
