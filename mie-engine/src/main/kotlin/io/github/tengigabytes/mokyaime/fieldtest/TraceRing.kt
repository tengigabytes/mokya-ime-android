// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime.fieldtest

/**
 * The last [capacity] trace lines, plus lifetime counts of lines containing
 * each of [markers] (name → substring), so a report can say how often an
 * event happened even after its lines were dropped. Not thread-safe: the
 * IME traces on the main thread only.
 */
class TraceRing(
    val capacity: Int,
    private val markers: Map<String, String> = emptyMap(),
) {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val lines = ArrayDeque<String>()
    private val counts = LinkedHashMap<String, Int>().apply { markers.keys.forEach { put(it, 0) } }

    /** Lines added since the start or the last [clear], dropped ones included. */
    var total = 0L
        private set

    fun add(line: String) {
        if (lines.size == capacity) lines.removeFirst()
        lines.addLast(line)
        total++
        for ((name, marker) in markers) {
            if (marker in line) counts[name] = counts.getValue(name) + 1
        }
    }

    /** The kept lines, oldest first. */
    fun lines(): List<String> = lines.toList()

    /** Marker counts in [markers] order. */
    fun counts(): Map<String, Int> = LinkedHashMap(counts)

    fun clear() {
        lines.clear()
        total = 0
        counts.keys.forEach { counts[it] = 0 }
    }
}
