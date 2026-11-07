package com.sanchari.bus.ui.helper

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.sanchari.bus.data.remote.NetworkManager
import com.sanchari.bus.data.local.SuggestionStorageManager
import com.sanchari.bus.data.manager.UserDataManager
import com.sanchari.bus.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

/**
 * Handles the background synchronization of offline suggestions/comments.
 */
class UploadManager(
    private val context: Context,
    private val upload: suspend (String) -> Boolean = {
        NetworkManager.uploadDataToGoogleSheet(context, it)
    }
) {

    companion object {
        private const val TAG = "UploadManager"
        private val retryMutex = Mutex()
    }

    suspend fun retryPendingUploads() = withContext(Dispatchers.IO) {
        retryMutex.withLock {
            val pendingFiles = try {
                SuggestionStorageManager.getPendingSuggestions(context)
            } catch (e: IOException) {
                Log.e(TAG, "Could not read pending submissions", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, R.string.saved_contributions_failed, Toast.LENGTH_LONG).show()
                }
                return@withLock
            }
            if (pendingFiles.isEmpty()) return@withLock

            Log.i(TAG, "Found ${pendingFiles.size} pending suggestions. Attempting to sync...")
            var failureCount = 0

            for (file in pendingFiles) {
                try {
                    val jsonPayload = file.readText()
                    val success = upload(jsonPayload)
                    currentCoroutineContext().ensureActive()

                    if (success) {
                        Log.i(TAG, "Successfully synced: ${file.name}")
                        logSubmissionFromPayload(jsonPayload)
                        if (!SuggestionStorageManager.deleteSuggestion(file)) {
                            Log.e(TAG, "Could not remove uploaded pending submission")
                            failureCount++
                        }
                    } else {
                        Log.e(TAG, "Failed to sync: ${file.name}")
                        failureCount++
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing pending file: ${file.name}", e)
                    failureCount++
                }
            }

            withContext(Dispatchers.Main) {
                if (failureCount > 0) {
                    Toast.makeText(context, context.getString(R.string.pending_upload_failed, failureCount), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, "Synced all offline suggestions.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun logSubmissionFromPayload(jsonPayload: String) {
        try {
            val root = JSONObject(jsonPayload)
            val suggestion = root.getJSONObject("suggestion")
            if (suggestion.optString("type") !in listOf("new_schedule", "edit_schedule")) return
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