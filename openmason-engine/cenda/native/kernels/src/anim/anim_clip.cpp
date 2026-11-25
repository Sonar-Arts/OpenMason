// Cenda animation clip documents (cm_*): the .omanim editing model and the clip-data queries. See
// cenda/anim_clip.h and omformat/omanim.hpp.
#include "cenda/anim_clip.h"

#include "../character/character_internal.hpp"
#include "omformat/omanim.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <span>
#include <string>
#include <unordered_map>
#include <vector>

namespace oa = cenda::omformat::omanim;

struct cm_clip {
    oa::Clip clip;
};

namespace {

thread_local std::string g_error;

int32_t fail(int32_t code, const std::string& message) {
    g_error = message;
    return code;
}

template <typename T>
void set_out(T* out, T value) {
    if (out != nullptr) *out = value;
}

template <typename T>
bool in_range(const std::vector<T>& v, int32_t i) {
    return i >= 0 && static_cast<std::size_t>(i) < v.size();
}

bool finite(float v) { return std::isfinite(v); }

#define CM_GUARD_BEGIN try {
#define CM_GUARD_END(fallback)          \
    }                                   \
    catch (const std::exception& e) {   \
        g_error = e.what();             \
        return fallback;                \
    }                                   \
    catch (...) {                       \
        g_error = "unknown error";      \
        return fallback;                \
    }

oa::Key key_from(const cr_key& k) {
    oa::Key out;
    out.time = k.time;
    std::memcpy(out.pos, k.pos, sizeof out.pos);
    std::memcpy(out.rot, k.rot_deg, sizeof out.rot);
    std::memcpy(out.scale, k.scale, sizeof out.scale);
    out.easing = k.easing >= CR_EASE_LINEAR && k.easing <= CR_EASE_STEP ? static_cast<oa::Easing>(k.easing) : oa::Easing::Linear;
    return out;
}

cr_key key_to(const oa::Key& k) {
    cr_key out{};
    out.time = k.time;
    std::memcpy(out.pos, k.pos, sizeof out.pos);
    std::memcpy(out.rot_deg, k.rot, sizeof out.rot_deg);
    std::memcpy(out.scale, k.scale, sizeof out.scale);
    out.easing = static_cast<int32_t>(k.easing);
    return out;
}

bool finite_key(const cr_key& k) {
    if (!finite(k.time)) return false;
    for (int i = 0; i < 3; ++i) {
        if (!finite(k.pos[i]) || !finite(k.rot_deg[i]) || !finite(k.scale[i])) return false;
    }
    return true;
}

// --- Notify firing --------------------------------------------------------------------------------------

struct Edge {
    float t;
    int32_t notify;
    int32_t phase;
};

std::vector<Edge> edges_of(const oa::Clip& c) {
    const float d = c.duration;
    std::vector<Edge> edges;
    auto place = [&](float t) {
        if (c.loop) {
            t = t - d * std::floor(t / d);
            if (t >= d) t = 0.f;  // floating rounding
            return t;
        }
        return std::clamp(t, 0.f, d);
    };
    for (std::size_t i = 0; i < c.notifies.size(); ++i) {
        const oa::Notify& n = c.notifies[i];
        const auto idx = static_cast<int32_t>(i);
        if (n.duration > 0.f) {
            edges.push_back({place(n.time), idx, CM_NOTIFY_BEGIN});
            edges.push_back({place(n.time + n.duration), idx, CM_NOTIFY_END});
        } else {
            edges.push_back({place(n.time), idx, CM_NOTIFY_POINT});
        }
    }
    std::stable_sort(edges.begin(), edges.end(), [](const Edge& a, const Edge& b) { return a.t < b.t; });
    return edges;
}

std::vector<Edge> fire(const oa::Clip& c, float prev, float delta) {
    std::vector<Edge> out;
    const float d = c.duration;
    if (!(d > 0.f) || !finite(prev) || !finite(delta) || delta == 0.f || c.notifies.empty()) return out;
    const std::vector<Edge> edges = edges_of(c);
    auto asc = [&](auto keep) {
        for (const Edge& e : edges) {
            if (keep(e.t)) out.push_back(e);
        }
    };
    auto desc = [&](auto keep) {
        for (auto it = edges.rbegin(); it != edges.rend(); ++it) {
            if (keep(it->t)) out.push_back(*it);
        }
    };
    if (c.loop) {
        float t0 = prev - d * std::floor(prev / d);
        if (t0 >= d) t0 = 0.f;
        if (std::abs(delta) >= d) {  // a whole cycle or more: each edge once, in playback order
            if (delta > 0) {
                asc([&](float t) { return t >= t0; });
                asc([&](float t) { return t < t0; });
            } else {
                desc([&](float t) { return t <= t0; });
                desc([&](float t) { return t > t0; });
            }
        } else if (delta > 0) {
            const float t1 = t0 + delta;
            if (t1 < d) {
                asc([&](float t) { return t >= t0 && t < t1; });
            } else {
                asc([&](float t) { return t >= t0; });
                asc([&](float t) { return t < t1 - d; });
            }
        } else {
            const float t1 = t0 + delta;
            if (t1 >= 0.f) {
                desc([&](float t) { return t > t1 && t <= t0; });
            } else {
                desc([&](float t) { return t <= t0; });
                desc([&](float t) { return t > t1 + d; });
            }
        }
    } else {
        const float t0 = std::clamp(prev, 0.f, d);
        const float t1 = std::clamp(t0 + delta, 0.f, d);
        if (delta > 0) {
            asc([&](float t) { return (t >= t0 && t < t1) || (t1 == d && t == d && t0 < d); });
        } else {
            desc([&](float t) { return (t > t1 && t <= t0) || (t1 == 0.f && t == 0.f && t0 > 0.f); });
        }
    }
    return out;
}

// --- Curves and markers ---------------------------------------------------------------------------------

float sample(const oa::Curve& curve, float t) {
    const auto& k = curve.keys;
    if (k.empty()) return 0.f;
    if (t <= k.front().time) return k.front().value;
    if (t >= k.back().time) return k.back().value;
    std::size_t i = 0;
    while (i + 1 < k.size() && k[i + 1].time <= t) ++i;
    const oa::CurveKey& a = k[i];
    const oa::CurveKey& b = k[i + 1];
    const double dt = static_cast<double>(b.time) - a.time;
    if (dt <= 0.0) return b.value;
    const double s = (static_cast<double>(t) - a.time) / dt;
    switch (a.interp) {
        case oa::CurveInterp::Step: return a.value;
        case oa::CurveInterp::Cubic: {
            const double s2 = s * s;
            const double s3 = s2 * s;
            const double v = (2 * s3 - 3 * s2 + 1) * a.value + (s3 - 2 * s2 + s) * dt * a.out_tangent +
                             (-2 * s3 + 3 * s2) * b.value + (s3 - s2) * dt * b.in_tangent;
            return static_cast<float>(v);
        }
        default: return static_cast<float>(a.value + (static_cast<double>(b.value) - a.value) * s);
    }
}

// The span [start, end) a marker index covers (marker -1 = the clip start in a non-looping clip).
void marker_span(const oa::Clip& c, int32_t p, double& start, double& end) {
    const auto& m = c.markers;
    const auto n = static_cast<int32_t>(m.size());
    const double d = c.duration;
    if (p < 0) {
        start = 0.0;
        end = n > 0 ? m[0].time : d;
        return;
    }
    start = m[static_cast<std::size_t>(p)].time;
    if (p + 1 < n) {
        end = m[static_cast<std::size_t>(p + 1)].time;
    } else {
        end = c.loop ? m[0].time + d : d;
    }
}

}  // namespace

extern "C" {

int32_t cm_abi_version(void) { return CM_ABI_VERSION; }

const char* cm_last_error(void) { return g_error.c_str(); }

cm_clip* cm_clip_create(void) {
    CM_GUARD_BEGIN
    g_error.clear();
    return new cm_clip();
    CM_GUARD_END(nullptr)
}

cm_clip* cm_clip_read(const uint8_t* bytes, int64_t length, int32_t* out_result) {
    set_out(out_result, int32_t{CM_OK});
    if (bytes == nullptr || length <= 0) {
        set_out(out_result, int32_t{CM_ERR_ARG});
        fail(CM_ERR_ARG, "cm_clip_read: no bytes");
        return nullptr;
    }
    try {
        g_error.clear();
        return new cm_clip{oa::read(std::span<const std::byte>(reinterpret_cast<const std::byte*>(bytes),
                                                               static_cast<std::size_t>(length)))};
    } catch (const std::exception& e) {
        set_out(out_result, int32_t{CM_ERR_FORMAT});
        fail(CM_ERR_FORMAT, e.what());
    }
    return nullptr;
}

cm_clip* cm_clip_clone(const cm_clip* c) {
    if (c == nullptr) return nullptr;
    CM_GUARD_BEGIN
    return new cm_clip(*c);
    CM_GUARD_END(nullptr)
}

void cm_clip_destroy(cm_clip* c) { delete c; }

cc_bytes* cm_clip_write(const cm_clip* c) {
    if (c == nullptr) {
        fail(CM_ERR_ARG, "cm_clip_write: null clip");
        return nullptr;
    }
    CM_GUARD_BEGIN
    g_error.clear();
    auto* b = new cc_bytes();
    try {
        b->data = oa::write(c->clip);
    } catch (...) {
        delete b;
        throw;
    }
    return b;
    CM_GUARD_END(nullptr)
}

// --- Header, strings, mask ----------------------------------------------------------------------------

int32_t cm_clip_header_get(const cm_clip* c, cm_header* out) {
    if (c == nullptr || out == nullptr) return fail(CM_ERR_ARG, "cm_clip_header_get: null argument");
    const oa::Clip& k = c->clip;
    *out = cm_header{k.fps, k.duration, k.loop ? 1 : 0, k.layer.present ? 1 : 0, k.layer.fade_in, k.layer.fade_out,
                     k.layer.priority};
    return CM_OK;
}

int32_t cm_clip_header_set(cm_clip* c, const cm_header* h) {
    if (c == nullptr || h == nullptr) return fail(CM_ERR_ARG, "cm_clip_header_set: null argument");
    if (!finite(h->fps) || !finite(h->duration) || !finite(h->fade_in) || !finite(h->fade_out) || h->fps <= 0.f ||
        h->duration < 0.f) {
        return fail(CM_ERR_ARG, "cm_clip_header_set: fps must be > 0, duration >= 0, all finite");
    }
    oa::Clip& k = c->clip;
    k.fps = h->fps;
    k.duration = h->duration;
    k.loop = h->loop != 0;
    k.layer.present = h->has_layer != 0;
    k.layer.fade_in = std::max(0.f, h->fade_in);
    k.layer.fade_out = std::max(0.f, h->fade_out);
    k.layer.priority = h->priority;
    return CM_OK;
}

const char* cm_clip_get_string(const cm_clip* c, int32_t field) {
    if (c == nullptr) return "";
    switch (field) {
        case CM_FIELD_NAME: return c->clip.name.c_str();
        case CM_FIELD_MODEL_REF: return c->clip.model_ref.c_str();
        case CM_FIELD_VERSION: return c->clip.version.c_str();
        case CM_FIELD_LAYER_TYPE: return c->clip.layer.type.c_str();
        default: return "";
    }
}

int32_t cm_clip_set_string(cm_clip* c, int32_t field, const char* value) {
    if (c == nullptr) return fail(CM_ERR_ARG, "cm_clip_set_string: null clip");
    switch (field) {
        case CM_FIELD_NAME: c->clip.name = value == nullptr ? "" : value; return CM_OK;
        case CM_FIELD_MODEL_REF:
            c->clip.model_ref_null = value == nullptr;
            c->clip.model_ref = value == nullptr ? "" : value;
            return CM_OK;
        case CM_FIELD_LAYER_TYPE: {
            std::string t = value == nullptr ? "" : value;
            std::transform(t.begin(), t.end(), t.begin(), [](unsigned char ch) { return static_cast<char>(std::toupper(ch)); });
            if (t != "BASE" && t != "OVERLAY") return fail(CM_ERR_ARG, "layer type must be BASE or OVERLAY");
            c->clip.layer.type = t;
            c->clip.layer.present = true;
            return CM_OK;
        }
        default: return fail(CM_ERR_ARG, "cm_clip_set_string: field " + std::to_string(field) + " is not settable");
    }
}

int32_t cm_clip_model_ref_null(const cm_clip* c) { return c != nullptr && c->clip.model_ref_null ? 1 : 0; }

int32_t cm_clip_mask_count(const cm_clip* c) { return c == nullptr ? 0 : static_cast<int32_t>(c->clip.layer.mask_parts.size()); }

const char* cm_clip_mask_get(const cm_clip* c, int32_t index) {
    if (c == nullptr || !in_range(c->clip.layer.mask_parts, index)) return nullptr;
    return c->clip.layer.mask_parts[static_cast<std::size_t>(index)].c_str();
}

int32_t cm_clip_mask_set(cm_clip* c, const char* const* parts, int32_t count) {
    if (c == nullptr || count < 0 || (count > 0 && parts == nullptr)) return fail(CM_ERR_ARG, "cm_clip_mask_set: bad argument");
    CM_GUARD_BEGIN
    std::vector<std::string> mask;
    for (int32_t i = 0; i < count; ++i) {
        if (parts[i] != nullptr && parts[i][0] != '\0') mask.emplace_back(parts[i]);
    }
    c->clip.layer.mask_parts = std::move(mask);
    c->clip.layer.present = true;
    return CM_OK;
    CM_GUARD_END(CM_ERR_ARG)
}

// --- Tracks -------------------------------------------------------------------------------------------

int32_t cm_clip_track_count(const cm_clip* c) { return c == nullptr ? 0 : static_cast<int32_t>(c->clip.tracks.size()); }

int32_t cm_clip_track_get(const cm_clip* c, int32_t index, const char** out_part_id, const char** out_part_name,
                          const char** out_role, int32_t* out_bone, int32_t* out_key_count) {
    if (c == nullptr) return fail(CM_ERR_ARG, "cm_clip_track_get: null clip");
    if (!in_range(c->clip.tracks, index)) return fail(CM_ERR_RANGE, "cm_clip_track_get: index out of range");
    const oa::Track& t = c->clip.tracks[static_cast<std::size_t>(index)];
    set_out(out_part_id, t.part_id.c_str());
    set_out(out_part_name, t.part_name.c_str());
    set_out(out_role, t.role.c_str());
    set_out(out_bone, t.bone ? int32_t{1} : int32_t{0});
    set_out(out_key_count, static_cast<int32_t>(t.keys.size()));
    return CM_OK;
}

int32_t cm_clip_track_keys(const cm_clip* c, int32_t index, cr_key* out, int32_t capacity) {
    if (c == nullptr || capacity < 0) return fail(CM_ERR_ARG, "cm_clip_track_keys: bad argument");
    if (!in_range(c->clip.tracks, index)) return fail(CM_ERR_RANGE, "cm_clip_track_keys: index out of range");
    const auto& keys = c->clip.tracks[static_cast<std::size_t>(index)].keys;
    const auto n = static_cast<int32_t>(keys.size());
    for (int32_t i = 0; out != nullptr && i < std::min(n, capacity); ++i) out[i] = key_to(keys[static_cast<std::size_t>(i)]);
    return n;
}

int32_t cm_clip_track_set(cm_clip* c, const char* part_id, const char* part_name, const char* role, int32_t bone,
                          const cr_key* keys, int32_t count) {
    if (c == nullptr || count < 0 || (count > 0 && keys == nullptr)) return fail(CM_ERR_ARG, "cm_clip_track_set: bad argument");
    const std::string r = role == nullptr ? "" : role;
    const std::string id = r.empty() ? (part_id == nullptr ? "" : part_id) : std::string(oa::kRolePrefix) + r;
    if (id.empty()) return fail(CM_ERR_ARG, "cm_clip_track_set: a track needs a part id or a role");
    for (int32_t i = 0; i < count; ++i) {
        if (!finite_key(keys[i])) return fail(CM_ERR_ARG, "cm_clip_track_set: non-finite key");
    }
    CM_GUARD_BEGIN
    g_error.clear();
    oa::Track t;
    t.part_id = id;
    t.part_name = !r.empty() ? id : (part_name != nullptr && part_name[0] != '\0' ? part_name : id);
    t.role = r;
    t.bone = bone != 0;
    for (int32_t i = 0; i < count; ++i) t.keys.push_back(key_from(keys[i]));
    std::stable_sort(t.keys.begin(), t.keys.end(), [](const oa::Key& a, const oa::Key& b) { return a.time < b.time; });
    auto& tracks = c->clip.tracks;
    for (std::size_t i = 0; i < tracks.size(); ++i) {
        const bool same = r.empty() ? tracks[i].role.empty() && tracks[i].part_id == id : tracks[i].role == r;
        if (same) {
            t.data_file = tracks[i].data_file;
            tracks[i] = std::move(t);
            return static_cast<int32_t>(i);
        }
    }
    tracks.push_back(std::move(t));
    return static_cast<int32_t>(tracks.size() - 1);
    CM_GUARD_END(CM_ERR_ARG)
}

int32_t cm_clip_track_remove(cm_clip* c, int32_t index) {
    if (c == nullptr) return fail(CM_ERR_ARG, "cm_clip_track_remove: null clip");
    if (!in_range(c->clip.tracks, index)) return fail(CM_ERR_RANGE, "cm_clip_track_remove: index out of range");
    c->clip.tracks.erase(c->clip.tracks.begin() + index);
    return CM_OK;
}

// --- Notifies -----------------------------------------------------------------------------------------

int32_t cm_clip_notify_count(const cm_clip* c) { return c == nullptr ? 0 : static_cast<int32_t>(c->clip.notifies.size()); }

int32_t cm_clip_notify_get(const cm_clip* c, int32_t index, const char** out_name, float* out_time, float* out_duration,
                           const char** out_payload_json) {
    if (c == nullptr) return fail(CM_ERR_ARG, "cm_clip_notify_get: null clip");
    if (!in_range(c->clip.notifies, index)) return fail(CM_ERR_RANGE, "cm_clip_notify_get: index out of range");
    const oa::Notify& n = c->clip.notifies[static_cast<std::size_t>(index)];
    set_out(out_name, n.name.c_str());
    set_out(out_time, n.time);
    set_out(out_duration, n.duration);
    set_out(out_payload_json, n.payload_json.c_str());
    return CM_OK;
}

int32_t cm_clip_notify_add(cm_clip* c, const char* name, float time, float duration, const char* payload_json) {
    if (c == nullptr || name == nullptr || name[0] == '\0') return fail(CM_ERR_ARG, "cm_clip_notify_add: needs a name");
    if (!finite(time) || !finite(duration) || duration < 0.f) return fail(CM_ERR_ARG, "cm_clip_notify_add: bad time/duration");
    CM_GUARD_BEGIN
    g_error.clear();
    oa::Notify n{name, time, duration, ""};
    if (payload_json != nullptr && payload_json[0] != '\0') {
        try {
            n.payload_json = oa::minify_object(payload_json, "notify payload");
        } catch (const std::exception& e) {
            return fail(CM_ERR_ARG, e.what());
        }
    }
    auto& v = c->clip.notifies;
    auto at = std::upper_bound(v.begin(), v.end(), time, [](float t, const oa::Notify& x) { return t < x.time; });
    at = v.insert(at, std::move(n));
    return static_cast<int32_t>(at - v.begin());
    CM_GUARD_END(CM_ERR_ARG)
}

int32_t cm_clip_notify_remove(cm_clip* c, int32_t index) {
    if (c == nullptr) return fail(CM_ERR_ARG, "cm_clip_notify_remove: null clip");
    if (!in_range(c->clip.notifies, index)) return fail(CM_ERR_RANGE, "cm_clip_notify_remove: index out of range");
    c->clip.notifies.erase(c->clip.notifies.begin() + index);
    return CM_OK;
}

int32_t cm_clip_fire(const cm_clip* c, float prev_time, float delta, cm_fired* out, int32_t capacity) {
    if (c == nullptr || capacity < 0) return fail(CM_ERR_ARG, "cm_clip_fire: bad argument");
    CM_GUARD_BEGIN
    const std::vector<Edge> edges = fire(c->clip, prev_time, delta);
    const auto n = static_cast<int32_t>(edges.size());
    for (int32_t i = 0; out != nullptr && i < std::min(n, capacity); ++i) {
        const Edge& e = edges[static_cast<std::size_t>(i)];
        out[i] = cm_fired{e.notify, e.phase, e.t, 0};
    }
    return n;
    CM_GUARD_END(CM_ERR_ARG)
}

// --- Curves -------------------------------------------------------------------------------------------

int32_t cm_clip_curve_count(const cm_clip* c) { return c == nullptr ? 0 : static_cast<int32_t>(c->clip.curves.size()); }

int32_t cm_clip_curve_get(const cm_clip* c, int32_t index, const char** out_name, int32_t* out_key_count) {
    if (c == nullptr) return fail(CM_ERR_ARG, "cm_clip_curve_get: null clip");
    if (!in_range(c->clip.curves, index)) return fail(CM_ERR_RANGE, "cm_clip_curve_get: index out of range");
    const oa::Curve& cv = c->clip.curves[static_cast<std::size_t>(index)];
    set_out(out_name, cv.name.c_str());
    set_out(out_key_count, static_cast<int32_t>(cv.keys.size()));
    return CM_OK;
}

int32_t cm_clip_curve_keys(const cm_clip* c, int32_t index, cm_curve_key* out, int32_t capacity) {
    if (c == nullptr || capacity < 0) return fail(CM_ERR_ARG, "cm_clip_curve_keys: bad argument");
    if (!in_range(c->clip.curves, index)) return fail(CM_ERR_RANGE, "cm_clip_curve_keys: index out of range");
    const auto& keys = c->clip.curves[static_cast<std::size_t>(index)].keys;
    const auto n = static_cast<int32_t>(keys.size());
    for (int32_t i = 0; out != nullptr && i < std::min(n, capacity); ++i) {
        const oa::CurveKey& k = keys[static_cast<std::size_t>(i)];
        out[i] = cm_curve_key{k.time, k.value, k.in_tangent, k.out_tangent, static_cast<int32_t>(k.interp)};
    }
    return n;
}

int32_t cm_clip_curve_set(cm_clip* c, const char* name, const cm_curve_key* keys, int32_t count) {
    if (c == nullptr || name == nullptr || name[0] == '\0' || count < 0) return fail(CM_ERR_ARG, "cm_clip_curve_set: bad argument");
    CM_GUARD_BEGIN
    g_error.clear();
    auto& curves = c->clip.curves;
    auto it = std::find_if(curves.begin(), curves.end(), [&](const oa::Curve& cv) { return cv.name == name; });
    if (keys == nullptr || count == 0) {
        if (it != curves.end()) curves.erase(it);
        return CM_OK;
    }
    oa::Curve cv;
    cv.name = name;
    for (int32_t i = 0; i < count; ++i) {
        const cm_curve_key& k = keys[i];
        if (!finite(k.time) || !finite(k.value) || !finite(k.in_tangent) || !finite(k.out_tangent) ||
            k.interp < CM_INTERP_LINEAR || k.interp > CM_INTERP_CUBIC) {
            return fail(CM_ERR_ARG, "cm_clip_curve_set: bad key");
        }
        cv.keys.push_back({k.time, k.value, k.in_tangent, k.out_tangent, static_cast<oa::CurveInterp>(k.interp)});
    }
    std::stable_sort(cv.keys.begin(), cv.keys.end(), [](const oa::CurveKey& a, const oa::CurveKey& b) { return a.time < b.time; });
    if (it != curves.end()) {
        *it = std::move(cv);
        return static_cast<int32_t>(it - curves.begin());
    }
    curves.push_back(std::move(cv));
    return static_cast<int32_t>(curves.size() - 1);
    CM_GUARD_END(CM_ERR_ARG)
}

int32_t cm_clip_curve_find(const cm_clip* c, const char* name) {
    if (c == nullptr || name == nullptr) return -1;
    for (std::size_t i = 0; i < c->clip.curves.size(); ++i) {
        if (c->clip.curves[i].name == name) return static_cast<int32_t>(i);
    }
    return -1;
}

float cm_clip_curve_sample(const cm_clip* c, int32_t index, float time) {
    if (c == nullptr || !in_range(c->clip.curves, index) || !finite(time)) return 0.f;
    return sample(c->clip.curves[static_cast<std::size_t>(index)], time);
}

// --- Markers ------------------------------------------------------------------------------------------

int32_t cm_clip_marker_count(const cm_clip* c) { return c == nullptr ? 0 : static_cast<int32_t>(c->clip.markers.size()); }

int32_t cm_clip_marker_get(const cm_clip* c, int32_t index, const char** out_name, float* out_time) {
    if (c == nullptr) return fail(CM_ERR_ARG, "cm_clip_marker_get: null clip");
    if (!in_range(c->clip.markers, index)) return fail(CM_ERR_RANGE, "cm_clip_marker_get: index out of range");
    const oa::SyncMarker& m = c->clip.markers[static_cast<std::size_t>(index)];
    set_out(out_name, m.name.c_str());
    set_out(out_time, m.time);
    return CM_OK;
}

int32_t cm_clip_marker_add(cm_clip* c, const char* name, float time) {
    if (c == nullptr || name == nullptr || name[0] == '\0' || !finite(time)) return fail(CM_ERR_ARG, "cm_clip_marker_add: bad argument");
    CM_GUARD_BEGIN
    auto& v = c->clip.markers;
    auto at = std::upper_bound(v.begin(), v.end(), time, [](float t, const oa::SyncMarker& m) { return t < m.time; });
    at = v.insert(at, oa::SyncMarker{name, time});
    return static_cast<int32_t>(at - v.begin());
    CM_GUARD_END(CM_ERR_ARG)
}

int32_t cm_clip_marker_remove(cm_clip* c, int32_t index) {
    if (c == nullptr) return fail(CM_ERR_ARG, "cm_clip_marker_remove: null clip");
    if (!in_range(c->clip.markers, index)) return fail(CM_ERR_RANGE, "cm_clip_marker_remove: index out of range");
    c->clip.markers.erase(c->clip.markers.begin() + index);
    return CM_OK;
}

int32_t cm_clip_marker_position(const cm_clip* c, float time, int32_t* out_marker, float* out_fraction) {
    if (c == nullptr || !finite(time)) return fail(CM_ERR_ARG, "cm_clip_marker_position: bad argument");
    const oa::Clip& k = c->clip;
    const double d = k.duration;
    double t = time;
    if (k.loop && d > 0.0) {
        t = t - d * std::floor(t / d);
        if (t >= d) t = 0.0;
    } else {
        t = std::clamp(t, 0.0, std::max(d, 0.0));
    }
    const auto n = static_cast<int32_t>(k.markers.size());
    int32_t p = -1;
    for (int32_t i = 0; i < n; ++i) {
        if (k.markers[static_cast<std::size_t>(i)].time <= t) p = i;
    }
    double start = 0.0;
    double end = 0.0;
    if (p < 0 && k.loop && n > 0) {  // before the first marker: still inside the last one's span
        p = n - 1;
        marker_span(k, p, start, end);
        start -= d;
        end -= d;
    } else {
        marker_span(k, p, start, end);
    }
    const double span = end - start;
    double f = span > 0.0 ? (t - start) / span : 0.0;
    f = std::clamp(f, 0.0, std::nextafter(1.0, 0.0));
    set_out(out_marker, p);
    set_out(out_fraction, static_cast<float>(f));
    return CM_OK;
}

float cm_clip_marker_time(const cm_clip* c, int32_t marker, float fraction) {
    if (c == nullptr || !finite(fraction) || marker < -1 || marker >= static_cast<int32_t>(c->clip.markers.size())) return 0.f;
    const oa::Clip& k = c->clip;
    double start = 0.0;
    double end = 0.0;
    marker_span(k, marker, start, end);
    double t = start + (end - start) * static_cast<double>(fraction);
    const double d = k.duration;
    if (k.loop && d > 0.0) {
        t = t - d * std::floor(t / d);
        if (t >= d) t = 0.0;
    }
    return static_cast<float>(t);
}

// --- Root motion and additive -------------------------------------------------------------------------

int32_t cm_clip_root_motion_get(const cm_clip* c, const char** out_joint, uint32_t* out_axes) {
    if (c == nullptr) return fail(CM_ERR_ARG, "cm_clip_root_motion_get: null clip");
    set_out(out_joint, c->clip.root_motion.joint.c_str());
    set_out(out_axes, c->clip.root_motion.axes);
    return CM_OK;
}

int32_t cm_clip_root_motion_set(cm_clip* c, const char* joint, uint32_t axes) {
    if (c == nullptr) return fail(CM_ERR_ARG, "cm_clip_root_motion_set: null clip");
    if (joint == nullptr || joint[0] == '\0') {
        c->clip.root_motion = {};
        return CM_OK;
    }
    c->clip.root_motion = {joint, axes & (CM_ROOT_X | CM_ROOT_Y | CM_ROOT_Z | CM_ROOT_YAW)};
    return CM_OK;
}

int32_t cm_clip_additive_get(const cm_clip* c, int32_t* out_reference, float* out_time, const char** out_clip_ref,
                             int32_t* out_mesh_space) {
    if (c == nullptr) return fail(CM_ERR_ARG, "cm_clip_additive_get: null clip");
    const oa::Additive& a = c->clip.additive;
    set_out(out_reference, static_cast<int32_t>(a.reference));
    set_out(out_time, a.time);
    set_out(out_clip_ref, a.clip.c_str());
    set_out(out_mesh_space, a.mesh_space ? int32_t{1} : int32_t{0});
    return CM_OK;
}

int32_t cm_clip_additive_set(cm_clip* c, int32_t reference, float time, const char* clip_ref, int32_t mesh_space) {
    if (c == nullptr || reference < CM_ADDITIVE_NONE || reference > CM_ADDITIVE_CHARACTER || !finite(time)) {
        return fail(CM_ERR_ARG, "cm_clip_additive_set: bad argument");
    }
    if (reference == CM_ADDITIVE_CLIP && (clip_ref == nullptr || clip_ref[0] == '\0')) {
        return fail(CM_ERR_ARG, "cm_clip_additive_set: a clip reference needs clip_ref");
    }
    oa::Additive a;
    a.reference = static_cast<oa::AdditiveReference>(reference);
    if (reference != CM_ADDITIVE_NONE) {
        a.time = time;
        a.clip = reference == CM_ADDITIVE_CLIP ? clip_ref : "";
        a.mesh_space = mesh_space != 0;
    }
    c->clip.additive = a;
    return CM_OK;
}

// --- Roles --------------------------------------------------------------------------------------------

cm_clip* cm_clip_bind_roles(const cm_clip* c, const cc_char* character, int32_t* out_dropped) {
    set_out(out_dropped, int32_t{0});
    if (c == nullptr || character == nullptr) {
        fail(CM_ERR_ARG, "cm_clip_bind_roles: null argument");
        return nullptr;
    }
    CM_GUARD_BEGIN
    g_error.clear();
    const auto& def = character->def;
    std::unordered_map<std::string, const cenda::omformat::omchar::SkeletonJoint*> joints;
    for (const auto& j : def.joints) joints[j.id] = &j;
    auto* out = new cm_clip(*c);
    int32_t dropped = 0;
    std::vector<oa::Track> kept;
    for (oa::Track& t : out->clip.tracks) {
        if (t.role.empty()) {
            kept.push_back(std::move(t));
            continue;
        }
        const std::string joint = cenda::character::role_joint(def, t.role);
        if (joint.empty()) {
            ++dropped;
            continue;
        }
        auto it = joints.find(joint);
        t.part_id = joint;
        t.part_name = it != joints.end() && !it->second->name.empty() ? it->second->name : joint;
        t.bone = it != joints.end() && it->second->kind == cenda::omformat::omchar::JointKind::Bone;
        t.role.clear();
        t.data_file.clear();
        kept.push_back(std::move(t));
    }
    out->clip.tracks = std::move(kept);
    std::string& root = out->clip.root_motion.joint;
    if (root.rfind(oa::kRolePrefix, 0) == 0) {
        const std::string joint = cenda::character::role_joint(def, root.substr(std::strlen(oa::kRolePrefix)));
        if (joint.empty()) {
            out->clip.root_motion = {};
        } else {
            root = joint;
        }
    }
    set_out(out_dropped, dropped);
    return out;
    CM_GUARD_END(nullptr)
}

cm_clip* cm_clip_to_roles(const cm_clip* c, const cc_char* character) {
    if (c == nullptr || character == nullptr) {
        fail(CM_ERR_ARG, "cm_clip_to_roles: null argument");
        return nullptr;
    }
    CM_GUARD_BEGIN
    g_error.clear();
    std::unordered_map<std::string, std::string> role_of;
    for (const auto& r : character->def.roles) role_of.emplace(r.joint, r.role);
    auto* out = new cm_clip(*c);
    for (oa::Track& t : out->clip.tracks) {
        if (!t.role.empty()) continue;
        auto it = role_of.find(t.part_id);
        if (it == role_of.end()) continue;
        t.role = it->second;
        t.part_id = std::string(oa::kRolePrefix) + t.role;
        t.part_name = t.part_id;
        t.data_file.clear();
    }
    if (auto it = role_of.find(out->clip.root_motion.joint); it != role_of.end()) {
        out->clip.root_motion.joint = std::string(oa::kRolePrefix) + it->second;
    }
    return out;
    CM_GUARD_END(nullptr)
}

// --- Sync groups ----------------------------------------------------------------------------------------

int32_t cm_sync_advance(cm_sync_member* members, int32_t count, int32_t leader, float dt) {
    if (members == nullptr || count < 1 || leader >= count || !finite(dt)) {
        return fail(CM_ERR_ARG, "cm_sync_advance: bad arguments");
    }
    for (int32_t i = 0; i < count; ++i) {
        if (members[i].clip == nullptr || !finite(members[i].time)) {
            return fail(CM_ERR_ARG, "cm_sync_advance: member " + std::to_string(i) + " has no clip or a bad time");
        }
    }
    if (leader < 0) {
        leader = 0;
        for (int32_t i = 1; i < count; ++i) {
            if (members[i].weight > members[leader].weight) leader = i;
        }
    }
    // Clip time rules: looping wraps into [0, duration), others clamp.
    const auto place = [](const oa::Clip& k, double t) {
        const double d = k.duration;
        if (d <= 0.0) return 0.0;
        if (k.loop) {
            t = t - d * std::floor(t / d);
            return t >= d ? 0.0 : t;
        }
        return std::clamp(t, 0.0, d);
    };
    cm_sync_member& lead = members[leader];
    const oa::Clip& lk = lead.clip->clip;
    lead.time = static_cast<float>(place(lk, static_cast<double>(lead.time) + static_cast<double>(lead.rate) * dt));
    int32_t marker = -1;
    float fraction = 0.f;
    if (!lk.markers.empty()) cm_clip_marker_position(lead.clip, lead.time, &marker, &fraction);
    const std::string* name = marker >= 0 ? &lk.markers[static_cast<std::size_t>(marker)].name : nullptr;
    const double phase = lk.duration > 0.0 ? lead.time / lk.duration : 0.0;
    for (int32_t i = 0; i < count; ++i) {
        if (i == leader) continue;
        cm_sync_member& m = members[i];
        const oa::Clip& k = m.clip->clip;
        const double d = k.duration;
        double best = -1.0;
        double best_dist = 0.0;
        if (name != nullptr) {
            for (std::size_t j = 0; j < k.markers.size(); ++j) {
                if (k.markers[j].name != *name) continue;
                const double t = cm_clip_marker_time(m.clip, static_cast<int32_t>(j), fraction);
                // Forward distance from the follower's current time (wrapping for loops).
                double dist = t - m.time;
                if (k.loop && d > 0.0) {
                    dist = dist - d * std::floor(dist / d);
                } else if (dist < 0.0) {
                    dist = d + (m.time - t);  // backwards only when nothing lies ahead
                }
                if (best < 0.0 || dist < best_dist) {
                    best = t;
                    best_dist = dist;
                }
            }
        }
        if (best < 0.0) best = place(k, phase * d);
        m.time = static_cast<float>(best);
    }
    return leader;
}

}  // extern "C"
