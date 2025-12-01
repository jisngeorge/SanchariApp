package com.sanchari.bus.ui.helper

import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.sanchari.bus.data.model.BusService
import com.sanchari.bus.data.model.EditableStop
import com.sanchari.bus.databinding.ActivitySuggestEditBinding
import com.sanchari.bus.ui.activity.ConfirmationActivity
import com.sanchari.bus.ui.adapter.StopEditAdapter
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Handles logic for SuggestEditActivity:
 * - Validations
 * - Dialogs (Instructions, TimePicker)
 * - JSON Generation
 */
class SuggestEditHandler(
    private val activity: AppCompatActivity,
    private val binding: ActivitySuggestEditBinding,
    private val adapter: StopEditAdapter,
    private val editableStops: MutableList<EditableStop>
) {

    companion object {
        private const val TAG = "SuggestEditHandler"
    }

    fun showInstructionsDialog() {
        AlertDialog.Builder(activity)
            .setTitle("Instructions")
            .setMessage("• Add only important stops and junctions to bus routes.\n\n• Please do mention any special cases in notes, like running status on Sundays, deviations from normal route etc.")
            .setPositiveButton("Got it", null)
            .show()
    }

    fun showTimePicker(position: Int) {
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

            val newPosition = adapter.updateTime(position, formattedTime)
            // Use post to ensure RecyclerView layout is updated after sorting before scrolling
            binding.recyclerViewStopsEditor.post {
                binding.recyclerViewStopsEditor.smoothScrollToPosition(newPosition)
            }
        }

        picker.show(activity.supportFragmentManager, "TimePicker")
    }

    fun applyChanges(originalService: BusService?) {
        val serviceName = binding.editTextServiceName.text.toString().trim()
        val serviceType = binding.editTextServiceType.text.toString().trim()
        val stopsData = adapter.getStopsData()
        val editNotes = binding.editTextEditNotes.text.toString().trim()

        if (serviceName.isEmpty()) {
            Toast.makeText(activity, "Please enter a bus service name.", Toast.LENGTH_SHORT).show()
            return
        }
        if (stopsData.isEmpty()) {
            Toast.makeText(activity, "Please add at least one stop.", Toast.LENGTH_SHORT).show()
            return
        }
        if (stopsData.any { it.stopName.isBlank() || it.scheduledTime.isBlank() }) {
            Toast.makeText(activity, "Please fill in all stop names and times.", Toast.LENGTH_SHORT).show()
            return
        }

        val json = buildJsonPayload(originalService, serviceName, serviceType, stopsData, editNotes)
        Log.d(TAG, "Generated JSON: $json")

        val intent = ConfirmationActivity.newIntent(activity, json)
        activity.startActivity(intent)
    }

    private fun buildJsonPayload(
        originalService: BusService?,
        name: String,
        type: String,
        stops: List<EditableStop>,
        editNotes: String
    ): String {
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

            // --- UPDATED: Use "NEW" for new stops ---
            if (stop.originalStopId != -1) {
                stopJson.put("stopId", stop.originalStopId) // Use INT from DB
            } else {
                stopJson.put("stopId", "NEW") // Just "NEW", no index
            }

            stopJson.put("locationName", stop.stopName)
            stopJson.put("scheduledTime", stop.scheduledTime)
            stopJson.put("stopOrder", index + 1)

            // Removed Lat/Long as requested previously

            stopsArray.put(stopJson)
        }

        val timestamp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Instant.now().toString()
        } else {
            android.text.format.DateFormat.format("yyyy-MM-dd'T'HH:mm:ss'Z'", Date()).toString()
        }

        root.put("service", service)
        root.put("stops", stopsArray)
        root.put("editNotes", editNotes)
        root.put("suggestionDate", timestamp)

        if (originalService != null) {
            root.put("type", "edit_schedule")
        } else {
            root.put("type", "new_schedule")
        }

        return root.toString(2)
    }
}