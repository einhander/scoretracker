package com.einhander.temposcore.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.ContextCompat
import com.einhander.temposcore.R
import com.einhander.temposcore.midi.MidiNote
import com.einhander.temposcore.midi.MidiScore
import com.einhander.temposcore.score.BeatViewport
import com.einhander.temposcore.score.MeasureBoundary
import com.einhander.temposcore.score.NoteInterval
import com.einhander.temposcore.score.NoteNaming
import com.einhander.temposcore.score.NoteStartGroup
import com.einhander.temposcore.score.NoteTimeState
import com.einhander.temposcore.score.ScoreNavigator
import com.einhander.temposcore.score.TrackSelection
import com.einhander.temposcore.score.groupNotesByStartTick
import com.einhander.temposcore.score.noteInterval
import com.einhander.temposcore.score.visibleNotes
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** MIDI timing preview, not a notation engraver or microphone pitch detector. */
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
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private fun paint(id: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(id) }
    private val linePaint = paint(R.color.staff_line).apply { strokeWidth = dp(0.8f) }
    private val barPaint = paint(R.color.staff_bar).apply { strokeWidth = dp(0.8f) }
    private val futurePaint = paint(R.color.ink)
    private val activePaint = paint(R.color.active_note)
    private val pastPaint = paint(R.color.past_note)
    private val futureRibbonPaint = paint(R.color.future_ribbon)
    private val activeRibbonPaint = paint(R.color.active_ribbon)
    private val activeElapsedPaint = paint(R.color.active_elapsed_ribbon)
    private val pastRibbonPaint = paint(R.color.past_ribbon)
    private val cursorPaint = paint(R.color.cursor).apply { strokeWidth = dp(1.5f) }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(R.color.muted)
        textSize = 11f * scaledDensity
    }
    private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(R.color.ink)
        textSize = 12f * scaledDensity
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val markerPaint = paint(R.color.muted).apply { strokeWidth = dp(0.8f) }
    private val markerHaloPaint = paint(R.color.white).apply { strokeWidth = dp(2.4f) }
    private val pastMarkerColor = color(R.color.muted)
    private val rect = RectF()

    init {
        isClickable = true
        isFocusable = true
    }

    private data class DrawNote(
        val note: MidiNote,
        val interval: NoteInterval,
        val group: Int,
        val step: Int,
        val lane: Int,
        val headShift: Float,
    )

    private data class GroupLabel(
        val lines: List<String>,
        val widths: List<Float>,
        val width: Float,
        val row: Int,
        val hidden: Int,
    )

    private var notes: List<DrawNote> = emptyList()
    private var groups: List<NoteStartGroup> = emptyList()
    private var labels: List<GroupLabel> = emptyList()
    private var noteTreeEnd = DoubleArray(0)
    private var groupEnds = DoubleArray(0)
    private var groupTreeEnd = DoubleArray(0)
    private var visibleNotes = IntArray(0)
    private var visibleCount = 0
    private var visibleGroups = IntArray(0)
    private var groupCount = 0
    private var overflowGroups = IntArray(0)
    private var overflowCount = 0
    private var labelLeft = FloatArray(0)
    private var labelRight = FloatArray(0)
    private var labelTop = FloatArray(0)
    private var labelBottom = FloatArray(0)
    private var labelVisible = BooleanArray(0)
    private var labelRows = 2
    private var rowHeight = 0f
    private var labelBandTop = 0f
    private var overflowLeft = 0f
    private var overflowRight = 0f
    private var overflowBottom = 0f
    private var overflowText = ""
    private var overflowPitchCount = -1
    private var grid: List<MeasureBoundary> = emptyList()
    private var gridNumbers: List<String> = emptyList()
    private var gridMeters: List<String> = emptyList()
    private var gridNumberWidths = FloatArray(0)
    private var gridMeterWidths = FloatArray(0)
    private var gridFrom = Double.POSITIVE_INFINITY
    private var gridTo = Double.NEGATIVE_INFINITY
    private var emptyText = emptyLabel
    private var octaveShift = 0
    private var maxHeadShift = 0f
    private var staffCenter = 0f
    private var lineGap = 0f
    private var headX = 0f
    private var headY = 0f
    private var staffTop = 0f
    private var staffBottom = 0f
    private var captionBottom = 0f

    var score: MidiScore? = null
        set(value) { field = value; rebuildNotes(); invalidate() }
    var quarterBeatPosition: Double = 0.0
        set(value) { field = value; invalidate() }
    var trackSelection: TrackSelection = TrackSelection.All
        set(value) { if (field != value) { field = value; rebuildNotes(); invalidate() } }
    var noteNaming: NoteNaming = NoteNaming.Letters
        set(value) { if (field != value) { field = value; rebuildLabels(); invalidate() } }

    var onPositionScrubStart: ((Double) -> Unit)? = null
    var onPositionScrubChanged: ((Double) -> Unit)? = null
    var onPositionScrubFinished: ((Double) -> Unit)? = null
    var onNoteGroupsRequested: ((List<NoteStartGroup>) -> Unit)? = null

    private var scrubbing = false
    private var scrubStartX = 0f
    private var scrubStartY = 0f
    private var scrubStartPosition = 0.0
    private var movedBeyondSlop = false
    private var pendingTapGroup = -1
    private var pendingOverflowTap = false
    private var pointerClick = false

    private fun pixelsPerQuarterBeat(): Float = max(dp(32f), width.toFloat() / 10f)
    private fun viewport() = BeatViewport(quarterBeatPosition, width * 0.34f,
        pixelsPerQuarterBeat(), 0f, width.toFloat())

    private fun diatonicStepFromB4(pitch: Int): Int {
        val p = pitch.coerceIn(0, 127)
        val letter = when (p % 12) { 0, 1 -> 0; 2, 3 -> 1; 4 -> 2; 5, 6 -> 3; 7, 8 -> 4; 9, 10 -> 5; else -> 6 }
        return (p / 12 - 1) * 7 + letter - 34
    }

    /** All full-score work happens on model/selection changes, never in onDraw. */
    private fun rebuildNotes() {
        val localScore = score
        val selected = localScore?.visibleNotes(trackSelection) ?: emptyList()
        octaveShift = if (selected.isEmpty()) 0 else
            (-selected.sumOf { diatonicStepFromB4(it.pitch).toDouble() } / selected.size / 7.0)
                .roundToInt().coerceIn(-3, 3)
        groups = if (localScore == null) emptyList() else groupNotesByStartTick(localScore, selected)
        val groupIndices = groups.mapIndexed { index, group -> group.startTick to index }.toMap()
        // Minimal stable head-only shifts separate seconds and accidental pitches
        // sharing a diatonic step. Timing anchors and ribbon ends never move.
        val candidates = floatArrayOf(0f, 1.35f, -1.35f, 2.1f, -2.1f, 3.45f, -3.45f)
        val headShifts = groups.map { group ->
            val shifts = LinkedHashMap<Int, Float>()
            for (pitch in group.uniquePitches) {
                val step = diatonicStepFromB4(pitch)
                shifts[pitch] = candidates.firstOrNull { candidate -> shifts.all { (previousPitch, shift) ->
                    val dy = (step - diatonicStepFromB4(previousPitch)) / 1.32f
                    val dx = (candidate - shift) / 2f
                    dx * dx + dy * dy >= 1.02f
                } } ?: candidates.last()
            }
            shifts
        }
        val laneEnds = Array(128) { ArrayList<Double>() }
        notes = if (localScore == null) emptyList() else selected
            .sortedWith(compareBy<MidiNote> { it.startTick }.thenBy { it.endTick }
                .thenBy { it.pitch }.thenBy { it.track }.thenBy { it.channel })
            .map { note ->
                val interval = noteInterval(localScore, note)
                val ends = laneEnds[note.pitch.coerceIn(0, 127)]
                var lane = ends.indexOfFirst { it <= interval.startBeat }
                if (lane < 0) { lane = ends.size; ends.add(interval.endBeat) } else ends[lane] = interval.endBeat
                val groupIndex = groupIndices.getValue(note.startTick)
                DrawNote(note, interval, groupIndex, diatonicStepFromB4(note.pitch) + octaveShift * 7,
                    lane, headShifts[groupIndex].getValue(note.pitch))
            }
        maxHeadShift = notes.maxOfOrNull { abs(it.headShift) } ?: 0f
        noteTreeEnd = DoubleArray(notes.size)
        groupEnds = DoubleArray(groups.size) { index ->
            groups[index].notes.maxOfOrNull { maxOf(it.startTick, it.endTick).toDouble() / (localScore?.ppq ?: 1) }
                ?: groups[index].startBeat
        }
        groupTreeEnd = DoubleArray(groups.size)
        buildNoteTree(0, notes.size)
        buildGroupTree(0, groups.size)
        visibleNotes = IntArray(notes.size)
        visibleGroups = IntArray(groups.size)
        overflowGroups = IntArray(groups.size)
        labelLeft = FloatArray(groups.size); labelRight = FloatArray(groups.size)
        labelTop = FloatArray(groups.size); labelBottom = FloatArray(groups.size)
        labelVisible = BooleanArray(groups.size)
        visibleCount = 0; groupCount = 0; overflowCount = 0
        pendingTapGroup = -1; pendingOverflowTap = false
        grid = emptyList(); gridNumbers = emptyList(); gridMeters = emptyList()
        gridNumberWidths = FloatArray(0); gridMeterWidths = FloatArray(0)
        gridFrom = Double.POSITIVE_INFINITY; gridTo = Double.NEGATIVE_INFINITY
        rebuildLabels()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildLabels()
        gridFrom = Double.POSITIVE_INFINITY; gridTo = Double.NEGATIVE_INFINITY
    }

    /** Label rows are reserved outside note/stem geometry; packing stays stable while scrolling. */
    private fun rebuildLabels() {
        val metrics = namePaint.fontMetrics
        rowHeight = metrics.descent - metrics.ascent + dp(3f)
        labelRows = if (height >= dp(220f) && rowHeight * 3f < height * 0.3f) 3 else 2
        val rowEnds = DoubleArray(labelRows) { Double.NEGATIVE_INFINITY }
        val maxWidth = minOf(dp(190f), width * 0.64f).coerceAtLeast(dp(28f))
        val scale = pixelsPerQuarterBeat()
        labels = groups.map { group ->
            val names = group.uniquePitches.map { ScoreNavigator.pitchName(it, noteNaming) }
            val lines = ArrayList<String>(2)
            var consumed = 0
            repeat(2) {
                var line = ""
                while (consumed < names.size) {
                    val candidate = if (line.isEmpty()) names[consumed] else "$line · ${names[consumed]}"
                    if (namePaint.measureText(candidate) > maxWidth) break
                    line = candidate; consumed++
                }
                if (line.isNotEmpty()) lines.add(line)
            }
            var hidden = names.size - consumed
            if (hidden > 0) {
                if (lines.isEmpty()) lines.add("")
                var last = lines.removeAt(lines.lastIndex)
                while (last.isNotEmpty() && namePaint.measureText("$last +$hidden") > maxWidth) {
                    val split = last.lastIndexOf(" · ")
                    last = if (split < 0) "" else last.substring(0, split)
                    hidden++
                }
                lines.add(if (last.isEmpty()) "+$hidden" else "$last +$hidden")
            }
            if (lines.isEmpty()) lines.add("+${names.size}")
            var widths = lines.map { namePaint.measureText(it) }
            var blockWidth = widths.maxOrNull() ?: 0f
            var left = group.startBeat * scale - blockWidth / 2.0
            fun freeRow(lineCount: Int): Int = (0..(labelRows - lineCount)).firstOrNull { row ->
                (row until row + lineCount).all { rowEnds[it] + dp(5f) <= left }
            } ?: -1
            var row = freeRow(lines.size)
            if (row < 0) {
                hidden = names.size
                lines.clear(); lines.add("+$hidden")
                widths = listOf(namePaint.measureText(lines[0])); blockWidth = widths[0]
                left = group.startBeat * scale - blockWidth / 2.0
                row = freeRow(1)
            }
            if (row >= 0) for (r in row until row + lines.size) rowEnds[r] = left + blockWidth
            GroupLabel(lines, widths, blockWidth, row, hidden)
        }
        emptyText = TextUtils.ellipsize(emptyLabel, textPaint,
            (width - dp(24f)).coerceAtLeast(1f), TextUtils.TruncateAt.END).toString()
        overflowPitchCount = -1
    }

    // Balanced interval trees prune expired history even when one very long note
    // began at the start of the file. Arrays are allocated only when MIDI changes.
    private fun buildNoteTree(low: Int, high: Int): Double {
        if (low >= high) return Double.NEGATIVE_INFINITY
        val mid = (low + high) ushr 1
        val interval = notes[mid].interval
        val end = maxOf(maxOf(interval.startBeat, interval.endBeat),
            buildNoteTree(low, mid), buildNoteTree(mid + 1, high))
        noteTreeEnd[mid] = end
        return end
    }

    private fun buildGroupTree(low: Int, high: Int): Double {
        if (low >= high) return Double.NEGATIVE_INFINITY
        val mid = (low + high) ushr 1
        val end = maxOf(groupEnds[mid], buildGroupTree(low, mid), buildGroupTree(mid + 1, high))
        groupTreeEnd[mid] = end
        return end
    }

    private fun queryNotes(low: Int, high: Int, from: Double, to: Double) {
        if (low >= high) return
        val mid = (low + high) ushr 1
        if (noteTreeEnd[mid] < from) return
        queryNotes(low, mid, from, to)
        val interval = notes[mid].interval
        if (interval.startBeat > to) return
        if (interval.intersects(from, to) || interval.headVisible(from, to)) visibleNotes[visibleCount++] = mid
        queryNotes(mid + 1, high, from, to)
    }

    private fun queryGroups(low: Int, high: Int, from: Double, to: Double) {
        if (low >= high) return
        val mid = (low + high) ushr 1
        if (groupTreeEnd[mid] < from) return
        queryGroups(low, mid, from, to)
        if (groups[mid].startBeat > to) return
        if (groupEnds[mid] >= from) visibleGroups[groupCount++] = mid
        queryGroups(mid + 1, high, from, to)
    }

    private fun selectVisible(from: Double, to: Double) {
        visibleCount = 0; groupCount = 0
        queryNotes(0, notes.size, from, to)
        queryGroups(0, groups.size, from, to)
    }

    private fun ensureGrid(localScore: MidiScore, from: Double, to: Double) {
        if (from >= gridFrom && to <= gridTo) return
        gridFrom = maxOf(0.0, from - 12.0); gridTo = maxOf(gridFrom, to + 12.0)
        grid = ScoreNavigator.measureBoundaries(localScore, gridFrom, gridTo)
        gridNumbers = grid.map { it.bar.toString() }
        gridMeters = grid.map { context.getString(R.string.staff_meter, it.numerator, it.denominator) }
        gridNumberWidths = FloatArray(grid.size) { textPaint.measureText(gridNumbers[it]) }
        gridMeterWidths = FloatArray(grid.size) { textPaint.measureText(gridMeters[it]) }
    }

    private fun laneOffset(lane: Int): Float = when (lane % 5) {
        1 -> 0.11f; 2 -> -0.11f; 3 -> 0.2f; 4 -> -0.2f; else -> 0f
    } * lineGap

    private fun noteY(note: DrawNote): Float = staffCenter - note.step * lineGap / 2f + laneOffset(note.lane)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val viewport = viewport()
        val overscan = max(dp(16f), h * 0.105f * 0.52f * (maxHeadShift + 1f)) / viewport.pixelsPerQuarterBeat.toDouble()
        val range = viewport.visibleBeatRange(overscan)
        selectVisible(range.start, range.endInclusive)
        val captionMetrics = textPaint.fontMetrics
        val captionHeight = captionMetrics.descent - captionMetrics.ascent + dp(3f)
        captionBottom = minOf(captionHeight, h * 0.12f)
        val barBandBottom = minOf(captionBottom + captionHeight, h * 0.25f)
        labelBandTop = maxOf(barBandBottom + dp(12f), h - rowHeight * labelRows - dp(4f))
        val available = (labelBandTop - barBandBottom - dp(6f)).coerceAtLeast(1f)
        var topUnits = 2f; var bottomUnits = 2f
        for (v in 0 until visibleCount) {
            val note = notes[visibleNotes[v]]
            topUnits = max(topUnits, note.step / 2f + 1.7f + 0.55f)
            bottomUnits = max(bottomUnits, -note.step / 2f + 0.55f)
        }
        lineGap = minOf(h * 0.105f, available / (topUnits + bottomUnits))
        staffCenter = barBandBottom + (available - (topUnits + bottomUnits) * lineGap) / 2f + topUnits * lineGap
        headX = lineGap * 0.52f; headY = lineGap * 0.33f
        staffTop = staffCenter - lineGap * 2f; staffBottom = staffCenter + lineGap * 2f
        val localScore = score
        canvas.save()
        canvas.clipRect(0f, barBandBottom, w, labelBandTop)
        // Ribbons use nominal timing coordinates even when a nearby head is offset.
        for (v in 0 until visibleCount) {
            val note = notes[visibleNotes[v]]
            val interval = note.interval
            if (interval.endBeat <= interval.startBeat) continue
            val start = viewport.beatToX(interval.startBeat) + headX
            val end = viewport.beatToX(interval.endBeat)
            if (end <= start || end < 0f || start > w) continue
            val y = noteY(note)
            val halfThickness = lineGap * (if (note.lane == 0) 0.13f else 0.065f)
            val stage = interval.stateAt(quarterBeatPosition)
            val ribbonPaint = when (stage) {
                NoteTimeState.Future -> futureRibbonPaint
                NoteTimeState.Active -> activeRibbonPaint
                NoteTimeState.Past -> pastRibbonPaint
            }
            rect.set(start, y - halfThickness, end, y + halfThickness)
            // Clipping, not a round stroke cap, keeps the right edge at exact Note Off.
            canvas.drawRoundRect(rect, halfThickness, halfThickness, ribbonPaint)
            if (stage == NoteTimeState.Active && start < viewport.cursorX) {
                canvas.save(); canvas.clipRect(0f, barBandBottom, viewport.cursorX, labelBandTop)
                canvas.drawRoundRect(rect, halfThickness, halfThickness, activeElapsedPaint); canvas.restore()
            }
        }
        for (i in -2..2) canvas.drawLine(dp(8f), staffCenter + i * lineGap, w - dp(8f), staffCenter + i * lineGap, linePaint)
        if (localScore != null) {
            val visibleRange = viewport.visibleBeatRange()
            // Negative musical coordinates do not generate negative measure numbers.
            if (visibleRange.endInclusive >= 0.0) ensureGrid(localScore, maxOf(0.0, visibleRange.start), visibleRange.endInclusive)
            for (boundary in grid) {
                val x = viewport.beatToX(boundary.quarterBeat)
                if (x in 0f..w && abs(x - viewport.cursorX) > cursorPaint.strokeWidth)
                    canvas.drawLine(x, staffTop, x, staffBottom, barPaint)
            }
        }
        val stemWidth = max(dp(0.8f), lineGap * 0.055f)
        val stemX = headX * 0.9f
        for (v in 0 until visibleCount) {
            val note = notes[visibleNotes[v]]
            if (!note.interval.headVisible(range.start, range.endInclusive)) continue
            val onset = viewport.beatToX(note.interval.startBeat)
            val x = onset + note.headShift * headX
            val y = noteY(note)
            val paint = when (note.interval.stateAt(quarterBeatPosition)) {
                NoteTimeState.Future -> futurePaint
                NoteTimeState.Active -> activePaint
                NoteTimeState.Past -> pastPaint
            }
            paint.strokeWidth = stemWidth
            if (note.step > 4) for (step in 6..note.step step 2) {
                val ledgerY = staffCenter - step * lineGap / 2f
                canvas.drawLine(x - headX * 1.5f, ledgerY, x + headX * 1.5f, ledgerY, linePaint)
            }
            if (note.step < -4) for (step in -6 downTo note.step step 2) {
                val ledgerY = staffCenter - step * lineGap / 2f
                canvas.drawLine(x - headX * 1.5f, ledgerY, x + headX * 1.5f, ledgerY, linePaint)
            }
            if (note.headShift != 0f) {
                canvas.drawLine(onset, y - headY, onset, y + headY, paint)
                canvas.drawLine(onset, y, x, y, paint)
            }
            rect.set(x - headX, y - headY, x + headX, y + headY)
            canvas.drawOval(rect, paint)
            canvas.drawLine(x + stemX, y, x + stemX, y - lineGap * 1.7f, paint)
        }
        drawNoteOffMarkers(canvas, viewport)
        canvas.restore()

        textPaint.color = color(R.color.muted)
        textPaint.textAlign = Paint.Align.LEFT
        var previousNumberRight = Float.NEGATIVE_INFINITY
        for (index in grid.indices) {
            val boundary = grid[index]
            val x = viewport.beatToX(boundary.quarterBeat)
            if (x < 0f || x > w) continue
            val number = gridNumbers[index]
            val numberWidth = gridNumberWidths[index]
            if (x + dp(3f) >= previousNumberRight && x + dp(3f) + numberWidth <= w) {
                val baseline = captionBottom - captionMetrics.ascent
                canvas.drawText(number, x + dp(3f), baseline, textPaint)
                previousNumberRight = x + dp(6f) + numberWidth
                val changed = index == 0 || boundary.numerator != grid[index - 1].numerator || boundary.denominator != grid[index - 1].denominator
                if (changed) {
                    val meter = gridMeters[index]
                    val nextX = if (index + 1 < grid.size) viewport.beatToX(grid[index + 1].quarterBeat) else w
                    if (previousNumberRight + gridMeterWidths[index] < minOf(nextX - dp(4f), w)) {
                        canvas.drawText(meter, previousNumberRight, baseline, textPaint)
                        previousNumberRight += gridMeterWidths[index] + dp(4f)
                    }
                }
            }
        }
        drawLabels(canvas, viewport)
        canvas.drawLine(viewport.cursorX, captionBottom, viewport.cursorX, labelBandTop, cursorPaint)
        textPaint.color = cursorPaint.color
        canvas.drawText(cursorLabel, viewport.cursorX + dp(4f), -captionMetrics.ascent, textPaint)
        if (octaveShift != 0) {
            textPaint.color = color(R.color.muted)
            canvas.drawText(if (octaveShift < 0) "8va" else "8vb", dp(8f), -captionMetrics.ascent, textPaint)
        }
        if (localScore == null || notes.isEmpty()) {
            textPaint.color = color(R.color.muted); textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(if (localScore == null) emptyText else context.getString(R.string.staff_no_notes),
                w / 2f, h - namePaint.fontMetrics.descent - dp(4f), textPaint)
            textPaint.textAlign = Paint.Align.LEFT
        }
    }

    /**
     * Compact lanes can share Y, but a later ribbon/head must never erase an
     * earlier release. Paint every halo before any endpoint stroke: interleaving
     * halo/stroke per note would recreate the same bug for closely spaced ends.
     */
    private fun drawNoteOffMarkers(canvas: Canvas, viewport: BeatViewport) {
        for (pass in 0..1) {
            for (v in 0 until visibleCount) {
                val note = notes[visibleNotes[v]]
                val interval = note.interval
                if (interval.endBeat <= interval.startBeat) continue
                val ribbonStart = viewport.beatToX(interval.startBeat) + headX
                val end = viewport.beatToX(interval.endBeat)
                // Head-only short events have no artificial ribbon/endpoint.
                // Off-screen real ends stay off-screen; clipping never relocates them.
                if (end <= ribbonStart || end < viewport.leftX || end > viewport.rightX) continue
                val y = noteY(note)
                val halfThickness = lineGap * (if (note.lane == 0) 0.13f else 0.065f)
                val paint = if (pass == 0) markerHaloPaint else markerPaint.apply {
                    color = when (interval.stateAt(quarterBeatPosition)) {
                        NoteTimeState.Active -> activePaint.color
                        NoteTimeState.Future -> futurePaint.color
                        NoteTimeState.Past -> pastMarkerColor
                    }
                }
                canvas.drawLine(end, y - halfThickness - dp(1f), end, y + halfThickness + dp(1f), paint)
            }
        }
    }

    private fun drawLabels(canvas: Canvas, viewport: BeatViewport) {
        overflowCount = 0
        var hiddenPitches = 0
        val metrics = namePaint.fontMetrics
        val width = width.toFloat()
        for (v in 0 until groupCount) {
            val index = visibleGroups[v]
            val group = groups[index]
            val label = labels[index]
            val x = viewport.beatToX(group.startBeat)
            val left = x - label.width / 2f
            val top = labelBandTop + label.row * rowHeight
            val bottom = top + label.lines.size * rowHeight
            val visible = label.row >= 0 && left >= dp(2f) && left + label.width <= width - dp(2f) && bottom <= height
            labelVisible[index] = visible
            if (!visible) {
                overflowGroups[overflowCount++] = index
                hiddenPitches += group.uniquePitches.size
                continue
            }
            labelLeft[index] = left; labelRight[index] = left + label.width
            labelTop[index] = top; labelBottom[index] = bottom
            val active = group.notes.any { score?.let { local ->
                local.noteStartBeat(it) <= quarterBeatPosition && quarterBeatPosition < local.noteEndBeat(it)
            } ?: false }
            namePaint.color = if (active) activePaint.color else color(R.color.ink)
            label.lines.forEachIndexed { row, text ->
                canvas.drawText(text, x - label.widths[row] / 2f, top + row * rowHeight - metrics.ascent, namePaint)
            }
            if (label.row > 0) canvas.drawLine(x, labelBandTop, x, top - dp(2f), barPaint)
        }
        if (overflowCount > 0) {
            if (hiddenPitches != overflowPitchCount) {
                overflowPitchCount = hiddenPitches
                overflowText = context.getString(R.string.staff_overflow, hiddenPitches)
            }
            namePaint.color = color(R.color.accent)
            val textWidth = namePaint.measureText(overflowText)
            overflowRight = width - dp(5f)
            overflowLeft = maxOf(0f, overflowRight - textWidth - dp(8f))
            overflowBottom = captionBottom
            canvas.drawText(overflowText, overflowRight - textWidth, -metrics.ascent, namePaint)
        }
    }

    private fun hitGroup(x: Float, y: Float): Int {
        val viewport = viewport()
        for (v in 0 until groupCount) {
            val index = visibleGroups[v]
            if (labelVisible[index] && x >= labelLeft[index] - dp(6f) && x <= labelRight[index] + dp(6f) &&
                y >= labelTop[index] - dp(4f) && y <= labelBottom[index] + dp(4f)) return index
        }
        var closest = -1
        var distance = Float.POSITIVE_INFINITY
        for (v in 0 until visibleCount) {
            val note = notes[visibleNotes[v]]
            val onset = viewport.beatToX(note.interval.startBeat)
            val end = viewport.beatToX(note.interval.endBeat)
            val visualHead = onset + note.headShift * headX
            val nearHead = abs(x - visualHead) <= max(dp(12f), headX)
            val nearRibbon = note.interval.endBeat > note.interval.startBeat && x >= onset + headX && x <= end
            val dy = abs(y - noteY(note))
            if ((nearHead || nearRibbon) && dy < max(dp(12f), headY) && dy < distance) { closest = note.group; distance = dy }
        }
        return closest
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val localScore = score ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scrubbing = true
                scrubStartX = event.x; scrubStartY = event.y
                scrubStartPosition = quarterBeatPosition
                movedBeyondSlop = false
                pendingOverflowTap = overflowCount > 0 && event.x >= overflowLeft && event.x <= overflowRight && event.y <= overflowBottom + dp(6f)
                pendingTapGroup = if (pendingOverflowTap) -1 else hitGroup(event.x, event.y)
                parent?.requestDisallowInterceptTouchEvent(true)
                onPositionScrubStart?.invoke(scrubStartPosition)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scrubbing) return false
                if (abs(event.x - scrubStartX) > touchSlop || abs(event.y - scrubStartY) > touchSlop) movedBeyondSlop = true
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
                if (abs(event.x - scrubStartX) > touchSlop || abs(event.y - scrubStartY) > touchSlop) movedBeyondSlop = true
                parent?.requestDisallowInterceptTouchEvent(false)
                onPositionScrubFinished?.invoke(quarterBeatPosition)
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    pointerClick = true
                    if (movedBeyondSlop) { pendingTapGroup = -1; pendingOverflowTap = false }
                    performClick()
                    pointerClick = false
                }
                pendingTapGroup = -1; pendingOverflowTap = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        when {
            pendingOverflowTap -> onNoteGroupsRequested?.invoke((0 until overflowCount).map { groups[overflowGroups[it]] })
            pendingTapGroup in groups.indices -> onNoteGroupsRequested?.invoke(listOf(groups[pendingTapGroup]))
            !pointerClick && groupCount > 0 -> onNoteGroupsRequested?.invoke((0 until groupCount).map { groups[visibleGroups[it]] })
        }
        return true
    }
}
