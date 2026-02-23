package com.sanchari.bus.ui.activity

import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.appbar.MaterialToolbar
import com.sanchari.bus.R
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
        const val EXTRA_FROM_STOP = "EXTRA_FROM_STOP"
        const val EXTRA_TO_STOP = "EXTRA_TO_STOP"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchResultsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1. Get strings from intent
        val fromStop = intent.getStringExtra(EXTRA_FROM_STOP) ?: "Search"
        val toStop = intent.getStringExtra(EXTRA_TO_STOP) ?: "Results"

        // 3. Set the text on your custom Marquee TextView
        // (Notice we use the nice arrow here on our custom view!)
        binding.tvToolbarTitle.text = "$fromStop → $toStop"
        binding.tvToolbarTitle.isSelected = true // Force selection to start marquee!

        // 4. Handle the back button click (Just once!)
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        // 5. Setup the Menu
        // Note: If you use setSupportActionBar, it's usually better to override
        // onCreateOptionsMenu, but if this standalone inflation works for you, keep it!
        binding.toolbar.inflateMenu(R.menu.menu_search_results)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_suggest_nearby -> {
                    Toast.makeText(this, "Nearby suggestions coming soon!", Toast.LENGTH_SHORT).show()
                    true
                }
                else -> false
            }
        }

        // 6. Setup RecyclerView with results
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

            // Save this to recent views in the background
            lifecycleScope.launch(Dispatchers.IO) {
                UserDataManager.addRecentView(
                    applicationContext, // Get context from the activity
                    service.serviceId,
                    service.name
                )
            }

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