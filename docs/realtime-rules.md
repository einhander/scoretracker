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
- run heavy FFT/STFT work once the production tracker is introduced.

The production architecture must be:

```text
Oboe callback
  -> preallocated SPSC audio/features ring
  -> native analysis worker (STFT/onsets/beat tracking)
  -> atomic/latest BeatObservation
  -> audio callback consumes latest observation
  -> sample-frame-based LiveTransport
  -> atomic TransportState
  -> Kotlin UI polls snapshot
```

The audio frame count is the transport clock. Android wall-clock timers may refresh the UI but must never define musical position.
