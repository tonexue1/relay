cmake_minimum_required(VERSION 3.22.1)

project(relay_llama)

set(LLAMA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp")

# Always optimize the native stack -- AGP debug would otherwise build llama.cpp -O0
# and make a 0.5B model feel slower than a tuned 3B build.
if(NOT CMAKE_BUILD_TYPE OR CMAKE_BUILD_TYPE STREQUAL "Debug")
    set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
endif()

# Static-link llama.cpp into a single JNI shared library for Android.
# COMMON is OFF: our JNI only needs libllama (no chat-template helpers / httplib).
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
# Cross-compile: don't probe the host CPU; set ARM features explicitly for modern phones.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod" CACHE STRING "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_DIR} ${CMAKE_CURRENT_BINARY_DIR}/llama)

add_library(relay_llama SHARED relay_llama_jni.cpp)

target_include_directories(relay_llama PRIVATE
    ${LLAMA_DIR}/include
    ${LLAMA_DIR}/ggml/include
)

find_library(log-lib log)

target_link_libraries(relay_llama
    llama
    android
    ${log-lib}
)

target_compile_options(relay_llama PRIVATE -fexceptions -frtti -O3)

# Google Play / Android 15+: ELF LOAD segments must be 16 KB-aligned.
target_link_options(relay_llama PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)
