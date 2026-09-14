package com.einhander.temposcore

import android.Manifest
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Choreographer
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.einhander.temposcore.databinding.ActivityMainBinding
import com.einhander.temposcore.midi.MidiFileParser
import com.einhander.temposcore.midi.MidiScore
import com.einhander.temposcore.score.ScoreNavigator
import java.util.Locale

class MainActivity : AppCompatActivity(), Choreographer.FrameCallback {
    private lateinit var binding: ActivityMainBinding
    private var score: MidiScore? = null
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
            if (nativeInitialized) NativeAudioBridge.resetPosition(0.0)
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
                    expectedBpm = parsed.initialBpm
                    binding.scoreView.score = parsed
                    binding.fileNameText.text = "$name  •  ${parsed.notes.size} notes"
                    binding.listenButton.isEnabled = true
                    binding.resetButton.isEnabled = true
                    configureNativeEngine()
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

    private fun configureNativeEngine() {
        if (!nativeInitialized) {
            NativeAudioBridge.initialize(expectedBpm, 0.0)
            nativeInitialized = true
        } else {
            NativeAudioBridge.setExpectedBpm(expectedBpm)
            NativeAudioBridge.resetPosition(0.0)
        }
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
        val now = ScoreNavigator.soundingNotes(localScore, pos)
        val next = ScoreNavigator.nextNotes(localScore, pos)

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
        binding.confidenceText.text = String.format(Locale.US, "Confidence: %.0f%%", state.confidence * 100.0)
        binding.listenButton.text = if (state.running) getString(R.string.stop_listening) else getString(R.string.start_listening)
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
