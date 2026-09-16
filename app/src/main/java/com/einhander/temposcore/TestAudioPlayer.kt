package com.einhander.temposcore

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.nio.ByteOrder
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
    private val pauseLock = Object()
    @Volatile private var sourceUri: Uri? = null
    @Volatile private var stopRequested = false
    @Volatile private var paused = false
    @Volatile private var playbackThread: Thread? = null
    @Volatile private var audioTrack: AudioTrack? = null
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

    private fun pause() {
        if (state != State.Playing) return
        paused = true
        try { audioTrack?.pause() } catch (_: IllegalStateException) { }
        publishState(State.Paused)
    }

    private fun resume() {
        if (state != State.Paused) return
        paused = false
        try { audioTrack?.play() } catch (_: IllegalStateException) { }
        synchronized(pauseLock) { pauseLock.notifyAll() }
        publishState(State.Playing)
    }

    private fun startSession(startUs: Long) {
        val uri = sourceUri ?: return
        if (playbackThread?.isAlive == true) return
        stopRequested = false
        paused = false
        val thread = Thread({ decodeAndPlay(uri, startUs) }, "scoretracker-test-audio")
        playbackThread = thread
        publishState(State.Playing)
        thread.start()
    }

    private fun stopSession(keepReady: Boolean) {
        val thread = playbackThread
        if (thread != null && thread.isAlive) {
            stopRequested = true
            paused = false
            synchronized(pauseLock) { pauseLock.notifyAll() }
            try { audioTrack?.pause() } catch (_: IllegalStateException) { }
            try { audioTrack?.flush() } catch (_: IllegalStateException) { }
            thread.interrupt()
            try { thread.join(1500L) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        playbackThread = null
        audioTrack = null
        stopRequested = false
        paused = false
        if (keepReady && sourceUri != null) publishState(State.Ready)
    }

    private fun decodeAndPlay(uri: Uri, startUs: Long) {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        var track: AudioTrack? = null
        var nativeStarted = false
        try {
            val ex = MediaExtractor()
            extractor = ex
            ex.setDataSource(context, uri, null)
            val trackIndex = findAudioTrack(ex)
            if (trackIndex < 0) error("No audio track in selected file")
            ex.selectTrack(trackIndex)
            val inputFormat = ex.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: error("Audio MIME is missing")
            durationMs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                inputFormat.getLong(MediaFormat.KEY_DURATION) / 1000L
            } else durationMs
            if (startUs > 0L) ex.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

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

            while (!outputEos && !stopRequested) {
                waitWhilePaused()
                if (stopRequested) break

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

                when (val outputIndex = decoder.dequeueOutputBuffer(info, 10_000L)) {
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
                        val newTrack = createAudioTrack(outputSampleRate, outputChannels, outputEncoding)
                        track = newTrack
                        audioTrack = newTrack
                        if (!NativeAudioBridge.startTest(outputSampleRate)) {
                            error("Could not start native test analyzer")
                        }
                        nativeStarted = true
                        // A seek/restart must prove its absolute position again. Do not
                        // seed the matcher with the MP3 timestamp (that would hide bugs).
                        NativeAudioBridge.resetPosition(0.0)
                        NativeAudioBridge.requestGlobalReacquire()
                        newTrack.play()
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
                            waitWhilePaused()
                            if (!stopRequested) {
                                // Blocking write is the clock for the test source: decoding is
                                // prevented from running through the entire MP3 at CPU speed.
                                writeAll(outputTrack, pcm)
                                NativeAudioBridge.pushTestAudio(
                                    downmixToMono(pcm, outputChannels, outputEncoding),
                                )
                                positionMs = info.presentationTimeUs / 1000L
                                publishProgress()
                            }
                        }
                        outputEos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        } catch (t: Throwable) {
            if (!stopRequested) {
                mainHandler.post { listener.onTestAudioError(t.message ?: t.javaClass.simpleName) }
            }
        } finally {
            if (nativeStarted) NativeAudioBridge.stopTest()
            try { track?.pause() } catch (_: Throwable) { }
            try { track?.flush() } catch (_: Throwable) { }
            try { track?.release() } catch (_: Throwable) { }
            audioTrack = null
            try { codec?.stop() } catch (_: Throwable) { }
            try { codec?.release() } catch (_: Throwable) { }
            try { extractor?.release() } catch (_: Throwable) { }
            if (playbackThread === Thread.currentThread()) playbackThread = null
            if (!stopRequested) {
                nextStartUs = 0L
                publishState(if (sourceUri != null) State.Ready else State.Empty)
            }
        }
    }

    private fun waitWhilePaused() {
        synchronized(pauseLock) {
            while (paused && !stopRequested) {
                try { pauseLock.wait(250L) } catch (_: InterruptedException) {
                    if (stopRequested) return
                }
            }
        }
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

    private fun writeAll(track: AudioTrack, pcm: ByteArray) {
        var offset = 0
        while (offset < pcm.size && !stopRequested) {
            waitWhilePaused()
            val written = track.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING)
            if (written < 0) error("AudioTrack write failed: $written")
            if (written == 0) continue
            offset += written
        }
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
}
