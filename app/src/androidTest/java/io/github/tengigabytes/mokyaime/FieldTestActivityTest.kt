// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.Switch
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tengigabytes.mokyaime.fieldtest.ChecklistCodec
import io.github.tengigabytes.mokyaime.fieldtest.FieldTestActivity
import io.github.tengigabytes.mokyaime.fieldtest.Verdict
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The field-test page of debug builds: answers persist, the switch drives [Diagnostics]. */
@RunWith(AndroidJUnit4::class)
class FieldTestActivityTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs = context.getSharedPreferences("mokya_fieldtest", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { Diagnostics.setEnabled(context, false) }
    }

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { Diagnostics.setEnabled(context, false) }
    }

    @Test
    fun answersPersistAndTheSwitchTurnsDiagnosticsOn() {
        ActivityScenario.launch(FieldTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val root = activity.window.decorView
                // The first item is layout.strip_switch_app; its first choice is Pass.
                val pass = views(root).filterIsInstance<RadioButton>().first { it.text == context.getString(R.string.ft_pass) }
                pass.performClick()
                val switch = views(root).filterIsInstance<Switch>().single()
                switch.performClick()
                assertNotNull(Diagnostics.ring)
            }
            scenario.moveToState(Lifecycle.State.CREATED)   // onPause saves
        }
        val saved = ChecklistCodec.decode(prefs.getString("results", "")!!)
        assertEquals(Verdict.PASS, saved.getValue("layout.strip_switch_app").verdict)
        assertTrue(Diagnostics.isEnabled(context))
    }

    @Test
    fun savedAnswersAreShownAgain() {
        prefs.edit().putString("results", "layout.dark\tFAIL\t0\t1\tdark keys too dim").commit()
        ActivityScenario.launch(FieldTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val texts = views(activity.window.decorView).mapNotNull { (it as? TextView)?.text?.toString() }
                assertTrue(texts.contains("dark keys too dim"))
                val checkedFails = views(activity.window.decorView).filterIsInstance<RadioButton>()
                    .count { it.isChecked && it.text == context.getString(R.string.ft_fail) }
                assertEquals(1, checkedFails)
            }
        }
    }

    private fun views(root: View): List<View> =
        listOf(root) + ((root as? ViewGroup)?.let { group -> (0 until group.childCount).flatMap { views(group.getChildAt(it)) } }.orEmpty())
}
