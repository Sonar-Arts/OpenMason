// morph ctest (OpenMason #171): shape keys. Mesh targets (cr_morphs: CSR build, merge / zero drop, queries, remap,
// from an OMO document), weights (Blender ranges, clamping), CPU apply (positions + renormalized normals) == a manual
// sum == the GPU program emulated over the packed buffers, morph then skin; the pose pipeline's morph channels
// (REST, SAMPLE bound by name, BLEND, override / additive LAYER, masks, NO_PASSTHROUGH, MAKE_ADDITIVE, INERTIALIZE,
// CR_OP_MORPH, DRIVE_ALL, MORPHS_ONLY, CLIP_MASK), clips without morph tracks, rigs without channels (joint results
// bit-identical), configuration freezes; the animation graph (clip morph tracks through a blend, a masked layer,
// drives) and authoring (morph keys, layers, bake). Plain CHECKs.

#include "cenda/anim_author.h"
#include "cenda/anim_clip.h"
#include "cenda/anim_graph.h"
#include "cenda/anim_graph_edit.h"
#include "cenda/model.h"
#include "cenda/rig.h"

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <limits>
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

#define CHECK_NEAR(a, b, tol)                                                                                   \
    do {                                                                                                        \
        const double _a = (a), _b = (b);                                                                        \
        if (!(std::fabs(_a - _b) <= (tol))) {                                                                   \
            std::fprintf(stderr, "%s:%d: CHECK_NEAR failed: %s = %.9g, %s = %.9g\n", __FILE__, __LINE__, #a, _a, \
                         #b, _b);                                                                               \
            ++failures;                                                                                         \
        }                                                                                                       \
    } while (0)

bool same_bits(const float* a, const float* b, size_t n) { return std::memcmp(a, b, n * sizeof(float)) == 0; }

// ---- mesh targets -------------------------------------------------------------------------------------------------

// 6 vertices; targets Smile (range -1..2), Frown (default 0.25, range 0..1), Blink (range -10..10, normal deltas).
//   Smile: v1 (0.1, 0, 0) + v1 again (0.05, 0, 0.01) (merged), v3 (0, 0.2, 0), v5 zero (dropped)
//   Frown: v2 (0, 0, 0.4), v1 (0, -0.3, 0)
//   Blink: v3 (0.5, 0.5, 0) normal (0, 0, 0.3), v0 zero delta with normal (0, 0, 0.8) (kept: the normal)
struct Mesh {
    std::vector<float> pos, nrm;
    cr_morphs* m = nullptr;
};

Mesh make_mesh() {
    Mesh me;
    for (int v = 0; v < 6; ++v) {
        me.pos.insert(me.pos.end(), {0.1f * static_cast<float>(v), 1.f - 0.2f * static_cast<float>(v),
                                     0.3f + 0.05f * static_cast<float>(v)});
        const double l = std::sqrt(1.0 + 0.04 * v * v);
        me.nrm.insert(me.nrm.end(), {static_cast<float>(0.2 * v / l), static_cast<float>(1.0 / l), 0.f});
    }
    const char* names[3] = {"Smile", "Frown", "Blink"};
    const float params[9] = {0.f, -1.f, 2.f, 0.25f, 0.f, 1.f, 0.f, -10.f, 10.f};
    const int32_t offsets[4] = {0, 4, 6, 8};
    const int32_t verts[8] = {1, 3, 1, 5, 2, 1, 3, 0};
    const float deltas[24] = {0.1f, 0.f, 0.f, 0.f, 0.2f, 0.f, 0.05f, 0.f, 0.01f, 0.f, 0.f, 0.f,
                              0.f, 0.f, 0.4f, 0.f, -0.3f, 0.f, 0.5f, 0.5f, 0.f, 0.f, 0.f, 0.f};
    const float normals[24] = {0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f,
                               0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.3f, 0.f, 0.f, 0.8f};
    me.m = cr_morphs_create(6, 3, names, params, offsets, verts, deltas, normals);
    return me;
}

void test_build_and_queries() {
    Mesh me = make_mesh();
    CHECK(me.m != nullptr);
    if (me.m == nullptr) return;
    CHECK(cr_morphs_vertex_count(me.m) == 6 && cr_morphs_target_count(me.m) == 3);
    CHECK(cr_morphs_row_count(me.m) == 6 && cr_morphs_pack_size(me.m) == 6 && cr_morphs_has_normals(me.m) == 1);
    int32_t v[4];
    float d[12], n[12];
    CHECK(cr_morphs_target_rows(me.m, 0, v, d, n, 4) == 2);
    CHECK(v[0] == 1 && v[1] == 3 && d[0] == 0.1f + 0.05f && d[2] == 0.01f && d[4] == 0.2f);
    CHECK(cr_morphs_target_rows(me.m, 1, v, d, n, 4) == 2 && v[0] == 1 && v[1] == 2 && d[1] == -0.3f && d[5] == 0.4f);
    CHECK(cr_morphs_target_rows(me.m, 2, v, d, n, 4) == 2 && v[0] == 0 && v[1] == 3 && n[2] == 0.8f && n[5] == 0.3f);
    CHECK(cr_morphs_target_rows(me.m, 2, nullptr, nullptr, nullptr, 0) == 2);  // a size query
    CHECK(cr_morphs_find(me.m, "Frown") == 1 && cr_morphs_find(me.m, "frown") == 1 && cr_morphs_find(me.m, "morph:BLINK") == 2);
    CHECK(cr_morphs_find(me.m, "nope") == -1 && std::strcmp(cr_morphs_target_name(me.m, 2), "Blink") == 0);
    float p[3];
    CHECK(cr_morphs_target_params(me.m, 1, p) == CR_OK && p[0] == 0.25f && p[1] == 0.f && p[2] == 1.f);
    // Refusals.
    const char* two[2] = {"A", "a"};
    const int32_t off0[3] = {0, 0, 0};
    CHECK(cr_morphs_create(3, 2, two, nullptr, off0, nullptr, nullptr, nullptr) == nullptr);  // duplicate (case)
    const char* one[1] = {"A"};
    const int32_t off1[2] = {0, 1};
    const int32_t outside[1] = {3};
    const float dd[3] = {1.f, 0.f, 0.f};
    CHECK(cr_morphs_create(3, 1, one, nullptr, off1, outside, dd, nullptr) == nullptr);
    const float badp[3] = {0.f, 1.f, 0.f};  // min > max
    CHECK(cr_morphs_create(3, 1, one, badp, off0, nullptr, nullptr, nullptr) == nullptr);
    const float nan3[3] = {std::nanf(""), 0.f, 0.f};
    const int32_t zero[1] = {0};
    CHECK(cr_morphs_create(3, 1, one, nullptr, off1, zero, nan3, nullptr) == nullptr);
    cr_morphs* empty = cr_morphs_create(3, 0, nullptr, nullptr, nullptr, nullptr, nullptr, nullptr);
    CHECK(empty != nullptr && cr_morphs_target_count(empty) == 0 && cr_morphs_pack_size(empty) == 0);
    cr_morphs_destroy(empty);
    cr_morphs_destroy(me.m);
}

// Weights: Blender ranges clamp (the shape key value's hard range); NaN and missing read 0.
void test_weights() {
    Mesh me = make_mesh();
    float out[3];
    const float w[3] = {5.f, -3.f, -12.f};
    CHECK(cr_morphs_clamp_weights(me.m, w, 3, out) == CR_OK && out[0] == 2.f && out[1] == 0.f && out[2] == -10.f);
    const float w2[1] = {std::nanf("")};
    CHECK(cr_morphs_clamp_weights(me.m, w2, 1, out) == CR_OK && out[0] == 0.f && out[1] == 0.f && out[2] == 0.f);
    const float w3[3] = {-0.5f, 0.6f, 7.5f};
    CHECK(cr_morphs_clamp_weights(me.m, w3, 3, out) == CR_OK && out[0] == -0.5f && out[1] == 0.6f && out[2] == 7.5f);
    cr_morphs_destroy(me.m);
}

// The vertex shader of rig.h ("GPU packing") over the packed buffers, in float, the same order.
void gpu_emulate(const cr_morphs* m, const float* weights, const float* pos, const float* nrm, std::vector<float>& op,
                 std::vector<float>& on) {
    const int32_t nv = cr_morphs_vertex_count(m), rec = cr_morphs_pack_size(m), nt = cr_morphs_target_count(m);
    std::vector<uint32_t> ranges(static_cast<size_t>(nv) * 2);
    std::vector<float> deltas(static_cast<size_t>(rec) * 4), normals(static_cast<size_t>(rec) * 4), w(static_cast<size_t>(nt));
    CHECK(cr_morphs_pack(m, rec, ranges.data(), deltas.data(), normals.data()) == CR_OK);
    CHECK(cr_morphs_clamp_weights(m, weights, nt, w.data()) == CR_OK);
    op.assign(pos, pos + nv * 3);
    on.assign(nrm, nrm + nv * 3);
    for (size_t v = 0; v < static_cast<size_t>(nv); ++v) {
        float d[3] = {0, 0, 0}, dn[3] = {0, 0, 0};
        for (uint32_t i = ranges[2 * v]; i < ranges[2 * v] + ranges[2 * v + 1]; ++i) {
            const float wt = w[static_cast<size_t>(deltas[4 * i + 3])];
            for (int c = 0; c < 3; ++c) {
                d[c] = d[c] + wt * deltas[4 * i + static_cast<uint32_t>(c)];
                dn[c] = dn[c] + wt * normals[4 * i + static_cast<uint32_t>(c)];
            }
        }
        if (ranges[2 * v + 1] == 0) continue;
        for (size_t c = 0; c < 3; ++c) op[3 * v + c] = pos[3 * v + c] + d[c];
        if (dn[0] != 0.f || dn[1] != 0.f || dn[2] != 0.f) {
            const float x = nrm[3 * v] + dn[0], y = nrm[3 * v + 1] + dn[1], z = nrm[3 * v + 2] + dn[2];
            const double l = std::sqrt(double(x) * x + double(y) * y + double(z) * z);
            on[3 * v] = float(x / l);
            on[3 * v + 1] = float(y / l);
            on[3 * v + 2] = float(z / l);
        }
    }
}

void test_apply_and_pack() {
    Mesh me = make_mesh();
    const int nv = 6;
    // Pack layout: ranges contiguous in vertex order, records by ascending target, the target an exact float.
    std::vector<uint32_t> ranges(12);
    std::vector<float> deltas(24), normals(24);
    CHECK(cr_morphs_pack(me.m, 5, ranges.data(), deltas.data(), normals.data()) == CR_ERR_ARG);  // too small
    CHECK(cr_morphs_pack(me.m, 6, ranges.data(), deltas.data(), normals.data()) == CR_OK);
    const uint32_t want_ranges[12] = {0, 1, 1, 2, 3, 1, 4, 2, 6, 0, 6, 0};
    CHECK(std::memcmp(ranges.data(), want_ranges, sizeof want_ranges) == 0);
    CHECK(deltas[3] == 2.f && deltas[7] == 0.f && deltas[11] == 1.f && deltas[15] == 1.f && deltas[19] == 0.f && deltas[23] == 2.f);
    CHECK(normals[2] == 0.8f && normals[22] == 0.3f && normals[3] == 0.f && normals[6] == 0.f);

    const std::vector<std::vector<float>> sets = {{1.f, 0.f, 0.f}, {0.5f, 0.7f, -2.5f}, {-1.f, 1.f, 9.f}, {3.f, 2.f, 11.f}};
    for (const auto& w : sets) {
        std::vector<float> op(nv * 3), on(nv * 3), gp, gn;
        CHECK(cr_morph_apply(me.m, w.data(), 3, me.pos.data(), me.nrm.data(), op.data(), on.data()) == CR_OK);
        gpu_emulate(me.m, w.data(), me.pos.data(), me.nrm.data(), gp, gn);
        CHECK(same_bits(op.data(), gp.data(), op.size()));  // CPU == the GPU program, bit for bit (no contraction)
        CHECK(same_bits(on.data(), gn.data(), on.size()));
        // A manual sum over the authored rows (double, clamped weights).
        float cw[3];
        cr_morphs_clamp_weights(me.m, w.data(), 3, cw);
        const double sum[6][3] = {{0, 0, 0},
                                  {0.15 * cw[0], -0.3 * cw[1], 0.01 * cw[0]},
                                  {0, 0, 0.4 * cw[1]},
                                  {0.5 * cw[2], 0.2 * cw[0] + 0.5 * cw[2], 0},
                                  {0, 0, 0},
                                  {0, 0, 0}};
        for (size_t vtx = 0; vtx < 6; ++vtx) {
            for (size_t c = 0; c < 3; ++c) CHECK_NEAR(op[3 * vtx + c], me.pos[3 * vtx + c] + sum[vtx][c], 4e-6);
        }
        // Normals: v0 and v3 turned by Blink (renormalized), the rest untouched bit for bit.
        for (size_t vtx : {size_t{0}, size_t{3}}) {
            const double x = me.nrm[3 * vtx], y = me.nrm[3 * vtx + 1];
            const double z = me.nrm[3 * vtx + 2] + cw[2] * (vtx == 0 ? 0.8 : 0.3);
            const double l = std::sqrt(x * x + y * y + z * z);
            CHECK_NEAR(on[3 * vtx], x / l, 1e-6);
            CHECK_NEAR(on[3 * vtx + 2], z / l, 1e-6);
        }
        for (size_t vtx : {size_t{1}, size_t{2}, size_t{4}, size_t{5}}) CHECK(same_bits(&on[3 * vtx], &me.nrm[3 * vtx], 3));
        for (size_t vtx : {size_t{4}, size_t{5}}) CHECK(same_bits(&op[3 * vtx], &me.pos[3 * vtx], 3));
    }
    // Zero weights: everything copied exactly; in-place apply equals out-of-place.
    std::vector<float> op(nv * 3), on(nv * 3);
    CHECK(cr_morph_apply(me.m, nullptr, 0, me.pos.data(), me.nrm.data(), op.data(), on.data()) == CR_OK);
    CHECK(same_bits(op.data(), me.pos.data(), op.size()) && same_bits(on.data(), me.nrm.data(), on.size()));
    const float w[3] = {0.5f, 0.7f, -2.5f};
    std::vector<float> ip = me.pos, in = me.nrm;
    CHECK(cr_morph_apply(me.m, w, 3, ip.data(), in.data(), ip.data(), in.data()) == CR_OK);
    CHECK(cr_morph_apply(me.m, w, 3, me.pos.data(), me.nrm.data(), op.data(), on.data()) == CR_OK);
    CHECK(same_bits(ip.data(), op.data(), op.size()) && same_bits(in.data(), on.data(), on.size()));
    // Positions only.
    CHECK(cr_morph_apply(me.m, w, 3, me.pos.data(), nullptr, ip.data(), nullptr) == CR_OK && same_bits(ip.data(), op.data(), op.size()));
    cr_morphs_destroy(me.m);
}

void test_remap_and_doc() {
    Mesh me = make_mesh();
    // A loader that split vertex 1 into two (new 1 and new 6) and dropped vertex 4.
    const int32_t src[7] = {0, 1, 2, 3, -1, 5, 1};
    cr_morphs* r = cr_morphs_remap(me.m, 7, src);
    CHECK(r != nullptr && cr_morphs_vertex_count(r) == 7 && cr_morphs_row_count(r) == 6 + 2);
    if (r != nullptr) {
        std::vector<float> pos(21, 0.f), a(21);
        const float w[3] = {1.f, 1.f, 1.f};
        CHECK(cr_morph_apply(r, w, 3, pos.data(), nullptr, a.data(), nullptr) == CR_OK);
        CHECK(same_bits(&a[3], &a[18], 3) && a[3] != 0.f);  // the split copies move alike
        cr_morphs_destroy(r);
    }
    const int32_t bad[1] = {9};
    CHECK(cr_morphs_remap(me.m, 1, bad) == nullptr);
    cr_morphs_destroy(me.m);

    // From an OMO document: part-local rows -> soup vertices, unknown parts and out-of-range rows skipped.
    co_doc* doc = co_doc_create();
    const char* names[2] = {"Smile", "Puff"};
    const float params[6] = {0.f, 0.f, 1.f, 0.5f, -1.f, 1.f};
    const int32_t morph_parts[3] = {0, 2, 3};
    const char* part_ids[3] = {"head", "body", "ghost"};
    const int32_t part_flags[3] = {CO_MORPH_PART_NORMALS, 0, 0};
    const int32_t part_rows[4] = {0, 2, 4, 5};
    const int32_t verts[5] = {0, 2, 1, 7, 0};
    const float deltas[15] = {1, 0, 0, 0, 1, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2};
    const float normals[15] = {0, 0, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    CHECK(co_doc_morphs_set(doc, 2, names, params, morph_parts, part_ids, part_flags, part_rows, verts, deltas, normals) == CO_OK);
    const char* soup_parts[2] = {"body", "head"};
    const int32_t first[2] = {0, 4};
    const int32_t count[2] = {4, 3};
    int32_t skipped = -1;
    cr_morphs* m = cr_morphs_from_doc(doc, 7, 2, soup_parts, first, count, &skipped);
    CHECK(m != nullptr && skipped == 2);  // body local 7 (out of range), the ghost part
    if (m != nullptr) {
        CHECK(cr_morphs_target_count(m) == 2 && cr_morphs_has_normals(m) == 1);
        int32_t v[4];
        float d[12], n[12], p[3];
        // Smile: head 0 -> 4 (normal z), head 2 -> 6 (normal y), body 1 -> 1 (no normal).
        CHECK(cr_morphs_target_rows(m, 0, v, d, n, 4) == 3);
        CHECK(v[0] == 1 && v[1] == 4 && v[2] == 6 && d[2] == 1.f && d[3] == 1.f && n[2] == 0.f && n[5] == 1.f && n[7] == 1.f);
        CHECK(cr_morphs_target_rows(m, 1, v, d, n, 4) == 0);
        CHECK(cr_morphs_target_params(m, 1, p) == CR_OK && p[0] == 0.5f && p[1] == -1.f);
        cr_morphs_destroy(m);
    }
    co_doc_destroy(doc);
}

// ---- a rig ----------------------------------------------------------------------------------------------------------

// Two parts: root and arm (child, pivot at x = 1). Channels Smile, Frown (default 0.25), Blink unless bare.
cr_rig* make_rig(bool channels) {
    cr_joint_desc j[2]{};
    j[0].parent = -1;
    j[1].parent = 0;
    for (auto& d : j) {
        d.kind = CR_JOINT_PART;
        d.scale[0] = d.scale[1] = d.scale[2] = 1.f;
    }
    j[1].origin[0] = 1.f;
    const char* ids[2] = {"root", "arm"};
    cr_rig* rig = cr_rig_create(j, 2, ids, ids);
    if (channels) {
        const char* names[3] = {"Smile", "Frown", "Blink"};
        const float defs[3] = {0.f, 0.25f, 0.f};
        CHECK(cr_rig_set_morph_channels(rig, 3, names, defs) == CR_OK);
    }
    return rig;
}

// A clip turning the arm about z by arm_angle over 1 s, with morph tracks (when given).
cr_clip* make_clip(float arm_angle, const std::vector<std::pair<std::string, std::vector<cr_morph_key>>>& morphs,
                   const char* mask = nullptr) {
    cr_key keys[2]{};
    for (int k = 0; k < 2; ++k) {
        keys[k].time = static_cast<float>(k);
        keys[k].rot_deg[2] = arm_angle * static_cast<float>(k);
        keys[k].scale[0] = keys[k].scale[1] = keys[k].scale[2] = 1.f;
    }
    const cr_track_desc tr{0, 2};
    const char* id = "arm";
    cr_clip_desc d{1.f, 0, CR_LAYER_BASE, 0, 0.f, 0.f};
    cr_clip* c = cr_clip_create(&d, &tr, 1, keys, 2, &id, &id, mask != nullptr ? &mask : nullptr, mask != nullptr ? 1 : 0);
    if (!morphs.empty()) {
        std::vector<const char*> names;
        std::vector<int32_t> offsets{0};
        std::vector<cr_morph_key> all;
        for (const auto& [n, ks] : morphs) {
            names.push_back(n.c_str());
            all.insert(all.end(), ks.begin(), ks.end());
            offsets.push_back(static_cast<int32_t>(all.size()));
        }
        CHECK(cr_clip_set_morph_tracks(c, static_cast<int32_t>(names.size()), names.data(), offsets.data(), all.data()) == CR_OK);
    }
    return c;
}

cr_morph_key key(float t, float v) { return cr_morph_key{t, v, 0.f, 0.f, CR_MORPH_LINEAR}; }

std::vector<float> weights_of(cr_poses* p, int32_t slot, std::vector<uint8_t>* driven = nullptr) {
    std::vector<float> w(3);
    std::vector<uint8_t> d(3);
    CHECK(cr_poses_morph_weights(p, slot, w.data(), d.data(), 3) == 3);
    if (driven != nullptr) *driven = d;
    return w;
}

cr_pose_op op(int32_t kind, int32_t dst, int32_t a = 0, int32_t b = 0) {
    cr_pose_op o{};
    o.op = kind;
    o.dst = dst;
    o.a = a;
    o.b = b;
    return o;
}

void test_channels_and_sampling() {
    cr_rig* rig = make_rig(true);
    CHECK(cr_rig_morph_count(rig) == 3 && cr_rig_morph_find(rig, "morph:frown") == 1 && cr_rig_morph_find(rig, "x") == -1);
    CHECK(std::strcmp(cr_rig_morph_name(rig, 2), "Blink") == 0 && cr_rig_morph_default(rig, 1) == 0.25f);
    const char* dup[2] = {"A", "a"};
    cr_rig* other = make_rig(false);
    CHECK(cr_rig_set_morph_channels(other, 2, dup, nullptr) == CR_ERR_ARG && cr_rig_morph_count(other) == 0);
    cr_rig_destroy(other);
    // a: lower-case "smile" binds case-insensitively; "Nope" names no channel. Smile 0 -> 1, Frown 0.8.
    cr_clip* a = make_clip(90.f, {{"smile", {key(0.f, 0.f), key(1.f, 1.f)}}, {"Frown", {key(0.f, 0.8f)}}, {"Nope", {key(0.f, 5.f)}}});
    cr_clip* plain = make_clip(90.f, {});
    CHECK(cr_clip_morph_track_count(a) == 3 && cr_clip_morph_track_count(plain) == 0);
    CHECK(std::strcmp(cr_clip_morph_track_name(a, 1), "Frown") == 0);
    cr_poses* p = cr_poses_create(rig, 4);
    // Frozen now.
    const char* nm[1] = {"X"};
    CHECK(cr_rig_set_morph_channels(rig, 1, nm, nullptr) == CR_ERR_ARG && cr_rig_morph_count(rig) == 3);
    std::vector<uint8_t> dr;
    // REST = the defaults, nothing driven.
    cr_pose_op rest = op(CR_OP_REST, 0);
    CHECK(cr_poses_run(p, &rest, 1) == CR_OK);
    auto w = weights_of(p, 0, &dr);
    CHECK(w == (std::vector<float>{0.f, 0.25f, 0.f}) && dr == (std::vector<uint8_t>{0, 0, 0}));
    // SAMPLE: tracks bound by name.
    cr_pose_op s = op(CR_OP_SAMPLE, 1);
    s.object = a;
    s.time = 0.25f;
    CHECK(cr_poses_run(p, &s, 1) == CR_OK);
    w = weights_of(p, 1, &dr);
    CHECK(w[0] == 0.25f && w[1] == 0.8f && w[2] == 0.f && dr == (std::vector<uint8_t>{1, 1, 0}));
    CHECK(cr_clip_morph_track_for(a, rig, 0) == 0 && cr_clip_morph_track_for(a, rig, 1) == 1 && cr_clip_morph_track_for(a, rig, 2) == -1);
    CHECK(cr_clip_sample_morph(a, 0, 0.25f) == 0.25f && cr_clip_sample_morph(a, 9, 0.25f) == 0.f);
    // Bound: the tracks are fixed.
    CHECK(cr_clip_set_morph_tracks(a, 0, nullptr, nullptr, nullptr) == CR_ERR_ARG && cr_clip_morph_track_count(a) == 3);
    // A clip without morph tracks leaves the weights at rest.
    s.object = plain;
    CHECK(cr_poses_run(p, &s, 1) == CR_OK);
    w = weights_of(p, 1, &dr);
    CHECK(w == (std::vector<float>{0.f, 0.25f, 0.f}) && dr == (std::vector<uint8_t>{0, 0, 0}));
    // Joints are bit-identical to the same clip on a rig without channels (a clip WITH morph tracks there: joints as
    // ever, no weights).
    cr_rig* bare = make_rig(false);
    cr_poses* pb = cr_poses_create(bare, 1);
    cr_pose_op sb = op(CR_OP_SAMPLE, 0);
    sb.object = a;
    sb.time = 0.6f;
    s.object = a;
    s.time = 0.6f;
    CHECK(cr_poses_run(p, &s, 1) == CR_OK && cr_poses_run(pb, &sb, 1) == CR_OK);
    float la[18], lb[18];
    cr_poses_get(p, 1, la, nullptr);
    cr_poses_get(pb, 0, lb, nullptr);
    CHECK(same_bits(la, lb, 18));
    CHECK(cr_poses_morph_weights(pb, 0, nullptr, nullptr, 0) == 0);
    float ka[32], kb[32];
    CHECK(cr_rig_pose(rig, a, 0.6f, nullptr, 0, nullptr, 0, nullptr, ka, nullptr, nullptr) == CR_OK);
    CHECK(cr_rig_pose(bare, a, 0.6f, nullptr, 0, nullptr, 0, nullptr, kb, nullptr, nullptr) == CR_OK);
    CHECK(same_bits(ka, kb, 32));
    // DRIVE_ALL drives every channel; MORPHS_ONLY leaves the joints alone.
    s.flags = CR_POSE_DRIVE_ALL;
    CHECK(cr_poses_run(p, &s, 1) == CR_OK);
    weights_of(p, 1, &dr);
    CHECK(dr == (std::vector<uint8_t>{1, 1, 1}));
    cr_pose_op mo = op(CR_OP_SAMPLE, 2);
    mo.object = a;
    mo.time = 0.5f;
    mo.flags = CR_POSE_MORPHS_ONLY;
    cr_pose_op prog[2] = {op(CR_OP_REST, 2), mo};
    CHECK(cr_poses_run(p, prog, 2) == CR_OK);
    float lr[18];
    uint8_t jd[2];
    cr_poses_get(p, 2, lr, jd);
    w = weights_of(p, 2, &dr);
    CHECK(lr[9 + 5] == 0.f && jd[1] == 0 && w[0] == 0.5f && dr == (std::vector<uint8_t>{1, 1, 0}));
    // Set / get.
    const float sw[3] = {0.1f, 0.2f, 0.3f};
    CHECK(cr_poses_set_morph_weights(p, 3, sw, nullptr) == CR_OK);
    w = weights_of(p, 3, &dr);
    CHECK(w == (std::vector<float>{0.1f, 0.2f, 0.3f}) && dr == (std::vector<uint8_t>{1, 1, 1}));
    CHECK(cr_poses_morph_weights(p, 9, nullptr, nullptr, 0) == CR_ERR_ARG);
    cr_poses_destroy(pb);
    cr_poses_destroy(p);
    cr_clip_destroy(plain);
    cr_clip_destroy(a);
    cr_rig_destroy(bare);
    cr_rig_destroy(rig);
}

void test_blend_layer_mask() {
    cr_rig* rig = make_rig(true);
    cr_clip* a = make_clip(0.f, {{"Smile", {key(0.f, 1.f)}}, {"Frown", {key(0.f, 0.6f)}}});
    cr_clip* b = make_clip(0.f, {{"Smile", {key(0.f, 0.f)}}, {"Blink", {key(0.f, 1.f)}}});
    cr_poses* p = cr_poses_create(rig, 8);
    enum { A = 0, B = 1, OUT = 2, DELTA = 3, REST = 4, T = 5 };
    cr_pose_op base[3] = {op(CR_OP_SAMPLE, A), op(CR_OP_SAMPLE, B), op(CR_OP_REST, REST)};
    base[0].object = a;
    base[1].object = b;
    CHECK(cr_poses_run(p, base, 3) == CR_OK);
    std::vector<uint8_t> dr;
    // BLEND (Euler, the running lerp): Smile 0.25; Frown a 0.6 / b at its default 0.25; Blink 0.75.
    float bw[2] = {0.25f, 0.75f};
    cr_pose_op bl = op(CR_OP_BLEND, OUT, A, 2);
    bl.object = bw;
    CHECK(cr_poses_run(p, &bl, 1) == CR_OK);
    auto w = weights_of(p, OUT, &dr);
    CHECK(w[0] == 0.25f && w[2] == 0.75f);
    CHECK_NEAR(w[1], 0.25 * 0.6 + 0.75 * 0.25, 1e-7);
    CHECK(dr == (std::vector<uint8_t>{1, 1, 1}));
    bl.flags = CR_POSE_QUAT;  // the normalized weighted sum in double
    CHECK(cr_poses_run(p, &bl, 1) == CR_OK);
    w = weights_of(p, OUT);
    CHECK(w[0] == 0.25f && w[1] == static_cast<float>(0.6f * 0.25 + 0.25f * 0.75));
    // A single positive weight copies exactly.
    float one[2] = {0.f, 2.f};
    bl.object = one;
    bl.flags = 0;
    CHECK(cr_poses_run(p, &bl, 1) == CR_OK);
    CHECK(weights_of(p, OUT) == weights_of(p, B));
    // LAYER override at 0.5 over A, under a mask that excludes Smile.
    cr_mask* mask = cr_mask_create(rig, 1.f);
    CHECK(cr_mask_morph_count(mask) == 3 && cr_mask_set_morph(mask, 0, 0.f) == CR_OK);
    CHECK(cr_mask_set_morph(mask, 3, 1.f) == CR_ERR_RANGE);
    cr_pose_op ly = op(CR_OP_LAYER, OUT, B);
    ly.weight = 0.5f;
    ly.object = mask;
    cr_pose_op prog[2] = {op(CR_OP_COPY, OUT, A), ly};
    CHECK(cr_poses_run(p, prog, 2) == CR_OK);
    w = weights_of(p, OUT, &dr);
    CHECK(w[0] == 1.f);    // masked out: A's value
    CHECK(w[1] == 0.6f);   // b does not drive Frown
    CHECK(w[2] == 0.5f);   // b drives Blink: 0 -> 1 at 0.5
    CHECK(dr == (std::vector<uint8_t>{1, 1, 1}));
    // Weight >= 1: src exactly. NO_PASSTHROUGH: from the default.
    ly.weight = 1.f;
    ly.object = nullptr;
    prog[1] = ly;
    CHECK(cr_poses_run(p, prog, 2) == CR_OK);
    w = weights_of(p, OUT);
    CHECK(w[0] == 0.f && w[1] == 0.6f && w[2] == 1.f);
    ly.weight = 0.5f;
    ly.flags = CR_POSE_NO_PASSTHROUGH;
    prog[1] = ly;
    CHECK(cr_poses_run(p, prog, 2) == CR_OK);
    w = weights_of(p, OUT);
    CHECK(w[0] == 0.f && w[1] == 0.6f && w[2] == 0.5f);  // Smile from its default 0 toward 0, not from A's 1
    // ADDITIVE: delta = A - REST (Smile +1, Frown +0.35), added at 0.5 over B.
    cr_pose_op mk = op(CR_OP_MAKE_ADDITIVE, DELTA, A, REST);
    cr_pose_op la = op(CR_OP_LAYER, OUT, DELTA);
    la.mode = CR_BLEND_ADDITIVE;
    la.weight = 0.5f;
    cr_pose_op prog3[3] = {mk, op(CR_OP_COPY, OUT, B), la};
    CHECK(cr_poses_run(p, prog3, 3) == CR_OK);
    auto d = weights_of(p, DELTA, &dr);
    CHECK(d[0] == 1.f && d[1] == 0.6f - 0.25f && d[2] == 0.f && dr == (std::vector<uint8_t>{1, 1, 0}));
    w = weights_of(p, OUT);
    CHECK(w[0] == 0.5f && w[1] == 0.25f + (0.6f - 0.25f) * 0.5f && w[2] == 1.f);
    // Equal values: an exact zero delta. Mesh-space MAKE_ADDITIVE: the same channel deltas.
    cr_pose_op same = op(CR_OP_MAKE_ADDITIVE, T, A, A);
    CHECK(cr_poses_run(p, &same, 1) == CR_OK && weights_of(p, T) == (std::vector<float>{0.f, 0.f, 0.f}));
    mk.flags = CR_POSE_MESH_SPACE;
    CHECK(cr_poses_run(p, &mk, 1) == CR_OK && weights_of(p, DELTA) == d);
    // A mask from a clip's name mask covers "morph:Blink".
    cr_clip* masked = make_clip(0.f, {{"Blink", {key(0.f, 1.f)}}, {"Smile", {key(0.f, 1.f)}}}, "morph:Blink");
    cr_mask* fm = cr_mask_create(rig, 0.f);
    CHECK(cr_mask_from_clip(fm, masked) == CR_OK);
    float mw[3];
    CHECK(cr_mask_get_morph(fm, mw) == CR_OK && mw[0] == 0.f && mw[1] == 0.f && mw[2] == 1.f);
    // CLIP_MASK sampling: only Blink.
    cr_pose_op sm = op(CR_OP_SAMPLE, T);
    sm.object = masked;
    sm.flags = CR_POSE_CLIP_MASK;
    CHECK(cr_poses_run(p, &sm, 1) == CR_OK);
    w = weights_of(p, T, &dr);
    CHECK(w[0] == 0.f && w[2] == 1.f && dr == (std::vector<uint8_t>{0, 0, 1}));
    // cr_mask_fill fills the channels too; channel -1 = all (clamped to [0, 1]).
    CHECK(cr_mask_fill(fm, 0.3f) == CR_OK && cr_mask_get_morph(fm, mw) == CR_OK && mw[1] == 0.3f);
    CHECK(cr_mask_set_morph(fm, -1, 2.f) == CR_OK && cr_mask_get_morph(fm, mw) == CR_OK && mw[0] == 1.f && mw[2] == 1.f);
    // CR_OP_MORPH: NaN leaves a channel; the weight lerps.
    const float vals[3] = {std::numeric_limits<float>::quiet_NaN(), 1.f, -2.f};
    cr_pose_op mp = op(CR_OP_MORPH, OUT);
    mp.object = vals;
    mp.weight = 0.5f;
    cr_pose_op prog2[2] = {op(CR_OP_REST, OUT), mp};
    CHECK(cr_poses_run(p, prog2, 2) == CR_OK);
    w = weights_of(p, OUT, &dr);
    CHECK(w[0] == 0.f && w[1] == 0.25f + (1.f - 0.25f) * 0.5f && w[2] == -1.f && dr == (std::vector<uint8_t>{0, 1, 1}));
    mp.object = nullptr;
    CHECK(cr_poses_run(p, &mp, 1) == CR_ERR_ARG);
    cr_mask_destroy(fm);
    cr_mask_destroy(mask);
    cr_clip_destroy(masked);
    cr_poses_destroy(p);
    cr_clip_destroy(b);
    cr_clip_destroy(a);
    cr_rig_destroy(rig);
}

// INERTIALIZE: a cut from a (Smile 1) to b (Smile 0) is continuous and settles on b by the blend time.
void test_inertialize() {
    cr_rig* rig = make_rig(true);
    cr_clip* a = make_clip(0.f, {{"Smile", {key(0.f, 1.f)}}});
    cr_clip* b = make_clip(0.f, {{"Smile", {key(0.f, 0.f)}}});
    cr_poses* p = cr_poses_create(rig, 1);
    cr_inertia* in = cr_inertia_create(rig);
    const float dt = 1.f / 30.f;
    auto frame = [&](const cr_clip* c) {
        cr_pose_op ops[2] = {op(CR_OP_SAMPLE, 0), op(CR_OP_INERTIALIZE, 0)};
        ops[0].object = c;
        ops[1].object = in;
        ops[1].param = dt;
        CHECK(cr_poses_run(p, ops, 2) == CR_OK);
        return weights_of(p, 0)[0];
    };
    frame(a);
    frame(a);
    CHECK(cr_inertia_request(in, 0.3f) == CR_OK);
    const float first = frame(b);
    CHECK(first < 1.f && first > 0.9f);  // continuous: evaluated dt into the decay
    float prev = first;
    for (int k = 0; k < 7; ++k) {
        const float v = frame(b);
        CHECK(v <= prev && v >= 0.f);  // decays monotonically, no overshoot
        prev = v;
    }
    for (int k = 0; k < 3; ++k) prev = frame(b);
    CHECK(prev == 0.f && cr_inertia_active(in) == 0);
    cr_inertia_destroy(in);
    cr_poses_destroy(p);
    cr_clip_destroy(b);
    cr_clip_destroy(a);
    cr_rig_destroy(rig);
}

// Morph, then skin: cr_skin_deform_morphed == cr_skin_deform(cr_morph_apply(...)), and differs from skin-then-morph.
void test_morph_then_skin() {
    cr_rig* rig = make_rig(false);
    cr_clip* c = make_clip(90.f, {});
    float skin[32];
    CHECK(cr_rig_pose(rig, c, 1.f, nullptr, 0, nullptr, 0, nullptr, skin, nullptr, nullptr) == CR_OK);
    const float pos[6] = {2.f, 0.f, 0.f, 0.5f, 0.f, 0.f};
    const int32_t part[2] = {1, 0};
    const char* name = "Push";
    const int32_t off[2] = {0, 1};
    const int32_t v[1] = {0};
    const float d[3] = {1.f, 0.f, 0.f};
    cr_morphs* m = cr_morphs_create(2, 1, &name, nullptr, off, v, d, nullptr);
    const float w = 1.f;
    float morphed[6], want[6], got[6];
    CHECK(cr_morph_apply(m, &w, 1, pos, nullptr, morphed, nullptr) == CR_OK);
    CHECK(cr_skin_deform(nullptr, part, morphed, 2, skin, 2, want) == CR_OK);
    CHECK(cr_skin_deform_morphed(nullptr, part, m, &w, 1, pos, 2, skin, 2, got) == CR_OK);
    CHECK(same_bits(want, got, 6));
    // The arm turned 90 degrees about z around (1, 0, 0): the morphed (3, 0, 0) lands at (1, 2, 0).
    CHECK_NEAR(got[0], 1.0, 1e-6);
    CHECK_NEAR(got[1], 2.0, 1e-6);
    // Skinning first, then adding the delta, gives (1, 1, 0) + (1, 0, 0): a different point.
    float sk[6];
    CHECK(cr_skin_deform(nullptr, part, pos, 2, skin, 2, sk) == CR_OK);
    CHECK(std::fabs((sk[0] + 1.f) - got[0]) > 0.5f);
    CHECK(cr_skin_deform_morphed(nullptr, part, m, &w, 1, pos, 3, skin, 2, got) == CR_ERR_ARG);  // vertex count
    CHECK(cr_skin_deform_morphed(nullptr, part, nullptr, nullptr, 0, pos, 2, skin, 2, got) == CR_OK);
    CHECK(same_bits(got, sk, 6));
    cr_morphs_destroy(m);
    cr_clip_destroy(c);
    cr_rig_destroy(rig);
}

// ---- the animation graph ------------------------------------------------------------------------------------------

cm_clip* doc_clip(float arm, const std::vector<std::pair<const char*, std::vector<cm_curve_key>>>& morphs) {
    cm_clip* c = cm_clip_create();
    cm_header h{30.f, 1.f, 1, 0, 0.f, 0.f, 0};
    cm_clip_header_set(c, &h);
    cr_key keys[2]{};
    for (int k = 0; k < 2; ++k) {
        keys[k].time = static_cast<float>(k);
        keys[k].rot_deg[2] = arm * static_cast<float>(k);
        keys[k].scale[0] = keys[k].scale[1] = keys[k].scale[2] = 1.f;
    }
    cm_clip_track_set(c, "arm", "arm", nullptr, 0, keys, 2);
    for (const auto& [n, ks] : morphs) cm_clip_morph_set(c, n, ks.data(), static_cast<int32_t>(ks.size()));
    return c;
}

cm_curve_key ck(float t, float v) { return cm_curve_key{t, v, 0.f, 0.f, CM_INTERP_LINEAR}; }

void test_graph() {
    const std::string json = R"({"format": "omanimgraph", "version": "1.2", "slots": ["a", "b"],
      "parameters": [{"name": "mix", "type": "float"}, {"name": "smile", "type": "float"}, {"name": "on", "type": "float", "default": 1}],
      "masks": [{"name": "blinkOnly", "entries": [{"morph": "Blink"}]}, {"name": "everything", "entries": [{"morph": "*"}, {"joint": "root"}]}],
      "root": "out",
      "nodes": [
        {"id": "ca", "type": "clip", "slot": "a"},
        {"id": "cb", "type": "clip", "slot": "b"},
        {"id": "mix", "type": "blend1d", "param": "mix", "samples": [{"node": "ca", "value": 0}, {"node": "cb", "value": 1}]},
        {"id": "out", "type": "layer", "base": "mix", "layers": [{"node": "cb", "mask": "blinkOnly", "weight": 0.5}]}
      ],
      "morphs": [{"morph": "Frown", "param": "smile", "weightParam": "on"}, {"morph": "Missing", "param": "smile"}]})";
    int32_t rc = 0;
    cg_graph* g = cg_graph_read(json.data(), static_cast<int64_t>(json.size()), &rc);
    CHECK(g != nullptr);
    if (g == nullptr) {
        std::fprintf(stderr, "  %s\n", cg_last_error());
        return;
    }
    // The writer keeps 1.2 (morph features) and the sections.
    cc_bytes* out = cg_graph_write(g);
    const std::string text(reinterpret_cast<const char*>(cc_bytes_data(out)), static_cast<size_t>(cc_bytes_size(out)));
    cc_bytes_destroy(out);
    CHECK(text.find("\"version\": \"1.2\"") != std::string::npos && text.find("\"morphs\"") != std::string::npos);
    CHECK(text.find("\"morph\": \"*\"") != std::string::npos && text.find("\"weightParam\": \"on\"") != std::string::npos);
    cr_rig* rig = make_rig(true);
    int32_t unresolved = -1;
    cg_instance* inst = cg_instance_create(g, rig, nullptr, &unresolved);
    CHECK(inst != nullptr && unresolved == 1);  // the drive of "Missing"
    cm_clip* a = doc_clip(0.f, {{"Smile", {ck(0.f, 1.f)}}, {"Frown", {ck(0.f, 0.6f)}}});
    cm_clip* b = doc_clip(0.f, {{"Smile", {ck(0.f, 0.f)}}, {"Blink", {ck(0.f, 1.f)}}});
    CHECK(cg_instance_bind_slot(inst, 0, a, nullptr) == CG_OK && cg_instance_bind_slot(inst, 1, b, nullptr) == CG_OK);
    float w[3];
    // mix 0 = clip a; the layer (b at 0.5, mask Blink only) gives Blink 0.5; the drive sets Frown to smile = 0.2.
    cg_instance_set_float(inst, 0, 0.f);
    cg_instance_set_float(inst, 1, 0.2f);
    CHECK(cg_instance_evaluate(inst, 1.f / 30.f, nullptr, 0, nullptr, nullptr, nullptr, nullptr) == CG_OK);
    CHECK(cg_instance_morph_weights(inst, CG_VIEW_BODY, w, 3) == 3);
    CHECK(w[0] == 1.f && w[1] == 0.2f && w[2] == 0.5f);
    // mix 0.5: Smile blends to 0.5; Blink: the blend 0.5, the layer then 0.5 -> 1 at 0.5; the drive at half weight
    // moves Frown halfway from the blend toward 0.2.
    cg_instance_set_float(inst, 0, 0.5f);
    cg_instance_set_float(inst, 2, 0.5f);
    CHECK(cg_instance_evaluate(inst, 1.f / 30.f, nullptr, 0, nullptr, nullptr, nullptr, nullptr) == CG_OK);
    CHECK(cg_instance_morph_weights(inst, CG_VIEW_BODY, w, 3) == 3);
    CHECK_NEAR(w[0], 0.5, 1e-6);
    CHECK_NEAR(w[2], 0.75, 1e-6);
    const double frown_blend = 0.5 * 0.6 + 0.5 * 0.25;
    CHECK_NEAR(w[1], frown_blend + (0.2 - frown_blend) * 0.5, 1e-6);
    CHECK(cg_instance_morph_weights(inst, 7, w, 3) == CG_ERR_ARG);
    cg_instance_destroy(inst);
    // A rig without channels: the graph still runs; the Blink entry and both drives are unresolved.
    cr_rig* bare = make_rig(false);
    cg_instance* bi = cg_instance_create(g, bare, nullptr, &unresolved);
    CHECK(bi != nullptr && unresolved == 3);
    CHECK(cg_instance_bind_slot(bi, 0, a, nullptr) == CG_OK);
    CHECK(cg_instance_evaluate(bi, 1.f / 30.f, nullptr, 0, nullptr, nullptr, nullptr, nullptr) == CG_OK);
    CHECK(cg_instance_morph_weights(bi, CG_VIEW_BODY, w, 3) == 0);
    cg_instance_destroy(bi);
    cr_rig_destroy(bare);
    cm_clip_destroy(b);
    cm_clip_destroy(a);
    cr_rig_destroy(rig);
    cg_graph_destroy(g);
    // A drive on a non-float parameter is refused.
    const std::string bad = R"({"format": "omanimgraph", "version": "1.2", "slots": ["a"], "parameters": [{"name": "b", "type": "bool"}],
      "root": "c", "nodes": [{"id": "c", "type": "clip", "slot": "a"}], "morphs": [{"morph": "x", "param": "b"}]})";
    CHECK(cg_graph_read(bad.data(), static_cast<int64_t>(bad.size()), &rc) == nullptr && rc == CG_ERR_FORMAT);
    // A graph without morph features still writes 1.1.
    const std::string plain = R"({"format": "omanimgraph", "version": "1.1", "slots": ["a"], "root": "c",
      "nodes": [{"id": "c", "type": "clip", "slot": "a"}]})";
    cg_graph* pg = cg_graph_read(plain.data(), static_cast<int64_t>(plain.size()), &rc);
    CHECK(pg != nullptr);
    if (pg != nullptr) {
        cc_bytes* pb = cg_graph_write(pg);
        const std::string pt(reinterpret_cast<const char*>(cc_bytes_data(pb)), static_cast<size_t>(cc_bytes_size(pb)));
        CHECK(pt.find("\"version\": \"1.1\"") != std::string::npos && pt.find("morph") == std::string::npos);
        cc_bytes_destroy(pb);
        cg_graph_destroy(pg);
    }
}

// The graph editor sets the morph drives as a section (#171).
void test_graph_edit() {
    const std::string json = R"({"format": "omanimgraph", "version": "1.1", "slots": ["a"],
      "parameters": [{"name": "smile", "type": "float"}], "root": "c", "nodes": [{"id": "c", "type": "clip", "slot": "a"}]})";
    int32_t rc = 0;
    cg_doc* d = cg_doc_read(json.data(), static_cast<int64_t>(json.size()), &rc);
    CHECK(d != nullptr);
    if (d == nullptr) return;
    const std::string ops = R"([{"op": "section", "name": "morphs", "value": [{"morph": "Smile", "param": "smile"}]}])";
    CHECK(cg_doc_apply(d, ops.data(), static_cast<int64_t>(ops.size()), nullptr, nullptr) == 1);
    const std::string out = cg_doc_json(d);
    CHECK(out.find("\"morphs\"") != std::string::npos && out.find("\"version\": \"1.2\"") != std::string::npos);
    CHECK(cg_doc_valid(d) == 1);
    const std::string bad = R"([{"op": "section", "name": "morphs", "value": [{"morph": "Smile", "param": "nope"}]}])";
    cg_doc_apply(d, bad.data(), static_cast<int64_t>(bad.size()), nullptr, nullptr);
    CHECK(cg_doc_valid(d) == 0 || std::string(cg_doc_json(d)).find("nope") == std::string::npos);
    cg_doc_destroy(d);
}

// ---- authoring ----------------------------------------------------------------------------------------------------

int32_t apply(cm_author* a, const std::string& ops) {
    return cm_author_apply(a, ops.data(), static_cast<int64_t>(ops.size()), nullptr, nullptr);
}

void test_authoring() {
    cr_rig* rig = make_rig(true);
    cm_clip* c = doc_clip(30.f, {{"Smile", {ck(0.f, 0.f), ck(1.f, 1.f)}}});
    cm_author* a = cm_author_create(c, rig, nullptr);
    CHECK(a != nullptr);
    if (a == nullptr) {
        std::fprintf(stderr, "  %s\n", cm_last_error());
        return;
    }
    float w[3];
    CHECK(cm_author_morph_weights(a, 0.5f, CM_STAGE_FINAL, w, 3) == 3);
    CHECK(w[0] == 0.5f && w[1] == 0.25f && w[2] == 0.f);
    // A key on the base layer (a trivial session: the base morph tracks are the clip's, unbaked).
    CHECK(apply(a, R"([{"op": "morph_key", "morph": "morph:Blink", "time": 0.5, "value": 1, "interp": "STEP"}])") == 1);
    const cm_clip* out = cm_author_clip(a);
    CHECK(cm_clip_morph_count(out) == 2 && cm_clip_morph_find(out, "Blink") == 1);
    cm_curve_key keys[4];
    CHECK(cm_clip_morph_keys(out, 1, keys, 4) == 1 && keys[0].interp == CM_INTERP_STEP && keys[0].value == 1.f);
    CHECK(cm_author_morph_weights(a, 0.7f, CM_STAGE_FINAL, w, 3) == 3 && w[2] == 1.f);
    // An additive layer keying Smile: composed = base + w * (layer - default).
    CHECK(apply(a, R"([{"op": "layer", "name": "Add", "mode": "additive", "weight": 0.5},
                       {"op": "morph", "layer": "Add", "morph": "Smile", "keys": [{"time": 0, "value": 0.4}]}])") == 2);
    CHECK(cm_author_morph_weights(a, 0.5f, CM_STAGE_FINAL, w, 3) == 3);
    CHECK_NEAR(w[0], 0.5 + 0.5 * 0.4, 1e-6);
    out = cm_author_clip(a);
    const int32_t si = cm_clip_morph_find(out, "Smile");
    CHECK(si >= 0);
    // Baked at the clip's fps; the baked track gives what the session poses at the frames.
    for (float t : {0.f, 1.f / 3.f, 0.5f, 1.f}) {
        CHECK(cm_author_morph_weights(a, t, CM_STAGE_FINAL, w, 3) == 3);
        CHECK_NEAR(cm_clip_morph_sample(out, si, t), w[0], 1e-6);
    }
    // Blink is keyed only on the base layer: copied unbaked (its one STEP key).
    CHECK(cm_clip_morph_keys(out, cm_clip_morph_find(out, "Blink"), keys, 4) == 1);
    // The written file is 1.3 and reopens to the same session.
    cc_bytes* bytes = cm_author_write(a);
    int32_t rc = 0;
    cm_clip* back = cm_clip_read(cc_bytes_data(bytes), cc_bytes_size(bytes), &rc);
    CHECK(back != nullptr && cm_clip_morph_count(back) == 2);
    cc_bytes_destroy(bytes);
    if (back != nullptr) {
        CHECK(std::strcmp(cm_clip_get_string(back, CM_FIELD_VERSION), "1.3") == 0);
        cm_author* again = cm_author_create(back, rig, nullptr);
        float w2[3];
        CHECK(again != nullptr && cm_author_morph_weights(again, 0.5f, CM_STAGE_FINAL, w2, 3) == 3);
        CHECK(cm_author_morph_weights(a, 0.5f, CM_STAGE_FINAL, w, 3) == 3 && same_bits(w, w2, 3));
        if (again != nullptr) cm_author_destroy(again);
        cm_clip_destroy(back);
    }
    // Removing a key (the track goes with its last key); refusals.
    CHECK(apply(a, R"([{"op": "morph_key", "morph": "Blink", "time": 0.5, "remove": true}])") == 1);
    CHECK(cm_clip_morph_find(cm_author_clip(a), "Blink") == -1);
    CHECK(apply(a, R"([{"op": "morph_key", "morph": "Blink", "time": 0.5, "remove": true}])") == CM_ERR_FORMAT);
    CHECK(apply(a, R"([{"op": "morph_key", "morph": "Smile", "time": 0.5, "value": 1, "interp": "BOUNCY"}])") == CM_ERR_FORMAT);
    CHECK(apply(a, R"([{"op": "morph_key", "time": 0.5, "value": 1}])") == CM_ERR_FORMAT);
    // Undo brings Blink back.
    CHECK(cm_author_undo(a) == CM_OK && cm_clip_morph_find(cm_author_clip(a), "Blink") >= 0);
    // Replacing a key keeps its interp; the whole track removed with keys null.
    CHECK(apply(a, R"([{"op": "morph_key", "morph": "Blink", "time": 0.5, "value": 0.25}])") == 1);
    out = cm_author_clip(a);
    CHECK(cm_clip_morph_keys(out, cm_clip_morph_find(out, "Blink"), keys, 4) == 1 && keys[0].interp == CM_INTERP_STEP &&
          keys[0].value == 0.25f);
    CHECK(apply(a, R"([{"op": "morph", "morph": "Blink", "keys": null}])") == 1);
    CHECK(cm_clip_morph_find(cm_author_clip(a), "Blink") == -1);
    // The view lists each layer's morph tracks.
    const std::string view = cm_author_view(a);
    CHECK(view.find("\"morphs\"") != std::string::npos && view.find("Smile") != std::string::npos);
    cm_author_destroy(a);
    cm_clip_destroy(c);
    cr_rig_destroy(rig);
}

}  // namespace

int main() {
    CHECK(cr_abi_version() == CR_ABI_VERSION && CR_ABI_VERSION == 3);
    test_build_and_queries();
    test_weights();
    test_apply_and_pack();
    test_remap_and_doc();
    test_channels_and_sampling();
    test_blend_layer_mask();
    test_inertialize();
    test_morph_then_skin();
    test_graph();
    test_graph_edit();
    test_authoring();
    if (failures != 0) {
        std::fprintf(stderr, "morph: %d check(s) failed\n", failures);
        return 1;
    }
    std::printf("morph: all checks passed\n");
    return 0;
}
