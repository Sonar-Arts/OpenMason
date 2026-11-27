// Cenda sockets and mounts (Stonebreak #459) conformance: socket frames = pre · K(joint) · T·R·S (root sockets =
// root · T·R·S) against reference math written here, the name rules (case-insensitive, unique, source precedence
// HOST > CHARACTER > OMO, a lower-ranked add changes nothing), OMO references (id, then exact name, missing joint
// refused), .omchar sockets (cc_char_sockets_into, unresolved counted), mounts (item = socket · offset ·
// attach^-1: the item's attach point lands exactly on the socket; points ride the item), unresolved mounts (zero
// matrices, valid 0), remove / replace / clear, and argument checks. Plain asserts.

#include "cenda/character.h"
#include "cenda/rig.h"
#include "cenda/socket.h"

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <numbers>
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

// Column-major 4x4 in double.
struct M {
    double m[16];
};

M identity() {
    M r{};
    r.m[0] = r.m[5] = r.m[10] = r.m[15] = 1.0;
    return r;
}

M mul(const M& a, const M& b) {
    M r{};
    for (int c = 0; c < 4; ++c) {
        for (int row = 0; row < 4; ++row) {
            double s = 0.0;
            for (int k = 0; k < 4; ++k) s += a.m[k * 4 + row] * b.m[c * 4 + k];
            r.m[c * 4 + row] = s;
        }
    }
    return r;
}

M of(const float* f) {
    M r{};
    for (int i = 0; i < 16; ++i) r.m[i] = f[i];
    return r;
}

void to(const M& a, float* f) {
    for (int i = 0; i < 16; ++i) f[i] = static_cast<float>(a.m[i]);
}

M translate(double x, double y, double z) {
    M r = identity();
    r.m[12] = x;
    r.m[13] = y;
    r.m[14] = z;
    return r;
}

M scale(double x, double y, double z) {
    M r = identity();
    r.m[0] = x;
    r.m[5] = y;
    r.m[10] = z;
    return r;
}

M rot_x(double a) {
    M r = identity();
    r.m[5] = std::cos(a);
    r.m[6] = std::sin(a);
    r.m[9] = -std::sin(a);
    r.m[10] = std::cos(a);
    return r;
}

M rot_y(double a) {
    M r = identity();
    r.m[0] = std::cos(a);
    r.m[2] = -std::sin(a);
    r.m[8] = std::sin(a);
    r.m[10] = std::cos(a);
    return r;
}

M rot_z(double a) {
    M r = identity();
    r.m[0] = std::cos(a);
    r.m[1] = std::sin(a);
    r.m[4] = -std::sin(a);
    r.m[5] = std::cos(a);
    return r;
}

constexpr double kDeg = std::numbers::pi / 180.0;

// JOML translate(pos).rotateXYZ(rot).scale(s).
M trs(const float* p, const float* r, const float* s) {
    return mul(mul(mul(mul(translate(p[0], p[1], p[2]), rot_x(r[0] * kDeg)), rot_y(r[1] * kDeg)), rot_z(r[2] * kDeg)),
               scale(s[0], s[1], s[2]));
}

bool near_m(const float* a, const M& b, double eps) {
    for (int i = 0; i < 16; ++i) {
        if (std::fabs(static_cast<double>(a[i]) - b.m[i]) > eps) return false;
    }
    return true;
}

bool all_zero(const float* a) {
    for (int i = 0; i < 16; ++i) {
        if (a[i] != 0.f) return false;
    }
    return true;
}

// ---- a small rig: root part, a bone arm under it, a hand bone --------------------------------------------

enum { BODY, ARM, HAND, JOINTS };
const char* kIds[JOINTS] = {"id_body", "id_arm", "id_hand"};
const char* kNames[JOINTS] = {"body", "arm", "hand"};

cr_joint_desc desc(int parent, int kind, float px, float py, float pz, float ex, float ey, float ez) {
    cr_joint_desc d{};
    d.parent = parent;
    d.kind = kind;
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

cr_rig* make_rig() {
    cr_joint_desc j[JOINTS];
    j[BODY] = desc(-1, CR_JOINT_PART, 0, 1, 0, 0, 0, 0);
    j[ARM] = desc(BODY, CR_JOINT_BONE, 0.3f, 0.4f, 0, 0, -0.5f, 0);
    j[HAND] = desc(ARM, CR_JOINT_BONE, 0, 0, 0, 0, -0.1f, 0);
    return cr_rig_create(j, JOINTS, kIds, kNames);
}

// A posed palette with something non-trivial in it: the arm turned, the body moved, a base.
struct Pose {
    std::vector<float> skin = std::vector<float>(JOINTS * 16);
};

const float kBase[16] = {0, 0, -1, 0, 0, 1, 0, 0, 1, 0, 0, 0, 5, 2, 3, 1};  // Ry(90) then T(5, 2, 3)

Pose pose(const cr_rig* rig, const float* base) {
    std::vector<float> locals(JOINTS * 9, 0.f);
    for (size_t i = 0; i < JOINTS; ++i) locals[i * 9 + 6] = locals[i * 9 + 7] = locals[i * 9 + 8] = 1.f;
    locals[BODY * 9 + 1] = 1.1f;   // body pos y
    locals[ARM * 9 + 0] = 0.3f;    // arm pos
    locals[ARM * 9 + 1] = 0.4f;
    locals[ARM * 9 + 3] = 35.f;    // arm rot x
    locals[ARM * 9 + 5] = -20.f;   // arm rot z
    locals[HAND * 9 + 4] = 15.f;   // hand rot y
    Pose p;
    CHECK(cr_rig_pose_locals(rig, locals.data(), nullptr, 0, base, p.skin.data(), nullptr, nullptr) == CR_OK);
    return p;
}

void test_frames_and_names() {
    cr_rig* rig = make_rig();
    cr_sockets* set = cr_sockets_create(rig);
    const float pos[3] = {0.05f, -0.1f, 0.02f}, rot[3] = {10.f, -30.f, 45.f}, scl[3] = {1.f, 2.f, 0.5f};
    const int32_t grip = cr_sockets_add(set, "Grip_R", HAND, pos, rot, scl, CR_SOCKET_OMO);
    const int32_t top = cr_sockets_add(set, "top", CR_SOCKET_ROOT, pos, nullptr, nullptr, CR_SOCKET_OMO);
    CHECK(grip == 0 && top == 1 && cr_sockets_count(set) == 2);
    CHECK(cr_sockets_find(set, "grip_r") == 0 && cr_sockets_find(set, "GRIP_R") == 0 && cr_sockets_find(set, "x") == -1);

    // Model-space palette + base as pre: pre · K · TRS.
    Pose model = pose(rig, nullptr);
    float out[16];
    CHECK(cr_sockets_frame(set, grip, model.skin.data(), JOINTS, kBase, nullptr, out) == CR_OK);
    const float one[3] = {1.f, 1.f, 1.f}, zero[3] = {0.f, 0.f, 0.f};
    const M want = mul(mul(of(kBase), of(model.skin.data() + HAND * 16)), trs(pos, rot, scl));
    CHECK(near_m(out, want, 1e-6));
    // The same palette posed WITH the base and no pre gives the same frame.
    Pose based = pose(rig, kBase);
    float out2[16];
    CHECK(cr_sockets_frame(set, grip, based.skin.data(), JOINTS, nullptr, kBase, out2) == CR_OK);
    CHECK(near_m(out2, want, 1e-5));
    // Root socket: root · TRS (root defaults to pre).
    CHECK(cr_sockets_frame(set, top, model.skin.data(), JOINTS, kBase, nullptr, out) == CR_OK);
    CHECK(near_m(out, mul(of(kBase), trs(pos, zero, one)), 1e-6));
    // A palette too short for the joint.
    CHECK(cr_sockets_frame(set, grip, model.skin.data(), HAND, kBase, nullptr, out) == CR_ERR_RANGE);

    // Precedence: CHARACTER replaces OMO (same index), a later OMO does not replace CHARACTER, HOST beats both.
    const float p2[3] = {0.f, 0.5f, 0.f};
    CHECK(cr_sockets_add(set, "GRIP_r", ARM, p2, nullptr, nullptr, CR_SOCKET_CHARACTER) == grip);
    int32_t joint = -2, source = -2;
    const char* name = nullptr;
    float local[16];
    CHECK(cr_sockets_get(set, grip, &name, &joint, &source, local) == CR_OK);
    CHECK(joint == ARM && source == CR_SOCKET_CHARACTER && std::strcmp(name, "GRIP_r") == 0);
    CHECK(near_m(local, translate(0, 0.5, 0), 0.0));
    CHECK(cr_sockets_add(set, "grip_r", HAND, nullptr, nullptr, nullptr, CR_SOCKET_OMO) == grip);
    CHECK(cr_sockets_get(set, grip, nullptr, &joint, &source, nullptr) == CR_OK && joint == ARM);
    CHECK(cr_sockets_add(set, "grip_r", BODY, nullptr, nullptr, nullptr, CR_SOCKET_HOST) == grip);
    CHECK(cr_sockets_get(set, grip, nullptr, &joint, &source, nullptr) == CR_OK && joint == BODY &&
          source == CR_SOCKET_HOST);

    // Argument checks.
    CHECK(cr_sockets_add(set, "", HAND, nullptr, nullptr, nullptr, CR_SOCKET_OMO) == CR_ERR_ARG);
    CHECK(cr_sockets_add(set, "x", JOINTS, nullptr, nullptr, nullptr, CR_SOCKET_OMO) == CR_ERR_ARG);
    CHECK(cr_sockets_add(set, "x", HAND, nullptr, nullptr, nullptr, 7) == CR_ERR_ARG);
    const float nan3[3] = {std::nanf(""), 0.f, 0.f};
    CHECK(cr_sockets_add(set, "x", HAND, nan3, nullptr, nullptr, CR_SOCKET_OMO) == CR_ERR_ARG);
    CHECK(cr_sockets_count(set) == 2);

    cr_sockets_destroy(set);
    cr_rig_destroy(rig);
}

void test_references() {
    cr_rig* rig = make_rig();
    cr_sockets* set = cr_sockets_create(rig);
    // By id; by exact name when the id misses; a name differing in case does not match (the OMO rule).
    CHECK(cr_sockets_add_ref(set, "a", "id_arm", "nope", nullptr, nullptr, nullptr, CR_SOCKET_OMO) == 0);
    CHECK(cr_sockets_add_ref(set, "b", "missing", "hand", nullptr, nullptr, nullptr, CR_SOCKET_OMO) == 1);
    CHECK(cr_sockets_add_ref(set, "c", nullptr, "HAND", nullptr, nullptr, nullptr, CR_SOCKET_OMO) == CR_ERR_RANGE);
    CHECK(cr_sockets_add_ref(set, "d", "", "", nullptr, nullptr, nullptr, CR_SOCKET_OMO) == 2);
    int32_t joint = -2;
    CHECK(cr_sockets_get(set, 0, nullptr, &joint, nullptr, nullptr) == CR_OK && joint == ARM);
    CHECK(cr_sockets_get(set, 1, nullptr, &joint, nullptr, nullptr) == CR_OK && joint == HAND);
    CHECK(cr_sockets_get(set, 2, nullptr, &joint, nullptr, nullptr) == CR_OK && joint == CR_SOCKET_ROOT);
    CHECK(cr_sockets_count(set) == 3);
    cr_sockets_destroy(set);

    // A rig-less set: root sockets only.
    cr_sockets* items = cr_sockets_create(nullptr);
    CHECK(cr_sockets_add(items, "tip", CR_SOCKET_ROOT, nullptr, nullptr, nullptr, CR_SOCKET_HOST) == 0);
    CHECK(cr_sockets_add(items, "x", 0, nullptr, nullptr, nullptr, CR_SOCKET_HOST) == CR_ERR_ARG);
    CHECK(cr_sockets_add_ref(items, "y", "id_arm", nullptr, nullptr, nullptr, nullptr, CR_SOCKET_OMO) == CR_ERR_RANGE);
    cr_sockets_destroy(items);
    cr_rig_destroy(rig);
}

void test_character_sockets() {
    cr_rig* rig = make_rig();
    cc_char* c = cc_char_create();
    CHECK(cc_char_set_skeleton(c, rig) == CC_OK);
    const float pos[3] = {0.f, -0.05f, 0.f}, rot[3] = {0.f, 90.f, 0.f};
    CHECK(cc_char_socket_set(c, "grip_r", "id_hand", pos, rot, nullptr) == CC_OK);
    CHECK(cc_char_socket_set(c, "back", "id_body", nullptr, nullptr, nullptr) == CC_OK);
    CHECK(cc_char_socket_set(c, "ghost", "id_gone", nullptr, nullptr, nullptr) == CC_OK);

    cr_sockets* set = cr_sockets_create(rig);
    // An OMO point of the same name is replaced by the character's.
    CHECK(cr_sockets_add(set, "Grip_R", ARM, nullptr, nullptr, nullptr, CR_SOCKET_OMO) == 0);
    int32_t unresolved = -1;
    CHECK(cc_char_sockets_into(c, set, &unresolved) == 2 && unresolved == 1);
    CHECK(cr_sockets_count(set) == 2);
    int32_t joint = -2, source = -2;
    float local[16];
    CHECK(cr_sockets_get(set, cr_sockets_find(set, "grip_r"), nullptr, &joint, &source, local) == CR_OK);
    CHECK(joint == HAND && source == CR_SOCKET_CHARACTER);
    const float one[3] = {1.f, 1.f, 1.f};
    CHECK(near_m(local, trs(pos, rot, one), 1e-7));
    CHECK(cc_char_sockets_into(c, nullptr, nullptr) == CR_ERR_ARG);
    cr_sockets* rigless = cr_sockets_create(nullptr);
    CHECK(cc_char_sockets_into(c, rigless, nullptr) == CR_ERR_ARG);
    cr_sockets_destroy(rigless);
    cr_sockets_destroy(set);
    cc_char_destroy(c);
    cr_rig_destroy(rig);
}

void test_mounts() {
    cr_rig* rig = make_rig();
    cr_sockets* set = cr_sockets_create(rig);
    const float spos[3] = {0.f, -0.08f, 0.f}, srot[3] = {0.f, 0.f, 90.f};
    const int32_t grip = cr_sockets_add(set, "grip_r", HAND, spos, srot, nullptr, CR_SOCKET_CHARACTER);
    const int32_t top = cr_sockets_add(set, "Hatzone", CR_SOCKET_ROOT, spos, nullptr, nullptr, CR_SOCKET_OMO);

    // The item's own attach point (where it is held) and a point at its tip.
    const float apos[3] = {0.f, -0.3f, 0.f}, arot[3] = {0.f, 0.f, -45.f}, one[3] = {1.f, 1.f, 1.f};
    float attach[16], offset[16], tip[16];
    to(trs(apos, arot, one), attach);
    to(rot_y(30 * kDeg), offset);
    to(translate(0, 0.45, 0), tip);

    cr_mounts* mounts = cr_mounts_create();
    CHECK(cr_mounts_set(mounts, "held", grip, offset, attach) == 0);
    CHECK(cr_mounts_set_point(mounts, 0, "tip", tip) == 0);
    CHECK(cr_mounts_set_point(mounts, 0, "flame", nullptr) == 1);
    CHECK(cr_mounts_set(mounts, "hat", top, nullptr, nullptr) == 1);
    CHECK(cr_mounts_set(mounts, "gone", -1, nullptr, nullptr) == 2);
    CHECK(cr_mounts_set_point(mounts, 2, "p", nullptr) == 0);
    CHECK(cr_mounts_count(mounts) == 3 && cr_mounts_total_points(mounts) == 3);
    CHECK(cr_mounts_point_base(mounts, 0) == 0 && cr_mounts_point_base(mounts, 1) == 2 &&
          cr_mounts_point_base(mounts, 2) == 2);
    CHECK(cr_mounts_find(mounts, "HELD") == 0 && cr_mounts_find_point(mounts, 0, "TIP") == 0 &&
          cr_mounts_find_point(mounts, 0, "x") == -1);

    Pose model = pose(rig, nullptr);
    std::vector<float> frames(3 * 16), points(3 * 16, 7.f);
    uint8_t valid[3] = {9, 9, 9};
    CHECK(cr_mounts_resolve(mounts, set, model.skin.data(), JOINTS, kBase, nullptr, frames.data(), points.data(),
                            valid) == 2);
    CHECK(valid[0] == 1 && valid[1] == 1 && valid[2] == 0);

    float socket[16];
    CHECK(cr_sockets_frame(set, grip, model.skin.data(), JOINTS, kBase, nullptr, socket) == CR_OK);
    // The item's attach point, carried by the item frame, lands on socket · offset.
    const M item = of(frames.data());
    const M attach_world = mul(item, of(attach));
    CHECK(near_m(socket, mul(attach_world, rot_y(-30 * kDeg)), 1e-5));
    CHECK(near_m(points.data(), mul(item, of(tip)), 1e-5));
    CHECK(near_m(points.data() + 16, item, 1e-6));
    CHECK(cr_sockets_frame(set, top, model.skin.data(), JOINTS, kBase, nullptr, socket) == CR_OK);
    CHECK(near_m(frames.data() + 16, of(socket), 0.0));
    CHECK(all_zero(frames.data() + 32) && all_zero(points.data() + 32));

    // Replace keeps the index and drops the points; remove shifts later mounts; clear empties.
    CHECK(cr_mounts_set(mounts, "HELD", grip, nullptr, nullptr) == 0 && cr_mounts_point_count(mounts, 0) == 0);
    CHECK(cr_mounts_remove(mounts, "hat") == CR_OK && cr_mounts_find(mounts, "gone") == 1);
    CHECK(cr_mounts_remove(mounts, "hat") == CR_ERR_RANGE);
    const char* name = nullptr;
    int32_t sock = 5;
    CHECK(cr_mounts_get(mounts, 1, &name, &sock) == CR_OK && std::strcmp(name, "gone") == 0 && sock == -1);
    CHECK(cr_mounts_clear(mounts) == CR_OK && cr_mounts_count(mounts) == 0);
    CHECK(cr_mounts_resolve(mounts, set, model.skin.data(), JOINTS, nullptr, nullptr, nullptr, nullptr, nullptr) == 0);

    // Argument checks.
    CHECK(cr_mounts_set(mounts, "", 0, nullptr, nullptr) == CR_ERR_ARG);
    float bad[16];
    to(identity(), bad);
    bad[3] = std::nanf("");
    CHECK(cr_mounts_set(mounts, "x", 0, bad, nullptr) == CR_ERR_ARG);
    CHECK(cr_mounts_set_point(mounts, 0, "p", nullptr) == CR_ERR_ARG);
    CHECK(cr_mounts_set(mounts, "x", 0, nullptr, nullptr) == 0);
    CHECK(cr_mounts_resolve(mounts, set, model.skin.data(), JOINTS, nullptr, nullptr, nullptr, nullptr, nullptr) ==
          CR_ERR_ARG);

    cr_mounts_destroy(mounts);
    cr_sockets_destroy(set);
    cr_rig_destroy(rig);
}

}  // namespace

int main() {
    test_frames_and_names();
    test_references();
    test_character_sockets();
    test_mounts();
    if (failures != 0) {
        std::fprintf(stderr, "sockets: %d failure(s)\n", failures);
        return 1;
    }
    std::printf("sockets: ok\n");
    return 0;
}
