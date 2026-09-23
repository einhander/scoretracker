package com.einhander.temposcore.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.einhander.temposcore.midi.MidiNote
import com.einhander.temposcore.midi.MidiScore
import com.einhander.temposcore.score.NoteNaming
import com.einhander.temposcore.score.ScoreNavigator
import com.einhander.temposcore.score.TrackSelection
import com.einhander.temposcore.score.visibleNotes
import kotlin.math.max
import kotlin.math.roundToInt

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

    private data class LabelBounds(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    )

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
    private val noteLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xff333333.toInt()
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
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

    var noteNaming: NoteNaming = NoteNaming.Letters
        set(value) {
            field = value
            invalidate()
        }

    var onPositionScrubStart: ((Double) -> Unit)? = null
    var onPositionScrubChanged: ((Double) -> Unit)? = null
    var onPositionScrubFinished: ((Double) -> Unit)? = null

    private var scrubbing = false
    private var scrubStartX = 0f
    private var scrubStartPosition = 0.0

    private fun pixelsPerQuarterBeat(): Float = max(52f, width.toFloat() / 10f)

    /** Treble staff reference: B4 is middle line, E4 is bottom line, F5 is top line. */
    private fun diatonicStepFromB4(pitch: Int): Int {
        val p = pitch.coerceIn(0, 127)
        val octave = p / 12 - 1
        val letterStep = when (p % 12) {
            0, 1 -> 0 // C / C♯
            2, 3 -> 1 // D / D♯
            4 -> 2 // E
            5, 6 -> 3 // F / F♯
            7, 8 -> 4 // G / G♯
            9, 10 -> 5 // A / A♯
            else -> 6 // B
        }
        return octave * 7 + letterStep - (4 * 7 + 6)
    }

    /** Keep register shifts stable while notes enter/leave temporal preview window. */
    private fun displayOctaveShift(notes: List<MidiNote>): Int {
        if (notes.isEmpty()) return 0
        var stepSum = 0
        notes.forEach { stepSum += diatonicStepFromB4(it.pitch) }
        return (-stepSum.toDouble() / notes.size / 7.0).roundToInt().coerceIn(-3, 3)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val localScore = score ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scrubbing = true
                scrubStartX = event.x
                scrubStartPosition = quarterBeatPosition
                parent?.requestDisallowInterceptTouchEvent(true)
                onPositionScrubStart?.invoke(scrubStartPosition)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scrubbing) return false
                val deltaBeats = (event.x - scrubStartX) / pixelsPerQuarterBeat()
                val maxBeat = localScore.tickToQuarterBeats(localScore.totalTicks)
                val newPosition = (scrubStartPosition - deltaBeats).coerceIn(0.0, maxBeat)
                quarterBeatPosition = newPosition
                onPositionScrubChanged?.invoke(newPosition)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!scrubbing) return false
                scrubbing = false
                parent?.requestDisallowInterceptTouchEvent(false)
                onPositionScrubFinished?.invoke(quarterBeatPosition)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
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

        val pixelsPerQuarterBeat = pixelsPerQuarterBeat()
        val pastWindow = 2.5
        val futureWindow = 8.0
        val minBeat = quarterBeatPosition - pastWindow
        val maxBeat = quarterBeatPosition + futureWindow
        val selectedNotes = localScore.visibleNotes(trackSelection)
        val octaveShift = displayOctaveShift(selectedNotes)
        val visibleNotes = selectedNotes.asSequence()
            .filter {
                val beat = localScore.noteStartBeat(it)
                beat in minBeat..maxBeat
            }
            .toList()
        val labelSize = max(16f, 14f * resources.displayMetrics.scaledDensity)
        val labelGap = max(4f, 3f * resources.displayMetrics.density)
        val obstaclePadding = max(3f, 2f * resources.displayMetrics.density)
        noteLabelPaint.textSize = labelSize
        val updatedFontMetrics = noteLabelPaint.fontMetrics
        val minBaseline = max(0f, -updatedFontMetrics.top + labelGap)
        val notesToDraw = visibleNotes
            .map { note ->
                val startBeat = localScore.noteStartBeat(note)
                val x = cursorX + ((startBeat - quarterBeatPosition) * pixelsPerQuarterBeat).toFloat()
                // Stable diatonic staff position. Accidentals share their natural letter step.
                val staffStep = diatonicStepFromB4(note.pitch) + octaveShift * 7
                val y = staffCenter - staffStep * (lineGap / 2f)
                Triple(note, x, y)
        }

        if (octaveShift != 0) {
            canvas.drawText(if (octaveShift < 0) "8va" else "8vb", 28f, 42f, textPaint)
        }

        notesToDraw.forEach { (note, x, y) ->
            val startBeat = localScore.noteStartBeat(note)
            val paint = if (startBeat <= quarterBeatPosition + 0.05) notePaint else futurePaint
            val staffStep = diatonicStepFromB4(note.pitch) + octaveShift * 7
            if (staffStep > 4) {
                for (ledgerStep in 6..staffStep step 2) {
                    val ledgerY = staffCenter - ledgerStep * (lineGap / 2f)
                    canvas.drawLine(x - 16f, ledgerY, x + 16f, ledgerY, linePaint)
                }
            } else if (staffStep < -4) {
                for (ledgerStep in -6 downTo staffStep step 2) {
                    val ledgerY = staffCenter - ledgerStep * (lineGap / 2f)
                    canvas.drawLine(x - 16f, ledgerY, x + 16f, ledgerY, linePaint)
                }
            }
            val oval = RectF(x - 11f, y - 7f, x + 11f, y + 7f)
            canvas.drawOval(oval, paint)
            canvas.drawLine(x + 10f, y, x + 10f, y - lineGap * 1.7f, paint)
        }

        if (notesToDraw.isNotEmpty()) {
            val placedLabels = ArrayList<LabelBounds>(notesToDraw.size)

            fun overlaps(left: Float, top: Float, right: Float, bottom: Float,
                otherLeft: Float, otherTop: Float, otherRight: Float, otherBottom: Float,
            ): Boolean = left < otherRight && otherLeft < right && top < otherBottom && otherTop < bottom

            fun intersectsNoteGeometry(bounds: LabelBounds): Boolean {
                notesToDraw.forEach { (_, noteX, noteY) ->
                    if (overlaps(
                            bounds.left, bounds.top, bounds.right, bounds.bottom,
                            noteX - 11f - obstaclePadding,
                            noteY - 7f - obstaclePadding,
                            noteX + 11f + obstaclePadding,
                            noteY + 7f + obstaclePadding,
                        ) || overlaps(
                            bounds.left, bounds.top, bounds.right, bounds.bottom,
                            noteX + 10f - 1f - obstaclePadding,
                            noteY - lineGap * 1.7f - obstaclePadding,
                            noteX + 10f + 1f + obstaclePadding,
                            noteY + obstaclePadding,
                        )
                    ) return true
                }
                return false
            }

            // Keep labels attached to note x while moving only upward. Skip when no safe space remains.
            notesToDraw.forEach { (note, x, y) ->
                val label = ScoreNavigator.pitchName(note.pitch, noteNaming)
                val halfWidth = noteLabelPaint.measureText(label) / 2f
                if (x - halfWidth < 0f || x + halfWidth > w) return@forEach

                val stemTop = y - lineGap * 1.7f
                val highestSafeBaseline = stemTop - obstaclePadding - labelGap - updatedFontMetrics.bottom
                var baseline = highestSafeBaseline
                var bounds = LabelBounds(
                    x - halfWidth,
                    baseline + updatedFontMetrics.top - labelGap,
                    x + halfWidth,
                    baseline + updatedFontMetrics.bottom + labelGap,
                )
                while (baseline - (labelSize + labelGap) >= minBaseline &&
                    (placedLabels.any { overlaps(
                        it.left, it.top, it.right, it.bottom,
                        bounds.left, bounds.top, bounds.right, bounds.bottom,
                    ) } || intersectsNoteGeometry(bounds))
                ) {
                    baseline -= labelSize + labelGap
                    bounds = LabelBounds(
                        x - halfWidth,
                        baseline + updatedFontMetrics.top - labelGap,
                        x + halfWidth,
                        baseline + updatedFontMetrics.bottom + labelGap,
                    )
                }

                // Never clamp below stem or record a colliding label after space runs out.
                if (baseline < minBaseline || bounds.top < 0f || bounds.bottom > h ||
                    placedLabels.any { overlaps(
                        it.left, it.top, it.right, it.bottom,
                        bounds.left, bounds.top, bounds.right, bounds.bottom,
                    ) } || intersectsNoteGeometry(bounds)
                ) return@forEach

                canvas.drawText(label, x, baseline, noteLabelPaint)
                placedLabels += bounds
            }
        }
    }
}
