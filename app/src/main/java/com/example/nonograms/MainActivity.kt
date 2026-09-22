package com.example.nonograms

import android.content.res.ColorStateList
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    private lateinit var board: NonogramView
    private lateinit var tvStatus: TextView
    private lateinit var btnMode: MaterialButton

    private val sizes = intArrayOf(5, 10, 15, 20, 25)
    private var currentSize = 10
    private var solved = false

    /** Bumped for every new game so a slow generator thread can't overwrite a newer puzzle. */
    private var generation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        board = findViewById(R.id.board)
        tvStatus = findViewById(R.id.tvStatus)
        btnMode = findViewById(R.id.btnMode)

        board.onSolved = {
            solved = true
            updateStatus()
            showSolvedDialog()
        }

        findViewById<MaterialButton>(R.id.btnNew).setOnClickListener { showSizeDialog() }
        findViewById<MaterialButton>(R.id.btnClear).setOnClickListener { confirmClear() }
        btnMode.setOnClickListener {
            board.crossMode = !board.crossMode
            updateModeButton()
        }

        updateModeButton()
        startGame(currentSize)
    }

    // ---------------------------------------------------------------------------------------

    private fun startGame(size: Int) {
        currentSize = size
        solved = false
        val token = ++generation
        tvStatus.text = getString(R.string.generating)
        board.inputEnabled = false

        // Generation is quick (tens of ms), but keep it off the UI thread anyway.
        Thread {
            val puzzle = PuzzleGenerator.generate(size)
            runOnUiThread {
                if (!isFinishing && !isDestroyed && token == generation) {
                    board.setPuzzle(puzzle)
                    updateStatus()
                }
            }
        }.start()
    }

    private fun updateStatus() {
        val label = getString(R.string.size_label, currentSize, currentSize)
        tvStatus.text = if (solved) getString(R.string.solved_status, label) else label
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
        val labels = Array(sizes.size) { getString(R.string.size_label, sizes[it], sizes[it]) }
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_size)
            .setSingleChoiceItems(labels, sizes.indexOf(currentSize)) { dialog, which ->
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
                solved = false
                board.clearBoard()
                updateStatus()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showSolvedDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.solved_title)
            .setMessage(getString(R.string.solved_message, currentSize, currentSize))
            .setPositiveButton(R.string.new_game) { _, _ -> showSizeDialog() }
            .setNegativeButton(R.string.close, null)
            .show()
    }
}
