// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime

import android.content.Context
import io.github.tengigabytes.mokyaime.fieldtest.TraceRing

/**
 * Diagnostics for testing on a real device. Off by default; switched on
 * from the field-test page of debug builds. While on, [MokyaImeService.trace]
 * keeps its lines in [ring] (and logcat, tag MokyaTrace) as it does for the
 * instrumentation tests. What is typed into fields that must not be learned
 * from (passwords, incognito) is left out ([MokyaImeService.traceInput]).
 *
 * The IME and the page run in the same process; the switch is persisted so
 * the IME picks it up when the process restarts. Main thread only.
 */
internal object Diagnostics {
    private const val PREFS_NAME = "mokya_diagnostics"
    private const val PREF_ENABLED = "enabled"
    private const val CAPACITY = 3000

    /** Counted over the whole session, also after their lines left the ring. */
    val MARKERS = linkedMapOf(
        "input_started" to "startInput restarting=false",
        "strip_area_revealed" to "candidates area was",
        "composition_dropped" to "discardComposition",
        "bottom_inset_changed" to "keyboard bottom inset",
        "touch_slid_off" to "touch slid off",
    )

    /** The trace while diagnostics are on, else null. */
    @Volatile
    var ring: TraceRing? = null
        private set

    /** Turns the ring on if the persisted switch says so; safe to call repeatedly. */
    fun init(context: Context) {
        if (ring == null && isEnabled(context)) ring = TraceRing(CAPACITY, MARKERS)
    }

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(PREF_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(PREF_ENABLED, enabled).apply()
        ring = if (enabled) ring ?: TraceRing(CAPACITY, MARKERS) else null
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
