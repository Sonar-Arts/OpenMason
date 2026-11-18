// Surface tracker (#144) and column records of the voxel world: two channels per (x, z) — SKY (first free
// cell above the topmost CV_BLOCK_SKY_OPAQUE block) and TOP (first free cell above the topmost non-air
// block). Contract in include/cenda/voxel_world.h; structures in cv_impl.hpp.
//
// STRUCTURE (per column, per channel)
//   leaf   = one cube: per (x, z) the topmost matching block y inside the cube, or none. Leaves survive the
//            cube's removal (the column remembers what was generated).
//   group  = 16 consecutive leaves (cy >> 4) + their summary (topmost over the 16), the 16-way level of
//            CubicChunks2's SurfaceTracker{Leaf,Branch}. Groups whose leaves are all none do not exist.
//   column = groups sorted top-down; the topmost non-none group summary answers for generated space.
//   All leaf/summary arrays are Packed: none / uniform / 4..64-bit offsets from a base, so open sky and
//   solid ground cost a few bytes and a surface cube ~0.5-1 KiB.
//
// ANSWER of a channel at (x, z) — the exact rule (also cached per column cell in Column::answer):
//   T_gen = topmost matching y over GENERATED cubes (tracker).
//   T_ng  = from the host callback value s for never-generated space (when the callback answered):
//           t = s - 1 (the generator's topmost block, clamped to max_y - 1), ct = t >> 5. Never-generated
//           cubes above ct are open; the never-generated cube ct (if not generated) holds t; a lower
//           never-generated cube g (the highest one below the generated run containing ct) is assumed full
//           under the generator's surface, so it contributes its top cell min(g*32+31, max_y-1).
//           s <= min_y means "nothing" and contributes none. An unknown callback contributes none.
//   answer = max(T_gen, T_ng) + 1, or min_y when both are none.
#include "cv_impl.hpp"
#include "cv_internal.hpp"

#include <algorithm>
#include <cstring>

namespace cenda::voxel {

namespace {
constexpr int64_t kCubeMin = INT64_MIN >> CV_CUBE_SHIFT;
constexpr int64_t kCubeMax = INT64_MAX >> CV_CUBE_SHIFT;
constexpr uint8_t kColumnVersion = 1;

uint64_t sentinel(uint32_t w) {
    return w == 64 ? UINT64_MAX : (uint64_t{1} << w) - 1;
}
} // namespace

// ---- Packed ------------------------------------------------------------------------------------------

uint32_t Packed::width(Kind k) {
    switch (k) {
        case kW4: return 4;
        case kW8: return 8;
        case kW16: return 16;
        case kW32: return 32;
        case kW64: return 64;
        default: return 0;
    }
}

uint64_t Packed::raw(uint32_t i) const {
    const uint8_t* d = data_.get();
    switch (kind_) {
        case kW4: return (static_cast<uint32_t>(d[i >> 1]) >> ((i & 1u) << 2)) & 15u;
        case kW8: return d[i];
        case kW16: {
            uint16_t v;
            std::memcpy(&v, d + 2 * i, 2);
            return v;
        }
        case kW32: {
            uint32_t v;
            std::memcpy(&v, d + 4 * i, 4);
            return v;
        }
        default: {
            uint64_t v;
            std::memcpy(&v, d + 8 * static_cast<size_t>(i), 8);
            return v;
        }
    }
}

void Packed::put(uint32_t i, uint64_t v) {
    uint8_t* d = data_.get();
    switch (kind_) {
        case kW4: {
            uint8_t& b = d[i >> 1];
            const uint32_t shift = (i & 1u) << 2;
            b = static_cast<uint8_t>((b & ~(15u << shift)) | (static_cast<uint32_t>(v) << shift));
            return;
        }
        case kW8: d[i] = static_cast<uint8_t>(v); return;
        case kW16: {
            const auto x = static_cast<uint16_t>(v);
            std::memcpy(d + 2 * i, &x, 2);
            return;
        }
        case kW32: {
            const auto x = static_cast<uint32_t>(v);
            std::memcpy(d + 4 * i, &x, 4);
            return;
        }
        default: std::memcpy(d + 8 * static_cast<size_t>(i), &v, 8); return;
    }
}

int64_t Packed::get(uint32_t i) const {
    if (kind_ == kNoneKind) return kNone;
    if (kind_ == kUniform) return base_;
    const uint64_t r = raw(i);
    if (r == sentinel(width(kind_))) return kNone;
    return static_cast<int64_t>(static_cast<uint64_t>(base_) + r);
}

namespace {
// Whole-array codecs, one tight loop per width (the per-element raw()/put() switch costs ~5x).
template <class T>
void decode_words(const uint8_t* d, uint64_t b, int64_t* out) {
    constexpr T s = static_cast<T>(~T{0});
    for (uint32_t i = 0; i < kColumnCells; ++i) {
        T r;
        std::memcpy(&r, d + sizeof(T) * i, sizeof(T));
        out[i] = r == s ? kNone : static_cast<int64_t>(b + r);
    }
}
template <class T>
void encode_words(uint8_t* d, const int64_t* v, uint64_t mn) {
    constexpr T s = static_cast<T>(~T{0});
    for (uint32_t i = 0; i < kColumnCells; ++i) {
        const T x = v[i] == kNone ? s : static_cast<T>(static_cast<uint64_t>(v[i]) - mn);
        std::memcpy(d + sizeof(T) * i, &x, sizeof(T));
    }
}
} // namespace

void Packed::decode(int64_t* out) const {
    if (kind_ == kNoneKind || kind_ == kUniform) {
        std::fill(out, out + kColumnCells, kind_ == kNoneKind ? kNone : base_);
        return;
    }
    const auto b = static_cast<uint64_t>(base_);
    const uint8_t* d = data_.get();
    switch (kind_) {
        case kW4:
            for (uint32_t i = 0; i < kColumnCells / 2; ++i) {
                const uint32_t lo = d[i] & 15u, hi = d[i] >> 4;
                out[2 * i] = lo == 15u ? kNone : static_cast<int64_t>(b + lo);
                out[2 * i + 1] = hi == 15u ? kNone : static_cast<int64_t>(b + hi);
            }
            return;
        case kW8: decode_words<uint8_t>(d, b, out); return;
        case kW16: decode_words<uint16_t>(d, b, out); return;
        case kW32: decode_words<uint32_t>(d, b, out); return;
        default: decode_words<uint64_t>(d, b, out); return;
    }
}

void Packed::assign(const int64_t* v) {
    bool any = false, any_none = false;
    int64_t mn = INT64_MAX, mx = INT64_MIN;
    for (uint32_t i = 0; i < kColumnCells; ++i) {
        if (v[i] == kNone) {
            any_none = true;
        } else {
            any = true;
            mn = std::min(mn, v[i]);
            mx = std::max(mx, v[i]);
        }
    }
    if (!any) {
        kind_ = kNoneKind;
        data_.reset();
        base_ = 0;
        return;
    }
    if (!any_none && mn == mx) {
        kind_ = kUniform;
        data_.reset();
        base_ = mn;
        return;
    }
    const uint64_t range = static_cast<uint64_t>(mx) - static_cast<uint64_t>(mn);
    Kind k = kW64;
    if (range < 15) k = kW4;
    else if (range < 255) k = kW8;
    else if (range < 65535) k = kW16;
    else if (range < 0xFFFFFFFFull) k = kW32;
    const uint32_t w = width(k);
    const size_t bytes = kColumnCells * w / 8;
    if (kind_ != k || !data_) data_ = std::make_unique<uint8_t[]>(bytes);
    kind_ = k;
    base_ = mn;
    const auto m = static_cast<uint64_t>(mn);
    uint8_t* d = data_.get();
    switch (k) {
        case kW4:
            for (uint32_t i = 0; i < kColumnCells / 2; ++i) {
                const uint32_t lo = v[2 * i] == kNone ? 15u : static_cast<uint32_t>(static_cast<uint64_t>(v[2 * i]) - m);
                const uint32_t hi =
                    v[2 * i + 1] == kNone ? 15u : static_cast<uint32_t>(static_cast<uint64_t>(v[2 * i + 1]) - m);
                d[i] = static_cast<uint8_t>(lo | (hi << 4));
            }
            break;
        case kW8: encode_words<uint8_t>(d, v, m); break;
        case kW16: encode_words<uint16_t>(d, v, m); break;
        case kW32: encode_words<uint32_t>(d, v, m); break;
        default: encode_words<uint64_t>(d, v, m); break;
    }
}

void Packed::set(uint32_t i, int64_t v) {
    if (kind_ == kNoneKind) {
        if (v == kNone) return;
    } else if (kind_ == kUniform) {
        if (v == base_) return;
    } else {
        const uint64_t s = sentinel(width(kind_));
        if (v == kNone) {
            put(i, s);
            return;
        }
        if (v >= base_ && static_cast<uint64_t>(v) - static_cast<uint64_t>(base_) < s) {
            put(i, static_cast<uint64_t>(v) - static_cast<uint64_t>(base_));
            return;
        }
    }
    int64_t tmp[kColumnCells];
    decode(tmp);
    tmp[i] = v;
    assign(tmp);
}

size_t Packed::heap_bytes() const {
    return data_ ? kColumnCells * width(kind_) / 8 : 0;
}

void Packed::copy_from(const Packed& o) {
    kind_ = o.kind_;
    base_ = o.base_;
    if (!o.data_) {
        data_.reset();
        return;
    }
    const size_t n = o.heap_bytes();
    data_ = std::make_unique_for_overwrite<uint8_t[]>(n);
    std::memcpy(data_.get(), o.data_.get(), n);
}

bool Packed::same(const Packed& o) const {
    if (kind_ != o.kind_) return false;
    if (kind_ == kNoneKind) return true;
    if (base_ != o.base_) return false;
    if (kind_ == kUniform) return true;
    return std::memcmp(data_.get(), o.data_.get(), heap_bytes()) == 0;
}

void Packed::serialize(std::vector<uint8_t>& out) const {
    out.push_back(kind_);
    if (kind_ == kNoneKind) return;
    const auto b = static_cast<uint64_t>(base_);
    for (int k = 0; k < 8; ++k) out.push_back(static_cast<uint8_t>(b >> (8 * k)));
    if (data_) out.insert(out.end(), data_.get(), data_.get() + heap_bytes());
}

bool Packed::deserialize(const uint8_t*& p, const uint8_t* end, Packed& out) {
    if (p >= end || *p > kW64) return false;
    const auto k = static_cast<Kind>(*p++);
    out.kind_ = k;
    out.data_.reset();
    out.base_ = 0;
    if (k == kNoneKind) return true;
    if (end - p < 8) return false;
    uint64_t b = 0;
    for (int i = 0; i < 8; ++i) b |= static_cast<uint64_t>(p[i]) << (8 * i);
    p += 8;
    out.base_ = static_cast<int64_t>(b);
    if (out.base_ == kNone) return false;
    if (k == kUniform) return true;
    const size_t bytes = kColumnCells * width(k) / 8;
    if (static_cast<size_t>(end - p) < bytes) return false;
    out.data_ = std::make_unique<uint8_t[]>(bytes);
    std::memcpy(out.data_.get(), p, bytes);
    p += bytes;
    const uint64_t s = sentinel(width(k));
    const uint64_t headroom = static_cast<uint64_t>(INT64_MAX) - static_cast<uint64_t>(out.base_);
    for (uint32_t i = 0; i < kColumnCells; ++i) {
        const uint64_t r = out.raw(i);
        if (r != s && r > headroom) return false;   // would overflow int64
    }
    return true;
}

// ---- tracker -----------------------------------------------------------------------------------------

size_t Group::heap_bytes() const {
    size_t n = sizeof(Group) + summary.heap_bytes();
    for (const Packed& l : leaf) n += l.heap_bytes();
    return n;
}

Group* Tracker::find(int64_t key) const {
    // groups are sorted by descending key
    size_t lo = 0, hi = groups.size();
    while (lo < hi) {
        const size_t mid = (lo + hi) / 2;
        if (groups[mid]->key > key) lo = mid + 1;
        else hi = mid;
    }
    return (lo < groups.size() && groups[lo]->key == key) ? groups[lo].get() : nullptr;
}

Group* Tracker::find_or_create(int64_t key) {
    size_t lo = 0, hi = groups.size();
    while (lo < hi) {
        const size_t mid = (lo + hi) / 2;
        if (groups[mid]->key > key) lo = mid + 1;
        else hi = mid;
    }
    if (lo < groups.size() && groups[lo]->key == key) return groups[lo].get();
    auto g = std::make_unique<Group>();
    g->key = key;
    Group* raw = g.get();
    groups.insert(groups.begin() + static_cast<ptrdiff_t>(lo), std::move(g));
    return raw;
}

void Tracker::drop_if_empty(int64_t key) {
    for (size_t i = 0; i < groups.size(); ++i) {
        if (groups[i]->key != key) continue;
        for (const Packed& l : groups[i]->leaf) {
            if (!l.none()) return;
        }
        groups.erase(groups.begin() + static_cast<ptrdiff_t>(i));
        return;
    }
}

int64_t Tracker::leaf_get(int64_t cy, uint32_t i) const {
    const Group* g = find(cy >> 4);
    return g == nullptr ? kNone : g->leaf[cy & 15].get(i);
}

void Tracker::leaf_set_value(int64_t cy, uint32_t i, int64_t v) {
    Group* g = find(cy >> 4);
    if (g == nullptr) {
        if (v == kNone) return;
        g = find_or_create(cy >> 4);
    }
    g->leaf[cy & 15].set(i, v);
    int64_t m = kNone;
    for (const Packed& l : g->leaf) m = std::max(m, l.get(i));
    g->summary.set(i, m);
    if (g->summary.none()) drop_if_empty(cy >> 4);
}

void Tracker::leaf_set(int64_t cy, const int64_t* v, Packed* pre) {
    bool all_none = true;
    if (pre != nullptr) {
        all_none = pre->none();
    } else {
        for (uint32_t i = 0; i < kColumnCells && all_none; ++i) all_none = v[i] == kNone;
    }
    Group* g = find(cy >> 4);
    if (g == nullptr) {
        if (all_none) return;
        g = find_or_create(cy >> 4);
    }
    Packed& slot = g->leaf[cy & 15];
    const bool was_none = slot.none();
    if (pre != nullptr) {
        slot = std::move(*pre);   // == assign(v): assign is canonical
    } else {
        slot.assign(v);
    }
    int64_t acc[kColumnCells];
    if (was_none && g->summary.none()) {
        // First leaf of the group: the summary IS the leaf (assign is canonical, so a copy is identical).
        if (all_none) {
            drop_if_empty(cy >> 4);
            return;
        }
        g->summary.copy_from(slot);
        return;
    }
    if (was_none) {
        // The summary is exactly the max over the leaves, and this leaf contributed nothing: fold v in
        // instead of re-reading all 16 leaves (the common case: a cube generated for the first time).
        g->summary.decode(acc);
        bool any = false;
        if (!all_none) {
            for (uint32_t i = 0; i < kColumnCells; ++i) acc[i] = std::max(acc[i], v[i]);
            any = true;
        } else {
            for (uint32_t i = 0; i < kColumnCells && !any; ++i) any = acc[i] != kNone;
        }
        if (!any) {
            drop_if_empty(cy >> 4);
            return;
        }
        g->summary.assign(acc);
        return;
    }
    int64_t tmp[kColumnCells];
    std::fill(acc, acc + kColumnCells, kNone);
    bool any = false;
    for (const Packed& l : g->leaf) {
        if (l.none()) continue;
        any = true;
        l.decode(tmp);
        for (uint32_t i = 0; i < kColumnCells; ++i) acc[i] = std::max(acc[i], tmp[i]);
    }
    if (!any) {
        drop_if_empty(cy >> 4);
        return;
    }
    g->summary.assign(acc);
}

int64_t Tracker::top_generated(uint32_t i) const {
    for (const auto& g : groups) {
        const int64_t v = g->summary.get(i);
        if (v != kNone) return v;
    }
    return kNone;
}

size_t Tracker::heap_bytes() const {
    size_t n = groups.capacity() * sizeof(void*);
    for (const auto& g : groups) n += g->heap_bytes();
    return n;
}

// ---- column ------------------------------------------------------------------------------------------

bool Column::is_generated(int64_t cy) const {
    auto it = std::upper_bound(generated.begin(), generated.end(), cy,
                               [](int64_t v, const Range& r) { return v < r.lo; });
    if (it == generated.begin()) return false;
    --it;
    return cy <= it->hi;
}

void Column::mark_generated(int64_t cy) {
    auto it = std::upper_bound(generated.begin(), generated.end(), cy,
                               [](int64_t v, const Range& r) { return v < r.lo; });
    // it: first range with lo > cy
    if (it != generated.begin()) {
        auto prev = it - 1;
        if (cy <= prev->hi) return;
        if (prev->hi + 1 == cy) {
            prev->hi = cy;
            if (it != generated.end() && it->lo == cy + 1) {
                prev->hi = it->hi;
                generated.erase(it);
            }
            return;
        }
    }
    if (it != generated.end() && it->lo == cy + 1) {
        it->lo = cy;
        return;
    }
    generated.insert(it, Range{cy, cy});
}

void Column::add_loaded(int64_t cy) {
    auto it = std::lower_bound(loaded.begin(), loaded.end(), cy);
    if (it == loaded.end() || *it != cy) loaded.insert(it, cy);
}

void Column::remove_loaded(int64_t cy) {
    auto it = std::lower_bound(loaded.begin(), loaded.end(), cy);
    if (it != loaded.end() && *it == cy) loaded.erase(it);
}

void Column::answer_bounds(int c) {
    int64_t v[kColumnCells];
    answer[c].decode(v);
    amin[c] = *std::min_element(v, v + kColumnCells);
    amax[c] = *std::max_element(v, v + kColumnCells);
}

void Column::set_answer(int c, uint32_t i, int64_t v) {
    const int64_t before = answer[c].get(i);
    if (before == v) return;
    answer[c].set(i, v);
    if ((before == amin[c] && v > before) || (before == amax[c] && v < before)) {
        answer_bounds(c);
        return;
    }
    amin[c] = std::min(amin[c], v);
    amax[c] = std::max(amax[c], v);
}

size_t Column::heap_bytes() const {
    size_t n = sizeof(Column) + loaded.capacity() * sizeof(int64_t) + generated.capacity() * sizeof(Range);
    for (const Tracker& t : ch) n += t.heap_bytes();
    for (const Packed& a : answer) n += a.heap_bytes();
    return n;
}

// ---- answers -----------------------------------------------------------------------------------------

namespace {

// Never-generated contribution for a raw callback value s (see the file comment).
int64_t never_generated_from(const cv_world* w, const std::vector<Range>* ranges, int64_t s) {
    if (s == kNone || s <= w->min_y) return kNone;
    int64_t t = s - 1;
    if (t > w->max_y - 1) t = w->max_y - 1;
    const int64_t ct = t >> CV_CUBE_SHIFT;
    int64_t g = ct;
    if (ranges != nullptr && !ranges->empty()) {
        const Range* hit = nullptr;
        if (ranges->size() == 1) {
            if (ct >= (*ranges)[0].lo && ct <= (*ranges)[0].hi) hit = &(*ranges)[0];
        } else {
            auto it = std::upper_bound(ranges->begin(), ranges->end(), ct,
                                       [](int64_t v, const Range& r) { return v < r.lo; });
            if (it != ranges->begin() && ct <= (it - 1)->hi) hit = &*(it - 1);
        }
        if (hit != nullptr) {
            if (hit->lo == kCubeMin) return kNone;
            g = hit->lo - 1;
        }
    }
    if (g < (w->min_y >> CV_CUBE_SHIFT)) return kNone;
    if (g == ct) return t;
    return std::min(g * CV_CUBE_EDGE + (CV_CUBE_EDGE - 1), w->max_y - 1);
}

int64_t never_generated_top(const cv_world* w, const Column* col, const SurfaceCache* cb, int ch, uint32_t i) {
    if (cb == nullptr || !cb->known) return kNone;
    return never_generated_from(w, col != nullptr ? &col->generated : nullptr, cb->v[ch].get(i));
}

int64_t finish(const cv_world* w, int64_t gen, int64_t ng) {
    const int64_t t = std::max(gen, ng);
    return t == kNone ? w->min_y : t + 1;
}

} // namespace

int64_t column_value(const cv_world* w, const Column* col, const SurfaceCache* cb, int ch, uint32_t i) {
    const int64_t gen = col != nullptr ? col->ch[ch].top_generated(i) : kNone;
    return finish(w, gen, never_generated_top(w, col, cb, ch, i));
}

void column_values(const cv_world* w, const Column* col, const SurfaceCache* cb, int ch, int64_t* out,
                   int64_t* out_min, int64_t* out_max) {
    // T_gen: the topmost non-none group summary per cell (groups are sorted top-down).
    bool have = false;
    if (col != nullptr) {
        int64_t tmp[kColumnCells];
        bool open = false;
        for (const auto& g : col->ch[ch].groups) {
            if (g->summary.none()) continue;
            if (!have) {
                g->summary.decode(out);
                have = true;
            } else {
                g->summary.decode(tmp);
                open = false;
                for (uint32_t i = 0; i < kColumnCells; ++i) {
                    const int64_t o = out[i] == kNone ? tmp[i] : out[i];
                    out[i] = o;
                    open = open || o == kNone;
                }
                if (!open) break;
                continue;
            }
            open = false;
            for (uint32_t i = 0; i < kColumnCells && !open; ++i) open = out[i] == kNone;
            if (!open) break;
        }
    }
    if (!have) std::fill(out, out + kColumnCells, kNone);
    const int64_t min_y = w->min_y;
    int64_t mn = INT64_MAX, mx = INT64_MIN;
    if (cb == nullptr || !cb->known) {
        for (uint32_t i = 0; i < kColumnCells; ++i) {
            const int64_t v = out[i] == kNone ? min_y : out[i] + 1;   // finish(w, out[i], kNone)
            out[i] = v;
            mn = std::min(mn, v);
            mx = std::max(mx, v);
        }
    } else {
        int64_t raw[kColumnCells];
        cb->v[ch].decode(raw);
        const std::vector<Range>* ranges = col != nullptr ? &col->generated : nullptr;
        // never_generated_from(s), split: the generated-range lookup depends only on the cube ct of the
        // generator's top block, which neighbouring cells share; the rest is arithmetic on s.
        const int64_t max_top = w->max_y - 1;
        int64_t last_ct = 0, last_cell = kNone;   // last_cell: what ct contributes (kNone or the full cube top)
        bool have_ct = false;
        for (uint32_t i = 0; i < kColumnCells; ++i) {
            const int64_t sv = raw[i];
            int64_t ng = kNone;
            if (sv != kNone && sv > min_y) {
                const int64_t t = std::min(sv - 1, max_top);
                const int64_t ct = t >> CV_CUBE_SHIFT;
                if (!have_ct || ct != last_ct) {
                    // never_generated_from(w, ranges, t + 1) for a value whose cube is ct, with "ct itself"
                    // reported as INT64_MAX (resolved to t below).
                    const int64_t r = never_generated_from(w, ranges, std::min(ct * CV_CUBE_EDGE + (CV_CUBE_EDGE - 1), max_top) + 1);
                    last_ct = ct;
                    have_ct = true;
                    last_cell = (r != kNone && (r >> CV_CUBE_SHIFT) == ct) ? INT64_MAX : r;
                }
                ng = last_cell == INT64_MAX ? t : last_cell;
            }
            const int64_t t = std::max(out[i], ng);
            const int64_t v = t == kNone ? min_y : t + 1;   // finish(w, out[i], last_ng)
            out[i] = v;
            mn = std::min(mn, v);
            mx = std::max(mx, v);
        }
    }
    if (out_min != nullptr) *out_min = mn;
    if (out_max != nullptr) *out_max = mx;
}

void column_refresh_answers(const cv_world* w, Column* col) {
    int64_t v[kColumnCells];
    for (int ch = 0; ch < kChannels; ++ch) {
        column_values(w, col, col->cb.get(), ch, v, &col->amin[ch], &col->amax[ch]);
        col->answer[ch].assign(v);
    }
}

std::shared_ptr<const SurfaceCache> make_surface_cache(const cv_world* w, bool known, const int64_t* sky,
                                                       const int64_t* top) {
    auto c = std::make_shared<SurfaceCache>();
    c->known = known;
    if (known) {
        c->v[kSky].assign(sky);
        c->v[kTop].assign(top);
    }
    int64_t v[kColumnCells];
    for (int ch = 0; ch < kChannels; ++ch) {
        column_values(w, nullptr, c.get(), ch, v, &c->amin[ch], &c->amax[ch]);
        c->answer[ch].assign(v);
    }
    return c;
}

void column_apply_cube(const cv_world* w, Column* col, int64_t cy, Derived& d, bool add_loaded,
                       SurfaceDelta& delta) {
    int64_t old_sky[kColumnCells];
    int64_t v[kColumnCells];
    if (col->is_generated(cy)) {
        // Re-install of a generated cube: nothing changes unless a leaf does.
        bool same = true;
        for (int ch = 0; ch < kChannels && same; ++ch) {
            const Group* g = col->ch[ch].find(cy >> 4);
            if (g == nullptr) {
                if (d.packed) {
                    same = d.leaf_packed[ch].none();
                } else {
                    for (uint32_t i = 0; i < kColumnCells && same; ++i) same = d.leaf[ch][i] == kNone;
                }
            } else if (d.packed && g->leaf[cy & 15].same(d.leaf_packed[ch])) {
                same = true;
            } else {
                g->leaf[cy & 15].decode(v);
                same = std::equal(v, v + kColumnCells, d.leaf[ch]);
            }
        }
        if (same) {
            if (add_loaded) col->add_loaded(cy);
            return;
        }
    }
    col->answer[kSky].decode(old_sky);
    col->mark_generated(cy);
    if (add_loaded) col->add_loaded(cy);
    for (int ch = 0; ch < kChannels; ++ch) col->ch[ch].leaf_set(cy, d.leaf[ch], d.packed ? &d.leaf_packed[ch] : nullptr);
    d.packed = false;   // moved out
    for (int ch = 0; ch < kChannels; ++ch) {
        column_values(w, col, col->cb.get(), ch, v, &col->amin[ch], &col->amax[ch]);
        if (ch == kSky) {
            for (uint32_t i = 0; i < kColumnCells; ++i) {
                delta.add(static_cast<int>(i & 31u), static_cast<int>(i >> 5), old_sky[i], v[i]);
            }
        }
        col->answer[ch].assign(v);
    }
}

// ---- column locking --------------------------------------------------------------------------------------

namespace {
template <bool Exclusive>
Column* acquire_column(const cv_world* cw, int64_t cx, int64_t cz) {
    auto* w = const_cast<cv_world*>(cw);
    const uint64_t h = hash3(cx, 0, cz);
    auto& s = w->columns.shard(h);
    s.lock.lock_shared();
    Column* c = ShardedMap<Column>::find(s, cx, 0, cz, h);
    if (c != nullptr) {
        if constexpr (Exclusive) {
            c->lock.lock();
        } else {
            c->lock.lock_shared();
        }
    }
    s.lock.unlock_shared();
    return c;
}

// Marks every loaded cube of the 3x3 columns around (cx, cz) dirty (a column's answers changed wholesale).
void mark_columns(cv_world* w, int64_t cx, int64_t cz) {
    std::vector<Int3> keys;
    for (int64_t dz = -1; dz <= 1; ++dz) {
        for (int64_t dx = -1; dx <= 1; ++dx) {
            const int64_t x = cx + dx, z = cz + dz;
            if (x < kCubeMin || x > kCubeMax || z < kCubeMin || z > kCubeMax) continue;
            Column* col = acquire_column<false>(w, x, z);
            if (col == nullptr) continue;
            for (int64_t cy : col->loaded) keys.push_back(Int3{x, cy, z});
            col->lock.unlock_shared();
        }
    }
    mark_present(w, keys);
}
} // namespace

Column* column_shared(const cv_world* w, int64_t cx, int64_t cz) {
    return acquire_column<false>(w, cx, cz);
}

Column* column_exclusive(cv_world* w, int64_t cx, int64_t cz) {
    return acquire_column<true>(w, cx, cz);
}

Column* column_create_exclusive(cv_world* w, int64_t cx, int64_t cz, std::shared_ptr<const SurfaceCache> cb) {
    const uint64_t h = hash3(cx, 0, cz);
    auto& s = w->columns.shard(h);
    s.lock.lock();
    Column* col = ShardedMap<Column>::find(s, cx, 0, cz, h);
    if (col == nullptr) {
        col = new Column();
        {
            std::lock_guard<std::mutex> g(w->ephemeral_mutex);
            auto it = w->ephemeral.find(Int3{cx, 0, cz});
            if (it != w->ephemeral.end()) {
                col->cb = it->second;
                w->ephemeral.erase(it);
            }
        }
        if (!col->cb) col->cb = cb ? cb : w->unknown_cache;
        // A fresh column (no tracker, nothing generated) answers exactly what its callback cache answers
        // (make_surface_cache ran the same column_values with no column): copy instead of recomputing.
        for (int ch = 0; ch < kChannels; ++ch) {
            col->answer[ch].copy_from(col->cb->answer[ch]);
            col->amin[ch] = col->cb->amin[ch];
            col->amax[ch] = col->cb->amax[ch];
        }
        w->columns.insert(s, cx, 0, cz, h, col);
    }
    col->lock.lock();   // column holders never wait on a column-map shard
    s.lock.unlock();
    if (!col->cb) {
        col->cb = cb ? cb : w->unknown_cache;
        column_refresh_answers(w, col);
    }
    return col;
}

std::shared_ptr<const SurfaceCache> ensure_surface_cache(cv_world* w, int64_t cx, int64_t cz) {
    if (w->surface == nullptr) return w->unknown_cache;
    if (Column* col = column_shared(w, cx, cz)) {
        std::shared_ptr<const SurfaceCache> cb = col->cb;
        col->lock.unlock_shared();
        if (cb) return cb;
    }
    {
        std::lock_guard<std::mutex> g(w->ephemeral_mutex);
        auto it = w->ephemeral.find(Int3{cx, 0, cz});
        if (it != w->ephemeral.end()) return it->second;
    }
    // No lock held: the host may take as long as it likes.
    std::vector<int64_t> sky(kColumnCells, kNone), top(kColumnCells, kNone);
    const int32_t r = w->surface(w->user, cx, cz, sky.data(), top.data());
    std::shared_ptr<const SurfaceCache> cache =
        r == 1 ? make_surface_cache(w, true, sky.data(), top.data()) : w->unknown_cache;
    bool remark = false;
    if (Column* col = column_exclusive(w, cx, cz)) {
        if (!col->cb) {
            col->cb = cache;
            column_refresh_answers(w, col);
            remark = !col->loaded.empty();
        } else {
            cache = col->cb;
        }
        col->lock.unlock();
        if (remark) mark_columns(w, cx, cz);
        return cache;
    }
    std::lock_guard<std::mutex> g(w->ephemeral_mutex);
    if (w->ephemeral.size() >= kEphemeralCap) w->ephemeral.clear();
    auto [it, inserted] = w->ephemeral.emplace(Int3{cx, 0, cz}, cache);
    return it->second;
}

bool column_answer_at(const cv_world* cw, int64_t cx, int64_t cz, int ch, uint32_t i, int64_t* v) {
    auto* w = const_cast<cv_world*>(cw);
    if (Column* col = column_shared(w, cx, cz)) {
        *v = col->answer[ch].get(i);
        col->lock.unlock_shared();
        return true;
    }
    std::shared_ptr<const SurfaceCache> cb = ensure_surface_cache(w, cx, cz);
    if (Column* col = column_shared(w, cx, cz)) {   // created meanwhile
        *v = col->answer[ch].get(i);
        col->lock.unlock_shared();
        return true;
    }
    *v = cb->known ? cb->answer[ch].get(i) : w->min_y;
    return cb->known;
}

bool column_answers(const cv_world* cw, int64_t cx, int64_t cz, int ch, int64_t* out) {
    auto* w = const_cast<cv_world*>(cw);
    if (Column* col = column_shared(w, cx, cz)) {
        col->answer[ch].decode(out);
        col->lock.unlock_shared();
        return true;
    }
    std::shared_ptr<const SurfaceCache> cb = ensure_surface_cache(w, cx, cz);
    if (Column* col = column_shared(w, cx, cz)) {
        col->answer[ch].decode(out);
        col->lock.unlock_shared();
        return true;
    }
    if (cb->known) {
        cb->answer[ch].decode(out);
    } else {
        std::fill(out, out + kColumnCells, w->min_y);
    }
    return cb->known;
}

// ---- dirtiness from surface changes -----------------------------------------------------------------------

void SurfaceDelta::add(int lx, int lz, int64_t a, int64_t b) {
    if (a == b) return;
    const int64_t low = std::min(a, b), high = std::max(a, b);
    // A cube's sky input is its cells' and corners' heights cy*32-1 .. cy*32+32 compared with the surface.
    const int64_t cy_lo = low == INT64_MIN ? kCubeMin : ((low - 1) >> CV_CUBE_SHIFT);
    const int64_t cy_hi = high >> CV_CUBE_SHIFT;
    for (int dz = -1; dz <= 1; ++dz) {
        if ((dz == -1 && lz != 0) || (dz == 1 && lz != 31)) continue;
        for (int dx = -1; dx <= 1; ++dx) {
            if ((dx == -1 && lx != 0) || (dx == 1 && lx != 31)) continue;
            const int k = (dz + 1) * 3 + (dx + 1);
            lo[k] = std::min(lo[k], cy_lo);
            hi[k] = std::max(hi[k], cy_hi);
        }
    }
}

bool SurfaceDelta::empty() const {
    for (int k = 0; k < 9; ++k) {
        if (lo[k] <= hi[k]) return false;
    }
    return true;
}

void apply_surface_delta(cv_world* w, int64_t cx, int64_t cz, const SurfaceDelta& d) {
    if (d.empty()) return;
    std::vector<Int3> keys;
    for (int dz = -1; dz <= 1; ++dz) {
        for (int dx = -1; dx <= 1; ++dx) {
            const int k = (dz + 1) * 3 + (dx + 1);
            if (d.lo[k] > d.hi[k]) continue;
            const int64_t x = cx + dx, z = cz + dz;
            if (x < kCubeMin || x > kCubeMax || z < kCubeMin || z > kCubeMax) continue;
            Column* col = column_shared(w, x, z);
            if (col == nullptr) continue;
            auto it = std::lower_bound(col->loaded.begin(), col->loaded.end(), d.lo[k]);
            for (; it != col->loaded.end() && *it <= d.hi[k]; ++it) keys.push_back(Int3{x, *it, z});
            col->lock.unlock_shared();
        }
    }
    mark_present(w, keys);
}

} // namespace cenda::voxel

using namespace cenda::voxel;
using cenda::Int3;
using cenda::hash3;

namespace {

bool cube_coord_ok(int64_t c) {
    return c >= (INT64_MIN >> CV_CUBE_SHIFT) && c <= (INT64_MAX >> CV_CUBE_SHIFT);
}

void put_u32(std::vector<uint8_t>& o, uint32_t v) {
    for (int i = 0; i < 4; ++i) o.push_back(static_cast<uint8_t>(v >> (8 * i)));
}
void put_u16(std::vector<uint8_t>& o, uint16_t v) {
    o.push_back(static_cast<uint8_t>(v));
    o.push_back(static_cast<uint8_t>(v >> 8));
}
void put_i64(std::vector<uint8_t>& o, int64_t v) {
    const auto u = static_cast<uint64_t>(v);
    for (int i = 0; i < 8; ++i) o.push_back(static_cast<uint8_t>(u >> (8 * i)));
}

bool get_u32(const uint8_t*& p, const uint8_t* end, uint32_t& v) {
    if (end - p < 4) return false;
    v = 0;
    for (int i = 0; i < 4; ++i) v |= static_cast<uint32_t>(p[i]) << (8 * i);
    p += 4;
    return true;
}
bool get_u16(const uint8_t*& p, const uint8_t* end, uint16_t& v) {
    if (end - p < 2) return false;
    v = static_cast<uint16_t>(p[0] | (p[1] << 8));
    p += 2;
    return true;
}
bool get_i64(const uint8_t*& p, const uint8_t* end, int64_t& v) {
    if (end - p < 8) return false;
    uint64_t u = 0;
    for (int i = 0; i < 8; ++i) u |= static_cast<uint64_t>(p[i]) << (8 * i);
    p += 8;
    v = static_cast<int64_t>(u);
    return true;
}

// Parses a column record (validating everything) into ranges + trackers.
bool parse_column(const uint8_t* p, const uint8_t* end, std::vector<Range>& ranges, Tracker* trackers) {
    if (p >= end || *p++ != kColumnVersion) return false;
    uint32_t n = 0;
    if (!get_u32(p, end, n) || n > static_cast<uint64_t>(end - p) / 16) return false;
    ranges.reserve(n);
    for (uint32_t k = 0; k < n; ++k) {
        int64_t lo = 0, hi = 0;
        if (!get_i64(p, end, lo) || !get_i64(p, end, hi)) return false;
        if (!cube_coord_ok(lo) || !cube_coord_ok(hi) || lo > hi) return false;
        if (!ranges.empty() && (ranges.back().hi == (INT64_MAX >> CV_CUBE_SHIFT) || lo <= ranges.back().hi + 1)) {
            return false;
        }
        ranges.push_back(Range{lo, hi});
    }
    Column probe;   // for is_generated
    probe.generated = ranges;
    for (int ch = 0; ch < kChannels; ++ch) {
        uint32_t groups = 0;
        if (!get_u32(p, end, groups) || groups > static_cast<uint64_t>(end - p) / 10) return false;
        bool first = true;
        int64_t prev = 0;
        for (uint32_t k = 0; k < groups; ++k) {
            int64_t key = 0;
            uint16_t mask = 0;
            if (!get_i64(p, end, key) || !get_u16(p, end, mask) || mask == 0) return false;
            if (key < ((INT64_MIN >> CV_CUBE_SHIFT) >> 4) || key > ((INT64_MAX >> CV_CUBE_SHIFT) >> 4)) return false;
            if (!first && key <= prev) return false;
            first = false;
            prev = key;
            auto g = std::make_unique<Group>();
            g->key = key;
            int64_t v[kColumnCells];
            int64_t acc[kColumnCells];
            std::fill(acc, acc + kColumnCells, kNone);
            for (int l = 0; l < 16; ++l) {
                if ((mask & (1u << l)) == 0) continue;
                const int64_t cy = key * 16 + l;
                if (!probe.is_generated(cy)) return false;
                if (!Packed::deserialize(p, end, g->leaf[l]) || g->leaf[l].none()) return false;
                g->leaf[l].decode(v);
                for (uint32_t i = 0; i < kColumnCells; ++i) {
                    if (v[i] == kNone) continue;
                    if (v[i] < cy * CV_CUBE_EDGE || v[i] > cy * CV_CUBE_EDGE + (CV_CUBE_EDGE - 1)) return false;
                    acc[i] = std::max(acc[i], v[i]);
                }
            }
            g->summary.assign(acc);
            trackers[ch].groups.insert(trackers[ch].groups.begin(), std::move(g));   // keep descending
        }
    }
    return p == end;
}

} // namespace

// cv_internal.hpp: cv_top_range without ever calling the host surface callback.
int32_t cenda::voxel::top_range_peek(cv_world* w, int64_t cx, int64_t cz, int64_t* out_top_min, int64_t* out_top_max,
                       int64_t* out_sky_min) {
    if (w == nullptr || !cube_coord_ok(cx) || !cube_coord_ok(cz)) return CV_ERR_ARG;
    int64_t tmin = w->min_y, tmax = w->min_y, smin = w->min_y;
    int32_t r = -1;
    if (Column* col = column_shared(w, cx, cz)) {
        tmin = col->amin[kTop];
        tmax = col->amax[kTop];
        smin = col->amin[kSky];
        col->lock.unlock_shared();
        r = 1;
    } else if (w->surface == nullptr) {
        r = 0;
    } else {
        std::shared_ptr<const SurfaceCache> cb;
        {
            std::lock_guard<std::mutex> g(w->ephemeral_mutex);
            const auto it = w->ephemeral.find(Int3{cx, 0, cz});
            if (it != w->ephemeral.end()) cb = it->second;
        }
        if (cb) {
            r = cb->known ? 1 : 0;
            if (cb->known) {
                tmin = cb->amin[kTop];
                tmax = cb->amax[kTop];
                smin = cb->amin[kSky];
            }
        }
    }
    if (out_top_min != nullptr) *out_top_min = tmin;
    if (out_top_max != nullptr) *out_top_max = tmax;
    if (out_sky_min != nullptr) *out_sky_min = smin;
    return r;
}

extern "C" {

int32_t cv_surface_y(cv_world* w, int64_t x, int64_t z, int64_t* out_y) {
    if (w == nullptr || out_y == nullptr) return fail(CV_ERR_ARG, "bad arguments");
    const auto i = static_cast<uint32_t>((z & 31) * 32 + (x & 31));
    const bool known = column_answer_at(w, x >> CV_CUBE_SHIFT, z >> CV_CUBE_SHIFT, kSky, i, out_y);
    return known ? CV_OK : fail(CV_ERR_MISSING, "nothing known about the column");
}

int32_t cv_top_y(cv_world* w, int64_t x, int64_t z, int64_t* out_y) {
    if (w == nullptr || out_y == nullptr) return fail(CV_ERR_ARG, "bad arguments");
    const auto i = static_cast<uint32_t>((z & 31) * 32 + (x & 31));
    const bool known = column_answer_at(w, x >> CV_CUBE_SHIFT, z >> CV_CUBE_SHIFT, kTop, i, out_y);
    return known ? CV_OK : fail(CV_ERR_MISSING, "nothing known about the column");
}

int32_t cv_surface_column(cv_world* w, int64_t cx, int64_t cz, int64_t* out) {
    if (w == nullptr || out == nullptr || !cube_coord_ok(cx) || !cube_coord_ok(cz)) return fail(CV_ERR_ARG, "bad arguments");
    return column_answers(w, cx, cz, kSky, out) ? CV_OK : fail(CV_ERR_MISSING, "nothing known about the column");
}

int32_t cv_top_range(cv_world* w, int64_t cx, int64_t cz, int64_t* out_top_min, int64_t* out_top_max,
                     int64_t* out_sky_min) {
    if (w == nullptr || !cube_coord_ok(cx) || !cube_coord_ok(cz)) return fail(CV_ERR_ARG, "bad arguments");
    int64_t tmin = w->min_y, tmax = w->min_y, smin = w->min_y;
    bool known = false;
    if (Column* col = column_shared(w, cx, cz)) {
        tmin = col->amin[kTop];
        tmax = col->amax[kTop];
        smin = col->amin[kSky];
        col->lock.unlock_shared();
        known = true;
    } else {
        std::shared_ptr<const SurfaceCache> cb = ensure_surface_cache(w, cx, cz);
        if (Column* c2 = column_shared(w, cx, cz)) {
            tmin = c2->amin[kTop];
            tmax = c2->amax[kTop];
            smin = c2->amin[kSky];
            c2->lock.unlock_shared();
            known = true;
        } else if (cb->known) {
            tmin = cb->amin[kTop];
            tmax = cb->amax[kTop];
            smin = cb->amin[kSky];
            known = true;
        }
    }
    if (out_top_min != nullptr) *out_top_min = tmin;
    if (out_top_max != nullptr) *out_top_max = tmax;
    if (out_sky_min != nullptr) *out_sky_min = smin;
    return known ? CV_OK : fail(CV_ERR_MISSING, "nothing known about the column");
}

int64_t cv_column_encode(const cv_world* cw, int64_t cx, int64_t cz, uint8_t* dst, int64_t cap) {
    if (cw == nullptr || cap < 0 || (cap > 0 && dst == nullptr) || !cube_coord_ok(cx) || !cube_coord_ok(cz)) {
        return fail(CV_ERR_ARG, "bad arguments");
    }
    Column* col = column_shared(cw, cx, cz);
    if (col == nullptr) return fail(CV_ERR_MISSING, "no such column");
    thread_local std::vector<uint8_t> out;
    out.clear();
    out.push_back(kColumnVersion);
    put_u32(out, static_cast<uint32_t>(col->generated.size()));
    for (const Range& r : col->generated) {
        put_i64(out, r.lo);
        put_i64(out, r.hi);
    }
    for (const Tracker& t : col->ch) {
        uint32_t groups = 0;
        for (const auto& g : t.groups) {
            for (const Packed& l : g->leaf) {
                if (!l.none()) {
                    ++groups;
                    break;
                }
            }
        }
        put_u32(out, groups);
        for (auto it = t.groups.rbegin(); it != t.groups.rend(); ++it) {   // ascending keys
            const Group& g = **it;
            uint16_t mask = 0;
            for (int l = 0; l < 16; ++l) {
                if (!g.leaf[l].none()) mask = static_cast<uint16_t>(mask | (1u << l));
            }
            if (mask == 0) continue;
            put_i64(out, g.key);
            put_u16(out, mask);
            for (int l = 0; l < 16; ++l) {
                if (!g.leaf[l].none()) g.leaf[l].serialize(out);
            }
        }
    }
    col->lock.unlock_shared();
    const auto n = static_cast<int64_t>(out.size());
    if (n > cap) return CV_NEED(n);
    std::memcpy(dst, out.data(), out.size());
    return n;
}

int32_t cv_column_decode(cv_world* w, int64_t cx, int64_t cz, const uint8_t* src, int64_t len) {
    if (w == nullptr || len < 0 || (len > 0 && src == nullptr) || !cube_coord_ok(cx) || !cube_coord_ok(cz)) {
        return fail(CV_ERR_ARG, "bad arguments");
    }
    std::vector<Range> ranges;
    Tracker trackers[kChannels];
    if (len == 0 || !parse_column(src, src + len, ranges, trackers)) return fail(CV_ERR_CORRUPT, "corrupt column record");
    std::shared_ptr<const SurfaceCache> cb = ensure_surface_cache(w, cx, cz);
    Column* col = column_create_exclusive(w, cx, cz, cb);
    int64_t old_sky[kColumnCells];
    col->answer[kSky].decode(old_sky);
    col->generated = std::move(ranges);
    for (int ch = 0; ch < kChannels; ++ch) col->ch[ch].groups = std::move(trackers[ch].groups);
    for (int64_t cy : col->loaded) col->mark_generated(cy);
    column_refresh_answers(w, col);
    SurfaceDelta delta;
    for (uint32_t i = 0; i < kColumnCells; ++i) {
        delta.add(static_cast<int>(i & 31u), static_cast<int>(i >> 5), old_sky[i], col->answer[kSky].get(i));
    }
    const std::vector<int64_t> loaded = col->loaded;
    col->lock.unlock();
    // Loaded content is authoritative over the record.
    for (int64_t cy : loaded) reapply_cube(w, cx, cy, cz);
    apply_surface_delta(w, cx, cz, delta);
    return CV_OK;
}

int32_t cv_column_remove(cv_world* w, int64_t cx, int64_t cz) {
    if (w == nullptr || !cube_coord_ok(cx) || !cube_coord_ok(cz)) return fail(CV_ERR_ARG, "bad arguments");
    {
        std::lock_guard<std::mutex> g(w->ephemeral_mutex);
        w->ephemeral.erase(Int3{cx, 0, cz});
    }
    const uint64_t h = hash3(cx, 0, cz);
    auto& s = w->columns.shard(h);
    s.lock.lock();
    Column* col = ShardedMap<Column>::find(s, cx, 0, cz, h);
    if (col == nullptr) {
        s.lock.unlock();
        return fail(CV_ERR_MISSING, "no such column");
    }
    col->lock.lock();
    if (!col->loaded.empty()) {
        col->lock.unlock();
        s.lock.unlock();
        return fail(CV_ERR_EXISTS, "the column still has cubes");
    }
    w->columns.erase(s, cx, 0, cz, h);
    s.lock.unlock();
    col->lock.unlock();
    delete col;
    return CV_OK;
}

} // extern "C"
