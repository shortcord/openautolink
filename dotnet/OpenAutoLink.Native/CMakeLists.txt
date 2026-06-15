cmake_minimum_required(VERSION 3.22.1)
project(oal_shim LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(oal_shim SHARED
    src/oal_shim.cpp
)

target_include_directories(oal_shim PUBLIC include)
