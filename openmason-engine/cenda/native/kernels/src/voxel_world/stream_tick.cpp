// The owner tick (cv_stream_tick; stream_impl.hpp "THREADS" OWNER): worker completions, installs, dirty cubes
// and CHANGED reports, the in-flight limits, population and mesh dispatch, the mesh hand-out, column readiness
// and the load dispatch.
#include "stream_impl.hpp"
#include "stream_internal.hpp"
#include "cv_internal.hpp"
#include "mesh_gpu.hpp"
#include "stream_plan.hpp"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <deque>
#include <memory>
#include <mutex>
#include <utility>
#include <vector>

namespace cenda::voxel::streaming {

namespace {

constexpr int64_t kTopsSignalMs = 100;      // re-plan after installs at most this often

} // namespace

// ---- completions ------------------------------------------------------------------------------------------

void process_completions(cv_stream* s) {
    std::vector<std::unique_ptr<Result>> got;
    {
        std::lock_guard<std::mutex> g(s->done_mutex);
        got.swap(s->done);
    }
    for (auto& r : got) {
        const Int3 c = r->c;
        switch (r->kind) {
        case JobKind::Generate: {
            --s->gen_in_flight;
            CubeState* st = find_cube(s, c);
            if (st == nullptr || st->phase != kLoading) {
                recycle(s, r);
                break;
            }
            if (r->gen == GenKind::Error || r->gen == GenKind::IoError) {
                s->errors_total.fetch_add(1, std::memory_order_relaxed);
                st->phase = kAbsent;
                ++st->retries;
                st->retry_tick = s->tick + std::min<int64_t>(400, int64_t{5} << std::min<uint32_t>(st->retries, 6));
                s->tops_changed = true;
                if (st->level == kLevelNone) erase_cube(s, c);
                recycle(s, r);
                break;
            }
            st->phase = kInstallReady;
            s->install_q.push_back(std::move(r));
            break;
        }
        case JobKind::LoadColumn: {
            --s->colload_in_flight;
            ColumnState* col = find_col(s, c.x, c.z);
            if (col != nullptr && col->rec == 1) apply_column_record(s, c, *col, *r);
            break;
        }
        case JobKind::Populate: {
            --s->pop_in_flight;
            if (r->status < 0) s->errors_total.fetch_add(1, std::memory_order_relaxed);
            for (const Int3& o : r->origins) {
                ColumnState& col = s->columns[Int2{o.x, o.z}];
                const auto pos = std::lower_bound(col.populated.begin(), col.populated.end(), o.y);
                if (pos == col.populated.end() || *pos != o.y) col.populated.insert(pos, o.y);
                col.dirty = true;
                ++s->populated_total;
            }
            s->tops_changed = true;
            // Release the hold (the union of the chain's cubes), then completeness: every origin is recorded.
            std::vector<Int3> held;
            held_union(r->origins, held);
            for (const Int3& x : held) {
                CubeState* st = find_cube(s, x);
                if (st == nullptr) continue;
                st->flags &= static_cast<uint16_t>(~kHeld);
                s->pop_changed.push_back(x);
            }
            for (const Int3& x : held) {
                CubeState* st = find_cube(s, x);
                if (st != nullptr) check_complete(s, x, *st);
            }
            break;
        }
        case JobKind::Mesh: {
            --s->mesh_in_flight;
            CubeState* st = find_cube(s, c);
            if (st == nullptr) {
                recycle(s, r);
                break;
            }
            st->flags &= static_cast<uint16_t>(~kMeshInFlight);
            if (r->mesh && r->mesh->epoch != s->mesh_epoch) {   // built from tables cv_stream_remesh_all replaced
                st->flags |= kRemesh;
                recycle(s, r);
            } else if (!installed(*st) || r->status < 0) {
                if (r->status < 0 && r->status != CV_ERR_MISSING) s->errors_total.fetch_add(1, std::memory_order_relaxed);
                recycle(s, r);
            } else {
                r->mesh->prio = prio_of(s, c);
                s->ready_meshes.push_back(std::move(r->mesh));
                recycle(s, r);
            }
            if ((st->flags & kRemesh) != 0) {
                st->flags &= static_cast<uint16_t>(~kRemesh);
                if (installed(*st) && (st->flags & kMeshQueued) == 0) enqueue_mesh(s, c, *st);
            }
            break;
        }
        case JobKind::SaveCube: {
            --s->save_in_flight;
            CubeState* st = find_cube(s, c);
            if (r->status < 0) {
                s->errors_total.fetch_add(1, std::memory_order_relaxed);
                if (s->saving_all && s->save_error == 0) s->save_error = r->status;
            } else {
                s->saved_total.fetch_add(1, std::memory_order_relaxed);
                ColumnState* col = find_col(s, c.x, c.z);
                if (col != nullptr) col->dirty = true;   // tracker leaves follow the cube content
            }
            if (st == nullptr) break;
            st->flags &= static_cast<uint16_t>(~kSaveInFlight);
            if (r->status >= 0) st->saved_rev = std::max(st->saved_rev, r->rev);
            if (st->phase == kUnloadSaving) s->resume_unloads.push_back(c);
            break;
        }
        case JobKind::SaveColumn: {
            --s->colsave_in_flight;
            ColumnState* col = find_col(s, c.x, c.z);
            if (r->status < 0) {
                s->errors_total.fetch_add(1, std::memory_order_relaxed);
                if (s->saving_all && s->save_error == 0) s->save_error = r->status;
            }
            if (col != nullptr) {
                col->saving = false;
                if (r->status < 0) col->dirty = true;
                touch(s, c.x, c.z);
            }
            break;
        }
        case JobKind::Top: break;   // answered to the planner, never here
        }
        if (r) recycle(s, r);
    }
}

// ---- tick steps ---------------------------------------------------------------------------------------------

namespace {

void step_installs(cv_stream* s, int64_t max_installs) {
    const uint8_t min_level = s->remote ? kLevelWanted : kLevelSupport;
    // More finished than the budget installs: nearest first (results arrive in worker completion order).
    if (static_cast<int64_t>(s->install_q.size()) > max_installs) {
        std::vector<std::pair<uint64_t, size_t>> order;
        order.reserve(s->install_q.size());
        for (size_t i = 0; i < s->install_q.size(); ++i) order.emplace_back(prio_of(s, s->install_q[i]->c), i);
        std::stable_sort(order.begin(), order.end(),
                         [](const auto& a, const auto& b) { return a.first < b.first; });
        std::deque<std::unique_ptr<Result>> sorted;
        for (const auto& [p, i] : order) {
            (void)p;
            sorted.push_back(std::move(s->install_q[i]));
        }
        s->install_q.swap(sorted);
    }
    const int64_t t_start = now_us();
    int64_t n = 0;
    while (!s->install_q.empty() && n < max_installs) {
        std::unique_ptr<Result> r = std::move(s->install_q.front());
        s->install_q.pop_front();
        const Int3 c = r->c;
        CubeState* st = find_cube(s, c);
        if (st == nullptr || st->phase != kInstallReady) {
            recycle(s, r);
            continue;
        }
        if (st->level < min_level) {
            st->phase = kAbsent;
            if (st->level == kLevelNone) erase_cube(s, c);
            recycle(s, r);
            continue;
        }
        if (cv_cube_exists(s->world, c.x, c.y, c.z) == 1) {   // the host installed it meanwhile
            st->phase = kAbsent;
            adopt(s, c);
            recycle(s, r);
            continue;
        }
        // Prepared on the worker: the owner only swaps it in (cv_internal.hpp install_prepared).
        const int32_t rc = install_prepared(s->world, c.x, c.y, c.z, std::move(r->prepared), false);
        if (rc == CV_ERR_EXISTS) {   // the host installed it meanwhile
            st->phase = kAbsent;
            adopt(s, c);
            recycle(s, r);
            continue;
        }
        if (rc == CV_OK) {
            if (r->gen == GenKind::Store) {
                st->saved_rev = cv_cube_revision(s->world, c.x, c.y, c.z);
                s->loaded_total.fetch_add(1, std::memory_order_relaxed);
            } else {
                st->saved_rev = 0;   // generated: saved at its first unload / save_all
                ColumnState* col = find_col(s, c.x, c.z);
                if (col != nullptr) col->dirty = true;
            }
        }
        recycle(s, r);
        if (rc != CV_OK) {
            s->errors_total.fetch_add(1, std::memory_order_relaxed);
            st->phase = kAbsent;
            continue;
        }
        on_installed(s, c, *st, false);
        ++n;
    }
    if (n > 0) {   // owner cost per install (bounds the generation limit, see IN-FLIGHT LIMITS)
        const double per = static_cast<double>(now_us() - t_start) / static_cast<double>(n);
        s->install_avg_us = s->install_avg_us <= 0.0 ? per : 0.8 * s->install_avg_us + 0.2 * per;
    }
}

void step_dirty(cv_stream* s) {
    if (!s->pop_changed.empty()) {
        std::vector<Int3> list;
        list.swap(s->pop_changed);
        for (const Int3& c : list) {
            CubeState* st = find_cube(s, c);
            if (st != nullptr && (st->flags & kHeld) == 0) note_changed(s, c, *st);
        }
        list.clear();
        if (s->pop_changed.empty()) s->pop_changed.swap(list);   // keep the capacity
    }
    if (s->dirty_buf.size() < 3 * 1024) s->dirty_buf.resize(3 * 1024);
    int64_t n = 0;
    for (;;) {
        n = cv_drain_dirty(s->world, s->dirty_buf.data(), static_cast<int64_t>(s->dirty_buf.size() / 3));
        if (CV_IS_NEED(n)) {
            s->dirty_buf.resize(static_cast<size_t>(CV_NEEDED(n)) * 3 + 3 * 256);
            continue;
        }
        break;
    }
    for (int64_t i = 0; i < n; ++i) {
        const auto k = static_cast<size_t>(i) * 3;
        const Int3 c{s->dirty_buf[k], s->dirty_buf[k + 1], s->dirty_buf[k + 2]};
        CubeState* st = find_cube(s, c);
        if (st == nullptr || st->phase == kAbsent) {
            if (cv_cube_exists(s->world, c.x, c.y, c.z) == 1) adopt(s, c);
            continue;
        }
        if (installed(*st)) {
            // A held cube's writes are reported when its population finishes (never half-written).
            if ((st->flags & kHeld) == 0) note_changed(s, c, *st);
            request_remesh(s, c, *st);
            if ((st->flags & kAdopted) != 0) consider_mesh(s, c, *st);
        }
    }
}

// IN-FLIGHT LIMITS. Work goes to the workers once per owner tick, so a fixed in-flight cap is also a
// throughput cap: at 60 Hz, 3 x workers generation jobs in flight allowed at most 180 x workers cubes/s however
// fast a job is (and one population per region per tick, see step_populate). Each tick sizes the limits from
// the measured job times and tick interval to keep the workers busy until the next tick - workers x (1 + 1.25 x
// interval / average job time) - never below the old fixed values and at most kMaxInFlightPerWorker x workers.
// Generation is further held to what the owner installs in a quarter of a tick interval and to two ticks of
// the host's install budget (results beyond that would only wait in install_q), meshing to two ticks of the
// hand-out budget. The queues then hold about one tick of work, which is also all a re-prioritised plan
// waits for.
constexpr double kMaxInFlightPerWorker = 48.0;
void update_job_times(cv_stream* s, int64_t t0) {
    if (s->last_tick_start_us > 0) {
        const double gap = std::clamp(static_cast<double>(t0 - s->last_tick_start_us), 200.0, 250000.0);
        s->tick_gap_us = s->tick_gap_us <= 0.0 ? gap : 0.9 * s->tick_gap_us + 0.1 * gap;
    }
    s->last_tick_start_us = t0;
    for (size_t k = 0; k < static_cast<size_t>(kJobKinds); ++k) {
        const int64_t ns = s->job_ns[k].load(std::memory_order_relaxed);
        const int64_t runs = s->job_runs[k].load(std::memory_order_relaxed);
        const int64_t dr = runs - s->job_runs_seen[k];
        if (dr <= 0) continue;
        const double sample = static_cast<double>(ns - s->job_ns_seen[k]) / 1000.0 / static_cast<double>(dr);
        s->job_ns_seen[k] = ns;
        s->job_runs_seen[k] = runs;
        s->job_avg_us[k] = s->job_avg_us[k] <= 0.0 ? sample : 0.8 * s->job_avg_us[k] + 0.2 * sample;
    }
}
// Jobs of `kind` that keep every worker busy until the next tick (0 = not measured yet).
double feed_jobs(const cv_stream* s, JobKind kind) {
    const double avg = s->job_avg_us[static_cast<size_t>(kind)];
    if (avg <= 0.0 || s->tick_gap_us <= 0.0) return 0.0;
    return static_cast<double>(s->worker_count) * (1.0 + std::ceil(1.25 * s->tick_gap_us / std::max(1.0, avg)));
}
void compute_limits(cv_stream* s, int64_t max_installs, int64_t max_meshes) {
    const double cap = kMaxInFlightPerWorker * static_cast<double>(s->worker_count);
    const int64_t base3 = std::max<int64_t>(4, int64_t{3} * s->worker_count);
    double g = feed_jobs(s, JobKind::Generate);
    if (s->install_avg_us > 0.0 && s->tick_gap_us > 0.0) g = std::min(g, 0.25 * s->tick_gap_us / s->install_avg_us);
    if (max_installs != INT64_MAX) g = std::min(g, 2.0 * static_cast<double>(max_installs));
    s->limit_gen = std::max(base3, static_cast<int64_t>(std::min(g, cap)));
    double m = feed_jobs(s, JobKind::Mesh);
    if (max_meshes != INT64_MAX) m = std::min(m, 2.0 * static_cast<double>(max_meshes));
    s->limit_mesh = std::max(base3, static_cast<int64_t>(std::min(m, cap)));
    s->limit_pop = std::max(std::max<int64_t>(2, s->worker_count),
                            static_cast<int64_t>(std::min(feed_jobs(s, JobKind::Populate), cap)));
}

// Origin o can run now: all its cubes installed, one of them wanted, none held. *held_out: blocked only by a
// running population (worth retrying), as opposed to not ready (dropped until one of its cubes changes).
bool origin_runnable(cv_stream* s, const Int3& o, bool* held_out) {
    bool needed = false, held = false;
    for (int64_t d = 0; d < 8; ++d) {
        const Int3 x = add3(o, d & 1, (d >> 1) & 1, (d >> 2) & 1);
        if (!cube_in(s, x.y)) continue;
        const CubeState* st = find_cube(s, x);
        if (st == nullptr || st->phase != kInstalled) {
            *held_out = false;
            return false;
        }
        if (st->level == kLevelWanted) needed = true;
        if ((st->flags & kHeld) != 0) held = true;
    }
    *held_out = needed && held;
    return needed && !held;
}

// Dispatches ready populations nearest first (pop_q). Origins whose cubes overlap can never run at the same
// time, so the nearest runnable origin takes every runnable candidate overlapping it (o-1..o+1) into ONE job
// that runs them in a row under the union of their holds: a cube's 8 origins then finish in one job instead
// of eight ticks one after another. At most limit_pop jobs in flight.
void step_populate(cv_stream* s) {
    if (s->remote || s->gen == nullptr || s->gen->populate == nullptr) {
        s->pop_cand.clear();
        s->pop_q = {};
        return;
    }
    const int64_t limit = s->limit_pop;
    std::vector<Job> batch;
    std::vector<HeapItem> held_back;
    std::vector<Int3> chain, cubes;
    while (s->pop_in_flight < limit && !s->pop_q.empty()) {
        const HeapItem item = s->pop_q.top();
        s->pop_q.pop();
        const Int3 o = item.c;
        const auto it = s->pop_cand.find(o);
        if (it == s->pop_cand.end()) continue;   // stale entry (dispatched or dropped since)
        bool held = false;
        if (origin_done(s, o) || !origin_runnable(s, o, &held)) {
            if (held) {
                held_back.push_back(item);   // overlaps a running population: retry next tick
            } else {
                s->pop_cand.erase(it);       // comes back when one of its cubes installs or becomes wanted
            }
            continue;
        }
        s->pop_cand.erase(it);
        chain.clear();
        chain.push_back(o);
        for (int64_t d = 0; d < 27; ++d) {
            if (d == 13) continue;
            const Int3 n = add3(o, d % 3 - 1, (d / 3) % 3 - 1, d / 9 - 1);
            const auto nit = s->pop_cand.find(n);
            if (nit == s->pop_cand.end() || origin_done(s, n)) continue;
            bool nheld = false;
            if (!origin_runnable(s, n, &nheld)) continue;   // left to its own heap entry
            s->pop_cand.erase(nit);
            chain.push_back(n);
        }
        held_union(chain, cubes);
        for (const Int3& x : cubes) {
            CubeState* st = find_cube(s, x);
            if (st != nullptr) st->flags |= kHeld;
        }
        ++s->pop_in_flight;
        Job j;
        j.kind = JobKind::Populate;
        j.c = o;
        j.origins = chain;
        batch.push_back(std::move(j));
    }
    for (const HeapItem& h : held_back) s->pop_q.push(h);
    submit_all(s, batch);
}

void step_mesh_dispatch(cv_stream* s) {
    const int64_t limit = s->limit_mesh;
    std::vector<Job> batch;
    while (!s->mesh_q.empty() && s->mesh_in_flight < limit) {
        const Int3 c = s->mesh_q.top().c;
        s->mesh_q.pop();
        CubeState* st = find_cube(s, c);
        if (st == nullptr || (st->flags & kMeshQueued) == 0) continue;
        st->flags &= static_cast<uint16_t>(~kMeshQueued);
        if (st->phase != kInstalled) continue;
        st->flags |= kMeshInFlight;
        ++s->mesh_in_flight;
        Job j;
        j.kind = JobKind::Mesh;
        j.c = c;
        j.epoch = s->mesh_epoch;
        batch.push_back(std::move(j));
    }
    submit_all(s, batch);
}

void fill_view(MeshResult& m) {
    cv_gpu_mesh& v = m.view;
    const GpuMeshBuild& b = m.build;
    v.cx = m.c.x;
    v.cy = m.c.y;
    v.cz = m.c.z;
    for (size_t i = 0; i < 3; ++i) {
        v.quad16[i] = b.quad16[i].empty() ? nullptr : b.quad16[i].data();
        v.quad_count[i] = b.quad_count[i];
    }
    v.water = b.water.empty() ? nullptr : b.water.data();
    v.water_count = b.water_count;
    v.stamp_vertices = b.stamp_vertices.empty() ? nullptr : b.stamp_vertices.data();
    v.stamp_vertex_count = b.stamp_vertex_count;
    v.stamp_indices = b.stamp_indices.empty() ? nullptr : reinterpret_cast<const uint8_t*>(b.stamp_indices.data());
    v.stamp_index_count = static_cast<int32_t>(b.stamp_indices.size());
    for (size_t i = 0; i < 3; ++i) {
        v.aabb_min[i] = b.aabb_min[i];
        v.aabb_max[i] = b.aabb_max[i];
    }
    v.visibility = b.visibility;
    v.flags = b.flags;
    v.revision = b.revision;
}

void step_handout(cv_stream* s, int64_t max_meshes, int64_t max_bytes) {
    if (s->ready_meshes.empty()) return;
    std::sort(s->ready_meshes.begin(), s->ready_meshes.end(),
              [](const std::unique_ptr<MeshResult>& a, const std::unique_ptr<MeshResult>& b) { return a->prio < b->prio; });
    std::vector<std::unique_ptr<MeshResult>> rest;
    int64_t count = 0, bytes = 0;
    for (auto& m : s->ready_meshes) {
        const Int3 c = m->c;
        CubeState* st = find_cube(s, c);
        if (st == nullptr || st->phase != kInstalled) {
            give_mesh(s, m);
            continue;
        }
        if (m->build.revision < cv_cube_revision(s->world, c.x, c.y, c.z)) {   // stale: built before an edit
            ++s->stale_total;
            give_mesh(s, m);
            request_remesh(s, c, *st);
            continue;
        }
        if (m->empty() && (st->flags & kHasMesh) == 0) {   // nothing to draw and nothing to replace
            if ((st->flags & kMeshed) == 0) {
                st->flags |= kMeshed;
                touch(s, c.x, c.z);
            }
            ++s->meshed_total;
            give_mesh(s, m);
            continue;
        }
        if (count >= max_meshes || (count > 0 && bytes + m->bytes > max_bytes)) {
            rest.push_back(std::move(m));
            continue;
        }
        ++count;
        bytes += m->bytes;
        fill_view(*m);
        if (!m->empty()) {
            st->flags |= kHasMesh;
        } else {
            st->flags &= static_cast<uint16_t>(~kHasMesh);
        }
        if ((st->flags & kMeshed) == 0) {
            st->flags |= kMeshed;
            touch(s, c.x, c.z);
        }
        ++s->meshed_total;
        const auto slot = static_cast<int32_t>(s->handed.size());
        s->handed.push_back(std::move(m));
        emit(s, CV_EV_REMESHED, slot, c.x, c.y, c.z);
    }
    s->ready_meshes.swap(rest);
}



// Readiness + column unloads of the touched columns, under a time budget (the rest stays touched for the
// next tick; UNREADY before an unload never waits for this step - step_unload_select sends it).
void step_columns(cv_stream* s) {
    std::vector<Int2> list;
    list.swap(s->touched);
    const int64_t deadline = now_us() + 150;
    const uint16_t ready_bit = s->no_mesh ? kComplete : kMeshed;
    size_t done = 0;
    for (; done < list.size(); ++done) {
        if ((done & 7) == 7 && now_us() > deadline) break;
        const Int2 k = list[done];
        ColumnState* col = find_col(s, k.x, k.z);
        if (col == nullptr) continue;
        col->touched = false;
        if (col->has_band) {
            bool all = true;
            for (int64_t cy = col->band_lo; all; ++cy) {
                const CubeState* st = find_cube(s, Int3{k.x, cy, k.z});
                if (st == nullptr || st->phase != kInstalled || (st->flags & ready_bit) == 0) all = false;
                if (cy == col->band_hi) break;
            }
            if (all && !col->ready) {
                col->ready = true;
                col->ready_lo = col->band_lo;
                col->ready_hi = col->band_hi;
                emit(s, CV_EV_COLUMN_READY, -1, k.x, 0, k.z);
            } else if (!all && col->ready) {
                col->ready = false;
                emit(s, CV_EV_COLUMN_UNREADY, -1, k.x, 0, k.z);
            } else if (all && col->ready) {   // band moved onto ready cubes: unloads of them must revoke too
                col->ready_lo = std::min(col->ready_lo, col->band_lo);
                col->ready_hi = std::max(col->ready_hi, col->band_hi);
            }
        }
        // Unload a column nothing needs any more (after saving its record).
        if (col->tracked || col->states != 0 || col->saving || col->rec == 1) continue;
        if (s->store != nullptr && !s->remote && col->dirty && col->rec == 2) {
            col->saving = true;
            col->dirty = false;
            ++s->colsave_in_flight;
            Job j;
            j.kind = JobKind::SaveColumn;
            j.c = Int3{k.x, 0, k.z};
            j.populated = col->populated;
            submit(s, std::move(j));
            continue;
        }
        if (col->ready) emit(s, CV_EV_COLUMN_UNREADY, -1, k.x, 0, k.z);
        cv_column_remove(s->world, k.x, k.z);
        s->columns.erase(k);
    }
    for (size_t i = done; i < list.size(); ++i) s->touched.push_back(list[i]);   // flags still set
}

void step_load_dispatch(cv_stream* s) {
    if (s->remote || !s->active_list) return;
    const std::vector<LoadItem>& list = *s->active_list;
    const int64_t limit = s->limit_gen;
    std::vector<Job> batch;
    int64_t scanned = 0;   // a fresh list starts over at 0: bound the re-scan of entries already in flight
    while (s->list_cursor < list.size() && scanned++ < 4096 &&
           s->gen_in_flight + static_cast<int64_t>(s->install_q.size()) < limit) {
        const LoadItem& item = list[s->list_cursor++];
        const Int3 c = item.c;
        CubeState* st = find_cube(s, c);
        if (st == nullptr || st->level < kLevelSupport || st->phase != kAbsent) continue;
        if (st->retries > 0 && s->tick < st->retry_tick) continue;
        // Install only once the column records holding its population flags (origins c-1..c) are loaded.
        bool ready = true;
        for (int64_t dz = -1; dz <= 0; ++dz) {
            for (int64_t dx = -1; dx <= 0; ++dx) {
                ColumnState* col = find_col(s, c.x + dx, c.z + dz);
                if (col == nullptr) continue;
                if (col->rec == 0) request_column(s, Int2{c.x + dx, c.z + dz}, *col);
                if (col->rec != 2) ready = false;
            }
        }
        if (!ready) continue;
        st->phase = kLoading;
        ++s->gen_in_flight;
        Job j;
        j.kind = JobKind::Generate;
        j.c = c;
        j.key = item.key;
        batch.push_back(std::move(j));
    }
    submit_all(s, batch);
}

} // namespace

void release_handed(cv_stream* s) {
    for (auto& m : s->handed) give_mesh(s, m);
    s->handed.clear();
}

namespace {

int64_t limit_of(int32_t v) { return v > 0 ? v : INT64_MAX; }

} // namespace

int32_t tick_locked(cv_stream* s, const cv_budget* budget) {
    const int64_t t0 = now_us();
    ++s->tick;
    update_job_times(s, t0);
    s->events.clear();
    release_handed(s);
    const int64_t max_installs = budget != nullptr ? limit_of(budget->max_installs) : INT64_MAX;
    const int64_t max_meshes = budget != nullptr ? limit_of(budget->max_meshes) : INT64_MAX;
    const int64_t max_bytes = (budget != nullptr && budget->max_mesh_bytes > 0) ? budget->max_mesh_bytes : INT64_MAX;
    const int64_t max_unloads = budget != nullptr ? limit_of(budget->max_unloads) : INT64_MAX;
    compute_limits(s, max_installs, max_meshes);
    {
        std::lock_guard<std::mutex> g(s->plan_mutex);
        if (s->owner_centers_version != s->centers_version) {
            s->owner_centers = s->centers;
            s->owner_centers_version = s->centers_version;
        }
    }

    int64_t tp = t0;
    auto lap = [&](int i) {
        const int64_t t = now_us();
        s->step_us_sum[i] += t - tp;
        s->step_us_max[i] = std::max(s->step_us_max[i], t - tp);
        tp = t;
    };
    process_completions(s);
    lap(0);
    apply_plans(s);
    lap(1);
    step_pending_unloads(s);
    lap(2);
    step_installs(s, max_installs);
    lap(3);
    step_dirty(s);
    lap(4);
    step_waits(s);
    lap(5);
    step_populate(s);
    lap(6);
    step_mesh_dispatch(s);
    lap(7);
    step_handout(s, max_meshes, max_bytes);
    lap(8);
    step_unload_select(s, max_unloads);
    lap(9);
    step_columns(s);
    lap(10);
    step_load_dispatch(s);
    lap(11);

    const int64_t ms = now_ms();
    if (s->tops_changed && ms - s->last_tops_signal_ms >= kTopsSignalMs) {
        s->tops_changed = false;
        s->last_tops_signal_ms = ms;
        {
            std::lock_guard<std::mutex> g(s->plan_mutex);
            s->tops_dirty = true;
        }
        s->plan_cv.notify_all();
    }
    s->tick_us_last = now_us() - t0;
    s->tick_us_max = std::max(s->tick_us_max, s->tick_us_last);
    return static_cast<int32_t>(s->events.size());
}

} // namespace cenda::voxel::streaming
