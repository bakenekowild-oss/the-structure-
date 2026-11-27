# The Structure — native build.
# Fetches llama.cpp v1.1.0 (ggml-org) and builds the `llama` library plus our
# small JNI bridge (llama_jni.cpp) into libheygobi.so.
# The llama.cpp C API used here was verified against include/llama.h at the
# v1.1.0 tag (llama_model_load_from_file, llama_init_from_model,
# llama_batch_ext_*, llama_process, sampler chain, chat template).
cmake_minimum_required(VERSION 3.22.1)

project(heygobi)

include(FetchContent)

FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp
    GIT_TAG v1.1.0
)

# We only need the core library: no tests, tools, examples, server or app.
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI OFF CACHE BOOL "" FORCE)

FetchContent_MakeAvailable(llama)

add_library(heygobi SHARED llama_jni.cpp)

target_link_libraries(
    heygobi
    PRIVATE
    llama
    android
    log
)

target_include_directories(
    heygobi
    PRIVATE
    ${llama_SOURCE_DIR}/include
)
