// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime.input

/**
 * What OK does when nothing is being composed: the engine's idle OK becomes
 * `sendKeyChar('\n')`, which runs the editor's action unless the editor asks
 * Enter not to (InputMethodService.sendDefaultEditorAction). The key shows
 * that action, so a second OK after committing a word is not a surprise
 * "Send".
 */
enum class EnterAction {
    NEWLINE, GO, SEARCH, SEND, NEXT, DONE, PREVIOUS;

    companion object {
        // android.view.inputmethod.EditorInfo
        private const val IME_MASK_ACTION = 0x000000ff
        private const val IME_ACTION_GO = 2
        private const val IME_ACTION_SEARCH = 3
        private const val IME_ACTION_SEND = 4
        private const val IME_ACTION_NEXT = 5
        private const val IME_ACTION_DONE = 6
        private const val IME_ACTION_PREVIOUS = 7
        private const val IME_FLAG_NO_ENTER_ACTION = 0x40000000

        fun of(imeOptions: Int): EnterAction {
            if (imeOptions and IME_FLAG_NO_ENTER_ACTION != 0) return NEWLINE
            return when (imeOptions and IME_MASK_ACTION) {
                IME_ACTION_GO -> GO
                IME_ACTION_SEARCH -> SEARCH
                IME_ACTION_SEND -> SEND
                IME_ACTION_NEXT -> NEXT
                IME_ACTION_DONE -> DONE
                IME_ACTION_PREVIOUS -> PREVIOUS
                else -> NEWLINE   // unspecified / none: a line break or a plain Enter
            }
        }
    }
}
