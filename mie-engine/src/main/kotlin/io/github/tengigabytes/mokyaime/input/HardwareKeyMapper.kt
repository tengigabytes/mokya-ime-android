// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime.input

import io.github.tengigabytes.mokyaime.engine.InputMode
import io.github.tengigabytes.mokyaime.engine.MokyaKeys

/** Non-character keys the mapper cares about. */
enum class SpecialKey { SPACE, ENTER, DEL, LEFT, RIGHT, UP, DOWN, TAB, ESCAPE, LANGUAGE_SWITCH }

/**
 * A hardware key-down, reduced to what the mapping needs.
 *
 * @property baseChar character of the key without modifiers, e.g. `'q'`,
 *   `'1'`, `','` (identifies the key position for Dachen); null if none or
 *   if the key has no layout position (numeric keypad).
 * @property char character with the current modifiers, e.g. `'Q'`, `'!'`.
 * @property repeat true for auto-repeat events of a held key.
 */
data class HardwareKey(
    val baseChar: Char? = null,
    val char: Char? = baseChar,
    val special: SpecialKey? = null,
    val shift: Boolean = false,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val meta: Boolean = false,
    val repeat: Boolean = false,
)

/**
 * Engine state the mapping depends on.
 *
 * @property composing something is pending, candidates are showing, or the
 *   symbol picker is open; keys such as Enter and Backspace then belong to
 *   the engine instead of the app.
 */
data class EngineState(val mode: InputMode, val composing: Boolean, val hasCandidates: Boolean)

/** What the IME does with a hardware key-down. */
sealed interface HardwareAction {
    /** Send this MIE key (press then release) with [flags]. */
    data class Engine(val keycode: Int, val flags: Int = 0) : HardwareAction

    /** Commit any pending input as OK would, then insert [text]. */
    data class Literal(val text: String) : HardwareAction

    /** Discard pending input. */
    data object Abort : HardwareAction

    /** Swallow the key. */
    data object Consume : HardwareAction

    /** Leave the key to the app. */
    data object PassThrough : HardwareAction
}

/**
 * Maps a full hardware keyboard onto the engine.
 *
 * - **SmartZh**: the standard Taiwanese Zhuyin (Dachen, 大千) layout. Each
 *   Bopomofo key is sent as its MokyaLora half-key with the exact phoneme
 *   flag, because the half-keys are Dachen key pairs folded together (Q+W →
 *   ㄆㄊ, 1+2 → ㄅㄉ, …). Shift types full-width punctuation or capitals.
 * - **SmartEn**: unshifted letters go to their half-key for word
 *   prediction; everything else is typed literally.
 * - **Direct**: the keyboard types normally; the engine is bypassed.
 * - Ctrl+Space or the Language-switch key cycles the mode. Enter,
 *   Backspace, arrows, Tab and Esc drive the engine only while it is
 *   composing; otherwise they, and any Ctrl / Alt / Meta shortcut, go to the app.
 */
object HardwareKeyMapper {

    /** Dachen key → (MokyaLora half-key, phoneme index on that key). */
    val dachen: Map<Char, Pair<Int, Int>> = buildMap {
        fun pair(first: Char, second: Char, keycode: Int) {
            put(first, keycode to 0)
            put(second, keycode to 1)
        }
        pair('1', '2', MokyaKeys.KEY_1)          // ㄅ ㄉ
        pair('3', '4', MokyaKeys.KEY_3)          // ˇ ˋ
        pair('5', '6', MokyaKeys.KEY_5)          // ㄓ ˊ
        pair('7', '8', MokyaKeys.KEY_7)          // ˙ ㄚ
        pair('9', '0', MokyaKeys.KEY_9)          // ㄞ ㄢ
        put('-', MokyaKeys.KEY_9 to 2)           // ㄦ
        pair('q', 'w', MokyaKeys.KEY_Q)          // ㄆ ㄊ
        pair('e', 'r', MokyaKeys.KEY_E)          // ㄍ ㄐ
        pair('t', 'y', MokyaKeys.KEY_T)          // ㄔ ㄗ
        pair('u', 'i', MokyaKeys.KEY_U)          // ㄧ ㄛ
        pair('o', 'p', MokyaKeys.KEY_O)          // ㄟ ㄣ
        pair('a', 's', MokyaKeys.KEY_A)          // ㄇ ㄋ
        pair('d', 'f', MokyaKeys.KEY_D)          // ㄎ ㄑ
        pair('g', 'h', MokyaKeys.KEY_G)          // ㄕ ㄘ
        pair('j', 'k', MokyaKeys.KEY_J)          // ㄨ ㄜ
        pair('l', ';', MokyaKeys.KEY_L)          // ㄠ ㄤ
        pair('z', 'x', MokyaKeys.KEY_Z)          // ㄈ ㄌ
        pair('c', 'v', MokyaKeys.KEY_C)          // ㄏ ㄒ
        pair('b', 'n', MokyaKeys.KEY_B)          // ㄖ ㄙ
        pair('m', ',', MokyaKeys.KEY_M)          // ㄩ ㄝ
        pair('.', '/', MokyaKeys.KEY_BACKSLASH)  // ㄡ ㄥ
    }

    /** SmartZh: unshifted keys outside the Dachen layout. */
    val zhPunctuation: Map<Char, String> = mapOf(
        '[' to "「", ']' to "」", '\'' to "、",
    )

    /** SmartZh: Shift + key (by unshifted key) → full-width punctuation. */
    val zhShiftedPunctuation: Map<Char, String> = mapOf(
        ',' to "，", '.' to "。", '/' to "？", ';' to "：", '\'' to "；",
        '1' to "！", '9' to "（", '0' to "）", '[' to "『", ']' to "』",
    )

    fun map(key: HardwareKey, state: EngineState): HardwareAction {
        val modeSwitch = key.special == SpecialKey.LANGUAGE_SWITCH ||
            (key.ctrl && key.special == SpecialKey.SPACE)
        if (modeSwitch) {
            return if (key.repeat) HardwareAction.Consume else HardwareAction.Engine(MokyaKeys.KEY_MODE)
        }
        if (key.ctrl || key.alt || key.meta) return HardwareAction.PassThrough
        if (state.mode == InputMode.DIRECT) return HardwareAction.PassThrough

        key.special?.let { return mapSpecial(it, key.repeat, state) }

        // No layout position (e.g. the numeric keypad): type the character.
        val base = key.baseChar?.lowercaseChar()
            ?: return if (key.repeat) HardwareAction.Consume else literalOrPass(key)
        val action = when (state.mode) {
            InputMode.SMART_ZH -> mapZh(base, key)
            InputMode.SMART_EN -> mapEn(base, key)
            InputMode.DIRECT -> HardwareAction.PassThrough
        }
        // Holding a character key does not auto-type it here.
        return if (key.repeat && action != HardwareAction.PassThrough) HardwareAction.Consume else action
    }

    private fun mapSpecial(special: SpecialKey, repeat: Boolean, state: EngineState): HardwareAction {
        if (!state.composing && !(special == SpecialKey.TAB && state.hasCandidates)) {
            return HardwareAction.PassThrough
        }
        return when (special) {
            SpecialKey.SPACE -> if (repeat) HardwareAction.Consume else HardwareAction.Engine(MokyaKeys.KEY_SPACE)
            SpecialKey.ENTER -> if (repeat) HardwareAction.Consume else HardwareAction.Engine(MokyaKeys.KEY_OK)
            SpecialKey.DEL -> HardwareAction.Engine(MokyaKeys.KEY_DEL)
            SpecialKey.LEFT -> HardwareAction.Engine(MokyaKeys.KEY_LEFT)
            SpecialKey.RIGHT -> HardwareAction.Engine(MokyaKeys.KEY_RIGHT)
            SpecialKey.UP -> HardwareAction.Engine(MokyaKeys.KEY_UP)
            SpecialKey.DOWN -> HardwareAction.Engine(MokyaKeys.KEY_DOWN)
            SpecialKey.TAB ->
                if (state.hasCandidates) HardwareAction.Engine(MokyaKeys.KEY_TAB) else HardwareAction.PassThrough
            SpecialKey.ESCAPE -> HardwareAction.Abort
            SpecialKey.LANGUAGE_SWITCH -> HardwareAction.Engine(MokyaKeys.KEY_MODE)
        }
    }

    private fun mapZh(base: Char, key: HardwareKey): HardwareAction {
        if (key.shift) {
            zhShiftedPunctuation[base]?.let { return HardwareAction.Literal(it) }
            if (base in 'a'..'z') return HardwareAction.Literal(base.uppercase())
            return literalOrPass(key)
        }
        dachen[base]?.let { (keycode, phoneme) ->
            return HardwareAction.Engine(keycode, MokyaKeys.keyFlagPhoneme(phoneme))
        }
        zhPunctuation[base]?.let { return HardwareAction.Literal(it) }
        return literalOrPass(key)
    }

    private fun mapEn(base: Char, key: HardwareKey): HardwareAction {
        if (!key.shift && base in 'a'..'z') {
            val (keycode, _) = dachen.getValue(base)   // letters share Dachen's key pairs
            return HardwareAction.Engine(keycode)
        }
        return literalOrPass(key)
    }

    private fun literalOrPass(key: HardwareKey): HardwareAction =
        key.char?.let { HardwareAction.Literal(it.toString()) } ?: HardwareAction.PassThrough
}
