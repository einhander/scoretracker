# Captured-frame / transport-history phase-2 evidence

## Implemented timeline contract

- `AudioSourceSpan` tags each accepted ring descriptor with `streamEpoch`,
  `continuityEpoch`, `positionGeneration`, first captured frame, and exact
  count. `OboeInputEngine` advances captured frame count before ring write and
  calls transport with that same source range. Position reset does not reset
  captured frame numbering; start/restart increments stream epoch and starts a
  new source-frame origin.
- `SpscAudioRing::readStamped` returns only one contiguous descriptor per call.
  A dropped suffix increments dropped count, remains absent from analyzer PCM,
  and causes next accepted write to carry a new continuity epoch. Read/write
  sample ownership and drop-incoming behavior remain unchanged. Descriptor
  queue has one slot per sample-capacity element, so metadata does not impose a
  tighter producer-block limit than sample capacity.
- `AudioAnalyzer` uses source stamps for absolute STFT centers. It resets STFT,
  spectral flux, beat history, feature ring and matcher context on source gaps,
  stream changes, or position-generation changes. It never feeds STFT across a
  missing source interval. History queue overflow clears feature/matcher context
  and invalidates position confidence; score position itself is not reset.
- `AudioAnalyzerDiagnostics` now distinguishes captured source frame, ring
  written/consumed/dropped sample counts, oldest buffered source frame, and
  latest processed feature/source frame. Counts are individually sampled
  atomics, not a transaction; ring backlog is capacity-clamped.
- Captured-frame watermark is a lock-free monotonic total across stream epochs,
  incremented by `LiveTransport::processSourceFrames` before ring writes. It does
  not depend on analyzer draining history; analyzer also exposes epoch-local
  latest-processed/oldest-buffered frames and separate latest-history frame.
  Ring written/consumed/dropped counters are per ring reset; cumulative capture
  watermark is global, so compare only counters with matching scopes.
- Test PCM session token originates in Kotlin `AtomicLong` playback-run counter,
  is immutable in `PlaybackRun`, returned/verified by tokenized `startTest`, and
  passed unchanged to each push/stop. Production has no native reserve/current-
  token getter on push. `TestPcmSessionIdentity` tracks latest-started and
  monotonic revoked-through caller token; delayed startTest calls with revoked
  or superseded tokens fail. JNI push rejects invalid/non-active token before
  `GetArray` where possible, and Oboe checks again before PCM ring writes.
- Native `TestPcmWriterGate` separately owns packed internal session epoch,
  closed bit and in-flight count. Stop closes admission; reopen only increments
  epoch after registered writes drain. A paused old caller token cannot enter
  after reopen; monotonic Kotlin IDs cannot ABA. Token-scoped `stopTest` only
  tears down engine still owned by that caller token, so late cleanup cannot
  stop newer session or microphone. A non-RT mutex serializes JNI
  test writers; Oboe callback never touches this mutex. Playback stop uses
  per-run stop/pause/AudioTrack refs, 1.5 s bounded join, and retains any still
  alive `Thread` in a retired-run set; stale worker cleanup uses its token and
  cannot stop a newer session.
- `LiveTransport` records callback segments containing source interval, actual
  start position, integrated base-beat start/delta, and applied correction
  delta. Callback-owned history is binary searched locally; its bounded SPSC
  copy is independently owned by analyzer. Segment payloads are plain data
  behind release/acquire SPSC indices; callback never reads worker-owned or
  concurrently mutated payloads. Stream history capacity 32,768 segments
  retains 2.73 s at 192 kHz with 16-frame callbacks, exceeding 2 s stale limit
  plus margin under that quantum. Smaller callback quanta can exhaust bounded
  history earlier; exact lookup then fails closed. Queue overflow never
  overwrites unread slots and fails closed.
- Matcher prediction uses history at its last feature's exact source frame.
  Observation includes source frame, stream/continuity/position epochs, and
  monotonically increasing sequence. At callback apply, projected correction
  is `matchedAtF + (baseBeatsNow - baseBeatsAtF) - actualPositionNow`; raw
  same-frame residual is separately recorded. Reject reasons include wrong
  epoch, future frame, age >2 s, missing history, and duplicate sequence.
- Existing large/global relocation confidence and uniqueness gates remain.
  Every sub-4-beat offset, including <0.5 beat, now follows existing bounded
  0.15–0.50 beat/s slew; no new gain or instant small-error snap. Base, applied
  correction, actual beat deltas, source-frame age, raw/projected residual,
  sequence, epochs and rejection code travel through bounded preallocated
  diagnostics channel only when explicitly enabled. No callback logs, locks,
  allocation, JNI, or export I/O.

## Host tests and artifacts

Release build in `/tmp/opencode/temposcore-phase1-host`; run CTest there. New
production-class tests cover exact historical interpolation, observation ages
50/250/1000/2000 ms with BPM change during delay, prior applied corrections,
including a correction applied after match frame but before delayed delivery;
stale/wrong-epoch/duplicate/missing-history rejection, positive/negative small
correction slew and effective-vs-base delta, position reset epoch, history
queue overflow, stamped ring drops, and actual `AudioAnalyzer` DSP reset plus
confidence invalidation for 0.1/0.5/2.0-second source gaps.

`test_test_pcm_writer_gate` uses production admission/identity helpers to pause
an old token before registration, close/reopen the gate, and verify stale
admission invokes no PCM-write action/source-frame advance; current token is
accepted. Separate test closes admission with a registered writer, verifies
stop waits for its release, and tests late old-token cleanup against a newer
caller session. Watermark test leaves the history SPSC undrained through
overflow and verifies capture watermark continues across a stream restart.

Follow-up tests use production `TestPcmWriterGate` with a captured-token pause
before admission across stop/restart, active-writer drain, stalled history
consumer/overflow while capture watermark advances, and epoch transition while
the cumulative watermark remains monotonic.

Three-minute deterministic `LiveTransport` evidence (correct synthetic match,
not DTW/audio):

- CSV `/tmp/opencode/temposcore-phase2-transport.csv`
- SVG `/tmp/opencode/temposcore-phase2-transport.svg`
- generator `temposcore_transport_timeline_evidence`
- Baseline starts +0.2 beat, no match: final error +0.2 beat; base/effective
  123/123 BPM.
- Historical-match scenario starts +0.2 beat, sends exact ground-truth match
  at source frame from 250 ms earlier every 2 s: final sampled error 0.0 beat;
  base/effective 123/123 BPM after bounded correction converges. This isolates
  source-age math and demonstrates bounded slew; it is not a real matcher test
  or closed-loop acoustic result.

Phase-1 grid CSV/SVG remain at `/tmp/opencode/temposcore-phase1-feature-grid.csv`
and `/tmp/opencode/temposcore-phase1-feature-grid.svg`.

## Validation boundaries

- Observation age is checked at callback source-start boundary (before
  integrating next block), so observation arriving at exactly +2 s remains
  admissible. Projection includes base movement through the block being
  processed before bounded slew begins at its source-start boundary.
- Host CTest suite includes all prior tests plus new production transport and
  analyzer discontinuity tests; phase-2 Release build and CTest passed (1/1
  CTest suite).
- NDK 26.1.10909125 compile-time lock-free probes passed for atomic uint32,
  uint64 and double on `armeabi-v7a` and `arm64-v8a`. Added corresponding
  compile-time assertions in native headers; core timeline headers compiled
  for both targets. Oboe integration translation unit remains for parent Android
  build. SPSC payloads themselves remain non-atomic and are accessed only under
  queue ownership.
- T2/T3/T5 host mechanism tests PASS. T7 end-to-end PCM+MIDI matcher drift is
  NOT RUN: current evidence injects known synthetic matches into the production
  transport and does not establish actual DTW lock/alignment. T8 acoustic and
  T9 AudioTrack playback-head checks are NOT RUN. No acoustic synchronization
  claim follows from these host tests.
- Android/JVM/APK build remains parent validation. No Kotlin/JNI surface,
  microphone pitch detection, parser, MIDI model, BeatTracker policy, or UI
  change in this phase.
