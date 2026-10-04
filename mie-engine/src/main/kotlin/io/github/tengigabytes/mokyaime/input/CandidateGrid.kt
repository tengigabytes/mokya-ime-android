// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime.input

/**
 * Rows of the expanded candidate strip. Candidates of varying widths fill
 * rows [rowWidth] pixels wide, in order; a candidate that does not fit in
 * what is left of a row starts the next one.
 *
 * A row that is followed by another is stretched to the full width, its
 * spare pixels shared out evenly, so the cells line up at both edges and
 * are easier to hit. The last row keeps the candidates' own widths. A
 * candidate wider than a row gets a row of its own, cut to [rowWidth].
 */
object CandidateGrid {

    /** Where one candidate goes: its row, and its left edge and width in that row. */
    data class Cell(val row: Int, val left: Int, val width: Int)

    /** One cell per entry of [widths], in the same order. */
    fun layout(widths: List<Int>, rowWidth: Int): List<Cell> {
        val limit = maxOf(rowWidth, 0)
        val cells = ArrayList<Cell>(widths.size)
        var start = 0
        var row = 0
        while (start < widths.size) {
            var end = start
            var used = 0
            while (end < widths.size) {
                val width = widths[end].coerceIn(0, limit)
                if (end > start && used + width > limit) break
                used += width
                end++
            }
            val count = end - start
            val spare = if (end == widths.size) 0 else limit - used
            var left = 0
            for (i in start until end) {
                val extra = spare / count + if (i - start < spare % count) 1 else 0
                val width = widths[i].coerceIn(0, limit) + extra
                cells += Cell(row, left, width)
                left += width
            }
            start = end
            row++
        }
        return cells
    }

    /** Number of rows [cells] take. */
    fun rowCount(cells: List<Cell>): Int = if (cells.isEmpty()) 0 else cells.last().row + 1
}
