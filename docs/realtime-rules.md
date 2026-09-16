# Real-time rules

The Oboe data callback is a hard real-time path.

Do not do any of the following in `onAudioReady`:

- allocate/free memory;
- use mutexes, condition variables or blocking queues;
- perform file/network I/O;
- call JNI/Kotlin;
- log;
- sleep;
- resize STL containers;
- run heavy FFT/STFT work once the production tracker is introduced;
- run the score-following matcher / constrained DTW (it allocates and does O(K·N) work —
  it runs on the analyzer thread, never in the callback).

The production architecture must be:

```text
Oboe callback (hard RT)
  -> copy PCM into preallocated SPSC audio ring
  -> processFrames using latest atomic tempo snapshot
  -> sample-frame-based LiveTransport (slew/relocate toward latest position target)
  -> atomic TransportState
  -> Kotlin UI polls snapshot

Analyzer thread (non-RT, ~10 Hz features, ~2 s matcher cadence)
  -> AudioAnalyzer: STFT / multiband spectral flux every hop
  -> BeatTracker::processFlux -> atomic tempo snapshot
  -> chroma/features at 10 Hz
  -> FeatureRing (200 frames = 20 s live context)
  -> PositionMatcher: constrained DTW (global acquisition + local correction)
       + position-tracking state machine (Acquiring/Locked/Weak/Reacquiring)
  -> LiveTransport.submitPositionObservation (publishes position state + correction target)
```

Cross-thread hand-offs use atomics / ring only:

1. PCM: Oboe callback → analyzer, via the preallocated SPSC ring.
2. Position target: analyzer → Oboe callback, via the persistent target atomics
   (`LiveTransport::submitPositionObservation`); the transport slews toward it.
3. Reacquire request: main thread → analyzer, via the `PositionMatcher` atomic flag
   (applied on the analyzer thread in `update()`).
4. Tempo snapshot: analyzer → Oboe callback, via bounded coherent tempo atomics.
5. Transport requests: main thread → Oboe callback, via the request atomics
   (`requestedExpectedBpm_`, `requestedPosition_`, running flag).

The matcher never touches the transport's musical position directly; it only proposes a target.

The audio frame count is the transport clock. Android wall-clock timers may refresh the UI but must never define musical position.
