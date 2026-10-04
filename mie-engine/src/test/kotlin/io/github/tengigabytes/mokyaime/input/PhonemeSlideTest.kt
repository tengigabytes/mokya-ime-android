// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PhonemeSlideTest {

    @Test
    fun leftPicksTheFirstSymbolAndRightTheLast() {
        assertEquals(0, PhonemeSlide.picked(count = 2, dx = -30f, threshold = 20f))
        assertEquals(1, PhonemeSlide.picked(count = 2, dx = 30f, threshold = 20f))
        assertEquals(0, PhonemeSlide.picked(count = 3, dx = -30f, threshold = 20f))
        assertEquals(2, PhonemeSlide.picked(count = 3, dx = 30f, threshold = 20f))
    }

    @Test
    fun nothingIsPickedNearTheTouchPoint() {
        assertNull(PhonemeSlide.picked(count = 2, dx = 19f, threshold = 20f))
        assertNull(PhonemeSlide.picked(count = 2, dx = -19f, threshold = 20f))
        assertEquals(1, PhonemeSlide.picked(count = 2, dx = 20f, threshold = 20f))
        assertNull(PhonemeSlide.picked(count = 0, dx = 100f, threshold = 20f))
    }

    @Test
    fun keyWithOneSymbolGivesItEitherWay() {
        assertEquals(0, PhonemeSlide.picked(count = 1, dx = 100f, threshold = 20f))
        assertEquals(0, PhonemeSlide.picked(count = 1, dx = -100f, threshold = 20f))
        assertNull(PhonemeSlide.picked(count = 1, dx = 5f, threshold = 20f))
        assertEquals(0, PhonemeSlide.held(1))
    }

    @Test
    fun holdingGivesTheFirstOfTwoAndTheMiddleOfThree() {
        assertEquals(0, PhonemeSlide.held(2))
        assertEquals(1, PhonemeSlide.held(3))
    }
}
