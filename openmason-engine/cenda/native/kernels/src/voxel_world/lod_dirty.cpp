// Octree LOD runtime: dirty propagation (notes, held columns, column checks), the revision watch + its scanner
// thread, and the worker threads (column checks, rebuilds, prepares). Shared runtime structures: lod_runtime.hpp.
#include "lod_runtime.hpp"

#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <utility>
#include <vector>

namespace cenda::voxel::lod {

namespace {

// ---- dirty propagation (m held) --------------------------------------------------------------------------------
void mark_dirty_locked(cv_lod* L, const NodeKey& k) {
    if (k.level < 1 || k.level > L->max_level) return;
    if (k.level > L->active_level.load(std::memory_order_relaxed)) {
        std::lock_guard<std::mutex> g(L->def_m);
        L->deferred.insert(k);
        return;
    }
    if (L->dirty[k.level].insert(k).second) {
        L->dirty_q[k.level].push_back(k);
        L->work_cv.notify_one();
    }
}

// Change notices for the owner (bounded: a host that stops ticking cannot grow them without limit).
void push_changed_locked(cv_lod* L, const NodeKey& k) {
    wake_planner_locked(L);
    if (L->changed_overflow) return;
    if (L->changed.size() >= (size_t{1} << 20)) {
        L->changed.clear();
        L->changed_overflow = true;
        return;
    }
    L->changed.push_back(k);
}

void note_cube_locked(cv_lod* L, int64_t cx, int64_t cy, int64_t cz) {
    const NodeKey k0{0, cx, cy, cz};
    const auto col = std::make_pair(cx, cz);
    {
        std::lock_guard<std::mutex> g(L->col_m);
        ++L->col_ver[col];
        L->columns.erase(col);
    }
    L->cache0.erase(k0);
    mark_dirty_locked(L, parent_of(k0));
    push_changed_locked(L, k0);
    // The column's facts (generated runs, TOP) may have moved: its synthesized cubes are re-derived by a check.
    if (L->col_check.insert(col).second) {
        L->col_q.push_back(col);
        L->work_cv.notify_one();
    }
}

constexpr int64_t kHoldQuietUs = 300000;
constexpr int64_t kHoldMaxUs = 2000000;

} // namespace

// A note from the host / the watch: noted now when its column is READY, else held (m held).
void note_or_hold_locked(cv_lod* L, int64_t cx, int64_t cy, int64_t cz, int64_t now) {
    const auto col = std::make_pair(cx, cz);
    if (L->ready_cols.count(col) != 0) {
        note_cube_locked(L, cx, cy, cz);
        return;
    }
    auto& h = L->held[col];
    if (h.cys.empty()) h.first_us = now;
    h.last_us = now;
    if (std::find(h.cys.begin(), h.cys.end(), cy) == h.cys.end()) {
        h.cys.push_back(cy);
        ++L->held_cubes;
    }
}

void release_held_locked(cv_lod* L, const std::pair<int64_t, int64_t>& col) {
    auto it = L->held.find(col);
    if (it == L->held.end()) return;
    for (int64_t cy : it->second.cys) note_cube_locked(L, col.first, cy, col.second);
    L->held_cubes -= static_cast<int64_t>(it->second.cys.size());
    L->held.erase(it);
}

// Columns quiet long enough (or held too long); all of them when `all`.
void release_settled_locked(cv_lod* L, int64_t now, bool all) {
    std::vector<std::pair<int64_t, int64_t>> go;
    for (const auto& [col, h] : L->held) {
        if (all || now - h.last_us >= kHoldQuietUs || now - h.first_us >= kHoldMaxUs) go.push_back(col);
    }
    for (const auto& col : go) release_held_locked(L, col);
}

// A column's facts changed: every cube of it in the range may synthesize differently (m NOT held).
void column_changed(cv_lod* L, int64_t cx, int64_t cz) {
    const int64_t lo = floor_div64(L->min_y, kEdge), hi = floor_div64(L->max_y - 1, kEdge);
    {
        std::lock_guard<std::mutex> g(L->col_m);
        ++L->col_ver[std::make_pair(cx, cz)];
    }
    for (int64_t cy = lo; cy <= hi; ++cy) L->cache0.erase(NodeKey{0, cx, cy, cz});
    std::lock_guard<std::mutex> g(L->m);
    for (int64_t cy = lo; cy <= hi; ++cy) {
        const NodeKey k0{0, cx, cy, cz};
        if ((cy & 1) == 0 || cy == lo) mark_dirty_locked(L, parent_of(k0));
        push_changed_locked(L, k0);
    }
}

namespace {

bool dispatchable(cv_lod* L, const NodeKey& k) {
    if (L->running.count(k) != 0) return false;
    if (k.level <= 1) return true;
    for (int i = 0; i < 8; ++i) {
        const NodeKey c = child_of(k, i);
        if (L->dirty[c.level].count(c) != 0 || L->running.count(c) != 0) return false;
    }
    return true;
}

bool pick_rebuild(cv_lod* L, NodeKey* out) {
    for (int lv = 1; lv <= L->max_level; ++lv) {
        auto& q = L->dirty_q[lv];
        int scanned = 0;
        for (auto it = q.begin(); it != q.end() && scanned < 64; ++scanned) {
            if (dispatchable(L, *it)) {
                *out = *it;
                L->dirty[lv].erase(*it);
                q.erase(it);
                return true;
            }
            ++it;
        }
    }
    return false;
}

} // namespace

bool any_dirty(const cv_lod* L) {
    if (!L->col_q.empty() || L->col_running > 0) return true;
    for (int lv = 1; lv < kLevels; ++lv) {
        if (!L->dirty[lv].empty()) return true;
    }
    return false;
}

// One pass over the watch: cubes whose revision moved since the LOD read them are noted; unloaded ones leave
// the watch (their saved record is what was read, or UNLOAD_PENDING noted the change). Returns the count noted.
int64_t scan_once(cv_lod* L) {
    std::vector<Int3> moved;
    {
        std::lock_guard<std::mutex> g(L->watch_m);
        for (auto it = L->watch.begin(); it != L->watch.end();) {
            const int64_t rev = cv_cube_revision(L->world, it->first.x, it->first.y, it->first.z);
            if (rev == 0) {
                it = L->watch.erase(it);
                continue;
            }
            if (rev != it->second) {
                moved.push_back(it->first);
                it->second = rev;
            }
            ++it;
        }
    }
    if (!moved.empty()) {
        const int64_t now = now_us();
        std::lock_guard<std::mutex> g(L->m);
        for (const Int3& c : moved) note_or_hold_locked(L, c.x, c.y, c.z, now);
    }
    return static_cast<int64_t>(moved.size());
}

void scanner_main(cv_lod* L) {
    std::unique_lock<std::mutex> lk(L->m);
    while (!L->stopping) {
        L->scan_cv.wait_for(lk, std::chrono::milliseconds(100));
        if (L->stopping) break;
        release_settled_locked(L, now_us(), false);
        lk.unlock();
        scan_once(L);
        lk.lock();
    }
}

// True when the cube is loaded and still at the revision the LOD read (a note would change nothing).
bool watch_unchanged(cv_lod* L, int64_t cx, int64_t cy, int64_t cz) {
    const int64_t rev = cv_cube_revision(L->world, cx, cy, cz);
    if (rev == 0) return false;
    std::lock_guard<std::mutex> g(L->watch_m);
    auto it = L->watch.find(Int3{cx, cy, cz});
    return it != L->watch.end() && it->second == rev;
}

// Watch state of a cube: 0 not watched, 1 watched and unchanged since the LOD read it (a note would change
// nothing), 2 watched and changed (the entry moves to the current revision: the caller notes it).
int watch_check(cv_lod* L, int64_t cx, int64_t cy, int64_t cz) {
    const int64_t rev = cv_cube_revision(L->world, cx, cy, cz);
    std::lock_guard<std::mutex> g(L->watch_m);
    auto it = L->watch.find(Int3{cx, cy, cz});
    if (it == L->watch.end()) return 0;
    if (it->second == rev && rev != 0) return 1;
    if (rev != 0) it->second = rev;
    return 2;
}

void worker_main(cv_lod* L) {
    std::unique_lock<std::mutex> lk(L->m);
    for (;;) {
        if (L->stopping) return;
        NodeKey rk;
        bool have_rebuild = false;
        bool have_prep = false;
        PrepJob pj;
        if (!L->col_q.empty()) {   // column checks first: cheap, and they create the rebuild work
            const auto col = L->col_q.front();
            L->col_q.pop_front();
            L->col_check.erase(col);
            ++L->col_running;
            lk.unlock();
            try {
                column_info(L, col.first, col.second, true);
            } catch (...) {
                L->errors_total.fetch_add(1);
            }
            lk.lock();
            --L->col_running;
            L->idle_cv.notify_all();
            continue;
        }
        // Fairness: every other pick prefers rebuilds (keeps edits flowing up while far meshes are requested).
        const bool rebuild_first = (L->picks++ & 1u) == 0;
        if (rebuild_first) have_rebuild = pick_rebuild(L, &rk);
        if (!have_rebuild && !L->prep_q.empty()) {
            pj = L->prep_q.front();
            L->prep_q.pop_front();
            have_prep = true;
        }
        if (!have_rebuild && !have_prep && !rebuild_first) have_rebuild = pick_rebuild(L, &rk);
        if (have_rebuild) {
            L->running.insert(rk);
            lk.unlock();
            int changed = 0;
            try {
                changed = rebuild(L, rk);
            } catch (...) {
                L->errors_total.fetch_add(1);
            }
            lk.lock();
            L->running.erase(rk);
            if (changed > 0) {
                if (rk.level < L->max_level) mark_dirty_locked(L, parent_of(rk));
                push_changed_locked(L, rk);
            }
            L->idle_cv.notify_all();
            L->work_cv.notify_all();   // parents may have become dispatchable
            continue;
        }
        if (have_prep) {
            ++L->prep_running;
            lk.unlock();
            PrepResult r;
            r.serial = pj.serial;
            try {
                prepare(L, pj.key, r);
            } catch (...) {
                L->errors_total.fetch_add(1);
                r.key = pj.key;
            }
            lk.lock();
            --L->prep_running;
            L->done.push_back(std::move(r));
            wake_planner_locked(L);
            L->idle_cv.notify_all();
            continue;
        }
        L->idle_cv.notify_all();
        L->work_cv.wait(lk);
    }
}

// The active level moved (cv_lod_set_view): deferred nodes it now covers become worker work again.
void set_active_level(cv_lod* L, int32_t level) {
    level = std::max(1, std::min(level, L->max_level));
    std::lock_guard<std::mutex> g(L->m);
    const int32_t old = L->active_level.exchange(level);
    if (level <= old) return;
    std::vector<NodeKey> promote;
    {
        std::lock_guard<std::mutex> d(L->def_m);
        for (auto it = L->deferred.begin(); it != L->deferred.end();) {
            if (it->level <= level) {
                promote.push_back(*it);
                it = L->deferred.erase(it);
            } else {
                ++it;
            }
        }
    }
    std::sort(promote.begin(), promote.end());
    for (const NodeKey& k : promote) mark_dirty_locked(L, k);
}

} // namespace cenda::voxel::lod
