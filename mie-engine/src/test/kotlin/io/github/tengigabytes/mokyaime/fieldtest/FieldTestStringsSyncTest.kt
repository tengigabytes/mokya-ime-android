// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime.fieldtest

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.w3c.dom.Element

/**
 * The field-test page's strings (app/src/debug/res): every language has the
 * same strings and checklist arrays, the same item ids in the same order,
 * and every entry parses as `id|label`.
 */
class FieldTestStringsSyncTest {

    private val res = File(System.getProperty("mokya.fieldTestRes") ?: error("mokya.fieldTestRes not set"))

    private class Strings(val names: Set<String>, val arrays: Map<String, List<String>>)

    private fun load(dir: String): Strings {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, "$dir/strings.xml"))
        fun elements(tag: String): List<Element> {
            val list = doc.getElementsByTagName(tag)
            return (0 until list.length).map { list.item(it) as Element }
        }
        return Strings(
            names = elements("string").map { it.getAttribute("name") }.toSet(),
            arrays = elements("string-array").associate { array ->
                val items = array.getElementsByTagName("item")
                array.getAttribute("name") to (0 until items.length).map { items.item(it).textContent }
            },
        )
    }

    @Test
    fun languagesMatch() {
        val en = load("values")
        val zh = load("values-zh-rTW")
        assertEquals(en.names, zh.names)
        assertEquals(en.arrays.keys, zh.arrays.keys)
        assertEquals(9, en.arrays.size)
        assertTrue(en.arrays.keys.all { it.startsWith("ft_items_") })

        val ids = ArrayList<String>()
        for ((name, entries) in en.arrays) {
            val enIds = entries.map { ChecklistItem.parse(it).id }
            val zhIds = zh.arrays.getValue(name).map { ChecklistItem.parse(it).id }
            assertEquals(enIds, zhIds, name)
            ids += enIds
        }
        assertEquals(ids.size, ids.toSet().size, "duplicate checklist ids")
    }
}
