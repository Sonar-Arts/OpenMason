// Private structures of the streamer (cv_stream_*, OpenMason #121; rules in voxel_world.h "STREAMER RULES").
//
// THREADS
//  - OWNER: cv_stream_tick (and save_all / destroy, which take the same owner mutex). Owns every field in
//    the "owner state" block; nothing else touches them.
//  - PLANNER: one thread (stream_plan.hpp) turning centers into level diffs + a load list, handed over in
//    `batches` under plan_mutex.
//  - WORKERS: desc.worker_threads threads running Jobs (store reads, generation, population, meshing,
//    saves) and handing Results back under done_mutex. Workers never touch owner state. TOP jobs (the
//    planner's column TOP requests, stream_plan.hpp "TOPS") answer the planner instead: top_results under
//    plan_mutex.
// Lock order: owner_mutex -> stats_mutex -> plan_mutex; owner_mutex -> {q_mutex | done_mutex | list_mutex |
// pool_mutex} (never held together). stats_get only TRY-locks owner_mutex. World calls (cv_*) take only the
// world's own locks and are never made while holding plan/q/done/list/stats/pool mutexes.
// Functions shared by the stream*.cpp files (and the map of which file holds what): stream_internal.hpp.
#pragma once

#include "cenda/voxel_world.h"
#include "../int3_key.hpp"
#include "cv_internal.hpp"
#include "mesh_gpu.hpp"
#include "stream_plan.hpp"

#include <atomic>
#include <condition_variable>
#include <deque>
#include <memory>
#include <mutex>
#include <queue>
#include <thread>
#include <unordered_map>
#include <unordered_set>
#include <vector>
#include <utility>

namespace cenda::voxel::streaming {

enum class JobKind : uint8_t { SaveCube = 0, SaveColumn, Populate, LoadColumn, Mesh, Top, Generate };
constexpr int kJobKinds = 7;   // worker priority order: saves first; Top and Generate last, between them by key

struct Job {
    JobKind kind = JobKind::Generate;
    Int3 c;
    bool skip_store = false;          // Generate: the record was corrupt, generate instead
    std::vector<int64_t> populated;   // SaveColumn: populated origin cy snapshot
    uint64_t epoch = 0;               // Mesh: the stream's mesh_epoch when queued (cv_stream_remesh_all)
    std::vector<Int3> origins;        // Populate: the chain of origins run in order (c = origins[0])
    uint64_t key = 0;                 // Generate / Top: LoadItem key order (worker_main runs the lower first)
};

// Store: a store record, Generated: generator output - either way PREPARED on the worker (cv_internal.hpp
// prepare_cube*: sections, masks, tracker leaves, callback answer), so the owner's install only swaps it in.
enum class GenKind : uint8_t { Error, IoError, Store, Generated };

struct MeshResult {
    GpuMeshBuild build;
    cv_gpu_mesh view{};
    Int3 c;
    int64_t bytes = 0;
    uint64_t prio = 0;
    uint64_t epoch = 0;               // mesh_epoch of its job: older ones are dropped, never handed out
    bool empty() const { return (build.flags & 1u) != 0; }
};

struct Result {
    JobKind kind = JobKind::Generate;
    Int3 c;
    int32_t status = 0;
    int64_t rev = 0;                  // SaveCube: revision the saved bytes are at least as new as
    GenKind gen = GenKind::Error;
    PreparedCube prepared;            // Generate: the cube, ready for install_prepared
    std::vector<uint8_t> bytes;       // LoadColumn: the column record
    std::unique_ptr<MeshResult> mesh;
    std::vector<Int3> origins;        // Populate: the origins the job ran
};

enum Phase : uint8_t {
    kAbsent = 0,       // tracked by level only
    kLoading,          // Generate job in flight
    kInstallReady,     // result waiting in install_q
    kInstalled,
    kUnloadPending,    // UNLOAD_PENDING emitted; removal next tick
    kUnloadSaving      // saving before removal
};

enum : uint16_t {
    kComplete = 1u << 0,     // every population writing into the cube has run (or adopted)
    kMeshed = 1u << 1,       // a first mesh was handed out or found empty (readiness)
    kHasMesh = 1u << 2,      // the host holds a non-empty mesh of it
    kMeshQueued = 1u << 3,
    kMeshInFlight = 1u << 4,
    kRemesh = 1u << 5,       // dirtied while its mesh was in flight
    kHeld = 1u << 6,         // held by a running population
    kSaveInFlight = 1u << 7,
    kAdopted = 1u << 8,      // installed by the host (REMOTE decode, host create)
    kRemoteWait = 1u << 9,   // in remote_wait
    kWaitOver = 1u << 10     // remote neighbour wait expired: mesh without waiting
};

struct CubeState {
    uint8_t level = 0;
    uint8_t phase = kAbsent;
    uint16_t flags = 0;
    uint32_t retries = 0;
    int64_t retry_tick = 0;
    int64_t saved_rev = 0;           // revision at load/last save; 0 = never saved
    int64_t installed_tick = 0;
    int64_t pending_tick = 0;
    int64_t seen_rev = 0;            // revision last reported to the host (INSTALLED, then CV_EV_CHANGED)
};

struct ColumnState {
    bool tracked = false;            // the plan has cubes in it: its record is wanted
    uint8_t rec = 0;                 // 0 not requested, 1 loading, 2 loaded (or nothing to load)
    bool dirty = false;              // record changed since load/save
    bool ready = false;              // COLUMN_READY emitted (and no UNREADY since)
    bool has_band = false;
    bool touched = false;
    bool saving = false;
    int64_t band_lo = 0, band_hi = -1;
    int64_t ready_lo = 0, ready_hi = -1;
    std::vector<int64_t> populated;  // sorted cy of populated origins
    int64_t states = 0;              // CubeState entries of this column
};

struct HeapItem {
    uint64_t key;
    Int3 c;
    friend bool operator>(const HeapItem& a, const HeapItem& b) { return a.key > b.key; }
};
struct TickItem {
    int64_t tick;
    Int3 c;
    friend bool operator>(const TickItem& a, const TickItem& b) { return a.tick > b.tick; }
};

// Envelope of the stream's column record (voxel_world.h "RECORDS").
constexpr uint32_t kColumnMagic = 0x31535643u;   // "CVS1"
bool parse_column_record(const uint8_t* p, size_t n, std::vector<int64_t>& populated, const uint8_t** core,
                         size_t* core_len);
void build_column_record(const std::vector<int64_t>& populated, const uint8_t* core, size_t core_len,
                         std::vector<uint8_t>& out);

} // namespace cenda::voxel::streaming

struct cv_stream {
    cv_world* world = nullptr;
    cv_store* store = nullptr;
    const cv_generator* gen = nullptr;
    bool remote = false;
    bool no_mesh = false;         // CV_STREAM_NO_MESH: no mesh jobs; readiness = installed + complete
    uint32_t mesh_flags = 0;      // CV_MESH_* passed to the mesher (CV_STREAM_NO_MESH stripped)
    int32_t worker_count = 1;
    int64_t cmin = 0, cmax = 0;   // cube-y bounds

    // ---- worker pool -------------------------------------------------------------------------------------
    std::mutex q_mutex;
    std::condition_variable q_cv;
    std::deque<cenda::voxel::streaming::Job> queue[cenda::voxel::streaming::kJobKinds];
    bool stopping = false;
    std::vector<std::thread> workers;

    std::mutex done_mutex;
    std::condition_variable done_cv;
    std::vector<std::unique_ptr<cenda::voxel::streaming::Result>> done;

    std::mutex pool_mutex;   // recycled buffers
    std::vector<std::unique_ptr<uint16_t[]>> free_cells;
    std::vector<std::unique_ptr<cenda::voxel::streaming::MeshResult>> free_meshes;

    std::atomic<int64_t> generated_total{0}, loaded_total{0}, saved_total{0}, errors_total{0};
    // Worker time per job kind (ns, jobs; Populate counts origins): the owner sizes its in-flight limits from
    // them (stream_tick.cpp "IN-FLIGHT LIMITS").
    std::atomic<int64_t> job_ns[cenda::voxel::streaming::kJobKinds] = {}, job_runs[cenda::voxel::streaming::kJobKinds] = {};

    // ---- planner -------------------------------------------------------------------------------------------
    std::thread planner_thread;
    std::mutex plan_mutex;
    std::condition_variable plan_cv;
    std::vector<cv_center> centers;
    uint64_t centers_version = 0;
    uint64_t planned_version = 0;
    bool tops_dirty = false;
    bool plan_stop = false;
    bool planning = false;
    std::deque<std::unique_ptr<cenda::voxel::streaming::PlanBatch>> batches;
    int64_t plans_total = 0;
    int64_t plan_us_last = 0;
    // Answers of TOP jobs for the planner (stream_plan.hpp "TOPS"); tops_outstanding counts requests not yet
    // handed to the planner (queued, running or waiting in top_results).
    std::vector<std::pair<cenda::voxel::streaming::Int2, cenda::voxel::streaming::TopInfo>> top_results;
    std::atomic<int64_t> tops_outstanding{0};

    std::mutex list_mutex;
    std::shared_ptr<const std::vector<cenda::voxel::streaming::LoadItem>> missing_list;

    // ---- owner state (owner_mutex) ------------------------------------------------------------------------
    std::mutex owner_mutex;
    int64_t tick = 0;
    std::unordered_map<cenda::Int3, cenda::voxel::streaming::CubeState, cenda::Int3Hash> cubes;
    std::unordered_map<cenda::voxel::streaming::Int2, cenda::voxel::streaming::ColumnState,
                       cenda::voxel::streaming::Int2Hash> columns;
    std::unique_ptr<cenda::voxel::streaming::PlanBatch> applying;
    size_t apply_col = 0, apply_cube = 0;
    std::shared_ptr<const std::vector<cenda::voxel::streaming::LoadItem>> active_list;
    size_t list_cursor = 0;
    std::deque<std::unique_ptr<cenda::voxel::streaming::Result>> install_q;
    std::vector<std::unique_ptr<cenda::voxel::streaming::MeshResult>> ready_meshes;
    std::vector<std::unique_ptr<cenda::voxel::streaming::MeshResult>> handed;   // this tick's slots
    std::vector<cv_event> events;
    std::priority_queue<cenda::voxel::streaming::HeapItem, std::vector<cenda::voxel::streaming::HeapItem>,
                        std::greater<cenda::voxel::streaming::HeapItem>> mesh_q;
    std::priority_queue<cenda::voxel::streaming::TickItem, std::vector<cenda::voxel::streaming::TickItem>,
                        std::greater<cenda::voxel::streaming::TickItem>> remote_wait, adopt_wait;
    // Population candidates (origins): pop_cand is the set, pop_q orders it nearest first (priority taken at
    // insertion; stale heap entries - no longer in the set - are skipped).
    std::unordered_set<cenda::Int3, cenda::Int3Hash> pop_cand;
    std::priority_queue<cenda::voxel::streaming::HeapItem, std::vector<cenda::voxel::streaming::HeapItem>,
                        std::greater<cenda::voxel::streaming::HeapItem>> pop_q;
    std::deque<cenda::Int3> unload_q;
    std::vector<cenda::Int3> pending_unloads;
    std::vector<cenda::Int3> resume_unloads;
    std::vector<cenda::voxel::streaming::Int2> touched;
    std::vector<int64_t> dirty_buf;
    std::vector<cenda::Int3> pop_changed;   // cubes of finished populations: CHANGED candidates (next step_dirty)
    uint64_t mesh_epoch = 0;                // bumped by cv_stream_remesh_all
    std::vector<cv_center> owner_centers;
    uint64_t owner_centers_version = ~uint64_t{0};
    bool tops_changed = false;
    int64_t last_tops_signal_ms = 0;
    int64_t gen_in_flight = 0, mesh_in_flight = 0, pop_in_flight = 0, save_in_flight = 0;
    int64_t colload_in_flight = 0, colsave_in_flight = 0;
    int64_t populated_total = 0, unloaded_total = 0, stale_total = 0, meshed_total = 0, level_changes = 0;
    int64_t plan_wanted = 0, plan_needed = 0, plan_walk = 0;
    int64_t tick_us_last = 0, tick_us_max = 0;
    // In-flight limits (owner): average job time per kind and the interval between ticks, both smoothed.
    double job_avg_us[cenda::voxel::streaming::kJobKinds] = {};
    int64_t job_ns_seen[cenda::voxel::streaming::kJobKinds] = {}, job_runs_seen[cenda::voxel::streaming::kJobKinds] = {};
    double tick_gap_us = 0;
    int64_t last_tick_start_us = 0;
    double install_avg_us = 0;   // owner time per install
    int64_t limit_gen = 0, limit_mesh = 0, limit_pop = 0;   // this tick's in-flight limits
    // Per-step owner time (diagnostics for tests/benchmarks): sum and max in microseconds, kSteps steps.
    static constexpr int kSteps = 12;
    int64_t step_us_sum[kSteps] = {}, step_us_max[kSteps] = {};
    bool saving_all = false;
    int32_t save_error = 0;

    mutable std::mutex stats_mutex;
    cv_stream_stats stats{};
};
