package com.einhander.temposcore

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Choreographer
import android.view.Menu
import android.view.View
import android.view.WindowManager
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
import com.einhander.temposcore.score.DurationDisplay
import com.einhander.temposcore.score.AccidentalPreference
import com.einhander.temposcore.score.NoteStartGroup
import com.einhander.temposcore.score.ScoreNavigator
import com.einhander.temposcore.score.TrackSelection
import com.einhander.temposcore.score.visibleNotes
import com.einhander.temposcore.score.keySignatureAt
import com.einhander.temposcore.score.pitchName
import com.einhander.temposcore.score.spellMidiPitch
import com.einhander.temposcore.ui.NoteSpellingCache
import com.einhander.temposcore.ui.browser.MidiBrowserDialog
import com.einhander.temposcore.ui.browser.BrowserMode
import com.einhander.temposcore.ui.browser.shouldReleaseTreePermission
import com.einhander.temposcore.transport.PositionTrackingState
import com.einhander.temposcore.transport.TransportSnapshot
import java.util.Locale
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Compact controls retain full-size touch targets without overlaying the score. */
internal fun collapsedPanelHeight(expandedHeight: Int, density: Float): Int {
    require(density.isFinite() && density > 0f)
    if (expandedHeight <= 0) return 0
    val minimum = ceil(48f * density).toInt().coerceAtLeast(1)
    return maxOf((expandedHeight * 0.1f).roundToInt(), minimum).coerceAtMost(expandedHeight)
}

internal data class TransportControlState(val listenEnabled: Boolean, val resetEnabled: Boolean, val stopping: Boolean)

internal fun transportControlState(scoreLoaded: Boolean, initialized: Boolean, running: Boolean,
    testActive: Boolean, busy: Boolean): TransportControlState {
    val ready = scoreLoaded && initialized
    return TransportControlState(ready && !testActive && !busy, ready, ready && running && !testActive)
}

/** Exclusive MenuItem.setChecked(false) also selects that item: only check the chosen radio item. */
internal fun applySettingsMenuChecks(selectedRadioItemIds: List<Int>, toggleChecks: Map<Int, Boolean>,
    setChecked: (Int, Boolean) -> Unit) {
    for (id in selectedRadioItemIds) setChecked(id, true)
    for ((id, checked) in toggleChecks) setChecked(id, checked)
}

class MainActivity : AppCompatActivity(), Choreographer.FrameCallback, TestAudioPlayer.Listener, MidiBrowserDialog.Host {
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
    private var accidentalPreference = AccidentalPreference.Sharps
    private var noteSpellingCache: NoteSpellingCache? = null
    private var showTestMode = false
    private var testPanelExpanded = true
    private var upperPanelExpanded = true
    private var durationDisplay = DurationDisplay.Symbols
    private var pitchColors = false
    private var scoreScrubActive = false
    private var scoreScrubPosition = 0.0
    private var smoothedTestPosition = 0.0
    private var lastNativePosition = 0.0
    private var lastPresentationNanos = 0L
    private var lastNativeRunning = false
    private var lastPositionState: PositionTrackingState? = null
    private var lastDiagnosticSampleNs = 0L
    private var midiLoadGeneration = 0
    private var testAudioLoadGeneration = 0
    private var listeningStartPending = false
    private var micPermissionPending = false

    private val openMidi = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadMidi(uri)
    }

    private val openMidiTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) persistBrowserTree(BrowserMode.MIDI, uri)
        showFileBrowser(BrowserMode.MIDI)
    }

    private val openTestAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadTestAudio(uri)
    }

    private val openMp3Tree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) persistBrowserTree(BrowserMode.MP3, uri)
        showFileBrowser(BrowserMode.MP3)
    }

    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micPermissionPending = false
        if (granted) startListening() else Toast.makeText(
            this,
            getString(R.string.permission_required),
            Toast.LENGTH_LONG,
        ).show()
        updateTransportControls()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Keep controls outside display cutouts. Configure once: transient system
        // bars must not change the content bounds or cause inset/padding jumps.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
            }
        }
        restoreImmersiveMode()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        testAudioPlayer = TestAudioPlayer(this, this)
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        durationDisplay = DurationDisplay.entries.firstOrNull { it.name == prefs.getString(KEY_DURATION_DISPLAY, null) }
            ?: DurationDisplay.Symbols
        pitchColors = prefs.getBoolean(KEY_PITCH_COLORS, false)
        binding.scoreView.durationDisplay = durationDisplay
        binding.scoreView.pitchColors = pitchColors
        upperPanelExpanded = prefs.getBoolean(KEY_UPPER_PANEL_EXPANDED, true)
        binding.collapsePanelButton.setOnClickListener { setUpperPanelExpanded(false) }
        binding.expandPanelButton.setOnClickListener { setUpperPanelExpanded(true) }
        binding.mainRoot.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) applyUpperPanelLayout()
        }
        applyUpperPanelLayout()
        noteNaming = when (prefs.getString(KEY_NOTE_NAMING, NoteNaming.Letters.name)) {
            NoteNaming.Solfege.name -> NoteNaming.Solfege
            else -> NoteNaming.Letters
        }
        binding.scoreView.noteNaming = noteNaming
        accidentalPreference = when (prefs.getString(KEY_ACCIDENTAL_PREFERENCE, AccidentalPreference.Sharps.name)) {
            AccidentalPreference.Flats.name -> AccidentalPreference.Flats
            else -> AccidentalPreference.Sharps
        }
        binding.scoreView.accidentalPreference = accidentalPreference
        showTestMode = prefs.getBoolean(KEY_SHOW_TEST_MODE, false)
        applyTestModeVisibility()

        binding.settingsButton.setOnClickListener { showSettingsMenu() }
        binding.testModeButton.setOnClickListener {
            testPanelExpanded = !testPanelExpanded
            applyTestModeVisibility()
        }
        binding.exitTestModeButton.setOnClickListener {
            testAudioPlayer.stop()
            setTestModeVisible(false)
        }

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
        binding.scoreView.onNoteGroupsRequested = { groups -> showNoteGroups(groups) }

        binding.loadMidiButton.setOnClickListener {
            openFileBrowser(BrowserMode.MIDI)
        }

        val listenClick = View.OnClickListener { toggleListening() }
        binding.listenButton.setOnClickListener(listenClick)
        binding.compactListenButton.setOnClickListener(listenClick)
        val resetClick = View.OnClickListener { resetScorePosition() }
        binding.resetButton.setOnClickListener(resetClick)
        binding.compactResetButton.setOnClickListener(resetClick)

        binding.loadTestAudioButton.setOnClickListener {
            openFileBrowser(BrowserMode.MP3)
        }
        binding.testPlayPauseButton.setOnClickListener {
            if (score == null) {
                Toast.makeText(this, R.string.midi_first, Toast.LENGTH_SHORT).show()
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
                    binding.testTimeText.text = getString(R.string.test_time, formatTime(pos), formatTime(duration))
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
                binding.trackSpinner.contentDescription = getString(R.string.track_label) + ": " +
                    (view?.contentDescription?.toString() ?: parent?.getItemAtPosition(pos)?.toString() ?: "")
                updateUiFromTransport()
            }
        }
        updateTransportControls()
    }

    private fun treePreference(mode: BrowserMode) = if (mode == BrowserMode.MIDI) KEY_MIDI_TREE else KEY_MP3_TREE

    private fun setUpperPanelExpanded(expanded: Boolean) {
        val previous = if (expanded) binding.expandPanelButton else binding.collapsePanelButton
        val transferInputFocus = previous.hasFocus()
        val transferAccessibilityFocus = previous.isAccessibilityFocused
        upperPanelExpanded = expanded
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(KEY_UPPER_PANEL_EXPANDED, expanded).apply()
        applyUpperPanelLayout()
        val target = if (expanded) binding.collapsePanelButton else binding.expandPanelButton
        if (transferInputFocus) target.requestFocus()
        if (transferAccessibilityFocus) target.post {
            if (target.visibility == View.VISIBLE) {
                target.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
            }
        }
    }

    private fun applyUpperPanelLayout() {
        val width = binding.topPanelContainer.width
        if (width > 0) {
            // Measure natural expanded content even when hidden; never infer it from the collapsed wrapper.
            binding.topPanel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        }
        val expandedHeight = binding.topPanel.measuredHeight.takeIf { it > 0 }
            ?: (124f * resources.displayMetrics.density).toInt()
        val slimHeight = collapsedPanelHeight(expandedHeight, resources.displayMetrics.density)
        // Keep all three metrics readable: stack below controls when width or font scale needs it.
        val density = resources.displayMetrics.density
        val stacked = width > 0 && width / density < 276f + 330f * resources.configuration.fontScale
        binding.compactPanel.orientation = if (stacked) android.widget.LinearLayout.VERTICAL else android.widget.LinearLayout.HORIZONTAL
        binding.compactTempos.layoutParams = (binding.compactTempos.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
            this.width = if (stacked) android.view.ViewGroup.LayoutParams.MATCH_PARENT else 0
            weight = if (stacked) 0f else 1f
        }
        val verticalTempos = stacked && width / density < 330f * resources.configuration.fontScale
        binding.compactTempos.orientation = if (verticalTempos) android.widget.LinearLayout.VERTICAL else android.widget.LinearLayout.HORIZONTAL
        listOf(binding.compactTempoText, binding.compactLiveTempoText, binding.compactTransportTempoText).forEach { metric ->
            metric.layoutParams = (metric.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
                this.width = if (verticalTempos) android.view.ViewGroup.LayoutParams.MATCH_PARENT else 0
                weight = if (verticalTempos) 0f else 1f
            }
        }
        if (width > 0) {
            binding.compactPanel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        }
        val targetHeight = if (upperPanelExpanded) android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            else maxOf(slimHeight, binding.compactPanel.measuredHeight)
        if (binding.topPanelContainer.layoutParams.height != targetHeight) {
            binding.topPanelContainer.layoutParams = binding.topPanelContainer.layoutParams.apply { height = targetHeight }
        }
        binding.topPanel.visibility = if (upperPanelExpanded) View.VISIBLE else View.GONE
        binding.topPanelContainer.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        binding.compactPanel.visibility = if (upperPanelExpanded) View.GONE else View.VISIBLE
    }

    private fun openFileBrowser(mode: BrowserMode) {
        if (getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(treePreference(mode), null) == null) {
            chooseBrowserFolder(mode)
        } else showFileBrowser(mode)
    }

    private fun showFileBrowser(mode: BrowserMode) {
        if (supportFragmentManager.isStateSaved || supportFragmentManager.findFragmentByTag(MidiBrowserDialog.TAG) != null) return
        val tree = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(treePreference(mode), null)
        MidiBrowserDialog.create(tree, mode).show(supportFragmentManager, MidiBrowserDialog.TAG)
    }

    private fun persistBrowserTree(mode: BrowserMode, uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val key = treePreference(mode)
            val otherKey = treePreference(if (mode == BrowserMode.MIDI) BrowserMode.MP3 else BrowserMode.MIDI)
            val previous = prefs.getString(key, null)
            val otherTree = prefs.getString(otherKey, null)
            prefs.edit().putString(key, uri.toString()).apply()
            if (shouldReleaseTreePermission(previous, uri.toString(), otherTree)) {
                try {
                    contentResolver.releasePersistableUriPermission(Uri.parse(previous!!), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: SecurityException) { /* Old permission may already have been revoked. */ }
            }
        } catch (_: SecurityException) {
            Toast.makeText(this, R.string.browser_grant_error, Toast.LENGTH_LONG).show()
        }
    }

    override fun chooseBrowserFolder(mode: BrowserMode) {
        val tree = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(treePreference(mode), null)?.let(Uri::parse)
        if (mode == BrowserMode.MIDI) openMidiTree.launch(tree) else openMp3Tree.launch(tree)
    }

    override fun chooseWithSystemPicker(mode: BrowserMode) {
        if (mode == BrowserMode.MIDI) {
            openMidi.launch(arrayOf("audio/midi", "audio/x-midi", "application/octet-stream"))
        } else openTestAudio.launch(arrayOf("audio/mpeg", "audio/mp3"))
    }

    override fun selectBrowserDocument(mode: BrowserMode, uri: Uri) {
        if (mode == BrowserMode.MIDI) loadMidi(uri) else loadTestAudio(uri)
    }

    override fun onResume() {
        super.onResume()
        restoreImmersiveMode()
        if (!frameLoopActive) {
            frameLoopActive = true
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // SAF and microphone permission dialogs can return focus without a new
        // onResume. Do not fight system gestures while focus stays unchanged.
        if (hasFocus) restoreImmersiveMode()
    }

    @Suppress("DEPRECATION")
    private fun restoreImmersiveMode() {
        // Available on every supported API (26+). IMMERSIVE_STICKY lets edge
        // gestures reveal transient bars; layout flags keep the score stationary.
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    override fun onPause() {
        frameLoopActive = false
        Choreographer.getInstance().removeFrameCallback(this)
        super.onPause()
    }

    override fun onDestroy() {
        midiLoadGeneration++
        testAudioLoadGeneration++
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
        val token = ++midiLoadGeneration
        val weak = WeakReference(this)
        val resolver = applicationContext.contentResolver
        val handler = Handler(Looper.getMainLooper())
        Thread {
            val result = runCatching {
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Could not read selected file")
                val parsed = MidiFileParser.parse(bytes)
                val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: "MIDI"
                parsed to name
            }
            handler.post {
                val activity = weak.get()
                if (activity != null && !activity.isDestroyed && !activity.isFinishing && activity.midiLoadGeneration == token) {
                    result.fold(onSuccess = { (parsed, name) -> activity.applyLoadedMidi(parsed, name) }, onFailure = { error ->
                        Toast.makeText(activity, activity.getString(R.string.midi_error, error.message), Toast.LENGTH_LONG).show()
                    })
                    activity.binding.loadMidiButton.isEnabled = true
                }
            }
        }.start()
    }

    private fun applyLoadedMidi(parsed: MidiScore, name: String) {
        testAudioPlayer.stop()
        if (nativeInitialized && NativeAudioBridge.state().running) NativeAudioBridge.stop()
        score = parsed
        noteSpellingCache = NoteSpellingCache(parsed, parsed.notes, accidentalPreference)
        val defaultTrack = parsed.tracks.firstOrNull { it.noteCount > 0 }?.index
        trackSelection = defaultTrack?.let { TrackSelection.Track(it) }
            ?: parsed.tracks.firstOrNull()?.let { TrackSelection.Track(it.index) }
            ?: TrackSelection.All
        visibleNotes = parsed.visibleNotes(trackSelection)
        binding.scoreView.trackSelection = trackSelection
        populateTrackSelector(parsed)
        expectedBpm = parsed.initialBpm
        binding.scoreView.score = parsed
        binding.fileNameText.text = name
        binding.fileNameText.contentDescription = name
        configureNativeEngine()
        sendScoreReferenceToNative(parsed)
        onTestAudioStateChanged(testAudioPlayer.state)
        updateUiFromTransport()
    }

    private fun populateTrackSelector(score: MidiScore) {
        if (score.tracks.size > 1) {
            val items = score.tracks.map {
                getString(R.string.track_item, it.index + 1, it.name?.takeIf { name -> name.isNotBlank() }
                    ?: getString(R.string.track_unnamed))
            }
            val details = score.tracks.map {
                when {
                    it.noteCount == 0 -> getString(R.string.track_no_notes)
                    it.pitchMin != null && it.pitchMax != null -> getString(R.string.track_details,
                        it.noteCount, pitchName(spellMidiPitch(it.pitchMin, keySignatureAt(score, 0L), accidentalPreference), noteNaming),
                        pitchName(spellMidiPitch(it.pitchMax, keySignatureAt(score, 0L), accidentalPreference), noteNaming))
                    else -> getString(R.string.track_note_count, it.noteCount)
                }
            }
            val adapter = object : ArrayAdapter<String>(this, R.layout.track_spinner_item, items) {
                override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                    return super.getView(position, convertView, parent).apply {
                        contentDescription = "${items[position]}. ${details[position]}. ${getString(R.string.track_range_spelling_context)}"
                    }
                }

                override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                    val row = convertView ?: layoutInflater.inflate(R.layout.track_spinner_dropdown_item, parent, false)
                    row.findViewById<android.widget.TextView>(R.id.trackTitle).text = items[position]
                    row.findViewById<android.widget.TextView>(R.id.trackDetails).text = details[position]
                    row.contentDescription = "${items[position]}. ${details[position]}. ${getString(R.string.track_range_spelling_context)}"
                    return row
                }
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
            binding.trackSpinner.contentDescription = getString(R.string.track_label) + ": " +
                items[selection] + ". " + details[selection] + ". " + getString(R.string.track_range_spelling_context)
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
            micPermissionPending = true
            updateTransportControls()
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startListening() {
        if (!nativeInitialized || listeningStartPending) return
        if (testAudioPlayer.state == TestAudioPlayer.State.Playing ||
            testAudioPlayer.state == TestAudioPlayer.State.Paused) {
            testAudioPlayer.stop()
        }
        listeningStartPending = true
        updateTransportControls()
        Thread {
            val ok = NativeAudioBridge.start()
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                listeningStartPending = false
                updateTransportControls()
                if (!ok) Toast.makeText(this, R.string.mic_error, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun stopListening() {
        NativeAudioBridge.stop()
        updateTransportControls()
    }

    private fun toggleListening() {
        if (!binding.listenButton.isEnabled) return
        val running = if (nativeInitialized) NativeAudioBridge.state().running else false
        if (running) stopListening() else ensureMicAndStart()
    }

    private fun resetScorePosition() {
        if (!binding.resetButton.isEnabled) return
        NativeAudioBridge.resetPosition(0.0)
        // Reset requests fresh score acquisition, not an MP3 restart.
        NativeAudioBridge.requestGlobalReacquire()
    }

    private fun updateTransportControls(running: Boolean = if (nativeInitialized) NativeAudioBridge.state().running else false) {
        val testActive = testAudioPlayer.state == TestAudioPlayer.State.Playing || testAudioPlayer.state == TestAudioPlayer.State.Paused
        val controls = transportControlState(score != null, nativeInitialized, running, testActive,
            listeningStartPending || micPermissionPending)
        val label = getString(if (controls.stopping) R.string.stop_listening else R.string.start_listening)
        if (binding.listenButton.text.toString() != label) binding.listenButton.text = label
        if (binding.compactListenButton.text.toString() != label) binding.compactListenButton.text = label
        binding.listenButton.isEnabled = controls.listenEnabled
        binding.compactListenButton.isEnabled = controls.listenEnabled
        binding.resetButton.isEnabled = controls.resetEnabled
        binding.compactResetButton.isEnabled = controls.resetEnabled
        val hasTracks = binding.trackSelectorRow.visibility == View.VISIBLE
        val summary = if (hasTracks) binding.trackSpinner.selectedItem?.toString() ?: binding.fileNameText.text.toString()
            else binding.fileNameText.text.toString()
        if (binding.compactTrackText.text.toString() != summary) binding.compactTrackText.text = summary
        binding.compactTrackText.contentDescription = if (hasTracks) binding.trackSpinner.contentDescription else binding.fileNameText.contentDescription
    }

    private fun updateUiFromTransport() {
        val state = if (nativeInitialized) NativeAudioBridge.state() else null
        updateTransportControls(state?.running ?: false)
        val localScore = score ?: return
        if (state == null) return
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
        if (BuildConfig.DEBUG && testPlaying) {
            val diagnosticNow = System.nanoTime()
            if (diagnosticNow - lastDiagnosticSampleNs >= 250_000_000L) {
                PlaybackDiagnostics.currentSession()?.let { diagnosticSession ->
                    PlaybackDiagnostics.recordDisplay(diagnosticSession, diagnosticNow, authoritativePos, pos)
                }
                lastDiagnosticSampleNs = diagnosticNow
            }
        }
        binding.tempoText.text = getString(R.string.midi_tempo, expectedBpm)
        binding.liveTempoText.text = getString(R.string.live_tempo,
            if (state.detectedBpm > 1.0) String.format(Locale.getDefault(), "%.1f", state.detectedBpm) else "—")
        binding.transportTempoText.text = getString(R.string.transport_tempo, state.transportBpm)
        // Same formatted text, same snapshot, including LIVE's unavailable marker.
        binding.compactTempoText.text = binding.tempoText.text
        binding.compactLiveTempoText.text = binding.liveTempoText.text
        binding.compactTransportTempoText.text = binding.transportTempoText.text
        binding.positionText.text = getString(
            R.string.position_format,
            barBeat.bar,
            barBeat.beat,
            barBeat.numerator,
            barBeat.denominator,
        )
        binding.nowText.text = getString(R.string.now_notes, spelledNoteList(now))
        binding.nextText.text = getString(R.string.next_notes, spelledNoteList(next))
        // Beat confidence (fast loop) — kept separate from position confidence (spec §31).
        binding.confidenceText.text = getString(R.string.beat_confidence, state.beatConfidence * 100.0)
        binding.positionStatusText.text = formatPositionStatus(state)
        binding.positionStatusText.setTextColor(ContextCompat.getColor(this,
            if (state.positionState == PositionTrackingState.Weak) R.color.warning else R.color.ink))
        binding.positionStatusText.contentDescription = if (state.positionState == PositionTrackingState.Locked) {
            getString(R.string.position_locked) + ". " +
                getString(R.string.position_confidence_description, state.positionConfidence * 100.0)
        } else formatPositionStatus(state)
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
        popup.menu.add(1, MENU_NOTATION_LETTERS, 0, getString(R.string.notation_letters))
        popup.menu.add(1, MENU_NOTATION_SOLFEGE, 1, getString(R.string.notation_solfege))
        popup.menu.setGroupCheckable(1, true, true)
        popup.menu.add(2, MENU_SHOW_TEST_MODE, 2, getString(R.string.show_test_mode)).apply {
            isCheckable = true
        }
        popup.menu.add(4, MENU_ACCIDENTAL_HINT, 3, getString(R.string.accidental_midi_priority)).isEnabled = false
        popup.menu.add(3, MENU_ACCIDENTAL_SHARPS, 4, getString(R.string.accidental_sharps))
        popup.menu.add(3, MENU_ACCIDENTAL_FLATS, 5, getString(R.string.accidental_flats))
        popup.menu.setGroupCheckable(3, true, true)
        popup.menu.add(5, MENU_DURATION_SYMBOLS, 6, getString(R.string.duration_symbols))
        popup.menu.add(5, MENU_DURATION_RIBBONS, 7, getString(R.string.duration_ribbons))
        popup.menu.setGroupCheckable(5, true, true)
        popup.menu.add(6, MENU_PITCH_COLORS, 8, getString(R.string.pitch_colors)).apply {
            isCheckable = true
        }
        syncSettingsMenuChecks(popup.menu)
        popup.setOnMenuItemClickListener { item ->
            val handled = when (item.itemId) {
                MENU_NOTATION_LETTERS -> { setNoteNaming(NoteNaming.Letters); true }
                MENU_NOTATION_SOLFEGE -> { setNoteNaming(NoteNaming.Solfege); true }
                MENU_ACCIDENTAL_SHARPS -> { setAccidentalPreference(AccidentalPreference.Sharps); true }
                MENU_ACCIDENTAL_FLATS -> { setAccidentalPreference(AccidentalPreference.Flats); true }
                MENU_DURATION_SYMBOLS -> { setDurationDisplay(DurationDisplay.Symbols); true }
                MENU_DURATION_RIBBONS -> { setDurationDisplay(DurationDisplay.Ribbons); true }
                MENU_PITCH_COLORS -> {
                    pitchColors = !pitchColors
                    binding.scoreView.pitchColors = pitchColors
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(KEY_PITCH_COLORS, pitchColors).apply()
                    true
                }
                MENU_SHOW_TEST_MODE -> {
                    setTestModeVisible(!showTestMode)
                    true
                }
                else -> false
            }
            // Synchronize immediately, before the normal PopupMenu dismissal, not only on next open.
            if (handled) syncSettingsMenuChecks(popup.menu)
            handled
        }
        popup.show()
    }

    private fun syncSettingsMenuChecks(menu: Menu) {
        applySettingsMenuChecks(listOf(
            if (noteNaming == NoteNaming.Letters) MENU_NOTATION_LETTERS else MENU_NOTATION_SOLFEGE,
            if (accidentalPreference == AccidentalPreference.Sharps) MENU_ACCIDENTAL_SHARPS else MENU_ACCIDENTAL_FLATS,
            if (durationDisplay == DurationDisplay.Symbols) MENU_DURATION_SYMBOLS else MENU_DURATION_RIBBONS,
        ), mapOf(MENU_PITCH_COLORS to pitchColors, MENU_SHOW_TEST_MODE to showTestMode)) { id, checked ->
            menu.findItem(id).isChecked = checked
        }
    }

    /** Full, scrollable fallback for chord labels: never ellipsize or substitute live BPM. */
    @Suppress("DEPRECATION")
    private fun showNoteGroups(groups: List<NoteStartGroup>) {
        val localScore = score ?: return
        val spellings = noteSpellingCache ?: return
        if (groups.isEmpty()) return
        val details = buildString {
            append(getString(R.string.staff_details_hint))
            for (group in groups) {
                append("\n\n")
                append(getString(R.string.staff_group_header, group.startBeat,
                    group.uniquePitches.joinToString(" · ") { spellings.name(it, group.startTick, noteNaming) }))
                for (note in group.notes) {
                    append("\n")
                    append(getString(R.string.staff_note_details,
                        spellings.name(note, noteNaming),
                        note.durationTicks.toDouble() / localScore.ppq,
                        localScore.noteEndBeat(note), note.track + 1, note.channel + 1))
                }
            }
        }
        val padding = (16f * resources.displayMetrics.density).toInt()
        val text = android.widget.TextView(this).apply {
            this.text = details
            textSize = 16f
            setPadding(padding, padding, padding, padding)
            setTextIsSelectable(true)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(text) }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.staff_details_title)
            .setView(scroll)
            .setPositiveButton(R.string.staff_details_close, null)
            .show()
        // A focused dialog has its own decor; retain the activity's sticky
        // immersive policy instead of permanently revealing navigation bars.
        dialog.window?.decorView?.systemUiVisibility = window.decorView.systemUiVisibility
    }

    private fun spelledNoteList(notes: List<MidiNote>): String {
        val spellings = noteSpellingCache ?: return "—"
        return if (notes.isEmpty()) "—" else notes.joinToString(" ") { spellings.name(it, noteNaming) }
    }

    private fun setAccidentalPreference(value: AccidentalPreference) {
        if (accidentalPreference == value) return
        accidentalPreference = value
        binding.scoreView.accidentalPreference = value
        noteSpellingCache = score?.let { NoteSpellingCache(it, it.notes, value) }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(KEY_ACCIDENTAL_PREFERENCE, value.name).apply()
        score?.let { populateTrackSelector(it) }
        updateUiFromTransport()
    }

    private fun setDurationDisplay(value: DurationDisplay) {
        durationDisplay = value
        binding.scoreView.durationDisplay = value
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(KEY_DURATION_DISPLAY, value.name).apply()
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
        binding.testControlsContainer.visibility = if (showTestMode && testPanelExpanded) View.VISIBLE else View.GONE
        binding.testModeButton.visibility = if (showTestMode) View.VISIBLE else View.GONE
        binding.testModeButton.text = getString(
            if (testPanelExpanded) R.string.hide_test_mode else R.string.test_mode,
        )
        binding.testModeButton.contentDescription = getString(
            if (testPanelExpanded) R.string.test_panel_close else R.string.test_panel_open)
        binding.testModeButton.isSelected = showTestMode
    }

    private fun setTestModeVisible(visible: Boolean) {
        showTestMode = visible
        if (visible) testPanelExpanded = true
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(KEY_SHOW_TEST_MODE, showTestMode).apply()
        applyTestModeVisibility()
    }

    private fun loadTestAudio(uri: Uri) {
        binding.loadTestAudioButton.isEnabled = false
        val token = ++testAudioLoadGeneration
        val weak = WeakReference(this)
        val resolver = applicationContext.contentResolver
        val handler = Handler(Looper.getMainLooper())
        Thread {
            val name = runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            }.getOrNull()
            handler.post {
                val activity = weak.get()
                if (activity != null && !activity.isDestroyed && !activity.isFinishing && activity.testAudioLoadGeneration == token) {
                    activity.testAudioPlayer.load(uri)
                    activity.binding.testAudioNameText.text = name ?: activity.getString(R.string.test_audio_fallback)
                    activity.binding.testPlayPauseButton.isEnabled = activity.score != null
                    activity.binding.testRestartButton.isEnabled = activity.score != null
                    activity.binding.testStopButton.isEnabled = false
                    activity.binding.testSeekBar.isEnabled = true
                    activity.binding.loadTestAudioButton.isEnabled = true
                }
            }
        }.start()
    }

    override fun onTestAudioStateChanged(state: TestAudioPlayer.State) {
        val hasAudio = state != TestAudioPlayer.State.Empty
        val active = state == TestAudioPlayer.State.Playing || state == TestAudioPlayer.State.Paused
        binding.testStateText.text = getString(when (state) {
            TestAudioPlayer.State.Playing -> R.string.test_active
            TestAudioPlayer.State.Paused -> R.string.test_paused
            else -> R.string.test_inactive
        })
        binding.testPlayPauseButton.isEnabled = hasAudio && score != null
        binding.testRestartButton.isEnabled = hasAudio && score != null
        binding.testStopButton.isEnabled = active
        binding.testSeekBar.isEnabled = hasAudio
        binding.testPlayPauseButton.text = when (state) {
            TestAudioPlayer.State.Playing -> getString(R.string.test_pause)
            else -> getString(R.string.test_play)
        }
        updateTransportControls()
        if (state == TestAudioPlayer.State.Empty) {
            binding.testAudioNameText.text = getString(R.string.no_test_audio)
        }
    }

    override fun onTestAudioProgress(positionMs: Long, durationMs: Long) {
        if (!testSeekUserDragging) {
            binding.testTimeText.text = getString(R.string.test_time, formatTime(positionMs), formatTime(durationMs))
            binding.testSeekBar.progress = if (durationMs > 0L) {
                ((positionMs.coerceIn(0L, durationMs) * 1000L) / durationMs).toInt()
            } else 0
        }
    }

    override fun onTestAudioError(message: String) {
        Toast.makeText(this, getString(R.string.test_error, message), Toast.LENGTH_LONG).show()
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0L) / 1000L)
        return String.format(Locale.US, "%d:%02d", totalSeconds / 60L, totalSeconds % 60L)
    }

    private fun formatPositionStatus(state: TransportSnapshot): String = when (state.positionState) {
        PositionTrackingState.Idle -> getString(R.string.position_idle)
        PositionTrackingState.Acquiring -> getString(R.string.position_acquiring)
        PositionTrackingState.Locked -> getString(R.string.position_locked)
        PositionTrackingState.Weak -> getString(R.string.position_weak)
        PositionTrackingState.Reacquiring -> getString(R.string.position_reacquiring)
    }

    companion object {
        private const val PREFS_NAME = "temposcore_settings"
        private const val KEY_NOTE_NAMING = "note_naming"
        private const val KEY_ACCIDENTAL_PREFERENCE = "accidental_preference"
        private const val KEY_SHOW_TEST_MODE = "show_test_mode"
        private const val KEY_UPPER_PANEL_EXPANDED = "upper_panel_expanded"
        private const val KEY_DURATION_DISPLAY = "duration_display"
        private const val KEY_PITCH_COLORS = "pitch_colors"
        private const val KEY_MIDI_TREE = "midi_tree_uri"
        private const val KEY_MP3_TREE = "mp3_tree_uri"
        private const val MENU_NOTATION_LETTERS = 1
        private const val MENU_NOTATION_SOLFEGE = 2
        private const val MENU_SHOW_TEST_MODE = 3
        private const val MENU_ACCIDENTAL_SHARPS = 4
        private const val MENU_ACCIDENTAL_FLATS = 5
        private const val MENU_ACCIDENTAL_HINT = 6
        private const val MENU_DURATION_SYMBOLS = 7
        private const val MENU_DURATION_RIBBONS = 8
        private const val MENU_PITCH_COLORS = 9
    }

}
