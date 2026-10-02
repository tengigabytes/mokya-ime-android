// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime.fieldtest

import java.util.Locale

/**
 * The field-test report, as Markdown: environment, per-section summary,
 * every answered item with its note, diagnostic counters and the trace.
 * The caller supplies all text in the tester's language; the table and
 * code-block syntax is the only fixed part.
 */
object FieldReport {

    data class Section(val title: String, val kind: ItemKind, val items: List<Pair<ChecklistItem, ItemResult?>>)

    /** Words used in the report, in the tester's language. */
    data class Labels(
        val environment: String,
        val summary: String,
        val results: String,
        val counters: String,
        val trace: String,
        val section: String,
        val answered: String,
        val pass: String,
        val fail: String,
        val skip: String,
        val averageRating: String,
        val notTested: String,
        val traceOff: String,
    )

    /** Pass / fail / skip counts, or the ratings, of one section. */
    data class Tally(val answered: Int, val total: Int, val pass: Int, val fail: Int, val skip: Int, val averageRating: Double?)

    fun tally(section: Section): Tally {
        val results = section.items.mapNotNull { it.second }
        val ratings = results.map { it.rating }.filter { it > 0 }
        return Tally(
            answered = results.count { it.answered(section.kind) },
            total = section.items.size,
            pass = results.count { it.verdict == Verdict.PASS },
            fail = results.count { it.verdict == Verdict.FAIL },
            skip = results.count { it.verdict == Verdict.SKIP },
            averageRating = if (ratings.isEmpty()) null else ratings.average(),
        )
    }

    /**
     * [trace] is null when diagnostics are off; [maxTraceLines] keeps the
     * newest lines only (a shared text has a size limit).
     */
    fun markdown(
        title: String,
        labels: Labels,
        environment: List<Pair<String, String>>,
        sections: List<Section>,
        counters: List<Pair<String, Int>>,
        trace: List<String>?,
        maxTraceLines: Int = Int.MAX_VALUE,
    ): String = buildString {
        appendLine("# $title")
        appendLine()
        appendLine("## ${labels.environment}")
        appendLine()
        appendTable(environment.map { listOf(it.first, it.second) }, header = null)

        appendLine("## ${labels.summary}")
        appendLine()
        val rows = sections.map { s ->
            val t = tally(s)
            val detail = when (s.kind) {
                ItemKind.CHECK -> "${labels.pass} ${t.pass} · ${labels.fail} ${t.fail} · ${labels.skip} ${t.skip}"
                ItemKind.RATING -> "${labels.averageRating} " + (t.averageRating?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "–")
            }
            listOf(s.title, "${t.answered}/${t.total}", detail)
        }
        appendTable(rows, header = listOf(labels.section, labels.answered, ""))

        appendLine("## ${labels.results}")
        appendLine()
        for (s in sections) {
            appendLine("### ${s.title}")
            appendLine()
            for ((item, result) in s.items) {
                appendLine("- ${mark(s.kind, result, labels)} ${item.label} `${item.id}`")
                result?.note?.takeIf { it.isNotBlank() }?.lines()?.forEach { appendLine("  > $it") }
            }
            appendLine()
        }

        appendLine("## ${labels.counters}")
        appendLine()
        appendTable(counters.map { listOf(it.first, "${it.second}") }, header = null)

        appendLine("## ${labels.trace}")
        appendLine()
        if (trace == null) {
            appendLine(labels.traceOff)
        } else {
            appendLine("```")
            trace.takeLast(maxTraceLines).forEach { appendLine(it) }
            appendLine("```")
        }
    }

    private fun mark(kind: ItemKind, result: ItemResult?, labels: Labels): String = when {
        kind == ItemKind.RATING && result != null && result.rating > 0 -> "[${result.rating}/5]"
        kind == ItemKind.CHECK && result?.verdict == Verdict.PASS -> "[${labels.pass}]"
        kind == ItemKind.CHECK && result?.verdict == Verdict.FAIL -> "[**${labels.fail}**]"
        kind == ItemKind.CHECK && result?.verdict == Verdict.SKIP -> "[${labels.skip}]"
        else -> "[${labels.notTested}]"
    }

    private fun StringBuilder.appendTable(rows: List<List<String>>, header: List<String>?) {
        val columns = header?.size ?: rows.firstOrNull()?.size ?: return
        appendLine("| " + (header ?: List(columns) { "" }).joinToString(" | ") { cell(it) } + " |")
        appendLine("|" + "---|".repeat(columns))
        rows.forEach { row -> appendLine("| " + row.joinToString(" | ") { cell(it) } + " |") }
        appendLine()
    }

    private fun cell(s: String) = s.replace("|", "\\|").replace("\n", " ")
}
