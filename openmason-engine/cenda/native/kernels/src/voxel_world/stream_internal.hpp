// Functions shared by the streamer's source files (cv_stream_*, OpenMason #121; split by #146). Kept out of
// stream_impl.hpp, which the ctest voxel_stream includes to inspect owner state.
//   stream.cpp         lifecycle (create/destroy), stats, the cv_stream_* C ABI
//   stream_jobs.cpp    worker pool: jobs (store reads, generation, population, meshing, saves, TOPs), buffer pools
//   stream_planner.cpp planner thread, owner-side application of its plan batches
//   stream_state.cpp   owner helpers: cube/column state, priority, population origins, meshing rules, installs
//   stream_tick.cpp    owner tick: completions, installs, dirty cubes, dispatch, mesh hand-out, readiness
//   stream_save.cpp    column records, unloads + saves, save_all, the column record envelope
//   stream_remote.cpp  host-installed cubes (adoption), REMOTE neighbour wait, cv_stream_missing
#pragma once

#include "stream_impl.hpp"

#include <chrono>
#include <cstdint>
#include <memory>
#include <mutex>
#include <vector>

namespace cenda::voxel::streaming {

constexpr int64_t kRemoteWaitTicks = 20;    // REMOTE: mesh without missing wanted neighbours after this
constexpr int64_t kAdoptGraceTicks = 40;    // host-installed cubes the plan does not want stay this long

inline int64_t now_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
               std::chrono::steady_clock::now().time_since_epoch())
        .count();
}
inline int64_t now_ns() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now().time_since_epoch())
        .count();
}
inline int64_t now_us() {
    return std::chrono::duration_cast<std::chrono::microseconds>(
               std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

inline Int3 add3(const Int3& a, int64_t dx, int64_t dy, int64_t dz) { return Int3{a.x + dx, a.y + dy, a.z + dz}; }

// ---- owner helpers (stream_state.cpp; the small lookups inline) ---------------------------------------------

inline CubeState* find_cube(cv_stream* s, const Int3& c) {
    const auto it = s->cubes.find(c);
    return it == s->cubes.end() ? nullptr : &it->second;
}
inline ColumnState* find_col(cv_stream* s, int64_t x, int64_t z) {
    const auto it = s->columns.find(Int2{x, z});
    return it == s->columns.end() ? nullptr : &it->second;
}
inline void emit(cv_stream* s, int32_t type, int32_t mesh, int64_t x, int64_t y, int64_t z) {
    s->events.push_back(cv_event{type, mesh, x, y, z});
}
inline bool cube_in(const cv_stream* s, int64_t cy) { return cy >= s->cmin && cy <= s->cmax; }
inline bool installed(const CubeState& st) {
    return st.phase == kInstalled || st.phase == kUnloadPending || st.phase == kUnloadSaving;
}

// ---- stream_jobs.cpp ----------------------------------------------------------------------------------------
void give_mesh(cv_stream* s, std::unique_ptr<MeshResult>& m);
void recycle(cv_stream* s, std::unique_ptr<Result>& r);
void worker_main(cv_stream* s);
void submit(cv_stream* s, Job&& job);
void submit_all(cv_stream* s, std::vector<Job>& jobs);

// ---- stream_planner.cpp -------------------------------------------------------------------------------------
void planner_main(cv_stream* s);
void apply_plans(cv_stream* s);

// ---- stream_state.cpp ---------------------------------------------------------------------------------------
void touch(cv_stream* s, int64_t x, int64_t z);
CubeState& get_cube(cv_stream* s, const Int3& c);
void erase_cube(cv_stream* s, const Int3& c);
uint64_t prio_of(cv_stream* s, const Int3& c);
void held_union(const std::vector<Int3>& origins, std::vector<Int3>& out);
bool origin_done(cv_stream* s, const Int3& o);
void enqueue_mesh(cv_stream* s, const Int3& c, CubeState& st);
// DETAIL RADIUS: whether cube c's next mesh is built with CV_MESH_DETAIL (had = its current mesh is).
bool want_detail(const cv_stream* s, const Int3& c, bool had);
// Records the detail bits of a handed-out (or found-empty) mesh and re-queues it when its mode is stale.
void note_detail(cv_stream* s, const Int3& c, CubeState& st, uint16_t build_flags);
// Re-evaluates the detail cubes when the viewers moved or the radius changed (owner tick).
void step_detail(cv_stream* s);
void consider_mesh(cv_stream* s, const Int3& c, CubeState& st);
void request_remesh(cv_stream* s, const Int3& c, CubeState& st);
void note_changed(cv_stream* s, const Int3& c, CubeState& st);
void check_complete(cv_stream* s, const Int3& c, CubeState& st);
void add_origin_candidates(cv_stream* s, const Int3& c);
void on_installed(cv_stream* s, const Int3& c, CubeState& st, bool adopted);

// ---- stream_save.cpp ----------------------------------------------------------------------------------------
void request_column(cv_stream* s, const Int2& k, ColumnState& col);
void apply_column_record(cv_stream* s, const Int3& k, ColumnState& col, const Result& r);
void step_pending_unloads(cv_stream* s);
void step_unload_select(cv_stream* s, int64_t max_unloads);
int32_t save_all_locked(cv_stream* s);

// ---- stream_remote.cpp --------------------------------------------------------------------------------------
bool neighbours_ok(cv_stream* s, const Int3& c);
void adopt(cv_stream* s, const Int3& c);
void step_waits(cv_stream* s);

// ---- stream_tick.cpp ----------------------------------------------------------------------------------------
void process_completions(cv_stream* s);
void release_handed(cv_stream* s);
int32_t tick_locked(cv_stream* s, const cv_budget* budget);

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

} // namespace cenda::voxel::streaming
