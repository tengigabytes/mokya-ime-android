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

    /** Bottom row of both ABC pages; the page key switches between them. */
    private val bottomRow: List<TouchKey> = listOf(
        TouchKey.Engine(MokyaKeys.KEY_MODE, 1.5f),
        TouchKey.Page(),
        TouchKey.Text("/", "?"),
        TouchKey.Text(",", ";"),
        TouchKey.Text(" ", " ", 2.5f),
        TouchKey.Text(".", ":"),
        TouchKey.Engine(MokyaKeys.KEY_OK, 2f),
    )

    /**
     * ABC on the touch screen: QWERTY with a number row, where MokyaLora's
     * keypad uses multi-tap. Shift gives capitals and, on the number row and
     * the punctuation keys, the symbols of a US keyboard. Every row is
     * [ROW_UNITS] key units wide. Number and phone fields get this layout
     * too (ABC is their required mode). The page key (#+=) opens
     * [symbolRows].
     */
    val qwertyRows: List<List<TouchKey>> = listOf(
        "1234567890".zip("!@#$%^&*()").map { (n, s) -> TouchKey.Text("$n", "$s") },
        "qwertyuiop".map(::letter),
        "asdfghjkl".map(::letter) + TouchKey.Text("-", "_"),
        listOf(TouchKey.Shift(1.5f)) + "zxcvbnm".map(::letter) + TouchKey.Engine(MokyaKeys.KEY_DEL, 1.5f),
        bottomRow,
    )

    /**
     * ABC's symbol page: the digits, then every ASCII symbol, so the ones
     * the letter page lacks (= + [ ] { } \ | ~ < > ' " `) need no Shift.
     * The bottom row is the letter page's, with the page key back to it.
     */
    val symbolRows: List<List<TouchKey>> = listOf(
        "1234567890".map(::symbol),
        "!@#$%^&*()".map(::symbol),
        "[]{}<>=+\\|".map(::symbol),
        "?'\"~`_;:".map(::symbol) + TouchKey.Engine(MokyaKeys.KEY_DEL, 2f),
        bottomRow,
    )

    /**
     * The page the half-keyboard turns into while the engine's SYM1 picker
     * is open (，SYM held): the digits on top, the picker's own sixteen
     * marks and the brackets and dashes it lacks, then the ASCII symbols
     * most used in Chinese text. A key types its symbol and closes the
     * picker; the SYM1 key in the corner closes it without typing, as on
     * the device. The other ASCII symbols are on ABC's [symbolRows].
     */
    val pickerRows: List<List<TouchKey>> = listOf(
        "1234567890".map(::symbol),
        "「」『』（）【】《》".map(::symbol),
        "，。、；：？！…〈〉".map(::symbol),
        listOf(symbol("——")) + "～·@#%&*+=".map(::symbol),
        "-/_()\"':;?".map(::symbol),
        listOf(TouchKey.Engine(MokyaKeys.KEY_SYM1, 2f)) + ",.!$<>[]".map(::symbol),
    )

    const val ROW_UNITS = 10f

    /**
     * The on-screen rows for [mode]; [symbols] picks ABC's symbol page, and
     * [picker] the page of the SYM1 picker, whatever the mode.
     */
    fun touchRows(mode: InputMode, symbols: Boolean = false, picker: Boolean = false): List<List<TouchKey>> = when {
        picker -> pickerRows
        mode != InputMode.DIRECT -> rows.map { row -> row.map { TouchKey.Engine(it) } }
        symbols -> symbolRows
        else -> qwertyRows
    }

    private fun letter(c: Char) = TouchKey.Text("$c", "${c.uppercaseChar()}")

    /** The symbol pages have no Shift: a key types the same either way. */
    private fun symbol(c: Char) = symbol("$c")

    private fun symbol(s: String) = TouchKey.Text(s, s)

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
            MokyaKeys.KEY_SYM2 -> Label(if (zh) zhSentenceMarks.joinToString("") else ". ? !", "")
            MokyaKeys.KEY_OK -> Label("OK", "")
            MokyaKeys.KEY_DEL -> Label("⌫", "DEL")
            else -> Label("", "")
        }
    }

    /** SYM2's sentence marks in SmartZh, as `kSym2ZhCycle` in libmie's `src/ime_direct.cpp`. */
    val zhSentenceMarks: List<String> = listOf("。", "？", "！")

    /**
     * What sliding on [keycode] or holding it picks from ([PhonemeSlide]),
     * in the order the key shows them; empty when there is nothing to
     * pick. In SmartZh the 20 input keys offer their Bopomofo symbols and
     * SYM2 its sentence marks; other modes have nothing to pick.
     */
    fun slideChoices(keycode: Int, mode: InputMode): List<String> = when {
        mode != InputMode.SMART_ZH -> emptyList()
        keycode == MokyaKeys.KEY_SYM2 -> zhSentenceMarks
        else -> KeyLabels.inputKey(keycode)?.phonemes.orEmpty()
    }

    /**
     * True for keys whose press is deferred until release ([PressTracker]):
     * those with [slideChoices]. Other keys act on press.
     */
    fun defersPress(keycode: Int, mode: InputMode): Boolean = slideChoices(keycode, mode).isNotEmpty()
}
