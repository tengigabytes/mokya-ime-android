// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime.fieldtest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChecklistTest {

    @Test
    fun parsesIdAndLabel() {
        assertEquals(ChecklistItem("layout.dark", "深色模式 | 版面"), ChecklistItem.parse("layout.dark|深色模式 | 版面"))
        for (bad in listOf("no label", "layout.dark|", "Layout.Dark|x", "nodot|x", "|x")) {
            assertFailsWith<IllegalArgumentException>(bad) { ChecklistItem.parse(bad) }
        }
    }

    @Test
    fun codecRoundTripsNotesWithSpecialCharacters() {
        val results = mapOf(
            "apps.line" to ItemResult(Verdict.FAIL, note = "LINE 聊天\n第二行\ttab \\ 反斜線 \\n 字面 😀", updatedAtMs = 1_700_000_000_000),
            "ergo.haptics" to ItemResult(rating = 4, updatedAtMs = 5),
            "touch.page" to ItemResult(Verdict.PASS),
            "Bad.Id" to ItemResult(Verdict.PASS),   // not a valid id: skipped on decode
        )
        val decoded = ChecklistCodec.decode(ChecklistCodec.encode(results))
        assertEquals(results - "Bad.Id", decoded)
    }

    @Test
    fun emptyResultsAreNotStored() {
        assertEquals("", ChecklistCodec.encode(mapOf("apps.line" to ItemResult())))
    }

    @Test
    fun damagedLinesAreSkipped() {
        val text = listOf(
            "apps.line\tPASS\t0\t1\tok",
            "broken line",
            "apps.gmail\tMAYBE\t0\t1\t",
            "apps.notes\t\t9\t1\t",
            "hw.esc\tFAIL\t0\t2\tx",
        ).joinToString("\n")
        assertEquals(setOf("apps.line", "hw.esc"), ChecklistCodec.decode(text).keys)
    }

    @Test
    fun answeredDependsOnTheKind() {
        val noteOnly = ItemResult(note = "x")
        assertFalse(noteOnly.answered(ItemKind.CHECK))
        assertFalse(noteOnly.answered(ItemKind.RATING))
        assertTrue(ItemResult(Verdict.SKIP).answered(ItemKind.CHECK))
        assertTrue(ItemResult(rating = 1).answered(ItemKind.RATING))
        assertFailsWith<IllegalArgumentException> { ItemResult(rating = 6) }
    }
}
