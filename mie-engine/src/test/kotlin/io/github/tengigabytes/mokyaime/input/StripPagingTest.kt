// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StripPagingTest {

    // Ten candidates 100 px wide in a 250 px viewport: 2½ fit on screen.
    private val starts = List(10) { it * 100 }
    private val width = 1000
    private val viewport = 250

    @Test
    fun nextPageStartsAtTheCandidateCutOff() {
        assertEquals(200, StripPaging.next(starts, width, scrollX = 0, viewport = viewport))
        assertEquals(400, StripPaging.next(starts, width, scrollX = 200, viewport = viewport))
    }

    @Test
    fun nextPageStopsAtTheEnd() {
        assertEquals(750, StripPaging.next(starts, width, scrollX = 600, viewport = viewport))
        assertEquals(750, StripPaging.next(starts, width, scrollX = 750, viewport = viewport))
        assertFalse(StripPaging.canPageForward(width, scrollX = 750, viewport = viewport))
        assertTrue(StripPaging.canPageForward(width, scrollX = 600, viewport = viewport))
    }

    @Test
    fun previousPageEndsWhereTheCurrentOneStarts() {
        // From 750 the previous page shows 500..750: candidates 5 and 6 in full.
        assertEquals(500, StripPaging.previous(starts, width, scrollX = 750, viewport = viewport))
        assertEquals(200, StripPaging.previous(starts, width, scrollX = 400, viewport = viewport))
        assertEquals(0, StripPaging.previous(starts, width, scrollX = 200, viewport = viewport))
        assertFalse(StripPaging.canPageBack(0))
        assertTrue(StripPaging.canPageBack(1))
    }

    @Test
    fun forwardThenBackReturnsToTheStart() {
        var x = 0
        repeat(3) { x = StripPaging.next(starts, width, x, viewport) }
        repeat(3) { x = StripPaging.previous(starts, width, x, viewport) }
        assertEquals(0, x)
    }

    @Test
    fun candidateWiderThanTheViewportScrollsByAViewport() {
        val wide = listOf(0, 50, 400)   // the second candidate is 350 px wide
        assertEquals(150, StripPaging.next(wide, 450, scrollX = 50, viewport = 100))
        assertEquals(50, StripPaging.previous(wide, 450, scrollX = 150, viewport = 100))
    }

    @Test
    fun contentThatFitsDoesNotScroll() {
        val few = listOf(0, 60, 120)
        assertEquals(0, StripPaging.next(few, 180, scrollX = 0, viewport = 250))
        assertFalse(StripPaging.canPageForward(180, scrollX = 0, viewport = 250))
        assertEquals(0, StripPaging.next(emptyList(), 0, scrollX = 0, viewport = 250))
    }
}
