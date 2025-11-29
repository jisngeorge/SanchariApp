package com.sanchari.bus.ui.helper

import android.R
import android.content.Intent
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sanchari.bus.data.manager.SearchManager
import com.sanchari.bus.data.manager.UserDataManager
import com.sanchari.bus.data.model.BusService
import com.sanchari.bus.databinding.ActivityMainBinding
import com.sanchari.bus.ui.activity.BusDetailsActivity
import com.sanchari.bus.ui.activity.SearchResultsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.ArrayList

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

        // Bind the "Find Bus" button
        binding.searchButton.setOnClickListener {
            handleRouteSearch()
        }
    }

    /**
     * Loads stop suggestions into the autocomplete fields.
     */
    suspend fun loadStopSuggestions() {
        val suggestions = SearchManager.getStopSuggestions(activity)
        withContext(Dispatchers.Main) {
            val adapter = ArrayAdapter(
                activity,
                R.layout.simple_dropdown_item_1line,
                suggestions
            )
            binding.fromAutocomplete.setAdapter(adapter)
            binding.toAutocomplete.setAdapter(adapter)
        }
    }

    private fun setupBusNameSearch() {
        val adapter = ArrayAdapter<BusSearchItem>(activity, R.layout.simple_dropdown_item_1line)
        binding.busNameAutocomplete.setAdapter(adapter)

        binding.busNameAutocomplete.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s.toString().trim()
                if (query.length >= 1) {
                    activity.lifecycleScope.launch(Dispatchers.IO) {
                        val results = SearchManager.searchBusServicesByName(activity, query)
                        val searchItems = results.map { BusSearchItem(it) }

                        withContext(Dispatchers.Main) {
                            adapter.clear()
                            adapter.addAll(searchItems)
                            adapter.notifyDataSetChanged()
                        }
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
            Toast.makeText(activity, "Please enter both 'From' and 'To' locations.", Toast.LENGTH_SHORT).show()
            return
        }

        Log.i(TAG, "Searching for routes from '$from' to '$to'")

        activity.lifecycleScope.launch(Dispatchers.IO) {
            val results = SearchManager.findBusServices(activity, from, to)

            withContext(Dispatchers.Main) {
                if (results.isEmpty()) {
                    Toast.makeText(activity, "No routes found.", Toast.LENGTH_LONG).show()
                } else {
                    Log.i(TAG, "Found ${results.size} routes. Launching SearchResultsActivity.")
                    val intent = Intent(activity, SearchResultsActivity::class.java).apply {
                        putParcelableArrayListExtra(SearchResultsActivity.EXTRA_SEARCH_RESULTS, ArrayList(results))
                    }
                    activity.startActivity(intent)
                }
            }
        }
    }
}