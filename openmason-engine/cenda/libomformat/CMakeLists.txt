include(FetchContent)

# yyjson (MIT): JSON for format manifests. One C file, built here with our own flags (none of their CMake).
# Pinned by SHA-256 (GitHub tag archive 0.13.0, verified 2026-11-24).
FetchContent_Declare(yyjson
        URL https://github.com/ibireme/yyjson/archive/refs/tags/0.13.0.tar.gz
        URL_HASH SHA256=34e0f62a2bc11ab20d601e8ca1cc2b2079503aa45119a19133d89d19b94a0fae
        DOWNLOAD_EXTRACT_TIMESTAMP TRUE
        SOURCE_SUBDIR cenda-no-cmake)
# miniz (MIT): zip archives + deflate. The release asset is the single-file amalgamation.
# Pinned by SHA-256 (release 3.1.2, miniz-3.1.2.zip, verified 2026-11-24).
FetchContent_Declare(miniz
        URL https://github.com/richgel999/miniz/releases/download/3.1.2/miniz-3.1.2.zip
        URL_HASH SHA256=f0446d863f9c19926ad9483c523fdc42e42b8d4a6a431d27e09d49c79a140d9a
        DOWNLOAD_EXTRACT_TIMESTAMP TRUE
        SOURCE_SUBDIR cenda-no-cmake)
FetchContent_MakeAvailable(yyjson miniz)

add_library(cenda_yyjson STATIC ${yyjson_SOURCE_DIR}/src/yyjson.c)
target_include_directories(cenda_yyjson SYSTEM PUBLIC ${yyjson_SOURCE_DIR}/src)
# Hidden: yyjson_* / mz_* must stay out of libcenda_kernels' export table (only the c?_* ABIs are exported).
set_target_properties(cenda_yyjson PROPERTIES C_VISIBILITY_PRESET hidden POSITION_INDEPENDENT_CODE ON)
# yyjson.h forces default visibility on its API unless yyjson_api is predefined (MSVC exports nothing
# from a static lib by default).
if(NOT MSVC)
    target_compile_definitions(cenda_yyjson PUBLIC "yyjson_api=__attribute__((visibility(\"hidden\")))")
endif()

add_library(cenda_miniz STATIC ${miniz_SOURCE_DIR}/miniz.c)
target_include_directories(cenda_miniz SYSTEM PUBLIC ${miniz_SOURCE_DIR})
# In-memory archives only; no file times (identical input gives identical archive bytes); no zlib-named
# symbols (they would clash with a zlib elsewhere in the process).
target_compile_definitions(cenda_miniz PUBLIC
        MINIZ_NO_STDIO MINIZ_NO_TIME MINIZ_NO_ZLIB_COMPATIBLE_NAMES)
set_target_properties(cenda_miniz PROPERTIES C_VISIBILITY_PRESET hidden POSITION_INDEPENDENT_CODE ON)
if(NOT MSVC)
    target_compile_options(cenda_yyjson PRIVATE -w)
    target_compile_options(cenda_miniz PRIVATE -w)
endif()

# The shipped skeleton profiles (profiles/*.omprofile, OpenMason #154) are compiled in as byte arrays
# (not string literals: MSVC caps a string literal at 16 KB). Editing a profile re-runs configure.
set(OMPROFILE_ARRAYS "")
set(OMPROFILE_ENTRIES "")
foreach(_profile humanoid quadruped bird)
    set(_file ${CMAKE_CURRENT_SOURCE_DIR}/profiles/${_profile}.omprofile)
    file(READ ${_file} _hex HEX)
    string(REGEX REPLACE "([0-9a-f][0-9a-f])" "0x\\1," _bytes "${_hex}")
    string(REGEX REPLACE "((0x[0-9a-f][0-9a-f],){24})" "\\1\n    " _bytes "${_bytes}")
    string(APPEND OMPROFILE_ARRAYS "const unsigned char k_${_profile}[] = {\n    ${_bytes}};\n")
    string(APPEND OMPROFILE_ENTRIES
            "    {\"${_profile}\", std::string_view(reinterpret_cast<const char*>(k_${_profile}), sizeof k_${_profile})},\n")
    set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${_file})
endforeach()
configure_file(src/builtin_profiles.cpp.in ${CMAKE_CURRENT_BINARY_DIR}/builtin_profiles.cpp @ONLY)

add_library(omformat STATIC
        src/version.cpp
        src/zip_archive.cpp
        src/omchar.cpp
        src/omprofile.cpp
        src/omanim.cpp
        src/omanimgraph.cpp
        ${CMAKE_CURRENT_BINARY_DIR}/builtin_profiles.cpp)
target_include_directories(omformat PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src)
add_library(cenda::omformat ALIAS omformat)

target_include_directories(omformat PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include)
target_link_libraries(omformat PRIVATE cenda_yyjson cenda_miniz cenda_warnings)
# Linked into libcenda_kernels (a shared lib).
set_target_properties(omformat PROPERTIES POSITION_INDEPENDENT_CODE ON CXX_VISIBILITY_PRESET hidden)
