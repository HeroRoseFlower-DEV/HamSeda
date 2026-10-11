cmake_minimum_required(VERSION 3.22.1)
project(hamseda_opus_jni LANGUAGES C CXX)

include(FetchContent)

# Pin the official Xiph release archive. FetchContent verifies SHA-256 before
# extraction, protecting the build from mutable refs or substituted files.
FetchContent_Declare(
    opus
    URL https://downloads.xiph.org/releases/opus/opus-1.6.1.tar.gz
    URL_HASH SHA256=6ffcb593207be92584df15b32466ed64bbec99109f007c82205f0194572411a1
)

# Build the reference codec only; the PTT application does not need command-line
# tools, upstream tests, DRED model download, or experimental OSCE.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_CUSTOM_MODES OFF CACHE BOOL "" FORCE)
set(OPUS_DRED OFF CACHE BOOL "" FORCE)
set(OPUS_OSCE OFF CACHE BOOL "" FORCE)
set(OPUS_ENABLE_FLOAT_API ON CACHE BOOL "" FORCE)
set(OPUS_HARDENING ON CACHE BOOL "" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

FetchContent_MakeAvailable(opus)

if(NOT TARGET opus)
    message(FATAL_ERROR "The pinned libopus source did not define the opus target")
endif()

add_library(hamseda_opus_jni SHARED opus_jni.cpp)
target_compile_features(hamseda_opus_jni PRIVATE cxx_std_17)
target_include_directories(hamseda_opus_jni PRIVATE "${opus_SOURCE_DIR}/include")
target_link_libraries(hamseda_opus_jni PRIVATE opus)

# Explicit 16 KiB ELF segment alignment for Android 15+ devices using 16 KiB pages.
target_link_options(hamseda_opus_jni PRIVATE "-Wl,-z,max-page-size=16384")
