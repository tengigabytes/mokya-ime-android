// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KeyboardSizingTest {

    /** Rows, gap and strip at [scale], in dp. */
    private fun total(scale: Float) = KeyboardSizing.fullRowsDp * scale + KeyboardSizing.GAP_DP + KeyboardSizing.STRIP_DP

    @Test
    fun fullSizeIsTheHalfKeyboardsRows() {
        assertEquals(304f, KeyboardSizing.fullRowsDp)   // 44 + 5 × 52
    }

    @Test
    fun portraitPhonesAndTabletsKeepTheFullSize() {
        // Heights of phones in portrait and of tablets either way round.
        for (height in listOf(592f, 640f, 683f, 732f, 851f, 915f, 600f, 800f, 1280f)) {
            assertEquals(1f, KeyboardSizing.scale(height), "height $height dp")
        }
    }

    @Test
    fun theFullSizeIsKeptWhileItFitsSixtyPercent() {
        // 304 + 3 + 44 = 351 dp is 60 % of 585 dp.
        assertEquals(1f, KeyboardSizing.scale(586f))
        assertTrue(KeyboardSizing.scale(580f) < 1f)
    }

    @Test
    fun landscapePhonesShrinkTheRowsToSixtyPercent() {
        for (height in listOf(411f, 430f, 500f)) {
            val scale = KeyboardSizing.scale(height)
            assertTrue(scale < 1f && scale > KeyboardSizing.MIN_SCALE, "height $height dp: $scale")
            assertEquals(height * KeyboardSizing.MAX_SCREEN_SHARE, total(scale), 0.01f, "height $height dp")
        }
    }

    @Test
    fun rowsNeverShrinkBelowTheMinimum() {
        // 60 % of 360 dp leaves 169 dp for the rows: 0.56 of their full height.
        assertEquals(KeyboardSizing.MIN_SCALE, KeyboardSizing.scale(360f))
        assertEquals(KeyboardSizing.MIN_SCALE, KeyboardSizing.scale(200f))
    }

    @Test
    fun aTallerScreenNeverGetsSmallerRows() {
        var last = 0f
        for (height in 100..1500 step 5) {
            val scale = KeyboardSizing.scale(height.toFloat())
            assertTrue(scale >= last, "height $height dp: $scale after $last")
            last = scale
        }
    }

    @Test
    fun anUnknownHeightKeepsTheFullSize() {
        assertEquals(1f, KeyboardSizing.scale(0f))   // Configuration.SCREEN_HEIGHT_DP_UNDEFINED
    }
}
