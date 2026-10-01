// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import io.github.tengigabytes.mokyaime.MokyaImeService
import io.github.tengigabytes.mokyaime.R
import io.github.tengigabytes.mokyaime.engine.InputMode
import io.github.tengigabytes.mokyaime.input.KeyLabels
import io.github.tengigabytes.mokyaime.input.KeyboardLayout
import io.github.tengigabytes.mokyaime.input.PressTracker

/**
 * On-screen MokyaLora half-keyboard ([KeyboardLayout]): the navigation row
 * and the 5×5 core input area, drawn on a canvas. Touches become MIE key
 * edges through [PressTracker], which reproduces the device's long-press
 * timing; [onKey] receives them.
 */
class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Receives MIE key edges (keycode, pressed, flags). */
    var onKey: (keycode: Int, pressed: Boolean, flags: Int) -> Unit = { _, _, _ -> }

    /** Current input mode; drives the labels and long-press behaviour. */
    var mode: InputMode = InputMode.SMART_ZH
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private class Key(val keycode: Int, val bounds: RectF = RectF())

    private val rows: List<List<Key>> = KeyboardLayout.rows.map { row -> row.map { Key(it) } }
    private val keys: List<Key> = rows.flatten()

    private val density = resources.displayMetrics.density
    private fun sp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
    private val navRowHeight = 44 * density
    private val keyRowHeight = 52 * density
    private val gap = 3 * density
    private val radius = 6 * density

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = context.getColor(R.color.key_text)
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(11f)
        color = context.getColor(R.color.key_hint_text)
    }
    private val colorInput = context.getColor(R.color.key_bg)
    private val colorFunction = context.getColor(R.color.key_bg_function)
    private val colorPressed = context.getColor(R.color.key_bg_pressed)

    private val handler = Handler(Looper.getMainLooper())
    private val pressedKeys = HashMap<Int, Key>()   // by pointer id
    private val tracker = PressTracker(
        scheduler = { delayMs, action ->
            val runnable = Runnable(action)
            handler.postDelayed(runnable, delayMs)
            PressTracker.Cancellable { handler.removeCallbacks(runnable) }
        },
        sink = { keycode, pressed, flags -> onKey(keycode, pressed, flags) },
    )

    init {
        setBackgroundColor(context.getColor(R.color.keyboard_bg))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = navRowHeight + keyRowHeight * KeyboardLayout.coreRows.size + gap
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height.toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        var top = gap / 2
        rows.forEachIndexed { index, row ->
            val rowHeight = if (index == 0) navRowHeight else keyRowHeight
            val keyWidth = (w - gap) / row.size
            row.forEachIndexed { col, key ->
                val left = gap / 2 + col * keyWidth
                key.bounds.set(left + gap / 2, top + gap / 2, left + keyWidth - gap / 2, top + rowHeight - gap / 2)
            }
            top += rowHeight
        }
    }

    override fun onDraw(canvas: Canvas) {
        val pressed = pressedKeys.values.toSet()
        for (key in keys) {
            keyPaint.color = when {
                key in pressed -> colorPressed
                KeyLabels.isInputKey(key.keycode) -> colorInput
                else -> colorFunction
            }
            canvas.drawRoundRect(key.bounds, radius, radius, keyPaint)

            val label = KeyboardLayout.label(key.keycode, mode)
            val b = key.bounds
            mainPaint.textSize = sp(if (label.main.length > 3) 15f else 19f)
            val mainY = b.centerY() + mainPaint.textSize * 0.45f + (if (label.hint.isEmpty()) 0f else 4 * density)
            canvas.drawText(label.main, b.centerX(), mainY, mainPaint)
            if (label.hint.isNotEmpty()) {
                canvas.drawText(label.hint, b.centerX(), b.top + hintPaint.textSize + 2 * density, hintPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                val key = keyAt(event.getX(i), event.getY(i))
                MokyaImeService.trace { "touch down (${event.getX(i)}, ${event.getY(i)}) key=${key?.keycode}" }
                if (key == null) return true
                val pointer = event.getPointerId(i)
                pressedKeys[pointer] = key
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                tracker.down(pointer, key.keycode, KeyboardLayout.defersPress(key.keycode, mode))
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                // Sliding off a key abandons it (a deferred tap types nothing).
                for (i in 0 until event.pointerCount) {
                    val pointer = event.getPointerId(i)
                    val key = pressedKeys[pointer] ?: continue
                    if (!key.bounds.contains(event.getX(i), event.getY(i))) {
                        MokyaImeService.trace { "touch slid off key=${key.keycode}" }
                        pressedKeys.remove(pointer)
                        tracker.cancel(pointer)
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pointer = event.getPointerId(event.actionIndex)
                MokyaImeService.trace { "touch up" }
                if (pressedKeys.remove(pointer) != null) tracker.up(pointer)
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                MokyaImeService.trace { "touch cancel" }
                cancelTouches()
            }
        }
        return true
    }

    /** Abandons every held key, e.g. when the keyboard is hidden. */
    fun cancelTouches() {
        pressedKeys.clear()
        tracker.cancelAll()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        cancelTouches()
        super.onDetachedFromWindow()
    }

    /** Screen position of [keycode]'s centre, or null while not laid out and shown (tests). */
    internal fun keyCenterOnScreen(keycode: Int): PointF? {
        if (!isShown || width == 0) return null
        val key = keys.firstOrNull { it.keycode == keycode } ?: return null
        val origin = IntArray(2)
        getLocationOnScreen(origin)
        return PointF(origin[0] + key.bounds.centerX(), origin[1] + key.bounds.centerY())
    }

    private fun keyAt(x: Float, y: Float): Key? =
        keys.firstOrNull { it.bounds.contains(x, y) }
            ?: keys.minByOrNull { val dx = it.bounds.centerX() - x; val dy = it.bounds.centerY() - y; dx * dx + dy * dy }
                ?.takeIf { y >= 0 && y <= height }
}
