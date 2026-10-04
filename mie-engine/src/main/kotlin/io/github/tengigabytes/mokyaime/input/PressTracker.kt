// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime.input

import io.github.tengigabytes.mokyaime.engine.MokyaKeys

/**
 * Turns touch-down / touch-up on on-screen keys into MIE key edges.
 *
 * - Deferred keys (input keys in SmartZh, see [KeyboardLayout.defersPress])
 *   act on release, with press + release. Released before [LONG_PRESS_MS]
 *   without a symbol picked, they carry no flag (a fuzzy half-key tap).
 *   With one picked by sliding ([pick], see [PhonemeSlide]) they carry its
 *   explicit phoneme flag. Held past [LONG_PRESS_MS], which [onHold]
 *   reports, they carry the flag of the picked symbol, or else of the one
 *   that holding gives. The device's own way to reach the second symbol, a
 *   second long press within 800 ms, is not used: on a touch screen that
 *   leaves 300 ms to lift the finger and press again.
 * - [REPEATING] keys (DEL and the arrows) press on touch-down and, when
 *   held, press again after [REPEAT_DELAY_MS] and every
 *   [REPEAT_INTERVAL_MS]. This auto-repeat is an Android addition; the
 *   device keypad does not repeat.
 * - Every other key presses on touch-down and releases on touch-up, which
 *   SYM1's engine-side long-press detection relies on.
 *
 * Pointers are tracked independently, so several keys may be held at once.
 * Not thread-safe; use it on the UI thread.
 */
class PressTracker(
    private val scheduler: Scheduler,
    private val onHold: (pointerId: Int) -> Unit = {},
    private val sink: (keycode: Int, pressed: Boolean, flags: Int) -> Unit,
) {
    /** Runs [action] after [delayMs] on the same thread; returns a handle to cancel it. */
    fun interface Scheduler {
        fun schedule(delayMs: Long, action: () -> Unit): Cancellable
    }

    fun interface Cancellable {
        fun cancel()
    }

    companion object {
        /** As MokyaLora's `KP_LONG_PRESS_MS` (= `ImeLogic::kLongPressMs`). */
        const val LONG_PRESS_MS = 500L
        const val REPEAT_DELAY_MS = 400L
        const val REPEAT_INTERVAL_MS = 50L

        val REPEATING: Set<Int> = setOf(
            MokyaKeys.KEY_DEL, MokyaKeys.KEY_LEFT, MokyaKeys.KEY_RIGHT,
            MokyaKeys.KEY_UP, MokyaKeys.KEY_DOWN,
        )
    }

    private enum class State { DEFERRED, HELD, DOWN }

    private class Touch(val keycode: Int, var state: State, var timer: Cancellable?) {
        /** Symbol picked by sliding, for a deferred key. */
        var phoneme: Int? = null
    }

    private val touches = HashMap<Int, Touch>()

    /** True while any key is held. */
    val isActive: Boolean get() = touches.isNotEmpty()

    fun down(pointerId: Int, keycode: Int, deferPress: Boolean) {
        cancel(pointerId)
        val touch = Touch(keycode, if (deferPress) State.DEFERRED else State.DOWN, null)
        touches[pointerId] = touch
        when {
            deferPress -> touch.timer = scheduler.schedule(LONG_PRESS_MS) {
                touch.timer = null
                touch.state = State.HELD
                onHold(pointerId)
            }
            else -> {
                sink(keycode, true, 0)
                if (keycode in REPEATING) touch.timer = scheduleRepeat(touch, REPEAT_DELAY_MS)
            }
        }
    }

    /**
     * The symbol the finger has slid to on a deferred key, or null once it
     * is back near where it went down.
     */
    fun pick(pointerId: Int, phoneme: Int?) {
        val touch = touches[pointerId] ?: return
        if (touch.state != State.DOWN) touch.phoneme = phoneme
    }

    fun up(pointerId: Int) {
        val touch = touches.remove(pointerId) ?: return
        touch.timer?.cancel()
        if (touch.state == State.DOWN) {
            sink(touch.keycode, false, 0)
            return
        }
        val phoneme = touch.phoneme
            ?: if (touch.state == State.HELD) PhonemeSlide.held(phonemeCount(touch.keycode)) else null
        val flags = if (phoneme == null) 0 else MokyaKeys.keyFlagPhoneme(phoneme)
        sink(touch.keycode, true, flags)
        sink(touch.keycode, false, flags)
    }

    /**
     * Abandons a touch (finger slid off the key, gesture cancelled): a
     * deferred key produces nothing; a key already pressed is released.
     */
    fun cancel(pointerId: Int) {
        val touch = touches.remove(pointerId) ?: return
        touch.timer?.cancel()
        if (touch.state == State.DOWN) sink(touch.keycode, false, 0)
    }

    fun cancelAll() {
        touches.keys.toList().forEach(::cancel)
    }

    private fun phonemeCount(keycode: Int): Int = KeyLabels.inputKey(keycode)?.phonemes?.size ?: 1

    private fun scheduleRepeat(touch: Touch, delayMs: Long): Cancellable =
        scheduler.schedule(delayMs) {
            sink(touch.keycode, true, 0)
            touch.timer = scheduleRepeat(touch, REPEAT_INTERVAL_MS)
        }
}
