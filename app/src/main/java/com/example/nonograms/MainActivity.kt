package com.example.nonograms

import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    private lateinit var board: NonogramView
    private lateinit var tvStatus: TextView
    private lateinit var tvTimer: TextView
    private lateinit var tvBest: TextView
    private lateinit var btnMode: MaterialButton
    private lateinit var btnClear: MaterialButton

    private val sizes = intArrayOf(5, 10, 15, 20, 25)
    private var currentSize = 10
    private var solved = false

    /** Bumped for every new game so a slow generator thread can't overwrite a newer puzzle. */
    private var generation = 0

    // ---- timer --------------------------------------------------------------------------
    private val timerHandler = Handler(Looper.getMainLooper())
    private var timerRunning = false
    private var timerStartRealtime = 0L   // elapsedRealtime() when the current run began
    private var timerAccumulatedMs = 0L   // time banked from earlier runs of this game
    private val timerTick = object : Runnable {
        override fun run() {
            updateTimerText()
            timerHandler.postDelayed(this, 200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        board = findViewById(R.id.board)
        tvStatus = findViewById(R.id.tvStatus)
        tvTimer = findViewById(R.id.tvTimer)
        tvBest = findViewById(R.id.tvBest)
        btnMode = findViewById(R.id.btnMode)
        btnClear = findViewById(R.id.btnClear)

        board.onSolved = {
            solved = true
            pauseTimer()
            btnClear.isEnabled = false
            val isNewBest = BestTimes.submit(this, currentSize, elapsedMs())
            updateStatus()
            updateBestLabel()
            showSolvedDialog(isNewBest)
        }

        findViewById<MaterialButton>(R.id.btnNew).setOnClickListener { showSizeDialog() }
        tvStatus.setOnClickListener { showSizeDialog() }
        btnClear.setOnClickListener { if (!solved) confirmClear() }
        btnMode.setOnClickListener {
            board.crossMode = !board.crossMode
            updateModeButton()
        }

        updateModeButton()
        if (!restoreSavedGame()) startGame(currentSize)
    }

    /** Restores a previously in-progress game, if one was saved. Returns true if it did. */
    private fun restoreSavedGame(): Boolean {
        val snapshot = GameStore.load(this) ?: return false
        generation++   // invalidate any in-flight generator thread from a previous instance
        currentSize = snapshot.size
        solved = snapshot.solved
        board.crossMode = snapshot.crossMode
        board.setPuzzle(Puzzle(snapshot.size, snapshot.solution))
        board.restoreState(snapshot.cells, locked = snapshot.solved)
        btnClear.isEnabled = !solved
        timerAccumulatedMs = snapshot.elapsedMs
        updateTimerText()
        updateModeButton()
        updateStatus()
        updateBestLabel()
        return true
    }

    // ---------------------------------------------------------------------------------------

    private fun startGame(size: Int) {
        currentSize = size
        solved = false
        resetTimer()
        val token = ++generation
        tvStatus.text = getString(R.string.generating)
        board.inputEnabled = false
        btnClear.isEnabled = false
        updateBestLabel()

        // Generation is quick (tens of ms), but keep it off the UI thread anyway.
        Thread {
            val puzzle = PuzzleGenerator.generate(size)
            runOnUiThread {
                if (!isFinishing && !isDestroyed && token == generation) {
                    board.setPuzzle(puzzle)
                    btnClear.isEnabled = true
                    updateStatus()
                    startTimer()
                }
            }
        }.start()
    }

    // ---- timer ------------------------------------------------------------------------------

    private fun startTimer() {
        if (timerRunning || solved) return
        timerRunning = true
        timerStartRealtime = SystemClock.elapsedRealtime()
        timerHandler.post(timerTick)
    }

    private fun pauseTimer() {
        if (!timerRunning) return
        timerAccumulatedMs += SystemClock.elapsedRealtime() - timerStartRealtime
        timerRunning = false
        timerHandler.removeCallbacks(timerTick)
        updateTimerText()
    }

    private fun resetTimer() {
        pauseTimer()
        timerAccumulatedMs = 0L
        updateTimerText()
    }

    private fun elapsedMs(): Long =
        timerAccumulatedMs + if (timerRunning) SystemClock.elapsedRealtime() - timerStartRealtime else 0L

    private fun updateTimerText() {
        tvTimer.text = formatTime(elapsedMs())
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }

    override fun onPause() {
        super.onPause()
        pauseTimer()
        saveCurrentGame()
    }

    override fun onResume() {
        super.onResume()
        if (!solved && board.inputEnabled) startTimer()
    }

    private fun saveCurrentGame() {
        val puzzle = board.currentPuzzle ?: return   // still generating: leave the last saved game alone
        GameStore.save(
            this,
            GameSnapshot(
                size = puzzle.size,
                solution = puzzle.solution,
                cells = board.cellStates,
                crossMode = board.crossMode,
                solved = solved,
                elapsedMs = elapsedMs()
            )
        )
    }

    private fun updateStatus() {
        val label = getString(R.string.size_label, currentSize, currentSize)
        tvStatus.text = if (solved) getString(R.string.solved_status, label) else label
    }

    private fun updateBestLabel() {
        val best = BestTimes.get(this, currentSize)
        tvBest.text = if (best != null) getString(R.string.best_time, formatTime(best))
        else getString(R.string.best_time_none)
    }

    private fun updateModeButton() {
        if (board.crossMode) {
            btnMode.text = getString(R.string.mode_cross)
            btnMode.backgroundTintList = ColorStateList.valueOf(0xFFD32F2F.toInt())
        } else {
            btnMode.text = getString(R.string.mode_fill)
            btnMode.backgroundTintList = ColorStateList.valueOf(0xFF263238.toInt())
        }
    }

    private fun showSizeDialog() {
        val labels = Array(sizes.size) { i ->
            val size = sizes[i]
            val best = BestTimes.get(this, size)
            val raw = if (best != null) getString(R.string.size_label_with_best, size, size, formatTime(best))
            else getString(R.string.size_label_with_best_none, size, size)
            // Single-digit sizes ("5") are one character narrower than the rest ("10".."25"),
            // so their em dash lands slightly left of the others. A "figure space" is a
            // Unicode space defined to match a digit's width in fonts that support it, so
            // prefixing one nudges just the TEXT right. Unlike padding the row itself, this
            // leaves the radio button (which is positioned by the row's own fixed padding,
            // not by the text) exactly where it is on every row.
            if (size < 10) "\u2007$raw" else raw
        }
        // Uses the app's dialog_size_item layout (checkmark at the start) so every row's
        // radio button lines up in a column regardless of the label text above.
        val adapter = ArrayAdapter(this, R.layout.dialog_size_item, android.R.id.text1, labels)
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_size)
            .setSingleChoiceItems(adapter, sizes.indexOf(currentSize)) { dialog, which ->
                dialog.dismiss()
                startGame(sizes[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_title)
            .setMessage(R.string.clear_message)
            .setPositiveButton(R.string.clear) { _, _ ->
                if (!solved) {
                    board.clearBoard()
                    updateStatus()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showSolvedDialog(isNewBest: Boolean) {
        val message = getString(R.string.solved_message, currentSize, currentSize, formatTime(elapsedMs())) +
                if (isNewBest) "\n\n" + getString(R.string.new_best) else ""
        AlertDialog.Builder(this)
            .setTitle(R.string.solved_title)
            .setMessage(message)
            .setPositiveButton(R.string.new_game) { _, _ -> showSizeDialog() }
            .setNegativeButton(R.string.close, null)
            .show()
    }
}