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

## Fundamental limitation

Beat/tempo analysis alone does not identify an absolute song section. If two measures share the same pulse, microphone BPM cannot tell measure 12 from measure 72. Therefore the user starts from the beginning or chooses a known starting measure. Recovery from arbitrary jumps/repeats requires either manual repositioning or future content-aware score following, which is outside the base scope.
