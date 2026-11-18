// Streamer planner (#121): turns the viewer set into per-cube LEVELS (voxel_world.h "STREAMER RULES") and
// diffs them against the previous plan. Runs on the stream's planner thread only (not thread-safe itself);
// it reads the world through thread-safe calls (cv_top_range, cv_cube_exists, the visibility walk).
//
// Representation: per column (cx, cz) a sorted list of cube-y RUNS with a level, so a plan over hundreds of
// thousands of cubes costs work per column, and the diff only expands columns whose runs changed.
//
// TOPS (2026-11-17): a column's TOP range of a never-generated column comes from the generator's
// column_surface, which can cost a millisecond or more. With set_async() (the stream) the planner never
// calls it itself: columns whose TOP only the callback can answer are PENDING - requested from the workers
// nearest first, left out of the plan (no ellipsoid, band or visible cubes; a neighbour's support may still
// reach into them) and planned by a later compute once their answer arrives. Adding a column only adds
// levels to its neighbours (support is a union), so these incremental plans never take a level back. The
// visibility walk (c) waits until no TOP is pending (it would ask the callback on this thread). Without
// set_async (tests, direct use) compute() resolves every TOP inline, as before.
//
// PRIORITY (2026-11-17): one distance order for every rule (LoadItem::key): the cube centre's 3D distance to
// the nearest viewer minus kBandBias for the surface band (and the cube above/below it that completes it),
// with the rule as the tie-break (band, ellipsoid, support, visible (c)) - so the columns next to the viewer
// go READY first and the ground around it appears before anything far away.
#pragma once

#include "cenda/voxel_world.h"
#include "../int3_key.hpp"
#include "mesh_gpu.hpp"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <functional>
#include <unordered_map>
#include <unordered_set>
#include <vector>
#include <utility>

namespace cenda::voxel::streaming {

struct Int2 {
    int64_t x = 0, z = 0;
    friend bool operator==(const Int2& a, const Int2& b) { return a.x == b.x && a.z == b.z; }
};
struct Int2Hash {
    size_t operator()(const Int2& k) const { return static_cast<size_t>(hash3(k.x, 0x2545f4914f6cdd1dLL, k.z)); }
};

enum : uint8_t { kLevelNone = 0, kLevelKeep = 1, kLevelSupport = 2, kLevelWanted = 3 };

struct Iv {
    int64_t lo, hi;   // inclusive cube y
};
using Ivs = std::vector<Iv>;

struct Run {
    int64_t lo, hi;   // inclusive
    uint8_t level;
    friend bool operator==(const Run& a, const Run& b) { return a.lo == b.lo && a.hi == b.hi && a.level == b.level; }
};
using Runs = std::vector<Run>;

struct ColumnPlan {
    Runs runs;
    bool has_band = false;
    int64_t band_lo = 0, band_hi = -1;   // inclusive cube y of the readiness band (wanted (b) cubes)
};

struct CubeChange {
    Int3 c;
    uint8_t level;
};
struct ColumnChange {
    Int2 c;
    bool tracked;     // the column has cubes of any level (its record should be loaded)
    bool has_band;
    int64_t band_lo, band_hi;
};
struct LoadItem {
    uint64_t key;     // lower = sooner: (biased distance in 1/16 blocks) << 2 | rule (see PRIORITY above)
    Int3 c;
};
// LoadItem::key rule (tie-break) values.
enum : uint64_t { kRuleBand = 0, kRuleEllipsoid = 1, kRuleSupport = 2, kRuleVisible = 3 };
constexpr double kBandBias = 32.0;   // blocks: the band (+ its completing cube above/below) is preferred by one cube
// A column's TOP request in the same order (workers run TOP and generation jobs by key, see stream_jobs.cpp
// worker_main): kTopLead blocks ahead of its band cubes, so the column is planned before its cubes are due.
constexpr double kTopLead = 64.0;
inline uint64_t top_request_key(double horizontal_d2) {
    const double d = std::max(0.0, std::sqrt(std::max(0.0, horizontal_d2)) - kBandBias - kTopLead);
    return static_cast<uint64_t>(std::min(d * 16.0, 2.0e18)) << 2;
}

struct PlanBatch {
    uint64_t seq = 0;
    std::vector<ColumnChange> columns;
    std::vector<CubeChange> cubes;
    std::vector<LoadItem> list;      // absent cubes to load, sorted by key
    int64_t wanted = 0, needed = 0, walk_cubes = 0;
    int64_t plan_us = 0;
    bool walked = false;          // the visibility walk ran (else it is still due)
    int64_t tops_pending = 0;     // columns left out of this plan for want of their TOP
};

struct TopInfo {
    bool known = false;
    bool pending = false;         // planner: the answer is being resolved on a worker (not part of the plan)
    int64_t top_min = 0, top_max = 0, sky_min = 0;
};

// The TOP range of column (cx, cz) as the planner uses it: cv_top_range, else (nothing known) the generator's
// column_surface directly. May call the surface callback: any thread, no lock held.
TopInfo resolve_top(cv_world* world, const cv_generator* gen, int64_t cx, int64_t cz);

class Planner {
public:
    Planner(cv_world* world, const cv_generator* gen, bool remote);

    // Computes the plan for `centers` at time now_ms (walking the visibility graph when `walk`) and writes
    // the diff against the previous compute into out.
    void compute(const std::vector<cv_center>& centers, int64_t now_ms, bool walk, PlanBatch& out);

    // Asynchronous TOP resolution (see TOPS above): `request` receives columns to resolve (c.y = 0) with their
    // top_request_key, nearest first (called from compute on the planner thread; it must not call back into
    // the planner). Every request is answered later through add_resolved (planner thread).
    using TopRequest = std::function<void(const std::vector<LoadItem>&)>;
    void set_async(TopRequest request) { request_ = std::move(request); }
    void add_resolved(const Int2& c, const TopInfo& t);
    // Requested TOPs not answered yet.
    size_t tops_pending() const { return requested_.size(); }

private:
    struct Work {
        Ivs wn, wk;          // wanted (normal shape) / wanted (hysteresis shape)
        Ivs n, k;            // needed (wanted + support) / keep
        TopInfo top;
        bool top_fetched = false;
        bool band = false;
        int64_t band_lo = 0, band_hi = -1;
    };

    // The column's TOP for this compute; pending (and queued for a request with priority d2) when only the
    // surface callback could answer and the planner is asynchronous.
    TopInfo top_of(int64_t cx, int64_t cz, double d2);
    void walk_viewers(const std::vector<cv_center>& centers, int64_t now_ms);
    void evict_resolved(const std::vector<cv_center>& centers);
    uint64_t key_of(const std::vector<cv_center>& centers, const Int3& c, const Work& w, uint8_t level) const;

    cv_world* world_;
    const cv_generator* gen_;
    bool remote_;
    int64_t cmin_, cmax_;   // cube-y bounds (inclusive)
    std::unordered_map<Int2, ColumnPlan, Int2Hash> prev_;
    std::unordered_map<Int2, TopInfo, Int2Hash> resolved_;   // callback answers of never-generated columns
    std::unordered_set<Int2, Int2Hash> requested_;           // asked of the workers, not answered yet
    std::unordered_map<Int2, double, Int2Hash> to_request_;  // this compute's new requests -> priority (d2)
    TopRequest request_;
    std::unordered_map<Int3, int64_t, Int3Hash> seen_;       // (c) cubes -> last time reported (ms)
    std::vector<std::vector<Int3>> walk_latest_;             // (c) cubes of the latest walk, per viewer
    VisibleWalk walk_buf_;
};

// ---- interval helpers (exposed for tests) -----------------------------------------------------------------
void normalize(Ivs& v);
void clip(Ivs& v, int64_t lo, int64_t hi);
// Levels: wn subset of n subset of k; runs ordered by lo, adjacent runs of equal level merged.
Runs build_runs(const Ivs& wn, const Ivs& n, const Ivs& k);
uint8_t level_at(const Runs& runs, int64_t cy);

} // namespace cenda::voxel::streaming
