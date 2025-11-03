package com.sanchari.bus

import android.content.Context
import android.content.Intent
import android.os.Build // Import for version check
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.sanchari.bus.databinding.ActivityBusDetailsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BusDetailsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBusDetailsBinding
    private var busService: BusService? = null
    private lateinit var busStopAdapter: BusStopAdapter

    companion object {
        private const val EXTRA_BUS_SERVICE = "EXTRA_BUS_SERVICE"
        private const val TAG = "BusDetailsActivity"

        fun newIntent(context: Context, service: BusService): Intent {
            return Intent(context, BusDetailsActivity::class.java).apply {
                putExtra(EXTRA_BUS_SERVICE, service)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBusDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set up the toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Bus Stops"

        // Get the bus service details
        // --- UPDATED: Replaced deprecated getParcelableExtra ---
        busService = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_BUS_SERVICE, BusService::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_BUS_SERVICE)
        }
        // --- End of update ---

        if (busService == null) {
            Toast.makeText(this, "Error: Bus details not found.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setupRecyclerView()
        loadBusStops()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle the toolbar back button
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun setupRecyclerView() {
        busStopAdapter = BusStopAdapter(emptyList())
        binding.recyclerViewBusStops.apply {
            layoutManager = LinearLayoutManager(this@BusDetailsActivity)
            adapter = busStopAdapter
        }
    }

    private fun loadBusStops() {
        val serviceId = busService?.serviceId ?: return
        showLoading(true)

        lifecycleScope.launch(Dispatchers.IO) {
            val stops = SearchManager.getBusStops(applicationContext, serviceId)

            withContext(Dispatchers.Main) {
                showLoading(false)
                if (stops.isEmpty()) {
                    showError("No stops found for this service.")
                } else {
                    binding.recyclerViewBusStops.visibility = View.VISIBLE
                    binding.textViewError.visibility = View.GONE
                    busStopAdapter.updateStops(stops)
                }
            }
        }
    }

    private fun showLoading(isLoading: Boolean) {
        binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        if (isLoading) {
            binding.recyclerViewBusStops.visibility = View.GONE
            binding.textViewError.visibility = View.GONE
        }
    }

    private fun showError(message: String) {
        binding.recyclerViewBusStops.visibility = View.GONE
        binding.textViewError.visibility = View.VISIBLE
        binding.textViewError.text = message
    }
}

