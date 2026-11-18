// TEST-ONLY: the cube mesher as it was before the bit-sliced lighting rewrite (voxel_mesh_ref.cpp). Same
// signatures as build_gpu_mesh / cv_mesh_cube; own per-thread workspace; models and fluids come from the
// library (unchanged by the rewrite).
#pragma once

#include "cenda/voxel_world.h"
#include "mesh_gpu.hpp"
#include "mesh_internal.hpp"

namespace cenda::voxel::meshref {

MeshWork& mesh_work();
int32_t mesh_prepare(cv_world* world, int64_t cx, int64_t cy, int64_t cz, uint32_t flags, MeshWork& w);
void mesh_cube_faces(MeshWork& w, bool greedy, std::vector<cv_quad>* out3);
void mesh_host_cells(const MeshWork& w, std::vector<cv_host_cell>& out);
int32_t build_gpu_mesh(cv_world* world, int64_t cx, int64_t cy, int64_t cz, uint32_t flags, GpuMeshBuild& out);
int32_t mesh_cube(cv_world* world, int64_t cx, int64_t cy, int64_t cz, uint32_t flags, cv_mesh_out* out);

} // namespace cenda::voxel::meshref
