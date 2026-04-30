package com.sanchari.bus.ui.helper

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.sanchari.bus.data.remote.NetworkManager
import com.sanchari.bus.data.local.SuggestionStorageManager
import com.sanchari.bus.data.manager.UserDataManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Handles the background synchronization of offline suggestions/comments.
 */
class UploadManager(private val context: Context) {

    companion object {
        private const val TAG = "UploadManager"
    }

    suspend fun retryPendingUploads() = withContext(Dispatchers.IO) {
        val pendingFiles = SuggestionStorageManager.getPendingSuggestions(context)
        if (pendingFiles.isEmpty()) return@withContext

        Log.i(TAG, "Found ${pendingFiles.size} pending suggestions. Attempting to sync...")
        var failureCount = 0

        for (file in pendingFiles) {
            try {
                val jsonPayload = file.readText()
                val success = NetworkManager.uploadDataToGoogleSheet(context, jsonPayload)

                if (success) {
                    Log.i(TAG, "Successfully synced: ${file.name}")
                    logSubmissionFromPayload(jsonPayload)
                    SuggestionStorageManager.deleteSuggestion(file)
                } else {
                    Log.e(TAG, "Failed to sync: ${file.name}")
                    failureCount++
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing pending file: ${file.name}", e)
                failureCount++
            }
        }

        withContext(Dispatchers.Main) {
            if (failureCount > 0) {
                Toast.makeText(context, "Failed to sync $failureCount offline suggestion(s).", Toast.LENGTH_LONG).show()
            } else if (pendingFiles.isNotEmpty()) {
                Toast.makeText(context, "Synced all offline suggestions.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun logSubmissionFromPayload(jsonPayload: String) {
        try {
            val root = JSONObject(jsonPayload)
            val suggestion = root.getJSONObject("suggestion")
            val service = suggestion.getJSONObject("service")
            val busName = service.optString("name", "Unknown")
            val busType = service.optString("type", "")
            val stopsArray = suggestion.optJSONArray("stops")
            val startingPlace = if (stopsArray != null && stopsArray.length() > 0) {
                stopsArray.getJSONObject(0).optString("locationName", "")
            } else ""
            UserDataManager.addSubmissionLog(context, busName, busType, startingPlace)
        } catch (e: Exception) {
            Log.e(TAG, "Error logging submission from retry", e)
        }
    }
}