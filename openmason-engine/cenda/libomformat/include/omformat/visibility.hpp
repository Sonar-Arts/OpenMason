#pragma once

// Exception types libomformat throws have DEFAULT visibility even though the library is built with hidden visibility
// (it is linked into libcenda_kernels, whose export table holds the c?_* ABIs): a type that may be caught outside the
// image that throws it needs one unique type_info (GCC wiki "Visibility", C++ exceptions). MSVC needs nothing.
// (The macOS CI failure of 2026-11-25/26 - `catch (const std::exception&)` missing a format_error - was NOT this but
// yoga's -fno-rtti copies of the std exception typeinfos; see native/kernels/CMakeLists.txt next to yoga.)
#if defined(_WIN32)
#define OMFORMAT_EXCEPTION_TYPE
#else
#define OMFORMAT_EXCEPTION_TYPE __attribute__((visibility("default")))
#endif
