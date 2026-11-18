// Cenda navigation (cn_*): the world, the search service, raw searches, probes and steering helpers.
// Contract in include/cenda/nav.h (OpenMason #116). Agents are in nav_agents.cpp.

#include "nav_internal.hpp"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <limits>
#include <new>

namespace cenda::nav {

namespace {
thread_local std::string t_error;
std::atomic<uint64_t> g_world_serial{1};

// A finished result nobody collected (its agent was dropped) is forgotten after this many steps.
constexpr uint64_t kResultExpirySteps = 1200;

bool finite3(const double* v) {
    return std::isfinite(v[0]) && std::isfinite(v[1]) && std::isfinite(v[2]);
}

// Positions beyond this are refused outright (their cells could not be formed without overflow).
constexpr double kPositionLimit = 4.0e18;

bool sane3(const double* v) {
    return finite3(v) && std::fabs(v[0]) < kPositionLimit && std::fabs(v[1]) < kPositionLimit
        && std::fabs(v[2]) < kPositionLimit;
}

// floor(v) as an int64 (finite v within kPositionLimit).
int64_t lfloor(double v) {
    return static_cast<int64_t>(std::floor(v));
}


// Saturating int64 add (frame + local cell): out-of-range results clamp, and in_range rejects them.
int64_t add_sat(int64_t a, int64_t b) {
    if (b > 0 && a > INT64_MAX - b) return INT64_MAX;
    if (b < 0 && a < INT64_MIN - b) return INT64_MIN;
    return a + b;
}

bool valid_limits(const cn_limits& l) {
    return l.max_expansions > 0 && l.max_cost > 0.0f && l.heuristic_weight >= 1.0f;
}

int64_t enqueue(cn_world* w, Request&& r) {
    int64_t id = w->next_id++;
    w->requests.emplace(id, std::move(r));
    w->queue.push_back(id);
    w->submitted.fetch_add(1, std::memory_order_relaxed);
    w->in_flight.store(static_cast<int32_t>(w->queue.size()), std::memory_order_relaxed);
    return id;
}

int64_t resolve_now(cn_world* w, Request&& r, int32_t status) {
    int64_t id = w->next_id++;
    r.done = true;
    r.done_step = w->steps;
    r.route.status = status;
    w->requests.emplace(id, std::move(r));
    return id;
}

} // namespace

int32_t fail(int32_t code, const char* message) {
    t_error = message;
    return code;
}

int64_t submit_ground(cn_world* w, const double feet[3], const double goal[3], const Node& frame, float goal_radius,
                      int32_t snap_down, int32_t snap_up, const cn_ground_profile& profile, const cn_limits& limits) {
    if (static_cast<int32_t>(w->queue.size()) >= w->max_in_flight) {
        w->rejected.fetch_add(1, std::memory_order_relaxed);
        return 0;
    }
    Request r;
    r.goal[0] = goal[0];
    r.goal[1] = goal[1];
    r.goal[2] = goal[2];
    r.goal_frame = frame;
    GroundJob& j = r.ground;
    j.start = Node{add_sat(frame.x, lfloor(feet[0])), add_sat(frame.y, floor_add(feet[1], 0.001f)), // nudge off an exact block boundary
                   add_sat(frame.z, lfloor(feet[2]))};
    j.goal = Node{add_sat(frame.x, lfloor(goal[0])), add_sat(frame.y, floor_add(goal[1], 0.001f)),
                  add_sat(frame.z, lfloor(goal[2]))};
    j.anchor = Node{add_sat(frame.x, anchor_of(feet[0])), add_sat(frame.y, anchor_of(feet[1])),
                    add_sat(frame.z, anchor_of(feet[2]))};
    j.goal_radius = goal_radius;
    j.snap_down = snap_down;
    j.snap_up = snap_up;
    j.profile = profile;
    j.limits = limits;
    r.route.anchor = j.anchor;
    if (!in_range(j.start.x, j.start.y, j.start.z) || !in_range(j.goal.x, j.goal.y, j.goal.z)
        || !in_range(j.anchor.x, j.anchor.y, j.anchor.z)) {
        return resolve_now(w, std::move(r), CN_SEARCH_OUT_OF_RANGE);
    }
    return enqueue(w, std::move(r));
}

int64_t submit_air(cn_world* w, const double from[3], const double goal[3], const Node& frame, float goal_radius,
                   int32_t goal_snap_cells, const cn_air_profile& profile, const cn_limits& limits) {
    if (static_cast<int32_t>(w->queue.size()) >= w->max_in_flight) {
        w->rejected.fetch_add(1, std::memory_order_relaxed);
        return 0;
    }
    Request r;
    r.air = true;
    r.goal[0] = goal[0];
    r.goal[1] = goal[1];
    r.goal[2] = goal[2];
    r.goal_frame = frame;
    AirJob& j = r.air_job;
    j.start = Node{add_sat(frame.x, lfloor(from[0])), add_sat(frame.y, lfloor(from[1])), add_sat(frame.z, lfloor(from[2]))};
    j.goal = Node{add_sat(frame.x, lfloor(goal[0])), add_sat(frame.y, lfloor(goal[1])), add_sat(frame.z, lfloor(goal[2]))};
    const Node anchor_blocks{add_sat(frame.x, anchor_of(from[0])), add_sat(frame.y, anchor_of(from[1])),
                             add_sat(frame.z, anchor_of(from[2]))};
    j.goal_radius = goal_radius;
    j.goal_snap_cells = goal_snap_cells;
    j.profile = profile;
    j.limits = limits;
    if (!air_cell_in_range(profile, j.start.x, j.start.y, j.start.z)
        || !air_cell_in_range(profile, j.goal.x, j.goal.y, j.goal.z)
        || !in_range(anchor_blocks.x, anchor_blocks.y, anchor_blocks.z) || !std::isfinite(profile.cruise_y)
        || std::fabs(profile.cruise_y) >= kPositionLimit) {
        j.anchor = Node{};
        j.cruise_cells = 0.0f;
        return resolve_now(w, std::move(r), CN_SEARCH_OUT_OF_RANGE);
    }
    const int64_t size = profile.cell_size;
    j.anchor = Node{cenda::floor_div64(anchor_blocks.x, size), cenda::floor_div64(anchor_blocks.y, size),
                    cenda::floor_div64(anchor_blocks.z, size)};
    // Cruise in cube cells relative to the anchor: the Java's cruiseY / cellSize (float) near the origin.
    const float cruise_blocks = static_cast<float>(static_cast<double>(frame.y - j.anchor.y * size) + profile.cruise_y);
    j.cruise_cells = cruise_blocks / static_cast<float>(profile.cell_size);
    r.route.anchor = Node{j.anchor.x * size, j.anchor.y * size, j.anchor.z * size};
    return enqueue(w, std::move(r));
}

Request* finished(cn_world* w, int64_t id) {
    auto it = w->requests.find(id);
    if (it == w->requests.end() || !it->second.done) return nullptr;
    return &it->second;
}

void release(cn_world* w, int64_t id) {
    auto it = w->requests.find(id);
    if (it == w->requests.end()) return;
    if (!it->second.done) {
        auto q = std::find(w->queue.begin(), w->queue.end(), id);
        if (q != w->queue.end()) w->queue.erase(q);
        w->in_flight.store(static_cast<int32_t>(w->queue.size()), std::memory_order_relaxed);
    }
    w->requests.erase(it);
}

bool blocks(physics_detail::CellView& view, int64_t x, int64_t y, int64_t z, uint32_t mask) {
    // x, y, z are absolute.
    uint16_t shape = 0, fluid = 0;
    view.read(x, y, z, shape, fluid);
    return physics_detail::shape_top(view.world(), shape, mask) >= 1.0f - 1e-3f;
}

float yaw_for(float dir_x, float dir_z, float model_yaw_offset) {
    // Java: (float) Math.toDegrees(Math.atan2(x, z)) + offset; toDegrees = rad * 57.29577951308232.
    double degrees = std::atan2(static_cast<double>(dir_x), static_cast<double>(dir_z)) * 57.29577951308232;
    return static_cast<float>(degrees) + model_yaw_offset;
}

namespace {
float wrap(float delta) {
    while (delta > 180.0f) delta -= 360.0f;
    while (delta < -180.0f) delta += 360.0f;
    return delta;
}
} // namespace

float turn(float yaw, float target, float max_step) {
    float delta = wrap(target - yaw);
    if (std::fabs(delta) > max_step) {
        delta = (delta > 0.0f ? 1.0f : delta < 0.0f ? -1.0f : delta) * max_step;
    }
    return yaw + delta;
}

} // namespace cenda::nav

using namespace cenda::nav;

extern "C" {

int32_t cn_abi_version(void) {
    return CN_ABI_VERSION;
}

const char* cn_last_error(void) {
    return t_error.c_str();
}

cn_world* cn_world_create(const cn_world_desc* desc) {
    if (desc == nullptr || desc->physics == nullptr) {
        fail(CN_ERR_ARG, "null description or physics world");
        return nullptr;
    }
    if (desc->max_y <= desc->min_y) {
        fail(CN_ERR_ARG, "max_y must be above min_y");
        return nullptr;
    }
    // A bound beyond the node range is the same as none (and keeps cy +- 1 from overflowing).
    auto* w = new (std::nothrow) cn_world();
    if (w == nullptr) {
        fail(CN_ERR_ARG, "out of memory");
        return nullptr;
    }
    w->serial = g_world_serial.fetch_add(1, std::memory_order_relaxed);
    w->rules.physics = desc->physics;
    w->rules.solid_mask = desc->solid_mask;
    w->rules.hazard_mask = desc->hazard_mask;
    w->rules.fluid_mask = desc->fluid_mask;
    w->rules.min_y = std::max<int64_t>(desc->min_y, -kCoordLimit);
    w->rules.max_y = std::min<int64_t>(desc->max_y, kCoordLimit);
    w->max_in_flight = desc->max_in_flight <= 0 ? 64 : desc->max_in_flight;
    return w;
}

void cn_world_destroy(cn_world* world) {
    delete world;
}

int32_t cn_world_set_origin(cn_world* world, int64_t ox, int64_t oy, int64_t oz) {
    if (world == nullptr) return fail(CN_ERR_ARG, "null world");
    world->origin = Node{ox, oy, oz};
    return CN_OK;
}

int32_t cn_world_get_origin(const cn_world* world, int64_t* out) {
    if (world == nullptr || out == nullptr) return fail(CN_ERR_ARG, "null world or output");
    out[0] = world->origin.x;
    out[1] = world->origin.y;
    out[2] = world->origin.z;
    return CN_OK;
}

uint32_t cn_cell(cn_world* world, int64_t x, int64_t y, int64_t z, float* out_top) {
    if (out_top) *out_top = 0.0f;
    if (world == nullptr) return CN_CELL_UNKNOWN;
    cenda::physics_detail::begin(world->rules.physics);
    cenda::physics_detail::CellView view(world->rules.physics);
    // Through a one-cell cache, so the top is quantised exactly as a search sees it.
    CellCache cache;
    cache.reset(&world->rules, &view);
    uint32_t flags = cache.flags(x, y, z);
    if (out_top && (flags & (CN_CELL_SOLID | CN_CELL_LIQUID)) != 0u) *out_top = cache.top(x, y, z);
    return flags;
}

int32_t cn_world_step(cn_world* w, int32_t expansion_budget) {
    if (w == nullptr) return fail(CN_ERR_ARG, "null world");
    w->steps++;
    int32_t ran = 0;
    if (!w->queue.empty()) {
        cenda::physics_detail::begin(w->rules.physics);
        cenda::physics_detail::CellView view(w->rules.physics);
        int64_t spent = 0;
        while (!w->queue.empty() && spent < expansion_budget) {
            int64_t id = w->queue.front();
            w->queue.pop_front();
            auto it = w->requests.find(id);
            if (it == w->requests.end()) continue;
            Request& r = it->second;
            auto began = std::chrono::steady_clock::now();
            try {
                r.route = r.air ? w->searcher.air(w->rules, view, r.air_job)
                                : w->searcher.ground(w->rules, view, r.ground);
                if (r.route.status == CN_SEARCH_PARTIAL_BUDGET || r.route.status == CN_SEARCH_PARTIAL_UNREACHABLE) {
                    w->partial.fetch_add(1, std::memory_order_relaxed);
                }
                w->completed.fetch_add(1, std::memory_order_relaxed);
            } catch (...) {
                // A search must never leave its asker waiting: it resolves empty.
                r.route = Route{};
                w->failed.fetch_add(1, std::memory_order_relaxed);
            }
            auto nanos = std::chrono::duration_cast<std::chrono::nanoseconds>(
                std::chrono::steady_clock::now() - began).count();
            w->search_nanos.fetch_add(static_cast<int64_t>(nanos), std::memory_order_relaxed);
            r.done = true;
            r.done_step = w->steps;
            spent += std::max(1, r.route.expansions);
            ++ran;
        }
        w->in_flight.store(static_cast<int32_t>(w->queue.size()), std::memory_order_relaxed);
    }
    // Forget results nobody came back for.
    if ((w->steps & 63u) == 0u) {
        for (auto it = w->requests.begin(); it != w->requests.end();) {
            if (it->second.done && w->steps - it->second.done_step > kResultExpirySteps) {
                it = w->requests.erase(it);
            } else {
                ++it;
            }
        }
    }
    return ran;
}

void cn_stats_get(const cn_world* w, cn_stats* out) {
    if (out == nullptr) return;
    *out = cn_stats{};
    if (w == nullptr) return;
    out->submitted = w->submitted.load(std::memory_order_relaxed);
    out->rejected = w->rejected.load(std::memory_order_relaxed);
    out->completed = w->completed.load(std::memory_order_relaxed);
    out->failed = w->failed.load(std::memory_order_relaxed);
    out->partial = w->partial.load(std::memory_order_relaxed);
    out->search_nanos = w->search_nanos.load(std::memory_order_relaxed);
    out->in_flight = w->in_flight.load(std::memory_order_relaxed);
}

int64_t cn_search_ground(cn_world* w, const cn_ground_query* q) {
    if (w == nullptr || q == nullptr || !sane3(q->feet) || !sane3(q->goal) || !valid_limits(q->limits)) {
        fail(CN_ERR_ARG, "bad ground query");
        return 0;
    }
    return submit_ground(w, q->feet, q->goal, w->origin, q->goal_radius, q->snap_down, q->snap_up, q->profile,
                         q->limits);
}

int64_t cn_search_air(cn_world* w, const cn_air_query* q) {
    if (w == nullptr || q == nullptr || !sane3(q->from) || !sane3(q->goal) || !valid_limits(q->limits)
        || q->profile.cell_size <= 0) {
        fail(CN_ERR_ARG, "bad air query");
        return 0;
    }
    return submit_air(w, q->from, q->goal, w->origin, q->goal_radius, q->goal_snap_cells, q->profile, q->limits);
}

int32_t cn_search_poll(cn_world* w, int64_t id, cn_search_info* info) {
    if (w == nullptr) return fail(CN_ERR_ARG, "null world");
    auto it = w->requests.find(id);
    if (it == w->requests.end()) return fail(CN_ERR_ARG, "unknown search");
    const Request& r = it->second;
    if (!r.done) return 0;
    if (info != nullptr) {
        info->status = r.route.status;
        info->points = static_cast<int32_t>(r.route.points.size() / 3);
        info->complete = r.route.complete ? 1 : 0;
        info->expansions = r.route.expansions;
        info->cost = r.route.cost;
        info->reserved = 0;
    }
    return 1;
}

int32_t cn_search_points(cn_world* w, int64_t id, double* xyz, int32_t cap) {
    if (w == nullptr) return fail(CN_ERR_ARG, "null world");
    Request* r = finished(w, id);
    if (r == nullptr) return 0;
    int32_t n = static_cast<int32_t>(r->route.points.size() / 3);
    if (xyz != nullptr) {
        int32_t copy = std::min(n, std::max(cap, 0));
        const double base[3] = {static_cast<double>(r->route.anchor.x - w->origin.x),
                                static_cast<double>(r->route.anchor.y - w->origin.y),
                                static_cast<double>(r->route.anchor.z - w->origin.z)};
        for (size_t i = 0; i < static_cast<size_t>(copy) * 3; ++i) xyz[i] = base[i % 3] + r->route.points[i];
    }
    return n;
}

void cn_search_release(cn_world* w, int64_t id) {
    if (w != nullptr) release(w, id);
}

float cn_steer_yaw_for(float dir_x, float dir_z, float model_yaw_offset) {
    return yaw_for(dir_x, dir_z, model_yaw_offset);
}

float cn_steer_turn(float yaw, float target_yaw, float max_step) {
    return turn(yaw, target_yaw, max_step);
}

float cn_steer_yaw_error(float yaw, float target_yaw) {
    float delta = target_yaw - yaw;
    while (delta > 180.0f) delta -= 360.0f;
    while (delta < -180.0f) delta += 360.0f;
    return std::fabs(delta);
}

double cn_probe_ground_level(cp_world* physics, double x, double z, double start_y, int32_t scan_up,
                             int32_t scan_down, uint32_t mask) {
    if (physics == nullptr || !std::isfinite(x) || !std::isfinite(z) || !std::isfinite(start_y)) {
        return -std::numeric_limits<double>::infinity();
    }
    cenda::physics_detail::begin(physics);
    cenda::physics_detail::CellView view(physics);
    int64_t o[3];
    cenda::physics_detail::origin(physics, o);
    int64_t bx = lfloor(x) + o[0], bz = lfloor(z) + o[2], from = lfloor(start_y);
    for (int64_t y = from + scan_up; y >= from - scan_down; --y) {
        if (blocks(view, bx, y + o[1], bz, mask) && !blocks(view, bx, y + o[1] + 1, bz, mask)) {
            return static_cast<double>(y) + 1.0;
        }
    }
    return -std::numeric_limits<double>::infinity();
}

double cn_probe_fluid_top(cp_world* physics, double x, double y, double z, int32_t scan_up, int32_t scan_down,
                          uint32_t fluid_mask) {
    if (physics == nullptr || !std::isfinite(x) || !std::isfinite(y) || !std::isfinite(z)) {
        return -std::numeric_limits<double>::infinity();
    }
    cenda::physics_detail::begin(physics);
    cenda::physics_detail::CellView view(physics);
    int64_t o[3];
    cenda::physics_detail::origin(physics, o);
    int64_t bx = lfloor(x) + o[0], bz = lfloor(z) + o[2], from = lfloor(y);
    for (int64_t cy = from + scan_up; cy >= from - scan_down; --cy) {
        uint16_t shape = 0, fluid = 0;
        view.read(bx, cy + o[1], bz, shape, fluid);
        if (cenda::physics_detail::fluid_surface(physics, fluid, fluid_mask) >= 0.0f) {
            return static_cast<double>(cy) + 1.0;
        }
    }
    return -std::numeric_limits<double>::infinity();
}

int32_t cn_probe_clear(cp_world* physics, double x, double y, double z, float radius, uint32_t mask) {
    if (physics == nullptr) return 1;
    if (!std::isfinite(x) || !std::isfinite(y) || !std::isfinite(z) || !std::isfinite(radius)) return 1;
    cenda::physics_detail::begin(physics);
    cenda::physics_detail::CellView view(physics);
    int64_t o[3];
    cenda::physics_detail::origin(physics, o);
    const double r = static_cast<double>(radius);
    int64_t x0 = lfloor(x - r) + o[0], x1 = lfloor(x + r) + o[0];
    int64_t y0 = lfloor(y - r) + o[1], y1 = lfloor(y + r) + o[1];
    int64_t z0 = lfloor(z - r) + o[2], z1 = lfloor(z + r) + o[2];
    for (int64_t bx = x0; bx <= x1; ++bx) {
        for (int64_t by = y0; by <= y1; ++by) {
            for (int64_t bz = z0; bz <= z1; ++bz) {
                if (blocks(view, bx, by, bz, mask)) return 0;
            }
        }
    }
    return 1;
}

double cn_probe_column_peak(cp_world* physics, double x, double z, double from_y, int32_t range, uint32_t mask) {
    if (physics == nullptr || !std::isfinite(x) || !std::isfinite(z) || !std::isfinite(from_y)) {
        return -std::numeric_limits<double>::infinity();
    }
    cenda::physics_detail::begin(physics);
    cenda::physics_detail::CellView view(physics);
    int64_t o[3];
    cenda::physics_detail::origin(physics, o);
    int64_t bx = lfloor(x) + o[0], bz = lfloor(z) + o[2], centre = lfloor(from_y);
    for (int64_t y = centre + range; y >= centre - range; --y) {
        if (blocks(view, bx, y + o[1], bz, mask)) return static_cast<double>(y) + 1.0;
    }
    return -std::numeric_limits<double>::infinity();
}

int32_t cn_reach_ground(cn_world* w, const cn_reach_query* q, cn_reach_cell* out, int32_t cap) {
    if (w == nullptr || q == nullptr || !sane3(q->feet) || q->max_cells <= 0 || q->radius < 0
        || !(q->max_cost > 0.0f) || !(q->profile.height > 0.0f)) {
        return fail(CN_ERR_ARG, "bad reach query");
    }
    const Node& f = w->origin;
    const Node start{add_sat(f.x, lfloor(q->feet[0])), add_sat(f.y, floor_add(q->feet[1], 0.001f)),
                     add_sat(f.z, lfloor(q->feet[2]))};
    const Node anchor{add_sat(f.x, anchor_of(q->feet[0])), add_sat(f.y, anchor_of(q->feet[1])),
                      add_sat(f.z, anchor_of(q->feet[2]))};
    if (!in_range(start.x, start.y, start.z) || !in_range(anchor.x, anchor.y, anchor.z)) return 0;
    cenda::physics_detail::begin(w->rules.physics);
    cenda::physics_detail::CellView view(w->rules.physics);
    return w->searcher.reach(w->rules, view, start, anchor, f.y, q->snap_down, q->snap_up, q->radius, q->max_cells,
                             q->max_cost, q->profile, out, cap);
}

int32_t cn_flood(cp_world* physics, const cn_flood_query* q, cn_flood_cell* out, int32_t cap) {
    if (physics == nullptr || q == nullptr || q->max_cells <= 0 || q->max_steps < 0 || q->max_y <= q->min_y
        || !in_range(q->x, q->y, q->z)) {
        return fail(CN_ERR_ARG, "bad flood query");
    }
    struct Open {
        int64_t x, y, z;
        int32_t depth;
    };
    thread_local std::vector<Open> open;
    thread_local CellIntMap seen(4096);
    open.clear();
    seen.clear();
    cenda::physics_detail::begin(physics);
    cenda::physics_detail::CellView view(physics);
    static constexpr int32_t kDx[6] = {1, -1, 0, 0, 0, 0};
    static constexpr int32_t kDy[6] = {0, 0, 1, -1, 0, 0};
    static constexpr int32_t kDz[6] = {0, 0, 0, 0, 1, -1};

    // Full int64 cells as keys: a flood may travel any distance (max_steps) without aliasing.
    open.push_back(Open{q->x, q->y, q->z, 0});
    seen.put(Node{q->x, q->y, q->z}, 1);
    size_t head = 0;
    int32_t expanded = 0;
    while (head < open.size() && expanded < q->max_cells) {
        const Open cell = open[head++];
        uint16_t shape = 0, fluid = 0;
        view.read(cell.x, cell.y, cell.z, shape, fluid);
        if (out != nullptr && expanded < cap) {
            cn_flood_cell& o = out[expanded];
            o.x = cell.x;
            o.y = cell.y;
            o.z = cell.z;
            o.category = cenda::physics_detail::shape_category(physics, shape);
            o.reserved = 0;
        }
        ++expanded;
        if (cell.depth >= q->max_steps) continue;
        for (int d = 0; d < 6; ++d) {
            int64_t nx = cell.x + kDx[d], ny = cell.y + kDy[d], nz = cell.z + kDz[d];
            if (ny < q->min_y || ny >= q->max_y || !in_range(nx, ny, nz)) continue;
            const Node k{nx, ny, nz};
            if (seen.get(k, 0) != 0) continue;
            uint16_t ns = 0, nf = 0;
            view.read(nx, ny, nz, ns, nf);
            if ((cenda::physics_detail::shape_category(physics, ns) & q->pass_mask) == 0u) continue;
            seen.put(k, 1);
            open.push_back(Open{nx, ny, nz, cell.depth + 1});
        }
    }
    return expanded;
}

} // extern "C"
