// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime.input

import io.github.tengigabytes.mokyaime.input.ShiftKey.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ShiftKeyTest {

    @Test
    fun tapShiftsOneCharacter() {
        val shift = ShiftKey()
        shift.tap(1_000)
        assertEquals(State.ONCE, shift.state)
        shift.typed()
        assertEquals(State.OFF, shift.state)
    }

    @Test
    fun doubleTapLocksUntilTappedAgain() {
        val shift = ShiftKey()
        shift.tap(1_000)
        shift.tap(1_200)
        assertEquals(State.LOCKED, shift.state)
        shift.typed()
        shift.typed()
        assertEquals(State.LOCKED, shift.state)
        shift.tap(5_000)
        assertFalse(shift.active)
    }

    @Test
    fun slowSecondTapTurnsShiftOff() {
        val shift = ShiftKey()
        shift.tap(1_000)
        shift.tap(1_000 + ShiftKey.DOUBLE_TAP_MS + 1)
        assertEquals(State.OFF, shift.state)
    }

    @Test
    fun resetClearsALock() {
        val shift = ShiftKey()
        shift.tap(1_000)
        shift.tap(1_100)
        shift.reset()
        assertEquals(State.OFF, shift.state)
    }
}
