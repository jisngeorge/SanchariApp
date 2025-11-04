package com.sanchari.bus

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SuggestionStorageManager {

    private const val TAG = "SuggestionStorageManager"
    private const val SUGGESTIONS_DIR = "suggestions"

    /**
     * Saves the given JSON string to a new file in the app's internal storage.
     *
     * @param context The application context.
     * @param jsonPayload The JSON string to save.
     * @return True if saving was successful, false otherwise.
     */
    fun saveSuggestion(context: Context, jsonPayload: String): Boolean {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "suggestion_$timestamp.json"

        // --- 1. Save to Internal Storage (Critical Path) ---
        val internalSaveSuccess = try {
            // Get the directory for suggestions (e.g., /data/data/com.sanchari.bus/files/suggestions)
            val suggestionsDir = File(context.filesDir, SUGGESTIONS_DIR)
            if (!suggestionsDir.exists()) {
                suggestionsDir.mkdirs()
            }

            val file = File(suggestionsDir, fileName)

            // Write the JSON payload to the file
            file.writeText(jsonPayload)

            Log.i(TAG, "Suggestion saved successfully to: ${file.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error saving suggestion to internal storage", e)
            false
        }

        // Return the success of the internal save.
        return internalSaveSuccess
    }
}

