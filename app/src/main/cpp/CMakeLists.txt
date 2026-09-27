cmake_minimum_required(VERSION 3.22.1)
project(vetro_whisper C CXX)

# whisper.cpp — сабмодуль app/src/main/cpp/whisper.cpp (закреплённый тег). Только CPU-бэкенд:
# на Android он стабилен на всех SoC, GPU-бэкенды ggml для Adreno/Mali пока экспериментальные.
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
# ARMv8.2 dotprod + fp16 — есть на всех 64-битных SoC с 2018 года; ускоряет квантованные q5_1 в разы.
if (ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif ()

add_subdirectory(whisper.cpp)

add_library(vetro_whisper SHARED vetro_whisper.cpp)
target_link_libraries(vetro_whisper PRIVATE whisper log)
target_compile_options(vetro_whisper PRIVATE -O3)
