// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime

import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Persists the engine's personalised LRU (libmie LRU1 blob) in a private
 * file. Writes are atomic and skipped when nothing changed since the last
 * load or save.
 */
internal class LruStore(file: File) {

    private val file = AtomicFile(file)
    private var lastWritten: ByteArray? = null

    fun load(): ByteArray? =
        try {
            file.readFully().also { lastWritten = it }
        } catch (e: FileNotFoundException) {
            null
        } catch (e: IOException) {
            Log.w(TAG, "Could not read LRU file", e)
            null
        }

    fun save(data: ByteArray) {
        if (lastWritten?.contentEquals(data) == true) return
        val out = try {
            file.startWrite()
        } catch (e: IOException) {
            Log.w(TAG, "Could not open LRU file for writing", e)
            return
        }
        try {
            out.write(data)
            file.finishWrite(out)
            lastWritten = data
        } catch (e: IOException) {
            file.failWrite(out)
            Log.w(TAG, "Could not write LRU file", e)
        }
    }

    private companion object {
        const val TAG = "MokyaIme"
    }
}
