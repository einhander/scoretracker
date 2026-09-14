cmake_minimum_required(VERSION 3.22.1)
project(temposcore_native LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(temposcore_native SHARED
    native_audio_jni.cpp
    audio/OboeInputEngine.cpp
    beat/BeatTracker.cpp
    transport/LiveTransport.cpp
)

target_include_directories(temposcore_native PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

find_package(oboe REQUIRED CONFIG)
find_library(log-lib log)

target_link_libraries(temposcore_native
    PRIVATE
    oboe::oboe
    ${log-lib}
)
