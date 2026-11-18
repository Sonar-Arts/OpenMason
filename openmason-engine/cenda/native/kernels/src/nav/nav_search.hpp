// Wayfind in C++: A* plus the ground and air domains, over cells read from the physics cache.
// Engine-internal (the ABI is include/cenda/nav.h). A port of the engine's retired Java Wayfind (AStar,
// GroundNavDomain, AirNavDomain, NavCellCache, NavNodes, PathSmoother) — same float arithmetic, same
// successor order, same tie-breaks — pinned route for route against what that core produced by
// engine-core NavGoldenRoutesTest, and rule by rule by NavRulesTest.
#pragma once

#include "cenda/nav.h"
#include "../physics/physics_internal.hpp"
#include "../int3_key.hpp"

#include <cmath>
#include <cstdint>
#include <vector>

namespace cenda::nav {

// ---- nodes (NavNodes) -------------------------------------------------------------------------
// A node is a full int64 triple (OpenMason #142: no packed keys; Y is signed and unbounded). Air nodes
// are cube indices (block / cell_size, floored), ground nodes cells.
using Node = cenda::Int3;
// Coordinates beyond +-2^62 are out of range (neighbour arithmetic and cell * size must not overflow).
constexpr int64_t kCoordLimit = int64_t{1} << 62;
inline constexpr Node kNoNode{INT64_MIN, INT64_MIN, INT64_MIN};

inline bool in_range(int64_t x, int64_t y, int64_t z) {
    return x > -kCoordLimit && x < kCoordLimit && y > -kCoordLimit && y < kCoordLimit && z > -kCoordLimit
        && z < kCoordLimit;
}

// Java's Math.round(float): the closest int, ties toward positive infinity.
inline int32_t java_round(float a) {
    return static_cast<int32_t>(std::floor(static_cast<double>(a) + 0.5));
}

// An integral anchor near v on a 4096-block grid (0 for |v| < 2048). Searches do their float math
// (surfaces, costs, waypoints) relative to an anchor near their start, so it is the Java Wayfind's
// arithmetic exactly near the frame origin and keeps full precision at any distance from it.
inline int64_t anchor_of(double v) {
    return static_cast<int64_t>(std::floor(v / 4096.0 + 0.5)) * 4096;
}

// ---- an Int3 -> int map with O(1) clear (open addressing, linear probing) ----------------------
// Keys are full int64 triples, always compared in full (nothing aliases). One 32-byte slot per entry
// (key, value, stamp together: a probe touches one cache line). The hash is the hottest arithmetic in
// a search (tens of lookups per expansion), so it is the short form: each axis times its own odd
// constant (independent multiplies), then one multiply-xorshift finalisation round (slot_hash).
class CellIntMap {
public:
    explicit CellIntMap(size_t capacity = 2048);
    int32_t get(const Node& key, int32_t missing) const {
        size_t i = slot_hash(key) & mask_;
        while (true) {
            const Slot& s = slots_[i];
            if (s.stamp != stamp_) return missing;
            if (s.key == key) return s.value;
            i = (i + 1) & mask_;
        }
    }
    void put(const Node& key, int32_t value);
    void clear();
    int32_t size() const { return size_; }

    static size_t slot_hash(const Node& k) {
        // Chained polynomial (see int3_key.hpp hash3: an XOR of per-axis products collides near negative
        // corners), then a light mix - the nav tables are hot.
        constexpr uint64_t kx = 0x9e3779b97f4a7c15u, ky = 0xc2b2ae3d27d4eb4fu;
        constexpr uint64_t kf = 0xff51afd7ed558ccdu;
        uint64_t h = (static_cast<uint64_t>(k.x) * kx + static_cast<uint64_t>(k.y)) * ky
            + static_cast<uint64_t>(k.z);
        h ^= h >> 32;
        h *= kf;
        h ^= h >> 29;
        return static_cast<size_t>(h);
    }

private:
    struct Slot {
        Node key;
        int32_t value;
        uint32_t stamp;
    };
    void grow();
    std::vector<Slot> slots_;
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
    int64_t min_y = 0; // absolute; CN_Y_UNBOUNDED_MIN for none
    int64_t max_y = 0; // absolute, exclusive; CN_Y_UNBOUNDED_MAX for none
};

// One (absolute) cell's flags (CN_CELL_*) and its unquantised top.
uint32_t classify(const CellRules& rules, physics_detail::CellView& view, int64_t x, int64_t y, int64_t z,
                  float* top);

// Per-search memo of (flags, quantised top) per cell (NavCellCache).
class CellCache {
public:
    void reset(const CellRules* rules, physics_detail::CellView* view);
    uint32_t flags(int64_t x, int64_t y, int64_t z) { return static_cast<uint32_t>(sample(x, y, z) & 0xFF); }
    float top(int64_t x, int64_t y, int64_t z) {
        return static_cast<float>(static_cast<uint32_t>(sample(x, y, z)) >> 8) / 1024.0f;
    }
    int32_t probed() const { return cells_.size(); }

private:
    int32_t sample(int64_t x, int64_t y, int64_t z);
    const CellRules* rules_ = nullptr;
    physics_detail::CellView* view_ = nullptr;
    CellIntMap cells_{2048};
};

// ---- results ----------------------------------------------------------------------------------
// Waypoints are stored relative to an absolute anchor cell (the search's), so a result stays right
// whatever frame its reader is in: frame position = (anchor - frame origin) + point.
struct Route {
    int32_t status = CN_SEARCH_NO_PATH;
    bool complete = false;
    int32_t expansions = 0;
    float cost = 0.0f;
    Node anchor{};
    std::vector<double> points; // xyz per waypoint, relative to anchor
};

struct GroundJob {
    Node start;   // absolute cells
    Node goal;
    Node anchor;  // the float math's origin (absolute)
    float goal_radius;
    int32_t snap_down;
    int32_t snap_up;
    cn_ground_profile profile;
    cn_limits limits;
};

struct AirJob {
    Node start;   // absolute blocks
    Node goal;
    Node anchor;  // the float math's origin, in CUBE cells (absolute)
    float cruise_cells; // cruise_y in cube cells, relative to anchor.y
    float goal_radius;
    int32_t goal_snap_cells;
    cn_air_profile profile;
    cn_limits limits;
};

// Air cell range check (AirNavDomain.cellInRange): the block's cube is a valid node.
bool air_cell_in_range(const cn_air_profile& profile, int64_t bx, int64_t by, int64_t bz);

// A reusable searcher (buffers stay warm between searches). Owner thread.
class Searcher {
public:
    Searcher();
    ~Searcher();
    Searcher(const Searcher&) = delete;
    Searcher& operator=(const Searcher&) = delete;

    Route ground(const CellRules& rules, physics_detail::CellView& view, const GroundJob& job);
    // Every cell reachable from the (snapped) start, cheapest first; see cn_reach_ground. Surfaces are
    // written relative to frame_y.
    int32_t reach(const CellRules& rules, physics_detail::CellView& view, const Node& start, const Node& anchor,
                  int64_t frame_y, int32_t snap_down, int32_t snap_up, int32_t radius, int32_t max_cells,
                  float max_cost, const cn_ground_profile& profile, cn_reach_cell* out, int32_t cap);
    Route air(const CellRules& rules, physics_detail::CellView& view, const AirJob& job);

    struct Impl;

private:
    Impl* impl_;
};

} // namespace cenda::nav
