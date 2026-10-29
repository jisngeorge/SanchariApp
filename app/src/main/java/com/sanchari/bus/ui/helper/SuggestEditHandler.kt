package com.sanchari.bus.ui.helper

import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
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
import com.sanchari.bus.R
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import java.io.IOException
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

    var lastSavedContent: String? = null
    var isDiscarded = false
        private set
    private var pendingSave: Deferred<String>? = null
    private var pendingContent: String? = null
    private var transitionInProgress = false

    fun showInstructionsDialog() {
        AlertDialog.Builder(activity)
            .setTitle(R.string.editor_quick_tips)
            .setMessage(R.string.editor_instructions)
            .setPositiveButton(R.string.editor_got_it, null)
            .show()
    }

    fun showEditHistory() {
        runAfterSaving { showSavedHistory() }
    }

    private fun showSavedHistory() {
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
            binding.editTextServiceType.setText(service.optString("type"), false)
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
            adapter.routeReplaced()

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

            isDiscarded = false
            lastSavedContent = currentContent()
            Toast.makeText(activity, "Draft loaded successfully", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load draft", e)
            Toast.makeText(activity, "Error reading draft file", Toast.LENGTH_SHORT).show()
        }
    }

    fun saveDraft() {
        runAfterSaving(force = true) {
            Toast.makeText(activity,
                if ((activity as SuggestEditActivity).currentDraftFileName == null) "Nothing to save" else "Draft Saved",
                Toast.LENGTH_SHORT).show()
            activity.finish()
        }
    }

    fun saveDraftAndExit() {
        runAfterSaving { activity.finish() }
    }

    fun autoSaveDraft() {
        if (isDiscarded) return
        val save = persistDraft() ?: return
        activity.lifecycleScope.launch {
            try {
                save.await()
            } catch (e: IOException) {
                showSaveError()
            } catch (e: JSONException) {
                showSaveError()
            }
        }
    }

    private fun currentContent(): String {
        val suggestActivity = activity as SuggestEditActivity
        val root = JSONObject(buildJsonPayload(
            suggestActivity.getOriginalService(),
            binding.editTextServiceName.text.toString().trim(),
            binding.editTextServiceType.text.toString().trim(),
            adapter.getStopsData(),
            binding.editTextEditNotes.text.toString().trim(),
            serviceId = suggestActivity.getOriginalService()?.serviceId ?: "NEW-unsaved"
        ))
        root.remove("timestamp")
        val service = root.getJSONObject("service")
        // A new service receives its permanent UUID on the first save.
        if (service.getString("serviceId").startsWith("NEW-")) service.put("serviceId", "NEW")
        return root.toString()
    }

    private fun persistDraft(force: Boolean = false): Deferred<String>? {
        if (isDiscarded) return null
        adapter.commitNames()
        val suggestActivity = activity as SuggestEditActivity
        val name = binding.editTextServiceName.text.toString().trim()
        val type = binding.editTextServiceType.text.toString().trim()
        val notes = binding.editTextEditNotes.text.toString().trim()
        val stops = adapter.getStopsData()
        if (suggestActivity.currentDraftFileName == null && name.isEmpty() && type.isEmpty() &&
            notes.isEmpty() && stops.none { it.stopName.isNotBlank() || it.scheduledTime.isNotBlank() }) return null
        val content = currentContent()
        if (pendingSave != null && pendingContent == content) return pendingSave
        if (!force && pendingSave == null && lastSavedContent == content) return null
        val json = buildJsonPayload(suggestActivity.getOriginalService(), name, type, stops, notes)
        if (suggestActivity.getOriginalService() == null) {
            val service = JSONObject(json).getJSONObject("service")
            suggestActivity.setOriginalService(BusService(
                service.getString("serviceId"), name, type, true, 0L,
                service.getString("fromTime"), service.getString("toTime")
            ))
        }
        val fileName = suggestActivity.currentDraftFileName ?: DraftManager.newDraftFileName()
        suggestActivity.currentDraftFileName = fileName
        val save = DraftManager.saveDraftAsync(activity, json, fileName)
        pendingSave = save
        pendingContent = content
        save.invokeOnCompletion { error ->
            if (error == null) lastSavedContent = content
            if (pendingSave === save) {
                pendingSave = null
                pendingContent = null
            }
        }
        return save
    }

    private fun runAfterSaving(force: Boolean = false, forSubmission: Boolean = false, action: () -> Unit) {
        if (transitionInProgress || isDiscarded) return
        if (!(activity as SuggestEditActivity).prepareEditorForAction(forDraft = true)) return
        transitionInProgress = true
        activity.lifecycleScope.launch {
            try {
                var forceSave = force
                while (true) {
                    if (forSubmission && !validateSubmission()) return@launch
                    val save = persistDraft(forceSave) ?: break
                    forceSave = false
                    save.await()
                }
                if (!isDiscarded) action()
            } catch (e: IOException) {
                showSaveError()
            } catch (e: JSONException) {
                showSaveError()
            } finally {
                transitionInProgress = false
            }
        }
    }

    private fun showSaveError() {
        Toast.makeText(activity, R.string.editor_draft_save_failed, Toast.LENGTH_LONG).show()
    }

    fun discardDraft() {
        AlertDialog.Builder(activity)
            .setTitle("Discard Draft")
            .setMessage("Are you sure you want to discard your current changes? This cannot be undone.")
            .setPositiveButton("Discard") { _, _ ->
                discardAndExit()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun discardAndExit() {
        val suggestActivity = activity as SuggestEditActivity
        isDiscarded = true
        activity.lifecycleScope.launch {
            try {
                suggestActivity.currentDraftFileName?.let {
                    DraftManager.deleteDraftAsync(activity, it).await()
                }
                Toast.makeText(activity, "Draft Discarded", Toast.LENGTH_SHORT).show()
                activity.finish()
            } catch (e: IOException) {
                isDiscarded = false
                Toast.makeText(activity, R.string.editor_draft_discard_failed, Toast.LENGTH_LONG).show()
            }
        }
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
            .setTimeFormat(TimeFormat.CLOCK_12H)
            .setHour(initialHour)
            .setMinute(initialMinute)
            .setTitleText("Select Stop Time")
            .build()

        picker.addOnPositiveButtonClickListener {
            val formattedTime = String.format(Locale.ROOT, "%02d:%02d", picker.hour, picker.minute)
            adapter.updateTime(position, formattedTime)
        }
        picker.show(activity.supportFragmentManager, "TimePicker")
    }

    fun applyChanges() {
        if (!validateSubmission()) return
        runAfterSaving(forSubmission = true) {
            val suggestActivity = activity as SuggestEditActivity
            val json = buildJsonPayload(
                suggestActivity.getOriginalService(), binding.editTextServiceName.text.toString().trim(),
                binding.editTextServiceType.text.toString().trim(), adapter.getStopsData(),
                binding.editTextEditNotes.text.toString().trim()
            )
            val intent = ConfirmationActivity.newIntent(activity, json)
            suggestActivity.currentDraftFileName?.let {
                intent.putExtra(ConfirmationActivity.EXTRA_DRAFT_FILE_NAME, it)
            }
            activity.startActivity(intent)
        }
    }

    private fun validateSubmission(): Boolean {
        if (!(activity as SuggestEditActivity).prepareEditorForAction()) return false
        val serviceName = binding.editTextServiceName.text.toString().trim()
        val stopsData = adapter.getStopsData()

        if (serviceName.isEmpty() || stopsData.isEmpty() || stopsData.any { it.stopName.isBlank() }) {
            Toast.makeText(activity, "Please fill required fields", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun buildJsonPayload(
        originalService: BusService?,
        name: String,
        type: String,
        stops: List<EditableStop>,
        editNotes: String,
        serviceId: String = originalService?.serviceId ?: "NEW-${UUID.randomUUID()}"
    ): String {
        val root = JSONObject()
        val service = JSONObject()

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