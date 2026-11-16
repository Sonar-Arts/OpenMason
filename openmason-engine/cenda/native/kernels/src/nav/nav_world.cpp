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

bool finite3(const float* v) {
    return std::isfinite(v[0]) && std::isfinite(v[1]) && std::isfinite(v[2]);
}

// Java's (int) Math.floor(v) for finite v in int range.
int32_t ifloor(float v) {
    return static_cast<int32_t>(std::floor(static_cast<double>(v)));
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

int64_t submit_ground(cn_world* w, const float feet[3], const float goal[3], float goal_radius, int32_t snap_down,
                      int32_t snap_up, const cn_ground_profile& profile, const cn_limits& limits) {
    if (static_cast<int32_t>(w->queue.size()) >= w->max_in_flight) {
        w->rejected.fetch_add(1, std::memory_order_relaxed);
        return 0;
    }
    Request r;
    r.goal[0] = goal[0];
    r.goal[1] = goal[1];
    r.goal[2] = goal[2];
    GroundJob& j = r.ground;
    j.start[0] = ifloor(feet[0]);
    j.start[1] = ifloor(feet[1] + 0.001f); // nudge off an exact block boundary
    j.start[2] = ifloor(feet[2]);
    j.goal[0] = ifloor(goal[0]);
    j.goal[1] = ifloor(goal[1] + 0.001f);
    j.goal[2] = ifloor(goal[2]);
    j.goal_radius = goal_radius;
    j.snap_down = snap_down;
    j.snap_up = snap_up;
    j.profile = profile;
    j.limits = limits;
    if (!in_range(j.start[0], j.start[1], j.start[2]) || !in_range(j.goal[0], j.goal[1], j.goal[2])) {
        return resolve_now(w, std::move(r), CN_SEARCH_OUT_OF_RANGE);
    }
    return enqueue(w, std::move(r));
}

int64_t submit_air(cn_world* w, const float from[3], const float goal[3], float goal_radius, int32_t goal_snap_cells,
                   const cn_air_profile& profile, const cn_limits& limits) {
    if (static_cast<int32_t>(w->queue.size()) >= w->max_in_flight) {
        w->rejected.fetch_add(1, std::memory_order_relaxed);
        return 0;
    }
    Request r;
    r.air = true;
    r.goal[0] = goal[0];
    r.goal[1] = goal[1];
    r.goal[2] = goal[2];
    AirJob& j = r.air_job;
    j.start[0] = ifloor(from[0]);
    j.start[1] = ifloor(from[1]);
    j.start[2] = ifloor(from[2]);
    j.goal[0] = ifloor(goal[0]);
    j.goal[1] = ifloor(goal[1]);
    j.goal[2] = ifloor(goal[2]);
    j.goal_radius = goal_radius;
    j.goal_snap_cells = goal_snap_cells;
    j.profile = profile;
    j.limits = limits;
    if (!air_cell_in_range(profile, j.start[0], j.start[1], j.start[2])
        || !air_cell_in_range(profile, j.goal[0], j.goal[1], j.goal[2])) {
        return resolve_now(w, std::move(r), CN_SEARCH_OUT_OF_RANGE);
    }
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
    w->rules.min_y = desc->min_y;
    w->rules.max_y = desc->max_y;
    w->max_in_flight = desc->max_in_flight <= 0 ? 64 : desc->max_in_flight;
    return w;
}

void cn_world_destroy(cn_world* world) {
    delete world;
}

uint32_t cn_cell(cn_world* world, int32_t x, int32_t y, int32_t z, float* out_top) {
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
    if (w == nullptr || q == nullptr || !finite3(q->feet) || !finite3(q->goal) || !valid_limits(q->limits)) {
        fail(CN_ERR_ARG, "bad ground query");
        return 0;
    }
    return submit_ground(w, q->feet, q->goal, q->goal_radius, q->snap_down, q->snap_up, q->profile, q->limits);
}

int64_t cn_search_air(cn_world* w, const cn_air_query* q) {
    if (w == nullptr || q == nullptr || !finite3(q->from) || !finite3(q->goal) || !valid_limits(q->limits)
        || q->profile.cell_size <= 0) {
        fail(CN_ERR_ARG, "bad air query");
        return 0;
    }
    return submit_air(w, q->from, q->goal, q->goal_radius, q->goal_snap_cells, q->profile, q->limits);
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

int32_t cn_search_points(cn_world* w, int64_t id, float* xyz, int32_t cap) {
    if (w == nullptr) return fail(CN_ERR_ARG, "null world");
    Request* r = finished(w, id);
    if (r == nullptr) return 0;
    int32_t n = static_cast<int32_t>(r->route.points.size() / 3);
    if (xyz != nullptr) {
        int32_t copy = std::min(n, std::max(cap, 0));
        std::copy_n(r->route.points.data(), static_cast<size_t>(copy) * 3, xyz);
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

float cn_probe_ground_level(cp_world* physics, float x, float z, float start_y, int32_t scan_up, int32_t scan_down,
                            uint32_t mask) {
    if (physics == nullptr || !std::isfinite(x) || !std::isfinite(z) || !std::isfinite(start_y)) {
        return -std::numeric_limits<float>::infinity();
    }
    cenda::physics_detail::begin(physics);
    cenda::physics_detail::CellView view(physics);
    int32_t bx = ifloor(x), bz = ifloor(z), from = ifloor(start_y);
    for (int32_t y = from + scan_up; y >= from - scan_down; --y) {
        if (blocks(view, bx, y, bz, mask) && !blocks(view, bx, static_cast<int64_t>(y) + 1, bz, mask)) {
            return static_cast<float>(y) + 1.0f;
        }
    }
    return -std::numeric_limits<float>::infinity();
}

float cn_probe_fluid_top(cp_world* physics, float x, float y, float z, int32_t scan_up, int32_t scan_down,
                         uint32_t fluid_mask) {
    if (physics == nullptr || !std::isfinite(x) || !std::isfinite(y) || !std::isfinite(z)) {
        return -std::numeric_limits<float>::infinity();
    }
    cenda::physics_detail::begin(physics);
    cenda::physics_detail::CellView view(physics);
    int32_t bx = ifloor(x), bz = ifloor(z), from = ifloor(y);
    for (int32_t cy = from + scan_up; cy >= from - scan_down; --cy) {
        uint16_t shape = 0, fluid = 0;
        view.read(bx, cy, bz, shape, fluid);
        if (cenda::physics_detail::fluid_surface(physics, fluid, fluid_mask) >= 0.0f) {
            return static_cast<float>(cy) + 1.0f;
        }
    }
    return -std::numeric_limits<float>::infinity();
}

int32_t cn_probe_clear(cp_world* physics, float x, float y, float z, float radius, uint32_t mask) {
    if (physics == nullptr) return 1;
    if (!std::isfinite(x) || !std::isfinite(y) || !std::isfinite(z) || !std::isfinite(radius)) return 1;
    cenda::physics_detail::begin(physics);
    cenda::physics_detail::CellView view(physics);
    int32_t x0 = ifloor(x - radius), x1 = ifloor(x + radius);
    int32_t y0 = ifloor(y - radius), y1 = ifloor(y + radius);
    int32_t z0 = ifloor(z - radius), z1 = ifloor(z + radius);
    for (int32_t bx = x0; bx <= x1; ++bx) {
        for (int32_t by = y0; by <= y1; ++by) {
            for (int32_t bz = z0; bz <= z1; ++bz) {
                if (blocks(view, bx, by, bz, mask)) return 0;
            }
        }
    }
    return 1;
}

float cn_probe_column_peak(cp_world* physics, float x, float z, float from_y, int32_t range, uint32_t mask) {
    if (physics == nullptr || !std::isfinite(x) || !std::isfinite(z) || !std::isfinite(from_y)) {
        return -std::numeric_limits<float>::infinity();
    }
    cenda::physics_detail::begin(physics);
    cenda::physics_detail::CellView view(physics);
    int32_t bx = ifloor(x), bz = ifloor(z), centre = ifloor(from_y);
    for (int32_t y = centre + range; y >= centre - range; --y) {
        if (blocks(view, bx, y, bz, mask)) return static_cast<float>(y) + 1.0f;
    }
    return -std::numeric_limits<float>::infinity();
}

int32_t cn_flood(cp_world* physics, const cn_flood_query* q, int32_t* out, int32_t cap) {
    if (physics == nullptr || q == nullptr || q->max_cells <= 0 || q->max_steps < 0 || q->max_steps >= (1 << 20)
        || q->max_y <= q->min_y) {
        return fail(CN_ERR_ARG, "bad flood query");
    }
    struct Open {
        int32_t x, y, z, depth;
    };
    thread_local std::vector<Open> open;
    thread_local LongIntMap seen(4096);
    open.clear();
    seen.clear();
    cenda::physics_detail::begin(physics);
    cenda::physics_detail::CellView view(physics);
    static constexpr int32_t kDx[6] = {1, -1, 0, 0, 0, 0};
    static constexpr int32_t kDy[6] = {0, 0, 1, -1, 0, 0};
    static constexpr int32_t kDz[6] = {0, 0, 0, 0, 1, -1};

    // Offsets from the start, 21 bits per axis: a flood never travels farther than max_steps.
    const int32_t sx = q->x, sy = q->y, sz = q->z;
    auto key = [sx, sy, sz](int32_t x, int32_t y, int32_t z) {
        constexpr int64_t bias = int64_t{1} << 20;
        return ((static_cast<int64_t>(x) - sx + bias) << 42) | ((static_cast<int64_t>(y) - sy + bias) << 21)
            | (static_cast<int64_t>(z) - sz + bias);
    };
    open.push_back(Open{q->x, q->y, q->z, 0});
    seen.put(key(q->x, q->y, q->z), 1);
    size_t head = 0;
    int32_t expanded = 0;
    while (head < open.size() && expanded < q->max_cells) {
        const Open cell = open[head++];
        uint16_t shape = 0, fluid = 0;
        view.read(cell.x, cell.y, cell.z, shape, fluid);
        if (out != nullptr && expanded < cap) {
            int32_t* o = out + static_cast<size_t>(expanded) * 4;
            o[0] = cell.x;
            o[1] = cell.y;
            o[2] = cell.z;
            o[3] = static_cast<int32_t>(cenda::physics_detail::shape_category(physics, shape));
        }
        ++expanded;
        if (cell.depth >= q->max_steps) continue;
        for (int d = 0; d < 6; ++d) {
            int32_t nx = cell.x + kDx[d], ny = cell.y + kDy[d], nz = cell.z + kDz[d];
            if (ny < q->min_y || ny >= q->max_y) continue;
            int64_t k = key(nx, ny, nz);
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
