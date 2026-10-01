// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime.engine

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * Optional smoke test against a full generated dictionary, loaded the way
 * the app loads its asset (a read-only memory map). Runs only when
 * -Pmokya.realDict=/path/to/dict_mie_v4.bin is passed to Gradle.
 */
class RealDictionarySmokeTest {

    @Test
    fun typesChineseAndEnglish() {
        val path = System.getProperty("mokya.realDict").orEmpty()
        assumeTrue("mokya.realDict not set", path.isNotEmpty())

        val mapped = RandomAccessFile(File(path), "r").use { raf ->
            raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
        }
        val commits = mutableListOf<String>()
        val listener = object : MieListener {
            override fun onCommit(text: String) { commits += text }
            override fun onCursorMove(direction: NavDirection) {}
            override fun onDeleteBefore() {}
            override fun onCompositionChanged() {}
        }

        MieEngine.create(mapped, listener).use { engine ->
            assertTrue(engine.hasEnglish)
            var now = 0L
            fun tap(key: Int) { now += 50; engine.processKey(key, true, now) }

            tap(MokyaKeys.KEY_A)   // ㄇ/ㄋ
            tap(MokyaKeys.KEY_C)   // ㄏ/ㄒ
            assertContains(engine.candidates(), "你好")

            engine.abort()
            tap(MokyaKeys.KEY_MODE)   // SmartZh -> SmartEn
            for (key in listOf(MokyaKeys.KEY_G, MokyaKeys.KEY_E, MokyaKeys.KEY_L, MokyaKeys.KEY_L, MokyaKeys.KEY_O)) {
                tap(key)
            }
            assertContains(engine.candidates(), "hello")
        }
    }
}
