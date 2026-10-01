package io.github.tengigabytes.mokyaime

import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import io.github.tengigabytes.mokyaime.engine.MieEngine
import io.github.tengigabytes.mokyaime.engine.MieListener
import io.github.tengigabytes.mokyaime.engine.NavDirection
import io.github.tengigabytes.mokyaime.engine.PendingStyle
import io.github.tengigabytes.mokyaime.engine.PendingView
import java.io.File
import java.io.IOException

/**
 * Input method service hosting the MokyaInput Engine.
 *
 * This is the engine plumbing only: there is no keyboard UI and no hardware
 * key mapping yet. A front end feeds MIE keys through [dispatchKey]; engine
 * events are mapped onto the editor's InputConnection:
 *
 * | Engine                     | Editor                                        |
 * |----------------------------|-----------------------------------------------|
 * | on_commit                  | commitText (idle OK `"\n"` → sendKeyChar)     |
 * | pending_view               | setComposingText (matched prefix in bold)     |
 * | on_delete_before           | deleteSurroundingText(1, 0) (2 for a surrogate pair) |
 * | on_cursor_move             | sendDownUpKeyEvents(KEYCODE_DPAD_*)           |
 * | set_text_context           | getTextBeforeCursor(2, 0) in onUpdateSelection|
 * | abort                      | onFinishInput, or the cursor moved externally |
 *
 * Everything runs on the main thread: InputMethodService callbacks, the tick
 * Handler and every engine call. The engine's time base is
 * SystemClock.uptimeMillis(), the same base as KeyEvent.getEventTime().
 */
class MokyaImeService : InputMethodService(), MieListener {

    private companion object {
        const val TAG = "MokyaIme"
        const val TICK_INTERVAL_MS = 20L
        const val LRU_FILE_NAME = "mie_lru.bin"
        const val MAX_EXPECTED_REPORTS = 32
        val NOTHING_SHOWN = PendingView("", 0, PendingStyle.NONE)
    }

    private var engine: MieEngine? = null
    private lateinit var lruStore: LruStore

    private val handler = Handler(Looper.getMainLooper())
    private val tickRunnable = Runnable { onTick() }
    private var tickScheduled = false

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

    // ── Lifecycle ────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        lruStore = LruStore(File(filesDir, LRU_FILE_NAME))
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
        saveLru()
        engine?.close()
        engine = null
        super.onDestroy()
    }

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        // Only matters when input restarts in the same editor: a new editor
        // starts with an empty engine (onFinishInput aborted it).
        engine?.abort()
        resetTracking(attribute.initialSelStart, attribute.initialSelEnd)
        syncTextContext()
    }

    override fun onFinishInput() {
        engine?.abort()   // discards pending input and clears our composing text
        stopTick()
        saveLru()
        resetTracking(-1, -1)
        super.onFinishInput()
    }

    // ── Input from a front end ───────────────────────────────────────────

    /**
     * Feeds one MIE key edge (a [io.github.tengigabytes.mokyaime.engine.MokyaKeys]
     * value). For a long press of a Bopomofo key pass
     * `MokyaKeys.KEY_FLAG_LONG_PRESS`. SYM1 needs both edges; other keys act
     * on press. [eventTimeMs] must be on the uptimeMillis() time base (for
     * hardware keys use KeyEvent.getEventTime()).
     *
     * Intended for the keyboard UI / hardware key mapping, which do not
     * exist yet.
     */
    fun dispatchKey(
        keycode: Int,
        pressed: Boolean,
        flags: Int = 0,
        eventTimeMs: Long = SystemClock.uptimeMillis(),
    ) {
        val e = engine ?: return
        editBatch { e.processKey(keycode, pressed, eventTimeMs, flags) }
        scheduleTick()
    }

    // ── MieListener: engine → editor ─────────────────────────────────────

    override fun onCommit(text: String) {
        if (detached) return
        val ic = currentInputConnection ?: return
        if (text == "\n") {
            // Idle OK. sendKeyChar runs the editor action (send / search /
            // next ...) when there is one, otherwise sends Enter.
            sendKeyChar('\n')
            expectedCursor = -1
            return
        }
        ic.commitText(text, 1)   // replaces the composing text, if any
        if (expectedCursor >= 0) expectedCursor += text.length - shown.text.length
        shown = NOTHING_SHOWN
    }

    override fun onCompositionChanged() {
        if (detached) return
        showComposition()
        scheduleTick()
        // The candidate list / symbol picker UI will refresh from here.
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
        engine?.let { e ->
            detached = true
            try {
                e.abort()
            } finally {
                detached = false
            }
        }
        stopTick()
        expectedReports.clear()
        shown = NOTHING_SHOWN

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
     * Runs one engine call as a single editor batch, so the editor reports
     * one consistent state for all the edits the call produced, and records
     * where that state should leave the cursor.
     */
    private inline fun <T> editBatch(block: () -> T): T {
        val ic = currentInputConnection
        val cursorBefore = expectedCursor
        val shownBefore = shown
        ic?.beginBatchEdit()
        try {
            return block()
        } finally {
            ic?.endBatchEdit()
            val edited = expectedCursor != cursorBefore || shown != shownBefore
            if (edited && expectedCursor >= 0) {
                expectedReports.addLast(expectedCursor)
                while (expectedReports.size > MAX_EXPECTED_REPORTS) expectedReports.removeFirst()
            }
        }
    }

    private fun resetTracking(selStart: Int, selEnd: Int) {
        shown = NOTHING_SHOWN
        expectedReports.clear()
        expectedCursor = if (selStart >= 0 && selStart == selEnd) selEnd else -1
    }

    private fun onTick() {
        tickScheduled = false
        val e = engine ?: return
        editBatch { e.tick(SystemClock.uptimeMillis()) }
        scheduleTick()
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
}
