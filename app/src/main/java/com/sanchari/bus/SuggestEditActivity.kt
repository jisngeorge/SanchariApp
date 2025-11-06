package com.sanchari.bus

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.sanchari.bus.databinding.ActivitySuggestEditBinding
// --- NEW IMPORTS ---
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
// --- END NEW IMPORTS ---
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID
import java.util.Locale // <-- ADD THIS IMPORT

class SuggestEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySuggestEditBinding
    private lateinit var adapter: StopEditAdapter
    private val editableStops = mutableListOf<EditableStop>()
    private var originalService: BusService? = null

    companion object {
        private const val TAG = "SuggestEditActivity"
        private const val EXTRA_BUS_SERVICE = "EXTRA_BUS_SERVICE"

        // Use this to launch for "Suggest Edit"
        fun newIntentForEdit(context: Context, service: BusService): Intent {
            return Intent(context, SuggestEditActivity::class.java).apply {
                putExtra(EXTRA_BUS_SERVICE, service)
            }
        }

        // Use this to launch for "Add New"
        fun newIntentForNew(context: Context): Intent {
            return Intent(context, SuggestEditActivity::class.java)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySuggestEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Setup Toolbar
        binding.toolbar.setNavigationOnClickListener { finish() }

        // Check if we are editing or creating new
        originalService = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_BUS_SERVICE, BusService::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_BUS_SERVICE)
        }

        setupRecyclerView()

        if (originalService != null) {
            binding.toolbar.title = "Suggest an Edit"
            preFillData(originalService!!)
        } else {
            binding.toolbar.title = "Add New Bus Service"
            // Add one blank stop to start with
            addNewStop()
        }

        binding.buttonAddStop.setOnClickListener {
            addNewStop()
        }

        binding.buttonApplyChanges.setOnClickListener {
            applyChanges()
        }
    }

    private fun setupRecyclerView() {
        // --- MODIFIED: Added onTimeClicked lambda ---
        adapter = StopEditAdapter(
            editableStops,
            onRemoveClicked = { position ->
                // Handle remove click
                editableStops.removeAt(position)
                adapter.notifyItemRemoved(position)
                adapter.notifyItemRangeChanged(position, editableStops.size)
            },
            onTimeClicked = { position ->
                // Handle time click
                showTimePicker(position)
            }
        )
        // --- END MODIFICATION ---
        binding.recyclerViewStopsEditor.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewStopsEditor.adapter = adapter
    }

    private fun preFillData(service: BusService) {
        binding.editTextServiceName.setText(service.name)
        binding.editTextServiceType.setText(service.type)

        // Fetch stops and populate the list
        lifecycleScope.launch(Dispatchers.IO) {
            val stops = SearchManager.getBusStops(applicationContext, service.serviceId)
            withContext(Dispatchers.Main) {
                stops.sortedBy { it.stopOrder }.forEach {
                    editableStops.add(EditableStop.fromBusStop(it))
                }
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun addNewStop() {
        editableStops.add(EditableStop("", "", editableStops.size + 1))
        adapter.notifyItemInserted(editableStops.size - 1)
    }

    // --- NEW FUNCTION ---
    /**
     * Shows a 24-hour MaterialTimePicker dialog.
     */
    private fun showTimePicker(position: Int) {
        // Try to parse existing time, or default
        val currentStop = editableStops[position]
        val (initialHour, initialMinute) = try {
            if (currentStop.scheduledTime.isNotBlank()) {
                val parts = currentStop.scheduledTime.split(":")
                parts[0].toInt() to parts[1].toInt()
            } else {
                9 to 0 // Default to 09:00
            }
        } catch (e: Exception) {
            9 to 0 // Default on parse error
        }

        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(initialHour)
            .setMinute(initialMinute)
            .setTitleText("Select Stop Time")
            .build()

        picker.addOnPositiveButtonClickListener {
            val hour = picker.hour
            val minute = picker.minute

            // Format to "HH:mm" (e.g., "08:30")
            val formattedTime = String.format(Locale.getDefault(), "%02d:%02d", hour, minute)

            // Update the adapter's data
            adapter.updateTime(position, formattedTime)
        }

        picker.show(supportFragmentManager, "TimePicker")
    }
    // --- END NEW FUNCTION ---

    private fun applyChanges() {
        // --- 1. Validate Data ---
        val serviceName = binding.editTextServiceName.text.toString().trim()
        val serviceType = binding.editTextServiceType.text.toString().trim()
        val stopsData = adapter.getStopsData()

        if (serviceName.isEmpty()) {
            Toast.makeText(this, "Please enter a bus service name.", Toast.LENGTH_SHORT).show()
            return
        }
        if (stopsData.isEmpty()) {
            Toast.makeText(this, "Please add at least one stop.", Toast.LENGTH_SHORT).show()
            return
        }
        // --- MODIFIED: Validation text changed to be more specific ---
        if (stopsData.any { it.stopName.isBlank() || it.scheduledTime.isBlank() }) {
            Toast.makeText(this, "Please fill in all stop names and times.", Toast.LENGTH_SHORT).show()
            return
        }
        // --- END MODIFICATION ---

        // --- 2. Build JSON ---
        val json = buildJsonPayload(serviceName, serviceType, stopsData)
        Log.d(TAG, "Generated JSON: $json")

        // --- 3. Launch ConfirmationActivity ---
        val intent = ConfirmationActivity.newIntent(this, json)
        startActivity(intent)
    }

    /**
     * Creates the JSON string based on the user's edits.
     */
    private fun buildJsonPayload(name: String, type: String, stops: List<EditableStop>): String {
        val root = JSONObject()
        val service = JSONObject()
        service.put("serviceId", originalService?.serviceId ?: "NEW-${UUID.randomUUID()}")
        service.put("name", name)
        service.put("type", type)
        // Add placeholders for data not in this form
        service.put("isRunning", 1)
        service.put("lastReport_edTime", 0L)

        val stopsArray = JSONArray()
        stops.forEachIndexed { index, stop ->
            val stopJson = JSONObject()
            // We can't know the real stopId, so we generate a placeholder
            stopJson.put("stopId", "NEW-${index + 1}")
            stopJson.put("locationName", stop.stopName)
            stopJson.put("scheduledTime", stop.scheduledTime) // This now comes from the time picker
            stopJson.put("stopOrder", index + 1)
            // Add placeholder lat/lng
            stopJson.put("latitude", 0.0)
            stopJson.put("longitude", 0.0)
            stopsArray.put(stopJson)
        }

        root.put("service", service)
        root.put("stops", stopsArray)
        return root.toString(2) // Indent by 2 spaces for readability
    }
}