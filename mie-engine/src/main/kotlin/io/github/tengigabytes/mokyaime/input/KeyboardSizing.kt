// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime.input

/**
 * How tall the on-screen keyboard is drawn. At full size the half-keyboard
 * has a [ACTION_ROW_DP] action row and [KEY_ROW_DP] rows for the 5×5 core
 * (304 dp), the candidate strip above it is [STRIP_DP] and a [GAP_DP] gap
 * frames the keys. Every layout (QWERTY, symbol and picker pages) shares
 * that height.
 *
 * On a short screen (a phone in landscape) that would leave the app almost
 * nothing, so the rows shrink, all by one [scale], until keyboard, gap and
 * strip take [MAX_SCREEN_SHARE] of the screen height, but never below
 * [MIN_SCALE] of their full size. Where everything fits at full size
 * (phones in portrait, tablets) the scale is exactly 1.
 */
object KeyboardSizing {
    const val ACTION_ROW_DP = 44f
    const val KEY_ROW_DP = 52f
    const val GAP_DP = 3f
    const val STRIP_DP = 44f

    /** Most of the screen height the keyboard and the strip take when they have to shrink. */
    const val MAX_SCREEN_SHARE = 0.6f

    /** The rows never shrink below this share of their full height. */
    const val MIN_SCALE = 0.6f

    /** The half-keyboard's rows at full size: the action row and the core rows. */
    val fullRowsDp: Float = ACTION_ROW_DP + KEY_ROW_DP * KeyboardLayout.coreRows.size

    /**
     * The factor every row height (and the labels in it) is drawn at, for a
     * screen [screenHeightDp] tall; 1 when the height is unknown (0 or less).
     */
    fun scale(screenHeightDp: Float): Float {
        if (screenHeightDp <= 0f) return 1f
        val roomForRows = screenHeightDp * MAX_SCREEN_SHARE - STRIP_DP - GAP_DP
        if (fullRowsDp <= roomForRows) return 1f
        return maxOf(roomForRows / fullRowsDp, MIN_SCALE)
    }
}
