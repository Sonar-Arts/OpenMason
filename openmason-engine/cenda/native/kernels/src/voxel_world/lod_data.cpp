// Octree LOD node data (OpenMason #122): classification of level 0, the downsampling rule, digests, node
// records and the cube-record / column-record readers (voxel_world.h "LOD").
#include "lod_internal.hpp"

#include <algorithm>
#include <cstring>

namespace cenda::voxel::lod {

namespace {

inline void put8(std::vector<uint8_t>& o, uint8_t v) { o.push_back(v); }
inline void put16(std::vector<uint8_t>& o, uint16_t v) {
    o.push_back(static_cast<uint8_t>(v));
    o.push_back(static_cast<uint8_t>(v >> 8));
}
inline void put32(std::vector<uint8_t>& o, uint32_t v) {
    for (int i = 0; i < 4; ++i) o.push_back(static_cast<uint8_t>(v >> (8 * i)));
}
inline void put64(std::vector<uint8_t>& o, uint64_t v) {
    for (int i = 0; i < 8; ++i) o.push_back(static_cast<uint8_t>(v >> (8 * i)));
}

struct Reader {
    const uint8_t* p;
    const uint8_t* end;
    bool ok = true;
    bool need(size_t n) {
        if (!ok || static_cast<size_t>(end - p) < n) {
            ok = false;
            return false;
        }
        return true;
    }
    uint8_t u8() {
        if (!need(1)) return 0;
        return *p++;
    }
    uint16_t u16() {
        if (!need(2)) return 0;
        const uint16_t v = static_cast<uint16_t>(p[0] | (p[1] << 8));
        p += 2;
        return v;
    }
    uint32_t u32() {
        if (!need(4)) return 0;
        uint32_t v = 0;
        for (int i = 0; i < 4; ++i) v |= static_cast<uint32_t>(p[i]) << (8 * i);
        p += 4;
        return v;
    }
    uint64_t u64() {
        if (!need(8)) return 0;
        uint64_t v = 0;
        for (int i = 0; i < 8; ++i) v |= static_cast<uint64_t>(p[i]) << (8 * i);
        p += 8;
        return v;
    }
};

constexpr uint32_t kRecordMagic = 0x4E4C5643u;   // "CVLN"
constexpr uint8_t kRecordVersion = 1;
constexpr uint32_t kStreamColumnMagic = 0x31535643u;   // "CVS1" (stream_impl.hpp kColumnMagic)

} // namespace

uint64_t hash_bytes(const void* data, size_t n, uint64_t seed) {
    // Four independent multiply-rotate lanes over 8-byte words, then splitmix finalisation: ~1 ns per word.
    const auto* p = static_cast<const uint8_t*>(data);
    uint64_t h[4] = {seed ^ 0x243f6a8885a308d3ull, seed ^ 0x13198a2e03707344ull, seed ^ 0xa4093822299f31d0ull,
                     seed ^ 0x082efa98ec4e6c89ull};
    constexpr uint64_t K = 0x9fb21c651e98df25ull;
    size_t i = 0;
    for (; i + 32 <= n; i += 32) {
        for (int l = 0; l < 4; ++l) {
            uint64_t w;
            std::memcpy(&w, p + i + 8 * l, 8);
            h[l] = (h[l] ^ w) * K;
            h[l] = (h[l] << 31) | (h[l] >> 33);
        }
    }
    uint64_t tail = 0;
    int shift = 0;
    for (; i < n; ++i) {
        tail |= static_cast<uint64_t>(p[i]) << shift;
        shift += 8;
        if (shift == 64) {
            h[0] = ((h[0] ^ tail) * K);
            tail = 0;
            shift = 0;
        }
    }
    uint64_t r = mix64(h[0] ^ tail ^ (static_cast<uint64_t>(n) << 1));
    r = mix64(r ^ h[1]);
    r = mix64(r ^ h[2]);
    r = mix64(r ^ h[3]);
    return r;
}

void finalize(NodeData& d) {
    if (!d.uniform && !d.ids.empty()) {
        const uint16_t id0 = d.ids[0];
        const uint8_t c0 = d.cells[0];
        bool same = true;
        for (int i = 1; i < kCells && same; ++i) same = d.ids[static_cast<size_t>(i)] == id0 && d.cells[static_cast<size_t>(i)] == c0;
        if (same) {
            d.uniform = true;
            d.uniform_id = id0;
            d.uniform_cell = c0;
            std::vector<uint16_t>().swap(d.ids);
            std::vector<uint8_t>().swap(d.cells);
        }
    }
    const uint64_t seed = (static_cast<uint64_t>(d.key.level) << 8) | (d.complete ? 1u : 0u);
    uint64_t h;
    if (d.uniform) {
        const uint64_t v = (uint64_t{1} << 40) | (static_cast<uint64_t>(d.uniform_cell) << 16) | d.uniform_id;
        h = mix64(mix64(seed) ^ v);
    } else {
        h = hash_bytes(d.ids.data(), d.ids.size() * 2, seed);
        h = mix64(h ^ hash_bytes(d.cells.data(), d.cells.size(), seed + 1));
    }
    d.digest = h == 0 ? 1 : h;
}

void node_uniform(NodeData& out, const NodeKey& key, uint16_t id, uint8_t cell, bool complete) {
    out = NodeData{};
    out.key = key;
    out.complete = complete;
    out.uniform = true;
    out.uniform_id = id;
    out.uniform_cell = cell;
    finalize(out);
}

void node_from_ids(NodeData& out, const NodeKey& key, const uint16_t* ids, const ClassTable& ct, uint16_t air,
                   int64_t min_y, int64_t max_y) {
    out = NodeData{};
    out.key = key;
    out.complete = true;
    out.ids.resize(kCells);
    out.cells.resize(kCells);
    const int64_t y0 = key.y * kEdge;
    for (int ly = 0; ly < kEdge; ++ly) {
        const int64_t wy = y0 + ly;
        const bool in = wy >= min_y && wy < max_y;
        const int base = ly << 10;
        for (int i = base; i < base + 1024; ++i) {
            const uint16_t id = ids[i];
            const uint8_t c = in ? ct.cls[id] : kAir;
            if (c == kAir) {
                out.ids[static_cast<size_t>(i)] = air;
                out.cells[static_cast<size_t>(i)] = kAir;
            } else {
                out.ids[static_cast<size_t>(i)] = id;
                out.cells[static_cast<size_t>(i)] = c == kSolid ? static_cast<uint8_t>(kSolid | kFull) : c;
            }
        }
    }
    finalize(out);
}

void node_downsample(NodeData& out, const NodeKey& key, const NodePtr* children, uint16_t air) {
    out = NodeData{};
    out.key = key;
    bool complete = true;
    bool all_uniform = true;
    for (int i = 0; i < 8; ++i) {
        const NodeData* c = children[i].get();
        out.child_digest[i] = c != nullptr ? c->digest : 0;
        if (c == nullptr || !c->complete) complete = false;
        if (c == nullptr || !c->uniform) all_uniform = false;
    }
    out.complete = complete;
    // Fast path: eight identical uniform children give the same uniform parent (8 equal sub-cells keep their
    // class, FULL bit and id under the rule).
    if (all_uniform) {
        const NodeData* c0 = children[0].get();
        bool same = true;
        for (int i = 1; i < 8 && same; ++i) {
            same = children[i]->uniform_id == c0->uniform_id && children[i]->uniform_cell == c0->uniform_cell;
        }
        if (same) {
            out.uniform = true;
            out.uniform_id = c0->uniform_id;
            out.uniform_cell = c0->uniform_cell;
            finalize(out);
            return;
        }
    }
    out.ids.assign(kCells, air);
    out.cells.assign(kCells, kAir);
    for (int ci = 0; ci < 8; ++ci) {
        const NodeData* c = children[ci].get();
        const int ox = (ci & 1) * 16, oy = ((ci >> 2) & 1) * 16, oz = ((ci >> 1) & 1) * 16;
        if (c == nullptr) continue;   // unknown: air
        if (c->uniform) {
            if ((c->uniform_cell & kClassMask) == kAir) continue;
            for (int y = 0; y < 16; ++y) {
                for (int z = 0; z < 16; ++z) {
                    const int i = cell_index(ox, oy + y, oz + z);
                    std::fill_n(out.ids.begin() + i, 16, c->uniform_id);
                    std::fill_n(out.cells.begin() + i, 16, c->uniform_cell);
                }
            }
            continue;
        }
        const uint16_t* cid = c->ids.data();
        const uint8_t* ccl = c->cells.data();
        for (int y = 0; y < 16; ++y) {
            for (int z = 0; z < 16; ++z) {
                for (int x = 0; x < 16; ++x) {
                    // Sub-cells: top layer (dy = 1) first.
                    uint16_t sid[8];
                    uint8_t scl[8];
                    int n = 0;
                    for (int dy = 1; dy >= 0; --dy) {
                        for (int dz = 0; dz < 2; ++dz) {
                            for (int dx = 0; dx < 2; ++dx) {
                                const int si = cell_index(2 * x + dx, 2 * y + dy, 2 * z + dz);
                                sid[n] = cid[si];
                                scl[n] = ccl[si];
                                ++n;
                            }
                        }
                    }
                    const int o = cell_index(ox + x, oy + y, oz + z);
                    bool same = true;
                    for (int k = 1; k < 8 && same; ++k) same = scl[k] == scl[0] && sid[k] == sid[0];
                    if (same) {   // 8 equal sub-cells keep their class, FULL bit and id under the rule
                        out.ids[static_cast<size_t>(o)] = (scl[0] & kClassMask) == kAir ? air : sid[0];
                        out.cells[static_cast<size_t>(o)] = scl[0];
                        continue;
                    }
                    int s = 0, f = 0, full = 0;
                    for (int k = 0; k < 8; ++k) {
                        const uint8_t cls = scl[k] & kClassMask;
                        if (cls == kSolid) ++s;
                        else if (cls == kFluid) ++f;
                        if ((scl[k] & kFull) != 0) ++full;
                    }
                    const int a = 8 - s - f;
                    uint8_t cell = kAir;
                    uint16_t id = air;
                    if (s >= 4) {
                        cell = kSolid;
                        if (full == 8) cell |= kFull;
                        // Highest sub-layer holding a solid sub-cell: [0, 4) top, [4, 8) bottom.
                        int lo = 0;
                        bool top_has = false;
                        for (int k = 0; k < 4; ++k) top_has |= (scl[k] & kClassMask) == kSolid;
                        if (!top_has) lo = 4;
                        int best_n = 0;
                        uint16_t best = 0;
                        for (int k = lo; k < lo + 4; ++k) {
                            if ((scl[k] & kClassMask) != kSolid) continue;
                            int cnt = 0;
                            for (int j = lo; j < lo + 4; ++j) cnt += ((scl[j] & kClassMask) == kSolid && sid[j] == sid[k]) ? 1 : 0;
                            if (cnt > best_n || (cnt == best_n && sid[k] < best)) {
                                best_n = cnt;
                                best = sid[k];
                            }
                        }
                        id = best;
                    } else if (f > 0 && f >= a) {
                        cell = kFluid;
                        int best_n = 0;
                        uint16_t best = 0;
                        for (int k = 0; k < 8; ++k) {
                            if ((scl[k] & kClassMask) != kFluid) continue;
                            int cnt = 0;
                            for (int j = 0; j < 8; ++j) cnt += ((scl[j] & kClassMask) == kFluid && sid[j] == sid[k]) ? 1 : 0;
                            if (cnt > best_n || (cnt == best_n && sid[k] < best)) {
                                best_n = cnt;
                                best = sid[k];
                            }
                        }
                        id = best;
                    }
                    out.ids[static_cast<size_t>(o)] = id;
                    out.cells[static_cast<size_t>(o)] = cell;
                }
            }
        }
    }
    finalize(out);
}

// ---- records ------------------------------------------------------------------------------------------------
void encode_record(const NodeData& d, std::vector<uint8_t>& out) {
    out.clear();
    out.reserve(d.uniform ? 128 : 128 + 3 * static_cast<size_t>(kCells));
    put32(out, kRecordMagic);
    put8(out, kRecordVersion);
    put8(out, static_cast<uint8_t>(d.key.level));
    put8(out, static_cast<uint8_t>((d.complete ? 1u : 0u) | (d.uniform ? 2u : 0u)));
    put8(out, 0);
    put64(out, static_cast<uint64_t>(d.key.x));
    put64(out, static_cast<uint64_t>(d.key.y));
    put64(out, static_cast<uint64_t>(d.key.z));
    put64(out, d.digest);
    for (int i = 0; i < 8; ++i) put64(out, d.child_digest[i]);
    if (d.uniform) {
        put16(out, d.uniform_id);
        put8(out, d.uniform_cell);
        put8(out, 0);
    } else {
        for (int i = 0; i < kCells; ++i) put16(out, d.ids[static_cast<size_t>(i)]);
        out.insert(out.end(), d.cells.begin(), d.cells.end());
    }
}

int32_t decode_record(const uint8_t* p, size_t n, const NodeKey& key, NodeData& out) {
    Reader r{p, p + n};
    if (r.u32() != kRecordMagic || r.u8() != kRecordVersion) return CV_ERR_CORRUPT;
    const uint8_t level = r.u8();
    const uint8_t flags = r.u8();
    const uint8_t zero = r.u8();
    const int64_t x = static_cast<int64_t>(r.u64());
    const int64_t y = static_cast<int64_t>(r.u64());
    const int64_t z = static_cast<int64_t>(r.u64());
    if (!r.ok || level != key.level || zero != 0 || (flags & ~3u) != 0 || x != key.x || y != key.y || z != key.z) {
        return CV_ERR_CORRUPT;
    }
    NodeData d;
    d.key = key;
    d.complete = (flags & 1u) != 0;
    d.uniform = (flags & 2u) != 0;
    const uint64_t digest = r.u64();
    for (int i = 0; i < 8; ++i) d.child_digest[i] = r.u64();
    auto valid_cell = [](uint8_t c) { return (c & ~7u) == 0 && (c & kClassMask) != 3 && ((c & kFull) == 0 || (c & kClassMask) == kSolid); };
    if (d.uniform) {
        d.uniform_id = r.u16();
        d.uniform_cell = r.u8();
        if (r.u8() != 0 || !valid_cell(d.uniform_cell)) return CV_ERR_CORRUPT;
    } else {
        if (!r.need(static_cast<size_t>(kCells) * 3)) return CV_ERR_CORRUPT;
        d.ids.resize(kCells);
        d.cells.resize(kCells);
        for (int i = 0; i < kCells; ++i) d.ids[static_cast<size_t>(i)] = r.u16();
        std::memcpy(d.cells.data(), r.p, kCells);
        r.p += kCells;
        for (int i = 0; i < kCells; ++i) {
            if (!valid_cell(d.cells[static_cast<size_t>(i)])) return CV_ERR_CORRUPT;
        }
    }
    if (!r.ok || r.p != r.end) return CV_ERR_CORRUPT;
    // The digest is recomputed (canonical form) and must match the stored one.
    finalize(d);
    if (d.digest != digest) return CV_ERR_CORRUPT;
    out = std::move(d);
    return CV_OK;
}

// ---- foreign records ---------------------------------------------------------------------------------------
int32_t decode_cube_blocks(const uint8_t* p, size_t n, uint16_t* out_ids) {
    Reader r{p, p + n};
    if (r.u8() != 1) return CV_ERR_CORRUPT;
    const uint32_t parts = r.u32();
    if (!r.ok || (parts & CV_PART_BLOCKS) == 0) return CV_ERR_CORRUPT;
    uint16_t palette[256];
    for (int s = 0; s < 8; ++s) {
        const int sx = s & 1, sz = (s >> 1) & 1, sy = (s >> 2) & 1;
        const uint8_t tag = r.u8();
        if (!r.ok) return CV_ERR_CORRUPT;
        auto put = [&](int i, uint16_t id) {
            const int x = i & 15, z = (i >> 4) & 15, y = i >> 8;
            out_ids[cell_index(sx * 16 + x, sy * 16 + y, sz * 16 + z)] = id;
        };
        if (tag == 0) {
            const uint16_t id = r.u16();
            if (!r.ok) return CV_ERR_CORRUPT;
            for (int i = 0; i < 4096; ++i) put(i, id);
        } else if (tag == 1) {
            const uint32_t count = r.u16();
            if (!r.ok || count < 1 || count > 256) return CV_ERR_CORRUPT;
            for (uint32_t i = 0; i < count; ++i) palette[i] = r.u16();
            uint32_t bits = 1;
            while ((1u << bits) < count) ++bits;
            const uint32_t per = 64 / bits;
            const uint32_t words = (4096 + per - 1) / per;
            if (!r.need(static_cast<size_t>(words) * 8)) return CV_ERR_CORRUPT;
            const uint64_t mask = (uint64_t{1} << bits) - 1;
            int i = 0;
            for (uint32_t w = 0; w < words; ++w) {
                const uint64_t word = r.u64();
                for (uint32_t k = 0; k < per && i < 4096; ++k, ++i) {
                    const uint32_t idx = static_cast<uint32_t>((word >> (k * bits)) & mask);
                    if (idx >= count) return CV_ERR_CORRUPT;
                    put(i, palette[idx]);
                }
            }
        } else if (tag == 2) {
            if (!r.need(8192)) return CV_ERR_CORRUPT;
            for (int i = 0; i < 4096; ++i) put(i, r.u16());
        } else {
            return CV_ERR_CORRUPT;
        }
    }
    return r.ok ? CV_OK : CV_ERR_CORRUPT;
}

bool parse_column_record(const uint8_t* p, size_t n, std::vector<Run>& runs, const uint8_t** core, size_t* core_len) {
    runs.clear();
    Reader r{p, p + n};
    if (n >= 4) {
        uint32_t magic = 0;
        for (int i = 0; i < 4; ++i) magic |= static_cast<uint32_t>(p[i]) << (8 * i);
        if (magic == kStreamColumnMagic) {
            r.u32();
            const uint32_t pops = r.u32();
            if (!r.ok || !r.need(static_cast<size_t>(pops) * 16)) return false;
            r.p += static_cast<size_t>(pops) * 16;
            const uint32_t len = r.u32();
            if (!r.ok || static_cast<size_t>(r.end - r.p) != len) return false;
            r.end = r.p + len;
        }
    }
    *core = r.p;
    *core_len = static_cast<size_t>(r.end - r.p);
    if (r.u8() != 1) return false;
    const uint32_t count = r.u32();
    if (!r.ok || !r.need(static_cast<size_t>(count) * 16)) return false;
    runs.reserve(count);
    for (uint32_t i = 0; i < count; ++i) {
        const int64_t lo = static_cast<int64_t>(r.u64());
        const int64_t hi = static_cast<int64_t>(r.u64());
        if (hi < lo) return false;
        runs.push_back(Run{lo, hi});
    }
    return r.ok;
}

// ---- cache ------------------------------------------------------------------------------------------------
NodePtr NodeCache::get(const NodeKey& k, uint64_t* tag) {
    std::lock_guard<std::mutex> g(m_);
    auto it = map_.find(k);
    if (it == map_.end()) return nullptr;
    lru_.splice(lru_.begin(), lru_, it->second.it);
    if (tag != nullptr) *tag = it->second.tag;
    return it->second.d;
}

void NodeCache::put(const NodeKey& k, NodePtr d, uint64_t tag) {
    if (!d) return;
    std::lock_guard<std::mutex> g(m_);
    auto it = map_.find(k);
    if (it != map_.end()) {
        bytes_ -= it->second.d->bytes();
        it->second.d = std::move(d);
        it->second.tag = tag;
        bytes_ += it->second.d->bytes();
        lru_.splice(lru_.begin(), lru_, it->second.it);
    } else {
        lru_.push_front(k);
        Entry e;
        e.d = std::move(d);
        e.tag = tag;
        e.it = lru_.begin();
        bytes_ += e.d->bytes();
        map_.emplace(k, std::move(e));
    }
    while (bytes_ > cap_ && lru_.size() > 1) {
        const NodeKey victim = lru_.back();
        auto v = map_.find(victim);
        bytes_ -= v->second.d->bytes();
        map_.erase(v);
        lru_.pop_back();
    }
}

void NodeCache::erase(const NodeKey& k) {
    std::lock_guard<std::mutex> g(m_);
    auto it = map_.find(k);
    if (it == map_.end()) return;
    bytes_ -= it->second.d->bytes();
    lru_.erase(it->second.it);
    map_.erase(it);
}

size_t NodeCache::bytes() const {
    std::lock_guard<std::mutex> g(m_);
    return bytes_;
}

size_t NodeCache::count() const {
    std::lock_guard<std::mutex> g(m_);
    return map_.size();
}

} // namespace cenda::voxel::lod
