package com.sanchari.bus.ui.helper

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
            .setMessage("• Add only important stops and junctions to bus routes.\n\n" +
                    "• Please use notes to convey any other details, like running status on Sundays, stops for which time unknown etc.\n\n" +
                    "• For naming the bus, you can use place, depo names and numbers to make it easy to identify.\n\n" +
                    "• For adding a halt, add same stop name with arrival and departure time.")
            .setPositiveButton("Got it", null)
            .show()
    }

    fun showTimePicker(position: Int) {
        val currentStop = editableStops[position]
        var initialHour = 9
        var initialMinute = 0

        try {
            if (currentStop.scheduledTime.isNotBlank()) {
                val parts = currentStop.scheduledTime.split(":")
                if (parts.size == 2) {
                    initialHour = parts[0].trim().toInt()
                    initialMinute = parts[1].trim().toInt()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing time: ${currentStop.scheduledTime}", e)
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

            // Just update the time; auto-sorting is handled by adapter.
            // --- REMOVED: Scrolling logic ---
            adapter.updateTime(position, formattedTime)
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

            // Use "NEW" for new stops, preserve ID for existing
            if (stop.originalStopId != -1) {
                stopJson.put("stopId", stop.originalStopId)
            } else {
                stopJson.put("stopId", "NEW")
            }

            stopJson.put("locationName", stop.stopName.trim())
            stopJson.put("scheduledTime", stop.scheduledTime.trim())
            stopJson.put("stopOrder", index + 1)

            stopsArray.put(stopJson)
        }

        // Use Unix Timestamp (Seconds)
        val timestamp = System.currentTimeMillis() / 1000

        root.put("service", service)
        root.put("stops", stopsArray)
        root.put("editNotes", editNotes)
        root.put("timestamp", timestamp)

        if (originalService != null) {
            root.put("type", "edit_schedule")
        } else {
            root.put("type", "new_schedule")
        }

        return root.toString(2)
    }
}