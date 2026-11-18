// Cenda physics (cp_*) conformance: the brick cache and its invalidation, swept movement with
// step-up and grounding, raycasts, sweeps, fluids. Plain asserts, as the other kernel tests.
//
// ABI 2 (OpenMason #142): every scenario runs three times — at the origin, and in worlds shifted to
// +-2^40 blocks and beyond (negative Y too) with the frame origin moved there, so local positions are
// identical and every check holds unchanged. Then: keys that aliased under the old 21-bit packing,
// origin moves keeping the cache, and precision of far positions WITHOUT a rebased origin.

#include "cenda/physics.h"

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <map>
#include <tuple>

// The Java binding (engine-core physics.CendaPhysics / PhysicsWorld) hard-codes these layouts.
static_assert(sizeof(cp_body) == 80, "cp_body layout");
static_assert(sizeof(cp_hit) == 80, "cp_hit layout");
static_assert(sizeof(cp_fluid_sample) == 32, "cp_fluid_sample layout");
static_assert(sizeof(cp_aabb) == 48, "cp_aabb layout");
static_assert(sizeof(cp_world_desc) == 32, "cp_world_desc layout");

namespace {

int failures = 0;

#define CHECK(cond)                                                              \
    do {                                                                         \
        if (!(cond)) {                                                           \
            std::fprintf(stderr, "%s:%d: CHECK failed: %s\n", __FILE__, __LINE__, #cond); \
            ++failures;                                                          \
        }                                                                        \
    } while (0)

// The current scenario's world offset: the test world's cells live at offset + (x, y, z) and the
// physics frame origin is the offset, so scenario code reads as if at 0.
int64_t g_off[3] = {0, 0, 0};
int64_t X(int64_t x) { return x + g_off[0]; }
int64_t Y(int64_t y) { return y + g_off[1]; }
int64_t Z(int64_t z) { return z + g_off[2]; }

// A test world: a sparse map of (shape, fluid) per ABSOLUTE cell.
struct TestWorld {
    std::map<std::tuple<int64_t, int64_t, int64_t>, std::pair<uint16_t, uint16_t>> cells;
    bool unload_negative_x = false; // local x < 0 reports "not loaded"
    int fills = 0;

    // Scenario (local) coordinates.
    void set(int64_t x, int64_t y, int64_t z, uint16_t shape, uint16_t fluid = 0) {
        cells[{X(x), Y(y), Z(z)}] = {shape, fluid};
    }
    void clear(int64_t x, int64_t y, int64_t z) { cells.erase({X(x), Y(y), Z(z)}); }
    // Absolute coordinates.
    void set_abs(int64_t x, int64_t y, int64_t z, uint16_t shape, uint16_t fluid = 0) {
        cells[{x, y, z}] = {shape, fluid};
    }
};

int32_t fill(void* user, int64_t x0, int64_t y0, int64_t z0, int32_t sx, int32_t sy, int32_t sz,
             uint16_t* shapes, uint16_t* fluids) {
    auto* w = static_cast<TestWorld*>(user);
    w->fills++;
    if (w->unload_negative_x && x0 - g_off[0] < 0) return 0;
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

constexpr uint32_t SOLID = 1u, PICK = 2u, DOOR = 4u;
constexpr uint32_t WATER = 1u;
constexpr uint16_t FULL = 1, HALF = 2, SNOW7 = 3, FLOWER = 4, STAIR_LOW = 5, POLE = 6;

cp_world* make(TestWorld& tw, int cache = 0) {
    cp_world_desc desc{};
    desc.fill = fill;
    desc.user = &tw;
    desc.cache_bricks = cache;
    cp_world* w = cp_world_create(&desc);
    CHECK(w != nullptr);
    cp_box full{0, 0, 0, 1, 1, 1};
    cp_box half{0, 0, 0, 1, 0.5f, 1};
    cp_box snow{0, 0, 0, 1, 0.875f, 1};
    cp_box pole{0.4f, 0, 0.4f, 0.6f, 1, 0.6f};
    // A stair: a low tread over the whole cell and a high one over its back half (z >= 0.5).
    cp_box stair[2] = {{0, 0, 0, 1, 0.5f, 1}, {0, 0, 0.5f, 1, 1, 1}};
    CHECK(cp_shape_define(w, FULL, &full, 1, SOLID | PICK, 0) == CP_OK);
    CHECK(cp_shape_define(w, HALF, &half, 1, SOLID | PICK, CP_SHAPE_PARTIAL) == CP_OK);
    CHECK(cp_shape_define(w, SNOW7, &snow, 1, SOLID | PICK, CP_SHAPE_PARTIAL) == CP_OK);
    CHECK(cp_shape_define(w, FLOWER, &full, 1, PICK, 0) == CP_OK);
    CHECK(cp_shape_define(w, STAIR_LOW, stair, 2, SOLID | PICK, CP_SHAPE_PARTIAL) == CP_OK);
    CHECK(cp_shape_define(w, POLE, &pole, 1, SOLID | PICK, 0) == CP_OK);
    cp_fluid source{0.875f, 0, 0, 0, WATER, 0};
    cp_fluid flowing{0.5f, 2.5f, 0, 0, WATER, 0};
    CHECK(cp_fluid_define(w, 1, &source) == CP_OK);
    CHECK(cp_fluid_define(w, 2, &flowing) == CP_OK);
    CHECK(cp_set_origin(w, g_off[0], g_off[1], g_off[2]) == CP_OK);
    return w;
}

void floor_at(TestWorld& tw, int y, int r = 8) {
    for (int x = -r; x <= r; ++x)
        for (int z = -r; z <= r; ++z) tw.set(x, y, z, FULL);
}

cp_body player(double x, double y, double z) {
    cp_body b{};
    b.x = x; b.y = y; b.z = z;
    b.half_x = 0.3f; b.half_z = 0.3f;
    b.below = 0.0f; b.above = 1.8f;
    b.step_height = 0.5f;
    b.partial_step_height = 1.0f;
    b.mask = SOLID;
    b.flags = CP_BODY_ON_GROUND;
    return b;
}

bool near(double a, double b, double eps = 1e-4) { return std::fabs(a - b) <= eps; }

void falls_and_lands_without_tunnelling() {
    TestWorld tw;
    floor_at(tw, 0);
    cp_world* w = make(tw);
    cp_body b = player(0.5f, 20.0f, 0.5f);
    b.flags = 0;
    b.vy = -400.0f; // 20 blocks in one 50 ms step: far more than the floor is thick
    CHECK(cp_move(w, &b, 0.05f) == CP_OK);
    CHECK(near(b.y, 1.0f));
    CHECK(b.vy == 0.0f);
    CHECK((b.flags & CP_BODY_ON_GROUND) != 0u);
    CHECK((b.contacts & CP_CONTACT_GROUND) != 0u);
    cp_world_destroy(w);
}

void walls_stop_and_slide() {
    TestWorld tw;
    floor_at(tw, 0);
    for (int y = 1; y <= 3; ++y)
        for (int z = -8; z <= 8; ++z) tw.set(3, y, z, FULL);
    cp_world* w = make(tw);
    cp_body b = player(2.0f, 1.0f, 0.5f);
    b.vx = 20.0f;
    b.vz = 4.0f;
    cp_move(w, &b, 0.1f);
    CHECK(near(b.x, 3.0f - 0.3f));
    CHECK(near(b.z, 0.9f)); // slid along the wall
    CHECK((b.contacts & CP_CONTACT_POS_X) != 0u);
    CHECK(b.vx == 0.0f && b.vz == 4.0f);
    // Mobs keep their velocity against walls.
    cp_body m = player(2.0f, 1.0f, 0.5f);
    m.flags |= CP_BODY_KEEP_VELOCITY;
    m.vx = 20.0f;
    cp_move(w, &m, 0.1f);
    CHECK(near(m.x, 2.7f) && m.vx == 20.0f);
    cp_world_destroy(w);
}

void steps_up_low_obstacles_only() {
    TestWorld tw;
    floor_at(tw, 0);
    tw.set(3, 1, 0, HALF);   // 0.5 rise: a step
    tw.set(3, 1, 4, FULL);   // 1.0 rise: a wall for a standing body
    tw.set(3, 1, -4, SNOW7); // 0.875 rise, partial: climbed with partial_step_height
    cp_world* w = make(tw);

    cp_body b = player(2.5f, 1.0f, 0.5f);
    b.vx = 10.0f;
    cp_move(w, &b, 0.1f);
    CHECK(near(b.y, 1.5f) && b.x > 2.7f);
    CHECK((b.contacts & CP_CONTACT_STEPPED) != 0u);

    cp_body c = player(2.5f, 1.0f, 4.5f);
    c.vx = 10.0f;
    cp_move(w, &c, 0.1f);
    CHECK(near(c.y, 1.0f) && near(c.x, 2.7f));

    cp_body s = player(2.5f, 1.0f, -3.5f);
    s.vx = 10.0f;
    cp_move(w, &s, 0.1f);
    CHECK(near(s.y, 1.875f) && s.x > 2.7f);

    // Not standing: no step.
    cp_body air = player(2.5f, 1.0f, 0.5f);
    air.flags = 0;
    air.vx = 10.0f;
    cp_move(w, &air, 0.1f);
    CHECK(near(air.x, 2.7f) && near(air.y, 1.0f));

    // A mob with no partial allowance stays below deep snow.
    cp_body mob = player(2.5f, 1.0f, -3.5f);
    mob.partial_step_height = 0.5f;
    mob.vx = 10.0f;
    cp_move(w, &mob, 0.1f);
    CHECK(near(mob.x, 2.7f));

    // A jump into a wall is not turned into a step.
    cp_body jump = player(2.5f, 1.0f, 0.5f);
    jump.vx = 10.0f;
    jump.vy = 8.0f;
    cp_move(w, &jump, 0.05f);
    CHECK(jump.vy == 8.0f && (jump.contacts & CP_CONTACT_STEPPED) == 0u);
    cp_world_destroy(w);
}

void airborne_bodies_mantle_only_with_air_step() {
    TestWorld tw;
    floor_at(tw, 0);
    for (int x = 3; x <= 8; ++x)
        for (int z = -8; z <= 8; ++z) tw.set(x, 1, z, FULL); // a one-block ledge, top at y = 2
    cp_world* w = make(tw);

    // Feet 0.4 under the ledge top, falling, pushing into it: mantles onto it.
    cp_body m = player(2.5f, 1.6f, 0.5f);
    m.flags = CP_BODY_AIR_STEP;
    m.vx = 10.0f;
    m.vy = -1.0f;
    cp_move(w, &m, 0.05f);
    CHECK(near(m.y, 2.0f) && m.x > 2.7f);
    CHECK((m.flags & CP_BODY_ON_GROUND) != 0u && m.vy == 0.0f);
    CHECK((m.contacts & CP_CONTACT_STEPPED) != 0u);

    // Same, without AIR_STEP: a wall.
    cp_body plain = player(2.5f, 1.6f, 0.5f);
    plain.flags = 0;
    plain.vx = 10.0f;
    plain.vy = -1.0f;
    cp_move(w, &plain, 0.05f);
    CHECK(near(plain.x, 2.7f));

    // Rising with the feet 0.4 under the top: no climb yet (it keeps its jump).
    cp_body rising = player(2.5f, 1.6f, 0.5f);
    rising.flags = CP_BODY_AIR_STEP;
    rising.vx = 10.0f;
    rising.vy = 2.0f;
    cp_move(w, &rising, 0.05f);
    CHECK(near(rising.x, 2.7f) && near(rising.y, 1.7f) && rising.vy == 2.0f);

    // Feet 0.7 under the top: too far below to mantle a full block.
    cp_body low = player(2.5f, 1.3f, 0.5f);
    low.flags = CP_BODY_AIR_STEP;
    low.vx = 10.0f;
    cp_move(w, &low, 0.05f);
    CHECK(near(low.x, 2.7f) && low.y < 2.0f);

    // A real jump (8.5 up, gravity 35, 60 fps) peaks under one block, so only the mantle gets it
    // onto the ledge; it must also not cut the first rising frames short.
    for (int with_mantle = 0; with_mantle <= 1; ++with_mantle) {
        cp_body j = player(2.6f, 1.0f, 0.5f);
        j.flags = CP_BODY_ON_GROUND | (with_mantle ? CP_BODY_AIR_STEP : 0u);
        j.vy = 8.5f;
        j.flags &= ~CP_BODY_ON_GROUND;
        double peak = 0.0;
        double max_rise_per_frame = 0.0;
        for (int i = 0; i < 60; ++i) {
            j.vx = 4.0f;
            if ((j.flags & CP_BODY_ON_GROUND) == 0u) j.vy -= 35.0f / 60.0f;
            double before = j.y;
            float vy = j.vy;
            cp_move(w, &j, 1.0f / 60.0f);
            peak = j.y > peak ? j.y : peak;
            // Never more than the frame's own vertical travel plus the few cm a mantle at the apex adds.
            double extra = (j.y - before) - (vy > 0.0f ? vy / 60.0f : 0.0f);
            max_rise_per_frame = extra > max_rise_per_frame ? extra : max_rise_per_frame;
        }
        CHECK(max_rise_per_frame < 0.1f);
        if (with_mantle) {
            CHECK(near(j.y, 2.0f) && j.x > 3.0f);
        } else {
            CHECK(peak < 2.0f && near(j.x, 2.7f) && near(j.y, 1.0f));
        }
    }
    cp_world_destroy(w);
}

void climbs_a_stair_one_tread_at_a_time() {
    TestWorld tw;
    floor_at(tw, 0);
    tw.set(0, 1, 2, STAIR_LOW); // tread 0.5 for z in [2, 2.5), 1.0 for z in [2.5, 3)
    cp_world* w = make(tw);
    cp_body b = player(0.5f, 1.0f, 1.0f);
    b.vz = 10.0f;
    cp_move(w, &b, 0.1f);
    CHECK(near(b.y, 1.5f));      // the low tread
    cp_move(w, &b, 0.1f);
    CHECK(near(b.y, 2.0f));      // then the high one (rise 0.5 again)
    cp_world_destroy(w);
}

void walking_off_an_edge_drops_the_ground_flag() {
    TestWorld tw;
    tw.set(0, 0, 0, FULL);
    cp_world* w = make(tw);
    cp_body b = player(0.5f, 1.0f, 0.5f);
    cp_move(w, &b, 0.05f);
    CHECK((b.flags & CP_BODY_ON_GROUND) != 0u);
    b.vx = 20.0f;
    cp_move(w, &b, 0.1f);
    CHECK((b.flags & CP_BODY_ON_GROUND) == 0u);
    CHECK((b.contacts & CP_CONTACT_LEFT_GROUND) != 0u);
    cp_world_destroy(w);
}

void a_body_inside_low_snow_is_lifted() {
    TestWorld tw;
    floor_at(tw, 0);
    tw.set(0, 1, 0, HALF);
    cp_world* w = make(tw);
    cp_body b = player(0.5f, 1.2f, 0.5f); // feet 0.3 deep in a half-height layer
    cp_move(w, &b, 0.05f);
    CHECK(near(b.y, 1.5f));
    CHECK((b.contacts & CP_CONTACT_LIFTED) != 0u);
    cp_world_destroy(w);
}

void the_cache_fills_once_and_honours_invalidation() {
    TestWorld tw;
    floor_at(tw, 0);
    cp_world* w = make(tw);
    cp_body b = player(0.5f, 1.0f, 0.5f);
    cp_move(w, &b, 0.05f);
    int after_first = tw.fills;
    CHECK(after_first > 0);
    for (int i = 0; i < 20; ++i) cp_move(w, &b, 0.05f);
    CHECK(tw.fills == after_first); // cached

    // Break the floor under the body: without invalidation physics still sees it...
    tw.clear(0, 0, 0);
    cp_move(w, &b, 0.05f);
    CHECK((b.flags & CP_BODY_ON_GROUND) != 0u);
    // ...with it, the body drops.
    cp_invalidate_cell(w, X(0), Y(0), Z(0));
    cp_move(w, &b, 0.05f);
    CHECK((b.flags & CP_BODY_ON_GROUND) == 0u);
    CHECK(tw.fills == after_first + 1); // one dirty-box refill, not the brick

    cp_stats stats{};
    cp_stats_get(w, &stats);
    CHECK(stats.bricks >= 1 && stats.fills == tw.fills);

    tw.set(0, 0, 0, FULL);
    cp_invalidate_all(w);
    uint16_t shape = 99;
    cp_cell_get(w, X(0), Y(0), Z(0), &shape, nullptr);
    CHECK(shape == FULL);
    cp_world_destroy(w);
}

void unloaded_cells_are_never_cached() {
    TestWorld tw;
    tw.unload_negative_x = true;
    tw.set(-5, 0, 0, FULL);
    cp_world* w = make(tw);
    uint16_t shape = 99;
    cp_cell_get(w, X(-5), Y(0), Z(0), &shape, nullptr);
    CHECK(shape == 0); // unloaded_shape
    int fills = tw.fills;
    tw.unload_negative_x = false;
    cp_cell_get(w, X(-5), Y(0), Z(0), &shape, nullptr);
    CHECK(shape == FULL && tw.fills == fills + 1);
    cp_world_destroy(w);
}

void the_cache_stays_bounded() {
    TestWorld tw;
    cp_world* w = make(tw, 16);
    for (int i = 0; i < 200; ++i) {
        uint16_t s;
        cp_cell_get(w, X(i * 16), Y(0), Z(0), &s, nullptr);
    }
    cp_stats stats{};
    cp_stats_get(w, &stats);
    CHECK(stats.bricks <= 17); // capacity + the brick of the current call
    cp_world_destroy(w);
}

void raycasts_hit_shape_boxes_not_cells() {
    TestWorld tw;
    tw.set(5, 0, 0, POLE);
    tw.set(8, 0, 0, FULL);
    tw.set(3, 0, 1, FLOWER);
    cp_world* w = make(tw);
    double o[3] = {0.5f, 0.5f, 0.5f};
    double d[3] = {1, 0, 0};
    cp_hit hit{};
    CHECK(cp_raycast(w, o, d, 20.0f, SOLID, 0, &hit) == 1);
    CHECK(hit.kind == CP_HIT_CELL && hit.x == X(5) && hit.face == CP_FACE_NEG_X && near(hit.distance, 4.9f));

    double o2[3] = {0.5f, 0.5f, 0.1f}; // beside the pole: passes it and hits the cube
    CHECK(cp_raycast(w, o2, d, 20.0f, SOLID, 0, &hit) == 1);
    CHECK(hit.x == X(8) && near(hit.distance, 7.5f));

    // Pickable-only shapes answer a picking mask but not a solid one.
    double o3[3] = {0.5f, 0.5f, 1.5f};
    CHECK(cp_raycast(w, o3, d, 20.0f, SOLID, 0, &hit) == 0);
    CHECK(cp_raycast(w, o3, d, 20.0f, SOLID | PICK, 0, &hit) == 1 && hit.x == X(3));

    // Out of range.
    CHECK(cp_raycast(w, o, d, 3.0f, SOLID, 0, &hit) == 0 && hit.kind == CP_HIT_NONE);
    cp_world_destroy(w);
}

void raycasts_see_dynamic_boxes_and_fluids() {
    TestWorld tw;
    tw.set(6, 0, 0, FULL);
    tw.set(2, 0, 0, 0, 1);
    cp_world* w = make(tw);
    cp_aabb door{4.0, 0.0, 0.0, 4.2, 2.0, 1.0};
    int32_t id = cp_box_add(w, &door, DOOR, 0, 777);
    CHECK(id > 0);
    double o[3] = {0.5f, 0.5f, 0.5f};
    double d[3] = {1, 0, 0};
    cp_hit hit{};
    CHECK(cp_raycast(w, o, d, 20.0f, SOLID | DOOR, 0, &hit) == 1);
    CHECK(hit.kind == CP_HIT_BOX && hit.user == 777 && hit.id == id && hit.x == X(4) && near(hit.distance, 3.5f));
    CHECK(cp_raycast(w, o, d, 20.0f, SOLID, 0, &hit) == 1 && hit.x == X(6));
    CHECK(cp_raycast(w, o, d, 20.0f, SOLID, WATER, &hit) == 1 && hit.kind == CP_HIT_FLUID && hit.x == X(2));
    cp_aabb moved{10.0, 0.0, 0.0, 10.2, 2.0, 1.0};
    CHECK(cp_box_set(w, id, &moved, DOOR, 0) == CP_OK);
    CHECK(cp_raycast(w, o, d, 20.0f, DOOR, 0, &hit) == 1 && hit.x == X(10));
    CHECK(cp_box_remove(w, id) == CP_OK);
    CHECK(cp_raycast(w, o, d, 20.0f, DOOR, 0, &hit) == 0);
    cp_world_destroy(w);
}

void many_dynamic_boxes_use_the_grid() {
    TestWorld tw;
    cp_world* w = make(tw);
    for (int i = 0; i < 200; ++i) {
        cp_aabb b{static_cast<double>(i * 3), 0.0, 0.0, static_cast<double>(i * 3) + 1.0, 1.0, 1.0};
        CHECK(cp_box_add(w, &b, SOLID, 0, static_cast<uint64_t>(i)) > 0);
    }
    double o[3] = {300.5f, 0.5f, -5.0f};
    double d[3] = {0, 0, 1};
    cp_hit hit{};
    CHECK(cp_raycast(w, o, d, 20.0f, SOLID, 0, &hit) == 1 && hit.user == 100);
    cp_body b = player(300.5f, 5.0f, 0.5f);
    b.flags = 0;
    b.vy = -100.0f;
    cp_move(w, &b, 0.1f);
    CHECK(near(b.y, 1.0f));
    cp_world_destroy(w);
}

void sweeps_stop_projectiles_at_thin_shapes() {
    TestWorld tw;
    tw.set(5, 0, 0, POLE);
    cp_world* w = make(tw);
    double from[3] = {0.5f, 0.5f, 0.5f};
    double to[3] = {20.5f, 0.5f, 0.5f};
    cp_hit hit{};
    CHECK(cp_sweep(w, from, to, 0.15f, 0.15f, 0.15f, SOLID, &hit) == 1);
    // Front of the box (x + 0.15) meets the pole's face at 5.4.
    CHECK(near(hit.px, 5.25f, 1e-3f) && hit.x == X(5) && hit.face == CP_FACE_NEG_X);
    double to2[3] = {0.5f, 0.5f, 10.5f};
    CHECK(cp_sweep(w, from, to2, 0.15f, 0.15f, 0.15f, SOLID, &hit) == 0);
    // Grazing a floor top is not a hit.
    tw.set(1, -1, 0, FULL);
    tw.set(2, -1, 0, FULL);
    cp_invalidate_all(w);
    double g0[3] = {0.5f, 0.15f, 0.5f};
    double g1[3] = {3.0f, 0.15f, 0.5f};
    CHECK(cp_sweep(w, g0, g1, 0.15f, 0.15f, 0.15f, SOLID, &hit) == 0);
    cp_world_destroy(w);
}

void fluids_measure_submersion_and_flow() {
    TestWorld tw;
    for (int y = 0; y < 3; ++y) tw.set(0, y, 0, 0, 1); // a 3-deep column of source water
    tw.set(1, 2, 0, 0, 2);                               // flowing water beside the top
    cp_world* w = make(tw);
    cp_fluid_sample s{};
    // Feet at 1.0, 1.8 tall: surface at 2.875 -> 1.875 under -> fully submerged.
    CHECK(cp_fluid_measure(w, 0.5, 1.0, 0.5, 1.8f, 0.3f, 1.0, 1.8f, WATER, &s) == CP_OK);
    CHECK(near(s.submersion, 1.0f) && near(s.surface_y, 2.875f));
    // Feet at 2.0: 0.875 of 1.8.
    cp_fluid_measure(w, 0.5, 2.0, 0.5, 1.8f, 0.3f, 2.0, 1.8f, WATER, &s);
    CHECK(near(s.submersion, 0.875f / 1.8f));
    // Dry.
    cp_fluid_measure(w, 5.5, 2.0, 0.5, 1.8f, 0.3f, 2.0, 1.8f, WATER, &s);
    CHECK(s.submersion == 0.0f && std::isnan(s.surface_y) && s.flow_samples == 0);
    // Flow: the sample offset into x = 1 reads the flowing cell.
    cp_fluid_measure(w, 0.9, 2.0, 0.5, 1.0f, 0.5f, 2.0, 1.0f, WATER, &s);
    CHECK(s.flow_samples > 0 && s.flow_x > 0.0f);

    cp_integrator p{};
    p.gravity = -40.0f;
    p.float_submersion = 0.55f;
    p.drift_speed = 1.2f;
    p.fluid_drag = 2.2f;
    // Fully under: buoyancy pushes up, capped at the drift.
    float vy = 0.0f;
    for (int i = 0; i < 40; ++i) vy = cp_buoyancy_vy(vy, 1.0f, 0.05f, &p);
    CHECK(vy > 0.0f && vy <= 1.2f);
    // At the float line: no net force.
    CHECK(near(cp_buoyancy_vy(0.0f, 0.55f, 0.05f, &p), 0.0f));
    cp_world_destroy(w);
}

void step_integrates_gravity_buoyancy_and_friction() {
    TestWorld tw;
    floor_at(tw, 0);
    for (int x = -3; x <= 3; ++x)
        for (int z = -3; z <= 3; ++z)
            for (int y = 1; y <= 3; ++y) tw.set(x, y, z, 0, 1); // a pond 3 deep on the floor
    cp_world* w = make(tw);
    cp_integrator p{};
    p.gravity = -40.0f;
    p.ground_friction = 5.0f;
    p.float_submersion = 0.55f;
    p.drift_speed = 1.2f;
    p.fluid_drag = 2.2f;
    p.fluid_mask = WATER;
    cp_body mob = player(0.5f, 1.0f, 0.5f);
    mob.above = 1.0f;
    mob.flags = CP_BODY_ON_GROUND | CP_BODY_KEEP_VELOCITY;
    cp_fluid_sample s{};
    for (int i = 0; i < 400; ++i) cp_step(w, &mob, 1, 0.05f, &p, &s);
    // Floats with about 55% under: feet at the surface (3.875) - 0.55.
    CHECK(near(mob.y, 3.875 - 0.55, 0.05));

    // Dry ground: friction decays horizontal speed.
    cp_body walker = player(6.5f, 1.0f, 6.5f);
    walker.vx = 1.0f;
    cp_step(w, &walker, 1, 0.05f, &p, nullptr);
    CHECK(walker.vx < 1.0f && walker.vx > 0.7f && (walker.flags & CP_BODY_ON_GROUND) != 0u);
    cp_world_destroy(w);
}

void cell_queries() {
    TestWorld tw;
    tw.set(0, 0, 0, STAIR_LOW);
    tw.set(1, 0, 0, FULL);
    tw.set(2, 0, 0, FLOWER);
    cp_world* w = make(tw);
    // Footprint over the front half of the stair: the low tread.
    CHECK(near(cp_cell_top(w, X(0), Y(0), Z(0), 0.2, 0.1, 0.8, 0.4, SOLID), 0.5));
    // Whole cell: the tallest tread.
    CHECK(near(cp_cell_top(w, X(0), Y(0), Z(0), 0.0, 0.0, 1.0, 1.0, SOLID), 1.0));
    CHECK(cp_cell_top(w, X(2), Y(0), Z(0), 2.0, 0.0, 3.0, 1.0, SOLID) == 0.0f);

    int64_t cells[] = {X(1), Y(0), Z(0), X(2), Y(0), Z(0), X(3), Y(0), Z(0)};
    double pref[3] = {3.5, 0.5, 0.5};
    CHECK(cp_nearest_open_cell(w, cells, 3, pref, SOLID) == 2);
    CHECK(cp_nearest_open_cell(w, cells, 3, nullptr, SOLID) == 1);
    CHECK(cp_nearest_open_cell(w, cells, 1, nullptr, SOLID) == -1);

    cp_aabb inside{1.2, 0.2, 0.2, 1.4, 0.4, 0.4};
    cp_aabb touching{1.0, 1.0, 0.0, 2.0, 2.0, 1.0};
    CHECK(cp_overlaps(w, &inside, SOLID) == 1);
    CHECK(cp_overlaps(w, &touching, SOLID) == 0);
    cp_world_destroy(w);
}

void ray_boxes_match_the_slab_test() {
    cp_aabb boxes[3] = {{5, 0, 0, 6, 1, 1}, {2, 0, 0, 3, 1, 1}, {2, 5, 0, 3, 6, 1}};
    double o[3] = {0.0, 0.5, 0.5};
    double d[3] = {10.0, 0.0, 0.0}; // segment of length 10: t in units of it
    double t[3];
    CHECK(cp_ray_boxes(o, d, 1.0, boxes, 3, 0.0, t) == 1);
    CHECK(near(t[0], 0.5) && near(t[1], 0.2) && std::isinf(t[2]));
    CHECK(cp_ray_boxes(o, d, 0.1, boxes, 3, 0.0, nullptr) == -1);
    CHECK(cp_ray_boxes(o, d, 1.0, boxes, 3, 0.5, t) == 1 && near(t[1], 0.15));
}

void rejects_bad_input() {
    CHECK(cp_world_create(nullptr) == nullptr);
    cp_world_desc bad{};
    bad.brick_shift = 9;
    CHECK(cp_world_create(&bad) == nullptr);
    CHECK(std::strlen(cp_last_error()) > 0);
    TestWorld tw;
    cp_world* w = make(tw);
    cp_body b = player(NAN, 0, 0);
    CHECK(cp_move(w, &b, 0.05f) == CP_ERR_ARG);
    CHECK(cp_shape_define(w, 0, nullptr, 0, 0, 0) == CP_ERR_ARG);
    cp_aabb inverted{1, 1, 1, 0, 0, 0};
    CHECK(cp_box_add(w, &inverted, SOLID, 0, 0) == 0);
    CHECK(cp_box_remove(w, 42) == CP_ERR_ARG);
    cp_world_destroy(w);
}

void run_scenarios() {
    falls_and_lands_without_tunnelling();
    walls_stop_and_slide();
    steps_up_low_obstacles_only();
    climbs_a_stair_one_tread_at_a_time();
    airborne_bodies_mantle_only_with_air_step();
    walking_off_an_edge_drops_the_ground_flag();
    a_body_inside_low_snow_is_lifted();
    the_cache_fills_once_and_honours_invalidation();
    unloaded_cells_are_never_cached();
    the_cache_stays_bounded();
    raycasts_hit_shape_boxes_not_cells();
    raycasts_see_dynamic_boxes_and_fluids();
    many_dynamic_boxes_use_the_grid();
    sweeps_stop_projectiles_at_thin_shapes();
    fluids_measure_submersion_and_flow();
    step_integrates_gravity_buoyancy_and_friction();
    cell_queries();
    ray_boxes_match_the_slab_test();
    rejects_bad_input();
}

// ---- 64-bit coordinates (ABI 2) ------------------------------------------------------------------

constexpr int64_t kFar = int64_t{1} << 40;

// Cells that shared a key under the old packing (21 bits per brick axis: bricks b and b + 2^21, i.e.
// cells 2^25 apart at edge 16) are distinct now, on every axis and across the sign.
void far_cells_never_alias() {
    TestWorld tw;
    tw.set_abs(3, 4, 5, FULL);
    tw.set_abs(-7, -9, 11, HALF);
    cp_world* w = make(tw);
    const int64_t alias = int64_t{1} << 25;
    const int64_t probes[][3] = {{3 + alias, 4, 5}, {3, 4 + alias, 5}, {3, 4, 5 + alias}, {3 - alias, 4, 5},
                                 {3 + 2 * alias, 4 - 2 * alias, 5}, {3 + kFar, 4, 5}, {3, 4 - kFar, 5 + kFar},
                                 {-7 + alias, -9, 11}, {-7, -9 - alias, 11 + alias}};
    uint16_t shape = 99;
    CHECK(cp_cell_get(w, 3, 4, 5, &shape, nullptr) == CP_OK && shape == FULL);
    for (const auto& p : probes) {
        shape = 99;
        cp_cell_get(w, p[0], p[1], p[2], &shape, nullptr);
        CHECK(shape == 0);
    }
    // And back: the originals still read right with all those bricks cached beside them.
    cp_cell_get(w, 3, 4, 5, &shape, nullptr);
    CHECK(shape == FULL);
    cp_cell_get(w, -7, -9, 11, &shape, nullptr);
    CHECK(shape == HALF);
    // Invalidating a far cell must not touch its old alias.
    int fills = tw.fills;
    cp_invalidate_cell(w, 3 + alias, 4, 5);
    cp_cell_get(w, 3, 4, 5, &shape, nullptr);
    CHECK(shape == FULL && tw.fills == fills);
    cp_world_destroy(w);
}

// Moving the frame origin keeps every cached cell (no refill) and every dynamic box in its absolute
// place; the same absolute move gives the same absolute result in either frame.
void origin_moves_keep_the_cache() {
    TestWorld tw;
    for (int64_t x = -8; x <= 8; ++x)
        for (int64_t z = -8; z <= 8; ++z) tw.set_abs(kFar + x, -kFar, -kFar + z, FULL);
    cp_world* w = make(tw);
    CHECK(cp_set_origin(w, kFar, -kFar + 1, -kFar) == CP_OK);
    cp_body b = player(0.5, 0.0, 0.5); // feet on the floor top: absolute y = -kFar + 1
    for (int i = 0; i < 10; ++i) cp_move(w, &b, 0.05f);
    CHECK((b.flags & CP_BODY_ON_GROUND) != 0u && b.y == 0.0);
    // A dynamic box two cells east of the body, then move the origin by an odd amount.
    cp_aabb wall{2.0, 0.0, 0.0, 2.2, 2.0, 1.0};
    int32_t id = cp_box_add(w, &wall, DOOR, 0, 5);
    CHECK(id > 0);
    const int fills = tw.fills;
    const int64_t d[3] = {-37, 1000003, 12};
    CHECK(cp_set_origin(w, kFar + d[0], -kFar + 1 + d[1], -kFar + d[2]) == CP_OK);
    int64_t o[3];
    CHECK(cp_get_origin(w, o) == CP_OK && o[0] == kFar + d[0] && o[1] == -kFar + 1 + d[1] && o[2] == -kFar + d[2]);
    b.x -= static_cast<double>(d[0]);
    b.y -= static_cast<double>(d[1]);
    b.z -= static_cast<double>(d[2]);
    for (int i = 0; i < 10; ++i) cp_move(w, &b, 0.05f);
    CHECK((b.flags & CP_BODY_ON_GROUND) != 0u && b.y == -static_cast<double>(d[1]));
    CHECK(tw.fills == fills); // nothing refilled
    // The box is still where it was in absolute terms.
    double ro[3] = {b.x, b.y + 1.0, b.z};
    double rd[3] = {1, 0, 0};
    cp_hit hit{};
    CHECK(cp_raycast(w, ro, rd, 10.0, DOOR, 0, &hit) == 1 && hit.kind == CP_HIT_BOX && hit.user == 5);
    CHECK(near(hit.distance, 1.5) && hit.x == kFar + 2 && hit.y == -kFar + 2 && hit.z == -kFar);
    // Walking into it stops at its absolute face.
    cp_body walker = b;
    walker.vx = 20.0f;
    walker.mask = SOLID | DOOR;
    cp_move(w, &walker, 0.1f);
    CHECK(near(walker.x + static_cast<double>(d[0]), 2.0 - 0.3, 1e-6)); // half_x is the float 0.3f
    cp_world_destroy(w);
}

// Far from the origin WITHOUT rebasing (origin 0, positions ~2^40 as doubles, about 1/4096 apart):
// bodies still land, rest and walk with sub-1/1000-block error, and rays hit the right cells.
void far_positions_without_rebasing_stay_stable() {
    TestWorld tw;
    const int64_t fx = kFar, fy = -kFar, fz = -kFar;
    for (int64_t x = -8; x <= 8; ++x)
        for (int64_t z = -8; z <= 8; ++z) tw.set_abs(fx + x, fy, fz + z, FULL);
    for (int64_t z = -8; z <= 8; ++z) tw.set_abs(fx + 3, fy + 1, fz + z, FULL); // a wall at x = fx + 3
    cp_world* w = make(tw);
    const double top = static_cast<double>(fy) + 1.0;
    cp_body b = player(static_cast<double>(fx) + 0.5, top + 5.0, static_cast<double>(fz) + 0.5);
    b.flags = 0;
    b.vy = -100.0f;
    cp_move(w, &b, 0.1f);
    CHECK(b.y == top && (b.flags & CP_BODY_ON_GROUND) != 0u);
    for (int i = 0; i < 200; ++i) cp_move(w, &b, 1.0f / 60.0f);
    CHECK(b.y == top); // resting: never creeps
    b.vx = 20.0f;
    cp_move(w, &b, 0.2f);
    CHECK(std::fabs(b.x - (static_cast<double>(fx) + 3.0 - 0.3)) < 1e-3 && b.y == top);
    double ro[3] = {static_cast<double>(fx) + 0.25, top + 0.5, static_cast<double>(fz) + 0.5};
    double rd[3] = {1, 0, 0};
    cp_hit hit{};
    CHECK(cp_raycast(w, ro, rd, 10.0, SOLID, 0, &hit) == 1);
    CHECK(hit.x == fx + 3 && hit.y == fy + 1 && hit.z == fz && std::fabs(hit.distance - 2.75) < 1e-3);
    cp_world_destroy(w);
}

} // namespace

int main() {
    CHECK(cp_abi_version() == CP_ABI_VERSION);
    run_scenarios();
    // The same scenarios far from 0 in every direction (Y negative), the frame origin moved there.
    g_off[0] = kFar;
    g_off[1] = -kFar - 12345;
    g_off[2] = -kFar + 77;
    run_scenarios();
    g_off[0] = -kFar * 3;
    g_off[1] = kFar;
    g_off[2] = kFar * 5;
    run_scenarios();
    g_off[0] = g_off[1] = g_off[2] = 0;
    far_cells_never_alias();
    origin_moves_keep_the_cache();
    far_positions_without_rebasing_stay_stable();
    if (failures != 0) {
        std::fprintf(stderr, "physics_world: %d check(s) failed\n", failures);
        return 1;
    }
    std::printf("physics_world: ok\n");
    return 0;
}
