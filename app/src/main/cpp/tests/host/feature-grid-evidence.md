# Feature-grid phase-1 evidence

Regenerate on a host build:

```sh
cmake -S app/src/main/cpp -B build-host -DCMAKE_BUILD_TYPE=Release
cmake --build build-host -j
ctest --test-dir build-host --output-on-failure
build-host/temposcore_feature_grid_evidence /tmp/feature-grid.csv
```

`test_feature_grid_timing` drives production `FeatureGrid`, `FeatureRing`, and
`FeatureMatcherCadence` using STFT-center sample positions. It checks
180 s at 44.1/48 kHz, monotonic center timestamps, <= one hop lateness, measured
200-frame context duration, first-20-frame warmup, ~2 s matcher cadence, reset,
and missed-deadline catch-up without duplicate centers. Its legacy comparison
replays the old `next = selectedCenter + sampleRate/10` recurrence and asserts
that its result is not near 10 Hz; it is a regression oracle, not an acoustic
test.

The separate `temposcore_feature_grid_evidence` executable drives real `Stft`
with deterministic silence and emits both legacy and fixed selected-center
timestamps to CSV. Its current output and plotted timestamp error are in the
local baseline/evidence workspace (not checked in):

- `/tmp/opencode/temposcore-phase1-feature-grid.csv`
- `/tmp/opencode/temposcore-phase1-feature-grid.svg`
- Baseline arithmetic-only probe: `/tmp/opencode/temposcore-baseline.csv`

| Rate | Legacy count / mean | Fixed count / mean | Fixed measured feature-ring span |
|---|---:|---:|---:|
| 44.1 kHz | 1,550 / 8.61328 Hz | 1,800 / 9.99952 Hz | 19.9 s (200 entries, 199 measured intervals) |
| 48 kHz | 1,688 / 9.37502 Hz | 1,800 / 9.99976 Hz | 19.9 s (200 entries, 199 measured intervals) |

The production worker calls `FeatureGrid` on every STFT center after the
per-hop BeatTracker update. The deadline accumulator advances by exact rational
sample units (`sampleRate / 10`) and retains the selected STFT center as the
feature timestamp. Reset recreates the grid at the recreated STFT's first
center. If a deadline is missed, only the latest offered center is published;
intervening deadlines are counted and skipped, never filled with duplicate
frames.

`FeatureRing::durationSeconds()` measures `(lastCenter-firstCenter)/sampleRate`.
The worker publishes that value as `validContextSeconds`; DTW's live span also
uses measured timestamps. The reference score remains sampled at its existing
0.1-second cadence. A 200-feature ring therefore spans about 19.9 seconds
between its first and last timestamps, not an assumed 20.0 seconds.

`AudioAnalyzer::diagnostics()` exposes independently-loaded atomic scalars for
sample rate, consumed frames, feature count/rate/latest center, skipped grid
deadlines, current ring backlog, matcher run count/interval, and last matcher
compute time. It is approximate and intentionally not a coherent snapshot.
The backlog observation loads consumer tail before producer head and clamps the
result to physical capacity, so it cannot underflow or claim impossible depth.
Diagnostics run on/are read outside `onAudioReady()`; no new callback work,
logging, export I/O, or non-atomic shared telemetry was added.

**Time-origin limitation:** feature centers remain analyzer-local. STFT reset
restarts that local index; mapping centers through capture-frame origin,
dropouts, matcher observation age/history, and transport correction are later
phases. The existing ring can drop PCM suffixes without exposing a discontinuity
to this phase. These host results prove grid arithmetic/timestamps for
continuous synthetic input, not music alignment, real backlog behavior,
effective cursor drift, or acoustic synchronization. No microphone or device
test was run.
