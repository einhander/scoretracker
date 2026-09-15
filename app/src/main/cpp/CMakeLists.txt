cmake_minimum_required(VERSION 3.22.1)
project(temposcore_native LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(ANDROID)
add_library(temposcore_native SHARED
    native_audio_jni.cpp
    audio/OboeInputEngine.cpp
    audio/SpscAudioRing.cpp
    audio/AudioAnalyzer.cpp
    dsp/Fft.cpp
    dsp/Stft.cpp
    dsp/Chroma.cpp
    dsp/SpectralFlux.cpp
    position/ScoreReference.cpp
    position/FeatureRing.cpp
    position/DtwMatcher.cpp
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
endif()

if(NOT ANDROID)
    enable_testing()
    set(TEMPOSCORE_HOST_TEST_SOURCES
        audio/SpscAudioRing.cpp
        dsp/Fft.cpp
        dsp/Stft.cpp
        dsp/Chroma.cpp
        dsp/SpectralFlux.cpp
        position/ScoreReference.cpp
        position/FeatureRing.cpp
        position/DtwMatcher.cpp
        tests/host/test_spsc_ring.cpp
        tests/host/test_main.cpp
        tests/host/test_fft.cpp
        tests/host/test_hann.cpp
        tests/host/test_chroma.cpp
        tests/host/test_flux.cpp
        tests/host/test_feature_pipeline.cpp
        tests/host/test_position.cpp
    )
    add_executable(temposcore_host_tests ${TEMPOSCORE_HOST_TEST_SOURCES})
    target_include_directories(temposcore_host_tests PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
    add_test(NAME host_tests COMMAND temposcore_host_tests)
endif()
