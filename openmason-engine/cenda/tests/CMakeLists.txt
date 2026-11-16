# Plain-assert smoke tests for now; switch to Catch2/doctest via FetchContent
# once the conformance suite grows real fixtures.
add_executable(omformat_smoke smoke_test.cpp)
target_link_libraries(omformat_smoke PRIVATE cenda::omformat cenda_warnings)
add_test(NAME omformat_smoke COMMAND omformat_smoke)

if(CENDA_BUILD_KERNELS)
    set(_kernel_tests kernels_smoke kernels_chunk lua_host flex_host audio_engine entity_world physics_world)
    foreach(_test IN LISTS _kernel_tests)
        add_executable(${_test} ${_test}_test.cpp)
        target_link_libraries(${_test} PRIVATE cenda_kernels cenda_warnings)
        # Same float rule as the library (native/kernels/CMakeLists.txt): the tests check kernel
        # results bit-for-bit against reference math written in the test (entity_world's nearest =
        # sqrt(dx*dx + (dy*dy + dz*dz))). Where the compiler may fuse a*b+c into an FMA (ARM, macOS,
        # FMA-enabled x86 builds) the test's own reference rounds differently and the checks fail
        # although the library is right. Contraction off keeps both sides on plain IEEE ops.
        if(MSVC)
            target_compile_options(${_test} PRIVATE /fp:precise)
        else()
            target_compile_options(${_test} PRIVATE -ffp-contract=off)
        endif()
        add_test(NAME ${_test} COMMAND ${_test})
    endforeach()

    # Windows has no rpath: the test exes (tests/<Config>/) cannot see cenda_kernels.dll
    # (native/kernels/<Config>/), and the loader's "DLL not found" box then blocks a headless
    # CI runner until ctest's 1500 s default timeout. Put the DLL's folder on the test PATH.
    if(WIN32)
        set_tests_properties(${_kernel_tests} PROPERTIES
                ENVIRONMENT_MODIFICATION "PATH=path_list_prepend:$<TARGET_FILE_DIR:cenda_kernels>")
    endif()
    # Each test finishes in well under a second; a hang should fail fast, not stall CI.
    set_tests_properties(omformat_smoke ${_kernel_tests} PROPERTIES TIMEOUT 120)
endif()
