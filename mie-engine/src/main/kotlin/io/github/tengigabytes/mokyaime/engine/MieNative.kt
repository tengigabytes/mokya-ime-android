// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime.engine

import java.nio.ByteBuffer

/**
 * Raw JNI entry points into `libmokyaime_jni` (app/src/main/cpp/mie_jni.cpp).
 *
 * The natives are bound with `RegisterNatives` in `JNI_OnLoad`, which must
 * match these declarations exactly (name and JVM signature); a mismatch
 * makes `System.loadLibrary` fail. Use [MieEngine] instead of calling these
 * directly.
 */
internal object MieNative {
    const val LIBRARY = "mokyaime_jni"

    init {
        System.loadLibrary(LIBRARY)
    }

    /** @param callbacks a [NativeCallbacks]; typed as Any to keep the JNI signature stable. */
    @JvmStatic external fun create(dict: ByteBuffer, callbacks: Any): Long
    @JvmStatic external fun destroy(handle: Long)
    @JvmStatic external fun hasEnglish(handle: Long): Boolean

    @JvmStatic external fun processKey(handle: Long, keycode: Int, pressed: Boolean, nowMs: Long, flags: Int): Boolean
    @JvmStatic external fun tick(handle: Long, nowMs: Long): Boolean
    @JvmStatic external fun abort(handle: Long)
    @JvmStatic external fun setTextContext(handle: Long, before: String?)
    @JvmStatic external fun needsTick(handle: Long): Boolean

    @JvmStatic external fun hasPending(handle: Long): Boolean
    @JvmStatic external fun pendingText(handle: Long): String
    @JvmStatic external fun pendingMatchedPrefix(handle: Long): Int
    @JvmStatic external fun pendingStyle(handle: Long): Int
    @JvmStatic external fun mode(handle: Long): Int

    @JvmStatic external fun candidates(handle: Long): Array<String>
    @JvmStatic external fun selected(handle: Long): Int
    @JvmStatic external fun setSelected(handle: Long, index: Int)

    @JvmStatic external fun pickerActive(handle: Long): Boolean
    @JvmStatic external fun pickerCols(handle: Long): Int
    @JvmStatic external fun pickerCells(handle: Long): Array<String>
    @JvmStatic external fun pickerSelected(handle: Long): Int

    @JvmStatic external fun serializeLru(handle: Long): ByteArray
    @JvmStatic external fun loadLru(handle: Long, data: ByteArray): Boolean
}

/**
 * Receives IImeListener events from native code. Method names and
 * signatures are looked up by mie_jni.cpp at engine creation; keep them in
 * sync (and keep them from being renamed by R8, see app/proguard-rules.pro).
 */
internal class NativeCallbacks(private val listener: MieListener) {
    fun onCommit(text: String) = listener.onCommit(text)
    fun onCursorMove(direction: Int) = listener.onCursorMove(NavDirection.entries[direction])
    fun onDeleteBefore() = listener.onDeleteBefore()
    fun onCompositionChanged() = listener.onCompositionChanged()
}
