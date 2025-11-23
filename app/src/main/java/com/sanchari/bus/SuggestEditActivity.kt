package com.sanchari.bus

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sanchari.bus.databinding.ActivitySuggestEditBinding
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID
import java.util.Locale
// --- NEW IMPORTS ---
import java.time.Instant
import java.util.Date
// --- END NEW IMPORTS ---

class SuggestEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySuggestEditBinding
    private lateinit var adapter: StopEditAdapter
    private val editableStops = mutableListOf<EditableStop>()
    private var originalService: BusService? = null
    private var itemTouchHelper: ItemTouchHelper? = null

    companion object {
        private const val TAG = "SuggestEditActivity"
        private const val EXTRA_BUS_SERVICE = "EXTRA_BUS_SERVICE"

        private const val KEY_SERVICE_NAME = "KEY_SERVICE_NAME"
        private const val KEY_SERVICE_TYPE = "KEY_SERVICE_TYPE"
        private const val KEY_STOPS = "KEY_STOPS"
        private const val KEY_EDIT_NOTES = "KEY_EDIT_NOTES"

        fun newIntentForEdit(context: Context, service: BusService): Intent {
            return Intent(context, SuggestEditActivity::class.java).apply {
                putExtra(EXTRA_BUS_SERVICE, service)
            }
        }

        fun newIntentForNew(context: Context): Intent {
            return Intent(context, SuggestEditActivity::class.java)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySuggestEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        setupRecyclerView()

        if (savedInstanceState != null) {
            Log.i(TAG, "Restoring state from savedInstanceState")
            restoreFromSavedState(savedInstanceState)
        } else {
            Log.i(TAG, "Loading from intent (new or edit)")
            loadFromIntent()
        }

        binding.buttonAddStop.setOnClickListener {
            addNewStop()
        }

        binding.buttonApplyChanges.setOnClickListener {
            applyChanges()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        Log.i(TAG, "onSaveInstanceState: Saving user edits...")
        outState.putString(KEY_SERVICE_NAME, binding.editTextServiceName.text.toString())
        outState.putString(KEY_SERVICE_TYPE, binding.editTextServiceType.text.toString())
        outState.putParcelableArrayList(KEY_STOPS, ArrayList(adapter.getStopsData()))
        outState.putString(KEY_EDIT_NOTES, binding.editTextEditNotes.text.toString())
    }

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

    private fun loadFromIntent() {
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
            addNewStop()
        }
    }

    private fun setupRecyclerView() {
        val dragCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                adapter.onItemMove(viewHolder.adapterPosition, target.adapterPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) { }

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.alpha = 0.7f
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.alpha = 1.0f
            }
        }

        itemTouchHelper = ItemTouchHelper(dragCallback)

        adapter = StopEditAdapter(
            editableStops,
            itemTouchHelper!!,
            onRemoveClicked = { position ->
                editableStops.removeAt(position)
                adapter.notifyItemRemoved(position)
                adapter.notifyItemRangeChanged(position, editableStops.size)
            },
            onTimeClicked = { position ->
                showTimePicker(position)
            }
        )
        binding.recyclerViewStopsEditor.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewStopsEditor.adapter = adapter

        itemTouchHelper?.attachToRecyclerView(binding.recyclerViewStopsEditor)
    }

    private fun preFillData(service: BusService) {
        binding.editTextServiceName.setText(service.name)
        binding.editTextServiceType.setText(service.type)

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

    private fun showTimePicker(position: Int) {
        val currentStop = editableStops[position]
        val (initialHour, initialMinute) = try {
            if (currentStop.scheduledTime.isNotBlank()) {
                val parts = currentStop.scheduledTime.split(":")
                parts[0].toInt() to parts[1].toInt()
            } else {
                9 to 0
            }
        } catch (e: Exception) {
            9 to 0
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
            val formattedTime = String.format(Locale.getDefault(), "%02d:%02d", hour, minute)
            adapter.updateTime(position, formattedTime)
        }

        picker.show(supportFragmentManager, "TimePicker")
    }

    private fun applyChanges() {
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
        if (stopsData.any { it.stopName.isBlank() || it.scheduledTime.isBlank() }) {
            Toast.makeText(this, "Please fill in all stop names and times.", Toast.LENGTH_SHORT).show()
            return
        }

        val json = buildJsonPayload(serviceName, serviceType, stopsData, editNotes)
        Log.d(TAG, "Generated JSON: $json")

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
        service.put("isRunning", 1)
        service.put("lastReportedTime", 0L)

        val stopsArray = JSONArray()
        stops.forEachIndexed { index, stop ->
            val stopJson = JSONObject()
            stopJson.put("stopId", "NEW-${index + 1}")
            stopJson.put("locationName", stop.stopName)
            stopJson.put("scheduledTime", stop.scheduledTime)
            stopJson.put("stopOrder", index + 1)
            stopJson.put("latitude", 0.0)
            stopJson.put("longitude", 0.0)
            stopsArray.put(stopJson)
        }

        // --- ADDED: Timestamp logic ---
        val timestamp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Instant.now().toString()
        } else {
            android.text.format.DateFormat.format("yyyy-MM-dd'T'HH:mm:ss'Z'", Date()).toString()
        }

        root.put("service", service)
        root.put("stops", stopsArray)
        root.put("editNotes", editNotes)
        root.put("suggestionDate", timestamp) // --- ADDED: Date field ---

        // Distinguish edit types
        if (originalService != null) {
            root.put("type", "edit_schedule")
        } else {
            root.put("type", "new_schedule")
        }

        return root.toString(2)
    }
}