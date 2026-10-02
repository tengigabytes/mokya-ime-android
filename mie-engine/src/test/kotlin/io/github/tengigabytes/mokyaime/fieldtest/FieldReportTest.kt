// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime.fieldtest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FieldReportTest {

    private val labels = FieldReport.Labels(
        environment = "Env", summary = "Summary", results = "Results", counters = "Counters", trace = "Trace",
        section = "Section", answered = "Answered", pass = "Pass", fail = "Fail", skip = "Skip",
        averageRating = "Avg", notTested = "Untested", traceOff = "Trace was off",
    )

    private val checks = FieldReport.Section(
        "1. Layout", ItemKind.CHECK,
        listOf(
            ChecklistItem("layout.dark", "Dark mode") to ItemResult(Verdict.PASS),
            ChecklistItem("layout.rotate", "Rotate") to ItemResult(Verdict.FAIL, note = "strip gone\nafter 2 turns"),
            ChecklistItem("layout.unlock", "Unlock") to ItemResult(note = "not yet"),
            ChecklistItem("layout.font", "Font") to null,
        ),
    )
    private val ratings = FieldReport.Section(
        "8. Ergonomics", ItemKind.RATING,
        listOf(
            ChecklistItem("ergo.a", "A") to ItemResult(rating = 4),
            ChecklistItem("ergo.b", "B") to ItemResult(rating = 3),
            ChecklistItem("ergo.c", "C") to null,
        ),
    )

    @Test
    fun tallies() {
        assertEquals(FieldReport.Tally(2, 4, 1, 1, 0, null), FieldReport.tally(checks))
        assertEquals(FieldReport.Tally(2, 3, 0, 0, 0, 3.5), FieldReport.tally(ratings))
    }

    @Test
    fun markdownHasEveryPart() {
        val md = FieldReport.markdown(
            "Report", labels, listOf("android" to "16 (API 36)", "odd" to "a|b"), listOf(checks, ratings),
            listOf("strip_area_revealed" to 2), trace = listOf("1 a", "2 b", "3 c"), maxTraceLines = 2,
        )
        assertTrue(md.startsWith("# Report\n"))
        assertTrue("| android | 16 (API 36) |" in md)
        assertTrue("| odd | a\\|b |" in md)   // table cells escape |
        assertTrue("| 1. Layout | 2/4 | Pass 1 · Fail 1 · Skip 0 |" in md)
        assertTrue("| 8. Ergonomics | 2/3 | Avg 3.5 |" in md)
        assertTrue("- [**Fail**] Rotate `layout.rotate`\n  > strip gone\n  > after 2 turns\n" in md)
        assertTrue("- [Untested] Unlock `layout.unlock`\n  > not yet\n" in md)
        assertTrue("- [4/5] A `ergo.a`" in md)
        assertTrue("| strip_area_revealed | 2 |" in md)
        assertTrue("```\n2 b\n3 c\n```" in md)   // newest lines only
        assertTrue("1 a" !in md)
    }

    @Test
    fun saysWhenTheTraceWasOff() {
        val md = FieldReport.markdown("R", labels, emptyList(), listOf(checks), emptyList(), trace = null)
        assertTrue(md.trimEnd().endsWith("Trace was off"))
    }
}
