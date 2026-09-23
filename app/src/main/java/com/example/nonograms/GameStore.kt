package com.example.nonograms

import android.content.Context

/** A snapshot of one game in progress, everything needed to redraw it exactly as it was. */
data class GameSnapshot(
    val size: Int,
    val solution: BooleanArray,
    val cells: IntArray,
    val crossMode: Boolean,
    val solved: Boolean,
    val elapsedMs: Long
)

/**
 * Persists the current game so it survives the app being closed or killed by the system.
 * Stored as one delimited string in SharedPreferences — simple, and plenty for a single
 * board's worth of data (at most 625 squares).
 */
object GameStore {
    private const val PREFS_NAME = "nonograms_game_state"
    private const val KEY_SNAPSHOT = "snapshot"
    private const val FIELD_SEP = ':'

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(context: Context, snapshot: GameSnapshot) {
        val solutionBits = CharArray(snapshot.solution.size) { if (snapshot.solution[it]) '1' else '0' }
        val cellDigits = CharArray(snapshot.cells.size) { ('0' + snapshot.cells[it]) }
        val encoded = listOf(
            snapshot.size.toString(),
            String(solutionBits),
            String(cellDigits),
            if (snapshot.crossMode) "1" else "0",
            if (snapshot.solved) "1" else "0",
            snapshot.elapsedMs.toString()
        ).joinToString(FIELD_SEP.toString())
        prefs(context).edit().putString(KEY_SNAPSHOT, encoded).apply()
    }

    /** Returns the saved game, or null if there isn't one or it's unreadable (e.g. after an app update). */
    fun load(context: Context): GameSnapshot? {
        val raw = prefs(context).getString(KEY_SNAPSHOT, null) ?: return null
        return try {
            val parts = raw.split(FIELD_SEP)
            val size = parts[0].toInt()
            val cellCount = size * size
            val solutionStr = parts[1]
            val cellsStr = parts[2]
            if (solutionStr.length != cellCount || cellsStr.length != cellCount) return null
            GameSnapshot(
                size = size,
                solution = BooleanArray(cellCount) { solutionStr[it] == '1' },
                cells = IntArray(cellCount) { cellsStr[it] - '0' },
                crossMode = parts[3] == "1",
                solved = parts[4] == "1",
                elapsedMs = parts[5].toLong()
            )
        } catch (e: Exception) {
            null
        }
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_SNAPSHOT).apply()
    }
}
