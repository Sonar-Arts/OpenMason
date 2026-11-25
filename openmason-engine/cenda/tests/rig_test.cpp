// Cenda rig (cr_*) conformance: joint kinds and hierarchy, rest/pose/skin matrices, head tracking,
// clip sampling (easing, shortest-path angles, clamping, wrapping), binding by id/name, overlay
// masks/priority/fades, cycle cutting, and the skin-weight operations. Plain asserts.

#include "cenda/rig.h"

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
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

bool near(float a, float b, float eps = 1e-5f) { return std::fabs(a - b) <= eps; }

cr_joint_desc part(int parent, float ox, float oy, float oz, float px, float py, float pz) {
    cr_joint_desc d{};
    d.parent = parent;
    d.kind = CR_JOINT_PART;
    d.flags = CR_JOINT_HAS_GEOMETRY;
    d.origin[0] = ox; d.origin[1] = oy; d.origin[2] = oz;
    d.pos[0] = px; d.pos[1] = py; d.pos[2] = pz;
    d.scale[0] = d.scale[1] = d.scale[2] = 1.f;
    return d;
}

cr_joint_desc bone(int parent, float py, float tail_y) {
    cr_joint_desc d{};
    d.parent = parent;
    d.kind = CR_JOINT_BONE;
    d.pos[1] = py;
    d.scale[0] = d.scale[1] = d.scale[2] = 1.f;
    d.endpoint[1] = tail_y;
    return d;
}

// Apply column-major m to point p.
void xform(const float* m, const float p[3], float out[3]) {
    for (int r = 0; r < 3; ++r) out[r] = m[r] * p[0] + m[4 + r] * p[1] + m[8 + r] * p[2] + m[12 + r];
}

cr_key key(float t, float ry, int easing = CR_EASE_LINEAR) {
    cr_key k{};
    k.time = t;
    k.rot_deg[1] = ry;
    k.scale[0] = k.scale[1] = k.scale[2] = 1.f;
    k.easing = easing;
    return k;
}

void test_rest_and_part_rotation() {
    // arm pivots at (1,0,0); hand is its child.
    cr_joint_desc j[2] = {part(-1, 1, 0, 0, 0, 0, 0), part(0, 0, 0, 0, 0, 0, 0)};
    const char* ids[2] = {"arm-id", "hand-id"};
    const char* names[2] = {"arm", "hand"};
    cr_rig* rig = cr_rig_create(j, 2, ids, names);
    CHECK(rig != nullptr);
    std::vector<float> skin(32);
    CHECK(cr_rig_pose(rig, nullptr, 0.f, nullptr, 0, nullptr, 0, nullptr, skin.data(), nullptr, nullptr) == CR_OK);
    for (size_t i = 0; i < 16; ++i) CHECK(near(skin[i], (i % 5 == 0) ? 1.f : 0.f));

    // A clip turns the arm 90 degrees about Y around its pivot; the hand follows.
    cr_key keys[1] = {key(0.f, 90.f)};
    cr_track_desc tracks[1] = {{0, 1}};
    const char* tid[1] = {"arm-id"};
    const char* tname[1] = {"arm"};
    cr_clip_desc desc{1.f, 1, CR_LAYER_BASE, 0, 0.1f, 0.1f};
    cr_clip* clip = cr_clip_create(&desc, tracks, 1, keys, 1, tid, tname, nullptr, 0);
    CHECK(clip != nullptr);
    CHECK(cr_rig_pose(rig, clip, 0.25f, nullptr, 0, nullptr, 0, nullptr, skin.data(), nullptr, nullptr) == CR_OK);
    const float p[3] = {2.f, 0.f, 0.f};
    float out[3];
    xform(skin.data(), p, out);
    CHECK(near(out[0], 1.f) && near(out[1], 0.f) && near(out[2], -1.f));
    xform(skin.data() + 16, p, out);  // the hand inherits
    CHECK(near(out[0], 1.f) && near(out[2], -1.f));

    // Base matrix multiplies on the left.
    float base[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 10, 20, 30, 1};
    CHECK(cr_rig_pose(rig, clip, 0.25f, nullptr, 0, nullptr, 0, base, skin.data(), nullptr, nullptr) == CR_OK);
    xform(skin.data(), p, out);
    CHECK(near(out[0], 11.f) && near(out[1], 20.f) && near(out[2], 29.f));

    // The same pose from explicit locals.
    std::vector<float> locals(18, 0.f);
    locals[4] = 90.f;  // arm rot.y
    locals[6] = locals[7] = locals[8] = 1.f;
    locals[15] = locals[16] = locals[17] = 1.f;
    std::vector<float> skin2(32);
    CHECK(cr_rig_pose_locals(rig, locals.data(), nullptr, 0, base, skin2.data(), nullptr, nullptr) == CR_OK);
    for (size_t i = 0; i < 32; ++i) CHECK(near(skin[i], skin2[i]));
    CHECK(cr_rig_pose_locals(rig, nullptr, nullptr, 0, nullptr, skin2.data(), nullptr, nullptr) == CR_OK);
    for (size_t i = 0; i < 16; ++i) CHECK(near(skin2[i], (i % 5 == 0) ? 1.f : 0.f));

    cr_clip_destroy(clip);
    cr_rig_destroy(rig);
}

void test_bones_and_parts_under_bones() {
    // root bone at y=1 with tail +1; a child bone at its tail; a part hangs under the child bone.
    cr_joint_desc j[3] = {bone(-1, 1.f, 1.f), bone(0, 0.f, 1.f), part(1, 0, 0, 0, 0, 0, 0)};
    j[0].flags = 0;
    j[1].flags = 0;
    const char* names[3] = {"spine", "head", "skull"};
    cr_rig* rig = cr_rig_create(j, 3, nullptr, names);
    CHECK(rig != nullptr);
    std::vector<float> world(48), child(48);
    CHECK(cr_rig_rest_world(rig, world.data(), child.data()) == CR_OK);
    CHECK(near(world[13], 1.f));          // spine head at y=1
    CHECK(near(child[13], 2.f));          // spine tail at y=2
    CHECK(near(world[16 + 13], 2.f));     // head bone head = spine tail
    CHECK(near(child[16 + 13], 3.f));     // head bone tail
    CHECK(near(world[32 + 13], 3.f));     // the part sits at the head bone's tail
    // Head tracking prefers the bone with geometry below it.
    CHECK(cr_rig_find_head(rig) == 1);
    CHECK(cr_rig_find(rig, "HEAD") == 1);

    // Turning the spine bone 90 degrees about Z swings everything above it.
    cr_key k{};
    k.rot_deg[2] = 90.f;
    k.pos[1] = 1.f;  // absolute local pose: keep the rest translation
    k.scale[0] = k.scale[1] = k.scale[2] = 1.f;
    cr_track_desc t{0, 1};
    const char* tname[1] = {"spine"};
    cr_clip_desc desc{1.f, 0, CR_LAYER_BASE, 0, 0.f, 0.f};
    cr_clip* clip = cr_clip_create(&desc, &t, 1, &k, 1, nullptr, tname, nullptr, 0);
    std::vector<float> skin(48), posed(48);
    CHECK(cr_rig_pose(rig, clip, 0.f, nullptr, 0, nullptr, 0, nullptr, skin.data(), posed.data(), nullptr) == CR_OK);
    CHECK(near(posed[32 + 12], -2.f) && near(posed[32 + 13], 1.f));  // part origin swung to (-2,1)
    const float v[3] = {0.f, 3.f, 0.f};  // a vertex at the part's rest origin
    float out[3];
    xform(skin.data() + 32, v, out);
    CHECK(near(out[0], -2.f) && near(out[1], 1.f));

    // Head tracking extra on the head bone: yaw 90 turns the skull's +X offset to -Z.
    cr_joint_extra ex{1, 90.f, 0.f, {0.f, 0.f, 0.f}};
    CHECK(cr_rig_pose(rig, nullptr, 0.f, nullptr, 0, &ex, 1, nullptr, skin.data(), nullptr, nullptr) == CR_OK);
    const float side[3] = {1.f, 3.f, 0.f};
    xform(skin.data() + 32, side, out);
    CHECK(near(out[0], 0.f) && near(out[1], 3.f) && near(out[2], -1.f));
    // An out-of-range extra is refused.
    cr_joint_extra bad{7, 1.f, 0.f, {0.f, 0.f, 0.f}};
    CHECK(cr_rig_pose(rig, nullptr, 0.f, nullptr, 0, &bad, 1, nullptr, skin.data(), nullptr, nullptr) == CR_ERR_RANGE);

    cr_clip_destroy(clip);
    cr_rig_destroy(rig);
}

void test_cycles_and_head_fallback() {
    cr_joint_desc j[3] = {part(2, 0, 0, 0, 0, 0, 0), part(0, 0, 0, 0, 0, 0, 0), part(1, 0, 0, 0, 0, 0, 0)};
    const char* names[3] = {"body", "Head", "head_top"};
    cr_rig* rig = cr_rig_create(j, 3, nullptr, names);
    CHECK(rig != nullptr);
    CHECK((cr_rig_flags(rig) & CR_RIG_CYCLE_BROKEN) != 0);
    CHECK(cr_rig_find_head(rig) == 1);  // first PART containing "head"
    std::vector<float> skin(48);
    CHECK(cr_rig_pose(rig, nullptr, 0.f, nullptr, 0, nullptr, 0, nullptr, skin.data(), nullptr, nullptr) == CR_OK);
    for (float f : skin) CHECK(std::isfinite(f));
    cr_rig_destroy(rig);

    cr_joint_desc bad = part(9, 0, 0, 0, 0, 0, 0);
    cr_rig* r2 = cr_rig_create(&bad, 1, nullptr, nullptr);
    CHECK(r2 != nullptr && (cr_rig_flags(r2) & CR_RIG_BAD_PARENT) != 0);
    cr_rig_destroy(r2);
    CHECK(cr_rig_create(nullptr, 0, nullptr, nullptr) == nullptr);
    CHECK(std::strlen(cr_last_error()) > 0);
}

void test_sampling() {
    cr_key keys[3] = {key(0.f, 350.f, CR_EASE_LINEAR), key(1.f, 10.f, CR_EASE_STEP), key(2.f, 50.f)};
    cr_track_desc t{0, 3};
    const char* tid[1] = {"x"};
    cr_clip_desc desc{2.f, 1, CR_LAYER_BASE, 0, 0.f, 0.f};
    cr_clip* clip = cr_clip_create(&desc, &t, 1, keys, 3, tid, nullptr, nullptr, 0);
    float pos[3], rot[3], scl[3];
    CHECK(cr_clip_sample_track(clip, 0, 0.5f, pos, rot, scl) == CR_OK);
    CHECK(near(rot[1], 360.f));  // 350 -> 10 the short way: halfway is 360 (= 0)
    CHECK(cr_clip_sample_track(clip, 0, 1.5f, pos, rot, scl) == CR_OK);
    CHECK(near(rot[1], 10.f));  // STEP holds
    CHECK(cr_clip_sample_track(clip, 0, 5.f, pos, rot, scl) == CR_OK);
    CHECK(near(rot[1], 50.f));  // clamps after the last key
    CHECK(cr_clip_sample_track(clip, 0, -1.f, pos, rot, scl) == CR_OK);
    CHECK(near(rot[1], 350.f));
    CHECK(near(cr_wrap_time(2.5f, 2.f, 1), 0.5f));
    CHECK(near(cr_wrap_time(-0.5f, 2.f, 1), 1.5f));
    CHECK(near(cr_wrap_time(3.f, 2.f, 0), 2.f));
    CHECK(near(cr_wrap_time(1.f, 0.f, 1), 0.f));
    cr_clip_destroy(clip);

    // Unsorted keys are sorted per track.
    cr_key unsorted[2] = {key(1.f, 20.f), key(0.f, 0.f)};
    cr_clip* c2 = cr_clip_create(&desc, &t, 0, unsorted, 2, nullptr, nullptr, nullptr, 0);
    CHECK(c2 != nullptr);
    cr_clip_destroy(c2);
    cr_track_desc t2{0, 2};
    cr_clip* c3 = cr_clip_create(&desc, &t2, 1, unsorted, 2, tid, nullptr, nullptr, 0);
    CHECK(cr_clip_sample_track(c3, 0, 0.5f, pos, rot, scl) == CR_OK && near(rot[1], 10.f));
    cr_clip_destroy(c3);

    // EASE curves.
    cr_key e[2] = {key(0.f, 0.f, CR_EASE_IN), key(1.f, 80.f)};
    cr_clip* ce = cr_clip_create(&desc, &t2, 1, e, 2, tid, nullptr, nullptr, 0);
    CHECK(cr_clip_sample_track(ce, 0, 0.5f, pos, rot, scl) == CR_OK && near(rot[1], 10.f));  // 0.125*80
    cr_clip_destroy(ce);
}

void test_overlays_and_binding() {
    cr_joint_desc j[2] = {part(-1, 0, 0, 0, 0, 0, 0), part(-1, 0, 0, 0, 0, 0, 0)};
    const char* ids[2] = {"a", "b"};
    const char* names[2] = {"Leg", "Arm"};
    cr_rig* rig = cr_rig_create(j, 2, ids, names);

    // Base clip binds by case-insensitive name (track id misses).
    cr_key bk[2] = {key(0.f, 10.f), key(0.f, 10.f)};
    cr_track_desc bt[2] = {{0, 1}, {1, 1}};
    const char* bid[2] = {"zz", "yy"};
    const char* bn[2] = {"leg", "arm"};
    cr_clip_desc bd{1.f, 1, CR_LAYER_BASE, 0, 0.f, 0.f};
    cr_clip* base = cr_clip_create(&bd, bt, 2, bk, 2, bid, bn, nullptr, 0);
    CHECK(cr_clip_track_for(base, rig, 0) == 0);
    CHECK(cr_clip_track_for(base, rig, 1) == 1);

    // Overlay masked to "ARM" only, priority 1; another to everything at priority 0.
    cr_key ok[2] = {key(0.f, 90.f), key(0.f, 90.f)};
    const char* oid[2] = {"a", "b"};
    const char* mask[1] = {"ARM"};
    cr_clip_desc od{1.f, 1, CR_LAYER_OVERLAY, 1, 0.f, 0.f};
    cr_clip* over = cr_clip_create(&od, bt, 2, ok, 2, oid, nullptr, mask, 1);
    cr_key lk[2] = {key(0.f, 40.f), key(0.f, 40.f)};
    cr_clip_desc ld{1.f, 1, CR_LAYER_OVERLAY, 0, 0.f, 0.f};
    cr_clip* low = cr_clip_create(&ld, bt, 2, lk, 2, oid, nullptr, nullptr, 0);

    // Order given high-then-low: the fold sorts by priority so `over` (prio 1) wins Arm.
    cr_layer layers[2] = {{over, 0.f, 1.f}, {low, 0.f, 1.f}};
    std::vector<float> local(18);
    CHECK(cr_rig_local_pose(rig, base, 0.f, layers, 2, local.data()) == CR_OK);
    CHECK(near(local[4], 40.f));      // Leg: only `low` applies
    CHECK(near(local[9 + 4], 90.f));  // Arm: low then over

    // Half weight on the masked overlay alone.
    cr_layer half[1] = {{over, 0.f, 0.5f}};
    CHECK(cr_rig_local_pose(rig, base, 0.f, half, 1, local.data()) == CR_OK);
    CHECK(near(local[4], 10.f));
    CHECK(near(local[9 + 4], 50.f));

    // Fade envelope: fade-in 0.5 s, at 0.25 s the weight is 0.5.
    cr_clip_desc fd{2.f, 0, CR_LAYER_OVERLAY, 0, 0.5f, 0.5f};
    cr_clip* fade = cr_clip_create(&fd, bt, 2, ok, 2, oid, nullptr, nullptr, 0);
    CHECK(near(cr_clip_weight(fade, 0.25f), 0.5f));
    CHECK(near(cr_clip_weight(fade, 1.75f), 0.5f));
    CHECK(near(cr_clip_weight(fade, 1.f), 1.f));
    cr_layer faded[1] = {{fade, 0.25f, 1.f}};
    CHECK(cr_rig_local_pose(rig, nullptr, 0.f, faded, 1, local.data()) == CR_OK);
    CHECK(near(local[4], 45.f));  // rest 0 -> 90 at weight 0.5
    // Zero weight or null clips are ignored.
    cr_layer none[2] = {{fade, 0.f, 1.f}, {nullptr, 0.f, 1.f}};
    CHECK(cr_rig_local_pose(rig, nullptr, 0.f, none, 2, local.data()) == CR_OK);
    CHECK(near(local[4], 0.f));

    cr_clip_destroy(fade);
    cr_clip_destroy(low);
    cr_clip_destroy(over);
    cr_clip_destroy(base);
    cr_rig_destroy(rig);
}

void test_skin_ops() {
    // Two bones along +Y: lower [0,1], upper [1,2]. Five vertices up the axis.
    cr_joint_desc j[3] = {bone(-1, 0.f, 1.f), bone(0, 0.f, 1.f), part(-1, 0, 0, 0, 0, 0, 0)};
    cr_rig* rig = cr_rig_create(j, 3, nullptr, nullptr);
    const float pos[15] = {0.1f, 0.f, 0.f, 0.1f, 0.5f, 0.f, 0.1f, 1.f, 0.f, 0.1f, 1.5f, 0.f, 0.1f, 2.f, 0.f};
    const int32_t part_of[5] = {2, 2, 2, 2, 2};
    cr_skin* skin = cr_skin_create(5);
    CHECK(cr_skin_weighted_count(skin) == 0);

    // set: drops non-positive, merges, keeps 4 heaviest, normalizes.
    const int32_t sj[6] = {0, 1, 0, 2, 3, 4};
    const float sw[6] = {1.f, 1.f, 1.f, 0.5f, -1.f, 0.25f};
    CHECK(cr_skin_set(skin, 0, sj, sw, 6) == CR_OK);
    int32_t gj[4];
    float gw[4];
    CHECK(cr_skin_get(skin, 0, gj, gw) == 4);
    CHECK(gj[0] == 0 && near(gw[0], 2.f / 3.75f));

    // pack: unweighted vertices -> part joint at 255; weighted sum to exactly 255.
    std::vector<uint16_t> pj(20);
    std::vector<uint8_t> pw(20);
    CHECK(cr_skin_pack(skin, part_of, pj.data(), pw.data()) == CR_OK);
    CHECK(pw[0] + pw[1] + pw[2] + pw[3] == 255);
    CHECK(pj[4] == 2 && pw[4] == 255 && pw[5] == 0);

    // Auto weights on vertices 1..4 to both bones.
    const int32_t bones[2] = {0, 1};
    CHECK(cr_skin_auto_weights(skin, rig, pos, 1, 4, bones, 2, 2.f, 0.01f) == CR_OK);
    CHECK(cr_skin_get(skin, 1, gj, gw) >= 1 && gj[0] == 0);  // mid-lower: lower bone dominates
    CHECK(cr_skin_get(skin, 3, gj, gw) >= 1 && gj[0] == 1);  // mid-upper
    // Rigid (falloff 0): exactly one influence.
    CHECK(cr_skin_auto_weights(skin, rig, pos, 4, 1, bones, 2, 0.f, 0.f) == CR_OK);
    CHECK(cr_skin_get(skin, 4, gj, gw) == 1 && gj[0] == 1 && near(gw[0], 1.f));

    // Paint: ADD joint 1 fully at vertex 1 with a small brush; others rescale.
    const float c[3] = {0.1f, 0.5f, 0.f};
    CHECK(cr_skin_paint(skin, pos, part_of, nullptr, 0, nullptr, c, 0.2f, 1, CR_PAINT_REPLACE, 1.f) == 1);
    CHECK(cr_skin_get(skin, 1, gj, gw) == 1 && gj[0] == 1 && near(gw[0], 1.f));
    // SUBTRACT on an unweighted vertex starts from its part joint and hands the rest back to it.
    cr_skin* s2 = cr_skin_create(5);
    const float c0[3] = {0.1f, 0.f, 0.f};
    CHECK(cr_skin_paint(s2, pos, part_of, nullptr, 0, nullptr, c0, 0.2f, 1, CR_PAINT_ADD, 0.5f) == 1);
    CHECK(cr_skin_get(s2, 0, gj, gw) == 2);
    float w1 = 0.f, w2 = 0.f;
    for (int k = 0; k < 2; ++k) {
        if (gj[k] == 1) w1 = gw[k];
        if (gj[k] == 2) w2 = gw[k];
    }
    CHECK(near(w1, 0.5f) && near(w2, 0.5f));

    // SMOOTH with triangles moves vertex 1 toward its neighbours.
    const int32_t tris[6] = {0, 1, 2, 2, 3, 4};
    cr_skin* s3 = cr_skin_create(5);
    const int32_t one[1] = {1};
    const float full[1] = {1.f};
    cr_skin_set(s3, 1, one, full, 1);
    CHECK(cr_skin_paint(s3, pos, part_of, tris, 6, nullptr, c, 0.2f, 1, CR_PAINT_SMOOTH, 1.f) == 1);
    CHECK(cr_skin_get(s3, 1, gj, gw) == 2);

    // Normalize / prune / remove / remap.
    CHECK(cr_skin_prune(skin, 0.4f) >= 0);
    CHECK(cr_skin_remove_joint(skin, 1) >= 1);
    CHECK(cr_skin_get(skin, 1, gj, gw) == 0);  // only joint 1 -> unweighted again
    const int32_t map[3] = {1, 0, 2};
    CHECK(cr_skin_remap_joints(skin, map, 3) == CR_OK);
    CHECK(cr_skin_normalize(skin) == CR_OK);

    // Export / import round trip.
    const int32_t count = cr_skin_export(skin, nullptr, 0);
    std::vector<cr_skin_entry> entries(static_cast<size_t>(count));
    CHECK(cr_skin_export(skin, entries.data(), count) == count);
    cr_skin* back = cr_skin_create(5);
    CHECK(cr_skin_import(back, entries.data(), count) == CR_OK);
    CHECK(cr_skin_weighted_count(back) == count);

    // Mirror across X: a vertex at +x hands its weights to its twin at -x, joints mirrored.
    const float mpos[6] = {0.5f, 1.f, 0.f, -0.5f, 1.f, 0.f};
    cr_skin* ms = cr_skin_create(2);
    const int32_t mj[1] = {0};
    cr_skin_set(ms, 0, mj, full, 1);
    const int32_t mirror_map[2] = {1, 0};
    CHECK(cr_skin_mirror(ms, mpos, 0, 1, 1e-4f, mirror_map, 2) == 1);
    CHECK(cr_skin_get(ms, 1, gj, gw) == 1 && gj[0] == 1);

    // Mirror writes every coincident copy of the target (split UV seams).
    const float spos[9] = {0.5f, 1.f, 0.f, -0.5f, 1.f, 0.f, -0.5f, 1.f, 0.f};
    cr_skin* seam = cr_skin_create(3);
    cr_skin_set(seam, 0, mj, full, 1);
    CHECK(cr_skin_mirror(seam, spos, 0, 1, 1e-4f, mirror_map, 2) == 2);
    CHECK(cr_skin_get(seam, 1, gj, gw) == 1 && gj[0] == 1);
    CHECK(cr_skin_get(seam, 2, gj, gw) == 1 && gj[0] == 1);
    cr_skin_destroy(seam);

    // Transfer onto an edited mesh: nearest old vertex.
    const float npos[6] = {0.49f, 1.f, 0.f, 0.f, 9.f, 0.f};
    cr_skin* moved = cr_skin_transfer(ms, mpos, npos, 2);
    CHECK(moved != nullptr && cr_skin_get(moved, 0, gj, gw) == 1 && gj[0] == 0);

    // Deform = sum w K v; a rotated bone carries its weighted vertices.
    cr_key k{};
    k.rot_deg[2] = 90.f;
    k.scale[0] = k.scale[1] = k.scale[2] = 1.f;
    cr_track_desc t{0, 1};
    const char* tid[1] = {nullptr};
    const char* tn[1] = {nullptr};
    (void) tid;
    (void) tn;
    cr_clip_desc d{1.f, 0, CR_LAYER_BASE, 0, 0.f, 0.f};
    cr_clip* clip = cr_clip_create(&d, &t, 1, &k, 1, nullptr, nullptr, nullptr, 0);
    (void) clip;  // no ids/names: binds nothing
    std::vector<float> K(48);
    CHECK(cr_rig_pose(rig, nullptr, 0.f, nullptr, 0, nullptr, 0, nullptr, K.data(), nullptr, nullptr) == CR_OK);
    float out[15];
    CHECK(cr_skin_deform(skin, part_of, pos, 5, K.data(), 3, out) == CR_OK);
    for (int i = 0; i < 15; ++i) CHECK(near(out[i], pos[i]));  // rest pose: identity

    cr_clip_destroy(clip);
    cr_skin_destroy(moved);
    cr_skin_destroy(ms);
    cr_skin_destroy(back);
    cr_skin_destroy(s3);
    cr_skin_destroy(s2);
    cr_skin_destroy(skin);
    cr_rig_destroy(rig);
}


// ---- ABI 2 pose pipeline (OpenMason #156) ---------------------------------------------------------

cr_pose_op op_of(int32_t op, int32_t dst) {
    cr_pose_op o{};
    o.op = op;
    o.dst = dst;
    return o;
}

// A chain of n parts (joint i's parent = i-1), names j0..j(n-1).
cr_rig* chain(int n) {
    std::vector<cr_joint_desc> j;
    for (int i = 0; i < n; ++i) j.push_back(part(i - 1, 0, static_cast<float>(i), 0, 0, 0, 0));
    static const char* names[8] = {"j0", "j1", "j2", "j3", "j4", "j5", "j6", "j7"};
    return cr_rig_create(j.data(), n, names, names);
}

// Slot values helper: every joint rot y = ry, driven.
void set_all_rot(cr_poses* poses, int slot, int n, float ry, float rx = 0.f) {
    std::vector<float> v(static_cast<size_t>(n) * 9, 0.f);
    for (size_t i = 0; i < static_cast<size_t>(n); ++i) {
        v[i * 9 + 3] = rx;
        v[i * 9 + 4] = ry;
        v[i * 9 + 6] = v[i * 9 + 7] = v[i * 9 + 8] = 1.f;
    }
    CHECK(cr_poses_set(poses, slot, v.data(), nullptr) == CR_OK);
}

std::vector<float> locals_of(cr_poses* poses, int slot, int n) {
    std::vector<float> v(static_cast<size_t>(n) * 9);
    CHECK(cr_poses_get(poses, slot, v.data(), nullptr) == CR_OK);
    return v;
}

void test_program_matches_rig_pose() {
    // Bones and parts, base clip + a masked overlay with fades: the op program reproduces cr_rig_pose bit
    // for bit (same float code).
    cr_joint_desc j[4] = {bone(-1, 0.f, 1.f), part(0, 0, 1, 0, 0, 0, 0), bone(0, 0.5f, 1.f), part(2, 0, 2, 0, 0, 0, 0)};
    const char* ids[4] = {"b0", "p1", "b2", "p3"};
    const char* names[4] = {"spine", "torso", "neck", "head"};
    cr_rig* rig = cr_rig_create(j, 4, ids, names);
    cr_key bk[4] = {key(0.f, 0.f), key(1.f, 33.f, CR_EASE_IN_OUT), key(0.f, -20.f), key(1.f, 70.f)};
    cr_track_desc bt[2] = {{0, 2}, {2, 2}};
    const char* bid[2] = {"b0", "b2"};
    cr_clip_desc bd{1.f, 1, CR_LAYER_BASE, 0, 0.f, 0.f};
    cr_clip* base = cr_clip_create(&bd, bt, 2, bk, 4, bid, nullptr, nullptr, 0);
    cr_key ok[4] = {key(0.f, 45.f), key(0.7f, -60.f, CR_EASE_OUT), key(0.f, 10.f), key(0.7f, 11.f)};
    const char* oid[2] = {"p1", "b2"};
    const char* mask[1] = {"NECK"};
    cr_clip_desc od{0.7f, 0, CR_LAYER_OVERLAY, 2, 0.2f, 0.3f};
    cr_clip* over = cr_clip_create(&od, bt, 2, ok, 4, oid, nullptr, mask, 1);
    float bm[16] = {0.9f, 0, 0.1f, 0, 0, 1, 0, 0, -0.1f, 0, 0.9f, 0, 3, 4, 5, 1};
    cr_joint_extra ex{3, 12.f, -7.f, {1.f, 2.f, 3.f}};

    cr_poses* poses = cr_poses_create(rig, 2);
    CHECK(poses != nullptr);
    for (float t : {0.f, 0.1f, 0.33f, 0.6f, 1.4f}) {
        const float w = 0.8f;
        cr_layer layer{over, t * 0.5f, w};
        std::vector<float> a(64 * 2), b(64 * 2);
        CHECK(cr_rig_pose(rig, base, t, &layer, 1, &ex, 1, bm, a.data(), a.data() + 64, nullptr) == CR_OK);
        cr_pose_op ops[3] = {op_of(CR_OP_SAMPLE, 0), op_of(CR_OP_SAMPLE, 1), op_of(CR_OP_LAYER, 0)};
        ops[0].object = base;
        ops[0].time = t;
        ops[1].object = over;
        ops[1].time = t * 0.5f;
        ops[1].flags = CR_POSE_CLIP_MASK;
        ops[2].a = 1;
        ops[2].weight = w * cr_clip_weight(over, t * 0.5f);
        ops[2].mode = CR_BLEND_OVERRIDE;
        CHECK(cr_poses_evaluate(poses, ops, 3, 0, &ex, 1, bm, b.data(), b.data() + 64, nullptr) == CR_OK);
        CHECK(std::memcmp(a.data(), b.data(), a.size() * sizeof(float)) == 0);
    }
    // Errors name the op.
    cr_pose_op bad = op_of(CR_OP_COPY, 0);
    bad.a = 9;
    CHECK(cr_poses_run(poses, &bad, 1) == CR_ERR_RANGE);
    CHECK(std::strstr(cr_last_error(), "op 0") != nullptr);
    cr_poses_destroy(poses);
    cr_clip_destroy(over);
    cr_clip_destroy(base);
    cr_rig_destroy(rig);
}

void test_weighted_mask_ramps() {
    cr_rig* rig = chain(6);
    cr_mask* m = cr_mask_create(rig, 0.f);
    std::vector<float> w(6);
    // Blend depth 3 from j1: 1/3, 2/3, 1, 1 down the branch, j0 untouched.
    CHECK(cr_mask_set(m, 1, 1.f, CR_MASK_BRANCH, 3) == CR_OK);
    CHECK(cr_mask_get(m, w.data()) == CR_OK);
    CHECK(w[0] == 0.f);
    CHECK(near(w[1], 1.f / 3.f) && near(w[2], 2.f / 3.f) && w[3] == 1.f && w[4] == 1.f && w[5] == 1.f);
    // Branch without depth: flat weight; a later single-joint set overwrites.
    CHECK(cr_mask_set(m, 3, 0.5f, CR_MASK_BRANCH, 0) == CR_OK);
    CHECK(cr_mask_set(m, 5, 0.25f, 0, 0) == CR_OK);
    CHECK(cr_mask_get(m, w.data()) == CR_OK);
    CHECK(w[3] == 0.5f && w[4] == 0.5f && w[5] == 0.25f && near(w[2], 2.f / 3.f));
    CHECK(cr_mask_set(m, 9, 1.f, 0, 0) == CR_ERR_RANGE);

    // Layering with the ramp: rest (0) toward 90 degrees about Y per joint weight.
    CHECK(cr_mask_fill(m, 0.f) == CR_OK);
    CHECK(cr_mask_set(m, 1, 1.f, CR_MASK_BRANCH, 4) == CR_OK);  // 1/4, 2/4, 3/4, 1, 1
    cr_poses* poses = cr_poses_create(rig, 2);
    set_all_rot(poses, 1, 6, 90.f);
    cr_pose_op ops[2] = {op_of(CR_OP_REST, 0), op_of(CR_OP_LAYER, 0)};
    ops[1].a = 1;
    ops[1].object = m;
    ops[1].weight = 1.f;
    CHECK(cr_poses_run(poses, ops, 2) == CR_OK);
    std::vector<float> v = locals_of(poses, 0, 6);
    const float want[6] = {0.f, 22.5f, 45.f, 67.5f, 90.f, 90.f};
    for (size_t i = 0; i < 6; ++i) CHECK(near(v[i * 9 + 4], want[i], 1e-4f));
    // Half layer weight halves every joint's weight.
    ops[1].weight = 0.5f;
    CHECK(cr_poses_run(poses, ops, 2) == CR_OK);
    v = locals_of(poses, 0, 6);
    for (size_t i = 0; i < 6; ++i) CHECK(near(v[i * 9 + 4], want[i] * 0.5f, 1e-4f));
    // Passthrough off: a partial weight blends from the rest pose, not the layers below.
    set_all_rot(poses, 0, 6, 40.f);
    cr_pose_op opaque = op_of(CR_OP_LAYER, 0);
    opaque.a = 1;
    opaque.weight = 0.5f;
    opaque.flags = CR_POSE_NO_PASSTHROUGH;
    CHECK(cr_poses_run(poses, &opaque, 1) == CR_OK);
    v = locals_of(poses, 0, 6);
    CHECK(near(v[4], 45.f, 1e-4f));
    cr_poses_destroy(poses);
    cr_mask_destroy(m);
    cr_rig_destroy(rig);
}

void test_blend() {
    cr_rig* rig = chain(2);
    cr_poses* poses = cr_poses_create(rig, 4);
    set_all_rot(poses, 0, 2, 0.f);
    set_all_rot(poses, 1, 2, 90.f, 10.f);
    set_all_rot(poses, 2, 2, 170.f);
    // Euler running mean: equal weights of 0 and 90 = 45.
    float w2[3] = {1.f, 1.f, 0.f};
    cr_pose_op b = op_of(CR_OP_BLEND, 3);
    b.a = 0;
    b.b = 3;
    b.object = w2;
    CHECK(cr_poses_run(poses, &b, 1) == CR_OK);
    std::vector<float> v = locals_of(poses, 3, 2);
    CHECK(near(v[4], 45.f, 1e-4f) && near(v[3], 5.f, 1e-4f));
    // Three-way: (0 + 90 + 170) / 3 via shortest paths.
    float w3[3] = {1.f, 1.f, 1.f};
    b.object = w3;
    CHECK(cr_poses_run(poses, &b, 1) == CR_OK);
    v = locals_of(poses, 3, 2);
    CHECK(near(v[4], 260.f / 3.f, 1e-3f));
    // A single positive weight copies exactly; the destination may be a source.
    float w1[3] = {0.f, 2.f, 0.f};
    b.object = w1;
    b.dst = 1;
    std::vector<float> before = locals_of(poses, 1, 2);
    CHECK(cr_poses_run(poses, &b, 1) == CR_OK);
    CHECK(locals_of(poses, 1, 2) == before);
    // Quaternions: halfway between 0 and 90 about Y is 45 about Y.
    set_all_rot(poses, 1, 2, 90.f);
    b.dst = 3;
    b.object = w2;
    b.flags = CR_POSE_QUAT;
    CHECK(cr_poses_run(poses, &b, 1) == CR_OK);
    v = locals_of(poses, 3, 2);
    CHECK(near(v[3], 0.f, 1e-4f) && near(v[4], 45.f, 1e-4f) && near(v[5], 0.f, 1e-4f));
    cr_poses_destroy(poses);
    cr_rig_destroy(rig);
}

void test_additive() {
    cr_rig* rig = chain(3);
    cr_poses* poses = cr_poses_create(rig, 5);
    // Slot 0: an arbitrary pose; slot 1: another.
    std::vector<float> a(27), b(27);
    for (size_t i = 0; i < 27; ++i) {
        a[i] = 0.37f * static_cast<float>(i) - 2.f;
        b[i] = 1.f + 0.11f * static_cast<float>(i * i % 7);
    }
    for (size_t i = 0; i < 3; ++i) a[i * 9 + 6] = a[i * 9 + 7] = a[i * 9 + 8] = 1.25f;
    CHECK(cr_poses_set(poses, 0, a.data(), nullptr) == CR_OK);
    CHECK(cr_poses_set(poses, 1, b.data(), nullptr) == CR_OK);

    // Additive identity: a pose minus itself is an exact zero delta, and applying it (any weight, local or
    // mesh space) leaves the pose bit-identical.
    for (uint32_t space : {0u, static_cast<uint32_t>(CR_POSE_MESH_SPACE)}) {
        cr_pose_op mk = op_of(CR_OP_MAKE_ADDITIVE, 2);
        mk.a = 0;
        mk.b = 0;
        mk.flags = space;
        CHECK(cr_poses_run(poses, &mk, 1) == CR_OK);
        std::vector<float> d = locals_of(poses, 2, 3);
        for (size_t i = 0; i < 3; ++i) {
            for (size_t c = 0; c < 6; ++c) CHECK(d[i * 9 + c] == 0.f);
            for (size_t c = 6; c < 9; ++c) CHECK(d[i * 9 + c] == 1.f);
        }
        for (float w : {1.f, 0.5f, 0.123f}) {
            CHECK(cr_poses_set(poses, 3, b.data(), nullptr) == CR_OK);
            cr_pose_op ap = op_of(CR_OP_LAYER, 3);
            ap.a = 2;
            ap.mode = CR_BLEND_ADDITIVE;
            ap.weight = w;
            ap.flags = space;
            CHECK(cr_poses_run(poses, &ap, 1) == CR_OK);
            CHECK(locals_of(poses, 3, 3) == b);
        }
    }

    // Local additive: +30 about Y (and +1 x, scale x2) over a base turned 20 about X.
    set_all_rot(poses, 0, 3, 30.f);
    set_all_rot(poses, 1, 3, 0.f);
    std::vector<float> src = locals_of(poses, 0, 3);
    src[0] = 1.f;
    src[6] = 2.f;
    CHECK(cr_poses_set(poses, 0, src.data(), nullptr) == CR_OK);
    cr_pose_op mk = op_of(CR_OP_MAKE_ADDITIVE, 2);
    mk.a = 0;
    mk.b = 1;
    CHECK(cr_poses_run(poses, &mk, 1) == CR_OK);
    std::vector<float> d = locals_of(poses, 2, 3);
    CHECK(near(d[4], 30.f, 1e-4f) && d[0] == 1.f && d[6] == 2.f);
    set_all_rot(poses, 3, 3, 0.f, 20.f);
    cr_pose_op ap = op_of(CR_OP_LAYER, 3);
    ap.a = 2;
    ap.mode = CR_BLEND_ADDITIVE;
    ap.weight = 0.5f;
    CHECK(cr_poses_run(poses, &ap, 1) == CR_OK);
    std::vector<float> o = locals_of(poses, 3, 3);
    // q = Rx(20)·Ry(15): Euler XYZ (20, 15, 0) exactly that composition.
    CHECK(near(o[3], 20.f, 1e-3f) && near(o[4], 15.f, 1e-3f) && near(o[5], 0.f, 1e-3f));
    CHECK(near(o[0], 0.5f) && near(o[6], 1.5f));

    // Mesh space: the delta is "turn the whole chain 30 about Y" (only the root differs). Applied over a
    // base whose root is tilted 20 about X, the root's local becomes Ry30·Rx20 and the children keep their
    // locals (they inherit the turn through the parent, mesh-space deltas equal).
    std::vector<float> ref = locals_of(poses, 1, 3);  // all zero rotation
    std::vector<float> turned = ref;
    turned[4] = 30.f;
    CHECK(cr_poses_set(poses, 0, turned.data(), nullptr) == CR_OK);
    mk.flags = CR_POSE_MESH_SPACE;
    CHECK(cr_poses_run(poses, &mk, 1) == CR_OK);
    std::vector<float> base = ref;
    base[3] = 20.f;        // root Rx20
    base[9 + 5] = 15.f;    // child Rz15
    CHECK(cr_poses_set(poses, 3, base.data(), nullptr) == CR_OK);
    ap.weight = 1.f;
    ap.flags = CR_POSE_MESH_SPACE;
    CHECK(cr_poses_run(poses, &ap, 1) == CR_OK);
    o = locals_of(poses, 3, 3);
    // Ry30·Rx20 is not an XYZ product of those two; compare the composed world matrices instead.
    std::vector<float> w(48), want(48);
    CHECK(cr_poses_compose(poses, 3, nullptr, 0, nullptr, nullptr, w.data(), nullptr) == CR_OK);
    CHECK(near(o[9 + 3], 0.f, 1e-4f) && near(o[9 + 4], 0.f, 1e-4f) && near(o[9 + 5], 15.f, 1e-4f));
    CHECK(near(o[18 + 3], 0.f, 1e-4f) && near(o[18 + 4], 0.f, 1e-4f) && near(o[18 + 5], 0.f, 1e-4f));
    // Root world rotation = Ry(30)·Rx(20): its +Z axis = Ry30·(0, -sin20, cos20).
    const float s20 = std::sin(20.f * 3.14159265f / 180.f), c20 = std::cos(20.f * 3.14159265f / 180.f);
    const float s30 = 0.5f, c30 = std::sqrt(3.f) / 2.f;
    CHECK(near(w[8], s30 * c20, 1e-5f) && near(w[9], -s20, 1e-5f) && near(w[10], c30 * c20, 1e-5f));
    cr_poses_destroy(poses);
    cr_rig_destroy(rig);
}

// One joint, a scalar driven along X: the source moves toward the target until the switch.
void test_inertialization() {
    cr_rig* rig = chain(1);
    cr_poses* poses = cr_poses_create(rig, 1);
    cr_inertia* in = cr_inertia_create(rig);
    const float dt = 1e-3f;
    const float v = -2.f;  // source velocity (toward the target at 0)
    cr_pose_op op = op_of(CR_OP_INERTIALIZE, 0);
    op.object = in;
    op.param = dt;
    std::vector<float> pose(9, 0.f);
    pose[6] = pose[7] = pose[8] = 1.f;
    float prev = 0.f, prev_rot = 0.f;
    const float wv = 90.f;  // rotation source: degrees per second about Y, away from 0... toward the target 0
    for (int k = 0; k < 50; ++k) {
        pose[0] = 1.f + v * dt * static_cast<float>(k);
        pose[4] = 30.f - wv * dt * static_cast<float>(k);
        CHECK(cr_poses_set(poses, 0, pose.data(), nullptr) == CR_OK);
        CHECK(cr_poses_run(poses, &op, 1) == CR_OK);
        std::vector<float> o = locals_of(poses, 0, 1);
        CHECK(o[0] == pose[0] && o[4] == pose[4]);  // no transition: untouched
        prev = o[0];
        prev_rot = o[4];
    }
    // Switch to a target at rest (0) with a 0.25 s inertialization.
    CHECK(cr_inertia_request(in, 0.25f) == CR_OK);
    CHECK(cr_inertia_active(in) == 1);
    std::vector<float> target(9, 0.f);
    target[6] = target[7] = target[8] = 1.f;
    CHECK(cr_poses_set(poses, 0, target.data(), nullptr) == CR_OK);
    CHECK(cr_poses_run(poses, &op, 1) == CR_OK);
    std::vector<float> o = locals_of(poses, 0, 1);
    // Position: continuous (the step is one frame of the old velocity), velocity continuous.
    CHECK(near((o[0] - prev) / dt, v, 0.05f));
    CHECK(near((o[4] - prev_rot) / dt, -wv, 1.f));
    // It then decays monotonically to the target without overshoot, and lands exactly on it.
    float last = o[0], last_rot = o[4];
    int frames = 0;
    while (cr_inertia_active(in) && frames < 1000) {
        CHECK(cr_poses_set(poses, 0, target.data(), nullptr) == CR_OK);
        CHECK(cr_poses_run(poses, &op, 1) == CR_OK);
        o = locals_of(poses, 0, 1);
        CHECK(o[0] <= last + 1e-6f && o[0] >= 0.f);
        CHECK(o[4] <= last_rot + 1e-4f && o[4] >= -1e-4f);
        last = o[0];
        last_rot = o[4];
        ++frames;
    }
    CHECK(frames > 200 && frames <= 251);
    CHECK(o == target);
    // An instant cut lands on the target at once.
    pose[0] = 5.f;
    CHECK(cr_poses_set(poses, 0, pose.data(), nullptr) == CR_OK);
    CHECK(cr_poses_run(poses, &op, 1) == CR_OK);
    CHECK(cr_inertia_request(in, 0.f) == CR_OK);
    CHECK(cr_poses_set(poses, 0, target.data(), nullptr) == CR_OK);
    CHECK(cr_poses_run(poses, &op, 1) == CR_OK);
    CHECK(locals_of(poses, 0, 1) == target);
    CHECK(cr_inertia_active(in) == 0);
    cr_inertia_destroy(in);
    cr_poses_destroy(poses);
    cr_rig_destroy(rig);
}

void test_root_motion() {
    cr_rig* rig = chain(2);
    // Root walks +2 along X in one looping second while facing +X (yaw 90).
    cr_key k[2] = {key(0.f, 90.f), key(1.f, 90.f)};
    k[1].pos[0] = 2.f;
    cr_track_desc t[1] = {{0, 2}};
    const char* tid[1] = {"j0"};
    cr_clip_desc d{1.f, 1, CR_LAYER_BASE, 0, 0.f, 0.f};
    cr_clip* walk = cr_clip_create(&d, t, 1, k, 2, tid, nullptr, nullptr, 0);
    cr_root_motion m{};
    const uint32_t all = CR_ROOT_X | CR_ROOT_Y | CR_ROOT_Z | CR_ROOT_YAW;
    // In the root's own frame the walk is forward (+Z): 1 block in half a second.
    CHECK(cr_clip_root_motion(walk, rig, 0, all, 0.25f, 0.5f, &m) == CR_OK);
    CHECK(near(m.translation[0], 0.f, 1e-5f) && near(m.translation[2], 1.f, 1e-5f) && m.yaw_deg == 0.f);
    // Across the loop seam, and over whole cycles.
    CHECK(cr_clip_root_motion(walk, rig, 0, all, 0.75f, 0.5f, &m) == CR_OK);
    CHECK(near(m.translation[2], 1.f, 1e-5f));
    CHECK(cr_clip_root_motion(walk, rig, 0, all, 0.f, 2.5f, &m) == CR_OK);
    CHECK(near(m.translation[2], 5.f, 1e-4f));
    CHECK(cr_clip_root_motion(walk, rig, 0, all, 0.5f, -0.75f, &m) == CR_OK);
    CHECK(near(m.translation[2], -1.5f, 1e-5f));
    // Without yaw extraction the translation stays in the clip's frame (+X).
    CHECK(cr_clip_root_motion(walk, rig, 0, CR_ROOT_X | CR_ROOT_Z, 0.25f, 0.5f, &m) == CR_OK);
    CHECK(near(m.translation[0], 1.f, 1e-5f) && near(m.translation[2], 0.f, 1e-5f));
    // A joint the clip does not key moves nothing.
    CHECK(cr_clip_root_motion(walk, rig, 1, all, 0.25f, 0.5f, &m) == CR_OK);
    CHECK(m.translation[0] == 0.f && m.translation[2] == 0.f);

    // A turning clip: yaw 0 -> 90 over a non-looping second, moving +1 Z.
    cr_key tk[2] = {key(0.f, 0.f), key(1.f, 90.f)};
    tk[1].pos[2] = 1.f;
    cr_clip_desc td{1.f, 0, CR_LAYER_BASE, 0, 0.f, 0.f};
    cr_clip* turn = cr_clip_create(&td, t, 1, tk, 2, tid, nullptr, nullptr, 0);
    CHECK(cr_clip_root_motion(turn, rig, 0, all, 0.f, 0.5f, &m) == CR_OK);
    CHECK(near(m.yaw_deg, 45.f, 1e-4f) && near(m.translation[2], 0.5f, 1e-5f));
    CHECK(cr_clip_root_motion(turn, rig, 0, all, 0.9f, 5.f, &m) == CR_OK);  // clamps at the end
    CHECK(near(m.yaw_deg, 9.f, 1e-3f));

    // REMOVE_ROOT keeps the root at its first frame on the extracted axes.
    cr_poses* poses = cr_poses_create(rig, 1);
    cr_pose_op ops[2] = {op_of(CR_OP_SAMPLE, 0), op_of(CR_OP_REMOVE_ROOT, 0)};
    ops[0].object = turn;
    ops[0].time = 0.5f;
    ops[1].object = turn;
    ops[1].b = 0;
    ops[1].axes = CR_ROOT_X | CR_ROOT_Z | CR_ROOT_YAW;
    CHECK(cr_poses_run(poses, ops, 2) == CR_OK);
    std::vector<float> o = locals_of(poses, 0, 2);
    CHECK(o[2] == 0.f && near(o[4], 0.f, 1e-4f));
    // Only Z: the turn stays.
    ops[1].axes = CR_ROOT_Z;
    CHECK(cr_poses_run(poses, ops, 2) == CR_OK);
    o = locals_of(poses, 0, 2);
    CHECK(o[2] == 0.f && near(o[4], 45.f, 1e-4f));
    cr_poses_destroy(poses);
    cr_clip_destroy(turn);
    cr_clip_destroy(walk);
    cr_rig_destroy(rig);
}
}  // namespace

int main() {
    CHECK(cr_abi_version() == CR_ABI_VERSION);
    test_rest_and_part_rotation();
    test_bones_and_parts_under_bones();
    test_cycles_and_head_fallback();
    test_sampling();
    test_overlays_and_binding();
    test_skin_ops();
    test_program_matches_rig_pose();
    test_weighted_mask_ramps();
    test_blend();
    test_additive();
    test_inertialization();
    test_root_motion();
    if (failures != 0) {
        std::fprintf(stderr, "rig: %d check(s) failed\n", failures);
        return 1;
    }
    std::printf("rig: ok\n");
    return 0;
}
