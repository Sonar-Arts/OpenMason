# Model importers (glTF 2.0 + OBJ, OpenMason #173): the PARSE half (omformat/import.hpp, src/import/).
# Included at the end of libomformat/CMakeLists.txt, after the omformat target exists.

# cgltf (MIT): glTF 2.0 / GLB parser, single header. Its implementation is compiled in exactly one TU
# (src/import/gltf_reader.cpp). Pinned by SHA-256 (GitHub tag archive v1.15, downloaded twice and hashed with
# sha256sum, 2026-11-27).
FetchContent_Declare(cgltf
        URL https://github.com/jkuhlmann/cgltf/archive/refs/tags/v1.15.tar.gz
        URL_HASH SHA256=84e352092e5cd6aab7f66de62ddb66beb5e6f18d412ca9d12950d7a55bfef25a
        DOWNLOAD_EXTRACT_TIMESTAMP TRUE
        SOURCE_SUBDIR cenda-no-cmake)
# stb_image (public domain / MIT): JPEG + PNG decoding only. One file at a pinned commit (v2.34,
# nothings/stb 1da9ade1e66195252114f5da497f244704a6c58d, 2026-11-19: carries the 2026-11 fuzzing fixes),
# pinned by SHA-256 (downloaded and hashed with sha256sum, 2026-11-27). Compiled static inside
# src/import/image_codec.cpp, so none of its symbols leave that TU.
FetchContent_Declare(stb_image
        URL https://raw.githubusercontent.com/nothings/stb/1da9ade1e66195252114f5da497f244704a6c58d/stb_image.h
        URL_HASH SHA256=dfc2aadda6295f67f677587773f25e5bf8c8ad427ed0db7c518525c91e72e020
        DOWNLOAD_NO_EXTRACT TRUE  # a single header: nothing to extract (so no DOWNLOAD_EXTRACT_TIMESTAMP)
        SOURCE_SUBDIR cenda-no-cmake)
FetchContent_MakeAvailable(cgltf stb_image)

target_sources(omformat PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/src/import/scene.cpp
        ${CMAKE_CURRENT_SOURCE_DIR}/src/import/gltf_reader.cpp
        ${CMAKE_CURRENT_SOURCE_DIR}/src/import/obj_reader.cpp
        ${CMAKE_CURRENT_SOURCE_DIR}/src/import/image_codec.cpp)
# SYSTEM: the third-party headers' own warnings must not fail -Werror (cenda_warnings). Their code compiles
# with omformat's hidden visibility (CXX_VISIBILITY_PRESET), and stb_image is STB_IMAGE_STATIC, so no cgltf_* /
# stbi_* symbol reaches libcenda_kernels' export table.
target_include_directories(omformat SYSTEM PRIVATE ${cgltf_SOURCE_DIR} ${stb_image_SOURCE_DIR})
