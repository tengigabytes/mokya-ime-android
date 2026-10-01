// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime.input

import io.github.tengigabytes.mokyaime.engine.InputMode

/**
 * Per-editor decisions taken from `EditorInfo.inputType` / `imeOptions`.
 * The constants are the stable public values of `android.text.InputType`
 * and `android.view.inputmethod.EditorInfo`, copied so this stays JVM-only.
 */
object EditorPolicy {
    private const val TYPE_MASK_CLASS = 0x0000000f
    private const val TYPE_MASK_VARIATION = 0x00000ff0
    private const val TYPE_CLASS_TEXT = 0x00000001
    private const val TYPE_CLASS_NUMBER = 0x00000002
    private const val TYPE_CLASS_PHONE = 0x00000003
    private const val TYPE_CLASS_DATETIME = 0x00000004
    private const val TYPE_TEXT_VARIATION_URI = 0x00000010
    private const val TYPE_TEXT_VARIATION_EMAIL_ADDRESS = 0x00000020
    private const val TYPE_TEXT_VARIATION_PASSWORD = 0x00000080
    private const val TYPE_TEXT_VARIATION_VISIBLE_PASSWORD = 0x00000090
    private const val TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS = 0x000000d0
    private const val TYPE_TEXT_VARIATION_WEB_PASSWORD = 0x000000e0
    private const val TYPE_NUMBER_VARIATION_PASSWORD = 0x00000010
    private const val IME_FLAG_NO_PERSONALIZED_LEARNING = 0x01000000

    private val TEXT_PASSWORDS = setOf(
        TYPE_TEXT_VARIATION_PASSWORD, TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, TYPE_TEXT_VARIATION_WEB_PASSWORD,
    )
    private val TEXT_ADDRESSES = setOf(
        TYPE_TEXT_VARIATION_URI, TYPE_TEXT_VARIATION_EMAIL_ADDRESS, TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
    )

    /**
     * Mode this editor needs, or null to keep the user's mode. Passwords,
     * numbers, phone numbers, dates, URLs and e-mail addresses take exact
     * characters, so they use Direct.
     */
    fun requiredMode(inputType: Int): InputMode? {
        val variation = inputType and TYPE_MASK_VARIATION
        return when (inputType and TYPE_MASK_CLASS) {
            TYPE_CLASS_TEXT ->
                if (variation in TEXT_PASSWORDS || variation in TEXT_ADDRESSES) InputMode.DIRECT else null
            TYPE_CLASS_NUMBER, TYPE_CLASS_PHONE, TYPE_CLASS_DATETIME -> InputMode.DIRECT
            else -> null
        }
    }

    fun isPassword(inputType: Int): Boolean {
        val variation = inputType and TYPE_MASK_VARIATION
        return when (inputType and TYPE_MASK_CLASS) {
            TYPE_CLASS_TEXT -> variation in TEXT_PASSWORDS
            TYPE_CLASS_NUMBER -> variation == TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    /** True when what is typed here must not be remembered (personalised LRU). */
    fun forbidsLearning(inputType: Int, imeOptions: Int): Boolean =
        isPassword(inputType) || (imeOptions and IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
}
