// The streamer's worker pool (stream_impl.hpp "THREADS" WORKERS): the jobs - store reads, generation, population
// (confined to its 8 held cubes), meshing, saves, the planner's TOP requests - the job queue and the recycled
// buffers.
#include "stream_impl.hpp"
#include "stream_internal.hpp"
#include "cv_internal.hpp"
#include "mesh_gpu.hpp"
#include "stream_plan.hpp"

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <deque>
#include <memory>
#include <mutex>
#include <utility>
#include <vector>

namespace cenda::voxel::streaming {

namespace {

constexpr size_t kFreeCellsMax = 96;
constexpr size_t kFreeMeshesMax = 64;

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

} // namespace

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

namespace {

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

} // namespace

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

} // namespace cenda::voxel::streaming
