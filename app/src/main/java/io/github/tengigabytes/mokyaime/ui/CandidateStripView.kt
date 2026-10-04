// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime.ui

import android.content.Context
import android.graphics.PointF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.tengigabytes.mokyaime.MokyaImeService
import io.github.tengigabytes.mokyaime.R
import io.github.tengigabytes.mokyaime.input.CandidateGrid

/**
 * The strip above the keyboard: `[mode] candidate candidate … ▲`. Tapping a
 * candidate commits it. The row can be swiped; ▲ expands it upwards into
 * several rows holding every candidate ([CandidateGrid]), which close again
 * once one is picked or the list changes. While the SYM1 picker is open the
 * strip lists the picker's symbols instead.
 */
class CandidateStripView(context: Context) : LinearLayout(context) {

    /**
     * What the strip shows. [selected] is -1 when nothing is selectable.
     * The mode is left out ([modeVisible]) while the on-screen keyboard,
     * whose MODE key shows it too, is up.
     */
    data class State(
        val modeIndicator: String,
        val modeVisible: Boolean,
        val items: List<String>,
        val selected: Int,
        val picker: Boolean,
    )

    var onItemTapped: (index: Int) -> Unit = {}
    var onModeTapped: () -> Unit = {}

    private val density = resources.displayMetrics.density
    private val textColor = context.getColor(R.color.strip_text)
    private val selectedBackground = context.getColor(R.color.strip_selected_bg)
    private val barHeight = (44 * density).toInt()

    private val modeView = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(textColor)
        minWidth = (48 * density).toInt()
        setOnClickListener { onModeTapped() }
    }
    private val itemsRow = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val scroller = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(itemsRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }
    private val grid = GridView(context, barHeight)
    private var maxGridHeight = MAX_GRID_ROWS * barHeight
    private val gridScroller = object : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxGridHeight, MeasureSpec.AT_MOST))
    }.apply {
        visibility = GONE
        addView(grid, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
    }
    private val expandButton = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(textColor)
        setOnClickListener { setExpanded(!expanded) }
    }

    private var shownItems: List<String> = emptyList()
    private var shownSelected = -1
    private var expanded = false

    /**
     * How far the expanded rows reach above the strip's own row, in pixels.
     * They float over the app: its content keeps ending at the row.
     */
    val expandedExtraHeight: Int get() = if (expanded) maxOf(0, height - barHeight) else 0

    init {
        orientation = HORIZONTAL
        gravity = Gravity.BOTTOM
        isBaselineAligned = false
        setBackgroundColor(context.getColor(R.color.strip_bg))
        minimumHeight = barHeight
        val content = FrameLayout(context).apply {
            addView(scroller, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, barHeight))
            addView(gridScroller, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        addView(modeView, LayoutParams(LayoutParams.WRAP_CONTENT, barHeight))
        addView(content, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(expandButton, LayoutParams((40 * density).toInt(), barHeight))
        scroller.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateExpandButton() }
        itemsRow.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateExpandButton() }
        updateExpandButton()
    }

    fun show(state: State) {
        modeView.text = state.modeIndicator
        modeView.visibility = if (state.modeVisible) VISIBLE else GONE
        if (state.items != shownItems) {
            shownItems = state.items
            shownSelected = -1
            setExpanded(false)
            itemsRow.removeAllViews()
            state.items.forEachIndexed { index, text -> itemsRow.addView(itemView(index, text, inGrid = false)) }
            scroller.scrollTo(0, 0)
            updateExpandButton()
            MokyaImeService.trace { "strip shows ${state.items.size} items" }
        }
        if (state.selected != shownSelected) {
            shownSelected = state.selected
            styleSelection(itemsRow)
            styleSelection(grid)
            if (expanded) {
                grid.getChildAt(state.selected)?.let { child -> gridScroller.post { scrollIntoViewVertically(child) } }
            } else {
                itemsRow.getChildAt(state.selected)?.let { child -> scroller.post { scrollIntoView(child) } }
            }
        }
    }

    /** Back to the single row, e.g. when the keyboard is put away. */
    fun collapse() = setExpanded(false)

    private fun setExpanded(expand: Boolean) {
        if (expand == expanded) return
        expanded = expand
        grid.removeAllViews()
        if (expand) {
            // Leave most of the app visible, also in landscape.
            val rows = (resources.displayMetrics.heightPixels * MAX_GRID_SCREEN_SHARE / barHeight).toInt()
            maxGridHeight = rows.coerceIn(MIN_GRID_ROWS, MAX_GRID_ROWS) * barHeight
            shownItems.forEachIndexed { index, text -> grid.addView(itemView(index, text, inGrid = true)) }
            styleSelection(grid)
            gridScroller.scrollTo(0, 0)
        }
        scroller.visibility = if (expand) GONE else VISIBLE
        gridScroller.visibility = if (expand) VISIBLE else GONE
        updateExpandButton()
        MokyaImeService.trace { "strip expanded=$expand items=${shownItems.size}" }
    }

    /** Shown while there is more than the row holds, or rows to close. */
    private fun updateExpandButton() {
        val overflows = itemsRow.childCount > 0 && itemsRow.width > scroller.width
        expandButton.visibility = if (expanded || overflows) VISIBLE else INVISIBLE
        expandButton.text = if (expanded) "▼" else "▲"
        expandButton.contentDescription =
            context.getString(if (expanded) R.string.candidates_collapse else R.string.candidates_expand)
    }

    private fun itemView(index: Int, text: String, inGrid: Boolean) = TextView(context).apply {
        this.text = text
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        setTextColor(textColor)
        val padding = (12 * density).toInt()
        setPadding(padding, 0, padding, 0)
        if (inGrid) {
            minWidth = (48 * density).toInt()
            setOnClickListener {
                onItemTapped(index)
                setExpanded(false)
            }
        } else {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
            setOnClickListener { onItemTapped(index) }
        }
        styleItem(this, false)
    }

    private fun styleSelection(items: ViewGroup) {
        (0 until items.childCount).forEach { i -> styleItem(items.getChildAt(i) as TextView, i == shownSelected) }
    }

    private fun styleItem(view: TextView, selected: Boolean) {
        view.background = if (selected) {
            GradientDrawable().apply {
                cornerRadius = 6 * density
                setColor(selectedBackground)
            }
        } else {
            null
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        MokyaImeService.trace { "strip touch action=${ev.actionMasked} (${ev.x}, ${ev.y})" }
        return super.dispatchTouchEvent(ev)
    }

    /** The expanded candidates: rows of [rowHeight], laid out by [CandidateGrid]. */
    private class GridView(context: Context, private val rowHeight: Int) : ViewGroup(context) {

        private var cells: List<CandidateGrid.Cell> = emptyList()

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val anyWidth = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            val row = MeasureSpec.makeMeasureSpec(rowHeight, MeasureSpec.EXACTLY)
            val widths = (0 until childCount).map { i ->
                getChildAt(i).run {
                    measure(anyWidth, row)
                    measuredWidth
                }
            }
            cells = CandidateGrid.layout(widths, width)
            cells.forEachIndexed { i, cell ->
                getChildAt(i).measure(MeasureSpec.makeMeasureSpec(cell.width, MeasureSpec.EXACTLY), row)
            }
            setMeasuredDimension(width, CandidateGrid.rowCount(cells) * rowHeight)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            cells.forEachIndexed { i, cell ->
                val top = cell.row * rowHeight
                getChildAt(i)?.layout(cell.left, top, cell.left + cell.width, top + rowHeight)
            }
        }
    }

    private companion object {
        const val MIN_GRID_ROWS = 2
        const val MAX_GRID_ROWS = 4
        const val MAX_GRID_SCREEN_SHARE = 0.3f
    }

    // ── Test hooks (instrumentation tests run in this process) ───────────

    /** Where the strip and its ▲ button are, for the trace of a failing test. */
    internal fun geometryForTest(): String {
        val strip = IntArray(2).also(::getLocationOnScreen)
        val button = IntArray(2).also(expandButton::getLocationOnScreen)
        return "strip at (${strip[0]}, ${strip[1]}) ${width}x$height shown=$isShown expanded=$expanded; " +
            "▲ at (${button[0]}, ${button[1]}) ${expandButton.width}x${expandButton.height} " +
            "visible=${expandButton.visibility == VISIBLE}"
    }

    /** There are more candidates than the row holds, so ▲ is offered. */
    internal val canExpandForTest: Boolean get() = !expanded && expandButton.visibility == VISIBLE

    internal val expandedForTest: Boolean get() = expanded

    /** The candidates are laid out (a new list gets positions on the next layout pass). */
    internal val settledForTest: Boolean
        get() = if (expanded) !grid.isLayoutRequested && grid.height > 0 else !itemsRow.isLayoutRequested && itemsRow.width > 0

    /** Screen position of the ▲ / ▼ button's centre. */
    internal fun expandButtonCenterOnScreen(): PointF = centerOnScreen(expandButton)

    /** Index of the first candidate in [row] of the expanded strip, or -1. */
    internal fun firstItemOfRowForTest(row: Int): Int =
        (0 until grid.childCount).firstOrNull { grid.getChildAt(it).top == row * barHeight } ?: -1

    internal fun itemCenterOnScreen(index: Int): PointF? =
        (if (expanded) grid else itemsRow).getChildAt(index)?.let(::centerOnScreen)

    private fun centerOnScreen(view: View): PointF {
        val origin = IntArray(2)
        view.getLocationOnScreen(origin)
        return PointF(origin[0] + view.width / 2f, origin[1] + view.height / 2f)
    }

    private fun scrollIntoView(child: View) {
        val left = child.left
        val right = child.right
        val visibleLeft = scroller.scrollX
        val visibleRight = visibleLeft + scroller.width
        when {
            left < visibleLeft -> scroller.smoothScrollTo(left, 0)
            right > visibleRight -> scroller.smoothScrollTo(right - scroller.width, 0)
        }
    }

    private fun scrollIntoViewVertically(child: View) {
        val visibleTop = gridScroller.scrollY
        val visibleBottom = visibleTop + gridScroller.height
        when {
            child.top < visibleTop -> gridScroller.smoothScrollTo(0, child.top)
            child.bottom > visibleBottom -> gridScroller.smoothScrollTo(0, child.bottom - gridScroller.height)
        }
    }
}
