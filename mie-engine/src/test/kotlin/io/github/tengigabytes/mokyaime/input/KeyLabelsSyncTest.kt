// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime.input

import io.github.tengigabytes.mokyaime.engine.MokyaKeys
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** KeyLabels must mirror kKeyTable in libmie's src/ime_keys.cpp. */
class KeyLabelsSyncTest {

    private fun strings(list: String): List<String> =
        Regex("\"([^\"]*)\"").findAll(list).map { it.groupValues[1] }.toList()

    @Test
    fun matchesKeyTable() {
        val source = File(System.getProperty("mokya.keyTableSource") ?: error("mokya.keyTableSource not set"))
        val table = source.readText().substringAfter("kKeyTable[20] = {").substringBefore("};")
        val entry = Regex("""\{\s*MOKYA_(KEY_\w+)\s*,\s*\{([^}]*)\}\s*,\s*\{([^}]*)\}\s*,\s*\{([^}]*)\}\s*\}""")
        val keycodes = MokyaKeys::class.java.declaredFields.associate { it.name to it }

        val fromSource = entry.findAll(table).map { m ->
            InputKey(
                keycode = keycodes.getValue(m.groupValues[1]).getInt(null),
                phonemes = strings(m.groupValues[2]),
                digits = strings(m.groupValues[3]),
                letters = strings(m.groupValues[4]),
            )
        }.toList()

        assertEquals(20, fromSource.size)
        assertEquals(fromSource, KeyLabels.inputKeys)
    }
}
