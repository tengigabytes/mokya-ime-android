// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime.input

/**
 * Picking one Bopomofo symbol of a half-keyboard key by sliding the finger
 * sideways, in the order the key shows them: left for the first symbol,
 * right for the last. A key with three (ㄞㄢㄦ) gives the middle one when it
 * is held without sliding; a key with two gives the first.
 *
 * Indices are phoneme indices of [InputKey.phonemes], as
 * `MokyaKeys.keyFlagPhoneme` takes them.
 */
object PhonemeSlide {

    /**
     * The symbol picked [dx] pixels from where the finger went down, among
     * [count] on the key, or null while the finger is within [threshold] of
     * that point.
     */
    fun picked(count: Int, dx: Float, threshold: Float): Int? = when {
        count < 2 -> null
        dx <= -threshold -> 0
        dx >= threshold -> count - 1
        else -> null
    }

    /** The symbol of a key held without sliding. */
    fun held(count: Int): Int = if (count >= 3) 1 else 0
}
