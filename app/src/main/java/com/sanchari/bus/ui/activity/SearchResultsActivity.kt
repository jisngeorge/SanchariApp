package com.sanchari.bus.ui.activity

import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sanchari.bus.R
import com.sanchari.bus.data.manager.RouteResultsManager
import com.sanchari.bus.data.manager.RouteResultsManager.SortOrder
import com.sanchari.bus.data.manager.SuggestionManager
import com.sanchari.bus.data.manager.UserDataManager
import com.sanchari.bus.data.model.BusService
import com.sanchari.bus.data.model.RouteSearchStatistics
import com.sanchari.bus.databinding.ActivitySearchResultsBinding
import com.sanchari.bus.databinding.DialogNearbySuggestionsBinding
import com.sanchari.bus.ui.adapter.BusServiceAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

class SearchResultsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchResultsBinding
    private lateinit var adapter: BusServiceAdapter
    private var allResults: List<BusService> = emptyList()
    private var selectedTypes: Set<String>? = null
    private var sortOrder = SortOrder.DEPARTURE_TIME

    companion object {
        const val EXTRA_SEARCH_RESULTS = "EXTRA_SEARCH_RESULTS"
        const val EXTRA_FROM_STOP = "EXTRA_FROM_STOP"
        const val EXTRA_TO_STOP = "EXTRA_TO_STOP"
        private const val STATE_SORT_ORDER = "sort_order"
        private const val STATE_SELECTED_TYPES = "selected_types"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchResultsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val results = getParcelableArrayList<BusService>(EXTRA_SEARCH_RESULTS)
        if (results.isNullOrEmpty()) {
            Log.e("SearchResults", "No search results passed to activity.")
            Toast.makeText(this, "No results found.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        allResults = results
        selectedTypes = savedInstanceState?.getStringArrayList(STATE_SELECTED_TYPES)?.toSet()
        sortOrder = SortOrder.entries.firstOrNull {
            it.name == savedInstanceState?.getString(STATE_SORT_ORDER)
        } ?: SortOrder.DEPARTURE_TIME

        val fromStop = intent.getStringExtra(EXTRA_FROM_STOP) ?: "Search"
        val toStop = intent.getStringExtra(EXTRA_TO_STOP) ?: "Results"
        binding.tvToolbarTitle.text = getString(R.string.route_search_title, fromStop, toStop)
        binding.tvToolbarTitle.isSelected = true
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        setupMenu(fromStop, toStop)
        setupRecyclerView()
        renderResults()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    if (sortOrder == SortOrder.NEXT_DEPARTURE) renderResults()
                    delay(60_000L - System.currentTimeMillis() % 60_000L)
                }
            }
        }
    }

    private fun setupMenu(fromStop: String, toStop: String) {
        binding.toolbar.inflateMenu(R.menu.menu_search_results)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_sort_duration -> {
                    showSortOptions()
                    true
                }
                R.id.action_filter_bus_type -> {
                    showBusTypeFilter()
                    true
                }
                R.id.action_suggest_nearby -> {
                    showNearbySuggestions(fromStop, toStop)
                    true
                }
                else -> false
            }
        }
    }

    private fun changeSortOrder(order: SortOrder) {
        sortOrder = order
        renderResults(resetScroll = true)
    }

    private fun sortOrderLabel(order: SortOrder): Int = when (order) {
        SortOrder.DEPARTURE_TIME -> R.string.sort_departure_time
        SortOrder.SHORTEST_JOURNEY -> R.string.sort_shortest_journey
        SortOrder.LONGEST_JOURNEY -> R.string.sort_longest_journey
        SortOrder.NEXT_DEPARTURE -> R.string.sort_next_departure
    }

    private fun showSortOptions() {
        val orders = SortOrder.entries
        val labels = orders.map { getString(sortOrderLabel(it)) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.sort_results)
            .setSingleChoiceItems(labels, orders.indexOf(sortOrder)) { dialog, index ->
                changeSortOrder(orders[index])
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showBusTypeFilter() {
        val types = allResults.map { RouteResultsManager.busTypeKey(it.type) }.distinct().sorted()
        val labels = types.map {
            it.ifEmpty { getString(R.string.label_bus_unspecified) }
        }.toTypedArray()
        val checked = types.map { selectedTypes?.contains(it) ?: true }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.filter_bus_types)
            .setMultiChoiceItems(labels, checked) { _, index, isChecked ->
                checked[index] = isChecked
            }
            .setPositiveButton(R.string.filter_apply) { _, _ ->
                selectedTypes = if (checked.all { it }) null else {
                    types.filterIndexed { index, _ -> checked[index] }.toSet()
                }
                renderResults(resetScroll = true)
            }
            .setNeutralButton(R.string.filter_all_types) { _, _ ->
                selectedTypes = null
                renderResults(resetScroll = true)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun renderResults(resetScroll: Boolean = false) {
        val now = Calendar.getInstance()
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val results = RouteResultsManager.applyOptions(allResults, selectedTypes, sortOrder, currentMinutes)
        adapter.submitList(results) {
            if (resetScroll) binding.searchResultsRecyclerView.scrollToPosition(0)
        }
        binding.emptyResults.isVisible = results.isEmpty()
        binding.searchResultsRecyclerView.isVisible = results.isNotEmpty()
        updateResultsSummary(results)
        binding.toolbar.menu.findItem(R.id.action_filter_bus_type).apply {
            setTitle(if (selectedTypes != null) R.string.filter_bus_types_active else R.string.filter_bus_types)
        }
    }

    private fun updateResultsSummary(results: List<BusService>) {
        val statistics = RouteSearchStatistics.fromServices(results)
        val average = statistics.averageMinutes
        val averageLabel = if (average == null) {
            getString(R.string.statistics_unavailable)
        } else {
            getString(R.string.duration_compact, average / 60, average % 60)
        }
        val summary = getString(R.string.results_summary, results.size, averageLabel)
        val details = buildString {
            append(resources.getQuantityString(
                R.plurals.statistics_coverage,
                statistics.totalCount,
                statistics.timedCount,
                statistics.totalCount
            ))
            if (statistics.timedCount < statistics.totalCount) {
                append(" ").append(getString(R.string.statistics_exclusions))
            }
        }
        binding.resultsSummary.text = summary
        binding.resultsSummary.contentDescription = getString(R.string.results_summary_details, summary, details)
        TooltipCompat.setTooltipText(binding.resultsSummary, details)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_SORT_ORDER, sortOrder.name)
        selectedTypes?.let { outState.putStringArrayList(STATE_SELECTED_TYPES, ArrayList(it)) }
        super.onSaveInstanceState(outState)
    }

    private fun setupRecyclerView() {
        adapter = BusServiceAdapter { service ->
            Log.i("SearchResults", "Clicked on service: ${service.name} (ID: ${service.serviceId})")
            lifecycleScope.launch(Dispatchers.IO) {
                UserDataManager.addRecentView(applicationContext, service.serviceId, service.name)
            }
            startActivity(BusDetailsActivity.newIntent(this, service))
        }
        adapter.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        binding.searchResultsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.searchResultsRecyclerView.adapter = adapter
    }

    private fun showNearbySuggestions(fromStop: String, toStop: String) {
        Toast.makeText(this, R.string.finding_nearby_stops, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val resultText = SuggestionManager(this@SearchResultsActivity)
                .getNearbySuggestionsText(fromStop, toStop)
            val dialogBinding = DialogNearbySuggestionsBinding.inflate(layoutInflater)
            dialogBinding.suggestionsText.text = resultText
            AlertDialog.Builder(this@SearchResultsActivity)
                .setTitle(R.string.title_alternative_routes)
                .setView(dialogBinding.root)
                .setPositiveButton(R.string.close, null)
                .show()
        }
    }

    private inline fun <reified T : Parcelable> getParcelableArrayList(key: String): ArrayList<T>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(key, T::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra(key)
        }
    }
}
