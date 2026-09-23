package com.example.nonograms

import android.content.Context

/** Persists the fastest solve time (in ms) achieved for each board size. */
object BestTimes {
    private const val PREFS_NAME = "nonograms_best_times"
    private const val KEY_PREFIX = "best_"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The best time for [size], or null if that size has never been completed. */
    fun get(context: Context, size: Int): Long? {
        val v = prefs(context).getLong(KEY_PREFIX + size, -1L)
        return if (v < 0) null else v
    }

    /**
     * Records [timeMs] as the result for [size] if it beats (or is the first) recorded time.
     * Returns true if this was a new best.
     */
    fun submit(context: Context, size: Int, timeMs: Long): Boolean {
        val current = get(context, size)
        if (current == null || timeMs < current) {
            prefs(context).edit().putLong(KEY_PREFIX + size, timeMs).apply()
            return true
        }
        return false
    }
}
