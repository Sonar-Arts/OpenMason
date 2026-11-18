// Octree LOD node meshes (OpenMason #122): greedy LODQUAD16 in node mode with the boundary cover rule that
// keeps seams between levels crack-free (voxel_world.h "MESHES" / "SEAMS").
#include "lod_internal.hpp"

#include <algorithm>
#include <cstring>
#include <unordered_map>

namespace cenda::voxel::lod {

namespace {

constexpr int kFaceDx[6] = {0, 0, 0, 0, 1, -1};
constexpr int kFaceDy[6] = {1, -1, 0, 0, 0, 0};
constexpr int kFaceDz[6] = {0, 0, -1, 1, 0, 0};

// (face, slice, u, v) -> cell (x, y, z); u/v axes as MmsCuboidGenerator.uAxis / vAxis.
inline void cell_of(int f, int s, int u, int v, int* x, int* y, int* z) {
    if (f <= 1) {
        *x = u; *y = s; *z = v;
    } else if (f <= 3) {
        *x = u; *y = v; *z = s;
    } else {
        *x = s; *y = v; *z = u;
    }
}

// Solidity pyramid of a neighbour node: level j (1..levels) grid of (32 >> j)^3 cells, >= 4 of 8 rule.
struct Pyramid {
    std::vector<uint8_t> grid[6];   // grid[0] = solid bits of the node's cells
    int levels = 0;
    void build(const NodeData& n, int lv) {
        levels = lv;
        grid[0].assign(kCells, 0);
        for (int i = 0; i < kCells; ++i) grid[0][static_cast<size_t>(i)] = (n.cell(i) & kClassMask) == kSolid ? 1 : 0;
        for (int j = 1; j <= lv; ++j) {
            const int e = kEdge >> j;
            const int pe = e * 2;
            grid[j].assign(static_cast<size_t>(e * e * e), 0);
            const std::vector<uint8_t>& p = grid[j - 1];
            for (int y = 0; y < e; ++y) {
                for (int z = 0; z < e; ++z) {
                    for (int x = 0; x < e; ++x) {
                        int s = 0;
                        for (int k = 0; k < 8; ++k) {
                            const int px = 2 * x + (k & 1), py = 2 * y + ((k >> 2) & 1), pz = 2 * z + ((k >> 1) & 1);
                            s += p[static_cast<size_t>((py * pe + pz) * pe + px)];
                        }
                        grid[j][static_cast<size_t>((y * e + z) * e + x)] = s >= 4 ? 1 : 0;
                    }
                }
            }
        }
    }
    bool ancestors_solid(int x, int y, int z) const {
        for (int j = 1; j <= levels; ++j) {
            const int e = kEdge >> j;
            if (grid[j][static_cast<size_t>(((y >> j) * e + (z >> j)) * e + (x >> j))] == 0) return false;
        }
        return true;
    }
};

struct Scratch {
    uint8_t cls[kCells];
    uint16_t ids[kCells];
    uint8_t cover[6][1024];        // boundary faces: 1 = covered by the neighbour (solid faces)
    uint8_t water_cover[1024];     // top boundary of fluid cells: neighbour above solid or fluid
    uint32_t mask[1024];
    Pyramid pyr;
};

Scratch& scratch() {
    thread_local std::unique_ptr<Scratch> s;
    if (!s) s = std::make_unique<Scratch>();
    return *s;
}

void put_u32(std::vector<uint8_t>& o, uint32_t v) {
    o.push_back(static_cast<uint8_t>(v));
    o.push_back(static_cast<uint8_t>(v >> 8));
    o.push_back(static_cast<uint8_t>(v >> 16));
    o.push_back(static_cast<uint8_t>(v >> 24));
}

class Faces {
public:
    explicit Faces(const FaceLookup& f) : f_(f) {}
    // Key: bit 0 valid, bits 1..12 layer, bit 13 alpha.
    uint32_t key(uint16_t id, int face) {
        auto it = cache_.find(id);
        if (it == cache_.end()) {
            std::array<uint32_t, 6> k{};
            for (int i = 0; i < 6; ++i) {
                uint32_t layer = 0;
                bool alpha = false;
                f_.face(id, i, &layer, &alpha);
                k[static_cast<size_t>(i)] = 1u | ((layer & 0xFFFu) << 1) | ((alpha ? 1u : 0u) << 13);
            }
            it = cache_.emplace(id, k).first;
        }
        return it->second[static_cast<size_t>(face)];
    }

private:
    const FaceLookup& f_;
    std::unordered_map<uint16_t, std::array<uint32_t, 6>> cache_;
};

struct Emitter {
    MeshBuild& out;
    bool any = false;
    void emit(std::vector<uint8_t>& dst, int32_t& count, int f, int s, int u, int v, int w, int h, uint32_t key) {
        const int plane = (f == 0 || f == 3 || f == 4) ? s + 1 : s;
        int x, y, z;
        if (f <= 1) {
            x = u; y = plane; z = v;
        } else if (f <= 3) {
            x = u; y = v; z = plane;
        } else {
            x = plane; y = v; z = u;
        }
        put_u32(dst, node_word0(x, y, z, f));
        put_u32(dst, node_word1(w, h, (key >> 1) & 0xFFFu, ((key >> 13) & 1u) != 0, true));
        put_u32(dst, 0);
        put_u32(dst, 0);
        ++count;
        // Bounds (node-local cells).
        float lo[3] = {static_cast<float>(x), static_cast<float>(y), static_cast<float>(z)};
        float hi[3] = {lo[0], lo[1], lo[2]};
        const int ua = f <= 3 ? 0 : 2;
        const int va = f <= 1 ? 2 : 1;
        hi[ua] += static_cast<float>(w);
        hi[va] += static_cast<float>(h);
        for (int a = 0; a < 3; ++a) {
            if (!any || lo[a] < out.aabb_min[a]) out.aabb_min[a] = lo[a];
            if (!any || hi[a] > out.aabb_max[a]) out.aabb_max[a] = hi[a];
        }
        if (!any) {
            for (int a = 0; a < 3; ++a) {
                out.aabb_min[a] = lo[a];
                out.aabb_max[a] = hi[a];
            }
        }
        any = true;
    }
};

// Greedy merge of a 32x32 key mask (u fastest), emitting rectangles.
template <class F>
void greedy(uint32_t* mask, F&& emit) {
    for (int v = 0; v < kEdge; ++v) {
        for (int u = 0; u < kEdge;) {
            const uint32_t k = mask[v * kEdge + u];
            if (k == 0) {
                ++u;
                continue;
            }
            int w = 1;
            while (u + w < kEdge && mask[v * kEdge + u + w] == k) ++w;
            int h = 1;
            for (; v + h < kEdge; ++h) {
                bool row = true;
                for (int i = 0; i < w && row; ++i) row = mask[(v + h) * kEdge + u + i] == k;
                if (!row) break;
            }
            for (int r = 0; r < h; ++r) std::fill_n(mask + (v + r) * kEdge + u, w, 0u);
            emit(u, v, w, h, k);
            u += w;
        }
    }
}

} // namespace

void mesh_node(const NodeData& node, const NodeData* const* nb, bool below_solid, int cover_levels,
               const FaceLookup& faces, MeshBuild& out) {
    out.key = node.key;
    out.quads.clear();
    out.water.clear();
    out.quad_count = 0;
    out.water_count = 0;
    for (int a = 0; a < 3; ++a) out.aabb_min[a] = out.aabb_max[a] = 0.0f;
    out.digest = node.digest;
    if (node.uniform && (node.uniform_cell & kClassMask) == kAir) return;

    Scratch& sc = scratch();
    if (node.uniform) {
        std::memset(sc.cls, node.uniform_cell & kClassMask, sizeof(sc.cls));
        std::fill_n(sc.ids, kCells, node.uniform_id);
    } else {
        for (int i = 0; i < kCells; ++i) sc.cls[i] = node.cells[static_cast<size_t>(i)] & kClassMask;
        std::memcpy(sc.ids, node.ids.data(), sizeof(sc.ids));
    }

    // ---- boundary covers -------------------------------------------------------------------------------------
    std::memset(sc.cover, 0, sizeof(sc.cover));
    std::memset(sc.water_cover, 0, sizeof(sc.water_cover));
    if (const NodeData* up = nb[0]) {
        for (int z = 0; z < kEdge; ++z) {
            for (int x = 0; x < kEdge; ++x) {
                const uint8_t c = up->cell(cell_index(x, 0, z)) & kClassMask;
                sc.cover[0][z * kEdge + x] = c == kSolid ? 1 : 0;
                sc.water_cover[z * kEdge + x] = c != kAir ? 1 : 0;
            }
        }
    }
    if (below_solid) {
        std::memset(sc.cover[1], 1, sizeof(sc.cover[1]));
    } else if (const NodeData* dn = nb[1]) {
        for (int z = 0; z < kEdge; ++z) {
            for (int x = 0; x < kEdge; ++x) {
                sc.cover[1][z * kEdge + x] = (dn->cell(cell_index(x, kEdge - 1, z)) & kClassMask) == kSolid ? 1 : 0;
            }
        }
    }
    for (int f = 2; f < 6; ++f) {
        const NodeData* n = nb[f];
        if (n == nullptr) continue;
        if (n->uniform && (n->uniform_cell & kFull) == 0) continue;   // uniform non-full: covers nothing
        const bool uniform_full = n->uniform;                        // uniform FULL: every ancestor is full too
        if (!uniform_full) sc.pyr.build(*n, cover_levels);
        for (int v = 0; v < kEdge; ++v) {
            for (int u = 0; u < kEdge; ++u) {
                // The neighbour's cell across the boundary (its face-opposite slice).
                int x, y, z;
                const int s = (kFaceDx[f] + kFaceDy[f] + kFaceDz[f]) > 0 ? 0 : kEdge - 1;
                cell_of(f, s, u, v, &x, &y, &z);
                bool covered;
                if (uniform_full) {
                    covered = true;
                } else {
                    covered = (n->cell(cell_index(x, y, z)) & kFull) != 0 && sc.pyr.ancestors_solid(x, y, z);
                }
                sc.cover[f][v * kEdge + u] = covered ? 1 : 0;
            }
        }
    }

    // ---- faces ---------------------------------------------------------------------------------------------------
    Faces fl(faces);
    Emitter em{out};
    for (int f = 0; f < 6; ++f) {
        for (int s = 0; s < kEdge; ++s) {
            bool any = false;
            for (int v = 0; v < kEdge; ++v) {
                for (int u = 0; u < kEdge; ++u) {
                    int x, y, z;
                    cell_of(f, s, u, v, &x, &y, &z);
                    const int i = cell_index(x, y, z);
                    uint32_t k = 0;
                    if (sc.cls[i] == kSolid) {
                        const int nx = x + kFaceDx[f], ny = y + kFaceDy[f], nz = z + kFaceDz[f];
                        bool draw;
                        if (nx < 0 || ny < 0 || nz < 0 || nx >= kEdge || ny >= kEdge || nz >= kEdge) {
                            draw = sc.cover[f][v * kEdge + u] == 0;
                        } else {
                            draw = sc.cls[cell_index(nx, ny, nz)] != kSolid;
                        }
                        if (draw) {
                            k = fl.key(sc.ids[i], f);
                            any = true;
                        }
                    }
                    sc.mask[v * kEdge + u] = k;
                }
            }
            if (!any) continue;
            greedy(sc.mask, [&](int u, int v, int w, int h, uint32_t k) {
                em.emit(out.quads, out.quad_count, f, s, u, v, w, h, k);
            });
        }
    }
    // Fluid tops.
    for (int s = 0; s < kEdge; ++s) {
        bool any = false;
        for (int v = 0; v < kEdge; ++v) {
            for (int u = 0; u < kEdge; ++u) {
                const int i = cell_index(u, s, v);
                uint32_t k = 0;
                if (sc.cls[i] == kFluid) {
                    const bool draw = s + 1 < kEdge ? sc.cls[cell_index(u, s + 1, v)] == kAir
                                                    : sc.water_cover[v * kEdge + u] == 0;
                    if (draw) {
                        k = fl.key(sc.ids[i], 0);
                        any = true;
                    }
                }
                sc.mask[v * kEdge + u] = k;
            }
        }
        if (!any) continue;
        greedy(sc.mask, [&](int u, int v, int w, int h, uint32_t k) {
            em.emit(out.water, out.water_count, 0, s, u, v, w, h, k);
        });
    }
}

} // namespace cenda::voxel::lod
