cmake_minimum_required(VERSION 3.22.1)
project(elagent_native_runtimes LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(BUILD_SHARED_LIBS ON)

# Shared CPU-only GGML backend for both pinned inference engines.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_RPC OFF CACHE BOOL "" FORCE)

set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)

set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2 OFF CACHE BOOL "" FORCE)
set(WHISPER_COREML OFF CACHE BOOL "" FORCE)
set(WHISPER_OPENVINO OFF CACHE BOOL "" FORCE)
set(WHISPER_VITISAI OFF CACHE BOOL "" FORCE)
set(WHISPER_USE_SYSTEM_GGML OFF CACHE BOOL "" FORCE)

include(FetchContent)
FetchContent_Declare(
    llama_cpp
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG a83d68b562b19fba1f8836edc93eb76d9ca1ce25
    GIT_SHALLOW FALSE
)
FetchContent_MakeAvailable(llama_cpp)

# Whisper detects the existing GGML targets and reuses the shared backend.
FetchContent_Declare(
    whisper_cpp
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG 5eaee0cc9024eb9e9a99469199920c8b7f5cb9ec
    GIT_SHALLOW FALSE
)
FetchContent_MakeAvailable(whisper_cpp)

add_library(elagent_llama_jni SHARED native_llama.cpp)
target_link_libraries(elagent_llama_jni PRIVATE llama log android)
target_compile_options(elagent_llama_jni PRIVATE -O2 -fvisibility=hidden)

add_library(elagent_whisper_jni SHARED native_whisper.cpp)
target_link_libraries(elagent_whisper_jni PRIVATE whisper log android)
target_compile_options(elagent_whisper_jni PRIVATE -O2 -fvisibility=hidden)
