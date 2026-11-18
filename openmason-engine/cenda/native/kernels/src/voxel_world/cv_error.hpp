/* Shared error-message hook of the cv_* ABI (include/cenda/voxel_world.h). cv_last_error() returns the
 * calling thread's last message; every cv_* domain (world core, region store) sets it through this one
 * function. The DEFINITION lives with the voxel_world core (it owns the thread-local buffer and
 * cv_last_error); this header only declares it so the store links against the same symbol. */
#pragma once

#if defined(__GNUC__) || defined(__clang__)
#define CV_PRINTF_LIKE(f, a) __attribute__((format(printf, f, a)))
#else
#define CV_PRINTF_LIKE(f, a)
#endif

/* printf-style; truncates to the internal buffer size. Thread-local: safe from any thread. */
void cv_set_error(const char* fmt, ...) CV_PRINTF_LIKE(1, 2);
