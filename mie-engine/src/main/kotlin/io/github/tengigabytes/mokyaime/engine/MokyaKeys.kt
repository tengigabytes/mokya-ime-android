// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime.engine

/**
 * MIE keycodes and key-event flags. `KEY_X` mirrors `MOKYA_KEY_X` in libmie's
 * `include/mie/keycode.h`, which is the source of truth; KeycodeSyncTest
 * fails if the two drift apart.
 *
 * Values follow MokyaLora's 6x6 keypad (row * 6 + col + 1) but are only
 * identifiers: always refer to them by name.
 */
@Suppress("unused")
object MokyaKeys {
    const val KEY_NONE = 0x00
    const val KEY_1 = 0x01  // 1 2 / ㄅㄉ
    const val KEY_3 = 0x02  // 3 4 / ˇ ˋ
    const val KEY_5 = 0x03  // 5 6 / ㄓ ˊ
    const val KEY_7 = 0x04  // 7 8 / ˙ ㄚ
    const val KEY_9 = 0x05  // 9 0 / ㄞㄢㄦ
    const val KEY_FUNC = 0x06
    const val KEY_Q = 0x07  // Q W / ㄆㄊ
    const val KEY_E = 0x08  // E R / ㄍㄐ
    const val KEY_T = 0x09  // T Y / ㄔㄗ
    const val KEY_U = 0x0A  // U I / ㄧㄛ
    const val KEY_O = 0x0B  // O P / ㄟㄣ
    const val KEY_SET = 0x0C
    const val KEY_A = 0x0D  // A S / ㄇㄋ
    const val KEY_D = 0x0E  // D F / ㄎㄑ
    const val KEY_G = 0x0F  // G H / ㄕㄘ
    const val KEY_J = 0x10  // J K / ㄨㄜ
    const val KEY_L = 0x11  // L   / ㄠㄤ
    const val KEY_BACK = 0x12
    const val KEY_Z = 0x13  // Z X / ㄈㄌ
    const val KEY_C = 0x14  // C V / ㄏㄒ
    const val KEY_B = 0x15  // B N / ㄖㄙ
    const val KEY_M = 0x16  // M   / ㄩㄝ
    const val KEY_BACKSLASH = 0x17  // \   / ㄡㄥ
    const val KEY_DEL = 0x18
    const val KEY_MODE = 0x19
    const val KEY_TAB = 0x1A
    const val KEY_SPACE = 0x1B
    const val KEY_SYM1 = 0x1C  // ，SYM (row4 col3)
    const val KEY_SYM2 = 0x1D  // 。.？ (row4 col4)
    const val KEY_VOL_UP = 0x1E
    const val KEY_UP = 0x1F
    const val KEY_DOWN = 0x20
    const val KEY_LEFT = 0x21
    const val KEY_RIGHT = 0x22
    const val KEY_OK = 0x23
    const val KEY_VOL_DOWN = 0x24
    const val KEY_POWER = 0x25
    const val KEY_LIMIT = 0x40

    // Flags for MieEngine.processKey(flags = ...).
    const val KEY_FLAG_LONG_PRESS = 0x01
    const val KEY_FLAG_HINT_ANY = 0x04
    const val KEY_FLAG_PHONEME_MASK = 0x18

    /**
     * Explicit phoneme flag (`MOKYA_KEY_FLAG_PHONEME(idx)`): index 0..2 of
     * the half-key's Bopomofo symbols, for producers that know exactly which
     * one was typed (e.g. a Dachen hardware keyboard). SmartZh only.
     */
    fun keyFlagPhoneme(index: Int): Int = ((index + 1) shl 3) and KEY_FLAG_PHONEME_MASK
}
