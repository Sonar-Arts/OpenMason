// Shared internals of Cenda navigation (cn_*): the world/service and what agents need from it.
#pragma once

#include "nav_search.hpp"

#include <atomic>
#include <cstdint>
#include <deque>
#include <string>
#include <unordered_map>

namespace cenda::nav {

struct Request {
    bool air = false;
    GroundJob ground{};
    AirJob air_job{};
    double goal[3]{};  // the goal as asked, in the asker's frame (agents compare it with where they want to go now)
    Node goal_frame{}; // that frame's origin
    bool done = false;
    Route route;
    uint64_t done_step = 0;
};

// Sets the calling thread's last error; returns code.
int32_t fail(int32_t code, const char* message);

} // namespace cenda::nav

struct cn_world {
    uint64_t serial = 0;
    cenda::nav::CellRules rules;
    cenda::nav::Node origin{}; // frame of raw queries, waypoints read out and reach surfaces
    int32_t max_in_flight = 64;

    std::deque<int64_t> queue; // ids, submission order
    std::unordered_map<int64_t, cenda::nav::Request> requests;
    int64_t next_id = 1;
    uint64_t steps = 0;

    std::atomic<int64_t> submitted{0};
    std::atomic<int64_t> rejected{0};
    std::atomic<int64_t> completed{0};
    std::atomic<int64_t> failed{0};
    std::atomic<int64_t> partial{0};
    std::atomic<int64_t> search_nanos{0};
    std::atomic<int32_t> in_flight{0};

    cenda::nav::Searcher searcher;
};

namespace cenda::nav {

// Queue a ground search: from the FEET, to goal, both relative to frame (an absolute origin). Returns
// the id, or 0 when refused.
int64_t submit_ground(cn_world* w, const double feet[3], const double goal[3], const Node& frame, float goal_radius,
                      int32_t snap_down, int32_t snap_up, const cn_ground_profile& profile, const cn_limits& limits);
// As submit_ground; profile.cruise_y is relative to frame too.
int64_t submit_air(cn_world* w, const double from[3], const double goal[3], const Node& frame, float goal_radius,
                   int32_t goal_snap_cells, const cn_air_profile& profile, const cn_limits& limits);
// The finished request, or null while queued / unknown.
Request* finished(cn_world* w, int64_t id);
// Cancels a queued search or forgets a result.
void release(cn_world* w, int64_t id);

// Whether an (absolute) cell blocks for the probes: shape in mask with a full-block top.
bool blocks(physics_detail::CellView& view, int64_t x, int64_t y, int64_t z, uint32_t mask);

// JOML's Vector3f.distance arithmetic: sqrt(dx*dx + (dy*dy + dz*dz)); each difference taken in double
// and rounded once to float (exactly the float subtraction when both were floats).
inline float distance3(double ax, double ay, double az, double bx, double by, double bz) {
    float dx = static_cast<float>(ax - bx), dy = static_cast<float>(ay - by), dz = static_cast<float>(az - bz);
    float t = dz * dz;
    t = dy * dy + t;
    t = dx * dx + t;
    return static_cast<float>(std::sqrt(static_cast<double>(t)));
}

// floor(v + add) with the add done in float relative to an anchor near v (see anchor_of): the Java's
// (int) Math.floor(v + add) on floats exactly near the frame origin, full precision anywhere.
inline int64_t floor_add(double v, float add) {
    const int64_t a = anchor_of(v);
    const float rel = static_cast<float>(v - static_cast<double>(a));
    return static_cast<int64_t>(std::floor(static_cast<double>(rel + add))) + a;
}

// Steering math shared by the agents and the pure cn_steer_* helpers.
float yaw_for(float dir_x, float dir_z, float model_yaw_offset);
float turn(float yaw, float target, float max_step);

} // namespace cenda::nav
