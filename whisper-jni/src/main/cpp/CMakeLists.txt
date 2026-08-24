cmake_minimum_required(VERSION 3.22.1)
project(whisper-jni)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(WHISPER_SRC ${CMAKE_CURRENT_LIST_DIR}/whisper.cpp)

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
