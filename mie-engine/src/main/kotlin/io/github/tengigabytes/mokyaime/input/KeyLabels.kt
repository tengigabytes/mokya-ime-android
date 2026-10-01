// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime.input

import io.github.tengigabytes.mokyaime.engine.MokyaKeys

/**
 * Labels of one of the 20 half-keyboard input keys.
 *
 * @property phonemes Bopomofo symbols, primary first (index = phoneme index
 *   used by `MokyaKeys.keyFlagPhoneme`).
 * @property digits digits on row 0 keys (SmartEn / Direct multi-tap order).
 * @property letters Direct-mode multi-tap order, e.g. `q w Q W`.
 */
data class InputKey(
    val keycode: Int,
    val phonemes: List<String>,
    val digits: List<String>,
    val letters: List<String>,
)

/**
 * The 20 input keys, mirroring `kKeyTable` in libmie's `src/ime_keys.cpp`
 * (the source of truth); KeyLabelsSyncTest fails if they drift apart.
 */
object KeyLabels {
    val inputKeys: List<InputKey> = listOf(
        InputKey(MokyaKeys.KEY_1, listOf("ㄅ", "ㄉ"), listOf("1", "2"), emptyList()),
        InputKey(MokyaKeys.KEY_3, listOf("ˇ", "ˋ"), listOf("3", "4"), emptyList()),
        InputKey(MokyaKeys.KEY_5, listOf("ㄓ", "ˊ"), listOf("5", "6"), emptyList()),
        InputKey(MokyaKeys.KEY_7, listOf("˙", "ㄚ"), listOf("7", "8"), emptyList()),
        InputKey(MokyaKeys.KEY_9, listOf("ㄞ", "ㄢ", "ㄦ"), listOf("9", "0"), emptyList()),
        InputKey(MokyaKeys.KEY_Q, listOf("ㄆ", "ㄊ"), emptyList(), listOf("q", "w", "Q", "W")),
        InputKey(MokyaKeys.KEY_E, listOf("ㄍ", "ㄐ"), emptyList(), listOf("e", "r", "E", "R")),
        InputKey(MokyaKeys.KEY_T, listOf("ㄔ", "ㄗ"), emptyList(), listOf("t", "y", "T", "Y")),
        InputKey(MokyaKeys.KEY_U, listOf("ㄧ", "ㄛ"), emptyList(), listOf("u", "i", "U", "I")),
        InputKey(MokyaKeys.KEY_O, listOf("ㄟ", "ㄣ"), emptyList(), listOf("o", "p", "O", "P")),
        InputKey(MokyaKeys.KEY_A, listOf("ㄇ", "ㄋ"), emptyList(), listOf("a", "s", "A", "S")),
        InputKey(MokyaKeys.KEY_D, listOf("ㄎ", "ㄑ"), emptyList(), listOf("d", "f", "D", "F")),
        InputKey(MokyaKeys.KEY_G, listOf("ㄕ", "ㄘ"), emptyList(), listOf("g", "h", "G", "H")),
        InputKey(MokyaKeys.KEY_J, listOf("ㄨ", "ㄜ"), emptyList(), listOf("j", "k", "J", "K")),
        InputKey(MokyaKeys.KEY_L, listOf("ㄠ", "ㄤ"), emptyList(), listOf("l", "L")),
        InputKey(MokyaKeys.KEY_Z, listOf("ㄈ", "ㄌ"), emptyList(), listOf("z", "x", "Z", "X")),
        InputKey(MokyaKeys.KEY_C, listOf("ㄏ", "ㄒ"), emptyList(), listOf("c", "v", "C", "V")),
        InputKey(MokyaKeys.KEY_B, listOf("ㄖ", "ㄙ"), emptyList(), listOf("b", "n", "B", "N")),
        InputKey(MokyaKeys.KEY_M, listOf("ㄩ", "ㄝ"), emptyList(), listOf("m", "M")),
        InputKey(MokyaKeys.KEY_BACKSLASH, listOf("ㄡ", "ㄥ"), emptyList(), emptyList()),
    )

    private val byKeycode: Map<Int, InputKey> = inputKeys.associateBy { it.keycode }

    fun inputKey(keycode: Int): InputKey? = byKeycode[keycode]

    fun isInputKey(keycode: Int): Boolean = keycode in byKeycode
}
