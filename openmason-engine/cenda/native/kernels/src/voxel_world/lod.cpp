// Octree LOD runtime (cv_lod_*, OpenMason #122 / Phase 5): workers rebuilding dirty nodes bottom-up and
// preparing requested ones (data + mesh), the per-level node stores, and the owner-side selection + handover
// that turns viewers, band policy and column readiness into SHOW / UPDATE / HIDE / FALLBACK events. Contract:
// voxel_world.h "LOD"; private structures: lod_internal.hpp (node data / meshes), lod_runtime.hpp (cv_lod).
// Split by concern (lod_sources / lod_dirty / lod_persist / lod_select / lod_owner .cpp); this file keeps the
// lifecycle and the cv_lod_* C ABI (cv_lod_tick lives with the owner, lod_owner.cpp).
#include "lod_runtime.hpp"
#include "cv_error.hpp"

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <utility>
#include <vector>

// Layouts the Java binding (CendaVoxelWorld LOD_*_BYTES, CubeLod, LodMesh, LodEvents, LodStats) reads.
static_assert(sizeof(cv_lod_desc) == 80 && offsetof(cv_lod_desc, max_level) == 48 && offsetof(cv_lod_desc, fill_id) == 60,
              "cv_lod_desc layout");
static_assert(sizeof(cv_lod_policy) == 32 && offsetof(cv_lod_policy, standby_from) == 16, "cv_lod_policy layout");
static_assert(sizeof(cv_lod_viewer) == 24 && sizeof(cv_lod_budget) == 32, "cv_lod_viewer / cv_lod_budget layout");
static_assert(sizeof(cv_lod_event) == 40 && offsetof(cv_lod_event, x) == 16, "cv_lod_event layout");
static_assert(sizeof(cv_lod_mesh) == 128 && offsetof(cv_lod_mesh, origin) == 32 && offsetof(cv_lod_mesh, quads) == 56 &&
                  offsetof(cv_lod_mesh, quad_count) == 64 && offsetof(cv_lod_mesh, water) == 72 &&
                  offsetof(cv_lod_mesh, water_count) == 80 && offsetof(cv_lod_mesh, aabb_min) == 88 &&
                  offsetof(cv_lod_mesh, digest) == 112 && offsetof(cv_lod_mesh, flags) == 120,
              "cv_lod_mesh layout");
static_assert(sizeof(cv_lod_stats) == 192 && offsetof(cv_lod_stats, corrupt_total) == 144, "cv_lod_stats layout");

namespace cenda::voxel::lod {

int32_t fail_arg(const char* message) {
    cv_set_error("%s", message);
    return CV_ERR_ARG;
}

namespace {

int32_t check_level(const cv_lod* L, int32_t level) {
    if (level < 0 || level > L->max_level) return fail_arg("level out of range");
    return CV_OK;
}

} // namespace
} // namespace cenda::voxel::lod

using namespace cenda::voxel::lod;
using cenda::Int3;

using namespace cenda::voxel;

extern "C" {

cv_lod* cv_lod_create(cv_world* world, const cv_lod_desc* desc) {
    if (world == nullptr || desc == nullptr) {
        fail_arg("cv_lod_create: null world or desc");
        return nullptr;
    }
    if (desc->max_level < 1 || desc->max_level > CV_LOD_MAX_LEVEL) {
        fail_arg("cv_lod_create: max_level must be 1..16");
        return nullptr;
    }
    int64_t lo = desc->min_y, hi = desc->max_y;
    if (lo == 0 && hi == 0) {
        lo = world_min_y(world);
        hi = world_max_y(world);
        if (lo == CV_UNBOUNDED_MIN || hi == CV_UNBOUNDED_MAX) {
            fail_arg("cv_lod_create: an unbounded world needs desc.min_y / max_y");
            return nullptr;
        }
    }
    if (hi <= lo || hi - lo > 8192 || lo < -(int64_t{1} << 40) || hi > (int64_t{1} << 40)) {
        fail_arg("cv_lod_create: y range must be non-empty, at most 8192 blocks, within +-2^40");
        return nullptr;
    }
    static std::atomic<uint64_t> next_serial{1};
    auto* L = new cv_lod();
    L->serial = next_serial.fetch_add(1);
    L->world = world;
    L->cube_store = desc->cube_store;
    L->min_y = lo;
    L->max_y = hi;
    L->air = world_air(world);
    L->fill = desc->fill_id;
    L->max_level = desc->max_level;
    L->content_version = desc->content_version;
    {
        const size_t cap = desc->cache_bytes > 0 ? static_cast<size_t>(desc->cache_bytes) : size_t{256} << 20;
        L->cache.set_cap(cap - cap / 4);
        L->cache0.set_cap(cap / 4);
    }
    if (desc->store_dir != nullptr && desc->store_dir[0] != 0) {
        L->dir = desc->store_dir;
        L->persist = true;
        const int32_t r = open_meta(L, (desc->flags & CV_LOD_RESET_ON_MISMATCH) != 0);
        if (r != CV_OK) {
            delete L;
            return nullptr;
        }
    }
    L->active_level.store(L->max_level);
    load_deferred(L);
    const int n = desc->worker_threads > 0 ? desc->worker_threads : 2;
    for (int i = 0; i < n; ++i) L->workers.emplace_back(worker_main, L);
    L->scanner = std::thread(scanner_main, L);
    L->planner = std::thread(planner_main, L);
    return L;
}

int32_t cv_lod_destroy(cv_lod* lod) {
    if (lod == nullptr) return CV_OK;
    {
        std::lock_guard<std::mutex> g(lod->m);
        lod->stopping = true;
        lod->prep_q.clear();
    }
    lod->work_cv.notify_all();
    lod->scan_cv.notify_all();
    lod->plan_cv.notify_all();
    for (std::thread& t : lod->workers) t.join();
    if (lod->scanner.joinable()) lod->scanner.join();
    if (lod->planner.joinable()) lod->planner.join();
    save_deferred(lod);
    int32_t result = lod->first_store_error.load();
    for (cv_store*& s : lod->stores) {
        if (s != nullptr) {
            const int32_t r = cv_store_close(s);
            if (r != CV_OK && result == CV_OK) result = r;
            s = nullptr;
        }
    }
    delete lod;
    return result;
}

int32_t cv_lod_block_classes(cv_lod* lod, const uint8_t* classes, int32_t count) {
    if (lod == nullptr || count < 0 || count > 65536 || (count > 0 && classes == nullptr)) {
        return fail_arg("cv_lod_block_classes: bad arguments");
    }
    auto next = std::make_shared<std::vector<uint8_t>>(65536, 0);
    for (int32_t i = 0; i < count; ++i) {
        if (classes[i] > CV_LOD_CLASS_FLUID) return fail_arg("cv_lod_block_classes: bad class");
        (*next)[static_cast<size_t>(i)] = classes[i];
    }
    {
        std::lock_guard<std::mutex> g(lod->class_m);
        lod->overrides = std::move(next);
    }
    lod->overrides_version.fetch_add(1, std::memory_order_acq_rel);
    return CV_OK;
}

int32_t cv_lod_note_cubes(cv_lod* lod, const int64_t* xyz, int64_t count) {
    if (lod == nullptr || count < 0 || (count > 0 && xyz == nullptr)) return fail_arg("cv_lod_note_cubes: bad arguments");
    // A loaded cube still at the revision the LOD read is unchanged (revisions never go backwards): free. Hosts
    // that note every re-mesh (neighbour padding) cost nothing then.
    std::vector<Int3> notes;
    notes.reserve(static_cast<size_t>(count));
    for (int64_t i = 0; i < count; ++i) {
        const int64_t cx = xyz[3 * i], cy = xyz[3 * i + 1], cz = xyz[3 * i + 2];
        if (!watch_unchanged(lod, cx, cy, cz)) notes.push_back(Int3{cx, cy, cz});
    }
    if (notes.empty()) return CV_OK;
    const int64_t now = now_us();
    std::lock_guard<std::mutex> g(lod->m);
    for (const Int3& c : notes) note_or_hold_locked(lod, c.x, c.y, c.z, now);
    return CV_OK;
}

int32_t cv_lod_note_events(cv_lod* lod, const cv_event* events, int32_t count) {
    if (lod == nullptr || count < 0 || (count > 0 && events == nullptr)) {
        return fail_arg("cv_lod_note_events: bad arguments");
    }
    // Cube events. INSTALLED / CHANGED: the content is new. REMESHED: only when the cube moved past the revision
    // the LOD read (else it is neighbour padding). UNLOAD_PENDING: only a watched cube that changed (its saved
    // record is then newer than what the LOD used). The watch entry stays: a pending unload may be cancelled
    // (the cube wanted again); the scanner drops entries of cubes that are really gone.
    std::vector<Int3> notes;
    for (int32_t i = 0; i < count; ++i) {
        const cv_event& e = events[i];
#if defined(CV_EV_CHANGED)
        const bool changed = e.type == CV_EV_CHANGED;
#else
        const bool changed = false;
#endif
        if (e.type == CV_EV_INSTALLED || changed) {
            notes.push_back(Int3{e.cx, e.cy, e.cz});
        } else if (e.type == CV_EV_REMESHED) {
            if (watch_check(lod, e.cx, e.cy, e.cz) != 1) notes.push_back(Int3{e.cx, e.cy, e.cz});
        } else if (e.type == CV_EV_UNLOAD_PENDING) {
            if (watch_check(lod, e.cx, e.cy, e.cz) == 2) notes.push_back(Int3{e.cx, e.cy, e.cz});
        }
    }
    if (!notes.empty()) {
        const int64_t now = now_us();
        std::lock_guard<std::mutex> g(lod->m);
        for (const Int3& c : notes) note_or_hold_locked(lod, c.x, c.y, c.z, now);
    }
    for (int32_t i = 0; i < count; ++i) {
        const cv_event& e = events[i];
        if (e.type == CV_EV_COLUMN_READY) set_ready(lod, e.cx, e.cz, true);
        else if (e.type == CV_EV_COLUMN_UNREADY) set_ready(lod, e.cx, e.cz, false);
    }
    return CV_OK;
}

int32_t cv_lod_set_column_ready(cv_lod* lod, int64_t cx, int64_t cz, int32_t ready) {
    if (lod == nullptr) return fail_arg("cv_lod_set_column_ready: null lod");
    set_ready(lod, cx, cz, ready != 0);
    return CV_OK;
}

int32_t cv_lod_set_view(cv_lod* lod, const cv_lod_viewer* viewers, int32_t count, const cv_lod_policy* policy) {
    if (lod == nullptr || policy == nullptr || count < 0 || (count > 0 && viewers == nullptr)) {
        return fail_arg("cv_lod_set_view: bad arguments");
    }
    if (policy->blocks_per_cell < 46 || policy->max_level < 0 || policy->max_level > lod->max_level ||
        policy->radius < 0 || policy->radius > (1 << 30) || policy->incomplete_depth < 0 ||
        policy->incomplete_depth > 8 || policy->incomplete_min_level < -1 || policy->incomplete_min_level > CV_LOD_MAX_LEVEL) {
        return fail_arg("cv_lod_set_view: bad policy (blocks_per_cell >= 46, max_level <= desc.max_level, "
                        "incomplete_depth 0..8, incomplete_min_level -1..16)");
    }
    for (int32_t i = 0; i < count; ++i) {
        if (std::llabs(viewers[i].x) > (int64_t{1} << 58) || std::llabs(viewers[i].z) > (int64_t{1} << 58)) {
            return fail_arg("cv_lod_set_view: viewer out of range");
        }
    }
    if (count > 0) set_active_level(lod, policy->max_level);
    {
        std::lock_guard<std::mutex> g(lod->view_m);
        lod->viewers.assign(viewers, viewers + count);
        lod->policy = *policy;
        ++lod->view_version;
    }
    std::lock_guard<std::mutex> g(lod->m);
    wake_planner_locked(lod);
    return CV_OK;
}

const cv_lod_mesh* cv_lod_mesh_get(cv_lod* lod, int32_t slot) {
    if (lod == nullptr || slot < 0 || static_cast<size_t>(slot) >= lod->slot_views.size()) return nullptr;
    return &lod->slot_views[static_cast<size_t>(slot)];
}

int32_t cv_lod_read_node(cv_lod* lod, int32_t level, int64_t nx, int64_t ny, int64_t nz, uint16_t* out_ids,
                         uint8_t* out_cells, uint64_t* out_digest) {
    if (lod == nullptr) return fail_arg("cv_lod_read_node: null lod");
    if (const int32_t r = check_level(lod, level); r != CV_OK) return r;
    NodePtr d = get_data(lod, NodeKey{level, nx, ny, nz}, true);
    for (int i = 0; i < kCells; ++i) {
        if (out_ids != nullptr) out_ids[i] = d->id(i);
        if (out_cells != nullptr) out_cells[i] = d->cell(i);
    }
    if (out_digest != nullptr) *out_digest = d->digest;
    return d->complete ? 1 : 0;
}

int32_t cv_lod_build_mesh(cv_lod* lod, int32_t level, int64_t nx, int64_t ny, int64_t nz, cv_lod_mesh* out) {
    if (lod == nullptr || out == nullptr) return fail_arg("cv_lod_build_mesh: bad arguments");
    if (const int32_t r = check_level(lod, level); r != CV_OK) return r;
    thread_local MeshPtr held;
    PrepResult r;
    prepare(lod, NodeKey{level, nx, ny, nz}, r);
    if (!r.mesh) {
        auto empty = std::make_shared<MeshBuild>();
        empty->key = NodeKey{level, nx, ny, nz};
        empty->digest = r.digest;
        r.mesh = std::move(empty);
    }
    held = r.mesh;
    slot_view(*held, *out);
    return CV_OK;
}

int32_t cv_lod_wait_idle(cv_lod* lod) {
    if (lod == nullptr) return fail_arg("cv_lod_wait_idle: null lod");
    for (int pass = 0; pass < 64; ++pass) {
        const bool noted = scan_once(lod) > 0;   // writes nobody noted count as work too
        std::unique_lock<std::mutex> lk(lod->m);
        release_settled_locked(lod, now_us(), true);
        lod->work_cv.notify_all();
        lod->idle_cv.wait(lk, [&] {
            return lod->stopping ||
                   (!any_dirty(lod) && lod->running.empty() && lod->prep_q.empty() && lod->prep_running == 0 &&
                    !lod->plan_busy && !lod->plan_wake && lod->done.empty() && lod->changed.empty());
        });
        if (!noted) break;
    }
    return CV_OK;
}

int32_t cv_lod_flush(cv_lod* lod) {
    if (lod == nullptr) return fail_arg("cv_lod_flush: null lod");
    save_deferred(lod);
    std::lock_guard<std::mutex> g(lod->store_m);
    int32_t result = CV_OK;
    for (cv_store* s : lod->stores) {
        if (s == nullptr) continue;
        const int32_t r = cv_store_flush(s);
        if (r != CV_OK && result == CV_OK) result = r;
    }
    return result;
}

void cv_lod_stats_get(cv_lod* lod, cv_lod_stats* out) {
    if (lod == nullptr || out == nullptr) return;
    std::lock_guard<std::mutex> g(lod->stats_m);
    *out = lod->o_stats;
}

} // extern "C"
