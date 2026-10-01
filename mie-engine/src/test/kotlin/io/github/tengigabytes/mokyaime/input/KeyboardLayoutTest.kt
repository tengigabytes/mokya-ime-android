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
