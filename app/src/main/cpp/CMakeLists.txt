cmake_minimum_required(VERSION 3.22.1)
project(whisper_jni CXX C)

# whisper.cpp (MIT), fetched at build time (pinned tag).
include(FetchContent)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)   # ggml's own thread pool: no libomp.so to ship
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

if (ANDROID_ABI STREQUAL "arm64-v8a")
    # One ggml CPU backend per Android ARM generation (libggml-cpu-android_armv8.0_1.so … armv9.2_2), the best one
    # for the phone is loaded at runtime (ggml_backend_load_all_from_path). dotprod + fp16 + i8mm made Whisper
    # ~2x faster on a Snapdragon 8 Gen 3 than the plain armv8.0 build. Needs shared libraries, extracted on install.
    set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
    set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
    set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
    set(VT_BACKEND_DL ON)
else()
    # 32-bit ARM: a single static build, as before
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(VT_BACKEND_DL OFF)
endif()

FetchContent_Declare(whisper
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG v1.9.5
    GIT_SHALLOW TRUE)
FetchContent_MakeAvailable(whisper)

add_library(whisper_jni SHARED whisper_jni.cpp)
target_compile_features(whisper_jni PRIVATE cxx_std_17)
target_compile_options(whisper_jni PRIVATE -O3)
if (VT_BACKEND_DL)
    target_compile_definitions(whisper_jni PRIVATE VT_BACKEND_DL=1)
endif()
find_library(log-lib log)
target_link_libraries(whisper_jni PRIVATE whisper ggml ${log-lib})

# 16 KB page alignment (Android 15+ devices), for every native library we ship
set(VT_NATIVE_TARGETS whisper_jni)
foreach (t whisper ggml ggml-base)
    if (TARGET ${t})
        get_target_property(type ${t} TYPE)
        if (type STREQUAL "SHARED_LIBRARY" OR type STREQUAL "MODULE_LIBRARY")
            list(APPEND VT_NATIVE_TARGETS ${t})
        endif()
    endif()
endforeach()
if (VT_BACKEND_DL)
    get_property(all_targets DIRECTORY ${whisper_SOURCE_DIR}/ggml/src PROPERTY BUILDSYSTEM_TARGETS)
    foreach (t ${all_targets})
        if (t MATCHES "^ggml-cpu-")
            list(APPEND VT_NATIVE_TARGETS ${t})
        endif()
    endforeach()
endif()
foreach (t ${VT_NATIVE_TARGETS})
    target_link_options(${t} PRIVATE "-Wl,-z,max-page-size=16384")
endforeach()
message(STATUS "Voice Typer native libraries: ${VT_NATIVE_TARGETS}")
