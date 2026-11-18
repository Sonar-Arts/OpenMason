// Octree LOD runtime (cv_lod_*, OpenMason #122 / Phase 5): workers rebuilding dirty nodes bottom-up and
// preparing requested ones (data + mesh), the per-level node stores, and the owner-side selection + handover
// that turns viewers, band policy and column readiness into SHOW / UPDATE / HIDE / FALLBACK events. Contract:
// voxel_world.h "LOD"; private structures: lod_internal.hpp.
#include "lod_internal.hpp"
#include "cv_error.hpp"
#include "mesh_internal.hpp"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdio>
#include <cstddef>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <list>
#include <limits>
#include <map>
#include <string>
#include <thread>
#include <unordered_set>

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

} // namespace cenda::voxel::lod

using namespace cenda::voxel::lod;
using cenda::Int3;
using cenda::mix64;
using cenda::floor_div64;

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
    NodeCache cache{192u << 20};
    NodeCache cache0{64u << 20};
    std::mutex col_m;
    std::unordered_map<std::pair<int64_t, int64_t>, ColumnPtr, ColumnKeyHash> columns;
    std::unordered_map<std::pair<int64_t, int64_t>, uint64_t, ColumnKeyHash> col_ver;    // bumps on every note
    std::unordered_map<std::pair<int64_t, int64_t>, uint64_t, ColumnKeyHash> col_seen;   // last ColumnInfo digest
    std::mutex store_m;
    cv_store* stores[kLevels] = {};
    std::mutex stripes[kLevels][64];
    std::mutex class_m;
    std::shared_ptr<const std::vector<uint8_t>> overrides = std::make_shared<std::vector<uint8_t>>(65536, 0);
    std::atomic<uint64_t> overrides_version{1};

    // ---- work (m) ----------------------------------------------------------------------------------------
    std::mutex m;
    std::condition_variable work_cv, idle_cv;
    std::unordered_set<NodeKey, NodeKeyHash> dirty[kLevels];
    std::list<NodeKey> dirty_q[kLevels];
    std::unordered_set<NodeKey, NodeKeyHash> running;   // rebuilds in flight
    std::deque<PrepJob> prep_q;
    int64_t prep_running = 0;
    std::unordered_set<std::pair<int64_t, int64_t>, ColumnKeyHash> col_check;   // noted columns to re-derive
    std::deque<std::pair<int64_t, int64_t>> col_q;
    int64_t col_running = 0;
    // Notes of columns that are still generating (not READY) are HELD until the column settles: READY, quiet
    // for kHoldQuietUs, held for kHoldMaxUs, or cv_lod_wait_idle. A generating column's facts and cubes move
    // with every install / population / neighbour re-mesh; building its nodes before it settles only churns.
    struct Held {
        int64_t first_us = 0, last_us = 0;
        std::vector<int64_t> cys;
    };
    std::unordered_map<std::pair<int64_t, int64_t>, Held, ColumnKeyHash> held;
    std::unordered_set<std::pair<int64_t, int64_t>, ColumnKeyHash> ready_cols;   // mirror of the owner's set
    int64_t held_cubes = 0;
    // Levels above the active level (the coarsest level any view draws; desc.max_level until a view is set) are
    // not rebuilt eagerly: a dirty node there is only remembered (def_m, leaf lock) and rebuilt when something
    // reads it (get_data), so an edit never cascades up to max_level (a level-8 parent spans 8192 blocks of mostly
    // unexplored land: building it from scratch enumerates its whole subtree). Persisted in "deferred.bin".
    std::atomic<int32_t> active_level{1};
    std::mutex def_m;
    std::unordered_set<NodeKey, NodeKeyHash> deferred;
    bool stopping = false;
    uint64_t picks = 0;
    std::vector<PrepResult> done;
    std::vector<NodeKey> changed;   // data changed: re-mesh the node and its neighbours
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
    std::unordered_map<NodeKey, NodeState, NodeKeyHash> states;
    std::unordered_set<FootKey, FootKeyHash> p_ready;  // level-0 footprints = columns
    std::unordered_map<FootKey, int64_t, FootKeyHash> p_ready_count;
    std::vector<cv_lod_viewer> p_viewers;
    cv_lod_policy p_policy{};
    uint64_t p_view_version = 0;
    bool p_have_view = false;
    int32_t p_root_level = -1;
    uint64_t p_seq = 0;                                // readiness changes applied
    uint64_t epoch = 0;
    std::unordered_set<FootKey, FootKeyHash> p_dirty_roots;
    std::unordered_map<FootKey, uint64_t, FootKeyHash> p_root_epoch;
    std::unordered_map<FootKey, SentGroup, FootKeyHash> p_sent;   // last commit per root
    std::unordered_map<FootKey, std::vector<Candidate>, FootKeyHash> p_cands;   // requests per root
    std::unordered_set<FootKey, FootKeyHash> p_feet_touched;      // stacks whose built status may have moved
    std::vector<Candidate> candidates;                 // scratch of the root being selected
    int64_t p_last_prune_us = 0;
    std::atomic<int64_t> p_select_us{0}, p_standby{0}, p_states{0}, p_resident_bytes{0};
    StackTable stacks;                                 // built stacks, published for the owner's patches

    // ---- planner <-> owner (m) -----------------------------------------------------------------------------
    std::condition_variable plan_cv;
    bool plan_wake = false;
    bool plan_busy = false;
    std::vector<ReadyChange> ready_in;                 // owner -> planner, in seq order
    std::vector<FootKey> owner_touched;                // owner -> planner: roots the owner re-covered itself
    std::map<FootKey, CommitPtr> commits;              // planner -> owner, latest per root
    uint64_t planner_seq = 0;                          // p_seq published with the commits

    // ---- owner -------------------------------------------------------------------------------------------
    std::unordered_set<FootKey, FootKeyHash> ready;    // level-0 footprints = columns
    std::unordered_map<FootKey, int64_t, FootKeyHash> ready_count;
    uint64_t ready_seq = 0;
    std::deque<ReadyChange> ready_log;                 // changes newer than the oldest outstanding commit
    std::vector<std::pair<int64_t, int64_t>> ready_todo;   // columns to re-check at the next tick
    std::unordered_map<FootKey, DrawnFoot, FootKeyHash> drawn;   // the cover the host draws
    std::unordered_map<FootKey, std::unordered_set<FootKey, FootKeyHash>, FootKeyHash> root_feet;
    std::unordered_map<FootKey, int32_t, FootKeyHash> below;     // drawn footprints strictly inside (<= root)
    std::unordered_map<NodeKey, MeshPtr, NodeKeyHash> node_mesh; // drawn nodes
    std::unordered_map<NodeKey, TickNode, NodeKeyHash> tick_nodes;
    std::unordered_map<FootKey, bool, FootKeyHash> tick_fb;
    std::map<FootKey, CommitPtr> pending;              // commits waiting for budget
    int32_t groups_level = -1;
    std::vector<cv_lod_viewer> o_viewers;
    cv_lod_policy o_policy{};
    uint64_t o_view_version = 0;
    uint64_t min_view_version = 0;                     // commits of older views are refused
    bool have_view = false;
    int64_t o_drawn_nodes = 0, o_drawn_bytes = 0, o_fallbacks = 0;
    std::vector<cv_lod_event> events;
    std::vector<MeshPtr> slots;
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

namespace {

int64_t now_us() {
    return std::chrono::duration_cast<std::chrono::microseconds>(
               std::chrono::steady_clock::now().time_since_epoch()).count();
}

int32_t fail_arg(const char* message) {
    cv_set_error("%s", message);
    return CV_ERR_ARG;
}

inline int64_t span(int32_t level) { return int64_t{kEdge} << level; }

// Node y range inside [min_y, max_y)?
bool node_in_range(const cv_lod* L, const NodeKey& k) {
    const int64_t y0 = node_origin(k.y, k.level);
    return y0 < L->max_y && y0 + span(k.level) > L->min_y;
}

void stack_range(const cv_lod* L, int32_t level, int64_t* lo, int64_t* hi) {
    *lo = floor_div64(L->min_y, span(level));
    *hi = floor_div64(L->max_y - 1, span(level));
}

NodeKey neighbour(const NodeKey& k, int f) {
    static constexpr int dx[6] = {0, 0, 0, 0, 1, -1};
    static constexpr int dy[6] = {1, -1, 0, 0, 0, 0};
    static constexpr int dz[6] = {0, 0, -1, 1, 0, 0};
    return NodeKey{k.level, k.x + dx[f], k.y + dy[f], k.z + dz[f]};
}

std::mutex& stripe(cv_lod* L, const NodeKey& k) {
    return L->stripes[k.level][NodeKeyHash{}(k) & 63];
}

// ---- classification ------------------------------------------------------------------------------------------
const ClassTable& class_table(cv_lod* L) {
    struct Cached {
        uint64_t lod = 0;
        const uint16_t* flags = nullptr;
        uint64_t cfg_version = ~uint64_t{0};
        uint64_t ovr_version = 0;
        ClassTable ct;
    };
    thread_local std::unique_ptr<Cached> c;
    if (!c) c = std::make_unique<Cached>();
    const uint16_t* flags = world_block_flags(L->world);
    std::shared_ptr<const MeshConfig> cfg = mesh_config(L->world);
    const uint64_t ov = L->overrides_version.load(std::memory_order_acquire);
    if (c->lod != L->serial || c->flags != flags || c->cfg_version != cfg->version || c->ovr_version != ov) {
        std::shared_ptr<const std::vector<uint8_t>> over;
        {
            std::lock_guard<std::mutex> g(L->class_m);
            over = L->overrides;
        }
        for (uint32_t id = 0; id < 65536; ++id) {
            uint8_t cls;
            switch ((*over)[id]) {
                case CV_LOD_CLASS_AIR: cls = kAir; break;
                case CV_LOD_CLASS_SOLID: cls = kSolid; break;
                case CV_LOD_CLASS_FLUID: cls = kFluid; break;
                default:
                    if (id == L->air) cls = kAir;
                    else if (cfg->is_fluid(static_cast<uint16_t>(id))) cls = kFluid;
                    else if ((flags[id] & CV_BLOCK_CUBE) != 0) cls = kSolid;
                    else cls = kAir;
            }
            c->ct.cls[id] = cls;
        }
        c->lod = L->serial;
        c->flags = flags;
        c->cfg_version = cfg->version;
        c->ovr_version = ov;
    }
    return c->ct;
}

struct WorldFaces final : FaceLookup {
    std::shared_ptr<const MeshConfig> cfg;
    const uint16_t* flags = nullptr;
    void face(uint16_t id, int f, uint32_t* layer, bool* alpha) const override {
        auto it = cfg->faces.find(id);
        if (it != cfg->faces.end()) {
            const cv_face_tex& t = it->second[static_cast<size_t>(f)];
            *layer = t.layer;
            *alpha = (t.flags & CV_FACE_ALPHA_TEST) != 0;
            return;
        }
        *layer = id;
        *alpha = (flags[id] & CV_BLOCK_TRANSPARENT) != 0 && (flags[id] & CV_BLOCK_TRANSLUCENT) == 0;
    }
};

// ---- stores --------------------------------------------------------------------------------------------------
cv_store* level_store(cv_lod* L, int32_t level) {
    if (!L->persist || level < 1) return nullptr;
    std::lock_guard<std::mutex> g(L->store_m);
    if (L->stores[level] == nullptr) {
        const std::string d = L->dir + "/l" + std::to_string(level);
        L->stores[level] = cv_store_open(d.c_str());
        if (L->stores[level] == nullptr) L->errors_total.fetch_add(1);
    }
    return L->stores[level];
}

void note_store_error(cv_lod* L, int32_t r) {
    L->errors_total.fetch_add(1);
    int32_t expected = 0;
    L->first_store_error.compare_exchange_strong(expected, r);
}

NodePtr load_record(cv_lod* L, const NodeKey& k) {
    cv_store* s = level_store(L, k.level);
    if (s == nullptr) return nullptr;
    thread_local std::vector<uint8_t> buf;
    if (buf.size() < 4096) buf.resize(4096);
    int64_t n = cv_store_read_cube(s, k.x, k.y, k.z, buf.data(), static_cast<int64_t>(buf.size()));
    if (CV_IS_NEED(n)) {
        buf.resize(static_cast<size_t>(CV_NEEDED(n)));
        n = cv_store_read_cube(s, k.x, k.y, k.z, buf.data(), static_cast<int64_t>(buf.size()));
    }
    if (n == 0) return nullptr;
    if (n < 0) {
        if (n == CV_ERR_CORRUPT) L->corrupt_total.fetch_add(1);
        else note_store_error(L, static_cast<int32_t>(n));
        return nullptr;
    }
    auto d = std::make_shared<NodeData>();
    if (decode_record(buf.data(), static_cast<size_t>(n), k, *d) != CV_OK) {
        L->corrupt_total.fetch_add(1);
        return nullptr;
    }
    L->loaded_total.fetch_add(1);
    return d;
}

void save_record(cv_lod* L, const NodeData& d) {
    // Unexplored space (incomplete and all air) is cheap to re-derive and would only fill the store.
    if (!d.complete && d.uniform && (d.uniform_cell & kClassMask) == kAir) return;
    cv_store* s = level_store(L, d.key.level);
    if (s == nullptr) return;
    thread_local std::vector<uint8_t> buf;
    encode_record(d, buf);
    const int32_t r = cv_store_write_cube(s, d.key.x, d.key.y, d.key.z, buf.data(), static_cast<int64_t>(buf.size()));
    if (r != CV_OK) note_store_error(L, r);
    else L->saved_total.fetch_add(1);
}

// ---- level-0 sources ----------------------------------------------------------------------------------------
cv_world* scratch_world(cv_lod* L) {
    struct Holder {
        cv_world* w = nullptr;
        int64_t lo = 0, hi = 0;
        uint16_t air = 0;
        ~Holder() {
            if (w != nullptr) cv_world_destroy(w);
        }
    };
    thread_local Holder h;
    const int64_t lo = world_min_y(L->world), hi = world_max_y(L->world);
    if (h.w == nullptr || h.lo != lo || h.hi != hi || h.air != L->air) {
        if (h.w != nullptr) cv_world_destroy(h.w);
        cv_world_desc d{};
        d.min_y = lo;
        d.max_y = hi;
        d.air_id = L->air;
        h.w = cv_world_create(&d);
        h.lo = lo;
        h.hi = hi;
        h.air = L->air;
    }
    return h.w;
}

uint64_t column_version(cv_lod* L, const std::pair<int64_t, int64_t>& key) {   // col_m held
    auto it = L->col_ver.find(key);
    return it == L->col_ver.end() ? 0 : it->second;
}

void column_changed(cv_lod* L, int64_t cx, int64_t cz);

// check: a noted column re-derived by its check job (always recomputed; a first sight counts as a change, since
// nodes persisted by an earlier session may have used older column facts).
ColumnPtr column_info(cv_lod* L, int64_t cx, int64_t cz, bool check = false) {
    const auto key = std::make_pair(cx, cz);
    uint64_t ver = 0;
    {
        std::lock_guard<std::mutex> g(L->col_m);
        if (!check) {
            auto it = L->columns.find(key);
            if (it != L->columns.end()) return it->second;
        }
        ver = column_version(L, key);
    }
    auto info = std::make_shared<ColumnInfo>();
    thread_local std::vector<uint8_t> buf;
    if (buf.size() < 8192) buf.resize(8192);
    int64_t n = cv_column_encode(L->world, cx, cz, buf.data(), static_cast<int64_t>(buf.size()));
    if (CV_IS_NEED(n)) {
        buf.resize(static_cast<size_t>(CV_NEEDED(n)));
        n = cv_column_encode(L->world, cx, cz, buf.data(), static_cast<int64_t>(buf.size()));
    }
    const uint8_t* core = nullptr;
    size_t core_len = 0;
    if (n > 0) {
        if (parse_column_record(buf.data(), static_cast<size_t>(n), info->runs, &core, &core_len) && !info->runs.empty()) {
            info->top.resize(1024);
            column_top(L->world, cx, cz, info->top.data());
            info->generated = true;
            info->top_min = *std::min_element(info->top.begin(), info->top.end());
            info->top_max = *std::max_element(info->top.begin(), info->top.end());
        }
    } else if (L->cube_store != nullptr) {
        n = cv_store_read_column(L->cube_store, cx, cz, buf.data(), static_cast<int64_t>(buf.size()));
        if (CV_IS_NEED(n)) {
            buf.resize(static_cast<size_t>(CV_NEEDED(n)));
            n = cv_store_read_column(L->cube_store, cx, cz, buf.data(), static_cast<int64_t>(buf.size()));
        }
        if (n > 0) {
            if (parse_column_record(buf.data(), static_cast<size_t>(n), info->runs, &core, &core_len) &&
                !info->runs.empty()) {
                cv_world* sw = scratch_world(L);
                if (sw != nullptr && cv_column_decode(sw, cx, cz, core, static_cast<int64_t>(core_len)) == CV_OK) {
                    info->top.resize(1024);
                    column_top(sw, cx, cz, info->top.data());
                    cv_column_remove(sw, cx, cz);
                    info->generated = true;
                    info->top_min = *std::min_element(info->top.begin(), info->top.end());
                    info->top_max = *std::max_element(info->top.begin(), info->top.end());
                } else {
                    info->runs.clear();
                    L->corrupt_total.fetch_add(1);
                }
            } else {
                info->runs.clear();
                L->corrupt_total.fetch_add(1);
            }
        } else if (n < 0) {
            L->errors_total.fetch_add(1);
        }
    }
    {
        uint64_t h = cenda::mix64(info->generated ? 2 : 1);
        for (const Run& r : info->runs) h = cenda::mix64(h ^ static_cast<uint64_t>(r.lo)) + static_cast<uint64_t>(r.hi);
        if (!info->top.empty()) h = cenda::mix64(h ^ hash_bytes(info->top.data(), info->top.size() * 8, 5));
        info->digest = h;
    }
    bool changed = false;
    {
        std::lock_guard<std::mutex> g(L->col_m);
        auto seen = L->col_seen.find(key);
        changed = seen == L->col_seen.end() ? check : seen->second != info->digest;
        if (L->col_seen.size() > (size_t{1} << 20)) L->col_seen.clear();
        L->col_seen[key] = info->digest;
        if (column_version(L, key) == ver) {
            if (L->columns.size() > 16384) L->columns.clear();
            L->columns[key] = info;
        }
    }
    if (changed) column_changed(L, cx, cz);
    return info;
}

NodePtr l0_data(cv_lod* L, const NodeKey& k) {
    if (!node_in_range(L, k)) {
        auto d = std::make_shared<NodeData>();
        node_uniform(*d, k, L->air, kAir, true);
        return d;
    }
    const int64_t rev = cv_cube_revision(L->world, k.x, k.y, k.z);
    uint64_t tag = 0;
    if (NodePtr p = L->cache0.get(k, &tag); p && tag == static_cast<uint64_t>(rev)) return p;
    uint64_t ver = 0;
    {
        std::lock_guard<std::mutex> g(L->col_m);
        ver = column_version(L, std::make_pair(k.x, k.z));
    }
    const ClassTable& ct = class_table(L);
    thread_local std::vector<uint16_t> ids;
    ids.resize(kCells);
    auto d = std::make_shared<NodeData>();
    bool built = false;
    bool uniform_cube = false;
    uint16_t uniform_value = 0;
    const bool inside = k.y * kEdge >= L->min_y && k.y * kEdge + kEdge <= L->max_y;
    int64_t got_rev = 0;
    {
        CubeRead cr(L->world, k.x, k.y, k.z);
        if (cr) {
            got_rev = cr.revision();
            uint16_t u = 0;
            if (cr.uniform(&u)) {
                uniform_cube = true;
                uniform_value = u;
            } else {
                cr.read_ids(ids.data());
            }
            built = true;
        }
    }
    if (built) {
        if (uniform_cube && inside) {
            const uint8_t c = ct.cls[uniform_value];
            node_uniform(*d, k, c == kAir ? L->air : uniform_value,
                         c == kSolid ? static_cast<uint8_t>(kSolid | kFull) : c, true);
        } else {
            if (uniform_cube) std::fill(ids.begin(), ids.end(), uniform_value);
            node_from_ids(*d, k, ids.data(), ct, L->air, L->min_y, L->max_y);
        }
    } else if (L->cube_store != nullptr) {
        thread_local std::vector<uint8_t> buf;
        if (buf.size() < 65536) buf.resize(65536);
        int64_t n = cv_store_read_cube(L->cube_store, k.x, k.y, k.z, buf.data(), static_cast<int64_t>(buf.size()));
        if (CV_IS_NEED(n)) {
            buf.resize(static_cast<size_t>(CV_NEEDED(n)));
            n = cv_store_read_cube(L->cube_store, k.x, k.y, k.z, buf.data(), static_cast<int64_t>(buf.size()));
        }
        if (n > 0) {
            if (decode_cube_blocks(buf.data(), static_cast<size_t>(n), ids.data()) == CV_OK) {
                node_from_ids(*d, k, ids.data(), ct, L->air, L->min_y, L->max_y);
                built = true;
            } else {
                L->corrupt_total.fetch_add(1);
            }
        } else if (n < 0) {
            L->errors_total.fetch_add(1);
        }
    }
    if (!built) {
        ColumnPtr col = column_info(L, k.x, k.z);
        const int64_t y0 = k.y * kEdge;
        const uint8_t fill_cls = ct.cls[L->fill];
        if (col->generated && !col->in_runs(k.y) && inside && y0 >= col->top_max) {
            node_uniform(*d, k, L->air, kAir, true);
        } else if (col->generated && !col->in_runs(k.y) && inside && y0 + kEdge <= col->top_min) {
            node_uniform(*d, k, fill_cls == kAir ? L->air : L->fill,
                         fill_cls == kSolid ? static_cast<uint8_t>(kSolid | kFull) : fill_cls, true);
        } else if (col->generated && !col->in_runs(k.y)) {
            for (int ly = 0; ly < kEdge; ++ly) {
                const int64_t wy = y0 + ly;
                for (int lz = 0; lz < kEdge; ++lz) {
                    for (int lx = 0; lx < kEdge; ++lx) {
                        ids[static_cast<size_t>(cell_index(lx, ly, lz))] =
                            wy >= col->top[static_cast<size_t>(lz * kEdge + lx)] ? L->air : L->fill;
                    }
                }
            }
            node_from_ids(*d, k, ids.data(), ct, L->air, L->min_y, L->max_y);
        } else {
            node_uniform(*d, k, L->air, kAir, false);   // unknown
        }
    }
    bool fresh;
    {
        std::lock_guard<std::mutex> g(L->col_m);
        fresh = column_version(L, std::make_pair(k.x, k.z)) == ver;
    }
    // Uniform level-0 nodes (sky, deep rock, uniform cubes) are cheaper to re-derive than to keep.
    if (fresh && !d->uniform && cv_cube_revision(L->world, k.x, k.y, k.z) == got_rev) {
        L->cache0.put(k, d, static_cast<uint64_t>(got_rev));
    }
    if (got_rev != 0) {
        std::lock_guard<std::mutex> g(L->watch_m);
        L->watch[Int3{k.x, k.y, k.z}] = got_rev;
    }
    return d;
}

NodePtr get_data(cv_lod* L, const NodeKey& k, bool build);

NodePtr build_from_children(cv_lod* L, const NodeKey& k) {
    NodePtr ch[8];
    for (int i = 0; i < 8; ++i) ch[i] = get_data(L, child_of(k, i), true);
    auto d = std::make_shared<NodeData>();
    node_downsample(*d, k, ch, L->air);
    L->built_total.fetch_add(1);
    return d;
}

int rebuild(cv_lod* L, const NodeKey& k);

// A deferred-dirty node is rebuilt before it is read (its parent then becomes deferred-dirty in turn).
void settle_deferred(cv_lod* L, const NodeKey& k) {
    {
        std::lock_guard<std::mutex> g(L->def_m);
        if (L->deferred.empty() || L->deferred.erase(k) == 0) return;
    }
    if (rebuild(L, k) > 0 && k.level < L->max_level) {
        std::lock_guard<std::mutex> g(L->def_m);
        L->deferred.insert(parent_of(k));
    }
}

NodePtr get_data(cv_lod* L, const NodeKey& k, bool build) {
    if (k.level == 0) return l0_data(L, k);
    if (!node_in_range(L, k)) {
        auto d = std::make_shared<NodeData>();
        node_uniform(*d, k, L->air, kAir, true);
        return d;
    }
    if (k.level > L->active_level.load(std::memory_order_relaxed)) settle_deferred(L, k);
    if (NodePtr p = L->cache.get(k)) return p;
    std::lock_guard<std::mutex> g(stripe(L, k));
    if (NodePtr p = L->cache.get(k)) return p;
    if (NodePtr p = load_record(L, k)) {
        L->cache.put(k, p);
        return p;
    }
    if (!build) return nullptr;
    NodePtr p = build_from_children(L, k);
    L->cache.put(k, p);
    save_record(L, *p);
    return p;
}

// Rebuilds a dirty node from its children. 1 changed, 0 unchanged.
int rebuild(cv_lod* L, const NodeKey& k) {
    if (!node_in_range(L, k)) return 0;
    std::lock_guard<std::mutex> g(stripe(L, k));
    NodePtr old = L->cache.get(k);
    if (!old) old = load_record(L, k);
    NodePtr ch[8];
    bool same = old != nullptr;
    for (int i = 0; i < 8; ++i) {
        ch[i] = get_data(L, child_of(k, i), true);
        if (same && ch[i]->digest != old->child_digest[i]) same = false;
    }
    if (same) {
        L->cache.put(k, old);
        return 0;
    }
    auto d = std::make_shared<NodeData>();
    node_downsample(*d, k, ch, L->air);
    L->built_total.fetch_add(1);
    const bool changed = !old || old->digest != d->digest;
    L->cache.put(k, d);
    save_record(L, *d);   // child digests may differ even when the content does not
    return changed ? 1 : 0;
}

// ---- prepare (data + mesh) -------------------------------------------------------------------------------------
void prepare(cv_lod* L, const NodeKey& k, PrepResult& out) {
    out.key = k;
    NodePtr d = get_data(L, k, true);
    out.complete = d->complete;
    out.digest = d->digest;
    if (!d->complete) return;
    NodePtr nb[6];
    const NodeData* nbp[6];
    uint64_t h = mix64(d->digest);
    for (int f = 0; f < 6; ++f) {
        nb[f] = get_data(L, neighbour(k, f), true);
        nbp[f] = nb[f].get();
        h = mix64(h ^ (nb[f] ? nb[f]->digest : 0) ^ static_cast<uint64_t>(f + 1));
    }
    const bool below_solid = node_origin(k.y, k.level) <= L->min_y;
    const int cover_levels = std::min(5, std::max(0, L->max_level - k.level));
    WorldFaces faces;
    faces.cfg = mesh_config(L->world);
    faces.flags = world_block_flags(L->world);
    auto mesh = std::make_shared<MeshBuild>();
    mesh_node(*d, nbp, below_solid, cover_levels, faces, *mesh);
    mesh->input_hash = h;
    L->meshes_total.fetch_add(1);
    out.mesh = std::move(mesh);
}

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
void wake_planner_locked(cv_lod* L);

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

// ---- meta ------------------------------------------------------------------------------------------------------
constexpr uint32_t kMetaMagic = 0x4D4C5643u;   // "CVLM"
constexpr uint32_t kMetaVersion = 1;

std::vector<uint8_t> meta_bytes(const cv_lod* L) {
    std::vector<uint8_t> o;
    auto p32 = [&](uint32_t v) { for (int i = 0; i < 4; ++i) o.push_back(static_cast<uint8_t>(v >> (8 * i))); };
    auto p64 = [&](uint64_t v) { for (int i = 0; i < 8; ++i) o.push_back(static_cast<uint8_t>(v >> (8 * i))); };
    p32(kMetaMagic);
    p32(kMetaVersion);
    p32(kEdge);
    o.push_back(static_cast<uint8_t>(L->air));
    o.push_back(static_cast<uint8_t>(L->air >> 8));
    o.push_back(static_cast<uint8_t>(L->fill));
    o.push_back(static_cast<uint8_t>(L->fill >> 8));
    p64(static_cast<uint64_t>(L->min_y));
    p64(static_cast<uint64_t>(L->max_y));
    p64(L->content_version);
    p32(static_cast<uint32_t>(L->max_level));
    return o;
}

std::filesystem::path upath(const std::string& utf8) {
    return std::filesystem::path(std::u8string(reinterpret_cast<const char8_t*>(utf8.c_str())));
}

// CV_OK, or CV_ERR_CORRUPT (mismatch, message set) / CV_ERR_IO.
int32_t open_meta(cv_lod* L, bool reset_on_mismatch) {
    namespace fs = std::filesystem;
    std::error_code ec;
    fs::create_directories(upath(L->dir), ec);
    if (ec) {
        cv_set_error("lod store: cannot create %s: %s", L->dir.c_str(), ec.message().c_str());
        return CV_ERR_IO;
    }
    const fs::path meta = upath(L->dir) / "lod.meta";
    const std::vector<uint8_t> want = meta_bytes(L);
    bool write = true;
    if (fs::exists(meta, ec)) {
        std::ifstream in(meta, std::ios::binary);
        std::vector<uint8_t> have((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
        if (have == want) {
            write = false;
        } else if (!reset_on_mismatch) {
            cv_set_error("lod store mismatch in %s (format, node edge, air/fill id, y range, content_version or "
                         "max_level differ); pass CV_LOD_RESET_ON_MISMATCH to rebuild it", L->dir.c_str());
            return CV_ERR_CORRUPT;
        } else {
            for (int lv = 0; lv < kLevels; ++lv) {
                fs::remove_all(upath(L->dir) / ("l" + std::to_string(lv)), ec);
            }
        }
    }
    if (write) {
        const fs::path tmp = upath(L->dir) / "lod.meta.tmp";
        {
            std::ofstream out(tmp, std::ios::binary | std::ios::trunc);
            out.write(reinterpret_cast<const char*>(want.data()), static_cast<std::streamsize>(want.size()));
            out.flush();
            if (!out) {
                cv_set_error("lod store: cannot write %s", tmp.string().c_str());
                return CV_ERR_IO;
            }
        }
        fs::rename(tmp, meta, ec);
        if (ec) {
            cv_set_error("lod store: cannot write %s: %s", meta.string().c_str(), ec.message().c_str());
            return CV_ERR_IO;
        }
    }
    return CV_OK;
}

// ---- deferred dirty set ("deferred.bin": u32 magic "CVLD", u32 count, count x (i32 level, i64 x, y, z)) --------
constexpr uint32_t kDeferredMagic = 0x444C5643u;   // "CVLD"

void save_deferred(cv_lod* L) {
    if (!L->persist) return;
    std::vector<NodeKey> keys;
    {
        std::lock_guard<std::mutex> g(L->def_m);
        keys.assign(L->deferred.begin(), L->deferred.end());
    }
    namespace fs = std::filesystem;
    std::error_code ec;
    const fs::path file = upath(L->dir) / "deferred.bin";
    if (keys.empty()) {
        fs::remove(file, ec);
        return;
    }
    std::vector<uint8_t> o;
    auto p32 = [&](uint32_t v) { for (int i = 0; i < 4; ++i) o.push_back(static_cast<uint8_t>(v >> (8 * i))); };
    auto p64 = [&](uint64_t v) { for (int i = 0; i < 8; ++i) o.push_back(static_cast<uint8_t>(v >> (8 * i))); };
    p32(kDeferredMagic);
    p32(static_cast<uint32_t>(keys.size()));
    for (const NodeKey& k : keys) {
        p32(static_cast<uint32_t>(k.level));
        p64(static_cast<uint64_t>(k.x));
        p64(static_cast<uint64_t>(k.y));
        p64(static_cast<uint64_t>(k.z));
    }
    const fs::path tmp = upath(L->dir) / "deferred.bin.tmp";
    {
        std::ofstream out(tmp, std::ios::binary | std::ios::trunc);
        out.write(reinterpret_cast<const char*>(o.data()), static_cast<std::streamsize>(o.size()));
        if (!out) {
            L->errors_total.fetch_add(1);
            return;
        }
    }
    fs::rename(tmp, file, ec);
    if (ec) L->errors_total.fetch_add(1);
}

void load_deferred(cv_lod* L) {
    if (!L->persist) return;
    std::ifstream in(upath(L->dir) / "deferred.bin", std::ios::binary);
    if (!in) return;
    std::vector<uint8_t> b((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    auto g32 = [&](size_t at) { uint32_t v = 0; for (int i = 0; i < 4; ++i) v |= static_cast<uint32_t>(b[at + static_cast<size_t>(i)]) << (8 * i); return v; };
    auto g64 = [&](size_t at) { uint64_t v = 0; for (int i = 0; i < 8; ++i) v |= static_cast<uint64_t>(b[at + static_cast<size_t>(i)]) << (8 * i); return v; };
    if (b.size() < 8 || g32(0) != kDeferredMagic || b.size() != 8 + size_t{g32(4)} * 28) {
        L->corrupt_total.fetch_add(1);
        return;
    }
    std::lock_guard<std::mutex> g(L->def_m);
    for (size_t at = 8; at < b.size(); at += 28) {
        const NodeKey k{static_cast<int32_t>(g32(at)), static_cast<int64_t>(g64(at + 4)), static_cast<int64_t>(g64(at + 12)),
                        static_cast<int64_t>(g64(at + 20))};
        if (k.level >= 1 && k.level <= L->max_level) L->deferred.insert(k);
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

// ---- selection (planner thread) -----------------------------------------------------------------------------
// The selection runs on the PLANNER thread, per root, only for roots something changed in (a prepare result, a data
// change, readiness, the view). Each changed root becomes a Commit (that root's whole cover). The owner tick only
// applies prepared commits within its budget and keeps the drawn cover valid on its own in between (readiness
// patches + gap fills, see "owner" below), so cv_lod_tick never runs an O(nodes) step.
double foot_dmin(const FootKey& f, const std::vector<cv_lod_viewer>& viewers) {
    const double s = static_cast<double>(span(f.level));
    const double x0 = static_cast<double>(f.x) * s, z0 = static_cast<double>(f.z) * s;
    double best = std::numeric_limits<double>::infinity();
    for (const cv_lod_viewer& v : viewers) {
        const double vx = static_cast<double>(v.x) + 0.5, vz = static_cast<double>(v.z) + 0.5;
        const double dx = std::max({x0 - vx, 0.0, vx - (x0 + s)});
        const double dz = std::max({z0 - vz, 0.0, vz - (z0 + s)});
        best = std::min(best, std::sqrt(dx * dx + dz * dz));
    }
    return best;
}

// Farthest point of the footprint from the viewer nearest to that point, bounded above by the minimum over
// viewers of the farthest corner (conservative: "the whole footprint lies within r of some viewer").
double foot_dmax(const FootKey& f, const std::vector<cv_lod_viewer>& viewers) {
    const double s = static_cast<double>(span(f.level));
    const double x0 = static_cast<double>(f.x) * s, z0 = static_cast<double>(f.z) * s;
    double best = std::numeric_limits<double>::infinity();
    for (const cv_lod_viewer& v : viewers) {
        const double vx = static_cast<double>(v.x) + 0.5, vz = static_cast<double>(v.z) + 0.5;
        const double dx = std::max(std::abs(x0 - vx), std::abs(x0 + s - vx));
        const double dz = std::max(std::abs(z0 - vz), std::abs(z0 + s - vz));
        best = std::min(best, std::sqrt(dx * dx + dz * dz));
    }
    return best;
}

inline int64_t foot_total(int32_t level) { return int64_t{1} << (2 * level); }
inline FootKey foot_child(const FootKey& f, int i) { return FootKey{f.level - 1, f.x * 2 + (i & 1), f.z * 2 + (i >> 1)}; }
inline FootKey foot_at(const FootKey& f, int32_t level) {   // ancestor (level >= f.level)
    const int sh = level - f.level;
    return FootKey{level, f.x >> sh, f.z >> sh};
}
inline FootKey foot_of(const NodeKey& k) { return FootKey{k.level, k.x, k.z}; }

// The finest level an INCOMPLETE stack is refined to (policy.incomplete_min_level: 0 = 1, < 0 = 0).
inline int32_t incomplete_min_level(const cv_lod_policy& P) {
    const int32_t v = P.incomplete_min_level;
    return v == 0 ? 1 : (v < 0 ? 0 : v);
}

struct Selector {
    cv_lod* L;
    const cv_lod_policy& P;
    double K;
    int32_t min_inc;

    double dmin(const FootKey& f) const { return foot_dmin(f, L->p_viewers); }
    bool leaf(const FootKey& f, double d) const {
        return f.level == 0 || d >= std::ldexp(K, f.level);
    }
    int64_t ready(const FootKey& f) const {
        auto it = L->p_ready_count.find(f);
        return it == L->p_ready_count.end() ? 0 : it->second;
    }
    static int64_t total(int32_t level) { return foot_total(level); }
    static FootKey child(const FootKey& f, int i) { return foot_child(f, i); }

    NodeState& state(const NodeKey& k) {
        NodeState& s = L->states[k];
        s.touched = L->epoch;
        return s;
    }

    void request(const FootKey& f, double d) {
        int64_t lo, hi;
        stack_range(L, f.level, &lo, &hi);
        for (int64_t ny = lo; ny <= hi; ++ny) {
            const NodeKey k{f.level, f.x, ny, f.z};
            NodeState& s = state(k);
            if (s.stale()) L->candidates.push_back(Candidate{d, k});
        }
    }

    enum Status { kBuilt, kPending, kIncomplete };
    Status status(const FootKey& f) {
        int64_t lo, hi;
        stack_range(L, f.level, &lo, &hi);
        bool pending = false;
        for (int64_t ny = lo; ny <= hi; ++ny) {
            auto it = L->states.find(NodeKey{f.level, f.x, ny, f.z});
            if (it == L->states.end() || !it->second.known) {
                pending = true;
                continue;
            }
            const NodeState& s = it->second;
            if (!s.complete) {
                if (!s.stale()) return kIncomplete;
                pending = true;
                continue;
            }
            if (!s.mesh) pending = true;
        }
        return pending ? kPending : kBuilt;
    }

    void add_stack(const FootKey& f, Group& out) {
        int64_t lo, hi;
        stack_range(L, f.level, &lo, &hi);
        out.stacks.push_back(f);
        for (int64_t ny = lo; ny <= hi; ++ny) {
            const NodeKey k{f.level, f.x, ny, f.z};
            NodeState& s = state(k);
            if (s.mesh && !s.mesh->empty()) out.nodes.push_back(k);
        }
    }

    static void append(Group& to, Group& from) {
        to.nodes.insert(to.nodes.end(), from.nodes.begin(), from.nodes.end());
        to.fallbacks.insert(to.fallbacks.end(), from.fallbacks.begin(), from.fallbacks.end());
        to.stacks.insert(to.stacks.end(), from.stacks.begin(), from.stacks.end());
    }

    // True when F's area (minus ready columns) is covered by built stacks below F (no requests).
    bool probe(const FootKey& f, int depth, Group& out) {
        if (depth == 0 || f.level == 0) return false;
        for (int i = 0; i < 4; ++i) {
            const FootKey c = child(f, i);
            const int64_t rc = ready(c);
            if (rc == total(c.level)) continue;
            if (rc == 0 && status(c) == kBuilt) {
                add_stack(c, out);
                continue;
            }
            if (!probe(c, depth - 1, out)) return false;
        }
        return true;
    }

    // F must be covered at its level or finer; returns true when the cover holds a FALLBACK.
    bool cover_leaf(const FootKey& f, double d, bool partial, int idepth, Group& out) {
        if (partial) {
            bool fb = false;
            for (int i = 0; i < 4; ++i) fb |= cover_sub(child(f, i), idepth, out);
            return fb;
        }
        request(f, d);
        const Status st = status(f);
        if (st == kBuilt) {
            add_stack(f, out);
            return false;
        }
        if (f.level == 0) {
            out.fallbacks.push_back(f);
            return true;
        }
        if (st == kIncomplete) {
            // Unexplored land: its explored parts are drawn down to min_inc, the rest is the host's FALLBACK
            // (cheaper than many fine incomplete stacks).
            if (idepth > 0 && f.level > min_inc) {
                bool fb = false;
                for (int i = 0; i < 4; ++i) fb |= cover_sub(child(f, i), idepth - 1, out);
                return fb;
            }
            out.fallbacks.push_back(f);
            return true;
        }
        Group tmp;
        if (probe(f, 2, tmp)) {
            append(out, tmp);
            return false;
        }
        out.fallbacks.push_back(f);
        return true;
    }

    bool cover_sub(const FootKey& c, int idepth, Group& out) {
        const int64_t rc = ready(c);
        if (rc == total(c.level)) return false;
        return cover_leaf(c, dmin(c), rc > 0, idepth, out);
    }

    void standby(const FootKey& f, double d) {
        if (P.standby_from < 0) return;
        const double diag = static_cast<double>(span(f.level)) * 1.4142135623730951;
        if (d + diag < static_cast<double>(P.standby_from)) return;
        if (leaf(f, d)) {
            if (d >= static_cast<double>(P.standby_from)) request(f, d);
            return;
        }
        for (int i = 0; i < 4; ++i) {
            const FootKey c = child(f, i);
            const double dc = dmin(c);
            if (dc <= static_cast<double>(P.radius)) standby(c, dc);
        }
    }

    bool cover(const FootKey& f, Group& out) {
        const double d = dmin(f);
        if (d > static_cast<double>(P.radius)) return false;
        const int64_t rc = ready(f);
        if (rc == total(f.level)) {
            standby(f, d);
            return false;
        }
        if (leaf(f, d)) return cover_leaf(f, d, rc > 0, P.incomplete_depth, out);
        Group tmp;
        bool fb = false;
        for (int i = 0; i < 4; ++i) fb |= cover(child(f, i), tmp);
        if (fb && rc == 0 && status(f) == kBuilt) {
            add_stack(f, out);   // the parent until all children are ready
            return false;
        }
        append(out, tmp);
        return fb;
    }
};

template <typename T>
void sort_unique(std::vector<T>& v) {
    std::sort(v.begin(), v.end());
    v.erase(std::unique(v.begin(), v.end()), v.end());
}

// Roots (footprints of the root level) within the radius of the viewers.
void roots_in_range(const std::vector<cv_lod_viewer>& viewers, const cv_lod_policy& P, std::vector<FootKey>& out) {
    out.clear();
    const int32_t rl = P.max_level;
    const int64_t rs = span(rl);
    for (const cv_lod_viewer& v : viewers) {
        const int64_t x0 = floor_div64(v.x - P.radius, rs), x1 = floor_div64(v.x + P.radius, rs);
        const int64_t z0 = floor_div64(v.z - P.radius, rs), z1 = floor_div64(v.z + P.radius, rs);
        for (int64_t x = x0; x <= x1; ++x) {
            for (int64_t z = z0; z <= z1; ++z) {
                const FootKey r{rl, x, z};
                if (foot_dmin(r, viewers) <= static_cast<double>(P.radius)) out.push_back(r);
            }
        }
    }
    sort_unique(out);
}

// ---- planner state helpers -------------------------------------------------------------------------------------
void p_mark_root(cv_lod* L, const NodeKey& k) {
    if (L->p_root_level < 0 || k.level > L->p_root_level) return;
    L->p_dirty_roots.insert(foot_at(foot_of(k), L->p_root_level));
}

void p_mark_stale(cv_lod* L, const NodeKey& k) {
    auto it = L->states.find(k);
    if (it == L->states.end()) return;
    ++it->second.want;
    p_mark_root(L, k);
}

void p_sent_refs(cv_lod* L, const Group& g, int32_t delta) {
    for (const NodeKey& k : g.nodes) {
        auto it = L->states.find(k);
        if (it != L->states.end()) it->second.sent += delta;
    }
}

void p_forget_sent(cv_lod* L, const FootKey& r) {
    auto it = L->p_sent.find(r);
    if (it == L->p_sent.end()) return;
    p_sent_refs(L, it->second.g, -1);
    L->p_sent.erase(it);
}

// Re-publishes the built state of a stack (StackTable) after its node states moved.
void p_publish_stack(cv_lod* L, const FootKey& f) {
    int64_t lo, hi;
    stack_range(L, f.level, &lo, &hi);
    auto st = std::make_shared<Stack>();
    for (int64_t ny = lo; ny <= hi; ++ny) {
        auto it = L->states.find(NodeKey{f.level, f.x, ny, f.z});
        if (it == L->states.end() || !it->second.known || !it->second.complete || !it->second.mesh) {
            L->stacks.put(f, nullptr);
            return;
        }
        if (!it->second.mesh->empty()) {
            st->nodes.push_back(it->first);
            st->meshes.push_back(it->second.mesh);
        }
    }
    L->stacks.put(f, std::move(st));
}

void p_set_ready(cv_lod* L, int64_t cx, int64_t cz, bool ready) {
    const FootKey col{0, cx, cz};
    const bool had = L->p_ready.count(col) != 0;
    if (had == ready) return;
    if (ready) L->p_ready.insert(col);
    else L->p_ready.erase(col);
    for (int lv = 0; lv <= L->max_level; ++lv) {
        const FootKey f{lv, cx >> lv, cz >> lv};
        int64_t& c = L->p_ready_count[f];
        c += ready ? 1 : -1;
        if (c == 0) L->p_ready_count.erase(f);
    }
    if (L->p_root_level >= 0) {
        const FootKey r = foot_at(col, L->p_root_level);
        L->p_dirty_roots.insert(r);
        p_forget_sent(L, r);   // the owner patched that root: its cover is no longer what was sent
    }
}

// Selects one root and queues its commit when it differs from what was sent.
void p_select_root(cv_lod* L, Selector& S, const FootKey& r, std::vector<CommitPtr>& out) {
    L->candidates.clear();
    Group g;
    S.cover(r, g);
    sort_unique(g.nodes);
    sort_unique(g.fallbacks);
    sort_unique(g.stacks);
    L->p_root_epoch[r] = L->epoch;
    if (L->candidates.empty()) L->p_cands.erase(r);
    else L->p_cands[r] = L->candidates;
    std::vector<MeshPtr> meshes;
    meshes.reserve(g.nodes.size());
    for (const NodeKey& k : g.nodes) meshes.push_back(L->states[k].mesh);
    auto sit = L->p_sent.find(r);
    if (sit != L->p_sent.end() && sit->second.g.nodes == g.nodes && sit->second.g.fallbacks == g.fallbacks &&
        sit->second.g.stacks == g.stacks && sit->second.meshes == meshes) {
        return;
    }
    if (sit == L->p_sent.end() && g.empty()) {
        // Nothing sent and nothing wanted: still tell the owner (it may hold its own patches / gap fills there).
        auto c = std::make_shared<Commit>();
        c->root = r;
        c->seq = L->p_seq;
        c->view_version = L->p_view_version;
        c->root_level = L->p_root_level;
        c->d = S.dmin(r);
        c->viewers = L->p_viewers;
        c->radius = L->p_policy.radius;
        out.push_back(std::move(c));
        SentGroup sg;
        L->p_sent.emplace(r, std::move(sg));
        return;
    }
    p_forget_sent(L, r);
    auto c = std::make_shared<Commit>();
    c->root = r;
    c->seq = L->p_seq;
    c->view_version = L->p_view_version;
    c->root_level = L->p_root_level;
    c->d = S.dmin(r);
    c->g = g;
    c->meshes = meshes;
    c->viewers = L->p_viewers;
    c->radius = L->p_policy.radius;
    out.push_back(c);
    SentGroup sg;
    sg.g = std::move(g);
    sg.meshes = std::move(meshes);
    p_sent_refs(L, sg.g, +1);
    L->p_sent.emplace(r, std::move(sg));
}

void p_prune(cv_lod* L, const std::unordered_set<FootKey, FootKeyHash>& in_range) {
    int64_t standby = 0, bytes = 0;
    for (auto it = L->states.begin(); it != L->states.end();) {
        const NodeKey& k = it->first;
        const NodeState& s = it->second;
        bool drop = false;
        if (s.sent == 0) {
            if (L->p_root_level < 0 || k.level > L->p_root_level) {
                drop = true;
            } else {
                const FootKey r = foot_at(foot_of(k), L->p_root_level);
                auto e = L->p_root_epoch.find(r);
                drop = in_range.count(r) == 0 || e == L->p_root_epoch.end() || s.touched < e->second;
            }
        }
        if (drop) {
            L->p_feet_touched.insert(foot_of(k));
            it = L->states.erase(it);
            continue;
        }
        if (s.mesh) {
            bytes += s.mesh->bytes();
            if (s.sent == 0 && !s.mesh->empty()) ++standby;
        }
        ++it;
    }
    for (auto it = L->p_root_epoch.begin(); it != L->p_root_epoch.end();) {
        if (in_range.count(it->first) == 0) it = L->p_root_epoch.erase(it);
        else ++it;
    }
    L->p_standby.store(standby);
    L->p_states.store(static_cast<int64_t>(L->states.size()));
    L->p_resident_bytes.store(bytes);
}

// Standby / resident counters (stats only).
void p_count(cv_lod* L) {
    int64_t standby = 0, bytes = 0;
    for (const auto& [k, s] : L->states) {
        if (!s.mesh) continue;
        bytes += s.mesh->bytes();
        if (s.sent == 0 && !s.mesh->empty()) ++standby;
    }
    L->p_standby.store(standby);
    L->p_states.store(static_cast<int64_t>(L->states.size()));
    L->p_resident_bytes.store(bytes);
}

void planner_main(cv_lod* L) {
    std::vector<PrepResult> results;
    std::vector<NodeKey> changes;
    std::vector<ReadyChange> readies;
    std::vector<FootKey> touched;
    std::vector<CommitPtr> out;
    std::vector<FootKey> roots;
    std::unordered_set<FootKey, FootKeyHash> in_range;
    std::unique_lock<std::mutex> lk(L->m);
    for (;;) {
        L->plan_cv.wait_for(lk, std::chrono::milliseconds(200), [&] { return L->stopping || L->plan_wake; });
        if (L->stopping) return;
        const bool timer = !L->plan_wake;
        L->plan_wake = false;
        L->plan_busy = true;
        results.clear();
        changes.clear();
        readies.clear();
        touched.clear();
        results.swap(L->done);
        changes.swap(L->changed);
        readies.swap(L->ready_in);
        touched.swap(L->owner_touched);
        const bool overflow = L->changed_overflow;
        L->changed_overflow = false;
        lk.unlock();

        const int64_t t0 = now_us();
        ++L->epoch;
        // View / policy.
        bool all_dirty = false;
        {
            std::lock_guard<std::mutex> g(L->view_m);
            if (L->view_version != L->p_view_version) {
                L->p_view_version = L->view_version;
                L->p_viewers = L->viewers;
                L->p_policy = L->policy;
                L->p_have_view = true;
                all_dirty = true;
            }
        }
        const int32_t rl = L->p_have_view && !L->p_viewers.empty() ? L->p_policy.max_level : -1;
        if (rl != L->p_root_level) {
            for (auto& [r, sg] : L->p_sent) p_sent_refs(L, sg.g, -1);
            L->p_sent.clear();
            L->p_cands.clear();
            L->p_dirty_roots.clear();
            L->p_root_epoch.clear();
            L->p_root_level = rl;
            all_dirty = true;
        }
        if (overflow) {
            for (auto& [k, st] : L->states) ++st.want;
            all_dirty = true;
        }
        for (const NodeKey& k : changes) {
            p_mark_stale(L, k);
            for (int f = 0; f < 6; ++f) p_mark_stale(L, neighbour(k, f));
        }
        for (PrepResult& r : results) {
            auto it = L->states.find(r.key);
            if (it == L->states.end()) continue;
            NodeState& s = it->second;
            if (s.inflight == r.serial) s.inflight = 0;
            if (r.serial < s.have) continue;   // older than what we have
            s.have = r.serial;
            s.known = true;
            s.complete = r.complete;
            s.digest = r.digest;
            if (!r.complete) {
                s.mesh.reset();
            } else if (!s.mesh || !r.mesh || s.mesh->input_hash != r.mesh->input_hash) {
                s.mesh = std::move(r.mesh);
            }
            L->p_feet_touched.insert(foot_of(r.key));
            p_mark_root(L, r.key);
        }
        for (const ReadyChange& c : readies) {
            p_set_ready(L, c.cx, c.cz, c.ready);
            L->p_seq = c.seq;
        }
        for (const FootKey& r : touched) {
            if (r.level != L->p_root_level) continue;
            p_forget_sent(L, r);
            L->p_dirty_roots.insert(r);
        }
        for (const FootKey& f : L->p_feet_touched) p_publish_stack(L, f);
        L->p_feet_touched.clear();

        // Selection of the dirty roots (all of them after a view change), then roots that left the range.
        out.clear();
        bool selected = false;
        if (L->p_root_level >= 0) {
            roots_in_range(L->p_viewers, L->p_policy, roots);
            in_range.clear();
            in_range.insert(roots.begin(), roots.end());
            Selector S{L, L->p_policy, static_cast<double>(L->p_policy.blocks_per_cell), incomplete_min_level(L->p_policy)};
            for (const FootKey& r : roots) {
                if (!all_dirty && L->p_dirty_roots.count(r) == 0) continue;
                p_select_root(L, S, r, out);
                selected = true;
            }
            std::vector<FootKey> gone;
            for (auto& [r, sg] : L->p_sent) {
                if (in_range.count(r) == 0) gone.push_back(r);
            }
            for (const FootKey& r : gone) {
                p_forget_sent(L, r);
                auto c = std::make_shared<Commit>();
                c->root = r;
                c->seq = L->p_seq;
                c->view_version = L->p_view_version;
                c->root_level = L->p_root_level;
                c->d = foot_dmin(r, L->p_viewers);
                c->viewers = L->p_viewers;
                c->radius = L->p_policy.radius;
                out.push_back(std::move(c));
            }
            for (auto it = L->p_cands.begin(); it != L->p_cands.end();) {
                if (in_range.count(it->first) == 0) it = L->p_cands.erase(it);
                else ++it;
            }
        } else {
            in_range.clear();
            L->p_cands.clear();
        }
        L->p_dirty_roots.clear();
        const int64_t now = now_us();
        if (selected || timer || now - L->p_last_prune_us > 250000) {
            if (now - L->p_last_prune_us > 100000 || L->p_root_level < 0) {
                p_prune(L, in_range);
                for (const FootKey& f : L->p_feet_touched) p_publish_stack(L, f);
                L->p_feet_touched.clear();
                L->p_last_prune_us = now;
            }
        }
        // Requests -> the prepare queue (nearest first, finer first on ties); jobs no longer wanted are dropped.
        std::vector<Candidate> all;
        if (selected) {
            for (auto& [r, v] : L->p_cands) all.insert(all.end(), v.begin(), v.end());
            std::sort(all.begin(), all.end(), [](const Candidate& a, const Candidate& b) {
                if (a.d != b.d) return a.d < b.d;
                return a.key < b.key;
            });
        }
        if (selected) L->p_select_us.store(now_us() - t0);

        lk.lock();
        if (selected) {
            for (const PrepJob& j : L->prep_q) {
                auto it = L->states.find(j.key);
                if (it != L->states.end() && it->second.inflight == j.serial) it->second.inflight = 0;
            }
            L->prep_q.clear();
            for (const Candidate& c : all) {
                auto it = L->states.find(c.key);
                if (it == L->states.end()) continue;
                NodeState& s = it->second;
                if (!s.stale() || s.inflight == s.want) continue;   // fresh, queued (this loop) or running
                s.inflight = s.want;
                L->prep_q.push_back(PrepJob{c.key, s.want});
            }
            if (!L->prep_q.empty()) L->work_cv.notify_all();
        }
        for (CommitPtr& c : out) L->commits[c->root] = std::move(c);
        L->planner_seq = L->p_seq;
        if (!L->plan_wake && (selected || !results.empty())) {   // going idle: refresh the counters
            lk.unlock();
            p_count(L);
            lk.lock();
        }
        L->plan_busy = false;
        L->idle_cv.notify_all();
    }
}

void wake_planner_locked(cv_lod* L) {
    L->plan_wake = true;
    L->plan_cv.notify_one();
}

// ---- owner: the drawn cover ------------------------------------------------------------------------------------
// The owner keeps a VALID cover at the end of every tick (every column within the radius covered exactly once by
// READY detail, a drawn stack or a fallback; nothing drawn over a READY column), whatever the planner is doing:
//  - a readiness change is patched at once (ensure_column): a column turning READY splits the footprint drawn
//    over it into its other parts (each a built stack if one is published, else a FALLBACK); a column turning
//    UNREADY gets its level-0 stack (standby) or a level-0 FALLBACK;
//  - a view change fills what the new radius uncovered (gap) with root-level stacks / fallbacks;
//  - a commit replaces its root's cover, then readiness changes newer than the commit are re-patched and the
//    radius is re-filled if the view moved since.
// Events are the NET change of the tick: SHOW / FALLBACK_ON, then UPDATE, then HIDE / FALLBACK_OFF.
void o_record_node(cv_lod* L, const NodeKey& k) {
    if (L->tick_nodes.count(k) != 0) return;
    TickNode t;
    auto it = L->node_mesh.find(k);
    if (it != L->node_mesh.end()) {
        t.was_drawn = true;
        t.was_mesh = it->second;
    }
    L->tick_nodes.emplace(k, std::move(t));
}

void o_record_fb(cv_lod* L, const FootKey& f, bool was_on) {
    L->tick_fb.emplace(f, was_on);   // first record wins
}

void o_below(cv_lod* L, const FootKey& f, int32_t delta) {
    for (int32_t lv = f.level + 1; lv <= L->groups_level; ++lv) {
        const FootKey a = foot_at(f, lv);
        int32_t& c = L->below[a];
        c += delta;
        if (c == 0) L->below.erase(a);
    }
}

void o_remove_foot(cv_lod* L, const FootKey& f) {
    auto it = L->drawn.find(f);
    if (it == L->drawn.end()) return;
    if (it->second.fallback) {
        o_record_fb(L, f, true);
        --L->o_fallbacks;
    }
    for (const NodeKey& k : it->second.nodes) {
        o_record_node(L, k);
        auto m = L->node_mesh.find(k);
        if (m != L->node_mesh.end()) {
            L->o_drawn_bytes -= m->second ? m->second->bytes() : 0;
            --L->o_drawn_nodes;
            L->node_mesh.erase(m);
        }
    }
    L->drawn.erase(it);
    o_below(L, f, -1);
    const FootKey r = foot_at(f, L->groups_level);
    auto rf = L->root_feet.find(r);
    if (rf != L->root_feet.end()) {
        rf->second.erase(f);
        if (rf->second.empty()) L->root_feet.erase(rf);
    }
}

void o_add_fallback(cv_lod* L, const FootKey& f) {
    if (L->drawn.count(f) != 0) return;
    DrawnFoot d;
    d.fallback = true;
    L->drawn.emplace(f, std::move(d));
    o_record_fb(L, f, false);
    ++L->o_fallbacks;
    o_below(L, f, +1);
    L->root_feet[foot_at(f, L->groups_level)].insert(f);
}

void o_add_stack(cv_lod* L, const FootKey& f, const NodeKey* nodes, const MeshPtr* meshes, size_t n) {
    if (L->drawn.count(f) != 0) return;
    DrawnFoot d;
    d.nodes.assign(nodes, nodes + n);
    for (size_t i = 0; i < n; ++i) {
        o_record_node(L, nodes[i]);
        L->node_mesh[nodes[i]] = meshes[i];
        L->o_drawn_bytes += meshes[i] ? meshes[i]->bytes() : 0;
        ++L->o_drawn_nodes;
    }
    L->drawn.emplace(f, std::move(d));
    o_below(L, f, +1);
    L->root_feet[foot_at(f, L->groups_level)].insert(f);
}

int64_t o_ready(const cv_lod* L, const FootKey& f) {
    auto it = L->ready_count.find(f);
    return it == L->ready_count.end() ? 0 : it->second;
}

// Covers F's non-ready columns within the radius (nothing of F is drawn): published stacks, else FALLBACK.
void o_min_cover(cv_lod* L, const FootKey& f) {
    if (foot_dmin(f, L->o_viewers) > static_cast<double>(L->o_policy.radius)) return;
    const int64_t rc = o_ready(L, f);
    if (rc == foot_total(f.level)) return;
    if (rc == 0) {
        if (StackPtr st = L->stacks.get(f)) {
            o_add_stack(L, f, st->nodes.data(), st->meshes.data(), st->nodes.size());
        } else {
            o_add_fallback(L, f);
        }
        return;
    }
    for (int i = 0; i < 4; ++i) o_min_cover(L, foot_child(f, i));
}

const FootKey* o_cover_of(cv_lod* L, int64_t cx, int64_t cz, FootKey& tmp) {
    for (int32_t lv = 0; lv <= L->groups_level; ++lv) {
        tmp = FootKey{lv, cx >> lv, cz >> lv};
        if (L->drawn.count(tmp) != 0) return &tmp;
    }
    return nullptr;
}

// Makes the cover right for one column's current readiness (idempotent).
void o_ensure_column(cv_lod* L, int64_t cx, int64_t cz) {
    if (!L->have_view || L->groups_level < 0) return;
    FootKey tmp;
    const FootKey* cov = o_cover_of(L, cx, cz, tmp);
    const bool is_ready = L->ready.count(FootKey{0, cx, cz}) != 0;
    if (is_ready) {
        if (cov == nullptr) return;
        const FootKey f = *cov;
        o_remove_foot(L, f);
        // Re-cover F minus the column path: at each level the three siblings of the path.
        for (int32_t lv = f.level; lv >= 1; --lv) {
            const FootKey p{lv, cx >> lv, cz >> lv};
            const FootKey path{lv - 1, cx >> (lv - 1), cz >> (lv - 1)};
            for (int i = 0; i < 4; ++i) {
                const FootKey c = foot_child(p, i);
                if (!(c == path)) o_min_cover(L, c);
            }
        }
        return;
    }
    if (cov != nullptr) return;
    // The column is not covered: is an ancestor area partly covered? Then only the column itself is missing.
    o_min_cover(L, FootKey{0, cx, cz});
}

// Fills the parts of F within the radius that nothing covers. `ref`: a view F is known to be covered for (whole
// footprints inside its radius are skipped); null: no such view.
void o_gap(cv_lod* L, const FootKey& f, const std::vector<cv_lod_viewer>* ref, int32_t ref_radius) {
    if (foot_dmin(f, L->o_viewers) > static_cast<double>(L->o_policy.radius)) return;
    if (o_ready(L, f) == foot_total(f.level)) return;
    if (L->drawn.count(f) != 0) return;
    if (ref != nullptr && !ref->empty() && foot_dmax(f, *ref) <= static_cast<double>(ref_radius)) return;
    if (L->below.count(f) == 0) {
        o_min_cover(L, f);
        return;
    }
    for (int i = 0; i < 4; ++i) o_gap(L, foot_child(f, i), ref, ref_radius);
}

void o_clear_all(cv_lod* L) {
    std::vector<FootKey> all;
    all.reserve(L->drawn.size());
    for (const auto& [f, d] : L->drawn) all.push_back(f);
    for (const FootKey& f : all) o_remove_foot(L, f);
    L->root_feet.clear();
    L->below.clear();
}

// Replaces root r's cover with the commit's. Returns false when the commit is refused (stale view / root level).
bool o_apply_commit(cv_lod* L, const Commit& c, std::vector<FootKey>& touched) {
    if (c.view_version < L->min_view_version || c.root_level != L->groups_level || !L->have_view) return false;
    auto rf = L->root_feet.find(c.root);
    if (rf != L->root_feet.end()) {
        std::vector<FootKey> old(rf->second.begin(), rf->second.end());
        for (const FootKey& f : old) o_remove_foot(L, f);
    }
    // Nodes grouped per stack footprint (nodes are sorted by (level, x, y, z), so the same x interleaves z).
    std::vector<size_t> idx(c.g.nodes.size());
    for (size_t i = 0; i < idx.size(); ++i) idx[i] = i;
    std::sort(idx.begin(), idx.end(), [&](size_t a, size_t b) {
        const FootKey fa = foot_of(c.g.nodes[a]), fb = foot_of(c.g.nodes[b]);
        if (!(fa == fb)) return fa < fb;
        return c.g.nodes[a].y < c.g.nodes[b].y;
    });
    std::vector<NodeKey> nodes;
    std::vector<MeshPtr> meshes;
    size_t ni = 0;
    for (const FootKey& f : c.g.stacks) {
        while (ni < idx.size() && foot_of(c.g.nodes[idx[ni]]) < f) ++ni;
        nodes.clear();
        meshes.clear();
        while (ni < idx.size() && foot_of(c.g.nodes[idx[ni]]) == f) {
            nodes.push_back(c.g.nodes[idx[ni]]);
            meshes.push_back(c.meshes[idx[ni]]);
            ++ni;
        }
        o_add_stack(L, f, nodes.data(), meshes.data(), nodes.size());
    }
    for (const FootKey& f : c.g.fallbacks) o_add_fallback(L, f);
    // Readiness the selection did not see yet, then the radius of the current view.
    bool patched = false;
    for (const ReadyChange& rc : L->ready_log) {
        if (rc.seq <= c.seq) continue;
        if (!(foot_at(FootKey{0, rc.cx, rc.cz}, L->groups_level) == c.root)) continue;
        o_ensure_column(L, rc.cx, rc.cz);
        patched = true;
    }
    if (c.view_version != L->o_view_version) {
        const size_t before = L->drawn.size();
        o_gap(L, c.root, &c.viewers, c.radius);
        patched |= L->drawn.size() != before;
    }
    if (patched) touched.push_back(c.root);
    return true;
}

void slot_view(const MeshBuild& m, cv_lod_mesh& v) {
    std::memset(&v, 0, sizeof(v));
    v.level = m.key.level;
    v.cell_size = 1 << m.key.level;
    v.nx = m.key.x;
    v.ny = m.key.y;
    v.nz = m.key.z;
    v.origin[0] = node_origin(m.key.x, m.key.level);
    v.origin[1] = node_origin(m.key.y, m.key.level);
    v.origin[2] = node_origin(m.key.z, m.key.level);
    v.quads = m.quads.empty() ? nullptr : m.quads.data();
    v.quad_count = m.quad_count;
    v.water = m.water.empty() ? nullptr : m.water.data();
    v.water_count = m.water_count;
    for (int a = 0; a < 3; ++a) {
        v.aabb_min[a] = m.aabb_min[a];
        v.aabb_max[a] = m.aabb_max[a];
    }
    v.digest = m.digest;
    v.flags = m.empty() ? 1u : 0u;
}

int32_t hand_out(cv_lod* L, const MeshPtr& m) {
    L->slots.push_back(m);
    return static_cast<int32_t>(L->slots.size() - 1);
}

void push_event(cv_lod* L, int32_t type, int32_t mesh, int32_t level, int64_t x, int64_t y, int64_t z) {
    cv_lod_event e{};
    e.type = type;
    e.mesh = mesh;
    e.level = level;
    e.x = x;
    e.y = y;
    e.z = z;
    L->events.push_back(e);
}

// The tick's net events (deterministic order inside each class).
void o_emit(cv_lod* L) {
    std::vector<std::pair<NodeKey, const TickNode*>> nodes;
    nodes.reserve(L->tick_nodes.size());
    for (const auto& [k, t] : L->tick_nodes) nodes.emplace_back(k, &t);
    std::sort(nodes.begin(), nodes.end(), [](const auto& a, const auto& b) { return a.first < b.first; });
    std::vector<std::pair<FootKey, bool>> fbs(L->tick_fb.begin(), L->tick_fb.end());
    std::sort(fbs.begin(), fbs.end(), [](const auto& a, const auto& b) { return a.first < b.first; });
    for (const auto& [k, t] : nodes) {
        auto it = L->node_mesh.find(k);
        if (!t->was_drawn && it != L->node_mesh.end()) {
            push_event(L, CV_LOD_EV_SHOW, hand_out(L, it->second), k.level, k.x, k.y, k.z);
        }
    }
    for (const auto& [f, was_on] : fbs) {
        if (!was_on && L->drawn.count(f) != 0) push_event(L, CV_LOD_EV_FALLBACK_ON, -1, f.level, f.x, 0, f.z);
    }
    for (const auto& [k, t] : nodes) {
        auto it = L->node_mesh.find(k);
        if (t->was_drawn && it != L->node_mesh.end() && it->second != t->was_mesh) {
            push_event(L, CV_LOD_EV_UPDATE, hand_out(L, it->second), k.level, k.x, k.y, k.z);
        }
    }
    for (const auto& [k, t] : nodes) {
        if (t->was_drawn && L->node_mesh.count(k) == 0) push_event(L, CV_LOD_EV_HIDE, -1, k.level, k.x, k.y, k.z);
    }
    for (const auto& [f, was_on] : fbs) {
        if (was_on) {
            auto it = L->drawn.find(f);
            if (it == L->drawn.end() || !it->second.fallback) push_event(L, CV_LOD_EV_FALLBACK_OFF, -1, f.level, f.x, 0, f.z);
        }
    }
    L->tick_nodes.clear();
    L->tick_fb.clear();
}

void set_ready(cv_lod* L, int64_t cx, int64_t cz, bool ready) {
    const FootKey col{0, cx, cz};
    const bool had = L->ready.count(col) != 0;
    if (had == ready) return;
    if (ready) L->ready.insert(col);
    else L->ready.erase(col);
    for (int lv = 0; lv <= L->max_level; ++lv) {
        const FootKey f{lv, cx >> lv, cz >> lv};
        int64_t& c = L->ready_count[f];
        c += ready ? 1 : -1;
        if (c == 0) L->ready_count.erase(f);
    }
    const ReadyChange rc{++L->ready_seq, cx, cz, ready};
    L->ready_log.push_back(rc);
    L->ready_todo.emplace_back(cx, cz);
    std::lock_guard<std::mutex> g(L->m);
    if (ready) {
        L->ready_cols.insert(std::make_pair(cx, cz));
        release_held_locked(L, std::make_pair(cx, cz));
    } else {
        L->ready_cols.erase(std::make_pair(cx, cz));
    }
    L->ready_in.push_back(rc);
    wake_planner_locked(L);
}

void fill_stats(cv_lod* L, int64_t tick_us) {
    cv_lod_stats st{};
    st.nodes_cached = static_cast<int64_t>(L->cache.count() + L->cache0.count());
    st.cache_bytes = static_cast<int64_t>(L->cache.bytes() + L->cache0.bytes());
    {
        std::lock_guard<std::mutex> g(L->m);
        for (int lv = 1; lv < kLevels; ++lv) st.nodes_dirty += static_cast<int64_t>(L->dirty[lv].size());
        st.nodes_dirty += L->held_cubes;
        st.jobs_queued = static_cast<int64_t>(L->prep_q.size() + L->col_q.size() + L->commits.size() + L->pending.size());
        st.jobs_running = static_cast<int64_t>(L->running.size()) + L->prep_running + L->col_running;
        if (L->plan_busy || L->plan_wake || !L->done.empty() || !L->changed.empty() || !L->ready_in.empty() ||
            !L->owner_touched.empty()) {
            ++st.jobs_running;
        }
    }
    st.nodes_built_total = L->built_total.load();
    st.nodes_loaded_total = L->loaded_total.load();
    st.nodes_saved_total = L->saved_total.load();
    st.meshes_built_total = L->meshes_total.load();
    st.drawn_nodes = L->o_drawn_nodes;
    st.drawn_bytes = L->o_drawn_bytes;
    st.fallback_footprints = L->o_fallbacks;
    st.standby_nodes = L->p_standby.load();
    st.ready_columns = static_cast<int64_t>(L->ready.size());
    st.select_us_last = L->p_select_us.load();
    st.tick_us_last = tick_us;
    L->tick_us_max = std::max(L->tick_us_max, tick_us);
    st.tick_us_max = L->tick_us_max;
    st.errors_total = L->errors_total.load();
    st.corrupt_total = L->corrupt_total.load();
    st.resident_mesh_bytes = L->p_resident_bytes.load();
    st.planner_states = L->p_states.load();
    std::lock_guard<std::mutex> g(L->stats_m);
    L->o_stats = st;
}

int32_t check_level(const cv_lod* L, int32_t level) {
    if (level < 0 || level > L->max_level) return fail_arg("level out of range");
    return CV_OK;
}

} // namespace
} // namespace cenda::voxel::lod

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

int32_t cv_lod_tick(cv_lod* lod, const cv_lod_budget* budget, const cv_lod_event** out_events) {
    if (lod == nullptr || out_events == nullptr) return fail_arg("cv_lod_tick: bad arguments");
    const int64_t t0 = now_us();
    cv_lod* L = lod;
    L->events.clear();
    L->slots.clear();
    L->slot_views.clear();
    std::vector<FootKey> touched;
    // 1. The view: no viewers -> nothing drawn; a new root level -> regroup; else fill what the radius uncovered.
    {
        std::vector<cv_lod_viewer> nv;
        cv_lod_policy np{};
        uint64_t vv = 0;
        bool changed = false;
        {
            std::lock_guard<std::mutex> g(L->view_m);
            if (L->view_version != L->o_view_version) {
                nv = L->viewers;
                np = L->policy;
                vv = L->view_version;
                changed = true;
            }
        }
        if (changed) {
            const std::vector<cv_lod_viewer> old_viewers = L->o_viewers;
            const int32_t old_radius = L->o_policy.radius;
            const bool had_view = L->have_view;
            L->o_viewers = std::move(nv);
            L->o_policy = np;
            L->o_view_version = vv;
            if (L->o_viewers.empty()) {
                o_clear_all(L);
                L->have_view = false;
                L->groups_level = -1;
                L->pending.clear();
                L->min_view_version = vv;
            } else if (!had_view || L->groups_level != np.max_level) {
                o_clear_all(L);
                L->have_view = true;
                L->groups_level = np.max_level;
                L->pending.clear();
                L->min_view_version = vv;
                std::vector<FootKey> roots;
                roots_in_range(L->o_viewers, L->o_policy, roots);
                for (const FootKey& r : roots) o_gap(L, r, nullptr, 0);
            } else {
                // Roots beyond the radius go; the rest only where the old radius did not reach.
                std::vector<FootKey> gone;
                for (const auto& [r, feet] : L->root_feet) {
                    if (foot_dmin(r, L->o_viewers) > static_cast<double>(np.radius)) gone.push_back(r);
                }
                for (const FootKey& r : gone) {
                    auto rf = L->root_feet.find(r);
                    if (rf == L->root_feet.end()) continue;
                    std::vector<FootKey> feet(rf->second.begin(), rf->second.end());
                    for (const FootKey& f : feet) o_remove_foot(L, f);
                    touched.push_back(r);
                }
                std::vector<FootKey> roots;
                roots_in_range(L->o_viewers, L->o_policy, roots);
                for (const FootKey& r : roots) {
                    const size_t before = L->drawn.size();
                    o_gap(L, r, &old_viewers, old_radius);
                    if (L->drawn.size() != before) touched.push_back(r);
                }
            }
        }
    }
    // 2. Readiness changes since the last tick: patched now (same tick, whatever the planner does).
    for (const auto& [cx, cz] : L->ready_todo) o_ensure_column(L, cx, cz);
    L->ready_todo.clear();
    // 3. Prepared commits, nearest first, within the mesh and time budgets (at least one per tick).
    uint64_t planner_seq = 0;
    {
        std::lock_guard<std::mutex> g(L->m);
        for (auto& [r, c] : L->commits) L->pending[r] = std::move(c);
        L->commits.clear();
        planner_seq = L->planner_seq;
    }
    if (!L->pending.empty()) {
        std::vector<std::pair<double, FootKey>> order;
        order.reserve(L->pending.size());
        for (const auto& [r, c] : L->pending) order.emplace_back(c->d, r);
        std::sort(order.begin(), order.end());
        const int64_t limit = budget != nullptr && budget->max_mesh_bytes > 0 ? budget->max_mesh_bytes : INT64_MAX;
        constexpr int64_t kTimeBudgetUs = 300;
        int64_t spent = 0;
        int applied = 0;
        for (const auto& [d, r] : order) {
            auto it = L->pending.find(r);
            const Commit& c = *it->second;
            int64_t cost = 0;
            for (size_t i = 0; i < c.g.nodes.size(); ++i) {
                auto m = L->node_mesh.find(c.g.nodes[i]);
                if (m == L->node_mesh.end() || m->second != c.meshes[i]) cost += c.meshes[i] ? c.meshes[i]->bytes() : 0;
            }
            if (applied > 0 && (spent + cost > limit || now_us() - t0 > kTimeBudgetUs)) break;
            spent += cost;
            if (!o_apply_commit(L, c, touched)) touched.push_back(r);
            L->pending.erase(it);
            ++applied;
        }
    }
    // 4. Readiness log: keep what an outstanding or future commit may still predate.
    {
        uint64_t keep_after = planner_seq;
        for (const auto& [r, c] : L->pending) keep_after = std::min(keep_after, c->seq);
        while (!L->ready_log.empty() && L->ready_log.front().seq <= keep_after) L->ready_log.pop_front();
    }
    if (!touched.empty()) {
        sort_unique(touched);
        std::lock_guard<std::mutex> g(L->m);
        L->owner_touched.insert(L->owner_touched.end(), touched.begin(), touched.end());
        wake_planner_locked(L);
    }
    o_emit(L);
    L->slot_views.resize(L->slots.size());
    for (size_t i = 0; i < L->slots.size(); ++i) slot_view(*L->slots[i], L->slot_views[i]);
    fill_stats(L, now_us() - t0);
    *out_events = L->events.empty() ? nullptr : L->events.data();
    return static_cast<int32_t>(L->events.size());
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
