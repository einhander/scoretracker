# T7 production PCM/MIDI end-to-end evidence

Phase-3 ablations and diagnostics appended below. Original harmonic fixture
remains primary acceptance case; variants are diagnostic and never substitute
for its T7 result.

## Reproduction

Build the standalone long-running harness (not registered in CTest):

```sh
cmake -S app/src/main/cpp -B build-host -DCMAKE_BUILD_TYPE=Release
cmake --build build-host --target temposcore_t7_end_to_end_evidence -j
build-host/temposcore_t7_end_to_end_evidence 44100 /tmp/t7-44100.csv 180
build-host/temposcore_t7_end_to_end_evidence 48000 /tmp/t7-48000.csv 180
```

Harness synthesizes deterministic, non-repeating 123 BPM MIDI note pitches,
matched harmonic/percussive PCM attacks, and a known 8-quarter initial score
offset. Transport starts at score beat 0 (only expected BPM prior set); no
known position is submitted. Each production `AudioAnalyzer`, `PositionMatcher`,
`DtwMatcher`, stamped ring, history, and `LiveTransport` executes in path.
PCM is sent in 480-frame/10 ms callback partitions; producer waits for ring
drain every 100 ms. This is paced worker validation, not wall-clock acoustic
playback. Both runs completed 180 s captured timeline with no ring drops.

## T7 result: FAIL

The acceptance condition requires sustained lock and post-lock p95 absolute
position error below 0.5 quarter. Neither sample-rate run met all criteria:

| Rate | First lock | Locked fraction | Post-lock p95 / max abs error | Error slope (beats/s) | Mean base/effective BPM | Final position error |
|---|---:|---:|---:|---:|---:|---:|
| 44.1 kHz | 35.385 s | 35.6% | 0.943843 / 0.963237 | -0.000289955 | 123.046 / 122.927 | -0.545 beat |
| 48 kHz | no lock | 0% | N/A | N/A | 122.299 / 122.299 | -10.106 beats |

At 44.1 kHz matcher entered Locked intermittently (5,890 of 16,538 callback
records); matcher repeatedly left lock. Post-lock error p95 exceeds 0.5-quarter
criterion. At 48 kHz matcher never locked (18,000 callback records). Feature
grid delivered 1,799/1,798 features at 9.99915/9.99933 Hz, with no drops,
history misses, or history overflow. Match delivery age p50/p95/max was
118/160/165 ms at 44.1 kHz and 143/151/151 ms at 48 kHz.

The 48 kHz run's measured base tempo averaged 122.299 BPM against 123 BPM
synthetic truth; resulting source-time integration under-ran by about 2.1
additional beats across 180 s (on top of initial -8 beat offset). This is
consistent with the already-known integer-hop BeatTracker quantization
hypothesis, not proof of a particular acoustic source failure. At 44.1 kHz
base tempo averaged 123.046 BPM, but position confidence/lock remained
intermittent and post-lock p95 still failed. Matcher feature-to-score alignment
root cause remains unresolved by this fixture; do not mask failure with MIDI
position injection or BPM scaling.

## Artifacts / limitations

- `/tmp/opencode/t7-e2e-44100.csv`, `/tmp/opencode/t7-e2e-44100.svg`
- `/tmp/opencode/t7-e2e-48000.csv`, `/tmp/opencode/t7-e2e-48000.svg`
- Generator: `tests/host/t7_end_to_end_evidence.cpp`
- Standalone target: `temposcore_t7_end_to_end_evidence` (intentionally not CTest)
- Prior baseline material is arithmetic/transport mechanism-only at
  `/tmp/opencode/temposcore-baseline.csv`; an old-source T7 matcher comparison
  was not run. Therefore this is actual-path current-build evidence, not a
  baseline-vs-fixed T7 comparison.
- T2/T3/T5 host tests were separately green in phase-2 suite. T8 real-microphone
  and T9 AudioTrack playback-head tests remain NOT RUN. No acoustic or live
  synchronization-success claim follows.

## Phase 3 supplemental evidence correction

Known-source score mapping uses `truthBeat * 60 / tempo` and nearest actual
`ScoreFeatureFrame.nominalSeconds`, then checks quarter-beat coordinate. This
preserves initial offset: audio frame 0 maps to score beat 8, not beat 0. Score
reference is 10 Hz, so nearest-frame time error is bounded by 0.05 s (plus
floating tolerance) at both 44.1 and 48 kHz. Focused host regression covers
beat-8 mapping and time/beat tolerances at both rates.

Earlier phase-3 aligned-distance figures are superseded: prior formula
subtracted the beat-8 offset while selecting reference index and compared live
features near score start. Corrected harness uses actual production STFT
centers and score-time lookup. CSV contains source-time position error, matched-endpoint
residual at latest live feature center, base/effective BPM, feature-center and
processed-PCM time, matcher endpoint/cost/fraction data. Analyzer diagnostics
are separate atomics, not one coherent snapshot; values sampled while worker
runs may be from adjacent matcher updates. Treat candidate fields as
approximate, not a transactional row.

## Phase 3: tempo refinement and matcher diagnostics (FAIL retained)

BeatTracker now reports raw integer-peak BPM and interpolated selected lag
separately. Parabolic interpolation runs only after candidate ranking, only for
negative curvature, and clamps offset to half a feature frame. No confidence,
ranking, prior, PLL, or transport gain changed. Continuous-phase tests cover
90/110/120/123/125/140 BPM at both rates plus accent/subdivision/quiet attacks.
Existing estimator regressions remain separate and unchanged.

T7 writes worker-side raw/detected tempo, fractional lag, confidence, DTW
best/second cost and endpoints, plus live feature first/last source frames.
Harness accepts harmonic/pure fundamental and click/no-click variants and
calculates same-time feature-to-reference `frameDistance`; variants never seed
matcher positions. Release artifacts under `/tmp/opencode/temposcore-phase3-*`.

| Rate/fixture | Lock | Locked fraction | Post-lock p95 / max | Error slope | Aligned feature distance |
|---|---:|---:|---:|---:|---:|
| Earlier phase-3 ablation rows | superseded | | | | |

All variants retain 0 ring drops, history misses/overflows, and diagnostic
queue drops. Similar aligned-frame chroma distances and continued failures do
not establish fixture mismatch as cause. At 48 kHz initial global DTW endpoint
diagnostic is score beat 364.281 against truth 12.3255, cost 23809.7; at
44.1 kHz early match is beat 4.3 against truth 12.4849, low cost 0.166494, and
later locked endpoint 96.1458 against truth 98.1888. Evidence localizes issue
to global acquisition/ambiguous endpoint behavior but does not prove exact root
cause. No safe endpoint/time correction follows from current evidence.

Aligned distances in previous preliminary rows are superseded. Final figures
below use actual score-time mapping and STFT centers. Invalid mapping made the
earlier values irrelevant to source-alignment comparison.

Neutral invalid-frame distance regression covers note/rest target values,
mixed invalid/valid windows, all-invalid no-lock, and valid wrong-pitch no-lock.
Neutral invalid cost plus existing valid-frame-fraction confidence penalty
prevents silence/rest rows from accruing artificial catastrophic path cost or
raising confidence. This is a matcher evidence policy, not claim that silence
proves score location or that acoustic sync is fixed.

The corrected known-source mapping uses `truthBeat * 60 / tempo` and nearest
actual `ScoreFeatureFrame.nominalSeconds`; source frame zero maps to score beat
8 (nearest reference beat 7.99583), not beat zero. Reference spacing is 0.1 s;
nearest-frame time error bounded to 0.05 s at both rates. Focused host test
checks beat-8 mapping and time/beat tolerance at 44.1/48 kHz.

Tempo tests pass new calibrated output tolerances for smooth exact-phase input;
raw integer lag remains quantized. 48 kHz T7 diagnostic raw BPM was about
122.283 (lag about 23); interpolation improves estimate but does not fix score
acquisition. T7 still FAILS lock/p95 requirements. No coasting policy change:
available signals cannot safely distinguish rest, sustain, quiet passage, and
stopped/missing attacks. T4 estimator suite PASS; T6 coasting classification
NOT RUN (no grounded safe classifier); T7 FAIL. T8/T9 NOT RUN.

## Final corrected T7 runs

Correct aligned feature distance is 0.328850 / 0.331996 (harmonic 44.1/48 kHz),
and 0.360512 / 0.370695 (pure tone). Actual production STFT centers used.
Measurements compare equivalent score time; they do not imply exact spectral
agreement or prove matcher correctness.

| Fixture | Rate | First lock | Locked fraction | Post-lock p50 / p95 / max error | Slope beats/s | Mean base / effective BPM | Features / Hz |
|---|---:|---:|---:|---:|---:|---:|---:|
| harmonic+click | 44.1 kHz | 12.528 s | 91.90% | 0.538153 / 0.863803 / 0.964203 | -0.000804866 | 123.019 / 122.945 | 1799 / 9.99915 |
| harmonic+click | 48 kHz | 12.310 s | 92.00% | 0.527428 / 0.898346 / 1.27882 | -0.000634139 | 122.784 / 122.926 | 1800 / 9.99978 |
| pure+click | 44.1 kHz | 18.841 s | 82.59% | 0.560581 / 0.960125 / 1.57180 | -0.000138932 | 123.020 / 122.877 | 1799 / 9.99915 |
| pure+click | 48 kHz | 10.210 s | 84.00% | 0.535799 / 0.946429 / 1.75190 | +0.00175868 | 122.780 / 122.897 | 1800 / 9.99978 |

All runs completed 180 s with zero ring drops, history misses/overflow, and
diagnostic queue drops. Every run FAILS p95 absolute position error <0.5
quarter; maxima also exceed threshold. CSVs:
`/tmp/opencode/temposcore-phase3-refmap-{44100,48000}.csv` and
`/tmp/opencode/temposcore-phase3-refmap-pure-{44100,48000}.csv`. Each has
position-error, base/effective-speed, and feature-timestamp SVGs with
`-position.svg`, `-speed.svg`, `-features.svg` suffixes. Generator:
`tests/host/plot_t7_evidence.py`, Python standard library only.

Matcher diagnostics are separate atomics sampled while analyzer runs; fields
may span adjacent matcher updates. Candidate columns are approximate, not a
coherent transactional matcher tuple. Metrics above use harness lock/error and
transport diagnostics, not candidate tuple coherence.

| Test | Status | Evidence / limitation |
|---|---|---|
| T1 10 Hz grid | PASS | Host grid/worker tests; corrected runs 1799/1800 features at ~10 Hz. |
| T2 delayed observation/history | PASS | Phase-2 production history/projection host tests. |
| T3 base/effective cursor speed | PASS | Phase-2 correction tests; current CSV exports both metrics. |
| T4 tempo estimation | PASS (host suite) | Exact-phase/interpolation; not acoustic validation. |
| T5 PCM dropout | PASS | Phase-2 stamped-ring/discontinuity tests. |
| T6 rest/stop/coasting classification | NOT RUN / unsupported | No grounded classifier; no guessed silence-freeze policy. |
| T7 end-to-end position drift | FAIL | All corrected 180 s runs exceed p95 <0.5-quarter. |
| T8 microphone acoustic test | NOT RUN | No device/ensemble test performed. |
| T9 AudioTrack playback-head test | NOT RUN | No device playback-head validation performed. |

Code-confirmed: invalid live frames previously cost `1e6` per DTW step. Test-
confirmed: neutral note/rest cost, mixed windows, all-invalid no-lock, and valid
wrong-pitch no-lock regressions pass. Hypothesis: catastrophic invalid cost
contributed to previous acquisition/no-lock behavior; corrected runs lock more
often but still fail position tolerance. Acoustic success and a safe quiet/rest/
stopped classifier remain unestablished.
