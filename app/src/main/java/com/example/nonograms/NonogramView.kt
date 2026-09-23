package com.example.nonograms

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The nonogram board: clues + grid, drawn by hand so a 25x25 board stays smooth.
 *
 * Gestures
 *  - one finger tap / drag ........ apply the current tool (fill, or cross if [crossMode])
 *  - one finger HOLD, then drag ... apply the OTHER tool
 *  - touching a square that already has the tool's mark clears it; dragging from such a
 *    square clears the marked squares it passes over
 *  - two fingers .................. pinch to zoom, drag to pan (handy on 20x20 / 25x25)
 */
class NonogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Called on the UI thread when the board satisfies every row and column clue. */
    var onSolved: (() -> Unit)? = null

    /** false: tap/drag fills, hold+drag crosses. true: tap/drag crosses, hold+drag fills. */
    var crossMode: Boolean = false

    /** When false the board ignores touches (while a puzzle is being generated / after solving). */
    var inputEnabled: Boolean = true

    private enum class Touch { NONE, PENDING, DRAWING, TRANSFORM, IGNORE }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    // ---- puzzle / board state -------------------------------------------------------------
    private var puzzle: Puzzle? = null
    private var n = 0
    private var state = IntArray(0)
    private var rowOk = BooleanArray(0)
    private var colOk = BooleanArray(0)

    // ---- layout (all in view pixels) ------------------------------------------------------
    private var cs = 0f              // square size at zoom 1
    private var gridLeft = 0f        // left edge of the grid viewport
    private var gridTop = 0f         // top edge of the grid viewport
    private var clueTextSize = 0f
    private var clueLineHeight = 0f
    private var clueGap = 0f
    private val clueMargin = dp(4f)

    // ---- zoom / pan -----------------------------------------------------------------------
    private var scale = 1f
    private var maxScale = 1f
    private var panX = 0f            // grid content offset relative to the viewport (<= 0)
    private var panY = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f

    // ---- touch state ----------------------------------------------------------------------
    private var touch = Touch.NONE
    private var downX = 0f
    private var downY = 0f
    private var startCell = -1
    private var lastCell = -1
    private var strokeMark = CellState.FILLED
    private var strokeClears = false
    private var strokeDirty = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val longPressRunnable = Runnable {
        if (touch == Touch.PENDING && startCell >= 0) {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            beginStroke(startCell, useAlternateTool = true)
            commitChanges()
        }
    }

    // ---- paints ---------------------------------------------------------------------------
    private val thinLinePaint = Paint().apply {
        color = 0xFFCFD8DC.toInt(); style = Paint.Style.STROKE; isAntiAlias = false
    }
    private val thickLinePaint = Paint().apply {
        color = 0xFF455A64.toInt(); style = Paint.Style.STROKE; isAntiAlias = false
    }
    private val fillPaint = Paint().apply {
        color = 0xFF263238.toInt(); style = Paint.Style.FILL
    }
    private val crossPaint = Paint().apply {
        color = 0xFFD32F2F.toInt(); style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; isAntiAlias = true
    }
    private val textPaint = Paint().apply { isAntiAlias = true }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                lastFocusX = detector.focusX
                lastFocusY = detector.focusY
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (touch != Touch.TRANSFORM) return true
                // follow the fingers, then zoom around them
                panX += detector.focusX - lastFocusX
                panY += detector.focusY - lastFocusY
                val newScale = (scale * detector.scaleFactor).coerceIn(1f, maxScale)
                val ratio = newScale / scale
                val fx = detector.focusX - gridLeft
                val fy = detector.focusY - gridTop
                panX = fx - (fx - panX) * ratio
                panY = fy - (fy - panY) * ratio
                scale = newScale
                lastFocusX = detector.focusX
                lastFocusY = detector.focusY
                clampPan()
                invalidate()
                return true
            }
        }
    )

    // =========================================================================================
    // Public API
    // =========================================================================================

    fun setPuzzle(p: Puzzle) {
        removeCallbacks(longPressRunnable)
        puzzle = p
        n = p.size
        state = IntArray(n * n)
        rowOk = BooleanArray(n)
        colOk = BooleanArray(n)
        scale = 1f
        panX = 0f
        panY = 0f
        touch = Touch.NONE
        inputEnabled = true
        updateSatisfaction()
        computeLayout()
        invalidate()
    }

    fun clearBoard() {
        removeCallbacks(longPressRunnable)
        touch = Touch.NONE
        state.fill(CellState.EMPTY)
        inputEnabled = true
        updateSatisfaction()
        invalidate()
    }

    /** The puzzle currently loaded, if any — used to persist/restore a game in progress. */
    val currentPuzzle: Puzzle? get() = puzzle

    /** A copy of the current fill/cross marks, in row-major order — safe to persist. */
    val cellStates: IntArray get() = state.copyOf()

    /**
     * Restores previously-saved marks onto the puzzle already loaded via [setPuzzle].
     * [locked] should be true if that saved game was already solved, which keeps the board
     * showing the finished picture without accepting further input.
     */
    fun restoreState(cells: IntArray, locked: Boolean) {
        if (cells.size != state.size) return
        removeCallbacks(longPressRunnable)
        touch = Touch.NONE
        cells.copyInto(state)
        inputEnabled = !locked
        updateSatisfaction()
        invalidate()
    }

    // =========================================================================================
    // Layout
    // =========================================================================================

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeLayout()
    }

    private fun computeLayout() {
        val p = puzzle ?: return
        if (width == 0 || height == 0) return

        val maxColClues = p.colClues.maxOf { max(it.size, 1) }
        var cell = min(width, height) / (n + 5f)
        var clueW = 0f
        var clueH = 0f

        // Clue text size depends on square size and the clue area depends on the text size,
        // so a few passes settle on a consistent layout.
        repeat(4) {
            clueTextSize = (cell * 0.65f).coerceIn(dp(8f), dp(18f))
            textPaint.textSize = clueTextSize
            clueGap = clueTextSize * 0.4f
            clueLineHeight = clueTextSize * 1.2f

            var widest = 0f
            for (clue in p.rowClues) {
                var wsum = 0f
                if (clue.isEmpty()) {
                    wsum = textPaint.measureText("0")
                } else {
                    for (v in clue) wsum += textPaint.measureText(v.toString())
                    wsum += clueGap * (clue.size - 1)
                }
                widest = max(widest, wsum)
            }
            clueW = widest + clueMargin * 2
            clueH = maxColClues * clueLineHeight + clueMargin * 2
            cell = min((width - clueW - clueMargin) / n, (height - clueH - clueMargin) / n)
        }

        cs = max(cell, 1f)
        val boardW = clueW + n * cs
        val boardH = clueH + n * cs
        gridLeft = (width - boardW) / 2f + clueW
        gridTop = max(0f, (height - boardH) / 2f) + clueH
        maxScale = max(1f, dp(44f) / cs)
        scale = scale.coerceIn(1f, maxScale)
        clampPan()
    }

    private fun clampPan() {
        val viewport = n * cs
        val content = viewport * scale
        panX = if (content <= viewport) 0f else panX.coerceIn(viewport - content, 0f)
        panY = if (content <= viewport) 0f else panY.coerceIn(viewport - content, 0f)
    }

    // =========================================================================================
    // Drawing
    // =========================================================================================

    override fun onDraw(canvas: Canvas) {
        val p = puzzle ?: return
        val cellPx = cs * scale
        val viewport = n * cs
        val ox = gridLeft + panX
        val oy = gridTop + panY

        val first = 0
        val last = n - 1
        val c0 = max(first, floor(-panX / cellPx).toInt())
        val c1 = min(last, floor((viewport - panX) / cellPx).toInt())
        val r0 = max(first, floor(-panY / cellPx).toInt())
        val r1 = min(last, floor((viewport - panY) / cellPx).toInt())

        // ---------------- grid area ----------------
        canvas.save()
        canvas.clipRect(gridLeft, gridTop, gridLeft + viewport, gridTop + viewport)

        thinLinePaint.strokeWidth = max(1f, dp(0.5f))
        thickLinePaint.strokeWidth = max(2f, dp(1.5f))

        // thin lines
        for (i in 0..n) {
            if (i % 5 == 0 || i == n) continue
            val x = ox + i * cellPx
            val y = oy + i * cellPx
            canvas.drawLine(x, gridTop, x, gridTop + viewport, thinLinePaint)
            canvas.drawLine(gridLeft, y, gridLeft + viewport, y, thinLinePaint)
        }

        // fills and crosses
        val inset = max(1f, cellPx * 0.05f)
        val crossPad = cellPx * 0.28f
        crossPaint.strokeWidth = max(dp(1.2f), cellPx * 0.09f)
        for (r in r0..r1) {
            for (c in c0..c1) {
                val s = state[r * n + c]
                if (s == CellState.EMPTY) continue
                val l = ox + c * cellPx
                val t = oy + r * cellPx
                if (s == CellState.FILLED) {
                    canvas.drawRect(l + inset, t + inset, l + cellPx - inset, t + cellPx - inset, fillPaint)
                } else {
                    canvas.drawLine(l + crossPad, t + crossPad, l + cellPx - crossPad, t + cellPx - crossPad, crossPaint)
                    canvas.drawLine(l + cellPx - crossPad, t + crossPad, l + crossPad, t + cellPx - crossPad, crossPaint)
                }
            }
        }

        // thick lines every 5 squares + border
        for (i in 0..n) {
            if (!(i % 5 == 0 || i == n)) continue
            val x = ox + i * cellPx
            val y = oy + i * cellPx
            canvas.drawLine(x, gridTop, x, gridTop + viewport, thickLinePaint)
            canvas.drawLine(gridLeft, y, gridLeft + viewport, y, thickLinePaint)
        }
        canvas.restore()

        // ---------------- clues (stay visible while zoomed) ----------------
        textPaint.textSize = clueTextSize
        val fm = textPaint.fontMetrics

        // rows
        canvas.save()
        canvas.clipRect(0f, gridTop, gridLeft, gridTop + viewport)
        textPaint.textAlign = Paint.Align.RIGHT
        val rowBaselineShift = -(fm.ascent + fm.descent) / 2f
        for (r in r0..r1) {
            textPaint.color = if (rowOk[r]) CLUE_DONE else CLUE_TODO
            val baseline = oy + (r + 0.5f) * cellPx + rowBaselineShift
            val clue = p.rowClues[r]
            var x = gridLeft - clueMargin
            if (clue.isEmpty()) {
                canvas.drawText("0", x, baseline, textPaint)
            } else {
                for (idx in clue.indices.reversed()) {
                    val s = clue[idx].toString()
                    canvas.drawText(s, x, baseline, textPaint)
                    x -= textPaint.measureText(s) + clueGap
                }
            }
        }
        canvas.restore()

        // columns
        canvas.save()
        canvas.clipRect(gridLeft, 0f, gridLeft + viewport, gridTop)
        textPaint.textAlign = Paint.Align.CENTER
        for (c in c0..c1) {
            textPaint.color = if (colOk[c]) CLUE_DONE else CLUE_TODO
            val xc = ox + (c + 0.5f) * cellPx
            val clue = p.colClues[c]
            var y = gridTop - clueMargin - fm.descent
            if (clue.isEmpty()) {
                canvas.drawText("0", xc, y, textPaint)
            } else {
                for (idx in clue.indices.reversed()) {
                    canvas.drawText(clue[idx].toString(), xc, y, textPaint)
                    y -= clueLineHeight
                }
            }
        }
        canvas.restore()
    }

    // =========================================================================================
    // Touch handling
    // =========================================================================================

    /** Index (row * n + col) of the square under the point, or -1 if outside the grid. */
    private fun cellAt(x: Float, y: Float): Int {
        val viewport = n * cs
        if (x < gridLeft || y < gridTop || x >= gridLeft + viewport || y >= gridTop + viewport) return -1
        val cellPx = cs * scale
        val c = floor((x - gridLeft - panX) / cellPx).toInt()
        val r = floor((y - gridTop - panY) / cellPx).toInt()
        if (c !in 0 until n || r !in 0 until n) return -1
        return r * n + c
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (puzzle == null) return false
        scaleDetector.onTouchEvent(ev)

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                removeCallbacks(longPressRunnable)
                startCell = -1
                lastCell = -1
                touch = Touch.NONE
                if (inputEnabled) {
                    val cell = cellAt(ev.x, ev.y)
                    if (cell >= 0) {
                        downX = ev.x
                        downY = ev.y
                        startCell = cell
                        touch = Touch.PENDING
                        postDelayed(longPressRunnable, LONG_PRESS_MS)
                    }
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // a second finger means zoom/pan: keep whatever was painted, stop painting
                removeCallbacks(longPressRunnable)
                if (touch == Touch.DRAWING) endStroke()
                touch = Touch.TRANSFORM
                lastFocusX = focusX(ev)
                lastFocusY = focusY(ev)
            }

            MotionEvent.ACTION_MOVE -> when (touch) {
                Touch.PENDING -> {
                    if (hypot(ev.x - downX, ev.y - downY) > touchSlop) {
                        removeCallbacks(longPressRunnable)
                        beginStroke(startCell, useAlternateTool = false)
                        continueStroke(ev.x, ev.y)
                    }
                }
                Touch.DRAWING -> continueStroke(ev.x, ev.y)
                Touch.TRANSFORM -> {
                    if (!scaleDetector.isInProgress) {
                        val fx = focusX(ev)
                        val fy = focusY(ev)
                        panX += fx - lastFocusX
                        panY += fy - lastFocusY
                        lastFocusX = fx
                        lastFocusY = fy
                        clampPan()
                        invalidate()
                    }
                }
                else -> Unit
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (touch == Touch.TRANSFORM) touch = Touch.IGNORE
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                when (touch) {
                    Touch.PENDING -> {            // a plain tap
                        beginStroke(startCell, useAlternateTool = false)
                        endStroke()
                    }
                    Touch.DRAWING -> endStroke()
                    else -> Unit
                }
                touch = Touch.NONE
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                if (touch == Touch.DRAWING) endStroke()
                touch = Touch.NONE
            }
        }
        return true
    }

    private fun focusX(ev: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until ev.pointerCount) sum += ev.getX(i)
        return sum / ev.pointerCount
    }

    private fun focusY(ev: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until ev.pointerCount) sum += ev.getY(i)
        return sum / ev.pointerCount
    }

    // =========================================================================================
    // Painting strokes
    // =========================================================================================

    /**
     * Starts a stroke on [cell]. The first square decides what the whole stroke does:
     * if it already carries the tool's mark the stroke CLEARS marks, otherwise it PLACES marks.
     */
    private fun beginStroke(cell: Int, useAlternateTool: Boolean) {
        val defaultMark = if (crossMode) CellState.CROSSED else CellState.FILLED
        strokeMark = if (useAlternateTool) {
            if (defaultMark == CellState.FILLED) CellState.CROSSED else CellState.FILLED
        } else {
            defaultMark
        }
        strokeClears = state[cell] == strokeMark
        strokeDirty = false
        touch = Touch.DRAWING
        applyCell(cell, isFirst = true)
        lastCell = cell
    }

    private fun continueStroke(x: Float, y: Float) {
        val cell = cellAt(x, y)
        if (cell < 0 || cell == lastCell) return
        applyLine(lastCell, cell)
        lastCell = cell
        commitChanges()
    }

    private fun applyCell(idx: Int, isFirst: Boolean) {
        val current = state[idx]
        val updated = when {
            strokeClears -> if (current == strokeMark) CellState.EMPTY else current
            isFirst -> strokeMark                                   // tap always sets
            current == CellState.EMPTY -> strokeMark                // drag only paints blanks
            else -> current
        }
        if (updated != current) {
            state[idx] = updated
            strokeDirty = true
        }
    }

    /** Applies the stroke to every square on the straight line from [from] to [to] (exclusive of [from]). */
    private fun applyLine(from: Int, to: Int) {
        var r = from / n
        var c = from % n
        val r1 = to / n
        val c1 = to % n
        val dr = abs(r1 - r)
        val dc = abs(c1 - c)
        val sr = if (r < r1) 1 else -1
        val sc = if (c < c1) 1 else -1
        var err = dc - dr
        while (r != r1 || c != c1) {
            val e2 = 2 * err
            if (e2 > -dr) { err -= dr; c += sc }
            if (e2 < dc) { err += dc; r += sr }
            applyCell(r * n + c, isFirst = false)
        }
    }

    private fun endStroke() {
        commitChanges()
        if (strokeDirty) {
            strokeDirty = false
            if (rowOk.all { it } && colOk.all { it }) finishSolved()
        }
    }

    private fun commitChanges() {
        if (strokeDirty) updateSatisfaction()
        invalidate()
    }

    private fun finishSolved() {
        // tidy up: show just the picture
        for (i in state.indices) if (state[i] == CellState.CROSSED) state[i] = CellState.EMPTY
        inputEnabled = false
        invalidate()
        onSolved?.invoke()
    }

    /** Recomputes which rows/columns currently match their clues (used to grey out done clues and to detect a win). */
    private fun updateSatisfaction() {
        val p = puzzle ?: return
        for (r in 0 until n) {
            rowOk[r] = computeClues(n) { i -> state[r * n + i] == CellState.FILLED }.contentEquals(p.rowClues[r])
        }
        for (c in 0 until n) {
            colOk[c] = computeClues(n) { i -> state[i * n + c] == CellState.FILLED }.contentEquals(p.colClues[c])
        }
    }

    private companion object {
        const val LONG_PRESS_MS = 350L
        const val CLUE_TODO = 0xFF212121.toInt()
        const val CLUE_DONE = 0xFFB0BEC5.toInt()
    }
}