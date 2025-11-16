package com.sanchari.bus

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
// --- NEW IMPORT ---
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
// --- END NEW IMPORT ---
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
import java.util.Locale

class SuggestEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySuggestEditBinding
    private lateinit var adapter: StopEditAdapter
    private val editableStops = mutableListOf<EditableStop>()
    private var originalService: BusService? = null
    // --- NEW: ItemTouchHelper ---
    private var itemTouchHelper: ItemTouchHelper? = null

    companion object {
        private const val TAG = "SuggestEditActivity"
        private const val EXTRA_BUS_SERVICE = "EXTRA_BUS_SERVICE"

        // --- NEW: Keys for saving instance state ---
        private const val KEY_SERVICE_NAME = "KEY_SERVICE_NAME"
        private const val KEY_SERVICE_TYPE = "KEY_SERVICE_TYPE"
        private const val KEY_STOPS = "KEY_STOPS"
        private const val KEY_EDIT_NOTES = "KEY_EDIT_NOTES"
        // --- END NEW ---

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

        // --- THIS LOGIC IS NOW MOVED ---
        // We set up the RecyclerView first, then populate it
        // based on whether we have saved state or a new intent.
        setupRecyclerView()

        // --- NEW: Check for saved state ---
        if (savedInstanceState != null) {
            Log.i(TAG, "Restoring state from savedInstanceState")
            restoreFromSavedState(savedInstanceState)
        } else {
            // No saved state, so load from intent (edit or new)
            Log.i(TAG, "Loading from intent (new or edit)")
            loadFromIntent()
        }
        // --- END NEW ---


        binding.buttonAddStop.setOnClickListener {
            addNewStop()
        }

        binding.buttonApplyChanges.setOnClickListener {
            applyChanges()
        }
    }

    // --- NEW: Save the user's edits ---
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        Log.i(TAG, "onSaveInstanceState: Saving user edits...")
        // Save the current state of the fields and the adapter
        outState.putString(KEY_SERVICE_NAME, binding.editTextServiceName.text.toString())
        outState.putString(KEY_SERVICE_TYPE, binding.editTextServiceType.text.toString())
        // Get the current list from the adapter
        outState.putParcelableArrayList(KEY_STOPS, ArrayList(adapter.getStopsData()))
        outState.putString(KEY_EDIT_NOTES, binding.editTextEditNotes.text.toString())
    }
    // --- END NEW ---

    // --- NEW: Restore data from the bundle ---
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
            adapter.notifyDataSetChanged()
        }
    }
    // --- END NEW ---

    // --- NEW: Renamed original logic ---
    private fun loadFromIntent() {
        // Check if we are editing or creating new
        originalService = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_BUS_SERVICE, BusService::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_BUS_SERVICE)
        }

        if (originalService != null) {
            binding.toolbar.title = "Suggest an Edit"
            preFillData(originalService!!)
        } else {
            binding.toolbar.title = "Add New Bus Service"
            // Add one blank stop to start with
            addNewStop()
        }
    }
    // --- END NEW ---

    private fun setupRecyclerView() {
        // --- NEW: Setup ItemTouchHelper Callback ---
        val dragCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, // Enable dragging up and down
            0 // We don't want swiping
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                // Notify the adapter of the move
                adapter.onItemMove(viewHolder.adapterPosition, target.adapterPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                // Not used
            }

            // Optional: Change UI on drag start/end
            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.alpha = 0.7f // Make it semi-transparent
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.alpha = 1.0f // Restore full opacity
            }
        }

        // Create the ItemTouchHelper
        itemTouchHelper = ItemTouchHelper(dragCallback)
        // --- END OF NEW ---


        // --- MODIFIED: Pass ItemTouchHelper to adapter ---
        adapter = StopEditAdapter(
            editableStops,
            itemTouchHelper!!, // Pass the helper
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

        // --- NEW: Attach helper to RecyclerView ---
        itemTouchHelper?.attachToRecyclerView(binding.recyclerViewStopsEditor)
        // --- END OF NEW ---
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
        val editNotes = binding.editTextEditNotes.text.toString().trim()

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
        val json = buildJsonPayload(serviceName, serviceType, stopsData, editNotes)
        Log.d(TAG, "Generated JSON: $json")

        // --- 3. Launch ConfirmationActivity ---
        val intent = ConfirmationActivity.newIntent(this, json)
        startActivity(intent)
    }

    /**
     * Creates the JSON string based on the user's edits.
     */
    private fun buildJsonPayload(name: String, type: String, stops: List<EditableStop>, editNotes: String): String {
        val root = JSONObject()
        val service = JSONObject()
        service.put("serviceId", originalService?.serviceId ?: "NEW-${UUID.randomUUID()}")
        service.put("name", name)
        service.put("type", type)
        // Add placeholders for data not in this form
        service.put("isRunning", 1)
        service.put("lastReportedTime", 0L)

        val stopsArray = JSONArray()
        // --- MODIFIED: Use the (now reordered) stops list ---
        stops.forEachIndexed { index, stop ->
            val stopJson = JSONObject()
            // We can't know the real stopId, so we generate a placeholder
            stopJson.put("stopId", "NEW-${index + 1}")
            stopJson.put("locationName", stop.stopName)
            stopJson.put("scheduledTime", stop.scheduledTime) // This now comes from the time picker
            // --- CRITICAL: Save the stopOrder based on the new index ---
            stopJson.put("stopOrder", index + 1)
            // Add placeholder lat/lng
            stopJson.put("latitude", 0.0)
            stopJson.put("longitude", 0.0)
            stopsArray.put(stopJson)
        }

        root.put("service", service)
        root.put("stops", stopsArray)
        root.put("editNotes", editNotes)
        return root.toString(2) // Indent by 2 spaces for readability
    }
}