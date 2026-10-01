package io.github.tengigabytes.mokyaime.engine

import java.io.File
import java.nio.ByteBuffer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Runs the real JNI bridge (built for the host by the buildHostJni task)
 * against a tiny dictionary generated from src/test/dict by gen_dict.py.
 */
class MieEngineHostTest {

    private class Recorder : MieListener {
        val events = mutableListOf<String>()
        var throwOnCommit: RuntimeException? = null

        override fun onCommit(text: String) {
            throwOnCommit?.let { throw it }
            events += "commit:$text"
        }
        override fun onCursorMove(direction: NavDirection) { events += "move:$direction" }
        override fun onDeleteBefore() { events += "delete" }
        override fun onCompositionChanged() { events += "changed" }

        fun commits() = events.filter { it.startsWith("commit:") }.map { it.removePrefix("commit:") }
    }

    private val recorder = Recorder()
    private lateinit var engine: MieEngine
    private var now = 1_000L

    @BeforeTest
    fun setUp() {
        engine = MieEngine.create(dictionary(), recorder)
    }

    @AfterTest
    fun tearDown() {
        engine.close()
    }

    private fun dictionary(): ByteBuffer {
        val path = System.getProperty("mokya.testDict") ?: error("mokya.testDict not set")
        val bytes = File(path).readBytes()
        return ByteBuffer.allocateDirect(bytes.size).put(bytes)
    }

    /** Short tap: press then release, 50 ms after the previous key. */
    private fun tap(key: Int, flags: Int = 0) {
        now += 50
        engine.processKey(key, true, now, flags)
        engine.processKey(key, false, now + 10)
    }

    private fun cycleTo(mode: InputMode) {
        while (engine.mode != mode) tap(MokyaKeys.KEY_MODE)
    }

    @Test
    fun rejectsHeapBuffer() {
        assertFailsWith<IllegalArgumentException> {
            MieEngine.create(ByteBuffer.allocate(64), recorder)
        }
    }

    @Test
    fun rejectsNonDictionary() {
        assertFailsWith<IllegalArgumentException> {
            MieEngine.create(ByteBuffer.allocateDirect(256), recorder)
        }
    }

    @Test
    fun loadsEmbeddedEnglish() {
        assertTrue(engine.hasEnglish)
        assertEquals(InputMode.SMART_ZH, engine.mode)
    }

    @Test
    fun smartZhAbbreviationCommitsWord() {
        tap(MokyaKeys.KEY_A)   // ㄇ/ㄋ
        tap(MokyaKeys.KEY_C)   // ㄏ/ㄒ
        assertTrue(engine.hasPending)
        assertEquals("你好", engine.candidates().first())

        val pending = engine.pending()
        assertEquals(PendingStyle.PREFIX_BOLD, pending.style)
        assertTrue(pending.matchedPrefixLength in 0..pending.text.length)

        tap(MokyaKeys.KEY_OK)
        assertEquals(listOf("你好"), recorder.commits())
        assertFalse(engine.hasPending)
        assertEquals(PendingView("", 0, PendingStyle.NONE), engine.pending())
    }

    @Test
    fun supplementaryCharacterSurvivesJni() {
        tap(MokyaKeys.KEY_G)           // ㄕ/ㄘ
        tap(MokyaKeys.KEY_J)           // ㄨ/ㄜ
        tap(MokyaKeys.KEY_BACKSLASH)   // ㄡ/ㄥ
        val candidates = engine.candidates()
        assertContains(candidates, "𠊎")   // U+2228E, a surrogate pair in UTF-16

        engine.selectedCandidate = candidates.indexOf("𠊎")
        tap(MokyaKeys.KEY_OK)
        assertEquals(listOf("𠊎"), recorder.commits())
    }

    @Test
    fun smartEnPredictsFromEmbeddedEnglish() {
        cycleTo(InputMode.SMART_EN)
        engine.setTextContext("")
        for (key in listOf(MokyaKeys.KEY_G, MokyaKeys.KEY_E, MokyaKeys.KEY_L, MokyaKeys.KEY_L, MokyaKeys.KEY_O)) {
            tap(key)
        }
        assertEquals("hello", engine.candidates().first())
        tap(MokyaKeys.KEY_OK)
        assertEquals(listOf("Hello"), recorder.commits())   // sentence start: capitalised
    }

    @Test
    fun textContextControlsSpacingAndCase() {
        cycleTo(InputMode.SMART_EN)
        engine.setTextContext("ok")   // mid-sentence, no trailing space
        for (key in listOf(MokyaKeys.KEY_G, MokyaKeys.KEY_E, MokyaKeys.KEY_L, MokyaKeys.KEY_L, MokyaKeys.KEY_O)) {
            tap(key)
        }
        tap(MokyaKeys.KEY_OK)
        // The leading space arrives as its own commit, before the word.
        assertEquals(" hello", recorder.commits().joinToString(""))

        // Non-BMP and lone surrogates must not upset the UTF-16 -> UTF-8 path.
        engine.setTextContext("a😀")
        engine.setTextContext("\uDE00")
    }

    @Test
    fun directMultiTapCommitsOnTick() {
        cycleTo(InputMode.DIRECT)
        tap(MokyaKeys.KEY_A)
        assertEquals(PendingView("a", 0, PendingStyle.INVERTED), engine.pending())
        assertTrue(engine.needsTick)

        engine.tick(now + 100)
        assertTrue(recorder.commits().isEmpty())
        engine.tick(now + 900)   // > ImeLogic::kMultiTapTimeoutMs (800)
        assertEquals(listOf("a"), recorder.commits())
        assertFalse(engine.needsTick)
    }

    @Test
    fun sym1LongPressNeedsTickWhileNothingPending() {
        val t0 = now + 50
        engine.processKey(MokyaKeys.KEY_SYM1, true, t0)
        assertFalse(engine.hasPending)
        assertTrue(engine.needsTick)

        engine.tick(t0 + 600)   // > ImeLogic::kLongPressMs (500)
        assertTrue(engine.pickerActive)
        engine.processKey(MokyaKeys.KEY_SYM1, false, t0 + 700)
        assertFalse(engine.needsTick)

        assertEquals(16, engine.pickerCells().size)
        assertEquals(4, engine.pickerColumns)
        assertEquals(0, engine.pickerSelected)
        now = t0 + 700
        tap(MokyaKeys.KEY_OK)
        assertEquals(listOf("「"), recorder.commits())
    }

    @Test
    fun abortForgetsHeldSym1() {
        engine.processKey(MokyaKeys.KEY_SYM1, true, now)
        assertTrue(engine.needsTick)
        engine.abort()
        assertFalse(engine.needsTick)
    }

    @Test
    fun idleKeysReachListener() {
        tap(MokyaKeys.KEY_DEL)
        tap(MokyaKeys.KEY_LEFT)
        tap(MokyaKeys.KEY_OK)
        tap(MokyaKeys.KEY_SPACE)
        assertEquals(
            listOf("delete", "move:LEFT", "commit:\n", "changed", "commit: ", "changed"),
            recorder.events,
        )
    }

    @Test
    fun lruRoundTrip() {
        tap(MokyaKeys.KEY_A)
        tap(MokyaKeys.KEY_C)
        tap(MokyaKeys.KEY_OK)
        val saved = engine.serializeLru()
        assertTrue(saved.size > 8)

        MieEngine.create(dictionary(), Recorder()).use { other ->
            assertTrue(other.loadLru(saved))
            assertFalse(other.loadLru(byteArrayOf(1, 2, 3)))
        }
    }

    @Test
    fun listenerExceptionPropagatesAndEngineSurvives() {
        recorder.throwOnCommit = IllegalStateException("boom")
        tap(MokyaKeys.KEY_A)
        tap(MokyaKeys.KEY_C)
        val e = assertFailsWith<IllegalStateException> {
            engine.processKey(MokyaKeys.KEY_OK, true, now + 50)
        }
        assertEquals("boom", e.message)

        recorder.throwOnCommit = null
        now += 100
        tap(MokyaKeys.KEY_A)
        assertTrue(engine.hasPending)
    }

    @Test
    fun closedEngineThrows() {
        engine.close()
        engine.close()   // idempotent
        assertFailsWith<IllegalStateException> { engine.hasPending }
    }
}
