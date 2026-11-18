#pragma once
#include <cstdint>
#include <vector>
#include "cenda/voxel_world.h"
namespace cenda::voxel {
struct GpuMeshBuild {
    std::vector<uint8_t> quad16[3]; int32_t quad_count[3] = {0, 0, 0};   // 0 opaque, 1 cutout, 2 translucent
    std::vector<uint8_t> water;     int32_t water_count = 0;              // WATERQUAD16
    std::vector<uint8_t> stamp_vertices; int32_t stamp_vertex_count = 0;  // COMPACT20
    std::vector<uint32_t> stamp_indices;
    float aabb_min[3] = {0, 0, 0}, aabb_max[3] = {0, 0, 0};              // region-local
    uint16_t visibility = 0; uint16_t flags = 0;                          // flags: bit0 empty, bit1 has translucent
    int64_t revision = 0;
    void clear();
};
// Meshes cube (cx,cy,cz) against its 26 neighbours and packs it REGION-local (region = 8^3 cubes). Reuses out's
// capacity. Thread-safe (many workers at once). CV_OK or CV_ERR_MISSING.
int32_t build_gpu_mesh(cv_world* world, int64_t cx, int64_t cy, int64_t cz, uint32_t flags, GpuMeshBuild& out);
// The visibility walk behind cv_visible_cubes; also used by the streamer's load rule (c) with planes = nullptr.
// present/missing are flat (cx,cy,cz) triples.
struct VisibleWalk { std::vector<int64_t> present; std::vector<int64_t> missing; };
int32_t walk_visible(cv_world* world, int64_t cam_cx, int64_t cam_cy, int64_t cam_cz, int32_t max_radius,
                     const float* planes6x4, VisibleWalk& out);
}
