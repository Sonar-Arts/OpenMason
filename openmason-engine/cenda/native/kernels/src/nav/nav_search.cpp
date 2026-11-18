// Wayfind in C++ (see nav_search.hpp). Each part names the retired Java class it ports; the arithmetic is
// written in the same order and the same float/int types so results match bit for bit (the library
// builds with -ffp-contract=off: no fused multiply-adds).

#include "nav_search.hpp"

#include <algorithm>
#include <cmath>
#include <limits>

namespace cenda::nav {

// ---- CellIntMap -------------------------------------------------------------------------------

CellIntMap::CellIntMap(size_t capacity) {
    size_t cap = 16;
    while (cap < capacity * 2) cap <<= 1;
    slots_.assign(cap, Slot{Node{}, 0, 0u});
    mask_ = cap - 1;
}

void CellIntMap::put(const Node& key, int32_t value) {
    if (static_cast<size_t>(size_ + 1) * 2 > slots_.size()) grow();
    size_t i = slot_hash(key) & mask_;
    while (slots_[i].stamp == stamp_) {
        if (slots_[i].key == key) {
            slots_[i].value = value;
            return;
        }
        i = (i + 1) & mask_;
    }
    slots_[i] = Slot{key, value, stamp_};
    ++size_;
}

void CellIntMap::clear() {
    size_ = 0;
    if (++stamp_ == 0) { // wrapped: stale stamps could read as live
        for (Slot& s : slots_) s.stamp = 0u;
        stamp_ = 1;
    }
}

void CellIntMap::grow() {
    std::vector<Slot> live;
    live.reserve(static_cast<size_t>(size_));
    for (const Slot& s : slots_) {
        if (s.stamp == stamp_) live.push_back(s);
    }
    size_t cap = slots_.size() << 1;
    slots_.assign(cap, Slot{Node{}, 0, 0u});
    stamp_ = 1;
    mask_ = cap - 1;
    size_ = 0;
    for (const Slot& s : live) put(s.key, s.value);
}

// ---- cells ------------------------------------------------------------------------------------

uint32_t classify(const CellRules& rules, physics_detail::CellView& view, int64_t x, int64_t y, int64_t z,
                  float* top) {
    *top = 0.0f;
    if (y < rules.min_y || y >= rules.max_y) return CN_CELL_UNKNOWN;
    uint16_t shape = 0, fluid = 0;
    if (!view.read(x, y, z, shape, fluid)) return CN_CELL_UNKNOWN;
    uint32_t flags = 0;
    if (rules.hazard_mask != 0u && physics_detail::shape_top(rules.physics, shape, rules.hazard_mask) > 0.0f) {
        flags |= CN_CELL_HAZARD;
    }
    float solid = physics_detail::shape_top(rules.physics, shape, rules.solid_mask);
    if (solid > 0.0f) {
        flags |= CN_CELL_SOLID;
        *top = solid;
    }
    float surface = physics_detail::fluid_surface(rules.physics, fluid, rules.fluid_mask);
    if (surface >= 0.0f) {
        flags |= CN_CELL_LIQUID;
        if ((flags & CN_CELL_SOLID) == 0u) *top = surface;
    }
    return flags;
}

void CellCache::reset(const CellRules* rules, physics_detail::CellView* view) {
    rules_ = rules;
    view_ = view;
    cells_.clear();
}

int32_t CellCache::sample(int64_t x, int64_t y, int64_t z) {
    if (!in_range(x, y, z)) return static_cast<int32_t>(CN_CELL_UNKNOWN);
    const Node key{x, y, z};
    int32_t packed = cells_.get(key, -1);
    if (packed == -1) {
        float top = 0.0f;
        uint32_t flags = classify(*rules_, *view_, x, y, z, &top);
        // Liquids carry a surface height too: it is where a swimmer floats.
        bool has_surface = (flags & (CN_CELL_SOLID | CN_CELL_LIQUID)) != 0u;
        float surface = has_surface ? top : 0.0f;
        int32_t quantised = java_round(std::min(1.0f, std::max(0.0f, surface)) * 1024.0f);
        packed = static_cast<int32_t>(flags & 0xFFu) | (quantised << 8);
        cells_.put(key, packed);
    }
    return packed;
}

bool air_cell_in_range(const cn_air_profile& profile, int64_t bx, int64_t by, int64_t bz) {
    // Every block is in some cube; the block itself must be in range (cube * size cannot overflow).
    (void) profile;
    return in_range(bx, by, bz);
}

namespace {

constexpr float kDiagonalCost = 1.4142135f;
constexpr float kEpsilon = 1e-3f;
constexpr float kRelaxEpsilon = 1e-4f;
constexpr int32_t kNoParent = -1;
constexpr int32_t kNotInHeap = -1;
constexpr int32_t kUnknownNode = -1;

inline bool is_unknown(uint32_t f) { return (f & CN_CELL_UNKNOWN) != 0u; }
inline bool is_solid(uint32_t f) { return (f & CN_CELL_SOLID) != 0u; }
inline bool is_liquid(uint32_t f) { return (f & CN_CELL_LIQUID) != 0u; }
inline bool is_hazard(uint32_t f) { return (f & CN_CELL_HAZARD) != 0u; }

// ---- GroundNavDomain ----------------------------------------------------------------------------

constexpr int32_t kGroundDx[8] = {1, -1, 0, 0, 1, 1, -1, -1};
constexpr int32_t kGroundDz[8] = {0, 0, 1, -1, 1, -1, 1, -1};

class GroundDomain {
public:
    // anchor: the absolute cell the float math is relative to (surfaces are y - anchor.y + top).
    GroundDomain(CellCache& cells, const CellRules& rules, const cn_ground_profile& profile, const Node& goal,
                 float radius, const Node& anchor)
        : cells_(cells), rules_(rules), p_(profile), gx_(goal.x), gy_(goal.y), gz_(goal.z),
          radius_sq_(radius * radius), ay_(anchor.y) {}

    static constexpr int32_t max_successors() { return 8; }

    int32_t successors(const Node& node, Node* out_nodes, float* out_costs) {
        const int64_t x = node.x, y = node.y, z = node.z;
        float from_surface = stand_surface(x, y, z);
        if (std::isnan(from_surface)) return 0;
        bool from_liquid = liquid(x, y, z);
        int32_t count = 0;
        for (int dir = 0; dir < 8; ++dir) {
            int64_t dx = kGroundDx[dir], dz = kGroundDz[dir];
            int64_t nx = x + dx, nz = z + dz;
            if (!in_range(nx, y, nz)) continue;
            bool diagonal = dx != 0 && dz != 0;
            if (diagonal && !(columns_clear(nx, z, from_surface, 0) && columns_clear(x, nz, from_surface, 0))) {
                continue;
            }
            if (!resolve_landing(nx, nz, y, from_surface, from_liquid)) continue;
            float multiplier = landing_submerged_ ? p_.swim_cost : landing_wading_ ? p_.wade_cost : 1.0f;
            out_nodes[count] = Node{nx, landing_y_, nz};
            out_costs[count] = (diagonal ? kDiagonalCost : 1.0f) * multiplier + landing_surcharge_;
            ++count;
        }
        return count;
    }

    float heuristic(const Node& node) const {
        int64_t dx = node.x > gx_ ? node.x - gx_ : gx_ - node.x;
        int64_t dz = node.z > gz_ ? node.z - gz_ : gz_ - node.z;
        int64_t diagonal = std::min(dx, dz);
        return static_cast<float>(dx + dz - 2 * diagonal) + kDiagonalCost * static_cast<float>(diagonal);
    }

    bool is_goal(const Node& node) const {
        float dx = static_cast<float>(node.x - gx_);
        float dy = static_cast<float>(node.y - gy_);
        float dz = static_cast<float>(node.z - gz_);
        return dx * dx + dy * dy + dz * dz <= radius_sq_;
    }

    // The standing surface, relative to the anchor's y.
    float surface_of(const Node& node) { return stand_surface(node.x, node.y, node.z); }

    Node snap_to_surface(int64_t x, int64_t y, int64_t z, int32_t down, int32_t up) {
        if (in_range(x, y, z) && !std::isnan(stand_surface(x, y, z))) return Node{x, y, z};
        for (int32_t offset = 1; offset <= std::max(down, up); ++offset) {
            if (offset <= down) {
                int64_t cy = y - offset;
                if (in_range(x, cy, z) && !std::isnan(stand_surface(x, cy, z))) return Node{x, cy, z};
            }
            if (offset <= up) {
                int64_t cy = y + offset;
                if (in_range(x, cy, z) && !std::isnan(stand_surface(x, cy, z))) return Node{x, cy, z};
            }
        }
        return kNoNode;
    }

private:
    // y relative to the anchor, as the float the Java did its surface math in.
    float rel(int64_t y) const { return static_cast<float>(y - ay_); }

    bool resolve_landing(int64_t nx, int64_t nz, int64_t from_y, float from_surface, bool from_liquid) {
        float surface = stand_surface(nx, from_y, nz);
        if (!std::isnan(surface) && accept_landing(nx, from_y, nz, surface, from_surface, from_liquid)) {
            return true;
        }
        float reach = from_liquid ? std::max(p_.max_climb, p_.water_escape_climb) : p_.max_climb;
        // Cells outside [min_y, max_y) are UNKNOWN (never standable): the scans stop at the bounds.
        for (int64_t cy = from_y + 1; rel(cy) - from_surface <= reach + kEpsilon; ++cy) {
            if (cy >= rules_.max_y || !in_range(nx, cy, nz)) break;
            surface = stand_surface(nx, cy, nz);
            if (!std::isnan(surface)) return accept_landing(nx, cy, nz, surface, from_surface, from_liquid);
        }
        for (int64_t cy = from_y - 1; from_surface - rel(cy + 1) <= p_.max_fall + kEpsilon; --cy) {
            if (cy < rules_.min_y || !in_range(nx, cy, nz)) break;
            surface = stand_surface(nx, cy, nz);
            if (!std::isnan(surface)) return accept_landing(nx, cy, nz, surface, from_surface, from_liquid);
        }
        return false;
    }

    bool accept_landing(int64_t x, int64_t y, int64_t z, float surface, float from_surface, bool from_liquid) {
        float delta = surface - from_surface;
        float climb_limit = from_liquid ? std::max(p_.max_climb, p_.water_escape_climb) : p_.max_climb;
        if (delta > climb_limit + kEpsilon) return false;
        if (-delta > p_.max_fall + kEpsilon) return false;
        float surcharge = 0.0f;
        if (delta > kEpsilon) {
            surcharge = delta > p_.max_step_up + kEpsilon ? p_.jump_cost : p_.step_cost;
        } else if (delta < -kEpsilon) {
            surcharge = p_.fall_cost_per_block * -delta;
        }
        landing_y_ = y;
        landing_surcharge_ = surcharge;
        landing_wading_ = liquid(x, y, z);
        landing_submerged_ = landing_wading_ && liquid(x, y - 1, z);
        return true;
    }

    float stand_surface(int64_t x, int64_t y, int64_t z) {
        uint32_t flags = cells_.flags(x, y, z);
        if (is_unknown(flags) || is_hazard(flags)) return std::numeric_limits<float>::quiet_NaN();
        float surface;
        if (is_solid(flags)) {
            float top = cells_.top(x, y, z);
            if (top >= 1.0f - kEpsilon) return std::numeric_limits<float>::quiet_NaN();
            surface = rel(y) + top;
        } else if (is_liquid(flags)) {
            if (liquid(x, y + 1, z)) return std::numeric_limits<float>::quiet_NaN();
            bool over_deep = liquid(x, y - 1, z);
            if (over_deep && p_.can_swim == 0) return std::numeric_limits<float>::quiet_NaN();
            if (!over_deep && p_.can_swim == 0 && !full_support_below(x, y, z)) {
                return std::numeric_limits<float>::quiet_NaN();
            }
            surface = rel(y) + std::min(1.0f, cells_.top(x, y, z));
        } else {
            if (!full_support_below(x, y, z)) return std::numeric_limits<float>::quiet_NaN();
            surface = rel(y);
        }
        return columns_clear(x, z, surface, p_.column_radius) ? surface : std::numeric_limits<float>::quiet_NaN();
    }

    bool full_support_below(int64_t x, int64_t y, int64_t z) {
        uint32_t below = cells_.flags(x, y - 1, z);
        return is_solid(below) && cells_.top(x, y - 1, z) >= 1.0f - kEpsilon;
    }

    bool columns_clear(int64_t x, int64_t z, float surface, int32_t radius) {
        int64_t lowest = static_cast<int64_t>(std::floor(surface + kEpsilon)) + ay_;
        int64_t highest = static_cast<int64_t>(std::floor(surface + p_.height - kEpsilon)) + ay_;
        for (int64_t cx = x - radius; cx <= x + radius; ++cx) {
            for (int64_t cz = z - radius; cz <= z + radius; ++cz) {
                for (int64_t cy = lowest; cy <= highest; ++cy) {
                    if (blocks_body(cx, cy, cz, surface)) return false;
                }
            }
        }
        return true;
    }

    bool blocks_body(int64_t x, int64_t y, int64_t z, float surface) {
        uint32_t flags = cells_.flags(x, y, z);
        if (is_unknown(flags) || is_hazard(flags)) return true;
        if (!is_solid(flags)) return false;
        return rel(y) + cells_.top(x, y, z) > surface + kEpsilon;
    }

    bool liquid(int64_t x, int64_t y, int64_t z) { return is_liquid(cells_.flags(x, y, z)); }

    CellCache& cells_;
    const CellRules& rules_;
    const cn_ground_profile& p_;
    int64_t gx_, gy_, gz_;
    float radius_sq_;
    int64_t ay_;
    int64_t landing_y_ = 0;
    float landing_surcharge_ = 0.0f;
    bool landing_wading_ = false;
    bool landing_submerged_ = false;
};

// ---- AirNavDomain -------------------------------------------------------------------------------

constexpr int32_t kUnclassified = 0;
constexpr int32_t kFlyable = 1;
constexpr int32_t kAirUnknown = 2;
constexpr int32_t kBlocked = 3;
constexpr float kLineSampleStep = 0.25f;

struct AirDirections {
    int32_t dx[26], dy[26], dz[26];
    float length[26];
    AirDirections() {
        int i = 0;
        for (int32_t x = -1; x <= 1; ++x) {
            for (int32_t y = -1; y <= 1; ++y) {
                for (int32_t z = -1; z <= 1; ++z) {
                    if (x == 0 && y == 0 && z == 0) continue;
                    dx[i] = x;
                    dy[i] = y;
                    dz[i] = z;
                    length[i] = static_cast<float>(std::sqrt(static_cast<double>(x * x + y * y + z * z)));
                    ++i;
                }
            }
        }
    }
};
const AirDirections kAir;

class AirDomain {
public:
    // anchor: absolute cube cell the float math is relative to; cruise_cells: cruise_y in cube cells
    // relative to anchor.y.
    AirDomain(CellCache& cells, CellIntMap& classified, const cn_air_profile& profile, const Node& goal_cell,
              float goal_radius, const Node& anchor, float cruise_cells)
        : cells_(cells), classified_(classified), p_(profile), goal_(goal_cell),
          goal_radius_cells_(std::max(0.0f, goal_radius) / static_cast<float>(profile.cell_size)),
          cruise_cell_y_(cruise_cells), anchor_(anchor) {}

    static Node cell_of(const cn_air_profile& p, int64_t bx, int64_t by, int64_t bz) {
        const int64_t size = p.cell_size;
        return Node{cenda::floor_div64(bx, size), cenda::floor_div64(by, size), cenda::floor_div64(bz, size)};
    }

    static constexpr int32_t max_successors() { return 26; }

    int32_t successors(const Node& node, Node* out_nodes, float* out_costs) {
        const int64_t x = node.x, y = node.y, z = node.z;
        int32_t count = 0;
        for (int dir = 0; dir < 26; ++dir) {
            int64_t dx = kAir.dx[dir], dy = kAir.dy[dir], dz = kAir.dz[dir];
            int64_t nx = x + dx, ny = y + dy, nz = z + dz;
            if (!in_range(nx, ny, nz)) continue;
            int32_t neighbour = classify_cell(nx, ny, nz);
            if (!passable(neighbour)) continue;
            if (!corner_clear(x, y, z, dx, dy, dz)) continue;
            float cost = kAir.length[dir];
            if (dy > 0) {
                cost += p_.climb_cost;
            } else if (dy < 0) {
                cost += p_.descend_cost;
            }
            cost += p_.altitude_cost * std::fabs(static_cast<float>(ny - anchor_.y) + 0.5f - cruise_cell_y_);
            if (neighbour == kAirUnknown) cost += p_.unknown_cost;
            out_nodes[count] = Node{nx, ny, nz};
            out_costs[count] = cost;
            ++count;
        }
        return count;
    }

    float heuristic(const Node& node) const {
        float dx = static_cast<float>(node.x - goal_.x);
        float dy = static_cast<float>(node.y - goal_.y);
        float dz = static_cast<float>(node.z - goal_.z);
        return static_cast<float>(std::sqrt(static_cast<double>(dx * dx + dy * dy + dz * dz)));
    }

    bool is_goal(const Node& node) const {
        if (node == goal_) return true;
        float dx = static_cast<float>(node.x - goal_.x);
        float dy = static_cast<float>(node.y - goal_.y);
        float dz = static_cast<float>(node.z - goal_.z);
        return dx * dx + dy * dy + dz * dz <= goal_radius_cells_ * goal_radius_cells_;
    }

    bool is_flyable(const Node& node) { return passable(classify_cell(node.x, node.y, node.z)); }

    Node snap_to_flyable(int64_t bx, int64_t by, int64_t bz, int32_t radius_cells) {
        if (!air_cell_in_range(p_, bx, by, bz)) return kNoNode;
        Node origin = cell_of(p_, bx, by, bz);
        if (is_flyable(origin)) return origin;
        const int64_t ox = origin.x, oy = origin.y, oz = origin.z;
        for (int32_t ring = 1; ring <= radius_cells; ++ring) {
            Node best = kNoNode;
            float best_distance = std::numeric_limits<float>::max();
            for (int32_t dx = -ring; dx <= ring; ++dx) {
                for (int32_t dy = -ring; dy <= ring; ++dy) {
                    for (int32_t dz = -ring; dz <= ring; ++dz) {
                        if (std::abs(dx) != ring && std::abs(dy) != ring && std::abs(dz) != ring) continue;
                        int64_t cx = ox + dx, cy = oy + dy, cz = oz + dz;
                        if (!in_range(cx, cy, cz) || !passable(classify_cell(cx, cy, cz))) continue;
                        float distance = static_cast<float>(dx * dx + dz * dz)
                            + 4.0f * static_cast<float>(dy) * static_cast<float>(dy);
                        if (distance < best_distance) {
                            best_distance = distance;
                            best = Node{cx, cy, cz};
                        }
                    }
                }
            }
            if (best != kNoNode) return best;
        }
        return kNoNode;
    }

    std::vector<Node> string_pull(const std::vector<Node>& nodes) {
        if (nodes.size() < 3) return nodes;
        std::vector<Node> kept;
        kept.reserve(nodes.size());
        kept.push_back(nodes[0]);
        size_t anchor = 0;
        while (anchor < nodes.size() - 1) {
            size_t furthest = anchor + 1;
            for (size_t candidate = nodes.size() - 1; candidate > anchor + 1; --candidate) {
                if (line_flyable(nodes[anchor], nodes[candidate])) {
                    furthest = candidate;
                    break;
                }
            }
            kept.push_back(nodes[furthest]);
            anchor = furthest;
        }
        return kept;
    }

private:
    bool line_flyable(const Node& from, const Node& to) {
        // Cube indices relative to the anchor, as the float the Java did this in.
        float x0 = static_cast<float>(from.x - anchor_.x);
        float y0 = static_cast<float>(from.y - anchor_.y);
        float z0 = static_cast<float>(from.z - anchor_.z);
        float dx = static_cast<float>(to.x - anchor_.x) - x0;
        float dy = static_cast<float>(to.y - anchor_.y) - y0;
        float dz = static_cast<float>(to.z - anchor_.z) - z0;
        float length = static_cast<float>(std::sqrt(static_cast<double>(dx * dx + dy * dy + dz * dz)));
        int32_t steps = std::max(1, static_cast<int32_t>(std::ceil(static_cast<double>(length / kLineSampleStep))));
        for (int32_t step = 0; step <= steps; ++step) {
            float t = static_cast<float>(step) / static_cast<float>(steps);
            int64_t cx = java_round(x0 + dx * t) + anchor_.x;
            int64_t cy = java_round(y0 + dy * t) + anchor_.y;
            int64_t cz = java_round(z0 + dz * t) + anchor_.z;
            if (!in_range(cx, cy, cz) || classify_cell(cx, cy, cz) != kFlyable) return false;
        }
        return true;
    }

    bool passable(int32_t c) const { return c == kFlyable || (c == kAirUnknown && p_.allow_unknown != 0); }

    bool corner_clear(int64_t x, int64_t y, int64_t z, int64_t dx, int64_t dy, int64_t dz) {
        int components = (dx != 0 ? 1 : 0) + (dy != 0 ? 1 : 0) + (dz != 0 ? 1 : 0);
        if (components < 2) return true;
        if (dx != 0 && !passable(classify_cell(x + dx, y, z))) return false;
        if (dy != 0 && !passable(classify_cell(x, y + dy, z))) return false;
        return dz == 0 || passable(classify_cell(x, y, z + dz));
    }

    int32_t classify_cell(int64_t cx, int64_t cy, int64_t cz) {
        const Node key{cx, cy, cz};
        int32_t cached = classified_.get(key, kUnclassified);
        if (cached != kUnclassified) return cached;
        int32_t c = probe(cx, cy, cz);
        classified_.put(key, c);
        return c;
    }

    int32_t probe(int64_t cx, int64_t cy, int64_t cz) {
        const int64_t size = p_.cell_size;
        // A cube cell beyond the range a block coordinate can hold is out of the world.
        if (cy <= -kCoordLimit / size || cy >= kCoordLimit / size || cx <= -kCoordLimit / size
            || cx >= kCoordLimit / size || cz <= -kCoordLimit / size || cz >= kCoordLimit / size) {
            return kBlocked;
        }
        int64_t base_y = cy * size;
        if (base_y < p_.min_y || base_y + size - 1 > p_.max_y) return kBlocked;
        int64_t base_x = cx * size;
        int64_t base_z = cz * size;
        bool saw_unknown = false;
        for (int64_t y = base_y; y < base_y + size; ++y) {
            for (int64_t x = base_x; x < base_x + size; ++x) {
                for (int64_t z = base_z; z < base_z + size; ++z) {
                    uint32_t flags = cells_.flags(x, y, z);
                    if (is_solid(flags) || is_hazard(flags) || is_liquid(flags)) return kBlocked;
                    if (is_unknown(flags)) saw_unknown = true;
                }
            }
        }
        return saw_unknown ? kAirUnknown : kFlyable;
    }

    CellCache& cells_;
    CellIntMap& classified_;
    const cn_air_profile& p_;
    Node goal_;
    float goal_radius_cells_;
    float cruise_cell_y_;
    Node anchor_;
};

// ---- PathSmoother -------------------------------------------------------------------------------

std::vector<Node> collapse_collinear(const std::vector<Node>& nodes) {
    if (nodes.size() < 3) return nodes;
    std::vector<Node> kept;
    kept.reserve(nodes.size());
    kept.push_back(nodes[0]);
    int64_t pdx = nodes[1].x - nodes[0].x;
    int64_t pdy = nodes[1].y - nodes[0].y;
    int64_t pdz = nodes[1].z - nodes[0].z;
    for (size_t i = 1; i < nodes.size() - 1; ++i) {
        int64_t dx = nodes[i + 1].x - nodes[i].x;
        int64_t dy = nodes[i + 1].y - nodes[i].y;
        int64_t dz = nodes[i + 1].z - nodes[i].z;
        if (dx != pdx || dy != pdy || dz != pdz) {
            kept.push_back(nodes[i]);
            pdx = dx;
            pdy = dy;
            pdz = dz;
        }
    }
    kept.push_back(nodes.back());
    return kept;
}

} // namespace

// ---- AStar --------------------------------------------------------------------------------------

struct Searcher::Impl {
    std::vector<Node> key;
    std::vector<float> g, h, f;
    std::vector<int32_t> parent, heap_pos, heap;
    int32_t node_count = 0;
    int32_t heap_size = 0;
    CellIntMap index{1024};
    Node succ_nodes[26];
    float succ_costs[26];

    CellCache cells;
    CellIntMap classified{2048};

    Impl() {
        key.resize(1024);
        g.resize(1024);
        h.resize(1024);
        f.resize(1024);
        parent.resize(1024);
        heap_pos.resize(1024);
        heap.resize(1024);
    }

    void reset() {
        node_count = 0;
        heap_size = 0;
        index.clear();
    }

    int32_t add_node(const Node& k, float gv, float hv, int32_t par, float weight) {
        int32_t id = node_count++;
        if (static_cast<size_t>(id) == key.size()) {
            size_t cap = key.size() << 1;
            key.resize(cap);
            g.resize(cap);
            h.resize(cap);
            f.resize(cap);
            parent.resize(cap);
            heap_pos.resize(cap);
        }
        key[static_cast<size_t>(id)] = k;
        g[static_cast<size_t>(id)] = gv;
        h[static_cast<size_t>(id)] = hv;
        f[static_cast<size_t>(id)] = gv + weight * hv;
        parent[static_cast<size_t>(id)] = par;
        heap_pos[static_cast<size_t>(id)] = kNotInHeap;
        index.put(k, id);
        return id;
    }

    bool precedes(int32_t a, int32_t b) const {
        const size_t ua = static_cast<size_t>(a), ub = static_cast<size_t>(b);
        if (f[ua] != f[ub]) return f[ua] < f[ub];
        if (h[ua] != h[ub]) return h[ua] < h[ub];
        return a < b;
    }

    void sift_up(int32_t position) {
        int32_t id = heap[static_cast<size_t>(position)];
        while (position > 0) {
            int32_t parent_pos = (position - 1) >> 1;
            int32_t parent_node = heap[static_cast<size_t>(parent_pos)];
            if (!precedes(id, parent_node)) break;
            heap[static_cast<size_t>(position)] = parent_node;
            heap_pos[static_cast<size_t>(parent_node)] = position;
            position = parent_pos;
        }
        heap[static_cast<size_t>(position)] = id;
        heap_pos[static_cast<size_t>(id)] = position;
    }

    void sift_down(int32_t position) {
        int32_t id = heap[static_cast<size_t>(position)];
        int32_t half = heap_size >> 1;
        while (position < half) {
            int32_t child_pos = (position << 1) + 1;
            int32_t right = child_pos + 1;
            int32_t child = heap[static_cast<size_t>(child_pos)];
            if (right < heap_size && precedes(heap[static_cast<size_t>(right)], child)) {
                child_pos = right;
                child = heap[static_cast<size_t>(child_pos)];
            }
            if (!precedes(child, id)) break;
            heap[static_cast<size_t>(position)] = child;
            heap_pos[static_cast<size_t>(child)] = position;
            position = child_pos;
        }
        heap[static_cast<size_t>(position)] = id;
        heap_pos[static_cast<size_t>(id)] = position;
    }

    void push(int32_t id) {
        if (static_cast<size_t>(heap_size) == heap.size()) heap.resize(heap.size() << 1);
        heap[static_cast<size_t>(heap_size)] = id;
        heap_pos[static_cast<size_t>(id)] = heap_size;
        sift_up(heap_size++);
    }

    int32_t pop() {
        int32_t top = heap[0];
        heap_pos[static_cast<size_t>(top)] = kNotInHeap;
        int32_t last = heap[static_cast<size_t>(--heap_size)];
        if (heap_size > 0) {
            heap[0] = last;
            heap_pos[static_cast<size_t>(last)] = 0;
            sift_down(0);
        }
        return top;
    }

    bool better_fallback(int32_t candidate, int32_t incumbent) const {
        const size_t c = static_cast<size_t>(candidate), i = static_cast<size_t>(incumbent);
        if (h[c] < h[i]) return true;
        return h[c] == h[i] && g[c] < g[i];
    }

    void reconstruct(int32_t end, std::vector<Node>& out) const {
        int32_t length = 0;
        for (int32_t id = end; id != kNoParent; id = parent[static_cast<size_t>(id)]) ++length;
        out.assign(static_cast<size_t>(length), Node{});
        int32_t i = length - 1;
        for (int32_t id = end; id != kNoParent; id = parent[static_cast<size_t>(id)], --i) {
            out[static_cast<size_t>(i)] = key[static_cast<size_t>(id)];
        }
    }

    // Returns the status; fills chain, cost, expansions.
    template <typename Domain>
    int32_t search(Domain& domain, const Node& start, const cn_limits& limits, std::vector<Node>& chain, float& cost,
                   int32_t& expansions_out) {
        reset();
        const float weight = limits.heuristic_weight;
        const int32_t start_id = add_node(start, 0.0f, domain.heuristic(start), kNoParent, weight);
        push(start_id);
        int32_t best = start_id;
        int32_t expansions = 0;
        chain.clear();
        cost = 0.0f;
        while (heap_size > 0) {
            if (expansions >= limits.max_expansions) {
                expansions_out = expansions;
                return partial(CN_SEARCH_PARTIAL_BUDGET, start_id, best, chain, cost);
            }
            int32_t current = pop();
            const Node current_key = key[static_cast<size_t>(current)];
            if (domain.is_goal(current_key)) {
                expansions_out = expansions;
                reconstruct(current, chain);
                cost = g[static_cast<size_t>(current)];
                return CN_SEARCH_FOUND;
            }
            ++expansions;
            int32_t count = domain.successors(current_key, succ_nodes, succ_costs);
            for (int32_t i = 0; i < count; ++i) {
                float tentative = g[static_cast<size_t>(current)] + succ_costs[i];
                if (tentative > limits.max_cost) continue;
                const Node sk = succ_nodes[i];
                int32_t successor = index.get(sk, kUnknownNode);
                if (successor == kUnknownNode) {
                    successor = add_node(sk, tentative, domain.heuristic(sk), current, weight);
                    push(successor);
                } else if (tentative + kRelaxEpsilon < g[static_cast<size_t>(successor)]) {
                    const size_t us = static_cast<size_t>(successor);
                    g[us] = tentative;
                    f[us] = tentative + weight * h[us];
                    parent[us] = current;
                    if (heap_pos[us] == kNotInHeap) {
                        push(successor);
                    } else {
                        sift_up(heap_pos[us]);
                    }
                } else {
                    continue;
                }
                if (better_fallback(successor, best)) best = successor;
            }
        }
        expansions_out = expansions;
        return partial(CN_SEARCH_PARTIAL_UNREACHABLE, start_id, best, chain, cost);
    }

    int32_t partial(int32_t status, int32_t start_id, int32_t best, std::vector<Node>& chain, float& cost) {
        if (best == start_id) {
            chain.clear();
            cost = 0.0f;
            return CN_SEARCH_NO_PATH;
        }
        reconstruct(best, chain);
        cost = g[static_cast<size_t>(best)];
        return status;
    }
};

Searcher::Searcher() : impl_(new Impl()) {}
Searcher::~Searcher() { delete impl_; }

Route Searcher::ground(const CellRules& rules, physics_detail::CellView& view, const GroundJob& job) {
    Route route;
    route.anchor = job.anchor;
    Impl& s = *impl_;
    s.cells.reset(&rules, &view);
    // The goal is wherever the walker was told to go — often mid-air or inside a block; snapping it
    // onto a real surface lets the search finish FOUND instead of grinding out a partial.
    GroundDomain snapper(s.cells, rules, job.profile, job.goal, job.goal_radius, job.anchor);
    Node start = snapper.snap_to_surface(job.start.x, job.start.y, job.start.z, job.snap_down, job.snap_up);
    if (start == kNoNode) {
        route.status = CN_SEARCH_NO_PATH;
        return route;
    }
    Node snapped = snapper.snap_to_surface(job.goal.x, job.goal.y, job.goal.z, job.snap_down, job.snap_up);
    GroundDomain snapped_domain(s.cells, rules, job.profile, snapped == kNoNode ? job.goal : snapped,
                                job.goal_radius, job.anchor);
    GroundDomain& domain = snapped == kNoNode ? snapper : snapped_domain;

    std::vector<Node> chain;
    route.status = s.search(domain, start, job.limits, chain, route.cost, route.expansions);
    route.complete = route.status == CN_SEARCH_FOUND;
    if (chain.empty()) return route;
    std::vector<Node> corners = collapse_collinear(chain);
    route.points.resize(corners.size() * 3);
    for (size_t i = 0; i < corners.size(); ++i) {
        const Node& node = corners[i];
        float surface = domain.surface_of(node);
        // Relative to the anchor: x + 0.5 is exact in double; the surface is the search's own float.
        route.points[i * 3] = static_cast<double>(node.x - job.anchor.x) + 0.5;
        route.points[i * 3 + 1] = std::isnan(surface) ? static_cast<double>(node.y - job.anchor.y)
                                                      : static_cast<double>(surface);
        route.points[i * 3 + 2] = static_cast<double>(node.z - job.anchor.z) + 0.5;
    }
    return route;
}

int32_t Searcher::reach(const CellRules& rules, physics_detail::CellView& view, const Node& start_cell,
                        const Node& anchor, int64_t frame_y, int32_t snap_down, int32_t snap_up, int32_t radius,
                        int32_t max_cells, float max_cost, const cn_ground_profile& profile, cn_reach_cell* out,
                        int32_t cap) {
    Impl& s = *impl_;
    s.cells.reset(&rules, &view);
    // The goal is irrelevant (heuristic 0, never a goal): a uniform-cost flood over the ground moves.
    GroundDomain domain(s.cells, rules, profile, start_cell, -1.0f, anchor);
    Node start = domain.snap_to_surface(start_cell.x, start_cell.y, start_cell.z, snap_down, snap_up);
    if (start == kNoNode) return 0;
    const int64_t sx = start.x, sz = start.z;
    const double surface_base = static_cast<double>(anchor.y - frame_y);

    s.reset();
    std::vector<int32_t> out_index; // node id -> settled index
    const int32_t first = s.add_node(start, 0.0f, 0.0f, kNoParent, 1.0f);
    s.push(first);
    int32_t settled = 0;
    while (s.heap_size > 0 && settled < max_cells) {
        const int32_t current = s.pop();
        const size_t uc = static_cast<size_t>(current);
        if (out_index.size() <= uc) out_index.resize(uc + 1, -1);
        if (out_index[uc] >= 0) continue; // settled already (a cheaper route reopened it)
        out_index[uc] = settled;
        const Node key = s.key[uc];
        if (out != nullptr && settled < cap) {
            cn_reach_cell& c = out[settled];
            c.x = key.x;
            c.y = key.y;
            c.z = key.z;
            const int32_t par = s.parent[uc];
            c.parent = par == kNoParent ? -1 : out_index[static_cast<size_t>(par)];
            const float surface = domain.surface_of(key);
            c.surface = surface_base
                + (std::isnan(surface) ? static_cast<double>(key.y - anchor.y) : static_cast<double>(surface));
            c.cost = s.g[uc];
        }
        ++settled;
        const int32_t count = domain.successors(key, s.succ_nodes, s.succ_costs);
        for (int32_t i = 0; i < count; ++i) {
            const Node sk = s.succ_nodes[i];
            const int64_t ddx = sk.x > sx ? sk.x - sx : sx - sk.x;
            const int64_t ddz = sk.z > sz ? sk.z - sz : sz - sk.z;
            if (ddx > radius || ddz > radius) continue;
            const float tentative = s.g[uc] + s.succ_costs[i];
            if (tentative > max_cost) continue;
            int32_t successor = s.index.get(sk, kUnknownNode);
            if (successor == kUnknownNode) {
                successor = s.add_node(sk, tentative, 0.0f, current, 1.0f);
                s.push(successor);
            } else if (tentative + kRelaxEpsilon < s.g[static_cast<size_t>(successor)]) {
                const size_t us = static_cast<size_t>(successor);
                if (us < out_index.size() && out_index[us] >= 0) continue; // settled is final
                s.g[us] = tentative;
                s.f[us] = tentative;
                s.parent[us] = current;
                if (s.heap_pos[us] == kNotInHeap) {
                    s.push(successor);
                } else {
                    s.sift_up(s.heap_pos[us]);
                }
            }
        }
    }
    return settled;
}

Route Searcher::air(const CellRules& rules, physics_detail::CellView& view, const AirJob& job) {
    Route route;
    Impl& s = *impl_;
    s.cells.reset(&rules, &view);
    s.classified.clear();
    Node raw_goal = AirDomain::cell_of(job.profile, job.goal.x, job.goal.y, job.goal.z);
    AirDomain snapper(s.cells, s.classified, job.profile, raw_goal, job.goal_radius, job.anchor, job.cruise_cells);
    Node snapped = snapper.snap_to_flyable(job.goal.x, job.goal.y, job.goal.z, job.goal_snap_cells);
    bool keep = snapped == kNoNode || snapped == raw_goal;
    // Classification is a pure function of the cells, so the second domain may share the memo.
    AirDomain snapped_domain(s.cells, s.classified, job.profile, keep ? raw_goal : snapped, job.goal_radius,
                             job.anchor, job.cruise_cells);
    AirDomain& domain = keep ? snapper : snapped_domain;

    Node start = AirDomain::cell_of(job.profile, job.start.x, job.start.y, job.start.z);
    std::vector<Node> chain;
    route.status = s.search(domain, start, job.limits, chain, route.cost, route.expansions);
    route.complete = route.status == CN_SEARCH_FOUND;
    // Waypoints relative to the anchor cube's corner (in blocks).
    const int64_t size64 = job.profile.cell_size;
    route.anchor = Node{job.anchor.x * size64, job.anchor.y * size64, job.anchor.z * size64};
    if (chain.empty()) return route;
    std::vector<Node> corners = domain.string_pull(chain);
    float size = static_cast<float>(job.profile.cell_size);
    float half = size * 0.5f;
    route.points.resize(corners.size() * 3);
    for (size_t i = 0; i < corners.size(); ++i) {
        const Node& node = corners[i];
        route.points[i * 3] = static_cast<double>(static_cast<float>(node.x - job.anchor.x) * size + half);
        route.points[i * 3 + 1] = static_cast<double>(static_cast<float>(node.y - job.anchor.y) * size + half);
        route.points[i * 3 + 2] = static_cast<double>(static_cast<float>(node.z - job.anchor.z) * size + half);
    }
    return route;
}

} // namespace cenda::nav
