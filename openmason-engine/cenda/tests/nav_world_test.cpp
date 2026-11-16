// Cenda navigation (cn_*) conformance: cells from the physics cache, ground and air searches, the
// service (queue, budget, refusal, release), ground and air agents, probes and steering helpers.
// Route-for-route parity with the retired Java Wayfind core is pinned in engine-core (NavGoldenRoutesTest).

#include "cenda/nav.h"

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <map>
#include <tuple>
#include <vector>

namespace {

int failures = 0;

#define CHECK(cond)                                                              \
    do {                                                                         \
        if (!(cond)) {                                                           \
            std::fprintf(stderr, "%s:%d: CHECK failed: %s\n", __FILE__, __LINE__, #cond); \
            ++failures;                                                          \
        }                                                                        \
    } while (0)

bool near(float a, float b, float eps = 1e-4f) { return std::fabs(a - b) <= eps; }

struct TestWorld {
    std::map<std::tuple<int, int, int>, std::pair<uint16_t, uint16_t>> cells;
    bool unload_negative_x = false;
    void set(int x, int y, int z, uint16_t shape, uint16_t fluid = 0) { cells[{x, y, z}] = {shape, fluid}; }
    void clear(int x, int y, int z) { cells.erase({x, y, z}); }
    void floor(int y, int x0, int x1, int z0, int z1, uint16_t shape) {
        for (int x = x0; x <= x1; ++x)
            for (int z = z0; z <= z1; ++z) set(x, y, z, shape);
    }
};

int32_t fill(void* user, int32_t x0, int32_t y0, int32_t z0, int32_t sx, int32_t sy, int32_t sz, uint16_t* shapes,
             uint16_t* fluids) {
    auto* w = static_cast<TestWorld*>(user);
    if (w->unload_negative_x && x0 < 0) return 0;
    for (int y = 0; y < sy; ++y)
        for (int z = 0; z < sz; ++z)
            for (int x = 0; x < sx; ++x) {
                size_t i = static_cast<size_t>((y * sz + z) * sx + x);
                auto it = w->cells.find({x0 + x, y0 + y, z0 + z});
                shapes[i] = it == w->cells.end() ? 0 : it->second.first;
                fluids[i] = it == w->cells.end() ? 0 : it->second.second;
            }
    return 1;
}

constexpr uint32_t SOLID = 1u, PICK = 2u, LAVA = 4u;
constexpr uint32_t WATER = 1u;
constexpr uint16_t FULL = 1, SNOW1 = 2, FLOWER = 3, HOT = 4, HALF = 5;

struct Fixture {
    TestWorld tw;
    cp_world* physics = nullptr;
    cn_world* nav = nullptr;

    Fixture() {
        cp_world_desc d{};
        d.fill = fill;
        d.user = &tw;
        physics = cp_world_create(&d);
        cp_box full{0, 0, 0, 1, 1, 1};
        cp_box snow{0, 0, 0, 1, 0.125f, 1};
        cp_box half{0, 0, 0, 1, 0.5f, 1};
        cp_shape_define(physics, FULL, &full, 1, SOLID | PICK, 0);
        cp_shape_define(physics, SNOW1, &snow, 1, SOLID | PICK, CP_SHAPE_PARTIAL);
        cp_shape_define(physics, FLOWER, &full, 1, PICK, 0);
        cp_shape_define(physics, HOT, &full, 1, LAVA, 0);
        cp_shape_define(physics, HALF, &half, 1, SOLID | PICK, CP_SHAPE_PARTIAL);
        cp_fluid source{0.875f, 0, 0, 0, WATER, 0};
        cp_fluid_define(physics, 1, &source);
        cn_world_desc nd{};
        nd.physics = physics;
        nd.solid_mask = SOLID;
        nd.hazard_mask = LAVA;
        nd.fluid_mask = WATER;
        nd.min_y = 0;
        nd.max_y = 256;
        nd.max_in_flight = 4;
        nav = cn_world_create(&nd);
        CHECK(nav != nullptr);
    }
    ~Fixture() {
        cn_world_destroy(nav);
        cp_world_destroy(physics);
    }
    void invalidate() { cp_invalidate_all(physics); }
};

cn_ground_profile walker() {
    return cn_ground_profile{1.0f, 0, 0.5f, 1.125f, 1.5f, 3.0f, 0, 3.0f, 8.0f, 0.5f, 2.0f, 0.5f};
}

cn_limits limits(int32_t expansions = 600) { return cn_limits{expansions, 3.4e38f, 1.0f, 0}; }

cn_ground_query ground_query(float fx, float fy, float fz, float gx, float gy, float gz) {
    cn_ground_query q{};
    q.feet[0] = fx; q.feet[1] = fy; q.feet[2] = fz;
    q.goal[0] = gx; q.goal[1] = gy; q.goal[2] = gz;
    q.goal_radius = 0.0f;
    q.snap_down = 4;
    q.snap_up = 2;
    q.profile = walker();
    q.limits = limits();
    return q;
}

std::vector<float> run(cn_world* nav, int64_t id, cn_search_info& info) {
    CHECK(id > 0);
    CHECK(cn_search_poll(nav, id, &info) == 0 || info.status == CN_SEARCH_OUT_OF_RANGE);
    cn_world_step(nav, 1 << 30);
    CHECK(cn_search_poll(nav, id, &info) == 1);
    std::vector<float> pts(static_cast<size_t>(info.points) * 3);
    CHECK(cn_search_points(nav, id, pts.data(), info.points) == info.points);
    cn_search_release(nav, id);
    return pts;
}

void cells_come_from_the_physics_cache() {
    Fixture f;
    f.tw.set(0, 10, 0, FULL);
    f.tw.set(1, 10, 0, SNOW1);
    f.tw.set(2, 10, 0, FLOWER);
    f.tw.set(3, 10, 0, HOT);
    f.tw.set(4, 10, 0, 0, 1);
    float top = -1.0f;
    CHECK(cn_cell(f.nav, 0, 10, 0, &top) == CN_CELL_SOLID && top == 1.0f);
    CHECK(cn_cell(f.nav, 1, 10, 0, &top) == CN_CELL_SOLID && top == 0.125f);
    CHECK(cn_cell(f.nav, 2, 10, 0, &top) == 0u);  // pickable, not solid: open
    CHECK(cn_cell(f.nav, 3, 10, 0, &top) == CN_CELL_HAZARD);
    CHECK(cn_cell(f.nav, 4, 10, 0, &top) == CN_CELL_LIQUID && top == 0.875f);
    CHECK(cn_cell(f.nav, 5, 10, 0, &top) == 0u);
    CHECK(cn_cell(f.nav, 0, -1, 0, &top) == CN_CELL_UNKNOWN);
    CHECK(cn_cell(f.nav, 0, 256, 0, &top) == CN_CELL_UNKNOWN);
    f.tw.unload_negative_x = true;
    CHECK(cn_cell(f.nav, -5, 10, 0, &top) == CN_CELL_UNKNOWN);
    // An edit reaches navigation through the physics invalidation, nothing else.
    f.tw.set(5, 10, 0, FULL);
    CHECK(cn_cell(f.nav, 5, 10, 0, &top) == 0u);
    cp_invalidate_cell(f.physics, 5, 10, 0);
    CHECK(cn_cell(f.nav, 5, 10, 0, &top) == CN_CELL_SOLID);
}

void ground_routes() {
    Fixture f;
    f.tw.floor(63, -2, 20, -4, 4, FULL);
    cn_search_info info{};

    // Flat: the straight run collapses to its two ends, waypoints at cell centres on the floor.
    cn_ground_query flat = ground_query(0.5f, 64, 0.5f, 10.5f, 64, 0.5f);
    auto pts = run(f.nav, cn_search_ground(f.nav, &flat), info);
    CHECK(info.status == CN_SEARCH_FOUND && info.complete == 1);
    CHECK(info.points == 2);
    CHECK(near(pts[0], 0.5f) && near(pts[1], 64.0f) && near(pts[3], 10.5f) && near(pts[5], 0.5f));

    // A one-block step is a jump (within max_climb), a two-block wall is not: partial, ending at it.
    // A one-block step at x 5, then a two-block wall above it at x 6.
    for (int z = -4; z <= 4; ++z) {
        f.tw.set(5, 64, z, FULL);
        f.tw.set(6, 64, z, FULL);
        f.tw.set(6, 65, z, FULL);
        f.tw.set(6, 66, z, FULL);
    }
    f.invalidate();
    cn_ground_query q = ground_query(0.5f, 64, 0.5f, 10.5f, 64, 0.5f);
    pts = run(f.nav, cn_search_ground(f.nav, &q), info);
    CHECK(info.status == CN_SEARCH_PARTIAL_UNREACHABLE && info.complete == 0);
    CHECK(info.points >= 2);
    CHECK(near(pts[static_cast<size_t>(info.points - 1) * 3 + 1], 65.0f)); // standing on the step

    // A snow layer is stood on at its real height (the physics cell top), as a step.
    for (int z = -4; z <= 4; ++z) {
        f.tw.clear(5, 64, z);
        f.tw.clear(6, 64, z);
        f.tw.clear(6, 65, z);
        f.tw.clear(6, 66, z);
        f.tw.set(5, 64, z, SNOW1);
    }
    f.invalidate();
    cn_ground_query to_snow = ground_query(0.5f, 64, 0.5f, 5.5f, 64, 0.5f);
    pts = run(f.nav, cn_search_ground(f.nav, &to_snow), info);
    CHECK(info.status == CN_SEARCH_FOUND);
    CHECK(near(pts[static_cast<size_t>(info.points - 1) * 3], 5.5f));
    CHECK(near(pts[static_cast<size_t>(info.points - 1) * 3 + 1], 64.125f));

    // Hazards are walls; a corridor of lava forces a detour or a partial.
    for (int z = -4; z <= 4; ++z) {
        f.tw.clear(5, 64, z);
        f.tw.set(5, 63, z, HOT);
    }
    f.tw.set(5, 63, 4, FULL);
    f.invalidate();
    pts = run(f.nav, cn_search_ground(f.nav, &q), info);
    CHECK(info.status == CN_SEARCH_FOUND);
    bool detour = false;
    for (int i = 0; i < info.points; ++i) {
        if (near(pts[static_cast<size_t>(i) * 3 + 2], 4.5f)) detour = true;
    }
    CHECK(detour);

    // Unloaded ground is never routed over.
    Fixture g;
    g.tw.floor(63, -10, 10, -1, 1, FULL);
    g.tw.unload_negative_x = true;
    cn_ground_query back = ground_query(5.5f, 64, 0.5f, -5.5f, 64, 0.5f);
    run(g.nav, cn_search_ground(g.nav, &back), info);
    CHECK(info.status != CN_SEARCH_FOUND);
}

void water_costs_and_swimmers() {
    Fixture f;
    f.tw.floor(60, -2, 12, -2, 2, FULL);
    // A pond two deep between x 3..7: a walker cannot cross without swimming, a swimmer floats.
    for (int x = -2; x <= 12; ++x)
        for (int z = -2; z <= 2; ++z) {
            if (x >= 3 && x <= 7) {
                f.tw.set(x, 61, z, 0, 1);
                f.tw.set(x, 62, z, 0, 1);
            } else {
                f.tw.set(x, 61, z, FULL);
                f.tw.set(x, 62, z, FULL);
            }
        }
    cn_search_info info{};
    cn_ground_query q = ground_query(0.5f, 63, 0.5f, 10.5f, 63, 0.5f);
    q.profile.can_swim = 1;
    q.profile.wade_cost = 1.0f;
    q.profile.swim_cost = 1.2f;
    q.profile.fall_cost_per_block = 0.2f;
    auto pts = run(f.nav, cn_search_ground(f.nav, &q), info);
    CHECK(info.status == CN_SEARCH_FOUND);
    bool floated = false;
    for (int i = 0; i < info.points; ++i) {
        if (near(pts[static_cast<size_t>(i) * 3 + 1], 62.875f)) floated = true;
    }
    CHECK(floated);
    cn_ground_query w = ground_query(0.5f, 63, 0.5f, 10.5f, 63, 0.5f);
    run(f.nav, cn_search_ground(f.nav, &w), info);
    CHECK(info.status != CN_SEARCH_FOUND); // deep water: out of its depth
}

cn_air_profile flyer(float cruise) {
    return cn_air_profile{4, cruise, 0.35f, 0.5f, 0.15f, 1, 0.75f, 1, 254, 0};
}

void air_routes() {
    Fixture f;
    // A tall wall across the corridor with a hole at one side.
    for (int y = 60; y < 120; ++y)
        for (int z = -16; z < 16; ++z)
            if (!(z >= 8 && y < 80)) f.tw.set(20, y, z, FULL);
    cn_air_query q{};
    q.from[0] = 2; q.from[1] = 70; q.from[2] = 2;
    q.goal[0] = 40; q.goal[1] = 70; q.goal[2] = 2;
    q.goal_radius = 6.0f;
    q.goal_snap_cells = 2;
    q.profile = flyer(70.0f);
    q.limits = limits(3000);
    q.limits.heuristic_weight = 1.25f;
    cn_search_info info{};
    auto pts = run(f.nav, cn_search_air(f.nav, &q), info);
    CHECK(info.status == CN_SEARCH_FOUND);
    // Every waypoint is a cube centre and the route goes round the wall through the gap.
    bool through_gap = false;
    for (int i = 0; i < info.points; ++i) {
        float x = pts[static_cast<size_t>(i) * 3], z = pts[static_cast<size_t>(i) * 3 + 2];
        CHECK(near(std::fmod(x, 4.0f), 2.0f));
        if (z > 8.0f) through_gap = true;
    }
    CHECK(through_gap);

    // Out of range resolves at once.
    cn_air_query bad = q;
    bad.profile.cell_size = 1;
    bad.from[1] = -10;
    int64_t id = cn_search_air(f.nav, &bad);
    CHECK(id > 0);
    CHECK(cn_search_poll(f.nav, id, &info) == 1 && info.status == CN_SEARCH_OUT_OF_RANGE && info.points == 0);
    cn_search_release(f.nav, id);
}

void the_service_queues_refuses_and_releases() {
    Fixture f;
    f.tw.floor(63, -2, 20, -2, 2, FULL);
    cn_ground_query q = ground_query(0.5f, 64, 0.5f, 10.5f, 64, 0.5f);
    int64_t ids[4];
    for (auto& id : ids) id = cn_search_ground(f.nav, &q);
    CHECK(cn_search_ground(f.nav, &q) == 0); // max_in_flight 4
    cn_stats st{};
    cn_stats_get(f.nav, &st);
    CHECK(st.submitted == 4 && st.rejected == 1 && st.in_flight == 4);
    cn_search_release(f.nav, ids[1]); // cancel a queued one
    // A small budget runs one search per step (a search always runs to its own limit).
    CHECK(cn_world_step(f.nav, 1) == 1);
    cn_search_info info{};
    CHECK(cn_search_poll(f.nav, ids[0], &info) == 1);
    CHECK(cn_search_poll(f.nav, ids[2], &info) == 0);
    CHECK(cn_world_step(f.nav, 1 << 30) == 2);
    cn_stats_get(f.nav, &st);
    CHECK(st.completed == 3 && st.in_flight == 0);
    CHECK(cn_search_poll(f.nav, ids[1], &info) == CN_ERR_ARG);
    for (int64_t id : ids) cn_search_release(f.nav, id);
}

cn_ground_agent_desc agent_desc() {
    cn_ground_agent_desc d{};
    d.profile = walker();
    d.limits = cn_limits{600, 3.4e38f, 1.1f, 0};
    d.snap_down = 4;
    d.snap_up = 2;
    d.repath_interval = 1.0f;
    d.no_path_retry = 3.0f;
    d.saturated_retry = 0.5f;
    d.goal_drift = 2.0f;
    d.waypoint_radius = 0.7f;
    d.waypoint_vertical_slack = 1.6f;
    d.stuck_speed = 0.15f;
    d.stuck_seconds = 1.5f;
    d.jump_rise_margin = 0.05f;
    d.jump_approach = 1.5f;
    return d;
}

cn_steering_desc steering_desc(float hop_boost = 2.0f) {
    cn_steering_desc d{};
    d.rotation_speed = 180.0f;
    d.hop_boost_speed = hop_boost;
    d.hop_duration = 0.5f;
    d.jump_cooldown = 1.0f;
    d.swim_stroke_cooldown = 0.35f;
    d.obstacle_look_ahead = 0.15f;
    d.foot_probe_lift = 0.1f;
    d.max_shore_probe_cells = 3;
    d.model_yaw_offset = 180.0f;
    d.collide_mask = SOLID;
    return d;
}

cn_body body_at(float x, float y, float z) {
    cn_body b{};
    b.x = x; b.y = y; b.z = z;
    b.yaw = 0.0f;
    b.leg_height = 0.5f;
    b.half_width = 0.4f;
    b.half_length = 0.6f;
    b.move_speed = 2.0f;
    b.speed_multiplier = 1.0f;
    b.water_speed_factor = 0.5f;
    b.swim_stroke_reach = 1.5f;
    b.flags = CN_BODY_ON_GROUND;
    return b;
}

void ground_agent_walks_a_route() {
    Fixture f;
    f.tw.floor(63, -2, 20, -2, 2, FULL);
    cn_ground_agent_desc d = agent_desc();
    cn_steering_desc sd = steering_desc();
    cn_steering* st = cn_steering_create(&sd);
    cn_body b = body_at(0.5f, 64.5f, 0.5f);
    cn_ground_agent* a = cn_ground_agent_create(&d, b.x, b.y, b.z);
    CHECK(a != nullptr);
    cn_agent_view v{};
    cn_apply out{};
    CHECK(cn_ground_agent_move_to(a, 8.5f, 64.0f, 0.5f, 1.0f, 1.0f, &v) == CN_OK);
    CHECK(v.status == CN_AGENT_SEARCHING && v.has_goal == 1);

    const float dt = 0.05f;
    // Tick 1 queues a search and holds position.
    cn_ground_agent_tick(a, st, f.nav, f.physics, &b, dt, &out, &v);
    CHECK(v.status == CN_AGENT_SEARCHING);
    CHECK((out.apply & CN_APPLY_VELOCITY) != 0u && out.vx == 0.0f && out.vz == 0.0f);
    cn_world_step(f.nav, 1 << 30);
    // Tick 2 adopts the route and walks it: +X, facing it through the model offset.
    cn_ground_agent_tick(a, st, f.nav, f.physics, &b, dt, &out, &v);
    CHECK(v.status == CN_AGENT_FOLLOWING && v.path_points >= 2);
    CHECK(near(out.vx, 2.0f) && near(out.vz, 0.0f));
    CHECK((out.apply & CN_APPLY_YAW) != 0u && near(out.yaw, -9.0f)); // 180 deg/s * 0.05, the short way to 270
    float route[30];
    int32_t complete = 0;
    CHECK(cn_ground_agent_path(a, route, 10, &complete) == v.path_points && complete == 1);

    int arrived_at = -1;
    for (int tick = 0; tick < 200; ++tick) {
        b.yaw = out.yaw;
        if (out.apply & CN_APPLY_VELOCITY) {
            b.x += out.vx * dt;
            b.z += out.vz * dt;
        }
        cn_world_step(f.nav, 1 << 30);
        cn_ground_agent_tick(a, st, f.nav, f.physics, &b, dt, &out, &v);
        if (v.status == CN_AGENT_ARRIVED) {
            arrived_at = tick;
            break;
        }
    }
    CHECK(arrived_at > 0);
    // The goal radius (1) ends the route one cell short of the goal cell; arrival is within the
    // waypoint radius of that end.
    CHECK(std::fabs(b.x - 7.5f) < 0.7f);

    // Stop: idle, no goal, a stop to apply.
    cn_ground_agent_stop(a, f.nav, &out, &v);
    CHECK(v.status == CN_AGENT_IDLE && v.has_goal == 0 && (out.apply & CN_APPLY_VELOCITY) != 0u);
    cn_ground_agent_destroy(a);
    cn_steering_destroy(st);
}

void ground_agent_jumps_and_gets_unstuck() {
    Fixture f;
    f.tw.floor(63, -2, 20, -2, 2, FULL);
    for (int z = -2; z <= 2; ++z) f.tw.set(3, 64, z, FULL); // a one-block step across the way
    cn_ground_agent_desc d = agent_desc();
    cn_steering_desc sd = steering_desc(3.0f);
    cn_steering* st = cn_steering_create(&sd);
    cn_body b = body_at(1.5f, 64.5f, 0.5f);
    cn_ground_agent* a = cn_ground_agent_create(&d, b.x, b.y, b.z);
    cn_agent_view v{};
    cn_apply out{};
    cn_ground_agent_move_to(a, 8.5f, 65.0f, 0.5f, 1.0f, 1.0f, &v);
    const float dt = 0.05f;
    cn_ground_agent_tick(a, st, f.nav, f.physics, &b, dt, &out, &v);
    cn_world_step(f.nav, 1 << 30);
    cn_ground_agent_tick(a, st, f.nav, f.physics, &b, dt, &out, &v); // adopts the route at x 1.5
    CHECK(v.status == CN_AGENT_FOLLOWING);
    CHECK((out.apply & CN_APPLY_JUMP) == 0u);
    b.x = 2.45f; // right at the step's face
    cn_ground_agent_tick(a, st, f.nav, f.physics, &b, dt, &out, &v);
    CHECK(v.status == CN_AGENT_FOLLOWING);
    CHECK((out.apply & CN_APPLY_JUMP) != 0u); // the obstacle probe (and the planned rise) jump
    CHECK(near(out.vx, 3.0f)); // the hop boost applies in the tick of the jump

    // Pinned in place while following: wedged after stuck_seconds, path dropped, replanning.
    int stuck_at = -1;
    for (int tick = 0; tick < 60; ++tick) {
        b.flags = CN_BODY_ON_GROUND;
        cn_world_step(f.nav, 1 << 30);
        cn_ground_agent_tick(a, st, f.nav, f.physics, &b, dt, &out, &v);
        if (v.status == CN_AGENT_STUCK) {
            stuck_at = tick;
            break;
        }
    }
    CHECK(stuck_at > 20 && stuck_at < 40);
    CHECK(v.path_points == 0);

    // From water the probe looks for a shore a stroke can top.
    b.flags = CN_BODY_IN_WATER;
    cn_ground_agent_replan(a, f.nav, &v);
    for (int tick = 0; tick < 40; ++tick) {
        cn_world_step(f.nav, 1 << 30);
        cn_ground_agent_tick(a, st, f.nav, f.physics, &b, dt, &out, &v);
        if (out.apply & CN_APPLY_SWIM_UP) break;
    }
    CHECK((out.apply & CN_APPLY_SWIM_UP) != 0u);
    cn_ground_agent_destroy(a);
    cn_steering_destroy(st);
}

void a_world_swap_drops_the_pending_search() {
    Fixture f;
    f.tw.floor(63, -2, 20, -2, 2, FULL);
    Fixture g;
    cn_ground_agent_desc d = agent_desc();
    cn_steering_desc sd = steering_desc();
    cn_steering* st = cn_steering_create(&sd);
    cn_body b = body_at(0.5f, 64.5f, 0.5f);
    cn_ground_agent* a = cn_ground_agent_create(&d, b.x, b.y, b.z);
    cn_agent_view v{};
    cn_apply out{};
    cn_ground_agent_move_to(a, 8.5f, 64.0f, 0.5f, 1.0f, 1.0f, &v);
    cn_ground_agent_tick(a, st, f.nav, f.physics, &b, 0.05f, &out, &v);
    cn_world_step(f.nav, 1 << 30);
    // The next tick is in another world: the result from the first is not adopted.
    cn_ground_agent_tick(a, st, g.nav, g.physics, &b, 0.05f, &out, &v);
    CHECK(v.status != CN_AGENT_FOLLOWING);
    // No service at all: no route, a long back-off.
    cn_ground_agent_tick(a, st, nullptr, nullptr, &b, 1.1f, &out, &v);
    CHECK(v.status == CN_AGENT_NO_PATH);
    cn_ground_agent_destroy(a);
    cn_steering_destroy(st);
}

void air_agent_flies_to_the_horizon() {
    Fixture f;
    cn_air_agent_desc d{};
    d.profile = flyer(0.0f);
    d.limits = cn_limits{3000, 3.4e38f, 1.25f, 0};
    d.plan_range = 112.0f;
    d.replan_travel = 32.0f;
    d.replan_interval = 1.5f;
    d.no_path_retry = 3.0f;
    d.saturated_retry = 0.5f;
    d.waypoint_radius = 4.0f;
    d.goal_radius = 6.0f;
    d.goal_snap_cells = 2;
    cn_air_agent* a = cn_air_agent_create(&d, 0.0f, 90.0f, 0.0f);
    CHECK(a != nullptr);
    cn_agent_view v{};
    cn_air_agent_move_to(a, 500.0f, 90.0f, 0.0f, &v);
    float target[3];
    // No route yet: the horizon along the bearing at cruise altitude.
    cn_air_agent_steer_target(a, 0.0f, 90.0f, 0.0f, target);
    CHECK(near(target[0], 112.0f) && near(target[1], 90.0f) && near(target[2], 0.0f));
    cn_air_agent_tick(a, f.nav, 0.0f, 90.0f, 0.0f, 0.05f, &v);
    CHECK(v.status == CN_AGENT_SEARCHING);
    cn_world_step(f.nav, 1 << 30);
    cn_air_agent_tick(a, f.nav, 0.0f, 90.0f, 0.0f, 0.05f, &v);
    CHECK(v.status == CN_AGENT_FOLLOWING && v.path_points >= 1);
    cn_air_agent_steer_target(a, 0.0f, 90.0f, 0.0f, target);
    CHECK(target[0] > 4.0f && near(std::fmod(target[0], 4.0f), 2.0f));
    cn_air_agent_stop(a, f.nav, &v);
    CHECK(v.status == CN_AGENT_IDLE && v.has_goal == 0 && v.path_points == 0);
    cn_air_agent_destroy(a);
}

void probes_and_steering() {
    Fixture f;
    f.tw.floor(63, -2, 2, -2, 2, FULL);
    f.tw.set(0, 64, 0, SNOW1);     // a layer does not block
    f.tw.set(1, 64, 0, FULL);
    f.tw.set(1, 70, 0, FULL);
    f.tw.set(2, 64, 0, 0, 1);
    CHECK(cn_probe_ground_level(f.physics, 0.5f, 0.5f, 66.0f, 5, 10, SOLID) == 64.0f);
    CHECK(cn_probe_ground_level(f.physics, 1.5f, 0.5f, 66.0f, 5, 10, SOLID) == 71.0f);
    CHECK(std::isinf(cn_probe_ground_level(f.physics, 9.5f, 9.5f, 66.0f, 5, 10, SOLID)));
    CHECK(cn_probe_fluid_top(f.physics, 2.5f, 64.5f, 0.5f, 1, 3, WATER) == 65.0f);
    CHECK(std::isinf(cn_probe_fluid_top(f.physics, 0.5f, 64.5f, 0.5f, 1, 3, WATER)));
    CHECK(cn_probe_clear(f.physics, 0.5f, 65.5f, 0.5f, 0.4f, SOLID) == 1);
    CHECK(cn_probe_clear(f.physics, 1.5f, 65.5f, 0.5f, 0.6f, SOLID) == 0);
    CHECK(cn_probe_column_peak(f.physics, 1.5f, 0.5f, 66.0f, 32, SOLID) == 71.0f);

    CHECK(near(cn_steer_yaw_for(1.0f, 0.0f, 0.0f), 90.0f));
    CHECK(near(cn_steer_yaw_for(0.0f, -1.0f, 180.0f), 360.0f));
    CHECK(near(cn_steer_turn(170.0f, -170.0f, 5.0f), 175.0f)); // shortest arc across 180
    CHECK(near(cn_steer_turn(10.0f, 12.0f, 5.0f), 12.0f));
    CHECK(near(cn_steer_yaw_error(350.0f, 10.0f), 20.0f));
}

void steering_on_its_own() {
    Fixture f;
    f.tw.floor(63, -2, 4, -2, 2, FULL);
    f.tw.set(2, 64, 0, FULL);
    cn_steering_desc sd = steering_desc(3.0f);
    cn_steering* st = cn_steering_create(&sd);
    cn_body b = body_at(1.5f, 64.5f, 0.5f); // the probe reaches 0.55 past the centre: cell x 2
    cn_apply out{};
    // Straight at the block: turn (the short way), hop, and the boost applies at once.
    CHECK(cn_steering_steer(st, f.physics, &b, 1.0f, 0.0f, 1.0f, 0.1f, 1, &out) == CN_OK);
    CHECK((out.apply & CN_APPLY_JUMP) != 0u && near(out.vx, 3.0f) && near(out.yaw, -18.0f));
    // The cooldown holds a second hop, and the request API says so.
    CHECK(cn_steering_request_jump(st, &b, &out) == 0);
    CHECK(cn_steering_tick(st, 1, 1.5f) == CN_OK);
    CHECK(cn_steering_request_jump(st, &b, &out) == 1 && (out.apply & CN_APPLY_JUMP) != 0u);
    // Without allow_jump: no hop, base speed * route * own multiplier.
    cn_steering_tick(st, 1, 1.5f);
    b.speed_multiplier = 0.5f;
    CHECK(cn_steering_steer(st, f.physics, &b, 0.0f, 1.0f, 2.0f, 0.1f, 0, &out) == CN_OK);
    CHECK((out.apply & CN_APPLY_JUMP) == 0u && near(out.vz, 2.0f) && near(out.vx, 0.0f));
    // In water: slowed, and the request is a stroke.
    b.flags = CN_BODY_IN_WATER;
    b.speed_multiplier = 1.0f;
    CHECK(cn_steering_steer(st, f.physics, &b, 0.0f, 1.0f, 1.0f, 0.1f, 0, &out) == CN_OK);
    CHECK(near(out.vz, 1.0f)); // 2 * 0.5 water factor
    CHECK(cn_steering_request_jump(st, &b, &out) == 1 && (out.apply & CN_APPLY_SWIM_UP) != 0u);
    cn_steering_destroy(st);
}

void flood_reaches_face_neighbours_breadth_first() {
    TestWorld tw;
    cp_world_desc d{};
    d.fill = fill;
    d.user = &tw;
    cp_world* physics = cp_world_create(&d);
    constexpr uint32_t LEAF = 8u, LOG = 16u;
    constexpr uint16_t LEAF_SHAPE = 1, LOG_SHAPE = 2, STONE = 3;
    cp_box full{0, 0, 0, 1, 1, 1};
    cp_shape_define(physics, LEAF_SHAPE, &full, 1, SOLID | LEAF, 0);
    cp_shape_define(physics, LOG_SHAPE, &full, 1, SOLID | LOG, 0);
    cp_shape_define(physics, STONE, &full, 1, SOLID, 0);
    // A log at the origin, a line of leaves along +x, one leaf touching the line only diagonally,
    // stone the flood must not enter, and a leaf below the floor.
    tw.set(0, 5, 0, LOG_SHAPE);
    for (int x = 1; x <= 4; ++x) tw.set(x, 5, 0, LEAF_SHAPE);
    tw.set(2, 6, 1, LEAF_SHAPE);   // diagonal to (2,5,0): not connected
    tw.set(0, 6, 0, STONE);
    tw.set(0, 4, 0, LEAF_SHAPE);   // below min_y 5: never entered
    cn_flood_query q{0, 5, 0, 3, 100, LEAF | LOG, 5, 256};
    int32_t out[64 * 4];
    int32_t n = cn_flood(physics, &q, out, 64);
    CHECK(n == 4); // the log and the leaves at distance 1..3
    CHECK(out[0] == 0 && out[3] == static_cast<int32_t>(SOLID | LOG));
    for (int i = 1; i < 4; ++i) {
        CHECK(out[i * 4] == i && out[i * 4 + 1] == 5 && out[i * 4 + 3] == static_cast<int32_t>(SOLID | LEAF));
    }
    // The start expands whatever it holds (an emptied trunk cell), and the cap bounds the work.
    cn_flood_query empty_start{1, 6, 0, 6, 2, LEAF | LOG, 0, 256};
    CHECK(cn_flood(physics, &empty_start, out, 64) == 2);
    CHECK(out[3] == 0 && out[4] == 1 && out[5] == 5);
    // Breadth-first: from the middle of the line, both neighbours come before anything farther.
    cn_flood_query mid{2, 5, 0, 6, 100, LEAF | LOG, 0, 256};
    n = cn_flood(physics, &mid, out, 64);
    CHECK(n == 6);
    CHECK(out[4] == 3 && out[8] == 1); // +x first, then -x
    CHECK(cn_flood(physics, nullptr, out, 64) == CN_ERR_ARG);
    cp_world_destroy(physics);
}

void bad_input() {
    CHECK(cn_world_create(nullptr) == nullptr);
    cn_world_desc d{};
    CHECK(cn_world_create(&d) == nullptr);
    CHECK(cn_cell(nullptr, 0, 0, 0, nullptr) == CN_CELL_UNKNOWN);
    CHECK(cn_search_ground(nullptr, nullptr) == 0);
    cn_ground_agent_desc ad{};
    CHECK(cn_ground_agent_create(&ad, 0, 0, 0) == nullptr);
    CHECK(cn_world_step(nullptr, 10) == CN_ERR_ARG);
}

} // namespace

int main() {
    CHECK(cn_abi_version() == CN_ABI_VERSION);
    cells_come_from_the_physics_cache();
    ground_routes();
    water_costs_and_swimmers();
    air_routes();
    the_service_queues_refuses_and_releases();
    ground_agent_walks_a_route();
    ground_agent_jumps_and_gets_unstuck();
    a_world_swap_drops_the_pending_search();
    air_agent_flies_to_the_horizon();
    probes_and_steering();
    steering_on_its_own();
    flood_reaches_face_neighbours_breadth_first();
    bad_input();
    if (failures != 0) {
        std::fprintf(stderr, "nav_world: %d check(s) failed\n", failures);
        return 1;
    }
    std::printf("nav_world: ok\n");
    return 0;
}
