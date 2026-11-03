package com.sanchari.bus.ui.helper

import android.R
import android.content.Intent
import android.database.sqlite.SQLiteException
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Filterable
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import com.sanchari.bus.data.manager.SearchManager
import com.sanchari.bus.data.manager.UserDataManager
import com.sanchari.bus.data.model.BusService
import com.sanchari.bus.databinding.ActivityMainBinding
import com.sanchari.bus.ui.activity.BusDetailsActivity
import com.sanchari.bus.ui.activity.SearchResultsActivity
import com.sanchari.bus.ui.adapter.BusSearchSuggestionAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.ArrayList
import java.util.Locale

/**
 * Handles Search UI logic for MainActivity:
 * - Bus Name Autocomplete
 * - Route Search (From/To)
 * - Stop Suggestions
 */
class BusSearchHandler(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val busNameSearch: suspend (String) -> List<BusService> = { query ->
        SearchManager.searchBusServicesByName(activity, query)
    },
    private val addSearchMetadata: (Intent, String, String) -> Unit = { _, _, _ -> }
) {
    private var stopNames: Set<String>? = null
    private var stopNamesIgnoreCase: Map<String, String> = emptyMap()
    private var routeSearchJob: Job? = null
    private var busNameSearchJob: Job? = null
    private var busNameSearchGeneration = 0L
    private var suggestionsActive = true
    private var loadedStopSuggestions: List<String>? = null
    private lateinit var busNameAdapter: BusSearchSuggestionAdapter
    var selectedStopIsFrom = true
        private set

    fun restoreSelectedStop(from: Boolean) {
        selectedStopIsFrom = from
        updateStopActionDescriptions()
    }

    private fun updateStopActionDescriptions() {
        binding.stopLocationButton.contentDescription = activity.getString(
            if (selectedStopIsFrom) com.sanchari.bus.R.string.location_from_action
            else com.sanchari.bus.R.string.location_to_action
        )
        binding.stopWildcardButton.contentDescription = activity.getString(
            if (selectedStopIsFrom) com.sanchari.bus.R.string.wildcard_any_origin
            else com.sanchari.bus.R.string.wildcard_any_destination
        )
    }

    companion object {
        private const val TAG = "BusSearchHandler"
    }

    // Wrapper class for dropdown display
    data class BusSearchItem(val service: BusService) {
        override fun toString(): String {
            return "${service.name} (${service.type})"
        }
    }

    fun setup() {
        setupBusNameSearch()
        setupStopActions(binding.fromAutocomplete, binding.fromInputLayout)
        setupStopActions(binding.toAutocomplete, binding.toInputLayout)
        updateStopActionDescriptions()
        binding.stopWildcardButton.setOnClickListener {
            val field = if (selectedStopIsFrom) binding.fromAutocomplete else binding.toAutocomplete
            field.setText("*", false)
            field.setSelection(field.length())
            field.dismissDropDown()
        }

        // Bind the "Find Bus" button
        binding.searchButton.setOnClickListener {
            handleRouteSearch()
        }
    }

    private fun setupStopActions(
        field: MaterialAutoCompleteTextView,
        layout: TextInputLayout
    ) {
        layout.setEndIconOnClickListener {
            field.setText("", false)
            field.dismissDropDown()
        }
        field.addTextChangedListener {
            layout.error = null
            val wildcardError = activity.getString(com.sanchari.bus.R.string.wildcard_both_invalid)
            if (binding.fromInputLayout.error == wildcardError) binding.fromInputLayout.error = null
            if (binding.toInputLayout.error == wildcardError) binding.toInputLayout.error = null
        }
        field.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                restoreSelectedStop(field === binding.fromAutocomplete)
                refreshStopDropdown(field)
            } else {
                field.dismissDropDown()
                validateStop(field, layout, required = false)
            }
        }
        field.setOnClickListener { refreshStopDropdown(field) }
    }

    /**
     * Loads stop suggestions into the autocomplete fields.
     */
    suspend fun loadStopSuggestions(): Boolean {
        val suggestions = try {
            withContext(Dispatchers.IO) {
                SearchManager.getStopSuggestions(activity)
            }
        } catch (e: SQLiteException) {
            Log.e(TAG, "Failed to load stop suggestions", e)
            emptyList()
        }
        withContext(Dispatchers.Main) {
            setStopSuggestions(suggestions)
            if (suggestions.isEmpty()) {
                Log.e(TAG, "No stop suggestions available for route validation")
                Toast.makeText(activity, com.sanchari.bus.R.string.stop_names_unavailable, Toast.LENGTH_LONG).show()
            }
        }
        return suggestions.isNotEmpty()
    }

    internal fun setStopSuggestions(suggestions: List<String>) {
        stopNames = suggestions.toSet()
        stopNamesIgnoreCase = suggestions.associateBy { it.lowercase(Locale.ROOT) }
        if (loadedStopSuggestions != suggestions) {
            loadedStopSuggestions = suggestions.toList()
            binding.fromAutocomplete.setAdapter(
                ArrayAdapter(activity, R.layout.simple_dropdown_item_1line, suggestions)
            )
            binding.toAutocomplete.setAdapter(
                ArrayAdapter(activity, R.layout.simple_dropdown_item_1line, suggestions)
            )
        }
        refreshStopDropdown(binding.fromAutocomplete)
        refreshStopDropdown(binding.toAutocomplete)
        if (binding.busNameAutocomplete.hasFocus()) requestBusNameSuggestions()
        if (!binding.fromAutocomplete.hasFocus()) {
            validateStop(binding.fromAutocomplete, binding.fromInputLayout, required = false)
        }
        if (!binding.toAutocomplete.hasFocus()) {
            validateStop(binding.toAutocomplete, binding.toInputLayout, required = false)
        }
    }

    private fun refreshStopDropdown(field: MaterialAutoCompleteTextView) {
        val query = field.text.toString()
        if (!suggestionsActive || !field.hasFocus() || query.trim().isEmpty() ||
            query.trim() == "*" || !field.enoughToFilter()) {
            field.dismissDropDown()
            return
        }
        val filter = (field.adapter as? Filterable)?.filter ?: return
        filter.filter(query) { count ->
            if (suggestionsActive && field.hasFocus() && field.hasWindowFocus() &&
                field.text.toString() == query) {
                if (count > 0) field.showDropDown() else field.dismissDropDown()
            }
        }
    }

    private fun validateStop(
        field: MaterialAutoCompleteTextView,
        layout: TextInputLayout,
        required: Boolean
    ): String? {
        val value = field.text.toString().trim()
        val canonical = when {
            value.isEmpty() -> null
            value == "*" -> value
            stopNames?.contains(value) == true -> value
            else -> stopNamesIgnoreCase[value.lowercase(Locale.ROOT)]
        }
        layout.error = when {
            value.isEmpty() -> if (required) activity.getString(com.sanchari.bus.R.string.stop_name_required) else null
            stopNames.isNullOrEmpty() -> null
            canonical == null -> activity.getString(com.sanchari.bus.R.string.stop_name_not_found)
            else -> null
        }
        return canonical
    }

    internal fun validateRouteStops(): Pair<String, String>? {
        val from = validateStop(binding.fromAutocomplete, binding.fromInputLayout, required = true)
        val to = validateStop(binding.toAutocomplete, binding.toInputLayout, required = true)
        if (from == "*" && to == "*") {
            val error = activity.getString(com.sanchari.bus.R.string.wildcard_both_invalid)
            binding.fromInputLayout.error = error
            binding.toInputLayout.error = error
            return null
        }
        return if (from != null && to != null) Pair(from, to) else null
    }

    fun validateMapStop(from: Boolean): String? {
        val field = if (from) binding.fromAutocomplete else binding.toAutocomplete
        val layout = if (from) binding.fromInputLayout else binding.toInputLayout
        if (stopNames.isNullOrEmpty()) {
            Toast.makeText(activity, com.sanchari.bus.R.string.stop_names_unavailable, Toast.LENGTH_LONG).show()
            return null
        }
        val name = validateStop(field, layout, required = true)
        return name?.takeUnless { it == "*" }
    }

    private fun setupBusNameSearch() {
        busNameAdapter = BusSearchSuggestionAdapter(activity)
        binding.busNameAutocomplete.setAdapter(busNameAdapter)

        binding.busNameAutocomplete.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!binding.busNameAutocomplete.isPerformingCompletion) requestBusNameSuggestions()
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        binding.busNameAutocomplete.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) requestBusNameSuggestions() else clearBusNameSuggestions()
        }
        binding.busNameAutocomplete.setOnClickListener {
            if (busNameAdapter.count > 0 && binding.busNameAutocomplete.enoughToFilter()) {
                binding.busNameAutocomplete.showDropDown()
            } else requestBusNameSuggestions()
        }
        binding.busNameAutocomplete.setOnItemClickListener { parent, _, position, _ ->
            val item = parent.getItemAtPosition(position) as BusSearchItem
            val service = item.service
            clearBusNameSuggestions()

            // Save to recent views
            activity.lifecycleScope.launch(Dispatchers.IO) {
                UserDataManager.addRecentView(activity, service.serviceId, service.name)
            }

            // Launch Details Activity
            val intent = BusDetailsActivity.newIntent(activity, service)
            activity.startActivity(intent)

            // Clear the search bar
            binding.busNameAutocomplete.setText("", false)
        }
    }

    private fun clearBusNameSuggestions() {
        busNameSearchGeneration++
        busNameSearchJob?.cancel()
        busNameSearchJob = null
        busNameAdapter.replaceResults(emptyList())
        binding.busNameAutocomplete.dismissDropDown()
    }

    private fun requestBusNameSuggestions() {
        clearBusNameSuggestions()
        binding.busNameInputLayout.error = null
        val field = binding.busNameAutocomplete
        val query = field.text.toString().trim()
        if (!suggestionsActive || !field.hasFocus() || query.isEmpty() || !field.enoughToFilter()) return
        val generation = busNameSearchGeneration
        busNameSearchJob = activity.lifecycleScope.launch {
            try {
                delay(150)
                val results = withContext(Dispatchers.IO) { busNameSearch(query) }
                if (generation != busNameSearchGeneration || query != field.text.toString().trim() ||
                    !suggestionsActive || !field.hasFocus()) return@launch
                busNameAdapter.replaceResults(results.map { BusSearchItem(it) })
                if (results.isNotEmpty() && field.hasWindowFocus()) field.showDropDown()
                else field.dismissDropDown()
            } catch (e: SQLiteException) {
                Log.e(TAG, "Failed to load bus-name suggestions", e)
                if (generation == busNameSearchGeneration && suggestionsActive && field.hasFocus()) {
                    binding.busNameInputLayout.error =
                        activity.getString(com.sanchari.bus.R.string.bus_name_search_unavailable)
                }
            }
        }
    }

    fun pauseSuggestions() {
        suggestionsActive = false
        clearBusNameSuggestions()
        binding.fromAutocomplete.dismissDropDown()
        binding.toAutocomplete.dismissDropDown()
    }

    fun resumeSuggestions() {
        suggestionsActive = true
        refreshStopDropdown(binding.fromAutocomplete)
        refreshStopDropdown(binding.toAutocomplete)
        if (binding.busNameAutocomplete.hasFocus()) requestBusNameSuggestions()
    }

    private fun handleRouteSearch() {
        val from = binding.fromAutocomplete.text.toString().trim()
        val to = binding.toAutocomplete.text.toString().trim()

        if (from.isEmpty() || to.isEmpty()) {
            validateRouteStops()
            return
        }
        if (from == "*" && to == "*") {
            validateRouteStops()
            return
        }

        if (routeSearchJob?.isActive == true) return
        routeSearchJob = activity.lifecycleScope.launch {
            binding.searchButton.isEnabled = false
            try {
                if (!loadStopSuggestions()) return@launch
                val (canonicalFrom, canonicalTo) = validateRouteStops() ?: return@launch
                Log.i(TAG, "Searching for routes from '$canonicalFrom' to '$canonicalTo'")
                val results = withContext(Dispatchers.IO) {
                    SearchManager.findRouteResults(activity, canonicalFrom, canonicalTo)
                }
                if (results.isEmpty()) {
                    Toast.makeText(activity, "No routes found.", Toast.LENGTH_LONG).show()
                } else {
                    Log.i(TAG, "Found ${results.size} routes. Launching SearchResultsActivity.")
                    val intent = Intent(activity, SearchResultsActivity::class.java).apply {
                        putParcelableArrayListExtra(SearchResultsActivity.EXTRA_ROUTE_RESULTS, ArrayList(results))
                        putExtra(SearchResultsActivity.EXTRA_FROM_STOP, canonicalFrom)
                        putExtra(SearchResultsActivity.EXTRA_TO_STOP, canonicalTo)
                        addSearchMetadata(this, canonicalFrom, canonicalTo)
                    }
                    activity.startActivity(intent)
                }
            } finally {
                binding.searchButton.isEnabled = true
            }
        }
    }
}