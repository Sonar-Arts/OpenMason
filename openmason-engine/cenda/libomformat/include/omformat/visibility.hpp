#pragma once

// Exception types libomformat throws must have DEFAULT visibility even though the library is built with hidden
// visibility (it is linked into libcenda_kernels, whose export table holds only the c?_* ABIs). With hidden RTTI,
// Apple's libc++abi cannot match a thrown format_error against a handler for one of its bases: every
// `catch (const std::exception&)` missed it and the process terminated, only an exact `catch (format_error&)`
// matched (macOS CI, 2026-11-25). Exporting the type gives it one unique type_info. MSVC needs nothing.
#if defined(_WIN32)
#define OMFORMAT_EXCEPTION_TYPE
#else
#define OMFORMAT_EXCEPTION_TYPE __attribute__((visibility("default")))
#endif
