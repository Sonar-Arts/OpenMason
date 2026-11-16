// Engine-internal access to a cp_world's cell cache, for other Cenda subsystems in the same library
// (navigation, cn_*). NOT part of any C ABI: the JVM never sees these, and they keep the physics
// cache the single place a world's cells are read (OpenMason #116 — navigation answers from the same
// shapes and fluids physics collides with, instead of a second block reader).
//
// Threading: like every non-invalidation cp_* call, owner thread only. Call begin() once at the
// start of each top-level call that reads cells (it applies queued invalidations and keeps the cache
// bounded); a CellView never evicts, so brick pointers it memoises stay valid until the next begin().
#pragma once

#include "cenda/physics.h"

#include <cstdint>

namespace cenda::physics_detail {

// Applies queued invalidations and evicts over-capacity bricks (cp_* calls do this on entry).
void begin(cp_world* world);

// One reader with a one-brick memo, the way cp_* queries read cells.
class CellView {
public:
    explicit CellView(cp_world* world) : world_(world) {}

    // The cell's shape and fluid ids. Returns false when the host reported the region not loaded
    // (shape/fluid are then the world's unloaded shape and 0, and nothing was cached).
    bool read(int64_t x, int64_t y, int64_t z, uint16_t& shape, uint16_t& fluid);

    cp_world* world() const { return world_; }

private:
    cp_world* world_;
    uint64_t key_ = ~0ull;
    void* brick_ = nullptr;
    bool known_ = false;
};

// Height of the tallest box of a shape over the whole cell, when its category is in mask; 0 when the
// shape is empty or not in mask (the footprint-free cp_cell_top).
float shape_top(const cp_world* world, uint16_t shape, uint32_t mask);

// The category word of a shape (0 for shape 0 or an undefined/empty shape).
uint32_t shape_category(const cp_world* world, uint16_t shape);

// The surface height of a fluid whose category is in mask, or a negative value when the fluid is 0,
// undefined or not in mask.
float fluid_surface(const cp_world* world, uint16_t fluid, uint32_t mask);

} // namespace cenda::physics_detail
