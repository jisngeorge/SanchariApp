package com.sanchari.bus.ui.activity

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.database.sqlite.SQLiteException
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.core.view.ViewCompat
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.sanchari.bus.data.manager.SearchManager
import com.sanchari.bus.data.manager.RouteResultsManager
import com.sanchari.bus.data.model.BusService
import com.sanchari.bus.data.model.EditableStop
import com.sanchari.bus.databinding.ActivitySuggestEditBinding
import com.sanchari.bus.ui.adapter.StopEditAdapter
import com.sanchari.bus.ui.helper.SuggestEditHandler
import com.sanchari.bus.ui.helper.ScheduleEditorLogic
import com.sanchari.bus.ui.helper.TimetableTimeFormatter
import com.sanchari.bus.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import java.util.ArrayList

class SuggestEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySuggestEditBinding
    private lateinit var adapter: StopEditAdapter
    private val editableStops = mutableListOf<EditableStop>()
    private var originalService: BusService? = null

    // Tracks the current draft if loaded from history
    var currentDraftFileName: String? = null

    private lateinit var handler: SuggestEditHandler
    private var editorRevision = 0L
    private var estimateJob: Job? = null
    private var estimateDialog: AlertDialog? = null
    private var reverseDialog: AlertDialog? = null
    private var initialLoading = false
    private var busTypes = emptyList<String>()

    companion object {
        private const val TAG = "SuggestEditActivity"
        private const val EXTRA_BUS_SERVICE = "EXTRA_BUS_SERVICE"
        const val EXTRA_LOAD_DRAFT_FILE = "EXTRA_LOAD_DRAFT_FILE"

        private const val KEY_SERVICE_NAME = "KEY_SERVICE_NAME"
        private const val KEY_SERVICE_TYPE = "KEY_SERVICE_TYPE"
        private const val KEY_STOPS = "KEY_STOPS"
        private const val KEY_EDIT_NOTES = "KEY_EDIT_NOTES"
        private const val KEY_ORIGINAL_SERVICE = "KEY_ORIGINAL_SERVICE"
        private const val KEY_DRAFT_FILE = "KEY_DRAFT_FILE"
        private const val KEY_INITIAL_LOADING = "KEY_INITIAL_LOADING"

        fun newIntentForEdit(context: Context, service: BusService): Intent {
            return Intent(context, SuggestEditActivity::class.java).apply {
                putExtra(EXTRA_BUS_SERVICE, service)
            }
        }

        fun newIntentForNew(context: Context): Intent {
            return Intent(context, SuggestEditActivity::class.java)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySuggestEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        listOf(binding.serviceDetailsHeading, binding.routeStopsHeading, binding.notesHeading).forEach {
            ViewCompat.setAccessibilityHeading(it, true)
        }

        setupRecyclerView()

        // Initialize Handler
        handler = SuggestEditHandler(this, binding, adapter, editableStops)

        if (savedInstanceState == null) {
            if (intent.getStringExtra(EXTRA_LOAD_DRAFT_FILE) == null) {
                handler.showInstructionsDialog()
            }
        } else {
            Log.i(TAG, "Restoring state from savedInstanceState")
            restoreFromSavedState(savedInstanceState)
        }

        if (savedInstanceState == null) {
            Log.i(TAG, "Loading from intent (new or edit)")
            loadFromIntent()
        }

        // Intercept Back Press to prompt "Save Draft"
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handler.promptSaveDraftAndExit()
            }
        })

        // Toolbar Back Button
        binding.toolbar.setNavigationOnClickListener {
            handler.promptSaveDraftAndExit()
        }

        // History Icon
        binding.btnHistory.setOnClickListener {
            handler.showEditHistory()
        }

        binding.buttonAddStop.setOnClickListener {
            addNewStop()
        }

        binding.buttonSaveDraft.setOnClickListener {
            handler.saveDraft()
        }

        binding.buttonDiscardDraft.setOnClickListener {
            handler.discardDraft()
        }

        binding.buttonApplyChanges.setOnClickListener {
            handler.applyChanges(originalService)
        }
        binding.buttonReverseStops.setOnClickListener { showReverseDialog() }
        binding.editTextServiceType.doAfterTextChanged { invalidateEstimate() }
        loadEditorOptions()
    }


    // Expose Original Service to Handler
    fun getOriginalService(): BusService? = originalService
    fun setOriginalService(service: BusService?) {
        originalService = service
        binding.toolbar.setTitle(
            if (service != null && !service.serviceId.startsWith("NEW-")) R.string.editor_edit_title
            else R.string.editor_new_title
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        adapter.commitNames()
        outState.putString(KEY_SERVICE_NAME, binding.editTextServiceName.text.toString())
        outState.putString(KEY_SERVICE_TYPE, binding.editTextServiceType.text.toString())
        outState.putParcelableArrayList(KEY_STOPS, ArrayList(adapter.getStopsData()))
        outState.putString(KEY_EDIT_NOTES, binding.editTextEditNotes.text.toString())
        outState.putParcelable(KEY_ORIGINAL_SERVICE, originalService)
        outState.putString(KEY_DRAFT_FILE, currentDraftFileName)
        outState.putBoolean(KEY_INITIAL_LOADING, initialLoading)
    }

    private fun restoreFromSavedState(savedInstanceState: Bundle) {
        val serviceName = savedInstanceState.getString(KEY_SERVICE_NAME)
        val serviceType = savedInstanceState.getString(KEY_SERVICE_TYPE)
        val editNotes = savedInstanceState.getString(KEY_EDIT_NOTES)
        val stops: ArrayList<EditableStop>? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                savedInstanceState.getParcelableArrayList(KEY_STOPS, EditableStop::class.java)
            } else {
                @Suppress("DEPRECATION")
                savedInstanceState.getParcelableArrayList(KEY_STOPS)
            }

        binding.editTextServiceName.setText(serviceName)
        binding.editTextServiceType.setText(serviceType)
        binding.editTextEditNotes.setText(editNotes)

        if (stops != null) {
            editableStops.clear()
            editableStops.addAll(stops)
            adapter.routeReplaced()
        }
        setOriginalService(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                savedInstanceState.getParcelable(KEY_ORIGINAL_SERVICE, BusService::class.java)
            } else {
                @Suppress("DEPRECATION")
                savedInstanceState.getParcelable(KEY_ORIGINAL_SERVICE)
            }
        )
        currentDraftFileName = savedInstanceState.getString(KEY_DRAFT_FILE)
        if (savedInstanceState.getBoolean(KEY_INITIAL_LOADING)) {
            originalService?.let(::preFillData)
        }
    }

    private fun loadFromIntent() {
        originalService = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_BUS_SERVICE, BusService::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_BUS_SERVICE)
        }

        // Check if we should auto-load a specific draft
        val draftFileToLoad = intent.getStringExtra(EXTRA_LOAD_DRAFT_FILE)
        if (draftFileToLoad != null) {
            binding.toolbar.title = "Add New Bus Service"
            handler.loadDraftByFileName(draftFileToLoad)
            return
        }

        if (originalService != null) {
            binding.toolbar.title = "Suggest an Edit"
            binding.editTextServiceName.setText(originalService!!.name)
            binding.editTextServiceType.setText(originalService!!.type, false)
            preFillData(originalService!!)
        } else {
            binding.toolbar.title = "Add New Bus Service"
            addNewStop()
        }
    }

    private fun setupRecyclerView() {
        adapter = StopEditAdapter(
            editableStops,
            onRemoveClicked = { position ->
                editableStops.removeAt(position)
                adapter.notifyItemRemoved(position)
                // Refresh from the previous neighbor onwards so order numbers
                // and arrow visibility (for the new last item) update correctly.
                val refreshStart = (position - 1).coerceAtLeast(0)
                if (editableStops.size > refreshStart) {
                    adapter.notifyItemRangeChanged(refreshStart, editableStops.size - refreshStart)
                }
            },
            onTimeClicked = { position ->
                handler.showTimePicker(position)
            },
            onEstimateClicked = ::suggestTime,
            onRouteChanged = ::invalidateEstimate
        )
        binding.recyclerViewStopsEditor.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewStopsEditor.adapter = adapter
    }

    private fun preFillData(service: BusService) {
        setInitialLoading(true)
        lifecycleScope.launch {
            try {
                val stops = withContext(Dispatchers.IO) {
                    SearchManager.getEditorBusStops(applicationContext, service.serviceId)
                }
                editableStops.clear()
                editableStops.addAll(stops.map(EditableStop::fromBusStop))
                adapter.routeReplaced()
                setInitialLoading(false)
            } catch (e: SQLiteException) {
                Log.e(TAG, "Failed to load editable route", e)
                AlertDialog.Builder(this@SuggestEditActivity)
                    .setMessage(R.string.editor_route_load_failed)
                    .setPositiveButton(R.string.editor_retry) { _, _ -> preFillData(service) }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
                    .setCancelable(false)
                    .show()
            }
        }
    }

    private fun addNewStop() {
        val newPos = editableStops.size
        editableStops.add(EditableStop("", "", newPos + 1, -1))
        invalidateEstimate()
        adapter.notifyItemInserted(newPos)
        // The previous-last item now needs its down-arrow shown.
        if (newPos > 0) {
            adapter.notifyItemChanged(newPos - 1)
        }
        binding.recyclerViewStopsEditor.smoothScrollToPosition(newPos)
    }

    private fun setInitialLoading(loading: Boolean) {
        initialLoading = loading
        listOf(binding.buttonAddStop, binding.buttonReverseStops, binding.buttonApplyChanges,
            binding.buttonSaveDraft, binding.btnHistory).forEach { it.isEnabled = !loading }
    }

    fun prepareEditorForAction(forDraft: Boolean = false): Boolean {
        if (initialLoading) {
            Toast.makeText(this, R.string.editor_loading_route, Toast.LENGTH_SHORT).show()
            return false
        }
        binding.root.clearFocus()
        if (!adapter.commitNames() && !forDraft) {
            Toast.makeText(this, R.string.editor_ambiguous_stop, Toast.LENGTH_LONG).show()
            return false
        }
        val type = binding.editTextServiceType.text.toString().trim()
        val canonical = busTypes.firstOrNull {
            RouteResultsManager.busTypeKey(it) == RouteResultsManager.busTypeKey(type)
        } ?: type
        if (canonical != binding.editTextServiceType.text.toString()) {
            binding.editTextServiceType.setText(canonical, false)
        }
        return true
    }

    private fun loadEditorOptions() {
        lifecycleScope.launch {
            try {
                val (stops, types) = withContext(Dispatchers.IO) {
                    SearchManager.getEditorStopOptions(applicationContext) to
                        SearchManager.getEditorBusTypes(applicationContext)
                }
                adapter.setStopOptions(stops)
                busTypes = types
                binding.editTextServiceType.setAdapter(
                    ArrayAdapter(this@SuggestEditActivity, android.R.layout.simple_dropdown_item_1line, types)
                )
            } catch (e: SQLiteException) {
                Log.e(TAG, "Failed to load editor suggestions", e)
                adapter.setStopOptions(null, failed = true)
                binding.serviceTypeLayout.helperText = getString(R.string.editor_lookup_unavailable)
                Toast.makeText(this@SuggestEditActivity, R.string.editor_lookup_unavailable, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun invalidateEstimate() {
        editorRevision++
        estimateJob?.cancel()
        estimateDialog?.dismiss()
        estimateDialog = null
    }

    private fun showReverseDialog() {
        if (!prepareEditorForAction()) return
        if (editableStops.size < 2) {
            Toast.makeText(this, R.string.editor_reverse_need_stops, Toast.LENGTH_LONG).show()
            return
        }
        val revision = editorRevision
        val labels = editableStops.mapIndexed { index, stop ->
            getString(R.string.editor_numbered_stop, index + 1, stop.stopName)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
        }
        val start = Spinner(this).apply {
            adapter = ArrayAdapter(this@SuggestEditActivity, android.R.layout.simple_spinner_dropdown_item, labels)
            contentDescription = getString(R.string.editor_reverse_start)
        }
        val end = Spinner(this).apply {
            adapter = ArrayAdapter(this@SuggestEditActivity, android.R.layout.simple_spinner_dropdown_item, labels)
            contentDescription = getString(R.string.editor_reverse_end)
            setSelection(labels.lastIndex)
        }
        val preview = TextView(this)
        listOf<View>(
            TextView(this).apply { setText(R.string.editor_reverse_start) }, start,
            TextView(this).apply { setText(R.string.editor_reverse_end) }, end, preview
        ).forEach(content::addView)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.editor_reverse_append)
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton(R.string.editor_append, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        reverseDialog = dialog
        fun updatePreview() {
            val from = start.selectedItemPosition
            val to = end.selectedItemPosition
            val valid = from < to && editableStops.subList(from, to + 1).all { it.stopName.isNotBlank() }
            preview.text = if (valid) {
                getString(
                    R.string.editor_reverse_preview, to - from + 1,
                    editableStops.subList(from, to + 1).asReversed().joinToString(" > ") { it.stopName },
                    editableStops.last().stopName
                )
            } else getString(R.string.editor_reverse_need_stops)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = valid
        }
        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = updatePreview()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        start.onItemSelectedListener = listener
        end.onItemSelectedListener = listener
        dialog.setOnShowListener {
            updatePreview()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (revision != editorRevision) {
                    Toast.makeText(this, R.string.editor_route_changed, Toast.LENGTH_LONG).show()
                } else {
                    adapter.appendCopies(ScheduleEditorLogic.reverseCopies(
                        editableStops, start.selectedItemPosition, end.selectedItemPosition
                    ))
                    binding.recyclerViewStopsEditor.smoothScrollToPosition(editableStops.lastIndex)
                }
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun suggestTime(position: Int) {
        if (!prepareEditorForAction()) return
        invalidateEstimate()
        val snapshot = editableStops.map { it.copy() }
        if (!ScheduleEditorLogic.canEstimate(snapshot, position)) {
            Toast.makeText(this, R.string.editor_estimate_requirements, Toast.LENGTH_LONG).show()
            return
        }
        val revision = editorRevision
        val type = binding.editTextServiceType.text.toString()
        estimateJob = lifecycleScope.launch {
            try {
                val estimate = ScheduleEditorLogic.editorEstimate(snapshot, position)
                    ?: withContext(Dispatchers.IO) {
                        ScheduleEditorLogic.timetableEstimate(
                            SearchManager.getEditorRouteResults(
                                applicationContext, snapshot[position - 1].stopName, snapshot[position].stopName
                            ).map { it.service }, type
                        )
                    }
                if (revision != editorRevision) return@launch
                if (estimate == null) {
                    Toast.makeText(this@SuggestEditActivity, R.string.editor_no_estimate, Toast.LENGTH_LONG).show()
                    return@launch
                }
                val previousTime = snapshot[position - 1].scheduledTime
                val time = estimate.arrival(previousTime)
                val source = when (estimate.source) {
                    ScheduleEditorLogic.EstimateSource.CURRENT_ROUTE -> getString(R.string.editor_source_route)
                    ScheduleEditorLogic.EstimateSource.BUS_TYPE -> getString(R.string.editor_source_type, type)
                    ScheduleEditorLogic.EstimateSource.ALL_TYPES -> getString(R.string.editor_source_all)
                }
                val current = snapshot[position].scheduledTime
                val message = getString(
                    R.string.editor_estimate_preview,
                    TimetableTimeFormatter.format(time),
                    if (estimate.isNextDay(previousTime)) getString(R.string.editor_next_day) else "",
                    estimate.minutes, source, estimate.sampleCount
                ) + if (current.isNotBlank()) {
                    getString(R.string.editor_replace_current, TimetableTimeFormatter.format(current))
                } else ""
                estimateDialog = AlertDialog.Builder(this@SuggestEditActivity)
                    .setTitle(R.string.editor_suggest_time)
                    .setMessage(message)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(
                        if (current.isBlank()) R.string.editor_use_time else R.string.editor_replace_time
                    ) { _, _ ->
                        if (revision == editorRevision && editableStops == snapshot) {
                            adapter.updateTime(position, time)
                        } else Toast.makeText(this@SuggestEditActivity, R.string.editor_route_changed, Toast.LENGTH_LONG).show()
                    }
                    .show()
            } catch (e: SQLiteException) {
                Log.e(TAG, "Failed to calculate timetable estimate", e)
                Toast.makeText(this@SuggestEditActivity, R.string.editor_estimate_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        estimateDialog?.dismiss()
        reverseDialog?.dismiss()
        super.onDestroy()
    }
}