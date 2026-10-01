// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime.input

import io.github.tengigabytes.mokyaime.engine.InputMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorPolicyTest {
    // android.text.InputType values.
    private val text = 0x1
    private val textMultiLine = 0x1 or 0x20000
    private val textPassword = 0x1 or 0x80
    private val textWebPassword = 0x1 or 0xe0
    private val textEmail = 0x1 or 0x20
    private val textUri = 0x1 or 0x10
    private val number = 0x2
    private val numberPassword = 0x2 or 0x10
    private val phone = 0x3
    private val noLearning = 0x01000000

    @Test
    fun requiredMode() {
        assertNull(EditorPolicy.requiredMode(text))
        assertNull(EditorPolicy.requiredMode(textMultiLine))
        assertNull(EditorPolicy.requiredMode(0))   // TYPE_NULL
        for (type in listOf(textPassword, textWebPassword, textEmail, textUri, number, numberPassword, phone)) {
            assertEquals(InputMode.DIRECT, EditorPolicy.requiredMode(type), "inputType 0x${type.toString(16)}")
        }
    }

    @Test
    fun learning() {
        assertFalse(EditorPolicy.forbidsLearning(text, 0))
        assertTrue(EditorPolicy.forbidsLearning(textPassword, 0))
        assertTrue(EditorPolicy.forbidsLearning(numberPassword, 0))
        assertTrue(EditorPolicy.forbidsLearning(text, noLearning))
        assertFalse(EditorPolicy.forbidsLearning(textEmail, 0))
    }
}
