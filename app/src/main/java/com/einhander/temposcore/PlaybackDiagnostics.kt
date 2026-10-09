package com.einhander.temposcore

import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/** Debug-only MP3 bookkeeping. Never supplies score position or transport tempo. */
internal object PlaybackDiagnostics {
    private const val MAX_DISPLAY_ROWS = 1024
    private const val MAX_CSV_BYTES = 256 * 1024
    private const val MAX_EXPORT_FILES = 20
    private const val MAX_FORMAT_EPOCHS = 8
    private val ownerLock = Any()
    private val exportLock = Any()
    private val active = AtomicReference<Session?>(null)

    data class Snapshot(
        val token: Long,
        val epoch: Long,
        val sampleRate: Int,
        val formatEpoch: Int,
        val decodedFrames: Long,
        val writtenFrames: Long,
        val pushSubmittedFrames: Long,
        val decodedPtsUs: Long,
        val headFrames: Long,
        val headSource: String,
        val timestampFrame: Long?,
        val timestampMonotonicNs: Long?,
        val paused: Boolean,
        val eos: Boolean,
        val errorCount: Long,
        val displayRows: List<String>,
        val formats: List<FormatMark>,
    )

    data class FormatMark(val epoch: Int, val sampleRate: Int, val decodedFramesAtChange: Long)

    internal data class Counters(
        val sampleRate: Int = 0,
        val formatEpoch: Int = 0,
        val writtenFrameBytes: Long = 0,
        val writtenRemainderBytes: Long = 0,
        val decodedFrames: Long = 0,
        val writtenFrames: Long = 0,
        val pushSubmittedFrames: Long = 0,
        val decodedPtsUs: Long = -1,
        val headFrames: Long = 0,
        val headSource: String = "unsupported",
        val timestampFrame: Long? = null,
        val timestampMonotonicNs: Long? = null,
        val paused: Boolean = false,
        val eos: Boolean = false,
        val errorCount: Long = 0,
    )

    class Session internal constructor(val token: Long, val epoch: Long) {
        internal var counters = Counters()
        internal val displayRows = ArrayDeque<String>()
        internal val formats = ArrayList<FormatMark>()
    }

    fun start(token: Long, epoch: Long): Session = synchronized(ownerLock) {
        Session(token, epoch).also { active.set(it) }
    }

    fun currentSession(): Session? = active.get()
    fun currentSnapshot(): Snapshot? = synchronized(ownerLock) { active.get()?.toSnapshot() }
    fun snapshot(session: Session): Snapshot = synchronized(ownerLock) { session.toSnapshot() }

    fun setSampleRate(session: Session, sampleRate: Int) = synchronized(ownerLock) {
        if (active.get() !== session || sampleRate <= 0) return@synchronized
        val previous = session.counters.sampleRate
        if (previous == sampleRate) return@synchronized
        val nextFormatEpoch = if (previous == 0) 1 else session.counters.formatEpoch + 1
        session.counters = session.counters.copy(sampleRate = sampleRate, formatEpoch = nextFormatEpoch)
        if (session.formats.size < MAX_FORMAT_EPOCHS) {
            session.formats.add(FormatMark(nextFormatEpoch, sampleRate, session.counters.decodedFrames))
        }
    }

    fun decoded(session: Session, frames: Long, ptsUs: Long) = update(session) {
        it.copy(decodedFrames = it.decodedFrames + frames.coerceAtLeast(0), decodedPtsUs = ptsUs)
    }
    fun written(session: Session, channels: Int, bytesPerSample: Int, result: Int) = update(session) {
        val frameBytes = channels.toLong() * bytesPerSample
        val changedFormat = frameBytes > 0 && it.writtenFrameBytes != 0L && it.writtenFrameBytes != frameBytes
        val carry = if (changedFormat) 0L else it.writtenRemainderBytes
        val totalBytes = carry + if (result > 0 && frameBytes > 0) result.toLong() else 0L
        val frames = if (frameBytes > 0) totalBytes / frameBytes else 0L
        it.copy(
            writtenFrames = it.writtenFrames + frames,
            writtenFrameBytes = if (frameBytes > 0) frameBytes else it.writtenFrameBytes,
            writtenRemainderBytes = if (frameBytes > 0) totalBytes % frameBytes else 0L,
            errorCount = it.errorCount + if (result < 0 || (changedFormat && it.writtenRemainderBytes > 0)) 1 else 0,
        )
    }
    fun pushed(session: Session, frames: Long) = update(session) {
        it.copy(pushSubmittedFrames = it.pushSubmittedFrames + frames.coerceAtLeast(0))
    }
    fun paused(session: Session, value: Boolean) = update(session) { it.copy(paused = value) }
    fun eos(session: Session) = update(session) { it.copy(eos = true) }
    fun error(session: Session) = update(session) { it.copy(errorCount = it.errorCount + 1) }
    fun timestamp(session: Session, frame: Long, monotonicNs: Long) = update(session) {
        it.copy(timestampFrame = frame, timestampMonotonicNs = monotonicNs, headFrames = frame, headSource = "timestamp")
    }
    fun fallbackHead(session: Session, rawPosition: Int) = update(session) {
        val unsigned = rawPosition.toLong() and 0xffff_ffffL
        val previousRaw = it.headFrames and 0xffff_ffffL
        val wrapBase = it.headFrames - previousRaw
        val candidate = wrapBase + unsigned
        val unwrapped = when {
            unsigned < previousRaw && previousRaw - unsigned > 0x8000_0000L -> candidate + 0x1_0000_0000L
            unsigned > previousRaw && unsigned - previousRaw > 0x8000_0000L && wrapBase >= 0x1_0000_0000L -> candidate - 0x1_0000_0000L
            else -> candidate
        }
        if (unwrapped < it.headFrames) it.copy(errorCount = it.errorCount + 1)
        else it.copy(headFrames = unwrapped, headSource = "playback_head_fallback", timestampFrame = null, timestampMonotonicNs = null)
    }

    /** Token check and row append share ownerLock with start(), preventing stale-run rows. */
    fun recordDisplay(session: Session, monotonicNs: Long, nativeBeat: Double, displayedBeat: Double) = synchronized(ownerLock) {
        if (active.get() !== session) return@synchronized
        if (session.displayRows.size == MAX_DISPLAY_ROWS) session.displayRows.removeFirst()
        session.displayRows.addLast(String.format(Locale.US, "%d,%d,%.9f,%.9f", session.token, monotonicNs, nativeBeat, displayedBeat))
    }

    fun csv(snapshot: Snapshot): String = buildString {
        appendLine("# heard_frame_estimate is AudioTrack timestamp/head estimate, not guaranteed physical sound; decoder PTS is separate")
        appendLine("token,epoch,sample_rate_hz,format_epoch,decoded_frames,written_frames,push_submitted_frames,decoder_pts_us,heard_frame_estimate,head_source,timestamp_monotonic_ns,paused,eos,error_count")
        append(snapshot.token).append(',').append(snapshot.epoch).append(',').append(snapshot.sampleRate).append(',')
            .append(snapshot.formatEpoch).append(',').append(snapshot.decodedFrames).append(',').append(snapshot.writtenFrames).append(',')
            .append(snapshot.pushSubmittedFrames).append(',').append(snapshot.decodedPtsUs).append(',').append(snapshot.headFrames).append(',')
            .append(snapshot.headSource).append(',').append(snapshot.timestampMonotonicNs ?: "").append(',')
            .append(snapshot.paused).append(',').append(snapshot.eos).append(',').append(snapshot.errorCount)
        appendLine().appendLine().appendLine("format_epoch,sample_rate_hz,decoded_frames_at_change")
        snapshot.formats.forEach { append(it.epoch).append(',').append(it.sampleRate).append(',').appendLine(it.decodedFramesAtChange) }
        appendLine().appendLine("display_token,monotonic_ns,native_transport_beat,displayed_beat")
        snapshot.displayRows.forEach { appendLine(it) }
    }

    /** Atomically writes one bounded session file; retains newest MAX_EXPORT_FILES files only. */
    fun export(directory: File, snapshot: Snapshot): File = synchronized(exportLock) {
        directory.mkdirs()
        val name = "playback-${snapshot.token}-epoch-${snapshot.epoch}.csv"
        val destination = File(directory, name)
        val temporary = File(directory, "$name.tmp")
        var boundedSnapshot = snapshot
        var csvText = csv(boundedSnapshot)
        while (csvText.toByteArray(Charsets.UTF_8).size > MAX_CSV_BYTES && boundedSnapshot.displayRows.isNotEmpty()) {
            boundedSnapshot = boundedSnapshot.copy(displayRows = boundedSnapshot.displayRows.drop(64))
            csvText = csv(boundedSnapshot)
        }
        temporary.outputStream().use { it.write(csvText.toByteArray(Charsets.UTF_8)) }
        if (!temporary.renameTo(destination)) {
            temporary.delete()
            throw IllegalStateException("Could not finalize playback diagnostics CSV")
        }
        directory.listFiles { file -> file.isFile && file.name.startsWith("playback-") && file.name.endsWith(".csv") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_EXPORT_FILES)
            ?.forEach { it.delete() }
        directory.listFiles { file -> file.isFile && file.name.startsWith("playback-") && file.name.endsWith(".tmp") }
            ?.forEach { it.delete() }
        destination
    }

    private inline fun update(session: Session, transform: (Counters) -> Counters) = synchronized(ownerLock) {
        if (active.get() !== session) return@synchronized
        session.counters = transform(session.counters)
    }

    private fun Session.toSnapshot() = Snapshot(
        token, epoch, counters.sampleRate, counters.formatEpoch, counters.decodedFrames, counters.writtenFrames,
        counters.pushSubmittedFrames, counters.decodedPtsUs, counters.headFrames, counters.headSource,
        counters.timestampFrame, counters.timestampMonotonicNs, counters.paused, counters.eos, counters.errorCount,
        displayRows.toList(), formats.toList(),
    )
}
