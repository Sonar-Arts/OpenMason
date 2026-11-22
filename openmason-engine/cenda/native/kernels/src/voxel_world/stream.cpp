// The streamer (cv_stream_*, OpenMason #121 + the save path of #120): lifecycle, stats and the C ABI. Rules:
// voxel_world.h "STREAMER RULES"; private structures and thread ownership: stream_impl.hpp; the other streamer
// files (worker pool, planner thread, owner tick, unload + saves, REMOTE): stream_internal.hpp; the wanted-set
// planner: stream_plan.cpp.
#include "stream_impl.hpp"
#include "stream_internal.hpp"
#include "cv_error.hpp"
#include "cv_internal.hpp"

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <new>
#include <thread>
#include <utility>
#include <vector>

using namespace cenda::voxel;
using namespace cenda::voxel::streaming;
using cenda::Int3;

// The Java binding (engine-voxel CubeStream, StreamMesh, StreamEvents, StreamStats) reads these layouts.
static_assert(sizeof(cv_event) == 32 && offsetof(cv_event, cx) == 8);
static_assert(sizeof(cv_center) == 40 && offsetof(cv_center, radius_h) == 24);
static_assert(sizeof(cv_budget) == 32 && offsetof(cv_budget, max_mesh_bytes) == 8 && offsetof(cv_budget, max_unloads) == 16);
static_assert(sizeof(cv_stream_desc) == 56 && offsetof(cv_stream_desc, worker_threads) == 16);
static_assert(sizeof(cv_stream_stats) == 248 && offsetof(cv_stream_stats, level_changes_total) == 27 * 8);
static_assert(sizeof(cv_generator) == 72 && offsetof(cv_generator, user) == 8 && offsetof(cv_generator, column_surface) == 16);
static_assert(sizeof(cv_gpu_mesh) == 144 && offsetof(cv_gpu_mesh, quad16) == 24 && offsetof(cv_gpu_mesh, quad_count) == 48 &&
              offsetof(cv_gpu_mesh, water) == 64 && offsetof(cv_gpu_mesh, water_count) == 72 &&
              offsetof(cv_gpu_mesh, stamp_vertices) == 80 && offsetof(cv_gpu_mesh, stamp_vertex_count) == 88 &&
              offsetof(cv_gpu_mesh, stamp_indices) == 96 && offsetof(cv_gpu_mesh, stamp_index_count) == 104 &&
              offsetof(cv_gpu_mesh, aabb_min) == 108 && offsetof(cv_gpu_mesh, aabb_max) == 120 &&
              offsetof(cv_gpu_mesh, visibility) == 132 && offsetof(cv_gpu_mesh, flags) == 134 &&
              offsetof(cv_gpu_mesh, revision) == 136);

namespace cenda::voxel::streaming {

namespace {

// Counters that need a scan: computed when asked (cv_stream_stats_get), never inside a tick.
void compute_stats(cv_stream* s, cv_stream_stats& st) {
    st = cv_stream_stats{};
    int64_t loaded = 0, unload_pending = 0;
    for (const auto& [c, cs] : s->cubes) {
        (void)c;
        if (installed(cs)) ++loaded;
        if (cs.phase == kUnloadPending || cs.phase == kUnloadSaving) ++unload_pending;
    }
    int64_t ready = 0;
    for (const auto& [k, col] : s->columns) {
        (void)k;
        if (col.ready) ++ready;
    }
    int64_t ready_bytes = 0;
    for (const auto& m : s->ready_meshes) ready_bytes += m->bytes;
    int64_t queued = 0;
    if (s->active_list) {
        for (size_t i = s->list_cursor; i < s->active_list->size(); ++i) {
            const CubeState* cs = find_cube(s, (*s->active_list)[i].c);
            if (cs != nullptr && cs->phase == kAbsent && cs->level >= (s->remote ? kLevelWanted : kLevelSupport)) ++queued;
        }
    }
    st.cubes_loaded = loaded;
    st.cubes_generating = s->gen_in_flight;
    st.cubes_meshing = s->mesh_in_flight + static_cast<int64_t>(s->mesh_q.size());
    st.cubes_queued = queued;
    st.generated_total = s->generated_total.load();
    st.loaded_total = s->loaded_total.load();
    st.saved_total = s->saved_total.load();
    st.meshed_total = s->meshed_total;
    st.mesh_bytes_pending = ready_bytes;
    st.cubes_wanted = s->plan_wanted;
    st.cubes_needed = s->plan_needed;
    st.populating = s->pop_in_flight + static_cast<int64_t>(s->pop_cand.size());
    st.populated_total = s->populated_total;
    st.installs_pending = static_cast<int64_t>(s->install_q.size());
    st.meshes_pending = static_cast<int64_t>(s->ready_meshes.size());
    st.unloads_pending = unload_pending + static_cast<int64_t>(s->unload_q.size());
    st.unloaded_total = s->unloaded_total;
    st.columns_tracked = static_cast<int64_t>(s->columns.size());
    st.columns_ready = ready;
    st.walk_cubes = s->plan_walk;
    {
        std::lock_guard<std::mutex> g(s->plan_mutex);
        st.plans_total = s->plans_total;
        st.plan_pending = (s->planning || !s->batches.empty() || s->tops_dirty ||
                           s->centers_version != s->planned_version || s->applying ||
                           s->tops_outstanding.load() > 0)
                              ? 1
                              : 0;
        st.plan_us_last = s->plan_us_last;
    }
    st.tick_us_last = s->tick_us_last;
    st.tick_us_max = s->tick_us_max;
    st.stale_meshes_total = s->stale_total;
    st.errors_total = s->errors_total.load();
    st.level_changes_total = s->level_changes;
}

} // namespace

} // namespace cenda::voxel::streaming

// ---- C ABI ---------------------------------------------------------------------------------------------------

extern "C" {

cv_stream* cv_stream_create(cv_world* world, const cv_stream_desc* desc) {
    if (world == nullptr || desc == nullptr) {
        cv_set_error("cv_stream_create: null world or desc");
        return nullptr;
    }
    const cv_generator* gen = desc->generator;
    if (gen != nullptr) {
        if (gen->abi != CV_ABI_VERSION) {
            cv_set_error("cv_stream_create: generator built against ABI %d (expected %d)", gen->abi, CV_ABI_VERSION);
            return nullptr;
        }
        if (gen->generate_cube == nullptr || (gen->populate != nullptr && gen->populate_reach != 1)) {
            cv_set_error("cv_stream_create: generator needs generate_cube and populate_reach 1");
            return nullptr;
        }
    }
    cv_stream* s = new (std::nothrow) cv_stream();
    if (s == nullptr) {
        cv_set_error("cv_stream_create: out of memory");
        return nullptr;
    }
    s->world = world;
    s->gen = gen;
    s->remote = gen == nullptr;
    s->store = s->remote ? nullptr : desc->store;
    s->no_mesh = (desc->mesh_flags & CV_STREAM_NO_MESH) != 0;
    s->mesh_flags = desc->mesh_flags & ~CV_STREAM_NO_MESH;
    const int64_t min_y = world_min_y(world), max_y = world_max_y(world);
    s->cmin = min_y >> CV_CUBE_SHIFT;
    s->cmax = (max_y == INT64_MIN ? INT64_MIN : max_y - 1) >> CV_CUBE_SHIFT;
    int32_t n = desc->worker_threads;
    if (n <= 0) n = std::max(1, static_cast<int32_t>(std::thread::hardware_concurrency()) - 2);
    s->worker_count = n;
    try {
        for (int32_t i = 0; i < n; ++i) s->workers.emplace_back(worker_main, s);
        s->planner_thread = std::thread(planner_main, s);
    } catch (...) {
        {
            std::lock_guard<std::mutex> g(s->q_mutex);
            s->stopping = true;
        }
        s->q_cv.notify_all();
        for (auto& t : s->workers) t.join();
        delete s;
        cv_set_error("cv_stream_create: cannot start threads");
        return nullptr;
    }
    return s;
}

int32_t cv_stream_destroy(cv_stream* s) {
    if (s == nullptr) return CV_ERR_ARG;
    {
        std::lock_guard<std::mutex> g(s->plan_mutex);
        s->plan_stop = true;
    }
    s->plan_cv.notify_all();
    if (s->planner_thread.joinable()) s->planner_thread.join();
    int32_t rc = CV_OK;
    {
        std::lock_guard<std::mutex> owner(s->owner_mutex);
        // Drop work nobody needs any more; saves and populations run to completion.
        {
            std::lock_guard<std::mutex> g(s->q_mutex);
            s->gen_in_flight -= static_cast<int64_t>(s->queue[static_cast<size_t>(JobKind::Generate)].size());
            s->mesh_in_flight -= static_cast<int64_t>(s->queue[static_cast<size_t>(JobKind::Mesh)].size());
            s->colload_in_flight -= static_cast<int64_t>(s->queue[static_cast<size_t>(JobKind::LoadColumn)].size());
            s->queue[static_cast<size_t>(JobKind::Generate)].clear();
            s->queue[static_cast<size_t>(JobKind::Mesh)].clear();
            s->queue[static_cast<size_t>(JobKind::Top)].clear();   // the planner is gone
            s->queue[static_cast<size_t>(JobKind::LoadColumn)].clear();
        }
        wait_for_jobs(s, [&] {
            return s->gen_in_flight == 0 && s->mesh_in_flight == 0 && s->pop_in_flight == 0 && s->save_in_flight == 0 &&
                   s->colload_in_flight == 0 && s->colsave_in_flight == 0;
        });
        rc = save_all_locked(s);
        release_handed(s);
    }
    {
        std::lock_guard<std::mutex> g(s->q_mutex);
        s->stopping = true;
    }
    s->q_cv.notify_all();
    for (auto& t : s->workers) t.join();
    delete s;
    return rc;
}

int32_t cv_stream_set_centers(cv_stream* s, const cv_center* centers, int32_t count) {
    if (s == nullptr || count < 0 || (count > 0 && centers == nullptr)) {
        cv_set_error("cv_stream_set_centers: bad arguments");
        return CV_ERR_ARG;
    }
    {
        std::lock_guard<std::mutex> g(s->plan_mutex);
        s->centers.assign(centers, centers + count);
        ++s->centers_version;
    }
    s->plan_cv.notify_all();
    return CV_OK;
}

int32_t cv_stream_tick(cv_stream* s, const cv_budget* budget, const cv_event** out_events) {
    if (out_events != nullptr) *out_events = nullptr;
    if (s == nullptr) {
        cv_set_error("cv_stream_tick: null stream");
        return CV_ERR_ARG;
    }
    std::lock_guard<std::mutex> owner(s->owner_mutex);
    const int32_t n = tick_locked(s, budget);
    if (out_events != nullptr) *out_events = s->events.empty() ? nullptr : s->events.data();
    return n;
}

const cv_gpu_mesh* cv_stream_mesh(cv_stream* s, int32_t mesh) {
    if (s == nullptr || mesh < 0 || static_cast<size_t>(mesh) >= s->handed.size() || !s->handed[static_cast<size_t>(mesh)]) {
        cv_set_error("cv_stream_mesh: no such mesh this tick");
        return nullptr;
    }
    return &s->handed[static_cast<size_t>(mesh)]->view;
}

void cv_stream_release_mesh(cv_stream* s, int32_t mesh) {
    if (s == nullptr || mesh < 0 || static_cast<size_t>(mesh) >= s->handed.size()) return;
    give_mesh(s, s->handed[static_cast<size_t>(mesh)]);
}

int32_t cv_stream_save_all(cv_stream* s) {
    if (s == nullptr) {
        cv_set_error("cv_stream_save_all: null stream");
        return CV_ERR_ARG;
    }
    std::lock_guard<std::mutex> owner(s->owner_mutex);
    const int32_t rc = save_all_locked(s);
    if (rc < 0) cv_set_error("cv_stream_save_all: store error %d", rc);
    return rc;
}

int32_t cv_stream_set_detail_radius(cv_stream* s, int32_t radius_blocks) {
    if (s == nullptr) {
        cv_set_error("cv_stream_set_detail_radius: null stream");
        return CV_ERR_ARG;
    }
    std::lock_guard<std::mutex> owner(s->owner_mutex);
    const int32_t r = radius_blocks < 0 ? -1 : radius_blocks;
    if (r != s->detail_radius) {
        s->detail_radius = r;
        s->detail_dirty = true;   // step_detail re-evaluates every detail cube on the next tick
    }
    return CV_OK;
}

int64_t cv_stream_remesh_all(cv_stream* s) {
    if (s == nullptr) {
        cv_set_error("cv_stream_remesh_all: null stream");
        return CV_ERR_ARG;
    }
    std::lock_guard<std::mutex> owner(s->owner_mutex);
    if (s->no_mesh) return 0;
    // Meshes in flight now carry the old epoch: dropped on completion and re-queued (kRemesh).
    ++s->mesh_epoch;
    // Built but not handed out: from the old tables too.
    for (auto& m : s->ready_meshes) {
        CubeState* st = find_cube(s, m->c);
        if (st != nullptr) st->flags |= kRemesh;   // re-queued below with the others
        give_mesh(s, m);
    }
    s->ready_meshes.clear();
    int64_t n = 0;
    for (auto& [c, st] : s->cubes) {
        const bool had_ready = (st.flags & kRemesh) != 0 && (st.flags & kMeshInFlight) == 0;
        if (had_ready) st.flags &= static_cast<uint16_t>(~kRemesh);
        if (!installed(st)) continue;
        if ((st.flags & kMeshInFlight) != 0) {
            st.flags |= kRemesh;
            ++n;
        } else if ((st.flags & kMeshQueued) != 0) {
            ++n;   // queued: dispatched with the new epoch
        } else if ((st.flags & kMeshed) != 0 || had_ready) {
            enqueue_mesh(s, c, st);
            ++n;
        } else {
            consider_mesh(s, c, st);   // never meshed: its first-mesh rules
            if ((st.flags & kMeshQueued) != 0) ++n;
        }
    }
    return n;
}

void cv_stream_stats_get(const cv_stream* s, cv_stream_stats* out) {
    if (out == nullptr) return;
    if (s == nullptr) {
        std::memset(out, 0, sizeof *out);
        return;
    }
    // Fresh when the owner is idle (the usual case: the overlay asks between ticks); otherwise the last
    // snapshot (never blocks behind a tick or a save).
    auto* m = const_cast<cv_stream*>(s);
    std::unique_lock<std::mutex> owner(m->owner_mutex, std::try_to_lock);
    std::lock_guard<std::mutex> g(m->stats_mutex);
    if (owner.owns_lock()) compute_stats(m, m->stats);
    *out = m->stats;
}

} // extern "C"
