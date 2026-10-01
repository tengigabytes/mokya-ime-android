// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime

import android.content.res.AssetManager
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * The MIE4 v4 dictionary packaged as an uncompressed asset (generated at
 * build time by the generateMieDict Gradle task).
 */
internal object DictionaryAsset {
    const val NAME = "dict_mie_v4.bin"

    /**
     * Memory-maps the dictionary read-only straight from the APK. The
     * mapping stays valid after the file descriptor is closed; the engine
     * keeps a reference to the buffer for as long as it lives.
     *
     * @throws java.io.IOException if the asset is missing or compressed.
     */
    fun map(assets: AssetManager): MappedByteBuffer =
        assets.openFd(NAME).use { afd ->
            FileInputStream(afd.fileDescriptor).use { stream ->
                stream.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            }
        }
}
