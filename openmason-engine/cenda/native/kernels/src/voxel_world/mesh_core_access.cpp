// The mesher's only window into core-private structures (cv_impl.hpp): the block-table generation and the
// per-cube visibility cache (Cube::vis_cache, a Phase 1b addition). Everything else in the mesher goes
// through cv_internal.hpp.
#include "mesh_internal.hpp"
#include "cv_impl.hpp"

#include <mutex>

namespace cenda::voxel {

const uint16_t* table_snapshot(const cv_world* cw, uint32_t* generation) {
    auto* w = const_cast<cv_world*>(cw);
    std::lock_guard<std::mutex> g(w->table_mutex);
    const BlockTable* t = w->table.load();
    uint32_t gen = 0;
    for (size_t i = w->tables.size(); i-- > 0;) {
        if (w->tables[i].get() == t) {
            gen = static_cast<uint32_t>(i);
            break;
        }
    }
    *generation = gen;
    return t->flags;
}

namespace {

uint16_t compute_locked(const Cube* c, const uint16_t* flags) {
    if (c->sec == nullptr) {
        return (flags[c->uniform_id] & CV_BLOCK_VIS_OPAQUE) != 0 ? uint16_t{0} : kAllPairs;
    }
    uint32_t closed[1024];
    uint16_t row[32];
    bool any_closed = false, any_open = false;
    for (int ly = 0; ly < 32; ++ly) {
        for (int lz = 0; lz < 32; ++lz) {
            const auto r = static_cast<uint32_t>(ly * 32 + lz);
            uint32_t bits = 0;
            if (c->occ_row(r) != 0) {
                channel_row(c->sec, c->uniform_id, ly, lz, row);
                for (int x = 0; x < 32; ++x) {
                    if ((flags[row[x]] & CV_BLOCK_VIS_OPAQUE) != 0) bits |= 1u << x;
                }
            }
            closed[r] = bits;
            any_closed = any_closed || bits != 0;
            any_open = any_open || bits != ~0u;
        }
    }
    if (!any_closed) return kAllPairs;
    if (!any_open) return 0;
    return visibility_from_rows(closed);
}

} // namespace

int vis_probe(const cv_world* w, int64_t cx, int64_t cy, int64_t cz, const uint16_t* flags, uint32_t generation,
              uint16_t* bits) {
    Cube* c = cube_shared(w, cx, cy, cz);
    if (c == nullptr) return 0;
    const uint64_t want = vis_word(c->revision, generation, 0);
    const uint64_t have = c->vis_cache.load(std::memory_order_relaxed);
    if ((have & ~uint64_t{kAllPairs}) == want) {
        *bits = static_cast<uint16_t>(have & kAllPairs);
    } else {
        *bits = compute_locked(c, flags);
        c->vis_cache.store(vis_word(c->revision, generation, *bits), std::memory_order_relaxed);
    }
    c->lock.unlock_shared();
    return 1;
}

bool vis_cached(const cv_world* w, int64_t cx, int64_t cy, int64_t cz, int64_t revision, uint32_t generation,
                uint16_t* bits) {
    Cube* c = cube_shared(w, cx, cy, cz);
    if (c == nullptr) return false;
    bool hit = false;
    if (c->revision == revision) {
        const uint64_t have = c->vis_cache.load(std::memory_order_relaxed);
        if ((have & ~uint64_t{kAllPairs}) == vis_word(revision, generation, 0)) {
            *bits = static_cast<uint16_t>(have & kAllPairs);
            hit = true;
        }
    }
    c->lock.unlock_shared();
    return hit;
}

void vis_store(const cv_world* w, int64_t cx, int64_t cy, int64_t cz, int64_t revision, uint32_t generation,
               uint16_t bits) {
    Cube* c = cube_shared(w, cx, cy, cz);
    if (c == nullptr) return;
    if (c->revision == revision) c->vis_cache.store(vis_word(revision, generation, bits), std::memory_order_relaxed);
    c->lock.unlock_shared();
}

} // namespace cenda::voxel
