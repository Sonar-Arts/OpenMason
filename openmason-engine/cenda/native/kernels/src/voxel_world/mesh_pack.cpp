// GPU packing + the public mesher entry points (OpenMason #143, Phase 1b).
//
// FORMATS (little-endian u32 words, bit-exact with the engine-voxel Java codecs):
//  QUAD16 (MmsQuadCodec, w/h widened 2026-11-17):
//    word0: x:8 | y:10 | z:8 | face:3 | spare:3            region-local block of the rectangle's min cell
//    word1: (h-1)&15 :4 | orient:3 | alpha:1 | translucent:1 | layer:16 | (w-1):5 | (h-1)>>4 :1 | spare:1
//    word2: light0..3 u8 (toUnsignedByte), corner order = MmsCuboidGenerator.FACE_VERTEX_OFFSETS
//    word3: 0 (full cubes)
//  WATERQUAD16 (MmsWaterQuadCodec, unchanged): word0 x:8 y:10 z:8 face:3 falling:1 source:1 sheet:1;
//    word1 4 x u8 vertex y from (cellY - 1) in 1/128; word2 4 x u8 surface flags; word3 (w-1):4 (h-1):4
//    (flow+1):4 depth:8 inset:1.
//  COMPACT20 (MmsVertexFormat): i16 x, y, z in 1/32 block (y stored minus COMPACT_Y_SHIFT 512), f16 u, v,
//    snorm8 normal xyz + pad, u16 layer, u32 packFlags(water 0, alpha, translucent, light).
// Region = 8^3 cubes: positions are (cube & 7) * 32 + cube-local.
#include "mesh_internal.hpp"
#include "cv_error.hpp"

#include <algorithm>
#include <cstring>
#include <limits>

namespace cenda::voxel {

// ---- codecs ---------------------------------------------------------------------------------------------------

uint32_t quad16_word0(uint32_t x, uint32_t y, uint32_t z, uint32_t face) {
    return (x & 0xFFu) | ((y & 0x3FFu) << 8) | ((z & 0xFFu) << 18) | ((face & 7u) << 26);
}

uint32_t quad16_word1(uint32_t w, uint32_t h, uint32_t orientation, bool alpha, bool translucent, uint32_t layer) {
    const uint32_t hm = h - 1, wm = w - 1;
    return (hm & 15u) | ((orientation & 7u) << 4) | ((alpha ? 1u : 0u) << 7) | ((translucent ? 1u : 0u) << 8) |
           ((layer & 0xFFFFu) << 9) | ((wm & 31u) << 25) | (((hm >> 4) & 1u) << 30);
}

uint32_t quad16_word2(const uint8_t light[4]) {
    return static_cast<uint32_t>(light[0]) | (static_cast<uint32_t>(light[1]) << 8) |
           (static_cast<uint32_t>(light[2]) << 16) | (static_cast<uint32_t>(light[3]) << 24);
}

uint32_t water_word0(uint32_t x, uint32_t y, uint32_t z, uint32_t face, bool falling, bool source, bool sheet) {
    return (x & 0xFFu) | ((y & 0x3FFu) << 8) | ((z & 0xFFu) << 18) | ((face & 7u) << 26) |
           ((falling ? 1u : 0u) << 29) | ((source ? 1u : 0u) << 30) | ((sheet ? 1u : 0u) << 31);
}

namespace {
uint32_t y_byte(int32_t cell_y, float vy) {
    const float off = (vy - (static_cast<float>(cell_y) + -1.0f)) / (1.0f / 128.0f);
    return static_cast<uint32_t>(std::clamp(java_round(off), 0, 255));
}
} // namespace

uint32_t water_word1(int32_t cell_y, const float vy[4]) {
    return y_byte(cell_y, vy[0]) | (y_byte(cell_y, vy[1]) << 8) | (y_byte(cell_y, vy[2]) << 16) |
           (y_byte(cell_y, vy[3]) << 24);
}

uint32_t water_word2(const float f[4]) {
    return static_cast<uint32_t>(to_u8(f[0])) | (static_cast<uint32_t>(to_u8(f[1])) << 8) |
           (static_cast<uint32_t>(to_u8(f[2])) << 16) | (static_cast<uint32_t>(to_u8(f[3])) << 24);
}

uint32_t water_word3(uint32_t w, uint32_t h, int32_t flow, uint32_t depth, bool inset) {
    const int32_t fl = std::clamp(flow, -1, 7);
    return ((w - 1) & 15u) | (((h - 1) & 15u) << 4) | (static_cast<uint32_t>(fl + 1) << 8) |
           ((std::min(depth, 255u)) << 12) | ((inset ? 1u : 0u) << 20);
}

uint16_t float_to_half(float f) {
    uint32_t x = 0;
    std::memcpy(&x, &f, 4);
    const uint32_t sign = (x >> 16) & 0x8000u;
    const uint32_t exp = (x >> 23) & 0xFFu;
    uint32_t mant = x & 0x7FFFFFu;
    if (exp == 0xFFu) {   // inf / NaN
        return static_cast<uint16_t>(sign | 0x7C00u | (mant != 0 ? (0x200u | (mant >> 13)) : 0u));
    }
    const int32_t e = static_cast<int32_t>(exp) - 127 + 15;
    if (e >= 31) return static_cast<uint16_t>(sign | 0x7C00u);   // overflow -> inf
    if (e <= 0) {
        if (e < -10) return static_cast<uint16_t>(sign);           // underflow -> signed zero
        mant |= 0x800000u;
        const auto shift = static_cast<uint32_t>(14 - e);
        uint32_t half = mant >> shift;
        const uint32_t rem = mant & ((1u << shift) - 1u);
        const uint32_t mid = 1u << (shift - 1);
        if (rem > mid || (rem == mid && (half & 1u) != 0)) ++half;
        return static_cast<uint16_t>(sign | half);
    }
    uint32_t half = (static_cast<uint32_t>(e) << 10) | (mant >> 13);
    const uint32_t rem = mant & 0x1FFFu;
    if (rem > 0x1000u || (rem == 0x1000u && (half & 1u) != 0)) ++half;   // may carry into the exponent: correct
    return static_cast<uint16_t>(sign | half);
}

namespace {
int16_t fixed16(float local) {
    const int32_t q = java_round(local * kCompactUnits);
    return static_cast<int16_t>(std::clamp(q, -32768, 32767));
}
int8_t snorm8(float n) {
    return static_cast<int8_t>(java_round(std::clamp(n, -1.0f, 1.0f) * 127.0f));
}
void put_u16(std::vector<uint8_t>& d, uint16_t v) {
    d.push_back(static_cast<uint8_t>(v));
    d.push_back(static_cast<uint8_t>(v >> 8));
}
} // namespace

void compact20_vertex(std::vector<uint8_t>& dst, float x, float y, float z, float u, float v, const float n[3],
                      float alpha, float translucent, float light, uint16_t layer) {
    put_u16(dst, static_cast<uint16_t>(fixed16(x)));
    put_u16(dst, static_cast<uint16_t>(fixed16(y - kCompactYShift)));
    put_u16(dst, static_cast<uint16_t>(fixed16(z)));
    put_u16(dst, float_to_half(u));
    put_u16(dst, float_to_half(v));
    dst.push_back(static_cast<uint8_t>(snorm8(n[0])));
    dst.push_back(static_cast<uint8_t>(snorm8(n[1])));
    dst.push_back(static_cast<uint8_t>(snorm8(n[2])));
    dst.push_back(0);
    put_u16(dst, layer);
    put_u32(dst, static_cast<uint32_t>(to_u8(0.0f)) | (static_cast<uint32_t>(to_u8(alpha)) << 8) |
                     (static_cast<uint32_t>(to_u8(translucent)) << 16) | (static_cast<uint32_t>(to_u8(light)) << 24));
}

// ---- GpuMeshBuild ----------------------------------------------------------------------------------------------

void GpuMeshBuild::clear() {
    for (int i = 0; i < 3; ++i) {
        quad16[i].clear();
        quad_count[i] = 0;
    }
    water.clear();
    water_count = 0;
    stamp_vertices.clear();
    stamp_vertex_count = 0;
    stamp_indices.clear();
    for (int i = 0; i < 3; ++i) aabb_min[i] = aabb_max[i] = 0.0f;
    visibility = 0;
    flags = 0;
    revision = 0;
}

namespace {

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

// The cube's 15 pair bits for the snapshot in w, from the cube's visibility cache when it holds exactly this
// revision + block table (a neighbour-triggered remesh, or a cube the camera walk already graphed), else
// computed and stored. The walk computes cached bits from the stored cells with CV_BLOCK_VIS_OPAQUE alone, the
// mesher from its classes (VIS_OPAQUE and not air): the same bits unless the air id itself carries
// VIS_OPAQUE, so the cache is only read without that flag.
uint16_t visibility_of(cv_world* world, int64_t cx, int64_t cy, int64_t cz, const MeshWork& w) {
    uint16_t bits = 0;
    if ((w.flags[world_air(world)] & CV_BLOCK_VIS_OPAQUE) == 0 &&
        vis_cached(world, cx, cy, cz, w.pad.revision, w.generation, &bits)) {
        return bits;
    }
    bits = cube_visibility(w);
    vis_store(world, cx, cy, cz, w.pad.revision, w.generation, bits);
    return bits;
}

bool has_specials(const MeshWork& w) {
    for (int py = 1; py <= 32; ++py) {
        for (int pz = 1; pz <= 32; ++pz) {
            if ((w.special[py * kPad + pz] & ((uint64_t{1} << 33) - 2)) != 0) return true;
        }
    }
    return false;
}

} // namespace

int32_t build_gpu_mesh(cv_world* world, int64_t cx, int64_t cy, int64_t cz, uint32_t flags, GpuMeshBuild& out) {
    out.clear();
    MeshWork& w = mesh_work();
    const int32_t rc = mesh_prepare(world, cx, cy, cz, flags, w);
    if (rc != CV_OK) return rc;
    out.revision = w.pad.revision;
    // Cube faces packed straight to QUAD16 by the face pass (what packing its cv_quad lists gives: region-local
    // word0, word1 texture layer / orientation / alpha / translucent with forced-opaque faces cleared, light
    // word, word3 0), with the records' integer bounds (every corner is a whole block), folded into the float
    // bounds once.
    int lo[3], hi[3];
    mesh_cube_faces_gpu(w, (flags & CV_MESH_GREEDY) != 0, out, lo, hi);
    float bmin[3], bmax[3];
    for (int a = 0; a < 3; ++a) {
        bmin[a] = std::numeric_limits<float>::max();
        bmax[a] = -std::numeric_limits<float>::max();
    }
    for (int list = 0; list < 3; ++list) out.quad_count[list] = static_cast<int32_t>(out.quad16[list].size() / 16);
    if (lo[0] <= hi[0]) {
        for (int a = 0; a < 3; ++a) {
            bmin[a] = std::min(bmin[a], static_cast<float>(lo[a]));
            bmax[a] = std::max(bmax[a], static_cast<float>(hi[a]));
        }
    }
    if (out.quad_count[2] > 0) out.flags = static_cast<uint16_t>(out.flags | 2u);
    if (has_specials(w)) {
        mesh_models(w, out, bmin, bmax);
        mesh_fluids(w, out, bmin, bmax);
    }
    // bit2: the look of this cube depends on CV_MESH_DETAIL (an exposed CV_BLOCK_DETAIL cell); bit3: built with it.
    if (mesh_has_exposed_detail(w)) out.flags = static_cast<uint16_t>(out.flags | 4u);
    if (w.detail) out.flags = static_cast<uint16_t>(out.flags | 8u);
    out.visibility = visibility_of(world, cx, cy, cz, w);
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

} // namespace cenda::voxel

using namespace cenda::voxel;

extern "C" {

int32_t cv_mesh_cube(cv_world* world, int64_t cx, int64_t cy, int64_t cz, uint32_t flags, cv_mesh_out* out) {
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
    const int32_t rc = mesh_prepare(world, cx, cy, cz, flags, w);
    if (rc != CV_OK) return rc;
    thread_local std::vector<cv_quad> quads[3];
    thread_local std::vector<cv_host_cell> host;
    for (auto& q : quads) q.clear();
    host.clear();
    mesh_cube_faces(w, (flags & CV_MESH_GREEDY) != 0, quads);
    mesh_host_cells(w, host);
    if (!w.cfg->face_rules.empty()) {
        for (cv_quad& q : quads[0]) q.face = static_cast<uint8_t>(q.face & 7u);   // internal forced-opaque mark
    }
    out->visibility = visibility_of(world, cx, cy, cz, w);
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

} // extern "C"
