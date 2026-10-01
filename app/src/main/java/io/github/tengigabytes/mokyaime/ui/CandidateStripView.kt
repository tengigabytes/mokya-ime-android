// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime.ui

import android.content.Context
import android.graphics.PointF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.tengigabytes.mokyaime.R
import io.github.tengigabytes.mokyaime.input.StripPaging

/**
 * The strip above the keyboard: `[mode] ‹ candidate candidate … ›`. Tapping
 * a candidate commits it; ‹ › page through the row ([StripPaging]), which
 * can also be swiped. While the SYM1 picker is open it lists the picker's
 * symbols instead.
 */
class CandidateStripView(context: Context) : LinearLayout(context) {

    /** What the strip shows. [selected] is -1 when nothing is selectable. */
    data class State(
        val modeIndicator: String,
        val items: List<String>,
        val selected: Int,
        val picker: Boolean,
    )

    var onItemTapped: (index: Int) -> Unit = {}
    var onModeTapped: () -> Unit = {}

    private val density = resources.displayMetrics.density
    private val textColor = context.getColor(R.color.strip_text)
    private val selectedBackground = context.getColor(R.color.strip_selected_bg)

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
    private val previousPage = pageButton("‹", R.string.candidates_previous_page) {
        pageTo(StripPaging.previous(starts(), itemsRow.width, scroller.scrollX, scroller.width))
    }
    private val nextPage = pageButton("›", R.string.candidates_next_page) {
        pageTo(StripPaging.next(starts(), itemsRow.width, scroller.scrollX, scroller.width))
    }

    private var shownItems: List<String> = emptyList()
    private var shownSelected = -1

    init {
        orientation = HORIZONTAL
        setBackgroundColor(context.getColor(R.color.strip_bg))
        val height = (44 * density).toInt()
        minimumHeight = height
        val pageButtonWidth = (40 * density).toInt()
        addView(modeView, LayoutParams(LayoutParams.WRAP_CONTENT, height))
        addView(previousPage, LayoutParams(pageButtonWidth, height))
        addView(scroller, LayoutParams(0, height, 1f))
        addView(nextPage, LayoutParams(pageButtonWidth, height))
        scroller.setOnScrollChangeListener { _, _, _, _, _ -> updatePageButtons() }
        itemsRow.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updatePageButtons() }
        updatePageButtons()
    }

    fun show(state: State) {
        modeView.text = state.modeIndicator
        if (state.items != shownItems) {
            shownItems = state.items
            shownSelected = -1
            itemsRow.removeAllViews()
            state.items.forEachIndexed { index, text -> itemsRow.addView(itemView(index, text)) }
            scroller.scrollTo(0, 0)
        }
        if (state.selected != shownSelected) {
            (0 until itemsRow.childCount).forEach { i -> styleItem(itemsRow.getChildAt(i) as TextView, i == state.selected) }
            shownSelected = state.selected
            itemsRow.getChildAt(state.selected)?.let { child -> scroller.post { scrollIntoView(child) } }
        }
    }

    private fun pageButton(label: String, description: Int, onTap: () -> Unit) = TextView(context).apply {
        text = label
        contentDescription = context.getString(description)
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        setTextColor(textColor)
        setOnClickListener { onTap() }
    }

    private fun starts(): List<Int> = (0 until itemsRow.childCount).map { itemsRow.getChildAt(it).left }

    private fun pageTo(x: Int) {
        scroller.smoothScrollTo(x, 0)
    }

    /** Hidden while there is nothing to page; dimmed at either end. */
    private fun updatePageButtons() {
        val visibility = if (itemsRow.childCount == 0) INVISIBLE else VISIBLE
        val back = StripPaging.canPageBack(scroller.scrollX)
        val forward = StripPaging.canPageForward(itemsRow.width, scroller.scrollX, scroller.width)
        for ((button, enabled) in listOf(previousPage to back, nextPage to forward)) {
            button.visibility = visibility
            button.isEnabled = enabled
            button.alpha = if (enabled) 1f else 0.25f
        }
    }

    private fun itemView(index: Int, text: String) = TextView(context).apply {
        this.text = text
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        setTextColor(textColor)
        val padding = (12 * density).toInt()
        setPadding(padding, 0, padding, 0)
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
        setOnClickListener { onItemTapped(index) }
        styleItem(this, false)
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

    // ── Test hooks (instrumentation tests run in this process) ───────────

    internal val scrollXForTest: Int get() = scroller.scrollX

    internal val canPageForwardForTest: Boolean get() = nextPage.isEnabled

    /** Screen position of the ‹ (false) or › (true) button's centre. */
    internal fun pageButtonCenterOnScreen(forward: Boolean): PointF = centerOnScreen(if (forward) nextPage else previousPage)

    /** Index of the first candidate shown in full, or -1. */
    internal fun firstVisibleItemForTest(): Int =
        (0 until itemsRow.childCount).firstOrNull { itemsRow.getChildAt(it).left >= scroller.scrollX } ?: -1

    internal fun itemCenterOnScreen(index: Int): PointF? = itemsRow.getChildAt(index)?.let(::centerOnScreen)

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
}
