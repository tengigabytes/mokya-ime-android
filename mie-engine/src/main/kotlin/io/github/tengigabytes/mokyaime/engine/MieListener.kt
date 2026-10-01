// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime.engine

/** Cursor / candidate navigation direction (mirrors `mie::NavDir`). */
enum class NavDirection { LEFT, RIGHT, UP, DOWN }

/**
 * Engine events, delivered synchronously on the thread that called into
 * [MieEngine] (always the main thread in the IME). Implementations must not
 * call back into the engine from inside a callback.
 */
interface MieListener {
    /** Insert [text] at the cursor (replacing any composing text). */
    fun onCommit(text: String)

    /** DPAD pressed with no candidates showing: move the editor cursor. */
    fun onCursorMove(direction: NavDirection)

    /** DEL pressed with nothing pending: delete the character before the cursor. */
    fun onDeleteBefore()

    /** Pending composition, candidates or picker changed: re-query and redraw. */
    fun onCompositionChanged()
}
