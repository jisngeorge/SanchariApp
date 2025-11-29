package com.sanchari.bus.ui.activity

import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.sanchari.bus.data.model.BusService
import com.sanchari.bus.ui.adapter.BusServiceAdapter
import com.sanchari.bus.data.manager.UserDataManager
import com.sanchari.bus.databinding.ActivitySearchResultsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SearchResultsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchResultsBinding
    private lateinit var adapter: BusServiceAdapter

    companion object {
        const val EXTRA_SEARCH_RESULTS = "EXTRA_SEARCH_RESULTS"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchResultsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Setup toolbar
        binding.toolbar.setNavigationOnClickListener {
            finish() // Go back to MainActivity
        }

        // Get results from intent
        val results = getParcelableArrayList<BusService>(EXTRA_SEARCH_RESULTS)

        if (results.isNullOrEmpty()) {
            Log.e("SearchResults", "No search results passed to activity.")
            Toast.makeText(this, "No results found.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setupRecyclerView(results)
    }

    private fun setupRecyclerView(results: List<BusService>) {
        adapter = BusServiceAdapter(results) { service ->
            // Handle click on a bus service
            Log.i("SearchResults", "Clicked on service: ${service.name} (ID: ${service.serviceId})")

            // --- ADDED THIS BLOCK ---
            // Save this to recent views in the background
            // This is tied to the Activity's lifecycle
            lifecycleScope.launch(Dispatchers.IO) {
                UserDataManager.addRecentView(
                    applicationContext, // Get context from the activity
                    service.serviceId,
                    service.name
                )
            }
            // --- END OF BLOCK ---

            // Launch BusDetailsActivity
            val intent = BusDetailsActivity.newIntent(this, service)
            startActivity(intent)
        }
        binding.searchResultsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.searchResultsRecyclerView.adapter = adapter
    }

    /**
     * Helper function to get ParcelableArrayList in a backward-compatible way.
     */
    private inline fun <reified T : Parcelable> getParcelableArrayList(key: String): ArrayList<T>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(key, T::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra(key)
        }
    }
}

