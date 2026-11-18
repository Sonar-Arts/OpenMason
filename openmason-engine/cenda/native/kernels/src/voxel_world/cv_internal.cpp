// Engine-internal voxel world API (cv_internal.hpp): cube read guards, the padded neighbourhood the
// mesher reads, surface rings, known air, bulk installs and diagnostics.
#include "cv_internal.hpp"
#include "cv_impl.hpp"

#include <algorithm>
#include <cstring>

namespace cenda::voxel {

namespace {
constexpr int64_t kCubeMin = INT64_MIN >> CV_CUBE_SHIFT;
constexpr int64_t kCubeMax = INT64_MAX >> CV_CUBE_SHIFT;
bool coord_ok(int64_t c) { return c >= kCubeMin && c <= kCubeMax; }
} // namespace

int64_t world_min_y(const cv_world* w) { return w->min_y; }
int64_t world_max_y(const cv_world* w) { return w->max_y; }
uint16_t world_air(const cv_world* w) { return w->air; }
const uint16_t* world_block_flags(const cv_world* w) { return w->table.load()->flags; }
uint32_t world_layer_flags(const cv_world* w, int32_t layer) {
    return (layer >= 0 && layer < CV_MAX_LAYERS) ? w->layer_flags[layer].load(std::memory_order_relaxed) : 0u;
}

// ---- CubeRead ------------------------------------------------------------------------------------------

CubeRead::CubeRead(const cv_world* world, int64_t cx, int64_t cy, int64_t cz) : world_(world) {
    if (world != nullptr && coord_ok(cx) && coord_ok(cy) && coord_ok(cz)) cube_ = cube_shared(world, cx, cy, cz);
}

CubeRead::~CubeRead() {
    if (cube_ != nullptr) cube_->lock.unlock_shared();
}

CubeRead::CubeRead(CubeRead&& other) noexcept : cube_(other.cube_), world_(other.world_) {
    other.cube_ = nullptr;
}

int64_t CubeRead::revision() const { return cube_->revision; }

bool CubeRead::uniform(uint16_t* id) const {
    if (cube_->sec == nullptr) {
        *id = cube_->uniform_id;
        return true;
    }
    uint16_t first = 0;
    if (!cube_->sec[0].uniform(&first)) return false;
    for (int s = 1; s < 8; ++s) {
        uint16_t v = 0;
        if (!cube_->sec[s].uniform(&v) || v != first) return false;
    }
    *id = first;
    return true;
}

uint16_t CubeRead::get(int lx, int ly, int lz) const { return cube_->get(lx, ly, lz); }

void CubeRead::read_ids(uint16_t* out) const { read_channel(cube_->sec, cube_->uniform_id, out); }

void CubeRead::copy_ids(int x0, int y0, int z0, int sx, int sy, int sz, uint16_t* dst, ptrdiff_t stride_z,
                        ptrdiff_t stride_y) const {
    copy_channel_box(cube_->sec, cube_->uniform_id, x0, y0, z0, sx, sy, sz, dst, stride_z, stride_y);
}

bool CubeRead::layer_empty(int32_t layer) const {
    const Section* sec = cube_->layer(layer);
    if (sec == nullptr) return true;
    for (int s = 0; s < 8; ++s) {
        if (!sec[s].all_zero()) return false;
    }
    return true;
}

uint16_t CubeRead::layer(int32_t layer, int lx, int ly, int lz) const {
    return channel_get(cube_->layer(layer), 0, lx, ly, lz);
}

void CubeRead::read_layer(int32_t layer, uint16_t* out) const { read_channel(cube_->layer(layer), 0, out); }

void CubeRead::copy_layer(int32_t layer, int x0, int y0, int z0, int sx, int sy, int sz, uint16_t* dst,
                          ptrdiff_t stride_z, ptrdiff_t stride_y) const {
    copy_channel_box(cube_->layer(layer), 0, x0, y0, z0, sx, sy, sz, dst, stride_z, stride_y);
}

uint32_t CubeRead::occupancy_row(int ly, int lz) const { return cube_->occ_row(static_cast<uint32_t>(ly * 32 + lz)); }
uint32_t CubeRead::opacity_row(int ly, int lz) const { return cube_->opq_row(static_cast<uint32_t>(ly * 32 + lz)); }

void CubeRead::occupancy_rows(uint32_t* out) const {
    for (uint32_t r = 0; r < 1024; ++r) out[r] = cube_->occ_row(r);
}
void CubeRead::opacity_rows(uint32_t* out) const {
    for (uint32_t r = 0; r < 1024; ++r) out[r] = cube_->opq_row(r);
}

// ---- padded neighbourhood ------------------------------------------------------------------------------

namespace {
// Local range of a neighbour along one axis: d = -1 -> [31, 32) at p 0; 0 -> [0, 32) at p 1; 1 -> [0, 1) at p 33.
struct Span {
    int l0, n, p0;
};
Span span(int d) {
    if (d < 0) return Span{31, 1, 0};
    if (d > 0) return Span{0, 1, 33};
    return Span{0, 32, 1};
}
} // namespace

int32_t read_padded(const cv_world* cw, int64_t cx, int64_t cy, int64_t cz, PaddedCube& out, const int32_t* layers,
                    uint16_t* const* layer_out, int32_t n) {
    auto* w = const_cast<cv_world*>(cw);
    if (w == nullptr || n < 0 || (n > 0 && (layers == nullptr || layer_out == nullptr))) {
        return fail(CV_ERR_ARG, "bad arguments");
    }
    for (int32_t k = 0; k < n; ++k) {
        if (layers[k] < 0 || layers[k] >= CV_MAX_LAYERS || layer_out[k] == nullptr) return fail(CV_ERR_ARG, "bad layer");
    }
    if (!coord_ok(cx) || !coord_ok(cy) || !coord_ok(cz)) return fail(CV_ERR_ARG, "bad cube coordinates");
    if (!cube_present(w, cx, cy, cz)) return fail(CV_ERR_MISSING, "cube not loaded");

    std::fill(out.ids, out.ids + kPadCells, w->air);
    std::memset(out.occupancy, 0, sizeof out.occupancy);
    std::memset(out.opacity, 0, sizeof out.opacity);
    std::memset(out.unknown, 0, sizeof out.unknown);
    for (int32_t k = 0; k < n; ++k) std::fill(layer_out[k], layer_out[k] + kPadCells, uint16_t{0});
    out.present = 0;
    out.revision = 0;

    // Column TOP answers of missing neighbours, fetched lazily (no lock held then).
    int64_t tops[9][kColumnCells];
    int8_t tops_state[9] = {};   // 0 not fetched, 1 known, -1 unknown

    constexpr ptrdiff_t kStrideZ = kPad;
    constexpr ptrdiff_t kStrideY = kPad * kPad;
    for (int dy = -1; dy <= 1; ++dy) {
        for (int dz = -1; dz <= 1; ++dz) {
            for (int dx = -1; dx <= 1; ++dx) {
                const int64_t nx = cx + dx, ny = cy + dy, nz = cz + dz;
                if (!coord_ok(nx) || !coord_ok(ny) || !coord_ok(nz)) continue;
                const Span sx = span(dx), sy = span(dy), sz = span(dz);
                Cube* c = cube_shared(w, nx, ny, nz);
                if (c != nullptr) {
                    out.present |= 1u << neighbour_bit(dx, dy, dz);
                    if (dx == 0 && dy == 0 && dz == 0) out.revision = c->revision;
                    uint16_t* dst = out.ids + pad_index(sx.p0, sy.p0, sz.p0);
                    copy_channel_box(c->sec, c->uniform_id, sx.l0, sy.l0, sz.l0, sx.n, sy.n, sz.n, dst, kStrideZ, kStrideY);
                    for (int32_t k = 0; k < n; ++k) {
                        copy_channel_box(c->layer(layers[k]), 0, sx.l0, sy.l0, sz.l0, sx.n, sy.n, sz.n,
                                         layer_out[k] + pad_index(sx.p0, sy.p0, sz.p0), kStrideZ, kStrideY);
                    }
                    const uint32_t xmask = sx.n == 32 ? ~0u : 1u;
                    for (int y = 0; y < sy.n; ++y) {
                        for (int z = 0; z < sz.n; ++z) {
                            const auto r = static_cast<uint32_t>((sy.l0 + y) * 32 + (sz.l0 + z));
                            const int pr = (sy.p0 + y) * kPad + (sz.p0 + z);
                            const uint64_t o = (c->occ_row(r) >> sx.l0) & xmask;
                            const uint64_t p = (c->opq_row(r) >> sx.l0) & xmask;
                            out.occupancy[pr] |= o << sx.p0;
                            out.opacity[pr] |= p << sx.p0;
                        }
                    }
                    c->lock.unlock_shared();
                    continue;
                }
                // Missing: mark the cells below the column TOP (inside the bounds) unknown.
                const int ci = (dz + 1) * 3 + (dx + 1);
                if (tops_state[ci] == 0) {
                    tops_state[ci] = column_answers(w, nx, nz, kTop, tops[ci]) ? int8_t{1} : int8_t{-1};
                }
                for (int y = 0; y < sy.n; ++y) {
                    const int64_t wy = ny * CV_CUBE_EDGE + sy.l0 + y;
                    if (!w->y_in(wy)) continue;
                    for (int z = 0; z < sz.n; ++z) {
                        const int pr = (sy.p0 + y) * kPad + (sz.p0 + z);
                        for (int x = 0; x < sx.n; ++x) {
                            const auto i = static_cast<uint32_t>((sz.l0 + z) * 32 + (sx.l0 + x));
                            if (tops_state[ci] < 0 || wy < tops[ci][i]) out.unknown[pr] |= uint64_t{1} << (sx.p0 + x);
                        }
                    }
                }
            }
        }
    }
    return CV_OK;
}

// ---- surface -------------------------------------------------------------------------------------------

int32_t surface_column(cv_world* world, int64_t cx, int64_t cz, int64_t* out1024) {
    return cv_surface_column(world, cx, cz, out1024);
}

namespace {
void ring(cv_world* w, int64_t cx, int64_t cz, int ch, int64_t* out) {
    int64_t col[kColumnCells];
    for (int dz = -1; dz <= 1; ++dz) {
        for (int dx = -1; dx <= 1; ++dx) {
            const int64_t x = cx + dx, z = cz + dz;
            const Span sx = span(dx), sz = span(dz);
            if (!coord_ok(x) || !coord_ok(z)) {
                std::fill(col, col + kColumnCells, w->min_y);
            } else {
                column_answers(w, x, z, ch, col);
            }
            for (int zz = 0; zz < sz.n; ++zz) {
                for (int xx = 0; xx < sx.n; ++xx) {
                    out[(sz.p0 + zz) * kPad + (sx.p0 + xx)] = col[(sz.l0 + zz) * 32 + (sx.l0 + xx)];
                }
            }
        }
    }
}
} // namespace

void surface_ring(cv_world* world, int64_t cx, int64_t cz, int64_t* out) { ring(world, cx, cz, kSky, out); }
void top_ring(cv_world* world, int64_t cx, int64_t cz, int64_t* out) { ring(world, cx, cz, kTop, out); }

// ---- known air ------------------------------------------------------------------------------------------

bool known_air(cv_world* w, int64_t x, int64_t y, int64_t z) {
    if (!w->y_in(y)) return true;
    int64_t top = 0;
    const auto i = static_cast<uint32_t>((z & 31) * 32 + (x & 31));
    return column_answer_at(w, x >> CV_CUBE_SHIFT, z >> CV_CUBE_SHIFT, kTop, i, &top) && y >= top;
}

bool cube_known_air(cv_world* w, int64_t cx, int64_t cy, int64_t cz) {
    if (!w->cube_in(cy)) return true;
    int64_t tmax = 0;
    if (cv_top_range(w, cx, cz, nullptr, &tmax, nullptr) != CV_OK) return false;
    return cy * CV_CUBE_EDGE >= tmax;
}

bool column_top(cv_world* w, int64_t cx, int64_t cz, int64_t* out) {
    return column_answers(w, cx, cz, kTop, out);
}

// ---- install + dirty -------------------------------------------------------------------------------------

int32_t install_cube(cv_world* world, int64_t cx, int64_t cy, int64_t cz, const CubeInstall& content, bool replace) {
    return install_prepared_ids(world, cx, cy, cz, content, replace);
}

void mark_dirty(cv_world* world, int64_t cx, int64_t cy, int64_t cz) {
    if (world == nullptr || !coord_ok(cx) || !coord_ok(cy) || !coord_ok(cz)) return;
    mark_present(world, std::vector<Int3>{Int3{cx, cy, cz}});
}

// ---- diagnostics -----------------------------------------------------------------------------------------

size_t cube_heap_bytes(const cv_world* w, int64_t cx, int64_t cy, int64_t cz) {
    Cube* c = cube_shared(w, cx, cy, cz);
    if (c == nullptr) return 0;
    const size_t n = c->heap_bytes();
    c->lock.unlock_shared();
    return n;
}

size_t column_heap_bytes(const cv_world* w, int64_t cx, int64_t cz) {
    Column* c = column_shared(w, cx, cz);
    if (c == nullptr) return 0;
    const size_t n = c->heap_bytes();
    c->lock.unlock_shared();
    return n;
}

} // namespace cenda::voxel
