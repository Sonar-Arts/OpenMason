/* Region store (#120): internal entry points beside the C ABI (cv_store_* in cenda/voxel_world.h).
 * The ctest compiles the store sources directly and uses these to shrink thresholds, inject faults and
 * look at the handle cache; the shipped library only ever uses the defaults through cv_store_open. */
#pragma once

#include "region_file.hpp"

#include "cenda/voxel_world.h"

#include <cstddef>

namespace cenda::cvstore {

/* cv_store_open with explicit tuning (NULL + cv_last_error on failure). */
cv_store* open_store(const char* dir_utf8, const Tuning& tuning);

/* Region objects currently cached (each holds at most one file handle). */
size_t resident_regions(cv_store* store);
/* Region objects whose file handle is open. */
size_t open_handles(cv_store* store);
/* Sectors and generation of a region file as the store sees it (0 when not resident). */
uint64_t region_file_sectors(cv_store* store, RegionKind kind, int64_t rx, int64_t ry, int64_t rz);
uint64_t region_generation(cv_store* store, RegionKind kind, int64_t rx, int64_t ry, int64_t rz);

} // namespace cenda::cvstore
