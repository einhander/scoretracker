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
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.einhander.temposcore.databinding.ActivityMainBinding
import com.einhander.temposcore.midi.MidiFileParser
import com.einhander.temposcore.midi.MidiNote
import com.einhander.temposcore.midi.MidiScore
import com.einhander.temposcore.score.NoteNaming
import com.einhander.temposcore.score.ScoreNavigator
import com.einhander.temposcore.score.TrackSelection
import com.einhander.temposcore.score.visibleNotes
import com.einhander.temposcore.transport.PositionTrackingState
import com.einhander.temposcore.transport.TransportSnapshot
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity(), Choreographer.FrameCallback, TestAudioPlayer.Listener {
    private lateinit var binding: ActivityMainBinding
    private var score: MidiScore? = null
    private var trackSelection: TrackSelection = TrackSelection.All
    private var visibleNotes: List<MidiNote> = emptyList()
    private var spinnerPopulating = false
    private var expectedBpm = 120.0
    private var nativeInitialized = false
    private var frameLoopActive = false
    private lateinit var testAudioPlayer: TestAudioPlayer
    private var testSeekUserDragging = false
    private var noteNaming = NoteNaming.Letters
    private var showTestMode = false
    private var scoreScrubActive = false
    private var scoreScrubPosition = 0.0
    private var smoothedTestPosition = 0.0
    private var lastNativePosition = 0.0
    private var lastPresentationNanos = 0L
    private var lastNativeRunning = false
    private var lastPositionState: PositionTrackingState? = null

    private val openMidi = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadMidi(uri)
    }

    private val openTestAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadTestAudio(uri)
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
        testAudioPlayer = TestAudioPlayer(this, this)
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        noteNaming = when (prefs.getString(KEY_NOTE_NAMING, NoteNaming.Letters.name)) {
            NoteNaming.Solfege.name -> NoteNaming.Solfege
            else -> NoteNaming.Letters
        }
        binding.scoreView.noteNaming = noteNaming
        showTestMode = prefs.getBoolean(KEY_SHOW_TEST_MODE, false)
        applyTestModeVisibility()

        binding.settingsButton.setOnClickListener { showSettingsMenu() }
        binding.testModeButton.setOnClickListener { setTestModeVisible(!showTestMode) }

        binding.scoreView.onPositionScrubStart = { position ->
            scoreScrubActive = true
            scoreScrubPosition = position
        }
        binding.scoreView.onPositionScrubChanged = { position ->
            scoreScrubPosition = position
            updateUiFromTransport()
        }
        binding.scoreView.onPositionScrubFinished = { position ->
            scoreScrubPosition = position
            scoreScrubActive = false
            if (nativeInitialized) NativeAudioBridge.setManualPosition(position)
            updateUiFromTransport()
        }

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

        binding.loadTestAudioButton.setOnClickListener {
            openTestAudio.launch(arrayOf("audio/mpeg", "audio/mp3", "audio/*"))
        }
        binding.testPlayPauseButton.setOnClickListener {
            if (score == null) {
                Toast.makeText(this, "Load MIDI first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // startTest() stops an active Oboe stream, so test mode and microphone
            // input can never feed the analyzer at the same time.
            testAudioPlayer.togglePlayPause()
        }
        binding.testRestartButton.setOnClickListener {
            if (score == null) return@setOnClickListener
            testAudioPlayer.restart()
        }
        binding.testStopButton.setOnClickListener { testAudioPlayer.stop() }
        binding.testSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = testAudioPlayer.durationMs
                    val pos = if (duration > 0L) duration * progress / 1000L else 0L
                    binding.testTimeText.text = "${formatTime(pos)} / ${formatTime(duration)}"
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { testSeekUserDragging = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val duration = testAudioPlayer.durationMs
                val progress = seekBar?.progress ?: 0
                val pos = if (duration > 0L) duration * progress / 1000L else 0L
                testSeekUserDragging = false
                testAudioPlayer.seekTo(pos)
            }
        })

        binding.trackSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit

            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (spinnerPopulating) return
                trackSelection = TrackSelection.Track(pos)
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
        testAudioPlayer.release()
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
                    testAudioPlayer.stop()
                    if (nativeInitialized && NativeAudioBridge.state().running) {
                        NativeAudioBridge.stop()
                    }
                    score = parsed
                    val defaultTrack = parsed.tracks.firstOrNull { it.noteCount > 0 }?.index
                    trackSelection = defaultTrack?.let { TrackSelection.Track(it) }
                        ?: parsed.tracks.firstOrNull()?.let { TrackSelection.Track(it.index) }
                        ?: TrackSelection.All
                    visibleNotes = parsed.visibleNotes(trackSelection)
                    binding.scoreView.trackSelection = trackSelection
                    populateTrackSelector(parsed)
                    expectedBpm = parsed.initialBpm
                    binding.scoreView.score = parsed
                    binding.fileNameText.text = "$name  •  ${parsed.notes.size} notes"
                    binding.listenButton.isEnabled = true
                    binding.resetButton.isEnabled = true
                    configureNativeEngine()
                    sendScoreReferenceToNative(parsed)
                    onTestAudioStateChanged(testAudioPlayer.state)
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
            val items = score.tracks.map { ScoreNavigator.trackLabel(it, noteNaming) }
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            // Guard must be set before the adapter assignment: setAdapter can
            // fire onItemSelected synchronously (selection clamp) when the old
            // selection index is out of range of the new adapter.
            spinnerPopulating = true
            binding.trackSpinner.adapter = adapter
            val selection = when (val selected = trackSelection) {
                TrackSelection.All -> {
                    val firstTrack = score.tracks.first().index
                    trackSelection = TrackSelection.Track(firstTrack)
                    visibleNotes = score.visibleNotes(trackSelection)
                    binding.scoreView.trackSelection = trackSelection
                    firstTrack.coerceIn(0, items.lastIndex)
                }
                is TrackSelection.Track -> selected.index.coerceIn(0, items.lastIndex)
            }
            binding.trackSpinner.setSelection(selection, false)
            spinnerPopulating = false
            binding.trackSelectorRow.visibility = View.VISIBLE
        } else {
            if (score.tracks.size == 1) {
                trackSelection = TrackSelection.Track(score.tracks[0].index)
                visibleNotes = score.visibleNotes(trackSelection)
                binding.scoreView.trackSelection = trackSelection
            } else {
                trackSelection = TrackSelection.All
                visibleNotes = emptyList()
                binding.scoreView.trackSelection = trackSelection
            }
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
        if (testAudioPlayer.state == TestAudioPlayer.State.Playing ||
            testAudioPlayer.state == TestAudioPlayer.State.Paused) {
            testAudioPlayer.stop()
        }
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
        val testPlaying = testAudioPlayer.state == TestAudioPlayer.State.Playing ||
            testAudioPlayer.state == TestAudioPlayer.State.Paused
        val authoritativePos = state.quarterBeatPosition.coerceAtLeast(0.0)
        val pos = if (scoreScrubActive) {
            resetTestPresentation(authoritativePos)
            scoreScrubPosition
        } else if (testPlaying) {
            smoothTestPresentation(authoritativePos, state)
        } else {
            resetTestPresentation(authoritativePos)
            authoritativePos
        }
        val barBeat = ScoreNavigator.barBeatAt(localScore, pos)
        val now = ScoreNavigator.soundingNotes(localScore, visibleNotes, pos)
        val next = ScoreNavigator.nextNotes(localScore, visibleNotes, pos)

        // Tempo map (spec §26): update the native expected BPM only when the
        // transport crosses a tempo region — not on every frame.
        val regionBpm = ScoreNavigator.tempoAtQuarterBeat(localScore, if (scoreScrubActive) scoreScrubPosition else authoritativePos)
        if (!scoreScrubActive && abs(regionBpm - expectedBpm) > 0.5) {
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
        binding.nowText.text = "Now: ${ScoreNavigator.noteList(now, noteNaming)}"
        binding.nextText.text = "Next: ${ScoreNavigator.noteList(next, noteNaming)}"
        // Beat confidence (fast loop) — kept separate from position confidence (spec §31).
        binding.confidenceText.text = String.format(Locale.US, "Beat: %.0f%%", state.beatConfidence * 100.0)
        binding.positionStatusText.text = formatPositionStatus(state)
        val testActive = testAudioPlayer.state == TestAudioPlayer.State.Playing ||
            testAudioPlayer.state == TestAudioPlayer.State.Paused
        binding.listenButton.isEnabled = !testActive
        binding.listenButton.text = if (state.running && !testActive) {
            getString(R.string.stop_listening)
        } else {
            getString(R.string.start_listening)
        }
    }

    private fun smoothTestPresentation(authoritativePos: Double, state: TransportSnapshot): Double {
        val now = System.nanoTime()
        val elapsed = if (lastPresentationNanos == 0L) 0.0 else
            ((now - lastPresentationNanos).coerceAtLeast(0L) / 1_000_000_000.0).coerceAtMost(0.25)
        val discontinuity = lastPresentationNanos == 0L ||
            lastNativeRunning != state.running || lastPositionState != state.positionState ||
            abs(authoritativePos - lastNativePosition) > 2.0
        if (discontinuity || testAudioPlayer.state == TestAudioPlayer.State.Paused) {
            smoothedTestPosition = authoritativePos
        } else {
            val bpm = if (state.transportBpm > 1.0) state.transportBpm else expectedBpm
            val maxStep = bpm / 60.0 * elapsed * 1.5
            val delta = authoritativePos - smoothedTestPosition
            smoothedTestPosition += delta.coerceIn(-maxStep, maxStep)
        }
        lastNativePosition = authoritativePos
        lastNativeRunning = state.running
        lastPositionState = state.positionState
        lastPresentationNanos = now
        return smoothedTestPosition
    }

    private fun resetTestPresentation(authoritativePos: Double) {
        smoothedTestPosition = authoritativePos
        lastNativePosition = authoritativePos
        lastNativeRunning = false
        lastPositionState = null
        lastPresentationNanos = 0L
    }

    private fun showSettingsMenu() {
        val popup = PopupMenu(this, binding.settingsButton)
        val letters = popup.menu.add(1, MENU_NOTATION_LETTERS, 0, getString(R.string.notation_letters))
        val solfege = popup.menu.add(1, MENU_NOTATION_SOLFEGE, 1, getString(R.string.notation_solfege))
        popup.menu.setGroupCheckable(1, true, true)
        letters.isChecked = noteNaming == NoteNaming.Letters
        solfege.isChecked = noteNaming == NoteNaming.Solfege
        popup.menu.add(2, MENU_SHOW_TEST_MODE, 2, getString(R.string.show_test_mode)).apply {
            isCheckable = true
            isChecked = showTestMode
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_NOTATION_LETTERS -> { setNoteNaming(NoteNaming.Letters); true }
                MENU_NOTATION_SOLFEGE -> { setNoteNaming(NoteNaming.Solfege); true }
                MENU_SHOW_TEST_MODE -> {
                    setTestModeVisible(!showTestMode)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun setNoteNaming(value: NoteNaming) {
        if (noteNaming == value) return
        noteNaming = value
        binding.scoreView.noteNaming = value
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(KEY_NOTE_NAMING, value.name).apply()
        score?.let { populateTrackSelector(it) }
        updateUiFromTransport()
    }

    private fun applyTestModeVisibility() {
        binding.testControlsContainer.visibility = if (showTestMode) View.VISIBLE else View.GONE
        binding.testModeButton.text = getString(
            if (showTestMode) R.string.hide_test_mode else R.string.test_mode,
        )
        binding.testModeButton.isSelected = showTestMode
    }

    private fun setTestModeVisible(visible: Boolean) {
        showTestMode = visible
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(KEY_SHOW_TEST_MODE, showTestMode).apply()
        applyTestModeVisibility()
    }

    private fun loadTestAudio(uri: Uri) {
        val name = displayName(uri) ?: "test audio"
        testAudioPlayer.load(uri)
        binding.testAudioNameText.text = name
        binding.testPlayPauseButton.isEnabled = score != null
        binding.testRestartButton.isEnabled = score != null
        binding.testStopButton.isEnabled = false
        binding.testSeekBar.isEnabled = true
    }

    override fun onTestAudioStateChanged(state: TestAudioPlayer.State) {
        val hasAudio = state != TestAudioPlayer.State.Empty
        val active = state == TestAudioPlayer.State.Playing || state == TestAudioPlayer.State.Paused
        binding.testPlayPauseButton.isEnabled = hasAudio && score != null
        binding.testRestartButton.isEnabled = hasAudio && score != null
        binding.testStopButton.isEnabled = active
        binding.testSeekBar.isEnabled = hasAudio
        binding.testPlayPauseButton.text = when (state) {
            TestAudioPlayer.State.Playing -> getString(R.string.test_pause)
            else -> getString(R.string.test_play)
        }
        binding.listenButton.isEnabled = !active && score != null
        if (state == TestAudioPlayer.State.Empty) {
            binding.testAudioNameText.text = getString(R.string.no_test_audio)
        }
    }

    override fun onTestAudioProgress(positionMs: Long, durationMs: Long) {
        if (!testSeekUserDragging) {
            binding.testTimeText.text = "${formatTime(positionMs)} / ${formatTime(durationMs)}"
            binding.testSeekBar.progress = if (durationMs > 0L) {
                ((positionMs.coerceIn(0L, durationMs) * 1000L) / durationMs).toInt()
            } else 0
        }
    }

    override fun onTestAudioError(message: String) {
        Toast.makeText(this, "Test audio error: $message", Toast.LENGTH_LONG).show()
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0L) / 1000L)
        return String.format(Locale.US, "%d:%02d", totalSeconds / 60L, totalSeconds % 60L)
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
    companion object {
        private const val PREFS_NAME = "temposcore_settings"
        private const val KEY_NOTE_NAMING = "note_naming"
        private const val KEY_SHOW_TEST_MODE = "show_test_mode"
        private const val MENU_NOTATION_LETTERS = 1
        private const val MENU_NOTATION_SOLFEGE = 2
        private const val MENU_SHOW_TEST_MODE = 3
    }

}
