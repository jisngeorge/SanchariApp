package com.sanchari.bus.ui.helper

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.sanchari.bus.data.remote.NetworkManager
import com.sanchari.bus.data.local.SuggestionStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Handles the background synchronization of offline suggestions/comments.
 */
class UploadManager(private val context: Context) {

    companion object {
        private const val TAG = "UploadManager"
    }

    suspend fun retryPendingUploads() {
        val pendingFiles = SuggestionStorageManager.getPendingSuggestions(context)
        if (pendingFiles.isEmpty()) return

        Log.i(TAG, "Found ${pendingFiles.size} pending suggestions. Attempting to sync...")
        var failureCount = 0

        for (file in pendingFiles) {
            try {
                val jsonPayload = file.readText()
                val success = NetworkManager.uploadDataToGoogleSheet(context, jsonPayload)

                if (success) {
                    Log.i(TAG, "Successfully synced: ${file.name}")
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
            } else {
                Toast.makeText(context, "Synced all offline suggestions.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}