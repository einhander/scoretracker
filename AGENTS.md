# AGENTS.md — TempoScore

Android 10 (targetSdk 29) practice assistant: loads a Standard MIDI File as the
score/tempo reference and follows the live ensemble's tempo/phase through the
microphone. Before changing anything, read `SPEC.md` and `AGENT_INSTRUCTIONS.md`
(in full; written in Russian) — they are the product/implementation contract.
`docs/architecture.md` and `docs/realtime-rules.md` cover the component and
real-time model.

## Build & test (this machine)

```sh
export JAVA_HOME=/home/einhander/tools/jdk-17.0.14+7
export ANDROID_HOME=/home/einhander/tools/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
```

- Build: `./build.sh debug` (or `./gradlew :app:assembleDebug`).
- Tests: `./gradlew :app:testDebugUnitTest` — JVM unit tests only (MIDI parser,
  track selection, navigator). No instrumented tests, no emulator on this
  machine: UI verification = build + JVM tests; device checks are manual.
- `local.properties` is gitignored: copy `local.properties.template` and fix
  `sdk.dir`, or rely on the `ANDROID_HOME` env. It also carries local release
  signing creds (`temposcore.*`) — never commit it.
- `:app:assembleRelease` fails in `lintVitalRelease` (targetSdk 29 triggers
  `ExpiredTargetSdkVersion`). CI passes `-x lintVitalRelease`; do the same
  locally.
- No linter/formatter is configured — match the existing Kotlin/C++ style by
  hand; do not add tooling without a concrete need.

## Hard constraints (do not "modernize")

- `targetSdk` stays **29**; `compileSdk 34` is deliberate.
- No MIDI keyboard/controller input, no Android MIDI device discovery.
- No pitch detection, audio-to-MIDI, or chord recognition.
- Mic supplies rhythm evidence only; MIDI supplies notes, time signatures, and
  the tempo reference.
- Audio DSP stays in C++. Oboe 1.10.0 comes through Prefab — do not vendor it;
  no FluidSynth; no large audio framework for one FFT.
- `onAudioReady()` is hard real-time: no allocation, locks, logging, JNI, file
  I/O, or thread creation (see `docs/realtime-rules.md`).
- JNI stays narrow: `initialize / start / stop / setExpectedBpm /
  resetPosition / getState`. No per-note JNI calls, no JNI from the Oboe
  callback.

## Architecture

- Kotlin: lifecycle, mic permission, SAF, MIDI parsing, model, display, UI.
  C++: Oboe stream, ring buffer, DSP, tempo/phase tracking, transport, atomic
  state publication. JNI boundary: `app/src/main/cpp/native_audio_jni.cpp`.
- Package direction is one-way: `midi/` (parser + model) ← `score/`
  (navigation, `TrackSelection`) ← `ui/` (views), with `MainActivity` at the
  root. `midi` must never import from `score` (layering rule enforced in
  review).
- `BeatTracker` and `ScoreStaffView` are deliberate scaffold placeholders — do
  not polish them indefinitely; the production plan and milestone order are in
  `AGENT_INSTRUCTIONS.md` (§10–14, §29, §30).

## Git & release

- Branch `main`; CI (`.github/workflows/build-apk.yml`) runs on `main`,
  `feature/*`, `fix/*` pushes and PRs.
- Conventional commits in English (`feat:`, `fix:`, `docs:`, `build:`,
  `chore:`, `ci:`).
- Release = push an annotated `v*` tag (e.g. `v0.1.1`): CI builds the signed
  release APK (from `SCORE_KEYSTORE_*` secrets) and creates a GitHub Release.
  The tag is the single source of truth for `versionName` — do not bump
  `baseVersion` in `app/build.gradle.kts` to cut a release.
- Keep out of commits: `local.properties`, keystores, `.slim/deepwork/`
  (deepwork progress files — git-local, OpenCode-readable via `.ignore`;
  deliverables belong in `src/`/`docs/`), `.playwright-mcp/`.
- Pushes use this machine's SSH deploy key (write access granted); if a push
  is denied, check the repo's Deploy keys settings.
