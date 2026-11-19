# Cenda inference engine (ci_*): compiled into libcenda_kernels. Needs no CUDA toolkit to build: the driver and NVRTC
# are opened at run time, and the CUDA C++ kernels it ships are compiled by NVRTC on the user's machine.
set(_ci_gen ${CMAKE_CURRENT_BINARY_DIR}/generated)
set(_ci_kernels ci_builtin)
set(_ci_incs)
foreach(_k IN LISTS _ci_kernels)
    set(_in ${CMAKE_CURRENT_SOURCE_DIR}/cuda/${_k}.cu)
    set(_out ${_ci_gen}/${_k}.cu.inc)
    add_custom_command(OUTPUT ${_out}
            COMMAND ${CMAKE_COMMAND} -DIN=${_in} -DOUT=${_out} -P ${CMAKE_CURRENT_SOURCE_DIR}/cmake/EmbedText.cmake
            DEPENDS ${_in} ${CMAKE_CURRENT_SOURCE_DIR}/cmake/EmbedText.cmake
            COMMENT "Embedding ${_k}.cu")
    list(APPEND _ci_incs ${_out})
endforeach()

add_library(cenda_infer OBJECT
        cuda_api.cpp
        json.cpp
        runtime.cpp
        engine.cpp
        ${_ci_incs})
set_target_properties(cenda_infer PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_include_directories(cenda_infer PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/../../include PRIVATE ${_ci_gen})
target_link_libraries(cenda_infer PRIVATE cenda_warnings ${CMAKE_DL_LIBS} Threads::Threads)
