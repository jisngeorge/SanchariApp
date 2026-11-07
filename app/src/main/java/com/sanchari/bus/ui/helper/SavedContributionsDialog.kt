package com.sanchari.bus.ui.helper

import android.content.Context
import android.database.sqlite.SQLiteException
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sanchari.bus.R
import com.sanchari.bus.data.local.SuggestionStorageManager
import com.sanchari.bus.data.manager.UserDataManager
import com.sanchari.bus.data.model.SavedDraft
import com.sanchari.bus.data.model.SubmissionLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SavedContributionsDialog {
    data class PendingEntry(val file: File, val label: String)

    fun pendingEntries(context: Context): List<PendingEntry> =
        SuggestionStorageManager.getPendingSuggestions(context).map { file ->
            val suggestion = JSONObject(file.readText()).getJSONObject("suggestion")
            val label = suggestion.optJSONObject("service")?.getString("name")
                ?: suggestion.getString("type").replace('_', ' ') +
                    suggestion.optString("serviceId").takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
            PendingEntry(file, context.getString(R.string.pending_upload_label, label))
        }

    fun show(activity: AppCompatActivity, openDraft: (SavedDraft) -> Unit) {
        activity.lifecycleScope.launch {
            try {
                val (drafts, pending, logs) = withContext(Dispatchers.IO) {
                    Triple(DraftManager.getDrafts(activity), pendingEntries(activity),
                        UserDataManager.getSubmissionLogs(activity))
                }
                if (drafts.isEmpty() && pending.isEmpty()) {
                    if (logs.isEmpty()) Toast.makeText(activity, R.string.saved_contributions_empty, Toast.LENGTH_SHORT).show()
                    else showLogs(activity, logs)
                    return@launch
                }
                val builder = AlertDialog.Builder(activity)
                    .setTitle("Saved Drafts")
                    .setItems((drafts.map { it.displayName } + pending.map { it.label }).toTypedArray()) { _, index ->
                        if (index < drafts.size) openDraft(drafts[index])
                        else showPending(activity, pending[index - drafts.size].file)
                    }
                    .setNegativeButton("Close", null)
                if (logs.isNotEmpty()) builder.setNeutralButton("Submissions") { _, _ -> showLogs(activity, logs) }
                builder.show()
            } catch (e: IOException) {
                showError(activity, e)
            } catch (e: JSONException) {
                showError(activity, e)
            } catch (e: SQLiteException) {
                showError(activity, e)
            }
        }
    }

    private fun showPending(activity: AppCompatActivity, file: File) {
        activity.lifecycleScope.launch {
            try {
                val preview = withContext(Dispatchers.IO) {
                    if (!file.exists()) null else ContributionPreview.build(activity,
                        JSONObject(file.readText()).getJSONObject("suggestion"), includeUsername = false)
                        .joinToString("\n") { it.text }
                }
                if (preview == null) {
                    Toast.makeText(activity, R.string.pending_upload_synced, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                AlertDialog.Builder(activity)
                    .setTitle(R.string.pending_upload_title)
                    .setMessage(activity.getString(R.string.pending_upload_help) + "\n\n" + preview)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            } catch (e: IOException) {
                showError(activity, e)
            } catch (e: JSONException) {
                showError(activity, e)
            } catch (e: IllegalArgumentException) {
                showError(activity, e)
            }
        }
    }

    private fun showLogs(activity: AppCompatActivity, logs: List<SubmissionLog>) {
        val format = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
        AlertDialog.Builder(activity)
            .setTitle("Submission History")
            .setItems(logs.map {
                "${it.busName} (${it.busType}) - ${it.startingPlace}\n${format.format(Date(it.submittedAt))}"
            }.toTypedArray(), null)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showError(activity: AppCompatActivity, error: Exception) {
        Log.e("SavedContributions", "Could not load saved contributions", error)
        Toast.makeText(activity, R.string.saved_contributions_failed, Toast.LENGTH_LONG).show()
    }
}
