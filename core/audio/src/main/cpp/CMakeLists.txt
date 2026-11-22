cmake_minimum_required(VERSION 3.22.1)

project("horn")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(oboe REQUIRED CONFIG)

add_library(horn SHARED
    native_engine.cpp
    native_jni.cpp
)

target_link_libraries(horn
    oboe::oboe
    android
    log
)

# 16 KB page-size compatible ELF segments for Android 15+ arm64 devices.
target_link_options(horn PRIVATE "-Wl,-z,max-page-size=16384")
