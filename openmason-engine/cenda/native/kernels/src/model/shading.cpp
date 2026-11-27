// Cenda model shading store (OpenMason #169): the editor's per-part shading keyed so it survives a re-soup (face ids,
// welded positions), resolved against the current soup on every call. See cenda/model_shading.h. No exception leaves
// this file.
#define CENDA_MODEL_SHADING_INTERNAL 1
#include "cenda/model_shading.h"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <exception>
#include <limits>
#include <map>
#include <memory>
#include <string>
#include <tuple>
#include <type_traits>
#include <utility>
#include <vector>

namespace sd = cenda::model::shading_detail;

struct co_shading {
    struct Edge {
        float a[3];
        float b[3];
    };
    struct Custom {
        int32_t face;
        float p[3];
        int16_t oct[2];
    };
    float epsilon = CO_SHADING_DEFAULT_WELD_EPSILON;
    float auto_degrees = 0.0f;  // 0 = off
    std::vector<uint32_t> smooth;
    std::vector<Edge> sharp;
    std::vector<Custom> custom;
    uint64_t revision = 0;
};

namespace {

constexpr int32_t kMaxFaceId = 1 << 26;

bool less3(const float* a, const float* b) {
    return std::tie(a[0], a[1], a[2]) < std::tie(b[0], b[1], b[2]);
}

co_shading::Edge make_edge(const float* a, const float* b) {
    co_shading::Edge e{};
    if (less3(b, a)) std::swap(a, b);
    std::copy_n(a, 3, e.a);
    std::copy_n(b, 3, e.b);
    return e;
}

uint64_t edge_key(int32_t a, int32_t b) {
    const auto lo = static_cast<uint32_t>(std::min(a, b));
    const auto hi = static_cast<uint32_t>(std::max(a, b));
    return (static_cast<uint64_t>(lo) << 32) | hi;
}

uint64_t corner_key(int32_t face, int32_t weld) {
    return (static_cast<uint64_t>(static_cast<uint32_t>(face)) << 32) | static_cast<uint32_t>(weld);
}

void trim(std::vector<uint32_t>& words) {
    while (!words.empty() && words.back() == 0) words.pop_back();
}

bool bit(const std::vector<uint32_t>& words, int64_t f) {
    const auto w = static_cast<std::size_t>(f / 32);
    return w < words.size() && ((words[w] >> (f % 32)) & 1u) != 0;
}

void set_bit(std::vector<uint32_t>& words, int64_t f, bool on) {
    const auto w = static_cast<std::size_t>(f / 32);
    const uint32_t mask = 1u << static_cast<uint32_t>(f % 32);
    if (on) {
        if (w >= words.size()) words.resize(w + 1, 0u);
        words[w] |= mask;
    } else if (w < words.size()) {
        words[w] &= ~mask;
    }
}

const float* rep_position(const sd::Soup& soup, int32_t weld) {
    return soup.positions + 3 * static_cast<std::ptrdiff_t>(soup.rep_vertex[static_cast<std::size_t>(weld)]);
}

// Every stored sharp endpoint (a then b per edge) resolved against the soup.
std::vector<int32_t> resolve_edges(const co_shading& s, const sd::Soup& soup) {
    std::vector<float> q;
    q.reserve(s.sharp.size() * 6);
    for (const auto& e : s.sharp) {
        q.insert(q.end(), e.a, e.a + 3);
        q.insert(q.end(), e.b, e.b + 3);
    }
    std::vector<int32_t> w(s.sharp.size() * 2, -1);
    sd::resolve_in_soup(soup, s.epsilon, q.data(), static_cast<int64_t>(w.size()), w.data());
    return w;
}

std::vector<int32_t> resolve_customs(const co_shading& s, const sd::Soup& soup) {
    std::vector<float> q;
    q.reserve(s.custom.size() * 3);
    for (const auto& c : s.custom) q.insert(q.end(), c.p, c.p + 3);
    std::vector<int32_t> w(s.custom.size(), -1);
    sd::resolve_in_soup(soup, s.epsilon, q.data(), static_cast<int64_t>(w.size()), w.data());
    return w;
}

// (face_of, weld, vertex) of every referenced soup vertex, sorted: the soup vertices of a corner.
struct CornerVertices {
    std::vector<std::tuple<uint64_t, int32_t>> rows;  // (corner key, vertex)
    explicit CornerVertices(const sd::Soup& soup) {
        for (int64_t v = 0; v < soup.vertex_count; ++v) {
            const auto i = static_cast<std::size_t>(v);
            if (soup.weld[i] < 0) continue;
            rows.emplace_back(corner_key(soup.face_of[i], soup.weld[i]), static_cast<int32_t>(v));
        }
        std::sort(rows.begin(), rows.end());
    }
    template <typename F>
    void each(int32_t face, int32_t weld, F&& f) const {
        const uint64_t key = corner_key(face, weld);
        auto it = std::lower_bound(rows.begin(), rows.end(), std::make_tuple(key, std::numeric_limits<int32_t>::min()));
        for (; it != rows.end() && std::get<0>(*it) == key; ++it) f(std::get<1>(*it));
    }
    [[nodiscard]] bool has(int32_t face, int32_t weld) const {
        bool found = false;
        each(face, weld, [&](int32_t) { found = true; });
        return found;
    }
};

template <typename F>
auto guarded(const char* fn, F&& body) -> decltype(body()) {
    try {
        return body();
    } catch (const std::exception& e) {
        sd::fail(CO_ERR_ARG, std::string(fn) + ": " + e.what());
    } catch (...) {
        sd::fail(CO_ERR_ARG, std::string(fn) + ": unknown error");
    }
    if constexpr (std::is_pointer_v<decltype(body())>) {
        return nullptr;
    } else {
        return static_cast<decltype(body())>(CO_ERR_ARG);
    }
}

// A document's mesh (or one part's share of it) as a local soup.
struct DocSoup {
    int64_t vertex_start = 0, vertex_count = 0;
    int64_t face_start = 0, face_count = 0;  // face_count < 0 = unbounded (whole document)
    std::vector<int32_t> indices, faces;
    sd::Soup soup;
};

int32_t doc_soup(const co_doc* d, const co_part* part, float epsilon, DocSoup& out, const char* fn) {
    const std::string name(fn);
    const void* vdata = nullptr;
    const int64_t floats = co_doc_mesh_array(d, CO_MESH_VERTICES, &vdata);
    if (floats <= 0 || floats % 3 != 0) return sd::fail(CO_ERR_ARG, name + ": the document has no mesh vertices");
    const int64_t vertices = floats / 3;
    const void* idata = nullptr;
    int64_t index_count = co_doc_mesh_array(d, CO_MESH_INDICES, &idata);
    if (index_count < 0) index_count = 0;
    const int64_t triangles = index_count / 3;
    const void* fdata = nullptr;
    const int64_t face_ids = co_doc_mesh_array(d, CO_MESH_TRIANGLE_FACE_IDS, &fdata);
    if (face_ids >= 0 && face_ids != triangles) {
        return sd::fail(CO_ERR_ARG, name + ": triangleToFaceId length != triangle count");
    }
    const auto* positions = static_cast<const float*>(vdata);
    const auto* idx = static_cast<const int32_t*>(idata);
    const auto* tri_faces = face_ids >= 0 ? static_cast<const int32_t*>(fdata) : nullptr;

    int64_t tri_begin = 0, tri_end = triangles;
    out.vertex_start = 0;
    out.vertex_count = vertices;
    out.face_start = 0;
    out.face_count = -1;
    if (part != nullptr) {
        if (part->vertex_start < 0 || part->vertex_count < 0 ||
            int64_t{part->vertex_start} + part->vertex_count > vertices || part->index_start < 0 ||
            part->index_count < 0 || int64_t{part->index_start} + part->index_count > index_count ||
            part->face_start < 0 || part->face_count < 0) {
            return sd::fail(CO_ERR_RANGE, name + ": part range outside the mesh");
        }
        out.vertex_start = part->vertex_start;
        out.vertex_count = part->vertex_count;
        out.face_start = part->face_start;
        out.face_count = part->face_count;
        tri_begin = part->index_start / 3;
        tri_end = (int64_t{part->index_start} + part->index_count) / 3;
    }
    out.indices.clear();
    out.faces.clear();
    for (int64_t t = tri_begin; t < tri_end; ++t) {
        bool inside = true;
        int32_t local[3];
        for (int k = 0; k < 3; ++k) {
            const int64_t v = int64_t{idx[3 * t + k]} - out.vertex_start;
            inside = inside && v >= 0 && v < out.vertex_count;
            local[k] = static_cast<int32_t>(v);
        }
        const int64_t face = (tri_faces != nullptr ? int64_t{tri_faces[t]} : t) - out.face_start;
        if (!inside || face < 0 || (out.face_count >= 0 && face >= out.face_count) ||
            face > std::numeric_limits<int32_t>::max()) {
            continue;
        }
        out.indices.insert(out.indices.end(), local, local + 3);
        out.faces.push_back(static_cast<int32_t>(face));
    }
    return sd::prepare_soup(out.soup, positions + 3 * out.vertex_start, out.vertex_count, out.indices.data(),
                            static_cast<int64_t>(out.indices.size()), out.faces.data(), epsilon, fn);
}

int32_t doc_error(int32_t rc, const char* fn) {
    return sd::fail(rc, std::string(fn) + ": " + co_last_error());
}

}  // namespace

extern "C" {

co_shading* co_shading_create(float weld_epsilon) {
    return guarded("co_shading_create", [&]() -> co_shading* {
        auto* s = new co_shading();
        s->epsilon = sd::effective_epsilon(weld_epsilon);
        sd::clear_error();
        return s;
    });
}

co_shading* co_shading_clone(const co_shading* s) {
    return guarded("co_shading_clone", [&]() -> co_shading* {
        if (s == nullptr) {
            sd::fail(CO_ERR_ARG, "co_shading_clone: null handle");
            return nullptr;
        }
        sd::clear_error();
        return new co_shading(*s);
    });
}

void co_shading_destroy(co_shading* s) { delete s; }

float co_shading_weld_epsilon(const co_shading* s) { return s == nullptr ? 0.0f : s->epsilon; }

uint64_t co_shading_revision(const co_shading* s) { return s == nullptr ? 0 : s->revision; }

co_shading* co_shading_from_doc(const co_doc* d, const co_part* part, float weld_epsilon) {
    return guarded("co_shading_from_doc", [&]() -> co_shading* {
        constexpr const char* fn = "co_shading_from_doc";
        if (d == nullptr) {
            sd::fail(CO_ERR_ARG, std::string(fn) + ": null document");
            return nullptr;
        }
        const float eps = sd::effective_epsilon(weld_epsilon);
        DocSoup ds;
        if (doc_soup(d, part, eps, ds, fn) != CO_OK) return nullptr;
        const sd::Soup& soup = ds.soup;
        auto s = std::make_unique<co_shading>();
        s->epsilon = eps;

        const uint32_t* words = nullptr;
        const int64_t word_count = std::max<int64_t>(co_doc_shading_smooth(d, &words), 0);
        for (int64_t w = 0; w < word_count; ++w) {
            for (int64_t b = 0; b < 32; ++b) {
                if (((words[w] >> b) & 1u) == 0) continue;
                const int64_t local = w * 32 + b - ds.face_start;
                if (local < 0 || (ds.face_count >= 0 && local >= ds.face_count) || local >= kMaxFaceId) continue;
                set_bit(s->smooth, local, true);
            }
        }
        const auto in_part = [&](int64_t v) { return v >= ds.vertex_start && v < ds.vertex_start + ds.vertex_count; };

        const uint32_t* pairs = nullptr;
        const int64_t edges = std::max<int64_t>(co_doc_shading_sharp_edges(d, &pairs), 0);
        std::vector<uint64_t> seen;
        for (int64_t e = 0; e < edges; ++e) {
            const int64_t a = pairs[2 * e], b = pairs[2 * e + 1];
            if (!in_part(a) || !in_part(b)) continue;
            const int32_t wa = soup.weld[static_cast<std::size_t>(a - ds.vertex_start)];
            const int32_t wb = soup.weld[static_cast<std::size_t>(b - ds.vertex_start)];
            if (wa < 0 || wb < 0 || wa == wb) continue;
            const uint64_t key = edge_key(wa, wb);
            if (std::find(seen.begin(), seen.end(), key) != seen.end()) continue;
            seen.push_back(key);
            s->sharp.push_back(make_edge(rep_position(soup, wa), rep_position(soup, wb)));
        }

        float degrees = 0.0f;
        if (co_doc_shading_auto_smooth(d, &degrees) == 1 && degrees > 0.0f && std::isfinite(degrees)) {
            s->auto_degrees = degrees;
        }

        const int32_t* rows = nullptr;
        const int16_t* oct = nullptr;
        const int64_t row_count = std::max<int64_t>(co_doc_shading_custom_normals(d, &rows, &oct), 0);
        std::map<uint64_t, std::size_t> by_corner;
        for (int64_t r = 0; r < row_count; ++r) {
            if (!in_part(rows[r])) continue;
            const auto v = static_cast<std::size_t>(rows[r] - ds.vertex_start);
            const int32_t w = soup.weld[v];
            if (w < 0) continue;
            co_shading::Custom c{};
            c.face = soup.face_of[v];
            std::copy_n(rep_position(soup, w), 3, c.p);
            c.oct[0] = oct[2 * r];
            c.oct[1] = oct[2 * r + 1];
            const auto [it, added] = by_corner.try_emplace(corner_key(c.face, w), s->custom.size());
            if (added) {
                s->custom.push_back(c);
            } else {
                s->custom[it->second] = c;
            }
        }
        sd::clear_error();
        return s.release();
    });
}

int32_t co_shading_to_doc(const co_shading* s, co_doc* d, const co_part* part) {
    return guarded("co_shading_to_doc", [&]() -> int32_t {
        constexpr const char* fn = "co_shading_to_doc";
        if (s == nullptr || d == nullptr) return sd::fail(CO_ERR_ARG, std::string(fn) + ": null handle");
        DocSoup ds;
        if (int32_t rc = doc_soup(d, part, s->epsilon, ds, fn)) return rc;
        const sd::Soup& soup = ds.soup;
        const bool whole = part == nullptr;
        const auto in_part = [&](int64_t v) { return v >= ds.vertex_start && v < ds.vertex_start + ds.vertex_count; };
        const CornerVertices corners(soup);
        const auto first_vertex = [&](int32_t face, int32_t weld) {
            int32_t first = -1;
            corners.each(face, weld, [&](int32_t v) {
                if (first < 0) first = v;
            });
            return first;
        };

        // Smooth bits.
        std::vector<uint32_t> words;
        if (!whole) {
            const uint32_t* existing = nullptr;
            const int64_t count = std::max<int64_t>(co_doc_shading_smooth(d, &existing), 0);
            words.assign(existing, existing + count);
            for (int64_t f = 0; f < ds.face_count; ++f) set_bit(words, ds.face_start + f, false);
        }
        for (std::size_t w = 0; w < s->smooth.size(); ++w) {
            for (uint32_t b = 0; b < 32; ++b) {
                if (((s->smooth[w] >> b) & 1u) == 0) continue;
                const auto local = static_cast<int64_t>(w * 32 + b);
                if (!whole && local >= ds.face_count) continue;
                set_bit(words, ds.face_start + local, true);
            }
        }
        trim(words);

        // Sharp edges: the soup pair of the first face (lowest id) whose boundary runs the edge.
        std::vector<std::tuple<uint64_t, int32_t, int32_t, int32_t>> runs;  // (edge key, face, from, to)
        runs.reserve(soup.boundary.size());
        for (const auto& e : soup.boundary) runs.emplace_back(edge_key(e.from, e.to), e.face, e.from, e.to);
        std::sort(runs.begin(), runs.end());
        std::vector<uint32_t> pairs;
        {
            const uint32_t* existing = nullptr;
            const int64_t count = std::max<int64_t>(co_doc_shading_sharp_edges(d, &existing), 0);
            for (int64_t e = 0; e < count && !whole; ++e) {
                if (in_part(existing[2 * e]) && in_part(existing[2 * e + 1])) continue;
                pairs.push_back(existing[2 * e]);
                pairs.push_back(existing[2 * e + 1]);
            }
        }
        const std::vector<int32_t> ew = resolve_edges(*s, soup);
        std::vector<std::pair<uint32_t, uint32_t>> fresh;
        for (std::size_t e = 0; e < s->sharp.size(); ++e) {
            const int32_t wa = ew[2 * e], wb = ew[2 * e + 1];
            if (wa < 0 || wb < 0 || wa == wb) continue;
            const uint64_t key = edge_key(wa, wb);
            auto it = std::lower_bound(runs.begin(), runs.end(),
                                       std::make_tuple(key, std::numeric_limits<int32_t>::min(),
                                                       std::numeric_limits<int32_t>::min(),
                                                       std::numeric_limits<int32_t>::min()));
            if (it == runs.end() || std::get<0>(*it) != key) continue;
            const int32_t va = first_vertex(std::get<1>(*it), std::get<2>(*it));
            const int32_t vb = first_vertex(std::get<1>(*it), std::get<3>(*it));
            if (va < 0 || vb < 0) continue;
            fresh.emplace_back(static_cast<uint32_t>(va + ds.vertex_start), static_cast<uint32_t>(vb + ds.vertex_start));
        }
        std::sort(fresh.begin(), fresh.end());
        fresh.erase(std::unique(fresh.begin(), fresh.end()), fresh.end());
        for (const auto& [a, b] : fresh) {
            pairs.push_back(a);
            pairs.push_back(b);
        }

        // Custom normals: one row per soup vertex of the corner (a later store entry wins).
        std::vector<int32_t> rows;
        std::vector<int16_t> octs;
        {
            const int32_t* existing = nullptr;
            const int16_t* existing_oct = nullptr;
            const int64_t count = std::max<int64_t>(co_doc_shading_custom_normals(d, &existing, &existing_oct), 0);
            for (int64_t r = 0; r < count && !whole; ++r) {
                if (in_part(existing[r])) continue;
                rows.push_back(existing[r]);
                octs.push_back(existing_oct[2 * r]);
                octs.push_back(existing_oct[2 * r + 1]);
            }
        }
        const std::vector<int32_t> cw = resolve_customs(*s, soup);
        std::map<int32_t, std::pair<int16_t, int16_t>> by_vertex;
        for (std::size_t c = 0; c < s->custom.size(); ++c) {
            if (cw[c] < 0) continue;
            const auto& entry = s->custom[c];
            corners.each(entry.face, cw[c], [&](int32_t v) {
                by_vertex[static_cast<int32_t>(v + ds.vertex_start)] = {entry.oct[0], entry.oct[1]};
            });
        }
        for (const auto& [v, o] : by_vertex) {
            rows.push_back(v);
            octs.push_back(o.first);
            octs.push_back(o.second);
        }

        if (int32_t rc = co_doc_shading_set_smooth(d, words.data(), static_cast<int64_t>(words.size()))) {
            return doc_error(rc, fn);
        }
        if (int32_t rc = co_doc_shading_set_sharp_edges(d, pairs.data(), static_cast<int64_t>(pairs.size() / 2))) {
            return doc_error(rc, fn);
        }
        if (int32_t rc = co_doc_shading_set_custom_normals(d, rows.data(), octs.data(), static_cast<int64_t>(rows.size()))) {
            return doc_error(rc, fn);
        }
        if (s->auto_degrees > 0.0f) {
            if (int32_t rc = co_doc_shading_set_auto_smooth(d, &s->auto_degrees)) return doc_error(rc, fn);
        } else if (whole) {
            if (int32_t rc = co_doc_shading_set_auto_smooth(d, nullptr)) return doc_error(rc, fn);
        }
        sd::clear_error();
        return CO_OK;
    });
}

int32_t co_shading_set_smooth(co_shading* s, const int32_t* face_ids, int64_t count, int32_t smooth) {
    return guarded("co_shading_set_smooth", [&]() -> int32_t {
        if (s == nullptr) return sd::fail(CO_ERR_ARG, "co_shading_set_smooth: null handle");
        if (count < 0 || (count > 0 && face_ids == nullptr)) return sd::fail(CO_ERR_ARG, "co_shading_set_smooth: bad ids");
        for (int64_t i = 0; i < count; ++i) {
            if (face_ids[i] < 0 || face_ids[i] >= kMaxFaceId) {
                return sd::fail(CO_ERR_RANGE, "co_shading_set_smooth: face id " + std::to_string(face_ids[i]) +
                                                  " out of range");
            }
        }
        bool changed = false;
        for (int64_t i = 0; i < count; ++i) {
            if (bit(s->smooth, face_ids[i]) != (smooth != 0)) {
                set_bit(s->smooth, face_ids[i], smooth != 0);
                changed = true;
            }
        }
        trim(s->smooth);
        if (changed) ++s->revision;
        sd::clear_error();
        return CO_OK;
    });
}

int32_t co_shading_is_smooth(const co_shading* s, int32_t face_id) {
    return s != nullptr && face_id >= 0 && bit(s->smooth, face_id) ? 1 : 0;
}

int64_t co_shading_smooth_words(const co_shading* s, const uint32_t** out_words) {
    if (out_words != nullptr) *out_words = nullptr;
    if (s == nullptr) return sd::fail(CO_ERR_ARG, "co_shading_smooth_words: null handle");
    if (out_words != nullptr && !s->smooth.empty()) *out_words = s->smooth.data();
    return static_cast<int64_t>(s->smooth.size());
}

int32_t co_shading_set_auto_smooth(co_shading* s, float degrees) {
    if (s == nullptr) return sd::fail(CO_ERR_ARG, "co_shading_set_auto_smooth: null handle");
    const float value = (degrees > 0.0f && std::isfinite(degrees)) ? degrees : 0.0f;
    if (value != s->auto_degrees) {
        s->auto_degrees = value;
        ++s->revision;
    }
    sd::clear_error();
    return CO_OK;
}

float co_shading_auto_smooth(const co_shading* s) { return s == nullptr ? 0.0f : s->auto_degrees; }

int32_t co_shading_mark_sharp(co_shading* s, const float* positions, int64_t vertex_count, const uint32_t* pairs,
                              int64_t edge_count, int32_t sharp) {
    return guarded("co_shading_mark_sharp", [&]() -> int32_t {
        constexpr const char* fn = "co_shading_mark_sharp";
        if (s == nullptr) return sd::fail(CO_ERR_ARG, std::string(fn) + ": null handle");
        if (vertex_count < 0 || vertex_count > std::numeric_limits<int32_t>::max() ||
            (vertex_count > 0 && positions == nullptr) || edge_count < 0 || (edge_count > 0 && pairs == nullptr)) {
            return sd::fail(CO_ERR_ARG, std::string(fn) + ": bad arguments");
        }
        for (int64_t i = 0; i < edge_count * 2; ++i) {
            if (int64_t{pairs[i]} >= vertex_count) return sd::fail(CO_ERR_RANGE, std::string(fn) + ": vertex out of range");
        }
        std::vector<int32_t> weld, rep;
        sd::weld_vertices(positions, vertex_count, nullptr, s->epsilon, weld, rep);
        // The stored edges in this soup's welds.
        std::vector<float> q;
        for (const auto& e : s->sharp) {
            q.insert(q.end(), e.a, e.a + 3);
            q.insert(q.end(), e.b, e.b + 3);
        }
        std::vector<int32_t> ew(s->sharp.size() * 2, -1);
        sd::resolve_points(positions, weld.data(), vertex_count, s->epsilon, q.data(), static_cast<int64_t>(ew.size()),
                           ew.data());
        std::vector<uint64_t> keys(s->sharp.size(), ~uint64_t{0});
        for (std::size_t e = 0; e < s->sharp.size(); ++e) {
            if (ew[2 * e] >= 0 && ew[2 * e + 1] >= 0 && ew[2 * e] != ew[2 * e + 1]) keys[e] = edge_key(ew[2 * e], ew[2 * e + 1]);
        }
        bool changed = false;
        for (int64_t i = 0; i < edge_count; ++i) {
            const int32_t wa = weld[pairs[2 * i]], wb = weld[pairs[2 * i + 1]];
            if (wa == wb) continue;
            const uint64_t key = edge_key(wa, wb);
            if (sharp != 0) {
                if (std::find(keys.begin(), keys.end(), key) != keys.end()) continue;
                const float* pa = positions + 3 * static_cast<std::ptrdiff_t>(rep[static_cast<std::size_t>(wa)]);
                const float* pb = positions + 3 * static_cast<std::ptrdiff_t>(rep[static_cast<std::size_t>(wb)]);
                s->sharp.push_back(make_edge(pa, pb));
                keys.push_back(key);
                changed = true;
            } else {
                for (std::size_t e = 0; e < keys.size(); ++e) {
                    if (keys[e] == key) {
                        keys[e] = ~uint64_t{1};  // marked for removal
                        changed = true;
                    }
                }
            }
        }
        if (sharp == 0 && changed) {
            std::vector<co_shading::Edge> kept;
            for (std::size_t e = 0; e < s->sharp.size(); ++e) {
                if (keys[e] != ~uint64_t{1}) kept.push_back(s->sharp[e]);
            }
            s->sharp = std::move(kept);
        }
        if (changed) ++s->revision;
        sd::clear_error();
        return CO_OK;
    });
}

int32_t co_shading_clear_all_sharp(co_shading* s) {
    if (s == nullptr) return sd::fail(CO_ERR_ARG, "co_shading_clear_all_sharp: null handle");
    if (!s->sharp.empty()) {
        s->sharp.clear();
        ++s->revision;
    }
    sd::clear_error();
    return CO_OK;
}

int64_t co_shading_sharp_edges(const co_shading* s, float* out_positions, int64_t capacity) {
    if (s == nullptr) return sd::fail(CO_ERR_ARG, "co_shading_sharp_edges: null handle");
    const auto n = static_cast<int64_t>(s->sharp.size());
    if (out_positions != nullptr) {
        for (int64_t e = 0; e < std::min(n, capacity); ++e) {
            std::copy_n(s->sharp[static_cast<std::size_t>(e)].a, 3, out_positions + 6 * e);
            std::copy_n(s->sharp[static_cast<std::size_t>(e)].b, 3, out_positions + 6 * e + 3);
        }
    }
    return n;
}

int32_t co_shading_set_custom_normals(co_shading* s, const float* positions, int64_t vertex_count,
                                      const int32_t* indices, int64_t index_count, const int32_t* tri_face_ids,
                                      const int32_t* vertices, const int16_t* oct, int64_t count) {
    return guarded("co_shading_set_custom_normals", [&]() -> int32_t {
        constexpr const char* fn = "co_shading_set_custom_normals";
        if (s == nullptr) return sd::fail(CO_ERR_ARG, std::string(fn) + ": null handle");
        if (count < 0 || (count > 0 && (vertices == nullptr || oct == nullptr))) {
            return sd::fail(CO_ERR_ARG, std::string(fn) + ": bad rows");
        }
        sd::Soup soup;
        if (int32_t rc = sd::prepare_soup(soup, positions, vertex_count, indices, index_count, tri_face_ids, s->epsilon, fn)) {
            return rc;
        }
        for (int64_t r = 0; r < count; ++r) {
            if (vertices[r] < 0 || vertices[r] >= vertex_count || soup.weld[static_cast<std::size_t>(vertices[r])] < 0) {
                return sd::fail(CO_ERR_RANGE, std::string(fn) + ": row " + std::to_string(r) +
                                                  " names an out-of-range or unreferenced vertex");
            }
        }
        const std::vector<int32_t> cw = resolve_customs(*s, soup);
        std::map<uint64_t, std::size_t> by_corner;
        for (std::size_t c = 0; c < s->custom.size(); ++c) {
            if (cw[c] >= 0) by_corner[corner_key(s->custom[c].face, cw[c])] = c;
        }
        for (int64_t r = 0; r < count; ++r) {
            const auto v = static_cast<std::size_t>(vertices[r]);
            const int32_t w = soup.weld[v];
            co_shading::Custom c{};
            c.face = soup.face_of[v];
            std::copy_n(rep_position(soup, w), 3, c.p);
            c.oct[0] = oct[2 * r];
            c.oct[1] = oct[2 * r + 1];
            const auto [it, added] = by_corner.try_emplace(corner_key(c.face, w), s->custom.size());
            if (added) {
                s->custom.push_back(c);
            } else {
                s->custom[it->second] = c;
            }
        }
        if (count > 0) ++s->revision;
        sd::clear_error();
        return CO_OK;
    });
}

int32_t co_shading_clear_custom_normals(co_shading* s, const int32_t* face_ids, int64_t count) {
    return guarded("co_shading_clear_custom_normals", [&]() -> int32_t {
        if (s == nullptr) return sd::fail(CO_ERR_ARG, "co_shading_clear_custom_normals: null handle");
        if (count < 0) return sd::fail(CO_ERR_ARG, "co_shading_clear_custom_normals: negative count");
        const std::size_t before = s->custom.size();
        if (face_ids == nullptr) {
            s->custom.clear();
        } else {
            std::vector<int32_t> faces(face_ids, face_ids + count);
            std::sort(faces.begin(), faces.end());
            std::erase_if(s->custom, [&](const co_shading::Custom& c) {
                return std::binary_search(faces.begin(), faces.end(), c.face);
            });
        }
        if (s->custom.size() != before) ++s->revision;
        sd::clear_error();
        return CO_OK;
    });
}

int64_t co_shading_custom_normals(const co_shading* s, int32_t* out_faces, float* out_positions, int16_t* out_oct,
                                  int64_t capacity) {
    if (s == nullptr) return sd::fail(CO_ERR_ARG, "co_shading_custom_normals: null handle");
    const auto n = static_cast<int64_t>(s->custom.size());
    for (int64_t i = 0; i < std::min(n, capacity); ++i) {
        const auto& c = s->custom[static_cast<std::size_t>(i)];
        if (out_faces != nullptr) out_faces[i] = c.face;
        if (out_positions != nullptr) std::copy_n(c.p, 3, out_positions + 3 * i);
        if (out_oct != nullptr) std::copy_n(c.oct, 2, out_oct + 2 * i);
    }
    return n;
}

int32_t co_shading_compute(const co_shading* s, const float* positions, int64_t vertex_count, const int32_t* indices,
                           int64_t index_count, const int32_t* tri_face_ids, float* out_xyz, int16_t* out_oct) {
    return guarded("co_shading_compute", [&]() -> int32_t {
        constexpr const char* fn = "co_shading_compute";
        if (s == nullptr) return sd::fail(CO_ERR_ARG, std::string(fn) + ": null handle");
        if (out_xyz == nullptr && vertex_count > 0) return sd::fail(CO_ERR_ARG, std::string(fn) + ": null out_xyz");
        sd::Soup soup;
        if (int32_t rc = sd::prepare_soup(soup, positions, vertex_count, indices, index_count, tri_face_ids, s->epsilon, fn)) {
            return rc;
        }
        const std::vector<int32_t> ew = resolve_edges(*s, soup);
        std::vector<uint64_t> sharp;
        for (std::size_t e = 0; e < s->sharp.size(); ++e) {
            if (ew[2 * e] >= 0 && ew[2 * e + 1] >= 0 && ew[2 * e] != ew[2 * e + 1]) sharp.push_back(edge_key(ew[2 * e], ew[2 * e + 1]));
        }
        std::sort(sharp.begin(), sharp.end());
        sharp.erase(std::unique(sharp.begin(), sharp.end()), sharp.end());
        std::vector<sd::CustomRow> custom;
        if (!s->custom.empty()) {
            const CornerVertices corners(soup);
            const std::vector<int32_t> cw = resolve_customs(*s, soup);
            for (std::size_t c = 0; c < s->custom.size(); ++c) {
                if (cw[c] < 0) continue;
                const auto& entry = s->custom[c];
                corners.each(entry.face, cw[c], [&](int32_t v) { custom.push_back({v, {entry.oct[0], entry.oct[1]}}); });
            }
        }
        const int32_t rc = sd::compute_normals(soup, s->smooth.data(), static_cast<int64_t>(s->smooth.size()), false,
                                               sharp, s->auto_degrees, custom, out_xyz, out_oct);
        if (rc == CO_OK) sd::clear_error();
        return rc;
    });
}

int32_t co_shading_reconcile(co_shading* s, const float* positions, int64_t vertex_count, const int32_t* indices,
                             int64_t index_count, const int32_t* tri_face_ids, int64_t* out_dropped) {
    return guarded("co_shading_reconcile", [&]() -> int32_t {
        constexpr const char* fn = "co_shading_reconcile";
        if (s == nullptr) return sd::fail(CO_ERR_ARG, std::string(fn) + ": null handle");
        sd::Soup soup;
        if (int32_t rc = sd::prepare_soup(soup, positions, vertex_count, indices, index_count, tri_face_ids, s->epsilon, fn)) {
            return rc;
        }
        int64_t dropped[3] = {0, 0, 0};
        bool changed = false;

        for (std::size_t w = 0; w < s->smooth.size(); ++w) {
            for (uint32_t b = 0; b < 32; ++b) {
                if (((s->smooth[w] >> b) & 1u) == 0) continue;
                const auto f = static_cast<int32_t>(w * 32 + b);
                if (std::binary_search(soup.faces.begin(), soup.faces.end(), f)) continue;
                s->smooth[w] &= ~(1u << b);
                ++dropped[0];
            }
        }
        trim(s->smooth);

        std::vector<uint64_t> mesh_edges;
        mesh_edges.reserve(soup.boundary.size());
        for (const auto& e : soup.boundary) mesh_edges.push_back(edge_key(e.from, e.to));
        std::sort(mesh_edges.begin(), mesh_edges.end());
        const std::vector<int32_t> ew = resolve_edges(*s, soup);
        std::vector<co_shading::Edge> kept_edges;
        std::vector<uint64_t> kept_keys;
        for (std::size_t e = 0; e < s->sharp.size(); ++e) {
            const int32_t wa = ew[2 * e], wb = ew[2 * e + 1];
            const uint64_t key = (wa >= 0 && wb >= 0 && wa != wb) ? edge_key(wa, wb) : ~uint64_t{0};
            if (key == ~uint64_t{0} || !std::binary_search(mesh_edges.begin(), mesh_edges.end(), key) ||
                std::find(kept_keys.begin(), kept_keys.end(), key) != kept_keys.end()) {
                ++dropped[1];
                continue;
            }
            kept_keys.push_back(key);
            kept_edges.push_back(make_edge(rep_position(soup, wa), rep_position(soup, wb)));
        }
        if (kept_edges.size() == s->sharp.size()) {
            for (std::size_t e = 0; e < kept_edges.size(); ++e) {
                changed = changed || std::memcmp(&kept_edges[e], &s->sharp[e], sizeof(co_shading::Edge)) != 0;
            }
        }
        s->sharp = std::move(kept_edges);

        const CornerVertices corners(soup);
        const std::vector<int32_t> cw = resolve_customs(*s, soup);
        std::vector<co_shading::Custom> kept_custom;
        std::vector<uint64_t> seen;
        for (std::size_t i = s->custom.size(); i-- > 0;) {  // a later duplicate wins
            const auto& c = s->custom[i];
            const int32_t w = cw[i];
            const uint64_t key = w >= 0 ? corner_key(c.face, w) : 0;
            if (w < 0 || !corners.has(c.face, w) || std::find(seen.begin(), seen.end(), key) != seen.end()) {
                ++dropped[2];
                continue;
            }
            seen.push_back(key);
            co_shading::Custom snapped = c;
            std::copy_n(rep_position(soup, w), 3, snapped.p);
            changed = changed || std::memcmp(&snapped, &c, sizeof(co_shading::Custom)) != 0;
            kept_custom.push_back(snapped);
        }
        std::reverse(kept_custom.begin(), kept_custom.end());
        s->custom = std::move(kept_custom);

        if (changed || dropped[0] + dropped[1] + dropped[2] > 0) ++s->revision;
        if (out_dropped != nullptr) std::copy_n(dropped, 3, out_dropped);
        sd::clear_error();
        return CO_OK;
    });
}

int32_t co_shading_move_vertices(co_shading* s, const float* old_positions, const float* new_positions, int64_t count) {
    return guarded("co_shading_move_vertices", [&]() -> int32_t {
        constexpr const char* fn = "co_shading_move_vertices";
        if (s == nullptr) return sd::fail(CO_ERR_ARG, std::string(fn) + ": null handle");
        if (count < 0 || count > std::numeric_limits<int32_t>::max() ||
            (count > 0 && (old_positions == nullptr || new_positions == nullptr))) {
            return sd::fail(CO_ERR_ARG, std::string(fn) + ": bad arguments");
        }
        if (count == 0) {
            sd::clear_error();
            return CO_OK;
        }
        std::vector<float> q;
        q.reserve(s->sharp.size() * 6 + s->custom.size() * 3);
        for (const auto& e : s->sharp) {
            q.insert(q.end(), e.a, e.a + 3);
            q.insert(q.end(), e.b, e.b + 3);
        }
        for (const auto& c : s->custom) q.insert(q.end(), c.p, c.p + 3);
        std::vector<int32_t> hit(q.size() / 3, -1);
        sd::resolve_points(old_positions, nullptr, count, s->epsilon, q.data(), static_cast<int64_t>(hit.size()),
                           hit.data());
        const auto moved = [&](std::size_t k, float* p) {
            if (hit[k] < 0) return false;
            const float* to = new_positions + 3 * static_cast<std::ptrdiff_t>(hit[k]);
            const bool differs = p[0] != to[0] || p[1] != to[1] || p[2] != to[2];
            std::copy_n(to, 3, p);
            return differs;
        };
        bool changed = false;
        std::size_t k = 0;
        for (auto& e : s->sharp) {
            float a[3], b[3];
            std::copy_n(e.a, 3, a);
            std::copy_n(e.b, 3, b);
            changed = moved(k++, a) || changed;
            changed = moved(k++, b) || changed;
            e = make_edge(a, b);
        }
        for (auto& c : s->custom) changed = moved(k++, c.p) || changed;
        if (changed) ++s->revision;
        sd::clear_error();
        return CO_OK;
    });
}

}  // extern "C"
