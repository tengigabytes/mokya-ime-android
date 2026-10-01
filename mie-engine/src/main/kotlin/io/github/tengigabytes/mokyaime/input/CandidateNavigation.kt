// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime.input

import io.github.tengigabytes.mokyaime.engine.MieEngine

/**
 * Up / Down on the candidate list. The engine leaves these keys to the view
 * layer (ImeLogic::handle_dpad), which knows its own layout; the Android
 * strip is a single row, so Up / Down move by one engine page.
 */
object CandidateNavigation {
    fun pageJump(selected: Int, count: Int, down: Boolean): Int {
        if (count <= 0) return -1
        val target = if (down) selected + MieEngine.PAGE_SIZE else selected - MieEngine.PAGE_SIZE
        return target.coerceIn(0, count - 1)
    }
}
