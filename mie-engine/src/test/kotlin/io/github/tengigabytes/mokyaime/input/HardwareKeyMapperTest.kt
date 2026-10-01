// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime.input

import io.github.tengigabytes.mokyaime.engine.InputMode
import io.github.tengigabytes.mokyaime.engine.MokyaKeys
import io.github.tengigabytes.mokyaime.input.HardwareAction.Abort
import io.github.tengigabytes.mokyaime.input.HardwareAction.Consume
import io.github.tengigabytes.mokyaime.input.HardwareAction.Engine
import io.github.tengigabytes.mokyaime.input.HardwareAction.Literal
import io.github.tengigabytes.mokyaime.input.HardwareAction.PassThrough
import kotlin.test.Test
import kotlin.test.assertEquals

class HardwareKeyMapperTest {

    private val zhIdle = EngineState(InputMode.SMART_ZH, composing = false, hasCandidates = false)
    private val zhBusy = EngineState(InputMode.SMART_ZH, composing = true, hasCandidates = true)
    private val enIdle = EngineState(InputMode.SMART_EN, composing = false, hasCandidates = false)
    private val enBusy = EngineState(InputMode.SMART_EN, composing = true, hasCandidates = true)
    private val direct = EngineState(InputMode.DIRECT, composing = false, hasCandidates = false)

    private fun ch(c: Char, shifted: Char = c, shift: Boolean = false, repeat: Boolean = false) =
        HardwareKey(baseChar = c, char = shifted, shift = shift, repeat = repeat)

    private fun sp(s: SpecialKey, ctrl: Boolean = false, repeat: Boolean = false) =
        HardwareKey(special = s, ctrl = ctrl, repeat = repeat)

    /** The standard Dachen (大千) layout, key → Bopomofo. */
    private val dachenLayout = mapOf(
        '1' to "ㄅ", '2' to "ㄉ", '3' to "ˇ", '4' to "ˋ", '5' to "ㄓ", '6' to "ˊ", '7' to "˙", '8' to "ㄚ",
        '9' to "ㄞ", '0' to "ㄢ", '-' to "ㄦ",
        'q' to "ㄆ", 'w' to "ㄊ", 'e' to "ㄍ", 'r' to "ㄐ", 't' to "ㄔ", 'y' to "ㄗ", 'u' to "ㄧ", 'i' to "ㄛ",
        'o' to "ㄟ", 'p' to "ㄣ",
        'a' to "ㄇ", 's' to "ㄋ", 'd' to "ㄎ", 'f' to "ㄑ", 'g' to "ㄕ", 'h' to "ㄘ", 'j' to "ㄨ", 'k' to "ㄜ",
        'l' to "ㄠ", ';' to "ㄤ",
        'z' to "ㄈ", 'x' to "ㄌ", 'c' to "ㄏ", 'v' to "ㄒ", 'b' to "ㄖ", 'n' to "ㄙ", 'm' to "ㄩ", ',' to "ㄝ",
        '.' to "ㄡ", '/' to "ㄥ",
    )

    @Test
    fun dachenTableTypesTheRightPhoneme() {
        assertEquals(dachenLayout.keys, HardwareKeyMapper.dachen.keys)
        for ((key, bopomofo) in dachenLayout) {
            val (keycode, index) = HardwareKeyMapper.dachen.getValue(key)
            assertEquals(bopomofo, KeyLabels.inputKey(keycode)!!.phonemes[index], "key '$key'")
        }
        // 41 Dachen keys cover every phoneme slot of the 20 half-keys exactly once.
        assertEquals(
            KeyLabels.inputKeys.sumOf { it.phonemes.size },
            HardwareKeyMapper.dachen.values.toSet().size,
        )
    }

    @Test
    fun smartZhSendsExactPhonemes() {
        assertEquals(Engine(MokyaKeys.KEY_Q, MokyaKeys.keyFlagPhoneme(1)), HardwareKeyMapper.map(ch('w'), zhIdle))
        assertEquals(Engine(MokyaKeys.KEY_9, MokyaKeys.keyFlagPhoneme(2)), HardwareKeyMapper.map(ch('-'), zhBusy))
        assertEquals(Engine(MokyaKeys.KEY_BACKSLASH, MokyaKeys.keyFlagPhoneme(0)), HardwareKeyMapper.map(ch('.'), zhIdle))
    }

    @Test
    fun smartZhShiftAndPunctuation() {
        assertEquals(Literal("，"), HardwareKeyMapper.map(ch(',', '<', shift = true), zhBusy))
        assertEquals(Literal("？"), HardwareKeyMapper.map(ch('/', '?', shift = true), zhIdle))
        assertEquals(Literal("Q"), HardwareKeyMapper.map(ch('q', 'Q', shift = true), zhBusy))
        assertEquals(Literal("@"), HardwareKeyMapper.map(ch('2', '@', shift = true), zhIdle))
        assertEquals(Literal("「"), HardwareKeyMapper.map(ch('['), zhIdle))
        assertEquals(Literal("="), HardwareKeyMapper.map(ch('='), zhIdle))
    }

    @Test
    fun smartEnPredictsLettersAndTypesTheRest() {
        assertEquals(Engine(MokyaKeys.KEY_Q), HardwareKeyMapper.map(ch('w'), enIdle))
        assertEquals(Engine(MokyaKeys.KEY_L), HardwareKeyMapper.map(ch('l'), enBusy))
        assertEquals(Literal("W"), HardwareKeyMapper.map(ch('w', 'W', shift = true), enIdle))
        assertEquals(Literal("7"), HardwareKeyMapper.map(ch('7'), enBusy))
        assertEquals(Literal("."), HardwareKeyMapper.map(ch('.'), enBusy))
    }

    @Test
    fun directModeAndShortcutsPassThrough() {
        assertEquals(PassThrough, HardwareKeyMapper.map(ch('q'), direct))
        assertEquals(PassThrough, HardwareKeyMapper.map(sp(SpecialKey.ENTER), direct))
        assertEquals(PassThrough, HardwareKeyMapper.map(HardwareKey(baseChar = 'c', ctrl = true), zhBusy))
        assertEquals(PassThrough, HardwareKeyMapper.map(HardwareKey(), zhBusy))   // e.g. F1
    }

    @Test
    fun numericKeypadTypesDigits() {
        val numpad7 = HardwareKey(baseChar = null, char = '7')
        assertEquals(Literal("7"), HardwareKeyMapper.map(numpad7, zhBusy))
        assertEquals(Literal("7"), HardwareKeyMapper.map(numpad7, enIdle))
        assertEquals(PassThrough, HardwareKeyMapper.map(numpad7, direct))
    }

    @Test
    fun modeSwitchFromAnyMode() {
        for (state in listOf(zhIdle, enBusy, direct)) {
            assertEquals(Engine(MokyaKeys.KEY_MODE), HardwareKeyMapper.map(sp(SpecialKey.SPACE, ctrl = true), state))
            assertEquals(Engine(MokyaKeys.KEY_MODE), HardwareKeyMapper.map(sp(SpecialKey.LANGUAGE_SWITCH), state))
        }
        assertEquals(Consume, HardwareKeyMapper.map(sp(SpecialKey.LANGUAGE_SWITCH, repeat = true), zhIdle))
    }

    @Test
    fun controlKeysBelongToEngineOnlyWhileComposing() {
        assertEquals(PassThrough, HardwareKeyMapper.map(sp(SpecialKey.ENTER), zhIdle))
        assertEquals(PassThrough, HardwareKeyMapper.map(sp(SpecialKey.DEL), zhIdle))
        assertEquals(PassThrough, HardwareKeyMapper.map(sp(SpecialKey.SPACE), enIdle))
        assertEquals(Engine(MokyaKeys.KEY_OK), HardwareKeyMapper.map(sp(SpecialKey.ENTER), zhBusy))
        assertEquals(Engine(MokyaKeys.KEY_DEL), HardwareKeyMapper.map(sp(SpecialKey.DEL, repeat = true), zhBusy))
        assertEquals(Engine(MokyaKeys.KEY_SPACE), HardwareKeyMapper.map(sp(SpecialKey.SPACE), zhBusy))
        assertEquals(Engine(MokyaKeys.KEY_DOWN), HardwareKeyMapper.map(sp(SpecialKey.DOWN), enBusy))
        assertEquals(Engine(MokyaKeys.KEY_TAB), HardwareKeyMapper.map(sp(SpecialKey.TAB), zhBusy))
        assertEquals(Abort, HardwareKeyMapper.map(sp(SpecialKey.ESCAPE), zhBusy))
        assertEquals(PassThrough, HardwareKeyMapper.map(sp(SpecialKey.ESCAPE), zhIdle))
    }

    @Test
    fun heldCharacterKeysDoNotRepeat() {
        assertEquals(Consume, HardwareKeyMapper.map(ch('q', repeat = true), zhBusy))
        assertEquals(Consume, HardwareKeyMapper.map(ch('7', repeat = true), enBusy))
        assertEquals(PassThrough, HardwareKeyMapper.map(ch('q', repeat = true), direct))
    }
}
