// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime.input

import kotlin.test.Test
import kotlin.test.assertEquals

class EnterActionTest {

    @Test
    fun followsTheEditorAction() {
        assertEquals(EnterAction.SEND, EnterAction.of(4))
        assertEquals(EnterAction.SEARCH, EnterAction.of(3))
        assertEquals(EnterAction.GO, EnterAction.of(2))
        assertEquals(EnterAction.NEXT, EnterAction.of(5))
        assertEquals(EnterAction.DONE, EnterAction.of(6))
        assertEquals(EnterAction.PREVIOUS, EnterAction.of(7))
        // IME_ACTION_DONE with other flags (IME_FLAG_NO_EXTRACT_UI) set.
        assertEquals(EnterAction.DONE, EnterAction.of(0x10000000 or 6))
    }

    @Test
    fun noActionMeansANewLine() {
        assertEquals(EnterAction.NEWLINE, EnterAction.of(0))   // unspecified
        assertEquals(EnterAction.NEWLINE, EnterAction.of(1))   // none
        // Multi-line fields ask Enter not to run their action.
        assertEquals(EnterAction.NEWLINE, EnterAction.of(0x40000000 or 4))
    }
}
