// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime

import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.PointF
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.inputmethodservice.InputMethodService.Insets
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.Log
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import io.github.tengigabytes.mokyaime.engine.InputMode
import io.github.tengigabytes.mokyaime.engine.MieEngine
import io.github.tengigabytes.mokyaime.engine.MieListener
import io.github.tengigabytes.mokyaime.engine.MokyaKeys
import io.github.tengigabytes.mokyaime.engine.NavDirection
import io.github.tengigabytes.mokyaime.engine.PendingStyle
import io.github.tengigabytes.mokyaime.engine.PendingView
import io.github.tengigabytes.mokyaime.input.CandidateNavigation
import io.github.tengigabytes.mokyaime.input.EditorPolicy
import io.github.tengigabytes.mokyaime.input.EngineState
import io.github.tengigabytes.mokyaime.input.EnterAction
import io.github.tengigabytes.mokyaime.input.HardwareAction
import io.github.tengigabytes.mokyaime.input.HardwareKey
import io.github.tengigabytes.mokyaime.input.HardwareKeyMapper
import io.github.tengigabytes.mokyaime.input.SpecialKey
import io.github.tengigabytes.mokyaime.ui.CandidateStripView
import io.github.tengigabytes.mokyaime.ui.KeyboardView
import java.io.File
import java.io.IOException

/**
 * Input method service hosting the MokyaInput Engine.
 *
 * Input comes from the on-screen half-keyboard ([KeyboardView]) and from
 * hardware keyboards ([HardwareKeyMapper]); both end up in [dispatchKey].
 * Engine events are mapped onto the editor's InputConnection:
 *
 * | Engine           | Editor                                                  |
 * |------------------|---------------------------------------------------------|
 * | on_commit        | commitText (idle OK `"\n"` → sendKeyChar)               |
 * | pending_view     | setComposingText (matched prefix in bold)               |
 * | on_delete_before | deleteSurroundingText(1, 0) (2 for a surrogate pair)    |
 * | on_cursor_move   | sendDownUpKeyEvents(KEYCODE_DPAD_*)                     |
 * | set_text_context | getTextBeforeCursor(2, 0) in onUpdateSelection          |
 * | abort            | onFinishInput, or the cursor moved externally           |
 *
 * Everything runs on the main thread. Engine callbacks never call back into
 * the engine: on_composition_changed only marks the composition dirty, and
 * the composing text and UI are synced after the engine call returns
 * ([runEngine]). The engine's time base is SystemClock.uptimeMillis(), the
 * same base as KeyEvent.getEventTime().
 */
class MokyaImeService : InputMethodService(), MieListener {

    internal companion object {
        private const val TAG = "MokyaIme"
        private const val TICK_INTERVAL_MS = 20L
        private const val MODE_FLASH_MS = 1500L
        private const val LRU_FILE_NAME = "mie_lru.bin"
        private const val PREFS_NAME = "mokya_ime"
        private const val PREF_MODE = "mode"
        private const val MAX_EXPECTED_REPORTS = 32
        private val NOTHING_SHOWN = PendingView("", 0, PendingStyle.NONE)

        /** The running service, for instrumentation tests (same process). */
        @Volatile
        internal var current: MokyaImeService? = null
            private set

        /**
         * Set by instrumentation tests to record lifecycle and key events,
         * which they print when an assertion fails. Null otherwise.
         */
        @Volatile
        internal var traceForTest: MutableList<String>? = null

        /** True while a test or the device [Diagnostics] record the trace. */
        internal val tracing: Boolean get() = traceForTest != null || Diagnostics.ring != null

        /**
         * True while the editor must not learn from what is typed (passwords,
         * incognito): [traceInput] then keeps keys out of the diagnostics.
         */
        @Volatile
        internal var sensitiveInput = false

        /**
         * Records an event for [traceForTest] and the device [Diagnostics],
         * and in logcat (tag MokyaTrace) so CI can print the trace of passing
         * runs too; main thread only.
         */
        internal inline fun trace(event: () -> String) {
            val test = traceForTest
            val ring = Diagnostics.ring
            if (test == null && ring == null) return
            val line = "${SystemClock.uptimeMillis()} ${event()}"
            test?.add(line)
            ring?.add(line)
            Log.i("MokyaTrace", line)
        }

        /**
         * As [trace], for events that tell what is typed (keys, touch
         * positions). In a [sensitiveInput] field the device diagnostics get
         * a placeholder instead; tests, which type into their own field, get
         * the event.
         */
        internal inline fun traceInput(event: () -> String) {
            if (sensitiveInput && traceForTest == null) trace { "input in a sensitive field (not recorded)" } else trace(event)
        }
    }

    private var engine: MieEngine? = null
    private lateinit var lruStore: LruStore
    private lateinit var prefs: SharedPreferences

    private val handler = Handler(Looper.getMainLooper())
    private val tickRunnable = Runnable { onTick() }
    private val refreshRunnable = Runnable { refreshUi() }
    private var tickScheduled = false

    private var keyboardView: KeyboardView? = null
    private var candidateStrip: CandidateStripView? = null
    private var modeFlashUntil = 0L
    private var candidatesShown: Boolean? = null

    /** Mode the user picked last in an editor without a required mode. */
    private var userMode = InputMode.SMART_ZH
    private var editorForcesMode = false

    /** LRU state to restore at the end of an editor that must not be learned from. */
    private var lruSnapshot: ByteArray? = null

    /** Hardware key-downs we handled; their key-ups are swallowed too. */
    private val consumedKeyDowns = HashSet<Int>()

    // ── Editor-state tracking ────────────────────────────────────────────
    //
    // onUpdateSelection reports arrive asynchronously, possibly after we
    // have already issued further edits. To tell our own edits apart from
    // the user moving the cursor, we predict where each batch of our edits
    // leaves the (collapsed) cursor and match the reports against that.

    /** Composing text currently shown in the editor (as last sent). */
    private var shown: PendingView = NOTHING_SHOWN

    /** Cursor position after all edits issued so far; -1 when unknown. */
    private var expectedCursor = -1

    /** Cursor positions our pending edit batches will be reported at, oldest first. */
    private val expectedReports = ArrayDeque<Int>()

    /** Set while aborting for an external edit: engine callbacks must not edit. */
    private var detached = false

    /** The engine reported a composition change during the current call. */
    private var compositionDirty = false

    // ── Lifecycle ────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        current = this
        lruStore = LruStore(File(filesDir, LRU_FILE_NAME))
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        Diagnostics.init(this)
        userMode = InputMode.entries.getOrElse(prefs.getInt(PREF_MODE, 0)) { InputMode.SMART_ZH }
        engine = try {
            MieEngine.create(DictionaryAsset.map(assets), this)
        } catch (e: IOException) {
            Log.e(TAG, "Dictionary asset ${DictionaryAsset.NAME} could not be mapped", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Dictionary asset ${DictionaryAsset.NAME} was rejected", e)
            null
        }
        engine?.let { e ->
            lruStore.load()?.let { saved ->
                if (!e.loadLru(saved)) Log.w(TAG, "Ignoring unreadable LRU file")
            }
        }
    }

    override fun onDestroy() {
        stopTick()
        handler.removeCallbacks(refreshRunnable)
        saveLru()
        engine?.close()
        engine = null
        if (current === this) current = null
        super.onDestroy()
    }

    override fun onCreateInputView(): View =
        KeyboardView(this).also { view ->
            view.onKey = { keycode, pressed, flags -> dispatchKey(keycode, pressed, flags) }
            view.onText = ::commitLiteral
            engine?.let { view.mode = it.mode }
            keyboardView = view
        }

    override fun onCreateCandidatesView(): View =
        CandidateStripView(this).also { strip ->
            strip.onItemTapped = ::onCandidateTapped
            strip.onModeTapped = {
                dispatchKey(MokyaKeys.KEY_MODE, true)
                dispatchKey(MokyaKeys.KEY_MODE, false)
            }
            candidateStrip = strip
        }

    /** Never take over the screen in landscape; the strip shows the composition. */
    override fun onEvaluateFullscreenMode(): Boolean = false

    private var lastInsetsTrace = ""

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        // With the keyboard up the strip is always shown: let the app's content
        // end above it, so it never covers the field being typed into. (The
        // strip alone, for a hardware keyboard, still floats over the app.)
        if (isInputViewShown) outInsets.contentTopInsets = outInsets.visibleTopInsets
        if (tracing) {   // diagnostics: where the IME accepts touches
            val now = "insets content=${outInsets.contentTopInsets} visible=${outInsets.visibleTopInsets} " +
                "touchable=${outInsets.touchableInsets} region=${outInsets.touchableRegion.bounds}"
            if (now != lastInsetsTrace) {
                lastInsetsTrace = now
                trace { now }
            }
        }
    }

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        sensitiveInput = EditorPolicy.forbidsLearning(attribute.inputType, attribute.imeOptions)
        trace {
            "startInput restarting=$restarting pkg=${attribute.packageName} field=${attribute.fieldId} " +
                "type=0x${attribute.inputType.toString(16)} options=0x${attribute.imeOptions.toString(16)} " +
                "sensitive=$sensitiveInput sel=${attribute.initialSelStart}..${attribute.initialSelEnd}"
        }
        // Only matters when input restarts in the same editor: a new editor
        // starts with an empty engine (onFinishInput aborted it).
        runEngine { it.abort() }
        resetTracking(attribute.initialSelStart, attribute.initialSelEnd)
        selectionEndForTest = attribute.initialSelEnd
        consumedKeyDowns.clear()

        val required = EditorPolicy.requiredMode(attribute.inputType)
        editorForcesMode = required != null
        switchMode(required ?: userMode)
        lruSnapshot = if (sensitiveInput) {
            engine?.serializeLru()
        } else {
            null
        }
        syncTextContext()
        refreshUi()
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        trace { "startInputView restarting=$restarting" }
        keyboardView?.idleOkLabel = enterLabel(EnterAction.of(info.imeOptions))
        keyboardView?.resetShift()
        refreshUi()
    }

    private fun enterLabel(action: EnterAction): String = when (action) {
        EnterAction.NEWLINE -> "↵"
        EnterAction.GO -> getString(R.string.enter_go)
        EnterAction.SEARCH -> getString(R.string.enter_search)
        EnterAction.SEND -> getString(R.string.enter_send)
        EnterAction.NEXT -> getString(R.string.enter_next)
        EnterAction.DONE -> getString(R.string.enter_done)
        EnterAction.PREVIOUS -> getString(R.string.enter_previous)
    }

    override fun onWindowShown() {
        super.onWindowShown()
        trace { "windowShown" }
        refreshUi()
    }

    override fun onWindowHidden() {
        trace { "windowHidden" }
        super.onWindowHidden()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        trace {
            val night = (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            "configuration orientation=${newConfig.orientation} night=$night fontScale=${newConfig.fontScale} " +
                "screen=${newConfig.screenWidthDp}x${newConfig.screenHeightDp}dp hardKeyboardHidden=${newConfig.hardKeyboardHidden}"
        }
        super.onConfigurationChanged(newConfig)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        trace { "finishInputView finishingInput=$finishingInput" }
        keyboardView?.cancelTouches()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        trace { "finishInput" }
        runEngine { it.abort() }   // discards pending input and clears our composing text
        stopTick()
        lruSnapshot?.let { engine?.loadLru(it) }   // forget what this editor taught
        lruSnapshot = null
        saveLru()
        resetTracking(-1, -1)
        consumedKeyDowns.clear()
        sensitiveInput = false
        super.onFinishInput()
    }

    // ── Input ────────────────────────────────────────────────────────────

    /**
     * Feeds one MIE key edge (a [MokyaKeys] value) from the on-screen
     * keyboard, a hardware key or a test. For a long press of a Bopomofo key
     * pass `MokyaKeys.KEY_FLAG_LONG_PRESS`; SYM1 needs both edges, other keys
     * act on press. [eventTimeMs] is on the uptimeMillis() time base.
     *
     * Up / Down on a non-empty candidate list move by one page (the engine
     * leaves vertical navigation to the view).
     */
    internal fun dispatchKey(
        keycode: Int,
        pressed: Boolean,
        flags: Int = 0,
        eventTimeMs: Long = SystemClock.uptimeMillis(),
    ) {
        val e = engine ?: return
        traceInput { "key $keycode pressed=$pressed flags=$flags" }
        if ((keycode == MokyaKeys.KEY_UP || keycode == MokyaKeys.KEY_DOWN) && !e.pickerActive) {
            val count = e.candidates().size
            if (count > 0) {
                if (pressed) {
                    runEngine {
                        it.selectedCandidate =
                            CandidateNavigation.pageJump(it.selectedCandidate, count, keycode == MokyaKeys.KEY_DOWN)
                    }
                }
                return
            }
        }
        val modeBefore = e.mode
        runEngine { it.processKey(keycode, pressed, eventTimeMs, flags) }
        if (e.mode != modeBefore) onModeChanged(e.mode)
    }

    private fun onCandidateTapped(index: Int) {
        trace { "candidate $index tapped" }
        runEngine { e ->
            val now = SystemClock.uptimeMillis()
            if (e.pickerActive) {
                // The picker selection only moves with the D-pad.
                val steps = Math.floorMod(index - e.pickerSelected, e.pickerCells().size)
                repeat(steps) { tap(e, MokyaKeys.KEY_RIGHT, now) }
            } else {
                e.selectedCandidate = index
            }
            tap(e, MokyaKeys.KEY_OK, now)
        }
    }

    private fun onModeChanged(mode: InputMode) {
        if (!editorForcesMode) {
            userMode = mode
            prefs.edit().putInt(PREF_MODE, mode.ordinal).apply()
        }
        // Without the on-screen keyboard, show the strip briefly so the new
        // mode is visible.
        modeFlashUntil = SystemClock.uptimeMillis() + MODE_FLASH_MS
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, MODE_FLASH_MS)
        refreshUi()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val e = engine
        val editor = currentInputEditorInfo
        if (e == null || currentInputConnection == null || editor == null ||
            editor.inputType == InputType.TYPE_NULL
        ) {
            return super.onKeyDown(keyCode, event)
        }
        val hasCandidates = e.candidates().isNotEmpty()
        val state = EngineState(e.mode, e.hasPending || e.pickerActive || hasCandidates, hasCandidates)
        when (val action = HardwareKeyMapper.map(hardwareKey(event), state)) {
            is HardwareAction.Engine -> {
                dispatchKey(action.keycode, true, action.flags, event.eventTime)
                dispatchKey(action.keycode, false, action.flags, event.eventTime)
            }
            is HardwareAction.Literal -> commitLiteral(action.text)
            HardwareAction.Abort -> runEngine { it.abort() }
            HardwareAction.Consume -> Unit
            HardwareAction.PassThrough -> return super.onKeyDown(keyCode, event)
        }
        consumedKeyDowns += keyCode
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        if (consumedKeyDowns.remove(keyCode)) true else super.onKeyUp(keyCode, event)

    private fun hardwareKey(event: KeyEvent): HardwareKey {
        val special = when (event.keyCode) {
            KeyEvent.KEYCODE_SPACE -> SpecialKey.SPACE
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> SpecialKey.ENTER
            KeyEvent.KEYCODE_DEL -> SpecialKey.DEL
            KeyEvent.KEYCODE_DPAD_LEFT -> SpecialKey.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> SpecialKey.RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> SpecialKey.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> SpecialKey.DOWN
            KeyEvent.KEYCODE_TAB -> SpecialKey.TAB
            KeyEvent.KEYCODE_ESCAPE -> SpecialKey.ESCAPE
            KeyEvent.KEYCODE_LANGUAGE_SWITCH -> SpecialKey.LANGUAGE_SWITCH
            else -> null
        }
        // The numeric keypad types digits, never Dachen phonemes.
        val numpad = event.keyCode in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_EQUALS
        return HardwareKey(
            baseChar = if (special != null || numpad) null else event.getUnicodeChar(0).toKeyChar(),
            char = event.unicodeChar.toKeyChar(),
            special = special,
            shift = event.isShiftPressed,
            ctrl = event.isCtrlPressed,
            alt = event.isAltPressed,
            meta = event.isMetaPressed,
            repeat = event.repeatCount > 0,
        )
    }

    private fun Int.toKeyChar(): Char? =
        if (this > 0 && this <= 0xFFFF && (this and KeyCharacterMap.COMBINING_ACCENT) == 0) toChar() else null

    /** Commits pending input the way OK would, then inserts [text]. */
    private fun commitLiteral(text: String) {
        runEngine { e ->
            when {
                e.pickerActive -> e.abort()
                e.hasPending || e.candidates().isNotEmpty() -> tap(e, MokyaKeys.KEY_OK, SystemClock.uptimeMillis())
            }
            commitToEditor(text)
        }
    }

    private fun tap(e: MieEngine, keycode: Int, nowMs: Long) {
        e.processKey(keycode, true, nowMs)
        e.processKey(keycode, false, nowMs)
    }

    private fun switchMode(target: InputMode) {
        val e = engine ?: return
        repeat(InputMode.entries.size) {
            if (e.mode == target) return
            runEngine { tap(it, MokyaKeys.KEY_MODE, SystemClock.uptimeMillis()) }
        }
    }

    // ── MieListener: engine → editor ─────────────────────────────────────

    override fun onCommit(text: String) {
        if (detached) return
        if (text == "\n") {
            // Idle OK. sendKeyChar runs the editor action (send / search /
            // next ...) when there is one, otherwise sends Enter.
            sendKeyChar('\n')
            expectedCursor = -1
            return
        }
        commitToEditor(text)
    }

    override fun onCompositionChanged() {
        compositionDirty = true   // synced in runEngine, once the engine call returns
    }

    override fun onDeleteBefore() {
        if (detached) return
        val ic = currentInputConnection ?: return
        if (!ic.getSelectedText(0).isNullOrEmpty()) {
            ic.commitText("", 1)   // delete the selection
            expectedCursor = -1
            return
        }
        val before = ic.getTextBeforeCursor(2, 0)
        if (before.isNullOrEmpty()) {
            // Nothing (visible) to delete: let the app see a DEL key, e.g. to
            // remove a recipient chip.
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            expectedCursor = -1
            return
        }
        // deleteSurroundingText counts UTF-16 units; never split a surrogate pair.
        val n = if (before.length == 2 && Character.isSurrogatePair(before[0], before[1])) 2 else 1
        ic.deleteSurroundingText(n, 0)
        if (expectedCursor >= 0) expectedCursor = maxOf(0, expectedCursor - n)
    }

    override fun onCursorMove(direction: NavDirection) {
        if (detached) return
        sendDownUpKeyEvents(
            when (direction) {
                NavDirection.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
                NavDirection.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
                NavDirection.UP -> KeyEvent.KEYCODE_DPAD_UP
                NavDirection.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
            },
        )
        expectedCursor = -1   // the editor decides where the cursor lands
    }

    private fun commitToEditor(text: String) {
        val ic = currentInputConnection ?: return
        ic.commitText(text, 1)   // replaces the composing text, if any
        if (expectedCursor >= 0) expectedCursor += text.length - shown.text.length
        shown = NOTHING_SHOWN
    }

    private fun showComposition() {
        val e = engine ?: return
        val ic = currentInputConnection ?: return
        val pending = e.pending()
        if (pending == shown) return
        ic.setComposingText(styled(pending), 1)   // "" removes the composing text
        if (expectedCursor >= 0) expectedCursor += pending.text.length - shown.text.length
        shown = pending
    }

    private fun styled(pending: PendingView): CharSequence {
        if (pending.style != PendingStyle.PREFIX_BOLD || pending.matchedPrefixLength <= 0) {
            return pending.text
        }
        return SpannableString(pending.text).apply {
            setSpan(
                StyleSpan(Typeface.BOLD), 0, pending.matchedPrefixLength,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    // ── Editor → engine ──────────────────────────────────────────────────

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        trace { "selection $newSelStart..$newSelEnd composing=$candidatesStart..$candidatesEnd" }
        selectionEndForTest = newSelEnd
        val e = engine ?: return
        val collapsed = newSelStart == newSelEnd

        val reportIndex = if (collapsed) expectedReports.indexOf(newSelEnd) else -1
        if (reportIndex >= 0) {
            repeat(reportIndex + 1) { expectedReports.removeFirst() }   // one of our edits
        } else if (e.hasPending) {
            val showsOurComposition = collapsed && candidatesStart >= 0 &&
                newSelEnd == candidatesEnd &&
                candidatesEnd - candidatesStart == shown.text.length
            if (!showsOurComposition) {
                // The user or the app moved the cursor or changed the text.
                discardComposition(newSelStart, newSelEnd, candidatesStart, candidatesEnd)
                return
            }
            if (expectedCursor < 0) expectedCursor = newSelEnd
        } else {
            // Not ours and nothing pending: just learn where the cursor is.
            expectedCursor = if (collapsed) newSelEnd else -1
            expectedReports.clear()
        }

        // Keep SmartEn spacing / capitalisation in step with the real text,
        // once our own edits have landed.
        if (!e.hasPending && candidatesStart < 0 && expectedReports.isEmpty()) {
            syncTextContext()
        }
    }

    /**
     * Drops the pending composition after an external cursor move or edit,
     * leaving the cursor where the user put it.
     */
    private fun discardComposition(selStart: Int, selEnd: Int, composingStart: Int, composingEnd: Int) {
        trace { "discardComposition" }
        engine?.let { e ->
            detached = true
            try {
                e.abort()
            } finally {
                detached = false
                compositionDirty = false
            }
        }
        stopTick()
        expectedReports.clear()
        shown = NOTHING_SHOWN
        refreshUi()

        val ic = currentInputConnection
        if (ic == null || composingStart < 0 || composingEnd <= composingStart) {
            expectedCursor = if (selStart == selEnd) selEnd else -1
            return
        }
        // Remove the pending phonemes, then restore the user's selection,
        // shifted for the removed text.
        val removed = composingEnd - composingStart
        fun shift(pos: Int) = when {
            pos >= composingEnd -> pos - removed
            pos > composingStart -> composingStart
            else -> pos
        }
        val start = shift(selStart)
        val end = shift(selEnd)
        ic.beginBatchEdit()
        ic.setComposingText("", 1)
        ic.setSelection(start, end)
        ic.endBatchEdit()
        expectedCursor = if (start == end) end else -1
        if (expectedCursor >= 0) expectedReports.addLast(expectedCursor)
    }

    private fun syncTextContext() {
        val e = engine ?: return
        val ic = currentInputConnection ?: return
        var before: CharSequence = ic.getTextBeforeCursor(2, 0) ?: return
        // Two UTF-16 units can cut a surrogate pair in half; MIE needs whole
        // code points.
        if (before.isNotEmpty() && Character.isLowSurrogate(before[0])) {
            before = before.subSequence(1, before.length)
        }
        e.setTextContext(before)
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Runs one engine call as a single editor batch: the edits it produces
     * (commits during the call, then the composing text once it returns)
     * reach the editor as one consistent state, whose cursor position is
     * recorded for [onUpdateSelection]. Then refreshes timers and UI.
     */
    private inline fun <T> runEngine(block: (MieEngine) -> T): T? {
        val e = engine ?: return null
        val ic = currentInputConnection
        val cursorBefore = expectedCursor
        val shownBefore = shown
        ic?.beginBatchEdit()
        try {
            val result = block(e)
            if (compositionDirty) {
                compositionDirty = false
                showComposition()
            }
            return result
        } finally {
            ic?.endBatchEdit()
            val edited = expectedCursor != cursorBefore || shown != shownBefore
            if (edited && expectedCursor >= 0) {
                expectedReports.addLast(expectedCursor)
                while (expectedReports.size > MAX_EXPECTED_REPORTS) expectedReports.removeFirst()
            }
            scheduleTick()
            refreshUi()
        }
    }

    /** Pushes engine state to the keyboard, the strip and the candidates area. */
    private fun refreshUi() {
        val e = engine ?: return
        keyboardView?.mode = e.mode
        val picker = e.pickerActive
        val items = if (picker) e.pickerCells() else e.candidates()
        val selected = when {
            picker -> e.pickerSelected
            items.isEmpty() -> -1
            else -> e.selectedCandidate
        }
        candidateStrip?.show(CandidateStripView.State(e.mode.indicator, items, selected, picker))
        val composing = e.hasPending || items.isNotEmpty()
        keyboardView?.composing = composing
        val showCandidates = isInputViewShown || composing || SystemClock.uptimeMillis() < modeFlashUntil
        if (showCandidates != candidatesShown) {
            candidatesShown = showCandidates
            trace { "candidates view shown=$showCandidates" }
        }
        setCandidatesViewShown(showCandidates)
        if (showCandidates) revealCandidatesArea()
    }

    /**
     * The platform (InputMethodService in the API 36 and 37 sources) sets the
     * visibility of the candidates frame's parent only when fullscreen mode
     * is re-evaluated, copying the frame's visibility at that moment. The
     * window is first shown before the strip is, so the parent stays
     * INVISIBLE and a strip shown later never appears. Show the parent too;
     * this IME is never fullscreen, where the parent holds the extract view.
     */
    private fun revealCandidatesArea() {
        val area = candidateStrip?.parent?.parent as? View ?: return
        if (area.visibility != View.VISIBLE) {
            trace { "candidates area was ${area.visibility}, made visible" }
            area.visibility = View.VISIBLE
        }
    }

    private fun resetTracking(selStart: Int, selEnd: Int) {
        shown = NOTHING_SHOWN
        expectedReports.clear()
        expectedCursor = if (selStart >= 0 && selStart == selEnd) selEnd else -1
    }

    private fun onTick() {
        tickScheduled = false
        if (runEngine { it.tick(SystemClock.uptimeMillis()) } == true) {
            trace { "tick changed state, picker=${engine?.pickerActive}" }
        }
    }

    /** Runs the 20 ms tick only while the engine has timers running. */
    private fun scheduleTick() {
        val e = engine ?: return
        if (!tickScheduled && e.needsTick) {
            tickScheduled = handler.postDelayed(tickRunnable, TICK_INTERVAL_MS)
        }
    }

    private fun stopTick() {
        handler.removeCallbacks(tickRunnable)
        tickScheduled = false
    }

    private fun saveLru() {
        val e = engine ?: return
        lruStore.save(e.serializeLru())
    }

    // ── Test hooks (instrumentation tests run in this process) ───────────

    internal val modeForTest: InputMode? get() = engine?.mode

    /** Switches mode as if the user had picked it, so later input restarts keep it. */
    internal fun switchModeForTest(mode: InputMode) {
        switchMode(mode)
        engine?.let { if (it.mode == mode) onModeChanged(mode) }
    }

    internal fun candidatesForTest(): List<String> = engine?.candidates().orEmpty()

    internal val pickerActiveForTest: Boolean get() = engine?.pickerActive == true

    /** End of the selection in the last onUpdateSelection report, or of the initial one. */
    internal var selectionEndForTest = -1
        private set

    internal val candidateStripForTest: CandidateStripView? get() = candidateStrip

    /** Screen position of an on-screen key, or null while the keyboard is not shown. */
    internal fun keyCenterOnScreen(keycode: Int): PointF? = keyboardView?.keyCenterOnScreen(keycode)

    internal fun textKeyCenterOnScreen(normal: String): PointF? = keyboardView?.textKeyCenterOnScreen(normal)

    internal fun shiftKeyCenterOnScreen(): PointF? = keyboardView?.shiftKeyCenterOnScreen()

    internal val okLabelForTest: String? get() = keyboardView?.okLabelForTest
}
