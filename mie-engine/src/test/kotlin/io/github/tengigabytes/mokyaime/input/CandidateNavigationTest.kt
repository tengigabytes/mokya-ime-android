// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime.input

import kotlin.test.Test
import kotlin.test.assertEquals

class CandidateNavigationTest {
    @Test
    fun pageJump() {
        assertEquals(5, CandidateNavigation.pageJump(0, 12, down = true))
        assertEquals(11, CandidateNavigation.pageJump(9, 12, down = true))
        assertEquals(2, CandidateNavigation.pageJump(7, 12, down = false))
        assertEquals(0, CandidateNavigation.pageJump(3, 12, down = false))
        assertEquals(-1, CandidateNavigation.pageJump(0, 0, down = true))
    }
}
