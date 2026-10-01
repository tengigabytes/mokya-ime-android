// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView

/**
 * Launcher and IME settings screen: enable / select the keyboard, a field to
 * try it in, usage notes and the open-source licences.
 */
class SetupActivity : Activity() {

    private val imm by lazy { getSystemService(InputMethodManager::class.java) }
    private val imeId by lazy { ComponentName(this, MokyaImeService::class.java).flattenToShortString() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        findViewById<Button>(R.id.enable).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<Button>(R.id.select).setOnClickListener { imm.showInputMethodPicker() }
        findViewById<Button>(R.id.licenses).setOnClickListener {
            startActivity(Intent(this, LicensesActivity::class.java))
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The IME picker and the settings screen return focus here.
        if (hasFocus) updateStatus()
    }

    private fun updateStatus() {
        val enabled = imm.enabledInputMethodList.any { it.id == imeId }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) == imeId
        findViewById<TextView>(R.id.status).setText(
            when {
                current -> R.string.setup_status_ready
                enabled -> R.string.setup_status_enabled
                else -> R.string.setup_status_disabled
            },
        )
        findViewById<Button>(R.id.select).isEnabled = enabled
    }
}
