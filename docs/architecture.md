# Architecture

```text
                          MIDI file
                             |
              +--------------+---------------+
              |                              |
        MidiFileParser                  score model
  tempo map / meter / notes                 |
              |                              v
              |                        ScoreStaffView
              |                      Now / Next notes
              v                              ^
       expected tempo prior                  |
              |                              |
              v                              |
Microphone -> Oboe -> beat tracker -> LiveTransport
              C++         C++             C++
                          |                |
                          +-------> TransportState
                                       atomics
                                         |
                                        JNI
                                         |
                                       Kotlin

  Score following (slow loop, analyzer thread — never the RT callback):

  Oboe callback --(SPSC ring)--> AudioAnalyzer (STFT/chroma/spectral-flux @ 10 Hz)
                                       |
                                       v
                              FeatureRing (200 frames = 20 s live context)
                                       |   ~2 s feature-time cadence
                                       v
                              PositionMatcher (state machine)
                                - DtwMatcher: global acquisition + local correction
                                - confidence = dtw x validFraction x stability
                                - Acquiring -> Locked -> Weak -> Reacquiring
                                       |  PositionObservation
                                       v
                              LiveTransport.submitPositionObservation
                                - slew (small error) / hard relocate (large, gated)
                                - publishes positionConfidence / matchedPosition /
                                  error / state / ambiguity / validContextSeconds
                                       |
                              TransportState atomics -> JNI -> Kotlin UI
```

## Layer ownership

### Kotlin / Android

Owns UI, Storage Access Framework file selection, MIDI parsing/model, score navigation and presentation. It must not attempt microphone pitch recognition or connect to Android MIDI devices.

### C++ / native

Owns low-latency microphone capture, DSP/beat tracking and the live transport. The C++ boundary exists now so a production STFT/onset tracker can be introduced later without restructuring the application.

### Transport semantics

The transport coordinate is **quarter-note beats from the selected start position**. At 120 BPM it advances by 2.0 quarter beats per second. Time signatures are presentation metadata: they map the continuous quarter-beat coordinate to bar/beat labels.

The live transport has separate concepts:

- `expectedBpm`: prior from MIDI;
- `detectedBpm`: noisy audio estimate;
- `transportBpm`: smoothed BPM used for cursor motion;
- `quarterBeatPosition`: continuously integrated score position;
- `confidence`: beat tracker confidence.

## Score following

Beat/tempo analysis alone does not identify an absolute song section: if two measures share the
same pulse, microphone BPM cannot tell measure 12 from measure 72. TempoScore therefore runs a
**content-aware score follower** on the analyzer thread. It matches the live audio (chroma +
spectral flux) against the MIDI score with a constrained DTW, acquires the absolute position
globally (top-K coarse pass + fine DTW), then tracks it locally with a bounded continuity prior.
Repeats and ambiguous passages are handled by preferring a non-neighbour second-best candidate and
by a confidence/ambiguity margin; when the match degrades the state machine falls back to
`Weak`/`Reacquiring` rather than silently jumping to the wrong section.

The live transport is corrected by the follower: small position errors are slewed in (bounded
rate), large errors are hard-relocated only when the match is strong (or a confident global
re-acquisition). The user can also reposition with Reset (a fresh global acquisition).

The matcher runs on the analyzer thread at a ~2 s feature-time cadence — **never** in the Oboe
real-time callback (see `docs/realtime-rules.md`).
