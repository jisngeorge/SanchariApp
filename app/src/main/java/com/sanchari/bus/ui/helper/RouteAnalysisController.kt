package com.sanchari.bus.ui.helper

import android.database.sqlite.SQLiteException
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import com.sanchari.bus.R
import com.sanchari.bus.data.local.LocalVersionManager
import com.sanchari.bus.data.manager.RouteResultsManager
import com.sanchari.bus.data.manager.SearchManager
import com.sanchari.bus.data.manager.MissingStopAnalysis
import com.sanchari.bus.data.model.EditableStop
import com.sanchari.bus.databinding.ActivitySuggestEditBinding
import com.sanchari.bus.databinding.DialogStopSuggestionsBinding
import com.sanchari.bus.databinding.ItemStopSuggestionBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

class RouteAnalysisController(
    private val activity: AppCompatActivity,
    private val binding: ActivitySuggestEditBinding,
    private val prepare: () -> Boolean,
    private val stops: () -> List<EditableStop>,
    private val serviceId: () -> String?,
    private val replace: (List<EditableStop>) -> Unit
) {
    private var approved: String? = null
    private var request: String? = null
    private var job: Job? = null
    private var dialog: AlertDialog? = null
    private var loading = false
    private var onComplete: (() -> Unit)? = null

    init {
        refresh()
    }

    private fun signature(): String = JSONArray().apply {
        put(serviceId()?.takeUnless { it.startsWith("NEW-") }.orEmpty())
        put(RouteResultsManager.busTypeKey(binding.editTextServiceType.text.toString()))
        put(LocalVersionManager.getTimetableDbVersion(activity))
        put(JSONArray().apply {
            stops().forEach { put(JSONArray().put(it.originalStopId).put(it.stopName.trim())) }
        })
    }.toString()

    fun onInputsChanged() {
        val current = signature()
        if (approved != current) approved = null
        if (request != null && request != current) {
            cancelReview()
        }
        refresh()
    }

    fun reset() {
        approved = null
        cancelReview()
        refresh()
    }

    fun setLoading(value: Boolean) {
        loading = value
        refresh()
    }

    fun canProceed(showError: Boolean = false): Boolean {
        onInputsChanged()
        val ready = !loading && approved == signature()
        if (!ready && showError) Toast.makeText(activity, R.string.analysis_route_changed, Toast.LENGTH_LONG).show()
        return ready
    }

    private fun refresh() {
        binding.buttonApplyChanges.isEnabled = !loading && request == null
        binding.textRouteAnalysisStatus.visibility = if (request != null) View.VISIBLE else View.GONE
        if (request != null) binding.textRouteAnalysisStatus.setText(R.string.analysis_reviewing)
    }

    private fun cancelReview() {
        job?.cancel()
        job = null
        dialog?.dismiss()
        dialog = null
        request = null
        onComplete = null
    }

    fun analyse(onComplete: () -> Unit) {
        if (loading || request != null || !prepare()) return
        val snapshot = stops().map { it.copy() }
        if (snapshot.isEmpty() || snapshot.any { it.stopName.isBlank() }) {
            Toast.makeText(activity, R.string.analysis_need_stops, Toast.LENGTH_LONG).show()
            return
        }
        approved = null
        val key = signature()
        request = key
        this.onComplete = onComplete
        refresh()
        val type = binding.editTextServiceType.text.toString()
        val excluded = serviceId()?.takeUnless { it.startsWith("NEW-") }
        job = activity.lifecycleScope.launch {
            try {
                val gaps = withContext(Dispatchers.IO) {
                    MissingStopAnalysis.analyse(snapshot,
                        SearchManager.getAnalysisRoutes(activity.applicationContext, snapshot.map { it.originalStopId }),
                        type, excluded)
                }
                if (!isCurrent(key)) return@launch
                if (gaps.isEmpty()) {
                    complete(key, emptyMap())
                } else reviewStops(key, snapshot, gaps)
            } catch (e: SQLiteException) {
                Log.e("RouteAnalysis", "Failed to analyse timetable gaps", e)
                request = null
                this@RouteAnalysisController.onComplete = null
                refresh()
                binding.textRouteAnalysisStatus.setText(R.string.analysis_failed)
                binding.textRouteAnalysisStatus.visibility = View.VISIBLE
                Toast.makeText(activity, R.string.analysis_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun isCurrent(key: String): Boolean {
        if (request != key) return false
        if (signature() == key) return true
        reset()
        Toast.makeText(activity, R.string.analysis_route_changed, Toast.LENGTH_LONG).show()
        return false
    }

    private fun reviewStops(key: String, snapshot: List<EditableStop>, gaps: List<MissingStopAnalysis.Gap>) {
        if (!isCurrent(key)) return
        val content = DialogStopSuggestionsBinding.inflate(activity.layoutInflater)
        val selections = linkedMapOf<Int, List<MissingStopAnalysis.Stop>>()
        val review = AlertDialog.Builder(activity)
            .setTitle(R.string.analysis_title)
            .setView(content.root)
            .setOnCancelListener { reset() }
            .create()
        content.continueSuggestions.setOnClickListener {
            complete(key, selections)
            review.dismiss()
        }
        content.editSuggestionTimes.setOnClickListener {
            complete(key, selections, continueToConfirmation = false)
            review.dismiss()
        }
        content.cancelSuggestions.setOnClickListener { reset() }
        fun refreshEditButton() {
            content.editSuggestionTimes.isEnabled = selections.values.any { it.isNotEmpty() }
        }
        gaps.forEachIndexed { index, gap ->
            val from = snapshot[gap.position]
            val to = snapshot[gap.position + 1]
            content.suggestionGaps.addView(gapTitle(activity.getString(R.string.analysis_gap,
                index + 1, from.stopName, gap.position + 1,
                TimetableTimeFormatter.format(from.scheduledTime.ifBlank { "--:--" }),
                to.stopName, gap.position + 2,
                TimetableTimeFormatter.format(to.scheduledTime.ifBlank { "--:--" }))))
            val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            fun displayAlternative(choice: Int) {
                rows.removeAllViews()
                selections[gap.position] = emptyList()
                refreshEditButton()
                val candidates = gap.alternatives[choice].stops
                val accepted = BooleanArray(candidates.size)
                candidates.forEachIndexed { stopIndex, stop ->
                    val row = ItemStopSuggestionBinding.inflate(activity.layoutInflater, rows, false)
                    row.suggestionStopName.text = stop.name
                    listOf(row.acceptSuggestion, row.rejectSuggestion).forEach { button ->
                        button.contentDescription = activity.getString(R.string.analysis_choice_description,
                            index + 1, stopIndex + 1, stop.name, button.text)
                    }
                    row.suggestionDecision.setOnCheckedChangeListener { _, checked ->
                        accepted[stopIndex] = checked == R.id.acceptSuggestion
                        selections[gap.position] = candidates.filterIndexed { i, _ -> accepted[i] }
                        refreshEditButton()
                    }
                    rows.addView(row.root)
                }
            }
            if (gap.alternatives.size > 1) {
                val options = Spinner(activity, Spinner.MODE_DROPDOWN).apply {
                    contentDescription = activity.getString(R.string.analysis_route_choice, index + 1)
                    minimumHeight = (48 * resources.displayMetrics.density).toInt()
                    adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item,
                        gap.alternatives.indices.map { activity.getString(R.string.analysis_route_option, it + 1) })
                        .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                    onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                            displayAlternative(position)
                        }
                        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                    }
                }
                content.suggestionGaps.addView(options)
            }
            content.suggestionGaps.addView(rows)
            displayAlternative(0)
        }
        dialog = review
        review.show()
        refreshEditButton()
    }

    private fun gapTitle(text: String): TextView = TextView(activity).apply {
        this.text = text
        setTextAppearance(androidx.appcompat.R.style.TextAppearance_AppCompat_Body1)
        val padding = (24 * resources.displayMetrics.density).toInt()
        setPadding(0, padding, 0, padding / 2)
        ViewCompat.setAccessibilityHeading(this, true)
    }

    private fun complete(
        key: String, selections: Map<Int, List<MissingStopAnalysis.Stop>>,
        continueToConfirmation: Boolean = true
    ) {
        if (!isCurrent(key)) return
        val continuation = onComplete
        onComplete = null
        // Use current rows so time-only edits during analysis are retained.
        val updated = MissingStopAnalysis.insert(stops(), selections)
        request = null
        if (selections.values.any { it.isNotEmpty() }) replace(updated)
        approved = signature()
        refresh()
        if (continueToConfirmation) continuation?.invoke()
    }

    fun close() = cancelReview()
}
