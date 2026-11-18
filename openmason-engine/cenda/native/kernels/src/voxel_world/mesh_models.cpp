// Host models -> COMPACT20 stamp geometry (OpenMason #143, Phase 1b): the native port of the engine's
// voxel.stamp emitter (StampEmitter + MmsFaceCullingService + VertexLightSampler.sampleCombined with the
// own-cell rule).
//
// A model quad with cull_face f in 0..5 is dropped when the neighbour across f hides it:
//  1. air (and unknown cells of missing cubes, which read as air) never hides;
//  2. partial-height rule: on a SIDE face, two cells of the same block bound to DIFFERENT models (snow
//     layers of different heights) always show their sides;
//  3. one-way shape rule: a neighbour that does not cover the shared plane cannot hide a face whose own
//     plane is covered by this model (stairs' open sides);
//  4. a cutout block whose face plane is covered, next to a non-see-through block covering its plane,
//     is hidden (no coplanar z-fight with the opaque block's own face);
//  5. otherwise MmsFaceCullingService.shouldRenderAgainst: a transparent block (TRANSPARENT/TRANSLUCENT
//     flag) shows faces against other ids (translucent ones only against see-through other ids); an
//     opaque one only against see-through neighbours.
// cull_face 6 quads (crosses, interior treads) and quads of oversize models that reach past [-1, 2]^3 (Model::cull,
// see voxel_world.h) are never culled. Then the host's neighbour face rules
// (cv_face_rule_add) on quads with cull_face 0..5: CULL drops the quad, OPAQUE clears its translucent and
// alpha-test bytes. Plane coverage of a model is computed at
// cv_model_define (16x16 samples per plane); cubes cover every plane, unbound host cells count as covering.
//
// Vertices: cell-local corners + the cell, region-local, 4 per quad, triangles (0,1,2) (0,2,3). Light per
// vertex = VertexLightSampler.sampleCombined (sky over the up to four air-side columns, AO over OPAQUE cells,
// the cell itself never shades its own geometry) on the quad's face (cull_face, or the dominant axis of its
// normal for cull_face 6); flag bit2 (no AO) keeps the sky term only. Flags: bit0 translucent -> the
// translucent attribute byte, bit1 cutout -> the alpha-test byte (MmsBufferLayout.packFlags order).
#include "mesh_internal.hpp"

#include <bit>
#include <cmath>

namespace cenda::voxel {

namespace {

int normal_face(const float* n) {
    const float ax = std::fabs(n[0]), ay = std::fabs(n[1]), az = std::fabs(n[2]);
    if (ay >= ax && ay >= az) return n[1] >= 0.0f ? 0 : 1;
    if (az >= ax) return n[2] < 0.0f ? 2 : 3;
    return n[0] >= 0.0f ? 4 : 5;
}

bool model_face_visible(const MeshWork& w, int px, int py, int pz, int f, const Model* self) {
    const int nx = px + kFaceDx[f], ny = py + kFaceDy[f], nz = pz + kFaceDz[f];
    const uint16_t nid = w.id_at(nx, ny, nz);
    const uint8_t ncls = w.cls[nid];
    if ((ncls & kClsAir) != 0) return true;
    const uint16_t aid = w.id_at(px, py, pz);
    if (f >= 2 && nid == aid) {
        const Model* other = w.model_at(nx, ny, nz);
        if (other != nullptr && other != self) return true;
    }
    const bool fills_self = ((self->fills >> f) & 1u) != 0;
    const bool fills_nb = cell_fills(w, nx, ny, nz, kOpposite[f]);
    if (!fills_nb && fills_self) return true;
    const uint16_t af = w.flags[aid];
    const bool a_transparent = (af & (CV_BLOCK_TRANSPARENT | CV_BLOCK_TRANSLUCENT)) != 0;
    const bool n_see = (ncls & kClsSee) != 0;
    if (a_transparent && !n_see && fills_self && fills_nb) return false;
    const bool same = nid == aid;
    if (a_transparent) {
        if ((af & CV_BLOCK_TRANSLUCENT) != 0) return n_see && !same;
        return !same;
    }
    return n_see;
}

void extend(float* bmin, float* bmax, float x, float y, float z) {
    if (x < bmin[0]) bmin[0] = x;
    if (y < bmin[1]) bmin[1] = y;
    if (z < bmin[2]) bmin[2] = z;
    if (x > bmax[0]) bmax[0] = x;
    if (y > bmax[1]) bmax[1] = y;
    if (z > bmax[2]) bmax[2] = z;
}

} // namespace

void mesh_models(MeshWork& w, GpuMeshBuild& out, float* bmin, float* bmax) {
    const int ox = static_cast<int>(w.cx & 7) * 32, oy = static_cast<int>(w.cy & 7) * 32,
              oz = static_cast<int>(w.cz & 7) * 32;
    const bool rules = !w.cfg->face_rules.empty();
    for (int py = 1; py <= 32; ++py) {
        for (int pz = 1; pz <= 32; ++pz) {
            uint64_t sp = w.special[py * kPad + pz] & (((uint64_t{1} << 33) - 1) & ~uint64_t{1});
            while (sp != 0) {
                const int px = std::countr_zero(sp);
                sp &= sp - 1;
                const Model* m = w.model_at(px, py, pz);
                if (m == nullptr) continue;
                const int lx = px - 1, ly = py - 1, lz = pz - 1;
                // Neighbour face rules of this cell per face, evaluated once (bit 31 = computed).
                uint32_t rule_kinds[6] = {0, 0, 0, 0, 0, 0};
                const bool ruled = rules && w.cfg->is_ruled(w.id_at(px, py, pz));
                for (size_t qi = 0; qi < m->quads.size(); ++qi) {
                    const cv_model_quad& q = m->quads[qi];
                    const uint8_t cull = m->cull[qi];   // 6 for quads reaching past [-1, 2]^3
                    if (cull < 6 && !model_face_visible(w, px, py, pz, cull, m)) continue;
                    uint32_t kinds = 0;
                    if (ruled && cull < 6) {
                        uint32_t& k = rule_kinds[cull];
                        if (k == 0) k = 0x80000000u | face_rule_kinds(w, px, py, pz, cull);
                        kinds = k;
                        if ((kinds & CV_FACE_RULE_CULL_WHEN_NEIGHBOUR_IN) != 0) continue;
                    }
                    const bool forced = (kinds & CV_FACE_RULE_OPAQUE_WHEN_NEIGHBOUR_IN) != 0;
                    const int face = q.cull_face < 6 ? q.cull_face : normal_face(q.normal);
                    const bool translucent = !forced && (q.flags & 1u) != 0;
                    const float alpha = !forced && (q.flags & 2u) != 0 ? 1.0f : 0.0f;
                    const auto base = static_cast<uint32_t>(out.stamp_vertex_count);
                    for (int c = 0; c < 4; ++c) {
                        const float vx = static_cast<float>(lx) + q.pos[c][0];
                        const float vy = static_cast<float>(ly) + q.pos[c][1];
                        const float vz = static_cast<float>(lz) + q.pos[c][2];
                        const float light = model_vertex_light(w, vx, vy, vz, face, lx, ly, lz, (q.flags & 4u) == 0);
                        const float rx = static_cast<float>(ox + lx) + q.pos[c][0];
                        const float ry = static_cast<float>(oy + ly) + q.pos[c][1];
                        const float rz = static_cast<float>(oz + lz) + q.pos[c][2];
                        compact20_vertex(out.stamp_vertices, rx, ry, rz, q.uv[c][0], q.uv[c][1], q.normal, alpha,
                                         translucent ? 1.0f : 0.0f, light, q.texture_layer);
                        extend(bmin, bmax, rx, ry, rz);
                    }
                    out.stamp_vertex_count += 4;
                    const uint32_t idx[6] = {base, base + 1, base + 2, base, base + 2, base + 3};
                    out.stamp_indices.insert(out.stamp_indices.end(), idx, idx + 6);
                    if (translucent) out.flags = static_cast<uint16_t>(out.flags | 2u);
                }
            }
        }
    }
}

} // namespace cenda::voxel
