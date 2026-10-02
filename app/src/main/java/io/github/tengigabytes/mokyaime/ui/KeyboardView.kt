// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import io.github.tengigabytes.mokyaime.MokyaImeService
import io.github.tengigabytes.mokyaime.R
import io.github.tengigabytes.mokyaime.engine.InputMode
import io.github.tengigabytes.mokyaime.engine.MokyaKeys
import io.github.tengigabytes.mokyaime.input.KeyLabels
import io.github.tengigabytes.mokyaime.input.KeyboardLayout
import io.github.tengigabytes.mokyaime.input.PressTracker
import io.github.tengigabytes.mokyaime.input.ShiftKey
import io.github.tengigabytes.mokyaime.input.TouchKey

/**
 * On-screen keyboard, drawn on a canvas ([KeyboardLayout.touchRows]): the
 * MokyaLora half-keyboard (OK / DEL row and the 5×5 core) in 中 and EN, and
 * QWERTY with a number row in ABC, plus a symbol page. Engine keys become MIE key edges through
 * [PressTracker], which reproduces the device's long-press timing, and go to
 * [onKey]; QWERTY text goes to [onText].
 */
class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Receives MIE key edges (keycode, pressed, flags). */
    var onKey: (keycode: Int, pressed: Boolean, flags: Int) -> Unit = { _, _, _ -> }

    /** Receives text typed on the QWERTY layout. */
    var onText: (String) -> Unit = {}

    /** Current input mode; picks the layout and drives labels and long-press behaviour. */
    var mode: InputMode = InputMode.SMART_ZH
        set(value) {
            if (field == value) return
            val newLayout = (field == InputMode.DIRECT) != (value == InputMode.DIRECT)
            field = value
            if (newLayout) {
                symbols = false
                rebuild()
            }
            invalidate()
        }

    /** True while ABC shows its symbol page instead of the letters. */
    private var symbols = false

    /** True while something is pending: OK then commits it rather than running [idleOkLabel]. */
    var composing = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** What OK shows when nothing is pending: the editor's Enter action. */
    var idleOkLabel = "↵"
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private class Key(val spec: TouchKey, val bounds: RectF = RectF())

    private var rows: List<List<Key>> = buildRows()
    private val keys: List<Key> get() = rows.flatten()

    private val shift = ShiftKey()

    private val density = resources.displayMetrics.density
    private fun sp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
    private val actionRowHeight = 44 * density
    private val keyRowHeight = 52 * density
    private val keyboardHeight = actionRowHeight + keyRowHeight * KeyboardLayout.coreRows.size
    private val gap = 3 * density
    private val radius = 6 * density

    /**
     * Space below the keys for the navigation bar / gesture handle. From
     * Android 15 the IME window is edge to edge and the system bar overlaps
     * its bottom; earlier, the window already insets its content (0 here).
     */
    private var bottomInset = 0

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
    private val colorAccent = context.getColor(R.color.key_bg_accent)
    private val colorLong = context.getColor(R.color.key_bg_long)

    private val handler = Handler(Looper.getMainLooper())
    private val pressedKeys = HashMap<Int, Key>()   // by pointer id
    private val longPressed = HashSet<Int>()        // keycodes past the long-press mark
    private val tracker = PressTracker(
        scheduler = { delayMs, action ->
            val runnable = Runnable(action)
            handler.postDelayed(runnable, delayMs)
            PressTracker.Cancellable { handler.removeCallbacks(runnable) }
        },
        sink = { keycode, pressed, flags ->
            if (flags and MokyaKeys.KEY_FLAG_LONG_PRESS != 0) onLongPressEdge(keycode, pressed)
            onKey(keycode, pressed, flags)
        },
    )

    init {
        setBackgroundColor(context.getColor(R.color.keyboard_bg))
    }

    /** Back to lower-case letters, e.g. for a new editor. */
    fun resetShift() {
        if (symbols) {
            symbols = false
            rebuild()
        }
        shift.reset()
        invalidate()
    }

    private fun buildRows(): List<List<Key>> =
        KeyboardLayout.touchRows(mode, symbols).map { row -> row.map { Key(it) } }

    /** New keys for the current layout; held keys keep their old Key objects, so their release still arrives. */
    private fun rebuild() {
        rows = buildRows()
        shift.reset()
        if (width > 0) layoutKeys(width)
    }

    /**
     * The long-press mark of a Bopomofo key: confirm it with a stronger
     * haptic tick and highlight the key until it is released, so the user
     * knows the phoneme was pinned without counting milliseconds.
     */
    private fun onLongPressEdge(keycode: Int, pressed: Boolean) {
        if (pressed) {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            longPressed += keycode
        } else {
            longPressed -= keycode
        }
        invalidate()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val bottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // The IME's own navigation bar (back / IME switcher) is a caption bar.
            insets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.captionBar()).bottom
        } else {
            @Suppress("DEPRECATION")
            insets.systemWindowInsetBottom
        }
        if (bottom != bottomInset) {
            MokyaImeService.trace { "keyboard bottom inset $bottomInset -> $bottom" }
            bottomInset = bottom
            requestLayout()
        }
        return insets
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // The same height for every layout, so switching modes does not resize the window.
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (keyboardHeight + gap).toInt() + bottomInset)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layoutKeys(w)
    }

    private fun layoutKeys(w: Int) {
        val halfKeyboard = mode != InputMode.DIRECT
        var top = gap / 2
        rows.forEachIndexed { index, row ->
            val rowHeight = when {
                !halfKeyboard -> keyboardHeight / rows.size
                index == 0 -> actionRowHeight
                else -> keyRowHeight
            }
            val unit = (w - gap) / row.sumOf { it.spec.weight.toDouble() }.toFloat()
            var left = gap / 2
            for (key in row) {
                val keyWidth = unit * key.spec.weight
                key.bounds.set(left + gap / 2, top + gap / 2, left + keyWidth - gap / 2, top + rowHeight - gap / 2)
                left += keyWidth
            }
            top += rowHeight
        }
    }

    override fun onDraw(canvas: Canvas) {
        val pressed = pressedKeys.values.toSet()
        for (key in keys) {
            val spec = key.spec
            val keycode = (spec as? TouchKey.Engine)?.keycode
            keyPaint.color = when {
                keycode != null && keycode in longPressed -> colorLong
                key in pressed -> colorPressed
                keycode == MokyaKeys.KEY_OK && !composing -> colorAccent
                spec is TouchKey.Shift && shift.active -> colorAccent
                spec is TouchKey.Page && symbols -> colorAccent
                spec is TouchKey.Text -> colorInput
                keycode != null && KeyLabels.isInputKey(keycode) -> colorInput
                else -> colorFunction
            }
            canvas.drawRoundRect(key.bounds, radius, radius, keyPaint)

            val label = label(spec)
            val b = key.bounds
            mainPaint.textSize = sp(if (label.main.length > 3) 15f else 19f)
            val mainY = b.centerY() + mainPaint.textSize * 0.45f + (if (label.hint.isEmpty()) 0f else 4 * density)
            canvas.drawText(label.main, b.centerX(), mainY, mainPaint)
            if (label.hint.isNotEmpty()) {
                canvas.drawText(label.hint, b.centerX(), b.top + hintPaint.textSize + 2 * density, hintPaint)
            }
        }
    }

    private fun label(spec: TouchKey): KeyboardLayout.Label = when (spec) {
        is TouchKey.Engine ->
            if (spec.keycode == MokyaKeys.KEY_OK) {
                KeyboardLayout.Label(if (composing) "OK" else idleOkLabel, "")
            } else {
                KeyboardLayout.label(spec.keycode, mode)
            }
        is TouchKey.Text -> when {
            spec.normal == " " -> KeyboardLayout.Label("␣", "")
            shift.active -> KeyboardLayout.Label(spec.shifted, "")
            // Show the symbol Shift gives, unless it is just the capital letter.
            spec.shifted != spec.normal.uppercase() -> KeyboardLayout.Label(spec.normal, spec.shifted)
            else -> KeyboardLayout.Label(spec.normal, "")
        }
        is TouchKey.Shift -> KeyboardLayout.Label(if (shift.state == ShiftKey.State.LOCKED) "⇪" else "⇧", "")
        is TouchKey.Page -> KeyboardLayout.Label(if (symbols) "abc" else "#+=", "")
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                val key = keyAt(event.getX(i), event.getY(i))
                MokyaImeService.traceInput { "touch down (${event.getX(i)}, ${event.getY(i)}) key=${key?.spec}" }
                if (key == null) return true
                val pointer = event.getPointerId(i)
                pressedKeys[pointer] = key
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                // Text, Shift and the page key act on release, so sliding off cancels them.
                (key.spec as? TouchKey.Engine)?.let {
                    tracker.down(pointer, it.keycode, KeyboardLayout.defersPress(it.keycode, mode))
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                // Sliding off a key abandons it (a deferred tap types nothing).
                for (i in 0 until event.pointerCount) {
                    val pointer = event.getPointerId(i)
                    val key = pressedKeys[pointer] ?: continue
                    if (!key.bounds.contains(event.getX(i), event.getY(i))) {
                        MokyaImeService.traceInput { "touch slid off key=${key.spec}" }
                        pressedKeys.remove(pointer)
                        if (key.spec is TouchKey.Engine) tracker.cancel(pointer)
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pointer = event.getPointerId(event.actionIndex)
                MokyaImeService.trace { "touch up" }
                when (val spec = pressedKeys.remove(pointer)?.spec) {
                    is TouchKey.Engine -> tracker.up(pointer)
                    is TouchKey.Text -> {
                        onText(if (shift.active) spec.shifted else spec.normal)
                        shift.typed()
                    }
                    is TouchKey.Shift -> shift.tap(event.eventTime)
                    is TouchKey.Page -> {
                        symbols = !symbols
                        MokyaImeService.trace { "ABC page: ${if (symbols) "symbols" else "letters"}" }
                        rebuild()
                    }
                    null -> Unit
                }
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
        longPressed.clear()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        cancelTouches()
        super.onDetachedFromWindow()
    }

    // ── Test hooks (instrumentation tests run in this process) ───────────

    /** Screen position of engine key [keycode]'s centre, or null while not laid out and shown. */
    internal fun keyCenterOnScreen(keycode: Int): PointF? =
        centerOnScreen { (it as? TouchKey.Engine)?.keycode == keycode }

    /** Screen position of the QWERTY key typing [normal], or null. */
    internal fun textKeyCenterOnScreen(normal: String): PointF? =
        centerOnScreen { (it as? TouchKey.Text)?.normal == normal }

    internal fun shiftKeyCenterOnScreen(): PointF? = centerOnScreen { it is TouchKey.Shift }

    internal fun pageKeyCenterOnScreen(): PointF? = centerOnScreen { it is TouchKey.Page }

    /** The label OK shows right now. */
    internal val okLabelForTest: String get() = if (composing) "OK" else idleOkLabel

    private fun centerOnScreen(match: (TouchKey) -> Boolean): PointF? {
        if (!isShown || width == 0) return null
        val key = keys.firstOrNull { match(it.spec) } ?: return null
        val origin = IntArray(2)
        getLocationOnScreen(origin)
        return PointF(origin[0] + key.bounds.centerX(), origin[1] + key.bounds.centerY())
    }

    private fun keyAt(x: Float, y: Float): Key? =
        keys.firstOrNull { it.bounds.contains(x, y) }
            ?: keys.minByOrNull { val dx = it.bounds.centerX() - x; val dy = it.bounds.centerY() - y; dx * dx + dy * dy }
                ?.takeIf { y >= 0 && y <= height - bottomInset }
}
