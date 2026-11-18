// The streamer (cv_stream_*, OpenMason #121 + the save path of #120): owner tick, worker pool, population
// scheduling, meshing hand-out, readiness, unload + saves. Rules: voxel_world.h "STREAMER RULES"; private
// structures and thread ownership: stream_impl.hpp; the wanted-set planner: stream_plan.cpp.
#include "stream_impl.hpp"
#include "cv_error.hpp"
#include "cv_internal.hpp"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstddef>
#include <cstring>
#include <new>
#include <utility>

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

namespace {

constexpr int64_t kRemoteWaitTicks = 20;    // REMOTE: mesh without missing wanted neighbours after this
constexpr int64_t kAdoptGraceTicks = 40;    // host-installed cubes the plan does not want stay this long
constexpr int64_t kApplyBudgetUs = 250;     // plan diff application per tick
constexpr int64_t kMinPlanIntervalMs = 25;
constexpr int64_t kTopsPlanIntervalMs = 8;  // re-plan this soon when TOPs arrive for columns a plan left out
constexpr int64_t kWalkIntervalMs = 200;    // rule (c) walk cadence
constexpr int64_t kTopsSignalMs = 100;      // re-plan after installs at most this often
constexpr size_t kFreeCellsMax = 96;
constexpr size_t kFreeMeshesMax = 64;

int64_t now_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
               std::chrono::steady_clock::now().time_since_epoch())
        .count();
}
int64_t now_ns() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now().time_since_epoch())
        .count();
}
int64_t now_us() {
    return std::chrono::duration_cast<std::chrono::microseconds>(
               std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

Int3 add3(const Int3& a, int64_t dx, int64_t dy, int64_t dz) { return Int3{a.x + dx, a.y + dy, a.z + dz}; }

// ---- population api: confined to the 8 held cubes --------------------------------------------------------

struct PopCtx {
    cv_world* world;
    int64_t ox, oy, oz;
};
bool held_cell(const PopCtx* p, int64_t x, int64_t y, int64_t z) {
    const int64_t cx = x >> CV_CUBE_SHIFT, cy = y >> CV_CUBE_SHIFT, cz = z >> CV_CUBE_SHIFT;
    return (cx == p->ox || cx - 1 == p->ox) && (cy == p->oy || cy - 1 == p->oy) && (cz == p->oz || cz - 1 == p->oz);
}
PopCtx* ctx_of(cv_world* w) { return reinterpret_cast<PopCtx*>(w); }
int32_t api_get(cv_world* w, int64_t x, int64_t y, int64_t z) {
    PopCtx* p = ctx_of(w);
    if (!held_cell(p, x, y, z)) return CV_ERR_MISSING;
    return cv_get(p->world, x, y, z);
}
int32_t api_set(cv_world* w, int64_t x, int64_t y, int64_t z, uint16_t id) {
    PopCtx* p = ctx_of(w);
    if (!held_cell(p, x, y, z)) return CV_ERR_ARG;
    return cv_set(p->world, x, y, z, id);
}
int32_t api_layer_get(cv_world* w, int32_t layer, int64_t x, int64_t y, int64_t z) {
    PopCtx* p = ctx_of(w);
    if (!held_cell(p, x, y, z)) return CV_ERR_MISSING;
    return cv_layer_get(p->world, layer, x, y, z);
}
int32_t api_layer_set(cv_world* w, int32_t layer, int64_t x, int64_t y, int64_t z, uint16_t v) {
    PopCtx* p = ctx_of(w);
    if (!held_cell(p, x, y, z)) return CV_ERR_ARG;
    return cv_layer_set(p->world, layer, x, y, z, v);
}
int32_t api_surface_y(cv_world* w, int64_t x, int64_t z, int64_t* out_y) {
    return cv_surface_y(ctx_of(w)->world, x, z, out_y);
}
const cv_gen_api kApi = {api_get, api_set, api_layer_get, api_layer_set, api_surface_y, {}};

// ---- buffer pools --------------------------------------------------------------------------------------

std::unique_ptr<uint16_t[]> take_cells(cv_stream* s) {
    {
        std::lock_guard<std::mutex> g(s->pool_mutex);
        if (!s->free_cells.empty()) {
            auto p = std::move(s->free_cells.back());
            s->free_cells.pop_back();
            return p;
        }
    }
    return std::unique_ptr<uint16_t[]>(new uint16_t[CV_CUBE_CELLS]);
}
void give_cells(cv_stream* s, std::unique_ptr<uint16_t[]>& p) {
    if (!p) return;
    std::lock_guard<std::mutex> g(s->pool_mutex);
    if (s->free_cells.size() < kFreeCellsMax) s->free_cells.push_back(std::move(p));
    p.reset();
}
std::unique_ptr<MeshResult> take_mesh(cv_stream* s) {
    {
        std::lock_guard<std::mutex> g(s->pool_mutex);
        if (!s->free_meshes.empty()) {
            auto p = std::move(s->free_meshes.back());
            s->free_meshes.pop_back();
            return p;
        }
    }
    return std::make_unique<MeshResult>();
}
void give_mesh(cv_stream* s, std::unique_ptr<MeshResult>& m) {
    if (!m) return;
    std::lock_guard<std::mutex> g(s->pool_mutex);
    if (s->free_meshes.size() < kFreeMeshesMax) s->free_meshes.push_back(std::move(m));
    m.reset();
}
void recycle(cv_stream* s, std::unique_ptr<Result>& r) {
    if (!r) return;
    give_mesh(s, r->mesh);
    r.reset();
}

bool all_equal(const uint16_t* v, uint16_t first) {
    for (int i = 0; i < CV_CUBE_CELLS; ++i) {
        if (v[i] != first) return false;
    }
    return true;
}

// ---- worker jobs ------------------------------------------------------------------------------------------

template <class F>
int64_t read_grow(std::vector<uint8_t>& buf, F&& read) {
    for (;;) {
        const int64_t n = read(buf.data(), static_cast<int64_t>(buf.size()));
        if (CV_IS_NEED(n)) {
            buf.resize(static_cast<size_t>(CV_NEEDED(n)));
            continue;
        }
        return n;
    }
}

// Loads or generates one cube and PREPARES it here on the worker (sections, masks, tracker leaves, callback
// answer), so the owner's install is a pointer swap + one tracker update (cv_internal.hpp install_prepared).
void run_generate(cv_stream* s, const Job& job, Result& r) {
    const Int3 c = job.c;
    if (s->store != nullptr && !job.skip_store) {
        thread_local std::vector<uint8_t> buf(64 * 1024);
        const int64_t n = read_grow(buf, [&](uint8_t* d, int64_t cap) {
            return cv_store_read_cube(s->store, c.x, c.y, c.z, d, cap);
        });
        if (n > 0) {
            const int32_t rc = prepare_cube_encoded(s->world, c.x, c.y, c.z, buf.data(), n, r.prepared);
            if (rc == CV_OK) {
                r.gen = GenKind::Store;
                return;
            }
            if (rc != CV_ERR_CORRUPT) {
                r.gen = GenKind::Error;
                r.status = rc;
                return;
            }
            // An undecodable record: generate instead (as for one failing the store's checksum below).
            s->errors_total.fetch_add(1, std::memory_order_relaxed);
        } else if (n < 0 && n != CV_ERR_CORRUPT) {
            r.gen = GenKind::IoError;
            r.status = static_cast<int32_t>(n);
            return;
        } else if (n == CV_ERR_CORRUPT) {
            s->errors_total.fetch_add(1, std::memory_order_relaxed);
        }
    }
    std::unique_ptr<uint16_t[]> ids = take_cells(s);
    std::unique_ptr<uint16_t[]> layers[CV_MAX_LAYERS];
    uint16_t* lp[CV_MAX_LAYERS] = {};
    bool any = false;
    for (int l = 0; l < CV_MAX_LAYERS; ++l) {
        if (world_layer_flags(s->world, l) == 0) continue;
        layers[l] = take_cells(s);
        std::memset(layers[l].get(), 0, sizeof(uint16_t) * CV_CUBE_CELLS);
        lp[l] = layers[l].get();
        any = true;
    }
    const int32_t g = s->gen->generate_cube(s->gen->user, c.x, c.y, c.z, ids.get(), any ? lp : nullptr);
    int32_t rc = g;
    if (g >= 0) {
        const uint16_t first = ids[0];
        bool uniform = g == 0 || all_equal(ids.get(), first);
        CubeInstall in;
        for (int l = 0; l < CV_MAX_LAYERS; ++l) {
            if (!layers[l] || g == 0 || all_equal(layers[l].get(), 0)) continue;
            in.layers[l] = layers[l].get();
            uniform = false;
        }
        if (uniform) {
            in.fill_id = first;   // fast path: no sections, uniform masks and leaves
        } else {
            in.ids = ids.get();
        }
        s->generated_total.fetch_add(1, std::memory_order_relaxed);
        rc = prepare_cube(s->world, c.x, c.y, c.z, in, r.prepared);
    }
    give_cells(s, ids);
    for (auto& l : layers) give_cells(s, l);
    if (rc < 0) {
        r.gen = GenKind::Error;
        r.status = rc;
        return;
    }
    r.gen = GenKind::Generated;
}

void run_load_column(cv_stream* s, const Job& job, Result& r) {
    thread_local std::vector<uint8_t> buf(16 * 1024);
    const int64_t n = read_grow(buf, [&](uint8_t* d, int64_t cap) {
        return cv_store_read_column(s->store, job.c.x, job.c.z, d, cap);
    });
    if (n > 0) {
        r.bytes.assign(buf.begin(), buf.begin() + n);
    } else if (n < 0) {
        r.status = static_cast<int32_t>(n);
    }
}

// A chain of overlapping origins (step_populate), one after another under one hold; each population still
// sees only its own 8 cubes.
void run_populate(cv_stream* s, const Job& job, Result& r) {
    r.origins = job.origins;
    r.status = CV_OK;
    for (const Int3& o : job.origins) {
        PopCtx ctx{s->world, o.x, o.y, o.z};
        const int32_t rc = s->gen->populate(s->gen->user, &kApi, reinterpret_cast<cv_world*>(&ctx), o.x, o.y, o.z);
        if (rc < 0 && r.status >= 0) r.status = rc;
    }
}

void run_mesh(cv_stream* s, const Job& job, Result& r) {
    r.mesh = take_mesh(s);
    MeshResult& m = *r.mesh;
    m.c = job.c;
    m.epoch = job.epoch;
    r.status = build_gpu_mesh(s->world, job.c.x, job.c.y, job.c.z, s->mesh_flags, m.build);
    int64_t bytes = 0;
    for (const auto& q : m.build.quad16) bytes += static_cast<int64_t>(q.size());
    bytes += static_cast<int64_t>(m.build.water.size() + m.build.stamp_vertices.size() +
                                  m.build.stamp_indices.size() * sizeof(uint32_t));
    m.bytes = bytes;
}

void run_save_cube(cv_stream* s, const Job& job, Result& r) {
    thread_local std::vector<uint8_t> buf(64 * 1024);
    const Int3 c = job.c;
    // Read the revision BEFORE encoding: the bytes are at least this new, so a later edit re-saves.
    r.rev = cv_cube_revision(s->world, c.x, c.y, c.z);
    if (r.rev == 0) {
        r.status = CV_ERR_MISSING;
        return;
    }
    const int64_t n = read_grow(buf, [&](uint8_t* d, int64_t cap) {
        return cv_cube_encode(s->world, c.x, c.y, c.z, CV_PARTS_SAVED, d, cap);
    });
    if (n < 0) {
        r.status = static_cast<int32_t>(n);
        return;
    }
    r.status = cv_store_write_cube(s->store, c.x, c.y, c.z, buf.data(), n);
}

// A planner TOP request (stream_plan.hpp "TOPS"): may call the generator's column_surface here, on the worker.
void run_top(cv_stream* s, const Job& job) {
    const TopInfo t = resolve_top(s->world, s->gen, job.c.x, job.c.z);
    bool wake = false;
    {
        std::lock_guard<std::mutex> g(s->plan_mutex);
        wake = s->top_results.empty();   // one wake-up per drain, not per answer
        s->top_results.emplace_back(Int2{job.c.x, job.c.z}, t);
    }
    if (wake) s->plan_cv.notify_all();
}

void run_save_column(cv_stream* s, const Job& job, Result& r) {
    thread_local std::vector<uint8_t> core(16 * 1024);
    thread_local std::vector<uint8_t> out;
    const int64_t n = read_grow(core, [&](uint8_t* d, int64_t cap) {
        return cv_column_encode(s->world, job.c.x, job.c.z, d, cap);
    });
    if (n < 0 && n != CV_ERR_MISSING) {
        r.status = static_cast<int32_t>(n);
        return;
    }
    build_column_record(job.populated, core.data(), n > 0 ? static_cast<size_t>(n) : 0, out);
    r.status = cv_store_write_column(s->store, job.c.x, job.c.z, out.data(), static_cast<int64_t>(out.size()));
}

void worker_main(cv_stream* s) {
    for (;;) {
        Job job;
        {
            std::unique_lock<std::mutex> lk(s->q_mutex);
            auto pending = [&] {
                for (const auto& q : s->queue) {
                    if (!q.empty()) return true;
                }
                return false;
            };
            s->q_cv.wait(lk, [&] { return s->stopping || pending(); });
            if (!pending()) return;   // stopping with nothing left
            // Kinds in priority order, except that TOP and generation jobs form ONE distance order: the
            // planner's TOP requests run just ahead of the cubes they unlock (stream_plan.hpp top_request_key).
            auto& tops = s->queue[static_cast<size_t>(JobKind::Top)];
            auto& gens = s->queue[static_cast<size_t>(JobKind::Generate)];
            for (size_t k = 0; k < static_cast<size_t>(kJobKinds); ++k) {
                auto* q = &s->queue[k];
                if (q->empty()) continue;
                if (q == &tops && !gens.empty() && gens.front().key < tops.front().key) q = &gens;
                job = std::move(q->front());
                q->pop_front();
                break;
            }
        }
        if (job.kind == JobKind::Top) {   // answers the planner, not the owner
            run_top(s, job);
            continue;
        }
        auto r = std::make_unique<Result>();
        r->kind = job.kind;
        r->c = job.c;
        const int64_t job_t0 = now_ns();
        switch (job.kind) {
        case JobKind::Generate: run_generate(s, job, *r); break;
        case JobKind::LoadColumn: run_load_column(s, job, *r); break;
        case JobKind::Populate: run_populate(s, job, *r); break;
        case JobKind::Mesh: run_mesh(s, job, *r); break;
        case JobKind::SaveCube: run_save_cube(s, job, *r); break;
        case JobKind::SaveColumn: run_save_column(s, job, *r); break;
        case JobKind::Top: break;
        }
        const auto k = static_cast<size_t>(job.kind);
        s->job_ns[k].fetch_add(now_ns() - job_t0, std::memory_order_relaxed);
        s->job_runs[k].fetch_add(job.kind == JobKind::Populate ? static_cast<int64_t>(job.origins.size()) : 1,
                                 std::memory_order_relaxed);
        {
            std::lock_guard<std::mutex> g(s->done_mutex);
            s->done.push_back(std::move(r));
        }
        s->done_cv.notify_all();
    }
}

void submit(cv_stream* s, Job&& job) {
    {
        std::lock_guard<std::mutex> g(s->q_mutex);
        s->queue[static_cast<size_t>(job.kind)].push_back(std::move(job));
    }
    s->q_cv.notify_one();
}

// One lock and one wake-up round for a burst of jobs (dispatch steps queue dozens per tick).
void submit_all(cv_stream* s, std::vector<Job>& jobs) {
    if (jobs.empty()) return;
    {
        std::lock_guard<std::mutex> g(s->q_mutex);
        for (Job& j : jobs) s->queue[static_cast<size_t>(j.kind)].push_back(std::move(j));
    }
    if (jobs.size() == 1) {
        s->q_cv.notify_one();
    } else {
        s->q_cv.notify_all();
    }
    jobs.clear();
}

// ---- planner thread ---------------------------------------------------------------------------------------

void planner_main(cv_stream* s) {
    Planner planner(s->world, s->gen, s->remote);
    // TOPs only the surface callback can answer are resolved on the workers, nearest first and in one
    // distance order with generation (worker_main); the planner plans the columns already known and re-plans
    // as answers arrive.
    planner.set_async([s](const std::vector<LoadItem>& cols) {
        std::vector<Job> jobs;
        jobs.reserve(cols.size());
        for (const LoadItem& k : cols) {
            Job j;
            j.kind = JobKind::Top;
            j.c = k.c;
            j.key = k.key;
            jobs.push_back(std::move(j));
        }
        s->tops_outstanding.fetch_add(static_cast<int64_t>(cols.size()));
        submit_all(s, jobs);
    });
    int64_t last_plan = INT64_MIN / 2, last_walk = INT64_MIN / 2;
    bool partial = false;   // the last plan left columns out for want of their TOP
    std::vector<std::pair<Int2, TopInfo>> got;
    std::unique_lock<std::mutex> lk(s->plan_mutex);
    for (;;) {
        if (s->plan_stop) return;
        const int64_t now = now_ms();
        const bool backlog = s->batches.size() >= 2;
        const bool centers_changed = s->centers_version != s->planned_version;
        const bool tops_arrived = !s->top_results.empty();
        // The walk waits for every TOP it may reach (it would ask the callback on this thread).
        const bool walk_due = !s->centers.empty() && now - last_walk >= kWalkIntervalMs;
        const bool walk_ready = walk_due && planner.tops_pending() == 0;
        const bool want = centers_changed || s->tops_dirty || tops_arrived || walk_ready;
        const int64_t interval = tops_arrived && partial ? kTopsPlanIntervalMs : kMinPlanIntervalMs;
        if (want && !backlog && now - last_plan >= interval) {
            std::vector<cv_center> snap = s->centers;
            s->planned_version = s->centers_version;
            s->tops_dirty = false;
            s->planning = true;
            got.clear();
            got.swap(s->top_results);
            lk.unlock();
            for (const auto& [k, t] : got) planner.add_resolved(k, t);
            s->tops_outstanding.fetch_sub(static_cast<int64_t>(got.size()));
            auto batch = std::make_unique<PlanBatch>();
            planner.compute(snap, now, walk_due, *batch);
            partial = batch->tops_pending > 0 || planner.tops_pending() > 0;
            lk.lock();
            s->planning = false;
            if (batch->walked) last_walk = now;
            last_plan = now;
            ++s->plans_total;
            s->plan_us_last = batch->plan_us;
            s->batches.push_back(std::move(batch));
            continue;
        }
        int64_t wait = 50;
        if (want && !backlog) wait = std::max<int64_t>(1, interval - (now - last_plan));
        if (!s->centers.empty() && !walk_due) wait = std::min(wait, std::max<int64_t>(1, kWalkIntervalMs - (now - last_walk)));
        s->plan_cv.wait_for(lk, std::chrono::milliseconds(wait));
    }
}

// ---- owner helpers ------------------------------------------------------------------------------------------

CubeState* find_cube(cv_stream* s, const Int3& c) {
    const auto it = s->cubes.find(c);
    return it == s->cubes.end() ? nullptr : &it->second;
}
ColumnState* find_col(cv_stream* s, int64_t x, int64_t z) {
    const auto it = s->columns.find(Int2{x, z});
    return it == s->columns.end() ? nullptr : &it->second;
}
void touch(cv_stream* s, int64_t x, int64_t z) {
    ColumnState* col = find_col(s, x, z);
    if (col == nullptr || col->touched) return;
    col->touched = true;
    s->touched.push_back(Int2{x, z});
}
CubeState& get_cube(cv_stream* s, const Int3& c) {
    auto [it, inserted] = s->cubes.try_emplace(c);
    if (inserted) {
        ColumnState& col = s->columns[Int2{c.x, c.z}];
        ++col.states;
        if (col.rec == 0 && (s->store == nullptr || s->remote)) col.rec = 2;
    }
    return it->second;
}
void erase_cube(cv_stream* s, const Int3& c) {
    if (s->cubes.erase(c) == 0) return;
    ColumnState* col = find_col(s, c.x, c.z);
    if (col != nullptr) --col->states;
    touch(s, c.x, c.z);
}
void emit(cv_stream* s, int32_t type, int32_t mesh, int64_t x, int64_t y, int64_t z) {
    s->events.push_back(cv_event{type, mesh, x, y, z});
}
bool cube_in(const cv_stream* s, int64_t cy) { return cy >= s->cmin && cy <= s->cmax; }
bool installed(const CubeState& st) {
    return st.phase == kInstalled || st.phase == kUnloadPending || st.phase == kUnloadSaving;
}

// Squared distance (blocks) from the point cube c * 32 + off (per axis) to the nearest viewer.
uint64_t prio_at(const cv_stream* s, const Int3& c, double off) {
    if (s->owner_centers.empty()) return 0;
    double best = 1e300;
    for (const cv_center& v : s->owner_centers) {
        const double dx = static_cast<double>(c.x - (v.x >> 5)) * 32.0 + off - static_cast<double>(v.x & 31);
        const double dy = static_cast<double>(c.y - (v.y >> 5)) * 32.0 + off - static_cast<double>(v.y & 31);
        const double dz = static_cast<double>(c.z - (v.z >> 5)) * 32.0 + off - static_cast<double>(v.z & 31);
        best = std::min(best, dx * dx + dy * dy + dz * dz);
    }
    return static_cast<uint64_t>(std::min(best, 1.8e19));
}
// Owner-side priority of a cube (install order, mesh queue, mesh hand-out; lower = sooner): the planner's
// order (stream_plan.hpp "PRIORITY") - distance of its centre to the nearest viewer in 1/16 blocks, kBandBias
// less for the column's band and the cube above/below it.
uint64_t prio_of(cv_stream* s, const Int3& c) {
    double d = std::sqrt(static_cast<double>(prio_at(s, c, 16.0)));
    const auto it = s->columns.find(Int2{c.x, c.z});
    if (it != s->columns.end() && it->second.has_band && c.y >= it->second.band_lo - 1 && c.y <= it->second.band_hi + 1) {
        d = std::max(0.0, d - kBandBias);
    }
    return static_cast<uint64_t>(std::min(d * 16.0, 1.8e19));
}

// The cubes o..o+1 of every origin of a chain, each once.
void held_union(const std::vector<Int3>& origins, std::vector<Int3>& out) {
    out.clear();
    for (const Int3& o : origins) {
        for (int64_t d = 0; d < 8; ++d) {
            const Int3 x = add3(o, d & 1, (d >> 1) & 1, (d >> 2) & 1);
            if (std::find(out.begin(), out.end(), x) == out.end()) out.push_back(x);
        }
    }
}

bool origin_done(cv_stream* s, const Int3& o) {
    if (!cube_in(s, o.y)) return true;
    ColumnState* col = find_col(s, o.x, o.z);
    if (col == nullptr || col->rec != 2) return false;
    return std::binary_search(col->populated.begin(), col->populated.end(), o.y);
}

void request_column(cv_stream* s, const Int2& k, ColumnState& col) {
    if (col.rec != 0) return;
    if (s->store == nullptr || s->remote) {
        col.rec = 2;
        return;
    }
    col.rec = 1;
    ++s->colload_in_flight;
    Job j;
    j.kind = JobKind::LoadColumn;
    j.c = Int3{k.x, 0, k.z};
    submit(s, std::move(j));
}

bool neighbours_ok(cv_stream* s, const Int3& c) {
    for (int64_t dy = -1; dy <= 1; ++dy) {
        for (int64_t dz = -1; dz <= 1; ++dz) {
            for (int64_t dx = -1; dx <= 1; ++dx) {
                if (dx == 0 && dy == 0 && dz == 0) continue;
                const Int3 n = add3(c, dx, dy, dz);
                if (!cube_in(s, n.y)) continue;
                const CubeState* ns = find_cube(s, n);
                if (ns == nullptr || ns->level < kLevelWanted || installed(*ns)) continue;
                if (cube_known_air(s->world, n.x, n.y, n.z)) continue;
                return false;
            }
        }
    }
    return true;
}

void enqueue_mesh(cv_stream* s, const Int3& c, CubeState& st) {
    if (s->no_mesh) return;
    st.flags |= kMeshQueued;
    s->mesh_q.push(HeapItem{prio_of(s, c), c});
}

// First mesh of a cube: once wanted, installed, complete (and, for host-installed cubes, its wanted
// neighbours arrived or the wait expired).
void consider_mesh(cv_stream* s, const Int3& c, CubeState& st) {
    if (s->no_mesh) return;   // readiness counts kComplete instead (step_columns)
    if (st.phase != kInstalled || st.level != kLevelWanted) return;
    if ((st.flags & kComplete) == 0) return;
    if ((st.flags & (kMeshed | kMeshQueued | kMeshInFlight | kHeld)) != 0) return;
    if ((st.flags & kAdopted) != 0 && (st.flags & kWaitOver) == 0 && !neighbours_ok(s, c)) {
        if (s->tick - st.installed_tick < kRemoteWaitTicks) {
            if ((st.flags & kRemoteWait) == 0) {
                st.flags |= kRemoteWait;
                s->remote_wait.push(TickItem{st.installed_tick + kRemoteWaitTicks, c});
            }
            return;
        }
    }
    st.flags &= static_cast<uint16_t>(~kRemoteWait);
    enqueue_mesh(s, c, st);
}

void request_remesh(cv_stream* s, const Int3& c, CubeState& st) {
    if (s->no_mesh || !installed(st)) return;
    if ((st.flags & kMeshInFlight) != 0) {
        st.flags |= kRemesh;
        return;
    }
    if ((st.flags & kMeshQueued) != 0) return;
    if ((st.flags & kMeshed) != 0) {
        enqueue_mesh(s, c, st);
    } else {
        consider_mesh(s, c, st);
    }
}

// CV_EV_CHANGED when the cube's revision moved past the one last reported (coalesces per tick: the second
// call in a tick finds seen_rev current).
void note_changed(cv_stream* s, const Int3& c, CubeState& st) {
    if (!installed(st)) return;
    const int64_t rev = cv_cube_revision(s->world, c.x, c.y, c.z);
    if (rev <= 0 || rev == st.seen_rev) return;
    st.seen_rev = rev;
    emit(s, CV_EV_CHANGED, -1, c.x, c.y, c.z);
}

void check_complete(cv_stream* s, const Int3& c, CubeState& st) {
    if ((st.flags & kComplete) != 0 || st.phase != kInstalled) return;
    for (int64_t d = 0; d < 8; ++d) {
        if (!origin_done(s, add3(c, -(d & 1), -((d >> 1) & 1), -((d >> 2) & 1)))) return;
    }
    st.flags |= kComplete;
    touch(s, c.x, c.z);
    consider_mesh(s, c, st);
}

void add_origin_candidates(cv_stream* s, const Int3& c) {
    if (s->remote) return;
    for (int64_t d = 0; d < 8; ++d) {
        const Int3 o = add3(c, -(d & 1), -((d >> 1) & 1), -((d >> 2) & 1));
        // Population origin o: the centre of its 2x2x2 cubes o..o+1.
        if (cube_in(s, o.y) && !origin_done(s, o) && s->pop_cand.insert(o).second) {
            s->pop_q.push(HeapItem{prio_at(s, o, 32.0), o});
        }
    }
}

void on_installed(cv_stream* s, const Int3& c, CubeState& st, bool adopted) {
    st.phase = kInstalled;
    st.installed_tick = s->tick;
    st.flags = static_cast<uint16_t>(st.flags & (kSaveInFlight | kHeld | kMeshInFlight));
    if (adopted) st.flags |= kComplete | kAdopted;
    st.seen_rev = cv_cube_revision(s->world, c.x, c.y, c.z);
    emit(s, CV_EV_INSTALLED, -1, c.x, c.y, c.z);
    touch(s, c.x, c.z);
    s->tops_changed = true;
    add_origin_candidates(s, c);
    if (adopted) {
        consider_mesh(s, c, st);
    } else {
        check_complete(s, c, st);
    }
    for (int64_t dy = -1; dy <= 1; ++dy) {
        for (int64_t dz = -1; dz <= 1; ++dz) {
            for (int64_t dx = -1; dx <= 1; ++dx) {
                if (dx == 0 && dy == 0 && dz == 0) continue;
                const Int3 n = add3(c, dx, dy, dz);
                CubeState* ns = find_cube(s, n);
                if (ns != nullptr && (ns->flags & kRemoteWait) != 0) consider_mesh(s, n, *ns);
            }
        }
    }
}

void adopt(cv_stream* s, const Int3& c) {
    CubeState& st = get_cube(s, c);
    st.saved_rev = (s->store != nullptr && !s->remote) ? 0 : cv_cube_revision(s->world, c.x, c.y, c.z);
    on_installed(s, c, st, true);
    if (st.level == kLevelNone) s->adopt_wait.push(TickItem{s->tick + kAdoptGraceTicks, c});
}

bool save_needed(cv_stream* s, const Int3& c, const CubeState& st) {
    return s->store != nullptr && !s->remote && cv_cube_revision(s->world, c.x, c.y, c.z) != st.saved_rev;
}

void submit_save(cv_stream* s, const Int3& c, CubeState& st) {
    st.flags |= kSaveInFlight;
    ++s->save_in_flight;
    Job j;
    j.kind = JobKind::SaveCube;
    j.c = c;
    submit(s, std::move(j));
}

void remove_cube(cv_stream* s, const Int3& c) {
    cv_cube_remove(s->world, c.x, c.y, c.z);
    emit(s, CV_EV_UNLOADED, -1, c.x, c.y, c.z);
    ++s->unloaded_total;
    erase_cube(s, c);
}

void finish_unload(cv_stream* s, const Int3& c, CubeState& st) {
    if (save_needed(s, c, st)) {
        st.phase = kUnloadSaving;
        submit_save(s, c, st);
        return;
    }
    remove_cube(s, c);
}

// ---- completions ------------------------------------------------------------------------------------------

void apply_column_record(cv_stream* s, const Int3& k, ColumnState& col, const Result& r) {
    if (r.status < 0) {
        s->errors_total.fetch_add(1, std::memory_order_relaxed);
        if (r.status != CV_ERR_CORRUPT) {   // I/O: try again later
            col.rec = 0;
            return;
        }
    } else if (!r.bytes.empty()) {
        std::vector<int64_t> populated;
        const uint8_t* core = nullptr;
        size_t core_len = 0;
        if (!parse_column_record(r.bytes.data(), r.bytes.size(), populated, &core, &core_len)) {
            s->errors_total.fetch_add(1, std::memory_order_relaxed);
        } else {
            if (core_len > 0 && cv_column_decode(s->world, k.x, k.z, core, static_cast<int64_t>(core_len)) != CV_OK) {
                s->errors_total.fetch_add(1, std::memory_order_relaxed);
            }
            col.populated = std::move(populated);
        }
    }
    col.rec = 2;
    s->tops_changed = true;
    touch(s, k.x, k.z);
}

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

// Blocks (owner context) until `cond` holds, processing completions meanwhile.
template <class Cond>
void wait_for_jobs(cv_stream* s, Cond&& cond) {
    for (;;) {
        process_completions(s);
        if (cond()) return;
        std::unique_lock<std::mutex> lk(s->done_mutex);
        s->done_cv.wait_for(lk, std::chrono::milliseconds(20), [&] { return !s->done.empty(); });
    }
}

// ---- plan application -----------------------------------------------------------------------------------------

void apply_column_change(cv_stream* s, const ColumnChange& cc) {
    ColumnState& col = s->columns[cc.c];
    col.tracked = cc.tracked;
    col.has_band = cc.has_band;
    col.band_lo = cc.band_lo;
    col.band_hi = cc.band_hi;
    if (col.tracked) request_column(s, cc.c, col);
    touch(s, cc.c.x, cc.c.z);
}

void apply_level(cv_stream* s, const Int3& c, uint8_t lv) {
    auto it = s->cubes.find(c);
    if (it == s->cubes.end() && lv == kLevelNone) return;
    CubeState& st = it == s->cubes.end() ? get_cube(s, c) : it->second;
    const uint8_t old = st.level;
    if (old == lv) return;
    st.level = lv;
    ++s->level_changes;
    touch(s, c.x, c.z);
    if (lv == kLevelWanted) {
        add_origin_candidates(s, c);
        if (st.phase == kInstalled) consider_mesh(s, c, st);
    }
    if (lv == kLevelNone) {
        if (st.phase == kAbsent) {
            erase_cube(s, c);
            return;
        }
        if (st.phase == kInstalled) s->unload_q.push_back(c);
    }
}

void apply_plans(cv_stream* s) {
    const int64_t deadline = now_us() + kApplyBudgetUs;
    for (;;) {
        if (!s->applying) {
            std::lock_guard<std::mutex> g(s->plan_mutex);
            if (s->batches.empty()) return;
            s->applying = std::move(s->batches.front());
            s->batches.pop_front();
            s->apply_col = s->apply_cube = 0;
        }
        PlanBatch& b = *s->applying;
        while (s->apply_col < b.columns.size()) apply_column_change(s, b.columns[s->apply_col++]);
        while (s->apply_cube < b.cubes.size()) {
            const CubeChange& ch = b.cubes[s->apply_cube++];
            apply_level(s, ch.c, ch.level);
            if ((s->apply_cube & 511) == 0 && now_us() > deadline) return;
        }
        s->plan_wanted = b.wanted;
        s->plan_needed = b.needed;
        s->plan_walk = b.walk_cubes;
        auto list = std::make_shared<const std::vector<LoadItem>>(std::move(b.list));
        s->active_list = list;
        s->list_cursor = 0;
        {
            std::lock_guard<std::mutex> g(s->list_mutex);
            s->missing_list = std::move(list);
        }
        s->applying.reset();
        s->plan_cv.notify_all();
        if (now_us() > deadline) return;
    }
}

// ---- tick steps ---------------------------------------------------------------------------------------------

void step_pending_unloads(cv_stream* s) {
    std::vector<Int3> keep;
    std::vector<Int3> list;
    list.swap(s->pending_unloads);
    for (const Int3& c : list) {
        CubeState* st = find_cube(s, c);
        if (st == nullptr || st->phase != kUnloadPending) continue;
        if (st->level > kLevelNone) {   // wanted again before it went: keep it
            st->phase = kInstalled;
            touch(s, c.x, c.z);
            consider_mesh(s, c, *st);
            continue;
        }
        if (st->pending_tick >= s->tick || (st->flags & (kHeld | kSaveInFlight)) != 0) {
            keep.push_back(c);
            continue;
        }
        finish_unload(s, c, *st);
    }
    for (const Int3& c : keep) s->pending_unloads.push_back(c);

    list.clear();
    list.swap(s->resume_unloads);
    for (const Int3& c : list) {
        CubeState* st = find_cube(s, c);
        if (st == nullptr || st->phase != kUnloadSaving || (st->flags & kSaveInFlight) != 0) continue;
        if (st->level > kLevelNone) {
            st->phase = kInstalled;
            touch(s, c.x, c.z);
            consider_mesh(s, c, *st);
            continue;
        }
        if (save_needed(s, c, *st)) {
            submit_save(s, c, *st);   // edited while saving
            continue;
        }
        remove_cube(s, c);
    }
}

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

void step_waits(cv_stream* s) {
    while (!s->remote_wait.empty() && s->remote_wait.top().tick <= s->tick) {
        const Int3 c = s->remote_wait.top().c;
        s->remote_wait.pop();
        CubeState* st = find_cube(s, c);
        if (st == nullptr || (st->flags & kRemoteWait) == 0) continue;
        st->flags = static_cast<uint16_t>((st->flags & ~kRemoteWait) | kWaitOver);
        consider_mesh(s, c, *st);
    }
    while (!s->adopt_wait.empty() && s->adopt_wait.top().tick <= s->tick) {
        const Int3 c = s->adopt_wait.top().c;
        s->adopt_wait.pop();
        CubeState* st = find_cube(s, c);
        if (st != nullptr && st->level == kLevelNone && st->phase == kInstalled) s->unload_q.push_back(c);
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

void step_unload_select(cv_stream* s, int64_t max_unloads) {
    std::vector<Int3> deferred;
    int64_t n = 0;
    while (!s->unload_q.empty() && n < max_unloads) {
        const Int3 c = s->unload_q.front();
        s->unload_q.pop_front();
        CubeState* st = find_cube(s, c);
        if (st == nullptr || st->level != kLevelNone || st->phase != kInstalled) continue;
        if ((st->flags & kHeld) != 0 || ((st->flags & kAdopted) != 0 && s->tick - st->installed_tick < kAdoptGraceTicks)) {
            deferred.push_back(c);
            continue;
        }
        ColumnState* col = find_col(s, c.x, c.z);
        if (col != nullptr && col->ready && c.y >= col->ready_lo && c.y <= col->ready_hi) {
            col->ready = false;   // far LOD back first (this tick, before UNLOADED next tick)
            emit(s, CV_EV_COLUMN_UNREADY, -1, c.x, 0, c.z);
        }
        emit(s, CV_EV_UNLOAD_PENDING, -1, c.x, c.y, c.z);
        st->phase = kUnloadPending;
        st->pending_tick = s->tick;
        s->pending_unloads.push_back(c);
        touch(s, c.x, c.z);
        ++n;
    }
    for (const Int3& c : deferred) s->unload_q.push_back(c);
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

void release_handed(cv_stream* s) {
    for (auto& m : s->handed) give_mesh(s, m);
    s->handed.clear();
}

int64_t limit_of(int32_t v) { return v > 0 ? v : INT64_MAX; }

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

int32_t save_all_locked(cv_stream* s) {
    if (s->store == nullptr || s->remote) return CV_OK;
    s->saving_all = true;
    s->save_error = 0;
    // Populations in flight write into their cubes: let them finish so the snapshot is consistent.
    wait_for_jobs(s, [&] { return s->pop_in_flight == 0; });
    for (auto& [c, st] : s->cubes) {
        if (!installed(st) || (st.flags & kSaveInFlight) != 0) continue;
        if (cv_cube_revision(s->world, c.x, c.y, c.z) != st.saved_rev) submit_save(s, c, st);
    }
    wait_for_jobs(s, [&] { return s->save_in_flight == 0; });
    for (auto& [k, col] : s->columns) {
        if (!col.dirty || col.rec != 2 || col.saving) continue;
        col.saving = true;
        col.dirty = false;
        ++s->colsave_in_flight;
        Job j;
        j.kind = JobKind::SaveColumn;
        j.c = Int3{k.x, 0, k.z};
        j.populated = col.populated;
        submit(s, std::move(j));
    }
    wait_for_jobs(s, [&] { return s->colsave_in_flight == 0; });
    int32_t rc = s->save_error;
    const int32_t f = cv_store_flush(s->store);
    if (rc == 0 && f < 0) rc = f;
    s->saving_all = false;
    return rc;
}

} // namespace

// ---- column record envelope ----------------------------------------------------------------------------------

namespace cenda::voxel::streaming {

namespace {
uint32_t rd32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) | (static_cast<uint32_t>(p[2]) << 16) |
           (static_cast<uint32_t>(p[3]) << 24);
}
int64_t rd64(const uint8_t* p) {
    uint64_t v = 0;
    for (int i = 7; i >= 0; --i) v = (v << 8) | p[i];
    return static_cast<int64_t>(v);
}
void wr32(std::vector<uint8_t>& o, uint32_t v) {
    for (int i = 0; i < 4; ++i) o.push_back(static_cast<uint8_t>(v >> (8 * i)));
}
void wr64(std::vector<uint8_t>& o, int64_t v) {
    const auto u = static_cast<uint64_t>(v);
    for (int i = 0; i < 8; ++i) o.push_back(static_cast<uint8_t>(u >> (8 * i)));
}
} // namespace

bool parse_column_record(const uint8_t* p, size_t n, std::vector<int64_t>& populated, const uint8_t** core,
                         size_t* core_len) {
    populated.clear();
    if (p == nullptr || n < 12 || rd32(p) != kColumnMagic) return false;
    const uint32_t runs = rd32(p + 4);
    size_t at = 8;
    if (runs > (n - at) / 16) return false;
    int64_t prev_hi = 0;
    for (uint32_t i = 0; i < runs; ++i) {
        const int64_t lo = rd64(p + at), hi = rd64(p + at + 8);
        at += 16;
        if (lo > hi || (i > 0 && lo <= prev_hi) || hi - lo > (1 << 20)) return false;
        if (populated.size() + static_cast<size_t>(hi - lo + 1) > (size_t{1} << 22)) return false;
        for (int64_t y = lo;; ++y) {
            populated.push_back(y);
            if (y == hi) break;
        }
        prev_hi = hi;
    }
    if (n - at < 4) return false;
    const uint32_t len = rd32(p + at);
    at += 4;
    if (n - at != len) return false;
    *core = p + at;
    *core_len = len;
    return true;
}

void build_column_record(const std::vector<int64_t>& populated, const uint8_t* core, size_t core_len,
                         std::vector<uint8_t>& out) {
    out.clear();
    wr32(out, kColumnMagic);
    std::vector<std::pair<int64_t, int64_t>> runs;
    for (const int64_t y : populated) {
        if (!runs.empty() && runs.back().second + 1 == y) {
            runs.back().second = y;
        } else {
            runs.emplace_back(y, y);
        }
    }
    wr32(out, static_cast<uint32_t>(runs.size()));
    for (const auto& [lo, hi] : runs) {
        wr64(out, lo);
        wr64(out, hi);
    }
    wr32(out, static_cast<uint32_t>(core_len));
    out.insert(out.end(), core, core + core_len);
}

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

int64_t cv_stream_missing(cv_stream* s, int64_t* out_xyz, int64_t cap) {
    if (s == nullptr || cap < 0 || (cap > 0 && out_xyz == nullptr)) {
        cv_set_error("cv_stream_missing: bad arguments");
        return CV_ERR_ARG;
    }
    std::shared_ptr<const std::vector<LoadItem>> list;
    {
        std::lock_guard<std::mutex> g(s->list_mutex);
        list = s->missing_list;
    }
    if (!list) return 0;
    int64_t n = 0;
    for (const LoadItem& it : *list) {
        if (cv_cube_exists(s->world, it.c.x, it.c.y, it.c.z) == 1) continue;
        if (n < cap) {
            const auto k = static_cast<size_t>(n) * 3;
            out_xyz[k] = it.c.x;
            out_xyz[k + 1] = it.c.y;
            out_xyz[k + 2] = it.c.z;
        }
        ++n;
    }
    return n > cap ? CV_NEED(n) : n;
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
