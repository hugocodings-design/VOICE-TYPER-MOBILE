cmake_minimum_required(VERSION 3.22.1)
project(whisper_jni CXX C)

# whisper.cpp (MIT), fetched at build time (pinned tag) and linked statically into libwhisper_jni.so.
include(FetchContent)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)   # ggml's own thread pool: no libomp.so to ship
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
FetchContent_Declare(whisper
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG v1.9.5
    GIT_SHALLOW TRUE)
FetchContent_MakeAvailable(whisper)

add_library(whisper_jni SHARED whisper_jni.cpp)
target_compile_features(whisper_jni PRIVATE cxx_std_17)
target_compile_options(whisper_jni PRIVATE -O3)
find_library(log-lib log)
target_link_libraries(whisper_jni PRIVATE whisper ${log-lib})
# 16 KB page alignment (Android 15+ devices)
target_link_options(whisper_jni PRIVATE "-Wl,-z,max-page-size=16384")
