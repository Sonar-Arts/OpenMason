// ctest voxel_stream: the streamer (cv_stream_*, OpenMason #121; voxel_world.h "STREAMER RULES") with the
// toy generator (cv_test_toy_generator_create): load-shape levels (a)(b)(c), support + hysteresis, the
// pipeline generate -> install -> populate -> mesh -> events against a brute-force reference, readiness
// ordering, unload/save/reload round trips through cv_store, save_all durability, REMOTE mode, determinism
// across thread counts, budgets, churn, shutdown under load. The owner-side state is inspected between
// ticks (stream_impl.hpp) - safe there: only the owner thread touches it.
//
// CV_STREAM_BENCH=1 adds the large-radius timing run (radius 384 x 128, several viewers);
// CV_STREAM_ONLY=<substring> runs only the matching tests.
#include "cenda/voxel_world.h"
#include "cv_internal.hpp"
#include "mesh_gpu.hpp"
#include "stream_impl.hpp"
#include "stream_plan.hpp"

#include <algorithm>
#include <cmath>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <functional>
#include <map>
#include <set>
#include <string>
#include <thread>
#include <tuple>
#include <vector>
#include <utility>

using namespace cenda::voxel;
using namespace cenda::voxel::streaming;
using cenda::Int3;

namespace {

int g_failures = 0;
int g_checks = 0;

#define CHECK(...)                                                                                  \
    do {                                                                                            \
        ++g_checks;                                                                                 \
        if (!(__VA_ARGS__)) {                                                                       \
            ++g_failures;                                                                           \
            if (g_failures < 60) std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #__VA_ARGS__); \
        }                                                                                           \
    } while (0)

constexpr uint16_t AIR = 0, STONE = 1, LOG = 4, LEAVES = 5;
constexpr uint16_t kSolid = CV_BLOCK_CUBE | CV_BLOCK_OPAQUE | CV_BLOCK_SKY_OPAQUE | CV_BLOCK_VIS_OPAQUE;
// Logs and leaves are not SKY_OPAQUE here: trees must not move min(sky) (keeps the band - and so the result -
// independent of population order; see the determinism test).
const uint16_t kFlags[6] = {
    0, kSolid, kSolid, CV_BLOCK_TRANSLUCENT | CV_BLOCK_TRANSPARENT,
    CV_BLOCK_CUBE | CV_BLOCK_OPAQUE | CV_BLOCK_VIS_OPAQUE, CV_BLOCK_CUBE | CV_BLOCK_TRANSPARENT,
};
constexpr int64_t kMinY = -384, kMaxY = 640;

using K3 = std::tuple<int64_t, int64_t, int64_t>;
using K2 = std::pair<int64_t, int64_t>;
K3 k3(const Int3& c) { return {c.x, c.y, c.z}; }

int64_t now_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

cv_world* make_world(const cv_generator* gen) {
    cv_world_desc d{};
    d.min_y = kMinY;
    d.max_y = kMaxY;
    if (gen != nullptr) {
        d.surface = gen->column_surface;
        d.user = gen->user;
    }
    d.air_id = AIR;
    cv_world* w = cv_world_create(&d);
    cv_block_table_set(w, kFlags, 6);
    cv_layer_define(w, 0, CV_LAYER_SAVED | CV_LAYER_NETWORK);
    return w;
}

// The same world through the native surface factory (cv_world_create_with_generator): no hand-wired callback.
cv_world* make_world_native(const cv_generator* gen) {
    cv_world_desc d{};
    d.min_y = kMinY;
    d.max_y = kMaxY;
    d.air_id = AIR;
    cv_world* w = cv_world_create_with_generator(&d, gen);
    if (w == nullptr) return nullptr;
    cv_block_table_set(w, kFlags, 6);
    cv_layer_define(w, 0, CV_LAYER_SAVED | CV_LAYER_NETWORK);
    return w;
}

cv_center center(int64_t x, int64_t y, int64_t z, int32_t rh, int32_t rv, int32_t below = 16, int32_t above = 8) {
    cv_center c{};
    c.x = x;
    c.y = y;
    c.z = z;
    c.radius_h = rh;
    c.radius_v = rv;
    c.band_below = below;
    c.band_above = above;
    return c;
}

std::string temp_dir(const char* name) {
    static std::atomic<int> seq{0};
    const auto p = std::filesystem::temp_directory_path() /
                   (std::string("cvstream-") + name + "-" + std::to_string(now_ms()) + "-" + std::to_string(++seq));
    std::filesystem::remove_all(p);
    return p.string();
}

// ---- event tracker: checks the event contract every tick ------------------------------------------------------

struct Seen {
    std::set<K3> installed;
    std::map<K3, int64_t> pending;   // UNLOAD_PENDING tick
    std::set<K2> ready;
    std::map<K2, int64_t> unready_tick;
    std::map<K3, int64_t> mesh_rev;  // last handed-out mesh revision
    std::map<K3, int64_t> inst_tick; // tick of the INSTALLED event
    std::map<K3, int64_t> changed;   // CV_EV_CHANGED count per cube
    std::vector<cv_event> last;      // the events of the last tick
    int64_t changes = 0;
    int64_t tick = 0;
    int64_t installs = 0, unloads = 0, meshes = 0, readies = 0, unreadies = 0, pendings = 0;
    int64_t max_installs_tick = 0, max_meshes_tick = 0, max_unloads_tick = 0, max_bytes_tick = 0;
    int64_t max_loaded = 0;
    std::function<void(cv_world*, const cv_event&)> on_pending;
};

int64_t mesh_bytes(const cv_gpu_mesh* m) {
    int64_t b = 0;
    for (int i = 0; i < 3; ++i) b += int64_t{m->quad_count[i]} * 16;
    return b;
}

void observe(cv_stream* s, cv_world* w, Seen& seen, const cv_event* ev, int32_t n) {
    ++seen.tick;
    int64_t inst = 0, mesh = 0, unl = 0;
    std::set<K3> changed_now;
    seen.last.assign(ev, ev + n);
    for (int32_t i = 0; i < n; ++i) {
        const cv_event& e = ev[i];
        const K3 k{e.cx, e.cy, e.cz};
        const K2 col{e.cx, e.cz};
        switch (e.type) {
        case CV_EV_INSTALLED:
            CHECK(seen.installed.count(k) == 0);
            CHECK(cv_cube_exists(w, e.cx, e.cy, e.cz) == 1);
            seen.installed.insert(k);
            seen.inst_tick[k] = seen.tick;
            ++seen.installs;
            ++inst;
            break;
        case CV_EV_UNLOAD_PENDING:
            CHECK(seen.installed.count(k) == 1);
            seen.pending[k] = seen.tick;
            ++seen.pendings;
            ++unl;
            if (seen.on_pending) seen.on_pending(w, e);
            break;
        case CV_EV_UNLOADED: {
            CHECK(seen.installed.count(k) == 1);
            seen.installed.erase(k);
            const auto p = seen.pending.find(k);
            CHECK(p != seen.pending.end() && p->second < seen.tick);   // PENDING strictly in an earlier tick
            if (p != seen.pending.end()) seen.pending.erase(p);
            CHECK(cv_cube_exists(w, e.cx, e.cy, e.cz) == 0);
            seen.mesh_rev.erase(k);
            seen.inst_tick.erase(k);
            ++seen.unloads;
            break;
        }
        case CV_EV_REMESHED: {
            const cv_gpu_mesh* m = cv_stream_mesh(s, e.mesh);
            CHECK(m != nullptr);
            if (m == nullptr) break;
            CHECK(m->cx == e.cx && m->cy == e.cy && m->cz == e.cz);
            CHECK(seen.installed.count(k) == 1);
            CHECK(m->revision <= cv_cube_revision(w, e.cx, e.cy, e.cz));
            CHECK(m->revision > 0);
            const auto prev = seen.mesh_rev.find(k);
            if (prev != seen.mesh_rev.end()) CHECK(m->revision >= prev->second);
            seen.mesh_rev[k] = m->revision;
            seen.max_bytes_tick = std::max(seen.max_bytes_tick, mesh_bytes(m));
            ++seen.meshes;
            ++mesh;
            break;
        }
        case CV_EV_COLUMN_READY:
            CHECK(seen.ready.count(col) == 0);
            CHECK(e.cy == 0);
            seen.ready.insert(col);
            ++seen.readies;
            break;
        case CV_EV_COLUMN_UNREADY:
            CHECK(seen.ready.count(col) == 1);
            seen.ready.erase(col);
            seen.unready_tick[col] = seen.tick;
            ++seen.unreadies;
            break;
        case CV_EV_CHANGED: {
            // Installed before this tick, at most once per tick, and the revision it reports is real.
            CHECK(e.mesh == -1);
            CHECK(seen.installed.count(k) == 1);
            CHECK(cv_cube_exists(w, e.cx, e.cy, e.cz) == 1);
            const auto it = seen.inst_tick.find(k);
            CHECK(it != seen.inst_tick.end() && it->second < seen.tick);
            CHECK(changed_now.insert(k).second);
            const auto cs = s->cubes.find(Int3{e.cx, e.cy, e.cz});
            CHECK(cs != s->cubes.end());
            if (cs != s->cubes.end()) {
                CHECK(cs->second.seen_rev > 0);
                CHECK(cs->second.seen_rev <= cv_cube_revision(w, e.cx, e.cy, e.cz));
            }
            ++seen.changed[k];
            ++seen.changes;
            break;
        }
        default: CHECK(false);
        }
    }
    seen.max_installs_tick = std::max(seen.max_installs_tick, inst);
    seen.max_meshes_tick = std::max(seen.max_meshes_tick, mesh);
    seen.max_unloads_tick = std::max(seen.max_unloads_tick, unl);
    seen.max_loaded = std::max(seen.max_loaded, static_cast<int64_t>(seen.installed.size()));
    // No holes: every READY column's ready band is installed (per events) right now.
    for (const K2& col : seen.ready) {
        const auto it = s->columns.find(Int2{col.first, col.second});
        CHECK(it != s->columns.end());
        if (it == s->columns.end()) continue;
        for (int64_t cy = it->second.ready_lo; cy <= it->second.ready_hi; ++cy) {
            const bool ok = seen.installed.count(K3{col.first, cy, col.second}) == 1;
            CHECK(ok);
            if (!ok) break;
        }
    }
}

struct Env {
    const cv_generator* gen = nullptr;
    bool own_gen = true;
    cv_world* world = nullptr;
    cv_store* store = nullptr;
    cv_stream* stream = nullptr;
    Seen seen;

    Env(int threads, const std::string& store_dir, bool remote = false, int64_t seed = 7, bool native_surface = false,
        uint32_t mesh_flags = CV_MESH_SMOOTH_LIGHTING | CV_MESH_GREEDY, const cv_generator* custom_gen = nullptr) {
        own_gen = custom_gen == nullptr;
        gen = own_gen ? cv_test_toy_generator_create(seed) : custom_gen;
        // the world's surface callback is the generator's (REMOTE too: known air)
        world = native_surface ? make_world_native(gen) : make_world(gen);
        if (!store_dir.empty()) store = cv_store_open(store_dir.c_str());
        cv_stream_desc d{};
        d.store = store;
        d.generator = remote ? nullptr : gen;
        d.worker_threads = threads;
        d.mesh_flags = mesh_flags;
        stream = cv_stream_create(world, &d);
        CHECK(stream != nullptr);
    }
    ~Env() { close(); }
    Env(const Env&) = delete;
    Env& operator=(const Env&) = delete;
    void close() {
        if (stream != nullptr) CHECK(cv_stream_destroy(stream) == CV_OK);
        stream = nullptr;
        if (store != nullptr) CHECK(cv_store_close(store) == CV_OK);
        store = nullptr;
        if (world != nullptr) cv_world_destroy(world);
        world = nullptr;
        if (gen != nullptr && own_gen) cv_test_toy_generator_destroy(gen);
        gen = nullptr;
    }
    bool viewers = false;
    void centers(const std::vector<cv_center>& cs) {
        viewers = !cs.empty();
        CHECK(cv_stream_set_centers(stream, cs.data(), static_cast<int32_t>(cs.size())) == CV_OK);
    }
    int32_t tick(const cv_budget* b = nullptr) {
        const cv_event* ev = nullptr;
        const int32_t n = cv_stream_tick(stream, b, &ev);
        CHECK(n >= 0);
        observe(stream, world, seen, ev, n);
        return n;
    }
    cv_stream_stats stats() const {
        cv_stream_stats st{};
        cv_stream_stats_get(stream, &st);
        return st;
    }
    static bool work_idle(const cv_stream_stats& st) {
        return st.cubes_generating == 0 && st.installs_pending == 0 && st.cubes_meshing == 0 && st.meshes_pending == 0 &&
               st.populating == 0 && st.unloads_pending == 0 && st.cubes_queued == 0;
    }
    bool idle_now() const {
        const cv_stream_stats st = stats();
        return work_idle(st) && st.plan_pending == 0;
    }
    // Ticks until no work is left and two further plans changed no level (with viewers: plans run every 200 ms
    // for the visibility walk), or - without viewers - nothing happened for 300 ms. False on timeout.
    bool settle(int64_t timeout_ms = 60000, const cv_budget* b = nullptr, const std::function<void()>& per_tick = {}) {
        const int64_t end = now_ms() + timeout_ms;
        int64_t idle_since = -1, last_report = now_ms(), base_plans = 0, base_changes = -1;
        while (now_ms() < end) {
            tick(b);
            if (per_tick) per_tick();
            if (std::getenv("CV_STREAM_VERBOSE") != nullptr && now_ms() - last_report > 2000) {
                last_report = now_ms();
                const cv_stream_stats st = stats();
                std::printf("  .. loaded %lld gen %lld inst %lld mesh %lld ready %lld pop %lld unl %lld plan %lld queued %lld tick %lld plan_us %lld walk %lld\n",
                            static_cast<long long>(st.cubes_loaded), static_cast<long long>(st.cubes_generating),
                            static_cast<long long>(st.installs_pending), static_cast<long long>(st.cubes_meshing),
                            static_cast<long long>(st.meshes_pending), static_cast<long long>(st.populating),
                            static_cast<long long>(st.unloads_pending), static_cast<long long>(st.plan_pending),
                            static_cast<long long>(st.cubes_queued), static_cast<long long>(seen.tick), static_cast<long long>(st.plan_us_last), static_cast<long long>(st.walk_cubes));
                std::fflush(stdout);
            }
            const cv_stream_stats st = stats();
            if (work_idle(st) && st.plan_pending == 0) {
                if (idle_since < 0 || st.level_changes_total != base_changes) {
                    idle_since = now_ms();
                    base_plans = st.plans_total;
                    base_changes = st.level_changes_total;
                } else if (viewers ? st.plans_total >= base_plans + 2 : now_ms() - idle_since >= 300) {
                    return true;
                }
            } else if (!work_idle(st)) {
                idle_since = -1;
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
        const cv_stream_stats st = stats();
        std::printf("  settle timeout: gen %lld inst %lld mesh %lld ready %lld pop %lld unl %lld plan %lld queued %lld\n",
                    static_cast<long long>(st.cubes_generating), static_cast<long long>(st.installs_pending),
                    static_cast<long long>(st.cubes_meshing), static_cast<long long>(st.meshes_pending),
                    static_cast<long long>(st.populating), static_cast<long long>(st.unloads_pending),
                    static_cast<long long>(st.plan_pending), static_cast<long long>(st.cubes_queued));
        return false;
    }
    const CubeState* state(int64_t x, int64_t y, int64_t z) const {
        const auto it = stream->cubes.find(Int3{x, y, z});
        return it == stream->cubes.end() ? nullptr : &it->second;
    }
};

// ---- reference: raw terrain + all populations of a cube's 8 origins, in a scratch world -----------------------

int32_t ref_get(cv_world* w, int64_t x, int64_t y, int64_t z) { return cv_get(w, x, y, z); }
int32_t ref_set(cv_world* w, int64_t x, int64_t y, int64_t z, uint16_t id) { return cv_set(w, x, y, z, id); }
int32_t ref_lget(cv_world* w, int32_t l, int64_t x, int64_t y, int64_t z) { return cv_layer_get(w, l, x, y, z); }
int32_t ref_lset(cv_world* w, int32_t l, int64_t x, int64_t y, int64_t z, uint16_t v) { return cv_layer_set(w, l, x, y, z, v); }
int32_t ref_sy(cv_world* w, int64_t x, int64_t z, int64_t* y) { return cv_surface_y(w, x, z, y); }
const cv_gen_api kRefApi = {ref_get, ref_set, ref_lget, ref_lset, ref_sy, {}};

void raw_install(const cv_generator* gen, cv_world* w, int64_t cx, int64_t cy, int64_t cz) {
    if (cy < (kMinY >> 5) || cy > ((kMaxY - 1) >> 5)) return;
    std::vector<uint16_t> ids(CV_CUBE_CELLS), layer(CV_CUBE_CELLS, 0);
    uint16_t* lp[CV_MAX_LAYERS] = {layer.data()};
    const int32_t r = gen->generate_cube(gen->user, cx, cy, cz, ids.data(), lp);
    if (r == 0) {
        std::fill(ids.begin(), ids.end(), ids[0]);
        std::fill(layer.begin(), layer.end(), uint16_t{0});
    }
    CHECK(cv_cube_write_ids(w, cx, cy, cz, ids.data()) == CV_OK);
    CHECK(cv_layer_write_cube(w, 0, cx, cy, cz, layer.data()) == CV_OK);
}

std::vector<uint16_t> reference_cube(const cv_generator* gen, int64_t cx, int64_t cy, int64_t cz) {
    cv_world* w = make_world(gen);
    for (int64_t dy = -1; dy <= 1; ++dy) {
        for (int64_t dz = -1; dz <= 1; ++dz) {
            for (int64_t dx = -1; dx <= 1; ++dx) raw_install(gen, w, cx + dx, cy + dy, cz + dz);
        }
    }
    for (int64_t d = 0; d < 8; ++d) {
        const int64_t ox = cx - (d & 1), oy = cy - ((d >> 1) & 1), oz = cz - ((d >> 2) & 1);
        if (oy < (kMinY >> 5) || oy > ((kMaxY - 1) >> 5)) continue;
        gen->populate(gen->user, &kRefApi, w, ox, oy, oz);
    }
    std::vector<uint16_t> out(CV_CUBE_CELLS);
    CHECK(cv_cube_read_ids(w, cx, cy, cz, out.data()) == CV_OK);
    cv_world_destroy(w);
    return out;
}

std::vector<uint8_t> encode_cube(cv_world* w, int64_t x, int64_t y, int64_t z, uint32_t parts = CV_PARTS_SAVED) {
    std::vector<uint8_t> b(1 << 16);
    int64_t n = cv_cube_encode(w, x, y, z, parts, b.data(), static_cast<int64_t>(b.size()));
    if (CV_IS_NEED(n)) {
        b.resize(static_cast<size_t>(CV_NEEDED(n)));
        n = cv_cube_encode(w, x, y, z, parts, b.data(), static_cast<int64_t>(b.size()));
    }
    CHECK(n > 0);
    b.resize(n > 0 ? static_cast<size_t>(n) : 0);
    return b;
}
std::vector<uint8_t> encode_column(cv_world* w, int64_t x, int64_t z) {
    std::vector<uint8_t> b(1 << 14);
    int64_t n = cv_column_encode(w, x, z, b.data(), static_cast<int64_t>(b.size()));
    if (CV_IS_NEED(n)) {
        b.resize(static_cast<size_t>(CV_NEEDED(n)));
        n = cv_column_encode(w, x, z, b.data(), static_cast<int64_t>(b.size()));
    }
    b.resize(n > 0 ? static_cast<size_t>(n) : 0);
    return b;
}
std::vector<K3> world_cubes(cv_world* w) {
    std::vector<int64_t> xyz(static_cast<size_t>(cv_cube_count(w)) * 3 + 3);
    const int64_t n = cv_cube_list(w, xyz.data(), static_cast<int64_t>(xyz.size() / 3));
    std::vector<K3> out;
    for (int64_t i = 0; i < n; ++i) {
        const auto k = static_cast<size_t>(i) * 3;
        out.emplace_back(xyz[k], xyz[k + 1], xyz[k + 2]);
    }
    std::sort(out.begin(), out.end());
    return out;
}

// ---- tests -----------------------------------------------------------------------------------------------------

void test_helpers() {
    Ivs v{{5, 7}, {1, 2}, {3, 4}, {10, 9}, {8, 8}, {20, 25}};
    normalize(v);
    CHECK(v.size() == 2 && v[0].lo == 1 && v[0].hi == 8 && v[1].lo == 20 && v[1].hi == 25);
    clip(v, 2, 22);
    CHECK(v.size() == 2 && v[0].lo == 2 && v[1].hi == 22);
    const Runs r = build_runs(Ivs{{3, 4}}, Ivs{{2, 5}}, Ivs{{0, 9}});
    CHECK(r.size() == 5);
    CHECK(level_at(r, 0) == kLevelKeep && level_at(r, 2) == kLevelSupport && level_at(r, 3) == kLevelWanted &&
          level_at(r, 5) == kLevelSupport && level_at(r, 9) == kLevelKeep && level_at(r, 10) == kLevelNone &&
          level_at(r, -1) == kLevelNone);

    // Column record envelope.
    const std::vector<int64_t> pop{-3, -2, -1, 4, 9, 10};
    const uint8_t core[5] = {1, 2, 3, 4, 5};
    std::vector<uint8_t> rec;
    build_column_record(pop, core, 5, rec);
    std::vector<int64_t> back;
    const uint8_t* c = nullptr;
    size_t cl = 0;
    CHECK(parse_column_record(rec.data(), rec.size(), back, &c, &cl));
    CHECK(back == pop && cl == 5 && std::memcmp(c, core, 5) == 0);
    CHECK(rec.size() == 4 + 4 + 3 * 16 + 4 + 5);   // three runs
    for (size_t cut = 0; cut < rec.size(); ++cut) CHECK(!parse_column_record(rec.data(), cut, back, &c, &cl));
    std::vector<uint8_t> bad = rec;
    bad[0] ^= 1;
    CHECK(!parse_column_record(bad.data(), bad.size(), back, &c, &cl));
    bad = rec;
    bad[15] = 0x7f;   // run lo > hi
    CHECK(!parse_column_record(bad.data(), bad.size(), back, &c, &cl));
}

// Levels of a plan for one viewer set, through the planner directly (no stream).
struct LevelMap {
    std::map<K3, uint8_t> lv;
    std::map<K2, std::pair<int64_t, int64_t>> band;
    std::vector<LoadItem> list;
    uint8_t at(int64_t x, int64_t y, int64_t z) const {
        const auto it = lv.find(K3{x, y, z});
        return it == lv.end() ? static_cast<uint8_t>(kLevelNone) : it->second;
    }
};
LevelMap plan(cv_world* w, const cv_generator* gen, bool remote, const std::vector<cv_center>& cs) {
    Planner p(w, gen, remote);
    PlanBatch b;
    p.compute(cs, 0, false, b);
    LevelMap m;
    for (const CubeChange& ch : b.cubes) m.lv[k3(ch.c)] = ch.level;
    for (const ColumnChange& cc : b.columns) {
        if (cc.has_band) m.band[K2{cc.c.x, cc.c.z}] = {cc.band_lo, cc.band_hi};
    }
    m.list = b.list;
    return m;
}

void test_shapes() {
    const cv_generator* gen = cv_test_toy_generator_create(7);
    cv_world* w = make_world(gen);

    // Rolling land around the origin (ground ~ -2..18), viewer standing on it.
    {
        const LevelMap m = plan(w, gen, false, {center(0, 20, 0, 96, 32)});
        CHECK(m.at(0, 0, 0) == kLevelWanted);    // under the viewer
        CHECK(m.at(0, -1, 0) == kLevelWanted);   // ellipsoid + band (min(sky) - 16)
        CHECK(m.at(0, -3, 0) <= kLevelKeep);     // deeper than ellipsoid, band and support
        CHECK(m.at(0, -8, 0) == kLevelNone);
        // Known air: nothing wholly above the column top is wanted; support reaches one cube above at most.
        for (const auto& [k, l] : m.lv) {
            int64_t tmin = 0, tmax = 0, smin = 0;
            CHECK(cv_top_range(w, std::get<0>(k), std::get<2>(k), &tmin, &tmax, &smin) == CV_OK);
            if (l == kLevelWanted) CHECK(std::get<1>(k) * 32 < tmax);
        }
        CHECK(m.at(0, 3, 0) == kLevelNone);      // sky above the viewer inside the ellipsoid: known air
        // Disc: radius 96 + 27.7 -> the cube column at 7 * 32 + 16 = 240 blocks is out (keep or none at most).
        CHECK(m.at(7, 0, 0) <= kLevelKeep);
        CHECK(m.at(9, 0, 0) == kLevelNone);
        // Priority: ONE distance order (keys ascending), nearest first; band and ellipsoid cubes interleave
        // (a band cube far out comes after an ellipsoid cube near the viewer and vice versa).
        CHECK(!m.list.empty());
        if (!m.list.empty()) {
            const Int3& f = m.list.front().c;
            CHECK(std::abs(f.x) <= 1 && std::abs(f.z) <= 1 && f.y >= -2 && f.y <= 1);
        }
        for (size_t i = 1; i < m.list.size(); ++i) CHECK(m.list[i - 1].key <= m.list[i].key);
        int64_t first_ell = -1, last_ell = -1, first_band = -1, last_band = -1;
        for (size_t i = 0; i < m.list.size(); ++i) {
            const uint64_t rule = m.list[i].key & 3;
            if (rule == kRuleEllipsoid) {
                if (first_ell < 0) first_ell = static_cast<int64_t>(i);
                last_ell = static_cast<int64_t>(i);
            }
            if (rule == kRuleBand) {
                if (first_band < 0) first_band = static_cast<int64_t>(i);
                last_band = static_cast<int64_t>(i);
            }
        }
        CHECK(first_ell >= 0 && first_band >= 0);
        CHECK(first_band < last_ell && first_ell < last_band);   // interleaved, not one class after the other
        CHECK(m.band.count(K2{0, 0}) == 1);
        // Support: every wanted cube's 26 neighbours are at least SUPPORT (inside the bounds).
        for (const auto& [k, l] : m.lv) {
            if (l != kLevelWanted) continue;
            const auto& [x, y, z] = k;
            for (int64_t d = 0; d < 27; ++d) {
                const int64_t nx = x + d % 3 - 1, ny = y + (d / 3) % 3 - 1, nz = z + d / 9 - 1;
                if (ny < (kMinY >> 5) || ny > ((kMaxY - 1) >> 5)) continue;
                CHECK(m.at(nx, ny, nz) >= kLevelSupport);
            }
        }
    }
    // Seabed: the band of a sea column reaches the floor under ~30 blocks of water.
    {
        const LevelMap m = plan(w, gen, false, {center(-300, 10, 0, 160, 32)});
        const int64_t far_cx = (-300 - 128) >> 5;
        CHECK(m.at(far_cx, -2, 0) == kLevelWanted);   // seabed ~ -30: band only (the ellipsoid ends ~ -33 here)
        CHECK(m.at(far_cx, -1, 0) == kLevelWanted);   // water
        CHECK(m.at(far_cx, 0, 0) != kLevelWanted);    // above the sea surface: known air
    }
    // Mountain-top viewer sees the valley floor 150 blocks below (band), not only its ellipsoid.
    {
        const LevelMap m = plan(w, gen, false, {center(300, 161, 0, 256, 64)});
        CHECK(m.at(300 >> 5, 4, 0) == kLevelWanted);   // plateau top under the viewer
        CHECK(m.at(100 >> 5, 0, 0) == kLevelWanted);   // valley ground: band
        CHECK(m.at(100 >> 5, 3, 0) == kLevelNone);     // valley sky: known air
        CHECK(m.at(300 >> 5, -1, 0) == kLevelNone);    // inside the plateau, below the ellipsoid (+ support)
    }
    // Hysteresis: cubes just outside the shape are KEEP; REMOTE has no support level.
    {
        const LevelMap m = plan(w, gen, false, {center(0, 20, 0, 96, 32)});
        bool keep = false;
        for (const auto& [k, l] : m.lv) keep = keep || l == kLevelKeep;
        CHECK(keep);
        const LevelMap r = plan(w, nullptr, true, {center(0, 20, 0, 96, 32)});
        for (const auto& [k, l] : r.lv) CHECK(l != kLevelSupport);
        CHECK(r.at(0, 0, 0) == kLevelWanted);
    }
    // Two viewers: the union of their shapes.
    {
        const LevelMap m = plan(w, gen, false, {center(0, 20, 0, 64, 32), center(2000, 20, 0, 64, 32)});
        CHECK(m.at(0, 0, 0) == kLevelWanted && m.at(2000 >> 5, 0, 0) == kLevelWanted);
        CHECK(m.at(1000 >> 5, 0, 0) == kLevelNone);
    }
    // No viewer: nothing.
    CHECK(plan(w, gen, false, {}).lv.empty());
    cv_world_destroy(w);
    cv_test_toy_generator_destroy(gen);
}

// Full pipeline around one viewer with a store; reference check of populated content; meshing; readiness;
// unload + save + reload byte-identical; save_all; durability across a restart.
void test_pipeline_and_roundtrip() {
    const std::string dir = temp_dir("pipeline");
    std::map<K3, std::vector<uint8_t>> expected;   // encodings at UNLOAD_PENDING (with the blob attached)
    int64_t edit_x = 8, edit_y = 0, edit_z = 8;
    {
        Env env(4, dir);
        env.seen.on_pending = [&](cv_world* w, const cv_event& e) {
            const std::string blob = "blob " + std::to_string(e.cx) + " " + std::to_string(e.cy) + " " + std::to_string(e.cz);
            CHECK(cv_cube_set_blob(w, e.cx, e.cy, e.cz, reinterpret_cast<const uint8_t*>(blob.data()),
                                   static_cast<int64_t>(blob.size())) == CV_OK);
            expected[K3{e.cx, e.cy, e.cz}] = encode_cube(w, e.cx, e.cy, e.cz);
        };
        env.centers({center(8, 20, 8, 80, 32)});
        CHECK(env.settle());
        const cv_stream_stats st = env.stats();
        CHECK(st.generated_total > 0 && st.loaded_total == 0);
        CHECK(st.populated_total > 0);
        CHECK(st.cubes_wanted > 0 && st.cubes_needed >= st.cubes_wanted);
        CHECK(st.errors_total == 0);
        CHECK(st.columns_ready > 0);
        CHECK(env.seen.installs == static_cast<int64_t>(world_cubes(env.world).size()));
        CHECK(static_cast<int64_t>(env.seen.ready.size()) == st.columns_ready);

        // Every wanted cube is installed, complete and meshed; support cubes installed; band columns READY.
        int64_t wanted = 0, logs = 0, border_leaves = 0, checked = 0;
        for (const auto& [c, cs] : env.stream->cubes) {
            if (cs.level >= kLevelSupport) CHECK(cs.phase == kInstalled);
            if (cs.level != kLevelWanted) continue;
            ++wanted;
            CHECK((cs.flags & kComplete) != 0);
            CHECK((cs.flags & kMeshed) != 0);
            std::vector<uint16_t> ids(CV_CUBE_CELLS);
            CHECK(cv_cube_read_ids(env.world, c.x, c.y, c.z, ids.data()) == CV_OK);
            for (int ly = 0; ly < 32; ++ly) {
                for (int lz = 0; lz < 32; ++lz) {
                    for (int lx = 0; lx < 32; ++lx) {
                        const uint16_t id = ids[static_cast<size_t>(CV_CELL_INDEX(lx, ly, lz))];
                        if (id == LOG) ++logs;
                        if (id == LEAVES && (lx < 2 || lz < 2)) ++border_leaves;
                    }
                }
            }
            // Reference: raw terrain + the populations of its 8 origins (order-independent merge).
            if (checked < 24 && (c.y == 0 || (c.x + c.z) % 3 == 0)) {
                ++checked;
                CHECK(reference_cube(env.gen, c.x, c.y, c.z) == ids);
            }
        }
        CHECK(wanted == st.cubes_wanted);
        CHECK(logs > 0);
        CHECK(border_leaves > 0);   // leaves from roots in the -x/-z neighbour cube: populations across borders
        CHECK(checked > 5);
        for (const auto& [k, col] : env.stream->columns) {
            if (col.has_band) CHECK(col.ready);
        }
        for (const auto& [k, rev] : env.seen.mesh_rev) {
            CHECK(rev == cv_cube_revision(env.world, std::get<0>(k), std::get<1>(k), std::get<2>(k)));
        }

        // An edit re-meshes its cube.
        CHECK(cv_surface_y(env.world, edit_x, edit_z, &edit_y) == CV_OK);
        const K3 ek{0, edit_y >> 5, 0};
        CHECK(cv_set(env.world, edit_x, edit_y, edit_z, STONE) >= 0);
        const int64_t rev = cv_cube_revision(env.world, 0, edit_y >> 5, 0);
        for (int i = 0; i < 200 && env.seen.mesh_rev[ek] != rev; ++i) {
            env.tick();
            std::this_thread::sleep_for(std::chrono::milliseconds(2));
        }
        CHECK(env.seen.mesh_rev[ek] == rev);

        // Unload everything (viewer far away): UNREADY before UNLOADED (checked per tick), all saved.
        const auto snapshot = world_cubes(env.world);
        std::map<K2, std::vector<uint8_t>> columns_before;
        for (const auto& [k, col] : env.stream->columns) {
            (void)col;
            columns_before[K2{k.x, k.z}] = encode_column(env.world, k.x, k.z);
        }
        const int64_t saved_before = env.stats().saved_total;
        std::map<K2, std::vector<int64_t>> populated_before;
        for (const auto& [k, col] : env.stream->columns) populated_before[K2{k.x, k.z}] = col.populated;
        env.centers({center(int64_t{1} << 20, 20, 0, 32, 16)});
        CHECK(env.settle());
        int64_t left = 0;
        for (const K3& k : snapshot) {
            const bool gone = cv_cube_exists(env.world, std::get<0>(k), std::get<1>(k), std::get<2>(k)) == 0;
            if (!gone && left++ == 0) {
                const CubeState* cs = env.state(std::get<0>(k), std::get<1>(k), std::get<2>(k));
                std::printf("  left: (%lld %lld %lld) state %s level %d phase %d flags %x\n",
                            static_cast<long long>(std::get<0>(k)), static_cast<long long>(std::get<1>(k)),
                            static_cast<long long>(std::get<2>(k)), cs ? "yes" : "no", cs ? cs->level : -1,
                            cs ? cs->phase : -1, cs ? cs->flags : 0);
            }
        }
        CHECK(left == 0);
        CHECK(env.seen.unreadies > 0);
        CHECK(env.stats().saved_total - saved_before >= static_cast<int64_t>(snapshot.size()));
        CHECK(expected.size() == snapshot.size());
        for (const auto& [k, col] : env.stream->columns) CHECK(k.x > 1000);   // old columns removed

        // Back: everything loads from the store, byte-identical (blocks, layers, blobs), nothing regenerated.
        const int64_t generated = env.stats().generated_total;
        env.centers({center(8, 20, 8, 80, 32)});
        CHECK(env.settle());
        const cv_stream_stats again = env.stats();
        CHECK(again.generated_total == generated);
        CHECK(again.loaded_total >= static_cast<int64_t>(snapshot.size()));
        for (const auto& [k, pop] : populated_before) {   // population flags came back from the column records
            const auto it = env.stream->columns.find(Int2{k.first, k.second});
            if (it != env.stream->columns.end()) CHECK(it->second.populated == pop);
        }
        for (const K3& k : snapshot) {
            const auto& [x, y, z] = k;
            CHECK(cv_cube_exists(env.world, x, y, z) == 1);
            const auto e = expected.find(k);
            CHECK(e != expected.end());
            if (e != expected.end()) CHECK(encode_cube(env.world, x, y, z) == e->second);
        }
        for (const auto& [k, bytes] : columns_before) {
            if (env.stream->columns.count(Int2{k.first, k.second}) == 0) continue;
            CHECK(encode_column(env.world, k.first, k.second) == bytes);
        }
        for (const auto& [c, cs] : env.stream->cubes) {
            if (cs.level == kLevelWanted) CHECK((cs.flags & (kComplete | kMeshed)) == (kComplete | kMeshed));
        }

        // save_all: everything dirty saved and visible in the store.
        CHECK(cv_set(env.world, edit_x, edit_y + 1, edit_z, STONE) >= 0);
        CHECK(cv_stream_save_all(env.stream) == CV_OK);
        std::vector<uint8_t> rec(1 << 16);
        const int64_t n = cv_store_read_cube(env.store, 0, (edit_y + 1) >> 5, 0, rec.data(), static_cast<int64_t>(rec.size()));
        CHECK(n > 0);
        rec.resize(n > 0 ? static_cast<size_t>(n) : 0);
        CHECK(rec == encode_cube(env.world, 0, (edit_y + 1) >> 5, 0));
        env.close();
    }
    // Durability across a restart: a new world + stream on the reopened store sees the same cubes.
    {
        Env env(2, dir);
        env.centers({center(8, 20, 8, 80, 32)});
        CHECK(env.settle());
        const cv_stream_stats st = env.stats();
        CHECK(st.loaded_total > 0);
        CHECK(st.generated_total == 0);
        CHECK(cv_get(env.world, edit_x, edit_y, edit_z) == STONE);       // the edits survived
        CHECK(cv_get(env.world, edit_x, edit_y + 1, edit_z) == STONE);
        for (const auto& [k, bytes] : expected) {
            const auto& [x, y, z] = k;
            if (cv_cube_exists(env.world, x, y, z) != 1) continue;
            if (x == 0 && z == 0 && (y == edit_y >> 5 || y == (edit_y + 1) >> 5)) continue;   // edited later
            CHECK(encode_cube(env.world, x, y, z) == bytes);
        }
    }
    std::filesystem::remove_all(dir);
}

// (c): a tunnel into the cliff is discovered by the visibility walk, cube by cube.
void test_visible_rule() {
    Env env(4, "");
    env.centers({center(150, 20, 4, 128, 16)});
    CHECK(env.settle());
    CHECK(env.stats().walk_cubes > 0);
    const CubeState* mouth = env.state(6, 2, 0);   // cliff-face column: band covers the whole face
    CHECK(mouth != nullptr && mouth->level == kLevelWanted);
    for (int64_t cx = 7; cx <= 8; ++cx) {
        const CubeState* st = env.state(cx, 2, 0);
        CHECK(st != nullptr && st->level == kLevelWanted && st->phase == kInstalled);
        const CubeState* rock = env.state(cx, 2, 2);   // solid rock beside the tunnel: not visible
        CHECK(rock == nullptr || rock->level != kLevelWanted);
    }
    // Without the walk the tunnel is not wanted.
    const LevelMap m = plan(env.world, env.gen, false, {center(150, 20, 4, 128, 16)});
    CHECK(m.at(8, 2, 0) != kLevelWanted);
    std::vector<int64_t> miss(3 * 4096);
    CHECK(cv_stream_missing(env.stream, miss.data(), 4096) == 0);
}

// REMOTE: a client stream fed from a "server" world by the host loop (cv_stream_missing -> encode/decode).
void test_remote() {
    Env server(4, "");
    server.centers({center(8, 20, 8, 96, 32)});
    CHECK(server.settle());

    Env client(2, "", true);
    client.centers({center(8, 20, 8, 64, 32)});
    std::vector<int64_t> miss(3 * 8192);
    std::set<K2> sent_columns;
    int64_t sent = 0;
    auto feed = [&] {
        const int64_t n = cv_stream_missing(client.stream, miss.data(), 8192);
        CHECK(n >= 0);
        for (int64_t i = 0; i < n && i < 64; ++i) {   // the server answers 64 requests per tick
            const auto k = static_cast<size_t>(i) * 3;
            const int64_t x = miss[k], y = miss[k + 1], z = miss[k + 2];
            if (sent_columns.insert(K2{x, z}).second) {
                const auto col = encode_column(server.world, x, z);
                if (!col.empty()) CHECK(cv_column_decode(client.world, x, z, col.data(), static_cast<int64_t>(col.size())) == CV_OK);
            }
            if (cv_cube_exists(server.world, x, y, z) != 1) continue;
            const auto bytes = encode_cube(server.world, x, y, z, CV_PARTS_NETWORK);
            CHECK(cv_cube_decode(client.world, x, y, z, bytes.data(), static_cast<int64_t>(bytes.size())) == CV_OK);
            ++sent;
        }
    };
    const bool settled = client.settle(30000, nullptr, feed);
    CHECK(settled);
    if (!settled) {
        const int64_t n = cv_stream_missing(client.stream, miss.data(), 8192);
        for (int64_t i = 0; i < n && i < 8; ++i) {
            const auto k = static_cast<size_t>(i) * 3;
            const CubeState* cs = client.state(miss[k], miss[k + 1], miss[k + 2]);
            std::printf("  missing (%lld %lld %lld) level %d server has %d\n", static_cast<long long>(miss[k]),
                        static_cast<long long>(miss[k + 1]), static_cast<long long>(miss[k + 2]), cs ? cs->level : -1,
                        cv_cube_exists(server.world, miss[k], miss[k + 1], miss[k + 2]));
        }
    }
    const cv_stream_stats st = client.stats();
    CHECK(sent > 0);
    CHECK(st.generated_total == 0 && st.saved_total == 0 && st.populated_total == 0);
    CHECK(client.seen.installs >= sent);
    CHECK(client.seen.meshes > 0);
    CHECK(st.columns_ready > 0);
    for (const auto& [c, cs] : client.stream->cubes) {
        CHECK(cs.level != kLevelSupport);
        if (cs.level == kLevelWanted) {
            CHECK(cs.phase == kInstalled);
            CHECK((cs.flags & kMeshed) != 0);
        }
    }
    for (const K3& k : world_cubes(client.world)) {
        const auto& [x, y, z] = k;
        CHECK(encode_cube(client.world, x, y, z, CV_PARTS_NETWORK) == encode_cube(server.world, x, y, z, CV_PARTS_NETWORK));
    }
    // A host re-send of an edited cube re-meshes it on the client.
    int64_t sy = 0;
    CHECK(cv_surface_y(server.world, 8, 8, &sy) == CV_OK);
    CHECK(cv_set(server.world, 8, sy, 8, STONE) >= 0);
    const auto bytes = encode_cube(server.world, 0, sy >> 5, 0, CV_PARTS_NETWORK);
    const int64_t meshes = client.seen.meshes;
    CHECK(cv_cube_decode(client.world, 0, sy >> 5, 0, bytes.data(), static_cast<int64_t>(bytes.size())) == CV_OK);
    for (int i = 0; i < 200 && client.seen.meshes == meshes; ++i) {
        client.tick();
        std::this_thread::sleep_for(std::chrono::milliseconds(2));
    }
    CHECK(client.seen.meshes > meshes);
    // Leaving: the client drops everything by the same rules, saving nothing.
    client.centers({});
    CHECK(client.settle());
    CHECK(cv_cube_count(client.world) == 0);
    CHECK(client.stats().saved_total == 0);
    CHECK(client.seen.ready.empty());
}

// A generator whose column_surface is slow (the real one costs about a millisecond per column): the world's
// surface callback AND the planner's TOPs go through it.
struct SlowGen {
    const cv_generator* toy = nullptr;
    cv_generator g{};
    int64_t delay_us = 0;
    std::atomic<int64_t> calls{0};
    std::atomic<int64_t> last_done_ms{0};
};
int32_t slow_surface(void* user, int64_t cx, int64_t cz, int64_t* sky, int64_t* top) {
    auto* m = static_cast<SlowGen*>(user);
    std::this_thread::sleep_for(std::chrono::microseconds(m->delay_us));
    const int32_t r = m->toy->column_surface(m->toy->user, cx, cz, sky, top);
    ++m->calls;
    m->last_done_ms.store(now_ms());
    return r;
}
int32_t slow_generate(void* user, int64_t cx, int64_t cy, int64_t cz, uint16_t* ids, uint16_t* const* layers) {
    auto* m = static_cast<SlowGen*>(user);
    return m->toy->generate_cube(m->toy->user, cx, cy, cz, ids, layers);
}
int32_t slow_populate(void* user, const cv_gen_api* api, cv_world* w, int64_t cx, int64_t cy, int64_t cz) {
    auto* m = static_cast<SlowGen*>(user);
    return m->toy->populate(m->toy->user, api, w, cx, cy, cz);
}
SlowGen* make_slow_gen(int64_t delay_us) {
    auto* m = new SlowGen();
    m->toy = cv_test_toy_generator_create(7);
    m->delay_us = delay_us;
    m->g = *m->toy;
    m->g.user = m;
    m->g.column_surface = slow_surface;
    m->g.generate_cube = slow_generate;
    m->g.populate = slow_populate;
    return m;
}
void free_slow_gen(SlowGen* m) {
    cv_test_toy_generator_destroy(m->toy);
    delete m;
}

// Same area with 1 and 8 workers: identical cubes and column records. A third run plans incrementally (slow
// column_surface: TOPs arrive over many plans) under an install budget: still the same world.
void test_determinism() {
    std::map<K3, std::vector<uint8_t>> cubes[3];
    std::map<K2, std::vector<uint8_t>> cols[3];
    std::map<K2, std::vector<int64_t>> pops[3];
    const int threads[3] = {1, 8, 8};
    SlowGen* slow = make_slow_gen(1500);
    for (int run = 0; run < 3; ++run) {
        Env env(threads[run], "", false, 7, false, CV_MESH_SMOOTH_LIGHTING | CV_MESH_GREEDY, run == 2 ? &slow->g : nullptr);
        env.centers({center(40, 20, -30, 96, 40), center(-150, 30, 60, 64, 32)});
        cv_budget b{};
        b.max_installs = 5;
        CHECK(env.settle(120000, run == 2 ? &b : nullptr));
        for (const K3& k : world_cubes(env.world)) {
            const auto& [x, y, z] = k;
            cubes[run][k] = encode_cube(env.world, x, y, z);
            if (cols[run].count(K2{x, z}) == 0) cols[run][K2{x, z}] = encode_column(env.world, x, z);
        }
        for (const auto& [k, col] : env.stream->columns) pops[run][K2{k.x, k.z}] = col.populated;
    }
    free_slow_gen(slow);
    CHECK(!cubes[0].empty());
    for (int run = 1; run < 3; ++run) {
        CHECK(cubes[0].size() == cubes[run].size());
        CHECK(cubes[0] == cubes[run]);
        CHECK(cols[0] == cols[run]);
        CHECK(pops[0] == pops[run]);
    }
}

void test_budgets() {
    Env env(4, "");
    cv_budget b{};
    b.max_installs = 3;
    b.max_meshes = 2;
    b.max_mesh_bytes = 4096;
    b.max_unloads = 2;
    env.centers({center(0, 20, 0, 64, 32)});
    CHECK(env.settle(60000, &b));
    CHECK(env.seen.max_installs_tick <= 3);
    CHECK(env.seen.max_meshes_tick <= 2);
    CHECK(env.seen.installs > 6);
    env.centers({});
    CHECK(env.settle(60000, &b));
    CHECK(env.seen.max_unloads_tick <= 2);
    CHECK(cv_cube_count(env.world) == 0);
}

// A viewer flying fast back and forth: invariants hold every tick, memory stays bounded, all returns to zero.
void test_churn() {
    const std::string dir = temp_dir("churn");
    {
        Env env(6, dir);
        int64_t peak_states = 0;
        for (int t = 0; t < 600; ++t) {
            const int64_t x = (t % 300 < 150) ? (t % 150) * 24 : (150 - t % 150) * 24;
            env.centers({center(x, 30, (t * 7) % 64, 96, 32)});
            env.tick();
            peak_states = std::max(peak_states, static_cast<int64_t>(env.stream->cubes.size()));
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
        env.centers({center(0, 30, 0, 96, 32)});
        CHECK(env.settle());
        const int64_t steady = env.stats().cubes_needed;
        std::printf("  churn: peak %lld stream states, steady needed %lld, peak installed %lld\n",
                    static_cast<long long>(peak_states), static_cast<long long>(steady),
                    static_cast<long long>(env.seen.max_loaded));
        CHECK(peak_states < steady * 8);   // bounded: never the whole flight path at once
        CHECK(env.seen.max_loaded < steady * 8);
        env.centers({});
        CHECK(env.settle());
        CHECK(cv_cube_count(env.world) == 0);
        CHECK(env.stream->cubes.empty());
        CHECK(env.stream->columns.empty());
        CHECK(env.seen.ready.empty() && env.seen.installed.empty());
        CHECK(env.stats().errors_total == 0);
    }
    std::filesystem::remove_all(dir);
}

// Destroy while workers are busy (generation, population, meshing, saves in flight): destroy saves every cube
// still in the world - its store record equals the cube's encoding afterwards - and leaks nothing (ASan).
void test_shutdown_under_load() {
    for (int round = 0; round < 3; ++round) {
        const std::string dir = temp_dir("shutdown");
        Env env(8, dir);
        env.centers({center(0, 30, 0, 256, 64), center(700, 30, 300, 160, 32)});
        for (int t = 0; t < 10 + round * 25; ++t) {
            env.tick();
            std::this_thread::sleep_for(std::chrono::milliseconds(2));
        }
        CHECK(cv_stream_destroy(env.stream) == CV_OK);
        env.stream = nullptr;
        std::vector<uint8_t> buf(1 << 16);
        int64_t checked = 0;
        for (const K3& k : world_cubes(env.world)) {
            const auto& [x, y, z] = k;
            int64_t n = cv_store_read_cube(env.store, x, y, z, buf.data(), static_cast<int64_t>(buf.size()));
            if (CV_IS_NEED(n)) {
                buf.resize(static_cast<size_t>(CV_NEEDED(n)));
                n = cv_store_read_cube(env.store, x, y, z, buf.data(), static_cast<int64_t>(buf.size()));
            }
            CHECK(n > 0);
            if (n <= 0) continue;
            buf.resize(static_cast<size_t>(n));
            CHECK(buf == encode_cube(env.world, x, y, z));
            buf.resize(1 << 16);
            ++checked;
        }
        CHECK(checked == static_cast<int64_t>(env.seen.installed.size()));
        env.close();
        std::filesystem::remove_all(dir);
    }
}

// Population confinement: the api refuses reads/writes outside the 8 held cubes.
const cv_generator* g_confine_base = nullptr;
std::atomic<int> g_confine_bad{0};
std::atomic<int> g_confine_calls{0};
int32_t confine_populate(void* user, const cv_gen_api* api, cv_world* w, int64_t cx, int64_t cy, int64_t cz) {
    (void)user;
    ++g_confine_calls;
    if (api->get(w, cx * 32 - 1, cy * 32, cz * 32) != CV_ERR_MISSING) ++g_confine_bad;
    if (api->set(w, cx * 32 + 64, cy * 32, cz * 32, STONE) != CV_ERR_ARG) ++g_confine_bad;
    if (api->get(w, cx * 32 + 63, cy * 32 + 63, cz * 32 + 63) < 0 && cy * 32 + 63 < kMaxY) ++g_confine_bad;
    return g_confine_base->populate(g_confine_base->user, api, w, cx, cy, cz);
}
void test_confinement() {
    Env env(4, "");
    g_confine_base = env.gen;
    cv_generator wrapped = *env.gen;
    wrapped.populate = confine_populate;
    CHECK(cv_stream_destroy(env.stream) == CV_OK);
    cv_stream_desc d{};
    d.generator = &wrapped;
    d.worker_threads = 4;
    env.stream = cv_stream_create(env.world, &d);
    env.centers({center(0, 20, 0, 48, 24)});
    CHECK(env.settle());
    CHECK(g_confine_calls.load() > 0);
    CHECK(g_confine_bad.load() == 0);
    env.close();
}

void test_bad_arguments() {
    CHECK(cv_stream_create(nullptr, nullptr) == nullptr);
    const cv_generator* gen = cv_test_toy_generator_create(1);
    cv_world* w = make_world(gen);
    cv_generator bad = *gen;
    bad.abi = 999;
    cv_stream_desc d{};
    d.generator = &bad;
    CHECK(cv_stream_create(w, &d) == nullptr);
    d.generator = gen;
    d.worker_threads = 1;
    cv_stream* s = cv_stream_create(w, &d);
    CHECK(s != nullptr);
    CHECK(cv_stream_set_centers(s, nullptr, 1) == CV_ERR_ARG);
    CHECK(cv_stream_mesh(s, 0) == nullptr);
    CHECK(cv_stream_missing(s, nullptr, 5) == CV_ERR_ARG);
    CHECK(cv_stream_missing(s, nullptr, 0) == 0);
    const cv_center c = center(0, 20, 0, 48, 24);
    CHECK(cv_stream_set_centers(s, &c, 1) == CV_OK);
    std::this_thread::sleep_for(std::chrono::milliseconds(60));
    const cv_event* ev = nullptr;
    CHECK(cv_stream_tick(s, nullptr, &ev) >= 0);
    const int64_t n = cv_stream_missing(s, nullptr, 0);
    CHECK(n == 0 || CV_IS_NEED(n));
    CHECK(cv_stream_destroy(s) == CV_OK);
    cv_world_destroy(w);
    cv_test_toy_generator_destroy(gen);
}

// cv_world_create_with_generator: the world answers never-generated space from the generator's column_surface
// directly (the same answers as a hand-wired desc), argument rules, and a stream over such a world produces the
// same world as one over the hand-wired world.
void test_native_surface() {
    const cv_generator* gen = cv_test_toy_generator_create(7);
    cv_world* wired = make_world(gen);
    cv_world* native = make_world_native(gen);
    CHECK(native != nullptr);
    bool same = native != nullptr;
    for (int64_t cx = -24; cx <= 24 && same; cx += 3) {
        for (int64_t cz = -24; cz <= 24 && same; cz += 5) {
            int64_t a[1024], b[1024];
            same = cv_surface_column(wired, cx, cz, a) == cv_surface_column(native, cx, cz, b) &&
                   std::equal(a, a + 1024, b);
            int64_t t0[3] = {}, t1[3] = {};
            same = same && cv_top_range(wired, cx, cz, &t0[0], &t0[1], &t0[2]) == CV_OK &&
                   cv_top_range(native, cx, cz, &t1[0], &t1[1], &t1[2]) == CV_OK &&
                   std::equal(t0, t0 + 3, t1);
            const int64_t x = cx * 32 + 7, z = cz * 32 + 19;
            int64_t top = 0;
            same = same && cv_top_y(native, x, z, &top) == CV_OK && cv_get(native, x, top, z) == AIR &&
                   cv_get(native, x, top - 1, z) == CV_ERR_MISSING;   // known air above TOP, unknown below
        }
    }
    CHECK(same);
    // Argument rules.
    cv_world_desc d{};
    d.min_y = kMinY;
    d.max_y = kMaxY;
    CHECK(cv_world_create_with_generator(nullptr, gen) == nullptr);
    CHECK(cv_world_create_with_generator(&d, nullptr) == nullptr);
    cv_world_desc wired_desc = d;
    wired_desc.surface = gen->column_surface;
    CHECK(cv_world_create_with_generator(&wired_desc, gen) == nullptr);   // desc must leave surface/user NULL
    cv_generator old_abi = *gen;
    old_abi.abi = CV_ABI_VERSION + 1;
    CHECK(cv_world_create_with_generator(&d, &old_abi) == nullptr && std::strlen(cv_last_error()) > 0);
    cv_generator no_surface = *gen;
    no_surface.column_surface = nullptr;
    cv_world* open = cv_world_create_with_generator(&d, &no_surface);
    CHECK(open != nullptr);
    int64_t y = 0;
    CHECK(cv_top_y(open, 0, 0, &y) == CV_ERR_MISSING);   // no callback: nothing known
    cv_world_destroy(open);
    if (native != nullptr) cv_world_destroy(native);
    cv_world_destroy(wired);
    cv_test_toy_generator_destroy(gen);

    // A stream over the native-surface world generates exactly what one over the wired world does.
    Env a(4, "");
    Env b(4, "", false, 7, true);
    for (Env* e : {&a, &b}) {
        e->centers({center(10, 30, -20, 112, 48)});
        CHECK(e->settle());
    }
    const std::vector<K3> ca = world_cubes(a.world), cb = world_cubes(b.world);
    CHECK(ca == cb && !ca.empty());
    bool equal = ca == cb;
    std::vector<uint16_t> ia(CV_CUBE_CELLS), ib(CV_CUBE_CELLS);
    for (size_t i = 0; i < ca.size() && equal; ++i) {
        const auto [x, y2, z] = ca[i];
        equal = cv_cube_read_ids(a.world, x, y2, z, ia.data()) == CV_OK && cv_cube_read_ids(b.world, x, y2, z, ib.data()) == CV_OK &&
                ia == ib;
    }
    CHECK(equal);
}

// ---- CV_EV_CHANGED, CV_STREAM_NO_MESH, cv_stream_remesh_all (added 2026-11-17) --------------------------------

// Nothing changed since its last report: every installed cube's reported revision is its current one (the
// INSTALLED revision, then each CHANGED - the only two places seen_rev moves), so every change was reported.
void check_all_reported(Env& env) {
    int64_t unreported = 0, cubes = 0;
    for (const auto& [c, cs] : env.stream->cubes) {
        if (cs.phase != kInstalled) continue;
        ++cubes;
        if (cs.seen_rev != cv_cube_revision(env.world, c.x, c.y, c.z)) ++unreported;
    }
    CHECK(cubes > 0);
    CHECK(unreported == 0);
}

std::vector<K3> changed_of_last(const Env& env) {
    std::vector<K3> out;
    for (const cv_event& e : env.seen.last) {
        if (e.type == CV_EV_CHANGED) out.emplace_back(e.cx, e.cy, e.cz);
    }
    return out;
}

// Edits after settling: two writes into one cube (one on its -x border, which also dirties the -x neighbour)
// give exactly one CHANGED next tick, for that cube; a no-op write gives none.
void check_edits_reported(Env& env) {
    int64_t sy = 0;
    CHECK(cv_surface_y(env.world, 8, 8, &sy) == CV_OK);
    const int64_t cy = sy >> 5;
    CHECK(env.state(-1, cy, 0) != nullptr && env.state(-1, cy, 0)->phase == kInstalled);
    const int64_t neighbour_rev = cv_cube_revision(env.world, -1, cy, 0);
    const int32_t a = cv_get(env.world, 0, sy, 8), b = cv_get(env.world, 9, sy, 9);
    CHECK(a >= 0 && b >= 0);
    CHECK(cv_set(env.world, 0, sy, 8, a == STONE ? AIR : STONE) >= 0);   // local x 0: border dirt for (-1, cy, 0)
    CHECK(cv_set(env.world, 9, sy, 9, b == STONE ? AIR : STONE) >= 0);
    env.tick();
    const std::vector<K3> got = changed_of_last(env);
    CHECK(got.size() == 1);
    if (got.size() == 1) CHECK(got[0] == K3(0, cy, 0));
    CHECK(cv_cube_revision(env.world, -1, cy, 0) == neighbour_rev);
    // A write of the id a cell already holds is a no-op (no revision, no dirt): nothing.
    const int32_t again = cv_get(env.world, 9, sy, 9);
    CHECK(cv_set(env.world, 9, sy, 9, static_cast<uint16_t>(again)) == again);
    env.tick();
    CHECK(changed_of_last(env).empty());
    check_all_reported(env);
}

void test_changed_and_no_mesh() {
    const uint32_t mesh = CV_MESH_SMOOTH_LIGHTING | CV_MESH_GREEDY;
    std::vector<K3> meshed_cubes;
    std::map<K3, std::vector<uint8_t>> meshed_content;
    {   // Meshing stream: populations are reported, edits are reported.
        Env env(4, "", false, 7, false, mesh);
        env.centers({center(8, 20, 8, 80, 32)});
        CHECK(env.settle());
        const cv_stream_stats st = env.stats();
        CHECK(st.populated_total > 0 && st.meshed_total > 0);
        CHECK(env.seen.changes > 0);   // trees written into installed cubes
        int64_t max_per_cube = 0;
        for (const auto& [k, n] : env.seen.changed) max_per_cube = std::max(max_per_cube, n);
        CHECK(max_per_cube <= 8);      // at most once per population (8 origins write into a cube), no edits yet
        check_all_reported(env);
        meshed_cubes = world_cubes(env.world);
        for (const K3& k : meshed_cubes) {
            meshed_content[k] = encode_cube(env.world, std::get<0>(k), std::get<1>(k), std::get<2>(k));
        }
        check_edits_reported(env);
    }
    {   // NO_MESH: no mesh is ever built, readiness = band installed + complete, CHANGED as above.
        Env env(4, "", false, 7, false, mesh | CV_STREAM_NO_MESH);
        CHECK(env.stream->no_mesh && env.stream->mesh_flags == mesh);
        env.centers({center(8, 20, 8, 80, 32)});
        CHECK(env.settle());
        const cv_stream_stats st = env.stats();
        CHECK(st.meshed_total == 0 && st.cubes_meshing == 0 && st.meshes_pending == 0);
        CHECK(env.seen.meshes == 0);
        CHECK(st.populated_total > 0);
        CHECK(st.columns_ready > 0);
        CHECK(static_cast<int64_t>(env.seen.ready.size()) == st.columns_ready);
        CHECK(env.seen.changes > 0);
        CHECK(env.stream->mesh_q.empty());
        int64_t wanted = 0, checked = 0;
        for (const auto& [c, cs] : env.stream->cubes) {
            CHECK((cs.flags & (kMeshed | kHasMesh | kMeshQueued | kMeshInFlight)) == 0);
            if (cs.level != kLevelWanted) continue;
            ++wanted;
            CHECK(cs.phase == kInstalled && (cs.flags & kComplete) != 0);
            if (checked < 12 && (c.y == 0 || (c.x + c.z) % 3 == 0)) {
                ++checked;
                std::vector<uint16_t> ids(CV_CUBE_CELLS);
                CHECK(cv_cube_read_ids(env.world, c.x, c.y, c.z, ids.data()) == CV_OK);
                CHECK(reference_cube(env.gen, c.x, c.y, c.z) == ids);
            }
        }
        CHECK(wanted == st.cubes_wanted && checked > 4);
        for (const auto& [k, col] : env.stream->columns) {
            if (!col.has_band) continue;
            CHECK(col.ready);
            for (int64_t cy = col.band_lo; cy <= col.band_hi; ++cy) {
                const CubeState* cs = env.state(k.x, cy, k.z);
                CHECK(cs != nullptr && cs->phase == kInstalled && (cs->flags & kComplete) != 0);
            }
        }
        check_all_reported(env);
        // The same world as the meshing stream's: NO_MESH changes nothing about what loads.
        CHECK(world_cubes(env.world) == meshed_cubes);
        for (const K3& k : meshed_cubes) {
            const auto it = meshed_content.find(k);
            if (it != meshed_content.end()) CHECK(encode_cube(env.world, std::get<0>(k), std::get<1>(k), std::get<2>(k)) == it->second);
        }
        CHECK(cv_stream_remesh_all(env.stream) == 0);
        check_edits_reported(env);
        CHECK(env.seen.meshes == 0);
        // Unloading still works (no mesh state involved).
        env.centers({});
        CHECK(env.settle());
        CHECK(cv_cube_count(env.world) == 0);
        CHECK(env.seen.ready.empty());
    }
}

// The toy generator plus marks of each population in all 8 of its cubes: LAYER mode writes one layer-0 cell
// per cube (layer 0 is not CV_LAYER_MESHED: no dirt, so only the population-complete path can report it);
// SLOW mode writes two LOG cells per cube with a pause between them, so ticks drain the dirty set while
// populations are half-written.
struct MarkGen {
    const cv_generator* toy = nullptr;
    bool slow = false;
    cv_generator g{};
};
int32_t mark_surface(void* u, int64_t cx, int64_t cz, int64_t* sky, int64_t* top) {
    const MarkGen* m = static_cast<const MarkGen*>(u);
    return m->toy->column_surface(m->toy->user, cx, cz, sky, top);
}
int32_t mark_generate(void* u, int64_t cx, int64_t cy, int64_t cz, uint16_t* ids, uint16_t* const* layers) {
    const MarkGen* m = static_cast<const MarkGen*>(u);
    return m->toy->generate_cube(m->toy->user, cx, cy, cz, ids, layers);
}
int32_t mark_populate(void* u, const cv_gen_api* api, cv_world* w, int64_t cx, int64_t cy, int64_t cz) {
    const MarkGen* m = static_cast<const MarkGen*>(u);
    const int32_t r = m->toy->populate(m->toy->user, api, w, cx, cy, cz);
    if (r < 0) return r;
    for (int d = 0; d < 8; ++d) {
        const int64_t ox = (cx + (d & 1)) * 32, oy = (cy + ((d >> 1) & 1)) * 32, oz = (cz + ((d >> 2) & 1)) * 32;
        if (oy < kMinY || oy >= kMaxY) continue;
        // A cell per (cube, writing origin): the 8 origins of a cube use 8 different cells.
        const int64_t x = ox + 4 + 4 * (d & 1), y = oy + 8 + 4 * ((d >> 1) & 1), z = oz + 4 + 4 * ((d >> 2) & 1);
        if (!m->slow) {
            if (api->layer_set(w, 0, x, y, z, 9) < 0) return CV_ERR_ARG;
            continue;
        }
        if (api->set(w, x, y, z, LOG) < 0) return CV_ERR_ARG;
        std::this_thread::sleep_for(std::chrono::microseconds(300));
        if (api->set(w, x + 1, y, z, LOG) < 0) return CV_ERR_ARG;
    }
    return r;
}
MarkGen* make_mark_gen(bool slow) {
    auto* m = new MarkGen();
    m->toy = cv_test_toy_generator_create(7);
    m->slow = slow;
    m->g = *m->toy;
    m->g.user = m;
    m->g.column_surface = mark_surface;
    m->g.generate_cube = mark_generate;
    m->g.populate = mark_populate;
    return m;
}
void free_mark_gen(MarkGen* m) {
    cv_test_toy_generator_destroy(m->toy);
    delete m;
}

void test_changed_population_paths() {
    {   // Layer-only marks dirty nothing: reported only because the population finished.
        MarkGen* m = make_mark_gen(false);
        {
            Env env(4, "", false, 7, false, CV_MESH_SMOOTH_LIGHTING | CV_MESH_GREEDY | CV_STREAM_NO_MESH, &m->g);
            env.centers({center(8, 20, 8, 64, 32)});
            CHECK(env.settle());
            CHECK(env.stats().populated_total > 0 && env.stats().errors_total == 0);
            int64_t marked = 0, marked_changed = 0;
            for (const auto& [c, cs] : env.stream->cubes) {
                if (cs.phase != kInstalled || cv_layer_get(env.world, 0, c.x * 32 + 4, c.y * 32 + 8, c.z * 32 + 4) != 9) continue;
                ++marked;   // its origin c's own mark (d = 0)
                if (env.seen.changed.count(k3(c)) == 1) ++marked_changed;
            }
            CHECK(marked > 0);
            CHECK(marked_changed == marked);
            check_all_reported(env);
        }
        free_mark_gen(m);
    }
    {   // Half-written populations are never reported: one CHANGED per finished population at most.
        MarkGen* m = make_mark_gen(true);
        {
            Env env(4, "", false, 7, false, CV_MESH_SMOOTH_LIGHTING | CV_MESH_GREEDY, &m->g);
            env.centers({center(8, 20, 8, 64, 32)});
            CHECK(env.settle());
            CHECK(env.stats().populated_total > 0 && env.stats().errors_total == 0);
            int64_t max_per_cube = 0;
            for (const auto& [k, n] : env.seen.changed) max_per_cube = std::max(max_per_cube, n);
            CHECK(max_per_cube >= 1 && max_per_cube <= 8);
            check_all_reported(env);
        }
        free_mark_gen(m);
    }
}

// remesh_all after a face-texture change (which dirties nothing): every cube with geometry is handed out
// exactly once more, built from the new tables (a second call while the first round is in flight drops
// everything of the first round), nearest first, within the budget.
void test_remesh_all() {
    CHECK(cv_stream_remesh_all(nullptr) == CV_ERR_ARG);
    const uint32_t mesh = CV_MESH_SMOOTH_LIGHTING | CV_MESH_GREEDY;
    Env env(4, "", false, 7, false, mesh);
    const cv_center viewer = center(8, 20, 8, 80, 32);
    env.centers({viewer});
    CHECK(env.settle());
    std::set<K3> with_mesh;
    for (const auto& [c, cs] : env.stream->cubes) {
        if ((cs.flags & kHasMesh) != 0) with_mesh.insert(k3(c));
    }
    CHECK(with_mesh.size() > 10);
    auto faces = [&](uint16_t layer) {
        cv_face_tex f[6];
        for (auto& t : f) t = cv_face_tex{layer, 0, 0};
        CHECK(cv_block_faces_set(env.world, STONE, f) == CV_OK);
        CHECK(cv_block_faces_set(env.world, 2, f) == CV_OK);
    };
    faces(77);
    const int64_t meshes_before = env.seen.meshes;
    for (int i = 0; i < 10; ++i) {   // nothing dirtied: no re-mesh by itself
        env.tick();
        std::this_thread::sleep_for(std::chrono::milliseconds(2));
    }
    CHECK(env.seen.meshes == meshes_before);

    cv_budget b{};
    b.max_meshes = 3;
    CHECK(cv_stream_remesh_all(env.stream) >= static_cast<int64_t>(with_mesh.size()));
    env.tick(&b);   // dispatches the first round
    std::this_thread::sleep_for(std::chrono::milliseconds(1));
    const int64_t in_flight = env.stream->mesh_in_flight;
    faces(78);
    CHECK(cv_stream_remesh_all(env.stream) >= static_cast<int64_t>(with_mesh.size()));
    CHECK(env.stream->ready_meshes.empty());

    std::map<K3, int64_t> got;
    std::vector<double> dist;
    int64_t worst_tick = 0, mismatches = 0;
    GpuMeshBuild fresh;
    auto per_tick = [&] {
        int64_t n = 0;
        for (const cv_event& e : env.seen.last) {
            if (e.type != CV_EV_REMESHED) continue;
            ++n;
            const K3 k{e.cx, e.cy, e.cz};
            ++got[k];
            const double dx = static_cast<double>(e.cx * 32 + 16 - viewer.x), dy = static_cast<double>(e.cy * 32 + 16 - viewer.y),
                         dz = static_cast<double>(e.cz * 32 + 16 - viewer.z);
            dist.push_back(std::sqrt(dx * dx + dy * dy + dz * dz));
            const cv_gpu_mesh* m = cv_stream_mesh(env.stream, e.mesh);
            CHECK(m != nullptr);
            if (m == nullptr) continue;
            CHECK(build_gpu_mesh(env.world, e.cx, e.cy, e.cz, mesh, fresh) == CV_OK);
            for (int l = 0; l < 3; ++l) {
                const size_t bytes = static_cast<size_t>(m->quad_count[l]) * 16;
                const bool same = m->quad_count[l] == fresh.quad_count[l] &&
                                  (bytes == 0 || std::memcmp(m->quad16[l], fresh.quad16[l].data(), bytes) == 0);
                if (!same) ++mismatches;
            }
        }
        worst_tick = std::max(worst_tick, n);
    };
    CHECK(env.settle(60000, &b, per_tick));
    std::printf("  remesh_all: %zu cubes with geometry, %lld in flight at the second call\n", with_mesh.size(),
                static_cast<long long>(in_flight));
    CHECK(mismatches == 0);      // every hand-out built from the latest tables (layer 78)
    CHECK(worst_tick <= 3);
    CHECK(got.size() == with_mesh.size());
    for (const K3& k : with_mesh) {
        const auto it = got.find(k);
        CHECK(it != got.end() && it->second == 1);
    }
    // Nearest first: the first quarter of hand-outs is nearer than the last quarter.
    if (dist.size() >= 8) {
        const size_t q = dist.size() / 4;
        double first = 0, last = 0;
        for (size_t i = 0; i < q; ++i) {
            first += dist[i];
            last += dist[dist.size() - 1 - i];
        }
        CHECK(first < last);
    }
    CHECK(env.stats().errors_total == 0);
}

// DETAIL RADIUS (2026-11-22, cv_stream_set_detail_radius): CV_BLOCK_DETAIL blocks are meshed as their model only
// in cubes near a viewer; moving the viewer re-meshes exactly the detail cubes that crossed the radius.
void test_detail_radius() {
    CHECK(cv_stream_set_detail_radius(nullptr, 10) == CV_ERR_ARG);
    const uint32_t mesh = CV_MESH_SMOOTH_LIGHTING | CV_MESH_GREEDY;
    Env env(4, "", false, 7, false, mesh);
    auto find_cube = [&](cv_stream* s, const Int3& c) -> CubeState* {
        const auto it = s->cubes.find(c);
        return it == s->cubes.end() ? nullptr : &it->second;
    };
    cv_center viewer = center(8, 20, 8, 112, 32);
    env.centers({viewer});
    CHECK(env.settle());
    // ORE = a detail cube with a bound full-cube model.
    constexpr uint16_t ORE = 6;
    uint16_t flags[7];
    for (int i = 0; i < 6; ++i) flags[i] = kFlags[i];
    flags[ORE] = kSolid | CV_BLOCK_DETAIL;
    cv_block_table_set(env.world, flags, 7);
    std::vector<cv_model_quad> quads;
    for (int f = 0; f < 6; ++f) {
        cv_model_quad q{};
        const int ax = f < 2 ? 1 : (f < 4 ? 2 : 0);   // face order +y -y -z +z +x -x
        const float side = (f == 0 || f == 3 || f == 4) ? 1.0f : 0.0f;
        const int u = (ax + 1) % 3, v = (ax + 2) % 3;
        for (int c = 0; c < 4; ++c) {
            q.pos[c][ax] = side;
            q.pos[c][u] = static_cast<float>(c & 1);
            q.pos[c][v] = static_cast<float>(c >> 1);
            q.uv[c][0] = static_cast<float>(c & 1);
            q.uv[c][1] = static_cast<float>(c >> 1);
        }
        q.normal[ax] = side > 0.5f ? 1.0f : -1.0f;
        q.texture_layer = 9;
        q.cull_face = static_cast<uint8_t>(f);
        quads.push_back(q);
    }
    CHECK(cv_model_define(env.world, 5, quads.data(), 6) == CV_OK);
    CHECK(cv_block_model_bind(env.world, ORE, -1, 0, 5) == CV_OK);
    // A floating ore (air below and above) in an installed cube of column (x, z).
    auto place = [&](int64_t x, int64_t z) -> Int3 {
        for (int64_t y = 400; y > kMinY + 2; --y) {
            const Int3 c{x >> 5, y >> 5, z >> 5};
            const CubeState* st = find_cube(env.stream, c);
            if (st == nullptr || st->phase != kInstalled) continue;
            if (cv_get(env.world, x, y, z) == AIR && cv_get(env.world, x, y - 1, z) == AIR &&
                cv_get(env.world, x, y + 1, z) == AIR) {
                CHECK(cv_set(env.world, x, y, z, ORE) == CV_OK);
                return c;
            }
        }
        CHECK(false);
        return Int3{0, 0, 0};
    };
    const Int3 near_c = place(12, 12);
    const Int3 far_c = place(8 + 80, 8);
    CHECK(cv_stream_set_detail_radius(env.stream, 24) == CV_OK);
    CHECK(cv_stream_remesh_all(env.stream) > 0);
    // Every REMESHED hand-out of a detail cube carries the mode the stream recorded for it.
    int64_t checked = 0;
    auto per_tick = [&] {
        for (const cv_event& e : env.seen.last) {
            if (e.type != CV_EV_REMESHED) continue;
            const cv_gpu_mesh* m = cv_stream_mesh(env.stream, e.mesh);
            if (m == nullptr || (m->flags & 4u) == 0) continue;
            const CubeState* st = find_cube(env.stream, Int3{e.cx, e.cy, e.cz});
            CHECK(st != nullptr && ((st->flags & kDetailMesh) != 0) == ((m->flags & 8u) != 0));
            ++checked;
        }
    };
    CHECK(env.settle(60000, nullptr, per_tick));
    auto state = [&](const Int3& c) { return find_cube(env.stream, c)->flags; };
    CHECK((state(near_c) & kHasDetail) != 0 && (state(near_c) & kDetailMesh) != 0);
    CHECK((state(far_c) & kHasDetail) != 0 && (state(far_c) & kDetailMesh) == 0);
    CHECK(env.stream->detail_cubes.size() == 2);   // only cubes holding exposed ore
    // Walk to the far ore: it gains detail, the first one (now ~80 blocks away) loses it.
    const int64_t meshes_before = env.seen.meshes;
    viewer.x = 88;
    viewer.z = 8;
    env.centers({viewer});
    CHECK(env.settle(60000, nullptr, per_tick));
    CHECK((state(far_c) & kDetailMesh) != 0);
    CHECK((state(near_c) & kDetailMesh) == 0);
    CHECK(env.seen.meshes > meshes_before);
    // radius < 0: every mesh with detail.
    CHECK(cv_stream_set_detail_radius(env.stream, -5) == CV_OK);
    CHECK(env.settle(60000, nullptr, per_tick));
    CHECK((state(near_c) & kDetailMesh) != 0 && (state(far_c) & kDetailMesh) != 0);
    CHECK(checked >= 4);
    CHECK(env.stats().errors_total == 0);
}

// ---- planning and priority (2026-11-17): async TOPs, one distance order -------------------------------------

// Ticks until the first COLUMN_READY (false on timeout).
bool tick_until_ready(Env& env, int64_t timeout_ms) {
    const int64_t end = now_ms() + timeout_ms;
    while (now_ms() < end) {
        env.tick();
        for (const cv_event& e : env.seen.last) {
            if (e.type == CV_EV_COLUMN_READY) return true;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    return false;
}

// (1) The planner no longer computes every TOP before the first plan: with a slow column_surface the columns
// next to the viewer are READY long before the last TOP of the disc is known, and the result is the same world.
void test_async_tops() {
    // Internal peek: never calls the callback.
    {
        SlowGen* m = make_slow_gen(0);
        cv_world* w = make_world(&m->g);
        int64_t a = 0, b = 0, c = 0;
        CHECK(top_range_peek(w, 3, 4, &a, &b, &c) == -1);   // never asked: only the callback knows
        CHECK(m->calls.load() == 0);
        int64_t ta = 0, tb = 0, tc = 0;
        CHECK(cv_top_range(w, 3, 4, &ta, &tb, &tc) == CV_OK);
        CHECK(m->calls.load() == 1);
        CHECK(top_range_peek(w, 3, 4, &a, &b, &c) == 1 && a == ta && b == tb && c == tc);   // cached answer
        raw_install(&m->g, w, 5, 0, 5);
        CHECK(top_range_peek(w, 5, 5, &a, &b, &c) == 1);   // a column record
        CHECK(cv_top_range(w, 5, 5, &ta, &tb, &tc) == CV_OK && a == ta && b == tb && c == tc);
        cv_world_destroy(w);
        cv_world_desc d{};
        d.min_y = kMinY;
        d.max_y = kMaxY;
        cv_world* open = cv_world_create(&d);
        CHECK(top_range_peek(open, 0, 0, &a, &b, &c) == 0);   // no callback: known to be unknown
        cv_world_destroy(open);
        free_slow_gen(m);
    }
    SlowGen* m = make_slow_gen(3000);
    std::vector<K3> slow_cubes;
    {
        Env env(4, "", false, 7, false, CV_MESH_SMOOTH_LIGHTING | CV_MESH_GREEDY, &m->g);
        const int64_t t0 = now_ms();
        env.centers({center(8, 20, 8, 256, 48)});
        CHECK(tick_until_ready(env, 60000));
        const int64_t first_ready = now_ms() - t0;
        const int64_t calls_at_ready = m->calls.load();
        CHECK(env.settle(120000));
        const int64_t calls = m->calls.load();
        const int64_t tops_done = m->last_done_ms.load() - t0;
        std::printf("  slow column_surface (3 ms, 4 workers, r=256): first READY %lld ms after %lld of %lld surface calls; "
                    "last surface call done at %lld ms\n",
                    static_cast<long long>(first_ready), static_cast<long long>(calls_at_ready),
                    static_cast<long long>(calls), static_cast<long long>(tops_done));
        CHECK(calls > 200);                      // the disc: hundreds of columns
        CHECK(calls_at_ready * 2 < calls);       // the first READY came with less than half the TOPs known
        CHECK(first_ready * 2 < tops_done);      // ... and long before the last one was
        CHECK(env.stats().errors_total == 0);
        for (const auto& [k, col] : env.stream->columns) {
            if (col.has_band) CHECK(col.ready);
        }
        slow_cubes = world_cubes(env.world);
    }
    // Same world as a stream with a fast callback (the planner then plans everything at once).
    {
        Env env(4, "");
        env.centers({center(8, 20, 8, 256, 48)});
        CHECK(env.settle(120000));
        CHECK(world_cubes(env.world) == slow_cubes);
    }
    free_slow_gen(m);
}

// (2) Columns go READY nearest first: every column within 64 blocks of the viewer is READY before any column
// beyond 256 blocks (the old order loaded the whole ellipsoid out to the disc edge before any band cube).
void test_ready_order() {
    for (const int threads : {4, 8}) {
        Env env(threads, "");
        std::map<K2, int64_t> ready_tick;
        const int64_t vx = 8, vz = 8;
        env.centers({center(vx, 20, vz, 320, 96)});
        CHECK(env.settle(120000, nullptr, [&] {
            for (const cv_event& e : env.seen.last) {
                if (e.type == CV_EV_COLUMN_READY) ready_tick.emplace(K2{e.cx, e.cz}, env.seen.tick);
            }
        }));
        int64_t near_last = -1, far_first = INT64_MAX, near = 0, far = 0;
        std::vector<std::pair<double, int64_t>> by_dist;
        for (const auto& [k, t] : ready_tick) {
            const double dx = static_cast<double>(k.first * 32 + 16 - vx), dz = static_cast<double>(k.second * 32 + 16 - vz);
            const double d = std::sqrt(dx * dx + dz * dz);
            by_dist.emplace_back(d, t);
            if (d <= 64) {
                near_last = std::max(near_last, t);
                ++near;
            }
            if (d > 256) {
                far_first = std::min(far_first, t);
                ++far;
            }
        }
        std::printf("  threads %d: %zu READY columns; the %lld within 64 blocks by tick %lld, the first of %lld beyond "
                    "256 at tick %lld\n",
                    threads, ready_tick.size(), static_cast<long long>(near), static_cast<long long>(near_last),
                    static_cast<long long>(far), static_cast<long long>(far_first));
        CHECK(near >= 9 && far > 0);
        CHECK(near_last < far_first);
        // Roughly nearest-first overall: the median READY tick grows from the inner to the outer third.
        std::sort(by_dist.begin(), by_dist.end());
        auto median_tick = [&](size_t lo, size_t hi) {
            std::vector<int64_t> v;
            for (size_t i = lo; i < hi; ++i) v.push_back(by_dist[i].second);
            std::sort(v.begin(), v.end());
            return v.empty() ? int64_t{0} : v[v.size() / 2];
        };
        const size_t n = by_dist.size();
        CHECK(median_tick(0, n / 3) <= median_tick(n / 3, 2 * n / 3));
        CHECK(median_tick(n / 3, 2 * n / 3) <= median_tick(2 * n / 3, n));
    }
}

// Timing + throughput + memory (a small version always; CV_STREAM_BENCH=1 for radius 384 x 128 + 4 viewers).
void test_bench() {
    const bool big = std::getenv("CV_STREAM_BENCH") != nullptr;
    const int rh = big ? 384 : 160, rv = big ? 128 : 64;
    const int thread_counts[2] = {big ? 26 : 8, 4};
    for (const int threads : thread_counts) {
        Env env(threads, "");
        const int64_t t0 = now_ms();
        env.centers({center(0, 30, 0, rh, rv)});
        int64_t tick_sum = 0, ticks = 0, tick_max = 0, busy_until = t0;
        const bool ok = env.settle(240000, nullptr, [&] {
            const cv_stream_stats st = env.stats();
            tick_sum += st.tick_us_last;
            tick_max = std::max(tick_max, st.tick_us_last);
            ++ticks;
            if (!env.idle_now()) busy_until = now_ms();
        });
        CHECK(ok);
        const double secs = static_cast<double>(std::max<int64_t>(1, busy_until - t0)) / 1000.0;
        const cv_stream_stats st = env.stats();
        size_t cube_bytes = 0;
        int64_t cubes = 0;
        for (const K3& k : world_cubes(env.world)) {
            cube_bytes += cube_heap_bytes(env.world, std::get<0>(k), std::get<1>(k), std::get<2>(k));
            ++cubes;
        }
        const size_t node = sizeof(std::pair<const Int3, CubeState>) + 2 * sizeof(void*) + sizeof(size_t);
        std::printf("  bench r=%dx%d threads=%d: %lld cubes (%lld wanted) generated+meshed in %.2f s = %.0f cubes/s; "
                    "%lld meshes, %lld populations; tick avg %.1f us max %lld us; plan %lld us; "
                    "cube heap avg %.0f B + stream %zu B/cube\n",
                    rh, rv, threads, static_cast<long long>(cubes), static_cast<long long>(st.cubes_wanted), secs,
                    static_cast<double>(cubes) / secs, static_cast<long long>(st.meshed_total),
                    static_cast<long long>(st.populated_total),
                    ticks != 0 ? static_cast<double>(tick_sum) / static_cast<double>(ticks) : 0.0,
                    static_cast<long long>(tick_max), static_cast<long long>(st.plan_us_last),
                    cubes != 0 ? static_cast<double>(cube_bytes) / static_cast<double>(cubes) : 0.0, node + kCubeSlotBytes);
        // Walking viewer (8 blocks per tick, 60 Hz pacing) with and without an install budget.
        for (const int budget : {0, 6}) {
            cv_budget b{};
            b.max_installs = budget;
            int64_t walk_max = 0, walk_sum = 0;
            const int64_t base = budget == 0 ? 0 : 1600;
            for (int t = 0; t < 200; ++t) {
                env.centers({center(base + t * 8, 30, 0, rh, rv)});
                env.tick(&b);
                const int64_t us = env.stats().tick_us_last;
                walk_sum += us;
                walk_max = std::max(walk_max, us);
                std::this_thread::sleep_for(std::chrono::microseconds(16600));
            }
            std::printf("  walking viewer (8 b/tick, max_installs %d): tick avg %.1f us, max %lld us, plan %lld us\n",
                        budget, static_cast<double>(walk_sum) / 200.0, static_cast<long long>(walk_max),
                        static_cast<long long>(env.stats().plan_us_last));
        }
        if (!big) break;
    }
    if (big) {
        Env env(26, "");
        std::vector<cv_center> cs;
        for (int i = 0; i < 4; ++i) cs.push_back(center(i * 900, 30, (i % 2) * 700, 384, 128));
        const int64_t t0 = now_ms();
        env.centers(cs);
        int64_t tick_max = 0;
        CHECK(env.settle(600000, nullptr, [&] { tick_max = std::max(tick_max, env.stats().tick_us_last); }));
        const cv_stream_stats st = env.stats();
        std::printf("  4 viewers r=384x128: %lld cubes in %.2f s, plan %lld us, tick max %lld us\n",
                    static_cast<long long>(cv_cube_count(env.world)), static_cast<double>(now_ms() - t0) / 1000.0,
                    static_cast<long long>(st.plan_us_last), static_cast<long long>(tick_max));
        int64_t walk_max = 0, walk_sum = 0;
        for (int t = 0; t < 200; ++t) {
            for (auto& c : cs) c.x += 1;
            env.centers(cs);
            env.tick();
            const int64_t us = env.stats().tick_us_last;
            walk_sum += us;
            walk_max = std::max(walk_max, us);
            std::this_thread::sleep_for(std::chrono::milliseconds(2));
        }
        std::printf("  4 walking viewers: tick avg %.1f us, max %lld us, plan %lld us\n",
                    static_cast<double>(walk_sum) / 200.0, static_cast<long long>(walk_max),
                    static_cast<long long>(env.stats().plan_us_last));
    }
}

} // namespace

int main() {
    CHECK(cv_abi_version() == CV_ABI_VERSION);
    const struct {
        const char* name;
        void (*fn)();
    } tests[] = {
        {"helpers + column record envelope", test_helpers},
        {"load shapes (a)(b) + support + hysteresis", test_shapes},
        {"bad arguments", test_bad_arguments},
        {"pipeline + reference + unload/save/reload + durability", test_pipeline_and_roundtrip},
        {"visibility rule (c)", test_visible_rule},
        {"population confinement", test_confinement},
        {"REMOTE mode", test_remote},
        {"determinism 1 vs 8 threads", test_determinism},
        {"budgets", test_budgets},
        {"churn", test_churn},
        {"shutdown under load", test_shutdown_under_load},
        {"native surface factory", test_native_surface},
        {"CHANGED events + NO_MESH", test_changed_and_no_mesh},
        {"remesh_all", test_remesh_all},
        {"detail radius", test_detail_radius},
        {"CHANGED population paths", test_changed_population_paths},
        {"async TOPs (slow column_surface)", test_async_tops},
        {"READY order nearest first", test_ready_order},
        {"bench", test_bench},
    };
    const char* only = std::getenv("CV_STREAM_ONLY");
    for (const auto& t : tests) {
        if (only != nullptr && std::strstr(t.name, only) == nullptr) continue;
        const int before = g_failures;
        const int64_t t0 = now_ms();
        std::printf("[voxel_stream] %s\n", t.name);
        std::fflush(stdout);
        t.fn();
        std::printf("  (%lld ms)\n", static_cast<long long>(now_ms() - t0));
        if (g_failures != before) std::printf("  -> %d failure(s)\n", g_failures - before);
        std::fflush(stdout);
    }
    std::printf("[voxel_stream] %d checks, %d failures\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}
