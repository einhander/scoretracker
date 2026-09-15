package com.einhander.temposcore.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.einhander.temposcore.midi.MidiScore
import com.einhander.temposcore.score.TrackSelection
import com.einhander.temposcore.score.visibleNotes
import kotlin.math.max

/**
 * Deliberately simple score preview for the scaffold.
 *
 * It draws a five-line staff-like guide, a fixed playhead and upcoming MIDI noteheads.
 * It is NOT a notation engraver: clefs, accidentals, voices, beams and quantization belong
 * to the dedicated engraving milestone described in SPEC.md.
 */
class ScoreStaffView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xff222222.toInt()
        strokeWidth = 2f
    }
    private val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xff111111.toInt()
        style = Paint.Style.FILL
    }
    private val futurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xff777777.toInt()
        style = Paint.Style.FILL
    }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xffb00020.toInt()
        strokeWidth = 4f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xff333333.toInt()
        textSize = 28f
    }

    var score: MidiScore? = null
        set(value) {
            field = value
            invalidate()
        }

    var quarterBeatPosition: Double = 0.0
        set(value) {
            field = value
            invalidate()
        }

    var trackSelection: TrackSelection = TrackSelection.All
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val staffCenter = h * 0.48f
        val lineGap = max(18f, h * 0.055f)
        for (i in -2..2) {
            val y = staffCenter + i * lineGap
            canvas.drawLine(20f, y, w - 20f, y, linePaint)
        }

        val cursorX = w * 0.34f
        canvas.drawLine(cursorX, 20f, cursorX, h - 20f, cursorPaint)
        canvas.drawText("NOW", cursorX + 8f, 42f, textPaint)

        val localScore = score ?: run {
            canvas.drawText("Load a MIDI file", 32f, h * 0.82f, textPaint)
            return
        }

        val pixelsPerQuarterBeat = max(52f, w / 10f)
        val pastWindow = 2.5
        val futureWindow = 8.0
        val minBeat = quarterBeatPosition - pastWindow
        val maxBeat = quarterBeatPosition + futureWindow

        localScore.visibleNotes(trackSelection).asSequence()
            .filter {
                val beat = localScore.noteStartBeat(it)
                beat in minBeat..maxBeat
            }
            .forEach { note ->
                val startBeat = localScore.noteStartBeat(note)
                val x = cursorX + ((startBeat - quarterBeatPosition) * pixelsPerQuarterBeat).toFloat()
                // Approximate vertical mapping around E4 (MIDI 64). Proper notation comes later.
                val semitoneStep = lineGap / 3.5f
                val y = staffCenter - (note.pitch - 64) * semitoneStep
                val paint = if (startBeat <= quarterBeatPosition + 0.05) notePaint else futurePaint
                val oval = RectF(x - 11f, y - 7f, x + 11f, y + 7f)
                canvas.drawOval(oval, paint)
                canvas.drawLine(x + 10f, y, x + 10f, y - lineGap * 1.7f, paint)
            }
    }
}
