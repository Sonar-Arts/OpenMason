// Cenda rig: rigs (joint forests), clips, sampling/layering and posing. Contract in cenda/rig.h.
//
// Sampling, time wrapping, easing, angle lerp and the layering fold run in float with the exact
// operation order of the retired Java AnimSampler / AnimLayering / Easing (so a sampled local pose is
// bit-identical to the Java one); matrix composition runs in double and is rounded once at the end.

#include "rig_internal.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <new>

namespace cenda::rig {

namespace {
thread_local std::string g_error;
std::atomic<uint64_t> g_clip_serial{1};
constexpr size_t MAX_BINDINGS = 64;
}  // namespace

void set_error(const std::string& message) { g_error = message; }

std::string lower(const std::string& s) {
    std::string out(s);
    for (char& c : out) {
        if (c >= 'A' && c <= 'Z') c = static_cast<char>(c - 'A' + 'a');
    }
    return out;
}

namespace {

std::string str_or_empty(const char* const* arr, int32_t i) {
    if (arr == nullptr || arr[i] == nullptr) return std::string();
    return std::string(arr[i]);
}

// ---- float sampling (Java parity) -----------------------------------------------------------

float ease(int32_t easing, float t) {
    const float x = t < 0.f ? 0.f : (t > 1.f ? 1.f : t);
    switch (easing) {
    case CR_EASE_IN: return x * x * x;
    case CR_EASE_OUT: {
        const float inv = 1.f - x;
        return 1.f - inv * inv * inv;
    }
    case CR_EASE_IN_OUT: {
        if (x < 0.5f) return 4.f * x * x * x;
        const float v = -2.f * x + 2.f;
        return 1.f - (v * v * v) / 2.f;
    }
    case CR_EASE_STEP: return x >= 1.f ? 1.f : 0.f;
    default: return x;
    }
}

}  // namespace

float lerp_angle(float a, float b, float u) {
    const float delta = std::fmod(std::fmod(b - a, 360.f) + 540.f, 360.f) - 180.f;
    return a + delta * u;
}

namespace {

void pose_of(const cr_key& k, Pose& out) {
    std::memcpy(out.pos, k.pos, sizeof out.pos);
    std::memcpy(out.rot, k.rot_deg, sizeof out.rot);
    std::memcpy(out.scale, k.scale, sizeof out.scale);
}

}  // namespace

void sample_track(const cr_clip& clip, int32_t track, float t, Pose& out) {
    const cr_track_desc& td = clip.tracks[static_cast<size_t>(track)];
    const cr_key* keys = clip.keys.data() + td.first_key;
    const int32_t n = td.key_count;
    const cr_key& first = keys[0];
    if (t <= first.time || n == 1) {
        pose_of(first, out);
        return;
    }
    const cr_key& last = keys[n - 1];
    if (t >= last.time) {
        pose_of(last, out);
        return;
    }
    for (int32_t i = 0; i < n - 1; ++i) {
        const cr_key& a = keys[i];
        const cr_key& b = keys[i + 1];
        if (t >= a.time && t <= b.time) {
            const float span = b.time - a.time;
            const float u = span <= 0.f ? 0.f : (t - a.time) / span;
            const float e = ease(a.easing, u);
            for (int c = 0; c < 3; ++c) {
                out.pos[c] = a.pos[c] + (b.pos[c] - a.pos[c]) * e;
                out.rot[c] = lerp_angle(a.rot_deg[c], b.rot_deg[c], e);
                out.scale[c] = a.scale[c] + (b.scale[c] - a.scale[c]) * e;
            }
            return;
        }
    }
    pose_of(last, out);
}

void lerp_pose(Pose& a, const Pose& b, float w) {
    if (w <= 0.f) return;
    if (w >= 1.f) {
        a = b;
        return;
    }
    for (int c = 0; c < 3; ++c) {
        a.pos[c] = a.pos[c] + (b.pos[c] - a.pos[c]) * w;
        a.rot[c] = lerp_angle(a.rot[c], b.rot[c], w);
        a.scale[c] = a.scale[c] + (b.scale[c] - a.scale[c]) * w;
    }
}

float wrap_time(float elapsed, float duration, int32_t loop) {
    if (duration <= 0.f) return 0.f;
    if (loop != 0) {
        const float t = std::fmod(elapsed, duration);
        return t < 0.f ? t + duration : t;
    }
    return std::min(std::max(elapsed, 0.f), duration);
}

float clip_weight(const cr_clip& clip, float elapsed) {
    const float fade_in = clip.desc.fade_in;
    const float fade_out = clip.desc.fade_out;
    const float in = fade_in <= 0.f ? 1.f : std::min(elapsed / fade_in, 1.f);
    float out = 1.f;
    if (clip.desc.loop == 0 && clip.desc.duration > 0.f && fade_out > 0.f) {
        const float remaining = clip.desc.duration - elapsed;
        out = std::min(remaining / fade_out, 1.f);
    }
    const float v = std::min(in, out);
    return v < 0.f ? 0.f : (v > 1.f ? 1.f : v);
}

namespace {

// ---- matrices --------------------------------------------------------------------------------

Mat4 local_matrix(const cenda::rig::Joint& j, const float pos[3], const float rot[3], const float scale[3],
                  double pre_yaw_rad, double pre_pitch_rad) {
    double r[9];
    rot_xyz(rot[0] * DEG_TO_RAD, rot[1] * DEG_TO_RAD, rot[2] * DEG_TO_RAD, r);
    if (pre_yaw_rad != 0.0 || pre_pitch_rad != 0.0) {
        double pre[9];
        rot_xyz(pre_pitch_rad, 0.0, 0.0, pre);  // Rx(pitch)
        double yaw[9];
        rot_xyz(0.0, pre_yaw_rad, 0.0, yaw);     // Ry(yaw)
        double ry_rx[9];
        mul3(yaw, pre, ry_rx);                   // Ry·Rx
        mul3(ry_rx, r, r);                       // Ry·Rx·R
    }
    double a[9];
    for (int row = 0; row < 3; ++row) {
        for (int col = 0; col < 3; ++col) a[row * 3 + col] = r[row * 3 + col] * scale[col];
    }
    Mat4 m = Mat4::identity();
    for (int row = 0; row < 3; ++row) {
        for (int col = 0; col < 3; ++col) m.at(row, col) = a[row * 3 + col];
    }
    const double ox = j.origin[0], oy = j.origin[1], oz = j.origin[2];
    if (j.kind == CR_JOINT_BONE) {
        m.at(0, 3) = ox + pos[0];
        m.at(1, 3) = oy + pos[1];
        m.at(2, 3) = oz + pos[2];
    } else {
        // T(pos)·T(origin)·A·T(-origin): translation = pos + origin - A·origin
        for (int row = 0; row < 3; ++row) {
            const double ao = a[row * 3 + 0] * ox + a[row * 3 + 1] * oy + a[row * 3 + 2] * oz;
            m.at(row, 3) = static_cast<double>(pos[row]) + j.origin[row] - ao;
        }
    }
    return m;
}

Mat4 child_frame(const cenda::rig::Joint& j, const Mat4& world) {
    if (j.kind != CR_JOINT_BONE) return world;
    return world.translated(j.endpoint[0], j.endpoint[1], j.endpoint[2]);
}

// The binding of a clip onto a rig (cached on the rig).
Binding make_binding(const cr_rig& rig, const cr_clip& clip) {
    Binding b;
    b.clip_serial = clip.serial;
    const size_t n = rig.joints.size();
    b.track.assign(n, -1);
    b.masked.assign(n, 0);
    for (size_t i = 0; i < n; ++i) {
        const auto& j = rig.joints[i];
        int32_t t = -1;
        if (auto it = clip.track_by_id.find(j.id); !j.id.empty() && it != clip.track_by_id.end()) {
            t = it->second;
        } else if (auto it2 = clip.track_by_name.find(j.name); !j.name.empty() && it2 != clip.track_by_name.end()) {
            t = it2->second;
        } else if (auto it3 = clip.track_by_name_lower.find(j.name_lower);
                   !j.name.empty() && it3 != clip.track_by_name_lower.end()) {
            t = it3->second;
        }
        if (t >= 0 && clip.tracks[static_cast<size_t>(t)].key_count <= 0) t = -1;
        b.track[i] = t;
        bool masked = clip.mask.empty();
        for (size_t m = 0; !masked && m < clip.mask.size(); ++m) {
            if ((!j.name.empty() && clip.mask_lower[m] == j.name_lower) || (!j.id.empty() && clip.mask[m] == j.id)) {
                masked = true;
            }
        }
        b.masked[i] = masked ? 1 : 0;
    }
    return b;
}

}  // namespace

// Copy of the binding (tiny: two small vectors); the cache stays locked only for the lookup.
Binding binding_for(const cr_rig& rig, const cr_clip& clip) {
    Binding b;
    binding_into(rig, clip, b);
    return b;
}

void binding_into(const cr_rig& rig, const cr_clip& clip, Binding& out) {
    {
        std::lock_guard<std::mutex> lock(rig.binding_mutex);
        for (auto it = rig.bindings.begin(); it != rig.bindings.end(); ++it) {
            if (it->clip_serial == clip.serial) {
                rig.bindings.splice(rig.bindings.begin(), rig.bindings, it);
                const Binding& hit = rig.bindings.front();
                out.clip_serial = hit.clip_serial;
                out.track.assign(hit.track.begin(), hit.track.end());
                out.masked.assign(hit.masked.begin(), hit.masked.end());
                return;
            }
        }
    }
    Binding b = make_binding(rig, clip);
    out.clip_serial = b.clip_serial;
    out.track.assign(b.track.begin(), b.track.end());
    out.masked.assign(b.masked.begin(), b.masked.end());
    std::lock_guard<std::mutex> lock(rig.binding_mutex);
    rig.bindings.push_front(std::move(b));
    if (rig.bindings.size() > MAX_BINDINGS) rig.bindings.pop_back();
}

// ---- pose ops shared with pose.cpp -----------------------------------------------------------

void pose_rest(const cr_rig& rig, PoseBuf& dst) {
    const size_t n = rig.joints.size();
    dst.v.resize(n);
    dst.driven.assign(n, 0);
    for (size_t i = 0; i < n; ++i) {
        const auto& j = rig.joints[i];
        std::memcpy(dst.v[i].pos, j.pos, sizeof j.pos);
        std::memcpy(dst.v[i].rot, j.rot, sizeof j.rot);
        std::memcpy(dst.v[i].scale, j.scale, sizeof j.scale);
    }
}

void pose_sample(const cr_rig& rig, const cr_clip& clip, float elapsed, bool clip_mask, PoseBuf& dst,
                 Binding& scratch) {
    pose_rest(rig, dst);
    const float t = wrap_time(elapsed, clip.desc.duration, clip.desc.loop);
    binding_into(rig, clip, scratch);
    const size_t n = rig.joints.size();
    for (size_t i = 0; i < n; ++i) {
        if (scratch.track[i] < 0 || (clip_mask && !scratch.masked[i])) continue;
        sample_track(clip, scratch.track[i], t, dst.v[i]);
        dst.driven[i] = 1;
    }
}

namespace {

// The rest values of one joint.
Pose rest_of(const Joint& j) {
    Pose p;
    std::memcpy(p.pos, j.pos, sizeof j.pos);
    std::memcpy(p.rot, j.rot, sizeof j.rot);
    std::memcpy(p.scale, j.scale, sizeof j.scale);
    return p;
}

}  // namespace

void pose_layer_override(const cr_rig& rig, PoseBuf& dst, const PoseBuf& src, float weight, const float* mask,
                         uint32_t flags) {
    const size_t n = rig.joints.size();
    const bool passthrough = (flags & CR_POSE_NO_PASSTHROUGH) == 0;
    const bool quat = (flags & CR_POSE_QUAT) != 0;
    for (size_t i = 0; i < n; ++i) {
        if (!src.driven[i]) continue;
        // The ABI 1 overlay fold: lerp weight = min(weight, 1), lerp_pose short-circuits 0 and 1.
        float w = mask != nullptr ? weight * mask[i] : weight;
        w = std::min(w, 1.f);
        if (w <= 0.f) continue;
        if (!passthrough) dst.v[i] = rest_of(rig.joints[i]);
        if (quat) {
            lerp_pose_quat(dst.v[i], src.v[i], w);
        } else {
            lerp_pose(dst.v[i], src.v[i], w);
        }
        dst.driven[i] = 1;
    }
}

namespace {

struct LayerFrame {
    const cr_clip* clip;
    float elapsed; // unwrapped (pose_sample wraps)
    float weight;  // caller weight * clip envelope
    int32_t priority;
};

// Local pose per joint for an ABI 1 frame: sample the base, then one override layer per overlay in
// ascending priority. Returns false on a bad argument.
bool local_poses(const cr_rig& rig, const cr_clip* base_clip, float base_time, const cr_layer* overlays,
                 int32_t overlay_count, PoseBuf& out) {
    thread_local Binding scratch;
    thread_local PoseBuf layer;
    thread_local std::vector<LayerFrame> frames;
    if (base_clip != nullptr) {
        pose_sample(rig, *base_clip, base_time, false, out, scratch);
    } else {
        pose_rest(rig, out);
    }
    if (overlay_count > 0) {
        if (overlays == nullptr) return false;
        frames.clear();
        for (int32_t o = 0; o < overlay_count; ++o) {
            const cr_layer& l = overlays[o];
            if (l.clip == nullptr) continue;
            const float w = l.weight * clip_weight(*l.clip, l.time);
            if (w <= 0.f) continue;
            frames.push_back(LayerFrame{l.clip, l.time, w, l.clip->desc.priority});
        }
        std::stable_sort(frames.begin(), frames.end(),
                         [](const LayerFrame& a, const LayerFrame& b) { return a.priority < b.priority; });
        for (const LayerFrame& f : frames) {
            pose_sample(rig, *f.clip, f.elapsed, true, layer, scratch);
            pose_layer_override(rig, out, layer, f.weight, nullptr, 0);
        }
    }
    return true;
}

bool build_order(cr_rig& rig) {
    const int32_t n = static_cast<int32_t>(rig.joints.size());
    std::vector<uint8_t> state(static_cast<size_t>(n), 0);  // 0 new, 1 visiting, 2 done
    rig.order.clear();
    rig.order.reserve(static_cast<size_t>(n));
    std::vector<int32_t> chain;
    for (int32_t start = 0; start < n; ++start) {
        if (state[static_cast<size_t>(start)] == 2) continue;
        chain.clear();
        int32_t cur = start;
        while (cur >= 0 && state[static_cast<size_t>(cur)] == 0) {
            state[static_cast<size_t>(cur)] = 1;
            chain.push_back(cur);
            cur = rig.joints[static_cast<size_t>(cur)].parent;
        }
        if (cur >= 0 && state[static_cast<size_t>(cur)] == 1) {
            // Cycle: cut it at the joint that closes it.
            rig.joints[static_cast<size_t>(chain.back())].parent = -1;
            rig.flags |= CR_RIG_CYCLE_BROKEN;
        }
        for (auto it = chain.rbegin(); it != chain.rend(); ++it) {
            state[static_cast<size_t>(*it)] = 2;
            rig.order.push_back(*it);
        }
    }
    return true;
}

}  // namespace
}  // namespace cenda::rig

using namespace cenda::rig;

extern "C" {

int32_t cr_abi_version(void) { return CR_ABI_VERSION; }

const char* cr_last_error(void) { return g_error.c_str(); }

cr_rig* cr_rig_create(const cr_joint_desc* joints, int32_t count, const char* const* ids, const char* const* names) {
    if (joints == nullptr || count <= 0 || count > CR_MAX_JOINTS) {
        set_error("cr_rig_create: need 1.." + std::to_string(CR_MAX_JOINTS) + " joints, got " + std::to_string(count));
        return nullptr;
    }
    cr_rig* rig = new (std::nothrow) cr_rig();
    if (rig == nullptr) {
        set_error("cr_rig_create: out of memory");
        return nullptr;
    }
    rig->joints.resize(static_cast<size_t>(count));
    for (int32_t i = 0; i < count; ++i) {
        const cr_joint_desc& d = joints[i];
        Joint& j = rig->joints[static_cast<size_t>(i)];
        j.parent = d.parent;
        if (j.parent >= count || j.parent < -1 || j.parent == i) {
            if (j.parent != i) rig->flags |= CR_RIG_BAD_PARENT;
            j.parent = -1;
        }
        j.kind = d.kind == CR_JOINT_BONE ? CR_JOINT_BONE : CR_JOINT_PART;
        j.flags = d.flags;
        std::memcpy(j.origin, d.origin, sizeof j.origin);
        std::memcpy(j.pos, d.pos, sizeof j.pos);
        std::memcpy(j.rot, d.rot_deg, sizeof j.rot);
        std::memcpy(j.scale, d.scale, sizeof j.scale);
        std::memcpy(j.endpoint, d.endpoint, sizeof j.endpoint);
        j.id = str_or_empty(ids, i);
        j.name = str_or_empty(names, i);
        j.name_lower = lower(j.name);
        if (!j.id.empty()) rig->by_id.emplace(j.id, i);
        if (!j.name.empty()) {
            rig->by_name.emplace(j.name, i);
            rig->by_name_lower.emplace(j.name_lower, i);
        }
    }
    build_order(*rig);
    const size_t n = static_cast<size_t>(count);
    rig->rest_world.assign(n, Mat4::identity());
    rig->rest_child.assign(n, Mat4::identity());
    rig->rest_world_inv.assign(n, Mat4::identity());
    for (int32_t idx : rig->order) {
        const Joint& j = rig->joints[static_cast<size_t>(idx)];
        const Mat4 parent = j.parent >= 0 ? rig->rest_child[static_cast<size_t>(j.parent)] : Mat4::identity();
        const Mat4 w = parent * local_matrix(j, j.pos, j.rot, j.scale, 0.0, 0.0);
        rig->rest_world[static_cast<size_t>(idx)] = w;
        rig->rest_child[static_cast<size_t>(idx)] = child_frame(j, w);
        rig->rest_world_inv[static_cast<size_t>(idx)] = w.affine_inverse();
    }
    return rig;
}

void cr_rig_destroy(cr_rig* rig) { delete rig; }

int32_t cr_rig_joint_count(const cr_rig* rig) {
    return rig == nullptr ? 0 : static_cast<int32_t>(rig->joints.size());
}

uint32_t cr_rig_flags(const cr_rig* rig) { return rig == nullptr ? 0u : rig->flags; }

int32_t cr_rig_find(const cr_rig* rig, const char* id_or_name) {
    if (rig == nullptr || id_or_name == nullptr || id_or_name[0] == '\0') return -1;
    const std::string key(id_or_name);
    if (auto it = rig->by_id.find(key); it != rig->by_id.end()) return it->second;
    if (auto it = rig->by_name.find(key); it != rig->by_name.end()) return it->second;
    if (auto it = rig->by_name_lower.find(lower(key)); it != rig->by_name_lower.end()) return it->second;
    return -1;
}

int32_t cr_rig_find_head(const cr_rig* rig) {
    if (rig == nullptr) return -1;
    const size_t n = rig->joints.size();
    // Geometry below: walk children-before-parents (reverse topological order).
    std::vector<uint8_t> below(n, 0);
    for (auto it = rig->order.rbegin(); it != rig->order.rend(); ++it) {
        const Joint& j = rig->joints[static_cast<size_t>(*it)];
        if ((j.flags & CR_JOINT_HAS_GEOMETRY) != 0) below[static_cast<size_t>(*it)] = 1;
        if (below[static_cast<size_t>(*it)] && j.parent >= 0) below[static_cast<size_t>(j.parent)] = 1;
    }
    for (size_t i = 0; i < n; ++i) {
        const Joint& j = rig->joints[i];
        if (j.kind == CR_JOINT_BONE && below[i] && j.name_lower.find("head") != std::string::npos) {
            return static_cast<int32_t>(i);
        }
    }
    for (size_t i = 0; i < n; ++i) {
        const Joint& j = rig->joints[i];
        if (j.kind == CR_JOINT_PART && j.name_lower.find("head") != std::string::npos) {
            return static_cast<int32_t>(i);
        }
    }
    return -1;
}

int32_t cr_rig_rest_world(const cr_rig* rig, float* out_world, float* out_child_frame) {
    if (rig == nullptr) {
        set_error("cr_rig_rest_world: null rig");
        return CR_ERR_ARG;
    }
    for (size_t i = 0; i < rig->joints.size(); ++i) {
        if (out_world != nullptr) rig->rest_world[i].to_float(out_world + i * 16);
        if (out_child_frame != nullptr) rig->rest_child[i].to_float(out_child_frame + i * 16);
    }
    return CR_OK;
}

cr_clip* cr_clip_create(const cr_clip_desc* desc, const cr_track_desc* tracks, int32_t track_count,
                        const cr_key* keys, int32_t key_count, const char* const* track_ids,
                        const char* const* track_names, const char* const* mask, int32_t mask_count) {
    if (desc == nullptr || track_count < 0 || key_count < 0 || mask_count < 0
        || (track_count > 0 && tracks == nullptr) || (key_count > 0 && keys == nullptr)
        || (mask_count > 0 && mask == nullptr)) {
        set_error("cr_clip_create: bad arguments");
        return nullptr;
    }
    cr_clip* clip = new (std::nothrow) cr_clip();
    if (clip == nullptr) {
        set_error("cr_clip_create: out of memory");
        return nullptr;
    }
    clip->serial = g_clip_serial.fetch_add(1);
    clip->desc = *desc;
    if (clip->desc.fade_in < 0.f) clip->desc.fade_in = 0.f;
    if (clip->desc.fade_out < 0.f) clip->desc.fade_out = 0.f;
    clip->keys.assign(keys, keys + key_count);
    clip->tracks.assign(tracks, tracks + track_count);
    for (int32_t t = 0; t < track_count; ++t) {
        cr_track_desc& td = clip->tracks[static_cast<size_t>(t)];
        if (td.first_key < 0 || td.key_count < 0 || td.first_key + td.key_count > key_count) {
            set_error("cr_clip_create: track " + std::to_string(t) + " keys out of range");
            delete clip;
            return nullptr;
        }
        std::stable_sort(clip->keys.begin() + td.first_key, clip->keys.begin() + td.first_key + td.key_count,
                         [](const cr_key& a, const cr_key& b) { return a.time < b.time; });
        std::string id = str_or_empty(track_ids, t);
        std::string name = str_or_empty(track_names, t);
        if (!id.empty()) clip->track_by_id.emplace(id, t);  // first track per key wins
        if (!name.empty()) {
            clip->track_by_name.emplace(name, t);
            clip->track_by_name_lower.emplace(lower(name), t);
        }
        clip->track_ids.push_back(std::move(id));
        clip->track_names.push_back(std::move(name));
    }
    for (int32_t m = 0; m < mask_count; ++m) {
        std::string s = str_or_empty(mask, m);
        clip->mask_lower.push_back(lower(s));
        clip->mask.push_back(std::move(s));
    }
    return clip;
}

void cr_clip_destroy(cr_clip* clip) { delete clip; }

float cr_wrap_time(float elapsed, float duration, int32_t loop) { return wrap_time(elapsed, duration, loop); }

float cr_clip_weight(const cr_clip* clip, float elapsed) { return clip == nullptr ? 0.f : clip_weight(*clip, elapsed); }

int32_t cr_clip_sample_track(const cr_clip* clip, int32_t track, float time, float* out_pos, float* out_rot_deg,
                             float* out_scale) {
    if (clip == nullptr || track < 0 || track >= static_cast<int32_t>(clip->tracks.size())
        || clip->tracks[static_cast<size_t>(track)].key_count <= 0) {
        set_error("cr_clip_sample_track: bad clip/track");
        return CR_ERR_ARG;
    }
    Pose p;
    sample_track(*clip, track, time, p);
    if (out_pos != nullptr) std::memcpy(out_pos, p.pos, sizeof p.pos);
    if (out_rot_deg != nullptr) std::memcpy(out_rot_deg, p.rot, sizeof p.rot);
    if (out_scale != nullptr) std::memcpy(out_scale, p.scale, sizeof p.scale);
    return CR_OK;
}

int32_t cr_clip_track_for(const cr_clip* clip, const cr_rig* rig, int32_t joint) {
    if (clip == nullptr || rig == nullptr || joint < 0 || joint >= static_cast<int32_t>(rig->joints.size())) return -1;
    return binding_for(*rig, *clip).track[static_cast<size_t>(joint)];
}

int32_t cr_rig_local_pose(const cr_rig* rig, const cr_clip* base_clip, float base_time, const cr_layer* overlays,
                          int32_t overlay_count, float* out_local) {
    if (rig == nullptr || out_local == nullptr || overlay_count < 0) {
        set_error("cr_rig_local_pose: bad arguments");
        return CR_ERR_ARG;
    }
    thread_local PoseBuf poses;
    if (!local_poses(*rig, base_clip, base_time, overlays, overlay_count, poses)) {
        set_error("cr_rig_local_pose: overlays null");
        return CR_ERR_ARG;
    }
    for (size_t i = 0; i < poses.v.size(); ++i) {
        float* o = out_local + i * 9;
        std::memcpy(o, poses.v[i].pos, 12);
        std::memcpy(o + 3, poses.v[i].rot, 12);
        std::memcpy(o + 6, poses.v[i].scale, 12);
    }
    return CR_OK;
}

}  // extern "C"

namespace cenda::rig {

int32_t compose_pose(const cr_rig* rig, const std::vector<Pose>& poses, const cr_joint_extra* extras,
                     int32_t extra_count, const float* base_matrix, float* out_skin, float* out_world,
                     float* out_child, const char* who) {
    thread_local std::vector<Mat4> world;
    thread_local std::vector<Mat4> child;
    thread_local std::vector<int32_t> extra_of;
    const size_t n = rig->joints.size();
    extra_of.assign(n, -1);
    for (int32_t e = 0; e < extra_count; ++e) {
        const int32_t j = extras[e].joint;
        if (j < 0 || j >= static_cast<int32_t>(n)) {
            set_error(std::string(who) + ": extra joint " + std::to_string(j) + " out of range");
            return CR_ERR_RANGE;
        }
        extra_of[static_cast<size_t>(j)] = e;
    }
    world.resize(n);
    child.resize(n);
    const Mat4 base = base_matrix != nullptr ? Mat4::from_float(base_matrix) : Mat4::identity();
    for (int32_t idx : rig->order) {
        const size_t i = static_cast<size_t>(idx);
        const Joint& j = rig->joints[i];
        const Pose& p = poses[i];
        double yaw = 0.0, pitch = 0.0;
        float rot[3] = {p.rot[0], p.rot[1], p.rot[2]};
        if (extra_of[i] >= 0) {
            const cr_joint_extra& ex = extras[extra_of[i]];
            yaw = ex.pre_yaw_deg * DEG_TO_RAD;
            pitch = ex.pre_pitch_deg * DEG_TO_RAD;
            for (int c = 0; c < 3; ++c) rot[c] = rot[c] + ex.add_rot_deg[c];
        }
        const Mat4 parent = j.parent >= 0 ? child[static_cast<size_t>(j.parent)] : Mat4::identity();
        world[i] = parent * local_matrix(j, p.pos, rot, p.scale, yaw, pitch);
        child[i] = child_frame(j, world[i]);
    }
    for (size_t i = 0; i < n; ++i) {
        if (out_skin != nullptr) (base * world[i] * rig->rest_world_inv[i]).to_float(out_skin + i * 16);
        if (out_world != nullptr) (base * world[i]).to_float(out_world + i * 16);
        if (out_child != nullptr) (base * child[i]).to_float(out_child + i * 16);
    }
    return CR_OK;
}

}  // namespace cenda::rig

extern "C" {

int32_t cr_rig_pose(const cr_rig* rig, const cr_clip* base_clip, float base_time, const cr_layer* overlays,
                    int32_t overlay_count, const cr_joint_extra* extras, int32_t extra_count,
                    const float* base_matrix, float* out_skin, float* out_world, float* out_child) {
    if (rig == nullptr || overlay_count < 0 || extra_count < 0 || (extra_count > 0 && extras == nullptr)) {
        set_error("cr_rig_pose: bad arguments");
        return CR_ERR_ARG;
    }
    thread_local PoseBuf poses;
    if (!local_poses(*rig, base_clip, base_time, overlays, overlay_count, poses)) {
        set_error("cr_rig_pose: overlays null");
        return CR_ERR_ARG;
    }
    return compose_pose(rig, poses.v, extras, extra_count, base_matrix, out_skin, out_world, out_child, "cr_rig_pose");
}

int32_t cr_rig_pose_locals(const cr_rig* rig, const float* locals, const cr_joint_extra* extras, int32_t extra_count,
                           const float* base_matrix, float* out_skin, float* out_world, float* out_child) {
    if (rig == nullptr || extra_count < 0 || (extra_count > 0 && extras == nullptr)) {
        set_error("cr_rig_pose_locals: bad arguments");
        return CR_ERR_ARG;
    }
    thread_local std::vector<Pose> poses;
    const size_t n = rig->joints.size();
    poses.resize(n);
    for (size_t i = 0; i < n; ++i) {
        const Joint& j = rig->joints[i];
        if (locals != nullptr) {
            std::memcpy(poses[i].pos, locals + i * 9, 12);
            std::memcpy(poses[i].rot, locals + i * 9 + 3, 12);
            std::memcpy(poses[i].scale, locals + i * 9 + 6, 12);
        } else {
            std::memcpy(poses[i].pos, j.pos, 12);
            std::memcpy(poses[i].rot, j.rot, 12);
            std::memcpy(poses[i].scale, j.scale, 12);
        }
    }
    return compose_pose(rig, poses, extras, extra_count, base_matrix, out_skin, out_world, out_child,
                        "cr_rig_pose_locals");
}

}  // extern "C"
