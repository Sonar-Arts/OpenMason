// Cenda voxel world core (cv_*): cubes, blocks, host layers, blobs, masks, revisions, the dirty set and
// the cube encoding. Contract in include/cenda/voxel_world.h (OpenMason #141); the surface tracker and
// column records are in cv_surface.cpp, the engine-internal API in cv_internal.cpp.
#include "cv_error.hpp"
#include "cv_impl.hpp"
#include "cv_internal.hpp"

#include <algorithm>
#include <bit>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <new>
#include <utility>

namespace cenda::voxel {

// ---- errors -----------------------------------------------------------------------------------------

namespace {
thread_local char t_error[512] = "";

constexpr int64_t kCubeMin = INT64_MIN >> CV_CUBE_SHIFT;
constexpr int64_t kCubeMax = INT64_MAX >> CV_CUBE_SHIFT;
constexpr uint8_t kEncodingVersion = 1;
constexpr uint8_t kTagSingle = 0;
constexpr uint8_t kTagPaletted = 1;
constexpr uint8_t kTagDirect = 2;
constexpr uint32_t kLayerParts = 0xFFu << 8;
constexpr uint32_t kValidParts = CV_PART_BLOCKS | kLayerParts | CV_PART_BLOB;

bool cube_coord_ok(int64_t c) { return c >= kCubeMin && c <= kCubeMax; }
bool cube_ok(int64_t cx, int64_t cy, int64_t cz) { return cube_coord_ok(cx) && cube_coord_ok(cy) && cube_coord_ok(cz); }
bool layer_ok(int32_t l) { return l >= 0 && l < CV_MAX_LAYERS; }

int lc(int64_t v) { return static_cast<int>(v & (CV_CUBE_EDGE - 1)); }
} // namespace

int32_t fail(int32_t code, const char* message) {
    std::snprintf(t_error, sizeof t_error, "%s", message);
    return code;
}

} // namespace cenda::voxel

void cv_set_error(const char* fmt, ...) {
    va_list args;
    va_start(args, fmt);
    std::vsnprintf(cenda::voxel::t_error, sizeof cenda::voxel::t_error, fmt, args);
    va_end(args);
}

namespace cenda::voxel {

// ---- sections, cubes, derived masks -------------------------------------------------------------------

Section* new_sections(uint16_t fill) {
    auto* s = new Section[8];
    for (int i = 0; i < 8; ++i) s[i].fill(fill);
    return s;
}

void delete_sections(Section* sec) {
    delete[] sec;
}

void channel_row(const Section* sec, uint16_t uniform, int ly, int lz, uint16_t* out) {
    if (sec == nullptr) {
        for (int i = 0; i < 32; ++i) out[i] = uniform;
        return;
    }
    const uint32_t s0 = section_of(0, ly, lz);
    const auto row = static_cast<uint32_t>((ly & 15) * 16 + (lz & 15));
    sec[s0].read_row(row, out);
    sec[s0 + 1].read_row(row, out + 16);
}

// Whole channel in CV_CELL_INDEX order.
void read_channel(const Section* sec, uint16_t uniform, uint16_t* out) {
    if (sec == nullptr) {
        std::fill(out, out + CV_CUBE_CELLS, uniform);
        return;
    }
    uint16_t tmp[kSectionCells];
    for (int s = 0; s < 8; ++s) {
        const int sx = s & 1, sz = (s >> 1) & 1, sy = s >> 2;
        const Section& section = sec[s];
        const bool uni = section.is_uniform_storage();
        if (!uni) section.read(tmp);
        for (int sly = 0; sly < 16; ++sly) {
            for (int slz = 0; slz < 16; ++slz) {
                uint16_t* dst = out + CV_CELL_INDEX(sx * 16, sy * 16 + sly, sz * 16 + slz);
                if (uni) {
                    std::fill(dst, dst + 16, section.uniform_value());
                } else {
                    std::memcpy(dst, tmp + (sly * 16 + slz) * 16, 32);
                }
            }
        }
    }
}

namespace {

bool sections_uniform(const Section* sec, uint16_t* v) {
    uint16_t first = 0;
    if (!sec[0].uniform(&first)) return false;
    for (int s = 1; s < 8; ++s) {
        uint16_t x = 0;
        if (!sec[s].uniform(&x) || x != first) return false;
    }
    *v = first;
    return true;
}

bool sections_all_uniform_storage(const Section* sec, uint16_t* v) {
    for (int s = 0; s < 8; ++s) {
        if (!sec[s].is_uniform_storage() || sec[s].uniform_value() != sec[0].uniform_value()) return false;
    }
    *v = sec[0].uniform_value();
    return true;
}

// Builds a channel from cube-order values (null: every cell `fill`), forcing cells outside the vertical
// bounds to `oob`. Returns null when the result is one value (written to *uniform).
Section* build_channel(const cv_world* w, int64_t cy, const uint16_t* values, uint16_t fill, uint16_t oob,
                       uint16_t* uniform) {
    const bool inside = w->cube_inside(cy);
    if (values == nullptr && inside) {
        *uniform = fill;
        return nullptr;
    }
    thread_local std::vector<uint16_t> scratch;
    const uint16_t* src = values;
    if (!inside) {
        scratch.resize(CV_CUBE_CELLS);
        if (values != nullptr) {
            std::memcpy(scratch.data(), values, CV_CUBE_CELLS * 2);
        } else {
            std::fill(scratch.begin(), scratch.end(), fill);
        }
        for (int ly = 0; ly < CV_CUBE_EDGE; ++ly) {
            if (!w->y_in(cy * CV_CUBE_EDGE + ly)) {
                std::fill(scratch.begin() + (ly << 10), scratch.begin() + ((ly + 1) << 10), oob);
            }
        }
        src = scratch.data();
    }
    Section* sec = new Section[8];
    uint16_t gathered[kSectionCells];
    for (int s = 0; s < 8; ++s) {
        const int sx = s & 1, sz = (s >> 1) & 1, sy = s >> 2;
        for (int sly = 0; sly < 16; ++sly) {
            for (int slz = 0; slz < 16; ++slz) {
                std::memcpy(gathered + (sly * 16 + slz) * 16,
                            src + CV_CELL_INDEX(sx * 16, sy * 16 + sly, sz * 16 + slz), 32);
            }
        }
        sec[s].assign(gathered);
    }
    uint16_t u = 0;
    if (sections_all_uniform_storage(sec, &u)) {
        delete[] sec;
        *uniform = u;
        return nullptr;
    }
    return sec;
}

void top_from_rows(const uint32_t* rows, int64_t base, int64_t* leaf) {
    std::fill(leaf, leaf + kColumnCells, kNone);
    for (int lz = 0; lz < 32; ++lz) {
        uint32_t remaining = ~0u;
        for (int ly = 31; ly >= 0 && remaining != 0; --ly) {
            uint32_t bits = rows[ly * 32 + lz] & remaining;
            remaining &= ~bits;
            while (bits != 0) {
                const int lx = std::countr_zero(bits);
                bits &= bits - 1;
                leaf[lz * 32 + lx] = base + ly;
            }
        }
    }
}

uint32_t* collapse(const uint32_t* rows, uint8_t& flags, uint8_t full_bit) {
    bool all_on = true, all_off = true;
    for (uint32_t r = 0; r < 1024; ++r) {
        all_on = all_on && rows[r] == ~0u;
        all_off = all_off && rows[r] == 0;
    }
    if (all_on) {
        flags = static_cast<uint8_t>(flags | full_bit);
        return nullptr;
    }
    if (all_off) return nullptr;
    auto* out = new uint32_t[1024];
    std::memcpy(out, rows, 1024 * 4);
    return out;
}

} // namespace

Derived::~Derived() {
    delete[] occ;
    delete[] opq;
}

void derive(const Section* sec, uint16_t uid, const BlockTable* t, uint16_t air, int64_t cy, Derived& d) {
    delete[] d.occ;
    delete[] d.opq;
    d.occ = nullptr;
    d.opq = nullptr;
    d.flags = 0;
    d.packed = false;
    d.table = t;
    const int64_t base = cy * CV_CUBE_EDGE;
    if (sec == nullptr) {
        const uint16_t f = t->flags[uid];
        d.flags = static_cast<uint8_t>((uid != air ? kOccFull : 0) | ((f & CV_BLOCK_OPAQUE) != 0 ? kOpqFull : 0));
        std::fill(d.leaf[kSky], d.leaf[kSky] + kColumnCells, (f & CV_BLOCK_SKY_OPAQUE) != 0 ? base + 31 : kNone);
        std::fill(d.leaf[kTop], d.leaf[kTop] + kColumnCells, uid != air ? base + 31 : kNone);
        return;
    }
    uint32_t occ[1024] = {}, opq[1024] = {}, sky[1024] = {};
    uint16_t values[kSectionCells];
    for (int si = 0; si < 8; ++si) {
        const int sx = si & 1, sz = (si >> 1) & 1, sy = si >> 2;
        const Section& section = sec[si];
        if (section.is_uniform_storage()) {
            const uint16_t id = section.uniform_value();
            const uint16_t f = t->flags[id];
            const uint32_t half = 0xFFFFu << (16 * sx);
            const uint32_t o = id != air ? half : 0u;
            const uint32_t p = (f & CV_BLOCK_OPAQUE) != 0 ? half : 0u;
            const uint32_t s = (f & CV_BLOCK_SKY_OPAQUE) != 0 ? half : 0u;
            for (int sly = 0; sly < 16; ++sly) {
                for (int slz = 0; slz < 16; ++slz) {
                    const int r = (sy * 16 + sly) * 32 + sz * 16 + slz;
                    occ[r] |= o;
                    opq[r] |= p;
                    sky[r] |= s;
                }
            }
            continue;
        }
        section.read(values);
        uint16_t last = values[0];
        uint32_t lo = last != air ? 1u : 0u;
        uint32_t lp = (t->flags[last] & CV_BLOCK_OPAQUE) != 0 ? 1u : 0u;
        uint32_t ls = (t->flags[last] & CV_BLOCK_SKY_OPAQUE) != 0 ? 1u : 0u;
        for (int sly = 0; sly < 16; ++sly) {
            for (int slz = 0; slz < 16; ++slz) {
                const uint16_t* row = values + (sly * 16 + slz) * 16;
                uint32_t o = 0, p = 0, s = 0;
                for (uint32_t x = 0; x < 16; ++x) {
                    const uint16_t id = row[x];
                    if (id != last) {
                        last = id;
                        const uint16_t f = t->flags[id];
                        lo = id != air ? 1u : 0u;
                        lp = (f & CV_BLOCK_OPAQUE) != 0 ? 1u : 0u;
                        ls = (f & CV_BLOCK_SKY_OPAQUE) != 0 ? 1u : 0u;
                    }
                    o |= lo << x;
                    p |= lp << x;
                    s |= ls << x;
                }
                const int r = (sy * 16 + sly) * 32 + sz * 16 + slz;
                const int shift = 16 * sx;
                occ[r] |= o << shift;
                opq[r] |= p << shift;
                sky[r] |= s << shift;
            }
        }
    }
    d.occ = collapse(occ, d.flags, kOccFull);
    d.opq = collapse(opq, d.flags, kOpqFull);
    top_from_rows(sky, base, d.leaf[kSky]);
    top_from_rows(occ, base, d.leaf[kTop]);
}

void pack_leaves(Derived& d) {
    for (int ch = 0; ch < kChannels; ++ch) d.leaf_packed[ch].assign(d.leaf[ch]);
    d.packed = true;
}

PreparedData::~PreparedData() {
    delete_sections(sec);
    delete extra;
}

CubeExtra::~CubeExtra() {
    for (Section*& s : layer) {
        delete_sections(s);
        s = nullptr;
    }
}

bool CubeExtra::empty() const {
    for (const Section* s : layer) {
        if (s != nullptr) return false;
    }
    return blob.empty();
}

size_t CubeExtra::heap_bytes() const {
    size_t n = sizeof(CubeExtra) + blob.capacity();
    for (const Section* s : layer) {
        if (s == nullptr) continue;
        n += 8 * sizeof(Section);
        for (int i = 0; i < 8; ++i) n += s[i].heap_bytes();
    }
    return n;
}

Cube::~Cube() {
    delete_sections(sec);
    delete[] occ;
    delete[] opq;
    delete extra;
}

size_t Cube::heap_bytes() const {
    size_t n = sizeof(Cube);
    if (sec != nullptr) {
        n += 8 * sizeof(Section);
        for (int i = 0; i < 8; ++i) n += sec[i].heap_bytes();
    }
    if (occ != nullptr) n += 4096;
    if (opq != nullptr) n += 4096;
    if (extra != nullptr) n += extra->heap_bytes();
    return n;
}

// ---- locking ------------------------------------------------------------------------------------------

namespace {
template <bool Exclusive>
Cube* acquire_cube(const cv_world* cw, int64_t cx, int64_t cy, int64_t cz) {
    auto* w = const_cast<cv_world*>(cw);
    const uint64_t h = hash3(cx, cy, cz);
    auto& s = w->cubes.shard(h);
    s.lock.lock_shared();
    Cube* c = ShardedMap<Cube>::find(s, cx, cy, cz, h);
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
} // namespace

Cube* cube_shared(const cv_world* w, int64_t cx, int64_t cy, int64_t cz) {
    return acquire_cube<false>(w, cx, cy, cz);
}
Cube* cube_exclusive(cv_world* w, int64_t cx, int64_t cy, int64_t cz) {
    return acquire_cube<true>(w, cx, cy, cz);
}
bool cube_present(const cv_world* cw, int64_t cx, int64_t cy, int64_t cz) {
    auto* w = const_cast<cv_world*>(cw);
    const uint64_t h = hash3(cx, cy, cz);
    auto& s = w->cubes.shard(h);
    s.lock.lock_shared();
    const bool found = ShardedMap<Cube>::find(s, cx, cy, cz, h) != nullptr;
    s.lock.unlock_shared();
    return found;
}

// ---- dirty set ---------------------------------------------------------------------------------------

void mark_present(cv_world* w, const std::vector<Int3>& keys) {
    thread_local std::vector<Int3> present;
    present.clear();
    for (const Int3& k : keys) {
        if (cube_present(w, k.x, k.y, k.z)) present.push_back(k);
    }
    if (present.empty()) return;
    std::lock_guard<std::mutex> g(w->dirty_mutex);
    for (const Int3& k : present) w->dirty.insert(k);
}

void mark_neighbourhood(cv_world* w, int64_t cx, int64_t cy, int64_t cz) {
    thread_local std::vector<Int3> keys;
    keys.clear();
    for (int64_t dy = -1; dy <= 1; ++dy) {
        for (int64_t dz = -1; dz <= 1; ++dz) {
            for (int64_t dx = -1; dx <= 1; ++dx) {
                const int64_t x = cx + dx, y = cy + dy, z = cz + dz;
                if (cube_ok(x, y, z)) keys.push_back(Int3{x, y, z});
            }
        }
    }
    mark_present(w, keys);
}

namespace {

// The cube of a cell plus every neighbour whose 1-cell padding contains it.
void mark_cell(cv_world* w, int64_t cx, int64_t cy, int64_t cz, int lx, int ly, int lz) {
    thread_local std::vector<Int3> keys;
    keys.clear();
    for (int dy = -1; dy <= 1; ++dy) {
        if ((dy == -1 && ly != 0) || (dy == 1 && ly != 31)) continue;
        for (int dz = -1; dz <= 1; ++dz) {
            if ((dz == -1 && lz != 0) || (dz == 1 && lz != 31)) continue;
            for (int dx = -1; dx <= 1; ++dx) {
                if ((dx == -1 && lx != 0) || (dx == 1 && lx != 31)) continue;
                const int64_t x = cx + dx, y = cy + dy, z = cz + dz;
                if (cube_ok(x, y, z)) keys.push_back(Int3{x, y, z});
            }
        }
    }
    mark_present(w, keys);
}

int64_t bump(cv_world* w) {
    return w->revision.fetch_add(1, std::memory_order_acq_rel) + 1;
}

// ---- install (commit of a prepared cube) ------------------------------------------------------------

// Replaces (or creates) a whole cube: blocks, layers and blob from p; masks + leaves from p.d (re-derived
// when the block table changed since p was prepared). The previous content ends up in p.
int32_t commit(cv_world* w, int64_t cx, int64_t cy, int64_t cz, PreparedData& p, bool replace) {
    std::shared_ptr<const SurfaceCache> cb = p.cb ? p.cb : ensure_surface_cache(w, cx, cz);
    Cube* c = nullptr;
    bool created = false;
    for (;;) {
        c = cube_exclusive(w, cx, cy, cz);
        if (c != nullptr) {
            if (!replace) {
                c->lock.unlock();
                return fail(CV_ERR_EXISTS, "cube already exists");
            }
            break;
        }
        auto* fresh = new Cube();
        fresh->lock.lock();   // unpublished: uncontended
        const uint64_t h = hash3(cx, cy, cz);
        auto& s = w->cubes.shard(h);
        s.lock.lock();
        if (ShardedMap<Cube>::find(s, cx, cy, cz, h) != nullptr) {
            s.lock.unlock();
            fresh->lock.unlock();
            delete fresh;
            continue;
        }
        w->cubes.insert(s, cx, cy, cz, h, fresh);
        s.lock.unlock();
        c = fresh;
        created = true;
        break;
    }
    Derived& d = p.d;
    const BlockTable* now = w->table.load();
    if (now != d.table) derive(p.sec, p.uniform_id, now, w->air, cy, d);
    std::swap(c->sec, p.sec);
    std::swap(c->uniform_id, p.uniform_id);
    std::swap(c->extra, p.extra);
    std::swap(c->occ, d.occ);
    std::swap(c->opq, d.opq);
    c->flags = d.flags;
    c->revision = bump(w);

    Column* col = column_create_exclusive(w, cx, cz, cb);
    SurfaceDelta delta;
    column_apply_cube(w, col, cy, d, created, delta);
    col->lock.unlock();
    c->lock.unlock();

    mark_neighbourhood(w, cx, cy, cz);
    apply_surface_delta(w, cx, cz, delta);
    return CV_OK;
}

// The off-thread half: masks, leaves (packed), the column's callback answer.
void finish_prepare(const cv_world* cw, PreparedData& p) {
    auto* w = const_cast<cv_world*>(cw);
    derive(p.sec, p.uniform_id, w->table.load(), w->air, p.cy, p.d);
    pack_leaves(p.d);
    p.cb = ensure_surface_cache(w, p.cx, p.cz);
}

// Re-derives masks and leaves of a present cube from its content (table change, column decode).
void reapply(cv_world* w, int64_t cx, int64_t cy, int64_t cz) {
    Derived d;
    Cube* c = cube_exclusive(w, cx, cy, cz);
    if (c == nullptr) return;
    derive(c->sec, c->uniform_id, w->table.load(), w->air, cy, d);
    std::swap(c->occ, d.occ);
    std::swap(c->opq, d.opq);
    c->flags = static_cast<uint8_t>(d.flags | (c->flags & kCompactHint));
    SurfaceDelta delta;
    Column* col = column_exclusive(w, cx, cz);
    if (col != nullptr) {
        column_apply_cube(w, col, cy, d, false, delta);
        col->lock.unlock();
    }
    c->lock.unlock();
    apply_surface_delta(w, cx, cz, delta);
}

// ---- encoding ---------------------------------------------------------------------------------------

void put_u8(std::vector<uint8_t>& o, uint8_t v) { o.push_back(v); }
void put_u16(std::vector<uint8_t>& o, uint16_t v) {
    o.push_back(static_cast<uint8_t>(v));
    o.push_back(static_cast<uint8_t>(v >> 8));
}
void put_u32(std::vector<uint8_t>& o, uint32_t v) {
    for (int i = 0; i < 4; ++i) o.push_back(static_cast<uint8_t>(v >> (8 * i)));
}
void put_u64(std::vector<uint8_t>& o, uint64_t v) {
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
        const auto v = static_cast<uint16_t>(p[0] | (p[1] << 8));
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

uint32_t index_bits(uint32_t count) {
    uint32_t bits = 1;
    while ((1u << bits) < count) ++bits;
    return bits;
}

void encode_values(std::vector<uint8_t>& out, const uint16_t* values) {
    uint16_t pal[256];
    uint8_t index[kSectionCells];
    const uint32_t n = distinct_values(values, pal, index);
    if (n > 256) {
        put_u8(out, kTagDirect);
        for (uint32_t i = 0; i < kSectionCells; ++i) put_u16(out, values[i]);
        return;
    }
    if (n == 1) {
        put_u8(out, kTagSingle);
        put_u16(out, pal[0]);
        return;
    }
    put_u8(out, kTagPaletted);
    put_u16(out, static_cast<uint16_t>(n));
    for (uint32_t i = 0; i < n; ++i) put_u16(out, pal[i]);
    const uint32_t bits = index_bits(n);
    const uint32_t per = 64 / bits;
    uint64_t word = 0;
    uint32_t used = 0;
    for (uint32_t i = 0; i < kSectionCells; ++i) {
        word |= static_cast<uint64_t>(index[i]) << (used * bits);
        if (++used == per) {
            put_u64(out, word);
            word = 0;
            used = 0;
        }
    }
    if (used != 0) put_u64(out, word);
}

void encode_section(std::vector<uint8_t>& out, const Section& s) {
    if (s.is_uniform_storage()) {
        put_u8(out, kTagSingle);
        put_u16(out, s.uniform_value());
        return;
    }
    uint16_t values[kSectionCells];
    s.read(values);
    encode_values(out, values);
}

void encode_channel(std::vector<uint8_t>& out, const Section* sec, uint16_t uniform) {
    for (int s = 0; s < 8; ++s) {
        if (sec == nullptr) {
            put_u8(out, kTagSingle);
            put_u16(out, uniform);
        } else {
            encode_section(out, sec[s]);
        }
    }
}

// Decodes one section's values. For PALETTED sections *n_out, pal and index hold the encoded palette;
// otherwise *n_out is 0.
bool decode_values(Reader& r, uint16_t* out, uint32_t* n_out, uint16_t* pal, uint8_t* index) {
    *n_out = 0;
    const uint8_t tag = r.u8();
    if (!r.ok) return false;
    if (tag == kTagSingle) {
        const uint16_t v = r.u16();
        std::fill(out, out + kSectionCells, v);
        return r.ok;
    }
    if (tag == kTagDirect) {
        if (!r.need(kSectionCells * 2)) return false;
        for (uint32_t i = 0; i < kSectionCells; ++i) out[i] = r.u16();
        return r.ok;
    }
    if (tag != kTagPaletted) return false;
    const uint32_t n = r.u16();
    if (!r.ok || n == 0 || n > 256) return false;
    for (uint32_t i = 0; i < n; ++i) pal[i] = r.u16();
    const uint32_t bits = index_bits(n);
    const uint32_t per = 64 / bits;
    const uint32_t words = (kSectionCells + per - 1) / per;
    if (!r.need(static_cast<size_t>(words) * 8)) return false;
    const uint64_t mask = (uint64_t{1} << bits) - 1;
    uint32_t i = 0;
    for (uint32_t wi = 0; wi < words; ++wi) {
        const uint64_t word = r.u64();
        for (uint32_t k = 0; k < per && i < kSectionCells; ++k, ++i) {
            const auto idx = static_cast<uint32_t>((word >> (k * bits)) & mask);
            if (idx >= n) return false;
            out[i] = pal[idx];
            index[i] = static_cast<uint8_t>(idx);
        }
    }
    *n_out = n;
    return r.ok;
}

// Decodes 8 sections; null result when the channel is one value (*uniform).
bool decode_channel(Reader& r, Section*& sec, uint16_t* uniform) {
    sec = new Section[8];
    uint16_t values[kSectionCells];
    uint16_t pal[256];
    uint8_t index[kSectionCells];
    for (int s = 0; s < 8; ++s) {
        uint32_t n = 0;
        if (!decode_values(r, values, &n, pal, index)) {
            delete[] sec;
            sec = nullptr;
            return false;
        }
        if (n > 0) {
            sec[s].assign_indexed(values, pal, n, index);
        } else if (values[0] == values[kSectionCells - 1] && std::all_of(values, values + kSectionCells,
                                                                        [&](uint16_t x) { return x == values[0]; })) {
            sec[s].fill(values[0]);
        } else {
            sec[s].assign(values);
        }
    }
    uint16_t u = 0;
    if (sections_all_uniform_storage(sec, &u)) {
        delete[] sec;
        sec = nullptr;
        *uniform = u;
    }
    return true;
}

// Forces out-of-bounds cells of a decoded channel (partially outside cubes only).
Section* clip_channel(const cv_world* w, int64_t cy, Section* sec, uint16_t* uniform, uint16_t oob) {
    if (w->cube_inside(cy)) return sec;
    std::vector<uint16_t> all(CV_CUBE_CELLS);
    read_channel(sec, *uniform, all.data());
    delete_sections(sec);
    return build_channel(w, cy, all.data(), 0, oob, uniform);
}

uint32_t resolve_parts(const cv_world* w, const Cube* c, uint32_t parts, bool* bad) {
    uint32_t want = 0;
    *bad = false;
    if (parts == CV_PARTS_SAVED || parts == CV_PARTS_NETWORK) {
        const uint32_t layer_flag = parts == CV_PARTS_SAVED ? CV_LAYER_SAVED : CV_LAYER_NETWORK;
        want = CV_PART_BLOCKS;
        for (int l = 0; l < CV_MAX_LAYERS; ++l) {
            if ((w->layer_flags[l].load(std::memory_order_relaxed) & layer_flag) != 0) want |= CV_PART_LAYER(l);
        }
        if (parts == CV_PARTS_SAVED) want |= CV_PART_BLOB;
    } else {
        if ((parts & ~kValidParts) != 0) {
            *bad = true;
            return 0;
        }
        want = parts;
    }
    for (int l = 0; l < CV_MAX_LAYERS; ++l) {
        if ((want & CV_PART_LAYER(l)) != 0 && c->layer(l) == nullptr) want &= ~CV_PART_LAYER(l);
    }
    if ((want & CV_PART_BLOB) != 0 && (c->extra == nullptr || c->extra->blob.empty())) want &= ~CV_PART_BLOB;
    return want;
}

} // namespace

void copy_channel_box(const Section* sec, uint16_t uniform, int x0, int y0, int z0, int sx, int sy, int sz,
                      uint16_t* dst, ptrdiff_t stride_z, ptrdiff_t stride_y) {
    uint16_t row[32];
    for (int y = 0; y < sy; ++y) {
        for (int z = 0; z < sz; ++z) {
            uint16_t* d = dst + y * stride_y + z * stride_z;
            if (sec == nullptr) {
                std::fill(d, d + sx, uniform);
                continue;
            }
            channel_row(sec, uniform, y0 + y, z0 + z, row);
            std::memcpy(d, row + x0, static_cast<size_t>(sx) * 2);
        }
    }
}

namespace {

void maybe_hint(Cube* c, const Section& s) {
    if (s.compactable()) c->flags = static_cast<uint8_t>(c->flags | kCompactHint);
}

void compact_cube(const cv_world* w, Cube* c) {
    if (c->sec != nullptr) {
        for (int s = 0; s < 8; ++s) c->sec[s].compact();
        uint16_t u = 0;
        if (sections_all_uniform_storage(c->sec, &u)) {
            delete_sections(c->sec);
            c->sec = nullptr;
            c->uniform_id = u;
            delete[] c->occ;
            delete[] c->opq;
            c->occ = nullptr;
            c->opq = nullptr;
            const uint16_t f = w->table.load()->flags[u];
            c->flags = static_cast<uint8_t>((c->flags & kCompactHint) | (u != w->air ? kOccFull : 0) |
                                            ((f & CV_BLOCK_OPAQUE) != 0 ? kOpqFull : 0));
        }
    }
    if (c->extra != nullptr) {
        for (Section*& l : c->extra->layer) {
            if (l == nullptr) continue;
            for (int s = 0; s < 8; ++s) l[s].compact();
            uint16_t u = 0;
            if (sections_all_uniform_storage(l, &u) && u == 0) {
                delete_sections(l);
                l = nullptr;
            }
        }
        if (c->extra->empty()) {
            delete c->extra;
            c->extra = nullptr;
        }
    }
    c->flags = static_cast<uint8_t>(c->flags & ~kCompactHint);
}

} // namespace

// ---- prepare / install (cv_internal.hpp) -----------------------------------------------------------------

PreparedCube::PreparedCube() noexcept = default;
PreparedCube::~PreparedCube() {
    delete data_;
}
PreparedCube::PreparedCube(PreparedCube&& o) noexcept : data_(o.data_) {
    o.data_ = nullptr;
}
PreparedCube& PreparedCube::operator=(PreparedCube&& o) noexcept {
    if (this != &o) {
        delete data_;
        data_ = o.data_;
        o.data_ = nullptr;
    }
    return *this;
}
void PreparedCube::reset() {
    delete data_;
    data_ = nullptr;
}
void PreparedCube::adopt(PreparedData* d) noexcept {
    delete data_;
    data_ = d;
}
size_t PreparedCube::heap_bytes() const {
    if (data_ == nullptr) return 0;
    size_t n = sizeof(PreparedData);
    if (data_->sec != nullptr) {
        n += 8 * sizeof(Section);
        for (int i = 0; i < 8; ++i) n += data_->sec[i].heap_bytes();
    }
    if (data_->d.occ != nullptr) n += 4096;
    if (data_->d.opq != nullptr) n += 4096;
    if (data_->extra != nullptr) n += data_->extra->heap_bytes();
    for (const Packed& l : data_->d.leaf_packed) n += l.heap_bytes();
    return n;
}

int32_t prepare_cube(const cv_world* w, int64_t cx, int64_t cy, int64_t cz, const CubeInstall& in, PreparedCube& out) {
    out.reset();
    if (w == nullptr || !cube_ok(cx, cy, cz)) return fail(CV_ERR_ARG, "bad world or cube coordinates");
    if (!w->cube_in(cy)) return fail(CV_ERR_ARG, "cube lies outside the vertical bounds");
    if (in.blob_len < 0 || in.blob_len > INT32_MAX || (in.blob_len > 0 && in.blob == nullptr)) {
        return fail(CV_ERR_ARG, "bad blob");
    }
    auto p = std::make_unique<PreparedData>();
    p->world = w;
    p->cx = cx;
    p->cy = cy;
    p->cz = cz;
    p->sec = build_channel(w, cy, in.ids, in.fill_id, w->air, &p->uniform_id);
    for (int l = 0; l < CV_MAX_LAYERS; ++l) {
        if (in.layers[l] == nullptr) continue;
        uint16_t u = 0;
        Section* ls = build_channel(w, cy, in.layers[l], 0, 0, &u);
        if (ls == nullptr && u == 0) continue;
        if (ls == nullptr) ls = new_sections(u);
        if (p->extra == nullptr) p->extra = new CubeExtra();
        p->extra->layer[l] = ls;
    }
    if (in.blob_len > 0) {
        if (p->extra == nullptr) p->extra = new CubeExtra();
        p->extra->blob.assign(in.blob, in.blob + in.blob_len);
    }
    finish_prepare(w, *p);
    out.adopt(p.release());
    return CV_OK;
}

int32_t prepare_cube_encoded(const cv_world* w, int64_t cx, int64_t cy, int64_t cz, const uint8_t* src, int64_t len,
                             PreparedCube& out) {
    out.reset();
    if (w == nullptr || len < 0 || (len > 0 && src == nullptr) || !cube_ok(cx, cy, cz)) {
        return fail(CV_ERR_ARG, "bad arguments");
    }
    if (!w->cube_in(cy)) return fail(CV_ERR_ARG, "cube lies outside the vertical bounds");
    Reader r{src, src + len};
    const uint8_t version = r.u8();
    const uint32_t parts = r.u32();
    if (!r.ok || version != kEncodingVersion) return fail(CV_ERR_CORRUPT, "bad cube encoding version");
    if ((parts & ~kValidParts) != 0) return fail(CV_ERR_CORRUPT, "unknown cube parts");
    auto p = std::make_unique<PreparedData>();
    p->world = w;
    p->cx = cx;
    p->cy = cy;
    p->cz = cz;
    p->uniform_id = w->air;
    if ((parts & CV_PART_BLOCKS) != 0) {
        if (!decode_channel(r, p->sec, &p->uniform_id)) return fail(CV_ERR_CORRUPT, "corrupt block sections");
        p->sec = clip_channel(w, cy, p->sec, &p->uniform_id, w->air);
    }
    for (int l = 0; l < CV_MAX_LAYERS; ++l) {
        if ((parts & CV_PART_LAYER(l)) == 0) continue;
        Section* ls = nullptr;
        uint16_t u = 0;
        if (!decode_channel(r, ls, &u)) return fail(CV_ERR_CORRUPT, "corrupt layer sections");
        ls = clip_channel(w, cy, ls, &u, 0);
        if (ls == nullptr && u == 0) continue;
        if (ls == nullptr) ls = new_sections(u);
        if (p->extra == nullptr) p->extra = new CubeExtra();
        p->extra->layer[l] = ls;
    }
    if ((parts & CV_PART_BLOB) != 0) {
        const uint32_t n = r.u32();
        if (!r.need(n)) return fail(CV_ERR_CORRUPT, "truncated blob");
        if (n > 0) {
            if (p->extra == nullptr) p->extra = new CubeExtra();
            p->extra->blob.assign(r.p, r.p + n);
            r.p += n;
        }
    }
    if (!r.ok || r.p != r.end) return fail(CV_ERR_CORRUPT, "trailing or truncated cube bytes");
    finish_prepare(w, *p);
    out.adopt(p.release());
    return CV_OK;
}

int32_t install_prepared(cv_world* w, int64_t cx, int64_t cy, int64_t cz, PreparedCube&& prepared, bool replace) {
    PreparedData* p = prepared.data();
    if (w == nullptr || p == nullptr) return fail(CV_ERR_ARG, "bad world or empty prepared cube");
    if (p->world != w || p->cx != cx || p->cy != cy || p->cz != cz) {
        return fail(CV_ERR_ARG, "prepared for another world or cube");
    }
    const int32_t rc = commit(w, cx, cy, cz, *p, replace);
    if (rc == CV_OK) prepared.reset();
    return rc;
}

int32_t install_prepared_ids(cv_world* w, int64_t cx, int64_t cy, int64_t cz, const CubeInstall& in, bool replace) {
    PreparedCube p;
    const int32_t rc = prepare_cube(w, cx, cy, cz, in, p);
    if (rc != CV_OK) return rc;
    return install_prepared(w, cx, cy, cz, std::move(p), replace);
}

void reapply_cube(cv_world* w, int64_t cx, int64_t cy, int64_t cz) {
    reapply(w, cx, cy, cz);
}

} // namespace cenda::voxel

using namespace cenda::voxel;
using cenda::Int3;
using cenda::hash3;

extern "C" {

int32_t cv_abi_version(void) {
    return CV_ABI_VERSION;
}

const char* cv_last_error(void) {
    return t_error;
}

cv_world* cv_world_create(const cv_world_desc* desc) {
    if (desc == nullptr) {
        fail(CV_ERR_ARG, "null desc");
        return nullptr;
    }
    if (desc->min_y >= desc->max_y) {
        fail(CV_ERR_ARG, "min_y must be below max_y");
        return nullptr;
    }
    try {
        auto* w = new cv_world();
        w->min_y = desc->min_y;
        w->max_y = desc->max_y;
        w->surface = desc->surface;
        w->user = desc->user;
        w->air = desc->air_id;
        auto t = std::make_unique<BlockTable>();
        std::memset(t->flags, 0, sizeof t->flags);
        w->table.store(t.get());
        w->tables.push_back(std::move(t));
        w->unknown_cache = make_surface_cache(w, false, nullptr, nullptr);
        return w;
    } catch (const std::bad_alloc&) {
        fail(CV_ERR_ARG, "out of memory");
        return nullptr;
    }
}

cv_world* cv_world_create_with_generator(const cv_world_desc* desc, const cv_generator* generator) {
    if (desc == nullptr || generator == nullptr) {
        fail(CV_ERR_ARG, "null desc or generator");
        return nullptr;
    }
    if (desc->surface != nullptr || desc->user != nullptr) {
        fail(CV_ERR_ARG, "desc->surface and desc->user must be NULL (the generator provides them)");
        return nullptr;
    }
    if (generator->abi != CV_ABI_VERSION) {
        cv_set_error("generator built against CV_ABI_VERSION %d, this library is %d", generator->abi, CV_ABI_VERSION);
        return nullptr;
    }
    cv_world_desc d = *desc;
    d.surface = generator->column_surface;   // same signature as cv_surface_fn: called directly, no upcall
    d.user = generator->user;
    return cv_world_create(&d);
}

void cv_world_destroy(cv_world* w) {
    if (w == nullptr) return;
    w->cubes.destroy_all();
    w->columns.destroy_all();
    delete w;
}

int32_t cv_block_table_set(cv_world* w, const uint16_t* flags, int32_t count) {
    if (w == nullptr || count < 0 || count > 65536 || (count > 0 && flags == nullptr)) {
        return fail(CV_ERR_ARG, "bad block table");
    }
    auto t = std::make_unique<BlockTable>();
    std::memset(t->flags, 0, sizeof t->flags);
    if (count > 0) std::memcpy(t->flags, flags, static_cast<size_t>(count) * 2);
    {
        std::lock_guard<std::mutex> g(w->table_mutex);
        w->table.store(t.get());
        w->tables.push_back(std::move(t));
    }
    std::vector<Int3> keys;
    w->cubes.for_each([&](int64_t x, int64_t y, int64_t z, Cube*) { keys.push_back(Int3{x, y, z}); });
    for (const Int3& k : keys) reapply_cube(w, k.x, k.y, k.z);
    mark_present(w, keys);
    return CV_OK;
}

// ---- cubes -------------------------------------------------------------------------------------------

int32_t cv_cube_create(cv_world* w, int64_t cx, int64_t cy, int64_t cz, uint16_t fill_id) {
    CubeInstall in;
    in.fill_id = fill_id;
    return install_prepared_ids(w, cx, cy, cz, in, false);
}

int32_t cv_cube_remove(cv_world* w, int64_t cx, int64_t cy, int64_t cz) {
    if (w == nullptr || !cube_ok(cx, cy, cz)) return fail(CV_ERR_ARG, "bad arguments");
    const uint64_t h = hash3(cx, cy, cz);
    auto& s = w->cubes.shard(h);
    s.lock.lock();
    Cube* c = w->cubes.erase(s, cx, cy, cz, h);
    s.lock.unlock();
    if (c == nullptr) return fail(CV_ERR_MISSING, "cube not loaded");
    c->lock.lock();   // drains readers/writers that found it before the erase
    Column* col = column_exclusive(w, cx, cz);
    if (col != nullptr) {
        col->remove_loaded(cy);
        col->lock.unlock();
    }
    c->lock.unlock();
    delete c;
    {
        std::lock_guard<std::mutex> g(w->dirty_mutex);
        w->dirty.erase(Int3{cx, cy, cz});
    }
    mark_neighbourhood(w, cx, cy, cz);
    return CV_OK;
}

int32_t cv_cube_exists(const cv_world* w, int64_t cx, int64_t cy, int64_t cz) {
    if (w == nullptr || !cube_ok(cx, cy, cz)) return 0;
    return cube_present(w, cx, cy, cz) ? 1 : 0;
}

int64_t cv_cube_count(const cv_world* w) {
    return w == nullptr ? 0 : w->cubes.size.load(std::memory_order_relaxed);
}

int64_t cv_cube_list(const cv_world* cw, int64_t* out, int64_t cap) {
    if (cw == nullptr || cap < 0 || (cap > 0 && out == nullptr)) return fail(CV_ERR_ARG, "bad arguments");
    auto* w = const_cast<cv_world*>(cw);
    std::vector<Int3> keys;
    w->cubes.for_each([&](int64_t x, int64_t y, int64_t z, Cube*) { keys.push_back(Int3{x, y, z}); });
    const auto n = static_cast<int64_t>(keys.size());
    if (n > cap) return CV_NEED(n);
    for (size_t i = 0; i < keys.size(); ++i) {
        out[3 * i] = keys[i].x;
        out[3 * i + 1] = keys[i].y;
        out[3 * i + 2] = keys[i].z;
    }
    return n;
}

int32_t cv_cube_write_ids(cv_world* w, int64_t cx, int64_t cy, int64_t cz, const uint16_t* ids) {
    if (ids == nullptr) return fail(CV_ERR_ARG, "null ids");
    CubeInstall in;
    in.ids = ids;
    return install_prepared_ids(w, cx, cy, cz, in, true);
}

int32_t cv_cube_read_ids(const cv_world* w, int64_t cx, int64_t cy, int64_t cz, uint16_t* out) {
    if (w == nullptr || out == nullptr || !cube_ok(cx, cy, cz)) return fail(CV_ERR_ARG, "bad arguments");
    Cube* c = cube_shared(w, cx, cy, cz);
    if (c == nullptr) return fail(CV_ERR_MISSING, "cube not loaded");
    read_channel(c->sec, c->uniform_id, out);
    c->lock.unlock_shared();
    return CV_OK;
}

int64_t cv_cube_encode(const cv_world* cw, int64_t cx, int64_t cy, int64_t cz, uint32_t parts, uint8_t* dst,
                       int64_t cap) {
    if (cw == nullptr || cap < 0 || (cap > 0 && dst == nullptr) || !cube_ok(cx, cy, cz)) {
        return fail(CV_ERR_ARG, "bad arguments");
    }
    auto* w = const_cast<cv_world*>(cw);
    Cube* c = cube_shared(w, cx, cy, cz);
    if (c == nullptr) return fail(CV_ERR_MISSING, "cube not loaded");
    bool exclusive = false;
    if ((c->flags & kCompactHint) != 0) {
        c->lock.unlock_shared();
        c = cube_exclusive(w, cx, cy, cz);
        if (c == nullptr) return fail(CV_ERR_MISSING, "cube not loaded");
        exclusive = true;
        compact_cube(w, c);
    }
    bool bad = false;
    const uint32_t written = resolve_parts(w, c, parts, &bad);
    thread_local std::vector<uint8_t> out;
    out.clear();
    if (!bad) {
        put_u8(out, kEncodingVersion);
        put_u32(out, written);
        if ((written & CV_PART_BLOCKS) != 0) encode_channel(out, c->sec, c->uniform_id);
        for (int l = 0; l < CV_MAX_LAYERS; ++l) {
            if ((written & CV_PART_LAYER(l)) != 0) encode_channel(out, c->layer(l), 0);
        }
        if ((written & CV_PART_BLOB) != 0) {
            put_u32(out, static_cast<uint32_t>(c->extra->blob.size()));
            out.insert(out.end(), c->extra->blob.begin(), c->extra->blob.end());
        }
    }
    if (exclusive) {
        c->lock.unlock();
    } else {
        c->lock.unlock_shared();
    }
    if (bad) return fail(CV_ERR_ARG, "unknown part bits");
    const auto n = static_cast<int64_t>(out.size());
    if (n > cap) return CV_NEED(n);
    std::memcpy(dst, out.data(), out.size());
    return n;
}

int32_t cv_cube_decode(cv_world* w, int64_t cx, int64_t cy, int64_t cz, const uint8_t* src, int64_t len) {
    PreparedCube p;
    const int32_t rc = prepare_cube_encoded(w, cx, cy, cz, src, len, p);
    if (rc != CV_OK) return rc;
    return install_prepared(w, cx, cy, cz, std::move(p), true);
}

int32_t cv_cube_uniform(const cv_world* w, int64_t cx, int64_t cy, int64_t cz, uint16_t* out_id) {
    if (w == nullptr || !cube_ok(cx, cy, cz)) return fail(CV_ERR_ARG, "bad arguments");
    Cube* c = cube_shared(w, cx, cy, cz);
    if (c == nullptr) return fail(CV_ERR_MISSING, "cube not loaded");
    uint16_t id = c->uniform_id;
    const bool uniform = c->sec == nullptr || sections_uniform(c->sec, &id);
    c->lock.unlock_shared();
    if (uniform && out_id != nullptr) *out_id = id;
    return uniform ? 1 : 0;
}

// ---- blocks ------------------------------------------------------------------------------------------

int32_t cv_get(const cv_world* w, int64_t x, int64_t y, int64_t z) {
    if (w == nullptr) return fail(CV_ERR_ARG, "null world");
    if (!w->y_in(y)) return w->air;
    Cube* c = cube_shared(w, x >> CV_CUBE_SHIFT, y >> CV_CUBE_SHIFT, z >> CV_CUBE_SHIFT);
    if (c == nullptr) {
        int64_t top = 0;
        if (column_answer_at(w, x >> CV_CUBE_SHIFT, z >> CV_CUBE_SHIFT, kTop, static_cast<uint32_t>(lc(z) * 32 + lc(x)), &top) &&
            y >= top) {
            return w->air;   // known air
        }
        return fail(CV_ERR_MISSING, "cube not loaded");
    }
    const uint16_t id = c->get(lc(x), lc(y), lc(z));
    c->lock.unlock_shared();
    return id;
}

int32_t cv_set(cv_world* w, int64_t x, int64_t y, int64_t z, uint16_t id) {
    if (w == nullptr) return fail(CV_ERR_ARG, "null world");
    if (!w->y_in(y)) return fail(CV_ERR_ARG, "write outside the vertical bounds");
    const int64_t cx = x >> CV_CUBE_SHIFT, cy = y >> CV_CUBE_SHIFT, cz = z >> CV_CUBE_SHIFT;
    const int lx = lc(x), ly = lc(y), lz = lc(z);
    Cube* c = cube_exclusive(w, cx, cy, cz);
    if (c == nullptr) return fail(CV_ERR_MISSING, "cube not loaded");
    const uint16_t old = c->get(lx, ly, lz);
    if (old == id) {
        c->lock.unlock();
        return old;
    }
    if (c->sec == nullptr) c->sec = new_sections(c->uniform_id);
    const uint32_t s = section_of(lx, ly, lz);
    c->sec[s].set(section_local(lx, ly, lz), id);
    maybe_hint(c, c->sec[s]);
    const BlockTable* t = w->table.load();
    const uint32_t r = static_cast<uint32_t>(ly * 32 + lz);
    const uint32_t bit = 1u << lx;
    auto update = [&](uint32_t*& rows, uint8_t full_bit, bool on) {
        const bool full = (c->flags & full_bit) != 0;
        if (rows == nullptr) {
            if (on == full) return;
            rows = new uint32_t[1024];
            std::fill(rows, rows + 1024, full ? ~0u : 0u);
            c->flags = static_cast<uint8_t>(c->flags & ~full_bit);
        }
        rows[r] = on ? (rows[r] | bit) : (rows[r] & ~bit);
    };
    const uint16_t fo = t->flags[old], fn = t->flags[id];
    update(c->occ, kOccFull, id != w->air);
    update(c->opq, kOpqFull, (fn & CV_BLOCK_OPAQUE) != 0);
    // Back to one block?
    uint16_t u = 0;
    if (c->sec[s].is_uniform_storage() && sections_all_uniform_storage(c->sec, &u)) {
        delete_sections(c->sec);
        c->sec = nullptr;
        c->uniform_id = u;
        delete[] c->occ;
        delete[] c->opq;
        c->occ = nullptr;
        c->opq = nullptr;
        const uint16_t f = t->flags[u];
        c->flags = static_cast<uint8_t>((u != w->air ? kOccFull : 0) | ((f & CV_BLOCK_OPAQUE) != 0 ? kOpqFull : 0));
    }
    c->revision = bump(w);

    const bool sky_changed = ((fo ^ fn) & CV_BLOCK_SKY_OPAQUE) != 0;
    const bool top_changed = (old == w->air) != (id == w->air);
    SurfaceDelta delta;
    if (sky_changed || top_changed) {
        Column* col = column_exclusive(w, cx, cz);
        if (col != nullptr) {
            const auto i = static_cast<uint32_t>(lz * 32 + lx);
            const SurfaceCache* cb = col->cb.get();
            for (int ch = 0; ch < kChannels; ++ch) {
                if ((ch == kSky && !sky_changed) || (ch == kTop && !top_changed)) continue;
                const bool now_on = ch == kSky ? (fn & CV_BLOCK_SKY_OPAQUE) != 0 : id != w->air;
                const int64_t cur = col->ch[ch].leaf_get(cy, i);
                int64_t next = cur;
                if (now_on) {
                    if (cur == kNone || y > cur) next = y;
                } else if (y == cur) {
                    next = kNone;
                    for (int yy = ly - 1; yy >= 0; --yy) {
                        const bool on = ch == kSky
                            ? (t->flags[c->get(lx, yy, lz)] & CV_BLOCK_SKY_OPAQUE) != 0
                            : ((c->occ_row(static_cast<uint32_t>(yy * 32 + lz)) >> lx) & 1u) != 0;
                        if (on) {
                            next = cy * CV_CUBE_EDGE + yy;
                            break;
                        }
                    }
                }
                if (next == cur) continue;
                col->ch[ch].leaf_set_value(cy, i, next);
                const int64_t before = col->answer[ch].get(i);
                const int64_t after = column_value(w, col, cb, ch, i);
                col->set_answer(ch, i, after);
                if (ch == kSky) delta.add(lx, lz, before, after);
            }
            col->lock.unlock();
        }
    }
    c->lock.unlock();
    mark_cell(w, cx, cy, cz, lx, ly, lz);
    if (!delta.empty()) apply_surface_delta(w, cx, cz, delta);
    return old;
}

int64_t cv_read_box(const cv_world* w, int64_t x0, int64_t y0, int64_t z0, int32_t sx, int32_t sy, int32_t sz,
                    uint16_t* out, uint16_t missing_id) {
    if (w == nullptr || out == nullptr || sx <= 0 || sy <= 0 || sz <= 0) return fail(CV_ERR_ARG, "bad arguments");
    if (x0 > INT64_MAX - sx || y0 > INT64_MAX - sy || z0 > INT64_MAX - sz) return fail(CV_ERR_ARG, "box overflows");
    const int64_t x1 = x0 + sx, y1 = y0 + sy, z1 = z0 + sz;   // exclusive
    const ptrdiff_t stride_z = sx, stride_y = static_cast<ptrdiff_t>(sx) * sz;
    int64_t missing = 0;
    for (int64_t cy = y0 >> CV_CUBE_SHIFT; cy <= (y1 - 1) >> CV_CUBE_SHIFT; ++cy) {
        const int64_t ya = std::max(y0, cy * CV_CUBE_EDGE), yb = std::min(y1, cy * CV_CUBE_EDGE + CV_CUBE_EDGE);
        for (int64_t cz = z0 >> CV_CUBE_SHIFT; cz <= (z1 - 1) >> CV_CUBE_SHIFT; ++cz) {
            const int64_t za = std::max(z0, cz * CV_CUBE_EDGE), zb = std::min(z1, cz * CV_CUBE_EDGE + CV_CUBE_EDGE);
            for (int64_t cx = x0 >> CV_CUBE_SHIFT; cx <= (x1 - 1) >> CV_CUBE_SHIFT; ++cx) {
                const int64_t xa = std::max(x0, cx * CV_CUBE_EDGE), xb = std::min(x1, cx * CV_CUBE_EDGE + CV_CUBE_EDGE);
                const auto nx = static_cast<size_t>(xb - xa);
                // Rows outside the vertical bounds: air, never missing.
                bool any_in = false;
                for (int64_t y = ya; y < yb; ++y) any_in = any_in || w->y_in(y);
                Cube* c = any_in ? cube_shared(w, cx, cy, cz) : nullptr;
                // A missing cube: cells at or above the column's TOP are known air.
                int64_t tops[kColumnCells];
                const bool known = any_in && c == nullptr && column_answers(w, cx, cz, kTop, tops);
                for (int64_t y = ya; y < yb; ++y) {
                    const bool in = w->y_in(y);
                    for (int64_t z = za; z < zb; ++z) {
                        uint16_t* d = out + (y - y0) * stride_y + (z - z0) * stride_z + (xa - x0);
                        if (!in) {
                            std::fill(d, d + nx, w->air);
                        } else if (c == nullptr) {
                            const int64_t* trow = tops + lc(z) * 32;
                            for (size_t k = 0; k < nx; ++k) {
                                if (known && y >= trow[lc(xa) + static_cast<int>(k)]) {
                                    d[k] = w->air;
                                } else {
                                    d[k] = missing_id;
                                    ++missing;
                                }
                            }
                        } else {
                            uint16_t row[32];
                            channel_row(c->sec, c->uniform_id, lc(y), lc(z), row);
                            std::memcpy(d, row + lc(xa), nx * 2);
                        }
                    }
                }
                if (c != nullptr) c->lock.unlock_shared();
            }
        }
    }
    return missing;
}

// ---- host layers -------------------------------------------------------------------------------------

int32_t cv_layer_define(cv_world* w, int32_t layer, uint32_t flags) {
    if (w == nullptr || !layer_ok(layer)) return fail(CV_ERR_ARG, "bad layer");
    if ((flags & ~(CV_LAYER_SAVED | CV_LAYER_NETWORK | CV_LAYER_MESHED)) != 0) return fail(CV_ERR_ARG, "bad layer flags");
    w->layer_flags[layer].store(flags);
    return CV_OK;
}

int32_t cv_layer_get(const cv_world* w, int32_t layer, int64_t x, int64_t y, int64_t z) {
    if (w == nullptr || !layer_ok(layer)) return fail(CV_ERR_ARG, "bad layer");
    if (!w->y_in(y)) return 0;
    Cube* c = cube_shared(w, x >> CV_CUBE_SHIFT, y >> CV_CUBE_SHIFT, z >> CV_CUBE_SHIFT);
    if (c == nullptr) return fail(CV_ERR_MISSING, "cube not loaded");
    const uint16_t v = channel_get(c->layer(layer), 0, lc(x), lc(y), lc(z));
    c->lock.unlock_shared();
    return v;
}

int32_t cv_layer_set(cv_world* w, int32_t layer, int64_t x, int64_t y, int64_t z, uint16_t value) {
    if (w == nullptr || !layer_ok(layer)) return fail(CV_ERR_ARG, "bad layer");
    if (!w->y_in(y)) return fail(CV_ERR_ARG, "write outside the vertical bounds");
    const int64_t cx = x >> CV_CUBE_SHIFT, cy = y >> CV_CUBE_SHIFT, cz = z >> CV_CUBE_SHIFT;
    const int lx = lc(x), ly = lc(y), lz = lc(z);
    Cube* c = cube_exclusive(w, cx, cy, cz);
    if (c == nullptr) return fail(CV_ERR_MISSING, "cube not loaded");
    const uint16_t old = channel_get(c->layer(layer), 0, lx, ly, lz);
    if (old == value) {
        c->lock.unlock();
        return old;
    }
    if (c->extra == nullptr) c->extra = new CubeExtra();
    Section*& sec = c->extra->layer[layer];
    if (sec == nullptr) sec = new_sections(0);
    const uint32_t s = section_of(lx, ly, lz);
    sec[s].set(section_local(lx, ly, lz), value);
    maybe_hint(c, sec[s]);
    uint16_t u = 0;
    if (value == 0 && sec[s].is_uniform_storage() && sections_all_uniform_storage(sec, &u) && u == 0) {
        delete_sections(sec);
        sec = nullptr;
        if (c->extra->empty()) {
            delete c->extra;
            c->extra = nullptr;
        }
    }
    c->revision = bump(w);
    c->lock.unlock();
    if ((w->layer_flags[layer].load(std::memory_order_relaxed) & CV_LAYER_MESHED) != 0) {
        mark_cell(w, cx, cy, cz, lx, ly, lz);
    }
    return old;
}

int32_t cv_layer_read_cube(const cv_world* w, int32_t layer, int64_t cx, int64_t cy, int64_t cz, uint16_t* out) {
    if (w == nullptr || !layer_ok(layer) || out == nullptr || !cube_ok(cx, cy, cz)) return fail(CV_ERR_ARG, "bad arguments");
    Cube* c = cube_shared(w, cx, cy, cz);
    if (c == nullptr) return fail(CV_ERR_MISSING, "cube not loaded");
    read_channel(c->layer(layer), 0, out);
    c->lock.unlock_shared();
    return CV_OK;
}

int32_t cv_layer_write_cube(cv_world* w, int32_t layer, int64_t cx, int64_t cy, int64_t cz, const uint16_t* values) {
    if (w == nullptr || !layer_ok(layer) || values == nullptr || !cube_ok(cx, cy, cz)) {
        return fail(CV_ERR_ARG, "bad arguments");
    }
    if (!w->cube_in(cy)) return fail(CV_ERR_ARG, "cube lies outside the vertical bounds");
    uint16_t u = 0;
    Section* fresh = build_channel(w, cy, values, 0, 0, &u);
    if (fresh == nullptr && u != 0) fresh = new_sections(u);
    Cube* c = cube_exclusive(w, cx, cy, cz);
    if (c == nullptr) {
        delete_sections(fresh);
        return fail(CV_ERR_MISSING, "cube not loaded");
    }
    if (c->extra == nullptr) c->extra = new CubeExtra();
    std::swap(c->extra->layer[layer], fresh);
    if (c->extra->empty()) {
        delete c->extra;
        c->extra = nullptr;
    }
    c->revision = bump(w);
    c->lock.unlock();
    delete_sections(fresh);
    if ((w->layer_flags[layer].load(std::memory_order_relaxed) & CV_LAYER_MESHED) != 0) {
        mark_neighbourhood(w, cx, cy, cz);
    }
    return CV_OK;
}

int64_t cv_layer_scan(const cv_world* w, int32_t layer, int64_t cx, int64_t cy, int64_t cz, uint16_t* out_index,
                      uint16_t* out_value, int64_t cap) {
    if (w == nullptr || !layer_ok(layer) || cap < 0 || !cube_ok(cx, cy, cz) ||
        (cap > 0 && (out_index == nullptr || out_value == nullptr))) {
        return fail(CV_ERR_ARG, "bad arguments");
    }
    Cube* c = cube_shared(w, cx, cy, cz);
    if (c == nullptr) return fail(CV_ERR_MISSING, "cube not loaded");
    const Section* sec = c->layer(layer);
    int64_t n = 0;
    if (sec != nullptr) {
        for (int s = 0; s < 8; ++s) n += sec[s].count_not(0);
    }
    if (n > cap) {
        c->lock.unlock_shared();
        return CV_NEED(n);
    }
    int64_t k = 0;
    if (sec != nullptr) {
        uint16_t values[kSectionCells];
        for (int s = 0; s < 8; ++s) {
            if (sec[s].count_not(0) == 0) continue;
            sec[s].read(values);
            const int sx = s & 1, sz = (s >> 1) & 1, sy = s >> 2;
            for (uint32_t i = 0; i < kSectionCells; ++i) {
                if (values[i] == 0) continue;
                const int slx = static_cast<int>(i & 15), slz = static_cast<int>((i >> 4) & 15),
                          sly = static_cast<int>(i >> 8);
                out_index[k] = static_cast<uint16_t>(CV_CELL_INDEX(sx * 16 + slx, sy * 16 + sly, sz * 16 + slz));
                out_value[k] = values[i];
                ++k;
            }
        }
    }
    c->lock.unlock_shared();
    return k;
}

// ---- blob ---------------------------------------------------------------------------------------------

int32_t cv_cube_set_blob(cv_world* w, int64_t cx, int64_t cy, int64_t cz, const uint8_t* src, int64_t len) {
    if (w == nullptr || len < 0 || len > INT32_MAX || (len > 0 && src == nullptr) || !cube_ok(cx, cy, cz)) {
        return fail(CV_ERR_ARG, "bad arguments");
    }
    Cube* c = cube_exclusive(w, cx, cy, cz);
    if (c == nullptr) return fail(CV_ERR_MISSING, "cube not loaded");
    if (len == 0) {
        if (c->extra != nullptr) {
            c->extra->blob.clear();
            c->extra->blob.shrink_to_fit();
            if (c->extra->empty()) {
                delete c->extra;
                c->extra = nullptr;
            }
        }
    } else {
        if (c->extra == nullptr) c->extra = new CubeExtra();
        c->extra->blob.assign(src, src + len);
    }
    c->revision = bump(w);
    c->lock.unlock();
    return CV_OK;
}

int64_t cv_cube_get_blob(const cv_world* w, int64_t cx, int64_t cy, int64_t cz, uint8_t* dst, int64_t cap) {
    if (w == nullptr || cap < 0 || (cap > 0 && dst == nullptr) || !cube_ok(cx, cy, cz)) {
        return fail(CV_ERR_ARG, "bad arguments");
    }
    Cube* c = cube_shared(w, cx, cy, cz);
    if (c == nullptr) return fail(CV_ERR_MISSING, "cube not loaded");
    const int64_t n = c->extra != nullptr ? static_cast<int64_t>(c->extra->blob.size()) : 0;
    if (n > cap) {
        c->lock.unlock_shared();
        return CV_NEED(n);
    }
    if (n > 0) std::memcpy(dst, c->extra->blob.data(), static_cast<size_t>(n));
    c->lock.unlock_shared();
    return n;
}

// ---- revisions and dirtiness ---------------------------------------------------------------------------

int64_t cv_world_revision(const cv_world* w) {
    return w == nullptr ? 0 : w->revision.load(std::memory_order_acquire);
}

int64_t cv_cube_revision(const cv_world* w, int64_t cx, int64_t cy, int64_t cz) {
    if (w == nullptr || !cube_ok(cx, cy, cz)) return 0;
    Cube* c = cube_shared(w, cx, cy, cz);
    if (c == nullptr) return 0;
    const int64_t r = c->revision;
    c->lock.unlock_shared();
    return r;
}

int64_t cv_drain_dirty(cv_world* w, int64_t* out, int64_t cap) {
    if (w == nullptr || cap < 0 || (cap > 0 && out == nullptr)) return fail(CV_ERR_ARG, "bad arguments");
    std::lock_guard<std::mutex> g(w->dirty_mutex);
    const auto n = static_cast<int64_t>(w->dirty.size());
    if (n > cap) return CV_NEED(n);
    int64_t i = 0;
    for (const Int3& k : w->dirty) {
        out[3 * i] = k.x;
        out[3 * i + 1] = k.y;
        out[3 * i + 2] = k.z;
        ++i;
    }
    w->dirty.clear();
    return n;
}

} // extern "C"
