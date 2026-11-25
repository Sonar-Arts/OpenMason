// Cenda first-person view (OpenMason #161) conformance: palette hiding by zero scale (branches collapse to their
// hide root, visible joints bit-identical, partial weights), role view masks (shoulder / upper-arm and neck / head
// fallbacks, missing roles), the eyes point (socket > eye roles > head), the first-person projection (own FOV,
// same aspect and jitter, near plane on the world near plane, slab end, ordered and perspective-correct depth,
// in front of a wall 0.1 ahead, the pull leaves the screen image alone), the head-bob stabilizer (exact below
// the threshold, damped above, catches up, no lag from the base, snaps) and the animation graph's first-person
// view layers (body pose without them bit-identical, first-person pose = the layer applied, no influence taken
// from the base, format rules). Plain asserts.

#include "cenda/anim_graph.h"
#include "cenda/character.h"
#include "cenda/rig.h"

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
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

// ---- a small humanoid of bones (ids = names) -----------------------------------------------------------

enum {
    HIPS, SPINE, HEAD, EYE, SHOULDER_L, UPPER_ARM_L, HAND_L, SHOULDER_R, UPPER_ARM_R, HAND_R, LEG_L, LEG_R, PROP_L,
    JOINTS
};
const char* kNames[JOINTS] = {"hips",       "spine",       "head",   "eye",   "shoulder_l", "upper_arm_l", "hand_l",
                              "shoulder_r", "upper_arm_r", "hand_r", "leg_l", "leg_r",      "prop_l"};

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

// Rest heads: hips (0,1,0), spine (0,1.2,0), head (0,1.5,0), eye (0,1.6,-0.1), shoulders at the spine's tail (0,1.5,0).
cr_rig* make_rig() {
    cr_joint_desc j[JOINTS];
    j[HIPS] = bone(-1, 0, 1, 0, 0, 0.2f, 0);
    j[SPINE] = bone(HIPS, 0, 0, 0, 0, 0.3f, 0);
    j[HEAD] = bone(SPINE, 0, 0, 0, 0, 0.25f, 0);
    j[EYE] = bone(HEAD, 0, -0.15f, -0.1f, 0, 0, -0.02f);
    j[SHOULDER_L] = bone(SPINE, 0, -0.05f, 0, 0.15f, 0, 0);
    j[UPPER_ARM_L] = bone(SHOULDER_L, 0, 0, 0, 0, -0.3f, 0);
    j[HAND_L] = bone(UPPER_ARM_L, 0, 0, 0, 0, -0.1f, 0);
    j[SHOULDER_R] = bone(SPINE, 0, -0.05f, 0, -0.15f, 0, 0);
    j[UPPER_ARM_R] = bone(SHOULDER_R, 0, 0, 0, 0, -0.3f, 0);
    j[HAND_R] = bone(UPPER_ARM_R, 0, 0, 0, 0, -0.1f, 0);
    j[LEG_L] = bone(HIPS, 0.1f, -0.2f, 0, 0, -0.8f, 0);
    j[LEG_R] = bone(HIPS, -0.1f, -0.2f, 0, 0, -0.8f, 0);
    j[PROP_L] = bone(HAND_L, 0, 0, 0, 0, 0, -0.4f);
    return cr_rig_create(j, JOINTS, kNames, kNames);
}

cc_char* make_char(const cr_rig* rig, bool shoulders, bool arms, bool head) {
    cc_char* c = cc_char_create();
    CHECK(cc_char_set_skeleton(c, rig) == CC_OK);
    cc_char_role_set(c, "hips", "hips");
    cc_char_role_set(c, "spine", "spine");
    if (head) cc_char_role_set(c, "head", "head");
    if (shoulders) {
        cc_char_role_set(c, "shoulder_l", "shoulder_l");
        cc_char_role_set(c, "shoulder_r", "shoulder_r");
    }
    if (arms) {
        cc_char_role_set(c, "upper_arm_l", "upper_arm_l");
        cc_char_role_set(c, "upper_arm_r", "upper_arm_r");
    }
    cc_char_role_set(c, "upper_leg_l", "leg_l");
    cc_char_role_set(c, "upper_leg_r", "leg_r");
    return c;
}

struct Weights {
    std::vector<float> v = std::vector<float>(JOINTS);
    float operator[](int j) const { return v[static_cast<size_t>(j)]; }
};
Weights weights(const cr_mask* m) {
    Weights w;
    cr_mask_get(m, w.v.data());
    return w;
}

// Column-major helpers.
void apply(const float* m, const double in[3], double out[3]) {
    for (int r = 0; r < 3; ++r) out[r] = m[r] * in[0] + m[4 + r] * in[1] + m[8 + r] * in[2] + m[12 + r];
}
void apply4(const double* m, const double in[4], double out[4]) {
    for (int r = 0; r < 4; ++r) out[r] = m[r] * in[0] + m[4 + r] * in[1] + m[8 + r] * in[2] + m[12 + r] * in[3];
}

const float kBase[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 5, 0, 3, 1};  // T(5, 0, 3)

struct Posed {
    std::vector<float> skin = std::vector<float>(JOINTS * 16);
    std::vector<float> world = std::vector<float>(JOINTS * 16);
};

Posed pose(const cr_rig* rig, const float* locals = nullptr) {
    Posed p;
    CHECK(cr_rig_pose_locals(rig, locals, nullptr, 0, kBase, p.skin.data(), p.world.data(), nullptr) == CR_OK);
    return p;
}

// ---- palette hide + view masks ----------------------------------------------------------------------------

void test_view_masks() {
    cr_rig* rig = make_rig();
    cr_mask* m = cr_mask_create(rig, 0.5f);
    cc_char* full = make_char(rig, true, true, true);
    int32_t missing = -1;

    CHECK(cc_char_view_mask(full, rig, CC_VIEW_FIRST_PERSON, m, &missing) == CC_OK && missing == 0);
    Weights w = weights(m);
    for (int j : {SHOULDER_L, UPPER_ARM_L, HAND_L, PROP_L, SHOULDER_R, UPPER_ARM_R, HAND_R}) CHECK(w[j] == 1.f);
    for (int j : {HIPS, SPINE, HEAD, EYE, LEG_L, LEG_R}) CHECK(w[j] == 0.f);

    CHECK(cc_char_view_mask(full, rig, CC_VIEW_HEADLESS, m, &missing) == CC_OK && missing == 0);
    w = weights(m);
    for (int j = 0; j < JOINTS; ++j) CHECK(w[j] == ((j == HEAD || j == EYE) ? 0.f : 1.f));

    CHECK(cc_char_view_mask(full, rig, CC_VIEW_BODY_AWARENESS, m, &missing) == CC_OK && missing == 0);
    w = weights(m);
    for (int j : {HIPS, SPINE, LEG_L, LEG_R}) CHECK(w[j] == 1.f);
    for (int j : {HEAD, EYE, SHOULDER_L, UPPER_ARM_L, HAND_L, PROP_L, SHOULDER_R, HAND_R}) CHECK(w[j] == 0.f);

    // A neck, when mapped, hides from there (the head's branch is inside it).
    cc_char_role_set(full, "neck", "spine");
    CHECK(cc_char_view_mask(full, rig, CC_VIEW_HEADLESS, m, nullptr) == CC_OK);
    CHECK(weights(m)[SPINE] == 0.f && weights(m)[HIPS] == 1.f);

    // No shoulders: the upper arms; no arms at all: nothing to show in first person.
    cc_char* no_shoulders = make_char(rig, false, true, true);
    CHECK(cc_char_view_mask(no_shoulders, rig, CC_VIEW_FIRST_PERSON, m, &missing) == CC_OK && missing == 0);
    w = weights(m);
    CHECK(w[SHOULDER_L] == 0.f && w[UPPER_ARM_L] == 1.f && w[HAND_L] == 1.f && w[UPPER_ARM_R] == 1.f);
    cc_char* armless = make_char(rig, false, false, true);
    CHECK(cc_char_view_mask(armless, rig, CC_VIEW_FIRST_PERSON, m, &missing) == CC_ERR_RANGE && missing == 2);
    CHECK(cc_char_view_mask(armless, rig, CC_VIEW_BODY_AWARENESS, m, &missing) == CC_OK && missing == 2);
    cc_char* headless = make_char(rig, true, true, false);
    CHECK(cc_char_view_mask(headless, rig, CC_VIEW_HEADLESS, m, &missing) == CC_ERR_RANGE && missing == 1);

    CHECK(cc_char_view_mask(full, rig, 7, m, nullptr) == CC_ERR_ARG);
    cr_rig* other = make_rig();
    cr_joint_desc one = bone(-1, 0, 0, 0, 0, 1, 0);
    cr_rig* tiny = cr_rig_create(&one, 1, nullptr, nullptr);
    cr_mask* wrong = cr_mask_create(tiny, 1.f);
    CHECK(cc_char_view_mask(full, rig, CC_VIEW_HEADLESS, wrong, nullptr) == CC_ERR_ARG);

    cr_mask_destroy(wrong);
    cr_rig_destroy(tiny);
    cr_rig_destroy(other);
    cc_char_destroy(full);
    cc_char_destroy(no_shoulders);
    cc_char_destroy(armless);
    cc_char_destroy(headless);
    cr_mask_destroy(m);
    cr_rig_destroy(rig);
}

void test_palette_hide() {
    cr_rig* rig = make_rig();
    cc_char* c = make_char(rig, true, true, true);
    cr_mask* m = cr_mask_create(rig, 1.f);
    std::vector<float> locals(JOINTS * 9);
    // A non-rest pose: spine bent, head turned, arm raised.
    CHECK(cr_rig_local_pose(rig, nullptr, 0.f, nullptr, 0, locals.data()) == CR_OK);
    locals[SPINE * 9 + 3] = 20.f;
    locals[HEAD * 9 + 4] = 35.f;
    locals[UPPER_ARM_L * 9 + 5] = -70.f;
    const Posed body = pose(rig, locals.data());
    const double probe[3] = {0.3, -0.7, 1.9};

    // Headless: the head and the eye under it collapse to the head's point; nothing else changes.
    CHECK(cc_char_view_mask(c, rig, CC_VIEW_HEADLESS, m, nullptr) == CC_OK);
    Posed p = body;
    CHECK(cr_palette_hide(rig, m, p.world.data(), p.skin.data()) == CR_OK);
    CHECK(std::memcmp(p.world.data(), body.world.data(), body.world.size() * 4) == 0);
    double head[3], q[3];
    head[0] = body.world[HEAD * 16 + 12];
    head[1] = body.world[HEAD * 16 + 13];
    head[2] = body.world[HEAD * 16 + 14];
    for (int j : {HEAD, EYE}) {
        apply(p.skin.data() + j * 16, probe, q);
        CHECK(near(q[0], head[0], 1e-6) && near(q[1], head[1], 1e-6) && near(q[2], head[2], 1e-6));
    }
    for (int j = 0; j < JOINTS; ++j) {
        if (j == HEAD || j == EYE) continue;
        CHECK(std::memcmp(p.skin.data() + j * 16, body.skin.data() + j * 16, 64) == 0);
    }

    // First person: everything but the arms collapses to ONE point, the root's (hips head): no slivers.
    CHECK(cc_char_view_mask(c, rig, CC_VIEW_FIRST_PERSON, m, nullptr) == CC_OK);
    p = body;
    CHECK(cr_palette_hide(rig, m, p.world.data(), p.skin.data()) == CR_OK);
    const double hips[3] = {body.world[HIPS * 16 + 12], body.world[HIPS * 16 + 13], body.world[HIPS * 16 + 14]};
    CHECK(near(hips[0], 5.0, 1e-6) && near(hips[1], 1.0, 1e-6) && near(hips[2], 3.0, 1e-6));
    for (int j : {HIPS, SPINE, HEAD, EYE, LEG_L, LEG_R}) {
        apply(p.skin.data() + j * 16, probe, q);
        CHECK(near(q[0], hips[0], 1e-6) && near(q[1], hips[1], 1e-6) && near(q[2], hips[2], 1e-6));
    }
    for (int j : {SHOULDER_L, UPPER_ARM_L, HAND_L, PROP_L, SHOULDER_R, UPPER_ARM_R, HAND_R}) {
        CHECK(std::memcmp(p.skin.data() + j * 16, body.skin.data() + j * 16, 64) == 0);
    }

    // A partial weight shrinks part way toward the point (head at 0.5, alone).
    cr_mask_fill(m, 1.f);
    cr_mask_set(m, HEAD, 0.5f, 0, 0);
    p = body;
    CHECK(cr_palette_hide(rig, m, p.world.data(), p.skin.data()) == CR_OK);
    double full[3];
    apply(body.skin.data() + HEAD * 16, probe, full);
    apply(p.skin.data() + HEAD * 16, probe, q);
    for (int r = 0; r < 3; ++r) CHECK(near(q[r], 0.5 * full[r] + 0.5 * head[r], 1e-6));
    CHECK(std::memcmp(p.skin.data() + EYE * 16, body.skin.data() + EYE * 16, 64) == 0);

    // A part hides toward its pivot.
    cr_joint_desc part{};
    part.parent = -1;
    part.kind = CR_JOINT_PART;
    part.origin[0] = 1.f;
    part.origin[1] = 2.f;
    part.origin[2] = 3.f;
    part.scale[0] = part.scale[1] = part.scale[2] = 1.f;
    cr_rig* prig = cr_rig_create(&part, 1, nullptr, nullptr);
    cr_mask* pm = cr_mask_create(prig, 0.f);
    float ps[16], pw[16];
    CHECK(cr_rig_pose_locals(prig, nullptr, nullptr, 0, kBase, ps, pw, nullptr) == CR_OK);
    CHECK(cr_palette_hide(prig, pm, pw, ps) == CR_OK);
    apply(ps, probe, q);
    CHECK(near(q[0], 6.0, 1e-6) && near(q[1], 2.0, 1e-6) && near(q[2], 6.0, 1e-6));
    CHECK(cr_palette_hide(rig, pm, p.world.data(), p.skin.data()) == CR_ERR_ARG);  // mask of another rig
    CHECK(cr_palette_hide(rig, nullptr, p.world.data(), p.skin.data()) == CR_ERR_ARG);

    cr_mask_destroy(pm);
    cr_rig_destroy(prig);
    cr_mask_destroy(m);
    cc_char_destroy(c);
    cr_rig_destroy(rig);
}

void test_eyes_point() {
    cr_rig* rig = make_rig();
    cc_char* c = make_char(rig, true, true, true);
    std::vector<float> locals(JOINTS * 9);
    CHECK(cr_rig_local_pose(rig, nullptr, 0.f, nullptr, 0, locals.data()) == CR_OK);
    locals[HEAD * 9 + 4] = 180.f;  // head turned around: the eyes swing from -z to +z of the head
    const Posed p = pose(rig, locals.data());
    float e[3];

    CHECK(cc_char_eyes_point(c, rig, p.skin.data(), p.world.data(), e) == CC_EYES_HEAD);
    CHECK(near(e[0], 5.0, 1e-6) && near(e[1], 1.5, 1e-6) && near(e[2], 3.0, 1e-6));

    cc_char_role_set(c, "eye_l", "eye");
    CHECK(cc_char_eyes_point(c, rig, p.skin.data(), p.world.data(), e) == CC_EYES_ROLES);
    CHECK(near(e[0], 5.0, 1e-5) && near(e[1], 1.6, 1e-5) && near(e[2], 3.1, 1e-5));

    const float at[3] = {0.f, 1.62f, -0.12f};  // rest model space
    CHECK(cc_char_socket_set(c, "eyes", "head", at, nullptr, nullptr) == CC_OK);
    CHECK(cc_char_eyes_point(c, rig, p.skin.data(), nullptr, e) == CC_EYES_SOCKET);
    CHECK(near(e[0], 5.0, 1e-5) && near(e[1], 1.62, 1e-5) && near(e[2], 3.12, 1e-5));

    cc_char* bare = make_char(rig, true, true, false);
    CHECK(cc_char_eyes_point(bare, rig, p.skin.data(), p.world.data(), e) == CC_ERR_RANGE);
    cc_char_destroy(bare);
    cc_char_destroy(c);
    cr_rig_destroy(rig);
}

// ---- first-person projection ----------------------------------------------------------------------------

void perspective(double fovy_deg, double aspect, double n, double f, float* out) {
    const double t = 1.0 / std::tan(fovy_deg * M_PI / 360.0);
    std::memset(out, 0, 64);
    out[0] = static_cast<float>(t / aspect);
    out[5] = static_cast<float>(t);
    out[10] = static_cast<float>(-(f + n) / (f - n));
    out[11] = -1.f;
    out[14] = static_cast<float>(-2.0 * f * n / (f - n));
}

// View: eye at E, turned by yaw about Y (looking -Z at yaw 0). world -> view = Ry(-yaw)·T(-E).
const double E[3] = {3.0, 2.0, 7.0};
const double kYaw = 30.0 * M_PI / 180.0;
void make_view(float* v) {
    const double c = std::cos(kYaw), s = std::sin(kYaw);
    // R = Ry(-yaw): rows (c,0,-s),(0,1,0),(s,0,c) applied to (p - E).
    const double R[3][3] = {{c, 0, -s}, {0, 1, 0}, {s, 0, c}};
    std::memset(v, 0, 64);
    for (int r = 0; r < 3; ++r) {
        for (int k = 0; k < 3; ++k) v[k * 4 + r] = static_cast<float>(R[r][k]);
        v[12 + r] = static_cast<float>(-(R[r][0] * E[0] + R[r][1] * E[1] + R[r][2] * E[2]));
    }
    v[15] = 1.f;
}

// A world point at view depth d along the view ray through view-space direction (dx, dy, -1).
void point_at(double d, double dx, double dy, double out[3]) {
    const double vx = dx * d, vy = dy * d, vz = -d;
    const double c = std::cos(kYaw), s = std::sin(kYaw);
    // world = R^T v + E, R^T = Ry(yaw): rows (c,0,s),(0,1,0),(-s,0,c).
    out[0] = c * vx + s * vz + E[0];
    out[1] = vy + E[1];
    out[2] = -s * vx + c * vz + E[2];
}

// clip = P · V · M · p (M null = identity).
void clip_of(const float* P, const float* V, const float* M, const double p[3], double out[4]) {
    double q[3] = {p[0], p[1], p[2]};
    if (M != nullptr) apply(M, p, q);
    double v[3];
    apply(V, q, v);
    double Pd[16];
    for (int i = 0; i < 16; ++i) Pd[i] = P[i];
    const double v4[4] = {v[0], v[1], v[2], 1.0};
    apply4(Pd, v4, out);
}

void test_projection() {
    float P[16], V[16], fp[16], pull[16];
    perspective(90.0, 1.5, 0.05, 500.0, P);
    P[8] = 0.01f;   // jitter / off-centre terms survive
    P[9] = -0.02f;
    make_view(V);
    cr_first_person_desc d{};
    d.fov_y_deg = 70.f;
    d.near = 0.01f;
    d.far = 2.f;
    d.depth_scale = 0.5f;
    d.pull = 0.2f;
    CHECK(cr_first_person_projection(V, P, &d, fp, pull) == CR_OK);

    // Own FOV, same aspect, jitter kept, w = view depth.
    CHECK(near(fp[5], 1.0 / std::tan(35.0 * M_PI / 180.0), 1e-5));
    CHECK(near(fp[0] / fp[5], P[0] / P[5], 1e-6));
    CHECK(fp[8] == P[8] && fp[9] == P[9] && fp[11] == -1.f && fp[15] == 0.f);

    // Depth: near -> the world near plane (-1), far -> the slab end n·1.5, ordered, perspective-correct, in front
    // of a wall 0.1 ahead.
    auto world_ndc = [&](double depth) { return (-P[10] * depth + P[14]) / depth; };
    const double wall = world_ndc(0.1);
    const double slab_end = world_ndc(0.075);
    const double depths[] = {0.01, 0.02, 0.05, 0.1, 0.3, 0.6, 1.0, 1.5, 2.0};
    double prev = -2.0, A = 0, B = 0;
    for (size_t i = 0; i < sizeof(depths) / sizeof(depths[0]); ++i) {
        double p[3], cl[4];
        point_at(depths[i], 0.3, -0.2, p);
        clip_of(fp, V, pull, p, cl);
        const double z = cl[2] / cl[3];
        CHECK(near(cl[3], 0.2 * depths[i], 1e-6));  // w = pulled view depth
        CHECK(z > prev);
        CHECK(z < wall);
        prev = z;
        if (i == 0) {
            CHECK(near(z, -1.0, 2e-5));
            A = z;
            B = cl[3];
        } else if (i == 1) {
            // z = a + b / w: fit from the first two, every other depth must lie on it.
            const double b = (z - A) / (1.0 / cl[3] - 1.0 / B);
            A = z - b / cl[3];
            B = b;
        } else {
            CHECK(near(z, A + B / cl[3], 2e-5));
        }
        if (i + 1 == sizeof(depths) / sizeof(depths[0])) CHECK(near(z, slab_end, 2e-5));
    }

    // The pull moves points toward the eye along their ray: the screen image is the one the FP FOV would give at
    // the true position.
    float wfov[16];
    std::memcpy(wfov, P, 64);
    const double c = fp[5] / P[5];
    for (int r = 0; r < 4; ++r) {
        wfov[r] = static_cast<float>(P[r] * c);
        wfov[4 + r] = static_cast<float>(P[4 + r] * c);
    }
    for (double dd : {0.05, 0.4, 1.7}) {
        double p[3], a[4], b[4];
        point_at(dd, -0.4, 0.25, p);
        clip_of(fp, V, pull, p, a);
        clip_of(wfov, V, nullptr, p, b);
        CHECK(near(a[0] / a[3], b[0] / b[3], 1e-5) && near(a[1] / a[3], b[1] / b[3], 1e-5));
    }
    double eye_out[3], pt[3] = {1.0, -2.0, 4.0}, pq[3];
    apply(pull, E, eye_out);
    CHECK(near(eye_out[0], E[0], 1e-5) && near(eye_out[1], E[1], 1e-5) && near(eye_out[2], E[2], 1e-5));
    apply(pull, pt, pq);
    for (int r = 0; r < 3; ++r) CHECK(near(pq[r], E[r] + 0.2 * (pt[r] - E[r]), 1e-5));

    // Defaults: the world's FOV kept exactly; outputs optional.
    float fp2[16];
    CHECK(cr_first_person_projection(V, P, nullptr, fp2, nullptr) == CR_OK);
    for (int i : {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 11, 12, 13, 15}) CHECK(fp2[i] == P[i]);
    CHECK(cr_first_person_projection(V, P, nullptr, nullptr, nullptr) == CR_OK);

    // Refusals.
    float ortho[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, -0.01f, 0, 0, 0, -1, 1};
    CHECK(cr_first_person_projection(V, ortho, &d, fp, pull) == CR_ERR_ARG);
    cr_first_person_desc bad = d;
    bad.pull = 1.5f;
    CHECK(cr_first_person_projection(V, P, &bad, fp, pull) == CR_ERR_ARG);
    bad = d;
    bad.far = 0.005f;
    CHECK(cr_first_person_projection(V, P, &bad, fp, pull) == CR_ERR_ARG);
    CHECK(cr_first_person_projection(nullptr, P, &d, fp, pull) == CR_ERR_ARG);
}

// ---- stabilizer ---------------------------------------------------------------------------------------

void test_stabilizer() {
    cr_stabilizer_desc d{0.02f, 0.1f, 0.3f, 1.f};
    cr_stabilizer* s = cr_stabilizer_create(&d);
    const float dt = 1.f / 60.f;
    float out[3];

    // Small bob (amplitude 0.01 < threshold) passes through exactly, the first frame too.
    for (int i = 0; i < 240; ++i) {
        const float t = static_cast<float>(i) * dt;
        const float raw[3] = {0.f, 1.6f + 0.01f * std::sin(2.f * float(M_PI) * 2.f * t), 0.f};
        CHECK(cr_stabilizer_update(s, raw, nullptr, dt, out) == CR_OK);
        CHECK(near(out[1], raw[1], 1e-6) && out[0] == 0.f && out[2] == 0.f);
    }

    // Large bob (0.05 at 2 Hz) is cut to about the threshold.
    cr_stabilizer_reset(s);
    float lo = 1e9f, hi = -1e9f;
    for (int i = 0; i < 600; ++i) {
        const float t = static_cast<float>(i) * dt;
        const float raw[3] = {0.f, 1.6f + 0.05f * std::sin(2.f * float(M_PI) * 2.f * t), 0.f};
        CHECK(cr_stabilizer_update(s, raw, nullptr, dt, out) == CR_OK);
        if (i >= 300) {
            lo = std::fmin(lo, out[1]);
            hi = std::fmax(hi, out[1]);
        }
    }
    std::printf("[first_person] bob 0.10 peak-to-peak -> camera %.4f\n", static_cast<double>(hi - lo));
    CHECK(hi - lo < 2.f * (0.02f + 0.015f));
    CHECK(hi - lo > 2.f * 0.02f);  // the threshold's worth still moves

    // A crouch (0.5 down) is intended motion: the camera trails by max_offset - threshold at most and settles fast.
    for (int i = 0; i < 120; ++i) {
        const float raw[3] = {0.f, 1.6f, 0.f};
        cr_stabilizer_update(s, raw, nullptr, dt, out);
    }
    const float crouch[3] = {0.f, 1.1f, 0.f};
    int frames = 0;
    for (; frames < 600; ++frames) {
        cr_stabilizer_update(s, crouch, nullptr, dt, out);
        CHECK(std::fabs(out[1] - crouch[1]) <= 0.08f + 1e-5f);
        if (std::fabs(out[1] - crouch[1]) < 1e-3f) break;
    }
    const float settle = static_cast<float>(frames) * dt;
    std::printf("[first_person] crouch settles in %.3f s\n", static_cast<double>(settle));
    CHECK(settle < 0.3f * 3.f);

    // Walking and turning (the base) never lags: a fixed model-space eye point comes out exactly.
    cr_stabilizer_reset(s);
    for (int i = 0; i < 300; ++i) {
        const float fi = static_cast<float>(i);
        const float a = 0.02f * fi, x = 4.f * fi * dt;
        const float base[16] = {std::cos(a), 0, -std::sin(a), 0, 0, 1, 0, 0, std::sin(a), 0, std::cos(a), 0, x, 0.f, 2.f, 1};
        const double model[3] = {0.0, 1.6, -0.1};
        double world[3];
        apply(base, model, world);
        const float raw[3] = {float(world[0]), float(world[1]), float(world[2])};
        CHECK(cr_stabilizer_update(s, raw, base, dt, out) == CR_OK);
        CHECK(near(out[0], raw[0], 2e-5) && near(out[1], raw[1], 2e-5) && near(out[2], raw[2], 2e-5));
    }

    // A teleport (further than snap) resets instead of sliding.
    const float a[3] = {0.f, 1.6f, 0.f}, b[3] = {0.f, 3.6f, 0.f};
    cr_stabilizer_update(s, a, nullptr, dt, out);
    CHECK(cr_stabilizer_update(s, b, nullptr, dt, out) == CR_OK && out[1] == b[1]);

    // Deterministic, aliasing allowed, refusals.
    cr_stabilizer* s2 = cr_stabilizer_create(&d);
    cr_stabilizer_reset(s);
    for (int i = 0; i < 100; ++i) {
        const float fi = static_cast<float>(i);
        float r1[3] = {0.f, 1.6f + 0.07f * std::sin(0.4f * fi), 0.03f * std::cos(0.3f * fi)};
        float r2[3] = {r1[0], r1[1], r1[2]};
        cr_stabilizer_update(s, r1, nullptr, dt, r1);
        cr_stabilizer_update(s2, r2, nullptr, dt, out);
        CHECK(std::memcmp(r1, out, 12) == 0);
    }
    const float nan3[3] = {NAN, 0.f, 0.f};
    CHECK(cr_stabilizer_update(s, nan3, nullptr, dt, out) == CR_ERR_ARG);
    CHECK(cr_stabilizer_update(nullptr, a, nullptr, dt, out) == CR_ERR_ARG);
    cr_stabilizer_destroy(s2);
    cr_stabilizer_destroy(s);
    cr_stabilizer_destroy(cr_stabilizer_create(nullptr));
}

// ---- graph first-person view layers ---------------------------------------------------------------------

cr_key key(float t, float rx) {
    cr_key k{};
    k.time = t;
    k.rot_deg[0] = rx;
    k.scale[0] = k.scale[1] = k.scale[2] = 1.f;
    k.easing = CR_EASE_LINEAR;
    return k;
}

cm_clip* swing(const char* joint, float a, float b) {
    cm_clip* c = cm_clip_create();
    cm_header h{30.f, 1.f, 1, 0, 0.f, 0.f, 0};
    cm_clip_header_set(c, &h);
    cr_key k[2] = {key(0.f, a), key(1.f, b)};
    cm_clip_track_set(c, joint, joint, nullptr, 0, k, 2);
    return c;
}

std::string graph_json(const char* layers) {
    return std::string(R"({"format": "omanimgraph", "version": "1.0",
      "parameters": [{"name": "firstPerson", "type": "float"}],
      "slots": ["body", "fp"],
      "root": "out",
      "nodes": [
        {"id": "bodyClip", "type": "clip", "slot": "body"},
        {"id": "fpClip", "type": "clip", "slot": "fp"},
        {"id": "out", "type": "layer", "base": "bodyClip", "layers": [)") + layers + "]}]}";
}

cg_graph* read_graph(const std::string& json, int32_t* rc) {
    return cg_graph_read(json.data(), static_cast<int64_t>(json.size()), rc);
}

void test_graph_views() {
    cr_rig* rig = make_rig();
    cm_clip* body = swing("spine", 0.f, 12.f);
    cm_clip* fp = swing("upper_arm_l", 10.f, -60.f);  // a full-body override that only keys the arm
    int32_t rc = 0;
    cg_graph* gv = read_graph(graph_json(R"({"node": "fpClip", "view": "firstPerson", "weightParam": "firstPerson"})"), &rc);
    cg_graph* gn = read_graph(graph_json(R"({"node": "fpClip", "weightParam": "firstPerson"})"), &rc);
    // The body graph: the same layer with weight 0 (the body pose must not depend on the view layer).
    cg_graph* gb = read_graph(graph_json(R"({"node": "fpClip", "weight": 0})"), &rc);
    CHECK(gv != nullptr && gn != nullptr && gb != nullptr);
    if (gv == nullptr || gn == nullptr || gb == nullptr) return;
    CHECK(cg_graph_first_person_layer_count(gv) == 1 && cg_graph_first_person_layer_count(gn) == 0);

    cg_instance* iv = cg_instance_create(gv, rig, nullptr, nullptr);
    cg_instance* in = cg_instance_create(gn, rig, nullptr, nullptr);
    cg_instance* ib = cg_instance_create(gb, rig, nullptr, nullptr);
    for (cg_instance* i : {iv, in, ib}) {
        CHECK(cg_instance_bind_slot(i, 0, body, nullptr) == CG_OK);
        CHECK(cg_instance_bind_slot(i, 1, fp, nullptr) == CG_OK);
        CHECK(cg_instance_set_float(i, 0, 1.f) == CG_OK);
    }
    std::vector<float> sv(JOINTS * 16), sn(JOINTS * 16), sb(JOINTS * 16), view(JOINTS * 16), wv(JOINTS * 16);
    for (int f = 0; f < 7; ++f) {
        CHECK(cg_instance_evaluate(iv, 0.13f, nullptr, 0, kBase, sv.data(), wv.data(), nullptr) == CG_OK);
        CHECK(cg_instance_evaluate(in, 0.13f, nullptr, 0, kBase, sn.data(), nullptr, nullptr) == CG_OK);
        CHECK(cg_instance_evaluate(ib, 0.13f, nullptr, 0, kBase, sb.data(), nullptr, nullptr) == CG_OK);
        // Body = without the layer, bit for bit; first person = with it.
        CHECK(std::memcmp(sv.data(), sb.data(), sv.size() * 4) == 0);
        CHECK(cg_instance_compose_view(iv, CG_VIEW_FIRST_PERSON, nullptr, 0, kBase, view.data(), nullptr, nullptr) == CG_OK);
        CHECK(std::memcmp(view.data(), sn.data(), sn.size() * 4) == 0);
        CHECK(std::memcmp(view.data(), sv.data(), sv.size() * 4) != 0);
        CHECK(cg_instance_compose_view(iv, CG_VIEW_BODY, nullptr, 0, kBase, view.data(), nullptr, nullptr) == CG_OK);
        CHECK(std::memcmp(view.data(), sv.data(), sv.size() * 4) == 0);
        // A graph without view layers: first person = body.
        CHECK(cg_instance_compose_view(ib, CG_VIEW_FIRST_PERSON, nullptr, 0, kBase, view.data(), nullptr, nullptr) == CG_OK);
        CHECK(std::memcmp(view.data(), sb.data(), sb.size() * 4) == 0);
    }
    // A full-body view layer takes nothing from the base's influence (root motion, notifies, sync stay the body's).
    std::vector<cg_node_debug> dv(static_cast<size_t>(cg_graph_node_count(gv))), dn(dv.size());
    cg_instance_debug(iv, dv.data());
    cg_instance_debug(in, dn.data());
    const int base_node = cg_graph_node_find(gv, "bodyClip");
    CHECK(dv[static_cast<size_t>(base_node)].influence == 1.f);
    CHECK(dn[static_cast<size_t>(base_node)].influence == 0.f);
    CHECK(dv[static_cast<size_t>(cg_graph_node_find(gv, "fpClip"))].influence == 1.f);
    // Switched off by its parameter: first person = body.
    cg_instance_set_float(iv, 0, 0.f);
    CHECK(cg_instance_evaluate(iv, 0.1f, nullptr, 0, kBase, sv.data(), nullptr, nullptr) == CG_OK);
    CHECK(cg_instance_compose_view(iv, CG_VIEW_FIRST_PERSON, nullptr, 0, kBase, view.data(), nullptr, nullptr) == CG_OK);
    CHECK(std::memcmp(view.data(), sv.data(), sv.size() * 4) == 0);
    CHECK(cg_instance_compose_view(iv, 5, nullptr, 0, kBase, view.data(), nullptr, nullptr) == CG_ERR_ARG);

    // Format: written back, refused off the root, refused for an unknown view.
    cc_bytes* w = cg_graph_write(gv);
    const std::string text(reinterpret_cast<const char*>(cc_bytes_data(w)), static_cast<size_t>(cc_bytes_size(w)));
    CHECK(text.find("\"view\": \"firstPerson\"") != std::string::npos || text.find("\"view\":\"firstPerson\"") != std::string::npos);
    cg_graph* again = read_graph(text, &rc);
    CHECK(again != nullptr && cg_graph_first_person_layer_count(again) == 1);
    cg_graph_destroy(again);
    cc_bytes_destroy(w);
    const std::string nested = R"({"format": "omanimgraph", "version": "1.0", "slots": ["a", "b"], "root": "top",
      "nodes": [{"id": "a", "type": "clip", "slot": "a"}, {"id": "b", "type": "clip", "slot": "b"},
        {"id": "inner", "type": "layer", "base": "a", "layers": [{"node": "b", "view": "firstPerson"}]},
        {"id": "top", "type": "slot", "name": "full", "source": "inner"}]})";
    CHECK(read_graph(nested, &rc) == nullptr && rc == CG_ERR_FORMAT);
    CHECK(read_graph(graph_json(R"({"node": "fpClip", "view": "thirdPerson"})"), &rc) == nullptr && rc == CG_ERR_FORMAT);

    cg_instance_destroy(iv);
    cg_instance_destroy(in);
    cg_instance_destroy(ib);
    cg_graph_destroy(gv);
    cg_graph_destroy(gn);
    cg_graph_destroy(gb);
    cm_clip_destroy(body);
    cm_clip_destroy(fp);
    cr_rig_destroy(rig);
}

}  // namespace

int main() {
    test_view_masks();
    test_palette_hide();
    test_eyes_point();
    test_projection();
    test_stabilizer();
    test_graph_views();
    if (failures != 0) {
        std::fprintf(stderr, "first_person: %d failure(s)\n", failures);
        return 1;
    }
    std::printf("first_person: OK\n");
    return 0;
}
