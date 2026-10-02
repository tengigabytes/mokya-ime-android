// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime.input

/** A key of the on-screen keyboard; [weight] is its width in key units. */
sealed interface TouchKey {
    val weight: Float

    /** Sends MIE key edges to the engine, with MokyaLora's timing ([PressTracker]). */
    data class Engine(val keycode: Int, override val weight: Float = 1f) : TouchKey

    /** Types [normal], or [shifted] while Shift is on, straight into the editor. */
    data class Text(val normal: String, val shifted: String, override val weight: Float = 1f) : TouchKey

    /** Shift for the [Text] keys ([ShiftKey]). */
    data class Shift(override val weight: Float) : TouchKey

    /** Switches ABC between its letter and symbol pages. */
    data class Page(override val weight: Float = 1f) : TouchKey
}
