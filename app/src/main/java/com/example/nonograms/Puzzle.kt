package com.example.nonograms

import kotlin.random.Random

/** Per-square states used by the board. */
object CellState {
    const val EMPTY = 0
    const val FILLED = 1
    const val CROSSED = 2
}

/**
 * Returns the run-length clues for a line of [length] squares.
 * A line with nothing filled returns an empty array (the UI shows that as "0").
 */
fun computeClues(length: Int, isFilled: (Int) -> Boolean): IntArray {
    val out = ArrayList<Int>()
    var run = 0
    for (i in 0 until length) {
        if (isFilled(i)) {
            run++
        } else if (run > 0) {
            out.add(run)
            run = 0
        }
    }
    if (run > 0) out.add(run)
    return out.toIntArray()
}

/** A square puzzle: the hidden picture plus the row/column clues derived from it. */
class Puzzle(val size: Int, val solution: BooleanArray) {
    val rowClues: Array<IntArray> =
        Array(size) { r -> computeClues(size) { i -> solution[r * size + i] } }
    val colClues: Array<IntArray> =
        Array(size) { c -> computeClues(size) { i -> solution[i * size + c] } }
}

/**
 * A classic "line solver": repeatedly works out, for a single row or column, which squares
 * must be filled / must be blank in EVERY arrangement that still fits the clues and the
 * squares already known. If this alone can finish a puzzle, the puzzle can be solved by
 * pure logic (no guessing), which is what makes a nonogram feel fair.
 */
object LineSolver {
    private const val UNKNOWN = 0
    private const val FILLED = 1
    private const val BLANK = 2

    /**
     * Returns how many squares line-logic could NOT determine (0 = fully logic-solvable),
     * or -1 if the clues turn out to be contradictory (never happens for generated puzzles).
     */
    fun unresolvedCells(p: Puzzle): Int {
        val n = p.size
        val grid = IntArray(n * n)
        val rowDirty = BooleanArray(n) { true }
        val colDirty = BooleanArray(n) { true }
        val line = IntArray(n)

        var progress = true
        while (progress) {
            progress = false
            for (r in 0 until n) {
                if (!rowDirty[r]) continue
                rowDirty[r] = false
                for (c in 0 until n) line[c] = grid[r * n + c]
                if (!solveLine(line, p.rowClues[r])) return -1
                for (c in 0 until n) {
                    if (line[c] != grid[r * n + c]) {
                        grid[r * n + c] = line[c]
                        colDirty[c] = true
                        progress = true
                    }
                }
            }
            for (c in 0 until n) {
                if (!colDirty[c]) continue
                colDirty[c] = false
                for (r in 0 until n) line[r] = grid[r * n + c]
                if (!solveLine(line, p.colClues[c])) return -1
                for (r in 0 until n) {
                    if (line[r] != grid[r * n + c]) {
                        grid[r * n + c] = line[r]
                        rowDirty[r] = true
                        progress = true
                    }
                }
            }
        }
        var unknown = 0
        for (v in grid) if (v == UNKNOWN) unknown++
        return unknown
    }

    /**
     * Fills in every square of [line] that is forced by [clues]. Returns false on contradiction.
     *
     * Dynamic programming over (number of clue blocks placed, next free position):
     *  g[j][i] = the first j blocks can be placed so that squares before i are decided
     *            and a block may start at i
     *  h[j][i] = from that state the rest of the line can be completed
     */
    private fun solveLine(line: IntArray, clues: IntArray): Boolean {
        val n = line.size
        val k = clues.size
        val w = n + 1
        val g = BooleanArray((k + 1) * w)
        val h = BooleanArray((k + 1) * w)

        // run[i] = number of consecutive squares starting at i that are not known-blank
        val run = IntArray(n + 1)
        for (i in n - 1 downTo 0) run[i] = if (line[i] != BLANK) run[i + 1] + 1 else 0

        fun canBlock(i: Int, len: Int): Boolean =
            i + len <= n && run[i] >= len && (i + len == n || line[i + len] != FILLED)

        fun target(i: Int, len: Int): Int = if (i + len == n) n else i + len + 1

        g[0] = true
        for (i in 0..n) {
            for (j in 0..k) {
                if (!g[j * w + i]) continue
                if (i < n && line[i] != FILLED) g[j * w + i + 1] = true
                if (j < k && canBlock(i, clues[j])) g[(j + 1) * w + target(i, clues[j])] = true
            }
        }

        h[k * w + n] = true
        for (i in n downTo 0) {
            for (j in k downTo 0) {
                if (j == k && i == n) continue
                var ok = false
                if (i < n && line[i] != FILLED && h[j * w + i + 1]) ok = true
                if (!ok && j < k && canBlock(i, clues[j]) && h[(j + 1) * w + target(i, clues[j])]) ok = true
                h[j * w + i] = ok
            }
        }
        if (!h[0]) return false

        val canFill = BooleanArray(n)
        val canBlank = BooleanArray(n)
        for (i in 0 until n) {
            for (j in 0..k) {
                if (!g[j * w + i]) continue
                if (line[i] != FILLED && h[j * w + i + 1]) canBlank[i] = true
                if (j < k) {
                    val len = clues[j]
                    if (canBlock(i, len) && h[(j + 1) * w + target(i, len)]) {
                        for (x in i until i + len) canFill[x] = true
                        if (i + len < n) canBlank[i + len] = true
                    }
                }
            }
        }

        for (x in 0 until n) {
            if (line[x] != UNKNOWN) continue
            if (canFill[x] && !canBlank[x]) line[x] = FILLED
            else if (!canFill[x] && canBlank[x]) line[x] = BLANK
            else if (!canFill[x] && !canBlank[x]) return false
        }
        return true
    }
}

object PuzzleGenerator {
    private const val MAX_ATTEMPTS = 150

    /**
     * Makes a random picture and keeps it only if it can be solved by line logic alone
     * (so no guessing is needed). If no such picture turns up, the closest one is used.
     * Winning is always checked against the clues, so any valid solution counts.
     */
    fun generate(size: Int, rng: Random = Random.Default): Puzzle {
        var best: Puzzle? = null
        var bestUnresolved = Int.MAX_VALUE
        for (attempt in 0 until MAX_ATTEMPTS) {
            val density = 0.58 + rng.nextDouble() * 0.10
            val solution = BooleanArray(size * size) { rng.nextDouble() < density }
            val puzzle = Puzzle(size, solution)
            val unresolved = LineSolver.unresolvedCells(puzzle)
            if (unresolved == 0) return puzzle
            if (unresolved in 0 until bestUnresolved) {
                best = puzzle
                bestUnresolved = unresolved
            }
        }
        return best ?: Puzzle(size, BooleanArray(size * size) { rng.nextBoolean() })
    }
}
