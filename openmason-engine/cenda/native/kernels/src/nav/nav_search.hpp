// Wayfind in C++: A* plus the ground and air domains, over cells read from the physics cache.
// Engine-internal (the ABI is include/cenda/nav.h). A port of the engine's Java Wayfind
// (com.openmason.engine.wayfind.AStar, wayfind.voxel.{GroundNavDomain, AirNavDomain, NavCellCache,
// NavNodes, PathSmoother}) — same float arithmetic, same successor order, same tie-breaks — so a
// route found here is the route the Java core finds over the same cells (pinned by
// NavParityTest in engine-voxel).
#pragma once

#include "cenda/nav.h"
#include "../physics/physics_internal.hpp"

#include <cmath>
#include <cstdint>
#include <vector>

namespace cenda::nav {

// ---- node keys (NavNodes) ---------------------------------------------------------------------
// X in the top 26 bits, Y in the middle 12, Z in the low 26; X and Z signed, Y unsigned.
constexpr int kYBits = 12;
constexpr int kZBits = 26;
constexpr int kXBits = 26;
constexpr int kYShift = kZBits;
constexpr int kXShift = kZBits + kYBits;
constexpr int64_t kZMask = (int64_t{1} << kZBits) - 1;
constexpr int64_t kYMask = (int64_t{1} << kYBits) - 1;
constexpr int32_t kMinHorizontal = -(1 << (kXBits - 1));
constexpr int32_t kMaxHorizontal = (1 << (kXBits - 1)) - 1;
constexpr int32_t kMaxY = (1 << kYBits) - 1;
constexpr int64_t kNoNode = INT64_MIN;

inline bool in_range(int32_t x, int32_t y, int32_t z) {
    return x >= kMinHorizontal && x <= kMaxHorizontal && z >= kMinHorizontal && z <= kMaxHorizontal
        && y >= 0 && y <= kMaxY;
}
inline int64_t pack(int32_t x, int32_t y, int32_t z) {
    return static_cast<int64_t>((static_cast<uint64_t>(static_cast<int64_t>(x)) << kXShift)
                                | (static_cast<uint64_t>(static_cast<int64_t>(y)) << kYShift)
                                | (static_cast<uint64_t>(z) & static_cast<uint64_t>(kZMask)));
}
inline int32_t node_x(int64_t n) { return static_cast<int32_t>(n >> kXShift); }
inline int32_t node_y(int64_t n) { return static_cast<int32_t>((static_cast<uint64_t>(n) >> kYShift) & kYMask); }
inline int32_t node_z(int64_t n) {
    return static_cast<int32_t>(static_cast<int64_t>(static_cast<uint64_t>(n) << (64 - kZBits)) >> (64 - kZBits));
}

// Java's Math.round(float): the closest int, ties toward positive infinity.
inline int32_t java_round(float a) {
    return static_cast<int32_t>(std::floor(static_cast<double>(a) + 0.5));
}
inline int32_t floor_div(int32_t a, int32_t b) {
    int32_t q = a / b;
    if ((a % b != 0) && ((a < 0) != (b < 0))) --q;
    return q;
}

// ---- a long -> int map with O(1) clear --------------------------------------------------------
class LongIntMap {
public:
    explicit LongIntMap(size_t capacity = 2048);
    int32_t get(int64_t key, int32_t missing) const;
    void put(int64_t key, int32_t value);
    void clear();
    int32_t size() const { return size_; }

private:
    void grow();
    std::vector<int64_t> keys_;
    std::vector<int32_t> values_;
    std::vector<uint32_t> stamps_;
    uint32_t stamp_ = 1;
    size_t mask_ = 0;
    int32_t size_ = 0;
};

// ---- the cell source ----------------------------------------------------------------------------
struct CellRules {
    cp_world* physics = nullptr;
    uint32_t solid_mask = 0;
    uint32_t hazard_mask = 0;
    uint32_t fluid_mask = 0;
    int32_t min_y = 0;
    int32_t max_y = 0;
};

// One cell's flags (CN_CELL_*) and its unquantised top.
uint32_t classify(const CellRules& rules, physics_detail::CellView& view, int32_t x, int32_t y, int32_t z,
                  float* top);

// Per-search memo of (flags, quantised top) per cell (NavCellCache).
class CellCache {
public:
    void reset(const CellRules* rules, physics_detail::CellView* view);
    uint32_t flags(int32_t x, int32_t y, int32_t z) { return static_cast<uint32_t>(sample(x, y, z) & 0xFF); }
    float top(int32_t x, int32_t y, int32_t z) {
        return static_cast<float>(static_cast<uint32_t>(sample(x, y, z)) >> 8) / 1024.0f;
    }
    int32_t probed() const { return cells_.size(); }

private:
    int32_t sample(int32_t x, int32_t y, int32_t z);
    const CellRules* rules_ = nullptr;
    physics_detail::CellView* view_ = nullptr;
    LongIntMap cells_{2048};
};

// ---- results ----------------------------------------------------------------------------------
struct Route {
    int32_t status = CN_SEARCH_NO_PATH;
    bool complete = false;
    int32_t expansions = 0;
    float cost = 0.0f;
    std::vector<float> points; // xyz per waypoint
};

struct GroundJob {
    int32_t start[3];
    int32_t goal[3];
    float goal_radius;
    int32_t snap_down;
    int32_t snap_up;
    cn_ground_profile profile;
    cn_limits limits;
};

struct AirJob {
    int32_t start[3];
    int32_t goal[3];
    float goal_radius;
    int32_t goal_snap_cells;
    cn_air_profile profile;
    cn_limits limits;
};

// Air cell range check (AirNavDomain.cellInRange).
bool air_cell_in_range(const cn_air_profile& profile, int32_t bx, int32_t by, int32_t bz);

// A reusable searcher (buffers stay warm between searches). Owner thread.
class Searcher {
public:
    Searcher();
    ~Searcher();
    Searcher(const Searcher&) = delete;
    Searcher& operator=(const Searcher&) = delete;

    Route ground(const CellRules& rules, physics_detail::CellView& view, const GroundJob& job);
    Route air(const CellRules& rules, physics_detail::CellView& view, const AirJob& job);

    struct Impl;

private:
    Impl* impl_;
};

} // namespace cenda::nav
