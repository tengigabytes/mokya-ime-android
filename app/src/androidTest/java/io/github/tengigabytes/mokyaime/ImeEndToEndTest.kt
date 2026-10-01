// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime

import android.app.Instrumentation
import android.graphics.PointF
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tengigabytes.mokyaime.engine.InputMode
import io.github.tengigabytes.mokyaime.engine.MokyaKeys
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the real IME on a device / emulator: enables and selects Mokya
 * IME, focuses the field in SetupActivity, then types through injected
 * hardware key events and touches on the on-screen keyboard.
 *
 * Every test starts once the IME serves the field and its keyboard is on
 * screen; failures print the IME's event trace.
 */
@RunWith(AndroidJUnit4::class)
class ImeEndToEndTest {

    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val imeId = "io.github.tengigabytes.mokyaime/.MokyaImeService"
    private lateinit var scenario: ActivityScenario<SetupActivity>
    private lateinit var field: EditText
    private val trace = ArrayList<String>()   // written and read on the main thread

    @Before
    fun setUp() {
        MokyaImeService.traceForTest = trace
        shell("ime enable $imeId")
        shell("ime set $imeId")
        // Keep the on-screen keyboard even if the emulator reports a hardware keyboard.
        shell("settings put secure show_ime_with_hard_keyboard 1")

        scenario = ActivityScenario.launch(SetupActivity::class.java)
        scenario.onActivity { activity ->
            field = activity.findViewById(R.id.try_field)
            field.requestFocus()
        }
        // The window may get a TYPE_NULL input start before the field's own one.
        waitFor("IME started on the field") {
            onMain {
                val ime = MokyaImeService.current
                val info = ime?.currentInputEditorInfo
                ime != null && ime.currentInputStarted && info != null &&
                    info.packageName == instrumentation.targetContext.packageName &&
                    info.fieldId == R.id.try_field && info.inputType != InputType.TYPE_NULL
            }
        }
        // The mode is persisted: an earlier test may have left another one.
        onMain { MokyaImeService.current!!.switchModeForTest(InputMode.SMART_ZH) }
        // Only a served field can request the keyboard.
        scenario.onActivity { activity ->
            activity.getSystemService(InputMethodManager::class.java).showSoftInput(field, 0)
        }
        waitForKeyboardOnScreen()
    }

    @After
    fun tearDown() {
        scenario.close()
        MokyaImeService.traceForTest = null
    }

    // ── Hardware keyboard ────────────────────────────────────────────────

    @Test
    fun dachenTypingCommitsWordOnEnter() {
        keys("su3cl3")                                   // ㄋㄧˇ ㄏㄠˇ
        waitForText("ㄋ, ㄧ, ˇ, ㄏ, ㄠ, ˇ")               // composing text in the field
        assertEquals("你好", onMain { MokyaImeService.current!!.candidatesForTest().first() })
        key(KeyEvent.KEYCODE_ENTER)
        waitForText("你好")
    }

    @Test
    fun shiftedPunctuationCommitsPendingWordFirst() {
        keys("su3cl3")
        key(KeyEvent.KEYCODE_COMMA, KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON)
        waitForText("你好，")
    }

    @Test
    fun ctrlSpaceSwitchesToEnglishPrediction() {
        key(KeyEvent.KEYCODE_SPACE, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
        waitFor("SmartEn") { onMain { MokyaImeService.current!!.modeForTest } == InputMode.SMART_EN }
        keys("hello")
        key(KeyEvent.KEYCODE_ENTER)
        waitForText("Hello")   // first word of the field is capitalised
    }

    @Test
    fun passwordFieldTypesDirectly() {
        restartField(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        waitFor("Direct mode") { onMain { MokyaImeService.current!!.modeForTest } == InputMode.DIRECT }
        keys("ab1")
        waitForText("ab1")
    }

    @Test
    fun okShowsTheEditorActionWhenIdle() {
        restartField(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_SEND)
        val send = instrumentation.targetContext.getString(R.string.enter_send)
        waitFor("OK labelled Send") { onMain { MokyaImeService.current!!.okLabelForTest } == send }
        keys("su")
        waitFor("OK commits while composing") { onMain { MokyaImeService.current!!.okLabelForTest } == "OK" }
        key(KeyEvent.KEYCODE_ENTER)   // commits the word, does not send
        waitFor("OK labelled Send again") { onMain { MokyaImeService.current!!.okLabelForTest } == send }
    }

    @Test
    fun externalCursorMoveDropsComposition() {
        // append, unlike setText, does not restart input. The IME must see
        // the new cursor before we type, or its late report would itself
        // count as an external edit.
        scenario.onActivity { field.append("xy"); field.setSelection(2) }
        waitFor("IME saw the cursor after xy") { onMain { MokyaImeService.current!!.selectionEndForTest } == 2 }
        keys("su")
        waitForText("xyㄋ, ㄧ")
        scenario.onActivity { field.setSelection(0) }   // the app moves the cursor
        waitForText("xy")
        waitFor("cursor kept at 0") { onMain { field.selectionStart } == 0 }
        keys("su3cl3")                                    // typed at the new position
        key(KeyEvent.KEYCODE_ENTER)
        waitForText("你好xy")
    }

    // ── On-screen keyboard ───────────────────────────────────────────────

    @Test
    fun abcLayoutTypesQwertyWithShift() {
        restartField(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        waitForQwerty()
        tapText("a")
        tapShift()
        tapText("b")   // one-shot Shift: B, then back to lower case
        tapText("1")
        tapShift()
        tapText("2")   // Shift on the number row: @
        tapText("c")
        waitForText("aB1@c")
    }

    @Test
    fun phoneFieldTypesOnTheNumberRow() {
        restartField(InputType.TYPE_CLASS_PHONE)
        waitForQwerty()
        "0912".forEach { tapText("$it") }
        waitForText("0912")
    }

    @Test
    fun touchTapsAndOk() {
        touch(MokyaKeys.KEY_A)   // ㄇㄋ
        touch(MokyaKeys.KEY_C)   // ㄏㄒ
        waitForText("ㄇㄋ, ㄏㄒ")
        val first = onMain { MokyaImeService.current!!.candidatesForTest().first() }
        touch(MokyaKeys.KEY_OK)   // commits the highlighted (first) candidate
        waitForText(first)
    }

    @Test
    fun touchPagesCandidatesAndTapsOne() {
        touch(MokyaKeys.KEY_A)   // ㄇㄋ
        touch(MokyaKeys.KEY_C)   // ㄏㄒ
        waitForText("ㄇㄋ, ㄏㄒ")
        val strip = { MokyaImeService.current!!.candidateStripForTest!! }
        waitFor("more candidates than fit") { onMain { strip().canPageForwardForTest } }

        tapAt(onMain { strip().pageButtonCenterOnScreen(forward = true) })
        var lastX = -1
        waitFor("strip paged") {
            val x = onMain { strip().scrollXForTest }
            (x > 0 && x == lastX).also { lastX = x }   // scrolled, and the animation ended
        }
        val index = onMain { strip().firstVisibleItemForTest() }
        val word = onMain { MokyaImeService.current!!.candidatesForTest()[index] }
        assertTrue("first candidate of the second page, got $index", index > 0)
        tapAt(onMain { strip().itemCenterOnScreen(index)!! })
        waitForText(word)
    }

    @Test
    fun touchLongPressPinsAndCyclesPhoneme() {
        // Long press → primary ㄆ; a second long press whose 500 ms mark
        // falls within 800 ms of the first one cycles to the secondary ㄊ.
        touch(MokyaKeys.KEY_Q, holdMs = 550)
        touch(MokyaKeys.KEY_Q, holdMs = 550)
        waitForText("ㄊ")
        touch(MokyaKeys.KEY_DEL)
        waitForText("")
    }

    @Test
    fun touchSym1LongPressOpensPicker() {
        touch(MokyaKeys.KEY_SYM1, holdMs = 800)
        assertTrue("symbol picker not open\n${traceDump()}", onMain { MokyaImeService.current!!.pickerActiveForTest })
        touch(MokyaKeys.KEY_OK)                 // first cell
        waitForText("「")
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { pfd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).readBytes()
        }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun waitFor(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        fail("Timed out waiting for $what\n${traceDump()}")
    }

    private fun waitForText(expected: String) {
        var last = ""
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            last = onMain { field.text.toString() }
            if (last == expected) return
            SystemClock.sleep(50)
        }
        assertEquals("field text\n${traceDump()}\n", expected, last)
    }

    /** The last IME events, oldest first. */
    private fun traceDump(): String = onMain { trace.takeLast(60).joinToString("\n", prefix = "IME trace:\n") }

    /**
     * Waits until the keyboard is really on screen: the app sees the IME
     * insets and the keys have stopped moving. A touch injected before that
     * can land on the window behind the keyboard.
     */
    private fun waitForKeyboardOnScreen() {
        var last: PointF? = null
        var stablePolls = 0
        waitFor("on-screen keyboard") {
            val now = onMain { MokyaImeService.current?.keyCenterOnScreen(MokyaKeys.KEY_OK) }
            val settled = now != null && last?.let { it.x == now.x && it.y == now.y } == true
            stablePolls = if (settled && onMain { imeInsetsVisible() }) stablePolls + 1 else 0
            last = now
            stablePolls >= 5
        }
        instrumentation.waitForIdleSync()
    }

    private fun imeInsetsVisible(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
        return field.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
    }

    private fun key(keyCode: Int, metaState: Int = 0) {
        onMain { MokyaImeService.trace { "test: key $keyCode meta=0x${metaState.toString(16)}" } }
        val now = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState))
        instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, metaState))
        instrumentation.waitForIdleSync()
    }

    /** Types US-QWERTY characters (letters, digits, - , . / ;) as hardware keys. */
    private fun keys(text: String) {
        for (c in text) {
            val code = when (c) {
                in 'a'..'z' -> KeyEvent.KEYCODE_A + (c - 'a')
                in '0'..'9' -> KeyEvent.KEYCODE_0 + (c - '0')
                '-' -> KeyEvent.KEYCODE_MINUS
                ',' -> KeyEvent.KEYCODE_COMMA
                '.' -> KeyEvent.KEYCODE_PERIOD
                '/' -> KeyEvent.KEYCODE_SLASH
                ';' -> KeyEvent.KEYCODE_SEMICOLON
                else -> error("unsupported '$c'")
            }
            key(code)
        }
    }

    /** Touches an on-screen key at its centre for [holdMs]. */
    private fun touch(keycode: Int, holdMs: Long = 60) {
        onMain { MokyaImeService.trace { "test: touch $keycode for $holdMs ms" } }
        val point = onMain { MokyaImeService.current?.keyCenterOnScreen(keycode) }
        assertNotNull("key $keycode not on screen", point)
        tapAt(point!!, holdMs)
    }

    private fun restartField(inputType: Int, imeOptions: Int = EditorInfo.IME_NULL) {
        scenario.onActivity { activity ->
            field.inputType = inputType
            field.imeOptions = imeOptions
            activity.getSystemService(InputMethodManager::class.java).restartInput(field)
        }
    }

    private fun waitForQwerty() {
        waitFor("QWERTY layout") {
            onMain { MokyaImeService.current!!.modeForTest == InputMode.DIRECT && MokyaImeService.current!!.textKeyCenterOnScreen("q") != null }
        }
    }

    private fun tapText(normal: String) {
        val point = onMain { MokyaImeService.current?.textKeyCenterOnScreen(normal) }
        assertNotNull("key '$normal' not on screen", point)
        tapAt(point!!)
    }

    private fun tapShift() {
        val point = onMain { MokyaImeService.current?.shiftKeyCenterOnScreen() }
        assertNotNull("Shift not on screen", point)
        tapAt(point!!)
    }

    /** Touches the screen at [point] for [holdMs]. */
    private fun tapAt(point: PointF, holdMs: Long = 60) {
        onMain { MokyaImeService.trace { "test: tap at (${point.x}, ${point.y})" } }
        val down = SystemClock.uptimeMillis()
        inject(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, point.x, point.y, 0))
        SystemClock.sleep(holdMs)
        inject(MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, point.x, point.y, 0))
        instrumentation.waitForIdleSync()
    }

    private fun inject(event: MotionEvent) {
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        check(instrumentation.uiAutomation.injectInputEvent(event, true)) { "injectInputEvent failed" }
        event.recycle()
    }
}
