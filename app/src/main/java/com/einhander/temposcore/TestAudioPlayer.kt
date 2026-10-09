package com.einhander.temposcore

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * Deterministic score-following test source.
 *
 * The selected MP3 is decoded once by MediaCodec. The decoded PCM is both:
 *  1) written to AudioTrack so the tester hears it, and
 *  2) downmixed to mono and injected directly into the native analyzer.
 *
 * This intentionally bypasses the microphone, room acoustics, AGC and device
 * input latency. AudioTrack's blocking writes provide the real-time pacing.
 */
class TestAudioPlayer(
    private val context: Context,
    private val listener: Listener,
) {
    private class PlaybackRun(
        val uri: Uri,
        val startUs: Long,
        val sessionToken: Long,
        val diagnostics: PlaybackDiagnostics.Session?,
    ) {
        val pauseLock = Object()
        @Volatile var stopRequested = false
        @Volatile var paused = false
        @Volatile var thread: Thread? = null
        @Volatile var audioTrack: AudioTrack? = null
    }

    interface Listener {
        fun onTestAudioStateChanged(state: State)
        fun onTestAudioProgress(positionMs: Long, durationMs: Long)
        fun onTestAudioError(message: String)
    }

    enum class State { Empty, Ready, Playing, Paused }

    @Volatile var state: State = State.Empty
        private set
    @Volatile var durationMs: Long = 0L
        private set
    @Volatile var positionMs: Long = 0L
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private val playbackLock = Any()
    private val diagnosticEpoch = AtomicLong(0L)
    private val retiredRuns = Collections.newSetFromMap(java.util.IdentityHashMap<PlaybackRun, Boolean>())
    @Volatile private var sourceUri: Uri? = null
    @Volatile private var activeRun: PlaybackRun? = null
    @Volatile private var latestPlaybackToken = 0L
    @Volatile private var nextStartUs = 0L

    fun load(uri: Uri) {
        stopSession(keepReady = false)
        sourceUri = uri
        nextStartUs = 0L
        positionMs = 0L
        durationMs = probeDuration(uri) / 1000L
        publishState(State.Ready)
        publishProgress()
    }

    fun togglePlayPause() {
        when (state) {
            State.Empty -> Unit
            State.Ready -> startSession(nextStartUs)
            State.Playing -> pause()
            State.Paused -> resume()
        }
    }

    fun restart() {
        if (sourceUri == null) return
        stopSession(keepReady = true)
        nextStartUs = 0L
        positionMs = 0L
        publishProgress()
        startSession(0L)
    }

    /** Seek is deliberately a fresh global-acquisition test, not a hidden score hint. */
    fun seekTo(positionMs: Long) {
        if (sourceUri == null) return
        val targetMs = positionMs.coerceIn(0L, max(0L, durationMs))
        val wasPlaying = state == State.Playing
        stopSession(keepReady = true)
        nextStartUs = targetMs * 1000L
        this.positionMs = targetMs
        publishProgress()
        if (wasPlaying) startSession(nextStartUs)
    }

    fun stop() {
        stopSession(keepReady = sourceUri != null)
        nextStartUs = 0L
        positionMs = 0L
        publishProgress()
    }

    fun release() {
        stopSession(keepReady = false)
        sourceUri = null
        publishState(State.Empty)
    }

    /** Debug CSV payload; caller controls export and persistence. */
    fun diagnosticCsv(): String? = if (BuildConfig.DEBUG) {
        PlaybackDiagnostics.currentSnapshot()?.let(PlaybackDiagnostics::csv)
    } else null

    private fun pause() {
        synchronized(playbackLock) {
            if (state != State.Playing) return
            val current = activeRun ?: return
            current.paused = true
            if (BuildConfig.DEBUG) current.diagnostics?.let { PlaybackDiagnostics.paused(it, true) }
            try { current.audioTrack?.pause() } catch (_: IllegalStateException) { }
        }
        publishState(State.Paused)
    }

    private fun resume() {
        val run = synchronized(playbackLock) {
            if (state != State.Paused) return
            val current = activeRun ?: return
            current.paused = false
            if (BuildConfig.DEBUG) current.diagnostics?.let { PlaybackDiagnostics.paused(it, false) }
            try { current.audioTrack?.play() } catch (_: IllegalStateException) { }
            current
        }
        synchronized(run.pauseLock) { run.pauseLock.notifyAll() }
        publishState(State.Playing)
    }

    private fun startSession(startUs: Long) {
        val uri = sourceUri ?: return
        val run = synchronized(playbackLock) {
            if (activeRun?.thread?.isAlive == true) return
            val token = NativeAudioBridge.newTestSessionToken()
            if (token == 0L) return
            val diagnostics = if (BuildConfig.DEBUG) PlaybackDiagnostics.start(token, diagnosticEpoch.incrementAndGet()) else null
            PlaybackRun(uri, startUs, token, diagnostics).also { session ->
                session.thread = Thread({ decodeAndPlay(session) }, "scoretracker-test-audio-$token")
                activeRun = session
                latestPlaybackToken = token
            }
        }
        publishState(State.Playing)
        run.thread?.start()
    }

    private fun stopSession(keepReady: Boolean) {
        val run = synchronized(playbackLock) {
            activeRun.also {
                activeRun = null
                latestPlaybackToken = 0L
            }
        }
        if (run != null) {
            run.stopRequested = true
            run.paused = false
            synchronized(run.pauseLock) { run.pauseLock.notifyAll() }
            try { run.audioTrack?.pause() } catch (_: IllegalStateException) { }
            try { run.audioTrack?.flush() } catch (_: IllegalStateException) { }
            // Revoke admission immediately; token-scoped worker cleanup may finish later.
            NativeAudioBridge.revokeTestSession(run.sessionToken)
            val thread = run.thread
            if (thread != null && thread !== Thread.currentThread() && thread.isAlive) {
                thread.interrupt()
                try { thread.join(1500L) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            }
            synchronized(playbackLock) {
                if (thread?.isAlive == true) retiredRuns.add(run) else retiredRuns.remove(run)
            }
        }
        if (keepReady && sourceUri != null) publishState(State.Ready)
    }

    private fun decodeAndPlay(run: PlaybackRun) {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        var track: AudioTrack? = null
        try {
            val ex = MediaExtractor()
            extractor = ex
            ex.setDataSource(context, run.uri, null)
            val trackIndex = findAudioTrack(ex)
            if (trackIndex < 0) error("No audio track in selected file")
            ex.selectTrack(trackIndex)
            val inputFormat = ex.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: error("Audio MIME is missing")
            synchronized(playbackLock) {
                if (activeRun === run && !run.stopRequested && inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                    durationMs = inputFormat.getLong(MediaFormat.KEY_DURATION) / 1000L
                }
            }
            if (run.startUs > 0L) ex.seekTo(run.startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            val info = MediaCodec.BufferInfo()
            var inputEos = false
            var outputEos = false
            var outputChannels = 0
            var outputSampleRate = 0
            var outputEncoding = AudioFormat.ENCODING_PCM_16BIT

            while (!outputEos && !run.stopRequested) {
                waitWhilePaused(run)
                if (run.stopRequested) break

                if (!inputEos) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000L)
                    if (inputIndex >= 0) {
                        val input = decoder.getInputBuffer(inputIndex) ?: error("Decoder input buffer unavailable")
                        val size = ex.readSampleData(input, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }

                val outputIndex = decoder.dequeueOutputBuffer(info, 10_000L)
                if (run.stopRequested) {
                    if (outputIndex >= 0) decoder.releaseOutputBuffer(outputIndex, false)
                    break
                }
                when (outputIndex) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = decoder.outputFormat
                        outputChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        outputSampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        outputEncoding = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else AudioFormat.ENCODING_PCM_16BIT
                        if (outputEncoding != AudioFormat.ENCODING_PCM_16BIT) {
                            error("Test mode expects 16-bit PCM from the MP3 decoder (got $outputEncoding)")
                        }
                        if (BuildConfig.DEBUG) run.diagnostics?.let {
                            PlaybackDiagnostics.setSampleRate(it, outputSampleRate)
                        }
                        val newTrack = createAudioTrack(outputSampleRate, outputChannels, outputEncoding)
                        track = newTrack
                        run.audioTrack = newTrack
                        val startedSession = NativeAudioBridge.startTest(outputSampleRate, run.sessionToken)
                        if (run.stopRequested) {
                            if (startedSession == run.sessionToken) NativeAudioBridge.stopTest(run.sessionToken)
                            break
                        }
                        if (startedSession != run.sessionToken) {
                            error("Could not start native test analyzer")
                        }
                        if (!playTrackForRun(run, newTrack)) {
                            NativeAudioBridge.stopTest(run.sessionToken)
                            break
                        }
                    }
                    else -> if (outputIndex >= 0) {
                        if (info.size > 0) {
                            val out = decoder.getOutputBuffer(outputIndex) ?: error("Decoder output buffer unavailable")
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            val pcm = ByteArray(info.size)
                            out.get(pcm)
                            val outputTrack = track ?: error("Decoder produced PCM before output format")
                            if (outputChannels <= 0 || outputSampleRate <= 0) {
                                error("Invalid decoder output format")
                            }
                            if (BuildConfig.DEBUG) run.diagnostics?.let {
                                PlaybackDiagnostics.decoded(it, (info.size / (outputChannels * 2)).toLong(), info.presentationTimeUs)
                            }
                            waitWhilePaused(run)
                            if (!run.stopRequested) {
                                // Blocking write is the clock for the test source: decoding is
                                // prevented from running through the entire MP3 at CPU speed.
                                writeAll(run, outputTrack, pcm)
                                if (!run.stopRequested) {
                                    val mono = downmixToMono(pcm, outputChannels, outputEncoding)
                                    NativeAudioBridge.pushTestAudio(run.sessionToken, mono)
                                    if (BuildConfig.DEBUG) run.diagnostics?.let { PlaybackDiagnostics.pushed(it, mono.size.toLong()) }
                                    if (BuildConfig.DEBUG) pollPlaybackClock(run, outputTrack)
                                }
                                if (!run.stopRequested) {
                                    publishProgressForRun(run, info.presentationTimeUs / 1000L)
                                }
                            }
                        }
                        outputEos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        if (BuildConfig.DEBUG && outputEos) run.diagnostics?.let(PlaybackDiagnostics::eos)
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        } catch (t: Throwable) {
            if (BuildConfig.DEBUG) run.diagnostics?.let(PlaybackDiagnostics::error)
            if (!run.stopRequested && activeRun === run) {
                mainHandler.post {
                    if (activeRun === run) listener.onTestAudioError(t.message ?: t.javaClass.simpleName)
                }
            }
        } finally {
            NativeAudioBridge.stopTest(run.sessionToken)
            try { track?.pause() } catch (_: Throwable) { }
            try { track?.flush() } catch (_: Throwable) { }
            try { track?.release() } catch (_: Throwable) { }
            run.audioTrack = null
            try { codec?.stop() } catch (_: Throwable) { }
            try { codec?.release() } catch (_: Throwable) { }
            try { extractor?.release() } catch (_: Throwable) { }
            if (BuildConfig.DEBUG) run.diagnostics?.let {
                try {
                    PlaybackDiagnostics.export(java.io.File(context.filesDir, "diagnostics"), PlaybackDiagnostics.snapshot(it))
                } catch (_: Throwable) {
                    // Diagnostics export must not disrupt playback cleanup.
                }
            }
            val completedCurrentRun = synchronized(playbackLock) {
                retiredRuns.remove(run)
                if (activeRun === run) {
                    activeRun = null
                    true
                } else false
            }
            if (!run.stopRequested && completedCurrentRun) {
                synchronized(playbackLock) {
                    if (latestPlaybackToken == run.sessionToken && activeRun == null) nextStartUs = 0L
                }
                publishCompletedRunState(run)
            }
        }
    }

    private fun waitWhilePaused(run: PlaybackRun) {
        synchronized(run.pauseLock) {
            while (run.paused && !run.stopRequested) {
                try { run.pauseLock.wait(250L) } catch (_: InterruptedException) {
                    if (run.stopRequested) return
                }
            }
        }
    }

    /** Serialize play-start with stopSession's active-run invalidation. */
    private fun playTrackForRun(run: PlaybackRun, track: AudioTrack): Boolean =
        synchronized(playbackLock) {
            if (activeRun !== run || run.stopRequested) return@synchronized false
            try { track.play() } catch (_: IllegalStateException) { return@synchronized false }
            true
        }

    private fun createAudioTrack(sampleRate: Int, channels: Int, encoding: Int): AudioTrack {
        val channelMask = when (channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> error("Only mono/stereo test audio is supported (got $channels channels)")
        }
        val bytesPerSample = 2
        val minimum = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
        val bufferBytes = max(minimum, sampleRate * channels * bytesPerSample / 10)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun writeAll(run: PlaybackRun, track: AudioTrack, pcm: ByteArray) {
        var offset = 0
        while (offset < pcm.size && !run.stopRequested) {
            waitWhilePaused(run)
            if (run.stopRequested) return
            val written = track.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING)
            if (written < 0) error("AudioTrack write failed: $written")
            if (written == 0) continue
            if (BuildConfig.DEBUG) run.diagnostics?.let {
                PlaybackDiagnostics.written(it, track.channelCount, 2, written)
            }
            offset += written
        }
    }

    private fun pollPlaybackClock(run: PlaybackRun, track: AudioTrack) {
        val stamp = AudioTimestamp()
        try {
            if (track.getTimestamp(stamp)) {
                run.diagnostics?.let { PlaybackDiagnostics.timestamp(it, stamp.framePosition, stamp.nanoTime) }
                return
            }
        } catch (_: IllegalStateException) {
            // Track stopping; playback head remains best available estimate.
        }
        run.diagnostics?.let { PlaybackDiagnostics.fallbackHead(it, track.playbackHeadPosition) }
    }

    private fun downmixToMono(pcm: ByteArray, channels: Int, encoding: Int): FloatArray {
        if (encoding != AudioFormat.ENCODING_PCM_16BIT) return FloatArray(0)
        val shorts = pcm.size / 2
        val frames = shorts / channels
        val sb = java.nio.ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(frames) { frame ->
            var sum = 0f
            repeat(channels) { sum += sb.get(frame * channels + it) / 32768f }
            sum / channels
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) return i
        }
        return -1
    }

    private fun probeDuration(uri: Uri): Long {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            val i = findAudioTrack(extractor)
            if (i < 0) 0L else {
                val f = extractor.getTrackFormat(i)
                if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L
            }
        } catch (_: Throwable) {
            0L
        } finally {
            extractor.release()
        }
    }

    private fun publishState(newState: State) {
        state = newState
        mainHandler.post { listener.onTestAudioStateChanged(newState) }
    }

    private fun publishProgress() {
        val p = positionMs
        val d = durationMs
        mainHandler.post { listener.onTestAudioProgress(p, d) }
    }

    private fun publishProgressForRun(run: PlaybackRun, newPositionMs: Long) {
        val accepted = synchronized(playbackLock) {
            if (activeRun !== run || run.stopRequested) false
            else { positionMs = newPositionMs; true }
        }
        if (!accepted) return
        val p = newPositionMs
        val d = durationMs
        mainHandler.post { if (activeRun === run) listener.onTestAudioProgress(p, d) }
    }

    private fun publishCompletedRunState(run: PlaybackRun) {
        mainHandler.post {
            if (latestPlaybackToken == run.sessionToken && activeRun == null) {
                val finalState = if (sourceUri != null) State.Ready else State.Empty
                state = finalState
                listener.onTestAudioStateChanged(finalState)
            }
        }
    }
}
