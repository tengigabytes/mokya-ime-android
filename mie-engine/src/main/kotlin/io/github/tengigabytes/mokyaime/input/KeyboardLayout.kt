// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime.input

import io.github.tengigabytes.mokyaime.engine.InputMode
import io.github.tengigabytes.mokyaime.engine.MokyaKeys

/**
 * On-screen keyboard arrangement, following MokyaLora's keypad
 * (docs/requirements/hardware-requirements.md §8.1 in MokyaLora): the
 * navigation cluster (D-pad, OK, DEL) on top, then the 5×5 core input area.
 * FUNC, SET, BACK and the volume keys are not used by the engine and are
 * left out; Android's own Back key hides the keyboard.
 */
object KeyboardLayout {

    val navigationRow: List<Int> = listOf(
        MokyaKeys.KEY_LEFT, MokyaKeys.KEY_UP, MokyaKeys.KEY_OK,
        MokyaKeys.KEY_DOWN, MokyaKeys.KEY_RIGHT, MokyaKeys.KEY_DEL,
    )

    val coreRows: List<List<Int>> = listOf(
        listOf(MokyaKeys.KEY_1, MokyaKeys.KEY_3, MokyaKeys.KEY_5, MokyaKeys.KEY_7, MokyaKeys.KEY_9),
        listOf(MokyaKeys.KEY_Q, MokyaKeys.KEY_E, MokyaKeys.KEY_T, MokyaKeys.KEY_U, MokyaKeys.KEY_O),
        listOf(MokyaKeys.KEY_A, MokyaKeys.KEY_D, MokyaKeys.KEY_G, MokyaKeys.KEY_J, MokyaKeys.KEY_L),
        listOf(MokyaKeys.KEY_Z, MokyaKeys.KEY_C, MokyaKeys.KEY_B, MokyaKeys.KEY_M, MokyaKeys.KEY_BACKSLASH),
        listOf(MokyaKeys.KEY_MODE, MokyaKeys.KEY_TAB, MokyaKeys.KEY_SPACE, MokyaKeys.KEY_SYM1, MokyaKeys.KEY_SYM2),
    )

    val rows: List<List<Int>> = listOf(navigationRow) + coreRows

    /** Text drawn on a key: [main] large in the centre, [hint] small above it. */
    data class Label(val main: String, val hint: String)

    /** Label of [keycode] in [mode]; input keys show what the mode types. */
    fun label(keycode: Int, mode: InputMode): Label {
        KeyLabels.inputKey(keycode)?.let { key ->
            val bopomofo = key.phonemes.joinToString("")
            val latin = (key.digits.ifEmpty { key.letters.filter { it == it.lowercase() } })
                .joinToString(" ")
            return when {
                mode == InputMode.SMART_ZH -> Label(bopomofo, latin)
                latin.isEmpty() -> Label(bopomofo, "")
                else -> Label(latin, bopomofo)
            }
        }
        val zh = mode == InputMode.SMART_ZH
        return when (keycode) {
            MokyaKeys.KEY_MODE -> Label(mode.indicator, "MODE")
            MokyaKeys.KEY_TAB -> Label("⇥", "TAB")
            MokyaKeys.KEY_SPACE -> Label("␣", "SPACE")
            MokyaKeys.KEY_SYM1 -> Label(if (zh) "，" else ",", "SYM")
            MokyaKeys.KEY_SYM2 -> Label(if (zh) "。？！" else ". ? !", "")
            MokyaKeys.KEY_LEFT -> Label("◀", "")
            MokyaKeys.KEY_RIGHT -> Label("▶", "")
            MokyaKeys.KEY_UP -> Label("▲", "")
            MokyaKeys.KEY_DOWN -> Label("▼", "")
            MokyaKeys.KEY_OK -> Label("OK", "")
            MokyaKeys.KEY_DEL -> Label("⌫", "DEL")
            else -> Label("", "")
        }
    }

    /**
     * True for keys whose press is deferred until release or the 500 ms
     * long-press mark (MokyaLora keypad_scan.c): the 20 input keys in
     * SmartZh, where a long press pins a phoneme. Other modes ignore the
     * long-press flag, so their input keys act on press.
     */
    fun defersPress(keycode: Int, mode: InputMode): Boolean =
        mode == InputMode.SMART_ZH && KeyLabels.isInputKey(keycode)
}
