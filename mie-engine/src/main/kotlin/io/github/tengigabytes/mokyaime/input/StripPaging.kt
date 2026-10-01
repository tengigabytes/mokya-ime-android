// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime.input

/**
 * Page scrolling for the candidate strip's ‹ › buttons. The strip is one
 * horizontal row of candidates of varying widths; a page is what fits in the
 * visible part ([viewport] pixels wide).
 *
 * Pages start at a candidate's left edge, so the candidate that was cut off
 * at the right becomes the first one of the next page, in full.
 * [starts] holds each candidate's left edge, in order; the row is
 * [contentWidth] wide and the last candidate ends there. All results are
 * scroll positions clamped to the scrollable range.
 */
object StripPaging {

    /** Scroll position of the next page. */
    fun next(starts: List<Int>, contentWidth: Int, scrollX: Int, viewport: Int): Int {
        val max = maxScroll(contentWidth, viewport)
        val visibleEnd = scrollX + viewport
        val cut = starts.indices.firstOrNull { end(starts, contentWidth, it) > visibleEnd } ?: return max
        // A candidate wider than the viewport: move on by a full viewport.
        val target = if (starts[cut] > scrollX) starts[cut] else visibleEnd
        return target.coerceIn(0, max)
    }

    /** Scroll position of the previous page. */
    fun previous(starts: List<Int>, contentWidth: Int, scrollX: Int, viewport: Int): Int {
        val target = scrollX - viewport
        if (target <= 0) return 0
        // The first candidate that fits entirely before the current page start.
        val first = starts.firstOrNull { it >= target } ?: target
        return (if (first < scrollX) first else target).coerceIn(0, maxScroll(contentWidth, viewport))
    }

    fun canPageBack(scrollX: Int): Boolean = scrollX > 0

    fun canPageForward(contentWidth: Int, scrollX: Int, viewport: Int): Boolean =
        scrollX < maxScroll(contentWidth, viewport)

    private fun maxScroll(contentWidth: Int, viewport: Int) = maxOf(0, contentWidth - viewport)

    private fun end(starts: List<Int>, contentWidth: Int, index: Int) =
        if (index + 1 < starts.size) starts[index + 1] else contentWidth
}
