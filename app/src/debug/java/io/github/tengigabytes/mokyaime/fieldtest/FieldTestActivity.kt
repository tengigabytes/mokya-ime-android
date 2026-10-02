// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime.fieldtest

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import io.github.tengigabytes.mokyaime.Diagnostics
import io.github.tengigabytes.mokyaime.R
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Field-test page of debug builds: the device test checklist (pass / fail /
 * skip or a 1–5 rating, plus a note per item), the diagnostics-log switch,
 * the test environment, and the report ([FieldReport]) to share or save.
 * Results persist in the app's private storage until reset.
 */
class FieldTestActivity : Activity() {

    private companion object {
        const val TAG = "MokyaFieldTest"
        const val PREFS_NAME = "mokya_fieldtest"
        const val PREF_RESULTS = "results"
        const val SAVE_DELAY_MS = 500L
        const val SHARED_TRACE_LINES = 300
        const val REQUEST_SAVE = 1
        const val REPORT_DIR = "fieldtest"
        const val REPORT_FILE = "report.md"
    }

    private class Section(val title: Int, val items: Int, val kind: ItemKind)

    private val sectionSpecs = listOf(
        Section(R.string.ft_section_layout, R.array.ft_items_layout, ItemKind.CHECK),
        Section(R.string.ft_section_apps, R.array.ft_items_apps, ItemKind.CHECK),
        Section(R.string.ft_section_touch, R.array.ft_items_touch, ItemKind.CHECK),
        Section(R.string.ft_section_english, R.array.ft_items_english, ItemKind.CHECK),
        Section(R.string.ft_section_hardware, R.array.ft_items_hardware, ItemKind.CHECK),
        Section(R.string.ft_section_learning, R.array.ft_items_learning, ItemKind.CHECK),
        Section(R.string.ft_section_stability, R.array.ft_items_stability, ItemKind.CHECK),
        Section(R.string.ft_section_ergonomics, R.array.ft_items_ergonomics, ItemKind.RATING),
        Section(R.string.ft_section_accessibility, R.array.ft_items_accessibility, ItemKind.CHECK),
    )

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private val saveRunnable = Runnable { save() }

    private lateinit var results: MutableMap<String, ItemResult>
    private lateinit var diagnosticsStatus: TextView
    private lateinit var environmentText: TextView
    private val sectionHeaders = ArrayList<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Diagnostics.init(this)
        results = ChecklistCodec.decode(prefs.getString(PREF_RESULTS, "") ?: "").toMutableMap()
        setContentView(buildContent())
    }

    override fun onResume() {
        super.onResume()
        refreshDiagnostics()
        environmentText.text = DeviceInfo.collect(this).joinToString("\n") { "${it.first}: ${it.second}" }
    }

    override fun onPause() {
        handler.removeCallbacks(saveRunnable)
        save()
        super.onPause()
    }

    // ── Content ──────────────────────────────────────────────────────────

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun buildContent(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        column.addView(TextView(this).apply { setText(R.string.ft_intro) })

        column.addView(heading(getString(R.string.ft_diagnostics)))
        column.addView(Switch(this).apply {
            setText(R.string.ft_diagnostics)
            isChecked = Diagnostics.ring != null
            setOnCheckedChangeListener { _, on ->
                Diagnostics.setEnabled(this@FieldTestActivity, on)
                refreshDiagnostics()
            }
        })
        diagnosticsStatus = TextView(this).also(column::addView)
        column.addView(buttonRow(
            button(R.string.ft_share) { share() },
            button(R.string.ft_save) { startSave() },
        ))
        column.addView(buttonRow(
            button(R.string.ft_clear_trace) {
                Diagnostics.ring?.clear()
                refreshDiagnostics()
            },
            button(R.string.ft_reset) { confirmReset() },
        ))

        column.addView(heading(getString(R.string.ft_environment)))
        environmentText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextIsSelectable(true)
        }
        column.addView(environmentText)

        sectionSpecs.forEachIndexed { index, spec ->
            val header = heading("").also(column::addView)
            sectionHeaders += header
            if (spec.kind == ItemKind.RATING) {
                column.addView(TextView(this).apply { setText(R.string.ft_rating_hint) })
            }
            for (item in items(spec)) column.addView(itemView(item, spec.kind, index))
            updateHeader(index)
        }
        return ScrollView(this).apply {
            fitsSystemWindows = true
            addView(column, MATCH_PARENT, WRAP_CONTENT)
        }
    }

    private fun items(spec: Section): List<ChecklistItem> =
        resources.getStringArray(spec.items).map(ChecklistItem::parse)

    private fun itemView(item: ChecklistItem, kind: ItemKind, section: Int): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(4))
        }
        box.addView(TextView(this).apply {
            text = item.label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        })

        val saved = results[item.id]
        val choices: List<Pair<String, (ItemResult) -> ItemResult>> = when (kind) {
            ItemKind.CHECK -> Verdict.entries.map { verdict ->
                getString(verdictLabel(verdict)) to { r: ItemResult -> r.copy(verdict = verdict) }
            }
            ItemKind.RATING -> (1..5).map { rating -> "$rating" to { r: ItemResult -> r.copy(rating = rating) } }
        }
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        choices.forEachIndexed { i, (label, apply) ->
            val button = RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                setPadding(0, 0, dp(12), 0)
            }
            group.addView(button)
            val checked = when (kind) {
                ItemKind.CHECK -> saved?.verdict == Verdict.entries[i]
                ItemKind.RATING -> saved?.rating == i + 1
            }
            if (checked) group.check(button.id)
            button.setOnClickListener {
                update(item.id) { apply(it) }
                updateHeader(section)
            }
        }
        box.addView(group)

        box.addView(EditText(this).apply {
            setHint(R.string.ft_note_hint)
            setText(saved?.note.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable) {
                    val note = s.toString()
                    if (note != (results[item.id]?.note ?: "")) update(item.id) { it.copy(note = note) }
                }
            })
        }, MATCH_PARENT, WRAP_CONTENT)
        return box
    }

    private fun verdictLabel(verdict: Verdict) = when (verdict) {
        Verdict.PASS -> R.string.ft_pass
        Verdict.FAIL -> R.string.ft_fail
        Verdict.SKIP -> R.string.ft_skip
    }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(24), 0, dp(4))
    }

    private fun button(label: Int, onClick: () -> Unit) = Button(this).apply {
        setText(label)
        setOnClickListener { onClick() }
    }

    private fun buttonRow(vararg buttons: Button) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        buttons.forEach { addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
    }

    private fun updateHeader(index: Int) {
        val spec = sectionSpecs[index]
        val tally = FieldReport.tally(reportSection(spec))
        sectionHeaders[index].text = getString(R.string.ft_section_progress, getString(spec.title), tally.answered, tally.total)
    }

    private fun refreshDiagnostics() {
        val ring = Diagnostics.ring
        diagnosticsStatus.text = if (ring == null) {
            getString(R.string.ft_diagnostics_off)
        } else {
            getString(R.string.ft_diagnostics_status, ring.lines().size, ring.total) + "\n" +
                ring.counts().entries.joinToString("\n") { "${it.key}: ${it.value}" }
        }
    }

    // ── Results ──────────────────────────────────────────────────────────

    private fun update(id: String, change: (ItemResult) -> ItemResult) {
        results[id] = change(results[id] ?: ItemResult()).copy(updatedAtMs = System.currentTimeMillis())
        handler.removeCallbacks(saveRunnable)
        handler.postDelayed(saveRunnable, SAVE_DELAY_MS)
    }

    private fun save() {
        prefs.edit().putString(PREF_RESULTS, ChecklistCodec.encode(results)).apply()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setMessage(R.string.ft_reset_confirm)
            .setPositiveButton(R.string.ft_reset) { _, _ ->
                handler.removeCallbacks(saveRunnable)
                results.clear()
                save()
                recreate()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ── Report ───────────────────────────────────────────────────────────

    private fun reportSection(spec: Section) =
        FieldReport.Section(getString(spec.title), spec.kind, items(spec).map { it to results[it.id] })

    private fun report(maxTraceLines: Int = Int.MAX_VALUE): String {
        val ring = Diagnostics.ring
        return FieldReport.markdown(
            title = getString(R.string.ft_report_title),
            labels = FieldReport.Labels(
                environment = getString(R.string.ft_environment),
                summary = getString(R.string.ft_summary),
                results = getString(R.string.ft_results),
                counters = getString(R.string.ft_counters),
                trace = getString(R.string.ft_trace),
                section = getString(R.string.ft_section),
                answered = getString(R.string.ft_answered),
                pass = getString(R.string.ft_pass),
                fail = getString(R.string.ft_fail),
                skip = getString(R.string.ft_skip),
                averageRating = getString(R.string.ft_average),
                notTested = getString(R.string.ft_not_tested),
                traceOff = getString(R.string.ft_trace_off),
            ),
            environment = DeviceInfo.collect(this),
            sections = sectionSpecs.map(::reportSection),
            counters = ring?.counts()?.toList().orEmpty(),
            trace = ring?.lines(),
            maxTraceLines = maxTraceLines,
        )
    }

    /** Keeps [report] readable with `adb shell run-as <package> cat files/fieldtest/report.md`. */
    private fun keepForAdb(report: String) {
        try {
            File(filesDir, REPORT_DIR).apply { mkdirs() }.resolve(REPORT_FILE).writeText(report)
        } catch (e: IOException) {
            Log.w(TAG, "Could not keep the report for adb", e)
        }
    }

    private fun share() {
        save()
        keepForAdb(report())
        val text = report(SHARED_TRACE_LINES)   // a shared text has a size limit
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.ft_report_title))
            .putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, getString(R.string.ft_share)))
    }

    private fun startSave() {
        save()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date())
        val create = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/markdown")
            .putExtra(Intent.EXTRA_TITLE, "mokya-fieldtest-$stamp.md")
        startActivityForResult(create, REQUEST_SAVE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri: Uri = data?.data ?: return
        if (requestCode != REQUEST_SAVE || resultCode != RESULT_OK) return
        val full = report()
        keepForAdb(full)
        val saved = try {
            contentResolver.openOutputStream(uri)?.use { it.write(full.toByteArray()) } != null
        } catch (e: IOException) {
            Log.w(TAG, "Could not save the report", e)
            false
        }
        Toast.makeText(this, if (saved) R.string.ft_saved else R.string.ft_save_failed, Toast.LENGTH_SHORT).show()
    }
}
