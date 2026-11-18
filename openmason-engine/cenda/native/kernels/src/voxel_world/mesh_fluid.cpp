// Fluids -> WATERQUAD16 (OpenMason #143, Phase 1b): the native port of Stonebreak's MmsWaterGenerator +
// MmsCcoAdapter.addWaterBlockWithCulling, generic over cv_fluid_desc (one record per visible cell face,
// region-local, exactly as MmsWaterQuadCodec packs it).
//
// Per fluid cell (block id == fluid_id): value = layer value; 0 or level 0 (not falling) = source; bit3
// falling; bit4 source; bits 5..8 = river flow octant + 1. Surface height = surface_by_level[0] for sources and
// falling cells, else surface_by_level[level].
//  - Corner heights (sewn: every cell sharing a corner computes the same value) from the four cells around the
//    corner: any source -> max; water above any -> max; walls (non-see-through, non-fluid) -> tallest; open
//    flow over sources below -> pulled down by up to half; else tallest. 0 when no fluid touches it. Aligned
//    to 1e-4 and clamped to [1/16, max].
//  - Faces: never against the same fluid; always (inset 1/64) against TRANSLUCENT blocks; the top face against
//    air or any non-see-through block; other faces against air or see-through blocks; SIDE faces toward an
//    UNKNOWN cell (missing cube below its column top) are skipped (seam guard, as the old unloaded-chunk rule);
//    then the fluid block's CULL face rules (cv_face_rule_add) drop faces toward their neighbour ids.
//  - Bottom of every face: attached to the fluid below (its surface), or to the top of the block below (a
//    model that covers its bottom plane gives its height, anything else 1); side bottoms sealed 0.2 below the
//    neighbour column's surface when both stand on fluid (wave-proof seams).
//  - word3 depth = fluid cells straight down from the cell (itself included), capped at 16 (the cube below is
//    read when the column reaches the cube's floor).
#include "mesh_internal.hpp"

#include <cmath>

namespace cenda::voxel {

namespace {

constexpr float kMinDisplayed = 0.0625f;
constexpr float kAttachEps = 0.001f;
constexpr float kSideClipOverlap = 0.2f;
constexpr float kFlagEps = 0.0001f;
constexpr float kAlignEps = 0.0001f;
constexpr int kMaxDepth = 16;

struct Fluid {
    const MeshWork& w;
    const cv_fluid_desc& d;
    float max_h;
    // Cells of the cube below (rows ly 16..31), read lazily for depth.
    std::vector<uint16_t>& below;
    int below_state = 0;   // 0 not read, 1 read, -1 missing

    bool is(int px, int py, int pz) const { return w.id_at(px, py, pz) == d.fluid_id; }
    uint16_t value(int px, int py, int pz) const { return w.layer_at(d.layer, px, py, pz); }
    static bool falling(uint16_t v) { return (v & 8u) != 0; }
    static bool source(uint16_t v) { return (v & 16u) != 0 || (!falling(v) && (v & 7u) == 0); }
    float height(int px, int py, int pz) const {
        if (!is(px, py, pz)) return NAN;
        const uint16_t v = value(px, py, pz);
        if (source(v) || falling(v)) return d.surface_by_level[0];
        return d.surface_by_level[v & 7u];
    }
    float clamp_h(float h) const { return std::fmax(kMinDisplayed, std::fmin(max_h, h)); }
    // MmsWaterGenerator.cornerHeightAtWorld; (cx, cz) padded corner = the cell at (cx, cz) has it as its
    // (-x, -z) corner.
    float corner(int cx, int py, int cz) const {
        float maxh = 0.0f;
        int water = 0, solid = 0, sources = 0, below_sources = 0;
        bool continues = false;
        for (int dx = -1; dx <= 0; ++dx) {
            for (int dz = -1; dz <= 0; ++dz) {
                const int bx = cx + dx, bz = cz + dz;
                const float h = height(bx, py, bz);
                if (!std::isnan(h)) {
                    ++water;
                    maxh = std::fmax(maxh, h);
                    if (source(value(bx, py, bz))) ++sources;
                    if (!std::isnan(height(bx, py + 1, bz))) continues = true;
                } else {
                    const uint8_t c = w.cls[w.id_at(bx, py, bz)];
                    if ((c & (kClsSee | kClsAir)) == 0) ++solid;
                }
                if (is(bx, py - 1, bz) && source(value(bx, py - 1, bz))) ++below_sources;
            }
        }
        if (water == 0) return 0.0f;
        if (sources > 0 || continues) return clamp_h(max_h);
        float h = maxh;
        if (solid > 0) return clamp_h(h);
        if (below_sources > 0) {
            const float pull = std::fmin(1.0f, static_cast<float>(below_sources) / 4.0f);
            h = h * (1.0f - pull * 0.5f);
        }
        return clamp_h(h);
    }
    float align(float h) const {
        const float aligned = static_cast<float>(java_round(h / kAlignEps)) * kAlignEps;
        return clamp_h(aligned);
    }
    bool unknown(int px, int py, int pz) const { return w.unknown_at(px, py, pz); }
};

int32_t flow_of(uint16_t v) { return static_cast<int32_t>((v >> 5) & 15u) - 1; }

} // namespace

void mesh_fluids(MeshWork& w, GpuMeshBuild& out, float* bmin, float* bmax) {
    if (w.cfg->fluids.empty()) return;
    thread_local std::vector<uint16_t> below_buf;
    const int ox = static_cast<int>(w.cx & 7) * 32, oy = static_cast<int>(w.cy & 7) * 32,
              oz = static_cast<int>(w.cz & 7) * 32;
    const int64_t min_y = world_min_y(w.world);
    const uint64_t inner = ((uint64_t{1} << 33) - 1) & ~uint64_t{1};
    for (const FluidCfg& fc : w.cfg->fluids) {
        Fluid fl{w, fc.desc, fc.desc.surface_by_level[0], below_buf};
        const bool ruled = w.cfg->is_ruled(fc.desc.fluid_id);
        for (int py = 1; py <= 32; ++py) {
            for (int pz = 1; pz <= 32; ++pz) {
                uint64_t sp = w.special[py * kPad + pz] & inner;
                while (sp != 0) {
                    const int px = std::countr_zero(sp);
                    sp &= sp - 1;
                    if (!fl.is(px, py, pz)) continue;
                    const int lx = px - 1, ly = py - 1, lz = pz - 1;
                    const int ry = oy + ly;                 // region-local cell y (the codec's cellY)
                    const auto wy = static_cast<float>(ry);  // worldY of the generator, region-local
                    const bool at_floor = w.y0 + ly <= min_y;
                    const uint16_t v = fl.value(px, py, pz);
                    const bool falling = Fluid::falling(v);
                    const bool source = Fluid::source(v);
                    const int32_t flow = flow_of(v);
                    int depth = -1;
                    bool heights_ready = false;
                    float h[4] = {0, 0, 0, 0};
                    for (int f = 0; f < 6; ++f) {
                        const int nx = px + kFaceDx[f], ny = py + kFaceDy[f], nz = pz + kFaceDz[f];
                        if (f >= 2 && fl.unknown(nx, ny, nz)) continue;
                        if (fl.is(nx, ny, nz)) continue;
                        const uint16_t nid = w.id_at(nx, ny, nz);
                        const uint8_t ncls = w.cls[nid];
                        const bool translucent = (w.flags[nid] & CV_BLOCK_TRANSLUCENT) != 0 && (ncls & kClsAir) == 0;
                        const bool air = (ncls & kClsAir) != 0;
                        const bool see = (ncls & kClsSee) != 0;
                        bool draw;
                        if (translucent) draw = true;
                        else if (f == 0) draw = !see || air;
                        else draw = see || air;
                        if (!draw) continue;
                        if (ruled && (face_rule_kinds(w, px, py, pz, f) & CV_FACE_RULE_CULL_WHEN_NEIGHBOUR_IN) != 0) {
                            continue;   // neighbour face rule (cv_face_rule_add); OPAQUE has no meaning here
                        }
                        if (depth < 0) {
                            depth = 0;
                            for (int y = py; y >= 0 && depth < kMaxDepth; --y) {
                                if (!fl.is(px, y, pz)) break;
                                ++depth;
                                if (y == 0) {
                                    // Continue into the cube below (rows 16..31 are enough for kMaxDepth).
                                    if (fl.below_state == 0) {
                                        CubeRead below(w.world, w.cx, w.cy - 1, w.cz);
                                        if (below) {
                                            below_buf.resize(16 * 32 * 32);
                                            below.copy_ids(0, 16, 0, 32, 16, 32, below_buf.data(), 32, 32 * 32);
                                            fl.below_state = 1;
                                        } else {
                                            fl.below_state = -1;
                                        }
                                    }
                                    if (fl.below_state < 0) break;
                                    for (int by = 30; by >= 16 && depth < kMaxDepth; --by) {
                                        if (below_buf[static_cast<size_t>((by - 16) * 1024 + lz * 32 + lx)] !=
                                            fc.desc.fluid_id) {
                                            break;
                                        }
                                        ++depth;
                                    }
                                }
                            }
                        }
                        if (!heights_ready) {
                            // Corners: 0 (x, z+1), 1 (x+1, z+1), 2 (x+1, z), 3 (x, z).
                            h[0] = fl.align(fl.corner(px, py, pz + 1));
                            h[1] = fl.align(fl.corner(px + 1, py, pz + 1));
                            h[2] = fl.align(fl.corner(px + 1, py, pz));
                            h[3] = fl.align(fl.corner(px, py, pz));
                            heights_ready = true;
                        }
                        // Bottom attachment (computeWaterBottomAttachmentHeight).
                        float bottom = wy;
                        if (!at_floor && !fl.unknown(px, py - 1, pz)) {
                            const auto below_y = static_cast<float>(ry - 1);
                            if (fl.is(px, py - 1, pz)) {
                                bottom = below_y + fl.height(px, py - 1, pz);
                            } else {
                                float nh = 1.0f;
                                const Model* m = w.model_at(px, py - 1, pz);
                                if (m != nullptr && (m->fills & 1u) != 0) nh = m->top;
                                nh = std::fmax(0.0f, std::fmin(1.0f, nh));
                                const float attach = std::fmin(wy, below_y + nh);
                                if (attach < wy - kAttachEps) {
                                    const float adjusted = std::fmax(below_y, attach - kAttachEps);
                                    bottom = std::fmax(wy - 1.0f, adjusted);
                                }
                            }
                        }
                        if (f >= 2 && !at_floor) {
                            // sealSideBottomToNeighborSurface
                            const float nbh = fl.height(nx, py - 1, nz);
                            if (!std::isnan(nbh) && fl.is(px, py - 1, pz)) {
                                const auto below_y = static_cast<float>(ry - 1);
                                const float sealed = std::fmax(below_y, below_y + nbh - kSideClipOverlap);
                                bottom = std::fmin(bottom, sealed);
                            }
                        }
                        float vy[4];
                        float fl4[4];
                        auto enc = [](float x) { return x > kFlagEps ? x : kFlagEps; };
                        switch (f) {
                            case 0:
                                for (int c = 0; c < 4; ++c) {
                                    vy[c] = wy + h[c];
                                    fl4[c] = enc(h[c]);
                                }
                                break;
                            case 1:
                                for (int c = 0; c < 4; ++c) {
                                    vy[c] = bottom;
                                    fl4[c] = kFlagEps;
                                }
                                break;
                            default: {
                                static constexpr int kTop[6][2] = {{0, 0}, {0, 0}, {3, 2}, {1, 0}, {2, 1}, {0, 3}};
                                vy[0] = vy[1] = bottom;
                                fl4[0] = fl4[1] = kFlagEps;
                                vy[2] = wy + h[kTop[f][0]];
                                vy[3] = wy + h[kTop[f][1]];
                                fl4[2] = enc(h[kTop[f][0]]);
                                fl4[3] = enc(h[kTop[f][1]]);
                                break;
                            }
                        }
                        const auto rx = static_cast<uint32_t>(ox + lx), rz = static_cast<uint32_t>(oz + lz);
                        put_u32(out.water, water_word0(rx, static_cast<uint32_t>(ry), rz, static_cast<uint32_t>(f),
                                                       falling, source, false));
                        put_u32(out.water, water_word1(ry, vy));
                        put_u32(out.water, water_word2(fl4));
                        put_u32(out.water, water_word3(1, 1, flow, static_cast<uint32_t>(depth), translucent));
                        ++out.water_count;
                        for (int c = 0; c < 4; ++c) {
                            const float cxp = static_cast<float>(rx) + static_cast<float>(kFaceOffsets[f][c][0]);
                            const float czp = static_cast<float>(rz) + static_cast<float>(kFaceOffsets[f][c][2]);
                            if (cxp < bmin[0]) bmin[0] = cxp;
                            if (czp < bmin[2]) bmin[2] = czp;
                            if (cxp > bmax[0]) bmax[0] = cxp;
                            if (czp > bmax[2]) bmax[2] = czp;
                            if (vy[c] < bmin[1]) bmin[1] = vy[c];
                            if (vy[c] > bmax[1]) bmax[1] = vy[c];
                        }
                    }
                }
            }
        }
    }
}

} // namespace cenda::voxel
