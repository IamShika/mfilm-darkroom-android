cmake_minimum_required(VERSION 3.22.1)
project(mfilmdarkroom C)

set(ENGINE ${CMAKE_CURRENT_SOURCE_DIR}/mfilm)

add_library(mfilmdarkroom SHARED
        mfilm_jni.c
        port.c
        ${ENGINE}/fos_dev.c
        ${ENGINE}/fos_print.c
        ${ENGINE}/fos_looks.c
        ${ENGINE}/fos_film.c
        ${ENGINE}/fos_gfx.c
        ${ENGINE}/fos_jpeg.c)

target_include_directories(mfilmdarkroom PRIVATE ${ENGINE})
target_compile_options(mfilmdarkroom PRIVATE -O3 -std=gnu99 -Wall -Wno-unused-parameter)
target_compile_definitions(mfilmdarkroom PRIVATE MFILM_VERSION="${MFILM_VERSION}")
target_link_libraries(mfilmdarkroom log m)
