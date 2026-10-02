// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime.fieldtest

import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.ViewConfiguration
import android.view.inputmethod.InputMethodManager
import io.github.tengigabytes.mokyaime.MokyaImeService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The test environment, as key / value pairs for the report: device, OS,
 * display and the system settings that change the keyboard's layout or
 * behaviour. Keys are fixed identifiers, like the diagnostic counters.
 */
internal object DeviceInfo {

    fun collect(context: Context): List<Pair<String, String>> {
        val res = context.resources
        val config = res.configuration
        val metrics = res.displayMetrics
        val resolver = context.contentResolver
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        val versionCode = if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode else packageInfo.versionCode.toLong()
        val imeId = ComponentName(context, MokyaImeService::class.java).flattenToShortString()
        val imm = context.getSystemService(InputMethodManager::class.java)
        val night = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        // Display size: the current density against the device's default.
        val displayScale = metrics.densityDpi.toFloat() / DisplayMetrics.DENSITY_DEVICE_STABLE
        val hardKeyboard = config.keyboard != Configuration.KEYBOARD_NOKEYS &&
            config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO

        return listOf(
            "time" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(Date()),
            "app" to "${packageInfo.versionName} ($versionCode)",
            "device" to "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})",
            "android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), patch ${Build.VERSION.SECURITY_PATCH}",
            "build" to Build.DISPLAY,
            "locale" to config.locales[0].toLanguageTag(),
            "screen_px" to "${metrics.widthPixels}x${metrics.heightPixels} @ ${metrics.densityDpi} dpi",
            "smallest_width_dp" to "${config.smallestScreenWidthDp}",
            "display_size_scale" to String.format(Locale.ROOT, "%.2f", displayScale),
            "font_scale" to "${config.fontScale}",
            "orientation" to if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait",
            "dark_mode" to "$night",
            "navigation_mode" to navigationMode(context),
            "long_press_timeout_ms" to "${ViewConfiguration.getLongPressTimeout()}",
            "haptic_feedback" to settingText(context, "haptic_feedback_enabled", system = true),
            "hardware_keyboard" to "$hardKeyboard",
            "show_ime_with_hard_keyboard" to settingText(context, "show_ime_with_hard_keyboard", system = false),
            "mokya_enabled" to "${imm.enabledInputMethodList.any { it.id == imeId }}",
            "default_ime" to (Settings.Secure.getString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: "?"),
        )
    }

    /**
     * Settings.Secure "navigation_mode" (not public API): 0 three-button,
     * 1 two-button, 2 gesture.
     */
    private fun navigationMode(context: Context): String =
        when (val mode = intSetting(context, "navigation_mode", system = false)) {
            0 -> "three-button"
            1 -> "two-button"
            2 -> "gesture"
            null -> "unknown"
            else -> "other ($mode)"
        }

    private fun settingText(context: Context, name: String, system: Boolean): String =
        intSetting(context, name, system)?.toString() ?: "unknown"

    private fun intSetting(context: Context, name: String, system: Boolean): Int? = try {
        if (system) {
            Settings.System.getInt(context.contentResolver, name)
        } else {
            Settings.Secure.getInt(context.contentResolver, name)
        }
    } catch (e: Settings.SettingNotFoundException) {
        null
    } catch (e: SecurityException) {
        null
    }
}
