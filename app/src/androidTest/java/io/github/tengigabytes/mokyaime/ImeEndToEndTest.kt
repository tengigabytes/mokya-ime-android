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
 * screen, with nothing learned; what the device had learned is put back
 * afterwards. Failures print the IME's event trace.
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
        // So are the learned words, which reorder the candidates: type with
        // none, and leave those of the device as they were.
        onMain { MokyaImeService.current!!.useEmptyLruForTest(true) }
        // Only a served field can request the keyboard.
        scenario.onActivity { activity ->
            activity.getSystemService(InputMethodManager::class.java).showSoftInput(field, 0)
        }
        waitForKeyboardOnScreen()
    }

    @After
    fun tearDown() {
        onMain { MokyaImeService.current?.useEmptyLruForTest(false) }
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
    fun abcSymbolPageTypesBracketsAndReturns() {
        restartField(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        waitForQwerty()
        tapText("a")
        tapPage()
        waitFor("symbol page") { onMain { MokyaImeService.current!!.textKeyCenterOnScreen("[") != null } }
        tapText("[")
        tapText("=")
        tapText("]")
        tapPage()
        waitForQwerty()
        tapText("b")
        waitForText("a[=]b")
    }

    @Test
    fun diagnosticsLeaveOutWhatIsTypedInPasswordFields() {
        val context = instrumentation.targetContext
        onMain {
            Diagnostics.setEnabled(context, true)
            Diagnostics.ring!!.clear()
        }
        try {
            restartField(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            waitForQwerty()
            onMain { MokyaImeService.traceForTest = null }   // as on a device: diagnostics only
            tapText("a")
            tapText("b")
            waitForText("ab")
            val lines = onMain { Diagnostics.ring!!.lines() }
            val dump = lines.joinToString("\n", prefix = "diagnostics:\n")
            assertTrue(dump, lines.any { "sensitive=true" in it })
            assertTrue(dump, lines.any { "input in a sensitive field" in it })
            assertTrue(dump, lines.none { "touch down" in it })
        } finally {
            onMain {
                MokyaImeService.traceForTest = trace
                Diagnostics.setEnabled(context, false)
            }
        }
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
    fun touchExpandsCandidatesAndTapsOne() {
        touch(MokyaKeys.KEY_A)   // ㄇㄋ
        touch(MokyaKeys.KEY_C)   // ㄏㄒ
        waitForText("ㄇㄋ, ㄏㄒ")
        val strip = { MokyaImeService.current!!.candidateStripForTest!! }
        waitFor("more candidates than fit") { onMain { strip().settledForTest && strip().canExpandForTest } }

        onMain { MokyaImeService.trace { "test: " + strip().geometryForTest() } }
        tapAt(onMain { strip().expandButtonCenterOnScreen() })
        waitFor("strip expanded") { onMain { strip().expandedForTest && strip().settledForTest } }
        onMain { MokyaImeService.trace { "test: " + strip().geometryForTest() } }
        val index = onMain { strip().firstItemOfRowForTest(1) }
        assertTrue("first candidate of the second row, got $index\n${traceDump()}", index > 0)
        val word = onMain { MokyaImeService.current!!.candidatesForTest()[index] }
        tapAt(onMain { strip().itemCenterOnScreen(index)!! })
        waitForText(word)
        waitFor("strip collapsed") { onMain { !strip().expandedForTest } }
    }

    @Test
    fun touchHoldAndSlidePickPhonemes() {
        touch(MokyaKeys.KEY_Q, holdMs = 600)            // held: the first symbol
        waitForText("ㄆ")
        touch(MokyaKeys.KEY_DEL)
        waitForText("")
        slide(MokyaKeys.KEY_Q, dxDp = 40f)              // slid right, no wait: the second
        waitForText("ㄊ")
        touch(MokyaKeys.KEY_DEL)
        waitForText("")
        slide(MokyaKeys.KEY_Q, dxDp = -40f)             // slid left: the first
        waitForText("ㄆ")
        touch(MokyaKeys.KEY_DEL)
        waitForText("")
    }

    @Test
    fun touchKeyOfThreePhonemes() {
        touch(MokyaKeys.KEY_9, holdMs = 600)            // ㄞㄢㄦ held: the middle one
        waitForText("ㄢ")
        touch(MokyaKeys.KEY_DEL)
        waitForText("")
        slide(MokyaKeys.KEY_9, dxDp = -40f, holdMs = 600)   // held, then slid left
        waitForText("ㄞ")
        touch(MokyaKeys.KEY_DEL)
        waitForText("")
        slide(MokyaKeys.KEY_9, dxDp = 40f)
        waitForText("ㄦ")
        touch(MokyaKeys.KEY_DEL)
        waitForText("")
    }

    @Test
    fun englishSlideSpellsLettersAndHoldingGivesCapitals() {
        onMain { MokyaImeService.current!!.switchModeForTest(InputMode.SMART_EN) }
        keys("hello")
        key(KeyEvent.KEYCODE_ENTER)
        waitForText("Hello")
        slide(MokyaKeys.KEY_Q, dxDp = 40f)                  // a new word: a space, then w
        waitForText("Hello w")
        slide(MokyaKeys.KEY_Q, dxDp = -40f)                 // the same word goes on
        waitForText("Hello wq")
        slide(MokyaKeys.KEY_E, dxDp = 40f, holdMs = 600)    // held, then slid: the capital
        waitForText("Hello wqR")
        slide(MokyaKeys.KEY_1, dxDp = 40f)                  // a digit takes no space
        waitForText("Hello wqR2")
        slide(MokyaKeys.KEY_Q, dxDp = -40f)                 // and the word goes on after it
        waitForText("Hello wqR2q")
    }

    @Test
    fun englishMarksSlideAndKeepTheirSpace() {
        onMain { MokyaImeService.current!!.switchModeForTest(InputMode.SMART_EN) }
        keys("hello")
        slide(MokyaKeys.KEY_SYM1, dxDp = -40f)              // commits the word, then "; "
        waitForText("Hello; ")
        slide(MokyaKeys.KEY_SYM1, dxDp = 40f)
        waitForText("Hello; : ")
        touch(MokyaKeys.KEY_SYM2, holdMs = 600)             // held: the middle mark
        waitForText("Hello; : ? ")
        slide(MokyaKeys.KEY_SYM2, dxDp = 40f)
        waitForText("Hello; : ? ! ")
        slide(MokyaKeys.KEY_SYM2, dxDp = -40f)
        waitForText("Hello; : ? ! . ")
        assertTrue("picker opened\n${traceDump()}", onMain { !MokyaImeService.current!!.pickerActiveForTest })
    }

    @Test
    fun touchSentenceMarks() {
        slide(MokyaKeys.KEY_SYM2, dxDp = 40f)               // slid right
        waitForText("！")
        touch(MokyaKeys.KEY_SYM2, holdMs = 600)             // held: the middle one
        waitForText("！？")
        slide(MokyaKeys.KEY_SYM2, dxDp = -40f)
        waitForText("！？。")
    }

    @Test
    fun touchCommaKeySlides() {
        touch(MokyaKeys.KEY_SYM1)
        waitForText("，")
        slide(MokyaKeys.KEY_SYM1, dxDp = -40f)
        waitForText("，、")
        slide(MokyaKeys.KEY_SYM1, dxDp = 40f)
        waitForText("，、：")
        assertTrue("picker opened\n${traceDump()}", onMain { !MokyaImeService.current!!.pickerActiveForTest })
    }

    @Test
    fun touchSym1LongPressOpensPicker() {
        touch(MokyaKeys.KEY_SYM1, holdMs = 800)
        assertTrue("symbol picker not open\n${traceDump()}", onMain { MokyaImeService.current!!.pickerActiveForTest })
        tapText("、")                            // the picker's page: one symbol, then back
        waitForText("、")
        waitFor("half-keyboard back") {
            onMain { !MokyaImeService.current!!.pickerActiveForTest && MokyaImeService.current!!.keyCenterOnScreen(MokyaKeys.KEY_Q) != null }
        }
        touch(MokyaKeys.KEY_SYM1, holdMs = 800)
        tapText("7")                            // digits are on its top row
        waitForText("、7")
        touch(MokyaKeys.KEY_SYM1, holdMs = 800)
        waitFor("picker page") { onMain { MokyaImeService.current!!.textKeyCenterOnScreen("「") != null } }
        touch(MokyaKeys.KEY_SYM1)               // SYM on the page: back without typing
        waitFor("picker closed") { onMain { !MokyaImeService.current!!.pickerActiveForTest } }
        waitForText("、7")
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

    /** Touches a key, holds it for [holdMs], slides [dxDp] sideways and lifts. */
    private fun slide(keycode: Int, dxDp: Float, holdMs: Long = 60) {
        onMain { MokyaImeService.trace { "test: slide $keycode by $dxDp dp after $holdMs ms" } }
        val point = onMain { MokyaImeService.current?.keyCenterOnScreen(keycode) }
        assertNotNull("key $keycode not on screen", point)
        val dx = dxDp * instrumentation.targetContext.resources.displayMetrics.density
        val down = SystemClock.uptimeMillis()
        inject(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, point!!.x, point.y, 0))
        SystemClock.sleep(holdMs)
        for (step in 1..4) {
            inject(MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, point.x + dx * step / 4, point.y, 0))
            SystemClock.sleep(10)
        }
        inject(MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, point.x + dx, point.y, 0))
        instrumentation.waitForIdleSync()
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

    private fun tapPage() {
        val point = onMain { MokyaImeService.current?.pageKeyCenterOnScreen() }
        assertNotNull("page key not on screen", point)
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
