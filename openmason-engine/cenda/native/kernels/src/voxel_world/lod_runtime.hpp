// Private structures of the octree LOD RUNTIME (the cv_lod object; lod.cpp, lod_sources.cpp, lod_dirty.cpp,
// lod_persist.cpp, lod_select.cpp, lod_owner.cpp): struct cv_lod, the types its threads share, and the functions
// one runtime file calls in another. Node data / meshes (and the threads + locks map): lod_internal.hpp.
#pragma once

#include "lod_internal.hpp"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <deque>
#include <list>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>

namespace cenda::voxel::lod {

struct PrepJob {
    NodeKey key;
    uint64_t serial = 0;
};
struct PrepResult {
    NodeKey key;
    uint64_t serial = 0;
    bool complete = false;
    uint64_t digest = 0;
    MeshPtr mesh;   // null when incomplete
};

// Planner-side state of a node the selection asked about.
struct NodeState {
    bool known = false;        // a prepare result arrived (complete/digest/mesh below are valid)
    bool complete = false;
    uint64_t digest = 0;
    uint64_t want = 1;         // bumps when the node or a neighbour changed
    uint64_t have = 0;         // `want` of the applied result: stale while have != want
    uint64_t inflight = 0;     // `want` of the queued / running prepare job (0: none)
    MeshPtr mesh;              // latest built mesh (complete nodes)
    int32_t sent = 0;          // references from the planner's last commits (keeps the state)
    uint64_t touched = 0;      // selection epoch
    bool stale() const { return have != want; }
};

struct Group {
    std::vector<NodeKey> nodes;      // sorted
    std::vector<FootKey> fallbacks;  // sorted
    std::vector<FootKey> stacks;     // sorted: footprints drawn as stacks (also those whose nodes are all empty)
    bool empty() const { return nodes.empty() && fallbacks.empty() && stacks.empty(); }
};

// A built stack (every node data complete and meshed), published by the planner for the owner's patches.
struct Stack {
    std::vector<NodeKey> nodes;      // non-empty meshes only
    std::vector<MeshPtr> meshes;
};
using StackPtr = std::shared_ptr<const Stack>;

class StackTable {
public:
    StackPtr get(const FootKey& f) const {
        const Shard& s = shard(f);
        std::lock_guard<std::mutex> g(s.m);
        auto it = s.map.find(f);
        return it == s.map.end() ? nullptr : it->second;
    }
    void put(const FootKey& f, StackPtr p) {
        Shard& s = shard(f);
        std::lock_guard<std::mutex> g(s.m);
        if (p) s.map[f] = std::move(p);
        else s.map.erase(f);
    }

private:
    struct Shard {
        mutable std::mutex m;
        std::unordered_map<FootKey, StackPtr, FootKeyHash> map;
    };
    const Shard& shard(const FootKey& f) const { return shards_[FootKeyHash{}(f) & 31]; }
    Shard& shard(const FootKey& f) { return shards_[FootKeyHash{}(f) & 31]; }
    Shard shards_[32];
};

// The planner's last commit of a root (to skip identical ones).
struct SentGroup {
    Group g;
    std::vector<MeshPtr> meshes;     // parallel to g.nodes
};

// One root's cover computed by the planner: replaces whatever the owner draws in that root.
struct Commit {
    FootKey root;
    uint64_t seq = 0;                // readiness changes the selection saw
    uint64_t view_version = 0;
    int32_t root_level = 0;
    double d = 0;
    Group g;
    std::vector<MeshPtr> meshes;     // parallel to g.nodes
    std::vector<cv_lod_viewer> viewers;
    int32_t radius = 0;
};
using CommitPtr = std::shared_ptr<const Commit>;

struct ReadyChange {
    uint64_t seq;
    int64_t cx, cz;
    bool ready;
};

// A footprint the host draws: a fallback, or a stack (its non-empty nodes).
struct DrawnFoot {
    bool fallback = false;
    std::vector<NodeKey> nodes;
};

// First touch of a drawn node within a tick (net events).
struct TickNode {
    bool was_drawn = false;
    MeshPtr was_mesh;
};

struct Candidate {
    double d;
    NodeKey key;
};

struct ColumnKeyHash {
    size_t operator()(const std::pair<int64_t, int64_t>& k) const {
        return static_cast<size_t>(mix64(hash3(k.first, 0x5bd1e995, k.second)));
    }
};

inline int64_t now_us() {
    return std::chrono::duration_cast<std::chrono::microseconds>(
               std::chrono::steady_clock::now().time_since_epoch()).count();
}

inline int64_t span(int32_t level) { return int64_t{kEdge} << level; }

inline NodeKey neighbour(const NodeKey& k, int f) {
    static constexpr int dx[6] = {0, 0, 0, 0, 1, -1};
    static constexpr int dy[6] = {1, -1, 0, 0, 0, 0};
    static constexpr int dz[6] = {0, 0, -1, 1, 0, 0};
    return NodeKey{k.level, k.x + dx[f], k.y + dy[f], k.z + dz[f]};
}

// 2D footprint helpers.
inline int64_t foot_total(int32_t level) { return int64_t{1} << (2 * level); }
inline FootKey foot_child(const FootKey& f, int i) { return FootKey{f.level - 1, f.x * 2 + (i & 1), f.z * 2 + (i >> 1)}; }
inline FootKey foot_at(const FootKey& f, int32_t level) {   // ancestor (level >= f.level)
    const int sh = level - f.level;
    return FootKey{level, f.x >> sh, f.z >> sh};
}
inline FootKey foot_of(const NodeKey& k) { return FootKey{k.level, k.x, k.z}; }

template <typename T>
void sort_unique(std::vector<T>& v) {
    std::sort(v.begin(), v.end());
    v.erase(std::unique(v.begin(), v.end()), v.end());
}

} // namespace cenda::voxel::lod

struct cv_lod {
    uint64_t serial = 0;   // process-unique: keys per-thread caches (a freed LOD's address can be reused)
    cv_world* world = nullptr;
    cv_store* cube_store = nullptr;
    std::string dir;
    bool persist = false;
    int64_t min_y = 0, max_y = 0;
    uint16_t air = 0, fill = 0;
    int32_t max_level = 1;
    uint64_t content_version = 0;

    // ---- caches + stores (leaf locks) --------------------------------------------------------------------
    // Levels >= 1 and level 0 (re-derivable from the loaded cube) are cached apart, so a big loaded area cannot
    // evict the levels that are expensive to rebuild. Level 0 gets a quarter of desc.cache_bytes.
    cenda::voxel::lod::NodeCache cache{192u << 20};
    cenda::voxel::lod::NodeCache cache0{64u << 20};
    std::mutex col_m;
    std::unordered_map<std::pair<int64_t, int64_t>, cenda::voxel::lod::ColumnPtr, cenda::voxel::lod::ColumnKeyHash>
        columns;
    // bumps on every note
    std::unordered_map<std::pair<int64_t, int64_t>, uint64_t, cenda::voxel::lod::ColumnKeyHash> col_ver;
    // last ColumnInfo digest
    std::unordered_map<std::pair<int64_t, int64_t>, uint64_t, cenda::voxel::lod::ColumnKeyHash> col_seen;
    std::mutex store_m;
    cv_store* stores[cenda::voxel::lod::kLevels] = {};
    std::mutex stripes[cenda::voxel::lod::kLevels][64];
    std::mutex class_m;
    std::shared_ptr<const std::vector<uint8_t>> overrides = std::make_shared<std::vector<uint8_t>>(65536, 0);
    std::atomic<uint64_t> overrides_version{1};

    // ---- work (m) ----------------------------------------------------------------------------------------
    std::mutex m;
    std::condition_variable work_cv, idle_cv;
    std::unordered_set<cenda::voxel::lod::NodeKey, cenda::voxel::lod::NodeKeyHash> dirty[cenda::voxel::lod::kLevels];
    std::list<cenda::voxel::lod::NodeKey> dirty_q[cenda::voxel::lod::kLevels];
    std::unordered_set<cenda::voxel::lod::NodeKey, cenda::voxel::lod::NodeKeyHash> running;   // rebuilds in flight
    std::deque<cenda::voxel::lod::PrepJob> prep_q;
    int64_t prep_running = 0;
    // noted columns to re-derive
    std::unordered_set<std::pair<int64_t, int64_t>, cenda::voxel::lod::ColumnKeyHash> col_check;
    std::deque<std::pair<int64_t, int64_t>> col_q;
    int64_t col_running = 0;
    // Notes of columns that are still generating (not READY) are HELD until the column settles: READY, quiet
    // for kHoldQuietUs, held for kHoldMaxUs, or cv_lod_wait_idle. A generating column's facts and cubes move
    // with every install / population / neighbour re-mesh; building its nodes before it settles only churns.
    struct Held {
        int64_t first_us = 0, last_us = 0;
        std::vector<int64_t> cys;
    };
    std::unordered_map<std::pair<int64_t, int64_t>, Held, cenda::voxel::lod::ColumnKeyHash> held;
    // mirror of the owner's set
    std::unordered_set<std::pair<int64_t, int64_t>, cenda::voxel::lod::ColumnKeyHash> ready_cols;
    int64_t held_cubes = 0;
    // Levels above the active level (the coarsest level any view draws; desc.max_level until a view is set) are
    // not rebuilt eagerly: a dirty node there is only remembered (def_m, leaf lock) and rebuilt when something
    // reads it (get_data), so an edit never cascades up to max_level (a level-8 parent spans 8192 blocks of mostly
    // unexplored land: building it from scratch enumerates its whole subtree). Persisted in "deferred.bin".
    std::atomic<int32_t> active_level{1};
    std::mutex def_m;
    std::unordered_set<cenda::voxel::lod::NodeKey, cenda::voxel::lod::NodeKeyHash> deferred;
    bool stopping = false;
    uint64_t picks = 0;
    std::vector<cenda::voxel::lod::PrepResult> done;
    std::vector<cenda::voxel::lod::NodeKey> changed;   // data changed: re-mesh the node and its neighbours
    bool changed_overflow = false;  // too many notices without a tick: everything is re-meshed
    std::vector<std::thread> workers;

    // ---- revision watch (watch_m, leaf) --------------------------------------------------------------------
    // Loaded cubes the LOD read, with the revision it read: writes nobody noted (population into SUPPORT cubes,
    // host edits) are found by the scanner thread; UNLOAD_PENDING / REMESHED of an unchanged cube cost nothing.
    std::mutex watch_m;
    std::unordered_map<cenda::Int3, int64_t, cenda::Int3Hash> watch;
    std::thread scanner;
    std::condition_variable scan_cv;

    // ---- view (view_m) -----------------------------------------------------------------------------------
    std::mutex view_m;
    std::vector<cv_lod_viewer> viewers;
    cv_lod_policy policy{};
    uint64_t view_version = 0;

    // ---- planner (the selection thread; nothing else touches these) ----------------------------------------
    std::thread planner;
    std::unordered_map<cenda::voxel::lod::NodeKey, cenda::voxel::lod::NodeState, cenda::voxel::lod::NodeKeyHash> states;
    // level-0 footprints = columns
    std::unordered_set<cenda::voxel::lod::FootKey, cenda::voxel::lod::FootKeyHash> p_ready;
    std::unordered_map<cenda::voxel::lod::FootKey, int64_t, cenda::voxel::lod::FootKeyHash> p_ready_count;
    std::vector<cv_lod_viewer> p_viewers;
    cv_lod_policy p_policy{};
    uint64_t p_view_version = 0;
    bool p_have_view = false;
    int32_t p_root_level = -1;
    uint64_t p_seq = 0;                                // readiness changes applied
    uint64_t epoch = 0;
    std::unordered_set<cenda::voxel::lod::FootKey, cenda::voxel::lod::FootKeyHash> p_dirty_roots;
    std::unordered_map<cenda::voxel::lod::FootKey, uint64_t, cenda::voxel::lod::FootKeyHash> p_root_epoch;
    // last commit per root
    std::unordered_map<cenda::voxel::lod::FootKey, cenda::voxel::lod::SentGroup, cenda::voxel::lod::FootKeyHash> p_sent;
    // requests per root
    std::unordered_map<cenda::voxel::lod::FootKey, std::vector<cenda::voxel::lod::Candidate>,
                       cenda::voxel::lod::FootKeyHash> p_cands;
    // stacks whose built status may have moved
    std::unordered_set<cenda::voxel::lod::FootKey, cenda::voxel::lod::FootKeyHash> p_feet_touched;
    std::vector<cenda::voxel::lod::Candidate> candidates;                 // scratch of the root being selected
    int64_t p_last_prune_us = 0;
    std::atomic<int64_t> p_select_us{0}, p_standby{0}, p_states{0}, p_resident_bytes{0};
    // built stacks, published for the owner's patches
    cenda::voxel::lod::StackTable stacks;

    // ---- planner <-> owner (m) -----------------------------------------------------------------------------
    std::condition_variable plan_cv;
    bool plan_wake = false;
    bool plan_busy = false;
    std::vector<cenda::voxel::lod::ReadyChange> ready_in;                 // owner -> planner, in seq order
    // owner -> planner: roots the owner re-covered itself
    std::vector<cenda::voxel::lod::FootKey> owner_touched;
    // planner -> owner, latest per root
    std::map<cenda::voxel::lod::FootKey, cenda::voxel::lod::CommitPtr> commits;
    uint64_t planner_seq = 0;                          // p_seq published with the commits

    // ---- owner -------------------------------------------------------------------------------------------
    // level-0 footprints = columns
    std::unordered_set<cenda::voxel::lod::FootKey, cenda::voxel::lod::FootKeyHash> ready;
    std::unordered_map<cenda::voxel::lod::FootKey, int64_t, cenda::voxel::lod::FootKeyHash> ready_count;
    uint64_t ready_seq = 0;
    // changes newer than the oldest outstanding commit
    std::deque<cenda::voxel::lod::ReadyChange> ready_log;
    std::vector<std::pair<int64_t, int64_t>> ready_todo;   // columns to re-check at the next tick
    // the cover the host draws
    std::unordered_map<cenda::voxel::lod::FootKey, cenda::voxel::lod::DrawnFoot, cenda::voxel::lod::FootKeyHash> drawn;
    std::unordered_map<cenda::voxel::lod::FootKey,
                       std::unordered_set<cenda::voxel::lod::FootKey, cenda::voxel::lod::FootKeyHash>,
                       cenda::voxel::lod::FootKeyHash> root_feet;
    // drawn footprints strictly inside (<= root)
    std::unordered_map<cenda::voxel::lod::FootKey, int32_t, cenda::voxel::lod::FootKeyHash> below;
    // drawn nodes
    std::unordered_map<cenda::voxel::lod::NodeKey, cenda::voxel::lod::MeshPtr, cenda::voxel::lod::NodeKeyHash>
        node_mesh;
    std::unordered_map<cenda::voxel::lod::NodeKey, cenda::voxel::lod::TickNode, cenda::voxel::lod::NodeKeyHash>
        tick_nodes;
    std::unordered_map<cenda::voxel::lod::FootKey, bool, cenda::voxel::lod::FootKeyHash> tick_fb;
    // commits waiting for budget
    std::map<cenda::voxel::lod::FootKey, cenda::voxel::lod::CommitPtr> pending;
    int32_t groups_level = -1;
    std::vector<cv_lod_viewer> o_viewers;
    cv_lod_policy o_policy{};
    uint64_t o_view_version = 0;
    uint64_t min_view_version = 0;                     // commits of older views are refused
    bool have_view = false;
    int64_t o_drawn_nodes = 0, o_drawn_bytes = 0, o_fallbacks = 0;
    std::vector<cv_lod_event> events;
    std::vector<cenda::voxel::lod::MeshPtr> slots;
    std::vector<cv_lod_mesh> slot_views;

    // ---- stats -------------------------------------------------------------------------------------------
    std::atomic<int64_t> built_total{0}, loaded_total{0}, saved_total{0}, meshes_total{0};
    std::atomic<int64_t> errors_total{0}, corrupt_total{0};
    std::atomic<int32_t> first_store_error{0};
    std::mutex stats_m;
    cv_lod_stats o_stats{};
    int64_t tick_us_max = 0;
};

namespace cenda::voxel::lod {

// lod.cpp
int32_t fail_arg(const char* message);

// lod_sources.cpp
ColumnPtr column_info(cv_lod* L, int64_t cx, int64_t cz, bool check = false);
NodePtr get_data(cv_lod* L, const NodeKey& k, bool build);
int rebuild(cv_lod* L, const NodeKey& k);
void prepare(cv_lod* L, const NodeKey& k, PrepResult& out);

// lod_dirty.cpp
void note_or_hold_locked(cv_lod* L, int64_t cx, int64_t cy, int64_t cz, int64_t now);
void release_held_locked(cv_lod* L, const std::pair<int64_t, int64_t>& col);
void release_settled_locked(cv_lod* L, int64_t now, bool all);
void column_changed(cv_lod* L, int64_t cx, int64_t cz);
bool any_dirty(const cv_lod* L);
int64_t scan_once(cv_lod* L);
void scanner_main(cv_lod* L);
bool watch_unchanged(cv_lod* L, int64_t cx, int64_t cy, int64_t cz);
int watch_check(cv_lod* L, int64_t cx, int64_t cy, int64_t cz);
void worker_main(cv_lod* L);
void set_active_level(cv_lod* L, int32_t level);

// lod_persist.cpp
int32_t open_meta(cv_lod* L, bool reset_on_mismatch);
void save_deferred(cv_lod* L);
void load_deferred(cv_lod* L);

// lod_select.cpp
double foot_dmin(const FootKey& f, const std::vector<cv_lod_viewer>& viewers);
double foot_dmax(const FootKey& f, const std::vector<cv_lod_viewer>& viewers);
void roots_in_range(const std::vector<cv_lod_viewer>& viewers, const cv_lod_policy& P, std::vector<FootKey>& out);
void planner_main(cv_lod* L);
void wake_planner_locked(cv_lod* L);

// lod_owner.cpp
void slot_view(const MeshBuild& m, cv_lod_mesh& v);
void set_ready(cv_lod* L, int64_t cx, int64_t cz, bool ready);

} // namespace cenda::voxel::lod
