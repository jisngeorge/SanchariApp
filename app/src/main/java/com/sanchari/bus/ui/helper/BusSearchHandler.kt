package com.sanchari.bus.ui.helper

import android.R
import android.content.Intent
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.ImageButton
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
    private val binding: ActivityMainBinding
) {
    private var stopNames: Set<String>? = null
    private var stopNamesIgnoreCase: Map<String, String> = emptyMap()
    private var routeSearchJob: Job? = null

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
        setupStopActions(
            binding.fromAutocomplete, binding.fromInputLayout,
            binding.fromWildcardButton, binding.fromClearButton
        )
        setupStopActions(
            binding.toAutocomplete, binding.toInputLayout,
            binding.toWildcardButton, binding.toClearButton
        )

        // Bind the "Find Bus" button
        binding.searchButton.setOnClickListener {
            handleRouteSearch()
        }
    }

    private fun setupStopActions(
        field: MaterialAutoCompleteTextView,
        layout: TextInputLayout,
        wildcard: ImageButton,
        clear: ImageButton
    ) {
        wildcard.setOnClickListener {
            field.setText("*", false)
            field.setSelection(field.length())
            field.dismissDropDown()
        }
        clear.setOnClickListener {
            field.setText("", false)
            field.dismissDropDown()
        }
        clear.isEnabled = !field.text.isNullOrEmpty()
        field.addTextChangedListener {
            clear.isEnabled = !it.isNullOrEmpty()
            layout.error = null
            val wildcardError = activity.getString(com.sanchari.bus.R.string.wildcard_both_invalid)
            if (binding.fromInputLayout.error == wildcardError) binding.fromInputLayout.error = null
            if (binding.toInputLayout.error == wildcardError) binding.toInputLayout.error = null
        }
        field.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) validateStop(field, layout, required = false)
        }
    }

    /**
     * Loads stop suggestions into the autocomplete fields.
     */
    suspend fun loadStopSuggestions(): Boolean {
        val suggestions = withContext(Dispatchers.IO) {
            SearchManager.getStopSuggestions(activity)
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
        val adapter = ArrayAdapter(activity, R.layout.simple_dropdown_item_1line, suggestions)
        binding.fromAutocomplete.setAdapter(adapter)
        binding.toAutocomplete.setAdapter(adapter)
        if (!binding.fromAutocomplete.hasFocus()) {
            validateStop(binding.fromAutocomplete, binding.fromInputLayout, required = false)
        }
        if (!binding.toAutocomplete.hasFocus()) {
            validateStop(binding.toAutocomplete, binding.toInputLayout, required = false)
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

    private fun setupBusNameSearch() {
        val adapter = ArrayAdapter<BusSearchItem>(activity, R.layout.simple_dropdown_item_1line)
        binding.busNameAutocomplete.setAdapter(adapter)

        var searchJob: Job? = null

        binding.busNameAutocomplete.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s.toString().trim()
                // Cancel any previous in-flight search to avoid stale results
                // and a flood of DB queries while the user is typing.
                searchJob?.cancel()
                if (query.isEmpty()) return

                searchJob = activity.lifecycleScope.launch(Dispatchers.IO) {
                    // Light debounce — collapse rapid keystrokes
                    delay(150)
                    val results = SearchManager.searchBusServicesByName(activity, query)
                    val searchItems = results.map { BusSearchItem(it) }

                    withContext(Dispatchers.Main) {
                        adapter.clear()
                        adapter.addAll(searchItems)
                        adapter.notifyDataSetChanged()
                    }
                }
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        binding.busNameAutocomplete.setOnItemClickListener { parent, _, position, _ ->
            val item = parent.getItemAtPosition(position) as BusSearchItem
            val service = item.service

            // Save to recent views
            activity.lifecycleScope.launch(Dispatchers.IO) {
                UserDataManager.addRecentView(activity, service.serviceId, service.name)
            }

            // Launch Details Activity
            val intent = BusDetailsActivity.newIntent(activity, service)
            activity.startActivity(intent)

            // Clear the search bar
            binding.busNameAutocomplete.setText("")
        }
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
                    }
                    activity.startActivity(intent)
                }
            } finally {
                binding.searchButton.isEnabled = true
            }
        }
    }
}