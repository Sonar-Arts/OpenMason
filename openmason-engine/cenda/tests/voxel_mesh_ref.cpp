// TEST-ONLY reference of the cube mesher's cube-face path as it was before the bit-sliced lighting rewrite
// (2026-11-17): a verbatim copy of mesh_binary.cpp (classes, classify, face masks, per-vertex light with the
// memo, merge table, mesh_cube_faces, mesh_host_cells, mesh_prepare) and mesh_pack.cpp (build_gpu_mesh,
// cv_mesh_cube), in namespace cenda::voxel::meshref with its own per-thread workspace. Models and fluids are
// the library's (unchanged by the rewrite). voxel_mesh_diff compares the library against it byte for byte.
#include "voxel_mesh_ref.hpp"
#include "cv_error.hpp"

#include <algorithm>
#include <bit>
#include <cstring>
#include <limits>
#include <memory>

namespace cenda::voxel::meshref {

namespace {

constexpr uint64_t kFull34 = (uint64_t{1} << 34) - 1;
constexpr uint64_t kInner = kFull34 & ~uint64_t{1} & ~(uint64_t{1} << 33);   // bits 1..32

// AO tables (VertexLightSampler.sampleAoFactor): normal offset + two tangents.
constexpr int kAoN[6][3] = {{0, 0, 0}, {0, -1, 0}, {0, 0, -1}, {0, 0, 0}, {0, 0, 0}, {-1, 0, 0}};
constexpr int kAoT1[6][3] = {{-1, 0, 0}, {-1, 0, 0}, {-1, 0, 0}, {-1, 0, 0}, {0, -1, 0}, {0, -1, 0}};
constexpr int kAoT2[6][3] = {{0, 0, -1}, {0, 0, -1}, {0, -1, 0}, {0, -1, 0}, {0, 0, -1}, {0, 0, -1}};
constexpr float kAoPerNeighbour = 0.13f;

void build_classes(MeshWork& w) {
    const MeshConfig& cfg = *w.cfg;
    if (w.cls_flags == w.flags && w.cls_cfg == &cfg && w.cls_version == cfg.version) return;
    const uint16_t air = world_air(w.world);
    for (uint32_t id = 0; id < 65536; ++id) {
        const uint16_t f = w.flags[id];
        const auto bid = static_cast<uint16_t>(id);
        uint8_t c = 0;
        if (bid == air) {
            c = kClsSee | kClsAir;
        } else if (cfg.is_fluid(bid)) {
            c = kClsSee | kClsSpecial;
        } else if ((f & CV_BLOCK_CUBE) != 0) {
            c = (f & (CV_BLOCK_TRANSPARENT | CV_BLOCK_TRANSLUCENT)) != 0 ? uint8_t{kClsTranspCube | kClsSee}
                                                                       : uint8_t{kClsOpaqueCube};
        } else {
            if (cfg.is_bound(bid) || (f & CV_BLOCK_HOST_GEOMETRY) != 0) c |= kClsSpecial;
            if ((f & (CV_BLOCK_TRANSPARENT | CV_BLOCK_TRANSLUCENT)) != 0) c |= kClsSee;
        }
        if (bid != air && (f & CV_BLOCK_VIS_OPAQUE) != 0) c |= kClsVisClosed;
        w.cls[id] = c;
    }
    w.cls_flags = w.flags;
    w.cls_cfg = &cfg;
    w.cls_version = cfg.version;
}

void classify(MeshWork& w) {
    const PaddedCube& p = w.pad;
    for (int py = 0; py < kPad; ++py) {
        for (int pz = 0; pz < kPad; ++pz) {
            const int r = py * kPad + pz;
            const uint64_t occ = p.occupancy[r];
            const bool interior = py >= 1 && py <= 32 && pz >= 1 && pz <= 32;
            if (occ == 0) {
                w.see[r] = kFull34;
                w.opaque_cube[r] = w.transp_cube[r] = w.special[r] = 0;
                if (interior) w.closed[(py - 1) * 32 + (pz - 1)] = 0;
                continue;
            }
            uint64_t see = ~occ & kFull34, oc = 0, tc = 0, sp = 0, vc = 0;
            const uint16_t* row = p.ids + pad_index(0, py, pz);
            if (occ == kFull34) {
                bool same = true;
                for (int x = 1; x < kPad; ++x) same &= row[x] == row[0];
                if (same) {   // one block across the row (buried stone, oceans)
                    const uint8_t c = w.cls[row[0]];
                    w.see[r] = (c & kClsSee) != 0 ? kFull34 : 0;
                    w.opaque_cube[r] = (c & kClsOpaqueCube) != 0 ? kFull34 : 0;
                    w.transp_cube[r] = (c & kClsTranspCube) != 0 ? kFull34 : 0;
                    w.special[r] = (c & kClsSpecial) != 0 ? kFull34 : 0;
                    if (interior) w.closed[(py - 1) * 32 + (pz - 1)] = (c & kClsVisClosed) != 0 ? ~0u : 0u;
                    continue;
                }
            }
            uint64_t bits = occ;
            uint16_t last_id = row[std::countr_zero(bits)];
            uint8_t last = w.cls[last_id];
            while (bits != 0) {
                const int x = std::countr_zero(bits);
                bits &= bits - 1;
                const uint16_t id = row[x];
                if (id != last_id) {
                    last_id = id;
                    last = w.cls[id];
                }
                const uint64_t m = uint64_t{1} << x;
                if ((last & kClsSee) != 0) see |= m;
                if ((last & kClsOpaqueCube) != 0) oc |= m;
                if ((last & kClsTranspCube) != 0) tc |= m;
                if ((last & kClsSpecial) != 0) sp |= m;
                if ((last & kClsVisClosed) != 0) vc |= m;
            }
            w.see[r] = see;
            w.opaque_cube[r] = oc;
            w.transp_cube[r] = tc;
            w.special[r] = sp;
            if (interior) w.closed[(py - 1) * 32 + (pz - 1)] = static_cast<uint32_t>((vc >> 1) & 0xFFFFFFFFu);
        }
    }
}

// Row of `mask` for the neighbours of row r's cells across face f (bit px = the neighbour of cell px).
inline uint64_t nb_row(const uint64_t* mask, int r, int f) {
    switch (f) {
        case 0: return mask[r + kPad];
        case 1: return mask[r - kPad];
        case 2: return mask[r - 1];
        case 3: return mask[r + 1];
        case 4: return mask[r] >> 1;
        default: return mask[r] << 1;
    }
}

// Face bits per direction: rows[f][ly * 32 + lz], bit lx.
void face_masks(MeshWork& w, uint32_t (*rows)[1024]) {
    for (int py = 1; py <= 32; ++py) {
        for (int pz = 1; pz <= 32; ++pz) {
            const int r = py * kPad + pz;
            const int out = (py - 1) * 32 + (pz - 1);
            const uint64_t a = w.opaque_cube[r], b = w.transp_cube[r];
            if ((a | b) == 0) {
                for (int f = 0; f < 6; ++f) rows[f][out] = 0;
                continue;
            }
            for (int f = 0; f < 6; ++f) {
                const uint64_t see = nb_row(w.see, r, f);
                uint64_t face = a & see & kInner;
                uint64_t cand = b & see & kInner;
                while (cand != 0) {
                    const int x = std::countr_zero(cand);
                    cand &= cand - 1;
                    const int nx = x + kFaceDx[f], ny = py + kFaceDy[f], nz = pz + kFaceDz[f];
                    const uint16_t nid = w.id_at(nx, ny, nz);
                    if ((w.cls[nid] & kClsAir) != 0 || nid != w.id_at(x, py, pz)) face |= uint64_t{1} << x;
                }
                // One-way shape rule: a special neighbour that does not cover the shared plane.
                uint64_t shaped = (a | b) & ~see & nb_row(w.special, r, f) & kInner;
                while (shaped != 0) {
                    const int x = std::countr_zero(shaped);
                    shaped &= shaped - 1;
                    if (!cell_fills(w, x + kFaceDx[f], py + kFaceDy[f], pz + kFaceDz[f], kOpposite[f])) {
                        face |= uint64_t{1} << x;
                    }
                }
                rows[f][out] = static_cast<uint32_t>((face >> 1) & 0xFFFFFFFFu);
            }
        }
    }
}

struct MergeEntry {
    uint32_t key;    // forced-opaque << 24 | id << 8 | light
    uint32_t used;   // bit v: rows[v] != 0
    uint32_t rows[32];
};

struct MergeTable {
    std::vector<MergeEntry> entries;
    int16_t slots[1024];
    std::vector<uint16_t> used;
    MergeTable() { std::memset(slots, 0xFF, sizeof slots); }
    MergeEntry& get(uint32_t key) {
        uint32_t h = (key * 2654435761u) >> 22;
        for (;; h = (h + 1) & 1023u) {
            const int16_t s = slots[h];
            if (s < 0) {
                slots[h] = static_cast<int16_t>(entries.size());
                used.push_back(static_cast<uint16_t>(h));
                entries.push_back(MergeEntry{key, 0, {}});
                return entries.back();
            }
            if (entries[static_cast<size_t>(s)].key == key) return entries[static_cast<size_t>(s)];
        }
    }
    void reset() {
        for (uint16_t h : used) slots[h] = -1;
        used.clear();
        entries.clear();
    }
};

int list_of(const MeshWork& w, uint16_t id) {
    const uint16_t f = w.flags[id];
    if ((w.cls[id] & kClsTranspCube) == 0) return 0;
    return (f & CV_BLOCK_TRANSLUCENT) != 0 ? 2 : 1;
}

} // namespace

MeshWork& mesh_work() {
    thread_local std::unique_ptr<MeshWork> work;
    if (!work) work = std::make_unique<MeshWork>();
    return *work;
}

int32_t mesh_prepare(cv_world* world, int64_t cx, int64_t cy, int64_t cz, uint32_t flags, MeshWork& w) {
    if (world == nullptr) {
        cv_set_error("mesh: null world");
        return CV_ERR_ARG;
    }
    w.world = world;
    w.cx = cx;
    w.cy = cy;
    w.cz = cz;
    w.flags = table_snapshot(world, &w.generation);
    w.cfg = mesh_config(world);
    w.smooth = (flags & CV_MESH_SMOOTH_LIGHTING) != 0;
    w.ring_ready = false;
    int32_t layers[CV_MAX_LAYERS];
    uint16_t* bufs[CV_MAX_LAYERS];
    for (int i = 0; i < CV_MAX_LAYERS; ++i) w.layer_slot[i] = -1;
    int32_t n = 0;
    for (int32_t layer : w.cfg->layers) {
        if (layer < 0 || layer >= CV_MAX_LAYERS) continue;
        auto& buf = w.layer_buf[n];
        if (buf.size() != static_cast<size_t>(kPadCells)) buf.resize(static_cast<size_t>(kPadCells));
        layers[n] = layer;
        bufs[n] = buf.data();
        w.layer_slot[layer] = n;
        ++n;
    }
    const int32_t rc = read_padded(world, cx, cy, cz, w.pad, layers, bufs, n);
    if (rc != CV_OK) return rc;
    w.y0 = cy * CV_CUBE_EDGE;
    build_classes(w);
    classify(w);
    return CV_OK;
}

namespace {

// mesher.cpp skyFactor/aoFactor as offsets from the VERTEX (the light of a face corner depends only on the
// vertex and the face direction, so faces sharing a vertex share its light), plus the u8 result of every
// (lit samples, AO count) combination computed with the exact float ops of the scalar code.
struct LightTables {
    int8_t sky[6][4][3];   // (a, b) = (-1,-1) (-1,0) (0,-1) (0,0); flat lighting reads only [3]
    int8_t ao[6][3][3];    // side1, side2, corner
    uint8_t smooth[5][4];  // [lit of 4][AO count]
    uint8_t flat[2];
    LightTables() {
        for (int f = 0; f < 6; ++f) {
            int k = 0;
            for (int a = -1; a <= 0; ++a) {
                for (int b = -1; b <= 0; ++b, ++k) {
                    int sx, sy, sz;
                    switch (f) {
                        case 0: sx = a; sy = 0; sz = b; break;
                        case 1: sx = a; sy = -1; sz = b; break;
                        case 2: sx = a; sy = b; sz = -1; break;
                        case 3: sx = a; sy = b; sz = 0; break;
                        case 4: sx = 0; sy = a; sz = b; break;
                        default: sx = -1; sy = a; sz = b; break;
                    }
                    sky[f][k][0] = static_cast<int8_t>(sx);
                    sky[f][k][1] = static_cast<int8_t>(sy);
                    sky[f][k][2] = static_cast<int8_t>(sz);
                }
            }
            const int* n = kAoN[f];
            const int* t1 = kAoT1[f];
            const int* t2 = kAoT2[f];
            for (int i = 0; i < 3; ++i) {
                ao[f][0][i] = static_cast<int8_t>(n[i] + t1[i]);
                ao[f][1][i] = static_cast<int8_t>(n[i] + t2[i]);
                ao[f][2][i] = static_cast<int8_t>(n[i] + t1[i] + t2[i]);
            }
        }
        for (int lit = 0; lit <= 4; ++lit) {
            for (int count = 0; count <= 3; ++count) {
                float light = static_cast<float>(lit) / static_cast<float>(4);
                light *= 1.0f - kAoPerNeighbour * static_cast<float>(count);
                smooth[lit][count] = to_u8(light);
            }
        }
        for (int lit = 0; lit <= 1; ++lit) flat[lit] = to_u8(static_cast<float>(lit) / static_cast<float>(1));
    }
};
const LightTables kLight;

// Light of vertex (vx, vy, vz) (cube-local, 0..32) for faces of direction f.
inline uint8_t vertex_light(MeshWork& w, const int64_t* ring, int f, int vx, int vy, int vz) {
    auto lit_at = [&](int k) {
        const int x = vx + kLight.sky[f][k][0], y = vy + kLight.sky[f][k][1], z = vz + kLight.sky[f][k][2];
        return w.y0 + y >= ring[(z + 1) * kPad + (x + 1)] ? 1 : 0;
    };
    if (!w.smooth) return kLight.flat[lit_at(3)];
    const int lit = lit_at(0) + lit_at(1) + lit_at(2) + lit_at(3);
    auto solid = [&](int k) {
        return w.opaque_at(vx + kLight.ao[f][k][0] + 1, vy + kLight.ao[f][k][1] + 1, vz + kLight.ao[f][k][2] + 1);
    };
    const bool side1 = solid(0), side2 = solid(1), cornr = solid(2);
    int count = (side1 ? 1 : 0) + (side2 ? 1 : 0) + (cornr ? 1 : 0);
    if (side1 && side2) count = 3;
    return kLight.smooth[lit][count];
}

// Vertex lights of one face-direction slice, computed once per vertex.
struct LightMemo {
    uint32_t stamp[33 * 33] = {};
    uint8_t value[33 * 33] = {};
    uint32_t cur = 0;
    void next() {
        if (++cur == 0) {
            std::memset(stamp, 0, sizeof stamp);
            cur = 1;
        }
    }
};

inline void face_lights_memo(MeshWork& w, const int64_t* ring, LightMemo& memo, int lx, int ly, int lz, int f,
                             uint8_t* out) {
    for (int c = 0; c < 4; ++c) {
        const int vx = lx + kFaceOffsets[f][c][0], vy = ly + kFaceOffsets[f][c][1], vz = lz + kFaceOffsets[f][c][2];
        const int i = f <= 1 ? vx * 33 + vz : (f <= 3 ? vx * 33 + vy : vz * 33 + vy);
        if (memo.stamp[i] != memo.cur) {
            memo.stamp[i] = memo.cur;
            memo.value[i] = vertex_light(w, ring, f, vx, vy, vz);
        }
        out[c] = memo.value[i];
    }
}

} // namespace

void mesh_cube_faces(MeshWork& w, bool greedy, std::vector<cv_quad>* out) {
    struct Scratch {
        uint32_t rows[6][1024];
        uint32_t plane[32][32];
        MergeTable table;
        LightMemo memo;
    };
    thread_local std::unique_ptr<Scratch> scratch;
    if (!scratch) scratch = std::make_unique<Scratch>();
    // Plain references: a thread_local inside the shared library costs a __tls_get_addr per access.
    Scratch& sc = *scratch;
    auto& rows = sc.rows;
    auto& plane = sc.plane;
    MergeTable& table = sc.table;
    LightMemo& memo = sc.memo;
    face_masks(w, rows);
    const int64_t* ring = nullptr;
    const bool rules = !w.cfg->face_rules.empty();
    for (int f = 0; f < 6; ++f) {
        bool any = false;
        for (int i = 0; i < 1024 && !any; ++i) any = rows[f][i] != 0;
        if (!any) continue;
        std::memset(plane, 0, sizeof plane);
        for (int ly = 0; ly < 32; ++ly) {
            for (int lz = 0; lz < 32; ++lz) {
                const uint32_t bits = rows[f][ly * 32 + lz];
                if (bits == 0) continue;
                if (f <= 1) {
                    plane[ly][lz] = bits;
                } else if (f <= 3) {
                    plane[lz][ly] = bits;
                } else {
                    uint32_t b = bits;
                    while (b != 0) {
                        const int lx = std::countr_zero(b);
                        b &= b - 1;
                        plane[lx][ly] |= 1u << lz;
                    }
                }
            }
        }
        if (ring == nullptr) ring = w.sky_ring();
        for (int s = 0; s < 32; ++s) {
            table.reset();
            memo.next();
            for (int v = 0; v < 32; ++v) {
                uint32_t bits = plane[s][v];
                while (bits != 0) {
                    const int u = std::countr_zero(bits);
                    bits &= bits - 1;
                    const int lx = f <= 3 ? u : s;
                    const int ly = f <= 1 ? s : v;
                    const int lz = f <= 1 ? v : (f <= 3 ? s : u);
                    const uint16_t id = w.id_at(lx + 1, ly + 1, lz + 1);
                    bool forced = false;   // neighbour face rules (cv_face_rule_add)
                    if (rules) {
                        const uint32_t k = face_rule_kinds(w, lx + 1, ly + 1, lz + 1, f);
                        if ((k & CV_FACE_RULE_CULL_WHEN_NEIGHBOUR_IN) != 0) continue;
                        forced = (k & CV_FACE_RULE_OPAQUE_WHEN_NEIGHBOUR_IN) != 0;
                    }
                    cv_quad q{};
                    q.x = static_cast<uint8_t>(lx);
                    q.y = static_cast<uint8_t>(ly);
                    q.z = static_cast<uint8_t>(lz);
                    q.face = static_cast<uint8_t>(forced ? (f | kQuadForcedOpaque) : f);
                    q.w = 1;
                    q.h = 1;
                    q.id = id;
                    face_lights_memo(w, ring, memo, lx, ly, lz, f, q.light);
                    if (!greedy || q.light[0] != q.light[1] || q.light[0] != q.light[2] || q.light[0] != q.light[3]) {
                        out[forced ? 0 : list_of(w, id)].push_back(q);
                        continue;
                    }
                    MergeEntry& e =
                        table.get((forced ? 1u << 24 : 0u) | static_cast<uint32_t>(id) << 8 | q.light[0]);
                    e.rows[v] |= 1u << u;
                    e.used |= 1u << v;
                }
            }
            for (MergeEntry& e : table.entries) {
                const auto id = static_cast<uint16_t>(e.key >> 8);
                const auto light = static_cast<uint8_t>(e.key & 0xFFu);
                const bool forced = (e.key >> 24) != 0;
                std::vector<cv_quad>& dst = out[forced ? 0 : list_of(w, id)];
                while (e.used != 0) {
                    const int v = std::countr_zero(e.used);
                    e.used &= e.used - 1;
                    while (e.rows[v] != 0) {
                        const uint64_t row = e.rows[v];
                        const int u0 = std::countr_zero(row);
                        const int len = std::countr_zero(~(row >> u0));
                        const auto run = static_cast<uint32_t>(((uint64_t{1} << len) - 1) << u0);
                        int h = 1;
                        while (v + h < 32 && (e.rows[v + h] & run) == run) {
                            e.rows[v + h] &= ~run;
                            ++h;
                        }
                        e.rows[v] &= ~run;
                        const int lx = f <= 3 ? u0 : s;
                        const int ly = f <= 1 ? s : v;
                        const int lz = f <= 1 ? v : (f <= 3 ? s : u0);
                        cv_quad q{};
                        q.x = static_cast<uint8_t>(lx);
                        q.y = static_cast<uint8_t>(ly);
                        q.z = static_cast<uint8_t>(lz);
                        q.face = static_cast<uint8_t>(forced ? (f | kQuadForcedOpaque) : f);
                        q.w = static_cast<uint8_t>(len);
                        q.h = static_cast<uint8_t>(h);
                        q.id = id;
                        q.light[0] = q.light[1] = q.light[2] = q.light[3] = light;
                        dst.push_back(q);
                    }
                }
            }
        }
    }
}

void mesh_host_cells(const MeshWork& w, std::vector<cv_host_cell>& out) {
    for (int py = 1; py <= 32; ++py) {
        for (int pz = 1; pz <= 32; ++pz) {
            uint64_t sp = w.special[py * kPad + pz] & kInner;
            while (sp != 0) {
                const int px = std::countr_zero(sp);
                sp &= sp - 1;
                const uint16_t id = w.id_at(px, py, pz);
                if ((w.flags[id] & CV_BLOCK_HOST_GEOMETRY) == 0 || w.cfg->is_fluid(id)) continue;
                if (w.model_at(px, py, pz) != nullptr) continue;
                out.push_back(cv_host_cell{static_cast<uint16_t>(CV_CELL_INDEX(px - 1, py - 1, pz - 1)), id});
            }
        }
    }
}

namespace {

inline void store_u32(uint8_t* p, uint32_t v) {
    p[0] = static_cast<uint8_t>(v);
    p[1] = static_cast<uint8_t>(v >> 8);
    p[2] = static_cast<uint8_t>(v >> 16);
    p[3] = static_cast<uint8_t>(v >> 24);
}

uint16_t cube_visibility(const MeshWork& w) {
    bool any_closed = false, any_open = false;
    for (int i = 0; i < 1024; ++i) {
        any_closed = any_closed || w.closed[i] != 0;
        any_open = any_open || w.closed[i] != ~0u;
    }
    if (!any_closed) return kAllPairs;
    if (!any_open) return 0;
    return visibility_from_rows(w.closed);
}

bool has_specials(const MeshWork& w) {
    for (int py = 1; py <= 32; ++py) {
        for (int pz = 1; pz <= 32; ++pz) {
            if ((w.special[py * kPad + pz] & ((uint64_t{1} << 33) - 2)) != 0) return true;
        }
    }
    return false;
}

void quad_bounds(const cv_quad& q, int ox, int oy, int oz, float* bmin, float* bmax) {
    int lo[3] = {ox + q.x, oy + q.y, oz + q.z};
    int hi[3] = {lo[0], lo[1], lo[2]};
    hi[kUAxis[q.face]] += q.w;
    hi[kVAxis[q.face]] += q.h;
    const int n = q.face <= 1 ? 1 : (q.face <= 3 ? 2 : 0);   // the plane sits at +1 for top/south/east
    if (q.face == 0 || q.face == 3 || q.face == 4) lo[n] = hi[n] = lo[n] + 1;
    for (int a = 0; a < 3; ++a) {
        bmin[a] = std::min(bmin[a], static_cast<float>(lo[a]));
        bmax[a] = std::max(bmax[a], static_cast<float>(hi[a]));
    }
}

} // namespace

int32_t build_gpu_mesh(cv_world* world, int64_t cx, int64_t cy, int64_t cz, uint32_t flags, GpuMeshBuild& out) {
    out.clear();
    MeshWork& w = mesh_work();
    const int32_t rc = meshref::mesh_prepare(world, cx, cy, cz, flags, w);
    if (rc != CV_OK) return rc;
    out.revision = w.pad.revision;
    thread_local std::vector<cv_quad> quads[3];
    for (auto& q : quads) q.clear();
    meshref::mesh_cube_faces(w, (flags & CV_MESH_GREEDY) != 0, quads);

    float bmin[3], bmax[3];
    for (int a = 0; a < 3; ++a) {
        bmin[a] = std::numeric_limits<float>::max();
        bmax[a] = -std::numeric_limits<float>::max();
    }
    const int ox = static_cast<int>(cx & 7) * 32, oy = static_cast<int>(cy & 7) * 32, oz = static_cast<int>(cz & 7) * 32;
    const MeshConfig& cfg = *w.cfg;
    for (int list = 0; list < 3; ++list) {
        std::vector<uint8_t>& dst = out.quad16[list];
        dst.resize(quads[list].size() * 16);
        uint8_t* p = dst.data();
        for (cv_quad q : quads[list]) {
            // A face rule (cv_face_rule_add) may force the face opaque: alpha test + translucent off.
            const bool forced = (q.face & kQuadForcedOpaque) != 0;
            q.face = static_cast<uint8_t>(q.face & 7u);
            const uint16_t bf = w.flags[q.id];
            uint32_t layer = q.id, orient = 0;
            bool alpha = (bf & CV_BLOCK_TRANSPARENT) != 0 && (bf & CV_BLOCK_TRANSLUCENT) == 0;
            if (!cfg.faces.empty()) {
                auto it = cfg.faces.find(q.id);
                if (it != cfg.faces.end()) {
                    const cv_face_tex& t = it->second[q.face];
                    layer = t.layer;
                    orient = t.orientation;
                    alpha = (t.flags & CV_FACE_ALPHA_TEST) != 0;
                }
            }
            const bool translucent = !forced && (bf & CV_BLOCK_TRANSLUCENT) != 0;
            if (forced) alpha = false;
            store_u32(p, quad16_word0(static_cast<uint32_t>(ox + q.x), static_cast<uint32_t>(oy + q.y),
                                      static_cast<uint32_t>(oz + q.z), q.face));
            store_u32(p + 4, quad16_word1(q.w, q.h, orient, alpha, translucent, layer));
            store_u32(p + 8, quad16_word2(q.light));
            store_u32(p + 12, 0u);
            p += 16;
            quad_bounds(q, ox, oy, oz, bmin, bmax);
        }
        out.quad_count[list] = static_cast<int32_t>(quads[list].size());
    }
    if (out.quad_count[2] > 0) out.flags = static_cast<uint16_t>(out.flags | 2u);
    if (has_specials(w)) {
        mesh_models(w, out, bmin, bmax);
        mesh_fluids(w, out, bmin, bmax);
    }
    const uint16_t vis = cube_visibility(w);
    out.visibility = vis;
    vis_store(world, cx, cy, cz, w.pad.revision, w.generation, vis);
    const bool empty = out.quad_count[0] == 0 && out.quad_count[1] == 0 && out.quad_count[2] == 0 &&
                       out.water_count == 0 && out.stamp_vertex_count == 0;
    if (empty) {
        out.flags = static_cast<uint16_t>(out.flags | 1u);
    } else {
        for (int a = 0; a < 3; ++a) {
            out.aabb_min[a] = bmin[a];
            out.aabb_max[a] = bmax[a];
        }
    }
    return CV_OK;
}

int32_t mesh_cube(cv_world* world, int64_t cx, int64_t cy, int64_t cz, uint32_t flags, cv_mesh_out* out) {
    if (world == nullptr || out == nullptr || out->opaque_cap < 0 || out->cutout_cap < 0 || out->translucent_cap < 0 ||
        out->host_cap < 0 || (out->opaque_cap > 0 && out->opaque == nullptr) ||
        (out->cutout_cap > 0 && out->cutout == nullptr) || (out->translucent_cap > 0 && out->translucent == nullptr) ||
        (out->host_cap > 0 && out->host_cells == nullptr)) {
        cv_set_error("cv_mesh_cube: bad arguments");
        return CV_ERR_ARG;
    }
    out->opaque_count = out->cutout_count = out->translucent_count = out->host_count = 0;
    out->visibility = 0;
    out->flags_out = 0;
    MeshWork& w = mesh_work();
    const int32_t rc = meshref::mesh_prepare(world, cx, cy, cz, flags, w);
    if (rc != CV_OK) return rc;
    thread_local std::vector<cv_quad> quads[3];
    thread_local std::vector<cv_host_cell> host;
    for (auto& q : quads) q.clear();
    host.clear();
    meshref::mesh_cube_faces(w, (flags & CV_MESH_GREEDY) != 0, quads);
    meshref::mesh_host_cells(w, host);
    if (!w.cfg->face_rules.empty()) {
        for (cv_quad& q : quads[0]) q.face = static_cast<uint8_t>(q.face & 7u);   // internal forced-opaque mark
    }
    const uint16_t vis = cube_visibility(w);
    vis_store(world, cx, cy, cz, w.pad.revision, w.generation, vis);
    out->visibility = vis;
    const bool empty = quads[0].empty() && quads[1].empty() && quads[2].empty() && host.empty() && !has_specials(w);
    out->flags_out = empty ? 1u : 0u;

    int64_t need = 0;
    auto copy_list = [&](const auto& src, auto* dst, int32_t cap, int32_t* count) {
        const auto n = static_cast<int32_t>(src.size());
        *count = n;
        if (n > cap) {
            if (need == 0) need = n;
            return;
        }
        if (n > 0) std::memcpy(dst, src.data(), src.size() * sizeof(src[0]));
    };
    copy_list(quads[0], out->opaque, out->opaque_cap, &out->opaque_count);
    copy_list(quads[1], out->cutout, out->cutout_cap, &out->cutout_count);
    copy_list(quads[2], out->translucent, out->translucent_cap, &out->translucent_count);
    copy_list(host, out->host_cells, out->host_cap, &out->host_count);
    if (need != 0) return static_cast<int32_t>(CV_NEED(need));
    return CV_OK;
}

} // namespace cenda::voxel::meshref
