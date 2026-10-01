// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime.engine

import io.github.tengigabytes.mokyaime.input.EngineState
import io.github.tengigabytes.mokyaime.input.HardwareAction
import io.github.tengigabytes.mokyaime.input.HardwareKey
import io.github.tengigabytes.mokyaime.input.HardwareKeyMapper
import java.io.File
import java.nio.ByteBuffer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Dachen hardware typing through the mapper into the real engine (mini dictionary). */
class HardwareTypingHostTest {

    private val commits = mutableListOf<String>()
    private val engine = MieEngine.create(
        File(System.getProperty("mokya.testDict") ?: error("mokya.testDict not set")).readBytes()
            .let { ByteBuffer.allocateDirect(it.size).put(it) },
        object : MieListener {
            override fun onCommit(text: String) { commits += text }
            override fun onCursorMove(direction: NavDirection) {}
            override fun onDeleteBefore() {}
            override fun onCompositionChanged() {}
        },
    )
    private var now = 0L

    @AfterTest
    fun tearDown() = engine.close()

    private fun type(keys: String) {
        for (c in keys) {
            val state = EngineState(engine.mode, engine.hasPending, engine.candidates().isNotEmpty())
            when (val action = HardwareKeyMapper.map(HardwareKey(baseChar = c), state)) {
                is HardwareAction.Engine -> {
                    now += 50
                    engine.processKey(action.keycode, true, now, action.flags)
                    engine.processKey(action.keycode, false, now + 10, action.flags)
                }
                else -> error("unexpected $action for '$c'")
            }
        }
    }

    @Test
    fun dachenWordAndExactPhonemes() {
        type("su3cl3")   // ㄋㄧˇ ㄏㄠˇ
        assertEquals("ㄋ, ㄧ, ˇ, ㄏ, ㄠ, ˇ", engine.pending().text)
        assertEquals("你好", engine.candidates().first())
    }

    @Test
    fun exactPhonemeFiltersHalfKeyNeighbours() {
        // ㄇ and ㄋ share KEY_A: 媽 (ㄇㄚ) and 那 (ㄋㄚˋ) differ only there.
        type("a8")
        assertContains(engine.candidates(), "媽")
        assertFalse("那" in engine.candidates())

        engine.abort()
        type("s8")
        assertContains(engine.candidates(), "那")
        assertFalse("媽" in engine.candidates())
    }
}
