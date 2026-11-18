// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime.input

import io.github.tengigabytes.mokyaime.engine.MieEngine
import io.github.tengigabytes.mokyaime.engine.MieListener
import io.github.tengigabytes.mokyaime.engine.MokyaKeys
import io.github.tengigabytes.mokyaime.engine.NavDirection
import java.io.File
import java.nio.ByteBuffer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [LearningGuard] over the real engine (host build, mini dictionary), driven
 * the way MokyaImeService drives it: [LearningGuard.enter] on every
 * onStartInput, [LearningGuard.leave] in onFinishInput and before the LRU is
 * saved in onDestroy. Only SmartZh candidate commits teach the LRU.
 */
class LearningGuardTest {

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
    private var restores = 0
    private val guard = LearningGuard(
        snapshot = { engine.serializeLru() },
        restore = { restores++; check(engine.loadLru(it)) },
    )
    private var now = 0L

    @AfterTest
    fun tearDown() = engine.close()

    private fun tap(key: Int) {
        now += 50
        engine.processKey(key, true, now)
        engine.processKey(key, false, now + 10)
    }

    /** Commits 你好 (ㄇㄋ ㄏㄒ, first candidate), which the engine learns. */
    private fun teachNiHao() {
        tap(MokyaKeys.KEY_A)
        tap(MokyaKeys.KEY_C)
        tap(MokyaKeys.KEY_OK)
        assertEquals("你好", commits.last())
    }

    /** Commits the first candidate of ㄖㄙ, another word to learn. */
    private fun teachRen() {
        tap(MokyaKeys.KEY_B)
        assertTrue(engine.candidates().isNotEmpty())
        tap(MokyaKeys.KEY_OK)
    }

    private fun lru() = engine.serializeLru()

    @Test
    fun theEngineLearnsFromCommits() {
        // Without this the other tests would prove nothing.
        val empty = lru()
        teachNiHao()
        val one = lru()
        teachRen()
        assertFalse(one.contentEquals(empty))
        assertFalse(lru().contentEquals(one))
    }

    @Test
    fun noLearningEditorForgetsWhatItTaught() {
        teachNiHao()   // learned earlier, in an ordinary editor
        val before = lru()
        guard.enter(forbidsLearning = true)
        teachRen()
        assertTrue(guard.holding)
        guard.leave()
        assertContentEquals(before, lru())
        assertFalse(guard.holding)
    }

    @Test
    fun restartInsideNoLearningEditorStillForgets() {
        val before = lru()
        guard.enter(forbidsLearning = true)
        teachNiHao()
        guard.enter(forbidsLearning = true)   // onStartInput(restarting = true): no onFinishInput first
        teachRen()
        guard.leave()
        assertContentEquals(before, lru())
    }

    @Test
    fun restartAsOrdinaryEditorForgetsAndThenLearns() {
        val before = lru()
        guard.enter(forbidsLearning = true)
        teachNiHao()
        guard.enter(forbidsLearning = false)   // the same editor, restarted without the flag
        assertContentEquals(before, lru())
        assertFalse(guard.holding)
        teachRen()
        val learned = lru()
        assertFalse(learned.contentEquals(before))
        guard.leave()
        assertContentEquals(learned, lru())
    }

    @Test
    fun destroyedInsideNoLearningEditorSavesWhatWasThereBefore() {
        val before = lru()
        guard.enter(forbidsLearning = true)
        teachNiHao()
        // onDestroy: leave, then save; super.onDestroy then runs onFinishInput,
        // whose leave must not touch the (by then closed) engine again.
        guard.leave()
        val saved = lru()
        assertEquals(1, restores)
        guard.leave()
        assertEquals(1, restores)
        assertContentEquals(before, saved)
    }

    @Test
    fun ordinaryEditorsKeepLearning() {
        val before = lru()
        guard.enter(forbidsLearning = false)
        teachNiHao()
        guard.enter(forbidsLearning = false)
        teachRen()
        guard.leave()
        assertFalse(lru().contentEquals(before))
        assertEquals(0, restores)
    }

    @Test
    fun noEngineMeansNothingToGuard() {
        val none = LearningGuard(snapshot = { null }, restore = { error("nothing was held") })
        none.enter(forbidsLearning = true)
        assertFalse(none.holding)
        none.leave()
    }
}
