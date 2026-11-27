// Cenda model masks (OpenMason #172): vertex groups -> a triangle order where any hidden-group choice is a few
// contiguous index ranges. Rules in cenda/model_mask.h. No exception leaves this file.
#include "model_internal.hpp"
#include "cenda/model_mask.h"

#include <algorithm>
#include <bit>
#include <cstddef>
#include <cstdint>
#include <exception>
#include <iterator>
#include <limits>
#include <memory>
#include <string>
#include <type_traits>
#include <unordered_map>
#include <utility>
#include <vector>

struct co_mask_plan {
    int32_t vertex_count = 0;
    int32_t group_count = 0;
    std::vector<std::string> names;
    std::vector<int32_t> indices;      // reordered, 3 per triangle
    std::vector<int32_t> permutation;  // slot -> original triangle
    // Segments in buffer order.
    std::vector<int32_t> seg_key, seg_first_tri, seg_tri_count;
    // Signatures, CSR, in their documented order (id = rank).
    std::vector<int32_t> sig_offsets{0}, sig_groups;
    // Classes in buffer order.
    std::vector<int32_t> class_sig, class_first_tri, class_tri_count, class_segment;
};

namespace {

// Vector element by a signed index (the build is -Wsign-conversion clean).
template <typename Vec>
auto& el(Vec& v, int64_t i) {
    return v[static_cast<size_t>(i)];
}

std::string& last_error() { return cenda::model::co_error_message(); }  // the one store behind co_last_error

int32_t fail(int32_t code, const std::string& message) {
    last_error() = message;
    return code;
}

void clear_error() { last_error().clear(); }

template <typename F>
auto guarded(const char* fn, F&& body) -> decltype(body()) {
    try {
        return body();
    } catch (const std::exception& e) {
        fail(CO_ERR_ARG, std::string(fn) + ": " + e.what());
    } catch (...) {
        fail(CO_ERR_ARG, std::string(fn) + ": unknown error");
    }
    if constexpr (std::is_pointer_v<decltype(body())>) {
        return nullptr;
    } else {
        return static_cast<decltype(body())>(CO_ERR_ARG);
    }
}

struct SigHash {
    size_t operator()(const std::vector<int32_t>& v) const noexcept {
        uint64_t h = 0xcbf29ce484222325ull ^ v.size();
        for (int32_t x : v) {
            h ^= static_cast<uint32_t>(x);
            h *= 0x100000001b3ull;
            h ^= h >> 29;
        }
        return static_cast<size_t>(h);
    }
};

// Everything the builder needs, already validated. Group rows name SOUP vertices.
struct Input {
    const int32_t* indices = nullptr;
    int64_t triangles = 0;
    int32_t vertex_count = 0;
    const int32_t* keys = nullptr;  // per triangle, or null
    int32_t group_count = 0;
    std::vector<std::string> names;
    const int32_t* group_rows = nullptr;  // group_count + 1 offsets (null when group_count == 0)
    const int32_t* vertices = nullptr;
    const float* weights = nullptr;  // null = every row is in its group
};

// Checks the raw arrays; on success fills the geometry + group fields of `in`.
int32_t validate(const char* fn, const int32_t* indices, int64_t index_count, int32_t vertex_count,
                 int32_t group_count, const int32_t* group_rows, const int32_t* vertices, Input& in) {
    const std::string name(fn);
    if (index_count < 0) return fail(CO_ERR_ARG, name + ": negative index count");
    if (index_count % 3 != 0) return fail(CO_ERR_ARG, name + ": index count is not a multiple of 3");
    if (index_count > std::numeric_limits<int32_t>::max()) {
        return fail(CO_ERR_ARG, name + ": more indices than an int32 can count");
    }
    if (index_count > 0 && indices == nullptr) return fail(CO_ERR_ARG, name + ": null indices");
    if (vertex_count < 0) return fail(CO_ERR_ARG, name + ": negative vertex count");
    if (group_count < 0) return fail(CO_ERR_ARG, name + ": negative group count");
    for (int64_t i = 0; i < index_count; ++i) {
        if (indices[i] < 0 || indices[i] >= vertex_count) {
            return fail(CO_ERR_RANGE, name + ": index " + std::to_string(i) + " (" + std::to_string(indices[i]) +
                                          ") outside [0, " + std::to_string(vertex_count) + ")");
        }
    }
    int32_t rows = 0;
    if (group_count > 0) {
        if (group_rows == nullptr) return fail(CO_ERR_ARG, name + ": null group_rows");
        if (group_rows[0] != 0) return fail(CO_ERR_ARG, name + ": group_rows[0] != 0");
        for (int32_t g = 0; g < group_count; ++g) {
            if (group_rows[g + 1] < group_rows[g]) {
                return fail(CO_ERR_ARG, name + ": group_rows decrease at group " + std::to_string(g));
            }
        }
        rows = group_rows[group_count];
    }
    if (rows > 0 && vertices == nullptr) return fail(CO_ERR_ARG, name + ": null group vertices");
    for (int32_t r = 0; r < rows; ++r) {
        if (vertices[r] < 0 || vertices[r] >= vertex_count) {
            return fail(CO_ERR_RANGE, name + ": group row " + std::to_string(r) + " names vertex " +
                                          std::to_string(vertices[r]) + " outside [0, " +
                                          std::to_string(vertex_count) + ")");
        }
    }
    in.indices = indices;
    in.triangles = index_count / 3;
    in.vertex_count = vertex_count;
    in.group_count = group_count;
    in.group_rows = group_rows;
    in.vertices = vertices;
    return CO_OK;
}

co_mask_plan* build(const Input& in) {
    auto plan = std::make_unique<co_mask_plan>();
    co_mask_plan& p = *plan;
    p.vertex_count = in.vertex_count;
    p.group_count = in.group_count;
    p.names = in.names;
    p.names.resize(static_cast<size_t>(in.group_count));

    // 1. Vertex -> ascending unique groups (CSR). Groups are visited in ascending order, so each list comes out
    //    sorted; `last` drops a second row of one group naming the same vertex.
    const auto V = static_cast<size_t>(in.vertex_count);
    std::vector<int32_t> v_off(V + 1, 0);
    std::vector<int32_t> last(V, -1);
    auto member = [&](int32_t r) { return in.weights == nullptr || in.weights[r] > 0.0f; };
    for (int32_t g = 0; g < in.group_count; ++g) {
        for (int32_t r = in.group_rows[g]; r < in.group_rows[g + 1]; ++r) {
            const auto v = static_cast<size_t>(in.vertices[r]);
            if (member(r) && last[v] != g) {
                last[v] = g;
                ++v_off[v + 1];
            }
        }
    }
    for (size_t v = 0; v < V; ++v) v_off[v + 1] += v_off[v];
    std::vector<int32_t> v_groups(static_cast<size_t>(v_off[V]));
    {
        std::vector<int32_t> cursor(v_off.begin(), v_off.end() - 1);
        std::fill(last.begin(), last.end(), -1);
        for (int32_t g = 0; g < in.group_count; ++g) {
            for (int32_t r = in.group_rows[g]; r < in.group_rows[g + 1]; ++r) {
                const auto v = static_cast<size_t>(in.vertices[r]);
                if (member(r) && last[v] != g) {
                    last[v] = g;
                    el(v_groups, cursor[v]++) = g;
                }
            }
        }
    }

    // 2. Per triangle: signature = intersection of its three vertices' groups, interned.
    const auto T = static_cast<size_t>(in.triangles);
    std::vector<int32_t> tri_sig(T);
    std::unordered_map<std::vector<int32_t>, int32_t, SigHash> interned;
    std::vector<std::vector<int32_t>> sigs;
    std::vector<int32_t> scratch, scratch2;
    const int32_t* vg = v_groups.data();
    for (size_t t = 0; t < T; ++t) {
        const auto a = static_cast<size_t>(in.indices[3 * t]);
        const auto b = static_cast<size_t>(in.indices[3 * t + 1]);
        const auto c = static_cast<size_t>(in.indices[3 * t + 2]);
        const int32_t *a0 = vg + v_off[a], *a1 = vg + v_off[a + 1];
        const int32_t *b0 = vg + v_off[b], *b1 = vg + v_off[b + 1];
        const int32_t *c0 = vg + v_off[c], *c1 = vg + v_off[c + 1];
        scratch.clear();
        if (a0 != a1 && b0 != b1 && c0 != c1) {
            scratch2.clear();
            std::set_intersection(a0, a1, b0, b1, std::back_inserter(scratch2));
            std::set_intersection(scratch2.begin(), scratch2.end(), c0, c1, std::back_inserter(scratch));
        }
        auto it = interned.find(scratch);
        if (it == interned.end()) {
            it = interned.emplace(scratch, static_cast<int32_t>(sigs.size())).first;
            sigs.push_back(scratch);
        }
        tri_sig[t] = it->second;
    }
    interned.clear();

    // 3. Signature ids in the documented order (lexicographic over ascending group ids; {} first).
    std::vector<int32_t> order(sigs.size());
    for (size_t i = 0; i < order.size(); ++i) order[i] = static_cast<int32_t>(i);
    std::sort(order.begin(), order.end(), [&](int32_t x, int32_t y) { return el(sigs, x) < el(sigs, y); });
    std::vector<int32_t> rank(sigs.size());
    for (size_t i = 0; i < order.size(); ++i) {
        el(rank, order[i]) = static_cast<int32_t>(i);
        const auto& s = el(sigs, order[i]);
        p.sig_groups.insert(p.sig_groups.end(), s.begin(), s.end());
        p.sig_offsets.push_back(static_cast<int32_t>(p.sig_groups.size()));
    }
    for (auto& s : tri_sig) s = el(rank, s);

    // 4. Segments (key runs); a stable sort by signature inside each; classes.
    p.permutation.resize(T);
    p.indices.resize(3 * T);
    for (size_t t = 0; t < T; ++t) p.permutation[t] = static_cast<int32_t>(t);
    size_t begin = 0;
    while (begin < T) {
        const int32_t key = in.keys != nullptr ? in.keys[begin] : 0;
        size_t end = begin + 1;
        while (end < T && (in.keys == nullptr || in.keys[end] == key)) ++end;
        const auto segment = static_cast<int32_t>(p.seg_key.size());
        p.seg_key.push_back(key);
        p.seg_first_tri.push_back(static_cast<int32_t>(begin));
        p.seg_tri_count.push_back(static_cast<int32_t>(end - begin));
        std::stable_sort(p.permutation.begin() + static_cast<std::ptrdiff_t>(begin),
                         p.permutation.begin() + static_cast<std::ptrdiff_t>(end),
                         [&](int32_t x, int32_t y) { return el(tri_sig, x) < el(tri_sig, y); });
        for (size_t s = begin; s < end; ++s) {
            const int32_t sig = el(tri_sig, p.permutation[s]);
            if (s == begin || sig != p.class_sig.back()) {
                p.class_sig.push_back(sig);
                p.class_first_tri.push_back(static_cast<int32_t>(s));
                p.class_tri_count.push_back(0);
                p.class_segment.push_back(segment);
            }
            ++p.class_tri_count.back();
        }
        begin = end;
    }
    for (size_t s = 0; s < T; ++s) {
        const auto t = static_cast<size_t>(p.permutation[s]);
        p.indices[3 * s] = in.indices[3 * t];
        p.indices[3 * s + 1] = in.indices[3 * t + 1];
        p.indices[3 * s + 2] = in.indices[3 * t + 2];
    }
    return plan.release();
}

int32_t null_plan(const co_mask_plan* p, const char* fn) {
    return p == nullptr ? fail(CO_ERR_ARG, std::string(fn) + ": null plan") : CO_OK;
}

// Bitset checks: word count >= 0, words non-null when > 0, no set bit at or beyond group_count.
int32_t check_bits(const uint64_t* words, int32_t count, int32_t group_count, const char* what) {
    if (count < 0 || (count > 0 && words == nullptr)) {
        return fail(CO_ERR_ARG, std::string("co_mask_ranges: bad ") + what + " bitset");
    }
    for (int32_t w = 0; w < count; ++w) {
        const uint64_t bits = words[w];
        if (bits == 0) continue;
        const int64_t top = int64_t{w} * 64 + 63 - std::countl_zero(bits);
        if (top >= group_count) {
            return fail(CO_ERR_RANGE, std::string("co_mask_ranges: ") + what + " bit " + std::to_string(top) +
                                          " is not a group (" + std::to_string(group_count) + " groups)");
        }
    }
    return CO_OK;
}

bool bit(const uint64_t* words, int32_t count, int32_t g) {
    const int32_t w = g >> 6;
    return w < count && ((words[w] >> (g & 63)) & 1u) != 0;
}

}  // namespace

extern "C" {

co_mask_plan* co_mask_plan_create(const int32_t* indices, int64_t index_count, int32_t vertex_count,
                                  const int32_t* tri_keys, int32_t group_count, const char* const* names,
                                  const int32_t* group_rows, const int32_t* vertices, const float* weights) {
    constexpr const char* fn = "co_mask_plan_create";
    return guarded(fn, [&]() -> co_mask_plan* {
        Input in;
        if (validate(fn, indices, index_count, vertex_count, group_count, group_rows, vertices, in) != CO_OK) {
            return nullptr;
        }
        in.keys = tri_keys;
        in.weights = weights;
        if (names != nullptr) {
            in.names.reserve(static_cast<size_t>(group_count));
            for (int32_t g = 0; g < group_count; ++g) in.names.emplace_back(names[g] != nullptr ? names[g] : "");
        }
        co_mask_plan* p = build(in);
        clear_error();
        return p;
    });
}

co_mask_plan* co_mask_plan_from_doc(const co_doc* d, int32_t key_mode, const int32_t* tri_keys,
                                    int64_t tri_key_count) {
    constexpr const char* fn = "co_mask_plan_from_doc";
    return guarded(fn, [&]() -> co_mask_plan* {
        const std::string name(fn);
        if (d == nullptr) {
            fail(CO_ERR_ARG, name + ": null document");
            return nullptr;
        }
        if (key_mode < CO_MASK_KEY_NONE || key_mode > CO_MASK_KEY_CUSTOM) {
            fail(CO_ERR_ARG, name + ": unknown key mode " + std::to_string(key_mode));
            return nullptr;
        }
        const void* vdata = nullptr;
        int64_t floats = co_doc_mesh_array(d, CO_MESH_VERTICES, &vdata);
        if (floats < 0) floats = 0;
        if (floats % 3 != 0) {
            fail(CO_ERR_FORMAT, name + ": vertex array length is not a multiple of 3");
            return nullptr;
        }
        if (floats / 3 > std::numeric_limits<int32_t>::max()) {
            fail(CO_ERR_ARG, name + ": too many vertices");
            return nullptr;
        }
        const auto vertex_count = static_cast<int32_t>(floats / 3);
        const void* idata = nullptr;
        int64_t index_count = co_doc_mesh_array(d, CO_MESH_INDICES, &idata);
        if (index_count < 0) index_count = 0;
        if (index_count % 3 != 0) {
            fail(CO_ERR_FORMAT, name + ": index array length is not a multiple of 3");
            return nullptr;
        }
        const auto* idx = static_cast<const int32_t*>(idata);
        const int64_t triangles = index_count / 3;

        // Parts (part-local rows, part keys).
        const int32_t part_count = co_doc_parts_get(d, nullptr, 0);
        if (part_count < 0) {
            fail(part_count, name + ": " + co_last_error());
            return nullptr;
        }
        std::vector<co_part> parts(static_cast<size_t>(part_count));
        if (part_count > 0) co_doc_parts_get(d, parts.data(), part_count);

        // Vertex groups (CSR over parts, part-local rows).
        int32_t groups = 0, gparts = 0, grows = 0;
        if (int32_t rc = co_doc_vertex_group_counts(d, &groups, &gparts, &grows); rc != CO_OK) {
            fail(rc, name + ": " + co_last_error());
            return nullptr;
        }
        std::vector<const char*> names(static_cast<size_t>(groups));
        std::vector<int32_t> group_parts(static_cast<size_t>(groups) + 1, 0);
        std::vector<const char*> part_ids(static_cast<size_t>(gparts));
        std::vector<int32_t> part_rows(static_cast<size_t>(gparts) + 1, 0);
        std::vector<int32_t> local(static_cast<size_t>(grows));
        std::vector<float> weights(static_cast<size_t>(grows));
        if (groups > 0) {
            if (int32_t rc = co_doc_vertex_groups_get(d, names.data(), group_parts.data(), part_ids.data(),
                                                      part_rows.data(), local.data(), weights.data());
                rc != CO_OK) {
                fail(rc, name + ": " + co_last_error());
                return nullptr;
            }
        }
        Input in;
        for (const char* n : names) in.names.emplace_back(n != nullptr ? n : "");
        std::unordered_map<std::string, int32_t> part_by_id;
        for (int32_t i = part_count - 1; i >= 0; --i) {  // backwards: the first part wins on a repeated id
            if (el(parts, i).id != nullptr) part_by_id[el(parts, i).id] = i;
        }
        // part_rows are offsets into the one row array, so group g owns rows
        // [part_rows[group_parts[g]], part_rows[group_parts[g + 1]]).
        std::vector<int32_t> group_rows(static_cast<size_t>(groups) + 1, 0);
        std::vector<int32_t> soup(static_cast<size_t>(grows));
        for (int32_t g = 0; g < groups; ++g) {
            const std::string& gname = el(in.names, g);
            for (int32_t gp = el(group_parts, g); gp < el(group_parts, g + 1); ++gp) {
                const char* pid = el(part_ids, gp);
                const auto found = pid != nullptr ? part_by_id.find(pid) : part_by_id.end();
                if (found == part_by_id.end()) {
                    fail(CO_ERR_ARG, name + ": vertex group '" + gname + "' names part '" +
                                         (pid != nullptr ? pid : "(null)") + "', which the document does not have");
                    return nullptr;
                }
                const co_part& part = el(parts, found->second);
                if (part.vertex_start < 0 || part.vertex_count < 0 ||
                    int64_t{part.vertex_start} + part.vertex_count > vertex_count) {
                    fail(CO_ERR_RANGE, name + ": part '" + pid + "' vertex range lies outside the mesh");
                    return nullptr;
                }
                for (int32_t r = el(part_rows, gp); r < el(part_rows, gp + 1); ++r) {
                    const int32_t lv = el(local, r);
                    if (lv < 0 || lv >= part.vertex_count) {
                        fail(CO_ERR_RANGE, name + ": vertex group '" + gname + "' row " + std::to_string(r) +
                                               " names local vertex " + std::to_string(lv) + " outside part '" + pid +
                                               "' (" + std::to_string(part.vertex_count) + " vertices)");
                        return nullptr;
                    }
                    el(soup, r) = part.vertex_start + lv;
                }
            }
            el(group_rows, g + 1) = el(part_rows, el(group_parts, g + 1));
        }

        std::vector<int32_t> keys;
        if (key_mode == CO_MASK_KEY_CUSTOM) {
            if (tri_keys == nullptr || tri_key_count != triangles) {
                fail(CO_ERR_ARG, name + ": CO_MASK_KEY_CUSTOM needs one key per triangle (" +
                                     std::to_string(triangles) + "), got " + std::to_string(tri_key_count));
                return nullptr;
            }
            keys.assign(tri_keys, tri_keys + triangles);
        } else if (key_mode == CO_MASK_KEY_PART) {
            keys.assign(static_cast<size_t>(triangles), -1);
            for (int64_t t = 0; t < triangles; ++t) {
                for (int32_t i = 0; i < part_count; ++i) {
                    const int64_t s = el(parts, i).index_start, n = el(parts, i).index_count;
                    if (3 * t >= s && 3 * t < s + n) {
                        el(keys, t) = i;
                        break;
                    }
                }
            }
        }
        if (validate(fn, idx, index_count, vertex_count, groups, groups > 0 ? group_rows.data() : nullptr,
                     soup.data(), in) != CO_OK) {
            return nullptr;
        }
        in.keys = keys.empty() ? nullptr : keys.data();
        in.weights = weights.data();
        co_mask_plan* p = build(in);
        clear_error();
        return p;
    });
}

void co_mask_plan_destroy(co_mask_plan* p) { delete p; }

int32_t co_mask_plan_info(const co_mask_plan* p, co_mask_info* out) {
    if (int32_t rc = null_plan(p, "co_mask_plan_info")) return rc;
    if (out == nullptr) return fail(CO_ERR_ARG, "co_mask_plan_info: null out");
    *out = co_mask_info{};
    out->triangle_count = static_cast<int64_t>(p->permutation.size());
    out->vertex_count = p->vertex_count;
    out->group_count = p->group_count;
    out->segment_count = static_cast<int32_t>(p->seg_key.size());
    out->signature_count = static_cast<int32_t>(p->sig_offsets.size() - 1);
    out->class_count = static_cast<int32_t>(p->class_sig.size());
    clear_error();
    return CO_OK;
}

int64_t co_mask_indices(const co_mask_plan* p, const int32_t** out_data) {
    if (null_plan(p, "co_mask_indices") != CO_OK) return -1;
    if (out_data != nullptr) *out_data = p->indices.data();
    clear_error();
    return static_cast<int64_t>(p->indices.size());
}

int64_t co_mask_permutation(const co_mask_plan* p, const int32_t** out_data) {
    if (null_plan(p, "co_mask_permutation") != CO_OK) return -1;
    if (out_data != nullptr) *out_data = p->permutation.data();
    clear_error();
    return static_cast<int64_t>(p->permutation.size());
}

int32_t co_mask_segments(const co_mask_plan* p, co_mask_range* out, int32_t capacity) {
    if (int32_t rc = null_plan(p, "co_mask_segments")) return rc;
    if (capacity < 0 || (capacity > 0 && out == nullptr)) return fail(CO_ERR_ARG, "co_mask_segments: bad out");
    const auto total = static_cast<int32_t>(p->seg_key.size());
    for (int32_t s = 0; s < std::min(total, capacity); ++s) {
        out[s] = co_mask_range{3 * el(p->seg_first_tri, s), 3 * el(p->seg_tri_count, s), el(p->seg_key, s), s};
    }
    clear_error();
    return total;
}

int32_t co_mask_classes(const co_mask_plan* p, co_mask_class* out, int32_t capacity) {
    if (int32_t rc = null_plan(p, "co_mask_classes")) return rc;
    if (capacity < 0 || (capacity > 0 && out == nullptr)) return fail(CO_ERR_ARG, "co_mask_classes: bad out");
    const auto total = static_cast<int32_t>(p->class_sig.size());
    for (int32_t c = 0; c < std::min(total, capacity); ++c) {
        out[c] = co_mask_class{3 * el(p->class_first_tri, c), 3 * el(p->class_tri_count, c), el(p->class_segment, c),
                               el(p->class_sig, c)};
    }
    clear_error();
    return total;
}

int32_t co_mask_signature(const co_mask_plan* p, int32_t signature, const int32_t** out_groups) {
    if (int32_t rc = null_plan(p, "co_mask_signature")) return rc;
    const auto count = static_cast<int32_t>(p->sig_offsets.size() - 1);
    if (signature < 0 || signature >= count) {
        return fail(CO_ERR_RANGE, "co_mask_signature: signature " + std::to_string(signature) + " outside [0, " +
                                      std::to_string(count) + ")");
    }
    const int32_t b = el(p->sig_offsets, signature), e = el(p->sig_offsets, signature + 1);
    if (out_groups != nullptr) *out_groups = p->sig_groups.data() + b;
    clear_error();
    return e - b;
}

int32_t co_mask_group_find(const co_mask_plan* p, const char* name) {
    if (null_plan(p, "co_mask_group_find") != CO_OK) return -1;
    if (name == nullptr) {
        fail(CO_ERR_ARG, "co_mask_group_find: null name");
        return -1;
    }
    for (size_t g = 0; g < p->names.size(); ++g) {
        if (!p->names[g].empty() && p->names[g] == name) {
            clear_error();
            return static_cast<int32_t>(g);
        }
    }
    fail(CO_ERR_ARG, std::string("co_mask_group_find: no group named '") + name + "'");
    return -1;
}

const char* co_mask_group_name(const co_mask_plan* p, int32_t group) {
    if (null_plan(p, "co_mask_group_name") != CO_OK) return nullptr;
    if (group < 0 || group >= p->group_count) {
        fail(CO_ERR_RANGE, "co_mask_group_name: group " + std::to_string(group) + " outside [0, " +
                               std::to_string(p->group_count) + ")");
        return nullptr;
    }
    clear_error();
    return el(p->names, group).c_str();
}

int32_t co_mask_ranges(const co_mask_plan* p, const uint64_t* hide_words, int32_t hide_word_count,
                       const uint64_t* keep_words, int32_t keep_word_count, int32_t flags, co_mask_range* out,
                       int32_t capacity) {
    constexpr const char* fn = "co_mask_ranges";
    return guarded(fn, [&]() -> int32_t {
        if (int32_t rc = null_plan(p, fn)) return rc;
        if (capacity < 0 || (capacity > 0 && out == nullptr)) return fail(CO_ERR_ARG, "co_mask_ranges: bad out");
        if ((flags & ~CO_MASK_MERGE_SEGMENTS) != 0) {
            return fail(CO_ERR_ARG, "co_mask_ranges: unknown flags " + std::to_string(flags));
        }
        if (int32_t rc = check_bits(hide_words, hide_word_count, p->group_count, "hide")) return rc;
        if (int32_t rc = check_bits(keep_words, keep_word_count, p->group_count, "keep")) return rc;
        int64_t keep_total = 0;
        bool any_hide = false;
        for (int32_t w = 0; w < keep_word_count; ++w) keep_total += std::popcount(keep_words[w]);
        for (int32_t w = 0; w < hide_word_count; ++w) any_hide = any_hide || hide_words[w] != 0;

        // Visibility per signature: no hidden group in it, every kept group in it.
        const size_t sig_count = p->sig_offsets.size() - 1;
        thread_local std::vector<uint8_t> visible;
        visible.assign(sig_count, 1);
        if (any_hide || keep_total > 0) {
            for (size_t s = 0; s < sig_count; ++s) {
                const int32_t b = p->sig_offsets[s], e = p->sig_offsets[s + 1];
                if (e - b < keep_total) {
                    visible[s] = 0;
                    continue;
                }
                int64_t kept = 0;
                bool hidden = false;
                for (int32_t i = b; i < e && !hidden; ++i) {
                    const int32_t g = el(p->sig_groups, i);
                    hidden = bit(hide_words, hide_word_count, g);
                    kept += bit(keep_words, keep_word_count, g) ? 1 : 0;
                }
                visible[s] = !hidden && kept == keep_total ? 1 : 0;
            }
        }

        // Walk the classes in buffer order, merging visible neighbours.
        const bool merge_segments = (flags & CO_MASK_MERGE_SEGMENTS) != 0;
        int32_t total = 0;
        bool open = false;
        co_mask_range cur{};
        auto flush = [&]() {
            if (open) {
                if (total < capacity) out[total] = cur;
                ++total;
                open = false;
            }
        };
        const size_t classes = p->class_sig.size();
        for (size_t c = 0; c < classes; ++c) {
            const int32_t seg = p->class_segment[c];
            if (open && !merge_segments && cur.segment != seg) flush();
            if (el(visible, p->class_sig[c]) == 0) {
                flush();
                continue;
            }
            const int32_t first = 3 * p->class_first_tri[c], count = 3 * p->class_tri_count[c];
            if (open && cur.first + cur.count == first) {
                cur.count += count;
            } else {
                flush();
                cur = co_mask_range{first, count, el(p->seg_key, seg), seg};
                open = true;
            }
        }
        flush();
        clear_error();
        return total;
    });
}

}  // extern "C"
