cmake_minimum_required(VERSION 3.22.1)
project(whisper-jni)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(WHISPER_SRC ${CMAKE_CURRENT_LIST_DIR}/whisper.cpp)

# Android Gradle builds the debug variant with CMAKE_BUILD_TYPE=Debug, which leaves clang at
# its -O0 default. Unoptimized ggml is catastrophically slow: a 2.2s phrase took 19s to
# transcribe with tiny.en on a Snapdragon 8 Elite. Debug APKs are what gets sideloaded for
# testing, so always optimize the numerical code regardless of variant.
set(CMAKE_C_FLAGS_DEBUG "${CMAKE_C_FLAGS_DEBUG} -O3")
set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} -O3")

# Matches whisper.cpp's own Android example, which ships a dedicated armv8.2+fp16 variant:
# native FP16 arithmetic is what makes the f16 ggml models fast, and dotprod accelerates the
# quantized cleanup model. Requires an ARMv8.2 CPU (2018+); older arm64 devices are not supported.
if (ANDROID_ABI STREQUAL "arm64-v8a")
    add_compile_options(-march=armv8.2-a+fp16+dotprod)
endif()

set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

add_subdirectory(${WHISPER_SRC} whisper-build)

add_library(whisper-jni SHARED whisper_jni.cpp)

find_library(LOG_LIB log)

target_include_directories(whisper-jni PRIVATE
        ${WHISPER_SRC}/include
        ${WHISPER_SRC}/ggml/include)

target_link_libraries(whisper-jni whisper ${LOG_LIB} android)
