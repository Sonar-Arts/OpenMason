// Cenda navigation agents (cn_ground_agent_*, cn_air_agent_*). Contract in include/cenda/nav.h
// (OpenMason #116). A port of the Java ground/air path agents and steering it replaced: the same state
// machine, in the same order, with every tuning number taken from the host's descriptor.
//
// The agent never touches the body. What the Java steering did to the entity — turn it, set its
// horizontal velocity, make it jump or stroke — is accumulated in a cn_apply the host applies after
// the call; the agent mirrors the two effects that later steps of the same tick read back (the yaw
// it turned to, and a jump/stroke taking the body off the ground).
//
// Frames (ABI 3): an agent holds every position (goal, route, stuck anchor, plan origin, cruise) as a
// double in ITS frame (origin set by cn_*_agent_set_origin, default 0). It talks to the world in
// absolute cells: searches are submitted with the agent's origin as their frame, and routes come back
// against an absolute anchor. Float math the Java did on positions runs on differences taken in
// double and rounded once (the float subtraction itself when both sides were floats).

#include "nav_internal.hpp"

#include <algorithm>
#include <cmath>
#include <new>
#include <vector>

using namespace cenda::nav;

namespace {

struct Pending {
    int64_t id = 0;
    uint64_t world_serial = 0;
};

// The route being followed (in the agent's frame): waypoints + whether it reaches its goal.
struct Path {
    std::vector<double> points;
    bool complete = false;
    int32_t size() const { return static_cast<int32_t>(points.size() / 3); }
    bool empty() const { return points.empty(); }
    double x(int32_t i) const { return points[static_cast<size_t>(i) * 3]; }
    double y(int32_t i) const { return points[static_cast<size_t>(i) * 3 + 1]; }
    double z(int32_t i) const { return points[static_cast<size_t>(i) * 3 + 2]; }

    void shift(const double d[3]) {
        for (size_t i = 0; i < points.size(); ++i) points[i] += d[i % 3];
    }
};

// A finished route in a frame: (anchor - frame) + each anchor-relative point.
Path path_in_frame(Route&& route, const Node& frame) {
    Path p;
    p.complete = route.complete;
    p.points = std::move(route.points);
    const double base[3] = {static_cast<double>(route.anchor.x - frame.x), static_cast<double>(route.anchor.y - frame.y),
                            static_cast<double>(route.anchor.z - frame.z)};
    p.shift(base);
    return p;
}

// The shift that moves a position from frame `from` into frame `to`.
void frame_delta(const Node& from, const Node& to, double out[3]) {
    out[0] = static_cast<double>(from.x - to.x);
    out[1] = static_cast<double>(from.y - to.y);
    out[2] = static_cast<double>(from.z - to.z);
}

bool same_world(const cn_world* w, const Pending& p) {
    return w != nullptr && w->serial == p.world_serial;
}

void fill_view(cn_agent_view* view, int32_t status, int32_t cursor, bool has_goal, int32_t version,
               const double goal[3], int32_t points) {
    if (view == nullptr) return;
    view->status = status;
    view->cursor = cursor;
    view->has_goal = has_goal ? 1 : 0;
    view->path_version = version;
    view->goal_x = goal[0];
    view->goal_y = goal[1];
    view->goal_z = goal[2];
    view->path_points = points;
    view->reserved = 0;
}

int32_t copy_path(const Path& path, double* xyz, int32_t cap, int32_t* out_complete) {
    if (out_complete != nullptr) *out_complete = path.complete ? 1 : 0;
    int32_t n = path.size();
    if (xyz != nullptr && cap > 0) {
        std::copy_n(path.points.data(), static_cast<size_t>(std::min(n, cap)) * 3, xyz);
    }
    return n;
}

} // namespace

// ================================================================================================
// Ground agent (PathAgent + Steering)
// ================================================================================================

struct cn_ground_agent {
    cn_ground_agent_desc d{};
    Node origin{}; // the agent's frame

    double desired[3]{};
    double planned[3]{};
    bool has_goal = false;
    float goal_radius = 1.0f;
    float speed_multiplier = 1.0f;

    Path path;
    int32_t cursor = 0;
    int32_t path_version = 0;
    Pending pending;

    float search_cooldown = 0.0f;
    float stuck_timer = 0.0f;
    double previous[3]{};
    int32_t status = CN_AGENT_IDLE;
};

// Steering: its tuning and the state the agent and the host share.
struct cn_steering {
    cn_steering_desc d{};
    float jump_cooldown = 0.0f;
    float hop_timer = 0.0f;
};

namespace {

// The body as this tick sees it, with the effects already decided this tick applied. frame: the
// origin its position is relative to (cells probed = frame + floor(position)).
struct Body {
    const cn_body* in;
    float yaw;
    bool on_ground;
    bool in_water;
    cn_apply* out;
    Node frame;
};

void view_of(const cn_ground_agent* a, cn_agent_view* view) {
    fill_view(view, a->status, a->cursor, a->has_goal, a->path_version, a->desired, a->path.size());
}

void clear_path(cn_ground_agent* a) {
    if (!a->path.empty() || a->cursor != 0) {
        a->path.points.clear();
        a->path.complete = false;
        a->path_version++;
    }
    a->cursor = 0;
}

void cancel_pending(cn_ground_agent* a, cn_world* w) {
    if (a->pending.id != 0) {
        if (same_world(w, a->pending)) release(w, a->pending.id);
        a->pending = Pending{};
    }
}

void stop_moving(cn_apply* out) {
    out->apply |= CN_APPLY_VELOCITY;
    out->vx = 0.0f;
    out->vz = 0.0f;
}

// Steering.requestJump: a jump on land, a swim stroke in water, sharing one cooldown.
bool request_jump(cn_steering* st, Body& b) {
    if (st->jump_cooldown > 0.0f) return false;
    if (b.in_water) {
        b.out->apply |= CN_APPLY_SWIM_UP;
        b.on_ground = false;
        st->jump_cooldown = st->d.swim_stroke_cooldown;
        return true;
    }
    if (!b.on_ground) return false;
    b.out->apply |= CN_APPLY_JUMP;
    b.on_ground = false;
    st->jump_cooldown = st->d.jump_cooldown;
    if (st->d.hop_boost_speed > 0.0f) st->hop_timer = st->d.hop_duration;
    return true;
}

// Steering.shouldStrokeOverShore: a stroke from the water reaches the top of the shore ahead.
// bx, by, bz absolute; feet_y in the body's frame.
bool stroke_over_shore(const cn_steering* st, cenda::physics_detail::CellView& view, const Body& b, int64_t bx,
                       int64_t by, int64_t bz, double feet_y) {
    const uint32_t mask = st->d.collide_mask;
    int64_t top = by;
    bool found = false;
    int64_t limit = by + st->d.max_shore_probe_cells;
    for (int64_t y = by; y <= limit; ++y) {
        if (blocks(view, bx, y, bz, mask)) {
            found = true;
            top = y;
        } else if (found) {
            break;
        }
    }
    if (!found) return false;
    // shore (top + 1) above the feet, as the Java's float subtraction.
    float rise = static_cast<float>(static_cast<double>(top - b.frame.y) + 1.0 - feet_y);
    if (rise > b.in->swim_stroke_reach) return false;
    return !blocks(view, bx, top + 1, bz, mask) && !blocks(view, bx, top + 2, bz, mask);
}

// Steering.shouldJumpObstacle: a solid cell at foot level just past the leading edge, with two clear
// cells above it (or, from water, a shore a stroke can top).
bool jump_obstacle(const cn_steering* st, cp_world* physics, const Body& b, float dir_x, float dir_z) {
    if ((!b.on_ground && !b.in_water) || st->jump_cooldown > 0.0f || physics == nullptr) return false;
    const cn_body& in = *b.in;
    float reach = std::fabs(dir_x) * in.half_width + std::fabs(dir_z) * in.half_length + st->d.obstacle_look_ahead;
    int64_t bx = floor_add(in.x, dir_x * reach) + b.frame.x;
    int64_t bz = floor_add(in.z, dir_z * reach) + b.frame.z;
    double feet_y = in.y - static_cast<double>(in.leg_height);
    int64_t by = floor_add(feet_y, st->d.foot_probe_lift) + b.frame.y;

    cenda::physics_detail::CellView view(physics);
    if (b.in_water) return stroke_over_shore(st, view, b, bx, by, bz, feet_y);
    const uint32_t mask = st->d.collide_mask;
    if (!blocks(view, bx, by, bz, mask)) return false;
    if (st->d.step_height >= 1.0f) return false; // a whole block is a step to this body: no hop
    return !blocks(view, bx, by + 1, bz, mask) && !blocks(view, bx, by + 2, bz, mask);
}

// Steering.steerAlong: turn toward the heading, hop an obstacle, drive horizontal velocity.
void steer_along(cn_steering* st, cp_world* physics, Body& b, float dir_x, float dir_z, float speed_multiplier,
                 float dt, bool allow_jump) {
    b.yaw = turn(b.yaw, yaw_for(dir_x, dir_z, st->d.model_yaw_offset), st->d.rotation_speed * dt);
    b.out->apply |= CN_APPLY_YAW;
    b.out->yaw = b.yaw;

    if (allow_jump && jump_obstacle(st, physics, b, dir_x, dir_z)) request_jump(st, b);

    const cn_body& in = *b.in;
    float speed = st->hop_timer > 0.0f ? st->d.hop_boost_speed : in.move_speed * speed_multiplier * in.speed_multiplier;
    if (b.in_water) speed *= in.water_speed_factor;
    b.out->apply |= CN_APPLY_VELOCITY;
    b.out->vx = dir_x * speed;
    b.out->vz = dir_z * speed;
}

void arrive(cn_ground_agent* a, Body& b) {
    clear_path(a);
    stop_moving(b.out);
    a->status = CN_AGENT_ARRIVED;
}

void collect(cn_ground_agent* a, cn_world* w) {
    if (a->pending.id == 0) return;
    if (!same_world(w, a->pending)) {
        // The world was swapped underneath us: whatever comes back describes a world this body no
        // longer lives in.
        cancel_pending(a, w);
        return;
    }
    Request* r = finished(w, a->pending.id);
    if (r == nullptr) return;
    double shift[3];
    frame_delta(r->goal_frame, a->origin, shift);
    double goal[3] = {r->goal[0] + shift[0], r->goal[1] + shift[1], r->goal[2] + shift[2]};
    Path result = path_in_frame(std::move(r->route), a->origin);
    release(w, a->pending.id);
    a->pending = Pending{};

    if (!a->has_goal) return;
    if (distance3(goal[0], goal[1], goal[2], a->desired[0], a->desired[1], a->desired[2]) > a->d.goal_drift) {
        a->search_cooldown = 0.0f; // the target moved while we searched: the route is already wrong
        return;
    }
    if (result.empty()) {
        a->status = CN_AGENT_NO_PATH;
        a->search_cooldown = a->d.no_path_retry;
        return;
    }
    a->path = std::move(result);
    a->path_version++;
    a->cursor = 0;
    a->planned[0] = goal[0];
    a->planned[1] = goal[1];
    a->planned[2] = goal[2];
    a->stuck_timer = 0.0f;
    a->status = CN_AGENT_FOLLOWING;
}

void request_if_needed(cn_ground_agent* a, cn_world* w, const cn_body& in) {
    if (a->pending.id != 0 || a->search_cooldown > 0.0f) return;
    bool needs = a->path.empty() || a->cursor >= a->path.size()
        || distance3(a->planned[0], a->planned[1], a->planned[2], a->desired[0], a->desired[1], a->desired[2])
            > a->d.goal_drift;
    if (!needs) return;
    if (w == nullptr) {
        a->status = CN_AGENT_NO_PATH;
        a->search_cooldown = a->d.no_path_retry;
        return;
    }
    double feet[3] = {in.x, in.y - static_cast<double>(in.leg_height), in.z};
    int64_t id = submit_ground(w, feet, a->desired, a->origin, a->goal_radius, a->d.snap_down, a->d.snap_up,
                               a->d.profile, a->d.limits);
    if (id == 0) {
        a->search_cooldown = a->d.saturated_retry;
        return;
    }
    a->pending.id = id;
    a->pending.world_serial = w->serial;
    a->search_cooldown = a->d.repath_interval;
    if (a->path.empty()) a->status = CN_AGENT_SEARCHING;
}

void follow(cn_ground_agent* a, cn_steering* st, cp_world* physics, Body& b, float dt) {
    if (a->path.empty()) {
        stop_moving(b.out); // hold position until a route arrives
        return;
    }
    if (a->cursor >= a->path.size()) {
        arrive(a, b);
        return;
    }
    const cn_body& in = *b.in;
    const double feet_y = in.y - static_cast<double>(in.leg_height);
    float dx = static_cast<float>(a->path.x(a->cursor) - in.x);
    float dz = static_cast<float>(a->path.z(a->cursor) - in.z);
    float horizontal = static_cast<float>(std::sqrt(static_cast<double>(dx * dx + dz * dz)));

    if (horizontal < a->d.waypoint_radius
        && std::fabs(static_cast<float>(a->path.y(a->cursor) - feet_y)) < a->d.waypoint_vertical_slack) {
        a->cursor++;
        if (a->cursor >= a->path.size()) {
            arrive(a, b);
            return;
        }
        dx = static_cast<float>(a->path.x(a->cursor) - in.x);
        dz = static_cast<float>(a->path.z(a->cursor) - in.z);
        horizontal = static_cast<float>(std::sqrt(static_cast<double>(dx * dx + dz * dz)));
    }
    if (horizontal < 1e-4f) {
        stop_moving(b.out);
        return;
    }
    // A glider floating down onto the waypoint: hold over it rather than overshooting and circling
    // back all the way down.
    if ((in.flags & CN_BODY_GLIDING) != 0u && horizontal < a->d.waypoint_radius && !b.on_ground
        && static_cast<float>(feet_y - a->path.y(a->cursor)) >= a->d.waypoint_vertical_slack) {
        stop_moving(b.out);
        a->status = CN_AGENT_FOLLOWING;
        return;
    }
    steer_along(st, physics, b, dx / horizontal, dz / horizontal, a->speed_multiplier, dt, true);

    // The route planned a rise the step cannot make, so it planned a jump: take it on the approach.
    if (static_cast<float>(a->path.y(a->cursor) - feet_y) > a->d.profile.max_step_up + a->d.jump_rise_margin
        && horizontal < a->d.jump_approach) {
        request_jump(st, b);
    }
    a->status = CN_AGENT_FOLLOWING;
}

void update_stuck(cn_ground_agent* a, const cn_body& in, float dt) {
    if (a->status != CN_AGENT_FOLLOWING) {
        a->previous[0] = in.x;
        a->previous[1] = in.y;
        a->previous[2] = in.z;
        a->stuck_timer = 0.0f;
        return;
    }
    float moved = distance3(in.x, in.y, in.z, a->previous[0], a->previous[1], a->previous[2]);
    a->previous[0] = in.x;
    a->previous[1] = in.y;
    a->previous[2] = in.z;
    if (moved < a->d.stuck_speed * dt) {
        a->stuck_timer += dt;
        if (a->stuck_timer >= a->d.stuck_seconds) {
            a->stuck_timer = 0.0f;
            clear_path(a);
            a->search_cooldown = 0.0f;
            a->status = CN_AGENT_STUCK;
        }
    } else {
        a->stuck_timer = 0.0f;
    }
}

void steering_tick(cn_steering* st, bool on_ground, float dt) {
    st->jump_cooldown = std::max(0.0f, st->jump_cooldown - dt);
    // The hop boost ends as soon as the body lands (or after the safety cap).
    if (on_ground) {
        st->hop_timer = 0.0f;
    } else {
        st->hop_timer = std::max(0.0f, st->hop_timer - dt);
    }
}

bool valid_body(const cn_body* b) {
    return b != nullptr && std::isfinite(b->x) && std::isfinite(b->y) && std::isfinite(b->z) && std::isfinite(b->yaw)
        && std::fabs(b->x) < 4.0e18 && std::fabs(b->y) < 4.0e18 && std::fabs(b->z) < 4.0e18;
}

bool finite_pos(double x, double y, double z) {
    return std::isfinite(x) && std::isfinite(y) && std::isfinite(z) && std::fabs(x) < 4.0e18 && std::fabs(y) < 4.0e18
        && std::fabs(z) < 4.0e18;
}

// The frame a host-driven steering call's body is in: its physics world's (0 without one).
Node physics_frame(const cp_world* physics) {
    if (physics == nullptr) return Node{};
    int64_t o[3];
    cenda::physics_detail::origin(physics, o);
    return Node{o[0], o[1], o[2]};
}

} // namespace

extern "C" {

cn_ground_agent* cn_ground_agent_create(const cn_ground_agent_desc* desc, const int64_t* origin, double x, double y,
                                        double z) {
    if (desc == nullptr || desc->limits.max_expansions <= 0 || !(desc->limits.max_cost > 0.0f)
        || !(desc->limits.heuristic_weight >= 1.0f) || !(desc->profile.height > 0.0f)) {
        fail(CN_ERR_ARG, "bad ground agent description");
        return nullptr;
    }
    auto* a = new (std::nothrow) cn_ground_agent();
    if (a == nullptr) {
        fail(CN_ERR_ARG, "out of memory");
        return nullptr;
    }
    a->d = *desc;
    if (origin != nullptr) a->origin = Node{origin[0], origin[1], origin[2]};
    a->previous[0] = x;
    a->previous[1] = y;
    a->previous[2] = z;
    return a;
}

void cn_ground_agent_destroy(cn_ground_agent* agent) {
    delete agent;
}

int32_t cn_ground_agent_set_origin(cn_ground_agent* a, int64_t ox, int64_t oy, int64_t oz) {
    if (a == nullptr) return fail(CN_ERR_ARG, "null agent");
    const Node next{ox, oy, oz};
    double d[3];
    frame_delta(a->origin, next, d);
    for (int i = 0; i < 3; ++i) {
        a->desired[i] += d[i];
        a->planned[i] += d[i];
        a->previous[i] += d[i];
    }
    a->path.shift(d);
    a->origin = next;
    return CN_OK;
}

int32_t cn_ground_agent_move_to(cn_ground_agent* a, double x, double y, double z, float goal_radius, float speed,
                                cn_agent_view* view) {
    if (a == nullptr || !finite_pos(x, y, z)) {
        return fail(CN_ERR_ARG, "bad move_to");
    }
    a->desired[0] = x;
    a->desired[1] = y;
    a->desired[2] = z;
    a->goal_radius = goal_radius;
    a->speed_multiplier = speed;
    if (!a->has_goal) {
        a->has_goal = true;
        a->status = CN_AGENT_SEARCHING;
    }
    view_of(a, view);
    return CN_OK;
}

int32_t cn_ground_agent_stop(cn_ground_agent* a, cn_world* w, cn_apply* out, cn_agent_view* view) {
    if (a == nullptr) return fail(CN_ERR_ARG, "null agent");
    a->has_goal = false;
    clear_path(a);
    cancel_pending(a, w);
    a->stuck_timer = 0.0f;
    a->status = CN_AGENT_IDLE;
    if (out != nullptr) {
        *out = cn_apply{};
        stop_moving(out);
    }
    view_of(a, view);
    return CN_OK;
}

int32_t cn_ground_agent_replan(cn_ground_agent* a, cn_world* w, cn_agent_view* view) {
    if (a == nullptr) return fail(CN_ERR_ARG, "null agent");
    clear_path(a);
    cancel_pending(a, w);
    a->search_cooldown = 0.0f;
    view_of(a, view);
    return CN_OK;
}

int32_t cn_ground_agent_tick(cn_ground_agent* a, cn_steering* st, cn_world* w, cp_world* physics, const cn_body* body,
                             float dt, cn_apply* out, cn_agent_view* view) {
    if (a == nullptr || st == nullptr || out == nullptr || !valid_body(body) || !std::isfinite(dt)) {
        return fail(CN_ERR_ARG, "bad ground agent tick");
    }
    *out = cn_apply{};
    Body b{body, body->yaw, (body->flags & CN_BODY_ON_GROUND) != 0u, (body->flags & CN_BODY_IN_WATER) != 0u, out,
           a->origin};
    if (physics != nullptr) cenda::physics_detail::begin(physics);

    steering_tick(st, b.on_ground, dt);
    a->search_cooldown = std::max(0.0f, a->search_cooldown - dt);

    collect(a, w);
    if (a->has_goal) {
        request_if_needed(a, w, *body);
        follow(a, st, physics, b, dt);
        update_stuck(a, *body, dt);
    }
    view_of(a, view);
    return CN_OK;
}

int32_t cn_ground_agent_path(const cn_ground_agent* a, double* xyz, int32_t cap, int32_t* out_complete) {
    if (a == nullptr) return 0;
    return copy_path(a->path, xyz, cap, out_complete);
}

} // extern "C"

extern "C" {

cn_steering* cn_steering_create(const cn_steering_desc* desc) {
    if (desc == nullptr || !std::isfinite(desc->rotation_speed)) {
        fail(CN_ERR_ARG, "bad steering description");
        return nullptr;
    }
    auto* st = new (std::nothrow) cn_steering();
    if (st == nullptr) {
        fail(CN_ERR_ARG, "out of memory");
        return nullptr;
    }
    st->d = *desc;
    return st;
}

void cn_steering_destroy(cn_steering* steering) {
    delete steering;
}

int32_t cn_steering_tick(cn_steering* st, int32_t on_ground, float dt) {
    if (st == nullptr || !std::isfinite(dt)) return fail(CN_ERR_ARG, "bad steering tick");
    steering_tick(st, on_ground != 0, dt);
    return CN_OK;
}

int32_t cn_steering_steer(cn_steering* st, cp_world* physics, const cn_body* body, float dir_x, float dir_z,
                          float speed_multiplier, float dt, int32_t allow_jump, cn_apply* out) {
    if (st == nullptr || out == nullptr || !valid_body(body) || !std::isfinite(dir_x) || !std::isfinite(dir_z)) {
        return fail(CN_ERR_ARG, "bad steer");
    }
    *out = cn_apply{};
    Body b{body, body->yaw, (body->flags & CN_BODY_ON_GROUND) != 0u, (body->flags & CN_BODY_IN_WATER) != 0u, out,
           physics_frame(physics)};
    if (physics != nullptr) cenda::physics_detail::begin(physics);
    steer_along(st, physics, b, dir_x, dir_z, speed_multiplier, dt, allow_jump != 0);
    return CN_OK;
}

int32_t cn_steering_request_jump(cn_steering* st, const cn_body* body, cn_apply* out) {
    if (st == nullptr || out == nullptr || body == nullptr) return fail(CN_ERR_ARG, "bad jump request");
    *out = cn_apply{};
    Body b{body, body->yaw, (body->flags & CN_BODY_ON_GROUND) != 0u, (body->flags & CN_BODY_IN_WATER) != 0u, out,
           Node{}};
    return request_jump(st, b) ? 1 : 0;
}

} // extern "C"

// ================================================================================================
// Air agent (AirPathAgent)
// ================================================================================================

struct cn_air_agent {
    cn_air_agent_desc d{};
    Node origin{}; // the agent's frame
    double destination[3]{};
    bool has_goal = false;
    double cruise = 0.0;

    Path path;
    int32_t cursor = 0;
    int32_t path_version = 0;
    Pending pending;

    float search_cooldown = 0.0f;
    double plan_origin[3]{};
    int32_t status = CN_AGENT_IDLE;
};

namespace {

void view_of(const cn_air_agent* a, cn_agent_view* view) {
    fill_view(view, a->status, a->cursor, a->has_goal, a->path_version, a->destination, a->path.size());
}

void clear_path(cn_air_agent* a) {
    if (!a->path.empty() || a->cursor != 0) {
        a->path.points.clear();
        a->path.complete = false;
        a->path_version++;
    }
    a->cursor = 0;
}

void cancel_pending(cn_air_agent* a, cn_world* w) {
    if (a->pending.id != 0) {
        if (same_world(w, a->pending)) release(w, a->pending.id);
        a->pending = Pending{};
    }
}

// Where a search aims: the destination when it is within plan range, else the horizon along the
// bearing at cruise altitude.
void horizon_goal(const cn_air_agent* a, double x, double y, double z, double out[3]) {
    float dx = static_cast<float>(a->destination[0] - x);
    float dz = static_cast<float>(a->destination[2] - z);
    float distance = static_cast<float>(std::sqrt(static_cast<double>(dx * dx + dz * dz)));
    if (distance <= a->d.plan_range) {
        out[0] = a->destination[0];
        out[1] = a->destination[1];
        out[2] = a->destination[2];
        return;
    }
    if (distance < 1e-4f) {
        out[0] = x;
        out[1] = a->cruise;
        out[2] = z;
        return;
    }
    out[0] = x + static_cast<double>(dx / distance * a->d.plan_range);
    out[1] = a->cruise;
    out[2] = z + static_cast<double>(dz / distance * a->d.plan_range);
    (void) y;
}

void advance_cursor(cn_air_agent* a, double x, double y, double z) {
    while (a->cursor < a->path.size()
           && distance3(x, y, z, a->path.x(a->cursor), a->path.y(a->cursor), a->path.z(a->cursor))
               < a->d.waypoint_radius) {
        a->cursor++;
    }
    if (a->cursor >= a->path.size() && a->status == CN_AGENT_FOLLOWING) {
        a->status = CN_AGENT_NO_PATH; // route spent; the bearing carries on until the next one lands
    }
}

void collect(cn_air_agent* a, cn_world* w, double x, double y, double z) {
    if (a->pending.id == 0) return;
    if (!same_world(w, a->pending)) {
        cancel_pending(a, w);
        return;
    }
    Request* r = finished(w, a->pending.id);
    if (r == nullptr) return;
    Path result = path_in_frame(std::move(r->route), a->origin);
    release(w, a->pending.id);
    a->pending = Pending{};

    if (!a->has_goal) return;
    if (result.empty()) {
        // Nothing found. The bearing is still a good direction to fly meanwhile.
        a->status = CN_AGENT_NO_PATH;
        a->search_cooldown = std::max(a->search_cooldown, a->d.no_path_retry);
        return;
    }
    a->path = std::move(result);
    a->path_version++;
    a->cursor = 0;
    a->plan_origin[0] = x;
    a->plan_origin[1] = y;
    a->plan_origin[2] = z;
    // The route starts where the flyer asked from; drop the waypoints already passed.
    advance_cursor(a, x, y, z);
    a->status = a->cursor < a->path.size() ? CN_AGENT_FOLLOWING : CN_AGENT_NO_PATH;
}

void request_if_needed(cn_air_agent* a, cn_world* w, double x, double y, double z) {
    if (a->pending.id != 0 || a->search_cooldown > 0.0f) return;
    bool needs = a->path.empty() || a->cursor >= a->path.size()
        || distance3(x, y, z, a->plan_origin[0], a->plan_origin[1], a->plan_origin[2]) > a->d.replan_travel
        || !a->path.complete;
    if (!needs) return;
    if (w == nullptr) {
        a->status = CN_AGENT_NO_PATH;
        a->search_cooldown = a->d.no_path_retry;
        return;
    }
    double horizon[3];
    horizon_goal(a, x, y, z, horizon);
    cn_air_profile profile = a->d.profile;
    profile.cruise_y = a->cruise;
    double from[3] = {x, y, z};
    int64_t id = submit_air(w, from, horizon, a->origin, a->d.goal_radius, a->d.goal_snap_cells, profile,
                            a->d.limits);
    if (id == 0) {
        a->search_cooldown = a->d.saturated_retry;
        return;
    }
    a->pending.id = id;
    a->pending.world_serial = w->serial;
    a->search_cooldown = a->d.replan_interval;
    if (a->path.empty()) a->status = CN_AGENT_SEARCHING;
}

} // namespace

extern "C" {

cn_air_agent* cn_air_agent_create(const cn_air_agent_desc* desc, const int64_t* origin, double x, double y,
                                  double z) {
    if (desc == nullptr || desc->profile.cell_size <= 0 || desc->limits.max_expansions <= 0
        || !(desc->limits.max_cost > 0.0f) || !(desc->limits.heuristic_weight >= 1.0f)) {
        fail(CN_ERR_ARG, "bad air agent description");
        return nullptr;
    }
    auto* a = new (std::nothrow) cn_air_agent();
    if (a == nullptr) {
        fail(CN_ERR_ARG, "out of memory");
        return nullptr;
    }
    a->d = *desc;
    if (origin != nullptr) a->origin = Node{origin[0], origin[1], origin[2]};
    a->cruise = y;
    (void) x;
    (void) z;
    return a;
}

void cn_air_agent_destroy(cn_air_agent* agent) {
    delete agent;
}

int32_t cn_air_agent_set_origin(cn_air_agent* a, int64_t ox, int64_t oy, int64_t oz) {
    if (a == nullptr) return fail(CN_ERR_ARG, "null agent");
    const Node next{ox, oy, oz};
    double d[3];
    frame_delta(a->origin, next, d);
    for (int i = 0; i < 3; ++i) {
        a->destination[i] += d[i];
        a->plan_origin[i] += d[i];
    }
    a->cruise += d[1];
    a->path.shift(d);
    a->origin = next;
    return CN_OK;
}

void cn_air_agent_set_cruise(cn_air_agent* a, double cruise_y) {
    if (a != nullptr && std::isfinite(cruise_y)) a->cruise = cruise_y;
}

int32_t cn_air_agent_move_to(cn_air_agent* a, double x, double y, double z, cn_agent_view* view) {
    if (a == nullptr || !finite_pos(x, y, z)) {
        return fail(CN_ERR_ARG, "bad move_to");
    }
    if (!a->has_goal) {
        a->has_goal = true;
        a->status = CN_AGENT_SEARCHING;
        a->search_cooldown = 0.0f;
    }
    a->destination[0] = x;
    a->destination[1] = y;
    a->destination[2] = z;
    view_of(a, view);
    return CN_OK;
}

int32_t cn_air_agent_stop(cn_air_agent* a, cn_world* w, cn_agent_view* view) {
    if (a == nullptr) return fail(CN_ERR_ARG, "null agent");
    a->has_goal = false;
    clear_path(a);
    cancel_pending(a, w);
    a->status = CN_AGENT_IDLE;
    view_of(a, view);
    return CN_OK;
}

int32_t cn_air_agent_replan(cn_air_agent* a, cn_world* w, cn_agent_view* view) {
    if (a == nullptr) return fail(CN_ERR_ARG, "null agent");
    clear_path(a);
    cancel_pending(a, w);
    a->search_cooldown = 0.0f;
    view_of(a, view);
    return CN_OK;
}

int32_t cn_air_agent_tick(cn_air_agent* a, cn_world* w, double x, double y, double z, float dt, cn_agent_view* view) {
    if (a == nullptr || !finite_pos(x, y, z) || !std::isfinite(dt)) {
        return fail(CN_ERR_ARG, "bad air agent tick");
    }
    a->search_cooldown = std::max(0.0f, a->search_cooldown - dt);
    collect(a, w, x, y, z);
    if (a->has_goal) {
        advance_cursor(a, x, y, z);
        request_if_needed(a, w, x, y, z);
    }
    view_of(a, view);
    return CN_OK;
}

int32_t cn_air_agent_steer_target(const cn_air_agent* a, double x, double y, double z, double* out) {
    if (a == nullptr || out == nullptr) return fail(CN_ERR_ARG, "null agent or output");
    if (a->cursor < a->path.size()) {
        out[0] = a->path.x(a->cursor);
        out[1] = a->path.y(a->cursor);
        out[2] = a->path.z(a->cursor);
    } else {
        horizon_goal(a, x, y, z, out);
    }
    return CN_OK;
}

int32_t cn_air_agent_path(const cn_air_agent* a, double* xyz, int32_t cap, int32_t* out_complete) {
    if (a == nullptr) return 0;
    return copy_path(a->path, xyz, cap, out_complete);
}

} // extern "C"
