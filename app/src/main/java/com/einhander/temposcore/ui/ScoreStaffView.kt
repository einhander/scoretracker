package com.einhander.temposcore.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.einhander.temposcore.R
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
 * to a later dedicated engraving milestone.
 */
class ScoreStaffView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private fun dp(value: Float) = value * density
    private fun color(id: Int) = ContextCompat.getColor(context, id)
    private val cursorLabel = context.getString(R.string.cursor_now)
    private val emptyLabel = context.getString(R.string.score_empty)

    private data class LabelBounds(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    )

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(R.color.staff_line)
        strokeWidth = dp(0.8f)
    }
    private val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(R.color.accent)
        style = Paint.Style.FILL
    }
    private val futurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(R.color.ink)
        style = Paint.Style.FILL
    }
    private val pastPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(R.color.past_note)
        style = Paint.Style.FILL
    }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(R.color.cursor)
        strokeWidth = dp(1.5f)
    }
    private val textPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(R.color.muted)
        textSize = 12f * scaledDensity
    }
    private val noteLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(R.color.ink)
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

        val localScore = score
        val selectedNotes = localScore?.visibleNotes(trackSelection) ?: emptyList()
        val octaveShift = displayOctaveShift(selectedNotes)
        val minBeat = quarterBeatPosition - 2.5
        val maxBeat = quarterBeatPosition + 8.0
        val visibleNotes = if (localScore != null) selectedNotes.filter {
            localScore.noteStartBeat(it) in minBeat..maxBeat
        } else emptyList()
        // Reserve a separate caption band, then fit heads, stems and ledger lines.
        // Typical register: four line gaps occupy 42% of the viewport height.
        val topInset = minOf(dp(30f), h * 0.22f)
        val bottomInset = minOf(dp(12f), h * 0.08f)
        var topUnits = 2f
        var bottomUnits = 2f
        visibleNotes.forEach {
            val step = diatonicStepFromB4(it.pitch) + octaveShift * 7
            topUnits = max(topUnits, step / 2f + 1.7f + 0.38f)
            bottomUnits = max(bottomUnits, -step / 2f + 0.38f)
        }
        val available = (h - topInset - bottomInset).coerceAtLeast(1f)
        val lineGap = minOf(h * 0.105f, available / (topUnits + bottomUnits))
        val staffCenter = topInset + (available - (topUnits + bottomUnits) * lineGap) / 2f + topUnits * lineGap
        val headX = lineGap * 0.52f
        val headY = lineGap * 0.33f
        val stemX = headX * 0.9f
        val stemWidth = max(dp(0.9f), lineGap * 0.06f)
        val ledgerHalf = headX * 1.5f
        val sideInset = minOf(dp(12f), w * 0.04f)
        for (i in -2..2) {
            val y = staffCenter + i * lineGap
            canvas.drawLine(sideInset, y, w - sideInset, y, linePaint)
        }

        val cursorX = w * 0.34f
        canvas.drawLine(cursorX, topInset, cursorX, h - bottomInset, cursorPaint)
        textPaint.color = color(R.color.cursor)
        val captionBaseline = minOf(-textPaint.fontMetrics.top + dp(3f), topInset - textPaint.fontMetrics.bottom)
        canvas.drawText(cursorLabel, cursorX + dp(5f), captionBaseline, textPaint)

        if (localScore == null) {
            textPaint.color = color(R.color.muted)
            textPaint.textAlign = Paint.Align.CENTER
            val emptyText = android.text.TextUtils.ellipsize(emptyLabel,
                textPaint, (w - sideInset * 2f).coerceAtLeast(1f),
                android.text.TextUtils.TruncateAt.END).toString()
            canvas.drawText(emptyText, w / 2f, (h - bottomInset).coerceAtLeast(captionBaseline), textPaint)
            textPaint.textAlign = Paint.Align.LEFT
            return
        }

        val pixelsPerQuarterBeat = pixelsPerQuarterBeat()
        val labelSize = 13f * scaledDensity
        val labelGap = dp(3f)
        val obstaclePadding = dp(2f)
        noteLabelPaint.textSize = labelSize
        val updatedFontMetrics = noteLabelPaint.fontMetrics
        val minBaseline = topInset - updatedFontMetrics.top + labelGap
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
            textPaint.color = color(R.color.muted)
            canvas.drawText(if (octaveShift < 0) "8va" else "8vb", sideInset, captionBaseline, textPaint)
        }

        notesToDraw.forEach { (note, x, y) ->
            val startBeat = localScore.noteStartBeat(note)
            val paint = when {
                startBeat <= quarterBeatPosition && quarterBeatPosition < localScore.noteEndBeat(note) -> notePaint
                startBeat < quarterBeatPosition -> pastPaint
                else -> futurePaint
            }
            paint.strokeWidth = stemWidth
            val staffStep = diatonicStepFromB4(note.pitch) + octaveShift * 7
            if (staffStep > 4) {
                for (ledgerStep in 6..staffStep step 2) {
                    val ledgerY = staffCenter - ledgerStep * (lineGap / 2f)
                    canvas.drawLine(x - ledgerHalf, ledgerY, x + ledgerHalf, ledgerY, linePaint)
                }
            } else if (staffStep < -4) {
                for (ledgerStep in -6 downTo staffStep step 2) {
                    val ledgerY = staffCenter - ledgerStep * (lineGap / 2f)
                    canvas.drawLine(x - ledgerHalf, ledgerY, x + ledgerHalf, ledgerY, linePaint)
                }
            }
            val oval = RectF(x - headX, y - headY, x + headX, y + headY)
            canvas.drawOval(oval, paint)
            canvas.drawLine(x + stemX, y, x + stemX, y - lineGap * 1.7f, paint)
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
                            noteX - headX - obstaclePadding,
                            noteY - headY - obstaclePadding,
                            noteX + headX + obstaclePadding,
                            noteY + headY + obstaclePadding,
                        ) || overlaps(
                            bounds.left, bounds.top, bounds.right, bounds.bottom,
                            noteX + stemX - stemWidth / 2f - obstaclePadding,
                            noteY - lineGap * 1.7f - obstaclePadding,
                            noteX + stemX + stemWidth / 2f + obstaclePadding,
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
