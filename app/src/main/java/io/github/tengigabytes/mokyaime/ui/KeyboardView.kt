// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
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
import io.github.tengigabytes.mokyaime.input.PhonemeSlide
import io.github.tengigabytes.mokyaime.input.PressTracker
import io.github.tengigabytes.mokyaime.input.ShiftKey
import io.github.tengigabytes.mokyaime.input.TouchKey

/**
 * On-screen keyboard, drawn on a canvas ([KeyboardLayout.touchRows]): the
 * MokyaLora half-keyboard (OK / DEL row and the 5×5 core) in 中 and EN, and
 * QWERTY with a number row in ABC, plus a symbol page. Engine keys become MIE key edges through
 * [PressTracker] and go to [onKey]; QWERTY text goes to [onText].
 *
 * In 中 a Bopomofo key is tapped for any of its symbols, or one is picked by
 * sliding left or right ([PhonemeSlide]) or by holding the key; a popup
 * above the key then shows which. 。？！ is picked from in the same way.
 * ，SYM types 、 or ： when slid; holding it opens the engine's picker,
 * for which the keyboard turns into a page of symbols ([picker]).
 */
class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Receives MIE key edges (keycode, pressed, flags). */
    var onKey: (keycode: Int, pressed: Boolean, flags: Int) -> Unit = { _, _, _ -> }

    /**
     * Receives the press of a key held since [sinceMs] (uptimeMillis), for
     * the engine to see it as held that long. Its release goes to [onKey].
     */
    var onKeyHeld: (keycode: Int, sinceMs: Long) -> Unit = { _, _ -> }

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
            updateGestureExclusion()
            invalidate()
        }

    /** True while ABC shows its symbol page instead of the letters. */
    private var symbols = false

    /** True while the engine's SYM1 picker is open: shows its page ([KeyboardLayout.pickerRows]). */
    var picker = false
        set(value) {
            if (field == value) return
            field = value
            MokyaImeService.trace { "keyboard picker page=$value" }
            rebuild()
            invalidate()
        }

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

    /** A finger on [key]. [choices]: what sliding or holding picks from ([KeyboardLayout.slideChoices]). */
    private class Press(val key: Key, val downX: Float, val downTimeMs: Long, val choices: List<String>) {
        val slides: Boolean get() = choices.isNotEmpty()

        /** Holding the key opens the engine's picker instead of picking one of [choices]. */
        val holdOpensPicker = (key.spec as? TouchKey.Engine)?.let { KeyboardLayout.holdOpensPicker(it.keycode) } == true

        /** Past the long-press mark. */
        var held = false

        /** Index in [choices] picked by sliding. */
        var phoneme: Int? = null

        /** Held without sliding, on a key that opens the picker: the engine has its press ([onKeyHeld]). */
        var engineDown = false

        /** Index in [choices] that a release types, or null for a plain tap. */
        val typed: Int? get() = phoneme ?: if (held && !holdOpensPicker) PhonemeSlide.held(choices.size) else null
    }

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
    private val slideThreshold = 16 * density
    private val popupCellWidth = 44 * density
    private val popupHeight = 40 * density
    private val edgeGestureWidth = (32 * density).toInt()

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
    private val presses = HashMap<Int, Press>()   // by pointer id
    private val popupBounds = RectF()
    private val tracker = PressTracker(
        scheduler = { delayMs, action ->
            val runnable = Runnable(action)
            handler.postDelayed(runnable, delayMs)
            PressTracker.Cancellable { handler.removeCallbacks(runnable) }
        },
        onHold = ::onHold,
        sink = { keycode, pressed, flags -> onKey(keycode, pressed, flags) },
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
        KeyboardLayout.touchRows(mode, symbols, picker).map { row -> row.map { Key(it) } }

    /** New keys for the current layout; held keys keep their old Key objects, so their release still arrives. */
    private fun rebuild() {
        rows = buildRows()
        shift.reset()
        if (width > 0) layoutKeys(width)
    }

    /**
     * The long-press mark of a Bopomofo key: confirm it with a stronger
     * haptic tick, highlight the key and show its symbols, so the user sees
     * which one a release types without counting milliseconds.
     */
    private fun onHold(pointerId: Int) {
        val press = presses[pointerId] ?: return
        press.held = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val keycode = (press.key.spec as? TouchKey.Engine)?.keycode
        if (keycode != null && press.phoneme == null && press.holdOpensPicker) {
            // The engine opens its picker on its next tick: it measures the hold itself.
            press.engineDown = true
            onKeyHeld(keycode, press.downTimeMs)
        }
        invalidate()
    }

    /**
     * Sliding sideways from the edge columns must pick a symbol, not start
     * the system's back gesture.
     */
    private fun updateGestureExclusion() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        systemGestureExclusionRects = if (mode == InputMode.SMART_ZH && width > 0) {
            listOf(Rect(0, 0, edgeGestureWidth, height), Rect(width - edgeGestureWidth, 0, width, height))
        } else {
            emptyList()
        }
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
        updateGestureExclusion()
    }

    private fun layoutKeys(w: Int) {
        val halfKeyboard = mode != InputMode.DIRECT && !picker
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
        val pressed = presses.values.map { it.key }.toSet()
        val picking = presses.values.filter { (it.held || it.phoneme != null) && !it.engineDown }
        val pickingKeys = picking.map { it.key }.toSet()
        for (key in keys) {
            val spec = key.spec
            val keycode = (spec as? TouchKey.Engine)?.keycode
            keyPaint.color = when {
                key in pickingKeys -> colorLong
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
        picking.forEach { drawPhonemePopup(canvas, it) }
    }

    /** The symbols of a held or slid key, above it, with the one a release types highlighted. */
    private fun drawPhonemePopup(canvas: Canvas, press: Press) {
        val phonemes = press.choices
        val typed = press.typed
        val key = press.key.bounds
        val popupWidth = popupCellWidth * phonemes.size
        val left = (key.centerX() - popupWidth / 2).coerceIn(gap, maxOf(gap, width - gap - popupWidth))
        val top = maxOf(0f, key.top - gap - popupHeight)
        popupBounds.set(left, top, left + popupWidth, top + popupHeight)
        keyPaint.color = colorPressed
        canvas.drawRoundRect(popupBounds, radius, radius, keyPaint)
        mainPaint.textSize = sp(19f)
        phonemes.forEachIndexed { index, phoneme ->
            val cellLeft = left + popupCellWidth * index
            if (index == typed) {
                popupBounds.set(cellLeft, top, cellLeft + popupCellWidth, top + popupHeight)
                keyPaint.color = colorLong
                canvas.drawRoundRect(popupBounds, radius, radius, keyPaint)
            }
            canvas.drawText(phoneme, cellLeft + popupCellWidth / 2, top + popupHeight / 2 + mainPaint.textSize * 0.38f, mainPaint)
        }
    }

    private fun label(spec: TouchKey): KeyboardLayout.Label = when (spec) {
        is TouchKey.Engine ->
            when {
                spec.keycode == MokyaKeys.KEY_OK -> KeyboardLayout.Label(if (composing) "OK" else idleOkLabel, "")
                // On the picker's page SYM1 goes back to the mode's keyboard.
                picker && spec.keycode == MokyaKeys.KEY_SYM1 -> KeyboardLayout.Label(mode.indicator, "SYM")
                else -> KeyboardLayout.label(spec.keycode, mode)
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
                val engineKey = key.spec as? TouchKey.Engine
                // The picker's page has nothing to pick by sliding: its SYM key only goes back.
                val choices = if (picker) emptyList() else engineKey?.let { KeyboardLayout.slideChoices(it.keycode, mode) }.orEmpty()
                val press = Press(key, event.getX(i), event.eventTime, choices)
                presses[pointer] = press
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                // Text, Shift and the page key act on release, so sliding off cancels them.
                engineKey?.let { tracker.down(pointer, it.keycode, deferPress = press.slides) }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val pointer = event.getPointerId(i)
                    val press = presses[pointer] ?: continue
                    val key = press.key
                    val x = event.getX(i)
                    val y = event.getY(i)
                    // Sliding off a key abandons it (it types nothing). A key with
                    // symbols to pick is only left upwards or downwards: sideways
                    // picks one of them.
                    val off = if (press.slides) {
                        y < key.bounds.top - key.bounds.height() / 2 || y > key.bounds.bottom + key.bounds.height() / 2
                    } else {
                        !key.bounds.contains(x, y)
                    }
                    if (off) {
                        MokyaImeService.traceInput { "touch slid off key=${key.spec}" }
                        presses.remove(pointer)
                        if (key.spec is TouchKey.Engine) {
                            tracker.cancel(pointer)
                            if (press.engineDown) onKey(key.spec.keycode, false, 0)
                        }
                        invalidate()
                    } else if (press.slides && !press.engineDown) {
                        val phoneme = PhonemeSlide.picked(press.choices.size, x - press.downX, slideThreshold)
                        if (phoneme != press.phoneme) {
                            MokyaImeService.traceInput { "touch slid to phoneme $phoneme of key=${key.spec}" }
                            press.phoneme = phoneme
                            tracker.pick(pointer, phoneme)
                            if (phoneme != null) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            invalidate()
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pointer = event.getPointerId(event.actionIndex)
                MokyaImeService.trace { "touch up" }
                val press = presses.remove(pointer) ?: return true
                when (val spec = press.key.spec) {
                    is TouchKey.Engine -> {
                        val typed = press.typed
                        when {
                            press.engineDown -> {
                                tracker.cancel(pointer)
                                onKey(spec.keycode, false, 0)
                            }
                            typed != null && !KeyLabels.isInputKey(spec.keycode) -> {
                                // A punctuation mark: the engine has no way to name one.
                                tracker.cancel(pointer)
                                onText(press.choices[typed])
                            }
                            else -> tracker.up(pointer)
                        }
                    }
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
        presses.values.filter { it.engineDown }.forEach { onKey((it.key.spec as TouchKey.Engine).keycode, false, 0) }
        presses.clear()
        tracker.cancelAll()
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
