// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime.fieldtest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TraceRingTest {

    @Test
    fun keepsTheNewestLines() {
        val ring = TraceRing(3)
        (1..5).forEach { ring.add("line $it") }
        assertEquals(listOf("line 3", "line 4", "line 5"), ring.lines())
        assertEquals(5, ring.total)
    }

    @Test
    fun countsMarkersOverTheWholeSession() {
        val ring = TraceRing(2, linkedMapOf("revealed" to "made visible", "dropped" to "discardComposition"))
        ring.add("1 candidates area was 4, made visible")
        ring.add("2 key 3")
        ring.add("3 candidates area was 4, made visible")
        ring.add("4 key 5")
        assertEquals(mapOf("revealed" to 2, "dropped" to 0), ring.counts())
        assertEquals(listOf("revealed", "dropped"), ring.counts().keys.toList())   // marker order
    }

    @Test
    fun clearResetsLinesAndCounts() {
        val ring = TraceRing(2, mapOf("m" to "x"))
        ring.add("x")
        ring.clear()
        assertEquals(emptyList(), ring.lines())
        assertEquals(0, ring.total)
        assertEquals(mapOf("m" to 0), ring.counts())
    }

    @Test
    fun capacityMustBePositive() {
        assertFailsWith<IllegalArgumentException> { TraceRing(0) }
    }
}
