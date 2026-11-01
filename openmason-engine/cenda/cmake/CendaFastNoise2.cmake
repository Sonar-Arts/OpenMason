# The ONE FastNoise2 pin for every native library that samples the engine's noise.
#
# Included by the engine's kernels (native/kernels) and by game-owned native libraries that
# build their own FastNoise2 node graphs (Stonebreak's worldgen library, stonebreak-game/native,
# #386). Those graphs must agree bit-for-bit with what CendaKernels' ck_gen_grid_* hands Java,
# so a game library may not pin its own version: bump the tag here and every consumer follows.
#
# Defines the FastNoise target (static, PIC, third-party headers as SYSTEM includes).
include_guard(GLOBAL)
include(FetchContent)

set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(FASTNOISE2_NOISETOOL OFF CACHE BOOL "" FORCE)
set(FASTNOISE2_TESTS OFF CACHE BOOL "" FORCE)

FetchContent_Declare(FastNoise2
        GIT_REPOSITORY https://github.com/Auburn/FastNoise2.git
        GIT_TAG v1.1.1
        GIT_SHALLOW TRUE)
FetchContent_MakeAvailable(FastNoise2)

# GCC on non-x86 (linux-aarch64): upstream only builds ARM with Clang/MSVC. x86 flags stay
# exactly as upstream sets them, so x86 noise output is unchanged.
#  - FastNoise2 v1.1.1 adds the x86-only -mno-vzeroupper for every GCC build; drop it.
#  - FastSIMD's NEON Cast() returns a uint32x4_t mask as an int32x4_t register. Clang accepts
#    that same-size reinterpretation implicitly; GCC needs -flax-vector-conversions.
if(CMAKE_CXX_COMPILER_ID STREQUAL "GNU"
        AND NOT CMAKE_SYSTEM_PROCESSOR MATCHES "^([xX]86_64|AMD64|amd64|i[3-6]86|x86)$"
        AND TARGET FastSIMD_FastNoise)
    get_target_property(_cenda_fn2_opts FastSIMD_FastNoise COMPILE_OPTIONS)
    if(_cenda_fn2_opts)
        list(REMOVE_ITEM _cenda_fn2_opts -mno-vzeroupper)
        set_target_properties(FastSIMD_FastNoise PROPERTIES COMPILE_OPTIONS "${_cenda_fn2_opts}")
    endif()
    target_compile_options(FastSIMD_FastNoise PRIVATE -flax-vector-conversions)
endif()

# Treat third-party headers as system includes so their warnings don't trip -Werror.
get_target_property(_cenda_fn2_includes FastNoise INTERFACE_INCLUDE_DIRECTORIES)
set_target_properties(FastNoise PROPERTIES
        INTERFACE_SYSTEM_INCLUDE_DIRECTORIES "${_cenda_fn2_includes}")
