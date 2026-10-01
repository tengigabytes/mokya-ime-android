// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.widget.ScrollView
import android.widget.TextView

/**
 * Shows NOTICE and the full licence texts packaged under assets/licenses/
 * by the collectLicenses Gradle task.
 */
class LicensesActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = (16 * resources.displayMetrics.density).toInt()
        val text = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextIsSelectable(true)
            setPadding(padding, padding, padding, padding)
            this.text = licenceText()
        }
        setContentView(ScrollView(this).apply { addView(text) })
    }

    private fun licenceText(): String = LICENCE_FILES.joinToString("\n\n") { name ->
        val body = assets.open("$LICENCE_DIR/$name").bufferedReader().use { it.readText() }
        "══ $name ══\n\n$body"
    }

    internal companion object {
        const val LICENCE_DIR = "licenses"

        /** Packaged by app/build.gradle.kts (collectLicenses), NOTICE first. */
        val LICENCE_FILES = listOf(
            "NOTICE",
            "Apache-2.0.txt",
            "libmie-MIT.txt",
            "LGPL-2.1.txt",
            "CC-BY-SA-4.0.txt",
        )
    }
}
