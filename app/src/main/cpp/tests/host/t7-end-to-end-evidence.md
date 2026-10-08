# T7 production PCM/MIDI end-to-end evidence

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
