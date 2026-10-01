// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime.input

/**
 * Shift on the touch QWERTY layout: a tap shifts the next character only, a
 * double tap locks it, and a tap while locked releases it.
 */
class ShiftKey(private val doubleTapMs: Long = DOUBLE_TAP_MS) {

    enum class State { OFF, ONCE, LOCKED }

    companion object {
        const val DOUBLE_TAP_MS = 300L
    }

    var state = State.OFF
        private set

    val active: Boolean get() = state != State.OFF

    private var lastTapMs = 0L

    fun tap(nowMs: Long) {
        state = when (state) {
            State.OFF -> State.ONCE
            State.ONCE -> if (nowMs - lastTapMs <= doubleTapMs) State.LOCKED else State.OFF
            State.LOCKED -> State.OFF
        }
        lastTapMs = nowMs
    }

    /** A character was typed: a one-shot shift ends. */
    fun typed() {
        if (state == State.ONCE) state = State.OFF
    }

    fun reset() {
        state = State.OFF
    }
}
