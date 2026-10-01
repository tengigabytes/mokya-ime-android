// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime.input

import io.github.tengigabytes.mokyaime.engine.InputMode
import io.github.tengigabytes.mokyaime.engine.MokyaKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeyboardLayoutTest {

    @Test
    fun everyInputKeyAppearsOnce() {
        val keys = KeyboardLayout.rows.flatten()
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.containsAll(KeyLabels.inputKeys.map { it.keycode }))
        assertEquals(List(5) { 5 }, KeyboardLayout.coreRows.map { it.size })
    }

    @Test
    fun touchKeyboardHasNoArrowKeys() {
        assertEquals(listOf(MokyaKeys.KEY_OK, MokyaKeys.KEY_DEL), KeyboardLayout.rows.first())
        val arrows = setOf(MokyaKeys.KEY_LEFT, MokyaKeys.KEY_RIGHT, MokyaKeys.KEY_UP, MokyaKeys.KEY_DOWN)
        assertTrue(KeyboardLayout.rows.flatten().none { it in arrows })
    }

    @Test
    fun abcIsQwertyWithANumberRow() {
        val rows = KeyboardLayout.touchRows(InputMode.DIRECT)
        rows.forEach { row -> assertEquals(KeyboardLayout.ROW_UNITS, row.sumOf { it.weight.toDouble() }.toFloat()) }
        val texts = rows.flatten().filterIsInstance<TouchKey.Text>()
        assertEquals("1234567890", texts.take(10).joinToString("") { it.normal })
        assertEquals("!@#$%^&*()", texts.take(10).joinToString("") { it.shifted })
        val letters = texts.map { it.normal }.filter { it.length == 1 && it[0] in 'a'..'z' }
        assertEquals(('a'..'z').map { "$it" }.sorted(), letters.sorted())
        texts.filter { it.normal in letters }.forEach { assertEquals(it.normal.uppercase(), it.shifted) }
        val engineKeys = rows.flatten().filterIsInstance<TouchKey.Engine>().map { it.keycode }.toSet()
        assertEquals(setOf(MokyaKeys.KEY_DEL, MokyaKeys.KEY_OK, MokyaKeys.KEY_MODE), engineKeys)
        assertEquals(1, rows.flatten().count { it is TouchKey.Shift })
        assertTrue(texts.any { it.normal == " " })
    }

    @Test
    fun smartModesKeepTheHalfKeyboard() {
        for (mode in listOf(InputMode.SMART_ZH, InputMode.SMART_EN)) {
            val keys = KeyboardLayout.touchRows(mode).map { row -> row.map { (it as TouchKey.Engine).keycode } }
            assertEquals(KeyboardLayout.rows, keys)
        }
    }

    @Test
    fun inputKeyLabelsFollowMode() {
        assertEquals(KeyboardLayout.Label("ㄆㄊ", "q w"), KeyboardLayout.label(MokyaKeys.KEY_Q, InputMode.SMART_ZH))
        assertEquals(KeyboardLayout.Label("q w", "ㄆㄊ"), KeyboardLayout.label(MokyaKeys.KEY_Q, InputMode.SMART_EN))
        assertEquals(KeyboardLayout.Label("1 2", "ㄅㄉ"), KeyboardLayout.label(MokyaKeys.KEY_1, InputMode.DIRECT))
        assertEquals(KeyboardLayout.Label("ㄞㄢㄦ", "9 0"), KeyboardLayout.label(MokyaKeys.KEY_9, InputMode.SMART_ZH))
        // No Latin on the ㄡㄥ key: Bopomofo stays the main label.
        assertEquals(KeyboardLayout.Label("ㄡㄥ", ""), KeyboardLayout.label(MokyaKeys.KEY_BACKSLASH, InputMode.DIRECT))
    }

    @Test
    fun functionKeyLabels() {
        assertEquals("中", KeyboardLayout.label(MokyaKeys.KEY_MODE, InputMode.SMART_ZH).main)
        assertEquals("ABC", KeyboardLayout.label(MokyaKeys.KEY_MODE, InputMode.DIRECT).main)
        assertEquals("，", KeyboardLayout.label(MokyaKeys.KEY_SYM1, InputMode.SMART_ZH).main)
        assertEquals(",", KeyboardLayout.label(MokyaKeys.KEY_SYM1, InputMode.SMART_EN).main)
    }

    @Test
    fun onlySmartZhInputKeysDeferPress() {
        assertTrue(KeyboardLayout.defersPress(MokyaKeys.KEY_Q, InputMode.SMART_ZH))
        assertFalse(KeyboardLayout.defersPress(MokyaKeys.KEY_Q, InputMode.SMART_EN))
        assertFalse(KeyboardLayout.defersPress(MokyaKeys.KEY_SYM1, InputMode.SMART_ZH))
        assertFalse(KeyboardLayout.defersPress(MokyaKeys.KEY_DEL, InputMode.SMART_ZH))
    }
}
