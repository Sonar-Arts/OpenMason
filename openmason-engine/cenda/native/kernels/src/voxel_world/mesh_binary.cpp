// Binary greedy cube mesher (OpenMason #143, Phase 1b).
//
// INPUT: the 34^3 padded cube (read_padded) classified once into u64 row masks (row = py * 34 + pz, bit px):
// see-through (air or CV_BLOCK_TRANSPARENT, and every fluid cell), opaque cubes, transparent cubes and
// "special" cells (non-cube with a model binding, fluids, CV_BLOCK_HOST_GEOMETRY).
//
// CULLING (mesher.cpp / MmsCcoAdapter.shouldRenderAgainst, unchanged):
//  - an opaque cube draws a face against a see-through neighbour;
//  - a transparent cube (cutout, or translucent) draws a face against air, or against a see-through
//    neighbour of a DIFFERENT id (leaves/ice/glass cull against their own kind and against opaque blocks);
//  - one-way shape rule (MmsFaceCullingService): a cube face is also drawn against a special neighbour whose
//    model does not cover the shared plane (cell_fills), whatever that block's TRANSPARENT flag says;
//  - cells of MISSING neighbour cubes read as air (known air and unknown alike), so a cube at the load front
//    shows its faces toward unloaded space; the camera pass hides them behind proxies of the unknown cubes
//    cv_visible_cubes reports (they are behind those proxies by construction).
//  - then the host's neighbour face rules (cv_face_rule_add, face_rule_kinds): CULL drops a surviving face,
//    OPAQUE moves it to the opaque list (kQuadForcedOpaque on the internal face byte, part of the merge key).
//
// LIGHT per face corner (mesher.cpp / VertexLightSampler, same float math): sky = fraction of the four
// columns around the corner whose surface y <= the corner's y (flat mode: one column), times the 3-tap AO
// 1 - 0.13 * n over CV_BLOCK_OPAQUE cells (both sides -> 3) in smooth mode; quantized to u8 as MmsQuadCodec.
// vertex_light() is that rule for one vertex; the face pass evaluates it BIT-SLICED (2026-11-17, output
// byte-identical to the per-vertex version): per face direction and slice, the 33 vertices of a vertex row
// are bits of u64 masks — four sky masks from per-column threshold tables, three AO taps from opacity rows
// (transposed for faces 4-5) — summed by bit-sliced adders into five canonical class planes (see
// "bit-sliced corner light" below); classes index the same u8 table.
//
// MERGE: per face direction and slice, faces whose four corner bytes are equal are grouped by (id, light)
// in first-appearance order and merged with bit ops (width = run of set bits from the lowest, then grown row
// by row while the whole run is set — cgerikj/binary-greedy-meshing); extents up to 32 (QUAD16 5-bit w/h).
// Faces with unequal corners are emitted 1x1 first (scan order): a merged rectangle is pixel-identical to
// its parts only when the light field is constant. "Four corners equal" is mask algebra on the class planes;
// adjacent equal faces share two corners, hence one light per run.
//
// OUTPUT: cv_quad lists (cv_mesh_cube) or, for build_gpu_mesh, QUAD16 records packed in the same pass
// (mesh_cube_faces_gpu) — the bytes packing the cv_quad lists gives.
#include "mesh_internal.hpp"
#include "cv_error.hpp"

#include <array>
#include <bit>
#include <climits>
#include <cstddef>
#include <cstring>

namespace cenda::voxel {

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
            if ((f & CV_BLOCK_TRANSLUCENT) != 0) c |= kClsTranslucentCube;
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

// True when all 34 ids of the row are equal (SWAR on little-endian targets).
inline bool row_uniform(const uint16_t* row) {
    if constexpr (std::endian::native == std::endian::little) {
        const uint64_t b = 0x0001000100010001ull * row[0];
        uint64_t d = 0;
        for (int x = 0; x < 32; x += 4) {
            uint64_t a = 0;
            std::memcpy(&a, row + x, sizeof a);
            d |= a ^ b;
        }
        return d == 0 && row[32] == row[0] && row[33] == row[0];
    } else {
        for (int x = 1; x < kPad; ++x) {
            if (row[x] != row[0]) return false;
        }
        return true;
    }
}

// Bit x (1..33) set where row[x] != row[x - 1], bit 0 always: the starts of the row's id runs. SWAR over four
// u16 lanes per u64 on little-endian targets (all supported ones), scalar elsewhere.
inline uint64_t run_starts(const uint16_t* row) {
    uint64_t m = 1;
    if constexpr (std::endian::native == std::endian::little) {
        constexpr uint64_t kLow15 = 0x7FFF7FFF7FFF7FFFull, kHigh = 0x8000800080008000ull;
        for (int x = 1; x < 33; x += 4) {   // lanes x..x+3 against x-1..x+2
            uint64_t a = 0, b = 0;
            std::memcpy(&a, row + x, sizeof a);
            std::memcpy(&b, row + x - 1, sizeof b);
            const uint64_t d = a ^ b;
            const uint64_t t = (((d & kLow15) + kLow15) | d) & kHigh;   // bit 15 of every nonzero lane
            // lane bits 0/16/32/48 -> 48..51 (no two partial products meet, so no carries)
            m |= ((((t >> 15) * 0x0001000200040008ull) >> 48) & 0xFu) << x;
        }
        m |= static_cast<uint64_t>(row[33] != row[32]) << 33;
    } else {
        for (int x = 1; x < kPad; ++x) m |= static_cast<uint64_t>(row[x] != row[x - 1]) << x;
    }
    return m;
}

static_assert(kClsSee == 1 && kClsOpaqueCube == 2 && kClsTranspCube == 4 && kClsSpecial == 8 && kClsVisClosed == 16,
              "classify() reads the class bits by position");

void classify(MeshWork& w) {
    const PaddedCube& p = w.pad;
    for (int py = 0; py < kPad; ++py) {
        for (int pz = 0; pz < kPad; ++pz) {
            const int r = py * kPad + pz;
            const bool interior = py >= 1 && py <= 32 && pz >= 1 && pz <= 32;
            if (p.occupancy[r] == 0) {   // all air
                w.id_runs[r] = 1;
                w.see[r] = kFull34;
                w.opaque_cube[r] = w.transp_cube[r] = w.special[r] = 0;
                if (interior) w.closed[(py - 1) * 32 + (pz - 1)] = 0;
                continue;
            }
            // Class masks run by run (terrain rows hold a handful of id runs).
            const uint16_t* row = p.ids + pad_index(0, py, pz);
            uint64_t see = 0, oc = 0, tc = 0, sp = 0, vc = 0;
            // a fully occupied row is often one id (buried stone, a dirt band): one run without the SWAR scan
            const uint64_t runs = p.occupancy[r] == kFull34 && row_uniform(row) ? uint64_t{1} : run_starts(row);
            w.id_runs[r] = runs;
            for (uint64_t starts = runs; starts != 0;) {
                const int x0 = std::countr_zero(starts);
                starts &= starts - 1;
                const uint64_t end = starts != 0 ? (starts & (0 - starts)) : (uint64_t{1} << kPad);
                const uint64_t run = (end - 1) & ~((uint64_t{1} << x0) - 1);
                const uint8_t c = w.cls[row[x0]];
                see |= run & (0 - static_cast<uint64_t>(c & kClsSee));
                oc |= run & (0 - static_cast<uint64_t>((c >> 1) & 1u));
                tc |= run & (0 - static_cast<uint64_t>((c >> 2) & 1u));
                sp |= run & (0 - static_cast<uint64_t>((c >> 3) & 1u));
                vc |= run & (0 - static_cast<uint64_t>((c >> 4) & 1u));
            }
            w.see[r] = see;
            w.opaque_cube[r] = oc;
            w.transp_cube[r] = tc;
            w.special[r] = sp;
            if (interior) w.closed[(py - 1) * 32 + (pz - 1)] = static_cast<uint32_t>((vc >> 1) & 0xFFFFFFFFu);
        }
    }
}

// Bit x (0..33) set where a[x] != b[x] (SWAR as run_starts).
inline uint64_t row_differs(const uint16_t* a, const uint16_t* b) {
    uint64_t m = 0;
    if constexpr (std::endian::native == std::endian::little) {
        constexpr uint64_t kLow15 = 0x7FFF7FFF7FFF7FFFull, kHigh = 0x8000800080008000ull;
        for (int x = 0; x < 32; x += 4) {
            uint64_t p = 0, q = 0;
            std::memcpy(&p, a + x, sizeof p);
            std::memcpy(&q, b + x, sizeof q);
            const uint64_t d = p ^ q;
            const uint64_t t = (((d & kLow15) + kLow15) | d) & kHigh;
            m |= ((((t >> 15) * 0x0001000200040008ull) >> 48) & 0xFu) << x;
        }
        m |= (static_cast<uint64_t>(a[32] != b[32]) << 32) | (static_cast<uint64_t>(a[33] != b[33]) << 33);
    } else {
        for (int x = 0; x < kPad; ++x) m |= static_cast<uint64_t>(a[x] != b[x]) << x;
    }
    return m;
}

// Face bits per direction: rows[f][ly * 32 + lz], bit lx; nz[f][s] bit v = row (s, v) of the face plane is not
// empty for faces 0-3 (plane rows: 0-1 s = ly, v = lz; 2-3 s = lz, v = ly; 4-5 are transposed later).
void face_masks(MeshWork& w, uint32_t (*rows)[1024], uint32_t (*nz)[32]) {
    std::memset(nz, 0, 6 * 32 * sizeof(uint32_t));
    const uint64_t* occ = w.pad.occupancy;
    const uint64_t* see = w.see;
    const uint64_t* spc = w.special;
    constexpr int kNb[4] = {kPad, -kPad, -1, 1};   // row offsets of faces 0-3 (+y, -y, -z, +z)
    uint32_t any4 = 0, any5 = 0;
    for (int py = 1; py <= 32; ++py) {
        uint32_t nz0 = 0, nz1 = 0;
        for (int pz = 1; pz <= 32; ++pz) {
            const int r = py * kPad + pz;
            const int out = (py - 1) * 32 + (pz - 1);
            const uint64_t a = w.opaque_cube[r], b = w.transp_cube[r];
            if ((a | b) == 0) {
                for (int f = 0; f < 6; ++f) rows[f][out] = 0;
                continue;
            }
            const uint64_t nsee[6] = {see[r + kPad], see[r - kPad], see[r - 1], see[r + 1], see[r] >> 1, see[r] << 1};
            uint64_t face[6];
            for (int f = 0; f < 6; ++f) face[f] = a & nsee[f];
            // A transparent cube shows a face toward air or toward a different id: x neighbours from the row's
            // id runs, y/z neighbours by comparing the two id rows (a few candidates: one by one).
            if (b != 0) {
                const uint16_t* row = w.pad.ids + r * kPad;
                for (int f = 0; f < 4; ++f) {
                    const uint64_t cand = b & nsee[f] & kInner;
                    if (cand == 0) continue;
                    const int nr = r + kNb[f];
                    const uint16_t* nrow = w.pad.ids + nr * kPad;
                    if (std::popcount(cand) <= 2) {
                        for (uint64_t c = cand; c != 0; c &= c - 1) {
                            const int x = std::countr_zero(c);
                            if (((occ[nr] >> x) & 1u) == 0 || nrow[x] != row[x]) face[f] |= uint64_t{1} << x;
                        }
                    } else {
                        face[f] |= cand & (~occ[nr] | row_differs(row, nrow));
                    }
                }
                face[4] |= b & nsee[4] & kInner & ((~occ[r] >> 1) | (w.id_runs[r] >> 1));
                face[5] |= b & nsee[5] & kInner & ((~occ[r] << 1) | w.id_runs[r]);
            }
            // One-way shape rule: a special neighbour that does not cover the shared plane.
            const uint64_t nsp[6] = {spc[r + kPad], spc[r - kPad], spc[r - 1], spc[r + 1], spc[r] >> 1, spc[r] << 1};
            if (((a | b) & kInner & (nsp[0] | nsp[1] | nsp[2] | nsp[3] | nsp[4] | nsp[5])) != 0) {
                for (int f = 0; f < 6; ++f) {
                    uint64_t shaped = (a | b) & ~nsee[f] & nsp[f] & kInner;
                    while (shaped != 0) {
                        const int x = std::countr_zero(shaped);
                        shaped &= shaped - 1;
                        if (!cell_fills(w, x + kFaceDx[f], py + kFaceDy[f], pz + kFaceDz[f], kOpposite[f])) {
                            face[f] |= uint64_t{1} << x;
                        }
                    }
                }
            }
            uint32_t fr[6];
            for (int f = 0; f < 6; ++f) rows[f][out] = fr[f] = static_cast<uint32_t>((face[f] >> 1) & 0xFFFFFFFFu);
            const int ly = py - 1, lz = pz - 1;
            nz0 |= (fr[0] != 0 ? 1u : 0u) << lz;
            nz1 |= (fr[1] != 0 ? 1u : 0u) << lz;
            nz[2][lz] |= (fr[2] != 0 ? 1u : 0u) << ly;
            nz[3][lz] |= (fr[3] != 0 ? 1u : 0u) << ly;
            any4 |= fr[4];
            any5 |= fr[5];
        }
        nz[0][py - 1] = nz0;
        nz[1][py - 1] = nz1;
    }
    nz[4][0] = any4;   // faces 4-5: "any" only (their nz comes with the transpose)
    nz[5][0] = any5;
}

// The equal-light faces of one slice grouped by key (forced-opaque << 24 | id << 8 | light) in
// first-appearance order. Each key keeps its rows as a chain of (v, mask) nodes in increasing v (rows are
// added in increasing v), so a slice costs what its faces cost, not 32 rows per key.
struct MergeNode {
    uint32_t mask;   // bit u
    int16_t next;    // -1: end of chain
    uint8_t v;
};
struct MergeEntry {
    uint32_t key;
    int16_t head, tail;
};
struct MergeTable {
    static constexpr int kSlots = 2048;   // a slice has at most 1024 faces, so at most 1024 keys and nodes
    MergeEntry entries[1024];
    MergeNode nodes[1024];
    int16_t slots[kSlots];
    uint16_t used[1024];
    int n_entries = 0, n_nodes = 0, n_used = 0;
    MergeTable() { std::memset(slots, 0xFF, sizeof slots); }
    // Index of the entry of key (appended on first use).
    int index(uint32_t key) {
        uint32_t h = (key * 2654435761u) >> 21;
        for (;; h = (h + 1) & (kSlots - 1)) {
            const int16_t s = slots[h];
            if (s < 0) {
                slots[h] = static_cast<int16_t>(n_entries);
                used[n_used++] = static_cast<uint16_t>(h);
                entries[n_entries] = MergeEntry{key, -1, -1};
                return n_entries++;
            }
            if (entries[s].key == key) return s;
        }
    }
    void add(int e, int v, uint32_t mask) {
        MergeEntry& en = entries[e];
        if (en.tail >= 0 && nodes[en.tail].v == v) {
            nodes[en.tail].mask |= mask;
            return;
        }
        const auto n = static_cast<int16_t>(n_nodes++);
        nodes[n] = MergeNode{mask, -1, static_cast<uint8_t>(v)};
        if (en.tail >= 0) nodes[en.tail].next = n;
        else en.head = n;
        en.tail = n;
    }
    void reset() {
        for (int i = 0; i < n_used; ++i) slots[used[i]] = -1;
        n_used = n_entries = n_nodes = 0;
    }
};


} // namespace

MeshWork& mesh_work() {
    thread_local std::unique_ptr<MeshWork> work;
    if (!work) work = std::make_unique<MeshWork>();
    return *work;
}

const int64_t* MeshWork::sky_ring() {
    if (!ring_ready) {
        surface_ring(world, cx, cz, ring);
        ring_ready = true;
    }
    return ring;
}

const Model* MeshWork::model_at(int px, int py, int pz) const {
    const uint16_t id = id_at(px, py, pz);
    const Binding* b = cfg->binding(id);
    if (b == nullptr) return nullptr;
    if ((cls[id] & (kClsOpaqueCube | kClsTranspCube | kClsAir)) != 0 || cfg->is_fluid(id)) return nullptr;
    return cfg->model_for(id, b->layer >= 0 ? layer_at(b->layer, px, py, pz) : uint16_t{0});
}

bool cell_fills(const MeshWork& w, int px, int py, int pz, int face) {
    const uint16_t id = w.id_at(px, py, pz);
    const uint8_t c = w.cls[id];
    if ((c & kClsAir) != 0) return false;
    if ((c & (kClsOpaqueCube | kClsTranspCube)) != 0) return true;
    if (w.cfg->is_fluid(id)) return false;
    const Model* m = w.model_at(px, py, pz);
    if (m != nullptr) return ((m->fills >> face) & 1u) != 0;
    return true;   // unrecognised shape (StampFaceOcclusionPolicy default)
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

// ---- bit-sliced corner light (2026-11-17) ----------------------------------------------------------------------
// The light of a face corner is a function of (vertex, face direction) only (vertex_light). For one face
// direction and one slice the vertices form a 33x33 grid: row v, bit u. Every input of vertex_light is a row
// mask over that grid:
//  - sky sample k is lit when y0 + sample y >= the sample column's surface. Along a row the sample y is
//    constant (u never runs along y) and the column runs along x (faces 0-3) or z (faces 4-5), so the test
//    is one bit of a per-column threshold table: sky_z[zp][t + 1] bit xp = (y0 + t >= ring[zp][xp]) and
//    sky_x[xp][t + 1] bit zp (the same, transposed), t = sample y - y0 in [-1, 32];
//  - an AO tap is an opacity row (faces 0-3: opacity[py][pz] bit px) or a transposed one (faces 4-5:
//    opq_t[px][py] bit pz), shifted so bit u is the tap of vertex u.
// Bit-sliced adders turn the four sky bits into the lit count (3 planes) and the three taps into the AO
// count (2 planes, side1 && side2 -> 3); the 5-bit class indexes the u8 table vertex_light reads. Classes
// are canonical (lit 0 -> AO count 0: every lit-0 class quantizes to 0, and the other 16 classes give 16
// distinct bytes), so "four corner classes equal" == "four corner bytes equal" and the merge test is plain
// mask algebra; corner bytes are only materialised for faces whose corners differ.
constexpr int kSmoothPlanes = 5;

struct LightLut {
    uint8_t smooth[32] = {};
    uint8_t flat[2] = {};
    LightLut() {
        for (int c = 0; c < 32; ++c) {
            const int lit = c & 7, count = c >> 3;
            smooth[c] = lit <= 4 ? kLight.smooth[lit][count] : uint8_t{0};
        }
        flat[0] = kLight.flat[0];
        flat[1] = kLight.flat[1];
    }
};
const LightLut kLut;

// Plane geometry of face direction f: u / v / normal axes, the vertex offset along the normal, and the
// (du, dv) of each corner in kFaceOffsets order.
struct FaceGeom {
    int ua, va, na, noff;
    int du[4], dv[4];
};
struct FaceGeoms {
    FaceGeom g[6];
};
constexpr FaceGeoms make_geoms() {
    FaceGeoms r{};
    for (int f = 0; f < 6; ++f) {
        FaceGeom& x = r.g[f];
        x.ua = kUAxis[f];
        x.va = kVAxis[f];
        x.na = 3 - x.ua - x.va;
        x.noff = kFaceOffsets[f][0][x.na];
        for (int c = 0; c < 4; ++c) {
            x.du[c] = kFaceOffsets[f][c][x.ua];
            x.dv[c] = kFaceOffsets[f][c][x.va];
        }
    }
    return r;
}
constexpr FaceGeoms kGeom = make_geoms();

// a[r] bit c -> a[c] bit r (Hacker's Delight block swaps, LSB = column 0).
void transpose32(uint32_t* a) {
    uint32_t m = 0x0000FFFFu;
    for (int j = 16; j != 0; j >>= 1, m ^= (m << j)) {
        for (int k = 0; k < 32; k = (k + j + 1) & ~j) {
            const uint32_t t = ((a[k] >> j) ^ a[k + j]) & m;
            a[k] ^= t << j;
            a[k + j] ^= t;
        }
    }
}


struct LightPlanes {
    uint64_t sky_z[kPadRows];   // [zp * 34 + t + 1] bit xp
    uint64_t sky_x[kPadRows];   // [xp * 34 + t + 1] bit zp
    uint64_t opq_t[kPadRows];   // [px * 34 + py] bit pz
    uint64_t cls[kPad][8];      // class planes of the current slice: [vertex row][plane]
};

// Sky threshold tables of the cube from the surface ring (both orientations when `both`).
void build_sky(const MeshWork& w, const int64_t* ring, bool both, LightPlanes& lp) {
    uint8_t th[kPadRows];   // first t + 1 at which the column is lit; 34 = never (t <= 32)
    const int64_t y0 = w.y0;
    for (int i = 0; i < kPadRows; ++i) {
        const int64_t r = ring[i];
        if (r < y0) {
            th[i] = 0;
        } else {
            const uint64_t d = static_cast<uint64_t>(r) - static_cast<uint64_t>(y0);   // no overflow: r >= y0
            th[i] = d > 32 ? uint8_t{34} : static_cast<uint8_t>(d + 1);
        }
    }
    uint64_t bucket[kPad + 1];
    for (int zp = 0; zp < kPad; ++zp) {
        std::memset(bucket, 0, sizeof bucket);
        for (int xp = 0; xp < kPad; ++xp) bucket[th[zp * kPad + xp]] |= uint64_t{1} << xp;
        uint64_t acc = 0;
        for (int t = 0; t < kPad; ++t) lp.sky_z[zp * kPad + t] = acc |= bucket[t];
    }
    if (!both) return;
    for (int xp = 0; xp < kPad; ++xp) {
        std::memset(bucket, 0, sizeof bucket);
        for (int zp = 0; zp < kPad; ++zp) bucket[th[zp * kPad + xp]] |= uint64_t{1} << zp;
        uint64_t acc = 0;
        for (int t = 0; t < kPad; ++t) lp.sky_x[xp * kPad + t] = acc |= bucket[t];
    }
}

// opq_t[px * 34 + py] bit pz = opacity[py * 34 + pz] bit px: per py slab a 32x32 core transpose plus the
// two edge rows / columns (uniform slabs directly).
void build_opacity_t(const MeshWork& w, LightPlanes& lp) {
    constexpr uint64_t kFull = (uint64_t{1} << kPad) - 1;
    uint32_t core[32];
    for (int py = 0; py < kPad; ++py) {
        const uint64_t* rows = w.pad.opacity + py * kPad;
        uint64_t any = 0, all = kFull;
        for (int pz = 0; pz < kPad; ++pz) {
            any |= rows[pz];
            all &= rows[pz];
        }
        if (any == 0 || all == kFull) {
            const uint64_t v = any == 0 ? 0 : kFull;
            for (int px = 0; px < kPad; ++px) lp.opq_t[px * kPad + py] = v;
            continue;
        }
        for (int pz = 0; pz < 32; ++pz) core[pz] = static_cast<uint32_t>(rows[pz]);
        transpose32(core);
        const uint64_t e32 = rows[32], e33 = rows[33];
        for (int px = 0; px < 32; ++px) {
            lp.opq_t[px * kPad + py] = core[px] | (((e32 >> px) & 1u) << 32) | (((e33 >> px) & 1u) << 33);
        }
        uint64_t t32 = 0, t33 = 0;
        for (int pz = 0; pz < kPad; ++pz) {
            t32 |= ((rows[pz] >> 32) & 1u) << pz;
            t33 |= ((rows[pz] >> 33) & 1u) << pz;
        }
        lp.opq_t[32 * kPad + py] = t32;
        lp.opq_t[33 * kPad + py] = t33;
    }
}

// Where each light input of a vertex row lives: index = c + s * slice + v * row into the sky table
// (sky_z for faces 0-3, sky_x for 4-5) or the AO table (pad.opacity for 0-3, opq_t for 4-5), shifted right
// by `shift` so bit u is vertex u. Linear in (s, v) by construction; compile-time constants.
struct SampleAt {
    int32_t c, s, v, shift;
};
struct FaceSamples {
    SampleAt sky[4];
    SampleAt ao[3];
};
// vertex_light's sample offsets from the vertex (kLight.sky / kLight.ao), as constants.
constexpr void sky_offset(int f, int k, int* o) {
    const int a = k < 2 ? -1 : 0, b = (k & 1) == 0 ? -1 : 0;
    switch (f) {
        case 0: o[0] = a; o[1] = 0; o[2] = b; break;
        case 1: o[0] = a; o[1] = -1; o[2] = b; break;
        case 2: o[0] = a; o[1] = b; o[2] = -1; break;
        case 3: o[0] = a; o[1] = b; o[2] = 0; break;
        case 4: o[0] = 0; o[1] = a; o[2] = b; break;
        default: o[0] = -1; o[1] = a; o[2] = b; break;
    }
}
constexpr void ao_offset(int f, int j, int* o) {
    for (int i = 0; i < 3; ++i) {
        o[i] = kAoN[f][i] + (j != 1 ? kAoT1[f][i] : 0) + (j != 0 ? kAoT2[f][i] : 0);
    }
}
struct SampleTables {
    FaceSamples by_face[6];
};
constexpr SampleAt sample_at(bool sky, int f, int k, int s, int v) {
    const FaceGeom& g = kGeom.g[f];
    int pos[3] = {0, 0, 0};
    pos[g.na] = s + g.noff;
    pos[g.va] = v;
    int o[3] = {0, 0, 0};
    if (sky) {
        sky_offset(f, k, o);
        const int t1 = pos[1] + o[1] + 1;
        if (g.ua == 0) return SampleAt{(pos[2] + o[2] + 1) * kPad + t1, 0, 0, o[0] + 1};
        return SampleAt{(pos[0] + o[0] + 1) * kPad + t1, 0, 0, o[2] + 1};
    }
    ao_offset(f, k, o);
    if (g.ua == 0) return SampleAt{(pos[1] + o[1] + 1) * kPad + (pos[2] + o[2] + 1), 0, 0, o[0] + 1};
    return SampleAt{(pos[0] + o[0] + 1) * kPad + (pos[1] + o[1] + 1), 0, 0, o[2] + 1};
}
constexpr SampleAt sample_linear(bool sky, int f, int k) {
    const SampleAt a = sample_at(sky, f, k, 0, 0), b = sample_at(sky, f, k, 1, 0), c = sample_at(sky, f, k, 0, 1);
    return SampleAt{a.c, b.c - a.c, c.c - a.c, a.shift};
}
constexpr SampleTables make_samples() {
    SampleTables t{};
    for (int f = 0; f < 6; ++f) {
        for (int k = 0; k < 4; ++k) t.by_face[f].sky[k] = sample_linear(true, f, k);
        for (int j = 0; j < 3; ++j) t.by_face[f].ao[j] = sample_linear(false, f, j);
    }
    return t;
}
constexpr SampleTables kSamples = make_samples();

// Per-thread scratch of mesh_cube_faces.
struct FaceScratch {
    uint32_t rows[6][1024];
    uint32_t plane[32][32];      // faces 4-5: plane[lx][ly] bit lz (rows of plane[s] not flagged in nz are stale)
    uint32_t nz[6][32];          // nz[f][s] bit v: face plane row (s, v) is not empty
    MergeTable table;
    LightPlanes lp;
    cv_quad buf[3][1024];        // one slice's quads per list (a slice has at most 1024 faces)
    uint32_t gbuf[3][1024 * 4];  // the same as QUAD16 records (GPU mode)
    // QUAD16 word1 without w/h per (id, face), direct-mapped by id, valid for one build (stamp).
    uint64_t w1_tag[256] = {};
    uint32_t w1_base[256][6];
    uint64_t stamp = 0;
};

// Where mesh_direction puts its quads: cv_quad lists (cv_mesh_cube) or QUAD16 records straight into a
// GpuMeshBuild (build_gpu_mesh) with the region offsets, plus the records' integer bounds.
struct FaceOut {
    std::vector<cv_quad>* quads = nullptr;
    GpuMeshBuild* gpu = nullptr;
    int ox = 0, oy = 0, oz = 0;
    int lo[3] = {INT_MAX, INT_MAX, INT_MAX};
    int hi[3] = {INT_MIN, INT_MIN, INT_MIN};
};

inline uint32_t le32(uint32_t v) {
    if constexpr (std::endian::native == std::endian::little) return v;
    else return std::byteswap(v);
}

// QUAD16 word1 bits other than w/h for (id, face), as the packer always wrote them: texture layer and
// orientation (cv_block_faces_set, else layer = id), alpha test (face flag, else TRANSPARENT && !TRANSLUCENT),
// translucent = CV_BLOCK_TRANSLUCENT. A forced-opaque face clears alpha and translucent (& ~0x180).
const uint32_t* word1_bases(const MeshWork& w, FaceScratch& sc, uint16_t id) {
    const int e = id & 255;
    const uint64_t tag = (sc.stamp << 16) | id;
    if (sc.w1_tag[e] != tag) {
        sc.w1_tag[e] = tag;
        const MeshConfig& cfg = *w.cfg;
        const uint16_t bf = w.flags[id];
        const std::array<cv_face_tex, 6>* tex = nullptr;
        if (!cfg.faces.empty()) {
            auto it = cfg.faces.find(id);
            if (it != cfg.faces.end()) tex = &it->second;
        }
        for (size_t f = 0; f < 6; ++f) {
            uint32_t layer = id, orient = 0;
            bool alpha = (bf & CV_BLOCK_TRANSPARENT) != 0 && (bf & CV_BLOCK_TRANSLUCENT) == 0;
            if (tex != nullptr) {
                const cv_face_tex& t = (*tex)[f];
                layer = t.layer;
                orient = t.orientation;
                alpha = (t.flags & CV_FACE_ALPHA_TEST) != 0;
            }
            const bool translucent = (bf & CV_BLOCK_TRANSLUCENT) != 0;
            sc.w1_base[e][f] = quad16_word1(1, 1, orient, alpha, translucent, layer);   // w = h = 1: no w/h bits
        }
    }
    return sc.w1_base[e];
}

template <bool Smooth>
inline uint32_t class_at(const uint64_t* row, int u) {
    if constexpr (!Smooth) {
        return static_cast<uint32_t>((row[0] >> u) & 1u);
    } else {
        return static_cast<uint32_t>((row[0] >> u) & 1u) | static_cast<uint32_t>(((row[1] >> u) & 1u) << 1) |
               static_cast<uint32_t>(((row[2] >> u) & 1u) << 2) | static_cast<uint32_t>(((row[3] >> u) & 1u) << 3) |
               static_cast<uint32_t>(((row[4] >> u) & 1u) << 4);
    }
}

// The u8 lights of the four corners of face u (vertex rows r0 = v, r1 = v + 1) as a cv_quad light word
// (corner c in byte c). Each plane contributes a 2x2 window (bit du + 2 dv) at bit 5 * plane; window
// corner j's class is then gathered with one multiply (bits 5b -> 16 + b; no two partial products land
// on the same bit, so there are no carries).
template <bool Smooth, int J0, int J1, int J2, int J3>
inline uint32_t corner_lights(const uint64_t* r0, const uint64_t* r1, int u, const uint8_t* lut) {
    // The windows are cut at u in place and shifted down once (bits 5b + u + 0..3 stay below 64 for u <= 32).
    const uint64_t m = uint64_t{3} << u;
    uint64_t wide = 0;
    constexpr int kNp = Smooth ? kSmoothPlanes : 1;
    for (int b = 0; b < kNp; ++b) wide |= ((r0[b] & m) | ((r1[b] & m) << 2)) << (5 * b);
    const auto x = static_cast<uint32_t>(wide >> u);
    auto light = [&](int j) -> uint32_t {
        if constexpr (!Smooth) return lut[(x >> j) & 1u];
        else return lut[((((x >> j) & 0x108421u) * 0x11111u) >> 16) & 31u];
    };
    return light(J0) | (light(J1) << 8) | (light(J2) << 16) | (light(J3) << 24);
}

// One cv_quad as two stores (x y z face w h id | light word).
inline void put_quad(cv_quad* dst, int x, int y, int z, int face, int w, int h, uint16_t id, uint32_t light) {
    static_assert(sizeof(cv_quad) == 12 && offsetof(cv_quad, id) == 6 && offsetof(cv_quad, light) == 8,
                  "cv_quad layout");
    if constexpr (std::endian::native == std::endian::little) {
        const uint64_t head = static_cast<uint64_t>(x) | (static_cast<uint64_t>(y) << 8) |
                              (static_cast<uint64_t>(z) << 16) | (static_cast<uint64_t>(face) << 24) |
                              (static_cast<uint64_t>(w) << 32) | (static_cast<uint64_t>(h) << 40) |
                              (static_cast<uint64_t>(id) << 48);
        auto* p = reinterpret_cast<unsigned char*>(dst);
        std::memcpy(p, &head, 8);
        std::memcpy(p + 8, &light, 4);
    } else {
        dst->x = static_cast<uint8_t>(x);
        dst->y = static_cast<uint8_t>(y);
        dst->z = static_cast<uint8_t>(z);
        dst->face = static_cast<uint8_t>(face);
        dst->w = static_cast<uint8_t>(w);
        dst->h = static_cast<uint8_t>(h);
        dst->id = id;
        for (int c = 0; c < 4; ++c) dst->light[c] = static_cast<uint8_t>(light >> (8 * c));
    }
}

// One face direction: slices -> class planes -> faces whose corners differ (straight to the lists) and
// equal-corner faces (merge table) -> the lists, slice by slice in the order of the scalar mesher.
template <int F, bool Smooth, bool Gpu>
void mesh_direction(const MeshWork& w, FaceScratch& sc, bool greedy, bool rules, FaceOut& fo) {
    constexpr int kNp = Smooth ? kSmoothPlanes : 1;
    constexpr int kUStride = F <= 3 ? 1 : kPad;
    constexpr int kVStride = F <= 1 ? kPad : kPad * kPad;
    constexpr int kSStride = F <= 1 ? kPad * kPad : (F <= 3 ? kPad : 1);
    constexpr int kU = kUAxis[F], kV = kVAxis[F];
    constexpr int kDu0 = kFaceOffsets[F][0][kU], kDu1 = kFaceOffsets[F][1][kU], kDu2 = kFaceOffsets[F][2][kU],
                  kDu3 = kFaceOffsets[F][3][kU];
    constexpr int kDv0 = kFaceOffsets[F][0][kV], kDv1 = kFaceOffsets[F][1][kV], kDv2 = kFaceOffsets[F][2][kV],
                  kDv3 = kFaceOffsets[F][3][kV];
    constexpr int kJ0 = kDu0 + 2 * kDv0, kJ1 = kDu1 + 2 * kDv1, kJ2 = kDu2 + 2 * kDv2, kJ3 = kDu3 + 2 * kDv3;
    const MeshConfig& cfg = *w.cfg;
    const uint8_t* lut = Smooth ? kLut.smooth : kLut.flat;
    constexpr FaceSamples smp = kSamples.by_face[F];
    const uint64_t* sky = F <= 3 ? sc.lp.sky_z : sc.lp.sky_x;
    const uint64_t* aot = F <= 3 ? w.pad.opacity : sc.lp.opq_t;
    const uint16_t* ids = w.pad.ids;
    const uint8_t* cls = w.cls;
    LightPlanes& lp = sc.lp;
    MergeTable& table = sc.table;
    int n[3] = {0, 0, 0};
    uint64_t hdiff[kPad];
    uint32_t w1_id = 0x10000u, w1_base = 0;   // last word1_bases lookup (GPU mode)   // per vertex row of the current slice: horizontal class changes
    // Integer bounds of this slice's QUAD16 records (GPU mode): in-plane ranges, folded in per slice.
    int umin = INT_MAX, umax = INT_MIN, vmin = INT_MAX, vmax = INT_MIN;
    // One quad (min cell (u, v) of slice s, w along u, h along v) into the slice buffer of `list`.
    auto emit = [&](int list, int sl, int u, int v, bool forced, int qw, int qh, uint16_t id, uint32_t light) {
        const int lx = F <= 3 ? u : sl;
        const int ly = F <= 1 ? sl : v;
        const int lz = F <= 1 ? v : (F <= 3 ? sl : u);
        if constexpr (!Gpu) {
            put_quad(&sc.buf[list][n[list]++], lx, ly, lz, forced ? (F | kQuadForcedOpaque) : F, qw, qh, id, light);
        } else {
            uint32_t* r = sc.gbuf[list] + 4 * n[list]++;
            const uint32_t hm = static_cast<uint32_t>(qh - 1), wm = static_cast<uint32_t>(qw - 1);
            // quad16_word0, inlined (it lives in mesh_pack.cpp)
            r[0] = le32((static_cast<uint32_t>(fo.ox + lx) & 0xFFu) | ((static_cast<uint32_t>(fo.oy + ly) & 0x3FFu) << 8) |
                        ((static_cast<uint32_t>(fo.oz + lz) & 0xFFu) << 18) | (uint32_t{F} << 26));
            if (id != w1_id) {   // quads of one id come in runs
                w1_id = id;
                w1_base = word1_bases(w, sc, id)[F];
            }
            r[1] = le32((w1_base & (forced ? ~0x180u : ~0u)) | (hm & 15u) | ((wm & 31u) << 25) |
                        (((hm >> 4) & 1u) << 30));
            r[2] = le32(light);
            r[3] = 0;
            umin = std::min(umin, u);
            umax = std::max(umax, u + qw);
            vmin = std::min(vmin, v);
            vmax = std::max(vmax, v + qh);
        }
    };
    for (int s = 0; s < 32; ++s) {
        const uint32_t nz = sc.nz[F][s];
        if (nz == 0) continue;
        // Class planes of the vertex rows the slice's faces touch.
        int32_t sk[4], ao[3];
        for (int k = 0; k < 4; ++k) sk[k] = smp.sky[k].c + smp.sky[k].s * s;
        for (int j = 0; j < 3; ++j) ao[j] = smp.ao[j].c + smp.ao[j].s * s;
        for (uint64_t need = uint64_t{nz} | (uint64_t{nz} << 1); need != 0; need &= need - 1) {
            const int v = std::countr_zero(need);
            uint64_t* row = lp.cls[v];
            if constexpr (!Smooth) {
                row[0] = sky[sk[3] + smp.sky[3].v * v] >> smp.sky[3].shift;
            } else {
                const uint64_t l0 = sky[sk[0] + smp.sky[0].v * v] >> smp.sky[0].shift;
                const uint64_t l1 = sky[sk[1] + smp.sky[1].v * v] >> smp.sky[1].shift;
                const uint64_t l2 = sky[sk[2] + smp.sky[2].v * v] >> smp.sky[2].shift;
                const uint64_t l3 = sky[sk[3] + smp.sky[3].v * v] >> smp.sky[3].shift;
                const uint64_t s1 = aot[ao[0] + smp.ao[0].v * v] >> smp.ao[0].shift;
                const uint64_t s2 = aot[ao[1] + smp.ao[1].v * v] >> smp.ao[1].shift;
                const uint64_t cr = aot[ao[2] + smp.ao[2].v * v] >> smp.ao[2].shift;
                const uint64_t p = l0 ^ l1, q = l0 & l1, r = l2 ^ l3, t = l2 & l3;
                const uint64_t b0 = p ^ r, c0 = p & r;
                const uint64_t b1 = q ^ t ^ c0;
                const uint64_t b2 = (q & t) | (c0 & (q ^ t));
                const uint64_t both = s1 & s2, either = s1 | s2;
                const uint64_t lit = b0 | b1 | b2;
                row[0] = b0;
                row[1] = b1;
                row[2] = b2;
                row[3] = ((either ^ cr) | both) & lit;
                row[4] = ((either & cr) | both) & lit;
            }
            uint64_t hd = 0;   // bit u: vertices u and u + 1 of this row differ
            for (int b = 0; b < kNp; ++b) hd |= row[b] ^ (row[b] >> 1);
            hdiff[v] = hd;
        }
        const int sbase = pad_index(1, 1, 1) + s * kSStride;
        uint32_t eq[32];
        // Pass 1, in (v, u) order: faces whose corners differ (every face without greedy) go straight to the
        // lists with their four corner bytes; the others wait in eq[v] for the merge (same order as one pass:
        // the lists only receive pass-1 faces before this slice's merged rectangles).
        for (uint32_t vb = nz; vb != 0; vb &= vb - 1) {
            const int v = std::countr_zero(vb);
            const uint32_t bits = F <= 1 ? sc.rows[F][s * 32 + v] : (F <= 3 ? sc.rows[F][v * 32 + s] : sc.plane[s][v]);
            const uint64_t* r0 = lp.cls[v];
            const uint64_t* r1 = lp.cls[v + 1];
            uint32_t uneq = bits;
            if (greedy) {
                // corners equal <=> c00 == c10 (row v), c01 == c11 (row v + 1) and c00 == c01
                uint64_t diff = 0;
                for (int b = 0; b < kNp; ++b) diff |= r0[b] ^ r1[b];
                diff |= hdiff[v] | hdiff[v + 1];
                uneq = bits & static_cast<uint32_t>(diff);
                eq[v] = bits & ~uneq;
            }
            const int vbase = sbase + v * kVStride;
            for (; uneq != 0; uneq &= uneq - 1) {
                const int u = std::countr_zero(uneq);
                const int lx = F <= 3 ? u : s;
                const int ly = F <= 1 ? s : v;
                const int lz = F <= 1 ? v : (F <= 3 ? s : u);
                const uint16_t id = ids[vbase + u * kUStride];
                bool forced = false;   // neighbour face rules (cv_face_rule_add); 0 for ids without rules
                if (rules && ((static_cast<uint32_t>(cfg.rule_faces[id]) >> F) & 1u) != 0) {
                    const uint32_t k = face_rule_kinds(w, lx + 1, ly + 1, lz + 1, F);
                    if ((k & CV_FACE_RULE_CULL_WHEN_NEIGHBOUR_IN) != 0) continue;
                    forced = (k & CV_FACE_RULE_OPAQUE_WHEN_NEIGHBOUR_IN) != 0;
                }
                const uint8_t c = cls[id];
                const int list = forced ? 0 : ((c >> 2) & 1) + ((c >> 6) & 1);
                emit(list, s, u, v, forced, 1, 1, id, corner_lights<Smooth, kJ0, kJ1, kJ2, kJ3>(r0, r1, u, lut));
            }
        }
        if (greedy) {
            // Pass 2: equal-corner faces grouped by (forced, id, light) in first-appearance order. Inside a
            // run of adjacent equal faces the class cannot change (neighbours share two corners).
            table.reset();
            uint32_t last_key = ~0u;
            int last = 0;
            auto add = [&](int v, uint32_t mask, uint32_t key) {
                if (key != last_key) {
                    last = table.index(key);
                    last_key = key;
                }
                table.add(last, v, mask);
            };
            for (uint32_t vb = nz; vb != 0; vb &= vb - 1) {
                const int v = std::countr_zero(vb);
                const uint32_t e = eq[v];
                if (e == 0) continue;
                const int vbase = sbase + v * kVStride;
                const uint64_t* r0 = lp.cls[v];
                // Segments: maximal runs of adjacent equal faces with one id (one class each: adjacent equal
                // faces share two corners). Faces 0-3 run along x, where classify's id runs give the id
                // changes; faces 4-5 compare ids face by face. First appearances keep their order.
                uint32_t breaks;
                if constexpr (F <= 3) {
                    const int r = F <= 1 ? (s + 1) * kPad + (v + 1) : (v + 1) * kPad + (s + 1);
                    breaks = static_cast<uint32_t>(w.id_runs[r] >> 1);   // bit u: id(u) != id(u - 1)
                } else {
                    breaks = 0;
                    uint16_t prev = 0;
                    for (uint32_t m = e; m != 0; m &= m - 1) {
                        const int u = std::countr_zero(m);
                        const uint16_t id = ids[vbase + u * kUStride];
                        if (u > 0 && id != prev) breaks |= 1u << u;
                        prev = id;
                    }
                }
                uint32_t starts = (e & ~(e << 1)) | (e & breaks);
                while (starts != 0) {
                    const int u0 = std::countr_zero(starts);
                    starts &= starts - 1;
                    // segment = e from u0 up to the next start or the end of the run
                    const uint32_t from = e & (~0u << u0);
                    const uint32_t gap = ~from & (~0u << u0);   // first face after the run
                    const uint32_t stop = (gap != 0 ? gap & (0u - gap) : 0u) | (starts != 0 ? starts & (0u - starts) : 0u);
                    const uint32_t upto = stop != 0 ? (stop & (0u - stop)) - 1u : ~0u;
                    const uint32_t seg = from & upto;
                    const uint16_t id = ids[vbase + u0 * kUStride];
                    const uint32_t light = lut[class_at<Smooth>(r0, u0)];
                    if (!rules || ((static_cast<uint32_t>(cfg.rule_faces[id]) >> F) & 1u) == 0) {
                        add(v, seg, static_cast<uint32_t>(id) << 8 | light);
                        continue;
                    }
                    for (uint32_t m = seg; m != 0; m &= m - 1) {
                        const int u = std::countr_zero(m);
                        const int lx = F <= 3 ? u : s;
                        const int ly = F <= 1 ? s : v;
                        const int lz = F <= 1 ? v : (F <= 3 ? s : u);
                        const uint32_t k = face_rule_kinds(w, lx + 1, ly + 1, lz + 1, F);
                        if ((k & CV_FACE_RULE_CULL_WHEN_NEIGHBOUR_IN) != 0) continue;
                        const bool forced = (k & CV_FACE_RULE_OPAQUE_WHEN_NEIGHBOUR_IN) != 0;
                        add(v, 1u << u, (forced ? 1u << 24 : 0u) | static_cast<uint32_t>(id) << 8 | light);
                    }
                }
            }
            for (int ei = 0; ei < table.n_entries; ++ei) {
                const MergeEntry& e = table.entries[ei];
                const auto id = static_cast<uint16_t>(e.key >> 8);
                const uint32_t light4 = (e.key & 0xFFu) * 0x01010101u;
                const bool forced = (e.key >> 24) != 0;
                const uint8_t c = cls[id];
                const int list = forced ? 0 : ((c >> 2) & 1) + ((c >> 6) & 1);
                for (int ni = e.head; ni >= 0; ni = table.nodes[ni].next) {
                    MergeNode& node = table.nodes[ni];
                    const int v = node.v;
                    while (node.mask != 0) {
                        const uint64_t row = node.mask;
                        const int u0 = std::countr_zero(row);
                        const int len = std::countr_zero(~(row >> u0));
                        const auto run = static_cast<uint32_t>(((uint64_t{1} << len) - 1) << u0);
                        int h = 1;
                        for (int nj = node.next; nj >= 0; nj = table.nodes[nj].next) {
                            MergeNode& below = table.nodes[nj];
                            if (below.v != v + h || (below.mask & run) != run) break;
                            below.mask &= ~run;
                            ++h;
                        }
                        node.mask &= ~run;
                        emit(list, s, u0, v, forced, len, h, id, light4);
                    }
                }
            }
        }
        if constexpr (Gpu) {
            if (umin <= umax) {   // the quads' plane sits at +1 for top/south/east
                constexpr int kN = 3 - kU - kV;
                const int off[3] = {fo.ox, fo.oy, fo.oz};
                const int pn = off[kN] + s + ((F == 0 || F == 3 || F == 4) ? 1 : 0);
                fo.lo[kN] = std::min(fo.lo[kN], pn);
                fo.hi[kN] = std::max(fo.hi[kN], pn);
                fo.lo[kU] = std::min(fo.lo[kU], off[kU] + umin);
                fo.hi[kU] = std::max(fo.hi[kU], off[kU] + umax);
                fo.lo[kV] = std::min(fo.lo[kV], off[kV] + vmin);
                fo.hi[kV] = std::max(fo.hi[kV], off[kV] + vmax);
                umin = vmin = INT_MAX;
                umax = vmax = INT_MIN;
            }
        }
        for (int l = 0; l < 3; ++l) {
            if (n[l] == 0) continue;
            if constexpr (!Gpu) {
                fo.quads[l].insert(fo.quads[l].end(), sc.buf[l], sc.buf[l] + n[l]);
            } else {
                const auto* b = reinterpret_cast<const uint8_t*>(sc.gbuf[l]);
                fo.gpu->quad16[l].insert(fo.gpu->quad16[l].end(), b, b + static_cast<size_t>(n[l]) * 16);
            }
            n[l] = 0;
        }
    }
}

template <bool Smooth, bool Gpu>
void mesh_direction(int f, const MeshWork& w, FaceScratch& sc, bool greedy, bool rules, FaceOut& fo) {
    switch (f) {
        case 0: mesh_direction<0, Smooth, Gpu>(w, sc, greedy, rules, fo); break;
        case 1: mesh_direction<1, Smooth, Gpu>(w, sc, greedy, rules, fo); break;
        case 2: mesh_direction<2, Smooth, Gpu>(w, sc, greedy, rules, fo); break;
        case 3: mesh_direction<3, Smooth, Gpu>(w, sc, greedy, rules, fo); break;
        case 4: mesh_direction<4, Smooth, Gpu>(w, sc, greedy, rules, fo); break;
        default: mesh_direction<5, Smooth, Gpu>(w, sc, greedy, rules, fo); break;
    }
}

} // namespace

// The four corner lights of the face (lx, ly, lz, f) as u8, mesher.cpp skyFactor * aoFactor (same float ops).
void face_lights(MeshWork& w, int lx, int ly, int lz, int f, uint8_t* out) {
    const int64_t* ring = w.sky_ring();
    for (int c = 0; c < 4; ++c) {
        out[c] = vertex_light(w, ring, f, lx + kFaceOffsets[f][c][0], ly + kFaceOffsets[f][c][1], lz + kFaceOffsets[f][c][2]);
    }
}

namespace {

template <bool Gpu>
void faces_impl(MeshWork& w, bool greedy, FaceOut& fo) {
    thread_local std::unique_ptr<FaceScratch> scratch;
    if (!scratch) scratch = std::make_unique<FaceScratch>();
    // Plain reference: a thread_local inside the shared library costs a __tls_get_addr per access.
    FaceScratch& sc = *scratch;
    if constexpr (Gpu) ++sc.stamp;
    auto& rows = sc.rows;
    face_masks(w, rows, sc.nz);
    bool any[6];
    bool any_face = false;
    for (int f = 0; f < 6; ++f) {
        uint32_t acc = 0;
        for (int i = 0; i < 32; ++i) acc |= sc.nz[f][i];
        any[f] = acc != 0;
        any_face = any_face || any[f];
    }
    if (!any_face) return;
    const bool side_x = any[4] || any[5];
    build_sky(w, w.sky_ring(), side_x, sc.lp);
    if (side_x && w.smooth) build_opacity_t(w, sc.lp);
    const bool rules = !w.cfg->face_rules.empty();
    for (int f = 0; f < 6; ++f) {
        if (!any[f]) continue;
        if (f >= 4) {   // plane[lx][ly] bit lz and nz[f][lx] bit ly by 32x32 transposes per ly
            std::memset(sc.nz[f], 0, sizeof sc.nz[f]);
            uint32_t t[32];
            for (int ly = 0; ly < 32; ++ly) {
                uint32_t acc = 0;
                for (int lz = 0; lz < 32; ++lz) acc |= t[lz] = rows[f][ly * 32 + lz];
                if (acc == 0) continue;
                transpose32(t);
                for (int lx = 0; lx < 32; ++lx) {
                    sc.plane[lx][ly] = t[lx];
                    sc.nz[f][lx] |= (t[lx] != 0 ? 1u : 0u) << ly;
                }
            }
        }
        if (w.smooth) mesh_direction<true, Gpu>(f, w, sc, greedy, rules, fo);
        else mesh_direction<false, Gpu>(f, w, sc, greedy, rules, fo);
    }
}

} // namespace

void mesh_cube_faces(MeshWork& w, bool greedy, std::vector<cv_quad>* out) {
    FaceOut fo;
    fo.quads = out;
    faces_impl<false>(w, greedy, fo);
}

void mesh_cube_faces_gpu(MeshWork& w, bool greedy, GpuMeshBuild& out, int* lo3, int* hi3) {
    FaceOut fo;
    fo.gpu = &out;
    fo.ox = static_cast<int>(w.cx & 7) * 32;
    fo.oy = static_cast<int>(w.cy & 7) * 32;
    fo.oz = static_cast<int>(w.cz & 7) * 32;
    faces_impl<true>(w, greedy, fo);
    for (int a = 0; a < 3; ++a) {
        lo3[a] = fo.lo[a];
        hi3[a] = fo.hi[a];
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
constexpr float kCellEps = 1e-3f;
int ifloor(float v) { return static_cast<int>(std::floor(v)); }
} // namespace

float model_vertex_light(MeshWork& w, float vx, float vy, float vz, int face, int ox, int oy, int oz, bool ao) {
    const int nx = face == 4 ? 1 : face == 5 ? -1 : 0;
    const int ny = face == 0 ? 1 : face == 1 ? -1 : 0;
    const int nz = face == 3 ? 1 : face == 2 ? -1 : 0;
    const int xlo = nx != 0 ? ifloor(vx + static_cast<float>(nx) * kCellEps) : ifloor(vx - kCellEps);
    const int xhi = nx != 0 ? xlo : ifloor(vx + kCellEps);
    const int ylo = ny != 0 ? ifloor(vy + static_cast<float>(ny) * kCellEps) : ifloor(vy - kCellEps);
    const int yhi = ny != 0 ? ylo : ifloor(vy + kCellEps);
    const int zlo = nz != 0 ? ifloor(vz + static_cast<float>(nz) * kCellEps) : ifloor(vz - kCellEps);
    const int zhi = nz != 0 ? zlo : ifloor(vz + kCellEps);
    const int64_t* ring = w.sky_ring();
    auto clamp_ring = [](int v) { return v < -1 ? -1 : (v > 32 ? 32 : v); };
    auto column = [&](int x, int z) {
        const int64_t h = ring[(clamp_ring(z) + 1) * kPad + (clamp_ring(x) + 1)];
        if (h == w.y0 + oy + 1 && x == ox && z == oz) return w.y0 + oy;
        return h;
    };
    auto solid = [&](int x, int y, int z) {
        if (x == ox && y == oy && z == oz) return false;
        if (x < -1 || x > 32 || y < -1 || y > 32 || z < -1 || z > 32) return false;
        return w.opaque_at(x + 1, y + 1, z + 1);
    };
    int lit = 0, sampled = 0;
    for (int a = w.smooth ? 0 : 1; a < 2; ++a) {
        for (int b = w.smooth ? 0 : 1; b < 2; ++b) {
            int sx, sy, sz;
            if (ny != 0) {
                sx = a == 0 ? xlo : xhi; sy = ylo; sz = b == 0 ? zlo : zhi;
            } else if (nz != 0) {
                sx = a == 0 ? xlo : xhi; sy = b == 0 ? ylo : yhi; sz = zlo;
            } else {
                sx = xlo; sy = a == 0 ? ylo : yhi; sz = b == 0 ? zlo : zhi;
            }
            ++sampled;
            if (w.y0 + sy >= column(sx, sz)) ++lit;
        }
    }
    const float sky = static_cast<float>(lit) / static_cast<float>(sampled);
    if (!w.smooth || !ao) return sky;
    int s1x, s1y, s1z, s2x, s2y, s2z, cx, cy, cz;
    if (ny != 0) {
        s1x = xlo; s1y = ylo; s1z = zhi;
        s2x = xhi; s2y = ylo; s2z = zlo;
    } else if (nz != 0) {
        s1x = xlo; s1y = yhi; s1z = zlo;
        s2x = xhi; s2y = ylo; s2z = zlo;
    } else {
        s1x = xlo; s1y = ylo; s1z = zhi;
        s2x = xlo; s2y = yhi; s2z = zlo;
    }
    cx = xlo;
    cy = ylo;
    cz = zlo;
    auto same = [](int ax, int ay, int az, int bx, int by, int bz) { return ax == bx && ay == by && az == bz; };
    const bool side1 = solid(s1x, s1y, s1z);
    const bool side2 = !same(s2x, s2y, s2z, s1x, s1y, s1z) && solid(s2x, s2y, s2z);
    const bool cornr = !same(cx, cy, cz, s1x, s1y, s1z) && !same(cx, cy, cz, s2x, s2y, s2z) && solid(cx, cy, cz);
    int count = (side1 ? 1 : 0) + (side2 ? 1 : 0) + (cornr ? 1 : 0);
    if (side1 && side2) count = 3;
    return sky * (1.0f - kAoPerNeighbour * static_cast<float>(count));
}

} // namespace cenda::voxel
