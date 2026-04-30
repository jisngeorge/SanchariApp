package com.sanchari.bus.ui.helper

import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.sanchari.bus.data.model.BusService
import com.sanchari.bus.data.model.EditableStop
import com.sanchari.bus.data.model.SavedDraft
import com.sanchari.bus.databinding.ActivitySuggestEditBinding
import com.sanchari.bus.ui.activity.ConfirmationActivity
import com.sanchari.bus.ui.activity.SuggestEditActivity
import com.sanchari.bus.ui.adapter.StopEditAdapter
import com.sanchari.bus.data.manager.UserDataManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

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

    fun showEditHistory() {
        val drafts = DraftManager.getDrafts(activity)
        val hasLogs = UserDataManager.getSubmissionLogs(activity).isNotEmpty()

        if (drafts.isEmpty() && !hasLogs) {
            Toast.makeText(activity, "No saved drafts or submissions yet.", Toast.LENGTH_SHORT).show()
            return
        }

        if (drafts.isEmpty() && hasLogs) {
            showSubmissionLogs()
            return
        }

        val displayNames = drafts.map { it.displayName }.toTypedArray()

        AlertDialog.Builder(activity)
            .setTitle("Saved Drafts")
            .setItems(displayNames) { _, which ->
                loadDraftIntoUI(drafts[which])
            }
            .setNeutralButton("Submissions") { _, _ ->
                showSubmissionLogs()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showSubmissionLogs() {
        val logs = UserDataManager.getSubmissionLogs(activity)
        if (logs.isEmpty()) {
            Toast.makeText(activity, "No submissions yet.", Toast.LENGTH_SHORT).show()
            return
        }

        val dateFormat = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
        val items = logs.map { log ->
            val date = dateFormat.format(Date(log.submittedAt))
            "${log.busName} (${log.busType}) - ${log.startingPlace}\n$date"
        }.toTypedArray()

        AlertDialog.Builder(activity)
            .setTitle("Submission History")
            .setItems(items, null)
            .setPositiveButton("OK", null)
            .show()
    }

    fun loadDraftByFileName(fileName: String) {
        val drafts = DraftManager.getDrafts(activity)
        val draft = drafts.find { it.fileName == fileName }
        if (draft != null) {
            loadDraftIntoUI(draft)
        } else {
            Toast.makeText(activity, "Draft not found.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadDraftIntoUI(draft: SavedDraft) {
        try {
            val root = JSONObject(draft.jsonPayload)
            val service = root.getJSONObject("service")

            binding.editTextServiceName.setText(service.optString("name"))
            binding.editTextServiceType.setText(service.optString("type"))
            binding.editTextEditNotes.setText(root.optString("editNotes"))

            editableStops.clear()
            val stopsArray = root.getJSONArray("stops")
            for (i in 0 until stopsArray.length()) {
                val stopObj = stopsArray.getJSONObject(i)
                val stopIdRaw = stopObj.optString("stopId", "NEW")
                val originalStopId = if (stopIdRaw == "NEW") -1 else stopIdRaw.toIntOrNull() ?: -1

                editableStops.add(
                    EditableStop(
                        stopName = stopObj.optString("locationName"),
                        scheduledTime = stopObj.optString("scheduledTime"),
                        stopOrder = stopObj.optInt("stopOrder", i + 1),
                        originalStopId = originalStopId
                    )
                )
            }
            adapter.notifyDataSetChanged()

            val suggestActivity = activity as SuggestEditActivity
            suggestActivity.currentDraftFileName = draft.fileName

            val sId = service.optString("serviceId", "")

            if (sId.isNotBlank()) {
                val bus = BusService(
                    serviceId = sId,
                    name = service.optString("name"),
                    type = service.optString("type"),
                    isRunning = service.optBoolean("isRunning", true),
                    lastReportedTime = service.optLong("lastReportedTime", 0L),
                    fromTime = service.optString("fromTime", ""),
                    toTime = service.optString("toTime", "")
                )
                suggestActivity.setOriginalService(bus)
            } else {
                suggestActivity.setOriginalService(null)
            }

            Toast.makeText(activity, "Draft loaded successfully", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load draft", e)
            Toast.makeText(activity, "Error reading draft file", Toast.LENGTH_SHORT).show()
        }
    }

    fun saveDraft() {
        val suggestActivity = activity as SuggestEditActivity
        val serviceName = binding.editTextServiceName.text.toString().trim()
        val stopsData = adapter.getStopsData()

        if (serviceName.isEmpty() && stopsData.isEmpty()) {
            Toast.makeText(activity, "Nothing to save", Toast.LENGTH_SHORT).show()
            activity.finish()
            return
        }

        val json = buildJsonPayload(
            suggestActivity.getOriginalService(),
            serviceName,
            binding.editTextServiceType.text.toString().trim(),
            stopsData,
            binding.editTextEditNotes.text.toString().trim()
        )

        // Save and ensure we strictly track the exact updated UUID file name
        val newDraftFileName = DraftManager.saveDraft(activity, json, suggestActivity.currentDraftFileName)
        suggestActivity.currentDraftFileName = newDraftFileName

        Toast.makeText(activity, "Draft Saved", Toast.LENGTH_SHORT).show()
        activity.finish()
    }

    fun discardDraft() {
        val suggestActivity = activity as SuggestEditActivity
        AlertDialog.Builder(activity)
            .setTitle("Discard Draft")
            .setMessage("Are you sure you want to discard your current changes? This cannot be undone.")
            .setPositiveButton("Discard") { _, _ ->
                suggestActivity.currentDraftFileName?.let {
                    DraftManager.deleteDraft(activity, it)
                }
                Toast.makeText(activity, "Draft Discarded", Toast.LENGTH_SHORT).show()
                activity.finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun promptSaveDraftAndExit() {
        val suggestActivity = activity as SuggestEditActivity
        val serviceName = binding.editTextServiceName.text.toString().trim()
        val stopsData = adapter.getStopsData()

        if (serviceName.isEmpty() && stopsData.isEmpty()) {
            activity.finish()
            return
        }

        AlertDialog.Builder(activity)
            .setTitle("Save Draft?")
            .setMessage("Do you want to save your progress to history?")
            .setPositiveButton("Save") { _, _ ->
                saveDraft()
            }
            .setNegativeButton("Discard") { _, _ ->
                suggestActivity.currentDraftFileName?.let {
                    DraftManager.deleteDraft(activity, it)
                }
                activity.finish()
            }
            .setNeutralButton("Cancel", null)
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
            Log.e(TAG, "Error parsing time", e)
        }

        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(initialHour)
            .setMinute(initialMinute)
            .setTitleText("Select Stop Time")
            .build()

        picker.addOnPositiveButtonClickListener {
            val formattedTime = String.format(Locale.getDefault(), "%02d:%02d", picker.hour, picker.minute)
            adapter.updateTime(position, formattedTime)
        }
        picker.show(activity.supportFragmentManager, "TimePicker")
    }

    fun applyChanges(originalService: BusService?) {
        val serviceName = binding.editTextServiceName.text.toString().trim()
        val serviceType = binding.editTextServiceType.text.toString().trim()
        val stopsData = adapter.getStopsData()
        val editNotes = binding.editTextEditNotes.text.toString().trim()

        if (serviceName.isEmpty() || stopsData.isEmpty()) {
            Toast.makeText(activity, "Please fill required fields", Toast.LENGTH_SHORT).show()
            return
        }

        val json = buildJsonPayload(originalService, serviceName, serviceType, stopsData, editNotes)
        val intent = ConfirmationActivity.newIntent(activity, json)
        (activity as SuggestEditActivity).currentDraftFileName?.let {
            intent.putExtra("EXTRA_DRAFT_FILE_NAME", it)
        }
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

        val serviceId = originalService?.serviceId ?: "NEW-${UUID.randomUUID()}"

        service.put("serviceId", serviceId)
        service.put("name", name)
        service.put("type", type)
        service.put("isRunning", originalService?.isRunning ?: true)
        service.put("lastReportedTime", originalService?.lastReportedTime ?: 0L)

        val fromTime = if (stops.isNotEmpty()) stops.first().scheduledTime else ""
        val toTime = if (stops.isNotEmpty()) stops.last().scheduledTime else ""

        service.put("fromTime", fromTime)
        service.put("toTime", toTime)

        val stopsArray = JSONArray()
        stops.forEachIndexed { index, stop ->
            val stopJson = JSONObject()
            stopJson.put("stopId", if (stop.originalStopId != -1) stop.originalStopId else "NEW")
            stopJson.put("locationName", stop.stopName.trim())
            stopJson.put("scheduledTime", stop.scheduledTime.trim())
            stopJson.put("stopOrder", index + 1)
            stopsArray.put(stopJson)
        }

        root.put("service", service)
        root.put("stops", stopsArray)
        root.put("editNotes", editNotes)
        root.put("timestamp", System.currentTimeMillis() / 1000)

        root.put("type", if (serviceId.startsWith("NEW-")) "new_schedule" else "edit_schedule")

        return root.toString(2)
    }
}