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
    position/PositionMatcher.cpp
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
        audio/AudioAnalyzer.cpp
        dsp/Fft.cpp
        dsp/Stft.cpp
        dsp/Chroma.cpp
        dsp/SpectralFlux.cpp
        position/ScoreReference.cpp
        position/FeatureRing.cpp
        position/DtwMatcher.cpp
        position/PositionMatcher.cpp
        beat/BeatTracker.cpp
        transport/LiveTransport.cpp
        tests/host/test_spsc_ring.cpp
        tests/host/test_main.cpp
        tests/host/test_fft.cpp
        tests/host/test_hann.cpp
        tests/host/test_chroma.cpp
        tests/host/test_flux.cpp
        tests/host/test_feature_pipeline.cpp
        tests/host/test_position.cpp
        tests/host/test_position_matcher.cpp
        tests/host/test_beat_tracker.cpp
        tests/host/test_feature_grid_timing.cpp
        tests/host/test_transport_timeline.cpp
        tests/host/test_analyzer_discontinuity.cpp
        tests/host/test_test_pcm_writer_gate.cpp
    )
    add_executable(temposcore_host_tests ${TEMPOSCORE_HOST_TEST_SOURCES})
    target_include_directories(temposcore_host_tests PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
    add_test(NAME host_tests COMMAND temposcore_host_tests)

    add_executable(temposcore_feature_grid_evidence
        tests/host/feature_grid_evidence.cpp
        dsp/Fft.cpp
        dsp/Stft.cpp
    )
    target_include_directories(temposcore_feature_grid_evidence PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

    add_executable(temposcore_transport_timeline_evidence
        tests/host/transport_timeline_evidence.cpp
        transport/LiveTransport.cpp
    )
    target_include_directories(temposcore_transport_timeline_evidence PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

    # Long, manually invoked end-to-end PCM/MIDI evidence. Intentionally not a
    # CTest: runtime is minutes and its PASS/FAIL metrics are reviewed separately.
    add_executable(temposcore_t7_end_to_end_evidence
        tests/host/t7_end_to_end_evidence.cpp
        audio/SpscAudioRing.cpp
        audio/AudioAnalyzer.cpp
        dsp/Fft.cpp
        dsp/Stft.cpp
        dsp/Chroma.cpp
        dsp/SpectralFlux.cpp
        position/ScoreReference.cpp
        position/FeatureRing.cpp
        position/DtwMatcher.cpp
        position/PositionMatcher.cpp
        beat/BeatTracker.cpp
        transport/LiveTransport.cpp
    )
    target_include_directories(temposcore_t7_end_to_end_evidence PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
endif()
