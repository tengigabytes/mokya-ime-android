// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime.input

import io.github.tengigabytes.mokyaime.engine.MokyaKeys

/**
 * Turns touch-down / touch-up on on-screen keys into MIE key edges with the
 * timing of MokyaLora's keypad driver (firmware/core1/src/keypad/keypad_scan.c):
 *
 * - Deferred keys (input keys in SmartZh, see [KeyboardLayout.defersPress]):
 *   a release before [LONG_PRESS_MS] emits press + release with no flag (a
 *   fuzzy half-key tap); holding emits the press with
 *   `KEY_FLAG_LONG_PRESS` at the [LONG_PRESS_MS] mark, and the release
 *   later carries the same flag.
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
        /** MokyaLora `KP_LONG_PRESS_MS` (= `ImeLogic::kLongPressMs`). */
        const val LONG_PRESS_MS = 500L
        const val REPEAT_DELAY_MS = 400L
        const val REPEAT_INTERVAL_MS = 50L

        val REPEATING: Set<Int> = setOf(
            MokyaKeys.KEY_DEL, MokyaKeys.KEY_LEFT, MokyaKeys.KEY_RIGHT,
            MokyaKeys.KEY_UP, MokyaKeys.KEY_DOWN,
        )
    }

    private enum class State { DEFERRED, LONG, DOWN }

    private class Touch(val keycode: Int, var state: State, var timer: Cancellable?)

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
                touch.state = State.LONG
                sink(keycode, true, MokyaKeys.KEY_FLAG_LONG_PRESS)
            }
            else -> {
                sink(keycode, true, 0)
                if (keycode in REPEATING) touch.timer = scheduleRepeat(touch, REPEAT_DELAY_MS)
            }
        }
    }

    fun up(pointerId: Int) {
        val touch = touches.remove(pointerId) ?: return
        touch.timer?.cancel()
        when (touch.state) {
            State.DEFERRED -> {
                sink(touch.keycode, true, 0)
                sink(touch.keycode, false, 0)
            }
            State.LONG -> sink(touch.keycode, false, MokyaKeys.KEY_FLAG_LONG_PRESS)
            State.DOWN -> sink(touch.keycode, false, 0)
        }
    }

    /**
     * Abandons a touch (finger slid off the key, gesture cancelled): a
     * deferred tap produces nothing; a key already pressed is released.
     */
    fun cancel(pointerId: Int) {
        val touch = touches.remove(pointerId) ?: return
        touch.timer?.cancel()
        when (touch.state) {
            State.DEFERRED -> Unit
            State.LONG -> sink(touch.keycode, false, MokyaKeys.KEY_FLAG_LONG_PRESS)
            State.DOWN -> sink(touch.keycode, false, 0)
        }
    }

    fun cancelAll() {
        touches.keys.toList().forEach(::cancel)
    }

    private fun scheduleRepeat(touch: Touch, delayMs: Long): Cancellable =
        scheduler.schedule(delayMs) {
            sink(touch.keycode, true, 0)
            touch.timer = scheduleRepeat(touch, REPEAT_INTERVAL_MS)
        }
}
