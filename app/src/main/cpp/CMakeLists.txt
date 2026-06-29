cmake_minimum_required(VERSION 3.22.1)
project(dabber_whisper)

# Build whisper.cpp + ggml as static libs and fold them into our single shared lib.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

# Force AVX2/FMA/F16C codepaths for the x86_64 emulator build (the default Android x86_64
# ABI is baseline-no-AVX, which makes the large-v3 encoder extremely slow on the emulator).
# add_compile_options applies to the ggml subproject pulled in below. arm64-v8a (the phone)
# uses NEON SIMD automatically and is unaffected by these x86 flags.
if(ANDROID_ABI STREQUAL "x86_64")
    add_compile_options(-mavx2 -mfma -mf16c -mavx)
endif()

include(FetchContent)
FetchContent_Declare(
    whisper
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG v1.9.1
)
FetchContent_MakeAvailable(whisper)

add_library(dabber_whisper SHARED whisper_jni.cpp)
target_link_libraries(dabber_whisper PRIVATE whisper android log)
