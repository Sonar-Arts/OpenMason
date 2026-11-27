// Cenda morph targets / shape keys (CR ABI 3, #171 additions; OpenMason #171): the mesh's targets (cr_morphs: sparse
// deltas, CPU apply, GPU packing), a rig's morph channels and a clip's morph tracks. The pose pipeline's handling of
// morph weights lives with the joints' (rig.cpp, pose.cpp). Contract in cenda/rig.h "Morph targets".
#include "rig_internal.hpp"

#include "cenda/model.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <exception>
#include <memory>
#include <new>
#include <string>
#include <unordered_map>
#include <vector>

using namespace cenda::rig;

namespace {

struct Target {
    std::string name;
    std::string lower;
    float def = 0.f, min = 0.f, max = 1.f;
};

// One stored row (vertex-major in cr_morphs::rows).
struct Row {
    int32_t vertex;
    int32_t target;
    float d[3];
    float n[3];
};

bool finite3(const float* v) { return std::isfinite(v[0]) && std::isfinite(v[1]) && std::isfinite(v[2]); }

bool zero_row(const Row& r) {
    return r.d[0] == 0.f && r.d[1] == 0.f && r.d[2] == 0.f && r.n[0] == 0.f && r.n[1] == 0.f && r.n[2] == 0.f;
}

std::string strip_prefix(const char* name) {
    std::string s(name);
    if (s.size() > 6 && lower(s.substr(0, 6)) == "morph:") s.erase(0, 6);
    return s;
}

}  // namespace

struct cr_morphs {
    int32_t vertex_count = 0;
    std::vector<Target> targets;
    std::unordered_map<std::string, int32_t> by_name, by_lower;
    std::vector<uint32_t> range_offset;  // per vertex
    std::vector<uint32_t> range_count;   // per vertex
    std::vector<Row> rows;               // vertex-major, target ascending, zeros dropped
    bool normals = false;
};

namespace {

// Validates the targets' names and params; fills m.targets / lookups. Returns false (error set) on bad input.
bool set_targets(cr_morphs& m, int32_t target_count, const char* const* names, const float* params, const char* who) {
    if (target_count < 0 || target_count > CR_MAX_MORPH_TARGETS || (target_count > 0 && names == nullptr)) {
        set_error(std::string(who) + ": bad target count / names");
        return false;
    }
    m.targets.resize(static_cast<size_t>(target_count));
    for (int32_t t = 0; t < target_count; ++t) {
        Target& tg = m.targets[static_cast<size_t>(t)];
        if (names[t] == nullptr || names[t][0] == '\0') {
            set_error(std::string(who) + ": target " + std::to_string(t) + " has no name");
            return false;
        }
        tg.name = names[t];
        tg.lower = lower(tg.name);
        if (params != nullptr) {
            tg.def = params[3 * t];
            tg.min = params[3 * t + 1];
            tg.max = params[3 * t + 2];
            if (!std::isfinite(tg.def) || !std::isfinite(tg.min) || !std::isfinite(tg.max) || tg.min > tg.max) {
                set_error(std::string(who) + ": target " + tg.name + " has a bad default / range");
                return false;
            }
        }
        if (!m.by_lower.emplace(tg.lower, t).second) {
            set_error(std::string(who) + ": duplicate target name " + tg.name);
            return false;
        }
        m.by_name.emplace(tg.name, t);
    }
    return true;
}

// Sorts rows vertex-major (target ascending, stable), merges equal (vertex, target) by adding (in row order), drops
// zero rows and builds the per-vertex ranges.
void finish(cr_morphs& m, std::vector<Row>& rows) {
    std::stable_sort(rows.begin(), rows.end(), [](const Row& a, const Row& b) {
        return a.vertex != b.vertex ? a.vertex < b.vertex : a.target < b.target;
    });
    m.rows.clear();
    m.rows.reserve(rows.size());
    for (const Row& r : rows) {
        if (!m.rows.empty() && m.rows.back().vertex == r.vertex && m.rows.back().target == r.target) {
            Row& b = m.rows.back();
            for (int c = 0; c < 3; ++c) {
                b.d[c] += r.d[c];
                b.n[c] += r.n[c];
            }
        } else {
            m.rows.push_back(r);
        }
    }
    m.rows.erase(std::remove_if(m.rows.begin(), m.rows.end(), zero_row), m.rows.end());
    m.normals = std::any_of(m.rows.begin(), m.rows.end(),
                            [](const Row& r) { return r.n[0] != 0.f || r.n[1] != 0.f || r.n[2] != 0.f; });
    const auto nv = static_cast<size_t>(m.vertex_count);
    m.range_offset.assign(nv, 0);
    m.range_count.assign(nv, 0);
    for (size_t i = 0; i < m.rows.size(); ++i) {
        const auto v = static_cast<size_t>(m.rows[i].vertex);
        if (m.range_count[v] == 0) m.range_offset[v] = static_cast<uint32_t>(i);
        ++m.range_count[v];
    }
    // Vertices without rows point at the end of the run before them (offset of v + 1 = offset + count of v).
    uint32_t next = 0;
    for (size_t v = 0; v < nv; ++v) {
        if (m.range_count[v] == 0) m.range_offset[v] = next;
        next = m.range_offset[v] + m.range_count[v];
    }
}

float clamp_weight(const Target& t, float w) {
    if (std::isnan(w)) w = 0.f;
    return std::min(std::max(w, t.min), t.max);
}

int32_t find_target(const cr_morphs& m, const char* name) {
    if (name == nullptr || name[0] == '\0') return -1;
    const std::string s = strip_prefix(name);
    if (auto it = m.by_name.find(s); it != m.by_name.end()) return it->second;
    if (auto it = m.by_lower.find(lower(s)); it != m.by_lower.end()) return it->second;
    return -1;
}

}  // namespace

namespace cenda::rig {

// cm_clip_curve_sample's math (anim_clip.cpp `sample`), so a sampled morph weight equals the clip document's value.
float sample_morph_track(const cr_clip& clip, int32_t track, float t) {
    const cr_track_desc& td = clip.morph_tracks[static_cast<size_t>(track)];
    const cr_morph_key* k = clip.morph_keys.data() + td.first_key;
    const int32_t n = td.key_count;
    if (n <= 0) return 0.f;
    if (t <= k[0].time) return k[0].value;
    if (t >= k[n - 1].time) return k[n - 1].value;
    int32_t i = 0;
    while (i + 1 < n && k[i + 1].time <= t) ++i;
    const cr_morph_key& a = k[i];
    const cr_morph_key& b = k[i + 1];
    const double dt = static_cast<double>(b.time) - a.time;
    if (dt <= 0.0) return b.value;
    const double s = (static_cast<double>(t) - a.time) / dt;
    switch (a.interp) {
    case CR_MORPH_STEP: return a.value;
    case CR_MORPH_CUBIC: {
        const double s2 = s * s;
        const double s3 = s2 * s;
        const double v = (2 * s3 - 3 * s2 + 1) * a.value + (s3 - 2 * s2 + s) * dt * a.out_tangent +
                         (-2 * s3 + 3 * s2) * b.value + (s3 - s2) * dt * b.in_tangent;
        return static_cast<float>(v);
    }
    default: return static_cast<float>(a.value + (static_cast<double>(b.value) - a.value) * s);
    }
}

}  // namespace cenda::rig

extern "C" {

// ---- rig morph channels ------------------------------------------------------------------------------------------

int32_t cr_rig_set_morph_channels(cr_rig* rig, int32_t count, const char* const* names, const float* defaults) {
    if (rig == nullptr || count < 0 || count > CR_MAX_MORPH_TARGETS || (count > 0 && names == nullptr)) {
        set_error("cr_rig_set_morph_channels: bad arguments");
        return CR_ERR_ARG;
    }
    if (rig->morph_frozen.load(std::memory_order_relaxed)) {
        set_error("cr_rig_set_morph_channels: the rig is in use (poses, masks, inertializers or bound clips exist)");
        return CR_ERR_ARG;
    }
    try {
        std::vector<std::string> n, l;
        std::vector<float> d;
        std::unordered_map<std::string, int32_t> by_name, by_lower;
        for (int32_t c = 0; c < count; ++c) {
            if (names[c] == nullptr || names[c][0] == '\0') {
                set_error("cr_rig_set_morph_channels: channel " + std::to_string(c) + " has no name");
                return CR_ERR_ARG;
            }
            const float def = defaults != nullptr ? defaults[c] : 0.f;
            if (!std::isfinite(def)) {
                set_error("cr_rig_set_morph_channels: channel " + std::string(names[c]) + " has a non-finite default");
                return CR_ERR_ARG;
            }
            n.emplace_back(names[c]);
            l.push_back(lower(n.back()));
            if (!by_lower.emplace(l.back(), c).second) {
                set_error("cr_rig_set_morph_channels: duplicate channel " + n.back());
                return CR_ERR_ARG;
            }
            by_name.emplace(n.back(), c);
            d.push_back(def);
        }
        rig->morph_names = std::move(n);
        rig->morph_lower = std::move(l);
        rig->morph_defaults = std::move(d);
        rig->morph_by_name = std::move(by_name);
        rig->morph_by_lower = std::move(by_lower);
        return CR_OK;
    } catch (const std::exception& e) {
        set_error(std::string("cr_rig_set_morph_channels: ") + e.what());
        return CR_ERR_ARG;
    }
}

int32_t cr_rig_set_morph_channels_from(cr_rig* rig, const cr_morphs* morphs) {
    if (morphs == nullptr) {
        set_error("cr_rig_set_morph_channels_from: null morphs");
        return CR_ERR_ARG;
    }
    try {
        std::vector<const char*> names;
        std::vector<float> defs;
        for (const Target& t : morphs->targets) {
            names.push_back(t.name.c_str());
            defs.push_back(t.def);
        }
        return cr_rig_set_morph_channels(rig, static_cast<int32_t>(names.size()), names.data(), defs.data());
    } catch (const std::exception& e) {
        set_error(std::string("cr_rig_set_morph_channels_from: ") + e.what());
        return CR_ERR_ARG;
    }
}

int32_t cr_rig_morph_count(const cr_rig* rig) { return rig == nullptr ? 0 : static_cast<int32_t>(rig->morph_names.size()); }

const char* cr_rig_morph_name(const cr_rig* rig, int32_t channel) {
    if (rig == nullptr || channel < 0 || channel >= static_cast<int32_t>(rig->morph_names.size())) return nullptr;
    return rig->morph_names[static_cast<size_t>(channel)].c_str();
}

float cr_rig_morph_default(const cr_rig* rig, int32_t channel) {
    if (rig == nullptr || channel < 0 || channel >= static_cast<int32_t>(rig->morph_defaults.size())) return 0.f;
    return rig->morph_defaults[static_cast<size_t>(channel)];
}

int32_t cr_rig_morph_find(const cr_rig* rig, const char* name) {
    if (rig == nullptr || name == nullptr || name[0] == '\0') return -1;
    const std::string s = strip_prefix(name);
    if (auto it = rig->morph_by_name.find(s); it != rig->morph_by_name.end()) return it->second;
    if (auto it = rig->morph_by_lower.find(lower(s)); it != rig->morph_by_lower.end()) return it->second;
    return -1;
}

// ---- clip morph tracks -------------------------------------------------------------------------------------------

int32_t cr_clip_set_morph_tracks(cr_clip* clip, int32_t count, const char* const* names, const int32_t* offsets,
                                 const cr_morph_key* keys) {
    if (clip == nullptr || count < 0 || (count > 0 && (names == nullptr || offsets == nullptr))) {
        set_error("cr_clip_set_morph_tracks: bad arguments");
        return CR_ERR_ARG;
    }
    if (clip->bound.load(std::memory_order_relaxed)) {
        set_error("cr_clip_set_morph_tracks: the clip was already bound to a rig");
        return CR_ERR_ARG;
    }
    if (count > 0 && offsets[0] != 0) {
        set_error("cr_clip_set_morph_tracks: offsets[0] must be 0");
        return CR_ERR_ARG;
    }
    try {
        std::vector<cr_track_desc> tracks;
        std::vector<cr_morph_key> all;
        std::vector<std::string> n;
        std::unordered_map<std::string, int32_t> by_name, by_lower;
        for (int32_t t = 0; t < count; ++t) {
            if (names[t] == nullptr || names[t][0] == '\0') {
                set_error("cr_clip_set_morph_tracks: track " + std::to_string(t) + " has no name");
                return CR_ERR_ARG;
            }
            const int32_t a = offsets[t], b = offsets[t + 1];
            if (b < a || (b > a && keys == nullptr)) {
                set_error("cr_clip_set_morph_tracks: track " + std::to_string(t) + " has bad offsets");
                return CR_ERR_ARG;
            }
            const auto first = static_cast<int32_t>(all.size());
            for (int32_t k = a; k < b; ++k) {
                const cr_morph_key& key = keys[k];
                if (!std::isfinite(key.time) || !std::isfinite(key.value) || !std::isfinite(key.in_tangent) ||
                    !std::isfinite(key.out_tangent) || key.interp < CR_MORPH_LINEAR || key.interp > CR_MORPH_CUBIC) {
                    set_error("cr_clip_set_morph_tracks: track " + std::string(names[t]) + " has a bad key");
                    return CR_ERR_ARG;
                }
                all.push_back(key);
            }
            std::stable_sort(all.begin() + first, all.end(),
                             [](const cr_morph_key& x, const cr_morph_key& y) { return x.time < y.time; });
            tracks.push_back(cr_track_desc{first, b - a});
            n.emplace_back(names[t]);
            if (!by_name.emplace(n.back(), t).second) {
                set_error("cr_clip_set_morph_tracks: duplicate track " + n.back());
                return CR_ERR_ARG;
            }
            by_lower.emplace(lower(n.back()), t);  // the first track per lower-case name wins
        }
        clip->morph_tracks = std::move(tracks);
        clip->morph_keys = std::move(all);
        clip->morph_names = std::move(n);
        clip->morph_by_name = std::move(by_name);
        clip->morph_by_lower = std::move(by_lower);
        return CR_OK;
    } catch (const std::exception& e) {
        set_error(std::string("cr_clip_set_morph_tracks: ") + e.what());
        return CR_ERR_ARG;
    }
}

int32_t cr_clip_morph_track_count(const cr_clip* clip) {
    return clip == nullptr ? 0 : static_cast<int32_t>(clip->morph_tracks.size());
}

const char* cr_clip_morph_track_name(const cr_clip* clip, int32_t track) {
    if (clip == nullptr || track < 0 || track >= static_cast<int32_t>(clip->morph_names.size())) return nullptr;
    return clip->morph_names[static_cast<size_t>(track)].c_str();
}

float cr_clip_sample_morph(const cr_clip* clip, int32_t track, float time) {
    if (clip == nullptr || track < 0 || track >= static_cast<int32_t>(clip->morph_tracks.size()) || std::isnan(time)) {
        return 0.f;
    }
    return sample_morph_track(*clip, track, time);
}

int32_t cr_clip_morph_track_for(const cr_clip* clip, const cr_rig* rig, int32_t channel) {
    if (clip == nullptr || rig == nullptr || channel < 0 || channel >= static_cast<int32_t>(rig->morph_names.size())) {
        return -1;
    }
    return binding_for(*rig, *clip).morph_track[static_cast<size_t>(channel)];
}

// ---- mesh targets ------------------------------------------------------------------------------------------------

cr_morphs* cr_morphs_create(int32_t vertex_count, int32_t target_count, const char* const* names, const float* params,
                            const int32_t* offsets, const int32_t* vertices, const float* deltas,
                            const float* normal_deltas) {
    if (vertex_count < 0 || target_count < 0 || (target_count > 0 && offsets == nullptr)) {
        set_error("cr_morphs_create: bad arguments");
        return nullptr;
    }
    try {
        auto m = std::make_unique<cr_morphs>();
        m->vertex_count = vertex_count;
        if (!set_targets(*m, target_count, names, params, "cr_morphs_create")) return nullptr;
        std::vector<Row> rows;
        if (target_count > 0) {
            if (offsets[0] != 0) {
                set_error("cr_morphs_create: offsets[0] must be 0");
                return nullptr;
            }
            for (int32_t t = 0; t < target_count; ++t) {
                if (offsets[t + 1] < offsets[t]) {
                    set_error("cr_morphs_create: offsets decrease at target " + std::to_string(t));
                    return nullptr;
                }
            }
            const int32_t total = offsets[target_count];
            if (total > 0 && (vertices == nullptr || deltas == nullptr)) {
                set_error("cr_morphs_create: rows without vertices / deltas");
                return nullptr;
            }
            rows.reserve(static_cast<size_t>(total));
            for (int32_t t = 0; t < target_count; ++t) {
                for (int32_t r = offsets[t]; r < offsets[t + 1]; ++r) {
                    Row row{};
                    row.vertex = vertices[r];
                    row.target = t;
                    std::memcpy(row.d, deltas + 3 * static_cast<size_t>(r), sizeof row.d);
                    if (normal_deltas != nullptr) std::memcpy(row.n, normal_deltas + 3 * static_cast<size_t>(r), sizeof row.n);
                    if (row.vertex < 0 || row.vertex >= vertex_count) {
                        set_error("cr_morphs_create: row " + std::to_string(r) + " names vertex " +
                                  std::to_string(row.vertex) + " outside the mesh");
                        return nullptr;
                    }
                    if (!finite3(row.d) || !finite3(row.n)) {
                        set_error("cr_morphs_create: row " + std::to_string(r) + " is not finite");
                        return nullptr;
                    }
                    rows.push_back(row);
                }
            }
        }
        finish(*m, rows);
        return m.release();
    } catch (const std::exception& e) {
        set_error(std::string("cr_morphs_create: ") + e.what());
        return nullptr;
    }
}

cr_morphs* cr_morphs_from_doc(const struct co_doc* doc, int32_t vertex_count, int32_t part_count,
                              const char* const* part_ids, const int32_t* part_first_vertex,
                              const int32_t* part_vertex_count, int32_t* out_skipped) {
    if (out_skipped != nullptr) *out_skipped = 0;
    if (doc == nullptr || vertex_count < 0 || part_count < 0 ||
        (part_count > 0 && (part_ids == nullptr || part_first_vertex == nullptr || part_vertex_count == nullptr))) {
        set_error("cr_morphs_from_doc: bad arguments");
        return nullptr;
    }
    try {
        int32_t nm = 0, np = 0, nr = 0;
        if (co_doc_morph_counts(doc, &nm, &np, &nr) != CO_OK) {
            set_error(std::string("cr_morphs_from_doc: ") + co_last_error());
            return nullptr;
        }
        const auto um = static_cast<size_t>(nm), up = static_cast<size_t>(np), ur = static_cast<size_t>(nr);
        std::vector<const char*> names(um), pids(up);
        std::vector<float> params(um * 3), deltas(ur * 3), normals(ur * 3);
        std::vector<int32_t> morph_parts(um + 1), part_flags(up), part_rows(up + 1), vertices(ur);
        if (co_doc_morphs_get(doc, names.data(), params.data(), morph_parts.data(), pids.data(), part_flags.data(),
                              part_rows.data(), vertices.data(), deltas.data(), normals.data()) != CO_OK) {
            set_error(std::string("cr_morphs_from_doc: ") + co_last_error());
            return nullptr;
        }
        std::unordered_map<std::string, int32_t> part_of;
        for (int32_t p = 0; p < part_count; ++p) {
            if (part_ids[p] != nullptr) part_of.emplace(part_ids[p], p);
        }
        std::vector<int32_t> offsets{0}, rv;
        std::vector<float> rd, rn;
        bool any_normals = false;
        int32_t skipped = 0;
        for (size_t m = 0; m < um; ++m) {
            for (int32_t mp = morph_parts[m]; mp < morph_parts[m + 1]; ++mp) {
                const auto ump = static_cast<size_t>(mp);
                const auto it = part_of.find(pids[ump] != nullptr ? pids[ump] : "");
                const bool has_n = (part_flags[ump] & CO_MORPH_PART_NORMALS) != 0;
                for (int32_t r = part_rows[ump]; r < part_rows[ump + 1]; ++r) {
                    const auto ur2 = static_cast<size_t>(r);
                    const int32_t local = vertices[ur2];
                    if (it == part_of.end() || local < 0 || local >= part_vertex_count[it->second]) {
                        ++skipped;
                        continue;
                    }
                    const int32_t v = part_first_vertex[it->second] + local;
                    if (v < 0 || v >= vertex_count) {
                        ++skipped;
                        continue;
                    }
                    rv.push_back(v);
                    for (size_t c = 0; c < 3; ++c) {
                        rd.push_back(deltas[ur2 * 3 + c]);
                        rn.push_back(has_n ? normals[ur2 * 3 + c] : 0.f);
                    }
                    any_normals |= has_n;
                }
            }
            offsets.push_back(static_cast<int32_t>(rv.size()));
        }
        if (out_skipped != nullptr) *out_skipped = skipped;
        return cr_morphs_create(vertex_count, nm, names.data(), params.data(), offsets.data(), rv.data(), rd.data(),
                                any_normals ? rn.data() : nullptr);
    } catch (const std::exception& e) {
        set_error(std::string("cr_morphs_from_doc: ") + e.what());
        return nullptr;
    }
}

cr_morphs* cr_morphs_remap(const cr_morphs* morphs, int32_t vertex_count, const int32_t* source_vertex) {
    if (morphs == nullptr || vertex_count < 0 || (vertex_count > 0 && source_vertex == nullptr)) {
        set_error("cr_morphs_remap: bad arguments");
        return nullptr;
    }
    try {
        auto m = std::make_unique<cr_morphs>();
        m->vertex_count = vertex_count;
        m->targets = morphs->targets;
        m->by_name = morphs->by_name;
        m->by_lower = morphs->by_lower;
        std::vector<Row> rows;
        for (int32_t v = 0; v < vertex_count; ++v) {
            const int32_t src = source_vertex[v];
            if (src < 0) continue;
            if (src >= morphs->vertex_count) {
                set_error("cr_morphs_remap: vertex " + std::to_string(v) + " maps to " + std::to_string(src) +
                          ", outside the source mesh");
                return nullptr;
            }
            const uint32_t off = morphs->range_offset[static_cast<size_t>(src)];
            const uint32_t cnt = morphs->range_count[static_cast<size_t>(src)];
            for (uint32_t i = off; i < off + cnt; ++i) {
                Row r = morphs->rows[i];
                r.vertex = v;
                rows.push_back(r);
            }
        }
        finish(*m, rows);
        return m.release();
    } catch (const std::exception& e) {
        set_error(std::string("cr_morphs_remap: ") + e.what());
        return nullptr;
    }
}

void cr_morphs_destroy(cr_morphs* morphs) { delete morphs; }

int32_t cr_morphs_vertex_count(const cr_morphs* morphs) { return morphs == nullptr ? 0 : morphs->vertex_count; }

int32_t cr_morphs_target_count(const cr_morphs* morphs) {
    return morphs == nullptr ? 0 : static_cast<int32_t>(morphs->targets.size());
}

int32_t cr_morphs_row_count(const cr_morphs* morphs) {
    return morphs == nullptr ? 0 : static_cast<int32_t>(morphs->rows.size());
}

int32_t cr_morphs_has_normals(const cr_morphs* morphs) { return morphs != nullptr && morphs->normals ? 1 : 0; }

const char* cr_morphs_target_name(const cr_morphs* morphs, int32_t target) {
    if (morphs == nullptr || target < 0 || target >= static_cast<int32_t>(morphs->targets.size())) return nullptr;
    return morphs->targets[static_cast<size_t>(target)].name.c_str();
}

int32_t cr_morphs_target_params(const cr_morphs* morphs, int32_t target, float* out3) {
    if (morphs == nullptr || out3 == nullptr || target < 0 || target >= static_cast<int32_t>(morphs->targets.size())) {
        set_error("cr_morphs_target_params: bad arguments");
        return CR_ERR_ARG;
    }
    const Target& t = morphs->targets[static_cast<size_t>(target)];
    out3[0] = t.def;
    out3[1] = t.min;
    out3[2] = t.max;
    return CR_OK;
}

int32_t cr_morphs_find(const cr_morphs* morphs, const char* name) {
    return morphs == nullptr ? -1 : find_target(*morphs, name);
}

int32_t cr_morphs_target_rows(const cr_morphs* morphs, int32_t target, int32_t* out_vertices, float* out_deltas,
                              float* out_normal_deltas, int32_t capacity) {
    if (morphs == nullptr || target < 0 || target >= static_cast<int32_t>(morphs->targets.size())) {
        set_error("cr_morphs_target_rows: bad arguments");
        return CR_ERR_ARG;
    }
    int32_t n = 0;
    for (const Row& r : morphs->rows) {  // vertex-major: a target's rows come out by ascending vertex
        if (r.target != target) continue;
        if (n < capacity) {
            const auto un = static_cast<size_t>(n);
            if (out_vertices != nullptr) out_vertices[n] = r.vertex;
            if (out_deltas != nullptr) std::memcpy(out_deltas + 3 * un, r.d, sizeof r.d);
            if (out_normal_deltas != nullptr) std::memcpy(out_normal_deltas + 3 * un, r.n, sizeof r.n);
        }
        ++n;
    }
    return n;
}

int32_t cr_morphs_clamp_weights(const cr_morphs* morphs, const float* weights, int32_t count, float* out) {
    if (morphs == nullptr || count < 0 || (out == nullptr && !morphs->targets.empty())) {
        set_error("cr_morphs_clamp_weights: bad arguments");
        return CR_ERR_ARG;
    }
    for (size_t t = 0; t < morphs->targets.size(); ++t) {
        const float w = weights != nullptr && static_cast<int32_t>(t) < count ? weights[t] : 0.f;
        out[t] = clamp_weight(morphs->targets[t], w);
    }
    return CR_OK;
}

int32_t cr_morph_apply(const cr_morphs* morphs, const float* weights, int32_t weight_count, const float* positions,
                       const float* normals, float* out_positions, float* out_normals) {
    if (morphs == nullptr || weight_count < 0 ||
        (morphs->vertex_count > 0 && (positions == nullptr || out_positions == nullptr))) {
        set_error("cr_morph_apply: bad arguments");
        return CR_ERR_ARG;
    }
    const bool do_normals = normals != nullptr && out_normals != nullptr;
    const auto nv = static_cast<size_t>(morphs->vertex_count);
    for (size_t v = 0; v < nv; ++v) {
        const uint32_t off = morphs->range_offset[v];
        const uint32_t cnt = morphs->range_count[v];
        float acc[3] = {0.f, 0.f, 0.f}, nacc[3] = {0.f, 0.f, 0.f};
        bool moved = false, turned = false;
        for (uint32_t i = off; i < off + cnt; ++i) {
            const Row& r = morphs->rows[i];
            const auto t = static_cast<size_t>(r.target);
            const float raw = weights != nullptr && static_cast<int32_t>(t) < weight_count ? weights[t] : 0.f;
            const float w = clamp_weight(morphs->targets[t], raw);
            if (w == 0.f) continue;
            for (int c = 0; c < 3; ++c) {
                const float pd = w * r.d[c];
                acc[c] = acc[c] + pd;
                const float nd = w * r.n[c];
                nacc[c] = nacc[c] + nd;
            }
            moved = true;
            turned |= r.n[0] != 0.f || r.n[1] != 0.f || r.n[2] != 0.f;
        }
        const float* p = positions + 3 * v;
        float* po = out_positions + 3 * v;
        if (moved) {
            const float x = p[0] + acc[0], y = p[1] + acc[1], z = p[2] + acc[2];
            po[0] = x;
            po[1] = y;
            po[2] = z;
        } else if (po != p) {
            std::memcpy(po, p, sizeof(float) * 3);
        }
        if (!do_normals) continue;
        const float* n = normals + 3 * v;
        float* no = out_normals + 3 * v;
        if (turned) {
            const float x = n[0] + nacc[0], y = n[1] + nacc[1], z = n[2] + nacc[2];
            const double len =
                std::sqrt(static_cast<double>(x) * x + static_cast<double>(y) * y + static_cast<double>(z) * z);
            if (len > 0.0) {
                no[0] = static_cast<float>(x / len);
                no[1] = static_cast<float>(y / len);
                no[2] = static_cast<float>(z / len);
                continue;
            }
        }
        if (no != n) std::memcpy(no, n, sizeof(float) * 3);
    }
    return CR_OK;
}

int32_t cr_skin_deform_morphed(const cr_skin* skin, const int32_t* part_joint_of_vertex, const cr_morphs* morphs,
                               const float* weights, int32_t weight_count, const float* positions, int32_t vertex_count,
                               const float* skin_matrices, int32_t joint_count, float* out_positions) {
    if (morphs == nullptr) {
        return cr_skin_deform(skin, part_joint_of_vertex, positions, vertex_count, skin_matrices, joint_count,
                              out_positions);
    }
    if (vertex_count != morphs->vertex_count || positions == nullptr) {
        set_error("cr_skin_deform_morphed: the morphs are for " + std::to_string(morphs->vertex_count) +
                  " vertices, got " + std::to_string(vertex_count));
        return CR_ERR_ARG;
    }
    thread_local std::vector<float> scratch;
    try {
        scratch.resize(static_cast<size_t>(vertex_count) * 3);
    } catch (const std::exception&) {
        set_error("cr_skin_deform_morphed: out of memory");
        return CR_ERR_ARG;
    }
    const int32_t rc = cr_morph_apply(morphs, weights, weight_count, positions, nullptr, scratch.data(), nullptr);
    if (rc != CR_OK) return rc;
    return cr_skin_deform(skin, part_joint_of_vertex, scratch.data(), vertex_count, skin_matrices, joint_count,
                          out_positions);
}

int32_t cr_morphs_pack_size(const cr_morphs* morphs) {
    return morphs == nullptr ? 0 : static_cast<int32_t>(morphs->rows.size());
}

int32_t cr_morphs_pack(const cr_morphs* morphs, int32_t record_capacity, uint32_t* out_ranges, float* out_deltas,
                       float* out_normals) {
    if (morphs == nullptr || (out_ranges == nullptr && morphs->vertex_count > 0) ||
        record_capacity < static_cast<int32_t>(morphs->rows.size())) {
        set_error("cr_morphs_pack: bad arguments (record_capacity must be >= cr_morphs_pack_size)");
        return CR_ERR_ARG;
    }
    for (size_t v = 0; v < static_cast<size_t>(morphs->vertex_count); ++v) {
        out_ranges[2 * v] = morphs->range_offset[v];
        out_ranges[2 * v + 1] = morphs->range_count[v];
    }
    for (size_t i = 0; i < morphs->rows.size(); ++i) {
        const Row& r = morphs->rows[i];
        if (out_deltas != nullptr) {
            out_deltas[4 * i] = r.d[0];
            out_deltas[4 * i + 1] = r.d[1];
            out_deltas[4 * i + 2] = r.d[2];
            out_deltas[4 * i + 3] = static_cast<float>(r.target);
        }
        if (out_normals != nullptr) {
            out_normals[4 * i] = r.n[0];
            out_normals[4 * i + 1] = r.n[1];
            out_normals[4 * i + 2] = r.n[2];
            out_normals[4 * i + 3] = 0.f;
        }
    }
    return CR_OK;
}

}  // extern "C"
