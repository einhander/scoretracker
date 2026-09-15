package com.einhander.temposcore

import android.Manifest
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Choreographer
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.einhander.temposcore.databinding.ActivityMainBinding
import com.einhander.temposcore.midi.MidiFileParser
import com.einhander.temposcore.midi.MidiNote
import com.einhander.temposcore.midi.MidiScore
import com.einhander.temposcore.score.ScoreNavigator
import com.einhander.temposcore.score.TrackSelection
import com.einhander.temposcore.score.visibleNotes
import com.einhander.temposcore.transport.PositionTrackingState
import com.einhander.temposcore.transport.TransportSnapshot
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity(), Choreographer.FrameCallback {
    private lateinit var binding: ActivityMainBinding
    private var score: MidiScore? = null
    private var trackSelection: TrackSelection = TrackSelection.All
    private var visibleNotes: List<MidiNote> = emptyList()
    private var spinnerPopulating = false
    private var expectedBpm = 120.0
    private var nativeInitialized = false
    private var frameLoopActive = false

    private val openMidi = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadMidi(uri)
    }

    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening() else Toast.makeText(
            this,
            getString(R.string.permission_required),
            Toast.LENGTH_LONG,
        ).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.loadMidiButton.setOnClickListener {
            openMidi.launch(arrayOf("audio/midi", "audio/x-midi", "application/octet-stream"))
        }

        binding.listenButton.setOnClickListener {
            val running = if (nativeInitialized) NativeAudioBridge.state().running else false
            if (running) stopListening() else ensureMicAndStart()
        }

        binding.resetButton.setOnClickListener {
            if (!nativeInitialized) return@setOnClickListener
            NativeAudioBridge.resetPosition(0.0)
            // Reset means a fresh acquisition (spec §30): the analyzer thread
            // applies the request on its next update — no cross-thread state write.
            NativeAudioBridge.requestGlobalReacquire()
        }

        binding.trackSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit

            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (spinnerPopulating) return
                trackSelection = if (pos == 0) TrackSelection.All else TrackSelection.Track(pos - 1)
                visibleNotes = score?.visibleNotes(trackSelection) ?: emptyList()
                binding.scoreView.trackSelection = trackSelection
                updateUiFromTransport()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!frameLoopActive) {
            frameLoopActive = true
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onPause() {
        frameLoopActive = false
        Choreographer.getInstance().removeFrameCallback(this)
        super.onPause()
    }

    override fun onDestroy() {
        if (nativeInitialized) NativeAudioBridge.stop()
        super.onDestroy()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!frameLoopActive) return
        updateUiFromTransport()
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun loadMidi(uri: Uri) {
        binding.loadMidiButton.isEnabled = false
        Thread {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Could not read selected file")
                val parsed = MidiFileParser.parse(bytes)
                val name = displayName(uri) ?: "MIDI"
                runOnUiThread {
                    if (nativeInitialized && NativeAudioBridge.state().running) {
                        NativeAudioBridge.stop()
                    }
                    score = parsed
                    trackSelection = TrackSelection.All
                    visibleNotes = parsed.notes
                    binding.scoreView.trackSelection = trackSelection
                    populateTrackSelector(parsed)
                    expectedBpm = parsed.initialBpm
                    binding.scoreView.score = parsed
                    binding.fileNameText.text = "$name  •  ${parsed.notes.size} notes"
                    binding.listenButton.isEnabled = true
                    binding.resetButton.isEnabled = true
                    configureNativeEngine()
                    sendScoreReferenceToNative(parsed)
                    updateUiFromTransport()
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "MIDI error: ${t.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                runOnUiThread { binding.loadMidiButton.isEnabled = true }
            }
        }.start()
    }

    private fun populateTrackSelector(score: MidiScore) {
        if (score.tracks.size > 1) {
            val items = listOf(getString(R.string.all_tracks)) + score.tracks.map { ScoreNavigator.trackLabel(it) }
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            // Guard must be set before the adapter assignment: setAdapter can
            // fire onItemSelected synchronously (selection clamp) when the old
            // selection index is out of range of the new adapter.
            spinnerPopulating = true
            binding.trackSpinner.adapter = adapter
            binding.trackSpinner.setSelection(0)
            spinnerPopulating = false
            binding.trackSelectorRow.visibility = View.VISIBLE
        } else {
            binding.trackSpinner.adapter = null
            binding.trackSelectorRow.visibility = View.GONE
        }
    }

    private fun configureNativeEngine() {
        if (!nativeInitialized) {
            NativeAudioBridge.initialize(expectedBpm, 0.0)
            nativeInitialized = true
        } else {
            NativeAudioBridge.setExpectedBpm(expectedBpm)
            NativeAudioBridge.resetPosition(0.0)
        }
    }

    /** Push the parsed MIDI to the native score reference (all tracks, spec §17). */
    private fun sendScoreReferenceToNative(parsed: MidiScore) {
        val notes = parsed.notes
        NativeAudioBridge.setScoreReference(
            ppq = parsed.ppq,
            totalTicks = parsed.totalTicks,
            noteChannels = notes.map { it.channel }.toIntArray(),
            notePitches = notes.map { it.pitch }.toIntArray(),
            noteVelocities = notes.map { it.velocity }.toIntArray(),
            noteStarts = notes.map { it.startTick }.toLongArray(),
            noteEnds = notes.map { it.endTick }.toLongArray(),
            tempoTicks = parsed.tempoMap.map { it.tick }.toLongArray(),
            tempoValues = parsed.tempoMap.map { it.microsecondsPerQuarter }.toIntArray(),
        )
    }

    private fun ensureMicAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startListening() {
        if (!nativeInitialized) return
        binding.listenButton.isEnabled = false
        Thread {
            val ok = NativeAudioBridge.start()
            runOnUiThread {
                binding.listenButton.isEnabled = true
                binding.listenButton.text = if (ok) getString(R.string.stop_listening) else getString(R.string.start_listening)
                if (!ok) Toast.makeText(this, "Could not start Oboe microphone input", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun stopListening() {
        NativeAudioBridge.stop()
        binding.listenButton.text = getString(R.string.start_listening)
    }

    private fun updateUiFromTransport() {
        val localScore = score ?: return
        val state = if (nativeInitialized) NativeAudioBridge.state() else return
        val pos = state.quarterBeatPosition.coerceAtLeast(0.0)
        val barBeat = ScoreNavigator.barBeatAt(localScore, pos)
        val now = ScoreNavigator.soundingNotes(localScore, visibleNotes, pos)
        val next = ScoreNavigator.nextNotes(localScore, visibleNotes, pos)

        // Tempo map (spec §26): update the native expected BPM only when the
        // transport crosses a tempo region — not on every frame.
        val regionBpm = ScoreNavigator.tempoAtQuarterBeat(localScore, pos)
        if (abs(regionBpm - expectedBpm) > 0.5) {
            expectedBpm = regionBpm
            if (nativeInitialized) NativeAudioBridge.setExpectedBpm(regionBpm)
        }

        binding.scoreView.quarterBeatPosition = pos
        binding.tempoText.text = String.format(
            Locale.US,
            "MIDI %.1f  |  LIVE %s  |  transport %.1f BPM",
            expectedBpm,
            if (state.detectedBpm > 1.0) String.format(Locale.US, "%.1f BPM", state.detectedBpm) else "--.- BPM",
            state.transportBpm,
        )
        binding.positionText.text = String.format(
            Locale.US,
            "Bar %d  Beat %.2f  (%d/%d)",
            barBeat.bar,
            barBeat.beat,
            barBeat.numerator,
            barBeat.denominator,
        )
        binding.nowText.text = "Now: ${ScoreNavigator.noteList(now)}"
        binding.nextText.text = "Next: ${ScoreNavigator.noteList(next)}"
        // Beat confidence (fast loop) — kept separate from position confidence (spec §31).
        binding.confidenceText.text = String.format(Locale.US, "Beat: %.0f%%", state.beatConfidence * 100.0)
        binding.positionStatusText.text = formatPositionStatus(state)
        binding.listenButton.text = if (state.running) getString(R.string.stop_listening) else getString(R.string.start_listening)
    }

    private fun formatPositionStatus(state: TransportSnapshot): String = when (state.positionState) {
        PositionTrackingState.Idle -> "Position: idle"
        PositionTrackingState.Acquiring ->
            "Position: locating ${String.format(Locale.US, "%.1f", state.validContextSeconds)}/20 s"
        PositionTrackingState.Locked ->
            "Position: LOCKED ${String.format(Locale.US, "%.0f", state.positionConfidence * 100.0)}% | " +
                "error ${String.format(Locale.US, "%+.2f", state.positionErrorBeats)} beat"
        PositionTrackingState.Weak -> "Position: weak — reacquiring"
        PositionTrackingState.Reacquiring -> "Position: reacquiring"
    }

    private fun displayName(uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) cursor.getString(0) else null
        } finally {
            cursor?.close()
        }
    }
}
