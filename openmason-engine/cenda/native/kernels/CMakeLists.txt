include(FetchContent)

# FastNoise2 + zstd are compiled statically into our shared lib: PIC required.
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# The shared FastNoise2 pin (game-owned native libraries include the same file — #386).
include(${CMAKE_CURRENT_SOURCE_DIR}/../../cmake/CendaFastNoise2.cmake)

set(ZSTD_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(ZSTD_BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(ZSTD_BUILD_STATIC ON CACHE BOOL "" FORCE)
set(ZSTD_BUILD_TESTS OFF CACHE BOOL "" FORCE)

FetchContent_Declare(zstd
        GIT_REPOSITORY https://github.com/facebook/zstd.git
        GIT_TAG v1.5.7
        GIT_SHALLOW TRUE
        SOURCE_SUBDIR build/cmake)
FetchContent_MakeAvailable(zstd)

# Lua 5.5 for UI scripting (#283/#292). The tarball has no CMake build, so we
# compile the core + the curated libraries ourselves. io/os/debug/package and
# linit are deliberately NOT compiled: the sandbox cannot reach what is not in
# the binary. Pinned by SHA-256 (verified against lua.org 2026-10-03).
FetchContent_Declare(lua
        URL https://www.lua.org/ftp/lua-5.5.1.tar.gz
        URL_HASH SHA256=1c4b4068d67061f2a2231ad2b5422e77acea1487ea9890f6320af614f4373dce
        DOWNLOAD_EXTRACT_TIMESTAMP TRUE
        # Deadline poll at loop back-jumps/calls — see lua/cenda_lua_interrupt.h.
        PATCH_COMMAND ${CMAKE_COMMAND} -DLUA_SRC_DIR=<SOURCE_DIR>
                -P ${CMAKE_CURRENT_SOURCE_DIR}/lua/patch_lvm.cmake)
FetchContent_MakeAvailable(lua)
# PATCH_COMMAND only runs on a fresh download; the deps cache is shared across builds, so re-run
# the (idempotent, per-file) patch at every configure to pick up patches added since.
execute_process(COMMAND ${CMAKE_COMMAND} -DLUA_SRC_DIR=${lua_SOURCE_DIR}
        -P ${CMAKE_CURRENT_SOURCE_DIR}/lua/patch_lvm.cmake
        RESULT_VARIABLE _cenda_lua_patch)
if(NOT _cenda_lua_patch EQUAL 0)
    message(FATAL_ERROR "Lua interrupt patch failed (${_cenda_lua_patch})")
endif()
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/lua/patch_lvm.cmake)

set(CENDA_LUA_SOURCES)
foreach(_src lapi lcode lctype ldebug ldo ldump lfunc lgc llex lmem lobject
        lopcodes lparser lstate lstring ltable ltm lundump lvm lzio
        lauxlib lbaselib lcorolib lmathlib lstrlib ltablib lutf8lib)
    list(APPEND CENDA_LUA_SOURCES ${lua_SOURCE_DIR}/src/${_src}.c)
endforeach()
add_library(cenda_lua STATIC ${CENDA_LUA_SOURCES})
target_include_directories(cenda_lua SYSTEM PUBLIC ${lua_SOURCE_DIR}/src ${CMAKE_CURRENT_SOURCE_DIR}/lua)
# Keep lua_* symbols out of the shared lib's export table; only cl_* is ABI.
set_target_properties(cenda_lua PROPERTIES C_VISIBILITY_PRESET hidden C_STANDARD 99)

# Yoga flexbox for the UI layout spike (#283 decides Yoga-in-Cenda vs Java for #287).
FetchContent_Declare(yoga
        GIT_REPOSITORY https://github.com/facebook/yoga.git
        GIT_TAG v3.2.1
        GIT_SHALLOW TRUE
        SOURCE_SUBDIR yoga)
FetchContent_MakeAvailable(yoga)
get_target_property(_yoga_includes yogacore INTERFACE_INCLUDE_DIRECTORIES)
set_target_properties(yogacore PROPERTIES INTERFACE_SYSTEM_INCLUDE_DIRECTORIES "${_yoga_includes}")

# miniaudio (public domain / MIT-0) for the audio engine (ca_*): playback device + decoders only;
# the mixer is ours (src/audio). stb_vorbis ships in its extras/ and gives the decoder Ogg Vorbis.
# Pinned by SHA-256 (GitHub tag archive 0.11.26, verified 2026-11-10). No CMakeLists of theirs is
# used: one implementation TU, compiled without the strict warning set.
FetchContent_Declare(miniaudio
        URL https://github.com/mackron/miniaudio/archive/refs/tags/0.11.26.tar.gz
        URL_HASH SHA256=838ba55a21c9142adf7b76bdc4351e5cd8b7ae128f46b25ec25f94ae9969c600
        DOWNLOAD_EXTRACT_TIMESTAMP TRUE
        SOURCE_SUBDIR cenda-no-cmake)
FetchContent_MakeAvailable(miniaudio)
add_library(cenda_miniaudio STATIC src/audio/miniaudio_impl.c)
target_include_directories(cenda_miniaudio SYSTEM PUBLIC ${miniaudio_SOURCE_DIR})
# The engine writes its own mixer: leave out miniaudio's engine, node graph, resource manager,
# encoders and generators. PUBLIC so the header part we include agrees with the implementation.
target_compile_definitions(cenda_miniaudio PUBLIC
        MA_NO_ENGINE MA_NO_NODE_GRAPH MA_NO_RESOURCE_MANAGER MA_NO_ENCODING MA_NO_GENERATION)
# Keep ma_*/stb_vorbis_* out of the shared lib's export table; only ca_* is ABI.
set_target_properties(cenda_miniaudio PROPERTIES C_VISIBILITY_PRESET hidden C_STANDARD 99)
if(NOT MSVC)
    target_compile_options(cenda_miniaudio PRIVATE -w)
endif()
find_package(Threads REQUIRED)
target_link_libraries(cenda_miniaudio PUBLIC Threads::Threads ${CMAKE_DL_LIBS})
if(UNIX AND NOT APPLE)
    target_link_libraries(cenda_miniaudio PUBLIC m)
endif()
if(APPLE)
    target_link_libraries(cenda_miniaudio PUBLIC
            "-framework CoreFoundation" "-framework CoreAudio" "-framework AudioToolbox")
endif()

# Treat third-party headers as system includes so their warnings don't trip -Werror.
get_target_property(_zstd_includes libzstd_static INTERFACE_INCLUDE_DIRECTORIES)
set_target_properties(libzstd_static PROPERTIES
        INTERFACE_SYSTEM_INCLUDE_DIRECTORIES "${_zstd_includes}")

add_library(cenda_kernels SHARED
        src/kernels.cpp
        src/mesher.cpp
        src/zstd_codec.cpp
        src/lua_host.cpp
        src/flex_host.cpp
        src/audio/audio_engine.cpp)

target_include_directories(cenda_kernels PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include)
target_link_libraries(cenda_kernels
        PRIVATE FastNoise libzstd_static cenda_lua yogacore cenda_miniaudio cenda_warnings)

# Float-op portability: the mesher's lighting math must match Java's IEEE
# single ops exactly (parity-tested). FMA contraction (-ffp-contract=fast, GCC's -O default) would
# fuse a*b+c and break that — keep our kernel TUs contraction-free.
if(MSVC)
    # /fp:precise never contracts a*b+c into an FMA (that needs /fp:contract).
    target_compile_options(cenda_kernels PRIVATE /fp:precise)
    # The C ABI (ck_*, cl_*, cf_*) is plain extern "C"; export it from the DLL without
    # per-function __declspec. Lua stays a static lib, so lua_* are not exported.
    set_target_properties(cenda_kernels PROPERTIES WINDOWS_EXPORT_ALL_SYMBOLS ON)
else()
    target_compile_options(cenda_kernels PRIVATE -ffp-contract=off)
endif()
