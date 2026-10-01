// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime

import android.app.Instrumentation
import android.os.SystemClock
import android.text.InputType
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the real IME on a device / emulator: enables and selects Mokya
 * IME, focuses the field in SetupActivity, then types through injected
 * hardware key events and touches on the on-screen keyboard.
 */
@RunWith(AndroidJUnit4::class)
class ImeEndToEndTest {

    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val imeId = "io.github.tengigabytes.mokyaime/.MokyaImeService"
    private lateinit var scenario: ActivityScenario<SetupActivity>
    private lateinit var field: EditText

    @Before
    fun setUp() {
        shell("ime enable $imeId")
        shell("ime set $imeId")
        // Keep the on-screen keyboard even if the emulator reports a hardware keyboard.
        shell("settings put secure show_ime_with_hard_keyboard 1")

        scenario = ActivityScenario.launch(SetupActivity::class.java)
        scenario.onActivity { activity ->
            field = activity.findViewById(R.id.try_field)
            field.requestFocus()
            activity.getSystemService(InputMethodManager::class.java).showSoftInput(field, 0)
        }
        waitFor("IME bound to the field") {
            val ime = MokyaImeService.current
            ime != null && ime.currentInputStarted &&
                ime.currentInputEditorInfo?.packageName == instrumentation.targetContext.packageName
        }
        onMain { MokyaImeService.current!!.switchModeForTest(InputMode.SMART_ZH) }
    }

    @After
    fun tearDown() {
        scenario.close()
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
        scenario.onActivity { activity ->
            field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            activity.getSystemService(InputMethodManager::class.java).restartInput(field)
        }
        waitFor("Direct mode") { onMain { MokyaImeService.current!!.modeForTest } == InputMode.DIRECT }
        keys("ab1")
        waitForText("ab1")
    }

    @Test
    fun externalCursorMoveDropsComposition() {
        scenario.onActivity { field.setText("xy"); field.setSelection(2) }
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
    fun touchTapsAndOk() {
        waitForKeyboard()
        touch(MokyaKeys.KEY_A)   // ㄇㄋ
        touch(MokyaKeys.KEY_C)   // ㄏㄒ
        waitForText("ㄇㄋ, ㄏㄒ")
        val first = onMain { MokyaImeService.current!!.candidatesForTest().first() }
        touch(MokyaKeys.KEY_OK)   // commits the highlighted (first) candidate
        waitForText(first)
    }

    @Test
    fun touchLongPressPinsAndCyclesPhoneme() {
        waitForKeyboard()
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
        waitForKeyboard()
        touch(MokyaKeys.KEY_SYM1, holdMs = 800)
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
        fail("Timed out waiting for $what")
    }

    private fun waitForText(expected: String) {
        var last = ""
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            last = onMain { field.text.toString() }
            if (last == expected) return
            SystemClock.sleep(50)
        }
        assertEquals(expected, last)
    }

    private fun waitForKeyboard() {
        waitFor("on-screen keyboard") { onMain { MokyaImeService.current?.keyCenterOnScreen(MokyaKeys.KEY_OK) } != null }
    }

    private fun key(keyCode: Int, metaState: Int = 0) {
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
        val point = onMain { MokyaImeService.current?.keyCenterOnScreen(keycode) }
        assertNotNull("key $keycode not on screen", point)
        val down = SystemClock.uptimeMillis()
        inject(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, point!!.x, point.y, 0))
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
