package io.github.tengigabytes.mokyaime.engine

import java.nio.ByteBuffer

/** Input mode (mirrors `mie::InputMode`). */
enum class InputMode(val indicator: String) {
    SMART_ZH("中"),
    SMART_EN("EN"),
    DIRECT("ABC"),
}

/** How to draw the pending composition (mirrors `mie::PendingStyle`). */
enum class PendingStyle {
    /** Nothing pending. */
    NONE,
    /** Bold the first [PendingView.matchedPrefixLength] chars, rest normal. */
    PREFIX_BOLD,
    /** Draw the whole string inverted (multi-tap / punctuation cycling). */
    INVERTED,
}

/** Snapshot of the pending composition. Lengths are in UTF-16 chars. */
data class PendingView(
    val text: String,
    val matchedPrefixLength: Int,
    val style: PendingStyle,
)

/**
 * Kotlin facade over one native `mie::ImeLogic` instance.
 *
 * Not thread-safe: create and use it on a single thread (the main thread in
 * the IME service), and do not call it from inside [MieListener] callbacks.
 *
 * @see create
 */
class MieEngine private constructor(private var handle: Long) : AutoCloseable {

    companion object {
        /** Candidates per page (`ImeLogic::kPageSize`). */
        const val PAGE_SIZE = 5

        /**
         * Creates an engine over a MIE4 v4 dictionary.
         *
         * @param dictionary a direct buffer (memory-mapped asset or
         *   `ByteBuffer.allocateDirect`) holding the whole dictionary from
         *   index 0 to its capacity. The engine reads it in place and keeps
         *   a reference to it until [close].
         * @throws IllegalArgumentException if the buffer is not direct or
         *   is not a MIE4 v4 dictionary.
         */
        fun create(dictionary: ByteBuffer, listener: MieListener): MieEngine {
            require(dictionary.isDirect) { "dictionary must be a direct ByteBuffer" }
            return MieEngine(MieNative.create(dictionary, NativeCallbacks(listener)))
        }
    }

    private fun h(): Long {
        check(handle != 0L) { "MieEngine is closed" }
        return handle
    }

    /** True when the dictionary carries the embedded English section (SmartEn prediction). */
    val hasEnglish: Boolean get() = MieNative.hasEnglish(h())

    /**
     * Feeds one key edge. [keycode] is a [MokyaKeys] value; [nowMs] must be
     * monotonic and share its time base with [tick]. SYM1 needs both the
     * press and the release; other keys act on press.
     *
     * @return true if engine state changed.
     */
    fun processKey(keycode: Int, pressed: Boolean, nowMs: Long, flags: Int = 0): Boolean =
        MieNative.processKey(h(), keycode, pressed, nowMs, flags)

    /** Advances multi-tap and long-press timers. Call about every 20 ms while [needsTick]. */
    fun tick(nowMs: Long): Boolean = MieNative.tick(h(), nowMs)

    /**
     * True while timers are running: something is pending (multi-tap
     * auto-commit) or SYM1 is held (long-press picker).
     */
    val needsTick: Boolean get() = MieNative.needsTick(h())

    /** Discards pending input without committing. */
    fun abort() = MieNative.abort(h())

    /**
     * Tells the engine the text before the cursor (up to two code points;
     * null or empty at the start of a field), so SmartEn spacing and
     * capitalisation follow the editor's real contents.
     */
    fun setTextContext(textBeforeCursor: CharSequence?) =
        MieNative.setTextContext(h(), textBeforeCursor?.toString())

    val hasPending: Boolean get() = MieNative.hasPending(h())

    fun pending(): PendingView {
        val handle = h()
        return PendingView(
            text = MieNative.pendingText(handle),
            matchedPrefixLength = MieNative.pendingMatchedPrefix(handle),
            style = PendingStyle.entries[MieNative.pendingStyle(handle)],
        )
    }

    val mode: InputMode get() = InputMode.entries[MieNative.mode(h())]

    /** All candidates (at most `ImeLogic::kMaxCandidates`), best first. */
    fun candidates(): List<String> = MieNative.candidates(h()).asList()

    /** Index of the highlighted candidate in [candidates]. */
    var selectedCandidate: Int
        get() = MieNative.selected(h())
        set(value) = MieNative.setSelected(h(), value)

    /** True while the SYM1 long-press symbol picker is open. */
    val pickerActive: Boolean get() = MieNative.pickerActive(h())
    val pickerColumns: Int get() = MieNative.pickerCols(h())
    fun pickerCells(): List<String> = MieNative.pickerCells(h()).asList()
    val pickerSelected: Int get() = MieNative.pickerSelected(h())

    /** Serialises the personalised LRU (LRU1 format) for persistence. */
    fun serializeLru(): ByteArray = MieNative.serializeLru(h())

    /** Restores the LRU; returns false (and leaves it empty) if [data] is invalid. */
    fun loadLru(data: ByteArray): Boolean = MieNative.loadLru(h(), data)

    /** Frees the native engine. Further calls throw [IllegalStateException]. */
    override fun close() {
        if (handle != 0L) {
            MieNative.destroy(handle)
            handle = 0L
        }
    }
}
