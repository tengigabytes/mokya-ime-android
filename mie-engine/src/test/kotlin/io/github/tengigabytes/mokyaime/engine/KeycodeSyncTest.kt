// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** MokyaKeys must mirror libmie's include/mie/keycode.h exactly. */
class KeycodeSyncTest {

    @Test
    fun mokyaKeysMatchesKeycodeHeader() {
        val header = File(System.getProperty("mokya.keycodeHeader") ?: error("mokya.keycodeHeader not set"))
        val define = Regex("""#define\s+MOKYA_(KEY_\w+)\s+\(\((?:mokya_keycode_t|uint8_t)\)(0x[0-9A-Fa-f]+)\)""")
        val fromHeader = define.findAll(header.readText())
            .associate { it.groupValues[1] to it.groupValues[2].removePrefix("0x").toInt(16) }

        val fromKotlin = MokyaKeys::class.java.declaredFields
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
            .associate { it.name to it.getInt(null) }

        assertEquals(fromHeader.toSortedMap(), fromKotlin.toSortedMap())
    }
}
