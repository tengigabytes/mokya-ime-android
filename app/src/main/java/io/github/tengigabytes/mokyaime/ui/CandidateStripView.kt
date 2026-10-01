// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.tengigabytes.mokyaime.R

/**
 * The strip above the keyboard, as on MokyaLora's IME view:
 * `[mode] candidate candidate … [n/total]`. While the SYM1 picker is open it
 * lists the picker's symbols instead.
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
    private val positionView = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(context.getColor(R.color.key_hint_text))
        minWidth = (52 * density).toInt()
    }

    private var shownItems: List<String> = emptyList()
    private var shownSelected = -1

    init {
        orientation = HORIZONTAL
        setBackgroundColor(context.getColor(R.color.strip_bg))
        val height = (44 * density).toInt()
        minimumHeight = height
        addView(modeView, LayoutParams(LayoutParams.WRAP_CONTENT, height))
        addView(scroller, LayoutParams(0, height, 1f))
        addView(positionView, LayoutParams(LayoutParams.WRAP_CONTENT, height))
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
        positionView.text = when {
            state.items.isEmpty() -> ""
            state.selected >= 0 -> "${state.selected + 1}/${state.items.size}"
            else -> "${state.items.size}"
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
