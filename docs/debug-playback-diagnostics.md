# Debug MP3 playback diagnostics

Debug builds automatically export one bounded CSV after each playback run stops,
reaches EOS, or exits through an error/cleanup path. Export runs from playback
worker cleanup, not the UI thread or native audio callback. Release builds do
not create diagnostics files.

Files live under app-private `files/diagnostics/`, with names
`playback-<session-token>-epoch-<run-epoch>.csv`. At most 20 CSV files are kept;
each file is capped at 256 KiB and display samples are capped at 1024 rows per
run. Starting a new run cannot append to or rename an old run's data.

The summary reports actual decoder output sample rate in Hz, frame counters,
decoder PTS in microseconds, AudioTrack timestamp/head frame estimate, and the
timestamp's monotonic nanoseconds where supported. AudioTrack frame position is
only a playback-clock estimate, not proof of physical sound. `head_source` marks
timestamp support or playback-head fallback. If decoder output sample rate
changes, format epochs and decoded-frame boundaries appear in `format_epoch`.
Decoder PTS is not used to set score position or transport BPM. Native push
frames are named `push_submitted_frames`: current JNI API does not return native
accept/reject status.

Display samples are captured by debug-only MainActivity instrumentation at most
4 Hz; `monotonic_ns` is `System.nanoTime()` in nanoseconds. Native transport beat
and displayed beat remain separate. CSV collection does not smooth or alter
either value.

## Retrieve from a connected device

After a debug playback run reaches stop/EOS/error cleanup:

```sh
adb shell run-as com.einhander.temposcore ls files/diagnostics
adb exec-out run-as com.einhander.temposcore cat files/diagnostics/playback-<token>-epoch-<epoch>.csv > playback.csv
```

Replace `<token>` and `<epoch>` with filename values from `ls`. No device is
available for runtime verification in this diagnostic implementation lane; file
creation and `run-as` retrieval remain device checks and are **NOT RUN** here.
