// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime.input

import io.github.tengigabytes.mokyaime.engine.InputMode
import io.github.tengigabytes.mokyaime.engine.MokyaKeys

/**
 * On-screen keyboard arrangement, following MokyaLora's keypad
 * (docs/requirements/hardware-requirements.md §8.1 in MokyaLora): OK and DEL
 * from the navigation cluster on top, then the 5×5 core input area.
 *
 * The D-pad is left out: on a touch screen candidates are tapped in the
 * strip and the cursor is placed by touching the text (hardware arrow keys
 * still navigate). FUNC, SET, BACK and the volume keys are not used by the
 * engine either; Android's own Back key hides the keyboard. ABC mode uses
 * [qwertyRows] instead of multi-tap.
 */
object KeyboardLayout {

    val actionRow: List<Int> = listOf(MokyaKeys.KEY_OK, MokyaKeys.KEY_DEL)

    val coreRows: List<List<Int>> = listOf(
        listOf(MokyaKeys.KEY_1, MokyaKeys.KEY_3, MokyaKeys.KEY_5, MokyaKeys.KEY_7, MokyaKeys.KEY_9),
        listOf(MokyaKeys.KEY_Q, MokyaKeys.KEY_E, MokyaKeys.KEY_T, MokyaKeys.KEY_U, MokyaKeys.KEY_O),
        listOf(MokyaKeys.KEY_A, MokyaKeys.KEY_D, MokyaKeys.KEY_G, MokyaKeys.KEY_J, MokyaKeys.KEY_L),
        listOf(MokyaKeys.KEY_Z, MokyaKeys.KEY_C, MokyaKeys.KEY_B, MokyaKeys.KEY_M, MokyaKeys.KEY_BACKSLASH),
        listOf(MokyaKeys.KEY_MODE, MokyaKeys.KEY_TAB, MokyaKeys.KEY_SPACE, MokyaKeys.KEY_SYM1, MokyaKeys.KEY_SYM2),
    )

    /** The MokyaLora half-keyboard: OK / DEL, then the 5×5 core. */
    val rows: List<List<Int>> = listOf(actionRow) + coreRows

    /**
     * ABC on the touch screen: QWERTY with a number row, where MokyaLora's
     * keypad uses multi-tap. Shift gives capitals and, on the number row and
     * the punctuation keys, the symbols of a US keyboard. Every row is
     * [ROW_UNITS] key units wide. Number and phone fields get this layout
     * too (ABC is their required mode).
     */
    val qwertyRows: List<List<TouchKey>> = listOf(
        "1234567890".zip("!@#$%^&*()").map { (n, s) -> TouchKey.Text("$n", "$s") },
        "qwertyuiop".map(::letter),
        "asdfghjkl".map(::letter) + TouchKey.Text("-", "_"),
        listOf(TouchKey.Shift(1.5f)) + "zxcvbnm".map(::letter) + TouchKey.Engine(MokyaKeys.KEY_DEL, 1.5f),
        listOf(
            TouchKey.Engine(MokyaKeys.KEY_MODE, 1.5f),
            TouchKey.Text("/", "?"),
            TouchKey.Text(",", ";"),
            TouchKey.Text(" ", " ", 3f),
            TouchKey.Text(".", ":"),
            TouchKey.Engine(MokyaKeys.KEY_OK, 2.5f),
        ),
    )

    const val ROW_UNITS = 10f

    /** The on-screen rows for [mode]. */
    fun touchRows(mode: InputMode): List<List<TouchKey>> =
        if (mode == InputMode.DIRECT) qwertyRows else rows.map { row -> row.map { TouchKey.Engine(it) } }

    private fun letter(c: Char) = TouchKey.Text("$c", "${c.uppercaseChar()}")

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
