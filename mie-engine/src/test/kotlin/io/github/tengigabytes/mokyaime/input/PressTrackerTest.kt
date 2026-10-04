// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime.input

import io.github.tengigabytes.mokyaime.engine.MokyaKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PressTrackerTest {

    /** Deterministic scheduler driven by [advance]. */
    private class FakeScheduler : PressTracker.Scheduler {
        private class Task(val at: Long, val action: () -> Unit) { var cancelled = false }
        private val tasks = mutableListOf<Task>()
        var now = 0L
            private set

        override fun schedule(delayMs: Long, action: () -> Unit): PressTracker.Cancellable {
            val task = Task(now + delayMs, action)
            tasks += task
            return PressTracker.Cancellable { task.cancelled = true }
        }

        fun advance(ms: Long) {
            val end = now + ms
            while (true) {
                val next = tasks.filter { !it.cancelled && it.at <= end }.minByOrNull { it.at } ?: break
                tasks -= next
                now = next.at
                next.action()
            }
            now = end
        }
    }

    private val scheduler = FakeScheduler()
    private val events = mutableListOf<String>()
    private val tracker = PressTracker(scheduler, onHold = { events += "hold:$it@${scheduler.now}" }) { kc, pressed, flags ->
        events += "${if (pressed) "down" else "up"}:$kc:$flags@${scheduler.now}"
    }

    private val q = MokyaKeys.KEY_Q
    private val first = MokyaKeys.keyFlagPhoneme(0)
    private val second = MokyaKeys.keyFlagPhoneme(1)
    private val third = MokyaKeys.keyFlagPhoneme(2)

    @Test
    fun deferredShortTapEmitsPressAndReleaseOnRelease() {
        tracker.down(0, q, deferPress = true)
        scheduler.advance(200)
        assertTrue(events.isEmpty())
        tracker.up(0)
        assertEquals(listOf("down:$q:0@200", "up:$q:0@200"), events)
        assertFalse(tracker.isActive)
    }

    @Test
    fun deferredHoldTypesTheFirstSymbolOnRelease() {
        tracker.down(0, q, deferPress = true)
        scheduler.advance(PressTracker.LONG_PRESS_MS)
        assertEquals(listOf("hold:0@500"), events)
        scheduler.advance(300)
        tracker.up(0)
        assertEquals(listOf("hold:0@500", "down:$q:$first@800", "up:$q:$first@800"), events)
    }

    @Test
    fun holdingAKeyOfThreeTypesTheMiddleSymbol() {
        val k9 = MokyaKeys.KEY_9   // ㄞㄢㄦ
        tracker.down(0, k9, deferPress = true)
        scheduler.advance(PressTracker.LONG_PRESS_MS)
        tracker.up(0)
        assertEquals(listOf("hold:0@500", "down:$k9:$second@500", "up:$k9:$second@500"), events)
    }

    @Test
    fun slidingPicksASymbolWithoutWaiting() {
        tracker.down(0, q, deferPress = true)
        scheduler.advance(80)
        tracker.pick(0, 1)
        tracker.up(0)
        assertEquals(listOf("down:$q:$second@80", "up:$q:$second@80"), events)
    }

    @Test
    fun slidingWhileHeldReplacesTheHeldSymbol() {
        val k9 = MokyaKeys.KEY_9
        tracker.down(0, k9, deferPress = true)
        scheduler.advance(600)
        tracker.pick(0, 2)
        tracker.up(0)
        assertEquals(listOf("hold:0@500", "down:$k9:$third@600", "up:$k9:$third@600"), events)
    }

    @Test
    fun slidingBackBeforeTheHoldMarkIsAFuzzyTap() {
        tracker.down(0, q, deferPress = true)
        tracker.pick(0, 1)
        tracker.pick(0, null)
        scheduler.advance(100)
        tracker.up(0)
        assertEquals(listOf("down:$q:0@100", "up:$q:0@100"), events)
    }

    @Test
    fun pickIsIgnoredOnKeysThatPressOnDown() {
        val ok = MokyaKeys.KEY_OK
        tracker.down(0, ok, deferPress = false)
        tracker.pick(0, 1)
        tracker.up(0)
        assertEquals(listOf("down:$ok:0@0", "up:$ok:0@0"), events)
    }

    @Test
    fun cancelDropsAHeldOrPickedKey() {
        tracker.down(0, q, deferPress = true)
        scheduler.advance(PressTracker.LONG_PRESS_MS)
        tracker.pick(0, 1)
        tracker.cancel(0)
        assertEquals(listOf("hold:0@500"), events)
    }

    @Test
    fun immediateKeyPressesOnDown() {
        val sym1 = MokyaKeys.KEY_SYM1
        tracker.down(0, sym1, deferPress = false)
        scheduler.advance(700)
        tracker.up(0)
        assertEquals(listOf("down:$sym1:0@0", "up:$sym1:0@700"), events)
    }

    @Test
    fun deleteRepeatsWhileHeld() {
        val del = MokyaKeys.KEY_DEL
        tracker.down(0, del, deferPress = false)
        scheduler.advance(PressTracker.REPEAT_DELAY_MS + 2 * PressTracker.REPEAT_INTERVAL_MS)
        tracker.up(0)
        scheduler.advance(1000)
        assertEquals(
            listOf("down:$del:0@0", "down:$del:0@400", "down:$del:0@450", "down:$del:0@500", "up:$del:0@500"),
            events,
        )
    }

    @Test
    fun cancelDropsDeferredTap() {
        tracker.down(0, q, deferPress = true)
        tracker.cancel(0)
        scheduler.advance(1000)
        assertTrue(events.isEmpty())
    }

    @Test
    fun cancelAllReleasesPressedKeys() {
        tracker.down(1, MokyaKeys.KEY_OK, deferPress = false)
        tracker.down(2, q, deferPress = true)
        scheduler.advance(PressTracker.LONG_PRESS_MS)
        tracker.cancelAll()
        assertEquals(
            setOf("down:${MokyaKeys.KEY_OK}:0@0", "up:${MokyaKeys.KEY_OK}:0@500", "hold:2@500"),
            events.toSet(),
        )
        assertFalse(tracker.isActive)
    }

    @Test
    fun independentPointers() {
        tracker.down(0, MokyaKeys.KEY_A, deferPress = true)
        tracker.down(1, MokyaKeys.KEY_C, deferPress = true)
        tracker.up(1)
        tracker.up(0)
        assertEquals(
            listOf(
                "down:${MokyaKeys.KEY_C}:0@0", "up:${MokyaKeys.KEY_C}:0@0",
                "down:${MokyaKeys.KEY_A}:0@0", "up:${MokyaKeys.KEY_A}:0@0",
            ),
            events,
        )
    }
}
