// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime.fieldtest

/**
 * The device test checklist of debug builds' field-test page. Items come
 * from string-array resources, one entry per item written `id|label`; the id
 * keys the stored result and stays the same in every language.
 */
data class ChecklistItem(val id: String, val label: String) {
    companion object {
        /** Parses an `id|label` resource entry. */
        fun parse(entry: String): ChecklistItem {
            val id = entry.substringBefore('|', missingDelimiterValue = "").trim()
            val label = entry.substringAfter('|', missingDelimiterValue = "").trim()
            require(ID.matches(id) && label.isNotEmpty()) { "checklist entry must be id|label: $entry" }
            return ChecklistItem(id, label)
        }

        val ID = Regex("[a-z0-9]+(\\.[a-z0-9_]+)+")
    }
}

/** How an item is answered: pass / fail / skip, or a 1–5 rating. */
enum class ItemKind { CHECK, RATING }

enum class Verdict { PASS, FAIL, SKIP }

/**
 * What the tester recorded for one item. [verdict] answers CHECK items and
 * [rating] (1–5, 0 = none) RATING items; [note] is free text for either.
 */
data class ItemResult(
    val verdict: Verdict? = null,
    val rating: Int = 0,
    val note: String = "",
    val updatedAtMs: Long = 0,
) {
    init {
        require(rating in 0..5) { "rating must be 0..5" }
    }

    val isEmpty: Boolean get() = verdict == null && rating == 0 && note.isEmpty()

    /** Answered for an item of [kind]: a note alone does not count. */
    fun answered(kind: ItemKind): Boolean = when (kind) {
        ItemKind.CHECK -> verdict != null
        ItemKind.RATING -> rating > 0
    }
}

/**
 * Stores results as text, one line per item:
 * `id TAB verdict TAB rating TAB updatedAtMs TAB note`, with backslash,
 * tab and line breaks in the note escaped. Unreadable lines are skipped, so
 * a damaged store loses only those items.
 */
object ChecklistCodec {

    fun encode(results: Map<String, ItemResult>): String =
        results.entries
            .filter { !it.value.isEmpty }
            .joinToString("\n") { (id, r) ->
                listOf(id, r.verdict?.name.orEmpty(), "${r.rating}", "${r.updatedAtMs}", escape(r.note)).joinToString("\t")
            }

    fun decode(text: String): Map<String, ItemResult> {
        val results = LinkedHashMap<String, ItemResult>()
        for (line in text.lineSequence()) {
            val fields = line.split('\t')
            if (fields.size != 5 || !ChecklistItem.ID.matches(fields[0])) continue
            val verdict = if (fields[1].isEmpty()) null else Verdict.entries.firstOrNull { it.name == fields[1] } ?: continue
            val rating = fields[2].toIntOrNull()?.takeIf { it in 0..5 } ?: continue
            val updatedAt = fields[3].toLongOrNull() ?: continue
            results[fields[0]] = ItemResult(verdict, rating, unescape(fields[4]), updatedAt)
        }
        return results
    }

    private fun escape(s: String): String = buildString {
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(c)
            }
        }
    }

    private fun unescape(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i++]
            if (c != '\\' || i == s.length) {
                append(c)
                continue
            }
            when (val next = s[i++]) {
                't' -> append('\t')
                'n' -> append('\n')
                'r' -> append('\r')
                else -> append(next)
            }
        }
    }
}
