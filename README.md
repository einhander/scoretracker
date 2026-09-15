# TempoScore scaffold

Android practice assistant scaffold for **Android 10 / API 29**.

The application loads a Standard MIDI File, uses it as the score/tempo reference, listens to the live ensemble through the phone microphone, estimates live beat/tempo, advances a live musical transport, and shows where the player is plus the notes coming next.

Important product constraints:

- microphone input only for live following;
- **no MIDI keyboard/controller input**;
- **no pitch/note recognition from audio**;
- MIDI supplies notes, time signatures and the initial/reference tempo;
- audio supplies rhythmic evidence (onsets / beat / tempo / phase);
- absolute song position is acquired by **content-aware score following** (matching the live
  audio against the MIDI score) and then tracked continuously; the user can start from the
  beginning or reposition with Reset.

## Toolchain

This scaffold intentionally follows the useful parts of `einhander/piano`:

- Android Gradle Plugin 8.2.0
- Kotlin 1.9.20
- Gradle 8.5
- JDK 17
- compileSdk 34
- **targetSdk 29**
- minSdk 26
- NDK 26.1.10909125
- CMake 3.22.1
- C++17
- arm64-v8a + armeabi-v7a

Native audio uses **Oboe 1.10.0 through Maven Prefab**. FluidSynth and external MIDI device support are intentionally not included.

## Build

1. Install JDK 17, Android SDK platform 34, NDK `26.1.10909125`, and CMake `3.22.1`.
2. Copy `local.properties.template` to `local.properties` and set `sdk.dir`.
   For this machine, set environment:
   `export JAVA_HOME=/home/einhander/tools/jdk-17.0.14+7`
   `export ANDROID_HOME=/home/einhander/tools/android-sdk` and `export ANDROID_SDK_ROOT=/home/einhander/tools/android-sdk`
3. Run:

```bash
./build.sh debug
```

or:

```bash
./gradlew :app:assembleDebug
```

The official Gradle 8.5 wrapper is included; `./gradlew` works out of the box (distribution is fetched on first use if not cached).

## What already exists

- SAF MIDI file picker;
- dependency-free SMF 0/1 parser (PPQ timing);
- Note On/Off, tempo and time-signature parsing;
- live score position model;
- simple five-line staff-like preview with fixed playhead;
- Now / Next note labels;
- JNI boundary;
- Oboe low-latency microphone input;
- lightweight energy-onset + MIDI-prior BPM bootstrap tracker;
- smoothed transport BPM and basic PLL-like phase correction;
- **content-aware score following**: STFT/chroma/spectral-flux feature analysis, a constrained
  DTW matcher (global acquisition + local correction + repeat disambiguation), and a
  position-tracking state machine (`Acquiring → Locked → Weak → Reacquiring`) that corrects the
  live transport;
- tempo-map integration (Kotlin `tempoAtQuarterBeat` / `quarterBeatToSeconds` / `secondsToQuarterBeat`);
- JVM parser + tempo-map regression tests; host C++ unit tests (ring, DSP, DTW matcher, position
  state machine, transport correction policy).

## What is deliberately not production-ready

The current `BeatTracker` is only an architectural bootstrap. It uses energy rises, not robust multiband spectral flux, and will fail on many real musical passages. The proper tracker is described in `SPEC.md` and `AGENT_INSTRUCTIONS.md`.

Likewise, `ScoreStaffView` is a visualization placeholder, not a notation engraver. Proper clefs, accidentals, voices, beams, ties, rhythmic quantization and multi-staff layout belong to a later milestone.

The score-following matcher is a first production pass: it is tuned for the common case (a
recognizable ensemble, a MIDI that matches the live material) and degrades to `Weak`/`Reacquiring`
rather than silently jumping to the wrong section. Live-audio acceptance is manual (no emulator on
the build machine); the matcher is covered by host C++ unit tests and the transport correction
policy by deterministic host tests.

## Key files

- `SPEC.md` — product/technical specification.
- `AGENT_INSTRUCTIONS.md` — detailed implementation guide for a coding agent.
- `docs/architecture.md` — component and threading model.
- `docs/realtime-rules.md` — hard real-time rules.
- `app/src/main/cpp/beat/BeatTracker.*` — replaceable beat-tracking prototype.
- `app/src/main/cpp/audio/OboeInputEngine.*` — microphone backend (owns the score reference + matcher).
- `app/src/main/cpp/audio/AudioAnalyzer.*` — non-RT feature analysis (STFT/chroma/flux) + matcher cadence.
- `app/src/main/cpp/dsp/*` — radix-2 FFT, STFT, chroma, spectral flux (own minimal DSP, no third-party).
- `app/src/main/cpp/position/DtwMatcher.*` — constrained DTW (global/local) + repeat disambiguation.
- `app/src/main/cpp/position/PositionMatcher.*` — position-tracking state machine + confidence policy.
- `app/src/main/cpp/transport/LiveTransport.*` — musical transport + position correction (slew/relocate).
- `app/src/main/cpp/native_audio_jni.cpp` — JNI boundary (12-field state, score reference, reacquire).
- `app/src/main/cpp/tests/host/*` — host C++ unit tests (run via CTest, no GTest).
