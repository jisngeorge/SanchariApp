package com.sanchari.bus

import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.sanchari.bus.databinding.ActivitySearchResultsBinding

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
            // TODO: Launch BusDetailsActivity here
            Toast.makeText(this, "Opening details for ${service.name}", Toast.LENGTH_SHORT).show()

            // TODO: Save this to recent views in UserDatabase
            // lifecycleScope.launch(Dispatchers.IO) {
            //    UserDataManager.addRecentView(applicationContext, service.serviceId, service.name)
            // }
        }
        binding.searchResultsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.searchResultsRecyclerView.adapter = adapter
    }

    /**
     * Helper function to get ParcelableArrayList in a backward-compatible way.
     */
    private inline fun <reified T : android.os.Parcelable> getParcelableArrayList(key: String): ArrayList<T>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(key, T::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra(key)
        }
    }
}
