// Cenda animation authoring (OpenMason #165): layers composed through the rig's pose operations, the control rig
// solved by the modifier stack, baking, switch-and-match, retarget import and the history. See cenda/anim_author.h.
#include "cenda/anim_author.h"

#include "../character/character_internal.hpp"
#include "omformat/omanim.hpp"
#include "omformat/omanim_author.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <memory>
#include <numbers>
#include <span>
#include <string>
#include <vector>

namespace oa = cenda::omformat::omanim;
namespace oz = cenda::omformat::omanim::author;

namespace cenda::anim {
void set_error(const std::string& message);
}  // namespace cenda::anim

namespace {

int32_t fail(int32_t code, const std::string& message) {
    cenda::anim::set_error(message);
    return code;
}

[[noreturn]] void raise(const std::string& message) { throw cenda::omformat::format_error(message); }

std::string quote(const std::string& s) {
    std::string out = "\"";
    for (char ch : s) {
        if (ch == '"' || ch == '\\') {
            out += '\\';
            out += ch;
        } else if (static_cast<unsigned char>(ch) < 0x20) {
            char b[8];
            std::snprintf(b, sizeof b, "\\u%04x", static_cast<unsigned>(static_cast<unsigned char>(ch)));
            out += b;
        } else {
            out += ch;
        }
    }
    return out + "\"";
}

// ---- small math (double) ----------------------------------------------------------------------------------------

using Mat3 = std::array<std::array<double, 3>, 3>;
constexpr double kDeg = std::numbers::pi / 180.0;

Mat3 euler_xyz(const float* deg) {
    const double a = deg[0] * kDeg, b = deg[1] * kDeg, c = deg[2] * kDeg;
    const double ca = std::cos(a), sa = std::sin(a), cb = std::cos(b), sb = std::sin(b), cc = std::cos(c), sc = std::sin(c);
    return {{{cb * cc, -cb * sc, sb},
             {ca * sc + sa * sb * cc, ca * cc - sa * sb * sc, -sa * cb},
             {sa * sc - ca * sb * cc, sa * cc + ca * sb * sc, ca * cb}}};
}

void to_euler_xyz(const Mat3& r, float* deg) {
    const double sb = std::clamp(r[0][2], -1.0, 1.0);
    const double b = std::asin(sb);
    double a = 0, c = 0;
    if (std::abs(sb) < 1.0 - 1e-9) {
        a = std::atan2(-r[1][2], r[2][2]);
        c = std::atan2(-r[0][1], r[0][0]);
    } else {
        a = std::atan2(r[2][1], r[1][1]);
    }
    deg[0] = static_cast<float>(a / kDeg);
    deg[1] = static_cast<float>(b / kDeg);
    deg[2] = static_cast<float>(c / kDeg);
}

Mat3 rotation_of(const float* m) {
    Mat3 r{};
    for (std::size_t col = 0; col < 3; ++col) {
        double x = m[col * 4], y = m[col * 4 + 1], z = m[col * 4 + 2];
        const double l = std::sqrt(x * x + y * y + z * z);
        if (l > 0) {
            x /= l;
            y /= l;
            z /= l;
        }
        r[0][col] = x;
        r[1][col] = y;
        r[2][col] = z;
    }
    return r;
}

// Quaternion (x, y, z, w) of a rotation matrix.
std::array<float, 4> quat_of(const Mat3& m) {
    const double tr = m[0][0] + m[1][1] + m[2][2];
    double x, y, z, w;
    if (tr > 0) {
        const double s = std::sqrt(tr + 1.0) * 2;
        w = 0.25 * s;
        x = (m[2][1] - m[1][2]) / s;
        y = (m[0][2] - m[2][0]) / s;
        z = (m[1][0] - m[0][1]) / s;
    } else if (m[0][0] > m[1][1] && m[0][0] > m[2][2]) {
        const double s = std::sqrt(1.0 + m[0][0] - m[1][1] - m[2][2]) * 2;
        w = (m[2][1] - m[1][2]) / s;
        x = 0.25 * s;
        y = (m[0][1] + m[1][0]) / s;
        z = (m[0][2] + m[2][0]) / s;
    } else if (m[1][1] > m[2][2]) {
        const double s = std::sqrt(1.0 + m[1][1] - m[0][0] - m[2][2]) * 2;
        w = (m[0][2] - m[2][0]) / s;
        x = (m[0][1] + m[1][0]) / s;
        y = 0.25 * s;
        z = (m[1][2] + m[2][1]) / s;
    } else {
        const double s = std::sqrt(1.0 + m[2][2] - m[0][0] - m[1][1]) * 2;
        w = (m[1][0] - m[0][1]) / s;
        x = (m[0][2] + m[2][0]) / s;
        y = (m[1][2] + m[2][1]) / s;
        z = 0.25 * s;
    }
    return {static_cast<float>(x), static_cast<float>(y), static_cast<float>(z), static_cast<float>(w)};
}

std::array<float, 4> slerp(std::array<float, 4> a, std::array<float, 4> b, float t) {
    double dot = static_cast<double>(a[0]) * b[0] + static_cast<double>(a[1]) * b[1] + static_cast<double>(a[2]) * b[2] +
                 static_cast<double>(a[3]) * b[3];
    if (dot < 0) {
        for (float& v : b) v = -v;
        dot = -dot;
    }
    std::array<float, 4> out{};
    if (dot > 0.9995) {
        double l = 0;
        for (int i = 0; i < 4; ++i) {
            out[static_cast<size_t>(i)] = a[static_cast<size_t>(i)] + t * (b[static_cast<size_t>(i)] - a[static_cast<size_t>(i)]);
            l += static_cast<double>(out[static_cast<size_t>(i)]) * out[static_cast<size_t>(i)];
        }
        l = std::sqrt(l);
        for (float& v : out) v = static_cast<float>(v / l);
        return out;
    }
    const double th = std::acos(dot), s = std::sin(th);
    const double wa = std::sin((1 - t) * th) / s, wb = std::sin(t * th) / s;
    for (int i = 0; i < 4; ++i) {
        out[static_cast<size_t>(i)] = static_cast<float>(wa * a[static_cast<size_t>(i)] + wb * b[static_cast<size_t>(i)]);
    }
    return out;
}

// ---- the evaluator: one session's clips, masks, control rig and pose slots --------------------------------------

enum { SLOT_OUT = 0, SLOT_REST = 1, SLOT_TMP = 2, SLOT_LAYERS = 3 };

struct Evaluator {
    const cr_rig* rig;
    const cc_char* character;
    const oz::Session* s;
    std::vector<cr_clip*> clips;  // per layer (null: no track binds)
    std::vector<cr_mask*> masks;  // per layer (null: whole body)
    std::vector<std::vector<int32_t>> chains;  // per effector: joint indices (twoBone: upper, mid, end)
    cr_modstack* stack = nullptr;
    cr_poses* poses = nullptr;
    std::vector<float> locals;

    Evaluator(const oz::Session& session, const cr_rig* r, const cc_char* c) : rig(r), character(c), s(&session) {
        for (const oz::AuthorLayer& l : session.layers) {
            clips.push_back(clip_of(l.clip));
            masks.push_back(mask_of(l.mask));
        }
        build_stack();
        poses = cr_poses_create(rig, SLOT_LAYERS + static_cast<int32_t>(session.layers.size()));
        if (poses == nullptr) {
            release();
            raise(std::string("pose slots: ") + cr_last_error());
        }
        locals.resize(static_cast<size_t>(cr_rig_joint_count(rig)) * 9);
    }
    Evaluator(const Evaluator&) = delete;
    Evaluator& operator=(const Evaluator&) = delete;
    ~Evaluator() { release(); }

    void release() {
        for (cr_clip* c : clips) {
            if (c != nullptr) cr_clip_destroy(c);
        }
        clips.clear();
        for (cr_mask* m : masks) {
            if (m != nullptr) cr_mask_destroy(m);
        }
        masks.clear();
        if (stack != nullptr) cr_modstack_destroy(stack);
        stack = nullptr;
        if (poses != nullptr) cr_poses_destroy(poses);
        poses = nullptr;
    }

    // The joint a track addresses (-1 when it binds to none).
    int32_t joint_of(const oa::Track& t) const {
        if (!t.role.empty()) {
            if (character == nullptr) return -1;
            const char* id = cc_char_role_joint(character, t.role.c_str());
            return id == nullptr ? -1 : cr_rig_find(rig, id);
        }
        int32_t j = cr_rig_find(rig, t.part_id.c_str());
        if (j < 0 && !t.part_name.empty()) j = cr_rig_find(rig, t.part_name.c_str());
        return j;
    }

    cr_clip* clip_of(const oa::Clip& c) const {
        std::vector<cr_track_desc> tracks;
        std::vector<cr_key> keys;
        std::vector<std::string> ids, names;
        for (const oa::Track& t : c.tracks) {
            const int32_t j = joint_of(t);
            if (j < 0 || t.keys.empty()) continue;
            const auto first = static_cast<int32_t>(keys.size());
            for (const oa::Key& k : t.keys) {
                cr_key ck{};
                ck.time = k.time;
                std::memcpy(ck.pos, k.pos, sizeof ck.pos);
                std::memcpy(ck.rot_deg, k.rot, sizeof ck.rot_deg);
                std::memcpy(ck.scale, k.scale, sizeof ck.scale);
                ck.easing = static_cast<int32_t>(k.easing);
                keys.push_back(ck);
            }
            tracks.push_back({first, static_cast<int32_t>(t.keys.size())});
            ids.emplace_back(rig->joints[static_cast<size_t>(j)].id);
            names.emplace_back(rig->joints[static_cast<size_t>(j)].name);
        }
        if (tracks.empty()) return nullptr;
        std::vector<const char*> idp, namep;
        for (size_t i = 0; i < ids.size(); ++i) {
            idp.push_back(ids[i].c_str());
            namep.push_back(names[i].c_str());
        }
        cr_clip_desc desc{std::max(c.duration, s->clip.duration), s->clip.loop ? 1 : 0, CR_LAYER_BASE, 0, 0.f, 0.f};
        cr_clip* out = cr_clip_create(&desc, tracks.data(), static_cast<int32_t>(tracks.size()), keys.data(),
                                      static_cast<int32_t>(keys.size()), idp.data(), namep.data(), nullptr, 0);
        if (out == nullptr) raise(std::string("layer clip: ") + cr_last_error());
        return out;
    }

    int32_t resolve(const std::string& ref) const {
        if (ref.rfind("role:", 0) == 0) {
            if (character == nullptr) return -1;
            const char* id = cc_char_role_joint(character, ref.substr(5).c_str());
            return id == nullptr ? -1 : cr_rig_find(rig, id);
        }
        return cr_rig_find(rig, ref.c_str());
    }

    cr_mask* mask_of(const std::vector<oz::MaskEntry>& entries) const {
        if (entries.empty()) return nullptr;
        cr_mask* m = cr_mask_create(rig, 0.f);
        for (const oz::MaskEntry& e : entries) {
            const int32_t j = resolve(e.role.empty() ? e.joint : "role:" + e.role);
            if (j < 0) continue;
            cr_mask_set(m, j, e.weight, e.branch ? static_cast<uint32_t>(CR_MASK_BRANCH) : 0u, 0);
        }
        return m;
    }

    void build_stack() {
        if (s->effectors.empty()) return;
        std::vector<cr_modifier_desc> mods;
        std::vector<std::vector<float>> weights;  // per modifier (rig ABI 3: the desc points at chains / weights)
        for (size_t i = 0; i < s->effectors.size(); ++i) {
            const oz::Effector& e = s->effectors[i];
            cr_modifier_desc d{};
            d.weight = 1.f;
            d.target = static_cast<int32_t>(i * 2);
            d.pole = -1;
            std::vector<int32_t> chain;
            std::vector<float> w;
            if (e.kind == "twoBone") {
                for (const std::string* ref : {&e.upper, &e.mid, &e.end}) chain.push_back(resolve(*ref));
                d.type = CR_MOD_TWO_BONE;
                const bool any_pole = std::any_of(e.keys.begin(), e.keys.end(), [](const oz::EffectorKey& k) { return k.has_pole; });
                const bool any_rot = std::any_of(e.keys.begin(), e.keys.end(), [](const oz::EffectorKey& k) { return k.has_rot; });
                if (any_pole) d.pole = static_cast<int32_t>(i * 2 + 1);
                if (any_rot) d.flags |= CR_MOD_MATCH_ROTATION;
            } else {
                for (const std::string& ref : e.joints) chain.push_back(resolve(ref));
                d.type = CR_MOD_AIM;
                for (int k = 0; k < 3; ++k) d.p[k] = e.axis[k];
                w.assign(chain.size(), chain.empty() ? 0.f : 1.f / static_cast<float>(chain.size()));
            }
            if (chain.empty() || std::any_of(chain.begin(), chain.end(), [](int32_t j) { return j < 0; })) {
                raise("effector " + e.name + ": a joint is not in the rig");
            }
            d.joint_count = static_cast<int32_t>(chain.size());
            chains.push_back(chain);
            weights.push_back(std::move(w));
            mods.push_back(d);
        }
        const size_t first = chains.size() - mods.size();  // this build's chains
        for (size_t i = 0; i < mods.size(); ++i) {
            mods[i].joints = chains[first + i].data();
            mods[i].joint_weights = weights[i].empty() ? nullptr : weights[i].data();
        }
        stack = cr_modstack_create(rig, mods.data(), static_cast<int32_t>(mods.size()),
                                   static_cast<int32_t>(s->effectors.size() * 2));
        if (stack == nullptr) raise(std::string("control rig: ") + cr_last_error());
    }

    static float curve_at(const oa::Clip& c, const char* name, float t, float fallback) {
        for (const oa::Curve& cv : c.curves) {
            if (cv.name != name || cv.keys.empty()) continue;
            if (t <= cv.keys.front().time) return cv.keys.front().value;
            if (t >= cv.keys.back().time) return cv.keys.back().value;
            for (size_t i = 1; i < cv.keys.size(); ++i) {
                const oa::CurveKey& a = cv.keys[i - 1];
                const oa::CurveKey& b = cv.keys[i];
                if (t > b.time) continue;
                if (a.interp == oa::CurveInterp::Step) return a.value;
                const float u = b.time > a.time ? (t - a.time) / (b.time - a.time) : 1.f;
                return a.value + (b.value - a.value) * u;
            }
        }
        return fallback;
    }

    // The effector's interpolated key at t: target, rotation (quat; w 0 = none), weight, pole (has).
    struct Goal {
        float pos[3]{};
        std::array<float, 4> rot{0, 0, 0, 0};
        bool has_rot = false;
        float weight = 0.f;
        float pole[3]{};
        bool has_pole = false;
    };

    static Goal goal_at(const oz::Effector& e, float t) {
        Goal g;
        if (e.keys.empty()) return g;
        auto quat = [](const oz::EffectorKey& k) { return quat_of(euler_xyz(k.rot)); };
        auto set = [&](const oz::EffectorKey& k) {
            std::memcpy(g.pos, k.pos, sizeof g.pos);
            g.has_rot = k.has_rot;
            if (k.has_rot) g.rot = quat(k);
            g.weight = k.weight;
            g.has_pole = k.has_pole;
            std::memcpy(g.pole, k.pole, sizeof g.pole);
        };
        if (t <= e.keys.front().time) {
            set(e.keys.front());
            return g;
        }
        if (t >= e.keys.back().time) {
            set(e.keys.back());
            return g;
        }
        for (size_t i = 1; i < e.keys.size(); ++i) {
            const oz::EffectorKey& a = e.keys[i - 1];
            const oz::EffectorKey& b = e.keys[i];
            if (t > b.time) continue;
            const float u = b.time > a.time ? (t - a.time) / (b.time - a.time) : 1.f;
            for (int k = 0; k < 3; ++k) {
                g.pos[k] = a.pos[k] + (b.pos[k] - a.pos[k]) * u;
                g.pole[k] = a.pole[k] + (b.pole[k] - a.pole[k]) * u;
            }
            g.weight = a.weight + (b.weight - a.weight) * u;
            g.has_pole = a.has_pole || b.has_pole;
            if (a.has_pole != b.has_pole) std::memcpy(g.pole, a.has_pole ? a.pole : b.pole, sizeof g.pole);
            g.has_rot = a.has_rot || b.has_rot;
            if (a.has_rot && b.has_rot) g.rot = slerp(quat(a), quat(b), u);
            else if (g.has_rot) g.rot = quat(a.has_rot ? a : b);
            return g;
        }
        set(e.keys.back());
        return g;
    }

    // Runs the layers (and the control rig, minus effector `skip`) at t into SLOT_OUT; returns its locals.
    const float* pose(float t, int stage, int skip = -1) {
        std::vector<cr_pose_op> ops;
        ops.reserve(2 + 3 * s->layers.size());  // references from push() stay valid (never reallocates)
        auto push = [&](int32_t op, int32_t dst) -> cr_pose_op& {
            ops.push_back(cr_pose_op{});
            ops.back().op = op;
            ops.back().dst = dst;
            return ops.back();
        };
        push(CR_OP_REST, SLOT_REST);
        bool solo = false;
        for (size_t i = 1; i < s->layers.size(); ++i) solo |= s->layers[i].solo;
        const oz::AuthorLayer& base = s->layers.front();
        if (base.mute || clips[0] == nullptr) {
            push(CR_OP_REST, SLOT_OUT);
        } else {
            cr_pose_op& op = push(CR_OP_SAMPLE, SLOT_OUT);
            op.object = clips[0];
            op.time = t;
        }
        for (size_t i = 1; i < s->layers.size(); ++i) {
            const oz::AuthorLayer& l = s->layers[i];
            if (l.mute || (solo && !l.solo) || clips[i] == nullptr) continue;
            const float w = std::clamp(l.weight * curve_at(l.clip, "weight", t, 1.f), 0.f, 1.f);
            if (w <= 0.f) continue;
            const auto slot = static_cast<int32_t>(SLOT_LAYERS + i);
            cr_pose_op& sm = push(CR_OP_SAMPLE, slot);
            sm.object = clips[i];
            sm.time = t;
            if (l.mode == oz::LayerMode::Additive) {
                const uint32_t space = l.mesh_space ? static_cast<uint32_t>(CR_POSE_MESH_SPACE) : 0u;
                cr_pose_op& mk = push(CR_OP_MAKE_ADDITIVE, SLOT_TMP);
                mk.a = slot;
                mk.b = SLOT_REST;
                mk.flags = space;
                cr_pose_op& ly = push(CR_OP_LAYER, SLOT_OUT);
                ly.a = SLOT_TMP;
                ly.object = masks[i];
                ly.weight = w;
                ly.mode = CR_BLEND_ADDITIVE;
                ly.flags = space;
            } else {
                cr_pose_op& ly = push(CR_OP_LAYER, SLOT_OUT);
                ly.a = slot;
                ly.object = masks[i];
                ly.weight = w;
                ly.mode = CR_BLEND_OVERRIDE;
                ly.flags = l.passthrough ? 0u : static_cast<uint32_t>(CR_POSE_NO_PASSTHROUGH);
            }
        }
        if (cr_poses_run(poses, ops.data(), static_cast<int32_t>(ops.size())) != CR_OK) {
            raise(std::string("posing: ") + cr_last_error());
        }
        if (stage == CM_STAGE_FINAL && stack != nullptr) {
            for (size_t i = 0; i < s->effectors.size(); ++i) {
                const Goal g = goal_at(s->effectors[i], t);
                cr_target tg{};
                tg.kind = CR_TARGET_MODEL;
                std::memcpy(tg.pos, g.pos, sizeof tg.pos);
                if (g.has_rot) std::memcpy(tg.rot, g.rot.data(), sizeof tg.rot);
                cr_modstack_set_target(stack, static_cast<int32_t>(i * 2), &tg);
                cr_target pole{};
                pole.kind = g.has_pole ? CR_TARGET_MODEL : CR_TARGET_NONE;
                std::memcpy(pole.pos, g.pole, sizeof pole.pos);
                cr_modstack_set_target(stack, static_cast<int32_t>(i * 2 + 1), &pole);
                cr_modstack_set_weight(stack, static_cast<int32_t>(i), static_cast<int>(i) == skip ? 0.f : g.weight);
            }
            cr_modstack_reset(stack);
            if (cr_modstack_apply(stack, poses, SLOT_OUT, 0.f) != CR_OK) raise(std::string("control rig: ") + cr_last_error());
        }
        cr_poses_get(poses, SLOT_OUT, locals.data(), nullptr);
        return locals.data();
    }

    void world(const float* local, std::vector<float>& skin, std::vector<float>& w) const {
        const size_t n = static_cast<size_t>(cr_rig_joint_count(rig));
        skin.resize(n * 16);
        w.resize(n * 16);
        cr_rig_pose_locals(rig, local, nullptr, 0, nullptr, skin.data(), w.data(), nullptr);
    }

    std::array<double, 3> point(int32_t j, const std::vector<float>& w) const {
        double p[3];
        cenda::character::joint_point(rig, j, w.data(), p);
        return {p[0], p[1], p[2]};
    }
};

// Bakes the session's tracks (when not trivial): FINAL poses at k / fps and the last frame, Euler unwrapped.
void bake(oz::Session& s, const cr_rig* rig, const cc_char* character) {
    if (oz::trivial(s)) {
        s.clip.tracks = s.layers.front().clip.tracks;
        return;
    }
    Evaluator ev(s, rig, character);
    const size_t n = static_cast<size_t>(cr_rig_joint_count(rig));
    std::vector<int> role_of(n, -1);  // driven joints: index into `roles` (-1 = joint-addressed), -2 = undriven
    std::vector<std::string> roles;
    std::fill(role_of.begin(), role_of.end(), -2);
    for (size_t li = 0; li < s.layers.size(); ++li) {
        for (const oa::Track& t : s.layers[li].clip.tracks) {
            const int32_t j = ev.joint_of(t);
            if (j < 0) continue;
            int& r = role_of[static_cast<size_t>(j)];
            if (r == -2) {
                if (!t.role.empty()) {
                    r = static_cast<int>(roles.size());
                    roles.push_back(t.role);
                } else {
                    r = -1;
                }
            }
        }
    }
    for (const auto& chain : ev.chains) {
        for (int32_t j : chain) {
            if (role_of[static_cast<size_t>(j)] == -2) role_of[static_cast<size_t>(j)] = -1;
        }
    }
    const float fps = s.clip.fps > 0.f ? s.clip.fps : 30.f;
    const float dur = std::max(0.f, s.clip.duration);
    std::vector<float> times;
    const int frames = static_cast<int>(std::floor(dur * fps + 1e-4f));
    for (int k = 0; k <= frames; ++k) times.push_back(static_cast<float>(k) / fps);
    if (times.empty() || dur - times.back() > 1e-4f) times.push_back(dur);

    std::vector<oa::Track> tracks;
    std::vector<int32_t> track_joint;
    for (size_t j = 0; j < n; ++j) {
        if (role_of[j] == -2) continue;
        oa::Track t;
        const auto& joint = rig->joints[j];
        if (role_of[j] >= 0) {
            t.role = roles[static_cast<size_t>(role_of[j])];
            t.part_id = std::string(oa::kRolePrefix) + t.role;
            t.part_name = t.part_id;
        } else {
            t.part_id = joint.id;
            t.part_name = joint.name;
        }
        t.bone = joint.kind == CR_JOINT_BONE;
        tracks.push_back(std::move(t));
        track_joint.push_back(static_cast<int32_t>(j));
    }
    for (float t : times) {
        const float* local = ev.pose(t, CM_STAGE_FINAL);
        for (size_t k = 0; k < tracks.size(); ++k) {
            const float* v = local + static_cast<size_t>(track_joint[k]) * 9;
            oa::Key key;
            key.time = t;
            std::memcpy(key.pos, v, sizeof key.pos);
            std::memcpy(key.rot, v + 3, sizeof key.rot);
            std::memcpy(key.scale, v + 6, sizeof key.scale);
            if (!tracks[k].keys.empty()) {  // unwrap: the equivalent angle nearest the previous key
                const oa::Key& prev = tracks[k].keys.back();
                for (int a = 0; a < 3; ++a) {
                    while (key.rot[a] - prev.rot[a] > 180.f) key.rot[a] -= 360.f;
                    while (key.rot[a] - prev.rot[a] < -180.f) key.rot[a] += 360.f;
                }
            }
            tracks[k].keys.push_back(key);
        }
    }
    s.clip.tracks = std::move(tracks);
}

// ---- host (joint resolution, sampling, matching) ------------------------------------------------------------------

class KernelHost final : public oz::Host {
public:
    KernelHost(const cr_rig* rig, const cc_char* character) : rig_(rig), character_(character) {}

    Joint resolve(const std::string& ref) override {
        Joint out;
        int32_t j = -1;
        if (ref.rfind("role:", 0) == 0) {
            out.role = ref.substr(5);
            if (character_ == nullptr) raise("role " + out.role + ": no character");
            const char* id = cc_char_role_joint(character_, out.role.c_str());
            if (id == nullptr) raise("role " + out.role + " is not mapped");
            j = cr_rig_find(rig_, id);
        } else {
            j = cr_rig_find(rig_, ref.c_str());
        }
        if (j < 0) raise("no joint " + ref);
        const auto& joint = rig_->joints[static_cast<size_t>(j)];
        out.id = joint.id;
        out.name = joint.name.empty() ? joint.id : joint.name;
        out.bone = joint.kind == CR_JOINT_BONE;
        return out;
    }

    oa::Key sample(const oz::Session& s, int layer, const Joint& joint, float time) override {
        const int32_t j = cr_rig_find(rig_, joint.id.c_str());
        oa::Key k;
        k.time = time;
        if (j < 0) return k;
        Evaluator ev(s, rig_, character_);
        std::vector<float> locals(static_cast<size_t>(cr_rig_joint_count(rig_)) * 9);
        cr_rig_local_pose(rig_, ev.clips[static_cast<size_t>(layer)], time, nullptr, 0, locals.data());
        const float* v = locals.data() + static_cast<size_t>(j) * 9;
        std::memcpy(k.pos, v, sizeof k.pos);
        std::memcpy(k.rot, v + 3, sizeof k.rot);
        std::memcpy(k.scale, v + 6, sizeof k.scale);
        return k;
    }

    void match(oz::Session& s, int e, float time, bool to_ik, int layer) override {
        bake(s, rig_, character_);
        Evaluator ev(s, rig_, character_);
        oz::Effector& eff = s.effectors[static_cast<size_t>(e)];
        const std::vector<int32_t>& chain = ev.chains[static_cast<size_t>(e)];
        if (to_ik) {
            // The pose without this effector: where FK (and the other effectors) put the chain.
            const float* local = ev.pose(time, CM_STAGE_FINAL, e);
            std::vector<float> skin, w;
            ev.world(local, skin, w);
            oz::EffectorKey k;
            k.time = time;
            k.weight = 1.f;
            const int32_t end = chain.back();
            if (eff.kind == "twoBone") {
                const auto p = ev.point(end, w);
                for (int i = 0; i < 3; ++i) k.pos[i] = static_cast<float>(p[static_cast<size_t>(i)]);
                to_euler_xyz(rotation_of(w.data() + static_cast<size_t>(end) * 16), k.rot);
                k.has_rot = true;
                const auto mid = ev.point(chain[1], w);
                for (int i = 0; i < 3; ++i) k.pole[i] = static_cast<float>(mid[static_cast<size_t>(i)]);
                k.has_pole = true;
            } else {
                // A point one unit along the aim axis of the last joint.
                const float* m = w.data() + static_cast<size_t>(end) * 16;
                const auto p = ev.point(end, w);
                double dir[3];
                for (int r = 0; r < 3; ++r) {
                    dir[r] = static_cast<double>(m[r]) * eff.axis[0] + static_cast<double>(m[4 + r]) * eff.axis[1] +
                             static_cast<double>(m[8 + r]) * eff.axis[2];
                }
                const double l = std::max(1e-9, std::sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2]));
                for (int i = 0; i < 3; ++i) k.pos[i] = static_cast<float>(p[static_cast<size_t>(i)] + dir[i] / l);
            }
            auto it = std::find_if(eff.keys.begin(), eff.keys.end(), [&](const oz::EffectorKey& x) { return std::fabs(x.time - time) <= 1e-4f; });
            if (it != eff.keys.end()) *it = k;
            else eff.keys.push_back(k);
            std::stable_sort(eff.keys.begin(), eff.keys.end(), [](const oz::EffectorKey& a, const oz::EffectorKey& b) { return a.time < b.time; });
        } else {
            // Key the chain to the solved pose on a layer, then hand control back to FK at this time - on the layer
            // asked for, or (none asked) the topmost one that holds the pose exactly: an override at full weight
            // with nothing above it touching the limb, else the base layer under the same condition. The result is
            // checked; a layer that cannot hold the pose (partial weight, mask, an additive or layer above) is refused.
            const size_t n = static_cast<size_t>(cr_rig_joint_count(rig_));
            const float* local = ev.pose(time, CM_STAGE_FINAL);
            std::vector<float> solved(local, local + n * 9);
            std::vector<float> skin, want;
            ev.world(solved.data(), skin, want);
            std::vector<int> candidates;
            if (layer >= 0) candidates.push_back(layer);
            else for (int i = static_cast<int>(s.layers.size()) - 1; i >= 0; --i) candidates.push_back(i);
            std::string tried;
            for (int cand : candidates) {
                oz::Session next = s;
                key_fk(next, cand, e, time, chain, solved, ev);
                bake(next, rig_, character_);
                Evaluator check(next, rig_, character_);
                std::vector<float> got;
                check.world(check.pose(time, CM_STAGE_FINAL), skin, got);
                double diff = 0;
                for (int32_t j : chain) {
                    const auto a = check.point(j, got), b = ev.point(j, want);
                    for (int k = 0; k < 3; ++k) diff = std::max(diff, std::fabs(a[static_cast<size_t>(k)] - b[static_cast<size_t>(k)]));
                }
                if (diff <= 1e-4) {
                    s = std::move(next);
                    return;
                }
                tried += (tried.empty() ? "" : ", ") + s.layers[static_cast<size_t>(cand)].name;
            }
            raise("match to FK: no layer holds the pose (" + tried + "); key it on an override layer at full weight"
                  " with no layer above it moving the limb");
        }
    }

    // Keys the chain's solved locals on layer `li` at `time` and the effector's weight 0 there.
    void key_fk(oz::Session& s, int li, int e, float time, const std::vector<int32_t>& chain,
                const std::vector<float>& solved, const Evaluator& ev) const {
        oz::AuthorLayer& l = s.layers[static_cast<size_t>(li)];
        for (int32_t j : chain) {
            const auto& joint = rig_->joints[static_cast<size_t>(j)];
            oa::Track* tr = nullptr;
            for (oa::Track& t : l.clip.tracks) {
                if (ev.joint_of(t) == j) tr = &t;
            }
            if (tr == nullptr) {
                oa::Track t;
                t.part_id = joint.id;
                t.part_name = joint.name;
                t.bone = joint.kind == CR_JOINT_BONE;
                l.clip.tracks.push_back(std::move(t));
                tr = &l.clip.tracks.back();
            }
            oa::Key key;
            key.time = time;
            const float* v = solved.data() + static_cast<size_t>(j) * 9;
            std::memcpy(key.pos, v, sizeof key.pos);
            std::memcpy(key.rot, v + 3, sizeof key.rot);
            std::memcpy(key.scale, v + 6, sizeof key.scale);
            auto it = std::find_if(tr->keys.begin(), tr->keys.end(), [&](const oa::Key& x) { return std::fabs(x.time - time) <= 1e-4f; });
            if (it != tr->keys.end()) {
                key.easing = it->easing;
                *it = key;
            } else {
                tr->keys.push_back(key);
                std::stable_sort(tr->keys.begin(), tr->keys.end(), [](const oa::Key& a, const oa::Key& b) { return a.time < b.time; });
            }
        }
        oz::Effector& eff = s.effectors[static_cast<size_t>(e)];
        const Evaluator::Goal g = Evaluator::goal_at(eff, time);
        auto it = std::find_if(eff.keys.begin(), eff.keys.end(), [&](const oz::EffectorKey& x) { return std::fabs(x.time - time) <= 1e-4f; });
        if (it != eff.keys.end()) {
            it->weight = 0.f;
        } else {
            oz::EffectorKey k;
            k.time = time;
            std::memcpy(k.pos, g.pos, sizeof k.pos);
            k.weight = 0.f;
            eff.keys.push_back(k);
            std::stable_sort(eff.keys.begin(), eff.keys.end(), [](const oz::EffectorKey& a, const oz::EffectorKey& b) { return a.time < b.time; });
        }
    }

private:
    const cr_rig* rig_;
    const cc_char* character_;
};

std::vector<std::byte> bytes_of(const oz::Session& s) { return oa::write(oz::clip_of(s)); }

}  // namespace

struct cm_author {
    struct Step {
        std::vector<std::byte> state;
        std::string label;
        std::string merge_key;
    };
    const cr_rig* rig = nullptr;
    const cc_char* character = nullptr;
    oz::Session s;
    std::vector<std::byte> state;
    std::vector<Step> steps;
    int32_t cursor = 0;
    int32_t saved = 0;
    bool sealed = true;
    int64_t revision = 0;
    std::string results = "[]";
    // Per-revision caches.
    int64_t cached = -1;
    std::string view;
    cm_clip* output = nullptr;
    std::unique_ptr<Evaluator> evaluator;
    std::string handles;

    ~cm_author() {
        evaluator.reset();
        if (output != nullptr) cm_clip_destroy(output);
    }

    void refresh() {
        if (cached == revision) return;
        evaluator.reset();
        if (output != nullptr) cm_clip_destroy(output);
        output = nullptr;
        view = oz::view(s);
        int32_t rc = 0;
        output = cm_clip_read(reinterpret_cast<const uint8_t*>(state.data()), static_cast<int64_t>(state.size()), &rc);
        evaluator = std::make_unique<Evaluator>(s, rig, character);
        cached = revision;
    }

    void restore(const std::vector<std::byte>& bytes) {
        s = oz::session_of(oa::read(std::span<const std::byte>(bytes.data(), bytes.size())));
        state = bytes;
    }
};

extern "C" {

cm_author* cm_author_create(const cm_clip* clip, const cr_rig* rig, const cc_char* character) {
    if (clip == nullptr || rig == nullptr) {
        fail(CM_ERR_ARG, "cm_author_create: null argument");
        return nullptr;
    }
    try {
        cc_bytes* b = cm_clip_write(clip);
        if (b == nullptr) raise(std::string("cm_author_create: ") + cm_last_error());
        std::vector<std::byte> bytes(b->data);
        cc_bytes_destroy(b);
        auto a = std::make_unique<cm_author>();
        a->rig = rig;
        a->character = character;
        a->s = oz::session_of(oa::read(std::span<const std::byte>(bytes.data(), bytes.size())));
        bake(a->s, rig, character);  // the tracks the session implies (an authored file is already baked)
        a->state = bytes_of(a->s);
        a->steps.push_back({a->state, {}, {}});
        return a.release();
    } catch (const std::exception& e) {
        fail(CM_ERR_FORMAT, e.what());
        return nullptr;
    }
}

void cm_author_destroy(cm_author* a) { delete a; }

cc_bytes* cm_author_write(const cm_author* a) {
    if (a == nullptr) {
        fail(CM_ERR_ARG, "cm_author_write: null session");
        return nullptr;
    }
    auto* b = new cc_bytes();
    b->data = a->state;
    return b;
}

const cm_clip* cm_author_clip(const cm_author* a) {
    if (a == nullptr) return nullptr;
    try {
        const_cast<cm_author*>(a)->refresh();
    } catch (const std::exception& e) {
        fail(CM_ERR_ARG, e.what());
        return nullptr;
    }
    return a->output;
}

int32_t cm_author_apply(cm_author* a, const char* ops_json, int64_t length, const char* label, const char* merge_key) {
    if (a == nullptr || ops_json == nullptr || length < 0) return fail(CM_ERR_ARG, "cm_author_apply: null argument");
    try {
        oz::Session next = a->s;
        KernelHost host(a->rig, a->character);
        const std::vector<std::string> lines = oz::apply(next, std::string_view(ops_json, static_cast<size_t>(length)), host);
        bake(next, a->rig, a->character);
        std::vector<std::byte> state = bytes_of(next);
        std::string results = "[";
        for (size_t i = 0; i < lines.size(); ++i) results += (i ? "," : "") + quote(lines[i]);
        a->results = results + "]";
        if (state == a->state) return static_cast<int32_t>(lines.size());
        const std::string key = merge_key == nullptr ? "" : merge_key;
        const bool merge = !a->sealed && !key.empty() && a->cursor > 0 &&
                           a->cursor == static_cast<int32_t>(a->steps.size()) - 1 &&
                           a->steps[static_cast<size_t>(a->cursor)].merge_key == key;
        a->s = std::move(next);
        a->state = std::move(state);
        if (merge) {
            a->steps[static_cast<size_t>(a->cursor)].state = a->state;
            if (a->saved == a->cursor) a->saved = -1;
        } else {
            a->steps.resize(static_cast<size_t>(a->cursor) + 1);
            if (a->saved > a->cursor) a->saved = -1;
            a->steps.push_back({a->state, label != nullptr && label[0] != '\0' ? label : "edit animation", key});
            ++a->cursor;
            while (a->steps.size() > 201) {
                a->steps.erase(a->steps.begin());
                --a->cursor;
                a->saved = a->saved > 0 ? a->saved - 1 : -1;
            }
            a->sealed = key.empty();
        }
        ++a->revision;
        return static_cast<int32_t>(lines.size());
    } catch (const cenda::omformat::format_error& e) {
        return fail(CM_ERR_FORMAT, e.what());
    } catch (const std::exception& e) {
        return fail(CM_ERR_ARG, e.what());
    }
}

const char* cm_author_results(const cm_author* a) { return a == nullptr ? "[]" : a->results.c_str(); }

const char* cm_author_view(const cm_author* a) {
    if (a == nullptr) return "{}";
    try {
        const_cast<cm_author*>(a)->refresh();
    } catch (const std::exception& e) {
        fail(CM_ERR_ARG, e.what());
        return "{}";
    }
    return a->view.c_str();
}

int32_t cm_author_pose(const cm_author* a, float time, int32_t stage, float* out_local) {
    if (a == nullptr || out_local == nullptr) return fail(CM_ERR_ARG, "cm_author_pose: null argument");
    try {
        auto* m = const_cast<cm_author*>(a);
        m->refresh();
        const float* v = m->evaluator->pose(time, stage);
        std::memcpy(out_local, v, static_cast<size_t>(cr_rig_joint_count(a->rig)) * 9 * sizeof(float));
        return CM_OK;
    } catch (const std::exception& e) {
        return fail(CM_ERR_ARG, e.what());
    }
}

int32_t cm_author_compose(const cm_author* a, float time, int32_t stage, const float* base_matrix, float* out_skin,
                          float* out_world) {
    if (a == nullptr) return fail(CM_ERR_ARG, "cm_author_compose: null session");
    std::vector<float> local(static_cast<size_t>(cr_rig_joint_count(a->rig)) * 9);
    const int32_t rc = cm_author_pose(a, time, stage, local.data());
    if (rc != CM_OK) return rc;
    if (cr_rig_pose_locals(a->rig, local.data(), nullptr, 0, base_matrix, out_skin, out_world, nullptr) != CR_OK) {
        return fail(CM_ERR_ARG, std::string("cm_author_compose: ") + cr_last_error());
    }
    return CM_OK;
}

const char* cm_author_handles(const cm_author* a, float time) {
    if (a == nullptr) return "[]";
    try {
        auto* m = const_cast<cm_author*>(a);
        m->refresh();
        Evaluator& ev = *m->evaluator;
        std::string out = "[";
        std::vector<float> skin, w;
        for (size_t i = 0; i < a->s.effectors.size(); ++i) {
            const oz::Effector& e = a->s.effectors[i];
            const Evaluator::Goal g = Evaluator::goal_at(e, time);
            ev.world(ev.pose(time, CM_STAGE_FINAL, static_cast<int>(i)), skin, w);
            const auto end = ev.point(ev.chains[i].back(), w);
            char buf[512];
            std::snprintf(buf, sizeof buf, "%s{\"effector\":%s,\"kind\":%s,\"target\":[%g,%g,%g],\"weight\":%g,\"end\":[%g,%g,%g]",
                          i ? "," : "", quote(e.name).c_str(), quote(e.kind).c_str(), g.pos[0], g.pos[1], g.pos[2], g.weight,
                          end[0], end[1], end[2]);
            out += buf;
            if (g.has_pole) {
                std::snprintf(buf, sizeof buf, ",\"pole\":[%g,%g,%g]", g.pole[0], g.pole[1], g.pole[2]);
                out += buf;
            }
            out += "}";
        }
        m->handles = out + "]";
        return m->handles.c_str();
    } catch (const std::exception& e) {
        fail(CM_ERR_ARG, e.what());
        return "[]";
    }
}

int32_t cm_author_layer_value(const cm_author* a, int32_t layer, int32_t joint, float time, float* out9) {
    if (a == nullptr || out9 == nullptr) return fail(CM_ERR_ARG, "cm_author_layer_value: null argument");
    if (layer < 0 || layer >= static_cast<int32_t>(a->s.layers.size())) return fail(CM_ERR_RANGE, "cm_author_layer_value: no such layer");
    if (joint < 0 || joint >= cr_rig_joint_count(a->rig)) return fail(CM_ERR_RANGE, "cm_author_layer_value: no such joint");
    try {
        auto* m = const_cast<cm_author*>(a);
        m->refresh();
        std::vector<float> locals(static_cast<size_t>(cr_rig_joint_count(a->rig)) * 9);
        cr_rig_local_pose(a->rig, m->evaluator->clips[static_cast<size_t>(layer)], time, nullptr, 0, locals.data());
        std::memcpy(out9, locals.data() + static_cast<size_t>(joint) * 9, 9 * sizeof(float));
        return CM_OK;
    } catch (const std::exception& e) {
        return fail(CM_ERR_ARG, e.what());
    }
}

int32_t cm_author_root_motion(const cm_author* a, float time, float* out4) {
    if (a == nullptr || out4 == nullptr) return fail(CM_ERR_ARG, "cm_author_root_motion: null argument");
    std::fill(out4, out4 + 4, 0.f);
    const oa::RootMotion& rm = a->s.clip.root_motion;
    if (rm.joint.empty() || rm.axes == 0) return CM_OK;
    try {
        auto* m = const_cast<cm_author*>(a);
        m->refresh();
        Evaluator& ev = *m->evaluator;
        const int32_t j = ev.resolve(rm.joint);  // a joint id or "role:<role>" (kRolePrefix)
        if (j < 0) return CM_OK;
        std::vector<float> skin, w0, w1;
        ev.world(ev.pose(0.f, CM_STAGE_FINAL), skin, w0);
        ev.world(ev.pose(time, CM_STAGE_FINAL), skin, w1);
        const auto p0 = ev.point(j, w0), p1 = ev.point(j, w1);
        const uint32_t bits[3] = {oa::kRootX, oa::kRootY, oa::kRootZ};
        for (int i = 0; i < 3; ++i) {
            if (rm.axes & bits[i]) out4[i] = static_cast<float>(p1[static_cast<size_t>(i)] - p0[static_cast<size_t>(i)]);
        }
        if (rm.axes & oa::kRootYaw) {
            // Heading of the joint's +Z axis projected on the ground plane.
            const float* a0 = w0.data() + static_cast<size_t>(j) * 16 + 8;
            const float* a1 = w1.data() + static_cast<size_t>(j) * 16 + 8;
            const double y0 = std::atan2(static_cast<double>(a0[0]), static_cast<double>(a0[2]));
            const double y1 = std::atan2(static_cast<double>(a1[0]), static_cast<double>(a1[2]));
            double d = (y1 - y0) / kDeg;
            while (d > 180.0) d -= 360.0;
            while (d < -180.0) d += 360.0;
            out4[3] = static_cast<float>(d);
        }
        return CM_OK;
    } catch (const std::exception& e) {
        return fail(CM_ERR_ARG, e.what());
    }
}

int32_t cm_author_import_retarget(cm_author* a, const cm_clip* source, cc_retargeter* retargeter, float fps,
                                  const char* layer) {
    if (a == nullptr || source == nullptr || retargeter == nullptr) return fail(CM_ERR_ARG, "cm_author_import_retarget: null argument");
    int32_t rc = 0;
    cm_clip* baked = cc_retarget_bake(retargeter, source, fps, &rc);
    if (baked == nullptr) return fail(CM_ERR_ARG, std::string("cm_author_import_retarget: ") + cc_last_error());
    cc_bytes* b = cm_clip_write(baked);
    cm_clip_destroy(baked);
    if (b == nullptr) return fail(CM_ERR_ARG, std::string("cm_author_import_retarget: ") + cm_last_error());
    try {
        const oa::Clip c = oa::read(std::span<const std::byte>(b->data.data(), b->data.size()));
        cc_bytes_destroy(b);
        const std::string name = layer == nullptr ? "Retarget" : layer;
        // Through apply so it is one undo step: a layer op, then the tracks set directly below.
        oz::Session next = a->s;
        int li = oz::find_layer(next, name);
        if (li < 0) {
            oz::AuthorLayer l;
            l.name = name;
            next.layers.push_back(std::move(l));
            li = static_cast<int>(next.layers.size()) - 1;
        }
        oz::AuthorLayer& l = next.layers[static_cast<size_t>(li)];
        l.clip.tracks = c.tracks;
        if (c.duration > next.clip.duration) next.clip.duration = c.duration;
        l.clip.fps = next.clip.fps;
        l.clip.duration = next.clip.duration;
        bake(next, a->rig, a->character);
        a->s = std::move(next);
        a->state = bytes_of(a->s);
        a->steps.resize(static_cast<size_t>(a->cursor) + 1);
        if (a->saved > a->cursor) a->saved = -1;
        a->steps.push_back({a->state, "retarget " + name, {}});
        ++a->cursor;
        a->sealed = true;
        ++a->revision;
        return CM_OK;
    } catch (const std::exception& e) {
        return fail(CM_ERR_FORMAT, e.what());
    }
}

int32_t cm_author_undo(cm_author* a) {
    if (a == nullptr) return fail(CM_ERR_ARG, "cm_author_undo: null session");
    if (a->cursor <= 0) return fail(CM_ERR_RANGE, "cm_author_undo: nothing to undo");
    try {
        a->restore(a->steps[static_cast<size_t>(a->cursor) - 1].state);
    } catch (const std::exception& e) {
        return fail(CM_ERR_ARG, e.what());
    }
    --a->cursor;
    a->sealed = true;
    ++a->revision;
    return CM_OK;
}

int32_t cm_author_redo(cm_author* a) {
    if (a == nullptr) return fail(CM_ERR_ARG, "cm_author_redo: null session");
    if (a->cursor + 1 >= static_cast<int32_t>(a->steps.size())) return fail(CM_ERR_RANGE, "cm_author_redo: nothing to redo");
    try {
        a->restore(a->steps[static_cast<size_t>(a->cursor) + 1].state);
    } catch (const std::exception& e) {
        return fail(CM_ERR_ARG, e.what());
    }
    ++a->cursor;
    a->sealed = true;
    ++a->revision;
    return CM_OK;
}

int32_t cm_author_can_undo(const cm_author* a) { return a != nullptr && a->cursor > 0 ? 1 : 0; }

int32_t cm_author_can_redo(const cm_author* a) {
    return a != nullptr && a->cursor + 1 < static_cast<int32_t>(a->steps.size()) ? 1 : 0;
}

const char* cm_author_label(const cm_author* a, int32_t which) {
    if (a == nullptr) return "";
    if (which == 0) return a->cursor > 0 ? a->steps[static_cast<size_t>(a->cursor)].label.c_str() : "";
    if (which == 1 && a->cursor + 1 < static_cast<int32_t>(a->steps.size())) return a->steps[static_cast<size_t>(a->cursor) + 1].label.c_str();
    return "";
}

int32_t cm_author_seal(cm_author* a) {
    if (a == nullptr) return fail(CM_ERR_ARG, "cm_author_seal: null session");
    a->sealed = true;
    return CM_OK;
}

int64_t cm_author_revision(const cm_author* a) { return a == nullptr ? 0 : a->revision; }

int32_t cm_author_dirty(const cm_author* a) { return a != nullptr && a->saved != a->cursor ? 1 : 0; }

int32_t cm_author_mark_saved(cm_author* a) {
    if (a == nullptr) return fail(CM_ERR_ARG, "cm_author_mark_saved: null session");
    a->saved = a->cursor;
    return CM_OK;
}

int32_t cm_author_step_count(const cm_author* a) { return a == nullptr ? 0 : static_cast<int32_t>(a->steps.size()) - 1; }

}  // extern "C"
